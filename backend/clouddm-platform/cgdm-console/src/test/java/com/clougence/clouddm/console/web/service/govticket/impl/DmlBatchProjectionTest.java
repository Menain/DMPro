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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.component.governance.TicketSegment;
import com.clougence.clouddm.console.web.model.vo.govticket.GovTicketV2GroupVO;
import com.clougence.clouddm.console.web.service.dmlbatch.DmlBatchEffectiveParams;
import com.clougence.clouddm.console.web.service.dmlbatch.DmlBatchService;
import com.clougence.clouddm.console.web.service.dmlbatch.asyntask.DmlBatchAsyncTaskConfig;
import com.clougence.clouddm.console.web.service.dmlbatch.asyntask.DmlBatchCheckpoint;
import com.clougence.clouddm.console.web.service.ghost.GhostService;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.TicketDbStmtDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.govticket.DmTicketDbStmtMapper;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.execution.AsyncTaskStatus;
import com.clougence.clouddm.platform.dal.model.execution.DmExecAsyncTaskDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;
import com.clougence.utils.JsonUtils;

/**
 * B7 N3 (implement.md N3 「groupList 投影扩展 / 投影(flags 门控/checkpoint 映射/UNRESOLVED 透出)」):
 * the additive DML_BATCH twin of the B6 ghost projection — the newest bound batch task's
 * configData checkpoint projected into {@code GroupVO.dmlBatchProgress} under the SAME widened
 * flags gate (dual-write coupling pin), riding the existing 5s group refresh (ZERO new polling
 * endpoints). Pins: the flags pre-filter, the full checkpoint mapping (batches/rows/percent/
 * lagState/paused), the TERMINAL-GATED unresolved derivation (intent ∧ ¬retryPending ∨ the N2
 * re-frozen unconsumed-SKIP shape with intentSeq = batchesDone+1), the healthy in-flight intent
 * (a RUNNING task is never "unresolved"), retryPending exposure, and the never-throw degradation
 * (absent task / unparsable configData / mapper failure → null projection, the list survives).
 * The frozen GhostGroupListTest suite (9 pins) stays zero-change — its harness predates the
 * dmlBatchService field, which is exactly the engine-unwired degradation this suite also pins.
 * <p>
 * Discipline: JUnit4 + handwritten mocks + ReflectionTestUtils; no Spring context.
 */
public class DmlBatchProjectionTest {

    private GovTicketV2ServiceImpl service;
    private DmTicketDbStmtMapper   stmtMapper;
    private DmApprovalMapper       approvalMapper;
    private GhostService           ghostService;
    private DmlBatchService        dmlBatchService;

    private static final String PUID           = "puid-001";
    private static final String UID            = "uid-001";
    private static final long   TICKET_ID      = 900L;
    private static final long   BATCH_GROUP_ID = 100L;  // id-ASC position 0 → flags[0]
    private static final long   PLAIN_GROUP_ID = 101L;  // id-ASC position 1 → flags[1]
    private static final long   TASK_ID        = 55L;

    @Before
    public void setUp() {
        service = new GovTicketV2ServiceImpl();

        TicketDbStmtDal ticketDbStmtDal = mock(TicketDbStmtDal.class);
        stmtMapper = mock(DmTicketDbStmtMapper.class);
        when(ticketDbStmtDal.stmtMapper()).thenReturn(stmtMapper);

        ApprovalDal approvalDal = mock(ApprovalDal.class);
        approvalMapper = mock(DmApprovalMapper.class);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);

        ghostService = mock(GhostService.class);
        dmlBatchService = mock(DmlBatchService.class);

        ReflectionTestUtils.setField(service, "ticketDbStmtDal", ticketDbStmtDal);
        ReflectionTestUtils.setField(service, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(service, "ghostService", ghostService);
        ReflectionTestUtils.setField(service, "dmlBatchService", dmlBatchService);

        // default fixture: two groups, the FIRST one DML_BATCH-routed (positional flags contract)
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket(List.of(true, false)));
        when(stmtMapper.queryByTicketId(TICKET_ID))
            .thenReturn(List.of(group(BATCH_GROUP_ID), group(PLAIN_GROUP_ID)));
        // the ghost lookup no-ops by default (a batch-routed group has no GHOST task)
        when(ghostService.findTaskForTicketGroup(anyLong(), anyLong())).thenReturn(null);
    }

    // ==================== fixtures ====================

    private DmApprovalDO ticket(List<Boolean> routeFlags) {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setBizId("biz-900");
        ticket.setPrimaryUid(PUID);
        ticket.setOwnerUid(UID);
        ApprovalMO mo = new ApprovalMO();
        mo.setTicketType("PROD_DML");
        mo.setGhostRouteFlags(routeFlags);
        ticket.setTicketInfo(JsonUtils.toJson(mo));
        return ticket;
    }

    private DmTicketDbStmtDO group(long groupId) {
        DmTicketDbStmtDO group = new DmTicketDbStmtDO();
        group.setId(groupId);
        group.setTicketId(TICKET_ID);
        group.setPairId(300L);
        group.setDsId(20L);
        group.setDbName("db-1");
        group.setSqlContent("DELETE FROM t_order WHERE status = 0");
        group.setExecStatus("EXECUTING");
        return group;
    }

    private DmExecAsyncTaskDO batchTask(AsyncTaskStatus status, DmlBatchAsyncTaskConfig cfg) {
        DmExecAsyncTaskDO task = new DmExecAsyncTaskDO();
        task.setId(TASK_ID);
        task.setBizId("batch-biz");
        task.setBizType(DmlBatchService.BIZ_TYPE);
        task.setStatus(status);
        task.setUid(UID);
        task.setConfigData(cfg == null ? null : JsonUtils.toJson(cfg));
        return task;
    }

    private DmlBatchAsyncTaskConfig cfg(DmlBatchCheckpoint checkpoint) {
        DmlBatchAsyncTaskConfig cfg = new DmlBatchAsyncTaskConfig();
        cfg.setBizId("batch-biz");
        cfg.setTicketId(TICKET_ID);
        cfg.setGroupId(BATCH_GROUP_ID);
        cfg.setSegmentIndex(2);
        cfg.setUserId(UID);
        cfg.setDsId(20L);
        cfg.setDbName("db-1");
        cfg.setStmtSql("DELETE FROM t_order WHERE status = 0");
        cfg.setBatchKey(new TicketSegment.BatchKey("id", "INT"));
        cfg.setEffectiveParams(new DmlBatchEffectiveParams());
        cfg.setEstimatedRows(5_000_000L);
        cfg.setIdempotent(Boolean.FALSE);
        cfg.setCheckpoint(checkpoint);
        return cfg;
    }

    private GovTicketV2GroupVO.DmlBatchProgressVO project() {
        return service.groupList(PUID, UID, TICKET_ID).get(0).getDmlBatchProgress();
    }

    // ==================== flags gate ====================

    @Test
    public void flagsGate_unroutedTicket_neverTouchesBatchMapper() {
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket(null));

        List<GovTicketV2GroupVO> list = service.groupList(PUID, UID, TICKET_ID);

        assertEquals(2, list.size());
        assertNull(list.get(0).getDmlBatchProgress());
        verify(dmlBatchService, never()).findTaskForTicketGroup(anyLong(), anyLong(), any());
    }

    @Test
    public void flagsGate_positionFalseGroup_notProjected() {
        when(dmlBatchService.findTaskForTicketGroup(eq(TICKET_ID), eq(BATCH_GROUP_ID), isNull()))
            .thenReturn(batchTask(AsyncTaskStatus.RUNNING, cfg(new DmlBatchCheckpoint())));

        List<GovTicketV2GroupVO> list = service.groupList(PUID, UID, TICKET_ID);

        assertNotNull(list.get(0).getDmlBatchProgress());
        assertNull(list.get(1).getDmlBatchProgress());
        // the positional pre-filter is real: the plain group never touches the batch lookup
        verify(dmlBatchService, never()).findTaskForTicketGroup(eq(TICKET_ID), eq(PLAIN_GROUP_ID), any());
    }

    @Test
    public void routedGroup_taskMissing_nullProjection_baseFieldsIntact() {
        when(dmlBatchService.findTaskForTicketGroup(eq(TICKET_ID), eq(BATCH_GROUP_ID), isNull()))
            .thenReturn(null);

        GovTicketV2GroupVO vo = service.groupList(PUID, UID, TICKET_ID).get(0);

        assertNull(vo.getDmlBatchProgress());
        assertEquals("db-1", vo.getDbName());
        assertEquals("EXECUTING", vo.getExecStatus());
    }

    // ==================== checkpoint mapping ====================

    @Test
    public void runningTask_fullCheckpointMapping() {
        DmlBatchCheckpoint cp = new DmlBatchCheckpoint();
        cp.setCursor("50000");
        cp.setBatchesDone(10);
        cp.setRowsDone(50_000L);
        cp.setLastTickAt(System.currentTimeMillis());
        cp.setLastStatus("RUNNING");
        DmlBatchCheckpoint.LagState lag = new DmlBatchCheckpoint.LagState();
        lag.setVisible(true);
        lag.setLastLagSec(2.5);
        cp.setLagState(lag);
        when(dmlBatchService.findTaskForTicketGroup(eq(TICKET_ID), eq(BATCH_GROUP_ID), isNull()))
            .thenReturn(batchTask(AsyncTaskStatus.RUNNING, cfg(cp)));

        GovTicketV2GroupVO.DmlBatchProgressVO p = project();

        assertNotNull(p);
        assertEquals(Long.valueOf(TASK_ID), p.getTaskId());
        assertEquals(Integer.valueOf(2), p.getSegmentIndex());
        assertEquals("RUNNING", p.getTaskStatus());
        assertEquals(Boolean.FALSE, p.getPaused());
        assertEquals(Long.valueOf(10L), p.getBatchesDone());
        assertEquals(Long.valueOf(50_000L), p.getRowsDone());
        assertEquals(Long.valueOf(5_000_000L), p.getEstimatedRows());
        assertEquals(Integer.valueOf(1), p.getPercentApprox()); // 50000×100/5000000 = 1
        assertEquals(Boolean.FALSE, p.getIdempotent());
        assertNotNull(p.getLagState());
        assertEquals(Boolean.TRUE, p.getLagState().getVisible());
        assertEquals(Double.valueOf(2.5), p.getLagState().getLastLagSec());
        assertEquals(Boolean.FALSE, p.getLagState().getThrottled());
        assertNull("a healthy in-flight task is never unresolved", p.getUnresolvedIntent());
    }

    @Test
    public void runningTask_inFlightIntent_neverUnresolved_terminalGate() {
        // the TERMINAL-GATED derivation: intent ∧ ¬retryPending on a RUNNING task is a batch in
        // flight (the writeBackTerminal marker authority is the segment summary, not this field)
        DmlBatchCheckpoint cp = new DmlBatchCheckpoint();
        DmlBatchCheckpoint.Intent intent = new DmlBatchCheckpoint.Intent();
        intent.setSeq(11L);
        intent.setLo("50000");
        cp.setIntent(intent);
        cp.setBatchesDone(10);
        when(dmlBatchService.findTaskForTicketGroup(eq(TICKET_ID), eq(BATCH_GROUP_ID), isNull()))
            .thenReturn(batchTask(AsyncTaskStatus.RUNNING, cfg(cp)));

        GovTicketV2GroupVO.DmlBatchProgressVO p = project();

        assertNull(p.getUnresolvedIntent());
        assertNull(p.getIntentSeq());
    }

    @Test
    public void pausedTask_pausedFlag() {
        DmlBatchCheckpoint cp = new DmlBatchCheckpoint();
        cp.setLastStatus("PAUSED");
        when(dmlBatchService.findTaskForTicketGroup(eq(TICKET_ID), eq(BATCH_GROUP_ID), isNull()))
            .thenReturn(batchTask(AsyncTaskStatus.PAUSE, cfg(cp)));

        GovTicketV2GroupVO.DmlBatchProgressVO p = project();

        assertEquals(Boolean.TRUE, p.getPaused());
        assertEquals("PAUSE", p.getTaskStatus());
    }

    @Test
    public void throttledLagState_throttledTrue() {
        DmlBatchCheckpoint cp = new DmlBatchCheckpoint();
        DmlBatchCheckpoint.LagState lag = new DmlBatchCheckpoint.LagState();
        lag.setVisible(true);
        lag.setLastLagSec(25.0);
        lag.setThrottledAt(System.currentTimeMillis());
        cp.setLagState(lag);
        when(dmlBatchService.findTaskForTicketGroup(eq(TICKET_ID), eq(BATCH_GROUP_ID), isNull()))
            .thenReturn(batchTask(AsyncTaskStatus.RUNNING, cfg(cp)));

        GovTicketV2GroupVO.DmlBatchProgressVO p = project();

        assertEquals(Boolean.TRUE, p.getLagState().getThrottled());
        assertEquals(Double.valueOf(25.0), p.getLagState().getLastLagSec());
    }

    @Test
    public void invisibleLagState_visibleFalse() {
        DmlBatchCheckpoint cp = new DmlBatchCheckpoint();
        DmlBatchCheckpoint.LagState lag = new DmlBatchCheckpoint.LagState();
        lag.setVisible(false); // probe failure / permission gap → the fixed-interval degradation
        cp.setLagState(lag);
        when(dmlBatchService.findTaskForTicketGroup(eq(TICKET_ID), eq(BATCH_GROUP_ID), isNull()))
            .thenReturn(batchTask(AsyncTaskStatus.RUNNING, cfg(cp)));

        GovTicketV2GroupVO.DmlBatchProgressVO p = project();

        assertEquals(Boolean.FALSE, p.getLagState().getVisible());
        assertFalse(p.getLagState().getThrottled());
        assertNull(p.getLagState().getLastLagSec());
    }

    // ==================== UNRESOLVED exposure (the modal material) ====================

    @Test
    public void failureTask_windowCrashIntent_unresolvedExposed() {
        DmlBatchCheckpoint cp = new DmlBatchCheckpoint();
        cp.setBatchesDone(20);
        cp.setRowsDone(100_000L);
        DmlBatchCheckpoint.Intent intent = new DmlBatchCheckpoint.Intent();
        intent.setSeq(21L); // intent ∧ ¬retryPending = the true window crash
        intent.setLo("100000");
        cp.setIntent(intent);
        cp.setLastStatus("UNRESOLVED");
        when(dmlBatchService.findTaskForTicketGroup(eq(TICKET_ID), eq(BATCH_GROUP_ID), isNull()))
            .thenReturn(batchTask(AsyncTaskStatus.FAILURE, cfg(cp)));

        GovTicketV2GroupVO.DmlBatchProgressVO p = project();

        assertEquals(Boolean.TRUE, p.getUnresolvedIntent());
        assertEquals(Long.valueOf(21L), p.getIntentSeq());
        assertEquals(Boolean.FALSE, p.getIdempotent()); // the modal's red copy material
        assertNull(p.getRetryPending());
    }

    @Test
    public void failureTask_retryPendingIntent_notUnresolved_errorExposed() {
        // 裁决 #21 narrowing: a recorded DB error proves zero effect — the retry flow, not the
        // modal; lastBatchError is the auxiliary display line
        DmlBatchCheckpoint cp = new DmlBatchCheckpoint();
        cp.setBatchesDone(20);
        DmlBatchCheckpoint.Intent intent = new DmlBatchCheckpoint.Intent();
        intent.setSeq(21L);
        intent.setRetryPending(true);
        intent.setRetryCount(3);
        intent.setLastBatchError("Lock wait timeout exceeded");
        cp.setIntent(intent);
        when(dmlBatchService.findTaskForTicketGroup(eq(TICKET_ID), eq(BATCH_GROUP_ID), isNull()))
            .thenReturn(batchTask(AsyncTaskStatus.FAILURE, cfg(cp)));

        GovTicketV2GroupVO.DmlBatchProgressVO p = project();

        assertEquals(Boolean.FALSE, p.getUnresolvedIntent());
        assertNull(p.getIntentSeq());
        assertEquals(Boolean.TRUE, p.getRetryPending());
        assertEquals("Lock wait timeout exceeded", p.getLastBatchError());
    }

    @Test
    public void failureTask_unconsumedSkipVerdict_refrozenShape_unresolvedWithDerivedSeq() {
        // the N2 check re-freeze shape: the SKIP-launched task died BEFORE consuming the verdict —
        // no intent on the carrier, intentSeq = batchesDone+1 by construction, and the
        // skipVerdictPending flag drives the 「上次跳过未成功,请重新裁决」 copy
        DmlBatchCheckpoint cp = new DmlBatchCheckpoint();
        cp.setBatchesDone(20);
        cp.setRowsDone(100_000L);
        cp.setCursor("100000");
        DmlBatchAsyncTaskConfig cfg = cfg(cp);
        cfg.setPendingResolution(TicketSegment.RESOLUTION_SKIP_BATCH);
        when(dmlBatchService.findTaskForTicketGroup(eq(TICKET_ID), eq(BATCH_GROUP_ID), isNull()))
            .thenReturn(batchTask(AsyncTaskStatus.FAILURE, cfg));

        GovTicketV2GroupVO.DmlBatchProgressVO p = project();

        assertEquals(Boolean.TRUE, p.getUnresolvedIntent());
        assertEquals(Long.valueOf(21L), p.getIntentSeq());
        assertEquals(Boolean.TRUE, p.getSkipVerdictPending());
    }

    @Test
    public void completeTask_noIntent_notUnresolved() {
        DmlBatchCheckpoint cp = new DmlBatchCheckpoint();
        cp.setBatchesDone(1000);
        cp.setRowsDone(5_000_000L);
        cp.setLastStatus("COMPLETED");
        when(dmlBatchService.findTaskForTicketGroup(eq(TICKET_ID), eq(BATCH_GROUP_ID), isNull()))
            .thenReturn(batchTask(AsyncTaskStatus.COMPLETE, cfg(cp)));

        GovTicketV2GroupVO.DmlBatchProgressVO p = project();

        assertEquals(Boolean.FALSE, p.getUnresolvedIntent());
        assertEquals(Integer.valueOf(100), p.getPercentApprox());
    }

    // ==================== percent edges ====================

    @Test
    public void percentApprox_noDenominator_null_and_cappedAt100() {
        DmlBatchCheckpoint cp = new DmlBatchCheckpoint();
        cp.setRowsDone(500L);
        DmlBatchAsyncTaskConfig noDenominator = cfg(cp);
        noDenominator.setEstimatedRows(null);
        when(dmlBatchService.findTaskForTicketGroup(eq(TICKET_ID), eq(BATCH_GROUP_ID), isNull()))
            .thenReturn(batchTask(AsyncTaskStatus.RUNNING, noDenominator));
        assertNull(project().getPercentApprox());

        // drift past the estimate (the denominator is an EXPLAIN "约") → capped, never >100
        DmlBatchCheckpoint over = new DmlBatchCheckpoint();
        over.setRowsDone(7_000_000L);
        when(dmlBatchService.findTaskForTicketGroup(eq(TICKET_ID), eq(BATCH_GROUP_ID), isNull()))
            .thenReturn(batchTask(AsyncTaskStatus.RUNNING, cfg(over)));
        assertEquals(Integer.valueOf(100), project().getPercentApprox());
    }

    // ==================== never-throw degradation ====================

    @Test
    public void unparsableConfigData_taskLevelFieldsStand_checkpointDegrades() {
        DmExecAsyncTaskDO task = batchTask(AsyncTaskStatus.RUNNING, null);
        task.setConfigData("{not-json");
        when(dmlBatchService.findTaskForTicketGroup(eq(TICKET_ID), eq(BATCH_GROUP_ID), isNull()))
            .thenReturn(task);

        GovTicketV2GroupVO.DmlBatchProgressVO p = project();

        assertNotNull(p);
        assertEquals(Long.valueOf(TASK_ID), p.getTaskId());
        assertEquals("RUNNING", p.getTaskStatus());
        assertEquals(Boolean.FALSE, p.getPaused());
        assertNull(p.getBatchesDone());
        assertNull(p.getSegmentIndex());
    }

    @Test
    public void lookupFailure_projectionNull_listSurvives() {
        when(dmlBatchService.findTaskForTicketGroup(eq(TICKET_ID), eq(BATCH_GROUP_ID), isNull()))
            .thenThrow(new IllegalStateException("mapper down"));

        List<GovTicketV2GroupVO> list = service.groupList(PUID, UID, TICKET_ID);

        assertEquals(2, list.size());
        assertNull(list.get(0).getDmlBatchProgress());
        assertEquals("db-1", list.get(0).getDbName());
        assertEquals("EXECUTING", list.get(1).getExecStatus());
    }

    @Test
    public void engineUnwired_legacyHarnessShape_projectionAbsent_ghostProjectionIntact() {
        // the GhostGroupListTest harness shape: the dmlBatchService field predates B7 wiring in
        // the frozen suite — the null guard keeps that suite byte-identical (degradation pin)
        ReflectionTestUtils.setField(service, "dmlBatchService", null);
        when(dmlBatchService.findTaskForTicketGroup(eq(TICKET_ID), eq(BATCH_GROUP_ID), isNull()))
            .thenReturn(batchTask(AsyncTaskStatus.RUNNING, cfg(new DmlBatchCheckpoint())));

        List<GovTicketV2GroupVO> list = service.groupList(PUID, UID, TICKET_ID);

        assertEquals(2, list.size());
        assertNull(list.get(0).getDmlBatchProgress());
        assertEquals("db-1", list.get(0).getDbName());
    }
}
