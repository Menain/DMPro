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

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.console.web.component.approval.ApprovalStateService;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.component.auth.DmAuthServiceForBiz;
import com.clougence.clouddm.console.web.component.execute.AutoExecService;
import com.clougence.clouddm.console.web.component.governance.GovSqlHashUtils;
import com.clougence.clouddm.console.web.component.governance.ProdReleaseStateMachine;
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
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO;
import com.clougence.clouddm.platform.dal.model.dbpair.DmDbPairDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseStmtDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.ProdReleaseStatus;

/**
 * Tests for ProdReleaseServiceImpl covering:
 * a) createRelease four-condition rejection branches + library lock
 * c) startExecution hash drift rejection + aggregation
 * d) handleRejected/handleCancelled delete rows (UK release)
 * e) retryReleaseStmt acceptance condition matrix
 */
public class ProdReleaseServiceImplTest {

    private ProdReleaseServiceImpl  service;
    private ProdReleaseDal           prodReleaseDal;
    private DmProdReleaseMapper      releaseMapper;
    private DmProdReleaseStmtMapper  stmtMapper;
    private TicketDbStmtDal          ticketDbStmtDal;
    private DmTicketDbStmtMapper     ticketStmtMapper;
    private ApprovalDal              approvalDal;
    private DmApprovalMapper         approvalMapper;
    private DbPairDal               dbPairDal;
    private DmDbPairMapper           pairMapper;
    private DataSourceDal            dsDal;
    private DmDsMapper               dsMapper;
    private ProdReleaseStateMachine  releaseStateMachine;
    private DmAuthServiceForBiz      dmAuthServiceForBiz;
    private AutoExecService          autoExecService;
    private ApprovalControlService   approvalControlService;
    private ApprovalStateService     approvalStateService;
    private DbChangeEventDal       dbChangeEventDal;
    private DmDbChangeEventMapper    eventMapper;

    private static final String PUID = "puid-001";
    private static final String UID  = "uid-001";

    @Before
    public void setUp() {
        service = new ProdReleaseServiceImpl();

        // Set up DmTeamUtils static executionDal (required by nextExecJobBizId/nextExecTaskBizId)
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

        prodReleaseDal = mock(ProdReleaseDal.class);
        releaseMapper = mock(DmProdReleaseMapper.class);
        stmtMapper = mock(DmProdReleaseStmtMapper.class);
        when(prodReleaseDal.releaseMapper()).thenReturn(releaseMapper);
        when(prodReleaseDal.stmtMapper()).thenReturn(stmtMapper);

        ticketDbStmtDal = mock(TicketDbStmtDal.class);
        ticketStmtMapper = mock(DmTicketDbStmtMapper.class);
        when(ticketDbStmtDal.stmtMapper()).thenReturn(ticketStmtMapper);

        approvalDal = mock(ApprovalDal.class);
        approvalMapper = mock(DmApprovalMapper.class);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);

        dbPairDal = mock(DbPairDal.class);
        pairMapper = mock(DmDbPairMapper.class);
        when(dbPairDal.pairMapper()).thenReturn(pairMapper);

        dsDal = mock(DataSourceDal.class);
        dsMapper = mock(DmDsMapper.class);
        when(dsDal.dsMapper()).thenReturn(dsMapper);

        releaseStateMachine = mock(ProdReleaseStateMachine.class);
        dmAuthServiceForBiz = mock(DmAuthServiceForBiz.class);
        autoExecService = mock(AutoExecService.class);
        approvalControlService = mock(ApprovalControlService.class);
        approvalStateService = mock(ApprovalStateService.class);

        dbChangeEventDal = mock(DbChangeEventDal.class);
        eventMapper = mock(DmDbChangeEventMapper.class);
        when(dbChangeEventDal.eventMapper()).thenReturn(eventMapper);
        when(eventMapper.insert(any(DmDbChangeEventDO.class))).thenReturn(1);

        // execDal for retryReleaseStmt's active-job check
        com.clougence.clouddm.platform.dal.access.ExecutionDal execDal
            = mock(com.clougence.clouddm.platform.dal.access.ExecutionDal.class);
        when(execDal.autoJobMapper()).thenReturn(staticJobMapper);

        ReflectionTestUtils.setField(service, "prodReleaseDal", prodReleaseDal);
        ReflectionTestUtils.setField(service, "dbChangeEventDal", dbChangeEventDal);
        ReflectionTestUtils.setField(service, "ticketDbStmtDal", ticketDbStmtDal);
        ReflectionTestUtils.setField(service, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(service, "dsDal", dsDal);
        ReflectionTestUtils.setField(service, "dbPairDal", dbPairDal);
        ReflectionTestUtils.setField(service, "approvalControlService", approvalControlService);
        ReflectionTestUtils.setField(service, "releaseStateMachine", releaseStateMachine);
        ReflectionTestUtils.setField(service, "dmAuthServiceForBiz", dmAuthServiceForBiz);
        ReflectionTestUtils.setField(service, "autoExecService", autoExecService);
        ReflectionTestUtils.setField(service, "approvalStateService", approvalStateService);
        ReflectionTestUtils.setField(service, "execDal", execDal);
    }

    // ==================== createRelease four-condition rejection branches ====================

    @Test
    public void createRelease_ticketNotFinished_throws() {
        DmApprovalDO ticket = buildTicket(100L, ApprovalStatus.WAIT_APPROVAL, "PRE_DDL");
        when(approvalMapper.queryById(100L)).thenReturn(ticket);

        ProdReleaseCreateFO fo = new ProdReleaseCreateFO();
        fo.setTicketIds(List.of(100L));

        try {
            service.createRelease(PUID, UID, fo);
            fail("Expected ErrorMessageException");
        } catch (ErrorMessageException ex) {
            assertTrue(ex.getMessage().contains("100"));
        }
    }

    @Test
    public void createRelease_ticketNotPreDdl_throws() {
        DmApprovalDO ticket = buildTicket(101L, ApprovalStatus.FINISHED, "PROD_DML");
        when(approvalMapper.queryById(101L)).thenReturn(ticket);

        ProdReleaseCreateFO fo = new ProdReleaseCreateFO();
        fo.setTicketIds(List.of(101L));

        try {
            service.createRelease(PUID, UID, fo);
            fail("Expected ErrorMessageException");
        } catch (ErrorMessageException ex) {
            assertTrue(ex.getMessage().contains("101"));
        }
    }

    @Test
    public void createRelease_stmtNotSuccess_throws() {
        long ticketId = 102L;
        DmApprovalDO ticket = buildTicket(ticketId, ApprovalStatus.FINISHED, "PRE_DDL");
        when(approvalMapper.queryById(ticketId)).thenReturn(ticket);

        DmTicketDbStmtDO stmt = buildTicketStmt(200L, ticketId, "EXECUTING");
        when(ticketStmtMapper.queryByTicketId(ticketId)).thenReturn(List.of(stmt));

        ProdReleaseCreateFO fo = new ProdReleaseCreateFO();
        fo.setTicketIds(List.of(ticketId));

        try {
            service.createRelease(PUID, UID, fo);
            fail("Expected ErrorMessageException");
        } catch (ErrorMessageException ex) {
            assertTrue(ex.getMessage().contains("102"));
        }
    }

    @Test
    public void createRelease_stmtAlreadyMerged_throws() {
        long ticketId = 103L;
        DmApprovalDO ticket = buildTicket(ticketId, ApprovalStatus.FINISHED, "PRE_DDL");
        when(approvalMapper.queryById(ticketId)).thenReturn(ticket);

        DmTicketDbStmtDO stmt = buildTicketStmt(201L, ticketId, "SUCCESS");
        when(ticketStmtMapper.queryByTicketId(ticketId)).thenReturn(List.of(stmt));

        DmProdReleaseStmtDO existing = new DmProdReleaseStmtDO();
        existing.setReleaseId(999L);
        when(stmtMapper.queryBySourceStmtId(201L)).thenReturn(existing);

        ProdReleaseCreateFO fo = new ProdReleaseCreateFO();
        fo.setTicketIds(List.of(ticketId));

        try {
            service.createRelease(PUID, UID, fo);
            fail("Expected ErrorMessageException");
        } catch (ErrorMessageException ex) {
            assertTrue(ex.getMessage().contains("103"));
        }
    }

    @Test
    public void createRelease_libraryLocked_throws() {
        long ticketId = 104L;
        DmApprovalDO ticket = buildTicket(ticketId, ApprovalStatus.FINISHED, "PRE_DDL");
        when(approvalMapper.queryById(ticketId)).thenReturn(ticket);

        DmTicketDbStmtDO stmt = buildTicketStmt(202L, ticketId, "SUCCESS");
        when(ticketStmtMapper.queryByTicketId(ticketId)).thenReturn(List.of(stmt));

        DmDbPairDO pair = buildPair(300L, 10L, "prod_db");
        when(pairMapper.selectById(300L)).thenReturn(pair);
        when(stmtMapper.queryBySourceStmtId(202L)).thenReturn(null);

        com.clougence.clouddm.platform.dal.model.datasource.DmDsDO prodDs
            = mock(com.clougence.clouddm.platform.dal.model.datasource.DmDsDO.class);
        when(prodDs.getInstanceId()).thenReturn("inst-1");
        when(dsMapper.queryDsIdentityById(10L)).thenReturn(prodDs);
        when(releaseMapper.countActiveByProdDb(10L, "prod_db")).thenReturn(1);

        ProdReleaseCreateFO fo = new ProdReleaseCreateFO();
        fo.setTicketIds(List.of(ticketId));

        try {
            service.createRelease(PUID, UID, fo);
            fail("Expected ErrorMessageException");
        } catch (ErrorMessageException ex) {
            assertTrue(ex.getMessage().contains("prod_db"));
        }
    }

    @Test
    public void createRelease_emptyTicketList_throws() {
        ProdReleaseCreateFO fo = new ProdReleaseCreateFO();
        fo.setTicketIds(Collections.emptyList());
        try {
            service.createRelease(PUID, UID, fo);
            fail("Expected ErrorMessageException");
        } catch (ErrorMessageException ex) {
            // ok
        }
    }

    // ==================== startExecution hash drift rejection ====================

    @Test
    public void startExecution_hashDrift_marksStmtFailedAndAggregates() {
        long releaseId = 500L;
        DmProdReleaseDO release = buildRelease(releaseId, ProdReleaseStatus.APPROVED);
        when(releaseMapper.queryById(releaseId)).thenReturn(release);
        when(releaseStateMachine.transit(eq(releaseId), anySet(), eq(ProdReleaseStatus.EXECUTING))).thenReturn(true);

        // Stmt with a wrong hash (drift)
        DmProdReleaseStmtDO stmt = new DmProdReleaseStmtDO();
        stmt.setId(600L);
        stmt.setReleaseId(releaseId);
        stmt.setProdDsId(10L);
        stmt.setProdDbName("prod_db");
        stmt.setSeq(1);
        stmt.setSqlContent("CREATE TABLE t1(id int);");
        stmt.setHash("WRONG_HASH_NOT_MATCHING");
        stmt.setExecStatus("PENDING");

        when(stmtMapper.queryByReleaseId(releaseId)).thenReturn(List.of(stmt));
        when(stmtMapper.firstPendingStmt(releaseId, 10L, "prod_db")).thenReturn(stmt);

        // After drift, aggregation: all terminal (only 1 stmt, now FAILED)
        DmProdReleaseStmtDO failedStmt = new DmProdReleaseStmtDO();
        failedStmt.setId(600L);
        failedStmt.setExecStatus("FAILED");

        DmApprovalDO approval = mock(DmApprovalDO.class);
        when(approval.getBizId()).thenReturn("biz-500");
        when(approvalMapper.queryById(anyLong())).thenReturn(approval);

        // queryByReleaseId called twice: once for grouping, once for aggregation
        when(stmtMapper.queryByReleaseId(releaseId)).thenReturn(List.of(stmt), List.of(failedStmt));

        service.startExecution(releaseId, UID);

        // Verify stmt marked FAILED
        verify(stmtMapper).updateExecStatus(eq(600L), eq("FAILED"), anyString());
        // Verify hash drift event
        verify(eventMapper).insert(argThat((DmDbChangeEventDO e) ->
            e != null && e.getEventType() != null && e.getEventType().contains("HASH_DRIFT")));
        // Verify aggregation: transit to PARTIAL_FAILED
        verify(releaseStateMachine).transit(eq(releaseId),
            eq(Set.of(ProdReleaseStatus.EXECUTING)), eq(ProdReleaseStatus.PARTIAL_FAILED));
        // Verify no job created
        verify(autoExecService, never()).createReleaseStmtJob(any(), anyString(), anyBoolean(), any(), anyString(), anyString());
    }

    @Test
    public void startExecution_notApproved_throws() {
        long releaseId = 501L;
        DmProdReleaseDO release = buildRelease(releaseId, ProdReleaseStatus.EXECUTING);
        when(releaseMapper.queryById(releaseId)).thenReturn(release);
        when(releaseStateMachine.transit(eq(releaseId), anySet(), eq(ProdReleaseStatus.EXECUTING))).thenReturn(false);

        try {
            service.startExecution(releaseId, UID);
            fail("Expected ErrorMessageException");
        } catch (ErrorMessageException ex) {
            // ok
        }
    }

    // ==================== handleRejected / handleCancelled delete rows ====================

    @Test
    public void handleRejected_deletesStmtRowsAndReleasesUk() {
        long releaseId = 700L;
        DmProdReleaseDO release = buildRelease(releaseId, ProdReleaseStatus.APPROVING);
        when(releaseMapper.queryById(releaseId)).thenReturn(release);
        when(releaseStateMachine.transit(eq(releaseId),
            eq(Set.of(ProdReleaseStatus.APPROVING, ProdReleaseStatus.APPROVED)),
            eq(ProdReleaseStatus.REJECTED))).thenReturn(true);

        service.handleRejected(releaseId);

        verify(stmtMapper).deleteByReleaseId(releaseId);
        verify(eventMapper).insert(argThat((DmDbChangeEventDO e) ->
            e != null && e.getEventType() != null && e.getEventType().contains("REJECTED")));
    }

    @Test
    public void handleRejected_executingRelease_transitFails_noDelete() {
        long releaseId = 701L;
        DmProdReleaseDO release = buildRelease(releaseId, ProdReleaseStatus.EXECUTING);
        when(releaseMapper.queryById(releaseId)).thenReturn(release);
        when(releaseStateMachine.transit(eq(releaseId),
            eq(Set.of(ProdReleaseStatus.APPROVING, ProdReleaseStatus.APPROVED)),
            eq(ProdReleaseStatus.REJECTED))).thenReturn(false);

        service.handleRejected(releaseId);

        verify(stmtMapper, never()).deleteByReleaseId(anyLong());
    }

    @Test
    public void handleCancelled_deletesStmtRows() {
        long releaseId = 702L;
        DmProdReleaseDO release = buildRelease(releaseId, ProdReleaseStatus.APPROVING);
        when(releaseMapper.queryById(releaseId)).thenReturn(release);
        when(releaseStateMachine.transit(eq(releaseId),
            eq(Set.of(ProdReleaseStatus.APPROVING, ProdReleaseStatus.APPROVED)),
            eq(ProdReleaseStatus.CANCELLED))).thenReturn(true);

        service.handleCancelled(releaseId);

        verify(stmtMapper).deleteByReleaseId(releaseId);
        verify(eventMapper).insert(argThat((DmDbChangeEventDO e) ->
            e != null && e.getEventType() != null && e.getEventType().contains("CANCELLED")));
    }

    // ==================== handleApproved event ====================

    @Test
    public void handleApproved_transitsAndAppendsEvent() {
        long releaseId = 800L;
        when(releaseStateMachine.transit(eq(releaseId),
            eq(Set.of(ProdReleaseStatus.APPROVING)),
            eq(ProdReleaseStatus.APPROVED))).thenReturn(true);

        service.handleApproved(releaseId);

        verify(eventMapper).insert(argThat((DmDbChangeEventDO e) ->
            e != null && e.getEventType() != null && e.getEventType().contains("APPROVED")));
    }

    // ==================== retryReleaseStmt acceptance matrix ====================

    @Test
    public void retryReleaseStmt_successStmt_throws() {
        long stmtId = 900L;
        DmProdReleaseStmtDO stmt = buildReleaseStmt(stmtId, 910L, "SUCCESS");
        when(stmtMapper.queryById(stmtId)).thenReturn(stmt);

        DmProdReleaseDO release = buildRelease(910L, ProdReleaseStatus.EXECUTING);
        release.setPrimaryUid(PUID);
        when(releaseMapper.queryById(910L)).thenReturn(release);

        com.clougence.clouddm.platform.dal.model.datasource.DmDsDO prodDs
            = mock(com.clougence.clouddm.platform.dal.model.datasource.DmDsDO.class);
        when(prodDs.getInstanceId()).thenReturn("inst-1");
        when(dsMapper.queryDsIdentityById(anyLong())).thenReturn(prodDs);

        try {
            service.retryReleaseStmt(PUID, UID, stmtId);
            fail("Expected ErrorMessageException");
        } catch (ErrorMessageException ex) {
            assertTrue(ex.getMessage().contains("SUCCESS"));
        }
    }

    @Test
    public void retryReleaseStmt_wrongTenant_throws() {
        long stmtId = 901L;
        DmProdReleaseStmtDO stmt = buildReleaseStmt(stmtId, 911L, "FAILED");
        when(stmtMapper.queryById(stmtId)).thenReturn(stmt);

        DmProdReleaseDO release = buildRelease(911L, ProdReleaseStatus.PARTIAL_FAILED);
        release.setPrimaryUid("other-tenant");
        when(releaseMapper.queryById(911L)).thenReturn(release);

        try {
            service.retryReleaseStmt(PUID, UID, stmtId);
            fail("Expected ErrorMessageException");
        } catch (ErrorMessageException ex) {
            // ok
        }
    }

    @Test
    public void retryReleaseStmt_releaseNotRetryable_throws() {
        long stmtId = 902L;
        DmProdReleaseStmtDO stmt = buildReleaseStmt(stmtId, 912L, "FAILED");
        when(stmtMapper.queryById(stmtId)).thenReturn(stmt);

        DmProdReleaseDO release = buildRelease(912L, ProdReleaseStatus.APPROVING);
        release.setPrimaryUid(PUID);
        when(releaseMapper.queryById(912L)).thenReturn(release);

        com.clougence.clouddm.platform.dal.model.datasource.DmDsDO prodDs
            = mock(com.clougence.clouddm.platform.dal.model.datasource.DmDsDO.class);
        when(prodDs.getInstanceId()).thenReturn("inst-1");
        when(dsMapper.queryDsIdentityById(anyLong())).thenReturn(prodDs);

        try {
            service.retryReleaseStmt(PUID, UID, stmtId);
            fail("Expected ErrorMessageException");
        } catch (ErrorMessageException ex) {
            // ok
        }
    }

    @Test
    public void retryReleaseStmt_failedStmt_executingRelease_accepted() {
        long stmtId = 903L;
        long releaseId = 913L;
        DmProdReleaseStmtDO stmt = buildReleaseStmt(stmtId, releaseId, "FAILED");
        when(stmtMapper.queryById(stmtId)).thenReturn(stmt);

        DmProdReleaseDO release = buildRelease(releaseId, ProdReleaseStatus.EXECUTING);
        release.setPrimaryUid(PUID);
        when(releaseMapper.queryById(releaseId)).thenReturn(release);

        com.clougence.clouddm.platform.dal.model.datasource.DmDsDO prodDs
            = mock(com.clougence.clouddm.platform.dal.model.datasource.DmDsDO.class);
        when(prodDs.getInstanceId()).thenReturn("inst-1");
        when(dsMapper.queryDsIdentityById(anyLong())).thenReturn(prodDs);

        service.retryReleaseStmt(PUID, UID, stmtId);

        verify(stmtMapper).updateExecStatus(eq(stmtId), eq("PENDING"), isNull());
        verify(autoExecService).createReleaseStmtJob(eq(stmt), anyString(), anyBoolean(), any(), anyString(), eq(UID));
        verify(autoExecService).startJob(anyString(), eq(UID));
    }

    // ==================== helpers ====================

    private DmApprovalDO buildTicket(long id, ApprovalStatus status, String ticketType) {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(id);
        ticket.setBizId("biz-" + id);
        ticket.setTicketStatus(status);
        ticket.setPrimaryUid(PUID);
        ticket.setOwnerUid(UID);
        ticket.setGmtCreate(new Date());
        ApprovalMO mo = new ApprovalMO();
        mo.setTicketType(ticketType);
        ticket.setTicketInfo(com.clougence.utils.JsonUtils.toJson(mo));
        return ticket;
    }

    private DmTicketDbStmtDO buildTicketStmt(long id, long ticketId, String execStatus) {
        DmTicketDbStmtDO stmt = new DmTicketDbStmtDO();
        stmt.setId(id);
        stmt.setTicketId(ticketId);
        stmt.setPairId(300L);
        stmt.setDsId(20L);
        stmt.setDbName("pre_db");
        stmt.setSqlContent("CREATE TABLE t(id int);");
        stmt.setExecStatus(execStatus);
        return stmt;
    }

    private DmDbPairDO buildPair(long id, long prodDsId, String prodDbName) {
        DmDbPairDO pair = new DmDbPairDO();
        pair.setId(id);
        pair.setProdDsId(prodDsId);
        pair.setProdDbName(prodDbName);
        pair.setStatus("ENABLED");
        return pair;
    }

    private DmProdReleaseDO buildRelease(long id, ProdReleaseStatus status) {
        DmProdReleaseDO release = new DmProdReleaseDO();
        release.setId(id);
        release.setReleaseNo("REL-" + id);
        release.setStatus(status.name());
        release.setPrimaryUid(PUID);
        release.setCreatorUid(UID);
        release.setApprovalId(0L);
        return release;
    }

    private DmProdReleaseStmtDO buildReleaseStmt(long id, long releaseId, String execStatus) {
        DmProdReleaseStmtDO stmt = new DmProdReleaseStmtDO();
        stmt.setId(id);
        stmt.setReleaseId(releaseId);
        stmt.setProdDsId(10L);
        stmt.setProdDbName("prod_db");
        stmt.setSeq(1);
        stmt.setSqlContent("CREATE TABLE t(id int);");
        stmt.setHash(GovSqlHashUtils.hash("CREATE TABLE t(id int);"));
        stmt.setExecStatus(execStatus);
        return stmt;
    }
}
