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
package com.clougence.clouddm.console.web.component.approval.impl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.Collections;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.console.web.component.approval.ApprovalHandler;
import com.clougence.clouddm.console.web.component.approval.ApprovalStateService;
import com.clougence.clouddm.console.web.component.approval.model.ExternalCommandMeta;
import com.clougence.clouddm.console.web.component.cicd.ImSenderService;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.DbChangeEventDal;
import com.clougence.clouddm.platform.dal.access.AuthDal;
import com.clougence.clouddm.platform.dal.access.SystemDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalProcessMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangeEventMapper;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalProcessStatus;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStage;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalType;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO;
import com.clougence.clouddm.platform.dal.model.dbchange.GovEventType;
import com.clougence.clouddm.sdk.approval.ApprovalInstanceStatus;
import com.clougence.utils.JsonUtils;

/**
 * T4+T5+T13: External command service layer tests.
 * <p>
 * T4: Three-state command migration (approve/reject/cancel) — process status,
 * ticket status, handler callback, activity synthesis, audit event.
 * T5: Command guards — non-WAIT_APPROVAL rejected, end-status rejected,
 * non-External channel rejected.
 * T13: refreshApprovalStatus refactoring regression — DingTalk callback path
 * still works identically after the switch block was extracted to
 * applyExternalApprovalResult.
 */
public class ExternalCommandTest {

    private ApprovalProviderServiceImpl service;
    private ApprovalDal approvalDal;
    private DmApprovalMapper approvalMapper;
    private ApprovalStateService approvalStateService;
    private DbChangeEventDal dbChangeEventDal;
    private DmDbChangeEventMapper eventMapper;
    private ImSenderService imSenderService;
    private TestHandler testHandler;

    private static final long  TICKET_ID = 300L;
    private static final String PUID     = "puid-003";
    private static final String OPERATOR = "svc-uid-001";

    @Before
    public void setUp() {
        testHandler = spy(new TestHandler());
        service = new ApprovalProviderServiceImpl(Collections.singletonList(testHandler));

        approvalDal = mock(ApprovalDal.class);
        approvalMapper = mock(DmApprovalMapper.class);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);
        DmApprovalProcessMapper processMapper = mock(DmApprovalProcessMapper.class);
        when(approvalDal.processMapper()).thenReturn(processMapper);

        approvalStateService = mock(ApprovalStateService.class);

        dbChangeEventDal = mock(DbChangeEventDal.class);
        eventMapper = mock(DmDbChangeEventMapper.class);
        when(dbChangeEventDal.eventMapper()).thenReturn(eventMapper);
        when(eventMapper.insert(any(DmDbChangeEventDO.class))).thenReturn(1);

        imSenderService = mock(ImSenderService.class);

        ReflectionTestUtils.setField(service, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(service, "approvalStateService", approvalStateService);
        ReflectionTestUtils.setField(service, "dbChangeEventDal", dbChangeEventDal);
        ReflectionTestUtils.setField(service, "imSenderService", imSenderService);
        ReflectionTestUtils.setField(service, "systemDal", mock(SystemDal.class));
        ReflectionTestUtils.setField(service, "authDal", mock(AuthDal.class));
    }

    // ==================== T4: Three-state command migration ====================

    @Test
    public void approve_externalTicket_migratesToWaitConfirm() {
        DmApprovalDO ticket = buildExternalTicket(ApprovalStatus.WAIT_APPROVAL);
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(ticket);

        ExternalCommandMeta meta = new ExternalCommandMeta(OPERATOR, "alice", "approved", "flow-001");
        service.applyExternalApprovalResult(TICKET_ID, ApprovalInstanceStatus.COMPLETED, meta);

        // step1: APPROVAL process → FINISH
        verify(approvalStateService).updateProcessStatus(TICKET_ID, ApprovalStage.APPROVAL, ApprovalProcessStatus.FINISH, null);
        // step2: handler.approvalApproved called
        assertTrue("approvalApproved should be called", testHandler.approvedCalled);
        // Audit event: EXTERNAL_APPROVED
        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        DmDbChangeEventDO event = eventCaptor.getValue();
        assertEquals(GovEventType.EXTERNAL_APPROVED.name(), event.getEventType());
        assertEquals(OPERATOR, event.getOperatorUid());
        assertEquals(ApprovalStatus.WAIT_APPROVAL.name(), event.getFromStatus());
        assertEquals(ApprovalStatus.WAIT_CONFIRM.name(), event.getToStatus());

        Map<String, Object> data = JsonUtils.toObj(event.getEventData(), Map.class);
        assertEquals("EXTERNAL_COMMAND", data.get("via"));
        assertEquals("alice", data.get("approver"));
        assertEquals("approved", data.get("comment"));
        assertEquals("flow-001", data.get("externalFlowId"));
    }

    @Test
    public void reject_externalTicket_migratesToRejected() {
        DmApprovalDO ticket = buildExternalTicket(ApprovalStatus.WAIT_APPROVAL);
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(ticket);

        ExternalCommandMeta meta = new ExternalCommandMeta(OPERATOR, "bob", "rejected", null);
        service.applyExternalApprovalResult(TICKET_ID, ApprovalInstanceStatus.REFUSE, meta);

        // step1: APPROVAL process → REJECT
        verify(approvalStateService).updateProcessStatus(TICKET_ID, ApprovalStage.APPROVAL, ApprovalProcessStatus.REJECT, null);
        // step2: ticket → REJECTED
        verify(approvalStateService).updateApprovalStatus(TICKET_ID, ApprovalStatus.REJECTED, null);
        // step3: handler.approvalRejected called
        assertTrue("approvalRejected should be called", testHandler.rejectedCalled);
        // Audit event: EXTERNAL_REJECTED
        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        assertEquals(GovEventType.EXTERNAL_REJECTED.name(), eventCaptor.getValue().getEventType());
    }

    @Test
    public void cancel_externalTicket_migratesToCanceled() {
        DmApprovalDO ticket = buildExternalTicket(ApprovalStatus.WAIT_APPROVAL);
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(ticket);

        ExternalCommandMeta meta = new ExternalCommandMeta(OPERATOR, null, null, null);
        service.applyExternalApprovalResult(TICKET_ID, ApprovalInstanceStatus.CANCELED, meta);

        // step1: APPROVAL process → CLOSED
        verify(approvalStateService).updateProcessStatus(TICKET_ID, ApprovalStage.APPROVAL, ApprovalProcessStatus.CLOSED, null);
        // step2: ticket → CANCELED
        verify(approvalStateService).updateApprovalStatus(TICKET_ID, ApprovalStatus.CANCELED, null);
        // step3: handler.approvalCanceled called
        assertTrue("approvalCanceled should be called", testHandler.canceledCalled);
        // Audit event: EXTERNAL_CANCELED
        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        assertEquals(GovEventType.EXTERNAL_CANCELED.name(), eventCaptor.getValue().getEventType());

        // No activity synthesis (approver and comment both null)
        verify(approvalStateService, never()).initializeActivity(
            anyLong(), any(), anyString(), anyString(), anyInt(), any(), any());
    }

    @Test
    public void approve_withApprover_synthesizesActivity() {
        DmApprovalDO ticket = buildExternalTicket(ApprovalStatus.WAIT_APPROVAL);
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(ticket);

        ExternalCommandMeta meta = new ExternalCommandMeta(OPERATOR, "carol", "lgtm", null);
        service.applyExternalApprovalResult(TICKET_ID, ApprovalInstanceStatus.COMPLETED, meta);

        // Activity synthesized: initializeActivity called with activityId starting with "ext-"
        ArgumentCaptor<String> actIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(approvalStateService).initializeActivity(
            eq(TICKET_ID), eq(ApprovalStage.APPROVAL), actIdCaptor.capture(),
            eq("External Approval"), anyInt(), anyString(), anyString());
        assertTrue("activityId must start with ext-", actIdCaptor.getValue().startsWith("ext-"));
    }

    @Test
    public void externalFlowId_persistsToApproIdentity_whenEmpty() {
        DmApprovalDO ticket = buildExternalTicket(ApprovalStatus.WAIT_APPROVAL);
        ticket.setApproIdentity(null); // empty identity
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(ticket);

        ExternalCommandMeta meta = new ExternalCommandMeta(OPERATOR, null, null, "ext-flow-123");
        service.applyExternalApprovalResult(TICKET_ID, ApprovalInstanceStatus.COMPLETED, meta);

        verify(approvalMapper).updateThirdApprovalInfo(TICKET_ID, "ext-flow-123", null);
    }

    @Test
    public void externalFlowId_doesNotOverwrite_existingApproIdentity() {
        DmApprovalDO ticket = buildExternalTicket(ApprovalStatus.WAIT_APPROVAL);
        ticket.setApproIdentity("existing-identity");
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(ticket);

        ExternalCommandMeta meta = new ExternalCommandMeta(OPERATOR, null, null, "ext-flow-456");
        service.applyExternalApprovalResult(TICKET_ID, ApprovalInstanceStatus.COMPLETED, meta);

        // Should NOT overwrite the existing identity
        verify(approvalMapper, never()).updateThirdApprovalInfo(anyLong(), anyString(), any());
    }

    // ==================== T5: Command guards ====================

    @Test
    public void approve_nonWaitApprovalStatus_throwsInvalidStatus() {
        DmApprovalDO ticket = buildExternalTicket(ApprovalStatus.WAIT_CONFIRM);
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(ticket);

        ExternalCommandMeta meta = new ExternalCommandMeta(OPERATOR, null, null, null);
        try {
            service.applyExternalApprovalResult(TICKET_ID, ApprovalInstanceStatus.COMPLETED, meta);
            fail("Expected ErrorMessageException for non-WAIT_APPROVAL status");
        } catch (ErrorMessageException ex) {
            // ok
        }
        // Zero side effects: no process status change, no handler callback
        verify(approvalStateService, never()).updateProcessStatus(anyLong(), any(), any(), any());
        assertFalse("approvalApproved should NOT be called", testHandler.approvedCalled);
    }

    @Test
    public void approve_endStatus_throwsInvalidStatus() {
        DmApprovalDO ticket = buildExternalTicket(ApprovalStatus.FINISHED);
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(ticket);

        ExternalCommandMeta meta = new ExternalCommandMeta(OPERATOR, null, null, null);
        try {
            service.applyExternalApprovalResult(TICKET_ID, ApprovalInstanceStatus.COMPLETED, meta);
            fail("Expected ErrorMessageException for end-status ticket");
        } catch (ErrorMessageException ex) {
            // ok
        }
        verify(approvalStateService, never()).updateProcessStatus(anyLong(), any(), any(), any());
    }

    @Test
    public void approve_nonExternalChannel_throwsChannelMismatch() {
        DmApprovalDO ticket = buildTicket(ApprovalType.DingTalk, ApprovalStatus.WAIT_APPROVAL);
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(ticket);

        ExternalCommandMeta meta = new ExternalCommandMeta(OPERATOR, null, null, null);
        try {
            service.applyExternalApprovalResult(TICKET_ID, ApprovalInstanceStatus.COMPLETED, meta);
            fail("Expected ErrorMessageException for non-External channel");
        } catch (ErrorMessageException ex) {
            // ok
        }
        verify(approvalStateService, never()).updateProcessStatus(anyLong(), any(), any(), any());
    }

    @Test
    public void approve_replay_secondCommandRejectedByGuard() {
        DmApprovalDO ticket = buildExternalTicket(ApprovalStatus.WAIT_APPROVAL);
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(ticket);

        ExternalCommandMeta meta = new ExternalCommandMeta(OPERATOR, "alice", "approved", null);
        // First command succeeds
        service.applyExternalApprovalResult(TICKET_ID, ApprovalInstanceStatus.COMPLETED, meta);
        assertTrue("first approve should succeed", testHandler.approvedCalled);

        // Reset handler for second call
        testHandler.approvedCalled = false;
        // After first approve, ticket status would be WAIT_CONFIRM (in production the handler sets it)
        // Simulate: second command arrives, ticket is now WAIT_CONFIRM
        DmApprovalDO ticket2 = buildExternalTicket(ApprovalStatus.WAIT_CONFIRM);
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(ticket2);

        try {
            service.applyExternalApprovalResult(TICKET_ID, ApprovalInstanceStatus.COMPLETED, meta);
            fail("Expected ErrorMessageException for replay on non-WAIT_APPROVAL");
        } catch (ErrorMessageException ex) {
            // ok — guard rejects
        }
        assertFalse("second approve should NOT call handler", testHandler.approvedCalled);
    }

    // ==================== T13: refreshApprovalStatus refactoring regression ====================

    @Test
    public void refreshApprovalStatus_metaNull_noGuardsNoActivityNoEvent() {
        // T13: When refreshApprovalStatus calls applyExternalApprovalResult with meta=null,
        // no guards, no activity synthesis, no audit event — behavior identical to
        // the former inline switch block.
        DmApprovalDO ticket = buildTicket(ApprovalType.DingTalk, ApprovalStatus.WAIT_APPROVAL);
        ticket.setApproIdentity("ding-identity-001");
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket);

        // Cannot easily test the full refreshApprovalStatus path without mockStatic for PluginManager,
        // but we CAN test applyExternalApprovalResult directly with meta=null to verify
        // the switch block behavior is preserved.
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(ticket);

        service.applyExternalApprovalResult(TICKET_ID, ApprovalInstanceStatus.COMPLETED, null);

        // Same state migration as the old inline switch
        verify(approvalStateService).updateProcessStatus(TICKET_ID, ApprovalStage.APPROVAL, ApprovalProcessStatus.FINISH, null);
        assertTrue("approvalApproved should be called", testHandler.approvedCalled);

        // No audit event for meta=null (DingTalk callback path)
        verify(eventMapper, never()).insert(any(DmDbChangeEventDO.class));
        // No activity synthesis for meta=null
        verify(approvalStateService, never()).initializeActivity(
            anyLong(), any(), anyString(), anyString(), anyInt(), any(), any());
        // No externalFlowId persistence for meta=null
        verify(approvalMapper, never()).updateThirdApprovalInfo(anyLong(), anyString(), any());
    }

    @Test
    public void applyExternalApprovalResult_metaNull_reject_migratesSameAsBefore() {
        DmApprovalDO ticket = buildTicket(ApprovalType.DingTalk, ApprovalStatus.WAIT_APPROVAL);
        ticket.setApproIdentity("ding-identity-002");
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(ticket);

        service.applyExternalApprovalResult(TICKET_ID, ApprovalInstanceStatus.REFUSE, null);

        verify(approvalStateService).updateProcessStatus(TICKET_ID, ApprovalStage.APPROVAL, ApprovalProcessStatus.REJECT, null);
        verify(approvalStateService).updateApprovalStatus(TICKET_ID, ApprovalStatus.REJECTED, null);
        assertTrue("approvalRejected should be called", testHandler.rejectedCalled);

        // No audit event
        verify(eventMapper, never()).insert(any(DmDbChangeEventDO.class));
    }

    @Test
    public void applyExternalApprovalResult_metaNull_canceled_migratesSameAsBefore() {
        DmApprovalDO ticket = buildTicket(ApprovalType.DingTalk, ApprovalStatus.WAIT_APPROVAL);
        ticket.setApproIdentity("ding-identity-003");
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(ticket);

        service.applyExternalApprovalResult(TICKET_ID, ApprovalInstanceStatus.CANCELED, null);

        verify(approvalStateService).updateProcessStatus(TICKET_ID, ApprovalStage.APPROVAL, ApprovalProcessStatus.CLOSED, null);
        verify(approvalStateService).updateApprovalStatus(TICKET_ID, ApprovalStatus.CANCELED, null);
        assertTrue("approvalCanceled should be called", testHandler.canceledCalled);

        // No audit event
        verify(eventMapper, never()).insert(any(DmDbChangeEventDO.class));
    }

    // ==================== Helpers ====================

    private DmApprovalDO buildExternalTicket(ApprovalStatus status) {
        return buildTicket(ApprovalType.External, status);
    }

    private DmApprovalDO buildTicket(ApprovalType type, ApprovalStatus status) {
        DmApprovalDO t = new DmApprovalDO();
        t.setId(TICKET_ID);
        t.setPrimaryUid(PUID);
        t.setOwnerUid("uid-003");
        t.setApproType(type);
        t.setApproBiz(ApprovalBiz.DM_CHANGE);
        t.setTicketStatus(status);
        return t;
    }

    /**
     * Minimal test handler that records which callback was invoked.
     */
    private static class TestHandler implements ApprovalHandler {
        boolean approvedCalled = false;
        boolean rejectedCalled = false;
        boolean canceledCalled = false;

        @Override
        public ApprovalBiz handleType() {
            return ApprovalBiz.DM_CHANGE;
        }

        @Override
        public void createApproval(long approvalId, ImSenderService sender) {}

        @Override
        public void approvalApproved(long approvalId, ApprovalBiz bizType, ImSenderService sender) {
            approvedCalled = true;
        }

        @Override
        public void approvalRejected(long approvalId, ApprovalBiz bizType, ImSenderService sender) {
            rejectedCalled = true;
        }

        @Override
        public void approvalCanceled(long approvalId, ApprovalBiz bizType, ImSenderService sender) {
            canceledCalled = true;
        }

        @Override
        public void approvalCompleted(long approvalId, ApprovalBiz bizType, ImSenderService sender) {}

        @Override
        public void approvalFailed(long approvalId, ApprovalBiz bizType, ImSenderService sender) {}

        @Override
        public void executeTicket(long approvalId, ApprovalBiz bizType, ImSenderService sender) {}

        @Override
        public void runningCheck(long approvalId, ApprovalBiz bizType, ImSenderService sender) {}

        @Override
        public java.util.List<com.clougence.clouddm.console.web.model.vo.PrimaryUserVO> queryPerson(long approvalId) {
            return java.util.Collections.emptyList();
        }
    }
}
