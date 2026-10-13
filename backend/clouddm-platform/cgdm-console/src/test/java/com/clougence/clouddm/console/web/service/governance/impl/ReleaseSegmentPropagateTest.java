/*
 * Copyright 2026 杭州开云集致科技有限公司
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://apache.org/licenses/LICENSE-2.0
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
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.console.web.component.approval.ApprovalFlowService;
import com.clougence.clouddm.console.web.component.approval.ApprovalStateService;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.component.auth.DmAuthServiceForBiz;
import com.clougence.clouddm.console.web.component.execute.AutoExecService;
import com.clougence.clouddm.console.web.component.governance.GovReturnNotifyService;
import com.clougence.clouddm.console.web.component.governance.PrecheckConclusion;
import com.clougence.clouddm.console.web.component.governance.ProdReleaseStateMachine;
import com.clougence.clouddm.console.web.component.governance.ReleasePlanHashUtils;
import com.clougence.clouddm.console.web.component.governance.ReleaseSchemaDiffService;
import com.clougence.clouddm.console.web.component.governance.ReleaseSchemaPrecheckService;
import com.clougence.clouddm.console.web.component.governance.RiskAssessmentService;
import com.clougence.clouddm.console.web.model.fo.prodrelease.ProdReleaseCreateFO;
import com.clougence.clouddm.console.web.model.fo.prodrelease.ProdReleaseResubmitFO;
import com.clougence.clouddm.console.web.model.fo.prodrelease.ProdReleaseReturnStmtFO;
import com.clougence.clouddm.console.web.model.vo.ticket.DmTicketResultVO;
import com.clougence.clouddm.console.web.service.approval.ApprovalControlService;
import com.clougence.clouddm.console.web.util.DmTeamUtils;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.DbChangeEventDal;
import com.clougence.clouddm.platform.dal.access.DbPairDal;
import com.clougence.clouddm.platform.dal.access.DataSourceDal;
import com.clougence.clouddm.platform.dal.access.ExecutionDal;
import com.clougence.clouddm.platform.dal.access.ProdReleaseDal;
import com.clougence.clouddm.platform.dal.access.TicketDbStmtDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangeEventMapper;
import com.clougence.clouddm.platform.dal.mapper.dbpair.DmDbPairMapper;
import com.clougence.clouddm.platform.dal.mapper.datasource.DmDsMapper;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoJobMapper;
import com.clougence.clouddm.platform.dal.mapper.govticket.DmTicketDbStmtMapper;
import com.clougence.clouddm.platform.dal.mapper.govticket.DmTicketStmtSchemaMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseStmtMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseStmtSchemaMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseStmtSegmentMapper;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.datasource.DmDsDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO;
import com.clougence.clouddm.platform.dal.model.dbpair.DmDbPairDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseStmtDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseStmtSegmentDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.ProdReleaseStatus;
import com.clougence.utils.JsonUtils;

/**
 * ADR-013 M1 (release-chain frozen plan manifest — carrier + freeze propagation,
 * WRITE-ONLY stage): the merge transaction copies the source group's frozen
 * {@code precheck_result.segments} plan into {@code dm_prod_release_stmt_segment}
 * and pins its canonical hash on {@code dm_prod_release_stmt.plan_hash}.
 * <p>
 * Pins:
 * <ul>
 * <li>propagation: mixed AUTOEXEC+GHOST plan → faithful row copy (plan columns only,
 *     status PENDING, runtime columns null, releaseStmtId stamped post-insert);
 *     DML_BATCH plan → batchKey/estimatedRows/idempotent freeze;</li>
 * <li>write condition: NO routed (GHOST ∨ DML_BATCH) segment → zero manifest writes and
 *     plan_hash stays NULL (pure-normal / legacy null-plan shape byte-identical);</li>
 * <li>cascade deletes: returnStmt (by release stmt) + returnRelease / handleRejected /
 *     handleCancelled (by release) — lifecycle twin of the schema snapshot table;</li>
 * <li>resubmit re-propagation after the return-time cascade delete;</li>
 * <li>plan_hash caliber: deterministic, seg_idx-order-insensitive, runtime/identity
 *     columns NEVER participate, any plan-column change flips the hash, empty → null;</li>
 * <li>dispatch untouched: createReleaseStmtJob is never reached in this milestone's
 *     write paths (write-only stage — no reader exists).</li>
 * </ul>
 */
public class ReleaseSegmentPropagateTest {

    private ProdReleaseServiceImpl        service;
    private ProdReleaseDal                prodReleaseDal;
    private DmProdReleaseMapper           releaseMapper;
    private DmProdReleaseStmtMapper       stmtMapper;
    private DmProdReleaseStmtSchemaMapper schemaMapper;
    private DmProdReleaseStmtSegmentMapper segmentMapper;
    private TicketDbStmtDal               ticketDbStmtDal;
    private DmTicketDbStmtMapper          ticketStmtMapper;
    private ApprovalDal                   approvalDal;
    private DmApprovalMapper              approvalMapper;
    private DbPairDal                     dbPairDal;
    private DmDbPairMapper                pairMapper;
    private DataSourceDal                 dsDal;
    private DmDsMapper                    dsMapper;
    private ApprovalControlService        approvalControlService;
    private ProdReleaseStateMachine       releaseStateMachine;
    private DbChangeEventDal              dbChangeEventDal;
    private DmDbChangeEventMapper         eventMapper;
    private ApprovalStateService          approvalStateService;
    private ExecutionDal                  execDal;
    private DmExecAutoJobMapper           autoJobMapper;

    private static final String PUID = "puid-001";
    private static final String UID  = "uid-001";

    /** [plain, GHOST, plain] — the canonical mixed plan shape (buildSegmentPlan output). */
    private static final String MIXED_GHOST_PLAN =
        "{\"checkStatus\":\"PASS\",\"changeType\":\"DDL\",\"segments\":["
            + "{\"segIdx\":1,\"type\":\"AUTOEXEC\",\"stmtRange\":[1,1]},"
            + "{\"segIdx\":2,\"type\":\"GHOST\",\"stmtIndex\":2,\"table\":\"t_big\",\"rows\":8000000,"
            + "\"sql\":\"ALTER TABLE t_big ADD c INT\"},"
            + "{\"segIdx\":3,\"type\":\"AUTOEXEC\",\"stmtRange\":[3,4]}]}";

    private static final String DML_BATCH_PLAN =
        "{\"checkStatus\":\"PASS\",\"changeType\":\"DML\",\"segments\":["
            + "{\"segIdx\":1,\"type\":\"DML_BATCH\",\"stmtIndex\":1,\"table\":\"t_order\","
            + "\"estimatedRows\":5000000,\"idempotent\":true,"
            + "\"sql\":\"DELETE FROM t_order WHERE status = 0\","
            + "\"batchKey\":{\"column\":\"id\",\"keyType\":\"INT\"}}]}";

    /** A pure-AUTOEXEC plan carries NO routed segment — the manifest must NOT be written. */
    private static final String PURE_AUTOEXEC_PLAN =
        "{\"checkStatus\":\"PASS\",\"changeType\":\"DDL\",\"segments\":["
            + "{\"segIdx\":1,\"type\":\"AUTOEXEC\",\"stmtRange\":[1,3]}]}";

    private static final String PLAIN_PRECHECK =
        "{\"checkStatus\":\"PASS\",\"changeType\":\"DDL\"}";

    @Before
    public void setUp() {
        service = new ProdReleaseServiceImpl();

        // DmTeamUtils static executionDal (bizId generators)
        ExecutionDal staticExecDal = mock(ExecutionDal.class);
        com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoJobMapper staticJobMapper
            = mock(com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoJobMapper.class);
        com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoTaskMapper staticTaskMapper
            = mock(com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoTaskMapper.class);
        when(staticExecDal.autoJobMapper()).thenReturn(staticJobMapper);
        when(staticExecDal.autoTaskMapper()).thenReturn(staticTaskMapper);
        when(staticJobMapper.queryByBizId(anyString())).thenReturn(null);
        when(staticTaskMapper.queryByBizId(anyString())).thenReturn(null);
        ReflectionTestUtils.setField(DmTeamUtils.class, "executionDal", staticExecDal);

        prodReleaseDal = mock(ProdReleaseDal.class);
        releaseMapper = mock(DmProdReleaseMapper.class);
        stmtMapper = mock(DmProdReleaseStmtMapper.class);
        schemaMapper = mock(DmProdReleaseStmtSchemaMapper.class);
        segmentMapper = mock(DmProdReleaseStmtSegmentMapper.class);
        when(prodReleaseDal.releaseMapper()).thenReturn(releaseMapper);
        when(prodReleaseDal.stmtMapper()).thenReturn(stmtMapper);
        when(prodReleaseDal.schemaMapper()).thenReturn(schemaMapper);
        when(prodReleaseDal.segmentMapper()).thenReturn(segmentMapper);

        ticketDbStmtDal = mock(TicketDbStmtDal.class);
        ticketStmtMapper = mock(DmTicketDbStmtMapper.class);
        DmTicketStmtSchemaMapper ticketSchemaMapper = mock(DmTicketStmtSchemaMapper.class);
        when(ticketDbStmtDal.stmtMapper()).thenReturn(ticketStmtMapper);
        when(ticketDbStmtDal.schemaMapper()).thenReturn(ticketSchemaMapper);
        when(ticketSchemaMapper.queryByStmtId(anyLong())).thenReturn(Collections.emptyList());

        approvalDal = mock(ApprovalDal.class);
        approvalMapper = mock(DmApprovalMapper.class);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);

        dbPairDal = mock(DbPairDal.class);
        pairMapper = mock(DmDbPairMapper.class);
        when(dbPairDal.pairMapper()).thenReturn(pairMapper);

        dsDal = mock(DataSourceDal.class);
        dsMapper = mock(DmDsMapper.class);
        when(dsDal.dsMapper()).thenReturn(dsMapper);

        approvalControlService = mock(ApprovalControlService.class);
        releaseStateMachine = mock(ProdReleaseStateMachine.class);
        approvalStateService = mock(ApprovalStateService.class);

        dbChangeEventDal = mock(DbChangeEventDal.class);
        eventMapper = mock(DmDbChangeEventMapper.class);
        when(dbChangeEventDal.eventMapper()).thenReturn(eventMapper);
        when(eventMapper.insert(any(DmDbChangeEventDO.class))).thenReturn(1);

        execDal = mock(ExecutionDal.class);
        autoJobMapper = mock(DmExecAutoJobMapper.class);
        when(execDal.autoJobMapper()).thenReturn(autoJobMapper);

        ReleaseSchemaPrecheckService precheckService = mock(ReleaseSchemaPrecheckService.class);
        when(precheckService.precheck(any())).thenReturn(PrecheckConclusion.pass());
        when(precheckService.extractTables(anyString())).thenReturn(Collections.emptySet());
        ReleaseSchemaDiffService diffService = mock(ReleaseSchemaDiffService.class);
        when(diffService.diff(any())).thenReturn(PrecheckConclusion.pass());

        ReflectionTestUtils.setField(service, "prodReleaseDal", prodReleaseDal);
        ReflectionTestUtils.setField(service, "dbChangeEventDal", dbChangeEventDal);
        ReflectionTestUtils.setField(service, "ticketDbStmtDal", ticketDbStmtDal);
        ReflectionTestUtils.setField(service, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(service, "dsDal", dsDal);
        ReflectionTestUtils.setField(service, "dbPairDal", dbPairDal);
        ReflectionTestUtils.setField(service, "approvalControlService", approvalControlService);
        ReflectionTestUtils.setField(service, "releaseStateMachine", releaseStateMachine);
        ReflectionTestUtils.setField(service, "dmAuthServiceForBiz", mock(DmAuthServiceForBiz.class));
        ReflectionTestUtils.setField(service, "autoExecService", mock(AutoExecService.class));
        ReflectionTestUtils.setField(service, "approvalStateService", approvalStateService);
        ReflectionTestUtils.setField(service, "approvalFlowService", mock(ApprovalFlowService.class));
        ReflectionTestUtils.setField(service, "releaseSchemaPrecheckService", precheckService);
        ReflectionTestUtils.setField(service, "releaseSchemaDiffService", diffService);
        ReflectionTestUtils.setField(service, "govReturnNotifyService", mock(GovReturnNotifyService.class));
        ReflectionTestUtils.setField(service, "riskAssessmentService", new RiskAssessmentService());
        ReflectionTestUtils.setField(service, "execDal", execDal);
    }

    // ==================== merge propagation (createRelease) ====================

    @Test
    public void createRelease_mixedGhostPlan_propagatesFrozenManifestAndPlanHash() {
        mockMergeFixture(100L, 200L, MIXED_GHOST_PLAN);

        service.createRelease(PUID, UID, createFO(100L));

        List<DmProdReleaseStmtSegmentDO> rows = captureBatchInsert();
        assertEquals(3, rows.size());

        DmProdReleaseStmtSegmentDO auto1 = rows.get(0);
        assertEquals(600L, auto1.getReleaseId().longValue());
        assertEquals(800L, auto1.getReleaseStmtId().longValue()); // stamped post-insert
        assertEquals(1, auto1.getSegIdx());
        assertEquals("AUTOEXEC", auto1.getType());
        assertEquals(Integer.valueOf(1), auto1.getStmtRangeFrom());
        assertEquals(Integer.valueOf(1), auto1.getStmtRangeTo());
        assertNull(auto1.getStmtIndex());
        assertEquals("PENDING", auto1.getStatus());
        // runtime columns stay untouched at freeze time (M2 consumers only)
        assertNull(auto1.getTaskId());
        assertNull(auto1.getSessionId());
        assertNull(auto1.getJobBizId());
        assertNull(auto1.getSummary());
        assertNull(auto1.getCheckpoint());

        DmProdReleaseStmtSegmentDO ghost = rows.get(1);
        assertEquals(2, ghost.getSegIdx());
        assertEquals("GHOST", ghost.getType());
        assertEquals(Integer.valueOf(2), ghost.getStmtIndex());
        assertEquals("t_big", ghost.getTableName());
        assertEquals(Long.valueOf(8000000L), ghost.getRowsEstimated()); // GHOST freezes `rows`
        assertEquals("ALTER TABLE t_big ADD c INT", ghost.getSqlText());
        assertNull(ghost.getStmtRangeFrom());
        assertNull(ghost.getBatchKeyColumn());

        DmProdReleaseStmtSegmentDO auto2 = rows.get(2);
        assertEquals(3, auto2.getSegIdx());
        assertEquals(Integer.valueOf(3), auto2.getStmtRangeFrom());
        assertEquals(Integer.valueOf(4), auto2.getStmtRangeTo());

        // plan_hash pin: written on the stmt insert, canonical over the frozen rows
        DmProdReleaseStmtDO insertedStmt = captureStmtInsert();
        assertNotNull("routed plan must pin plan_hash", insertedStmt.getPlanHash());
        assertEquals(ReleasePlanHashUtils.hash(rows), insertedStmt.getPlanHash());
    }

    @Test
    public void createRelease_dmlBatchPlan_freezesBatchKeyEstimatedRowsIdempotent() {
        mockMergeFixture(101L, 201L, DML_BATCH_PLAN);

        service.createRelease(PUID, UID, createFO(101L));

        List<DmProdReleaseStmtSegmentDO> rows = captureBatchInsert();
        assertEquals(1, rows.size());
        DmProdReleaseStmtSegmentDO batch = rows.get(0);
        assertEquals("DML_BATCH", batch.getType());
        assertEquals(Integer.valueOf(1), batch.getStmtIndex());
        assertEquals("t_order", batch.getTableName());
        assertEquals(Long.valueOf(5000000L), batch.getRowsEstimated()); // DML_BATCH freezes `estimatedRows`
        assertEquals("DELETE FROM t_order WHERE status = 0", batch.getSqlText());
        assertEquals("id", batch.getBatchKeyColumn());
        assertEquals("INT", batch.getBatchKeyType());
        assertEquals(Boolean.TRUE, batch.getIdempotent());
        assertEquals("PENDING", batch.getStatus());

        DmProdReleaseStmtDO insertedStmt = captureStmtInsert();
        assertEquals(ReleasePlanHashUtils.hash(rows), insertedStmt.getPlanHash());
    }

    @Test
    public void createRelease_pureAutoexecPlan_noManifestWrite_nullPlanHash() {
        // write condition mirrors the ticket-side carrier: NO routed segment → null-plan legacy
        mockMergeFixture(102L, 202L, PURE_AUTOEXEC_PLAN);

        service.createRelease(PUID, UID, createFO(102L));

        verify(segmentMapper, never()).batchInsert(anyList());
        assertNull(captureStmtInsert().getPlanHash());
    }

    @Test
    public void createRelease_plainPrecheckResult_noManifestWrite_nullPlanHash() {
        mockMergeFixture(103L, 203L, PLAIN_PRECHECK);

        service.createRelease(PUID, UID, createFO(103L));

        verify(segmentMapper, never()).batchInsert(anyList());
        assertNull(captureStmtInsert().getPlanHash());
    }

    @Test
    public void createRelease_nullPrecheckResult_noManifestWrite_nullPlanHash() {
        mockMergeFixture(104L, 204L, null);

        service.createRelease(PUID, UID, createFO(104L));

        verify(segmentMapper, never()).batchInsert(anyList());
        assertNull(captureStmtInsert().getPlanHash());
    }

    // ==================== cascade deletes (four paths) ====================

    @Test
    public void returnStmt_cascadeDeletesManifestByReleaseStmtId() {
        long stmtId = 600L;
        long releaseId = 610L;
        DmProdReleaseStmtDO stmt = buildReleaseStmt(stmtId, releaseId, "FAILED");
        when(stmtMapper.queryById(stmtId)).thenReturn(stmt);
        DmProdReleaseDO release = buildRelease(releaseId, ProdReleaseStatus.EXECUTING);
        when(releaseMapper.selectByIdForUpdate(releaseId)).thenReturn(release);
        when(autoJobMapper.queryByDependOnReleaseStmtId(stmtId)).thenReturn(null);

        DmApprovalDO sourceTicket = new DmApprovalDO();
        sourceTicket.setId(100L);
        sourceTicket.setOwnerUid(UID); // actor == owner → IM notify skipped
        sourceTicket.setTicketStatus(ApprovalStatus.FINISHED);
        when(approvalMapper.queryById(100L)).thenReturn(sourceTicket);
        DmTicketDbStmtDO sourceStmt = new DmTicketDbStmtDO();
        sourceStmt.setId(200L);
        sourceStmt.setTicketId(100L);
        when(ticketStmtMapper.queryById(200L)).thenReturn(sourceStmt);
        when(stmtMapper.queryByReleaseId(releaseId)).thenReturn(Collections.emptyList());

        ProdReleaseReturnStmtFO fo = new ProdReleaseReturnStmtFO();
        fo.setStmtId(stmtId);
        fo.setReason("fix SQL text");
        service.returnStmt(PUID, UID, fo);

        // twin of the schema-table orphan cleanup (Step 3)
        verify(schemaMapper).deleteByReleaseStmtId(stmtId);
        verify(segmentMapper).deleteByReleaseStmtId(stmtId);
        verify(segmentMapper, never()).deleteByReleaseId(anyLong());
    }

    @Test
    public void returnRelease_cascadeDeletesManifestByReleaseId() {
        long releaseId = 500L;
        DmProdReleaseDO release = buildRelease(releaseId, ProdReleaseStatus.APPROVING);
        release.setApprovalId(600L);
        when(releaseMapper.selectByIdForUpdate(releaseId)).thenReturn(release);
        when(releaseStateMachine.transit(eq(releaseId), anySet(), eq(ProdReleaseStatus.RETURNED)))
            .thenReturn(true);
        DmApprovalDO approvalTicket = new DmApprovalDO();
        approvalTicket.setId(600L);
        approvalTicket.setTicketStatus(ApprovalStatus.WAIT_APPROVAL);
        when(approvalMapper.queryById(600L)).thenReturn(approvalTicket);

        service.returnRelease(PUID, UID, releaseId, "need revision");

        verify(stmtMapper).deleteByReleaseId(releaseId);
        verify(schemaMapper).deleteByReleaseId(releaseId);
        verify(segmentMapper).deleteByReleaseId(releaseId);
    }

    @Test
    public void handleRejected_cascadeDeletesManifestByReleaseId() {
        long releaseId = 700L;
        when(releaseMapper.queryById(releaseId))
            .thenReturn(buildRelease(releaseId, ProdReleaseStatus.APPROVING));
        when(releaseStateMachine.transit(eq(releaseId),
            eq(Set.of(ProdReleaseStatus.APPROVING, ProdReleaseStatus.APPROVED)),
            eq(ProdReleaseStatus.REJECTED))).thenReturn(true);

        service.handleRejected(releaseId);

        verify(stmtMapper).deleteByReleaseId(releaseId);
        verify(schemaMapper).deleteByReleaseId(releaseId);
        verify(segmentMapper).deleteByReleaseId(releaseId);
    }

    @Test
    public void handleCancelled_cascadeDeletesManifestByReleaseId() {
        long releaseId = 701L;
        when(releaseMapper.queryById(releaseId))
            .thenReturn(buildRelease(releaseId, ProdReleaseStatus.APPROVING));
        when(releaseStateMachine.transit(eq(releaseId),
            eq(Set.of(ProdReleaseStatus.APPROVING, ProdReleaseStatus.APPROVED)),
            eq(ProdReleaseStatus.CANCELLED))).thenReturn(true);

        service.handleCancelled(releaseId);

        verify(stmtMapper).deleteByReleaseId(releaseId);
        verify(schemaMapper).deleteByReleaseId(releaseId);
        verify(segmentMapper).deleteByReleaseId(releaseId);
    }

    // ==================== resubmit re-propagation ====================

    @Test
    public void resubmit_repropagatesFrozenManifestAndPlanHash() {
        long releaseId = 400L;
        DmProdReleaseDO release = buildReturnedRelease(releaseId);
        when(releaseMapper.selectByIdForUpdate(releaseId)).thenReturn(release);
        mockMergeFixture(100L, 200L, MIXED_GHOST_PLAN);
        when(releaseStateMachine.transit(eq(releaseId),
            eq(Set.of(ProdReleaseStatus.RETURNED)), eq(ProdReleaseStatus.APPROVING)))
            .thenReturn(true);

        ProdReleaseResubmitFO fo = new ProdReleaseResubmitFO();
        fo.setReleaseId(releaseId);
        fo.setTicketIds(List.of(100L));
        service.resubmitRelease(PUID, UID, true, fo);

        // the return path cascade-deleted nothing here (fixture starts fresh) — the resubmit
        // re-insert loop must re-freeze the manifest exactly like the first merge
        List<DmProdReleaseStmtSegmentDO> rows = captureBatchInsert();
        assertEquals(3, rows.size());
        assertEquals("GHOST", rows.get(1).getType());
        assertEquals(400L, rows.get(0).getReleaseId().longValue());
        assertEquals(800L, rows.get(0).getReleaseStmtId().longValue());
        DmProdReleaseStmtDO insertedStmt = captureStmtInsert();
        assertEquals(ReleasePlanHashUtils.hash(rows), insertedStmt.getPlanHash());
    }

    // ==================== plan_hash caliber pins (ReleasePlanHashUtils) ====================

    @Test
    public void planHash_deterministicAndSegIdxOrderInsensitive() {
        List<DmProdReleaseStmtSegmentDO> rows = List.of(ghostRow(2), autoexecRow(1));
        String h1 = ReleasePlanHashUtils.hash(rows);
        String h2 = ReleasePlanHashUtils.hash(rows);
        assertEquals("same rows → same hash", h1, h2);
        assertEquals(64, h1.length());
        assertTrue(h1.matches("[0-9a-f]{64}"));
        // input order irrelevant — canonical form sorts by seg_idx; input list not mutated
        List<DmProdReleaseStmtSegmentDO> shuffled = new ArrayList<>(List.of(autoexecRow(1), ghostRow(2)));
        assertEquals(h1, ReleasePlanHashUtils.hash(shuffled));
        assertEquals(1, shuffled.get(0).getSegIdx());
    }

    @Test
    public void planHash_runtimeAndIdentityColumnsNeverParticipate() {
        DmProdReleaseStmtSegmentDO ghost = ghostRow(2);
        DmProdReleaseStmtSegmentDO auto = autoexecRow(1);
        String frozen = ReleasePlanHashUtils.hash(List.of(auto, ghost));

        // M2 runtime mutations + identity backfills must NOT invalidate the plan pin
        ghost.setStatus("SUCCESS");
        ghost.setTaskId(999L);
        ghost.setSessionId("sess-1");
        ghost.setJobBizId("job-1");
        ghost.setSummary("{\"status\":\"SUCCESS\"}");
        ghost.setError("none");
        ghost.setCheckpoint("{\"cursor\":1}");
        ghost.setStartedAt(1L);
        ghost.setFinishedAt(2L);
        ghost.setId(12345L);
        ghost.setReleaseId(777L);
        ghost.setReleaseStmtId(888L);
        assertEquals(frozen, ReleasePlanHashUtils.hash(List.of(auto, ghost)));
    }

    @Test
    public void planHash_anyPlanColumnChangeFlipsHash() {
        String base = ReleasePlanHashUtils.hash(List.of(autoexecRow(1), ghostRow(2)));

        DmProdReleaseStmtSegmentDO sqlChanged = ghostRow(2);
        sqlChanged.setSqlText("ALTER TABLE t_big ADD c BIGINT");
        assertNotEquals(base, ReleasePlanHashUtils.hash(List.of(autoexecRow(1), sqlChanged)));

        DmProdReleaseStmtSegmentDO typeChanged = ghostRow(2);
        typeChanged.setType("AUTOEXEC");
        assertNotEquals(base, ReleasePlanHashUtils.hash(List.of(autoexecRow(1), typeChanged)));

        DmProdReleaseStmtSegmentDO rowsChanged = ghostRow(2);
        rowsChanged.setRowsEstimated(1L);
        assertNotEquals(base, ReleasePlanHashUtils.hash(List.of(autoexecRow(1), rowsChanged)));

        DmProdReleaseStmtSegmentDO tableChanged = ghostRow(2);
        tableChanged.setTableName("t_other");
        assertNotEquals(base, ReleasePlanHashUtils.hash(List.of(autoexecRow(1), tableChanged)));

        DmProdReleaseStmtSegmentDO rangeChanged = autoexecRow(1);
        rangeChanged.setStmtRangeTo(9);
        assertNotEquals(base, ReleasePlanHashUtils.hash(List.of(rangeChanged, ghostRow(2))));

        DmProdReleaseStmtSegmentDO idemChanged = ghostRow(2);
        idemChanged.setIdempotent(Boolean.TRUE); // null → true is a plan-column change
        assertNotEquals(base, ReleasePlanHashUtils.hash(List.of(autoexecRow(1), idemChanged)));

        // segment ADDED (plan shape change)
        assertNotEquals(base, ReleasePlanHashUtils.hash(
            List.of(autoexecRow(1), ghostRow(2), autoexecRow(3))));
    }

    @Test
    public void planHash_nullOrEmptyRows_returnsNull() {
        assertNull(ReleasePlanHashUtils.hash(null));
        assertNull(ReleasePlanHashUtils.hash(Collections.emptyList()));
    }

    // ==================== helpers ====================

    private ProdReleaseCreateFO createFO(long ticketId) {
        ProdReleaseCreateFO fo = new ProdReleaseCreateFO();
        fo.setTicketIds(List.of(ticketId));
        return fo;
    }

    /**
     * Full valid merge fixture: FINISHED PRE_DDL ticket + SUCCESS stmt carrying the given
     * precheck_result + enabled pair + free prod DB + id assignment on inserts + the
     * approval-ticket mocks (mirrors ProdReleaseServiceImplTest's ghost-hint fixture).
     */
    private void mockMergeFixture(long ticketId, long stmtId, String precheckResult) {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(ticketId);
        ticket.setBizId("biz-" + ticketId);
        ticket.setTicketStatus(ApprovalStatus.FINISHED);
        ticket.setPrimaryUid(PUID);
        ticket.setOwnerUid(UID);
        ticket.setGmtCreate(new Date());
        ApprovalMO mo = new ApprovalMO();
        mo.setTicketType("PRE_DDL");
        ticket.setTicketInfo(JsonUtils.toJson(mo));
        when(approvalMapper.queryById(ticketId)).thenReturn(ticket);

        DmTicketDbStmtDO stmt = new DmTicketDbStmtDO();
        stmt.setId(stmtId);
        stmt.setTicketId(ticketId);
        stmt.setPairId(300L);
        stmt.setDsId(20L);
        stmt.setDbName("pre_db");
        stmt.setSqlContent("ALTER TABLE t_big ADD c INT;");
        stmt.setExecStatus("SUCCESS");
        stmt.setPrecheckResult(precheckResult);
        when(ticketStmtMapper.queryByTicketId(ticketId)).thenReturn(List.of(stmt));
        when(stmtMapper.queryBySourceStmtId(stmtId)).thenReturn(null);

        DmDbPairDO pair = new DmDbPairDO();
        pair.setId(300L);
        pair.setProdDsId(11L);
        pair.setProdDbName("prod_db_a");
        pair.setStatus("ENABLED");
        when(pairMapper.selectById(300L)).thenReturn(pair);
        when(releaseMapper.countActiveByProdDb(11L, "prod_db_a")).thenReturn(0);

        when(releaseMapper.insert(any(DmProdReleaseDO.class))).thenAnswer(inv -> {
            inv.getArgument(0, DmProdReleaseDO.class).setId(600L);
            return 1;
        });
        when(stmtMapper.insert(any(DmProdReleaseStmtDO.class))).thenAnswer(inv -> {
            inv.getArgument(0, DmProdReleaseStmtDO.class).setId(800L);
            return 1;
        });

        DmDsDO prodDs = new DmDsDO();
        prodDs.setDsEnvId(1L);
        when(dsMapper.queryDsIdentityById(11L)).thenReturn(prodDs);

        DmTicketResultVO ticketResult = new DmTicketResultVO();
        ticketResult.setTicketId(700L);
        when(approvalControlService.createSqlTicket(any(), any(), any(),
            eq(ApprovalBiz.DM_PROD_RELEASE))).thenReturn(ticketResult);
        DmApprovalDO newApproval = new DmApprovalDO();
        newApproval.setId(700L);
        newApproval.setTicketInfo(JsonUtils.toJson(new ApprovalMO()));
        when(approvalMapper.queryById(700L)).thenReturn(newApproval);
    }

    private DmProdReleaseDO buildRelease(long id, ProdReleaseStatus status) {
        DmProdReleaseDO release = new DmProdReleaseDO();
        release.setId(id);
        release.setReleaseNo("REL-" + id);
        release.setStatus(status.name());
        release.setPrimaryUid(PUID);
        release.setCreatorUid(UID);
        return release;
    }

    private DmProdReleaseDO buildReturnedRelease(long id) {
        DmProdReleaseDO release = buildRelease(id, ProdReleaseStatus.RETURNED);
        Map<String, Object> gateResult = new LinkedHashMap<>();
        gateResult.put("sourceTicketCount", 1);
        List<Map<String, Object>> stmts = new ArrayList<>();
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("sourceTicketId", 100L);
        s.put("sourceStmtId", 200L);
        s.put("prodDbName", "prod_db_a");
        s.put("seq", 1);
        s.put("hash", "somehash");
        stmts.add(s);
        gateResult.put("stmts", stmts);
        release.setGateResult(JsonUtils.toJson(gateResult));
        return release;
    }

    private DmProdReleaseStmtDO buildReleaseStmt(long id, long releaseId, String execStatus) {
        DmProdReleaseStmtDO stmt = new DmProdReleaseStmtDO();
        stmt.setId(id);
        stmt.setReleaseId(releaseId);
        stmt.setProdDsId(11L);
        stmt.setProdDbName("prod_db_a");
        stmt.setSeq(1);
        stmt.setSqlContent("ALTER TABLE t_big ADD c INT;");
        stmt.setHash(com.clougence.clouddm.console.web.component.governance.GovSqlHashUtils
            .hash("ALTER TABLE t_big ADD c INT;"));
        stmt.setSourceTicketId(100L);
        stmt.setSourceStmtId(200L);
        stmt.setExecutionKey("exec-key-1");
        stmt.setExecStatus(execStatus);
        return stmt;
    }

    @SuppressWarnings("unchecked")
    private List<DmProdReleaseStmtSegmentDO> captureBatchInsert() {
        ArgumentCaptor<List<DmProdReleaseStmtSegmentDO>> captor =
            ArgumentCaptor.forClass((Class) List.class);
        verify(segmentMapper).batchInsert(captor.capture());
        return captor.getValue();
    }

    private DmProdReleaseStmtDO captureStmtInsert() {
        ArgumentCaptor<DmProdReleaseStmtDO> captor = ArgumentCaptor.forClass(DmProdReleaseStmtDO.class);
        verify(stmtMapper).insert(captor.capture());
        return captor.getValue();
    }

    private static DmProdReleaseStmtSegmentDO autoexecRow(int segIdx) {
        DmProdReleaseStmtSegmentDO row = new DmProdReleaseStmtSegmentDO();
        row.setSegIdx(segIdx);
        row.setType("AUTOEXEC");
        row.setStmtRangeFrom(segIdx);
        row.setStmtRangeTo(segIdx);
        row.setStatus("PENDING");
        return row;
    }

    private static DmProdReleaseStmtSegmentDO ghostRow(int segIdx) {
        DmProdReleaseStmtSegmentDO row = new DmProdReleaseStmtSegmentDO();
        row.setSegIdx(segIdx);
        row.setType("GHOST");
        row.setStmtIndex(segIdx);
        row.setTableName("t_big");
        row.setRowsEstimated(8000000L);
        row.setSqlText("ALTER TABLE t_big ADD c INT");
        row.setStatus("PENDING");
        return row;
    }
}
