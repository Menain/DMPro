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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.console.web.component.approval.ApprovalFlowService;
import com.clougence.clouddm.console.web.component.approval.ApprovalPersonService;
import com.clougence.clouddm.console.web.component.approval.ApprovalStateService;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalStageMO;
import com.clougence.clouddm.console.web.component.approval.model.ExternalCommandMeta;
import com.clougence.clouddm.console.web.component.execute.AutoExecService;
import com.clougence.clouddm.console.web.component.governance.GovReturnNotifyService;
import com.clougence.clouddm.console.web.component.governance.RollbackSqlGenerator;
import com.clougence.clouddm.console.web.component.governance.SchemaSnapshotService;
import com.clougence.clouddm.console.web.global.i18n.DmI18nUtils;
import com.clougence.clouddm.console.web.model.fo.govticket.GovTicketV2ResubmitFO;
import com.clougence.clouddm.console.web.model.vo.govticket.GovTicketV2CheckVO;
import com.clougence.clouddm.console.web.model.fo.ticket.DmAutoExecConfigFO;
import com.clougence.clouddm.console.web.service.dbpair.DbPairService;
import com.clougence.clouddm.console.web.service.envparam.DmEnvParamService;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.DbChangeEventDal;
import com.clougence.clouddm.platform.dal.access.DataSourceDal;
import com.clougence.clouddm.platform.dal.access.ExecutionDal;
import com.clougence.clouddm.platform.dal.access.NamingDao;
import com.clougence.clouddm.platform.dal.access.ProdReleaseDal;
import com.clougence.clouddm.platform.dal.access.SystemDal;
import com.clougence.clouddm.platform.dal.access.TicketDbStmtDal;
import com.clougence.clouddm.platform.dal.access.DbPairDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangeEventMapper;
import com.clougence.clouddm.platform.dal.mapper.dbpair.DmDbPairMapper;
import com.clougence.clouddm.platform.dal.mapper.datasource.DmDsMapper;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoJobMapper;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoTaskMapper;
import com.clougence.clouddm.platform.dal.mapper.govticket.DmTicketDbStmtMapper;
import com.clougence.clouddm.platform.dal.mapper.govticket.DmTicketStmtRevisionMapper;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalProcessStatus;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStage;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalType;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.dbchange.ChangeType;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO;
import com.clougence.clouddm.platform.dal.model.dbchange.GovEventType;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketStmtRevisionDO;
import com.clougence.utils.JsonUtils;

/**
 * T8: External resubmit direct-to-WAIT_EXEC test.
 * <p>
 * Verifies:
 * - External PROD_DML DIRECT resubmit goes directly to WAIT_EXEC (Q2 方案 A)
 * - APPROVAL→FINISH, CONFIRM→FINISH, EXECUTION→INIT transitions
 * - initializeExecutionProgress called
 * - Per-group createGroupJob + startJob called
 * - SUBMIT event via=EXTERNAL_RESUBMIT, toStatus=WAIT_EXEC
 * - SYSTEM_CONFIRM event recorded
 * - Internal resubmit surgery (replacePersons + APPROVAL→INIT) regression pin
 * - VIA_RELEASE resubmit lands WAIT_RELEASE regression pin
 */
public class GovTicketV2ResubmitExternalTest {

    private GovTicketV2ServiceImpl service;

    private TicketDbStmtDal       ticketDbStmtDal;
    private DmTicketDbStmtMapper   stmtMapper;
    private DmTicketStmtRevisionMapper revisionMapper;
    private ApprovalDal            approvalDal;
    private DmApprovalMapper       approvalMapper;
    private ApprovalStateService   approvalStateService;
    private DbChangeEventDal       dbChangeEventDal;
    private DmDbChangeEventMapper  eventMapper;
    private AutoExecService        autoExecService;
    private SchemaSnapshotService  schemaSnapshotService;
    private DbPairService         dbPairService;
    private ApprovalFlowService    approvalFlowService;
    private ApprovalPersonService  approvalPersonService;
    private NamingDao              namingDao;
    private ProdReleaseDal         prodReleaseDal;

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

        // runPrecheck dependencies: DbPairDal, DataSourceDal, QueryAnalysisService
        DbPairDal dbPairDal = mock(DbPairDal.class);
        DmDbPairMapper pairMapper = mock(DmDbPairMapper.class);
        when(dbPairDal.pairMapper()).thenReturn(pairMapper);
        com.clougence.clouddm.platform.dal.model.dbpair.DmDbPairDO pair =
            new com.clougence.clouddm.platform.dal.model.dbpair.DmDbPairDO();
        pair.setId(1L);
        pair.setPreDsId(20L);
        pair.setPreDbName("pre_db");
        pair.setProdDsId(10L);
        pair.setProdDbName("prod_db");
        pair.setStatus("ENABLED");
        when(pairMapper.selectById(1L)).thenReturn(pair);

        DataSourceDal dsDal = mock(DataSourceDal.class);
        DmDsMapper dsMapper = mock(DmDsMapper.class);
        when(dsDal.dsMapper()).thenReturn(dsMapper);
        com.clougence.clouddm.platform.dal.model.datasource.DmDsDO dsDO =
            mock(com.clougence.clouddm.platform.dal.model.datasource.DmDsDO.class);
        when(dsDO.getDsEnvId()).thenReturn(1L);
        when(dsDO.getInstanceId()).thenReturn("inst-1");
        when(dsMapper.queryDsIdentityById(anyLong())).thenReturn(dsDO);

        com.clougence.clouddm.console.web.component.analysis.QueryAnalysisService queryAnalysisService =
            mock(com.clougence.clouddm.console.web.component.analysis.QueryAnalysisService.class);
        // Return a single DDL SplitScript so runPrecheck classifies the SQL as DDL and passes
        com.clougence.clouddm.sdk.sql.parser.SplitScript splitScript =
            new com.clougence.clouddm.sdk.sql.parser.SplitScript();
        splitScript.setType(java.util.EnumSet.of(com.clougence.clouddm.sdk.sql.parser.SplitQueryType.INSERT));
        splitScript.setScript("CREATE TABLE t2(id INT);");
        when(queryAnalysisService.analysisSplitStream(any(), any(), any(), anyInt(), anyInt()))
            .thenReturn(java.util.stream.Stream.of(splitScript));
        when(queryAnalysisService.analysisRulesStream(any(), any(), any(), anyInt(), anyInt(), any()))
            .thenReturn(java.util.stream.Stream.empty());

        ticketDbStmtDal = mock(TicketDbStmtDal.class);
        stmtMapper = mock(DmTicketDbStmtMapper.class);
        revisionMapper = mock(DmTicketStmtRevisionMapper.class);
        when(ticketDbStmtDal.stmtMapper()).thenReturn(stmtMapper);
        when(ticketDbStmtDal.revisionMapper()).thenReturn(revisionMapper);
        when(revisionMapper.maxVersionByStmtId(anyLong())).thenReturn(0);
        when(revisionMapper.insertRevision(any())).thenReturn(1);

        approvalDal = mock(ApprovalDal.class);
        approvalMapper = mock(DmApprovalMapper.class);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);
        when(approvalMapper.updateById(any(com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO.class))).thenReturn(1);

        approvalStateService = mock(ApprovalStateService.class);
        dbChangeEventDal = mock(DbChangeEventDal.class);
        eventMapper = mock(DmDbChangeEventMapper.class);
        when(dbChangeEventDal.eventMapper()).thenReturn(eventMapper);
        when(eventMapper.insert(any(DmDbChangeEventDO.class))).thenReturn(1);

        autoExecService = mock(AutoExecService.class);
        schemaSnapshotService = mock(SchemaSnapshotService.class);
        dbPairService = mock(DbPairService.class);
        approvalFlowService = mock(ApprovalFlowService.class);
        approvalPersonService = mock(ApprovalPersonService.class);
        namingDao = mock(NamingDao.class);
        prodReleaseDal = mock(ProdReleaseDal.class);

        ReflectionTestUtils.setField(service, "ticketDbStmtDal", ticketDbStmtDal);
        ReflectionTestUtils.setField(service, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(service, "approvalStateService", approvalStateService);
        ReflectionTestUtils.setField(service, "dbChangeEventDal", dbChangeEventDal);
        ReflectionTestUtils.setField(service, "autoExecService", autoExecService);
        ReflectionTestUtils.setField(service, "schemaSnapshotService", schemaSnapshotService);
        ReflectionTestUtils.setField(service, "dbPairService", dbPairService);
        ReflectionTestUtils.setField(service, "approvalFlowService", approvalFlowService);
        ReflectionTestUtils.setField(service, "approvalPersonService", approvalPersonService);
        ReflectionTestUtils.setField(service, "namingDao", namingDao);
        ReflectionTestUtils.setField(service, "prodReleaseDal", prodReleaseDal);
        ReflectionTestUtils.setField(service, "rollbackSqlGenerator", mock(RollbackSqlGenerator.class));
        // RiskAssessmentService + SqlRowEstimator (added by risk-level task)
        com.clougence.clouddm.console.web.component.governance.RiskAssessmentService riskAssessmentService
            = new com.clougence.clouddm.console.web.component.governance.RiskAssessmentService();
        ReflectionTestUtils.setField(riskAssessmentService, "dmEnvParamService",
            mock(com.clougence.clouddm.console.web.service.envparam.DmEnvParamService.class));
        ReflectionTestUtils.setField(service, "riskAssessmentService", riskAssessmentService);
        com.clougence.clouddm.console.web.component.governance.SqlRowEstimator sqlRowEstimator
            = mock(com.clougence.clouddm.console.web.component.governance.SqlRowEstimator.class);
        when(sqlRowEstimator.estimate(any(), any(), any(), any(), anyLong()))
            .thenReturn(com.clougence.clouddm.console.web.component.governance.SqlRowEstimator.EstimationResult.unknown());
        ReflectionTestUtils.setField(service, "sqlRowEstimator", sqlRowEstimator);

        // runPrecheck dependencies are declared above (dbPairDal, dsDal, queryAnalysisService)
        // and injected into the service here.
        ReflectionTestUtils.setField(service, "dbPairDal", dbPairDal);
        ReflectionTestUtils.setField(service, "dsDal", dsDal);
        ReflectionTestUtils.setField(service, "queryAnalysisService", queryAnalysisService);
        // dmDsConfigService: stub parseLevels to return a mock DsLevels (avoids NPE in runPrecheck L893-894)
        com.clougence.clouddm.console.web.component.dsconfig.DmDsConfigService dmDsConfigService =
            mock(com.clougence.clouddm.console.web.component.dsconfig.DmDsConfigService.class);
        when(dmDsConfigService.fetchDsConfigFromExists(anyLong())).thenReturn(null);
        when(dmDsConfigService.parseLevels(anyList())).thenReturn(
            mock(com.clougence.clouddm.console.web.component.dsconfig.mode.DsLevels.class));
        ReflectionTestUtils.setField(service, "dmDsConfigService", dmDsConfigService);
        ReflectionTestUtils.setField(service, "systemDal", mock(SystemDal.class));
    }

    // ==================== External resubmit direct-to-WAIT_EXEC ====================

    @Test
    public void externalResubmit_directToWaitExec_singleTransaction() {
        long ticketId = 100L;
        DmApprovalDO ticket = buildExternalRevisionTicket(ticketId, "PROD_DML", "DIRECT");
        when(approvalMapper.queryById(ticketId)).thenReturn(ticket);

        // Group setup: one FAILED group
        DmTicketDbStmtDO group = buildGroup(201L, ticketId, "FAILED", "CREATE TABLE t(id INT);");
        when(stmtMapper.queryByTicketId(ticketId)).thenReturn(Collections.singletonList(group));
        when(stmtMapper.updateSqlForRevision(anyLong(), anyString(), anyString())).thenReturn(1);

        // No stmt merged into release
        when(prodReleaseDal.stmtMapper()).thenReturn(mock(com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseStmtMapper.class));
        when(prodReleaseDal.stmtMapper().countBySourceStmtIds(anyList())).thenReturn(0);

        // Build resubmit FO
        GovTicketV2ResubmitFO fo = new GovTicketV2ResubmitFO();
        fo.setTicketId(ticketId);
        GovTicketV2ResubmitFO.GroupInput gi = new GovTicketV2ResubmitFO.GroupInput();
        gi.setGroupId(201L);
        gi.setSqlContent("CREATE TABLE t2(id INT);");
        fo.setGroups(Collections.singletonList(gi));

        // Execute
        service.resubmitOpenCommand(PUID, UID, fo);

        // Verify ticket → WAIT_EXEC
        ArgumentCaptor<ApprovalStatus> statusCaptor = ArgumentCaptor.forClass(ApprovalStatus.class);
        verify(approvalStateService).updateApprovalStatus(eq(ticketId), statusCaptor.capture(), anyString());
        assertEquals(ApprovalStatus.WAIT_EXEC, statusCaptor.getValue());

        // Verify APPROVAL process → FINISH
        verify(approvalStateService).updateProcessStatus(eq(ticketId), eq(ApprovalStage.APPROVAL),
            eq(ApprovalProcessStatus.FINISH), anyString());

        // Verify CONFIRM process → FINISH
        verify(approvalStateService).updateProcessStatus(eq(ticketId), eq(ApprovalStage.CONFIRM),
            eq(ApprovalProcessStatus.FINISH), anyString());

        // Verify EXECUTION process → INIT
        verify(approvalStateService).updateProcessStatus(eq(ticketId), eq(ApprovalStage.EXECUTION),
            eq(ApprovalProcessStatus.INIT), anyString());

        // Verify initializeExecutionProgress called
        verify(approvalStateService).initializeExecutionProgress(ticketId);

        // Verify markExecutionDispatched called
        verify(approvalStateService).markExecutionDispatched(ticket.getBizId());

        // Verify createGroupJob + startJob called
        verify(autoExecService).createGroupJob(eq(group), anyString(), anyBoolean(), any(), anyString(), eq("SYSTEM"));
        verify(autoExecService).startJob(anyString(), eq("SYSTEM"));

        // Verify NO replacePersons (External skips surgery)
        verify(approvalPersonService, never()).replacePersons(any(), any());

        // Verify NO APPROVAL→INIT reset (that's the surgery path, External skips it)
        verify(approvalStateService, never()).updateProcessStatus(eq(ticketId), eq(ApprovalStage.APPROVAL),
            eq(ApprovalProcessStatus.INIT), anyString());
    }

    @Test
    public void externalResubmit_eventsSubmittViaExternalResubmitAndSystemConfirm() {
        long ticketId = 100L;
        DmApprovalDO ticket = buildExternalRevisionTicket(ticketId, "PROD_DML", "DIRECT");
        when(approvalMapper.queryById(ticketId)).thenReturn(ticket);

        DmTicketDbStmtDO group = buildGroup(201L, ticketId, "FAILED", "CREATE TABLE t(id INT);");
        when(stmtMapper.queryByTicketId(ticketId)).thenReturn(Collections.singletonList(group));
        when(stmtMapper.updateSqlForRevision(anyLong(), anyString(), anyString())).thenReturn(1);
        when(prodReleaseDal.stmtMapper()).thenReturn(mock(com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseStmtMapper.class));
        when(prodReleaseDal.stmtMapper().countBySourceStmtIds(anyList())).thenReturn(0);

        GovTicketV2ResubmitFO fo = new GovTicketV2ResubmitFO();
        fo.setTicketId(ticketId);
        GovTicketV2ResubmitFO.GroupInput gi = new GovTicketV2ResubmitFO.GroupInput();
        gi.setGroupId(201L);
        gi.setSqlContent("CREATE TABLE t2(id INT);");
        fo.setGroups(Collections.singletonList(gi));

        service.resubmitOpenCommand(PUID, UID, fo);

        // Capture all events
        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper, atLeast(2)).insert(eventCaptor.capture());
        List<DmDbChangeEventDO> events = eventCaptor.getAllValues();

        // Find SUBMIT and SYSTEM_CONFIRM events
        DmDbChangeEventDO submitEvent = null;
        DmDbChangeEventDO confirmEvent = null;
        for (DmDbChangeEventDO e : events) {
            if (GovEventType.SUBMIT.name().equals(e.getEventType())) {
                submitEvent = e;
            } else if (GovEventType.SYSTEM_CONFIRM.name().equals(e.getEventType())) {
                confirmEvent = e;
            }
        }

        assertNotNull("SUBMIT event must be recorded", submitEvent);
        assertEquals("SUBMIT toStatus must be WAIT_EXEC", ApprovalStatus.WAIT_EXEC.name(), submitEvent.getToStatus());
        assertEquals("SUBMIT operator must be the caller uid", UID, submitEvent.getOperatorUid());
        Map<String, Object> submitData = JsonUtils.toObj(submitEvent.getEventData(), Map.class);
        assertEquals("EXTERNAL_RESUBMIT", submitData.get("via"));

        assertNotNull("SYSTEM_CONFIRM event must be recorded", confirmEvent);
        assertEquals("SYSTEM_CONFIRM fromStatus must be WAIT_CONFIRM", ApprovalStatus.WAIT_CONFIRM.name(), confirmEvent.getFromStatus());
        assertEquals("SYSTEM_CONFIRM toStatus must be WAIT_EXEC", ApprovalStatus.WAIT_EXEC.name(), confirmEvent.getToStatus());
        Map<String, Object> confirmData = JsonUtils.toObj(confirmEvent.getEventData(), Map.class);
        assertEquals("EXTERNAL_RESUBMIT", confirmData.get("via"));
    }

    // ==================== Internal resubmit regression pin ====================

    @Test
    public void internalResubmit_doesReplacePersonsAndApprovalInit_regressionPin() {
        long ticketId = 200L;
        DmApprovalDO ticket = buildInternalRevisionTicket(ticketId, "PROD_DML", "DIRECT");
        when(approvalMapper.queryById(ticketId)).thenReturn(ticket);

        DmTicketDbStmtDO group = buildGroup(201L, ticketId, "FAILED", "CREATE TABLE t(id INT);");
        when(stmtMapper.queryByTicketId(ticketId)).thenReturn(Collections.singletonList(group));
        when(stmtMapper.updateSqlForRevision(anyLong(), anyString(), anyString())).thenReturn(1);
        when(prodReleaseDal.stmtMapper()).thenReturn(mock(com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseStmtMapper.class));
        when(prodReleaseDal.stmtMapper().countBySourceStmtIds(anyList())).thenReturn(0);

        // Owner check passes (console path)
        ticket.setOwnerUid(UID);

        GovTicketV2ResubmitFO fo = new GovTicketV2ResubmitFO();
        fo.setTicketId(ticketId);
        GovTicketV2ResubmitFO.GroupInput gi = new GovTicketV2ResubmitFO.GroupInput();
        gi.setGroupId(201L);
        gi.setSqlContent("CREATE TABLE t2(id INT);");
        fo.setGroups(Collections.singletonList(gi));

        service.resubmit(PUID, UID, fo);

        // Verify ticket → WAIT_APPROVAL (NOT WAIT_EXEC)
        ArgumentCaptor<ApprovalStatus> statusCaptor = ArgumentCaptor.forClass(ApprovalStatus.class);
        verify(approvalStateService).updateApprovalStatus(eq(ticketId), statusCaptor.capture(), anyString());
        assertEquals(ApprovalStatus.WAIT_APPROVAL, statusCaptor.getValue());

        // Verify replacePersons called (surgery path)
        verify(approvalPersonService).replacePersons(any(), any());

        // Verify APPROVAL → INIT called (surgery path)
        verify(approvalStateService).updateProcessStatus(eq(ticketId), eq(ApprovalStage.APPROVAL),
            eq(ApprovalProcessStatus.INIT), anyString());

        // Verify NO createGroupJob (no direct-to-exec)
        verify(autoExecService, never()).createGroupJob(any(), anyString(), anyBoolean(), any(), anyString(), anyString());

        // Verify NO SYSTEM_CONFIRM event
        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        for (DmDbChangeEventDO e : eventCaptor.getAllValues()) {
            assertNotEquals("No SYSTEM_CONFIRM for Internal resubmit", GovEventType.SYSTEM_CONFIRM.name(), e.getEventType());
        }
    }

    // ==================== VIA_RELEASE regression pin ====================

    @Test
    public void viaReleaseResubmit_landsWaitRelease_regressionPin() {
        long ticketId = 300L;
        DmApprovalDO ticket = buildExternalRevisionTicket(ticketId, "PROD_DML", "VIA_RELEASE");
        when(approvalMapper.queryById(ticketId)).thenReturn(ticket);

        DmTicketDbStmtDO group = buildGroup(201L, ticketId, "FAILED", "CREATE TABLE t(id INT);");
        when(stmtMapper.queryByTicketId(ticketId)).thenReturn(Collections.singletonList(group));
        when(stmtMapper.updateSqlForRevision(anyLong(), anyString(), anyString())).thenReturn(1);
        when(prodReleaseDal.stmtMapper()).thenReturn(mock(com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseStmtMapper.class));
        when(prodReleaseDal.stmtMapper().countBySourceStmtIds(anyList())).thenReturn(0);

        GovTicketV2ResubmitFO fo = new GovTicketV2ResubmitFO();
        fo.setTicketId(ticketId);
        GovTicketV2ResubmitFO.GroupInput gi = new GovTicketV2ResubmitFO.GroupInput();
        gi.setGroupId(201L);
        gi.setSqlContent("CREATE TABLE t2(id INT);");
        fo.setGroups(Collections.singletonList(gi));

        service.resubmitOpenCommand(PUID, UID, fo);

        // Verify ticket → WAIT_RELEASE
        ArgumentCaptor<ApprovalStatus> statusCaptor = ArgumentCaptor.forClass(ApprovalStatus.class);
        verify(approvalStateService).updateApprovalStatus(eq(ticketId), statusCaptor.capture(), anyString());
        assertEquals(ApprovalStatus.WAIT_RELEASE, statusCaptor.getValue());

        // Verify NO createGroupJob (no direct-to-exec)
        verify(autoExecService, never()).createGroupJob(any(), anyString(), anyBoolean(), any(), anyString(), anyString());

        // Verify NO APPROVAL→FINISH (External branch not taken for VIA_RELEASE)
        verify(approvalStateService, never()).updateProcessStatus(eq(ticketId), eq(ApprovalStage.APPROVAL),
            eq(ApprovalProcessStatus.FINISH), anyString());
    }

    // ==================== Open command skips owner check ====================

    @Test
    public void openCommand_skipsOwnerCheck_nonOwnerSucceeds() {
        long ticketId = 400L;
        DmApprovalDO ticket = buildExternalRevisionTicket(ticketId, "PROD_DML", "DIRECT");
        // Owner is a DIFFERENT user
        ticket.setOwnerUid("different-owner-uid");
        when(approvalMapper.queryById(ticketId)).thenReturn(ticket);

        DmTicketDbStmtDO group = buildGroup(201L, ticketId, "FAILED", "CREATE TABLE t(id INT);");
        when(stmtMapper.queryByTicketId(ticketId)).thenReturn(Collections.singletonList(group));
        when(stmtMapper.updateSqlForRevision(anyLong(), anyString(), anyString())).thenReturn(1);
        when(prodReleaseDal.stmtMapper()).thenReturn(mock(com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseStmtMapper.class));
        when(prodReleaseDal.stmtMapper().countBySourceStmtIds(anyList())).thenReturn(0);

        GovTicketV2ResubmitFO fo = new GovTicketV2ResubmitFO();
        fo.setTicketId(ticketId);
        GovTicketV2ResubmitFO.GroupInput gi = new GovTicketV2ResubmitFO.GroupInput();
        gi.setGroupId(201L);
        gi.setSqlContent("CREATE TABLE t2(id INT);");
        fo.setGroups(Collections.singletonList(gi));

        // Open command should NOT throw — owner check skipped
        service.resubmitOpenCommand(PUID, UID, fo);

        // Verify ticket went to WAIT_EXEC
        ArgumentCaptor<ApprovalStatus> statusCaptor = ArgumentCaptor.forClass(ApprovalStatus.class);
        verify(approvalStateService).updateApprovalStatus(eq(ticketId), statusCaptor.capture(), anyString());
        assertEquals(ApprovalStatus.WAIT_EXEC, statusCaptor.getValue());
    }

    @Test
    public void consoleResubmit_ownerCheck_failsForNonOwner() {
        long ticketId = 500L;
        DmApprovalDO ticket = buildInternalRevisionTicket(ticketId, "PROD_DML", "DIRECT");
        ticket.setOwnerUid("different-owner-uid");
        when(approvalMapper.queryById(ticketId)).thenReturn(ticket);

        GovTicketV2ResubmitFO fo = new GovTicketV2ResubmitFO();
        fo.setTicketId(ticketId);
        GovTicketV2ResubmitFO.GroupInput gi = new GovTicketV2ResubmitFO.GroupInput();
        gi.setGroupId(201L);
        gi.setSqlContent("CREATE TABLE t2(id INT);");
        fo.setGroups(Collections.singletonList(gi));

        // Console path should throw — owner check enforced
        try {
            service.resubmit(PUID, UID, fo);
            fail("Expected ErrorMessageException for non-owner console resubmit");
        } catch (ErrorMessageException e) {
            // Expected
        }
    }

    // ==================== Helpers ====================

    private DmApprovalDO buildExternalRevisionTicket(long ticketId, String ticketType, String approvalMode) {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(ticketId);
        ticket.setBizId("biz-" + ticketId);
        ticket.setOwnerUid(UID);
        ticket.setPrimaryUid(PUID);
        ticket.setTicketStatus(ApprovalStatus.REVISION);
        ticket.setApproType(ApprovalType.External);
        ticket.setApproBiz(ApprovalBiz.DM_CHANGE);
        ticket.setBindDsId(10L);

        ApprovalMO mo = new ApprovalMO();
        mo.setTicketType(ticketType);
        mo.setApprovalMode(approvalMode);
        ticket.setTicketInfo(JsonUtils.toJson(mo));
        return ticket;
    }

    private DmApprovalDO buildInternalRevisionTicket(long ticketId, String ticketType, String approvalMode) {
        DmApprovalDO ticket = buildExternalRevisionTicket(ticketId, ticketType, approvalMode);
        ticket.setApproType(ApprovalType.Internal);
        return ticket;
    }

    private DmTicketDbStmtDO buildGroup(long groupId, long ticketId, String execStatus, String sqlContent) {
        DmTicketDbStmtDO group = new DmTicketDbStmtDO();
        group.setId(groupId);
        group.setTicketId(ticketId);
        group.setPairId(1L);
        group.setDsId(10L);
        group.setDbName("testdb");
        group.setSqlContent(sqlContent);
        group.setExecStatus(execStatus);
        // Precheck result with changeType=DDL
        group.setPrecheckResult("{\"changeType\":\"DDL\",\"checkStatus\":\"PASS\"}");
        return group;
    }
}
