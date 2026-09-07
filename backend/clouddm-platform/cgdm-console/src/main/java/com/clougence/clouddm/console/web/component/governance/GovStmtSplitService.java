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

import com.clougence.clouddm.base.metadata.ds.DataSourceConfig;

/**
 * Governance SQL split + change-type classification service.
 * Reuses the same parser as the execution engine (QueryAnalysisService.analysisSplitStream)
 * to guarantee stmt_index alignment with dm_exec_auto_task.exec_order (design D3).
 *
 * Non-DDL/DML statements (SELECT, SHOW, etc.) are rejected — the governance link
 * only accepts DDL and DML (design D5).
 */
public interface GovStmtSplitService {

    /**
     * Split SQL text into statements and classify the change type.
     *
     * @param dsConfig target datasource config (dialect-specific parser)
     * @param sqlText   raw SQL text
     * @return split result with change type and per-statement rows (stmtIndex from 1)
     * @throws com.clougence.clouddm.api.common.exception.ErrorMessageException if SQL is empty
     *         or contains non-DDL/DML statements
     */
    GovSplitResult split(DataSourceConfig dsConfig, String sqlText);
}
