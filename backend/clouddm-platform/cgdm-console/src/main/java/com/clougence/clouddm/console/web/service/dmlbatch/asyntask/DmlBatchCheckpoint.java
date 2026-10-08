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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * B7 lock-free DML — the batch-loop checkpoint (design §5.1), persisted inside the AsyncTask
 * configData (zero new tables/columns). The intent/done DUAL-WRITE ORDER is the window-crash
 * contract (§7.3, 裁决 #9 option C):
 * <ul>
 * <li>{@code intent} is written BEFORE the batch statement executes (seq = batchesDone + 1,
 *     lo = cursor at batch start);</li>
 * <li>{@code done} is written AFTER the batch committed (cursor advances to hi, batchesDone++,
 *     intent cleared) — ONE configData update;</li>
 * <li>a crash BETWEEN the DB commit and the done write leaves {@code intent != null} with no
 *     matching done: outcome UNKNOWN → the recovery surfaces (new task first tick / sweep)
 *     judge the segment FAILED + UNRESOLVED and hand the batch to manual resolution — never an
 *     automatic re-run (a non-idempotent UPDATE would double-apply SILENTLY).</li>
 * </ul>
 * Narrowing (裁决 #21): {@code intent.retryPending=true} means a DB error WAS received and
 * recorded — statement-level atomicity proves the batch had ZERO effect, so that shape is NOT
 * a window crash: the retry flow continues automatically.
 * <p>
 * "已成功批永不重放" is physically guaranteed by the cursor: a batch's rows are exactly
 * (lo, hi] under the frozen predicate, and the cursor only advances on a done write.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class DmlBatchCheckpoint {

    /** last DONE batch's hi bound as the RAW target-DB string (null = the first batch has not run). */
    private String   cursor;
    private long     batchesDone;
    private long     rowsDone;

    /** written before the batch executes; cleared by the done write. Null = no batch in flight. */
    private Intent   intent;
    /** the last completed batch (forensic pair of the intent write). */
    private Done     lastDone;

    /**
     * HEARTBEAT — written on EVERY tick, including paused/throttled/retry-waiting ticks (the
     * sweep's dead-judgement basis, design §6.6; "Wrong: 心跳只在执行 tick 写"). Rides along with
     * the intent/done write when one happens; a tick without either writes it standalone.
     */
    private Long     lastTickAt;

    /** replica-lag throttle state (N2 lag throttling, design §6.3; null = no probe result yet). */
    private LagState lagState;
    /** RUNNING / PAUSED / RETRYING / THROTTLED... — statusMsg material (design §5.1). */
    private String   lastStatus;

    /**
     * N2 SKIP_BATCH evidence trail (design §7.4): one record per manually-skipped batch — the
     * re-derived boundary {lo,hi} whose row set was advanced over WITHOUT executing the DML.
     * Frozen into the segment summary at the terminal (the "evidence 入 summary" contract);
     * append-only and bounded by the number of manual skips (a rare human action).
     */
    private java.util.List<SkipRecord> skips;

    /**
     * A batch in flight (or awaiting its retry). {@code seq} is idempotent under retry: the
     * same batch re-derives its boundary and re-writes the intent with the SAME seq.
     */
    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Intent {
        private long    seq;
        /** the exclusive lower bound this batch started from (raw string; null = first batch). */
        private String  lo;
        private Long    startedAt;
        /**
         * true once a DB error was RECEIVED AND RECORDED for this seq — statement-level atomicity
         * proves zero effect, so recovery continues the retry flow instead of escalating to
         * manual resolution (裁决 #21: UNRESOLVED = intent ∧ ¬retryPending).
         */
        private Boolean retryPending;
        private Integer retryCount;
        private String  lastBatchError;
    }

    /** one committed batch (or one N2 SKIP_BATCH cursor advance — see {@link #resolution}). */
    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Done {
        private long   seq;
        /** the inclusive upper bound actually executed (raw string). */
        private String hi;
        private long   affectRows;
        private Long   finishedAt;
        /**
         * N2 (design §7.4): {@code SKIP_BATCH} when this "done" is a manual skip — the cursor
         * advanced over the re-derived (lo,hi] WITHOUT executing the DML (affectRows stays 0,
         * the honest "not executed" value). Null on every normally-executed batch.
         */
        private String resolution;
        /** the exclusive lower bound of this batch (the skip evidence pair with {@link #hi}). */
        private String lo;
    }

    /** one manual SKIP_BATCH cursor advance (the segment-summary evidence record, design §7.4). */
    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SkipRecord {
        private long   seq;
        /** the cursor the skip started from (raw string; null = the first batch was skipped). */
        private String lo;
        /** the re-derived boundary the cursor advanced to (raw string). */
        private String hi;
        private Long   at;
    }

    /** N2 lag-throttle visibility (design §6.3): visible=false → fixed-interval degradation + yellow flag. */
    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LagState {
        private boolean visible;
        private Double  lastLagSec;
        private Long    throttledAt;
    }
}
