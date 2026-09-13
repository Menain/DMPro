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
package com.clougence.clouddm.console.web.component.governance;

import static org.junit.Assert.*;

import java.util.List;

import org.junit.Test;

/**
 * Matrix tests for {@link FullWidthSqlLint} scanner.
 * <p>
 * Covers: all full-width char types outside literals (hit), inside each
 * lexical context (pass), escape sequences, unclosed literals at EOF,
 * multi-line line/col counting (\n and \r\n), null/empty safety,
 * and the real incident SQL (exactly 1 hit).
 * <p>
 * Pure unit tests - no Spring context.
 */
public class FullWidthSqlLintTest {

    // ==================== hit cases (outside literal/comment) ====================

    @Test
    public void scan_fullWidthSemicolonOutsideString_hit() {
        List<FullWidthSqlLint.Hit> hits = FullWidthSqlLint.scan("SELECT * FROM t；");
        assertEquals(1, hits.size());
        FullWidthSqlLint.Hit h = hits.get(0);
        assertEquals("；", h.getCharacter());
        assertEquals(0xFF1B, h.getCodePoint());
        assertEquals(1, h.getLine());
        assertEquals(16, h.getCol());
    }

    @Test
    public void scan_fullWidthCommaOutsideString_hit() {
        List<FullWidthSqlLint.Hit> hits = FullWidthSqlLint.scan("SELECT a，b FROM t");
        assertEquals(1, hits.size());
        assertEquals("，", hits.get(0).getCharacter());
        assertEquals(0xFF0C, hits.get(0).getCodePoint());
    }

    @Test
    public void scan_fullWidthLeftParenOutsideString_hit() {
        List<FullWidthSqlLint.Hit> hits = FullWidthSqlLint.scan("SELECT （1）");
        assertEquals(2, hits.size());
        assertEquals("（", hits.get(0).getCharacter());
        assertEquals(0xFF08, hits.get(0).getCodePoint());
        assertEquals("）", hits.get(1).getCharacter());
        assertEquals(0xFF09, hits.get(1).getCodePoint());
    }

    @Test
    public void scan_fullWidthFullStopOutsideString_hit() {
        List<FullWidthSqlLint.Hit> hits = FullWidthSqlLint.scan("SELECT 1。");
        assertEquals(1, hits.size());
        assertEquals("。", hits.get(0).getCharacter());
        assertEquals(0x3002, hits.get(0).getCodePoint());
    }

    @Test
    public void scan_ideographicCommaOutsideString_hit() {
        List<FullWidthSqlLint.Hit> hits = FullWidthSqlLint.scan("SELECT a、b");
        assertEquals(1, hits.size());
        assertEquals("、", hits.get(0).getCharacter());
        assertEquals(0x3001, hits.get(0).getCodePoint());
    }

    @Test
    public void scan_fullWidthSpaceOutsideString_hit() {
        // U+3000 ideographic space
        List<FullWidthSqlLint.Hit> hits = FullWidthSqlLint.scan("SELECT　* FROM t");
        assertEquals(1, hits.size());
        assertEquals(0x3000, hits.get(0).getCodePoint());
        assertEquals(7, hits.get(0).getCol());
    }

    @Test
    public void scan_curlySingleQuoteOutsideString_hit() {
        // U+2018 left single quotation mark
        List<FullWidthSqlLint.Hit> hits = FullWidthSqlLint.scan("SELECT ‘x’ FROM t");
        assertEquals(2, hits.size());
        assertEquals(0x2018, hits.get(0).getCodePoint());
        assertEquals(0x2019, hits.get(1).getCodePoint());
    }

    @Test
    public void scan_curlyDoubleQuoteOutsideString_hit() {
        // U+201C left double quotation mark, U+201D right
        List<FullWidthSqlLint.Hit> hits = FullWidthSqlLint.scan("SELECT “x” FROM t");
        assertEquals(2, hits.size());
        assertEquals(0x201C, hits.get(0).getCodePoint());
        assertEquals(0x201D, hits.get(1).getCodePoint());
    }

    @Test
    public void scan_fullWidthExclamationOutsideString_hit() {
        // U+FF01 full-width exclamation
        List<FullWidthSqlLint.Hit> hits = FullWidthSqlLint.scan("SELECT 1！");
        assertEquals(1, hits.size());
        assertEquals(0xFF01, hits.get(0).getCodePoint());
    }

    // ==================== pass cases (inside literal/comment) ====================

    @Test
    public void scan_fullWidthInsideSingleQuoteString_pass() {
        List<FullWidthSqlLint.Hit> hits = FullWidthSqlLint.scan("INSERT INTO t VALUES ('hello；world，test')");
        assertEquals(0, hits.size());
    }

    @Test
    public void scan_fullWidthInsideDoubleQuoteString_pass() {
        List<FullWidthSqlLint.Hit> hits = FullWidthSqlLint.scan("INSERT INTO t VALUES (\"hello；world\")");
        assertEquals(0, hits.size());
    }

    @Test
    public void scan_fullWidthInsideBacktickIdentifier_pass() {
        List<FullWidthSqlLint.Hit> hits = FullWidthSqlLint.scan("SELECT * FROM `订单；表`");
        assertEquals(0, hits.size());
    }

    @Test
    public void scan_fullWidthInsideDoubleDashComment_pass() {
        List<FullWidthSqlLint.Hit> hits = FullWidthSqlLint.scan("SELECT * FROM t -- this is；a comment\n");
        assertEquals(0, hits.size());
    }

    @Test
    public void scan_fullWidthInsideHashComment_pass() {
        List<FullWidthSqlLint.Hit> hits = FullWidthSqlLint.scan("SELECT * FROM t # comment；here\n");
        assertEquals(0, hits.size());
    }

    @Test
    public void scan_fullWidthInsideBlockComment_pass() {
        List<FullWidthSqlLint.Hit> hits = FullWidthSqlLint.scan("SELECT /* comment；here */ * FROM t");
        assertEquals(0, hits.size());
    }

    // ==================== escape sequence cases ====================

    @Test
    public void scan_backslashEscapeDoesNotEndString_pass() {
        // 'it\'s；' — the \' escapes the quote, full-width ； stays inside the string
        List<FullWidthSqlLint.Hit> hits = FullWidthSqlLint.scan("INSERT INTO t VALUES ('it\\'s；')");
        assertEquals(0, hits.size());
    }

    @Test
    public void scan_doubleQuoteEscapeDoesNotEndString_pass() {
        // 'a''b；' — the '' escapes the quote, full-width ； stays inside the string
        List<FullWidthSqlLint.Hit> hits = FullWidthSqlLint.scan("INSERT INTO t VALUES ('a''b；')");
        assertEquals(0, hits.size());
    }

    // ==================== edge tolerance (fail-open) ====================

    @Test
    public void scan_unclosedSingleQuoteAtEof_pass() {
        // Unclosed string at EOF - rest treated as inside literal, no hit
        List<FullWidthSqlLint.Hit> hits = FullWidthSqlLint.scan("SELECT 'unterminated；string");
        assertEquals(0, hits.size());
    }

    @Test
    public void scan_unclosedBlockCommentAtEof_pass() {
        List<FullWidthSqlLint.Hit> hits = FullWidthSqlLint.scan("SELECT /* comment；without end");
        assertEquals(0, hits.size());
    }

    // ==================== multi-line line/col counting ====================

    @Test
    public void scan_multiLineNewlineCounting_hit() {
        // \n line endings: full-width ； on line 3
        String sql = "SELECT *\nFROM t\nWHERE x；";
        List<FullWidthSqlLint.Hit> hits = FullWidthSqlLint.scan(sql);
        assertEquals(1, hits.size());
        assertEquals(3, hits.get(0).getLine());
        assertEquals(8, hits.get(0).getCol());
    }

    @Test
    public void scan_crlfLineEndings_hit() {
        // \r\n line endings: full-width ； on line 2
        String sql = "SELECT *\r\nFROM t；";
        List<FullWidthSqlLint.Hit> hits = FullWidthSqlLint.scan(sql);
        assertEquals(1, hits.size());
        assertEquals(2, hits.get(0).getLine());
        assertEquals(7, hits.get(0).getCol());
    }

    // ==================== empty / null safety ====================

    @Test
    public void scan_nullInput_returnsEmpty() {
        List<FullWidthSqlLint.Hit> hits = FullWidthSqlLint.scan(null);
        assertNotNull(hits);
        assertEquals(0, hits.size());
    }

    @Test
    public void scan_emptyString_returnsEmpty() {
        List<FullWidthSqlLint.Hit> hits = FullWidthSqlLint.scan("");
        assertNotNull(hits);
        assertEquals(0, hits.size());
    }

    @Test
    public void scan_pureAsciiSql_returnsEmpty() {
        List<FullWidthSqlLint.Hit> hits = FullWidthSqlLint.scan("SELECT * FROM `table` WHERE id = 1;");
        assertEquals(0, hits.size());
    }

    // ==================== multiple hits ====================

    @Test
    public void scan_multipleHitsAllReturned() {
        // 6 full-width chars outside literals - scan returns all 6
        String sql = "SELECT；a，b（c）d。e！";
        List<FullWidthSqlLint.Hit> hits = FullWidthSqlLint.scan(sql);
        assertEquals(6, hits.size());
    }

    @Test
    public void scan_moreThanFiveHitsAllReturned() {
        // 7 full-width semicolons - scan returns all 7 (caller caps at 5 for message)
        String sql = "SELECT；；；；；；；1";
        List<FullWidthSqlLint.Hit> hits = FullWidthSqlLint.scan(sql);
        assertEquals(7, hits.size());
    }

    // ==================== mixed scenario ====================

    @Test
    public void scan_mixedBacktickAndStringNoHit_pass() {
        // Chinese table name in backtick + full-width chars inside string literal
        String sql = "INSERT INTO `订单` (name) VALUES ('test；value，here')";
        List<FullWidthSqlLint.Hit> hits = FullWidthSqlLint.scan(sql);
        assertEquals(0, hits.size());
    }

    @Test
    public void scan_mixedHitInsideAndOutsideLiterals_hit() {
        // Full-width ； inside string (pass) + full-width ， outside (hit)
        String sql = "INSERT INTO t VALUES ('hello；')，";
        List<FullWidthSqlLint.Hit> hits = FullWidthSqlLint.scan(sql);
        assertEquals(1, hits.size());
        assertEquals("，", hits.get(0).getCharacter());
    }

    // ==================== real incident SQL ====================

    @Test
    public void scan_realIncidentSql_exactlyOneHit() {
        // The actual incident SQL: full-width ； at the end of line 3
        String sql = "CREATE TABLE `bebe` (\n  `c1` tinyint DEFAULT NULL\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci；";
        List<FullWidthSqlLint.Hit> hits = FullWidthSqlLint.scan(sql);
        assertEquals("Expected exactly 1 hit", 1, hits.size());
        FullWidthSqlLint.Hit h = hits.get(0);
        assertEquals("；", h.getCharacter());
        assertEquals(0xFF1B, h.getCodePoint());
        assertEquals(3, h.getLine());
        assertEquals(67, h.getCol());
    }

    // ==================== formatHit ====================

    @Test
    public void formatHit_containsAllRequiredInfo() {
        FullWidthSqlLint.Hit hit = new FullWidthSqlLint.Hit(3, 67, "；", 0xFF1B);
        String msg = FullWidthSqlLint.formatHit(hit);
        assertTrue("should contain character", msg.contains("；"));
        assertTrue("should contain code point", msg.contains("U+FF1B"));
        assertTrue("should contain line", msg.contains("line 3"));
        assertTrue("should contain col", msg.contains("col 67"));
        assertTrue("should contain ASCII suggestion", msg.contains("';'"));
        assertTrue("should mention outside literal/comment", msg.contains("outside string literal/comment"));
    }

    @Test
    public void formatHit_curlyQuoteSuggestion() {
        FullWidthSqlLint.Hit hit = new FullWidthSqlLint.Hit(1, 5, "‘", 0x2018);
        String msg = FullWidthSqlLint.formatHit(hit);
        assertTrue(msg.contains("U+2018"));
        assertTrue(msg.contains("'''")); // 'char' + 'replacement'
    }

    @Test
    public void formatHit_ideographicSpaceSuggestion() {
        FullWidthSqlLint.Hit hit = new FullWidthSqlLint.Hit(1, 5, "　", 0x3000);
        String msg = FullWidthSqlLint.formatHit(hit);
        assertTrue(msg.contains("U+3000"));
        assertTrue(msg.contains("replace with ASCII ' '"));
    }

    // ==================== isFullWidth ====================

    @Test
    public void isFullWidth_boundaryValues() {
        assertTrue(FullWidthSqlLint.isFullWidth(0xFF01));  // first full-width
        assertTrue(FullWidthSqlLint.isFullWidth(0xFF5E));  // last full-width
        assertFalse(FullWidthSqlLint.isFullWidth(0xFF00)); // before range
        assertFalse(FullWidthSqlLint.isFullWidth(0xFF5F)); // after range
        assertTrue(FullWidthSqlLint.isFullWidth(0x3000));  // ideographic space
        assertTrue(FullWidthSqlLint.isFullWidth(0x3001));  // ideographic comma
        assertTrue(FullWidthSqlLint.isFullWidth(0x3002));  // ideographic full stop
        assertTrue(FullWidthSqlLint.isFullWidth(0x2018));  // curly quote start
        assertTrue(FullWidthSqlLint.isFullWidth(0x201F));  // curly quote end
        assertFalse(FullWidthSqlLint.isFullWidth(0x2017)); // before curly range
        assertFalse(FullWidthSqlLint.isFullWidth(0x2020)); // after curly range
        assertFalse(FullWidthSqlLint.isFullWidth(';'));    // ASCII semicolon
        assertFalse(FullWidthSqlLint.isFullWidth(' '));    // ASCII space
    }

    @Test
    public void suggestAscii_fullWidthSemicolon() {
        assertEquals(";", FullWidthSqlLint.suggestAscii(0xFF1B));
    }

    @Test
    public void suggestAscii_fullWidthComma() {
        assertEquals(",", FullWidthSqlLint.suggestAscii(0xFF0C));
    }

    @Test
    public void suggestAscii_ideographicSpace() {
        assertEquals(" ", FullWidthSqlLint.suggestAscii(0x3000));
    }

    @Test
    public void suggestAscii_curlySingleQuote() {
        assertEquals("'", FullWidthSqlLint.suggestAscii(0x2018));
        assertEquals("'", FullWidthSqlLint.suggestAscii(0x2019));
    }

    @Test
    public void suggestAscii_curlyDoubleQuote() {
        assertEquals("\"", FullWidthSqlLint.suggestAscii(0x201C));
        assertEquals("\"", FullWidthSqlLint.suggestAscii(0x201D));
    }
}
