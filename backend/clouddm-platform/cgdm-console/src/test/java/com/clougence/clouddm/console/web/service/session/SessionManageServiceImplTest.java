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
package com.clougence.clouddm.console.web.service.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import com.clougence.clouddm.console.web.model.fo.session.SessionKillFO;
import com.clougence.clouddm.console.web.model.fo.session.SessionListFO;
import com.clougence.clouddm.console.web.model.vo.session.DbSessionListVO;
import com.clougence.clouddm.console.web.model.vo.session.DbSessionVO;
import com.clougence.clouddm.console.web.model.vo.session.SessionInstanceVO;
import com.clougence.clouddm.console.web.model.vo.session.SessionKillAuditObj;
import com.clougence.clouddm.console.web.model.vo.session.SessionProtectedReason;
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
 * Unit tests for {@link SessionManageServiceImpl} (design §9, six assertion groups):
 * row mapping, protection matrix, type whitelist / tenant ownership, kill flows with
 * server-side re-validation, hard timeout, and instance listing.
 * <p>
 * Discipline: JUnit4 + hand-written mocks + ReflectionTestUtils only; the PluginManager
 * static lookups are isolated behind protected seams overridden by an anonymous subclass
 * (no mockStatic, no Spring context).
 */
public class SessionManageServiceImplTest {

    private static final String   UID               = "uid-operator";
    private static final String   PUID              = "puid-tenant";
    private static final long     DS_ID             = 9L;
    private static final String   DIAG_SESSION      = "diag-session";
    private static final String   SELF_ID           = "77";
    private static final String   DS_USER           = "clouddm_agent";
    private static final String[] PROCESSLIST_COLS  = { "Id", "User", "Host", "db", "Command", "Time", "State", "Info" };

    private SessionManageServiceImpl service;
    private DataSourceDal            dsDal;
    private DmDsMapper               dsMapper;
    private DmDsConfigService        dmDsConfigService;
    private QueryService             queryService;

    /** SQL bodies actually dispatched to the (mocked) worker, in order. */
    private final List<String>       executedSqls   = Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong         queryIdSeq     = new AtomicLong();

    /** Per-test configurable processlist result. */
    private ResultList               processlistResult;
    /** Per-test configurable KILL result; defaults to Info-level success message. */
    private ResultList               killResult;

    private final SessionSpi         fakeSpi        = new SessionSpi() {

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
        service = new SessionManageServiceImpl() {

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

        ReflectionTestUtils.setField(service, "dsDal", dsDal);
        ReflectionTestUtils.setField(service, "dmDsConfigService", dmDsConfigService);
        ReflectionTestUtils.setField(service, "queryService", queryService);

        // default fixtures: owned MySQL datasource + its config
        when(dsMapper.listByOwnerAndIds(eq(PUID), any())).thenReturn(List.of(ds(DataSourceType.MySQL, LifeCycleState.CREATED)));
        when(dmDsConfigService.fetchDsConfigFromExists(DS_ID)).thenReturn(dsConfig(DataSourceType.MySQL, DS_USER));
        when(queryService.createSession(anyString(), any(), any())).thenReturn(DIAG_SESSION);
        processlistResult = queryResult(PROCESSLIST_COLS, new String[0][]);
        killResult = messageResult(MessageLevel.Info, "ok");

        when(queryService.syncExecuteQuery(anyString(), anyString(), any(QueryRequest.class))).thenAnswer(invocation -> {
            QueryRequest request = invocation.getArgument(2);
            String body = request.getQueryBody();
            executedSqls.add(body);
            if (body.startsWith("SELECT CONNECTION_ID")) {
                return queryResult(new String[] { "CONNECTION_ID()" }, new String[][] { { SELF_ID } });
            }
            if (body.startsWith("SHOW FULL PROCESSLIST")) {
                return processlistResult;
            }
            if (body.startsWith("KILL ")) {
                return killResult;
            }
            throw new IllegalStateException("unexpected sql dispatched: " + body);
        });
    }

    // ------------------------------------------------------------- group 1: row mapping

    @Test
    public void list_mapsColumnsCaseInsensitively_numericTime_nullInfoTolerated() {
        processlistResult = queryResult(new String[] { "ID", "user", "HOST", "Db", "COMMAND", "time", "STATE", "info" }, //
                new String[][] { //
                                 { "101", "app_user", "10.1.1.9:5555", "orders", "Query", "300", "Sending data", "SELECT * FROM t" }, //
                                 { "102", "report", "10.1.1.10:1111", null, "Sleep", "not-a-number", null, null }, //
                });

        DbSessionListVO vo = service.list(PUID, UID, listFO());

        assertEquals(SELF_ID, vo.getSelfConnectionId());
        assertEquals(2, vo.getSessions().size());

        DbSessionVO first = vo.getSessions().get(0);
        assertEquals("101", first.getId());
        assertEquals("app_user", first.getUser());
        assertEquals("10.1.1.9:5555", first.getHost());
        assertEquals("orders", first.getDb());
        assertEquals("Query", first.getCommand());
        assertEquals(Long.valueOf(300L), first.getTime());
        assertEquals("Sending data", first.getState());
        assertEquals("SELECT * FROM t", first.getInfo());

        DbSessionVO second = vo.getSessions().get(1);
        assertNull("non-numeric time degrades to null", second.getTime());
        assertNull("null info tolerated", second.getInfo());
        assertNull(second.getDb());
        assertNull(second.getState());
    }

    // ------------------------------------------------------------- group 2: protection matrix

    @Test
    public void list_protectionMatrix_allRules() {
        processlistResult = queryResult(PROCESSLIST_COLS, new String[][] { //
                row(SELF_ID, DS_USER, "10.0.0.5:1234", null, "Query", "0", "executing", "SHOW FULL PROCESSLIST"), // platform self
                row("21", "event_scheduler", "localhost", null, "Daemon", "999", "Waiting", null),                 // system user
                row("22", "system user", "localhost", null, "Connect", "888", "Waiting", null),                    // system user
                row("23", "sql/main", "localhost", null, "Daemon", "777", "Waiting", null),                        // system user
                row("24", "repl", "10.0.0.9:1", null, "Binlog Dump", "666", "Sending", null),                      // replication command
                row("25", "repl", "10.0.0.9:2", null, "binlog dump gtid", "665", "Sending", null),                 // replication command, lowercase
                row("26", "slave_io_x", "10.0.0.9:3", null, "Connect", "664", "Waiting", null),                    // replication user prefix
                row("27", "CloudDM_Agent", "10.0.0.5:9999", null, "Sleep", "10", "", null),                        // shared account (case-insensitive)
                row("28", "app_user", "10.1.1.9:5555", "orders", "Query", "300", "Sending data", "SELECT 1"),      // normal
        });

        List<DbSessionVO> sessions = service.list(PUID, UID, listFO()).getSessions();

        assertEquals(9, sessions.size());
        assertProtected(sessions.get(0), SessionProtectedReason.PLATFORM_SELF);
        assertFalse("self row is never flagged sharedAccount", sessions.get(0).isSharedAccount());
        assertProtected(sessions.get(1), SessionProtectedReason.SYSTEM_USER);
        assertProtected(sessions.get(2), SessionProtectedReason.SYSTEM_USER);
        assertProtected(sessions.get(3), SessionProtectedReason.SYSTEM_USER);
        assertProtected(sessions.get(4), SessionProtectedReason.REPLICATION_THREAD);
        assertProtected(sessions.get(5), SessionProtectedReason.REPLICATION_THREAD);
        assertProtected(sessions.get(6), SessionProtectedReason.REPLICATION_THREAD);

        // shared account: killable, but flagged for the confirm-dialog strong hint
        assertTrue(sessions.get(7).isKillable());
        assertNull(sessions.get(7).getProtectedReason());
        assertTrue(sessions.get(7).isSharedAccount());

        // plain session: fully killable
        assertTrue(sessions.get(8).isKillable());
        assertNull(sessions.get(8).getProtectedReason());
        assertFalse(sessions.get(8).isSharedAccount());
    }

    // ------------------------------------------------------------- group 3: whitelist & tenant scope

    @Test
    public void list_oracleDsType_rejectedAsUnsupported() {
        when(dsMapper.listByOwnerAndIds(eq(PUID), any())).thenReturn(List.of(ds(DataSourceType.Oracle, LifeCycleState.CREATED)));
        try {
            service.list(PUID, UID, listFO());
            fail("Oracle datasource must be rejected");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("暂不支持会话管理"));
        }
        verify(queryService, never()).createSession(anyString(), any(), any());
    }

    @Test
    public void list_foreignTenantDsId_notFoundSemantics() {
        when(dsMapper.listByOwnerAndIds(eq(PUID), any())).thenReturn(Collections.emptyList());
        try {
            service.list(PUID, UID, listFO());
            fail("cross-tenant dsId must be rejected");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("数据源不存在或无权限"));
        }
        verify(queryService, never()).createSession(anyString(), any(), any());
    }

    @Test
    public void kill_foreignTenantDsId_notFoundSemantics() {
        when(dsMapper.listByOwnerAndIds(eq(PUID), any())).thenReturn(Collections.emptyList());
        try {
            service.kill(PUID, UID, killFO("300"));
            fail("cross-tenant dsId must be rejected");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("数据源不存在或无权限"));
        }
        verify(queryService, never()).createSession(anyString(), any(), any());
    }

    // ------------------------------------------------------------- group 4: kill flows

    @Test
    public void kill_nonDigitSessionId_rejectedWithoutAnyRemoteCall() {
        try {
            service.kill(PUID, UID, killFO("1 OR 1"));
            fail("non-digit session id must be rejected");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("错误的参数"));
        }
        verify(queryService, never()).createSession(anyString(), any(), any());
        assertTrue(executedSqls.isEmpty());
    }

    @Test
    public void kill_targetGone_businessNotFound_noKillIssued_sessionClosed() {
        processlistResult = queryResult(PROCESSLIST_COLS, new String[][] { row("500", "app", "h:1", "db", "Query", "1", "s", "SELECT 1") });
        try {
            service.kill(PUID, UID, killFO("999"));
            fail("vanished session must surface business not-found");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("会话已不存在或已结束"));
        }
        assertNoKillDispatched();
        verify(queryService).closeSession(UID, DIAG_SESSION);
    }

    @Test
    public void kill_protectedTarget_rejected_noKillIssued_sessionClosed() {
        processlistResult = queryResult(PROCESSLIST_COLS, new String[][] { row("24", "repl", "10.0.0.9:1", null, "Binlog Dump", "666", "Sending", null) });
        try {
            service.kill(PUID, UID, killFO("24"));
            fail("replication session must be protected");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("受保护"));
        }
        assertNoKillDispatched();
        verify(queryService).closeSession(UID, DIAG_SESSION);
    }

    @Test
    public void kill_platformSelfConnection_protected() {
        try {
            service.kill(PUID, UID, killFO(SELF_ID));
            fail("self connection must be protected");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("受保护"));
        }
        assertNoKillDispatched();
        verify(queryService).closeSession(UID, DIAG_SESSION);
    }

    @Test
    public void kill_dbErrorMessage_surfacesKillFailedWithReason_sessionClosed() {
        processlistResult = queryResult(PROCESSLIST_COLS,
                new String[][] { row("300", "app_user", "10.1.1.9:5555", "orders", "Query", "300", "Sending data", "SELECT * FROM t") });
        killResult = messageResult(MessageLevel.Error, "Access denied; you need SUPER privilege");
        try {
            service.kill(PUID, UID, killFO("300"));
            fail("DB error must surface as kill-failed");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("终止会话失败"));
            assertTrue(e.getMessage().contains("Access denied"));
        }
        assertTrue(executedSqls.contains("KILL 300"));
        verify(queryService).closeSession(UID, DIAG_SESSION);
    }

    @Test
    public void kill_notOwnerOfThread_surfacesNoPrivilegeMessage_sessionClosed() {
        processlistResult = queryResult(PROCESSLIST_COLS,
                new String[][] { row("300", "other_user", "10.1.1.9:5555", "orders", "Query", "300", "Sending data", "SELECT * FROM t") });
        killResult = messageResult(MessageLevel.Error, "You are not owner of thread 123");
        try {
            service.kill(PUID, UID, killFO("300"));
            fail("missing SUPER/CONNECTION_ADMIN must map to the dedicated no-privilege message");
        } catch (ErrorMessageException e) {
            // CONNECTION_ADMIN appears in both the zh and en bundle values — locale-robust assertion
            assertTrue(e.getMessage().contains("CONNECTION_ADMIN"));
            assertTrue("original DB error must be carried in {0}", e.getMessage().contains("You are not owner of thread 123"));
        }
        assertTrue(executedSqls.contains("KILL 300"));
        verify(queryService).closeSession(UID, DIAG_SESSION);
    }

    @Test
    public void kill_success_returnsAuditObjWithTruncatedInfo_andClosesSession() {
        String longInfo = "x".repeat(900);
        processlistResult = queryResult(PROCESSLIST_COLS,
                new String[][] { row("300", "app_user", "10.1.1.9:5555", "orders", "Query", "300", "Sending data", longInfo) });

        SessionKillAuditObj auditObj = service.kill(PUID, UID, killFO("300"));

        assertEquals("300", auditObj.getSessionId());
        assertEquals("app_user", auditObj.getTargetUser());
        assertEquals("10.1.1.9:5555", auditObj.getTargetHost());
        assertEquals(500, auditObj.getTargetInfo().length());
        assertEquals("SUCCESS", auditObj.getResult());
        assertTrue(executedSqls.contains("KILL 300"));
        verify(queryService).closeSession(UID, DIAG_SESSION);
    }

    // ------------------------------------------------------------- group 5: hard timeout

    @Test
    public void list_remoteHangs_timesOutWithBusinessMessage() {
        ReflectionTestUtils.setField(service, "queryTimeoutSeconds", 1L);
        when(queryService.createSession(anyString(), any(), any())).thenAnswer(invocation -> {
            Thread.sleep(5_000L);
            return DIAG_SESSION;
        });
        try {
            service.list(PUID, UID, listFO());
            fail("hanging remote must time out");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("超时"));
        }
    }

    // ------------------------------------------------------------- group 6: listInstances

    @Test
    public void listInstances_filtersWhitelistAndDeleted_sortsByName() {
        DmDsDO mysql = ds(DataSourceType.MySQL, LifeCycleState.CREATED);
        mysql.setId(2L);
        mysql.setInstanceDesc("zeta-mysql");

        DmDsDO tidb = ds(DataSourceType.TiDB, LifeCycleState.CREATED);
        tidb.setId(1L);
        tidb.setInstanceDesc(null); // blank desc → falls back to host
        tidb.setHost("10.0.0.2");

        DmDsDO oracle = ds(DataSourceType.Oracle, LifeCycleState.CREATED);
        oracle.setId(3L);
        oracle.setInstanceDesc("beta-oracle");

        DmDsDO deleted = ds(DataSourceType.MySQL, LifeCycleState.DELETED);
        deleted.setId(4L);
        deleted.setInstanceDesc("aaa-deleted");

        when(dsMapper.listByOwner(PUID)).thenReturn(new ArrayList<>(List.of(mysql, tidb, oracle, deleted)));

        List<SessionInstanceVO> result = service.listInstances(PUID, UID);

        assertEquals(2, result.size());
        assertEquals(Long.valueOf(1L), result.get(0).getDsId());
        assertEquals("10.0.0.2", result.get(0).getDsName());
        assertEquals("TiDB", result.get(0).getDsType());
        assertEquals(Long.valueOf(2L), result.get(1).getDsId());
        assertEquals("zeta-mysql", result.get(1).getDsName());
        assertEquals("MySQL", result.get(1).getDsType());
    }

    // ------------------------------------------------------------- helpers

    private void assertNoKillDispatched() {
        synchronized (executedSqls) {
            assertTrue("no KILL may be dispatched, got: " + executedSqls, //
                    executedSqls.stream().noneMatch(sql -> sql.startsWith("KILL ")));
        }
    }

    private static void assertProtected(DbSessionVO vo, SessionProtectedReason reason) {
        assertFalse("row " + vo.getId() + " must not be killable", vo.isKillable());
        assertEquals(reason.name(), vo.getProtectedReason());
    }

    private static SessionListFO listFO() {
        SessionListFO fo = new SessionListFO();
        fo.setDsId(DS_ID);
        return fo;
    }

    private static SessionKillFO killFO(String sessionId) {
        SessionKillFO fo = new SessionKillFO();
        fo.setDsId(DS_ID);
        fo.setSessionId(sessionId);
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

    private static String[] row(String id, String user, String host, String db, String command, String time, String state, String info) {
        return new String[] { id, user, host, db, command, time, state, info };
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
