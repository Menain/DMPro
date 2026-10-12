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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.api.console.autoexec.ErrorStrategy;
import com.clougence.clouddm.console.web.component.execute.AutoExecService;
import com.clougence.clouddm.console.web.global.i18n.DmI18nUtils;
import com.clougence.clouddm.console.web.global.i18n.I18nDmMsgKeys;
import com.clougence.clouddm.console.web.service.ghost.GhostResumeProbe;
import com.clougence.clouddm.console.web.service.ghost.GhostService;
import com.clougence.clouddm.console.web.service.ghost.asyntask.GhostAsyncTaskConfig;
import com.clougence.clouddm.console.web.service.govticket.GovTicketV2Service;
import com.clougence.clouddm.console.web.util.DmTeamUtils;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.ExecutionDal;
import com.clougence.clouddm.platform.dal.access.TicketDbStmtDal;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.execution.AsyncTaskStatus;
import com.clougence.clouddm.platform.dal.model.execution.AutoExecJobStatus;
import com.clougence.clouddm.platform.dal.model.execution.DmExecAsyncTaskDO;
import com.clougence.clouddm.platform.dal.model.execution.DmExecAutoJobDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;
import com.clougence.utils.JsonUtils;
import com.clougence.utils.StringUtils;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * B6 M2 (design §6.3/§6.6): the mixed-statement SEGMENT ORCHESTRATOR of a PRE_DDL group —
 * the "advanceSegment single service method" ruling: serial segment progression driven by a
 * hybrid of in-callback chained advance (the handleReleaseJobCompletion precedent) and the
 * WAIT_EXEC sweep as the contractual safety net (governance-contracts: never pure event-driven).
 * <p>
 * <b>Lock contract</b>: every {@code *UnderLock} entry assumes the CALLER already holds the
 * ticket row lock ({@code approvalMapper.selectByIdForUpdate}) — the three writer event sources
 * (autoexec completion callback, ghost AsyncTask terminal hook, sweep repair) all take that
 * single lock first, so segment progression is serialized per ticket with no deadlock (the
 * handleV2JobCompletion concurrency contract, extended to segments). The segment status CAS is
 * re-read UNDER the lock (fresh group row — never a pre-lock snapshot, the M1 check F1 lesson):
 * advancement only proceeds while the target segment is still EXECUTING, giving exactly-once
 * semantics against callback replays, dual-event-source races and sweep overlaps.
 * <p>
 * <b>Aggregation split</b>: this service NEVER drives the ticket state machine — it writes
 * segment terminals (+ summary), dispatches the next segment, or writes the GROUP terminal when
 * the last segment finished; the caller's existing all-groups aggregation tail (unchanged
 * handleV2JobCompletion / onGhostTerminal / recoverV2CompletionIfDue tails) owns
 * completeExecution/failExecution exactly once per transition.
 * <p>
 * <b>uk_exec_auto_job_group ruling (a)</b>: a group hosts at most ONE job row — the next
 * AUTOEXEC segment's job creation physically deletes the previous segment's job+task rows.
 * The completed segment's summary ({@code exec_segments[].summary}: status/taskCount/error/
 * start/finish) is frozen here BEFORE that dispatch, which is the accepted forensic trail
 * (v2 groups have no task-level UI consumption today).
 */
@Slf4j
@Service
public class TicketSegmentService {

    private static final String EXEC_STATUS_EXECUTING = "EXECUTING";
    private static final String EXEC_STATUS_SUCCESS   = "SUCCESS";
    private static final String EXEC_STATUS_FAILED    = "FAILED";
    private static final String SYSTEM_OPERATOR       = "SYSTEM";

    @Resource
    private ApprovalDal      approvalDal;
    @Resource
    private TicketDbStmtDal  ticketDbStmtDal;
    @Resource
    private ExecutionDal     execDal;
    @Resource
    private SchemaSnapshotService schemaSnapshotService;
    // @Lazy: AutoExecServiceImpl reaches back into this service (handleV2JobCompletion segment
    //   delegation) — safe-side proxy injection (governance component iron rule).
    @org.springframework.context.annotation.Lazy
    @Resource
    private AutoExecService  autoExecService;
    // @Lazy: the ghost dispatch surface lives in the govticket service layer which lazy-reaches
    //   the governance components back — safe-side proxy injection (iron rule).
    @org.springframework.context.annotation.Lazy
    @Resource
    private GovTicketV2Service govTicketV2Service;
    @org.springframework.context.annotation.Lazy
    @Resource
    private GhostService     ghostService;
    // B7 N2: the scene-based alert channel (design §8.3 — the DML_BATCH_UNRESOLVED registration
    // rides the segment-terminal transactions; afterCommit fires on the sweep's REQUIRES_NEW
    // inner commit / the completion hook commit). NULL-SAFE consumption (the != null guard at
    // the register site): the frozen N1b pin suites predate this field and exercise the same
    // branches — an unwired alert channel degrades to the pre-N2 behavior (no push), never an
    // NPE inside the segment terminal write. No cycle: AlertPushService reaches only DALs +
    // the IM sender, so no @Lazy needed.
    @Resource
    private com.clougence.clouddm.console.web.component.alert.AlertPushService alertPushService;
    // @Lazy: B7 DML_BATCH seam — the batch engine service lives in the service layer which
    //   lazy-reaches the governance components back (completion service); safe-side proxy
    //   injection (governance component iron rule).
    @org.springframework.context.annotation.Lazy
    @Resource
    private com.clougence.clouddm.console.web.service.dmlbatch.DmlBatchService dmlBatchService;

    // ==================================================================================
    // first-segment dispatch (main chain, GovAutoAdvanceServiceImpl.systemAutoConfirmForV2)
    // ==================================================================================

    /**
     * Initialize the segment runtime from the plan and dispatch the FIRST segment (dispatch
     * site #1 of the segment shape). Mirrors the dispatchGhostGroup lock discipline: ticket row
     * lock first, then a CAS re-read of the group (only PENDING/FAILED are dispatchable — a
     * concurrent writer that already owns the group makes this skip idempotently; the autoexec
     * uk_exec_auto_job_group physical CAS has no ghost analog, this re-read is it).
     * <p>
     * The runtime is ALWAYS rebuilt from the plan here: a PENDING segmented group is either
     * pre-first-dispatch or post-resubmit-reset (updateExecSegments(null) + fresh plan), so no
     * prior runtime may survive into the new execution round.
     */
    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRED)
    public void dispatchFirstSegment(DmApprovalDO ticket, DmTicketDbStmtDO group, boolean transactional,
                                     ErrorStrategy errorStrategy, String languageTag, String uid) {
        this.approvalDal.approvalMapper().selectByIdForUpdate(ticket.getId());

        DmTicketDbStmtDO fresh = this.ticketDbStmtDal.stmtMapper().queryById(group.getId());
        if (fresh == null
            || (!TicketSegment.STATUS_PENDING.equals(fresh.getExecStatus())
                && !EXEC_STATUS_FAILED.equals(fresh.getExecStatus()))) {
            log.warn("[TicketSegment] first-segment dispatch skipped for ticket {} group {} — status {} under the lock",
                ticket.getId(), group.getId(), fresh == null ? null : fresh.getExecStatus());
            return;
        }
        List<TicketSegment> plan = TicketSegment.parsePlan(fresh.getPrecheckResult());
        // B7: the guard widened from hasGhostSegment to hasRoutedSegment — a DML_BATCH-only plan
        // is a valid segment plan (the decision-order "segments" branch gate, four-point contract)
        if (!TicketSegment.hasRoutedSegment(plan)) {
            // defensive: the caller detected a segment plan on a pre-lock snapshot that is gone
            // under the lock (concurrent resubmit) — nothing to dispatch here
            log.warn("[TicketSegment] first-segment dispatch skipped for group {} — no segment plan under the lock",
                fresh.getId());
            return;
        }

        List<TicketSegment> runtime = TicketSegment.initRuntime(plan);
        TicketSegment first = runtime.get(0);
        first.setStatus(TicketSegment.STATUS_EXECUTING);
        first.setStartedAt(System.currentTimeMillis());
        persistRuntime(fresh, runtime);

        if (TicketSegment.TYPE_GHOST.equals(first.getType())) {
            this.govTicketV2Service.dispatchGhostSegment(ticket, fresh, first,
                GovTicketV2Service.GHOST_VIA_AUTO_ROUTE);
            persistRuntime(fresh, runtime); // the launch recorded sessionId/taskId on the segment
        } else if (TicketSegment.TYPE_DML_BATCH.equals(first.getType())) {
            // B7: the batch dispatch records taskId on the segment; the caller-side persist is the
            // single-writer discipline (dispatchGhostSegment contract mirrored)
            this.govTicketV2Service.dispatchBatchSegment(ticket, fresh, first,
                GovTicketV2Service.GHOST_VIA_AUTO_ROUTE);
            persistRuntime(fresh, runtime);
        } else {
            this.schemaSnapshotService.captureIfMissing(fresh, ticket.getId());
            String jobBizId = DmTeamUtils.nextExecJobBizId();
            first.setJobBizId(jobBizId);
            persistRuntime(fresh, runtime);
            this.autoExecService.createGroupJob(fresh, jobBizId,
                forceNonTransactionalIfBatched(plan, transactional, fresh.getId()),
                errorStrategy, languageTag, uid);
            this.autoExecService.startJob(jobBizId, uid);
        }
    }

    /**
     * B7 N3 (裁决 #11/A7, design §13 "PROD_DML 确认弹窗事务开关=true ∧ 组含 DML_BATCH 段 → 服务端
     * 强制 enableTransactional=false"): a group carrying a DML_BATCH segment NEVER runs its
     * AUTOEXEC segments transactionally — the batch loop commits per batch (分批的核心收益), so a
     * whole-job transaction over the plain statements contradicts the group's partial-success
     * semantics and re-creates the long-transaction lock holding the batching exists to remove.
     * This is the SERVER-SIDE RE-VALIDATION covering EVERY dispatch chain (the auto-advance
     * config builds transactional=true for DML changeType by default; the human-confirm FO
     * carries the dialog's value — the dialog's disabled switch is the UX front, 三点明示之二).
     * Mid-chain/resume/sweep re-dispatch parity lives in {@link #resolveTransactional}.
     * GHOST-only plans are untouched (B6 behavior byte-identical).
     */
    private static boolean forceNonTransactionalIfBatched(List<TicketSegment> plan, boolean transactional, long groupId) {
        if (transactional && TicketSegment.hasDmlBatchSegment(plan)) {
            log.warn("[TicketSegment] group {} carries a DML_BATCH segment — enableTransactional forced to false: {}",
                groupId, DmI18nUtils.getMessage(I18nDmMsgKeys.DML_BATCH_TRANSACTIONAL_FORCED.name()));
            return false;
        }
        return transactional;
    }

    // ==================================================================================
    // B6 M3: retryGroup resume-from-failed-segment (design §6.5, contract :417 M3 revision)
    // ==================================================================================

    /**
     * Resume a SEGMENTED group from its failed segment (caller: GovTicketV2ServiceImpl.retryGroup,
     * UNDER the ticket row lock it just took — same lock-order discipline as every writer here).
     * <p>
     * Semantics (the M2 window-guard replacement; whole-group replay would necessarily fail on an
     * already-SUCCESS ghost segment — its ALTER landed):
     * <ul>
     * <li><b>Fresh-read-after-lock</b> (dispatchFirstSegment/check-F1 discipline): the pre-lock
     *     group row of the caller is NEVER trusted — status and runtime are re-read here;</li>
     * <li>already-SUCCESS segments keep their terminal state and are NEVER re-played;</li>
     * <li>the resume target = the first non-SUCCESS segment (FAILED in the normal shape; a
     *     dead-body EXECUTING / undispatched PENDING is picked up by the same lookup) — it resets
     *     to PENDING with cleared error/timestamps/bindings, then re-marks EXECUTING and
     *     dispatches through the SAME {@link #dispatchSegmentUnderLock} the chain uses;</li>
     * <li>GHOST target: idempotent pre-rebuild cleanup (closeQuietly the recorded old session —
     *     the sidecar cap-2 slot; the old AsyncTask is guaranteed terminal by the active check and
     *     is NEVER reused — the rebuild launches a fresh task; old-table remnants are deliberately
     *     not cleaned, R7 retention) + the target-effect probe (M1 check 前瞻①): a decidable
     *     single-action ADD COLUMN whose target column already exists means the lost-writeback
     *     migration ACTUALLY LANDED — the segment advances as SUCCESS carrying the probe evidence
     *     in its summary instead of replaying over the altered table. Undecidable shapes
     *     (MODIFY/DROP/multi-action) skip the probe; probe failure/timeout degrades to the
     *     conservative rebuild (gh-ost's duplicate-column error is the safe backstop);</li>
     * <li>extended active check (the ruling's "活跃 autoexec job ∨ 该组任一段活跃 GHOST AsyncTask"):
     *     an in-flight job always rejects; a FINISH job rejects only when the target is its own
     *     AUTOEXEC segment (lost writeback = the sweep's advance job, not a retry replay — legacy
     *     :394-399 parity); an earlier segment's FINISH job remnant (uk single-row) never blocks a
     *     GHOST resume. Ghost tasks: the repo-consistent non-terminal set (COMPLETE/FAILURE/CANCEL
     *     terminal — everything else, including the transient INIT/PAUSING/CANCELING, is active:
     *     the M1 superset ruling);</li>
     * <li>a group WITHOUT a parsable runtime: PENDING + plan present = pre-first-dispatch →
     *     initialize from the plan and dispatch the first segment; anything else (a FAILED/
     *     EXECUTING group whose per-segment history is missing/corrupt) rejects with the
     *     return-revise guidance — replaying the whole text could re-run a landed ghost ALTER.</li>
     * </ul>
     * The group row itself is never written here except through the dispatch (EXECUTING, stale
     * exec_detail cleared) / probe-success advance (group terminal when it was the last segment) —
     * markExecutionDispatched stays in retryGroup.
     *
     * @param uid the retry operator (AUTOEXEC job uid; the ghost launch keeps the submitter owner)
     * @return true ONLY when the probe-success advance wrote the GROUP TERMINAL synchronously
     *         (last resumable segment landed, NOTHING dispatched): no completion callback will
     *         ever fire and the EXEC_FAIL ticket is outside the WAIT_EXEC sweep IN set, so the
     *         CALLER must run the shared all-groups aggregation tail itself (the
     *         handleV2JobCompletion tail mirror) or the ticket sticks at EXEC_FAIL over
     *         all-SUCCESS groups. Every other path (dispatch / rejection) returns false —
     *         the dispatched body's callback owns the aggregation there.
     */
    public boolean resumeGroupUnderLock(DmApprovalDO ticket, long groupId, String uid) {
        return resumeGroupUnderLock(ticket, groupId, uid, null);
    }

    /**
     * B7 N2 overload (design §7.4): {@code dmlBatchResolution} is the human verdict over a
     * DML_BATCH window crash — REQUIRED (RERUN_BATCH ∨ SKIP_BATCH) iff the resume target's
     * frozen summary carries {@code unresolvedIntent}; missing/invalid → rejected with
     * GOV_TICKET_V2_DML_BATCH_RESOLUTION_REQUIRED (the B6 RETRY_UNSUPPORTED narrowing precedent:
     * a manual verdict gate, never a silent default). RERUN = the normal continuation (the
     * inherited cursor re-runs the unresolved batch — statement-level atomicity made the crash-
     * recorded retryPending class safe, and the true window crash is exactly what the human just
     * adjudicated); SKIP = the fresh task's first tick re-derives one boundary and advances the
     * cursor without executing (pendingResolution transport). Every non-DML_BATCH target and
     * every non-unresolved DML_BATCH failure ignores the parameter (普通段 FAILED 直接续跑).
     */
    public boolean resumeGroupUnderLock(DmApprovalDO ticket, long groupId, String uid, String dmlBatchResolution) {
        DmTicketDbStmtDO group = this.ticketDbStmtDal.stmtMapper().queryById(groupId); // fresh under-lock read
        if (group == null) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(
                I18nDmMsgKeys.GOV_TICKET_V2_GROUP_NOT_FOUND.name(), groupId));
        }
        String status = group.getExecStatus();
        if (!EXEC_STATUS_FAILED.equals(status) && !TicketSegment.STATUS_PENDING.equals(status)
            && !EXEC_STATUS_EXECUTING.equals(status)) {
            // a concurrent writer (hook/callback/sweep) already moved the group under the lock
            throw new ErrorMessageException(DmI18nUtils.getMessage(
                I18nDmMsgKeys.GOV_TICKET_V2_GROUP_NOT_FAILED.name()));
        }

        List<TicketSegment> runtime = TicketSegment.parseRuntime(group.getExecSegments());
        if (runtime == null) {
            // B7 check-fix: the gate widens with the plan's type domain (hasGhostSegment →
            // hasRoutedSegment, the dispatchFirstSegment-guard parity). A DML_BATCH-only plan
            // left PENDING by a rolled-back first dispatch has NO other recovery surface — the
            // sweep only probes EXECUTING groups and returnTicket requires a FAILED group — so
            // keeping the B6 ghost-only predicate here would dead-end the retry into
            // RETRY_UNSUPPORTED over a trivially safe restart (nothing ever executed).
            if (TicketSegment.STATUS_PENDING.equals(status)
                && TicketSegment.hasRoutedSegment(TicketSegment.parsePlan(group.getPrecheckResult()))) {
                // pre-first-dispatch (or its rollback): nothing ever executed — safe to start over
                assertNoActiveJob(group);
                initializeAndDispatchFirstSegment(ticket, group, uid);
                return false; // a dispatch happened — its completion callback owns the aggregation
            }
            // a group that already ran with a missing/corrupt runtime: per-segment history is gone,
            // a whole-text replay could re-run a landed ghost ALTER → return-revise guidance
            throw new ErrorMessageException(DmI18nUtils.getMessage(
                I18nDmMsgKeys.GOV_TICKET_V2_GHOST_SEGMENT_RETRY_UNSUPPORTED.name()));
        }

        TicketSegment target = TicketSegment.firstResumable(runtime);
        if (target == null) {
            // every segment SUCCESS while the group row is non-terminal — inconsistent carrier,
            // nothing to resume (the sweep's all-terminal repair owns the group write)
            throw new ErrorMessageException(DmI18nUtils.getMessage(
                I18nDmMsgKeys.GOV_TICKET_V2_GROUP_NOT_FAILED.name()));
        }

        assertNoActiveBody(ticket, group, runtime, target);

        // pre-before baseline (first-capture-wins; mid-chain usually a no-op — the first dispatch
        // already captured; mirrors the legacy retryGroup :455 and dispatchGhostSegment contracts)
        this.schemaSnapshotService.captureIfMissing(group, ticket.getId());

        Map<String, Object> inheritedCheckpoint = null;
        String pendingResolution = null;
        if (TicketSegment.TYPE_GHOST.equals(target.getType())) {
            // idempotent pre-rebuild cleanup: release the old session (sidecar cap-2 slot). The old
            // AsyncTask needs no disposal — the active check above guaranteed it is terminal, and
            // the rebuild launches a FRESH task (never reused). Old-table remnants stay (R7).
            releaseSegmentSessionQuietly(ticket, target);
            // target-effect probe (M1 check 前瞻①): decidable ADD COLUMN already landed → the
            // lost-writeback migration actually succeeded — advance as SUCCESS with the evidence
            if (this.ghostService.probeAddColumnLanded(ticket.getOwnerUid(), group.getDsId(),
                    group.getDbName(), target.getSql())) {
                target.setError(null);
                // no next PENDING segment ⟹ advanceUnderLock writes the GROUP TERMINAL right here
                // and NOTHING dispatches — signal the caller to run the aggregation tail itself
                boolean groupTerminal = TicketSegment.nextPending(runtime, target.getSegIdx()) == null;
                advanceUnderLock(ticket, group, runtime, target, true, null,
                    buildProbeSummary(target, group.getDbName()), uid);
                return groupTerminal;
            }
        } else if (TicketSegment.TYPE_DML_BATCH.equals(target.getType())) {
            // B7 N2 resolution guard (design §7.4, 裁决 #9 option C): a window crash frozen into
            // the segment summary (unresolvedIntent — the batch outcome is UNKNOWN and a silent
            // re-run could double-apply a non-idempotent UPDATE) demands an EXPLICIT human verdict
            // before the resume; anything else (missing/blank/invalid) rejects with the narrowing
            // key (the B6 RETRY_UNSUPPORTED precedent). The guard keys on the SEGMENT SUMMARY —
            // the authoritative frozen marker every UNRESOLVED terminal writes (task tick /
            // sweep dead-judge / sweep FAILURE-CANCEL replay). 普通段 FAILED (批重试耗尽/终止/
            // 失联无 intent) needs no resolution — direct cursor continuation.
            if (isUnresolvedSummary(target.getSummary())) {
                if (!TicketSegment.RESOLUTION_RERUN_BATCH.equals(dmlBatchResolution)
                    && !TicketSegment.RESOLUTION_SKIP_BATCH.equals(dmlBatchResolution)) {
                    throw new ErrorMessageException(DmI18nUtils.getMessage(
                        I18nDmMsgKeys.GOV_TICKET_V2_DML_BATCH_RESOLUTION_REQUIRED.name()));
                }
                if (TicketSegment.RESOLUTION_SKIP_BATCH.equals(dmlBatchResolution)) {
                    pendingResolution = TicketSegment.RESOLUTION_SKIP_BATCH; // the fresh task's first tick consumes it
                }
            }
            // B7 checkpoint inheritance (design §7.4, the C16 non-reusable replacement — DML has
            // NO generic target-effect probe, so the cursor IS the resume contract): the old
            // task's DONE side (cursor/batchesDone/rowsDone/lastDone) seeds the fresh task's
            // configData — "已成功批永不重放" is physically guaranteed by the cursor inheritance;
            // the old task is NEVER reused (B6 discipline). The intent side is deliberately NOT
            // inherited: a manual retry is the human decision (the resolution guard above is its
            // gate). No session exists → nothing to release.
            inheritedCheckpoint = extractDmlBatchDoneCheckpoint(ticket, group, target);
        }
        // reset the failed segment → PENDING (+ clear error/timestamps/stale bindings), then the
        // advanceSegment-shaped re-mark + dispatch (one persist — the intermediate PENDING is
        // never observable outside this transaction)
        resetSegmentForRerun(target);
        if (inheritedCheckpoint != null) {
            target.setResumeCheckpoint(inheritedCheckpoint); // dispatchBatchSegment consumes it into the fresh configData
        }
        if (pendingResolution != null) {
            target.setPendingResolution(pendingResolution); // SKIP verdict → the fresh task's first tick
        }
        target.setStatus(TicketSegment.STATUS_EXECUTING);
        target.setStartedAt(System.currentTimeMillis());
        persistRuntime(group, runtime);
        dispatchSegmentUnderLock(ticket, group, runtime, target, uid, GovTicketV2Service.GHOST_VIA_RETRY);
        return false; // a dispatch happened — its completion callback owns the aggregation
    }

    /**
     * The terminal old task's DONE-side checkpoint as a plain map (never-throw: a missing task /
     * unparsable carrier degrades to null = a FRESH cursor — which for a never-dispatched target
     * is exactly right; for a lost carrier it is the conservative re-run-from-scratch shape whose
     * double-apply risk the N2 resolution guard + the UNRESOLVED manual copy own). The intent /
     * heartbeat / status parts are stripped here AND re-stripped by the submit-side seeder
     * (defense in depth).
     */
    private Map<String, Object> extractDmlBatchDoneCheckpoint(DmApprovalDO ticket, DmTicketDbStmtDO group,
                                                              TicketSegment target) {
        try {
            DmExecAsyncTaskDO oldTask = target.getTaskId() == null ? null
                : this.execDal.asyncTaskMapper().queryById(target.getTaskId());
            if (oldTask == null) {
                oldTask = this.dmlBatchService.findTaskForTicketGroup(ticket.getId(), group.getId(), target.getSegIdx());
            }
            if (oldTask == null) {
                return null;
            }
            com.clougence.clouddm.console.web.service.dmlbatch.asyntask.DmlBatchAsyncTaskConfig cfg
                = com.clougence.clouddm.console.web.service.dmlbatch.DmlBatchServiceImpl.parseTaskConfig(oldTask);
            if (cfg == null || cfg.getCheckpoint() == null) {
                return null;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> full = JsonUtils.toObj(JsonUtils.toJson(cfg.getCheckpoint()), Map.class);
            if (full == null) {
                return null;
            }
            full.remove("intent");
            full.remove("lastTickAt");
            full.remove("lastStatus");
            full.remove("lagState");
            return full.isEmpty() ? null : full;
        } catch (Exception e) {
            log.warn("[TicketSegment] DML_BATCH checkpoint extraction failed for group {} segment {} (fresh cursor)",
                group.getId(), target.getSegIdx(), e);
            return null;
        }
    }

    /** the runtime-null PENDING shape: rebuild the runtime from the plan and dispatch segment 1. */
    private void initializeAndDispatchFirstSegment(DmApprovalDO ticket, DmTicketDbStmtDO group, String uid) {
        List<TicketSegment> plan = TicketSegment.parsePlan(group.getPrecheckResult());
        List<TicketSegment> runtime = TicketSegment.initRuntime(plan);
        TicketSegment first = runtime.get(0);
        first.setStatus(TicketSegment.STATUS_EXECUTING);
        first.setStartedAt(System.currentTimeMillis());
        this.schemaSnapshotService.captureIfMissing(group, ticket.getId());
        persistRuntime(group, runtime);
        dispatchSegmentUnderLock(ticket, group, runtime, first, uid, GovTicketV2Service.GHOST_VIA_RETRY);
    }

    /**
     * Extended active check (design §6.5): "活跃 autoexec job ∨ 该组任一段活跃 GHOST AsyncTask".
     * Job: an in-flight status always rejects; FINISH rejects only when the resume target is its
     * OWN AUTOEXEC segment (lost writeback → the sweep advances it as SUCCESS — a retry replay
     * would re-run landed statements; legacy retryGroup :394-399 parity), while an earlier
     * segment's FINISH remnant (uk single-row ruling (a)) never blocks a GHOST resume.
     * Ghost tasks: checked over EVERY ghost segment binding, not just the target (the ruling's
     * 任一段); non-terminal = active (M1 superset incl. INIT/PAUSING/CANCELING). A segment without
     * a recorded taskId never launched (the recording commits with the launch) — nothing to check.
     */
    private void assertNoActiveBody(DmApprovalDO ticket, DmTicketDbStmtDO group,
                                    List<TicketSegment> runtime, TicketSegment target) {
        DmExecAutoJobDO job = this.execDal.autoJobMapper().queryByDependOnGroupId(group.getId());
        if (job != null) {
            AutoExecJobStatus jobStatus = job.getStatus();
            boolean terminalJob = jobStatus == AutoExecJobStatus.FAILED || jobStatus == AutoExecJobStatus.TERMINATION;
            boolean finishRemnant = jobStatus == AutoExecJobStatus.FINISH
                && !TicketSegment.TYPE_AUTOEXEC.equals(target.getType());
            if (!terminalJob && !finishRemnant) {
                throw new ErrorMessageException(DmI18nUtils.getMessage(
                    I18nDmMsgKeys.GOV_RELEASE_STMT_JOB_ACTIVE.name()));
            }
        }
        for (TicketSegment seg : runtime) {
            if (seg == null || seg.getTaskId() == null) {
                continue;
            }
            // B7: the active-task check covers BOTH AsyncTask-bound segment types — GHOST (the
            // M1 superset ruling: non-terminal = active, incl. INIT/PAUSING/CANCELING) and
            // DML_BATCH (a live batch loop rejects the retry; the type-specific message keeps
            // the guidance copy honest)
            boolean ghost = TicketSegment.TYPE_GHOST.equals(seg.getType());
            boolean batch = TicketSegment.TYPE_DML_BATCH.equals(seg.getType());
            if (!ghost && !batch) {
                continue;
            }
            DmExecAsyncTaskDO task = this.execDal.asyncTaskMapper().queryById(seg.getTaskId());
            if (task != null && !isTerminalTaskStatus(task.getStatus())) {
                throw new ErrorMessageException(DmI18nUtils.getMessage(ghost
                    ? I18nDmMsgKeys.GOV_TICKET_V2_GHOST_TASK_ACTIVE.name()
                    : I18nDmMsgKeys.GOV_TICKET_V2_DML_BATCH_TASK_ACTIVE.name()));
            }
        }
    }

    /** the runtime-null branch's job guard (legacy PENDING/EXECUTING retry parity, under lock). */
    private void assertNoActiveJob(DmTicketDbStmtDO group) {
        DmExecAutoJobDO job = this.execDal.autoJobMapper().queryByDependOnGroupId(group.getId());
        if (job != null && job.getStatus() != AutoExecJobStatus.FAILED
            && job.getStatus() != AutoExecJobStatus.TERMINATION) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(
                I18nDmMsgKeys.GOV_RELEASE_STMT_JOB_ACTIVE.name()));
        }
    }

    /** reset a resumed segment to PENDING: clear error/timestamps and every stale execution binding. */
    private static void resetSegmentForRerun(TicketSegment seg) {
        seg.setStatus(TicketSegment.STATUS_PENDING);
        seg.setError(null);
        seg.setStartedAt(null);
        seg.setFinishedAt(null);
        seg.setJobBizId(null);
        seg.setSessionId(null);
        seg.setTaskId(null);
        seg.setSummary(null);
        seg.setResumeCheckpoint(null); // B7: the caller re-sets the fresh inheritance right after
        seg.setPendingResolution(null); // B7 N2: same re-set discipline for the SKIP verdict transport
    }

    /**
     * B7 N2 (design §7.4): the segment summary's frozen window-crash marker — the resolution
     * guard's ONLY key (the authoritative carrier every UNRESOLVED terminal writes: the task
     * tick's writeBackTerminal, the sweep dead-judge and the sweep FAILURE/CANCEL replay all
     * freeze {@code unresolvedIntent} + {@code intentSeq} through the summary builders).
     */
    public static boolean isUnresolvedSummary(Map<String, Object> summary) {
        return summary != null && Boolean.TRUE.equals(summary.get("unresolvedIntent"));
    }

    /**
     * Best-effort release of a ghost segment's recorded session (never-throw; the sidecar GHOST
     * concurrency slot, cap 2, is only returned on close — a dead-judged or rebuilt segment must
     * not starve future auto-route dispatches). Owner uid = the launch uid = the ticket submitter
     * (dispatchGhostSegment binds executeForTicket to ticket.getOwnerUid() by construction).
     */
    private void releaseSegmentSessionQuietly(DmApprovalDO ticket, TicketSegment seg) {
        if (seg == null || StringUtils.isBlank(seg.getSessionId())) {
            return;
        }
        try {
            this.ghostService.releaseSessionQuietly(ticket.getOwnerUid(), seg.getSessionId());
        } catch (RuntimeException e) {
            log.warn("[TicketSegment] ghost session release failed for segment {} (continues)", seg.getSegIdx(), e);
        }
    }

    /** resume-probe evidence frozen into the segment summary (auditable "why SUCCESS" trail). */
    private static Map<String, Object> buildProbeSummary(TicketSegment seg, String dbName) {
        Map<String, Object> summary = new HashMap<>();
        summary.put("status", TicketSegment.STATUS_SUCCESS);
        summary.put("resumeProbe", "TARGET_COLUMN_EXISTS"); // 迁移实成功:ADD COLUMN 目标列已存在
        summary.put("table", seg.getTable());
        // the probe already decided this exact input is parseable — the re-parse only recovers the
        // column name for the evidence record (null-safe: the summary stays valid without it)
        GhostResumeProbe.Target target = GhostResumeProbe.parseSingleAddColumn(seg.getSql(), dbName);
        if (target != null) {
            summary.put("column", target.getColumn());
        }
        if (seg.getStartedAt() != null) {
            summary.put("startedAt", seg.getStartedAt());
        }
        summary.put("finishedAt", System.currentTimeMillis());
        return summary;
    }

    // ==================================================================================
    // segment completion (the advanceSegment single service method — two callback callers)
    // ==================================================================================

    /**
     * AUTOEXEC segment completion (caller: handleV2JobCompletion, UNDER its ticket row lock,
     * after the job terminal CAS flip). Locates the segment by the EXECUTING+AUTOEXEC shape and
     * cross-checks the recorded jobBizId (a stale/replayed job callback never advances a segment
     * that already moved on). A corrupt/blank runtime degrades to the LEGACY direct group write
     * (never stuck on a malformed carrier).
     *
     * @return true when this call wrote the segment terminal (the caller's aggregation tail must
     *         run); false when the CAS skipped (replay/race — the first writer already ran it)
     */
    public boolean completeAutoexecSegmentUnderLock(DmApprovalDO ticket, long groupId, String jobBizId,
                                                    boolean success, String detail, String dispatchUid) {
        DmTicketDbStmtDO group = this.ticketDbStmtDal.stmtMapper().queryById(groupId); // fresh re-read UNDER the lock
        if (group == null) {
            return false;
        }
        List<TicketSegment> runtime = TicketSegment.parseRuntime(group.getExecSegments());
        if (runtime == null) {
            return legacyFallbackWrite(group, success, detail);
        }
        TicketSegment seg = null;
        for (TicketSegment one : runtime) {
            if (one != null && TicketSegment.STATUS_EXECUTING.equals(one.getStatus())
                && TicketSegment.TYPE_AUTOEXEC.equals(one.getType())) {
                seg = one;
                break;
            }
        }
        if (seg == null) {
            return false; // replay/race: no EXECUTING autoexec segment — the first writer won
        }
        if (StringUtils.isNotBlank(seg.getJobBizId()) && StringUtils.isNotBlank(jobBizId)
            && !seg.getJobBizId().equals(jobBizId)) {
            log.warn("[TicketSegment] stale job callback for group {} (job {} vs segment job {}) — skipped",
                groupId, jobBizId, seg.getJobBizId());
            return false;
        }
        Map<String, Object> summary = buildAutoexecSummary(seg, success, detail);
        return advanceUnderLock(ticket, group, runtime, seg, success, detail, summary,
            StringUtils.isNotBlank(dispatchUid) ? dispatchUid : SYSTEM_OPERATOR);
    }

    /**
     * GHOST segment completion (caller: GhostGroupCompletionService.onGhostTerminal, UNDER its
     * ticket row lock). CAS keys on the configData segmentIndex when present (multi-ghost-segment
     * groups bind each task to its segment); null index falls back to the EXECUTING+GHOST shape
     * (a single ghost segment is the only shape that can produce a null index).
     */
    public boolean completeGhostSegmentUnderLock(DmApprovalDO ticket, DmTicketDbStmtDO group,
                                                 Integer segmentIndex, boolean success, String detail) {
        List<TicketSegment> runtime = TicketSegment.parseRuntime(group.getExecSegments());
        if (runtime == null) {
            return legacyFallbackWrite(group, success, detail);
        }
        TicketSegment seg;
        if (segmentIndex != null) {
            seg = TicketSegment.byIdx(runtime, segmentIndex); // exact binding — no shape fallback
        } else {
            seg = null;
            for (TicketSegment one : runtime) {
                if (one != null && TicketSegment.STATUS_EXECUTING.equals(one.getStatus())
                    && TicketSegment.TYPE_GHOST.equals(one.getType())) {
                    seg = one;
                    break;
                }
            }
        }
        if (seg == null || !TicketSegment.STATUS_EXECUTING.equals(seg.getStatus())
            || !TicketSegment.TYPE_GHOST.equals(seg.getType())) {
            return false; // CAS: replayed hook / sweep already judged / stale task of a rebuilt segment
        }
        return advanceUnderLock(ticket, group, runtime, seg, success, detail, null, SYSTEM_OPERATOR);
    }

    /**
     * DML_BATCH segment completion (B7, caller: DmlBatchCompletionService.onBatchTerminal, UNDER
     * its ticket row lock). The ghost CAS shape (segmentIndex binding, EXECUTING+type check) PLUS
     * the <b>taskId cross-check</b> — B6 debt #5 discharged at the first new task re-entry surface:
     * the CAS also requires {@code segment.taskId ≡ firing-task}, so a stale terminal task of a
     * REBUILT segment (retryGroup launched a fresh task over the same segmentIndex) can never
     * write through the fresh dispatch's EXECUTING CAS. Both ids are present by construction
     * (the dispatch records taskId atomically with the EXECUTING mark) — a mismatch OR a missing
     * recorded id is a CAS skip.
     *
     * @param summary the checkpoint freeze {batchesDone, rowsDone, cursor?, error?,
     *                unresolvedIntent?, intentSeq?} — the segment's forensic trail (uk ruling (a)
     *                summary heritage; the task configData keeps the full checkpoint)
     * @return true when this call wrote the segment terminal (the caller's aggregation tail must
     *         run); false when the CAS skipped (replay/race/stale task — the first writer won)
     */
    public boolean completeDmlBatchSegmentUnderLock(DmApprovalDO ticket, DmTicketDbStmtDO group,
                                                    Integer segmentIndex, Long taskId,
                                                    boolean success, String detail, Map<String, Object> summary) {
        List<TicketSegment> runtime = TicketSegment.parseRuntime(group.getExecSegments());
        if (runtime == null) {
            return legacyFallbackWrite(group, success, detail);
        }
        TicketSegment seg;
        if (segmentIndex != null) {
            seg = TicketSegment.byIdx(runtime, segmentIndex); // exact binding — no shape fallback
        } else {
            seg = null;
            for (TicketSegment one : runtime) {
                if (one != null && TicketSegment.STATUS_EXECUTING.equals(one.getStatus())
                    && TicketSegment.TYPE_DML_BATCH.equals(one.getType())) {
                    seg = one;
                    break;
                }
            }
        }
        if (seg == null || !TicketSegment.STATUS_EXECUTING.equals(seg.getStatus())
            || !TicketSegment.TYPE_DML_BATCH.equals(seg.getType())) {
            return false; // CAS: replayed hook / sweep already judged / rebuilt segment moved on
        }
        // debt #5 cross-check: the firing task must BE the segment's recorded task
        if (seg.getTaskId() == null || taskId == null || !seg.getTaskId().equals(taskId)) {
            log.warn("[TicketSegment] stale DML_BATCH task hook for group {} segment {} (task {} vs segment task {}) — skipped",
                group.getId(), seg.getSegIdx(), taskId, seg.getTaskId());
            return false;
        }
        boolean advanced = advanceUnderLock(ticket, group, runtime, seg, success, detail, summary, SYSTEM_OPERATOR);
        if (advanced && !success) {
            // N2 (design §8.3): the hook-side UNRESOLVED registration (the task tick detected the
            // window crash itself — restart recovery / UNKNOWN failure class)
            registerDmlUnresolvedAlert(ticket, group, seg, summary);
        }
        return advanced;
    }

    /**
     * The advanceSegment core (design §6.3): segment terminal write (+ AUTOEXEC summary frozen
     * BEFORE the next job creation deletes the old job/task rows) → next segment dispatch, or
     * the GROUP terminal write when this was the last segment. NEVER aggregates the ticket —
     * the caller's tail owns that. Caller holds the ticket row lock; the segment CAS
     * ({@code status == EXECUTING}) was verified by the caller-specific entry above.
     */
    private boolean advanceUnderLock(DmApprovalDO ticket, DmTicketDbStmtDO group, List<TicketSegment> runtime,
                                     TicketSegment seg, boolean success, String detail,
                                     Map<String, Object> summary, String dispatchUid) {
        seg.setStatus(success ? TicketSegment.STATUS_SUCCESS : TicketSegment.STATUS_FAILED);
        seg.setFinishedAt(System.currentTimeMillis());
        if (!success) {
            seg.setError(detail);
        }
        if (summary != null) {
            seg.setSummary(summary);
        }

        if (!success) {
            persistRuntime(group, runtime);
            // 终止/失败: segment FAILED → group FAILED → (caller tail) → ticket EXEC_FAIL
            this.ticketDbStmtDal.stmtMapper().updateExecStatus(group.getId(), EXEC_STATUS_FAILED, detail);
            return true;
        }

        TicketSegment next = TicketSegment.nextPending(runtime, seg.getSegIdx());
        if (next == null) {
            persistRuntime(group, runtime);
            this.ticketDbStmtDal.stmtMapper().updateExecStatus(group.getId(), EXEC_STATUS_SUCCESS, null);
            return true;
        }

        next.setStatus(TicketSegment.STATUS_EXECUTING);
        next.setStartedAt(System.currentTimeMillis());
        persistRuntime(group, runtime);
        dispatchSegmentUnderLock(ticket, group, runtime, next, dispatchUid,
            GovTicketV2Service.GHOST_VIA_SEGMENT_CHAIN);
        return true;
    }

    /**
     * Type-specific dispatch of an already-EXECUTING-marked segment (caller persists runtime after).
     * {@code ghostVia} annotates the GHOST_EXECUTE / DML_BATCH_EXECUTE audit row (segment chain
     * vs M3 retry resume — the via constants are shared, they annotate the dispatch ORIGIN).
     */
    private void dispatchSegmentUnderLock(DmApprovalDO ticket, DmTicketDbStmtDO group,
                                          List<TicketSegment> runtime, TicketSegment seg, String uid,
                                          String ghostVia) {
        if (TicketSegment.TYPE_GHOST.equals(seg.getType())) {
            this.govTicketV2Service.dispatchGhostSegment(ticket, group, seg, ghostVia);
            persistRuntime(group, runtime); // the launch recorded sessionId/taskId on the segment
            return;
        }
        if (TicketSegment.TYPE_DML_BATCH.equals(seg.getType())) {
            // B7: the batch task submission records taskId on the segment (the completion CAS
            // cross-check key, debt #5); a submitTask failure throws — no execution body residue
            // exists (no session in the B7 shape), the transaction rolls back and the segment
            // stays for the sweep's PENDING re-dispatch row
            this.govTicketV2Service.dispatchBatchSegment(ticket, group, seg, ghostVia);
            persistRuntime(group, runtime);
            return;
        }
        String jobBizId = DmTeamUtils.nextExecJobBizId();
        seg.setJobBizId(jobBizId);
        persistRuntime(group, runtime);
        boolean transactional = resolveTransactional(group);
        String languageTag = DmI18nUtils.getLocale().toLanguageTag();
        this.autoExecService.createGroupJob(group, jobBizId, transactional, ErrorStrategy.NONE, languageTag, uid);
        this.autoExecService.startJob(jobBizId, uid);
    }

    // ==================================================================================
    // WAIT_EXEC sweep repair (advance + aggregate dual duty, contractual safety net)
    // ==================================================================================

    /**
     * Sweep-side repair of one EXECUTING segmented group (caller: recoverV2CompletionIfDue →
     * probeGhostGroupsIfDue loop, UNDER the sweep's ticket row lock; per-group failures are
     * caught by the caller and retried next cycle). Dual duty:
     * <ul>
     * <li>current segment EXECUTING → liveness probe:
     *     GHOST = AsyncTask (by ticketId+groupId+segmentIndex) + tool session — NEVER the
     *     autoexec task rows (they die with the segment job replacement, M1 check前瞻④);
     *     AUTOEXEC = the group's job row (it only disappears when the NEXT segment dispatches,
     *     which cannot happen while this segment is the current one);</li>
     * <li>current segment terminal / job terminal / task terminal while the group is still
     *     EXECUTING → the completion writeback was lost → advance (chain-dispatch the next
     *     segment or write the group terminal) exactly like the callback would;</li>
     * <li>current segment PENDING (a crashed mid-advance left it undispatched) → dispatch it;</li>
     * <li>ALL segments terminal while the group is still EXECUTING → the group terminal write
     *     was lost → write it (the caller's aggregation tail then finishes the ticket).</li>
     * </ul>
     * R3 ruling preserved: an unknown ghost outcome (dead task / lost session) is judged FAILED
     * — a retryable failure beats a false SUCCESS.
     * <p>
     * <b>RR staleness guard (check F1)</b>: the judgement runs in a FRESH transaction
     * ({@code REQUIRES_NEW}) and re-reads the group row here. The caller's sweep transaction
     * established its RR read view at its FIRST read — BEFORE the ticket row lock — so a
     * concurrent advance (ghost hook / autoexec callback, which under M2 holds the lock for
     * SECONDS while chain-dispatching the next segment remotely) that committed while the sweep
     * waited on the lock is INVISIBLE to the caller's plain reads. Judging on that stale view
     * mixes pre-lock DB state (segment EXECUTING, task RUNNING, old job rows) with the CURRENT
     * remote {@code hasSession} — the session the just-advanced hook already released makes the
     * healthy chain look dead: false FAILED over a live migration + a stale-runtime overwrite
     * losing the next segment's bindings. The fresh read view (established under the caller's
     * lock — writers that take the ticket lock first cannot interleave) restores the sound
     * invariants: job flip + segment advance commit atomically, and the ghost hook commits
     * BEFORE the task flips terminal / the session closes. This inner transaction must NEVER
     * take the ticket row lock (the suspended caller holds it — self-deadlock); its writes
     * commit independently, so the caller's stale aggregation tail may finish the ticket on the
     * NEXT sweep cycle — the contractual ~7s self-heal cadence, one cycle for the rare
     * lost-writeback repair.
     */
    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRES_NEW)
    public void repairGroupUnderLock(DmApprovalDO ticket, DmTicketDbStmtDO staleGroup) {
        DmTicketDbStmtDO group = this.ticketDbStmtDal.stmtMapper().queryById(staleGroup.getId());
        if (group == null) {
            return; // group gone under the lock — nothing to repair
        }
        if (!EXEC_STATUS_EXECUTING.equals(group.getExecStatus())) {
            return; // self-guard (the caller filters on a PRE-LOCK snapshot): only an EXECUTING
                    // group is repairable — a concurrent hook/callback already wrote the terminal
                    // (fresh re-read shows it), PENDING awaits its first dispatch, terminals are done
        }
        List<TicketSegment> runtime = TicketSegment.parseRuntime(group.getExecSegments());
        if (runtime == null) {
            log.error("[TicketSegment] group {} is EXECUTING with an unparsable exec_segments runtime — left for manual intervention",
                group.getId());
            return; // a carrier WE wrote cannot corrupt; failing the group blindly is worse
        }
        TicketSegment current = TicketSegment.firstNonTerminal(runtime);
        if (current == null) {
            // every segment terminal but the group write was lost — recover the group terminal
            boolean anyFailed = runtime.stream()
                .anyMatch(s -> s != null && TicketSegment.STATUS_FAILED.equals(s.getStatus()));
            String error = TicketSegment.lastTerminalError(runtime);
            this.ticketDbStmtDal.stmtMapper().updateExecStatus(group.getId(),
                anyFailed ? EXEC_STATUS_FAILED : EXEC_STATUS_SUCCESS, anyFailed ? error : null);
            return;
        }

        if (TicketSegment.STATUS_PENDING.equals(current.getStatus())) {
            // a crashed mid-advance: the predecessor committed SUCCESS but the dispatch rolled
            // back — re-mark and dispatch (nothing of this segment ever executed)
            log.warn("[TicketSegment] sweep re-dispatching the undispatched segment {} of group {}",
                current.getSegIdx(), group.getId());
            current.setStatus(TicketSegment.STATUS_EXECUTING);
            current.setStartedAt(System.currentTimeMillis());
            persistRuntime(group, runtime);
            dispatchSegmentUnderLock(ticket, group, runtime, current, SYSTEM_OPERATOR,
                GovTicketV2Service.GHOST_VIA_SEGMENT_CHAIN);
            return;
        }

        // current EXECUTING → liveness probe by type
        if (TicketSegment.TYPE_GHOST.equals(current.getType())) {
            probeGhostSegmentUnderLock(ticket, group, runtime, current);
        } else if (TicketSegment.TYPE_DML_BATCH.equals(current.getType())) {
            probeDmlBatchSegmentUnderLock(ticket, group, runtime, current);
        } else {
            probeAutoexecSegmentUnderLock(ticket, group, runtime, current);
        }
    }

    /**
     * B7 sweep liveness probe of a DML_BATCH segment (design §6.6 five-row decision table).
     * Liveness = the AsyncTask state + the configData HEARTBEAT (there is no hasSession analog —
     * the batch loop holds no session between ticks). The heartbeat stale threshold is DERIVED
     * (no new KV): {@code timeoutSec + max(intervalMs,10s)×2 + 60s} — a healthy tick writes
     * lastTickAt at most one batch duration + one interval ago, so the margin covers the worst
     * legal cadence (a paused/throttled task keeps writing heartbeats — 暂停中心跳照写 — and is
     * judged healthy). A missing lastTickAt falls back to the segment's dispatch time (a console
     * that died between the dispatch commit and the first tick still converges).
     * <p>
     * The dead judgement preserves the checkpoint on the task configData (nothing to release —
     * no session exists): retryGroup inherits the done-side cursor (已成功批永不重放 is
     * physically guaranteed by the inheritance). An unresolved intent at dead-judgement time is
     * frozen into the segment summary (unresolvedIntent — the N2 resolution guard consumes it;
     * the alert hook is N2 as well).
     */
    private void probeDmlBatchSegmentUnderLock(DmApprovalDO ticket, DmTicketDbStmtDO group,
                                               List<TicketSegment> runtime, TicketSegment seg) {
        com.clougence.clouddm.platform.dal.model.execution.DmExecAsyncTaskDO task
            = this.dmlBatchService.findTaskForTicketGroup(ticket.getId(), group.getId(), seg.getSegIdx());
        if (task == null) {
            failSegmentUnderLock(ticket, group, runtime, seg,
                "dml batch task record missing while the segment is EXECUTING — verify the target data manually",
                null);
            return;
        }
        com.clougence.clouddm.console.web.service.dmlbatch.asyntask.DmlBatchAsyncTaskConfig cfg
            = com.clougence.clouddm.console.web.service.dmlbatch.DmlBatchServiceImpl.parseTaskConfig(task);
        com.clougence.clouddm.console.web.service.dmlbatch.asyntask.DmlBatchCheckpoint cp
            = cfg == null ? null : cfg.getCheckpoint();
        AsyncTaskStatus status = task.getStatus();
        if (isTerminalTaskStatus(status)) {
            if (status == AsyncTaskStatus.COMPLETE) {
                // the terminal hook's writeback was lost — replay the completion exactly like it
                // would have (the AUTOEXEC job-FINISH replay semantics)
                log.warn("[TicketSegment] sweep recovering the lost completion of DML_BATCH segment {} group {} (task COMPLETE)",
                    seg.getSegIdx(), group.getId());
                advanceUnderLock(ticket, group, runtime, seg, true, null,
                    dmlBatchSummary(cp, null, false), SYSTEM_OPERATOR);
                return;
            }
            // FAILURE / CANCEL: the hook either already ran (CAS skip upstream — unreachable here
            // because the segment is still EXECUTING) or was lost — advance as FAILED
            String detail = "dml batch task " + status + " (completion writeback lost)";
            boolean unresolved = isUnresolvedIntent(cp) || isSkipVerdictPending(cfg);
            Map<String, Object> summary = dmlBatchSummary(cp, detail, unresolved);
            advanceUnderLock(ticket, group, runtime, seg, false, detail, summary, SYSTEM_OPERATOR);
            if (unresolved) {
                // N2 (design §8.3): the sweep-side registration of a window crash the lost hook
                // never trailed (the task's terminal intent — or an unconsumed SKIP verdict — is
                // the checkpoint/config carrier's own marker)
                registerDmlUnresolvedAlert(ticket, group, seg, summary);
            }
            return;
        }
        // N2 (design §6.6 "healthy covers paused", N1b check handover #2): a DB-PAUSE task is a
        // CONTROLLED pause (the ticket-chain endpoint / framework pauseTask) — the pause cancels
        // the tick loop, so the heartbeat necessarily froze at the pause moment and a legitimate
        // INDEFINITE pause must never be dead-judged by staleness. PAUSE is a pure DB state with
        // no host affinity (resumeTask rebinds console_ip — any console can resume), so there is
        // no "dead host" hiding behind it. PAUSING (the transient in-memory settle state) stays
        // on the heartbeat clock below: a console that died mid-pause is uncontrolled and
        // converges through the dead judgement (+ the host's restart resetPausingAsyncTaskStatus).
        if (status == AsyncTaskStatus.PAUSE) {
            return; // controlled pause — healthy regardless of heartbeat age
        }
        // active task → the heartbeat decides (host-affinity death / dispatcher stall)
        Long heartbeat = cp == null ? null : cp.getLastTickAt();
        long reference = heartbeat != null ? heartbeat
            : (seg.getStartedAt() != null ? seg.getStartedAt() : System.currentTimeMillis());
        long staleMillis = dmlBatchStaleThresholdMillis(cfg);
        if (System.currentTimeMillis() - reference <= staleMillis) {
            return; // healthy: running / retry-waiting / throttled — every tick writes the heartbeat
        }
        String detail = com.clougence.clouddm.console.web.global.i18n.DmI18nUtils.getMessage(
            com.clougence.clouddm.console.web.global.i18n.I18nDmMsgKeys.DML_BATCH_TASK_LOST.name());
        // N2 check fix: an unconsumed SKIP verdict on the dead task's carrier re-freezes the
        // marker too — the adjudicated batch was neither skipped nor proven, so the next
        // retryGroup must demand a fresh verdict (never a silent SKIP→RERUN downgrade)
        failSegmentUnderLock(ticket, group, runtime, seg, detail,
            dmlBatchSummary(cp, detail, isUnresolvedIntent(cp) || isSkipVerdictPending(cfg)));
    }

    /**
     * The segment summary freeze of a DML_BATCH terminal (design §5.3):
     * {batchesDone, rowsDone, cursor?, error?, unresolvedIntent?, intentSeq?, skippedBatches?} —
     * built from the task's configData checkpoint (null-safe: a missing/corrupt carrier yields the
     * bare shape). N2 addition: the SKIP_BATCH evidence list (design §7.4 "evidence 入 summary")
     * rides along when the checkpoint carries manual skips — additive, absent in every pre-N2
     * and skip-free shape.
     */
    static Map<String, Object> dmlBatchSummary(
            com.clougence.clouddm.console.web.service.dmlbatch.asyntask.DmlBatchCheckpoint cp,
            String error, boolean unresolvedIntent) {
        Map<String, Object> summary = new HashMap<>();
        summary.put("batchesDone", cp == null ? 0L : cp.getBatchesDone());
        summary.put("rowsDone", cp == null ? 0L : cp.getRowsDone());
        if (cp != null && cp.getCursor() != null) {
            // C6/M6: the sweep-side twin of DmlBatchAsyncTask.summary — same tiered-disclosure
            // ruling, the frontend-visible cursor rides as its sha256 handle (the functional
            // resume path reads the checkpoint cursor from configData, never this copy).
            summary.put("cursor", com.clougence.clouddm.console.web.service.dmlbatch.DmlBatchLiterals
                .hashedCursorForDisplay(cp.getCursor()));
        }
        if (error != null) {
            summary.put("error", error);
        }
        if (unresolvedIntent) {
            summary.put("unresolvedIntent", Boolean.TRUE);
            if (cp != null && cp.getIntent() != null) {
                summary.put("intentSeq", cp.getIntent().getSeq());
            } else if (cp != null) {
                // N2 check fix: the re-frozen skip-verdict shape carries no intent (stripped at the
                // resume inheritance) — the adjudicated batch's seq is batchesDone+1 by construction
                summary.put("intentSeq", cp.getBatchesDone() + 1);
            }
        }
        if (cp != null && cp.getSkips() != null && !cp.getSkips().isEmpty()) {
            summary.put("skippedBatches", cp.getSkips());
        }
        return summary;
    }

    /** §7.3 window-crash shape: intent ∧ ¬retryPending (retryPending = zero effect proven). */
    static boolean isUnresolvedIntent(
            com.clougence.clouddm.console.web.service.dmlbatch.asyntask.DmlBatchCheckpoint cp) {
        return cp != null && cp.getIntent() != null
            && !Boolean.TRUE.equals(cp.getIntent().getRetryPending());
    }

    /**
     * N2 check fix: an UNCONSUMED SKIP_BATCH verdict on the task carrier (only the successful
     * skip-done persist clears pendingResolution). A terminal in this state means the adjudicated
     * batch was neither skipped nor proven — the summary marker must re-freeze so the resolution
     * guard demands a fresh verdict (option C: a human SKIP verdict never silently downgrades to
     * a RERUN of an unknown-outcome batch — the double-apply hazard for non-idempotent UPDATEs).
     */
    static boolean isSkipVerdictPending(
            com.clougence.clouddm.console.web.service.dmlbatch.asyntask.DmlBatchAsyncTaskConfig cfg) {
        return cfg != null
            && TicketSegment.RESOLUTION_SKIP_BATCH.equals(cfg.getPendingResolution());
    }

    /**
     * B7 N2 (design §8.3): the DML_BATCH_UNRESOLVED alert registration at a segment terminal
     * whose summary froze {@code unresolvedIntent} — the window-crash / heartbeat-death verdict
     * needs a human, and the IM push is how ops learns about it (copy: deep link + segment/batch
     * location + 人工核对后重跑/跳过). Registered INSIDE the segment-terminal transaction:
     * AlertPushService defers to afterCommit (the sweep's REQUIRES_NEW inner tx commits
     * independently — the alert fires exactly when the FAILED verdict becomes visible; a
     * rollback fires nothing). Never-throw + null-safe (an unwired channel = no push, the
     * segment terminal write is never preempted). Exactly-once rides the segment CAS upstream
     * (the first writer's terminal is the only one that reaches here).
     */
    private void registerDmlUnresolvedAlert(DmApprovalDO ticket, DmTicketDbStmtDO group,
                                            TicketSegment seg, Map<String, Object> summary) {
        if (this.alertPushService == null || !isUnresolvedSummary(summary)) {
            return;
        }
        try {
            com.clougence.clouddm.console.web.component.alert.AlertContext ctx
                = new com.clougence.clouddm.console.web.component.alert.AlertContext();
            ctx.setTicketId(ticket.getId());
            ctx.setApplicantUid(ticket.getOwnerUid());
            ctx.setDbName(group.getDbName());
            ctx.setTableName(seg.getTable());
            ctx.setSegmentIndex(seg.getSegIdx());
            Object intentSeq = summary.get("intentSeq");
            if (intentSeq instanceof Number) {
                ctx.setIntentSeq(((Number) intentSeq).longValue());
            }
            this.alertPushService.pushAlert(ticket.getPrimaryUid(),
                com.clougence.clouddm.console.web.component.alert.AlertScene.DML_BATCH_UNRESOLVED, ctx);
        } catch (Exception e) {
            log.warn("[TicketSegment] DML_BATCH_UNRESOLVED alert registration failed for ticket {} group {} segment {} (the terminal write proceeds)",
                ticket.getId(), group.getId(), seg.getSegIdx(), e);
        }
    }

    /** derived heartbeat-stale threshold (design §6.6 — no new KV): timeout + 2×max(interval,10s) + 60s. */
    static long dmlBatchStaleThresholdMillis(
            com.clougence.clouddm.console.web.service.dmlbatch.asyntask.DmlBatchAsyncTaskConfig cfg) {
        com.clougence.clouddm.console.web.service.dmlbatch.DmlBatchEffectiveParams params
            = cfg == null || cfg.getEffectiveParams() == null
                ? new com.clougence.clouddm.console.web.service.dmlbatch.DmlBatchEffectiveParams()
                : cfg.getEffectiveParams();
        long intervalFloor = Math.max(params.getIntervalMs(), 10_000L);
        return params.getTimeoutSec() * 1000L + intervalFloor * 2 + 60_000L;
    }

    private void probeGhostSegmentUnderLock(DmApprovalDO ticket, DmTicketDbStmtDO group,
                                            List<TicketSegment> runtime, TicketSegment seg) {
        DmExecAsyncTaskDO task = this.ghostService.findTaskForTicketGroup(ticket.getId(), group.getId(), seg.getSegIdx());
        if (task == null) {
            failSegmentUnderLock(ticket, group, runtime, seg,
                "ghost task record missing while the segment is EXECUTING");
            return;
        }
        AsyncTaskStatus status = task.getStatus();
        if (isTerminalTaskStatus(status)) {
            failSegmentUnderLock(ticket, group, runtime, seg,
                "ghost completion writeback lost (task " + status + ")");
            return;
        }
        GhostAsyncTaskConfig cfg = parseTaskConfig(task);
        String ownerUid = cfg != null && StringUtils.isNotBlank(cfg.getUserId()) ? cfg.getUserId() : task.getUid();
        if (this.ghostService.hasSession(ownerUid, task.getBizId())) {
            return; // healthy: the migration is still in flight (RUNNING or dock-paused/throttled)
        }
        failSegmentUnderLock(ticket, group, runtime, seg,
            "ghost session lost while the task is " + status + " (result unknown)");
    }

    private void probeAutoexecSegmentUnderLock(DmApprovalDO ticket, DmTicketDbStmtDO group,
                                               List<TicketSegment> runtime, TicketSegment seg) {
        DmExecAutoJobDO job = this.execDal.autoJobMapper().queryByDependOnGroupId(group.getId());
        if (job == null) {
            failSegmentUnderLock(ticket, group, runtime, seg,
                "autoexec job record missing while the segment is EXECUTING");
            return;
        }
        AutoExecJobStatus status = job.getStatus();
        if (status == AutoExecJobStatus.FINISH) {
            // the jobFinish callback was lost — advance exactly like it would have
            log.warn("[TicketSegment] sweep recovering the lost completion of segment {} group {} (job FINISH)",
                seg.getSegIdx(), group.getId());
            Map<String, Object> summary = buildAutoexecSummary(seg, true, null);
            advanceUnderLock(ticket, group, runtime, seg, true, null, summary,
                StringUtils.isNotBlank(job.getUid()) ? job.getUid() : SYSTEM_OPERATOR);
            return;
        }
        if (status == AutoExecJobStatus.FAILED || status == AutoExecJobStatus.TERMINATION) {
            String detail = "autoexec job " + status + " (completion writeback lost)";
            advanceUnderLock(ticket, group, runtime, seg, false, detail,
                buildAutoexecSummary(seg, false, detail), SYSTEM_OPERATOR);
            return;
        }
        // INIT/PREPARING/PACKAGING/WAIT_EXEC/EXECUTING/PAUSE/PAUSING → in flight, healthy
    }

    private void failSegmentUnderLock(DmApprovalDO ticket, DmTicketDbStmtDO group,
                                      List<TicketSegment> runtime, TicketSegment seg, String detail) {
        failSegmentUnderLock(ticket, group, runtime, seg, detail, null);
    }

    /**
     * B7 overload: the DML_BATCH dead-judgement freezes the task checkpoint into the segment
     * summary (batchesDone/rowsDone/cursor + the unresolvedIntent marker) so the N2 resolution
     * guard and the retryGroup resume have the group-carrier trail. The GHOST branch semantics
     * are unchanged (summary stays null there — its trail is the tool log + old table).
     */
    private void failSegmentUnderLock(DmApprovalDO ticket, DmTicketDbStmtDO group,
                                      List<TicketSegment> runtime, TicketSegment seg, String detail,
                                      Map<String, Object> summary) {
        log.warn("[TicketSegment] segment {} of group {} judged dead by the sweep probe: {}",
            seg.getSegIdx(), group.getId(), detail);
        // B6 M3 orphan-session convergence (M2 check 裁决项 2): a dead-judged GHOST segment may
        // still hold an OPEN tool session (crash between the hook commit and its release, or a
        // pre-dock-guard raw pause remnant) — the sidecar GHOST concurrency slot (cap 2) is only
        // returned on session close, so release it here or every future auto-route dispatch
        // starves after two such leaks. Never-throw, best effort (closeSession on a gone session
        // is a no-op; the segment FAILED write proceeds regardless).
        // B7 DML_BATCH: NOTHING to release — the batch loop holds no session between ticks (R10
        // per-tick session economy) and the checkpoint stays on the task configData for the
        // retryGroup inheritance.
        if (TicketSegment.TYPE_GHOST.equals(seg.getType())) {
            releaseSegmentSessionQuietly(ticket, seg);
        }
        advanceUnderLock(ticket, group, runtime, seg, false, detail, summary, SYSTEM_OPERATOR);
        if (TicketSegment.TYPE_DML_BATCH.equals(seg.getType())) {
            // N2 (design §8.3/§6.6): the heartbeat-dead-judgement registration — a dead task with
            // an unresolved intent means the crashed batch's outcome died WITH it (人工裁决 needed);
            // the task-missing row carries no summary → no marker → no alert (its error copy
            // already tells the operator to verify the data manually)
            registerDmlUnresolvedAlert(ticket, group, seg, summary);
        }
    }

    // ==================================================================================
    // helpers
    // ==================================================================================

    /**
     * uk ruling (a): freeze the completed AUTOEXEC segment's forensic summary BEFORE the next
     * segment's job creation physically deletes this segment's job+task rows. taskCount comes
     * from the plan stmtRange span (= the number of task rows built for the segment — ghost
     * statements never fall inside an AUTOEXEC range).
     */
    private static Map<String, Object> buildAutoexecSummary(TicketSegment seg, boolean success, String detail) {
        Map<String, Object> summary = new HashMap<>();
        summary.put("status", success ? TicketSegment.STATUS_SUCCESS : TicketSegment.STATUS_FAILED);
        summary.put("taskCount", seg.taskCount());
        if (detail != null) {
            summary.put("error", detail);
        }
        if (seg.getStartedAt() != null) {
            summary.put("startedAt", seg.getStartedAt());
        }
        summary.put("finishedAt", System.currentTimeMillis());
        return summary;
    }

    /**
     * Persist the runtime JSON and keep the in-memory group DO consistent (createGroupJob reads
     * it back).
     * <p>
     * ⚠ DUAL-WRITE COUPLING PIN (B6 M4, M2 check 前瞻6; B7 semantic rename, 裁决 #12): the ENTRY
     * GATES that decide whether a ticket is segment-managed read {@code ApprovalMO.ghostRouteFlags}
     * — whose semantics B7 WIDENED from "ghost-routed" to "HAS NON-DIRECT-EXECUTION SEGMENTS"
     * (GHOST ∨ DML_BATCH; field name and wire shape unchanged for in-flight compatibility) — NOT
     * this carrier: the WAIT_EXEC sweep pre-filter (ChangeApprovalHandler.probeGhostGroupsIfDue →
     * GhostRouteFlags.anyRouted) skips a flagless ticket BEFORE ever inspecting exec_segments,
     * and the groupList frontend projections (GovTicketV2ServiceImpl.fillGhostTaskProjection AND
     * its B7 N3 twin fillDmlBatchTaskProjection) use the same positional gate. The coupling holds
     * because flags and the segments plan are
     * derived from the SAME authoritative precheck evaluation (submit/resubmit — the B7 marking
     * seam sets a group's flag when its plan carries a GHOST **or DML_BATCH** segment). ANY
     * future path that writes segments (plan or runtime, ANY routed type) MUST write the flags in
     * the same breath — or those pre-filters must become segments-aware — else the group silently
     * loses its sweep repair and projection (R2: a DML_BATCH-only ticket would never be swept).
     */
    private void persistRuntime(DmTicketDbStmtDO group, List<TicketSegment> runtime) {
        String json = JsonUtils.toJson(runtime);
        this.ticketDbStmtDal.stmtMapper().updateExecSegments(group.getId(), json);
        group.setExecSegments(json);
    }

    /**
     * Corrupt-runtime degradation (defensive — the carrier is machine-written): fall back to the
     * legacy direct group write so the ticket can still aggregate instead of sticking forever.
     */
    private boolean legacyFallbackWrite(DmTicketDbStmtDO group, boolean success, String detail) {
        log.error("[TicketSegment] group {} runtime unparsable at completion — degrading to the legacy group write",
            group.getId());
        this.ticketDbStmtDal.stmtMapper().updateExecStatus(group.getId(),
            success ? EXEC_STATUS_SUCCESS : EXEC_STATUS_FAILED, detail);
        return true;
    }

    /**
     * D15 group-level exec config: the group's own precheck changeType (DML → transactional).
     * B7 N3 (裁决 #11): a DML_BATCH-segmented group forces NON-transactional AUTOEXEC segments —
     * the mid-chain/resume/sweep-redispatch parity of {@link #forceNonTransactionalIfBatched}
     * (before B7 the comment below held: "a segmented group carries DDL by construction" — a
     * PROD_DML group segmented by a big DML has changeType=DML and WOULD run its plain statements
     * as one transaction while its batched statement commits per batch).
     */
    private static boolean resolveTransactional(DmTicketDbStmtDO group) {
        try {
            if (TicketSegment.hasDmlBatchSegment(TicketSegment.parsePlan(group.getPrecheckResult()))) {
                return false;
            }
            Map<?, ?> precheck = JsonUtils.toObj(group.getPrecheckResult(), Map.class);
            Object ct = precheck == null ? null : precheck.get("changeType");
            return "DML".equals(ct == null ? null : String.valueOf(ct));
        } catch (Exception e) {
            return false; // fail-safe: a segmented group carries DDL by construction
        }
    }

    private static boolean isTerminalTaskStatus(AsyncTaskStatus status) {
        return status == AsyncTaskStatus.COMPLETE || status == AsyncTaskStatus.FAILURE
            || status == AsyncTaskStatus.CANCEL;
    }

    private static GhostAsyncTaskConfig parseTaskConfig(DmExecAsyncTaskDO task) {
        if (task == null || StringUtils.isBlank(task.getConfigData())) {
            return null;
        }
        try {
            return JsonUtils.toObj(task.getConfigData(), GhostAsyncTaskConfig.class);
        } catch (Exception e) {
            // JsonUtils sneaky-throws the CHECKED JsonProcessingException — catch Exception
            log.warn("[TicketSegment] task configData unparsable, taskId={}", task.getId(), e);
            return null;
        }
    }
}
