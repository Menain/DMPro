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
package com.clougence.clouddm.console.web.component.governance;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.clougence.utils.JsonUtils;
import com.clougence.utils.StringUtils;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * B6 M2 (design §6.1/§6.6): one segment of a PRE_DDL group's execution plan — the shared
 * model of BOTH carriers:
 * <ul>
 * <li>PLAN (plan of record): {@code precheck_result.segments} JSON key (additive), recomputed
 *     by every authoritative precheck run (submit / resubmit / check preview). Shape:
 *     {@code [{segIdx,type,stmtRange:[a,b]} | {segIdx,type,stmtIndex,table,rows,sql}]},
 *     keyed in the <b>1-based exec_order domain</b> (R11 — SplitScript.index + 1; the precheck
 *     preview index elsewhere is 0-based, never mix the two domains).</li>
 * <li>RUNTIME (mutable state): {@code dm_ticket_db_stmt.exec_segments} column (V202609290001) —
 *     the plan entries copied verbatim plus {@code status/jobBizId/sessionId/taskId/error/
 *     startedAt/finishedAt/summary}; mutated ONLY inside the advanceSegment ticket-row-lock
 *     window (segment status CAS guards every writer: callbacks / ghost hook / sweep / first
 *     dispatch all serialize on the same lock).</li>
 * </ul>
 * Segment types: {@code AUTOEXEC} = a run of consecutive plain statements (the existing
 * "1 group 1 job = N tasks sequential" shape — zero regression for pure-normal groups),
 * {@code GHOST} = exactly ONE threshold-exceeding hard-pass ALTER (GhostAlterStmtChecker is
 * single-statement by construction) executed through the gh-ost tool session,
 * {@code DML_BATCH} (B7 lock-free DML) = exactly ONE threshold-exceeding shape-gated
 * single-table UPDATE/DELETE executed through the console-side keyset batch loop
 * (DmlBatchAsyncTask, channel C) — no job row, no task rows, no sidecar involvement.
 * <p>
 * exec_order semantics: GLOBAL numbering WITH HOLES — ghost AND DML_BATCH statements consume
 * their number but never become task rows, so "exec_order = SplitScript.index + 1" keeps
 * holding for every task row that exists (governance-contracts stmt_index alignment, M2
 * revision + B7 N1 addendum).
 * <p>
 * Parsing is NEVER-THROW (JsonUtils sneaky-throws the checked JsonProcessingException): a
 * corrupt carrier degrades to null/empty and the caller falls back defensively.
 */
@Slf4j
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class TicketSegment {

    public static final String TYPE_AUTOEXEC  = "AUTOEXEC";
    public static final String TYPE_GHOST     = "GHOST";
    /** B7 lock-free DML (design §9): one threshold-exceeding shape-gated UPDATE/DELETE, keyset-batched. */
    public static final String TYPE_DML_BATCH = "DML_BATCH";

    public static final String STATUS_PENDING   = "PENDING";
    public static final String STATUS_EXECUTING = "EXECUTING";
    public static final String STATUS_SUCCESS   = "SUCCESS";
    public static final String STATUS_FAILED    = "FAILED";

    /**
     * B7 N2 window-crash manual verdicts (design §7.4, retryGroup FO additive field
     * {@code dmlBatchResolution}): RERUN_BATCH = re-execute the unresolved batch from the
     * inherited cursor (the normal continuation); SKIP_BATCH = re-derive one boundary from the
     * cursor and advance it WITHOUT executing the DML (the evidence lands in the checkpoint +
     * segment summary). Required iff the target segment's summary froze {@code unresolvedIntent}
     * (missing/invalid → GOV_TICKET_V2_DML_BATCH_RESOLUTION_REQUIRED, the B6 RETRY_UNSUPPORTED
     * narrowing precedent).
     */
    public static final String RESOLUTION_RERUN_BATCH = "RERUN_BATCH";
    public static final String RESOLUTION_SKIP_BATCH  = "SKIP_BATCH";

    /** 1-based segment position within the group plan (dense, 1..N) */
    private int               segIdx;
    /** AUTOEXEC | GHOST | DML_BATCH */
    private String            type;

    // ---- plan fields (both carriers) ----
    /** AUTOEXEC only: inclusive [from,to] statement bounds in the 1-based exec_order domain */
    private List<Integer>     stmtRange;
    /** GHOST / DML_BATCH only: the single statement's 1-based exec_order position */
    private Integer           stmtIndex;
    /** GHOST / DML_BATCH only: probed target table + B4-estimate row count (display/alert copy) */
    private String            table;
    private Long              rows;
    /** GHOST / DML_BATCH only: the FULL statement text (frozen at precheck; sql_content is frozen
     *  between precheck and execution, so the plan text and the re-split execution text are
     *  byte-identical — the dispatch is self-contained without a remote re-split) */
    private String            sql;
    /**
     * DML_BATCH only (B7 plan freeze, design §9 marking seam): the probed batch key frozen at
     * precheck — the execution-time dispatch NEVER re-probes (B6 "precheck is the main gate"
     * heritage; a key dropped by a concurrent DDL fails the batch loudly instead).
     */
    private BatchKey          batchKey;
    /**
     * DML_BATCH only: the per-statement affected-rows signal that fired the routing (EXPLAIN
     * estimate when available, else the probed table-row B4 estimate) — the progress display
     * denominator ("约" semantics) and the audit trail of the threshold decision.
     */
    private Long              estimatedRows;
    /**
     * DML_BATCH only: false when the shape analysis found a self-referencing SET ({@code x=x+1})
     * or a non-deterministic function in SET (NOW/RAND/UUID...) — replaying a batch of a
     * NON-idempotent statement can double-apply silently, so the flag drives the precheck yellow
     * flag and the window-crash manual-resolution copy (§7.3). Always true for DELETE.
     */
    private Boolean           idempotent;

    /**
     * DML_BATCH resume transport (B7 retryGroup checkpoint inheritance, design §7.4): the DONE-side
     * checkpoint ({@code cursor/batchesDone/rowsDone/lastDone}) copied from the terminal old task's
     * configData by {@code resumeGroupUnderLock}, consumed once by the re-dispatch (the fresh task's
     * configData checkpoint is seeded from it — "已成功批永不重放" is physically guaranteed by the
     * cursor inheritance; the old task is NEVER reused, B6 discipline). The intent side is
     * deliberately NOT inherited (a manual retry is the human decision; N2 adds the RERUN/SKIP
     * resolution guard). Stays on the runtime carrier as the forensic record of the inheritance.
     */
    private Map<String, Object> resumeCheckpoint;

    /**
     * DML_BATCH resume transport (B7 N2, design §7.4): set to {@link #RESOLUTION_SKIP_BATCH} by
     * {@code resumeGroupUnderLock} when the human verdict over an UNRESOLVED segment is SKIP —
     * consumed once by {@code submitForTicket} into the fresh task's configData
     * ({@code pendingResolution}), whose FIRST tick performs the boundary re-derivation +
     * cursor-only advance. RERUN needs no marker (the normal continuation IS the re-run).
     * Cleared by {@code resetSegmentForRerun} like every stale execution binding.
     */
    private String            pendingResolution;

    /**
     * DML_BATCH batch key (plan + runtime freeze). {@code column} is the BARE identifier already
     * validated against the {@code [\w$]+} whitelist at probe time (never user text; the rewrite
     * SPI re-checks and dialect-quotes it). {@code keyType} is one of the
     * {@code DmlBatchRangeSpec.KEY_TYPE_*} wire values (INT / DECIMAL0 / DATETIME / STRING) —
     * the console-side literal renderer picks the typed rendering off it (R18 single point).
     */
    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BatchKey {
        private String column;
        private String keyType;

        public BatchKey() {
        }

        public BatchKey(String column, String keyType) {
            this.column = column;
            this.keyType = keyType;
        }
    }

    // ---- runtime fields (exec_segments carrier only) ----
    /** PENDING | EXECUTING | SUCCESS | FAILED (null in the plan carrier) */
    private String            status;
    /** AUTOEXEC runtime: the segment job's biz id (recorded at dispatch) */
    private String            jobBizId;
    /** GHOST runtime: tool session id (recorded at launch; DML_BATCH has NO session — stays null) */
    private String            sessionId;
    /** GHOST / DML_BATCH runtime: the AsyncTask row id (recorded at launch — the completion CAS
     *  taskId cross-check key, B6 debt #5 discharged by the DML_BATCH seam) */
    private Long              taskId;
    /** failure detail of THIS segment (exec_detail keeps the pure-error group semantics) */
    private String            error;
    private Long              startedAt;
    private Long              finishedAt;
    /**
     * AUTOEXEC completion summary frozen BEFORE the next segment's job creation physically
     * deletes this segment's job+task rows (uk_exec_auto_job_group single-row ruling (a)):
     * {status, taskCount, error?, startedAt, finishedAt}.
     * DML_BATCH segments freeze their summary at every terminal write (B7 design §5.3):
     * {status, batchesDone, rowsDone, cursor?, error?, unresolvedIntent?, startedAt, finishedAt}
     * — the batch task's configData checkpoint survives on the task row, but the summary is the
     * group-carrier forensic trail (uk ruling (a) heritage).
     */
    private Map<String, Object> summary;

    public boolean isTerminalStatus() {
        return STATUS_SUCCESS.equals(status) || STATUS_FAILED.equals(status);
    }

    // ==================== static carrier helpers (never-throw) ====================

    /** Parse the PLAN from a precheck_result JSON; null/empty when absent or unparsable. */
    public static List<TicketSegment> parsePlan(String precheckResultJson) {
        if (StringUtils.isBlank(precheckResultJson)) {
            return null;
        }
        try {
            Map<?, ?> precheck = JsonUtils.toObj(precheckResultJson, Map.class);
            Object segments = precheck == null ? null : precheck.get("segments");
            if (!(segments instanceof List) || ((List<?>) segments).isEmpty()) {
                return null;
            }
            List<TicketSegment> plan = JsonUtils.toListUseType(JsonUtils.toJson(segments), TicketSegment.class);
            return plan == null || plan.isEmpty() ? null : plan;
        } catch (Exception e) {
            // JsonUtils sneaky-throws the CHECKED JsonProcessingException — catch Exception
            log.warn("[TicketSegment] unparsable segments plan (degraded to unsegmented)", e);
            return null;
        }
    }

    /** Parse the RUNTIME from an exec_segments JSON; null/empty when absent or unparsable. */
    public static List<TicketSegment> parseRuntime(String execSegmentsJson) {
        if (StringUtils.isBlank(execSegmentsJson)) {
            return null;
        }
        try {
            List<TicketSegment> runtime = JsonUtils.toListUseType(execSegmentsJson, TicketSegment.class);
            return runtime == null || runtime.isEmpty() ? null : runtime;
        } catch (Exception e) {
            log.warn("[TicketSegment] unparsable exec_segments runtime (degraded)", e);
            return null;
        }
    }

    /**
     * true when the group's precheck_result carries a segment plan containing a NON-DIRECT
     * (routed) segment — GHOST (B6) or DML_BATCH (B7). This predicate is the decision-order
     * "segments" branch gate at every dispatch point; B7 widened it from hasGhostSegment so a
     * DML_BATCH-only plan is recognized as segmented (a pure AUTOEXEC plan is never written —
     * the plan carrier only exists when ≥1 routed segment exists).
     */
    public static boolean isSegmentedPlan(String precheckResultJson) {
        return hasRoutedSegment(parsePlan(precheckResultJson));
    }

    public static boolean hasGhostSegment(List<TicketSegment> segments) {
        return hasType(segments, TYPE_GHOST);
    }

    /** B7: true when the plan/runtime contains at least one DML_BATCH segment. */
    public static boolean hasDmlBatchSegment(List<TicketSegment> segments) {
        return hasType(segments, TYPE_DML_BATCH);
    }

    /** B7: true when the plan/runtime contains at least one non-direct-execution segment. */
    public static boolean hasRoutedSegment(List<TicketSegment> segments) {
        return hasGhostSegment(segments) || hasDmlBatchSegment(segments);
    }

    private static boolean hasType(List<TicketSegment> segments, String type) {
        if (segments == null) {
            return false;
        }
        for (TicketSegment seg : segments) {
            if (seg != null && type.equals(seg.getType())) {
                return true;
            }
        }
        return false;
    }

    /** Build the fresh RUNTIME from a plan: plan fields copied, every segment PENDING. */
    public static List<TicketSegment> initRuntime(List<TicketSegment> plan) {
        List<TicketSegment> runtime = new ArrayList<>(plan.size());
        for (TicketSegment p : plan) {
            TicketSegment r = new TicketSegment();
            r.setSegIdx(p.getSegIdx());
            r.setType(p.getType());
            r.setStmtRange(p.getStmtRange() == null ? null : new ArrayList<>(p.getStmtRange()));
            r.setStmtIndex(p.getStmtIndex());
            r.setTable(p.getTable());
            r.setRows(p.getRows());
            r.setSql(p.getSql());
            // B7 DML_BATCH plan freeze (additive — null on GHOST/AUTOEXEC segments)
            r.setBatchKey(p.getBatchKey() == null ? null
                : new BatchKey(p.getBatchKey().getColumn(), p.getBatchKey().getKeyType()));
            r.setEstimatedRows(p.getEstimatedRows());
            r.setIdempotent(p.getIdempotent());
            r.setStatus(STATUS_PENDING);
            runtime.add(r);
        }
        return runtime;
    }

    /** First segment (list order = segIdx order) that is not terminal; null when all terminal. */
    public static TicketSegment firstNonTerminal(List<TicketSegment> runtime) {
        if (runtime == null) {
            return null;
        }
        for (TicketSegment seg : runtime) {
            if (seg != null && !seg.isTerminalStatus()) {
                return seg;
            }
        }
        return null;
    }

    /**
     * B6 M3 retry-resume target: the first segment that is NOT SUCCESS (list order = segIdx
     * order). Under the serial-progress invariant this is exactly the FAILED segment (its
     * predecessors are SUCCESS, its successors never left PENDING); a dead-body EXECUTING or an
     * undispatched PENDING segment is picked up by the same lookup. Null when every segment
     * already landed (nothing to resume).
     */
    public static TicketSegment firstResumable(List<TicketSegment> runtime) {
        if (runtime == null) {
            return null;
        }
        for (TicketSegment seg : runtime) {
            if (seg != null && !STATUS_SUCCESS.equals(seg.getStatus())) {
                return seg;
            }
        }
        return null;
    }

    /** The segment with the given 1-based segIdx; null when absent. */
    public static TicketSegment byIdx(List<TicketSegment> runtime, int segIdx) {
        if (runtime == null) {
            return null;
        }
        for (TicketSegment seg : runtime) {
            if (seg != null && seg.getSegIdx() == segIdx) {
                return seg;
            }
        }
        return null;
    }

    /** First PENDING segment after {@code afterSegIdx}; null when none remains. */
    public static TicketSegment nextPending(List<TicketSegment> runtime, int afterSegIdx) {
        if (runtime == null) {
            return null;
        }
        for (TicketSegment seg : runtime) {
            if (seg != null && seg.getSegIdx() > afterSegIdx && STATUS_PENDING.equals(seg.getStatus())) {
                return seg;
            }
        }
        return null;
    }

    /** The last recorded error among terminal segments (group exec_detail of a failed chain). */
    public static String lastTerminalError(List<TicketSegment> runtime) {
        String error = null;
        if (runtime != null) {
            for (TicketSegment seg : runtime) {
                if (seg != null && STATUS_FAILED.equals(seg.getStatus()) && StringUtils.isNotBlank(seg.getError())) {
                    error = seg.getError();
                }
            }
        }
        return error;
    }

    /** AUTOEXEC plan statement count (stmtRange span); 0 when the range is missing. */
    public int taskCount() {
        if (stmtRange == null || stmtRange.size() < 2) {
            return 0;
        }
        return Math.max(0, stmtRange.get(1) - stmtRange.get(0) + 1);
    }

    /** Build one plan segment (the precheck-side factory; 1-based exec_order domain). */
    public static TicketSegment autoexecPlan(int segIdx, int stmtFrom, int stmtTo) {
        TicketSegment seg = new TicketSegment();
        seg.setSegIdx(segIdx);
        seg.setType(TYPE_AUTOEXEC);
        seg.setStmtRange(List.of(stmtFrom, stmtTo));
        return seg;
    }

    public static TicketSegment ghostPlan(int segIdx, int stmtIndex, String table, Long rows, String sql) {
        TicketSegment seg = new TicketSegment();
        seg.setSegIdx(segIdx);
        seg.setType(TYPE_GHOST);
        seg.setStmtIndex(stmtIndex);
        seg.setTable(table);
        seg.setRows(rows);
        seg.setSql(sql);
        return seg;
    }

    /**
     * B7 DML_BATCH plan segment factory (design §9 marking seam): ONE threshold-exceeding
     * shape-gated UPDATE/DELETE with its frozen full text, probed batch key, routing row signal
     * and the idempotence mark — the execution-time dispatch is self-contained (no re-probe,
     * no re-analysis; sql_content is frozen between precheck and execution).
     */
    public static TicketSegment dmlBatchPlan(int segIdx, int stmtIndex, String table, Long estimatedRows,
                                             String sql, BatchKey batchKey, boolean idempotent) {
        TicketSegment seg = new TicketSegment();
        seg.setSegIdx(segIdx);
        seg.setType(TYPE_DML_BATCH);
        seg.setStmtIndex(stmtIndex);
        seg.setTable(table);
        seg.setEstimatedRows(estimatedRows);
        seg.setSql(sql);
        seg.setBatchKey(batchKey);
        seg.setIdempotent(idempotent);
        return seg;
    }
}
