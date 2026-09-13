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
import com.clougence.clouddm.console.web.model.fo.ticket.DmAutoExecConfigFO;
import com.clougence.clouddm.console.web.service.approval.ApprovalControlService;
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
import com.clougence.clouddm.platform.dal.model.dbchange.GovEventType;
import com.clougence.utils.JsonUtils;

/**
 * Tests that resolveChangeType reads the LATEST SUBMIT event (not the first),
 * so a resubmit that changes the SQL type (e.g. DML -> DDL) drives the correct
 * execution config (autocommit=false for DDL).
 */
public class GovAutoAdvanceResolveChangeTypeTest {

    private GovAutoAdvanceServiceImpl  impl;
    private ApprovalDal                approvalDal;
    private DmApprovalMapper           approvalMapper;
    private DbChangeEventDal          dbChangeEventDal;
    private DmDbChangeEventMapper       eventMapper;
    private ApprovalControlService     approvalControlService;
    private TicketDbStmtDal           ticketDbStmtDal;
    private DmTicketDbStmtMapper        stmtMapper;
    private ApprovalStateService       approvalStateService;
    private ImSenderService           imSenderService;
    private AutoExecService           autoExecService;

    private static final String PUID = "puid-001";
    private static final long   TICKET_ID = 200L;

    @Before
    public void setUp() {
        approvalDal = mock(ApprovalDal.class);
        dbChangeEventDal = mock(DbChangeEventDal.class);
        approvalStateService = mock(ApprovalStateService.class);
        approvalControlService = mock(ApprovalControlService.class);
        imSenderService = mock(ImSenderService.class);
        ApprovalHandler changeHandler = mock(ApprovalHandler.class);
        when(changeHandler.handleType()).thenReturn(ApprovalBiz.DM_CHANGE);
        ticketDbStmtDal = mock(TicketDbStmtDal.class);
        stmtMapper = mock(DmTicketDbStmtMapper.class);
        autoExecService = mock(AutoExecService.class);

        approvalMapper = mock(DmApprovalMapper.class);
        eventMapper = mock(DmDbChangeEventMapper.class);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);
        when(dbChangeEventDal.eventMapper()).thenReturn(eventMapper);
        when(ticketDbStmtDal.stmtMapper()).thenReturn(stmtMapper);
        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(java.util.Collections.emptyList());

        impl = new GovAutoAdvanceServiceImpl(List.of(changeHandler));
        ReflectionTestUtils.setField(impl, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(impl, "dbChangeEventDal", dbChangeEventDal);
        ReflectionTestUtils.setField(impl, "approvalStateService", approvalStateService);
        ReflectionTestUtils.setField(impl, "approvalControlService", approvalControlService);
        ReflectionTestUtils.setField(impl, "imSenderService", imSenderService);
        ReflectionTestUtils.setField(impl, "ticketDbStmtDal", ticketDbStmtDal);
        ReflectionTestUtils.setField(impl, "autoExecService", autoExecService);
        ReflectionTestUtils.setField(impl, "schemaSnapshotService",
            mock(com.clougence.clouddm.console.web.component.governance.SchemaSnapshotService.class));
    }

    /**
     * Two SUBMIT events: first DML, second DDL (resubmit changed the SQL type).
     * resolveChangeType should read the LATEST (DDL) and pass it to buildAutoExecConfig
     * → enableTransactional=false (autocommit) for DDL.
     */
    @Test
    public void resolveChangeType_latestSubmitEventWins() {
        DmApprovalDO ticket = buildTicket(ApprovalBiz.DM_CHANGE, "PRE_DDL", ApprovalStatus.WAIT_APPROVAL);
        when(approvalMapper.listUnFinishTicketIdList()).thenReturn(List.of(TICKET_ID));
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket);

        // Two SUBMIT events: first DML (id=1), second DDL (id=2)
        DmDbChangeEventDO oldSubmit = buildSubmitEvent(1L, ChangeType.DML);
        DmDbChangeEventDO newSubmit = buildSubmitEvent(2L, ChangeType.DDL);
        when(eventMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(oldSubmit, newSubmit));

        // No groups to create jobs for (empty list)
        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(java.util.Collections.emptyList());

        impl.advancePreTickets();

        // Verify the SYSTEM_CONFIRM event carries the LATEST changeType (DDL)
        ArgumentCaptor<DmDbChangeEventDO> captor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper, atLeastOnce()).insert(captor.capture());
        DmDbChangeEventDO confirmEvent = captor.getAllValues().stream()
            .filter(e -> GovEventType.SYSTEM_CONFIRM.name().equals(e.getEventType()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("SYSTEM_CONFIRM event not found"));
        Map<String, Object> data = JsonUtils.toObj(confirmEvent.getEventData(), HashMap.class);
        assertEquals("DDL", data.get("changeType"));

        // Verify confirmTicketBySystemForV2 was called with enableTransactional=false (DDL config)
        ArgumentCaptor<DmAutoExecConfigFO> configCaptor = ArgumentCaptor.forClass(DmAutoExecConfigFO.class);
        verify(approvalControlService).confirmTicketBySystemForV2(eq(TICKET_ID), configCaptor.capture());
        assertFalse("DDL should have enableTransactional=false (autocommit)",
            configCaptor.getValue().isEnableTransactional());
    }

    /**
     * Legacy fallback: if no SUBMIT event carries changeType, derive from precheck results.
     * This test ensures the legacy path still works after the latest-first change.
     */
    @Test
    public void resolveChangeType_legacyFallback_fromPrecheckResults() {
        DmApprovalDO ticket = buildTicket(ApprovalBiz.DM_CHANGE, "PRE_DDL", ApprovalStatus.WAIT_APPROVAL);
        when(approvalMapper.listUnFinishTicketIdList()).thenReturn(List.of(TICKET_ID));
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket);

        // One SUBMIT event without changeType (legacy)
        DmDbChangeEventDO legacySubmit = new DmDbChangeEventDO();
        legacySubmit.setId(1L);
        legacySubmit.setEventType(GovEventType.SUBMIT.name());
        legacySubmit.setEventData(JsonUtils.toJson(Map.of("ticketType", "PRE_DDL", "groupCount", 1)));
        when(eventMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(legacySubmit));

        // Group precheck carries changeType
        com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO stmt
            = new com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO();
        stmt.setPrecheckResult(JsonUtils.toJson(Map.of("changeType", "DML")));
        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(stmt));

        impl.advancePreTickets();

        // Verify the fallback produced DML -> enableTransactional=true
        ArgumentCaptor<DmAutoExecConfigFO> configCaptor = ArgumentCaptor.forClass(DmAutoExecConfigFO.class);
        verify(approvalControlService).confirmTicketBySystemForV2(eq(TICKET_ID), configCaptor.capture());
        assertTrue("DML legacy fallback should have enableTransactional=true",
            configCaptor.getValue().isEnableTransactional());
    }

    // ==================== helpers ====================

    private DmApprovalDO buildTicket(ApprovalBiz biz, String ticketType, ApprovalStatus status) {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setBizId("biz-" + TICKET_ID);
        ticket.setPrimaryUid(PUID);
        ticket.setApproBiz(biz);
        ticket.setTicketStatus(status);
        ApprovalMO mo = new ApprovalMO();
        mo.setTicketType(ticketType);
        mo.setPairIds(List.of(300L));
        ticket.setTicketInfo(JsonUtils.toJson(mo));
        return ticket;
    }

    private DmDbChangeEventDO buildSubmitEvent(long id, ChangeType changeType) {
        DmDbChangeEventDO event = new DmDbChangeEventDO();
        event.setId(id);
        event.setEventType(GovEventType.SUBMIT.name());
        Map<String, Object> data = new HashMap<>();
        data.put("ticketType", "PRE_DDL");
        data.put("groupCount", 1);
        data.put("changeType", changeType.name());
        event.setEventData(JsonUtils.toJson(data));
        return event;
    }
}
