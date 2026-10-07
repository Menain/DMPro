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
package com.clougence.clouddm.console.web.component.execute;

import java.util.stream.Stream;

import com.clougence.clouddm.api.console.autoexec.AutoExecTaskPackageInfo;
import com.clougence.clouddm.api.console.autoexec.ErrorStrategy;
import com.clougence.clouddm.console.web.component.execute.model.AutoExecCreateMO;
import com.clougence.clouddm.console.web.model.vo.DmPageVO;
import com.clougence.clouddm.console.web.model.vo.ticket.DmAutoExecJobVO;
import com.clougence.clouddm.console.web.model.vo.ticket.DmAutoExecTaskVO;
import com.clougence.clouddm.platform.dal.model.execution.AutoExecTaskStatus;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;
import com.clougence.clouddm.platform.dal.util.PageObj;
import com.clougence.clouddm.sdk.sql.parser.SplitScript;

public interface AutoExecService {

    void createJob(AutoExecCreateMO request, Stream<SplitScript> scripts);

    /**
     * V2 governance: creates a per-DB execution job for a statement group.
     * <p>
     * Mirrors {@link #createJob} but sets {@code depend_on_group_id} (not {@code depend_on_biz_id})
     * and splits the SQL from the group's {@code sql_content}. The job is created with PREPARING
     * status; the caller must call {@link #startJob} to transition to INIT for scheduler pickup.
     * <p>
     * Convenience form for SYSTEM-driven chains (auto-advance / segment chain / retryGroup /
     * External resubmit): IMMEDIATE execution, no retry parameters — delegates to the extended
     * overload with byte-identical defaults (N0 additive-extension regression pin).
     *
     * @param group           the statement group row (dsId, dbName, sqlContent, id)
     * @param jobBizId        generated job bizId (unique within dm_exec_auto_job)
     * @param transactional   whether to wrap tasks in a transaction
     * @param errorStrategy   error strategy for the job
     * @param languageTag     locale language tag for worker messages
     * @param uid             operator uid (typically "SYSTEM")
     */
    void createGroupJob(DmTicketDbStmtDO group, String jobBizId, boolean transactional, ErrorStrategy errorStrategy, String languageTag, String uid);

    /**
     * V2 governance, extended form (B7 N0 — PROD_DML human-confirm chain migration): creates a
     * per-DB execution job for a statement group with the FULL execution config projected from
     * the confirm FO — the group-job analog of {@link #createJob}'s {@code AutoExecCreateMO}
     * fields. Needed because the human confirm dialog carries execType/execTime (SPECIFY_TIME
     * scheduled execution) and retryWaitTime/retryCount (RETRY error strategy) which the
     * SYSTEM-driven chains never set (they are IMMEDIATE/NONE by construction).
     * <p>
     * Semantics mirror {@code createJob} verbatim: {@code execType == IMMEDIATE} → scheduleTime
     * = now; otherwise scheduleTime = {@code new Date(execTime)} (the scheduler scan holds INIT
     * jobs until {@code schedule_time} — SPECIFY_TIME group semantics preserved); RETRY strategy
     * requires non-null, non-negative retryWaitTime/retryCount (same validation, same message).
     * The 6-arg overload delegates here with (null, null, IMMEDIATE, null) — existing callers
     * keep byte-identical job rows.
     *
     * @param group           the statement group row (dsId, dbName, sqlContent, id)
     * @param jobBizId        generated job bizId (unique within dm_exec_auto_job)
     * @param transactional   whether to wrap tasks in a transaction
     * @param errorStrategy   error strategy for the job
     * @param retryWaitTime   retry wait seconds (RETRY strategy only; null otherwise)
     * @param retryCount      retry count (RETRY strategy only; null otherwise)
     * @param execType        IMMEDIATE | SPECIFY_TIME (MANUAL_EXEC never reaches job creation)
     * @param execTime        epoch millis for SPECIFY_TIME (null for IMMEDIATE)
     * @param languageTag     locale language tag for worker messages
     * @param uid             operator uid (human confirmer or "SYSTEM")
     */
    void createGroupJob(DmTicketDbStmtDO group, String jobBizId, boolean transactional, ErrorStrategy errorStrategy,
                        Long retryWaitTime, Long retryCount,
                        com.clougence.clouddm.platform.dal.model.execution.AutoExecType execType, Long execTime,
                        String languageTag, String uid);

    /**
     * V2 job completion handler: updates the group's exec_status and, when all groups for the
     * ticket are terminal, aggregates to the ticket level (completeExecution / failExecution).
     * Called by ExecJobRServiceProvider and AutoExecServiceImpl when a v2 job reaches a terminal state.
     *
     * @param jobId      the dm_exec_auto_job id
     * @param success    {@code true} for job success, {@code false} for failure
     * @param errorDetail optional error message for failed groups
     */
    void handleV2JobCompletion(long jobId, boolean success, String errorDetail);

    /**
     * P3 production release: creates a per-stmt execution job for a release stmt snapshot.
     * <p>
     * Mirrors {@link #createGroupJob} but sets {@code depend_on_release_stmt_id} (not group_id)
     * and splits the SQL from the stmt's {@code sql_content}.
     * <p>
     * Inheritance fix (design §8): EXECUTING mark is applied AFTER job insert succeeds;
     * failure path resets stmt to PENDING.
     *
     * @param stmt           the release statement snapshot row
     * @param jobBizId       generated job bizId
     * @param transactional  whether to wrap tasks in a transaction
     * @param errorStrategy  error strategy for the job
     * @param languageTag    locale language tag for worker messages
     * @param uid            operator uid
     */
    void createReleaseStmtJob(com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseStmtDO stmt,
                             String jobBizId, boolean transactional, ErrorStrategy errorStrategy,
                             String languageTag, String uid);

    /**
     * P3 release job completion handler: updates the stmt's exec_status and, when all stmts
     * for the release are terminal, aggregates to the release level.
     * On success: chains the next PENDING stmt for the same (release, prod_ds_id, prod_db_name).
     * Called by ExecJobRServiceProvider and dispatchJob when a release job reaches terminal state.
     */
    void handleReleaseJobCompletion(long jobId, boolean success, String errorDetail);

    /**
     * Sweep safety net for the release completion aggregation (race fix 2026-09-12):
     * if the release is EXECUTING and every stmt is terminal, run the same aggregation
     * as handleReleaseJobCompletion (DONE / PARTIAL_FAILED + approval terminal update).
     * No-op otherwise; idempotent via the release row lock + EXECUTING guard + CAS transit.
     * Called from ProdReleaseApprovalHandler.executeTicket (WAIT_EXEC sweep entry).
     */
    void recoverReleaseCompletionIfDue(long releaseId);

    void startJob(String jobBizId, String operatorUid);

    void deleteJob(String jobBizId);

    /**
     * B7 N0 (strict confirm-cleanup, race-free variant of {@link #deleteJob}): deletes the job
     * ONLY while it is still pre-dispatch ({@code PREPARING} or {@code INIT} — the scheduler
     * claims {@code INIT → PACKAGING} by row-locked CAS, so the status check under
     * {@code queryByIdForUpdate} is race-free against dispatch). A job already claimed
     * ({@code PACKAGING} and beyond) is LEFT RUNNING: deleting it would orphan the live sidecar
     * execution (its reportMessage callbacks no-op on the deleted rows) and — with the group row
     * reset — cause a silent DOUBLE EXECUTION on re-confirm. The surviving job's completion
     * writeback owns the group state; the SUCCESS-retained PENDING/FAILED re-confirm filter then
     * prevents re-executing it.
     *
     * @param jobBizId the job bizId to delete
     * @return {@code true} only when the job existed pre-dispatch and was deleted (the caller may
     *         reset the group row); {@code false} when absent (nothing to do) or already
     *         dispatched (left running — the caller MUST NOT reset the group row)
     */
    boolean deleteJobIfNotDispatched(String jobBizId);

    /**
     * B7 N1b (shared-dispatcher strict confirm-cleanup): the GROUP-keyed variant of
     * {@link #deleteJobIfNotDispatched} — resolves the group's v2 job via
     * {@code depend_on_group_id} (uk single row) and applies the identical race-free guard.
     * Needed because a SEGMENTED group's job bizId is generated INSIDE
     * {@code TicketSegmentService.dispatchFirstSegment} (invisible to the confirm loop): when a
     * later group fails the strict human-confirm cleanup, an AUTOEXEC-first-segment job that is
     * still pre-dispatch must be deletable by group id. No job (GHOST/DML_BATCH first segment —
     * a live execution body) or an already-claimed job → {@code false} → the caller MUST NOT
     * reset the group row (the surviving body's writeback owns the group state — the N0
     * survive principle).
     *
     * @param groupId the statement group id
     * @return {@code true} only when a pre-dispatch job existed and was deleted
     */
    boolean deleteGroupJobIfNotDispatched(long groupId);

    void dispatchJob(Long jobId);

    boolean skipTask(String bizId, long taskId);

    void continueTask(String bizId, long taskId);

    void retryJob(String bizId);

    void endJob(String bizId);

    void stopJob(String bizId);

    DmAutoExecJobVO queryAutoExecJob(String bizId, boolean canOperate);

    DmPageVO<DmAutoExecTaskVO> queryAutoExecTaskSummaryList(String bizId, boolean canOperate, AutoExecTaskStatus status, PageObj page, int sqlSummaryLength);

    String queryAutoExecTaskSql(String bizId, long taskId);

    //

    AutoExecTaskPackageInfo create(long jobId);

    byte[] read(long jobId, long attachmentId, long offset, int length);

    void delete(long attachmentId);

    /**
     * Governance correction touchpoint #6: replace a failed task's SQL with corrected text.
     * <p>
     * Failed task → CANCELED; new task row created with WAIT_EXEC status, reusing the original exec_order
     * and generating a new biz_id/query_id. The old task is never replayed (CANCELED ∉ retryTask/create
     * replay set), the new task is always replayed (WAIT_EXEC ∈ replay set).
     * <p>
     * Existing methods are unchanged — this is a pure addition (spec §2.2 touchpoint #6).
     *
     * @param bizId        ticket bizId (same semantics as retryJob/skipTask — job is found via queryByDependOnBizId)
     * @param failedTaskId the FAILED or ROLLBACK task to replace
     * @param newExecSql   corrected single-statement SQL text
     */
    void replaceTask(String bizId, long failedTaskId, String newExecSql);

}
