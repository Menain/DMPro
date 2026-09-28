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
package com.clougence.clouddm.console.web.model.fo.ghost;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import lombok.Getter;
import lombok.Setter;

/**
 * Lock-free DDL precheck request (B6 R3/R4, design 09-17-online-ddl-activation §4.2).
 * <p>
 * {@code ddlSql} is optional: the form may precheck a cascade-selected table before the user
 * types the ALTER. When present, the statement is authoritative — the parsed table name drives
 * the PK/UK and size probes (and a db-prefix mismatch becomes a hard block). {@code tableName}
 * is only consulted while {@code ddlSql} is blank.
 */
@Getter
@Setter
public class GhostPrecheckFO {

    @NotNull
    private Long   dsId;

    /** target database (gh-ost --database) */
    @NotBlank
    private String dbName;

    /** cascade-selected table; ignored once a parsable ddlSql is supplied */
    private String tableName;

    /** the single ALTER TABLE statement (optional at precheck time) */
    private String ddlSql;
}
