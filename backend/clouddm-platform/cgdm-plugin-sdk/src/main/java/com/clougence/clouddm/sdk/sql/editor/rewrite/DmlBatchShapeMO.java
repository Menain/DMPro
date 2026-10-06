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

import java.util.ArrayList;
import java.util.List;

import com.clougence.clouddm.sdk.sql.parser.SplitQueryType;

import lombok.Getter;
import lombok.Setter;

/**
 * B7 lock-free DML — shape-gate analysis result for ONE DML statement, produced by
 * {@link RewriteSpi#analyzeDmlForBatch(String, String, RewriteContext)}.
 *
 * <p>The shape gate is a v1 WHITELIST (宁可误拒不可误放): only a single-table UPDATE/DELETE with a
 * WHERE clause is {@code supported}; everything else comes back as {@code supported=false} with a
 * stable {@link #rejectCode} so the caller can keep the statement on the direct execution chain
 * and render a per-code explanation. A rejection is NEVER a statement failure — it only declines
 * the batched (lock-free) execution route.</p>
 *
 * <p>Identifier normalization carried by this MO (consumers must NOT re-parse the SQL text):</p>
 * <ul>
 * <li>MySQL: quotes (backtick / ANSI double-quote) stripped, letter case PRESERVED (MySQL table
 * and schema names are case-sensitive on the target platform); column names are compared
 * case-insensitively by the analyzer itself.</li>
 * <li>PostgreSQL: quotes stripped and UNQUOTED identifiers folded to lower case (PG catalog
 * semantics — a quoted {@code "MyTable"} keeps its case, a bare {@code MyTable} folds to
 * {@code mytable}), so {@link #tableName}/{@link #schemaName} match the catalog names.</li>
 * </ul>
 *
 * <p>Serialization: additive JSON keys under the precheck_result additive contract — the whole MO
 * is embedded verbatim into the routing decision record.</p>
 *
 * @author B7 lockfree-dml (09-17-lockfree-dml), design §3.1/§3.2
 * @since 4.2.2
 */
@Getter
@Setter
public class DmlBatchShapeMO {

    // ---------------------------------------------------------------------
    // Stable rejectCode values (the console maps each code onto an i18n hint;
    // codes are additive — new shapes get new codes, never renumbered).
    // ---------------------------------------------------------------------

    /** the text could not be parsed as dialect SQL (blank input included) — never throws instead. */
    public static final String REJECT_PARSE_ERROR         = "DML_BATCH_PARSE_ERROR";
    /** more than one statement in the input (split result != 1). */
    public static final String REJECT_MULTI_STATEMENT     = "DML_BATCH_MULTI_STATEMENT";
    /** not an UPDATE/DELETE statement (SELECT/INSERT/DDL/... — v1 batches UPDATE/DELETE only). */
    public static final String REJECT_UNSUPPORTED_STATEMENT = "DML_BATCH_UNSUPPORTED_STATEMENT";
    /**
     * parseable but exotic single-statement shape kept out of the v1 whitelist, e.g. PG
     * {@code ONLY}/{@code t.*} relation forms, PG {@code RETURNING}, PG row assignment
     * {@code SET (a,b)=...}, PG 3-part relation names, MySQL delphi dot-names, and MySQL
     * user/system variable references ({@code @v}/{@code @@v} — per-session state that does
     * NOT survive the fresh per-batch session: an unset variable reads as NULL, silently
     * nulling a SET column or emptying the WHERE match set).
     */
    public static final String REJECT_UNSUPPORTED_SHAPE   = "DML_BATCH_UNSUPPORTED_SHAPE";
    /** multi-table DML: MySQL multipleUpdate/multipleDelete, PG {@code UPDATE ... FROM} / {@code DELETE ... USING}. */
    public static final String REJECT_MULTI_TABLE         = "DML_BATCH_MULTI_TABLE";
    /** statement carries a WITH (CTE) clause. */
    public static final String REJECT_CTE                 = "DML_BATCH_CTE";
    /** statement carries a PARTITION clause (MySQL only; PG DML has no PARTITION syntax). */
    public static final String REJECT_PARTITION           = "DML_BATCH_PARTITION";
    /**
     * no WHERE clause (full-table DML violates org-spec §9.8.3 "WHERE must land on keys" — the
     * platform does not automate batching for it) or, for PG, {@code WHERE CURRENT OF cursor}
     * (no injectable boolean predicate).
     */
    public static final String REJECT_NO_WHERE            = "DML_BATCH_NO_WHERE";
    /** statement carries its own ORDER BY (conflicts with the batch key ORDER BY; MySQL only). */
    public static final String REJECT_ORDER_BY            = "DML_BATCH_ORDER_BY";
    /** statement carries its own LIMIT (conflicts with the batch LIMIT; MySQL only). */
    public static final String REJECT_LIMIT               = "DML_BATCH_LIMIT";
    /** a SET assignment expression contains a subquery (rows it reads may be touched by other batches). */
    public static final String REJECT_SET_SUBQUERY        = "DML_BATCH_SET_SUBQUERY";
    /** statement carries an indexHint (MySQL only). */
    public static final String REJECT_INDEX_HINT          = "DML_BATCH_INDEX_HINT";
    /** any SQL comment anywhere in the input (B6 zero-comment discipline — shrinks the verification surface). */
    public static final String REJECT_COMMENT             = "DML_BATCH_COMMENT";
    /**
     * the statement's db/schema prefix differs from the expected schema
     * ({@link RewriteContext#getSchemaName()}), or the statement HAS a prefix while no expected
     * schema was supplied (fail-safe: an unvalidatable prefix is rejected).
     */
    public static final String REJECT_SCHEMA_MISMATCH     = "DML_BATCH_SCHEMA_MISMATCH";

    /** true only when the statement passes the whole v1 whitelist and may be batched. */
    private boolean         supported;

    /** one of the REJECT_* constants when {@link #supported} is false; null when supported. */
    private String          rejectCode;

    /** {@link SplitQueryType#UPDATE} or {@link SplitQueryType#DELETE} when determinable; null otherwise. */
    private SplitQueryType  statementType;

    /** db/schema prefix written on the statement (normalized, see class javadoc); null when unprefixed. */
    private String          schemaName;

    /** target table name (normalized, see class javadoc); null unless determinable. */
    private String          tableName;

    /** table alias if the statement declares one (normalized); null otherwise. */
    private String          alias;

    /** whether the statement has an injectable WHERE predicate (always true when supported). */
    private boolean         hasWhere;

    /**
     * true when some SET assignment references its own target column ({@code x = x + 1},
     * {@code x = f(x)}) — a NON-IDEMPOTENT statement: replaying a batch silently double-applies.
     * Always false for DELETE (deleting an already-deleted row is a no-op). Informational: feeds
     * the precheck yellow flag and the window-crash manual-resolution policy; never a rejection.
     */
    private boolean         selfReferencingSet;

    /**
     * non-deterministic function names found in SET expressions (normalized: MySQL upper case,
     * PG lower case; deduplicated, statement order). Empty when none. Informational like
     * {@link #selfReferencingSet} (replay renders different values).
     */
    private List<String>    nondeterministicFunctions = new ArrayList<>();

    /** normalized SET target column names in statement order (UPDATE only; empty for DELETE). */
    private List<String>    setColumns                = new ArrayList<>();

    /** convenience factory: a not-supported result carrying one of the REJECT_* codes. */
    public static DmlBatchShapeMO reject(String rejectCode) {
        DmlBatchShapeMO mo = new DmlBatchShapeMO();
        mo.supported = false;
        mo.rejectCode = rejectCode;
        return mo;
    }
}
