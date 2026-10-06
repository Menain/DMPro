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
package com.clougence.sql.postgres.editor.rewrite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.clougence.clouddm.sdk.sql.editor.rewrite.DmlBatchRangeSpec;
import com.clougence.clouddm.sdk.sql.editor.rewrite.DmlBatchShapeMO;
import com.clougence.clouddm.sdk.sql.editor.rewrite.RewriteContext;
import com.clougence.clouddm.sdk.sql.parser.SplitQueryType;
import com.clougence.sql.postgres.parser.PostgresVersion;

/**
 * B7 lock-free DML — PostgreSQL 15 shape gate + keyset rewrite pins (design 09-17-lockfree-dml §3).
 *
 * <p>Mirror of the MySQL suite with the PG-specific facts: no PARTITION/ORDER BY/LIMIT/indexHint
 * clauses exist on PG DML (such statements are parse errors here), multi-table forms are
 * UPDATE...FROM / DELETE...USING, RETURNING and WHERE CURRENT OF are gate-excluded, identifier
 * case folding follows catalog semantics (bare folds to lower case, "quoted" keeps case).</p>
 *
 * <p>Discipline (B4/B6 heritage): rewrite assertions pin the COMPLETE output SQL text.</p>
 */
public class PgRewriteSpiBatchTest {

    private final PgRewriteSpi spi = new PgRewriteSpi(PostgresVersion.POSTGRES_15);

    private static RewriteContext ctx(String schemaName) {
        RewriteContext context = new RewriteContext();
        context.setSchemaName(schemaName);
        return context;
    }

    private static DmlBatchRangeSpec spec(String lo, String hi, long limit) {
        return DmlBatchRangeSpec.of("id", DmlBatchRangeSpec.KEY_TYPE_INT, lo, hi, limit);
    }

    // ================================================================== analyze: accept

    @Test
    public void plainUpdateIsSupported() {
        DmlBatchShapeMO mo = spi.analyzeDmlForBatch("q1", "UPDATE big_tbl SET status = 1 WHERE status = 0", ctx(null));
        assertTrue(mo.isSupported());
        assertNull(mo.getRejectCode());
        assertEquals(SplitQueryType.UPDATE, mo.getStatementType());
        assertNull(mo.getSchemaName());
        assertEquals("big_tbl", mo.getTableName());
        assertNull(mo.getAlias());
        assertTrue(mo.isHasWhere());
        assertFalse(mo.isSelfReferencingSet());
        assertEquals(List.of("status"), mo.getSetColumns());
        assertTrue(mo.getNondeterministicFunctions().isEmpty());
    }

    @Test
    public void plainDeleteIsSupported() {
        DmlBatchShapeMO mo = spi.analyzeDmlForBatch("q1", "DELETE FROM logs WHERE created < '2020-01-01'", ctx(null));
        assertTrue(mo.isSupported());
        assertEquals(SplitQueryType.DELETE, mo.getStatementType());
        assertEquals("logs", mo.getTableName());
        assertNull(mo.getAlias());
        assertTrue(mo.isHasWhere());
        assertTrue(mo.getSetColumns().isEmpty());
    }

    @Test
    public void aliasesAreExtractedWithAndWithoutAs() {
        assertEquals("a", spi.analyzeDmlForBatch("q1", "UPDATE t AS a SET x = 1 WHERE y = 2", ctx(null)).getAlias());
        assertEquals("a", spi.analyzeDmlForBatch("q1", "UPDATE t a SET x = 1 WHERE y = 2", ctx(null)).getAlias());
        assertEquals("l", spi.analyzeDmlForBatch("q1", "DELETE FROM logs AS l WHERE l.x = 1", ctx(null)).getAlias());
    }

    @Test
    public void unquotedIdentifiersFoldToLowerCase() {
        DmlBatchShapeMO mo = spi.analyzeDmlForBatch("q1", "UPDATE MyTbl SET MyCol = 1 WHERE b = 2", ctx(null));
        assertTrue(mo.isSupported());
        assertEquals("mytbl", mo.getTableName());
        assertEquals(List.of("mycol"), mo.getSetColumns());
    }

    @Test
    public void quotedIdentifiersKeepTheirCase() {
        DmlBatchShapeMO mo = spi.analyzeDmlForBatch("q1", "UPDATE \"MyTbl\" SET \"MyCol\" = 1 WHERE b = 2", ctx(null));
        assertTrue(mo.isSupported());
        assertEquals("MyTbl", mo.getTableName());
        assertEquals(List.of("MyCol"), mo.getSetColumns());
    }

    @Test
    public void multiColumnSetCollectsAllTargets() {
        DmlBatchShapeMO mo = spi.analyzeDmlForBatch("q1", "UPDATE t SET a = 1, b = c + 1 WHERE x = 0", ctx(null));
        assertTrue(mo.isSupported());
        assertEquals(List.of("a", "b"), mo.getSetColumns());
        assertFalse(mo.isSelfReferencingSet());
    }

    // ================================================================== analyze: schema prefix

    @Test
    public void matchingSchemaPrefixIsAccepted() {
        DmlBatchShapeMO mo = spi.analyzeDmlForBatch("q1", "UPDATE public.t SET a = 1 WHERE b = 2", ctx("public"));
        assertTrue(mo.isSupported());
        assertEquals("public", mo.getSchemaName());
        assertEquals("t", mo.getTableName());
    }

    @Test
    public void mismatchingSchemaPrefixIsRejected() {
        DmlBatchShapeMO mo = spi.analyzeDmlForBatch("q1", "UPDATE other.t SET a = 1 WHERE b = 2", ctx("public"));
        assertFalse(mo.isSupported());
        assertEquals(DmlBatchShapeMO.REJECT_SCHEMA_MISMATCH, mo.getRejectCode());
    }

    @Test
    public void prefixedStatementWithoutExpectedSchemaIsRejectedFailSafe() {
        DmlBatchShapeMO mo = spi.analyzeDmlForBatch("q1", "UPDATE public.t SET a = 1 WHERE b = 2", ctx(null));
        assertFalse(mo.isSupported());
        assertEquals(DmlBatchShapeMO.REJECT_SCHEMA_MISMATCH, mo.getRejectCode());
    }

    @Test
    public void schemaComparisonHonorsCaseFolding() {
        // bare Public folds to public — matches the expected catalog name
        DmlBatchShapeMO folded = spi.analyzeDmlForBatch("q1", "UPDATE Public.T SET a = 1 WHERE b = 2", ctx("public"));
        assertTrue(folded.isSupported());
        assertEquals("public", folded.getSchemaName());
        assertEquals("t", folded.getTableName());
        // quoted "Public" keeps its case and is a DIFFERENT schema than public
        assertEquals(DmlBatchShapeMO.REJECT_SCHEMA_MISMATCH,
                spi.analyzeDmlForBatch("q1", "UPDATE \"Public\".t SET a = 1 WHERE b = 2", ctx("public")).getRejectCode());
        DmlBatchShapeMO quoted = spi.analyzeDmlForBatch("q1", "UPDATE \"Public\".t SET a = 1 WHERE b = 2", ctx("Public"));
        assertTrue(quoted.isSupported());
        assertEquals("Public", quoted.getSchemaName());
    }

    @Test
    public void unprefixedStatementNeedsNoSchemaContext() {
        assertTrue(spi.analyzeDmlForBatch("q1", "UPDATE t SET a = 1 WHERE b = 2", ctx(null)).isSupported());
        assertTrue(spi.analyzeDmlForBatch("q1", "UPDATE t SET a = 1 WHERE b = 2", ctx("public")).isSupported());
    }

    // ================================================================== analyze: rejection matrix (every rejectCode reachable on PG)

    @Test
    public void rejectsNoWhere() {
        assertEquals(DmlBatchShapeMO.REJECT_NO_WHERE,
                spi.analyzeDmlForBatch("q1", "UPDATE t SET a = 1", ctx(null)).getRejectCode());
        assertEquals(DmlBatchShapeMO.REJECT_NO_WHERE,
                spi.analyzeDmlForBatch("q1", "DELETE FROM t", ctx(null)).getRejectCode());
    }

    @Test
    public void rejectsWhereCurrentOf() {
        // WHERE CURRENT OF carries no injectable boolean predicate
        assertEquals(DmlBatchShapeMO.REJECT_NO_WHERE,
                spi.analyzeDmlForBatch("q1", "DELETE FROM t WHERE CURRENT OF mycur", ctx(null)).getRejectCode());
    }

    @Test
    public void rejectsMultiTableForms() {
        assertEquals(DmlBatchShapeMO.REJECT_MULTI_TABLE,
                spi.analyzeDmlForBatch("q1", "UPDATE t SET a = 1 FROM o WHERE t.id = o.id", ctx(null)).getRejectCode());
        assertEquals(DmlBatchShapeMO.REJECT_MULTI_TABLE,
                spi.analyzeDmlForBatch("q1", "DELETE FROM t USING o WHERE t.id = o.id", ctx(null)).getRejectCode());
    }

    @Test
    public void rejectsCte() {
        assertEquals(DmlBatchShapeMO.REJECT_CTE, spi.analyzeDmlForBatch("q1",
                "WITH c AS (SELECT id FROM src WHERE x = 1) UPDATE t SET a = 1 WHERE id IN (SELECT id FROM c)", ctx(null)).getRejectCode());
        assertEquals(DmlBatchShapeMO.REJECT_CTE, spi.analyzeDmlForBatch("q1",
                "WITH c AS (SELECT id FROM src WHERE x = 1) DELETE FROM t WHERE id IN (SELECT id FROM c)", ctx(null)).getRejectCode());
    }

    @Test
    public void rejectsReturning() {
        assertEquals(DmlBatchShapeMO.REJECT_UNSUPPORTED_SHAPE,
                spi.analyzeDmlForBatch("q1", "UPDATE t SET a = 1 WHERE b = 2 RETURNING a", ctx(null)).getRejectCode());
        assertEquals(DmlBatchShapeMO.REJECT_UNSUPPORTED_SHAPE,
                spi.analyzeDmlForBatch("q1", "DELETE FROM t WHERE b = 2 RETURNING *", ctx(null)).getRejectCode());
    }

    @Test
    public void rejectsOnlyAndStarRelationForms() {
        assertEquals(DmlBatchShapeMO.REJECT_UNSUPPORTED_SHAPE,
                spi.analyzeDmlForBatch("q1", "UPDATE ONLY t SET a = 1 WHERE b = 2", ctx(null)).getRejectCode());
        assertEquals(DmlBatchShapeMO.REJECT_UNSUPPORTED_SHAPE,
                spi.analyzeDmlForBatch("q1", "DELETE FROM ONLY t WHERE b = 2", ctx(null)).getRejectCode());
        assertEquals(DmlBatchShapeMO.REJECT_UNSUPPORTED_SHAPE,
                spi.analyzeDmlForBatch("q1", "UPDATE t.* SET a = 1 WHERE b = 2", ctx(null)).getRejectCode());
    }

    @Test
    public void rejectsRowAssignmentAndQualifiedSetTargets() {
        assertEquals(DmlBatchShapeMO.REJECT_UNSUPPORTED_SHAPE,
                spi.analyzeDmlForBatch("q1", "UPDATE t SET (a, b) = (1, 2) WHERE c = 3", ctx(null)).getRejectCode());
        assertEquals(DmlBatchShapeMO.REJECT_UNSUPPORTED_SHAPE,
                spi.analyzeDmlForBatch("q1", "UPDATE t SET t.a = 1 WHERE b = 2", ctx(null)).getRejectCode());
    }

    @Test
    public void rejectsThreePartRelationNames() {
        // PG DML has no database qualification — a 3-part name is outside the whitelist
        assertEquals(DmlBatchShapeMO.REJECT_UNSUPPORTED_SHAPE,
                spi.analyzeDmlForBatch("q1", "UPDATE db.sc.t SET a = 1 WHERE b = 2", ctx(null)).getRejectCode());
    }

    @Test
    public void rejectsSetSubquery() {
        assertEquals(DmlBatchShapeMO.REJECT_SET_SUBQUERY,
                spi.analyzeDmlForBatch("q1", "UPDATE t SET a = (SELECT MAX(x) FROM o) WHERE b = 2", ctx(null)).getRejectCode());
        assertEquals(DmlBatchShapeMO.REJECT_SET_SUBQUERY,
                spi.analyzeDmlForBatch("q1", "UPDATE t SET a = 1, b = (SELECT y FROM o WHERE o.id = t.id) WHERE c = 2", ctx(null)).getRejectCode());
        // a WHERE-side subquery is NOT a SET subquery — stays inside the whitelist
        assertTrue(spi.analyzeDmlForBatch("q1", "UPDATE t SET a = 1 WHERE b IN (SELECT x FROM o)", ctx(null)).isSupported());
        assertTrue(spi.analyzeDmlForBatch("q1", "UPDATE t SET a = 1 WHERE b = 2 AND EXISTS (SELECT 1 FROM o WHERE o.id = t.id)", ctx(null)).isSupported());
    }

    @Test
    public void rejectsCommentsAnywhere() {
        assertEquals(DmlBatchShapeMO.REJECT_COMMENT,
                spi.analyzeDmlForBatch("q1", "UPDATE t SET a = 1 WHERE b = 2 -- trailing", ctx(null)).getRejectCode());
        assertEquals(DmlBatchShapeMO.REJECT_COMMENT,
                spi.analyzeDmlForBatch("q1", "/* leading */ UPDATE t SET a = 1 WHERE b = 2", ctx(null)).getRejectCode());
        assertEquals(DmlBatchShapeMO.REJECT_COMMENT,
                spi.analyzeDmlForBatch("q1", "UPDATE t SET a = 1 /* mid */ WHERE b = 2", ctx(null)).getRejectCode());
    }

    @Test
    public void rejectsMultipleStatements() {
        assertEquals(DmlBatchShapeMO.REJECT_MULTI_STATEMENT,
                spi.analyzeDmlForBatch("q1", "UPDATE t SET a = 1 WHERE b = 2; DELETE FROM t WHERE c = 3", ctx(null)).getRejectCode());
    }

    @Test
    public void rejectsNonUpdateDeleteStatements() {
        assertEquals(DmlBatchShapeMO.REJECT_UNSUPPORTED_STATEMENT,
                spi.analyzeDmlForBatch("q1", "SELECT * FROM t WHERE a = 1", ctx(null)).getRejectCode());
        assertEquals(DmlBatchShapeMO.REJECT_UNSUPPORTED_STATEMENT,
                spi.analyzeDmlForBatch("q1", "INSERT INTO t (a) VALUES (1)", ctx(null)).getRejectCode());
    }

    @Test
    public void rejectsUnparsableInput() {
        assertEquals(DmlBatchShapeMO.REJECT_PARSE_ERROR,
                spi.analyzeDmlForBatch("q1", "UPDATE FROM SET WHERE", ctx(null)).getRejectCode());
        assertEquals(DmlBatchShapeMO.REJECT_PARSE_ERROR, spi.analyzeDmlForBatch("q1", "", ctx(null)).getRejectCode());
        assertEquals(DmlBatchShapeMO.REJECT_PARSE_ERROR, spi.analyzeDmlForBatch("q1", null, ctx(null)).getRejectCode());
        // PG DML has no native ORDER BY — the MySQL-shaped statement is a PG parse error
        assertEquals(DmlBatchShapeMO.REJECT_PARSE_ERROR,
                spi.analyzeDmlForBatch("q1", "DELETE FROM t WHERE a = 1 ORDER BY a", ctx(null)).getRejectCode());
    }

    @Test
    public void analyzeNeverThrowsOnGarbage() {
        DmlBatchShapeMO mo = spi.analyzeDmlForBatch("q1", "\u0000\u0001 not sql ;;; @@ ##", ctx("x"));
        assertFalse(mo.isSupported());
    }

    // ================================================================== analyze: non-idempotence marks

    @Test
    public void detectsSelfReferencingSet() {
        assertTrue(spi.analyzeDmlForBatch("q1", "UPDATE t SET cnt = cnt + 1 WHERE id > 0", ctx(null)).isSelfReferencingSet());
        assertTrue(spi.analyzeDmlForBatch("q1", "UPDATE t SET name = name || 'x' WHERE id > 0", ctx(null)).isSelfReferencingSet());
        assertTrue(spi.analyzeDmlForBatch("q1", "UPDATE t AS x SET cnt = x.cnt + 1 WHERE id > 0", ctx(null)).isSelfReferencingSet());
        assertTrue(spi.analyzeDmlForBatch("q1", "UPDATE t SET \"Cnt\" = \"Cnt\" + 1 WHERE id > 0", ctx(null)).isSelfReferencingSet());
        // PG case semantics: quoted "Cnt" and bare cnt are DIFFERENT columns — not self-referencing
        assertFalse(spi.analyzeDmlForBatch("q1", "UPDATE t SET \"Cnt\" = cnt + 1 WHERE id > 0", ctx(null)).isSelfReferencingSet());
        assertFalse(spi.analyzeDmlForBatch("q1", "UPDATE t SET a = b WHERE id > 0", ctx(null)).isSelfReferencingSet());
        assertFalse(spi.analyzeDmlForBatch("q1", "DELETE FROM t WHERE id > 0", ctx(null)).isSelfReferencingSet());
    }

    @Test
    public void detectsNondeterministicFunctions() {
        assertEquals(List.of("now"),
                spi.analyzeDmlForBatch("q1", "UPDATE t SET ts = now() WHERE id > 0", ctx(null)).getNondeterministicFunctions());
        assertEquals(List.of("current_timestamp"),
                spi.analyzeDmlForBatch("q1", "UPDATE t SET ts = CURRENT_TIMESTAMP WHERE id > 0", ctx(null)).getNondeterministicFunctions());
        assertEquals(List.of("random"),
                spi.analyzeDmlForBatch("q1", "UPDATE t SET r = random() WHERE id > 0", ctx(null)).getNondeterministicFunctions());
        assertEquals(List.of("nextval"),
                spi.analyzeDmlForBatch("q1", "UPDATE t SET n = nextval('seq_x') WHERE id > 0", ctx(null)).getNondeterministicFunctions());
        // schema-qualified calls resolve on the last segment
        assertEquals(List.of("now"),
                spi.analyzeDmlForBatch("q1", "UPDATE t SET ts = pg_catalog.now() WHERE id > 0", ctx(null)).getNondeterministicFunctions());
        // quoted "Now" is a different (case-sensitive) function name — not folded, not flagged
        assertTrue(spi.analyzeDmlForBatch("q1", "UPDATE t SET ts = \"Now\"() WHERE id > 0", ctx(null))
                .getNondeterministicFunctions().isEmpty());
        // deterministic functions stay unflagged
        assertTrue(spi.analyzeDmlForBatch("q1", "UPDATE t SET a = upper(b), c = coalesce(d, 0) WHERE id > 0", ctx(null))
                .getNondeterministicFunctions().isEmpty());
        // WHERE-side functions are out of scope (SET expressions only)
        assertTrue(spi.analyzeDmlForBatch("q1", "UPDATE t SET a = 1 WHERE ts > now() - interval '1 day'", ctx(null))
                .getNondeterministicFunctions().isEmpty());
    }

    // ================================================================== boundary select rewrite (full-text pins)

    @Test
    public void boundarySelectUpdateNumericCursor() {
        String out = spi.rewriteToBatchBoundarySelect("q1",
                "UPDATE big_tbl SET status = 1 WHERE status = 0", spec("100", null, 5000), ctx(null));
        assertEquals("SELECT MAX(\"id\") FROM (SELECT \"id\" FROM big_tbl WHERE (status = 0) AND \"id\" > 100 ORDER BY \"id\" LIMIT 5000) bnd", out);
    }

    @Test
    public void boundarySelectUpdateWithAliasAndSchemaPrefix() {
        String out = spi.rewriteToBatchBoundarySelect("q1",
                "UPDATE myschema.orders AS o SET o.total = o.total + 1 WHERE o.state = 'NEW'",
                DmlBatchRangeSpec.of("id", DmlBatchRangeSpec.KEY_TYPE_INT, "0", null, 1000), ctx("myschema"));
        // the alias part of the key reference is the ORIGINAL token text (source-faithful),
        // only the key column is SPI-quoted
        assertEquals("SELECT MAX(\"id\") FROM (SELECT o.\"id\" FROM myschema.orders AS o WHERE (o.state = 'NEW') AND o.\"id\" > 0 ORDER BY o.\"id\" LIMIT 1000) bnd", out);
    }

    @Test
    public void boundarySelectDeleteFirstBatchOmitsLowerBound() {
        String out = spi.rewriteToBatchBoundarySelect("q1",
                "DELETE FROM logs WHERE created < '2020-01-01'", spec(null, null, 5000), ctx(null));
        assertEquals("SELECT MAX(\"id\") FROM (SELECT \"id\" FROM logs WHERE (created < '2020-01-01') ORDER BY \"id\" LIMIT 5000) bnd", out);
    }

    @Test
    public void boundarySelectKeepsTrailingSemicolonOutside() {
        String out = spi.rewriteToBatchBoundarySelect("q1",
                "UPDATE big_tbl SET status = 1 WHERE status = 0;", spec("100", null, 5000), ctx(null));
        assertEquals("SELECT MAX(\"id\") FROM (SELECT \"id\" FROM big_tbl WHERE (status = 0) AND \"id\" > 100 ORDER BY \"id\" LIMIT 5000) bnd;", out);
    }

    @Test
    public void boundarySelectQuotedIdentifiersStayQuoted() {
        String out = spi.rewriteToBatchBoundarySelect("q1",
                "UPDATE \"MyTbl\" SET \"Col\" = 1 WHERE \"Col\" > 0",
                DmlBatchRangeSpec.of("Id", DmlBatchRangeSpec.KEY_TYPE_INT, "5", null, 100), ctx(null));
        assertEquals("SELECT MAX(\"Id\") FROM (SELECT \"Id\" FROM \"MyTbl\" WHERE (\"Col\" > 0) AND \"Id\" > 5 ORDER BY \"Id\" LIMIT 100) bnd", out);
    }

    @Test
    public void boundarySelectWrapsTopLevelOrPredicate() {
        // check sampling: a top-level OR in the original WHERE must be parenthesized so the
        // injected key predicate ANDs with the WHOLE original condition, not just its last arm
        String out = spi.rewriteToBatchBoundarySelect("q1", "UPDATE t SET a = 1 WHERE b = 2 OR c = 3",
                spec("100", null, 500), ctx(null));
        assertEquals("SELECT MAX(\"id\") FROM (SELECT \"id\" FROM t WHERE (b = 2 OR c = 3) AND \"id\" > 100 ORDER BY \"id\" LIMIT 500) bnd", out);
    }

    // ================================================================== batch range rewrite (full-text pins)

    @Test
    public void rangeRewriteUpdateNumericBounds() {
        String out = spi.rewriteToBatchRange("q1",
                "UPDATE big_tbl SET status = 1 WHERE status = 0", spec("100", "200", 5000), ctx(null));
        assertEquals("UPDATE big_tbl SET status = 1 WHERE ((status = 0) AND \"id\" > 100 AND \"id\" <= 200)", out);
    }

    @Test
    public void rangeRewriteUpdateFirstBatchOmitsLowerBound() {
        String out = spi.rewriteToBatchRange("q1",
                "UPDATE big_tbl SET status = 1 WHERE status = 0", spec(null, "200", 5000), ctx(null));
        assertEquals("UPDATE big_tbl SET status = 1 WHERE ((status = 0) AND \"id\" <= 200)", out);
    }

    @Test
    public void rangeRewriteDeleteDatetimeCursor() {
        String out = spi.rewriteToBatchRange("q1",
                "DELETE FROM logs WHERE created < '2020-01-01'",
                DmlBatchRangeSpec.of("id", DmlBatchRangeSpec.KEY_TYPE_DATETIME, null, "'2019-12-31 23:59:59'", 5000), ctx(null));
        assertEquals("DELETE FROM logs WHERE ((created < '2020-01-01') AND \"id\" <= '2019-12-31 23:59:59')", out);
    }

    @Test
    public void rangeRewritePreservesOriginalFormattingVerbatim() {
        String sql = "UPDATE myschema.t1\nSET a = a + 1,\n    b = 'x'\nWHERE (a > 10 AND b <> 'y')\n   OR c IS NULL";
        String out = spi.rewriteToBatchRange("q1", sql, spec("5", "50", 5000), ctx("myschema"));
        assertEquals("UPDATE myschema.t1\nSET a = a + 1,\n    b = 'x'\nWHERE (((a > 10 AND b <> 'y')\n   OR c IS NULL) AND \"id\" > 5 AND \"id\" <= 50)", out);
    }

    @Test
    public void rangeRewriteInjectsPreRenderedLiteralsVerbatim() {
        // escaping matrix: the console renders literals (R18 single escaping point, PG = quote
        // doubling only) — the SPI must inject them byte-identical
        String out = spi.rewriteToBatchRange("q1", "UPDATE t SET a = 1 WHERE b = 2",
                DmlBatchRangeSpec.of("name", DmlBatchRangeSpec.KEY_TYPE_STRING, "'it''s'", "'zebra'", 100), ctx(null));
        assertEquals("UPDATE t SET a = 1 WHERE ((b = 2) AND \"name\" > 'it''s' AND \"name\" <= 'zebra')", out);

        String tricky = spi.rewriteToBatchRange("q1", "UPDATE t SET a = 1 WHERE b = 2",
                DmlBatchRangeSpec.of("name", DmlBatchRangeSpec.KEY_TYPE_STRING, "'say \"hi\"'", "'back\\slash'", 100), ctx(null));
        assertEquals("UPDATE t SET a = 1 WHERE ((b = 2) AND \"name\" > 'say \"hi\"' AND \"name\" <= 'back\\slash')", tricky);
    }

    @Test
    public void rangeRewriteKeepsTrailingSemicolonOutside() {
        String out = spi.rewriteToBatchRange("q1",
                "UPDATE big_tbl SET status = 1 WHERE status = 0;", spec("100", "200", 5000), ctx(null));
        assertEquals("UPDATE big_tbl SET status = 1 WHERE ((status = 0) AND \"id\" > 100 AND \"id\" <= 200);", out);
    }

    @Test
    public void rangeRewriteUpdateAliasUsesOriginalTokenText() {
        // check sampling: UPDATE-side alias qualification at step ②; the alias part stays the
        // ORIGINAL token text — a double-quoted alias keeps its case, only the key column is
        // SPI-quoted
        String out = spi.rewriteToBatchRange("q1", "UPDATE myschema.orders AS o SET o.total = 1 WHERE o.state = 'NEW'",
                DmlBatchRangeSpec.of("id", DmlBatchRangeSpec.KEY_TYPE_INT, "0", "99", 1000), ctx("myschema"));
        assertEquals("UPDATE myschema.orders AS o SET o.total = 1 WHERE ((o.state = 'NEW') AND o.\"id\" > 0 AND o.\"id\" <= 99)", out);

        String quoted = spi.rewriteToBatchRange("q1", "UPDATE t AS \"A 1\" SET x = 1 WHERE y = 2",
                spec("0", "9", 100), ctx(null));
        assertEquals("UPDATE t AS \"A 1\" SET x = 1 WHERE ((y = 2) AND \"A 1\".\"id\" > 0 AND \"A 1\".\"id\" <= 9)", quoted);
    }

    @Test
    public void blankLowerBoundIsTreatedAsFirstBatch() {
        // DmlBatchRangeSpec contract: loLiteral null OR BLANK = first batch (no lower bound)
        String out = spi.rewriteToBatchRange("q1", "UPDATE t SET a = 1 WHERE b = 2", spec("", "50", 100), ctx(null));
        assertEquals("UPDATE t SET a = 1 WHERE ((b = 2) AND \"id\" <= 50)", out);
    }

    @Test
    public void rangeRewriteReturnsNullForWhereCurrentOf() {
        // check sampling: the CURRENT OF null-return was only pinned on the boundary-select side;
        // both rewrite methods share locateBatchTarget — pin the range side too (a bare injection
        // after CURRENT OF would produce a second WHERE)
        assertNull(spi.rewriteToBatchRange("q1", "UPDATE t SET a = 1 WHERE CURRENT OF mycur",
                spec("0", "10", 100), ctx(null)));
        assertNull(spi.rewriteToBatchRange("q1", "DELETE FROM t WHERE CURRENT OF mycur",
                spec("0", "10", 100), ctx(null)));
    }

    // ================================================================== defensive bare-WHERE forms (gate-excluded, rewriter still handles)

    @Test
    public void bareInjectionUpdateWithoutWhere() {
        String out = spi.rewriteToBatchRange("q1", "UPDATE big_tbl SET status = 1", spec(null, "200", 5000), ctx(null));
        assertEquals("UPDATE big_tbl SET status = 1 WHERE \"id\" <= 200", out);

        String both = spi.rewriteToBatchRange("q1", "UPDATE big_tbl SET status = 1", spec("0", "200", 5000), ctx(null));
        assertEquals("UPDATE big_tbl SET status = 1 WHERE \"id\" > 0 AND \"id\" <= 200", both);
    }

    @Test
    public void bareInjectionDeleteWithoutWhere() {
        String out = spi.rewriteToBatchRange("q1", "DELETE FROM logs", spec("0", "200", 5000), ctx(null));
        assertEquals("DELETE FROM logs WHERE \"id\" > 0 AND \"id\" <= 200", out);

        String aliased = spi.rewriteToBatchRange("q1", "DELETE FROM logs AS l", spec(null, "200", 5000), ctx(null));
        assertEquals("DELETE FROM logs AS l WHERE l.\"id\" <= 200", aliased);
    }

    @Test
    public void bareBoundarySelectWithoutWhere() {
        String out = spi.rewriteToBatchBoundarySelect("q1", "UPDATE big_tbl SET status = 1", spec(null, null, 5000), ctx(null));
        assertEquals("SELECT MAX(\"id\") FROM (SELECT \"id\" FROM big_tbl ORDER BY \"id\" LIMIT 5000) bnd", out);

        String delete = spi.rewriteToBatchBoundarySelect("q1", "DELETE FROM logs", spec("7", null, 100), ctx(null));
        assertEquals("SELECT MAX(\"id\") FROM (SELECT \"id\" FROM logs WHERE \"id\" > 7 ORDER BY \"id\" LIMIT 100) bnd", delete);
    }

    // ================================================================== rewrite: null returns + spec validation

    @Test
    public void rewriteReturnsNullForUntransformableStatements() {
        DmlBatchRangeSpec s = spec("0", "10", 100);
        assertNull(spi.rewriteToBatchRange("q1", "SELECT * FROM t WHERE a = 1", s, ctx(null)));
        assertNull(spi.rewriteToBatchRange("q1", "UPDATE t SET a = 1 FROM o WHERE t.id = o.id", s, ctx(null)));
        assertNull(spi.rewriteToBatchRange("q1", "DELETE FROM t USING o WHERE t.id = o.id", s, ctx(null)));
        assertNull(spi.rewriteToBatchRange("q1", "WITH c AS (SELECT 1) UPDATE t SET a = 1 WHERE b = 2", s, ctx(null)));
        assertNull(spi.rewriteToBatchRange("q1", "UPDATE t SET a = 1 WHERE b = 2 RETURNING a", s, ctx(null)));
        assertNull(spi.rewriteToBatchRange("q1", "UPDATE t SET a = 1 WHERE b = 2; DELETE FROM t WHERE c = 3", s, ctx(null)));
        assertNull(spi.rewriteToBatchRange("q1", "completely ## not sql", s, ctx(null)));
        assertNull(spi.rewriteToBatchRange("q1", null, s, ctx(null)));
        assertNull(spi.rewriteToBatchBoundarySelect("q1", "SELECT * FROM t", s, ctx(null)));
        assertNull(spi.rewriteToBatchBoundarySelect("q1", "DELETE FROM t WHERE CURRENT OF mycur", s, ctx(null)));
    }

    @Test
    public void rewriteRejectsInvalidSpecs() {
        String sql = "UPDATE t SET a = 1 WHERE b = 2";
        assertIae(() -> spi.rewriteToBatchRange("q1", sql, null, ctx(null)));
        assertIae(() -> spi.rewriteToBatchRange("q1", sql, DmlBatchRangeSpec.of(null, "INT", "0", "1", 10), ctx(null)));
        assertIae(() -> spi.rewriteToBatchRange("q1", sql, DmlBatchRangeSpec.of("", "INT", "0", "1", 10), ctx(null)));
        assertIae(() -> spi.rewriteToBatchRange("q1", sql, DmlBatchRangeSpec.of("a\"b", "INT", "0", "1", 10), ctx(null)));
        assertIae(() -> spi.rewriteToBatchRange("q1", sql, DmlBatchRangeSpec.of("a b", "INT", "0", "1", 10), ctx(null)));
        assertIae(() -> spi.rewriteToBatchRange("q1", sql, DmlBatchRangeSpec.of("id", "INT", "0", "1", 0), ctx(null)));
        // hiLiteral is mandatory for the range rewrite, optional (ignored) for the boundary select
        assertIae(() -> spi.rewriteToBatchRange("q1", sql, DmlBatchRangeSpec.of("id", "INT", "0", null, 10), ctx(null)));
        assertEquals("SELECT MAX(\"id\") FROM (SELECT \"id\" FROM t WHERE (b = 2) ORDER BY \"id\" LIMIT 10) bnd",
                spi.rewriteToBatchBoundarySelect("q1", sql, DmlBatchRangeSpec.of("id", "INT", null, null, 10), ctx(null)));
    }

    private static void assertIae(Runnable runnable) {
        try {
            runnable.run();
            org.junit.Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // contract
        }
    }

    // ================================================================== existing surface untouched (regression)

    @Test
    public void rewriteToExplainStillWorks() {
        assertEquals("EXPLAIN UPDATE t SET a = 1 WHERE b = 2",
                spi.rewriteToExplain("q1", "UPDATE t SET a = 1 WHERE b = 2", new RewriteContext()));
        assertNull(spi.rewriteToExplain("q1", "UPDATE t SET a = 1; DELETE FROM t", new RewriteContext()));
    }

    @Test
    public void rewriteLimitStillWorks() {
        RewriteContext context = new RewriteContext();
        context.setFetchLimit(10);
        assertEquals("SELECT * FROM t WHERE a = 1 LIMIT 10",
                spi.rewriteLimit("q1", "SELECT * FROM t WHERE a = 1", context));
    }

    @Test
    public void versionAccessorStillWorks() {
        assertEquals(PostgresVersion.POSTGRES_15, spi.version());
        assertTrue(Arrays.asList(PostgresVersion.values()).contains(PostgresVersion.POSTGRES_15));
    }
}
