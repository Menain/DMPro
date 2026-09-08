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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import com.clougence.clouddm.api.sidecar.session.execute.ResultList;
import com.clougence.clouddm.base.metadata.ds.DataSourceConfig;
import com.clougence.clouddm.console.web.component.analysis.AnalysisQueryOptions;
import com.clougence.clouddm.console.web.component.analysis.QueryAnalysisFeature;
import com.clougence.clouddm.console.web.component.analysis.QueryAnalysisService;
import com.clougence.clouddm.console.web.component.dsconfig.DmDsConfigService;
import com.clougence.clouddm.console.web.component.dsconfig.mode.DsLevels;
import com.clougence.clouddm.console.web.component.execute.QueryService;
import com.clougence.clouddm.console.web.util.DmDsUtils;
import com.clougence.clouddm.platform.dal.model.datasource.DmDsDO;
import com.clougence.clouddm.platform.plugin.DsPluginInfo;
import com.clougence.clouddm.platform.plugin.PluginManager;
import com.clougence.clouddm.sdk.execute.explain.ExplainPlan;
import com.clougence.clouddm.sdk.execute.explain.ExplainPlanNode;
import com.clougence.clouddm.sdk.execute.explain.ExplainPlanSpi;
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

import jakarta.annotation.Resource;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Governance DML row estimator — mirrors DmlExplainPreInitHandler's three-branch logic
 * (research/01 §1.2 line-level cross-reference) for synchronous submit-time evaluation.
 * <p>
 * Branch 1: INSERT with insertRows → BehaviorRelation.getInsertRows() sum (dialect-agnostic, zero EXPLAIN).
 * Branch 2: SPI unsupported (PG DML) → 0.
 * Branch 3: Native EXPLAIN → queryService.syncExecuteQuery in an isolated session
 *           (rdbAutoCommit=false, rollback+close at end) + explainSpi.analyze + estimatedRows sum.
 * <p>
 * Per-statement estimation failure → 0 + evidence note, never throws.
 * DmlExplainPreInitHandler body is never modified.
 */
@Service
@Slf4j
public class GovDmlRowEstimator {

    @Resource
    private QueryAnalysisService  queryAnalysisService;
    @Resource
    private QueryService         queryService;
    @Resource
    private DmDsConfigService    dmDsConfigService;

    /**
     * Estimate total affected rows for a list of DML statements.
     *
     * @param puid       tenant uid (for session creation)
     * @param dsConfig   datasource config
     * @param levels     parsed levels for session context
     * @param dmlStmts   list of DML statement texts (already split + classified as DML)
     * @return row estimate with total count and per-statement evidence
     */
    public RowEstimate estimate(String puid, DataSourceConfig dsConfig, DsLevels levels, List<String> dmlStmts) {
        ExplainPlanSpi explainSpi = findExplainSpi(dsConfig);
        if (explainSpi == null) {
            return new RowEstimate(0, "no ExplainPlanSpi available, all statements estimated 0");
        }

        SqlEngineSpi sqlEngine = dmDsConfigService.fetchSqlEngineSpi(dsConfig);
        SqlParserParameters parameters = dmDsConfigService.fetchSqlParserParameters(dsConfig, levels.levelsParam());
        RewriteSpi rewriteSpi = sqlEngine != null ? sqlEngine.rewriteSpi(parameters) : null;

        AnalysisQueryOptions options = AnalysisQueryOptions.builder()
            .currentUid(puid)
            .dataSourceId(levels.dsDO() != null ? levels.dsDO().getId() : 0)
            .levels(levels.levelsParam())
            .skip(QueryAnalysisFeature.REWRITE, QueryAnalysisFeature.LINEAGE, QueryAnalysisFeature.MASKING)
            .build();

        String sessionId = null;
        List<Map<String, Object>> stmtEvidence = new ArrayList<>();
        long totalRows = 0;

        for (int i = 0; i < dmlStmts.size(); i++) {
            String stmtText = dmlStmts.get(i);
            int stmtIndex = i + 1;
            try {
                long rows = 0;
                String branch;
                QueryRequest analyzed = parseQueryRequest(dsConfig, stmtText, options);
                if (analyzed == null || analyzed.getRelations() == null) {
                    branch = "SKIP";
                } else if (hasInsertStatement(analyzed.getRelations())) {
                    branch = "INSERT";
                    rows = sumInsertRows(analyzed.getRelations());
                } else if (!explainSpi.supportByQueryType(analyzed.getQueryTypes())) {
                    branch = "UNSUPPORTED";
                    rows = 0;
                } else {
                    branch = "NATIVE_EXPLAIN";
                    if (sessionId == null) {
                        sessionId = createExplainSession(puid, dsConfig, levels);
                    }
                    rows = estimateNativeExplain(puid, dsConfig, sessionId, analyzed, explainSpi, rewriteSpi, parameters);
                }
                totalRows += rows;
                stmtEvidence.add(buildStmtEvidence(stmtIndex, branch, rows, null));
            } catch (RuntimeException e) {
                log.warn("[GovDmlRowEstimator] statement {} estimation failed, counting as 0", stmtIndex, e);
                totalRows += 0;
                stmtEvidence.add(buildStmtEvidence(stmtIndex, "ERROR", 0, e.getMessage()));
            }
        }

        closeExplainSession(puid, sessionId);

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("totalRows", totalRows);
        evidence.put("stmts", stmtEvidence);
        return new RowEstimate(totalRows, com.clougence.utils.JsonUtils.toJson(evidence));
    }

    private QueryRequest parseQueryRequest(DataSourceConfig dsConfig, String stmtText, AnalysisQueryOptions options) {
        try (StringReader reader = new StringReader(stmtText);
             Stream<QueryRequest> requests = queryAnalysisService.analysisRequestsStream(
                 dsConfig, reader, Collections.emptyList(), 1, 0, options)) {
            return requests.findFirst().orElse(null);
        } catch (Exception e) {
            log.warn("[GovDmlRowEstimator] failed to parse statement into QueryRequest", e);
            throw new RuntimeException("parse failed: " + e.getMessage(), e);
        }
    }

    private long estimateNativeExplain(String puid, DataSourceConfig dsConfig, String sessionId,
                                      QueryRequest analyzed, ExplainPlanSpi explainSpi,
                                      RewriteSpi rewriteSpi, SqlParserParameters parameters) {
        SessionSpi sessionSpi = PluginManager.findSessionSpi(dsConfig.getDataSourceType());
        QueryRequest request = sessionSpi.createQueryRequest(dsConfig);
        request.setQueryId(sessionSpi.newQueryId());
        request.setQueryBody(analyzed.getQueryBody());
        request.setQueryArgs(analyzed.getQueryArgs());
        request.setBodyStartCodeLine(analyzed.getBodyStartCodeLine());
        request.setQueryTypes(analyzed.getQueryTypes());
        request.setRelations(analyzed.getRelations());
        request.setDsType(analyzed.getDsType());
        request.setRequester(Requester.TICKET);

        String explainQuery = null;
        if (rewriteSpi != null) {
            RewriteContext rewriteContext = new RewriteContext();
            rewriteContext.setParameters(parameters);
            explainQuery = rewriteSpi.rewriteToExplain(request.getQueryId(), request.getQueryBody(), rewriteContext);
        }
        if (com.clougence.utils.StringUtils.isBlank(explainQuery)) {
            return 0;
        }
        request.setQueryBody(explainQuery);
        request.setUseExplain(true);
        request.getResultConf().setCacheResult(false);
        request.getResultConf().setReceiveMode(ReceiveMode.PAGE_FULL);
        request.getResultConf().setRefreshStatus(true);

        ResultList resultList = queryService.syncExecuteQuery(puid, sessionId, request);
        List<Result> rawResults = resultList == null ? Collections.emptyList() : resultList.getResultList();
        Result failure = rawResults == null ? null : rawResults.stream().filter(value -> {
            if (!value.isSuccess()) {
                return true;
            }
            return value instanceof ResultMessage message && message.getLevel() == MessageLevel.Error;
        }).findFirst().orElse(null);
        if (failure != null) {
            log.warn("[GovDmlRowEstimator] EXPLAIN query failed: {}", failure.getMessage());
            return 0;
        }

        ExplainPlan plan = explainSpi.analyze(rawResults, request.getRelations());
        List<String> subjects = extractAffectedSubjects(request.getRelations());
        return sumEstimatedRows(subjects, plan);
    }

    private String createExplainSession(String puid, DataSourceConfig dsConfig, DsLevels levels) {
        SessionContextDTO sessionContext = DmDsUtils.createSessionCtx(dsConfig, levels.levelsParam());
        sessionContext.setSessionId(UUID.randomUUID().toString().replace("-", ""));
        sessionContext.setRdbAutoCommit(false);
        sessionContext.setRdbReadOnly(false);
        return queryService.createSession(puid, levels, sessionContext);
    }

    private void closeExplainSession(String puid, String sessionId) {
        if (sessionId == null) {
            return;
        }
        try {
            queryService.rollbackSession(puid, sessionId);
        } catch (RuntimeException e) {
            log.warn("[GovDmlRowEstimator] rollback session failed, sessionId={}", sessionId, e);
        }
        try {
            queryService.closeSession(puid, sessionId);
        } catch (RuntimeException e) {
            log.warn("[GovDmlRowEstimator] close session failed, sessionId={}", sessionId, e);
        }
    }

    // ---- Branch helpers (mirror DmlExplainPreInitHandler L386-447) ----

    private static boolean hasInsertStatement(List<BehaviorRelation> relations) {
        if (relations == null || relations.isEmpty()) {
            return false;
        }
        List<BehaviorRelation> writes = relations.stream()
            .filter(r -> r != null && ExplainPlanSpi.AFFECTED_ROW_ACTIONS.contains(r.getAction()))
            .toList();
        return !writes.isEmpty() && writes.stream().allMatch(r -> r.getInsertRows() != null);
    }

    private static long sumInsertRows(List<BehaviorRelation> relations) {
        return relations.stream()
            .filter(r -> r != null && ExplainPlanSpi.AFFECTED_ROW_ACTIONS.contains(r.getAction()))
            .map(BehaviorRelation::getInsertRows)
            .filter(Objects::nonNull)
            .mapToLong(Long::longValue)
            .sum();
    }

    private static List<String> extractAffectedSubjects(List<BehaviorRelation> relations) {
        Set<String> subjects = new LinkedHashSet<>();
        if (relations != null) {
            for (BehaviorRelation relation : relations) {
                if (relation == null || !ExplainPlanSpi.AFFECTED_ROW_ACTIONS.contains(relation.getAction())) {
                    continue;
                }
                String objectPath = relation.getSubject() == null ? null : relation.getSubject().getObjectPath();
                subjects.add(objectPath);
            }
        }
        return new ArrayList<>(subjects);
    }

    private static long sumEstimatedRows(List<String> subjects, ExplainPlan plan) {
        if (subjects == null || subjects.isEmpty() || plan == null || plan.getNodes() == null) {
            return 0;
        }
        List<Double> estimates = plan.getNodes().stream()
            .filter(node -> subjects.contains(node.getObjectPath()))
            .map(ExplainPlanNode::getEstimatedRows)
            .filter(Objects::nonNull)
            .toList();
        if (estimates.isEmpty()) {
            return 0;
        }
        return Math.round(estimates.stream().mapToDouble(Double::doubleValue).sum());
    }

    private static ExplainPlanSpi findExplainSpi(DataSourceConfig dsConfig) {
        if (dsConfig == null || dsConfig.getDataSourceType() == null) {
            return null;
        }
        DsPluginInfo dsPlugin = PluginManager.findDsPlugin(dsConfig.getDataSourceType());
        List<ExplainPlanSpi> explains = dsPlugin == null ? Collections.emptyList() : dsPlugin.findSpi(ExplainPlanSpi.class);
        return explains.isEmpty() ? null : explains.get(0);
    }

    private static Map<String, Object> buildStmtEvidence(int stmtIndex, String branch, long rows, String note) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("idx", stmtIndex);
        entry.put("branch", branch);
        entry.put("rows", rows);
        if (note != null) {
            entry.put("note", note);
        }
        return entry;
    }

    @Getter
    public static class RowEstimate {
        private final long   estimatedRows;
        private final String evidence;

        public RowEstimate(long estimatedRows, String evidence) {
            this.estimatedRows = estimatedRows;
            this.evidence = evidence;
        }
    }
}
