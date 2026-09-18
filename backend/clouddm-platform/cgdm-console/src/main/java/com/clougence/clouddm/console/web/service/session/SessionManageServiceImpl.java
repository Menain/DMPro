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

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.stereotype.Service;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.api.sidecar.session.execute.ResultList;
import com.clougence.clouddm.base.metadata.ds.DataSourceConfig;
import com.clougence.clouddm.base.metadata.ds.DataSourceType;
import com.clougence.clouddm.console.web.component.dsconfig.DmDsConfigService;
import com.clougence.clouddm.console.web.component.dsconfig.mode.DsConfig;
import com.clougence.clouddm.console.web.component.dsconfig.mode.DsLevels;
import com.clougence.clouddm.console.web.component.execute.QueryService;
import com.clougence.clouddm.console.web.global.i18n.DmI18nUtils;
import com.clougence.clouddm.console.web.global.i18n.I18nDmMsgKeys;
import com.clougence.clouddm.console.web.global.i18n.I18nRdpMsgKeys;
import com.clougence.clouddm.console.web.model.fo.session.SessionKillFO;
import com.clougence.clouddm.console.web.model.fo.session.SessionListFO;
import com.clougence.clouddm.console.web.model.vo.session.DbSessionListVO;
import com.clougence.clouddm.console.web.model.vo.session.DbSessionVO;
import com.clougence.clouddm.console.web.model.vo.session.SessionInstanceVO;
import com.clougence.clouddm.console.web.model.vo.session.SessionKillAuditObj;
import com.clougence.clouddm.console.web.model.vo.session.SessionProtectedReason;
import com.clougence.clouddm.console.web.util.DmDsUtils;
import com.clougence.clouddm.platform.dal.access.DataSourceDal;
import com.clougence.clouddm.platform.dal.model.LifeCycleState;
import com.clougence.clouddm.platform.dal.model.datasource.DmDsDO;
import com.clougence.clouddm.platform.plugin.PluginManager;
import com.clougence.clouddm.sdk.execute.resultset.echo.ReceiveMode;
import com.clougence.clouddm.sdk.execute.resultset.echo.Result;
import com.clougence.clouddm.sdk.execute.resultset.echo.ResultMessage;
import com.clougence.clouddm.sdk.execute.resultset.echo.ResultSet;
import com.clougence.clouddm.sdk.execute.resultset.echo.ResultSetMeta;
import com.clougence.clouddm.sdk.execute.resultset.echo.ResultSetRow;
import com.clougence.clouddm.sdk.execute.resultset.echo.ResultSetValue;
import com.clougence.clouddm.sdk.execute.session.MessageLevel;
import com.clougence.clouddm.sdk.execute.session.QueryRequest;
import com.clougence.clouddm.sdk.execute.session.SessionContextDTO;
import com.clougence.clouddm.sdk.execute.session.SessionSpi;
import com.clougence.clouddm.sdk.service.secrules.Requester;
import com.clougence.utils.StringUtils;

import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * Session management service (B2), design 09-17-session-management.
 * <p>
 * Executes read-only diagnostics ({@code SELECT CONNECTION_ID()}, {@code SHOW FULL PROCESSLIST})
 * and the {@code KILL <id>} admin command through channel C: a short-lived worker session
 * (createSession → syncExecuteQuery → finally closeSession, autoCommit=true), mirroring the
 * {@code SqlRowEstimator} / {@code ActionServiceImpl#executeSql} precedents. Every remote call is
 * wrapped with a dedicated daemon executor and a hard timeout because
 * {@code QueryServiceImpl#syncExecuteQuery} polls without an internal deadline.
 * <p>
 * Safety (server side is authoritative, frontend greying-out is UX only):
 * <ul>
 * <li>datasource type whitelist (MySQL protocol family, 9 types);</li>
 * <li>tenant ownership check (dsId must belong to the caller's tenant);</li>
 * <li>protection matrix: platform self connection / system users / replication threads are never killable;</li>
 * <li>kill re-queries the processlist and re-validates the target before issuing KILL;</li>
 * <li>target id must be pure digits before being concatenated into KILL (injection guard).</li>
 * </ul>
 */
@Slf4j
@Service
public class SessionManageServiceImpl implements SessionManageService {

    /** Phase-1 whitelist: MySQL protocol family, SHOW FULL PROCESSLIST + KILL fully compatible (design §1). */
    static final Set<DataSourceType> SUPPORTED = EnumSet.of(//
            DataSourceType.MySQL, DataSourceType.MariaDB, DataSourceType.TiDB, DataSourceType.PolarDbMySQL, //
            DataSourceType.PolarDbX, DataSourceType.OceanBase, DataSourceType.GoldenDBMySQL, //
            DataSourceType.KingbaseESMySQL, DataSourceType.AdbForMySQL);

    static final String              SQL_SELF_CONNECTION_ID = "SELECT CONNECTION_ID()";
    static final String              SQL_PROCESSLIST        = "SHOW FULL PROCESSLIST";
    static final String              SQL_KILL_PREFIX        = "KILL ";

    /** Lowercased MySQL error fragment meaning the DS account lacks SUPER/CONNECTION_ADMIN for other users' threads. */
    static final String              KILL_NOT_OWNER_ERROR   = "you are not owner of thread";

    /** System users that must never be killed (lowercase, exact match). */
    static final Set<String>         SYSTEM_USERS           = Set.of("event_scheduler", "system user", "sql/main");
    /** Replication dump commands (lowercase, exact match). */
    static final Set<String>         REPLICATION_COMMANDS   = Set.of("binlog dump", "binlog dump gtid");
    /** Replication account name prefixes (lowercase, prefix match). */
    static final List<String>        REPLICATION_USER_PREFIXES = List.of("slave_io", "slave_sql", "replication");

    /** Max length of the SQL digest stored in the audit record. */
    static final int                 AUDIT_INFO_MAX_LENGTH  = 500;

    private final ExecutorService    timeoutExecutor        = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "session-manage-timeout");
        t.setDaemon(true);
        return t;
    });

    /** Hard timeout (seconds) for one remote session operation; tests may shorten it. */
    private long                     queryTimeoutSeconds    = 10;

    @Resource
    private DataSourceDal            dsDal;
    @Resource
    private DmDsConfigService        dmDsConfigService;
    @Resource
    private QueryService             queryService;

    @PreDestroy
    void shutdown() {
        timeoutExecutor.shutdownNow();
    }

    // ==================================================================================
    // listInstances
    // ==================================================================================

    @Override
    public List<SessionInstanceVO> listInstances(String puid, String uid) {
        List<DmDsDO> dsList = dsDal.dsMapper().listByOwner(puid);
        List<SessionInstanceVO> result = new ArrayList<>();
        if (dsList == null) {
            return result;
        }
        for (DmDsDO dsDO : dsList) {
            if (!isUsable(dsDO) || !SUPPORTED.contains(dsDO.getDataSourceType())) {
                continue;
            }
            SessionInstanceVO vo = new SessionInstanceVO();
            vo.setDsId(dsDO.getId());
            vo.setDsName(StringUtils.isNotBlank(dsDO.getInstanceDesc()) ? dsDO.getInstanceDesc() : dsDO.getHost());
            vo.setDsType(dsDO.getDataSourceType().getTypeName());
            result.add(vo);
        }
        result.sort((a, b) -> {
            String nameA = a.getDsName() == null ? "" : a.getDsName();
            String nameB = b.getDsName() == null ? "" : b.getDsName();
            return nameA.compareToIgnoreCase(nameB);
        });
        return result;
    }

    // ==================================================================================
    // list
    // ==================================================================================

    @Override
    public DbSessionListVO list(String puid, String uid, SessionListFO fo) {
        DmDsDO dsDO = requireOwnedSupportedDs(puid, fo == null ? null : fo.getDsId());
        DataSourceConfig dsConfig = dmDsConfigService.fetchDsConfigFromExists(dsDO.getId());
        return callWithTimeout(() -> doList(uid, dsDO, dsConfig));
    }

    private DbSessionListVO doList(String uid, DmDsDO dsDO, DataSourceConfig dsConfig) {
        String sessionId = createDiagnosticSession(uid, dsDO, dsConfig);
        try {
            String selfId = fetchSelfConnectionId(uid, sessionId, dsConfig);
            List<DbSessionVO> sessions = fetchProcesslist(uid, sessionId, dsConfig, selfId, dsConfig.getUserName());
            DbSessionListVO vo = new DbSessionListVO();
            vo.setSelfConnectionId(selfId);
            vo.setSessions(sessions);
            return vo;
        } finally {
            closeSessionQuietly(uid, sessionId);
        }
    }

    // ==================================================================================
    // kill
    // ==================================================================================

    @Override
    public SessionKillAuditObj kill(String puid, String uid, SessionKillFO fo) {
        DmDsDO dsDO = requireOwnedSupportedDs(puid, fo == null ? null : fo.getDsId());
        String targetId = fo.getSessionId() == null ? "" : fo.getSessionId().trim();
        // injection guard: the id is concatenated into `KILL <id>`, so it must be pure digits
        // (KingbaseESHooks precedent). Fail fast before opening any diagnostic session.
        if (!isPureDigits(targetId)) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nRdpMsgKeys.COMM_BAD_ARG_ERROR.name()));
        }
        DataSourceConfig dsConfig = dmDsConfigService.fetchDsConfigFromExists(dsDO.getId());
        return callWithTimeout(() -> doKill(uid, dsDO, dsConfig, targetId));
    }

    private SessionKillAuditObj doKill(String uid, DmDsDO dsDO, DataSourceConfig dsConfig, String targetId) {
        String sessionId = createDiagnosticSession(uid, dsDO, dsConfig);
        try {
            // 1) never kill the platform's own diagnostic connection
            String selfId = fetchSelfConnectionId(uid, sessionId, dsConfig);
            if (targetId.equals(selfId)) {
                throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.SESSION_PROTECTED.name()));
            }
            // 2) server-side re-validation: re-query the processlist and apply the same protection
            //    matrix, never trusting the killable flag echoed back by the frontend
            List<DbSessionVO> sessions = fetchProcesslist(uid, sessionId, dsConfig, selfId, dsConfig.getUserName());
            DbSessionVO target = sessions.stream().filter(s -> targetId.equals(s.getId())).findFirst().orElse(null);
            if (target == null) {
                throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.SESSION_NOT_FOUND.name()));
            }
            if (!target.isKillable()) {
                throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.SESSION_PROTECTED.name()));
            }
            // 3) KILL <id> (= KILL CONNECTION); no result set, judge by ResultMessage level
            ResultList killResult = executeSql(uid, sessionId, buildRequest(dsConfig, SQL_KILL_PREFIX + targetId));
            String failure = firstFailureMessage(killResult);
            if (failure != null) {
                throw new ErrorMessageException(DmI18nUtils.getMessage(resolveKillFailureKey(failure).name(), failure));
            }

            SessionKillAuditObj auditObj = new SessionKillAuditObj();
            auditObj.setSessionId(targetId);
            auditObj.setTargetUser(target.getUser());
            auditObj.setTargetHost(target.getHost());
            auditObj.setTargetInfo(truncate(target.getInfo(), AUDIT_INFO_MAX_LENGTH));
            auditObj.setResult("SUCCESS");
            log.info("[SessionManage] killed session id={} user={} host={} on dsId={} by uid={}", //
                    targetId, target.getUser(), target.getHost(), dsDO.getId(), uid);
            return auditObj;
        } finally {
            closeSessionQuietly(uid, sessionId);
        }
    }

    // ==================================================================================
    // channel C: short-lived diagnostic session (SqlRowEstimator / ActionServiceImpl pattern)
    // ==================================================================================

    private String createDiagnosticSession(String uid, DmDsDO dsDO, DataSourceConfig dsConfig) {
        SessionSpi sessionSpi = findSessionSpi(dsDO.getDataSourceType());
        SessionContextDTO ctx = sessionSpi.createSessionContext(dsConfig, null);
        if (StringUtils.isBlank(ctx.getSessionId())) {
            ctx.setSessionId(UUID.randomUUID().toString().replace("-", ""));
        }
        // autoCommit=true: KILL is an immediately-effective admin command, no rollback step needed
        ctx.setRdbAutoCommit(true);
        ctx.setRdbReadOnly(false);
        DsLevels levels = createLevels(dsDO, ctx);
        return queryService.createSession(uid, levels, ctx);
    }

    private void closeSessionQuietly(String uid, String sessionId) {
        if (StringUtils.isBlank(sessionId)) {
            return;
        }
        try {
            queryService.closeSession(uid, sessionId);
        } catch (RuntimeException e) {
            log.debug("[SessionManage] close diagnostic session {} failed", sessionId, e);
        }
    }

    private QueryRequest buildRequest(DataSourceConfig dsConfig, String sql) {
        SessionSpi sessionSpi = findSessionSpi(dsConfig.getDataSourceType());
        QueryRequest request = sessionSpi.createQueryRequest(dsConfig);
        request.setQueryId(sessionSpi.newQueryId());
        request.setQueryBody(sql);
        request.setQueryArgs(Collections.emptyList());
        request.setRequester(Requester.CONSOLE);
        request.getResultConf().setCacheResult(false);
        request.getResultConf().setReceiveMode(ReceiveMode.PAGE_FULL);
        request.getResultConf().setRefreshStatus(true);
        return request;
    }

    /** Remote call with hard timeout: syncExecuteQuery polls every 500ms without an internal deadline. */
    private ResultList executeSql(String uid, String sessionId, QueryRequest request) {
        return queryService.syncExecuteQuery(uid, sessionId, request);
    }

    private <T> T callWithTimeout(Callable<T> task) {
        Future<T> future = timeoutExecutor.submit(task::call);
        try {
            return future.get(queryTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("[SessionManage] session operation timed out after {}s", queryTimeoutSeconds);
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.SESSION_QUERY_TIMEOUT.name()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.SESSION_QUERY_TIMEOUT.name()));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof ErrorMessageException errorMessageException) {
                throw errorMessageException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            log.warn("[SessionManage] session operation failed", cause);
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.CONSOLE_QUERY_EXEC_ERROR.name(), cause.getMessage()));
        }
    }

    // ==================================================================================
    // processlist query & mapping
    // ==================================================================================

    private String fetchSelfConnectionId(String uid, String sessionId, DataSourceConfig dsConfig) {
        ResultList resultList = executeSql(uid, sessionId, buildRequest(dsConfig, SQL_SELF_CONNECTION_ID));
        String failure = firstFailureMessage(resultList);
        if (failure != null) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.CONSOLE_QUERY_EXEC_ERROR.name(), failure));
        }
        List<Map<String, String>> rows = extractRows(resultList);
        if (rows.isEmpty() || rows.get(0).isEmpty()) {
            return null;
        }
        // single column: CONNECTION_ID()
        return rows.get(0).values().iterator().next();
    }

    private List<DbSessionVO> fetchProcesslist(String uid, String sessionId, DataSourceConfig dsConfig, String selfId, String dsUserName) {
        ResultList resultList = executeSql(uid, sessionId, buildRequest(dsConfig, SQL_PROCESSLIST));
        String failure = firstFailureMessage(resultList);
        if (failure != null) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.CONSOLE_QUERY_EXEC_ERROR.name(), failure));
        }
        List<DbSessionVO> sessions = new ArrayList<>();
        for (Map<String, String> row : extractRows(resultList)) {
            sessions.add(mapSessionRow(row, selfId, dsUserName));
        }
        return sessions;
    }

    /** Maps one processlist row; column keys are already lowercased by {@link #extractRows}. */
    DbSessionVO mapSessionRow(Map<String, String> row, String selfId, String dsUserName) {
        DbSessionVO vo = new DbSessionVO();
        vo.setId(row.get("id"));
        vo.setUser(row.get("user"));
        vo.setHost(row.get("host"));
        vo.setDb(row.get("db"));
        vo.setCommand(row.get("command"));
        vo.setTime(parseSeconds(row.get("time")));
        vo.setState(row.get("state"));
        vo.setInfo(row.get("info"));

        SessionProtectedReason reason = resolveProtectedReason(vo.getId(), vo.getUser(), vo.getCommand(), selfId);
        vo.setKillable(reason == null);
        vo.setProtectedReason(reason == null ? null : reason.name());
        vo.setSharedAccount(!isSelfRow(vo.getId(), selfId) //
                && StringUtils.isNotBlank(dsUserName) && dsUserName.equalsIgnoreCase(StringUtils.trimToEmpty(vo.getUser())));
        return vo;
    }

    /** Protection matrix (design §4): server-side authoritative. */
    static SessionProtectedReason resolveProtectedReason(String id, String user, String command, String selfId) {
        if (isSelfRow(id, selfId)) {
            return SessionProtectedReason.PLATFORM_SELF;
        }
        String normalizedUser = StringUtils.trimToEmpty(user).toLowerCase(Locale.ROOT);
        if (SYSTEM_USERS.contains(normalizedUser)) {
            return SessionProtectedReason.SYSTEM_USER;
        }
        String normalizedCommand = StringUtils.trimToEmpty(command).toLowerCase(Locale.ROOT);
        if (REPLICATION_COMMANDS.contains(normalizedCommand)) {
            return SessionProtectedReason.REPLICATION_THREAD;
        }
        for (String prefix : REPLICATION_USER_PREFIXES) {
            if (normalizedUser.startsWith(prefix)) {
                return SessionProtectedReason.REPLICATION_THREAD;
            }
        }
        return null;
    }

    private static boolean isSelfRow(String id, String selfId) {
        return selfId != null && selfId.equals(id);
    }

    /** Flattens ResultList (ResultSetMeta + ResultSet) into column-lowercased row maps. */
    static List<Map<String, String>> extractRows(ResultList resultList) {
        List<Map<String, String>> rows = new ArrayList<>();
        if (resultList == null || resultList.getResultList() == null) {
            return rows;
        }
        List<String> columns = null;
        List<ResultSetRow> rowSet = new ArrayList<>();
        for (Result result : resultList.getResultList()) {
            if (result instanceof ResultSetMeta meta) {
                if (columns == null) {
                    columns = meta.getColumnList();
                }
            } else if (result instanceof ResultSet set) {
                if (set.getRowSet() != null) {
                    rowSet.addAll(set.getRowSet());
                }
            }
        }
        if (columns == null) {
            return rows;
        }
        for (ResultSetRow row : rowSet) {
            Map<String, String> mapped = new LinkedHashMap<>();
            List<ResultSetValue> data = row.getData() == null ? Collections.emptyList() : row.getData();
            for (int i = 0; i < columns.size() && i < data.size(); i++) {
                String column = columns.get(i);
                if (column == null) {
                    continue;
                }
                ResultSetValue value = data.get(i);
                mapped.put(column.toLowerCase(Locale.ROOT), value == null ? null : value.getValue());
            }
            rows.add(mapped);
        }
        return rows;
    }

    /**
     * Picks the kill-failure i18n key: "You are not owner of thread" is MySQL's way of saying the
     * datasource connection account lacks SUPER/CONNECTION_ADMIN to kill other users' threads — a
     * DB-side grant issue unrelated to platform roles, surfaced with a dedicated actionable message.
     */
    static I18nDmMsgKeys resolveKillFailureKey(String failureMessage) {
        if (failureMessage.toLowerCase(Locale.ROOT).contains(KILL_NOT_OWNER_ERROR)) {
            return I18nDmMsgKeys.SESSION_KILL_NO_PRIVILEGE;
        }
        return I18nDmMsgKeys.SESSION_KILL_FAILED;
    }

    /** First failure message of a ResultList (ActionServiceImpl / SqlRowEstimator judgement), or null when all ok. */
    static String firstFailureMessage(ResultList resultList) {
        if (resultList == null || resultList.getResultList() == null) {
            return "empty result";
        }
        for (Result result : resultList.getResultList()) {
            if (result == null) {
                continue;
            }
            if (!result.isSuccess()) {
                return StringUtils.isBlank(result.getMessage()) ? "query failed" : result.getMessage();
            }
            if (result instanceof ResultMessage message && message.getLevel() == MessageLevel.Error) {
                return StringUtils.isBlank(message.getMessage()) ? "query failed" : message.getMessage();
            }
        }
        return null;
    }

    private static Long parseSeconds(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ==================================================================================
    // ownership & whitelist validation
    // ==================================================================================

    /** Tenant ownership + lifecycle + type whitelist; not-found semantics never leak existence. */
    private DmDsDO requireOwnedSupportedDs(String puid, Long dsId) {
        if (dsId == null) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.SESSION_DS_NOT_FOUND.name()));
        }
        List<DmDsDO> owned = dsDal.dsMapper().listByOwnerAndIds(puid, Collections.singletonList(dsId));
        DmDsDO dsDO = (owned == null || owned.isEmpty()) ? null : owned.get(0);
        if (dsDO == null || !isUsable(dsDO)) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.SESSION_DS_NOT_FOUND.name()));
        }
        if (!SUPPORTED.contains(dsDO.getDataSourceType())) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.SESSION_DS_TYPE_UNSUPPORTED.name()));
        }
        return dsDO;
    }

    private static boolean isUsable(DmDsDO dsDO) {
        return dsDO != null && dsDO.getDataSourceType() != null //
                && dsDO.getLifeCycleState() != LifeCycleState.DELETED && dsDO.getLifeCycleState() != LifeCycleState.DELETING;
    }

    static boolean isPureDigits(String value) {
        return StringUtils.isNotBlank(value) && value.chars().allMatch(Character::isDigit);
    }

    static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    // ==================================================================================
    // test seams (never mock statics in tests; PluginManager needs a live plugin registry)
    // ==================================================================================

    protected SessionSpi findSessionSpi(DataSourceType dsType) {
        return PluginManager.findSessionSpi(dsType);
    }

    protected DsLevels createLevels(DmDsDO dsDO, SessionContextDTO ctx) {
        DsConfig dsSetting = dmDsConfigService.dsConstantSettings(dsDO.getDataSourceType());
        return DmDsUtils.createLevels(dsDO, dsSetting, ctx.getRdbCatalog(), ctx.getRdbSchema());
    }
}
