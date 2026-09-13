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

import java.util.ArrayList;
import java.util.List;

/**
 * Lexical-context-aware full-width character scanner for SQL text.
 * <p>
 * Tracks five lexical contexts (single-quote string, double-quote string,
 * backtick identifier, line comment, block comment) and reports full-width
 * characters that appear <em>outside</em> those contexts. Full-width characters
 * inside strings, identifiers, or comments are legitimate and never reported.
 * <p>
 * Pure function - no Spring dependencies. Fail-open: any internal exception
 * returns the hits collected so far without throwing (never block submission).
 *
 * @see Hit
 */
public final class FullWidthSqlLint {

    /** Maximum hits collected by the scanner (caps pathological inputs). */
    private static final int MAX_SCAN_HITS = 100;

    private FullWidthSqlLint() {
    }

    // ==================== scanner states ====================
    private static final int STATE_NORMAL        = 0;
    private static final int STATE_SINGLE_QUOTE  = 1;
    private static final int STATE_DOUBLE_QUOTE  = 2;
    private static final int STATE_BACKTICK      = 3;
    private static final int STATE_LINE_COMMENT  = 4;
    private static final int STATE_BLOCK_COMMENT = 5;

    /**
     * Scan SQL text for full-width characters outside lexical contexts.
     *
     * @param sql raw SQL text (may be null or empty)
     * @return list of hits (empty if none or on fail-open); never null
     */
    public static List<Hit> scan(String sql) {
        List<Hit> hits = new ArrayList<>();
        if (sql == null || sql.isEmpty()) {
            return hits;
        }
        try {
            int[] codePoints = sql.codePoints().toArray();
            int len = codePoints.length;
            int i = 0;
            int line = 1;
            int col = 1;
            int state = STATE_NORMAL;

            while (i < len) {
                int cp = codePoints[i];

                // ---- newline handling (affects line/col, ends line comment) ----
                if (cp == '\r' || cp == '\n') {
                    if (state == STATE_LINE_COMMENT) {
                        state = STATE_NORMAL;
                    }
                    line++;
                    col = 1;
                    if (cp == '\r' && i + 1 < len && codePoints[i + 1] == '\n') {
                        i += 2; // \r\n counts as one line break
                    } else {
                        i++;
                    }
                    continue;
                }

                switch (state) {
                    case STATE_NORMAL: {
                        // Line comment: --
                        if (cp == '-' && i + 1 < len && codePoints[i + 1] == '-') {
                            state = STATE_LINE_COMMENT;
                            i += 2;
                            col += 2;
                            continue;
                        }
                        // Line comment: #
                        if (cp == '#') {
                            state = STATE_LINE_COMMENT;
                            i++;
                            col++;
                            continue;
                        }
                        // Block comment: /* */
                        if (cp == '/' && i + 1 < len && codePoints[i + 1] == '*') {
                            state = STATE_BLOCK_COMMENT;
                            i += 2;
                            col += 2;
                            continue;
                        }
                        // Single-quote string
                        if (cp == '\'') {
                            state = STATE_SINGLE_QUOTE;
                            i++;
                            col++;
                            continue;
                        }
                        // Double-quote string
                        if (cp == '"') {
                            state = STATE_DOUBLE_QUOTE;
                            i++;
                            col++;
                            continue;
                        }
                        // Backtick identifier
                        if (cp == '`') {
                            state = STATE_BACKTICK;
                            i++;
                            col++;
                            continue;
                        }
                        // Full-width check (only in NORMAL state)
                        if (isFullWidth(cp) && hits.size() < MAX_SCAN_HITS) {
                            hits.add(new Hit(line, col, new String(Character.toChars(cp)), cp));
                        }
                        i++;
                        col++;
                        break;
                    }
                    case STATE_SINGLE_QUOTE: {
                        // Backslash escape: skip next char
                        if (cp == '\\' && i + 1 < len) {
                            i += 2;
                            col += 2;
                            continue;
                        }
                        if (cp == '\'') {
                            if (i + 1 < len && codePoints[i + 1] == '\'') {
                                // '' escape
                                i += 2;
                                col += 2;
                                continue;
                            }
                            // end of string
                            state = STATE_NORMAL;
                            i++;
                            col++;
                            continue;
                        }
                        i++;
                        col++;
                        break;
                    }
                    case STATE_DOUBLE_QUOTE: {
                        // Backslash escape: skip next char
                        if (cp == '\\' && i + 1 < len) {
                            i += 2;
                            col += 2;
                            continue;
                        }
                        if (cp == '"') {
                            if (i + 1 < len && codePoints[i + 1] == '"') {
                                // "" escape
                                i += 2;
                                col += 2;
                                continue;
                            }
                            // end of string
                            state = STATE_NORMAL;
                            i++;
                            col++;
                            continue;
                        }
                        i++;
                        col++;
                        break;
                    }
                    case STATE_BACKTICK: {
                        if (cp == '`') {
                            if (i + 1 < len && codePoints[i + 1] == '`') {
                                // `` escape
                                i += 2;
                                col += 2;
                                continue;
                            }
                            // end of identifier
                            state = STATE_NORMAL;
                            i++;
                            col++;
                            continue;
                        }
                        i++;
                        col++;
                        break;
                    }
                    case STATE_LINE_COMMENT: {
                        // Newline handled above; consume everything else
                        i++;
                        col++;
                        break;
                    }
                    case STATE_BLOCK_COMMENT: {
                        if (cp == '*' && i + 1 < len && codePoints[i + 1] == '/') {
                            state = STATE_NORMAL;
                            i += 2;
                            col += 2;
                            continue;
                        }
                        i++;
                        col++;
                        break;
                    }
                    default: {
                        i++;
                        col++;
                        break;
                    }
                }
            }
            // EOF: if still inside a string/comment, treat remaining as inside
            // literal (fail-open - do not report). Simply return collected hits.
        } catch (Exception e) {
            // fail-open: return whatever we collected, never throw
        }
        return hits;
    }

    /**
     * Check if a Unicode code point is a full-width character that should be
     * reported when found outside a string literal or comment.
     */
    static boolean isFullWidth(int cp) {
        return (cp >= 0xFF01 && cp <= 0xFF5E)   // Full-width forms (；，（）？！etc.)
            || cp == 0x3000                       // Ideographic space
            || cp == 0x3001                       // Ideographic comma (、)
            || cp == 0x3002                       // Ideographic full stop (。)
            || (cp >= 0x2018 && cp <= 0x201F);    // Curly single/double quotes
    }

    /**
     * Suggest the ASCII replacement for a full-width code point.
     */
    static String suggestAscii(int cp) {
        if (cp >= 0xFF01 && cp <= 0xFF5E) {
            return String.valueOf((char) (cp - 0xFEE0));
        }
        if (cp == 0x3000) {
            return " ";
        }
        if (cp == 0x3001) {
            return ",";
        }
        if (cp == 0x3002) {
            return ".";
        }
        if (cp >= 0x2018 && cp <= 0x201F) {
            if (cp == 0x201A) {
                return ",";
            }
            if (cp == 0x201C || cp == 0x201D || cp == 0x201E || cp == 0x201F) {
                return "\"";
            }
            return "'"; // 0x2018, 0x2019, 0x201B
        }
        return "?";
    }

    /**
     * Format a single hit into a human-readable error message line.
     *
     * @param hit the hit to format
     * @return message like {@code Full-width character '；' (U+FF1B) at line 3, col 67
     *         outside string literal/comment - replace with ASCII ';'}
     */
    public static String formatHit(Hit hit) {
        return "Full-width character '" + hit.getCharacter() + "' (U+"
            + String.format("%04X", hit.getCodePoint()) + ") at line " + hit.getLine()
            + ", col " + hit.getCol() + " outside string literal/comment - replace with ASCII '"
            + suggestAscii(hit.getCodePoint()) + "'";
    }

    // ==================== Hit ====================

    /**
     * A single full-width character hit found outside a lexical context.
     */
    public static class Hit {

        private final int    line;
        private final int    col;
        private final String character;
        private final int    codePoint;

        public Hit(int line, int col, String character, int codePoint) {
            this.line = line;
            this.col = col;
            this.character = character;
            this.codePoint = codePoint;
        }

        public int getLine() {
            return line;
        }

        public int getCol() {
            return col;
        }

        public String getCharacter() {
            return character;
        }

        public int getCodePoint() {
            return codePoint;
        }

        @Override
        public String toString() {
            return formatHit(this);
        }
    }
}
