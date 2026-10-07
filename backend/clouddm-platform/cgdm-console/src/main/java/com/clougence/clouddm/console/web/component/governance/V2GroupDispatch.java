/*
 * Copyright 2026 杭州开云集致科技有限公司
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.clougence.clouddm.console.web.component.governance;

import com.clougence.clouddm.api.console.autoexec.ErrorStrategy;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.service.govticket.GovTicketV2Service;
import com.clougence.clouddm.console.web.util.DmTeamUtils;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;

/**
 * B7 N1b (design §2.3): the SHARED per-group v2 dispatch decision — the single point where the
 * compatibility contract <b>segments → flags → legacy</b> lives. Before this extraction the
 * decision order was mirrored at three loop sites (systemAutoConfirmForV2 / the N0 human-confirm
 * dispatchV2GroupsOnConfirm / its SYSTEM copy) and would drift the moment the DML_BATCH segment
 * type entered the confirm chain; now every main-chain dispatch point converges here (the fourth
 * point, the sweep probe, keeps its own runtime-check-precedes-flag-check order by contract, and
 * retryGroup keeps its segments → flags → legacy ladder around resumeGroupUnderLock).
 * <p>
 * <b>Shape ruling (the equivalence proof)</b>: this is a STATIC helper taking the callers' own
 * collaborators — NOT a component with injected fields. Both frozen pin suites inject their mocks
 * into the CALLER's fields (GovAutoAdvanceServiceTest: ticketSegmentService / govTicketV2Service /
 * autoExecService / schemaSnapshotService on the impl; ProdDmlConfirmV2DispatchTest: the confirm
 * loop's mocks) and verify the calls land on them — passing the caller's instances through keeps
 * every pin green with ZERO test change, which IS the extraction's equivalence proof (B6
 * GovAutoAdvance 12 + N0 confirm 17). It also preserves the two callers' DIFFERENT createGroupJob
 * projections: the SYSTEM chain launches through the 6-arg overload (IMMEDIATE by construction),
 * the human-confirm chain through the extended 10-arg overload (execType/execTime/retry
 * passthrough) — each caller supplies its own {@link GroupJobLauncher}, so neither pin's verified
 * signature changes.
 * <p>
 * Branch semantics (verbatim from the converged sites):
 * <ul>
 * <li><b>segments</b>: the segment PLAN in precheck_result wins — the orchestrator owns the whole
 *     dispatch (ticket lock + PENDING/FAILED CAS + runtime rebuild + first-segment launch, its
 *     own transaction). Covers GHOST and DML_BATCH plans (B7: isSegmentedPlan widened to
 *     hasRoutedSegment);</li>
 * <li><b>flags</b>: an M1-shaped ticket (positional ghostRouteFlags WITHOUT a plan — existing
 *     single-ALTER tickets) keeps the single-session ghost dispatch byte-for-byte. For B7 the
 *     flags' semantics WIDENED to "has non-direct-execution segments" (裁决 #12) but a
 *     DML_BATCH mark ALWAYS writes a plan, so this branch stays ghost-only in practice
 *     (defensive equivalence);</li>
 * <li><b>legacy</b>: unmarked groups keep the original autoexec path — captureIfMissing snapshot
 *     → fresh jobBizId → the caller's createGroupJob+startJob projection (regression pin).</li>
 * </ul>
 * The eligibility filter (PENDING/FAILED), the per-group failure semantics (auto-advance
 * log-continue vs confirm strict-cleanup) and the loop-level markExecutionDispatched all stay at
 * the CALLERS — this helper dispatches exactly one eligible group.
 */
public final class V2GroupDispatch {

    private V2GroupDispatch() {
    }

    /** which branch of the decision order fired (the confirm cleanup keys on it). */
    public enum Path { SEGMENT, GHOST_FLAG, LEGACY }

    /** dispatch result: the taken path + the legacy branch's job bizId (null otherwise). */
    public static final class Outcome {
        private final Path   path;
        private final String jobBizId;

        Outcome(Path path, String jobBizId) {
            this.path = path;
            this.jobBizId = jobBizId;
        }

        public Path getPath() { return path; }
        /** LEGACY only: the created group job's bizId (the confirm cleanup's tracked key). */
        public String getJobBizId() { return jobBizId; }
    }

    /**
     * The caller's legacy-branch job projection (createGroupJob + startJob). The confirm caller
     * registers its strict-cleanup tracking INSIDE the launcher (create → track → start order —
     * a startJob failure must still leave the created job deletable, the N0 pin); the bizId is
     * generated here so both branches share one generator discipline.
     */
    @FunctionalInterface
    public interface GroupJobLauncher {
        void launch(DmTicketDbStmtDO group, String jobBizId, String operatorUid);
    }

    /**
     * Dispatch ONE eligible group through the decision order.
     *
     * @param position the group's id-ASC index within the ticket (the GhostRouteFlags positional key)
     * @param mo       the parsed ticketInfo (flags carrier; nullable → flags branch never fires)
     * @param ghostVia the dispatch-origin audit annotation (GHOST_VIA_AUTO_ROUTE on every
     *                 main-chain site; retryGroup keeps its own GHOST_VIA_RETRY ladder)
     */
    public static Outcome dispatch(DmApprovalDO ticket, DmTicketDbStmtDO group, int position, ApprovalMO mo,
                                   boolean transactional, ErrorStrategy errorStrategy,
                                   String languageTag, String operatorUid, String ghostVia,
                                   TicketSegmentService segmentService, GovTicketV2Service govTicketV2Service,
                                   SchemaSnapshotService schemaSnapshotService, GroupJobLauncher legacyLauncher) {
        // 1. segments — the plan wins (dispatch authority, B6 M2 decision order)
        if (TicketSegment.isSegmentedPlan(group.getPrecheckResult())) {
            segmentService.dispatchFirstSegment(ticket, group, transactional, errorStrategy, languageTag, operatorUid);
            return new Outcome(Path.SEGMENT, null);
        }
        // 2. flags — the M1 degenerate shape (no plan): single-session ghost dispatch
        if (GhostRouteFlags.isRouted(mo, position)) {
            govTicketV2Service.dispatchGhostGroup(ticket, group, ghostVia);
            return new Outcome(Path.GHOST_FLAG, null);
        }
        // 3. legacy — the unmarked group keeps the original autoexec path byte-for-byte
        schemaSnapshotService.captureIfMissing(group, ticket.getId());
        String jobBizId = DmTeamUtils.nextExecJobBizId();
        legacyLauncher.launch(group, jobBizId, operatorUid);
        return new Outcome(Path.LEGACY, jobBizId);
    }
}
