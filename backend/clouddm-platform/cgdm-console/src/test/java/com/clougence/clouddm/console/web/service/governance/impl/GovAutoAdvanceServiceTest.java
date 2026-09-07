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
import com.clougence.clouddm.console.web.model.fo.ticket.DmAutoExecConfigFO;
import com.clougence.clouddm.console.web.model.vo.envparam.DmEnvParamTicketDesVO;
import com.clougence.clouddm.console.web.model.vo.logicaldb.LogicalDbTarget;
import com.clougence.clouddm.console.web.service.approval.ApprovalControlService;
import com.clougence.clouddm.console.web.service.envparam.DmEnvParamService;
import com.clougence.clouddm.console.web.service.governance.GovAutoAdvanceService;
import com.clougence.clouddm.console.web.service.logicaldb.LogicalDbService;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.DbChangeGovernDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangeEventMapper;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalProcessStatus;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStage;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalType;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.dbchange.ChangeType;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO;
import com.clougence.clouddm.platform.dal.model.dbchange.GovEventType;
import com.clougence.clouddm.platform.dal.model.logicaldb.GovRole;
import com.clougence.utils.JsonUtils;

public class GovAutoAdvanceServiceTest {

    private GovAutoAdvanceService     service;

    private ApprovalDal               approvalDal;
    private DmApprovalMapper          approvalMapper;
    private DbChangeGovernDal        dbChangeGovernDal;
    private DmDbChangeEventMapper     eventMapper;
    private LogicalDbService          logicalDbService;
    private DmEnvParamService         dmEnvParamService;
    private ApprovalStateService      approvalStateService;
    private ApprovalControlService    approvalControlService;
    private ImSenderService           imSenderService;
    private ApprovalHandler           changeHandler;

    private static final String       PUID        = "puid-001";
    private static final long         TICKET_ID   = 100L;
    private static final long         LOGICAL_DB_ID = 10L;

    @Before
    public void setUp() {
        approvalDal = mock(ApprovalDal.class);
        dbChangeGovernDal = mock(DbChangeGovernDal.class);
        logicalDbService = mock(LogicalDbService.class);
        dmEnvParamService = mock(DmEnvParamService.class);
        approvalStateService = mock(ApprovalStateService.class);
        approvalControlService = mock(ApprovalControlService.class);
        imSenderService = mock(ImSenderService.class);
        changeHandler = mock(ApprovalHandler.class);

        approvalMapper = mock(DmApprovalMapper.class);
        eventMapper = mock(DmDbChangeEventMapper.class);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);
        when(dbChangeGovernDal.eventMapper()).thenReturn(eventMapper);
        when(changeHandler.handleType()).thenReturn(ApprovalBiz.DM_CHANGE);

        impl = new GovAutoAdvanceServiceImpl(List.of(changeHandler));
        ReflectionTestUtils.setField(impl, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(impl, "dbChangeGovernDal", dbChangeGovernDal);
        ReflectionTestUtils.setField(impl, "logicalDbService", logicalDbService);
        ReflectionTestUtils.setField(impl, "dmEnvParamService", dmEnvParamService);
        ReflectionTestUtils.setField(impl, "approvalStateService", approvalStateService);
        ReflectionTestUtils.setField(impl, "approvalControlService", approvalControlService);
        ReflectionTestUtils.setField(impl, "imSenderService", imSenderService);

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

        verify(dbChangeGovernDal, never()).eventMapper();
        verifyNoInteractions(logicalDbService);
        verifyNoInteractions(approvalControlService);
    }

    @Test
    public void advance_governanceButNotWaitApproval_skipped() {
        DmApprovalDO ticket = buildTicket(ApprovalBiz.DM_CHANGE, GovRole.PRE.name(), ApprovalStatus.RUNNING);
        when(approvalMapper.listUnFinishTicketIdList()).thenReturn(List.of(TICKET_ID));
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket);

        service.advancePreTickets();

        verifyNoInteractions(logicalDbService);
        verifyNoInteractions(approvalControlService);
    }

    @Test
    public void advance_governanceWithExternalTemplate_skipped() {
        DmApprovalDO ticket = buildTicket(ApprovalBiz.DM_CHANGE, GovRole.PRE.name(), ApprovalStatus.WAIT_APPROVAL);
        when(approvalMapper.listUnFinishTicketIdList()).thenReturn(List.of(TICKET_ID));
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket);
        setupBinding(LogicalDbTarget());
        setupTemplate("DingTalk");

        service.advancePreTickets();

        verifyNoInteractions(approvalControlService);
    }

    @Test
    public void advance_internalTemplate_dml_advancedWithCorrectConfig() {
        DmApprovalDO ticket = buildTicket(ApprovalBiz.DM_CHANGE, GovRole.PRE.name(), ApprovalStatus.WAIT_APPROVAL);
        ticket.setRawSql("INSERT INTO foo VALUES (1)");
        when(approvalMapper.listUnFinishTicketIdList()).thenReturn(List.of(TICKET_ID));
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket);
        setupBinding(LogicalDbTarget());
        setupTemplate(ApprovalType.Internal.name());
        setupSubmitEvent(ChangeType.DML);

        service.advancePreTickets();

        // Verify auto-approve: updateProcessStatus with SYSTEM context
        ArgumentCaptor<String> contextCaptor = ArgumentCaptor.forClass(String.class);
        verify(approvalStateService).updateProcessStatus(eq(TICKET_ID), eq(ApprovalStage.APPROVAL), eq(ApprovalProcessStatus.FINISH), contextCaptor.capture());
        ApprovalStageMO stageMO = JsonUtils.toObj(contextCaptor.getValue(), ApprovalStageMO.class);
        assertEquals(List.of("SYSTEM"), stageMO.getExecUserName());

        // Verify handler.approvalApproved called
        verify(changeHandler).approvalApproved(eq(TICKET_ID), eq(ApprovalBiz.DM_CHANGE), eq(imSenderService));

        // Verify auto-confirm: config D15 routing for DML
        ArgumentCaptor<DmAutoExecConfigFO> configCaptor = ArgumentCaptor.forClass(DmAutoExecConfigFO.class);
        verify(approvalControlService).confirmTicketBySystem(eq(TICKET_ID), configCaptor.capture());
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
    public void advance_internalTemplate_ddl_advancedWithCorrectConfig() {
        DmApprovalDO ticket = buildTicket(ApprovalBiz.DM_CHANGE, GovRole.PRE.name(), ApprovalStatus.WAIT_APPROVAL);
        ticket.setRawSql("CREATE TABLE foo (id INT)");
        when(approvalMapper.listUnFinishTicketIdList()).thenReturn(List.of(TICKET_ID));
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket);
        setupBinding(LogicalDbTarget());
        setupTemplate(ApprovalType.Internal.name());
        setupSubmitEvent(ChangeType.DDL);

        service.advancePreTickets();

        ArgumentCaptor<DmAutoExecConfigFO> configCaptor = ArgumentCaptor.forClass(DmAutoExecConfigFO.class);
        verify(approvalControlService).confirmTicketBySystem(eq(TICKET_ID), configCaptor.capture());
        DmAutoExecConfigFO config = configCaptor.getValue();
        assertFalse("DDL should not be transactional", config.isEnableTransactional());
        assertEquals("ErrorStrategy must be NONE", com.clougence.clouddm.api.console.autoexec.ErrorStrategy.NONE, config.getErrorStrategy());
    }

    @Test
    public void advance_internalTemplate_mixed_advancedWithCorrectConfig() {
        DmApprovalDO ticket = buildTicket(ApprovalBiz.DM_CHANGE, GovRole.PRE.name(), ApprovalStatus.WAIT_APPROVAL);
        ticket.setRawSql("CREATE TABLE foo; INSERT INTO foo VALUES (1)");
        when(approvalMapper.listUnFinishTicketIdList()).thenReturn(List.of(TICKET_ID));
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket);
        setupBinding(LogicalDbTarget());
        setupTemplate(ApprovalType.Internal.name());
        setupSubmitEvent(ChangeType.MIXED);

        service.advancePreTickets();

        ArgumentCaptor<DmAutoExecConfigFO> configCaptor = ArgumentCaptor.forClass(DmAutoExecConfigFO.class);
        verify(approvalControlService).confirmTicketBySystem(eq(TICKET_ID), configCaptor.capture());
        DmAutoExecConfigFO config = configCaptor.getValue();
        assertFalse("MIXED should not be transactional", config.isEnableTransactional());
    }

    @Test
    public void advance_stateMachineMismatchDuringAdvance_noCrash() {
        DmApprovalDO ticket = buildTicket(ApprovalBiz.DM_CHANGE, GovRole.PRE.name(), ApprovalStatus.WAIT_APPROVAL);
        when(approvalMapper.listUnFinishTicketIdList()).thenReturn(List.of(TICKET_ID));
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket);
        setupBinding(LogicalDbTarget());
        setupTemplate(ApprovalType.Internal.name());
        setupSubmitEvent(ChangeType.DML);

        // Simulate confirmTicketBySystem throwing (state changed between advance and confirm)
        doThrow(new RuntimeException("state mismatch"))
            .when(approvalControlService).confirmTicketBySystem(eq(TICKET_ID), any());

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

        verifyNoInteractions(logicalDbService);
    }

    // ======= helpers =======

    private DmApprovalDO buildTicket(ApprovalBiz biz, String govRole, ApprovalStatus status) {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setApproBiz(biz);
        ticket.setTicketStatus(status);
        ticket.setPrimaryUid(PUID);
        ticket.setBizId("biz-001");

        ApprovalMO mo = new ApprovalMO();
        if (govRole != null) {
            mo.setLogicalDbId(LOGICAL_DB_ID);
            mo.setGovRole(govRole);
        }
        ticket.setTicketInfo(JsonUtils.toJson(mo));
        return ticket;
    }

    private LogicalDbTarget LogicalDbTarget() {
        LogicalDbTarget target = new LogicalDbTarget();
        target.setBindingId(1L);
        target.setLogicalDbId(LOGICAL_DB_ID);
        target.setEnvId(5L);
        target.setDsId(20L);
        target.setResPath("/mydb/");
        target.setGovRole(GovRole.PRE);
        return target;
    }

    private void setupBinding(LogicalDbTarget target) {
        when(logicalDbService.getBinding(PUID, LOGICAL_DB_ID, GovRole.PRE)).thenReturn(target);
    }

    private void setupTemplate(String type) {
        DmEnvParamTicketDesVO vo = DmEnvParamTicketDesVO.builder()
            .openTicket(true)
            .type(type)
            .build();
        when(dmEnvParamService.querySqlTicketInfoParam(PUID, 5L)).thenReturn(vo);
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
