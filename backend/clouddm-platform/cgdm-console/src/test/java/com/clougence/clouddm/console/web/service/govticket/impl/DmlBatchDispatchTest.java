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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.console.web.component.governance.SchemaSnapshotService;
import com.clougence.clouddm.console.web.component.governance.TicketSegment;
import com.clougence.clouddm.console.web.model.vo.dmlbatch.DmlBatchOpAuditObj;
import com.clougence.clouddm.console.web.service.dmlbatch.DmlBatchService;
import com.clougence.clouddm.console.web.service.govticket.GovTicketV2Service;
import com.clougence.clouddm.platform.dal.access.TicketDbStmtDal;
import com.clougence.clouddm.platform.dal.mapper.govticket.DmTicketDbStmtMapper;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;
import com.clougence.clouddm.platform.dal.model.monitor.AuditType;
import com.clougence.clouddm.platform.dal.model.monitor.SecurityLevel;
import com.clougence.clouddm.platform.dal.model.ResourceType;
import com.clougence.clouddm.sdk.sql.editor.rewrite.DmlBatchRangeSpec;

/**
 * B7 N1b (implement.md 派发缝): {@code dispatchBatchSegment} — the dispatchGhostSegment mirror
 * for the DML_BATCH segment type. Pins: the caller-lock contract (NO group CAS here), the
 * captureIfMissing pre-before baseline, the launch delegation with the resume-checkpoint
 * passthrough (retryGroup inheritance), the taskId recording ON the segment (the debt #5 CAS
 * cross-check key — the CALLER persists the runtime), the group EXECUTING idempotent rewrite,
 * the DML_BATCH_EXECUTE audit (HIGH / DATASOURCE / resId=dsId / subject=the SUBMITTER /
 * system-side URI marker / obj annotated ticketId+groupId+segmentIndex+via+frozen key facts),
 * the audit-failure-never-rolls-back-the-launched-task discipline, the submitTask-failure
 * propagation with NO residue (no group write, no audit — the sweep re-dispatches the segment),
 * and the sqlSummary ≤500 truncation.
 * <p>
 * Discipline: JUnit4 + hand-written mocks + ReflectionTestUtils + ArgumentCaptor; NO Spring.
 */
public class DmlBatchDispatchTest {

    private GovTicketV2ServiceImpl  service;
    private SchemaSnapshotService   schemaSnapshotService;
    private DmlBatchService         dmlBatchService;
    private TicketDbStmtDal         ticketDbStmtDal;
    private DmTicketDbStmtMapper    stmtMapper;
    private com.clougence.rdp.service.RdpOpAuditService rdpOpAuditService;

    private DmApprovalDO            ticket;
    private DmTicketDbStmtDO        group;
    private TicketSegment           segment;

    private static final long   TICKET_ID = 900L;
    private static final long   GROUP_ID  = 100L;
    private static final long   DS_ID     = 20L;
    private static final String PUID      = "puid-001";
    private static final String SUBMITTER = "uid-submitter";
    private static final String DB        = "prod_db";
    private static final long   TASK_ID   = 55L;
    private static final String URI       = "/system/dbChangeV2/dmlBatch/auto-dispatch";

    @Before
    public void setUp() {
        service = new GovTicketV2ServiceImpl();

        schemaSnapshotService = mock(SchemaSnapshotService.class);
        dmlBatchService = mock(DmlBatchService.class);
        ticketDbStmtDal = mock(TicketDbStmtDal.class);
        stmtMapper = mock(DmTicketDbStmtMapper.class);
        when(ticketDbStmtDal.stmtMapper()).thenReturn(stmtMapper);
        rdpOpAuditService = mock(com.clougence.rdp.service.RdpOpAuditService.class);

        ReflectionTestUtils.setField(service, "schemaSnapshotService", schemaSnapshotService);
        ReflectionTestUtils.setField(service, "dmlBatchService", dmlBatchService);
        ReflectionTestUtils.setField(service, "ticketDbStmtDal", ticketDbStmtDal);
        ReflectionTestUtils.setField(service, "rdpOpAuditService", rdpOpAuditService);

        ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setBizId("biz-900");
        ticket.setPrimaryUid(PUID);
        ticket.setOwnerUid(SUBMITTER);

        group = new DmTicketDbStmtDO();
        group.setId(GROUP_ID);
        group.setTicketId(TICKET_ID);
        group.setDsId(DS_ID);
        group.setDbName(DB);
        group.setExecStatus("EXECUTING");

        segment = TicketSegment.dmlBatchPlan(2, 3, "t_order", 5_000_000L,
            "UPDATE t_order SET status = 1 WHERE status = 0",
            new TicketSegment.BatchKey("id", DmlBatchRangeSpec.KEY_TYPE_INT), false);
        segment.setStatus(TicketSegment.STATUS_EXECUTING);

        when(dmlBatchService.submitForTicket(any(), any(), any(), any())).thenReturn(TASK_ID);
    }

    @Test
    public void dispatch_happyPath_snapshotThenLaunch_taskIdRecorded_groupExecuting_audited() {
        service.dispatchBatchSegment(ticket, group, segment, GovTicketV2Service.GHOST_VIA_SEGMENT_CHAIN);

        // the pre-before baseline + launch delegation (with the checkpoint passthrough slot)
        InOrder inOrder = Mockito.inOrder(schemaSnapshotService, dmlBatchService, stmtMapper, rdpOpAuditService);
        inOrder.verify(schemaSnapshotService).captureIfMissing(same(group), eq(TICKET_ID));
        inOrder.verify(dmlBatchService).submitForTicket(same(ticket), same(group), same(segment), isNull());
        // the taskId is recorded ON the segment (the caller persists the runtime — single writer)
        assertEquals(Long.valueOf(TASK_ID), segment.getTaskId());
        // the group row idempotent EXECUTING rewrite (first segment: PENDING→EXECUTING)
        inOrder.verify(stmtMapper).updateExecStatus(GROUP_ID, "EXECUTING", null);
        // the audit AFTER the launch (its failure must not roll the task back)
        inOrder.verify(rdpOpAuditService).logAndAddOperationAudit(eq(PUID), eq(SUBMITTER), eq(URI),
            isNull(), eq(DS_ID), any(DmlBatchOpAuditObj.class), eq(SecurityLevel.HIGH),
            eq(AuditType.DML_BATCH_EXECUTE), eq(ResourceType.DATASOURCE),
            any(com.clougence.rdp.service.model.AuditChainMO.class));
        // NO group CAS here — the caller (orchestrator) owns the segment state under its lock
        verify(stmtMapper, never()).queryById(anyLong());
    }

    @Test
    public void dispatch_auditObj_carriesTheFrozenPlanFactsAndVia() {
        service.dispatchBatchSegment(ticket, group, segment, GovTicketV2Service.GHOST_VIA_RETRY);

        ArgumentCaptor<DmlBatchOpAuditObj> captor = ArgumentCaptor.forClass(DmlBatchOpAuditObj.class);
        ArgumentCaptor<com.clougence.rdp.service.model.AuditChainMO> chainCaptor = ArgumentCaptor
            .forClass(com.clougence.rdp.service.model.AuditChainMO.class);
        verify(rdpOpAuditService).logAndAddOperationAudit(anyString(), anyString(), anyString(),
            isNull(), any(), captor.capture(), any(), any(), any(), chainCaptor.capture());
        // C8 M2 write-point matrix pin: ticket binding + fingerprint over the FULL frozen
        // statement + explicit SYSTEM_AUTO
        assertEquals(Long.valueOf(TICKET_ID), chainCaptor.getValue().getTicketId());
        assertEquals(com.clougence.clouddm.console.web.component.governance.GovSqlHashUtils
                .hash("UPDATE t_order SET status = 1 WHERE status = 0"),
            chainCaptor.getValue().getSqlFingerprint());
        assertEquals(com.clougence.clouddm.platform.dal.model.monitor.AuditChannel.SYSTEM_AUTO,
            chainCaptor.getValue().getChannel());
        DmlBatchOpAuditObj obj = captor.getValue();
        assertEquals(Long.valueOf(TICKET_ID), obj.getTicketId());
        assertEquals(Long.valueOf(GROUP_ID), obj.getGroupId());
        assertEquals(Integer.valueOf(2), obj.getSegmentIndex());
        assertEquals(GovTicketV2Service.GHOST_VIA_RETRY, obj.getVia());
        assertEquals(Long.valueOf(DS_ID), obj.getDsId());
        assertEquals(DB, obj.getDbName());
        assertEquals("t_order", obj.getTableName());
        assertEquals("id", obj.getKeyColumn());
        assertEquals(DmlBatchRangeSpec.KEY_TYPE_INT, obj.getKeyType());
        assertEquals(Long.valueOf(5_000_000L), obj.getEstimatedRows());
        assertEquals(Boolean.FALSE, obj.getIdempotent());
        assertEquals("UPDATE t_order SET status = 1 WHERE status = 0", obj.getSqlSummary());
    }

    @Test
    public void dispatch_sqlSummary_truncatedTo500() {
        StringBuilder sql = new StringBuilder("UPDATE t_order SET padding = '");
        while (sql.length() < 700) {
            sql.append('x');
        }
        sql.append("' WHERE status = 0");
        segment.setSql(sql.toString());

        service.dispatchBatchSegment(ticket, group, segment, GovTicketV2Service.GHOST_VIA_AUTO_ROUTE);

        ArgumentCaptor<DmlBatchOpAuditObj> captor = ArgumentCaptor.forClass(DmlBatchOpAuditObj.class);
        ArgumentCaptor<com.clougence.rdp.service.model.AuditChainMO> chainCaptor = ArgumentCaptor
            .forClass(com.clougence.rdp.service.model.AuditChainMO.class);
        verify(rdpOpAuditService).logAndAddOperationAudit(anyString(), anyString(), anyString(),
            isNull(), any(), captor.capture(), any(), any(), any(), chainCaptor.capture());
        assertEquals(DmlBatchOpAuditObj.SQL_MAX_LENGTH, captor.getValue().getSqlSummary().length());
        // C8 M2: the fingerprint is computed over the FULL statement text — NEVER over the
        // ≤500 sqlSummary truncation (design D3 "非 sqlSummary 截断文" pin)
        assertEquals(com.clougence.clouddm.console.web.component.governance.GovSqlHashUtils.hash(sql.toString()),
            chainCaptor.getValue().getSqlFingerprint());
        org.junit.Assert.assertNotEquals(com.clougence.clouddm.console.web.component.governance.GovSqlHashUtils
                .hash(captor.getValue().getSqlSummary()),
            chainCaptor.getValue().getSqlFingerprint());
    }

    @Test
    public void dispatch_resumeCheckpoint_ridesToTheLauncher() {
        // retryGroup inheritance: the segment's resumeCheckpoint (done side) seeds the fresh task
        Map<String, Object> inherited = new HashMap<>();
        inherited.put("cursor", "99");
        inherited.put("batchesDone", 20);
        segment.setResumeCheckpoint(inherited);

        service.dispatchBatchSegment(ticket, group, segment, GovTicketV2Service.GHOST_VIA_RETRY);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(dmlBatchService).submitForTicket(same(ticket), same(group), same(segment), captor.capture());
        assertNotNull(captor.getValue());
        assertEquals("99", captor.getValue().get("cursor"));
    }

    @Test
    public void dispatch_auditFailure_neverRollsBackTheLaunchedTask() {
        Mockito.doThrow(new RuntimeException("audit store down"))
            .when(rdpOpAuditService).logAndAddOperationAudit(anyString(), anyString(), anyString(),
                any(), any(), any(), any(), any(), any(), any(com.clougence.rdp.service.model.AuditChainMO.class));

        service.dispatchBatchSegment(ticket, group, segment, GovTicketV2Service.GHOST_VIA_AUTO_ROUTE);

        // the dispatch is KEPT (GHOST_EXECUTE discipline): taskId recorded, group EXECUTING written
        assertEquals(Long.valueOf(TASK_ID), segment.getTaskId());
        verify(stmtMapper).updateExecStatus(GROUP_ID, "EXECUTING", null);
    }

    @Test
    public void dispatch_submitTaskFailure_propagates_noGroupWriteNoAudit() {
        when(dmlBatchService.submitForTicket(any(), any(), any(), any()))
            .thenThrow(new IllegalStateException("task store down"));

        try {
            service.dispatchBatchSegment(ticket, group, segment, GovTicketV2Service.GHOST_VIA_AUTO_ROUTE);
            fail("expected the submitTask failure to propagate (no execution body residue)");
        } catch (IllegalStateException expected) {
            assertEquals("task store down", expected.getMessage());
        }

        assertNull(segment.getTaskId());
        verify(stmtMapper, never()).updateExecStatus(anyLong(), anyString(), any());
        verifyNoAudit();
    }

    private void verifyNoAudit() {
        verify(rdpOpAuditService, never()).logAndAddOperationAudit(anyString(), anyString(), anyString(),
            any(), any(), any(), any(), any(), any(), any(com.clougence.rdp.service.model.AuditChainMO.class));
    }

    @Test
    public void dispatch_batchKeyNullSegment_launchRefusesLoudly_atTheServiceSeam() {
        // the frozen-plan completeness check lives in submitForTicket (pinned in
        // DmlBatchServiceProbeTest) — the dispatch surface propagates it unchanged
        TicketSegment broken = TicketSegment.dmlBatchPlan(1, 1, "t_order", 1000L,
            "DELETE FROM t_order WHERE status = 0", null, true);
        when(dmlBatchService.submitForTicket(any(), any(), any(), any()))
            .thenThrow(new IllegalStateException("no frozen batchKey/sql"));

        try {
            service.dispatchBatchSegment(ticket, group, broken, GovTicketV2Service.GHOST_VIA_AUTO_ROUTE);
            fail("expected the incomplete-plan refusal to propagate");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("no frozen batchKey/sql"));
        }
        verify(stmtMapper, never()).updateExecStatus(anyLong(), anyString(), any());
    }

    // ==================== N2: audit task-fact decoration (N1b check handover #3) ====================

    private com.clougence.clouddm.platform.dal.mapper.execution.DmExecAsyncTaskMapper wireExecDal() {
        com.clougence.clouddm.platform.dal.access.ExecutionDal execDal
            = mock(com.clougence.clouddm.platform.dal.access.ExecutionDal.class);
        com.clougence.clouddm.platform.dal.mapper.execution.DmExecAsyncTaskMapper taskMapper
            = mock(com.clougence.clouddm.platform.dal.mapper.execution.DmExecAsyncTaskMapper.class);
        when(execDal.asyncTaskMapper()).thenReturn(taskMapper);
        ReflectionTestUtils.setField(service, "execDal", execDal);
        return taskMapper;
    }

    private static com.clougence.clouddm.platform.dal.model.execution.DmExecAsyncTaskDO submittedTaskRow() {
        com.clougence.clouddm.console.web.service.dmlbatch.asyntask.DmlBatchAsyncTaskConfig cfg
            = new com.clougence.clouddm.console.web.service.dmlbatch.asyntask.DmlBatchAsyncTaskConfig();
        cfg.setBizId("batch-biz-77");
        com.clougence.clouddm.console.web.service.dmlbatch.DmlBatchEffectiveParams params
            = new com.clougence.clouddm.console.web.service.dmlbatch.DmlBatchEffectiveParams();
        params.setBatchSize(2000);
        cfg.setEffectiveParams(params);
        com.clougence.clouddm.platform.dal.model.execution.DmExecAsyncTaskDO row
            = new com.clougence.clouddm.platform.dal.model.execution.DmExecAsyncTaskDO();
        row.setId(TASK_ID);
        row.setConfigData(com.clougence.utils.JsonUtils.toJson(cfg));
        return row;
    }

    @Test
    public void dispatch_auditObj_taskBizIdAndBatchSize_filledFromTaskRow() {
        com.clougence.clouddm.platform.dal.mapper.execution.DmExecAsyncTaskMapper taskMapper = wireExecDal();
        when(taskMapper.queryById(TASK_ID)).thenReturn(submittedTaskRow());

        service.dispatchBatchSegment(ticket, group, segment, GovTicketV2Service.GHOST_VIA_AUTO_ROUTE);

        ArgumentCaptor<DmlBatchOpAuditObj> captor = ArgumentCaptor.forClass(DmlBatchOpAuditObj.class);
        verify(rdpOpAuditService).logAndAddOperationAudit(anyString(), anyString(), anyString(),
            isNull(), any(), captor.capture(), any(), any(), any(), any(com.clougence.rdp.service.model.AuditChainMO.class));
        assertEquals("batch-biz-77", captor.getValue().getTaskBizId());
        assertEquals(Integer.valueOf(2000), captor.getValue().getBatchSize());
    }

    @Test
    public void dispatch_auditDecoration_taskRowMissing_fieldsStayNull_auditProceeds() {
        com.clougence.clouddm.platform.dal.mapper.execution.DmExecAsyncTaskMapper taskMapper = wireExecDal();
        when(taskMapper.queryById(TASK_ID)).thenReturn(null);

        service.dispatchBatchSegment(ticket, group, segment, GovTicketV2Service.GHOST_VIA_AUTO_ROUTE);

        ArgumentCaptor<DmlBatchOpAuditObj> captor = ArgumentCaptor.forClass(DmlBatchOpAuditObj.class);
        verify(rdpOpAuditService).logAndAddOperationAudit(anyString(), anyString(), anyString(),
            isNull(), any(), captor.capture(), any(), any(), any(), any(com.clougence.rdp.service.model.AuditChainMO.class));
        assertNull(captor.getValue().getTaskBizId());
        assertNull(captor.getValue().getBatchSize());
    }

    @Test
    public void dispatch_auditDecoration_lookupThrows_neverBreaksTheDispatch() {
        com.clougence.clouddm.platform.dal.mapper.execution.DmExecAsyncTaskMapper taskMapper = wireExecDal();
        when(taskMapper.queryById(TASK_ID)).thenThrow(new RuntimeException("task store down"));

        service.dispatchBatchSegment(ticket, group, segment, GovTicketV2Service.GHOST_VIA_AUTO_ROUTE);

        // the dispatch stands (taskId recorded, group EXECUTING) and the audit still fires
        assertEquals(Long.valueOf(TASK_ID), segment.getTaskId());
        verify(stmtMapper).updateExecStatus(GROUP_ID, "EXECUTING", null);
        verify(rdpOpAuditService).logAndAddOperationAudit(anyString(), anyString(), anyString(),
            isNull(), any(), any(DmlBatchOpAuditObj.class), any(), any(), any(), any(com.clougence.rdp.service.model.AuditChainMO.class));
    }

    @Test
    public void dispatch_multiBatchSegmentPlan_runtimeShapeIsAdditive() {
        // exec_segments carrier compatibility: a DML_BATCH runtime entry round-trips through the
        // TicketSegment model with its plan freeze intact (parseRuntime never-throw additive shape)
        TicketSegment batch = TicketSegment.dmlBatchPlan(1, 2, "t_order", 5_000_000L,
            "UPDATE t_order SET status = 1 WHERE status = 0",
            new TicketSegment.BatchKey("id", DmlBatchRangeSpec.KEY_TYPE_INT), false);
        TicketSegment autoexec = TicketSegment.autoexecPlan(2, 3, 4);
        String json = com.clougence.utils.JsonUtils.toJson(List.of(batch, autoexec));
        List<TicketSegment> parsed = TicketSegment.parseRuntime(json);

        assertEquals(2, parsed.size());
        assertEquals(TicketSegment.TYPE_DML_BATCH, parsed.get(0).getType());
        assertEquals("id", parsed.get(0).getBatchKey().getColumn());
        assertEquals(DmlBatchRangeSpec.KEY_TYPE_INT, parsed.get(0).getBatchKey().getKeyType());
        assertEquals(Long.valueOf(5_000_000L), parsed.get(0).getEstimatedRows());
        assertEquals(Boolean.FALSE, parsed.get(0).getIdempotent());
        assertEquals(Integer.valueOf(2), parsed.get(0).getStmtIndex());
        assertEquals(List.of(3, 4), parsed.get(1).getStmtRange());
    }
}
