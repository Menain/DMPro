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
}
