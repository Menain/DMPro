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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

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
import com.clougence.clouddm.platform.dal.model.execution.AutoExecJobStatus;
import com.clougence.clouddm.platform.dal.model.execution.DmExecAsyncTaskDO;
import com.clougence.clouddm.platform.dal.model.execution.DmExecAutoJobDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;
import com.clougence.clouddm.sdk.sql.editor.rewrite.DmlBatchRangeSpec;
import com.clougence.utils.JsonUtils;

/**
 * B7 N3 (implement.md N3 「混合矩阵钉」, design §9 PRE 混合组矩阵 / 裁决 #7): the THREE-segment-type
 * mixed group — a PRE group [普通语句, 大 DML, 大 ALTER] = AUTOEXEC + DML_BATCH + GHOST serially
 * advanced by the UNCHANGED B6 orchestration core (advanceUnderLock is type-transparent; these
 * pins are the R7 disposition proof: 判定表乘维 without touching the frozen seams). Coverage:
 * <ul>
 * <li>serial progression permutations to the group terminal (dispatch → complete → chain →
 *     complete → chain → complete → group SUCCESS), with the per-type dispatch targets and the
 *     runtime/summary trail;</li>
 * <li>mixed failure: a mid-chain DML_BATCH failure leaves downstream GHOST PENDING and writes the
 *     group FAILED (no cross-type dispatch after a failure);</li>
 * <li>transactional force (裁决 #11/A7): a DML_BATCH-carrying plan NEVER runs its AUTOEXEC
 *     segments transactionally — first dispatch AND the mid-chain resolveTransactional path —
 *     while a GHOST-only plan keeps the B6 pass-through byte-identical;</li>
 * <li>resume cross-active matrix: an active GHOST task / in-flight autoexec job rejects a
 *     DML_BATCH resume, an active DML_BATCH task rejects a GHOST resume (the extended 任一段
 *     ruling), and an earlier AUTOEXEC segment's FINISH job remnant never blocks;</li>
 * <li>sweep repair over a mixed runtime probes the CURRENT segment by type (the decision table's
 *     per-type liveness rows).</li>
 * </ul>
 * Discipline: JUnit4 + handwritten mocks + ReflectionTestUtils (the TicketSegmentServiceDmlBatchTest
 * harness pattern); the B6 frozen suites (TicketSegmentServiceTest 46) stay zero-change.
 */
public class TicketSegmentMixedMatrixTest {

    private TicketSegmentService   service;
    private DmApprovalMapper       approvalMapper;
    private DmTicketDbStmtMapper   stmtMapper;
    private DmExecAutoJobMapper    autoJobMapper;
    private DmExecAsyncTaskMapper  asyncTaskMapper;
    private AutoExecService        autoExecService;
    private GovTicketV2Service     govTicketV2Service;
    private GhostService           ghostService;
    private DmlBatchService        dmlBatchService;
    private SchemaSnapshotService  schemaSnapshotService;

    private static final long   TICKET_ID    = 900L;
    private static final long   GROUP_ID     = 100L;
    private static final String PUID         = "puid-001";
    private static final String OWNER        = "uid-submitter";
    private static final long   DS_ID        = 20L;
    private static final String DB           = "pre_db";
    private static final long   BATCH_TASK   = 55L;
    private static final long   GHOST_TASK   = 56L;
    private static final String GHOST_SESS   = "sess-ghost-1";

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

        ApprovalDal approvalDal = mock(ApprovalDal.class);
        approvalMapper = mock(DmApprovalMapper.class);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);
        TicketDbStmtDal ticketDbStmtDal = mock(TicketDbStmtDal.class);
        stmtMapper = mock(DmTicketDbStmtMapper.class);
        when(ticketDbStmtDal.stmtMapper()).thenReturn(stmtMapper);
        ExecutionDal execDal = mock(ExecutionDal.class);
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

        // the launches record their bindings on the segment (the production dispatch contract)
        org.mockito.Mockito.doAnswer(inv -> {
            inv.getArgument(2, TicketSegment.class).setTaskId(BATCH_TASK);
            return null;
        }).when(govTicketV2Service).dispatchBatchSegment(any(), any(), any(), anyString());
        org.mockito.Mockito.doAnswer(inv -> {
            TicketSegment seg = inv.getArgument(2, TicketSegment.class);
            seg.setSessionId(GHOST_SESS);
            seg.setTaskId(GHOST_TASK);
            return null;
        }).when(govTicketV2Service).dispatchGhostSegment(any(), any(), any(), anyString());
    }

    // ==================== fixtures ====================

    private DmTicketDbStmtDO group(String execStatus, String precheckResult, String execSegments) {
        DmTicketDbStmtDO group = new DmTicketDbStmtDO();
        group.setId(GROUP_ID);
        group.setTicketId(TICKET_ID);
        group.setDsId(DS_ID);
        group.setDbName(DB);
        group.setSqlContent("UPDATE t_small SET a = 1 WHERE id = 1;\n"
            + "DELETE FROM t_order WHERE status = 0;\n"
            + "ALTER TABLE t_big ADD COLUMN c1 INT;");
        group.setExecStatus(execStatus);
        group.setPrecheckResult(precheckResult);
        group.setExecSegments(execSegments);
        when(stmtMapper.queryById(GROUP_ID)).thenReturn(group);
        return group;
    }

    /** PRE 组 [普通, 大 DML, 大 ALTER] → [AUTOEXEC[1,1], DML_BATCH(2), GHOST(3)] (裁决 #7). */
    private static String threeTypePlanJson() {
        return "{\"checkStatus\":\"PASS\",\"changeType\":\"MIXED\",\"segments\":["
            + "{\"segIdx\":1,\"type\":\"AUTOEXEC\",\"stmtRange\":[1,1]},"
            + "{\"segIdx\":2,\"type\":\"DML_BATCH\",\"stmtIndex\":2,\"table\":\"t_order\","
            + "\"estimatedRows\":5000000,\"idempotent\":true,\"sql\":\"DELETE FROM t_order WHERE status = 0\","
            + "\"batchKey\":{\"column\":\"id\",\"keyType\":\"INT\"}},"
            + "{\"segIdx\":3,\"type\":\"GHOST\",\"stmtIndex\":3,\"table\":\"t_big\",\"rows\":20000000,"
            + "\"sql\":\"ALTER TABLE t_big ADD COLUMN c1 INT\"}]}";
    }

    /** PROD_DML-shaped plan [小 DML, 大 DML] → [AUTOEXEC[1,1], DML_BATCH(2)], changeType=DML. */
    private static String dmlChangeTypePlanJson() {
        return "{\"checkStatus\":\"PASS\",\"changeType\":\"DML\",\"segments\":["
            + "{\"segIdx\":1,\"type\":\"AUTOEXEC\",\"stmtRange\":[1,1]},"
            + "{\"segIdx\":2,\"type\":\"DML_BATCH\",\"stmtIndex\":2,\"table\":\"t_order\","
            + "\"estimatedRows\":5000000,\"idempotent\":true,\"sql\":\"DELETE FROM t_order WHERE status = 0\","
            + "\"batchKey\":{\"column\":\"id\",\"keyType\":\"INT\"}}]}";
    }

    /** B6-shaped plan [普通, 大 ALTER] → [AUTOEXEC[1,1], GHOST(2)] (the untouched pass-through). */
    private static String ghostOnlyPlanJson() {
        return "{\"checkStatus\":\"PASS\",\"changeType\":\"MIXED\",\"segments\":["
            + "{\"segIdx\":1,\"type\":\"AUTOEXEC\",\"stmtRange\":[1,1]},"
            + "{\"segIdx\":2,\"type\":\"GHOST\",\"stmtIndex\":2,\"table\":\"t_big\",\"rows\":20000000,"
            + "\"sql\":\"ALTER TABLE t_big ADD COLUMN c1 INT\"}]}";
    }

    private static String runtimeJson(TicketSegment... segments) {
        return JsonUtils.toJson(List.of(segments));
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

    private static TicketSegment batchSegment(int segIdx, String status, Long taskId) {
        TicketSegment seg = new TicketSegment();
        seg.setSegIdx(segIdx);
        seg.setType(TicketSegment.TYPE_DML_BATCH);
        seg.setStmtIndex(segIdx);
        seg.setTable("t_order");
        seg.setSql("DELETE FROM t_order WHERE status = 0");
        seg.setBatchKey(new TicketSegment.BatchKey("id", DmlBatchRangeSpec.KEY_TYPE_INT));
        seg.setEstimatedRows(5_000_000L);
        seg.setIdempotent(true);
        seg.setStatus(status);
        seg.setTaskId(taskId);
        return seg;
    }

    private static TicketSegment ghostSegment(int segIdx, String status, String sessionId, Long taskId) {
        TicketSegment seg = new TicketSegment();
        seg.setSegIdx(segIdx);
        seg.setType(TicketSegment.TYPE_GHOST);
        seg.setStmtIndex(segIdx);
        seg.setTable("t_big");
        seg.setSql("ALTER TABLE t_big ADD COLUMN c1 INT");
        seg.setStatus(status);
        seg.setSessionId(sessionId);
        seg.setTaskId(taskId);
        return seg;
    }

    private DmExecAsyncTaskDO batchTask(long taskId, AsyncTaskStatus status, DmlBatchCheckpoint checkpoint) {
        DmlBatchAsyncTaskConfig cfg = new DmlBatchAsyncTaskConfig();
        cfg.setBizId("batch-biz");
        cfg.setTicketId(TICKET_ID);
        cfg.setGroupId(GROUP_ID);
        cfg.setUserId(OWNER);
        cfg.setDsId(DS_ID);
        cfg.setDbName(DB);
        cfg.setEffectiveParams(new DmlBatchEffectiveParams());
        cfg.setCheckpoint(checkpoint);
        DmExecAsyncTaskDO task = new DmExecAsyncTaskDO();
        task.setId(taskId);
        task.setBizId("batch-biz");
        task.setBizType(DmlBatchService.BIZ_TYPE);
        task.setStatus(status);
        task.setUid(OWNER);
        task.setConfigData(JsonUtils.toJson(cfg));
        return task;
    }

    private DmExecAsyncTaskDO ghostTask(long taskId, AsyncTaskStatus status) {
        DmExecAsyncTaskDO task = new DmExecAsyncTaskDO();
        task.setId(taskId);
        task.setBizId(GHOST_SESS);
        task.setBizType("GHOST");
        task.setStatus(status);
        task.setUid(OWNER);
        return task;
    }

    /** the last persisted runtime (persistRuntime keeps the in-memory DO in sync as well). */
    private List<TicketSegment> lastRuntime() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(stmtMapper, org.mockito.Mockito.atLeastOnce()).updateExecSegments(eq(GROUP_ID), captor.capture());
        return TicketSegment.parseRuntime(captor.getValue());
    }

    // ==================== the mixed matrix: serial progression permutations ====================

    @Test
    public void mixedMatrix_autoexecBatchGhost_serialProgressionToGroupSuccess() {
        // A6 core: PRE [普通, 大 DML, 大 ALTER] — three segment types advanced serially by the
        // UNCHANGED advance core (dispatch → autoexec complete → batch dispatch → batch complete
        // → ghost dispatch → ghost complete → group SUCCESS)
        DmTicketDbStmtDO group = group("PENDING", threeTypePlanJson(), null);

        // 1. first dispatch: the AUTOEXEC segment (transactional=true must be FORCED false —
        //    the plan carries a DML_BATCH segment, 裁决 #11; see the dedicated pins below)
        service.dispatchFirstSegment(ticket, group, true, ErrorStrategy.NONE, "zh-CN", "SYSTEM");
        ArgumentCaptor<String> jobBizCaptor = ArgumentCaptor.forClass(String.class);
        verify(autoExecService).createGroupJob(same(group), jobBizCaptor.capture(), eq(false),
            eq(ErrorStrategy.NONE), eq("zh-CN"), eq("SYSTEM"));
        verify(autoExecService).startJob(jobBizCaptor.getValue(), "SYSTEM");
        List<TicketSegment> runtime = lastRuntime();
        assertEquals(TicketSegment.STATUS_EXECUTING, runtime.get(0).getStatus());
        assertEquals(TicketSegment.STATUS_PENDING, runtime.get(1).getStatus());
        assertEquals(TicketSegment.STATUS_PENDING, runtime.get(2).getStatus());

        // 2. AUTOEXEC completes → the DML_BATCH segment chains (summary frozen BEFORE the advance)
        group.setExecStatus("EXECUTING");
        assertTrue(service.completeAutoexecSegmentUnderLock(ticket, GROUP_ID, jobBizCaptor.getValue(),
            true, null, "SYSTEM"));
        verify(govTicketV2Service).dispatchBatchSegment(same(ticket), same(group), any(TicketSegment.class),
            eq(GovTicketV2Service.GHOST_VIA_SEGMENT_CHAIN));
        runtime = lastRuntime();
        assertEquals(TicketSegment.STATUS_SUCCESS, runtime.get(0).getStatus());
        assertNotNull("the uk-ruling (a) forensic summary freezes before the next dispatch",
            runtime.get(0).getSummary());
        assertEquals(1, ((Number) runtime.get(0).getSummary().get("taskCount")).intValue());
        assertEquals(TicketSegment.STATUS_EXECUTING, runtime.get(1).getStatus());
        assertEquals(Long.valueOf(BATCH_TASK), runtime.get(1).getTaskId());
        verify(stmtMapper, never()).updateExecStatus(anyLong(), anyString(), any()); // no group terminal mid-chain

        // 3. DML_BATCH completes → the GHOST segment chains
        assertTrue(service.completeDmlBatchSegmentUnderLock(ticket, group, 2, BATCH_TASK, true, null,
            java.util.Map.of("batchesDone", 1000L, "rowsDone", 5_000_000L, "cursor", "999999")));
        verify(govTicketV2Service).dispatchGhostSegment(same(ticket), same(group), any(TicketSegment.class),
            eq(GovTicketV2Service.GHOST_VIA_SEGMENT_CHAIN));
        runtime = lastRuntime();
        assertEquals(TicketSegment.STATUS_SUCCESS, runtime.get(1).getStatus());
        assertEquals(1000, ((Number) runtime.get(1).getSummary().get("batchesDone")).intValue());
        assertEquals(TicketSegment.STATUS_EXECUTING, runtime.get(2).getStatus());
        assertEquals(GHOST_SESS, runtime.get(2).getSessionId());
        assertEquals(Long.valueOf(GHOST_TASK), runtime.get(2).getTaskId());

        // 4. GHOST completes (last segment) → the GROUP terminal (组终态 = 全段终态)
        assertTrue(service.completeGhostSegmentUnderLock(ticket, group, 3, true, null));
        runtime = lastRuntime();
        assertEquals(TicketSegment.STATUS_SUCCESS, runtime.get(2).getStatus());
        verify(stmtMapper).updateExecStatus(GROUP_ID, "SUCCESS", null);
    }

    @Test
    public void mixedMatrix_batchFirstGhostSecond_serialProgression() {
        // the reverse permutation [大 DML, 大 ALTER] → [DML_BATCH(1), GHOST(2)]
        String plan = "{\"checkStatus\":\"PASS\",\"changeType\":\"MIXED\",\"segments\":["
            + "{\"segIdx\":1,\"type\":\"DML_BATCH\",\"stmtIndex\":1,\"table\":\"t_order\","
            + "\"estimatedRows\":5000000,\"idempotent\":true,\"sql\":\"DELETE FROM t_order WHERE status = 0\","
            + "\"batchKey\":{\"column\":\"id\",\"keyType\":\"INT\"}},"
            + "{\"segIdx\":2,\"type\":\"GHOST\",\"stmtIndex\":2,\"table\":\"t_big\",\"rows\":20000000,"
            + "\"sql\":\"ALTER TABLE t_big ADD COLUMN c1 INT\"}]}";
        DmTicketDbStmtDO group = group("PENDING", plan, null);

        service.dispatchFirstSegment(ticket, group, false, ErrorStrategy.NONE, "zh-CN", "SYSTEM");
        verify(govTicketV2Service).dispatchBatchSegment(same(ticket), same(group), any(TicketSegment.class),
            eq(GovTicketV2Service.GHOST_VIA_AUTO_ROUTE));
        verify(autoExecService, never()).createGroupJob(any(), anyString(), anyBoolean(), any(), anyString(), anyString());

        group.setExecStatus("EXECUTING");
        assertTrue(service.completeDmlBatchSegmentUnderLock(ticket, group, 1, BATCH_TASK, true, null, null));
        verify(govTicketV2Service).dispatchGhostSegment(same(ticket), same(group), any(TicketSegment.class),
            eq(GovTicketV2Service.GHOST_VIA_SEGMENT_CHAIN));

        assertTrue(service.completeGhostSegmentUnderLock(ticket, group, 2, true, null));
        verify(stmtMapper).updateExecStatus(GROUP_ID, "SUCCESS", null);
    }

    @Test
    public void mixedMatrix_midChainBatchFailure_groupFailed_downstreamGhostStaysPending() {
        TicketSegment seg1 = autoexecSegment(1, TicketSegment.STATUS_SUCCESS, "job-1");
        TicketSegment seg2 = batchSegment(2, TicketSegment.STATUS_EXECUTING, BATCH_TASK);
        TicketSegment seg3 = ghostSegment(3, TicketSegment.STATUS_PENDING, null, null);
        DmTicketDbStmtDO group = group("EXECUTING", threeTypePlanJson(), runtimeJson(seg1, seg2, seg3));

        assertTrue(service.completeDmlBatchSegmentUnderLock(ticket, group, 2, BATCH_TASK, false,
            "分批执行失败：lock wait timeout", null));

        List<TicketSegment> runtime = lastRuntime();
        assertEquals(TicketSegment.STATUS_FAILED, runtime.get(1).getStatus());
        assertEquals(TicketSegment.STATUS_PENDING, runtime.get(2).getStatus()); // downstream untouched
        verify(stmtMapper).updateExecStatus(GROUP_ID, "FAILED", "分批执行失败：lock wait timeout");
        verify(govTicketV2Service, never()).dispatchGhostSegment(any(), any(), any(), anyString());
    }

    // ==================== transactional force (裁决 #11/A7) ====================

    @Test
    public void transactionalForced_firstDispatch_dmlChangeTypePlan_correctedToFalse() {
        // the PROD_DML mixed shape: auto-advance/confirm both carry transactional=true for a DML
        // changeType — the DML_BATCH segment forces the AUTOEXEC job non-transactional
        DmTicketDbStmtDO group = group("PENDING", dmlChangeTypePlanJson(), null);

        service.dispatchFirstSegment(ticket, group, true, ErrorStrategy.NONE, "zh-CN", "uid-op");

        verify(autoExecService).createGroupJob(same(group), anyString(), eq(false),
            eq(ErrorStrategy.NONE), eq("zh-CN"), eq("uid-op"));
    }

    @Test
    public void transactionalForced_midChainAutoexecAfterBatch_resolveTransactionalPath() {
        // the chained/resume AUTOEXEC dispatch resolves transactional from the GROUP (D15:
        // changeType=DML → true) — the DML_BATCH plan membership forces it false on that path too
        TicketSegment seg1 = batchSegment(1, TicketSegment.STATUS_EXECUTING, BATCH_TASK);
        TicketSegment seg2 = autoexecSegment(2, TicketSegment.STATUS_PENDING, null);
        DmTicketDbStmtDO group = group("EXECUTING", dmlChangeTypePlanJson(), runtimeJson(seg1, seg2));

        assertTrue(service.completeDmlBatchSegmentUnderLock(ticket, group, 1, BATCH_TASK, true, null, null));

        verify(autoExecService).createGroupJob(same(group), anyString(), eq(false),
            eq(ErrorStrategy.NONE), anyString(), anyString());
        verify(autoExecService).startJob(anyString(), anyString());
    }

    @Test
    public void transactionalUntouched_ghostOnlyPlan_b6PassThroughByteIdentical() {
        // a GHOST-only plan keeps the caller's projection (B6 behavior — the force keys ONLY on
        // DML_BATCH membership; PRE groups carry MIXED/DDL anyway, this pins the pass-through)
        DmTicketDbStmtDO group = group("PENDING", ghostOnlyPlanJson(), null);

        service.dispatchFirstSegment(ticket, group, true, ErrorStrategy.NONE, "zh-CN", "SYSTEM");

        verify(autoExecService).createGroupJob(same(group), anyString(), eq(true),
            eq(ErrorStrategy.NONE), eq("zh-CN"), eq("SYSTEM"));
    }

    // ==================== resume cross-active matrix (任一段 extended ruling) ====================

    @Test
    public void resume_dmlBatchTarget_activeGhostTaskOnEarlierSegment_rejects() {
        // the ghost segment advanced SUCCESS through the sweep while its task row still shows a
        // non-terminal transient (the M1 superset ruling: non-terminal = active) — the DML_BATCH
        // resume must reject over it (defensive cross-type coverage of the 任一段 active check)
        TicketSegment ghost = ghostSegment(1, TicketSegment.STATUS_SUCCESS, GHOST_SESS, GHOST_TASK);
        TicketSegment batch = batchSegment(2, TicketSegment.STATUS_FAILED, BATCH_TASK);
        DmTicketDbStmtDO group = group("FAILED", threeTypePlanJson(), runtimeJson(ghost, batch));
        when(asyncTaskMapper.queryById(GHOST_TASK)).thenReturn(ghostTask(GHOST_TASK, AsyncTaskStatus.RUNNING));
        when(asyncTaskMapper.queryById(BATCH_TASK)).thenReturn(batchTask(BATCH_TASK, AsyncTaskStatus.FAILURE, null));
        when(autoJobMapper.queryByDependOnGroupId(GROUP_ID)).thenReturn(null);

        try {
            service.resumeGroupUnderLock(ticket, GROUP_ID, "uid-retry");
            fail("expected the active GHOST task to reject the DML_BATCH resume");
        } catch (ErrorMessageException expected) {
            assertTrue(expected.getMessage().length() > 0);
        }
        verify(govTicketV2Service, never()).dispatchBatchSegment(any(), any(), any(), anyString());
    }

    @Test
    public void resume_ghostTarget_activeBatchTaskOnEarlierSegment_rejects() {
        // the mirrored cross shape: resuming the GHOST target while an earlier DML_BATCH segment's
        // task is still non-terminal rejects with the batch-specific guidance
        TicketSegment batch = batchSegment(1, TicketSegment.STATUS_SUCCESS, BATCH_TASK);
        TicketSegment ghost = ghostSegment(2, TicketSegment.STATUS_FAILED, GHOST_SESS, GHOST_TASK);
        DmTicketDbStmtDO group = group("FAILED", threeTypePlanJson(), runtimeJson(batch, ghost));
        when(asyncTaskMapper.queryById(BATCH_TASK)).thenReturn(batchTask(BATCH_TASK, AsyncTaskStatus.RUNNING, null));
        when(autoJobMapper.queryByDependOnGroupId(GROUP_ID)).thenReturn(null);

        try {
            service.resumeGroupUnderLock(ticket, GROUP_ID, "uid-retry");
            fail("expected the active DML_BATCH task to reject the GHOST resume");
        } catch (ErrorMessageException expected) {
            assertTrue(expected.getMessage().length() > 0);
        }
        verify(govTicketV2Service, never()).dispatchGhostSegment(any(), any(), any(), anyString());
        verify(ghostService, never()).releaseSessionQuietly(anyString(), anyString());
    }

    @Test
    public void resume_dmlBatchTarget_inFlightAutoexecJob_rejects() {
        TicketSegment autoexec = autoexecSegment(1, TicketSegment.STATUS_SUCCESS, "job-1");
        TicketSegment batch = batchSegment(2, TicketSegment.STATUS_FAILED, BATCH_TASK);
        DmTicketDbStmtDO group = group("FAILED", threeTypePlanJson(), runtimeJson(autoexec, batch));
        DmExecAutoJobDO job = new DmExecAutoJobDO();
        job.setStatus(AutoExecJobStatus.EXECUTING); // in-flight → always rejects
        when(autoJobMapper.queryByDependOnGroupId(GROUP_ID)).thenReturn(job);
        when(asyncTaskMapper.queryById(BATCH_TASK)).thenReturn(batchTask(BATCH_TASK, AsyncTaskStatus.FAILURE, null));

        try {
            service.resumeGroupUnderLock(ticket, GROUP_ID, "uid-retry");
            fail("expected the in-flight autoexec job to reject");
        } catch (ErrorMessageException expected) {
            assertTrue(expected.getMessage().length() > 0);
        }
    }

    @Test
    public void resume_dmlBatchTarget_finishJobRemnantOfEarlierAutoexec_notBlocked() {
        // the uk single-row remnant: an earlier AUTOEXEC segment's FINISH job never blocks a
        // non-AUTOEXEC target (the finishRemnant arm of the extended active check)
        TicketSegment autoexec = autoexecSegment(1, TicketSegment.STATUS_SUCCESS, "job-1");
        TicketSegment batch = batchSegment(2, TicketSegment.STATUS_FAILED, BATCH_TASK);
        DmTicketDbStmtDO group = group("FAILED", threeTypePlanJson(), runtimeJson(autoexec, batch));
        DmExecAutoJobDO job = new DmExecAutoJobDO();
        job.setStatus(AutoExecJobStatus.FINISH);
        when(autoJobMapper.queryByDependOnGroupId(GROUP_ID)).thenReturn(job);
        DmlBatchCheckpoint cp = new DmlBatchCheckpoint();
        cp.setCursor("99");
        cp.setBatchesDone(20);
        when(asyncTaskMapper.queryById(BATCH_TASK)).thenReturn(batchTask(BATCH_TASK, AsyncTaskStatus.FAILURE, cp));

        assertFalse(service.resumeGroupUnderLock(ticket, GROUP_ID, "uid-retry"));

        ArgumentCaptor<TicketSegment> segCaptor = ArgumentCaptor.forClass(TicketSegment.class);
        verify(govTicketV2Service).dispatchBatchSegment(same(ticket), same(group), segCaptor.capture(),
            eq(GovTicketV2Service.GHOST_VIA_RETRY));
        // the checkpoint inheritance rides the mixed resume exactly like the pure shape
        assertNotNull(segCaptor.getValue().getResumeCheckpoint());
        assertEquals("99", segCaptor.getValue().getResumeCheckpoint().get("cursor"));
    }

    // ==================== sweep repair over a mixed runtime ====================

    @Test
    public void repair_mixedRuntime_currentBatchSegmentFreshHeartbeat_healthyNoop() {
        TicketSegment autoexec = autoexecSegment(1, TicketSegment.STATUS_SUCCESS, "job-1");
        TicketSegment batch = batchSegment(2, TicketSegment.STATUS_EXECUTING, BATCH_TASK);
        batch.setStartedAt(System.currentTimeMillis() - 60_000L);
        TicketSegment ghost = ghostSegment(3, TicketSegment.STATUS_PENDING, null, null);
        DmTicketDbStmtDO group = group("EXECUTING", threeTypePlanJson(),
            runtimeJson(autoexec, batch, ghost));
        DmlBatchCheckpoint cp = new DmlBatchCheckpoint();
        cp.setLastTickAt(System.currentTimeMillis() - 5_000L); // fresh (threshold ≈ 380s)
        when(dmlBatchService.findTaskForTicketGroup(TICKET_ID, GROUP_ID, 2))
            .thenReturn(batchTask(BATCH_TASK, AsyncTaskStatus.RUNNING, cp));

        service.repairGroupUnderLock(ticket, group);

        // the per-type liveness probe hit the DML_BATCH row (segmentIndex-scoped lookup) and the
        // healthy heartbeat kept the mixed chain untouched
        verify(dmlBatchService).findTaskForTicketGroup(TICKET_ID, GROUP_ID, 2);
        verify(stmtMapper, never()).updateExecSegments(anyLong(), anyString());
        verify(stmtMapper, never()).updateExecStatus(anyLong(), anyString(), any());
    }

    @Test
    public void repair_mixedRuntime_currentGhostSegment_probedByType_sessionHealthy() {
        TicketSegment autoexec = autoexecSegment(1, TicketSegment.STATUS_SUCCESS, "job-1");
        TicketSegment batch = batchSegment(2, TicketSegment.STATUS_SUCCESS, BATCH_TASK);
        TicketSegment ghost = ghostSegment(3, TicketSegment.STATUS_EXECUTING, GHOST_SESS, GHOST_TASK);
        DmTicketDbStmtDO group = group("EXECUTING", threeTypePlanJson(),
            runtimeJson(autoexec, batch, ghost));
        when(ghostService.findTaskForTicketGroup(TICKET_ID, GROUP_ID, 3))
            .thenReturn(ghostTask(GHOST_TASK, AsyncTaskStatus.RUNNING));
        when(ghostService.hasSession(OWNER, GHOST_SESS)).thenReturn(true);

        service.repairGroupUnderLock(ticket, group);

        verify(ghostService).hasSession(OWNER, GHOST_SESS);
        verify(dmlBatchService, never()).findTaskForTicketGroup(anyLong(), anyLong(), any());
        verify(stmtMapper, never()).updateExecStatus(anyLong(), anyString(), any());
    }

    @Test
    public void repair_mixedRuntime_currentBatchStaleHeartbeat_deadJudged_ghostStaysPending() {
        TicketSegment autoexec = autoexecSegment(1, TicketSegment.STATUS_SUCCESS, "job-1");
        TicketSegment batch = batchSegment(2, TicketSegment.STATUS_EXECUTING, BATCH_TASK);
        TicketSegment ghost = ghostSegment(3, TicketSegment.STATUS_PENDING, null, null);
        DmTicketDbStmtDO group = group("EXECUTING", threeTypePlanJson(),
            runtimeJson(autoexec, batch, ghost));
        DmlBatchCheckpoint cp = new DmlBatchCheckpoint();
        cp.setCursor("77");
        cp.setBatchesDone(15);
        cp.setLastTickAt(System.currentTimeMillis() - 400_000L); // stale → dead judgement
        when(dmlBatchService.findTaskForTicketGroup(TICKET_ID, GROUP_ID, 2))
            .thenReturn(batchTask(BATCH_TASK, AsyncTaskStatus.RUNNING, cp));

        service.repairGroupUnderLock(ticket, group);

        List<TicketSegment> runtime = lastRuntime();
        assertEquals(TicketSegment.STATUS_SUCCESS, runtime.get(0).getStatus());   // earlier untouched
        assertEquals(TicketSegment.STATUS_FAILED, runtime.get(1).getStatus());
        // C6/M6: the summary cursor is the sha256 display handle; the retryGroup INHERITANCE
        // trail is the raw checkpoint cursor on configData (the L520 pin), never this copy
        assertEquals(com.clougence.clouddm.console.web.service.dmlbatch.DmlBatchLiterals.hashedCursorForDisplay("77"),
            runtime.get(1).getSummary().get("cursor"));
        assertEquals(TicketSegment.STATUS_PENDING, runtime.get(2).getStatus());   // downstream untouched
        verify(stmtMapper).updateExecStatus(eq(GROUP_ID), eq("FAILED"), anyString());
        verify(govTicketV2Service, never()).dispatchGhostSegment(any(), any(), any(), anyString());
    }
}
