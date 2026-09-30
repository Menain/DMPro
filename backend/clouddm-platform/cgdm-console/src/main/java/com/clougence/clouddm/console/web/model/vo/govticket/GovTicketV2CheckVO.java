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
        private List<com.clougence.clouddm.console.web.component.governance.TicketSegment> segments;
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
