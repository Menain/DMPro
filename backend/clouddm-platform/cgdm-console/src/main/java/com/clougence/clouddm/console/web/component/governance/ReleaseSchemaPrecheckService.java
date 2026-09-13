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

import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseStmtDO;

/**
 * Production schema precondition precheck service for release statements.
 * <p>
 * Before a frozen release stmt is dispatched to production execution, this service
 * splits the stmt's SQL, classifies each operation, extracts table/column/index names,
 * fetches live production schema, and evaluates per-operation preconditions.
 * <p>
 * Design principle: 宁漏勿误 — parsing failures, unsupported syntax, and infrastructure
 * errors produce WARN_SKIP (never BLOCK). Only confirmed precondition violations produce BLOCK.
 * <p>
 * The caller is responsible for writing stmt status (FAILED on BLOCK) and events
 * (RELEASE_SCHEMA_PRECHECK_FAILED / RELEASE_SCHEMA_PRECHECK_SKIPPED).
 * The service itself is read-only and never throws — all exceptions are caught and
 * converted to WARN_SKIP internally.
 */
public interface ReleaseSchemaPrecheckService {

    /**
     * Run the schema precondition precheck for a single release stmt.
     *
     * @param stmt the frozen release statement (prodDsId, prodDbName, sqlContent are the key fields)
     * @return PASS / BLOCK / WARN_SKIP conclusion; never null, never throws
     */
    PrecheckConclusion precheck(DmProdReleaseStmtDO stmt);
}
