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
package com.clougence.clouddm.console.web.model.vo.dmlbatch;

import lombok.Getter;
import lombok.Setter;

/**
 * B7 N2 audit payload of the DML_BATCH_REJECT row (裁决 #16, design §8.2): obj = {table, rows,
 * reason:NO_KEY, sqlSummary≤500, alertPushed} — the ONLY in-platform trail of a no-key large-DML
 * submission rejection (the submit transaction rolled back; there is no ticket/event carrier).
 * Written OUTSIDE the transaction at the controller boundary (autocommit insert → survives the
 * rollback), subject = the submitter, resId = dsId (ResourceType.DATASOURCE), HIGH.
 * <p>
 * 留痕与告警解耦: {@code alertPushed} records the alert channel's accepted flag — false when
 * alertPushEnable is off or the push degraded (DISABLED/SKIPPED semantics); the audit row is
 * written regardless (the "可回溯" hard requirement of 裁决 #16).
 */
@Getter
@Setter
public class DmlBatchRejectAuditObj {

    private Long   dsId;
    private String dbName;
    private String tableName;
    /** the threshold signal that fired (EXPLAIN estimate, else the probed table rows). */
    private Long   rows;
    private Long   rowThreshold;
    /** the stable rejection reason — v1 value domain: {@link #REASON_NO_KEY}. */
    private String reason;
    /** the probe's human-copy reason detail (no PK/UK, nullable-UK-only...). */
    private String keyDetail;
    /** the rejected statement's text, truncated to {@link DmlBatchOpAuditObj#SQL_MAX_LENGTH}. */
    private String sqlSummary;
    /** the alert channel's accepted flag (false = switch off / degraded — the row survives either way). */
    private Boolean alertPushed;
    /** the rejected statement's 1-based exec_order position (R11 domain). */
    private Integer stmtIndex;

    public static final String REASON_NO_KEY = "NO_KEY";
}
