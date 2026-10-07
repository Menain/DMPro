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

import java.util.List;

import lombok.Getter;
import lombok.Setter;

/**
 * VO returned by /dbChangeV2/check — per-group precheck preview.
 */
@Getter
@Setter
public class GovTicketV2CheckVO {

    private String              ticketType;
    private List<GroupResult>   groups;

    @Getter
    @Setter
    public static class GroupResult {
        private Long                pairId;
        private String              dbName;
        private String              changeType;    // DDL | DML | MIXED
        private String              checkStatus;   // PASS | FAIL
        private List<StmtPreview>   statements;
        private RulesCheckSummary   rulesCheck;
        private String              errorMessage;  // populated when checkStatus == FAIL

        // Row estimation (design D3) — additive, null on legacy rows.
        private Long    estimatedRows;    // sum of per-statement EXPLAIN estimates
        private Boolean rowsEstimated;    // false if any statement failed/unsupported

        // B6 M1 ghost auto-route evaluation (additive; null/empty = no ghost evaluation took
        // place — non-PRE_DDL, switch off, non-MySQL or no ALTER candidate). Display carrier:
        // serialized into the group's precheck_result JSON under the same "ghostRoutes" key.
        private List<GhostRouteVO> ghostRoutes;

        // B6 M2 segment PLAN (additive; null/empty = no GHOST segment → the group keeps the
        // legacy unsegmented shape byte-identical). Serialized into precheck_result "segments"
        // (plan of record, contract :404); 1-based exec_order domain keys (R11).
        // B7: the write condition widens to "≥1 GHOST ∨ DML_BATCH segment" — a pure-normal group
        // still keeps the legacy shape byte-identical (regression pin).
        private List<com.clougence.clouddm.console.web.component.governance.TicketSegment> segments;

        // B7 N1b DML batch auto-route evaluation (additive; null/empty = no evaluation took
        // place — kill switch off, unsupported dialect/version, or no UPDATE/DELETE candidate).
        // Display carrier: serialized into the group's precheck_result JSON under the
        // "dmlBatchRoutes" key (the execution-time authority stays the positional flags + plan).
        private List<DmlBatchRouteVO> dmlBatchRoutes;
    }

    /**
     * B7 N1b: one evaluated UPDATE/DELETE statement's batch-routing decision (design §13 boundary
     * matrix), or the group-level same-table accumulation hint (R13 display-level anti-gaming).
     * {@code stmtIndex} is 1-based in the exec_order domain (R11); the accumulation hint carries
     * stmtIndex=0 (group-level, no single statement).
     */
    @Getter
    @Setter
    public static class DmlBatchRouteVO {

        /** route: the statement WILL execute in throttled keyset batches (DML_BATCH segment). */
        public static final String ROUTE_DML_BATCH              = "DML_BATCH";
        /** route: shape gate rejected (multi-table/CTE/ORDER BY/LIMIT/comment/...) → direct execution + grey hint. */
        public static final String ROUTE_DIRECT_SHAPE_UNSUPPORTED = "DIRECT_SHAPE_UNSUPPORTED";
        /** route: a key exists but is outside the v1 whitelist (composite/float/exotic) → direct + yellow flag. */
        public static final String ROUTE_DIRECT_KEY_UNSUPPORTED = "DIRECT_KEY_UNSUPPORTED";
        /** route: the key probe degraded (timeout/failure) → direct + grey hint (not a rejection — unknown ≠ absent). */
        public static final String ROUTE_DIRECT_PROBE_DEGRADED  = "DIRECT_PROBE_DEGRADED";
        /** group-level hint: same-table DML statements accumulate ≥ threshold without per-statement routing (R13). */
        public static final String ROUTE_SAME_TABLE_ACCUMULATE  = "SAME_TABLE_ACCUMULATE";

        /** 1-based statement position in the exec_order domain; 0 for the group-level accumulate hint. */
        private int     stmtIndex;
        /** one of the ROUTE_* values above. */
        private String  route;
        private String  tableName;

        /** the threshold signal that fired: per-statement EXPLAIN estimate, else the probed table rows. */
        private Long    estimatedRows;
        /** probed B4-caliber table rows (nullable when the probe degraded). */
        private Long    tableRows;
        /** the admin row threshold in effect (display copy). */
        private Long    rowThreshold;

        /** ROUTE_DML_BATCH: the frozen batch key. */
        private String  keyColumn;
        private String  keyType;
        /** ROUTE_DML_BATCH: false = self-referencing SET / non-deterministic function (window-crash copy). */
        private Boolean idempotent;
        /** ROUTE_DML_BATCH: announce-copy parameters (预计 N 批). */
        private Integer batchSize;
        private Long    approxBatches;

        /** ROUTE_DIRECT_SHAPE_UNSUPPORTED: the stable DmlBatchShapeMO rejectCode. */
        private String  rejectCode;
        /** ROUTE_DIRECT_KEY_UNSUPPORTED: the probe's human-copy reason (composite/float/nullable...). */
        private String  keyDetail;

        /**
         * The FULL shape analysis record (N1a handover obligation #7: embedded verbatim under the
         * precheck_result additive contract — SplitQueryType serializes as its enum name; the open
         * API passes the carrier through untouched). Null for the accumulate hint.
         */
        private com.clougence.clouddm.sdk.sql.editor.rewrite.DmlBatchShapeMO shape;

        /** already-translated display line (GHOST_ROUTE_ANNOUNCE precedent — the frontend renders verbatim). */
        private String  message;
    }

    /**
     * One evaluated ALTER statement's routing decision (design §6.10 boundary matrix).
     * {@code stmtIndex} is 1-based in the exec_order domain (R11 — pinned by unit tests).
     */
    @Getter
    @Setter
    public static class GhostRouteVO {

        /** route decision: gh-ost auto-route */
        public static final String ROUTE_GHOST       = "GHOST";
        /** route decision: direct execution + IM alert push (threshold exceeded but not lock-free capable) */
        public static final String ROUTE_ALERT_PASS  = "ALERT_PASS";

        /** 1-based statement position in the exec_order domain (SplitScript.index + 1) */
        private int     stmtIndex;
        /** GHOST | ALERT_PASS */
        private String  route;
        private String  tableName;

        /** B4-estimate stats + the admin thresholds in effect (display/alert copy) */
        private Long    tableRows;
        private Long    totalBytes;
        private Long    sizeThresholdBytes;
        private Long    rowThreshold;

        /** ALERT_PASS only: NO_UNIQUE_KEY | TUNNEL_UNSUPPORTED (AlertContext risk codes) */
        private String  alertReason;
        /** already-translated display line (B3 privilegeHint precedent — frontend renders verbatim) */
        private String  message;
    }

    @Getter
    @Setter
    public static class StmtPreview {
        private int     index;
        private String  type;     // SplitQueryType name
        private String  sql;      // truncated for display
    }

    @Getter
    @Setter
    public static class RulesCheckSummary {
        private boolean                 checked;
        private List<RuleMessage>       messages;
    }

    @Getter
    @Setter
    public static class RuleMessage {
        private String  level;   // WARN | BLOCK | PASS
        private String  rule;
        private String  message;
    }
}
