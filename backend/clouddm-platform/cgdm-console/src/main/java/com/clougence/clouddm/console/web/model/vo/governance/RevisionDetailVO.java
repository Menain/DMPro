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
package com.clougence.clouddm.console.web.model.vo.governance;

import java.util.Date;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/**
 * Read-only revision detail — exposes the frozen revision's full content
 * (sqlText, rollbackSqlText, stmtManifest parsed, auditSnapshot parsed)
 * for the promotion page's upper section display (spec §6.3).
 * <p>
 * The manifest JSON array {@code [{idx, stmt_hash, version, pre_exec}]} is parsed
 * into {@link StmtManifestEntry} list. The auditSnapshot JSON
 * {@code {BEHAVIOR_ANALYSIS:{status,context}, SECURITY_RULE:..., DML_EXPLAIN:...}}
 * is returned as a list of {@link AuditSnapshotEntry}.
 */
@Getter
@Setter
public class RevisionDetailVO {

    private Long   revisionId;
    private String revisionCode;
    private String changeType;
    private Date   gmtCreate;
    private Long   sourceTicketId;

    private String sqlText;
    private String rollbackSqlText;
    private String sqlHash;
    private String rollbackSqlHash;

    private List<StmtManifestEntry>   stmtManifest;
    private List<AuditSnapshotEntry>   auditSnapshot;

    @Getter
    @Setter
    public static class StmtManifestEntry {
        private int    stmtIndex;
        private String stmtHash;
        private int    version;
        private String preExec;
    }

    @Getter
    @Setter
    public static class AuditSnapshotEntry {
        private String activityId;
        private String status;
        private Object context;
    }
}
