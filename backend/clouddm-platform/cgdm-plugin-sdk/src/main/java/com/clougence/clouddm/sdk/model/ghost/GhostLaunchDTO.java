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
package com.clougence.clouddm.sdk.model.ghost;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * GHOST session launch payload, serialized into {@code ToolSessionContextDTO.configuration} by
 * the console side and parsed back by the tool implementation at {@code init} (same contract as
 * {@code FakerConfigDTO} for FAKER).
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class GhostLaunchDTO {

    private long            dsId;
    /** target schema (gh-ost --database); MySQL only */
    private String          schema;
    /** the single ALTER TABLE statement to migrate */
    private String          ddlSql;

    /** task-level parameter overrides; null (or null fields) fall back to the admin-level GhostPluginConfig defaults */
    private GhostOptionsDTO options;

    /** reserved for S7 (PRE_DDL ticket integration); null in the standalone-tool phase */
    private Long            ticketId;
    /** reserved for S7 (PRE_DDL ticket integration); null in the standalone-tool phase */
    private Integer         stmtSeq;
}
