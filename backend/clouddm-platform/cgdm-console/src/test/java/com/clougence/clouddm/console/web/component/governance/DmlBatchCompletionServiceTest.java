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

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.console.web.component.approval.ApprovalStateService;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.TicketDbStmtDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.govticket.DmTicketDbStmtMapper;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;

/**
 * B7 N1b (implement.md 单测矩阵「onBatchTerminal CAS」): the third advanceSegment callback
 * caller — ticket-lock-first discipline, the orchestrator delegation with the FULL binding
 * quadruple (ticketId/groupId/segmentIndex/**taskId** — the debt #5 cross-check key must ride
 * through), exactly-once aggregation (a CAS skip returns BEFORE the tail), the dual-event-source
 * guards (ticket gone / already terminal / group gone) and the defensive blank-runtime
 * degradation. The GhostGroupCompletionServiceTest shape, mirrored.
 * <p>
 * Discipline: JUnit4 + hand-written mocks + ReflectionTestUtils + ArgumentCaptor; NO Spring.
 */
public class DmlBatchCompletionServiceTest {

    private DmlBatchCompletionService service;
    private ApprovalDal               approvalDal;
    private DmApprovalMapper          approvalMapper;
    private TicketDbStmtDal           ticketDbStmtDal;
    private DmTicketDbStmtMapper      stmtMapper;
    private ApprovalStateService      approvalStateService;
    private TicketSegmentService      ticketSegmentService;

    private static final long   TICKET_ID = 900L;
    private static final long   GROUP_ID  = 100L;
    private static final long   OTHER_GROUP_ID = 101L;
    private static final String BIZ_ID    = "ticket-biz-900";
    private static final long   TASK_ID   = 55L;

    private DmApprovalDO        ticket;
    private DmTicketDbStmtDO    group;

    @Before
    public void setUp() {
        service = new DmlBatchCompletionService();

        approvalDal = mock(ApprovalDal.class);
        approvalMapper = mock(DmApprovalMapper.class);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);
        ticketDbStmtDal = mock(TicketDbStmtDal.class);
        stmtMapper = mock(DmTicketDbStmtMapper.class);
        when(ticketDbStmtDal.stmtMapper()).thenReturn(stmtMapper);
        approvalStateService = mock(ApprovalStateService.class);
        ticketSegmentService = mock(TicketSegmentService.class);

        ReflectionTestUtils.setField(service, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(service, "ticketDbStmtDal", ticketDbStmtDal);
        ReflectionTestUtils.setField(service, "approvalStateService", approvalStateService);
        ReflectionTestUtils.setField(service, "ticketSegmentService", ticketSegmentService);

        ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setBizId(BIZ_ID);
        ticket.setTicketStatus(ApprovalStatus.WAIT_EXEC);
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(ticket);

        group = new DmTicketDbStmtDO();
        group.setId(GROUP_ID);
        group.setTicketId(TICKET_ID);
        group.setExecStatus("EXECUTING");
        group.setExecSegments("[{\"segIdx\":1,\"type\":\"DML_BATCH\",\"status\":\"EXECUTING\",\"taskId\":55}]");
        when(stmtMapper.queryById(GROUP_ID)).thenReturn(group);
        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(group));
        when(ticketSegmentService.completeDmlBatchSegmentUnderLock(any(), any(), any(), any(), anyBoolean(), any(), any()))
            .thenReturn(true);
    }

    private static Map<String, Object> summary() {
        Map<String, Object> summary = new HashMap<>();
        summary.put("batchesDone", 10L);
        summary.put("rowsDone", 50000L);
        return summary;
    }

    // ==================== delegation + the binding quadruple ====================

    @Test
    public void onBatchTerminal_success_delegatesWithFullBinding_includingTaskId() {
        service.onBatchTerminal(TICKET_ID, GROUP_ID, 1, TASK_ID, true, null, summary());

        // lock-first discipline: the ticket row lock precedes every read/write
        InOrder inOrder = Mockito.inOrder(approvalMapper, stmtMapper, ticketSegmentService);
        inOrder.verify(approvalMapper).selectByIdForUpdate(TICKET_ID);
        inOrder.verify(stmtMapper).queryById(GROUP_ID);
        // the FULL quadruple rides into the CAS — the taskId is the debt #5 cross-check key
        inOrder.verify(ticketSegmentService).completeDmlBatchSegmentUnderLock(
            eq(ticket), eq(group), eq(1), eq(TASK_ID), eq(true), isNull(), any());
    }

    @Test
    public void onBatchTerminal_failure_detailAndSummaryRideThrough() {
        Map<String, Object> summary = summary();
        summary.put("unresolvedIntent", Boolean.TRUE);

        service.onBatchTerminal(TICKET_ID, GROUP_ID, 1, TASK_ID, false, "批 3 执行结果未知", summary);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(ticketSegmentService).completeDmlBatchSegmentUnderLock(
            eq(ticket), eq(group), eq(1), eq(TASK_ID), eq(false), eq("批 3 执行结果未知"), captor.capture());
        assertEquals(Boolean.TRUE, captor.getValue().get("unresolvedIntent"));
    }

    // ==================== aggregation tail (shared, exactly once) ====================

    @Test
    public void onBatchTerminal_advanced_lastGroupSuccess_completeExecution() {
        group.setExecStatus("SUCCESS"); // the orchestrator wrote the group terminal under the lock

        service.onBatchTerminal(TICKET_ID, GROUP_ID, 1, TASK_ID, true, null, summary());

        verify(approvalStateService).completeExecution(BIZ_ID);
        verify(approvalStateService, never()).failExecution(anyString(), anyString());
    }

    @Test
    public void onBatchTerminal_advanced_anyGroupFailed_failExecution() {
        DmTicketDbStmtDO other = new DmTicketDbStmtDO();
        other.setId(OTHER_GROUP_ID);
        other.setExecStatus("FAILED");
        group.setExecStatus("SUCCESS");
        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(group, other));

        service.onBatchTerminal(TICKET_ID, GROUP_ID, 1, TASK_ID, true, null, summary());

        verify(approvalStateService).failExecution(eq(BIZ_ID), anyString());
        verify(approvalStateService, never()).completeExecution(anyString());
    }

    @Test
    public void onBatchTerminal_groupsNotAllTerminal_noTicketTransition() {
        DmTicketDbStmtDO other = new DmTicketDbStmtDO();
        other.setId(OTHER_GROUP_ID);
        other.setExecStatus("PENDING");
        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(group, other));
        group.setExecStatus("SUCCESS");

        service.onBatchTerminal(TICKET_ID, GROUP_ID, 1, TASK_ID, true, null, summary());

        verifyNoInteractions(approvalStateService);
    }

    @Test
    public void onBatchTerminal_casSkip_returnsBeforeAggregationTail_exactlyOnce() {
        // a replayed hook / the sweep already judged — the FIRST writer owns the tail
        when(ticketSegmentService.completeDmlBatchSegmentUnderLock(any(), any(), any(), any(), anyBoolean(), any(), any()))
            .thenReturn(false);
        group.setExecStatus("SUCCESS");

        service.onBatchTerminal(TICKET_ID, GROUP_ID, 1, TASK_ID, true, null, summary());

        verifyNoInteractions(approvalStateService);
        verify(stmtMapper, never()).queryByTicketId(anyLong());
    }

    // ==================== dual-event-source guards ====================

    @Test
    public void onBatchTerminal_ticketGone_noop() {
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(null);

        service.onBatchTerminal(TICKET_ID, GROUP_ID, 1, TASK_ID, true, null, summary());

        verifyNoInteractions(ticketSegmentService, approvalStateService);
    }

    @Test
    public void onBatchTerminal_ticketAlreadyTerminal_noop_secondEventSourceLostTheRace() {
        ticket.setTicketStatus(ApprovalStatus.FINISHED);

        service.onBatchTerminal(TICKET_ID, GROUP_ID, 1, TASK_ID, true, null, summary());

        verifyNoInteractions(ticketSegmentService, approvalStateService);
        verifyNoMoreInteractions(stmtMapper);
    }

    @Test
    public void onBatchTerminal_groupGone_noop() {
        when(stmtMapper.queryById(GROUP_ID)).thenReturn(null);

        service.onBatchTerminal(TICKET_ID, GROUP_ID, 1, TASK_ID, true, null, summary());

        verifyNoInteractions(ticketSegmentService, approvalStateService);
    }

    // ==================== defensive blank-runtime degradation ====================

    @Test
    public void onBatchTerminal_blankRuntime_defensiveDirectGroupWrite_thenAggregates() {
        // a DML_BATCH segment cannot exist without a runtime — carrier anomaly: the direct write
        // keeps the ticket aggregatable instead of sticking forever (legacyFallbackWrite semantics)
        group.setExecSegments(null);
        // the mock must round-trip the write so the aggregation tail's re-read sees the terminal
        when(stmtMapper.updateExecStatus(eq(GROUP_ID), anyString(), any())).thenAnswer(inv -> {
            group.setExecStatus(inv.getArgument(1));
            return 1;
        });

        service.onBatchTerminal(TICKET_ID, GROUP_ID, 1, TASK_ID, true, null, summary());

        verify(stmtMapper).updateExecStatus(GROUP_ID, "SUCCESS", null);
        verifyNoInteractions(ticketSegmentService);
        verify(approvalStateService).completeExecution(BIZ_ID);
    }

    @Test
    public void onBatchTerminal_blankRuntime_groupNotExecuting_casSkip() {
        group.setExecSegments(null);
        group.setExecStatus("FAILED"); // the other event source already wrote the terminal

        service.onBatchTerminal(TICKET_ID, GROUP_ID, 1, TASK_ID, true, null, summary());

        verify(stmtMapper, never()).updateExecStatus(anyLong(), anyString(), any());
        verifyNoInteractions(approvalStateService);
    }

    @Test
    public void onBatchTerminal_blankRuntime_failure_directFailedWrite() {
        group.setExecSegments("");

        service.onBatchTerminal(TICKET_ID, GROUP_ID, 1, TASK_ID, false, "aborted", summary());

        verify(stmtMapper).updateExecStatus(GROUP_ID, "FAILED", "aborted");
    }
}
