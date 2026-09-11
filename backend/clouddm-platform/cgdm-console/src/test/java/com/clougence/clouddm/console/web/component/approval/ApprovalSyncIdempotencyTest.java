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
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;

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

    private static final long TICKET_ID = 100L;

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

        ReflectionTestUtils.setField(handler, "approvalStateService", approvalStateService);
        ReflectionTestUtils.setField(handler, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(handler, "changeFlowDal", changeFlowDal);
        ReflectionTestUtils.setField(handler, "execDal", execDal);
        ReflectionTestUtils.setField(handler, "authDal", authDal);
        ReflectionTestUtils.setField(handler, "changeCascadeService", changeCascadeService);

        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);
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
}
