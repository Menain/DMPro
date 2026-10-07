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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.clougence.clouddm.sdk.sql.editor.rewrite.DmlBatchRangeSpec;

/**
 * B7 N1b (implement.md 单测矩阵「字面量渲染」): the console-side SINGLE escaping point
 * (design §3.4, R18; N1a handover obligation #5) — three key types × two dialects × the escape
 * matrix ({@code '}, {@code \}, {@code "}) with COMPLETE fragment assertions (the B4 escaping
 * lesson: never assert single tokens).
 * <p>
 * Contract recap: INT/DECIMAL0 = plain-digit shape check then injected unquoted; DATETIME =
 * strict ISO-ish shape check (the character class excludes quotes/backslashes BY CONSTRUCTION)
 * then single-quoted; STRING = MySQL backslash-doubling FIRST then quote-doubling, PG
 * quote-doubling ONLY (standard_conforming_strings=on — doubling backslashes would CORRUPT the
 * value). Any shape deviation → null (the caller fails the batch loudly, never renders a
 * best-effort literal).
 */
public class DmlBatchLiteralsTest {

    // ==================== INT / DECIMAL0 ====================

    @Test
    public void renderInt_plainDigits_unquoted() {
        assertEquals("12345", DmlBatchLiterals.renderLiteral(DmlBatchRangeSpec.KEY_TYPE_INT, "12345", false));
        assertEquals("12345", DmlBatchLiterals.renderLiteral(DmlBatchRangeSpec.KEY_TYPE_INT, "12345", true));
    }

    @Test
    public void renderInt_negativeAndSurroundingWhitespace_trimmedThenAccepted() {
        assertEquals("-7", DmlBatchLiterals.renderLiteral(DmlBatchRangeSpec.KEY_TYPE_INT, " -7 ", false));
        assertEquals("-7", DmlBatchLiterals.renderLiteral(DmlBatchRangeSpec.KEY_TYPE_INT, " -7 ", true));
    }

    @Test
    public void renderInt_bigUnsignedBeyondLong_stillRendered_digitShapeOnly() {
        // the renderer never parses the value — a 20-digit unsigned BIGINT is a legal digit run
        String huge = "18446744073709551615";
        assertEquals(huge, DmlBatchLiterals.renderLiteral(DmlBatchRangeSpec.KEY_TYPE_INT, huge, false));
    }

    @Test
    public void renderInt_malformedShapes_null_neverRendered() {
        assertNull(DmlBatchLiterals.renderLiteral(DmlBatchRangeSpec.KEY_TYPE_INT, "+12", false));
        assertNull(DmlBatchLiterals.renderLiteral(DmlBatchRangeSpec.KEY_TYPE_INT, "1.5", false));
        assertNull(DmlBatchLiterals.renderLiteral(DmlBatchRangeSpec.KEY_TYPE_INT, "1e4", false));
        assertNull(DmlBatchLiterals.renderLiteral(DmlBatchRangeSpec.KEY_TYPE_INT, "12; DROP TABLE t", false));
        assertNull(DmlBatchLiterals.renderLiteral(DmlBatchRangeSpec.KEY_TYPE_INT, "'12'", false));
        assertNull(DmlBatchLiterals.renderLiteral(DmlBatchRangeSpec.KEY_TYPE_INT, "", false));
        assertNull(DmlBatchLiterals.renderLiteral(DmlBatchRangeSpec.KEY_TYPE_INT, null, false));
    }

    @Test
    public void renderDecimal0_sameDigitContractAsInt() {
        assertEquals("990", DmlBatchLiterals.renderLiteral(DmlBatchRangeSpec.KEY_TYPE_DECIMAL0, "990", true));
        assertNull(DmlBatchLiterals.renderLiteral(DmlBatchRangeSpec.KEY_TYPE_DECIMAL0, "9.90", true));
    }

    // ==================== DATETIME ====================

    @Test
    public void renderDatetime_mysqlShapes_singleQuotedVerbatim() {
        assertEquals("'2026-10-07 12:00:00'",
            DmlBatchLiterals.renderLiteral(DmlBatchRangeSpec.KEY_TYPE_DATETIME, "2026-10-07 12:00:00", false));
        assertEquals("'2026-10-07'",
            DmlBatchLiterals.renderLiteral(DmlBatchRangeSpec.KEY_TYPE_DATETIME, "2026-10-07", false));
        assertEquals("'2026-10-07 12:00:00.123456'",
            DmlBatchLiterals.renderLiteral(DmlBatchRangeSpec.KEY_TYPE_DATETIME, "2026-10-07 12:00:00.123456", false));
    }

    @Test
    public void renderDatetime_pgShapes_tSeparatorAndZones_singleQuoted() {
        assertEquals("'2026-10-07T12:00:00'",
            DmlBatchLiterals.renderLiteral(DmlBatchRangeSpec.KEY_TYPE_DATETIME, "2026-10-07T12:00:00", true));
        assertEquals("'2026-10-07 12:00:00.123456+08'",
            DmlBatchLiterals.renderLiteral(DmlBatchRangeSpec.KEY_TYPE_DATETIME, "2026-10-07 12:00:00.123456+08", true));
        assertEquals("'2026-10-07 12:00:00+08:00'",
            DmlBatchLiterals.renderLiteral(DmlBatchRangeSpec.KEY_TYPE_DATETIME, "2026-10-07 12:00:00+08:00", true));
        assertEquals("'2026-10-07 12:00:00Z'",
            DmlBatchLiterals.renderLiteral(DmlBatchRangeSpec.KEY_TYPE_DATETIME, "2026-10-07 12:00:00Z", true));
        assertEquals("'2026-10-07 12:00'",
            DmlBatchLiterals.renderLiteral(DmlBatchRangeSpec.KEY_TYPE_DATETIME, "2026-10-07 12:00", true));
    }

    @Test
    public void renderDatetime_injectionShapes_null_neverRendered() {
        // the character class excludes quotes/backslashes/semicolons BY CONSTRUCTION
        assertNull(DmlBatchLiterals.renderLiteral(DmlBatchRangeSpec.KEY_TYPE_DATETIME, "2026-10-07' OR '1'='1", false));
        assertNull(DmlBatchLiterals.renderLiteral(DmlBatchRangeSpec.KEY_TYPE_DATETIME, "2026-10-07\\", false));
        assertNull(DmlBatchLiterals.renderLiteral(DmlBatchRangeSpec.KEY_TYPE_DATETIME, "2026-10-07 12:00:00; DROP TABLE t", false));
        assertNull(DmlBatchLiterals.renderLiteral(DmlBatchRangeSpec.KEY_TYPE_DATETIME, "07.10.2026", false));
        assertNull(DmlBatchLiterals.renderLiteral(DmlBatchRangeSpec.KEY_TYPE_DATETIME, "26-10-07 12:00:00", false));
        assertNull(DmlBatchLiterals.renderLiteral(DmlBatchRangeSpec.KEY_TYPE_DATETIME, "", true));
    }

    // ==================== STRING — the escape matrix (complete fragment assertions) ====================

    @Test
    public void renderString_mysql_quoteDoubling() {
        assertEquals("'a''b'", DmlBatchLiterals.renderLiteral(DmlBatchRangeSpec.KEY_TYPE_STRING, "a'b", false));
    }

    @Test
    public void renderString_mysql_backslashDoublingFIRST_thenQuoteDoubling() {
        // the B4 lesson order: \ → \\ BEFORE ' → '' (the reversed order would double the
        // backslash that the quote-escape introduced)
        assertEquals("'a\\\\b'", DmlBatchLiterals.renderLiteral(DmlBatchRangeSpec.KEY_TYPE_STRING, "a\\b", false));
        assertEquals("'a''\\\\b'''", DmlBatchLiterals.renderLiteral(DmlBatchRangeSpec.KEY_TYPE_STRING, "a'\\b'", false));
    }

    @Test
    public void renderString_mysql_doubleQuoteUntouched_legitLiteralContent() {
        assertEquals("'a\"b'", DmlBatchLiterals.renderLiteral(DmlBatchRangeSpec.KEY_TYPE_STRING, "a\"b", false));
    }

    @Test
    public void renderString_pg_quoteDoublingONLY_backslashStaysLiteral() {
        // standard_conforming_strings=on (PG ≥ 9.1): doubling the backslash would CORRUPT the value
        assertEquals("'a''b'", DmlBatchLiterals.renderLiteral(DmlBatchRangeSpec.KEY_TYPE_STRING, "a'b", true));
        assertEquals("'a\\b'", DmlBatchLiterals.renderLiteral(DmlBatchRangeSpec.KEY_TYPE_STRING, "a\\b", true));
        assertEquals("'a\\''b'", DmlBatchLiterals.renderLiteral(DmlBatchRangeSpec.KEY_TYPE_STRING, "a\\'b", true));
        assertEquals("'a\"b'", DmlBatchLiterals.renderLiteral(DmlBatchRangeSpec.KEY_TYPE_STRING, "a\"b", true));
    }

    @Test
    public void renderString_plainAndUnicode_verbatimInsideQuotes() {
        assertEquals("'plain-key'", DmlBatchLiterals.renderLiteral(DmlBatchRangeSpec.KEY_TYPE_STRING, "plain-key", false));
        assertEquals("'中文键值'", DmlBatchLiterals.renderLiteral(DmlBatchRangeSpec.KEY_TYPE_STRING, "中文键值", true));
    }

    @Test
    public void renderString_nullValue_null_neverRendered() {
        assertNull(DmlBatchLiterals.renderLiteral(DmlBatchRangeSpec.KEY_TYPE_STRING, null, false));
        assertNull(DmlBatchLiterals.renderLiteral(DmlBatchRangeSpec.KEY_TYPE_STRING, "   ", true));
    }

    // ==================== unknown type / null type ====================

    @Test
    public void renderLiteral_unknownOrNullKeyType_null() {
        assertNull(DmlBatchLiterals.renderLiteral("FLOAT", "1.5", false));
        assertNull(DmlBatchLiterals.renderLiteral(null, "1", false));
    }

    // ==================== key-column whitelist (console side of the [\w$]+ contract) ====================

    @Test
    public void isValidKeyColumn_matrix() {
        assertTrue(DmlBatchLiterals.isValidKeyColumn("id"));
        assertTrue(DmlBatchLiterals.isValidKeyColumn("col_1"));
        assertTrue(DmlBatchLiterals.isValidKeyColumn("$special"));
        assertTrue(DmlBatchLiterals.isValidKeyColumn("Cnt"));
        assertFalse(DmlBatchLiterals.isValidKeyColumn(null));
        assertFalse(DmlBatchLiterals.isValidKeyColumn(""));
        assertFalse(DmlBatchLiterals.isValidKeyColumn("a b"));
        assertFalse(DmlBatchLiterals.isValidKeyColumn("a'b"));
        assertFalse(DmlBatchLiterals.isValidKeyColumn("a\"b"));
        assertFalse(DmlBatchLiterals.isValidKeyColumn("`a`"));
        assertFalse(DmlBatchLiterals.isValidKeyColumn("a.b"));
        assertFalse(DmlBatchLiterals.isValidKeyColumn("a;b"));
    }

    // ==================== probe-literal escapers (direct contract) ====================

    @Test
    public void escapeMySqlStringLiteral_contract() {
        assertEquals("'db'", DmlBatchLiterals.escapeMySqlStringLiteral("db"));
        assertEquals("'a\\\\b'", DmlBatchLiterals.escapeMySqlStringLiteral("a\\b"));
        assertEquals("'a''b'", DmlBatchLiterals.escapeMySqlStringLiteral("a'b"));
        // the ghost escapeMySqlStringLiteral contract verbatim (backslash doubling FIRST)
        assertEquals("'\\\\'''", DmlBatchLiterals.escapeMySqlStringLiteral("\\'"));
    }

    @Test
    public void escapePgStringLiteral_contract() {
        assertEquals("'db'", DmlBatchLiterals.escapePgStringLiteral("db"));
        assertEquals("'a\\b'", DmlBatchLiterals.escapePgStringLiteral("a\\b"));
        assertEquals("'a''b'", DmlBatchLiterals.escapePgStringLiteral("a'b"));
    }

    @Test
    public void isBlankCursor_firstBatchSentinel() {
        assertTrue(DmlBatchLiterals.isBlankCursor(null));
        assertTrue(DmlBatchLiterals.isBlankCursor(""));
        assertTrue(DmlBatchLiterals.isBlankCursor("  "));
        assertFalse(DmlBatchLiterals.isBlankCursor("0"));
    }
}
