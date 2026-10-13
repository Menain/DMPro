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
package com.clougence.clouddm.console.web.model.vo.prodrelease;

import java.util.List;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ProdReleaseDetailVO {

    private Long   id;
    private String releaseNo;
    private String title;
    private String status;
    private Long   approvalId;
    private String creatorUid;
    private String gmtCreate;
    private String gateResult;
    private String returnReason;
    private List<SourceTicketEntry> sourceTickets;
    private List<StmtGroup> stmtGroups;
    private List<EventEntry> events;

    @Getter
    @Setter
    public static class StmtGroup {

        private long   prodDsId;
        private String prodDbName;
        private List<StmtEntry> stmts;
    }

    @Getter
    @Setter
    public static class StmtEntry {

        private long   id;
        private int    seq;
        private String sqlContent;
        private String hash;
        private long   sourceTicketId;
        private long   sourceStmtId;
        private String sourceTicketType;   // PRE_DDL | PROD_DML (from source ticket ticketInfo)
        private String execStatus;
        private String execDetail;
        private String gmtCreate;
        /** ADR-013 M3 (design D10): the frozen-plan integrity pin (null = null-plan legacy stmt). */
        private String planHash;
        /**
         * ADR-013 M3 (design D10): the frozen segment manifest (seg_idx ascending) — null/empty
         * for a null-plan legacy stmt. Plan columns are the merge-time freeze; status/error/
         * timestamps are the runtime face (the review surface shows BOTH). sqlText is NOT exposed
         * (the stmt fulltext already renders above the block; C2 disclosure discipline).
         */
        private List<SegmentEntry> segments;
    }

    /**
     * One frozen-manifest segment row for the release-detail review surface (ADR-013 M3, R10).
     */
    @Getter
    @Setter
    public static class SegmentEntry {

        private int     segIdx;
        private String  type;              // AUTOEXEC | GHOST | DML_BATCH
        private Integer stmtRangeFrom;     // AUTOEXEC (1-based exec_order domain, inclusive)
        private Integer stmtRangeTo;
        private Integer stmtIndex;         // GHOST / DML_BATCH single-statement position
        private String  tableName;
        private Long    rowsEstimated;
        private String  batchKeyColumn;    // DML_BATCH
        private String  batchKeyType;      // DML_BATCH
        private Boolean idempotent;        // DML_BATCH
        private String  status;            // PENDING | EXECUTING | SUCCESS | FAILED
        private String  error;
        private Long    startedAt;
        private Long    finishedAt;
    }

    @Getter
    @Setter
    public static class EventEntry {

        private String eventType;
        private String fromStatus;
        private String toStatus;
        private String operatorUid;
        private String gmtCreate;
        private String eventData;
    }

    /**
     * Source ticket availability entry — only populated for RETURNED releases.
     * Dry-run validation: available=true means the ticket can be re-merged.
     */
    @Getter
    @Setter
    public static class SourceTicketEntry {

        private long   ticketId;
        private String ticketTitle;
        private String ticketType;       // PRE_DDL | PROD_DML
        private String ticketStatus;
        private boolean available;
        private String unavailableReason;
    }
}
