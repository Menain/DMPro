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
import org.springframework.transaction.PlatformTransactionManager;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.console.web.component.approval.ApprovalHandler;
import com.clougence.clouddm.console.web.component.approval.ApprovalStateService;
import com.clougence.clouddm.console.web.component.approval.model.ExternalCommandMeta;
import com.clougence.clouddm.console.web.component.cicd.ImSenderService;
import com.clougence.clouddm.console.web.component.config.RootUserConfig;
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
 * <p>
 * 10-11 rejected-command-audit: every guard rejection additionally persists an
 * EXTERNAL_COMMAND_REJECTED event (R1) without changing the throw / zero-side-effect
 * semantics (R2) — asserted on the three guard reasons, the replay path (R4) and the
 * audit-failure degradation path (A3).
 * <p>
 * 10-11 approval-round-binding (ADR-012 A+B): guards 4/5 — flow consistency
 * (FLOW_ID_MISMATCH, pin FLIPPED from "mismatch still succeeds, just no overwrite") and
 * round binding (ROUND_MISMATCH); tolerant mode pins (blank/absent values skip the checks —
 * the frozen §5.4 ticketId-only minimal call); identity-empty-window observation
 * (identityBackfill marker + no marker on the normal path); the config-gated strict tier
 * (default off, read-failure tolerant); D5 zero-misfire (state guard precedes the flow
 * guards on a resubmitted ticket).
 */
public class ExternalCommandTest {

    private ApprovalProviderServiceImpl service;
    private ApprovalDal approvalDal;
    private DmApprovalMapper approvalMapper;
    private ApprovalStateService approvalStateService;
    private DbChangeEventDal dbChangeEventDal;
    private DmDbChangeEventMapper eventMapper;
    private ImSenderService imSenderService;
    private SystemDal systemDal;
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

        systemDal = mock(SystemDal.class);

        ReflectionTestUtils.setField(service, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(service, "approvalStateService", approvalStateService);
        ReflectionTestUtils.setField(service, "dbChangeEventDal", dbChangeEventDal);
        ReflectionTestUtils.setField(service, "imSenderService", imSenderService);
        ReflectionTestUtils.setField(service, "systemDal", systemDal);
        ReflectionTestUtils.setField(service, "authDal", mock(AuthDal.class));
        // REQUIRES_NEW template of the rejected-command audit rides this manager (10-11 R2):
        // the bare mock makes getTransaction/commit no-ops — the insert runs inline.
        ReflectionTestUtils.setField(service, "txManager", mock(PlatformTransactionManager.class));
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

    /**
     * PIN FLIP (10-11 approval-round-binding, ADR-012 form A): this case formerly asserted
     * "mismatched flowId → the command still succeeds, just without overwriting the identity".
     * A provably different flow is now REJECTED (FLOW_ID_MISMATCH) — the stale-F1-hits-A2
     * vulnerability chain (adr-b §1.6) — with a durable audit trail carrying BOTH flow values,
     * and zero state side effects (A1). The "never overwrite" half of the old pin survives:
     * updateThirdApprovalInfo is still never called on a non-empty identity.
     */
    @Test
    public void externalFlowId_mismatchedWithRecordedIdentity_rejectedWithFlowIdMismatch() {
        DmApprovalDO ticket = buildExternalTicket(ApprovalStatus.WAIT_APPROVAL);
        ticket.setApproIdentity("existing-identity");
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(ticket);

        ExternalCommandMeta meta = new ExternalCommandMeta(OPERATOR, null, null, "ext-flow-456");
        try {
            service.applyExternalApprovalResult(TICKET_ID, ApprovalInstanceStatus.COMPLETED, meta);
            fail("Expected ErrorMessageException for flow id mismatch");
        } catch (ErrorMessageException ex) {
            // ok — guard 4 rejects
        }

        // Zero side effects: no overwrite, no state migration, no handler callback
        verify(approvalMapper, never()).updateThirdApprovalInfo(anyLong(), anyString(), any());
        verify(approvalStateService, never()).updateProcessStatus(anyLong(), any(), any(), any());
        assertFalse("approvalApproved should NOT be called", testHandler.approvedCalled);

        // A1: the rejection is audited with both flow values (flowIds are not credentials)
        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        DmDbChangeEventDO event = eventCaptor.getValue();
        assertEquals(GovEventType.EXTERNAL_COMMAND_REJECTED.name(), event.getEventType());
        assertEquals(OPERATOR, event.getOperatorUid());
        assertNull("a rejected command never moves the ticket", event.getToStatus());
        Map<String, Object> data = JsonUtils.toObj(event.getEventData(), Map.class);
        assertEquals("EXTERNAL_COMMAND", data.get("via"));
        assertEquals("approve", data.get("command"));
        assertEquals("FLOW_ID_MISMATCH", data.get("reason"));
        assertEquals("ext-flow-456", data.get("externalFlowId"));
        assertEquals("existing-identity", data.get("recordedFlowId"));
    }

    // ==================== 10-11 approval-round-binding (ADR-012 A+B) ====================

    @Test
    public void externalRound_mismatch_rejectedWithRoundMismatch() {
        // Form B: the row is in round 2 (release resubmit bumped it); a command carrying the
        // round-1 token is rejected even when the flowId side is blank/matching — the round
        // token closes the identity-empty window that pure flowId comparison cannot (adr-b §1.6-6).
        DmApprovalDO ticket = buildExternalTicket(ApprovalStatus.WAIT_APPROVAL);
        ticket.setApprovalRound(2);
        ticket.setApproIdentity("flow-round2");
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(ticket);

        ExternalCommandMeta meta = new ExternalCommandMeta(OPERATOR, null, null, "flow-round2", 1);
        try {
            service.applyExternalApprovalResult(TICKET_ID, ApprovalInstanceStatus.COMPLETED, meta);
            fail("Expected ErrorMessageException for round mismatch");
        } catch (ErrorMessageException ex) {
            // ok — guard 5 rejects
        }

        verify(approvalStateService, never()).updateProcessStatus(anyLong(), any(), any(), any());
        assertFalse(testHandler.approvedCalled);

        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        DmDbChangeEventDO event = eventCaptor.getValue();
        assertEquals(GovEventType.EXTERNAL_COMMAND_REJECTED.name(), event.getEventType());
        Map<String, Object> data = JsonUtils.toObj(event.getEventData(), Map.class);
        assertEquals("approve", data.get("command"));
        assertEquals("ROUND_MISMATCH", data.get("reason"));
        assertEquals("flow-round2", data.get("externalFlowId"));
    }

    @Test
    public void externalRound_matching_passesGuard() {
        DmApprovalDO ticket = buildExternalTicket(ApprovalStatus.WAIT_APPROVAL);
        ticket.setApprovalRound(2);
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(ticket);

        ExternalCommandMeta meta = new ExternalCommandMeta(OPERATOR, null, null, null, 2);
        service.applyExternalApprovalResult(TICKET_ID, ApprovalInstanceStatus.COMPLETED, meta);

        assertTrue("matching round must pass", testHandler.approvedCalled);
    }

    @Test
    public void externalRound_absent_tolerantSkip_commandSucceeds() {
        // A2 tolerant pin: an old bridge never sends externalRound — the check is skipped
        // entirely and the existing behavior is unchanged (frozen §5.4 minimal-call contract).
        DmApprovalDO ticket = buildExternalTicket(ApprovalStatus.WAIT_APPROVAL);
        ticket.setApprovalRound(2);
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(ticket);

        ExternalCommandMeta meta = new ExternalCommandMeta(OPERATOR, null, null, null); // no round
        service.applyExternalApprovalResult(TICKET_ID, ApprovalInstanceStatus.COMPLETED, meta);

        assertTrue("absent round must skip the guard", testHandler.approvedCalled);
        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        assertEquals(GovEventType.EXTERNAL_APPROVED.name(), eventCaptor.getValue().getEventType());
    }

    @Test
    public void flowId_absent_identityPresent_tolerantSkip_commandSucceeds() {
        // A2 tolerant pin: the ticketId-only minimal call against a row that already has an
        // identity — blank command flowId skips guard 4 (strict "missing ⇒ reject" would breach
        // the frozen contract and is reserved for the config-gated strict tier below).
        DmApprovalDO ticket = buildExternalTicket(ApprovalStatus.WAIT_APPROVAL);
        ticket.setApproIdentity("flow-1");
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(ticket);

        ExternalCommandMeta meta = new ExternalCommandMeta(OPERATOR, null, null, null);
        service.applyExternalApprovalResult(TICKET_ID, ApprovalInstanceStatus.COMPLETED, meta);

        assertTrue(testHandler.approvedCalled);
        verify(approvalMapper, never()).updateThirdApprovalInfo(anyLong(), anyString(), any());
    }

    @Test
    public void flowId_matchingIdentity_passesGuard_noOverwrite() {
        DmApprovalDO ticket = buildExternalTicket(ApprovalStatus.WAIT_APPROVAL);
        ticket.setApproIdentity("flow-1");
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(ticket);

        ExternalCommandMeta meta = new ExternalCommandMeta(OPERATOR, null, null, "flow-1");
        service.applyExternalApprovalResult(TICKET_ID, ApprovalInstanceStatus.COMPLETED, meta);

        assertTrue("equal flowIds must pass guard 4", testHandler.approvedCalled);
        // "write only when empty" dual-source contract untouched — no rewrite of an equal identity
        verify(approvalMapper, never()).updateThirdApprovalInfo(anyLong(), anyString(), any());
    }

    @Test
    public void identityEmpty_backfill_observationTrail() {
        // R6 / ADR-012 Decision 4: the legitimate §10.6 empty window (inbound event beats the
        // outbound backfill) still WRITES (behavior unchanged) but is now measurable —
        // identityBackfill marker on the success event (the warn log accompanies it).
        DmApprovalDO ticket = buildExternalTicket(ApprovalStatus.WAIT_APPROVAL);
        ticket.setApproIdentity(null);
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(ticket);

        ExternalCommandMeta meta = new ExternalCommandMeta(OPERATOR, null, null, "ext-flow-123");
        service.applyExternalApprovalResult(TICKET_ID, ApprovalInstanceStatus.COMPLETED, meta);

        verify(approvalMapper).updateThirdApprovalInfo(TICKET_ID, "ext-flow-123", null);
        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        DmDbChangeEventDO event = eventCaptor.getValue();
        assertEquals(GovEventType.EXTERNAL_APPROVED.name(), event.getEventType());
        Map<String, Object> data = JsonUtils.toObj(event.getEventData(), Map.class);
        assertEquals("ext-flow-123", data.get("externalFlowId"));
        assertEquals(Boolean.TRUE, data.get("identityBackfill"));
    }

    @Test
    public void successEvent_withoutBackfill_carriesNoMarker() {
        // The marker is precise: a matching-flowId success (no write happened) must NOT carry it,
        // otherwise the strict-tier gray release would measure noise.
        DmApprovalDO ticket = buildExternalTicket(ApprovalStatus.WAIT_APPROVAL);
        ticket.setApproIdentity("flow-1");
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(ticket);

        ExternalCommandMeta meta = new ExternalCommandMeta(OPERATOR, null, null, "flow-1");
        service.applyExternalApprovalResult(TICKET_ID, ApprovalInstanceStatus.COMPLETED, meta);

        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        Map<String, Object> data = JsonUtils.toObj(eventCaptor.getValue().getEventData(), Map.class);
        assertFalse("no write ⇒ no identityBackfill marker", data.containsKey("identityBackfill"));
    }

    @Test
    public void strictMode_missingFlowId_rejectedFlowIdMissing() {
        // M7 strict tier with the switch ON (gray-release posture): the minimal call is rejected.
        when(systemDal.fetchSystemConf(RootUserConfig.Fields.govExternalCommandStrictFlow, Boolean.class))
            .thenReturn(Boolean.TRUE);
        DmApprovalDO ticket = buildExternalTicket(ApprovalStatus.WAIT_APPROVAL);
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(ticket);

        ExternalCommandMeta meta = new ExternalCommandMeta(OPERATOR, null, null, null, 1);
        try {
            service.applyExternalApprovalResult(TICKET_ID, ApprovalInstanceStatus.COMPLETED, meta);
            fail("Expected ErrorMessageException for missing flowId in strict mode");
        } catch (ErrorMessageException ex) {
            // ok
        }

        assertFalse(testHandler.approvedCalled);
        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        Map<String, Object> data = JsonUtils.toObj(eventCaptor.getValue().getEventData(), Map.class);
        assertEquals("FLOW_ID_MISSING", data.get("reason"));
        assertEquals("approve", data.get("command"));
    }

    @Test
    public void strictMode_missingRound_rejectsRoundMissing() {
        when(systemDal.fetchSystemConf(RootUserConfig.Fields.govExternalCommandStrictFlow, Boolean.class))
            .thenReturn(Boolean.TRUE);
        DmApprovalDO ticket = buildExternalTicket(ApprovalStatus.WAIT_APPROVAL);
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(ticket);

        // flowId carried (guard 4 passes — identity blank skips the comparison even in strict
        // mode; strict only rejects MISSING values) but round absent → guard 5 rejects.
        ExternalCommandMeta meta = new ExternalCommandMeta(OPERATOR, null, null, "flow-x", null);
        try {
            service.applyExternalApprovalResult(TICKET_ID, ApprovalInstanceStatus.COMPLETED, meta);
            fail("Expected ErrorMessageException for missing round in strict mode");
        } catch (ErrorMessageException ex) {
            // ok
        }

        assertFalse(testHandler.approvedCalled);
        verify(approvalMapper, never()).updateThirdApprovalInfo(anyLong(), anyString(), any());
        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        Map<String, Object> data = JsonUtils.toObj(eventCaptor.getValue().getEventData(), Map.class);
        assertEquals("ROUND_MISSING", data.get("reason"));
    }

    @Test
    public void strictMode_readFailure_staysTolerant() {
        // Fail-safe direction: an unreadable switch must NEVER activate the strict tier
        // (DEFAULT_STRICT_FLOW_MODE = false) — the frozen minimal-call contract wins.
        when(systemDal.fetchSystemConf(RootUserConfig.Fields.govExternalCommandStrictFlow, Boolean.class))
            .thenThrow(new RuntimeException("kv store down"));
        DmApprovalDO ticket = buildExternalTicket(ApprovalStatus.WAIT_APPROVAL);
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(ticket);

        ExternalCommandMeta meta = new ExternalCommandMeta(OPERATOR, null, null, null);
        service.applyExternalApprovalResult(TICKET_ID, ApprovalInstanceStatus.COMPLETED, meta);

        assertTrue("read failure ⇒ tolerant mode ⇒ minimal call succeeds", testHandler.approvedCalled);
    }

    @Test
    public void d5ResubmitTicket_stateGuardFiresBeforeFlowGuards_zeroMisfire() {
        // A4 (adr-b §1.4): after a ticket-level External resubmit (D5) the ticket sits in
        // WAIT_EXEC with the ORIGINAL flowId still recorded. A three-state command — even one
        // carrying a different flowId — is rejected by the cheaper STATE guard first
        // (STATUS_MISMATCH), never by FLOW_ID_MISMATCH: the flow guards are unreachable in the
        // D5 scenario, so they can never misfire on it. (execute/return do not consume flowId
        // at all — pinned by the O-C2-1 audits in GovOpenApiServiceImplTest.)
        DmApprovalDO ticket = buildExternalTicket(ApprovalStatus.WAIT_EXEC);
        ticket.setApproIdentity("flow-original");
        ticket.setApprovalRound(1);
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(ticket);

        ExternalCommandMeta meta = new ExternalCommandMeta(OPERATOR, null, null, "flow-other", 9);
        try {
            service.applyExternalApprovalResult(TICKET_ID, ApprovalInstanceStatus.COMPLETED, meta);
            fail("Expected ErrorMessageException for non-WAIT_APPROVAL status");
        } catch (ErrorMessageException ex) {
            // ok
        }

        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        Map<String, Object> data = JsonUtils.toObj(eventCaptor.getValue().getEventData(), Map.class);
        assertEquals("state guard precedes the flow guards (order pin)", "STATUS_MISMATCH", data.get("reason"));
        assertFalse(testHandler.approvedCalled);
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

        // 10-11 R1: the guard rejection itself IS audited — STATUS_MISMATCH, command=approve
        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        DmDbChangeEventDO event = eventCaptor.getValue();
        assertEquals(GovEventType.EXTERNAL_COMMAND_REJECTED.name(), event.getEventType());
        assertEquals(Long.valueOf(TICKET_ID), event.getTicketId());
        assertEquals(OPERATOR, event.getOperatorUid());
        assertEquals(ApprovalStatus.WAIT_CONFIRM.name(), event.getFromStatus());
        assertNull("a rejected command never moves the ticket", event.getToStatus());
        Map<String, Object> data = JsonUtils.toObj(event.getEventData(), Map.class);
        assertEquals("EXTERNAL_COMMAND", data.get("via"));
        assertEquals("approve", data.get("command"));
        assertEquals("STATUS_MISMATCH", data.get("reason"));
        assertEquals(ApprovalStatus.WAIT_CONFIRM.name(), data.get("ticketStatus"));
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

        // 10-11 R1: END_STATUS rejection audited with the command name and the status that rejected it
        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        DmDbChangeEventDO event = eventCaptor.getValue();
        assertEquals(GovEventType.EXTERNAL_COMMAND_REJECTED.name(), event.getEventType());
        assertEquals(ApprovalStatus.FINISHED.name(), event.getFromStatus());
        Map<String, Object> data = JsonUtils.toObj(event.getEventData(), Map.class);
        assertEquals("approve", data.get("command"));
        assertEquals("END_STATUS", data.get("reason"));
        assertEquals(ApprovalStatus.FINISHED.name(), data.get("ticketStatus"));
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

        // 10-11 R1: CHANNEL_MISMATCH rejection audited, carrying the actual channel as approType
        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        DmDbChangeEventDO event = eventCaptor.getValue();
        assertEquals(GovEventType.EXTERNAL_COMMAND_REJECTED.name(), event.getEventType());
        Map<String, Object> data = JsonUtils.toObj(event.getEventData(), Map.class);
        assertEquals("approve", data.get("command"));
        assertEquals("CHANNEL_MISMATCH", data.get("reason"));
        assertEquals(ApprovalType.DingTalk.name(), data.get("approType"));
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

        // 10-11 R4: the duplicate command's rejection is audited too — insert #1 was the
        // success event (EXTERNAL_APPROVED), insert #2 is the rejection of the replay.
        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper, times(2)).insert(eventCaptor.capture());
        DmDbChangeEventDO rejectedEvent = eventCaptor.getAllValues().get(1);
        assertEquals(GovEventType.EXTERNAL_COMMAND_REJECTED.name(), rejectedEvent.getEventType());
        Map<String, Object> data = JsonUtils.toObj(rejectedEvent.getEventData(), Map.class);
        assertEquals("approve", data.get("command"));
        assertEquals("STATUS_MISMATCH", data.get("reason"));
    }

    @Test
    public void guardRejection_recordsFlowId_andOmitsApproverComment() {
        // R1: externalFlowId (若携带) enters the rejected-event payload — a stale-round callback's
        // flow identity is exactly the forensic value. approver/comment are execution attributions
        // of an ACCEPTED command — a rejected one records only the audit fields (payload discipline:
        // no credentials / signature parameters either, by construction of the event builder).
        DmApprovalDO ticket = buildExternalTicket(ApprovalStatus.FINISHED);
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(ticket);

        ExternalCommandMeta meta = new ExternalCommandMeta(OPERATOR, "alice", "late approval", "stale-flow-789");
        try {
            service.applyExternalApprovalResult(TICKET_ID, ApprovalInstanceStatus.COMPLETED, meta);
            fail("Expected ErrorMessageException for end-status ticket");
        } catch (ErrorMessageException ex) {
            // ok — guard rejects
        }

        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        Map<String, Object> data = JsonUtils.toObj(eventCaptor.getValue().getEventData(), Map.class);
        assertEquals("stale-flow-789", data.get("externalFlowId"));
        assertFalse(data.containsKey("approver"));
        assertFalse(data.containsKey("comment"));
    }

    @Test
    public void cancelOnEndStatus_recordsRejectedEventWithCancelCommand() {
        // Command-name mapping for the cancel leg (TERMINATED → cancel) on the guard path
        DmApprovalDO ticket = buildExternalTicket(ApprovalStatus.CANCELED);
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(ticket);

        ExternalCommandMeta meta = new ExternalCommandMeta(OPERATOR, null, null, null);
        try {
            service.applyExternalApprovalResult(TICKET_ID, ApprovalInstanceStatus.TERMINATED, meta);
            fail("Expected ErrorMessageException for end-status ticket");
        } catch (ErrorMessageException ex) {
            // ok
        }

        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        DmDbChangeEventDO event = eventCaptor.getValue();
        assertEquals(GovEventType.EXTERNAL_COMMAND_REJECTED.name(), event.getEventType());
        Map<String, Object> data = JsonUtils.toObj(event.getEventData(), Map.class);
        assertEquals("cancel", data.get("command"));
        assertEquals("END_STATUS", data.get("reason"));
        // zero state side effects (R2)
        verify(approvalStateService, never()).updateProcessStatus(anyLong(), any(), any(), any());
        assertFalse(testHandler.canceledCalled);
    }

    @Test
    public void rejectionAuditFailure_neverSwallowsTheGuardThrow() {
        // A3/R2: the audit write is best-effort — its failure must not replace or suppress the
        // rejection, and must not add state side effects.
        DmApprovalDO ticket = buildExternalTicket(ApprovalStatus.FINISHED);
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(ticket);
        when(eventMapper.insert(any(DmDbChangeEventDO.class))).thenThrow(new RuntimeException("audit db down"));

        ExternalCommandMeta meta = new ExternalCommandMeta(OPERATOR, null, null, null);
        try {
            service.applyExternalApprovalResult(TICKET_ID, ApprovalInstanceStatus.COMPLETED, meta);
            fail("Expected ErrorMessageException even when the audit insert fails");
        } catch (ErrorMessageException ex) {
            // ok — the rejection is the contract; audit degradation is log-only
        }
        verify(approvalStateService, never()).updateProcessStatus(anyLong(), any(), any(), any());
        assertFalse(testHandler.approvedCalled);
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
