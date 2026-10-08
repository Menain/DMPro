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
 * B7 audit payload of the DML_BATCH_EXECUTE row (the {@code GhostOpAuditObj} pattern): written
 * at batch-segment dispatch in the ticket SUBMITTER's name (HIGH, resource = DATASOURCE,
 * resId = dsId — the four-point audit contract). N2 reuses the same AuditType for the
 * window-crash manual resolution actions (obj then carries {@code resolution} + {@code seq}).
 */
@Getter
@Setter
public class DmlBatchOpAuditObj {

    /** the batch AsyncTask's bizId (the dm_mon_biz_log forensics key). */
    private String  taskBizId;
    private Long    ticketId;
    private Long    groupId;
    /** the 1-based segment position of the dispatched DML_BATCH segment. */
    private Integer segmentIndex;
    /** dispatch origin label: TICKET_AUTO_ROUTE / TICKET_RETRY / TICKET_SEGMENT_CHAIN. */
    private String  via;

    private Long    dsId;
    private String  dbName;
    private String  tableName;
    /** the frozen batch key (probe result). */
    private String  keyColumn;
    private String  keyType;
    /** the routing signal (EXPLAIN estimate or probed table rows) + the effective batch size. */
    private Long    estimatedRows;
    private Integer batchSize;
    /** false = self-referencing SET / non-deterministic function (the审批 yellow-flag fact). */
    private Boolean idempotent;
    /** the statement text, truncated to {@link #SQL_MAX_LENGTH}. */
    private String  sqlSummary;

    /**
     * N2 (design §7.4): the window-crash manual-resolution annotation — set ONLY on the
     * retryGroup resolution row (RERUN_BATCH / SKIP_BATCH); null on every dispatch row.
     */
    private String  resolution;
    /** N2: the unresolved batch seq the resolution adjudicated (summary.intentSeq); resolution rows only. */
    private Long    intentSeq;

    /** max length of the statement text stored in the audit row (the §8.2 sqlSummary≤500 caliber). */
    public static final int SQL_MAX_LENGTH = 500;
}
