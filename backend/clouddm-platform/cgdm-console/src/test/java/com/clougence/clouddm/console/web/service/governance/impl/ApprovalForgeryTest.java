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
package com.clougence.clouddm.console.web.service.governance.impl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.console.web.component.approval.ApprovalStateService;
import com.clougence.clouddm.console.web.component.approval.handler.ChangeApprovalHandler;
import com.clougence.clouddm.console.web.component.cicd.ImSenderService;
import com.clougence.clouddm.console.web.component.governance.PromotionStateMachine;
import com.clougence.clouddm.console.web.service.cicd.ChangeCascadeService;
import com.clougence.clouddm.console.web.service.governance.GovPromotionSyncService;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.AuthDal;
import com.clougence.clouddm.platform.dal.access.ChangeFlowDal;
import com.clougence.clouddm.platform.dal.access.DbChangeGovernDal;
import com.clougence.clouddm.platform.dal.access.ExecutionDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangeEventMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangePromotionMapper;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangePromotionDO;
import com.clougence.clouddm.platform.dal.model.dbchange.GovEventType;
import com.clougence.clouddm.platform.dal.model.dbchange.PromotionStatus;
import com.clougence.utils.JsonUtils;

/**
 * Phase 11 Wave A / A2: forged APPROVED cannot influence server-side approval state.
 * <p>
 * Three layers of defense against forged {@code approvalStatus}:
 * <ol>
 * <li>FO level: {@code @JsonAnySetter} rejects the field at deserialization (covered in
 * GovFoSmugglingTest). This test pins the remaining two layers:</li>
 * <li>Approval callback level: {@code approvalApproved} takes no status parameter — it always
 * writes WAIT_CONFIRM. A forged payload cannot inject APPROVED.</li>
 * <li>Sync level: {@code syncPromotionStatus} reads ticket status from DB only — the promotion
 * follows the DB ticket status, never any client-supplied value.</li>
 * </ol>
 * Additionally, {@code ApprovalMO} has {@code @JsonIgnoreProperties(ignoreUnknown = true)} —
 * an {@code approvalStatus} field injected into ticketInfo JSON is silently dropped.
 */
public class ApprovalForgeryTest {

    // ======= approvalApproved: no status parameter, always WAIT_CONFIRM =======

    private ChangeApprovalHandler  handler;
    private ApprovalStateService     approvalStateService;
    private ApprovalDal              approvalDal;
    private DmApprovalMapper         approvalMapper;
    private ChangeFlowDal            changeFlowDal;
    private ExecutionDal             execDal;
    private AuthDal                  authDal;
    private ChangeCascadeService     changeCascadeService;

    // ======= sync: reads DB ticket status =======

    private GovPromotionSyncService  syncService;
    private DbChangeGovernDal       syncDbChangeGovernDal;
    private DmDbChangePromotionMapper syncPromotionMapper;
    private DmDbChangeEventMapper    syncEventMapper;
    private ApprovalDal             syncApprovalDal;
    private DmApprovalMapper         syncApprovalMapper;
    private PromotionStateMachine   syncStateMachine;

    private static final long TICKET_ID    = 100L;
    private static final long PROMOTION_ID = 500L;
    private static final long REVISION_ID  = 300L;

    @Before
    public void setUp() {
        // --- ChangeApprovalHandler setup (mirrors ApprovalSyncIdempotencyTest) ---
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

        // --- GovPromotionSyncService setup (mirrors GovPromotionSyncServiceTest) ---
        syncDbChangeGovernDal = mock(DbChangeGovernDal.class);
        syncApprovalDal = mock(ApprovalDal.class);
        syncStateMachine = mock(PromotionStateMachine.class);
        syncPromotionMapper = mock(DmDbChangePromotionMapper.class);
        syncApprovalMapper = mock(DmApprovalMapper.class);
        syncEventMapper = mock(DmDbChangeEventMapper.class);

        when(syncDbChangeGovernDal.promotionMapper()).thenReturn(syncPromotionMapper);
        when(syncDbChangeGovernDal.eventMapper()).thenReturn(syncEventMapper);
        when(syncApprovalDal.approvalMapper()).thenReturn(syncApprovalMapper);

        GovPromotionSyncServiceImpl syncImpl = new GovPromotionSyncServiceImpl();
        ReflectionTestUtils.setField(syncImpl, "dbChangeGovernDal", syncDbChangeGovernDal);
        ReflectionTestUtils.setField(syncImpl, "approvalDal", syncApprovalDal);
        ReflectionTestUtils.setField(syncImpl, "stateMachine", syncStateMachine);
        syncService = syncImpl;
    }

    // ======= Layer 2: approvalApproved always writes WAIT_CONFIRM =======

    @Test
    public void approvalApproved_alwaysWritesWaitConfirm_neverApproved() {
        ImSenderService sender = mock(ImSenderService.class);

        handler.approvalApproved(TICKET_ID, ApprovalBiz.DM_CHANGE, sender);

        // The callback writes WAIT_CONFIRM — it does NOT write APPROVED
        verify(approvalStateService).updateApprovalStatus(TICKET_ID, ApprovalStatus.WAIT_CONFIRM, null);
        verify(approvalStateService, never()).updateApprovalStatus(eq(TICKET_ID), eq(ApprovalStatus.WAIT_EXEC), any());
        verify(approvalStateService, never()).updateApprovalStatus(eq(TICKET_ID), eq(ApprovalStatus.RUNNING), any());
        verify(approvalStateService, never()).updateApprovalStatus(eq(TICKET_ID), eq(ApprovalStatus.FINISHED), any());
    }

    // ======= Layer 3: sync reads DB ticket status, ignores ticketInfo forgery =======

    @Test
    public void sync_ticketInfoForgedApprovalStatus_ignored_dbStatusAuthoritative() {
        // Ticket in DB: still WAIT_APPROVAL (not approved yet)
        // ticketInfo has a forged "approvalStatus":"APPROVED" field — ignored by ApprovalMO
        // (@JsonIgnoreProperties(ignoreUnknown = true))
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setTicketStatus(ApprovalStatus.WAIT_APPROVAL);
        ticket.setTicketInfo("{\"govRole\":\"PROD\",\"promotionId\":500,\"revisionId\":300,\"logicalDbId\":10,\"approvalStatus\":\"APPROVED\"}");
        when(syncApprovalMapper.queryById(TICKET_ID)).thenReturn(ticket);

        DmDbChangePromotionDO promotion = new DmDbChangePromotionDO();
        promotion.setId(PROMOTION_ID);
        promotion.setRevisionId(REVISION_ID);
        promotion.setProdApprovalId(TICKET_ID);
        promotion.setStatus(PromotionStatus.CREATED.name());
        when(syncPromotionMapper.listNonTerminal()).thenReturn(List.of(promotion));

        // mapTicketStatus: WAIT_APPROVAL + CREATED → APPROVING (not APPROVED)
        when(syncStateMachine.mapTicketStatus(ApprovalStatus.WAIT_APPROVAL.name(), PromotionStatus.CREATED))
            .thenReturn(PromotionStatus.APPROVING);
        when(syncStateMachine.legalFromFor(PromotionStatus.APPROVING))
            .thenReturn(java.util.Set.of(PromotionStatus.CREATED));
        when(syncStateMachine.transit(PROMOTION_ID, java.util.Set.of(PromotionStatus.CREATED), PromotionStatus.APPROVING))
            .thenReturn(true);

        syncService.syncPromotionStatus();

        // Verify: promotion transits to APPROVING (following DB ticket status), NOT APPROVED
        verify(syncStateMachine).transit(PROMOTION_ID, java.util.Set.of(PromotionStatus.CREATED), PromotionStatus.APPROVING);
        verify(syncStateMachine, never()).transit(eq(PROMOTION_ID), any(), eq(PromotionStatus.APPROVED));
        verify(syncStateMachine, never()).transit(eq(PROMOTION_ID), any(), eq(PromotionStatus.SUCCEEDED));

        // Verify: STATUS_SYNC event written with the DB-derived status
        org.mockito.ArgumentCaptor<DmDbChangeEventDO> eventCaptor
            = org.mockito.ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(syncEventMapper).insert(eventCaptor.capture());
        assertEquals(GovEventType.STATUS_SYNC.name(), eventCaptor.getValue().getEventType());
        assertEquals(PromotionStatus.APPROVING.name(), eventCaptor.getValue().getToStatus());
    }

    @Test
    public void sync_ticketStillWaitingApproval_forgedApprovalStatusInEventData_inconsequential() {
        // Even if a STATUS_SYNC event's eventData somehow contained "APPROVED",
        // the sync service never reads eventData — it only reads DB ticket status.
        // This test pins that the sync loop is a one-way DB-driven mapping.
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setTicketStatus(ApprovalStatus.WAIT_CONFIRM);
        when(syncApprovalMapper.queryById(TICKET_ID)).thenReturn(ticket);

        DmDbChangePromotionDO promotion = new DmDbChangePromotionDO();
        promotion.setId(PROMOTION_ID);
        promotion.setRevisionId(REVISION_ID);
        promotion.setProdApprovalId(TICKET_ID);
        promotion.setStatus(PromotionStatus.APPROVING.name());
        when(syncPromotionMapper.listNonTerminal()).thenReturn(List.of(promotion));

        // WAIT_CONFIRM + APPROVING → APPROVED (server-side transition)
        when(syncStateMachine.mapTicketStatus(ApprovalStatus.WAIT_CONFIRM.name(), PromotionStatus.APPROVING))
            .thenReturn(PromotionStatus.APPROVED);
        when(syncStateMachine.legalFromFor(PromotionStatus.APPROVED))
            .thenReturn(java.util.Set.of(PromotionStatus.APPROVING));
        when(syncStateMachine.transit(PROMOTION_ID, java.util.Set.of(PromotionStatus.APPROVING), PromotionStatus.APPROVED))
            .thenReturn(true);

        syncService.syncPromotionStatus();

        // The transit happened because DB ticket status = WAIT_CONFIRM (not forged)
        verify(syncStateMachine).transit(PROMOTION_ID, java.util.Set.of(PromotionStatus.APPROVING), PromotionStatus.APPROVED);
    }
}
