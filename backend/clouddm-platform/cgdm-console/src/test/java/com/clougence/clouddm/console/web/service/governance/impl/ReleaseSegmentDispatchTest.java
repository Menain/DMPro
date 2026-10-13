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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.api.console.autoexec.ErrorStrategy;
import com.clougence.clouddm.console.web.component.analysis.QueryAnalysisService;
import com.clougence.clouddm.console.web.component.dsconfig.DmDsConfigService;
import com.clougence.clouddm.console.web.component.execute.AutoExecService;
import com.clougence.clouddm.console.web.component.execute.impl.AutoExecServiceImpl;
import com.clougence.clouddm.console.web.component.governance.ReleaseSegmentService;
import com.clougence.clouddm.console.web.model.vo.ghost.GhostExecuteVO;
import com.clougence.clouddm.console.web.model.vo.ghost.ReleaseGhostCapability;
import com.clougence.clouddm.console.web.service.ghost.GhostService;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.DataSourceDal;
import com.clougence.clouddm.platform.dal.access.DbChangeEventDal;
import com.clougence.clouddm.platform.dal.access.ExecutionDal;
import com.clougence.clouddm.platform.dal.access.ProdReleaseDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangeEventMapper;
import com.clougence.clouddm.platform.dal.mapper.datasource.DmDsMapper;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecAsyncTaskMapper;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoJobMapper;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoTaskMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseStmtMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseStmtSegmentMapper;
import com.clougence.clouddm.platform.dal.model.datasource.DmDsDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO;
import com.clougence.clouddm.platform.dal.model.execution.DmExecAutoTaskDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseStmtDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseStmtSegmentDO;
import com.clougence.clouddm.sdk.sql.parser.SplitQueryType;
import com.clougence.clouddm.sdk.sql.parser.SplitScript;

import java.util.EnumSet;

/**
 * ADR-013 M2 (release-chain segment-aware dispatch, design D3/D4/D5/D9): the release orchestrator
 * {@link ReleaseSegmentService} + the segment-aware {@code createReleaseStmtJob} / segment job
 * builder {@code createReleaseSegmentJob}.
 * <p>
 * Pins:
 * <ul>
 * <li><b>decision order + red line 1/3</b>: an EMPTY manifest keeps the legacy whole-text DIRECT job
 *     byte-identical (returns true, orchestrator never touched); a NON-EMPTY manifest delegates the
 *     WHOLE dispatch to the orchestrator (returns false — NO DIRECT fallback exists); an UNKNOWN
 *     segment type (corrupt manifest) FAILS CLOSED, never executes;</li>
 * <li><b>AUTOEXEC holes</b>: {@code createReleaseSegmentJob} emits task rows only within the segment's
 *     stmtRange with GLOBAL exec_order numbering WITH HOLES (per-segment re-densing forbidden);</li>
 * <li><b>DML_BATCH block (裁决 3)</b>: a DML_BATCH segment at its dispatch turn → stmt FAILED +
 *     RELEASE_ROUTE_UNSUPPORTED, NEVER a job / ghost / silent downgrade; eventData is the disclosure
 *     whitelist (NO SQL text);</li>
 * <li><b>GHOST capability re-check (裁决 2)</b>: capability failure → stmt FAILED +
 *     RELEASE_ROUTE_CAPABILITY, launch never attempted; capability pass → executeForRelease with the
 *     release binding + stmt EXECUTING;</li>
 * <li><b>segment advance + aggregation</b>: AUTOEXEC/GHOST completion advances to the next segment or
 *     writes the stmt terminal (SUCCESS/FAILED) and (for the ghost hook) chain-advances;</li>
 * <li><b>retry (design D9)</b>: prepareRetry resets non-SUCCESS segments to PENDING, SUCCESS segments
 *     NEVER re-play.</li>
 * </ul>
 */
public class ReleaseSegmentDispatchTest {

    private ReleaseSegmentService        segmentService;
    private ProdReleaseDal               prodReleaseDal;
    private DmProdReleaseMapper          releaseMapper;
    private DmProdReleaseStmtMapper      stmtMapper;
    private DmProdReleaseStmtSegmentMapper segmentMapper;
    private DbChangeEventDal             dbChangeEventDal;
    private DmDbChangeEventMapper        eventMapper;
    private ExecutionDal                 execDal;
    private DmExecAsyncTaskMapper        asyncTaskMapper;
    private ApprovalDal                  approvalDal;
    private AutoExecService              autoExecService;
    private GhostService                 ghostService;
    private com.clougence.rdp.service.RdpOpAuditService rdpOpAuditService;
    private com.clougence.clouddm.console.web.component.governance.GovRouteOverrideService govRouteOverrideService;

    private static final long   RELEASE_ID  = 500L;
    private static final long   STMT_ID     = 800L;
    private static final long   PROD_DS_ID  = 11L;
    private static final String PROD_DB     = "prod_db_a";
    private static final String UID         = "uid-op";
    private static final String CREATOR     = "creator-001";
    private static final String PUID        = "puid-001";

    @Before
    public void setUp() {
        segmentService = new ReleaseSegmentService();

        prodReleaseDal = mock(ProdReleaseDal.class);
        releaseMapper = mock(DmProdReleaseMapper.class);
        stmtMapper = mock(DmProdReleaseStmtMapper.class);
        segmentMapper = mock(DmProdReleaseStmtSegmentMapper.class);
        when(prodReleaseDal.releaseMapper()).thenReturn(releaseMapper);
        when(prodReleaseDal.stmtMapper()).thenReturn(stmtMapper);
        when(prodReleaseDal.segmentMapper()).thenReturn(segmentMapper);

        dbChangeEventDal = mock(DbChangeEventDal.class);
        eventMapper = mock(DmDbChangeEventMapper.class);
        when(dbChangeEventDal.eventMapper()).thenReturn(eventMapper);
        when(eventMapper.insert(any(DmDbChangeEventDO.class))).thenReturn(1);

        execDal = mock(ExecutionDal.class);
        asyncTaskMapper = mock(DmExecAsyncTaskMapper.class);
        when(execDal.asyncTaskMapper()).thenReturn(asyncTaskMapper);
        when(execDal.autoJobMapper()).thenReturn(mock(DmExecAutoJobMapper.class));
        when(execDal.autoTaskMapper()).thenReturn(mock(DmExecAutoTaskMapper.class));

        approvalDal = mock(ApprovalDal.class);
        when(approvalDal.approvalMapper()).thenReturn(mock(DmApprovalMapper.class));

        autoExecService = mock(AutoExecService.class);
        ghostService = mock(GhostService.class);
        rdpOpAuditService = mock(com.clougence.rdp.service.RdpOpAuditService.class);
        // ADR-013 M3: the two block points offer a route override consumption FIRST — default:
        // nothing consumable → every M2 block pin stands byte-identical
        govRouteOverrideService = mock(
            com.clougence.clouddm.console.web.component.governance.GovRouteOverrideService.class);
        when(govRouteOverrideService.findAndConsume(any(), any(), any(), anyString(), any()))
            .thenReturn(null);

        ReflectionTestUtils.setField(segmentService, "prodReleaseDal", prodReleaseDal);
        ReflectionTestUtils.setField(segmentService, "execDal", execDal);
        ReflectionTestUtils.setField(segmentService, "dbChangeEventDal", dbChangeEventDal);
        ReflectionTestUtils.setField(segmentService, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(segmentService, "autoExecService", autoExecService);
        ReflectionTestUtils.setField(segmentService, "ghostService", ghostService);
        ReflectionTestUtils.setField(segmentService, "rdpOpAuditService", rdpOpAuditService);
        ReflectionTestUtils.setField(segmentService, "govRouteOverrideService", govRouteOverrideService);

        // default: release row lock returns the release; stmt is PENDING (dispatchable)
        when(releaseMapper.selectByIdForUpdate(RELEASE_ID)).thenReturn(release());
    }

    // ==================== decision order: DML_BATCH block / GHOST capability / AUTOEXEC ====================

    @Test
    public void dispatchFirst_dmlBatchFirst_blocksWithUnsupportedEvent_neverDowngrades() {
        DmProdReleaseStmtSegmentDO batch = seg(1, "DML_BATCH", null, null, 1, "t_order", 5000000L,
            "DELETE FROM t_order WHERE status = 0", "id", "INT", Boolean.TRUE, "PENDING");
        when(stmtMapper.queryById(STMT_ID)).thenReturn(stmt("PENDING"));
        when(segmentMapper.queryByReleaseStmtId(STMT_ID)).thenReturn(new ArrayList<>(List.of(batch)));

        segmentService.dispatchFirstReleaseSegment(stmt("PENDING"), UID);

        // stmt FAILED (never a job / ghost / silent direct replay — red line 1)
        verify(stmtMapper).updateExecStatus(eq(STMT_ID), eq("FAILED"), anyString());
        verify(autoExecService, never()).createReleaseSegmentJob(any(), any(), anyString(), anyBoolean(), any(), any(), any());
        verify(autoExecService, never()).startJob(anyString(), anyString());
        verifyNoInteractions(ghostService);
        // M3: the route override was offered FIRST — nothing consumable → the block stands
        verify(govRouteOverrideService).findAndConsume(any(), any(), eq(batch),
            eq(com.clougence.clouddm.console.web.component.governance.GovRouteOverrideService.BLOCK_ROUTE_UNSUPPORTED),
            anyString());
        // RELEASE_ROUTE_UNSUPPORTED event with the disclosure whitelist (segIdx/table/rows/batchKey/sourceTicketId)
        DmDbChangeEventDO event = captureEvent("RELEASE_ROUTE_UNSUPPORTED");
        assertNotNull(event.getEventData());
        assertTrue(event.getEventData().contains("\"segIdx\":1"));
        assertTrue(event.getEventData().contains("t_order"));
        // NO SQL text in the eventData (C2 discipline)
        assertFalse(event.getEventData().contains("DELETE FROM"));
    }

    @Test
    public void dispatchFirst_unknownSegmentType_failsClosed_neverExecutes() {
        // a corrupt/tampered manifest row (the M1 writer only emits the three plan types) must
        // FAIL CLOSED — red line 1: no fall-through into a whole-text AUTOEXEC replay
        DmProdReleaseStmtSegmentDO corrupt = seg(1, "MYSTERY", null, null, 1, "t_x", null,
            "UPDATE t_x SET a=1", null, null, null, "PENDING");
        when(stmtMapper.queryById(STMT_ID)).thenReturn(stmt("PENDING"));
        when(segmentMapper.queryByReleaseStmtId(STMT_ID)).thenReturn(new ArrayList<>(List.of(corrupt)));

        segmentService.dispatchFirstReleaseSegment(stmt("PENDING"), UID);

        verify(stmtMapper).updateExecStatus(eq(STMT_ID), eq("FAILED"), contains("MYSTERY"));
        verify(autoExecService, never()).createReleaseSegmentJob(any(), any(), anyString(), anyBoolean(), any(), any(), any());
        verify(autoExecService, never()).startJob(anyString(), anyString());
        verifyNoInteractions(ghostService);
    }

    @Test
    public void dispatchFirst_ghostFirst_capabilityFail_blocksWithCapabilityEvent_noLaunch() {
        DmProdReleaseStmtSegmentDO ghost = seg(1, "GHOST", null, null, 1, "t_big", 8000000L,
            "ALTER TABLE t_big ADD c INT", null, null, null, "PENDING");
        when(stmtMapper.queryById(STMT_ID)).thenReturn(stmt("PENDING"));
        when(segmentMapper.queryByReleaseStmtId(STMT_ID)).thenReturn(new ArrayList<>(List.of(ghost)));
        when(ghostService.probeReleaseCapability(anyString(), eq(PROD_DS_ID), eq(PROD_DB), eq("t_big")))
            .thenReturn(ReleaseGhostCapability.fail("NO_UNIQUE_KEY", "no PK/UK on prod"));

        segmentService.dispatchFirstReleaseSegment(stmt("PENDING"), UID);

        verify(stmtMapper).updateExecStatus(eq(STMT_ID), eq("FAILED"), contains("NO_UNIQUE_KEY"));
        verify(ghostService, never()).executeForRelease(anyString(), anyString(), any(), anyLong(), anyLong(), any());
        DmDbChangeEventDO event = captureEvent("RELEASE_ROUTE_CAPABILITY");
        assertTrue(event.getEventData().contains("NO_UNIQUE_KEY"));
    }

    @Test
    public void dispatchFirst_ghostFirst_capable_launchesWithReleaseBinding_stmtExecuting() {
        DmProdReleaseStmtSegmentDO ghost = seg(1, "GHOST", null, null, 1, "t_big", 8000000L,
            "ALTER TABLE t_big ADD c INT", null, null, null, "PENDING");
        when(stmtMapper.queryById(STMT_ID)).thenReturn(stmt("PENDING"));
        when(segmentMapper.queryByReleaseStmtId(STMT_ID)).thenReturn(new ArrayList<>(List.of(ghost)));
        when(ghostService.probeReleaseCapability(anyString(), eq(PROD_DS_ID), eq(PROD_DB), eq("t_big")))
            .thenReturn(ReleaseGhostCapability.capable());
        GhostExecuteVO vo = new GhostExecuteVO();
        vo.setSessionId("sess-1");
        vo.setTaskId(77L);
        vo.setAudit(new com.clougence.clouddm.console.web.model.vo.ghost.GhostOpAuditObj());
        when(ghostService.executeForRelease(eq(PUID), eq(CREATOR), any(), eq(STMT_ID), eq(RELEASE_ID), eq(1)))
            .thenReturn(vo);

        segmentService.dispatchFirstReleaseSegment(stmt("PENDING"), UID);

        // launched with the release binding (releaseStmtId/releaseId/segmentIndex) — mutually exclusive
        // with the ticket binding; stmt EXECUTING; the launch bindings persisted onto the segment row
        verify(ghostService).executeForRelease(eq(PUID), eq(CREATOR), any(), eq(STMT_ID), eq(RELEASE_ID), eq(1));
        verify(stmtMapper).updateExecStatus(STMT_ID, "EXECUTING", null);
        ArgumentCaptor<DmProdReleaseStmtSegmentDO> captor = ArgumentCaptor.forClass(DmProdReleaseStmtSegmentDO.class);
        verify(segmentMapper, atLeastOnce()).updateRuntime(captor.capture());
        DmProdReleaseStmtSegmentDO persisted = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertEquals("sess-1", persisted.getSessionId());
        assertEquals(Long.valueOf(77L), persisted.getTaskId());
        verify(eventMapper, never()).insert(any(DmDbChangeEventDO.class));
        // ticket-R10 audit twin: GHOST_EXECUTE HIGH in the creator's name, DATASOURCE resource (resId =
        // the prod dsId = the ResourceType's own PK), obj annotated with the release binding
        ArgumentCaptor<com.clougence.clouddm.console.web.model.vo.ghost.GhostOpAuditObj> auditCaptor
            = ArgumentCaptor.forClass(com.clougence.clouddm.console.web.model.vo.ghost.GhostOpAuditObj.class);
        verify(rdpOpAuditService).logAndAddOperationAudit(eq(PUID), eq(CREATOR), anyString(), isNull(),
            eq(PROD_DS_ID), auditCaptor.capture(),
            eq(com.clougence.clouddm.platform.dal.model.monitor.SecurityLevel.HIGH),
            eq(com.clougence.clouddm.platform.dal.model.monitor.AuditType.GHOST_EXECUTE),
            eq(com.clougence.clouddm.platform.dal.model.ResourceType.DATASOURCE));
        assertEquals(Long.valueOf(STMT_ID), auditCaptor.getValue().getReleaseStmtId());
        assertEquals(Long.valueOf(RELEASE_ID), auditCaptor.getValue().getReleaseId());
        assertEquals("RELEASE_SEGMENT_CHAIN", auditCaptor.getValue().getVia());
    }

    @Test
    public void dispatchFirst_autoexecFirst_createsSegmentJobAndStarts() {
        DmProdReleaseStmtSegmentDO auto1 = seg(1, "AUTOEXEC", 1, 2, null, null, null, null, null, null, null, "PENDING");
        DmProdReleaseStmtSegmentDO ghost = seg(2, "GHOST", null, null, 3, "t_big", 8000000L,
            "ALTER TABLE t_big ADD c INT", null, null, null, "PENDING");
        when(stmtMapper.queryById(STMT_ID)).thenReturn(stmt("PENDING"));
        when(segmentMapper.queryByReleaseStmtId(STMT_ID)).thenReturn(new ArrayList<>(List.of(auto1, ghost)));

        segmentService.dispatchFirstReleaseSegment(stmt("PENDING"), UID);

        // firstNonTerminal = AUTOEXEC(1) → segment job built + started (the orchestrator owns start)
        verify(autoExecService).createReleaseSegmentJob(any(), eq(auto1), anyString(), anyBoolean(),
            eq(ErrorStrategy.NONE), any(), eq(UID));
        verify(autoExecService).startJob(anyString(), eq(UID));
        verifyNoInteractions(ghostService); // the GHOST segment is not reached yet (serial)
    }

    // ==================== segment advance + aggregation ====================

    @Test
    public void completeAutoexecSegment_advancesToNextGhostSegment_returnsFalse() {
        DmProdReleaseStmtSegmentDO auto1 = seg(1, "AUTOEXEC", 1, 1, null, null, null, null, null, null, null, "EXECUTING");
        auto1.setJobBizId("job-1");
        DmProdReleaseStmtSegmentDO ghost = seg(2, "GHOST", null, null, 2, "t_big", 8000000L,
            "ALTER TABLE t_big ADD c INT", null, null, null, "PENDING");
        when(segmentMapper.queryByReleaseStmtId(STMT_ID)).thenReturn(new ArrayList<>(List.of(auto1, ghost)));
        when(ghostService.probeReleaseCapability(anyString(), anyLong(), anyString(), anyString()))
            .thenReturn(ReleaseGhostCapability.capable());
        when(ghostService.executeForRelease(anyString(), anyString(), any(), anyLong(), anyLong(), any()))
            .thenReturn(new GhostExecuteVO());

        boolean stmtTerminal = segmentService.completeReleaseAutoexecSegmentUnderLock(
            release(), stmt("EXECUTING"), "job-1", true, null, UID);

        assertFalse("next segment dispatched → stmt not terminal", stmtTerminal);
        // seg1 SUCCESS frozen, seg2 (GHOST) launched
        assertEquals("SUCCESS", auto1.getStatus());
        verify(ghostService).executeForRelease(anyString(), anyString(), any(), eq(STMT_ID), eq(RELEASE_ID), eq(2));
        // no stmt terminal write yet (a segment is still in flight)
        verify(stmtMapper, never()).updateExecStatus(eq(STMT_ID), eq("SUCCESS"), any());
    }

    @Test
    public void completeAutoexecSegment_lastSegment_writesStmtSuccess_returnsTrue() {
        DmProdReleaseStmtSegmentDO auto1 = seg(1, "AUTOEXEC", 1, 3, null, null, null, null, null, null, null, "EXECUTING");
        auto1.setJobBizId("job-9");
        when(segmentMapper.queryByReleaseStmtId(STMT_ID)).thenReturn(new ArrayList<>(List.of(auto1)));

        boolean stmtTerminal = segmentService.completeReleaseAutoexecSegmentUnderLock(
            release(), stmt("EXECUTING"), "job-9", true, null, UID);

        assertTrue(stmtTerminal);
        assertEquals("SUCCESS", auto1.getStatus());
        verify(stmtMapper).updateExecStatus(STMT_ID, "SUCCESS", null);
    }

    @Test
    public void completeAutoexecSegment_failure_writesStmtFailed_returnsTrue() {
        DmProdReleaseStmtSegmentDO auto1 = seg(1, "AUTOEXEC", 1, 1, null, null, null, null, null, null, null, "EXECUTING");
        auto1.setJobBizId("job-1");
        DmProdReleaseStmtSegmentDO auto2 = seg(2, "AUTOEXEC", 2, 2, null, null, null, null, null, null, null, "PENDING");
        when(segmentMapper.queryByReleaseStmtId(STMT_ID)).thenReturn(new ArrayList<>(List.of(auto1, auto2)));

        boolean stmtTerminal = segmentService.completeReleaseAutoexecSegmentUnderLock(
            release(), stmt("EXECUTING"), "job-1", false, "syntax error", UID);

        assertTrue(stmtTerminal);
        assertEquals("FAILED", auto1.getStatus());
        assertEquals("downstream segment stays PENDING", "PENDING", auto2.getStatus());
        verify(stmtMapper).updateExecStatus(STMT_ID, "FAILED", "syntax error");
    }

    @Test
    public void completeAutoexecSegment_staleJobBizId_casSkip() {
        DmProdReleaseStmtSegmentDO auto1 = seg(1, "AUTOEXEC", 1, 1, null, null, null, null, null, null, null, "EXECUTING");
        auto1.setJobBizId("job-CURRENT");
        when(segmentMapper.queryByReleaseStmtId(STMT_ID)).thenReturn(new ArrayList<>(List.of(auto1)));

        boolean stmtTerminal = segmentService.completeReleaseAutoexecSegmentUnderLock(
            release(), stmt("EXECUTING"), "job-STALE", true, null, UID);

        assertFalse(stmtTerminal);
        assertEquals("EXECUTING", auto1.getStatus()); // untouched — the stale callback never advances
        verify(stmtMapper, never()).updateExecStatus(anyLong(), anyString(), any());
    }

    @Test
    public void onReleaseGhostTerminal_lastSegment_writesStmtSuccessAndChainAdvances() {
        DmProdReleaseStmtSegmentDO ghost = seg(1, "GHOST", null, null, 1, "t_big", 8000000L,
            "ALTER TABLE t_big ADD c INT", null, null, null, "EXECUTING");
        when(stmtMapper.queryById(STMT_ID)).thenReturn(stmt("EXECUTING"), stmt("SUCCESS"));
        when(segmentMapper.queryByReleaseStmtId(STMT_ID)).thenReturn(new ArrayList<>(List.of(ghost)));

        segmentService.onReleaseGhostTerminal(STMT_ID, 1, true, null);

        assertEquals("SUCCESS", ghost.getStatus());
        verify(stmtMapper).updateExecStatus(STMT_ID, "SUCCESS", null);
        // stmt-terminal event parity with the AUTOEXEC callback path (release-detail timeline)
        DmDbChangeEventDO stmtEvent = captureEvent("RELEASE_STMT_SUCCESS");
        assertTrue(stmtEvent.getEventData().contains("\"stmtId\":" + STMT_ID));
        // stmt terminal → the shared chain-advance+aggregation tail runs (stmtSuccess=true)
        verify(autoExecService).advanceReleaseChainUnderLock(any(DmProdReleaseDO.class), any(DmProdReleaseStmtDO.class),
            eq(true), eq(CREATOR));
    }

    @Test
    public void onReleaseGhostTerminal_casSkip_replayedHookNoAdvance() {
        // the segment already moved on (SUCCESS) — a replayed hook must not re-advance
        DmProdReleaseStmtSegmentDO ghost = seg(1, "GHOST", null, null, 1, "t_big", 8000000L,
            "ALTER TABLE t_big ADD c INT", null, null, null, "SUCCESS");
        when(stmtMapper.queryById(STMT_ID)).thenReturn(stmt("SUCCESS"));
        when(segmentMapper.queryByReleaseStmtId(STMT_ID)).thenReturn(new ArrayList<>(List.of(ghost)));

        segmentService.onReleaseGhostTerminal(STMT_ID, 1, true, null);

        verify(stmtMapper, never()).updateExecStatus(anyLong(), anyString(), any());
        verify(autoExecService, never()).advanceReleaseChainUnderLock(any(), any(), anyBoolean(), any());
    }

    // ==================== retry resume (design D9 — SUCCESS never re-plays) ====================

    @Test
    public void prepareRetry_resetsNonSuccess_segmentsNeverReplaySuccess() {
        DmProdReleaseStmtSegmentDO auto1 = seg(1, "AUTOEXEC", 1, 1, null, null, null, null, null, null, null, "SUCCESS");
        auto1.setJobBizId("job-done");
        DmProdReleaseStmtSegmentDO ghost = seg(2, "GHOST", null, null, 2, "t_big", 8000000L,
            "ALTER TABLE t_big ADD c INT", null, null, null, "FAILED");
        ghost.setError("gh-ost FATAL");
        ghost.setSessionId("sess-old");
        ghost.setTaskId(55L);
        ghost.setFinishedAt(999L);
        DmProdReleaseStmtSegmentDO auto2 = seg(3, "AUTOEXEC", 3, 4, null, null, null, null, null, null, null, "PENDING");
        when(segmentMapper.queryByReleaseStmtId(STMT_ID)).thenReturn(new ArrayList<>(List.of(auto1, ghost, auto2)));

        segmentService.prepareRetry(stmt("FAILED"));

        // SUCCESS segment untouched (已成功段永不重放)
        assertEquals("SUCCESS", auto1.getStatus());
        assertEquals("job-done", auto1.getJobBizId());
        // FAILED target reset to PENDING, error/timestamps/bindings cleared, old session released
        assertEquals("PENDING", ghost.getStatus());
        assertNull(ghost.getError());
        assertNull(ghost.getFinishedAt());
        assertNull(ghost.getSessionId());
        assertNull(ghost.getTaskId());
        verify(ghostService).releaseSessionQuietly(any(), eq("sess-old"));
        // downstream PENDING stays PENDING
        assertEquals("PENDING", auto2.getStatus());
    }

    @Test
    public void prepareRetry_nullPlan_noOp() {
        when(segmentMapper.queryByReleaseStmtId(STMT_ID)).thenReturn(Collections.emptyList());

        segmentService.prepareRetry(stmt("FAILED"));

        verify(segmentMapper, never()).updateRuntime(any());
        verifyNoInteractions(ghostService);
    }

    // ==================== createReleaseStmtJob decision order (AutoExecServiceImpl) ====================

    @Test
    public void createReleaseStmtJob_emptyManifest_legacyWholeText_noOrchestrator_returnsTrue() {
        AutoExecServiceImpl svc = autoExecWithSplit(4);
        ReleaseSegmentService orch = mock(ReleaseSegmentService.class);
        DmProdReleaseStmtSegmentMapper segMapper = wireAutoExec(svc, orch, Collections.emptyList());

        boolean mustStart = svc.createReleaseStmtJob(stmt("PENDING"), "job-legacy", false,
            ErrorStrategy.NONE, "zh-CN", UID);

        assertTrue("legacy null-plan → the caller starts the whole-stmt job", mustStart);
        // whole text re-split into 4 tasks (byte-identical legacy shape), orchestrator never touched
        assertEquals(4, capturedAutoExecTasks(svc).size());
        verifyNoInteractions(orch);
        assertNotNull(segMapper);
    }

    @Test
    public void createReleaseStmtJob_nonEmptyManifest_delegatesToOrchestrator_returnsFalse_noDirectFallback() {
        AutoExecServiceImpl svc = autoExecWithSplit(4);
        ReleaseSegmentService orch = mock(ReleaseSegmentService.class);
        DmProdReleaseStmtSegmentDO ghost = seg(1, "GHOST", null, null, 1, "t_big", 8000000L,
            "ALTER TABLE t_big ADD c INT", null, null, null, "PENDING");
        wireAutoExec(svc, orch, List.of(ghost));

        boolean mustStart = svc.createReleaseStmtJob(stmt("PENDING"), "job-seg", false,
            ErrorStrategy.NONE, "zh-CN", UID);

        assertFalse("segment shape → the orchestrator owns the whole dispatch", mustStart);
        verify(orch).dispatchFirstReleaseSegment(any(DmProdReleaseStmtDO.class), eq(UID));
        // red line 1: NO whole-text DIRECT task rows were built for a manifest-carrying stmt
        ExecutionDal exec = (ExecutionDal) ReflectionTestUtils.getField(svc, "execDal");
        verify(exec.autoTaskMapper(), never()).batchInsert(anyList());
    }

    @Test
    public void createReleaseSegmentJob_holesNumbering_globalExecOrder() {
        // stmt text splits into 4 statements; the AUTOEXEC segment covers [3,4] (a GHOST hole at 1..2)
        AutoExecServiceImpl svc = autoExecWithSplit(4);
        wireAutoExec(svc, mock(ReleaseSegmentService.class), Collections.emptyList());
        DmProdReleaseStmtSegmentDO autoSeg = seg(2, "AUTOEXEC", 3, 4, null, null, null, null, null, null, null, "EXECUTING");

        svc.createReleaseSegmentJob(stmt("EXECUTING"), autoSeg, "job-seg", false,
            ErrorStrategy.NONE, "zh-CN", UID);

        List<DmExecAutoTaskDO> tasks = capturedAutoExecTasks(svc);
        assertEquals(2, tasks.size());
        // GLOBAL exec_order WITH HOLES (3,4) — NOT re-densed to (1,2); the stmt_index alignment contract
        assertEquals(Integer.valueOf(3), tasks.get(0).getExecOrder());
        assertEquals(Integer.valueOf(4), tasks.get(1).getExecOrder());
        // the segment job carries the additive segmentIdx config key + depend_on_release_stmt_id
        assertEquals("EXECUTING", stmtStatusAfterSegmentJob(svc));
    }

    // ==================== M3 route-override release (design D5/D8) ====================

    @Test
    public void dispatchFirst_dmlBatch_overrideConsumed_releasesAsDirectSegmentJob_neverFailsStmt() {
        DmProdReleaseStmtSegmentDO batch = seg(1, "DML_BATCH", null, null, 1, "t_order", 5000000L,
            "DELETE FROM t_order WHERE status = 0", "id", "INT", Boolean.TRUE, "PENDING");
        when(stmtMapper.queryById(STMT_ID)).thenReturn(stmt("PENDING"));
        when(segmentMapper.queryByReleaseStmtId(STMT_ID)).thenReturn(new ArrayList<>(List.of(batch)));
        com.clougence.clouddm.platform.dal.model.prodrelease.DmGovRouteOverrideDO override
            = new com.clougence.clouddm.platform.dal.model.prodrelease.DmGovRouteOverrideDO();
        override.setId(77L);
        when(govRouteOverrideService.findAndConsume(any(), any(), eq(batch),
            eq(com.clougence.clouddm.console.web.component.governance.GovRouteOverrideService.BLOCK_ROUTE_UNSUPPORTED),
            anyString())).thenReturn(override);

        segmentService.dispatchFirstReleaseSegment(stmt("PENDING"), UID);

        // the consumed override releases THIS segment as a DIRECT single-statement job (explicit
        // risk acceptance) — the block disposal never runs
        verify(autoExecService).createReleaseSegmentJob(any(), eq(batch), anyString(), anyBoolean(),
            eq(ErrorStrategy.NONE), any(), eq(UID));
        verify(autoExecService).startJob(anyString(), eq(UID));
        verify(stmtMapper, never()).updateExecStatus(eq(STMT_ID), eq("FAILED"), any());
        // NO RELEASE_ROUTE_UNSUPPORTED event (the block was released, not imposed)
        verify(eventMapper, never()).insert(argThat((DmDbChangeEventDO e) ->
            e != null && "RELEASE_ROUTE_UNSUPPORTED".equals(e.getEventType())));
        // the released segment carries the jobBizId binding (the completion callback locates it by it)
        assertNotNull(batch.getJobBizId());
    }

    @Test
    public void dispatchFirst_ghostCapabilityFail_overrideConsumed_releasesAsDirectJob_noLaunch() {
        DmProdReleaseStmtSegmentDO ghost = seg(1, "GHOST", null, null, 1, "t_big", 8000000L,
            "ALTER TABLE t_big ADD c INT", null, null, null, "PENDING");
        when(stmtMapper.queryById(STMT_ID)).thenReturn(stmt("PENDING"));
        when(segmentMapper.queryByReleaseStmtId(STMT_ID)).thenReturn(new ArrayList<>(List.of(ghost)));
        when(ghostService.probeReleaseCapability(anyString(), eq(PROD_DS_ID), eq(PROD_DB), eq("t_big")))
            .thenReturn(ReleaseGhostCapability.fail("NO_UNIQUE_KEY", "no PK/UK on prod"));
        com.clougence.clouddm.platform.dal.model.prodrelease.DmGovRouteOverrideDO override
            = new com.clougence.clouddm.platform.dal.model.prodrelease.DmGovRouteOverrideDO();
        override.setId(78L);
        when(govRouteOverrideService.findAndConsume(any(), any(), eq(ghost),
            eq(com.clougence.clouddm.console.web.component.governance.GovRouteOverrideService.BLOCK_ROUTE_CAPABILITY),
            anyString())).thenReturn(override);

        segmentService.dispatchFirstReleaseSegment(stmt("PENDING"), UID);

        // explicit lock-risk acceptance → DIRECT replay; the gh-ost launch is never attempted
        verify(autoExecService).createReleaseSegmentJob(any(), eq(ghost), anyString(), anyBoolean(),
            eq(ErrorStrategy.NONE), any(), eq(UID));
        verify(autoExecService).startJob(anyString(), eq(UID));
        verify(ghostService, never()).executeForRelease(anyString(), anyString(), any(), anyLong(), anyLong(), any());
        verify(stmtMapper, never()).updateExecStatus(eq(STMT_ID), eq("FAILED"), any());
        verify(eventMapper, never()).insert(argThat((DmDbChangeEventDO e) ->
            e != null && "RELEASE_ROUTE_CAPABILITY".equals(e.getEventType())));
    }

    @Test
    public void completeSegment_overrideReleasedGhostSegment_locatedByJobBizId() {
        // an override-released GHOST segment keeps its frozen plan type but is JOB-driven — the
        // completion callback locates it by the recorded jobBizId (the M2 AUTOEXEC-only locator
        // would strand it EXECUTING forever)
        DmProdReleaseStmtSegmentDO released = seg(1, "GHOST", null, null, 1, "t_big", 8000000L,
            "ALTER TABLE t_big ADD c INT", null, null, null, "EXECUTING");
        released.setJobBizId("job-direct");
        when(segmentMapper.queryByReleaseStmtId(STMT_ID)).thenReturn(new ArrayList<>(List.of(released)));

        boolean stmtTerminal = segmentService.completeReleaseAutoexecSegmentUnderLock(
            release(), stmt("EXECUTING"), "job-direct", true, null, UID);

        assertTrue(stmtTerminal);
        assertEquals("SUCCESS", released.getStatus());
        verify(stmtMapper).updateExecStatus(STMT_ID, "SUCCESS", null);
    }

    @Test
    public void probeSegment_overrideReleasedGhost_jobDriven_skipsGhostProbe() {
        // sweep guard: an EXECUTING GHOST segment carrying a jobBizId is override-released
        // (job-driven — no ghost task exists); probing it as a ghost session would falsely
        // FAIL it ("task record missing") — the AUTOEXEC callback + aggregation backstop it
        DmProdReleaseStmtSegmentDO released = seg(1, "GHOST", null, null, 1, "t_big", 8000000L,
            "ALTER TABLE t_big ADD c INT", null, null, null, "EXECUTING");
        released.setJobBizId("job-direct");
        when(stmtMapper.queryById(STMT_ID)).thenReturn(stmt("EXECUTING"));
        when(segmentMapper.queryByReleaseStmtId(STMT_ID)).thenReturn(new ArrayList<>(List.of(released)));

        segmentService.probeReleaseSegmentUnderLock(release(), stmt("EXECUTING"));

        verify(ghostService, never()).findTaskForReleaseStmt(anyLong(), anyInt());
        verify(stmtMapper, never()).updateExecStatus(anyLong(), anyString(), any());
        assertEquals("EXECUTING", released.getStatus());
    }

    @Test
    public void createReleaseSegmentJob_overrideReleasedSingleStatement_execOrderIsStmtIndex() {
        // D5 mechanics: an override-released GHOST/DML_BATCH segment (no stmtRange, single
        // stmtIndex) builds exactly ONE task row with exec_order = stmtIndex (global-with-holes
        // domain — never re-densed, never the whole text)
        AutoExecServiceImpl svc = autoExecWithSplit(4);
        wireAutoExec(svc, mock(ReleaseSegmentService.class), Collections.emptyList());
        DmProdReleaseStmtSegmentDO releasedGhost = seg(2, "GHOST", null, null, 3, "t_big", 8000000L,
            "ALTER TABLE t_big ADD c INT", null, null, null, "EXECUTING");

        svc.createReleaseSegmentJob(stmt("EXECUTING"), releasedGhost, "job-seg", false,
            ErrorStrategy.NONE, "zh-CN", UID);

        List<DmExecAutoTaskDO> tasks = capturedAutoExecTasks(svc);
        assertEquals(1, tasks.size());
        assertEquals(Integer.valueOf(3), tasks.get(0).getExecOrder()); // = stmtIndex, holes domain
        assertEquals("ALTER TABLE t_big ADD c INT", tasks.get(0).getExecSql());
    }

    // ==================== helpers ====================

    private DmProdReleaseDO release() {
        DmProdReleaseDO release = new DmProdReleaseDO();
        release.setId(RELEASE_ID);
        release.setReleaseNo("REL-500");
        release.setStatus("EXECUTING");
        release.setPrimaryUid(PUID);
        release.setCreatorUid(CREATOR);
        return release;
    }

    private DmProdReleaseStmtDO stmt(String execStatus) {
        DmProdReleaseStmtDO stmt = new DmProdReleaseStmtDO();
        stmt.setId(STMT_ID);
        stmt.setReleaseId(RELEASE_ID);
        stmt.setProdDsId(PROD_DS_ID);
        stmt.setProdDbName(PROD_DB);
        stmt.setSeq(1);
        stmt.setSqlContent("INSERT INTO a VALUES (1); UPDATE b SET x=1; ALTER TABLE t_big ADD c INT; DELETE FROM c WHERE 1=1;");
        stmt.setSourceTicketId(100L);
        stmt.setSourceStmtId(200L);
        stmt.setExecStatus(execStatus);
        return stmt;
    }

    private static DmProdReleaseStmtSegmentDO seg(int segIdx, String type, Integer from, Integer to,
                                                  Integer stmtIndex, String table, Long rows, String sql,
                                                  String batchKeyColumn, String batchKeyType, Boolean idempotent,
                                                  String status) {
        DmProdReleaseStmtSegmentDO row = new DmProdReleaseStmtSegmentDO();
        row.setId((long) segIdx);
        row.setReleaseId(RELEASE_ID);
        row.setReleaseStmtId(STMT_ID);
        row.setSegIdx(segIdx);
        row.setType(type);
        row.setStmtRangeFrom(from);
        row.setStmtRangeTo(to);
        row.setStmtIndex(stmtIndex);
        row.setTableName(table);
        row.setRowsEstimated(rows);
        row.setSqlText(sql);
        row.setBatchKeyColumn(batchKeyColumn);
        row.setBatchKeyType(batchKeyType);
        row.setIdempotent(idempotent);
        row.setStatus(status);
        return row;
    }

    private DmDbChangeEventDO captureEvent(String eventType) {
        ArgumentCaptor<DmDbChangeEventDO> captor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper, atLeastOnce()).insert(captor.capture());
        for (DmDbChangeEventDO e : captor.getAllValues()) {
            if (eventType.equals(e.getEventType())) {
                return e;
            }
        }
        fail("expected event " + eventType + " not written");
        return null;
    }

    // ---- AutoExecServiceImpl wiring (decision-order + holes tests) ----

    private List<SplitScript> splitScripts;

    private AutoExecServiceImpl autoExecWithSplit(int count) {
        AutoExecServiceImpl svc = new AutoExecServiceImpl();
        splitScripts = new ArrayList<>();
        String[] sqls = {"INSERT INTO a VALUES (1)", "UPDATE b SET x=1",
            "ALTER TABLE t_big ADD c INT", "DELETE FROM c WHERE 1=1"};
        for (int i = 0; i < count; i++) {
            splitScripts.add(script(i, sqls[i]));
        }
        return svc;
    }

    private DmProdReleaseStmtSegmentMapper wireAutoExec(AutoExecServiceImpl svc, ReleaseSegmentService orch,
                                                        List<DmProdReleaseStmtSegmentDO> manifest) {
        // DmTeamUtils static exec dal (biz-id generators)
        ExecutionDal staticExecDal = mock(ExecutionDal.class);
        DmExecAutoJobMapper staticJobMapper = mock(DmExecAutoJobMapper.class);
        DmExecAutoTaskMapper staticTaskMapper = mock(DmExecAutoTaskMapper.class);
        when(staticExecDal.autoJobMapper()).thenReturn(staticJobMapper);
        when(staticExecDal.autoTaskMapper()).thenReturn(staticTaskMapper);
        when(staticJobMapper.queryByBizId(anyString())).thenReturn(null);
        when(staticTaskMapper.queryByBizId(anyString())).thenReturn(null);
        ReflectionTestUtils.setField(com.clougence.clouddm.console.web.util.DmTeamUtils.class, "executionDal", staticExecDal);

        ExecutionDal exec = mock(ExecutionDal.class);
        DmExecAutoJobMapper jobMapper = mock(DmExecAutoJobMapper.class);
        DmExecAutoTaskMapper taskMapper = mock(DmExecAutoTaskMapper.class);
        when(exec.autoJobMapper()).thenReturn(jobMapper);
        when(exec.autoTaskMapper()).thenReturn(taskMapper);
        when(jobMapper.queryByDependOnReleaseStmtId(anyLong())).thenReturn(null);
        when(taskMapper.batchInsert(anyList())).thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());

        ProdReleaseDal prd = mock(ProdReleaseDal.class);
        DmProdReleaseStmtMapper stmtM = mock(DmProdReleaseStmtMapper.class);
        DmProdReleaseStmtSegmentMapper segM = mock(DmProdReleaseStmtSegmentMapper.class);
        when(prd.stmtMapper()).thenReturn(stmtM);
        when(prd.segmentMapper()).thenReturn(segM);
        when(segM.queryByReleaseStmtId(anyLong())).thenReturn(manifest);

        DataSourceDal dsDal = mock(DataSourceDal.class);
        DmDsMapper dsMapper = mock(DmDsMapper.class);
        when(dsDal.dsMapper()).thenReturn(dsMapper);
        DmDsDO dsDO = new DmDsDO();
        dsDO.setId(PROD_DS_ID);
        dsDO.setDsEnvId(1L);
        when(dsMapper.queryDsIdentityById(PROD_DS_ID)).thenReturn(dsDO);

        DmDsConfigService configService = mock(DmDsConfigService.class);
        when(configService.fetchDsConfigFromExists(PROD_DS_ID))
            .thenReturn(new com.clougence.clouddm.base.metadata.ds.DataSourceConfig());
        com.clougence.clouddm.console.web.component.dsconfig.mode.DsLevels levels
            = mock(com.clougence.clouddm.console.web.component.dsconfig.mode.DsLevels.class);
        when(levels.dbLevels()).thenReturn(List.of("1", String.valueOf(PROD_DS_ID), PROD_DB));
        when(configService.parseLevels(anyList())).thenReturn(levels);

        QueryAnalysisService analysisService = mock(QueryAnalysisService.class);
        when(analysisService.analysisSplitStream(any(), any(), any(), anyInt(), anyInt()))
            .thenAnswer(inv -> splitScripts.stream());

        ReflectionTestUtils.setField(svc, "execDal", exec);
        ReflectionTestUtils.setField(svc, "prodReleaseDal", prd);
        ReflectionTestUtils.setField(svc, "dsDal", dsDal);
        ReflectionTestUtils.setField(svc, "configService", configService);
        ReflectionTestUtils.setField(svc, "analysisService", analysisService);
        ReflectionTestUtils.setField(svc, "releaseSegmentService", orch);
        ReflectionTestUtils.setField(svc, "txManager",
            mock(org.springframework.transaction.PlatformTransactionManager.class));
        return segM;
    }

    @SuppressWarnings("unchecked")
    private List<DmExecAutoTaskDO> capturedAutoExecTasks(AutoExecServiceImpl svc) {
        ExecutionDal exec = (ExecutionDal) ReflectionTestUtils.getField(svc, "execDal");
        DmExecAutoTaskMapper taskMapper = (DmExecAutoTaskMapper) exec.autoTaskMapper();
        ArgumentCaptor<List<DmExecAutoTaskDO>> captor = ArgumentCaptor.forClass(List.class);
        verify(taskMapper, atLeastOnce()).batchInsert(captor.capture());
        List<DmExecAutoTaskDO> all = new ArrayList<>();
        for (List<DmExecAutoTaskDO> batch : captor.getAllValues()) {
            all.addAll(batch);
        }
        return all;
    }

    private String stmtStatusAfterSegmentJob(AutoExecServiceImpl svc) {
        ProdReleaseDal prd = (ProdReleaseDal) ReflectionTestUtils.getField(svc, "prodReleaseDal");
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(prd.stmtMapper()).updateExecStatus(eq(STMT_ID), captor.capture(), any());
        return captor.getValue();
    }

    private static SplitScript script(long index, String sql) {
        SplitScript script = new SplitScript();
        script.setIndex(index);
        script.setType(EnumSet.of(sql.trim().toUpperCase().startsWith("ALTER")
            ? SplitQueryType.ALTER_TABLE : SplitQueryType.INSERT));
        script.setScript(sql);
        return script;
    }
}
