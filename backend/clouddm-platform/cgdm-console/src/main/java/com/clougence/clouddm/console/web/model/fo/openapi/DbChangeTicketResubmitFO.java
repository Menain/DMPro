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

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * FO for the open API /tickets/resubmit endpoint (governance ticket proxy resubmit).
 * <p>
 * {@code ticketId} identifies the REVISION ticket to resubmit. The caller's puid (from AK/SK)
 * must match the ticket's primaryUid (tenant check). The owner-only identity check is skipped
 * (AK/SK is the credential). For External-channel tickets, the resubmit goes directly to
 * WAIT_EXEC (Q2 方案 A).
 * <p>
 * {@code groups} contains the modified SQL per FAILED group (only FAILED groups are eligible).
 * pair_id/ds_id/db_name are NOT included — the pair set is frozen during REVISION.
 */
@Getter
@Setter
public class DbChangeTicketResubmitFO {

    @McpField(value = DmMcpI18nKey.F_TICKET_ID_DESC, required = true)
    @NotNull(message = "ticketId is required")
    private Long ticketId;

    @McpField(value = DmMcpI18nKey.F_GROUPS_DESC, required = true)
    @NotEmpty(message = "groups must not be empty")
    @Valid
    private List<GroupInput> groups;

    /** Optional reason for the revision (stored in the version audit row). */
    @McpField(value = DmMcpI18nKey.F_REASON_DESC, required = false)
    private String reason;

    /** Optional ticket-level rollback SQL. */
    @McpField(value = DmMcpI18nKey.F_ROLLBACK_SQL_DESC, required = false)
    private String rollBackSql;

    @Getter
    @Setter
    public static class GroupInput {

        @McpField(value = DmMcpI18nKey.F_GROUP_ID_DESC, required = true)
        @NotNull(message = "groupId is required")
        private Long groupId;

        @McpField(value = DmMcpI18nKey.F_SQL_CONTENT_DESC, required = true)
        @NotBlank(message = "sqlContent is required")
        private String sqlContent;
    }
}
