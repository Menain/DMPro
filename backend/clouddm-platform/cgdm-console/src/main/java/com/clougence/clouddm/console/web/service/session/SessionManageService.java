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
package com.clougence.clouddm.console.web.service.session;

import java.util.List;

import com.clougence.clouddm.console.web.model.fo.session.SessionKillFO;
import com.clougence.clouddm.console.web.model.fo.session.SessionListFO;
import com.clougence.clouddm.console.web.model.vo.session.DbSessionListVO;
import com.clougence.clouddm.console.web.model.vo.session.SessionInstanceVO;
import com.clougence.clouddm.console.web.model.vo.session.SessionKillAuditObj;

/**
 * DBA session management (B2): list database sessions of a datasource instance and kill
 * runaway sessions, both through the short-lived diagnostic session channel (channel C:
 * QueryService.createSession → syncExecuteQuery → closeSession).
 */
public interface SessionManageService {

    /**
     * Datasources visible to the current tenant, filtered by the supported-type whitelist
     * (MySQL protocol family), sorted by display name.
     */
    List<SessionInstanceVO> listInstances(String puid, String uid);

    /**
     * List all sessions of the given datasource (SHOW FULL PROCESSLIST), with the
     * server-side protection matrix applied (killable / protectedReason / sharedAccount).
     */
    DbSessionListVO list(String puid, String uid, SessionListFO fo);

    /**
     * Kill one session (KILL CONNECTION). Re-queries the processlist server-side to
     * re-validate the target against the protection matrix before issuing KILL.
     *
     * @return audit payload for the controller to write the operation audit record
     */
    SessionKillAuditObj kill(String puid, String uid, SessionKillFO fo);
}
