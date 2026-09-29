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

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.clougence.clouddm.console.web.service.ghost.GhostService;
import com.clougence.clouddm.console.web.service.ghost.asyntask.GhostAsyncTaskConfig;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.TicketDbStmtDal;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.execution.AsyncTaskStatus;
import com.clougence.clouddm.platform.dal.model.execution.DmExecAsyncTaskDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;
import com.clougence.utils.JsonUtils;
import com.clougence.utils.StringUtils;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * B6 M1 (design §6.4): the ghost-group completion writeback — a MIRROR (not a modification) of
 * {@code AutoExecServiceImpl.handleV2JobCompletion} for the single-segment degenerate shape.
 * The frozen contract is untouched: ticket row lock ({@code selectByIdForUpdate}) BEFORE the
 * group status write, group exec_status/exec_detail write, then the all-groups-terminal
 * aggregation (any FAILED → failExecution / all SUCCESS → completeExecution).
 * <p>
 * Dual-event-source CAS guard: the GHOST AsyncTask terminal hook, the console stop endpoint and
 * the WAIT_EXEC sweep all converge here (or probe under the sweep's own lock) — the writeback
 * only proceeds while the group is still EXECUTING, so callback replays / AsyncTask restart
 * resets / sweep races can never double-drive the ticket state machine.
 * <p>
 * R3 ruling: an unknown outcome (session lost, writeback lost) is judged FAILED — a retryable
 * failure beats a false SUCCESS (a FINISHED ticket over an unchanged table is unacceptable).
 */
@Slf4j
@Service
public class GhostGroupCompletionService {

    private static final String EXEC_STATUS_EXECUTING = "EXECUTING";
    private static final String EXEC_STATUS_SUCCESS   = "SUCCESS";
    private static final String EXEC_STATUS_FAILED    = "FAILED";

    @Resource
    private ApprovalDal            approvalDal;
    @Resource
    private TicketDbStmtDal        ticketDbStmtDal;
    @Resource
    private com.clougence.clouddm.console.web.component.approval.ApprovalStateService approvalStateService;
    // @Lazy: the ghost service surface reaches back into the governance component layer
    //   (completion writeback on the stop path) — safe-side proxy injection (iron rule).
    @org.springframework.context.annotation.Lazy
    @Resource
    private GhostService           ghostService;

    /**
     * GhostAsyncTask terminal hook / stop-endpoint writeback. Mirrors handleV2JobCompletion
     * (:757-788) semantics: row lock first, CAS-guarded group write, then aggregation.
     * <p>
     * The hook method carries its own transaction — the AsyncTask worker thread has no
     * transaction context (the design §6.3 implementation-difference note vs the RSocket
     * reportMessage entry).
     *
     * @param success true ONLY for the tool status COMPLETED; FAILED/ABORTED/unknown → false
     * @param detail  exec_detail text (pure error semantics — the frontend renders it verbatim)
     */
    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRED)
    public void onGhostTerminal(long ticketId, long groupId, boolean success, String detail) {
        // Lock the ticket row BEFORE writing the group status (handleV2JobCompletion contract:
        // serializes the concurrent event sources per ticket — single lock, same order).
        DmApprovalDO ticket = this.approvalDal.approvalMapper().selectByIdForUpdate(ticketId);
        if (ticket == null || ApprovalStatus.isEndStatus(ticket.getTicketStatus())) {
            return; // ticket gone or already driven terminal by the other event source / sweep
        }
        DmTicketDbStmtDO group = this.ticketDbStmtDal.stmtMapper().queryById(groupId);
        if (group == null || !EXEC_STATUS_EXECUTING.equals(group.getExecStatus())) {
            // CAS guard: a replayed hook, a sweep that already judged the group, or a retryGroup
            // rebuild that already moved on — the first writer wins, later writers skip.
            return;
        }

        this.ticketDbStmtDal.stmtMapper().updateExecStatus(groupId, success ? EXEC_STATUS_SUCCESS : EXEC_STATUS_FAILED, detail);

        List<DmTicketDbStmtDO> allGroups = this.ticketDbStmtDal.stmtMapper().queryByTicketId(ticketId);
        boolean allTerminal = allGroups.stream().allMatch(g ->
            EXEC_STATUS_SUCCESS.equals(g.getExecStatus()) || EXEC_STATUS_FAILED.equals(g.getExecStatus()));
        if (allTerminal) {
            boolean anyFailed = allGroups.stream().anyMatch(g -> EXEC_STATUS_FAILED.equals(g.getExecStatus()));
            if (anyFailed) {
                this.approvalStateService.failExecution(ticket.getBizId(),
                    "One or more database groups failed execution");
            } else {
                this.approvalStateService.completeExecution(ticket.getBizId());
            }
        }
    }

    /**
     * WAIT_EXEC sweep liveness probe (R2, design §6.3): the CALLER (recoverV2CompletionIfDue)
     * already holds the ticket row lock — this method performs NO lock acquisition and NO
     * aggregation (the sweep's own tail aggregates uniformly).
     * <p>
     * Judgement for an EXECUTING group bound to a ghost migration:
     * <ul>
     * <li>task missing → FAILED (anomalous — the binding row was never deleted by design);</li>
     * <li>task terminal (COMPLETE/FAILURE/CANCEL) while the group is still EXECUTING → the
     *     writeback was lost → FAILED (R3: unknown outcome fails, never a false SUCCESS);</li>
     * <li>task active (WAIT_START/RUNNING/BLOCK/PAUSE/…) → probe the tool session: alive →
     *     healthy, skip; session gone → the host-affinity orphan / sidecar-restart shape →
     *     FAILED.</li>
     * </ul>
     * Best-effort: any probe failure is caught by the caller and retried on the next sweep cycle.
     *
     * @return true when the group was judged dead and written FAILED
     */
    public boolean probeGhostGroupUnderLock(DmApprovalDO lockedTicket, DmTicketDbStmtDO group) {
        DmExecAsyncTaskDO task = this.ghostService.findTaskForTicketGroup(lockedTicket.getId(), group.getId());
        if (task == null) {
            return failGroup(group.getId(), "ghost task record missing while the group is EXECUTING");
        }
        AsyncTaskStatus status = task.getStatus();
        if (isTerminalTaskStatus(status)) {
            return failGroup(group.getId(), "ghost completion writeback lost (task " + status + ")");
        }
        // active task → session liveness decides (host-affinity orphan / sidecar restart)
        GhostAsyncTaskConfig cfg = parseTaskConfig(task);
        String ownerUid = cfg != null && StringUtils.isNotBlank(cfg.getUserId()) ? cfg.getUserId() : task.getUid();
        String sessionId = task.getBizId();
        if (this.ghostService.hasSession(ownerUid, sessionId)) {
            return false; // healthy: the migration is still in flight
        }
        return failGroup(group.getId(), "ghost session lost while the task is " + status + " (result unknown)");
    }

    private boolean failGroup(long groupId, String detail) {
        log.warn("[GhostCompletion] group {} judged dead by the sweep probe: {}", groupId, detail);
        this.ticketDbStmtDal.stmtMapper().updateExecStatus(groupId, EXEC_STATUS_FAILED, detail);
        return true;
    }

    static boolean isTerminalTaskStatus(AsyncTaskStatus status) {
        return status == AsyncTaskStatus.COMPLETE || status == AsyncTaskStatus.FAILURE || status == AsyncTaskStatus.CANCEL;
    }

    private static GhostAsyncTaskConfig parseTaskConfig(DmExecAsyncTaskDO task) {
        if (task == null || StringUtils.isBlank(task.getConfigData())) {
            return null;
        }
        try {
            return JsonUtils.toObj(task.getConfigData(), GhostAsyncTaskConfig.class);
        } catch (Exception e) {
            // JsonUtils sneaky-throws the CHECKED JsonProcessingException — catch Exception
            log.warn("[GhostCompletion] task configData unparsable, taskId={}", task.getId(), e);
            return null;
        }
    }
}
