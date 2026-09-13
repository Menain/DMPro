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
 * P3+ schema consistency (sub-task C): periodic pre/prod structure drift patrol.
 * <p>
 * Tables:
 * - dm_gov_schema_patrol_run: one row per patrol batch (AUTO or MANUAL trigger).
 *   status lifecycle: RUNNING -> DONE / PARTIAL / FAILED. retention cleanup keeps
 *   only the last N runs (govSchemaPatrolKeepRuns, default 30).
 * - dm_gov_schema_patrol_item: one row per (run, pair, table) that has a diff.
 *   diff_class = ONLY_PRE / ONLY_PROD / MISMATCH; explain_class =
 *   EXEMPT_ACTIVE / PENDING_RELEASE / UNEXPLAINED.
 * <p>
 * Pure addition; no existing tables or historical migrations are touched.
 * safeExecute ignores error codes 1050/1054/1060/1061/1062/1072/1091/1146.
 */
public class V202609130002__schema_drift_patrol extends AbstractUpgradeJavaMigration {

    @Override
    public List<String> collectScript() {
        return List.of("""
                CREATE TABLE IF NOT EXISTS dm_gov_schema_patrol_run
                (
                    id               bigint       NOT NULL AUTO_INCREMENT,
                    gmt_create       datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified     datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    trigger_type     varchar(16)  NOT NULL,
                    status           varchar(16)  NOT NULL,
                    pairs_total      int          NOT NULL DEFAULT 0,
                    pairs_done       int          NOT NULL DEFAULT 0,
                    unexplained_count int         NOT NULL DEFAULT 0,
                    error            varchar(1024) NULL,
                    started_by       varchar(127)  NULL,
                    PRIMARY KEY (id),
                    KEY idx_patrol_run_status (status),
                    KEY idx_patrol_run_trigger (trigger_type)
                ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
                """, """
                CREATE TABLE IF NOT EXISTS dm_gov_schema_patrol_item
                (
                    id              bigint       NOT NULL AUTO_INCREMENT,
                    gmt_create      datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified    datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    run_id          bigint       NOT NULL,
                    pair_id         bigint       NOT NULL,
                    pre_ds_id       bigint       NOT NULL,
                    pre_db_name     varchar(192) NOT NULL,
                    prod_ds_id      bigint       NOT NULL,
                    prod_db_name    varchar(192) NOT NULL,
                    table_name      varchar(192) NOT NULL,
                    diff_class      varchar(16)  NOT NULL,
                    explain_class   varchar(20)  NOT NULL,
                    explain_ref     varchar(128) NULL,
                    detail_json     longtext     NULL,
                    PRIMARY KEY (id),
                    KEY idx_patrol_item_run (run_id),
                    KEY idx_patrol_item_pair (pair_id)
                ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
                """);
    }
}
