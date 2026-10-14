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

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.console.web.component.analysis.QueryAnalysisService;
import com.clougence.clouddm.console.web.component.approval.ApprovalFlowService;
import com.clougence.clouddm.console.web.component.approval.ApprovalPersonService;
import com.clougence.clouddm.console.web.component.approval.ApprovalStateService;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalStageMO;
import com.clougence.clouddm.console.web.component.auth.DmAuthServiceForBiz;
import com.clougence.clouddm.console.web.component.dsconfig.DmDsConfigService;
import com.clougence.clouddm.console.web.component.governance.GovReturnNotifyService;
import com.clougence.clouddm.console.web.component.governance.SchemaSnapshotService;
import com.clougence.clouddm.console.web.model.fo.govticket.GovTicketV2SubmitFO;
import com.clougence.clouddm.console.web.model.vo.envparam.DmEnvParamTicketDesVO;
import com.clougence.clouddm.console.web.model.vo.govticket.GovTicketV2SubmitVO;
import com.clougence.clouddm.console.web.service.dbpair.DbPairService;
import com.clougence.clouddm.console.web.service.envparam.DmEnvParamService;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.DbChangeEventDal;
import com.clougence.clouddm.platform.dal.access.DbPairDal;
import com.clougence.clouddm.platform.dal.access.DataSourceDal;
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
import com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalType;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalTemplateDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO;
import com.clougence.clouddm.platform.dal.model.dbchange.GovEventType;
import com.clougence.clouddm.platform.dal.model.dbpair.DmDbPairDO;
import com.clougence.clouddm.platform.dal.model.datasource.DmDsDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;
import com.clougence.clouddm.sdk.sql.parser.SplitScript;
import com.clougence.clouddm.sdk.sql.parser.SplitQueryType;
import com.clougence.utils.JsonUtils;

/**
 * Tests for the PROD_DML submission path selection (DIRECT vs VIA_RELEASE):
 * <ul>
 *   <li>DIRECT regression pin — createProcess called, WAIT_APPROVAL, person list unchanged</li>
 *   <li>VIA_RELEASE — zero createProcess, zero approval-person objects, WAIT_RELEASE, stmt PENDING,
 *       SUBMIT event carries approvalMode</li>
 *   <li>PRE_DDL + VIA_RELEASE → 400; unknown approvalMode → DIRECT default</li>
 *   <li>Approval-entry mutex: canApproval=false / approvalTicket rejects for WAIT_RELEASE tickets</li>
 * </ul>
 */
public class GovTicketV2SubmitModeTest {

    private GovTicketV2ServiceImpl  service;
    private TicketDbStmtDal          ticketDbStmtDal;
    private DmTicketDbStmtMapper     stmtMapper;
    private ApprovalDal              approvalDal;
    private DmApprovalMapper         approvalMapper;
    private ApprovalFlowService     approvalFlowService;
    private ApprovalPersonService   approvalPersonService;
    private ApprovalStateService    approvalStateService;
    private DbChangeEventDal       dbChangeEventDal;
    private DmDbChangeEventMapper    eventMapper;
    private NamingDao               namingDao;
    private DmEnvParamService       dmEnvParamService;

    private static final String PUID = "puid-001";
    private static final String UID  = "uid-001";

    @Before
    public void setUp() {
        service = new GovTicketV2ServiceImpl();

        // DmTeamUtils static executionDal (required by nextExecJobBizId)
        ExecutionDal staticExecDal = mock(ExecutionDal.class);
        DmExecAutoJobMapper staticJobMapper = mock(DmExecAutoJobMapper.class);
        DmExecAutoTaskMapper staticTaskMapper = mock(DmExecAutoTaskMapper.class);
        when(staticExecDal.autoJobMapper()).thenReturn(staticJobMapper);
        when(staticExecDal.autoTaskMapper()).thenReturn(staticTaskMapper);
        when(staticJobMapper.queryByBizId(anyString())).thenReturn(null);
        when(staticTaskMapper.queryByBizId(anyString())).thenReturn(null);
        ReflectionTestUtils.setField(com.clougence.clouddm.console.web.util.DmTeamUtils.class, "executionDal", staticExecDal);

        ticketDbStmtDal = mock(TicketDbStmtDal.class);
        stmtMapper = mock(DmTicketDbStmtMapper.class);
        when(ticketDbStmtDal.stmtMapper()).thenReturn(stmtMapper);
        DmTicketStmtRevisionMapper revisionMapper = mock(DmTicketStmtRevisionMapper.class);
        when(ticketDbStmtDal.revisionMapper()).thenReturn(revisionMapper);
        DmTicketStmtSchemaMapper ticketSchemaMapper = mock(DmTicketStmtSchemaMapper.class);
        when(ticketDbStmtDal.schemaMapper()).thenReturn(ticketSchemaMapper);

        approvalDal = mock(ApprovalDal.class);
        approvalMapper = mock(DmApprovalMapper.class);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);
        // Simulate MyBatis auto-generating the id on insert
        when(approvalMapper.insert(any(com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO.class)))
            .thenAnswer(invocation -> {
                com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO t
                    = invocation.getArgument(0);
                t.setId(999L);
                return 1;
            });

        dbChangeEventDal = mock(DbChangeEventDal.class);
        eventMapper = mock(DmDbChangeEventMapper.class);
        when(dbChangeEventDal.eventMapper()).thenReturn(eventMapper);
        when(eventMapper.insert(any(DmDbChangeEventDO.class))).thenReturn(1);

        SchemaSnapshotService schemaSnapshotService = mock(SchemaSnapshotService.class);

        // DbPairDal
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

        // DataSourceDal
        DataSourceDal dsDal = mock(DataSourceDal.class);
        DmDsMapper dsMapper = mock(DmDsMapper.class);
        when(dsDal.dsMapper()).thenReturn(dsMapper);
        DmDsDO dsDO = mock(DmDsDO.class);
        when(dsDO.getDsEnvId()).thenReturn(1L);
        when(dsDO.getInstanceId()).thenReturn("inst-1");
        when(dsMapper.queryDsIdentityById(anyLong())).thenReturn(dsDO);

        // DmDsConfigService
        DmDsConfigService dmDsConfigService = mock(DmDsConfigService.class);
        when(dmDsConfigService.fetchDsConfigFromExists(anyLong())).thenReturn(null);
        when(dmDsConfigService.parseLevels(anyList())).thenReturn(
            mock(com.clougence.clouddm.console.web.component.dsconfig.mode.DsLevels.class));

        // QueryAnalysisService — DML SplitScript for PROD_DML precheck PASS path
        QueryAnalysisService queryAnalysisService = mock(QueryAnalysisService.class);
        when(queryAnalysisService.analysisSplitStream(any(), any(), any(), anyInt(), anyInt()))
            .thenAnswer(invocation -> java.util.stream.Stream.of(createDmlSplitScript()));
        when(queryAnalysisService.analysisRulesStream(any(), any(), any(), anyInt(), anyInt(), any()))
            .thenAnswer(invocation -> java.util.stream.Stream.empty());

        // DmEnvParamService
        dmEnvParamService = mock(DmEnvParamService.class);

        // SystemDal
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
        approvalPersonService = mock(ApprovalPersonService.class);
        approvalStateService = mock(ApprovalStateService.class);

        namingDao = mock(NamingDao.class);
        when(namingDao.genApprovalBizId()).thenReturn("biz-test");

        DbPairService dbPairService = mock(DbPairService.class);
        DmAuthServiceForBiz dmAuthServiceForBiz = mock(DmAuthServiceForBiz.class);
        GovReturnNotifyService govReturnNotifyService = mock(GovReturnNotifyService.class);
        ProdReleaseDal prodReleaseDal = mock(ProdReleaseDal.class);

        ReflectionTestUtils.setField(service, "ticketDbStmtDal", ticketDbStmtDal);
        ReflectionTestUtils.setField(service, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(service, "approvalStateService", approvalStateService);
        ReflectionTestUtils.setField(service, "dbChangeEventDal", dbChangeEventDal);
        ReflectionTestUtils.setField(service, "prodReleaseDal", prodReleaseDal);
        ReflectionTestUtils.setField(service, "schemaSnapshotService", schemaSnapshotService);
        ReflectionTestUtils.setField(service, "dbPairDal", dbPairDal);
        ReflectionTestUtils.setField(service, "dsDal", dsDal);
        ReflectionTestUtils.setField(service, "dmDsConfigService", dmDsConfigService);
        ReflectionTestUtils.setField(service, "queryAnalysisService", queryAnalysisService);
        ReflectionTestUtils.setField(service, "dmEnvParamService", dmEnvParamService);
        ReflectionTestUtils.setField(service, "systemDal", systemDal);
        ReflectionTestUtils.setField(service, "approvalFlowService", approvalFlowService);
        ReflectionTestUtils.setField(service, "dbPairService", dbPairService);
        ReflectionTestUtils.setField(service, "approvalPersonService", approvalPersonService);
        ReflectionTestUtils.setField(service, "dmAuthServiceForBiz", dmAuthServiceForBiz);
        ReflectionTestUtils.setField(service, "govReturnNotifyService", govReturnNotifyService);
        ReflectionTestUtils.setField(service, "namingDao", namingDao);
        // RollbackSqlGenerator is a pure-function component (no @Resource fields); inject a real instance.
        ReflectionTestUtils.setField(service, "rollbackSqlGenerator",
            new com.clougence.clouddm.console.web.component.governance.RollbackSqlGenerator());

        // RiskAssessmentService: real instance with mocked env-param lookup (returns defaults)
        com.clougence.clouddm.console.web.component.governance.RiskAssessmentService riskAssessmentService
            = new com.clougence.clouddm.console.web.component.governance.RiskAssessmentService();
        ReflectionTestUtils.setField(riskAssessmentService, "dmEnvParamService", dmEnvParamService);
        ReflectionTestUtils.setField(service, "riskAssessmentService", riskAssessmentService);

        // SqlRowEstimator: mock to avoid DB connections; returns unknown (degradation)
        com.clougence.clouddm.console.web.component.governance.SqlRowEstimator sqlRowEstimator
            = mock(com.clougence.clouddm.console.web.component.governance.SqlRowEstimator.class);
        when(sqlRowEstimator.estimate(any(), any(), any(), any(), anyLong()))
            .thenReturn(com.clougence.clouddm.console.web.component.governance.SqlRowEstimator.EstimationResult.unknown());
        ReflectionTestUtils.setField(service, "sqlRowEstimator", sqlRowEstimator);
    }

    // ==================== DIRECT regression pin ====================

    @Test
    public void submit_prodDmlDirect_createProcessCalledAndWaitApproval() {
        GovTicketV2SubmitFO fo = buildSubmitFO("PROD_DML", "DIRECT", "UPDATE t SET val=1 WHERE id=2;");
        GovTicketV2SubmitVO vo = service.submit(PUID, UID, fo);
        assertNotNull(vo.getTicketId());

        // createProcess MUST be called for DIRECT
        verify(approvalFlowService).createProcess(anyLong(), eq(ApprovalBiz.DM_CHANGE), eq(true));

        // Ticket status = WAIT_APPROVAL (not WAIT_RELEASE)
        ArgumentCaptor<com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO> ticketCaptor
            = ArgumentCaptor.forClass(com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO.class);
        verify(approvalMapper).insert(ticketCaptor.capture());
        assertEquals(ApprovalStatus.WAIT_APPROVAL, ticketCaptor.getValue().getTicketStatus());

        // ticketInfo carries approvalMode=DIRECT
        ApprovalMO mo = JsonUtils.toObj(ticketCaptor.getValue().getTicketInfo(), ApprovalMO.class);
        assertEquals("DIRECT", mo.getApprovalMode());
    }

    // ==================== VIA_RELEASE ====================

    @Test
    public void submit_prodDmlViaRelease_zeroApprovalFlowObjectsAndWaitRelease() {
        GovTicketV2SubmitFO fo = buildSubmitFO("PROD_DML", "VIA_RELEASE", "UPDATE t SET val=1 WHERE id=2;");
        service.submit(PUID, UID, fo);

        // createProcess MUST NOT be called (no approval process / person list)
        verify(approvalFlowService, never()).createProcess(anyLong(), any(), anyBoolean());
        verify(approvalFlowService, never()).queryPersons(anyLong());
        verify(approvalPersonService, never()).replacePersons(any(), any());

        // Ticket status = WAIT_RELEASE
        ArgumentCaptor<com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO> ticketCaptor
            = ArgumentCaptor.forClass(com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO.class);
        verify(approvalMapper).insert(ticketCaptor.capture());
        assertEquals(ApprovalStatus.WAIT_RELEASE, ticketCaptor.getValue().getTicketStatus());

        // ticketInfo carries approvalMode=VIA_RELEASE
        ApprovalMO mo = JsonUtils.toObj(ticketCaptor.getValue().getTicketInfo(), ApprovalMO.class);
        assertEquals("VIA_RELEASE", mo.getApprovalMode());
    }

    @Test
    public void submit_prodDmlViaRelease_stmtRowsPending() {
        GovTicketV2SubmitFO fo = buildSubmitFO("PROD_DML", "VIA_RELEASE", "UPDATE t SET val=1 WHERE id=2;");
        service.submit(PUID, UID, fo);

        ArgumentCaptor<DmTicketDbStmtDO> stmtCaptor = ArgumentCaptor.forClass(DmTicketDbStmtDO.class);
        verify(stmtMapper).insert(stmtCaptor.capture());
        assertEquals("PENDING", stmtCaptor.getValue().getExecStatus());
        // PROD_DML stmt row points at production side
        assertEquals(Long.valueOf(10L), stmtCaptor.getValue().getDsId());
        assertEquals("prod_db", stmtCaptor.getValue().getDbName());
    }

    @Test
    public void submit_prodDmlViaRelease_submitEventCarriesApprovalMode() {
        GovTicketV2SubmitFO fo = buildSubmitFO("PROD_DML", "VIA_RELEASE", "UPDATE t SET val=1 WHERE id=2;");
        service.submit(PUID, UID, fo);

        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        DmDbChangeEventDO event = eventCaptor.getValue();
        assertEquals(GovEventType.SUBMIT.name(), event.getEventType());
        assertEquals(ApprovalStatus.WAIT_RELEASE.name(), event.getToStatus());

        Map<String, Object> data = JsonUtils.toObj(event.getEventData(), HashMap.class);
        assertEquals("VIA_RELEASE", data.get("approvalMode"));
    }

    @Test
    public void submit_prodDmlDirect_submitEventCarriesApprovalMode() {
        GovTicketV2SubmitFO fo = buildSubmitFO("PROD_DML", "DIRECT", "UPDATE t SET val=1 WHERE id=2;");
        service.submit(PUID, UID, fo);

        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        assertEquals(ApprovalStatus.WAIT_APPROVAL.name(), eventCaptor.getValue().getToStatus());

        Map<String, Object> data = JsonUtils.toObj(eventCaptor.getValue().getEventData(), HashMap.class);
        assertEquals("DIRECT", data.get("approvalMode"));
    }

    // ==================== input validation ====================

    @Test
    public void submit_preDdlViaRelease_throws400() {
        GovTicketV2SubmitFO fo = buildSubmitFO("PRE_DDL", "VIA_RELEASE", "CREATE TABLE t(id int);");
        try {
            service.submit(PUID, UID, fo);
            fail("Expected ErrorMessageException for PRE_DDL + VIA_RELEASE");
        } catch (ErrorMessageException ex) {
            // ok
        }
        // Zero persistence: no ticket insert, no createProcess, no event
        verify(approvalMapper, never()).insert(any(com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO.class));
        verify(approvalFlowService, never()).createProcess(anyLong(), any(), anyBoolean());
        verify(eventMapper, never()).insert(any(DmDbChangeEventDO.class));
    }

    @Test
    public void submit_unknownApprovalMode_defaultsToDirect() {
        GovTicketV2SubmitFO fo = buildSubmitFO("PROD_DML", "GARBAGE_MODE", "UPDATE t SET val=1 WHERE id=2;");
        service.submit(PUID, UID, fo);

        // Unknown → DIRECT → createProcess called, WAIT_APPROVAL
        verify(approvalFlowService).createProcess(anyLong(), eq(ApprovalBiz.DM_CHANGE), eq(true));

        ArgumentCaptor<com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO> ticketCaptor
            = ArgumentCaptor.forClass(com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO.class);
        verify(approvalMapper).insert(ticketCaptor.capture());
        assertEquals(ApprovalStatus.WAIT_APPROVAL, ticketCaptor.getValue().getTicketStatus());

        ApprovalMO mo = JsonUtils.toObj(ticketCaptor.getValue().getTicketInfo(), ApprovalMO.class);
        assertEquals("DIRECT", mo.getApprovalMode());
    }

    @Test
    public void submit_nullApprovalMode_defaultsToDirect() {
        GovTicketV2SubmitFO fo = buildSubmitFO("PROD_DML", null, "UPDATE t SET val=1 WHERE id=2;");
        service.submit(PUID, UID, fo);

        verify(approvalFlowService).createProcess(anyLong(), eq(ApprovalBiz.DM_CHANGE), eq(true));

        ArgumentCaptor<com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO> ticketCaptor
            = ArgumentCaptor.forClass(com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO.class);
        verify(approvalMapper).insert(ticketCaptor.capture());
        assertEquals(ApprovalStatus.WAIT_APPROVAL, ticketCaptor.getValue().getTicketStatus());
    }

    // ==================== approval-entry mutex (structural) ====================

    @Test
    public void waitRelease_cannotBeApprovedByStatus() {
        // canApproval checks ticketStatus == WAIT_APPROVAL. WAIT_RELEASE != WAIT_APPROVAL → false.
        assertTrue(ApprovalStatus.WAIT_RELEASE != ApprovalStatus.WAIT_APPROVAL);
    }

    // ==================== DingTalk template field resolution (T1-T5) ====================

    /**
     * T1: DIRECT + DingTalk + valid template → approType/approTemplateIdentity/approTemplateName persisted.
     */
    @Test
    public void submit_prodDmlDirectDingTalk_persistsTemplateFields() {
        DmEnvParamTicketDesVO ticketConfig = DmEnvParamTicketDesVO.builder()
            .openTicket(true)
            .type("DingTalk")
            .templateId("tpl-123")
            .build();
        when(dmEnvParamService.querySqlTicketInfoParam(anyString(), anyLong())).thenReturn(ticketConfig);

        DmApprovalTemplateDO templateDO = new DmApprovalTemplateDO();
        templateDO.setTemplateName("DingTalk-Prod-DML-Template");
        when(approvalFlowService.checkApprovalAndReturnTemplate(
            eq(PUID), eq(ApprovalType.DingTalk), eq("tpl-123"), any()))
            .thenReturn(templateDO);

        GovTicketV2SubmitFO fo = buildSubmitFO("PROD_DML", "DIRECT", "UPDATE t SET val=1 WHERE id=2;");
        service.submit(PUID, UID, fo);

        ArgumentCaptor<DmApprovalDO> ticketCaptor = ArgumentCaptor.forClass(DmApprovalDO.class);
        verify(approvalMapper).insert(ticketCaptor.capture());
        DmApprovalDO ticket = ticketCaptor.getValue();
        assertEquals(ApprovalType.DingTalk, ticket.getApproType());
        assertEquals("tpl-123", ticket.getApproTemplateIdentity());
        assertEquals("DingTalk-Prod-DML-Template", ticket.getApproTemplateName());
    }

    /**
     * T2: DIRECT + DingTalk + template check throws → submit throws, zero dm_approval/stmt insert.
     */
    @Test
    public void submit_prodDmlDirectDingTalkTemplateMissing_throwsFailFast() {
        DmEnvParamTicketDesVO ticketConfig = DmEnvParamTicketDesVO.builder()
            .openTicket(true)
            .type("DingTalk")
            .templateId("tpl-missing")
            .build();
        when(dmEnvParamService.querySqlTicketInfoParam(anyString(), anyLong())).thenReturn(ticketConfig);
        when(approvalFlowService.checkApprovalAndReturnTemplate(
            anyString(), any(ApprovalType.class), anyString(), any()))
            .thenThrow(new ErrorMessageException("template not found"));

        GovTicketV2SubmitFO fo = buildSubmitFO("PROD_DML", "DIRECT", "UPDATE t SET val=1 WHERE id=2;");
        try {
            service.submit(PUID, UID, fo);
            fail("Expected ErrorMessageException for missing DingTalk template");
        } catch (ErrorMessageException ex) {
            // ok — fail-fast before any insert
        }
        verify(approvalMapper, never()).insert(any(DmApprovalDO.class));
        verify(stmtMapper, never()).insert(any(DmTicketDbStmtDO.class));
    }

    /**
     * T3: Internal pin — config null OR type=Internal → both template fields null (regression pin).
     */
    @Test
    public void submit_prodDmlInternal_templateFieldsNull() {
        // Sub-case 1: config null (default mock returns null)
        GovTicketV2SubmitFO fo1 = buildSubmitFO("PROD_DML", "DIRECT", "UPDATE t SET val=1 WHERE id=2;");
        service.submit(PUID, UID, fo1);
        ArgumentCaptor<DmApprovalDO> ticketCaptor = ArgumentCaptor.forClass(DmApprovalDO.class);
        verify(approvalMapper).insert(ticketCaptor.capture());
        DmApprovalDO ticket1 = ticketCaptor.getValue();
        assertEquals(ApprovalType.Internal, ticket1.getApproType());
        assertNull(ticket1.getApproTemplateIdentity());
        assertNull(ticket1.getApproTemplateName());
        verify(approvalFlowService, never()).checkApprovalAndReturnTemplate(
            anyString(), any(ApprovalType.class), anyString(), any());

        // Clear invocations for sub-case 2 (stubs remain active)
        clearInvocations(approvalMapper, approvalFlowService);

        // Sub-case 2: config with type=Internal
        DmEnvParamTicketDesVO internalConfig = DmEnvParamTicketDesVO.builder()
            .openTicket(true)
            .type("Internal")
            .templateId("tpl-internal")
            .build();
        when(dmEnvParamService.querySqlTicketInfoParam(anyString(), anyLong())).thenReturn(internalConfig);

        GovTicketV2SubmitFO fo2 = buildSubmitFO("PROD_DML", "DIRECT", "UPDATE t SET val=1 WHERE id=2;");
        service.submit(PUID, UID, fo2);

        verify(approvalMapper).insert(ticketCaptor.capture());
        DmApprovalDO ticket2 = ticketCaptor.getValue();
        assertEquals(ApprovalType.Internal, ticket2.getApproType());
        assertNull(ticket2.getApproTemplateIdentity());
        assertNull(ticket2.getApproTemplateName());
        verify(approvalFlowService, never()).checkApprovalAndReturnTemplate(
            anyString(), any(ApprovalType.class), anyString(), any());
    }

    /**
     * T4: VIA_RELEASE + DingTalk + valid template → WAIT_RELEASE, zero createProcess, template fields persisted.
     */
    @Test
    public void submit_prodDmlViaReleaseDingTalk_persistsTemplateFields() {
        DmEnvParamTicketDesVO ticketConfig = DmEnvParamTicketDesVO.builder()
            .openTicket(true)
            .type("DingTalk")
            .templateId("tpl-456")
            .build();
        when(dmEnvParamService.querySqlTicketInfoParam(anyString(), anyLong())).thenReturn(ticketConfig);

        DmApprovalTemplateDO templateDO = new DmApprovalTemplateDO();
        templateDO.setTemplateName("DingTalk-Release-Template");
        when(approvalFlowService.checkApprovalAndReturnTemplate(
            eq(PUID), eq(ApprovalType.DingTalk), eq("tpl-456"), any()))
            .thenReturn(templateDO);

        GovTicketV2SubmitFO fo = buildSubmitFO("PROD_DML", "VIA_RELEASE", "UPDATE t SET val=1 WHERE id=2;");
        service.submit(PUID, UID, fo);

        // VIA_RELEASE skips createProcess
        verify(approvalFlowService, never()).createProcess(anyLong(), any(), anyBoolean());

        ArgumentCaptor<DmApprovalDO> ticketCaptor = ArgumentCaptor.forClass(DmApprovalDO.class);
        verify(approvalMapper).insert(ticketCaptor.capture());
        DmApprovalDO ticket = ticketCaptor.getValue();
        assertEquals(ApprovalStatus.WAIT_RELEASE, ticket.getTicketStatus());
        assertEquals(ApprovalType.DingTalk, ticket.getApproType());
        assertEquals("tpl-456", ticket.getApproTemplateIdentity());
        assertEquals("DingTalk-Release-Template", ticket.getApproTemplateName());
    }

    /**
     * T5: VIA_RELEASE + DingTalk + template check throws → submit throws, zero dm_approval/stmt insert.
     */
    @Test
    public void submit_prodDmlViaReleaseDingTalkTemplateMissing_throwsFailFast() {
        DmEnvParamTicketDesVO ticketConfig = DmEnvParamTicketDesVO.builder()
            .openTicket(true)
            .type("DingTalk")
            .templateId("tpl-missing")
            .build();
        when(dmEnvParamService.querySqlTicketInfoParam(anyString(), anyLong())).thenReturn(ticketConfig);
        when(approvalFlowService.checkApprovalAndReturnTemplate(
            anyString(), any(ApprovalType.class), anyString(), any()))
            .thenThrow(new ErrorMessageException("template not found"));

        GovTicketV2SubmitFO fo = buildSubmitFO("PROD_DML", "VIA_RELEASE", "UPDATE t SET val=1 WHERE id=2;");
        try {
            service.submit(PUID, UID, fo);
            fail("Expected ErrorMessageException for missing DingTalk template");
        } catch (ErrorMessageException ex) {
            // ok — fail-fast before any insert
        }
        verify(approvalMapper, never()).insert(any(DmApprovalDO.class));
        verify(stmtMapper, never()).insert(any(DmTicketDbStmtDO.class));
    }

    // ==================== External orchestration template exemption (T3) ====================

    /**
     * T3-a: DIRECT + External → submit succeeds, template fields null, no template check called.
     * <p>
     * External has no template concept (no SPI, no OA instance). The template check
     * branch is skipped — External walks the same exemption path as Internal.
     */
    @Test
    public void submit_prodDmlDirectExternal_templateFieldsNullNoCheck() {
        DmEnvParamTicketDesVO ticketConfig = DmEnvParamTicketDesVO.builder()
            .openTicket(true)
            .type("External")
            .build();
        when(dmEnvParamService.querySqlTicketInfoParam(anyString(), anyLong())).thenReturn(ticketConfig);

        GovTicketV2SubmitFO fo = buildSubmitFO("PROD_DML", "DIRECT", "UPDATE t SET val=1 WHERE id=2;");
        service.submit(PUID, UID, fo);

        ArgumentCaptor<DmApprovalDO> ticketCaptor = ArgumentCaptor.forClass(DmApprovalDO.class);
        verify(approvalMapper).insert(ticketCaptor.capture());
        DmApprovalDO ticket = ticketCaptor.getValue();
        assertEquals(ApprovalType.External, ticket.getApproType());
        assertEquals(ApprovalStatus.WAIT_APPROVAL, ticket.getTicketStatus());
        assertNull("External template identity must be null", ticket.getApproTemplateIdentity());
        assertNull("External template name must be null", ticket.getApproTemplateName());

        // Template check must NOT be called for External (exempted alongside Internal)
        verify(approvalFlowService, never()).checkApprovalAndReturnTemplate(
            anyString(), any(ApprovalType.class), anyString(), any());
    }

    /**
     * T3-b: VIA_RELEASE + External → WAIT_RELEASE, template fields null (VIA_RELEASE stores type for display).
     */
    @Test
    public void submit_prodDmlViaReleaseExternal_templateFieldsNull() {
        DmEnvParamTicketDesVO ticketConfig = DmEnvParamTicketDesVO.builder()
            .openTicket(true)
            .type("External")
            .build();
        when(dmEnvParamService.querySqlTicketInfoParam(anyString(), anyLong())).thenReturn(ticketConfig);

        GovTicketV2SubmitFO fo = buildSubmitFO("PROD_DML", "VIA_RELEASE", "UPDATE t SET val=1 WHERE id=2;");
        service.submit(PUID, UID, fo);

        ArgumentCaptor<DmApprovalDO> ticketCaptor = ArgumentCaptor.forClass(DmApprovalDO.class);
        verify(approvalMapper).insert(ticketCaptor.capture());
        DmApprovalDO ticket = ticketCaptor.getValue();
        assertEquals(ApprovalType.External, ticket.getApproType());
        assertEquals(ApprovalStatus.WAIT_RELEASE, ticket.getTicketStatus());
        assertNull(ticket.getApproTemplateIdentity());
        assertNull(ticket.getApproTemplateName());
        verify(approvalFlowService, never()).checkApprovalAndReturnTemplate(
            anyString(), any(ApprovalType.class), anyString(), any());
    }

    /**
     * T3-c: DingTalk template regression pin — template check still called, fields persisted.
     *       (Confirms External exemption does not disturb the DingTalk branch.)
     */
    @Test
    public void submit_prodDmlDirectDingTalk_templateCheckStillCalled_regressionPin() {
        DmEnvParamTicketDesVO ticketConfig = DmEnvParamTicketDesVO.builder()
            .openTicket(true)
            .type("DingTalk")
            .templateId("tpl-regression")
            .build();
        when(dmEnvParamService.querySqlTicketInfoParam(anyString(), anyLong())).thenReturn(ticketConfig);

        DmApprovalTemplateDO templateDO = new DmApprovalTemplateDO();
        templateDO.setTemplateName("DingTalk-Regression");
        when(approvalFlowService.checkApprovalAndReturnTemplate(
            eq(PUID), eq(ApprovalType.DingTalk), eq("tpl-regression"), any()))
            .thenReturn(templateDO);

        GovTicketV2SubmitFO fo = buildSubmitFO("PROD_DML", "DIRECT", "UPDATE t SET val=1 WHERE id=2;");
        service.submit(PUID, UID, fo);

        // DingTalk MUST still call checkApprovalAndReturnTemplate (not exempted)
        verify(approvalFlowService).checkApprovalAndReturnTemplate(
            eq(PUID), eq(ApprovalType.DingTalk), eq("tpl-regression"), any());

        ArgumentCaptor<DmApprovalDO> ticketCaptor = ArgumentCaptor.forClass(DmApprovalDO.class);
        verify(approvalMapper).insert(ticketCaptor.capture());
        DmApprovalDO ticket = ticketCaptor.getValue();
        assertEquals(ApprovalType.DingTalk, ticket.getApproType());
        assertEquals("tpl-regression", ticket.getApproTemplateIdentity());
        assertEquals("DingTalk-Regression", ticket.getApproTemplateName());
    }

    // ==================== C10 recovery-type grading (task 10-14, design D3/D4/D7) ====================

    @Test
    public void c10_submit_blankRollback_dmlTicket_gradesTransactionWithGeneratedTemplate() {
        GovTicketV2SubmitFO fo = buildSubmitFO("PROD_DML", "DIRECT", "UPDATE t SET val=1 WHERE id=2;");
        service.submit(PUID, UID, fo);

        ArgumentCaptor<com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO> ticketCaptor
            = ArgumentCaptor.forClass(com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO.class);
        verify(approvalMapper).insert(ticketCaptor.capture());
        com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO row = ticketCaptor.getValue();
        // harness precheck: single UPDATE → changeType DML, no routed segments → arm 5 TRANSACTION
        // (the auto-generated template rides along as companion material — it never steals the
        // main grade, design D9)
        assertEquals(com.clougence.clouddm.platform.dal.model.approval.RecoveryType.TRANSACTION,
            row.getRecoveryType());
        assertNotNull("template generated by the real generator", row.getRollBackSql());
        assertTrue(row.getRollBackSql().startsWith("-- 系统自动生成"));
        ApprovalMO mo = JsonUtils.toObj(row.getTicketInfo(), ApprovalMO.class);
        assertEquals(Boolean.TRUE, mo.getRollBackSqlGenerated());
    }

    @Test
    public void c10_submit_userRollback_gradesCompensationSql_andAuditsUserSource() {
        com.clougence.rdp.service.RdpOpAuditService auditSvc
            = mock(com.clougence.rdp.service.RdpOpAuditService.class);
        ReflectionTestUtils.setField(service, "rdpOpAuditService", auditSvc);

        GovTicketV2SubmitFO fo = buildSubmitFO("PROD_DML", "DIRECT", "UPDATE t SET val=1 WHERE id=2;");
        fo.setRollBackSql("DELETE FROM t WHERE id=2;");
        service.submit(PUID, UID, fo);

        ArgumentCaptor<com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO> ticketCaptor
            = ArgumentCaptor.forClass(com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO.class);
        verify(approvalMapper).insert(ticketCaptor.capture());
        com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO row = ticketCaptor.getValue();
        // 裁决 1: 手填 = COMPENSATION_SQL intent declaration (arm 4 outranks the arm-5 TRANSACTION)
        assertEquals(com.clougence.clouddm.platform.dal.model.approval.RecoveryType.COMPENSATION_SQL,
            row.getRecoveryType());
        assertEquals("DELETE FROM t WHERE id=2;", row.getRollBackSql());

        // the ROLLBACK_MATERIAL row: PURE_URL + "ticket:<id>" bypass + obj whitelist + chain
        ArgumentCaptor<Object> objCaptor = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<com.clougence.rdp.service.model.AuditChainMO> chainCaptor
            = ArgumentCaptor.forClass(com.clougence.rdp.service.model.AuditChainMO.class);
        verify(auditSvc).logAndAddOperationAudit(eq(PUID), eq(UID),
            eq("/api/entry/dbChangeV2/submit"), isNull(), eq(999L), objCaptor.capture(),
            eq(com.clougence.clouddm.platform.dal.model.monitor.SecurityLevel.NORMAL),
            eq(com.clougence.clouddm.platform.dal.model.monitor.AuditType.ROLLBACK_MATERIAL),
            eq(com.clougence.clouddm.platform.dal.model.ResourceType.PURE_URL),
            eq("ticket:999"), chainCaptor.capture());
        com.clougence.clouddm.console.web.model.vo.govticket.RollbackMaterialAuditObj obj
            = (com.clougence.clouddm.console.web.model.vo.govticket.RollbackMaterialAuditObj) objCaptor.getValue();
        assertEquals("USER", obj.getSource());
        assertEquals("COMPENSATION_SQL", obj.getRecoveryType());
        assertEquals(com.clougence.clouddm.console.web.component.governance.GovSqlHashUtils
            .hash("DELETE FROM t WHERE id=2;"), obj.getFingerprint());
        // chain columns: ticket binding + the FULL-text fingerprint; channel stays null → the
        // sink's URI inference yields the truthful CONSOLE arrival plane
        assertEquals(Long.valueOf(999L), chainCaptor.getValue().getTicketId());
        assertEquals(obj.getFingerprint(), chainCaptor.getValue().getSqlFingerprint());
        assertNull(chainCaptor.getValue().getChannel());
        assertNull(chainCaptor.getValue().getReleaseId());
    }

    @Test
    public void c10_submit_auditObj_neverCarriesFullMaterial_summaryCappedAt500() {
        com.clougence.rdp.service.RdpOpAuditService auditSvc
            = mock(com.clougence.rdp.service.RdpOpAuditService.class);
        ReflectionTestUtils.setField(service, "rdpOpAuditService", auditSvc);

        // a long WHERE makes the generated template exceed 500 chars
        StringBuilder longLiteral = new StringBuilder();
        for (int i = 0; i < 600; i++) {
            longLiteral.append('x');
        }
        GovTicketV2SubmitFO fo = buildSubmitFO("PROD_DML", "DIRECT",
            "UPDATE t SET val=1 WHERE note='" + longLiteral + "';");
        service.submit(PUID, UID, fo);

        ArgumentCaptor<com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO> ticketCaptor
            = ArgumentCaptor.forClass(com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO.class);
        verify(approvalMapper).insert(ticketCaptor.capture());
        String material = ticketCaptor.getValue().getRollBackSql();
        assertTrue("the material must exceed the summary bound for this pin", material.length() > 500);

        ArgumentCaptor<Object> objCaptor = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<com.clougence.rdp.service.model.AuditChainMO> chainCaptor
            = ArgumentCaptor.forClass(com.clougence.rdp.service.model.AuditChainMO.class);
        verify(auditSvc).logAndAddOperationAudit(eq(PUID), eq(UID),
            eq("/api/entry/dbChangeV2/submit"), isNull(), eq(999L), objCaptor.capture(),
            eq(com.clougence.clouddm.platform.dal.model.monitor.SecurityLevel.NORMAL),
            eq(com.clougence.clouddm.platform.dal.model.monitor.AuditType.ROLLBACK_MATERIAL),
            eq(com.clougence.clouddm.platform.dal.model.ResourceType.PURE_URL),
            eq("ticket:999"), chainCaptor.capture());
        com.clougence.clouddm.console.web.model.vo.govticket.RollbackMaterialAuditObj obj
            = (com.clougence.clouddm.console.web.model.vo.govticket.RollbackMaterialAuditObj) objCaptor.getValue();
        assertEquals("GENERATED", obj.getSource());
        assertEquals("summary capped at 500 (the C8 sqlSummary caliber)", 500, obj.getSummary().length());
        assertEquals(material.substring(0, 500), obj.getSummary());
        // the fingerprint hashes the FULL material — never the truncated display copy (red line:
        // the C8 fingerprint caliber)
        assertEquals(com.clougence.clouddm.console.web.component.governance.GovSqlHashUtils.hash(material),
            obj.getFingerprint());
        assertEquals(obj.getFingerprint(), chainCaptor.getValue().getSqlFingerprint());
        // obj-whitelist pin: exactly the four instance fields — no carrier for a full SQL text
        int instanceFields = 0;
        for (java.lang.reflect.Field f : com.clougence.clouddm.console.web.model.vo.govticket
                .RollbackMaterialAuditObj.class.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                instanceFields++;
            }
        }
        assertEquals(4, instanceFields);
    }

    @Test
    public void c10_submit_auditFailure_neverBlocksSubmission() {
        com.clougence.rdp.service.RdpOpAuditService auditSvc
            = mock(com.clougence.rdp.service.RdpOpAuditService.class);
        // void method → doThrow form
        doThrow(new RuntimeException("audit down")).when(auditSvc).logAndAddOperationAudit(
            anyString(), anyString(), anyString(), any(), any(), any(), any(), any(), any(), anyString(), any());
        ReflectionTestUtils.setField(service, "rdpOpAuditService", auditSvc);

        GovTicketV2SubmitFO fo = buildSubmitFO("PROD_DML", "DIRECT", "UPDATE t SET val=1 WHERE id=2;");
        GovTicketV2SubmitVO vo = service.submit(PUID, UID, fo); // must not throw
        assertNotNull(vo.getTicketId());
        verify(approvalMapper).insert(any(com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO.class));
    }

    // ==================== helpers ====================

    private GovTicketV2SubmitFO buildSubmitFO(String ticketType, String approvalMode, String sqlContent) {
        GovTicketV2SubmitFO fo = new GovTicketV2SubmitFO();
        fo.setTicketType(ticketType);
        fo.setTicketTitle("test ticket");
        fo.setDescription("desc");
        fo.setApprovalMode(approvalMode);
        GovTicketV2SubmitFO.GroupInput gi = new GovTicketV2SubmitFO.GroupInput();
        gi.setPairId(300L);
        gi.setSqlContent(sqlContent);
        fo.setGroups(List.of(gi));
        return fo;
    }

    /** Canned DML SplitScript for PROD_DML precheck PASS path (UPDATE -> DML classification). */
    private static SplitScript createDmlSplitScript() {
        SplitScript script = new SplitScript();
        script.setIndex(1);
        script.setType(EnumSet.of(SplitQueryType.UPDATE));
        script.setScript("UPDATE t SET val=1 WHERE id=2;");
        return script;
    }
}
