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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.console.web.component.approval.ApprovalStateService;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.component.auth.DmAuthServiceForBiz;
import com.clougence.clouddm.console.web.component.execute.AutoExecService;
import com.clougence.clouddm.console.web.component.governance.PrecheckConclusion;
import com.clougence.clouddm.console.web.component.governance.ProdReleaseStateMachine;
import com.clougence.clouddm.console.web.component.governance.ReleaseSchemaPrecheckService;
import com.clougence.clouddm.console.web.model.fo.prodrelease.ProdReleaseCreateFO;
import com.clougence.clouddm.console.web.service.approval.ApprovalControlService;
import com.clougence.clouddm.console.web.util.DmTeamUtils;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.DbChangeEventDal;
import com.clougence.clouddm.platform.dal.access.DbPairDal;
import com.clougence.clouddm.platform.dal.access.DataSourceDal;
import com.clougence.clouddm.platform.dal.access.ProdReleaseDal;
import com.clougence.clouddm.platform.dal.access.TicketDbStmtDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangeEventMapper;
import com.clougence.clouddm.platform.dal.mapper.dbpair.DmDbPairMapper;
import com.clougence.clouddm.platform.dal.mapper.datasource.DmDsMapper;
import com.clougence.clouddm.platform.dal.mapper.govticket.DmTicketDbStmtMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseStmtMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseStmtSchemaMapper;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO;
import com.clougence.clouddm.platform.dal.model.dbpair.DmDbPairDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseStmtDO;
import com.clougence.utils.JsonUtils;

/**
 * B7 N3 发布链披露 (design §10/T18 — the B6 M3 additive-hint mirror): the gate_result snapshot of
 * a release merge carries {@code stmts[].dmlBatchRouted} + {@code dmlBatchRoutedStmtCount} for
 * batch-routed sources ONLY — the frozen release snapshot replays the whole group text through the
 * DIRECT chain on prod (release-chain batch routing is v2, the M5 cadence), so the merge/detail
 * surface must warn that a statement which ran in throttled batches on pre will run as ONE direct
 * statement on prod. Pure snapshot metadata: no merge/replay logic reads it, plain sources keep
 * gate_result byte-identical (the B6 M3 plain-source pin family covers the ghost keys; the twin
 * pin here covers the new ones), and the two hint families stay INDEPENDENT (the N3
 * GhostRouteMarks precision fix: a batch-only source never sets ghostRouted).
 * <p>
 * Harness mirrors ProdReleaseServiceImplTest (B6 family stays zero-change); discipline: JUnit4 +
 * handwritten mocks + ReflectionTestUtils, no Spring context.
 */
public class ProdReleaseDmlBatchDisclosureTest {

    private ProdReleaseServiceImpl       service;
    private DmProdReleaseMapper          releaseMapper;
    private DmProdReleaseStmtMapper      stmtMapper;
    private DmTicketDbStmtMapper         ticketStmtMapper;
    private DmApprovalMapper             approvalMapper;
    private DmDbPairMapper               pairMapper;
    private DmDsMapper                   dsMapper;
    private ApprovalControlService       approvalControlService;

    private static final String PUID = "puid-001";
    private static final String UID  = "uid-001";
    private static final long   TICKET_ID = 130L;

    private static final String GHOST_PLAN =
        "{\"checkStatus\":\"PASS\",\"changeType\":\"DDL\",\"segments\":["
            + "{\"segIdx\":1,\"type\":\"GHOST\",\"stmtIndex\":1,\"table\":\"t_big\","
            + "\"sql\":\"ALTER TABLE t_big ADD c INT\"}]}";

    private static final String DML_BATCH_PLAN =
        "{\"checkStatus\":\"PASS\",\"changeType\":\"DML\",\"segments\":["
            + "{\"segIdx\":1,\"type\":\"DML_BATCH\",\"stmtIndex\":1,\"table\":\"t_order\","
            + "\"estimatedRows\":5000000,\"idempotent\":true,"
            + "\"sql\":\"DELETE FROM t_order WHERE status = 0\","
            + "\"batchKey\":{\"column\":\"id\",\"keyType\":\"INT\"}}]}";

    @Before
    public void setUp() {
        service = new ProdReleaseServiceImpl();

        // DmTeamUtils static executionDal (nextExecJobBizId/nextExecTaskBizId generator)
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
        ReflectionTestUtils.setField(DmTeamUtils.class, "executionDal", staticExecDal);

        ProdReleaseDal prodReleaseDal = mock(ProdReleaseDal.class);
        releaseMapper = mock(DmProdReleaseMapper.class);
        stmtMapper = mock(DmProdReleaseStmtMapper.class);
        when(prodReleaseDal.releaseMapper()).thenReturn(releaseMapper);
        when(prodReleaseDal.stmtMapper()).thenReturn(stmtMapper);
        DmProdReleaseStmtSchemaMapper prodSchemaMapper = mock(DmProdReleaseStmtSchemaMapper.class);
        when(prodReleaseDal.schemaMapper()).thenReturn(prodSchemaMapper);
        // ADR-013 M1: frozen segment manifest mapper (the DML_BATCH/GHOST plan fixtures now propagate)
        when(prodReleaseDal.segmentMapper()).thenReturn(mock(
            com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseStmtSegmentMapper.class));

        TicketDbStmtDal ticketDbStmtDal = mock(TicketDbStmtDal.class);
        ticketStmtMapper = mock(DmTicketDbStmtMapper.class);
        when(ticketDbStmtDal.stmtMapper()).thenReturn(ticketStmtMapper);
        com.clougence.clouddm.platform.dal.mapper.govticket.DmTicketStmtSchemaMapper ticketSchemaMapper
            = mock(com.clougence.clouddm.platform.dal.mapper.govticket.DmTicketStmtSchemaMapper.class);
        when(ticketDbStmtDal.schemaMapper()).thenReturn(ticketSchemaMapper);
        when(ticketSchemaMapper.queryByStmtId(anyLong())).thenReturn(java.util.Collections.emptyList());

        ApprovalDal approvalDal = mock(ApprovalDal.class);
        approvalMapper = mock(DmApprovalMapper.class);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);

        DbPairDal dbPairDal = mock(DbPairDal.class);
        pairMapper = mock(DmDbPairMapper.class);
        when(dbPairDal.pairMapper()).thenReturn(pairMapper);

        DataSourceDal dsDal = mock(DataSourceDal.class);
        dsMapper = mock(DmDsMapper.class);
        when(dsDal.dsMapper()).thenReturn(dsMapper);

        ReleaseSchemaPrecheckService releaseSchemaPrecheckService = mock(ReleaseSchemaPrecheckService.class);
        when(releaseSchemaPrecheckService.precheck(any())).thenReturn(PrecheckConclusion.pass());
        when(releaseSchemaPrecheckService.extractTables(anyString())).thenReturn(java.util.Collections.emptySet());
        com.clougence.clouddm.console.web.component.governance.ReleaseSchemaDiffService releaseSchemaDiffService
            = mock(com.clougence.clouddm.console.web.component.governance.ReleaseSchemaDiffService.class);
        when(releaseSchemaDiffService.diff(any())).thenReturn(PrecheckConclusion.pass());

        DbChangeEventDal dbChangeEventDal = mock(DbChangeEventDal.class);
        DmDbChangeEventMapper eventMapper = mock(DmDbChangeEventMapper.class);
        when(dbChangeEventDal.eventMapper()).thenReturn(eventMapper);
        when(eventMapper.insert(any(DmDbChangeEventDO.class))).thenReturn(1);

        com.clougence.clouddm.platform.dal.access.ExecutionDal execDal
            = mock(com.clougence.clouddm.platform.dal.access.ExecutionDal.class);
        when(execDal.autoJobMapper()).thenReturn(staticJobMapper);

        approvalControlService = mock(ApprovalControlService.class);

        ReflectionTestUtils.setField(service, "prodReleaseDal", prodReleaseDal);
        ReflectionTestUtils.setField(service, "dbChangeEventDal", dbChangeEventDal);
        ReflectionTestUtils.setField(service, "ticketDbStmtDal", ticketDbStmtDal);
        ReflectionTestUtils.setField(service, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(service, "dsDal", dsDal);
        ReflectionTestUtils.setField(service, "dbPairDal", dbPairDal);
        ReflectionTestUtils.setField(service, "approvalControlService", approvalControlService);
        ReflectionTestUtils.setField(service, "releaseStateMachine", mock(ProdReleaseStateMachine.class));
        ReflectionTestUtils.setField(service, "dmAuthServiceForBiz", mock(DmAuthServiceForBiz.class));
        ReflectionTestUtils.setField(service, "autoExecService", mock(AutoExecService.class));
        ReflectionTestUtils.setField(service, "approvalStateService", mock(ApprovalStateService.class));
        ReflectionTestUtils.setField(service, "releaseSchemaPrecheckService", releaseSchemaPrecheckService);
        ReflectionTestUtils.setField(service, "releaseSchemaDiffService", releaseSchemaDiffService);
        ReflectionTestUtils.setField(service, "execDal", execDal);
        ReflectionTestUtils.setField(service, "riskAssessmentService",
            new com.clougence.clouddm.console.web.component.governance.RiskAssessmentService());
    }

    // ==================== fixtures ====================

    private void stubMergeFlow(DmTicketDbStmtDO... stmts) {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setBizId("biz-" + TICKET_ID);
        ticket.setTicketStatus(ApprovalStatus.FINISHED);
        ticket.setPrimaryUid(PUID);
        ticket.setOwnerUid(UID);
        ticket.setGmtCreate(new Date());
        ApprovalMO mo = new ApprovalMO();
        mo.setTicketType("PRE_DDL");
        ticket.setTicketInfo(JsonUtils.toJson(mo));
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket);
        when(ticketStmtMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(stmts));
        when(stmtMapper.queryBySourceStmtId(anyLong())).thenReturn(null);
        when(pairMapper.selectById(300L)).thenReturn(buildPair());
        when(releaseMapper.countActiveByProdDb(11L, "prod_db_a")).thenReturn(0);
        when(releaseMapper.insert(any(DmProdReleaseDO.class))).thenAnswer(inv -> {
            inv.getArgument(0, DmProdReleaseDO.class).setId(600L);
            return 1;
        });
        when(stmtMapper.insert(any(DmProdReleaseStmtDO.class))).thenAnswer(inv -> {
            inv.getArgument(0, DmProdReleaseStmtDO.class).setId(800L);
            return 1;
        });
        com.clougence.clouddm.platform.dal.model.datasource.DmDsDO prodDs
            = new com.clougence.clouddm.platform.dal.model.datasource.DmDsDO();
        prodDs.setDsEnvId(1L);
        when(dsMapper.queryDsIdentityById(11L)).thenReturn(prodDs);
        com.clougence.clouddm.console.web.model.vo.ticket.DmTicketResultVO ticketResult
            = new com.clougence.clouddm.console.web.model.vo.ticket.DmTicketResultVO();
        ticketResult.setTicketId(700L);
        when(approvalControlService.createSqlTicket(any(), any(), any(),
            eq(com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz.DM_PROD_RELEASE)))
            .thenReturn(ticketResult);
        DmApprovalDO newApproval = new DmApprovalDO();
        newApproval.setId(700L);
        newApproval.setTicketInfo(JsonUtils.toJson(new ApprovalMO()));
        when(approvalMapper.queryById(700L)).thenReturn(newApproval);
    }

    private static DmDbPairDO buildPair() {
        DmDbPairDO pair = new DmDbPairDO();
        pair.setId(300L);
        pair.setProdDsId(11L);
        pair.setProdDbName("prod_db_a");
        pair.setStatus("ENABLED");
        return pair;
    }

    private static DmTicketDbStmtDO stmt(long id, String precheckResult) {
        DmTicketDbStmtDO stmt = new DmTicketDbStmtDO();
        stmt.setId(id);
        stmt.setTicketId(TICKET_ID);
        stmt.setPairId(300L);
        stmt.setDsId(20L);
        stmt.setDbName("pre_db");
        stmt.setSqlContent("DELETE FROM t_order WHERE status = 0;");
        stmt.setExecStatus("SUCCESS");
        stmt.setPrecheckResult(precheckResult);
        return stmt;
    }

    private Map<?, ?> capturedGateResult() {
        ArgumentCaptor<DmProdReleaseDO> captor = ArgumentCaptor.forClass(DmProdReleaseDO.class);
        verify(releaseMapper).insert(captor.capture());
        return JsonUtils.toObj(captor.getValue().getGateResult(), Map.class);
    }

    // ==================== pins ====================

    @Test
    public void createRelease_dmlBatchRoutedSource_gateResultCarriesAdditiveHint_noGhostLeak() {
        stubMergeFlow(stmt(230L, DML_BATCH_PLAN));

        ProdReleaseCreateFO fo = new ProdReleaseCreateFO();
        fo.setTicketIds(List.of(TICKET_ID));
        service.createRelease(PUID, UID, fo);

        Map<?, ?> gr = capturedGateResult();
        assertEquals("the additive top-level batch hint count", 1, gr.get("dmlBatchRoutedStmtCount"));
        List<?> stmts = (List<?>) gr.get("stmts");
        assertEquals(1, stmts.size());
        Map<?, ?> s0 = (Map<?, ?>) stmts.get(0);
        assertEquals("the per-stmt additive flag", Boolean.TRUE, s0.get("dmlBatchRouted"));
        // the N3 precision fix: a batch-only source NEVER sets the gh-ost hint (its copy is
        // ALTER-specific) — the two families are independent
        assertFalse(s0.containsKey("ghostRouted"));
        assertFalse(gr.containsKey("ghostRoutedStmtCount"));
        // the frozen contract keys are untouched
        assertEquals(1, gr.get("sourceTicketCount"));
        assertEquals(1, gr.get("stmtCount"));
    }

    @Test
    public void createRelease_ghostAndBatchStmts_hintFamiliesIndependentPerStmt() {
        stubMergeFlow(stmt(230L, GHOST_PLAN), stmt(231L, DML_BATCH_PLAN));

        ProdReleaseCreateFO fo = new ProdReleaseCreateFO();
        fo.setTicketIds(List.of(TICKET_ID));
        service.createRelease(PUID, UID, fo);

        Map<?, ?> gr = capturedGateResult();
        assertEquals(1, gr.get("ghostRoutedStmtCount"));
        assertEquals(1, gr.get("dmlBatchRoutedStmtCount"));
        List<?> stmts = (List<?>) gr.get("stmts");
        assertEquals(2, stmts.size());
        Map<?, ?> ghostStmt = (Map<?, ?>) stmts.get(0); // id-ASC: 230 first
        Map<?, ?> batchStmt = (Map<?, ?>) stmts.get(1);
        assertEquals(230L, ((Number) ghostStmt.get("sourceStmtId")).longValue());
        assertEquals(Boolean.TRUE, ghostStmt.get("ghostRouted"));
        assertFalse(ghostStmt.containsKey("dmlBatchRouted"));
        assertEquals(231L, ((Number) batchStmt.get("sourceStmtId")).longValue());
        assertEquals(Boolean.TRUE, batchStmt.get("dmlBatchRouted"));
        assertFalse(batchStmt.containsKey("ghostRouted"));
    }

    @Test
    public void createRelease_plainSource_noDmlBatchHintKeys_byteShapeTwin() {
        // the B6 M3 plain-source pin's twin for the new keys: a plain release keeps gate_result
        // byte-identical (no hint keys of EITHER family)
        stubMergeFlow(stmt(231L, "{\"checkStatus\":\"PASS\",\"changeType\":\"DML\"}"));

        ProdReleaseCreateFO fo = new ProdReleaseCreateFO();
        fo.setTicketIds(List.of(TICKET_ID));
        service.createRelease(PUID, UID, fo);

        Map<?, ?> gr = capturedGateResult();
        assertFalse(gr.containsKey("dmlBatchRoutedStmtCount"));
        assertFalse(gr.containsKey("ghostRoutedStmtCount"));
        List<?> stmts = (List<?>) gr.get("stmts");
        Map<?, ?> s0 = (Map<?, ?>) stmts.get(0);
        assertFalse(s0.containsKey("dmlBatchRouted"));
        assertFalse(s0.containsKey("ghostRouted"));
        assertTrue(s0.containsKey("hash")); // the frozen snapshot keys are intact
    }
}
