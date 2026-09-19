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
package com.clougence.clouddm.console.web.model.vo.lockanalysis;

import lombok.Data;

/**
 * One lock-wait edge: a waiting session, the lock it waits for, and the blocking session
 * (design 09-17-lock-deadlock-analysis §4.1). Both dialects project onto these fields;
 * PG leaves trxId/rowsLocked/lockIndex/lockData null.
 */
@Data
public class LockWaitVO {

    /** Waiting session (connection) id. */
    private String waitingPid;

    /** SQL text of the waiting session; null when it is an idle transaction. */
    private String waitingQuery;

    /** Seconds the wait has been running. */
    private Long   waitSeconds;

    /** Locked object: schema.table (MySQL) / relation name or locktype (PG). */
    private String lockTable;

    private String lockIndex;

    private String lockType;

    private String lockMode;

    /** Locked key/rows description (MySQL LOCK_DATA). */
    private String lockData;

    /** Blocking session (connection) id — copy it to the session-management page to kill. */
    private String blockingPid;

    private String blockingTrxId;

    /** SQL text of the blocking session; null = idle transaction holding the lock. */
    private String blockingQuery;

    /** Transaction start time of the blocking session (display string). */
    private String blockingTrxStarted;

    private Long   blockingRowsLocked;
}
