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
package com.clougence.clouddm.console.web.model.vo.prodrelease;

import lombok.Getter;
import lombok.Setter;

/**
 * Audit trail object of a route-override CONSUMPTION (ADR-013 M3, the GOV_ROUTE_OVERRIDE_CONSUME
 * HIGH op_audit row) — the C4 override audit's object shape (a plain serializable POJO frozen
 * into log_info; GhostOpAuditObj is deliberately NOT reused — the two surfaces stay isolated).
 * Disclosure whitelist: NO SQL text (C2 eventData discipline); reason is the grantor's own
 * risk-acceptance text (≤ 1024, the C4 event-data precedent).
 */
@Getter
@Setter
public class RouteOverrideAuditObj {

    private Long   overrideId;
    private Long   releaseId;
    private Long   stmtId;
    /** The segIdx released by this consumption (single-use — one segment per consumption). */
    private Integer segIdx;
    /** The blocked segment's frozen plan type (GHOST ∨ DML_BATCH). */
    private String segmentType;
    /** ROUTE_UNSUPPORTED ∨ ROUTE_CAPABILITY — which block class the override released. */
    private String blockCode;
    /** The block's detail text (failCode/reason head, ≤512 — the released risk's evidence). */
    private String blockReason;
    private String reason;
    /** Grantor uid — the risk-acceptance subject (the audit row's uid). */
    private String createdBy;
    private String expireAt;
    private String coveredSegIdxs;
}
