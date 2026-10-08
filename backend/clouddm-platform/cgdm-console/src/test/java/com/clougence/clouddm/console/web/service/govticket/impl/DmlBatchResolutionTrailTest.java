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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.console.web.component.approval.ApprovalStateService;
import com.clougence.clouddm.console.web.component.governance.TicketSegment;
import com.clougence.clouddm.console.web.component.governance.TicketSegmentService;
import com.clougence.clouddm.console.web.model.vo.dmlbatch.DmlBatchOpAuditObj;
import com.clougence.clouddm.console.web.service.govticket.GovTicketV2Service;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.DbChangeEventDal;
import com.clougence.clouddm.platform.dal.access.ExecutionDal;
import com.clougence.clouddm.platform.dal.access.TicketDbStmtDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangeEventMapper;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoJobMapper;
import com.clougence.clouddm.platform.dal.mapper.govticket.DmTicketDbStmtMapper;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO;
import com.clougence.clouddm.platform.dal.model.dbchange.GovEventType;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;
import com.clougence.clouddm.platform.dal.model.monitor.AuditType;
import com.clougence.clouddm.platform.dal.model.monitor.SecurityLevel;
import com.clougence.clouddm.platform.dal.model.ResourceType;
import com.clougence.utils.JsonUtils;

/**
 * B7 N2 (implement.md 单测矩阵「resolution 守卫」的 retryGroup 侧): the window-crash manual
 * verdict's DOUBLE TRAIL (裁决 #13, design §7.4) — after a SUCCESSFUL resume carrying a
 * resolution over an UNRESOLVED DML_BATCH segment, retryGroup writes the DML_BATCH_RESOLUTION
 * event (operator = the real retry user) AND the DML_BATCH_EXECUTE audit row annotated
 * resolution+intentSeq (四点注册复用, subject = the OPERATOR — a human high-risk intervention,
 * unlike the dispatch row's submitter-subject). Trail material is captured BEFORE the resume
 * (resetSegmentForRerun clears the summary); a REJECTED resume (the orchestrator's
 * RESOLUTION_REQUIRED guard / active body) leaves NO trail; the 3-arg legacy form delegates with
 * a null resolution and keeps the B6 3-arg orchestrator call byte-identical (the frozen
 * GovTicketV2RetryGroupTest pins ride on exactly that signature).
 * <p>
 * Discipline: JUnit4 + hand-written mocks + ReflectionTestUtils; NO Spring.
 */
public class DmlBatchResolutionTrailTest {

    private GovTicketV2ServiceImpl   service;
    private DmApprovalMapper         approvalMapper;
    private DmTicketDbStmtMapper     stmtMapper;
    private DmDbChangeEventMapper    eventMapper;
    private TicketSegmentService     ticketSegmentService;
    private ApprovalStateService     approvalStateService;
    private com.clougence.rdp.service.RdpOpAuditService rdpOpAuditService;

    private static final long   TICKET_ID = 900L;
    private static final long   GROUP_ID  = 100L;
    private static final long   DS_ID     = 20L;
    private static final String PUID      = "puid-001";
    private static final String UID       = "uid-dba";
    private static final String DB        = "prod_db";

    @Before
    public void setUp() {
        service = new GovTicketV2ServiceImpl();

        ApprovalDal approvalDal = mock(ApprovalDal.class);
        approvalMapper = mock(DmApprovalMapper.class);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);
        TicketDbStmtDal ticketDbStmtDal = mock(TicketDbStmtDal.class);
        stmtMapper = mock(DmTicketDbStmtMapper.class);
        when(ticketDbStmtDal.stmtMapper()).thenReturn(stmtMapper);
        DbChangeEventDal dbChangeEventDal = mock(DbChangeEventDal.class);
        eventMapper = mock(DmDbChangeEventMapper.class);
        when(dbChangeEventDal.eventMapper()).thenReturn(eventMapper);
        ExecutionDal execDal = mock(ExecutionDal.class);
        when(execDal.autoJobMapper()).thenReturn(mock(DmExecAutoJobMapper.class));
        ticketSegmentService = mock(TicketSegmentService.class);
        approvalStateService = mock(ApprovalStateService.class);
        rdpOpAuditService = mock(com.clougence.rdp.service.RdpOpAuditService.class);

        ReflectionTestUtils.setField(service, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(service, "ticketDbStmtDal", ticketDbStmtDal);
        ReflectionTestUtils.setField(service, "dbChangeEventDal", dbChangeEventDal);
        ReflectionTestUtils.setField(service, "execDal", execDal);
        ReflectionTestUtils.setField(service, "ticketSegmentService", ticketSegmentService);
        ReflectionTestUtils.setField(service, "approvalStateService", approvalStateService);
        ReflectionTestUtils.setField(service, "rdpOpAuditService", rdpOpAuditService);

        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setBizId("biz-900");
        ticket.setPrimaryUid(PUID);
        ticket.setOwnerUid("uid-submitter");
        ticket.setTicketStatus(ApprovalStatus.EXEC_FAIL);
        ticket.setTicketInfo("{\"ticketType\":\"PROD_DML\"}");
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket);
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(ticket);
        when(ticketSegmentService.resumeGroupUnderLock(any(), anyLong(), anyString(), any())).thenReturn(false);
    }

    // ==================== fixtures ====================

    private DmTicketDbStmtDO unresolvedGroup() {
        TicketSegment seg = new TicketSegment();
        seg.setSegIdx(1);
        seg.setType(TicketSegment.TYPE_DML_BATCH);
        seg.setStatus(TicketSegment.STATUS_FAILED);
        seg.setTaskId(55L);
        seg.setTable("t_order");
        seg.setError("批 7 执行结果未知");
        seg.setSummary(new java.util.HashMap<>(Map.of(
            "unresolvedIntent", Boolean.TRUE, "intentSeq", 7, "batchesDone", 6, "rowsDone", 30000)));
        DmTicketDbStmtDO group = new DmTicketDbStmtDO();
        group.setId(GROUP_ID);
        group.setTicketId(TICKET_ID);
        group.setDsId(DS_ID);
        group.setDbName(DB);
        group.setExecStatus("FAILED");
        group.setExecSegments(JsonUtils.toJson(List.of(seg)));
        when(stmtMapper.queryById(GROUP_ID)).thenReturn(group);
        return group;
    }

    private DmTicketDbStmtDO plainFailedGroup() {
        TicketSegment seg = new TicketSegment();
        seg.setSegIdx(1);
        seg.setType(TicketSegment.TYPE_DML_BATCH);
        seg.setStatus(TicketSegment.STATUS_FAILED);
        seg.setTaskId(55L);
        seg.setSummary(new java.util.HashMap<>(Map.of("batchesDone", 6, "error", "retries exhausted")));
        DmTicketDbStmtDO group = new DmTicketDbStmtDO();
        group.setId(GROUP_ID);
        group.setTicketId(TICKET_ID);
        group.setDsId(DS_ID);
        group.setDbName(DB);
        group.setExecStatus("FAILED");
        group.setExecSegments(JsonUtils.toJson(List.of(seg)));
        when(stmtMapper.queryById(GROUP_ID)).thenReturn(group);
        return group;
    }

    // ==================== the double trail ====================

    @Test
    public void retry_skipResolution_eventAndAuditTrailed_afterSuccessfulResume() {
        DmTicketDbStmtDO group = unresolvedGroup();

        service.retryGroup(PUID, UID, GROUP_ID, TicketSegment.RESOLUTION_SKIP_BATCH);

        verify(ticketSegmentService).resumeGroupUnderLock(any(), eq(GROUP_ID), eq(UID),
            eq(TicketSegment.RESOLUTION_SKIP_BATCH));
        // the event trail
        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        DmDbChangeEventDO event = eventCaptor.getValue();
        assertEquals(GovEventType.DML_BATCH_RESOLUTION.name(), event.getEventType());
        assertEquals(TICKET_ID, event.getTicketId().longValue());
        assertEquals("the operator uid is the human who adjudicated", UID, event.getOperatorUid());
        assertTrue(event.getEventData().contains("\"resolution\":\"SKIP_BATCH\""));
        assertTrue(event.getEventData().contains("\"intentSeq\":7"));
        assertTrue(event.getEventData().contains("\"segmentIndex\":1"));
        assertTrue(event.getEventData().contains("\"groupId\":" + GROUP_ID));
        // the audit twin (DML_BATCH_EXECUTE reused, obj annotated resolution+intentSeq)
        ArgumentCaptor<DmlBatchOpAuditObj> auditCaptor = ArgumentCaptor.forClass(DmlBatchOpAuditObj.class);
        verify(rdpOpAuditService).logAndAddOperationAudit(eq(PUID), eq(UID),
            eq("/system/dbChangeV2/dmlBatch/resolution"), isNull(), eq(DS_ID), auditCaptor.capture(),
            eq(SecurityLevel.HIGH), eq(AuditType.DML_BATCH_EXECUTE), eq(ResourceType.DATASOURCE));
        DmlBatchOpAuditObj obj = auditCaptor.getValue();
        assertEquals(TicketSegment.RESOLUTION_SKIP_BATCH, obj.getResolution());
        assertEquals(Long.valueOf(7L), obj.getIntentSeq());
        assertEquals(GovTicketV2Service.GHOST_VIA_RETRY, obj.getVia());
        assertEquals(Long.valueOf(TICKET_ID), obj.getTicketId());
        assertEquals(Long.valueOf(GROUP_ID), obj.getGroupId());
        assertEquals(Integer.valueOf(1), obj.getSegmentIndex());
        assertEquals("t_order", obj.getTableName());
    }

    @Test
    public void retry_rerunResolution_trailed_withRerunVerdict() {
        unresolvedGroup();

        service.retryGroup(PUID, UID, GROUP_ID, TicketSegment.RESOLUTION_RERUN_BATCH);

        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        assertTrue(eventCaptor.getValue().getEventData().contains("\"resolution\":\"RERUN_BATCH\""));
        ArgumentCaptor<DmlBatchOpAuditObj> auditCaptor = ArgumentCaptor.forClass(DmlBatchOpAuditObj.class);
        verify(rdpOpAuditService).logAndAddOperationAudit(anyString(), anyString(), anyString(),
            isNull(), any(), auditCaptor.capture(), any(), any(), any());
        assertEquals(TicketSegment.RESOLUTION_RERUN_BATCH, auditCaptor.getValue().getResolution());
    }

    @Test
    public void retry_trailOrder_resumeThenDispatchMarkThenTrail() {
        unresolvedGroup();

        service.retryGroup(PUID, UID, GROUP_ID, TicketSegment.RESOLUTION_RERUN_BATCH);

        InOrder inOrder = Mockito.inOrder(ticketSegmentService, approvalStateService, eventMapper, rdpOpAuditService);
        inOrder.verify(ticketSegmentService).resumeGroupUnderLock(any(), anyLong(), anyString(), anyString());
        inOrder.verify(approvalStateService).markExecutionDispatched("biz-900");
        inOrder.verify(eventMapper).insert(any(DmDbChangeEventDO.class));
        inOrder.verify(rdpOpAuditService).logAndAddOperationAudit(anyString(), anyString(), anyString(),
            any(), any(), any(), any(), any(), any());
    }

    // ==================== no-trail shapes ====================

    @Test
    public void retry_threeArgLegacyForm_delegatesNullResolution_threeArgOrchestratorCall_noTrail() {
        unresolvedGroup();

        service.retryGroup(PUID, UID, GROUP_ID);

        // the B6 call shape byte-identical (the frozen RetryGroup pins verify THIS signature on
        // their orchestrator mock — the 4-arg overload must NOT be touched on the null path)
        verify(ticketSegmentService).resumeGroupUnderLock(any(DmApprovalDO.class), eq(GROUP_ID), eq(UID));
        verify(ticketSegmentService, never()).resumeGroupUnderLock(any(), anyLong(), anyString(), any());
        verify(eventMapper, never()).insert(any(DmDbChangeEventDO.class));
        verify(rdpOpAuditService, never()).logAndAddOperationAudit(anyString(), anyString(), anyString(),
            any(), any(), any(), any(), any(), any());
    }

    @Test
    public void retry_resolutionOverPlainFailure_ignored_noTrail() {
        // a resolution carried over a NON-unresolved target writes no verdict trail (there was
        // nothing to adjudicate; the dispatch audit inside the orchestrator is the only row)
        plainFailedGroup();

        service.retryGroup(PUID, UID, GROUP_ID, TicketSegment.RESOLUTION_SKIP_BATCH);

        verify(ticketSegmentService).resumeGroupUnderLock(any(), eq(GROUP_ID), eq(UID),
            eq(TicketSegment.RESOLUTION_SKIP_BATCH));
        verify(eventMapper, never()).insert(any(DmDbChangeEventDO.class));
        verify(rdpOpAuditService, never()).logAndAddOperationAudit(anyString(), anyString(), anyString(),
            any(), any(), any(), any(), any(), any());
    }

    @Test
    public void retry_resumeRejected_noTrail_theGuardComesFirst() {
        unresolvedGroup();
        when(ticketSegmentService.resumeGroupUnderLock(any(), anyLong(), anyString(), any()))
            .thenThrow(new ErrorMessageException("RESOLUTION_REQUIRED copy"));

        try {
            service.retryGroup(PUID, UID, GROUP_ID, "JUST_RUN_IT");
            fail("expected the orchestrator guard rejection to propagate");
        } catch (ErrorMessageException expected) {
            assertNotNull(expected.getMessage());
        }
        // a rejected resume leaves NO trail (the verdict never took effect)
        verify(eventMapper, never()).insert(any(DmDbChangeEventDO.class));
        verify(rdpOpAuditService, never()).logAndAddOperationAudit(anyString(), anyString(), anyString(),
            any(), any(), any(), any(), any(), any());
    }

    // ==================== trail resilience ====================

    @Test
    public void retry_auditFailure_neverBreaksTheResumedRetry() {
        unresolvedGroup();
        doThrow(new RuntimeException("audit store down")).when(rdpOpAuditService)
            .logAndAddOperationAudit(anyString(), anyString(), anyString(), any(), any(), any(), any(), any(), any());

        service.retryGroup(PUID, UID, GROUP_ID, TicketSegment.RESOLUTION_RERUN_BATCH); // must not throw

        verify(ticketSegmentService).resumeGroupUnderLock(any(), eq(GROUP_ID), eq(UID), anyString());
        verify(eventMapper).insert(any(DmDbChangeEventDO.class)); // the event leg survived
    }

    @Test
    public void retry_corruptRuntime_noTrailCapture_retryStillDelegates() {
        // the trail capture is best-effort: an unparsable runtime degrades to NO trail while the
        // resume itself proceeds (the orchestrator owns the authoritative under-lock judgement)
        DmTicketDbStmtDO group = unresolvedGroup();
        group.setExecSegments("{corrupt");

        service.retryGroup(PUID, UID, GROUP_ID, TicketSegment.RESOLUTION_SKIP_BATCH);

        verify(ticketSegmentService).resumeGroupUnderLock(any(), eq(GROUP_ID), eq(UID),
            eq(TicketSegment.RESOLUTION_SKIP_BATCH));
        verify(eventMapper, never()).insert(any(DmDbChangeEventDO.class));
    }
}
