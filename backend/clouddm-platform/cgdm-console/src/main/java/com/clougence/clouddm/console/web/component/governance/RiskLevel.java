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

/**
 * SQL change risk level — 4 tiers used for external approval routing and internal display.
 * <p>
 * Assessment = max(statement-type level, precheck-hit level, row-count level) per design D1.
 * Stored as the enum name string in ApprovalMO.riskLevel (zero DDL, approvalMode precedent).
 */
public enum RiskLevel {

    LOW,
    MEDIUM,
    HIGH,
    CRITICAL;

    /**
     * Returns the highest (most dangerous) level among the given non-null values.
     * Null inputs are skipped (treated as absent). If all inputs are null, returns {@link #LOW}.
     */
    public static RiskLevel max(RiskLevel... levels) {
        RiskLevel result = LOW;
        if (levels == null) {
            return result;
        }
        for (RiskLevel level : levels) {
            if (level == null) {
                continue;
            }
            if (level.ordinal() > result.ordinal()) {
                result = level;
            }
        }
        return result;
    }
}
