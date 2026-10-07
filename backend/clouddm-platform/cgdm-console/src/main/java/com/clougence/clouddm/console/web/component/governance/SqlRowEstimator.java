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
package com.clougence.clouddm.console.web.component.governance;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import com.clougence.clouddm.base.metadata.ds.DataSourceConfig;
import com.clougence.clouddm.console.web.component.analysis.AnalysisQueryOptions;
import com.clougence.clouddm.console.web.component.analysis.QueryAnalysisFeature;
import com.clougence.clouddm.console.web.component.analysis.QueryAnalysisService;
import com.clougence.clouddm.console.web.component.dsconfig.DmDsConfigService;
import com.clougence.clouddm.console.web.component.dsconfig.mode.DsLevels;
import com.clougence.clouddm.console.web.component.execute.QueryService;
import com.clougence.clouddm.console.web.util.DmDsUtils;
import com.clougence.clouddm.platform.plugin.DsPluginInfo;
import com.clougence.clouddm.platform.plugin.PluginManager;
import com.clougence.clouddm.sdk.execute.explain.ExplainPlan;
import com.clougence.clouddm.sdk.execute.explain.ExplainPlanNode;
import com.clougence.clouddm.sdk.execute.explain.ExplainPlanSpi;
import com.clougence.clouddm.api.sidecar.session.execute.ResultList;
import com.clougence.clouddm.sdk.execute.resultset.echo.ReceiveMode;
import com.clougence.clouddm.sdk.execute.resultset.echo.Result;
import com.clougence.clouddm.sdk.execute.resultset.echo.ResultMessage;
import com.clougence.clouddm.sdk.execute.session.MessageLevel;
import com.clougence.clouddm.sdk.execute.session.QueryRequest;
import com.clougence.clouddm.sdk.execute.session.SessionContextDTO;
import com.clougence.clouddm.sdk.execute.session.SessionSpi;
import com.clougence.clouddm.sdk.service.secrules.Requester;
import com.clougence.clouddm.sdk.sql.SqlEngineSpi;
import com.clougence.clouddm.sdk.sql.SqlParserParameters;
import com.clougence.clouddm.sdk.sql.analysis.behavior.BehaviorAction;
import com.clougence.clouddm.sdk.sql.analysis.behavior.BehaviorRelation;
import com.clougence.clouddm.sdk.sql.editor.rewrite.RewriteContext;
import com.clougence.clouddm.sdk.sql.editor.rewrite.RewriteSpi;
import com.clougence.clouddm.sdk.sql.parser.SplitQueryType;
import com.clougence.clouddm.sdk.sql.parser.SplitScript;
import com.clougence.utils.StringUtils;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Synchronous SQL row-count estimator for v2 precheck (design D3).
 * <p>
 * Reuses the core estimation logic from {@code DmlExplainPreInitHandler}:
 * <ul>
 * <li>INSERT with literal values → {@code BehaviorRelation.getInsertRows}</li>
 * <li>UPDATE/DELETE → native EXPLAIN via isolated session (autoCommit=false, rollback+close)</li>
 * </ul>
 * <p>
 * <b>Degradation contract (hard)</b>: per-statement EXPLAIN failure/timeout (budget ≤5s, configurable)/
 * unsupported dialect → that statement's row count = null, {@code rowsEstimated=false},
 * assessment skips row dimension. <b>Never blocks submission.</b>
 */
@Slf4j
@Service
public class SqlRowEstimator {

    /** Per-statement EXPLAIN timeout (seconds). */
    private static final long EXPLAIN_TIMEOUT_SECONDS = 5;

    private final ExecutorService timeoutExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "sql-row-estimator-timeout");
        t.setDaemon(true);
        return t;
    });

    @jakarta.annotation.Resource
    private QueryAnalysisService queryAnalysisService;
    @jakarta.annotation.Resource
    private DmDsConfigService    dmDsConfigService;
    @jakarta.annotation.Resource
    private QueryService        queryService;

    @PreDestroy
    void shutdown() {
        timeoutExecutor.shutdownNow();
    }

    /**
     * Estimate total affected rows for the given SQL.
     *
     * @param sqlContent  raw SQL text (may contain multiple statements)
     * @param dsConfig    data-source config
     * @param dsLevels    resolved levels
     * @param ownerUid    ticket owner UID (for session auth)
     * @return total estimated rows (0 if no DML), or null if estimation unavailable
     */
    public EstimationResult estimate(String sqlContent, DataSourceConfig dsConfig, DsLevels dsLevels, String ownerUid, long dsId) {
        if (StringUtils.isBlank(sqlContent) || dsConfig == null) {
            return EstimationResult.unknown();
        }

        ExplainPlanSpi explainSpi = findExplainSpi(dsConfig);
        if (explainSpi == null) {
            log.debug("[SqlRowEstimator] No ExplainPlanSpi for dsType={}, skip row estimation", dsConfig.getDataSourceType());
            return EstimationResult.unknown();
        }

        // Split SQL into statements
        List<SplitScript> scripts;
        try (StringReader reader = new StringReader(sqlContent);
             Stream<SplitScript> stream = queryAnalysisService.analysisSplitStream(
                 dsConfig, reader, Collections.emptyList(), 1, 0)) {
            scripts = stream.toList();
        } catch (Exception e) {
            log.warn("[SqlRowEstimator] SQL split failed, skip row estimation", e);
            return EstimationResult.unknown();
        }

        // Filter DML statements
        List<SplitScript> dmlScripts = new ArrayList<>();
        for (SplitScript s : scripts) {
            if (isDml(s.getType())) {
                dmlScripts.add(s);
            }
        }
        if (dmlScripts.isEmpty()) {
            return EstimationResult.empty();
        }

        // Prepare SQL engine + rewrite
        SqlEngineSpi sqlEngine = dmDsConfigService.fetchSqlEngineSpi(dsConfig);
        SqlParserParameters parameters = dmDsConfigService.fetchSqlParserParameters(dsConfig, dsLevels.levelsParam());
        RewriteSpi rewriteSpi = sqlEngine != null ? sqlEngine.rewriteSpi(parameters) : null;

        AnalysisQueryOptions options = AnalysisQueryOptions.builder()
            .currentUid(ownerUid)
            .dataSourceId(dsId)
            .levels(dsLevels.levelsParam())
            .skip(QueryAnalysisFeature.REWRITE, QueryAnalysisFeature.LINEAGE, QueryAnalysisFeature.MASKING)
            .build();

        // Create session for native EXPLAIN (lazy — only when needed)
        long totalRows = 0;
        boolean anyEstimated = false;
        // B7 additive extension (design §9 marking seam): the per-statement estimates are exposed
        // alongside the group total — the DML batch route evaluates a PER-STATEMENT threshold
        // (裁决 #6 "语句级 EXPLAIN 预估影响行数"), and attributing the group total to individual
        // statements is unsound for multi-statement groups. Zero extra remote cost (the EXPLAIN
        // runs already happen here); the total/rowsEstimated contract above is untouched.
        List<StmtEstimation> perStatement = new ArrayList<>(dmlScripts.size());

        for (SplitScript script : dmlScripts) {
            Long stmtRows = estimateOneStatement(script, dsConfig, dsLevels, options, explainSpi, rewriteSpi, parameters, ownerUid);
            // 1-based exec_order domain (R11: SplitScript.index + 1) — the join key the DML batch
            // marking seam uses; never the 0-based preview index
            perStatement.add(new StmtEstimation((int) script.getIndex() + 1, stmtRows));
            if (stmtRows != null) {
                totalRows += stmtRows;
                anyEstimated = true;
            }
        }

        if (!anyEstimated) {
            return EstimationResult.unknown();
        }
        return new EstimationResult(totalRows, anyEstimated, perStatement);
    }

    private Long estimateOneStatement(
        SplitScript script, DataSourceConfig dsConfig, DsLevels dsLevels,
        AnalysisQueryOptions options, ExplainPlanSpi explainSpi,
        RewriteSpi rewriteSpi, SqlParserParameters parameters,
        String ownerUid) {

        // Get QueryRequest with behavior relations
        QueryRequest request;
        try (StringReader reader = new StringReader(script.getScript());
             Stream<QueryRequest> requests = queryAnalysisService.analysisRequestsStream(
                 dsConfig, reader, script.getScriptArgs(), script.getBodyStartCodeLine(),
                 script.getBodyStartCodeColumn(), options)) {
            request = requests.findFirst().orElse(null);
        } catch (Exception e) {
            log.debug("[SqlRowEstimator] analysisRequestsStream failed for stmt index={}", script.getIndex(), e);
            return null;
        }
        if (request == null) {
            return null;
        }

        // INSERT with literal rows → use BehaviorRelation.getInsertRows
        if (hasInsertStatement(request.getRelations())) {
            ExplainPlan plan = explainSpi.analyze(Collections.emptyList(), request.getRelations());
            return extractEstimatedRows(request.getRelations(), plan);
        }

        // UPDATE/DELETE → native EXPLAIN
        boolean supportsNativeExplain = explainSpi.supportByQueryType(request.getQueryTypes());
        if (!supportsNativeExplain) {
            log.debug("[SqlRowEstimator] EXPLAIN not supported for types {} on stmt index={}", request.getQueryTypes(), script.getIndex());
            return null;
        }
        if (rewriteSpi == null) {
            return null;
        }

        // Rewrite to EXPLAIN query
        RewriteContext rewriteContext = new RewriteContext();
        rewriteContext.setParameters(parameters);
        String explainQuery;
        try {
            explainQuery = rewriteSpi.rewriteToExplain(request.getQueryId(), request.getQueryBody(), rewriteContext);
        } catch (Exception e) {
            log.debug("[SqlRowEstimator] rewriteToExplain failed for stmt index={}", script.getIndex(), e);
            return null;
        }
        if (StringUtils.isBlank(explainQuery)) {
            return null;
        }

        // Build EXPLAIN request
        SessionSpi sessionSpi = PluginManager.findSessionSpi(dsConfig.getDataSourceType());
        QueryRequest explainRequest = sessionSpi.createQueryRequest(dsConfig);
        explainRequest.setIndex(request.getIndex());
        explainRequest.setQueryId(sessionSpi.newQueryId());
        explainRequest.setQueryBody(explainQuery);
        explainRequest.setQueryArgs(request.getQueryArgs());
        explainRequest.setBodyStartCodeLine(request.getBodyStartCodeLine());
        explainRequest.setQueryTypes(request.getQueryTypes());
        explainRequest.setRelations(request.getRelations());
        explainRequest.setDsType(request.getDsType());
        explainRequest.setRequester(Requester.TICKET);
        explainRequest.setUseExplain(true);
        explainRequest.getResultConf().setCacheResult(false);
        explainRequest.getResultConf().setReceiveMode(ReceiveMode.PAGE_FULL);
        explainRequest.getResultConf().setRefreshStatus(true);

        // Execute EXPLAIN with timeout
        return executeExplainWithTimeout(ownerUid, dsConfig, dsLevels, explainRequest, explainSpi, request.getRelations());
    }

    private Long executeExplainWithTimeout(
        String ownerUid, DataSourceConfig dsConfig, DsLevels dsLevels, QueryRequest explainRequest,
        ExplainPlanSpi explainSpi, List<BehaviorRelation> relations) {

        Future<Long> future = timeoutExecutor.submit(() -> {
            String sessionId = createExplainSession(ownerUid, dsConfig, dsLevels);
            if (sessionId == null) {
                return null;
            }
            try {
                ResultList resultList = queryService.syncExecuteQuery(ownerUid, sessionId, explainRequest);
                List<Result> rawResults = resultList == null ? Collections.emptyList() : resultList.getResultList();
                Result failure = rawResults.stream().filter(value -> {
                    if (!value.isSuccess()) {
                        return true;
                    }
                    return value instanceof ResultMessage message && message.getLevel() == MessageLevel.Error;
                }).findFirst().orElse(null);
                if (failure != null) {
                    log.debug("[SqlRowEstimator] EXPLAIN returned failure: {}", failure.getMessage());
                    return null;
                }
                ExplainPlan plan = explainSpi.analyze(rawResults, relations);
                return extractEstimatedRows(relations, plan);
            } finally {
                closeSession(ownerUid, sessionId);
            }
        });

        try {
            return future.get(EXPLAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.debug("[SqlRowEstimator] EXPLAIN timed out after {}s", EXPLAIN_TIMEOUT_SECONDS);
            return null;
        } catch (Exception e) {
            log.debug("[SqlRowEstimator] EXPLAIN execution failed", e);
            return null;
        }
    }

    private String createExplainSession(String ownerUid, DataSourceConfig dsConfig, DsLevels dsLevels) {
        try {
            SessionContextDTO sessionContext = DmDsUtils.createSessionCtx(dsConfig, dsLevels.levelsParam());
            sessionContext.setSessionId(UUID.randomUUID().toString().replace("-", ""));
            sessionContext.setRdbAutoCommit(false);
            sessionContext.setRdbReadOnly(false);
            return queryService.createSession(ownerUid, dsLevels, sessionContext);
        } catch (Exception e) {
            log.warn("[SqlRowEstimator] createExplainSession failed", e);
            return null;
        }
    }

    private void closeSession(String ownerUid, String sessionId) {
        if (sessionId == null) {
            return;
        }
        try {
            queryService.rollbackSession(ownerUid, sessionId);
        } catch (RuntimeException e) {
            log.debug("[SqlRowEstimator] rollback session failed", e);
        }
        try {
            queryService.closeSession(ownerUid, sessionId);
        } catch (RuntimeException e) {
            log.debug("[SqlRowEstimator] close session failed", e);
        }
    }

    // ==================== Helpers (mirrors DmlExplainPreInitHandler) ====================

    private static ExplainPlanSpi findExplainSpi(DataSourceConfig dsConfig) {
        if (dsConfig == null || dsConfig.getDataSourceType() == null) {
            return null;
        }
        DsPluginInfo dsPlugin = PluginManager.findDsPlugin(dsConfig.getDataSourceType());
        List<ExplainPlanSpi> explains = dsPlugin == null ? Collections.emptyList() : dsPlugin.findSpi(ExplainPlanSpi.class);
        return explains.isEmpty() ? null : explains.get(0);
    }

    private static boolean isDml(Set<SplitQueryType> queryTypes) {
        return queryTypes != null && (queryTypes.contains(SplitQueryType.INSERT)
            || queryTypes.contains(SplitQueryType.UPDATE)
            || queryTypes.contains(SplitQueryType.DELETE)
            || queryTypes.contains(SplitQueryType.MERGE));
    }

    private static boolean hasInsertStatement(List<BehaviorRelation> relations) {
        if (relations == null || relations.isEmpty()) {
            return false;
        }
        List<BehaviorRelation> writes = relations.stream().filter(r -> {
            return r != null && ExplainPlanSpi.AFFECTED_ROW_ACTIONS.contains(r.getAction());
        }).toList();
        return !writes.isEmpty() && writes.stream().allMatch(r -> r.getInsertRows() != null);
    }

    private static Long extractEstimatedRows(List<BehaviorRelation> relations, ExplainPlan plan) {
        if (relations == null || relations.isEmpty() || plan == null || plan.getNodes() == null) {
            return null;
        }
        List<String> subjects = new ArrayList<>();
        for (BehaviorRelation relation : relations) {
            if (relation == null || !ExplainPlanSpi.AFFECTED_ROW_ACTIONS.contains(relation.getAction())) {
                continue;
            }
            if (relation.getSubject() != null && relation.getSubject().getObjectPath() != null) {
                subjects.add(relation.getSubject().getObjectPath());
            }
        }
        if (subjects.isEmpty()) {
            return null;
        }
        List<Double> estimates = plan.getNodes().stream()
            .filter(node -> subjects.contains(node.getObjectPath()))
            .map(ExplainPlanNode::getEstimatedRows)
            .filter(java.util.Objects::nonNull)
            .toList();
        if (estimates.isEmpty()) {
            return null;
        }
        return Math.round(estimates.stream().mapToDouble(Double::doubleValue).sum());
    }

    /**
     * Estimation result: total rows + whether any were estimated.
     * <p>
     * B7 additive third component: the PER-STATEMENT estimates (1-based exec_order domain, R11)
     * consumed by the DML batch marking seam's per-statement threshold decision — null entry =
     * that statement's EXPLAIN was unavailable (the seam falls back to the probed table rows).
     * {@code perStatement} is null on {@link #unknown()} (no statement was estimated) and empty
     * on {@link #empty()} (no DML at all); the legacy two-component contract
     * ({@code estimatedRows}/{@code rowsEstimated}) is byte-identical for every existing consumer.
     */
    public record EstimationResult(Long estimatedRows, boolean rowsEstimated, List<StmtEstimation> perStatement) {
        public static EstimationResult unknown() {
            return new EstimationResult(null, false, null);
        }

        public static EstimationResult empty() {
            return new EstimationResult(0L, true, java.util.Collections.emptyList());
        }
    }

    /**
     * One DML statement's EXPLAIN estimate (B7 additive).
     *
     * @param stmtIndex 1-based position in the exec_order domain (SplitScript.index + 1 — R11;
     *                  NEVER the 0-based precheck preview index)
     * @param rows      estimated affected rows; null when this statement's estimation was
     *                  unavailable (unsupported / failed / timed out — the degradation contract)
     */
    public record StmtEstimation(int stmtIndex, Long rows) {
    }
}
