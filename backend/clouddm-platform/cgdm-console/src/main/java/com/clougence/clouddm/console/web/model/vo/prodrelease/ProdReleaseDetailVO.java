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
package com.clougence.clouddm.console.web.model.vo.prodrelease;

import java.util.List;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ProdReleaseDetailVO {

    private Long   id;
    private String releaseNo;
    private String title;
    private String status;
    private Long   approvalId;
    private String creatorUid;
    private String gmtCreate;
    private String gateResult;
    private List<StmtGroup> stmtGroups;
    private List<EventEntry> events;

    @Getter
    @Setter
    public static class StmtGroup {

        private long   prodDsId;
        private String prodDbName;
        private List<StmtEntry> stmts;
    }

    @Getter
    @Setter
    public static class StmtEntry {

        private long   id;
        private int    seq;
        private String sqlContent;
        private String hash;
        private long   sourceTicketId;
        private long   sourceStmtId;
        private String execStatus;
        private String execDetail;
        private String gmtCreate;
    }

    @Getter
    @Setter
    public static class EventEntry {

        private String eventType;
        private String fromStatus;
        private String toStatus;
        private String operatorUid;
        private String gmtCreate;
        private String eventData;
    }
}
