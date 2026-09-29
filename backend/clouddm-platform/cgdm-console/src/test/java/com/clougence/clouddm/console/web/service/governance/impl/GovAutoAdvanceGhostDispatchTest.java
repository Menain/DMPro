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
import com.clougence.clouddm.console.web.component.cicd.ImSenderService;
import com.clougence.clouddm.console.web.component.execute.AutoExecService;
import com.clougence.clouddm.console.web.service.approval.ApprovalControlService;
import com.clougence.clouddm.console.web.service.govticket.GovTicketV2Service;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.DbChangeEventDal;
import com.clougence.clouddm.platform.dal.access.TicketDbStmtDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangeEventMapper;
import com.clougence.clouddm.platform.dal.mapper.govticket.DmTicketDbStmtMapper;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.dbchange.ChangeType;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;
import com.clougence.utils.JsonUtils;

/**
 * B6 M1 (Step 7a): dispatch site #1 — the SYSTEM auto-advance per-group loop early-exits a
 * ghost-marked group into {@code dispatchGhostGroup} (positional flag mapping, per-group
 * isolation of failures) while unmarked groups keep the original createGroupJob/startJob path
 * byte-for-byte (the pinned legacy matrix lives in GovAutoAdvanceServiceTest, untouched).
 */
public class GovAutoAdvanceGhostDispatchTest {

    private GovAutoAdvanceServiceImpl impl;
    private ApprovalDal               approvalDal;
    private DmApprovalMapper          approvalMapper;
    private DbChangeEventDal          dbChangeEventDal;
    private DmDbChangeEventMapper     eventMapper;
    private ApprovalStateService      approvalStateService;
    private ApprovalControlService    approvalControlService;
    private TicketDbStmtDal           ticketDbStmtDal;
    private DmTicketDbStmtMapper      stmtMapper;
    private AutoExecService           autoExecService;
    private GovTicketV2Service        govTicketV2Service;

    private static final String PUID      = "puid-001";
    private static final long   TICKET_ID = 100L;

    @Before
    public void setUp() {
        // DmTeamUtils static executionDal (required by nextExecJobBizId inside the group loop)
        com.clougence.clouddm.platform.dal.access.ExecutionDal staticExecDal
            = mock(com.clougence.clouddm.platform.dal.access.ExecutionDal.class);
        com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoJobMapper staticJobMapper
            = mock(com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoJobMapper.class);
        com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoTaskMapper staticTaskMapper
            = mock(com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoTaskMapper.class);
        when(staticExecDal.autoJobMapper()).thenReturn(staticJobMapper);
        when(staticExecDal.autoTaskMapper()).thenReturn(staticTaskMapper);
        when(staticJobMapper.queryByBizId(anyString())).thenReturn(null);
        when(staticTaskMapper.queryByBizId(anyString())).thenReturn(null);
        ReflectionTestUtils.setField(com.clougence.clouddm.console.web.util.DmTeamUtils.class, "executionDal", staticExecDal);

        approvalDal = mock(ApprovalDal.class);
        dbChangeEventDal = mock(DbChangeEventDal.class);
        approvalStateService = mock(ApprovalStateService.class);
        approvalControlService = mock(ApprovalControlService.class);
        ticketDbStmtDal = mock(TicketDbStmtDal.class);
        stmtMapper = mock(DmTicketDbStmtMapper.class);
        autoExecService = mock(AutoExecService.class);
        govTicketV2Service = mock(GovTicketV2Service.class);

        approvalMapper = mock(DmApprovalMapper.class);
        eventMapper = mock(DmDbChangeEventMapper.class);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);
        when(dbChangeEventDal.eventMapper()).thenReturn(eventMapper);
        when(ticketDbStmtDal.stmtMapper()).thenReturn(stmtMapper);

        ApprovalHandler changeHandler = mock(ApprovalHandler.class);
        when(changeHandler.handleType()).thenReturn(ApprovalBiz.DM_CHANGE);

        impl = new GovAutoAdvanceServiceImpl(List.of(changeHandler));
        ReflectionTestUtils.setField(impl, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(impl, "dbChangeEventDal", dbChangeEventDal);
        ReflectionTestUtils.setField(impl, "approvalStateService", approvalStateService);
        ReflectionTestUtils.setField(impl, "approvalControlService", approvalControlService);
        ReflectionTestUtils.setField(impl, "imSenderService", mock(ImSenderService.class));
        ReflectionTestUtils.setField(impl, "ticketDbStmtDal", ticketDbStmtDal);
        ReflectionTestUtils.setField(impl, "autoExecService", autoExecService);
        ReflectionTestUtils.setField(impl, "schemaSnapshotService",
            mock(com.clougence.clouddm.console.web.component.governance.SchemaSnapshotService.class));
        ReflectionTestUtils.setField(impl, "govTicketV2Service", govTicketV2Service);

        when(approvalMapper.listUnFinishTicketIdList()).thenReturn(List.of(TICKET_ID));
        when(approvalMapper.queryById(TICKET_ID)).thenAnswer(inv -> ticket(null));
        setupSubmitEvent(ChangeType.DDL);
    }

    @Test
    public void advance_ghostMarkedGroup_dispatchesThroughGhostService_noAutoexecJob() {
        DmApprovalDO ticket = ticket(List.of(true));
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket);
        DmTicketDbStmtDO group = group(200L, "PENDING");
        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(group));

        impl.advancePreTickets();

        verify(govTicketV2Service).dispatchGhostGroup(eq(ticket), eq(group),
            eq(GovTicketV2Service.GHOST_VIA_AUTO_ROUTE));
        verify(autoExecService, never()).createGroupJob(any(), anyString(), anyBoolean(), any(), anyString(), anyString());
        verify(autoExecService, never()).startJob(anyString(), anyString());
        // the loop-tail PREPARATION close still fires once for the ticket
        verify(approvalStateService).markExecutionDispatched("biz-001");
    }

    @Test
    public void advance_mixedFlags_positionalMappingPerGroup() {
        DmApprovalDO ticket = ticket(List.of(false, true));
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket);
        DmTicketDbStmtDO plain = group(200L, "PENDING");
        DmTicketDbStmtDO ghost = group(201L, "PENDING");
        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(plain, ghost));

        impl.advancePreTickets();

        // position 0 → autoexec job; position 1 → ghost dispatch (id-ASC positional contract)
        verify(autoExecService).createGroupJob(eq(plain), anyString(), anyBoolean(), any(), anyString(), eq("SYSTEM"));
        verify(autoExecService).startJob(anyString(), eq("SYSTEM"));
        verify(govTicketV2Service).dispatchGhostGroup(eq(ticket), eq(ghost), eq(GovTicketV2Service.GHOST_VIA_AUTO_ROUTE));
    }

    @Test
    public void advance_noFlags_legacyPathUntouched_regressionPin() {
        DmApprovalDO ticket = ticket(null);
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket);
        DmTicketDbStmtDO group = group(200L, "PENDING");
        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(group));

        impl.advancePreTickets();

        verifyNoInteractions(govTicketV2Service);
        verify(autoExecService).createGroupJob(eq(group), anyString(), anyBoolean(), any(), anyString(), eq("SYSTEM"));
    }

    @Test
    public void advance_falseFlag_legacyPathUntouched() {
        DmApprovalDO ticket = ticket(List.of(false));
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket);
        DmTicketDbStmtDO group = group(200L, "PENDING");
        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(group));

        impl.advancePreTickets();

        verifyNoInteractions(govTicketV2Service);
        verify(autoExecService).createGroupJob(eq(group), anyString(), anyBoolean(), any(), anyString(), eq("SYSTEM"));
    }

    @Test
    public void advance_ghostGroupNotEligible_skippedLikeLegacy() {
        DmApprovalDO ticket = ticket(List.of(true));
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket);
        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(group(200L, "SUCCESS")));

        impl.advancePreTickets();

        verifyNoInteractions(govTicketV2Service);
        verify(autoExecService, never()).createGroupJob(any(), anyString(), anyBoolean(), any(), anyString(), anyString());
    }

    @Test
    public void advance_ghostDispatchFailure_isolatedPerGroup_loopContinues() {
        DmApprovalDO ticket = ticket(List.of(true, false));
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket);
        DmTicketDbStmtDO ghost = group(200L, "PENDING");
        DmTicketDbStmtDO plain = group(201L, "PENDING");
        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(ghost, plain));
        doThrow(new RuntimeException("sidecar down")).when(govTicketV2Service)
            .dispatchGhostGroup(any(), any(), anyString());

        impl.advancePreTickets(); // must not throw (per-group try-catch, existing shape)

        // the failure of the ghost group never blocks the plain group's job creation
        verify(autoExecService).createGroupJob(eq(plain), anyString(), anyBoolean(), any(), anyString(), eq("SYSTEM"));
        verify(approvalStateService).markExecutionDispatched("biz-001");
    }

    // ======= helpers =======

    private DmApprovalDO ticket(List<Boolean> ghostFlags) {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setApproBiz(ApprovalBiz.DM_CHANGE);
        ticket.setTicketStatus(ApprovalStatus.WAIT_APPROVAL);
        ticket.setPrimaryUid(PUID);
        ticket.setBizId("biz-001");
        ApprovalMO mo = new ApprovalMO();
        mo.setTicketType("PRE_DDL");
        mo.setGhostRouteFlags(ghostFlags);
        ticket.setTicketInfo(JsonUtils.toJson(mo));
        return ticket;
    }

    private static DmTicketDbStmtDO group(long id, String execStatus) {
        DmTicketDbStmtDO group = new DmTicketDbStmtDO();
        group.setId(id);
        group.setTicketId(TICKET_ID);
        group.setExecStatus(execStatus);
        group.setSqlContent("ALTER TABLE t_big ADD COLUMN c1 INT");
        return group;
    }

    private void setupSubmitEvent(ChangeType changeType) {
        DmDbChangeEventDO event = new DmDbChangeEventDO();
        event.setEventType(com.clougence.clouddm.platform.dal.model.dbchange.GovEventType.SUBMIT.name());
        Map<String, Object> data = new HashMap<>();
        data.put("changeType", changeType.name());
        event.setEventData(JsonUtils.toJson(data));
        when(eventMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(event));
    }
}
