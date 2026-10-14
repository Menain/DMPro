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

import org.junit.Test;

/**
 * Branch-coverage tests for {@link RollbackSqlGenerator}.
 * <p>
 * Matrix (design D7):
 * <ul>
 * <li>UPDATE with WHERE → CREATE TABLE _bak AS SELECT ... WHERE ...</li>
 * <li>DELETE with WHERE → CREATE TABLE _bak AS SELECT ... WHERE ...</li>
 * <li>UPDATE without WHERE → full-table backup warning</li>
 * <li>DELETE multi-table JOIN → full-table backup warning</li>
 * <li>INSERT → comment template (PK warning)</li>
 * <li>DROP TABLE → irreversible warning</li>
 * <li>TRUNCATE → irreversible warning</li>
 * <li>ALTER TABLE → warning template</li>
 * <li>Multi-statement → per-statement segmentation</li>
 * <li>Qualified table name (db.schema.table) → last component extracted</li>
 * <li>Blank input → null</li>
 * <li>Malformed/garbage → warning text, never throws</li>
 * </ul>
 */
public class RollbackSqlGeneratorTest {

    private final RollbackSqlGenerator generator = new RollbackSqlGenerator();

    // ==================== UPDATE / DELETE with WHERE ====================

    @Test
    public void update_withWhere_generatesBackupTableWithCondition() {
        String result = generator.generate("UPDATE orders SET status=1 WHERE id=42;");
        assertNotNull(result);
        assertTrue("header comment present", result.contains("-- 系统自动生成"));
        assertTrue("backup table name", result.contains("`orders_bak_"));
        assertTrue("SELECT * FROM", result.contains("SELECT * FROM `orders`"));
        assertTrue("WHERE condition preserved", result.contains("WHERE id=42"));
    }

    @Test
    public void delete_withWhere_generatesBackupTableWithCondition() {
        String result = generator.generate("DELETE FROM logs WHERE created_at < '2025-01-01';");
        assertNotNull(result);
        assertTrue(result.contains("`logs_bak_"));
        assertTrue(result.contains("WHERE created_at < '2025-01-01'"));
    }

    // ==================== UPDATE / DELETE without WHERE / multi-table ====================

    @Test
    public void update_withoutWhere_generatesFullTableBackupWarning() {
        String result = generator.generate("UPDATE config SET value='test';");
        assertNotNull(result);
        assertTrue("warning text", result.contains("警"));
        assertTrue("full-table backup", result.contains("SELECT * FROM `config`"));
        // Should NOT have a WHERE clause
        assertFalse(result.contains("WHERE value"));
    }

    @Test
    public void delete_multiTableJoin_degradesToFullTableBackup() {
        // MySQL multi-table DELETE syntax (DELETE t1 FROM ...) does not match the simple
        // DELETE FROM regex → table extraction fails → warning text. This is correct degradation.
        String result = generator.generate(
            "DELETE t1 FROM orders t1 JOIN order_items t2 ON t1.id=t2.order_id WHERE t1.status=0;");
        assertNotNull(result);
        assertTrue("table extraction failure warning", result.contains("无法提取表名"));
    }

    @Test
    public void update_multiTableJoin_degradesToFullTableBackup() {
        String result = generator.generate(
            "UPDATE t1 JOIN t2 ON t1.id=t2.id SET t1.val=1 WHERE t1.status=0;");
        assertNotNull(result);
        assertTrue("multi-table warning", result.contains("警"));
    }

    // ==================== INSERT ====================

    @Test
    public void insert_generatesCommentTemplateWithPKWarning() {
        String result = generator.generate("INSERT INTO users (id, name) VALUES (1, 'test');");
        assertNotNull(result);
        assertTrue("PK warning", result.contains("主键"));
        assertTrue("DELETE suggestion", result.contains("DELETE FROM `users`"));
    }

    // ==================== DDL ====================

    @Test
    public void dropTable_generatesIrreversibleWarning() {
        String result = generator.generate("DROP TABLE old_data;");
        assertNotNull(result);
        assertTrue("irreversible warning", result.contains("不可逆"));
        assertTrue("table name in comment", result.contains("`old_data`"));
    }

    @Test
    public void truncate_generatesIrreversibleWarning() {
        String result = generator.generate("TRUNCATE TABLE temp_logs;");
        assertNotNull(result);
        assertTrue("irreversible warning", result.contains("不可逆"));
    }

    @Test
    public void alterTable_generatesWarningTemplate() {
        String result = generator.generate("ALTER TABLE users ADD COLUMN age INT;");
        assertNotNull(result);
        assertTrue("ALTER warning", result.contains("ALTER"));
        assertTrue("table name", result.contains("`users`"));
    }

    // ==================== Multi-statement ====================

    @Test
    public void multiStatement_generatesPerStatementSegments() {
        String sql = "UPDATE t1 SET a=1 WHERE id=1; DELETE FROM t2 WHERE id=2; DROP TABLE t3;";
        String result = generator.generate(sql);
        assertNotNull(result);
        assertTrue("header", result.contains("-- 系统自动生成"));
        // Each statement should produce its own rollback segment
        assertTrue("UPDATE backup", result.contains("`t1_bak_"));
        assertTrue("DELETE backup", result.contains("`t2_bak_"));
        assertTrue("DROP warning", result.contains("不可逆"));
        assertTrue("DROP table name", result.contains("`t3`"));
    }

    // ==================== Qualified table name ====================

    @Test
    public void qualifiedTableName_extractsLastComponent() {
        String result = generator.generate("UPDATE mydb.myschema.orders SET status=1 WHERE id=1;");
        assertNotNull(result);
        assertTrue("last component extracted", result.contains("`orders_bak_"));
    }

    // ==================== Edge cases ====================

    @Test
    public void blankInput_returnsNull() {
        assertNull(generator.generate(null));
        assertNull(generator.generate(""));
        assertNull(generator.generate("   "));
    }

    @Test
    public void garbageInput_neverThrows() {
        // Pure garbage — should produce warning text, not throw
        String result = generator.generate("@#$%^&*()");
        assertNotNull(result);
        assertTrue("header present", result.contains("-- 系统自动生成"));
    }

    @Test
    public void unknownStatementType_generatesGenericTemplate() {
        String result = generator.generate("GRANT SELECT ON db.* TO 'user'@'%';");
        assertNotNull(result);
        assertTrue("generic warning", result.contains("人工"));
    }

    @Test
    public void emptyStatementsBetweenSemicolons_areSkipped() {
        String result = generator.generate(";;UPDATE t SET a=1 WHERE id=1;;");
        assertNotNull(result);
        assertTrue("non-empty statement processed", result.contains("`t_bak_"));
    }

    // ==================== Header + structure ====================

    @Test
    public void result_alwaysStartsWithHeaderComment() {
        String[] inputs = {
            "UPDATE t SET a=1 WHERE id=1;",
            "DROP TABLE t;",
            "INSERT INTO t VALUES(1);",
            "GRANT SELECT ON t TO u;"
        };
        for (String input : inputs) {
            String result = generator.generate(input);
            assertNotNull("input: " + input, result);
            assertTrue("header first for: " + input,
                result.startsWith("-- 系统自动生成"));
        }
    }

    // ==================================================================================
    // C10 (task 10-14-recovery-type-grading, design D2) — dialect-aware + typed + per-group.
    // RED LINE 1: every test above this banner is a pre-C10 pin and stays BYTE-UNTOUCHED;
    // the MySQL/default rendering below is pinned byte-identical to the legacy one-arg shape.
    // ==================================================================================

    private static final com.clougence.clouddm.base.metadata.ds.DataSourceType PG
        = com.clougence.clouddm.base.metadata.ds.DataSourceType.PostgreSQL;
    private static final com.clougence.clouddm.base.metadata.ds.DataSourceType MY
        = com.clougence.clouddm.base.metadata.ds.DataSourceType.MySQL;

    // ==================== C10: the byte-identity red line ====================

    @Test
    public void c10_explicitMysql_byteIdenticalToLegacySignature() {
        // timestamp-free statements → deterministic byte comparison (no _bak_ minute flakiness)
        for (String sql : new String[] {
                "DROP TABLE old_data;",
                "GRANT SELECT ON db.* TO 'user'@'%';",
                "ALTER TABLE users ADD COLUMN age INT;",
                "@#$%^&*()" }) {
            assertEquals("MySQL arm must stay byte-identical: " + sql,
                generator.generate(sql), generator.generate(sql, MY));
            assertEquals("null dialect = MySQL default: " + sql,
                generator.generate(sql), generator.generate(sql, null));
        }
    }

    @Test
    public void c10_pgFamilyVariantsKeepMysqlShape_conservativeExactMatch() {
        // the DmlBatchServiceImpl exact-match precedent: only DataSourceType.PostgreSQL flips the
        // dialect; PG-family variants keep the status-quo shape (documented conservatism)
        String sql = "DROP TABLE t;";
        assertEquals(generator.generate(sql),
            generator.generate(sql, com.clougence.clouddm.base.metadata.ds.DataSourceType.PolarDBPg));
    }

    // ==================== C10: the PG arm ====================

    @Test
    public void c10_pg_updateWithWhere_doubleQuotedIdentifiers_noBackticks() {
        String result = generator.generate("UPDATE orders SET status=1 WHERE id=42;", PG);
        assertNotNull(result);
        assertTrue("header comment present", result.contains("-- 系统自动生成"));
        assertTrue("PG double-quoted backup table", result.contains("\"orders_bak_"));
        assertTrue("PG double-quoted source table", result.contains("SELECT * FROM \"orders\""));
        assertTrue("WHERE condition preserved", result.contains("WHERE id=42"));
        assertFalse("no MySQL backticks anywhere", result.contains("`"));
    }

    @Test
    public void c10_pg_insertAndDdlTemplates_doubleQuotedTables() {
        String insert = generator.generate("INSERT INTO users (id) VALUES (1);", PG);
        assertTrue(insert.contains("DELETE FROM \"users\""));
        assertFalse(insert.contains("`"));

        String drop = generator.generate("DROP TABLE old_data;", PG);
        assertTrue("irreversible warning kept (zh copy is dialect-neutral)", drop.contains("不可逆"));
        assertTrue(drop.contains("\"old_data\""));
        assertFalse(drop.contains("`"));
    }

    // ==================== C10: typed return (RollbackGenResult) ====================

    @Test
    public void c10_typed_perStmtClassification() {
        RollbackGenResult r = generator.generateTyped(
            "UPDATE t SET a=1 WHERE id=1; INSERT INTO t VALUES(1); DROP TABLE x; ALTER TABLE y ADD COLUMN c INT; GRANT SELECT ON z TO u;",
            null);
        assertNotNull(r.getText());
        assertEquals(5, r.getPerStmtTypes().size());
        assertEquals(com.clougence.clouddm.platform.dal.model.approval.RecoveryType.BACKUP_TEMPLATE,
            r.getPerStmtTypes().get(0).getRecovery());
        assertEquals("t", r.getPerStmtTypes().get(0).getTable());
        // INSERT: comment-only advice — no effective auto recovery artifact (honest UNSPECIFIED)
        assertEquals(com.clougence.clouddm.platform.dal.model.approval.RecoveryType.UNSPECIFIED,
            r.getPerStmtTypes().get(1).getRecovery());
        assertEquals(com.clougence.clouddm.platform.dal.model.approval.RecoveryType.IRREVERSIBLE,
            r.getPerStmtTypes().get(2).getRecovery());
        assertEquals(com.clougence.clouddm.platform.dal.model.approval.RecoveryType.UNSPECIFIED,
            r.getPerStmtTypes().get(3).getRecovery());
        assertEquals(com.clougence.clouddm.platform.dal.model.approval.RecoveryType.UNSPECIFIED,
            r.getPerStmtTypes().get(4).getRecovery());
        for (int i = 0; i < 5; i++) {
            assertEquals(i, r.getPerStmtTypes().get(i).getIndex());
            assertEquals(0, r.getPerStmtTypes().get(i).getGroupOrdinal());
            assertNotNull(r.getPerStmtTypes().get(i).getNote());
        }
    }

    @Test
    public void c10_typed_blankInput_nullTextEmptyCompanion() {
        RollbackGenResult r = generator.generateTyped("   ", MY);
        assertNull(r.getText());
        assertTrue(r.getPerStmtTypes().isEmpty());
        assertNull(generator.generate(null, PG));
    }

    @Test
    public void c10_typed_textLegEqualsStringSignature() {
        assertEquals(generator.generate("DROP TABLE t;", PG),
            generator.generateTyped("DROP TABLE t;", PG).getText());
    }

    // ==================== C10: per-group combined generation ====================

    @Test
    public void c10_combined_singleGroup_degeneratesByteIdenticalNoHeader() {
        // the identity pin (design D2): combinedSql of ONE group IS that group's SQL — the
        // single-group combined output equals the legacy generate output byte-for-byte, header-free
        for (String sql : new String[] { "DROP TABLE old_data;", "GRANT SELECT ON t TO u;" }) {
            RollbackGenResult r = generator.generateCombined(java.util.List.of(
                new RollbackSqlGenerator.GroupInput(sql, MY, "db1 (MySQL)")));
            assertEquals(generator.generate(sql), r.getText());
            assertFalse("no group header in the single-group shape", r.getText().contains("目标库"));
        }
    }

    @Test
    public void c10_combined_multiGroup_headersAndPerGroupDialects() {
        RollbackGenResult r = generator.generateCombined(java.util.List.of(
            new RollbackSqlGenerator.GroupInput("UPDATE t1 SET a=1 WHERE id=1;", MY, "db1 (MySQL)"),
            new RollbackSqlGenerator.GroupInput("UPDATE t2 SET a=1 WHERE id=1;", PG, "db2 (PostgreSQL)")));
        String text = r.getText();
        assertNotNull(text);
        assertTrue("group 1 header", text.contains("-- 目标库[1/2]: db1 (MySQL)"));
        assertTrue("group 2 header", text.contains("-- 目标库[2/2]: db2 (PostgreSQL)"));
        assertTrue("mysql section backticked", text.contains("`t1_bak_"));
        assertTrue("pg section double-quoted", text.contains("\"t2_bak_"));
        assertFalse("pg table never backticked", text.contains("`t2"));
        assertTrue("each segment keeps the generation header",
            text.indexOf("-- 系统自动生成") != text.lastIndexOf("-- 系统自动生成"));
        // the companion list carries both groups' entries with re-stamped ordinals
        assertEquals(2, r.getPerStmtTypes().size());
        assertEquals(0, r.getPerStmtTypes().get(0).getGroupOrdinal());
        assertEquals(1, r.getPerStmtTypes().get(1).getGroupOrdinal());
        assertEquals("t1", r.getPerStmtTypes().get(0).getTable());
        assertEquals("t2", r.getPerStmtTypes().get(1).getTable());
    }

    @Test
    public void c10_combined_blankOrNullGroups_noMaterial() {
        assertNull(generator.generateCombined(null).getText());
        assertNull(generator.generateCombined(java.util.List.of()).getText());
        // Arrays.asList tolerates the null element (List.of rejects it) — the null-group defensive arm
        assertNull(generator.generateCombined(java.util.Arrays.asList(
            new RollbackSqlGenerator.GroupInput("  ", MY, "db"), null)).getText());
        assertTrue(generator.generateCombined(java.util.List.of(
            new RollbackSqlGenerator.GroupInput("", MY, "db"))).getPerStmtTypes().isEmpty());
    }

    @Test
    public void c10_combined_multiGroup_blankGroupSkippedNumberingDense() {
        // a blank group is filtered BEFORE numbering — the [i/n] header stays dense
        RollbackGenResult r = generator.generateCombined(java.util.List.of(
            new RollbackSqlGenerator.GroupInput("DROP TABLE a;", MY, "db1"),
            new RollbackSqlGenerator.GroupInput("   ", MY, "db-blank"),
            new RollbackSqlGenerator.GroupInput("DROP TABLE b;", PG, "db2")));
        assertTrue(r.getText().contains("-- 目标库[1/2]: db1"));
        assertTrue(r.getText().contains("-- 目标库[2/2]: db2"));
        assertFalse(r.getText().contains("db-blank"));
        assertEquals(2, r.getPerStmtTypes().size());
        assertEquals(1, r.getPerStmtTypes().get(1).getGroupOrdinal());
    }
}
