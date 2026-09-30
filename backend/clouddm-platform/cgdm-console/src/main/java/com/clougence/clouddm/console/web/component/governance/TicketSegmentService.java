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

import com.clougence.clouddm.api.console.autoexec.ErrorStrategy;
import com.clougence.clouddm.console.web.component.execute.AutoExecService;
import com.clougence.clouddm.console.web.global.i18n.DmI18nUtils;
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
        if (!TicketSegment.hasGhostSegment(plan)) {
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
        } else {
            this.schemaSnapshotService.captureIfMissing(fresh, ticket.getId());
            String jobBizId = DmTeamUtils.nextExecJobBizId();
            first.setJobBizId(jobBizId);
            persistRuntime(fresh, runtime);
            this.autoExecService.createGroupJob(fresh, jobBizId, transactional, errorStrategy, languageTag, uid);
            this.autoExecService.startJob(jobBizId, uid);
        }
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
        dispatchSegmentUnderLock(ticket, group, runtime, next, dispatchUid);
        return true;
    }

    /** Type-specific dispatch of an already-EXECUTING-marked segment (caller persists runtime after). */
    private void dispatchSegmentUnderLock(DmApprovalDO ticket, DmTicketDbStmtDO group,
                                          List<TicketSegment> runtime, TicketSegment seg, String uid) {
        if (TicketSegment.TYPE_GHOST.equals(seg.getType())) {
            this.govTicketV2Service.dispatchGhostSegment(ticket, group, seg,
                GovTicketV2Service.GHOST_VIA_SEGMENT_CHAIN);
            persistRuntime(group, runtime); // the launch recorded sessionId/taskId on the segment
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
            dispatchSegmentUnderLock(ticket, group, runtime, current, SYSTEM_OPERATOR);
            return;
        }

        // current EXECUTING → liveness probe by type
        if (TicketSegment.TYPE_GHOST.equals(current.getType())) {
            probeGhostSegmentUnderLock(ticket, group, runtime, current);
        } else {
            probeAutoexecSegmentUnderLock(ticket, group, runtime, current);
        }
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
        log.warn("[TicketSegment] segment {} of group {} judged dead by the sweep probe: {}",
            seg.getSegIdx(), group.getId(), detail);
        advanceUnderLock(ticket, group, runtime, seg, false, detail, null, SYSTEM_OPERATOR);
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

    /** Persist the runtime JSON and keep the in-memory group DO consistent (createGroupJob reads it back). */
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

    /** D15 group-level exec config: the group's own precheck changeType (DML → transactional). */
    private static boolean resolveTransactional(DmTicketDbStmtDO group) {
        try {
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
