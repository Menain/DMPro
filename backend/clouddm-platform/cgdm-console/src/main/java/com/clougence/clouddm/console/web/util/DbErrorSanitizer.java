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
package com.clougence.clouddm.console.web.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * C6/M7 (task 10-11-masking-fail-closed, design §7 — the error-message value-segment
 * sanitization): raw DB error texts can carry REAL data values — the PG duplicate-key
 * {@code Detail: Key (col)=(value) already exists.} and the MySQL
 * {@code Duplicate entry 'value' for key 'idx'} families echo the conflicting row's key value
 * verbatim, and these texts ride user-facing surfaces (the DML_BATCH_EXEC_FAILED copy, the
 * task statusMsg via lastBatchError, the SQL audit message column + its app-log twin).
 * <p>
 * Contract:
 * <ul>
 * <li>value segments of the KNOWN patterns are replaced with {@code ***}, column/key names
 * and the full error semantics are KEPT (the conflicting key COLUMN is the troubleshooting
 * handle — the survey §6-1 ruling: keep the name, drop the value);</li>
 * <li>CONSERVATIVE by design: only patterns that provably carry data values are matched;
 * any unknown shape passes through UNCHANGED (宁可漏删不误伤 — a lost residual is caught by
 * the integration sweep, a corrupted error message breaks troubleshooting);</li>
 * <li>pure static, never throws; null → null.</li>
 * </ul>
 * Application points: the six {@code DML_BATCH_EXEC_FAILED} {0} arguments, the persisted
 * {@code intent.lastBatchError} (display-only consumers verified: statusMsg + the adjudication
 * modal projection — no functional consumer parses it), and {@code AuditServiceImpl}'s SQL
 * audit message (DB column and the LogInfo app-log exit share the sanitized source).
 */
public final class DbErrorSanitizer {

    /**
     * PG: {@code Key (col1, col2)=(val1, val2)} — the column list is kept, the value tuple is
     * dropped. Nested parens inside a value deliberately break the match (pass-through — the
     * conservative direction). Applies to the {@code already exists.} detail of unique/exclusion
     * constraint violations.
     */
    private static final Pattern PG_KEY_VALUE = Pattern.compile("Key \\(([^()]*)\\)=\\(([^()]*)\\)");

    /**
     * MySQL: {@code Duplicate entry 'val' for key 'idx'} (8.0 may qualify the key as 'tbl.idx').
     * A value containing a single quote deliberately breaks the match (pass-through).
     */
    private static final Pattern MYSQL_DUPLICATE_ENTRY = Pattern.compile("Duplicate entry '[^']*' for key");

    /** the value-segment replacement marker (same visual language as the masking FULL_MASK). */
    private static final String  VALUE_MASK            = "***";

    private DbErrorSanitizer(){
    }

    /**
     * Sanitize every KNOWN data-value segment of a raw DB error message; unknown shapes are
     * returned unchanged. Null-safe, never throws.
     */
    public static String sanitize(String rawError) {
        if (rawError == null || rawError.isEmpty()) {
            return rawError;
        }

        String result = rawError;

        Matcher pg = PG_KEY_VALUE.matcher(result);
        if (pg.find()) {
            StringBuilder sb = new StringBuilder(result.length());
            int last = 0;
            do {
                sb.append(result, last, pg.start())
                    .append("Key (").append(pg.group(1)).append(")=(").append(VALUE_MASK).append(')');
                last = pg.end();
            } while (pg.find());
            sb.append(result, last, result.length());
            result = sb.toString();
        }

        Matcher my = MYSQL_DUPLICATE_ENTRY.matcher(result);
        if (my.find()) {
            StringBuilder sb = new StringBuilder(result.length());
            int last = 0;
            do {
                sb.append(result, last, my.start())
                    .append("Duplicate entry '").append(VALUE_MASK).append("' for key");
                last = my.end();
            } while (my.find());
            sb.append(result, last, result.length());
            result = sb.toString();
        }

        return result;
    }
}
