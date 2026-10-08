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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * B7 N2 (implement.md 延迟节流钉): the replica-lag probe contract — the dialect SQL FULL TEXT
 * (B4/B6 discipline: probe SQL is pinned verbatim, both dialects) + the pure parse matrix
 * (no replica = 0.0 visible / undecidable = null invisible / multi-source MAX / PG count
 * discrimination). The throttle decision itself (engage / hysteresis / degrade) is pinned at the
 * tick level in DmlBatchAsyncTaskTest.
 * <p>
 * Discipline: JUnit4, pure statics — no mocks needed.
 */
public class DmlBatchLagProbeTest {

    // ==================== SQL full-text pins (both dialects) ====================

    @Test
    public void mysqlLagSql_fullText() {
        assertEquals("SHOW REPLICA STATUS", DmlBatchLagProbe.MYSQL_LAG_SQL);
        assertEquals(DmlBatchLagProbe.MYSQL_LAG_SQL, DmlBatchLagProbe.lagSql(false));
    }

    @Test
    public void pgLagSql_fullText() {
        assertEquals("SELECT COUNT(*) AS replicas, MAX(EXTRACT(EPOCH FROM replay_lag)) AS lag_sec "
            + "FROM pg_stat_replication", DmlBatchLagProbe.PG_LAG_SQL);
        assertEquals(DmlBatchLagProbe.PG_LAG_SQL, DmlBatchLagProbe.lagSql(true));
    }

    // ==================== MySQL parse matrix ====================

    @Test
    public void mysql_noRows_noReplica_zeroVisible() {
        assertEquals(Double.valueOf(0.0), DmlBatchLagProbe.parseLagSeconds(List.of(), false));
    }

    @Test
    public void mysql_singleRow_parsesSeconds() {
        assertEquals(Double.valueOf(3.0),
            DmlBatchLagProbe.parseLagSeconds(List.of(Map.of("seconds_behind_source", "3")), false));
    }

    @Test
    public void mysql_nullSeconds_undecidable_invisible() {
        // Seconds_Behind_Source NULL (channel disconnected) → undecidable → the caller degrades
        java.util.Map<String, String> row = new java.util.HashMap<>();
        row.put("seconds_behind_source", null);
        assertNull(DmlBatchLagProbe.parseLagSeconds(List.of(row), false));
    }

    @Test
    public void mysql_multiSource_maxWins_nullChannelIgnored() {
        Map<String, String> broken = new java.util.HashMap<>();
        broken.put("seconds_behind_source", null);
        assertEquals(Double.valueOf(12.0), DmlBatchLagProbe.parseLagSeconds(
            List.of(Map.of("seconds_behind_source", "5"), broken, Map.of("seconds_behind_source", "12")), false));
    }

    @Test
    public void mysql_allChannelsNull_invisible() {
        Map<String, String> broken = new java.util.HashMap<>();
        broken.put("seconds_behind_source", null);
        assertNull(DmlBatchLagProbe.parseLagSeconds(List.of(broken, broken), false));
    }

    @Test
    public void mysql_slaveStatusColumnAlias_tolerated() {
        // older minors / proxies may surface the pre-8.0.22 column label
        assertEquals(Double.valueOf(7.0),
            DmlBatchLagProbe.parseLagSeconds(List.of(Map.of("seconds_behind_master", "7")), false));
    }

    @Test
    public void mysql_garbageValue_invisible_neverThrows() {
        assertNull(DmlBatchLagProbe.parseLagSeconds(List.of(Map.of("seconds_behind_source", "not-a-number")), false));
    }

    // ==================== PG parse matrix ====================

    @Test
    public void pg_noReplicas_zeroVisible() {
        assertEquals(Double.valueOf(0.0), DmlBatchLagProbe.parseLagSeconds(
            List.of(Map.of("replicas", "0")), true));
    }

    @Test
    public void pg_replicasWithLag_parsesSeconds() {
        assertEquals(Double.valueOf(2.5), DmlBatchLagProbe.parseLagSeconds(
            List.of(Map.of("replicas", "2", "lag_sec", "2.5")), true));
    }

    @Test
    public void pg_replicasPresent_lagNull_invisible() {
        // walsenders exist but replay_lag is undecidable → invisible (never a false "healthy 0")
        Map<String, String> row = new java.util.HashMap<>();
        row.put("replicas", "1");
        row.put("lag_sec", null);
        assertNull(DmlBatchLagProbe.parseLagSeconds(List.of(row), true));
    }

    @Test
    public void pg_emptyResultSet_treatedAsNoReplica() {
        assertEquals(Double.valueOf(0.0), DmlBatchLagProbe.parseLagSeconds(List.of(), true));
    }

    @Test
    public void pg_unparsableCount_invisible() {
        assertNull(DmlBatchLagProbe.parseLagSeconds(List.of(Map.of("replicas", "garbage")), true));
    }

    @Test
    public void nullRows_invisible_neverThrows() {
        assertNull(DmlBatchLagProbe.parseLagSeconds(null, false));
        assertNull(DmlBatchLagProbe.parseLagSeconds(null, true));
    }
}
