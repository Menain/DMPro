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

import com.clougence.utils.StringUtils;

import lombok.extern.slf4j.Slf4j;

/**
 * Parsed GOV_DML_ROW_LIMIT configuration: {@code warn:1000,block:100000}.
 * <p>
 * Fault-tolerance: missing/invalid segment → that level is treated as unconfigured (null).
 * Null/blank raw string → overall unconfigured (both null).
 * Parse failures log a warning, never throw.
 */
@Slf4j
public final class GovRowLimitConfig {

    private final Long warn;
    private final Long block;

    private GovRowLimitConfig(Long warn, Long block) {
        this.warn = warn;
        this.block = block;
    }

    public Long getWarn() { return warn; }

    public Long getBlock() { return block; }

    public boolean isConfigured() {
        return warn != null || block != null;
    }

    public boolean shouldBlock(long rows) {
        return block != null && rows > block;
    }

    public boolean shouldWarn(long rows) {
        if (warn == null || block == null) {
            return false;
        }
        return rows > warn && rows <= block;
    }

    public static GovRowLimitConfig parse(String raw) {
        if (StringUtils.isBlank(raw)) {
            return new GovRowLimitConfig(null, null);
        }
        Long warn = null;
        Long block = null;
        for (String segment : raw.split(",")) {
            String trimmed = segment.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon < 0 || colon == trimmed.length() - 1) {
                log.warn("[GovDmlThreshold] ignoring malformed segment: '{}'", trimmed);
                continue;
            }
            String key = trimmed.substring(0, colon).trim().toLowerCase();
            String valStr = trimmed.substring(colon + 1).trim();
            try {
                long val = Long.parseLong(valStr);
                if (val < 0) {
                    log.warn("[GovDmlThreshold] ignoring negative value for '{}': {}", key, val);
                    continue;
                }
                if ("warn".equals(key)) {
                    warn = val;
                } else if ("block".equals(key)) {
                    block = val;
                } else {
                    log.warn("[GovDmlThreshold] unknown key '{}', ignoring", key);
                }
            } catch (NumberFormatException e) {
                log.warn("[GovDmlThreshold] non-numeric value for '{}': '{}'", key, valStr);
            }
        }
        return new GovRowLimitConfig(warn, block);
    }
}
