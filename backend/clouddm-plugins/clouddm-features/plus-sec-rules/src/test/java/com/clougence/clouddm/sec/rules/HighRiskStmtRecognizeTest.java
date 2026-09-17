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
package com.clougence.clouddm.sec.rules;

import java.util.List;

import org.junit.Test;

import com.clougence.clouddm.sdk.service.secrules.RuleDomain;
import com.clougence.clouddm.sdk.service.secrules.RuleQueryType;

/**
 * 校验 MySQL 解析器对高危语句的 sqlType 识别。
 *
 * 新增高危规则 DSL 均基于 {@code @domain.sqlType == 'XXX'} 判定，
 * 本测试确认解析器确实为对应语句产出预期 RuleQueryType，
 * 从而保证规则在预检链路中能被命中（不误报、不漏报）。
 */
public class HighRiskStmtRecognizeTest extends AbstractRangeTestCase {

    private RuleDomain firstDomain(String sql) {
        List<RuleDomain> list = resolveDomain(sql);
        assert list != null && !list.isEmpty() : "parser returned no domain for: " + sql;
        return list.get(0);
    }

    private void assertSqlType(String sql, RuleQueryType expected) {
        RuleDomain domain = firstDomain(sql);
        assert expected == domain.getSqlType()
            : "sql=" + sql + ", expected=" + expected + ", actual=" + domain.getSqlType();
    }

    // ===== P0：不可逆对象删除 / 清空 =====

    @Test
    public void dropTable() {
        assertSqlType("DROP TABLE t1;", RuleQueryType.DROP_TABLE);
    }

    @Test
    public void dropDatabase() {
        // MySQL 中 DROP DATABASE 与 DROP SCHEMA 同义，解析器统一归类为 DROP_SCHEMA
        assertSqlType("DROP DATABASE db1;", RuleQueryType.DROP_SCHEMA);
    }

    @Test
    public void dropSchema() {
        assertSqlType("DROP SCHEMA s1;", RuleQueryType.DROP_SCHEMA);
    }

    @Test
    public void truncateTable() {
        // RuleDomain.getSqlType() 将 TRUNCATE_TABLE 映射为 TRUNCATE
        assertSqlType("TRUNCATE TABLE t1;", RuleQueryType.TRUNCATE);
    }

    // ===== P1：DCL 与管理类操作 =====

    @Test
    public void grant() {
        assertSqlType("GRANT SELECT ON db.* TO 'u'@'%';", RuleQueryType.GRANT);
    }

    @Test
    public void revoke() {
        assertSqlType("REVOKE SELECT ON db.* FROM 'u'@'%';", RuleQueryType.REVOKE);
    }

    @Test
    public void createUser() {
        assertSqlType("CREATE USER 'u'@'%' IDENTIFIED BY 'pwd';", RuleQueryType.CREATE_USER);
    }

    @Test
    public void dropUser() {
        assertSqlType("DROP USER 'u'@'%';", RuleQueryType.DROP_USER);
    }

    // ===== P2：其他对象删除 / 管理类 =====

    @Test
    public void dropView() {
        assertSqlType("DROP VIEW v1;", RuleQueryType.DROP_VIEW);
    }

    @Test
    public void dropIndex() {
        assertSqlType("DROP INDEX idx ON t1;", RuleQueryType.DROP_INDEX);
    }

    @Test
    public void dropTrigger() {
        assertSqlType("DROP TRIGGER trg1;", RuleQueryType.DROP_TRIGGER);
    }

    // ===== P1 管理类 / P2 编程对象：经探查确认解析器实际产出的 sqlType =====

    @Test
    public void kill() {
        assertSqlType("KILL 123;", RuleQueryType.KILL);
    }

    @Test
    public void flush() {
        assertSqlType("FLUSH TABLES;", RuleQueryType.MYSQL_FLUSH);
    }

    @Test
    public void purge() {
        assertSqlType("PURGE BINARY LOGS TO 'mysql-bin.010';", RuleQueryType.PURGE);
    }

    /**
     * MySQL 解析器将 DROP PROCEDURE / DROP FUNCTION 统一归类为 DROP_PROG_OBJ
     * （而非 DROP_PROCEDURE / DROP_FUNCTION），故编程对象删除规则需覆盖 DROP_PROG_OBJ。
     */
    @Test
    public void dropProcedure() {
        assertSqlType("DROP PROCEDURE IF EXISTS p1;", RuleQueryType.DROP_PROG_OBJ);
    }

    @Test
    public void dropFunction() {
        assertSqlType("DROP FUNCTION IF EXISTS f1;", RuleQueryType.DROP_PROG_OBJ);
    }

    /**
     * RESET 语句 MySQL 解析器当前无法识别（SplitQueryType 未定义，解析会抛异常），
     * 不为其新增 sqlType 规则——规则永远不会命中。该语句在预检链路会因解析失败被阻断，
     * 详见任务 findings.md「RESET 解析缺口」。
     */
    @Test
    public void resetNotCleanlyRecognized() {
        boolean thrown;
        try {
            resolveDomain("RESET MASTER;");
            thrown = false;
        } catch (RuntimeException e) {
            thrown = true;
        }
        assert thrown : "RESET should not be cleanly recognized by the MySQL parser (parser gap, see findings)";
    }

    // ===== 非误报校验：普通语句不得命中高危规则 =====

    @Test
    public void selectIsNotHighRisk() {
        assertSqlType("SELECT * FROM t1 WHERE id=1;", RuleQueryType.SELECT);
    }

    @Test
    public void insertIsNotHighRisk() {
        assertSqlType("INSERT INTO t1 (id,name) VALUES(1,'a');", RuleQueryType.INSERT);
    }

    @Test
    public void updateIsNotHighRisk() {
        assertSqlType("UPDATE t1 SET name='a' WHERE id=1;", RuleQueryType.UPDATE);
    }

    @Test
    public void deleteIsNotTruncate() {
        assertSqlType("DELETE FROM t1 WHERE id=1;", RuleQueryType.DELETE);
    }
}
