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

import java.util.List;

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
    private com.clougence.clouddm.console.web.component.approval.ApprovalStateService approvalStateService;

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
        // Sub-task A: schema mapper for snapshot capture
        com.clougence.clouddm.platform.dal.mapper.govticket.DmTicketStmtSchemaMapper ticketSchemaMapper
            = mock(com.clougence.clouddm.platform.dal.mapper.govticket.DmTicketStmtSchemaMapper.class);
        when(ticketDbStmtDal.schemaMapper()).thenReturn(ticketSchemaMapper);

        // Sub-task A: snapshot service (no-op mock)
        com.clougence.clouddm.console.web.component.governance.SchemaSnapshotService schemaSnapshotService
            = mock(com.clougence.clouddm.console.web.component.governance.SchemaSnapshotService.class);
        // Mock DbPairDal for snapshot service's pair resolution
        com.clougence.clouddm.platform.dal.access.DbPairDal dbPairDal
            = mock(com.clougence.clouddm.platform.dal.access.DbPairDal.class);
        com.clougence.clouddm.platform.dal.mapper.dbpair.DmDbPairMapper pairMapper
            = mock(com.clougence.clouddm.platform.dal.mapper.dbpair.DmDbPairMapper.class);
        when(dbPairDal.pairMapper()).thenReturn(pairMapper);

        approvalDal = mock(ApprovalDal.class);
        approvalMapper = mock(DmApprovalMapper.class);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);

        execDal = mock(ExecutionDal.class);
        autoJobMapper = mock(DmExecAutoJobMapper.class);
        when(execDal.autoJobMapper()).thenReturn(autoJobMapper);

        autoExecService = mock(AutoExecService.class);

        approvalStateService = mock(com.clougence.clouddm.console.web.component.approval.ApprovalStateService.class);

        ReflectionTestUtils.setField(service, "ticketDbStmtDal", ticketDbStmtDal);
        ReflectionTestUtils.setField(service, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(service, "execDal", execDal);
        ReflectionTestUtils.setField(service, "autoExecService", autoExecService);
        ReflectionTestUtils.setField(service, "approvalStateService", approvalStateService);
        ReflectionTestUtils.setField(service, "schemaSnapshotService", schemaSnapshotService);
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
        // Step-times fix: retry redispatch closes PREPARATION at the real dispatch moment
        verify(approvalStateService).markExecutionDispatched("biz-200");
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

    // ============ B6 M3: segmented groups resume from the failed segment (M2 window guard removed) ============

    @Test
    public void retryGroup_segmentedRuntimeGroup_delegatesToResumeUnderLock_m3() {
        // the M2 tightening is GONE: a segmented group takes the ticket row lock and delegates to
        // the orchestrator's resume (fresh-read / failed-segment location / ghost cleanup + probe
        // are pinned in TicketSegmentServiceTest) — never the whole-group replay
        long groupId = 105L;
        DmTicketDbStmtDO group = buildGroup(groupId, "FAILED");
        group.setExecSegments("[{\"segIdx\":1,\"type\":\"AUTOEXEC\",\"stmtRange\":[1,2],\"status\":\"SUCCESS\"},"
            + "{\"segIdx\":2,\"type\":\"GHOST\",\"stmtIndex\":3,\"status\":\"FAILED\",\"error\":\"boom\"}]");
        when(stmtMapper.queryById(groupId)).thenReturn(group);
        DmApprovalDO ticket = buildTicket(groupId + 100, PUID, "PRE_DDL");
        when(approvalMapper.queryById(group.getTicketId())).thenReturn(ticket);
        com.clougence.clouddm.console.web.component.governance.TicketSegmentService segmentService
            = mock(com.clougence.clouddm.console.web.component.governance.TicketSegmentService.class);
        ReflectionTestUtils.setField(service, "ticketSegmentService", segmentService);

        service.retryGroup(PUID, UID, groupId);

        // lock-order discipline: the ticket row lock precedes the resume (single lock, same order)
        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(approvalMapper, segmentService);
        inOrder.verify(approvalMapper).selectByIdForUpdate(group.getTicketId());
        inOrder.verify(segmentService).resumeGroupUnderLock(org.mockito.ArgumentMatchers.same(ticket),
            org.mockito.ArgumentMatchers.eq(groupId), org.mockito.ArgumentMatchers.eq(UID));
        // step-times contract: the (re)dispatch closes PREPARATION at the real dispatch moment
        verify(approvalStateService).markExecutionDispatched("biz-" + group.getTicketId());
        // the legacy whole-group replay must never run for a segmented group
        verify(autoExecService, never()).createGroupJob(any(), anyString(), anyBoolean(), any(), anyString(), anyString());
        verify(stmtMapper, never()).updateExecStatus(anyLong(), anyString(), any());
    }

    @Test
    public void retryGroup_segmentedPlanOnlyGroup_delegatesToResume_m3() {
        // a segment PLAN without a runtime (pre-first-dispatch / corrupt carrier) also takes the
        // resume path — the orchestrator owns the runtime-null decision matrix (dispatch-first vs
        // return-revise guidance)
        long groupId = 106L;
        DmTicketDbStmtDO group = buildGroup(groupId, "PENDING");
        group.setPrecheckResult("{\"checkStatus\":\"PASS\",\"changeType\":\"MIXED\",\"segments\":["
            + "{\"segIdx\":1,\"type\":\"AUTOEXEC\",\"stmtRange\":[1,2]},"
            + "{\"segIdx\":2,\"type\":\"GHOST\",\"stmtIndex\":3,\"sql\":\"ALTER TABLE t_big ADD c INT\"}]}");
        when(stmtMapper.queryById(groupId)).thenReturn(group);
        DmApprovalDO ticket = buildTicket(groupId + 100, PUID, "PRE_DDL");
        when(approvalMapper.queryById(group.getTicketId())).thenReturn(ticket);
        when(autoJobMapper.queryByDependOnGroupId(groupId)).thenReturn(null);
        com.clougence.clouddm.console.web.component.governance.TicketSegmentService segmentService
            = mock(com.clougence.clouddm.console.web.component.governance.TicketSegmentService.class);
        ReflectionTestUtils.setField(service, "ticketSegmentService", segmentService);

        service.retryGroup(PUID, UID, groupId);

        verify(segmentService).resumeGroupUnderLock(org.mockito.ArgumentMatchers.same(ticket),
            org.mockito.ArgumentMatchers.eq(groupId), org.mockito.ArgumentMatchers.eq(UID));
        verify(autoExecService, never()).createGroupJob(any(), anyString(), anyBoolean(), any(), anyString(), anyString());
    }

    @Test
    public void retryGroup_resumeRejection_propagates_noDispatchMark_m3() {
        // an orchestrator rejection (active body / corrupt runtime) surfaces to the caller and
        // leaves the step-times hook untouched
        long groupId = 108L;
        DmTicketDbStmtDO group = buildGroup(groupId, "FAILED");
        group.setExecSegments("[{\"segIdx\":1,\"type\":\"GHOST\",\"stmtIndex\":1,\"status\":\"FAILED\"}]");
        when(stmtMapper.queryById(groupId)).thenReturn(group);
        DmApprovalDO ticket = buildTicket(groupId + 100, PUID, "PRE_DDL");
        when(approvalMapper.queryById(group.getTicketId())).thenReturn(ticket);
        com.clougence.clouddm.console.web.component.governance.TicketSegmentService segmentService
            = mock(com.clougence.clouddm.console.web.component.governance.TicketSegmentService.class);
        org.mockito.Mockito.doThrow(new ErrorMessageException("active"))
            .when(segmentService).resumeGroupUnderLock(any(), org.mockito.ArgumentMatchers.anyLong(), anyString());
        ReflectionTestUtils.setField(service, "ticketSegmentService", segmentService);

        try {
            service.retryGroup(PUID, UID, groupId);
            fail("Expected ErrorMessageException");
        } catch (ErrorMessageException ex) {
            // ok — the orchestrator's rejection propagates verbatim
        }
        verify(approvalStateService, never()).markExecutionDispatched(anyString());
    }

    @Test
    public void retryGroup_probeTerminalResume_runsAggregationTail_m3() {
        // M3 check fix: a probe-success on the LAST segment writes the group terminal synchronously
        // and dispatches NOTHING — no completion callback will ever fire and an EXEC_FAIL ticket is
        // outside the WAIT_EXEC sweep IN set, so retryGroup itself must drive the shared
        // "all groups terminal → ticket terminal" tail (the handleV2JobCompletion :833-846 mirror),
        // or the ticket sticks at EXEC_FAIL over all-SUCCESS groups
        long groupId = 109L;
        DmTicketDbStmtDO group = buildGroup(groupId, "FAILED");
        group.setExecSegments("[{\"segIdx\":1,\"type\":\"GHOST\",\"stmtIndex\":1,\"status\":\"FAILED\"}]");
        when(stmtMapper.queryById(groupId)).thenReturn(group);
        DmApprovalDO ticket = buildTicket(groupId + 100, PUID, "PRE_DDL");
        when(approvalMapper.queryById(group.getTicketId())).thenReturn(ticket);
        com.clougence.clouddm.console.web.component.governance.TicketSegmentService segmentService
            = mock(com.clougence.clouddm.console.web.component.governance.TicketSegmentService.class);
        when(segmentService.resumeGroupUnderLock(any(), org.mockito.ArgumentMatchers.anyLong(), anyString()))
            .thenReturn(true);
        ReflectionTestUtils.setField(service, "ticketSegmentService", segmentService);
        when(stmtMapper.queryByTicketId(group.getTicketId()))
            .thenReturn(List.of(buildGroup(groupId, "SUCCESS")));

        service.retryGroup(PUID, UID, groupId);

        verify(approvalStateService).markExecutionDispatched("biz-" + ticket.getId());
        verify(approvalStateService).completeExecution("biz-" + ticket.getId());
        verify(approvalStateService, never()).failExecution(anyString(), any());
    }

    @Test
    public void retryGroup_probeTerminalResume_otherGroupFailed_failExecution_m3() {
        // the tail mirrors the shared semantics: any FAILED group → failExecution (never a false
        // FINISHED over a failed sibling group)
        long groupId = 110L;
        DmTicketDbStmtDO group = buildGroup(groupId, "FAILED");
        group.setExecSegments("[{\"segIdx\":1,\"type\":\"GHOST\",\"stmtIndex\":1,\"status\":\"FAILED\"}]");
        when(stmtMapper.queryById(groupId)).thenReturn(group);
        DmApprovalDO ticket = buildTicket(groupId + 100, PUID, "PRE_DDL");
        when(approvalMapper.queryById(group.getTicketId())).thenReturn(ticket);
        com.clougence.clouddm.console.web.component.governance.TicketSegmentService segmentService
            = mock(com.clougence.clouddm.console.web.component.governance.TicketSegmentService.class);
        when(segmentService.resumeGroupUnderLock(any(), org.mockito.ArgumentMatchers.anyLong(), anyString()))
            .thenReturn(true);
        ReflectionTestUtils.setField(service, "ticketSegmentService", segmentService);
        DmTicketDbStmtDO sibling = buildGroup(groupId + 1, "FAILED");
        sibling.setTicketId(group.getTicketId());
        when(stmtMapper.queryByTicketId(group.getTicketId()))
            .thenReturn(List.of(buildGroup(groupId, "SUCCESS"), sibling));

        service.retryGroup(PUID, UID, groupId);

        verify(approvalStateService).failExecution(eq("biz-" + ticket.getId()), anyString());
        verify(approvalStateService, never()).completeExecution(anyString());
    }

    @Test
    public void retryGroup_dispatchingResume_noAggregationTail_m3() {
        // a resume that DISPATCHED (flag false) leaves the aggregation to the completion callback —
        // running the tail there would race the in-flight body (group EXECUTING → no-op at best)
        long groupId = 111L;
        DmTicketDbStmtDO group = buildGroup(groupId, "FAILED");
        group.setExecSegments("[{\"segIdx\":1,\"type\":\"GHOST\",\"stmtIndex\":1,\"status\":\"FAILED\"}]");
        when(stmtMapper.queryById(groupId)).thenReturn(group);
        DmApprovalDO ticket = buildTicket(groupId + 100, PUID, "PRE_DDL");
        when(approvalMapper.queryById(group.getTicketId())).thenReturn(ticket);
        com.clougence.clouddm.console.web.component.governance.TicketSegmentService segmentService
            = mock(com.clougence.clouddm.console.web.component.governance.TicketSegmentService.class);
        ReflectionTestUtils.setField(service, "ticketSegmentService", segmentService);

        service.retryGroup(PUID, UID, groupId); // the unstubbed mock returns false

        verify(approvalStateService).markExecutionDispatched("biz-" + ticket.getId());
        verify(stmtMapper, never()).queryByTicketId(anyLong());
        verify(approvalStateService, never()).completeExecution(anyString());
        verify(approvalStateService, never()).failExecution(anyString(), any());
    }

    @Test
    public void retryGroup_m1FlagOnlyGhostGroup_ghostVariantStillUsable_backwardCompatPin() {
        // M1-marked tickets (ghostRouteFlags WITHOUT a segment plan) keep the single-session
        // ghost retry variant — 存量 M1 工单必须继续走 M1 路径 (compatibility ruling)
        long groupId = 107L;
        DmTicketDbStmtDO group = buildGroup(groupId, "FAILED");
        group.setPrecheckResult("{\"checkStatus\":\"PASS\",\"changeType\":\"DDL\",\"ghostRoutes\":[{\"stmtIndex\":1,\"route\":\"GHOST\"}]}");
        when(stmtMapper.queryById(groupId)).thenReturn(group);
        when(stmtMapper.queryByTicketId(group.getTicketId())).thenReturn(List.of(group));
        DmApprovalDO ticket = buildTicket(groupId + 100, PUID, "PRE_DDL");
        com.clougence.clouddm.console.web.component.approval.model.ApprovalMO mo
            = com.clougence.utils.JsonUtils.toObj(ticket.getTicketInfo(),
                com.clougence.clouddm.console.web.component.approval.model.ApprovalMO.class);
        mo.setGhostRouteFlags(List.of(true));
        ticket.setTicketInfo(com.clougence.utils.JsonUtils.toJson(mo));
        when(approvalMapper.queryById(group.getTicketId())).thenReturn(ticket);
        when(autoJobMapper.queryByDependOnGroupId(groupId)).thenReturn(null);
        com.clougence.clouddm.console.web.service.ghost.GhostService ghostService
            = mock(com.clougence.clouddm.console.web.service.ghost.GhostService.class);
        when(ghostService.findTaskForTicketGroup(group.getTicketId(), groupId)).thenReturn(null);
        ReflectionTestUtils.setField(service, "ghostService", ghostService);
        ReflectionTestUtils.setField(service, "rdpOpAuditService",
            mock(com.clougence.rdp.service.RdpOpAuditService.class));

        service.retryGroup(PUID, UID, groupId);

        verify(ghostService).executeForTicket(any(), any(), any(),
            eq(group.getTicketId()), eq(groupId), isNull());
        verify(autoExecService, never()).createGroupJob(any(), anyString(), anyBoolean(), any(), anyString(), anyString());
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
