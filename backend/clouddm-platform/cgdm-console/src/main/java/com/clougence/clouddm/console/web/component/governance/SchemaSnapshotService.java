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
package com.clougence.clouddm.console.web.component.governance;

import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;

/**
 * Pre-release schema snapshot collection service (sub-task A).
 * <p>
 * Captures the structural fingerprint of tables touched by a v2 ticket group stmt,
 * BEFORE the group job's SQL is dispatched to the pre-release DB. Snapshots are
 * best-effort: any failure (worker unreachable, timeout, etc.) does NOT block
 * the pre-release execution — the missing snapshot degrades to WARN at the
 * production diff gate.
 * <p>
 * first-capture-wins / fill-missing semantics: retryGroup only fills missing
 * snapshots, never replaces an existing CAPTURED row.
 */
public interface SchemaSnapshotService {

    /**
     * Capture pre-before schema snapshots for a ticket group stmt.
     * <p>
     * Extracts table names from the stmt's SQL, fetches the live structure from the
     * pre-release DB (via the pair's preDsId/preDbName), normalizes to a canonical
     * JSON + fingerprint, and persists to dm_ticket_stmt_schema.
     * <p>
     * first-capture-wins: if a CAPTURED row already exists for (stmt_id, table_name),
     * it is NOT replaced. This ensures retryGroup captures the correct pre-before state
     * (the first attempt's), not a post-partial-execution polluted state.
     * <p>
     * Degradation: any exception is caught and logged — the caller's main flow
     * (createGroupJob/startJob) proceeds regardless.
     *
     * @param stmt   the ticket group stmt (pairId, dsId, dbName, sqlContent)
     * @param ticketId the ticket id
     */
    void captureIfMissing(DmTicketDbStmtDO stmt, long ticketId);
}
