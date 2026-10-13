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
package com.clougence.clouddm.console.web.component.governance;

import java.util.List;

import com.clougence.clouddm.console.web.model.fo.prodrelease.RouteOverrideGrantFO;
import com.clougence.clouddm.console.web.model.vo.prodrelease.RouteOverrideVO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmGovRouteOverrideDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseStmtDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseStmtSegmentDO;

/**
 * Governed override of production release ROUTE blocks (ADR-013 M3, design D8, 裁决 4).
 * <p>
 * Placed in component/governance next to {@link SchemaGateOverrideService} because the release
 * orchestrator (ReleaseSegmentService) consumes it at the two route-block points. PARALLEL to —
 * and fully ISOLATED from — the C4 schema-gate override: separate table
 * (dm_gov_route_override), separate permission label (GOV_ROUTE_OVERRIDE), separate event
 * (RELEASE_ROUTE_OVERRIDE); consuming one NEVER releases a denial of the other (pin).
 * <p>
 * Contract:
 * <ul>
 * <li>create: tenant check (puid == release.primaryUid), stmt ownership, reason mandatory
 *     (≤ 1024), expireAt strictly future and ≤ {@link #MAX_VALIDITY_MS} ahead, ACTIVE-UNIQUE
 *     (at most one consumable PENDING per (releaseId, stmtId) — service-enforced, the C4
 *     migration shape: no DB partial-unique constraint exists);</li>
 * <li>consumption: SINGLE-USE CAS (status PENDING → CONSUMED) — an override can never release
 *     two blocks; granularity exactly (releaseId, stmtId), one blocked segment per consumption
 *     (covered_seg_idxs freezes the released segIdx);</li>
 * <li>expiry: now &lt; expireAt (DmAuthResDO.isEffective pattern); EXPIRED is derived at read
 *     time (no sweeper, the C4 twin);</li>
 * <li>consumption trails: RELEASE_ROUTE_OVERRIDE event (same tx — consumption integrity) +
 *     HIGH op_audit GOV_ROUTE_OVERRIDE_CONSUME in the GRANTOR's name (audit failure logged,
 *     never rolls back the release);</li>
 * <li>PLAN DRIFT and TEXT HASH DRIFT are NOT overridable — integrity classes, out of this
 *     service's scope by design (the text-hash-drift twin).</li>
 * </ul>
 */
public interface GovRouteOverrideService {

    /** blockCode: the DML_BATCH dispatch block (RELEASE_ROUTE_UNSUPPORTED, 裁决 3). */
    String BLOCK_ROUTE_UNSUPPORTED = "ROUTE_UNSUPPORTED";
    /** blockCode: the GHOST capability/launch failure (RELEASE_ROUTE_CAPABILITY, 裁决 2). */
    String BLOCK_ROUTE_CAPABILITY  = "ROUTE_CAPABILITY";

    /** Upper bound of an override validity window: 7 days (no permanent exemptions). */
    long MAX_VALIDITY_MS = 7L * 24 * 60 * 60 * 1000;

    /**
     * Grant an override. Validates tenant/stmt ownership/reason/expiry/active-uniqueness.
     * Permission (GOV_ROUTE_OVERRIDE) is enforced at the controller (@RequestAuth — the C4 twin).
     * @return the new row id.
     */
    long create(String puid, String uid, RouteOverrideGrantFO fo);

    /** All route-override rows of a release (tenant-checked), with derived display status. */
    List<RouteOverrideVO> list(String puid, long releaseId);

    /**
     * Dispatch-side consumption: find the newest consumable (PENDING, unexpired) override of the
     * stmt and CAS-consume it, freezing the released segIdx. On success writes the
     * RELEASE_ROUTE_OVERRIDE event (same tx) + the HIGH consumption audit (never-throw), and the
     * CALLER releases the blocked segment as a DIRECT single-statement AUTOEXEC replay.
     *
     * @param blockCode   {@link #BLOCK_ROUTE_UNSUPPORTED} ∨ {@link #BLOCK_ROUTE_CAPABILITY}
     * @param blockReason the block's detail text (audit/event disclosure, truncated)
     * @return the consumed row, or null when nothing consumable exists / the CAS race was lost —
     *         the block then STANDS (fail-closed: stmt FAILED + the block event).
     */
    DmGovRouteOverrideDO findAndConsume(DmProdReleaseDO release, DmProdReleaseStmtDO stmt,
                                        DmProdReleaseStmtSegmentDO seg, String blockCode, String blockReason);
}
