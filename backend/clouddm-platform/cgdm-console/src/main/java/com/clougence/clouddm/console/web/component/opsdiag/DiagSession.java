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

import java.util.List;
import java.util.Map;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.api.sidecar.session.execute.ResultList;
import com.clougence.clouddm.base.metadata.ds.DataSourceConfig;
import com.clougence.clouddm.console.web.global.i18n.DmI18nUtils;
import com.clougence.clouddm.console.web.global.i18n.I18nDmMsgKeys;
import com.clougence.clouddm.sdk.execute.session.QueryRequest;

/**
 * Handle of the short-lived worker session opened by {@link OpsDiagnosticExecutor#runInSession}.
 * Statements run serially on it; the timeout granularity follows {@link DiagOpts}: per-statement
 * wrapping by default (B3/B4 shape), no individual wrapping under {@code operationLevelTimeout}
 * (B2 shape — the whole body already shares one budget).
 */
public final class DiagSession {

    private final OpsDiagnosticExecutor executor;
    private final String                uid;
    private final String                sessionId;
    private final DataSourceConfig      dsConfig;
    private final DiagOpts              opts;

    DiagSession(OpsDiagnosticExecutor executor, String uid, String sessionId, DataSourceConfig dsConfig, DiagOpts opts) {
        this.executor = executor;
        this.uid = uid;
        this.sessionId = sessionId;
        this.dsConfig = dsConfig;
        this.opts = opts;
    }

    public String getSessionId() {
        return sessionId;
    }

    /** Executes one statement with the global displayChars default (filled downstream by {@code DmDsUtils.fillRequestConfig}). */
    public ResultList execute(String sql) {
        return execute(sql, 0);
    }

    /**
     * Executes one statement with an explicit per-request displayChars passthrough
     * (&gt;0 survives {@code fillRequestConfig}; never raise the global default for a feature).
     */
    public ResultList execute(String sql, int displayChars) {
        QueryRequest request = executor.buildRequest(dsConfig, opts, sql);
        if (displayChars > 0) {
            request.getResultConf().setDisplayChars(displayChars);
        }
        if (opts.isOperationLevelTimeout()) {
            // B2 granularity: the enclosing runInSession wrap already budgets create + statements + release
            return executor.executeQuery(uid, sessionId, request);
        }
        return executor.callWithTimeout(() -> executor.executeQuery(uid, sessionId, request), opts.getTimeoutSeconds());
    }

    /**
     * Single-row single-value probe convenience: failure → {@code CONSOLE_QUERY_EXEC_ERROR}
     * (the shape both B2 and B3 probes shared verbatim), empty result → null, otherwise the first
     * column of the first row. Timeouts surface as {@link DiagTimeoutException} for the service
     * to translate by role.
     */
    public String executeSingleValue(String sql) {
        ResultList resultList = execute(sql);
        String failure = DiagResultUtils.firstFailureMessage(resultList);
        if (failure != null) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.CONSOLE_QUERY_EXEC_ERROR.name(), failure));
        }
        List<Map<String, String>> rows = DiagResultUtils.extractRows(resultList);
        if (rows.isEmpty() || rows.get(0).isEmpty()) {
            return null;
        }
        // single column: CONNECTION_ID() / pg_backend_pid() / VERSION() …
        return rows.get(0).values().iterator().next();
    }
}
