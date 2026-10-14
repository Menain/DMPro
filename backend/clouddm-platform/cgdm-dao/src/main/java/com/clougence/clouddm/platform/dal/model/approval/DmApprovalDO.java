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
package com.clougence.clouddm.platform.dal.model.approval;

import java.util.Date;
import java.util.List;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.clougence.clouddm.platform.dal.handler.enums.RdpApprovalTypeHandler;
import com.clougence.utils.CollectionUtils;

import lombok.Data;

/**
 * @author Ekko
 * @date 2024/5/6 10:58
*/
@Data
@TableName(value = "dm_approval", autoResultMap = true)
public class DmApprovalDO {

    // basic information
    @TableId(type = IdType.AUTO)
    private Long                  id;
    private String                bizId;
    @TableField(insertStrategy = FieldStrategy.NOT_NULL, updateStrategy = FieldStrategy.NOT_NULL)
    private Date                  gmtCreate;
    @TableField(insertStrategy = FieldStrategy.NOT_NULL, updateStrategy = FieldStrategy.NOT_NULL)
    private Date                  gmtModified;
    private String                ownerUid;
    private String                primaryUid;
    private Long                  bindDsId;
    private String                targetInfo;
    private ApprovalBiz           approBiz;
    private String                description;
    private String                ticketTitle;
    private String                envName;
    private String                rawSql;
    private SqlContentType        contentType;
    @TableField(value = "levels", typeHandler = JacksonTypeHandler.class)
    private List<String>          levels;
    private Boolean               deleted;

    // ticket flow
    @TableField(typeHandler = RdpApprovalTypeHandler.class)
    private ApprovalType          approType;
    private String                approIdentity;
    private String                approTemplateName;
    private String                approTemplateIdentity;
    private String                approComment;
    private ApprovalStatus        ticketStatus;
    private String                statusMessage;
    private Date                  finishTime;
    private String                approvalUrl;
    @TableField(value = "features", typeHandler = JacksonTypeHandler.class)
    private List<ApprovalFeature> features;

    // ticket execution
    @TableField("`expected_affected_rows`")
    private Long                  expectedAffectedRows;
    private String                rollBackSql;
    private String                ticketInfo;

    // C10 recovery-type grading (task 10-14-recovery-type-grading; migration V202610140001,
    // additive nullable — stock rows NULL, read-time derived, never backfilled). The ticket-level
    // recovery semantics of the rollBackSql material (RecoveryType enum name in a varchar(32);
    // plain enum field like approBiz/ticketStatus — MyBatis maps by name). Written by the
    // rollback-material write points; the resubmit "material kept" branch leaves it null so the
    // MyBatis-Plus NOT_NULL strategy keeps the old value out of the UPDATE set. Deliberately NOT
    // in ApprovalMO/ticket_info (red line 5 — the JSON read-modify-write trap, database-guidelines
    // Common Mistake #5); NO recovery_verified companion column (red line 4 — VO constant instead).
    private RecoveryType          recoveryType;

    // DingTalk bridge outbound runtime state (task 10-09-dingtalk-approval-bridge, design §3.1).
    // Written ONLY by the outbound machinery (trigger/sweep/reissue) of External-channel rows;
    // NULL on every non-applicable row. Status vocabulary: NOT_STARTED/STARTING/STARTED/FAILED
    // (NULL reads as NOT_STARTED). These columns never touch gmt_modified (scheduler lease).
    private String                outboundStatus;
    private String                outboundError;
    private Integer               outboundRetryCount;
    private Date                  outboundNextRetryAt;

    // Explicit approval round (ADR-012, task 10-11-approval-round-binding; migration
    // V202610110002, NOT NULL DEFAULT 1). Born at 1; clearOutboundForNewRound increments it
    // (release resubmit = new round) while resetting appro_identity + the four outbound columns.
    // Read by the inbound ROUND_MISMATCH guard and the outbound flows/create context. Plain
    // field like the outbound four: MyBatis-Plus default NOT_NULL strategy keeps null out of
    // INSERT/UPDATE sets, so the DB default always materializes the round.
    private Integer               approvalRound;

    public boolean hasFeature(ApprovalFeature feature) {
        return CollectionUtils.isNotEmpty(this.features) && this.features.contains(feature);
    }

    public String getLevelPath() {
        if (CollectionUtils.isEmpty(this.levels)) {
            return "/";
        }
        StringBuilder sb = new StringBuilder("/");
        for (String level : this.levels) {
            sb.append(level);
            sb.append("/");
        }
        return sb.toString();
    }
}
