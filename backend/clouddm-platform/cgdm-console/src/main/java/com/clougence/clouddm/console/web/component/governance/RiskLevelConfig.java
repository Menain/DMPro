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

import com.clougence.utils.JsonUtils;
import com.clougence.utils.StringUtils;

import lombok.Getter;
import lombok.Setter;

/**
 * Row-count thresholds for the row-dimension of risk assessment (design D1).
 * <p>
 * Defaults: mediumRows=1000, highRows=100000, criticalRows=1000000.
 * Overridable per-env via env param {@code SQL_RISK_LEVEL_CONFIG} (JSON).
 * Missing/invalid segments fall back to defaults (fault-tolerant, per governance-contracts precedent).
 */
@Getter
@Setter
public class RiskLevelConfig {

    public static final String ENV_PARAM_KEY = "sql_risk_level_config";

    public static final long DEFAULT_MEDIUM_ROWS   = 1_000L;
    public static final long DEFAULT_HIGH_ROWS     = 100_000L;
    public static final long DEFAULT_CRITICAL_ROWS = 1_000_000L;

    private long mediumRows   = DEFAULT_MEDIUM_ROWS;
    private long highRows     = DEFAULT_HIGH_ROWS;
    private long criticalRows = DEFAULT_CRITICAL_ROWS;

    /**
     * Parses config from an env-param JSON string. Missing or invalid fields use defaults;
     * a blank string yields the default config.
     */
    public static RiskLevelConfig fromEnvParam(String json) {
        RiskLevelConfig config = new RiskLevelConfig();
        if (StringUtils.isBlank(json)) {
            return config;
        }
        try {
            RiskLevelConfig parsed = JsonUtils.toObj(json, RiskLevelConfig.class);
            if (parsed.mediumRows > 0) {
                config.mediumRows = parsed.mediumRows;
            }
            if (parsed.highRows > 0) {
                config.highRows = parsed.highRows;
            }
            if (parsed.criticalRows > 0) {
                config.criticalRows = parsed.criticalRows;
            }
        } catch (Exception ignored) {
            // fault-tolerant: invalid JSON → all defaults
        }
        return config;
    }

    /**
     * Row-dimension risk level. null or non-positive rows → LOW (no upgrade).
     * Boundaries: rows == threshold → that threshold's level (e.g. rows==1000 → MEDIUM).
     */
    public RiskLevel levelByRows(Long estimatedRows) {
        if (estimatedRows == null || estimatedRows <= 0) {
            return RiskLevel.LOW;
        }
        if (estimatedRows > criticalRows) {
            return RiskLevel.CRITICAL;
        }
        if (estimatedRows > highRows) {
            return RiskLevel.HIGH;
        }
        if (estimatedRows > mediumRows) {
            return RiskLevel.MEDIUM;
        }
        return RiskLevel.LOW;
    }
}
