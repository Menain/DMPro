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

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * FO for openapi interface A: query db-change tickets by database name.
 * dbName matches either pre-prod or prod side of dm_db_pair (bidirectional).
 */
@Getter
@Setter
public class DbChangeTicketListFO {

    @McpField(value = DmMcpI18nKey.F_DB_NAME_DESC, required = true)
    @NotBlank(message = "dbName is required")
    private String  dbName;

    @McpField(value = DmMcpI18nKey.F_TICKET_TYPE_DESC)
    private String  ticketType; // optional: PRE_DDL | PROD_DML

    @McpField(value = DmMcpI18nKey.F_EXECUTED_ONLY_DESC)
    private boolean executedOnly; // default false

    @McpField(value = DmMcpI18nKey.F_PAGE_DESC)
    private int     page = 1;

    @McpField(value = DmMcpI18nKey.F_SIZE_DESC)
    private int     size = 20;
}
