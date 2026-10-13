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
 * ADR-013 M3 (task 10-12-release-chain-frozen-routing): governed ROUTE override records for
 * the production release frozen-plan dispatch (design D8, 裁决 4 — a route-dedicated override
 * PARALLEL to, and fully isolated from, the ADR-011 schema-gate override: two tables, two
 * permission labels, two event types, no cross-substitution).
 * <p>
 * One row = one grant: a permissioned DBA (GOV_ROUTE_OVERRIDE label) records a mandatory
 * reason (≤ 1024) and a time-boxed expiry (≤ 7 days, service-enforced) for ONE release stmt
 * (granularity (release_id, stmt_id) — stmt_id is NOT NULL, no release-level blanket). The
 * two route-block consumption points (a DML_BATCH segment's RELEASE_ROUTE_UNSUPPORTED block
 * ∨ a GHOST segment's RELEASE_ROUTE_CAPABILITY failure) consume it SINGLE-USE (CAS
 * status PENDING → CONSUMED) to release the blocked segment as a DIRECT single-statement
 * AUTOEXEC replay — an explicit lock/timeout risk acceptance, never a silent downgrade.
 * covered_seg_idxs freezes the released segIdx list at consumption (audit trail; the
 * RELEASE_ROUTE_OVERRIDE event + the HIGH op_audit row are the twin trails).
 * <p>
 * PLAN DRIFT (RELEASE_PLAN_DRIFT) AND TEXT HASH DRIFT ARE NOT OVERRIDABLE — integrity
 * classes, out of this table's scope by design (the text-hash-drift twin).
 * <p>
 * status ∈ {PENDING, CONSUMED} in the DB; EXPIRED is derived at read time (expire_at ≤ now
 * while still PENDING — the C4 schema-gate override's derived-status pattern, no sweeper).
 * Active uniqueness ("at most one PENDING per (release_id, stmt_id)") is enforced at the
 * SERVICE level (create rejects while a consumable row exists) — the C4 migration shape
 * verbatim: no DB unique constraint (MySQL cannot express a partial unique index over
 * "status = PENDING"), a plain secondary index serves the consumable lookup.
 * <p>
 * Pure addition; no existing tables or historical migrations are touched.
 * safeExecute ignores error code 1050 (table exists) — re-run safe.
 * Rollback = DROP TABLE dm_gov_route_override (transparent — pre-M3 binaries never read it).
 */
public class V202610130001__gov_route_override extends AbstractUpgradeJavaMigration {

    @Override
    public List<String> collectScript() {
        return List.of("""
                CREATE TABLE IF NOT EXISTS dm_gov_route_override
                (
                    id               bigint        NOT NULL AUTO_INCREMENT,
                    gmt_create       datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified     datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    release_id       bigint        NOT NULL,
                    stmt_id          bigint        NOT NULL,
                    reason           varchar(1024) NOT NULL,
                    created_by       varchar(127)  NOT NULL,
                    status           varchar(16)   NOT NULL DEFAULT 'PENDING',
                    expire_at        datetime      NOT NULL,
                    consumed_at      datetime      NULL,
                    covered_seg_idxs varchar(256)  NULL,
                    PRIMARY KEY (id),
                    KEY idx_route_override_release (release_id, status, expire_at)
                ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
                """);
    }
}
