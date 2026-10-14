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
package com.clougence.clouddm.platform.dal.model.monitor;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.*;
import com.clougence.clouddm.platform.dal.model.ResourceType;

import lombok.Getter;
import lombok.Setter;

/**
 * @author bucketli 2020/4/13 12:25
 */
@Getter
@Setter
@TableName(value = "dm_mon_op_audit")
public class DmMonOpAuditDO {

    @TableId(type = IdType.AUTO)
    private Long          id;
    @TableField(insertStrategy = FieldStrategy.NOT_NULL, updateStrategy = FieldStrategy.NOT_NULL)
    private Date          gmtCreate;
    @TableField(insertStrategy = FieldStrategy.NOT_NULL, updateStrategy = FieldStrategy.NOT_NULL)
    private Date          gmtModified;
    private String        uid;
    private String        ownerUid;
    private String        userName;
    private Date          operateDate;
    private String        sourceIp;
    private String        ip;
    private String        contextIn;
    private String        contextOut;
    private AuditType     auditType;
    private ResourceType  resourceType;
    private String        resourceValue;
    private String        resourceName;
    private String        operationUri;
    private SecurityLevel securityLevel;
    private String        uuidKey;
    private String        logInfo;
    private String        logPath;

    // ---- C8 M2 audit-chain columns (V202610130003; NULL on pre-C8 stock rows) ----
    /** GovSqlHashUtils hash of the FULL frozen statement text (governance stmt_hash join caliber). */
    private String        sqlFingerprint;
    /** the dm_approval ticket the audited action belongs to (mutually exclusive with releaseId). */
    private Long          ticketId;
    /** the dm_prod_release the audited action belongs to (mutually exclusive with ticketId). */
    private Long          releaseId;
    /** the arrival channel (CONSOLE / OPENAPI / SYSTEM_AUTO) — URI-inferred or explicit. */
    private AuditChannel  channel;
}
