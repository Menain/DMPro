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
package com.clougence.clouddm.platform.dal.mapper.prodrelease;

import java.util.Date;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmGovSchemaGateOverrideDO;

/**
 * Governed schema-gate override rows (ADR-011). Insert/select via BaseMapper + the
 * statements below; consumption and revocation are CAS updates (WHERE consumed=0 /
 * revoked=0) so a concurrent double-consume or a consume-after-revoke cannot happen.
 */
public interface DmGovSchemaGateOverrideMapper extends BaseMapper<DmGovSchemaGateOverrideDO> {

    /**
     * Latest consumable stmt-level override (exact stmt match), or null.
     * Consumable = consumed=0 AND revoked=0 AND expire_at &gt; now.
     */
    DmGovSchemaGateOverrideDO findConsumableStmtLevel(@Param("releaseId") long releaseId,
                                                      @Param("stmtId") long stmtId,
                                                      @Param("gateType") String gateType,
                                                      @Param("now") Date now);

    /**
     * Latest consumable release-level override (stmt_id IS NULL), or null.
     */
    DmGovSchemaGateOverrideDO findConsumableReleaseLevel(@Param("releaseId") long releaseId,
                                                         @Param("gateType") String gateType,
                                                         @Param("now") Date now);

    /**
     * CAS consumption: consumed 0→1 + trail columns. Affected rows 0 = lost the race
     * (already consumed) — caller must fall through to the release-level candidate or deny.
     */
    int consumeCas(@Param("id") long id,
                   @Param("consumedAt") Date consumedAt,
                   @Param("consumedDetail") String consumedDetail);

    /**
     * CAS revocation: only an unconsumed, unrevoked row can be revoked.
     * Affected rows 0 = already consumed/revoked — caller rejects.
     */
    int revokeCas(@Param("id") long id,
                  @Param("revokedAt") Date revokedAt,
                  @Param("revokedBy") String revokedBy);

    /** All override rows of a release (UI list: active/consumed/expired/revoked). */
    List<DmGovSchemaGateOverrideDO> queryByReleaseId(@Param("releaseId") long releaseId);

    /**
     * C8 M5.3 (V202610130004): set the materialized {@code expired} mark on the time-expired
     * ACTIVE row(s) of exactly the grant's granularity (gate_type + stmt/release level) — the
     * uk_gate_override_active convergence step run by grant() before the insert.
     */
    int expireStaleActive(@Param("releaseId") long releaseId,
                          @Param("stmtId") Long stmtId,
                          @Param("gateType") String gateType,
                          @Param("now") Date now);
}
