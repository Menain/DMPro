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

import com.clougence.clouddm.base.metadata.ds.DataSourceType;
import com.clougence.clouddm.platform.dal.model.approval.RecoveryType;
import com.clougence.utils.StringUtils;

import lombok.Getter;
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
 * C10 (task 10-14-recovery-type-grading, design D2) — dialect-aware + typed, with a hard
 * compatibility red line:
 * <ul>
 * <li><b>Dialect</b>: {@code generate(rawSql, dsType)} renders identifiers in the target
 *     dialect — PostgreSQL → double-quoted identifiers ({@code CREATE TABLE "t_bak_…" AS
 *     SELECT …} is valid PG); everything else (including null/unknown) → the pre-C10 MySQL
 *     backtick shape. Exact-match conservatism follows the DmlBatchServiceImpl dialect gate
 *     precedent (PG-family variants keep the status quo — a wrong quote style in a
 *     human-completed template is a cosmetic miss, never a mis-grade). The comment copy stays
 *     zh in both dialects (the pre-existing template language — only SQL syntax follows the
 *     dialect).</li>
 * <li><b>Byte-identity red line</b>: the MySQL/default branch text is UNCHANGED byte-for-byte
 *     (the 16 RollbackSqlGeneratorTest pins pass with zero edits), and the single-group
 *     degenerate of {@link #generateCombined} equals the legacy {@code generate(combinedSql)}
 *     output (combinedSql of one group IS that group's SQL).</li>
 * <li><b>Per-group generation</b>: {@link #generateCombined} renders each group's template in
 *     its OWN dialect and concatenates with a target-db header annotation per segment
 *     (multi-group multi-dialect correctness); the header appears ONLY in the multi-group
 *     shape (single-group output stays byte-identical to the legacy path).</li>
 * <li><b>Typed companion</b>: {@link RollbackGenResult#getPerStmtTypes()} classifies every
 *     processed statement (the RecoveryTypeInferrer per-statement view source, 裁决 2 —
 *     display material, never persisted).</li>
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
     * Generate a default rollback SQL from the submitted raw SQL text (the pre-C10 signature —
     * kept and delegating to the MySQL/default dialect so every legacy caller and the 16 test
     * pins stay byte-identical).
     *
     * @param rawSql the submitted SQL (may contain multiple statements separated by semicolons)
     * @return the generated rollback SQL text (with header comment), or {@code null} if input is blank
     */
    public String generate(String rawSql) {
        return generate(rawSql, null);
    }

    /**
     * C10 (design D2): dialect-aware generation — the text leg of {@link #generateTyped}.
     *
     * @param dsType the target datasource type; {@link DataSourceType#PostgreSQL} renders
     *               double-quoted identifiers, null/anything else keeps the MySQL shape
     */
    public String generate(String rawSql, DataSourceType dsType) {
        return generateTyped(rawSql, dsType).getText();
    }

    /**
     * C10 (design D2): the TYPED generation — template text + the per-statement classification
     * companion list ({@link RollbackGenResult}). Single-group shape (groupOrdinal 0, no header).
     * Never throws; blank input → {@code text == null} (the legacy contract).
     */
    public RollbackGenResult generateTyped(String rawSql, DataSourceType dsType) {
        return generateForGroup(rawSql, dsType, 0);
    }

    /**
     * C10 (design D2): PER-GROUP generation for the ticket-level combined material — each group
     * renders in its own dialect; the segments are concatenated with a target-db header comment
     * ({@code -- 目标库[i/n]: label}). Degenerate shapes:
     * <ul>
     * <li>no group / every group blank → {@code text == null} (no material — the write path
     *     leaves roll_back_sql untouched, the pre-C10 blank-combinedSql behavior);</li>
     * <li>EXACTLY ONE non-blank group → byte-identical to {@link #generateTyped} of that group
     *     (NO header — the single-group combinedSql identity pin, red line 1).</li>
     * </ul>
     * Never throws (a catastrophic assembly failure degrades to "no material" with a warn).
     */
    public RollbackGenResult generateCombined(List<GroupInput> groups) {
        try {
            if (groups == null || groups.isEmpty()) {
                return new RollbackGenResult(null, List.of());
            }
            List<GroupInput> effective = new ArrayList<>();
            for (GroupInput g : groups) {
                if (g != null && StringUtils.isNotBlank(g.getSql())) {
                    effective.add(g);
                }
            }
            if (effective.isEmpty()) {
                return new RollbackGenResult(null, List.of());
            }
            if (effective.size() == 1) {
                GroupInput only = effective.get(0);
                return generateForGroup(only.getSql(), only.getDsType(), 0);
            }
            StringBuilder text = new StringBuilder();
            List<RollbackGenResult.StmtRecovery> all = new ArrayList<>();
            int n = effective.size();
            for (int gi = 0; gi < n; gi++) {
                GroupInput g = effective.get(gi);
                RollbackGenResult r = generateForGroup(g.getSql(), g.getDsType(), gi);
                if (r.getText() == null) {
                    continue; // structurally unreachable (blank sql was filtered) — defensive
                }
                if (text.length() > 0) {
                    text.append("\n");
                }
                text.append("-- 目标库[").append(gi + 1).append("/").append(n).append("]: ")
                    .append(groupLabel(g)).append("\n");
                text.append(r.getText());
                all.addAll(r.getPerStmtTypes());
            }
            return new RollbackGenResult(text.length() == 0 ? null : text.toString(), all);
        } catch (Exception e) {
            log.warn("[RollbackSqlGenerator] combined generation failed, degrading to no material", e);
            return new RollbackGenResult(null, List.of());
        }
    }

    /** One group's contribution to the combined rollback material (the write paths build these). */
    @Getter
    public static final class GroupInput {
        /** The group's SQL text (blank groups are skipped by generateCombined). */
        private final String         sql;
        /** The group's target dialect (null → MySQL/default shape). */
        private final DataSourceType dsType;
        /** Display label for the multi-group header (dbName + dialect); nullable. */
        private final String         targetLabel;

        public GroupInput(String sql, DataSourceType dsType, String targetLabel) {
            this.sql = sql;
            this.dsType = dsType;
            this.targetLabel = targetLabel;
        }
    }

    // ==================== generation core ====================

    private RollbackGenResult generateForGroup(String rawSql, DataSourceType dsType, int groupOrdinal) {
        if (StringUtils.isBlank(rawSql)) {
            return new RollbackGenResult(null, List.of());
        }
        boolean pg = isPostgres(dsType);
        try {
            String timestamp = LocalDateTime.now().format(TS_FMT);
            List<String> statements = splitStatements(rawSql);

            StringBuilder sb = new StringBuilder();
            sb.append(HEADER_COMMENT).append("\n");

            List<RollbackGenResult.StmtRecovery> perStmt = new ArrayList<>();
            boolean first = true;
            int index = 0;
            for (String stmt : statements) {
                String trimmed = stmt.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (!first) {
                    sb.append("\n");
                }
                first = false;
                StmtGen gen = generateForStatement(trimmed, timestamp, pg);
                sb.append(gen.text);
                perStmt.add(new RollbackGenResult.StmtRecovery(groupOrdinal, index++, gen.recovery, gen.table, gen.note));
            }
            return new RollbackGenResult(sb.toString(), perStmt);
        } catch (Exception e) {
            log.warn("[RollbackSqlGenerator] generation failed, degrading to warning text", e);
            return new RollbackGenResult(HEADER_COMMENT + "\n"
                + "-- 生成过程发生异常: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()) + "\n"
                + "-- 请人工编写回滚 SQL", List.of());
        }
    }

    // ==================== per-statement generation ====================

    /** one statement's rendered text + its C10 classification (the typed companion). */
    private record StmtGen(String text, RecoveryType recovery, String table, String note) {
    }

    private StmtGen generateForStatement(String stmt, String timestamp, boolean pg) {
        String upper = stmt.toUpperCase().trim();
        try {
            if (upper.startsWith("UPDATE") || upper.startsWith("DELETE")) {
                return generateDmlRollback(stmt, timestamp, pg);
            }
            if (upper.startsWith("INSERT")) {
                return generateInsertRollback(stmt, pg);
            }
            if (upper.startsWith("DROP")) {
                return generateDDLRollback(stmt, "DROP", timestamp, pg);
            }
            if (upper.startsWith("TRUNCATE")) {
                return generateDDLRollback(stmt, "TRUNCATE", timestamp, pg);
            }
            if (upper.startsWith("ALTER")) {
                return generateDDLRollback(stmt, "ALTER", timestamp, pg);
            }
            // GRANT / REVOKE / CREATE / RENAME / other — generic template
            return generateGenericRollback(stmt);
        } catch (Exception e) {
            log.warn("[RollbackSqlGenerator] per-statement generation failed for: {}", stmt, e);
            return new StmtGen("-- 语句解析失败，请人工编写回滚 SQL\n"
                + "-- 原始语句: " + truncateForComment(stmt) + "\n"
                + "-- 错误: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()),
                RecoveryType.UNSPECIFIED, null, "语句解析失败，需人工编写回滚 SQL");
        }
    }

    // ---- UPDATE / DELETE ----

    private StmtGen generateDmlRollback(String stmt, String timestamp, boolean pg) {
        String table = extractTableName(stmt);
        if (table == null) {
            return new StmtGen("-- 无法提取表名，请人工编写回滚 SQL\n"
                + "-- 原始语句: " + truncateForComment(stmt),
                RecoveryType.UNSPECIFIED, null, "无法提取表名，需人工编写回滚 SQL");
        }

        String whereClause = extractWhereClause(stmt);
        boolean multiTable = P_JOIN.matcher(stmt).find();

        String backupTable = table + "_bak_" + timestamp;
        if (multiTable || whereClause == null) {
            // Degrade to full-table backup warning
            return new StmtGen("-- 警告: 多表 JOIN 或 WHERE 子句无法提取，已生成全表备份模板\n"
                + "CREATE TABLE " + quoteIdent(backupTable, pg) + " AS SELECT * FROM " + quoteIdent(table, pg) + ";",
                RecoveryType.BACKUP_TEMPLATE, table, "全表备份模板（多表 JOIN 或 WHERE 子句无法提取）");
        }

        return new StmtGen("CREATE TABLE " + quoteIdent(backupTable, pg)
            + " AS SELECT * FROM " + quoteIdent(table, pg) + " WHERE " + whereClause + ";",
            RecoveryType.BACKUP_TEMPLATE, table, "变更前备份表模板（WHERE 条件保留）");
    }

    // ---- INSERT ----

    private StmtGen generateInsertRollback(String stmt, boolean pg) {
        String table = extractTableName(stmt);
        String tableName = table != null ? quoteIdent(table, pg) : "<table>";
        return new StmtGen("-- INSERT 回滚需要主键值定位，无法自动生成精确的 DELETE 语句\n"
            + "-- 建议方法: 执行前查询插入的行，准备对应的 DELETE FROM " + tableName + " WHERE <pk_col> IN (<inserted_values>)\n"
            + "-- 或使用备份表保存插入前的数据\n"
            + "-- 原始语句: " + truncateForComment(stmt),
            RecoveryType.UNSPECIFIED, table, "INSERT 回滚需人工准备逆向 DELETE（主键定位）");
    }

    // ---- DDL (DROP / TRUNCATE / ALTER) ----

    private StmtGen generateDDLRollback(String stmt, String ddlType, String timestamp, boolean pg) {
        String table = extractTableName(stmt);
        String tableName = table != null ? quoteIdent(table, pg) : "<table>";

        if ("DROP".equals(ddlType) || "TRUNCATE".equals(ddlType)) {
            return new StmtGen("-- " + ddlType + " 操作不可逆，表数据无法恢复\n"
                + "-- 建议: 从数据库备份恢复; 如有结构快照可参考重建表结构\n"
                + "-- 涉及表: " + tableName + "\n"
                + "-- 原始语句: " + truncateForComment(stmt),
                RecoveryType.IRREVERSIBLE, table, ddlType + " 操作不可逆，恢复依赖数据库备份");
        }
        // ALTER — partially reversible (ADD COLUMN → DROP COLUMN), but auto-generation unreliable
        return new StmtGen("-- ALTER TABLE 操作的回滚取决于具体子句类型\n"
            + "-- 建议: 参考结构快照编写反向 ALTER 语句 (如 ADD COLUMN → DROP COLUMN)\n"
            + "-- 涉及表: " + tableName + "\n"
            + "-- 原始语句: " + truncateForComment(stmt),
            RecoveryType.UNSPECIFIED, table, "ALTER 回滚取决于子句类型，需人工编写反向 ALTER");
    }

    // ---- Generic fallback ----

    private StmtGen generateGenericRollback(String stmt) {
        return new StmtGen("-- 此语句类型无法自动生成回滚 SQL，请人工编写\n"
            + "-- 原始语句: " + truncateForComment(stmt),
            RecoveryType.UNSPECIFIED, null, "此语句类型无法自动生成，需人工编写");
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

    /**
     * C10 dialect gate: exact-match PostgreSQL (the DmlBatchServiceImpl.isBatchRouteSupportedDs
     * precedent — PG-family variants deliberately keep the MySQL shape; under-reaching only
     * affects template cosmetics, never a mis-grade). Null/unknown → MySQL default (status quo).
     */
    private static boolean isPostgres(DataSourceType dsType) {
        return dsType == DataSourceType.PostgreSQL;
    }

    /**
     * Wrap a table name for safe identifier usage — MySQL/default: backticks (UNCHANGED, red
     * line 1); PostgreSQL (C10): double quotes (lastComponent already stripped any embedded
     * quote characters, so no escape sequence can occur in the name).
     */
    private static String quoteIdent(String name, boolean pg) {
        return pg ? "\"" + name + "\"" : "`" + name + "`";
    }

    private static String groupLabel(GroupInput g) {
        if (StringUtils.isNotBlank(g.getTargetLabel())) {
            return g.getTargetLabel();
        }
        return g.getDsType() != null ? g.getDsType().name() : "未知";
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
