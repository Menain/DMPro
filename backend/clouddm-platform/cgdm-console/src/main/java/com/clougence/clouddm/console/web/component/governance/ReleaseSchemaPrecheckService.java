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
package com.clougence.clouddm.console.web.component.governance;

import java.util.Set;

import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseStmtDO;

/**
 * Production schema precondition precheck service for release statements.
 * <p>
 * Before a frozen release stmt is dispatched to production execution, this service
 * splits the stmt's SQL, classifies each operation, extracts table/column/index names,
 * fetches live production schema, and evaluates per-operation preconditions.
 * <p>
 * Design principle (ADR-011, fail-closed — SUPERSEDES the former 宁漏勿误 fail-open regime):
 * cannot prove safe ⇒ do not dispatch. Parsing failures, unsupported syntax, and infrastructure
 * errors produce UNVERIFIABLE (denial, distinct from confirmed-violation BLOCK); extraction
 * gaps and unclassifiable scripts (S1~S5) produce PASS carrying UNCERTAIN {@code Note}s.
 * WARN_SKIP is only reachable through the platform break-glass switch
 * (govReleaseSchemaPrecheckBlock=false degrading UNVERIFIABLE at the single engine exit).
 * <p>
 * The caller is responsible for the gate disposal (stmt FAILED on isDeny(), governed-override
 * consumption, and events: RELEASE_SCHEMA_PRECHECK_FAILED / RELEASE_SCHEMA_GATE_UNVERIFIABLE /
 * RELEASE_SCHEMA_PRECHECK_UNCERTAIN / RELEASE_SCHEMA_GATE_OVERRIDE).
 * The service itself is read-only and never throws — all exceptions are caught and
 * converted to UNVERIFIABLE internally.
 */
public interface ReleaseSchemaPrecheckService {

    /**
     * Run the schema precondition precheck for a single release stmt.
     *
     * @param stmt the frozen release statement (prodDsId, prodDbName, sqlContent are the key fields)
     * @return PASS (optionally with Notes) / BLOCK / UNVERIFIABLE conclusion; never null, never throws
     */
    PrecheckConclusion precheck(DmProdReleaseStmtDO stmt);

    /**
     * Extract the set of table names touched by the SQL content.
     * Used by sub-task A for snapshot collection scope and shared-table detection.
     * Returns an empty set if SQL cannot be split or no tables are found.
     *
     * @param sqlContent the SQL content (may contain multiple statements)
     * @return set of table names (last component, backtick-stripped); never null
     */
    Set<String> extractTables(String sqlContent);
}
