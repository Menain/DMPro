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

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.console.web.component.approval.ApprovalStateService;
import com.clougence.clouddm.console.web.service.ghost.GhostService;
import com.clougence.clouddm.console.web.service.ghost.asyntask.GhostAsyncTaskConfig;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.TicketDbStmtDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.govticket.DmTicketDbStmtMapper;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.execution.AsyncTaskStatus;
import com.clougence.clouddm.platform.dal.model.execution.DmExecAsyncTaskDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;
import com.clougence.utils.JsonUtils;

/**
 * B6 M1 (Step 7a): the ghost-group terminal writeback — the FOUR mirrored semantics of
 * handleV2JobCompletion (row-lock order / group status write / all-groups aggregation /
 * dual-event-source CAS guard) plus the sweep liveness probe judgement matrix (R2/R3:
 * missing task, lost writeback, dead session — all judged FAILED; alive → untouched).
 */
public class GhostGroupCompletionServiceTest {

    private GhostGroupCompletionService service;
    private ApprovalDal                 approvalDal;
    private DmApprovalMapper            approvalMapper;
    private TicketDbStmtDal             ticketDbStmtDal;
    private DmTicketDbStmtMapper        stmtMapper;
    private ApprovalStateService        approvalStateService;
    private GhostService                ghostService;

    private static final long TICKET_ID = 900L;
    private static final long GROUP_ID  = 100L;
    private static final String BIZ_ID  = "biz-900";

    @Before
    public void setUp() {
        service = new GhostGroupCompletionService();
        approvalDal = mock(ApprovalDal.class);
        approvalMapper = mock(DmApprovalMapper.class);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);
        ticketDbStmtDal = mock(TicketDbStmtDal.class);
        stmtMapper = mock(DmTicketDbStmtMapper.class);
        when(ticketDbStmtDal.stmtMapper()).thenReturn(stmtMapper);
        approvalStateService = mock(ApprovalStateService.class);
        ghostService = mock(GhostService.class);

        ReflectionTestUtils.setField(service, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(service, "ticketDbStmtDal", ticketDbStmtDal);
        ReflectionTestUtils.setField(service, "approvalStateService", approvalStateService);
        ReflectionTestUtils.setField(service, "ghostService", ghostService);
    }

    // ==================== onGhostTerminal: the four mirrored semantics ====================

    @Test
    public void onTerminal_success_locksTicketBeforeGroupWrite_thenCompletes() {
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(ticket(ApprovalStatus.RUNNING));
        when(stmtMapper.queryById(GROUP_ID)).thenReturn(group("EXECUTING"));
        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(group("SUCCESS")));

        service.onGhostTerminal(TICKET_ID, GROUP_ID, true, null);

        // semantics 1: the ticket row lock precedes the group status write
        InOrder inOrder = inOrder(approvalMapper, stmtMapper);
        inOrder.verify(approvalMapper).selectByIdForUpdate(TICKET_ID);
        inOrder.verify(stmtMapper).updateExecStatus(GROUP_ID, "SUCCESS", null);
        // semantics 3: single SUCCESS group = all terminal → completeExecution
        verify(approvalStateService).completeExecution(BIZ_ID);
        verify(approvalStateService, never()).failExecution(anyString(), any());
    }

    @Test
    public void onTerminal_failure_writesDetail_andFailsTicket() {
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(ticket(ApprovalStatus.RUNNING));
        when(stmtMapper.queryById(GROUP_ID)).thenReturn(group("EXECUTING"));
        DmTicketDbStmtDO written = group("FAILED");
        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(written));

        service.onGhostTerminal(TICKET_ID, GROUP_ID, false, "ghost migration failed: FATAL binlog");

        // semantics 2: FAILED + exec_detail carries the pure error text
        verify(stmtMapper).updateExecStatus(GROUP_ID, "FAILED", "ghost migration failed: FATAL binlog");
        verify(approvalStateService).failExecution(eq(BIZ_ID), anyString());
        verify(approvalStateService, never()).completeExecution(anyString());
    }

    @Test
    public void onTerminal_notAllGroupsTerminal_noTicketAction() {
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(ticket(ApprovalStatus.RUNNING));
        when(stmtMapper.queryById(GROUP_ID)).thenReturn(group("EXECUTING"));
        DmTicketDbStmtDO other = group("PENDING");
        other.setId(101L);
        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(group("SUCCESS"), other));

        service.onGhostTerminal(TICKET_ID, GROUP_ID, true, null);

        verify(stmtMapper).updateExecStatus(GROUP_ID, "SUCCESS", null);
        verify(approvalStateService, never()).completeExecution(anyString());
        verify(approvalStateService, never()).failExecution(anyString(), any());
    }

    @Test
    public void onTerminal_multiGroupAnyFailed_failsTicket() {
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(ticket(ApprovalStatus.RUNNING));
        when(stmtMapper.queryById(GROUP_ID)).thenReturn(group("EXECUTING"));
        DmTicketDbStmtDO other = group("SUCCESS");
        other.setId(101L);
        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(group("FAILED"), other));

        service.onGhostTerminal(TICKET_ID, GROUP_ID, false, "boom");

        verify(approvalStateService).failExecution(eq(BIZ_ID), anyString());
    }

    @Test
    public void onTerminal_replayAfterTerminalGroup_casGuardSkipsEverything() {
        // semantics 4 (dual-event-source CAS): the sweep / the other hook already finalized the
        // group — a replayed writeback must not rewrite it nor re-drive the aggregation
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(ticket(ApprovalStatus.RUNNING));
        when(stmtMapper.queryById(GROUP_ID)).thenReturn(group("FAILED"));

        service.onGhostTerminal(TICKET_ID, GROUP_ID, true, null);

        verify(stmtMapper, never()).updateExecStatus(anyLong(), anyString(), any());
        verifyNoInteractions(approvalStateService);
    }

    @Test
    public void onTerminal_groupStillPending_casGuardSkips() {
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(ticket(ApprovalStatus.RUNNING));
        when(stmtMapper.queryById(GROUP_ID)).thenReturn(group("PENDING"));

        service.onGhostTerminal(TICKET_ID, GROUP_ID, true, null);

        verify(stmtMapper, never()).updateExecStatus(anyLong(), anyString(), any());
        verifyNoInteractions(approvalStateService);
    }

    @Test
    public void onTerminal_ticketAlreadyEndStatus_earlyReturn() {
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(ticket(ApprovalStatus.FINISHED));

        service.onGhostTerminal(TICKET_ID, GROUP_ID, true, null);

        verify(stmtMapper, never()).queryById(anyLong());
        verify(stmtMapper, never()).updateExecStatus(anyLong(), anyString(), any());
        verifyNoInteractions(approvalStateService);
    }

    @Test
    public void onTerminal_ticketMissing_stopsAfterLock() {
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(null);

        service.onGhostTerminal(TICKET_ID, GROUP_ID, true, null);

        verify(stmtMapper, never()).updateExecStatus(anyLong(), anyString(), any());
        verifyNoInteractions(approvalStateService);
    }

    @Test
    public void onTerminal_groupRowMissing_stopsAfterLock() {
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(ticket(ApprovalStatus.RUNNING));
        when(stmtMapper.queryById(GROUP_ID)).thenReturn(null);

        service.onGhostTerminal(TICKET_ID, GROUP_ID, true, null);

        verify(stmtMapper, never()).updateExecStatus(anyLong(), anyString(), any());
    }

    // ==================== sweep liveness probe (R2/R3 judgement matrix) ====================

    @Test
    public void probe_taskMissing_judgedFailed() {
        when(ghostService.findTaskForTicketGroup(TICKET_ID, GROUP_ID)).thenReturn(null);

        assertTrue(service.probeGhostGroupUnderLock(ticket(ApprovalStatus.RUNNING), group("EXECUTING")));

        verify(stmtMapper).updateExecStatus(eq(GROUP_ID), eq("FAILED"), contains("record missing"));
    }

    @Test
    public void probe_taskTerminalButGroupExecuting_writebackLost_judgedFailed() {
        // R3: a COMPLETE task with the group still EXECUTING means the hook never committed —
        // the outcome is unknown, so it FAILS (retryable) instead of mirroring a false SUCCESS
        when(ghostService.findTaskForTicketGroup(TICKET_ID, GROUP_ID))
            .thenReturn(task(AsyncTaskStatus.COMPLETE));

        assertTrue(service.probeGhostGroupUnderLock(ticket(ApprovalStatus.RUNNING), group("EXECUTING")));

        verify(stmtMapper).updateExecStatus(eq(GROUP_ID), eq("FAILED"), contains("writeback lost"));
    }

    @Test
    public void probe_taskActiveSessionAlive_healthy_noWrite() {
        when(ghostService.findTaskForTicketGroup(TICKET_ID, GROUP_ID)).thenReturn(task(AsyncTaskStatus.RUNNING));
        when(ghostService.hasSession("uid-launcher", "session-1")).thenReturn(true);

        assertFalse(service.probeGhostGroupUnderLock(ticket(ApprovalStatus.RUNNING), group("EXECUTING")));

        verify(stmtMapper, never()).updateExecStatus(anyLong(), anyString(), any());
    }

    @Test
    public void probe_taskPausedSessionAlive_healthy_noWrite() {
        // a user-paused (throttled) migration is alive — PAUSE is in the active set
        when(ghostService.findTaskForTicketGroup(TICKET_ID, GROUP_ID)).thenReturn(task(AsyncTaskStatus.PAUSE));
        when(ghostService.hasSession("uid-launcher", "session-1")).thenReturn(true);

        assertFalse(service.probeGhostGroupUnderLock(ticket(ApprovalStatus.RUNNING), group("EXECUTING")));

        verify(stmtMapper, never()).updateExecStatus(anyLong(), anyString(), any());
    }

    @Test
    public void probe_taskActiveSessionGone_judgedFailed() {
        when(ghostService.findTaskForTicketGroup(TICKET_ID, GROUP_ID)).thenReturn(task(AsyncTaskStatus.RUNNING));
        when(ghostService.hasSession("uid-launcher", "session-1")).thenReturn(false);

        assertTrue(service.probeGhostGroupUnderLock(ticket(ApprovalStatus.RUNNING), group("EXECUTING")));

        verify(stmtMapper).updateExecStatus(eq(GROUP_ID), eq("FAILED"), contains("session lost"));
    }

    @Test
    public void probe_ownerUidFallsBackToTaskUid_whenConfigUnparsable() {
        DmExecAsyncTaskDO broken = task(AsyncTaskStatus.RUNNING);
        broken.setConfigData("{broken");
        broken.setUid("uid-taskrow");
        when(ghostService.findTaskForTicketGroup(TICKET_ID, GROUP_ID)).thenReturn(broken);
        when(ghostService.hasSession("uid-taskrow", "session-1")).thenReturn(true);

        assertFalse(service.probeGhostGroupUnderLock(ticket(ApprovalStatus.RUNNING), group("EXECUTING")));

        verify(ghostService).hasSession("uid-taskrow", "session-1");
    }

    // ==================== helpers ====================

    private static DmApprovalDO ticket(ApprovalStatus status) {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setBizId(BIZ_ID);
        ticket.setTicketStatus(status);
        return ticket;
    }

    private static DmTicketDbStmtDO group(String execStatus) {
        DmTicketDbStmtDO group = new DmTicketDbStmtDO();
        group.setId(GROUP_ID);
        group.setTicketId(TICKET_ID);
        group.setExecStatus(execStatus);
        return group;
    }

    private static DmExecAsyncTaskDO task(AsyncTaskStatus status) {
        GhostAsyncTaskConfig cfg = new GhostAsyncTaskConfig();
        cfg.setUserId("uid-launcher");
        cfg.setSessionId("session-1");
        cfg.setTicketId(TICKET_ID);
        cfg.setGroupId(GROUP_ID);
        DmExecAsyncTaskDO task = new DmExecAsyncTaskDO();
        task.setId(77L);
        task.setBizId("session-1");
        task.setBizType("GHOST");
        task.setStatus(status);
        task.setConfigData(JsonUtils.toJson(cfg));
        return task;
    }
}
