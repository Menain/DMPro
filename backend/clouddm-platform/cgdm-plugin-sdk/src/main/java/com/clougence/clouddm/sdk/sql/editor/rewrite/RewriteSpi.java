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

import com.clougence.clouddm.sdk.Spi;

/**
 * @author mode 2020-01-20 21:04
 * @since 1.1.3
 */
public interface RewriteSpi extends Spi {

    String rewriteLimit(String queryId, String queryStr, RewriteContext context);

    String rewriteToExplain(String queryId, String queryStr, RewriteContext context);

    /**
     * B7 lock-free DML — shape-gate analysis of ONE statement for keyset batching eligibility
     * (design 09-17-lockfree-dml §3.1/§3.2). The v1 whitelist accepts exactly: a single
     * single-table UPDATE/DELETE, WITH a WHERE predicate, no CTE / PARTITION / native ORDER BY /
     * native LIMIT / SET subquery / indexHint / SQL comments / multiple statements, and a
     * db-schema prefix (when present) equal to {@link RewriteContext#getSchemaName()}.
     *
     * <p>Contract for callers (console precheck marking seam):</p>
     * <ul>
     * <li>NEVER throws — any parse/runtime problem degrades to a not-supported shape
     * ({@link DmlBatchShapeMO#REJECT_PARSE_ERROR}); the batch route evaluation must never block
     * ticket submission.</li>
     * <li>{@code null} (this default) = the dialect has NO batch-rewrite support at all → treat
     * exactly like {@code supported=false}: the statement stays on the direct execution chain.</li>
     * <li>{@code supported=false} + a stable {@code rejectCode} → not routed; the code drives the
     * grey-hint text. A rejection is never a statement failure.</li>
     * <li>{@code supported=true} → the MO additionally carries the target table/schema/alias
     * (normalized), the SET columns and the non-idempotence marks (self-referencing SET like
     * {@code x=x+1}, non-deterministic functions like {@code NOW()}) for the precheck yellow flag
     * and the approval record.</li>
     * </ul>
     *
     * <p>Only the MySQL and PostgreSQL SPIs override this method (v1 scope); every other dialect
     * keeps this default.</p>
     *
     * @param queryId statement identifier (pass-through for logging symmetry with the other methods)
     * @param sql     ONE statement's full text (frozen statement text in the ticket chain)
     * @param context parser parameters; {@link RewriteContext#getSchemaName()} SHOULD carry the
     *                group's database name so a prefixed statement can be validated (absent +
     *                prefixed → rejected fail-safe)
     * @return the shape decision, or null when the dialect does not support batch rewriting
     */
    default DmlBatchShapeMO analyzeDmlForBatch(String queryId, String sql, RewriteContext context) {
        return null;
    }

    /**
     * B7 lock-free DML — step ① boundary discovery (design §3.3): transform the ORIGINAL
     * UPDATE/DELETE into the keyset boundary SELECT
     *
     * <pre>
     * SELECT MAX(&lt;key&gt;) FROM (SELECT &lt;keyRef&gt; FROM &lt;tableRef&gt; WHERE (&lt;original predicate&gt;)
     *   AND &lt;keyRef&gt; &gt; &lt;loLiteral&gt; ORDER BY &lt;keyRef&gt; LIMIT &lt;limit&gt;) bnd
     * </pre>
     *
     * where {@code keyRef} is the alias-qualified key column when the statement declares an alias.
     * The {@code AND key > loLiteral} predicate is omitted when {@code spec.loLiteral} is
     * null/blank (first batch). The transform is TokenStreamRewriter node surgery — the original WHERE text
     * and the original table reference text are preserved verbatim; the trailing NULL of MAX is
     * the batch loop's ONLY termination authority (never affectRows — a matched-but-unchanged
     * MySQL UPDATE returns 0).
     *
     * <p>Contract: throws {@link IllegalArgumentException} for an invalid spec (null spec, blank /
     * non-{@code [\w$]+} keyColumn, {@code limit <= 0}); returns {@code null} when the statement
     * is not transformable (unparsable, multi-statement, not a single-table UPDATE/DELETE, CTE /
     * ORDER BY / LIMIT present, PG FROM/USING/RETURNING/CURRENT OF). Callers treat a null as a
     * batch failure (loud), never as "execute directly".</p>
     *
     * @param spec carries the key column, the exclusive lower bound literal (null/blank = first
     *             batch) and the batch LIMIT; {@code hiLiteral} is ignored here. Escaping division
     *             of responsibility (R18, see {@link DmlBatchRangeSpec}): lo/hi literals arrive
     *             PRE-RENDERED by the console (single escaping point) and are injected VERBATIM —
     *             the SPI makes no escaping decision; only {@code keyColumn} is validated
     *             ({@code [\w$]+}) and dialect-quoted here
     * @return the full boundary SELECT text, or null when not transformable
     */
    default String rewriteToBatchBoundarySelect(String queryId, String sql, DmlBatchRangeSpec spec, RewriteContext context) {
        return null;
    }

    /**
     * B7 lock-free DML — step ② range execution (design §3.3): inject the batch range predicate
     * into the ORIGINAL statement's WHERE, touching nothing else:
     *
     * <pre>
     * UPDATE t SET ... WHERE ((&lt;original predicate&gt;) AND &lt;keyRef&gt; &gt; &lt;loLiteral&gt; AND &lt;keyRef&gt; &lt;= &lt;hiLiteral&gt;)
     * DELETE FROM t WHERE ((&lt;original predicate&gt;) AND &lt;keyRef&gt; &gt; &lt;loLiteral&gt; AND &lt;keyRef&gt; &lt;= &lt;hiLiteral&gt;)
     * </pre>
     *
     * The {@code AND keyRef > loLiteral} part is omitted when {@code spec.loLiteral} is null/blank
     * (first batch). SET/FROM and every other clause remain byte-identical (TokenStreamRewriter
     * node-level insertion only — the original text is never string-spliced, R18). Each batch
     * runs in its own autocommit transaction; the injected range shrinks the lock footprint from
     * the whole statement to one batch — the core lock-free win.
     *
     * <p>Contract: throws {@link IllegalArgumentException} for an invalid spec (null spec, blank /
     * non-{@code [\w$]+} keyColumn, {@code limit <= 0}, blank {@code hiLiteral}); returns
     * {@code null} when the statement is not transformable (same set as
     * {@link #rewriteToBatchBoundarySelect}). Defensive shape: a (gate-excluded) statement
     * WITHOUT WHERE receives a bare {@code WHERE keyRef > lo AND keyRef <= hi} injection instead
     * of null. Callers treat a null as a batch failure (loud), never as "execute directly".</p>
     *
     * @param spec carries the key column, both bound literals and the batch LIMIT. Escaping
     *             division of responsibility (R18, see {@link DmlBatchRangeSpec}): literals arrive
     *             PRE-RENDERED by the console and are injected VERBATIM — the SPI makes no
     *             escaping decision
     * @return the full rewritten statement text, or null when not transformable
     */
    default String rewriteToBatchRange(String queryId, String sql, DmlBatchRangeSpec spec, RewriteContext context) {
        return null;
    }
}
