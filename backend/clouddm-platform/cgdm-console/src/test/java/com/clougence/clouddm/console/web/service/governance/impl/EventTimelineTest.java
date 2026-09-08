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

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.component.auth.DmAuthServiceForBiz;
import com.clougence.clouddm.console.web.component.dsconfig.DmDsConfigService;
import com.clougence.clouddm.console.web.component.governance.GovStmtSplitService;
import com.clougence.clouddm.console.web.model.fo.governance.GovEventTimelineFO;
import com.clougence.clouddm.console.web.model.vo.governance.PromotionDetailVO;
import com.clougence.clouddm.console.web.service.approval.ApprovalControlService;
import com.clougence.clouddm.console.web.service.logicaldb.LogicalDbService;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.DbChangeGovernDal;
import com.clougence.clouddm.platform.dal.access.ExecutionDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangeEventMapper;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO;
import com.clougence.clouddm.platform.dal.model.dbchange.GovEventType;
import com.clougence.utils.JsonUtils;

public class EventTimelineTest {

    private DbChangeGovernServiceImpl service;

    private ApprovalDal          approvalDal;
    private DmApprovalMapper     approvalMapper;
    private DbChangeGovernDal   dbChangeGovernDal;
    private DmDbChangeEventMapper eventMapper;

    private static final long   TICKET_ID = 300L;

    @Before
    public void setUp() {
        approvalDal = mock(ApprovalDal.class);
        dbChangeGovernDal = mock(DbChangeGovernDal.class);
        approvalMapper = mock(DmApprovalMapper.class);
        eventMapper = mock(DmDbChangeEventMapper.class);

        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);
        when(dbChangeGovernDal.eventMapper()).thenReturn(eventMapper);

        service = new DbChangeGovernServiceImpl();
        ReflectionTestUtils.setField(service, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(service, "dbChangeGovernDal", dbChangeGovernDal);
        ReflectionTestUtils.setField(service, "executionDal", mock(ExecutionDal.class));
        ReflectionTestUtils.setField(service, "logicalDbService", mock(LogicalDbService.class));
        ReflectionTestUtils.setField(service, "dmAuthServiceForBiz", mock(DmAuthServiceForBiz.class));
        ReflectionTestUtils.setField(service, "dmDsConfigService", mock(DmDsConfigService.class));
        ReflectionTestUtils.setField(service, "govStmtSplitService", mock(GovStmtSplitService.class));
        ReflectionTestUtils.setField(service, "approvalControlService", mock(ApprovalControlService.class));
    }

    @Test
    public void eventTimeline_governanceTicket_returnsEvents() {
        setupGovernanceTicket("PRE");
        DmDbChangeEventDO e1 = buildEvent(1L, GovEventType.SUBMIT, null, "PRE_INIT_WAIT", "uid-001", new Date(1000));
        DmDbChangeEventDO e2 = buildEvent(2L, GovEventType.SYSTEM_APPROVE, "WAIT_APPROVAL", "WAIT_CONFIRM", "SYSTEM", new Date(2000));
        DmDbChangeEventDO e3 = buildEvent(3L, GovEventType.CORRECTION, null, null, "uid-001", new Date(3000));
        when(eventMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(e1, e2, e3));

        GovEventTimelineFO fo = new GovEventTimelineFO();
        fo.setTicketId(TICKET_ID);

        List<PromotionDetailVO.EventHandlerVO> result = service.eventTimeline("puid", "uid", fo);

        assertEquals(3, result.size());
        // Sorted by gmtCreate ascending
        assertEquals(GovEventType.SUBMIT.name(), result.get(0).getEventType());
        assertEquals("PRE_INIT_WAIT", result.get(0).getToStatus());
        assertEquals(GovEventType.SYSTEM_APPROVE.name(), result.get(1).getEventType());
        assertEquals("SYSTEM", result.get(1).getOperatorUid());
        assertEquals(GovEventType.CORRECTION.name(), result.get(2).getEventType());
    }

    @Test
    public void eventTimeline_nonGovernanceTicket_returnsEmpty() {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setApproBiz(ApprovalBiz.DM_CHANGE);
        // ticketInfo has no govRole
        ApprovalMO mo = new ApprovalMO();
        ticket.setTicketInfo(JsonUtils.toJson(mo));
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket);

        GovEventTimelineFO fo = new GovEventTimelineFO();
        fo.setTicketId(TICKET_ID);

        List<PromotionDetailVO.EventHandlerVO> result = service.eventTimeline("puid", "uid", fo);

        assertNotNull(result);
        assertTrue(result.isEmpty());
        verify(eventMapper, never()).queryByTicketId(any());
    }

    @Test
    public void eventTimeline_ticketNotFound_returnsEmpty() {
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(null);

        GovEventTimelineFO fo = new GovEventTimelineFO();
        fo.setTicketId(TICKET_ID);

        List<PromotionDetailVO.EventHandlerVO> result = service.eventTimeline("puid", "uid", fo);

        assertNotNull(result);
        assertTrue(result.isEmpty());
        verify(eventMapper, never()).queryByTicketId(any());
    }

    @Test
    public void eventTimeline_nullTicketInfo_returnsEmpty() {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setApproBiz(ApprovalBiz.DM_CHANGE);
        ticket.setTicketInfo(null);
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket);

        GovEventTimelineFO fo = new GovEventTimelineFO();
        fo.setTicketId(TICKET_ID);

        List<PromotionDetailVO.EventHandlerVO> result = service.eventTimeline("puid", "uid", fo);

        assertNotNull(result);
        assertTrue(result.isEmpty());
        verify(eventMapper, never()).queryByTicketId(any());
    }

    @Test
    public void eventTimeline_noEvents_returnsEmptyList() {
        setupGovernanceTicket("PROD");
        when(eventMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of());

        GovEventTimelineFO fo = new GovEventTimelineFO();
        fo.setTicketId(TICKET_ID);

        List<PromotionDetailVO.EventHandlerVO> result = service.eventTimeline("puid", "uid", fo);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // --- helpers ---

    private void setupGovernanceTicket(String govRole) {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setApproBiz(ApprovalBiz.DM_CHANGE);
        ApprovalMO mo = new ApprovalMO();
        mo.setGovRole(govRole);
        ticket.setTicketInfo(JsonUtils.toJson(mo));
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket);
    }

    private DmDbChangeEventDO buildEvent(long id, GovEventType type, String fromStatus, String toStatus,
                                         String operatorUid, Date gmtCreate) {
        DmDbChangeEventDO event = new DmDbChangeEventDO();
        event.setId(id);
        event.setTicketId(TICKET_ID);
        event.setEventType(type.name());
        event.setFromStatus(fromStatus);
        event.setToStatus(toStatus);
        event.setOperatorUid(operatorUid);
        event.setGmtCreate(gmtCreate);
        Map<String, Object> data = new HashMap<>();
        data.put("key", "value");
        event.setEventData(JsonUtils.toJson(data));
        return event;
    }
}
