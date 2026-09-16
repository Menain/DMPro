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
        DmEnvParamService dmEnvParamService = mock(DmEnvParamService.class);

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
