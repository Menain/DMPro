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
 * Space usage of one database (MySQL) or schema (PG); both dialects alias onto this snake_case
 * column set (design 09-19-table-space-usage §2). MySQL figures are InnoDB estimates served from
 * the instance statistics cache; PG sizes are exact physical bytes while rowEstimate is the
 * reltuples estimate.
 */
@Data
public class SpaceDbVO {

    /** MySQL: table_schema; PG: nspname (the "database level" of a PG connection is schema-scoped). */
    private String dbName;

    private Long   tableCount;

    /** Estimated row count (MySQL TABLE_ROWS ±40-50%; PG GREATEST(reltuples,0)); null when unparseable. */
    private Long   rowEstimate;

    /** MySQL SUM(data_length) estimate / PG SUM(pg_table_size) — table body incl. TOAST on PG. */
    private Long   dataBytes;

    /** MySQL SUM(index_length) estimate / PG SUM(pg_indexes_size). */
    private Long   indexBytes;

    /** MySQL SUM(data_free) — allocated but unused bytes; PG has no free equivalent → always null (frontend shows '-'). */
    private Long   freeBytes;

    /** MySQL SUM(data_length+index_length) / PG SUM(pg_total_relation_size). */
    private Long   totalBytes;
}
