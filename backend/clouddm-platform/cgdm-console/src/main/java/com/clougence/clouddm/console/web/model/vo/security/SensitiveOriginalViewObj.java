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
package com.clougence.clouddm.console.web.model.vo.security;

import java.util.List;

import lombok.Getter;
import lombok.Setter;

/**
 * C6/M5 audit payload of a {@code SENSITIVE_ORIGINAL_VIEW} / {@code SENSITIVE_MASKING_REJECTED}
 * row (the {@code GhostOpAuditObj} pattern; resource = DATASOURCE, resId = dsId — the four-point
 * audit contract). Records WHO saw (or attempted to see) ORIGINAL values through WHICH exemption.
 * <p>
 * Deliberately carries NO data values and NO SQL text (design §5): only identities, the
 * exemption kind and the authorized resource PATHS (capped at {@link #MAX_RES_PATHS} entries —
 * paths are configuration coordinates, not row data). No credential fields exist on this class,
 * so the C1 AuditSanitizeUtils pass-through convention is satisfied by construction.
 */
@Getter
@Setter
public class SensitiveOriginalViewObj {

    /** exemption kind markers (string constants below). */
    private String       exemption;
    private Long         dsId;
    private Long         envId;
    /** the exempted viewer (also on the audit row's uid column — kept for obj self-containment). */
    private String       uid;
    /** query batch identity when available (correlates with the SQL audit trail). */
    private String       queryId;
    /** authorized resource paths (truncated to {@link #MAX_RES_PATHS}; null when not path-scoped). */
    private List<String> resPaths;
    /** total authorized path count before truncation. */
    private Integer      resPathCount;
    /** rejection rows only: why the query was blocked (e.g. DANGLING_BINDING). */
    private String       reason;

    /** DM_DAUTH_SENSITIVE label covers every READ resource path of the query (no provenance). */
    public static final String EXEMPT_DAUTH_ALL_PATHS   = "DAUTH_SENSITIVE_ALL_PATHS";
    /** DM_DAUTH_SENSITIVE label covers every provenance source column of the query. */
    public static final String EXEMPT_DAUTH_ALL_COLUMNS = "DAUTH_SENSITIVE_ALL_COLUMNS";
    /** data-editor table-level DM_SENSITIVE authorization (whole-request exemption). */
    public static final String EXEMPT_DATA_EDITOR       = "DATA_EDITOR";
    /** C6/M3: query REJECTED because the masking binding is dangling (an attempt trail). */
    public static final String REJECT_DANGLING_BINDING  = "DANGLING_BINDING";

    /** cap of resource paths stored in the audit row (JSON size discipline). */
    public static final int    MAX_RES_PATHS            = 20;
}
