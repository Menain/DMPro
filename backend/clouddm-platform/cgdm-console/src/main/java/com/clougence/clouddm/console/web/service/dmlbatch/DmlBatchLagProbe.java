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
package com.clougence.clouddm.console.web.service.dmlbatch;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.clougence.utils.StringUtils;

/**
 * B7 N2 replica-lag throttle probe (design §4.1/§6.3, R3 — org spec §9.8.2 "延迟 >10 秒暂停
 * 评估"): the SQL constants + the pure result parsing of the per-tick lag probe that rides the
 * batch tick's OWN session (每 tick 同会话顺带探测 — zero extra round trips).
 * <p>
 * Probe shapes (full-text pinned by unit tests, both dialects):
 * <ul>
 * <li>MySQL: {@code SHOW REPLICA STATUS} → {@code Seconds_Behind_Source} (requires the
 *     REPLICATION CLIENT grant; multi-source replication = multiple rows → the MAX of the
 *     parsable values wins). An EMPTY result = the endpoint replicates nothing = no lag concern
 *     (0.0). A row with a NULL/blank Seconds_Behind_Source (channel not connected) is
 *     UNDECIDABLE → invisible (the throttle degrades to the fixed interval — never-throw
 *     direction, {@code DML_BATCH_LAG_UNKNOWN} copy);</li>
 * <li>PG 15: {@code pg_stat_replication} (requires REPLICATION or pg_monitor) —
 *     {@code COUNT(*)} discriminates "no walsender" (0 replicas → 0.0) from "replicas present
 *     but replay_lag undecidable" (MAX NULL → invisible); {@code EXTRACT(EPOCH …)} renders the
 *     interval as seconds.</li>
 * </ul>
 * Parse contract: returns the lag in seconds, {@code 0.0} for "probed, no replica", or
 * {@code null} for INVISIBLE (undecidable) — the caller (the tick) never throws on either shape
 * and records the outcome on {@code checkpoint.lagState} (visible/lastLagSec). Permission
 * failures surface as statement errors / exceptions at the caller and land on the same invisible
 * degradation (the probe is a throttle aid, never a batch-failure source).
 * <p>
 * MySQL version note: {@code SHOW REPLICA STATUS} exists since 8.0.22 (the routing gate's floor
 * is 8.0); an older minor rejects the statement → the caller's never-throw degradation → the
 * fixed-interval throttle with the "lag invisible" yellow flag. Deliberately NO fallback to
 * {@code SHOW SLAVE STATUS} (deprecated alias, one probe shape per dialect keeps the pin matrix
 * honest).
 */
public final class DmlBatchLagProbe {

    private DmlBatchLagProbe() {
    }

    /** MySQL 8.0.22+ replica status (Seconds_Behind_Source; column labels arrive lowercased). */
    public static final String MYSQL_LAG_SQL = "SHOW REPLICA STATUS";

    /**
     * PG 15 walsender lag: replicas=0 → no replica; lag_sec = MAX replay_lag in seconds
     * (NULL across present replicas → undecidable).
     */
    public static final String PG_LAG_SQL = "SELECT COUNT(*) AS replicas, "
        + "MAX(EXTRACT(EPOCH FROM replay_lag)) AS lag_sec FROM pg_stat_replication";

    /** the dialect probe SQL (the tick runs it on the batch session BEFORE the intent write). */
    public static String lagSql(boolean postgres) {
        return postgres ? PG_LAG_SQL : MYSQL_LAG_SQL;
    }

    /**
     * Pure parse of one probe result (rows = DiagResultUtils.extractRows — column-lowercased).
     *
     * @return lag seconds ≥ 0 (0.0 = probed, no replica), or null = INVISIBLE/undecidable
     */
    public static Double parseLagSeconds(List<Map<String, String>> rows, boolean postgres) {
        if (rows == null) {
            return null;
        }
        if (postgres) {
            if (rows.isEmpty()) {
                return 0.0; // no pg_stat_replication row at all — treated as no replica
            }
            Map<String, String> row = rows.get(0);
            Long replicas = parseLong(row.get("replicas"));
            if (replicas == null) {
                return null; // unparsable count — undecidable
            }
            if (replicas == 0L) {
                return 0.0; // no walsender = nothing replicating = no lag concern
            }
            Double lag = parseDouble(row.get("lag_sec"));
            // replicas present but replay_lag NULL/unparsable → undecidable → invisible
            return lag != null && lag >= 0 ? lag : null;
        }
        if (rows.isEmpty()) {
            return 0.0; // SHOW REPLICA STATUS empty = the endpoint is not replicating anything
        }
        // multi-source replication: one row per channel — the MAX of the parsable values wins;
        // a NULL Seconds_Behind_Source row (channel disconnected) contributes nothing, and if
        // NO channel yields a number the lag is undecidable → invisible
        Double max = null;
        for (Map<String, String> row : rows) {
            Double lag = parseDouble(firstNonNull(row.get("seconds_behind_source"), row.get("seconds_behind_master")));
            if (lag != null && lag >= 0 && (max == null || lag > max)) {
                max = lag;
            }
        }
        return max;
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    private static Long parseLong(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double parseDouble(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            return Double.parseDouble(value.trim().toLowerCase(Locale.ROOT));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
