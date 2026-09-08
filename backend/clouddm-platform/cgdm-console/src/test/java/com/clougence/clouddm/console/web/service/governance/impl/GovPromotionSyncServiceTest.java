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
import java.util.Set;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.console.web.component.governance.PromotionStateMachine;
import com.clougence.clouddm.console.web.service.governance.GovPromotionSyncService;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.DbChangeGovernDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangeEventMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangePromotionMapper;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangePromotionDO;
import com.clougence.clouddm.platform.dal.model.dbchange.GovEventType;
import com.clougence.clouddm.platform.dal.model.dbchange.PromotionStatus;

public class GovPromotionSyncServiceTest {

    private GovPromotionSyncService service;

    private DbChangeGovernDal     dbChangeGovernDal;
    private ApprovalDal           approvalDal;
    private PromotionStateMachine  stateMachine;
    private DmDbChangePromotionMapper promotionMapper;
    private DmApprovalMapper      approvalMapper;
    private DmDbChangeEventMapper  eventMapper;

    private static final long PROMOTION_ID = 500L;
    private static final long REVISION_ID  = 300L;
    private static final long TICKET_ID    = 999L;

    @Before
    public void setUp() {
        dbChangeGovernDal = mock(DbChangeGovernDal.class);
        approvalDal = mock(ApprovalDal.class);
        stateMachine = mock(PromotionStateMachine.class);

        promotionMapper = mock(DmDbChangePromotionMapper.class);
        approvalMapper = mock(DmApprovalMapper.class);
        eventMapper = mock(DmDbChangeEventMapper.class);

        when(dbChangeGovernDal.promotionMapper()).thenReturn(promotionMapper);
        when(dbChangeGovernDal.eventMapper()).thenReturn(eventMapper);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);

        GovPromotionSyncServiceImpl impl = new GovPromotionSyncServiceImpl();
        ReflectionTestUtils.setField(impl, "dbChangeGovernDal", dbChangeGovernDal);
        ReflectionTestUtils.setField(impl, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(impl, "stateMachine", stateMachine);
        service = impl;
    }

    @Test
    public void sync_ticketWaitApproval_promotionTransitToApproving() {
        DmDbChangePromotionDO promo = buildPromotion(PromotionStatus.CREATED, TICKET_ID);
        when(promotionMapper.listNonTerminal()).thenReturn(List.of(promo));
        setupTicket(TICKET_ID, ApprovalStatus.WAIT_APPROVAL);
        when(stateMachine.mapTicketStatus("WAIT_APPROVAL", PromotionStatus.CREATED)).thenReturn(PromotionStatus.APPROVING);
        when(stateMachine.legalFromFor(PromotionStatus.APPROVING)).thenReturn(Set.of(PromotionStatus.CREATED));
        when(stateMachine.transit(PROMOTION_ID, Set.of(PromotionStatus.CREATED), PromotionStatus.APPROVING)).thenReturn(true);

        service.syncPromotionStatus();

        verify(eventMapper).insert(any(com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO.class));
        ArgumentCaptor<DmDbChangeEventDO> captor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(captor.capture());
        assertEquals(GovEventType.STATUS_SYNC.name(), captor.getValue().getEventType());
    }

    @Test
    public void sync_ticketFinished_promotionTransitToSucceeded() {
        DmDbChangePromotionDO promo = buildPromotion(PromotionStatus.EXECUTING, TICKET_ID);
        when(promotionMapper.listNonTerminal()).thenReturn(List.of(promo));
        setupTicket(TICKET_ID, ApprovalStatus.FINISHED);
        when(stateMachine.mapTicketStatus("FINISHED", PromotionStatus.EXECUTING)).thenReturn(PromotionStatus.SUCCEEDED);
        when(stateMachine.legalFromFor(PromotionStatus.SUCCEEDED)).thenReturn(Set.of(PromotionStatus.EXECUTING));
        when(stateMachine.transit(PROMOTION_ID, Set.of(PromotionStatus.EXECUTING), PromotionStatus.SUCCEEDED)).thenReturn(true);

        service.syncPromotionStatus();
        verify(eventMapper).insert(any(com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO.class));
    }

    // ======= B4: EXECUTING restart three-state =======

    @Test
    public void sync_ticketRunning_promotionStaysExecuting_idempotentSkip() {
        // Restart while ticket is still RUNNING — promotion already EXECUTING, no transit.
        DmDbChangePromotionDO promo = buildPromotion(PromotionStatus.EXECUTING, TICKET_ID);
        when(promotionMapper.listNonTerminal()).thenReturn(List.of(promo));
        setupTicket(TICKET_ID, ApprovalStatus.RUNNING);
        when(stateMachine.mapTicketStatus("RUNNING", PromotionStatus.EXECUTING)).thenReturn(PromotionStatus.EXECUTING);

        service.syncPromotionStatus();

        verify(stateMachine, never()).transit(anyLong(), any(), any());
        verify(eventMapper, never()).insert(any(com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO.class));
    }

    @Test
    public void sync_ticketExecPause_promotionStaysExecuting_idempotentSkip() {
        // Restart while ticket is EXEC_PAUSE — promotion already EXECUTING, no transit.
        DmDbChangePromotionDO promo = buildPromotion(PromotionStatus.EXECUTING, TICKET_ID);
        when(promotionMapper.listNonTerminal()).thenReturn(List.of(promo));
        setupTicket(TICKET_ID, ApprovalStatus.EXEC_PAUSE);
        when(stateMachine.mapTicketStatus("EXEC_PAUSE", PromotionStatus.EXECUTING)).thenReturn(PromotionStatus.EXECUTING);

        service.syncPromotionStatus();

        verify(stateMachine, never()).transit(anyLong(), any(), any());
        verify(eventMapper, never()).insert(any(com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO.class));
    }

    @Test
    public void sync_execFail_promotionTransitToFailed() {
        DmDbChangePromotionDO promo = buildPromotion(PromotionStatus.EXECUTING, TICKET_ID);
        when(promotionMapper.listNonTerminal()).thenReturn(List.of(promo));
        setupTicket(TICKET_ID, ApprovalStatus.EXEC_FAIL);
        when(stateMachine.mapTicketStatus("EXEC_FAIL", PromotionStatus.EXECUTING)).thenReturn(PromotionStatus.FAILED);
        when(stateMachine.legalFromFor(PromotionStatus.FAILED)).thenReturn(Set.of(PromotionStatus.EXECUTING));
        when(stateMachine.transit(PROMOTION_ID, Set.of(PromotionStatus.EXECUTING), PromotionStatus.FAILED)).thenReturn(true);

        service.syncPromotionStatus();
        verify(eventMapper).insert(any(com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO.class));
    }

    @Test
    public void sync_revivalEdge_failedToExecuting() {
        DmDbChangePromotionDO promo = buildPromotion(PromotionStatus.FAILED, TICKET_ID);
        when(promotionMapper.listNonTerminal()).thenReturn(List.of(promo));
        setupTicket(TICKET_ID, ApprovalStatus.RUNNING);
        when(stateMachine.mapTicketStatus("RUNNING", PromotionStatus.FAILED)).thenReturn(PromotionStatus.EXECUTING);
        when(stateMachine.legalFromFor(PromotionStatus.EXECUTING)).thenReturn(Set.of(PromotionStatus.CONFIRMED, PromotionStatus.FAILED));
        when(stateMachine.transit(PROMOTION_ID, Set.of(PromotionStatus.CONFIRMED, PromotionStatus.FAILED), PromotionStatus.EXECUTING)).thenReturn(true);

        service.syncPromotionStatus();
        verify(eventMapper).insert(any(com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO.class));
    }

    @Test
    public void sync_closedAfterFailed_failedStays() {
        DmDbChangePromotionDO promo = buildPromotion(PromotionStatus.FAILED, TICKET_ID);
        when(promotionMapper.listNonTerminal()).thenReturn(List.of(promo));
        setupTicket(TICKET_ID, ApprovalStatus.CLOSED);
        when(stateMachine.mapTicketStatus("CLOSED", PromotionStatus.FAILED)).thenReturn(null);

        service.syncPromotionStatus();
        verify(eventMapper, never()).insert(any(com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO.class));
    }

    @Test
    public void sync_alreadyAtTarget_idempotentSkip() {
        DmDbChangePromotionDO promo = buildPromotion(PromotionStatus.APPROVING, TICKET_ID);
        when(promotionMapper.listNonTerminal()).thenReturn(List.of(promo));
        setupTicket(TICKET_ID, ApprovalStatus.WAIT_APPROVAL);
        when(stateMachine.mapTicketStatus("WAIT_APPROVAL", PromotionStatus.APPROVING)).thenReturn(PromotionStatus.APPROVING);

        service.syncPromotionStatus();
        verify(stateMachine, never()).transit(anyLong(), any(), any());
        verify(eventMapper, never()).insert(any(com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO.class));
    }

    @Test
    public void sync_transitReturnsZero_idempotentSkip() {
        DmDbChangePromotionDO promo = buildPromotion(PromotionStatus.CREATED, TICKET_ID);
        when(promotionMapper.listNonTerminal()).thenReturn(List.of(promo));
        setupTicket(TICKET_ID, ApprovalStatus.WAIT_APPROVAL);
        when(stateMachine.mapTicketStatus("WAIT_APPROVAL", PromotionStatus.CREATED)).thenReturn(PromotionStatus.APPROVING);
        when(stateMachine.legalFromFor(PromotionStatus.APPROVING)).thenReturn(Set.of(PromotionStatus.CREATED));
        when(stateMachine.transit(PROMOTION_ID, Set.of(PromotionStatus.CREATED), PromotionStatus.APPROVING)).thenReturn(false);

        service.syncPromotionStatus();
        verify(eventMapper, never()).insert(any(com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO.class));
    }

    @Test
    public void sync_nullProdApprovalId_skip() {
        DmDbChangePromotionDO promo = buildPromotion(PromotionStatus.CREATED, null);
        when(promotionMapper.listNonTerminal()).thenReturn(List.of(promo));

        service.syncPromotionStatus();
        verify(approvalMapper, never()).queryById(any());
        verify(eventMapper, never()).insert(any(com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO.class));
    }

    @Test
    public void sync_ticketNotFound_skip() {
        DmDbChangePromotionDO promo = buildPromotion(PromotionStatus.CREATED, TICKET_ID);
        when(promotionMapper.listNonTerminal()).thenReturn(List.of(promo));
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(null);

        service.syncPromotionStatus();
        verify(eventMapper, never()).insert(any(com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO.class));
    }

    @Test
    public void sync_illegalTransitionFromCurrent_skip() {
        DmDbChangePromotionDO promo = buildPromotion(PromotionStatus.CREATED, TICKET_ID);
        when(promotionMapper.listNonTerminal()).thenReturn(List.of(promo));
        setupTicket(TICKET_ID, ApprovalStatus.FINISHED);
        when(stateMachine.mapTicketStatus("FINISHED", PromotionStatus.CREATED)).thenReturn(PromotionStatus.SUCCEEDED);
        when(stateMachine.legalFromFor(PromotionStatus.SUCCEEDED)).thenReturn(Set.of(PromotionStatus.EXECUTING));

        service.syncPromotionStatus();
        verify(stateMachine, never()).transit(anyLong(), any(), any());
        verify(eventMapper, never()).insert(any(com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO.class));
    }

    @Test
    public void sync_exceptionInOnePromotion_continuesToNext() {
        DmDbChangePromotionDO promo1 = buildPromotion(PromotionStatus.CREATED, TICKET_ID);
        DmDbChangePromotionDO promo2 = buildPromotion(PromotionStatus.CREATED, 888L);
        promo2.setId(501L);
        when(promotionMapper.listNonTerminal()).thenReturn(List.of(promo1, promo2));

        // First ticket throws (simulating DB error)
        when(approvalMapper.queryById(TICKET_ID)).thenThrow(new RuntimeException("DB error"));
        DmApprovalDO ticket2 = new DmApprovalDO();
        ticket2.setTicketStatus(ApprovalStatus.WAIT_APPROVAL);
        when(approvalMapper.queryById(888L)).thenReturn(ticket2);
        when(stateMachine.mapTicketStatus("WAIT_APPROVAL", PromotionStatus.CREATED)).thenReturn(PromotionStatus.APPROVING);
        when(stateMachine.legalFromFor(PromotionStatus.APPROVING)).thenReturn(Set.of(PromotionStatus.CREATED));
        when(stateMachine.transit(eq(501L), any(), eq(PromotionStatus.APPROVING))).thenReturn(true);

        service.syncPromotionStatus();

        // Second promotion should still have been processed
        verify(stateMachine).transit(eq(501L), any(), eq(PromotionStatus.APPROVING));
    }

    private DmDbChangePromotionDO buildPromotion(PromotionStatus status, Long prodApprovalId) {
        DmDbChangePromotionDO promo = new DmDbChangePromotionDO();
        promo.setId(PROMOTION_ID);
        promo.setRevisionId(REVISION_ID);
        promo.setProdApprovalId(prodApprovalId);
        promo.setStatus(status.name());
        return promo;
    }

    private void setupTicket(long ticketId, ApprovalStatus status) {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(ticketId);
        ticket.setTicketStatus(status);
        when(approvalMapper.queryById(ticketId)).thenReturn(ticket);
    }
}
