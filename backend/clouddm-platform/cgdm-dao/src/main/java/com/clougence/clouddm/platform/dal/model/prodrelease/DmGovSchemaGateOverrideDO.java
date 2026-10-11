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
package com.clougence.clouddm.platform.dal.model.prodrelease;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

/**
 * Governed override of a production release schema gate denial (ADR-011).
 * <p>
 * One row = one grant: a permissioned DBA (GOV_SCHEMA_GATE_OVERRIDE) records a mandatory
 * reason and a time-boxed expiry (≤ 7 days); the gate consumes it SINGLE-USE (CAS
 * consumed 0→1) to downgrade one denial of ONE gate class (gateType PRECHECK|DIFF) to a
 * pass, writing a RELEASE_SCHEMA_GATE_OVERRIDE event with this row's trail.
 * stmtId null = release-level (any stmt of the release); non-null = stmt-level (matched
 * first). revoked marks a grant withdrawn before consumption.
 */
@Getter
@Setter
@TableName(value = "dm_gov_schema_gate_override")
public class DmGovSchemaGateOverrideDO {

    @TableId(type = IdType.AUTO)
    private Long   id;

    @TableField(insertStrategy = FieldStrategy.NOT_NULL, updateStrategy = FieldStrategy.NOT_NULL)
    private Date   gmtCreate;

    @TableField(insertStrategy = FieldStrategy.NOT_NULL, updateStrategy = FieldStrategy.NOT_NULL)
    private Date   gmtModified;

    private Long   releaseId;

    /** Null = release-level override; non-null = stmt-level (matched before release-level). */
    private Long   stmtId;

    /** PRECHECK | DIFF — one grant releases exactly one gate class. */
    private String gateType;

    /** Grantor uid (the real acting user — event/audit trail). */
    private String operatorUid;

    /** Mandatory governance reason (DAL NOT NULL, FO @NotBlank). */
    private String reason;

    /** Time-box: consumable only while now &lt; expireAt (DmAuthResDO.isEffective pattern). */
    private Date   expireAt;

    private int    consumed;

    private Date   consumedAt;

    /** Gate context summary frozen at consumption (gate/stmt/result/reason head). */
    private String consumedDetail;

    private int    revoked;

    private Date   revokedAt;

    private String revokedBy;
}
