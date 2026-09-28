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
package com.clougence.clouddm.console.web.model.vo.ghost;

import java.util.Date;

import lombok.Getter;
import lombok.Setter;

/**
 * One row of the GHOST task list (R8: initiator / time / target table / DDL / status / result /
 * old-table cleanup state). Backed by {@code dm_exec_async_task} (bizType=GHOST) joined with the
 * persisted {@code configData} ({@code GhostAsyncTaskConfig}) — no new table (design §8).
 */
@Getter
@Setter
public class GhostTaskVO {

    private Long    taskId;
    /** tool session id (= AsyncTask bizId) */
    private String  sessionId;
    private String  title;

    /** AsyncTaskStatus name: INIT / WAIT_START / RUNNING / PAUSE / COMPLETE / FAILURE / CANCEL ... */
    private String  status;
    /** last status message (progress text, failure/abort reason) */
    private String  statusMsg;
    /** 0-100 when processType=PROGRESS, else null */
    private Integer progress;

    /** tool-level status captured from tailStatus (RUNNING / PAUSED / COMPLETED / FAILED / ABORTED); null before the first capture */
    private String  toolStatus;

    private String  initiatorUid;
    private String  initiatorName;

    private Long    dsId;
    private String  dsName;
    private String  dbName;
    private String  tableName;
    private String  ddlSql;

    /** timestamped old table captured at cut-over (R7); null until cut-over */
    private String  oldTableName;
    /** old table already dropped through the platform (R7) */
    private boolean oldTableDropped;

    private Date    gmtCreate;
    private Date    timeOfStart;
    private Date    timeOfFinish;
}
