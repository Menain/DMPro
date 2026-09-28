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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * Statement-shape matrix of {@link GhostAlterStmtChecker} — the console-side twin of the
 * plugin's GhostAlterTableParser contract (design 09-17-online-ddl-activation §2.5 方案②):
 * with/without db prefix, backticks, mixed case, multi-statement rejection, non-ALTER
 * rejection, comment rejection, exotic-identifier rejection, schema mismatch.
 */
public class GhostAlterStmtCheckerTest {

    private static final String SCHEMA = "db1";

    private static GhostAlterStmtChecker.AlterStmtTarget ok(String sql) {
        try {
            return GhostAlterStmtChecker.check(sql, SCHEMA);
        } catch (GhostAlterStmtChecker.AlterStmtReject e) {
            fail("expected acceptance, rejected with: " + e.getMessage());
            return null;
        }
    }

    private static String reject(String sql) {
        try {
            GhostAlterStmtChecker.check(sql, SCHEMA);
            fail("expected rejection but the statement was accepted: " + sql);
            return null;
        } catch (GhostAlterStmtChecker.AlterStmtReject e) {
            return e.getCode();
        }
    }

    @Test
    public void plainAlter_extractsTableAndDefaultsDatabaseToSchema() {
        GhostAlterStmtChecker.AlterStmtTarget target = ok("ALTER TABLE t_order ADD COLUMN extra VARCHAR(64)");
        assertEquals(SCHEMA, target.getDatabase());
        assertEquals("t_order", target.getTableName());
    }

    @Test
    public void dbPrefix_matchingSchema_isAccepted() {
        GhostAlterStmtChecker.AlterStmtTarget target = ok("ALTER TABLE db1.t_order ADD INDEX idx_a (a)");
        assertEquals("db1", target.getDatabase());
        assertEquals("t_order", target.getTableName());
    }

    @Test
    public void dbPrefix_mismatch_isSchemaMismatchCarryingDetail() {
        try {
            GhostAlterStmtChecker.check("ALTER TABLE other.t_order ADD COLUMN c INT", SCHEMA);
            fail("mismatching db prefix must be rejected");
        } catch (GhostAlterStmtChecker.AlterStmtReject e) {
            assertEquals(GhostAlterStmtChecker.CODE_SCHEMA_MISMATCH, e.getCode());
            // the detail names both sides (rendered into the GHOST_SCHEMA_MISMATCH {0} message)
            org.junit.Assert.assertTrue(e.getDetail().contains("other"));
            org.junit.Assert.assertTrue(e.getDetail().contains(SCHEMA));
        }
    }

    @Test
    public void backtickQuotedIdentifiers_areUnquoted() {
        GhostAlterStmtChecker.AlterStmtTarget target = ok("ALTER TABLE `db1`.`t_order` ADD COLUMN `c1` INT");
        assertEquals("db1", target.getDatabase());
        assertEquals("t_order", target.getTableName());
    }

    @Test
    public void mixedCaseAndExtraWhitespace_isAccepted() {
        GhostAlterStmtChecker.AlterStmtTarget target = ok("  alter   table   T_Order   add column c int  ");
        assertEquals("T_Order", target.getTableName());
    }

    @Test
    public void dollarAndWordChars_areLegalIdentifierForm() {
        assertEquals("t$2", ok("ALTER TABLE t$2 ADD COLUMN c INT").getTableName());
    }

    @Test
    public void singleTrailingSemicolon_isTolerated() {
        assertEquals("t_order", ok("ALTER TABLE t_order ADD COLUMN c INT;").getTableName());
    }

    @Test
    public void embeddedSemicolon_isMultiStatementRejection() {
        assertEquals(GhostAlterStmtChecker.CODE_STMT_UNSUPPORTED,
                reject("ALTER TABLE t_order ADD COLUMN c INT; DROP TABLE t_other"));
    }

    @Test
    public void nonAlterStatements_areRejected() {
        assertEquals(GhostAlterStmtChecker.CODE_STMT_UNSUPPORTED, reject("CREATE TABLE t_order (id INT)"));
        assertEquals(GhostAlterStmtChecker.CODE_STMT_UNSUPPORTED, reject("SELECT * FROM t_order"));
        assertEquals(GhostAlterStmtChecker.CODE_STMT_UNSUPPORTED, reject("UPDATE t_order SET a = 1"));
    }

    @Test
    public void sqlComments_areRejected() {
        assertEquals(GhostAlterStmtChecker.CODE_STMT_UNSUPPORTED, reject("ALTER TABLE t_order ADD COLUMN c INT -- x"));
        assertEquals(GhostAlterStmtChecker.CODE_STMT_UNSUPPORTED, reject("ALTER TABLE t_order ADD COLUMN c INT # x"));
        assertEquals(GhostAlterStmtChecker.CODE_STMT_UNSUPPORTED, reject("/* c */ ALTER TABLE t_order ADD COLUMN c INT"));
    }

    @Test
    public void exoticIdentifiers_areRejected() {
        // CJK, hyphens, brackets and quotes inside identifiers: 宁可误拒不可误放
        assertEquals(GhostAlterStmtChecker.CODE_STMT_UNSUPPORTED, reject("ALTER TABLE 表一 ADD COLUMN c INT"));
        assertEquals(GhostAlterStmtChecker.CODE_STMT_UNSUPPORTED, reject("ALTER TABLE t-1 ADD COLUMN c INT"));
        assertEquals(GhostAlterStmtChecker.CODE_STMT_UNSUPPORTED, reject("ALTER TABLE \"t1\" ADD COLUMN c INT"));
        assertEquals(GhostAlterStmtChecker.CODE_STMT_UNSUPPORTED, reject("ALTER TABLE [t1] ADD COLUMN c INT"));
        assertEquals(GhostAlterStmtChecker.CODE_STMT_UNSUPPORTED, reject("ALTER TABLE `t1 ADD COLUMN c INT"));
    }

    @Test
    public void bareWordAfterTable_flowsIntoAlterClause_pluginParity() {
        // "ALTER TABLE my table ..." parses as table=my + clause="table ..." — identical to the
        // plugin's GhostAlterTableParser (the clause goes to gh-ost --alter verbatim, which
        // rejects nonsense there); the console twin must not diverge from the final arbiter.
        GhostAlterStmtChecker.AlterStmtTarget target = ok("ALTER TABLE my table ADD COLUMN c INT");
        assertEquals("my", target.getTableName());
    }

    @Test
    public void missingAlterClause_isRejected() {
        assertEquals(GhostAlterStmtChecker.CODE_STMT_UNSUPPORTED, reject("ALTER TABLE t_order"));
        assertEquals(GhostAlterStmtChecker.CODE_STMT_UNSUPPORTED, reject("ALTER TABLE t_order   "));
    }

    @Test
    public void blankInputs_areRejected() {
        assertEquals(GhostAlterStmtChecker.CODE_STMT_UNSUPPORTED, reject("   "));
        try {
            GhostAlterStmtChecker.check("ALTER TABLE t_order ADD COLUMN c INT", " ");
            fail("blank schema must be rejected");
        } catch (GhostAlterStmtChecker.AlterStmtReject e) {
            assertEquals(GhostAlterStmtChecker.CODE_STMT_UNSUPPORTED, e.getCode());
        }
    }
}
