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
package com.clougence.clouddm.console.web.model.fo.govticket;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = false)
public class GovTicketV2ResubmitFO {

    @NotNull
    private Long ticketId;

    /** Modified SQL per group (only FAILED groups are eligible; SUCCESS groups in the
     *  request are rejected). pair_id/ds_id/db_name are NOT included — the pair set is
     *  frozen during REVISION. */
    @NotEmpty
    @Valid
    private List<GroupInput> groups;

    /** Optional reason for the revision (stored in the version audit row). */
    private String reason;

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = false)
    public static class GroupInput {
        @NotNull
        private Long groupId;
        @NotNull
        private String sqlContent;
    }
}
