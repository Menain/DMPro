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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.console.web.component.approval.ApprovalFlowService;
import com.clougence.clouddm.console.web.component.approval.ApprovalPersonService;
import com.clougence.clouddm.console.web.component.approval.ApprovalStateService;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.component.auth.DmAuthServiceForBiz;
import com.clougence.clouddm.console.web.component.bridge.DingBridgeOutboundService;
import com.clougence.clouddm.console.web.component.execute.AutoExecService;
import com.clougence.clouddm.console.web.component.governance.GovReturnNotifyService;
import com.clougence.clouddm.console.web.component.governance.PrecheckConclusion;
import com.clougence.clouddm.console.web.component.governance.ProdReleaseStateMachine;
import com.clougence.clouddm.console.web.component.governance.ReleaseSchemaDiffService;
import com.clougence.clouddm.console.web.component.governance.ReleaseSchemaPrecheckService;
import com.clougence.clouddm.console.web.component.governance.RollbackSqlGenerator;
import com.clougence.clouddm.console.web.component.governance.SchemaSnapshotService;
import com.clougence.clouddm.console.web.model.fo.govticket.GovTicketV2ResubmitFO;
import com.clougence.clouddm.console.web.model.fo.prodrelease.ProdReleaseResubmitFO;
import com.clougence.clouddm.console.web.model.vo.ticket.DmTicketResultVO;
import com.clougence.clouddm.console.web.service.approval.ApprovalControlService;
import com.clougence.clouddm.console.web.service.dbpair.DbPairService;
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
import com.clougence.clouddm.platform.dal.mapper.datasource.DmDsMapper;
import com.clougence.clouddm.platform.dal.mapper.dbpair.DmDbPairMapper;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoJobMapper;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoTaskMapper;
import com.clougence.clouddm.platform.dal.mapper.govticket.DmTicketDbStmtMapper;
import com.clougence.clouddm.platform.dal.mapper.govticket.DmTicketStmtRevisionMapper;
import com.clougence.clouddm.platform.dal.mapper.govticket.DmTicketStmtSchemaMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseStmtMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseStmtSchemaMapper;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalType;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.datasource.DmDsDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO;
import com.clougence.clouddm.platform.dal.model.dbpair.DmDbPairDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseStmtDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.ProdReleaseStatus;
import com.clougence.utils.JsonUtils;

/**
 * Task 10-09 Step 6 (design boundary #15, test matrix row 7): the approval-ROUND contract of
 * resubmission.
 * <p>
 * RELEASE resubmit rebuilds the approval surface (a NEW dm_approval row via createSqlTicket,
 * release.approval_id re-pointed): the round-2 row must carry NO stale appro_identity /
 * outbound state (clean by construction + the explicit reset materializes the contract so the
 * "write only when empty" flowId guard can never block round 2), and the outbound flows/create
 * re-triggers for the new round — console AND open-command entries (service single point).
 * <p>
 * TICKET External resubmit is the D5/Q2 direct-to-WAIT_EXEC path: the original external
 * approval STILL STANDS (execution failure ≠ approval rejected) — NO round reset, NO
 * re-trigger, appro_identity untouched (regression pin of the 09-16 delivered behavior).
 */
public class ResubmitOutboundResetTest {

    private static final String PUID = "puid-001";
    private static final String UID  = "uid-001";

    private DingBridgeOutboundService bridge;

    // ---- release fixture ----
    private ProdReleaseServiceImpl   releaseService;
    private DmProdReleaseMapper      releaseMapper;
    private DmProdReleaseStmtMapper  releaseStmtMapper;
    private DmTicketDbStmtMapper     ticketStmtMapper;
    private DmTicketStmtSchemaMapper ticketSchemaMapper;
    private DmApprovalMapper         approvalMapper;
    private DmDbPairMapper           pairMapper;
    private DmDsMapper               dsMapper;
    private ApprovalControlService   approvalControlService;
    private ProdReleaseStateMachine  releaseStateMachine;

    @Before
    public void setUp() {
        bridge = mock(DingBridgeOutboundService.class);
    }

    // ==================== release resubmit = new approval round ====================

    private void setUpReleaseFixture() {
        releaseService = new ProdReleaseServiceImpl();

        ExecutionDal staticExecDal = mock(ExecutionDal.class);
        DmExecAutoJobMapper staticJobMapper = mock(DmExecAutoJobMapper.class);
        when(staticExecDal.autoJobMapper()).thenReturn(staticJobMapper);
        ReflectionTestUtils.setField(com.clougence.clouddm.console.web.util.DmTeamUtils.class, "executionDal", staticExecDal);

        ProdReleaseDal prodReleaseDal = mock(ProdReleaseDal.class);
        releaseMapper = mock(DmProdReleaseMapper.class);
        releaseStmtMapper = mock(DmProdReleaseStmtMapper.class);
        DmProdReleaseStmtSchemaMapper schemaMapper = mock(DmProdReleaseStmtSchemaMapper.class);
        when(prodReleaseDal.releaseMapper()).thenReturn(releaseMapper);
        when(prodReleaseDal.stmtMapper()).thenReturn(releaseStmtMapper);
        when(prodReleaseDal.schemaMapper()).thenReturn(schemaMapper);

        TicketDbStmtDal ticketDbStmtDal = mock(TicketDbStmtDal.class);
        ticketStmtMapper = mock(DmTicketDbStmtMapper.class);
        ticketSchemaMapper = mock(DmTicketStmtSchemaMapper.class);
        when(ticketDbStmtDal.stmtMapper()).thenReturn(ticketStmtMapper);
        when(ticketDbStmtDal.schemaMapper()).thenReturn(ticketSchemaMapper);

        ApprovalDal approvalDal = mock(ApprovalDal.class);
        approvalMapper = mock(DmApprovalMapper.class);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);

        DbPairDal dbPairDal = mock(DbPairDal.class);
        pairMapper = mock(DmDbPairMapper.class);
        when(dbPairDal.pairMapper()).thenReturn(pairMapper);

        DataSourceDal dsDal = mock(DataSourceDal.class);
        dsMapper = mock(DmDsMapper.class);
        when(dsDal.dsMapper()).thenReturn(dsMapper);

        approvalControlService = mock(ApprovalControlService.class);
        releaseStateMachine = mock(ProdReleaseStateMachine.class);
        DbChangeEventDal dbChangeEventDal = mock(DbChangeEventDal.class);
        DmDbChangeEventMapper eventMapper = mock(DmDbChangeEventMapper.class);
        when(dbChangeEventDal.eventMapper()).thenReturn(eventMapper);
        when(eventMapper.insert(any(DmDbChangeEventDO.class))).thenReturn(1);

        ReleaseSchemaPrecheckService precheckService = mock(ReleaseSchemaPrecheckService.class);
        when(precheckService.precheck(any())).thenReturn(PrecheckConclusion.pass());
        when(precheckService.extractTables(anyString())).thenReturn(Collections.emptySet());
        ReleaseSchemaDiffService diffService = mock(ReleaseSchemaDiffService.class);
        when(diffService.diff(any())).thenReturn(PrecheckConclusion.pass());

        ReflectionTestUtils.setField(releaseService, "prodReleaseDal", prodReleaseDal);
        ReflectionTestUtils.setField(releaseService, "dbChangeEventDal", dbChangeEventDal);
        ReflectionTestUtils.setField(releaseService, "ticketDbStmtDal", ticketDbStmtDal);
        ReflectionTestUtils.setField(releaseService, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(releaseService, "approvalControlService", approvalControlService);
        ReflectionTestUtils.setField(releaseService, "approvalStateService", mock(ApprovalStateService.class));
        ReflectionTestUtils.setField(releaseService, "approvalFlowService", mock(ApprovalFlowService.class));
        ReflectionTestUtils.setField(releaseService, "releaseStateMachine", releaseStateMachine);
        ReflectionTestUtils.setField(releaseService, "dbPairDal", dbPairDal);
        ReflectionTestUtils.setField(releaseService, "dsDal", dsDal);
        ReflectionTestUtils.setField(releaseService, "dmAuthServiceForBiz", mock(DmAuthServiceForBiz.class));
        ReflectionTestUtils.setField(releaseService, "releaseSchemaPrecheckService", precheckService);
        ReflectionTestUtils.setField(releaseService, "releaseSchemaDiffService", diffService);
        ReflectionTestUtils.setField(releaseService, "govReturnNotifyService", mock(GovReturnNotifyService.class));
        ReflectionTestUtils.setField(releaseService, "riskAssessmentService",
            new com.clougence.clouddm.console.web.component.governance.RiskAssessmentService());
        ReflectionTestUtils.setField(releaseService, "dingBridgeOutboundService", bridge);
    }

    private void stubResubmittableRelease(long releaseId) {
        DmProdReleaseDO release = new DmProdReleaseDO();
        release.setId(releaseId);
        release.setReleaseNo("REL-" + releaseId);
        release.setTitle("release title");
        release.setStatus(ProdReleaseStatus.RETURNED.name());
        release.setPrimaryUid(PUID);
        release.setCreatorUid(UID);
        release.setApprovalId(699L); // the OLD round approval row
        Map<String, Object> gateResult = new LinkedHashMap<>();
        gateResult.put("sourceTicketCount", 1);
        List<Map<String, Object>> stmts = new java.util.ArrayList<>();
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("sourceTicketId", 100L);
        s.put("sourceStmtId", 200L);
        stmts.add(s);
        gateResult.put("stmts", stmts);
        release.setGateResult(JsonUtils.toJson(gateResult));
        when(releaseMapper.selectByIdForUpdate(releaseId)).thenReturn(release);

        // source ticket 100 mergeable again
        DmApprovalDO source = new DmApprovalDO();
        source.setId(100L);
        source.setTicketStatus(ApprovalStatus.FINISHED);
        source.setPrimaryUid(PUID);
        source.setOwnerUid(UID);
        source.setGmtCreate(new java.util.Date(100_000L));
        ApprovalMO sourceMO = new ApprovalMO();
        sourceMO.setTicketType("PRE_DDL");
        source.setTicketInfo(JsonUtils.toJson(sourceMO));
        when(approvalMapper.queryById(100L)).thenReturn(source);

        DmTicketDbStmtDO stmt = new DmTicketDbStmtDO();
        stmt.setId(200L);
        stmt.setTicketId(100L);
        stmt.setPairId(300L);
        stmt.setDbName("pre_db");
        stmt.setSqlContent("CREATE TABLE t(id int);");
        stmt.setExecStatus("SUCCESS");
        when(ticketStmtMapper.queryByTicketId(100L)).thenReturn(List.of(stmt));
        when(releaseStmtMapper.queryBySourceStmtId(200L)).thenReturn(null);
        DmDbPairDO pair = new DmDbPairDO();
        pair.setId(300L);
        pair.setProdDsId(10L);
        pair.setProdDbName("prod_db");
        pair.setStatus("ENABLED");
        when(pairMapper.selectById(300L)).thenReturn(pair);
        when(releaseMapper.countActiveByProdDb(10L, "prod_db")).thenReturn(0);
        when(ticketSchemaMapper.queryByStmtId(200L)).thenReturn(Collections.emptyList());
        when(releaseStmtMapper.insert(any(DmProdReleaseStmtDO.class))).thenAnswer(inv -> {
            inv.getArgument(0, DmProdReleaseStmtDO.class).setId(800L);
            return 1;
        });
        DmDsDO prodDs = new DmDsDO();
        prodDs.setDsEnvId(1L);
        when(dsMapper.queryDsIdentityById(10L)).thenReturn(prodDs);

        // the NEW round approval row (round 2)
        DmTicketResultVO ticketResult = new DmTicketResultVO();
        ticketResult.setTicketId(700L);
        when(approvalControlService.createSqlTicket(any(), any(), any(), eq(ApprovalBiz.DM_PROD_RELEASE)))
            .thenReturn(ticketResult);
        DmApprovalDO newApproval = new DmApprovalDO();
        newApproval.setId(700L);
        newApproval.setTicketInfo(JsonUtils.toJson(new ApprovalMO()));
        when(approvalMapper.queryById(700L)).thenReturn(newApproval);
        when(releaseStateMachine.transit(eq(releaseId),
            eq(Set.of(ProdReleaseStatus.RETURNED)), eq(ProdReleaseStatus.APPROVING))).thenReturn(true);
    }

    @Test
    public void releaseResubmit_console_resetsRoundAndRetriggersOutbound_inOrder() {
        setUpReleaseFixture();
        stubResubmittableRelease(500L);

        releaseService.resubmitRelease(PUID, UID, true, resubmitFO(500L));

        InOrder order = inOrder(bridge);
        order.verify(bridge).resetForNewApprovalRound(700L); // clear appro_identity + outbound four columns
        order.verify(bridge).triggerCreateFlow(PUID, 700L);   // re-initiate for the new round
    }

    @Test
    public void releaseResubmit_openCommand_sameSinglePointCoverage() {
        setUpReleaseFixture();
        stubResubmittableRelease(501L);

        releaseService.resubmitReleaseOpenCommand(PUID, UID, resubmitFO(501L));

        verify(bridge).resetForNewApprovalRound(700L);
        verify(bridge).triggerCreateFlow(PUID, 700L);
    }

    @Test
    public void releaseResubmit_bridgeCrash_neverBreaksResubmit() {
        setUpReleaseFixture();
        stubResubmittableRelease(502L);
        when(bridge.triggerCreateFlow(anyString(), anyLong())).thenThrow(new RuntimeException("boom"));

        releaseService.resubmitRelease(PUID, UID, true, resubmitFO(502L)); // must not throw

        // the resubmit itself completed: RETURNED → APPROVING
        verify(releaseStateMachine).transit(eq(502L),
            eq(Set.of(ProdReleaseStatus.RETURNED)), eq(ProdReleaseStatus.APPROVING));
    }

    private static ProdReleaseResubmitFO resubmitFO(long releaseId) {
        ProdReleaseResubmitFO fo = new ProdReleaseResubmitFO();
        fo.setReleaseId(releaseId);
        fo.setTicketIds(List.of(100L));
        return fo;
    }

    // ==================== ticket External resubmit = original approval stands (D5 pin) ====================

    @Test
    public void ticketExternalResubmit_directToWaitExec_neverResetsNeverRetriggers() {
        GovTicketV2ServiceImpl ticketService = new GovTicketV2ServiceImpl();
        DmApprovalMapper tApprovalMapper = setUpTicketFixture(ticketService);

        long ticketId = 100L;
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(ticketId);
        ticket.setBizId("biz-100");
        ticket.setOwnerUid(UID);
        ticket.setPrimaryUid(PUID);
        ticket.setTicketStatus(ApprovalStatus.REVISION);
        ticket.setApproType(ApprovalType.External);
        ticket.setApproBiz(ApprovalBiz.DM_CHANGE);
        ticket.setBindDsId(10L);
        ticket.setApproIdentity("flow-original"); // the round-1 flow id MUST survive
        ApprovalMO mo = new ApprovalMO();
        mo.setTicketType("PROD_DML");
        mo.setApprovalMode("DIRECT");
        ticket.setTicketInfo(JsonUtils.toJson(mo));
        when(tApprovalMapper.queryById(ticketId)).thenReturn(ticket);

        DmTicketDbStmtDO group = new DmTicketDbStmtDO();
        group.setId(201L);
        group.setTicketId(ticketId);
        group.setPairId(1L);
        group.setDsId(10L);
        group.setDbName("testdb");
        group.setSqlContent("CREATE TABLE t(id INT);");
        group.setExecStatus("FAILED");
        group.setPrecheckResult("{\"changeType\":\"DDL\",\"checkStatus\":\"PASS\"}");
        when(stmtMapper.queryByTicketId(ticketId)).thenReturn(Collections.singletonList(group));
        when(stmtMapper.updateSqlForRevision(anyLong(), anyString(), anyString())).thenReturn(1);
        when(prodReleaseDal.stmtMapper()).thenReturn(mock(DmProdReleaseStmtMapper.class));
        when(prodReleaseDal.stmtMapper().countBySourceStmtIds(anyList())).thenReturn(0);

        GovTicketV2ResubmitFO fo = new GovTicketV2ResubmitFO();
        fo.setTicketId(ticketId);
        GovTicketV2ResubmitFO.GroupInput gi = new GovTicketV2ResubmitFO.GroupInput();
        gi.setGroupId(201L);
        gi.setSqlContent("CREATE TABLE t2(id INT);");
        fo.setGroups(Collections.singletonList(gi));

        ticketService.resubmitOpenCommand(PUID, UID, fo);

        // D5/Q2: direct-to-WAIT_EXEC, the original external approval still stands —
        // NO round reset, NO outbound re-trigger (boundary #15 applies to RELEASE resubmit only)
        verifyNoInteractions(bridge);
        ArgumentCaptor<ApprovalStatus> statusCaptor = ArgumentCaptor.forClass(ApprovalStatus.class);
        verify(approvalStateService).updateApprovalStatus(eq(ticketId), statusCaptor.capture(), anyString());
        assertEquals(ApprovalStatus.WAIT_EXEC, statusCaptor.getValue());
        // the persisted ticket update never touches appro_identity (the flow id survives the resubmit)
        ArgumentCaptor<DmApprovalDO> updateCaptor = ArgumentCaptor.forClass(DmApprovalDO.class);
        verify(tApprovalMapper).updateById(updateCaptor.capture());
        assertNull("resubmit must never clear appro_identity on the ticket path",
                updateCaptor.getValue().getApproIdentity());
        verify(tApprovalMapper, never()).clearOutboundForNewRound(anyLong());
    }

    private ApprovalStateService approvalStateService;
    private DmTicketDbStmtMapper   stmtMapper;
    private ProdReleaseDal         prodReleaseDal;

    /** GovTicketV2ResubmitExternalTest fixture copy (established duplication convention). */
    private DmApprovalMapper setUpTicketFixture(GovTicketV2ServiceImpl service) {
        ExecutionDal staticExecDal = mock(ExecutionDal.class);
        DmExecAutoJobMapper staticJobMapper = mock(DmExecAutoJobMapper.class);
        DmExecAutoTaskMapper staticTaskMapper = mock(DmExecAutoTaskMapper.class);
        when(staticExecDal.autoJobMapper()).thenReturn(staticJobMapper);
        when(staticExecDal.autoTaskMapper()).thenReturn(staticTaskMapper);
        when(staticJobMapper.queryByBizId(anyString())).thenReturn(null);
        when(staticTaskMapper.queryByBizId(anyString())).thenReturn(null);
        ReflectionTestUtils.setField(com.clougence.clouddm.console.web.util.DmTeamUtils.class, "executionDal", staticExecDal);

        DbPairDal dbPairDal = mock(DbPairDal.class);
        DmDbPairMapper tPairMapper = mock(DmDbPairMapper.class);
        when(dbPairDal.pairMapper()).thenReturn(tPairMapper);
        DmDbPairDO pair = new DmDbPairDO();
        pair.setId(1L);
        pair.setPreDsId(20L);
        pair.setPreDbName("pre_db");
        pair.setProdDsId(10L);
        pair.setProdDbName("prod_db");
        pair.setStatus("ENABLED");
        when(tPairMapper.selectById(1L)).thenReturn(pair);

        DataSourceDal dsDal = mock(DataSourceDal.class);
        DmDsMapper tDsMapper = mock(DmDsMapper.class);
        when(dsDal.dsMapper()).thenReturn(tDsMapper);
        DmDsDO dsDO = mock(DmDsDO.class);
        when(dsDO.getDsEnvId()).thenReturn(1L);
        when(dsDO.getInstanceId()).thenReturn("inst-1");
        when(tDsMapper.queryDsIdentityById(anyLong())).thenReturn(dsDO);

        com.clougence.clouddm.console.web.component.analysis.QueryAnalysisService queryAnalysisService
            = mock(com.clougence.clouddm.console.web.component.analysis.QueryAnalysisService.class);
        com.clougence.clouddm.sdk.sql.parser.SplitScript splitScript = new com.clougence.clouddm.sdk.sql.parser.SplitScript();
        splitScript.setType(java.util.EnumSet.of(com.clougence.clouddm.sdk.sql.parser.SplitQueryType.INSERT));
        splitScript.setScript("CREATE TABLE t2(id INT);");
        when(queryAnalysisService.analysisSplitStream(any(), any(), any(), anyInt(), anyInt()))
            .thenReturn(java.util.stream.Stream.of(splitScript));
        when(queryAnalysisService.analysisRulesStream(any(), any(), any(), anyInt(), anyInt(), any()))
            .thenReturn(java.util.stream.Stream.empty());

        TicketDbStmtDal ticketDbStmtDal = mock(TicketDbStmtDal.class);
        stmtMapper = mock(DmTicketDbStmtMapper.class);
        DmTicketStmtRevisionMapper revisionMapper = mock(DmTicketStmtRevisionMapper.class);
        when(ticketDbStmtDal.stmtMapper()).thenReturn(stmtMapper);
        when(ticketDbStmtDal.revisionMapper()).thenReturn(revisionMapper);
        when(revisionMapper.maxVersionByStmtId(anyLong())).thenReturn(0);
        when(revisionMapper.insertRevision(any())).thenReturn(1);

        ApprovalDal approvalDal = mock(ApprovalDal.class);
        DmApprovalMapper tApprovalMapper = mock(DmApprovalMapper.class);
        when(approvalDal.approvalMapper()).thenReturn(tApprovalMapper);
        when(tApprovalMapper.updateById(any(DmApprovalDO.class))).thenReturn(1);

        approvalStateService = mock(ApprovalStateService.class);
        DbChangeEventDal dbChangeEventDal = mock(DbChangeEventDal.class);
        DmDbChangeEventMapper eventMapper = mock(DmDbChangeEventMapper.class);
        when(dbChangeEventDal.eventMapper()).thenReturn(eventMapper);
        when(eventMapper.insert(any(DmDbChangeEventDO.class))).thenReturn(1);

        prodReleaseDal = mock(ProdReleaseDal.class);

        ReflectionTestUtils.setField(service, "ticketDbStmtDal", ticketDbStmtDal);
        ReflectionTestUtils.setField(service, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(service, "approvalStateService", approvalStateService);
        ReflectionTestUtils.setField(service, "dbChangeEventDal", dbChangeEventDal);
        ReflectionTestUtils.setField(service, "autoExecService", mock(AutoExecService.class));
        ReflectionTestUtils.setField(service, "schemaSnapshotService", mock(SchemaSnapshotService.class));
        ReflectionTestUtils.setField(service, "dbPairService", mock(DbPairService.class));
        ReflectionTestUtils.setField(service, "approvalFlowService", mock(ApprovalFlowService.class));
        ReflectionTestUtils.setField(service, "approvalPersonService", mock(ApprovalPersonService.class));
        ReflectionTestUtils.setField(service, "namingDao", mock(NamingDao.class));
        ReflectionTestUtils.setField(service, "prodReleaseDal", prodReleaseDal);
        ReflectionTestUtils.setField(service, "rollbackSqlGenerator", mock(RollbackSqlGenerator.class));
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
        ReflectionTestUtils.setField(service, "dbPairDal", dbPairDal);
        ReflectionTestUtils.setField(service, "dsDal", dsDal);
        ReflectionTestUtils.setField(service, "queryAnalysisService", queryAnalysisService);
        com.clougence.clouddm.console.web.component.dsconfig.DmDsConfigService dmDsConfigService
            = mock(com.clougence.clouddm.console.web.component.dsconfig.DmDsConfigService.class);
        when(dmDsConfigService.fetchDsConfigFromExists(anyLong())).thenReturn(null);
        when(dmDsConfigService.parseLevels(anyList())).thenReturn(
            mock(com.clougence.clouddm.console.web.component.dsconfig.mode.DsLevels.class));
        ReflectionTestUtils.setField(service, "dmDsConfigService", dmDsConfigService);
        ReflectionTestUtils.setField(service, "systemDal", mock(SystemDal.class));
        ReflectionTestUtils.setField(service, "dingBridgeOutboundService", bridge);
        return tApprovalMapper;
    }
}
