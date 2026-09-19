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
package com.clougence.clouddm.console.web.service.lockanalysis;

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
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.api.sidecar.session.execute.ResultList;
import com.clougence.clouddm.base.metadata.ds.DataSourceConfig;
import com.clougence.clouddm.base.metadata.ds.DataSourceType;
import com.clougence.clouddm.console.web.component.dsconfig.DmDsConfigService;
import com.clougence.clouddm.console.web.component.dsconfig.mode.DsLevels;
import com.clougence.clouddm.console.web.component.execute.QueryService;
import com.clougence.clouddm.console.web.component.opsdiag.OpsDiagnosticExecutor;
import com.clougence.clouddm.console.web.model.fo.lockanalysis.LockOverviewFO;
import com.clougence.clouddm.console.web.model.vo.lockanalysis.LockOverviewVO;
import com.clougence.clouddm.console.web.model.vo.lockanalysis.LockTrxVO;
import com.clougence.clouddm.console.web.model.vo.lockanalysis.LockWaitVO;
import com.clougence.clouddm.console.web.model.vo.session.SessionInstanceVO;
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
 * Unit tests for {@link LockAnalysisServiceImpl} (design 09-17-lock-deadlock-analysis §9, eight
 * assertion groups): dialect dispatch SQL evidence, version defense (5.7 / MariaDB / garbage all
 * refused, no fallback), dual-dialect row mapping, deadlock-section extraction shapes, the
 * performance_schema=OFF degradation, privilege-error translation vs block-level degradation,
 * timeout / whitelist / tenant rejections, and the displayChars=131072 deadlock passthrough.
 * <p>
 * Discipline (copied from B2 SessionManageServiceImplTest): JUnit4 + hand-written mocks +
 * ReflectionTestUtils only; the PluginManager static lookups are isolated behind the protected
 * seams of {@link OpsDiagnosticExecutor} overridden by an anonymous subclass (no mockStatic, no
 * Spring context, no MockMvc — the B4 Step-0 extraction moved the seams from the service to the
 * executor; all assertions below are unchanged). Every failure path verifies the finally-release
 * ({@code closeSession}); rejection paths verify zero remote calls; unrecognized SQL fails the
 * test on the spot.
 */
public class LockAnalysisServiceImplTest {

    private static final String   UID          = "uid-operator";
    private static final String   PUID         = "puid-tenant";
    private static final long     DS_ID        = 9L;
    private static final String   DIAG_SESSION = "diag-session";
    private static final String   DS_USER      = "clouddm_agent";

    /** Column labels of the MySQL lock-wait projection; deliberately mixed-case in fixtures (B2 precedent). */
    private static final String[] MY_LOCK_WAIT_COLS = { "waiting_pid", "waiting_query", "wait_seconds", "lock_table", "lock_index", //
            "lock_type", "lock_mode", "lock_data", "blocking_pid", "blocking_trx_id", "blocking_query", //
            "blocking_trx_started", "blocking_rows_locked" };
    private static final String[] MY_TRX_COLS       = { "trx_id", "trx_state", "trx_started", "duration_seconds", //
            "trx_rows_locked", "trx_rows_modified", "thread_pid", "query" };
    private static final String[] PG_TRX_COLS       = { "trx_id", "state", "started", "duration_seconds", //
            "rows_locked", "rows_modified", "thread_pid", "query" };
    private static final String[] STATUS_COLS       = { "Type", "Name", "Status" };

    private LockAnalysisServiceImpl service;
    private DataSourceDal           dsDal;
    private DmDsMapper              dsMapper;
    private DmDsConfigService       dmDsConfigService;
    private QueryService            queryService;

    /** SQL bodies actually dispatched to the (mocked) worker, in order. */
    private final List<String>      executedSqls    = Collections.synchronizedList(new ArrayList<>());
    /** Full requests dispatched, for displayChars assertions (design §9 group 8). */
    private final List<QueryRequest> executedRequests = Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong        queryIdSeq      = new AtomicLong();

    /** SQL body fragment whose execution must hang (sleep 5s) — for the timeout group. */
    private String                  hangSqlFragment;

    /** Per-test configurable results of each dialect query. */
    private ResultList              versionResult;
    private ResultList              psResult;
    private ResultList              myLockWaitResult;
    private ResultList              myTrxResult;
    private ResultList              innodbStatusResult;
    private ResultList              pgLockWaitResult;
    private ResultList              pgTrxResult;

    private final SessionSpi        fakeSpi         = new SessionSpi() {

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
        service = new LockAnalysisServiceImpl();

        // B4 Step-0 extraction: the channel-C seams (PluginManager statics) now live on the shared
        // executor — wiring only, every assertion below is untouched
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

        // default fixtures: owned MySQL 8.0 datasource + its config, everything healthy and empty
        when(dsMapper.listByOwnerAndIds(eq(PUID), any())).thenReturn(List.of(ds(DataSourceType.MySQL, LifeCycleState.CREATED)));
        when(dmDsConfigService.fetchDsConfigFromExists(DS_ID)).thenReturn(dsConfig(DataSourceType.MySQL, DS_USER));
        when(queryService.createSession(anyString(), any(), any())).thenReturn(DIAG_SESSION);
        versionResult = queryResult(new String[] { "VERSION()" }, new String[][] { { "8.0.32" } });
        psResult = queryResult(new String[] { "ps_enabled" }, new String[][] { { "1" } });
        myLockWaitResult = queryResult(MY_LOCK_WAIT_COLS, new String[0][]);
        myTrxResult = queryResult(MY_TRX_COLS, new String[0][]);
        innodbStatusResult = statusResult("=====================================\n2026-09-19 INNODB MONITOR OUTPUT\n=====================================\n" //
                + "------------\nTRANSACTIONS\n------------\nTrx id counter 100\n");
        pgLockWaitResult = queryResult(MY_LOCK_WAIT_COLS, new String[0][]);
        pgTrxResult = queryResult(PG_TRX_COLS, new String[0][]);

        when(queryService.syncExecuteQuery(anyString(), anyString(), any(QueryRequest.class))).thenAnswer(invocation -> {
            QueryRequest request = invocation.getArgument(2);
            String body = request.getQueryBody();
            executedSqls.add(body);
            executedRequests.add(request);
            if (hangSqlFragment != null && body.contains(hangSqlFragment)) {
                Thread.sleep(5_000L);
            }
            if (body.startsWith("SELECT VERSION")) {
                return versionResult;
            }
            if (body.contains("@@performance_schema")) {
                return psResult;
            }
            if (body.contains("pg_blocking_pids")) {
                return pgLockWaitResult;
            }
            if (body.contains("performance_schema.data_lock_waits")) {
                return myLockWaitResult;
            }
            if (body.startsWith("SHOW ENGINE INNODB STATUS")) {
                return innodbStatusResult;
            }
            if (body.contains("information_schema.innodb_trx")) {
                return myTrxResult;
            }
            if (body.contains("pg_stat_activity")) {
                return pgTrxResult;
            }
            throw new IllegalStateException("unexpected sql dispatched: " + body);
        });
    }

    // ------------------------------------------------------------- group 1: dialect dispatch

    @Test
    public void overview_mysql_dispatchesProbesThenBlocksInOrder() {
        LockOverviewVO vo = service.overview(PUID, UID, overviewFO());

        assertEquals(5, executedSqls.size());
        assertEquals(LockAnalysisServiceImpl.SQL_VERSION, executedSqls.get(0));
        assertEquals(LockAnalysisServiceImpl.SQL_PS_ENABLED, executedSqls.get(1));
        assertEquals(LockAnalysisServiceImpl.SQL_MY_LOCK_WAITS, executedSqls.get(2));
        assertEquals(LockAnalysisServiceImpl.SQL_MY_TRX, executedSqls.get(3));
        assertEquals(LockAnalysisServiceImpl.SQL_INNODB_STATUS, executedSqls.get(4));
        assertEquals("MYSQL_8", vo.getBranch());
        assertEquals("8.0.32", vo.getVersion());
        assertTrue(vo.isDeadlockSupported());
        assertTrue("healthy instance has no partial errors", vo.getPartialErrors().isEmpty());
        assertNotNull(vo.getLockWaits());
        assertNotNull(vo.getTransactions());
        verify(queryService).closeSession(UID, DIAG_SESSION);
    }

    @Test
    public void overview_pg_dispatchesOnlyTwoPgSqls_noVersionNoInnodbStatus() {
        usePostgresDs();

        LockOverviewVO vo = service.overview(PUID, UID, overviewFO());

        assertEquals(2, executedSqls.size());
        assertEquals(LockAnalysisServiceImpl.SQL_PG_LOCK_WAITS, executedSqls.get(0));
        assertEquals(LockAnalysisServiceImpl.SQL_PG_TRX, executedSqls.get(1));
        assertEquals("POSTGRES", vo.getBranch());
        assertNull("PG never probes versions", vo.getVersion());
        assertFalse("PG has no deadlock query — section must be hidden, not fake-empty", vo.isDeadlockSupported());
        assertNull(vo.getDeadlock());
        assertFalse("not 'queried and absent' but 'unsupported'", vo.isDeadlockAbsent());
        verify(queryService).closeSession(UID, DIAG_SESSION);
    }

    // ------------------------------------------------------------- group 2: version defense

    @Test
    public void overview_version57_refusedWithoutFurtherSql_sessionClosed() {
        versionResult = queryResult(new String[] { "VERSION()" }, new String[][] { { "5.7.44-log" } });
        try {
            service.overview(PUID, UID, overviewFO());
            fail("MySQL 5.7 must be refused (8.0 physically removed the 5.7 lock views)");
        } catch (ErrorMessageException e) {
            // "MySQL 8.0" appears in both the zh and en bundle values — locale-robust assertion
            assertTrue(e.getMessage().contains("MySQL 8.0"));
            assertTrue("version string must be carried in {0}", e.getMessage().contains("5.7.44-log"));
        }
        assertEquals("no block SQL may follow the refused probe", List.of(LockAnalysisServiceImpl.SQL_VERSION), executedSqls);
        verify(queryService).closeSession(UID, DIAG_SESSION);
    }

    @Test
    public void overview_mariaDbVersion_refused() {
        versionResult = queryResult(new String[] { "VERSION()" }, new String[][] { { "10.6.12-MariaDB" } });
        try {
            service.overview(PUID, UID, overviewFO());
            fail("MariaDB must be refused regardless of its major version");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("MySQL 8.0"));
            assertTrue(e.getMessage().contains("10.6.12-MariaDB"));
        }
        verify(queryService).closeSession(UID, DIAG_SESSION);
    }

    @Test
    public void overview_garbageVersion_refusedWithoutFallback() {
        versionResult = queryResult(new String[] { "VERSION()" }, new String[][] { { "garbage" } });
        try {
            service.overview(PUID, UID, overviewFO());
            fail("unparsable version strings must be refused (no 5.7 fallback exists)");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("MySQL 8.0"));
            assertTrue("even the unparsable string must be carried in {0}", e.getMessage().contains("garbage"));
        }
        verify(queryService).closeSession(UID, DIAG_SESSION);
    }

    // ------------------------------------------------------------- group 3: dual-dialect row mapping

    @Test
    public void overview_mysql_mapsLockWaitAndTrxRows_caseInsensitiveColumns() {
        myLockWaitResult = queryResult(//
                new String[] { "WAITING_PID", "waiting_query", "Wait_Seconds", "LOCK_TABLE", "lock_index", "Lock_Type", //
                        "LOCK_MODE", "lock_data", "BLOCKING_PID", "blocking_trx_id", "Blocking_Query", //
                        "blocking_trx_started", "BLOCKING_ROWS_LOCKED" }, //
                new String[][] { { "101", "UPDATE t SET c = 1 WHERE id = 1", "45", "orders.t", "PRIMARY", "RECORD", //
                        "X", "1", "90", "12345", null, "2026-09-19 10:00:00", "3" } });
        myTrxResult = queryResult(//
                new String[] { "TRX_ID", "trx_state", "trx_started", "Duration_Seconds", "TRX_ROWS_LOCKED", //
                        "trx_rows_modified", "THREAD_PID", "query" }, //
                new String[][] { //
                        { "12345", "RUNNING", "2026-09-19 10:00:00", "120", "3", "1", "90", null }, //
                        { "12346", "LOCK WAIT", "2026-09-19 10:00:05", "not-a-number", "0", "0", "101", "UPDATE t SET c = 1 WHERE id = 1" }, //
                });

        LockOverviewVO vo = service.overview(PUID, UID, overviewFO());

        assertEquals(1, vo.getLockWaits().size());
        LockWaitVO wait = vo.getLockWaits().get(0);
        assertEquals("101", wait.getWaitingPid());
        assertEquals("UPDATE t SET c = 1 WHERE id = 1", wait.getWaitingQuery());
        assertEquals(Long.valueOf(45L), wait.getWaitSeconds());
        assertEquals("orders.t", wait.getLockTable());
        assertEquals("PRIMARY", wait.getLockIndex());
        assertEquals("RECORD", wait.getLockType());
        assertEquals("X", wait.getLockMode());
        assertEquals("1", wait.getLockData());
        assertEquals("90", wait.getBlockingPid());
        assertEquals("12345", wait.getBlockingTrxId());
        assertNull("idle transaction holding the lock has no blocking query", wait.getBlockingQuery());
        assertEquals("2026-09-19 10:00:00", wait.getBlockingTrxStarted());
        assertEquals(Long.valueOf(3L), wait.getBlockingRowsLocked());

        assertEquals(2, vo.getTransactions().size());
        LockTrxVO first = vo.getTransactions().get(0);
        assertEquals("12345", first.getTrxId());
        assertEquals("RUNNING", first.getState());
        assertEquals("2026-09-19 10:00:00", first.getStarted());
        assertEquals(Long.valueOf(120L), first.getDurationSeconds());
        assertEquals(Long.valueOf(3L), first.getRowsLocked());
        assertEquals(Long.valueOf(1L), first.getRowsModified());
        assertEquals("90", first.getThreadPid());
        assertNull(first.getQuery());
        assertNull("non-numeric duration degrades to null", vo.getTransactions().get(1).getDurationSeconds());
    }

    @Test
    public void overview_pg_mapsRows_nullColumnsTolerated() {
        usePostgresDs();
        pgLockWaitResult = queryResult(MY_LOCK_WAIT_COLS, new String[][] { //
                { "84", "UPDATE t SET c = 1 WHERE id = 1", "300", "public.t", null, "tuple", "ExclusiveLock", null, //
                        "81", null, "BEGIN", "2026-09-19 10:00:00+08", null } });
        pgTrxResult = queryResult(PG_TRX_COLS, new String[][] { //
                { null, "idle in transaction", "2026-09-19 09:59:00+08", "90", null, null, "81", "UPDATE t SET c = 1 WHERE id = 1" } });

        LockOverviewVO vo = service.overview(PUID, UID, overviewFO());

        assertEquals(1, vo.getLockWaits().size());
        LockWaitVO wait = vo.getLockWaits().get(0);
        assertEquals("84", wait.getWaitingPid());
        assertEquals(Long.valueOf(300L), wait.getWaitSeconds());
        assertEquals("public.t", wait.getLockTable());
        assertNull("PG has no index-level detail here", wait.getLockIndex());
        assertEquals("tuple", wait.getLockType());
        assertEquals("ExclusiveLock", wait.getLockMode());
        assertNull(wait.getLockData());
        assertEquals("81", wait.getBlockingPid());
        assertNull("PG carries no blocking trx id", wait.getBlockingTrxId());
        assertEquals("BEGIN", wait.getBlockingQuery());
        assertEquals("2026-09-19 10:00:00+08", wait.getBlockingTrxStarted());
        assertNull(wait.getBlockingRowsLocked());

        assertEquals(1, vo.getTransactions().size());
        LockTrxVO trx = vo.getTransactions().get(0);
        assertNull("PG has no trx id equivalent", trx.getTrxId());
        assertEquals("idle in transaction", trx.getState());
        assertEquals("2026-09-19 09:59:00+08", trx.getStarted());
        assertEquals(Long.valueOf(90L), trx.getDurationSeconds());
        assertNull(trx.getRowsLocked());
        assertNull(trx.getRowsModified());
        assertEquals("81", trx.getThreadPid());
        assertNull(vo.getPrivilegeHint());
    }

    // ------------------------------------------------------------- group 4: deadlock section extraction

    @Test
    public void extractDeadlockSection_completeSection_stopsAtNextSeparator() {
        String status = "=====================================\n" //
                + "2026-09-19 10:00:00 0x7f INNODB MONITOR OUTPUT\n" //
                + "=====================================\n" //
                + "------------------------\n" //
                + "LATEST DETECTED DEADLOCK\n" //
                + "------------------------\n" //
                + "*** (1) TRANSACTION:\n" //
                + "TRANSACTION 100, ACTIVE 5 sec starting index read\n" //
                + "UPDATE t SET c = 1 WHERE id = 1\n" //
                + "*** (2) HOLDS THE LOCK(S):\n" //
                + "RECORD LOCKS space id 58 page no 3\n" //
                + "*** WE ROLL BACK TRANSACTION (2)\n" //
                + "------------\n" //
                + "TRANSACTIONS\n" //
                + "------------\n" //
                + "Trx id counter 200\n";

        String section = LockAnalysisServiceImpl.extractDeadlockSection(status);

        assertEquals("*** (1) TRANSACTION:\n" //
                + "TRANSACTION 100, ACTIVE 5 sec starting index read\n" //
                + "UPDATE t SET c = 1 WHERE id = 1\n" //
                + "*** (2) HOLDS THE LOCK(S):\n" //
                + "RECORD LOCKS space id 58 page no 3\n" //
                + "*** WE ROLL BACK TRANSACTION (2)", section);
    }

    @Test
    public void extractDeadlockSection_noMarker_returnsNull() {
        String status = "=====================================\n2026-09-19 INNODB MONITOR OUTPUT\n=====================================\n" //
                + "------------\nTRANSACTIONS\n------------\nTrx id counter 100\n";
        assertNull(LockAnalysisServiceImpl.extractDeadlockSection(status));
        assertNull(LockAnalysisServiceImpl.extractDeadlockSection(null));
        assertNull(LockAnalysisServiceImpl.extractDeadlockSection("   "));
    }

    @Test
    public void extractDeadlockSection_terminatedAtEof_noTruncationMarker() {
        String status = "------------------------\n" //
                + "LATEST DETECTED DEADLOCK\n" //
                + "------------------------\n" //
                + "*** (1) TRANSACTION:\n" //
                + "*** WE ROLL BACK TRANSACTION (1)\n" //
                + "-------------------------------------\n";

        String section = LockAnalysisServiceImpl.extractDeadlockSection(status);

        assertEquals("*** (1) TRANSACTION:\n*** WE ROLL BACK TRANSACTION (1)", section);
        assertFalse("a present closing separator means no truncation", section.endsWith(LockAnalysisServiceImpl.DEADLOCK_TRUNCATION_MARKER));
    }

    @Test
    public void extractDeadlockSection_truncatedText_keepsTailWithEllipsis() {
        String status = "------------------------\n" //
                + "LATEST DETECTED DEADLOCK\n" //
                + "------------------------\n" //
                + "*** (1) TRANSACTION:\n" //
                + "TRANSACTION 100, ACTIVE 5 se";

        String section = LockAnalysisServiceImpl.extractDeadlockSection(status);

        assertNotNull(section);
        assertTrue("everything to the tail is kept", section.contains("*** (1) TRANSACTION:"));
        assertTrue(section.contains("TRANSACTION 100, ACTIVE 5 se"));
        assertTrue("missing closing separator marks the column-bytes truncation", //
                section.endsWith("\n" + LockAnalysisServiceImpl.DEADLOCK_TRUNCATION_MARKER));
    }

    @Test
    public void overview_mysql_deadlockSectionExtracted_notAbsent() {
        innodbStatusResult = statusResult("------------------------\nLATEST DETECTED DEADLOCK\n------------------------\n" //
                + "*** (1) TRANSACTION:\nUPDATE t SET c = 1 WHERE id = 1\n*** WE ROLL BACK TRANSACTION (2)\n------------\nTRANSACTIONS\n");

        LockOverviewVO vo = service.overview(PUID, UID, overviewFO());

        assertTrue(vo.isDeadlockSupported());
        assertFalse(vo.isDeadlockAbsent());
        assertNotNull(vo.getDeadlock());
        assertTrue(vo.getDeadlock().contains("WE ROLL BACK TRANSACTION (2)"));
    }

    @Test
    public void overview_mysql_noDeadlockRecorded_absentTrueNotNull() {
        LockOverviewVO vo = service.overview(PUID, UID, overviewFO()); // default status fixture has no marker

        assertTrue(vo.isDeadlockSupported());
        assertTrue("query succeeded but nothing recorded — the normal state", vo.isDeadlockAbsent());
        assertNull(vo.getDeadlock());
    }

    // ------------------------------------------------------------- group 5: performance_schema OFF

    @Test
    public void overview_psDisabled_skipsLockWaitSql_blockDegraded_othersNormal() {
        psResult = queryResult(new String[] { "ps_enabled" }, new String[][] { { "0" } });

        LockOverviewVO vo = service.overview(PUID, UID, overviewFO());

        synchronized (executedSqls) {
            assertTrue("data_locks would be silently empty — the lock-wait SQL must not run", //
                    executedSqls.stream().noneMatch(sql -> sql.contains("data_lock_waits")));
        }
        assertEquals(4, executedSqls.size());
        String degraded = vo.getPartialErrors().get("lockWaits");
        assertNotNull(degraded);
        assertTrue("degradation text names the switch in every locale bundle", degraded.contains("performance_schema"));
        assertNull(vo.getLockWaits());
        assertNotNull("transactions block unaffected", vo.getTransactions());
        assertTrue("deadlock block unaffected", vo.isDeadlockAbsent());
        verify(queryService).closeSession(UID, DIAG_SESSION);
    }

    // ------------------------------------------------------------- group 6: privilege translation & degradation

    @Test
    public void overview_mysql_commandDenied_escalatesToNoPrivilegeWholeFailure_sessionClosed() {
        myLockWaitResult = messageResult(MessageLevel.Error, "SELECT command denied to user 'agent'@'%' for table 'data_lock_waits'");
        try {
            service.overview(PUID, UID, overviewFO());
            fail("missing PROCESS must fail the whole overview (all MySQL blocks depend on it)");
        } catch (ErrorMessageException e) {
            // "GRANT PROCESS ON" appears in both the zh and en bundle values and never in the raw
            // DB error — locale-robust proof that LOCK_QUERY_NO_PRIVILEGE (not a generic error) rendered
            assertTrue(e.getMessage().contains("GRANT PROCESS ON"));
            assertTrue("original DB error must be carried in {0}", e.getMessage().contains("command denied"));
        }
        assertEquals("no block SQL may follow the escalation", 3, executedSqls.size());
        verify(queryService).closeSession(UID, DIAG_SESSION);
    }

    @Test
    public void overview_mysql_accessDeniedOnStatusQuery_escalatesToNoPrivilege_sessionClosed() {
        innodbStatusResult = messageResult(MessageLevel.Error, "Access denied; you need (at least one of) the PROCESS privilege(s) for this operation");
        try {
            service.overview(PUID, UID, overviewFO());
            fail("missing PROCESS on INNODB STATUS must fail the whole overview");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("GRANT PROCESS ON"));
            assertTrue(e.getMessage().contains("Access denied"));
        }
        verify(queryService).closeSession(UID, DIAG_SESSION);
    }

    @Test
    public void overview_mysql_otherBlockFailure_degradesOnlyThatBlock() {
        myTrxResult = messageResult(MessageLevel.Error, "Table 'information_schema.innodb_trx' is broken");

        LockOverviewVO vo = service.overview(PUID, UID, overviewFO());

        String degraded = vo.getPartialErrors().get("transactions");
        assertNotNull(degraded);
        assertTrue("raw reason must be carried", degraded.contains("is broken"));
        assertNull(vo.getTransactions());
        assertNotNull("lock-wait block unaffected", vo.getLockWaits());
        assertTrue("deadlock block unaffected", vo.isDeadlockAbsent());
        verify(queryService).closeSession(UID, DIAG_SESSION);
    }

    @Test
    public void overview_pg_blockFailure_degradesWithoutNoPrivilegeEscalation() {
        usePostgresDs();
        pgLockWaitResult = messageResult(MessageLevel.Error, "permission denied for view pg_locks");

        LockOverviewVO vo = service.overview(PUID, UID, overviewFO());

        String degraded = vo.getPartialErrors().get("lockWaits");
        assertNotNull("PG failures degrade the block, never escalate (masking semantics)", degraded);
        assertTrue(degraded.contains("permission denied for view pg_locks"));
        assertNotNull("transactions block unaffected", vo.getTransactions());
        verify(queryService).closeSession(UID, DIAG_SESSION);
    }

    @Test
    public void overview_pg_maskedWaitQuery_setsTranslatedPrivilegeHint_notFailure() {
        usePostgresDs();
        pgLockWaitResult = queryResult(MY_LOCK_WAIT_COLS, new String[][] { //
                { "84", "<insufficient privileges>", "300", "public.t", null, "tuple", "ExclusiveLock", null, //
                        "81", null, null, "2026-09-19 10:00:00+08", null } });

        LockOverviewVO vo = service.overview(PUID, UID, overviewFO());

        assertNotNull("masked query text must set the hint", vo.getPrivilegeHint());
        assertTrue("hint names the pg_monitor grant in every locale bundle", vo.getPrivilegeHint().contains("pg_monitor"));
        assertTrue("masking is not an error — no block degradation", vo.getPartialErrors().isEmpty());
        assertEquals(1, vo.getLockWaits().size());
    }

    @Test
    public void overview_pg_maskedTrxQuery_setsTranslatedPrivilegeHint() {
        usePostgresDs();
        pgTrxResult = queryResult(PG_TRX_COLS, new String[][] { //
                { null, "active", "2026-09-19 09:59:00+08", "90", null, null, "81", "<insufficient privileges>" } });

        LockOverviewVO vo = service.overview(PUID, UID, overviewFO());

        assertNotNull(vo.getPrivilegeHint());
        assertTrue(vo.getPrivilegeHint().contains("pg_monitor"));
    }

    // ------------------------------------------------------------- group 7: timeout / whitelist / tenant

    @Test
    public void overview_createSessionHangs_timesOutWholeFailure() {
        ReflectionTestUtils.setField(service, "queryTimeoutSeconds", 1L);
        when(queryService.createSession(anyString(), any(), any())).thenAnswer(invocation -> {
            Thread.sleep(5_000L);
            return DIAG_SESSION;
        });
        try {
            service.overview(PUID, UID, overviewFO());
            fail("hanging session creation must time out");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("超时"));
        }
    }

    @Test
    public void overview_probeHangs_timesOutWholeFailure_sessionClosed() {
        ReflectionTestUtils.setField(service, "queryTimeoutSeconds", 1L);
        hangSqlFragment = "SELECT VERSION";
        try {
            service.overview(PUID, UID, overviewFO());
            fail("hanging version probe must time out the whole overview");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("超时"));
        }
        verify(queryService).closeSession(UID, DIAG_SESSION);
    }

    @Test
    public void overview_blockHangs_degradesWithTimeoutText_otherBlocksOk_sessionClosed() {
        ReflectionTestUtils.setField(service, "queryTimeoutSeconds", 1L);
        hangSqlFragment = "performance_schema.data_lock_waits";

        LockOverviewVO vo = service.overview(PUID, UID, overviewFO());

        String degraded = vo.getPartialErrors().get("lockWaits");
        assertNotNull(degraded);
        assertTrue(degraded.contains("超时"));
        assertNull(vo.getLockWaits());
        assertNotNull("remaining blocks still served", vo.getTransactions());
        assertTrue(vo.isDeadlockAbsent());
        verify(queryService).closeSession(UID, DIAG_SESSION);
    }

    @Test
    public void overview_unsupportedDsType_rejectedWithoutAnyRemoteCall() {
        when(dsMapper.listByOwnerAndIds(eq(PUID), any())).thenReturn(List.of(ds(DataSourceType.MariaDB, LifeCycleState.CREATED)));
        try {
            service.overview(PUID, UID, overviewFO());
            fail("MariaDB is outside the lock-analysis whitelist");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("暂不支持锁分析"));
        }
        verify(queryService, never()).createSession(anyString(), any(), any());
        assertTrue(executedSqls.isEmpty());
    }

    @Test
    public void overview_foreignTenantDsId_notFoundSemanticsWithoutAnyRemoteCall() {
        when(dsMapper.listByOwnerAndIds(eq(PUID), any())).thenReturn(Collections.emptyList());
        try {
            service.overview(PUID, UID, overviewFO());
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

    // ------------------------------------------------------------- group 8: displayChars passthrough

    @Test
    public void overview_displayChars_onlyDeadlockQueryRequests131072() {
        service.overview(PUID, UID, overviewFO());

        synchronized (executedRequests) {
            for (QueryRequest request : executedRequests) {
                int displayChars = request.getResultConf().getDisplayChars();
                if (request.getQueryBody().startsWith("SHOW ENGINE INNODB STATUS")) {
                    assertEquals("the Status cell must survive undisplayed-truncated", //
                            LockAnalysisServiceImpl.DEADLOCK_STATUS_DISPLAY_CHARS, displayChars);
                } else {
                    assertEquals("all other queries keep the global default (0 = fill in fillRequestConfig)", 0, displayChars);
                }
            }
            assertEquals(5, executedRequests.size());
        }
    }

    // ------------------------------------------------------------- helpers

    /** Switches the ownership/config fixtures to a PostgreSQL datasource. */
    private void usePostgresDs() {
        when(dsMapper.listByOwnerAndIds(eq(PUID), any())).thenReturn(List.of(ds(DataSourceType.PostgreSQL, LifeCycleState.CREATED)));
        when(dmDsConfigService.fetchDsConfigFromExists(DS_ID)).thenReturn(dsConfig(DataSourceType.PostgreSQL, DS_USER));
    }

    private static LockOverviewFO overviewFO() {
        LockOverviewFO fo = new LockOverviewFO();
        fo.setDsId(DS_ID);
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

    /** SHOW ENGINE INNODB STATUS shape: one row, the text lives in the Status column. */
    private static ResultList statusResult(String statusText) {
        return queryResult(STATUS_COLS, new String[][] { { "InnoDB", "Status", statusText } });
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
