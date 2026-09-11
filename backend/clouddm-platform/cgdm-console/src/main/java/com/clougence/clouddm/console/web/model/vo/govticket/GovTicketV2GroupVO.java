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
package com.clougence.clouddm.console.web.model.vo.govticket;

import lombok.Getter;
import lombok.Setter;

/**
 * VO returned by /dbChangeV2/groupList — one row per statement group in a ticket.
 */
@Getter
@Setter
public class GovTicketV2GroupVO {

    private Long   groupId;
    private Long   pairId;
    private Long   dsId;
    private String dbName;
    private String sqlContent;
    private String precheckResult;   // JSON string (design §4 contract)
    private String execStatus;       // PENDING | EXECUTING | SUCCESS | FAILED
    private String execDetail;
}
