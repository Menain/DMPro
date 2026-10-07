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
package com.clougence.clouddm.console.web.service.dmlbatch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.api.sidecar.session.execute.ResultList;
import com.clougence.clouddm.base.metadata.ds.DataSourceConfig;
import com.clougence.clouddm.base.metadata.ds.DataSourceType;
import com.clougence.clouddm.console.web.component.config.RootUserConfig;
import com.clougence.clouddm.console.web.component.dsconfig.mode.DsLevels;
import com.clougence.clouddm.console.web.component.execute.QueryService;
import com.clougence.clouddm.console.web.component.governance.TicketSegment;
import com.clougence.clouddm.console.web.component.opsdiag.OpsDiagnosticExecutor;
import com.clougence.clouddm.console.web.service.asyntask.AsyncTaskServiceService;
import com.clougence.clouddm.console.web.service.dmlbatch.asyntask.DmlBatchAsyncTask;
import com.clougence.clouddm.console.web.service.dmlbatch.asyntask.DmlBatchAsyncTaskConfig;
import com.clougence.clouddm.console.web.service.dmlbatch.asyntask.DmlBatchCheckpoint;
import com.clougence.clouddm.platform.dal.access.ExecutionDal;
import com.clougence.clouddm.platform.dal.access.SystemDal;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecAsyncTaskMapper;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.datasource.DmDsDO;
import com.clougence.clouddm.platform.dal.model.execution.AsyncTaskProcessType;
import com.clougence.clouddm.platform.dal.model.execution.AsyncTaskStatus;
import com.clougence.clouddm.platform.dal.model.execution.DmExecAsyncTaskDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;
import com.clougence.clouddm.sdk.execute.resultset.echo.Result;
import com.clougence.clouddm.sdk.execute.resultset.echo.ResultMessage;
import com.clougence.clouddm.sdk.execute.resultset.echo.ResultSet;
import com.clougence.clouddm.sdk.execute.resultset.echo.ResultSetMeta;
import com.clougence.clouddm.sdk.execute.resultset.echo.ResultSetRow;
import com.clougence.clouddm.sdk.execute.resultset.echo.ResultSetValue;
import com.clougence.clouddm.sdk.execute.resultset.echo.ResultType;
import com.clougence.clouddm.sdk.execute.session.QueryRequest;
import com.clougence.clouddm.sdk.execute.session.QueryResultConf;
import com.clougence.clouddm.sdk.execute.session.SessionContextDTO;
import com.clougence.clouddm.sdk.execute.session.SessionSpi;
import com.clougence.clouddm.sdk.sql.editor.rewrite.DmlBatchRangeSpec;
import com.clougence.schema.umi.struts.UmiTypes;
import com.clougence.utils.JsonUtils;

/**
 * B7 N1b (implement.md 单测矩阵「探测」): probeForKey — the dual-dialect probe SQL FULL-TEXT pins
 * (N1a handover obligation #1: the UK NOT NULL judgement lives IN the SQL/selection contract),
 * the never-throw degradation (timeout / query failure / bad identifier — zero remote calls),
 * the key-selection + whitelist matrix (nullable-UK-only = NO_KEY, composite = UNSUPPORTED),
 * the version gate (裁决 #3 — console-side, obligation #2), the six-KV fail-safe plane with
 * defensive clamping, the checkpoint seeding (done-side only, unparsable inheritance REFUSES),
 * the dispatch-time task submission (configData assembly + submitTask-failure-throws-no-residue)
 * and the C6 binding lookup.
 * <p>
 * Discipline: JUnit4 + hand-written mocks + ReflectionTestUtils + ArgumentCaptor; the
 * PluginManager statics are isolated behind the protected seams of OpsDiagnosticExecutor (the
 * GhostServiceImplTest scaffold).
 */
public class DmlBatchServiceProbeTest {

    private DmlBatchServiceImpl     service;
    private QueryService            queryService;
    private SystemDal               systemDal;
    private AsyncTaskServiceService asyncTaskServiceService;
    private ExecutionDal            executionDal;
    private DmExecAsyncTaskMapper   asyncTaskMapper;

    private static final String   UID   = "uid-submitter";
    private static final long     DS_ID = 20L;
    private static final String   DB    = "app_db";
    private static final String   TABLE = "t_order";
    private static final String   DIAG_SESSION = "diag-session";

    /** SQL bodies actually dispatched to the (mocked) worker, in order. */
    private final List<String>    executedSqls = Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong      queryIdSeq   = new AtomicLong();

    /** per-test swappable probe fixtures */
    private ResultList            rowsResult;
    private ResultList            pkResult;
    private ResultList            ukResult;

    private DmDsDO                myDs;
    private DmDsDO                pgDs;
    private DataSourceConfig      myConfig;
    private DataSourceConfig      pgConfig;

    private static final String[] MY_ROW_COLS = { "row_estimate" };
    private static final String[] MY_PK_COLS  = { "column_name", "data_type", "numeric_scale", "is_nullable" };
    private static final String[] MY_UK_COLS  = { "index_name", "column_name", "data_type", "numeric_scale", "is_nullable" };
    private static final String[] PG_ROW_COLS = { "row_estimate" };
    private static final String[] PG_PK_COLS  = { "column_name", "data_type", "not_null" };
    private static final String[] PG_UK_COLS  = { "index_name", "column_name", "data_type", "not_null", "seq_in_index" };

    private final SessionSpi fakeSpi = new SessionSpi() {
        @Override
        public SessionContextDTO createSessionContext(DataSourceConfig dsConfig, Map<String, Object> params) {
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
        service = new DmlBatchServiceImpl();

        // channel-C scaffold (the GhostServiceImplTest pattern): the PluginManager statics are
        // isolated behind the executor's protected seams
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

        queryService = mock(QueryService.class);
        systemDal = mock(SystemDal.class);
        asyncTaskServiceService = mock(AsyncTaskServiceService.class);
        executionDal = mock(ExecutionDal.class);
        asyncTaskMapper = mock(DmExecAsyncTaskMapper.class);
        when(executionDal.asyncTaskMapper()).thenReturn(asyncTaskMapper);
        when(queryService.createSession(anyString(), any(), any())).thenReturn(DIAG_SESSION);

        ReflectionTestUtils.setField(diagnosticExecutor, "queryService", queryService);
        ReflectionTestUtils.setField(service, "diagnosticExecutor", diagnosticExecutor);
        ReflectionTestUtils.setField(service, "systemDal", systemDal);
        ReflectionTestUtils.setField(service, "asyncTaskServiceService", asyncTaskServiceService);
        ReflectionTestUtils.setField(service, "executionDal", executionDal);
        ReflectionTestUtils.setField(service, "queryTimeoutSeconds", 3L);

        myDs = ds(DataSourceType.MySQL, "8.0");
        pgDs = ds(DataSourceType.PostgreSQL, "15.4");
        myConfig = new DataSourceConfig();
        myConfig.setDataSourceType(DataSourceType.MySQL);
        pgConfig = new DataSourceConfig();
        pgConfig.setDataSourceType(DataSourceType.PostgreSQL);

        // healthy MySQL fixtures by default: single bigint PK, table found
        rowsResult = queryResult(MY_ROW_COLS, new String[][] { { "5000000" } });
        pkResult = queryResult(MY_PK_COLS, new String[][] { { "id", "bigint", "0", "NO" } });
        ukResult = queryResult(MY_UK_COLS, new String[0][]);

        when(queryService.syncExecuteQuery(anyString(), anyString(), any(QueryRequest.class))).thenAnswer(inv -> {
            QueryRequest request = inv.getArgument(2);
            String body = request.getQueryBody();
            executedSqls.add(body);
            if (body.contains("key_column_usage")) {
                return pkResult;
            }
            if (body.contains("information_schema.statistics")) {
                return ukResult;
            }
            if (body.contains("information_schema.tables")) {
                return rowsResult;
            }
            if (body.contains("contype = 'p'")) {
                return pkResult;
            }
            if (body.contains("contype = 'u'")) {
                return ukResult;
            }
            if (body.contains("reltuples")) {
                return rowsResult;
            }
            throw new IllegalStateException("unexpected sql dispatched: " + body);
        });
    }

    private static DmDsDO ds(DataSourceType type, String version) {
        DmDsDO dsDO = new DmDsDO();
        dsDO.setId(DS_ID);
        dsDO.setDsEnvId(1L);
        dsDO.setDataSourceType(type);
        dsDO.setVersion(version);
        return dsDO;
    }

    // ==================================================================================
    // probe SQL FULL-TEXT pins (both dialects — the dispatched text, golden literals)
    // ==================================================================================

    @Test
    public void probeForKey_mysql_dispatchesThePinnedSqlTexts() {
        service.probeForKey(UID, myDs, myConfig, DB, null, TABLE);

        assertEquals(3, executedSqls.size());
        // B4-caliber row estimate
        assertEquals("SELECT table_rows AS row_estimate FROM information_schema.tables "
            + "WHERE table_type = 'BASE TABLE' AND table_schema = 'app_db' AND table_name = 't_order'",
            executedSqls.get(0));
        // PK columns in key order (constraint_name='PRIMARY', ordinal_position)
        assertEquals("SELECT k.column_name AS column_name, c.data_type AS data_type, "
            + "c.numeric_scale AS numeric_scale, c.is_nullable AS is_nullable "
            + "FROM information_schema.key_column_usage k "
            + "JOIN information_schema.columns c ON c.table_schema = k.table_schema "
            + "AND c.table_name = k.table_name AND c.column_name = k.column_name "
            + "WHERE k.table_schema = 'app_db' AND k.table_name = 't_order' "
            + "AND k.constraint_name = 'PRIMARY' ORDER BY k.ordinal_position",
            executedSqls.get(1));
        // UK columns WITH is_nullable (handover obligation #1 lives in this join)
        assertEquals("SELECT s.index_name AS index_name, s.column_name AS column_name, "
            + "c.data_type AS data_type, c.numeric_scale AS numeric_scale, c.is_nullable AS is_nullable "
            + "FROM information_schema.statistics s "
            + "JOIN information_schema.columns c ON c.table_schema = s.table_schema "
            + "AND c.table_name = s.table_name AND c.column_name = s.column_name "
            + "WHERE s.table_schema = 'app_db' AND s.table_name = 't_order' "
            + "AND s.non_unique = 0 AND s.index_name <> 'PRIMARY' ORDER BY s.index_name, s.seq_in_index",
            executedSqls.get(2));
    }

    @Test
    public void probeForKey_postgres_dispatchesThePinnedSqlTexts_currentSchemaPredicate() {
        service.probeForKey(UID, pgDs, pgConfig, DB, null, TABLE);

        assertEquals(3, executedSqls.size());
        assertEquals("SELECT GREATEST(rel.reltuples, 0)::bigint AS row_estimate "
            + "FROM pg_class rel JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace "
            + "WHERE rel.relkind IN ('r','p') AND nsp.nspname = current_schema() AND rel.relname = 't_order'",
            executedSqls.get(0));
        assertEquals("SELECT a.attname AS column_name, format_type(a.atttypid, a.atttypmod) AS data_type, "
            + "a.attnotnull AS not_null "
            + "FROM pg_constraint con "
            + "JOIN pg_class rel ON rel.oid = con.conrelid "
            + "JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace "
            + "JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = ANY (con.conkey) "
            + "WHERE con.contype = 'p' AND nsp.nspname = current_schema() AND rel.relname = 't_order' "
            + "ORDER BY array_position(con.conkey, a.attnum)",
            executedSqls.get(1));
        assertEquals("SELECT con.conname AS index_name, a.attname AS column_name, "
            + "format_type(a.atttypid, a.atttypmod) AS data_type, a.attnotnull AS not_null, "
            + "array_position(con.conkey, a.attnum) AS seq_in_index "
            + "FROM pg_constraint con "
            + "JOIN pg_class rel ON rel.oid = con.conrelid "
            + "JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace "
            + "JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = ANY (con.conkey) "
            + "WHERE con.contype = 'u' AND nsp.nspname = current_schema() AND rel.relname = 't_order' "
            + "ORDER BY con.conname, array_position(con.conkey, a.attnum)",
            executedSqls.get(2));
    }

    @Test
    public void probeForKey_postgres_explicitSchema_escapedLiteralPredicate() {
        service.probeForKey(UID, pgDs, pgConfig, DB, "reporting", TABLE);

        assertTrue(executedSqls.get(0).contains("nsp.nspname = 'reporting'"));
        assertFalse(executedSqls.get(0).contains("current_schema()"));
    }

    @Test
    public void probeForKey_mysql_literalsEscaped_backslashFirstThenQuote() {
        // a table name with a quote and a backslash: the B4 escape order inside the probe SQL
        service.probeForKey(UID, myDs, myConfig, DB, null, "t'\\x");

        assertTrue(executedSqls.get(0).contains("table_name = 't''\\\\x'"));
    }

    @Test
    public void probeForKey_postgres_literalsEscaped_quoteDoublingOnly() {
        service.probeForKey(UID, pgDs, pgConfig, DB, null, "t'\\x");

        // PG: quote doubling ONLY — the backslash stays literal (standard_conforming_strings=on)
        assertTrue(executedSqls.get(0).contains("rel.relname = 't''\\x'"));
    }

    // ==================================================================================
    // probe result mapping + key selection matrix
    // ==================================================================================

    @Test
    public void probeForKey_mysql_singleBigintPk_okState() {
        DmlBatchKeyProbe probe = service.probeForKey(UID, myDs, myConfig, DB, null, TABLE);

        assertTrue(probe.isProbed());
        assertTrue(probe.isTableFound());
        assertEquals(Long.valueOf(5_000_000L), probe.getTableRows());
        assertEquals(DmlBatchKeyProbe.STATE_OK, probe.getKeyState());
        assertEquals("id", probe.getKeyColumn());
        assertEquals(DmlBatchRangeSpec.KEY_TYPE_INT, probe.getKeyType());
        assertEquals(1_000_000L, probe.getRowThreshold()); // KV default (mock returns null)
    }

    @Test
    public void probeForKey_mysql_noPk_singleNotNullUkVarchar_okStringKey() {
        pkResult = queryResult(MY_PK_COLS, new String[0][]);
        ukResult = queryResult(MY_UK_COLS, new String[][] { { "uk_code", "code", "varchar", null, "NO" } });

        DmlBatchKeyProbe probe = service.probeForKey(UID, myDs, myConfig, DB, null, TABLE);

        assertEquals(DmlBatchKeyProbe.STATE_OK, probe.getKeyState());
        assertEquals("code", probe.getKeyColumn());
        assertEquals(DmlBatchRangeSpec.KEY_TYPE_STRING, probe.getKeyType());
    }

    @Test
    public void probeForKey_mysql_ukSelectionSkipsNullableAndComposite_takesFirstNotNullSingle() {
        pkResult = queryResult(MY_PK_COLS, new String[0][]);
        // index order: uk_a composite, uk_b nullable single, uk_c NOT NULL single decimal(10,0)
        ukResult = queryResult(MY_UK_COLS, new String[][] {
            { "uk_a", "c1", "bigint", "0", "NO" },
            { "uk_a", "c2", "bigint", "0", "NO" },
            { "uk_b", "email", "varchar", null, "YES" },
            { "uk_c", "amount", "decimal", "0", "NO" },
        });

        DmlBatchKeyProbe probe = service.probeForKey(UID, myDs, myConfig, DB, null, TABLE);

        assertEquals(DmlBatchKeyProbe.STATE_OK, probe.getKeyState());
        assertEquals("amount", probe.getKeyColumn());
        assertEquals(DmlBatchRangeSpec.KEY_TYPE_DECIMAL0, probe.getKeyType());
    }

    @Test
    public void probeForKey_onlyNullableSingleUk_noKeyState_handoverObligation1() {
        // N1a handover obligation #1: a nullable UK counts as NO KEY (NULL rows would silently
        // never enter any batch) → the marking seam's DML_NO_KEY rejection path
        pkResult = queryResult(MY_PK_COLS, new String[0][]);
        ukResult = queryResult(MY_UK_COLS, new String[][] { { "uk_email", "email", "varchar", null, "YES" } });

        DmlBatchKeyProbe probe = service.probeForKey(UID, myDs, myConfig, DB, null, TABLE);

        assertEquals(DmlBatchKeyProbe.STATE_NO_KEY, probe.getKeyState());
        assertTrue(probe.getKeyDetail().contains("nullable"));
        assertTrue(probe.getKeyDetail().contains("email"));
    }

    @Test
    public void probeForKey_pg_onlyNullableSingleUk_attnotnullFalse_noKey() {
        rowsResult = queryResult(PG_ROW_COLS, new String[][] { { "5000000" } });
        pkResult = queryResult(PG_PK_COLS, new String[0][]);
        ukResult = queryResult(PG_UK_COLS, new String[][] { { "uk_email", "email", "character varying(255)", "f", "1" } });

        DmlBatchKeyProbe probe = service.probeForKey(UID, pgDs, pgConfig, DB, null, TABLE);

        assertEquals(DmlBatchKeyProbe.STATE_NO_KEY, probe.getKeyState());
    }

    @Test
    public void probeForKey_noPk_compositeUk_unsupported_notNoKey() {
        // a unique constraint DOES exist (composite) — factually not "no key": yellow, not FAIL
        pkResult = queryResult(MY_PK_COLS, new String[0][]);
        ukResult = queryResult(MY_UK_COLS, new String[][] {
            { "uk_tenant", "tenant_id", "bigint", "0", "NO" },
            { "uk_tenant", "code", "varchar", null, "NO" },
        });

        DmlBatchKeyProbe probe = service.probeForKey(UID, myDs, myConfig, DB, null, TABLE);

        assertEquals(DmlBatchKeyProbe.STATE_UNSUPPORTED, probe.getKeyState());
        assertTrue(probe.getKeyDetail().contains("composite unique key"));
        assertTrue(probe.getKeyDetail().contains("tenant_id,code"));
    }

    @Test
    public void probeForKey_noKeyAtAll_noKeyState() {
        pkResult = queryResult(MY_PK_COLS, new String[0][]);
        ukResult = queryResult(MY_UK_COLS, new String[0][]);

        DmlBatchKeyProbe probe = service.probeForKey(UID, myDs, myConfig, DB, null, TABLE);

        assertTrue(probe.isProbed());
        assertEquals(DmlBatchKeyProbe.STATE_NO_KEY, probe.getKeyState());
        assertEquals("no primary key and no unique key", probe.getKeyDetail());
    }

    @Test
    public void probeForKey_compositePk_unsupportedWithColumnList() {
        pkResult = queryResult(MY_PK_COLS, new String[][] {
            { "id", "bigint", "0", "NO" },
            { "tenant_id", "bigint", "0", "NO" },
        });

        DmlBatchKeyProbe probe = service.probeForKey(UID, myDs, myConfig, DB, null, TABLE);

        assertEquals(DmlBatchKeyProbe.STATE_UNSUPPORTED, probe.getKeyState());
        assertEquals("composite primary key (id,tenant_id)", probe.getKeyDetail());
    }

    @Test
    public void probeForKey_floatPk_unsupportedKeyType() {
        pkResult = queryResult(MY_PK_COLS, new String[][] { { "score", "double", null, "NO" } });

        DmlBatchKeyProbe probe = service.probeForKey(UID, myDs, myConfig, DB, null, TABLE);

        assertEquals(DmlBatchKeyProbe.STATE_UNSUPPORTED, probe.getKeyState());
        assertEquals("primary key type double", probe.getKeyDetail());
    }

    @Test
    public void probeForKey_decimalWithScale_unsupported() {
        pkResult = queryResult(MY_PK_COLS, new String[][] { { "amount", "decimal", "2", "NO" } });

        DmlBatchKeyProbe probe = service.probeForKey(UID, myDs, myConfig, DB, null, TABLE);

        assertEquals(DmlBatchKeyProbe.STATE_UNSUPPORTED, probe.getKeyState());
    }

    @Test
    public void probeForKey_mysql_datetimeAndTimestampPk_datetimeType() {
        pkResult = queryResult(MY_PK_COLS, new String[][] { { "created", "datetime", null, "NO" } });
        assertEquals(DmlBatchRangeSpec.KEY_TYPE_DATETIME,
            service.probeForKey(UID, myDs, myConfig, DB, null, TABLE).getKeyType());

        pkResult = queryResult(MY_PK_COLS, new String[][] { { "ts", "timestamp", "0", "NO" } });
        assertEquals(DmlBatchRangeSpec.KEY_TYPE_DATETIME,
            service.probeForKey(UID, myDs, myConfig, DB, null, TABLE).getKeyType());
    }

    @Test
    public void probeForKey_pg_typeMapping_matrix() {
        rowsResult = queryResult(PG_ROW_COLS, new String[][] { { "100" } });
        pkResult = queryResult(PG_PK_COLS, new String[][] { { "id", "bigint", "t" } });
        assertEquals(DmlBatchRangeSpec.KEY_TYPE_INT,
            service.probeForKey(UID, pgDs, pgConfig, DB, null, TABLE).getKeyType());

        pkResult = queryResult(PG_PK_COLS, new String[][] { { "amount", "numeric(18,0)", "t" } });
        assertEquals(DmlBatchRangeSpec.KEY_TYPE_DECIMAL0,
            service.probeForKey(UID, pgDs, pgConfig, DB, null, TABLE).getKeyType());

        pkResult = queryResult(PG_PK_COLS, new String[][] { { "amount", "numeric(18,2)", "t" } });
        assertEquals(DmlBatchKeyProbe.STATE_UNSUPPORTED,
            service.probeForKey(UID, pgDs, pgConfig, DB, null, TABLE).getKeyState());

        pkResult = queryResult(PG_PK_COLS, new String[][] { { "ts", "timestamp with time zone", "t" } });
        assertEquals(DmlBatchRangeSpec.KEY_TYPE_DATETIME,
            service.probeForKey(UID, pgDs, pgConfig, DB, null, TABLE).getKeyType());

        pkResult = queryResult(PG_PK_COLS, new String[][] { { "code", "character varying(64)", "t" } });
        assertEquals(DmlBatchRangeSpec.KEY_TYPE_STRING,
            service.probeForKey(UID, pgDs, pgConfig, DB, null, TABLE).getKeyType());

        // PG text is OUT of the v1 whitelist (whitelist = CHAR/VARCHAR — conservative)
        pkResult = queryResult(PG_PK_COLS, new String[][] { { "code", "text", "t" } });
        assertEquals(DmlBatchKeyProbe.STATE_UNSUPPORTED,
            service.probeForKey(UID, pgDs, pgConfig, DB, null, TABLE).getKeyState());
    }

    @Test
    public void probeForKey_exoticKeyColumn_notPlainIdentifier_unsupported() {
        pkResult = queryResult(MY_PK_COLS, new String[][] { { "my key", "bigint", "0", "NO" } });

        DmlBatchKeyProbe probe = service.probeForKey(UID, myDs, myConfig, DB, null, TABLE);

        assertEquals(DmlBatchKeyProbe.STATE_UNSUPPORTED, probe.getKeyState());
        assertTrue(probe.getKeyDetail().contains("not a plain identifier"));
    }

    // ==================================================================================
    // degradation (never-throw, the probeForRoute discipline)
    // ==================================================================================

    @Test
    public void probeForKey_tableAbsent_notProbed_silentStatusQuo() {
        rowsResult = queryResult(MY_ROW_COLS, new String[0][]);

        DmlBatchKeyProbe probe = service.probeForKey(UID, myDs, myConfig, DB, null, TABLE);

        assertFalse(probe.isProbed());
        assertFalse(probe.isTableFound());
        assertEquals(1, executedSqls.size()); // the key queries are skipped once the table is absent
    }

    @Test
    public void probeForKey_queryError_degradesToNotProbed_neverThrows() {
        when(queryService.syncExecuteQuery(anyString(), anyString(), any(QueryRequest.class)))
            .thenAnswer(inv -> {
                executedSqls.add(inv.getArgument(2, QueryRequest.class).getQueryBody());
                ResultMessage error = new ResultMessage();
                error.setSuccess(false);
                error.setMessage("ERROR 1146 table gone");
                error.setResultType(ResultType.Message);
                ResultList list = new ResultList();
                list.setResultList(List.of(error));
                return list;
            });

        DmlBatchKeyProbe probe = service.probeForKey(UID, myDs, myConfig, DB, null, TABLE);

        assertFalse(probe.isProbed());
    }

    @Test
    public void probeForKey_transportFailure_degradesToNotProbed_neverThrows() {
        when(queryService.syncExecuteQuery(anyString(), anyString(), any(QueryRequest.class)))
            .thenThrow(new RuntimeException("worker unreachable"));

        DmlBatchKeyProbe probe = service.probeForKey(UID, myDs, myConfig, DB, null, TABLE);

        assertFalse(probe.isProbed());
    }

    @Test
    public void probeForKey_badIdentifier_degradesWithZeroRemoteCalls() {
        DmlBatchKeyProbe probe = service.probeForKey(UID, myDs, myConfig, DB, null, "bad\u0000name");

        assertFalse(probe.isProbed());
        assertTrue(executedSqls.isEmpty());
    }

    @Test
    public void probeForKey_blankDbName_degradesWithZeroRemoteCalls() {
        DmlBatchKeyProbe probe = service.probeForKey(UID, myDs, myConfig, " ", null, TABLE);

        assertFalse(probe.isProbed());
        assertTrue(executedSqls.isEmpty());
    }

    // ==================================================================================
    // version + dialect gate (裁决 #3 — console-side, obligation #2)
    // ==================================================================================

    @Test
    public void isBatchRouteSupportedDs_matrix() {
        assertTrue(service.isBatchRouteSupportedDs(ds(DataSourceType.MySQL, "8.0")));
        assertTrue(service.isBatchRouteSupportedDs(ds(DataSourceType.MySQL, "8.0.32")));
        assertTrue(service.isBatchRouteSupportedDs(ds(DataSourceType.MySQL, "8.4")));
        assertTrue(service.isBatchRouteSupportedDs(ds(DataSourceType.PostgreSQL, "15")));
        assertTrue(service.isBatchRouteSupportedDs(ds(DataSourceType.PostgreSQL, "15.4")));
        assertTrue(service.isBatchRouteSupportedDs(ds(DataSourceType.PostgreSQL, "16.2")));

        assertTrue(service.isBatchRouteSupportedDs(ds(DataSourceType.MySQL, "8"))); // major-only is the gate granularity

        assertFalse(service.isBatchRouteSupportedDs(ds(DataSourceType.MySQL, "5.7")));
        assertFalse(service.isBatchRouteSupportedDs(ds(DataSourceType.MySQL, "5.7.44-log")));
        assertFalse(service.isBatchRouteSupportedDs(ds(DataSourceType.PostgreSQL, "14.9")));
        assertFalse(service.isBatchRouteSupportedDs(ds(DataSourceType.PostgreSQL, "9.6.24")));
        // blank / unparsable version → conservative status quo (never routes)
        assertFalse(service.isBatchRouteSupportedDs(ds(DataSourceType.MySQL, null)));
        assertFalse(service.isBatchRouteSupportedDs(ds(DataSourceType.MySQL, "")));
        assertFalse(service.isBatchRouteSupportedDs(ds(DataSourceType.MySQL, "unknown")));
        // other dialects (including the MySQL-wire variants) never route
        assertFalse(service.isBatchRouteSupportedDs(ds(DataSourceType.Oracle, "19.0")));
        assertFalse(service.isBatchRouteSupportedDs(ds(DataSourceType.SQLServer, "2019")));
        assertFalse(service.isBatchRouteSupportedDs(ds(null, "8.0")));
        assertFalse(service.isBatchRouteSupportedDs(null));
    }

    @Test
    public void parseMajorVersion_toleratesPrefixesAndSingleNumbers() {
        assertEquals(Integer.valueOf(15), DmlBatchServiceImpl.parseMajorVersion("PostgreSQL 15.4 on x86_64"));
        assertEquals(Integer.valueOf(8), DmlBatchServiceImpl.parseMajorVersion("8.0.32-0ubuntu"));
        assertEquals(Integer.valueOf(15), DmlBatchServiceImpl.parseMajorVersion("15"));
        assertEquals(Integer.valueOf(5), DmlBatchServiceImpl.parseMajorVersion("5.7.44-log"));
        assertNull(DmlBatchServiceImpl.parseMajorVersion("unknown"));
        assertNull(DmlBatchServiceImpl.parseMajorVersion(""));
        assertNull(DmlBatchServiceImpl.parseMajorVersion(null));
    }

    // ==================================================================================
    // six-KV parameter plane (fail-safe + defensive clamping)
    // ==================================================================================

    @Test
    public void resolveEffectiveParams_defaultsOnNullReads() {
        DmlBatchEffectiveParams params = service.resolveEffectiveParams();

        assertEquals(5000, params.getBatchSize());
        assertEquals(1000L, params.getIntervalMs());
        assertEquals(10, params.getLagThresholdSec());
        assertEquals(300, params.getTimeoutSec());
        assertEquals(3, params.getMaxBatchRetries()); // compile-time constant, not a KV
    }

    @Test
    public void resolveEffectiveParams_configuredValuesUsed() {
        when(systemDal.fetchSystemConf(eq(RootUserConfig.Fields.dmlBatchSize), eq(Integer.class), any())).thenReturn(2000);
        when(systemDal.fetchSystemConf(eq(RootUserConfig.Fields.dmlBatchIntervalMs), eq(Integer.class), any())).thenReturn(250);
        when(systemDal.fetchSystemConf(eq(RootUserConfig.Fields.dmlLagThresholdSec), eq(Integer.class), any())).thenReturn(30);
        when(systemDal.fetchSystemConf(eq(RootUserConfig.Fields.dmlBatchTimeoutSec), eq(Integer.class), any())).thenReturn(60);

        DmlBatchEffectiveParams params = service.resolveEffectiveParams();

        assertEquals(2000, params.getBatchSize());
        assertEquals(250L, params.getIntervalMs());
        assertEquals(30, params.getLagThresholdSec());
        assertEquals(60, params.getTimeoutSec());
    }

    @Test
    public void resolveEffectiveParams_outOfRangeValuesClampedIntoWhiteListRanges() {
        // a corrupt KV row can never produce an out-of-contract batch shape (R11 floor)
        when(systemDal.fetchSystemConf(eq(RootUserConfig.Fields.dmlBatchSize), eq(Integer.class), any())).thenReturn(5);
        when(systemDal.fetchSystemConf(eq(RootUserConfig.Fields.dmlBatchIntervalMs), eq(Integer.class), any())).thenReturn(-100);
        when(systemDal.fetchSystemConf(eq(RootUserConfig.Fields.dmlLagThresholdSec), eq(Integer.class), any())).thenReturn(99999);
        when(systemDal.fetchSystemConf(eq(RootUserConfig.Fields.dmlBatchTimeoutSec), eq(Integer.class), any())).thenReturn(1);

        DmlBatchEffectiveParams params = service.resolveEffectiveParams();

        assertEquals(1000, params.getBatchSize());
        assertEquals(0L, params.getIntervalMs());
        assertEquals(3600, params.getLagThresholdSec());
        assertEquals(10, params.getTimeoutSec());
    }

    @Test
    public void resolveEffectiveParams_kvReadThrows_defaultsApplied_failSafe() {
        when(systemDal.fetchSystemConf(anyString(), eq(Integer.class), any())).thenThrow(new RuntimeException("kv down"));

        DmlBatchEffectiveParams params = service.resolveEffectiveParams();

        assertEquals(5000, params.getBatchSize());
        assertEquals(300, params.getTimeoutSec());
    }

    @Test
    public void killSwitchAndThreshold_failSafeReads() {
        assertTrue(service.isAutoRouteEnabled()); // null read → default true
        assertTrue(service.rowThreshold() == 1_000_000L);

        when(systemDal.fetchSystemConf(eq(RootUserConfig.Fields.dmlBatchAutoRoute), eq(Boolean.class))).thenReturn(false);
        assertFalse(service.isAutoRouteEnabled());

        when(systemDal.fetchSystemConf(eq(RootUserConfig.Fields.dmlBatchAutoRoute), eq(Boolean.class)))
            .thenThrow(new RuntimeException("kv down"));
        assertTrue(service.isAutoRouteEnabled()); // exception → fail-safe true (ghostAutoRoute pattern)

        when(systemDal.fetchSystemConf(eq(RootUserConfig.Fields.dmlRowThreshold), eq(Long.class), any())).thenReturn(500L);
        assertEquals(500L, service.rowThreshold());
        when(systemDal.fetchSystemConf(eq(RootUserConfig.Fields.dmlRowThreshold), eq(Long.class), any())).thenReturn(0L);
        assertEquals(1_000_000L, service.rowThreshold()); // < 1 → default
    }

    // ==================================================================================
    // checkpoint seeding (retryGroup inheritance — done side only)
    // ==================================================================================

    @Test
    public void seedCheckpoint_nullOrEmpty_freshCursor() {
        DmlBatchCheckpoint fresh = DmlBatchServiceImpl.seedCheckpoint(null);
        assertNull(fresh.getCursor());
        assertEquals(0L, fresh.getBatchesDone());
        assertNull(fresh.getIntent());

        assertNotNull(DmlBatchServiceImpl.seedCheckpoint(new HashMap<>()));
    }

    @Test
    public void seedCheckpoint_doneSideInherited_intentAndHeartbeatStripped() {
        Map<String, Object> inherited = new HashMap<>();
        inherited.put("cursor", "42");
        inherited.put("batchesDone", 7L);
        inherited.put("rowsDone", 35000L);
        Map<String, Object> intent = new HashMap<>();
        intent.put("seq", 8L);
        inherited.put("intent", intent);
        inherited.put("lastTickAt", 123456L);
        inherited.put("lastStatus", "UNRESOLVED");

        DmlBatchCheckpoint cp = DmlBatchServiceImpl.seedCheckpoint(inherited);

        assertEquals("42", cp.getCursor());       // 已成功批永不重放 — the cursor IS the guarantee
        assertEquals(7L, cp.getBatchesDone());
        assertEquals(35000L, cp.getRowsDone());
        assertNull(cp.getIntent());               // the intent side is NEVER inherited
        assertNull(cp.getLastTickAt());           // the heartbeat belongs to the new task
        assertNull(cp.getLastStatus());
    }

    @Test
    public void seedCheckpoint_unparsableInheritance_throws_refusesCursorRestart() {
        // silently starting from a null cursor would REPLAY every committed batch — refuse loudly
        Map<String, Object> corrupt = new HashMap<>();
        corrupt.put("batchesDone", "not-a-number");

        try {
            DmlBatchServiceImpl.seedCheckpoint(corrupt);
            fail("expected IllegalStateException on an unparsable inherited checkpoint");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("Unparsable inherited DML_BATCH checkpoint"));
        }
    }

    // ==================================================================================
    // dispatch-time task submission
    // ==================================================================================

    @Test
    public void submitForTicket_assemblesConfigData_andSubmitsProbeTaskShape() {
        when(systemDal.fetchSystemConf(eq(RootUserConfig.Fields.dmlBatchSize), eq(Integer.class), any())).thenReturn(2500);
        DmExecAsyncTaskDO stored = new DmExecAsyncTaskDO();
        stored.setId(77L);
        when(asyncTaskServiceService.queryAsyncTaskByBizId(anyString(), eq(DmlBatchService.BIZ_TYPE)))
            .thenReturn(stored);

        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(900L);
        ticket.setOwnerUid(UID);
        DmTicketDbStmtDO group = new DmTicketDbStmtDO();
        group.setId(100L);
        group.setDsId(DS_ID);
        group.setDbName(DB);
        TicketSegment segment = TicketSegment.dmlBatchPlan(2, 3, TABLE, 5_000_000L,
            "UPDATE t_order SET status = 1 WHERE status = 0",
            new TicketSegment.BatchKey("id", DmlBatchRangeSpec.KEY_TYPE_INT), false);

        Long taskId = service.submitForTicket(ticket, group, segment, null);

        assertEquals(Long.valueOf(77L), taskId);
        ArgumentCaptor<com.clougence.clouddm.console.web.component.execute.asyntask.AsyncTaskConfig> captor
            = ArgumentCaptor.forClass(com.clougence.clouddm.console.web.component.execute.asyntask.AsyncTaskConfig.class);
        verify(asyncTaskServiceService).submitTask(eq(UID), captor.capture()); // owner = the submitter (R19)
        com.clougence.clouddm.console.web.component.execute.asyntask.AsyncTaskConfig config = captor.getValue();
        assertEquals(DmlBatchService.BIZ_TYPE, config.getBizType());
        assertEquals(DmlBatchAsyncTask.class, config.getHandlerType());
        assertEquals(AsyncTaskProcessType.PROGRESS, config.getProcessType());
        assertTrue(config.isShowInDock());
        assertNotNull(config.getBizId());

        DmlBatchAsyncTaskConfig taskConfig = JsonUtils.toObj(config.getConfigData(), DmlBatchAsyncTaskConfig.class);
        assertEquals(Long.valueOf(900L), taskConfig.getTicketId());
        assertEquals(Long.valueOf(100L), taskConfig.getGroupId());
        assertEquals(Integer.valueOf(2), taskConfig.getSegmentIndex());
        assertEquals(UID, taskConfig.getUserId());
        assertEquals(Long.valueOf(DS_ID), taskConfig.getDsId());
        assertEquals(DB, taskConfig.getDbName());
        assertEquals("UPDATE t_order SET status = 1 WHERE status = 0", taskConfig.getStmtSql()); // frozen text
        assertEquals("id", taskConfig.getBatchKey().getColumn());
        assertEquals(DmlBatchRangeSpec.KEY_TYPE_INT, taskConfig.getBatchKey().getKeyType());
        assertEquals(2500, taskConfig.getEffectiveParams().getBatchSize()); // dispatch-time KV snapshot
        assertEquals(Long.valueOf(5_000_000L), taskConfig.getEstimatedRows());
        assertEquals(Boolean.FALSE, taskConfig.getIdempotent());
        assertNotNull(taskConfig.getCheckpoint());
        assertNull(taskConfig.getCheckpoint().getCursor()); // fresh — first dispatch
        assertEquals(config.getBizId(), taskConfig.getBizId());
    }

    @Test
    public void submitForTicket_inheritedCheckpoint_seedsDoneSide() {
        DmExecAsyncTaskDO stored = new DmExecAsyncTaskDO();
        stored.setId(78L);
        when(asyncTaskServiceService.queryAsyncTaskByBizId(anyString(), anyString())).thenReturn(stored);

        Map<String, Object> inherited = new HashMap<>();
        inherited.put("cursor", "99");
        inherited.put("batchesDone", 4L);
        TicketSegment segment = TicketSegment.dmlBatchPlan(1, 1, TABLE, 1000L,
            "DELETE FROM t_order WHERE status = 0", new TicketSegment.BatchKey("id", "INT"), true);

        service.submitForTicket(ticket(), group(), segment, inherited);

        ArgumentCaptor<com.clougence.clouddm.console.web.component.execute.asyntask.AsyncTaskConfig> captor
            = ArgumentCaptor.forClass(com.clougence.clouddm.console.web.component.execute.asyntask.AsyncTaskConfig.class);
        verify(asyncTaskServiceService).submitTask(anyString(), captor.capture());
        DmlBatchAsyncTaskConfig taskConfig = JsonUtils.toObj(captor.getValue().getConfigData(), DmlBatchAsyncTaskConfig.class);
        assertEquals("99", taskConfig.getCheckpoint().getCursor()); // cursor inheritance = no replay
        assertEquals(4L, taskConfig.getCheckpoint().getBatchesDone());
        assertNull(taskConfig.getCheckpoint().getIntent());
    }

    @Test
    public void submitForTicket_submitTaskFails_throws_noResidue() {
        org.mockito.Mockito.doThrow(new IllegalStateException("task store down"))
            .when(asyncTaskServiceService).submitTask(anyString(), any());
        TicketSegment segment = TicketSegment.dmlBatchPlan(1, 1, TABLE, 1000L,
            "DELETE FROM t_order WHERE status = 0", new TicketSegment.BatchKey("id", "INT"), true);

        try {
            service.submitForTicket(ticket(), group(), segment, null);
            fail("expected the submitTask failure to propagate");
        } catch (IllegalStateException expected) {
            assertEquals("task store down", expected.getMessage());
        }
        // no session exists in the B7 shape — nothing to clean up; the caller's rollback is the whole story
    }

    @Test
    public void submitForTicket_segmentWithoutFrozenKey_refusesLoudly() {
        TicketSegment broken = TicketSegment.dmlBatchPlan(1, 1, TABLE, 1000L,
            "DELETE FROM t_order WHERE status = 0", null, true);

        try {
            service.submitForTicket(ticket(), group(), broken, null);
            fail("expected IllegalStateException on a segment without a frozen batchKey");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("no frozen batchKey/sql"));
        }
        verify(asyncTaskServiceService, never()).submitTask(anyString(), any());
    }

    private static DmApprovalDO ticket() {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(900L);
        ticket.setOwnerUid(UID);
        return ticket;
    }

    private static DmTicketDbStmtDO group() {
        DmTicketDbStmtDO group = new DmTicketDbStmtDO();
        group.setId(100L);
        group.setDsId(DS_ID);
        group.setDbName(DB);
        return group;
    }

    // ==================================================================================
    // C6 binding lookup (the ghost findTaskForTicketGroup mirror, bizType parameterized)
    // ==================================================================================

    @Test
    public void findTaskForTicketGroup_exactBindingWins_substringOverInclusionHarmless() {
        DmlBatchAsyncTaskConfig unrelated = taskConfig(); // bound to ticket 90 (substring of 900)
        unrelated.setTicketId(90L);
        unrelated.setGroupId(666L);
        unrelated.setSegmentIndex(1);
        DmlBatchAsyncTaskConfig bound = taskConfig();
        bound.setTicketId(900L);
        bound.setGroupId(666L);
        bound.setSegmentIndex(1);

        DmExecAsyncTaskDO noise = taskRow(11L, AsyncTaskStatus.COMPLETE, unrelated);
        DmExecAsyncTaskDO match = taskRow(12L, AsyncTaskStatus.RUNNING, bound);
        when(asyncTaskMapper.selectList(any())).thenReturn(List.of(match, noise)); // newest-first

        DmExecAsyncTaskDO found = service.findTaskForTicketGroup(900L, 666L, 1);

        assertNotNull(found);
        assertEquals(Long.valueOf(12L), found.getId());
    }

    @Test
    public void findTaskForTicketGroup_segmentMismatch_skipped() {
        DmlBatchAsyncTaskConfig seg2 = taskConfig();
        seg2.setTicketId(900L);
        seg2.setGroupId(666L);
        seg2.setSegmentIndex(2);
        when(asyncTaskMapper.selectList(any())).thenReturn(List.of(taskRow(12L, AsyncTaskStatus.RUNNING, seg2)));

        assertNull(service.findTaskForTicketGroup(900L, 666L, 1));
        assertNotNull(service.findTaskForTicketGroup(900L, 666L, 2));
        // a null segmentIndex accepts any segment of the group (the M1-shape tolerance)
        assertNotNull(service.findTaskForTicketGroup(900L, 666L, null));
    }

    @Test
    public void findTaskForTicketGroup_unparsableConfigData_skipped_neverThrows() {
        DmExecAsyncTaskDO corrupt = new DmExecAsyncTaskDO();
        corrupt.setId(13L);
        corrupt.setConfigData("{not json");
        when(asyncTaskMapper.selectList(any())).thenReturn(List.of(corrupt));

        assertNull(service.findTaskForTicketGroup(900L, 666L, null));
    }

    private static DmlBatchAsyncTaskConfig taskConfig() {
        DmlBatchAsyncTaskConfig cfg = new DmlBatchAsyncTaskConfig();
        cfg.setBizId("biz-" + System.nanoTime());
        cfg.setUserId(UID);
        return cfg;
    }

    private static DmExecAsyncTaskDO taskRow(long id, AsyncTaskStatus status, DmlBatchAsyncTaskConfig cfg) {
        DmExecAsyncTaskDO row = new DmExecAsyncTaskDO();
        row.setId(id);
        row.setStatus(status);
        row.setBizType(DmlBatchService.BIZ_TYPE);
        row.setConfigData(JsonUtils.toJson(cfg));
        return row;
    }

    // ==================================================================================
    // ResultList fixtures (the GhostServiceImplTest helpers)
    // ==================================================================================

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
}
