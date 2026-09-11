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
package com.clougence.clouddm.console.web.service.govticket.impl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.component.execute.AutoExecService;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.ExecutionDal;
import com.clougence.clouddm.platform.dal.access.TicketDbStmtDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoJobMapper;
import com.clougence.clouddm.platform.dal.mapper.govticket.DmTicketDbStmtMapper;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.execution.AutoExecJobStatus;
import com.clougence.clouddm.platform.dal.model.execution.DmExecAutoJobDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;
import com.clougence.utils.JsonUtils;

/**
 * Tests for GovTicketV2ServiceImpl.retryGroup acceptance condition matrix:
 * - FAILED: accepted (original behavior)
 * - PENDING with no active job: accepted (inheritance fix)
 * - PENDING with active job: rejected
 * - EXECUTING with failed job: accepted (inheritance fix)
 * - SUCCESS: rejected
 */
public class GovTicketV2RetryGroupTest {

    private GovTicketV2ServiceImpl  service;
    private TicketDbStmtDal          ticketDbStmtDal;
    private DmTicketDbStmtMapper     stmtMapper;
    private ApprovalDal              approvalDal;
    private DmApprovalMapper         approvalMapper;
    private ExecutionDal            execDal;
    private DmExecAutoJobMapper      autoJobMapper;
    private AutoExecService          autoExecService;

    private static final String PUID = "puid-001";
    private static final String UID  = "uid-001";

    @Before
    public void setUp() {
        service = new GovTicketV2ServiceImpl();

        // Set up DmTeamUtils static executionDal (required by nextExecJobBizId)
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

        ticketDbStmtDal = mock(TicketDbStmtDal.class);
        stmtMapper = mock(DmTicketDbStmtMapper.class);
        when(ticketDbStmtDal.stmtMapper()).thenReturn(stmtMapper);

        approvalDal = mock(ApprovalDal.class);
        approvalMapper = mock(DmApprovalMapper.class);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);

        execDal = mock(ExecutionDal.class);
        autoJobMapper = mock(DmExecAutoJobMapper.class);
        when(execDal.autoJobMapper()).thenReturn(autoJobMapper);

        autoExecService = mock(AutoExecService.class);

        ReflectionTestUtils.setField(service, "ticketDbStmtDal", ticketDbStmtDal);
        ReflectionTestUtils.setField(service, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(service, "execDal", execDal);
        ReflectionTestUtils.setField(service, "autoExecService", autoExecService);
    }

    @Test
    public void retryGroup_failedAccepted() {
        long groupId = 100L;
        DmTicketDbStmtDO group = buildGroup(groupId, "FAILED");
        when(stmtMapper.queryById(groupId)).thenReturn(group);

        DmApprovalDO ticket = buildTicket(groupId + 100, PUID, "PRE_DDL");
        when(approvalMapper.queryById(group.getTicketId())).thenReturn(ticket);

        service.retryGroup(PUID, UID, groupId);

        verify(stmtMapper).updateExecStatus(eq(groupId), eq("PENDING"), isNull());
        verify(autoExecService).createGroupJob(eq(group), anyString(), anyBoolean(), any(), anyString(), eq(UID));
    }

    @Test
    public void retryGroup_pendingWithNoActiveJob_accepted() {
        long groupId = 101L;
        DmTicketDbStmtDO group = buildGroup(groupId, "PENDING");
        when(stmtMapper.queryById(groupId)).thenReturn(group);

        DmApprovalDO ticket = buildTicket(groupId + 100, PUID, "PRE_DDL");
        when(approvalMapper.queryById(group.getTicketId())).thenReturn(ticket);
        when(autoJobMapper.queryByDependOnGroupId(groupId)).thenReturn(null);

        service.retryGroup(PUID, UID, groupId);

        verify(stmtMapper).updateExecStatus(eq(groupId), eq("PENDING"), isNull());
    }

    @Test
    public void retryGroup_pendingWithActiveJob_rejected() {
        long groupId = 102L;
        DmTicketDbStmtDO group = buildGroup(groupId, "PENDING");
        when(stmtMapper.queryById(groupId)).thenReturn(group);

        DmExecAutoJobDO activeJob = mock(DmExecAutoJobDO.class);
        when(activeJob.getStatus()).thenReturn(AutoExecJobStatus.EXECUTING);
        when(autoJobMapper.queryByDependOnGroupId(groupId)).thenReturn(activeJob);

        try {
            service.retryGroup(PUID, UID, groupId);
            fail("Expected ErrorMessageException");
        } catch (ErrorMessageException ex) {
            // ok
        }
        verify(stmtMapper, never()).updateExecStatus(anyLong(), anyString(), any());
    }

    @Test
    public void retryGroup_executingWithFailedJob_accepted() {
        long groupId = 103L;
        DmTicketDbStmtDO group = buildGroup(groupId, "EXECUTING");
        when(stmtMapper.queryById(groupId)).thenReturn(group);

        DmApprovalDO ticket = buildTicket(groupId + 100, PUID, "PRE_DDL");
        when(approvalMapper.queryById(group.getTicketId())).thenReturn(ticket);

        DmExecAutoJobDO failedJob = mock(DmExecAutoJobDO.class);
        when(failedJob.getStatus()).thenReturn(AutoExecJobStatus.FAILED);
        when(autoJobMapper.queryByDependOnGroupId(groupId)).thenReturn(failedJob);

        service.retryGroup(PUID, UID, groupId);

        verify(stmtMapper).updateExecStatus(eq(groupId), eq("PENDING"), isNull());
    }

    @Test
    public void retryGroup_successRejected() {
        long groupId = 104L;
        DmTicketDbStmtDO group = buildGroup(groupId, "SUCCESS");
        when(stmtMapper.queryById(groupId)).thenReturn(group);

        try {
            service.retryGroup(PUID, UID, groupId);
            fail("Expected ErrorMessageException");
        } catch (ErrorMessageException ex) {
            // ok
        }
        verify(stmtMapper, never()).updateExecStatus(anyLong(), anyString(), any());
    }

    // ==================== helpers ====================

    private DmTicketDbStmtDO buildGroup(long id, String execStatus) {
        DmTicketDbStmtDO group = new DmTicketDbStmtDO();
        group.setId(id);
        group.setTicketId(id + 100);
        group.setPairId(300L);
        group.setDsId(20L);
        group.setDbName("pre_db");
        group.setSqlContent("CREATE TABLE t(id int);");
        group.setExecStatus(execStatus);
        return group;
    }

    private DmApprovalDO buildTicket(long id, String primaryUid, String ticketType) {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(id);
        ticket.setPrimaryUid(primaryUid);
        ticket.setBizId("biz-" + id);
        ApprovalMO mo = new ApprovalMO();
        mo.setTicketType(ticketType);
        ticket.setTicketInfo(JsonUtils.toJson(mo));
        return ticket;
    }
}
