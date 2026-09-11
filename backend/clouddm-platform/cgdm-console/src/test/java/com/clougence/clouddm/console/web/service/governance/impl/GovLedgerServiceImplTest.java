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
import com.clougence.clouddm.console.web.model.fo.prodrelease.GovLedgerListByDbFO;
import com.clougence.clouddm.console.web.model.vo.prodrelease.GovLedgerTicketVO;
import com.clougence.clouddm.console.web.service.dbpair.DbPairService;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.DbPairDal;
import com.clougence.clouddm.platform.dal.access.ProdReleaseDal;
import com.clougence.clouddm.platform.dal.access.TicketDbStmtDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.dbpair.DmDbServiceMapper;
import com.clougence.clouddm.platform.dal.mapper.govticket.DmTicketDbStmtMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseStmtMapper;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.dbpair.DmDbServiceDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;
import com.clougence.utils.JsonUtils;

/**
 * Tests for GovLedgerServiceImpl.listByDb serviceName batch-fill:
 * a) positive case — serviceId resolved via batch query → serviceName populated
 * b) serviceId missing (null in ticketInfo) → serviceName stays null, no batch query issued
 */
public class GovLedgerServiceImplTest {

    private GovLedgerServiceImpl  service;

    private TicketDbStmtDal       ticketDbStmtDal;
    private DmTicketDbStmtMapper  ticketStmtMapper;
    private ApprovalDal           approvalDal;
    private DmApprovalMapper      approvalMapper;
    private ProdReleaseDal        prodReleaseDal;
    private DmProdReleaseStmtMapper releaseStmtMapper;
    private DbPairDal             dbPairDal;
    private DmDbServiceMapper      serviceMapper;

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
        when(prodReleaseDal.stmtMapper()).thenReturn(releaseStmtMapper);

        dbPairDal = mock(DbPairDal.class);
        serviceMapper = mock(DmDbServiceMapper.class);
        when(dbPairDal.serviceMapper()).thenReturn(serviceMapper);

        // DbPairService only used by dbs() — not exercised in listByDb tests
        DbPairService dbPairService = mock(DbPairService.class);

        ReflectionTestUtils.setField(service, "dbPairService", dbPairService);
        ReflectionTestUtils.setField(service, "ticketDbStmtDal", ticketDbStmtDal);
        ReflectionTestUtils.setField(service, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(service, "prodReleaseDal", prodReleaseDal);
        ReflectionTestUtils.setField(service, "dbPairDal", dbPairDal);
    }

    // ==================== serviceName batch-fill: positive case ====================

    @Test
    public void listByDb_fillsServiceName_fromBatchQuery() {
        long ticketId = 100L;
        long stmtId = 200L;
        long serviceId = 5L;

        DmTicketDbStmtDO stmt = buildStmt(stmtId, ticketId, "SUCCESS");
        when(ticketStmtMapper.queryByDsAndDb(10L, "pre_db")).thenReturn(List.of(stmt));

        DmApprovalDO ticket = buildTicket(ticketId, ApprovalStatus.FINISHED, "PRE_DDL", serviceId);
        when(approvalMapper.queryById(ticketId)).thenReturn(ticket);

        // Not promoted
        when(releaseStmtMapper.queryBySourceStmtId(stmtId)).thenReturn(null);

        // Batch query returns the service
        DmDbServiceDO svc = new DmDbServiceDO();
        svc.setId(serviceId);
        svc.setServiceName("Order Service");
        when(serviceMapper.selectBatchIds(anyCollection())).thenReturn(List.of(svc));

        GovLedgerListByDbFO fo = new GovLedgerListByDbFO();
        fo.setDsId(10L);
        fo.setDbName("pre_db");

        List<GovLedgerTicketVO> result = service.listByDb(PUID, fo);

        assertEquals(1, result.size());
        GovLedgerTicketVO vo = result.get(0);
        assertEquals(ticketId, vo.getTicketId());
        assertEquals("Order Service", vo.getServiceName());
        assertFalse(vo.isPromoted());

        // Verify batch query was issued exactly once (no N+1)
        verify(serviceMapper, times(1)).selectBatchIds(anyCollection());
    }

    // ==================== serviceName batch-fill: two tickets, one batch query ====================

    @Test
    public void listByDb_twoTickets_oneBatchQuery() {
        long stmtId1 = 201L;
        long stmtId2 = 202L;
        long serviceId1 = 5L;
        long serviceId2 = 6L;

        DmTicketDbStmtDO stmt1 = buildStmt(stmtId1, 101L, "SUCCESS");
        DmTicketDbStmtDO stmt2 = buildStmt(stmtId2, 102L, "SUCCESS");
        when(ticketStmtMapper.queryByDsAndDb(10L, "pre_db")).thenReturn(Arrays.asList(stmt1, stmt2));

        DmApprovalDO ticket1 = buildTicket(101L, ApprovalStatus.FINISHED, "PRE_DDL", serviceId1);
        DmApprovalDO ticket2 = buildTicket(102L, ApprovalStatus.FINISHED, "PRE_DDL", serviceId2);
        when(approvalMapper.queryById(101L)).thenReturn(ticket1);
        when(approvalMapper.queryById(102L)).thenReturn(ticket2);

        when(releaseStmtMapper.queryBySourceStmtId(stmtId1)).thenReturn(null);
        when(releaseStmtMapper.queryBySourceStmtId(stmtId2)).thenReturn(null);

        DmDbServiceDO svc1 = new DmDbServiceDO();
        svc1.setId(serviceId1);
        svc1.setServiceName("Order Service");
        DmDbServiceDO svc2 = new DmDbServiceDO();
        svc2.setId(serviceId2);
        svc2.setServiceName("Payment Service");
        when(serviceMapper.selectBatchIds(anyCollection())).thenReturn(Arrays.asList(svc1, svc2));

        GovLedgerListByDbFO fo = new GovLedgerListByDbFO();
        fo.setDsId(10L);
        fo.setDbName("pre_db");

        List<GovLedgerTicketVO> result = service.listByDb(PUID, fo);

        assertEquals(2, result.size());
        assertEquals("Order Service", result.get(0).getServiceName());
        assertEquals("Payment Service", result.get(1).getServiceName());

        // Exactly one batch query for both tickets — no N+1
        verify(serviceMapper, times(1)).selectBatchIds(anyCollection());
    }

    // ==================== serviceName batch-fill: serviceId missing → null tolerant ====================

    @Test
    public void listByDb_serviceIdMissing_serviceNameStaysNull() {
        long ticketId = 103L;
        long stmtId = 203L;

        DmTicketDbStmtDO stmt = buildStmt(stmtId, ticketId, "SUCCESS");
        when(ticketStmtMapper.queryByDsAndDb(10L, "pre_db")).thenReturn(List.of(stmt));

        // serviceId not set in ApprovalMO (null)
        DmApprovalDO ticket = buildTicket(ticketId, ApprovalStatus.FINISHED, "PRE_DDL", null);
        when(approvalMapper.queryById(ticketId)).thenReturn(ticket);

        when(releaseStmtMapper.queryBySourceStmtId(stmtId)).thenReturn(null);

        GovLedgerListByDbFO fo = new GovLedgerListByDbFO();
        fo.setDsId(10L);
        fo.setDbName("pre_db");

        List<GovLedgerTicketVO> result = service.listByDb(PUID, fo);

        assertEquals(1, result.size());
        GovLedgerTicketVO vo = result.get(0);
        assertEquals(ticketId, vo.getTicketId());
        assertNull(vo.getServiceName());

        // No batch query should be issued when all serviceIds are null
        verify(serviceMapper, never()).selectBatchIds(anyCollection());
    }

    // ==================== serviceName batch-fill: serviceId present but not found in dm_db_service ====================

    @Test
    public void listByDb_serviceIdNotFound_serviceNameStaysNull() {
        long ticketId = 104L;
        long stmtId = 204L;
        long serviceId = 99L;

        DmTicketDbStmtDO stmt = buildStmt(stmtId, ticketId, "SUCCESS");
        when(ticketStmtMapper.queryByDsAndDb(10L, "pre_db")).thenReturn(List.of(stmt));

        DmApprovalDO ticket = buildTicket(ticketId, ApprovalStatus.FINISHED, "PRE_DDL", serviceId);
        when(approvalMapper.queryById(ticketId)).thenReturn(ticket);

        when(releaseStmtMapper.queryBySourceStmtId(stmtId)).thenReturn(null);

        // Batch query returns empty (service was deleted)
        when(serviceMapper.selectBatchIds(anyCollection())).thenReturn(Collections.emptyList());

        GovLedgerListByDbFO fo = new GovLedgerListByDbFO();
        fo.setDsId(10L);
        fo.setDbName("pre_db");

        List<GovLedgerTicketVO> result = service.listByDb(PUID, fo);

        assertEquals(1, result.size());
        assertNull(result.get(0).getServiceName());
        verify(serviceMapper, times(1)).selectBatchIds(anyCollection());
    }

    // ==================== helpers ====================

    private DmTicketDbStmtDO buildStmt(long id, long ticketId, String execStatus) {
        DmTicketDbStmtDO stmt = new DmTicketDbStmtDO();
        stmt.setId(id);
        stmt.setTicketId(ticketId);
        stmt.setDsId(10L);
        stmt.setDbName("pre_db");
        stmt.setSqlContent("CREATE TABLE t(id int);");
        stmt.setExecStatus(execStatus);
        return stmt;
    }

    private DmApprovalDO buildTicket(long id, ApprovalStatus status, String ticketType, Long serviceId) {
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
        ticket.setTicketInfo(JsonUtils.toJson(mo));
        return ticket;
    }
}
