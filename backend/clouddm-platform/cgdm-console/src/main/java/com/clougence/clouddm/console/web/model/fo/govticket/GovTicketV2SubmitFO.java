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

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.Setter;

/**
 * FO for the v2 submit endpoint /dbChangeV2/submit.
 * Server-side re-runs the authoritative precheck; any group failure rejects the whole ticket.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = false)
public class GovTicketV2SubmitFO {

    @NotBlank
    private String              ticketType;   // PRE_DDL | PROD_DML

    private Long                serviceId;

    @NotEmpty
    private List<GroupInput>    groups;

    @NotBlank
    private String              ticketTitle;

    private String              description;

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = false)
    public static class GroupInput {
        private Long   pairId;
        @NotBlank
        private String sqlContent;
    }
}
