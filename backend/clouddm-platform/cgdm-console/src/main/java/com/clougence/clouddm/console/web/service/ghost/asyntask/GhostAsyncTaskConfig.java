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
package com.clougence.clouddm.console.web.service.ghost.asyntask;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * {@code dm_exec_async_task.configData} payload of a GHOST task (the {@code FakerAsyncTaskConfig}
 * pattern plus the B6 result-metadata fields — Step 0 ruling: configData is the persistence
 * carrier for the cut-over old-table name, no new table).
 * <p>
 * The first six fields are written once at launch; {@code oldTableName} / {@code lastStatus} are
 * captured from tailStatus while the migration runs (console pass-through and the handler share
 * one write path, write-on-change only); {@code oldTableDropped*} record the R7 drop action.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class GhostAsyncTaskConfig {

    /** launching user — the uid the tool session belongs to (session lookups are uid+sessionId) */
    private String  userId;
    private String  sessionId;
    private Long    dsId;
    private String  dbName;
    private String  tableName;
    private String  ddlSql;

    /** captured from GhostStatusDTO.oldTableName at cut-over (magic cut-over table log line) */
    private String  oldTableName;
    /** last tool-level status seen: INIT / RUNNING / PAUSED / COMPLETED / FAILED / ABORTED */
    private String  lastStatus;

    /** old table already dropped through the platform (R7) */
    private Boolean oldTableDropped;
    private String  oldTableDropUid;
    private Long    oldTableDropTime;
}
