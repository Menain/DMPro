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
 * C8 M1 (task 10-13-audit-chain-enhancement, design D1): retrieval foundation for the
 * operation-audit table — dm_mon_op_audit has carried ONLY its PRIMARY KEY since the
 * V202605070001 creation (every rename/column migration added zero secondary indexes), so
 * every audit-page query dimension (owner_uid / operate_date range / audit_type /
 * resource_type — the operationAuditCondition predicate) is a full table scan.
 * <p>
 * Three indexes matching the existing predicate shape:
 * <ul>
 * <li>idx_owner_date (owner_uid, operate_date) — the mandatory tenant filter + the
 *     default time-range window (also the retention-cleanup scan path, C8 M1 D2);</li>
 * <li>idx_audit_type (audit_type) — the action filter dropdown;</li>
 * <li>idx_resource_type (resource_type) — the resource-type filter dropdown.</li>
 * </ul>
 * The M2 chain columns (sql_fingerprint / ticket_id / release_id / channel) carry their own
 * indexes in V202610130003 — deliberately NOT here (each migration stays single-purpose).
 * <p>
 * MySQL 8.0 ADD INDEX is INPLACE/online; the audit table keeps taking writes, so low-peak
 * deployment is recommended (survey D16-1: marginal insert cost of 3 indexes on a
 * write-heavy/read-light audit table is acceptable, measured at integration time).
 * Pure addition; no historical migration is touched; safeExecute ignores 1061
 * (duplicate key name) — re-run safe. Rollback = DROP INDEX on dm_mon_op_audit.
 */
public class V202610130002__op_audit_indexes extends AbstractUpgradeJavaMigration {

    @Override
    public List<String> collectScript() {
        return List.of("""
                ALTER TABLE dm_mon_op_audit ADD KEY idx_owner_date (owner_uid, operate_date)
                """, """
                ALTER TABLE dm_mon_op_audit ADD KEY idx_audit_type (audit_type)
                """, """
                ALTER TABLE dm_mon_op_audit ADD KEY idx_resource_type (resource_type)
                """);
    }
}
