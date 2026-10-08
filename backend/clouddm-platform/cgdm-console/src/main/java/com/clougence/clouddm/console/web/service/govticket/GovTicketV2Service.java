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
package com.clougence.clouddm.console.web.service.govticket;

import java.util.List;

import com.clougence.clouddm.console.web.model.fo.govticket.GovTicketV2CheckFO;
import com.clougence.clouddm.console.web.model.fo.govticket.GovTicketV2ResubmitFO;
import com.clougence.clouddm.console.web.model.fo.govticket.GovTicketV2ReturnTicketFO;
import com.clougence.clouddm.console.web.model.fo.govticket.GovTicketV2SubmitFO;
import com.clougence.clouddm.console.web.model.vo.dbpair.DbPairVO;
import com.clougence.clouddm.console.web.model.vo.dbpair.DbServiceVO;
import com.clougence.clouddm.console.web.model.vo.govticket.GovTicketV2CheckVO;
import com.clougence.clouddm.console.web.model.vo.govticket.GovTicketV2GroupVO;
import com.clougence.clouddm.console.web.model.vo.govticket.GovTicketV2SubmitVO;

public interface GovTicketV2Service {

    /** B6 M1: dispatch-origin annotations of the ghost auto-route GHOST_EXECUTE audit row. */
    String GHOST_VIA_AUTO_ROUTE = "TICKET_AUTO_ROUTE";
    String GHOST_VIA_RETRY      = "TICKET_RETRY";
    /** B6 M2: mid-chain dispatch of a GHOST segment by the segment orchestrator (advance/first). */
    String GHOST_VIA_SEGMENT_CHAIN = "TICKET_SEGMENT_CHAIN";

    /** Read-only precheck preview (no persistence). */
    GovTicketV2CheckVO check(String puid, String uid, GovTicketV2CheckFO fo);

    /** Authoritative submit: server re-runs precheck (blocking), creates ticket + statement groups. */
    GovTicketV2SubmitVO submit(String puid, String uid, GovTicketV2SubmitFO fo);

    /** Retry a single failed group (rebuild its exec job). */
    void retryGroup(String puid, String uid, long groupId);

    /**
     * B7 N2 (design §7.4): retryGroup with the window-crash manual verdict —
     * {@code dmlBatchResolution} ∈ {RERUN_BATCH, SKIP_BATCH} (nullable). REQUIRED iff the resume
     * target is a DML_BATCH segment whose frozen summary carries {@code unresolvedIntent}
     * (missing/invalid → GOV_TICKET_V2_DML_BATCH_RESOLUTION_REQUIRED); ignored by every other
     * target shape. RERUN = re-execute the unresolved batch from the inherited cursor; SKIP =
     * re-derive one boundary and advance the cursor WITHOUT executing (the evidence lands in the
     * checkpoint + segment summary). The verdict is trailed twice (event DML_BATCH_RESOLUTION +
     * the DML_BATCH_EXECUTE audit row annotated resolution+intentSeq — 四点注册复用, §12).
     * The 3-arg form delegates here with a null resolution (byte-identical legacy behavior).
     */
    void retryGroup(String puid, String uid, long groupId, String dmlBatchResolution);

    /**
     * B6 M1 (design §6.3): dispatch a ghost-marked PRE_DDL group through the GHOST tool session
     * (auto-route). Mirrors the retryGroup "build execution + markExecutionDispatched" shape:
     * launch the session bound to ticketId/groupId → group row EXECUTING → PREPARATION closed →
     * GHOST_EXECUTE audit in the submitter's name. Internal surface — callers are the SYSTEM
     * auto-advance chain and the retryGroup ghost variant, never a controller.
     *
     * @param via audit annotation of the dispatch origin (TICKET_AUTO_ROUTE / TICKET_RETRY)
     */
    void dispatchGhostGroup(com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO ticket,
                            com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO group,
                            String via);

    /**
     * B6 M2 (design §6.3): dispatch ONE GHOST segment of a segmented group — the in-chain
     * variant of {@link #dispatchGhostGroup}. Caller contract: the ticket row lock is ALREADY
     * held and the group/segment state transitions are owned by the caller
     * (TicketSegmentService.advanceSegment / dispatchFirstSegment) — no PENDING/FAILED CAS here
     * (the group is mid-chain EXECUTING; the segment-status CAS already passed under the same
     * lock). Launches the session bound to (ticketId, groupId, segmentIndex), records
     * sessionId/taskId ON the passed segment instance (the caller persists the runtime), writes
     * the group row EXECUTING and audits GHOST_EXECUTE in the submitter's name.
     */
    void dispatchGhostSegment(com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO ticket,
                              com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO group,
                              com.clougence.clouddm.console.web.component.governance.TicketSegment segment,
                              String via);

    /**
     * B7 N1b (design §5.3/§9 dispatch seam): dispatch ONE DML_BATCH segment — the structural
     * mirror of {@link #dispatchGhostSegment} with the AsyncTask replacing the tool session.
     * Caller contract identical: the ticket row lock is ALREADY held and the caller owns the
     * segment state transitions (no group CAS here). Assembles the task configData (launch
     * freeze: statement text + batch key + effectiveParams snapshot + binding triple + the
     * inherited-or-fresh checkpoint), submits the AsyncTask (bizType=DML_BATCH,
     * processType=PROGRESS, showInDock), records taskId ON the passed segment instance (the
     * caller persists the runtime — the completion CAS taskId cross-check key, debt #5), writes
     * the group row EXECUTING and audits DML_BATCH_EXECUTE (HIGH) in the submitter's name —
     * audit failure never rolls back the already-submitted task (GHOST_EXECUTE discipline).
     * <p>
     * Failure contract: submitTask failure THROWS with no execution-body residue (a batch task
     * has no session — the inverse direction of the ghost closeQuietly coupling); the dispatch
     * transaction rolls back and the segment stays for the sweep's PENDING re-dispatch row.
     *
     * @param via audit annotation of the dispatch origin (the shared TICKET_* constants)
     */
    void dispatchBatchSegment(com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO ticket,
                              com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO group,
                              com.clougence.clouddm.console.web.component.governance.TicketSegment segment,
                              String via);

    /** List statement groups for a ticket (detail page). */
    List<GovTicketV2GroupVO> groupList(String puid, String uid, long ticketId);

    /**
     * Return a ticket (EXEC_FAIL/WAIT_EXEC with FAILED groups) to REVISION for SQL editing.
     * Console path: enforces the three-pronged identity check (owner ∨ primary ∨ DM_DAUTH_TICKET@bindDsId).
     */
    void returnTicket(String puid, String uid, GovTicketV2ReturnTicketFO fo);

    /**
     * Return a ticket (EXEC_FAIL/WAIT_EXEC with FAILED groups) to REVISION — open command variant.
     * Skips the three-pronged identity check; tenant-level auth (AK/SK is the credential).
     * eventData.via = EXTERNAL_COMMAND. Status guards / REVISION / IM notify are reused from the console path.
     */
    void returnTicketOpenCommand(String puid, String uid, long ticketId, String reason);

    /**
     * Resubmit a REVISION ticket with modified SQL per FAILED group.
     * Re-runs authoritative precheck, writes version audit, updates sql_content in-place
     * (stmt id unchanged), resets groups to PENDING, transitions ticket back to WAIT_APPROVAL
     * where the existing scheduler auto-advance chain picks up.
     */
    void resubmit(String puid, String uid, GovTicketV2ResubmitFO fo);

    /**
     * Resubmit a REVISION ticket — open command variant.
     * Skips the owner-only identity check; tenant-level auth (AK/SK is the credential).
     * For External-channel tickets, the resubmit goes directly to WAIT_EXEC (Q2 方案 A),
     * bypassing the approval surface entirely — the original external approval still stands
     * (execution failure ≠ approval rejected). Internal/DingTalk channels use the same
     * resubmit logic as the console path (regression pin).
     */
    void resubmitOpenCommand(String puid, String uid, GovTicketV2ResubmitFO fo);

    /** Available pairs for the ticket creation dropdown. */
    List<DbPairVO> availablePairs(String puid, String side);

    /** Available services for the ticket creation dropdown. */
    List<DbServiceVO> availableServices(String puid);
}
