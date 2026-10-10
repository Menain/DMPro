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
package com.clougence.clouddm.console.web.model.vo.openapi;

import java.util.List;

import com.clougence.clouddm.console.web.model.vo.prodrelease.LedgerDbItemVO;

import lombok.Getter;
import lombok.Setter;

/**
 * VO for the openapi ledger list endpoint (/api/open/dbchange/ledger/list, ding-bridge R5) —
 * one row per ledger ticket (executed PRE_DDL awaiting release merge, or VIA_RELEASE PROD_DML
 * in the release pool).
 * <p>
 * Desensitized list-level shape (design §5): sqlSummary is capped at 200 chars — no full SQL
 * text, no connection strings, no ownerUid (the console {@code GovLedgerTicketVO} carries it;
 * the open surface does not). The ledger detail endpoint (full SQL) is deliberately NOT opened.
 * <p>
 * Tenant-filtered: only rows whose ticket primaryUid == caller puid are returned; cross-tenant
 * rows are silently dropped (empty list — list semantics, never not-found).
 */
@Getter
@Setter
public class OpenDbChangeLedgerTicketVO {

    private long   ticketId;
    private String ticketTitle;
    private String serviceName;
    private Long   serviceId;
    private String gmtCreate;          // yyyyMMdd HHmmss
    private String execStatus;         // SUCCESS (executed PRE_DDL) | PENDING (release-pool PROD_DML)
    private String sqlSummary;         // first 200 chars of the first group's SQL — never the full text
    private String ticketType;         // PRE_DDL | PROD_DML
    private String approvalMode;       // VIA_RELEASE for pool rows; null for legacy PRE_DDL rows
    private List<LedgerDbItemVO> preDbs;
    private List<String>         prodDbs;   // "dsName/dbName" display labels
    private boolean promoted;               // already merged into a release
    private String releaseNo;
    private String releaseStatus;
    private boolean ghostRouted;            // B6 M3 disclosure: ≥1 lock-free (gh-ost) routed stmt
    private boolean dmlBatchRouted;         // B7 N3 disclosure: ≥1 DML_BATCH routed stmt
}
