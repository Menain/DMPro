/*
 * Copyright 2026 杭州开云集致科技有限公司
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://apache.org/licenses/LICENSE-2.0
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
 * ADR-013 M1 (task 10-12-release-chain-frozen-routing): frozen segment-plan manifest
 * for production release statements + the stmt-level plan integrity pin.
 * <p>
 * - dm_prod_release_stmt_segment: per-release-stmt frozen copy of the source group's
 *   {@code precheck_result.segments} plan (propagated inside the createRelease/resubmit
 *   merge transaction, mirroring the dm_prod_release_stmt_schema lifecycle: batchInsert
 *   at merge, deleteByReleaseStmtId on returnStmt, deleteByReleaseId on
 *   returnRelease/handleRejected/handleCancelled). Plan columns (seg_idx..idempotent)
 *   are frozen at merge; runtime columns (status..finished_at) stay at their defaults
 *   until the M2 segment-aware dispatch consumes them. uk_rstmt_seg pins one row per
 *   (release_stmt_id, seg_idx); release_id is redundant for the cascade delete.
 *   Write condition mirrors the ticket-side carrier: rows exist ONLY when the source
 *   plan carries ≥1 routed (GHOST ∨ DML_BATCH) segment — pure-normal groups keep the
 *   null-plan legacy shape (dispatch byte-identical).
 * - dm_prod_release_stmt.plan_hash: canonical SHA-256 over the frozen manifest rows
 *   (ReleasePlanHashUtils single point); NULL = no plan (legacy/existing rows).
 *   M1 is WRITE-ONLY — no reader exists yet (M3 adds the plan-drift gate).
 * <p>
 * Pure addition; NO stock backfill (ADR-013 incidental ruling — existing/in-flight
 * releases keep null-plan legacy behavior); no historical migration is touched.
 * safeExecute ignores error codes 1050 (table exists) / 1060 (duplicate column) —
 * re-run safe. Rollback = DROP TABLE dm_prod_release_stmt_segment +
 * ALTER TABLE dm_prod_release_stmt DROP COLUMN plan_hash (transparent to older
 * binaries — neither is read by pre-M1 code).
 */
public class V202610120001__prod_release_stmt_segment extends AbstractUpgradeJavaMigration {

    @Override
    public List<String> collectScript() {
        return List.of("""
                CREATE TABLE IF NOT EXISTS dm_prod_release_stmt_segment
                (
                    id               bigint       NOT NULL AUTO_INCREMENT,
                    gmt_create       datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified     datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    release_id       bigint       NOT NULL,
                    release_stmt_id  bigint       NOT NULL,
                    seg_idx          int          NOT NULL,
                    type             varchar(32)  NOT NULL,
                    stmt_range_from  int          NULL,
                    stmt_range_to    int          NULL,
                    stmt_index       int          NULL,
                    table_name       varchar(192) NULL,
                    rows_estimated   bigint       NULL,
                    sql_text         longtext     NULL,
                    batch_key_column varchar(192) NULL,
                    batch_key_type   varchar(32)  NULL,
                    idempotent       tinyint      NULL,
                    status           varchar(16)  NOT NULL DEFAULT 'PENDING',
                    task_id          bigint       NULL,
                    session_id       varchar(127) NULL,
                    job_biz_id       varchar(64)  NULL,
                    summary          longtext     NULL,
                    error            text         NULL,
                    checkpoint       longtext     NULL,
                    started_at       bigint       NULL,
                    finished_at      bigint       NULL,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_rstmt_seg (release_stmt_id, seg_idx),
                    KEY idx_release (release_id)
                ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
                """, """
                ALTER TABLE dm_prod_release_stmt ADD COLUMN plan_hash varchar(64) NULL
                """);
    }
}
