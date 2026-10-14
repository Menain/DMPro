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
package com.clougence.clouddm.init.component.scripts;

import java.util.List;

import com.clougence.clouddm.init.component.flyway.AbstractUpgradeJavaMigration;

/**
 * C8 M5.3 (task 10-13-audit-chain-enhancement, O-C7-6a / design D6.3): DB-level "at most ONE
 * active override" backstops for the two governed override tables — the service-level
 * check-then-insert kept its friendly rejection, but a concurrent double-grant inside the race
 * window could land TWO consumable rows (single-use CAS then burns only one). MySQL cannot
 * express a partial unique index (the V202610130001 head-note ruling), so both tables get a
 * STORED generated column that is non-NULL exactly while the row is ACTIVE — a plain UNIQUE
 * index over it ignores NULLs and therefore only constrains active rows.
 * <p>
 * dm_gov_route_override: {@code active_key = IF(status='PENDING', '<releaseId>:<stmtId>', NULL)}.
 * Expiry convergence: PENDING rows whose expire_at has passed are materialized to the stored
 * status 'EXPIRED' by the service BEFORE insert (expireStalePending) — without that convergence
 * an expired-but-PENDING row would hold its active_key forever and block a legitimate re-grant
 * (EXPIRED was derived-at-read-only pre-C8; the read-side deriveStatus is compatible with the
 * stored value by construction).
 * <p>
 * dm_gov_schema_gate_override: the flag-based twin has no status column, so this migration
 * additionally introduces {@code expired tinyint NOT NULL DEFAULT 0} — the materialized expiry
 * mark (grant() converges expired actives via expireStaleActive before inserting). This is a
 * DOCUMENTED COMPLEMENT to the design's "同款" sketch: without an expiry mark the unique key
 * would permanently block re-grants after an expiry. Key granularity includes gate_type (one
 * grant releases exactly one gate class — PRECHECK and DIFF stay independently grantable) and
 * distinguishes the two granularities: {@code '<gateType>:r:<releaseId>'} (release-level) /
 * {@code '<gateType>:s:<releaseId>:<stmtId>'} (stmt-level).
 * <p>
 * PRE-DEPLOYMENT CHECK (also in tests/governance/audit-legacy-cleanup.md §7): stock rows
 * duplicating an active_key make the UNIQUE creation fail with 1062 — which safeExecute IGNORES,
 * leaving the column present but the constraint silently absent. The liveness test of BOTH
 * generated columns deliberately does NOT read expire_at (a generated column may not use NOW()),
 * so the duplicate census must cover EVERY key-holding row — INCLUDING time-expired ones never
 * converged by a grant (route: status='PENDING'; gate: consumed=0 AND revoked=0 AND expired=0),
 * not just the clock-active ones. Run the census BEFORE first boot whenever possible; if the
 * version already ran with duplicates present it is recorded SUCCESS (safeExecute skipped the
 * 1062) and a plain restart will NOT retry it — converge the losers (time-expired rows: all of
 * them; clock-live duplicates: keep the newest id), then either delete this version's
 * dm_update_history row and restart (every statement here is idempotent — 1060/1061 are ignored,
 * only the missing UNIQUE is rebuilt) or apply the two ADD UNIQUE statements manually. See
 * tests/governance/audit-legacy-cleanup.md §7. Rollback = DROP the unique keys + the
 * generated/expired columns (pre-C8 binaries never read them).
 */
public class V202610130004__override_active_unique extends AbstractUpgradeJavaMigration {

    @Override
    public List<String> collectScript() {
        return List.of("""
                ALTER TABLE dm_gov_route_override ADD COLUMN active_key varchar(64) AS (IF(status = 'PENDING', CONCAT(release_id, ':', stmt_id), NULL)) STORED
                """, """
                ALTER TABLE dm_gov_route_override ADD UNIQUE KEY uk_route_override_active (active_key)
                """, """
                ALTER TABLE dm_gov_schema_gate_override ADD COLUMN expired tinyint NOT NULL DEFAULT 0
                """, """
                ALTER TABLE dm_gov_schema_gate_override ADD COLUMN active_key varchar(96) AS (IF(consumed = 0 AND revoked = 0 AND expired = 0, CONCAT(gate_type, ':', IF(stmt_id IS NULL, CONCAT('r:', release_id), CONCAT('s:', release_id, ':', stmt_id))), NULL)) STORED
                """, """
                ALTER TABLE dm_gov_schema_gate_override ADD UNIQUE KEY uk_gate_override_active (active_key)
                """);
    }
}
