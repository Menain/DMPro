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

@Getter
@Setter
public class PromotionDetailVO {

    private Long       id;
    private String     promotionCode;
    private String     promotionType;
    private Long       revisionId;
    private Long       logicalDbId;
    private Long       prodEnvId;
    private Long       prodDsId;
    private String     prodResPath;
    private Long       prodApprovalId;
    private String     executionKey;
    private String     status;
    private Date       gmtCreate;
    private Date       gmtModified;

    private List<GateItemVO>        gateResult;
    private List<GateItemVO>        preflightResult;
    private RevisionSummaryVO       revision;
    private List<EventHandlerVO>    events;

    @Getter
    @Setter
    public static class GateItemVO {
        private int    item;
        private String label;
        private boolean pass;
        private String reason;
        private String timestamp;
    }

    @Getter
    @Setter
    public static class RevisionSummaryVO {
        private Long   revisionId;
        private String revisionCode;
        private String changeType;
        private Date   gmtCreate;
        private Long   sourceTicketId;
        private String sqlHash;
        private int    stmtCount;
    }

    @Getter
    @Setter
    public static class EventHandlerVO {
        private Long   id;
        private String eventType;
        private String fromStatus;
        private String toStatus;
        private String operatorUid;
        private Date   gmtCreate;
        private String eventData;
    }
}
