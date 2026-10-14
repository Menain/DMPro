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

/**
 * C8 M2 (task 10-13-audit-chain-enhancement, design D3): the "through which channel did the
 * audited action arrive" dimension of dm_mon_op_audit.channel (varchar name, NULL on
 * pre-C8 stock rows).
 * <p>
 * Three values by design (the DingTalk-bridge inbound-command plane deliberately does NOT
 * land in op_audit — its trail lives in dm_db_change_event, status quo kept):
 * <ul>
 * <li>{@link #CONSOLE} — a real console UI request (a genuine HTTP URI);</li>
 * <li>{@link #OPENAPI} — the open-API plane: the {@code /open/...} semantic audit markers
 *     (e.g. GovOpenApiServiceImpl.OPEN_SUBMIT_URI) and real {@code /api/open...} HTTP URIs
 *     (DmControllerUrlPrefix.OPEN_API_PREFIX);</li>
 * <li>{@link #SYSTEM_AUTO} — platform-initiated system plane: the {@code /system/...}
 *     semantic URI markers (auto-dispatch / sweep re-dispatch / override consumption /
 *     masking system face). Sweep- vs scheduler- vs manual-retry-origin stays distinguished
 *     by the audit obj's {@code via} field — this enum is deliberately coarse (prd §6).</li>
 * </ul>
 * Resolution order at the logOperation sink: an EXPLICIT channel parameter wins; otherwise
 * the URI-prefix inference below; an unparsable/absent URI yields null.
 */
public enum AuditChannel {

    CONSOLE,
    OPENAPI,
    SYSTEM_AUTO;

    /** semantic-marker prefix of the open-API audit rows (GovOpenApiServiceImpl.OPEN_SUBMIT_URI shape). */
    private static final String OPEN_MARKER_PREFIX   = "/open/";
    /** real HTTP prefix of the open-API controllers (DmControllerUrlPrefix.OPEN_API_PREFIX). */
    private static final String OPEN_HTTP_PREFIX     = "/api/open";
    /** semantic-marker prefix of the system-plane audit rows (dispatch/sweep/override/masking URIs). */
    private static final String SYSTEM_MARKER_PREFIX = "/system/";

    /**
     * The SINGLE-POINT URI inference rule (design D3 — no second implementation):
     * {@code /open/} ∨ {@code /api/open} → OPENAPI; {@code /system/} → SYSTEM_AUTO;
     * any other non-blank URI → CONSOLE; null/blank → null (unknown — never a fake default).
     */
    public static AuditChannel inferFromUri(String operationUri) {
        if (operationUri == null || operationUri.isBlank()) {
            return null;
        }
        if (operationUri.startsWith(OPEN_MARKER_PREFIX) || operationUri.startsWith(OPEN_HTTP_PREFIX)) {
            return OPENAPI;
        }
        if (operationUri.startsWith(SYSTEM_MARKER_PREFIX)) {
            return SYSTEM_AUTO;
        }
        return CONSOLE;
    }
}
