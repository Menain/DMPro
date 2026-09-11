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
 * P2 of the governance ticket model refactor: creates the per-DB statement group table and
 * adds a nullable group-link column to dm_exec_auto_job.
 * <p>
 * Tables: dm_ticket_db_stmt (one row per mapped DB in a v2 ticket — precheck result, exec status).
 * Column: dm_exec_auto_job.depend_on_group_id (nullable, links v2 group jobs to their stmt group).
 * Pure addition; no existing tables or historical migrations are touched. safeExecute ignores
 * error codes 1050/1060/1061/1062 (table exists / column exists / index exists / duplicate key).
 */
public class V202609110002__gov_ticket_v2 extends AbstractUpgradeJavaMigration {

    @Override
    public List<String> collectScript() {
        return List.of("""
                CREATE TABLE IF NOT EXISTS dm_ticket_db_stmt
                (
                    id              bigint       NOT NULL AUTO_INCREMENT,
                    gmt_create      datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified    datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    ticket_id       bigint       NOT NULL,
                    pair_id         bigint       NOT NULL,
                    ds_id           bigint       NOT NULL,
                    db_name         varchar(192) NOT NULL,
                    sql_content     longtext     NOT NULL,
                    precheck_result text         NULL,
                    exec_status     varchar(16)  NOT NULL DEFAULT 'PENDING',
                    exec_detail     text         NULL,
                    PRIMARY KEY (id),
                    KEY idx_ticket (ticket_id),
                    KEY idx_ds_db (ds_id, db_name)
                ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
                """, """
                ALTER TABLE dm_exec_auto_job ADD COLUMN depend_on_group_id bigint NULL
                """, """
                CREATE UNIQUE INDEX uk_exec_auto_job_group ON dm_exec_auto_job (depend_on_group_id)
                """);
    }
}
