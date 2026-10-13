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

import com.clougence.clouddm.sdk.model.ghost.GhostOptionsDTO;
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

    /**
     * B6 M1 ticket binding (design §6.4): non-null ONLY for ticket-dispatched sessions
     * (PRE_DDL auto-route). The terminal writeback hook, the retryGroup rebuild lookup and the
     * sweep liveness probe all key on this pair; standalone-entry tasks keep them null and
     * their behavior is completely unchanged (dual-form compatibility).
     */
    private Long    ticketId;
    private Long    groupId;

    /**
     * B6 M2 segment binding: the 1-based segIdx of the GHOST segment this session executes
     * (multi-ghost-segment groups host one task per segment — the terminal hook CAS and the
     * sweep liveness probe key on the triple). Null for the M1 single-segment degenerate shape
     * and standalone tasks (their groups carry no exec_segments runtime — M1 path unchanged).
     * <p>
     * ADR-013 M2: REUSED as the release-chain GHOST segment binding (the 1-based segIdx of the
     * manifest row) — the release form carries releaseStmtId/releaseId below and keeps
     * ticketId/groupId null, so segmentIndex disambiguates the two chains' multi-segment shapes.
     */
    private Integer segmentIndex;

    /**
     * ADR-013 M2 release-chain binding (O-C7-2 de-facto seam, design D4): non-null ONLY for
     * release-dispatched GHOST sessions (a frozen-manifest GHOST segment replayed on prod).
     * MUTUALLY EXCLUSIVE with the ticketId/groupId pair above — the GhostAsyncTask terminal hook
     * routes ticketId!=null to the ticket chain (onGhostTerminal, ZERO diff) and
     * ticketId==null && releaseStmtId!=null to the release chain (onReleaseGhostTerminal); both
     * null = standalone (unchanged), both set = invalid (defensive skip + warn). Additive fields:
     * every ticket-chain and standalone task keeps them null and its behavior byte-identical.
     */
    private Long    releaseStmtId;
    private Long    releaseId;

    /**
     * B6 M1 (R14): snapshot of the EFFECTIVE gh-ost options at launch time — the resolved
     * result of the merge chain (task options → admin {@code ghostDefaultOptions} JSON →
     * plugin @ConfigDef defaults), persisted for auditability/reproduction.
     */
    private GhostOptionsDTO effectiveOptions;
}
