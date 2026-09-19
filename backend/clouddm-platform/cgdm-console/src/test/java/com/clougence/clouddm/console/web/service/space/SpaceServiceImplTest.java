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
package com.clougence.clouddm.console.web.service.space;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
import com.clougence.clouddm.console.web.component.dsconfig.DmDsConfigService;
import com.clougence.clouddm.console.web.component.dsconfig.mode.DsLevels;
import com.clougence.clouddm.console.web.component.execute.QueryService;
import com.clougence.clouddm.console.web.component.opsdiag.OpsDiagnosticExecutor;
import com.clougence.clouddm.console.web.model.fo.space.SpaceDatabasesFO;
import com.clougence.clouddm.console.web.model.fo.space.SpaceTablesFO;
import com.clougence.clouddm.console.web.model.vo.session.SessionInstanceVO;
import com.clougence.clouddm.console.web.model.vo.space.SpaceDbVO;
import com.clougence.clouddm.console.web.model.vo.space.SpaceDatabasesVO;
import com.clougence.clouddm.console.web.model.vo.space.SpaceTableVO;
import com.clougence.clouddm.console.web.model.vo.space.SpaceTablesVO;
import com.clougence.clouddm.platform.dal.access.DataSourceDal;
import com.clougence.clouddm.platform.dal.mapper.datasource.DmDsMapper;
import com.clougence.clouddm.platform.dal.model.LifeCycleState;
import com.clougence.clouddm.platform.dal.model.datasource.DmDsDO;
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
import com.clougence.schema.umi.struts.UmiTypes;

/**
 * Unit tests for {@link SpaceServiceImpl} (design 09-19-table-space-usage §6): M1/M2/P1/P2 row
 * mapping (mixed-case column labels, null breakdown columns on the PG database level, the
 * GREATEST(reltuples,0) guard evidenced in the dispatched SQL), dbName safety (MySQL embeds it as
 * a string literal — single-quote/backslash doubling, never identifier quoting; PG passes it as
 * the connection catalog and NEVER embeds it into SQL; NUL/overlong/blank rejected with zero
 * remote calls), timeout translation, statement-error translation and the whitelist/tenant
 * rejections.
 * <p>
 * Discipline (copied from the B2/B3 suites): JUnit4 + hand-written mocks + ReflectionTestUtils
 * only; the PluginManager static lookups are isolated behind the protected seams of
 * {@link OpsDiagnosticExecutor} overridden by an anonymous subclass (no mockStatic, no Spring
 * context, no MockMvc). Every failure path verifies the finally-release ({@code closeSession});
 * rejection paths verify zero remote calls; unrecognized SQL fails the test on the spot.
 */
public class SpaceServiceImplTest {

    private static final String   UID          = "uid-operator";
    private static final String   PUID         = "puid-tenant";
    private static final long     DS_ID        = 9L;
    private static final String   DIAG_SESSION = "diag-session";
    private static final String   DS_USER      = "clouddm_agent";

    /** snake_case column set shared by both dialects (design §2); deliberately mixed-case in fixtures. */
    private static final String[] DB_COLS      = { "db_name", "table_count", "row_estimate", "data_bytes", //
            "index_bytes", "free_bytes", "total_bytes" };
    private static final String[] TABLE_COLS   = { "table_name", "row_estimate", "data_bytes", "index_bytes", //
            "free_bytes", "total_bytes" };
    /** The PG drill-down lists EVERY schema of the target database — one extra leading column. */
    private static final String[] PG_TABLE_COLS = { "schema_name", "table_name", "row_estimate", "data_bytes", //
            "index_bytes", "free_bytes", "total_bytes" };

    private SpaceServiceImpl      service;
    private DataSourceDal         dsDal;
    private DmDsMapper            dsMapper;
    private DmDsConfigService     dmDsConfigService;
    private QueryService          queryService;

    /** SQL bodies actually dispatched to the (mocked) worker, in order. */
    private final List<String>    executedSqls = Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong      queryIdSeq   = new AtomicLong();

    /** SQL body fragment whose execution must hang (sleep 5s) — for the timeout group. */
    private String                hangSqlFragment;

    /** Per-test configurable results of each dialect query. */
    private ResultList            myDbResult;
    private ResultList            myTableResult;
    private ResultList            pgDbResult;
    private ResultList            pgTableResult;

    private final SessionSpi      fakeSpi      = new SessionSpi() {

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
        service = new SpaceServiceImpl();

        // the channel-C seams (PluginManager statics) live on the shared executor — same wiring as B2/B3
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
        when(dsDal.dsMapper()).thenReturn(dsMapper);

        ReflectionTestUtils.setField(diagnosticExecutor, "queryService", queryService);
        ReflectionTestUtils.setField(service, "dsDal", dsDal);
        ReflectionTestUtils.setField(service, "dmDsConfigService", dmDsConfigService);
        ReflectionTestUtils.setField(service, "diagnosticExecutor", diagnosticExecutor);

        // default fixtures: owned MySQL datasource + its config, everything healthy and empty
        when(dsMapper.listByOwnerAndIds(eq(PUID), any())).thenReturn(List.of(ds(DataSourceType.MySQL, LifeCycleState.CREATED)));
        when(dmDsConfigService.fetchDsConfigFromExists(DS_ID)).thenReturn(dsConfig(DataSourceType.MySQL, DS_USER));
        when(queryService.createSession(anyString(), any(), any())).thenReturn(DIAG_SESSION);
        myDbResult = queryResult(DB_COLS, new String[0][]);
        myTableResult = queryResult(TABLE_COLS, new String[0][]);
        pgDbResult = queryResult(DB_COLS, new String[0][]);
        pgTableResult = queryResult(PG_TABLE_COLS, new String[0][]);

        when(queryService.syncExecuteQuery(anyString(), anyString(), any(QueryRequest.class))).thenAnswer(invocation -> {
            QueryRequest request = invocation.getArgument(2);
            String body = request.getQueryBody();
            executedSqls.add(body);
            if (hangSqlFragment != null && body.contains(hangSqlFragment)) {
                Thread.sleep(5_000L);
            }
            if (body.contains("FROM pg_database d")) {
                return pgDbResult;
            }
            if (body.contains("pg_class")) {
                return pgTableResult;
            }
            if (body.contains("information_schema.tables")) {
                return body.contains("GROUP BY") ? myDbResult : myTableResult;
            }
            throw new IllegalStateException("unexpected sql dispatched: " + body);
        });
    }

    // ------------------------------------------------------------- group 1: MySQL databases (M1)

    @Test
    public void databases_mysql_dispatchesSingleAggregate_mapsRowsCaseInsensitiveColumns() {
        myDbResult = queryResult(//
                new String[] { "DB_NAME", "table_count", "Row_Estimate", "DATA_BYTES", "index_bytes", "FREE_BYTES", "Total_Bytes" }, //
                new String[][] { { "orders", "120", "8400000", "52428800", "10485760", "2097152", "62914560" }, //
                        { "logs", "8", null, "1048576", "0", null, "1048576" } });

        SpaceDatabasesVO vo = service.databases(PUID, UID, databasesFO());

        assertEquals("the databases endpoint is exactly one M1 statement", //
                List.of(SpaceServiceImpl.SQL_MY_DATABASES), executedSqls);
        assertEquals("MYSQL_8", vo.getBranch());
        assertEquals(2, vo.getDbs().size());

        SpaceDbVO first = vo.getDbs().get(0);
        assertEquals("orders", first.getDbName());
        assertEquals(Long.valueOf(120L), first.getTableCount());
        assertEquals(Long.valueOf(8400000L), first.getRowEstimate());
        assertEquals(Long.valueOf(52428800L), first.getDataBytes());
        assertEquals(Long.valueOf(10485760L), first.getIndexBytes());
        assertEquals(Long.valueOf(2097152L), first.getFreeBytes());
        assertEquals(Long.valueOf(62914560L), first.getTotalBytes());

        SpaceDbVO second = vo.getDbs().get(1);
        assertNull("null row estimate tolerated", second.getRowEstimate());
        assertNull("null data_free tolerated", second.getFreeBytes());
        assertEquals(Long.valueOf(0L), second.getIndexBytes());
        verify(queryService).closeSession(UID, DIAG_SESSION);
    }

    @Test
    public void databases_mysql_emptyResult_normalEmptyStateNotError() {
        SpaceDatabasesVO vo = service.databases(PUID, UID, databasesFO()); // default fixture has zero rows

        assertEquals("an instance without user databases is a normal empty state", 0, vo.getDbs().size());
        assertNotNull(vo.getDbs());
        verify(queryService).closeSession(UID, DIAG_SESSION);
    }

    @Test
    public void databases_sqlContract_excludesSystemSchemas_baseTablesOnly() {
        String sql = SpaceServiceImpl.SQL_MY_DATABASES;
        assertTrue("4 system schemas excluded", //
                sql.contains("NOT IN ('information_schema','mysql','sys','performance_schema')"));
        assertTrue("views must not inflate the table count", sql.contains("table_type = 'BASE TABLE'"));
        assertTrue(sql.contains("ORDER BY total_bytes DESC"));
    }

    // ------------------------------------------------------------- group 2: PG databases (instance-wide, pg_database shared catalog)

    @Test
    public void databases_pg_listsEveryInstanceDatabase_totalOnlyBreakdownNull() {
        usePostgresDs();
        pgDbResult = queryResult(DB_COLS, new String[][] { //
                { "app_db", null, null, null, null, null, "13212057600" }, //
                { "postgres", null, null, null, null, null, "9314304" } });

        SpaceDatabasesVO vo = service.databases(PUID, UID, databasesFO());

        assertEquals("no probe anymore — the shared catalog answers from the default connection", //
                List.of(SpaceServiceImpl.SQL_PG_DATABASES), executedSqls);
        assertEquals("POSTGRES", vo.getBranch());
        assertEquals(2, vo.getDbs().size());

        SpaceDbVO first = vo.getDbs().get(0);
        assertEquals("app_db", first.getDbName());
        assertEquals(Long.valueOf(13212057600L), first.getTotalBytes());
        assertNull("cross-database breakdown is physically unreachable — null (frontend shows '-')", first.getTableCount());
        assertNull(first.getRowEstimate());
        assertNull(first.getDataBytes());
        assertNull(first.getIndexBytes());
        assertNull(first.getFreeBytes());
        verify(queryService).closeSession(UID, DIAG_SESSION);
    }

    @Test
    public void databases_pg_sqlContract_templateDbsExcluded_databaseSizeFromSharedCatalog() {
        String sql = SpaceServiceImpl.SQL_PG_DATABASES;
        assertTrue("template databases are not user capacity", sql.contains("WHERE NOT d.datistemplate"));
        assertTrue("pg_database_size works from ANY connection without touching the target db", //
                sql.contains("pg_database_size(d.oid) AS total_bytes"));
        assertTrue("breakdown columns are projected NULL — never faked", sql.contains("NULL AS table_count"));
        assertTrue(sql.contains("NULL AS data_bytes"));
        assertTrue(sql.contains("ORDER BY total_bytes DESC"));
    }

    @Test
    public void tables_pg_sqlContract_crossSchema_systemAndTempSchemasExcluded_neverFiltered() {
        String sql = SpaceServiceImpl.SQL_PG_TABLES;
        assertTrue("the drill-down lists EVERY schema of the target database", sql.contains("n.nspname AS schema_name"));
        assertFalse("dbName must never be embedded into the PG statement (it selects the connection instead)", //
                sql.contains("nspname ="));
        assertTrue(sql.contains("NOT IN ('pg_catalog','information_schema','pg_toast')"));
        assertTrue("other sessions' pg_temp_N schemas carry no capacity-inspection value — excluded (main-session ruling)", //
                sql.contains("NOT pg_is_other_temp_schema(n.oid)"));
        assertTrue("ordinary tables + partitioned parents only (indexes/TOAST/views counted via size functions)", //
                sql.contains("relkind IN ('r','p')"));
        assertTrue("reltuples = -1 (never analyzed, PG14+) must be floored to 0 in SQL", //
                sql.contains("GREATEST(c.reltuples,0)"));
        assertTrue("size functions take oids — no regclass name resolution", sql.contains("pg_table_size(c.oid)"));
        assertTrue(sql.contains("pg_indexes_size(c.oid)"));
        assertTrue(sql.contains("pg_total_relation_size(c.oid)"));
        assertTrue(sql.contains("ORDER BY total_bytes DESC"));
    }

    // ------------------------------------------------------------- group 3: MySQL tables (M2) + escaping

    @Test
    public void tables_mysql_singleQuoteName_dispatchedSqlCarriesLiteralDoubling_mapsRows() {
        myTableResult = queryResult(//
                new String[] { "TABLE_NAME", "Row_Estimate", "DATA_BYTES", "index_bytes", "Free_Bytes", "TOTAL_BYTES" }, //
                new String[][] { { "orders_2026", "4200000", "31457280", "4194304", "1048576", "35651584" } });

        SpaceTablesVO vo = service.tables(PUID, UID, tablesFO("or'ders"));

        assertEquals(1, executedSqls.size());
        String dispatched = executedSqls.get(0);
        assertTrue("table_schema is compared against a STRING LITERAL — single-quote wrap + inner doubling "
                + "(identifier backticks here would be parsed as a column reference)", dispatched.contains("table_schema = 'or''ders'"));
        assertTrue(dispatched.contains("information_schema.tables"));
        assertTrue(dispatched.contains("table_type = 'BASE TABLE'"));
        assertEquals("or'ders", vo.getDbName());
        assertEquals(1, vo.getTables().size());

        SpaceTableVO table = vo.getTables().get(0);
        assertEquals("orders_2026", table.getTableName());
        assertEquals(Long.valueOf(4200000L), table.getRowEstimate());
        assertEquals(Long.valueOf(31457280L), table.getDataBytes());
        assertEquals(Long.valueOf(4194304L), table.getIndexBytes());
        assertEquals(Long.valueOf(1048576L), table.getFreeBytes());
        assertEquals(Long.valueOf(35651584L), table.getTotalBytes());
        verify(queryService).closeSession(UID, DIAG_SESSION);
    }

    @Test
    public void tables_mysql_emptyResult_normalEmptyStateNotError() {
        SpaceTablesVO vo = service.tables(PUID, UID, tablesFO("orders"));

        assertEquals("orders", vo.getDbName());
        assertEquals("a database without base tables is a normal empty state", 0, vo.getTables().size());
        verify(queryService).closeSession(UID, DIAG_SESSION);
    }

    // ------------------------------------------------------------- group 4: PG tables (cross-schema drill-down on the target db)

    @Test
    public void tables_pg_connectsToTargetDb_neverEmbedsName_mapsSchemaColumn() {
        usePostgresDs();
        pgTableResult = queryResult(PG_TABLE_COLS, new String[][] { //
                { "public", "orders_2026", "4200000", "31457280", "4194304", null, "35651584" }, //
                { "audit", "access_log", "0", "0", "0", null, "8192" } });

        SpaceTablesVO vo = service.tables(PUID, UID, tablesFO("app_db"));

        assertEquals("exactly one statement — no probe", 1, executedSqls.size());
        assertEquals("dbName selects the CONNECTION, it is never concatenated into SQL", //
                SpaceServiceImpl.SQL_PG_TABLES, executedSqls.get(0));

        ArgumentCaptor<SessionContextDTO> ctxCaptor = ArgumentCaptor.forClass(SessionContextDTO.class);
        verify(queryService).createSession(eq(UID), any(), ctxCaptor.capture());
        assertEquals("rdbCatalog pins the session to the target database (DEFAULT_DATABASE override)", //
                "app_db", ctxCaptor.getValue().getRdbCatalog());

        assertEquals(2, vo.getTables().size());
        SpaceTableVO first = vo.getTables().get(0);
        assertEquals("public", first.getSchemaName());
        assertEquals("orders_2026", first.getTableName());
        assertEquals(Long.valueOf(4200000L), first.getRowEstimate());
        assertNull("PG free_bytes is always null", first.getFreeBytes());
        assertEquals(Long.valueOf(35651584L), first.getTotalBytes());
        SpaceTableVO second = vo.getTables().get(1);
        assertEquals("audit", second.getSchemaName());
        assertEquals(Long.valueOf(0L), second.getRowEstimate());
        verify(queryService).closeSession(UID, DIAG_SESSION);
    }

    // ------------------------------------------------------------- group 5: identifier rejection shapes

    @Test
    public void tables_nulCharName_rejectedWithoutAnyRemoteCall() {
        try {
            service.tables(PUID, UID, tablesFO("or\0ders"));
            fail("NUL can never be escaped inside a SQL string literal — must be rejected");
        } catch (ErrorMessageException e) {
            assertNotNull(e.getMessage());
        }
        verify(queryService, never()).createSession(anyString(), any(), any());
        assertTrue(executedSqls.isEmpty());
    }

    @Test
    public void tables_overlongName_rejectedWithoutAnyRemoteCall() {
        String name = "a".repeat(SpaceServiceImpl.MAX_IDENTIFIER_LENGTH + 1);
        try {
            service.tables(PUID, UID, tablesFO(name));
            fail("names beyond the identifier length bound must be rejected");
        } catch (ErrorMessageException e) {
            assertNotNull(e.getMessage());
        }
        verify(queryService, never()).createSession(anyString(), any(), any());
        assertTrue(executedSqls.isEmpty());
    }

    @Test
    public void tables_blankName_rejectedWithoutAnyRemoteCall() {
        try {
            service.tables(PUID, UID, tablesFO("   "));
            fail("blank dbName must be rejected (service-side backstop of @NotBlank)");
        } catch (ErrorMessageException e) {
            assertNotNull(e.getMessage());
        }
        verify(queryService, never()).createSession(anyString(), any(), any());
        assertTrue(executedSqls.isEmpty());
    }

    @Test
    public void escapeHelpers_mySqlStringLiteralQuoting() {
        // MySQL M2 embeds dbName as a string literal: single-quote wrap + inner single-quote doubling
        assertEquals("'or''ders'", SpaceServiceImpl.escapeMySqlStringLiteral("or'ders"));
        assertEquals("'plain'", SpaceServiceImpl.escapeMySqlStringLiteral("plain"));
        // MySQL default sql_mode: backslash is the escape character — it must be doubled FIRST,
        // otherwise a trailing '\' would swallow the closing quote and break out of the literal
        assertEquals("'back\\\\slash'", SpaceServiceImpl.escapeMySqlStringLiteral("back\\slash"));
        assertEquals("'a\\\\''b'", SpaceServiceImpl.escapeMySqlStringLiteral("a\\'b"));
        // identifier quote chars carry no special meaning inside a plain literal
        assertEquals("'pu\"blic'", SpaceServiceImpl.escapeMySqlStringLiteral("pu\"blic"));
        assertEquals("'or`ders'", SpaceServiceImpl.escapeMySqlStringLiteral("or`ders"));
        // PG needs no escape helper at all: dbName is a connection parameter there, never SQL text
    }

    // ------------------------------------------------------------- group 6: statement errors → SPACE_QUERY_FAILED

    @Test
    public void databases_mysql_statementError_failsWholeRequestCarryingRawReason_sessionClosed() {
        myDbResult = messageResult(MessageLevel.Error, "SELECT command denied to user 'agent'@'%' for table 'tables'");
        try {
            service.databases(PUID, UID, databasesFO());
            fail("a statement ERROR fails the whole single-SQL snapshot (no block degradation in B4)");
        } catch (ErrorMessageException e) {
            assertTrue("raw DB reason must be carried in {0}", e.getMessage().contains("command denied"));
            assertTrue("SPACE_QUERY_FAILED prefix renders in the default zh bundle", e.getMessage().contains("查询空间占用失败"));
        }
        verify(queryService).closeSession(UID, DIAG_SESSION);
    }

    @Test
    public void tables_pg_statementError_failsWholeRequest_noPrivilegeEscalationExists() {
        usePostgresDs();
        pgTableResult = messageResult(MessageLevel.Error, "permission denied for database billing");
        try {
            service.tables(PUID, UID, tablesFO("billing"));
            fail("PG statement errors fail the request carrying the raw reason");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("permission denied for database billing"));
        }
        verify(queryService).closeSession(UID, DIAG_SESSION);
    }

    // ------------------------------------------------------------- group 7: timeout translation

    @Test
    public void databases_createSessionHangs_timesOutWholeFailure() {
        ReflectionTestUtils.setField(service, "queryTimeoutSeconds", 1L);
        when(queryService.createSession(anyString(), any(), any())).thenAnswer(invocation -> {
            Thread.sleep(5_000L);
            return DIAG_SESSION;
        });
        try {
            service.databases(PUID, UID, databasesFO());
            fail("hanging session creation must time out");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("超时"));
        }
    }

    @Test
    public void tables_statementHangs_timesOutWholeFailure_sessionClosed() {
        ReflectionTestUtils.setField(service, "queryTimeoutSeconds", 1L);
        hangSqlFragment = "information_schema.tables";
        try {
            service.tables(PUID, UID, tablesFO("orders"));
            fail("hanging snapshot SQL must time out");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("超时"));
            assertTrue(e.getMessage().contains("空间占用"));
        }
        verify(queryService).closeSession(UID, DIAG_SESSION);
    }

    // ------------------------------------------------------------- group 8: whitelist / tenant

    @Test
    public void databases_unsupportedDsType_rejectedWithoutAnyRemoteCall() {
        when(dsMapper.listByOwnerAndIds(eq(PUID), any())).thenReturn(List.of(ds(DataSourceType.MariaDB, LifeCycleState.CREATED)));
        try {
            service.databases(PUID, UID, databasesFO());
            fail("MariaDB is outside the space-usage whitelist");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("暂不支持空间占用查看"));
        }
        verify(queryService, never()).createSession(anyString(), any(), any());
        assertTrue(executedSqls.isEmpty());
    }

    @Test
    public void tables_foreignTenantDsId_notFoundSemanticsWithoutAnyRemoteCall() {
        when(dsMapper.listByOwnerAndIds(eq(PUID), any())).thenReturn(Collections.emptyList());
        try {
            service.tables(PUID, UID, tablesFO("orders"));
            fail("cross-tenant dsId must be rejected");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("数据源不存在或无权限"));
        }
        verify(queryService, never()).createSession(anyString(), any(), any());
        assertTrue(executedSqls.isEmpty());
    }

    @Test
    public void listInstances_filtersWhitelistAndDeleted_sortsByName() {
        DmDsDO mysql = ds(DataSourceType.MySQL, LifeCycleState.CREATED);
        mysql.setId(2L);
        mysql.setInstanceDesc("zeta-mysql");

        DmDsDO pg = ds(DataSourceType.PostgreSQL, LifeCycleState.CREATED);
        pg.setId(1L);
        pg.setInstanceDesc(null); // blank desc → falls back to host
        pg.setHost("10.0.0.9");

        DmDsDO maria = ds(DataSourceType.MariaDB, LifeCycleState.CREATED);
        maria.setId(5L);
        maria.setInstanceDesc("aaa-mariadb");

        DmDsDO oracle = ds(DataSourceType.Oracle, LifeCycleState.CREATED);
        oracle.setId(3L);
        oracle.setInstanceDesc("beta-oracle");

        DmDsDO deleted = ds(DataSourceType.MySQL, LifeCycleState.DELETED);
        deleted.setId(4L);
        deleted.setInstanceDesc("aaa-deleted");

        when(dsMapper.listByOwner(PUID)).thenReturn(new ArrayList<>(List.of(mysql, pg, maria, oracle, deleted)));

        List<SessionInstanceVO> result = service.listInstances(PUID, UID);

        assertEquals(2, result.size());
        assertEquals(Long.valueOf(1L), result.get(0).getDsId());
        assertEquals("10.0.0.9", result.get(0).getDsName());
        assertEquals("PostgreSQL", result.get(0).getDsType());
        assertEquals(Long.valueOf(2L), result.get(1).getDsId());
        assertEquals("zeta-mysql", result.get(1).getDsName());
        assertEquals("MySQL", result.get(1).getDsType());
    }

    // ------------------------------------------------------------- helpers

    /** Switches the ownership/config fixtures to a PostgreSQL datasource. */
    private void usePostgresDs() {
        when(dsMapper.listByOwnerAndIds(eq(PUID), any())).thenReturn(List.of(ds(DataSourceType.PostgreSQL, LifeCycleState.CREATED)));
        when(dmDsConfigService.fetchDsConfigFromExists(DS_ID)).thenReturn(dsConfig(DataSourceType.PostgreSQL, DS_USER));
    }

    private static SpaceDatabasesFO databasesFO() {
        SpaceDatabasesFO fo = new SpaceDatabasesFO();
        fo.setDsId(DS_ID);
        return fo;
    }

    private static SpaceTablesFO tablesFO(String dbName) {
        SpaceTablesFO fo = new SpaceTablesFO();
        fo.setDsId(DS_ID);
        fo.setDbName(dbName);
        return fo;
    }

    private static DmDsDO ds(DataSourceType type, LifeCycleState state) {
        DmDsDO dsDO = new DmDsDO();
        dsDO.setId(DS_ID);
        dsDO.setDsEnvId(1L);
        dsDO.setUid(PUID);
        dsDO.setDataSourceType(type);
        dsDO.setInstanceDesc("prod-mysql");
        dsDO.setHost("10.0.0.1");
        dsDO.setLifeCycleState(state);
        return dsDO;
    }

    private static DataSourceConfig dsConfig(DataSourceType type, String userName) {
        DataSourceConfig config = new DataSourceConfig();
        config.setDataSourceType(type);
        config.setUserName(userName);
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
