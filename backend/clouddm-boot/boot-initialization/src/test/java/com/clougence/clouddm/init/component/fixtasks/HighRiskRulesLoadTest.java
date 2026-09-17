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
package com.clougence.clouddm.init.component.fixtasks;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.clougence.clouddm.console.web.component.detectrule.local.SecRuleScriptInfo;
import com.clougence.clouddm.console.web.component.detectrule.local.SecRulesScriptUtils;
import com.clougence.clouddm.platform.dal.model.secrule.RuleKind;
import com.clougence.clouddm.platform.dal.model.secrule.RuleTarget;
import com.clougence.detectrule.parser.DetectRuleDslProvider;
import com.clougence.detectrule.parser.DetectRuleHelper;
import com.clougence.detectrule.parser.ast.statement.StatementList;
import com.clougence.detectrule.domain.ParamInfo;
import com.clougence.dslpaser.antlr.DslHelper;
import com.clougence.dslpaser.ast.StatementSet;

/**
 * 校验 sec-rules.xml / sec-apply.xml 能被正确加载，且新增的高危规则定义齐全、
 * ruleTarget / dsRange 解析正确。这是 XML 改动的最小加载验证
 * （database-guidelines.md 规约：XML 改动需跑加载验证或编译验证）。
 */
public class HighRiskRulesLoadTest {

    private static final String[][] TARGETED_RULES = {
            // ruleId, ruleTarget
            { "rule_table_013", "Table" },
            { "rule_catalog_004", "Catalog" },
            { "rule_schema_006", "Schema" },
            { "rule_delete_006", "Delete" },
            { "rule_view_001", "View" },
            { "rule_index_001", "Index" },
            { "rule_trigger_001", "Trigger" },
    };

    private static final String[] GENERIC_RULES = {
            "rule_dcl_001", "rule_dcl_002", "rule_dcl_003", "rule_dcl_004",
            "rule_admin_001", "rule_admin_002", "rule_admin_003", "rule_prog_001",
    };

    @Test
    public void allHighRiskRulesLoaded() throws Exception {
        List<SecRuleScriptInfo> rules = SecRulesScriptUtils.innerRules();
        assert rules != null && !rules.isEmpty() : "inner rules should not be empty";

        Map<String, SecRuleScriptInfo> byId = new HashMap<>();
        for (SecRuleScriptInfo info : rules) {
            byId.put(info.getRuleId(), info);
        }

        List<String> allNewIds = new ArrayList<>(TARGETED_RULES.length + GENERIC_RULES.length);
        for (String[] row : TARGETED_RULES) {
            allNewIds.add(row[0]);
        }
        allNewIds.addAll(Arrays.asList(GENERIC_RULES));

        // 定向规则：ruleTarget 正确
        for (String[] row : TARGETED_RULES) {
            SecRuleScriptInfo info = byId.get(row[0]);
            assert info != null : "missing rule definition: " + row[0];
            assert info.getRuleKind() == RuleKind.QUERY : row[0] + " should be QUERY kind";
            assert !info.isDeprecated() : row[0] + " should not be deprecated";
            RuleTarget expected = RuleTarget.valueOf(row[1]);
            assert expected == info.getRuleTarget()
                : row[0] + " ruleTarget expected=" + expected + " actual=" + info.getRuleTarget();
            assert info.getDsRange() != null && !info.getDsRange().isEmpty()
                : row[0] + " should have non-empty dsRange (applyTo)";
            assert info.getContent() != null && info.getContent().contains("@domain.sqlType")
                : row[0] + " DSL should reference @domain.sqlType";
        }

        // 通用规则（DCL/管理类/编程对象）：ruleTarget 为空（generic），dsRange 非空
        for (String id : GENERIC_RULES) {
            SecRuleScriptInfo info = byId.get(id);
            assert info != null : "missing rule definition: " + id;
            assert info.getRuleKind() == RuleKind.QUERY : id + " should be QUERY kind";
            assert !info.isDeprecated() : id + " should not be deprecated";
            assert info.getRuleTarget() == null
                : id + " should be generic (null ruleTarget), actual=" + info.getRuleTarget();
            assert info.getDsRange() != null && !info.getDsRange().isEmpty()
                : id + " should have non-empty dsRange (applyTo)";
            assert info.getContent() != null && info.getContent().contains("@domain.sqlType")
                : id + " DSL should reference @domain.sqlType";
        }

        // DSL 脚本语法校验：复用 DmFixSecRules.fallbackExtractParameters 的解析路径，
        // 确保每条新规则的 DetectRules 脚本能被解析、参数能被抽取（启动期不会因语法错误失败）。
        for (String id : allNewIds) {
            SecRuleScriptInfo info = byId.get(id);
            List<ParamInfo> params = extractParameters(info);
            assert params != null && !params.isEmpty()
                : id + " DSL should define at least one parameter (allow), actual=" + params;
            boolean hasAllowParam = false;
            for (ParamInfo p : params) {
                if ("allow".equals(p.getName())) {
                    hasAllowParam = true;
                    assert "false".equals(p.getDefaultValue())
                        : id + " allow param default should be false (block by default), actual=" + p.getDefaultValue();
                }
            }
            assert hasAllowParam : id + " DSL should define an 'allow' parameter";
        }
    }

    /**
     * 复刻 DmFixSecRules.fallbackExtractParameters 的解析逻辑（插件未加载时的降级路径）。
     */
    private static List<ParamInfo> extractParameters(SecRuleScriptInfo info) {
        try (StringReader reader = new StringReader(info.getContent())) {
            StatementSet statementSet = DslHelper.parserDsl(DetectRuleDslProvider.INSTANCE, reader);
            return DetectRuleHelper.extractParameters((StatementList) statementSet);
        } catch (Exception e) {
            throw new AssertionError("Failed to parse DSL for rule " + info.getRuleId() + ": " + e.getMessage(), e);
        }
    }
}
