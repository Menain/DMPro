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
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.base.metadata.ds.DataSourceConfig;
import com.clougence.clouddm.base.metadata.ds.DataSourceType;
import com.clougence.clouddm.base.metadata.ds.SslMode;
import com.clougence.clouddm.console.web.component.alert.AlertContext;
import com.clougence.clouddm.console.web.component.alert.AlertPushService;
import com.clougence.clouddm.console.web.component.alert.AlertScene;
import com.clougence.clouddm.console.web.component.analysis.QueryAnalysisService;
import com.clougence.clouddm.console.web.component.approval.ApprovalFlowService;
import com.clougence.clouddm.console.web.component.approval.ApprovalPersonService;
import com.clougence.clouddm.console.web.component.approval.ApprovalStateService;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.component.auth.DmAuthServiceForBiz;
import com.clougence.clouddm.console.web.component.config.RootUserConfig;
import com.clougence.clouddm.console.web.component.dsconfig.DmDsConfigService;
import com.clougence.clouddm.console.web.component.execute.AutoExecService;
import com.clougence.clouddm.console.web.component.governance.GovReturnNotifyService;
import com.clougence.clouddm.console.web.component.governance.RollbackSqlGenerator;
import com.clougence.clouddm.console.web.component.governance.RiskAssessmentService;
import com.clougence.clouddm.console.web.component.governance.SqlRowEstimator;
import com.clougence.clouddm.console.web.model.fo.govticket.GovTicketV2CheckFO;
import com.clougence.clouddm.console.web.model.fo.govticket.GovTicketV2SubmitFO;
import com.clougence.clouddm.console.web.model.vo.govticket.GovTicketV2CheckVO;
import com.clougence.clouddm.console.web.service.dbpair.DbPairService;
import com.clougence.clouddm.console.web.service.envparam.DmEnvParamService;
import com.clougence.clouddm.console.web.service.ghost.GhostService;
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
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.datasource.DmDsDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO;
import com.clougence.clouddm.platform.dal.model.dbchange.GovEventType;
import com.clougence.clouddm.platform.dal.model.dbpair.DmDbPairDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;
import com.clougence.clouddm.sdk.sql.parser.SplitQueryType;
import com.clougence.clouddm.sdk.sql.parser.SplitScript;
import com.clougence.clouddm.console.web.model.vo.ghost.GhostRouteProbe;
import com.clougence.utils.JsonUtils;

/**
 * B6 M1 (Step 7a): the precheck marking matrix of the ghost auto-route — design §6.10 boundary
 * rows one by one (route / alert-pass / M1-transition multi-statement intercept / switch off /
 * non-MySQL / below threshold / degraded probe), the carriers (precheck_result "ghostRoutes"
 * display JSON + ApprovalMO.ghostRouteFlags execution-time authority) and the R15 alert
 * registration (push + event trail on the authoritative submit path only, never on /check).
 * <p>
 * Discipline: JUnit4 + hand-written mocks + ReflectionTestUtils + ArgumentCaptor; the ghost
 * probe is mocked at the GhostService seam (its channel-C contract is pinned in
 * GhostServiceImplTest). stmtIndex assertions pin the 1-based exec_order domain (R11).
 */
public class GhostRoutePrecheckTest {

    private GovTicketV2ServiceImpl  service;
    private TicketDbStmtDal         ticketDbStmtDal;
    private DmTicketDbStmtMapper    stmtMapper;
    private ApprovalDal             approvalDal;
    private DmApprovalMapper        approvalMapper;
    private DbChangeEventDal        dbChangeEventDal;
    private DmDbChangeEventMapper   eventMapper;
    private SystemDal               systemDal;
    private GhostService            ghostService;
    private AlertPushService        alertPushService;
    private DmDsMapper              dsMapper;
    private DmDsConfigService       dmDsConfigService;
    private QueryAnalysisService    queryAnalysisService;

    private static final String PUID = "puid-001";
    private static final String UID  = "uid-001";
    private static final long   PAIR_ID = 300L;
    private static final long   DS_ID   = 20L;
    private static final String DB      = "pre_db";

    /** per-test swap fixtures */
    private DmDsDO          dsDO;
    private DataSourceConfig dsConfig;
    private GhostRouteProbe  probe;
    private List<SplitScript> splitScripts;

    @Before
    public void setUp() {
        service = new GovTicketV2ServiceImpl();

        // DmTeamUtils static executionDal (required by nextExecJobBizId on the submit path)
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
        when(ticketDbStmtDal.revisionMapper()).thenReturn(mock(DmTicketStmtRevisionMapper.class));
        when(ticketDbStmtDal.schemaMapper()).thenReturn(mock(DmTicketStmtSchemaMapper.class));

        approvalDal = mock(ApprovalDal.class);
        approvalMapper = mock(DmApprovalMapper.class);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);
        when(approvalMapper.insert(any(DmApprovalDO.class))).thenAnswer(inv -> {
            inv.getArgument(0, DmApprovalDO.class).setId(999L);
            return 1;
        });

        dbChangeEventDal = mock(DbChangeEventDal.class);
        eventMapper = mock(DmDbChangeEventMapper.class);
        when(dbChangeEventDal.eventMapper()).thenReturn(eventMapper);

        // pair → PRE side
        DbPairDal dbPairDal = mock(DbPairDal.class);
        DmDbPairMapper pairMapper = mock(DmDbPairMapper.class);
        when(dbPairDal.pairMapper()).thenReturn(pairMapper);
        DmDbPairDO pair = new DmDbPairDO();
        pair.setId(PAIR_ID);
        pair.setPreDsId(DS_ID);
        pair.setPreDbName(DB);
        pair.setProdDsId(10L);
        pair.setProdDbName("prod_db");
        pair.setStatus("ENABLED");
        when(pairMapper.selectById(PAIR_ID)).thenReturn(pair);

        // datasource: plain direct MySQL by default (swap per test)
        DataSourceDal dsDal = mock(DataSourceDal.class);
        dsMapper = mock(DmDsMapper.class);
        when(dsDal.dsMapper()).thenReturn(dsMapper);
        dsDO = new DmDsDO();
        dsDO.setId(DS_ID);
        dsDO.setDsEnvId(1L);
        dsDO.setInstanceId("inst-1");
        dsDO.setDataSourceType(DataSourceType.MySQL);
        when(dsMapper.queryDsIdentityById(anyLong())).thenAnswer(inv -> dsDO);

        dmDsConfigService = mock(DmDsConfigService.class);
        dsConfig = new DataSourceConfig();
        dsConfig.setDataSourceType(DataSourceType.MySQL);
        when(dmDsConfigService.fetchDsConfigFromExists(anyLong())).thenAnswer(inv -> dsConfig);
        when(dmDsConfigService.parseLevels(anyList())).thenReturn(
            mock(com.clougence.clouddm.console.web.component.dsconfig.mode.DsLevels.class));

        // split stream: per-test scripts
        queryAnalysisService = mock(QueryAnalysisService.class);
        when(queryAnalysisService.analysisSplitStream(any(), any(), any(), anyInt(), anyInt()))
            .thenAnswer(inv -> splitScripts == null ? java.util.stream.Stream.<SplitScript>empty()
                                                    : splitScripts.stream());
        when(queryAnalysisService.analysisRulesStream(any(), any(), any(), anyInt(), anyInt(), any()))
            .thenAnswer(inv -> java.util.stream.Stream.empty());

        // system KV: switches ON by default (2-arg Boolean reads — the code handles null too)
        systemDal = mock(SystemDal.class);
        when(systemDal.fetchSystemConf(anyString(), eq(Boolean.class))).thenReturn(Boolean.TRUE);
        com.clougence.clouddm.platform.dal.mapper.system.DmSysEnvMapper envMapper
            = mock(com.clougence.clouddm.platform.dal.mapper.system.DmSysEnvMapper.class);
        when(systemDal.envMapper()).thenReturn(envMapper);
        com.clougence.clouddm.platform.dal.model.system.DmSysEnvDO envDO
            = new com.clougence.clouddm.platform.dal.model.system.DmSysEnvDO();
        envDO.setEnvName("env-test");
        when(envMapper.queryByEnvID(anyString(), anyLong())).thenReturn(envDO);

        // ghost probe seam: healthy exceeded+PK by default (swap per test)
        ghostService = mock(GhostService.class);
        probe = probe(true, true, true, 20_000_000L, 2L * 1024 * 1024 * 1024);
        when(ghostService.probeForRoute(anyString(), any(), any(), anyString(), anyString()))
            .thenAnswer(inv -> probe);

        alertPushService = mock(AlertPushService.class);
        when(alertPushService.isAlertPushEnabled()).thenReturn(true);

        NamingDao namingDao = mock(NamingDao.class);
        when(namingDao.genApprovalBizId()).thenReturn("biz-test");

        SqlRowEstimator sqlRowEstimator = mock(SqlRowEstimator.class);
        when(sqlRowEstimator.estimate(any(), any(), any(), any(), anyLong()))
            .thenReturn(SqlRowEstimator.EstimationResult.unknown());

        DmEnvParamService dmEnvParamService = mock(DmEnvParamService.class);
        RiskAssessmentService riskAssessmentService = new RiskAssessmentService();
        ReflectionTestUtils.setField(riskAssessmentService, "dmEnvParamService", dmEnvParamService);

        ReflectionTestUtils.setField(service, "ticketDbStmtDal", ticketDbStmtDal);
        ReflectionTestUtils.setField(service, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(service, "dbChangeEventDal", dbChangeEventDal);
        ReflectionTestUtils.setField(service, "dbPairDal", dbPairDal);
        ReflectionTestUtils.setField(service, "dsDal", dsDal);
        ReflectionTestUtils.setField(service, "dmDsConfigService", dmDsConfigService);
        ReflectionTestUtils.setField(service, "queryAnalysisService", queryAnalysisService);
        ReflectionTestUtils.setField(service, "systemDal", systemDal);
        ReflectionTestUtils.setField(service, "namingDao", namingDao);
        ReflectionTestUtils.setField(service, "sqlRowEstimator", sqlRowEstimator);
        ReflectionTestUtils.setField(service, "riskAssessmentService", riskAssessmentService);
        ReflectionTestUtils.setField(service, "rollbackSqlGenerator", new RollbackSqlGenerator());
        ReflectionTestUtils.setField(service, "ghostService", ghostService);
        ReflectionTestUtils.setField(service, "alertPushService", alertPushService);
        ReflectionTestUtils.setField(service, "approvalFlowService", mock(ApprovalFlowService.class));
        ReflectionTestUtils.setField(service, "approvalPersonService", mock(ApprovalPersonService.class));
        ReflectionTestUtils.setField(service, "approvalStateService", mock(ApprovalStateService.class));
        ReflectionTestUtils.setField(service, "dbPairService", mock(DbPairService.class));
        ReflectionTestUtils.setField(service, "dmAuthServiceForBiz", mock(DmAuthServiceForBiz.class));
        ReflectionTestUtils.setField(service, "govReturnNotifyService", mock(GovReturnNotifyService.class));
        ReflectionTestUtils.setField(service, "prodReleaseDal", mock(ProdReleaseDal.class));
        ReflectionTestUtils.setField(service, "schemaSnapshotService",
            mock(com.clougence.clouddm.console.web.component.governance.SchemaSnapshotService.class));
        ReflectionTestUtils.setField(service, "autoExecService", mock(AutoExecService.class));
        ReflectionTestUtils.setField(service, "dmEnvParamService", dmEnvParamService);
        ReflectionTestUtils.setField(service, "rdpOpAuditService",
            mock(com.clougence.rdp.service.RdpOpAuditService.class));

        splitScripts = List.of(alterScript(0, "ALTER TABLE t_big ADD COLUMN c1 INT"));
    }

    // ==================== design §6.10 boundary matrix (via the /check read path) ====================

    @Test
    public void check_singleAlterExceededHardPass_markedGhostRoute() {
        GovTicketV2CheckVO.GroupResult gr = checkPreDdl("ALTER TABLE t_big ADD COLUMN c1 INT");

        assertEquals("PASS", gr.getCheckStatus());
        assertNotNull(gr.getGhostRoutes());
        assertEquals(1, gr.getGhostRoutes().size());
        GovTicketV2CheckVO.GhostRouteVO route = gr.getGhostRoutes().get(0);
        assertEquals(GovTicketV2CheckVO.GhostRouteVO.ROUTE_GHOST, route.getRoute());
        assertEquals("t_big", route.getTableName());
        assertEquals(1, route.getStmtIndex()); // 1-based exec_order domain (R11)
        assertEquals(Long.valueOf(20_000_000L), route.getTableRows());
        assertNull(route.getAlertReason());
        assertNotNull(route.getMessage());
        verify(ghostService).probeForRoute(eq(UID), any(), any(), eq(DB), eq("t_big"));
    }

    @Test
    public void check_belowThreshold_noMarks_statusQuo() {
        probe = probe(true, true, false, 100L, 1024L);

        GovTicketV2CheckVO.GroupResult gr = checkPreDdl("ALTER TABLE t_small ADD COLUMN c1 INT");

        assertEquals("PASS", gr.getCheckStatus());
        assertNull(gr.getGhostRoutes());
        assertNull("no GHOST segment → no segment plan at all (legacy shape)", gr.getSegments());
        verifyNoInteractions(alertPushService);
    }

    @Test
    public void check_exceededNoPk_alertPassMarked_notFailed() {
        probe = probe(true, false, true, 20_000_000L, 2L * 1024 * 1024 * 1024);

        GovTicketV2CheckVO.GroupResult gr = checkPreDdl("ALTER TABLE t_nopk ADD COLUMN c1 INT");

        assertEquals("PASS", gr.getCheckStatus()); // 告警放行 — NOT an intercept
        assertEquals(1, gr.getGhostRoutes().size());
        GovTicketV2CheckVO.GhostRouteVO route = gr.getGhostRoutes().get(0);
        assertEquals(GovTicketV2CheckVO.GhostRouteVO.ROUTE_ALERT_PASS, route.getRoute());
        assertEquals(AlertContext.RISK_NO_UNIQUE_KEY, route.getAlertReason());
        assertNotNull(route.getMessage());
    }

    @Test
    public void check_exceededSshDs_alertPassMarked_evenWithPk() {
        dsConfig.setSshProxyEnabled(true);

        GovTicketV2CheckVO.GroupResult gr = checkPreDdl("ALTER TABLE t_big ADD COLUMN c1 INT");

        assertEquals("PASS", gr.getCheckStatus());
        GovTicketV2CheckVO.GhostRouteVO route = gr.getGhostRoutes().get(0);
        assertEquals(GovTicketV2CheckVO.GhostRouteVO.ROUTE_ALERT_PASS, route.getRoute());
        assertEquals(AlertContext.RISK_TUNNEL, route.getAlertReason());
    }

    @Test
    public void check_exceededSslDs_alertPassMarked() {
        dsConfig.setSslMode(SslMode.TRUST);

        GovTicketV2CheckVO.GroupResult gr = checkPreDdl("ALTER TABLE t_big ADD COLUMN c1 INT");

        assertEquals("PASS", gr.getCheckStatus());
        assertEquals(AlertContext.RISK_TUNNEL, gr.getGhostRoutes().get(0).getAlertReason());
    }

    @Test
    public void check_multiStmtWithExceededHardPassAlter_segmentedNotIntercepted_m2() {
        splitScripts = List.of(alterScript(0, "ALTER TABLE t_big ADD COLUMN c1 INT"),
                               dmlScript(1, "INSERT INTO t_other VALUES (1)"));

        GovTicketV2CheckVO.GroupResult gr = checkPreDdl("ALTER TABLE t_big ADD COLUMN c1 INT; INSERT INTO t_other VALUES (1);");

        // B6 M2 lifts the M1 transition intercept: the group is SPLIT INTO SEGMENTS instead —
        // [GHOST(1), AUTOEXEC[2,2]], submitted and executed serially without any user action
        assertEquals("PASS", gr.getCheckStatus());
        assertEquals(1, gr.getGhostRoutes().size());
        assertEquals(GovTicketV2CheckVO.GhostRouteVO.ROUTE_GHOST, gr.getGhostRoutes().get(0).getRoute());
        assertNotNull(gr.getSegments());
        assertEquals(2, gr.getSegments().size());
        com.clougence.clouddm.console.web.component.governance.TicketSegment s1 = gr.getSegments().get(0);
        assertEquals(com.clougence.clouddm.console.web.component.governance.TicketSegment.TYPE_GHOST, s1.getType());
        assertEquals(1, s1.getSegIdx());
        assertEquals(Integer.valueOf(1), s1.getStmtIndex()); // 1-based exec_order domain (R11)
        assertEquals("t_big", s1.getTable());
        assertEquals("ALTER TABLE t_big ADD COLUMN c1 INT", s1.getSql()); // FULL frozen text
        com.clougence.clouddm.console.web.component.governance.TicketSegment s2 = gr.getSegments().get(1);
        assertEquals(com.clougence.clouddm.console.web.component.governance.TicketSegment.TYPE_AUTOEXEC, s2.getType());
        assertEquals(2, s2.getSegIdx());
        assertEquals(List.of(2, 2), s2.getStmtRange());
    }

    @Test
    public void check_segmentMatrix_normal2GhostNormal_threeSegments() {
        // the ruling's canonical shape: [普通×2, GHOST, 普通] → 3 segments, dense segIdx,
        // 1-based exec_order-domain bounds, consecutive plain statements merged into ONE run
        splitScripts = List.of(dmlScript(0, "INSERT INTO a VALUES (1)"),
                               dmlScript(1, "UPDATE b SET x=1"),
                               alterScript(2, "ALTER TABLE t_big ADD COLUMN c1 INT"),
                               dmlScript(3, "DELETE FROM c WHERE 1=1"));

        GovTicketV2CheckVO.GroupResult gr = checkPreDdl("INSERT; UPDATE; ALTER; DELETE;");

        assertEquals("PASS", gr.getCheckStatus());
        assertEquals(3, gr.getSegments().size());
        com.clougence.clouddm.console.web.component.governance.TicketSegment s1 = gr.getSegments().get(0);
        assertEquals(com.clougence.clouddm.console.web.component.governance.TicketSegment.TYPE_AUTOEXEC, s1.getType());
        assertEquals(1, s1.getSegIdx());
        assertEquals(List.of(1, 2), s1.getStmtRange());
        com.clougence.clouddm.console.web.component.governance.TicketSegment s2 = gr.getSegments().get(1);
        assertEquals(com.clougence.clouddm.console.web.component.governance.TicketSegment.TYPE_GHOST, s2.getType());
        assertEquals(2, s2.getSegIdx());
        assertEquals(Integer.valueOf(3), s2.getStmtIndex());
        assertEquals("t_big", s2.getTable());
        assertEquals(Long.valueOf(20_000_000L), s2.getRows());
        com.clougence.clouddm.console.web.component.governance.TicketSegment s3 = gr.getSegments().get(2);
        assertEquals(com.clougence.clouddm.console.web.component.governance.TicketSegment.TYPE_AUTOEXEC, s3.getType());
        assertEquals(3, s3.getSegIdx());
        assertEquals(List.of(4, 4), s3.getStmtRange());
    }

    @Test
    public void check_twoGhostAlters_adjacentGhostSegments() {
        splitScripts = List.of(alterScript(0, "ALTER TABLE t_big1 ADD COLUMN c1 INT"),
                               alterScript(1, "ALTER TABLE t_big2 ADD COLUMN c2 INT"),
                               dmlScript(2, "INSERT INTO a VALUES (1)"));

        GovTicketV2CheckVO.GroupResult gr = checkPreDdl("ALTER; ALTER; INSERT;");

        assertEquals("PASS", gr.getCheckStatus());
        assertEquals(3, gr.getSegments().size());
        assertEquals(com.clougence.clouddm.console.web.component.governance.TicketSegment.TYPE_GHOST, gr.getSegments().get(0).getType());
        assertEquals(Integer.valueOf(1), gr.getSegments().get(0).getStmtIndex());
        assertEquals(com.clougence.clouddm.console.web.component.governance.TicketSegment.TYPE_GHOST, gr.getSegments().get(1).getType());
        assertEquals(Integer.valueOf(2), gr.getSegments().get(1).getStmtIndex());
        assertEquals(com.clougence.clouddm.console.web.component.governance.TicketSegment.TYPE_AUTOEXEC, gr.getSegments().get(2).getType());
        assertEquals(List.of(3, 3), gr.getSegments().get(2).getStmtRange());
        // both ALTERs are probed (no M1-style early return)
        verify(ghostService, times(2)).probeForRoute(anyString(), any(), any(), anyString(), anyString());
    }

    @Test
    public void check_singleAlter_degenerateSingleGhostSegment_m1CompatShape() {
        GovTicketV2CheckVO.GroupResult gr = checkPreDdl("ALTER TABLE t_big ADD COLUMN c1 INT");

        // the single-ALTER group is the single-GHOST-segment degenerate shape (M1 ⊂ M2)
        assertEquals(1, gr.getSegments().size());
        assertEquals(com.clougence.clouddm.console.web.component.governance.TicketSegment.TYPE_GHOST, gr.getSegments().get(0).getType());
        assertEquals(Integer.valueOf(1), gr.getSegments().get(0).getStmtIndex());
    }

    @Test
    public void check_multiStmtExceededNoPk_alertPassNoIntercept() {
        probe = probe(true, false, true, 20_000_000L, 2L * 1024 * 1024 * 1024);
        splitScripts = List.of(alterScript(0, "ALTER TABLE t_nopk ADD COLUMN c1 INT"),
                               dmlScript(1, "INSERT INTO t_other VALUES (1)"));

        GovTicketV2CheckVO.GroupResult gr = checkPreDdl("ALTER TABLE t_nopk ADD c1 INT; INSERT INTO t_other VALUES (1);");

        // the intercept needs a hard-PASS ALTER — a no-PK one alerts and passes through
        assertEquals("PASS", gr.getCheckStatus());
        assertEquals(1, gr.getGhostRoutes().size());
        assertEquals(GovTicketV2CheckVO.GhostRouteVO.ROUTE_ALERT_PASS, gr.getGhostRoutes().get(0).getRoute());
        // M2: an ALERT_PASS statement STAYS in its AUTOEXEC segment — no GHOST segment, and a
        // pure-alert group carries NO segment plan at all (the whole group runs the legacy shape)
        assertNull(gr.getSegments());
    }

    @Test
    public void check_alterAtSecondPosition_stmtIndexIsOneBased() {
        probe = probe(true, false, true, 20_000_000L, 0L);
        splitScripts = List.of(dmlScript(0, "INSERT INTO t_other VALUES (1)"),
                               alterScript(1, "ALTER TABLE t_nopk ADD COLUMN c1 INT"));

        GovTicketV2CheckVO.GroupResult gr = checkPreDdl("INSERT INTO t_other VALUES (1); ALTER TABLE t_nopk ADD c1 INT;");

        assertEquals("PASS", gr.getCheckStatus());
        // R11 pin: SplitScript.index is 0-based — the mark carries the 1-based exec_order domain
        assertEquals(2, gr.getGhostRoutes().get(0).getStmtIndex());
    }

    @Test
    public void check_switchOff_skipsAllEvaluation() {
        when(systemDal.fetchSystemConf(eq(RootUserConfig.Fields.ghostAutoRoute), eq(Boolean.class)))
            .thenReturn(Boolean.FALSE);

        GovTicketV2CheckVO.GroupResult gr = checkPreDdl("ALTER TABLE t_big ADD COLUMN c1 INT");

        assertEquals("PASS", gr.getCheckStatus());
        assertNull(gr.getGhostRoutes());
        verify(ghostService, never()).probeForRoute(anyString(), any(), any(), anyString(), anyString());
    }

    @Test
    public void check_nonMySql_neverEvaluated() {
        dsDO.setDataSourceType(DataSourceType.PostgreSQL);
        // DataSourceConfig.setDataSourceType is write-once — swap the whole config
        dsConfig = new DataSourceConfig();
        dsConfig.setDataSourceType(DataSourceType.PostgreSQL);

        GovTicketV2CheckVO.GroupResult gr = checkPreDdl("ALTER TABLE t_big ADD COLUMN c1 INT");

        assertEquals("PASS", gr.getCheckStatus());
        verify(ghostService, never()).probeForRoute(anyString(), any(), any(), anyString(), anyString());
    }

    @Test
    public void check_prodDml_neverEvaluated() {
        splitScripts = List.of(dmlScript(0, "UPDATE t SET a=1"));

        GovTicketV2CheckFO fo = new GovTicketV2CheckFO();
        fo.setTicketType("PROD_DML");
        GovTicketV2CheckFO.GroupInput gi = new GovTicketV2CheckFO.GroupInput();
        gi.setPairId(PAIR_ID);
        gi.setSqlContent("UPDATE t SET a=1");
        fo.setGroups(List.of(gi));

        GovTicketV2CheckVO vo = service.check(PUID, UID, fo);

        assertEquals("PASS", vo.getGroups().get(0).getCheckStatus());
        verify(ghostService, never()).probeForRoute(anyString(), any(), any(), anyString(), anyString());
    }

    @Test
    public void check_probeDegraded_statusQuoKept() {
        probe = probe(false, false, false, null, null);

        GovTicketV2CheckVO.GroupResult gr = checkPreDdl("ALTER TABLE t_big ADD COLUMN c1 INT");

        assertEquals("PASS", gr.getCheckStatus());
        assertNull(gr.getGhostRoutes());
    }

    @Test
    public void check_evaluationThrows_degradesToStatusQuo() {
        when(ghostService.probeForRoute(anyString(), any(), any(), anyString(), anyString()))
            .thenThrow(new RuntimeException("probe seam blew up"));

        GovTicketV2CheckVO.GroupResult gr = checkPreDdl("ALTER TABLE t_big ADD COLUMN c1 INT");

        assertEquals("PASS", gr.getCheckStatus());
        assertNull(gr.getGhostRoutes());
    }

    @Test
    public void check_noAlterStatements_neverProbes() {
        splitScripts = List.of(dmlScript(0, "INSERT INTO t VALUES (1)"));

        GovTicketV2CheckVO.GroupResult gr = checkPreDdl("INSERT INTO t VALUES (1)");

        assertEquals("PASS", gr.getCheckStatus());
        assertNull(gr.getGhostRoutes());
        verifyNoInteractions(ghostService);
    }

    @Test
    public void check_exoticAlterShape_notRoutable_staysDirect() {
        // ALTER_TABLE-typed but rejected by the strict checker (embedded comment) → no candidate
        splitScripts = List.of(alterScript(0, "ALTER TABLE t_big ADD COLUMN c1 INT -- comment"));

        GovTicketV2CheckVO.GroupResult gr = checkPreDdl("ALTER TABLE t_big ADD COLUMN c1 INT -- comment");

        assertEquals("PASS", gr.getCheckStatus());
        assertNull(gr.getGhostRoutes());
        verify(ghostService, never()).probeForRoute(anyString(), any(), any(), anyString(), anyString());
    }

    @Test
    public void check_previewPath_neverRegistersAlerts() {
        probe = probe(true, false, true, 20_000_000L, 0L);

        checkPreDdl("ALTER TABLE t_nopk ADD COLUMN c1 INT");

        // R15 anti-spam: /check is a preview — push + event trail only happen on submit/resubmit
        verify(alertPushService, never()).pushAlert(anyString(), any(), any());
        verifyNoInteractions(eventMapper);
    }

    // ==================== submit carriers: ApprovalMO flags + precheck_result JSON ====================

    @Test
    public void submit_routedGroup_writesFlagsAndPrecheckJson() {
        service.submit(PUID, UID, submitFO("ALTER TABLE t_big ADD COLUMN c1 INT"));

        ArgumentCaptor<DmApprovalDO> ticketCaptor = ArgumentCaptor.forClass(DmApprovalDO.class);
        verify(approvalMapper).insert(ticketCaptor.capture());
        ApprovalMO mo = JsonUtils.toObj(ticketCaptor.getValue().getTicketInfo(), ApprovalMO.class);
        assertEquals(List.of(Boolean.TRUE), mo.getGhostRouteFlags());

        ArgumentCaptor<DmTicketDbStmtDO> stmtCaptor = ArgumentCaptor.forClass(DmTicketDbStmtDO.class);
        verify(stmtMapper).insert(stmtCaptor.capture());
        Map<?, ?> precheckJson = JsonUtils.toObj(stmtCaptor.getValue().getPrecheckResult(), Map.class);
        assertNotNull("display carrier: precheck_result.ghostRoutes", precheckJson.get("ghostRoutes"));
        List<?> routes = (List<?>) precheckJson.get("ghostRoutes");
        assertEquals(1, routes.size());
        Map<?, ?> route = (Map<?, ?>) routes.get(0);
        assertEquals("GHOST", route.get("route"));
        assertEquals("t_big", route.get("tableName"));
        assertEquals(1, route.get("stmtIndex"));

        // B6 M2: the segment plan (plan of record, contract :404 additive key) rides in the same
        // JSON — degenerate single-GHOST-segment shape for the single-ALTER group, with the FULL
        // frozen statement text (self-contained execution-time dispatch)
        List<?> segments = (List<?>) precheckJson.get("segments");
        assertNotNull("plan carrier: precheck_result.segments", segments);
        assertEquals(1, segments.size());
        Map<?, ?> seg = (Map<?, ?>) segments.get(0);
        assertEquals(1, seg.get("segIdx"));
        assertEquals("GHOST", seg.get("type"));
        assertEquals(1, seg.get("stmtIndex")); // 1-based exec_order domain (R11)
        assertEquals("t_big", seg.get("table"));
        assertEquals("ALTER TABLE t_big ADD COLUMN c1 INT", seg.get("sql"));
    }

    @Test
    public void submit_segmentedMixedGroup_planJsonCarriesRangesAndHoleNumbering() {
        splitScripts = List.of(dmlScript(0, "INSERT INTO a VALUES (1)"),
                               dmlScript(1, "UPDATE b SET x=1"),
                               alterScript(2, "ALTER TABLE t_big ADD COLUMN c1 INT"),
                               dmlScript(3, "DELETE FROM c WHERE 1=1"));

        service.submit(PUID, UID, submitFO("INSERT; UPDATE; ALTER; DELETE;"));

        Map<?, ?> precheckJson = JsonUtils.toObj(captureStmt().getPrecheckResult(), Map.class);
        List<?> segments = (List<?>) precheckJson.get("segments");
        assertEquals(3, segments.size());
        Map<?, ?> s1 = (Map<?, ?>) segments.get(0);
        assertEquals("AUTOEXEC", s1.get("type"));
        assertEquals(List.of(1, 2), s1.get("stmtRange")); // 1-based inclusive bounds (R11 pin)
        Map<?, ?> s2 = (Map<?, ?>) segments.get(1);
        assertEquals("GHOST", s2.get("type"));
        assertEquals(3, s2.get("stmtIndex"));
        assertNull("AUTOEXEC segments carry no sql (the execution-time re-split owns the text)", s1.get("sql"));
        // the flags authority stays written (sweep/projection pre-filters keep working — the
        // segment plan wins at the dispatch decision points, decision order pinned in dispatch tests)
        ArgumentCaptor<DmApprovalDO> ticketCaptor = ArgumentCaptor.forClass(DmApprovalDO.class);
        verify(approvalMapper).insert(ticketCaptor.capture());
        ApprovalMO mo = JsonUtils.toObj(ticketCaptor.getValue().getTicketInfo(), ApprovalMO.class);
        assertEquals(List.of(Boolean.TRUE), mo.getGhostRouteFlags());
    }

    @Test
    public void submit_noRoute_flagsStayAbsent_legacyShape() {
        probe = probe(true, true, false, 10L, 10L);

        service.submit(PUID, UID, submitFO("ALTER TABLE t_small ADD COLUMN c1 INT"));

        ArgumentCaptor<DmApprovalDO> ticketCaptor = ArgumentCaptor.forClass(DmApprovalDO.class);
        verify(approvalMapper).insert(ticketCaptor.capture());
        ApprovalMO mo = JsonUtils.toObj(ticketCaptor.getValue().getTicketInfo(), ApprovalMO.class);
        assertNull(mo.getGhostRouteFlags());
        Map<?, ?> precheckJson = JsonUtils.toObj(
            captureStmt().getPrecheckResult(), Map.class);
        assertFalse(precheckJson.containsKey("ghostRoutes"));
        // pure-normal group: NO segments key at all — the legacy precheck_result shape stays
        // byte-identical (zero overhead + 存量形态逐位不变 regression pin)
        assertFalse(precheckJson.containsKey("segments"));
    }

    @Test
    public void submit_multiGroup_positionalFlagsPerGroup() {
        GovTicketV2SubmitFO fo = new GovTicketV2SubmitFO();
        fo.setTicketType("PRE_DDL");
        fo.setTicketTitle("two groups");
        GovTicketV2SubmitFO.GroupInput g0 = new GovTicketV2SubmitFO.GroupInput();
        g0.setPairId(PAIR_ID);
        g0.setSqlContent("ALTER TABLE t_big ADD COLUMN c1 INT");
        GovTicketV2SubmitFO.GroupInput g1 = new GovTicketV2SubmitFO.GroupInput();
        g1.setPairId(PAIR_ID);
        g1.setSqlContent("INSERT INTO t_other VALUES (1)");
        fo.setGroups(List.of(g0, g1));
        // group0: single exceeded ALTER; group1: plain DML — the probe answers per table name
        splitScripts = null; // per-group scripts: use the answer keyed on the SQL text below
        when(queryAnalysisService.analysisSplitStream(any(), any(), any(), anyInt(), anyInt()))
            .thenAnswer(inv -> {
                java.io.Reader reader = inv.getArgument(1);
                String sql = readAll(reader);
                return sql.contains("ALTER")
                    ? List.of(alterScript(0, "ALTER TABLE t_big ADD COLUMN c1 INT")).stream()
                    : List.of(dmlScript(0, "INSERT INTO t_other VALUES (1)")).stream();
            });

        service.submit(PUID, UID, fo);

        ArgumentCaptor<DmApprovalDO> ticketCaptor = ArgumentCaptor.forClass(DmApprovalDO.class);
        verify(approvalMapper).insert(ticketCaptor.capture());
        ApprovalMO mo = JsonUtils.toObj(ticketCaptor.getValue().getTicketInfo(), ApprovalMO.class);
        assertEquals(List.of(Boolean.TRUE, Boolean.FALSE), mo.getGhostRouteFlags());
    }

    // ==================== R15 alert registration (submit authoritative path) ====================

    @Test
    public void submit_alertPass_pushesAlertAndTrailsEvent() {
        probe = probe(true, false, true, 20_000_000L, 2L * 1024 * 1024 * 1024);
        splitScripts = List.of(alterScript(0, "ALTER TABLE t_nopk ADD COLUMN c1 INT"));

        service.submit(PUID, UID, submitFO("ALTER TABLE t_nopk ADD COLUMN c1 INT"));

        ArgumentCaptor<AlertContext> ctxCaptor = ArgumentCaptor.forClass(AlertContext.class);
        verify(alertPushService).pushAlert(eq(PUID), eq(AlertScene.GHOST_LARGE_TABLE_NO_PK), ctxCaptor.capture());
        AlertContext ctx = ctxCaptor.getValue();
        assertEquals(999L, ctx.getTicketId());
        assertEquals(UID, ctx.getApplicantUid());
        assertEquals(DB, ctx.getDbName());
        assertEquals("t_nopk", ctx.getTableName());
        assertEquals(Long.valueOf(20_000_000L), ctx.getTableRows());
        assertEquals(AlertContext.RISK_NO_UNIQUE_KEY, ctx.getRiskCode());

        // event trail: SUBMIT + GHOST_ALERT_TRIGGERED (in-transaction, zero new tables)
        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper, times(2)).insert(eventCaptor.capture());
        DmDbChangeEventDO alertEvent = eventCaptor.getAllValues().stream()
            .filter(e -> GovEventType.GHOST_ALERT_TRIGGERED.name().equals(e.getEventType()))
            .findFirst().orElse(null);
        assertNotNull(alertEvent);
        assertEquals(UID, alertEvent.getOperatorUid());
        Map<?, ?> data = JsonUtils.toObj(alertEvent.getEventData(), Map.class);
        assertEquals(AlertScene.GHOST_LARGE_TABLE_NO_PK.name(), data.get("scene"));
        List<?> tables = (List<?>) data.get("tables");
        assertEquals(1, tables.size());
        assertEquals("t_nopk", ((Map<?, ?>) tables.get(0)).get("tableName"));

        // the ticket itself is NOT blocked (告警放行)
        ArgumentCaptor<DmApprovalDO> ticketCaptor = ArgumentCaptor.forClass(DmApprovalDO.class);
        verify(approvalMapper).insert(ticketCaptor.capture());
        assertNull("alert-pass must not set route flags",
            JsonUtils.toObj(ticketCaptor.getValue().getTicketInfo(), ApprovalMO.class).getGhostRouteFlags());
    }

    @Test
    public void submit_alertSwitchOff_noPushNoEventTrail() {
        probe = probe(true, false, true, 20_000_000L, 0L);
        when(alertPushService.isAlertPushEnabled()).thenReturn(false);

        service.submit(PUID, UID, submitFO("ALTER TABLE t_nopk ADD COLUMN c1 INT"));

        verify(alertPushService, never()).pushAlert(anyString(), any(), any());
        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        assertEquals("only the SUBMIT event — a suppressed alert leaves no trail",
            GovEventType.SUBMIT.name(), eventCaptor.getValue().getEventType());
    }

    @Test
    public void submit_routedGroup_noAlertRegistration() {
        service.submit(PUID, UID, submitFO("ALTER TABLE t_big ADD COLUMN c1 INT"));

        // a hard-pass route is not an alert scene
        verify(alertPushService, never()).pushAlert(anyString(), any(), any());
    }

    // ==================== resubmit: flags rebuild + re-alert (authoritative re-run) ====================

    @Test
    public void resubmit_rebuildsFlags_keepsUntouched_recomputesModified() {
        // g0 (SUCCESS, previously ghost-routed) + g1 (FAILED, previously plain) — old flags [true,false]
        DmApprovalDO ticket = revisionTicket(List.of(true, false));
        when(approvalMapper.queryById(900L)).thenReturn(ticket);
        DmTicketDbStmtDO g0 = revisionGroup(50L, "SUCCESS", "ALTER TABLE t_done ADD COLUMN c0 INT");
        DmTicketDbStmtDO g1 = revisionGroup(51L, "FAILED", "INSERT INTO t_x VALUES (1)");
        when(stmtMapper.queryByTicketId(900L)).thenReturn(List.of(g0, g1));
        // the modified group's fresh SQL is now a single exceeded hard-pass ALTER
        splitScripts = List.of(alterScript(0, "ALTER TABLE t_big ADD COLUMN c2 INT"));

        service.resubmit(PUID, UID, resubmitFO(51L, "ALTER TABLE t_big ADD COLUMN c2 INT"));

        ArgumentCaptor<DmApprovalDO> updCaptor = ArgumentCaptor.forClass(DmApprovalDO.class);
        verify(approvalMapper).updateById(updCaptor.capture());
        ApprovalMO updated = JsonUtils.toObj(updCaptor.getValue().getTicketInfo(), ApprovalMO.class);
        // position 0 retains the old flag; position 1 is recomputed from the fresh precheck
        assertEquals(List.of(Boolean.TRUE, Boolean.TRUE), updated.getGhostRouteFlags());

        // the rewritten precheck_result of the modified group carries the display marks
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(stmtMapper).updateSqlForRevision(eq(51L), anyString(), jsonCaptor.capture());
        Map<?, ?> precheckJson = JsonUtils.toObj(jsonCaptor.getValue(), Map.class);
        assertNotNull(precheckJson.get("ghostRoutes"));
    }

    @Test
    public void resubmit_segmentedFailedGroup_runtimeResetPlanRecomputed_m2() {
        // B6 M2 (contract :417 step3): the resubmit of a FAILED segmented group MUST reset the
        // segment runtime — a stale exec_segments whose stmtRanges point into the OLD text would
        // make the first-segment dispatch execute wrong statement bounds. The fresh authoritative
        // precheck recomputes the plan (segments re-probed from scratch).
        DmApprovalDO ticket = revisionTicket(List.of(true));
        when(approvalMapper.queryById(900L)).thenReturn(ticket);
        DmTicketDbStmtDO g1 = revisionGroup(51L, "FAILED", "ALTER TABLE t_big ADD COLUMN c1 INT");
        g1.setExecSegments("[{\"segIdx\":1,\"type\":\"GHOST\",\"stmtIndex\":1,\"status\":\"FAILED\"}]");
        when(stmtMapper.queryByTicketId(900L)).thenReturn(List.of(g1));
        splitScripts = List.of(alterScript(0, "ALTER TABLE t_big ADD COLUMN c2 INT"));

        service.resubmit(PUID, UID, resubmitFO(51L, "ALTER TABLE t_big ADD COLUMN c2 INT"));

        // the runtime reset lands on the mapper seam (Java-side write, unit-testable)
        verify(stmtMapper).updateExecSegments(51L, null);
        // the rewritten precheck_result carries the RECOMPUTED plan
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(stmtMapper).updateSqlForRevision(eq(51L), anyString(), jsonCaptor.capture());
        Map<?, ?> precheckJson = JsonUtils.toObj(jsonCaptor.getValue(), Map.class);
        List<?> segments = (List<?>) precheckJson.get("segments");
        assertNotNull(segments);
        assertEquals(1, segments.size());
        assertEquals("ALTER TABLE t_big ADD COLUMN c2 INT", ((Map<?, ?>) segments.get(0)).get("sql"));
    }

    @Test
    public void resubmit_routeLost_flagsClearedBackToNull() {
        // g1 was routed and failed; the revised SQL now probes below threshold → nothing routed
        DmApprovalDO ticket = revisionTicket(List.of(false, true));
        when(approvalMapper.queryById(900L)).thenReturn(ticket);
        DmTicketDbStmtDO g0 = revisionGroup(50L, "SUCCESS", "INSERT INTO t_y VALUES (2)");
        DmTicketDbStmtDO g1 = revisionGroup(51L, "FAILED", "ALTER TABLE t_big ADD COLUMN c1 INT");
        when(stmtMapper.queryByTicketId(900L)).thenReturn(List.of(g0, g1));
        probe = probe(true, true, false, 10L, 10L); // below threshold now
        splitScripts = List.of(alterScript(0, "ALTER TABLE t_small ADD COLUMN c2 INT"));

        service.resubmit(PUID, UID, resubmitFO(51L, "ALTER TABLE t_small ADD COLUMN c2 INT"));

        ArgumentCaptor<DmApprovalDO> updCaptor = ArgumentCaptor.forClass(DmApprovalDO.class);
        verify(approvalMapper).updateById(updCaptor.capture());
        ApprovalMO updated = JsonUtils.toObj(updCaptor.getValue().getTicketInfo(), ApprovalMO.class);
        assertNull(updated.getGhostRouteFlags());
    }

    @Test
    public void resubmit_legacyNoFlags_staysNull_byteCompatible() {
        DmApprovalDO ticket = revisionTicket(null);
        when(approvalMapper.queryById(900L)).thenReturn(ticket);
        DmTicketDbStmtDO g1 = revisionGroup(51L, "FAILED", "INSERT INTO t_x VALUES (1)");
        when(stmtMapper.queryByTicketId(900L)).thenReturn(List.of(g1));
        splitScripts = List.of(dmlScript(0, "INSERT INTO t_x VALUES (9)"));
        probe = probe(true, true, false, 1L, 1L);

        service.resubmit(PUID, UID, resubmitFO(51L, "INSERT INTO t_x VALUES (9)"));

        ArgumentCaptor<DmApprovalDO> updCaptor = ArgumentCaptor.forClass(DmApprovalDO.class);
        verify(approvalMapper).updateById(updCaptor.capture());
        ApprovalMO updated = JsonUtils.toObj(updCaptor.getValue().getTicketInfo(), ApprovalMO.class);
        assertNull(updated.getGhostRouteFlags());
        verify(ghostService, never()).probeForRoute(anyString(), any(), any(), anyString(), anyString());
    }

    @Test
    public void resubmit_alertPassHit_reAlertsOnTheAuthoritativeRerun() {
        DmApprovalDO ticket = revisionTicket(null);
        when(approvalMapper.queryById(900L)).thenReturn(ticket);
        DmTicketDbStmtDO g1 = revisionGroup(51L, "FAILED", "ALTER TABLE t_nopk ADD COLUMN c1 INT");
        when(stmtMapper.queryByTicketId(900L)).thenReturn(List.of(g1));
        probe = probe(true, false, true, 20_000_000L, 0L); // exceeded + no PK
        splitScripts = List.of(alterScript(0, "ALTER TABLE t_nopk ADD COLUMN c2 INT"));

        service.resubmit(PUID, UID, resubmitFO(51L, "ALTER TABLE t_nopk ADD COLUMN c2 INT"));

        verify(alertPushService).pushAlert(eq(PUID), eq(AlertScene.GHOST_LARGE_TABLE_NO_PK), any(AlertContext.class));
        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper, times(2)).insert(eventCaptor.capture());
        assertTrue(eventCaptor.getAllValues().stream()
            .anyMatch(e -> GovEventType.GHOST_ALERT_TRIGGERED.name().equals(e.getEventType())));
    }

    // ==================== helpers ====================

    private com.clougence.clouddm.console.web.model.fo.govticket.GovTicketV2ResubmitFO resubmitFO(long groupId, String sql) {
        com.clougence.clouddm.console.web.model.fo.govticket.GovTicketV2ResubmitFO fo
            = new com.clougence.clouddm.console.web.model.fo.govticket.GovTicketV2ResubmitFO();
        fo.setTicketId(900L);
        com.clougence.clouddm.console.web.model.fo.govticket.GovTicketV2ResubmitFO.GroupInput gi
            = new com.clougence.clouddm.console.web.model.fo.govticket.GovTicketV2ResubmitFO.GroupInput();
        gi.setGroupId(groupId);
        gi.setSqlContent(sql);
        fo.setGroups(List.of(gi));
        fo.setReason("fix");
        return fo;
    }

    private DmApprovalDO revisionTicket(List<Boolean> oldFlags) {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(900L);
        ticket.setPrimaryUid(PUID);
        ticket.setOwnerUid(UID);
        ticket.setTicketStatus(com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus.REVISION);
        ticket.setApproBiz(com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz.DM_CHANGE);
        ticket.setApproType(com.clougence.clouddm.platform.dal.model.approval.ApprovalType.Internal);
        ticket.setBindDsId(DS_ID);
        ticket.setEnvName("env-test");
        ticket.setBizId("biz-900");
        ApprovalMO mo = new ApprovalMO();
        mo.setTicketType("PRE_DDL");
        mo.setApprovalMode("DIRECT");
        mo.setGhostRouteFlags(oldFlags);
        ticket.setTicketInfo(JsonUtils.toJson(mo));
        return ticket;
    }

    private static DmTicketDbStmtDO revisionGroup(long id, String execStatus, String sql) {
        DmTicketDbStmtDO group = new DmTicketDbStmtDO();
        group.setId(id);
        group.setTicketId(900L);
        group.setPairId(PAIR_ID);
        group.setDsId(DS_ID);
        group.setDbName(DB);
        group.setSqlContent(sql);
        group.setExecStatus(execStatus);
        return group;
    }

    private GovTicketV2CheckVO.GroupResult checkPreDdl(String sqlContent) {
        GovTicketV2CheckFO fo = new GovTicketV2CheckFO();
        fo.setTicketType("PRE_DDL");
        GovTicketV2CheckFO.GroupInput gi = new GovTicketV2CheckFO.GroupInput();
        gi.setPairId(PAIR_ID);
        gi.setSqlContent(sqlContent);
        fo.setGroups(List.of(gi));
        GovTicketV2CheckVO vo = service.check(PUID, UID, fo);
        return vo.getGroups().get(0);
    }

    private GovTicketV2SubmitFO submitFO(String sqlContent) {
        GovTicketV2SubmitFO fo = new GovTicketV2SubmitFO();
        fo.setTicketType("PRE_DDL");
        fo.setTicketTitle("t");
        GovTicketV2SubmitFO.GroupInput gi = new GovTicketV2SubmitFO.GroupInput();
        gi.setPairId(PAIR_ID);
        gi.setSqlContent(sqlContent);
        fo.setGroups(List.of(gi));
        return fo;
    }

    private DmTicketDbStmtDO captureStmt() {
        ArgumentCaptor<DmTicketDbStmtDO> captor = ArgumentCaptor.forClass(DmTicketDbStmtDO.class);
        verify(stmtMapper).insert(captor.capture());
        return captor.getValue();
    }

    private static GhostRouteProbe probe(boolean probed, boolean uniqueKey, boolean exceeded, Long rows, Long bytes) {
        GhostRouteProbe p = new GhostRouteProbe();
        p.setProbed(probed);
        p.setTableFound(probed);
        p.setUniqueKeyPresent(uniqueKey);
        p.setExceeded(exceeded);
        p.setTableRows(rows);
        p.setTotalBytes(bytes);
        p.setSizeThresholdBytes(1024L * 1024 * 1024);
        p.setRowThreshold(10_000_000L);
        return p;
    }

    private static SplitScript alterScript(long index, String sql) {
        SplitScript script = new SplitScript();
        script.setIndex(index);
        script.setType(EnumSet.of(SplitQueryType.ALTER_TABLE));
        script.setScript(sql);
        return script;
    }

    private static SplitScript dmlScript(long index, String sql) {
        SplitScript script = new SplitScript();
        script.setIndex(index);
        script.setType(EnumSet.of(SplitQueryType.INSERT));
        script.setScript(sql);
        return script;
    }

    private static String readAll(java.io.Reader reader) {
        try {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[512];
            int n;
            while ((n = reader.read(buf)) > 0) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }
}
