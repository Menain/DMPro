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
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.component.auth.DmResAuthService;
import com.clougence.clouddm.console.web.model.fo.openapi.DbChangeReleaseDetailFO;
import com.clougence.clouddm.console.web.model.fo.openapi.DbChangeTicketListFO;
import com.clougence.clouddm.console.web.model.fo.openapi.DbChangeTicketStmtFO;
import com.clougence.clouddm.console.web.model.vo.openapi.OpenDbChangeTicketStmtVO;
import com.clougence.clouddm.console.web.model.vo.openapi.OpenDbChangeTicketVO;
import com.clougence.clouddm.console.web.model.vo.prodrelease.ProdReleaseDetailVO;
import com.clougence.clouddm.console.web.service.governance.ProdReleaseService;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.DbPairDal;
import com.clougence.clouddm.platform.dal.access.ProdReleaseDal;
import com.clougence.clouddm.platform.dal.access.TicketDbStmtDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.dbpair.DmDbPairMapper;
import com.clougence.clouddm.platform.dal.mapper.dbpair.DmDbServiceMapper;
import com.clougence.clouddm.platform.dal.mapper.govticket.DmTicketDbStmtMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseStmtMapper;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.dbpair.DmDbPairDO;
import com.clougence.clouddm.platform.dal.model.dbpair.DmDbServiceDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseStmtDO;
import com.clougence.clouddm.sdk.security.auth.AuthKind;
import com.clougence.utils.JsonUtils;

/**
 * Tests for {@link GovOpenApiServiceImpl} — interfaces A, B, C.
 * <p>
 * Covers: dbName bidirectional pair resolution, DS auth filter, executedOnly/ticketType filter,
 * ticketId aggregation, tenant rejection (not-found semantics), release detail delegation.
 */
public class GovOpenApiServiceImplTest {

    private GovOpenApiServiceImpl   service;

    private DbPairDal              dbPairDal;
    private DmDbPairMapper          pairMapper;
    private DmDbServiceMapper       serviceMapper;
    private TicketDbStmtDal         ticketDbStmtDal;
    private DmTicketDbStmtMapper    stmtMapper;
    private ApprovalDal             approvalDal;
    private DmApprovalMapper        approvalMapper;
    private ProdReleaseDal          prodReleaseDal;
    private DmProdReleaseMapper      releaseMapper;
    private DmProdReleaseStmtMapper  releaseStmtMapper;
    private ProdReleaseService       prodReleaseService;
    private DmResAuthService         dmResAuthService;

    private static final String PUID = "puid-001";
    private static final String UID  = "uid-001";

    @Before
    public void setUp() {
        service = new GovOpenApiServiceImpl();

        dbPairDal = mock(DbPairDal.class);
        pairMapper = mock(DmDbPairMapper.class);
        serviceMapper = mock(DmDbServiceMapper.class);
        when(dbPairDal.pairMapper()).thenReturn(pairMapper);
        when(dbPairDal.serviceMapper()).thenReturn(serviceMapper);

        ticketDbStmtDal = mock(TicketDbStmtDal.class);
        stmtMapper = mock(DmTicketDbStmtMapper.class);
        when(ticketDbStmtDal.stmtMapper()).thenReturn(stmtMapper);

        approvalDal = mock(ApprovalDal.class);
        approvalMapper = mock(DmApprovalMapper.class);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);

        prodReleaseDal = mock(ProdReleaseDal.class);
        releaseMapper = mock(DmProdReleaseMapper.class);
        releaseStmtMapper = mock(DmProdReleaseStmtMapper.class);
        when(prodReleaseDal.releaseMapper()).thenReturn(releaseMapper);
        when(prodReleaseDal.stmtMapper()).thenReturn(releaseStmtMapper);

        prodReleaseService = mock(ProdReleaseService.class);
        dmResAuthService = mock(DmResAuthService.class);

        ReflectionTestUtils.setField(service, "dbPairDal", dbPairDal);
        ReflectionTestUtils.setField(service, "ticketDbStmtDal", ticketDbStmtDal);
        ReflectionTestUtils.setField(service, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(service, "prodReleaseDal", prodReleaseDal);
        ReflectionTestUtils.setField(service, "prodReleaseService", prodReleaseService);
        ReflectionTestUtils.setField(service, "dmResAuthService", dmResAuthService);
    }

    // ==================== Interface A: listTickets ====================

    @Test
    public void listTickets_bothSidesMatch_aggregatesByTicketId() {
        // pair: pre_db_name = "mydb", prod_db_name = "mydb" (both match)
        DmDbPairDO pair = new DmDbPairDO();
        pair.setId(1L);
        pair.setPreDsId(10L);
        pair.setPreDbName("mydb");
        pair.setProdDsId(20L);
        pair.setProdDbName("mydb");
        pair.setStatus("ENABLED");
        when(pairMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(pair));

        // auth: user has auth on both DS
        when(dmResAuthService.listResByUserContainAnyAuth(UID, AuthKind.DataSource)).thenReturn(Arrays.asList(10L, 20L));

        // stmts from pre side (dsId=10)
        DmTicketDbStmtDO stmt1 = buildStmt(101L, 100L, 1L, 10L, "mydb", "CREATE TABLE t(id INT);", "SUCCESS");
        when(stmtMapper.queryByDsAndDb(10L, "mydb")).thenReturn(List.of(stmt1));
        // stmts from prod side (dsId=20)
        DmTicketDbStmtDO stmt2 = buildStmt(102L, 100L, 1L, 20L, "mydb", "CREATE TABLE t(id INT);", "SUCCESS");
        when(stmtMapper.queryByDsAndDb(20L, "mydb")).thenReturn(List.of(stmt2));

        // ticket
        DmApprovalDO ticket = buildTicket(100L, PUID, ApprovalStatus.FINISHED, "PRE_DDL", 55L);
        when(approvalMapper.queryById(100L)).thenReturn(ticket);

        // service
        DmDbServiceDO svc = new DmDbServiceDO();
        svc.setId(55L);
        svc.setServiceName("order-service");
        when(serviceMapper.selectBatchIds(any())).thenReturn(List.of(svc));

        // no promotion
        when(releaseStmtMapper.queryBySourceStmtId(101L)).thenReturn(null);

        DbChangeTicketListFO fo = new DbChangeTicketListFO();
        fo.setDbName("mydb");
        fo.setPage(1);
        fo.setSize(20);

        List<OpenDbChangeTicketVO> result = service.listTickets(PUID, UID, fo);
        assertEquals(1, result.size());
        OpenDbChangeTicketVO vo = result.get(0);
        assertEquals(100L, vo.getTicketId());
        assertEquals("PRE_DDL", vo.getTicketType());
        assertEquals("order-service", vo.getServiceName());
        assertTrue(vo.getDbNames().contains("mydb"));
        assertEquals("EXECUTED", vo.getExecStatus());
        assertFalse(vo.isPromoted());
    }

    @Test
    public void listTickets_noPair_returnsEmpty() {
        when(pairMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());
        when(dmResAuthService.listResByUserContainAnyAuth(UID, AuthKind.DataSource)).thenReturn(List.of(10L));

        DbChangeTicketListFO fo = new DbChangeTicketListFO();
        fo.setDbName("nonexistent");

        List<OpenDbChangeTicketVO> result = service.listTickets(PUID, UID, fo);
        assertTrue(result.isEmpty());
    }

    @Test
    public void listTickets_dsAuthFilter_dropsUnauthorizedDs() {
        // pair has both pre (dsId=10) and prod (dsId=20)
        DmDbPairDO pair = new DmDbPairDO();
        pair.setPreDsId(10L);
        pair.setPreDbName("mydb");
        pair.setProdDsId(20L);
        pair.setProdDbName("mydb");
        pair.setStatus("ENABLED");
        when(pairMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(pair));

        // user only has auth on dsId=10, NOT dsId=20
        when(dmResAuthService.listResByUserContainAnyAuth(UID, AuthKind.DataSource)).thenReturn(List.of(10L));

        DmTicketDbStmtDO stmt1 = buildStmt(101L, 100L, 1L, 10L, "mydb", "CREATE TABLE t(id INT);", "SUCCESS");
        when(stmtMapper.queryByDsAndDb(10L, "mydb")).thenReturn(List.of(stmt1));
        // dsId=20 stmts exist but should be filtered out
        DmTicketDbStmtDO stmt2 = buildStmt(102L, 200L, 2L, 20L, "mydb", "CREATE TABLE u(id INT);", "SUCCESS");
        when(stmtMapper.queryByDsAndDb(20L, "mydb")).thenReturn(List.of(stmt2));

        DmApprovalDO ticket1 = buildTicket(100L, PUID, ApprovalStatus.FINISHED, "PRE_DDL", 55L);
        when(approvalMapper.queryById(100L)).thenReturn(ticket1);

        when(releaseStmtMapper.queryBySourceStmtId(101L)).thenReturn(null);
        DmDbServiceDO svc = new DmDbServiceDO();
        svc.setId(55L);
        svc.setServiceName("svc");
        when(serviceMapper.selectBatchIds(any())).thenReturn(List.of(svc));

        DbChangeTicketListFO fo = new DbChangeTicketListFO();
        fo.setDbName("mydb");

        List<OpenDbChangeTicketVO> result = service.listTickets(PUID, UID, fo);
        // Only ticket 100 (dsId=10) should be returned; ticket 200 (dsId=20) filtered out
        assertEquals(1, result.size());
        assertEquals(100L, result.get(0).getTicketId());
    }

    @Test
    public void listTickets_noDsAuth_returnsEmpty() {
        DmDbPairDO pair = new DmDbPairDO();
        pair.setPreDsId(10L);
        pair.setPreDbName("mydb");
        pair.setProdDsId(20L);
        pair.setProdDbName("mydb");
        pair.setStatus("ENABLED");
        when(pairMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(pair));

        when(dmResAuthService.listResByUserContainAnyAuth(UID, AuthKind.DataSource)).thenReturn(Collections.emptyList());

        DbChangeTicketListFO fo = new DbChangeTicketListFO();
        fo.setDbName("mydb");

        List<OpenDbChangeTicketVO> result = service.listTickets(PUID, UID, fo);
        assertTrue(result.isEmpty());
    }

    @Test
    public void listTickets_executedOnly_filterNonFinishedAndNonSuccess() {
        DmDbPairDO pair = new DmDbPairDO();
        pair.setPreDsId(10L);
        pair.setPreDbName("mydb");
        pair.setProdDsId(20L);
        pair.setProdDbName("mydb");
        pair.setStatus("ENABLED");
        when(pairMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(pair));
        when(dmResAuthService.listResByUserContainAnyAuth(UID, AuthKind.DataSource)).thenReturn(Arrays.asList(10L, 20L));

        // ticket 100: FINISHED + SUCCESS → passes
        DmTicketDbStmtDO stmt1 = buildStmt(101L, 100L, 1L, 10L, "mydb", "DDL;", "SUCCESS");
        when(stmtMapper.queryByDsAndDb(10L, "mydb")).thenReturn(List.of(stmt1));
        DmApprovalDO ticket1 = buildTicket(100L, PUID, ApprovalStatus.FINISHED, "PRE_DDL", 55L);
        when(approvalMapper.queryById(100L)).thenReturn(ticket1);

        // ticket 200: WAIT_APPROVAL + PENDING → filtered out by executedOnly
        DmTicketDbStmtDO stmt2 = buildStmt(102L, 200L, 2L, 20L, "mydb", "DDL;", "PENDING");
        when(stmtMapper.queryByDsAndDb(20L, "mydb")).thenReturn(List.of(stmt2));
        DmApprovalDO ticket2 = buildTicket(200L, PUID, ApprovalStatus.WAIT_APPROVAL, "PRE_DDL", 55L);
        when(approvalMapper.queryById(200L)).thenReturn(ticket2);

        when(releaseStmtMapper.queryBySourceStmtId(101L)).thenReturn(null);
        DmDbServiceDO svc = new DmDbServiceDO();
        svc.setId(55L);
        svc.setServiceName("svc");
        when(serviceMapper.selectBatchIds(any())).thenReturn(List.of(svc));

        DbChangeTicketListFO fo = new DbChangeTicketListFO();
        fo.setDbName("mydb");
        fo.setExecutedOnly(true);

        List<OpenDbChangeTicketVO> result = service.listTickets(PUID, UID, fo);
        assertEquals(1, result.size());
        assertEquals(100L, result.get(0).getTicketId());
    }

    @Test
    public void listTickets_ticketTypeFilter_filtersMismatched() {
        DmDbPairDO pair = new DmDbPairDO();
        pair.setPreDsId(10L);
        pair.setPreDbName("mydb");
        pair.setStatus("ENABLED");
        when(pairMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(pair));
        when(dmResAuthService.listResByUserContainAnyAuth(UID, AuthKind.DataSource)).thenReturn(List.of(10L));

        DmTicketDbStmtDO stmt = buildStmt(101L, 100L, 1L, 10L, "mydb", "DDL;", "SUCCESS");
        when(stmtMapper.queryByDsAndDb(10L, "mydb")).thenReturn(List.of(stmt));
        // ticket type = PROD_DML, but filter requests PRE_DDL → should be filtered out
        DmApprovalDO ticket = buildTicket(100L, PUID, ApprovalStatus.FINISHED, "PROD_DML", 55L);
        when(approvalMapper.queryById(100L)).thenReturn(ticket);

        DbChangeTicketListFO fo = new DbChangeTicketListFO();
        fo.setDbName("mydb");
        fo.setTicketType("PRE_DDL");

        List<OpenDbChangeTicketVO> result = service.listTickets(PUID, UID, fo);
        assertTrue(result.isEmpty());
    }

    @Test
    public void listTickets_pagination_returnsCorrectPage() {
        DmDbPairDO pair = new DmDbPairDO();
        pair.setPreDsId(10L);
        pair.setPreDbName("mydb");
        pair.setStatus("ENABLED");
        when(pairMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(pair));
        when(dmResAuthService.listResByUserContainAnyAuth(UID, AuthKind.DataSource)).thenReturn(List.of(10L));

        // 5 tickets, page 2 size 2 → 2 results (tickets 3 and 4)
        List<DmTicketDbStmtDO> allStmts = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            long ticketId = 100 + i;
            DmTicketDbStmtDO s = buildStmt(200 + i, ticketId, 1L, 10L, "mydb", "DDL;", "SUCCESS");
            allStmts.add(s);
            DmApprovalDO t = buildTicket(ticketId, PUID, ApprovalStatus.FINISHED, "PRE_DDL", null);
            when(approvalMapper.queryById(ticketId)).thenReturn(t);
            when(releaseStmtMapper.queryBySourceStmtId(200 + i)).thenReturn(null);
        }
        when(stmtMapper.queryByDsAndDb(10L, "mydb")).thenReturn(allStmts);

        DbChangeTicketListFO fo = new DbChangeTicketListFO();
        fo.setDbName("mydb");
        fo.setPage(2);
        fo.setSize(2);

        List<OpenDbChangeTicketVO> result = service.listTickets(PUID, UID, fo);
        assertEquals(2, result.size());
    }

    @Test
    public void listTickets_promoted_setsReleaseInfo() {
        DmDbPairDO pair = new DmDbPairDO();
        pair.setPreDsId(10L);
        pair.setPreDbName("mydb");
        pair.setStatus("ENABLED");
        when(pairMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(pair));
        when(dmResAuthService.listResByUserContainAnyAuth(UID, AuthKind.DataSource)).thenReturn(List.of(10L));

        DmTicketDbStmtDO stmt = buildStmt(101L, 100L, 1L, 10L, "mydb", "DDL;", "SUCCESS");
        when(stmtMapper.queryByDsAndDb(10L, "mydb")).thenReturn(List.of(stmt));
        DmApprovalDO ticket = buildTicket(100L, PUID, ApprovalStatus.FINISHED, "PRE_DDL", 55L);
        when(approvalMapper.queryById(100L)).thenReturn(ticket);

        DmProdReleaseStmtDO relStmt = new DmProdReleaseStmtDO();
        relStmt.setReleaseId(500L);
        when(releaseStmtMapper.queryBySourceStmtId(101L)).thenReturn(relStmt);

        DmProdReleaseDO release = new DmProdReleaseDO();
        release.setId(500L);
        release.setReleaseNo("REL-20260911-0001");
        release.setStatus("DONE");
        when(releaseMapper.queryById(500L)).thenReturn(release);

        DbChangeTicketListFO fo = new DbChangeTicketListFO();
        fo.setDbName("mydb");

        List<OpenDbChangeTicketVO> result = service.listTickets(PUID, UID, fo);
        assertEquals(1, result.size());
        OpenDbChangeTicketVO vo = result.get(0);
        assertTrue(vo.isPromoted());
        assertEquals(Long.valueOf(500L), vo.getReleaseId());
        assertEquals("REL-20260911-0001", vo.getReleaseNo());
        assertEquals("DONE", vo.getReleaseStatus());
    }

    // ==================== Interface B: ticketStatements ====================

    @Test
    public void ticketStatements_sameTenant_returnsGroups() {
        DmApprovalDO ticket = buildTicket(100L, PUID, ApprovalStatus.FINISHED, "PRE_DDL", 55L);
        when(approvalMapper.queryById(100L)).thenReturn(ticket);

        DmTicketDbStmtDO g1 = buildStmt(201L, 100L, 1L, 10L, "mydb", "CREATE TABLE t(id INT);", "SUCCESS");
        g1.setPrecheckResult("{\"checkStatus\":\"PASS\"}");
        when(stmtMapper.queryByTicketId(100L)).thenReturn(List.of(g1));

        DmDbServiceDO svc = new DmDbServiceDO();
        svc.setId(55L);
        svc.setServiceName("order-service");
        when(serviceMapper.selectById(55L)).thenReturn(svc);

        DbChangeTicketStmtFO fo = new DbChangeTicketStmtFO();
        fo.setTicketId(100L);

        OpenDbChangeTicketStmtVO result = service.ticketStatements(PUID, UID, fo);
        assertNotNull(result);
        assertEquals(100L, result.getTicketId());
        assertEquals("PRE_DDL", result.getTicketType());
        assertEquals("order-service", result.getServiceName());
        assertEquals(1, result.getGroups().size());
        assertEquals("{\"checkStatus\":\"PASS\"}", result.getGroups().get(0).getPrecheckResult());
    }

    @Test
    public void ticketStatements_crossTenant_returnsNull() {
        DmApprovalDO ticket = buildTicket(100L, "other-puid", ApprovalStatus.FINISHED, "PRE_DDL", 55L);
        when(approvalMapper.queryById(100L)).thenReturn(ticket);

        DbChangeTicketStmtFO fo = new DbChangeTicketStmtFO();
        fo.setTicketId(100L);

        OpenDbChangeTicketStmtVO result = service.ticketStatements(PUID, UID, fo);
        assertNull(result); // not-found semantics — existence not leaked
    }

    @Test
    public void ticketStatements_ticketNotFound_returnsNull() {
        when(approvalMapper.queryById(999L)).thenReturn(null);

        DbChangeTicketStmtFO fo = new DbChangeTicketStmtFO();
        fo.setTicketId(999L);

        OpenDbChangeTicketStmtVO result = service.ticketStatements(PUID, UID, fo);
        assertNull(result);
    }

    // ==================== Interface C: releaseDetail ====================

    @Test
    public void releaseDetail_sameTenant_delegatesToService() {
        DmProdReleaseDO release = new DmProdReleaseDO();
        release.setId(500L);
        release.setPrimaryUid(PUID);
        when(releaseMapper.queryById(500L)).thenReturn(release);

        ProdReleaseDetailVO detail = new ProdReleaseDetailVO();
        detail.setId(500L);
        detail.setReleaseNo("REL-001");
        when(prodReleaseService.getDetail(PUID, 500L)).thenReturn(detail);

        DbChangeReleaseDetailFO fo = new DbChangeReleaseDetailFO();
        fo.setReleaseId(500L);

        ProdReleaseDetailVO result = service.releaseDetail(PUID, UID, fo);
        assertNotNull(result);
        assertEquals(Long.valueOf(500L), result.getId());
        assertEquals("REL-001", result.getReleaseNo());
    }

    @Test
    public void releaseDetail_crossTenant_returnsNull() {
        DmProdReleaseDO release = new DmProdReleaseDO();
        release.setId(500L);
        release.setPrimaryUid("other-puid");
        when(releaseMapper.queryById(500L)).thenReturn(release);

        DbChangeReleaseDetailFO fo = new DbChangeReleaseDetailFO();
        fo.setReleaseId(500L);

        ProdReleaseDetailVO result = service.releaseDetail(PUID, UID, fo);
        assertNull(result); // not-found semantics — existence not leaked
    }

    @Test
    public void releaseDetail_notFound_returnsNull() {
        when(releaseMapper.queryById(999L)).thenReturn(null);

        DbChangeReleaseDetailFO fo = new DbChangeReleaseDetailFO();
        fo.setReleaseId(999L);

        ProdReleaseDetailVO result = service.releaseDetail(PUID, UID, fo);
        assertNull(result);
    }

    // ==================== Helpers ====================

    private DmTicketDbStmtDO buildStmt(long id, long ticketId, long pairId, long dsId, String dbName, String sql, String execStatus) {
        DmTicketDbStmtDO s = new DmTicketDbStmtDO();
        s.setId(id);
        s.setTicketId(ticketId);
        s.setPairId(pairId);
        s.setDsId(dsId);
        s.setDbName(dbName);
        s.setSqlContent(sql);
        s.setExecStatus(execStatus);
        s.setGmtModified(new Date());
        return s;
    }

    private DmApprovalDO buildTicket(long id, String puid, ApprovalStatus status, String ticketType, Long serviceId) {
        DmApprovalDO t = new DmApprovalDO();
        t.setId(id);
        t.setPrimaryUid(puid);
        t.setOwnerUid(UID);
        t.setTicketTitle("ticket-" + id);
        t.setApproBiz(ApprovalBiz.DM_CHANGE);
        t.setTicketStatus(status);
        ApprovalMO mo = new ApprovalMO();
        mo.setTicketType(ticketType);
        mo.setServiceId(serviceId);
        t.setTicketInfo(JsonUtils.toJson(mo));
        return t;
    }
}
