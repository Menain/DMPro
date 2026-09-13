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

import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseStmtDO;

/**
 * Production schema drift diff engine (sub-task A).
 * <p>
 * For each frozen release stmt, compares the pre-before snapshot (captured on
 * the pre-release DB before the group job's SQL landed) against the live
 * production structure, detecting out-of-band drift (e.g. a DBA manually altered
 * the production table after the pre-release execution).
 * <p>
 * Semantics:
 * <ul>
 *   <li><b>Exclusive table</b> (touched by only this stmt in its release group):
 *       full equivalence diff — fingerprint equality ⇔ BLOCK-level structural
 *       equality. BLOCK-level differences → BLOCK. WARN-level only (ENGINE,
 *       comment) → WARN (pass with warning).</li>
 *   <li><b>Shared table</b> (touched by >1 stmt in the group): degraded to WARN
 *       — ordered replay makes the pre-before baseline ambiguous.</li>
 *   <li><b>No snapshot</b>: WARN_SKIP (RELEASE_SCHEMA_SNAPSHOT_MISSING event).</li>
 *   <li><b>exists_flag=0</b>: production should not have the table — existence → BLOCK.</li>
 * </ul>
 * <p>
 * Design principle: 宁漏勿误 — any exception or timeout → WARN_SKIP, never BLOCK.
 * Escape hatch: govReleaseSchemaDiffBlock=false → BLOCK degrades to WARN.
 */
public interface ReleaseSchemaDiffService {

    /**
     * Run the schema drift diff for a single release stmt.
     *
     * @param stmt the frozen release statement (prodDsId, prodDbName, sqlContent, id)
     * @return PASS / BLOCK / WARN_SKIP conclusion; never null, never throws
     */
    PrecheckConclusion diff(DmProdReleaseStmtDO stmt);
}
