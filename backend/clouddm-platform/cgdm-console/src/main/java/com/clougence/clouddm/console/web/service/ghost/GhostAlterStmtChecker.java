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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.clougence.utils.StringUtils;

import lombok.Getter;

/**
 * Console-side statement-shape precheck of the single {@code ALTER TABLE [db.]tbl <clause>} a
 * GHOST launch accepts — deliberately the SAME contract as the plugin's
 * {@code GhostAlterTableParser} (design 09-17-online-ddl-activation §2.5 方案②): exactly one
 * statement (a single trailing {@code ;} tolerated), no SQL comments, identifiers only in
 * {@code [\w$]+} form optionally backtick-quoted (宁可误拒不可误放), and a {@code db.} prefix must
 * match the selected schema. The plugin re-runs the authoritative check at session init; this
 * copy only exists so precheck/execute can reject early with zero side effects (the plugin
 * module is not a console dependency, hence the duplication — keep the two in sync).
 */
public final class GhostAlterStmtChecker {

    /** `quoted` or bare identifier, ASCII word chars + $ only (strict, no spaces/CJK) */
    private static final String  IDENT       = "(?:`[\\w$]+`|[\\w$]+)";

    private static final Pattern ALTER_TABLE = Pattern.compile(
            "^ALTER\\s+TABLE\\s+(" + IDENT + ")(?:\\s*\\.\\s*(" + IDENT + "))?\\s+(\\S[\\s\\S]*)$",
            Pattern.CASE_INSENSITIVE);

    /** rejection codes — the stable GHOST_* code names shared with the plugin error contract */
    public static final String   CODE_STMT_UNSUPPORTED = "GHOST_STMT_UNSUPPORTED";
    public static final String   CODE_SCHEMA_MISMATCH  = "GHOST_SCHEMA_MISMATCH";

    private GhostAlterStmtChecker() {
    }

    /** check outcome: the resolved database (authoritative = the selected schema) and table name */
    @Getter
    public static final class AlterStmtTarget {

        private final String database;
        private final String tableName;

        AlterStmtTarget(String database, String tableName) {
            this.database = database;
            this.tableName = tableName;
        }
    }

    /** rejection carrying the stable code (the caller maps it onto the i18n reason text) */
    public static final class AlterStmtReject extends Exception {

        private static final long serialVersionUID = 1L;

        @Getter
        private final String      code;
        @Getter
        private final String      detail;

        AlterStmtReject(String code, String detail) {
            super(code + ": " + detail);
            this.code = code;
            this.detail = detail;
        }
    }

    /**
     * @param ddlSql the user supplied statement (must be non-blank)
     * @param schema the selected database name (authoritative, must be non-blank)
     * @throws AlterStmtReject CODE_STMT_UNSUPPORTED / CODE_SCHEMA_MISMATCH
     */
    public static AlterStmtTarget check(String ddlSql, String schema) throws AlterStmtReject {
        if (StringUtils.isBlank(ddlSql) || StringUtils.isBlank(schema)) {
            throw new AlterStmtReject(CODE_STMT_UNSUPPORTED, "statement and database are required.");
        }

        String sql = ddlSql.trim();
        // a single trailing semicolon is tolerated; any other ';' means multi-statement → reject
        if (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).trim();
        }
        if (sql.indexOf(';') >= 0) {
            throw new AlterStmtReject(CODE_STMT_UNSUPPORTED,
                    "only a single ALTER TABLE statement is allowed (embedded ';' found).");
        }
        if (sql.contains("--") || sql.contains("#") || sql.contains("/*") || sql.contains("*/")) {
            throw new AlterStmtReject(CODE_STMT_UNSUPPORTED,
                    "SQL comments are not allowed in the lock-free DDL statement.");
        }

        Matcher matcher = ALTER_TABLE.matcher(sql);
        if (!matcher.matches()) {
            throw new AlterStmtReject(CODE_STMT_UNSUPPORTED,
                    "not a plain 'ALTER TABLE [db.]tbl <alter clause>' statement (identifiers must be [\\w$]+,"
                                            + " optionally backtick-quoted).");
        }

        String first = unquote(matcher.group(1));
        String second = unquote(matcher.group(2));
        if (second != null) {
            // db.tbl form — the prefix must agree with the selected schema
            if (!first.equals(schema)) {
                throw new AlterStmtReject(CODE_SCHEMA_MISMATCH,
                        "statement database '" + first + "' differs from the selected database '" + schema + "'.");
            }
            return new AlterStmtTarget(first, second);
        }
        return new AlterStmtTarget(schema, first);
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
}
