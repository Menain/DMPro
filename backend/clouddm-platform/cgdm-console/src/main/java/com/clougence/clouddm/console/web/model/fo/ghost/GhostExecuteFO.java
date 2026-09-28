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

import com.clougence.clouddm.sdk.model.ghost.GhostOptionsDTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import lombok.Getter;
import lombok.Setter;

/**
 * Lock-free DDL launch request (B6 R2). {@code options} carries the task-level parameter
 * overrides of the launch form; null (or null fields) fall back to the admin-level
 * {@code GhostPluginConfig} defaults field by field (S1 merge contract).
 */
@Getter
@Setter
public class GhostExecuteFO {

    @NotNull
    private Long            dsId;

    /** target database (gh-ost --database) */
    @NotBlank
    private String          dbName;

    /** the single ALTER TABLE statement to migrate */
    @NotBlank
    private String          ddlSql;

    /** task-level gh-ost option overrides; optional */
    private GhostOptionsDTO options;
}
