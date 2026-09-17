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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Getter;
import lombok.Setter;

/**
 * Structured impact-scope payload persisted in ApprovalMO.impactScope (JSON text, zero DDL).
 * <p>
 * Structure per design D4:
 * <pre>{@code
 * {
 *   "envName": "...", "dsType": "MySQL",
 *   "stmtCount": 3, "ddlCount": 1, "dmlCount": 2,
 *   "estimatedRows": 50000, "rowsEstimated": true,
 *   "tables": ["db.t1"], "stmtTypes": ["DELETE","UPDATE"],
 *   "hitRules": [{"rule":"rule_delete_001","level":"FAILURE","message":"..."}]
 * }
 * }</pre>
 * <p>
 * Old rows without this field are null-safe (@JsonIgnoreProperties precedent from ApprovalMO).
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ImpactScope {

    private String  envName;
    private String  dsType;

    private Integer stmtCount;
    private Integer ddlCount;
    private Integer dmlCount;

    private Long    estimatedRows;
    private Boolean rowsEstimated;

    private List<String>     tables;
    private List<String>     stmtTypes;
    private List<HitRuleRef> hitRules;

    public void addTable(String table) {
        if (table == null || table.isEmpty()) {
            return;
        }
        if (this.tables == null) {
            this.tables = new ArrayList<>();
        }
        if (!this.tables.contains(table)) {
            this.tables.add(table);
        }
    }

    public void addStmtType(String type) {
        if (type == null || type.isEmpty()) {
            return;
        }
        if (this.stmtTypes == null) {
            this.stmtTypes = new ArrayList<>();
        }
        if (!this.stmtTypes.contains(type)) {
            this.stmtTypes.add(type);
        }
    }

    public void addHitRule(String rule, String level, String message) {
        if (rule == null || rule.isEmpty()) {
            return;
        }
        if (this.hitRules == null) {
            this.hitRules = new ArrayList<>();
        }
        this.hitRules.add(new HitRuleRef(rule, level, message));
    }

    public void merge(ImpactScope other) {
        if (other == null) {
            return;
        }
        if (this.stmtCount == null) {
            this.stmtCount = 0;
        }
        if (other.stmtCount != null) {
            this.stmtCount += other.stmtCount;
        }
        if (this.ddlCount == null) {
            this.ddlCount = 0;
        }
        if (other.ddlCount != null) {
            this.ddlCount += other.ddlCount;
        }
        if (this.dmlCount == null) {
            this.dmlCount = 0;
        }
        if (other.dmlCount != null) {
            this.dmlCount += other.dmlCount;
        }
        if (this.estimatedRows == null) {
            this.estimatedRows = 0L;
        }
        if (other.estimatedRows != null) {
            this.estimatedRows += other.estimatedRows;
        }
        // rowsEstimated stays true only if BOTH sides have rows estimated
        if (this.rowsEstimated == null) {
            this.rowsEstimated = other.rowsEstimated;
        } else if (other.rowsEstimated != null) {
            this.rowsEstimated = this.rowsEstimated && other.rowsEstimated;
        }
        if (other.tables != null) {
            for (String t : other.tables) {
                this.addTable(t);
            }
        }
        if (other.stmtTypes != null) {
            for (String t : other.stmtTypes) {
                this.addStmtType(t);
            }
        }
        if (other.hitRules != null) {
            for (HitRuleRef hr : other.hitRules) {
                this.addHitRule(hr.getRule(), hr.getLevel(), hr.getMessage());
            }
        }
    }

    @Getter
    @Setter
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class HitRuleRef {

        private String rule;
        private String level;
        private String message;

        public HitRuleRef() {
        }

        public HitRuleRef(String rule, String level, String message) {
            this.rule = rule;
            this.level = level;
            this.message = message;
        }
    }
}
