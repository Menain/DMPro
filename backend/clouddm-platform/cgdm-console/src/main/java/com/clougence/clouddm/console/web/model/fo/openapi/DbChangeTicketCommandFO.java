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

import com.clougence.clouddm.console.web.constants.DmMcpI18nKey;
import com.clougence.clouddm.console.web.global.mcp.model.McpField;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * FO for the open API three-state approval command endpoints
 * (/tickets/approve, /tickets/reject, /tickets/cancel).
 * <p>
 * {@code ticketId} is the dm_approval.id and routes by approBiz (DM_CHANGE / DM_PROD_RELEASE).
 * {@code approver}, {@code comment}, {@code externalFlowId} are all optional and degrade gracefully
 * when absent — they enrich the ticket activity log, approIdentity, and audit event.
 */
@Getter
@Setter
public class DbChangeTicketCommandFO {

    @McpField(value = DmMcpI18nKey.F_TICKET_ID_DESC, required = true)
    @NotNull(message = "ticketId is required")
    private Long ticketId;

    /** Optional human-readable approver name for activity synthesis. */
    @McpField(value = DmMcpI18nKey.F_APPROVER_DESC, required = false)
    private String approver;

    /** Optional approval comment for activity context. */
    @McpField(value = DmMcpI18nKey.F_COMMENT_DESC, required = false)
    private String comment;

    /** Optional external engine flow identity; persisted to approIdentity only when currently empty. */
    @McpField(value = DmMcpI18nKey.F_EXTERNAL_FLOW_ID_DESC, required = false)
    private String externalFlowId;

    /** Optional return reason — used only by the /tickets/return command; recorded in eventData + IM notification. */
    @McpField(value = DmMcpI18nKey.F_REASON_DESC, required = false)
    private String reason;
}
