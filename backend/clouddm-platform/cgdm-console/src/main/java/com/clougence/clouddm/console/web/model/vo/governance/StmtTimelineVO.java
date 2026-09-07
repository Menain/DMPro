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
 * Per-statement timeline view — aggregates stmt_version history, task terminal state,
 * and correction events by stmt_index (spec §3.4, Phase 5 design D5).
 */
@Getter
@Setter
public class StmtTimelineVO {

    private List<StmtGroup> groups;

    @Getter
    @Setter
    public static class StmtGroup {

        private int                stmtIndex;
        private String             currentStatus;
        private int                correctionCount;
        private List<VersionEntry> versions;
    }

    @Getter
    @Setter
    public static class VersionEntry {

        private int       version;
        private String    stmtHash;
        private String    source;
        private String    failReason;
        private String    operatorUid;
        private Date      gmtCreate;
    }
}
