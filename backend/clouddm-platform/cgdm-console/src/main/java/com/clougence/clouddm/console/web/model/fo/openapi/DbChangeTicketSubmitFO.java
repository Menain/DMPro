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
package com.clougence.clouddm.console.web.model.fo.openapi;

import java.util.List;

import com.clougence.clouddm.console.web.constants.DmMcpI18nKey;
import com.clougence.clouddm.console.web.global.mcp.model.McpField;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.Setter;

/**
 * FO for the open API /tickets/submit endpoint (governance ticket proxy submit).
 * <p>
 * {@code ownerAccount} is the platform account of the ticket applicant — the ticket
 * will be created with this user as ownerUid (used for return/IM notification routing).
 * The caller's puid (from AK/SK) must match the resolved user's primaryUid.
 * <p>
 * The remaining fields are the GovTicketV2SubmitFO open subset: ticketType, approvalMode,
 * groups (pairId + sqlContent), ticketTitle, description, serviceId.
 */
@Getter
@Setter
public class DbChangeTicketSubmitFO {

    @McpField(value = DmMcpI18nKey.F_OWNER_ACCOUNT_DESC, required = true)
    @NotBlank(message = "ownerAccount is required")
    private String ownerAccount;

    @McpField(value = DmMcpI18nKey.F_TICKET_TYPE_DESC, required = true)
    @NotBlank(message = "ticketType is required")
    private String ticketType;   // PRE_DDL | PROD_DML

    @McpField(value = DmMcpI18nKey.F_SERVICE_ID_DESC, required = false)
    private Long serviceId;

    @McpField(value = DmMcpI18nKey.F_GROUPS_DESC, required = true)
    @NotEmpty(message = "groups must not be empty")
    private List<GroupInput> groups;

    @McpField(value = DmMcpI18nKey.F_TICKET_TITLE_DESC, required = true)
    @NotBlank(message = "ticketTitle is required")
    private String ticketTitle;

    @McpField(value = DmMcpI18nKey.F_DESCRIPTION_DESC, required = false)
    private String description;

    /** PROD_DML only: DIRECT (default) = direct approval+execution; VIA_RELEASE = enter the
     *  release ledger pool at WAIT_RELEASE. Ignored for PRE_DDL. */
    @McpField(value = DmMcpI18nKey.F_APPROVAL_MODE_DESC, required = false)
    private String approvalMode;

    @Getter
    @Setter
    public static class GroupInput {

        @McpField(value = DmMcpI18nKey.F_PAIR_ID_DESC, required = true)
        private Long pairId;

        @McpField(value = DmMcpI18nKey.F_SQL_CONTENT_DESC, required = true)
        @NotBlank(message = "sqlContent is required")
        private String sqlContent;
    }
}
