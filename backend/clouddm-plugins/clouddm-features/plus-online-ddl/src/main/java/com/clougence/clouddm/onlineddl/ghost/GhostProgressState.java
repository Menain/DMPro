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
package com.clougence.clouddm.onlineddl.ghost;

import com.clougence.clouddm.sdk.model.ghost.GhostStatusDTO;

/**
 * Mutable, thread-safe accumulator of {@link GhostLogEvent}s: the executor's output pump applies
 * events from the gh-ost process thread while tailStatus reads from RPC threads. Also carries the
 * session lifecycle marks set by {@link GhostTools} (running / user pause / terminal states).
 *
 * <p>State domains (wire values of {@link GhostStatusDTO}):</p>
 * <ul>
 * <li>status: INIT → RUNNING ⇄ PAUSED → COMPLETED | FAILED | ABORTED;</li>
 * <li>phase (monotonic, never downgraded): READY → GHOST_TABLE_CREATED → COPYING →
 *     ROW_COPY_COMPLETE → CUT_OVER → DONE.</li>
 * </ul>
 */
public class GhostProgressState {

    // session-level status values
    public static final String STATUS_INIT      = "INIT";
    public static final String STATUS_RUNNING   = "RUNNING";
    public static final String STATUS_PAUSED    = "PAUSED";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED    = "FAILED";
    public static final String STATUS_ABORTED   = "ABORTED";

    // gh-ost phase values
    public static final String PHASE_READY               = "READY";
    public static final String PHASE_GHOST_TABLE_CREATED = "GHOST_TABLE_CREATED";
    public static final String PHASE_COPYING             = "COPYING";
    public static final String PHASE_ROW_COPY_COMPLETE   = "ROW_COPY_COMPLETE";
    public static final String PHASE_CUT_OVER            = "CUT_OVER";
    public static final String PHASE_DONE                = "DONE";

    /** phase rank for the monotonic guard */
    private static int rank(String phase) {
        switch (phase) {
            case PHASE_GHOST_TABLE_CREATED:
                return 1;
            case PHASE_COPYING:
                return 2;
            case PHASE_ROW_COPY_COMPLETE:
                return 3;
            case PHASE_CUT_OVER:
                return 4;
            case PHASE_DONE:
                return 5;
            default:
                return 0;
        }
    }

    private String  status       = STATUS_INIT;
    private String  phase        = PHASE_READY;
    private long    curValue     = 0;
    private long    maxValue     = 0;
    private double  percent      = 0;
    private long    applied      = 0;
    private String  backlog;
    private String  lag;
    private String  stateWord;
    private String  eta;
    private String  oldTableName;
    private String  fatalMessage;
    private String  failReason;
    private boolean userPaused   = false;

    /** apply one parsed log event (called from the process output pump thread) */
    public synchronized void apply(GhostLogEvent event) {
        if (event == null) {
            return;
        }
        switch (event.getType()) {
            case COPY_PROGRESS:
                this.curValue = event.getCurValue();
                this.maxValue = event.getMaxValue();
                this.percent = event.getPercent();
                this.applied = event.getApplied();
                this.backlog = event.getBacklog();
                this.lag = event.getLag();
                this.stateWord = event.getState();
                this.eta = event.getEta();
                advancePhase(PHASE_COPYING);
                break;
            case PHASE:
                switch (event.getPhase()) {
                    case GHOST_TABLE_CREATED:
                        advancePhase(PHASE_GHOST_TABLE_CREATED);
                        break;
                    case ROW_COPY_COMPLETE:
                        advancePhase(PHASE_ROW_COPY_COMPLETE);
                        break;
                    case CUT_OVER:
                        advancePhase(PHASE_CUT_OVER);
                        break;
                    case DONE:
                        advancePhase(PHASE_DONE);
                        break;
                }
                break;
            case OLD_TABLE:
                this.oldTableName = event.getValue();
                advancePhase(PHASE_CUT_OVER);
                break;
            case THROTTLED:
                if (STATUS_RUNNING.equals(this.status)) {
                    this.status = STATUS_PAUSED;
                }
                break;
            case UNTHROTTLED:
                // a user-requested pause outranks the log signal (the flag file is authoritative)
                if (STATUS_PAUSED.equals(this.status) && !this.userPaused) {
                    this.status = STATUS_RUNNING;
                }
                break;
            case FATAL:
                if (this.fatalMessage == null) {
                    this.fatalMessage = event.getValue();
                }
                break;
            case NOISE:
            case IGNORED:
            default:
                break;
        }
    }

    private void advancePhase(String candidate) {
        if (rank(candidate) > rank(this.phase)) {
            this.phase = candidate;
        }
    }

    // ---- lifecycle marks (called by GhostTools / the executor runner thread) ----

    public synchronized void markRunning() {
        if (STATUS_INIT.equals(this.status)) {
            this.status = STATUS_RUNNING;
        }
    }

    public synchronized void markUserPaused() {
        this.userPaused = true;
        if (STATUS_RUNNING.equals(this.status)) {
            this.status = STATUS_PAUSED;
        }
    }

    public synchronized void markUserResumed() {
        this.userPaused = false;
        if (STATUS_PAUSED.equals(this.status)) {
            this.status = STATUS_RUNNING;
        }
    }

    public synchronized void markCompleted() {
        if (!STATUS_FAILED.equals(this.status) && !STATUS_ABORTED.equals(this.status)) {
            this.status = STATUS_COMPLETED;
        }
    }

    public synchronized void markAborted(String reason) {
        this.status = STATUS_ABORTED;
        this.failReason = reason;
    }

    public synchronized void markFailed(String reason) {
        if (!STATUS_ABORTED.equals(this.status)) {
            this.status = STATUS_FAILED;
        }
        if (this.failReason == null) {
            this.failReason = reason;
        }
    }

    // ---- reads ----

    public synchronized String getStatus() { return status; }

    public synchronized String getOldTableName() { return oldTableName; }

    public synchronized boolean isFinished() {
        return STATUS_COMPLETED.equals(status) || STATUS_FAILED.equals(status) || STATUS_ABORTED.equals(status);
    }

    /** build the tailStatus wire payload */
    public synchronized GhostStatusDTO toStatusDTO() {
        GhostStatusDTO dto = new GhostStatusDTO();
        dto.setStatus(this.status);
        dto.setPhase(this.phase);
        dto.setCurValue(this.curValue);
        dto.setMaxValue(this.maxValue);
        dto.setUseProgress(this.maxValue > 0);
        dto.setOldTableName(this.oldTableName);
        dto.setFailReason(this.failReason != null ? this.failReason : this.fatalMessage);
        dto.setMessage(buildMessage());
        return dto;
    }

    private String buildMessage() {
        StringBuilder sb = new StringBuilder();
        sb.append("Phase: ").append(this.phase);
        if (this.maxValue > 0) {
            sb.append(String.format("; Copy: %d/%d %.1f%%", this.curValue, this.maxValue, this.percent));
            sb.append("; Applied: ").append(this.applied);
            if (this.backlog != null) {
                sb.append("; Backlog: ").append(this.backlog);
            }
        }
        if (this.lag != null) {
            sb.append("; Lag: ").append(this.lag).append("s");
        }
        if (this.stateWord != null) {
            sb.append("; State: ").append(this.stateWord);
        }
        if (this.eta != null) {
            sb.append("; ETA: ").append(this.eta);
        }
        if (this.oldTableName != null) {
            sb.append("; OldTable: ").append(this.oldTableName);
        }
        if (this.failReason != null) {
            sb.append("; Reason: ").append(this.failReason);
        }
        if (this.fatalMessage != null) {
            sb.append("; FATAL: ").append(this.fatalMessage);
        }
        return sb.toString();
    }
}
