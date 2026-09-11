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
package com.clougence.clouddm.console.web.component.execute.impl;

import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.console.web.component.approval.ApprovalStateService;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.ExecutionDal;
import com.clougence.clouddm.platform.dal.access.TicketDbStmtDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoJobMapper;
import com.clougence.clouddm.platform.dal.mapper.govticket.DmTicketDbStmtMapper;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.execution.DmExecAutoJobDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;

/**
 * v2 group-job completion aggregation (handleV2JobCompletion):
 * - the ticket row lock is taken BEFORE the group status write, so concurrent group
 *   completions serialize and exactly one transaction sees all groups terminal
 *   (without it, two RR-isolated transactions each see the other group non-terminal
 *   in their snapshot and the ticket sticks at WAIT_EXEC forever);
 * - aggregation semantics: all SUCCESS -> completeExecution, any FAILED -> failExecution,
 *   non-terminal -> no ticket-level action.
 */
public class AutoExecV2JobCompletionTest {

    private AutoExecServiceImpl  autoExecService;
    private ExecutionDal         execDal;
    private DmExecAutoJobMapper  autoJobMapper;
    private TicketDbStmtDal      ticketDbStmtDal;
    private DmTicketDbStmtMapper ticketStmtMapper;
    private ApprovalDal          approvalDal;
    private DmApprovalMapper     approvalMapper;
    private ApprovalStateService approvalStateService;

    private static final long   JOB_ID    = 300L;
    private static final long   GROUP_ID  = 200L;
    private static final long   TICKET_ID = 100L;
    private static final String BIZ_ID    = "biz-100";

    @Before
    public void setUp() {
        autoExecService = new AutoExecServiceImpl();

        execDal = mock(ExecutionDal.class);
        autoJobMapper = mock(DmExecAutoJobMapper.class);
        when(execDal.autoJobMapper()).thenReturn(autoJobMapper);

        ticketDbStmtDal = mock(TicketDbStmtDal.class);
        ticketStmtMapper = mock(DmTicketDbStmtMapper.class);
        when(ticketDbStmtDal.stmtMapper()).thenReturn(ticketStmtMapper);

        approvalDal = mock(ApprovalDal.class);
        approvalMapper = mock(DmApprovalMapper.class);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);

        approvalStateService = mock(ApprovalStateService.class);

        ReflectionTestUtils.setField(autoExecService, "execDal", execDal);
        ReflectionTestUtils.setField(autoExecService, "ticketDbStmtDal", ticketDbStmtDal);
        ReflectionTestUtils.setField(autoExecService, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(autoExecService, "approvalStateService", approvalStateService);
    }

    @Test
    public void handleV2JobCompletion_locksTicketBeforeGroupUpdate_thenCompletes() {
        when(autoJobMapper.queryById(JOB_ID)).thenReturn(v2Job());
        when(ticketStmtMapper.queryById(GROUP_ID)).thenReturn(group(GROUP_ID, "EXECUTING"));
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(ticket());
        when(ticketStmtMapper.queryByTicketId(TICKET_ID))
            .thenReturn(List.of(group(GROUP_ID, "SUCCESS"), group(201L, "SUCCESS")));

        autoExecService.handleV2JobCompletion(JOB_ID, true, null);

        InOrder inOrder = inOrder(approvalMapper, ticketStmtMapper);
        inOrder.verify(approvalMapper).selectByIdForUpdate(TICKET_ID);
        inOrder.verify(ticketStmtMapper).updateExecStatus(eq(GROUP_ID), eq("SUCCESS"), isNull());
        verify(approvalStateService).completeExecution(BIZ_ID);
    }

    @Test
    public void handleV2JobCompletion_anyFailed_failsTicket() {
        when(autoJobMapper.queryById(JOB_ID)).thenReturn(v2Job());
        when(ticketStmtMapper.queryById(GROUP_ID)).thenReturn(group(GROUP_ID, "EXECUTING"));
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(ticket());
        when(ticketStmtMapper.queryByTicketId(TICKET_ID))
            .thenReturn(List.of(group(GROUP_ID, "FAILED"), group(201L, "SUCCESS")));

        autoExecService.handleV2JobCompletion(JOB_ID, false, "syntax error");

        verify(ticketStmtMapper).updateExecStatus(eq(GROUP_ID), eq("FAILED"), eq("syntax error"));
        verify(approvalStateService).failExecution(eq(BIZ_ID), anyString());
        verify(approvalStateService, never()).completeExecution(anyString());
    }

    @Test
    public void handleV2JobCompletion_notAllTerminal_noTicketAction() {
        when(autoJobMapper.queryById(JOB_ID)).thenReturn(v2Job());
        when(ticketStmtMapper.queryById(GROUP_ID)).thenReturn(group(GROUP_ID, "EXECUTING"));
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(ticket());
        when(ticketStmtMapper.queryByTicketId(TICKET_ID))
            .thenReturn(List.of(group(GROUP_ID, "SUCCESS"), group(201L, "EXECUTING")));

        autoExecService.handleV2JobCompletion(JOB_ID, true, null);

        // group status written, but the ticket stays for the other group's callback
        verify(ticketStmtMapper).updateExecStatus(eq(GROUP_ID), eq("SUCCESS"), isNull());
        verifyNoInteractions(approvalStateService);
    }

    @Test
    public void handleV2JobCompletion_ticketMissing_stopsAfterLock() {
        when(autoJobMapper.queryById(JOB_ID)).thenReturn(v2Job());
        when(ticketStmtMapper.queryById(GROUP_ID)).thenReturn(group(GROUP_ID, "EXECUTING"));
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(null);

        autoExecService.handleV2JobCompletion(JOB_ID, true, null);

        verify(ticketStmtMapper, never()).updateExecStatus(anyLong(), anyString(), any());
        verifyNoInteractions(approvalStateService);
    }

    @Test
    public void handleV2JobCompletion_notV2Job_returnsEarly() {
        DmExecAutoJobDO legacyJob = new DmExecAutoJobDO();
        legacyJob.setId(JOB_ID);
        legacyJob.setDependOnGroupId(null);
        when(autoJobMapper.queryById(JOB_ID)).thenReturn(legacyJob);

        autoExecService.handleV2JobCompletion(JOB_ID, true, null);

        verifyNoInteractions(ticketDbStmtDal);
        verifyNoInteractions(approvalStateService);
    }

    // ==================== helpers ====================

    private DmExecAutoJobDO v2Job() {
        DmExecAutoJobDO job = new DmExecAutoJobDO();
        job.setId(JOB_ID);
        job.setDependOnGroupId(GROUP_ID);
        return job;
    }

    private DmApprovalDO ticket() {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setBizId(BIZ_ID);
        return ticket;
    }

    private DmTicketDbStmtDO group(long id, String execStatus) {
        DmTicketDbStmtDO group = new DmTicketDbStmtDO();
        group.setId(id);
        group.setTicketId(TICKET_ID);
        group.setExecStatus(execStatus);
        return group;
    }
}
