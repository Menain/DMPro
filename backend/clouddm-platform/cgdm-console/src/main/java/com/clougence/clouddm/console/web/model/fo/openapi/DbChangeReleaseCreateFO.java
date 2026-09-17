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

import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.Setter;

/**
 * FO for the open API /releases/create endpoint (production release proxy create).
 * <p>
 * {@code ticketIds} is the list of eligible source ticket IDs to merge (PRE_DDL FINISHED
 * or PROD_DML WAIT_RELEASE with SUCCESS stmts). {@code creatorAccount} is optional —
 * when omitted, the caller (service account) is the creator. When provided, the resolved
 * user's primaryUid must match the caller's puid.
 */
@Getter
@Setter
public class DbChangeReleaseCreateFO {

    @McpField(value = DmMcpI18nKey.F_TICKET_IDS_DESC, required = true)
    @NotEmpty(message = "ticketIds must not be empty")
    private List<Long> ticketIds;

    @McpField(value = DmMcpI18nKey.F_CREATOR_ACCOUNT_DESC, required = false)
    private String creatorAccount;

    @McpField(value = DmMcpI18nKey.F_TITLE_DESC, required = false)
    private String title;
}
