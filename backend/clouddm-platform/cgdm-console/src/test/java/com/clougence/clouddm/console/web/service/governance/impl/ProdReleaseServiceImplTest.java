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
    private ReleaseSchemaPrecheckService releaseSchemaPrecheckService;
    private com.clougence.clouddm.console.web.component.governance.ReleaseSchemaDiffService releaseSchemaDiffService;
    private com.clougence.clouddm.console.web.component.governance.SchemaGateOverrideService schemaGateOverrideService;

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
        // Sub-task A: schema mapper for freeze propagation
        DmProdReleaseStmtSchemaMapper prodSchemaMapper = mock(DmProdReleaseStmtSchemaMapper.class);
        when(prodReleaseDal.schemaMapper()).thenReturn(prodSchemaMapper);

        ticketDbStmtDal = mock(TicketDbStmtDal.class);
        ticketStmtMapper = mock(DmTicketDbStmtMapper.class);
        when(ticketDbStmtDal.stmtMapper()).thenReturn(ticketStmtMapper);
        // Sub-task A: ticket schema mapper for snapshot query during merge
        com.clougence.clouddm.platform.dal.mapper.govticket.DmTicketStmtSchemaMapper ticketSchemaMapper
            = mock(com.clougence.clouddm.platform.dal.mapper.govticket.DmTicketStmtSchemaMapper.class);
        when(ticketDbStmtDal.schemaMapper()).thenReturn(ticketSchemaMapper);
        when(ticketSchemaMapper.queryByStmtId(anyLong())).thenReturn(java.util.Collections.emptyList());

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
        releaseSchemaPrecheckService = mock(ReleaseSchemaPrecheckService.class);
        // Default: precheck passes
        when(releaseSchemaPrecheckService.precheck(any())).thenReturn(PrecheckConclusion.pass());
        // Sub-task A: extractTables default (no tables → no shared detection)
        when(releaseSchemaPrecheckService.extractTables(anyString())).thenReturn(java.util.Collections.emptySet());
        // Sub-task A: diff service (default pass — no drift)
        releaseSchemaDiffService = mock(com.clougence.clouddm.console.web.component.governance.ReleaseSchemaDiffService.class);
        when(releaseSchemaDiffService.diff(any())).thenReturn(PrecheckConclusion.pass());
        // ADR-011: governed override consumption (default: nothing consumable → denials stand)
        schemaGateOverrideService = mock(com.clougence.clouddm.console.web.component.governance.SchemaGateOverrideService.class);
        when(schemaGateOverrideService.findAndConsume(anyLong(), anyLong(), anyString(), any())).thenReturn(null);

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
        ReflectionTestUtils.setField(service, "releaseSchemaPrecheckService", releaseSchemaPrecheckService);
        ReflectionTestUtils.setField(service, "releaseSchemaDiffService", releaseSchemaDiffService);
        ReflectionTestUtils.setField(service, "schemaGateOverrideService", schemaGateOverrideService);
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

    // ==================== branch B: VIA_RELEASE PROD_DML merge eligibility ====================

    @Test
    public void createRelease_directProdDml_throwsNotViaRelease() {
        long ticketId = 130L;
        DmApprovalDO ticket = buildTicket(ticketId, ApprovalStatus.WAIT_APPROVAL, "PROD_DML", "DIRECT");
        when(approvalMapper.queryById(ticketId)).thenReturn(ticket);

        ProdReleaseCreateFO fo = new ProdReleaseCreateFO();
        fo.setTicketIds(List.of(ticketId));

        try {
            service.createRelease(PUID, UID, fo);
            fail("Expected ErrorMessageException for DIRECT PROD_DML merge");
        } catch (ErrorMessageException ex) {
            assertTrue(ex.getMessage().contains(String.valueOf(ticketId)));
        }
    }

    @Test
    public void createRelease_viaReleaseWrongStatus_throwsNotWaitRelease() {
        long ticketId = 131L;
        DmApprovalDO ticket = buildTicket(ticketId, ApprovalStatus.WAIT_APPROVAL, "PROD_DML", "VIA_RELEASE");
        when(approvalMapper.queryById(ticketId)).thenReturn(ticket);

        ProdReleaseCreateFO fo = new ProdReleaseCreateFO();
        fo.setTicketIds(List.of(ticketId));

        try {
            service.createRelease(PUID, UID, fo);
            fail("Expected ErrorMessageException for VIA_RELEASE ticket in wrong status");
        } catch (ErrorMessageException ex) {
            assertTrue(ex.getMessage().contains(String.valueOf(ticketId)));
        }
    }

    @Test
    public void createRelease_viaReleaseStmtNotPending_throws() {
        long ticketId = 132L;
        DmApprovalDO ticket = buildTicket(ticketId, ApprovalStatus.WAIT_RELEASE, "PROD_DML", "VIA_RELEASE");
        when(approvalMapper.queryById(ticketId)).thenReturn(ticket);

        DmTicketDbStmtDO stmt = buildTicketStmt(230L, ticketId, "SUCCESS");
        when(ticketStmtMapper.queryByTicketId(ticketId)).thenReturn(List.of(stmt));

        ProdReleaseCreateFO fo = new ProdReleaseCreateFO();
        fo.setTicketIds(List.of(ticketId));

        try {
            service.createRelease(PUID, UID, fo);
            fail("Expected ErrorMessageException for VIA_RELEASE ticket with non-PENDING stmt");
        } catch (ErrorMessageException ex) {
            assertTrue(ex.getMessage().contains(String.valueOf(ticketId)));
        }
    }

    @Test
    public void createRelease_viaReleaseFinished_throwsNotWaitRelease() {
        // FINISHED VIA_RELEASE ticket (via-release-completed) is visible in ledger for traceability
        // but NOT mergeable into a new release — only WAIT_RELEASE tickets can be merged.
        long ticketId = 133L;
        DmApprovalDO ticket = buildTicket(ticketId, ApprovalStatus.FINISHED, "PROD_DML", "VIA_RELEASE");
        when(approvalMapper.queryById(ticketId)).thenReturn(ticket);

        ProdReleaseCreateFO fo = new ProdReleaseCreateFO();
        fo.setTicketIds(List.of(ticketId));

        try {
            service.createRelease(PUID, UID, fo);
            fail("Expected ErrorMessageException for FINISHED VIA_RELEASE ticket");
        } catch (ErrorMessageException ex) {
            assertTrue(ex.getMessage().contains(String.valueOf(ticketId)));
        }
    }

    // ==================== createRelease tenant check (R1: open-to-all + cross-tenant block) ====================

    @Test
    public void createRelease_crossTenantTicket_throws_zeroInserts() {
        long ticketId = 110L;
        // Ticket exists, is FINISHED + PRE_DDL, but belongs to a different tenant
        DmApprovalDO ticket = buildTicket(ticketId, ApprovalStatus.FINISHED, "PRE_DDL");
        ticket.setPrimaryUid("puid-other-tenant"); // cross-tenant
        when(approvalMapper.queryById(ticketId)).thenReturn(ticket);

        ProdReleaseCreateFO fo = new ProdReleaseCreateFO();
        fo.setTicketIds(List.of(ticketId));

        try {
            service.createRelease(PUID, UID, fo);
            fail("Expected ErrorMessageException for cross-tenant ticket");
        } catch (ErrorMessageException ex) {
            assertTrue("Error message must contain the ticketId",
                ex.getMessage().contains(String.valueOf(ticketId)));
        }
        // Zero objects created — verify no inserts on any mapper
        verify(releaseMapper, never()).insert(any(DmProdReleaseDO.class));
        verify(stmtMapper, never()).insert(any(DmProdReleaseStmtDO.class));
        // checkResAuth must NOT be called (R1: removed from createRelease)
        verifyNoInteractions(dmAuthServiceForBiz);
    }

    @Test
    public void createRelease_mixedBatch_crossTenantSecond_throws_zeroInserts() {
        // First ticket: same-tenant, fully valid
        long ticketIdA = 120L;
        DmApprovalDO ticketA = buildTicket(ticketIdA, ApprovalStatus.FINISHED, "PRE_DDL");
        when(approvalMapper.queryById(ticketIdA)).thenReturn(ticketA);

        DmTicketDbStmtDO stmtA = buildTicketStmt(220L, ticketIdA, "SUCCESS");
        when(ticketStmtMapper.queryByTicketId(ticketIdA)).thenReturn(List.of(stmtA));
        when(stmtMapper.queryBySourceStmtId(220L)).thenReturn(null);

        // buildTicketStmt sets pairId=300L; mock pairMapper for that id
        DmDbPairDO pairA = buildPair(300L, 11L, "prod_db_a");
        when(pairMapper.selectById(300L)).thenReturn(pairA);
        when(releaseMapper.countActiveByProdDb(11L, "prod_db_a")).thenReturn(0);

        // Second ticket: cross-tenant
        long ticketIdB = 121L;
        DmApprovalDO ticketB = buildTicket(ticketIdB, ApprovalStatus.FINISHED, "PRE_DDL");
        ticketB.setPrimaryUid("puid-other-tenant");
        when(approvalMapper.queryById(ticketIdB)).thenReturn(ticketB);

        ProdReleaseCreateFO fo = new ProdReleaseCreateFO();
        fo.setTicketIds(List.of(ticketIdA, ticketIdB));

        try {
            service.createRelease(PUID, UID, fo);
            fail("Expected ErrorMessageException for mixed-batch with cross-tenant ticket");
        } catch (ErrorMessageException ex) {
            assertTrue("Error message must contain the cross-tenant ticketId",
                ex.getMessage().contains(String.valueOf(ticketIdB)));
        }
        // Whole batch rejected — zero inserts despite first ticket being valid
        verify(releaseMapper, never()).insert(any(DmProdReleaseDO.class));
        verify(stmtMapper, never()).insert(any(DmProdReleaseStmtDO.class));
    }

    // ==================== B6 M3 发布链过渡明示: gate_result additive ghost hint ====================

    @Test
    public void createRelease_ghostRoutedSource_gateResultCarriesAdditiveHint_m3() {
        // the source group ran a lock-free (gh-ost) segment on pre — the frozen snapshot carries
        // the additive display hint (PROD replay = DIRECT execution until M5); merge/replay logic
        // never reads it
        long ticketId = 130L;
        DmApprovalDO ticket = buildTicket(ticketId, ApprovalStatus.FINISHED, "PRE_DDL");
        when(approvalMapper.queryById(ticketId)).thenReturn(ticket);

        DmTicketDbStmtDO stmt = buildTicketStmt(230L, ticketId, "SUCCESS");
        stmt.setPrecheckResult("{\"checkStatus\":\"PASS\",\"changeType\":\"DDL\",\"segments\":["
            + "{\"segIdx\":1,\"type\":\"GHOST\",\"stmtIndex\":1,\"table\":\"t_big\","
            + "\"sql\":\"ALTER TABLE t_big ADD c INT\"}]}");
        when(ticketStmtMapper.queryByTicketId(ticketId)).thenReturn(List.of(stmt));
        when(stmtMapper.queryBySourceStmtId(230L)).thenReturn(null);
        when(pairMapper.selectById(300L)).thenReturn(buildPair(300L, 11L, "prod_db_a"));
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
        newApproval.setTicketInfo(com.clougence.utils.JsonUtils.toJson(new ApprovalMO()));
        when(approvalMapper.queryById(700L)).thenReturn(newApproval);
        ReflectionTestUtils.setField(service, "riskAssessmentService",
            new com.clougence.clouddm.console.web.component.governance.RiskAssessmentService());

        ProdReleaseCreateFO fo = new ProdReleaseCreateFO();
        fo.setTicketIds(List.of(ticketId));
        service.createRelease(PUID, UID, fo);

        org.mockito.ArgumentCaptor<DmProdReleaseDO> captor
            = org.mockito.ArgumentCaptor.forClass(DmProdReleaseDO.class);
        verify(releaseMapper).insert(captor.capture());
        java.util.Map<?, ?> gr = com.clougence.utils.JsonUtils.toObj(
            captor.getValue().getGateResult(), java.util.Map.class);
        assertEquals("the additive top-level hint count", 1, gr.get("ghostRoutedStmtCount"));
        java.util.List<?> stmts = (java.util.List<?>) gr.get("stmts");
        assertEquals(1, stmts.size());
        assertEquals("the per-stmt additive flag", Boolean.TRUE,
            ((java.util.Map<?, ?>) stmts.get(0)).get("ghostRouted"));
        // the frozen contract keys are untouched
        assertEquals(1, gr.get("sourceTicketCount"));
        assertEquals(1, gr.get("stmtCount"));
    }

    @Test
    public void createRelease_plainSource_gateResultStaysByteShape_m3() {
        // no ghost route → NO hint keys at all (a plain release keeps the legacy gate_result shape)
        long ticketId = 131L;
        DmApprovalDO ticket = buildTicket(ticketId, ApprovalStatus.FINISHED, "PRE_DDL");
        when(approvalMapper.queryById(ticketId)).thenReturn(ticket);

        DmTicketDbStmtDO stmt = buildTicketStmt(231L, ticketId, "SUCCESS");
        stmt.setPrecheckResult("{\"checkStatus\":\"PASS\",\"changeType\":\"DDL\"}");
        when(ticketStmtMapper.queryByTicketId(ticketId)).thenReturn(List.of(stmt));
        when(stmtMapper.queryBySourceStmtId(231L)).thenReturn(null);
        when(pairMapper.selectById(300L)).thenReturn(buildPair(300L, 11L, "prod_db_a"));
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
        newApproval.setTicketInfo(com.clougence.utils.JsonUtils.toJson(new ApprovalMO()));
        when(approvalMapper.queryById(700L)).thenReturn(newApproval);
        ReflectionTestUtils.setField(service, "riskAssessmentService",
            new com.clougence.clouddm.console.web.component.governance.RiskAssessmentService());

        ProdReleaseCreateFO fo = new ProdReleaseCreateFO();
        fo.setTicketIds(List.of(ticketId));
        service.createRelease(PUID, UID, fo);

        org.mockito.ArgumentCaptor<DmProdReleaseDO> captor
            = org.mockito.ArgumentCaptor.forClass(DmProdReleaseDO.class);
        verify(releaseMapper).insert(captor.capture());
        java.util.Map<?, ?> gr = com.clougence.utils.JsonUtils.toObj(
            captor.getValue().getGateResult(), java.util.Map.class);
        assertFalse(gr.containsKey("ghostRoutedStmtCount"));
        java.util.List<?> stmts = (java.util.List<?>) gr.get("stmts");
        assertFalse(((java.util.Map<?, ?>) stmts.get(0)).containsKey("ghostRouted"));
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
        // Hash drift event is operator-driven (startExecution carries uid) — the null/blank
        // fallback must NOT mask the real uid for RELEASE_CREATED/EXEC_STARTED/HASH_DRIFT.
        verify(eventMapper).insert(argThat((DmDbChangeEventDO e) ->
            e != null && e.getEventType() != null && e.getEventType().contains("HASH_DRIFT")
                && UID.equals(e.getOperatorUid())));
        // aggregateIfAllTerminal has no operator in scope -> RELEASE_STMT_FAILED must fall
        // back to SYSTEM (operator_uid column is NOT NULL without default).
        verify(eventMapper).insert(argThat((DmDbChangeEventDO e) ->
            e != null && "RELEASE_STMT_FAILED".equals(e.getEventType())
                && "SYSTEM".equals(e.getOperatorUid())));
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
        // operator_uid column is NOT NULL — callback events must fall back to SYSTEM
        verify(eventMapper).insert(argThat((DmDbChangeEventDO e) ->
            e != null && e.getEventType() != null && e.getEventType().contains("REJECTED")
                && "SYSTEM".equals(e.getOperatorUid())));
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
            e != null && e.getEventType() != null && e.getEventType().contains("CANCELLED")
                && "SYSTEM".equals(e.getOperatorUid())));
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
            e != null && e.getEventType() != null && e.getEventType().contains("APPROVED")
                && "SYSTEM".equals(e.getOperatorUid())));
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

    // ==================== Schema precheck wiring tests ====================

    @Test
    public void startExecution_precheckBlock_marksStmtFailedNoJob() {
        long releaseId = 550L;
        DmProdReleaseDO release = buildRelease(releaseId, ProdReleaseStatus.APPROVED);
        when(releaseMapper.queryById(releaseId)).thenReturn(release);
        when(releaseStateMachine.transit(eq(releaseId), anySet(), eq(ProdReleaseStatus.EXECUTING))).thenReturn(true);

        DmProdReleaseStmtDO stmt = buildReleaseStmt(650L, releaseId, "PENDING");
        when(stmtMapper.queryByReleaseId(releaseId)).thenReturn(List.of(stmt));
        when(stmtMapper.firstPendingStmt(releaseId, 10L, "prod_db")).thenReturn(stmt);

        // Precheck returns BLOCK
        when(releaseSchemaPrecheckService.precheck(any())).thenReturn(
            PrecheckConclusion.block(List.of(
                new com.clougence.clouddm.console.web.component.governance.SchemaViolation(
                    1, "CREATE TABLE t(id int)", "t", null, "table to not exist", "exists"))));

        // All terminal after BLOCK
        DmProdReleaseStmtDO failedStmt = buildReleaseStmt(650L, releaseId, "FAILED");
        when(stmtMapper.queryByReleaseId(releaseId)).thenReturn(List.of(stmt), List.of(failedStmt));

        DmApprovalDO approval = mock(DmApprovalDO.class);
        when(approval.getBizId()).thenReturn("biz-550");
        when(approvalMapper.queryById(anyLong())).thenReturn(approval);

        service.startExecution(releaseId, UID);

        // Verify stmt marked FAILED with precheck detail
        verify(stmtMapper).updateExecStatus(eq(650L), eq("FAILED"), contains("table"));
        // Verify precheck failed event written
        verify(eventMapper).insert(argThat((DmDbChangeEventDO e) ->
            e != null && "RELEASE_SCHEMA_PRECHECK_FAILED".equals(e.getEventType())
                && UID.equals(e.getOperatorUid())));
        // Verify no job created
        verify(autoExecService, never()).createReleaseStmtJob(any(), anyString(), anyBoolean(), any(), anyString(), anyString());
    }

    /**
     * ADR-011 pin flip (was startExecution_precheckWarnSkip_proceedsWithJobAndEvent): the
     * dispatch gate is now fail-closed — UNVERIFIABLE (the engine's replacement for the former
     * WARN_SKIP infra-failure outcomes) DENIES with the dedicated event type and the distinct
     * exec_detail wording; NO job is created.
     */
    @Test
    public void startExecution_precheckUnverifiable_marksStmtFailedNoJob() {
        long releaseId = 551L;
        DmProdReleaseDO release = buildRelease(releaseId, ProdReleaseStatus.APPROVED);
        when(releaseMapper.queryById(releaseId)).thenReturn(release);
        when(releaseStateMachine.transit(eq(releaseId), anySet(), eq(ProdReleaseStatus.EXECUTING))).thenReturn(true);

        DmProdReleaseStmtDO stmt = buildReleaseStmt(651L, releaseId, "PENDING");
        when(stmtMapper.queryByReleaseId(releaseId)).thenReturn(List.of(stmt));
        when(stmtMapper.firstPendingStmt(releaseId, 10L, "prod_db")).thenReturn(stmt);

        // Precheck returns UNVERIFIABLE (ADR-011: former WARN_SKIP infra outcomes)
        when(releaseSchemaPrecheckService.precheck(any())).thenReturn(
            PrecheckConclusion.unverifiable("worker unreachable"));

        service.startExecution(releaseId, UID);

        // Stmt FAILED with the UNVERIFIABLE wording (not the legacy "skipped:" text)
        verify(stmtMapper).updateExecStatus(eq(651L), eq("FAILED"), contains("Cannot verify"));
        // Dedicated GATE_UNVERIFIABLE event (NOT PRECHECK_FAILED — remediation owners differ)
        verify(eventMapper).insert(argThat((DmDbChangeEventDO e) ->
            e != null && "RELEASE_SCHEMA_GATE_UNVERIFIABLE".equals(e.getEventType())
                && UID.equals(e.getOperatorUid())
                && e.getEventData().contains("PRECHECK")));
        // No job created — fail-closed
        verify(autoExecService, never()).createReleaseStmtJob(any(), anyString(), anyBoolean(), any(), anyString(), anyString());
    }

    /**
     * ADR-011 governed override at the dispatch gate: a consumable grant downgrades the
     * UNVERIFIABLE denial to a pass — GATE_OVERRIDE event carries the grantor trail
     * (reason/expiry/operator) and the job IS created.
     */
    @Test
    public void startExecution_precheckUnverifiable_withOverride_consumesAndProceeds() {
        long releaseId = 553L;
        DmProdReleaseDO release = buildRelease(releaseId, ProdReleaseStatus.APPROVED);
        when(releaseMapper.queryById(releaseId)).thenReturn(release);
        when(releaseStateMachine.transit(eq(releaseId), anySet(), eq(ProdReleaseStatus.EXECUTING))).thenReturn(true);

        DmProdReleaseStmtDO stmt = buildReleaseStmt(653L, releaseId, "PENDING");
        when(stmtMapper.queryByReleaseId(releaseId)).thenReturn(List.of(stmt));
        when(stmtMapper.firstPendingStmt(releaseId, 10L, "prod_db")).thenReturn(stmt);

        when(releaseSchemaPrecheckService.precheck(any())).thenReturn(
            PrecheckConclusion.unverifiable("worker unreachable"));

        com.clougence.clouddm.platform.dal.model.prodrelease.DmGovSchemaGateOverrideDO override
            = new com.clougence.clouddm.platform.dal.model.prodrelease.DmGovSchemaGateOverrideDO();
        override.setId(88L);
        override.setReleaseId(releaseId);
        override.setGateType("PRECHECK");
        override.setOperatorUid("dba-001");
        override.setReason("infra window approved by ops");
        override.setExpireAt(new Date(System.currentTimeMillis() + 3600_000L));
        when(schemaGateOverrideService.findAndConsume(eq(releaseId), eq(653L), eq("PRECHECK"), any()))
            .thenReturn(override);

        service.startExecution(releaseId, UID);

        // Override event with reason/operator trail
        verify(eventMapper).insert(argThat((DmDbChangeEventDO e) ->
            e != null && "RELEASE_SCHEMA_GATE_OVERRIDE".equals(e.getEventType())
                && e.getEventData().contains("dba-001")
                && e.getEventData().contains("infra window approved by ops")
                && e.getEventData().contains("UNVERIFIABLE")));
        // Denial event NOT written, stmt NOT failed, job created (downgraded to pass)
        verify(eventMapper, never()).insert(argThat((DmDbChangeEventDO e) ->
            e != null && "RELEASE_SCHEMA_GATE_UNVERIFIABLE".equals(e.getEventType())));
        verify(stmtMapper, never()).updateExecStatus(eq(653L), eq("FAILED"), any());
        verify(autoExecService).createReleaseStmtJob(eq(stmt), anyString(), anyBoolean(), any(), anyString(), eq(UID));
    }

    /**
     * ADR-011: the diff gate consumes overrides under its OWN gateType (DIFF) — one grant
     * releases exactly one gate class; the PRECHECK denial of the same stmt would need a
     * second grant (strictness per design §3).
     */
    @Test
    public void startExecution_diffUnverifiable_withOverride_consumesDiffGateOnly() {
        long releaseId = 554L;
        DmProdReleaseDO release = buildRelease(releaseId, ProdReleaseStatus.APPROVED);
        when(releaseMapper.queryById(releaseId)).thenReturn(release);
        when(releaseStateMachine.transit(eq(releaseId), anySet(), eq(ProdReleaseStatus.EXECUTING))).thenReturn(true);

        DmProdReleaseStmtDO stmt = buildReleaseStmt(654L, releaseId, "PENDING");
        when(stmtMapper.queryByReleaseId(releaseId)).thenReturn(List.of(stmt));
        when(stmtMapper.firstPendingStmt(releaseId, 10L, "prod_db")).thenReturn(stmt);

        // Precheck passes; diff gate denies
        when(releaseSchemaDiffService.diff(any())).thenReturn(
            PrecheckConclusion.unverifiable("live schema fetch failed"));

        com.clougence.clouddm.platform.dal.model.prodrelease.DmGovSchemaGateOverrideDO override
            = new com.clougence.clouddm.platform.dal.model.prodrelease.DmGovSchemaGateOverrideDO();
        override.setId(89L);
        override.setReleaseId(releaseId);
        override.setGateType("DIFF");
        override.setOperatorUid("dba-002");
        override.setReason("drift probe window");
        override.setExpireAt(new Date(System.currentTimeMillis() + 3600_000L));
        when(schemaGateOverrideService.findAndConsume(eq(releaseId), eq(654L), eq("DIFF"), any()))
            .thenReturn(override);

        service.startExecution(releaseId, UID);

        // The DIFF gate looked for a DIFF override (gateType separation pinned)
        verify(schemaGateOverrideService).findAndConsume(eq(releaseId), eq(654L), eq("DIFF"), any());
        verify(eventMapper).insert(argThat((DmDbChangeEventDO e) ->
            e != null && "RELEASE_SCHEMA_GATE_OVERRIDE".equals(e.getEventType())
                && e.getEventData().contains("DIFF")));
        verify(autoExecService).createReleaseStmtJob(eq(stmt), anyString(), anyBoolean(), any(), anyString(), eq(UID));
    }

    /**
     * ADR-011 S-face regularization: a PASS carrying uncertain notes proceeds but leaves the
     * RELEASE_SCHEMA_PRECHECK_UNCERTAIN trail (formerly silent fail-open).
     */
    @Test
    public void startExecution_precheckPassWithNotes_writesUncertainEventAndProceeds() {
        long releaseId = 555L;
        DmProdReleaseDO release = buildRelease(releaseId, ProdReleaseStatus.APPROVED);
        when(releaseMapper.queryById(releaseId)).thenReturn(release);
        when(releaseStateMachine.transit(eq(releaseId), anySet(), eq(ProdReleaseStatus.EXECUTING))).thenReturn(true);

        DmProdReleaseStmtDO stmt = buildReleaseStmt(655L, releaseId, "PENDING");
        when(stmtMapper.queryByReleaseId(releaseId)).thenReturn(List.of(stmt));
        when(stmtMapper.firstPendingStmt(releaseId, 10L, "prod_db")).thenReturn(stmt);

        when(releaseSchemaPrecheckService.precheck(any())).thenReturn(
            PrecheckConclusion.passWithNotes(List.of(
                new PrecheckConclusion.Note(PrecheckConclusion.Note.UNKNOWN_STMT_TYPE,
                    "Unclassified statement at sql#1 — not verified: GRANT SELECT ..."))));

        service.startExecution(releaseId, UID);

        verify(eventMapper).insert(argThat((DmDbChangeEventDO e) ->
            e != null && "RELEASE_SCHEMA_PRECHECK_UNCERTAIN".equals(e.getEventType())
                && e.getEventData().contains("UNKNOWN_STMT_TYPE")));
        verify(stmtMapper, never()).updateExecStatus(eq(655L), eq("FAILED"), any());
        verify(autoExecService).createReleaseStmtJob(eq(stmt), anyString(), anyBoolean(), any(), anyString(), eq(UID));
    }

    /** ADR-011: retryReleaseStmt re-runs the same fail-closed gate (three-gate consistency). */
    @Test
    public void retryReleaseStmt_unverifiable_marksFailedNoRebuild() {
        long stmtId = 926L;
        long releaseId = 927L;
        DmProdReleaseStmtDO stmt = buildReleaseStmt(stmtId, releaseId, "FAILED");
        when(stmtMapper.queryById(stmtId)).thenReturn(stmt);

        DmProdReleaseDO release = buildRelease(releaseId, ProdReleaseStatus.EXECUTING);
        release.setPrimaryUid(PUID);
        when(releaseMapper.queryById(releaseId)).thenReturn(release);

        com.clougence.clouddm.platform.dal.model.datasource.DmDsDO prodDs
            = mock(com.clougence.clouddm.platform.dal.model.datasource.DmDsDO.class);
        when(prodDs.getInstanceId()).thenReturn("inst-1");
        when(dsMapper.queryDsIdentityById(anyLong())).thenReturn(prodDs);

        when(releaseSchemaPrecheckService.precheck(any())).thenReturn(
            PrecheckConclusion.unverifiable("Failed to fetch DS config: timeout"));

        service.retryReleaseStmt(PUID, UID, stmtId);

        verify(stmtMapper).updateExecStatus(eq(stmtId), eq("FAILED"), contains("Cannot verify"));
        verify(eventMapper).insert(argThat((DmDbChangeEventDO e) ->
            e != null && "RELEASE_SCHEMA_GATE_UNVERIFIABLE".equals(e.getEventType())
                && UID.equals(e.getOperatorUid())));
        verify(stmtMapper, never()).updateExecStatus(eq(stmtId), eq("PENDING"), any());
        verify(autoExecService, never()).createReleaseStmtJob(any(), anyString(), anyBoolean(), any(), anyString(), anyString());
    }

    @Test
    public void startExecution_precheckPass_noEvent() {
        long releaseId = 552L;
        DmProdReleaseDO release = buildRelease(releaseId, ProdReleaseStatus.APPROVED);
        when(releaseMapper.queryById(releaseId)).thenReturn(release);
        when(releaseStateMachine.transit(eq(releaseId), anySet(), eq(ProdReleaseStatus.EXECUTING))).thenReturn(true);

        DmProdReleaseStmtDO stmt = buildReleaseStmt(652L, releaseId, "PENDING");
        when(stmtMapper.queryByReleaseId(releaseId)).thenReturn(List.of(stmt));
        when(stmtMapper.firstPendingStmt(releaseId, 10L, "prod_db")).thenReturn(stmt);

        // Precheck returns PASS (default mock)
        service.startExecution(releaseId, UID);

        // No precheck events
        verify(eventMapper, never()).insert(argThat((DmDbChangeEventDO e) ->
            e != null && e.getEventType() != null
                && e.getEventType().contains("PRECHECK")));
        // Job created
        verify(autoExecService).createReleaseStmtJob(eq(stmt), anyString(), anyBoolean(), any(), anyString(), eq(UID));
    }

    // ==================== retryReleaseStmt hash gate + precheck wiring ====================

    @Test
    public void retryReleaseStmt_hashDrift_marksFailedNoJob() {
        long stmtId = 920L;
        long releaseId = 921L;
        DmProdReleaseStmtDO stmt = buildReleaseStmt(stmtId, releaseId, "FAILED");
        // Corrupt the hash to trigger drift
        stmt.setHash("WRONG_HASH");
        when(stmtMapper.queryById(stmtId)).thenReturn(stmt);

        DmProdReleaseDO release = buildRelease(releaseId, ProdReleaseStatus.EXECUTING);
        release.setPrimaryUid(PUID);
        when(releaseMapper.queryById(releaseId)).thenReturn(release);

        com.clougence.clouddm.platform.dal.model.datasource.DmDsDO prodDs
            = mock(com.clougence.clouddm.platform.dal.model.datasource.DmDsDO.class);
        when(prodDs.getInstanceId()).thenReturn("inst-1");
        when(dsMapper.queryDsIdentityById(anyLong())).thenReturn(prodDs);

        service.retryReleaseStmt(PUID, UID, stmtId);

        // Verify stmt marked FAILED with hash drift detail
        verify(stmtMapper).updateExecStatus(eq(stmtId), eq("FAILED"), contains("Hash drift"));
        // Verify hash drift event
        verify(eventMapper).insert(argThat((DmDbChangeEventDO e) ->
            e != null && "RELEASE_HASH_DRIFT".equals(e.getEventType())
                && UID.equals(e.getOperatorUid())));
        // Verify no PENDING reset and no job
        verify(stmtMapper, never()).updateExecStatus(eq(stmtId), eq("PENDING"), any());
        verify(autoExecService, never()).createReleaseStmtJob(any(), anyString(), anyBoolean(), any(), anyString(), anyString());
    }

    @Test
    public void retryReleaseStmt_precheckBlock_doesNotRebuild() {
        long stmtId = 922L;
        long releaseId = 923L;
        DmProdReleaseStmtDO stmt = buildReleaseStmt(stmtId, releaseId, "FAILED");
        when(stmtMapper.queryById(stmtId)).thenReturn(stmt);

        DmProdReleaseDO release = buildRelease(releaseId, ProdReleaseStatus.EXECUTING);
        release.setPrimaryUid(PUID);
        when(releaseMapper.queryById(releaseId)).thenReturn(release);

        com.clougence.clouddm.platform.dal.model.datasource.DmDsDO prodDs
            = mock(com.clougence.clouddm.platform.dal.model.datasource.DmDsDO.class);
        when(prodDs.getInstanceId()).thenReturn("inst-1");
        when(dsMapper.queryDsIdentityById(anyLong())).thenReturn(prodDs);

        // Precheck returns BLOCK
        when(releaseSchemaPrecheckService.precheck(any())).thenReturn(
            PrecheckConclusion.block(List.of(
                new com.clougence.clouddm.console.web.component.governance.SchemaViolation(
                    1, "CREATE TABLE t(id int)", "t", null, "table to not exist", "exists"))));

        service.retryReleaseStmt(PUID, UID, stmtId);

        // Verify stmt marked FAILED with precheck detail (not PENDING)
        verify(stmtMapper).updateExecStatus(eq(stmtId), eq("FAILED"), contains("table"));
        // Verify precheck failed event
        verify(eventMapper).insert(argThat((DmDbChangeEventDO e) ->
            e != null && "RELEASE_SCHEMA_PRECHECK_FAILED".equals(e.getEventType())
                && UID.equals(e.getOperatorUid())));
        // Verify no PENDING reset and no job
        verify(stmtMapper, never()).updateExecStatus(eq(stmtId), eq("PENDING"), any());
        verify(autoExecService, never()).createReleaseStmtJob(any(), anyString(), anyBoolean(), any(), anyString(), anyString());
    }

    @Test
    public void retryReleaseStmt_precheckPass_rebuildsJob() {
        long stmtId = 924L;
        long releaseId = 925L;
        DmProdReleaseStmtDO stmt = buildReleaseStmt(stmtId, releaseId, "FAILED");
        when(stmtMapper.queryById(stmtId)).thenReturn(stmt);

        DmProdReleaseDO release = buildRelease(releaseId, ProdReleaseStatus.EXECUTING);
        release.setPrimaryUid(PUID);
        when(releaseMapper.queryById(releaseId)).thenReturn(release);

        com.clougence.clouddm.platform.dal.model.datasource.DmDsDO prodDs
            = mock(com.clougence.clouddm.platform.dal.model.datasource.DmDsDO.class);
        when(prodDs.getInstanceId()).thenReturn("inst-1");
        when(dsMapper.queryDsIdentityById(anyLong())).thenReturn(prodDs);

        // Precheck returns PASS (default mock)
        service.retryReleaseStmt(PUID, UID, stmtId);

        // Verify PENDING reset happened
        verify(stmtMapper).updateExecStatus(eq(stmtId), eq("PENDING"), isNull());
        // Verify job created
        verify(autoExecService).createReleaseStmtJob(eq(stmt), anyString(), anyBoolean(), any(), anyString(), eq(UID));
    }

    // ==================== helpers ====================

    private DmApprovalDO buildTicket(long id, ApprovalStatus status, String ticketType) {
        return buildTicket(id, status, ticketType, null);
    }

    private DmApprovalDO buildTicket(long id, ApprovalStatus status, String ticketType, String approvalMode) {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(id);
        ticket.setBizId("biz-" + id);
        ticket.setTicketStatus(status);
        ticket.setPrimaryUid(PUID);
        ticket.setOwnerUid(UID);
        ticket.setGmtCreate(new Date());
        ApprovalMO mo = new ApprovalMO();
        mo.setTicketType(ticketType);
        mo.setApprovalMode(approvalMode);
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
