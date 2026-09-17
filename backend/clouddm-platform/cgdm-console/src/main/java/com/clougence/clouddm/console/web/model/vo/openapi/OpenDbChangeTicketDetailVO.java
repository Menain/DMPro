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
 * Response VO for the open API /tickets/detail endpoint.
 * <p>
 * Contains ticket base info + process list (with activities) + event trail.
 * Desensitized: does not expose AK/SK, internal exception stacks, or console-specific
 * permission flags (canApproval/canExecute/canClose).
 * <p>
 * Tenant-filtered: cross-tenant returns null (not-found semantics).
 */
@Getter
@Setter
public class OpenDbChangeTicketDetailVO {

    private Long   ticketId;
    private String bizId;
    private String ticketTitle;
    private String ticketType;
    private String ticketStatus;
    private String serviceName;
    private String approType;
    private String approIdentity;
    private String approTemplateName;
    private String description;
    private String statusMessage;
    private String ownerUserName;
    private String targetInfo;
    private String gmtCreate;
    private String gmtModified;
    private String finishTime;
    private String releaseId;
    private String releaseNo;
    private List<ProcessEntry> processList;
    private List<EventEntry>   events;

    @Getter
    @Setter
    public static class ProcessEntry {
        private String stage;         // APPROVAL | CONFIRM | EXECUTION
        private String processStatus; // INIT | FINISH | REJECT | CLOSED | FAIL
        private String startTime;
        private String finishTime;
        private String execMsg;
        private List<ActivityEntry> activities;
    }

    @Getter
    @Setter
    public static class ActivityEntry {
        private String activityTitle;
        private String activityStatus;
        private String startTime;
        private String finishTime;
        private String remark;
        private String approvalUser;
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
