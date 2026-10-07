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
package com.clougence.clouddm.console.web.component.governance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.api.console.autoexec.ErrorStrategy;
import com.clougence.clouddm.console.web.component.execute.AutoExecService;
import com.clougence.clouddm.console.web.service.dmlbatch.DmlBatchEffectiveParams;
import com.clougence.clouddm.console.web.service.dmlbatch.DmlBatchService;
import com.clougence.clouddm.console.web.service.dmlbatch.asyntask.DmlBatchAsyncTaskConfig;
import com.clougence.clouddm.console.web.service.dmlbatch.asyntask.DmlBatchCheckpoint;
import com.clougence.clouddm.console.web.service.ghost.GhostService;
import com.clougence.clouddm.console.web.service.govticket.GovTicketV2Service;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.ExecutionDal;
import com.clougence.clouddm.platform.dal.access.TicketDbStmtDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecAsyncTaskMapper;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoJobMapper;
import com.clougence.clouddm.platform.dal.mapper.govticket.DmTicketDbStmtMapper;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.execution.AsyncTaskStatus;
import com.clougence.clouddm.platform.dal.model.execution.DmExecAsyncTaskDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;
import com.clougence.clouddm.sdk.sql.editor.rewrite.DmlBatchRangeSpec;
import com.clougence.utils.JsonUtils;

/**
 * B7 N1b (implement.md 单测矩阵「sweep repair DML_BATCH 五行 / retryGroup 续跑 / 推进缝」): the
 * segment orchestrator's DML_BATCH branches — the ADDITIVE extension pins (every existing
 * GHOST/AUTOEXEC behavior stays in the untouched TicketSegmentServiceTest suite, 46 pins):
 * <ul>
 * <li>dispatch: dispatchFirstSegment over a DML_BATCH-only plan (the hasRoutedSegment guard
 *     widening) + the chained dispatchSegmentUnderLock branch;</li>
 * <li>advance: completeDmlBatchSegmentUnderLock CAS — segmentIndex binding, the TASKID
 *     CROSS-CHECK (debt #5: a stale task of a rebuilt segment never writes through), replay
 *     skips, the summary freeze, the corrupt-runtime degradation;</li>
 * <li>sweep: the design §6.6 five-row decision table (task missing / COMPLETE replay /
 *     FAILURE-CANCEL advance(false) / active+fresh heartbeat healthy / active+STALE heartbeat
 *     dead-judge) + the derived stale threshold + the unresolvedIntent marker;</li>
 * <li>resume: the checkpoint inheritance (done side only — cursor/batchesDone/rowsDone; the
 *     intent/heartbeat parts stripped) = the 已成功批永不重放 physical guarantee, the active-task
 *     rejection and the never-throw degradation.</li>
 * </ul>
 * Discipline: JUnit4 + hand-written mocks + ReflectionTestUtils + ArgumentCaptor; NO Spring.
 */
public class TicketSegmentServiceDmlBatchTest {

    private TicketSegmentService     service;
    private ApprovalDal              approvalDal;
    private DmApprovalMapper         approvalMapper;
    private TicketDbStmtDal          ticketDbStmtDal;
    private DmTicketDbStmtMapper     stmtMapper;
    private ExecutionDal             execDal;
    private DmExecAutoJobMapper      autoJobMapper;
    private DmExecAsyncTaskMapper    asyncTaskMapper;
    private AutoExecService          autoExecService;
    private GovTicketV2Service       govTicketV2Service;
    private GhostService             ghostService;
    private DmlBatchService          dmlBatchService;
    private SchemaSnapshotService    schemaSnapshotService;

    private static final long   TICKET_ID = 900L;
    private static final long   GROUP_ID  = 100L;
    private static final String PUID      = "puid-001";
    private static final String OWNER     = "uid-submitter";
    private static final long   DS_ID     = 20L;
    private static final String DB        = "prod_db";
    private static final long   TASK_ID   = 55L;

    private DmApprovalDO ticket;

    @Before
    public void setUp() {
        service = new TicketSegmentService();

        // DmTeamUtils static executionDal (required by nextExecJobBizId)
        ExecutionDal staticExecDal = mock(ExecutionDal.class);
        DmExecAutoJobMapper staticJobMapper = mock(DmExecAutoJobMapper.class);
        com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoTaskMapper staticTaskMapper
            = mock(com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoTaskMapper.class);
        when(staticExecDal.autoJobMapper()).thenReturn(staticJobMapper);
        when(staticExecDal.autoTaskMapper()).thenReturn(staticTaskMapper);
        when(staticJobMapper.queryByBizId(anyString())).thenReturn(null);
        when(staticTaskMapper.queryByBizId(anyString())).thenReturn(null);
        ReflectionTestUtils.setField(com.clougence.clouddm.console.web.util.DmTeamUtils.class, "executionDal", staticExecDal);

        approvalDal = mock(ApprovalDal.class);
        approvalMapper = mock(DmApprovalMapper.class);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);
        ticketDbStmtDal = mock(TicketDbStmtDal.class);
        stmtMapper = mock(DmTicketDbStmtMapper.class);
        when(ticketDbStmtDal.stmtMapper()).thenReturn(stmtMapper);
        execDal = mock(ExecutionDal.class);
        autoJobMapper = mock(DmExecAutoJobMapper.class);
        when(execDal.autoJobMapper()).thenReturn(autoJobMapper);
        asyncTaskMapper = mock(DmExecAsyncTaskMapper.class);
        when(execDal.asyncTaskMapper()).thenReturn(asyncTaskMapper);
        autoExecService = mock(AutoExecService.class);
        govTicketV2Service = mock(GovTicketV2Service.class);
        ghostService = mock(GhostService.class);
        dmlBatchService = mock(DmlBatchService.class);
        schemaSnapshotService = mock(SchemaSnapshotService.class);

        ReflectionTestUtils.setField(service, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(service, "ticketDbStmtDal", ticketDbStmtDal);
        ReflectionTestUtils.setField(service, "execDal", execDal);
        ReflectionTestUtils.setField(service, "autoExecService", autoExecService);
        ReflectionTestUtils.setField(service, "govTicketV2Service", govTicketV2Service);
        ReflectionTestUtils.setField(service, "ghostService", ghostService);
        ReflectionTestUtils.setField(service, "dmlBatchService", dmlBatchService);
        ReflectionTestUtils.setField(service, "schemaSnapshotService", schemaSnapshotService);

        ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setBizId("biz-900");
        ticket.setPrimaryUid(PUID);
        ticket.setOwnerUid(OWNER);
    }

    // ==================== fixtures ====================

    private DmTicketDbStmtDO group(String execStatus, String precheckResult, String execSegments) {
        DmTicketDbStmtDO group = new DmTicketDbStmtDO();
        group.setId(GROUP_ID);
        group.setTicketId(TICKET_ID);
        group.setDsId(DS_ID);
        group.setDbName(DB);
        group.setSqlContent("UPDATE t_order SET status = 1 WHERE status = 0");
        group.setExecStatus(execStatus);
        group.setPrecheckResult(precheckResult);
        group.setExecSegments(execSegments);
        when(stmtMapper.queryById(GROUP_ID)).thenReturn(group);
        return group;
    }

    private static String dmlBatchPlanJson() {
        return "{\"checkStatus\":\"PASS\",\"changeType\":\"DML\",\"segments\":["
            + "{\"segIdx\":1,\"type\":\"DML_BATCH\",\"stmtIndex\":1,\"table\":\"t_order\","
            + "\"estimatedRows\":5000000,\"idempotent\":false,\"sql\":\"UPDATE t_order SET status = 1 WHERE status = 0\","
            + "\"batchKey\":{\"column\":\"id\",\"keyType\":\"INT\"}}]}";
    }

    private static String mixedPlanJson() {
        return "{\"checkStatus\":\"PASS\",\"changeType\":\"MIXED\",\"segments\":["
            + "{\"segIdx\":1,\"type\":\"AUTOEXEC\",\"stmtRange\":[1,1]},"
            + "{\"segIdx\":2,\"type\":\"DML_BATCH\",\"stmtIndex\":2,\"table\":\"t_order\","
            + "\"estimatedRows\":5000000,\"idempotent\":true,\"sql\":\"DELETE FROM t_order WHERE status = 0\","
            + "\"batchKey\":{\"column\":\"id\",\"keyType\":\"INT\"}}]}";
    }

    private static String runtimeJson(TicketSegment... segments) {
        return JsonUtils.toJson(List.of(segments));
    }

    private static TicketSegment batchSegment(int segIdx, String status, Long taskId) {
        TicketSegment seg = new TicketSegment();
        seg.setSegIdx(segIdx);
        seg.setType(TicketSegment.TYPE_DML_BATCH);
        seg.setStmtIndex(segIdx);
        seg.setTable("t_order");
        seg.setSql("UPDATE t_order SET status = 1 WHERE status = 0");
        seg.setBatchKey(new TicketSegment.BatchKey("id", DmlBatchRangeSpec.KEY_TYPE_INT));
        seg.setEstimatedRows(5_000_000L);
        seg.setIdempotent(false);
        seg.setStatus(status);
        seg.setTaskId(taskId);
        return seg;
    }

    private static TicketSegment autoexecSegment(int segIdx, String status, String jobBizId) {
        TicketSegment seg = new TicketSegment();
        seg.setSegIdx(segIdx);
        seg.setType(TicketSegment.TYPE_AUTOEXEC);
        seg.setStmtRange(List.of(1, 1));
        seg.setStatus(status);
        seg.setJobBizId(jobBizId);
        return seg;
    }

    private DmExecAsyncTaskDO batchTask(AsyncTaskStatus status, DmlBatchCheckpoint checkpoint) {
        DmlBatchAsyncTaskConfig cfg = new DmlBatchAsyncTaskConfig();
        cfg.setBizId("batch-biz");
        cfg.setTicketId(TICKET_ID);
        cfg.setGroupId(GROUP_ID);
        cfg.setSegmentIndex(1);
        cfg.setUserId(OWNER);
        cfg.setDsId(DS_ID);
        cfg.setDbName(DB);
        cfg.setEffectiveParams(new DmlBatchEffectiveParams());
        cfg.setCheckpoint(checkpoint);
        DmExecAsyncTaskDO task = new DmExecAsyncTaskDO();
        task.setId(TASK_ID);
        task.setBizId("batch-biz");
        task.setBizType(DmlBatchService.BIZ_TYPE);
        task.setStatus(status);
        task.setUid(OWNER);
        task.setConfigData(JsonUtils.toJson(cfg));
        return task;
    }

    // ==================== dispatch seam ====================

    @Test
    public void dispatchFirstSegment_dmlBatchOnlyPlan_dispatchesBatchSegment_guardWidened() {
        // the B6 guard was hasGhostSegment — a DML_BATCH-only plan must pass (hasRoutedSegment)
        DmTicketDbStmtDO group = group("PENDING", dmlBatchPlanJson(), null);
        org.mockito.Mockito.doAnswer(inv -> {
            inv.getArgument(2, TicketSegment.class).setTaskId(TASK_ID); // the launch records the binding
            return null;
        }).when(govTicketV2Service).dispatchBatchSegment(any(), any(), any(), anyString());

        service.dispatchFirstSegment(ticket, group, false, ErrorStrategy.NONE, "zh-CN", "SYSTEM");

        verify(govTicketV2Service).dispatchBatchSegment(same(ticket), same(group), any(TicketSegment.class),
            eq(GovTicketV2Service.GHOST_VIA_AUTO_ROUTE));
        verify(autoExecService, never()).createGroupJob(any(), anyString(), anyBoolean(), any(), anyString(), anyString());
        // the runtime is rebuilt from the plan and persisted WITH the recorded taskId
        ArgumentCaptor<String> runtimeCaptor = ArgumentCaptor.forClass(String.class);
        verify(stmtMapper, org.mockito.Mockito.atLeastOnce()).updateExecSegments(eq(GROUP_ID), runtimeCaptor.capture());
        List<TicketSegment> runtime = TicketSegment.parseRuntime(runtimeCaptor.getValue());
        assertEquals(1, runtime.size());
        assertEquals(TicketSegment.TYPE_DML_BATCH, runtime.get(0).getType());
        assertEquals(TicketSegment.STATUS_EXECUTING, runtime.get(0).getStatus());
        assertEquals(Long.valueOf(TASK_ID), runtime.get(0).getTaskId());
        // the plan freeze survives into the runtime (batchKey/estimatedRows/idempotent/sql)
        assertEquals("id", runtime.get(0).getBatchKey().getColumn());
        assertEquals(Long.valueOf(5_000_000L), runtime.get(0).getEstimatedRows());
        assertEquals(Boolean.FALSE, runtime.get(0).getIdempotent());
    }

    @Test
    public void dispatchFirstSegment_autoexecFirst_batchLater_createsJobOnly() {
        DmTicketDbStmtDO group = group("PENDING", mixedPlanJson(), null);

        service.dispatchFirstSegment(ticket, group, true, ErrorStrategy.NONE, "zh-CN", "SYSTEM");

        // the AUTOEXEC first segment keeps the legacy job build; the batch dispatch happens at chain time
        verify(autoExecService).createGroupJob(same(group), anyString(), eq(true), eq(ErrorStrategy.NONE),
            eq("zh-CN"), eq("SYSTEM"));
        verify(autoExecService).startJob(anyString(), eq("SYSTEM"));
        verify(govTicketV2Service, never()).dispatchBatchSegment(any(), any(), any(), anyString());
    }

    @Test
    public void dispatchFirstSegment_batchSubmitFails_throws_groupStaysForSweepRedispatch() {
        DmTicketDbStmtDO group = group("PENDING", dmlBatchPlanJson(), null);
        org.mockito.Mockito.doThrow(new IllegalStateException("task store down"))
            .when(govTicketV2Service).dispatchBatchSegment(any(), any(), any(), anyString());

        try {
            service.dispatchFirstSegment(ticket, group, false, ErrorStrategy.NONE, "zh-CN", "SYSTEM");
            fail("expected the submitTask failure to propagate (no execution body residue)");
        } catch (IllegalStateException expected) {
            assertEquals("task store down", expected.getMessage());
        }
    }

    // ==================== advance seam: completeDmlBatchSegmentUnderLock CAS ====================

    @Test
    public void completeDmlBatch_exactBindingAndTaskId_advances_summaryFrozen_groupTerminalWhenLast() {
        TicketSegment seg = batchSegment(1, TicketSegment.STATUS_EXECUTING, TASK_ID);
        DmTicketDbStmtDO group = group("EXECUTING", dmlBatchPlanJson(),
            runtimeJson(seg));
        Map<String, Object> summary = Map.of("batchesDone", 10L, "rowsDone", 50000L, "cursor", "42");

        boolean advanced = service.completeDmlBatchSegmentUnderLock(ticket, group, 1, TASK_ID, true, null, summary);

        assertTrue(advanced);
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(stmtMapper).updateExecSegments(eq(GROUP_ID), captor.capture());
        List<TicketSegment> runtime = TicketSegment.parseRuntime(captor.getValue());
        assertEquals(TicketSegment.STATUS_SUCCESS, runtime.get(0).getStatus());
        // (the summary rides the JSON round trip — small longs re-parse as Integers)
        assertEquals(10, ((Number) runtime.get(0).getSummary().get("batchesDone")).intValue());
        assertEquals("42", runtime.get(0).getSummary().get("cursor"));
        // last segment → the GROUP terminal write (组终态 = 全段终态)
        verify(stmtMapper).updateExecStatus(GROUP_ID, "SUCCESS", null);
    }

    @Test
    public void completeDmlBatch_staleTaskId_skipped_debt5CrossCheck() {
        // the segment was REBUILT by retryGroup (fresh taskId 77) — the stale terminal task 55
        // must NEVER write through the fresh dispatch's EXECUTING CAS (debt #5 discharged)
        TicketSegment seg = batchSegment(1, TicketSegment.STATUS_EXECUTING, 77L);
        DmTicketDbStmtDO group = group("EXECUTING", dmlBatchPlanJson(), runtimeJson(seg));

        boolean advanced = service.completeDmlBatchSegmentUnderLock(ticket, group, 1, TASK_ID, true, null, null);

        assertFalse(advanced);
        verify(stmtMapper, never()).updateExecSegments(anyLong(), anyString());
        verify(stmtMapper, never()).updateExecStatus(anyLong(), anyString(), any());
    }

    @Test
    public void completeDmlBatch_missingTaskIdEitherSide_skipped() {
        TicketSegment noRecorded = batchSegment(1, TicketSegment.STATUS_EXECUTING, null);
        DmTicketDbStmtDO group = group("EXECUTING", dmlBatchPlanJson(), runtimeJson(noRecorded));

        assertFalse(service.completeDmlBatchSegmentUnderLock(ticket, group, 1, TASK_ID, true, null, null));

        TicketSegment recorded = batchSegment(1, TicketSegment.STATUS_EXECUTING, TASK_ID);
        DmTicketDbStmtDO group2 = group("EXECUTING", dmlBatchPlanJson(), runtimeJson(recorded));
        assertFalse(service.completeDmlBatchSegmentUnderLock(ticket, group2, 1, null, true, null, null));
        verify(stmtMapper, never()).updateExecSegments(anyLong(), anyString());
    }

    @Test
    public void completeDmlBatch_replayAfterTerminal_casSkip() {
        TicketSegment seg = batchSegment(1, TicketSegment.STATUS_SUCCESS, TASK_ID);
        DmTicketDbStmtDO group = group("SUCCESS", dmlBatchPlanJson(), runtimeJson(seg));

        assertFalse("a replayed hook never re-advances a terminal segment",
            service.completeDmlBatchSegmentUnderLock(ticket, group, 1, TASK_ID, true, null, null));
        verify(stmtMapper, never()).updateExecSegments(anyLong(), anyString());
    }

    @Test
    public void completeDmlBatch_typeMismatchAtIndex_skipped() {
        // segmentIndex 1 binds a GHOST segment — a batch hook must not advance it
        TicketSegment ghost = new TicketSegment();
        ghost.setSegIdx(1);
        ghost.setType(TicketSegment.TYPE_GHOST);
        ghost.setStatus(TicketSegment.STATUS_EXECUTING);
        ghost.setTaskId(TASK_ID);
        DmTicketDbStmtDO group = group("EXECUTING", "{}", runtimeJson(ghost));

        assertFalse(service.completeDmlBatchSegmentUnderLock(ticket, group, 1, TASK_ID, true, null, null));
    }

    @Test
    public void completeDmlBatch_nullSegmentIndex_shapeFallbackToExecutingBatchSegment() {
        TicketSegment seg = batchSegment(1, TicketSegment.STATUS_EXECUTING, TASK_ID);
        DmTicketDbStmtDO group = group("EXECUTING", dmlBatchPlanJson(), runtimeJson(seg));

        assertTrue(service.completeDmlBatchSegmentUnderLock(ticket, group, null, TASK_ID, true, null, null));
        verify(stmtMapper).updateExecStatus(GROUP_ID, "SUCCESS", null);
    }

    @Test
    public void completeDmlBatch_failure_segmentFailed_groupFailed_downstreamStaysPending() {
        TicketSegment seg1 = batchSegment(1, TicketSegment.STATUS_EXECUTING, TASK_ID);
        TicketSegment seg2 = autoexecSegment(2, TicketSegment.STATUS_PENDING, null);
        DmTicketDbStmtDO group = group("EXECUTING", mixedPlanJson(), runtimeJson(seg1, seg2));

        boolean advanced = service.completeDmlBatchSegmentUnderLock(ticket, group, 1, TASK_ID, false,
            "批 3 执行结果未知", Map.of("unresolvedIntent", Boolean.TRUE));

        assertTrue(advanced);
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(stmtMapper).updateExecSegments(eq(GROUP_ID), captor.capture());
        List<TicketSegment> runtime = TicketSegment.parseRuntime(captor.getValue());
        assertEquals(TicketSegment.STATUS_FAILED, runtime.get(0).getStatus());
        assertEquals("批 3 执行结果未知", runtime.get(0).getError());
        assertEquals(Boolean.TRUE, runtime.get(0).getSummary().get("unresolvedIntent"));
        assertEquals(TicketSegment.STATUS_PENDING, runtime.get(1).getStatus()); // downstream untouched
        verify(stmtMapper).updateExecStatus(GROUP_ID, "FAILED", "批 3 执行结果未知");
        verify(govTicketV2Service, never()).dispatchBatchSegment(any(), any(), any(), anyString());
    }

    @Test
    public void completeDmlBatch_successWithNextPending_chainsTheNextSegmentDispatch() {
        TicketSegment seg1 = batchSegment(1, TicketSegment.STATUS_EXECUTING, TASK_ID);
        TicketSegment seg2 = autoexecSegment(2, TicketSegment.STATUS_PENDING, null);
        DmTicketDbStmtDO group = group("EXECUTING", mixedPlanJson(), runtimeJson(seg1, seg2));

        assertTrue(service.completeDmlBatchSegmentUnderLock(ticket, group, 1, TASK_ID, true, null, null));

        // the chained AUTOEXEC dispatch (advanceUnderLock core reused with ZERO change)
        verify(autoExecService).createGroupJob(same(group), anyString(), anyBoolean(), any(), anyString(), anyString());
        verify(autoExecService).startJob(anyString(), anyString());
        verify(stmtMapper, never()).updateExecStatus(anyLong(), anyString(), any()); // no group terminal mid-chain
    }

    @Test
    public void completeDmlBatch_autoexecSuccess_chainsIntoBatchSegment() {
        TicketSegment seg1 = autoexecSegment(1, TicketSegment.STATUS_EXECUTING, "job-1");
        TicketSegment seg2 = batchSegment(2, TicketSegment.STATUS_PENDING, null);
        DmTicketDbStmtDO group = group("EXECUTING", mixedPlanJson(), runtimeJson(seg1, seg2));

        assertTrue(service.completeAutoexecSegmentUnderLock(ticket, GROUP_ID, "job-1", true, null, "SYSTEM"));

        verify(govTicketV2Service).dispatchBatchSegment(same(ticket), same(group), any(TicketSegment.class),
            eq(GovTicketV2Service.GHOST_VIA_SEGMENT_CHAIN));
    }

    @Test
    public void completeDmlBatch_corruptRuntime_legacyFallbackWrite() {
        DmTicketDbStmtDO group = group("EXECUTING", dmlBatchPlanJson(), "{corrupt");

        assertTrue(service.completeDmlBatchSegmentUnderLock(ticket, group, 1, TASK_ID, true, null, null));
        verify(stmtMapper).updateExecStatus(GROUP_ID, "SUCCESS", null);
    }

    // ==================== sweep repair: the design §6.6 five-row decision table ====================

    @Test
    public void repair_taskMissing_failSegment_noGhostSessionConsulted() {
        TicketSegment seg = batchSegment(1, TicketSegment.STATUS_EXECUTING, TASK_ID);
        DmTicketDbStmtDO group = group("EXECUTING", dmlBatchPlanJson(), runtimeJson(seg));
        when(dmlBatchService.findTaskForTicketGroup(TICKET_ID, GROUP_ID, 1)).thenReturn(null);

        service.repairGroupUnderLock(ticket, group);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(stmtMapper).updateExecSegments(eq(GROUP_ID), captor.capture());
        assertEquals(TicketSegment.STATUS_FAILED, TicketSegment.parseRuntime(captor.getValue()).get(0).getStatus());
        verify(stmtMapper).updateExecStatus(eq(GROUP_ID), eq("FAILED"),
            org.mockito.ArgumentMatchers.contains("task record missing"));
        verifyNoInteractions(ghostService); // a DML_BATCH repair never touches the ghost surface
    }

    @Test
    public void repair_taskCompleteWritebackLost_replaysTheLostCompletion() {
        TicketSegment seg = batchSegment(1, TicketSegment.STATUS_EXECUTING, TASK_ID);
        DmTicketDbStmtDO group = group("EXECUTING", dmlBatchPlanJson(), runtimeJson(seg));
        DmlBatchCheckpoint cp = new DmlBatchCheckpoint();
        cp.setCursor("99");
        cp.setBatchesDone(20);
        cp.setRowsDone(100000);
        when(dmlBatchService.findTaskForTicketGroup(TICKET_ID, GROUP_ID, 1))
            .thenReturn(batchTask(AsyncTaskStatus.COMPLETE, cp));

        service.repairGroupUnderLock(ticket, group);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(stmtMapper).updateExecSegments(eq(GROUP_ID), captor.capture());
        List<TicketSegment> runtime = TicketSegment.parseRuntime(captor.getValue());
        assertEquals(TicketSegment.STATUS_SUCCESS, runtime.get(0).getStatus());
        assertEquals(20, ((Number) runtime.get(0).getSummary().get("batchesDone")).intValue()); // the replay carries the checkpoint trail
        assertEquals("99", runtime.get(0).getSummary().get("cursor"));
        verify(stmtMapper).updateExecStatus(GROUP_ID, "SUCCESS", null);
    }

    @Test
    public void repair_taskFailureOrCancel_advancesFailed() {
        TicketSegment seg = batchSegment(1, TicketSegment.STATUS_EXECUTING, TASK_ID);
        DmTicketDbStmtDO group = group("EXECUTING", dmlBatchPlanJson(), runtimeJson(seg));
        when(dmlBatchService.findTaskForTicketGroup(TICKET_ID, GROUP_ID, 1))
            .thenReturn(batchTask(AsyncTaskStatus.FAILURE, new DmlBatchCheckpoint()));

        service.repairGroupUnderLock(ticket, group);

        verify(stmtMapper).updateExecStatus(eq(GROUP_ID), eq("FAILED"),
            org.mockito.ArgumentMatchers.contains("FAILURE"));
    }

    @Test
    public void repair_activeTaskFreshHeartbeat_healthyNoop_coversPausedAndRetrying() {
        TicketSegment seg = batchSegment(1, TicketSegment.STATUS_EXECUTING, TASK_ID);
        seg.setStartedAt(System.currentTimeMillis() - 60_000L);
        DmTicketDbStmtDO group = group("EXECUTING", dmlBatchPlanJson(), runtimeJson(seg));
        DmlBatchCheckpoint cp = new DmlBatchCheckpoint();
        cp.setLastTickAt(System.currentTimeMillis() - 5_000L); // fresh (threshold ≈ 380s)
        cp.setLastStatus("PAUSED"); // 暂停中心跳照写 → the sweep must NOT misjudge
        when(dmlBatchService.findTaskForTicketGroup(TICKET_ID, GROUP_ID, 1))
            .thenReturn(batchTask(AsyncTaskStatus.PAUSE, cp));

        service.repairGroupUnderLock(ticket, group);

        verify(stmtMapper, never()).updateExecSegments(anyLong(), anyString());
        verify(stmtMapper, never()).updateExecStatus(anyLong(), anyString(), any());
    }

    @Test
    public void repair_activeTaskStaleHeartbeat_deadJudged_checkpointTrailFrozen() {
        TicketSegment seg = batchSegment(1, TicketSegment.STATUS_EXECUTING, TASK_ID);
        DmTicketDbStmtDO group = group("EXECUTING", dmlBatchPlanJson(), runtimeJson(seg));
        DmlBatchCheckpoint cp = new DmlBatchCheckpoint();
        cp.setCursor("77");
        cp.setBatchesDone(15);
        cp.setLastTickAt(System.currentTimeMillis() - 400_000L); // stale: > 300s + 2×10s + 60s
        when(dmlBatchService.findTaskForTicketGroup(TICKET_ID, GROUP_ID, 1))
            .thenReturn(batchTask(AsyncTaskStatus.RUNNING, cp));

        service.repairGroupUnderLock(ticket, group);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(stmtMapper).updateExecSegments(eq(GROUP_ID), captor.capture());
        List<TicketSegment> runtime = TicketSegment.parseRuntime(captor.getValue());
        assertEquals(TicketSegment.STATUS_FAILED, runtime.get(0).getStatus());
        assertEquals("77", runtime.get(0).getSummary().get("cursor")); // retryGroup inheritance trail
        assertEquals(15, ((Number) runtime.get(0).getSummary().get("batchesDone")).intValue());
        assertNull("no unresolvedIntent marker without an intent", runtime.get(0).getSummary().get("unresolvedIntent"));
    }

    @Test
    public void repair_staleWithUnresolvedIntent_markerFrozenForN2Guard() {
        TicketSegment seg = batchSegment(1, TicketSegment.STATUS_EXECUTING, TASK_ID);
        DmTicketDbStmtDO group = group("EXECUTING", dmlBatchPlanJson(), runtimeJson(seg));
        DmlBatchCheckpoint cp = new DmlBatchCheckpoint();
        DmlBatchCheckpoint.Intent intent = new DmlBatchCheckpoint.Intent();
        intent.setSeq(6L); // intent ∧ ¬retryPending = the window crash
        cp.setIntent(intent);
        cp.setLastTickAt(System.currentTimeMillis() - 400_000L);
        when(dmlBatchService.findTaskForTicketGroup(TICKET_ID, GROUP_ID, 1))
            .thenReturn(batchTask(AsyncTaskStatus.RUNNING, cp));

        service.repairGroupUnderLock(ticket, group);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(stmtMapper).updateExecSegments(eq(GROUP_ID), captor.capture());
        Map<String, Object> summary = TicketSegment.parseRuntime(captor.getValue()).get(0).getSummary();
        assertEquals(Boolean.TRUE, summary.get("unresolvedIntent"));
        assertEquals(6, ((Number) summary.get("intentSeq")).intValue());
    }

    @Test
    public void repair_staleWithRetryPendingIntent_noUnresolvedMarker_zeroEffectProven() {
        TicketSegment seg = batchSegment(1, TicketSegment.STATUS_EXECUTING, TASK_ID);
        DmTicketDbStmtDO group = group("EXECUTING", dmlBatchPlanJson(), runtimeJson(seg));
        DmlBatchCheckpoint cp = new DmlBatchCheckpoint();
        DmlBatchCheckpoint.Intent intent = new DmlBatchCheckpoint.Intent();
        intent.setSeq(6L);
        intent.setRetryPending(true); // 裁决 #21: a recorded DB error proves zero effect
        cp.setIntent(intent);
        cp.setLastTickAt(System.currentTimeMillis() - 400_000L);
        when(dmlBatchService.findTaskForTicketGroup(TICKET_ID, GROUP_ID, 1))
            .thenReturn(batchTask(AsyncTaskStatus.RUNNING, cp));

        service.repairGroupUnderLock(ticket, group);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(stmtMapper).updateExecSegments(eq(GROUP_ID), captor.capture());
        Map<String, Object> summary = TicketSegment.parseRuntime(captor.getValue()).get(0).getSummary();
        assertNull(summary.get("unresolvedIntent"));
    }

    @Test
    public void repair_noHeartbeatYet_fallsBackToSegmentStartTime() {
        // a console that died between the dispatch commit and the first tick still converges
        TicketSegment seg = batchSegment(1, TicketSegment.STATUS_EXECUTING, TASK_ID);
        seg.setStartedAt(System.currentTimeMillis() - 400_000L); // dispatched long ago, never ticked
        DmTicketDbStmtDO group = group("EXECUTING", dmlBatchPlanJson(), runtimeJson(seg));
        when(dmlBatchService.findTaskForTicketGroup(TICKET_ID, GROUP_ID, 1))
            .thenReturn(batchTask(AsyncTaskStatus.WAIT_START, new DmlBatchCheckpoint()));

        service.repairGroupUnderLock(ticket, group);

        verify(stmtMapper).updateExecStatus(eq(GROUP_ID), eq("FAILED"), anyString());
    }

    @Test
    public void repair_noHeartbeat_recentDispatch_healthy() {
        TicketSegment seg = batchSegment(1, TicketSegment.STATUS_EXECUTING, TASK_ID);
        seg.setStartedAt(System.currentTimeMillis() - 5_000L);
        DmTicketDbStmtDO group = group("EXECUTING", dmlBatchPlanJson(), runtimeJson(seg));
        when(dmlBatchService.findTaskForTicketGroup(TICKET_ID, GROUP_ID, 1))
            .thenReturn(batchTask(AsyncTaskStatus.INIT, new DmlBatchCheckpoint()));

        service.repairGroupUnderLock(ticket, group);

        verify(stmtMapper, never()).updateExecStatus(anyLong(), anyString(), any());
    }

    @Test
    public void staleThreshold_derivedFormula_noNewKv() {
        DmlBatchAsyncTaskConfig cfg = new DmlBatchAsyncTaskConfig();
        DmlBatchEffectiveParams params = new DmlBatchEffectiveParams();
        params.setTimeoutSec(60);
        params.setIntervalMs(500); // below the 10s floor → the floor applies
        cfg.setEffectiveParams(params);

        // timeoutSec×1000 + max(intervalMs,10s)×2 + 60s = 60000 + 20000 + 60000
        assertEquals(140_000L, TicketSegmentService.dmlBatchStaleThresholdMillis(cfg));
        // a missing/corrupt carrier falls back to the defaults (300s + 2×10s + 60s)
        assertEquals(380_000L, TicketSegmentService.dmlBatchStaleThresholdMillis(null));
        assertEquals(380_000L, TicketSegmentService.dmlBatchStaleThresholdMillis(new DmlBatchAsyncTaskConfig()));
    }

    // ==================== retryGroup resume: checkpoint inheritance ====================

    @Test
    public void resume_failedBatchSegment_inheritsDoneSideCursor_stripsIntent() {
        TicketSegment seg = batchSegment(1, TicketSegment.STATUS_FAILED, TASK_ID);
        seg.setError("批 3 执行结果未知");
        DmTicketDbStmtDO group = group("FAILED", dmlBatchPlanJson(), runtimeJson(seg));
        DmlBatchCheckpoint cp = new DmlBatchCheckpoint();
        cp.setCursor("99");
        cp.setBatchesDone(20);
        cp.setRowsDone(100000);
        DmlBatchCheckpoint.Intent intent = new DmlBatchCheckpoint.Intent();
        intent.setSeq(21L);
        cp.setIntent(intent); // an unresolved intent — the N1b basic resume strips it (N2 guards)
        cp.setLastTickAt(123L);
        cp.setLastStatus("UNRESOLVED");
        when(asyncTaskMapper.queryById(TASK_ID)).thenReturn(batchTask(AsyncTaskStatus.FAILURE, cp));
        when(autoJobMapper.queryByDependOnGroupId(GROUP_ID)).thenReturn(null);

        boolean groupTerminal = service.resumeGroupUnderLock(ticket, GROUP_ID, "uid-retry");

        assertFalse(groupTerminal);
        // the fresh dispatch carries the DONE-side inheritance (已成功批永不重放 = the cursor)
        ArgumentCaptor<TicketSegment> segCaptor = ArgumentCaptor.forClass(TicketSegment.class);
        verify(govTicketV2Service).dispatchBatchSegment(same(ticket), same(group), segCaptor.capture(),
            eq(GovTicketV2Service.GHOST_VIA_RETRY));
        Map<String, Object> inherited = segCaptor.getValue().getResumeCheckpoint();
        assertNotNull(inherited);
        assertEquals("99", inherited.get("cursor"));
        assertEquals(20, ((Number) inherited.get("batchesDone")).intValue());
        assertEquals(100000, ((Number) inherited.get("rowsDone")).intValue());
        assertFalse("the intent side is NEVER inherited", inherited.containsKey("intent"));
        assertFalse(inherited.containsKey("lastTickAt"));
        assertFalse(inherited.containsKey("lastStatus"));
        // the reset cleared the stale bindings, then re-marked EXECUTING (the group-row EXECUTING
        // write itself lives inside dispatchBatchSegment — mocked away here, pinned in
        // DmlBatchDispatchTest)
        assertEquals(TicketSegment.STATUS_EXECUTING, segCaptor.getValue().getStatus());
        assertNull(segCaptor.getValue().getError());
        assertNull(segCaptor.getValue().getTaskId()); // the fresh task records its own id
    }

    @Test
    public void resume_activeBatchTask_rejected() {
        TicketSegment seg = batchSegment(1, TicketSegment.STATUS_FAILED, TASK_ID);
        DmTicketDbStmtDO group = group("FAILED", dmlBatchPlanJson(), runtimeJson(seg));
        when(asyncTaskMapper.queryById(TASK_ID)).thenReturn(batchTask(AsyncTaskStatus.RUNNING, new DmlBatchCheckpoint()));
        when(autoJobMapper.queryByDependOnGroupId(GROUP_ID)).thenReturn(null);

        try {
            service.resumeGroupUnderLock(ticket, GROUP_ID, "uid-retry");
            fail("expected the active batch task to reject the retry");
        } catch (ErrorMessageException expected) {
            assertNotNull(expected.getMessage());
        }
        verify(govTicketV2Service, never()).dispatchBatchSegment(any(), any(), any(), anyString());
    }

    @Test
    public void resume_oldTaskMissing_freshCursor_noThrow() {
        TicketSegment seg = batchSegment(1, TicketSegment.STATUS_FAILED, TASK_ID);
        DmTicketDbStmtDO group = group("FAILED", dmlBatchPlanJson(), runtimeJson(seg));
        when(asyncTaskMapper.queryById(TASK_ID)).thenReturn(null);
        when(dmlBatchService.findTaskForTicketGroup(TICKET_ID, GROUP_ID, 1)).thenReturn(null);
        when(autoJobMapper.queryByDependOnGroupId(GROUP_ID)).thenReturn(null);

        assertFalse(service.resumeGroupUnderLock(ticket, GROUP_ID, "uid-retry"));

        ArgumentCaptor<TicketSegment> segCaptor = ArgumentCaptor.forClass(TicketSegment.class);
        verify(govTicketV2Service).dispatchBatchSegment(any(), any(), segCaptor.capture(), anyString());
        assertNull("no old task → no inheritance → a fresh cursor", segCaptor.getValue().getResumeCheckpoint());
    }

    @Test
    public void resume_oldTaskConfigUnparsable_degradesToFreshCursor_neverThrows() {
        TicketSegment seg = batchSegment(1, TicketSegment.STATUS_FAILED, TASK_ID);
        DmTicketDbStmtDO group = group("FAILED", dmlBatchPlanJson(), runtimeJson(seg));
        DmExecAsyncTaskDO corrupt = new DmExecAsyncTaskDO();
        corrupt.setId(TASK_ID);
        corrupt.setStatus(AsyncTaskStatus.FAILURE);
        corrupt.setConfigData("{corrupt");
        when(asyncTaskMapper.queryById(TASK_ID)).thenReturn(corrupt);
        when(autoJobMapper.queryByDependOnGroupId(GROUP_ID)).thenReturn(null);

        assertFalse(service.resumeGroupUnderLock(ticket, GROUP_ID, "uid-retry"));

        ArgumentCaptor<TicketSegment> segCaptor = ArgumentCaptor.forClass(TicketSegment.class);
        verify(govTicketV2Service).dispatchBatchSegment(any(), any(), segCaptor.capture(), anyString());
        assertNull(segCaptor.getValue().getResumeCheckpoint());
    }

    @Test
    public void resume_pendingRuntimeNull_dmlBatchOnlyPlan_reinitializesAndDispatches() {
        // check-fix pin (B7): the runtime-null pre-first-dispatch branch keys on the WIDENED
        // hasRoutedSegment — a DML_BATCH-only group whose first dispatch rolled back must
        // restart safely instead of dead-ending in RETRY_UNSUPPORTED (the sweep only probes
        // EXECUTING groups and returnTicket requires a FAILED group, so without this branch
        // the group would have NO recovery surface at all)
        DmTicketDbStmtDO group = group("PENDING", dmlBatchPlanJson(), null);
        when(autoJobMapper.queryByDependOnGroupId(GROUP_ID)).thenReturn(null);

        assertFalse(service.resumeGroupUnderLock(ticket, GROUP_ID, "uid-retry"));

        verify(govTicketV2Service).dispatchBatchSegment(same(ticket), same(group),
            any(TicketSegment.class), eq(GovTicketV2Service.GHOST_VIA_RETRY));
        ArgumentCaptor<String> runtimeCaptor = ArgumentCaptor.forClass(String.class);
        verify(stmtMapper, org.mockito.Mockito.atLeastOnce())
            .updateExecSegments(eq(GROUP_ID), runtimeCaptor.capture());
        List<TicketSegment> runtime = TicketSegment.parseRuntime(runtimeCaptor.getValue());
        assertEquals(1, runtime.size());
        assertEquals(TicketSegment.TYPE_DML_BATCH, runtime.get(0).getType());
        assertEquals(TicketSegment.STATUS_EXECUTING, runtime.get(0).getStatus());
    }

    // ==================== static helpers ====================

    @Test
    public void routedSegmentPredicates_widenedForDmlBatch() {
        List<TicketSegment> batchOnly = List.of(batchSegment(1, TicketSegment.STATUS_PENDING, null));
        assertTrue(TicketSegment.hasDmlBatchSegment(batchOnly));
        assertTrue(TicketSegment.hasRoutedSegment(batchOnly));
        assertFalse(TicketSegment.hasGhostSegment(batchOnly));
        assertTrue(TicketSegment.isSegmentedPlan(dmlBatchPlanJson()));
        // a pure AUTOEXEC plan (never written by the builder, but parse-defensively) is NOT segmented
        assertFalse(TicketSegment.isSegmentedPlan(
            "{\"segments\":[{\"segIdx\":1,\"type\":\"AUTOEXEC\",\"stmtRange\":[1,2]}]}"));
        assertFalse(TicketSegment.isSegmentedPlan("{\"checkStatus\":\"PASS\"}"));
    }

    @Test
    public void unresolvedIntentPredicate_narrowedByRetryPending() {
        DmlBatchCheckpoint cp = new DmlBatchCheckpoint();
        assertFalse(TicketSegmentService.isUnresolvedIntent(cp));
        assertFalse(TicketSegmentService.isUnresolvedIntent(null));
        DmlBatchCheckpoint.Intent intent = new DmlBatchCheckpoint.Intent();
        cp.setIntent(intent);
        assertTrue(TicketSegmentService.isUnresolvedIntent(cp));
        intent.setRetryPending(true);
        assertFalse("retryPending = zero effect proven → NOT a window crash (裁决 #21)",
            TicketSegmentService.isUnresolvedIntent(cp));
    }
}
