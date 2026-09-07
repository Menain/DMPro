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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Governance SQL hash utility — dialect-neutral, byte-level immutable contract.
 * Used by preSubmit (Phase 4), revision freeze (Phase 4 Wave 2),
 * promotion gate (Phase 6), and gate-2 re-verification (Phase 7).
 *
 * Normalization rules (design D4, must not diverge):
 * 1. Split by line (\r\n, \r, \n all treated as line breaks)
 * 2. Each line trimmed
 * 3. Empty lines filtered out
 * 4. Consecutive whitespace within a line collapsed to a single space
 * 5. Lines joined with "\n"
 * 6. SHA-256(UTF-8 bytes) → lowercase hex (64 chars)
 */
public final class GovSqlHashUtils {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private GovSqlHashUtils() {
    }

    public static String normalize(String sql) {
        if (sql == null || sql.isBlank()) {
            return "";
        }
        String[] lines = sql.split("\\r\\n|\\r|\\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim().replaceAll("\\s+", " ");
            if (trimmed.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(trimmed);
        }
        return sb.toString();
    }

    public static String hash(String sql) {
        String normalized = normalize(sql);
        return sha256Hex(normalized.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            char[] hexChars = new char[digest.length * 2];
            for (int i = 0; i < digest.length; i++) {
                int v = digest[i] & 0xFF;
                hexChars[i * 2] = HEX[v >>> 4];
                hexChars[i * 2 + 1] = HEX[v & 0x0F];
            }
            return new String(hexChars);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
