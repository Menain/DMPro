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
package com.clougence.clouddm.console.web.model.vo.ticket;

import java.util.List;

import com.clougence.clouddm.console.web.component.approval.model.TicketRuleCheckResult;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalBehavior;
import com.clougence.clouddm.platform.dal.model.approval.SqlContentType;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DmQueryTicketVO {

    private Long                        id;
    private String                      bizId;
    private String                      gmtCreate;
    private String                      gmtModified;
    private Long                        dataSourceId;
    private SqlContentType              contentType;
    private Long                        attachmentId;
    private String                      attachmentFileName;
    private Long                        attachmentFileSize;
    private List<ApprovalBehavior>      behaviors;
    private Long                        totalCount;
    private String                      description;
    private String                      statusMessage;
    private Long                        expectedAffectedRows;
    private Boolean                     immediately;
    private String                      rollBackSql;
    private Boolean                     rollBackSqlGenerated;
    // C10 recovery-type grading (task 10-14-recovery-type-grading): the RecoveryType enum name
    // of the rollback material (dm_approval.recovery_type; stock rows are derived at READ time
    // from the material-source flags — never backfilled, design D5). Never null on the wire
    // (the honest UNSPECIFIED fallback).
    private String                      recoveryType;
    // C10 red line 4: NO recovery_verified DB column exists — the assembly stamps this CONSTANT
    // false until a real recovery-artifact verification/drill mechanism lands (O-C10-1). No
    // "verified" illusion is ever produced.
    private Boolean                     recoveryVerified;
    // Risk level + impact scope (design D4/D5: exposed for detail-page display)
    private String                      riskLevel;
    private String                      impactScopeText;
    // Row-estimate availability (task 10-11 M3): false = affected-rows estimate unavailable
    // (e.g. PG DML — EXPLAIN SPI unsupported); null = legacy rows without impactScope.
    private Boolean                     rowsEstimated;
    private String                      ticketMessage;
    private boolean                     autoExec;
    private List<TicketRuleCheckResult> checkedList;
}
