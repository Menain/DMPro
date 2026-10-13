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
package com.clougence.clouddm.console.web.service.ghost;

import java.util.List;

import com.clougence.clouddm.base.metadata.ds.DataSourceConfig;
import com.clougence.clouddm.console.web.model.fo.ghost.GhostExecuteFO;
import com.clougence.clouddm.console.web.model.fo.ghost.GhostPrecheckFO;
import com.clougence.clouddm.console.web.model.vo.ghost.GhostExecuteVO;
import com.clougence.clouddm.console.web.model.vo.ghost.GhostOpAuditObj;
import com.clougence.clouddm.console.web.model.vo.ghost.GhostPrecheckVO;
import com.clougence.clouddm.console.web.model.vo.ghost.GhostRouteProbe;
import com.clougence.clouddm.console.web.model.vo.ghost.GhostTaskVO;
import com.clougence.clouddm.platform.dal.model.datasource.DmDsDO;
import com.clougence.clouddm.platform.dal.model.execution.DmExecAsyncTaskDO;
import com.clougence.clouddm.sdk.model.ghost.GhostStatusDTO;
import com.clougence.clouddm.sdk.model.ghost.GhostTailResponseDTO;

/**
 * Lock-free DDL (gh-ost) console service (B6 S4, design 09-17-online-ddl-activation §4).
 * <p>
 * The {@code (puid, uid, ...)} methods are the controller-facing surface: tenant ownership is
 * resolved through the GHOST AsyncTask row (bizId = tool sessionId) and every tool-session call
 * runs under the LAUNCHING user's uid (session lookups are uid+sessionId), so co-admins of the
 * same realm can manage each other's migrations (the governance "admin semantics" convention).
 * <p>
 * The {@code *ForTask}/{@code *Session} methods are the trusted handler surface used by
 * {@code GhostAsyncTask} only — no tenant check there, the handler already runs inside the
 * platform scheduler on behalf of the recorded owner uid.
 */
public interface GhostService {

    // ---- tool-level status wire values (GhostProgressState domain, mirrored for the console) ----
    String STATUS_INIT      = "INIT";
    String STATUS_RUNNING   = "RUNNING";
    String STATUS_PAUSED    = "PAUSED";
    String STATUS_COMPLETED = "COMPLETED";
    String STATUS_FAILED    = "FAILED";
    String STATUS_ABORTED   = "ABORTED";

    /** R3/R4 precheck: hard blocks + threshold suggestions + B4-caliber table stats */
    GhostPrecheckVO precheck(String puid, String uid, GhostPrecheckFO fo);

    /** R2 launch: create the GHOST tool session, START it and submit the tracking AsyncTask */
    GhostExecuteVO execute(String puid, String uid, GhostExecuteFO fo);

    /** paged log tail; falls back to the task record when the session is already gone */
    GhostTailResponseDTO tailLog(String puid, String uid, String sessionId, int startLine);

    /** R6 progress pass-through; persists oldTableName/lastStatus on change (R7 carrier) */
    GhostStatusDTO tailStatus(String puid, String uid, String sessionId);

    /** R5 throttle-flag pause; returns the success-path audit payload */
    GhostOpAuditObj pause(String puid, String uid, String sessionId);

    /** R5 throttle-flag resume; returns the success-path audit payload */
    GhostOpAuditObj resume(String puid, String uid, String sessionId);

    /** R5 panic-flag stop; returns the success-path audit payload */
    GhostOpAuditObj stop(String puid, String uid, String sessionId);

    /** release the tool session (+ cancel the dock task); no audit (not in the R10 set) */
    void close(String puid, String uid, String sessionId);

    /** R7 old-table drop through channel C; returns the success-path audit payload */
    GhostOpAuditObj dropOldTable(String puid, String uid, String sessionId);

    /** R8 tenant-wide task list (bizType=GHOST) */
    List<GhostTaskVO> tasks(String puid, String uid);

    // ---- trusted handler surface (GhostAsyncTask only) ----

    boolean hasSession(String ownerUid, String sessionId);

    /** tailStatus pass-through WITHOUT tenant check + state capture; null when the session is gone */
    GhostStatusDTO tailStatusForTask(String ownerUid, String sessionId);

    /** tool invoke(PAUSE) only — the dock task transition is driven by the scheduler interrupt */
    void pauseSession(String ownerUid, String sessionId);

    /** tool invoke(RESUME) only */
    void resumeSession(String ownerUid, String sessionId);

    /**
     * Ticket-form terminal session release (B6 M1, deviation-6 closure): closeSession ONLY —
     * never-throw, no tenant check, no cancelTask, no audit (the caller IS the task transition).
     * The sidecar releases the GHOST concurrency slot (cap 2) and the session-map entry only on
     * session close, so every terminal outcome of a ticket-bound migration must call this;
     * standalone sessions keep their explicit /ghost/close button path ({@link #close}).
     */
    void releaseSessionQuietly(String ownerUid, String sessionId);

    // ---- B6 M1 ticket auto-route surface (trusted console-internal: ticket chain only) ----

    /**
     * Ticket-dispatched launch (design §6.3): identical to {@link #execute} plus the
     * ticketId/groupId binding persisted into the AsyncTask configData — the terminal
     * writeback hook, retryGroup rebuild and sweep liveness probe all key on that pair.
     * NEVER expose through a controller (the binding is a system decision, not user input).
     * <p>
     * B6 M2: {@code segmentIndex} additionally binds the launch to ONE GHOST segment of a
     * segmented group (1-based segIdx; the terminal hook / sweep probe CAS on it). Null for
     * the M1 single-segment degenerate shape (existing tickets keep the M1 path — compatibility).
     */
    GhostExecuteVO executeForTicket(String puid, String uid, GhostExecuteFO fo, long ticketId, long groupId,
                                    Integer segmentIndex);

    /**
     * Auto-route precheck probe (design §6.1): one channel-C round trip — B4-caliber table
     * stats + PK/UK existence + admin threshold hit flags. NEVER-THROW degradation contract:
     * any failure (timeout/unreachable/table gone/bad identifier) returns {@code probed=false}
     * so the ticket precheck falls back to the status-quo direct chain.
     */
    GhostRouteProbe probeForRoute(String uid, DmDsDO dsDO, DataSourceConfig dsConfig, String dbName, String tableName);

    /**
     * B6 M3 segment-retry target-effect probe (M1 check 前瞻①): true ONLY when the frozen
     * statement is a decidable single-action {@code ADD [COLUMN] <col>} (v1 pragmatic scope —
     * {@link GhostResumeProbe}) AND {@code <col>} already exists in the target table's
     * {@code information_schema.COLUMNS} — i.e. the migration of a lost-writeback segment
     * actually landed, so the retry must advance it as SUCCESS instead of replaying the ALTER
     * over an already-altered table. Every other outcome — undecidable shape (MODIFY/DROP/
     * multi-action), column absent, probe failure, timeout, non-MySQL — returns false and the
     * caller conservatively REBUILDS (gh-ost's duplicate-column error is the safe backstop).
     * NEVER-THROW (trusted ticket-chain surface; channel C one round trip).
     */
    boolean probeAddColumnLanded(String uid, long dsId, String dbName, String alterSql);

    /**
     * Locate the NEWEST GHOST AsyncTask bound to a ticket group (configData ticketId/groupId);
     * null when the group never dispatched a ghost migration. Bounded scan (newest
     * {@code TASK_LIST_LIMIT} GHOST rows) — the group binding lives in the configData JSON.
     */
    DmExecAsyncTaskDO findTaskForTicketGroup(long ticketId, long groupId);

    /**
     * B6 M2 segment-precise variant (M1 check 前瞻③): additionally requires the configData
     * {@code segmentIndex} binding — a segmented group can host MULTIPLE ghost segments (one
     * task each), so the sweep probe / writeback must not resolve "newest of the group" across
     * segments. {@code segmentIndex == null} degrades to the M1 two-key match (its tasks carry
     * no segmentIndex binding). LIKE pre-filters on the compact-JSON keys bound the scan; the
     * parse-equality check stays the authority.
     */
    DmExecAsyncTaskDO findTaskForTicketGroup(long ticketId, long groupId, Integer segmentIndex);

    // ---- ADR-013 M2 release-chain GHOST surface (trusted console-internal: release chain only) ----

    /**
     * Release-dispatched launch (design D4, the O-C7-2 de-facto seam): the release-chain twin of
     * {@link #executeForTicket} — identical session/launch/AsyncTask shape, but the configData
     * binding carries {@code releaseStmtId/releaseId/segmentIndex} INSTEAD OF {@code ticketId/
     * groupId} (mutually exclusive — the GhostAsyncTask terminal hook routes on it). NEVER expose
     * through a controller (the binding is a system decision). The launch itself proves the gh-ost
     * binary/tool availability (a GHOST_BINARY_MISSING failure surfaces as an ErrorMessageException
     * the caller translates into RELEASE_ROUTE_CAPABILITY).
     */
    com.clougence.clouddm.console.web.model.vo.ghost.GhostExecuteVO executeForRelease(
        String puid, String uid, com.clougence.clouddm.console.web.model.fo.ghost.GhostExecuteFO fo,
        long releaseStmtId, long releaseId, Integer segmentIndex);

    /**
     * ADR-013 M2 capability re-check (裁决 2, R6): verify the gh-ost HARD PRECONDITIONS against the
     * prod target before a frozen GHOST segment replays — dialect (MySQL family), direct connection
     * (no SSH/SSL), the channel-C diagnostic session reachable, and the target table present with a
     * PK/UK. CAPABILITY ONLY: the routing decision (thresholds/rows/matrix) is NEVER re-evaluated
     * (red line 4). NEVER-THROW — every failure/timeout degrades to {@code capable=false} carrying
     * the failed item code (the caller writes RELEASE_ROUTE_CAPABILITY + blocks; it does not throw).
     * Not cached (the plan is frozen, the environment is not).
     */
    com.clougence.clouddm.console.web.model.vo.ghost.ReleaseGhostCapability probeReleaseCapability(
        String uid, long dsId, String dbName, String tableName);

    /**
     * ADR-013 M2: locate the NEWEST GHOST AsyncTask bound to a release stmt's GHOST segment
     * (configData releaseStmtId + segmentIndex); null when never dispatched. The release-side sweep
     * liveness probe / writeback-lost repair key on it (the release twin of
     * {@link #findTaskForTicketGroup(long, long, Integer)}). Bounded newest-first scan; the
     * parse-equality check stays the authority.
     */
    DmExecAsyncTaskDO findTaskForReleaseStmt(long releaseStmtId, Integer segmentIndex);
}
