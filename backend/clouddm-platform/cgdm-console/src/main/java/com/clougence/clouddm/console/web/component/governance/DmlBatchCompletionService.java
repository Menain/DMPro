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
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.TicketDbStmtDal;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;
import com.clougence.utils.StringUtils;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * B7 lock-free DML (design §5.3) — the DML_BATCH completion writeback: the THIRD callback caller
 * of the advanceSegment core, the structural mirror of {@link GhostGroupCompletionService}
 * (which stays untouched). Contract points, all inherited verbatim:
 * <ul>
 * <li>the hook method carries its OWN {@code @Transactional} — the AsyncTask worker thread has
 *     no transaction context (unlike the RSocket reportMessage entry);</li>
 * <li>ticket row lock ({@code selectByIdForUpdate}) BEFORE the segment write — single lock, same
 *     order as every other writer event source (callback / ghost hook / batch hook / sweep),
 *     no AB-BA deadlock;</li>
 * <li>the segment-status CAS + taskId cross-check lives in
 *     {@link TicketSegmentService#completeDmlBatchSegmentUnderLock} (B6 debt #5 discharged:
 *     {@code segment.taskId ≡ firing-task} — a stale/replayed task hook can never advance a
 *     rebuilt segment);</li>
 * <li>a CAS skip returns BEFORE the aggregation tail — exactly-once, first writer wins;</li>
 * <li>the all-groups aggregation tail is the SHARED shape (handleV2JobCompletion /
 *     onGhostTerminal mirror): this service never drives the ticket state machine beyond it.</li>
 * </ul>
 * A DML_BATCH segment exists ONLY inside a segmented runtime (there is no M1-style degenerate
 * single-segment shape — B7 launched with the segment plan from day one), so a blank runtime
 * here is a carrier anomaly: the defensive direct group write (CAS on group EXECUTING) keeps
 * the ticket aggregatable instead of sticking forever (legacyFallbackWrite semantics).
 */
@Slf4j
@Service
public class DmlBatchCompletionService {

    private static final String EXEC_STATUS_EXECUTING = "EXECUTING";
    private static final String EXEC_STATUS_SUCCESS   = "SUCCESS";
    private static final String EXEC_STATUS_FAILED    = "FAILED";

    @Resource
    private ApprovalDal      approvalDal;
    @Resource
    private TicketDbStmtDal  ticketDbStmtDal;
    @Resource
    private com.clougence.clouddm.console.web.component.approval.ApprovalStateService approvalStateService;
    // @Lazy: the orchestrator lazy-reaches the service layer back (dispatchBatchSegment) —
    //   safe-side proxy injection (governance component iron rule).
    @org.springframework.context.annotation.Lazy
    @Resource
    private TicketSegmentService ticketSegmentService;

    /**
     * DmlBatchAsyncTask terminal hook (success / batch-failure-exhausted / UNRESOLVED window /
     * user abort — every terminal transition of the batch loop). Runs BEFORE the task status
     * transition (its own transaction commits independently); a failure here degrades to the
     * caller's error log and the WAIT_EXEC sweep heartbeat probe backstops the group judgement.
     *
     * @param segmentIndex the configData segment binding (always present for ticket-bound tasks)
     * @param taskId       the firing task's row id — the CAS cross-check key (debt #5)
     * @param success      true ONLY on the boundary-NULL full completion
     * @param detail       exec_detail text (pure-error semantics; the frontend renders verbatim)
     * @param summary      the checkpoint freeze {batchesDone, rowsDone, cursor?, error?,
     *                     unresolvedIntent?, intentSeq?} — the segment's forensic trail
     */
    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRED)
    public void onBatchTerminal(long ticketId, long groupId, Integer segmentIndex, Long taskId,
                                boolean success, String detail, Map<String, Object> summary) {
        // Lock the ticket row BEFORE writing any group/segment state (handleV2JobCompletion
        // contract: serializes the concurrent event sources per ticket — single lock, same order).
        DmApprovalDO ticket = this.approvalDal.approvalMapper().selectByIdForUpdate(ticketId);
        if (ticket == null || ApprovalStatus.isEndStatus(ticket.getTicketStatus())) {
            return; // ticket gone or already driven terminal by the other event source / sweep
        }
        DmTicketDbStmtDO group = this.ticketDbStmtDal.stmtMapper().queryById(groupId);
        if (group == null) {
            return;
        }

        if (StringUtils.isNotBlank(group.getExecSegments())) {
            // the group row IS the fresh under-lock read — the orchestrator owns the segment CAS
            // (+ taskId cross-check) and the chained next-segment dispatch
            boolean advanced = this.ticketSegmentService.completeDmlBatchSegmentUnderLock(
                ticket, group, segmentIndex, taskId, success, detail, summary);
            if (!advanced) {
                return; // CAS skip: replayed hook / the sweep already judged this segment
            }
        } else {
            // carrier anomaly (a DML_BATCH segment cannot exist without a runtime) — defensive
            // direct group write so the ticket still aggregates instead of sticking forever
            if (!EXEC_STATUS_EXECUTING.equals(group.getExecStatus())) {
                return; // CAS guard: the first writer wins
            }
            log.error("[DmlBatchCompletion] group {} reached a batch terminal WITHOUT a segment runtime — degrading to the direct group write",
                groupId);
            this.ticketDbStmtDal.stmtMapper().updateExecStatus(groupId,
                success ? EXEC_STATUS_SUCCESS : EXEC_STATUS_FAILED, detail);
        }

        // shared all-groups aggregation tail (handleV2JobCompletion / onGhostTerminal mirror —
        // unchanged semantics: "all groups terminal → ticket terminal", exactly once)
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
}
