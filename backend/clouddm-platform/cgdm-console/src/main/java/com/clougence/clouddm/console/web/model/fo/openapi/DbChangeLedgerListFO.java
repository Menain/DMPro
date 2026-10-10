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

import lombok.Getter;
import lombok.Setter;

/**
 * FO for the openapi ledger list endpoint (/api/open/dbchange/ledger/list, ding-bridge R5).
 * All fields are optional filters — an empty FO returns the caller tenant's whole ledger
 * (first page). The ledger is a projection: rows may be merged into a release between the
 * bridge listing them and creating the release; releases/create then fails on uk_source_stmt
 * and the bridge conveys "re-select" back to the DingTalk side (design §5 realtime caveat).
 */
@Getter
@Setter
public class DbChangeLedgerListFO {

    @McpField(value = DmMcpI18nKey.F_TICKET_TYPE_DESC)
    private String  ticketType; // optional: PRE_DDL | PROD_DML

    @McpField(value = DmMcpI18nKey.F_PROMOTED_DESC)
    private Boolean promoted;   // optional: null=no filter, true=merged only, false=selectable candidates only

    @McpField(value = DmMcpI18nKey.F_PAGE_DESC)
    private int     pageNum = 1;

    @McpField(value = DmMcpI18nKey.F_SIZE_DESC)
    private int     pageSize = 20;
}
