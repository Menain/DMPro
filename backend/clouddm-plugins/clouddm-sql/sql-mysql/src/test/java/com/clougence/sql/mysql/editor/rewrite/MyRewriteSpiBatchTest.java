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
package com.clougence.sql.mysql.editor.rewrite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.clougence.clouddm.sdk.sql.editor.rewrite.DmlBatchRangeSpec;
import com.clougence.clouddm.sdk.sql.editor.rewrite.DmlBatchShapeMO;
import com.clougence.clouddm.sdk.sql.editor.rewrite.RewriteContext;
import com.clougence.clouddm.sdk.sql.parser.SplitQueryType;
import com.clougence.sql.mysql.parser.MySqlParserConfig;

/**
 * B7 lock-free DML — MySQL shape gate + keyset rewrite pins (design 09-17-lockfree-dml §3).
 *
 * <p>Discipline (B4/B6 heritage): rewrite assertions pin the COMPLETE output SQL text, never a
 * single token; the rejection matrix walks every rejectCode; the escaping matrix feeds
 * pre-rendered console literals verbatim through the rewriter.</p>
 */
public class MyRewriteSpiBatchTest {

    // 8.0.33: >= 8.0.16 so the DELETE table-alias grammar gate is active
    private final MyRewriteSpi spi = new MyRewriteSpi(MySqlParserConfig.unknownSqlMode("8.0.33"));

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
        assertFalse(mo.isSelfReferencingSet());
        assertTrue(mo.getSetColumns().isEmpty());
        assertTrue(mo.getNondeterministicFunctions().isEmpty());
    }

    @Test
    public void aliasesAreExtractedWithAndWithoutAs() {
        assertEquals("a", spi.analyzeDmlForBatch("q1", "UPDATE t a SET x = 1 WHERE y = 2", ctx(null)).getAlias());
        assertEquals("a", spi.analyzeDmlForBatch("q1", "UPDATE t AS a SET x = 1 WHERE y = 2", ctx(null)).getAlias());
        assertTrue(spi.analyzeDmlForBatch("q1", "DELETE FROM logs AS l WHERE l.x = 1", ctx(null)).isSupported());
        assertEquals("l", spi.analyzeDmlForBatch("q1", "DELETE FROM logs AS l WHERE l.x = 1", ctx(null)).getAlias());
        assertEquals("l", spi.analyzeDmlForBatch("q1", "DELETE FROM logs l WHERE l.x = 1", ctx(null)).getAlias());
    }

    @Test
    public void backtickedIdentifiersAreUnquoted() {
        DmlBatchShapeMO mo = spi.analyzeDmlForBatch("q1", "UPDATE `mydb`.`tbl` SET `a` = 1 WHERE `b` = 2", ctx("mydb"));
        assertTrue(mo.isSupported());
        assertEquals("mydb", mo.getSchemaName());
        assertEquals("tbl", mo.getTableName());
        assertEquals(List.of("a"), mo.getSetColumns());
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
        DmlBatchShapeMO mo = spi.analyzeDmlForBatch("q1", "UPDATE mydb.t SET a = 1 WHERE b = 2", ctx("mydb"));
        assertTrue(mo.isSupported());
        assertEquals("mydb", mo.getSchemaName());
        assertEquals("t", mo.getTableName());
    }

    @Test
    public void mismatchingSchemaPrefixIsRejected() {
        DmlBatchShapeMO mo = spi.analyzeDmlForBatch("q1", "UPDATE otherdb.t SET a = 1 WHERE b = 2", ctx("mydb"));
        assertFalse(mo.isSupported());
        assertEquals(DmlBatchShapeMO.REJECT_SCHEMA_MISMATCH, mo.getRejectCode());
    }

    @Test
    public void prefixedStatementWithoutExpectedSchemaIsRejectedFailSafe() {
        DmlBatchShapeMO mo = spi.analyzeDmlForBatch("q1", "UPDATE mydb.t SET a = 1 WHERE b = 2", ctx(null));
        assertFalse(mo.isSupported());
        assertEquals(DmlBatchShapeMO.REJECT_SCHEMA_MISMATCH, mo.getRejectCode());
    }

    @Test
    public void schemaComparisonIsCaseSensitivePerB6Discipline() {
        // B6 GhostAlterStmtChecker heritage: exact match after unquoting (MySQL db names are
        // case-sensitive on the primary deployment platform, lower_case_table_names=0)
        assertFalse(spi.analyzeDmlForBatch("q1", "UPDATE MyDb.t SET a = 1 WHERE b = 2", ctx("mydb")).isSupported());
        assertEquals(DmlBatchShapeMO.REJECT_SCHEMA_MISMATCH,
                spi.analyzeDmlForBatch("q1", "UPDATE MyDb.t SET a = 1 WHERE b = 2", ctx("mydb")).getRejectCode());
    }

    @Test
    public void unprefixedStatementNeedsNoSchemaContext() {
        assertTrue(spi.analyzeDmlForBatch("q1", "UPDATE t SET a = 1 WHERE b = 2", ctx(null)).isSupported());
        assertTrue(spi.analyzeDmlForBatch("q1", "UPDATE t SET a = 1 WHERE b = 2", ctx("mydb")).isSupported());
    }

    // ================================================================== analyze: rejection matrix (every rejectCode)

    @Test
    public void rejectsNoWhere() {
        assertEquals(DmlBatchShapeMO.REJECT_NO_WHERE,
                spi.analyzeDmlForBatch("q1", "UPDATE t SET a = 1", ctx(null)).getRejectCode());
        assertEquals(DmlBatchShapeMO.REJECT_NO_WHERE,
                spi.analyzeDmlForBatch("q1", "DELETE FROM t", ctx(null)).getRejectCode());
    }

    @Test
    public void rejectsMultiTableForms() {
        assertEquals(DmlBatchShapeMO.REJECT_MULTI_TABLE,
                spi.analyzeDmlForBatch("q1", "UPDATE t1, t2 SET t1.a = t2.b WHERE t1.id = t2.id", ctx(null)).getRejectCode());
        assertEquals(DmlBatchShapeMO.REJECT_MULTI_TABLE,
                spi.analyzeDmlForBatch("q1", "DELETE t1 FROM t1 JOIN t2 ON t1.id = t2.id WHERE t2.x = 1", ctx(null)).getRejectCode());
    }

    @Test
    public void rejectsCte() {
        assertEquals(DmlBatchShapeMO.REJECT_CTE, spi.analyzeDmlForBatch("q1",
                "WITH c AS (SELECT id FROM src WHERE x = 1) UPDATE t SET a = 1 WHERE id IN (SELECT id FROM c)", ctx(null)).getRejectCode());
        assertEquals(DmlBatchShapeMO.REJECT_CTE, spi.analyzeDmlForBatch("q1",
                "WITH c AS (SELECT id FROM src WHERE x = 1) DELETE FROM t WHERE id IN (SELECT id FROM c)", ctx(null)).getRejectCode());
    }

    @Test
    public void rejectsPartitionClause() {
        assertEquals(DmlBatchShapeMO.REJECT_PARTITION,
                spi.analyzeDmlForBatch("q1", "UPDATE t PARTITION (p0) SET a = 1 WHERE b = 2", ctx(null)).getRejectCode());
        assertEquals(DmlBatchShapeMO.REJECT_PARTITION,
                spi.analyzeDmlForBatch("q1", "DELETE FROM t PARTITION (p0) WHERE b = 2", ctx(null)).getRejectCode());
    }

    @Test
    public void rejectsNativeOrderBy() {
        assertEquals(DmlBatchShapeMO.REJECT_ORDER_BY,
                spi.analyzeDmlForBatch("q1", "UPDATE t SET a = 1 WHERE b = 2 ORDER BY id", ctx(null)).getRejectCode());
        assertEquals(DmlBatchShapeMO.REJECT_ORDER_BY,
                spi.analyzeDmlForBatch("q1", "DELETE FROM t WHERE b = 2 ORDER BY id", ctx(null)).getRejectCode());
    }

    @Test
    public void rejectsNativeLimit() {
        assertEquals(DmlBatchShapeMO.REJECT_LIMIT,
                spi.analyzeDmlForBatch("q1", "UPDATE t SET a = 1 WHERE b = 2 LIMIT 10", ctx(null)).getRejectCode());
        assertEquals(DmlBatchShapeMO.REJECT_LIMIT,
                spi.analyzeDmlForBatch("q1", "DELETE FROM t WHERE b = 2 LIMIT 10", ctx(null)).getRejectCode());
    }

    @Test
    public void rejectsSetSubquery() {
        assertEquals(DmlBatchShapeMO.REJECT_SET_SUBQUERY,
                spi.analyzeDmlForBatch("q1", "UPDATE t SET a = (SELECT MAX(x) FROM o) WHERE b = 2", ctx(null)).getRejectCode());
        assertEquals(DmlBatchShapeMO.REJECT_SET_SUBQUERY,
                spi.analyzeDmlForBatch("q1", "UPDATE t SET a = 1, b = (SELECT y FROM o WHERE o.id = t.id) WHERE c = 2", ctx(null)).getRejectCode());
        // a WHERE-side subquery is NOT a SET subquery — stays inside the whitelist
        assertTrue(spi.analyzeDmlForBatch("q1", "UPDATE t SET a = 1 WHERE b IN (SELECT x FROM o)", ctx(null)).isSupported());
    }

    @Test
    public void rejectsIndexHint() {
        assertEquals(DmlBatchShapeMO.REJECT_INDEX_HINT,
                spi.analyzeDmlForBatch("q1", "UPDATE t USE INDEX (idx_a) SET a = 1 WHERE b = 2", ctx(null)).getRejectCode());
        assertEquals(DmlBatchShapeMO.REJECT_INDEX_HINT,
                spi.analyzeDmlForBatch("q1", "UPDATE t FORCE INDEX FOR JOIN (idx_a) SET a = 1 WHERE b = 2", ctx(null)).getRejectCode());
    }

    @Test
    public void rejectsSessionVariableReferences() {
        // check fix (R1 silent-corruption class): @var/@@var are per-connection state — each batch
        // runs in a FRESH session where the variable reads as NULL (silent no-op termination or
        // silent NULL writes), while the direct chain keeps ONE session across the whole group
        assertEquals(DmlBatchShapeMO.REJECT_UNSUPPORTED_SHAPE,
                spi.analyzeDmlForBatch("q1", "UPDATE t SET a = @v WHERE id > 0", ctx(null)).getRejectCode());
        assertEquals(DmlBatchShapeMO.REJECT_UNSUPPORTED_SHAPE,
                spi.analyzeDmlForBatch("q1", "UPDATE t SET a = 1 WHERE created < @cutoff", ctx(null)).getRejectCode());
        assertEquals(DmlBatchShapeMO.REJECT_UNSUPPORTED_SHAPE,
                spi.analyzeDmlForBatch("q1", "UPDATE t SET a = 1 WHERE (@x := @x + 1) < 5", ctx(null)).getRejectCode());
        assertEquals(DmlBatchShapeMO.REJECT_UNSUPPORTED_SHAPE,
                spi.analyzeDmlForBatch("q1", "DELETE FROM t WHERE a > @@threshold", ctx(null)).getRejectCode());
        // negative controls: SET col := expr is a plain column assignment (no session state) and
        // an @ inside a string literal is not a variable — both stay supported
        assertTrue(spi.analyzeDmlForBatch("q1", "UPDATE t SET a := 1 WHERE b = 'x@y'", ctx(null)).isSupported());
    }

    @Test
    public void rejectsCommentsAnywhere() {
        assertEquals(DmlBatchShapeMO.REJECT_COMMENT,
                spi.analyzeDmlForBatch("q1", "UPDATE t SET a = 1 WHERE b = 2 -- trailing", ctx(null)).getRejectCode());
        assertEquals(DmlBatchShapeMO.REJECT_COMMENT,
                spi.analyzeDmlForBatch("q1", "/* leading */ UPDATE t SET a = 1 WHERE b = 2", ctx(null)).getRejectCode());
        assertEquals(DmlBatchShapeMO.REJECT_COMMENT,
                spi.analyzeDmlForBatch("q1", "UPDATE t SET a = 1 /* mid */ WHERE b = 2", ctx(null)).getRejectCode());
        assertEquals(DmlBatchShapeMO.REJECT_COMMENT,
                spi.analyzeDmlForBatch("q1", "UPDATE t SET a = 1 # hash\n WHERE b = 2", ctx(null)).getRejectCode());
        // executable comments are comments too (zero-comment discipline)
        assertEquals(DmlBatchShapeMO.REJECT_COMMENT,
                spi.analyzeDmlForBatch("q1", "UPDATE t SET a = 1 /*! WHERE b = 2 */", ctx(null)).getRejectCode());
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
        assertEquals(DmlBatchShapeMO.REJECT_UNSUPPORTED_STATEMENT,
                spi.analyzeDmlForBatch("q1", "REPLACE INTO t (a) VALUES (1)", ctx(null)).getRejectCode());
        assertEquals(DmlBatchShapeMO.REJECT_UNSUPPORTED_STATEMENT,
                spi.analyzeDmlForBatch("q1", "ALTER TABLE t ADD COLUMN c INT", ctx(null)).getRejectCode());
    }

    @Test
    public void rejectsUnparsableInput() {
        assertEquals(DmlBatchShapeMO.REJECT_PARSE_ERROR,
                spi.analyzeDmlForBatch("q1", "UPDATE FROM SET WHERE", ctx(null)).getRejectCode());
        assertEquals(DmlBatchShapeMO.REJECT_PARSE_ERROR, spi.analyzeDmlForBatch("q1", "", ctx(null)).getRejectCode());
        assertEquals(DmlBatchShapeMO.REJECT_PARSE_ERROR, spi.analyzeDmlForBatch("q1", null, ctx(null)).getRejectCode());
    }

    @Test
    public void analyzeNeverThrowsOnGarbage() {
        // degradation contract: garbage in, not-supported shape out — never an exception
        DmlBatchShapeMO mo = spi.analyzeDmlForBatch("q1", "\u0000\u0001 not sql ;;; @@ ##", ctx("x"));
        assertFalse(mo.isSupported());
    }

    // ================================================================== analyze: non-idempotence marks

    @Test
    public void detectsSelfReferencingSet() {
        assertTrue(spi.analyzeDmlForBatch("q1", "UPDATE t SET cnt = cnt + 1 WHERE id > 0", ctx(null)).isSelfReferencingSet());
        assertTrue(spi.analyzeDmlForBatch("q1", "UPDATE t SET name = CONCAT(name, 'x') WHERE id > 0", ctx(null)).isSelfReferencingSet());
        assertTrue(spi.analyzeDmlForBatch("q1", "UPDATE t AS a SET a.cnt = a.cnt + 1 WHERE a.id > 0", ctx(null)).isSelfReferencingSet());
        assertTrue(spi.analyzeDmlForBatch("q1", "UPDATE t SET CNT = cnt + 1 WHERE id > 0", ctx(null)).isSelfReferencingSet());
        // column-to-column with a different column is NOT self-referencing
        assertFalse(spi.analyzeDmlForBatch("q1", "UPDATE t SET a = b WHERE id > 0", ctx(null)).isSelfReferencingSet());
        assertFalse(spi.analyzeDmlForBatch("q1", "DELETE FROM t WHERE id > 0", ctx(null)).isSelfReferencingSet());
    }

    @Test
    public void detectsNondeterministicFunctions() {
        assertEquals(List.of("NOW"),
                spi.analyzeDmlForBatch("q1", "UPDATE t SET ts = NOW() WHERE id > 0", ctx(null)).getNondeterministicFunctions());
        assertEquals(List.of("SYSDATE"),
                spi.analyzeDmlForBatch("q1", "UPDATE t SET ts = SYSDATE() WHERE id > 0", ctx(null)).getNondeterministicFunctions());
        assertEquals(List.of("CURRENT_TIMESTAMP"),
                spi.analyzeDmlForBatch("q1", "UPDATE t SET ts = CURRENT_TIMESTAMP WHERE id > 0", ctx(null)).getNondeterministicFunctions());
        assertEquals(List.of("RAND", "UUID"),
                spi.analyzeDmlForBatch("q1", "UPDATE t SET r = RAND(), u = UUID() WHERE id > 0", ctx(null)).getNondeterministicFunctions());
        // combined self-reference + non-deterministic
        DmlBatchShapeMO mo = spi.analyzeDmlForBatch("q1", "UPDATE t SET cnt = cnt + 1, ts = NOW(3) WHERE id > 0", ctx(null));
        assertTrue(mo.isSelfReferencingSet());
        assertEquals(List.of("NOW"), mo.getNondeterministicFunctions());
        // deterministic functions stay unflagged
        assertTrue(spi.analyzeDmlForBatch("q1", "UPDATE t SET a = UPPER(b), c = IFNULL(d, 0) WHERE id > 0", ctx(null))
                .getNondeterministicFunctions().isEmpty());
        // WHERE-side functions are out of scope (SET expressions only)
        assertTrue(spi.analyzeDmlForBatch("q1", "UPDATE t SET a = 1 WHERE ts > NOW() - INTERVAL 1 DAY", ctx(null))
                .getNondeterministicFunctions().isEmpty());
    }

    // ================================================================== boundary select rewrite (full-text pins)

    @Test
    public void boundarySelectUpdateNumericCursor() {
        String out = spi.rewriteToBatchBoundarySelect("q1",
                "UPDATE big_tbl SET status = 1 WHERE status = 0", spec("100", null, 5000), ctx(null));
        assertEquals("SELECT MAX(`id`) FROM (SELECT `id` FROM big_tbl WHERE (status = 0) AND `id` > 100 ORDER BY `id` LIMIT 5000) bnd", out);
    }

    @Test
    public void boundarySelectUpdateWithAliasAndSchemaPrefix() {
        String out = spi.rewriteToBatchBoundarySelect("q1",
                "UPDATE mydb.orders AS o SET o.total = o.total + 1 WHERE o.state = 'NEW'",
                DmlBatchRangeSpec.of("id", DmlBatchRangeSpec.KEY_TYPE_INT, "0", null, 1000), ctx("mydb"));
        // the alias part of the key reference is the ORIGINAL token text (source-faithful; a
        // backtick-quoted alias in the source stays quoted), only the key column is SPI-quoted
        assertEquals("SELECT MAX(`id`) FROM (SELECT o.`id` FROM mydb.orders AS o WHERE (o.state = 'NEW') AND o.`id` > 0 ORDER BY o.`id` LIMIT 1000) bnd", out);
    }

    @Test
    public void boundarySelectDeleteFirstBatchOmitsLowerBound() {
        String out = spi.rewriteToBatchBoundarySelect("q1",
                "DELETE FROM logs WHERE created < '2020-01-01'", spec(null, null, 5000), ctx(null));
        assertEquals("SELECT MAX(`id`) FROM (SELECT `id` FROM logs WHERE (created < '2020-01-01') ORDER BY `id` LIMIT 5000) bnd", out);
    }

    @Test
    public void boundarySelectKeepsTrailingSemicolonOutside() {
        String out = spi.rewriteToBatchBoundarySelect("q1",
                "UPDATE big_tbl SET status = 1 WHERE status = 0;", spec("100", null, 5000), ctx(null));
        assertEquals("SELECT MAX(`id`) FROM (SELECT `id` FROM big_tbl WHERE (status = 0) AND `id` > 100 ORDER BY `id` LIMIT 5000) bnd;", out);
    }

    @Test
    public void boundarySelectStringKeyAliasDelete() {
        String out = spi.rewriteToBatchBoundarySelect("q1",
                "DELETE FROM logs AS l WHERE l.created < '2020-01-01'",
                DmlBatchRangeSpec.of("name", DmlBatchRangeSpec.KEY_TYPE_STRING, "'m'", null, 2000), ctx(null));
        assertEquals("SELECT MAX(`name`) FROM (SELECT l.`name` FROM logs AS l WHERE (l.created < '2020-01-01') AND l.`name` > 'm' ORDER BY l.`name` LIMIT 2000) bnd", out);
    }

    @Test
    public void boundarySelectWrapsTopLevelOrPredicate() {
        // check sampling: a top-level OR in the original WHERE must be parenthesized so the
        // injected key predicate ANDs with the WHOLE original condition, not just its last arm
        String out = spi.rewriteToBatchBoundarySelect("q1", "UPDATE t SET a = 1 WHERE b = 2 OR c = 3",
                spec("100", null, 500), ctx(null));
        assertEquals("SELECT MAX(`id`) FROM (SELECT `id` FROM t WHERE (b = 2 OR c = 3) AND `id` > 100 ORDER BY `id` LIMIT 500) bnd", out);
    }

    // ================================================================== batch range rewrite (full-text pins)

    @Test
    public void rangeRewriteUpdateNumericBounds() {
        String out = spi.rewriteToBatchRange("q1",
                "UPDATE big_tbl SET status = 1 WHERE status = 0", spec("100", "200", 5000), ctx(null));
        assertEquals("UPDATE big_tbl SET status = 1 WHERE ((status = 0) AND `id` > 100 AND `id` <= 200)", out);
    }

    @Test
    public void rangeRewriteUpdateFirstBatchOmitsLowerBound() {
        String out = spi.rewriteToBatchRange("q1",
                "UPDATE big_tbl SET status = 1 WHERE status = 0", spec(null, "200", 5000), ctx(null));
        assertEquals("UPDATE big_tbl SET status = 1 WHERE ((status = 0) AND `id` <= 200)", out);
    }

    @Test
    public void rangeRewriteDeleteAliasDatetimeCursor() {
        String out = spi.rewriteToBatchRange("q1",
                "DELETE FROM logs AS l WHERE l.created < '2020-01-01'",
                DmlBatchRangeSpec.of("id", DmlBatchRangeSpec.KEY_TYPE_DATETIME, null, "'2019-12-31 23:59:59'", 5000), ctx(null));
        assertEquals("DELETE FROM logs AS l WHERE ((l.created < '2020-01-01') AND l.`id` <= '2019-12-31 23:59:59')", out);
    }

    @Test
    public void rangeRewritePreservesOriginalFormattingVerbatim() {
        String sql = "UPDATE mydb.t1\nSET a = a + 1,\n    b = 'x'\nWHERE (a > 10 AND b <> 'y')\n   OR c IS NULL";
        String out = spi.rewriteToBatchRange("q1", sql, spec("5", "50", 5000), ctx("mydb"));
        assertEquals("UPDATE mydb.t1\nSET a = a + 1,\n    b = 'x'\nWHERE (((a > 10 AND b <> 'y')\n   OR c IS NULL) AND `id` > 5 AND `id` <= 50)", out);
    }

    @Test
    public void rangeRewriteInjectsPreRenderedLiteralsVerbatim() {
        // escaping matrix: the console renders literals (R18 single escaping point) — the SPI
        // must inject them byte-identical, quote doubling and MySQL backslash doubling included
        String out = spi.rewriteToBatchRange("q1", "UPDATE t SET a = 1 WHERE b = 2",
                DmlBatchRangeSpec.of("name", DmlBatchRangeSpec.KEY_TYPE_STRING, "'it''s'", "'ze\\\\bra'", 100), ctx(null));
        assertEquals("UPDATE t SET a = 1 WHERE ((b = 2) AND `name` > 'it''s' AND `name` <= 'ze\\\\bra')", out);

        String quoted = spi.rewriteToBatchRange("q1", "UPDATE t SET a = 1 WHERE b = 2",
                DmlBatchRangeSpec.of("name", DmlBatchRangeSpec.KEY_TYPE_STRING, "'say \"hi\"'", "'back`tick'", 100), ctx(null));
        assertEquals("UPDATE t SET a = 1 WHERE ((b = 2) AND `name` > 'say \"hi\"' AND `name` <= 'back`tick')", quoted);
    }

    @Test
    public void rangeRewriteKeepsTrailingSemicolonOutside() {
        String out = spi.rewriteToBatchRange("q1",
                "UPDATE big_tbl SET status = 1 WHERE status = 0;", spec("100", "200", 5000), ctx(null));
        assertEquals("UPDATE big_tbl SET status = 1 WHERE ((status = 0) AND `id` > 100 AND `id` <= 200);", out);
    }

    @Test
    public void rangeRewriteUpdateAliasUsesOriginalTokenText() {
        // check sampling: UPDATE-side alias qualification at step ② (the boundary-select twin is
        // pinned above); the alias part stays the ORIGINAL token text — a backtick-quoted alias
        // with a space stays quoted verbatim, only the key column is SPI-quoted
        String out = spi.rewriteToBatchRange("q1", "UPDATE mydb.orders AS o SET o.total = 1 WHERE o.state = 'NEW'",
                DmlBatchRangeSpec.of("id", DmlBatchRangeSpec.KEY_TYPE_INT, "0", "99", 1000), ctx("mydb"));
        assertEquals("UPDATE mydb.orders AS o SET o.total = 1 WHERE ((o.state = 'NEW') AND o.`id` > 0 AND o.`id` <= 99)", out);

        String quoted = spi.rewriteToBatchRange("q1", "UPDATE t AS `a b` SET x = 1 WHERE y = 2",
                spec("0", "9", 100), ctx(null));
        assertEquals("UPDATE t AS `a b` SET x = 1 WHERE ((y = 2) AND `a b`.`id` > 0 AND `a b`.`id` <= 9)", quoted);
    }

    @Test
    public void blankLowerBoundIsTreatedAsFirstBatch() {
        // DmlBatchRangeSpec contract: loLiteral null OR BLANK = first batch (no lower bound)
        String out = spi.rewriteToBatchRange("q1", "UPDATE t SET a = 1 WHERE b = 2", spec("", "50", 100), ctx(null));
        assertEquals("UPDATE t SET a = 1 WHERE ((b = 2) AND `id` <= 50)", out);

        String boundary = spi.rewriteToBatchBoundarySelect("q1", "UPDATE t SET a = 1 WHERE b = 2", spec("  ", null, 100), ctx(null));
        assertEquals("SELECT MAX(`id`) FROM (SELECT `id` FROM t WHERE (b = 2) ORDER BY `id` LIMIT 100) bnd", boundary);
    }

    // ================================================================== defensive bare-WHERE forms (gate-excluded, rewriter still handles)

    @Test
    public void bareInjectionUpdateWithoutWhere() {
        String out = spi.rewriteToBatchRange("q1", "UPDATE big_tbl SET status = 1", spec(null, "200", 5000), ctx(null));
        assertEquals("UPDATE big_tbl SET status = 1 WHERE `id` <= 200", out);

        String both = spi.rewriteToBatchRange("q1", "UPDATE big_tbl SET status = 1", spec("0", "200", 5000), ctx(null));
        assertEquals("UPDATE big_tbl SET status = 1 WHERE `id` > 0 AND `id` <= 200", both);
    }

    @Test
    public void bareInjectionDeleteWithoutWhere() {
        String out = spi.rewriteToBatchRange("q1", "DELETE FROM logs", spec("0", "200", 5000), ctx(null));
        assertEquals("DELETE FROM logs WHERE `id` > 0 AND `id` <= 200", out);

        String aliased = spi.rewriteToBatchRange("q1", "DELETE FROM logs AS l", spec(null, "200", 5000), ctx(null));
        assertEquals("DELETE FROM logs AS l WHERE l.`id` <= 200", aliased);
    }

    @Test
    public void bareBoundarySelectWithoutWhere() {
        String out = spi.rewriteToBatchBoundarySelect("q1", "UPDATE big_tbl SET status = 1", spec(null, null, 5000), ctx(null));
        assertEquals("SELECT MAX(`id`) FROM (SELECT `id` FROM big_tbl ORDER BY `id` LIMIT 5000) bnd", out);

        String delete = spi.rewriteToBatchBoundarySelect("q1", "DELETE FROM logs", spec("7", null, 100), ctx(null));
        assertEquals("SELECT MAX(`id`) FROM (SELECT `id` FROM logs WHERE `id` > 7 ORDER BY `id` LIMIT 100) bnd", delete);
    }

    // ================================================================== rewrite: null returns + spec validation

    @Test
    public void rewriteReturnsNullForUntransformableStatements() {
        DmlBatchRangeSpec s = spec("0", "10", 100);
        assertNull(spi.rewriteToBatchRange("q1", "SELECT * FROM t WHERE a = 1", s, ctx(null)));
        assertNull(spi.rewriteToBatchRange("q1", "UPDATE t1, t2 SET t1.a = 1 WHERE t1.id = t2.id", s, ctx(null)));
        assertNull(spi.rewriteToBatchRange("q1", "WITH c AS (SELECT 1) UPDATE t SET a = 1 WHERE b = 2", s, ctx(null)));
        assertNull(spi.rewriteToBatchRange("q1", "DELETE FROM t WHERE a = 1 ORDER BY a", s, ctx(null)));
        assertNull(spi.rewriteToBatchRange("q1", "DELETE FROM t WHERE a = 1 LIMIT 5", s, ctx(null)));
        assertNull(spi.rewriteToBatchRange("q1", "UPDATE t SET a = 1 WHERE b = 2; DELETE FROM t WHERE c = 3", s, ctx(null)));
        assertNull(spi.rewriteToBatchRange("q1", "completely ## not sql", s, ctx(null)));
        assertNull(spi.rewriteToBatchRange("q1", null, s, ctx(null)));
        assertNull(spi.rewriteToBatchBoundarySelect("q1", "SELECT * FROM t", s, ctx(null)));
        assertNull(spi.rewriteToBatchBoundarySelect("q1", "UPDATE t SET a = 1 WHERE b = 2 ORDER BY id", s, ctx(null)));
    }

    @Test
    public void rewriteRejectsInvalidSpecs() {
        String sql = "UPDATE t SET a = 1 WHERE b = 2";
        assertThrows(IllegalArgumentException.class, () -> spi.rewriteToBatchRange("q1", sql, null, ctx(null)));
        assertThrows(IllegalArgumentException.class,
                () -> spi.rewriteToBatchRange("q1", sql, DmlBatchRangeSpec.of(null, "INT", "0", "1", 10), ctx(null)));
        assertThrows(IllegalArgumentException.class,
                () -> spi.rewriteToBatchRange("q1", sql, DmlBatchRangeSpec.of("", "INT", "0", "1", 10), ctx(null)));
        assertThrows(IllegalArgumentException.class,
                () -> spi.rewriteToBatchRange("q1", sql, DmlBatchRangeSpec.of("a`b", "INT", "0", "1", 10), ctx(null)));
        assertThrows(IllegalArgumentException.class,
                () -> spi.rewriteToBatchRange("q1", sql, DmlBatchRangeSpec.of("a b", "INT", "0", "1", 10), ctx(null)));
        assertThrows(IllegalArgumentException.class,
                () -> spi.rewriteToBatchRange("q1", sql, DmlBatchRangeSpec.of("id", "INT", "0", "1", 0), ctx(null)));
        // hiLiteral is mandatory for the range rewrite, optional (ignored) for the boundary select
        assertThrows(IllegalArgumentException.class,
                () -> spi.rewriteToBatchRange("q1", sql, DmlBatchRangeSpec.of("id", "INT", "0", null, 10), ctx(null)));
        assertEquals("SELECT MAX(`id`) FROM (SELECT `id` FROM t WHERE (b = 2) ORDER BY `id` LIMIT 10) bnd",
                spi.rewriteToBatchBoundarySelect("q1", sql, DmlBatchRangeSpec.of("id", "INT", null, null, 10), ctx(null)));
    }

    // ================================================================== existing surface untouched (regression)

    @Test
    public void rewriteToExplainStillWorks() {
        assertEquals("EXPLAIN FORMAT=TRADITIONAL UPDATE t SET a = 1 WHERE b = 2",
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
}
