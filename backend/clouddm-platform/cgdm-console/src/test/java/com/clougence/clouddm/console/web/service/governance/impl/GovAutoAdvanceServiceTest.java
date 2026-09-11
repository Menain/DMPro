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

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.console.web.component.approval.ApprovalHandler;
import com.clougence.clouddm.console.web.component.approval.ApprovalStateService;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalStageMO;
import com.clougence.clouddm.console.web.component.cicd.ImSenderService;
import com.clougence.clouddm.console.web.component.execute.AutoExecService;
import com.clougence.clouddm.console.web.model.fo.ticket.DmAutoExecConfigFO;
import com.clougence.clouddm.console.web.service.approval.ApprovalControlService;
import com.clougence.clouddm.console.web.service.governance.GovAutoAdvanceService;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.DbChangeEventDal;
import com.clougence.clouddm.platform.dal.access.TicketDbStmtDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangeEventMapper;
import com.clougence.clouddm.platform.dal.mapper.govticket.DmTicketDbStmtMapper;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalProcessStatus;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStage;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.dbchange.ChangeType;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO;
import com.clougence.clouddm.platform.dal.model.dbchange.GovEventType;
import com.clougence.utils.JsonUtils;

public class GovAutoAdvanceServiceTest {

    private GovAutoAdvanceService     service;

    private ApprovalDal               approvalDal;
    private DmApprovalMapper          approvalMapper;
    private DbChangeEventDal        dbChangeEventDal;
    private DmDbChangeEventMapper     eventMapper;
    private ApprovalStateService      approvalStateService;
    private ApprovalControlService    approvalControlService;
    private ImSenderService           imSenderService;
    private ApprovalHandler           changeHandler;
    private TicketDbStmtDal           ticketDbStmtDal;
    private DmTicketDbStmtMapper      stmtMapper;
    private AutoExecService           autoExecService;

    private static final String       PUID        = "puid-001";
    private static final long         TICKET_ID   = 100L;

    @Before
    public void setUp() {
        approvalDal = mock(ApprovalDal.class);
        dbChangeEventDal = mock(DbChangeEventDal.class);
        approvalStateService = mock(ApprovalStateService.class);
        approvalControlService = mock(ApprovalControlService.class);
        imSenderService = mock(ImSenderService.class);
        changeHandler = mock(ApprovalHandler.class);
        ticketDbStmtDal = mock(TicketDbStmtDal.class);
        stmtMapper = mock(DmTicketDbStmtMapper.class);
        autoExecService = mock(AutoExecService.class);

        approvalMapper = mock(DmApprovalMapper.class);
        eventMapper = mock(DmDbChangeEventMapper.class);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);
        when(dbChangeEventDal.eventMapper()).thenReturn(eventMapper);
        when(changeHandler.handleType()).thenReturn(ApprovalBiz.DM_CHANGE);
        when(ticketDbStmtDal.stmtMapper()).thenReturn(stmtMapper);
        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(Collections.emptyList());

        impl = new GovAutoAdvanceServiceImpl(List.of(changeHandler));
        ReflectionTestUtils.setField(impl, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(impl, "dbChangeEventDal", dbChangeEventDal);
        ReflectionTestUtils.setField(impl, "approvalStateService", approvalStateService);
        ReflectionTestUtils.setField(impl, "approvalControlService", approvalControlService);
        ReflectionTestUtils.setField(impl, "imSenderService", imSenderService);
        ReflectionTestUtils.setField(impl, "ticketDbStmtDal", ticketDbStmtDal);
        ReflectionTestUtils.setField(impl, "autoExecService", autoExecService);

        service = impl;

        when(approvalMapper.listUnFinishTicketIdList()).thenReturn(Collections.emptyList());
    }

    private GovAutoAdvanceServiceImpl impl;

    // ======= hit matrix tests =======

    @Test
    public void advance_nonGovernanceTicket_skippedNoGovQueries() {
        DmApprovalDO ticket = buildTicket(ApprovalBiz.DM_QUERY, null, ApprovalStatus.WAIT_APPROVAL);
        when(approvalMapper.listUnFinishTicketIdList()).thenReturn(List.of(TICKET_ID));
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket);

        service.advancePreTickets();

        verify(dbChangeEventDal, never()).eventMapper();
        verifyNoInteractions(approvalControlService);
    }

    @Test
    public void advance_v2PreDdlButNotWaitApproval_skipped() {
        DmApprovalDO ticket = buildTicket(ApprovalBiz.DM_CHANGE, "PRE_DDL", ApprovalStatus.RUNNING);
        when(approvalMapper.listUnFinishTicketIdList()).thenReturn(List.of(TICKET_ID));
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket);

        service.advancePreTickets();

        verifyNoInteractions(approvalControlService);
    }

    @Test
    public void advance_v2ProdDml_notAutoAdvanced() {
        // PROD_DML tickets are NOT auto-advanced (human approval required)
        DmApprovalDO ticket = buildTicket(ApprovalBiz.DM_CHANGE, "PROD_DML", ApprovalStatus.WAIT_APPROVAL);
        when(approvalMapper.listUnFinishTicketIdList()).thenReturn(List.of(TICKET_ID));
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket);

        service.advancePreTickets();

        verifyNoInteractions(approvalControlService);
    }

    @Test
    public void advance_oldGovernanceTicketWithoutTicketType_skipped() {
        // Old tickets (no ticketType — e.g. legacy governance or pre-v2) must not be advanced by the v2 filter
        DmApprovalDO ticket = buildTicket(ApprovalBiz.DM_CHANGE, null, ApprovalStatus.WAIT_APPROVAL);
        when(approvalMapper.listUnFinishTicketIdList()).thenReturn(List.of(TICKET_ID));
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket);

        service.advancePreTickets();

        verifyNoInteractions(approvalControlService);
    }

    @Test
    public void advance_v2PreDdl_dml_advancedWithCorrectConfig() {
        DmApprovalDO ticket = buildTicket(ApprovalBiz.DM_CHANGE, "PRE_DDL", ApprovalStatus.WAIT_APPROVAL);
        ticket.setRawSql("INSERT INTO foo VALUES (1)");
        when(approvalMapper.listUnFinishTicketIdList()).thenReturn(List.of(TICKET_ID));
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket);
        setupSubmitEvent(ChangeType.DML);

        service.advancePreTickets();

        // Verify auto-approve: updateProcessStatus with SYSTEM context
        ArgumentCaptor<String> contextCaptor = ArgumentCaptor.forClass(String.class);
        verify(approvalStateService).updateProcessStatus(eq(TICKET_ID), eq(ApprovalStage.APPROVAL), eq(ApprovalProcessStatus.FINISH), contextCaptor.capture());
        ApprovalStageMO stageMO = JsonUtils.toObj(contextCaptor.getValue(), ApprovalStageMO.class);
        assertEquals(List.of("SYSTEM"), stageMO.getExecUserName());

        // Verify handler.approvalApproved called
        verify(changeHandler).approvalApproved(eq(TICKET_ID), eq(ApprovalBiz.DM_CHANGE), eq(imSenderService));

        // Verify v2 auto-confirm: confirmTicketBySystemForV2 (not the old confirmTicketBySystem)
        ArgumentCaptor<DmAutoExecConfigFO> configCaptor = ArgumentCaptor.forClass(DmAutoExecConfigFO.class);
        verify(approvalControlService).confirmTicketBySystemForV2(eq(TICKET_ID), configCaptor.capture());
        DmAutoExecConfigFO config = configCaptor.getValue();
        assertTrue("DML should be transactional", config.isEnableTransactional());
        assertEquals("ErrorStrategy must be NONE", com.clougence.clouddm.api.console.autoexec.ErrorStrategy.NONE, config.getErrorStrategy());

        // Verify events
        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper, times(2)).insert(eventCaptor.capture());
        assertEquals(GovEventType.SYSTEM_APPROVE.name(), eventCaptor.getAllValues().get(0).getEventType());
        assertEquals(GovEventType.SYSTEM_CONFIRM.name(), eventCaptor.getAllValues().get(1).getEventType());
        assertEquals("SYSTEM", eventCaptor.getAllValues().get(0).getOperatorUid());
    }

    @Test
    public void advance_v2PreDdl_ddl_advancedWithCorrectConfig() {
        DmApprovalDO ticket = buildTicket(ApprovalBiz.DM_CHANGE, "PRE_DDL", ApprovalStatus.WAIT_APPROVAL);
        ticket.setRawSql("CREATE TABLE foo (id INT)");
        when(approvalMapper.listUnFinishTicketIdList()).thenReturn(List.of(TICKET_ID));
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket);
        setupSubmitEvent(ChangeType.DDL);

        service.advancePreTickets();

        ArgumentCaptor<DmAutoExecConfigFO> configCaptor = ArgumentCaptor.forClass(DmAutoExecConfigFO.class);
        verify(approvalControlService).confirmTicketBySystemForV2(eq(TICKET_ID), configCaptor.capture());
        DmAutoExecConfigFO config = configCaptor.getValue();
        assertFalse("DDL should not be transactional", config.isEnableTransactional());
        assertEquals("ErrorStrategy must be NONE", com.clougence.clouddm.api.console.autoexec.ErrorStrategy.NONE, config.getErrorStrategy());
    }

    @Test
    public void advance_v2PreDdl_mixed_advancedWithCorrectConfig() {
        DmApprovalDO ticket = buildTicket(ApprovalBiz.DM_CHANGE, "PRE_DDL", ApprovalStatus.WAIT_APPROVAL);
        ticket.setRawSql("CREATE TABLE foo; INSERT INTO foo VALUES (1)");
        when(approvalMapper.listUnFinishTicketIdList()).thenReturn(List.of(TICKET_ID));
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket);
        setupSubmitEvent(ChangeType.MIXED);

        service.advancePreTickets();

        ArgumentCaptor<DmAutoExecConfigFO> configCaptor = ArgumentCaptor.forClass(DmAutoExecConfigFO.class);
        verify(approvalControlService).confirmTicketBySystemForV2(eq(TICKET_ID), configCaptor.capture());
        DmAutoExecConfigFO config = configCaptor.getValue();
        assertFalse("MIXED should not be transactional", config.isEnableTransactional());
    }

    @Test
    public void advance_stateMachineMismatchDuringAdvance_noCrash() {
        DmApprovalDO ticket = buildTicket(ApprovalBiz.DM_CHANGE, "PRE_DDL", ApprovalStatus.WAIT_APPROVAL);
        when(approvalMapper.listUnFinishTicketIdList()).thenReturn(List.of(TICKET_ID));
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket);
        setupSubmitEvent(ChangeType.DML);

        // Simulate confirmTicketBySystemForV2 throwing (state changed between advance and confirm)
        doThrow(new RuntimeException("state mismatch"))
            .when(approvalControlService).confirmTicketBySystemForV2(eq(TICKET_ID), any());

        // Should not throw — catches and logs
        service.advancePreTickets();

        // Auto-approve should have happened, auto-confirm failed
        verify(changeHandler).approvalApproved(eq(TICKET_ID), eq(ApprovalBiz.DM_CHANGE), any());
    }

    @Test
    public void advance_nullTicketInfo_skipped() {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setApproBiz(ApprovalBiz.DM_CHANGE);
        ticket.setTicketStatus(ApprovalStatus.WAIT_APPROVAL);
        ticket.setPrimaryUid(PUID);
        ticket.setTicketInfo(null);

        when(approvalMapper.listUnFinishTicketIdList()).thenReturn(List.of(TICKET_ID));
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket);

        service.advancePreTickets();

        verifyNoInteractions(approvalControlService);
    }

    // ======= helpers =======

    /**
     * @param biz         approval biz type
     * @param ticketType  v2 ticket type ("PRE_DDL" / "PROD_DML"); null for non-v2 / skip tests
     * @param status      ticket status
     */
    private DmApprovalDO buildTicket(ApprovalBiz biz, String ticketType, ApprovalStatus status) {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setApproBiz(biz);
        ticket.setTicketStatus(status);
        ticket.setPrimaryUid(PUID);
        ticket.setBizId("biz-001");

        ApprovalMO mo = new ApprovalMO();
        if (ticketType != null) {
            mo.setTicketType(ticketType);
        }
        ticket.setTicketInfo(JsonUtils.toJson(mo));
        return ticket;
    }

    private void setupSubmitEvent(ChangeType changeType) {
        DmDbChangeEventDO event = new DmDbChangeEventDO();
        event.setEventType(GovEventType.SUBMIT.name());
        Map<String, Object> data = new HashMap<>();
        data.put("changeType", changeType.name());
        event.setEventData(JsonUtils.toJson(data));
        when(eventMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(event));
    }
}
