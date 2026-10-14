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
import com.clougence.clouddm.platform.dal.model.prodrelease.DmGovRouteOverrideDO;

/**
 * Governed route-override rows (ADR-013 M3, design D8). Insert/select via BaseMapper + the
 * statements below; consumption is a CAS update (WHERE status = 'PENDING') so a concurrent
 * double-consume cannot happen — the C4 {@link DmGovSchemaGateOverrideMapper} shape (no
 * arbitrary update/delete; active uniqueness is service-enforced, the C4 migration precedent).
 */
public interface DmGovRouteOverrideMapper extends BaseMapper<DmGovRouteOverrideDO> {

    /**
     * Latest consumable override of one release stmt, or null.
     * Consumable = status PENDING AND expire_at &gt; now (the C4 findConsumable* shape,
     * single granularity — no release-level arm by design).
     */
    DmGovRouteOverrideDO findConsumable(@Param("releaseId") long releaseId,
                                        @Param("stmtId") long stmtId,
                                        @Param("now") Date now);

    /**
     * CAS consumption: status PENDING → CONSUMED + consumed_at + covered_seg_idxs.
     * Affected rows 0 = lost the race (already consumed) — the caller DENIES (fail-closed,
     * single-use strictness: an override can never release two blocks).
     */
    int consumeCas(@Param("id") long id,
                   @Param("consumedAt") Date consumedAt,
                   @Param("coveredSegIdxs") String coveredSegIdxs);

    /** All override rows of a release (UI list: PENDING/CONSUMED + derived EXPIRED). */
    List<DmGovRouteOverrideDO> queryByReleaseId(@Param("releaseId") long releaseId);

    /**
     * C8 M5.3 (V202610130004): materialize the derived EXPIRED status on the time-expired
     * PENDING row(s) of one (release, stmt) — the uk_route_override_active convergence step
     * run by create() before the insert (an expired-but-PENDING row would otherwise hold its
     * generated active_key forever and block the legitimate re-grant).
     */
    int expireStalePending(@Param("releaseId") long releaseId,
                           @Param("stmtId") long stmtId,
                           @Param("now") Date now);
}
