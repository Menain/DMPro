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
package com.clougence.clouddm.console.web.service.ghost;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * B6 M3 (M1 check 前瞻①): the segment-retry target-effect probe parser — the v1 pragmatic scope
 * rules ONLY the single-action {@code ADD [COLUMN] <ident> ...} shape decidable; every other form
 * returns null so the caller conservatively rebuilds (gh-ost's duplicate-object error is the safe
 * backstop). Strictness mirrors {@link GhostAlterStmtChecker} (宁可误拒不可误放): false negatives
 * cost a rebuild, a false positive would fake a SUCCESS — the matrix below pins both directions.
 */
public class GhostResumeProbeTest {

    private static final String DB = "pre_db";

    // ==================== decidable shapes ====================

    @Test
    public void parse_addColumnKeyword_decidable() {
        GhostResumeProbe.Target t = GhostResumeProbe.parseSingleAddColumn(
            "ALTER TABLE t_big ADD COLUMN c1 INT NOT NULL DEFAULT 0", DB);
        assertNotNull(t);
        assertEquals("t_big", t.getTable());
        assertEquals("c1", t.getColumn());
    }

    @Test
    public void parse_addWithoutColumnKeyword_decidable() {
        GhostResumeProbe.Target t = GhostResumeProbe.parseSingleAddColumn(
            "ALTER TABLE t_big ADD c1 INT", DB);
        assertNotNull(t);
        assertEquals("c1", t.getColumn());
    }

    @Test
    public void parse_backtickQuotedIdentifiers_decidable() {
        GhostResumeProbe.Target t = GhostResumeProbe.parseSingleAddColumn(
            "ALTER TABLE `t_big` ADD COLUMN `key` VARCHAR(64)", DB);
        assertNotNull(t);
        assertEquals("t_big", t.getTable());
        assertEquals("a backtick-quoted reserved word IS a column name", "key", t.getColumn());
    }

    @Test
    public void parse_dbPrefixMatchingSchema_decidable() {
        GhostResumeProbe.Target t = GhostResumeProbe.parseSingleAddColumn(
            "ALTER TABLE pre_db.t_big ADD COLUMN c1 INT", DB);
        assertNotNull(t);
        assertEquals("t_big", t.getTable());
        assertEquals("c1", t.getColumn());
    }

    @Test
    public void parse_trailingSemicolonAndWhitespace_tolerated() {
        GhostResumeProbe.Target t = GhostResumeProbe.parseSingleAddColumn(
            "  ALTER TABLE t_big ADD COLUMN c1 INT ;  ", DB);
        assertNotNull(t);
        assertEquals("c1", t.getColumn());
    }

    @Test
    public void parse_caseInsensitive_decidable() {
        GhostResumeProbe.Target t = GhostResumeProbe.parseSingleAddColumn(
            "alter table t_big add column c1 decimal(10,2)", DB);
        assertNotNull(t);
        assertEquals("c1", t.getColumn());
    }

    @Test
    public void parse_parenCommaAndQuotedComma_staySingleAction() {
        // DECIMAL(10,2) — the comma sits inside parens
        assertNotNull(GhostResumeProbe.parseSingleAddColumn(
            "ALTER TABLE t_big ADD COLUMN c1 DECIMAL(10,2)", DB));
        // a comma inside a string literal is not an action separator
        GhostResumeProbe.Target t = GhostResumeProbe.parseSingleAddColumn(
            "ALTER TABLE t_big ADD COLUMN c1 VARCHAR(32) DEFAULT 'a,b' COMMENT 'x,y'", DB);
        assertNotNull(t);
        assertEquals("c1", t.getColumn());
    }

    @Test
    public void parse_positionSuffixes_decidable() {
        assertNotNull(GhostResumeProbe.parseSingleAddColumn(
            "ALTER TABLE t_big ADD COLUMN c1 INT FIRST", DB));
        assertNotNull(GhostResumeProbe.parseSingleAddColumn(
            "ALTER TABLE t_big ADD COLUMN c1 INT AFTER id", DB));
    }

    // ==================== undecidable shapes (conservative rebuild) ====================

    @Test
    public void parse_multiAction_topLevelComma_undecidable() {
        assertNull(GhostResumeProbe.parseSingleAddColumn(
            "ALTER TABLE t_big ADD COLUMN c1 INT, ADD COLUMN c2 INT", DB));
        assertNull(GhostResumeProbe.parseSingleAddColumn(
            "ALTER TABLE t_big ADD COLUMN c1 INT, DROP COLUMN c0", DB));
    }

    @Test
    public void parse_parenthesizedMultiAdd_undecidable() {
        // MySQL 8's ADD COLUMN (a INT, b INT) form — the ident slot never matches
        assertNull(GhostResumeProbe.parseSingleAddColumn(
            "ALTER TABLE t_big ADD COLUMN (c1 INT, c2 INT)", DB));
        assertNull(GhostResumeProbe.parseSingleAddColumn(
            "ALTER TABLE t_big ADD (c1 INT)", DB));
    }

    @Test
    public void parse_nonAddActions_undecidable() {
        assertNull(GhostResumeProbe.parseSingleAddColumn("ALTER TABLE t_big MODIFY c1 BIGINT", DB));
        assertNull(GhostResumeProbe.parseSingleAddColumn("ALTER TABLE t_big CHANGE c1 c2 INT", DB));
        assertNull(GhostResumeProbe.parseSingleAddColumn("ALTER TABLE t_big DROP COLUMN c1", DB));
        assertNull(GhostResumeProbe.parseSingleAddColumn("ALTER TABLE t_big RENAME TO t_new", DB));
        assertNull(GhostResumeProbe.parseSingleAddColumn("ALTER TABLE t_big ENGINE=InnoDB", DB));
    }

    @Test
    public void parse_addGrammarKeywords_notColumns_undecidable() {
        assertNull(GhostResumeProbe.parseSingleAddColumn("ALTER TABLE t_big ADD PRIMARY KEY (id)", DB));
        assertNull(GhostResumeProbe.parseSingleAddColumn("ALTER TABLE t_big ADD UNIQUE KEY uk (c)", DB));
        assertNull(GhostResumeProbe.parseSingleAddColumn("ALTER TABLE t_big ADD INDEX idx_c (c)", DB));
        assertNull(GhostResumeProbe.parseSingleAddColumn("ALTER TABLE t_big ADD KEY idx_c (c)", DB));
        assertNull(GhostResumeProbe.parseSingleAddColumn(
            "ALTER TABLE t_big ADD CONSTRAINT fk FOREIGN KEY (a) REFERENCES t2 (id)", DB));
        assertNull(GhostResumeProbe.parseSingleAddColumn("ALTER TABLE t_big ADD FULLTEXT INDEX ft (c)", DB));
        assertNull(GhostResumeProbe.parseSingleAddColumn("ALTER TABLE t_big ADD CHECK (c1 > 0)", DB));
        assertNull(GhostResumeProbe.parseSingleAddColumn(
            "ALTER TABLE t_big ADD PARTITION (PARTITION p2 VALUES LESS THAN (2000))", DB));
        // MariaDB-only IF NOT EXISTS — undecidable in the MySQL v1 scope
        assertNull(GhostResumeProbe.parseSingleAddColumn(
            "ALTER TABLE t_big ADD COLUMN IF NOT EXISTS c1 INT", DB));
    }

    @Test
    public void parse_addWithoutTypeDefinition_undecidable() {
        // a lone ident after ADD leaves the column/type roles ambiguous — never probe it
        assertNull(GhostResumeProbe.parseSingleAddColumn("ALTER TABLE t_big ADD c1", DB));
        assertNull(GhostResumeProbe.parseSingleAddColumn("ALTER TABLE t_big ADD COLUMN c1", DB));
    }

    @Test
    public void parse_structuralRejections_undecidable() {
        assertNull(GhostResumeProbe.parseSingleAddColumn(null, DB));
        assertNull(GhostResumeProbe.parseSingleAddColumn("   ", DB));
        assertNull(GhostResumeProbe.parseSingleAddColumn("ALTER TABLE t_big ADD COLUMN c1 INT", null));
        // multi-statement
        assertNull(GhostResumeProbe.parseSingleAddColumn(
            "ALTER TABLE t_big ADD COLUMN c1 INT; ALTER TABLE t2 ADD COLUMN c2 INT", DB));
        // comments could hide extra actions
        assertNull(GhostResumeProbe.parseSingleAddColumn(
            "ALTER TABLE t_big ADD COLUMN c1 INT, -- x\n DROP COLUMN c0", DB));
        assertNull(GhostResumeProbe.parseSingleAddColumn(
            "ALTER TABLE t_big /* x */ ADD COLUMN c1 INT", DB));
        // not an ALTER TABLE at all
        assertNull(GhostResumeProbe.parseSingleAddColumn("CREATE TABLE t (id INT)", DB));
        // db prefix disagreeing with the group schema
        assertNull(GhostResumeProbe.parseSingleAddColumn(
            "ALTER TABLE other_db.t_big ADD COLUMN c1 INT", DB));
        // non-ASCII (CJK) identifiers fail the strict [\w$] form
        assertNull(GhostResumeProbe.parseSingleAddColumn("ALTER TABLE `表` ADD COLUMN `列` INT", DB));
    }

    // ==================== the top-level comma scanner ====================

    @Test
    public void hasTopLevelComma_quoteAndParenAware() {
        assertFalse(GhostResumeProbe.hasTopLevelComma("ADD COLUMN c1 DECIMAL(10,2)"));
        assertFalse(GhostResumeProbe.hasTopLevelComma("ADD COLUMN c1 VARCHAR(8) DEFAULT 'a,b'"));
        assertFalse(GhostResumeProbe.hasTopLevelComma("ADD COLUMN c1 VARCHAR(8) DEFAULT \"a,b\""));
        assertFalse(GhostResumeProbe.hasTopLevelComma("ADD COLUMN `a,b` INT"));
        assertTrue(GhostResumeProbe.hasTopLevelComma("ADD COLUMN c1 INT, ADD INDEX i (c1)"));
        assertFalse(GhostResumeProbe.hasTopLevelComma("ADD COLUMN c1 INT"));
        // an escaped quote inside the literal does not end the span
        assertFalse(GhostResumeProbe.hasTopLevelComma("ADD COLUMN c1 VARCHAR(8) DEFAULT 'a\\',b'"));
    }
}
