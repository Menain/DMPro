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
package com.clougence.clouddm.console.web.service.dmlbatch;

import java.util.regex.Pattern;

import com.clougence.clouddm.sdk.sql.editor.rewrite.DmlBatchRangeSpec;
import com.clougence.utils.StringUtils;

/**
 * B7 lock-free DML — the CONSOLE-SIDE SINGLE POINT for every literal/identifier rendering that
 * rides into a rewritten batch statement or a probe statement (design §3.4, R18; N1a handover
 * obligation #5). The division of responsibility is the {@link DmlBatchRangeSpec} javadoc
 * contract: this class renders typed literals, the rewrite SPI injects them VERBATIM and only
 * dialect-quotes the (console-validated) bare key-column identifier.
 * <p>
 * Value provenance: cursor/boundary values come from the TARGET database's own SELECT MAX
 * output — never user text — but they are treated as untrusted input all the same (a string key
 * can contain quotes/backslashes; a mis-typed column could surface an exotic format). Every
 * renderer VALIDATES its input shape and returns null on any deviation — the caller treats a
 * null render as a loud batch failure (never as "execute with a best-effort literal").
 * <p>
 * Escaping discipline (ops-diagnostics §4 / the B4 lesson):
 * <ul>
 * <li>MySQL string literals: backslash doubling FIRST (default sql_mode has no
 *     NO_BACKSLASH_ESCAPES), then single-quote doubling — the ghost
 *     {@code escapeMySqlStringLiteral} contract verbatim;</li>
 * <li>PG string literals: single-quote doubling ONLY (standard_conforming_strings=on since 9.1 —
 *     doubling backslashes there would CORRUPT the value);</li>
 * <li>INT / DECIMAL0: plain-digit shape check ({@code ^-?\d+$}) then injected unquoted — no
 *     escaping exists for a digit-only token;</li>
 * <li>DATETIME: strict ISO-ish shape check (digits/colons/dashes/space/T/dot/sign/Z only — no
 *     quote or backslash can survive the check) then single-quoted verbatim.</li>
 * </ul>
 * Pure statics, never throw. Unit-test discipline: COMPLETE fragment assertions over the
 * escaping matrix (three key types × two dialects × {@code '}, {@code \}, {@code "}).
 */
public final class DmlBatchLiterals {

    /** B6 validateIdentifier heritage: the batch key column is a bare ASCII identifier (probe result, never user text). */
    public static final Pattern KEY_COLUMN_WHITELIST = Pattern.compile("[\\w$]+");

    /** plain integer literal (INT / DECIMAL0 — scale 0 by the key whitelist); no leading '+', no exponent, no decimal point. */
    static final Pattern INT_LITERAL = Pattern.compile("-?\\d+");

    /**
     * ISO-ish temporal literal as rendered by MySQL/PG SELECT output: {@code yyyy-MM-dd} with an
     * optional time part (space or 'T' separator, optional seconds, optional 1..9 fractional
     * digits) and an optional zone suffix ({@code Z} / {@code ±hh} / {@code ±hh:mm} / {@code ±hhmm}).
     * The character class excludes quotes, backslashes and semicolons BY CONSTRUCTION — a value
     * that passes needs no escaping inside the single quotes.
     */
    static final Pattern DATETIME_LITERAL = Pattern.compile(
        "\\d{4}-\\d{2}-\\d{2}([ T]\\d{2}:\\d{2}(:\\d{2}(\\.\\d{1,9})?)?)?( ?[Zz]|[+-]\\d{2}(:?\\d{2})?)?");

    private DmlBatchLiterals() {
    }

    /** {@code [\w$]+} whitelist check of a probed key column (console side; the SPI re-checks defensively). */
    public static boolean isValidKeyColumn(String column) {
        return column != null && KEY_COLUMN_WHITELIST.matcher(column).matches();
    }

    /**
     * Render one cursor/boundary literal for injection into a rewrite spec (loLiteral/hiLiteral).
     *
     * @param keyType  one of the {@link DmlBatchRangeSpec} KEY_TYPE_* wire values
     * @param rawValue the raw string exactly as the target DB returned it (SELECT MAX output);
     *                 null/blank is legal ONLY for the exclusive lower bound of the FIRST batch —
     *                 the caller passes null there and must not call this method
     * @param postgres dialect switch (STRING escaping differs; INT/DATETIME are dialect-neutral)
     * @return the fully-rendered literal (quotes included where the type requires them), or null
     *         when the value does not match its type's shape (loud batch failure, never execute)
     */
    public static String renderLiteral(String keyType, String rawValue, boolean postgres) {
        if (keyType == null || rawValue == null) {
            return null;
        }
        String value = rawValue.trim();
        if (value.isEmpty()) {
            return null;
        }
        switch (keyType) {
            case DmlBatchRangeSpec.KEY_TYPE_INT:
            case DmlBatchRangeSpec.KEY_TYPE_DECIMAL0:
                return INT_LITERAL.matcher(value).matches() ? value : null;
            case DmlBatchRangeSpec.KEY_TYPE_DATETIME:
                return DATETIME_LITERAL.matcher(value).matches() ? "'" + value + "'" : null;
            case DmlBatchRangeSpec.KEY_TYPE_STRING:
                return postgres ? escapePgStringLiteral(value) : escapeMySqlStringLiteral(value);
            default:
                return null; // unknown key type — the whitelist gate should have excluded it
        }
    }

    /**
     * MySQL string-literal quoting for value positions (ops-diagnostics §4 / B4 lesson):
     * backslash doubling FIRST, then single-quote doubling. Also the embedding form of the
     * probe statements' schema/table literals.
     */
    public static String escapeMySqlStringLiteral(String value) {
        return '\'' + value.replace("\\", "\\\\").replace("'", "''") + '\'';
    }

    /**
     * PG string-literal quoting for value positions: single-quote doubling ONLY
     * (standard_conforming_strings=on — a doubled backslash would corrupt the value).
     */
    public static String escapePgStringLiteral(String value) {
        return '\'' + value.replace("'", "''") + '\'';
    }

    /** true when the raw value is a legal first-batch lower bound sentinel (null/blank = no lower bound). */
    public static boolean isBlankCursor(String rawValue) {
        return StringUtils.isBlank(rawValue);
    }

    /**
     * C6/M6 (task 10-11-masking-fail-closed, design §6 — the tiered cursor-disclosure ruling):
     * the USER-facing display form of a raw cursor value (task statusMsg / segment summary —
     * both rendered on frontend surfaces). The raw cursor is a real key-column VALUE from the
     * target table, so the user side gets a correlation handle instead of the value:
     * {@code sha256:<first 8 hex>…(len=N)} — the operator can match it against the biz_log
     * forensics (which KEEP the raw value by ruling) without the value reaching the frontend.
     * <p>
     * NEVER apply this to the checkpoint cursor (functional: the resume WHERE lower bound) or
     * to the biz_log forensics / SKIP evidence (the UNRESOLVED manual-verdict basis).
     * Pure static, never throws (a digest failure degrades to the length-only form).
     */
    public static String hashedCursorForDisplay(String rawCursor) {
        if (rawCursor == null) {
            return null;
        }
        int len = rawCursor.length();
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawCursor.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(8);
            for (int i = 0; i < 4; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return "sha256:" + hex + "…(len=" + len + ")";
        } catch (java.security.NoSuchAlgorithmException e) {
            return "…(len=" + len + ")"; // SHA-256 is JDK-mandatory; defensive only
        }
    }
}
