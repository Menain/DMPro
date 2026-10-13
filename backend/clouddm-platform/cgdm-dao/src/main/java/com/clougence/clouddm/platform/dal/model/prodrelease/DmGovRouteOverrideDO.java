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
 * Governed ROUTE override of a production release frozen-plan dispatch block (ADR-013 M3,
 * design D8, 裁决 4). PARALLEL to — and fully isolated from — {@link DmGovSchemaGateOverrideDO}
 * (ADR-011): two tables, two permission labels (GOV_ROUTE_OVERRIDE ∨ GOV_SCHEMA_GATE_OVERRIDE),
 * two event types (RELEASE_ROUTE_OVERRIDE ∨ RELEASE_SCHEMA_GATE_OVERRIDE); consuming one NEVER
 * releases a denial of the other.
 * <p>
 * One row = one grant: a permissioned DBA records a mandatory reason and a time-boxed expiry
 * (≤ 7 days) for ONE release stmt (granularity (releaseId, stmtId) — stmtId NOT NULL, no
 * release-level blanket). The two route-block consumption points (a DML_BATCH segment's
 * RELEASE_ROUTE_UNSUPPORTED block ∨ a GHOST segment's RELEASE_ROUTE_CAPABILITY failure)
 * consume it SINGLE-USE (CAS status PENDING → CONSUMED) to release the blocked segment as a
 * DIRECT single-statement AUTOEXEC replay — explicit risk acceptance, never a silent downgrade.
 * <p>
 * status ∈ {PENDING, CONSUMED} in the DB; EXPIRED is derived at read time (the C4 twin's
 * derived-status pattern). coveredSegIdxs freezes the released segIdx list at consumption
 * (comma column, audit trail). PLAN_DRIFT / HASH_DRIFT are NOT overridable (integrity classes).
 */
@Getter
@Setter
@TableName(value = "dm_gov_route_override")
public class DmGovRouteOverrideDO {

    @TableId(type = IdType.AUTO)
    private Long   id;

    @TableField(insertStrategy = FieldStrategy.NOT_NULL, updateStrategy = FieldStrategy.NOT_NULL)
    private Date   gmtCreate;

    @TableField(insertStrategy = FieldStrategy.NOT_NULL, updateStrategy = FieldStrategy.NOT_NULL)
    private Date   gmtModified;

    private Long   releaseId;

    /** Grant granularity is (releaseId, stmtId) — NOT NULL (no release-level blanket, design D8). */
    private Long   stmtId;

    /** Mandatory governance reason (DAL NOT NULL, FO @NotBlank) — the explicit risk acceptance. */
    private String reason;

    /** Grantor uid (the real acting user — event/audit trail). */
    private String createdBy;

    /** PENDING | CONSUMED (DB states); EXPIRED is derived at read time (expire_at ≤ now). */
    private String status;

    /** Time-box: consumable only while now &lt; expireAt (DmAuthResDO.isEffective pattern). */
    private Date   expireAt;

    private Date   consumedAt;

    /** Comma list of the segIdx values released by the consumption (frozen at consume, audit). */
    private String coveredSegIdxs;
}
