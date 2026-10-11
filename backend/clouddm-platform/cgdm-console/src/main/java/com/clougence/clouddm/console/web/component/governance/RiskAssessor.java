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
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.clougence.clouddm.console.web.component.detectrule.SecRulesCheckResult;
import com.clougence.clouddm.sdk.service.secrules.RuleLevel;
import com.clougence.clouddm.sdk.sql.parser.SplitQueryType;
import com.clougence.utils.StringUtils;

/**
 * Pure-function risk assessment engine (design D1/D2).
 * <p>
 * Three independent dimensions, final level = max of the three:
 * <ol>
 * <li><b>Statement-type dimension</b> — maps each {@link SplitQueryType} to a base level per the D1 matrix.
 *     UPDATE/DELETE default to LOW (with-WHERE assumed); if the no-WHERE rule
 *     (rule_update_001 / rule_delete_001) is hit at FAILURE, the type dimension is elevated to CRITICAL.</li>
 * <li><b>Precheck-hit dimension</b> — FAILURE → HIGH, TICKET → MEDIUM, SUGGEST/none → LOW.</li>
 * <li><b>Row-count dimension</b> — uses {@link RiskLevelConfig} thresholds; null/zero rows → LOW (no upgrade).</li>
 * </ol>
 * <p>
 * No state, no Spring dependencies — fully unit-testable.
 */
public final class RiskAssessor {

    // Built-in rule IDs that signal "no WHERE" on UPDATE/DELETE (sec-rules.xml).
    // Hit at FAILURE level → the statement has no WHERE clause → CRITICAL per D1.
    private static final String RULE_NO_WHERE_UPDATE = "rule_update_001";
    private static final String RULE_NO_WHERE_DELETE = "rule_delete_001";
    // Multi-table DML rules — hit at FAILURE → multi-table UPDATE/DELETE → MEDIUM per D1.
    private static final String RULE_MULTI_UPDATE   = "rule_update_005";
    private static final String RULE_MULTI_DELETE   = "rule_delete_002";

    private RiskAssessor() {
    }

    /**
     * Assess risk from precheck artifacts.
     *
     * @param statementTypes  per-statement SplitQueryType sets (from SQL split); may be empty
     * @param ruleCheckResult  security-rules check result; null-safe (treated as "no hits")
     * @param estimatedRows    sum of estimated affected rows; null = rows not estimated (skip row dimension)
     * @param config           row-count thresholds; null = use defaults
     * @param envName          environment name for ImpactScope
     * @param dsType           data-source type for ImpactScope
     * @param tables           affected table list for ImpactScope
     * @return non-null {@link RiskAssessment}
     */
    public static RiskAssessment assess(
        List<Set<SplitQueryType>> statementTypes,
        SecRulesCheckResult ruleCheckResult,
        Long estimatedRows,
        RiskLevelConfig config,
        String envName,
        String dsType,
        List<String> tables) {

        if (config == null) {
            config = new RiskLevelConfig();
        }
        if (statementTypes == null) {
            statementTypes = new ArrayList<>();
        }

        // ---- Dimension 1: statement-type level ----
        boolean noWhereDml = detectNoWhereDml(ruleCheckResult);
        boolean multiTableDml = detectMultiTableDml(ruleCheckResult);
        RiskLevel typeLevel = RiskLevel.LOW;
        for (Set<SplitQueryType> types : statementTypes) {
            if (types == null) {
                continue;
            }
            for (SplitQueryType type : types) {
                RiskLevel stmtLevel = mapStatementType(type, noWhereDml, multiTableDml);
                typeLevel = RiskLevel.max(typeLevel, stmtLevel);
            }
        }

        // ---- Dimension 2: precheck-hit level ----
        RiskLevel hitLevel = mapPrecheckHits(ruleCheckResult);

        // ---- Dimension 3: row-count level ----
        RiskLevel rowLevel = config.levelByRows(estimatedRows);

        // ---- Final level = max of three dimensions ----
        RiskLevel finalLevel = RiskLevel.max(typeLevel, hitLevel, rowLevel);

        // ---- Build ImpactScope ----
        ImpactScope scope = buildScope(statementTypes, ruleCheckResult, estimatedRows, envName, dsType, tables);

        // ---- Human-readable text ----
        String readableText = buildReadableText(finalLevel, scope, ruleCheckResult);

        return RiskAssessment.of(finalLevel, scope, readableText);
    }

    // ==================== Dimension 1: Statement Type ====================

    private static RiskLevel mapStatementType(SplitQueryType type, boolean noWhereDml, boolean multiTableDml) {
        if (type == null) {
            return RiskLevel.LOW;
        }
        switch (type) {
            // ---- CRITICAL: irreversible / destructive operations ----
            case DROP_TABLE:
            case DROP_CATALOG:
            case DROP_SCHEMA:
            case TRUNCATE_TABLE:
            case DROP_USER:
            case REVOKE:
                return RiskLevel.CRITICAL;

            // UPDATE / DELETE: CRITICAL if no WHERE, MEDIUM if multi-table, LOW otherwise
            case UPDATE:
            case DELETE:
                if (noWhereDml) {
                    return RiskLevel.CRITICAL;
                }
                if (multiTableDml) {
                    return RiskLevel.MEDIUM;
                }
                return RiskLevel.LOW;

            // ---- HIGH: dangerous DCL / destructive DDL ----
            case GRANT:
            case CREATE_USER:
            case ALTER_USER:
            case DROP_VIEW:
            case DROP_INDEX:
            case DROP_TRIGGER:
            case DROP_COLUMN:           // ALTER TABLE DROP COLUMN (删列)
            case ALTER_COLUMN:          // type-change ALTER (destructive potential)
                return RiskLevel.HIGH;

            // ---- MEDIUM: structural changes / multi-table DML ----
            case ALTER_TABLE:
            case ADD_INDEX:             // CREATE INDEX
            case TRUNCATE_PARTITION:
            case RENAME_TABLE:
            case RENAME_CATALOG:
            case RENAME_SCHEMA:
            case RENAME_COLUMN:
            case MERGE:                 // complex multi-table DML
                return RiskLevel.MEDIUM;

            // ---- LOW: safe operations ----
            case SELECT:
            case INSERT:
            case CREATE_TABLE:
            case CREATE_VIEW:
                return RiskLevel.LOW;

            // ---- Default: other DDL (CREATE/ALTER/DROP for less critical objects) ----
            default:
                return mapDefaultStatementType(type);
        }
    }

    private static RiskLevel mapDefaultStatementType(SplitQueryType type) {
        // Design D1: KILL/PURGE/RESET/FLUSH = HIGH. SplitQueryType has no dedicated enums for
        // these — the MySQL parser maps KILL→ADMIN (bare), PURGE→MAINTAIN_LOG,
        // RESET→SYSTEM_SETTING_WRITE, FLUSH→MAINTAIN_LOG/SYSTEM_SETTING_WRITE/ADMIN_PERFORMANCE.
        // Map the dangerous admin buckets to HIGH; leave benign maintenance (ADMIN_TABLE for
        // ANALYZE/OPTIMIZE, ADMIN_PERFORMANCE for query-cache) at MEDIUM.
        switch (type) {
            // KILL lands here (visitKillStatement → ADMIN bare)
            case ADMIN:
            // PURGE BINARY LOGS lands here; log purge can break replication
            case MAINTAIN_LOG:
            // RESET fallback + SET GLOBAL system vars (research: HIGH severity)
            case SYSTEM_SETTING_WRITE:
            case SESSION_VARIABLE_RW:
            // PREPARE/EXECUTE/DEALLOCATE — marked UNSAFE authKind (precompiled, unbounded)
            case UNSAFE:
            case UNKNOWN:
                return RiskLevel.HIGH;
            default:
                // fall through to prefix-based mapping
        }
        String name = type.name();
        // DROP operations on non-critical objects → HIGH (still destructive)
        if (name.startsWith("DROP_")) {
            return RiskLevel.HIGH;
        }
        // ALTER operations → MEDIUM (structural change)
        if (name.startsWith("ALTER_")) {
            return RiskLevel.MEDIUM;
        }
        // RENAME operations → MEDIUM
        if (name.startsWith("RENAME_")) {
            return RiskLevel.MEDIUM;
        }
        // CREATE operations → LOW
        if (name.startsWith("CREATE_")) {
            return RiskLevel.LOW;
        }
        // Everything else (ADMIN_TABLE for ANALYZE/OPTIMIZE, ADMIN_PERFORMANCE, etc.) → MEDIUM
        return RiskLevel.MEDIUM;
    }

    // ==================== Dimension 2: Precheck Hits ====================

    private static RiskLevel mapPrecheckHits(SecRulesCheckResult result) {
        if (result == null || result.getChecked() == null || result.getChecked().isEmpty()) {
            return RiskLevel.LOW;
        }
        RiskLevel hitLevel = RiskLevel.LOW;
        for (RuleLevel level : result.getChecked().values()) {
            if (level == null) {
                continue;
            }
            RiskLevel mapped = switch (level) {
                case FAILURE -> RiskLevel.HIGH;
                case TICKET -> RiskLevel.MEDIUM;
                case SUGGEST -> RiskLevel.LOW;
                case PASS -> RiskLevel.LOW;
            };
            hitLevel = RiskLevel.max(hitLevel, mapped);
        }
        return hitLevel;
    }

    // ==================== No-WHERE / Multi-table detection ====================

    private static boolean detectNoWhereDml(SecRulesCheckResult result) {
        if (result == null || result.getChecked() == null) {
            return false;
        }
        return isFailureRuleHit(result, RULE_NO_WHERE_UPDATE)
            || isFailureRuleHit(result, RULE_NO_WHERE_DELETE);
    }

    private static boolean detectMultiTableDml(SecRulesCheckResult result) {
        if (result == null || result.getChecked() == null) {
            return false;
        }
        return isFailureRuleHit(result, RULE_MULTI_UPDATE)
            || isFailureRuleHit(result, RULE_MULTI_DELETE);
    }

    private static boolean isFailureRuleHit(SecRulesCheckResult result, String ruleName) {
        RuleLevel level = result.getChecked().get(ruleName);
        return level == RuleLevel.FAILURE;
    }

    // ==================== ImpactScope builder ====================

    private static ImpactScope buildScope(
        List<Set<SplitQueryType>> statementTypes,
        SecRulesCheckResult ruleCheckResult,
        Long estimatedRows,
        String envName,
        String dsType,
        List<String> tables) {

        ImpactScope scope = new ImpactScope();
        scope.setEnvName(envName);
        scope.setDsType(dsType);

        int ddlCount = 0;
        int dmlCount = 0;
        for (Set<SplitQueryType> types : statementTypes) {
            if (types == null || types.isEmpty()) {
                continue;
            }
            for (SplitQueryType type : types) {
                scope.addStmtType(type.name());
                if (isDmlType(type)) {
                    dmlCount++;
                } else if (isDdlType(type)) {
                    ddlCount++;
                }
            }
        }
        scope.setStmtCount(statementTypes.size());
        scope.setDdlCount(ddlCount);
        scope.setDmlCount(dmlCount);

        // rowsEstimated means exactly "an estimate value exists" (task 10-11 M2): a TRUE zero-row
        // estimate is a real estimate (rowsEstimated=true, readableText prints 预估影响0行); only
        // null (estimate unavailable, e.g. PG DML) marks the row dimension as unknown. Rating is
        // untouched — levelByRows(null) == levelByRows(0) == LOW (plan-b ruling).
        if (estimatedRows != null) {
            scope.setEstimatedRows(estimatedRows);
            scope.setRowsEstimated(true);
        } else {
            scope.setRowsEstimated(false);
        }

        if (tables != null) {
            for (String table : tables) {
                scope.addTable(table);
            }
        }

        if (ruleCheckResult != null && ruleCheckResult.getChecked() != null) {
            Map<String, String> messageMap = ruleCheckResult.getMessageMap();
            for (Map.Entry<String, RuleLevel> entry : ruleCheckResult.getChecked().entrySet()) {
                String ruleName = entry.getKey();
                String level = entry.getValue() != null ? entry.getValue().name() : null;
                String message = messageMap != null ? messageMap.get(ruleName) : null;
                scope.addHitRule(ruleName, level, message);
            }
        }

        return scope;
    }

    private static boolean isDmlType(SplitQueryType type) {
        return type == SplitQueryType.SELECT
            || type == SplitQueryType.INSERT
            || type == SplitQueryType.UPDATE
            || type == SplitQueryType.DELETE
            || type == SplitQueryType.MERGE;
    }

    private static boolean isDdlType(SplitQueryType type) {
        if (isDmlType(type)) {
            return false;
        }
        String name = type.name();
        return name.startsWith("CREATE_")
            || name.startsWith("ALTER_")
            || name.startsWith("DROP_")
            || name.startsWith("RENAME_")
            || name.startsWith("TRUNCATE_")
            || name.startsWith("ADD_")
            || name.startsWith("COMMENT_");
    }

    // ==================== Human-readable text ====================

    /**
     * Builds human-readable impact-scope text from the level and scope.
     * Package-private — called by {@link RiskAssessmentService#aggregate}.
     */
    static String buildReadableText(RiskLevel level, ImpactScope scope) {
        return buildReadableText(level, scope, null);
    }

    private static String buildReadableText(RiskLevel level, ImpactScope scope, SecRulesCheckResult result) {
        StringBuilder sb = new StringBuilder();

        // Level label
        sb.append("风险等级: ").append(level.name()).append("\n");

        // Statement summary
        int stmtCount = scope.getStmtCount() != null ? scope.getStmtCount() : 0;
        int ddl = scope.getDdlCount() != null ? scope.getDdlCount() : 0;
        int dml = scope.getDmlCount() != null ? scope.getDmlCount() : 0;
        sb.append("共").append(stmtCount).append("条语句");
        if (ddl > 0 || dml > 0) {
            sb.append("(DDL ").append(ddl).append("/DML ").append(dml).append(")");
        }

        // Estimated rows
        if (Boolean.TRUE.equals(scope.getRowsEstimated()) && scope.getEstimatedRows() != null) {
            sb.append("，预估影响").append(formatNumber(scope.getEstimatedRows())).append("行");
        }

        // Tables
        if (scope.getTables() != null && !scope.getTables().isEmpty()) {
            sb.append("，涉及表 ").append(String.join(", ", scope.getTables()));
        }

        // Hit rules
        if (scope.getHitRules() != null && !scope.getHitRules().isEmpty()) {
            sb.append("；预检命中：");
            List<String> hitTexts = new ArrayList<>();
            for (ImpactScope.HitRuleRef hr : scope.getHitRules()) {
                String levelText = hr.getLevel() != null ? hr.getLevel() : "";
                String msg = StringUtils.isNotBlank(hr.getMessage()) ? hr.getMessage() : hr.getRule();
                hitTexts.add(msg + "(" + levelText + ")");
            }
            sb.append(String.join("; ", hitTexts));
        }

        return sb.toString();
    }

    private static String formatNumber(long n) {
        if (n >= 100_000_000) {
            return String.format("%.2f亿", n / 100_000_000.0);
        }
        if (n >= 10_000) {
            return String.format("%.2f万", n / 10_000.0);
        }
        return String.valueOf(n);
    }
}
