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

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * Governance promote request FO.
 * Only revisionId + description are accepted — the PROD ticket's SQL comes from the frozen revision,
 * never from user input (spec §6.4: "PROD has no SQL editor").
 * Any unknown property (sql, dsId, envId) is rejected at deserialization time.
 */
@Getter
@Setter
public class GovPromoteFO {

    @NotNull
    private Long   revisionId;

    private String description;

    @JsonAnySetter
    public void rejectUnknownProperty(String key, Object value) {
        throw new IllegalArgumentException("Promote does not accept field: " + key);
    }
}
