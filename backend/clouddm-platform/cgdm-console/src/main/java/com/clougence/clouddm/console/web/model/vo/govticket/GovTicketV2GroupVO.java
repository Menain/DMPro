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
package com.clougence.clouddm.console.web.model.vo.govticket;

import lombok.Getter;
import lombok.Setter;

/**
 * VO returned by /dbChangeV2/groupList — one row per statement group in a ticket.
 */
@Getter
@Setter
public class GovTicketV2GroupVO {

    private Long   groupId;
    private Long   pairId;
    private Long   dsId;
    private String dbName;
    private String sqlContent;
    private String precheckResult;   // JSON string (design §4 contract)
    private String execStatus;       // PENDING | EXECUTING | SUCCESS | FAILED
    private String execDetail;

    // ---- B6 M1 (Step 7d D0) ghost-task projection, additive ----
    // Populated ONLY for ghost-routed groups (ApprovalMO.ghostRouteFlags) that already have a
    // dispatched GHOST AsyncTask; null otherwise (marked-but-not-dispatched, non-ghost groups,
    // legacy tickets). Read-only display carrier for the ticket-detail group panel — the live
    // progress still comes from /ghost/tailStatus by ghostSessionId (zero new ghost endpoint).

    /** dm_exec_async_task row id of the newest bound GHOST task */
    private Long    ghostTaskId;
    /** tool session id (AsyncTask bizId) — the tailStatus/tailLog/pause/resume/stop/drop channel */
    private String  ghostSessionId;
    /** cut-over old-table name captured from the migration (R7); null before cut-over */
    private String  ghostOldTableName;
    /** the old table was already dropped through the platform (R7) */
    private Boolean ghostOldTableDropped;
    /**
     * Effective tool-level status: INIT / RUNNING / PAUSED / COMPLETED / FAILED / ABORTED.
     * A TERMINAL AsyncTaskStatus wins over the captured lastStatus (the session-lost FAILED path
     * terminates the task without a final tool-state capture); null = task exists but no status
     * captured yet (the frontend renders INIT and keeps polling).
     */
    private String  ghostTaskStatus;

    // ---- B6 M4 (Step 10) segment runtime passthrough, additive ----

    /**
     * {@code dm_ticket_db_stmt.exec_segments} VERBATIM (the segment RUNTIME carrier — the
     * TicketSegment JSON shape, same passthrough treatment as precheckResult above, zero new
     * endpoints). Null for legacy tickets, pure-normal groups and segmented groups before their
     * first dispatch — the frontend then falls back to the precheck_result "segments" PLAN (also
     * passthrough) and renders the segment list all-PENDING. No trimming of the GHOST segments'
     * full sql text: the same statement bytes already travel twice in this response (sqlContent +
     * the precheck_result plan), so the marginal body growth is bounded by text the client
     * already holds, and a trimmed copy would fork the carrier into a second JSON shape.
     * <p>
     * ⚠ DUAL-WRITE COUPLING (see the pin in groupList / TicketSegmentService.persistRuntime):
     * the ghost AND DML_BATCH projections and the WAIT_EXEC sweep repair all gate on
     * ghostRouteFlags, NOT on this field — consumers must not treat a non-null execSegments as
     * self-sufficient.
     */
    private String  execSegments;

    // ---- B7 N3 DML_BATCH projection, additive ----

    /**
     * The NEWEST DML_BATCH AsyncTask bound to the group (configData ticketId/groupId binding,
     * the ghost projection's twin — same flags gate, same never-throw discipline, ZERO new
     * endpoints: the batch progress lives entirely in the task configData checkpoint, so unlike
     * ghost there is no tailStatus poller — the frontend renders off this projection as it
     * rides the existing 5s group refresh). Null for non-routed groups, undispatched marks,
     * legacy tickets and GHOST-only groups (no bound batch task).
     * <p>
     * The window-crash resolution trigger stays the SEGMENT SUMMARY frozen in execSegments
     * (the resolution guard's sole authority, N2 ruling) — {@code unresolvedIntent} here is
     * display material derived from the same terminal carrier, never the guard key.
     */
    private DmlBatchProgressVO dmlBatchProgress;

    /**
     * B7 N3: the batch-loop projection of one group's newest DML_BATCH task (design §10 —
     * {batchesDone, rowsDone, percentApprox, lagState, paused, unresolvedIntent, intentSeq,
     * idempotent} + the control/resolution material taskId/segmentIndex/taskStatus/
     * lastBatchError/retryPending/skipVerdictPending).
     */
    @Getter
    @Setter
    public static class DmlBatchProgressVO {
        /** dm_exec_async_task row id (control endpoints + the segment/task join). */
        private Long    taskId;
        /** the task's bound 1-based segIdx (control FO + inline attachment to the segment row). */
        private Integer segmentIndex;
        /** AsyncTaskStatus name: INIT/WAIT_START/RUNNING/BLOCK/PAUSE/PAUSING/CANCELING/COMPLETE/FAILURE/CANCEL. */
        private String  taskStatus;
        /** taskStatus == PAUSE (the controlled pause — the sweep never dead-judges it). */
        private Boolean paused;
        private Long    batchesDone;
        private Long    rowsDone;
        /** launch-frozen progress denominator ("约" semantics); null when the estimate was unavailable. */
        private Long    estimatedRows;
        /** rowsDone×100/estimatedRows capped at 100; null without a denominator. */
        private Integer percentApprox;
        /** replica-lag throttle visibility (null = no probe result yet). */
        private LagStateVO lagState;
        /**
         * TERMINAL tasks only: the window-crash marker mirrored from the carrier
         * (intent ∧ ¬retryPending ∨ an unconsumed SKIP verdict). A RUNNING task with an in-flight
         * intent is a healthy batch, never "unresolved" — and the guard authority is the segment
         * summary anyway (N2 single-key ruling).
         */
        private Boolean unresolvedIntent;
        /** the unresolved batch's seq (intent.seq, else batchesDone+1 for the re-frozen SKIP shape). */
        private Long    intentSeq;
        /** launch-frozen idempotence mark (the resolution modal's red/green copy). */
        private Boolean idempotent;
        /** the in-flight intent carries a recorded DB error → the automatic retry flow is active. */
        private Boolean retryPending;
        /** the retry/verdict material for the modal's auxiliary line (≤ one DB message). */
        private String  lastBatchError;
        /** an UNCONSUMED SKIP_BATCH verdict (N2 check re-freeze shape) → "上次跳过未成功" copy. */
        private Boolean skipVerdictPending;
    }

    /** the checkpoint lagState projection: visible=false → the fixed-interval degradation (延迟不可见). */
    @Getter
    @Setter
    public static class LagStateVO {
        private Boolean visible;
        private Double  lastLagSec;
        /** throttledAt != null — the batch loop is yielding to replica lag right now. */
        private Boolean throttled;
    }
}
