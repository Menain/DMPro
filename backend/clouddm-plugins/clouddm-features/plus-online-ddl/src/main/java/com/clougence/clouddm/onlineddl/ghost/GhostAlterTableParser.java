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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.clougence.utils.StringUtils;

import lombok.Getter;

/**
 * Narrow, strict parser for the single {@code ALTER TABLE [db.]tbl <alter clause>} statement a
 * GHOST session accepts (replaces the druid-based {@code MySqlCollectAlterTableVisitor} /
 * {@code GhostSessionOld.initAlterTable}; druid is gone from the build).
 *
 * <p>Contract (design 09-17-online-ddl-activation §2.5, 方案②):</p>
 * <ul>
 * <li>exactly one statement — any embedded {@code ;} (a single trailing one is tolerated and
 *     stripped) rejects as multi-statement;</li>
 * <li>must start with {@code ALTER TABLE} (case-insensitive);</li>
 * <li>identifiers only in {@code [\w$]+} form, optionally backtick-quoted — anything else
 *     (spaces, CJK, brackets, quotes…) rejects (宁可误拒不可误放);</li>
 * <li>SQL comments ({@code --}, {@code #}, {@code /*}) reject;</li>
 * <li>the database is authoritative from the session schema: when the statement carries a
 *     {@code db.} prefix it must match the schema exactly, otherwise
 *     {@link GhostToolException#CODE_SCHEMA_MISMATCH};</li>
 * <li>the extracted alter clause feeds gh-ost {@code --alter} (the {@code ALTER TABLE tbl}
 *     prefix is stripped, the clause is passed verbatim otherwise).</li>
 * </ul>
 */
public final class GhostAlterTableParser {

    /** `quoted` or bare identifier, ASCII word chars + $ only (strict, no spaces/CJK) */
    private static final String  IDENT       = "(?:`[\\w$]+`|[\\w$]+)";

    private static final Pattern ALTER_TABLE = Pattern.compile(
            "^ALTER\\s+TABLE\\s+(" + IDENT + ")(?:\\s*\\.\\s*(" + IDENT + "))?\\s+(\\S[\\s\\S]*)$",
            Pattern.CASE_INSENSITIVE);

    private GhostAlterTableParser(){
    }

    /** parse outcome: the gh-ost --database / --table / --alter triple */
    @Getter
    public static final class AlterTarget {

        private final String database;
        private final String tableName;
        private final String alterClause;

        AlterTarget(String database, String tableName, String alterClause){
            this.database = database;
            this.tableName = tableName;
            this.alterClause = alterClause;
        }
    }

    /**
     * @param ddlSql the user supplied statement
     * @param schema the session schema (authoritative database name, must be non-blank)
     * @throws GhostToolException CODE_STMT_UNSUPPORTED / CODE_SCHEMA_MISMATCH / CODE_BAD_LAUNCH_CONFIG
     */
    public static AlterTarget parse(String ddlSql, String schema) {
        if (StringUtils.isBlank(schema)) {
            throw new GhostToolException(GhostToolException.CODE_BAD_LAUNCH_CONFIG, "schema is blank.");
        }
        if (StringUtils.isBlank(ddlSql)) {
            throw new GhostToolException(GhostToolException.CODE_BAD_LAUNCH_CONFIG, "ddlSql is blank.");
        }

        String sql = ddlSql.trim();
        // a single trailing semicolon is tolerated; any other ';' means multi-statement → reject
        if (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).trim();
        }
        if (sql.indexOf(';') >= 0) {
            throw new GhostToolException(GhostToolException.CODE_STMT_UNSUPPORTED,
                    "only a single ALTER TABLE statement is allowed (embedded ';' found).");
        }
        if (sql.contains("--") || sql.contains("#") || sql.contains("/*") || sql.contains("*/")) {
            throw new GhostToolException(GhostToolException.CODE_STMT_UNSUPPORTED,
                    "SQL comments are not allowed in the lock-free DDL statement.");
        }

        Matcher matcher = ALTER_TABLE.matcher(sql);
        if (!matcher.matches()) {
            throw new GhostToolException(GhostToolException.CODE_STMT_UNSUPPORTED,
                    "not a plain 'ALTER TABLE [db.]tbl <alter clause>' statement (identifiers must be [\\w$]+,"
                                            + " optionally backtick-quoted): " + abbreviate(sql));
        }

        String first = unquote(matcher.group(1));
        String second = unquote(matcher.group(2));
        String database;
        String tableName;
        if (second != null) {
            // db.tbl form — the prefix must agree with the session schema
            if (!first.equals(schema)) {
                throw new GhostToolException(GhostToolException.CODE_SCHEMA_MISMATCH,
                        "statement database '" + first + "' differs from the session schema '" + schema + "'.");
            }
            database = first;
            tableName = second;
        } else {
            database = schema;
            tableName = first;
        }

        String alterClause = matcher.group(3).trim();
        if (StringUtils.isBlank(alterClause)) {
            throw new GhostToolException(GhostToolException.CODE_STMT_UNSUPPORTED, "the alter clause is empty.");
        }

        return new AlterTarget(database, tableName, alterClause);
    }

    private static String unquote(String ident) {
        if (ident == null) {
            return null;
        }
        if (ident.length() > 1 && ident.charAt(0) == '`' && ident.charAt(ident.length() - 1) == '`') {
            return ident.substring(1, ident.length() - 1);
        }
        return ident;
    }

    private static String abbreviate(String sql) {
        return sql.length() <= 200 ? sql : sql.substring(0, 200) + "...";
    }
}
