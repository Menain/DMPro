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
 * VO for openapi interface A — one row per ticket in the db-change ticket list.
 * Covers all parent R7 fields: ticketId, ticketType, title, serviceName,
 * dbNames, sqlSummary, execStatus, executedAt, promoted, releaseId, releaseNo, releaseStatus.
 */
@Getter
@Setter
public class OpenDbChangeTicketVO {

    private long       ticketId;
    private String     ticketType;     // PRE_DDL | PROD_DML
    private String     title;
    private String     serviceName;
    private List<String> dbNames;     // all DB names covered by this ticket
    private String     sqlSummary;     // first 200 chars of the first group's SQL
    private String     execStatus;     // derived from ticket_status + group exec_status
    private String     executedAt;     // gmtCreate of the first SUCCESS group (or null)
    private boolean    promoted;       // any stmt group already merged into a release
    private Long       releaseId;
    private String     releaseNo;
    private String     releaseStatus;
}
