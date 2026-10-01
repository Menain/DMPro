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

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.api.console.autoexec.ErrorStrategy;
import com.clougence.clouddm.console.web.component.execute.AutoExecService;
import com.clougence.clouddm.console.web.service.ghost.GhostService;
import com.clougence.clouddm.console.web.service.ghost.asyntask.GhostAsyncTaskConfig;
import com.clougence.clouddm.console.web.service.govticket.GovTicketV2Service;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.ExecutionDal;
import com.clougence.clouddm.platform.dal.access.TicketDbStmtDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoJobMapper;
import com.clougence.clouddm.platform.dal.mapper.govticket.DmTicketDbStmtMapper;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.execution.AsyncTaskStatus;
import com.clougence.clouddm.platform.dal.model.execution.AutoExecJobStatus;
import com.clougence.clouddm.platform.dal.model.execution.DmExecAsyncTaskDO;
import com.clougence.clouddm.platform.dal.model.execution.DmExecAutoJobDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;
import com.clougence.utils.JsonUtils;

/**
 * B6 M2 (Step 8): the segment orchestrator — advanceSegment exactly-once semantics (segment
 * status CAS under the caller's ticket row lock, dual-event-source races, callback replays),
 * chained next-segment dispatch (AUTOEXEC job rebuild ∨ GHOST session launch), last-segment
 * group terminal, the uk-ruling (a) AUTOEXEC summary freeze, first-segment dispatch (lock +
 * PENDING/FAILED CAS + runtime rebuild from the plan) and the WAIT_EXEC sweep repair matrix
 * (lost-writeback advance, ghost liveness via AsyncTask+session — NEVER autoexec task rows,
 * undispatched PENDING segment, lost group terminal, corrupt-runtime degradation).
 * <p>
 * Discipline: JUnit4 + hand-written mocks + ReflectionTestUtils + ArgumentCaptor; NO Spring
 * context. The aggregation tail is NOT this service's job (caller-owned) — pinned by
 * verifyNoInteractions on the state service which this service does not even hold.
 */
public class TicketSegmentServiceTest {

    private TicketSegmentService     service;
    private ApprovalDal              approvalDal;
    private DmApprovalMapper         approvalMapper;
    private TicketDbStmtDal          ticketDbStmtDal;
    private DmTicketDbStmtMapper     stmtMapper;
    private ExecutionDal             execDal;
    private DmExecAutoJobMapper      autoJobMapper;
    private com.clougence.clouddm.platform.dal.mapper.execution.DmExecAsyncTaskMapper asyncTaskMapper;
    private AutoExecService          autoExecService;
    private GovTicketV2Service       govTicketV2Service;
    private GhostService             ghostService;
    private SchemaSnapshotService    schemaSnapshotService;

    private static final long   TICKET_ID = 900L;
    private static final long   GROUP_ID  = 100L;
    private static final String PUID      = "puid-001";
    private static final String OWNER     = "uid-submitter";
    private static final long   DS_ID     = 20L;
    private static final String DB        = "pre_db";

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
        asyncTaskMapper = mock(com.clougence.clouddm.platform.dal.mapper.execution.DmExecAsyncTaskMapper.class);
        when(execDal.asyncTaskMapper()).thenReturn(asyncTaskMapper);
        autoExecService = mock(AutoExecService.class);
        govTicketV2Service = mock(GovTicketV2Service.class);
        ghostService = mock(GhostService.class);
        schemaSnapshotService = mock(SchemaSnapshotService.class);

        ReflectionTestUtils.setField(service, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(service, "ticketDbStmtDal", ticketDbStmtDal);
        ReflectionTestUtils.setField(service, "execDal", execDal);
        ReflectionTestUtils.setField(service, "autoExecService", autoExecService);
        ReflectionTestUtils.setField(service, "govTicketV2Service", govTicketV2Service);
        ReflectionTestUtils.setField(service, "ghostService", ghostService);
        ReflectionTestUtils.setField(service, "schemaSnapshotService", schemaSnapshotService);
    }

    // ==================== advanceSegment: exactly-once CAS + chained dispatch ====================

    @Test
    public void autoexecCompletion_middleSegment_writesSummary_dispatchesGhostNext() {
        // [AUTOEXEC[1,2] EXECUTING, GHOST(3) PENDING] — segment 1 succeeds → summary frozen
        // BEFORE the next dispatch (uk ruling (a): the ghost launch precedes any job deletion,
        // but the NEXT autoexec job creation would delete this job+tasks) → GHOST chain dispatch
        DmTicketDbStmtDO group = segmentedGroup(
            seg(1, "AUTOEXEC", "EXECUTING", null, List.of(1, 2), null),
            seg(2, "GHOST", "PENDING", "ALTER TABLE t_big ADD c INT", null, 3));

        boolean advanced = service.completeAutoexecSegmentUnderLock(ticket(), GROUP_ID, "job-1", true, null, "SYSTEM");

        assertTrue(advanced);
        // runtime persisted: seg1 SUCCESS + summary, seg2 EXECUTING
        List<List<TicketSegment>> saved = capturedRuntime(2); // one persist for the terminal, one for the dispatch
        TicketSegment s1 = saved.get(0).get(0);
        assertEquals(TicketSegment.STATUS_SUCCESS, s1.getStatus());
        assertNotNull("the completed AUTOEXEC segment freezes its forensic summary", s1.getSummary());
        assertEquals("SUCCESS", s1.getSummary().get("status"));
        assertEquals(2, s1.getSummary().get("taskCount")); // stmtRange [1,2] span
        assertNotNull(s1.getFinishedAt());
        TicketSegment s2 = saved.get(1).get(1);
        assertEquals(TicketSegment.STATUS_EXECUTING, s2.getStatus());
        // chained GHOST dispatch under the same lock, with the segment instance + chain via
        verify(govTicketV2Service).dispatchGhostSegment(any(DmApprovalDO.class), same(group),
            argThat(s -> s.getSegIdx() == 2), eq(GovTicketV2Service.GHOST_VIA_SEGMENT_CHAIN));
        // the group row stays EXECUTING (the orchestrator never writes a mid-chain group terminal)
        verify(stmtMapper, never()).updateExecStatus(anyLong(), anyString(), any());
        verifyNoInteractions(autoExecService);
    }

    @Test
    public void autoexecCompletion_lastSegment_groupSuccess_noDispatch() {
        DmTicketDbStmtDO group = segmentedGroup(
            seg(1, "GHOST", "SUCCESS", null, null, 1),
            seg(2, "AUTOEXEC", "EXECUTING", null, List.of(2, 4), null));

        boolean advanced = service.completeAutoexecSegmentUnderLock(ticket(), GROUP_ID, "job-2", true, null, "SYSTEM");

        assertTrue(advanced);
        verify(stmtMapper).updateExecStatus(GROUP_ID, "SUCCESS", null);
        verifyNoInteractions(autoExecService);
        verifyNoInteractions(govTicketV2Service);
    }

    @Test
    public void autoexecCompletion_failure_segmentAndGroupFailed_noDispatch() {
        // 段失败 → 组 FAILED (exec_detail = the failed segment's error) → the caller tail fails the ticket
        DmTicketDbStmtDO group = segmentedGroup(
            seg(1, "AUTOEXEC", "EXECUTING", null, List.of(1, 2), null),
            seg(2, "GHOST", "PENDING", "ALTER TABLE t_big ADD c INT", null, 3));

        boolean advanced = service.completeAutoexecSegmentUnderLock(
            ticket(), GROUP_ID, "job-1", false, "syntax error near 'ALTER'", "SYSTEM");

        assertTrue(advanced);
        verify(stmtMapper).updateExecStatus(GROUP_ID, "FAILED", "syntax error near 'ALTER'");
        List<TicketSegment> saved = capturedRuntime(1).get(0); // the single (failure-path) persist
        assertEquals(TicketSegment.STATUS_FAILED, saved.get(0).getStatus());
        assertEquals("syntax error near 'ALTER'", saved.get(0).getError());
        assertEquals("the downstream segment stays PENDING (serial progression)",
            TicketSegment.STATUS_PENDING, saved.get(1).getStatus());
        verifyNoInteractions(autoExecService);
        verifyNoInteractions(govTicketV2Service);
    }

    @Test
    public void autoexecCompletion_replayAfterAdvance_casSkips_zeroSideEffects() {
        // exactly-once: the callback is redelivered (or the sweep already advanced) — the segment
        // is terminal now, so the CAS skips WITHOUT re-dispatching or re-writing anything
        DmTicketDbStmtDO group = segmentedGroup(
            seg(1, "AUTOEXEC", "SUCCESS", null, List.of(1, 2), null),
            seg(2, "GHOST", "EXECUTING", "ALTER TABLE t_big ADD c INT", null, 3));

        boolean advanced = service.completeAutoexecSegmentUnderLock(ticket(), GROUP_ID, "job-1", true, null, "SYSTEM");

        assertFalse(advanced);
        verify(stmtMapper, never()).updateExecStatus(anyLong(), anyString(), any());
        verify(stmtMapper, never()).updateExecSegments(anyLong(), any());
        verifyNoInteractions(autoExecService);
        verifyNoInteractions(govTicketV2Service);
    }

    @Test
    public void autoexecCompletion_dualEventSourceRace_secondWriterSkips() {
        // the ghost hook and the autoexec callback converge on the SAME lock; whichever commits
        // first moves the segment out of EXECUTING — the loser's CAS finds no EXECUTING
        // AUTOEXEC segment and skips (first writer wins, no double dispatch)
        DmTicketDbStmtDO group = segmentedGroup(
            seg(1, "AUTOEXEC", "FAILED", null, List.of(1, 2), null),
            seg(2, "GHOST", "PENDING", "ALTER TABLE t_big ADD c INT", null, 3));

        assertFalse(service.completeAutoexecSegmentUnderLock(ticket(), GROUP_ID, "job-1", true, null, "SYSTEM"));
        verifyNoInteractions(autoExecService);
    }

    @Test
    public void autoexecCompletion_staleJobCallback_jobBizIdMismatch_skips() {
        DmTicketDbStmtDO group = segmentedGroup(
            seg(1, "AUTOEXEC", "EXECUTING", null, List.of(1, 2), null),
            seg(2, "GHOST", "PENDING", "ALTER TABLE t_big ADD c INT", null, 3));
        List<TicketSegment> staleRuntime = runtimeOf(group);
        staleRuntime.get(0).setJobBizId("job-current");
        commit(group, staleRuntime);

        assertFalse(service.completeAutoexecSegmentUnderLock(ticket(), GROUP_ID, "job-replaced", true, null, "SYSTEM"));
        verify(stmtMapper, never()).updateExecStatus(anyLong(), anyString(), any());
        verifyNoInteractions(autoExecService);
    }

    @Test
    public void autoexecCompletion_nextAutoexecSegment_rebuildsJobWithSegmentRuntime() {
        // [AUTOEXEC[1,2] EXECUTING, GHOST(3) SUCCESS?... no — serial: [A[1,2] EXEC, G(3) SUCCESS is
        // impossible mid-chain]; use [GHOST(1) SUCCESS, AUTOEXEC[2,3] EXECUTING, AUTOEXEC... ] —
        // the realistic shape: ghost finished, the NEXT plain run dispatches through createGroupJob
        DmTicketDbStmtDO group = segmentedGroup(
            seg(1, "GHOST", "PENDING", "ALTER TABLE t_big ADD c INT", null, 1),
            seg(2, "AUTOEXEC", "PENDING", null, List.of(2, 3), null));
        // simulate the ghost segment having just been marked EXECUTING by the caller chain
        List<TicketSegment> preRuntime = runtimeOf(group);
        preRuntime.get(0).setStatus(TicketSegment.STATUS_EXECUTING);
        commit(group, preRuntime);

        // ghost segment completes → the next AUTOEXEC segment dispatches
        boolean advanced = service.completeGhostSegmentUnderLock(ticket(), group, 1, true, null);

        assertTrue(advanced);
        ArgumentCaptor<String> jobBizCaptor = ArgumentCaptor.forClass(String.class);
        verify(autoExecService).createGroupJob(same(group), jobBizCaptor.capture(), eq(false),
            eq(ErrorStrategy.NONE), anyString(), eq("SYSTEM"));
        verify(autoExecService).startJob(jobBizCaptor.getValue(), "SYSTEM");
        // the runtime handed to createGroupJob carries the freshly-marked EXECUTING segment
        // (createGroupJob reads group.execSegments for its stmtRange filter)
        List<TicketSegment> passed = TicketSegment.parseRuntime(group.getExecSegments());
        assertEquals(TicketSegment.STATUS_EXECUTING, passed.get(1).getStatus());
        assertEquals(jobBizCaptor.getValue(), passed.get(1).getJobBizId());
        assertEquals(TicketSegment.STATUS_SUCCESS, passed.get(0).getStatus());
    }

    // ==================== ghost segment completion ====================

    @Test
    public void ghostCompletion_segmentIndexBound_casOnExactSegment() {
        DmTicketDbStmtDO group = segmentedGroup(
            seg(1, "AUTOEXEC", "SUCCESS", null, List.of(1, 1), null),
            seg(2, "GHOST", "EXECUTING", "ALTER TABLE t_big ADD c INT", null, 2),
            seg(3, "AUTOEXEC", "PENDING", null, List.of(3, 3), null));

        assertTrue(service.completeGhostSegmentUnderLock(ticket(), group, 2, true, null));

        // last-but-one segment → the trailing AUTOEXEC segment dispatches
        verify(autoExecService).createGroupJob(same(group), anyString(), anyBoolean(), any(), anyString(), anyString());
        verify(stmtMapper, never()).updateExecStatus(anyLong(), anyString(), any());
    }

    @Test
    public void ghostCompletion_failure_groupFailed_withSegmentError() {
        DmTicketDbStmtDO group = segmentedGroup(
            seg(1, "AUTOEXEC", "SUCCESS", null, List.of(1, 1), null),
            seg(2, "GHOST", "EXECUTING", "ALTER TABLE t_big ADD c INT", null, 2),
            seg(3, "AUTOEXEC", "PENDING", null, List.of(3, 3), null));

        assertTrue(service.completeGhostSegmentUnderLock(ticket(), group, 2, false,
            "ghost migration failed: FATAL binlog invalid"));

        verify(stmtMapper).updateExecStatus(GROUP_ID, "FAILED", "ghost migration failed: FATAL binlog invalid");
        verifyNoInteractions(autoExecService);
    }

    @Test
    public void ghostCompletion_wrongSegmentIndex_casSkips() {
        // a stale task of an OLD segment (rebuilt group) must never advance the CURRENT one
        DmTicketDbStmtDO group = segmentedGroup(
            seg(1, "GHOST", "PENDING", "ALTER TABLE t_big ADD c INT", null, 1),
            seg(2, "AUTOEXEC", "PENDING", null, List.of(2, 2), null));
        runtimeOf(group).get(0).setStatus(TicketSegment.STATUS_EXECUTING);

        assertFalse(service.completeGhostSegmentUnderLock(ticket(), group, 9, true, null));
        verify(stmtMapper, never()).updateExecStatus(anyLong(), anyString(), any());
        verifyNoInteractions(autoExecService);
    }

    @Test
    public void ghostCompletion_nullSegmentIndex_fallsBackToExecutingGhostShape_m1TaskOnM2Group() {
        DmTicketDbStmtDO group = segmentedGroup(
            seg(1, "GHOST", "EXECUTING", "ALTER TABLE t_big ADD c INT", null, 1));

        assertTrue(service.completeGhostSegmentUnderLock(ticket(), group, null, true, null));

        verify(stmtMapper).updateExecStatus(GROUP_ID, "SUCCESS", null); // single segment = last
    }

    @Test
    public void completion_corruptRuntime_degradesToLegacyGroupWrite_neverStuck() {
        DmTicketDbStmtDO group = group("EXECUTING");
        group.setExecSegments("{not json");
        when(stmtMapper.queryById(GROUP_ID)).thenReturn(group);

        assertTrue(service.completeAutoexecSegmentUnderLock(ticket(), GROUP_ID, "job-1", true, null, "SYSTEM"));

        verify(stmtMapper).updateExecStatus(GROUP_ID, "SUCCESS", null);
        verifyNoInteractions(autoExecService);
    }

    @Test
    public void autoexecCompletion_groupRowMissingUnderLock_skips() {
        when(stmtMapper.queryById(GROUP_ID)).thenReturn(null); // fresh re-read (前瞻②) finds nothing

        assertFalse(service.completeAutoexecSegmentUnderLock(ticket(), GROUP_ID, "job-1", true, null, "SYSTEM"));
        verifyNoInteractions(autoExecService);
    }

    // ==================== dispatchFirstSegment ====================

    @Test
    public void firstDispatch_autoexecFirst_locksCasReads_initsRuntime_buildsSegmentJob() {
        DmTicketDbStmtDO snapshot = group("PENDING");
        snapshot.setPrecheckResult(planJson());
        DmTicketDbStmtDO fresh = group("PENDING");
        fresh.setPrecheckResult(planJson());
        when(stmtMapper.queryById(GROUP_ID)).thenReturn(fresh);

        service.dispatchFirstSegment(ticket(), snapshot, false, ErrorStrategy.NONE, "zh-CN", "SYSTEM");

        // lock discipline: the ticket row lock precedes everything
        InOrder inOrder = inOrder(approvalMapper, stmtMapper, autoExecService);
        inOrder.verify(approvalMapper).selectByIdForUpdate(TICKET_ID);
        inOrder.verify(stmtMapper).queryById(GROUP_ID);
        // runtime initialized from the plan, first segment EXECUTING with its jobBizId
        inOrder.verify(stmtMapper, atLeastOnce()).updateExecSegments(eq(GROUP_ID), anyString());
        ArgumentCaptor<String> jobBizCaptor = ArgumentCaptor.forClass(String.class);
        inOrder.verify(autoExecService).createGroupJob(same(fresh), jobBizCaptor.capture(),
            eq(false), eq(ErrorStrategy.NONE), eq("zh-CN"), eq("SYSTEM"));
        inOrder.verify(autoExecService).startJob(jobBizCaptor.getValue(), "SYSTEM");

        List<TicketSegment> runtime = TicketSegment.parseRuntime(fresh.getExecSegments());
        assertEquals(3, runtime.size());
        assertEquals(TicketSegment.STATUS_EXECUTING, runtime.get(0).getStatus());
        assertEquals(jobBizCaptor.getValue(), runtime.get(0).getJobBizId());
        assertEquals(TicketSegment.STATUS_PENDING, runtime.get(1).getStatus());
        assertEquals(TicketSegment.STATUS_PENDING, runtime.get(2).getStatus());
        verify(schemaSnapshotService).captureIfMissing(fresh, TICKET_ID);
    }

    @Test
    public void firstDispatch_ghostFirst_launchesThroughSegmentDispatch() {
        DmTicketDbStmtDO fresh = group("PENDING");
        fresh.setPrecheckResult(JsonUtils.toJson(java.util.Map.of("checkStatus", "PASS", "changeType", "DDL",
            "segments", List.of(java.util.Map.of("segIdx", 1, "type", "GHOST", "stmtIndex", 1,
                "sql", "ALTER TABLE t_big ADD c INT")))));
        when(stmtMapper.queryById(GROUP_ID)).thenReturn(fresh);

        service.dispatchFirstSegment(ticket(), fresh, false, ErrorStrategy.NONE, "zh-CN", "SYSTEM");

        verify(govTicketV2Service).dispatchGhostSegment(any(DmApprovalDO.class), same(fresh),
            argThat(s -> s.getSegIdx() == 1 && TicketSegment.TYPE_GHOST.equals(s.getType())),
            eq(GovTicketV2Service.GHOST_VIA_AUTO_ROUTE));
        verifyNoInteractions(autoExecService);
    }

    @Test
    public void firstDispatch_groupNotDispatchableUnderLock_skips_zeroSideEffects() {
        DmTicketDbStmtDO snapshot = group("PENDING");
        snapshot.setPrecheckResult(planJson());
        when(stmtMapper.queryById(GROUP_ID)).thenReturn(group("EXECUTING")); // a concurrent writer won

        service.dispatchFirstSegment(ticket(), snapshot, false, ErrorStrategy.NONE, "zh-CN", "SYSTEM");

        verify(stmtMapper, never()).updateExecSegments(anyLong(), any());
        verifyNoInteractions(autoExecService);
        verifyNoInteractions(govTicketV2Service);
    }

    @Test
    public void firstDispatch_staleRuntimeOverwritten_rebuiltFromPlan() {
        // defense in depth: whatever runtime residue a PENDING group carries, the first dispatch
        // rebuilds it from the fresh plan (post-resubmit shape — the reset already nulled it)
        DmTicketDbStmtDO fresh = group("PENDING");
        fresh.setPrecheckResult(planJson());
        fresh.setExecSegments("[{\"segIdx\":1,\"type\":\"GHOST\",\"stmtIndex\":9,\"status\":\"FAILED\"}]");
        when(stmtMapper.queryById(GROUP_ID)).thenReturn(fresh);

        service.dispatchFirstSegment(ticket(), fresh, false, ErrorStrategy.NONE, "zh-CN", "SYSTEM");

        List<TicketSegment> runtime = TicketSegment.parseRuntime(fresh.getExecSegments());
        assertEquals(3, runtime.size()); // rebuilt from the 3-segment plan, not the stale 1-entry runtime
        assertEquals(TicketSegment.STATUS_EXECUTING, runtime.get(0).getStatus());
    }

    @Test
    public void firstDispatch_planMissingUnderLock_skips() {
        DmTicketDbStmtDO snapshot = group("PENDING");
        snapshot.setPrecheckResult(planJson());
        DmTicketDbStmtDO resubmitted = group("PENDING"); // concurrent resubmit rewrote the plan away
        resubmitted.setPrecheckResult("{\"checkStatus\":\"PASS\",\"changeType\":\"DML\"}");
        when(stmtMapper.queryById(GROUP_ID)).thenReturn(resubmitted);

        service.dispatchFirstSegment(ticket(), snapshot, false, ErrorStrategy.NONE, "zh-CN", "SYSTEM");

        verify(stmtMapper, never()).updateExecSegments(anyLong(), any());
        verifyNoInteractions(autoExecService);
        verifyNoInteractions(govTicketV2Service);
    }

    // ==================== sweep repair (advance + aggregate dual duty) ====================

    @Test
    public void repair_autoexecJobFinished_writebackLost_advancesLikeTheCallback() {
        DmTicketDbStmtDO group = segmentedGroup(
            seg(1, "AUTOEXEC", "EXECUTING", null, List.of(1, 2), null),
            seg(2, "GHOST", "PENDING", "ALTER TABLE t_big ADD c INT", null, 3));
        DmExecAutoJobDO job = new DmExecAutoJobDO();
        job.setStatus(AutoExecJobStatus.FINISH);
        job.setUid("SYSTEM");
        when(autoJobMapper.queryByDependOnGroupId(GROUP_ID)).thenReturn(job);

        service.repairGroupUnderLock(ticket(), group);

        // the lost jobFinish is replayed semantically: segment SUCCESS + summary + ghost dispatch
        verify(govTicketV2Service).dispatchGhostSegment(any(DmApprovalDO.class), same(group),
            argThat(s -> s.getSegIdx() == 2), anyString());
        List<TicketSegment> saved = lastCapturedRuntime();
        assertEquals(TicketSegment.STATUS_SUCCESS, saved.get(0).getStatus());
        assertNotNull(saved.get(0).getSummary());
    }

    @Test
    public void repair_autoexecJobFailed_advancesAsSegmentFailure() {
        DmTicketDbStmtDO group = segmentedGroup(
            seg(1, "AUTOEXEC", "EXECUTING", null, List.of(1, 2), null),
            seg(2, "GHOST", "PENDING", "ALTER TABLE t_big ADD c INT", null, 3));
        DmExecAutoJobDO job = new DmExecAutoJobDO();
        job.setStatus(AutoExecJobStatus.FAILED);
        when(autoJobMapper.queryByDependOnGroupId(GROUP_ID)).thenReturn(job);

        service.repairGroupUnderLock(ticket(), group);

        verify(stmtMapper).updateExecStatus(eq(GROUP_ID), eq("FAILED"), contains("writeback lost"));
        verifyNoInteractions(govTicketV2Service);
    }

    @Test
    public void repair_autoexecJobMissing_segmentFailed() {
        DmTicketDbStmtDO group = segmentedGroup(
            seg(1, "AUTOEXEC", "EXECUTING", null, List.of(1, 2), null));
        when(autoJobMapper.queryByDependOnGroupId(GROUP_ID)).thenReturn(null);

        service.repairGroupUnderLock(ticket(), group);

        verify(stmtMapper).updateExecStatus(eq(GROUP_ID), eq("FAILED"), contains("record missing"));
    }

    @Test
    public void repair_autoexecJobActive_healthy_noWrites() {
        DmTicketDbStmtDO group = segmentedGroup(
            seg(1, "AUTOEXEC", "EXECUTING", null, List.of(1, 2), null),
            seg(2, "GHOST", "PENDING", "ALTER TABLE t_big ADD c INT", null, 3));
        DmExecAutoJobDO job = new DmExecAutoJobDO();
        job.setStatus(AutoExecJobStatus.EXECUTING);
        when(autoJobMapper.queryByDependOnGroupId(GROUP_ID)).thenReturn(job);

        service.repairGroupUnderLock(ticket(), group);

        verify(stmtMapper, never()).updateExecStatus(anyLong(), anyString(), any());
        verify(stmtMapper, never()).updateExecSegments(anyLong(), any());
        verifyNoInteractions(autoExecService);
    }

    @Test
    public void repair_ghostSegment_taskLookupUsesSegmentIndex_neverAutoexecTaskRows() {
        // 前瞻④: the ghost liveness probe keys on the AsyncTask triple + tool session — the
        // autoexec TASK rows die with the segment job replacement and must never be consulted
        DmTicketDbStmtDO group = segmentedGroup(
            seg(1, "AUTOEXEC", "SUCCESS", null, List.of(1, 2), null),
            seg(2, "GHOST", "EXECUTING", "ALTER TABLE t_big ADD c INT", null, 3));
        when(ghostService.findTaskForTicketGroup(TICKET_ID, GROUP_ID, 2))
            .thenReturn(ghostTask(AsyncTaskStatus.RUNNING, "session-1", OWNER));
        when(ghostService.hasSession(OWNER, "session-1")).thenReturn(true);

        service.repairGroupUnderLock(ticket(), group);

        verify(ghostService).findTaskForTicketGroup(TICKET_ID, GROUP_ID, 2);
        verifyNoInteractions(execDal); // no autoexec job/task probe for a GHOST segment
        verify(stmtMapper, never()).updateExecStatus(anyLong(), anyString(), any());
    }

    @Test
    public void repair_ghostSegment_taskTerminal_writebackLost_segmentFailed() {
        DmTicketDbStmtDO group = segmentedGroup(
            seg(1, "GHOST", "EXECUTING", "ALTER TABLE t_big ADD c INT", null, 1),
            seg(2, "AUTOEXEC", "PENDING", null, List.of(2, 2), null));
        when(ghostService.findTaskForTicketGroup(TICKET_ID, GROUP_ID, 1))
            .thenReturn(ghostTask(AsyncTaskStatus.COMPLETE, "session-1", OWNER));

        service.repairGroupUnderLock(ticket(), group);

        verify(stmtMapper).updateExecStatus(eq(GROUP_ID), eq("FAILED"), contains("writeback lost"));
        verifyNoInteractions(autoExecService); // a FAILED segment never chain-dispatches
    }

    @Test
    public void repair_ghostSegment_sessionGone_resultUnknown_failed_r3Ruling() {
        DmTicketDbStmtDO group = segmentedGroup(
            seg(1, "GHOST", "EXECUTING", "ALTER TABLE t_big ADD c INT", null, 1));
        when(ghostService.findTaskForTicketGroup(TICKET_ID, GROUP_ID, 1))
            .thenReturn(ghostTask(AsyncTaskStatus.RUNNING, "session-1", OWNER));
        when(ghostService.hasSession(OWNER, "session-1")).thenReturn(false);

        service.repairGroupUnderLock(ticket(), group);

        verify(stmtMapper).updateExecStatus(eq(GROUP_ID), eq("FAILED"), contains("session lost"));
    }

    @Test
    public void repair_ghostSegment_pausedTaskSessionAlive_healthy() {
        // a throttled (panel/dock-paused) migration is ALIVE — PAUSE is in the active set
        DmTicketDbStmtDO group = segmentedGroup(
            seg(1, "GHOST", "EXECUTING", "ALTER TABLE t_big ADD c INT", null, 1));
        when(ghostService.findTaskForTicketGroup(TICKET_ID, GROUP_ID, 1))
            .thenReturn(ghostTask(AsyncTaskStatus.PAUSE, "session-1", OWNER));
        when(ghostService.hasSession(OWNER, "session-1")).thenReturn(true);

        service.repairGroupUnderLock(ticket(), group);

        verify(stmtMapper, never()).updateExecStatus(anyLong(), anyString(), any());
    }

    @Test
    public void repair_ghostSegment_taskMissing_segmentFailed() {
        DmTicketDbStmtDO group = segmentedGroup(
            seg(1, "GHOST", "EXECUTING", "ALTER TABLE t_big ADD c INT", null, 1));
        when(ghostService.findTaskForTicketGroup(TICKET_ID, GROUP_ID, 1)).thenReturn(null);

        service.repairGroupUnderLock(ticket(), group);

        verify(stmtMapper).updateExecStatus(eq(GROUP_ID), eq("FAILED"), contains("record missing"));
    }

    @Test
    public void repair_allSegmentsTerminal_groupWriteLost_recoversGroupTerminal() {
        DmTicketDbStmtDO group = segmentedGroup(
            seg(1, "GHOST", "SUCCESS", null, null, 1),
            seg(2, "AUTOEXEC", "SUCCESS", null, List.of(2, 2), null));

        service.repairGroupUnderLock(ticket(), group);

        verify(stmtMapper).updateExecStatus(GROUP_ID, "SUCCESS", null);
        verifyNoInteractions(autoExecService);
        verifyNoInteractions(ghostService);
    }

    @Test
    public void repair_allSegmentsTerminalWithFailure_groupFailedCarryingSegmentError() {
        DmTicketDbStmtDO group = segmentedGroup(
            seg(1, "GHOST", "SUCCESS", null, null, 1),
            seg(2, "AUTOEXEC", "FAILED", null, List.of(2, 2), null));
        List<TicketSegment> failedRuntime = runtimeOf(group);
        failedRuntime.get(1).setError("task 2 blew up");
        commit(group, failedRuntime);

        service.repairGroupUnderLock(ticket(), group);

        verify(stmtMapper).updateExecStatus(GROUP_ID, "FAILED", "task 2 blew up");
    }

    @Test
    public void repair_currentSegmentPendingUndispatched_dispatchesIt() {
        // a crashed mid-advance: the predecessor committed SUCCESS but the next dispatch rolled
        // back — the sweep re-marks and dispatches (nothing of this segment ever executed)
        DmTicketDbStmtDO group = segmentedGroup(
            seg(1, "GHOST", "SUCCESS", null, null, 1),
            seg(2, "AUTOEXEC", "PENDING", null, List.of(2, 3), null));

        service.repairGroupUnderLock(ticket(), group);

        verify(autoExecService).createGroupJob(same(group), anyString(), eq(false),
            eq(ErrorStrategy.NONE), anyString(), eq("SYSTEM"));
        verify(autoExecService).startJob(anyString(), eq("SYSTEM"));
    }

    @Test
    public void repair_nonExecutingGroup_untouched() {
        DmTicketDbStmtDO group = segmentedGroup(
            seg(1, "AUTOEXEC", "PENDING", null, List.of(1, 2), null));
        group.setExecStatus("PENDING"); // first dispatch never happened — not the sweep's business

        service.repairGroupUnderLock(ticket(), group);

        verify(stmtMapper, never()).updateExecStatus(anyLong(), anyString(), any());
        verifyNoInteractions(autoExecService);
    }

    @Test
    public void repair_staleCallerRow_judgesOnTheFreshReRead_checkF1() {
        // check F1 (RR staleness guard): the caller's group row comes from the sweep's PRE-LOCK
        // read view — a concurrent ghost hook that committed seg1 SUCCESS + seg2 dispatch while
        // the sweep waited on the ticket lock is INVISIBLE to that snapshot. The repair must
        // judge on its own fresh re-read (REQUIRES_NEW, post-lock): probing the stale seg1
        // (whose session the winning hook already released) would falsely FAIL a healthy chain
        // AND overwrite the committed runtime, losing seg2's bindings.
        DmTicketDbStmtDO stale = segmentedGroup(
            seg(1, "GHOST", "EXECUTING", "ALTER TABLE t_big ADD c INT", null, 1),
            seg(2, "AUTOEXEC", "PENDING", null, List.of(2, 2), null));
        // the fresh under-lock re-read: the hook already advanced to seg2 (bindings recorded)
        DmTicketDbStmtDO fresh = group("EXECUTING");
        fresh.setExecSegments(JsonUtils.toJson(List.of(
            seg(1, "GHOST", "SUCCESS", "ALTER TABLE t_big ADD c INT", null, 1),
            seg(2, "AUTOEXEC", "EXECUTING", null, List.of(2, 2), null))));
        when(stmtMapper.queryById(GROUP_ID)).thenReturn(fresh); // overrides the segmentedGroup stub
        DmExecAutoJobDO job = new DmExecAutoJobDO();
        job.setStatus(AutoExecJobStatus.EXECUTING); // seg2's job in flight → healthy
        when(autoJobMapper.queryByDependOnGroupId(GROUP_ID)).thenReturn(job);

        service.repairGroupUnderLock(ticket(), stale);

        // judged on the FRESH state: the current segment is seg2 (AUTOEXEC) — its job is probed,
        // seg1's ghost task/session is NEVER consulted, and nothing is written (job in flight)
        verify(ghostService, never()).findTaskForTicketGroup(anyLong(), anyLong(), any());
        verify(stmtMapper, never()).updateExecSegments(anyLong(), any());
        verify(stmtMapper, never()).updateExecStatus(anyLong(), anyString(), any());
    }

    // ==================== B6 M3: retryGroup resume-from-failed-segment ====================

    @Test
    public void resume_failedGhostSegment_probeFalse_cleansSessionAndRebuilds_m3() {
        TicketSegment g = seg(2, "GHOST", "FAILED", "ALTER TABLE t_big ADD c INT", null, 3);
        g.setSessionId("s-old");
        g.setTaskId(77L);
        g.setError("boom");
        g.setFinishedAt(123L);
        DmTicketDbStmtDO group = resumeGroup("FAILED",
            seg(1, "AUTOEXEC", "SUCCESS", null, List.of(1, 2), null),
            g,
            seg(3, "AUTOEXEC", "PENDING", null, List.of(4, 4), null));
        when(asyncTaskMapper.queryById(77L)).thenReturn(taskWithStatus(AsyncTaskStatus.FAILURE));
        DmExecAutoJobDO remnant = new DmExecAutoJobDO();
        remnant.setStatus(AutoExecJobStatus.FINISH); // seg1's job remnant — must NOT block a GHOST resume
        when(autoJobMapper.queryByDependOnGroupId(GROUP_ID)).thenReturn(remnant);
        when(ghostService.probeAddColumnLanded(OWNER, DS_ID, DB, "ALTER TABLE t_big ADD c INT")).thenReturn(false);

        assertFalse("a rebuild dispatched — the completion callback owns the aggregation",
            service.resumeGroupUnderLock(ticket(), GROUP_ID, "uid-op"));

        // idempotent pre-rebuild cleanup: the old session is released (sidecar cap-2 slot)
        verify(ghostService).releaseSessionQuietly(OWNER, "s-old");
        // the SAME segment re-dispatches with the RETRY via; error/timestamps/bindings cleared
        verify(govTicketV2Service).dispatchGhostSegment(any(DmApprovalDO.class), same(group),
            argThat(s -> s.getSegIdx() == 2 && TicketSegment.STATUS_EXECUTING.equals(s.getStatus())
                && s.getError() == null && s.getFinishedAt() == null
                && s.getSessionId() == null && s.getTaskId() == null),
            eq(GovTicketV2Service.GHOST_VIA_RETRY));
        List<TicketSegment> saved = lastCapturedRuntime();
        assertEquals("SUCCESS segments keep their terminal state (never re-played)",
            TicketSegment.STATUS_SUCCESS, saved.get(0).getStatus());
        assertEquals(TicketSegment.STATUS_EXECUTING, saved.get(1).getStatus());
        assertEquals("downstream segments stay PENDING", TicketSegment.STATUS_PENDING, saved.get(2).getStatus());
        verify(schemaSnapshotService).captureIfMissing(group, TICKET_ID);
        verifyNoInteractions(autoExecService);
    }

    @Test
    public void resume_probeSaysLanded_segmentSuccessWithEvidence_nextDispatched_m3() {
        // M1 check 前瞻①: the writeback was lost but the migration ACTUALLY LANDED — the decidable
        // ADD COLUMN probe finds the target column and advances the segment as SUCCESS (carrying
        // the probe evidence) instead of replaying the ALTER over the altered table
        TicketSegment g = seg(2, "GHOST", "FAILED", "ALTER TABLE t_big ADD c INT", null, 3);
        g.setTable("t_big"); // the plan's probed target (ghostPlan always carries it)
        g.setSessionId("s-old");
        g.setTaskId(77L);
        g.setError("boom");
        DmTicketDbStmtDO group = resumeGroup("FAILED",
            seg(1, "AUTOEXEC", "SUCCESS", null, List.of(1, 2), null),
            g,
            seg(3, "AUTOEXEC", "PENDING", null, List.of(4, 4), null));
        when(asyncTaskMapper.queryById(77L)).thenReturn(taskWithStatus(AsyncTaskStatus.CANCEL));
        when(autoJobMapper.queryByDependOnGroupId(GROUP_ID)).thenReturn(null);
        when(ghostService.probeAddColumnLanded(OWNER, DS_ID, DB, "ALTER TABLE t_big ADD c INT")).thenReturn(true);

        assertFalse("the next segment dispatched — its completion callback owns the aggregation",
            service.resumeGroupUnderLock(ticket(), GROUP_ID, "uid-op"));

        // the cleanup still ran (the session held a slot) but NO rebuild happens
        verify(ghostService).releaseSessionQuietly(OWNER, "s-old");
        verify(govTicketV2Service, never()).dispatchGhostSegment(any(), any(), any(), anyString());
        // the advanceSegment-shaped continuation: the next AUTOEXEC segment dispatches
        ArgumentCaptor<String> jobBiz = ArgumentCaptor.forClass(String.class);
        verify(autoExecService).createGroupJob(same(group), jobBiz.capture(), eq(false),
            eq(ErrorStrategy.NONE), anyString(), eq("uid-op"));
        verify(autoExecService).startJob(jobBiz.getValue(), "uid-op");
        List<List<TicketSegment>> writes = capturedRuntime(2);
        TicketSegment landed = writes.get(0).get(1);
        assertEquals(TicketSegment.STATUS_SUCCESS, landed.getStatus());
        assertNull(landed.getError());
        assertEquals("the summary records WHY this is a success (auditable probe evidence)",
            "TARGET_COLUMN_EXISTS", landed.getSummary().get("resumeProbe"));
        assertEquals("t_big", landed.getSummary().get("table"));
        assertEquals("c", landed.getSummary().get("column"));
        assertEquals(TicketSegment.STATUS_EXECUTING, writes.get(1).get(2).getStatus());
        // mid-chain: the orchestrator never writes a group terminal
        verify(stmtMapper, never()).updateExecStatus(anyLong(), anyString(), any());
    }

    @Test
    public void resume_probeSaysLanded_lastSegment_groupSuccess_m3() {
        TicketSegment g = seg(2, "GHOST", "FAILED", "ALTER TABLE t_big ADD c INT", null, 3);
        g.setTaskId(77L);
        DmTicketDbStmtDO group = resumeGroup("FAILED",
            seg(1, "AUTOEXEC", "SUCCESS", null, List.of(1, 2), null),
            g);
        when(asyncTaskMapper.queryById(77L)).thenReturn(taskWithStatus(AsyncTaskStatus.FAILURE));
        when(autoJobMapper.queryByDependOnGroupId(GROUP_ID)).thenReturn(null);
        when(ghostService.probeAddColumnLanded(OWNER, DS_ID, DB, "ALTER TABLE t_big ADD c INT")).thenReturn(true);

        // last segment → the group terminal write lands and NOTHING dispatches: the return flag
        // tells the caller (retryGroup) to run the shared aggregation tail itself — no completion
        // callback will ever fire and the EXEC_FAIL ticket is outside the sweep IN set
        assertTrue("the probe wrote the group terminal synchronously — the caller must aggregate",
            service.resumeGroupUnderLock(ticket(), GROUP_ID, "uid-op"));

        verify(stmtMapper).updateExecStatus(GROUP_ID, "SUCCESS", null);
        verifyNoInteractions(autoExecService);
        verify(govTicketV2Service, never()).dispatchGhostSegment(any(), any(), any(), anyString());
    }

    @Test
    public void resume_failedAutoexecSegment_rebuildsJob_ghostSuccessBindingsUntouched_m3() {
        TicketSegment g1 = seg(1, "GHOST", "SUCCESS", "ALTER TABLE t_big ADD c INT", null, 1);
        g1.setSessionId("s-1");
        g1.setTaskId(71L);
        TicketSegment a2 = seg(2, "AUTOEXEC", "FAILED", null, List.of(2, 3), null);
        a2.setJobBizId("job-old");
        a2.setError("syntax error");
        a2.setFinishedAt(999L);
        a2.setSummary(java.util.Map.of("status", "FAILED"));
        DmTicketDbStmtDO group = resumeGroup("FAILED", g1, a2,
            seg(3, "GHOST", "PENDING", "ALTER TABLE t2 ADD d INT", null, 4));
        when(asyncTaskMapper.queryById(71L)).thenReturn(taskWithStatus(AsyncTaskStatus.COMPLETE));
        DmExecAutoJobDO dead = new DmExecAutoJobDO();
        dead.setStatus(AutoExecJobStatus.FAILED);
        when(autoJobMapper.queryByDependOnGroupId(GROUP_ID)).thenReturn(dead);

        assertFalse(service.resumeGroupUnderLock(ticket(), GROUP_ID, "uid-op"));

        // AUTOEXEC resume = 整段重跑 through the standard segment dispatch (createGroupJob deletes
        // the old job row itself — the uk idempotency of contract :417)
        ArgumentCaptor<String> jobBiz = ArgumentCaptor.forClass(String.class);
        verify(autoExecService).createGroupJob(same(group), jobBiz.capture(), eq(false),
            eq(ErrorStrategy.NONE), anyString(), eq("uid-op"));
        verify(autoExecService).startJob(jobBiz.getValue(), "uid-op");
        assertNotEquals("job-old", jobBiz.getValue());
        // no ghost cleanup/probe for an AUTOEXEC target; the SUCCESS ghost segment keeps its bindings
        verify(ghostService, never()).releaseSessionQuietly(anyString(), anyString());
        verify(ghostService, never()).probeAddColumnLanded(anyString(), anyLong(), anyString(), anyString());
        verify(govTicketV2Service, never()).dispatchGhostSegment(any(), any(), any(), anyString());
        List<TicketSegment> saved = lastCapturedRuntime();
        assertEquals(TicketSegment.STATUS_SUCCESS, saved.get(0).getStatus());
        assertEquals("s-1", saved.get(0).getSessionId());
        TicketSegment resumed = saved.get(1);
        assertEquals(TicketSegment.STATUS_EXECUTING, resumed.getStatus());
        assertEquals(jobBiz.getValue(), resumed.getJobBizId());
        assertNull(resumed.getError());
        assertNull(resumed.getSummary());
        assertNull(resumed.getFinishedAt());
        assertEquals(TicketSegment.STATUS_PENDING, saved.get(2).getStatus());
    }

    @Test
    public void resume_activeGhostTaskOnAnySegment_rejected_zeroSideEffects_m3() {
        // the ruling's 任一段 active check: even a NON-target segment's active task rejects
        TicketSegment g1 = seg(1, "GHOST", "SUCCESS", "ALTER TABLE t_big ADD c INT", null, 1);
        g1.setTaskId(71L);
        DmTicketDbStmtDO group = resumeGroup("FAILED", g1,
            seg(2, "AUTOEXEC", "FAILED", null, List.of(2, 2), null));
        when(asyncTaskMapper.queryById(71L)).thenReturn(taskWithStatus(AsyncTaskStatus.RUNNING));
        when(autoJobMapper.queryByDependOnGroupId(GROUP_ID)).thenReturn(null);

        try {
            service.resumeGroupUnderLock(ticket(), GROUP_ID, "uid-op");
            fail("Expected ErrorMessageException");
        } catch (com.clougence.clouddm.api.common.exception.ErrorMessageException expected) {
            // GOV_TICKET_V2_GHOST_TASK_ACTIVE
        }
        verify(stmtMapper, never()).updateExecSegments(anyLong(), any());
        verify(stmtMapper, never()).updateExecStatus(anyLong(), anyString(), any());
        verify(ghostService, never()).releaseSessionQuietly(anyString(), anyString());
        verifyNoInteractions(autoExecService);
        verifyNoInteractions(govTicketV2Service);
    }

    @Test
    public void resume_inFlightJob_rejected_m3() {
        DmTicketDbStmtDO group = resumeGroup("FAILED",
            seg(1, "AUTOEXEC", "FAILED", null, List.of(1, 2), null));
        DmExecAutoJobDO active = new DmExecAutoJobDO();
        active.setStatus(AutoExecJobStatus.EXECUTING);
        when(autoJobMapper.queryByDependOnGroupId(GROUP_ID)).thenReturn(active);

        try {
            service.resumeGroupUnderLock(ticket(), GROUP_ID, "uid-op");
            fail("Expected ErrorMessageException");
        } catch (com.clougence.clouddm.api.common.exception.ErrorMessageException expected) {
            // GOV_RELEASE_STMT_JOB_ACTIVE
        }
        verify(stmtMapper, never()).updateExecSegments(anyLong(), any());
        verifyNoInteractions(autoExecService);
    }

    @Test
    public void resume_finishJobOverAutoexecTarget_rejected_sweepOwnsTheLostWriteback_m3() {
        // job FINISH + the segment still EXECUTING = a lost jobFinish writeback — the sweep
        // advances it as SUCCESS; a retry replay would re-run landed statements (legacy :394-399 parity)
        DmTicketDbStmtDO group = resumeGroup("EXECUTING",
            seg(1, "AUTOEXEC", "EXECUTING", null, List.of(1, 2), null));
        DmExecAutoJobDO finished = new DmExecAutoJobDO();
        finished.setStatus(AutoExecJobStatus.FINISH);
        when(autoJobMapper.queryByDependOnGroupId(GROUP_ID)).thenReturn(finished);

        try {
            service.resumeGroupUnderLock(ticket(), GROUP_ID, "uid-op");
            fail("Expected ErrorMessageException");
        } catch (com.clougence.clouddm.api.common.exception.ErrorMessageException expected) {
            // GOV_RELEASE_STMT_JOB_ACTIVE
        }
        verifyNoInteractions(autoExecService);
    }

    @Test
    public void resume_corruptRuntime_rejectedWithRevisionGuidance_m3() {
        // a FAILED group whose per-segment history is unparsable cannot resume safely (a whole-text
        // replay could re-run a landed ghost ALTER) → the return-revise loop is the recovery path
        DmTicketDbStmtDO group = group("FAILED");
        group.setPrecheckResult(planJson());
        group.setExecSegments("{not json");
        when(stmtMapper.queryById(GROUP_ID)).thenReturn(group);

        try {
            service.resumeGroupUnderLock(ticket(), GROUP_ID, "uid-op");
            fail("Expected ErrorMessageException");
        } catch (com.clougence.clouddm.api.common.exception.ErrorMessageException ex) {
            assertTrue("the rejection must surface the revision guidance",
                ex.getMessage().contains("退回修订") || ex.getMessage().contains("revision"));
        }
        verify(stmtMapper, never()).updateExecSegments(anyLong(), any());
        verifyNoInteractions(autoExecService);
        verifyNoInteractions(govTicketV2Service);
    }

    @Test
    public void resume_pendingNoRuntime_initsFromPlan_dispatchesFirstSegment_m3() {
        // pre-first-dispatch shape (the first dispatch rolled back): nothing ever executed — the
        // runtime is rebuilt from the plan and the FIRST segment dispatches (not a "resume")
        DmTicketDbStmtDO group = group("PENDING");
        group.setPrecheckResult(planJson());
        when(stmtMapper.queryById(GROUP_ID)).thenReturn(group);
        when(autoJobMapper.queryByDependOnGroupId(GROUP_ID)).thenReturn(null);

        assertFalse(service.resumeGroupUnderLock(ticket(), GROUP_ID, "uid-op"));

        ArgumentCaptor<String> jobBiz = ArgumentCaptor.forClass(String.class);
        verify(autoExecService).createGroupJob(same(group), jobBiz.capture(), eq(false),
            eq(ErrorStrategy.NONE), anyString(), eq("uid-op"));
        verify(autoExecService).startJob(jobBiz.getValue(), "uid-op");
        List<TicketSegment> saved = lastCapturedRuntime();
        assertEquals(3, saved.size());
        assertEquals(TicketSegment.STATUS_EXECUTING, saved.get(0).getStatus());
        assertEquals(TicketSegment.STATUS_PENDING, saved.get(1).getStatus());
        verify(schemaSnapshotService).captureIfMissing(group, TICKET_ID);
    }

    @Test
    public void resume_allSegmentsSuccess_rejected_inconsistentCarrier_m3() {
        DmTicketDbStmtDO group = resumeGroup("FAILED",
            seg(1, "GHOST", "SUCCESS", "ALTER TABLE t_big ADD c INT", null, 1),
            seg(2, "AUTOEXEC", "SUCCESS", null, List.of(2, 2), null));

        try {
            service.resumeGroupUnderLock(ticket(), GROUP_ID, "uid-op");
            fail("Expected ErrorMessageException");
        } catch (com.clougence.clouddm.api.common.exception.ErrorMessageException expected) {
            // GOV_TICKET_V2_GROUP_NOT_FAILED — nothing to resume (the sweep owns the group write)
        }
        verify(stmtMapper, never()).updateExecSegments(anyLong(), any());
        verifyNoInteractions(autoExecService);
    }

    @Test
    public void resume_statusChangedUnderLock_rejected_freshReadDiscipline_m3() {
        // the caller's pre-lock row said FAILED; a concurrent hook/sweep already wrote SUCCESS —
        // the fresh under-lock read is the authority (dispatchFirstSegment/check-F1 discipline)
        DmTicketDbStmtDO stale = group("FAILED");
        stale.setExecSegments("[{\"segIdx\":1,\"type\":\"GHOST\",\"stmtIndex\":1,\"status\":\"FAILED\"}]");
        DmTicketDbStmtDO fresh = resumeGroup("SUCCESS",
            seg(1, "GHOST", "SUCCESS", "ALTER TABLE t_big ADD c INT", null, 1));

        try {
            service.resumeGroupUnderLock(ticket(), GROUP_ID, "uid-op");
            fail("Expected ErrorMessageException");
        } catch (com.clougence.clouddm.api.common.exception.ErrorMessageException expected) {
            // GOV_TICKET_V2_GROUP_NOT_FAILED
        }
        verify(stmtMapper, never()).updateExecSegments(anyLong(), any());
        verifyNoInteractions(autoExecService);
        verifyNoInteractions(govTicketV2Service);
        verifyNoInteractions(ghostService);
    }

    // ==================== B6 M3: sweep dead-judgement orphan-session convergence ====================

    @Test
    public void repair_ghostSegmentDeadJudgement_releasesOrphanSession_m3() {
        // M2 check 裁决项 2: the dead judgement must return the sidecar GHOST slot (cap 2) — the
        // release keys on the RUNTIME binding (survives a missing/terminal task row)
        TicketSegment g = seg(1, "GHOST", "EXECUTING", "ALTER TABLE t_big ADD c INT", null, 1);
        g.setSessionId("s-1");
        g.setTaskId(77L);
        DmTicketDbStmtDO group = resumeGroup("EXECUTING", g,
            seg(2, "AUTOEXEC", "PENDING", null, List.of(2, 2), null));
        when(ghostService.findTaskForTicketGroup(TICKET_ID, GROUP_ID, 1))
            .thenReturn(ghostTask(AsyncTaskStatus.COMPLETE, "session-x", OWNER));

        service.repairGroupUnderLock(ticket(), group);

        verify(ghostService).releaseSessionQuietly(OWNER, "s-1");
        verify(stmtMapper).updateExecStatus(eq(GROUP_ID), eq("FAILED"), contains("writeback lost"));
    }

    @Test
    public void repair_ghostSessionLostJudgement_releaseStillAttempted_neverThrows_m3() {
        TicketSegment g = seg(1, "GHOST", "EXECUTING", "ALTER TABLE t_big ADD c INT", null, 1);
        g.setSessionId("s-1");
        DmTicketDbStmtDO group = resumeGroup("EXECUTING", g);
        when(ghostService.findTaskForTicketGroup(TICKET_ID, GROUP_ID, 1))
            .thenReturn(ghostTask(AsyncTaskStatus.RUNNING, "session-x", OWNER));
        when(ghostService.hasSession(OWNER, "session-x")).thenReturn(false);
        doThrow(new RuntimeException("sidecar gone")).when(ghostService)
            .releaseSessionQuietly(anyString(), anyString());

        service.repairGroupUnderLock(ticket(), group); // must not throw

        verify(ghostService).releaseSessionQuietly(OWNER, "s-1");
        verify(stmtMapper).updateExecStatus(eq(GROUP_ID), eq("FAILED"), contains("session lost"));
    }

    @Test
    public void repair_autoexecDeadJudgement_noSessionRelease_m3() {
        DmTicketDbStmtDO group = resumeGroup("EXECUTING",
            seg(1, "AUTOEXEC", "EXECUTING", null, List.of(1, 2), null));
        when(autoJobMapper.queryByDependOnGroupId(GROUP_ID)).thenReturn(null);

        service.repairGroupUnderLock(ticket(), group);

        verify(ghostService, never()).releaseSessionQuietly(anyString(), anyString());
        verify(stmtMapper).updateExecStatus(eq(GROUP_ID), eq("FAILED"), contains("record missing"));
    }

    // ==================== helpers ====================

    private DmApprovalDO ticket() {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setBizId("biz-900");
        ticket.setPrimaryUid(PUID);
        ticket.setOwnerUid(OWNER);
        return ticket;
    }

    private DmTicketDbStmtDO group(String execStatus) {
        DmTicketDbStmtDO group = new DmTicketDbStmtDO();
        group.setId(GROUP_ID);
        group.setTicketId(TICKET_ID);
        group.setPairId(300L);
        group.setDsId(DS_ID);
        group.setDbName(DB);
        group.setSqlContent("INSERT INTO a VALUES (1); UPDATE b SET x=1; ALTER TABLE t_big ADD c INT;");
        group.setPrecheckResult("{\"checkStatus\":\"PASS\",\"changeType\":\"MIXED\"}");
        group.setExecStatus(execStatus);
        return group;
    }

    /** a group EXECUTING with the given runtime segments persisted on it. */
    private DmTicketDbStmtDO segmentedGroup(TicketSegment... segments) {
        DmTicketDbStmtDO group = group("EXECUTING");
        group.setExecSegments(JsonUtils.toJson(List.of(segments)));
        when(stmtMapper.queryById(GROUP_ID)).thenReturn(group); // the under-lock fresh re-read
        return group;
    }

    /** M3 resume shape: the given exec status + runtime segments + the canonical plan. */
    private DmTicketDbStmtDO resumeGroup(String execStatus, TicketSegment... segments) {
        DmTicketDbStmtDO group = group(execStatus);
        group.setPrecheckResult(planJson());
        group.setExecSegments(JsonUtils.toJson(List.of(segments)));
        when(stmtMapper.queryById(GROUP_ID)).thenReturn(group); // the under-lock fresh re-read
        return group;
    }

    private static DmExecAsyncTaskDO taskWithStatus(AsyncTaskStatus status) {
        DmExecAsyncTaskDO task = new DmExecAsyncTaskDO();
        task.setId(77L);
        task.setBizType("GHOST");
        task.setStatus(status);
        return task;
    }

    private static TicketSegment seg(int segIdx, String type, String status, String sql,
                                     List<Integer> stmtRange, Integer stmtIndex) {
        TicketSegment seg = new TicketSegment();
        seg.setSegIdx(segIdx);
        seg.setType(type);
        seg.setStatus(status);
        seg.setSql(sql);
        seg.setStmtRange(stmtRange);
        seg.setStmtIndex(stmtIndex);
        return seg;
    }

    /** Parse the group's runtime for mutation; call {@link #commit} after editing. */
    private static List<TicketSegment> runtimeOf(DmTicketDbStmtDO group) {
        return TicketSegment.parseRuntime(group.getExecSegments());
    }

    private static void commit(DmTicketDbStmtDO group, List<TicketSegment> runtime) {
        group.setExecSegments(JsonUtils.toJson(runtime));
    }

    /** the canonical 3-segment plan JSON: [AUTOEXEC[1,2], GHOST(3), AUTOEXEC[4,4]]. */
    private static String planJson() {
        return "{\"checkStatus\":\"PASS\",\"changeType\":\"MIXED\",\"segments\":["
            + "{\"segIdx\":1,\"type\":\"AUTOEXEC\",\"stmtRange\":[1,2]},"
            + "{\"segIdx\":2,\"type\":\"GHOST\",\"stmtIndex\":3,\"table\":\"t_big\",\"sql\":\"ALTER TABLE t_big ADD c INT\"},"
            + "{\"segIdx\":3,\"type\":\"AUTOEXEC\",\"stmtRange\":[4,4]}]}";
    }

    private static DmExecAsyncTaskDO ghostTask(AsyncTaskStatus status, String sessionId, String ownerUid) {
        GhostAsyncTaskConfig cfg = new GhostAsyncTaskConfig();
        cfg.setUserId(ownerUid);
        cfg.setSessionId(sessionId);
        cfg.setTicketId(TICKET_ID);
        cfg.setGroupId(GROUP_ID);
        DmExecAsyncTaskDO task = new DmExecAsyncTaskDO();
        task.setId(77L);
        task.setBizId(sessionId);
        task.setBizType("GHOST");
        task.setStatus(status);
        task.setUid(ownerUid);
        task.setConfigData(JsonUtils.toJson(cfg));
        return task;
    }

    /** every runtime JSON persisted through updateExecSegments, in call order. */
    private List<List<TicketSegment>> capturedRuntime(int expectedWrites) {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(stmtMapper, times(expectedWrites)).updateExecSegments(eq(GROUP_ID), captor.capture());
        return captor.getAllValues().stream().map(TicketSegment::parseRuntime).toList();
    }

    private List<TicketSegment> lastCapturedRuntime() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(stmtMapper, atLeastOnce()).updateExecSegments(eq(GROUP_ID), captor.capture());
        List<String> all = captor.getAllValues();
        return TicketSegment.parseRuntime(all.get(all.size() - 1));
    }
}
