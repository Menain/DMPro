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
package com.clougence.clouddm.console.web.service.dmlbatch;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.console.web.component.execute.AsyncTaskService;
import com.clougence.clouddm.console.web.component.governance.TicketSegment;
import com.clougence.clouddm.console.web.global.i18n.DmI18nUtils;
import com.clougence.clouddm.console.web.global.i18n.I18nDmMsgKeys;
import com.clougence.clouddm.console.web.service.dmlbatch.asyntask.DmlBatchAsyncTaskConfig;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.DbChangeEventDal;
import com.clougence.clouddm.platform.dal.access.TicketDbStmtDal;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO;
import com.clougence.clouddm.platform.dal.model.dbchange.GovEventType;
import com.clougence.clouddm.platform.dal.model.execution.AsyncTaskStatus;
import com.clougence.clouddm.platform.dal.model.execution.DmExecAsyncTaskDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;
import com.clougence.utils.JsonUtils;
import com.clougence.utils.StringUtils;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * B7 N2 ticket-chain control plane of a DML_BATCH segment (design §6.1/§6.2/§6.4, 裁决 #11):
 * pause / resume / stop over the batch AsyncTask, located through the ticket's own coordinates
 * ({@code DmlBatchService.findTaskForTicketGroup}) — the dock's raw operations are guard-rejected
 * for ticket-bound tasks (§6.5), so THIS service is the single legitimate entry (单入口原则).
 * <p>
 * Semantics:
 * <ul>
 * <li><b>pause</b> — batch granularity (裁决 #11): the framework pauseTask cancels the tick loop's
 *     future; an IN-FLIGHT batch always runs to completion first (the tick's isPause check sits
 *     at the batch boundary and writes the pause heartbeat — 暂停中心跳照写). The task settles at
 *     DB status PAUSE, which the sweep treats as CONTROLLED-HEALTHY regardless of heartbeat age
 *     (a legitimate pause may last indefinitely; PAUSE has no host affinity). Ticket/group/segment
 *     statuses are NEVER touched (the B6 throttle = session-level discipline);</li>
 * <li><b>resume</b> — framework resumeTask: PAUSE → WAIT_START with a console_ip REBIND (any
 *     console can resume — no host affinity), a fresh tick re-enters the loop with the checkpoint
 *     re-read from configData;</li>
 * <li><b>stop</b> — framework cancelTask → the tick's isCancel branch writes the terminal
 *     (ABORTED: partial success KEPT, NO automatic rollback → segment FAILED → group FAILED →
 *     ticket EXEC_FAIL through the shared aggregation tail). A task cancelled while IDLE (delay-
 *     waiting on another host is impossible — see below — but PAUSE is) transitions in the DB
 *     directly and the sweep's terminal-task row advances the segment within one cycle (~7s).</li>
 * </ul>
 * Host affinity (the AsyncTask framework's own semantics, surfaced honestly): pause/stop of a
 * RUNNING task only take effect on the console hosting the tick loop — {@code pauseTask/
 * cancelTask} throw IllegalStateException when the row says RUNNING but the task is not in the
 * local schedule map; this service translates that into DML_BATCH_TASK_REMOTE_RUNNING guidance
 * (resume and stop-of-a-PAUSE-task work from ANY console — DB-state transitions).
 * <p>
 * Trail (裁决 #13): every successful operation appends the ticket event (DML_BATCH_PAUSE /
 * RESUME / ABORT — operator_uid = the real acting user, eventData = the task/segment locator).
 * NO op-audit row (the ticket-chain HTTP surface does not write op-audit — platform status quo;
 * the dispatch/resolution audits are the system-action exceptions). The event insert is
 * best-effort AFTER the framework operation succeeded: a trail failure logs and never reverts
 * the control action (the framework state is the truth).
 * <p>
 * Validation order (design §6.1): tenant (uniform not-found) → group belongs to the ticket →
 * task located → the bound segment is EXECUTING → the task status admits the operation.
 */
@Slf4j
@Service
public class DmlBatchControlService {

    private static final String EXEC_STATUS_EXECUTING = "EXECUTING";

    @Resource
    private ApprovalDal           approvalDal;
    @Resource
    private TicketDbStmtDal       ticketDbStmtDal;
    @Resource
    private DbChangeEventDal      dbChangeEventDal;
    @Resource
    private AsyncTaskService      asyncTaskService;
    // @Lazy: the engine service lives in the same layer but lazy-reaches governance components
    //   back through the completion hook — safe-side proxy injection (iron rule).
    @org.springframework.context.annotation.Lazy
    @Resource
    private DmlBatchService       dmlBatchService;

    /** batch-granularity pause (design §6.2) — the current batch always finishes first. */
    public void pause(String puid, String uid, long ticketId, long groupId, Integer segmentIndex) {
        DmApprovalDO ticket = locateTicket(puid, ticketId);
        DmTicketDbStmtDO group = locateGroup(ticketId, groupId);
        DmExecAsyncTaskDO task = locateTask(ticketId, groupId, segmentIndex);
        assertSegmentExecuting(group, task);
        AsyncTaskStatus status = task.getStatus();
        if (status != AsyncTaskStatus.RUNNING && status != AsyncTaskStatus.BLOCK) {
            // WAIT_START (about to be picked up — retry in a moment), PAUSE (already paused),
            // PAUSING/CANCELING (transient settle) and every terminal all reject
            throw stateInvalid(status);
        }
        try {
            this.asyncTaskService.pauseTask(task.getId(),
                "ticket-chain pause by uid=" + uid + " (ticket " + ticketId + ")");
        } catch (IllegalStateException e) {
            // the framework's "RUNNING but not in the local schedule map" = the tick loop lives
            // on another console (host affinity — surfaced honestly, never silently swallowed)
            throw remoteRunning();
        }
        appendControlEvent(ticket.getId(), uid, GovEventType.DML_BATCH_PAUSE, group.getId(),
            resolveSegmentIndex(task, segmentIndex), task);
    }

    /** resume of a paused batch task (design §6.2) — PAUSE is a DB state, any console can resume. */
    public void resume(String puid, String uid, long ticketId, long groupId, Integer segmentIndex) {
        DmApprovalDO ticket = locateTicket(puid, ticketId);
        DmTicketDbStmtDO group = locateGroup(ticketId, groupId);
        DmExecAsyncTaskDO task = locateTask(ticketId, groupId, segmentIndex);
        assertSegmentExecuting(group, task);
        if (task.getStatus() != AsyncTaskStatus.PAUSE) {
            throw stateInvalid(task.getStatus());
        }
        this.asyncTaskService.resumeTask(task.getId(),
            "ticket-chain resume by uid=" + uid + " (ticket " + ticketId + ")");
        appendControlEvent(ticket.getId(), uid, GovEventType.DML_BATCH_RESUME, group.getId(),
            resolveSegmentIndex(task, segmentIndex), task);
    }

    /**
     * termination (design §6.4): partial success KEPT, NO automatic rollback — the cancel drives
     * segment FAILED → group FAILED → ticket EXEC_FAIL (through the tick's isCancel writeback or,
     * for an idle/paused task, the sweep's terminal-task advance row within one cycle).
     */
    public void stop(String puid, String uid, long ticketId, long groupId, Integer segmentIndex) {
        DmApprovalDO ticket = locateTicket(puid, ticketId);
        DmTicketDbStmtDO group = locateGroup(ticketId, groupId);
        DmExecAsyncTaskDO task = locateTask(ticketId, groupId, segmentIndex);
        assertSegmentExecuting(group, task);
        AsyncTaskStatus status = task.getStatus();
        if (status != AsyncTaskStatus.RUNNING && status != AsyncTaskStatus.BLOCK
            && status != AsyncTaskStatus.PAUSE && status != AsyncTaskStatus.INIT) {
            // WAIT_START: the framework cancelTask has no transition for it (silent no-op — the
            // task would then be picked up and run); the honest answer is "retry in a moment"
            // (state-invalid copy). PAUSING/CANCELING: transient settle. Terminals: nothing to stop.
            throw stateInvalid(status);
        }
        try {
            this.asyncTaskService.cancelTask(task.getId(),
                "ticket-chain stop by uid=" + uid + " (ticket " + ticketId + ")");
        } catch (IllegalStateException e) {
            throw remoteRunning();
        }
        appendControlEvent(ticket.getId(), uid, GovEventType.DML_BATCH_ABORT, group.getId(),
            resolveSegmentIndex(task, segmentIndex), task);
    }

    // ==================================================================================
    // validation + trail helpers
    // ==================================================================================

    private DmApprovalDO locateTicket(String puid, long ticketId) {
        DmApprovalDO ticket = this.approvalDal.approvalMapper().queryById(ticketId);
        // uniform not-found across "missing" and "other tenant" (the retryGroup pattern)
        if (ticket == null || !puid.equals(ticket.getPrimaryUid())) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(
                I18nDmMsgKeys.GOV_TICKET_V2_TICKET_NOT_FOUND.name(), ticketId));
        }
        return ticket;
    }

    private DmTicketDbStmtDO locateGroup(long ticketId, long groupId) {
        DmTicketDbStmtDO group = this.ticketDbStmtDal.stmtMapper().queryById(groupId);
        if (group == null || group.getTicketId() == null || group.getTicketId() != ticketId) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(
                I18nDmMsgKeys.GOV_TICKET_V2_GROUP_NOT_FOUND.name(), groupId));
        }
        return group;
    }

    private DmExecAsyncTaskDO locateTask(long ticketId, long groupId, Integer segmentIndex) {
        DmExecAsyncTaskDO task = this.dmlBatchService.findTaskForTicketGroup(ticketId, groupId, segmentIndex);
        if (task == null) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(
                I18nDmMsgKeys.DML_BATCH_TASK_NOT_FOUND.name()));
        }
        return task;
    }

    /**
     * Segment-state validation (design §6.1): the group must be EXECUTING and the bound segment
     * (the task's own configData segmentIndex, else the request's) must be an EXECUTING DML_BATCH
     * segment — controlling over a terminal/mid-repair group is a stale-view operation (the sweep
     * converges those shapes; the honest answer is "nothing to control"). An unparsable runtime
     * rejects (a carrier WE wrote cannot corrupt — defensive).
     */
    private void assertSegmentExecuting(DmTicketDbStmtDO group, DmExecAsyncTaskDO task) {
        boolean ok = EXEC_STATUS_EXECUTING.equals(group.getExecStatus());
        if (ok) {
            List<TicketSegment> runtime = TicketSegment.parseRuntime(group.getExecSegments());
            Integer segIdx = resolveSegmentIndex(task, null);
            TicketSegment seg = runtime == null || segIdx == null ? null : TicketSegment.byIdx(runtime, segIdx);
            ok = seg != null && TicketSegment.TYPE_DML_BATCH.equals(seg.getType())
                && TicketSegment.STATUS_EXECUTING.equals(seg.getStatus());
        }
        if (!ok) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(
                I18nDmMsgKeys.DML_BATCH_SEGMENT_NOT_EXECUTING.name()));
        }
    }

    /** the task's bound segmentIndex (configData authority), falling back to the request's. */
    private static Integer resolveSegmentIndex(DmExecAsyncTaskDO task, Integer requested) {
        DmlBatchAsyncTaskConfig cfg = DmlBatchServiceImpl.parseTaskConfig(task);
        if (cfg != null && cfg.getSegmentIndex() != null) {
            return cfg.getSegmentIndex();
        }
        return requested;
    }

    private static ErrorMessageException stateInvalid(AsyncTaskStatus status) {
        return new ErrorMessageException(DmI18nUtils.getMessage(
            I18nDmMsgKeys.DML_BATCH_TASK_STATE_INVALID.name(), status == null ? "-" : status.name()));
    }

    private static ErrorMessageException remoteRunning() {
        return new ErrorMessageException(DmI18nUtils.getMessage(
            I18nDmMsgKeys.DML_BATCH_TASK_REMOTE_RUNNING.name()));
    }

    /**
     * The event trail (裁决 #13 — the ticket-chain control actions live in dm_db_change_event,
     * NOT op-audit: the ticket-chain HTTP surface status quo). Best-effort AFTER the framework
     * operation: a trail failure logs and never reverts the control action.
     */
    private void appendControlEvent(long ticketId, String uid, GovEventType type,
                                    long groupId, Integer segmentIndex, DmExecAsyncTaskDO task) {
        try {
            DmDbChangeEventDO event = new DmDbChangeEventDO();
            event.setTicketId(ticketId);
            event.setEventType(type.name());
            event.setFromStatus(null);
            event.setToStatus(null);
            event.setOperatorUid(StringUtils.isNotBlank(uid) ? uid : "SYSTEM");
            Map<String, Object> data = new HashMap<>();
            data.put("groupId", groupId);
            data.put("segmentIndex", segmentIndex);
            data.put("taskId", task.getId());
            data.put("taskBizId", task.getBizId());
            event.setEventData(JsonUtils.toJson(data));
            this.dbChangeEventDal.eventMapper().insert(event);
        } catch (RuntimeException e) {
            log.error("[DmlBatchControl] {} event trail failed for ticket {} task {} (the control action stands)",
                type, ticketId, task.getId(), e);
        }
    }
}
