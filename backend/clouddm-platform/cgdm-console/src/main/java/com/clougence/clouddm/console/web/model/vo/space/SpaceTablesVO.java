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

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * Table-level space usage snapshot of one database/schema (design 09-19-table-space-usage §3).
 * Filtering/sorting/pagination are frontend-local over the full {@code tables} set.
 */
@Data
public class SpaceTablesVO {

    /** Echo of the requested database (MySQL) / schema (PG) name. */
    private String             dbName;

    /** Rows already ordered by total_bytes DESC; empty list = the database has no base tables. */
    private List<SpaceTableVO> tables = new ArrayList<>();
}
