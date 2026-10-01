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

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.model.vo.prodrelease.GovLedgerTicketVO;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.DataSourceDal;
import com.clougence.clouddm.platform.dal.access.DbPairDal;
import com.clougence.clouddm.platform.dal.access.ProdReleaseDal;
import com.clougence.clouddm.platform.dal.access.TicketDbStmtDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.datasource.DmDsMapper;
import com.clougence.clouddm.platform.dal.mapper.dbpair.DmDbPairMapper;
import com.clougence.clouddm.platform.dal.mapper.dbpair.DmDbServiceMapper;
import com.clougence.clouddm.platform.dal.mapper.govticket.DmTicketDbStmtMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseStmtMapper;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.datasource.DmDsDO;
import com.clougence.clouddm.platform.dal.model.dbpair.DmDbPairDO;
import com.clougence.clouddm.platform.dal.model.dbpair.DmDbServiceDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseStmtDO;
import com.clougence.utils.JsonUtils;

/**
 * Tests for GovLedgerServiceImpl.list:
 * a) positive case — aggregated fields (preDbs, prodDbs, serviceId) populated
 * b) two tickets — single batch query for serviceName (no N+1)
 * c) serviceId missing → serviceName stays null, no batch query issued
 * d) exclusion: non-FINISHED, non-PRE_DDL, partial-SUCCESS
 * e) promoted ticket carries releaseNo/releaseStatus
 * f) cross-db ticket aggregates preDbs (2 entries) and prodDbs (2 entries)
 */
public class GovLedgerServiceImplTest {

    private GovLedgerServiceImpl   service;

    private TicketDbStmtDal        ticketDbStmtDal;
    private DmTicketDbStmtMapper    ticketStmtMapper;
    private ApprovalDal            approvalDal;
    private DmApprovalMapper       approvalMapper;
    private ProdReleaseDal         prodReleaseDal;
    private DmProdReleaseStmtMapper releaseStmtMapper;
    private DmProdReleaseMapper     releaseMapper;
    private DbPairDal              dbPairDal;
    private DmDbServiceMapper       serviceMapper;
    private DmDbPairMapper          pairMapper;
    private DataSourceDal          dsDal;
    private DmDsMapper              dsMapper;

    private static final String PUID = "puid-001";

    @Before
    public void setUp() {
        service = new GovLedgerServiceImpl();

        ticketDbStmtDal = mock(TicketDbStmtDal.class);
        ticketStmtMapper = mock(DmTicketDbStmtMapper.class);
        when(ticketDbStmtDal.stmtMapper()).thenReturn(ticketStmtMapper);

        approvalDal = mock(ApprovalDal.class);
        approvalMapper = mock(DmApprovalMapper.class);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);

        prodReleaseDal = mock(ProdReleaseDal.class);
        releaseStmtMapper = mock(DmProdReleaseStmtMapper.class);
        releaseMapper = mock(DmProdReleaseMapper.class);
        when(prodReleaseDal.stmtMapper()).thenReturn(releaseStmtMapper);
        when(prodReleaseDal.releaseMapper()).thenReturn(releaseMapper);

        dbPairDal = mock(DbPairDal.class);
        serviceMapper = mock(DmDbServiceMapper.class);
        pairMapper = mock(DmDbPairMapper.class);
        when(dbPairDal.serviceMapper()).thenReturn(serviceMapper);
        when(dbPairDal.pairMapper()).thenReturn(pairMapper);

        dsDal = mock(DataSourceDal.class);
        dsMapper = mock(DmDsMapper.class);
        when(dsDal.dsMapper()).thenReturn(dsMapper);

        ReflectionTestUtils.setField(service, "ticketDbStmtDal", ticketDbStmtDal);
        ReflectionTestUtils.setField(service, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(service, "prodReleaseDal", prodReleaseDal);
        ReflectionTestUtils.setField(service, "dbPairDal", dbPairDal);
        ReflectionTestUtils.setField(service, "dsDal", dsDal);
    }

    // ==================== positive: aggregated fields + serviceName ====================

    @Test
    public void list_fillsAggregatedFields_andServiceName() {
        long ticketId = 100L;
        long stmtId = 200L;
        long serviceId = 5L;
        long pairId = 30L;
        long prodDsId = 20L;

        DmTicketDbStmtDO stmt = buildStmt(stmtId, ticketId, pairId, 10L, "pre_db", "SUCCESS");
        when(ticketStmtMapper.selectList(any())).thenReturn(List.of(stmt));

        DmApprovalDO ticket = buildTicket(ticketId, ApprovalStatus.FINISHED, "PRE_DDL", serviceId);
        when(approvalMapper.selectBatchIds(anyCollection())).thenReturn(List.of(ticket));

        when(releaseStmtMapper.queryBySourceStmtId(stmtId)).thenReturn(null);

        DmDbPairDO pair = buildPair(pairId, prodDsId, "prod_db", "ENABLED");
        when(pairMapper.selectBatchIds(anyCollection())).thenReturn(List.of(pair));

        when(dsMapper.queryDsIdentityById(10L)).thenReturn(buildDs(10L, "pre-ds"));
        when(dsMapper.queryDsIdentityById(prodDsId)).thenReturn(buildDs(prodDsId, "prod-ds"));

        DmDbServiceDO svc = new DmDbServiceDO();
        svc.setId(serviceId);
        svc.setServiceName("Order Service");
        when(serviceMapper.selectBatchIds(anyCollection())).thenReturn(List.of(svc));

        List<GovLedgerTicketVO> result = service.list(PUID);

        assertEquals(1, result.size());
        GovLedgerTicketVO vo = result.get(0);
        assertEquals(ticketId, vo.getTicketId());
        assertEquals("Order Service", vo.getServiceName());
        assertEquals(Long.valueOf(serviceId), vo.getServiceId());
        assertFalse(vo.isPromoted());

        assertEquals(1, vo.getPreDbs().size());
        assertEquals(10L, vo.getPreDbs().get(0).getDsId());
        assertEquals("pre_db", vo.getPreDbs().get(0).getDbName());
        assertEquals("pre-ds", vo.getPreDbs().get(0).getDsName());

        assertEquals(1, vo.getProdDbs().size());
        assertEquals("prod-ds/prod_db", vo.getProdDbs().get(0));

        verify(serviceMapper, times(1)).selectBatchIds(anyCollection());
        verify(approvalMapper, times(1)).selectBatchIds(anyCollection());
    }

    // ==================== B6 M3 过渡明示: ghostRouted additive flag ====================

    @Test
    public void list_ghostRoutedTicket_flaggedForMergeConfirmHint_m3() {
        long ticketId = 110L;
        long stmtId = 210L;

        DmTicketDbStmtDO stmt = buildStmt(stmtId, ticketId, 30L, 10L, "pre_db", "SUCCESS");
        stmt.setPrecheckResult("{\"checkStatus\":\"PASS\",\"changeType\":\"DDL\",\"segments\":["
            + "{\"segIdx\":1,\"type\":\"GHOST\",\"stmtIndex\":1,\"sql\":\"ALTER TABLE t_big ADD c INT\"}]}");
        when(ticketStmtMapper.selectList(any())).thenReturn(List.of(stmt));
        when(approvalMapper.selectBatchIds(anyCollection()))
            .thenReturn(List.of(buildTicket(ticketId, ApprovalStatus.FINISHED, "PRE_DDL", 5L)));
        when(releaseStmtMapper.queryBySourceStmtId(stmtId)).thenReturn(null);
        when(pairMapper.selectBatchIds(anyCollection()))
            .thenReturn(List.of(buildPair(30L, 20L, "prod_db", "ENABLED")));
        when(dsMapper.queryDsIdentityById(10L)).thenReturn(buildDs(10L, "pre-ds"));
        when(dsMapper.queryDsIdentityById(20L)).thenReturn(buildDs(20L, "prod-ds"));

        List<GovLedgerTicketVO> result = service.list(PUID);

        assertEquals(1, result.size());
        assertTrue("the segmented ghost route marks the ledger row", result.get(0).isGhostRouted());
    }

    @Test
    public void list_m1GhostRoutesMarkOnly_alsoFlagged_m3() {
        // the M1 flags-only degenerate shape (ghostRoutes display marks, no segment plan) counts too
        long ticketId = 111L;
        long stmtId = 211L;

        DmTicketDbStmtDO stmt = buildStmt(stmtId, ticketId, 30L, 10L, "pre_db", "SUCCESS");
        stmt.setPrecheckResult("{\"checkStatus\":\"PASS\",\"changeType\":\"DDL\",\"ghostRoutes\":["
            + "{\"stmtIndex\":1,\"route\":\"GHOST\",\"tableName\":\"t_big\"}]}");
        when(ticketStmtMapper.selectList(any())).thenReturn(List.of(stmt));
        when(approvalMapper.selectBatchIds(anyCollection()))
            .thenReturn(List.of(buildTicket(ticketId, ApprovalStatus.FINISHED, "PRE_DDL", 5L)));
        when(releaseStmtMapper.queryBySourceStmtId(stmtId)).thenReturn(null);
        when(pairMapper.selectBatchIds(anyCollection()))
            .thenReturn(List.of(buildPair(30L, 20L, "prod_db", "ENABLED")));
        when(dsMapper.queryDsIdentityById(10L)).thenReturn(buildDs(10L, "pre-ds"));
        when(dsMapper.queryDsIdentityById(20L)).thenReturn(buildDs(20L, "prod-ds"));

        List<GovLedgerTicketVO> result = service.list(PUID);

        assertTrue(result.get(0).isGhostRouted());
    }

    @Test
    public void list_plainTicket_flagStaysFalse_m3() {
        long ticketId = 112L;
        long stmtId = 212L;

        DmTicketDbStmtDO stmt = buildStmt(stmtId, ticketId, 30L, 10L, "pre_db", "SUCCESS");
        stmt.setPrecheckResult("{\"checkStatus\":\"PASS\",\"changeType\":\"DDL\"}");
        when(ticketStmtMapper.selectList(any())).thenReturn(List.of(stmt));
        when(approvalMapper.selectBatchIds(anyCollection()))
            .thenReturn(List.of(buildTicket(ticketId, ApprovalStatus.FINISHED, "PRE_DDL", 5L)));
        when(releaseStmtMapper.queryBySourceStmtId(stmtId)).thenReturn(null);
        when(pairMapper.selectBatchIds(anyCollection()))
            .thenReturn(List.of(buildPair(30L, 20L, "prod_db", "ENABLED")));
        when(dsMapper.queryDsIdentityById(10L)).thenReturn(buildDs(10L, "pre-ds"));
        when(dsMapper.queryDsIdentityById(20L)).thenReturn(buildDs(20L, "prod-ds"));

        List<GovLedgerTicketVO> result = service.list(PUID);

        assertFalse(result.get(0).isGhostRouted());
    }

    // ==================== two tickets, one batch query ====================

    @Test
    public void list_twoTickets_oneBatchQuery() {
        long stmtId1 = 201L;
        long stmtId2 = 202L;
        long serviceId1 = 5L;
        long serviceId2 = 6L;

        DmTicketDbStmtDO stmt1 = buildStmt(stmtId1, 101L, 30L, 10L, "pre_db", "SUCCESS");
        DmTicketDbStmtDO stmt2 = buildStmt(stmtId2, 102L, 31L, 10L, "pre_db", "SUCCESS");
        when(ticketStmtMapper.selectList(any())).thenReturn(Arrays.asList(stmt1, stmt2));

        DmApprovalDO ticket1 = buildTicket(101L, ApprovalStatus.FINISHED, "PRE_DDL", serviceId1);
        DmApprovalDO ticket2 = buildTicket(102L, ApprovalStatus.FINISHED, "PRE_DDL", serviceId2);
        when(approvalMapper.selectBatchIds(anyCollection())).thenReturn(Arrays.asList(ticket1, ticket2));

        when(releaseStmtMapper.queryBySourceStmtId(stmtId1)).thenReturn(null);
        when(releaseStmtMapper.queryBySourceStmtId(stmtId2)).thenReturn(null);

        DmDbPairDO pair1 = buildPair(30L, 20L, "prod_db1", "ENABLED");
        DmDbPairDO pair2 = buildPair(31L, 20L, "prod_db2", "ENABLED");
        when(pairMapper.selectBatchIds(anyCollection())).thenReturn(Arrays.asList(pair1, pair2));

        when(dsMapper.queryDsIdentityById(10L)).thenReturn(buildDs(10L, "pre-ds"));
        when(dsMapper.queryDsIdentityById(20L)).thenReturn(buildDs(20L, "prod-ds"));

        DmDbServiceDO svc1 = new DmDbServiceDO();
        svc1.setId(serviceId1);
        svc1.setServiceName("Order Service");
        DmDbServiceDO svc2 = new DmDbServiceDO();
        svc2.setId(serviceId2);
        svc2.setServiceName("Payment Service");
        when(serviceMapper.selectBatchIds(anyCollection())).thenReturn(Arrays.asList(svc1, svc2));

        List<GovLedgerTicketVO> result = service.list(PUID);

        assertEquals(2, result.size());
        assertEquals("Order Service", result.get(0).getServiceName());
        assertEquals("Payment Service", result.get(1).getServiceName());

        verify(serviceMapper, times(1)).selectBatchIds(anyCollection());
    }

    // ==================== serviceId missing → serviceName null ====================

    @Test
    public void list_serviceIdMissing_serviceNameStaysNull() {
        long ticketId = 103L;
        long stmtId = 203L;

        DmTicketDbStmtDO stmt = buildStmt(stmtId, ticketId, 30L, 10L, "pre_db", "SUCCESS");
        when(ticketStmtMapper.selectList(any())).thenReturn(List.of(stmt));

        DmApprovalDO ticket = buildTicket(ticketId, ApprovalStatus.FINISHED, "PRE_DDL", null);
        when(approvalMapper.selectBatchIds(anyCollection())).thenReturn(List.of(ticket));

        when(releaseStmtMapper.queryBySourceStmtId(stmtId)).thenReturn(null);

        when(pairMapper.selectBatchIds(anyCollection())).thenReturn(List.of(buildPair(30L, 20L, "prod_db", "ENABLED")));
        when(dsMapper.queryDsIdentityById(10L)).thenReturn(buildDs(10L, "pre-ds"));
        when(dsMapper.queryDsIdentityById(20L)).thenReturn(buildDs(20L, "prod-ds"));

        List<GovLedgerTicketVO> result = service.list(PUID);

        assertEquals(1, result.size());
        assertNull(result.get(0).getServiceName());
        assertNull(result.get(0).getServiceId());

        verify(serviceMapper, never()).selectBatchIds(anyCollection());
    }

    // ==================== exclude non-FINISHED ====================

    @Test
    public void list_excludesNonFinished() {
        DmTicketDbStmtDO stmt = buildStmt(204L, 104L, 30L, 10L, "pre_db", "SUCCESS");
        when(ticketStmtMapper.selectList(any())).thenReturn(List.of(stmt));

        DmApprovalDO ticket = buildTicket(104L, ApprovalStatus.WAIT_CONFIRM, "PRE_DDL", 5L);
        when(approvalMapper.selectBatchIds(anyCollection())).thenReturn(List.of(ticket));

        when(pairMapper.selectBatchIds(anyCollection())).thenReturn(List.of(buildPair(30L, 20L, "prod_db", "ENABLED")));
        when(dsMapper.queryDsIdentityById(anyLong())).thenReturn(buildDs(1L, "ds"));

        List<GovLedgerTicketVO> result = service.list(PUID);

        assertEquals(0, result.size());
    }

    // ==================== exclude non-PRE_DDL ====================

    @Test
    public void list_excludesNonPreDdl() {
        DmTicketDbStmtDO stmt = buildStmt(205L, 105L, 30L, 10L, "pre_db", "SUCCESS");
        when(ticketStmtMapper.selectList(any())).thenReturn(List.of(stmt));

        DmApprovalDO ticket = buildTicket(105L, ApprovalStatus.FINISHED, "PROD_DML", 5L);
        when(approvalMapper.selectBatchIds(anyCollection())).thenReturn(List.of(ticket));

        when(pairMapper.selectBatchIds(anyCollection())).thenReturn(List.of(buildPair(30L, 20L, "prod_db", "ENABLED")));
        when(dsMapper.queryDsIdentityById(anyLong())).thenReturn(buildDs(1L, "ds"));

        List<GovLedgerTicketVO> result = service.list(PUID);

        assertEquals(0, result.size());
    }

    // ==================== exclude partial-SUCCESS ====================

    @Test
    public void list_excludesPartialSuccess() {
        DmTicketDbStmtDO stmt1 = buildStmt(206L, 106L, 30L, 10L, "pre_db", "SUCCESS");
        DmTicketDbStmtDO stmt2 = buildStmt(207L, 106L, 31L, 11L, "pre_db2", "FAILED");
        when(ticketStmtMapper.selectList(any())).thenReturn(Arrays.asList(stmt1, stmt2));

        DmApprovalDO ticket = buildTicket(106L, ApprovalStatus.FINISHED, "PRE_DDL", 5L);
        when(approvalMapper.selectBatchIds(anyCollection())).thenReturn(List.of(ticket));

        when(pairMapper.selectBatchIds(anyCollection())).thenReturn(Arrays.asList(
            buildPair(30L, 20L, "prod_db", "ENABLED"),
            buildPair(31L, 21L, "prod_db2", "ENABLED")));
        when(dsMapper.queryDsIdentityById(anyLong())).thenReturn(buildDs(1L, "ds"));

        List<GovLedgerTicketVO> result = service.list(PUID);

        assertEquals(0, result.size());
    }

    // ==================== branch B: VIA_RELEASE PROD_DML pool ====================

    @Test
    public void list_viaReleaseWaitRelease_allPending_appears() {
        long ticketId = 110L;
        long stmtId = 210L;
        DmTicketDbStmtDO stmt = buildStmt(stmtId, ticketId, 30L, 10L, "prod_db", "PENDING");
        when(ticketStmtMapper.selectList(any())).thenReturn(List.of(stmt));

        DmApprovalDO ticket = buildTicket(ticketId, ApprovalStatus.WAIT_RELEASE, "PROD_DML", 5L, "VIA_RELEASE");
        when(approvalMapper.selectBatchIds(anyCollection())).thenReturn(List.of(ticket));
        when(releaseStmtMapper.queryBySourceStmtId(stmtId)).thenReturn(null);
        when(pairMapper.selectBatchIds(anyCollection())).thenReturn(List.of(buildPair(30L, 20L, "prod_db", "ENABLED")));
        when(dsMapper.queryDsIdentityById(anyLong())).thenReturn(buildDs(1L, "ds"));
        when(serviceMapper.selectBatchIds(anyCollection())).thenReturn(Collections.emptyList());

        List<GovLedgerTicketVO> result = service.list(PUID);

        assertEquals(1, result.size());
        GovLedgerTicketVO vo = result.get(0);
        assertEquals("PROD_DML", vo.getTicketType());
        assertEquals("VIA_RELEASE", vo.getApprovalMode());
        assertEquals("PENDING", vo.getExecStatus());
        assertFalse(vo.isPromoted());
    }

    @Test
    public void list_viaReleaseFinished_allPending_appearsForTraceability() {
        long ticketId = 111L;
        long stmtId = 211L;
        DmTicketDbStmtDO stmt = buildStmt(stmtId, ticketId, 30L, 10L, "prod_db", "PENDING");
        when(ticketStmtMapper.selectList(any())).thenReturn(List.of(stmt));

        // Via-release-completed ticket: status=FINISHED but stmt rows still PENDING (release exec
        // never touches source rows) — retained for traceability, distinguishable from direct.
        DmApprovalDO ticket = buildTicket(ticketId, ApprovalStatus.FINISHED, "PROD_DML", 5L, "VIA_RELEASE");
        when(approvalMapper.selectBatchIds(anyCollection())).thenReturn(List.of(ticket));
        when(releaseStmtMapper.queryBySourceStmtId(stmtId)).thenReturn(null);
        when(pairMapper.selectBatchIds(anyCollection())).thenReturn(List.of(buildPair(30L, 20L, "prod_db", "ENABLED")));
        when(dsMapper.queryDsIdentityById(anyLong())).thenReturn(buildDs(1L, "ds"));
        when(serviceMapper.selectBatchIds(anyCollection())).thenReturn(Collections.emptyList());

        List<GovLedgerTicketVO> result = service.list(PUID);

        assertEquals(1, result.size());
        assertEquals("PROD_DML", result.get(0).getTicketType());
        assertEquals("VIA_RELEASE", result.get(0).getApprovalMode());
    }

    @Test
    public void list_directProdDml_neverAppears() {
        // Direct PROD_DML (FINISHED + all SUCCESS, no VIA_RELEASE flag) matches neither branch.
        long ticketId = 112L;
        long stmtId = 212L;
        DmTicketDbStmtDO stmt = buildStmt(stmtId, ticketId, 30L, 10L, "prod_db", "SUCCESS");
        when(ticketStmtMapper.selectList(any())).thenReturn(List.of(stmt));

        DmApprovalDO ticket = buildTicket(ticketId, ApprovalStatus.FINISHED, "PROD_DML", 5L, "DIRECT");
        when(approvalMapper.selectBatchIds(anyCollection())).thenReturn(List.of(ticket));
        when(pairMapper.selectBatchIds(anyCollection())).thenReturn(List.of(buildPair(30L, 20L, "prod_db", "ENABLED")));
        when(dsMapper.queryDsIdentityById(anyLong())).thenReturn(buildDs(1L, "ds"));

        List<GovLedgerTicketVO> result = service.list(PUID);

        assertEquals(0, result.size());
    }

    @Test
    public void list_viaReleaseWaitApproval_notInLedger() {
        // VIA_RELEASE should never be in WAIT_APPROVAL (submit puts it in WAIT_RELEASE),
        // but if somehow it were, it must not appear — wrong status for branch B.
        long ticketId = 113L;
        long stmtId = 213L;
        DmTicketDbStmtDO stmt = buildStmt(stmtId, ticketId, 30L, 10L, "prod_db", "PENDING");
        when(ticketStmtMapper.selectList(any())).thenReturn(List.of(stmt));

        DmApprovalDO ticket = buildTicket(ticketId, ApprovalStatus.WAIT_APPROVAL, "PROD_DML", 5L, "VIA_RELEASE");
        when(approvalMapper.selectBatchIds(anyCollection())).thenReturn(List.of(ticket));
        when(pairMapper.selectBatchIds(anyCollection())).thenReturn(List.of(buildPair(30L, 20L, "prod_db", "ENABLED")));
        when(dsMapper.queryDsIdentityById(anyLong())).thenReturn(buildDs(1L, "ds"));

        List<GovLedgerTicketVO> result = service.list(PUID);

        assertEquals(0, result.size());
    }

    @Test
    public void list_branchA_regression_preDdlStillAppears() {
        long ticketId = 114L;
        long stmtId = 214L;
        DmTicketDbStmtDO stmt = buildStmt(stmtId, ticketId, 30L, 10L, "pre_db", "SUCCESS");
        when(ticketStmtMapper.selectList(any())).thenReturn(List.of(stmt));

        DmApprovalDO ticket = buildTicket(ticketId, ApprovalStatus.FINISHED, "PRE_DDL", 5L);
        when(approvalMapper.selectBatchIds(anyCollection())).thenReturn(List.of(ticket));
        when(releaseStmtMapper.queryBySourceStmtId(stmtId)).thenReturn(null);
        when(pairMapper.selectBatchIds(anyCollection())).thenReturn(List.of(buildPair(30L, 20L, "prod_db", "ENABLED")));
        when(dsMapper.queryDsIdentityById(anyLong())).thenReturn(buildDs(1L, "ds"));
        when(serviceMapper.selectBatchIds(anyCollection())).thenReturn(Collections.emptyList());

        List<GovLedgerTicketVO> result = service.list(PUID);

        assertEquals(1, result.size());
        assertEquals("PRE_DDL", result.get(0).getTicketType());
        assertEquals("SUCCESS", result.get(0).getExecStatus());
    }

    // ==================== promoted ticket carries release info ====================

    @Test
    public void list_promotedTicket_hasReleaseInfo() {
        long ticketId = 107L;
        long stmtId = 208L;
        long releaseId = 999L;

        DmTicketDbStmtDO stmt = buildStmt(stmtId, ticketId, 30L, 10L, "pre_db", "SUCCESS");
        when(ticketStmtMapper.selectList(any())).thenReturn(List.of(stmt));

        DmApprovalDO ticket = buildTicket(ticketId, ApprovalStatus.FINISHED, "PRE_DDL", 5L);
        when(approvalMapper.selectBatchIds(anyCollection())).thenReturn(List.of(ticket));

        DmProdReleaseStmtDO releaseStmt = new DmProdReleaseStmtDO();
        releaseStmt.setReleaseId(releaseId);
        when(releaseStmtMapper.queryBySourceStmtId(stmtId)).thenReturn(releaseStmt);

        DmProdReleaseDO release = new DmProdReleaseDO();
        release.setId(releaseId);
        release.setReleaseNo("REL-001");
        release.setStatus("APPROVAL");
        when(releaseMapper.queryById(releaseId)).thenReturn(release);

        when(pairMapper.selectBatchIds(anyCollection())).thenReturn(List.of(buildPair(30L, 20L, "prod_db", "ENABLED")));
        when(dsMapper.queryDsIdentityById(anyLong())).thenReturn(buildDs(1L, "ds"));
        when(serviceMapper.selectBatchIds(anyCollection())).thenReturn(Collections.emptyList());

        List<GovLedgerTicketVO> result = service.list(PUID);

        assertEquals(1, result.size());
        GovLedgerTicketVO vo = result.get(0);
        assertTrue(vo.isPromoted());
        assertEquals("REL-001", vo.getReleaseNo());
        assertEquals("APPROVAL", vo.getReleaseStatus());
    }

    // ==================== cross-db ticket aggregates preDbs and prodDbs ====================

    @Test
    public void list_crossDbTicket_aggregatesPreDbsAndProdDbs() {
        long ticketId = 108L;
        long stmtId1 = 209L;
        long stmtId2 = 210L;

        DmTicketDbStmtDO stmt1 = buildStmt(stmtId1, ticketId, 30L, 10L, "pre_db_a", "SUCCESS");
        DmTicketDbStmtDO stmt2 = buildStmt(stmtId2, ticketId, 31L, 11L, "pre_db_b", "SUCCESS");
        when(ticketStmtMapper.selectList(any())).thenReturn(Arrays.asList(stmt1, stmt2));

        DmApprovalDO ticket = buildTicket(ticketId, ApprovalStatus.FINISHED, "PRE_DDL", 5L);
        when(approvalMapper.selectBatchIds(anyCollection())).thenReturn(List.of(ticket));

        when(releaseStmtMapper.queryBySourceStmtId(stmtId1)).thenReturn(null);

        DmDbPairDO pair1 = buildPair(30L, 20L, "prod_db_a", "ENABLED");
        DmDbPairDO pair2 = buildPair(31L, 21L, "prod_db_b", "ENABLED");
        when(pairMapper.selectBatchIds(anyCollection())).thenReturn(Arrays.asList(pair1, pair2));

        when(dsMapper.queryDsIdentityById(10L)).thenReturn(buildDs(10L, "pre-ds-a"));
        when(dsMapper.queryDsIdentityById(11L)).thenReturn(buildDs(11L, "pre-ds-b"));
        when(dsMapper.queryDsIdentityById(20L)).thenReturn(buildDs(20L, "prod-ds-a"));
        when(dsMapper.queryDsIdentityById(21L)).thenReturn(buildDs(21L, "prod-ds-b"));

        when(serviceMapper.selectBatchIds(anyCollection())).thenReturn(Collections.emptyList());

        List<GovLedgerTicketVO> result = service.list(PUID);

        assertEquals(1, result.size());
        GovLedgerTicketVO vo = result.get(0);

        assertEquals(2, vo.getPreDbs().size());
        assertEquals(10L, vo.getPreDbs().get(0).getDsId());
        assertEquals("pre_db_a", vo.getPreDbs().get(0).getDbName());
        assertEquals("pre-ds-a", vo.getPreDbs().get(0).getDsName());
        assertEquals(11L, vo.getPreDbs().get(1).getDsId());
        assertEquals("pre_db_b", vo.getPreDbs().get(1).getDbName());
        assertEquals("pre-ds-b", vo.getPreDbs().get(1).getDsName());

        assertEquals(2, vo.getProdDbs().size());
        assertEquals("prod-ds-a/prod_db_a", vo.getProdDbs().get(0));
        assertEquals("prod-ds-b/prod_db_b", vo.getProdDbs().get(1));
    }

    // ==================== helpers ====================

    private DmTicketDbStmtDO buildStmt(long id, long ticketId, long pairId, long dsId, String dbName, String execStatus) {
        DmTicketDbStmtDO stmt = new DmTicketDbStmtDO();
        stmt.setId(id);
        stmt.setTicketId(ticketId);
        stmt.setPairId(pairId);
        stmt.setDsId(dsId);
        stmt.setDbName(dbName);
        stmt.setSqlContent("CREATE TABLE t(id int);");
        stmt.setExecStatus(execStatus);
        return stmt;
    }

    private DmApprovalDO buildTicket(long id, ApprovalStatus status, String ticketType, Long serviceId) {
        return buildTicket(id, status, ticketType, serviceId, null);
    }

    private DmApprovalDO buildTicket(long id, ApprovalStatus status, String ticketType, Long serviceId, String approvalMode) {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(id);
        ticket.setBizId("biz-" + id);
        ticket.setTicketStatus(status);
        ticket.setPrimaryUid(PUID);
        ticket.setOwnerUid("uid-" + id);
        ticket.setTicketTitle("Ticket " + id);
        ticket.setGmtCreate(new Date());
        ApprovalMO mo = new ApprovalMO();
        mo.setTicketType(ticketType);
        mo.setServiceId(serviceId);
        mo.setApprovalMode(approvalMode);
        ticket.setTicketInfo(JsonUtils.toJson(mo));
        return ticket;
    }

    private DmDbPairDO buildPair(long id, long prodDsId, String prodDbName, String status) {
        DmDbPairDO pair = new DmDbPairDO();
        pair.setId(id);
        pair.setProdDsId(prodDsId);
        pair.setProdDbName(prodDbName);
        pair.setStatus(status);
        return pair;
    }

    private DmDsDO buildDs(long id, String instanceDesc) {
        DmDsDO ds = new DmDsDO();
        ds.setId(id);
        ds.setInstanceDesc(instanceDesc);
        return ds;
    }
}
