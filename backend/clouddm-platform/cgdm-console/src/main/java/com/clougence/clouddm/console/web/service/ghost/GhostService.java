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

import com.clougence.clouddm.console.web.model.fo.ghost.GhostExecuteFO;
import com.clougence.clouddm.console.web.model.fo.ghost.GhostPrecheckFO;
import com.clougence.clouddm.console.web.model.vo.ghost.GhostExecuteVO;
import com.clougence.clouddm.console.web.model.vo.ghost.GhostOpAuditObj;
import com.clougence.clouddm.console.web.model.vo.ghost.GhostPrecheckVO;
import com.clougence.clouddm.console.web.model.vo.ghost.GhostTaskVO;
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
}
