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
 * P3+ schema consistency (sub-task A): pre-execution structure snapshots and
 * production drift diff audit.
 * <p>
 * Tables:
 * - dm_ticket_stmt_schema: pre-release side snapshot (captured before a v2 group
 *   job's SQL lands on the pre-release DB). One row per (stmt_id, table_name).
 *   first-capture-wins via uk_stmt_table (fill-missing on retryGroup).
 * - dm_prod_release_stmt_schema: frozen copy propagated at createRelease time.
 *   shared_flag marks tables touched by >1 stmt within the same (prod_ds, prod_db)
 *   group — shared tables are degraded to WARN (ordered replay makes pre-before
 *   baseline ambiguous for shared tables).
 * <p>
 * Pure addition; no existing tables or historical migrations are touched.
 * safeExecute ignores error codes 1050/1054/1060/1061/1062/1072/1091/1146.
 */
public class V202609130001__schema_snapshot_diff extends AbstractUpgradeJavaMigration {

    @Override
    public List<String> collectScript() {
        return List.of("""
                CREATE TABLE IF NOT EXISTS dm_ticket_stmt_schema
                (
                    id              bigint       NOT NULL AUTO_INCREMENT,
                    gmt_create      datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified    datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    ticket_id        bigint       NOT NULL,
                    stmt_id          bigint       NOT NULL,
                    ds_id            bigint       NOT NULL,
                    db_name          varchar(192) NOT NULL,
                    table_name       varchar(192) NOT NULL,
                    exists_flag      tinyint       NOT NULL DEFAULT 1,
                    schema_json      longtext     NULL,
                    fingerprint      varchar(64)  NOT NULL,
                    capture_status   varchar(32)  NOT NULL DEFAULT 'CAPTURED',
                    capture_error    varchar(512) NULL,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_stmt_table (stmt_id, table_name),
                    KEY idx_ticket (ticket_id)
                ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
                """, """
                CREATE TABLE IF NOT EXISTS dm_prod_release_stmt_schema
                (
                    id              bigint       NOT NULL AUTO_INCREMENT,
                    gmt_create      datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified    datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    release_id       bigint       NOT NULL,
                    release_stmt_id  bigint       NOT NULL,
                    prod_ds_id       bigint       NOT NULL,
                    prod_db_name     varchar(192) NOT NULL,
                    table_name       varchar(192) NOT NULL,
                    exists_flag      tinyint       NOT NULL DEFAULT 1,
                    schema_json      longtext     NULL,
                    fingerprint      varchar(64)  NOT NULL,
                    shared_flag      tinyint       NOT NULL DEFAULT 0,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_rstmt_table (release_stmt_id, table_name),
                    KEY idx_release (release_id)
                ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
                """);
    }
}
