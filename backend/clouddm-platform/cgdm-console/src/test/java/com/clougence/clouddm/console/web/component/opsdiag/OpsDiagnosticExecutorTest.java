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
package com.clougence.clouddm.console.web.component.opsdiag;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
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
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.api.sidecar.session.execute.ResultList;
import com.clougence.clouddm.base.metadata.ds.DataSourceConfig;
import com.clougence.clouddm.base.metadata.ds.DataSourceType;
import com.clougence.clouddm.console.web.component.dsconfig.mode.DsLevels;
import com.clougence.clouddm.console.web.component.execute.QueryService;
import com.clougence.clouddm.platform.dal.model.datasource.DmDsDO;
import com.clougence.clouddm.sdk.execute.resultset.echo.ReceiveMode;
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
import com.clougence.clouddm.sdk.service.secrules.Requester;
import com.clougence.schema.umi.struts.UmiTypes;

/**
 * Unit tests for {@link OpsDiagnosticExecutor} (design 09-19-table-space-usage §1.3 C1): timeout
 * wrapping at both granularities (per-statement and operation-level, including a hanging
 * createSession), the finally-release contract (plain close, rollbackOnClose ordering, rollback
 * failure tolerance), per-request displayChars passthrough, verbatim body-exception propagation
 * and the ExecutionException unwrap order (ErrorMessageException → RuntimeException →
 * CONSOLE_QUERY_EXEC_ERROR fallback), plus the {@link DiagResultUtils} statics converged from
 * B2/B3 and the {@link DiagSession#executeSingleValue} probe shapes.
 * <p>
 * Discipline (B2/B3 precedent): JUnit4 + hand-written mocks + ReflectionTestUtils only; the
 * PluginManager static lookups are isolated behind the executor's protected seams overridden by an
 * anonymous subclass (no mockStatic, no Spring context, no MockMvc).
 */
public class OpsDiagnosticExecutorTest {

    private static final String   UID          = "uid-operator";
    private static final String   DIAG_SESSION = "diag-session";
    private static final long     DS_ID        = 9L;

    private OpsDiagnosticExecutor executor;
    private QueryService          queryService;

    /** Requests actually dispatched to the (mocked) worker, in order. */
    private final List<QueryRequest> executedRequests = Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong         queryIdSeq       = new AtomicLong();

    /** SQL body fragment whose execution must hang (sleep 5s) — for the timeout group. */
    private String                   hangSqlFragment;
    /** When set, fakeSpi returns a session context without a sessionId (UUID fallback probe). */
    private boolean                  blankCtxSessionId;

    private final SessionSpi         fakeSpi          = new SessionSpi() {

        @Override
        public SessionContextDTO createSessionContext(DataSourceConfig dsConfig, Map<String, Object> params) {
            SessionContextDTO ctx = new SessionContextDTO();
            if (!blankCtxSessionId) {
                ctx.setSessionId("ctx-session");
            }
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
        executor = new OpsDiagnosticExecutor() {

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
        ReflectionTestUtils.setField(executor, "queryService", queryService);

        when(queryService.createSession(anyString(), any(), any())).thenReturn(DIAG_SESSION);
        when(queryService.syncExecuteQuery(anyString(), anyString(), any(QueryRequest.class))).thenAnswer(invocation -> {
            QueryRequest request = invocation.getArgument(2);
            executedRequests.add(request);
            if (hangSqlFragment != null && request.getQueryBody().contains(hangSqlFragment)) {
                Thread.sleep(5_000L);
            }
            return queryResult(new String[] { "c1" }, new String[][] { { "v1" } });
        });
    }

    // ------------------------------------------------------------- happy path & session lifecycle

    @Test
    public void runInSession_returnsBodyResult_autoCommitCtx_sessionClosedInFinally() {
        List<String> sqls = new ArrayList<>();
        String out = executor.runInSession(UID, ds(), dsConfig(), DiagOpts.builder().build(), session -> {
            assertEquals(DIAG_SESSION, session.getSessionId());
            session.execute("SELECT 1");
            sqls.add("SELECT 1");
            return "done";
        });

        assertEquals("done", out);
        assertEquals(List.of("SELECT 1"), sqls);
        ArgumentCaptor<SessionContextDTO> ctxCaptor = ArgumentCaptor.forClass(SessionContextDTO.class);
        verify(queryService).createSession(anyString(), any(), ctxCaptor.capture());
        assertTrue("read-only diagnostics default to autoCommit=true", ctxCaptor.getValue().isRdbAutoCommit());
        assertFalse(ctxCaptor.getValue().isRdbReadOnly());
        verify(queryService).closeSession(UID, DIAG_SESSION);
    }

    @Test
    public void runInSession_catalog_setsRdbCatalogOnCtx_forDefaultDatabaseOverride() {
        // PG single-database scope: rdbCatalog flows into QueryServiceImpl.sessionConfigOverrides
        // → DEFAULT_DATABASE config override — the session connects to the target database
        executor.runInSession(UID, ds(), dsConfig(), DiagOpts.builder().catalog("app_db").build(), session -> null);

        ArgumentCaptor<SessionContextDTO> ctxCaptor = ArgumentCaptor.forClass(SessionContextDTO.class);
        verify(queryService).createSession(anyString(), any(), ctxCaptor.capture());
        assertEquals("app_db", ctxCaptor.getValue().getRdbCatalog());
        verify(queryService).closeSession(UID, DIAG_SESSION);
    }

    @Test
    public void runInSession_noCatalog_ctxRdbCatalogUntouched() {
        executor.runInSession(UID, ds(), dsConfig(), DiagOpts.builder().build(), session -> null);

        ArgumentCaptor<SessionContextDTO> ctxCaptor = ArgumentCaptor.forClass(SessionContextDTO.class);
        verify(queryService).createSession(anyString(), any(), ctxCaptor.capture());
        assertNull("the fake spi leaves rdbCatalog unset — the executor must not invent one", //
                ctxCaptor.getValue().getRdbCatalog());
    }

    @Test
    public void runInSession_blankCtxSessionId_uuidFallbackApplied() {
        blankCtxSessionId = true;
        executor.runInSession(UID, ds(), dsConfig(), DiagOpts.builder().build(), session -> null);

        ArgumentCaptor<SessionContextDTO> ctxCaptor = ArgumentCaptor.forClass(SessionContextDTO.class);
        verify(queryService).createSession(anyString(), any(), ctxCaptor.capture());
        String sessionId = ctxCaptor.getValue().getSessionId();
        assertNotNull("blank spi sessionId must be replaced by a UUID fallback", sessionId);
        assertTrue(sessionId.length() >= 32);
        assertTrue(sessionId.indexOf('-') < 0);
    }

    @Test
    public void runInSession_bodyException_propagatesVerbatim_sessionStillClosed() {
        ErrorMessageException boom = new ErrorMessageException("body-boom-marker");
        try {
            executor.runInSession(UID, ds(), dsConfig(), DiagOpts.builder().build(), session -> {
                throw boom;
            });
            fail("body exceptions must propagate as-is");
        } catch (ErrorMessageException e) {
            assertSame(boom, e);
        }
        verify(queryService).closeSession(UID, DIAG_SESSION);
    }

    @Test
    public void runInSession_rollbackOnClose_rollsBackThenCloses() {
        executor.runInSession(UID, ds(), dsConfig(), DiagOpts.builder().rollbackOnClose(true).autoCommit(false).build(), session -> null);

        InOrder order = inOrder(queryService);
        order.verify(queryService).rollbackSession(UID, DIAG_SESSION);
        order.verify(queryService).closeSession(UID, DIAG_SESSION);
    }

    @Test
    public void runInSession_rollbackOnClose_rollbackFails_closeStillRuns() {
        org.mockito.Mockito.doThrow(new RuntimeException("rollback-boom")).when(queryService).rollbackSession(UID, DIAG_SESSION);

        String out = executor.runInSession(UID, ds(), dsConfig(), DiagOpts.builder().rollbackOnClose(true).build(), session -> "ok");

        assertEquals("ok", out);
        verify(queryService).closeSession(UID, DIAG_SESSION);
    }

    // ------------------------------------------------------------- timeout wrapping

    @Test
    public void runInSession_createSessionHangs_throwsDiagTimeout_neverCloses() {
        when(queryService.createSession(anyString(), any(), any())).thenAnswer(invocation -> {
            Thread.sleep(5_000L);
            return DIAG_SESSION;
        });
        try {
            executor.runInSession(UID, ds(), dsConfig(), DiagOpts.builder().timeoutSeconds(1).build(), session -> null);
            fail("a hanging createSession RPC must not pin the request thread");
        } catch (DiagTimeoutException expected) {
            // neutral signal — services translate it into their own i18n keys
        }
        verify(queryService, never()).closeSession(anyString(), anyString());
    }

    @Test
    public void runInSession_perStatementHang_throwsDiagTimeout_sessionStillClosed() {
        hangSqlFragment = "SELECT 1";
        try {
            executor.runInSession(UID, ds(), dsConfig(), DiagOpts.builder().timeoutSeconds(1).build(), session -> session.execute("SELECT 1"));
            fail("a hanging statement must time out");
        } catch (DiagTimeoutException expected) {
        }
        verify(queryService).closeSession(UID, DIAG_SESSION);
    }

    @Test
    public void runInSession_operationLevelTimeout_createHangs_wholeOperationTimesOut() {
        when(queryService.createSession(anyString(), any(), any())).thenAnswer(invocation -> {
            Thread.sleep(5_000L);
            return DIAG_SESSION;
        });
        try {
            executor.runInSession(UID, ds(), dsConfig(), //
                    DiagOpts.builder().timeoutSeconds(1).operationLevelTimeout(true).build(), session -> null);
            fail("operation-level wrap must cover createSession too");
        } catch (DiagTimeoutException expected) {
        }
        verify(queryService, never()).syncExecuteQuery(anyString(), anyString(), any());
        verify(queryService, never()).closeSession(anyString(), anyString());
    }

    @Test
    public void runInSession_operationLevelTimeout_completesNormally_statementsNotIndividuallyWrapped() {
        String out = executor.runInSession(UID, ds(), dsConfig(), //
                DiagOpts.builder().operationLevelTimeout(true).build(), session -> {
                    session.execute("SELECT 1");
                    session.execute("SELECT 2");
                    return "op-done";
                });

        assertEquals("op-done", out);
        assertEquals(2, executedRequests.size());
        verify(queryService).closeSession(UID, DIAG_SESSION);
    }

    // ------------------------------------------------------------- request building & displayChars

    @Test
    public void execute_buildsConsolePageFullRequest_displayCharsOnlyOnExplicitOverride() {
        executor.runInSession(UID, ds(), dsConfig(), DiagOpts.builder().build(), session -> {
            session.execute("SELECT plain");
            session.execute("SHOW ENGINE INNODB STATUS", 131072);
            return null;
        });

        assertEquals(2, executedRequests.size());
        QueryRequest plain = executedRequests.get(0);
        assertEquals("SELECT plain", plain.getQueryBody());
        assertEquals("q-1", plain.getQueryId());
        assertEquals(Requester.CONSOLE, plain.getRequester());
        assertTrue(plain.getQueryArgs().isEmpty());
        assertFalse(plain.getResultConf().isCacheResult());
        assertEquals(ReceiveMode.PAGE_FULL, plain.getResultConf().getReceiveMode());
        assertTrue(plain.getResultConf().isRefreshStatus());
        assertEquals("default path keeps displayChars untouched (filled downstream by fillRequestConfig)", //
                0, plain.getResultConf().getDisplayChars());
        assertEquals("explicit per-request passthrough survives (fillRequestConfig <=0 backfill contract)", //
                131072, executedRequests.get(1).getResultConf().getDisplayChars());
    }

    // ------------------------------------------------------------- executeSingleValue probe shapes

    @Test
    public void executeSingleValue_shapes_value_nullOnEmpty_errorCarriesRawReason() {
        String value = executor.runInSession(UID, ds(), dsConfig(), DiagOpts.builder().build(), session -> session.executeSingleValue("SELECT 1"));
        assertEquals("v1", value);

        when(queryService.syncExecuteQuery(anyString(), anyString(), any(QueryRequest.class)))
                .thenReturn(queryResult(new String[] { "c1" }, new String[0][]));
        String empty = executor.runInSession(UID, ds(), dsConfig(), DiagOpts.builder().build(), session -> session.executeSingleValue("SELECT 1"));
        assertNull("empty result is a normal null, not a failure", empty);

        when(queryService.syncExecuteQuery(anyString(), anyString(), any(QueryRequest.class)))
                .thenReturn(messageResult(MessageLevel.Error, "probe-boom-reason"));
        try {
            executor.runInSession(UID, ds(), dsConfig(), DiagOpts.builder().build(), session -> session.executeSingleValue("SELECT 1"));
            fail("a failed probe must surface as a business error");
        } catch (ErrorMessageException e) {
            assertTrue("raw reason must be carried in {0}", e.getMessage().contains("probe-boom-reason"));
        }
        verify(queryService, org.mockito.Mockito.times(3)).closeSession(UID, DIAG_SESSION);
    }

    // ------------------------------------------------------------- ExecutionException unwrap order

    @Test
    public void execute_unwrapsBusinessAndRuntimeCauses_verbatim() {
        ErrorMessageException business = new ErrorMessageException("unwrap-business-marker");
        when(queryService.syncExecuteQuery(anyString(), anyString(), any(QueryRequest.class))).thenAnswer(invocation -> {
            throw business;
        });
        try {
            executor.runInSession(UID, ds(), dsConfig(), DiagOpts.builder().build(), session -> session.execute("SELECT 1"));
            fail("ErrorMessageException cause must be rethrown verbatim");
        } catch (ErrorMessageException e) {
            assertSame(business, e);
        }

        IllegalStateException runtime = new IllegalStateException("unwrap-runtime-marker");
        when(queryService.syncExecuteQuery(anyString(), anyString(), any(QueryRequest.class))).thenAnswer(invocation -> {
            throw runtime;
        });
        try {
            executor.runInSession(UID, ds(), dsConfig(), DiagOpts.builder().build(), session -> session.execute("SELECT 1"));
            fail("RuntimeException cause must be rethrown verbatim");
        } catch (IllegalStateException e) {
            assertSame(runtime, e);
        }
        verify(queryService, org.mockito.Mockito.times(2)).closeSession(UID, DIAG_SESSION);
    }

    @Test
    public void execute_checkedCause_fallsBackToConsoleQueryExecError() {
        when(queryService.syncExecuteQuery(anyString(), anyString(), any(QueryRequest.class))).thenAnswer(invocation -> {
            throw new Exception("checked-boom-reason");
        });
        try {
            executor.runInSession(UID, ds(), dsConfig(), DiagOpts.builder().build(), session -> session.execute("SELECT 1"));
            fail("checked causes must translate into the shared business error");
        } catch (ErrorMessageException e) {
            assertTrue("raw cause message must be carried in {0}", e.getMessage().contains("checked-boom-reason"));
        }
        verify(queryService).closeSession(UID, DIAG_SESSION);
    }

    // ------------------------------------------------------------- DiagResultUtils statics (converged from B2/B3)

    @Test
    public void extractRows_mixedCaseColumns_lowercasedKeys_nullValuesTolerated() {
        ResultList resultList = queryResult(new String[] { "ID", "Name", "info" }, //
                new String[][] { { "1", null, "x" }, { "2", "n2", null } });

        List<Map<String, String>> rows = DiagResultUtils.extractRows(resultList);

        assertEquals(2, rows.size());
        assertEquals("1", rows.get(0).get("id"));
        assertTrue(rows.get(0).containsKey("name"));
        assertNull(rows.get(0).get("name"));
        assertEquals("x", rows.get(0).get("info"));
        assertEquals("n2", rows.get(1).get("name"));
        assertTrue("null ResultList degrades to empty rows", DiagResultUtils.extractRows(null).isEmpty());
    }

    @Test
    public void firstFailureMessage_shapes() {
        assertEquals("empty result", DiagResultUtils.firstFailureMessage(null));
        assertNull(DiagResultUtils.firstFailureMessage(messageResult(MessageLevel.Info, "ok")));
        assertEquals("broken table", DiagResultUtils.firstFailureMessage(messageResult(MessageLevel.Error, "broken table")));
        assertEquals("query failed", DiagResultUtils.firstFailureMessage(messageResult(MessageLevel.Error, "  ")));
    }

    // ------------------------------------------------------------- helpers

    private static DmDsDO ds() {
        DmDsDO dsDO = new DmDsDO();
        dsDO.setId(DS_ID);
        dsDO.setDsEnvId(1L);
        dsDO.setDataSourceType(DataSourceType.MySQL);
        return dsDO;
    }

    private static DataSourceConfig dsConfig() {
        DataSourceConfig config = new DataSourceConfig();
        config.setDataSourceType(DataSourceType.MySQL);
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
