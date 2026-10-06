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
package com.clougence.clouddm.sdk.sql.editor.rewrite;

import com.clougence.clouddm.sdk.sql.SqlParserParameters;

import lombok.Getter;
import lombok.Setter;

/**
 * @author mode 2020-01-20 21:04
 * @since 1.1.3
 */
@Getter
@Setter
public class RewriteContext {

    private long                fetchLimit;
    private SqlParserParameters parameters = SqlParserParameters.empty();

    /**
     * B7 lock-free DML (additive, 4.2.2): the expected database/schema name of the execution
     * context, consumed by {@link RewriteSpi#analyzeDmlForBatch} to validate a statement's
     * db/schema prefix (B6 GHOST_SCHEMA_MISMATCH discipline — a prefix must EQUAL this name;
     * a prefixed statement with no expected name supplied is rejected fail-safe). Deliberately
     * NOT part of {@link SqlParserParameters}: parameters key the per-version RewriteSpi caches,
     * whereas this is per-call execution context. null/blank when the caller has no schema
     * binding (console batch routing always sets it to the ticket group's dbName).
     */
    private String              schemaName;

    public void setParameters(SqlParserParameters parameters) { this.parameters = SqlParserParameters.nullToEmpty(parameters); }
}
