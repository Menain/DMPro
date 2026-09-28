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
package com.clougence.clouddm.console.web.service.ghost;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.api.sidecar.session.execute.ResultList;
import com.clougence.clouddm.base.metadata.ds.DataSourceConfig;
import com.clougence.clouddm.base.metadata.ds.DataSourceType;
import com.clougence.clouddm.base.metadata.ds.SslMode;
import com.clougence.clouddm.console.web.component.config.RootUserConfig;
import com.clougence.clouddm.console.web.component.dsconfig.DmDsConfigService;
import com.clougence.clouddm.console.web.component.dsconfig.mode.DsLevels;
import com.clougence.clouddm.console.web.component.execute.QueryService;
import com.clougence.clouddm.console.web.component.execute.ToolsService;
import com.clougence.clouddm.console.web.component.execute.asyntask.AsyncTaskConfig;
import com.clougence.clouddm.console.web.component.opsdiag.OpsDiagnosticExecutor;
import com.clougence.clouddm.console.web.global.i18n.DmI18nUtils;
import com.clougence.clouddm.console.web.global.i18n.I18nDmMsgKeys;
import com.clougence.clouddm.console.web.model.fo.ghost.GhostExecuteFO;
import com.clougence.clouddm.console.web.model.fo.ghost.GhostPrecheckFO;
import com.clougence.clouddm.console.web.model.vo.ghost.GhostBlockVO;
import com.clougence.clouddm.console.web.model.vo.ghost.GhostExecuteVO;
import com.clougence.clouddm.console.web.model.vo.ghost.GhostOpAuditObj;
import com.clougence.clouddm.console.web.model.vo.ghost.GhostPrecheckVO;
import com.clougence.clouddm.console.web.model.vo.ghost.GhostSuggestionVO;
import com.clougence.clouddm.console.web.model.vo.ghost.GhostTaskVO;
import com.clougence.clouddm.console.web.service.asyntask.AsyncTaskServiceService;
import com.clougence.clouddm.console.web.service.ghost.asyntask.GhostAsyncTask;
import com.clougence.clouddm.console.web.service.ghost.asyntask.GhostAsyncTaskConfig;
import com.clougence.clouddm.platform.dal.access.AuthDal;
import com.clougence.clouddm.platform.dal.access.DataSourceDal;
import com.clougence.clouddm.platform.dal.access.ExecutionDal;
import com.clougence.clouddm.platform.dal.access.SystemDal;
import com.clougence.clouddm.platform.dal.mapper.auth.DmAuthUserMapper;
import com.clougence.clouddm.platform.dal.mapper.datasource.DmDsMapper;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecAsyncTaskMapper;
import com.clougence.clouddm.platform.dal.model.LifeCycleState;
import com.clougence.clouddm.platform.dal.model.auth.DmAuthUserDO;
import com.clougence.clouddm.platform.dal.model.datasource.DmDsDO;
import com.clougence.clouddm.platform.dal.model.execution.AsyncTaskProcessType;
import com.clougence.clouddm.platform.dal.model.execution.AsyncTaskStatus;
import com.clougence.clouddm.platform.dal.model.execution.DmExecAsyncTaskDO;
import com.clougence.clouddm.sdk.execute.resultset.echo.Result;
import com.clougence.clouddm.sdk.execute.resultset.echo.ResultMessage;
import com.clougence.clouddm.sdk.execute.resultset.echo.ResultSet;
import com.clougence.clouddm.sdk.execute.resultset.echo.ResultSetMeta;
import com.clougence.clouddm.sdk.execute.resultset.echo.ResultSetRow;
import com.clougence.clouddm.sdk.execute.resultset.echo.ResultSetValue;
import com.clougence.clouddm.sdk.execute.resultset.echo.ResultType;
import com.clougence.clouddm.sdk.execute.session.MessageLevel;
import com.clougence.clouddm.sdk.execute.session.QueryRequest;
import com.clougence.clouddm.sdk.execute.session.QueryResultConf;
import com.clougence.clouddm.sdk.execute.session.SessionContextDTO;
import com.clougence.clouddm.sdk.execute.session.SessionSpi;
import com.clougence.clouddm.sdk.execute.tools.ToolSessionContextDTO;
import com.clougence.clouddm.sdk.model.ghost.GhostLaunchDTO;
import com.clougence.clouddm.sdk.model.ghost.GhostOptionsDTO;
import com.clougence.clouddm.sdk.model.ghost.GhostStatusDTO;
import com.clougence.schema.umi.struts.UmiTypes;
import com.clougence.utils.JsonUtils;

/**
 * Unit tests for {@link GhostServiceImpl} (B6 S4): the precheck validation matrix (no-PK /
 * non-MySQL / SSH / SSL / multi-statement / threshold boundaries on both sides) with the
 * zero-remote-call rejections, the precheck SQL contract (FULL statement assertion including
 * the spec §4 escape fragments — backslash doubling first, B4 lesson), the launch assembly
 * (ArgumentCaptor on GhostLaunchDTO / AsyncTaskConfig), the terminal writeback + oldTableName
 * capture into configData, the drop guards (ownership / terminal state / injection / audit)
 * and the plugin error-code translation matrix (design §7).
 * <p>
 * Discipline (B2/B4 suites): JUnit4 + hand-written mocks + ReflectionTestUtils only; the
 * PluginManager statics are isolated behind the protected seams of {@link OpsDiagnosticExecutor}
 * (no mockStatic, no Spring context, no MockMvc). i18n expectations are computed from the live
 * bundle through DmI18nUtils (locale-robust); block assertions go through the stable code names.
 */
public class GhostServiceImplTest {

    private static final String   PUID       = "puid-tenant";
    private static final String   UID        = "uid-operator";
    private static final String   OWNER_UID  = "uid-launcher";
    private static final String   FOREIGN    = "uid-evil";
    private static final long     DS_ID      = 9L;
    private static final long     CLUSTER_ID = 7L;
    private static final String   DIAG_SESSION = "diag-session";
    private static final String   SESSION_ID = "ghost-session-1";
    private static final String   DB         = "db1";
    private static final String   TABLE      = "t_order";
    private static final String   TOOL       = "GHOST";

    /** information_schema.TABLES projection columns (B4 M2 caliber). */
    private static final String[] STAT_COLS  = { "table_name", "row_estimate", "data_bytes", "index_bytes" };
    private static final String[] UK_COLS    = { "index_name" };

    private GhostServiceImpl       service;
    private DataSourceDal          dsDal;
    private DmDsMapper             dsMapper;
    private DmDsConfigService      dmDsConfigService;
    private QueryService           queryService;
    private ToolsService           toolsService;
    private AsyncTaskServiceService asyncTaskServiceService;
    private ExecutionDal           executionDal;
    private DmExecAsyncTaskMapper  asyncTaskMapper;
    private SystemDal              systemDal;
    private AuthDal                authDal;
    private DmAuthUserMapper       userMapper;

    /** SQL bodies actually dispatched to the (mocked) worker, in order. */
    private final List<String>     executedSqls = Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong       queryIdSeq   = new AtomicLong();

    /** per-test configurable probe results */
    private ResultList             tableStatResult;
    private ResultList             uniqueKeyResult;
    private ResultList             dropResult;

    /** datasource row returned by the ownership query (swap for type/lifecycle variants) */
    private DmDsDO                 ownedDs;
    /** datasource config returned by fetchDsConfigFromExists (swap for ssh/ssl variants) */
    private DataSourceConfig       ownedDsConfig;

    private final SessionSpi       fakeSpi    = new SessionSpi() {

                                              @Override
                                              public SessionContextDTO createSessionContext(DataSourceConfig dsConfig,
                                                      Map<String, Object> params) {
                                                  SessionContextDTO ctx = new SessionContextDTO();
                                                  ctx.setSessionId("ctx-session");
                                                  return ctx;
                                              }

                                              @Override
                                              public QueryRequest createQueryRequest(DataSourceConfig dsConfig) {
                                                  QueryRequest request = new QueryRequest();
                                                  request.setResultConf(new QueryResultConf());
                                                  return request;
                                              }

                                              @Override
                                              public String newQueryId() {
                                                  return "q-" + queryIdSeq.incrementAndGet();
                                              }
                                          };

    @Before
    public void setUp() {
        service = new GhostServiceImpl();

        // the channel-C seams (PluginManager statics) live on the shared executor — B2/B4 wiring
        OpsDiagnosticExecutor diagnosticExecutor = new OpsDiagnosticExecutor() {

            @Override
            protected SessionSpi findSessionSpi(DataSourceType dsType) {
                return fakeSpi;
            }

            @Override
            protected DsLevels createLevels(DmDsDO dsDO, SessionContextDTO ctx) {
                return new DsLevels(String.valueOf(dsDO.getDsEnvId()), dsDO, //
                        List.of(String.valueOf(dsDO.getDsEnvId()), String.valueOf(dsDO.getId())), //
                        Collections.emptyList(), Collections.<UmiTypes> emptyList(), Collections.emptyMap());
            }
        };

        dsDal = mock(DataSourceDal.class);
        dsMapper = mock(DmDsMapper.class);
        dmDsConfigService = mock(DmDsConfigService.class);
        queryService = mock(QueryService.class);
        toolsService = mock(ToolsService.class);
        asyncTaskServiceService = mock(AsyncTaskServiceService.class);
        executionDal = mock(ExecutionDal.class);
        asyncTaskMapper = mock(DmExecAsyncTaskMapper.class);
        systemDal = mock(SystemDal.class);
        authDal = mock(AuthDal.class);
        userMapper = mock(DmAuthUserMapper.class);

        when(dsDal.dsMapper()).thenReturn(dsMapper);
        when(executionDal.asyncTaskMapper()).thenReturn(asyncTaskMapper);
        when(authDal.userMapper()).thenReturn(userMapper);

        ReflectionTestUtils.setField(diagnosticExecutor, "queryService", queryService);
        ReflectionTestUtils.setField(service, "dsDal", dsDal);
        ReflectionTestUtils.setField(service, "dmDsConfigService", dmDsConfigService);
        ReflectionTestUtils.setField(service, "diagnosticExecutor", diagnosticExecutor);
        ReflectionTestUtils.setField(service, "toolsService", toolsService);
        ReflectionTestUtils.setField(service, "asyncTaskServiceService", asyncTaskServiceService);
        ReflectionTestUtils.setField(service, "executionDal", executionDal);
        ReflectionTestUtils.setField(service, "systemDal", systemDal);
        ReflectionTestUtils.setField(service, "authDal", authDal);
        ReflectionTestUtils.setField(service, "queryTimeoutSeconds", 3L);
        ReflectionTestUtils.setField(service, "dropTimeoutSeconds", 3L);

        // default fixtures: owned plain-MySQL datasource, direct connection, healthy probes
        ownedDs = ds(DataSourceType.MySQL, LifeCycleState.CREATED);
        ownedDsConfig = dsConfig(DataSourceType.MySQL);
        when(dsMapper.listByOwnerAndIds(eq(PUID), any())).thenAnswer(inv -> //
                ownedDs == null ? Collections.emptyList() : List.of(ownedDs));
        when(dmDsConfigService.fetchDsConfigFromExists(DS_ID)).thenAnswer(inv -> ownedDsConfig);
        when(queryService.createSession(anyString(), any(), any())).thenReturn(DIAG_SESSION);

        // thresholds at their defaults (the RootUserConfig carrier)
        when(systemDal.fetchSystemConf(eq(RootUserConfig.Fields.ghostSizeThresholdMb), eq(Integer.class), any()))
                .thenReturn(GhostServiceImpl.DEFAULT_SIZE_THRESHOLD_MB);
        when(systemDal.fetchSystemConf(eq(RootUserConfig.Fields.ghostRowThreshold), eq(Long.class), any()))
                .thenReturn(GhostServiceImpl.DEFAULT_ROW_THRESHOLD);

        // tenant: primary account (id=1) + the operator and the launcher as its sub-accounts
        DmAuthUserDO parent = new DmAuthUserDO();
        parent.setId(1L);
        parent.setUid(PUID);
        when(userMapper.queryByUid(PUID)).thenReturn(parent);
        DmAuthUserDO sub = new DmAuthUserDO();
        sub.setUid(UID);
        sub.setUsername("operator");
        DmAuthUserDO launcher = new DmAuthUserDO();
        launcher.setUid(OWNER_UID);
        when(userMapper.listByParentId(1L)).thenReturn(List.of(sub, launcher));
        when(userMapper.queryByUid(UID)).thenReturn(sub);
        when(userMapper.queryByUid(OWNER_UID)).thenReturn(null);

        tableStatResult = queryResult(STAT_COLS, new String[][] { { TABLE, "1000", "1048576", "0" } });
        uniqueKeyResult = queryResult(UK_COLS, new String[][] { { "PRIMARY" } });
        dropResult = messageResult(MessageLevel.Info, "ok");

        when(queryService.syncExecuteQuery(anyString(), anyString(), any(QueryRequest.class))).thenAnswer(invocation -> {
            QueryRequest request = invocation.getArgument(2);
            String body = request.getQueryBody();
            executedSqls.add(body);
            if (body.startsWith("DROP TABLE")) {
                return dropResult;
            }
            if (body.contains("information_schema.statistics")) {
                return uniqueKeyResult;
            }
            if (body.contains("information_schema.tables")) {
                return tableStatResult;
            }
            throw new IllegalStateException("unexpected sql dispatched: " + body);
        });

        // tool session defaults: create echoes the ctx session id; status/tail respond alive
        when(toolsService.createSession(anyString(), eq(TOOL), any(ToolSessionContextDTO.class)))
                .thenAnswer(inv -> ((ToolSessionContextDTO) inv.getArgument(2)).getSessionId());
        when(toolsService.hasSession(anyString(), anyString())).thenReturn(true);
    }

    // ==================================================================================
    // group 1: precheck validation matrix (R3 hard blocks — zero remote calls on rejection)
    // ==================================================================================

    @Test
    public void precheck_healthy_mapsTableStatAndDiskHint_noBlocks() {
        GhostPrecheckVO vo = service.precheck(PUID, UID, precheckFO(DB, TABLE, null));

        assertTrue(vo.getHardBlocks().isEmpty());
        assertTrue(vo.getSuggestions().isEmpty());
        assertNotNull(vo.getTable());
        assertEquals(TABLE, vo.getTable().getTableName());
        assertEquals(Long.valueOf(1048576L), vo.getTable().getDataLength());
        assertEquals(Long.valueOf(0L), vo.getTable().getIndexLength());
        assertEquals(Long.valueOf(1000L), vo.getTable().getTableRows());
        assertEquals(Long.valueOf(1048576L), vo.getTable().getTotalBytes());
        // R3 disk hint: shadow table ≈ one extra copy → total × 2
        assertEquals(Long.valueOf(2097152L), vo.getTable().getEstimatedDiskBytes());
        assertEquals(2, executedSqls.size());
    }

    @Test
    public void precheck_nonMySqlDs_hardBlock_zeroSqlDispatched() {
        ownedDs = ds(DataSourceType.PostgreSQL, LifeCycleState.CREATED);
        ownedDsConfig = dsConfig(DataSourceType.PostgreSQL);

        GhostPrecheckVO vo = service.precheck(PUID, UID, precheckFO(DB, TABLE, null));

        assertSingleBlock(vo, I18nDmMsgKeys.GHOST_DS_TYPE_UNSUPPORTED.name());
        assertTrue(executedSqls.isEmpty());
    }

    @Test
    public void precheck_sshTunnelDs_hardBlock_zeroSqlDispatched() {
        ownedDsConfig = dsConfig(DataSourceType.MySQL);
        ownedDsConfig.setSshProxyEnabled(true);

        GhostPrecheckVO vo = service.precheck(PUID, UID, precheckFO(DB, TABLE, null));

        assertSingleBlock(vo, I18nDmMsgKeys.GHOST_TUNNEL_UNSUPPORTED.name());
        assertTrue(executedSqls.isEmpty());
    }

    @Test
    public void precheck_sslDs_hardBlock_zeroSqlDispatched() {
        ownedDsConfig = dsConfig(DataSourceType.MySQL);
        ownedDsConfig.setSslMode(SslMode.CA);

        GhostPrecheckVO vo = service.precheck(PUID, UID, precheckFO(DB, TABLE, null));

        assertSingleBlock(vo, I18nDmMsgKeys.GHOST_TUNNEL_UNSUPPORTED.name());
        assertTrue(executedSqls.isEmpty());
    }

    @Test
    public void precheck_sslDisabled_isNotATunnelBlock() {
        ownedDsConfig = dsConfig(DataSourceType.MySQL);
        ownedDsConfig.setSshProxyEnabled(false);
        ownedDsConfig.setSslMode(SslMode.DISABLED);

        GhostPrecheckVO vo = service.precheck(PUID, UID, precheckFO(DB, TABLE, null));

        assertTrue(vo.getHardBlocks().isEmpty());
    }

    @Test
    public void precheck_multiStatement_hardBlock_zeroSqlDispatched() {
        GhostPrecheckVO vo = service.precheck(PUID, UID,
                precheckFO(DB, TABLE, "ALTER TABLE t_order ADD COLUMN c INT; DROP TABLE t_other"));

        assertSingleBlock(vo, I18nDmMsgKeys.GHOST_STMT_UNSUPPORTED.name());
        assertTrue(executedSqls.isEmpty());
    }

    @Test
    public void precheck_schemaMismatch_hardBlockCarryingDetail() {
        GhostPrecheckVO vo = service.precheck(PUID, UID, precheckFO(DB, TABLE, "ALTER TABLE other_db.t1 ADD COLUMN c INT"));

        assertEquals(1, vo.getHardBlocks().size());
        GhostBlockVO block = vo.getHardBlocks().get(0);
        assertEquals(I18nDmMsgKeys.GHOST_SCHEMA_MISMATCH.name(), block.getCode());
        assertEquals(DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_SCHEMA_MISMATCH.name(),
                "statement database 'other_db' differs from the selected database 'db1'."), block.getReason());
        assertTrue(executedSqls.isEmpty());
    }

    @Test
    public void precheck_noPrimaryKeyOrUniqueKey_hardBlock() {
        uniqueKeyResult = queryResult(UK_COLS, new String[0][]);

        GhostPrecheckVO vo = service.precheck(PUID, UID, precheckFO(DB, TABLE, null));

        assertSingleBlock(vo, I18nDmMsgKeys.GHOST_NO_UNIQUE_KEY.name());
        // the size probe still ran (the table stats are useful context for the user)
        assertNotNull(vo.getTable());
        assertEquals(2, executedSqls.size());
    }

    @Test
    public void precheck_tableAbsent_hardBlock_andNoUkProbe() {
        tableStatResult = queryResult(STAT_COLS, new String[0][]);

        GhostPrecheckVO vo = service.precheck(PUID, UID, precheckFO(DB, TABLE, null));

        assertSingleBlock(vo, I18nDmMsgKeys.GHOST_TABLE_NOT_FOUND.name());
        assertNull(vo.getTable());
        // existence probe first — the UK probe is skipped once the table is known absent
        assertEquals(1, executedSqls.size());
        assertTrue(executedSqls.get(0).contains("information_schema.tables"));
    }

    @Test
    public void precheck_parsableDdl_overridesCascadeTableName() {
        service.precheck(PUID, UID, precheckFO(DB, "ignored_cascade_table", "ALTER TABLE t_real ADD COLUMN c INT"));

        assertEquals(2, executedSqls.size());
        for (String sql : executedSqls) {
            assertTrue("probe must target the parsed table: " + sql, sql.contains("table_name = 't_real'"));
        }
    }

    @Test
    public void precheck_noTableAndNoDdl_isBadArgWithZeroRemoteCalls() {
        try {
            service.precheck(PUID, UID, precheckFO(DB, null, "  "));
            fail("blank table + blank ddl must be rejected");
        } catch (ErrorMessageException e) {
            // COMM_BAD_ARG_ERROR — before any probe
        }
        assertTrue(executedSqls.isEmpty());
    }

    @Test
    public void precheck_foreignDs_notFoundSemantics_zeroRemoteCalls() {
        ownedDs = null; // ownership query comes back empty
        try {
            service.precheck(PUID, UID, precheckFO(DB, TABLE, null));
            fail("foreign datasource must be rejected");
        } catch (ErrorMessageException e) {
            assertEquals(DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_DS_NOT_FOUND.name()), e.getMessage());
        }
        assertTrue(executedSqls.isEmpty());
    }

    @Test
    public void precheck_overlongIdentifier_rejectedWithZeroRemoteCalls() {
        String overlong = "x".repeat(65);
        try {
            service.precheck(PUID, UID, precheckFO(DB, overlong, null));
            fail("overlong identifier must be rejected before any remote call");
        } catch (ErrorMessageException e) {
            // COMM_BAD_ARG_ERROR
        }
        assertTrue(executedSqls.isEmpty());
    }

    // ==================================================================================
    // group 2: precheck SQL contract (FULL statement assertion — B4 escape lesson)
    // ==================================================================================

    @Test
    public void precheck_sqlContract_fullStatementsWithEscapedLiterals() {
        // value positions are string literals: backslash doubling FIRST, then quote doubling
        GhostPrecheckVO vo = service.precheck(PUID, UID, precheckFO("d'b", "t\\y", null));
        assertTrue(vo.getHardBlocks().isEmpty());

        assertEquals(2, executedSqls.size());
        assertEquals("SELECT table_name, table_rows AS row_estimate, data_length AS data_bytes, "
                     + "index_length AS index_bytes FROM information_schema.tables "
                     + "WHERE table_type = 'BASE TABLE' AND table_schema = 'd''b' AND table_name = 't\\\\y'",
                executedSqls.get(0));
        assertEquals("SELECT index_name FROM information_schema.statistics "
                     + "WHERE table_schema = 'd''b' AND table_name = 't\\\\y' AND non_unique = 0 LIMIT 1",
                executedSqls.get(1));
    }

    @Test
    public void precheck_nulInIdentifier_rejectedWithZeroRemoteCalls() {
        try {
            service.precheck(PUID, UID, precheckFO(DB, "t\u0000x", null));
            fail("NUL in identifier must be rejected");
        } catch (ErrorMessageException e) {
            // COMM_BAD_ARG_ERROR
        }
        assertTrue(executedSqls.isEmpty());
    }

    // ==================================================================================
    // group 3: R4 threshold suggestion (boundaries on both sides, admin override)
    // ==================================================================================

    @Test
    public void precheck_sizeAtThreshold_suggestsLockfree() {
        // total bytes == 1024MB exactly → hit (>= semantics)
        tableStatResult = queryResult(STAT_COLS, new String[][] { { TABLE, "10", "1073741824", "0" } });

        GhostPrecheckVO vo = service.precheck(PUID, UID, precheckFO(DB, TABLE, null));

        assertSuggestion(vo, 1073741824L, 10L);
    }

    @Test
    public void precheck_sizeOneByteBelowThreshold_noSuggestion() {
        tableStatResult = queryResult(STAT_COLS, new String[][] { { TABLE, "10", "1073741823", "0" } });

        GhostPrecheckVO vo = service.precheck(PUID, UID, precheckFO(DB, TABLE, null));

        assertTrue(vo.getSuggestions().isEmpty());
    }

    @Test
    public void precheck_rowsAtThreshold_suggestsLockfree() {
        tableStatResult = queryResult(STAT_COLS, new String[][] { { TABLE, "10000000", "1024", "0" } });

        GhostPrecheckVO vo = service.precheck(PUID, UID, precheckFO(DB, TABLE, null));

        assertSuggestion(vo, 1024L, 10000000L);
    }

    @Test
    public void precheck_rowsOneBelowThreshold_noSuggestion() {
        tableStatResult = queryResult(STAT_COLS, new String[][] { { TABLE, "9999999", "1024", "0" } });

        GhostPrecheckVO vo = service.precheck(PUID, UID, precheckFO(DB, TABLE, null));

        assertTrue(vo.getSuggestions().isEmpty());
    }

    @Test
    public void precheck_adminThresholdOverride_takesEffect() {
        when(systemDal.fetchSystemConf(eq(RootUserConfig.Fields.ghostSizeThresholdMb), eq(Integer.class), any())).thenReturn(1);
        when(systemDal.fetchSystemConf(eq(RootUserConfig.Fields.ghostRowThreshold), eq(Long.class), any())).thenReturn(5L);
        tableStatResult = queryResult(STAT_COLS, new String[][] { { TABLE, "5", "1048576", "0" } });

        GhostPrecheckVO vo = service.precheck(PUID, UID, precheckFO(DB, TABLE, null));

        assertEquals(1, vo.getSuggestions().size());
        GhostSuggestionVO suggestion = vo.getSuggestions().get(0);
        assertEquals(Long.valueOf(1048576L), suggestion.getSizeThresholdBytes());
        assertEquals(Long.valueOf(5L), suggestion.getRowThreshold());
    }

    @Test
    public void precheck_hardBlock_suppressesSuggestion() {
        uniqueKeyResult = queryResult(UK_COLS, new String[0][]);
        tableStatResult = queryResult(STAT_COLS, new String[][] { { TABLE, "99999999", "99999999999", "0" } });

        GhostPrecheckVO vo = service.precheck(PUID, UID, precheckFO(DB, TABLE, null));

        assertSingleBlock(vo, I18nDmMsgKeys.GHOST_NO_UNIQUE_KEY.name());
        assertTrue(vo.getSuggestions().isEmpty());
    }

    // ==================================================================================
    // group 4: execute — launch assembly (ArgumentCaptor on the wire payloads)
    // ==================================================================================

    @Test
    public void execute_assemblesLaunchDto_toolCtx_andAsyncTask() {
        GhostExecuteFO fo = executeFO("ALTER TABLE db1.t_order ADD COLUMN extra VARCHAR(64)");
        GhostOptionsDTO options = new GhostOptionsDTO();
        options.setChunkSize(777);
        fo.setOptions(options);
        DmExecAsyncTaskDO stored = task(AsyncTaskStatus.INIT, taskConfig(OWNER_UID));
        when(asyncTaskServiceService.queryAsyncTaskByBizId(anyString(), eq(TOOL))).thenReturn(stored);

        GhostExecuteVO vo = service.execute(PUID, UID, fo);

        // --- tool session context: configuration = GhostLaunchDTO JSON (Faker contract) ---
        ArgumentCaptor<ToolSessionContextDTO> ctxCaptor = ArgumentCaptor.forClass(ToolSessionContextDTO.class);
        verify(toolsService).createSession(eq(UID), eq(TOOL), ctxCaptor.capture());
        ToolSessionContextDTO ctx = ctxCaptor.getValue();
        assertNotNull(ctx.getSessionId());
        assertEquals(32, ctx.getSessionId().length());
        assertEquals(CLUSTER_ID, ctx.getBindClusterId());
        GhostLaunchDTO launch = JsonUtils.toObj(ctx.getConfiguration(), GhostLaunchDTO.class);
        assertEquals(DS_ID, launch.getDsId());
        assertEquals(DB, launch.getSchema());
        assertEquals(fo.getDdlSql(), launch.getDdlSql());
        assertNotNull(launch.getOptions());
        assertEquals(Integer.valueOf(777), launch.getOptions().getChunkSize());
        assertNull(launch.getTicketId());
        assertNull(launch.getStmtSeq());

        // --- START right after creation ---
        verify(toolsService).invoke(eq(UID), eq(vo.getSessionId()), eq("START"), eq(null));

        // --- AsyncTask: bizType=GHOST, bizId=sessionId, handler + configData ---
        ArgumentCaptor<AsyncTaskConfig> taskCaptor = ArgumentCaptor.forClass(AsyncTaskConfig.class);
        verify(asyncTaskServiceService).submitTask(eq(UID), taskCaptor.capture());
        AsyncTaskConfig submitted = taskCaptor.getValue();
        assertEquals(TOOL, submitted.getBizType());
        assertEquals(vo.getSessionId(), submitted.getBizId());
        assertEquals(GhostAsyncTask.class, submitted.getHandlerType());
        assertEquals(AsyncTaskProcessType.PROGRESS, submitted.getProcessType());
        assertTrue(submitted.isShowInDock());
        GhostAsyncTaskConfig cfg = JsonUtils.toObj(submitted.getConfigData(), GhostAsyncTaskConfig.class);
        assertEquals(UID, cfg.getUserId());
        assertEquals(vo.getSessionId(), cfg.getSessionId());
        assertEquals(Long.valueOf(DS_ID), cfg.getDsId());
        assertEquals(DB, cfg.getDbName());
        assertEquals(TABLE, cfg.getTableName());
        assertEquals(fo.getDdlSql(), cfg.getDdlSql());

        // --- response + audit payload ---
        assertEquals(vo.getSessionId(), ctx.getSessionId());
        assertEquals(Long.valueOf(stored.getId()), vo.getTaskId());
        assertNotNull(vo.getAudit());
        assertEquals(Long.valueOf(DS_ID), vo.getAudit().getDsId());
        assertEquals(TABLE, vo.getAudit().getTableName());
        assertEquals("SUCCESS", vo.getAudit().getResult());
    }

    @Test
    public void execute_nonMySqlDs_rejectedBeforeSessionCreation() {
        ownedDs = ds(DataSourceType.MariaDB, LifeCycleState.CREATED);
        try {
            service.execute(PUID, UID, executeFO("ALTER TABLE t_order ADD COLUMN c INT"));
            fail("non-MySQL datasource must be rejected");
        } catch (ErrorMessageException e) {
            assertEquals(DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_DS_TYPE_UNSUPPORTED.name()), e.getMessage());
        }
        verify(toolsService, never()).createSession(anyString(), anyString(), any());
        verify(asyncTaskServiceService, never()).submitTask(anyString(), any());
    }

    @Test
    public void execute_sshDs_rejectedBeforeSessionCreation() {
        ownedDsConfig.setSshProxyEnabled(true);
        try {
            service.execute(PUID, UID, executeFO("ALTER TABLE t_order ADD COLUMN c INT"));
            fail("SSH datasource must be rejected");
        } catch (ErrorMessageException e) {
            assertEquals(DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_TUNNEL_UNSUPPORTED.name()), e.getMessage());
        }
        verify(toolsService, never()).createSession(anyString(), anyString(), any());
    }

    @Test
    public void execute_multiStatement_rejectedBeforeSessionCreation() {
        try {
            service.execute(PUID, UID, executeFO("ALTER TABLE t_order ADD COLUMN c INT; DELETE FROM t_order"));
            fail("multi-statement must be rejected");
        } catch (ErrorMessageException e) {
            assertEquals(DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_STMT_UNSUPPORTED.name()), e.getMessage());
        }
        verify(toolsService, never()).createSession(anyString(), anyString(), any());
    }

    @Test
    public void execute_startFailure_closesSessionAndTranslatesPluginCode() {
        when(toolsService.invoke(anyString(), anyString(), eq("START"), eq(null)))
                .thenThrow(new RuntimeException("GHOST_BINARY_MISSING: gh-ost binary not found at /opt/tools/gh-ost/gh-ost"));
        try {
            service.execute(PUID, UID, executeFO("ALTER TABLE t_order ADD COLUMN c INT"));
            fail("START failure must surface translated");
        } catch (ErrorMessageException e) {
            assertEquals(DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_BINARY_MISSING.name(),
                    "gh-ost binary not found at /opt/tools/gh-ost/gh-ost"), e.getMessage());
        }
        // the half-open session is cleaned up, no task is submitted
        verify(toolsService).closeSession(eq(UID), anyString());
        verify(asyncTaskServiceService, never()).submitTask(anyString(), any());
    }

    @Test
    public void execute_createSessionPluginRejection_translatesWithoutTaskSubmission() {
        when(toolsService.createSession(anyString(), eq(TOOL), any(ToolSessionContextDTO.class)))
                .thenThrow(new RuntimeException("GHOST_TUNNEL_UNSUPPORTED: datasource uses an SSH tunnel"));
        try {
            service.execute(PUID, UID, executeFO("ALTER TABLE t_order ADD COLUMN c INT"));
            fail("init rejection must surface translated");
        } catch (ErrorMessageException e) {
            assertEquals(DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_TUNNEL_UNSUPPORTED.name()), e.getMessage());
        }
        verify(asyncTaskServiceService, never()).submitTask(anyString(), any());
    }

    @Test
    public void execute_platformErrorMessage_passesThroughUntranslated() {
        ErrorMessageException platform = new ErrorMessageException("cluster has no workers");
        when(toolsService.createSession(anyString(), eq(TOOL), any(ToolSessionContextDTO.class))).thenThrow(platform);
        try {
            service.execute(PUID, UID, executeFO("ALTER TABLE t_order ADD COLUMN c INT"));
            fail("platform error must pass through");
        } catch (ErrorMessageException e) {
            assertSame(platform, e);
        }
    }

    // ==================================================================================
    // group 5: tailStatus pass-through — terminal writeback + oldTableName into configData
    // ==================================================================================

    @Test
    public void tailStatus_capturesOldTableNameAndLastStatus_intoConfigData() {
        GhostAsyncTaskConfig cfg = taskConfig(OWNER_UID);
        DmExecAsyncTaskDO stored = task(AsyncTaskStatus.RUNNING, cfg);
        stored.setFastFail(true); // non-default, so the partial-update copy below is observable
        when(asyncTaskServiceService.queryAsyncTaskByBizId(SESSION_ID, TOOL)).thenReturn(stored);

        GhostStatusDTO wire = new GhostStatusDTO();
        wire.setStatus("COMPLETED");
        wire.setPhase("DONE");
        wire.setOldTableName("t_order_20260924171321_del");
        wire.setCurValue(39);
        wire.setMaxValue(39);
        wire.setUseProgress(true);
        when(toolsService.tailStatus(OWNER_UID, SESSION_ID, null)).thenReturn(JsonUtils.toJson(wire));

        GhostStatusDTO dto = service.tailStatus(PUID, UID, SESSION_ID);

        assertEquals("COMPLETED", dto.getStatus());
        assertEquals("t_order_20260924171321_del", dto.getOldTableName());
        // the R7 carrier write: configData gains oldTableName + lastStatus, dock flags preserved
        ArgumentCaptor<DmExecAsyncTaskDO> captor = ArgumentCaptor.forClass(DmExecAsyncTaskDO.class);
        verify(asyncTaskMapper).updateById(captor.capture());
        DmExecAsyncTaskDO partial = captor.getValue();
        assertEquals(Long.valueOf(stored.getId()), partial.getId());
        // primitive clobber guard: the partial update must CARRY the dock flags, not reset them
        assertTrue(partial.isShowInDock());
        assertTrue(partial.isFastFail());
        GhostAsyncTaskConfig saved = JsonUtils.toObj(partial.getConfigData(), GhostAsyncTaskConfig.class);
        assertEquals("t_order_20260924171321_del", saved.getOldTableName());
        assertEquals("COMPLETED", saved.getLastStatus());
        assertEquals(OWNER_UID, saved.getUserId());
        // the tool call runs under the LAUNCHING uid, not the caller (tenant admin semantics)
        verify(toolsService).tailStatus(OWNER_UID, SESSION_ID, null);
    }

    @Test
    public void tailStatus_unchangedState_doesNotRewriteConfigData() {
        GhostAsyncTaskConfig cfg = taskConfig(OWNER_UID);
        cfg.setLastStatus("RUNNING");
        when(asyncTaskServiceService.queryAsyncTaskByBizId(SESSION_ID, TOOL)).thenReturn(task(AsyncTaskStatus.RUNNING, cfg));

        GhostStatusDTO wire = new GhostStatusDTO();
        wire.setStatus("RUNNING");
        when(toolsService.tailStatus(OWNER_UID, SESSION_ID, null)).thenReturn(JsonUtils.toJson(wire));

        service.tailStatus(PUID, UID, SESSION_ID);

        verify(asyncTaskMapper, never()).updateById(any(DmExecAsyncTaskDO.class));
    }

    @Test
    public void tailStatus_sessionGone_fallsBackToTaskRecord() {
        GhostAsyncTaskConfig cfg = taskConfig(OWNER_UID);
        cfg.setOldTableName("t_order_20260924171321_del");
        DmExecAsyncTaskDO stored = task(AsyncTaskStatus.FAILURE, cfg);
        stored.setProcessType(AsyncTaskProcessType.PROGRESS);
        stored.setProcessValue("80");
        stored.setStatusMsg("Error: ghost migration failed: FATAL boom");
        when(asyncTaskServiceService.queryAsyncTaskByBizId(SESSION_ID, TOOL)).thenReturn(stored);
        when(toolsService.tailStatus(anyString(), anyString(), any())).thenReturn(null);

        GhostStatusDTO dto = service.tailStatus(PUID, UID, SESSION_ID);

        // no captured tool status → the task status maps into the tool domain
        assertEquals(GhostService.STATUS_FAILED, dto.getStatus());
        assertEquals("t_order_20260924171321_del", dto.getOldTableName());
        assertEquals("Error: ghost migration failed: FATAL boom", dto.getFailReason());
        assertTrue(dto.isUseProgress());
        assertEquals(80L, dto.getCurValue());
        assertEquals(100L, dto.getMaxValue());
    }

    @Test
    public void tailStatus_capturedToolStatusWinsOverTaskMapping() {
        GhostAsyncTaskConfig cfg = taskConfig(OWNER_UID);
        cfg.setLastStatus(GhostService.STATUS_ABORTED);
        DmExecAsyncTaskDO stored = task(AsyncTaskStatus.CANCEL, cfg);
        when(asyncTaskServiceService.queryAsyncTaskByBizId(SESSION_ID, TOOL)).thenReturn(stored);
        when(toolsService.tailStatus(anyString(), anyString(), any())).thenReturn(null);

        GhostStatusDTO dto = service.tailStatus(PUID, UID, SESSION_ID);

        assertEquals(GhostService.STATUS_ABORTED, dto.getStatus());
    }

    @Test
    public void tailStatus_foreignTenantTask_rejectedWithNotFoundSemantics() {
        DmExecAsyncTaskDO foreign = task(AsyncTaskStatus.RUNNING, taskConfig(FOREIGN));
        foreign.setUid(FOREIGN);
        when(asyncTaskServiceService.queryAsyncTaskByBizId(SESSION_ID, TOOL)).thenReturn(foreign);

        try {
            service.tailStatus(PUID, UID, SESSION_ID);
            fail("foreign-tenant task must be rejected");
        } catch (ErrorMessageException e) {
            assertEquals(DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_TASK_NOT_FOUND.name()), e.getMessage());
        }
        verify(toolsService, never()).tailStatus(anyString(), anyString(), any());
    }

    @Test
    public void tailStatus_missingTask_rejected() {
        when(asyncTaskServiceService.queryAsyncTaskByBizId(SESSION_ID, TOOL)).thenReturn(null);
        try {
            service.tailStatus(PUID, UID, SESSION_ID);
            fail("missing task must be rejected");
        } catch (ErrorMessageException e) {
            assertEquals(DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_TASK_NOT_FOUND.name()), e.getMessage());
        }
    }

    // ==================================================================================
    // group 6: control operations (pause/resume/stop) — owner uid + dock sync + guards
    // ==================================================================================

    @Test
    public void pause_invokesUnderOwnerUid_andSyncsDockTask_andReturnsAuditPayload() {
        when(asyncTaskServiceService.queryAsyncTaskByBizId(SESSION_ID, TOOL))
                .thenReturn(task(AsyncTaskStatus.RUNNING, taskConfig(OWNER_UID)));

        GhostOpAuditObj auditObj = service.pause(PUID, UID, SESSION_ID);

        verify(toolsService).invoke(OWNER_UID, SESSION_ID, "PAUSE", null);
        verify(asyncTaskServiceService).pauseTask(eq(SESSION_ID), eq(TOOL), anyString());
        assertEquals(Long.valueOf(DS_ID), auditObj.getDsId());
        assertEquals(DB, auditObj.getDbName());
        assertEquals(TABLE, auditObj.getTableName());
        assertEquals(SESSION_ID, auditObj.getSessionId());
        assertEquals("SUCCESS", auditObj.getResult());
    }

    @Test
    public void resume_invokesResume_andSyncsDockTask() {
        when(asyncTaskServiceService.queryAsyncTaskByBizId(SESSION_ID, TOOL))
                .thenReturn(task(AsyncTaskStatus.PAUSE, taskConfig(OWNER_UID)));

        service.resume(PUID, UID, SESSION_ID);

        verify(toolsService).invoke(OWNER_UID, SESSION_ID, "RESUME", null);
        verify(asyncTaskServiceService).resumeTask(eq(SESSION_ID), eq(TOOL), anyString());
    }

    @Test
    public void stop_invokesPanic_andCancelsDockTask() {
        when(asyncTaskServiceService.queryAsyncTaskByBizId(SESSION_ID, TOOL))
                .thenReturn(task(AsyncTaskStatus.RUNNING, taskConfig(OWNER_UID)));

        service.stop(PUID, UID, SESSION_ID);

        verify(toolsService).invoke(OWNER_UID, SESSION_ID, "STOP", null);
        verify(asyncTaskServiceService).cancelTask(eq(SESSION_ID), eq(TOOL), anyString());
    }

    @Test
    public void control_sessionEnded_surfacesBusinessHint_notSilentNoop() {
        when(asyncTaskServiceService.queryAsyncTaskByBizId(SESSION_ID, TOOL))
                .thenReturn(task(AsyncTaskStatus.RUNNING, taskConfig(OWNER_UID)));
        when(toolsService.hasSession(OWNER_UID, SESSION_ID)).thenReturn(false);

        try {
            service.pause(PUID, UID, SESSION_ID);
            fail("ended session must surface");
        } catch (ErrorMessageException e) {
            assertEquals(DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_SESSION_ENDED.name()), e.getMessage());
        }
        verify(toolsService, never()).invoke(anyString(), anyString(), anyString(), any());
    }

    @Test
    public void control_foreignTenant_neverTouchesTheSession() {
        DmExecAsyncTaskDO foreign = task(AsyncTaskStatus.RUNNING, taskConfig(FOREIGN));
        foreign.setUid(FOREIGN);
        when(asyncTaskServiceService.queryAsyncTaskByBizId(SESSION_ID, TOOL)).thenReturn(foreign);

        try {
            service.stop(PUID, UID, SESSION_ID);
            fail("foreign-tenant session must be rejected");
        } catch (ErrorMessageException e) {
            assertEquals(DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_TASK_NOT_FOUND.name()), e.getMessage());
        }
        verify(toolsService, never()).hasSession(anyString(), anyString());
        verify(toolsService, never()).invoke(anyString(), anyString(), anyString(), any());
    }

    @Test
    public void close_releasesSessionAndCancelsDockTask() {
        when(asyncTaskServiceService.queryAsyncTaskByBizId(SESSION_ID, TOOL))
                .thenReturn(task(AsyncTaskStatus.COMPLETE, taskConfig(OWNER_UID)));

        service.close(PUID, UID, SESSION_ID);

        verify(toolsService).closeSession(OWNER_UID, SESSION_ID);
        verify(asyncTaskServiceService).cancelTask(eq(SESSION_ID), eq(TOOL), anyString());
    }

    // ==================================================================================
    // group 7: old-table drop (R7) — guards, SQL contract, audit payload, state marking
    // ==================================================================================

    @Test
    public void drop_success_executesQuotedDrop_marksConfigData_returnsAuditPayload() {
        GhostAsyncTaskConfig cfg = taskConfig(OWNER_UID);
        cfg.setOldTableName("t_order_20260924171321_del");
        DmExecAsyncTaskDO stored = task(AsyncTaskStatus.COMPLETE, cfg);
        when(asyncTaskServiceService.queryAsyncTaskByBizId(SESSION_ID, TOOL)).thenReturn(stored);

        GhostOpAuditObj auditObj = service.dropOldTable(PUID, UID, SESSION_ID);

        assertEquals(1, executedSqls.size());
        assertEquals("DROP TABLE `db1`.`t_order_20260924171321_del`", executedSqls.get(0));
        // the drop runs under the operator's own uid (channel C session ownership)
        verify(queryService).createSession(eq(UID), any(), any());

        ArgumentCaptor<DmExecAsyncTaskDO> captor = ArgumentCaptor.forClass(DmExecAsyncTaskDO.class);
        verify(asyncTaskMapper).updateById(captor.capture());
        GhostAsyncTaskConfig saved = JsonUtils.toObj(captor.getValue().getConfigData(), GhostAsyncTaskConfig.class);
        assertEquals(Boolean.TRUE, saved.getOldTableDropped());
        assertEquals(UID, saved.getOldTableDropUid());
        assertNotNull(saved.getOldTableDropTime());

        assertEquals("t_order_20260924171321_del", auditObj.getOldTableName());
        assertEquals(Long.valueOf(DS_ID), auditObj.getDsId());
        assertEquals(DB, auditObj.getDbName());
        assertEquals("SUCCESS", auditObj.getResult());
    }

    @Test
    public void drop_taskNotTerminal_rejectedWithZeroRemoteCalls() {
        GhostAsyncTaskConfig cfg = taskConfig(OWNER_UID);
        cfg.setOldTableName("t_order_20260924171321_del");
        when(asyncTaskServiceService.queryAsyncTaskByBizId(SESSION_ID, TOOL)).thenReturn(task(AsyncTaskStatus.RUNNING, cfg));

        try {
            service.dropOldTable(PUID, UID, SESSION_ID);
            fail("non-terminal task must not allow the drop");
        } catch (ErrorMessageException e) {
            assertEquals(DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_TASK_NOT_TERMINAL.name()), e.getMessage());
        }
        assertTrue(executedSqls.isEmpty());
        verify(queryService, never()).createSession(anyString(), any(), any());
    }

    @Test
    public void drop_noOldTableName_rejected() {
        when(asyncTaskServiceService.queryAsyncTaskByBizId(SESSION_ID, TOOL))
                .thenReturn(task(AsyncTaskStatus.COMPLETE, taskConfig(OWNER_UID)));

        try {
            service.dropOldTable(PUID, UID, SESSION_ID);
            fail("task without a captured old table must be rejected");
        } catch (ErrorMessageException e) {
            assertEquals(DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_OLD_TABLE_ABSENT.name()), e.getMessage());
        }
        assertTrue(executedSqls.isEmpty());
    }

    @Test
    public void drop_alreadyDropped_rejectedIdempotently() {
        GhostAsyncTaskConfig cfg = taskConfig(OWNER_UID);
        cfg.setOldTableName("t_order_20260924171321_del");
        cfg.setOldTableDropped(true);
        when(asyncTaskServiceService.queryAsyncTaskByBizId(SESSION_ID, TOOL)).thenReturn(task(AsyncTaskStatus.COMPLETE, cfg));

        try {
            service.dropOldTable(PUID, UID, SESSION_ID);
            fail("a second drop must be rejected");
        } catch (ErrorMessageException e) {
            assertEquals(DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_OLD_TABLE_ABSENT.name()), e.getMessage());
        }
        assertTrue(executedSqls.isEmpty());
    }

    @Test
    public void drop_injectionShapedOldTableName_rejectedWithZeroRemoteCalls() {
        GhostAsyncTaskConfig cfg = taskConfig(OWNER_UID);
        cfg.setOldTableName("t1`; DROP TABLE accounts; --");
        when(asyncTaskServiceService.queryAsyncTaskByBizId(SESSION_ID, TOOL)).thenReturn(task(AsyncTaskStatus.COMPLETE, cfg));

        try {
            service.dropOldTable(PUID, UID, SESSION_ID);
            fail("injection-shaped old table name must be rejected before any remote call");
        } catch (ErrorMessageException e) {
            // COMM_BAD_ARG_ERROR — the [A-Za-z0-9_$]{1,64} guard fires first
        }
        assertTrue(executedSqls.isEmpty());
        verify(queryService, never()).createSession(anyString(), any(), any());
        verify(asyncTaskMapper, never()).updateById(any(DmExecAsyncTaskDO.class));
    }

    @Test
    public void drop_tableAlreadyGone_businessHintAndNoStateMarking() {
        GhostAsyncTaskConfig cfg = taskConfig(OWNER_UID);
        cfg.setOldTableName("t_order_20260924171321_del");
        when(asyncTaskServiceService.queryAsyncTaskByBizId(SESSION_ID, TOOL)).thenReturn(task(AsyncTaskStatus.COMPLETE, cfg));
        dropResult = messageResult(MessageLevel.Error, "Unknown table 'db1.t_order_20260924171321_del'");

        try {
            service.dropOldTable(PUID, UID, SESSION_ID);
            fail("vanished table must surface as a business hint");
        } catch (ErrorMessageException e) {
            assertEquals(DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_OLD_TABLE_NOT_FOUND.name(),
                    "db1.t_order_20260924171321_del"), e.getMessage());
        }
        // no dropped mark (the failure precedes the state write), no audit payload returned
        verify(asyncTaskMapper, never()).updateById(any(DmExecAsyncTaskDO.class));
    }

    @Test
    public void drop_dbFailure_surfacesReason() {
        GhostAsyncTaskConfig cfg = taskConfig(OWNER_UID);
        cfg.setOldTableName("t_order_20260924171321_del");
        when(asyncTaskServiceService.queryAsyncTaskByBizId(SESSION_ID, TOOL)).thenReturn(task(AsyncTaskStatus.COMPLETE, cfg));
        dropResult = messageResult(MessageLevel.Error, "DROP command denied to user 'agent'@'%'");

        try {
            service.dropOldTable(PUID, UID, SESSION_ID);
            fail("db failure must surface");
        } catch (ErrorMessageException e) {
            assertEquals(DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_DROP_FAILED.name(),
                    "DROP command denied to user 'agent'@'%'"), e.getMessage());
        }
        verify(asyncTaskMapper, never()).updateById(any(DmExecAsyncTaskDO.class));
    }

    @Test
    public void drop_foreignDsOwnership_rejectedWithZeroRemoteCalls() {
        GhostAsyncTaskConfig cfg = taskConfig(OWNER_UID);
        cfg.setOldTableName("t_order_20260924171321_del");
        cfg.setDsId(12345L); // a datasource outside the tenant
        when(asyncTaskServiceService.queryAsyncTaskByBizId(SESSION_ID, TOOL)).thenReturn(task(AsyncTaskStatus.COMPLETE, cfg));
        // the ownership query for the foreign id comes back empty (the generic stub keeps DS_ID owned)
        when(dsMapper.listByOwnerAndIds(eq(PUID), eq(List.of(12345L)))).thenReturn(Collections.emptyList());

        try {
            service.dropOldTable(PUID, UID, SESSION_ID);
            fail("foreign datasource must be rejected");
        } catch (ErrorMessageException e) {
            assertEquals(DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_DS_NOT_FOUND.name()), e.getMessage());
        }
        assertTrue(executedSqls.isEmpty());
    }

    // ==================================================================================
    // group 8: task list (R8)
    // ==================================================================================

    @Test
    public void tasks_listsTenantRows_newestFirst_withResolvedNames() {
        GhostAsyncTaskConfig cfgA = taskConfig(OWNER_UID);
        cfgA.setLastStatus(GhostService.STATUS_COMPLETED);
        cfgA.setOldTableName("t_order_20260924171321_del");
        cfgA.setOldTableDropped(true);
        DmExecAsyncTaskDO rowA = task(AsyncTaskStatus.COMPLETE, cfgA);
        rowA.setId(2L);
        DmExecAsyncTaskDO rowB = task(AsyncTaskStatus.RUNNING, taskConfig(UID));
        rowB.setId(1L);
        rowB.setUid(UID);
        when(asyncTaskMapper.selectList(any())).thenReturn(List.of(rowA, rowB));
        when(dsMapper.listByOwnerAndIds(eq(PUID), any())).thenReturn(List.of(ds(DataSourceType.MySQL, LifeCycleState.CREATED)));

        List<GhostTaskVO> vos = service.tasks(PUID, UID);

        assertEquals(2, vos.size());
        GhostTaskVO voA = vos.get(0);
        assertEquals(SESSION_ID, voA.getSessionId());
        assertEquals("COMPLETE", voA.getStatus());
        assertEquals(GhostService.STATUS_COMPLETED, voA.getToolStatus());
        assertEquals("t_order_20260924171321_del", voA.getOldTableName());
        assertTrue(voA.isOldTableDropped());
        assertEquals(Integer.valueOf(42), voA.getProgress());
        assertEquals("prod-mysql", voA.getDsName());
        assertEquals(OWNER_UID, voA.getInitiatorUid());
        GhostTaskVO voB = vos.get(1);
        assertEquals("operator", voB.getInitiatorName());
        assertFalse(voB.isOldTableDropped());
    }

    @Test
    public void tasks_queryIsTenantScopedToGhostBizType() {
        // lambda column rendering needs the MP TableInfo cache — normally built at mapper
        // registration, seeded by hand in a bare unit test (no Spring context discipline)
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                DmExecAsyncTaskDO.class);
        when(asyncTaskMapper.selectList(any())).thenReturn(Collections.emptyList());

        assertTrue(service.tasks(PUID, UID).isEmpty());

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper> captor = //
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(asyncTaskMapper).selectList(captor.capture());
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DmExecAsyncTaskDO> wrapper = //
                (com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DmExecAsyncTaskDO>) captor.getValue();
        // MP 3.5.16 materializes the param map lazily while the SQL segment renders — render first
        String sqlSegment = wrapper.getSqlSegment();
        // the tenant predicate + bizType filter are structural, never optional
        assertTrue(sqlSegment.contains("biz_type"));
        assertTrue(sqlSegment.contains("owner_uid IN"));
        assertTrue(sqlSegment.contains("LIMIT"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(TOOL));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(PUID));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(UID));
        assertFalse(wrapper.getParamNameValuePairs().containsValue(FOREIGN));
    }

    // ==================================================================================
    // group 9: plugin error translation (design §7 matrix)
    // ==================================================================================

    @Test
    public void translate_plainCodeKeys_mapToOneToOne() {
        assertTranslation("GHOST_DS_TYPE_UNSUPPORTED: gh-ost only support MySQL DDL. current type: PostgreSQL",
                DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_DS_TYPE_UNSUPPORTED.name()));
        assertTranslation("GHOST_TUNNEL_UNSUPPORTED: datasource uses an SSH tunnel",
                DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_TUNNEL_UNSUPPORTED.name()));
        assertTranslation("GHOST_STMT_UNSUPPORTED: only a single ALTER TABLE statement is allowed",
                DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_STMT_UNSUPPORTED.name()));
        assertTranslation("GHOST_ALREADY_STARTED: the migration was already started",
                DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_ALREADY_STARTED.name()));
        assertTranslation("GHOST_NOT_INITIALIZED: ghost session is not initialized.",
                DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_NOT_INITIALIZED.name()));
    }

    @Test
    public void translate_detailCarryingCodes_keepTheDetail() {
        assertTranslation("GHOST_BAD_LAUNCH_CONFIG: ddlSql is required.",
                DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_BAD_LAUNCH_CONFIG.name(), "ddlSql is required."));
        assertTranslation("GHOST_SCHEMA_MISMATCH: statement database 'a' differs from the session schema 'b'.",
                DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_SCHEMA_MISMATCH.name(),
                        "statement database 'a' differs from the session schema 'b'."));
        assertTranslation("GHOST_BINARY_MISSING: deploy the linux binary to <appHome>/tools/gh-ost/gh-ost",
                DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_BINARY_MISSING.name(),
                        "deploy the linux binary to <appHome>/tools/gh-ost/gh-ost"));
        assertTranslation("GHOST_ABORTED: stopped by user",
                DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_ABORTED.name(), "stopped by user"));
    }

    @Test
    public void translate_execFailed_classifiesByDetailContent() {
        // spike-evidenced FATAL shapes (research/spike-result.md §2)
        assertTranslation("GHOST_EXEC_FAILED: FATAL Error 1045: Access denied for user ''@'10.0.0.5'",
                DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_PRIVILEGE_MISSING.name(),
                        "FATAL Error 1045: Access denied for user ''@'10.0.0.5'"));
        assertTranslation("GHOST_EXEC_FAILED: FATAL Unexpected database port reported: 20336",
                DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_TOPOLOGY_UNSUPPORTED.name(),
                        "FATAL Unexpected database port reported: 20336"));
        assertTranslation("GHOST_EXEC_FAILED: FATAL Current binlog_format is STATEMENT",
                DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_BINLOG_INVALID.name(),
                        "FATAL Current binlog_format is STATEMENT"));
        assertTranslation("GHOST_EXEC_FAILED: FATAL critical load met on master",
                DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_RESOURCE.name(),
                        "FATAL critical load met on master"));
        assertTranslation("GHOST_EXEC_FAILED: FATAL no space left on device",
                DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_RESOURCE.name(),
                        "FATAL no space left on device"));
        assertTranslation("GHOST_EXEC_FAILED: FATAL heartbeat not seen for 30s",
                DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_TIMEOUT.name(),
                        "FATAL heartbeat not seen for 30s"));
        assertTranslation("GHOST_EXEC_FAILED: FATAL something entirely new",
                DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_EXEC_FAILED.name(),
                        "FATAL something entirely new"));
    }

    @Test
    public void translate_wrappedAndUnknownShapes_fallBackToGenericKey() {
        // a transport wrapper in front of the code prefix still finds the code
        assertTranslation("RSocket invocation error: GHOST_STMT_UNSUPPORTED: not a plain ALTER TABLE",
                DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_STMT_UNSUPPORTED.name()));
        // unknown GHOST_* code → generic key with the full raw message as {0}
        assertTranslation("GHOST_SOMETHING_NEW: x",
                DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_TOOL_FAILED.name(), "GHOST_SOMETHING_NEW: x"));
        assertTranslation("boom",
                DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_TOOL_FAILED.name(), "boom"));
        assertTranslation(null,
                DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_TOOL_FAILED.name(), ""));
    }

    @Test
    public void taskStatusMapping_coversTheWholeDomain() {
        assertEquals(GhostService.STATUS_COMPLETED, GhostServiceImpl.mapTaskStatusToTool(AsyncTaskStatus.COMPLETE));
        assertEquals(GhostService.STATUS_FAILED, GhostServiceImpl.mapTaskStatusToTool(AsyncTaskStatus.FAILURE));
        assertEquals(GhostService.STATUS_ABORTED, GhostServiceImpl.mapTaskStatusToTool(AsyncTaskStatus.CANCEL));
        assertEquals(GhostService.STATUS_PAUSED, GhostServiceImpl.mapTaskStatusToTool(AsyncTaskStatus.PAUSE));
        assertEquals(GhostService.STATUS_PAUSED, GhostServiceImpl.mapTaskStatusToTool(AsyncTaskStatus.PAUSING));
        assertEquals(GhostService.STATUS_RUNNING, GhostServiceImpl.mapTaskStatusToTool(AsyncTaskStatus.RUNNING));
        assertEquals(GhostService.STATUS_RUNNING, GhostServiceImpl.mapTaskStatusToTool(AsyncTaskStatus.CANCELING));
        assertEquals(GhostService.STATUS_INIT, GhostServiceImpl.mapTaskStatusToTool(AsyncTaskStatus.INIT));
        assertEquals(GhostService.STATUS_INIT, GhostServiceImpl.mapTaskStatusToTool(AsyncTaskStatus.BLOCK));
        assertEquals(GhostService.STATUS_INIT, GhostServiceImpl.mapTaskStatusToTool(AsyncTaskStatus.WAIT_START));
        assertNull(GhostServiceImpl.mapTaskStatusToTool(null));
    }

    // ==================================================================================
    // fixtures & assertion helpers
    // ==================================================================================

    private static void assertTranslation(String rawMessage, String expected) {
        ErrorMessageException translated = GhostServiceImpl.translateGhostFailure(new RuntimeException(rawMessage));
        assertEquals(expected, translated.getMessage());
    }

    private static void assertSingleBlock(GhostPrecheckVO vo, String code) {
        assertEquals(1, vo.getHardBlocks().size());
        assertEquals(code, vo.getHardBlocks().get(0).getCode());
        assertNotNull(vo.getHardBlocks().get(0).getReason());
    }

    private static void assertSuggestion(GhostPrecheckVO vo, Long sizeBytes, Long rows) {
        assertTrue(vo.getHardBlocks().isEmpty());
        assertEquals(1, vo.getSuggestions().size());
        GhostSuggestionVO suggestion = vo.getSuggestions().get(0);
        assertEquals(GhostServiceImpl.SUGGEST_LARGE_TABLE, suggestion.getCode());
        assertEquals(sizeBytes, suggestion.getSizeBytes());
        assertEquals(rows, suggestion.getTableRows());
        assertEquals(Long.valueOf(1024L * 1024L * 1024L), suggestion.getSizeThresholdBytes());
        assertEquals(Long.valueOf(10000000L), suggestion.getRowThreshold());
        assertEquals(DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_SUGGEST_LARGE_TABLE.name(), 1024L, 10000000L),
                suggestion.getReason());
    }

    private static GhostPrecheckFO precheckFO(String dbName, String tableName, String ddlSql) {
        GhostPrecheckFO fo = new GhostPrecheckFO();
        fo.setDsId(DS_ID);
        fo.setDbName(dbName);
        fo.setTableName(tableName);
        fo.setDdlSql(ddlSql);
        return fo;
    }

    private static GhostExecuteFO executeFO(String ddlSql) {
        GhostExecuteFO fo = new GhostExecuteFO();
        fo.setDsId(DS_ID);
        fo.setDbName(DB);
        fo.setDdlSql(ddlSql);
        return fo;
    }

    private static GhostAsyncTaskConfig taskConfig(String ownerUid) {
        GhostAsyncTaskConfig cfg = new GhostAsyncTaskConfig();
        cfg.setUserId(ownerUid);
        cfg.setSessionId(SESSION_ID);
        cfg.setDsId(DS_ID);
        cfg.setDbName(DB);
        cfg.setTableName(TABLE);
        cfg.setDdlSql("ALTER TABLE t_order ADD COLUMN extra VARCHAR(64)");
        return cfg;
    }

    private DmExecAsyncTaskDO task(AsyncTaskStatus status, GhostAsyncTaskConfig cfg) {
        DmExecAsyncTaskDO taskDO = new DmExecAsyncTaskDO();
        taskDO.setId(77L);
        taskDO.setBizId(SESSION_ID);
        taskDO.setBizType(TOOL);
        taskDO.setUid(cfg.getUserId());
        taskDO.setStatus(status);
        taskDO.setStatusMsg("Phase: COPYING; Copy: 10/39 25.6%");
        taskDO.setConfigData(JsonUtils.toJson(cfg));
        taskDO.setShowInDock(true);
        taskDO.setProcessType(AsyncTaskProcessType.PROGRESS);
        taskDO.setProcessValue("42");
        taskDO.setGmtCreate(new Date());
        taskDO.setTimeOfStart(new Date());
        return taskDO;
    }

    private static DmDsDO ds(DataSourceType type, LifeCycleState state) {
        DmDsDO dsDO = new DmDsDO();
        dsDO.setId(DS_ID);
        dsDO.setDsEnvId(1L);
        dsDO.setUid(PUID);
        dsDO.setDataSourceType(type);
        dsDO.setInstanceDesc("prod-mysql");
        dsDO.setHost("10.0.0.1");
        dsDO.setBindClusterId(CLUSTER_ID);
        dsDO.setLifeCycleState(state);
        return dsDO;
    }

    private static DataSourceConfig dsConfig(DataSourceType type) {
        DataSourceConfig config = new DataSourceConfig();
        config.setDataSourceType(type);
        config.setUserName("clouddm_agent");
        return config;
    }

    private static ResultList queryResult(String[] columns, String[][] rows) {
        ResultSetMeta meta = new ResultSetMeta();
        meta.setSuccess(true);
        meta.setResultType(ResultType.ResultSetMeta);
        meta.setColumnList(Arrays.asList(columns));

        ResultSet resultSet = new ResultSet();
        resultSet.setSuccess(true);
        resultSet.setResultType(ResultType.ResultSet);
        List<ResultSetRow> rowSet = new ArrayList<>();
        for (String[] rawRow : rows) {
            ResultSetRow resultRow = new ResultSetRow();
            List<ResultSetValue> data = new ArrayList<>();
            for (String value : rawRow) {
                data.add(ResultSetValue.of(true, false, value, 0, value == null ? 0 : value.length()));
            }
            resultRow.setData(data);
            rowSet.add(resultRow);
        }
        resultSet.setRowSet(rowSet);

        ResultList list = new ResultList();
        List<Result> results = new ArrayList<>();
        results.add(meta);
        results.add(resultSet);
        list.setResultList(results);
        return list;
    }

    private static ResultList messageResult(MessageLevel level, String message) {
        ResultMessage resultMessage = new ResultMessage();
        resultMessage.setSuccess(true);
        resultMessage.setLevel(level);
        resultMessage.setMessage(message);
        resultMessage.setResultType(ResultType.Message);

        ResultList list = new ResultList();
        List<Result> results = new ArrayList<>();
        results.add(resultMessage);
        list.setResultList(results);
        return list;
    }
}
