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
package com.clougence.clouddm.console.web.model.vo.space;

import lombok.Data;

/**
 * Space usage of one table (MySQL base table / PG relation of relkind r|p); both dialects alias
 * onto the same snake_case column set (design 09-19-table-space-usage §2).
 */
@Data
public class SpaceTableVO {

    /**
     * PG only: the schema the relation lives in — the PG table-level drill-down connects to the
     * target database and lists ALL its schemas at once (2026-09-19 smoke ruling). Null on MySQL
     * (its table level is already schema-scoped by dbName) → frontend hides the column.
     */
    private String schemaName;

    private String tableName;

    /** Estimated row count (MySQL TABLE_ROWS ±40-50%; PG GREATEST(reltuples,0)); null when unparseable. */
    private Long   rowEstimate;

    /** MySQL data_length estimate / PG pg_table_size — table body incl. TOAST on PG. */
    private Long   dataBytes;

    /** MySQL index_length estimate / PG pg_indexes_size. */
    private Long   indexBytes;

    /** MySQL data_free — fragmentation/reclaimable bytes; PG has no equivalent → always null (frontend shows '-'). */
    private Long   freeBytes;

    /** MySQL data_length+index_length / PG pg_total_relation_size. */
    private Long   totalBytes;
}
