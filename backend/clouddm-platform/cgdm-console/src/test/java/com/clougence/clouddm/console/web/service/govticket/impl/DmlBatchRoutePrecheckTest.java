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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.base.metadata.ds.DataSourceConfig;
import com.clougence.clouddm.base.metadata.ds.DataSourceType;
import com.clougence.clouddm.console.web.component.alert.AlertPushService;
import com.clougence.clouddm.console.web.component.analysis.QueryAnalysisService;
import com.clougence.clouddm.console.web.component.approval.ApprovalFlowService;
import com.clougence.clouddm.console.web.component.approval.ApprovalPersonService;
import com.clougence.clouddm.console.web.component.approval.ApprovalStateService;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.component.auth.DmAuthServiceForBiz;
import com.clougence.clouddm.console.web.component.dsconfig.DmDsConfigService;
import com.clougence.clouddm.console.web.component.execute.AutoExecService;
import com.clougence.clouddm.console.web.component.governance.GhostRouteFlags;
import com.clougence.clouddm.console.web.component.governance.GovReturnNotifyService;
import com.clougence.clouddm.console.web.component.governance.RollbackSqlGenerator;
import com.clougence.clouddm.console.web.component.governance.RiskAssessmentService;
import com.clougence.clouddm.console.web.component.governance.SqlRowEstimator;
import com.clougence.clouddm.console.web.component.governance.TicketSegment;
import com.clougence.clouddm.console.web.model.fo.govticket.GovTicketV2CheckFO;
import com.clougence.clouddm.console.web.model.fo.govticket.GovTicketV2SubmitFO;
import com.clougence.clouddm.console.web.model.vo.govticket.GovTicketV2CheckVO;
import com.clougence.clouddm.console.web.service.dbpair.DbPairService;
import com.clougence.clouddm.console.web.service.dmlbatch.DmlBatchEffectiveParams;
import com.clougence.clouddm.console.web.service.dmlbatch.DmlBatchKeyProbe;
import com.clougence.clouddm.console.web.service.dmlbatch.DmlBatchService;
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
import com.clougence.clouddm.platform.dal.model.dbpair.DmDbPairDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;
import com.clougence.clouddm.sdk.sql.SqlEngineSpi;
import com.clougence.clouddm.sdk.sql.SqlParserParameters;
import com.clougence.clouddm.sdk.sql.editor.rewrite.DmlBatchRangeSpec;
import com.clougence.clouddm.sdk.sql.editor.rewrite.DmlBatchShapeMO;
import com.clougence.clouddm.sdk.sql.editor.rewrite.RewriteSpi;
import com.clougence.clouddm.sdk.sql.parser.SplitQueryType;
import com.clougence.clouddm.sdk.sql.parser.SplitScript;
import com.clougence.clouddm.console.web.model.vo.ghost.GhostRouteProbe;
import com.clougence.utils.JsonUtils;

/**
 * B7 N1b (implement.md 单测矩阵「标记矩阵 / 段计划构建 / flags 双写」): the DML batch marking
 * seam of runPrecheck Step 6 — design §13 boundary matrix row by row (route / no-key FAIL /
 * shape reject / key-type reject / kill switch / other dialect+version / estimation fallback /
 * probe degraded / same-table accumulate hint / below-threshold status quo), the combined segment
 * plan build (DML_BATCH hole numbering, mixed AUTOEXEC merging, GHOST+DML_BATCH coexistence,
 * 1-based exec_order off-by-one pins, PROD_DML group segmentation, pure-normal byte-identity)
 * and the flags dual-write (a DML_BATCH-only ticket SETS ghostRouteFlags and hits the sweep
 * pre-filter — the R2 disposition pin).
 * <p>
 * Discipline: JUnit4 + hand-written mocks + ReflectionTestUtils + ArgumentCaptor; the shape gate
 * is mocked at the RewriteSpi seam (its AST contract is pinned by the N1a plugin suites), the
 * probe at the DmlBatchService seam (its channel-C contract is pinned by DmlBatchServiceProbeTest).
 */
public class DmlBatchRoutePrecheckTest {

    private GovTicketV2ServiceImpl  service;
    private TicketDbStmtDal         ticketDbStmtDal;
    private DmTicketDbStmtMapper    stmtMapper;
    private ApprovalDal             approvalDal;
    private DmApprovalMapper        approvalMapper;
    private SystemDal               systemDal;
    private GhostService            ghostService;
    private DmlBatchService         dmlBatchService;
    private SqlRowEstimator         sqlRowEstimator;
    private DmDsConfigService       dmDsConfigService;
    private QueryAnalysisService    queryAnalysisService;
    private RewriteSpi              rewriteSpi;
    private com.clougence.clouddm.console.web.component.governance.TicketSegmentService ticketSegmentService;
    private AutoExecService         autoExecService;
    private ApprovalStateService    approvalStateService;

    private static final String PUID    = "puid-001";
    private static final String UID     = "uid-001";
    private static final long   PAIR_ID = 300L;
    private static final long   DS_ID   = 20L;
    private static final String DB      = "prod_db";
    private static final long   THRESHOLD = 1_000_000L;

    /** per-test swap fixtures */
    private DmDsDO                   dsDO;
    private DataSourceConfig         dsConfig;
    private List<SplitScript>        splitScripts;
    private DmlBatchKeyProbe         probe;
    private SqlRowEstimator.EstimationResult estimation;
    /** shape-gate answers keyed by the statement SQL text */
    private final Map<String, DmlBatchShapeMO> shapesBySql = new HashMap<>();
    private DmlBatchShapeMO          defaultShape;
    /** ghost probe (PRE_DDL mixed tests) */
    private GhostRouteProbe          ghostProbe;

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

        DbChangeEventDal dbChangeEventDal = mock(DbChangeEventDal.class);
        when(dbChangeEventDal.eventMapper()).thenReturn(mock(DmDbChangeEventMapper.class));

        // pair → both sides resolve to the same DS/DB fixtures
        DbPairDal dbPairDal = mock(DbPairDal.class);
        DmDbPairMapper pairMapper = mock(DmDbPairMapper.class);
        when(dbPairDal.pairMapper()).thenReturn(pairMapper);
        DmDbPairDO pair = new DmDbPairDO();
        pair.setId(PAIR_ID);
        pair.setPreDsId(DS_ID);
        pair.setPreDbName(DB);
        pair.setProdDsId(DS_ID);
        pair.setProdDbName(DB);
        pair.setStatus("ENABLED");
        when(pairMapper.selectById(PAIR_ID)).thenReturn(pair);

        // datasource: MySQL 8.0 by default (the version gate passes; swap per test)
        DataSourceDal dsDal = mock(DataSourceDal.class);
        DmDsMapper dsMapper = mock(DmDsMapper.class);
        when(dsDal.dsMapper()).thenReturn(dsMapper);
        dsDO = new DmDsDO();
        dsDO.setId(DS_ID);
        dsDO.setDsEnvId(1L);
        dsDO.setInstanceId("inst-1");
        dsDO.setDataSourceType(DataSourceType.MySQL);
        dsDO.setVersion("8.0");
        when(dsMapper.queryDsIdentityById(anyLong())).thenAnswer(inv -> dsDO);

        dmDsConfigService = mock(DmDsConfigService.class);
        dsConfig = new DataSourceConfig();
        dsConfig.setDataSourceType(DataSourceType.MySQL);
        when(dmDsConfigService.fetchDsConfigFromExists(anyLong())).thenAnswer(inv -> dsConfig);
        when(dmDsConfigService.parseLevels(anyList())).thenReturn(
            mock(com.clougence.clouddm.console.web.component.dsconfig.mode.DsLevels.class));
        // shape-gate seam: SqlEngineSpi → RewriteSpi → analyzeDmlForBatch (per-sql fixture map)
        SqlEngineSpi sqlEngine = mock(SqlEngineSpi.class);
        rewriteSpi = mock(RewriteSpi.class);
        when(dmDsConfigService.fetchSqlEngineSpi(any(DataSourceConfig.class))).thenReturn(sqlEngine);
        when(sqlEngine.rewriteSpi(any())).thenReturn(rewriteSpi);
        when(dmDsConfigService.fetchSqlParserParameters(any(), any())).thenReturn(SqlParserParameters.empty());
        when(rewriteSpi.analyzeDmlForBatch(anyString(), anyString(), any())).thenAnswer(inv -> {
            String sql = inv.getArgument(1);
            DmlBatchShapeMO shape = shapesBySql.get(sql);
            return shape != null ? shape : defaultShape;
        });

        // split stream: per-test scripts
        queryAnalysisService = mock(QueryAnalysisService.class);
        when(queryAnalysisService.analysisSplitStream(any(), any(), any(), anyInt(), anyInt()))
            .thenAnswer(inv -> splitScripts == null ? java.util.stream.Stream.<SplitScript>empty()
                                                    : splitScripts.stream());
        when(queryAnalysisService.analysisRulesStream(any(), any(), any(), anyInt(), anyInt(), any()))
            .thenAnswer(inv -> java.util.stream.Stream.empty());

        systemDal = mock(SystemDal.class);
        when(systemDal.fetchSystemConf(anyString(), eq(Boolean.class))).thenReturn(Boolean.TRUE);
        com.clougence.clouddm.platform.dal.mapper.system.DmSysEnvMapper envMapper
            = mock(com.clougence.clouddm.platform.dal.mapper.system.DmSysEnvMapper.class);
        when(systemDal.envMapper()).thenReturn(envMapper);
        com.clougence.clouddm.platform.dal.model.system.DmSysEnvDO envDO
            = new com.clougence.clouddm.platform.dal.model.system.DmSysEnvDO();
        envDO.setEnvName("env-test");
        when(envMapper.queryByEnvID(anyString(), anyLong())).thenReturn(envDO);

        // B7 engine seam: gate open, threshold 1M, default params, healthy OK-key probe
        dmlBatchService = mock(DmlBatchService.class);
        // the gate answer mirrors the real version contract (its own matrix is pinned in
        // DmlBatchServiceProbeTest): MySQL ≥ 8 / PG ≥ 15 by the first decimal run
        when(dmlBatchService.isBatchRouteSupportedDs(any())).thenAnswer(inv -> {
            DmDsDO ds = inv.getArgument(0);
            if (ds == null || ds.getDataSourceType() == null || ds.getVersion() == null) {
                return false;
            }
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+").matcher(ds.getVersion());
            if (!m.find()) {
                return false;
            }
            int major = Integer.parseInt(m.group());
            return ds.getDataSourceType() == DataSourceType.MySQL ? major >= 8
                : ds.getDataSourceType() == DataSourceType.PostgreSQL && major >= 15;
        });
        when(dmlBatchService.isAutoRouteEnabled()).thenReturn(true);
        when(dmlBatchService.rowThreshold()).thenReturn(THRESHOLD);
        when(dmlBatchService.resolveEffectiveParams()).thenReturn(new DmlBatchEffectiveParams());
        probe = probe(DmlBatchKeyProbe.STATE_OK, "id", DmlBatchRangeSpec.KEY_TYPE_INT, 5_000_000L);
        when(dmlBatchService.probeForKey(anyString(), any(), any(), anyString(), any(), anyString()))
            .thenAnswer(inv -> probe);

        // ghost seam (PRE_DDL mixed tests): healthy exceeded+PK by default
        ghostService = mock(GhostService.class);
        ghostProbe = ghostProbe(true, true, true, 20_000_000L);
        when(ghostService.probeForRoute(anyString(), any(), any(), anyString(), anyString()))
            .thenAnswer(inv -> ghostProbe);

        NamingDao namingDao = mock(NamingDao.class);
        when(namingDao.genApprovalBizId()).thenReturn("biz-test");

        sqlRowEstimator = mock(SqlRowEstimator.class);
        when(sqlRowEstimator.estimate(any(), any(), any(), any(), anyLong()))
            .thenAnswer(inv -> estimation == null ? SqlRowEstimator.EstimationResult.unknown() : estimation);

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
        ReflectionTestUtils.setField(service, "dmlBatchService", dmlBatchService);
        ReflectionTestUtils.setField(service, "alertPushService", mock(AlertPushService.class));
        ReflectionTestUtils.setField(service, "approvalFlowService", mock(ApprovalFlowService.class));
        ReflectionTestUtils.setField(service, "approvalPersonService", mock(ApprovalPersonService.class));
        ReflectionTestUtils.setField(service, "approvalStateService", mock(ApprovalStateService.class));
        ReflectionTestUtils.setField(service, "dbPairService", mock(DbPairService.class));
        ReflectionTestUtils.setField(service, "dmAuthServiceForBiz", mock(DmAuthServiceForBiz.class));
        ReflectionTestUtils.setField(service, "govReturnNotifyService", mock(GovReturnNotifyService.class));
        ReflectionTestUtils.setField(service, "prodReleaseDal", mock(ProdReleaseDal.class));
        ReflectionTestUtils.setField(service, "schemaSnapshotService",
            mock(com.clougence.clouddm.console.web.component.governance.SchemaSnapshotService.class));
        autoExecService = mock(AutoExecService.class);
        ReflectionTestUtils.setField(service, "autoExecService", autoExecService);
        ticketSegmentService = mock(com.clougence.clouddm.console.web.component.governance.TicketSegmentService.class);
        ReflectionTestUtils.setField(service, "ticketSegmentService", ticketSegmentService);
        approvalStateService = mock(ApprovalStateService.class);
        ReflectionTestUtils.setField(service, "approvalStateService", approvalStateService);
        ReflectionTestUtils.setField(service, "dmEnvParamService", dmEnvParamService);
        ReflectionTestUtils.setField(service, "rdpOpAuditService",
            mock(com.clougence.rdp.service.RdpOpAuditService.class));

        // default fixture: ONE supported big UPDATE, estimated 5M rows
        splitScripts = List.of(updateScript(0, "UPDATE t_order SET status = 1 WHERE status = 0"));
        defaultShape = shape(true, "t_order", SplitQueryType.UPDATE, false);
        estimation = estimation(5_000_000L, new SqlRowEstimator.StmtEstimation(1, 5_000_000L));
    }

    // ==================== design §13 boundary matrix (via the /check read path) ====================

    @Test
    public void check_singleBigUpdate_okKey_routedToDmlBatchSegment() {
        GovTicketV2CheckVO.GroupResult gr = checkProdDml("UPDATE t_order SET status = 1 WHERE status = 0");

        assertEquals("PASS", gr.getCheckStatus());
        // the display carrier
        assertNotNull(gr.getDmlBatchRoutes());
        assertEquals(1, gr.getDmlBatchRoutes().size());
        GovTicketV2CheckVO.DmlBatchRouteVO route = gr.getDmlBatchRoutes().get(0);
        assertEquals(GovTicketV2CheckVO.DmlBatchRouteVO.ROUTE_DML_BATCH, route.getRoute());
        assertEquals(1, route.getStmtIndex()); // 1-based exec_order domain (R11)
        assertEquals("t_order", route.getTableName());
        assertEquals(Long.valueOf(5_000_000L), route.getEstimatedRows());
        assertEquals("id", route.getKeyColumn());
        assertEquals(DmlBatchRangeSpec.KEY_TYPE_INT, route.getKeyType());
        assertEquals(Boolean.TRUE, route.getIdempotent());
        assertEquals(Integer.valueOf(5000), route.getBatchSize());
        assertEquals(Long.valueOf(1000L), route.getApproxBatches()); // ceil(5M / 5000)
        assertNotNull(route.getMessage());
        assertTrue(route.getMessage().contains("t_order"));
        assertNotNull("shape MO embedded verbatim (obligation #7)", route.getShape());
        // the plan freeze
        assertNotNull(gr.getSegments());
        assertEquals(1, gr.getSegments().size());
        TicketSegment seg = gr.getSegments().get(0);
        assertEquals(TicketSegment.TYPE_DML_BATCH, seg.getType());
        assertEquals(1, seg.getSegIdx());
        assertEquals(Integer.valueOf(1), seg.getStmtIndex());
        assertEquals("t_order", seg.getTable());
        assertEquals(Long.valueOf(5_000_000L), seg.getEstimatedRows());
        assertEquals("UPDATE t_order SET status = 1 WHERE status = 0", seg.getSql()); // FULL frozen text
        assertEquals("id", seg.getBatchKey().getColumn());
        assertEquals(DmlBatchRangeSpec.KEY_TYPE_INT, seg.getBatchKey().getKeyType());
        assertEquals(Boolean.TRUE, seg.getIdempotent());
        // the probe ran exactly once with the statement's schema binding
        verify(dmlBatchService).probeForKey(eq(UID), any(), any(), eq(DB), any(), eq("t_order"));
    }

    @Test
    public void check_bigDelete_routed_idempotentByNature() {
        splitScripts = List.of(deleteScript(0, "DELETE FROM t_log WHERE created < '2020-01-01'"));
        defaultShape = shape(true, "t_log", SplitQueryType.DELETE, false);

        GovTicketV2CheckVO.GroupResult gr = checkProdDml("DELETE FROM t_log WHERE created < '2020-01-01'");

        assertEquals("PASS", gr.getCheckStatus());
        assertEquals(GovTicketV2CheckVO.DmlBatchRouteVO.ROUTE_DML_BATCH, gr.getDmlBatchRoutes().get(0).getRoute());
        assertEquals(Boolean.TRUE, gr.getSegments().get(0).getIdempotent()); // DELETE is naturally idempotent
    }

    @Test
    public void check_nonIdempotentUpdate_routedWithRiskFlag() {
        defaultShape = shape(true, "t_order", SplitQueryType.UPDATE, true); // self-referencing SET

        GovTicketV2CheckVO.GroupResult gr = checkProdDml("UPDATE t_order SET cnt = cnt + 1 WHERE status = 0");

        assertEquals("PASS", gr.getCheckStatus());
        GovTicketV2CheckVO.DmlBatchRouteVO route = gr.getDmlBatchRoutes().get(0);
        assertEquals(GovTicketV2CheckVO.DmlBatchRouteVO.ROUTE_DML_BATCH, route.getRoute());
        assertEquals(Boolean.FALSE, route.getIdempotent()); // 照常分批 + 黄旗 (never a rejection)
        // the NONIDEMPOTENT risk line is APPENDED to the announce copy — assert against the
        // same i18n resolution the production path uses (locale-independent, no vacuous
        // length()>0 disjunct; check-fix tightening)
        String riskLine = com.clougence.clouddm.console.web.global.i18n.DmI18nUtils.getMessage(
            com.clougence.clouddm.console.web.global.i18n.I18nDmMsgKeys.DML_BATCH_NONIDEMPOTENT_RISK.name());
        assertTrue("the non-idempotent risk line must ride the announce message",
            route.getMessage().contains(riskLine));
        assertEquals(Boolean.FALSE, gr.getSegments().get(0).getIdempotent());
    }

    @Test
    public void check_exceededNoKey_groupFailDmlNoKey_rejectionPresentation() {
        probe = probe(DmlBatchKeyProbe.STATE_NO_KEY, null, null, 5_000_000L);
        probe.setKeyDetail("no primary key and no unique key");

        GovTicketV2CheckVO.GroupResult gr = checkProdDml("UPDATE t_order SET status = 1 WHERE status = 0");

        // 裁决 #5: 提交拒绝呈现 (the /check red error; submit refuses the whole ticket)
        assertEquals("FAIL", gr.getCheckStatus());
        assertNotNull(gr.getErrorMessage());
        assertTrue(gr.getErrorMessage().contains("prod_db.t_order"));
        assertTrue(gr.getErrorMessage().contains("no primary key and no unique key"));
        assertNull("a rejected group writes no plan", gr.getSegments());
    }

    @Test
    public void check_exceededNullableUkOnly_sameNoKeyRejection_handoverObligation1() {
        probe = probe(DmlBatchKeyProbe.STATE_NO_KEY, null, null, 5_000_000L);
        probe.setKeyDetail("only nullable unique key (email) - NULL keys would never enter a batch");

        GovTicketV2CheckVO.GroupResult gr = checkProdDml("UPDATE t_order SET status = 1 WHERE status = 0");

        assertEquals("FAIL", gr.getCheckStatus());
        assertTrue(gr.getErrorMessage().contains("nullable"));
    }

    @Test
    public void check_belowThreshold_noProbeNoMarks_statusQuo() {
        estimation = estimation(500_000L, new SqlRowEstimator.StmtEstimation(1, 500_000L));

        GovTicketV2CheckVO.GroupResult gr = checkProdDml("UPDATE t_order SET status = 1 WHERE status = 0");

        assertEquals("PASS", gr.getCheckStatus());
        assertNull(gr.getDmlBatchRoutes());
        assertNull("pure-normal group keeps the legacy shape byte-identical (regression pin)", gr.getSegments());
        // the cheap group pre-gate: ZERO remote calls when every candidate is estimated below
        verify(dmlBatchService, never()).probeForKey(anyString(), any(), any(), anyString(), any(), anyString());
    }

    @Test
    public void check_exceededShapeRejected_greyHint_noRouteNoSegment() {
        defaultShape = DmlBatchShapeMO.reject(DmlBatchShapeMO.REJECT_MULTI_TABLE);

        GovTicketV2CheckVO.GroupResult gr = checkProdDml("UPDATE t_order o JOIN t_user u SET o.status = 1");

        assertEquals("PASS", gr.getCheckStatus()); // a rejection is NEVER a statement failure
        assertEquals(1, gr.getDmlBatchRoutes().size());
        GovTicketV2CheckVO.DmlBatchRouteVO route = gr.getDmlBatchRoutes().get(0);
        assertEquals(GovTicketV2CheckVO.DmlBatchRouteVO.ROUTE_DIRECT_SHAPE_UNSUPPORTED, route.getRoute());
        assertEquals(DmlBatchShapeMO.REJECT_MULTI_TABLE, route.getRejectCode());
        assertNull(gr.getSegments());
        verify(dmlBatchService, never()).probeForKey(anyString(), any(), any(), anyString(), any(), anyString());
    }

    @Test
    public void check_smallStatementShapeRejected_silentStatusQuo() {
        // the grey hint rides ONLY on confirmed over-threshold statements (design §13)
        estimation = estimation(500L, new SqlRowEstimator.StmtEstimation(1, 500L));
        defaultShape = DmlBatchShapeMO.reject(DmlBatchShapeMO.REJECT_COMMENT);

        GovTicketV2CheckVO.GroupResult gr = checkProdDml("UPDATE t_order SET status = 1 WHERE id = 5");

        assertEquals("PASS", gr.getCheckStatus());
        assertNull(gr.getDmlBatchRoutes());
    }

    @Test
    public void check_exceededKeyUnsupported_yellowFlag_directExecution() {
        probe = probe(DmlBatchKeyProbe.STATE_UNSUPPORTED, null, null, 5_000_000L);
        probe.setKeyDetail("composite primary key (id,tenant_id)");

        GovTicketV2CheckVO.GroupResult gr = checkProdDml("UPDATE t_order SET status = 1 WHERE status = 0");

        assertEquals("PASS", gr.getCheckStatus()); // yellow, NOT a rejection
        GovTicketV2CheckVO.DmlBatchRouteVO route = gr.getDmlBatchRoutes().get(0);
        assertEquals(GovTicketV2CheckVO.DmlBatchRouteVO.ROUTE_DIRECT_KEY_UNSUPPORTED, route.getRoute());
        assertEquals("composite primary key (id,tenant_id)", route.getKeyDetail());
        assertTrue(route.getMessage().contains("t_order"));
        assertNull(gr.getSegments());
    }

    @Test
    public void check_probeDegraded_greyHint_neverRejection() {
        probe = new DmlBatchKeyProbe(); // probed=false — timeout/failure degradation
        probe.setRowThreshold(THRESHOLD);

        GovTicketV2CheckVO.GroupResult gr = checkProdDml("UPDATE t_order SET status = 1 WHERE status = 0");

        assertEquals("PASS", gr.getCheckStatus()); // 探测不可得 ≠ 违规 — never a FAIL
        GovTicketV2CheckVO.DmlBatchRouteVO route = gr.getDmlBatchRoutes().get(0);
        assertEquals(GovTicketV2CheckVO.DmlBatchRouteVO.ROUTE_DIRECT_PROBE_DEGRADED, route.getRoute());
        assertNull(gr.getSegments());
    }

    @Test
    public void check_estimationUnavailable_tableRowsFallback_routes() {
        // 裁决 #6 combined decision: estimate unavailable → the probed B4 table rows decide
        estimation = SqlRowEstimator.EstimationResult.unknown();
        probe = probe(DmlBatchKeyProbe.STATE_OK, "id", DmlBatchRangeSpec.KEY_TYPE_INT, 8_000_000L);

        GovTicketV2CheckVO.GroupResult gr = checkProdDml("UPDATE t_order SET status = 1 WHERE status = 0");

        assertEquals("PASS", gr.getCheckStatus());
        GovTicketV2CheckVO.DmlBatchRouteVO route = gr.getDmlBatchRoutes().get(0);
        assertEquals(GovTicketV2CheckVO.DmlBatchRouteVO.ROUTE_DML_BATCH, route.getRoute());
        assertEquals(Long.valueOf(8_000_000L), route.getEstimatedRows()); // the fallback signal is frozen
        assertEquals(Long.valueOf(8_000_000L), gr.getSegments().get(0).getEstimatedRows());
    }

    @Test
    public void check_estimationUnavailable_smallTable_statusQuoNoRejection() {
        estimation = SqlRowEstimator.EstimationResult.unknown();
        probe = probe(DmlBatchKeyProbe.STATE_OK, "id", DmlBatchRangeSpec.KEY_TYPE_INT, 500L);

        GovTicketV2CheckVO.GroupResult gr = checkProdDml("UPDATE t_order SET status = 1 WHERE status = 0");

        assertEquals("PASS", gr.getCheckStatus());
        assertNull(gr.getDmlBatchRoutes());
        assertNull(gr.getSegments());
        // a small keyless table under the fallback is NOT rejected either (不误伤)
    }

    @Test
    public void check_estimationUnavailable_noKeySmallTable_noFail() {
        // the NO_KEY rejection requires the exceedance FIRST (shape pass ∧ over threshold)
        estimation = SqlRowEstimator.EstimationResult.unknown();
        probe = probe(DmlBatchKeyProbe.STATE_NO_KEY, null, null, 500L);

        GovTicketV2CheckVO.GroupResult gr = checkProdDml("UPDATE t_order SET status = 1 WHERE status = 0");

        assertEquals("PASS", gr.getCheckStatus());
    }

    @Test
    public void check_killSwitchOff_noEvaluationNoProbeNoMarks() {
        when(dmlBatchService.isAutoRouteEnabled()).thenReturn(false);

        GovTicketV2CheckVO.GroupResult gr = checkProdDml("UPDATE t_order SET status = 1 WHERE status = 0");

        assertEquals("PASS", gr.getCheckStatus());
        assertNull(gr.getDmlBatchRoutes());
        assertNull(gr.getSegments());
        // 止血开关 semantics: no probe, no shape analysis, no marks (the gate read itself is the
        // only interaction with the engine)
        verify(dmlBatchService, never()).probeForKey(anyString(), any(), any(), anyString(), any(), anyString());
        verify(dmlBatchService, never()).resolveEffectiveParams();
        verifyNoInteractions(rewriteSpi);
    }

    @Test
    public void check_unsupportedDialectOrVersion_notEvaluated() {
        dsDO.setVersion("5.7"); // the console-side version gate (obligation #2)

        GovTicketV2CheckVO.GroupResult gr = checkProdDml("UPDATE t_order SET status = 1 WHERE status = 0");

        assertEquals("PASS", gr.getCheckStatus());
        assertNull(gr.getDmlBatchRoutes());
        verify(dmlBatchService, never()).probeForKey(anyString(), any(), any(), anyString(), any(), anyString());
    }

    @Test
    public void check_postgres15_evaluated() {
        dsDO.setDataSourceType(DataSourceType.PostgreSQL);
        dsDO.setVersion("15.4");
        // DataSourceConfig is write-once — swap a fresh PG config instance into the seam
        DataSourceConfig pgConfig = new DataSourceConfig();
        pgConfig.setDataSourceType(DataSourceType.PostgreSQL);
        when(dmDsConfigService.fetchDsConfigFromExists(anyLong())).thenReturn(pgConfig);

        GovTicketV2CheckVO.GroupResult gr = checkProdDml("UPDATE t_order SET status = 1 WHERE status = 0");

        assertEquals("PASS", gr.getCheckStatus());
        assertEquals(GovTicketV2CheckVO.DmlBatchRouteVO.ROUTE_DML_BATCH, gr.getDmlBatchRoutes().get(0).getRoute());
    }

    @Test
    public void check_preDdlGroupWithBigDml_alsoEvaluated_ruling7gate() {
        // the marking gate covers PRE_DDL groups too (裁决 #7 — the mixed matrix pins are N3)
        GovTicketV2CheckVO.GroupResult gr = checkPreDdl("UPDATE t_order SET status = 1 WHERE status = 0");

        assertEquals("PASS", gr.getCheckStatus());
        assertEquals(GovTicketV2CheckVO.DmlBatchRouteVO.ROUTE_DML_BATCH, gr.getDmlBatchRoutes().get(0).getRoute());
    }

    @Test
    public void check_sameTableAccumulation_displayHint_noRouting() {
        // R13 拆句博弈 display-level counter: 3 small updates on one table, Σ ≥ threshold
        splitScripts = List.of(
            updateScript(0, "UPDATE t_order SET a = 1 WHERE id < 100"),
            updateScript(1, "UPDATE t_order SET b = 2 WHERE id >= 100 AND id < 200"),
            updateScript(2, "UPDATE t_order SET c = 3 WHERE id >= 200"));
        shapesBySql.put("UPDATE t_order SET a = 1 WHERE id < 100", shape(true, "t_order", SplitQueryType.UPDATE, false));
        shapesBySql.put("UPDATE t_order SET b = 2 WHERE id >= 100 AND id < 200", shape(true, "t_order", SplitQueryType.UPDATE, false));
        shapesBySql.put("UPDATE t_order SET c = 3 WHERE id >= 200", shape(true, "t_order", SplitQueryType.UPDATE, false));
        estimation = estimation(1_200_000L,
            new SqlRowEstimator.StmtEstimation(1, 400_000L),
            new SqlRowEstimator.StmtEstimation(2, 400_000L),
            new SqlRowEstimator.StmtEstimation(3, 400_000L));

        GovTicketV2CheckVO.GroupResult gr = checkProdDml("...");

        assertEquals("PASS", gr.getCheckStatus());
        assertNull("no per-statement routing", gr.getSegments());
        assertEquals(1, gr.getDmlBatchRoutes().size());
        GovTicketV2CheckVO.DmlBatchRouteVO hint = gr.getDmlBatchRoutes().get(0);
        assertEquals(GovTicketV2CheckVO.DmlBatchRouteVO.ROUTE_SAME_TABLE_ACCUMULATE, hint.getRoute());
        assertEquals(0, hint.getStmtIndex()); // group-level entry
        assertEquals("t_order", hint.getTableName());
        assertEquals(Long.valueOf(1_200_000L), hint.getEstimatedRows());
        verify(dmlBatchService, never()).probeForKey(anyString(), any(), any(), anyString(), any(), anyString());
    }

    // ==================== segment plan construction (hole numbering / merging / domain) ====================

    @Test
    public void plan_bigDmlSecondStatement_holeNumbering_andAutoexecMerge() {
        // [small UPDATE, big DELETE] → [AUTOEXEC[1,1], DML_BATCH(2)] — the routed statement
        // consumes its GLOBAL number without a task row (the exec_order hole, R11/stmt_index)
        splitScripts = List.of(
            updateScript(0, "UPDATE t_small SET a = 1 WHERE id = 1"),
            deleteScript(1, "DELETE FROM t_big WHERE created < '2020-01-01'"));
        shapesBySql.put("UPDATE t_small SET a = 1 WHERE id = 1", shape(true, "t_small", SplitQueryType.UPDATE, false));
        shapesBySql.put("DELETE FROM t_big WHERE created < '2020-01-01'", shape(true, "t_big", SplitQueryType.DELETE, false));
        estimation = estimation(5_100_000L,
            new SqlRowEstimator.StmtEstimation(1, 100_000L),       // below threshold → stays AUTOEXEC
            new SqlRowEstimator.StmtEstimation(2, 5_000_000L));    // exceeded → routed

        GovTicketV2CheckVO.GroupResult gr = checkProdDml("...");

        List<TicketSegment> plan = gr.getSegments();
        assertNotNull(plan);
        assertEquals(2, plan.size());
        assertEquals(TicketSegment.TYPE_AUTOEXEC, plan.get(0).getType());
        assertEquals(1, plan.get(0).getSegIdx());
        assertEquals(List.of(1, 1), plan.get(0).getStmtRange()); // 1-based domain, small stmt keeps its number
        assertEquals(TicketSegment.TYPE_DML_BATCH, plan.get(1).getType());
        assertEquals(2, plan.get(1).getSegIdx());
        assertEquals(Integer.valueOf(2), plan.get(1).getStmtIndex()); // NOT 1 — the 0-based preview index is never used
        assertEquals("t_big", plan.get(1).getTable());
    }

    @Test
    public void plan_bigDmlFirstStatement_trailingAutoexec() {
        // [big UPDATE, small DELETE] → [DML_BATCH(1), AUTOEXEC[2,2]]
        splitScripts = List.of(
            updateScript(0, "UPDATE t_big SET a = 1 WHERE status = 0"),
            deleteScript(1, "DELETE FROM t_small WHERE id = 3"));
        shapesBySql.put("UPDATE t_big SET a = 1 WHERE status = 0", shape(true, "t_big", SplitQueryType.UPDATE, false));
        shapesBySql.put("DELETE FROM t_small WHERE id = 3", shape(true, "t_small", SplitQueryType.DELETE, false));
        estimation = estimation(5_000_100L,
            new SqlRowEstimator.StmtEstimation(1, 5_000_000L),
            new SqlRowEstimator.StmtEstimation(2, 100L));

        GovTicketV2CheckVO.GroupResult gr = checkProdDml("...");

        List<TicketSegment> plan = gr.getSegments();
        assertEquals(2, plan.size());
        assertEquals(TicketSegment.TYPE_DML_BATCH, plan.get(0).getType());
        assertEquals(Integer.valueOf(1), plan.get(0).getStmtIndex());
        assertEquals(TicketSegment.TYPE_AUTOEXEC, plan.get(1).getType());
        assertEquals(List.of(2, 2), plan.get(1).getStmtRange());
    }

    @Test
    public void plan_twoRoutedStatements_consecutiveSegments_noAutoexecBetween() {
        splitScripts = List.of(
            updateScript(0, "UPDATE t_a SET x = 1 WHERE f = 0"),
            deleteScript(1, "DELETE FROM t_b WHERE g = 0"));
        shapesBySql.put("UPDATE t_a SET x = 1 WHERE f = 0", shape(true, "t_a", SplitQueryType.UPDATE, false));
        shapesBySql.put("DELETE FROM t_b WHERE g = 0", shape(true, "t_b", SplitQueryType.DELETE, false));
        estimation = estimation(10_000_000L,
            new SqlRowEstimator.StmtEstimation(1, 5_000_000L),
            new SqlRowEstimator.StmtEstimation(2, 5_000_000L));

        GovTicketV2CheckVO.GroupResult gr = checkProdDml("...");

        List<TicketSegment> plan = gr.getSegments();
        assertEquals(2, plan.size());
        assertEquals(TicketSegment.TYPE_DML_BATCH, plan.get(0).getType());
        assertEquals(TicketSegment.TYPE_DML_BATCH, plan.get(1).getType());
        assertEquals(1, plan.get(0).getSegIdx());
        assertEquals(2, plan.get(1).getSegIdx());
    }

    @Test
    public void plan_mixedGhostAndDmlBatch_preDdlThreeSegmentTypes() {
        // PRE 组 [大 ALTER, 小 DML, 大 DML] → [GHOST(1), AUTOEXEC[2,2], DML_BATCH(3)]
        // (裁决 #7 coexistence — the full mixed matrix pins are N3, this is the plan-build shape)
        splitScripts = List.of(
            alterScript(0, "ALTER TABLE t_big ADD COLUMN c1 INT"),
            updateScript(1, "UPDATE t_small SET a = 1 WHERE id = 1"),
            deleteScript(2, "DELETE FROM t_big WHERE created < '2020-01-01'"));
        shapesBySql.put("UPDATE t_small SET a = 1 WHERE id = 1", shape(true, "t_small", SplitQueryType.UPDATE, false));
        shapesBySql.put("DELETE FROM t_big WHERE created < '2020-01-01'", shape(true, "t_big", SplitQueryType.DELETE, false));
        estimation = estimation(25_100_000L,
            new SqlRowEstimator.StmtEstimation(2, 100_000L),
            new SqlRowEstimator.StmtEstimation(3, 5_000_000L));

        GovTicketV2CheckVO.GroupResult gr = checkPreDdl("...");

        List<TicketSegment> plan = gr.getSegments();
        assertNotNull(plan);
        assertEquals(3, plan.size());
        assertEquals(TicketSegment.TYPE_GHOST, plan.get(0).getType());
        assertEquals(Integer.valueOf(1), plan.get(0).getStmtIndex());
        assertEquals(TicketSegment.TYPE_AUTOEXEC, plan.get(1).getType());
        assertEquals(List.of(2, 2), plan.get(1).getStmtRange());
        assertEquals(TicketSegment.TYPE_DML_BATCH, plan.get(2).getType());
        assertEquals(Integer.valueOf(3), plan.get(2).getStmtIndex());
        // the ghost display carrier is untouched by the DML evaluation
        assertNotNull(gr.getGhostRoutes());
    }

    @Test
    public void plan_pureNormalGroup_noPlanWritten_byteIdentityPin() {
        splitScripts = List.of(
            updateScript(0, "UPDATE t_small SET a = 1 WHERE id = 1"),
            insertScript(1, "INSERT INTO t_x VALUES (1)"));
        shapesBySql.put("UPDATE t_small SET a = 1 WHERE id = 1", shape(true, "t_small", SplitQueryType.UPDATE, false));
        estimation = estimation(100L, new SqlRowEstimator.StmtEstimation(1, 100L));

        GovTicketV2CheckVO.GroupResult gr = checkProdDml("...");

        assertEquals("PASS", gr.getCheckStatus());
        assertNull("no routed segment → NO plan carrier at all (legacy precheck_result shape)", gr.getSegments());
    }

    @Test
    public void plan_insertStatements_neverCandidates() {
        // INSERT is out of the v1 scope — a big INSERT neither routes nor probes
        splitScripts = List.of(insertScript(0, "INSERT INTO t_order SELECT * FROM t_tmp"));
        estimation = estimation(9_000_000L, new SqlRowEstimator.StmtEstimation(1, 9_000_000L));

        GovTicketV2CheckVO.GroupResult gr = checkProdDml("...");

        assertEquals("PASS", gr.getCheckStatus());
        assertNull(gr.getDmlBatchRoutes());
        assertNull(gr.getSegments());
        verify(dmlBatchService, never()).probeForKey(anyString(), any(), any(), anyString(), any(), anyString());
    }

    // ==================== flags dual-write (R2 disposition) ====================

    @Test
    public void submit_dmlBatchOnlyGroup_setsFlags_sweepPreFilterHits() {
        splitScripts = List.of(updateScript(0, "UPDATE t_order SET status = 1 WHERE status = 0"));
        estimation = estimation(5_000_000L, new SqlRowEstimator.StmtEstimation(1, 5_000_000L));

        service.submit(PUID, UID, submitFO("PROD_DML", "UPDATE t_order SET status = 1 WHERE status = 0"));

        // the flags dual-write: a DML_BATCH-only ticket (NO ghost segment) still sets its flag —
        // without it the WAIT_EXEC sweep pre-filter (anyRouted) would skip the ticket forever (R2)
        DmApprovalDO ticket = captureTicket();
        ApprovalMO mo = JsonUtils.toObj(ticket.getTicketInfo(), ApprovalMO.class);
        assertNotNull(mo.getGhostRouteFlags());
        assertEquals(List.of(Boolean.TRUE), mo.getGhostRouteFlags());
        assertTrue("the sweep pre-filter MUST hit a DML_BATCH-only ticket", GhostRouteFlags.anyRouted(mo));
        assertTrue(GhostRouteFlags.isRouted(mo, 0));

        // the group row carries the plan (the segments carrier) with the frozen key
        DmTicketDbStmtDO stmt = captureStmt();
        Map<?, ?> precheck = JsonUtils.toObj(stmt.getPrecheckResult(), Map.class);
        assertNotNull(precheck.get("segments"));
        assertNotNull(precheck.get("dmlBatchRoutes"));
        List<TicketSegment> plan = TicketSegment.parsePlan(stmt.getPrecheckResult());
        assertEquals(1, plan.size());
        assertEquals(TicketSegment.TYPE_DML_BATCH, plan.get(0).getType());
        assertEquals("id", plan.get(0).getBatchKey().getColumn());
        assertTrue(TicketSegment.isSegmentedPlan(stmt.getPrecheckResult())); // the decision-order gate sees it
    }

    @Test
    public void submit_unroutedTicket_flagsAbsent_legacyTicketInfoByteShape() {
        estimation = estimation(500L, new SqlRowEstimator.StmtEstimation(1, 500L));

        service.submit(PUID, UID, submitFO("PROD_DML", "UPDATE t_order SET status = 1 WHERE status = 0"));

        DmApprovalDO ticket = captureTicket();
        ApprovalMO mo = JsonUtils.toObj(ticket.getTicketInfo(), ApprovalMO.class);
        assertNull("nothing routed → the field stays absent (legacy shape)", mo.getGhostRouteFlags());
        assertFalse(GhostRouteFlags.anyRouted(mo));
    }

    @Test
    public void submit_noKeyRejection_wholeTicketRefused_noRowsWritten() {
        probe = probe(DmlBatchKeyProbe.STATE_NO_KEY, null, null, 5_000_000L);
        probe.setKeyDetail("no primary key and no unique key");

        try {
            service.submit(PUID, UID, submitFO("PROD_DML", "UPDATE t_order SET status = 1 WHERE status = 0"));
            org.junit.Assert.fail("expected the DML_NO_KEY rejection to refuse the whole ticket");
        } catch (com.clougence.clouddm.api.common.exception.ErrorMessageException expected) {
            assertNotNull(expected.getMessage());
        }
        verify(stmtMapper, never()).insert(any(DmTicketDbStmtDO.class));
        verify(approvalMapper, never()).insert(any(DmApprovalDO.class));
    }

    // ==================== External resubmit: the decision order closes the R18 bypass ====================

    @Test
    public void externalResubmit_revisedTextRoutesDmlBatch_segmentBranchNotDirectJob() {
        // an External PROD_DML ticket in REVISION; the revised text produces a DML_BATCH plan —
        // the direct-to-exec branch must take the SEGMENTS decision (dispatchFirstSegment), NOT
        // createGroupJob over the full text (which would silently bypass the batch route, R18)
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(999L);
        ticket.setBizId("biz-ext");
        ticket.setPrimaryUid(PUID);
        ticket.setOwnerUid(UID);
        ticket.setTicketStatus(com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus.REVISION);
        ticket.setApproType(com.clougence.clouddm.platform.dal.model.approval.ApprovalType.External);
        ticket.setBindDsId(DS_ID);
        ApprovalMO mo = new ApprovalMO();
        mo.setTicketType("PROD_DML");
        mo.setApprovalMode("DIRECT");
        ticket.setTicketInfo(JsonUtils.toJson(mo));
        when(approvalMapper.queryById(999L)).thenReturn(ticket);

        DmTicketDbStmtDO failedGroup = new DmTicketDbStmtDO();
        failedGroup.setId(100L);
        failedGroup.setTicketId(999L);
        failedGroup.setPairId(PAIR_ID);
        failedGroup.setDsId(DS_ID);
        failedGroup.setDbName(DB);
        failedGroup.setSqlContent("UPDATE t_order SET status = 1 WHERE status = 0");
        failedGroup.setPrecheckResult("{\"checkStatus\":\"FAIL\"}"); // stale pre-revision carrier
        failedGroup.setExecStatus("FAILED");
        when(stmtMapper.queryByTicketId(999L)).thenReturn(List.of(failedGroup));

        splitScripts = List.of(updateScript(0, "UPDATE t_order SET status = 1 WHERE status = 0"));
        estimation = estimation(5_000_000L, new SqlRowEstimator.StmtEstimation(1, 5_000_000L));

        com.clougence.clouddm.console.web.model.fo.govticket.GovTicketV2ResubmitFO fo
            = new com.clougence.clouddm.console.web.model.fo.govticket.GovTicketV2ResubmitFO();
        fo.setTicketId(999L);
        fo.setReason("retry the big update");
        com.clougence.clouddm.console.web.model.fo.govticket.GovTicketV2ResubmitFO.GroupInput gi
            = new com.clougence.clouddm.console.web.model.fo.govticket.GovTicketV2ResubmitFO.GroupInput();
        gi.setGroupId(100L);
        gi.setSqlContent("UPDATE t_order SET status = 1 WHERE status = 0");
        fo.setGroups(List.of(gi));

        service.resubmit(PUID, UID, fo);

        // the SEGMENTS branch fired on the FRESH (recomputed) plan — the Step-3 in-memory sync
        // is what makes the decision see it (the DO list was loaded pre-revision)
        verify(ticketSegmentService).dispatchFirstSegment(same(ticket), same(failedGroup),
            eq(true), // DML changeType → transactional projection (the AUTOEXEC-segment config)
            eq(com.clougence.clouddm.api.console.autoexec.ErrorStrategy.NONE), anyString(), eq("SYSTEM"));
        verify(autoExecService, never()).createGroupJob(any(), anyString(), org.mockito.ArgumentMatchers.anyBoolean(),
            any(), anyString(), anyString());
        verify(autoExecService, never()).createGroupJob(any(), anyString(), org.mockito.ArgumentMatchers.anyBoolean(),
            any(), any(), any(), any(), any(), anyString(), anyString());
        verify(approvalStateService).markExecutionDispatched("biz-ext");
        // the runtime reset rode along (the fresh plan re-initializes at dispatch time)
        verify(stmtMapper).updateExecSegments(100L, null);
        // R2 dual-write on the RESUBMIT side (check-fix pin): the rebuilt positional flags carry
        // the freshly-routed DML_BATCH group (rebuildGhostRouteFlags keys on the widened
        // hasRoutedMark) — a resubmit-routed group without its flag would lose the sweep repair
        ArgumentCaptor<DmApprovalDO> ticketCaptor = ArgumentCaptor.forClass(DmApprovalDO.class);
        verify(approvalMapper).updateById(ticketCaptor.capture());
        ApprovalMO persistedMo = JsonUtils.toObj(ticketCaptor.getValue().getTicketInfo(), ApprovalMO.class);
        assertEquals(List.of(Boolean.TRUE), persistedMo.getGhostRouteFlags());
        assertTrue(GhostRouteFlags.anyRouted(persistedMo));
    }

    // ==================== N3: group-level session-state gate (N1a handover obligation #2) ====================
    // The batch loop opens a FRESH channel-C session per tick — a group carrying SET/session-state
    // statements (anything outside the DML∪DDL whitelist) makes its batched statements lose that
    // context silently, so the WHOLE GROUP gives up DML_BATCH routing (display key). The NO_KEY
    // rejection is UNAFFECTED (the §9.8.3 violation stands regardless of routing).

    @Test
    public void check_groupWithSetStatement_bigUpdateNotRouted_sessionStateHint() {
        splitScripts = List.of(
            script(0, "SET @cutoff = '2020-01-01'", SplitQueryType.SESSION_VARIABLE_RW),
            updateScript(1, "UPDATE t_order SET status = 1 WHERE status = 0"));
        estimation = estimation(5_000_000L, new SqlRowEstimator.StmtEstimation(2, 5_000_000L));

        GovTicketV2CheckVO.GroupResult gr = checkProdDml("...");

        assertEquals("PASS", gr.getCheckStatus()); // the downgrade is NEVER a rejection
        assertNull("整组放弃 DML_BATCH 路由 — no plan at all", gr.getSegments());
        assertEquals(1, gr.getDmlBatchRoutes().size());
        GovTicketV2CheckVO.DmlBatchRouteVO route = gr.getDmlBatchRoutes().get(0);
        assertEquals(GovTicketV2CheckVO.DmlBatchRouteVO.ROUTE_DIRECT_GROUP_SESSION_STATE, route.getRoute());
        assertEquals(2, route.getStmtIndex()); // the UPDATE's 1-based exec_order position (SET occupies 1)
        assertEquals("t_order", route.getTableName());
        assertEquals(Long.valueOf(5_000_000L), route.getEstimatedRows());
        assertTrue(route.getMessage().contains("t_order"));
        assertNotNull("shape MO embedded verbatim (obligation #7 parity)", route.getShape());
        // the probe still ran (the key state feeds the unaffected rejection decision)
        verify(dmlBatchService).probeForKey(eq(UID), any(), any(), eq(DB), any(), eq("t_order"));
    }

    @Test
    public void check_groupWithSelectStatement_whitelistIsDmlDdlOnly_notRouted() {
        // SELECT is NEITHER isDmlType NOR isDdlType → the conservative whitelist fires the gate
        // (SELECT ... INTO @v / set_config(...) can carry session state — 宁可误拒)
        splitScripts = List.of(
            script(0, "SELECT COUNT(*) FROM t_order", SplitQueryType.SELECT),
            deleteScript(1, "DELETE FROM t_order WHERE status = 9"));
        shapesBySql.put("DELETE FROM t_order WHERE status = 9", shape(true, "t_order", SplitQueryType.DELETE, false));
        estimation = estimation(5_000_000L, new SqlRowEstimator.StmtEstimation(2, 5_000_000L));

        GovTicketV2CheckVO.GroupResult gr = checkProdDml("...");

        assertEquals("PASS", gr.getCheckStatus());
        assertNull(gr.getSegments());
        assertEquals(GovTicketV2CheckVO.DmlBatchRouteVO.ROUTE_DIRECT_GROUP_SESSION_STATE,
            gr.getDmlBatchRoutes().get(0).getRoute());
    }

    @Test
    public void check_groupWithSetStatement_noKeyStillRejected_rejectionIndependentOfRouting() {
        // the session-state gate downgrades ROUTING only — an over-threshold keyless DML stays a
        // §9.8.3 violation and the 裁决 #5 rejection fires exactly as in a pure group
        splitScripts = List.of(
            script(0, "SET @cutoff = '2020-01-01'", SplitQueryType.SESSION_VARIABLE_RW),
            updateScript(1, "UPDATE t_order SET status = 1 WHERE status = 0"));
        estimation = estimation(5_000_000L, new SqlRowEstimator.StmtEstimation(2, 5_000_000L));
        probe = probe(DmlBatchKeyProbe.STATE_NO_KEY, null, null, 5_000_000L);
        probe.setKeyDetail("no primary key and no unique key");

        GovTicketV2CheckVO.GroupResult gr = checkProdDml("...");

        assertEquals("FAIL", gr.getCheckStatus());
        assertEquals(GovTicketV2CheckVO.FAIL_CODE_DML_NO_KEY, gr.getFailCode());
        assertNotNull(gr.getDmlNoKeyReject());
        assertEquals("t_order", gr.getDmlNoKeyReject().getTableName());
    }

    @Test
    public void check_preDdlMixedAlterAndBigDml_ddlWhitelisted_bothStillRouted() {
        // the PRE mixed matrix (裁决 #7): DDL is whitelisted — [大 ALTER, 大 DML] keeps BOTH routes
        // ([GHOST(1), DML_BATCH(2)]) and the session-state gate never fires
        splitScripts = List.of(
            alterScript(0, "ALTER TABLE t_big ADD COLUMN c1 INT"),
            deleteScript(1, "DELETE FROM t_order WHERE created < '2020-01-01'"));
        shapesBySql.put("DELETE FROM t_order WHERE created < '2020-01-01'",
            shape(true, "t_order", SplitQueryType.DELETE, false));
        estimation = estimation(25_000_000L, new SqlRowEstimator.StmtEstimation(2, 5_000_000L));

        GovTicketV2CheckVO.GroupResult gr = checkPreDdl("...");

        assertEquals("PASS", gr.getCheckStatus());
        List<TicketSegment> plan = gr.getSegments();
        assertNotNull(plan);
        assertEquals(2, plan.size());
        assertEquals(TicketSegment.TYPE_GHOST, plan.get(0).getType());
        assertEquals(Integer.valueOf(1), plan.get(0).getStmtIndex());
        assertEquals(TicketSegment.TYPE_DML_BATCH, plan.get(1).getType());
        assertEquals(Integer.valueOf(2), plan.get(1).getStmtIndex());
        assertEquals(GovTicketV2CheckVO.DmlBatchRouteVO.ROUTE_DML_BATCH, gr.getDmlBatchRoutes().get(0).getRoute());
    }

    @Test
    public void check_sessionStateGroup_downgradedTableSuppressesAccumulateHint() {
        // the downgraded statement's display supersedes the R13 accumulate hint for its table
        // (one per-statement line, no redundant group-level line for the same table)
        splitScripts = List.of(
            script(0, "SET @x = 1", SplitQueryType.SESSION_VARIABLE_RW),
            updateScript(1, "UPDATE t_order SET a = 1 WHERE id < 100"),
            updateScript(2, "UPDATE t_order SET status = 1 WHERE status = 0"));
        shapesBySql.put("UPDATE t_order SET a = 1 WHERE id < 100", shape(true, "t_order", SplitQueryType.UPDATE, false));
        estimation = estimation(5_400_000L,
            new SqlRowEstimator.StmtEstimation(2, 400_000L),       // below threshold → accumulate bookkeeping
            new SqlRowEstimator.StmtEstimation(3, 5_000_000L));   // exceeded → downgraded by the gate

        GovTicketV2CheckVO.GroupResult gr = checkProdDml("...");

        assertEquals("PASS", gr.getCheckStatus());
        assertNull(gr.getSegments());
        assertEquals("exactly one display line — the accumulate hint is suppressed", 1, gr.getDmlBatchRoutes().size());
        assertEquals(GovTicketV2CheckVO.DmlBatchRouteVO.ROUTE_DIRECT_GROUP_SESSION_STATE,
            gr.getDmlBatchRoutes().get(0).getRoute());
        assertEquals(3, gr.getDmlBatchRoutes().get(0).getStmtIndex());
    }

    // ==================== fixture helpers ====================

    private GovTicketV2CheckVO.GroupResult checkProdDml(String sqlContent) {
        GovTicketV2CheckFO fo = new GovTicketV2CheckFO();
        fo.setTicketType("PROD_DML");
        GovTicketV2CheckFO.GroupInput gi = new GovTicketV2CheckFO.GroupInput();
        gi.setPairId(PAIR_ID);
        gi.setSqlContent(sqlContent);
        fo.setGroups(List.of(gi));
        return service.check(PUID, UID, fo).getGroups().get(0);
    }

    private GovTicketV2CheckVO.GroupResult checkPreDdl(String sqlContent) {
        GovTicketV2CheckFO fo = new GovTicketV2CheckFO();
        fo.setTicketType("PRE_DDL");
        GovTicketV2CheckFO.GroupInput gi = new GovTicketV2CheckFO.GroupInput();
        gi.setPairId(PAIR_ID);
        gi.setSqlContent(sqlContent);
        fo.setGroups(List.of(gi));
        return service.check(PUID, UID, fo).getGroups().get(0);
    }

    private GovTicketV2SubmitFO submitFO(String ticketType, String sqlContent) {
        GovTicketV2SubmitFO fo = new GovTicketV2SubmitFO();
        fo.setTicketType(ticketType);
        fo.setTicketTitle("t");
        GovTicketV2SubmitFO.GroupInput gi = new GovTicketV2SubmitFO.GroupInput();
        gi.setPairId(PAIR_ID);
        gi.setSqlContent(sqlContent);
        fo.setGroups(List.of(gi));
        return fo;
    }

    private DmApprovalDO captureTicket() {
        ArgumentCaptor<DmApprovalDO> captor = ArgumentCaptor.forClass(DmApprovalDO.class);
        verify(approvalMapper).insert(captor.capture());
        return captor.getValue();
    }

    private DmTicketDbStmtDO captureStmt() {
        ArgumentCaptor<DmTicketDbStmtDO> captor = ArgumentCaptor.forClass(DmTicketDbStmtDO.class);
        verify(stmtMapper).insert(captor.capture());
        return captor.getValue();
    }

    private static DmlBatchKeyProbe probe(String state, String column, String keyType, Long tableRows) {
        DmlBatchKeyProbe p = new DmlBatchKeyProbe();
        p.setProbed(true);
        p.setTableFound(true);
        p.setTableRows(tableRows);
        p.setKeyState(state);
        p.setKeyColumn(column);
        p.setKeyType(keyType);
        p.setRowThreshold(THRESHOLD);
        return p;
    }

    private static GhostRouteProbe ghostProbe(boolean probed, boolean uniqueKey, boolean exceeded, Long rows) {
        GhostRouteProbe p = new GhostRouteProbe();
        p.setProbed(probed);
        p.setTableFound(probed);
        p.setUniqueKeyPresent(uniqueKey);
        p.setExceeded(exceeded);
        p.setTableRows(rows);
        p.setTotalBytes(2L * 1024 * 1024 * 1024);
        p.setSizeThresholdBytes(1024L * 1024 * 1024);
        p.setRowThreshold(10_000_000L);
        return p;
    }

    private static SqlRowEstimator.EstimationResult estimation(Long total, SqlRowEstimator.StmtEstimation... perStmt) {
        return new SqlRowEstimator.EstimationResult(total, true, List.of(perStmt));
    }

    private static DmlBatchShapeMO shape(boolean supported, String table, SplitQueryType type, boolean selfReferencing) {
        DmlBatchShapeMO mo = new DmlBatchShapeMO();
        mo.setSupported(supported);
        mo.setTableName(table);
        mo.setStatementType(type);
        mo.setHasWhere(true);
        mo.setSelfReferencingSet(selfReferencing);
        return mo;
    }

    private static SplitScript updateScript(long index, String sql) {
        return script(index, sql, SplitQueryType.UPDATE);
    }

    private static SplitScript deleteScript(long index, String sql) {
        return script(index, sql, SplitQueryType.DELETE);
    }

    private static SplitScript insertScript(long index, String sql) {
        return script(index, sql, SplitQueryType.INSERT);
    }

    private static SplitScript alterScript(long index, String sql) {
        return script(index, sql, SplitQueryType.ALTER_TABLE);
    }

    private static SplitScript script(long index, String sql, SplitQueryType type) {
        SplitScript script = new SplitScript();
        script.setIndex(index);
        script.setType(EnumSet.of(type));
        script.setScript(sql);
        return script;
    }
}
