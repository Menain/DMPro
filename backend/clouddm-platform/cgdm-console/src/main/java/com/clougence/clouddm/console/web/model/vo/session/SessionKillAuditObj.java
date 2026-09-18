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
package com.clougence.clouddm.console.web.model.vo.session;

import lombok.Data;

/**
 * Audit payload for a successful session kill (JSON-serialized into the operation audit logInfo).
 * Returned by {@code SessionManageService#kill} so the controller can write the audit record
 * explicitly (PermissionGroupController pattern: audit only after business success).
 */
@Data
public class SessionKillAuditObj {

    /** Killed session id in the target database. */
    private String sessionId;

    /** Session user (processlist User column). */
    private String targetUser;

    /** Session source host (processlist Host column). */
    private String targetHost;

    /** Session SQL digest (processlist Info column, truncated to 500 chars). */
    private String targetInfo;

    /** Business result, e.g. SUCCESS. */
    private String result;
}
