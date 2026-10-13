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
package com.clougence.clouddm.console.web.component.bridge;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.EnumSet;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.console.web.component.approval.ApprovalFlowService;
import com.clougence.clouddm.console.web.component.approval.ApprovalPersonService;
import com.clougence.clouddm.console.web.component.approval.ApprovalStateService;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.component.auth.DmAuthServiceForBiz;
import com.clougence.clouddm.console.web.component.execute.AutoExecService;
import com.clougence.clouddm.console.web.component.governance.GovReturnNotifyService;
import com.clougence.clouddm.console.web.component.governance.PrecheckConclusion;
import com.clougence.clouddm.console.web.component.governance.ProdReleaseStateMachine;
import com.clougence.clouddm.console.web.component.governance.ReleaseSchemaDiffService;
import com.clougence.clouddm.console.web.component.governance.ReleaseSchemaPrecheckService;
import com.clougence.clouddm.console.web.component.governance.RollbackSqlGenerator;
import com.clougence.clouddm.console.web.component.governance.SchemaSnapshotService;
import com.clougence.clouddm.console.web.model.fo.govticket.GovTicketV2SubmitFO;
import com.clougence.clouddm.console.web.model.fo.prodrelease.ProdReleaseCreateFO;
import com.clougence.clouddm.console.web.model.vo.ticket.DmTicketResultVO;
import com.clougence.clouddm.console.web.model.vo.envparam.DmEnvParamTicketDesVO;
import com.clougence.clouddm.console.web.service.approval.ApprovalControlService;
import com.clougence.clouddm.console.web.service.dbpair.DbPairService;
import com.clougence.clouddm.console.web.service.envparam.DmEnvParamService;
import com.clougence.clouddm.console.web.service.governance.impl.ProdReleaseServiceImpl;
import com.clougence.clouddm.console.web.service.govticket.impl.GovTicketV2ServiceImpl;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.DataSourceDal;
import com.clougence.clouddm.platform.dal.access.DbChangeEventDal;
import com.clougence.clouddm.platform.dal.access.DbPairDal;
import com.clougence.clouddm.platform.dal.access.ExecutionDal;
import com.clougence.clouddm.platform.dal.access.NamingDao;
import com.clougence.clouddm.platform.dal.access.ProdReleaseDal;
import com.clougence.clouddm.platform.dal.access.SystemDal;
import com.clougence.clouddm.platform.dal.access.TicketDbStmtDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangeEventMapper;
import com.clougence.clouddm.platform.dal.mapper.dbpair.DmDbPairMapper;
import com.clougence.clouddm.platform.dal.mapper.datasource.DmDsMapper;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoJobMapper;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoTaskMapper;
import com.clougence.clouddm.platform.dal.mapper.govticket.DmTicketDbStmtMapper;
import com.clougence.clouddm.platform.dal.mapper.govticket.DmTicketStmtRevisionMapper;
import com.clougence.clouddm.platform.dal.mapper.govticket.DmTicketStmtSchemaMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseStmtMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseStmtSchemaMapper;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalType;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalTemplateDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO;
import com.clougence.clouddm.platform.dal.model.dbpair.DmDbPairDO;
import com.clougence.clouddm.platform.dal.model.datasource.DmDsDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseStmtDO;
import com.clougence.clouddm.sdk.sql.parser.SplitQueryType;
import com.clougence.clouddm.sdk.sql.parser.SplitScript;
import com.clougence.utils.JsonUtils;

/**
 * Task 10-09 Step 2 (design §3.3, test matrix row 2): the afterCommit trigger HOOK SITES —
 * ① GovTicketV2ServiceImpl.submit (PROD_DML DIRECT + External only; VIA_RELEASE and every
 * non-External channel trigger ZERO outbound, boundary #13/#14 pins) and ② ProdReleaseServiceImpl
 * .createRelease (service-layer single point covering BOTH the console and the open
 * /releases/create entries — GovOpenApiServiceImpl.createRelease delegates here).
 * <p>
 * Fixtures are copies of the GovTicketV2SubmitModeTest / ProdReleaseServiceImplTest patterns
 * (established test-fixture duplication convention); the bridge service is a MOCK — this suite
 * pins the CALL SITES, DingBridgeOutboundServiceTest pins the service semantics.
 */
public class DingBridgeFlowTriggerTest {

    private static final String PUID = "puid-001";
    private static final String UID  = "uid-001";

    private DingBridgeOutboundService bridge;

    // ---- submit fixture ----
    private GovTicketV2ServiceImpl ticketService;
    private DmApprovalMapper       approvalMapper;
    private DmEnvParamService      dmEnvParamService;
    private ApprovalFlowService    approvalFlowService;

    // ---- release fixture ----
    private ProdReleaseServiceImpl    releaseService;
    private DmProdReleaseMapper       releaseMapper;
    private DmProdReleaseStmtMapper   releaseStmtMapper;
    private DmTicketDbStmtMapper      ticketStmtMapper;
    private DmApprovalMapper          releaseApprovalMapper;
    private ApprovalControlService    approvalControlService;
    private DmDbChangeEventMapper     releaseEventMapper;

    @Before
    public void setUp() {
        bridge = mock(DingBridgeOutboundService.class);
    }

    // ==================== hook ①: submit ====================

    private void setUpSubmitFixture() {
        ticketService = new GovTicketV2ServiceImpl();

        ExecutionDal staticExecDal = mock(ExecutionDal.class);
        DmExecAutoJobMapper staticJobMapper = mock(DmExecAutoJobMapper.class);
        DmExecAutoTaskMapper staticTaskMapper = mock(DmExecAutoTaskMapper.class);
        when(staticExecDal.autoJobMapper()).thenReturn(staticJobMapper);
        when(staticExecDal.autoTaskMapper()).thenReturn(staticTaskMapper);
        when(staticJobMapper.queryByBizId(anyString())).thenReturn(null);
        when(staticTaskMapper.queryByBizId(anyString())).thenReturn(null);
        ReflectionTestUtils.setField(com.clougence.clouddm.console.web.util.DmTeamUtils.class, "executionDal", staticExecDal);

        TicketDbStmtDal ticketDbStmtDal = mock(TicketDbStmtDal.class);
        DmTicketDbStmtMapper stmtMapper = mock(DmTicketDbStmtMapper.class);
        when(ticketDbStmtDal.stmtMapper()).thenReturn(stmtMapper);
        when(ticketDbStmtDal.revisionMapper()).thenReturn(mock(DmTicketStmtRevisionMapper.class));
        when(ticketDbStmtDal.schemaMapper()).thenReturn(mock(DmTicketStmtSchemaMapper.class));

        ApprovalDal approvalDal = mock(ApprovalDal.class);
        approvalMapper = mock(DmApprovalMapper.class);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);
        when(approvalMapper.insert(any(DmApprovalDO.class))).thenAnswer(inv -> {
            inv.getArgument(0, DmApprovalDO.class).setId(999L);
            return 1;
        });

        DbChangeEventDal dbChangeEventDal = mock(DbChangeEventDal.class);
        DmDbChangeEventMapper eventMapper = mock(DmDbChangeEventMapper.class);
        when(dbChangeEventDal.eventMapper()).thenReturn(eventMapper);
        when(eventMapper.insert(any(DmDbChangeEventDO.class))).thenReturn(1);

        DbPairDal dbPairDal = mock(DbPairDal.class);
        DmDbPairMapper pairMapper = mock(DmDbPairMapper.class);
        when(dbPairDal.pairMapper()).thenReturn(pairMapper);
        DmDbPairDO pair = new DmDbPairDO();
        pair.setId(300L);
        pair.setPreDsId(20L);
        pair.setPreDbName("pre_db");
        pair.setProdDsId(10L);
        pair.setProdDbName("prod_db");
        pair.setStatus("ENABLED");
        when(pairMapper.selectById(300L)).thenReturn(pair);

        DataSourceDal dsDal = mock(DataSourceDal.class);
        DmDsMapper dsMapper = mock(DmDsMapper.class);
        when(dsDal.dsMapper()).thenReturn(dsMapper);
        DmDsDO dsDO = mock(DmDsDO.class);
        when(dsDO.getDsEnvId()).thenReturn(1L);
        when(dsDO.getInstanceId()).thenReturn("inst-1");
        when(dsMapper.queryDsIdentityById(anyLong())).thenReturn(dsDO);

        com.clougence.clouddm.console.web.component.dsconfig.DmDsConfigService dmDsConfigService
            = mock(com.clougence.clouddm.console.web.component.dsconfig.DmDsConfigService.class);
        when(dmDsConfigService.fetchDsConfigFromExists(anyLong())).thenReturn(null);
        when(dmDsConfigService.parseLevels(anyList())).thenReturn(
            mock(com.clougence.clouddm.console.web.component.dsconfig.mode.DsLevels.class));

        com.clougence.clouddm.console.web.component.analysis.QueryAnalysisService queryAnalysisService
            = mock(com.clougence.clouddm.console.web.component.analysis.QueryAnalysisService.class);
        when(queryAnalysisService.analysisSplitStream(any(), any(), any(), anyInt(), anyInt()))
            .thenAnswer(inv -> java.util.stream.Stream.of(dmlSplitScript()));
        when(queryAnalysisService.analysisRulesStream(any(), any(), any(), anyInt(), anyInt(), any()))
            .thenAnswer(inv -> java.util.stream.Stream.empty());

        dmEnvParamService = mock(DmEnvParamService.class);

        SystemDal systemDal = mock(SystemDal.class);
        when(systemDal.fetchSystemConf(anyString(), eq(Boolean.class))).thenReturn(true);
        com.clougence.clouddm.platform.dal.mapper.system.DmSysEnvMapper envMapper
            = mock(com.clougence.clouddm.platform.dal.mapper.system.DmSysEnvMapper.class);
        when(systemDal.envMapper()).thenReturn(envMapper);
        com.clougence.clouddm.platform.dal.model.system.DmSysEnvDO envDO
            = new com.clougence.clouddm.platform.dal.model.system.DmSysEnvDO();
        envDO.setEnvName("env-test");
        when(envMapper.queryByEnvID(anyString(), anyLong())).thenReturn(envDO);

        approvalFlowService = mock(ApprovalFlowService.class);
        NamingDao namingDao = mock(NamingDao.class);
        when(namingDao.genApprovalBizId()).thenReturn("biz-test");

        ReflectionTestUtils.setField(ticketService, "ticketDbStmtDal", ticketDbStmtDal);
        ReflectionTestUtils.setField(ticketService, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(ticketService, "approvalStateService", mock(ApprovalStateService.class));
        ReflectionTestUtils.setField(ticketService, "dbChangeEventDal", dbChangeEventDal);
        ReflectionTestUtils.setField(ticketService, "prodReleaseDal", mock(ProdReleaseDal.class));
        ReflectionTestUtils.setField(ticketService, "schemaSnapshotService", mock(SchemaSnapshotService.class));
        ReflectionTestUtils.setField(ticketService, "dbPairDal", dbPairDal);
        ReflectionTestUtils.setField(ticketService, "dsDal", dsDal);
        ReflectionTestUtils.setField(ticketService, "dmDsConfigService", dmDsConfigService);
        ReflectionTestUtils.setField(ticketService, "queryAnalysisService", queryAnalysisService);
        ReflectionTestUtils.setField(ticketService, "dmEnvParamService", dmEnvParamService);
        ReflectionTestUtils.setField(ticketService, "systemDal", systemDal);
        ReflectionTestUtils.setField(ticketService, "approvalFlowService", approvalFlowService);
        ReflectionTestUtils.setField(ticketService, "dbPairService", mock(DbPairService.class));
        ReflectionTestUtils.setField(ticketService, "approvalPersonService", mock(ApprovalPersonService.class));
        ReflectionTestUtils.setField(ticketService, "dmAuthServiceForBiz", mock(DmAuthServiceForBiz.class));
        ReflectionTestUtils.setField(ticketService, "govReturnNotifyService", mock(GovReturnNotifyService.class));
        ReflectionTestUtils.setField(ticketService, "namingDao", namingDao);
        ReflectionTestUtils.setField(ticketService, "rollbackSqlGenerator", new RollbackSqlGenerator());
        com.clougence.clouddm.console.web.component.governance.RiskAssessmentService riskAssessmentService
            = new com.clougence.clouddm.console.web.component.governance.RiskAssessmentService();
        ReflectionTestUtils.setField(riskAssessmentService, "dmEnvParamService", dmEnvParamService);
        ReflectionTestUtils.setField(ticketService, "riskAssessmentService", riskAssessmentService);
        com.clougence.clouddm.console.web.component.governance.SqlRowEstimator sqlRowEstimator
            = mock(com.clougence.clouddm.console.web.component.governance.SqlRowEstimator.class);
        when(sqlRowEstimator.estimate(any(), any(), any(), any(), anyLong()))
            .thenReturn(com.clougence.clouddm.console.web.component.governance.SqlRowEstimator.EstimationResult.unknown());
        ReflectionTestUtils.setField(ticketService, "sqlRowEstimator", sqlRowEstimator);
        ReflectionTestUtils.setField(ticketService, "dingBridgeOutboundService", bridge);
    }

    @Test
    public void submit_externalDirect_triggersOutboundOnNewTicket() {
        setUpSubmitFixture();
        stubEnvChannel("External");

        ticketService.submit(PUID, UID, submitFO("DIRECT"));

        verify(bridge).triggerCreateFlow(PUID, 999L);
    }

    @Test
    public void submit_externalViaRelease_neverTriggers_boundary14Pin() {
        setUpSubmitFixture();
        stubEnvChannel("External");

        ticketService.submit(PUID, UID, submitFO("VIA_RELEASE"));

        verifyNoInteractions(bridge);
    }

    @Test
    public void submit_internalDirect_neverTriggers_boundary13Pin() {
        setUpSubmitFixture();
        stubEnvChannel("Internal");

        ticketService.submit(PUID, UID, submitFO("DIRECT"));

        verifyNoInteractions(bridge);
    }

    @Test
    public void submit_envUnconfigured_neverTriggers() {
        setUpSubmitFixture();
        // dmEnvParamService returns null → approvalType stays Internal (legacy default)

        ticketService.submit(PUID, UID, submitFO("DIRECT"));

        verifyNoInteractions(bridge);
    }

    @Test
    public void submit_dingtalkDirect_neverTriggers_pluginChannelPin() {
        setUpSubmitFixture();
        stubEnvChannel("DingTalk");
        DmApprovalTemplateDO templateDO = new DmApprovalTemplateDO();
        templateDO.setTemplateName("tpl");
        when(approvalFlowService.checkApprovalAndReturnTemplate(eq(PUID), eq(ApprovalType.DingTalk), eq("tpl-1"), any()))
            .thenReturn(templateDO);

        ticketService.submit(PUID, UID, submitFO("DIRECT"));

        verifyNoInteractions(bridge);
    }

    @Test
    public void submit_bridgeTriggerCrash_neverBreaksSubmission() {
        setUpSubmitFixture();
        stubEnvChannel("External");
        when(bridge.triggerCreateFlow(anyString(), anyLong())).thenThrow(new RuntimeException("bridge layer exploded"));

        // the submit transaction must survive an outbound-trigger crash (never-throw contract)
        assertNotNull(ticketService.submit(PUID, UID, submitFO("DIRECT")));
    }

    // ==================== hook ②: createRelease ====================

    private void setUpReleaseFixture() {
        releaseService = new ProdReleaseServiceImpl();

        ExecutionDal staticExecDal = mock(ExecutionDal.class);
        DmExecAutoJobMapper staticJobMapper = mock(DmExecAutoJobMapper.class);
        DmExecAutoTaskMapper staticTaskMapper = mock(DmExecAutoTaskMapper.class);
        when(staticExecDal.autoJobMapper()).thenReturn(staticJobMapper);
        when(staticExecDal.autoTaskMapper()).thenReturn(staticTaskMapper);
        when(staticJobMapper.queryByBizId(anyString())).thenReturn(null);
        when(staticTaskMapper.queryByBizId(anyString())).thenReturn(null);
        ReflectionTestUtils.setField(com.clougence.clouddm.console.web.util.DmTeamUtils.class, "executionDal", staticExecDal);

        ProdReleaseDal prodReleaseDal = mock(ProdReleaseDal.class);
        releaseMapper = mock(DmProdReleaseMapper.class);
        releaseStmtMapper = mock(DmProdReleaseStmtMapper.class);
        DmProdReleaseStmtSchemaMapper prodSchemaMapper = mock(DmProdReleaseStmtSchemaMapper.class);
        when(prodReleaseDal.releaseMapper()).thenReturn(releaseMapper);
        when(prodReleaseDal.stmtMapper()).thenReturn(releaseStmtMapper);
        when(prodReleaseDal.schemaMapper()).thenReturn(prodSchemaMapper);
        // ADR-013 M1: frozen segment manifest mapper (createRelease propagation)
        when(prodReleaseDal.segmentMapper()).thenReturn(mock(
            com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseStmtSegmentMapper.class));

        TicketDbStmtDal ticketDbStmtDal = mock(TicketDbStmtDal.class);
        ticketStmtMapper = mock(DmTicketDbStmtMapper.class);
        DmTicketStmtSchemaMapper ticketSchemaMapper = mock(DmTicketStmtSchemaMapper.class);
        when(ticketDbStmtDal.stmtMapper()).thenReturn(ticketStmtMapper);
        when(ticketDbStmtDal.schemaMapper()).thenReturn(ticketSchemaMapper);
        when(ticketSchemaMapper.queryByStmtId(anyLong())).thenReturn(java.util.Collections.emptyList());

        ApprovalDal approvalDal = mock(ApprovalDal.class);
        releaseApprovalMapper = mock(DmApprovalMapper.class);
        when(approvalDal.approvalMapper()).thenReturn(releaseApprovalMapper);

        DbPairDal dbPairDal = mock(DbPairDal.class);
        DmDbPairMapper pairMapper = mock(DmDbPairMapper.class);
        when(dbPairDal.pairMapper()).thenReturn(pairMapper);
        DmDbPairDO pair = new DmDbPairDO();
        pair.setId(300L);
        pair.setProdDsId(11L);
        pair.setProdDbName("prod_db_a");
        pair.setStatus("ENABLED");
        when(pairMapper.selectById(300L)).thenReturn(pair);

        DataSourceDal dsDal = mock(DataSourceDal.class);
        DmDsMapper dsMapper = mock(DmDsMapper.class);
        when(dsDal.dsMapper()).thenReturn(dsMapper);
        DmDsDO prodDs = new DmDsDO();
        prodDs.setDsEnvId(1L);
        when(dsMapper.queryDsIdentityById(11L)).thenReturn(prodDs);

        approvalControlService = mock(ApprovalControlService.class);
        DbChangeEventDal dbChangeEventDal = mock(DbChangeEventDal.class);
        releaseEventMapper = mock(DmDbChangeEventMapper.class);
        when(dbChangeEventDal.eventMapper()).thenReturn(releaseEventMapper);
        when(releaseEventMapper.insert(any(DmDbChangeEventDO.class))).thenReturn(1);

        ReleaseSchemaPrecheckService precheckService = mock(ReleaseSchemaPrecheckService.class);
        when(precheckService.precheck(any())).thenReturn(PrecheckConclusion.pass());
        when(precheckService.extractTables(anyString())).thenReturn(java.util.Collections.emptySet());
        ReleaseSchemaDiffService diffService = mock(ReleaseSchemaDiffService.class);
        when(diffService.diff(any())).thenReturn(PrecheckConclusion.pass());

        ReflectionTestUtils.setField(releaseService, "prodReleaseDal", prodReleaseDal);
        ReflectionTestUtils.setField(releaseService, "dbChangeEventDal", dbChangeEventDal);
        ReflectionTestUtils.setField(releaseService, "ticketDbStmtDal", ticketDbStmtDal);
        ReflectionTestUtils.setField(releaseService, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(releaseService, "dsDal", dsDal);
        ReflectionTestUtils.setField(releaseService, "dbPairDal", dbPairDal);
        ReflectionTestUtils.setField(releaseService, "approvalControlService", approvalControlService);
        ReflectionTestUtils.setField(releaseService, "releaseStateMachine", mock(ProdReleaseStateMachine.class));
        ReflectionTestUtils.setField(releaseService, "dmAuthServiceForBiz", mock(DmAuthServiceForBiz.class));
        ReflectionTestUtils.setField(releaseService, "autoExecService", mock(AutoExecService.class));
        ReflectionTestUtils.setField(releaseService, "approvalStateService", mock(ApprovalStateService.class));
        ReflectionTestUtils.setField(releaseService, "releaseSchemaPrecheckService", precheckService);
        ReflectionTestUtils.setField(releaseService, "releaseSchemaDiffService", diffService);
        ReflectionTestUtils.setField(releaseService, "govReturnNotifyService", mock(GovReturnNotifyService.class));
        ReflectionTestUtils.setField(releaseService, "riskAssessmentService",
            new com.clougence.clouddm.console.web.component.governance.RiskAssessmentService());
        ReflectionTestUtils.setField(releaseService, "dingBridgeOutboundService", bridge);
    }

    @Test
    public void createRelease_triggersOutboundOnReleaseApprovalTicket() {
        setUpReleaseFixture();
        long ticketId = 130L;
        stubMergeableSourceTicket(ticketId, 230L);

        long releaseId = releaseService.createRelease(PUID, UID, createFO(ticketId));

        assertEquals(600L, releaseId);
        // the hook fires on the DM_PROD_RELEASE approval ticket id (700) — channel/switch guards
        // live inside the service (single point covering console + open entries)
        verify(bridge).triggerCreateFlow(PUID, 700L);
    }

    @Test
    public void createRelease_bridgeTriggerCrash_neverBreaksMerge() {
        setUpReleaseFixture();
        long ticketId = 131L;
        stubMergeableSourceTicket(ticketId, 231L);
        when(bridge.triggerCreateFlow(anyString(), anyLong())).thenThrow(new RuntimeException("boom"));

        assertEquals(600L, releaseService.createRelease(PUID, UID, createFO(ticketId)));
    }

    // ==================== hook ② M4b: skipOutbound (open entry only) ====================

    @Test
    public void createRelease_skipOutbound_neverTriggers_openEntryM4bPin() {
        // M4b (user ruling 2026-10-10): path-1 = the bridge converts ledger entries into a
        // release AFTER the DingTalk approval already completed inside its own flow — the open
        // /releases/create entry passes skipOutbound=true and the outbound hook is suppressed
        // explicitly (zero bridge interaction), replacing the "bridge-side dedup" wording.
        setUpReleaseFixture();
        long ticketId = 132L;
        stubMergeableSourceTicket(ticketId, 232L);

        long releaseId = releaseService.createRelease(PUID, UID, createFO(ticketId), true);

        assertEquals(600L, releaseId);
        verifyNoInteractions(bridge);
    }

    @Test
    public void createRelease_skipOutbound_onlySuppressesOutbound_chainUnchangedM4bPin() {
        // skipOutbound affects ONLY Step 11: release row, stmt snapshot, approval ticket creation,
        // ticketInfo writeback, approval_id backfill and the RELEASE_CREATED event all land
        // identically — the release stays fully drivable by the inbound command plane.
        setUpReleaseFixture();
        long ticketId = 133L;
        stubMergeableSourceTicket(ticketId, 233L);

        long releaseId = releaseService.createRelease(PUID, UID, createFO(ticketId), true);

        assertEquals(600L, releaseId);
        verify(releaseMapper).insert(any(DmProdReleaseDO.class));
        verify(releaseStmtMapper).insert(any(DmProdReleaseStmtDO.class));
        verify(approvalControlService).createSqlTicket(any(), any(), any(), eq(ApprovalBiz.DM_PROD_RELEASE));
        verify(releaseApprovalMapper).updateById(any(DmApprovalDO.class)); // ticketInfo releaseId/releaseNo writeback
        verify(releaseMapper).updateApprovalId(600L, 700L);
        verify(releaseEventMapper).insert(any(DmDbChangeEventDO.class)); // RELEASE_CREATED event
        verifyNoInteractions(bridge);
    }

    @Test
    public void createRelease_skipOutboundFalse_stillTriggers_openEntryDefaultM4bPin() {
        // open entry with explicit false (= absent flag, GovOpenApiServiceImpl maps null→false)
        // → outbound fires exactly as before (console 3-arg pin above covers the page entry).
        setUpReleaseFixture();
        long ticketId = 134L;
        stubMergeableSourceTicket(ticketId, 234L);

        assertEquals(600L, releaseService.createRelease(PUID, UID, createFO(ticketId), false));

        verify(bridge).triggerCreateFlow(PUID, 700L);
    }

    // ==================== helpers ====================

    private void stubEnvChannel(String type) {
        DmEnvParamTicketDesVO cfg = DmEnvParamTicketDesVO.builder()
            .openTicket(true)
            .type(type)
            .templateId("tpl-1")
            .build();
        when(dmEnvParamService.querySqlTicketInfoParam(anyString(), anyLong())).thenReturn(cfg);
    }

    private void stubMergeableSourceTicket(long ticketId, long stmtId) {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(ticketId);
        ticket.setTicketStatus(com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus.FINISHED);
        ticket.setPrimaryUid(PUID);
        ticket.setOwnerUid(UID);
        ticket.setTicketTitle("source " + ticketId);
        ticket.setGmtCreate(new java.util.Date(ticketId * 1000L));
        ApprovalMO mo = new ApprovalMO();
        mo.setTicketType("PRE_DDL");
        ticket.setTicketInfo(JsonUtils.toJson(mo));
        when(releaseApprovalMapper.queryById(ticketId)).thenReturn(ticket);

        DmTicketDbStmtDO stmt = new DmTicketDbStmtDO();
        stmt.setId(stmtId);
        stmt.setTicketId(ticketId);
        stmt.setPairId(300L);
        stmt.setDbName("pre_db");
        stmt.setSqlContent("CREATE TABLE t(id int);");
        stmt.setExecStatus("SUCCESS");
        stmt.setPrecheckResult("{\"checkStatus\":\"PASS\",\"changeType\":\"DDL\"}");
        when(ticketStmtMapper.queryByTicketId(ticketId)).thenReturn(List.of(stmt));
        when(releaseStmtMapper.queryBySourceStmtId(stmtId)).thenReturn(null);
        when(releaseMapper.countActiveByProdDb(11L, "prod_db_a")).thenReturn(0);
        when(releaseMapper.insert(any(DmProdReleaseDO.class))).thenAnswer(inv -> {
            inv.getArgument(0, DmProdReleaseDO.class).setId(600L);
            return 1;
        });
        when(releaseStmtMapper.insert(any(DmProdReleaseStmtDO.class))).thenAnswer(inv -> {
            inv.getArgument(0, DmProdReleaseStmtDO.class).setId(800L);
            return 1;
        });

        DmTicketResultVO ticketResult = new DmTicketResultVO();
        ticketResult.setTicketId(700L);
        when(approvalControlService.createSqlTicket(any(), any(), any(), eq(ApprovalBiz.DM_PROD_RELEASE)))
            .thenReturn(ticketResult);
        DmApprovalDO newApproval = new DmApprovalDO();
        newApproval.setId(700L);
        newApproval.setTicketInfo(JsonUtils.toJson(new ApprovalMO()));
        when(releaseApprovalMapper.queryById(700L)).thenReturn(newApproval);
    }

    private static ProdReleaseCreateFO createFO(long ticketId) {
        ProdReleaseCreateFO fo = new ProdReleaseCreateFO();
        fo.setTicketIds(List.of(ticketId));
        return fo;
    }

    private static GovTicketV2SubmitFO submitFO(String approvalMode) {
        GovTicketV2SubmitFO fo = new GovTicketV2SubmitFO();
        fo.setTicketType("PROD_DML");
        fo.setTicketTitle("test ticket");
        fo.setDescription("desc");
        fo.setApprovalMode(approvalMode);
        GovTicketV2SubmitFO.GroupInput gi = new GovTicketV2SubmitFO.GroupInput();
        gi.setPairId(300L);
        gi.setSqlContent("UPDATE t SET val=1 WHERE id=2;");
        fo.setGroups(List.of(gi));
        return fo;
    }

    private static SplitScript dmlSplitScript() {
        SplitScript script = new SplitScript();
        script.setIndex(1);
        script.setType(EnumSet.of(SplitQueryType.UPDATE));
        script.setScript("UPDATE t SET val=1 WHERE id=2;");
        return script;
    }
}
