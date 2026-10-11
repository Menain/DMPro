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

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import com.clougence.clouddm.console.web.component.detectrule.SecRulesCheckResult;
import com.clougence.clouddm.sdk.service.secrules.RuleLevel;
import com.clougence.clouddm.sdk.sql.parser.SplitQueryType;

/**
 * Unit tests for {@link RiskAssessor} — covers the D1 assessment matrix:
 * statement-type dimension, precheck-hit dimension, row-count dimension,
 * and the max() aggregation rule. Each D1 example-table row is asserted.
 */
public class RiskAssessorTest {

    private static RiskLevelConfig defaultConfig() {
        return new RiskLevelConfig(); // mediumRows=1000, highRows=100000, criticalRows=1000000
    }

    private static Set<SplitQueryType> types(SplitQueryType... ts) {
        return new HashSet<>(Arrays.asList(ts));
    }

    private static SecRulesCheckResult rules(String... ruleNameLevels) {
        SecRulesCheckResult result = new SecRulesCheckResult();
        for (int i = 0; i + 1 < ruleNameLevels.length; i += 2) {
            String name = ruleNameLevels[i];
            String levelName = ruleNameLevels[i + 1];
            result.addResult(name, RuleLevel.valueOf(levelName), null, "msg-" + name);
        }
        return result;
    }

    // ==================== D1 Example Table (逐行断言) ====================

    @Test
    public void selectWithWhere_isLow() {
        // SELECT ... WHERE id=1 → LOW
        RiskAssessment ra = RiskAssessor.assess(
            List.of(types(SplitQueryType.SELECT)),
            null, null, defaultConfig(), "dev", "MySQL", Collections.emptyList());
        assertEquals(RiskLevel.LOW, ra.getLevel());
    }

    @Test
    public void insert100Rows_isLow() {
        // INSERT INTO t VALUES(...)(100行) → LOW
        RiskAssessment ra = RiskAssessor.assess(
            List.of(types(SplitQueryType.INSERT)),
            null, 100L, defaultConfig(), "dev", "MySQL", Collections.emptyList());
        assertEquals(RiskLevel.LOW, ra.getLevel());
    }

    @Test
    public void updateWithWhere1Row_isLow() {
        // UPDATE t SET a=1 WHERE id=5 (预估1行) → LOW
        RiskAssessment ra = RiskAssessor.assess(
            List.of(types(SplitQueryType.UPDATE)),
            null, 1L, defaultConfig(), "dev", "MySQL", Collections.emptyList());
        assertEquals(RiskLevel.LOW, ra.getLevel());
    }

    @Test
    public void update50000Rows_isMedium() {
        // UPDATE t SET a=1 WHERE ctime<'2020-01-01' (预估5万行) → MEDIUM
        RiskAssessment ra = RiskAssessor.assess(
            List.of(types(SplitQueryType.UPDATE)),
            null, 50_000L, defaultConfig(), "dev", "MySQL", Collections.emptyList());
        assertEquals(RiskLevel.MEDIUM, ra.getLevel());
    }

    @Test
    public void delete1200000Rows_isCritical() {
        // DELETE FROM t WHERE ... (预估120万行) → CRITICAL (row dimension)
        RiskAssessment ra = RiskAssessor.assess(
            List.of(types(SplitQueryType.DELETE)),
            null, 1_200_000L, defaultConfig(), "dev", "MySQL", Collections.emptyList());
        assertEquals(RiskLevel.CRITICAL, ra.getLevel());
    }

    @Test
    public void deleteNoWhere_isCritical() {
        // DELETE FROM t (无WHERE) → CRITICAL (no-WHERE rule hit at FAILURE)
        SecRulesCheckResult rules = rules("rule_delete_001", "FAILURE");
        RiskAssessment ra = RiskAssessor.assess(
            List.of(types(SplitQueryType.DELETE)),
            rules, null, defaultConfig(), "dev", "MySQL", Collections.emptyList());
        assertEquals(RiskLevel.CRITICAL, ra.getLevel());
    }

    @Test
    public void dropTable_isCritical() {
        // DROP TABLE t → CRITICAL (statement type)
        RiskAssessment ra = RiskAssessor.assess(
            List.of(types(SplitQueryType.DROP_TABLE)),
            null, null, defaultConfig(), "dev", "MySQL", Collections.emptyList());
        assertEquals(RiskLevel.CRITICAL, ra.getLevel());
    }

    @Test
    public void truncateTable_isCritical() {
        // TRUNCATE t → CRITICAL
        RiskAssessment ra = RiskAssessor.assess(
            List.of(types(SplitQueryType.TRUNCATE_TABLE)),
            null, null, defaultConfig(), "dev", "MySQL", Collections.emptyList());
        assertEquals(RiskLevel.CRITICAL, ra.getLevel());
    }

    @Test
    public void alterTableDropColumn_isHigh() {
        // ALTER TABLE t DROP COLUMN c → HIGH
        RiskAssessment ra = RiskAssessor.assess(
            List.of(types(SplitQueryType.DROP_COLUMN)),
            null, null, defaultConfig(), "dev", "MySQL", Collections.emptyList());
        assertEquals(RiskLevel.HIGH, ra.getLevel());
    }

    @Test
    public void alterTableAddColumn_isMedium() {
        // ALTER TABLE t ADD COLUMN c INT → MEDIUM
        RiskAssessment ra = RiskAssessor.assess(
            List.of(types(SplitQueryType.ADD_COLUMN)),
            null, null, defaultConfig(), "dev", "MySQL", Collections.emptyList());
        assertEquals(RiskLevel.MEDIUM, ra.getLevel());
    }

    @Test
    public void grant_isHigh() {
        // GRANT SELECT ON db.* TO u → HIGH
        RiskAssessment ra = RiskAssessor.assess(
            List.of(types(SplitQueryType.GRANT)),
            null, null, defaultConfig(), "dev", "MySQL", Collections.emptyList());
        assertEquals(RiskLevel.HIGH, ra.getLevel());
    }

    @Test
    public void createIndexLargeTable_isMedium_noRows() {
        // CREATE INDEX idx ON t(a) (大表) → MEDIUM (行数不可估时不升级)
        RiskAssessment ra = RiskAssessor.assess(
            List.of(types(SplitQueryType.ADD_INDEX)),
            null, null, defaultConfig(), "dev", "MySQL", Collections.emptyList());
        assertEquals(RiskLevel.MEDIUM, ra.getLevel());
    }

    // ==================== Threshold boundaries ====================

    @Test
    public void rowsExactlyMedium_isLow() {
        // rows == mediumRows (1000) → LOW (boundary: >mediumRows → MEDIUM, == is LOW)
        RiskAssessment ra = RiskAssessor.assess(
            List.of(types(SplitQueryType.UPDATE)),
            null, 1000L, defaultConfig(), "dev", "MySQL", Collections.emptyList());
        assertEquals(RiskLevel.LOW, ra.getLevel());
    }

    @Test
    public void rowsOneAboveMedium_isMedium() {
        RiskAssessment ra = RiskAssessor.assess(
            List.of(types(SplitQueryType.UPDATE)),
            null, 1001L, defaultConfig(), "dev", "MySQL", Collections.emptyList());
        assertEquals(RiskLevel.MEDIUM, ra.getLevel());
    }

    @Test
    public void rowsExactlyHigh_isMedium() {
        // rows == highRows (100000) → MEDIUM
        RiskAssessment ra = RiskAssessor.assess(
            List.of(types(SplitQueryType.UPDATE)),
            null, 100_000L, defaultConfig(), "dev", "MySQL", Collections.emptyList());
        assertEquals(RiskLevel.MEDIUM, ra.getLevel());
    }

    @Test
    public void rowsOneAboveHigh_isHigh() {
        RiskAssessment ra = RiskAssessor.assess(
            List.of(types(SplitQueryType.UPDATE)),
            null, 100_001L, defaultConfig(), "dev", "MySQL", Collections.emptyList());
        assertEquals(RiskLevel.HIGH, ra.getLevel());
    }

    @Test
    public void rowsExactlyCritical_isHigh() {
        // rows == criticalRows (1000000) → HIGH
        RiskAssessment ra = RiskAssessor.assess(
            List.of(types(SplitQueryType.UPDATE)),
            null, 1_000_000L, defaultConfig(), "dev", "MySQL", Collections.emptyList());
        assertEquals(RiskLevel.HIGH, ra.getLevel());
    }

    @Test
    public void rowsOneAboveCritical_isCritical() {
        RiskAssessment ra = RiskAssessor.assess(
            List.of(types(SplitQueryType.UPDATE)),
            null, 1_000_001L, defaultConfig(), "dev", "MySQL", Collections.emptyList());
        assertEquals(RiskLevel.CRITICAL, ra.getLevel());
    }

    // ==================== D1 admin operations (KILL/PURGE/RESET/FLUSH → HIGH) ====================
    // SplitQueryType has no dedicated KILL/PURGE/RESET/FLUSH enums; the MySQL parser maps
    // KILL→ADMIN, PURGE→MAINTAIN_LOG, RESET→SYSTEM_SETTING_WRITE. Design D1 = HIGH.

    @Test
    public void killStatement_isHigh() {
        // KILL → SplitQueryType.ADMIN (bare) → HIGH per D1
        RiskAssessment ra = RiskAssessor.assess(
            List.of(types(SplitQueryType.ADMIN)),
            null, null, defaultConfig(), "prod", "MySQL", Collections.emptyList());
        assertEquals(RiskLevel.HIGH, ra.getLevel());
    }

    @Test
    public void purgeBinaryLogs_isHigh() {
        // PURGE BINARY LOGS → SplitQueryType.MAINTAIN_LOG → HIGH per D1
        RiskAssessment ra = RiskAssessor.assess(
            List.of(types(SplitQueryType.MAINTAIN_LOG)),
            null, null, defaultConfig(), "prod", "MySQL", Collections.emptyList());
        assertEquals(RiskLevel.HIGH, ra.getLevel());
    }

    @Test
    public void resetStatement_isHigh() {
        // RESET → SplitQueryType.SYSTEM_SETTING_WRITE → HIGH per D1
        RiskAssessment ra = RiskAssessor.assess(
            List.of(types(SplitQueryType.SYSTEM_SETTING_WRITE)),
            null, null, defaultConfig(), "prod", "MySQL", Collections.emptyList());
        assertEquals(RiskLevel.HIGH, ra.getLevel());
    }

    @Test
    public void analyzeTable_staysMedium() {
        // ANALYZE TABLE → SplitQueryType.ADMIN_TABLE → MEDIUM (benign maintenance, NOT HIGH)
        RiskAssessment ra = RiskAssessor.assess(
            List.of(types(SplitQueryType.ADMIN_TABLE)),
            null, null, defaultConfig(), "prod", "MySQL", Collections.emptyList());
        assertEquals(RiskLevel.MEDIUM, ra.getLevel());
    }

    // ==================== No-row degradation (v1 temporary level) ====================

    @Test
    public void noRows_dropTable_isCritical() {
        // No row count → row dimension = LOW, but statement type = CRITICAL
        RiskAssessment ra = RiskAssessor.assess(
            List.of(types(SplitQueryType.DROP_TABLE)),
            null, null, defaultConfig(), "dev", "MySQL", Collections.emptyList());
        assertEquals(RiskLevel.CRITICAL, ra.getLevel());
    }

    @Test
    public void noRows_update_isLow() {
        // No rows, UPDATE with WHERE (no no-WHERE rule hit) → LOW
        RiskAssessment ra = RiskAssessor.assess(
            List.of(types(SplitQueryType.UPDATE)),
            null, null, defaultConfig(), "dev", "MySQL", Collections.emptyList());
        assertEquals(RiskLevel.LOW, ra.getLevel());
    }

    // ==================== Precheck-hit dimension ====================

    @Test
    public void failureRule_isHigh() {
        SecRulesCheckResult rules = rules("rule_query_002", "FAILURE");
        RiskAssessment ra = RiskAssessor.assess(
            List.of(types(SplitQueryType.SELECT)),
            rules, null, defaultConfig(), "dev", "MySQL", Collections.emptyList());
        assertEquals(RiskLevel.HIGH, ra.getLevel());
    }

    @Test
    public void ticketRule_isMedium() {
        SecRulesCheckResult rules = rules("rule_table_004", "TICKET");
        RiskAssessment ra = RiskAssessor.assess(
            List.of(types(SplitQueryType.CREATE_TABLE)),
            rules, null, defaultConfig(), "dev", "MySQL", Collections.emptyList());
        assertEquals(RiskLevel.MEDIUM, ra.getLevel());
    }

    @Test
    public void suggestRule_isLow() {
        SecRulesCheckResult rules = rules("rule_table_011", "SUGGEST");
        RiskAssessment ra = RiskAssessor.assess(
            List.of(types(SplitQueryType.CREATE_TABLE)),
            rules, null, defaultConfig(), "dev", "MySQL", Collections.emptyList());
        assertEquals(RiskLevel.LOW, ra.getLevel());
    }

    // ==================== max() aggregation across statements ====================

    @Test
    public void mixedStatements_takesMax() {
        // INSERT (LOW) + DROP_TABLE (CRITICAL) → CRITICAL
        RiskAssessment ra = RiskAssessor.assess(
            List.of(types(SplitQueryType.INSERT), types(SplitQueryType.DROP_TABLE)),
            null, 50L, defaultConfig(), "dev", "MySQL", Collections.emptyList());
        assertEquals(RiskLevel.CRITICAL, ra.getLevel());
    }

    @Test
    public void multipleDimensions_takesMax() {
        // UPDATE (LOW type) + FAILURE rule (HIGH hit) + 200K rows (HIGH row) → HIGH
        SecRulesCheckResult rules = rules("rule_update_002", "FAILURE");
        RiskAssessment ra = RiskAssessor.assess(
            List.of(types(SplitQueryType.UPDATE)),
            rules, 200_000L, defaultConfig(), "dev", "MySQL", Collections.emptyList());
        assertEquals(RiskLevel.HIGH, ra.getLevel());
    }

    @Test
    public void noWhereUpdateElevatesToCritical() {
        // UPDATE (normally LOW) + no-WHERE rule at FAILURE → CRITICAL
        SecRulesCheckResult rules = rules("rule_update_001", "FAILURE");
        RiskAssessment ra = RiskAssessor.assess(
            List.of(types(SplitQueryType.UPDATE)),
            rules, null, defaultConfig(), "dev", "MySQL", Collections.emptyList());
        assertEquals(RiskLevel.CRITICAL, ra.getLevel());
    }

    // ==================== ImpactScope ====================

    @Test
    public void impactScope_populatedCorrectly() {
        SecRulesCheckResult rules = rules("rule_delete_001", "FAILURE", "rule_query_002", "TICKET");
        RiskAssessment ra = RiskAssessor.assess(
            List.of(types(SplitQueryType.DELETE), types(SplitQueryType.SELECT)),
            rules, 5000L, defaultConfig(), "prod", "MySQL", List.of("db.t1", "db.t2"));

        ImpactScope scope = ra.getScope();
        assertEquals("prod", scope.getEnvName());
        assertEquals("MySQL", scope.getDsType());
        assertEquals(Integer.valueOf(2), scope.getStmtCount());
        assertEquals(Integer.valueOf(0), scope.getDdlCount());
        assertEquals(Integer.valueOf(2), scope.getDmlCount());
        assertEquals(Long.valueOf(5000L), scope.getEstimatedRows());
        assertTrue(scope.getRowsEstimated());
        assertTrue(scope.getTables().contains("db.t1"));
        assertTrue(scope.getTables().contains("db.t2"));
        assertTrue(scope.getStmtTypes().contains("DELETE"));
        assertTrue(scope.getStmtTypes().contains("SELECT"));
        assertEquals(2, scope.getHitRules().size());
    }

    @Test
    public void trueZeroRows_isEstimated_notUnknown() {
        // Task 10-11 M2: a TRUE zero-row estimate is a real estimate — rowsEstimated=true,
        // estimatedRows=0 (before M2 the >0 guard conflated it with "unknown"); rating stays LOW
        RiskAssessment ra = RiskAssessor.assess(
            List.of(types(SplitQueryType.UPDATE)),
            null, 0L, defaultConfig(), "dev", "MySQL", Collections.emptyList());
        assertEquals(RiskLevel.LOW, ra.getLevel());
        ImpactScope scope = ra.getScope();
        assertEquals(Long.valueOf(0L), scope.getEstimatedRows());
        assertTrue(scope.getRowsEstimated());
        assertTrue(ra.getReadableText().contains("预估影响0行"));
    }

    @Test
    public void nullRows_scopeMarksUnknown() {
        // null = estimate unavailable (e.g. PG DML) → rowsEstimated=false, no rows in the text;
        // rating unchanged (LOW — plan-b: unknown neither upgrades nor masquerades as 0)
        RiskAssessment ra = RiskAssessor.assess(
            List.of(types(SplitQueryType.UPDATE)),
            null, null, defaultConfig(), "dev", "MySQL", Collections.emptyList());
        assertEquals(RiskLevel.LOW, ra.getLevel());
        ImpactScope scope = ra.getScope();
        assertNull(scope.getEstimatedRows());
        assertFalse(scope.getRowsEstimated());
        assertFalse(ra.getReadableText().contains("预估影响"));
    }

    @Test
    public void readableText_containsLevelAndSummary() {
        RiskAssessment ra = RiskAssessor.assess(
            List.of(types(SplitQueryType.DELETE)),
            null, 120_000L, defaultConfig(), "prod", "MySQL", List.of("db.t1"));
        // 120K rows → HIGH (row dimension > highRows); DELETE type → LOW; max = HIGH
        assertTrue(ra.getReadableText().contains("HIGH"));
        assertTrue(ra.getReadableText().contains("12.00万") || ra.getReadableText().contains("12.0万"));
        assertTrue(ra.getReadableText().contains("db.t1"));
    }

    // ==================== Custom config ====================

    @Test
    public void customConfig_appliesCustomThresholds() {
        RiskLevelConfig config = new RiskLevelConfig();
        config.setMediumRows(100);
        config.setHighRows(1000);
        config.setCriticalRows(10000);

        // 500 rows → MEDIUM with custom config (was LOW with defaults)
        RiskAssessment ra = RiskAssessor.assess(
            List.of(types(SplitQueryType.UPDATE)),
            null, 500L, config, "dev", "MySQL", Collections.emptyList());
        assertEquals(RiskLevel.MEDIUM, ra.getLevel());
    }

    @Test
    public void emptyInput_isLow() {
        RiskAssessment ra = RiskAssessor.assess(
            Collections.emptyList(), null, null, defaultConfig(), null, null, Collections.emptyList());
        assertEquals(RiskLevel.LOW, ra.getLevel());
    }

    @Test
    public void nullStatementTypes_isLow() {
        RiskAssessment ra = RiskAssessor.assess(
            null, null, null, defaultConfig(), null, null, null);
        assertEquals(RiskLevel.LOW, ra.getLevel());
    }
}
