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
package com.clougence.clouddm.console.web.model.fo.governance;

import com.clougence.clouddm.platform.dal.model.approval.SqlContentType;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * Governance preSubmit request FO.
 * dsId/envId are intentionally absent — the server resolves them via getBinding(PRE).
 * Any unknown property in the request body is rejected at deserialization time (§6.4 defense-in-depth).
 */
@Getter
@Setter
public class GovPreSubmitFO {

    @NotNull
    private Long             logicalDbId;

    @NotBlank
    private String           ticketTitle;

    private String           description;

    @NotBlank
    private String           sql;

    private String           rollbackSql;

    private SqlContentType   contentType;

    private Long             attachmentId;

    @JsonAnySetter
    public void rejectUnknownProperty(String key, Object value) {
        throw new IllegalArgumentException("Governance ticket does not accept field: " + key);
    }
}
