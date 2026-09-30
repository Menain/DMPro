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
package com.clougence.clouddm.console.web.component.approval.handler;

import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.console.web.component.approval.ApprovalStateService;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.component.governance.GhostGroupCompletionService;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.ExecutionDal;
import com.clougence.clouddm.platform.dal.access.TicketDbStmtDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoJobMapper;
import com.clougence.clouddm.platform.dal.mapper.govticket.DmTicketDbStmtMapper;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;
import com.clougence.utils.JsonUtils;

/**
 * B6 M1 (Step 7a, R2): the WAIT_EXEC sweep liveness-probe extension of recoverV2CompletionIfDue
 * — ghost-marked EXECUTING groups are probed under the ticket row lock, pure-autoexec tickets
 * keep the ZERO-change path, probe failures never kill the sweep, and the aggregation tail
 * stays the single place that drives the ticket terminal state (scan set unchanged).
 */
public class ChangeApprovalSweepGhostProbeTest {

    private ChangeApprovalHandler         handler;
    private ApprovalDal                   approvalDal;
    private DmApprovalMapper              approvalMapper;
    private ExecutionDal                  execDal;
    private TicketDbStmtDal               ticketDbStmtDal;
    private DmTicketDbStmtMapper          stmtMapper;
    private ApprovalStateService          approvalStateService;
    private GhostGroupCompletionService   completion;
    private com.clougence.clouddm.console.web.component.governance.TicketSegmentService segmentService;

    private static final long TICKET_ID = 900L;
    private static final String BIZ_ID  = "biz-900";

    @Before
    public void setUp() {
        handler = new ChangeApprovalHandler();
        approvalDal = mock(ApprovalDal.class);
        approvalMapper = mock(DmApprovalMapper.class);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);
        execDal = mock(ExecutionDal.class);
        DmExecAutoJobMapper autoJobMapper = mock(DmExecAutoJobMapper.class);
        when(execDal.autoJobMapper()).thenReturn(autoJobMapper);
        when(autoJobMapper.queryByDependOnBizId(BIZ_ID)).thenReturn(null); // v2 shape → recover path
        ticketDbStmtDal = mock(TicketDbStmtDal.class);
        stmtMapper = mock(DmTicketDbStmtMapper.class);
        when(ticketDbStmtDal.stmtMapper()).thenReturn(stmtMapper);
        approvalStateService = mock(ApprovalStateService.class);
        completion = mock(GhostGroupCompletionService.class);
        segmentService = mock(com.clougence.clouddm.console.web.component.governance.TicketSegmentService.class);

        ReflectionTestUtils.setField(handler, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(handler, "execDal", execDal);
        ReflectionTestUtils.setField(handler, "ticketDbStmtDal", ticketDbStmtDal);
        ReflectionTestUtils.setField(handler, "approvalStateService", approvalStateService);
        ReflectionTestUtils.setField(handler, "ghostGroupCompletionService", completion);
        ReflectionTestUtils.setField(handler, "ticketSegmentService", segmentService);

        when(approvalMapper.queryById(TICKET_ID)).thenAnswer(inv -> ticket(List.of(true)));
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenAnswer(inv -> ticket(List.of(true)));
    }

    @Test
    public void sweep_ghostTicketWithExecutingGroup_probedUnderLock() {
        DmTicketDbStmtDO executing = group(100L, "EXECUTING");
        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(executing));

        handler.executeTicket(TICKET_ID, com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz.DM_CHANGE, null);

        verify(completion).probeGhostGroupUnderLock(any(DmApprovalDO.class), eq(executing));
        // not all terminal → no aggregation yet (the probe's write decides on the next cycle
        // or through the terminal hook — the sweep never aggregates on a non-terminal read)
        verify(approvalStateService, never()).failExecution(anyString(), any());
        verify(approvalStateService, never()).completeExecution(anyString());
    }

    @Test
    public void sweep_probeJudgedDead_aggregationTailFailsTicket() {
        DmTicketDbStmtDO executing = group(100L, "EXECUTING");
        DmTicketDbStmtDO failed = group(100L, "FAILED");
        // handler tail pin: the aggregation acts on what ITS read returns. Production note
        // (check F1): the probe's judgement/writes run in their own REQUIRES_NEW transaction, so
        // a FAILED written THIS cycle is invisible to the sweep's pre-lock read view — the tail
        // aggregates it on the NEXT cycle (~7s). The two-read stubbing here pins the handler's
        // "tail sees a terminal group → failExecution" logic (the prior-cycle-judgement shape).
        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(executing), List.of(failed));
        when(completion.probeGhostGroupUnderLock(any(), eq(executing))).thenReturn(true);

        handler.executeTicket(TICKET_ID, com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz.DM_CHANGE, null);

        verify(approvalStateService).failExecution(eq(BIZ_ID), anyString());
        verify(approvalStateService, never()).completeExecution(anyString());
    }

    @Test
    public void sweep_pureAutoexecTicket_neverTouchesTheGhostProbe_zeroChange() {
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket(null));
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(ticket(null));
        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(group(100L, "EXECUTING")));

        handler.executeTicket(TICKET_ID, com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz.DM_CHANGE, null);

        verifyNoInteractions(completion);
        verify(approvalStateService, never()).failExecution(anyString(), any());
    }

    @Test
    public void sweep_probeOnlyTargetsGhostFlaggedExecutingGroups_positionally() {
        DmTicketDbStmtDO plainExecuting = group(100L, "EXECUTING");   // position 0, flag false
        DmTicketDbStmtDO ghostExecuting = group(101L, "EXECUTING");   // position 1, flag true
        DmTicketDbStmtDO ghostSuccess = group(102L, "SUCCESS");       // position 2, flag true but terminal
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket(List.of(false, true, true)));
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(ticket(List.of(false, true, true)));
        when(stmtMapper.queryByTicketId(TICKET_ID))
            .thenReturn(List.of(plainExecuting, ghostExecuting, ghostSuccess));

        handler.executeTicket(TICKET_ID, com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz.DM_CHANGE, null);

        verify(completion).probeGhostGroupUnderLock(any(), eq(ghostExecuting));
        verify(completion, never()).probeGhostGroupUnderLock(any(), eq(plainExecuting));
        verify(completion, never()).probeGhostGroupUnderLock(any(), eq(ghostSuccess));
    }

    @Test
    public void sweep_probeFailure_isolated_aggregationStillRuns() {
        DmTicketDbStmtDO ghostFailed = group(100L, "FAILED");
        // probe scan throws on the EXECUTING snapshot; aggregation re-reads all-failed
        DmTicketDbStmtDO executing = group(100L, "EXECUTING");
        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(executing), List.of(ghostFailed));
        when(completion.probeGhostGroupUnderLock(any(), any())).thenThrow(new RuntimeException("rpc down"));

        handler.executeTicket(TICKET_ID, com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz.DM_CHANGE, null);

        // the sweep survives the probe failure and the aggregation tail still fires
        verify(approvalStateService).failExecution(eq(BIZ_ID), anyString());
    }

    @Test
    public void sweep_allGhostGroupsTerminal_aggregatesWithoutProbe() {
        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(group(100L, "SUCCESS")));

        handler.executeTicket(TICKET_ID, com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz.DM_CHANGE, null);

        verify(completion, never()).probeGhostGroupUnderLock(any(), any());
        verify(approvalStateService).completeExecution(BIZ_ID);
    }

    @Test
    public void sweep_unparsableTicketInfo_probeSkipped_aggregationStillRuns() {
        // JsonUtils sneaky-throws the CHECKED JsonProcessingException — the probe's catch must be
        // Exception-shaped (parseTaskConfig precedent), otherwise one corrupt ticketInfo row would
        // abort the sweep's aggregation tail for that ticket on EVERY cycle (stuck at WAIT_EXEC).
        DmApprovalDO broken = ticket(List.of(true));
        broken.setTicketInfo("{not json");
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(broken);
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(broken);
        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(group(100L, "FAILED")));

        handler.executeTicket(TICKET_ID, com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz.DM_CHANGE, null); // must not throw

        verifyNoInteractions(completion);
        verify(approvalStateService).failExecution(eq(BIZ_ID), anyString());
    }

    @Test
    public void sweep_ticketAlreadyEndStatus_earlyReturn_regressionPin() {
        DmApprovalDO finished = ticket(List.of(true));
        finished.setTicketStatus(ApprovalStatus.FINISHED);
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(finished);

        handler.executeTicket(TICKET_ID, com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz.DM_CHANGE, null);

        verifyNoInteractions(completion);
        verify(stmtMapper, never()).queryByTicketId(anyLong());
        verifyNoInteractions(approvalStateService);
    }

    // ======= B6 M2: segmented groups route to the orchestrator repair (advance+aggregate dual duty) =======

    @Test
    public void sweep_segmentedExecutingGroup_routedToSegmentRepair_notTheM1Probe_m2() {
        DmTicketDbStmtDO segmented = group(100L, "EXECUTING");
        segmented.setExecSegments("[{\"segIdx\":1,\"type\":\"GHOST\",\"stmtIndex\":1,\"status\":\"EXECUTING\"}]");
        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(segmented));

        handler.executeTicket(TICKET_ID, com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz.DM_CHANGE, null);

        verify(segmentService).repairGroupUnderLock(any(DmApprovalDO.class), eq(segmented));
        // the M1 positional probe must NOT double-judge the same group
        verify(completion, never()).probeGhostGroupUnderLock(any(), any());
        // the scan set stays untouched: non-terminal read → the tail does not aggregate
        verify(approvalStateService, never()).failExecution(anyString(), any());
        verify(approvalStateService, never()).completeExecution(anyString());
    }

    @Test
    public void sweep_repairWritesGroupTerminal_aggregationTailFinishesTicket_m2() {
        DmTicketDbStmtDO executing = group(100L, "EXECUTING");
        executing.setExecSegments("[{\"segIdx\":1,\"type\":\"GHOST\",\"stmtIndex\":1,\"status\":\"EXECUTING\"}]");
        DmTicketDbStmtDO succeeded = group(100L, "SUCCESS");
        // first read (repair scan) → EXECUTING; second read (aggregation tail) → the repair wrote SUCCESS
        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(executing), List.of(succeeded));

        handler.executeTicket(TICKET_ID, com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz.DM_CHANGE, null);

        verify(segmentService).repairGroupUnderLock(any(DmApprovalDO.class), eq(executing));
        verify(approvalStateService).completeExecution(BIZ_ID);
    }

    @Test
    public void sweep_repairFailure_isolated_nextCycleRetries_m2() {
        DmTicketDbStmtDO executing = group(100L, "EXECUTING");
        executing.setExecSegments("[{\"segIdx\":1,\"type\":\"GHOST\",\"stmtIndex\":1,\"status\":\"EXECUTING\"}]");
        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(executing));
        doThrow(new RuntimeException("rpc down")).when(segmentService).repairGroupUnderLock(any(), any());

        handler.executeTicket(TICKET_ID, com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz.DM_CHANGE, null); // must not throw

        verify(approvalStateService, never()).failExecution(anyString(), any());
    }

    @Test
    public void sweep_unsegmentedGhostGroup_keepsTheM1Probe_regressionPin() {
        // exec_segments blank + positional flag → the M1 probe path stays byte-identical
        DmTicketDbStmtDO m1Group = group(100L, "EXECUTING"); // no execSegments
        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(m1Group));

        handler.executeTicket(TICKET_ID, com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz.DM_CHANGE, null);

        verify(completion).probeGhostGroupUnderLock(any(DmApprovalDO.class), eq(m1Group));
        verifyNoInteractions(segmentService);
    }

    // ======= helpers =======

    private static DmApprovalDO ticket(List<Boolean> ghostFlags) {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setBizId(BIZ_ID);
        ticket.setTicketStatus(ApprovalStatus.WAIT_EXEC);
        ApprovalMO mo = new ApprovalMO();
        mo.setTicketType("PRE_DDL");
        mo.setGhostRouteFlags(ghostFlags);
        ticket.setTicketInfo(JsonUtils.toJson(mo));
        return ticket;
    }

    private static DmTicketDbStmtDO group(long id, String execStatus) {
        DmTicketDbStmtDO group = new DmTicketDbStmtDO();
        group.setId(id);
        group.setTicketId(TICKET_ID);
        group.setExecStatus(execStatus);
        return group;
    }
}
