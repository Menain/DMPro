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
 * C8 M2 (task 10-13-audit-chain-enhancement, design D3 — 裁决 2 four-column plan): the
 * searchable audit-chain join keys as REAL indexed columns (the ticket/SQL/channel relations
 * used to live only inside the unindexed, unexported log_info JSON — the survey B6-B10 gap).
 * <p>
 * dm_mon_op_audit gains four NULLABLE columns (existing rows stay NULL — the plan_hash-null
 * legacy precedent; no stock backfill):
 * <ul>
 * <li>sql_fingerprint varchar(64) — GovSqlHashUtils normalize+SHA-256 over the FULL frozen
 *     statement text (the governance stmt_hash / release hash caliber — an equality join to
 *     the governance chain by construction; NEVER a second hash implementation);</li>
 * <li>ticket_id bigint — the dm_approval ticket the audited action belongs to;</li>
 * <li>release_id bigint — the dm_prod_release the audited action belongs to (ticket_id and
 *     release_id are mutually exclusive per row — the two dispatch chains);</li>
 * <li>channel varchar(16) — AuditChannel enum name (CONSOLE / OPENAPI / SYSTEM_AUTO), the
 *     "through which channel" dimension. NO index (low cardinality; audit_type+channel
 *     filtering rides idx_audit_type first).</li>
 * </ul>
 * Indexes: idx_fingerprint / idx_ticket / idx_release.
 * <p>
 * dm_exec_sql_audit gains sql_fingerprint varchar(64) + idx_sql_fingerprint — computed at the
 * prepareAudit write point over the full statement text (existing rows NULL, no backfill),
 * giving the SQL-level trail the same join key as the governance chain.
 * <p>
 * Pure addition; no historical migration is touched; safeExecute ignores 1060 (duplicate
 * column) / 1061 (duplicate key name) — re-run safe. MySQL 8.0 ADD COLUMN (nullable, no
 * default backfill) / ADD INDEX are INPLACE-online; low-peak deployment recommended for the
 * continuously-written audit tables (survey D16-1). Rollback = DROP COLUMN / DROP INDEX
 * (pre-C8 binaries never read the columns).
 */
public class V202610130003__audit_chain_columns extends AbstractUpgradeJavaMigration {

    @Override
    public List<String> collectScript() {
        return List.of("""
                ALTER TABLE dm_mon_op_audit ADD COLUMN sql_fingerprint varchar(64) NULL
                """, """
                ALTER TABLE dm_mon_op_audit ADD COLUMN ticket_id bigint NULL
                """, """
                ALTER TABLE dm_mon_op_audit ADD COLUMN release_id bigint NULL
                """, """
                ALTER TABLE dm_mon_op_audit ADD COLUMN channel varchar(16) NULL
                """, """
                ALTER TABLE dm_mon_op_audit ADD KEY idx_fingerprint (sql_fingerprint)
                """, """
                ALTER TABLE dm_mon_op_audit ADD KEY idx_ticket (ticket_id)
                """, """
                ALTER TABLE dm_mon_op_audit ADD KEY idx_release (release_id)
                """, """
                ALTER TABLE dm_exec_sql_audit ADD COLUMN sql_fingerprint varchar(64) NULL
                """, """
                ALTER TABLE dm_exec_sql_audit ADD KEY idx_sql_fingerprint (sql_fingerprint)
                """);
    }
}
