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
package com.clougence.clouddm.console.web.util;

import java.util.List;

import com.clougence.clouddm.console.web.component.detectrule.SecCheckerRules;
import com.clougence.clouddm.console.web.model.vo.security.SensitiveOriginalViewObj;
import com.clougence.clouddm.platform.dal.model.ResourceType;
import com.clougence.clouddm.platform.dal.model.monitor.AuditChannel;
import com.clougence.clouddm.platform.dal.model.monitor.AuditType;
import com.clougence.clouddm.platform.dal.model.monitor.SecurityLevel;
import com.clougence.rdp.service.RdpOpAuditService;
import com.clougence.rdp.service.model.AuditChainMO;
import com.clougence.utils.CollectionUtils;
import com.clougence.utils.StringUtils;

import lombok.extern.slf4j.Slf4j;

/**
 * C6/M3+M5 shared audit core (task 10-11-masking-fail-closed, design §3/§5) — the single point
 * behind BOTH console entry surfaces (the main analysis chain {@code QueryAnalysisServiceImpl}
 * and the data-editor path {@code DsDataEditorServiceImpl}):
 * <ul>
 * <li>{@link #auditOriginalView} — an explicit DM_DAUTH_SENSITIVE exemption served ORIGINAL
 *     values → {@code SENSITIVE_ORIGINAL_VIEW} (HIGH, resId = dsId, obj = identity + authorized
 *     resource PATHS only, never data values / SQL / credentials — the C1 sanitization spirit);</li>
 * <li>{@link #auditRejected} — a DANGLING masking binding blocked the query →
 *     {@code SENSITIVE_MASKING_REJECTED} (HIGH; the attempt is trailed even though nothing was
 *     served), written BEFORE the i18n rejection propagates.</li>
 * </ul>
 * Every method is NEVER-THROW: an audit failure is logged and must not fail (or veto) the query
 * or the rejection it trails. The root exemption deliberately does NOT land in op_audit (every-
 * query frequency → the dm_mon_biz_log MASKING_ROOT_EXEMPT tier at the caller — storm protection).
 */
@Slf4j
public final class MaskingAuditUtils {

    /** semantic audit-trail URIs (the DML_BATCH_AUTO_DISPATCH_URI precedent — findings, not endpoints). */
    public static final String MASKING_ORIGINAL_VIEW_URI = "/system/masking/original-view";
    public static final String MASKING_REJECTED_URI      = "/system/masking/rejected";

    private MaskingAuditUtils(){
    }

    /**
     * The SENSITIVE_ORIGINAL_VIEW row of an explicit exemption hit. {@code resPaths} is capped
     * at {@link SensitiveOriginalViewObj#MAX_RES_PATHS}; a blank owner uid skips the write
     * (logAndAddOperationAudit would reject the row anyway — the skip is loud in the app log).
     * <p>
     * C8 M2 additive parameters: {@code rules} is the analysis-time tri-state carrier — its
     * masking-spec snapshot (specId/specName/bindingState) rides the obj (裁决 2: the policy
     * reference stays JSON, readable via the audit detail endpoint); null/unresolvable values
     * leave the keys empty (never-throw discipline kept). {@code channel} is the landing-point
     * verdict per the design D3 matrix (data editor = CONSOLE; the analysis chain's system face
     * = SYSTEM_AUTO); null defers to the sink's URI inference.
     */
    public static void auditOriginalView(RdpOpAuditService rdpOpAuditService, String ownerUid, String uid, long dsId,
                                         String exemption, List<String> resPaths, String queryId,
                                         SecCheckerRules rules, AuditChannel channel) {
        try {
            if (StringUtils.isBlank(ownerUid)) {
                log.warn("[Masking] {} exemption audit skipped for ds {}: owner uid unavailable", exemption, dsId);
                return;
            }
            SensitiveOriginalViewObj obj = new SensitiveOriginalViewObj();
            obj.setExemption(exemption);
            obj.setDsId(dsId);
            obj.setUid(uid);
            obj.setQueryId(queryId);
            if (CollectionUtils.isNotEmpty(resPaths)) {
                obj.setResPathCount(resPaths.size());
                obj.setResPaths(resPaths.size() > SensitiveOriginalViewObj.MAX_RES_PATHS
                    ? resPaths.subList(0, SensitiveOriginalViewObj.MAX_RES_PATHS)
                    : resPaths);
            }
            stampMaskingSpecSnapshot(obj, rules);
            rdpOpAuditService.logAndAddOperationAudit(ownerUid, uid, MASKING_ORIGINAL_VIEW_URI, null, dsId, obj,
                SecurityLevel.HIGH, AuditType.SENSITIVE_ORIGINAL_VIEW, ResourceType.DATASOURCE,
                AuditChainMO.ofChannel(channel));
        } catch (Exception e) {
            log.warn("[Masking] {} exemption audit failed for ds {} (query unaffected)", exemption, dsId, e);
        }
    }

    /**
     * The SENSITIVE_MASKING_REJECTED row of a DANGLING-binding rejection (attempt trail).
     * C8 M2 additive parameters: the {@code rules} snapshot (bindingState will read DANGLING;
     * specId/name stay null by the tri-state construction) and the landing-point {@code channel}.
     */
    public static void auditRejected(RdpOpAuditService rdpOpAuditService, String ownerUid, String uid, long dsId,
                                     Long envId, String queryId, SecCheckerRules rules, AuditChannel channel) {
        try {
            SensitiveOriginalViewObj obj = new SensitiveOriginalViewObj();
            obj.setExemption(SensitiveOriginalViewObj.REJECT_DANGLING_BINDING);
            obj.setReason(SensitiveOriginalViewObj.REJECT_DANGLING_BINDING);
            obj.setDsId(dsId);
            obj.setEnvId(envId);
            obj.setUid(uid);
            obj.setQueryId(queryId);
            stampMaskingSpecSnapshot(obj, rules);
            rdpOpAuditService.logAndAddOperationAudit(ownerUid, uid, MASKING_REJECTED_URI, null, dsId, obj,
                SecurityLevel.HIGH, AuditType.SENSITIVE_MASKING_REJECTED, ResourceType.DATASOURCE,
                AuditChainMO.ofChannel(channel));
        } catch (Exception e) {
            log.warn("[Masking] DANGLING-rejection audit failed for ds {} (the rejection still applies)", dsId, e);
        }
    }

    /** the obj snapshot keys of the analysis-time resolution (null-safe — 取不到留空不抛). */
    private static void stampMaskingSpecSnapshot(SensitiveOriginalViewObj obj, SecCheckerRules rules) {
        if (rules == null) {
            return;
        }
        obj.setMaskingSpecId(rules.getMaskingSpecId());
        obj.setMaskingSpecName(rules.getMaskingSpecName());
        if (rules.getMaskingBindingState() != null) {
            obj.setMaskingBindingState(rules.getMaskingBindingState().name());
        }
    }
}
