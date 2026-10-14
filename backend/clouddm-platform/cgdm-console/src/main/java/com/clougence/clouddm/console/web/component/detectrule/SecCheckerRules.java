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
package com.clougence.clouddm.console.web.component.detectrule;

import java.util.Collections;
import java.util.List;

import com.clougence.clouddm.base.metadata.ds.DataSourceType;
import com.clougence.clouddm.sdk.service.secrules.CheckerRule;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SecCheckerRules {

    /**
     * C6/M3 additive (design §3, B2 ruling): tri-state of the MASKING (SENSITIVE chain) env
     * binding, resolved by {@code SecRulesServiceImpl.resolveCheckerRules}. Existing consumers
     * keep reading {@link #valid} only — this field never changes their behaviour.
     * <ul>
     * <li>{@code NOT_BOUND} — both env keys blank: never bound = an explicit opt-out (the
     * opt-in activation contract). Query proceeds unmasked + an observation event (M4).</li>
     * <li>{@code DANGLING} — a key carries a value but no usable spec resolves (non-numeric,
     * spec deleted or disabled) and no valid legacy fallback exists: the masking policy is
     * UNKNOWN → console-analysis-time query rejection (fail-closed, never silent plaintext).</li>
     * <li>{@code VALID} — a binding resolves to an enabled spec (a valid binding with zero
     * enabled referers stays VALID: "explicit user intent wins", the pinned no-fallback ruling;
     * it is NOT dangling — rejection is reserved for spec-level dangling only, aligned with the
     * UI carrier {@code DmEnvParamSecDesVO{openSec=true, invalid=true}}).</li>
     * </ul>
     */
    public enum MaskingBindingState {
        NOT_BOUND, DANGLING, VALID
    }

    private final boolean           valid;
    private final long              envId;
    private final long              dsId;
    private final String            dsName;
    private final DataSourceType    dsType;

    private final String            dsUseSpecName;
    private final String            dsUseSensitiveSpecName;

    private final List<CheckerRule> queryRuleList;
    private final List<CheckerRule> senRuleList;

    /** C6/M3 additive: masking-binding tri-state; defaults to NOT_BOUND on the invalid shell. */
    private MaskingBindingState     maskingBindingState = MaskingBindingState.NOT_BOUND;

    /** C6/M5 additive: the tenant owner uid of the ds (the audit rows need puid at the call site). */
    private String                  ownerUid;

    /**
     * C8 M2 additive (design D3 — the SENSITIVE_* audit rows' masking-spec snapshot source):
     * the id/name of the spec the SENSITIVE chain actually resolved to (new key first, legacy
     * fallback — the dsUseSensitiveSpecName caliber), null when nothing resolves. Set by
     * {@code SecRulesServiceImpl.resolveCheckerRules}; existing consumers never read it.
     */
    private Long                    maskingSpecId;
    private String                  maskingSpecName;

    public SecCheckerRules(){
        this.valid = false;
        this.envId = 0L;
        this.dsId = 0L;
        this.dsName = null;
        this.dsType = null;
        this.dsUseSpecName = "";
        this.dsUseSensitiveSpecName = "";
        this.queryRuleList = Collections.emptyList();
        this.senRuleList = Collections.emptyList();

    }

    public SecCheckerRules(long envId, long dsId, String dsName, DataSourceType dsType,
                           String dsUseSpecName, String dsUseSensitiveSpecName,
                           List<CheckerRule> queryRuleList, List<CheckerRule> senRuleList){
        this.valid = true;
        this.envId = envId;
        this.dsId = dsId;
        this.dsName = dsName;
        this.dsType = dsType;
        this.dsUseSpecName = dsUseSpecName;
        this.dsUseSensitiveSpecName = dsUseSensitiveSpecName;
        this.queryRuleList = (queryRuleList == null) ? Collections.emptyList() : queryRuleList;
        this.senRuleList = (senRuleList == null) ? Collections.emptyList() : senRuleList;
    }

    /**
     * C6/M3 additive: the invalid shell WITH identity — resolved zero rules on both chains, but
     * the masking tri-state and the ds identity ride along (the M4 observation event and the
     * M5 audit rows need the real dsId/envId; the no-arg shell zeroes them). {@code valid}
     * stays false, so every existing consumer (fetchSensitiveConfigByDs null-collapse,
     * hasSensitiveRules) behaves exactly as before.
     */
    public SecCheckerRules(long envId, long dsId, MaskingBindingState maskingBindingState, String ownerUid){
        this.valid = false;
        this.envId = envId;
        this.dsId = dsId;
        this.dsName = null;
        this.dsType = null;
        this.dsUseSpecName = "";
        this.dsUseSensitiveSpecName = "";
        this.queryRuleList = Collections.emptyList();
        this.senRuleList = Collections.emptyList();
        this.maskingBindingState = maskingBindingState;
        this.ownerUid = ownerUid;
    }

    public boolean isEmpty() { return this.queryRuleList.isEmpty() && this.senRuleList.isEmpty(); }
}
