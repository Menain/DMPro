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

import com.clougence.clouddm.console.web.model.fo.prodrelease.SchemaGateOverrideGrantFO;
import com.clougence.clouddm.console.web.model.vo.prodrelease.SchemaGateOverrideVO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmGovSchemaGateOverrideDO;

/**
 * Governed override of production release schema-gate denials (ADR-011).
 * <p>
 * Placed in component/governance next to the precheck/diff engines because BOTH gate owners
 * (ProdReleaseServiceImpl and AutoExecServiceImpl) consume it — discretion noted in the task
 * delivery (implement.md Step 3 allowed either placement).
 * <p>
 * Contract:
 * <ul>
 * <li>grant: tenant check (puid == release.primaryUid), gateType ∈ {PRECHECK, DIFF}, reason
 *     mandatory, expireAt strictly future and ≤ {@link #MAX_VALIDITY_MS} ahead;</li>
 * <li>consumption: SINGLE-USE CAS (consumed 0→1) — an override can never release two denials;
 *     stmt-level grants are matched before release-level ones;</li>
 * <li>expiry: now &lt; expireAt (DmAuthResDO.isEffective pattern);</li>
 * <li>revoke: unconsumed + unrevoked rows only, CAS-guarded;</li>
 * <li>hash drift is NOT overridable — this service only covers the two schema gate classes.</li>
 * </ul>
 */
public interface SchemaGateOverrideService {

    /** gateType: the pre-A/pre-B structural precondition precheck. */
    String GATE_PRECHECK = "PRECHECK";
    /** gateType: the pre-before snapshot drift diff. */
    String GATE_DIFF     = "DIFF";

    /** Upper bound of an override validity window: 7 days (no permanent exemptions). */
    long MAX_VALIDITY_MS = 7L * 24 * 60 * 60 * 1000;

    /**
     * Grant an override. Validates tenant/gateType/reason/expiry/stmt ownership.
     * @return the new row id.
     */
    long grant(String puid, String uid, SchemaGateOverrideGrantFO fo);

    /** All override rows of a release (tenant-checked), with derived display status. */
    List<SchemaGateOverrideVO> list(String puid, long releaseId);

    /**
     * Revoke an unconsumed grant (tenant-checked, CAS-guarded).
     *
     * @return the revoked row's releaseId — C8 M2: the audit chain column source (the revoke FO
     *         only carries the override id; the row is ALREADY loaded by the tenant check, so
     *         returning the key keeps the red-line-4 "caller-supplied, zero extra SELECT" discipline).
     */
    long revoke(String puid, String uid, long overrideId);

    /**
     * Gate-side consumption: find the newest consumable (unexpired, unconsumed, unrevoked)
     * override for the gate denial — stmt-level first, then release-level — and CAS-consume it.
     * @param consumedDetail gate context summary frozen into the row (≤512 chars)
     * @return the consumed row (trail for the RELEASE_SCHEMA_GATE_OVERRIDE event), or null when
     *         nothing consumable exists / the CAS race was lost — the gate then DENIES.
     */
    DmGovSchemaGateOverrideDO findAndConsume(long releaseId, Long stmtId, String gateType, String consumedDetail);
}
