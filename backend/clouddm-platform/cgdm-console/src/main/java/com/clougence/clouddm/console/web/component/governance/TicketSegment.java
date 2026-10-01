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
 * single-statement by construction) executed through the gh-ost tool session.
 * <p>
 * exec_order semantics: GLOBAL numbering WITH HOLES — ghost statements consume their number
 * but never become task rows, so "exec_order = SplitScript.index + 1" keeps holding for every
 * task row that exists (governance-contracts stmt_index alignment, M2 revision).
 * <p>
 * Parsing is NEVER-THROW (JsonUtils sneaky-throws the checked JsonProcessingException): a
 * corrupt carrier degrades to null/empty and the caller falls back defensively.
 */
@Slf4j
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class TicketSegment {

    public static final String TYPE_AUTOEXEC = "AUTOEXEC";
    public static final String TYPE_GHOST    = "GHOST";

    public static final String STATUS_PENDING   = "PENDING";
    public static final String STATUS_EXECUTING = "EXECUTING";
    public static final String STATUS_SUCCESS   = "SUCCESS";
    public static final String STATUS_FAILED    = "FAILED";

    /** 1-based segment position within the group plan (dense, 1..N) */
    private int               segIdx;
    /** AUTOEXEC | GHOST */
    private String            type;

    // ---- plan fields (both carriers) ----
    /** AUTOEXEC only: inclusive [from,to] statement bounds in the 1-based exec_order domain */
    private List<Integer>     stmtRange;
    /** GHOST only: the single statement's 1-based exec_order position */
    private Integer           stmtIndex;
    /** GHOST only: probed target table + B4-estimate row count (display/alert copy) */
    private String            table;
    private Long              rows;
    /** GHOST only: the FULL ALTER statement text (frozen at precheck; sql_content is frozen
     *  between precheck and execution, so the plan text and the re-split execution text are
     *  byte-identical — the dispatch is self-contained without a remote re-split) */
    private String            sql;

    // ---- runtime fields (exec_segments carrier only) ----
    /** PENDING | EXECUTING | SUCCESS | FAILED (null in the plan carrier) */
    private String            status;
    /** AUTOEXEC runtime: the segment job's biz id (recorded at dispatch) */
    private String            jobBizId;
    /** GHOST runtime: tool session id + AsyncTask row id (recorded at launch) */
    private String            sessionId;
    private Long              taskId;
    /** failure detail of THIS segment (exec_detail keeps the pure-error group semantics) */
    private String            error;
    private Long              startedAt;
    private Long              finishedAt;
    /**
     * AUTOEXEC completion summary frozen BEFORE the next segment's job creation physically
     * deletes this segment's job+task rows (uk_exec_auto_job_group single-row ruling (a)):
     * {status, taskCount, error?, startedAt, finishedAt}.
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

    /** true when the group's precheck_result carries a segment plan containing a GHOST segment. */
    public static boolean isSegmentedPlan(String precheckResultJson) {
        return hasGhostSegment(parsePlan(precheckResultJson));
    }

    public static boolean hasGhostSegment(List<TicketSegment> segments) {
        if (segments == null) {
            return false;
        }
        for (TicketSegment seg : segments) {
            if (seg != null && TYPE_GHOST.equals(seg.getType())) {
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
}
