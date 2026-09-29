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

import lombok.Getter;
import lombok.Setter;

/**
 * Audit payload of the mutating GHOST operations (execute / pause / resume / stop /
 * drop-old-table), the {@code SessionKillAuditObj} pattern: the service returns it on the
 * success path only and the controller writes the audit row (resource = DATASOURCE, resId =
 * dsId — the ResourceType's own PK, the four-point audit contract).
 */
@Getter
@Setter
public class GhostOpAuditObj {

    /** tool session id (= AsyncTask bizId) */
    private String sessionId;
    private Long   dsId;
    private String dbName;
    private String tableName;
    /** the ALTER statement, truncated to {@link #DDL_MAX_LENGTH} */
    private String ddlSql;
    /** drop-old-table only: the timestamped old table being dropped */
    private String oldTableName;
    /** SUCCESS — audit rows are written on the success path only */
    private String result;

    /** B6 M1 (R10): ticket-source annotation of an AUTO-dispatched migration (null = standalone entry) */
    private Long   ticketId;
    private Long   groupId;
    /** dispatch origin label, e.g. TICKET_AUTO_ROUTE / TICKET_RETRY */
    private String via;

    /** max length of the DDL text stored in the audit row */
    public static final int DDL_MAX_LENGTH = 2000;
}
