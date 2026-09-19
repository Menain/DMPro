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
package com.clougence.clouddm.console.web.model.fo.space;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import lombok.Getter;
import lombok.Setter;

/**
 * Request parameters for the table-level space usage snapshot of one database/schema
 * (design 09-19-table-space-usage §3). The dbName is only ever supplied from a prior
 * {@code databases} response; the service re-validates and escapes it server-side (design §2.3).
 */
@Getter
@Setter
public class SpaceTablesFO {

    @NotNull
    private Long   dsId;

    /** Database name (MySQL) or schema name (PG — its "database level" is schema-scoped). */
    @NotBlank
    private String dbName;
}
