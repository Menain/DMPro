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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.clougence.clouddm.console.web.component.detectrule.SecRulesCheckResult;
import com.clougence.clouddm.console.web.service.envparam.DmEnvParamService;
import com.clougence.clouddm.sdk.model.env.EnvParamKeys;
import com.clougence.clouddm.sdk.service.secrules.RuleLevel;
import com.clougence.clouddm.sdk.sql.parser.SplitQueryType;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * Service wrapper around {@link RiskAssessor} — resolves env-level config
 * ({@code SQL_RISK_LEVEL_CONFIG}) and delegates to the pure-function assessor.
 * <p>
 * Used by all three risk-assessment integration points: v2 submit, v1 createSqlTicket
 * (+ PRE_INIT writeback), and release aggregation.
 */
@Slf4j
@Service
public class RiskAssessmentService {

    @Resource
    private DmEnvParamService dmEnvParamService;

    /**
     * Assess risk from precheck artifacts with env-level config.
     *
     * @param puid             primary-uid (for env-param lookup)
     * @param envId            env-id (for env-param lookup)
     * @param statementTypes   per-statement SplitQueryType sets
     * @param ruleCheckResult  security-rules check result (null-safe)
     * @param estimatedRows    estimated affected rows (null = not estimated)
     * @param envName          environment name for ImpactScope
     * @param dsType           data-source type for ImpactScope
     * @param tables           affected table list for ImpactScope
     * @return non-null {@link RiskAssessment}
     */
    public RiskAssessment assess(
        String puid, long envId,
        List<Set<SplitQueryType>> statementTypes,
        SecRulesCheckResult ruleCheckResult,
        Long estimatedRows,
        String envName,
        String dsType,
        List<String> tables) {

        RiskLevelConfig config = resolveConfig(puid, envId);
        return RiskAssessor.assess(statementTypes, ruleCheckResult, estimatedRows, config, envName, dsType, tables);
    }

    /**
     * Aggregate risk from multiple source tickets (release path, design D2).
     * Level = max of source ticket levels; ImpactScope merged (stmtCount/rows summed, tables merged).
     *
     * @param sourceAssessments per-source-ticket assessments
     * @return aggregated assessment
     */
    public RiskAssessment aggregate(List<RiskAssessment> sourceAssessments) {
        if (sourceAssessments == null || sourceAssessments.isEmpty()) {
            return RiskAssessment.of(RiskLevel.LOW, new ImpactScope(), "无源工单");
        }

        RiskLevel maxLevel = RiskLevel.LOW;
        ImpactScope mergedScope = new ImpactScope();
        List<String> allHitRuleTexts = new ArrayList<>();

        for (RiskAssessment ra : sourceAssessments) {
            if (ra == null) {
                continue;
            }
            maxLevel = RiskLevel.max(maxLevel, ra.getLevel());
            if (ra.getScope() != null) {
                mergedScope.merge(ra.getScope());
            }
        }

        // Build readable text from merged scope
        String readableText = RiskAssessor.buildReadableText(maxLevel, mergedScope);
        return RiskAssessment.of(maxLevel, mergedScope, readableText);
    }

    private RiskLevelConfig resolveConfig(String puid, long envId) {
        try {
            String json = dmEnvParamService.queryParam(puid, envId, EnvParamKeys.SQL_RISK_LEVEL_CONFIG);
            return RiskLevelConfig.fromEnvParam(json);
        } catch (Exception e) {
            log.warn("[RiskAssessmentService] Failed to read SQL_RISK_LEVEL_CONFIG for envId={}, using defaults", envId, e);
            return new RiskLevelConfig();
        }
    }
}
