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

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import org.springframework.stereotype.Component;

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
import com.clougence.clouddm.console.web.util.DmDsUtils;
import com.clougence.clouddm.platform.dal.model.datasource.DmDsDO;
import com.clougence.clouddm.platform.plugin.PluginManager;
import com.clougence.clouddm.sdk.execute.resultset.echo.ReceiveMode;
import com.clougence.clouddm.sdk.execute.session.QueryRequest;
import com.clougence.clouddm.sdk.execute.session.SessionContextDTO;
import com.clougence.clouddm.sdk.execute.session.SessionSpi;
import com.clougence.utils.StringUtils;

import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * Shared channel-C executor for ops diagnostics (design 09-19-table-space-usage §1): short-lived
 * worker session — createSession → timeout-wrapped serial statements → finally release — converged
 * from the byte-identical private copies in B2 {@code SessionManageServiceImpl} and B3
 * {@code LockAnalysisServiceImpl} (the SqlRowEstimator / DmlExplainPreInitHandler family keeps its
 * own copy for now, migration deferred as C4).
 * <p>
 * Contract notes:
 * <ul>
 * <li>every remote operation is hard-timeout wrapped because {@code QueryServiceImpl#syncExecuteQuery}
 *     polls every 500ms without an internal deadline, and a hung createSession RPC must not pin the
 *     request thread either — {@link DiagOpts#isOperationLevelTimeout()} picks the granularity
 *     (B2: one shared budget for the whole operation; B3/B4: createSession and each statement
 *     wrapped individually);</li>
 * <li>timeouts surface as the neutral {@link DiagTimeoutException} — i18n translation stays in the
 *     consuming services (their keys differ); the {@code ExecutionException} unwrap order is
 *     preserved verbatim from the originals (ErrorMessageException → RuntimeException →
 *     CONSOLE_QUERY_EXEC_ERROR fallback);</li>
 * <li>body exceptions propagate as-is; the session is always released in finally
 *     ({@code rollbackOnClose} rolls back first, each step swallowing RuntimeException).</li>
 * </ul>
 */
@Slf4j
@Component
public class OpsDiagnosticExecutor {

    private final ExecutorService timeoutExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ops-diagnostic-timeout");
        t.setDaemon(true);
        return t;
    });

    @Resource
    private DmDsConfigService     dmDsConfigService;
    @Resource
    private QueryService          queryService;

    @PreDestroy
    void shutdown() {
        timeoutExecutor.shutdownNow();
    }

    /**
     * Opens a short-lived diagnostic session, runs {@code body} on it and releases the session in
     * finally. Under {@code operationLevelTimeout} the create + body + release all share ONE
     * timeout budget (B2 shape); otherwise createSession is wrapped separately and each
     * {@link DiagSession#execute} wraps its own statement (B3 shape).
     *
     * @throws DiagTimeoutException   a wrapped remote operation exceeded the budget
     * @throws ErrorMessageException  createSession failure (already translated by dsService.handleException)
     *                                or a checked-cause execution failure (CONSOLE_QUERY_EXEC_ERROR)
     */
    public <T> T runInSession(String uid, DmDsDO dsDO, DataSourceConfig dsConfig, DiagOpts opts, Function<DiagSession, T> body) {
        if (opts.isOperationLevelTimeout()) {
            return callWithTimeout(() -> {
                String sessionId = createDiagnosticSession(uid, dsDO, dsConfig, opts);
                return runBody(uid, sessionId, dsConfig, opts, body);
            }, opts.getTimeoutSeconds());
        }
        String sessionId = callWithTimeout(() -> createDiagnosticSession(uid, dsDO, dsConfig, opts), opts.getTimeoutSeconds());
        return runBody(uid, sessionId, dsConfig, opts, body);
    }

    private <T> T runBody(String uid, String sessionId, DataSourceConfig dsConfig, DiagOpts opts, Function<DiagSession, T> body) {
        DiagSession session = new DiagSession(this, uid, sessionId, dsConfig, opts);
        try {
            return body.apply(session);
        } finally {
            releaseQuietly(uid, sessionId, opts);
        }
    }

    private String createDiagnosticSession(String uid, DmDsDO dsDO, DataSourceConfig dsConfig, DiagOpts opts) {
        SessionContextDTO ctx = opts.getLevelsParam() == null //
                ? findSessionSpi(dsDO.getDataSourceType()).createSessionContext(dsConfig, null) //
                : DmDsUtils.createSessionCtx(dsConfig, opts.getLevelsParam());
        // target-database connection (PG single-database scope): rdbCatalog flows into
        // QueryServiceImpl.sessionConfigOverrides → DEFAULT_DATABASE config override (workbench switch-db mechanism)
        if (StringUtils.isNotBlank(opts.getCatalog())) {
            ctx.setRdbCatalog(opts.getCatalog());
        }
        if (StringUtils.isBlank(ctx.getSessionId())) {
            ctx.setSessionId(UUID.randomUUID().toString().replace("-", ""));
        }
        // autoCommit=true by default: read-only diagnostics never need a rollback step (B2/B3 pattern)
        ctx.setRdbAutoCommit(opts.isAutoCommit());
        ctx.setRdbReadOnly(false);
        DsLevels levels = createLevels(dsDO, ctx);
        return queryService.createSession(uid, levels, ctx);
    }

    /** Finally-release: optional rollback first (C4 ticket-domain shape), each step swallowing RuntimeException. */
    private void releaseQuietly(String uid, String sessionId, DiagOpts opts) {
        if (StringUtils.isBlank(sessionId)) {
            return;
        }
        if (opts.isRollbackOnClose()) {
            try {
                queryService.rollbackSession(uid, sessionId);
            } catch (RuntimeException e) {
                log.debug("[OpsDiagnostic] rollback diagnostic session {} failed", sessionId, e);
            }
        }
        try {
            queryService.closeSession(uid, sessionId);
        } catch (RuntimeException e) {
            log.debug("[OpsDiagnostic] close diagnostic session {} failed", sessionId, e);
        }
    }

    QueryRequest buildRequest(DataSourceConfig dsConfig, DiagOpts opts, String sql) {
        SessionSpi sessionSpi = findSessionSpi(dsConfig.getDataSourceType());
        QueryRequest request = sessionSpi.createQueryRequest(dsConfig);
        request.setQueryId(sessionSpi.newQueryId());
        request.setQueryBody(sql);
        request.setQueryArgs(Collections.emptyList());
        request.setRequester(opts.getRequester());
        request.getResultConf().setCacheResult(false);
        request.getResultConf().setReceiveMode(ReceiveMode.PAGE_FULL);
        request.getResultConf().setRefreshStatus(true);
        return request;
    }

    /** Remote call; syncExecuteQuery polls every 500ms without an internal deadline — always go through callWithTimeout. */
    ResultList executeQuery(String uid, String sessionId, QueryRequest request) {
        return queryService.syncExecuteQuery(uid, sessionId, request);
    }

    <T> T callWithTimeout(Callable<T> task, long timeoutSeconds) {
        Future<T> future = timeoutExecutor.submit(task::call);
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new DiagTimeoutException();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new DiagTimeoutException();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof ErrorMessageException errorMessageException) {
                throw errorMessageException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            log.warn("[OpsDiagnostic] remote operation failed", cause);
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.CONSOLE_QUERY_EXEC_ERROR.name(), cause.getMessage()));
        }
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
