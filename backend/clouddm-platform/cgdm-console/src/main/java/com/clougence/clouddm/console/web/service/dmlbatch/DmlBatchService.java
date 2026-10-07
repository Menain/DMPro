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
package com.clougence.clouddm.console.web.service.dmlbatch;

import java.util.Map;

import com.clougence.clouddm.base.metadata.ds.DataSourceConfig;
import com.clougence.clouddm.console.web.component.governance.TicketSegment;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.datasource.DmDsDO;
import com.clougence.clouddm.platform.dal.model.execution.DmExecAsyncTaskDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;

/**
 * B7 lock-free DML — the console-side engine service (design §4/§5/§9): batch-key probing
 * (channel C, dual dialect), the six-KV fail-safe parameter plane, the dispatch-time task
 * submission and the ticket-bound task lookup. The marking seam (GovTicketV2ServiceImpl
 * Step 6), the dispatch seam (dispatchBatchSegment), the sweep repair (TicketSegmentService)
 * and the retry resume all consume THIS surface; the batch loop itself lives in
 * {@code DmlBatchAsyncTask}.
 * <p>
 * Mirror disciplines inherited from B6: probe NEVER-THROW degradation (probeForRoute),
 * identifier validation + string-literal escaping before any remote call (ops-diagnostics §4),
 * submitTask-failure-throws-with-no-residue (the B7 inverse of the ghost closeQuietly coupling —
 * a batch task has no session, so a failed submission leaves nothing behind), and the
 * configData-binding LIKE pre-filter lookup (findTaskForTicketGroup, C6 direct reuse with the
 * bizType parameterized).
 */
public interface DmlBatchService {

    /** {@code dm_exec_async_task.bizType} of a batch task (the ghost TOOL_NAME analog). */
    String BIZ_TYPE = "DML_BATCH";

    /**
     * Version + dialect gate (裁决 #3, N1a handover obligation #2 — the rewrite SPI does NOT
     * gate versions, the console marking seam MUST): {@code dsType == MySQL ∧ version >= 8.0}
     * or {@code dsType == PostgreSQL ∧ version >= 15}. A blank/unparsable version is treated as
     * UNSUPPORTED (conservative — 不路由不拒绝, the statement keeps the direct chain exactly as
     * today). Floor semantics: the validated targets are MySQL 8.0 / PG 15; later majors of the
     * same dialect family parse identically and stay routed.
     */
    boolean isBatchRouteSupportedDs(DmDsDO dsDO);

    /**
     * NEVER-THROW channel-C probe (10s timeout, B6 probeForRoute shape): resolves the batch key
     * of one table (single-column PK → first single-column NOT NULL UK → not batchable) with the
     * v1 key-type whitelist, plus the B4-caliber table row count (the threshold fallback signal
     * when the EXPLAIN estimate is unavailable). Nullable single-column UKs count as NO KEY
     * (N1a handover obligation #1 — NULL keys would be silently skipped by the cursor).
     *
     * @param schemaName PG only: the statement's explicit schema prefix (nullable → the probe
     *                   matches {@code current_schema()}, consistent with the batch sessions'
     *                   default search_path); MySQL ignores it (table_schema = dbName)
     */
    DmlBatchKeyProbe probeForKey(String uid, DmDsDO dsDO, DataSourceConfig dsConfig,
                                 String dbName, String schemaName, String tableName);

    /** fail-safe six-KV resolution + defensive WhiteList clamping (dispatch-time snapshot). */
    DmlBatchEffectiveParams resolveEffectiveParams();

    /** dmlBatchAutoRoute kill switch — fail-safe read (null/exception → true, ghostAutoRoute pattern). */
    boolean isAutoRouteEnabled();

    /** dmlRowThreshold (fail-safe read) — the marking seam's threshold + display copy. */
    long rowThreshold();

    /**
     * Dispatch-mechanism launch of ONE DML_BATCH segment (the ghost {@code executeForTicket}
     * analog, called by GovTicketV2ServiceImpl.dispatchBatchSegment UNDER the caller's ticket
     * row lock): assembles the configData (launch freeze + effectiveParams snapshot + binding
     * triple + the inherited or fresh checkpoint) and submits the AsyncTask
     * (bizType=DML_BATCH, processType=PROGRESS, showInDock).
     * <p>
     * Failure contract: submitTask failure THROWS with NO execution-body residue (there is no
     * session to release — the inverse direction of the ghost closeQuietly coupling); the caller
     * lets the exception roll the dispatch transaction back, leaving the segment for the sweep's
     * PENDING re-dispatch row.
     *
     * @param inheritedCheckpoint retryGroup resume: the DONE-side checkpoint of the terminal old
     *                            task (cursor/batchesDone/rowsDone/lastDone — intent side never
     *                            inherited); null on a first dispatch
     * @return the submitted task's row id (recorded on the segment — the completion CAS taskId
     *         cross-check key, debt #5)
     */
    Long submitForTicket(DmApprovalDO ticket, DmTicketDbStmtDO group, TicketSegment segment,
                         Map<String, Object> inheritedCheckpoint);

    /**
     * The NEWEST DML_BATCH task bound to (ticketId, groupId[, segmentIndex]) — the C6 direct
     * reuse of the ghost lookup with the bizType parameterized: targeted config_data LIKE
     * pre-filter on the compact-JSON binding keys (without it the LIMIT-bounded newest-first
     * scan misses a long-running task once newer DML_BATCH tasks pile up), exact parse-equality
     * as the authority, substring over-inclusion harmless.
     */
    DmExecAsyncTaskDO findTaskForTicketGroup(long ticketId, long groupId, Integer segmentIndex);
}
