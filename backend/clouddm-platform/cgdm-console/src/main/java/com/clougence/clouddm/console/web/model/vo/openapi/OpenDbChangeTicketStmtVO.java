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
package com.clougence.clouddm.console.web.model.vo.openapi;

import java.util.List;

import lombok.Getter;
import lombok.Setter;

/**
 * VO for openapi interface B — ticket meta + per-DB statement groups.
 * precheckResult is passed through as-is (P2 JSON contract).
 */
@Getter
@Setter
public class OpenDbChangeTicketStmtVO {

    private long   ticketId;
    private String ticketType;     // PRE_DDL | PROD_DML
    private String title;
    private String serviceName;
    private String ticketStatus;   // raw approval ticket status
    private List<Group> groups;

    @Getter
    @Setter
    public static class Group {

        private long   groupId;
        private long   pairId;
        private long   dsId;
        private String dbName;
        private String sqlContent;
        private String precheckResult;   // JSON string — P2 §4 contract, passed through raw
        private String execStatus;       // PENDING | EXECUTING | SUCCESS | FAILED
        private String execDetail;
    }
}
