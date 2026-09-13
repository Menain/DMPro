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
 * Ticket return-revise loop: version audit table for pre-modification SQL snapshots.
 * <p>
 * Each row captures the full old SQL text of a ticket stmt group before it is edited
 * during a resubmit cycle (REVISION -> WAIT_APPROVAL). Version numbers are application-
 * assigned (max+1 per stmt within the resubmit transaction); no UK is needed because
 * multiple revisions of the same stmt accumulate as separate rows.
 * <p>
 * Pure addition — no existing tables are touched. safeExecute ignores error code 1050
 * (table exists). Rollback = DROP TABLE (standalone, no FK references).
 */
public class V202609140001__ticket_stmt_revision extends AbstractUpgradeJavaMigration {

    @Override
    public List<String> collectScript() {
        return List.of("""
                CREATE TABLE IF NOT EXISTS dm_ticket_stmt_revision
                (
                    id              bigint       NOT NULL AUTO_INCREMENT,
                    gmt_create      datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified    datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    ticket_id       bigint       NOT NULL,
                    stmt_id         bigint       NOT NULL,
                    version         int          NOT NULL,
                    sql_content     longtext     NOT NULL,
                    operator_uid    varchar(127) NOT NULL,
                    reason          varchar(1024) NULL,
                    PRIMARY KEY (id),
                    KEY idx_ticket_stmt (ticket_id, stmt_id)
                ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
                """);
    }
}
