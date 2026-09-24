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
package com.clougence.clouddm.onlineddl.ghost;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * SQL parse matrix of the strict regex parser (design §2.5 方案②): db prefix with/without,
 * backticks, mixed case, multi-statement reject, non-ALTER reject, exotic identifier reject,
 * db-prefix/schema mismatch reject.
 */
public class GhostAlterTableParserTest {

    private static GhostAlterTableParser.AlterTarget parse(String sql) {
        return GhostAlterTableParser.parse(sql, "linshiceshi");
    }

    private static void assertRejected(String code, String sql) {
        try {
            parse(sql);
            fail("must be rejected: " + sql);
        } catch (GhostToolException e) {
            assertEquals(code, e.getCode());
        }
    }

    // ------------------------------------------------------------------ accepted shapes

    @Test
    public void plainAlterWithoutDbPrefix() {
        GhostAlterTableParser.AlterTarget target = parse("ALTER TABLE t_spike ADD COLUMN extra INT");

        assertEquals("linshiceshi", target.getDatabase());
        assertEquals("t_spike", target.getTableName());
        assertEquals("ADD COLUMN extra INT", target.getAlterClause());
    }

    @Test
    public void trailingSemicolonIsTolerated() {
        GhostAlterTableParser.AlterTarget target = parse("ALTER TABLE t_spike ADD COLUMN extra INT;");

        assertEquals("ADD COLUMN extra INT", target.getAlterClause());
    }

    @Test
    public void matchingDbPrefixIsAccepted() {
        GhostAlterTableParser.AlterTarget target = parse("ALTER TABLE linshiceshi.t_spike ADD COLUMN extra INT");

        assertEquals("linshiceshi", target.getDatabase());
        assertEquals("t_spike", target.getTableName());
        assertEquals("ADD COLUMN extra INT", target.getAlterClause());
    }

    @Test
    public void backtickQuotedIdentifiers() {
        GhostAlterTableParser.AlterTarget target = parse("ALTER TABLE `linshiceshi`.`t_spike` ADD COLUMN `extra` INT");

        assertEquals("linshiceshi", target.getDatabase());
        assertEquals("t_spike", target.getTableName());
        assertEquals("ADD COLUMN `extra` INT", target.getAlterClause());
    }

    @Test
    public void mixedCaseKeywords() {
        GhostAlterTableParser.AlterTarget target = parse("alter table T_Spike add column extra int");

        assertEquals("T_Spike", target.getTableName());
        assertEquals("add column extra int", target.getAlterClause());
    }

    @Test
    public void dollarAndUnderscoreIdentifiers() {
        GhostAlterTableParser.AlterTarget target = parse("ALTER TABLE t_$1 ADD COLUMN c_2 INT");

        assertEquals("t_$1", target.getTableName());
    }

    @Test
    public void multilineAlterClauseIsPreserved() {
        GhostAlterTableParser.AlterTarget target = parse("ALTER TABLE t_spike\n  ADD COLUMN extra INT,\n  ADD INDEX idx_extra (extra)");

        assertEquals("ADD COLUMN extra INT,\n  ADD INDEX idx_extra (extra)", target.getAlterClause());
    }

    @Test
    public void extraWhitespaceAroundKeywords() {
        GhostAlterTableParser.AlterTarget target = parse("  ALTER   TABLE   t_spike    ENGINE = InnoDB  ");

        assertEquals("t_spike", target.getTableName());
        assertEquals("ENGINE = InnoDB", target.getAlterClause());
    }

    // ------------------------------------------------------------------ rejected shapes

    @Test
    public void multiStatementRejected() {
        assertRejected(GhostToolException.CODE_STMT_UNSUPPORTED,
                "ALTER TABLE t1 ADD COLUMN a INT; ALTER TABLE t2 ADD COLUMN b INT");
        assertRejected(GhostToolException.CODE_STMT_UNSUPPORTED,
                "ALTER TABLE t1 ADD COLUMN a INT; DELETE FROM t1");
        assertRejected(GhostToolException.CODE_STMT_UNSUPPORTED,
                "ALTER TABLE t1 ADD COLUMN a INT;;");
    }

    @Test
    public void nonAlterRejected() {
        assertRejected(GhostToolException.CODE_STMT_UNSUPPORTED, "CREATE TABLE t1 (id INT)");
        assertRejected(GhostToolException.CODE_STMT_UNSUPPORTED, "UPDATE t1 SET a = 1");
        assertRejected(GhostToolException.CODE_STMT_UNSUPPORTED, "DROP TABLE t1");
        // MySQL's ALTER ONLINE TABLE variant stays rejected (宁可误拒不可误放)
        assertRejected(GhostToolException.CODE_STMT_UNSUPPORTED, "ALTER ONLINE TABLE t1 ADD COLUMN a INT");
    }

    @Test
    public void emptyAlterClauseRejected() {
        assertRejected(GhostToolException.CODE_STMT_UNSUPPORTED, "ALTER TABLE t_spike");
        assertRejected(GhostToolException.CODE_STMT_UNSUPPORTED, "ALTER TABLE t_spike   ");
    }

    @Test
    public void exoticIdentifiersRejected() {
        assertRejected(GhostToolException.CODE_STMT_UNSUPPORTED, "ALTER TABLE [t1] ADD COLUMN a INT");
        assertRejected(GhostToolException.CODE_STMT_UNSUPPORTED, "ALTER TABLE \"t1\" ADD COLUMN a INT");
        assertRejected(GhostToolException.CODE_STMT_UNSUPPORTED, "ALTER TABLE `t 1` ADD COLUMN a INT");
        assertRejected(GhostToolException.CODE_STMT_UNSUPPORTED, "ALTER TABLE 表名 ADD COLUMN a INT");
        assertRejected(GhostToolException.CODE_STMT_UNSUPPORTED, "ALTER TABLE t1; DROP TABLE t2 ADD COLUMN a INT");
    }

    @Test
    public void commentsRejected() {
        assertRejected(GhostToolException.CODE_STMT_UNSUPPORTED, "ALTER TABLE t1 ADD COLUMN a INT -- comment");
        assertRejected(GhostToolException.CODE_STMT_UNSUPPORTED, "/* lead */ ALTER TABLE t1 ADD COLUMN a INT");
        assertRejected(GhostToolException.CODE_STMT_UNSUPPORTED, "# ALTER TABLE t1 ADD COLUMN a INT");
    }

    @Test
    public void dbPrefixMismatchRejected() {
        assertRejected(GhostToolException.CODE_SCHEMA_MISMATCH, "ALTER TABLE other_db.t_spike ADD COLUMN extra INT");
        // exact match is required (MySQL table/db names are case-sensitive on Linux defaults)
        assertRejected(GhostToolException.CODE_SCHEMA_MISMATCH, "ALTER TABLE LinShiCeShi.t_spike ADD COLUMN extra INT");
    }

    @Test
    public void blankInputsRejected() {
        assertRejected(GhostToolException.CODE_BAD_LAUNCH_CONFIG, "");
        assertRejected(GhostToolException.CODE_BAD_LAUNCH_CONFIG, "   ");
        try {
            GhostAlterTableParser.parse("ALTER TABLE t1 ADD COLUMN a INT", " ");
            fail("blank schema must be rejected");
        } catch (GhostToolException e) {
            assertEquals(GhostToolException.CODE_BAD_LAUNCH_CONFIG, e.getCode());
        }
    }

    @Test
    public void exceptionMessageCarriesCodePrefix() {
        // the console (S4) translates by the message prefix — contract of GhostToolException
        try {
            parse("CREATE TABLE t1 (id INT)");
            fail();
        } catch (GhostToolException e) {
            assertEquals(GhostToolException.CODE_STMT_UNSUPPORTED, e.getCode());
            org.junit.Assert.assertTrue(e.getMessage().startsWith(GhostToolException.CODE_STMT_UNSUPPORTED + ": "));
        }
    }
}
