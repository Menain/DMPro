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

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.clougence.utils.StringUtils;

import lombok.Getter;

/**
 * B6 M3 (design §6.5, M1 check 前瞻①): the target-effect probe parser of the segment retry —
 * decides whether a frozen GHOST-segment statement is a <b>single-action ADD COLUMN</b> whose
 * landing can be checked against {@code information_schema.COLUMNS} before a rebuild replays it
 * over an already-altered table ("回写丢失但迁移实成功" guard).
 * <p>
 * v1 pragmatic scope ruling: ONLY the single-action {@code ADD [COLUMN] <ident> ...} shape is
 * decidable — every other shape (MODIFY / DROP / multi-action / parenthesized multi-add /
 * comments / anything the strict grammar does not fully recognize) returns null and the caller
 * conservatively REBUILDS (gh-ost's duplicate-column error is the safe-failure backstop there).
 * <p>
 * Deliberately the SAME strictness contract as {@link GhostAlterStmtChecker} (宁可误拒不可误放):
 * identifiers only in {@code [\w$]+} form optionally backtick-quoted, no comments, single
 * statement. Pure function, never throws.
 */
public final class GhostResumeProbe {

    /** `quoted` or bare identifier, ASCII word chars + $ only (strict — same shape as the checker) */
    private static final String  IDENT       = "(?:`[\\w$]+`|[\\w$]+)";

    private static final Pattern ALTER_TABLE = Pattern.compile(
            "^ALTER\\s+TABLE\\s+(" + IDENT + ")(?:\\s*\\.\\s*(" + IDENT + "))?\\s+(\\S[\\s\\S]*)$",
            Pattern.CASE_INSENSITIVE);

    /** ADD <ident> ... — the ident doubles as the column name only when it is not a grammar keyword */
    private static final Pattern ADD_ACTION  = Pattern.compile(
            "^ADD\\s+(COLUMN\\s+)?(" + IDENT + ")(\\s+\\S[\\s\\S]*)?$",
            Pattern.CASE_INSENSITIVE);

    /**
     * Bare-identifier forms that are NOT a column name after ADD (MySQL alter-option keywords).
     * Only consulted when the explicit COLUMN keyword is absent AND the ident is unquoted —
     * a backtick-quoted {@code `key`} is a legal column name and stays decidable.
     */
    private static final Set<String> ADD_KEYWORDS = Set.of(
            "PRIMARY", "UNIQUE", "KEY", "INDEX", "CONSTRAINT", "FOREIGN", "FULLTEXT",
            "SPATIAL", "CHECK", "IF", "PARTITION", "COLUMN");

    private GhostResumeProbe() {
    }

    /** the probe target: resolved table + the single new column name */
    @Getter
    public static final class Target {

        private final String table;
        private final String column;

        Target(String table, String column) {
            this.table = table;
            this.column = column;
        }
    }

    /**
     * @param alterSql the frozen segment statement (the plan's FULL sql text)
     * @param schema   the group's database name (a {@code db.} prefix must match it)
     * @return the decidable single-action ADD COLUMN target; null for EVERY other shape
     *         (undecidable → the caller conservatively rebuilds)
     */
    public static Target parseSingleAddColumn(String alterSql, String schema) {
        if (StringUtils.isBlank(alterSql) || StringUtils.isBlank(schema)) {
            return null;
        }
        String sql = alterSql.trim();
        // a single trailing semicolon is tolerated; any other ';' means multi-statement → undecidable
        if (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).trim();
        }
        if (sql.indexOf(';') >= 0) {
            return null;
        }
        if (sql.contains("--") || sql.contains("#") || sql.contains("/*") || sql.contains("*/")) {
            return null; // comments could hide extra actions — never probe a commented statement
        }

        Matcher alter = ALTER_TABLE.matcher(sql);
        if (!alter.matches()) {
            return null;
        }
        String first = unquote(alter.group(1));
        String second = unquote(alter.group(2));
        String table;
        if (second != null) {
            if (!first.equals(schema)) {
                return null; // db prefix disagrees with the group schema — not ours to judge
            }
            table = second;
        } else {
            table = first;
        }

        String clause = alter.group(3).trim();
        if (hasTopLevelComma(clause)) {
            return null; // multi-action alter — v1 probes single actions only
        }

        Matcher add = ADD_ACTION.matcher(clause);
        if (!add.matches()) {
            return null; // MODIFY / DROP / RENAME / parenthesized multi-add / ... → rebuild
        }
        String rawIdent = add.group(2);
        boolean quoted = rawIdent.length() > 1 && rawIdent.charAt(0) == '`';
        String column = unquote(rawIdent);
        // an UNQUOTED ident in the keyword set is never treated as a column name — with or without
        // the explicit COLUMN keyword (ADD PRIMARY KEY / ADD INDEX / ADD PARTITION / MariaDB's
        // ADD COLUMN IF NOT EXISTS all land here); a backtick-quoted `key` stays decidable.
        // False negatives only cost a conservative rebuild (the safe direction).
        if (!quoted && ADD_KEYWORDS.contains(column.toUpperCase(java.util.Locale.ROOT))) {
            return null;
        }
        // a trailing definition is required for the bare form's sanity (ADD c INT); the explicit
        // COLUMN keyword form also demands it (ADD COLUMN c INT) — a lone "ADD COLUMN c" is not
        // a statement gh-ost would have accepted, so it never reaches a resume probe in practice.
        if (add.group(3) == null) {
            return null;
        }
        return new Target(table, column);
    }

    /**
     * true when a comma sits at paren-depth 0 outside any quoted span (a multi-action separator).
     * Quote-aware so {@code DECIMAL(10,2)} and {@code DEFAULT 'a,b'} stay single-action.
     */
    static boolean hasTopLevelComma(String clause) {
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < clause.length(); i++) {
            char c = clause.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++; // backslash escape inside the quoted span
                } else if (c == quote) {
                    // doubled quote = an escaped literal quote, stay inside the span
                    if (i + 1 < clause.length() && clause.charAt(i + 1) == quote) {
                        i++;
                    } else {
                        quote = 0;
                    }
                }
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                quote = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                return true;
            }
        }
        return false;
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
