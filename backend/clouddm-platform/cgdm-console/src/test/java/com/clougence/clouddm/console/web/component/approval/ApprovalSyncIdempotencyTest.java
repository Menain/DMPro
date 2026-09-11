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
package com.clougence.clouddm.console.web.component.approval;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.console.web.component.approval.handler.ChangeApprovalHandler;
import com.clougence.clouddm.console.web.component.cicd.ImSenderService;
import com.clougence.clouddm.console.web.service.cicd.ChangeCascadeService;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.AuthDal;
import com.clougence.clouddm.platform.dal.access.ChangeFlowDal;
import com.clougence.clouddm.platform.dal.access.ExecutionDal;
import com.clougence.clouddm.platform.dal.access.TicketDbStmtDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoJobMapper;
import com.clougence.clouddm.platform.dal.mapper.govticket.DmTicketDbStmtMapper;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;

/**
 * Phase 9 R2: approval sync idempotency pinning tests (design D6 — zero production code).
 * <p>
 * These tests pin the existing mechanisms that make governance PROD tickets safe
 * through the approval callback / sync chains without any new code:
 * <ol>
 * <li>Repeated callback idempotency: {@code approvalApproved} re-writes WAIT_CONFIRM
 * (same-value write = no effect); no execution side-effect.</li>
 * <li>Callback chain never triggers SQL execution: {@code approvalApproved} calls
 * only {@code updateApprovalStatus}, never createExecJob/dispatchJob/confirmTicket.</li>
 * <li>{@code updateChange} (called by approvalCompleted/rejected/failed/canceled)
 * returns early for governance tickets (no changeId/changeOwnerUid) without exception.</li>
 * <li>{@code listUnFinishTicketIdList} and {@code queryByApproIdentity} have no
 * approBiz filter — DM_CHANGE governance tickets are not excluded from sync paths.</li>
 * </ol>
 * Research: research/03-callback-chains.md (three chains, three idempotency layers).
 */
public class ApprovalSyncIdempotencyTest {

    private ChangeApprovalHandler  handler;
    private ApprovalStateService     approvalStateService;
    private ApprovalDal              approvalDal;
    private DmApprovalMapper         approvalMapper;
    private ChangeFlowDal            changeFlowDal;
    private ExecutionDal             execDal;
    private AuthDal                  authDal;
    private ChangeCascadeService     changeCascadeService;
    private DmExecAutoJobMapper      autoJobMapper;
    private TicketDbStmtDal          ticketDbStmtDal;
    private DmTicketDbStmtMapper     ticketStmtMapper;

    private static final long TICKET_ID = 100L;
    private static final String BIZ_ID  = "biz-100";

    @Before
    public void setUp() {
        handler = new ChangeApprovalHandler();
        approvalStateService = mock(ApprovalStateService.class);
        approvalDal = mock(ApprovalDal.class);
        approvalMapper = mock(DmApprovalMapper.class);
        changeFlowDal = mock(ChangeFlowDal.class);
        execDal = mock(ExecutionDal.class);
        authDal = mock(AuthDal.class);
        changeCascadeService = mock(ChangeCascadeService.class);
        autoJobMapper = mock(DmExecAutoJobMapper.class);
        ticketDbStmtDal = mock(TicketDbStmtDal.class);
        ticketStmtMapper = mock(DmTicketDbStmtMapper.class);

        ReflectionTestUtils.setField(handler, "approvalStateService", approvalStateService);
        ReflectionTestUtils.setField(handler, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(handler, "changeFlowDal", changeFlowDal);
        ReflectionTestUtils.setField(handler, "execDal", execDal);
        ReflectionTestUtils.setField(handler, "authDal", authDal);
        ReflectionTestUtils.setField(handler, "changeCascadeService", changeCascadeService);
        ReflectionTestUtils.setField(handler, "ticketDbStmtDal", ticketDbStmtDal);

        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);
        when(ticketDbStmtDal.stmtMapper()).thenReturn(ticketStmtMapper);
        // Default: the sweep's selectByIdForUpdate returns a non-terminal ticket so the
        // recovery guard proceeds; tests that need a terminal ticket override this stub.
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(v2Ticket());
    }

    // ======= 1. Repeated callback idempotency =======

    @Test
    public void repeatedApprovalApproved_writesWaitConfirmTwice_idempotentSameValue() {
        ImSenderService sender = mock(ImSenderService.class);

        // First callback
        handler.approvalApproved(TICKET_ID, ApprovalBiz.DM_CHANGE, sender);
        verify(approvalStateService).updateApprovalStatus(TICKET_ID, ApprovalStatus.WAIT_CONFIRM, null);

        // Second (duplicate) callback — same-value write, no new state transition
        handler.approvalApproved(TICKET_ID, ApprovalBiz.DM_CHANGE, sender);
        verify(approvalStateService, times(2)).updateApprovalStatus(TICKET_ID, ApprovalStatus.WAIT_CONFIRM, null);

        // No execution-related calls on either invocation
        verifyNoInteractions(execDal);
    }

    // ======= 2. Callback chain never triggers execution =======

    @Test
    public void approvalApproved_neverTriggersExecution() {
        ImSenderService sender = mock(ImSenderService.class);

        handler.approvalApproved(TICKET_ID, ApprovalBiz.DM_CHANGE, sender);

        // Only updateApprovalStatus is called
        verify(approvalStateService).updateApprovalStatus(TICKET_ID, ApprovalStatus.WAIT_CONFIRM, null);
        // Nothing else on approvalStateService
        verifyNoMoreInteractions(approvalStateService);

        // No execution DAL access at all
        verifyNoInteractions(execDal);

        // No change-flow access (that's CI/CD callback territory, not approvalApproved)
        verifyNoInteractions(changeFlowDal);
    }

    // ======= 3. updateChange returns early for governance tickets =======

    @Test
    public void approvalCompleted_governanceTicket_returnsEarlyNoException() {
        // Governance ticket: ticketInfo has govRole but no changeId/changeOwnerUid
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setOwnerUid("uid-001");
        ticket.setTicketInfo("{\"govRole\":\"PROD\",\"promotionId\":300,\"revisionId\":200}");
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket);

        ImSenderService sender = mock(ImSenderService.class);

        // Should not throw — updateChange returns early for governance tickets
        handler.approvalCompleted(TICKET_ID, ApprovalBiz.DM_CHANGE, sender);

        // No CI/CD change flow access (early return before any changeFlowDal call)
        verifyNoInteractions(changeFlowDal);
    }

    @Test
    public void approvalRejected_governanceTicket_returnsEarlyNoException() {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setOwnerUid("uid-001");
        ticket.setTicketInfo("{\"govRole\":\"PROD\",\"promotionId\":300,\"revisionId\":200}");
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket);

        ImSenderService sender = mock(ImSenderService.class);

        handler.approvalRejected(TICKET_ID, ApprovalBiz.DM_CHANGE, sender);

        verifyNoInteractions(changeFlowDal);
    }

    @Test
    public void approvalFailed_governanceTicket_returnsEarlyNoException() {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setOwnerUid("uid-001");
        ticket.setTicketInfo("{\"govRole\":\"PROD\",\"promotionId\":300,\"revisionId\":200}");
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket);

        ImSenderService sender = mock(ImSenderService.class);

        handler.approvalFailed(TICKET_ID, ApprovalBiz.DM_CHANGE, sender);

        verifyNoInteractions(changeFlowDal);
    }

    // ======= 4. No approBiz filter on sync path mapper methods =======

    @Test
    public void listUnFinishTicketIdList_hasNoApproBizParameter() throws NoSuchMethodException {
        // listUnFinishTicketIdList() takes zero parameters → no approBiz filter
        // This means DM_CHANGE governance tickets are scanned by the sync scheduler
        Method method = DmApprovalMapper.class.getDeclaredMethod("listUnFinishTicketIdList");
        assertEquals(0, method.getParameterCount());
    }

    @Test
    public void queryByApproIdentity_hasNoApproBizParameter() throws NoSuchMethodException {
        // queryByApproIdentity(identity, type, puid) — no approBiz parameter
        // This means DM_CHANGE governance ticket callbacks are resolved by identity, not filtered by biz type
        Method method = DmApprovalMapper.class.getDeclaredMethod(
            "queryByApproIdentity",
            String.class,
            String.class,
            String.class);
        assertEquals(3, method.getParameterCount());
        // Verify none of the parameter types is ApprovalBiz
        Class<?>[] paramTypes = method.getParameterTypes();
        for (Class<?> type : paramTypes) {
            assertFalse("queryByApproIdentity must not take ApprovalBiz parameter",
                type == ApprovalBiz.class);
        }
    }

    // ======= 5. WAIT_EXEC sweep recovers a lost v2 completion =======

    @Test
    public void executeTicket_v2GroupsAllSuccess_completesTicket() {
        // Lost-aggregation recovery: no legacy job, every group SUCCESS → complete on the sweep
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(v2Ticket());
        when(execDal.autoJobMapper()).thenReturn(autoJobMapper);
        when(autoJobMapper.queryByDependOnBizId(BIZ_ID)).thenReturn(null);
        when(ticketStmtMapper.queryByTicketId(TICKET_ID))
            .thenReturn(List.of(v2Group(1L, "SUCCESS"), v2Group(2L, "SUCCESS")));

        handler.executeTicket(TICKET_ID, ApprovalBiz.DM_CHANGE, mock(ImSenderService.class));

        verify(approvalStateService).completeExecution(BIZ_ID);
        verify(approvalStateService, never()).failExecution(anyString(), any());
    }

    @Test
    public void executeTicket_v2GroupFailed_failsTicket() {
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(v2Ticket());
        when(execDal.autoJobMapper()).thenReturn(autoJobMapper);
        when(autoJobMapper.queryByDependOnBizId(BIZ_ID)).thenReturn(null);
        when(ticketStmtMapper.queryByTicketId(TICKET_ID))
            .thenReturn(List.of(v2Group(1L, "SUCCESS"), v2Group(2L, "FAILED")));

        handler.executeTicket(TICKET_ID, ApprovalBiz.DM_CHANGE, mock(ImSenderService.class));

        verify(approvalStateService).failExecution(eq(BIZ_ID), anyString());
        verify(approvalStateService, never()).completeExecution(anyString());
    }

    @Test
    public void executeTicket_v2GroupsNotTerminal_waitsForCallbacks() {
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(v2Ticket());
        when(execDal.autoJobMapper()).thenReturn(autoJobMapper);
        when(autoJobMapper.queryByDependOnBizId(BIZ_ID)).thenReturn(null);
        when(ticketStmtMapper.queryByTicketId(TICKET_ID))
            .thenReturn(List.of(v2Group(1L, "SUCCESS"), v2Group(2L, "EXECUTING")));

        handler.executeTicket(TICKET_ID, ApprovalBiz.DM_CHANGE, mock(ImSenderService.class));

        verifyNoInteractions(approvalStateService);
    }

    @Test
    public void executeTicket_legacyTicketWithoutGroups_noRecovery() {
        // Legacy WAIT_EXEC ticket without a job row and without v2 groups: sweep stays a no-op
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(v2Ticket());
        when(execDal.autoJobMapper()).thenReturn(autoJobMapper);
        when(autoJobMapper.queryByDependOnBizId(BIZ_ID)).thenReturn(null);
        when(ticketStmtMapper.queryByTicketId(TICKET_ID)).thenReturn(Collections.emptyList());

        handler.executeTicket(TICKET_ID, ApprovalBiz.DM_CHANGE, mock(ImSenderService.class));

        verifyNoInteractions(approvalStateService);
    }

    @Test
    public void executeTicket_v2AlreadyCompleted_noDoubleCompletion() {
        // Concurrent callback already drove the ticket to FINISHED while the sweep was in
        // flight: the selectByIdForUpdate lock sees the committed terminal status and skips,
        // so completeExecution/failExecution are never called a second time.
        DmApprovalDO finished = v2Ticket();
        finished.setTicketStatus(ApprovalStatus.FINISHED);
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(v2Ticket());
        when(execDal.autoJobMapper()).thenReturn(autoJobMapper);
        when(autoJobMapper.queryByDependOnBizId(BIZ_ID)).thenReturn(null);
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(finished);
        // Groups read is never reached because the terminal-status guard returns first

        handler.executeTicket(TICKET_ID, ApprovalBiz.DM_CHANGE, mock(ImSenderService.class));

        verifyNoInteractions(approvalStateService);
        verify(ticketStmtMapper, never()).queryByTicketId(anyLong());
    }

    private DmApprovalDO v2Ticket() {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setBizId(BIZ_ID);
        return ticket;
    }

    private DmTicketDbStmtDO v2Group(long id, String execStatus) {
        DmTicketDbStmtDO group = new DmTicketDbStmtDO();
        group.setId(id);
        group.setTicketId(TICKET_ID);
        group.setExecStatus(execStatus);
        return group;
    }
}
