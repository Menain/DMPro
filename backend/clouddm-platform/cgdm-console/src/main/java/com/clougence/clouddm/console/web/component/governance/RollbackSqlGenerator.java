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

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.clougence.utils.StringUtils;

import lombok.extern.slf4j.Slf4j;

/**
 * Generates a default rollback SQL backup template when the applicant did not provide one.
 * <p>
 * Strategy (design D7 — backup template, NOT data-reverse):
 * <ul>
 * <li><b>UPDATE / DELETE</b> → {@code CREATE TABLE {table}_bak_{ts} AS SELECT * FROM {table} WHERE {original-condition};}
 *     — table name via regex (same {@code extractTables} approach as {@code ReleaseSchemaPrecheckServiceImpl});
 *       WHERE clause via text-level top-level extraction. Multi-table JOIN or unextractable WHERE →
 *       degrade to full-table backup warning template.</li>
 * <li><b>INSERT</b> → comment template (DELETE reverse needs PK positioning — warning + template).</li>
 * <li><b>DDL (DROP / TRUNCATE / ALTER)</b> → irreversible warning + object backup suggestion template.</li>
 * <li><b>Multi-statement</b> → per-statement segmentation with comment separators; header:
 *     {@code -- 系统自动生成，执行前请人工确认}</li>
 * <li><b>Parse exception</b> → pure warning text, <b>never blocks submission</b>.</li>
 * </ul>
 * <p>
 * Pure-functional, stateless, never throws.
 */
@Slf4j
@Service
public class RollbackSqlGenerator {

    private static final String  HEADER_COMMENT  = "-- 系统自动生成，执行前请人工确认";
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    // Identifier pattern: backtick-quoted, double-quoted, or bareword (same as ReleaseSchemaPrecheckServiceImpl).
    private static final String IDENT  = "(?:`[^`]+`|\"[^\"]+\"|[a-zA-Z_\\u4e00-\\u9fa5][\\w\\u4e00-\\u9fa5]*)";
    private static final String QIDENT = "(" + IDENT + "(?:\\s*\\.\\s*" + IDENT + ")*)";

    private static final Pattern P_UPDATE  = Pattern.compile("UPDATE\\s+" + QIDENT, Pattern.CASE_INSENSITIVE);
    private static final Pattern P_DELETE  = Pattern.compile("DELETE\\s+FROM\\s+" + QIDENT, Pattern.CASE_INSENSITIVE);
    private static final Pattern P_INSERT  = Pattern.compile("INSERT\\s+INTO\\s+" + QIDENT, Pattern.CASE_INSENSITIVE);
    private static final Pattern P_DROP    = Pattern.compile("DROP\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?" + QIDENT, Pattern.CASE_INSENSITIVE);
    private static final Pattern P_TRUNCATE= Pattern.compile("TRUNCATE\\s+(?:TABLE\\s+)?" + QIDENT, Pattern.CASE_INSENSITIVE);
    private static final Pattern P_ALTER   = Pattern.compile("ALTER\\s+TABLE\\s+" + QIDENT, Pattern.CASE_INSENSITIVE);

    private static final Pattern P_WHERE    = Pattern.compile("\\bWHERE\\b\\s+(.*)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Keywords that indicate multi-table DML — WHERE extraction would be unreliable. */
    private static final Pattern P_JOIN     = Pattern.compile("\\b(JOIN|INNER|LEFT|RIGHT|OUTER|CROSS|NATURAL|STRAIGHT_JOIN|USING)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Generate a default rollback SQL from the submitted raw SQL text.
     *
     * @param rawSql the submitted SQL (may contain multiple statements separated by semicolons)
     * @return the generated rollback SQL text (with header comment), or {@code null} if input is blank
     */
    public String generate(String rawSql) {
        if (StringUtils.isBlank(rawSql)) {
            return null;
        }
        try {
            String timestamp = LocalDateTime.now().format(TS_FMT);
            List<String> statements = splitStatements(rawSql);

            StringBuilder sb = new StringBuilder();
            sb.append(HEADER_COMMENT).append("\n");

            boolean first = true;
            for (String stmt : statements) {
                String trimmed = stmt.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (!first) {
                    sb.append("\n");
                }
                first = false;
                sb.append(generateForStatement(trimmed, timestamp));
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("[RollbackSqlGenerator] generation failed, degrading to warning text", e);
            return HEADER_COMMENT + "\n"
                + "-- 生成过程发生异常: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()) + "\n"
                + "-- 请人工编写回滚 SQL";
        }
    }

    // ==================== per-statement generation ====================

    private String generateForStatement(String stmt, String timestamp) {
        String upper = stmt.toUpperCase().trim();
        try {
            if (upper.startsWith("UPDATE") || upper.startsWith("DELETE")) {
                return generateDmlRollback(stmt, timestamp);
            }
            if (upper.startsWith("INSERT")) {
                return generateInsertRollback(stmt);
            }
            if (upper.startsWith("DROP")) {
                return generateDDLRollback(stmt, "DROP", timestamp);
            }
            if (upper.startsWith("TRUNCATE")) {
                return generateDDLRollback(stmt, "TRUNCATE", timestamp);
            }
            if (upper.startsWith("ALTER")) {
                return generateDDLRollback(stmt, "ALTER", timestamp);
            }
            // GRANT / REVOKE / CREATE / RENAME / other — generic template
            return generateGenericRollback(stmt);
        } catch (Exception e) {
            log.warn("[RollbackSqlGenerator] per-statement generation failed for: {}", stmt, e);
            return "-- 语句解析失败，请人工编写回滚 SQL\n"
                + "-- 原始语句: " + truncateForComment(stmt) + "\n"
                + "-- 错误: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    // ---- UPDATE / DELETE ----

    private String generateDmlRollback(String stmt, String timestamp) {
        String table = extractTableName(stmt);
        if (table == null) {
            return "-- 无法提取表名，请人工编写回滚 SQL\n"
                + "-- 原始语句: " + truncateForComment(stmt);
        }

        String whereClause = extractWhereClause(stmt);
        boolean multiTable = P_JOIN.matcher(stmt).find();

        String backupTable = table + "_bak_" + timestamp;
        if (multiTable || whereClause == null) {
            // Degrade to full-table backup warning
            return "-- 警告: 多表 JOIN 或 WHERE 子句无法提取，已生成全表备份模板\n"
                + "CREATE TABLE " + quoteIdent(backupTable) + " AS SELECT * FROM " + quoteIdent(table) + ";";
        }

        return "CREATE TABLE " + quoteIdent(backupTable)
            + " AS SELECT * FROM " + quoteIdent(table) + " WHERE " + whereClause + ";";
    }

    // ---- INSERT ----

    private String generateInsertRollback(String stmt) {
        String table = extractTableName(stmt);
        String tableName = table != null ? quoteIdent(table) : "<table>";
        return "-- INSERT 回滚需要主键值定位，无法自动生成精确的 DELETE 语句\n"
            + "-- 建议方法: 执行前查询插入的行，准备对应的 DELETE FROM " + tableName + " WHERE <pk_col> IN (<inserted_values>)\n"
            + "-- 或使用备份表保存插入前的数据\n"
            + "-- 原始语句: " + truncateForComment(stmt);
    }

    // ---- DDL (DROP / TRUNCATE / ALTER) ----

    private String generateDDLRollback(String stmt, String ddlType, String timestamp) {
        String table = extractTableName(stmt);
        String tableName = table != null ? quoteIdent(table) : "<table>";

        if ("DROP".equals(ddlType) || "TRUNCATE".equals(ddlType)) {
            return "-- " + ddlType + " 操作不可逆，表数据无法恢复\n"
                + "-- 建议: 从数据库备份恢复; 如有结构快照可参考重建表结构\n"
                + "-- 涉及表: " + tableName + "\n"
                + "-- 原始语句: " + truncateForComment(stmt);
        }
        // ALTER — partially reversible (ADD COLUMN → DROP COLUMN), but auto-generation unreliable
        return "-- ALTER TABLE 操作的回滚取决于具体子句类型\n"
            + "-- 建议: 参考结构快照编写反向 ALTER 语句 (如 ADD COLUMN → DROP COLUMN)\n"
            + "-- 涉及表: " + tableName + "\n"
            + "-- 原始语句: " + truncateForComment(stmt);
    }

    // ---- Generic fallback ----

    private String generateGenericRollback(String stmt) {
        return "-- 此语句类型无法自动生成回滚 SQL，请人工编写\n"
            + "-- 原始语句: " + truncateForComment(stmt);
    }

    // ==================== extraction helpers ====================

    /** Extract the first table name from a DML/DDL statement. Returns null if not found. */
    private String extractTableName(String stmt) {
        for (Pattern p : List.of(P_UPDATE, P_DELETE, P_INSERT, P_DROP, P_TRUNCATE, P_ALTER)) {
            Matcher m = p.matcher(stmt);
            if (m.find()) {
                return lastComponent(m.group(1));
            }
        }
        return null;
    }

    /**
     * Extract the top-level WHERE clause text from an UPDATE/DELETE statement.
     * Returns null if no WHERE found or extraction is unreliable.
     */
    private String extractWhereClause(String stmt) {
        Matcher m = P_WHERE.matcher(stmt);
        if (!m.find()) {
            return null;
        }
        String whereText = m.group(1).trim();
        // Strip trailing semicolon
        if (whereText.endsWith(";")) {
            whereText = whereText.substring(0, whereText.length() - 1).trim();
        }
        if (whereText.isEmpty()) {
            return null;
        }
        return whereText;
    }

    /** Extract the last component of a qualified name (db.schema.table → table). */
    private static String lastComponent(String qualified) {
        if (qualified == null) {
            return null;
        }
        // Remove backticks/quotes for the last segment
        String cleaned = qualified.replaceAll("`|\"", "");
        int lastDot = cleaned.lastIndexOf('.');
        if (lastDot >= 0 && lastDot < cleaned.length() - 1) {
            return cleaned.substring(lastDot + 1);
        }
        return cleaned;
    }

    /** Wrap a table name in backticks for safe identifier usage. */
    private static String quoteIdent(String name) {
        return "`" + name + "`";
    }

    private static String truncateForComment(String text) {
        if (text == null) {
            return "";
        }
        String single = text.replaceAll("\\s+", " ").trim();
        return single.length() > 200 ? single.substring(0, 200) + " ..." : single;
    }

    /**
     * Split raw SQL into individual statements by semicolons, with basic single/double quote
     * and line-comment awareness. Does not use ANTLR — text-level only (design D7).
     */
    private List<String> splitStatements(String rawSql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        boolean inLineComment = false;

        for (int i = 0; i < rawSql.length(); i++) {
            char c = rawSql.charAt(i);
            char next = (i + 1 < rawSql.length()) ? rawSql.charAt(i + 1) : '\0';

            if (inLineComment) {
                current.append(c);
                if (c == '\n') {
                    inLineComment = false;
                }
                continue;
            }
            if (c == '-' && next == '-' && !inSingle && !inDouble) {
                inLineComment = true;
                current.append(c);
                continue;
            }
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                current.append(c);
                continue;
            }
            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                current.append(c);
                continue;
            }
            if (c == ';' && !inSingle && !inDouble) {
                String stmt = current.toString().trim();
                if (!stmt.isEmpty()) {
                    statements.add(stmt);
                }
                current = new StringBuilder();
                continue;
            }
            current.append(c);
        }
        String last = current.toString().trim();
        if (!last.isEmpty()) {
            statements.add(last);
        }
        return statements;
    }
}
