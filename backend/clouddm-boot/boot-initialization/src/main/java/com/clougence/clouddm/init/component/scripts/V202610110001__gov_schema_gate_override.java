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
 * ADR-011 (task 10-11-schema-gate-fail-closed): governed override records for the
 * production release schema gates (precheck + diff). A gate denial (BLOCK or UNVERIFIABLE)
 * can be downgraded to pass by consuming an unexpired, unconsumed, unrevoked override row
 * granted through the GOV_SCHEMA_GATE_OVERRIDE permission with a mandatory reason and a
 * time-boxed expiry (≤ 7 days, service-enforced). Consumption is SINGLE-USE via a CAS
 * update (consumed 0→1), aligned with the DML_BATCH_RESOLUTION "an unconsumed ruling
 * re-freezes" strictness.
 * <p>
 * stmt_id NULL = release-level override (covers any stmt of the release); non-null =
 * stmt-level (matched first). gate_type ∈ {PRECHECK, DIFF} — one grant releases exactly
 * one gate class; both gates denied requires two grants. Hash drift is NOT overridable
 * (integrity, out of this table's scope by design).
 * <p>
 * Pure addition; no existing tables or historical migrations are touched.
 * safeExecute ignores error codes 1050/1054/1060/1061/1062/1072/1091/1146.
 */
public class V202610110001__gov_schema_gate_override extends AbstractUpgradeJavaMigration {

    @Override
    public List<String> collectScript() {
        return List.of("""
                CREATE TABLE IF NOT EXISTS dm_gov_schema_gate_override
                (
                    id               bigint        NOT NULL AUTO_INCREMENT,
                    gmt_create       datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified     datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    release_id       bigint        NOT NULL,
                    stmt_id          bigint        NULL,
                    gate_type        varchar(32)   NOT NULL,
                    operator_uid     varchar(127)  NOT NULL,
                    reason           varchar(1024) NOT NULL,
                    expire_at        datetime      NOT NULL,
                    consumed         tinyint       NOT NULL DEFAULT 0,
                    consumed_at      datetime      NULL,
                    consumed_detail  varchar(512)  NULL,
                    revoked          tinyint       NOT NULL DEFAULT 0,
                    revoked_at       datetime      NULL,
                    revoked_by       varchar(127)  NULL,
                    PRIMARY KEY (id),
                    KEY idx_gate_override_release (release_id, consumed, expire_at)
                ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
                """);
    }
}
