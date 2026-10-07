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
package com.clougence.clouddm.console.web.service.dmlbatch;

import lombok.Getter;
import lombok.Setter;

/**
 * B7 lock-free DML — one channel-C round trip's result inside the marking seam (design §4.1),
 * the structural mirror of the B6 {@code GhostRouteProbe} with the DML-batch key contract:
 * WHICH key (column + type), not just whether one exists.
 * <p>
 * NEVER-THROW degradation contract: any probe failure (timeout / unreachable / unparsable
 * result) yields {@code probed=false} and the caller keeps the statement on the direct chain
 * with a grey hint — a degraded probe must neither route, nor REJECT (rejection is reserved for
 * a SUCCESSFUL probe that CONFIRMS the absence of a usable key), nor block submission. A
 * MISSING TABLE degrades the same way ({@code tableFound=false} leaves {@code probed=false} →
 * the marking seam's PROBE_DEGRADED grey hint, design §4.1 "探测超时/失败/表不存在 → 不路由+灰提示");
 * the missing table itself is the execution chain's business — it fails loudly there.
 * <p>
 * Key selection contract (design §4.1 + N1a handover obligation #1):
 * single-column PK → first single-column NOT NULL UK (deterministic index order) → everything
 * else is not batchable. A NULLABLE single-column UK counts as NO KEY (multiple NULLs coexist
 * under uniqueness; {@code k > lo} is never true for NULL, so NULL-keyed rows would silently
 * never enter any batch while the boundary SELECT terminates normally — silent partial
 * execution). PK columns are NOT NULL by construction (no check needed).
 */
@Getter
@Setter
public class DmlBatchKeyProbe {

    /** key-state: no PK and no usable UK at all (including the nullable-UK-only shape). */
    public static final String STATE_NO_KEY      = "NO_KEY";
    /** key-state: a unique key exists but is outside the v1 whitelist (composite / float / exotic / text...). */
    public static final String STATE_UNSUPPORTED = "UNSUPPORTED";
    /** key-state: a batchable single-column whitelist key was found. */
    public static final String STATE_OK          = "OK";

    /** false = degraded probe (timeout/failure/invalid identifier) — no route, no rejection, grey hint. */
    private boolean probed;
    /** the target table exists (only meaningful when probed; false → silent status quo). */
    private boolean tableFound;

    /** B4-estimate caliber row count (MySQL information_schema.TABLES / PG pg_class.reltuples). */
    private Long    tableRows;

    /** STATE_NO_KEY | STATE_UNSUPPORTED | STATE_OK (null when !probed ∨ !tableFound). */
    private String  keyState;
    /** STATE_OK: the frozen batch key column (bare identifier, {@code [\w$]+}-validated). */
    private String  keyColumn;
    /** STATE_OK: one of the DmlBatchRangeSpec KEY_TYPE_* wire values. */
    private String  keyType;
    /**
     * STATE_NO_KEY / STATE_UNSUPPORTED: the human-copy reason detail ({@code composite primary
     * key (id,tenant_id)} / {@code unique key type double} / {@code only nullable unique key
     * (email)}) — rides into the reject/yellow-flag messages, never into SQL.
     */
    private String  keyDetail;

    /** the admin row threshold in effect (display copy). */
    private long    rowThreshold;
}
