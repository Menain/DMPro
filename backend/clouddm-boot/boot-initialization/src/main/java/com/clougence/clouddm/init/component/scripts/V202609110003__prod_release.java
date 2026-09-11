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
 * P3 of the governance ledger/promotion: creates the production release tables
 * and adds a nullable release-stmt-link column to dm_exec_auto_job.
 * <p>
 * Tables:
 * - dm_prod_release (release header: release_no, status, approval link, gate_result)
 * - dm_prod_release_stmt (per-DB statement snapshot: hash, execution_key, seq, exec_status)
 * Column: dm_exec_auto_job.depend_on_release_stmt_id (nullable, links release-stmt jobs)
 * <p>
 * Pure addition; no existing tables or historical migrations are touched.
 * safeExecute ignores error codes 1050/1060/1061/1062 (table/column/index exists / dup key).
 */
public class V202609110003__prod_release extends AbstractUpgradeJavaMigration {

    @Override
    public List<String> collectScript() {
        return List.of("""
                CREATE TABLE IF NOT EXISTS dm_prod_release
                (
                    id              bigint       NOT NULL AUTO_INCREMENT,
                    gmt_create      datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified    datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    release_no      varchar(64)  NOT NULL,
                    title           varchar(255) NOT NULL,
                    status          varchar(32)  NOT NULL,
                    approval_id     bigint       NULL,
                    creator_uid     varchar(127) NOT NULL,
                    primary_uid     varchar(127) NOT NULL,
                    gate_result     longtext     NULL,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_release_no (release_no),
                    KEY idx_release_approval (approval_id),
                    KEY idx_release_primary (primary_uid)
                ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
                """, """
                CREATE TABLE IF NOT EXISTS dm_prod_release_stmt
                (
                    id                 bigint       NOT NULL AUTO_INCREMENT,
                    gmt_create         datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified        datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    release_id         bigint       NOT NULL,
                    prod_ds_id         bigint       NOT NULL,
                    prod_db_name       varchar(192) NOT NULL,
                    seq                int          NOT NULL,
                    sql_content        longtext     NOT NULL,
                    hash               varchar(64)  NOT NULL,
                    source_ticket_id   bigint       NOT NULL,
                    source_stmt_id     bigint       NOT NULL,
                    execution_key      varchar(64)  NOT NULL,
                    exec_status        varchar(16)  NOT NULL DEFAULT 'PENDING',
                    exec_detail       text         NULL,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_source_stmt (source_stmt_id),
                    UNIQUE KEY uk_execution_key (execution_key),
                    KEY idx_release_prod_db (release_id, prod_ds_id, prod_db_name, seq),
                    KEY idx_release_stmt_release (release_id)
                ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
                """, """
                ALTER TABLE dm_exec_auto_job ADD COLUMN depend_on_release_stmt_id bigint NULL
                """, """
                CREATE UNIQUE INDEX uk_exec_auto_job_release_stmt ON dm_exec_auto_job (depend_on_release_stmt_id)
                """, """
                ALTER TABLE dm_db_change_event ADD COLUMN release_id bigint NULL
                """, """
                CREATE INDEX idx_event_release ON dm_db_change_event (release_id)
                """);
    }
}
