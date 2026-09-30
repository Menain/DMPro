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
 * B6 M2 (mixed-statement segment orchestration): segment RUNTIME state column on
 * dm_ticket_db_stmt — a nullable JSON array
 * [{segIdx,type,status,jobBizId?,sessionId?,taskId?,error?,startedAt?,finishedAt?,summary?}]
 * mutated ONLY inside the advanceSegment ticket-row-lock window (same table, same row and
 * same transaction as exec_status). The segment PLAN stays in the precheck_result JSON
 * ("segments" additive key, recomputed by every authoritative precheck run).
 * <p>
 * Pure addition — no existing tables or columns are touched. safeExecute ignores error code
 * 1060 (duplicate column). Rollback = ALTER TABLE dm_ticket_db_stmt DROP COLUMN exec_segments
 * (transparent to older code — the column is never read by pre-M2 binaries).
 */
public class V202609290001__ticket_stmt_exec_segments extends AbstractUpgradeJavaMigration {

    @Override
    public List<String> collectScript() {
        return List.of("""
                ALTER TABLE dm_ticket_db_stmt ADD COLUMN exec_segments text NULL
                """);
    }
}
