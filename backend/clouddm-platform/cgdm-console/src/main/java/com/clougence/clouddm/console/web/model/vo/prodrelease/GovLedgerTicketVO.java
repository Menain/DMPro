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
public class GovLedgerTicketVO {

    private long   ticketId;
    private String ticketTitle;
    private String serviceName;
    private Long   serviceId;
    private String ownerUid;
    private String gmtCreate;
    private String execStatus;
    private String sqlSummary;
    private String ticketType;     // PRE_DDL | PROD_DML
    private String approvalMode;   // DIRECT | VIA_RELEASE (null for legacy tickets)
    private List<LedgerDbItemVO> preDbs;
    private List<String>         prodDbs;
    private boolean promoted;
    private String releaseNo;
    private String releaseStatus;
    /**
     * B6 M3 发布链过渡明示 (additive, display-only): the ticket ran ≥1 lock-free (gh-ost) routed
     * statement on pre — the release merge replays it as a DIRECT ALTER on prod (release-chain
     * ghost routing is M5), so the merge-confirm surface warns about the large-table lock risk.
     */
    private boolean ghostRouted;
    /**
     * B7 N3 发布链披露 (additive, display-only — the ghostRouted twin): the ticket carries ≥1
     * DML_BATCH-routed statement — the release merge replays it as ONE direct statement on prod
     * (release-chain batch routing is v2, the B6 M5 cadence), so the merge-confirm surface warns
     * about the direct long-lock/timeout risk.
     */
    private boolean dmlBatchRouted;
}
