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
package com.clougence.clouddm.console.web.service.dmlbatch.asyntask;

import com.clougence.clouddm.console.web.component.governance.TicketSegment;
import com.clougence.clouddm.console.web.service.dmlbatch.DmlBatchEffectiveParams;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * {@code dm_exec_async_task.configData} payload of a DML_BATCH task (the
 * {@code GhostAsyncTaskConfig} pattern; design §5.1 — configData is the checkpoint persistence
 * carrier, zero new tables/columns).
 * <p>
 * Launch fields are written ONCE at dispatch (frozen): the statement text, the probed batch key
 * and the effective-parameter snapshot — the execution NEVER re-probes or re-resolves KVs
 * (auditability/reproduction, B6 effectiveOptions discipline). The {@code checkpoint} sub-object
 * is the mutable state (the tick loop's single write target).
 * <p>
 * B8 seam (prd R17 —预留不实现): the ticket binding triple is NULLABLE by design; the execution
 * inputs are the frozen (statement text + batch key + params) triple with no ticket-chain
 * assumption baked into the loop itself. v1 exposes NO standalone entry.
 * <p>
 * ⚠ STALENESS NOTE (the tick contract): the AsyncTask framework passes the IN-MEMORY
 * taskDO.configData into {@code executeTask} — it goes stale after the first checkpoint write.
 * Every tick therefore re-queries its own task row by {@code bizId} and parses the FRESH
 * configData; this bootstrap copy only supplies the identity fields (bizId + binding + launch
 * freeze, which never mutate).
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class DmlBatchAsyncTaskConfig {

    // ---- identity / ticket binding (B8-seam: nullable) ----
    /** the task's own bizId (generated UUID) — the tick's fresh-row re-query key. */
    private String                      bizId;
    private Long                        ticketId;
    private Long                        groupId;
    /** the 1-based segIdx of the DML_BATCH segment this task executes (CAS + taskId cross-check key). */
    private Integer                     segmentIndex;

    // ---- launch freeze (written once at dispatch) ----
    /** the session owner = audit subject = the ticket SUBMITTER (R19; ghost owner discipline). */
    private String                      userId;
    private Long                        dsId;
    private String                      dbName;
    /** the FULL frozen statement text (the segment plan's sql — never the group fulltext). */
    private String                      stmtSql;
    /** the probed batch key frozen at precheck ({column, keyType}). */
    private TicketSegment.BatchKey      batchKey;
    /** the six-KV resolution snapshot frozen at dispatch (design §11). */
    private DmlBatchEffectiveParams     effectiveParams;
    /** progress denominator ("约" semantics) — EXPLAIN estimate or the probed table rows. */
    private Long                        estimatedRows;
    /** false = self-referencing SET / non-deterministic function (drives the resolution copy, §7.3). */
    private Boolean                     idempotent;

    // ---- mutable execution state ----
    private DmlBatchCheckpoint          checkpoint;
}
