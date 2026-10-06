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
package com.clougence.clouddm.sdk.sql.editor.rewrite;

import lombok.Getter;
import lombok.Setter;

/**
 * B7 lock-free DML — one batch's range parameters for
 * {@link RewriteSpi#rewriteToBatchBoundarySelect(String, String, DmlBatchRangeSpec, RewriteContext)}
 * and {@link RewriteSpi#rewriteToBatchRange(String, String, DmlBatchRangeSpec, RewriteContext)}.
 *
 * <p><b>Division of responsibility (design §3.1/§3.4, R18):</b> the CONSOLE renders the cursor /
 * boundary literals (typed rendering + dialect escaping is centralized in one console-side tool —
 * integers digit-checked, datetimes ISO-checked and single-quoted, strings escaped per dialect:
 * MySQL backslash doubling FIRST then quote doubling, PG quote doubling). The SPI only performs
 * AST node-level insertion and injects {@link #loLiteral}/{@link #hiLiteral} VERBATIM — it makes
 * no escaping decision. {@link #keyColumn} is a bare identifier already validated by the console
 * against the {@code [\w$]+} whitelist (probe result, never user text); the SPI re-checks the
 * whitelist defensively and applies the dialect quoting (MySQL backticks with doubling, PG double
 * quotes with doubling).</p>
 *
 * <p><b>String key collation safety (裁决 #18, design §4.1):</b> cursor-style batching over a
 * string PK/UK is safe under any collation — the uniqueness constraint is enforced IN THE SAME
 * collation as the {@code >}/{@code <=} comparisons, so collation-equal variants (ci/accent/PAD
 * SPACE) cannot coexist in a unique index; the cursor order is therefore collision-free and no
 * row is skipped or repeated at batch boundaries.</p>
 *
 * @author B7 lockfree-dml (09-17-lockfree-dml), design §3.1/§3.3
 * @since 4.2.2
 */
@Getter
@Setter
public class DmlBatchRangeSpec {

    /** integer family key (TINYINT..BIGINT) — literals rendered as plain digits. */
    public static final String KEY_TYPE_INT      = "INT";
    /** DECIMAL(p,0) key — literals rendered as plain digits (scale 0 by whitelist). */
    public static final String KEY_TYPE_DECIMAL0 = "DECIMAL0";
    /** DATE/DATETIME/TIMESTAMP key — literals rendered ISO-form and single-quoted. */
    public static final String KEY_TYPE_DATETIME = "DATETIME";
    /** CHAR/VARCHAR key — literals rendered with dialect string escaping (see class javadoc). */
    public static final String KEY_TYPE_STRING   = "STRING";

    /**
     * batch key column — a BARE identifier matching {@code [\w$]+} (console-validated probe
     * result); the SPI quotes it per dialect. Never a rendered/quoted form.
     */
    private String keyColumn;

    /** one of the KEY_TYPE_* constants (informational for the SPI — literals arrive pre-rendered). */
    private String keyType;

    /**
     * pre-rendered exclusive lower bound literal, or null/blank for the FIRST batch (no lower
     * bound — the boundary SELECT scans from the beginning, cursor-style). Injected verbatim as
     * {@code <key> > <loLiteral>}.
     */
    private String loLiteral;

    /**
     * pre-rendered inclusive upper bound literal (the batch's discovered boundary). Required by
     * {@link RewriteSpi#rewriteToBatchRange} (injected verbatim as {@code <key> <= <hiLiteral>});
     * ignored by the boundary SELECT (which DISCOVERS this value).
     */
    private String hiLiteral;

    /** batch row cap (> 0) — rendered into the boundary SELECT's LIMIT. */
    private long   limit;

    /** convenience factory. */
    public static DmlBatchRangeSpec of(String keyColumn, String keyType, String loLiteral, String hiLiteral, long limit) {
        DmlBatchRangeSpec spec = new DmlBatchRangeSpec();
        spec.keyColumn = keyColumn;
        spec.keyType = keyType;
        spec.loLiteral = loLiteral;
        spec.hiLiteral = hiLiteral;
        spec.limit = limit;
        return spec;
    }
}
