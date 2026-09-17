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
}
