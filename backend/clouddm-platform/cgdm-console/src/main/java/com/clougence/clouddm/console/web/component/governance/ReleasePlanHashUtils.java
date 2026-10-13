/*
 * Copyright 2026 杭州开云集致科技有限公司
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://apache.org/licenses/LICENSE-2.0
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseStmtSegmentDO;

/**
 * ADR-013: canonical hash of a release stmt's frozen segment-plan manifest —
 * the plan-integrity pin stored in {@code dm_prod_release_stmt.plan_hash}.
 * SINGLE implementation for the release chain (GovSqlHashUtils discipline for SQL text):
 * the merge-time propagate writes the pin, the M3 dispatch gate re-computes it from the
 * manifest rows and compares (mismatch → RELEASE_PLAN_DRIFT, never overridable).
 *
 * Canonical form (design D1, must not diverge):
 * 1. Rows sorted by seg_idx ascending (input order irrelevant; the input list is not mutated)
 * 2. Per row, the PLAN columns joined with "|":
 *    {@code segIdx|type|from|to|stmtIndex|table|rows|sql|batchKeyColumn|batchKeyType|idempotent}
 *    — null renders as the empty string; idempotent renders as "1"/"0"/"";
 *    sql_text participates AS-IS (frozen byte copy — no whitespace normalization: the pin
 *    compares manifest rows against themselves, never re-derives from source text)
 * 3. Rows joined with "\n"
 * 4. SHA-256(UTF-8 bytes) → lowercase hex (64 chars) — same algorithm contract as
 *    GovSqlHashUtils
 *
 * RUNTIME columns (status/task_id/session_id/job_biz_id/summary/error/checkpoint/
 * started_at/finished_at) and the identity columns (id/release_id/release_stmt_id)
 * NEVER participate — the plan freeze does not include runtime state, so M2 segment
 * execution can mutate runtime columns without invalidating the pin.
 *
 * Empty/null row list → null (no plan → no pin; mirrors the null-plan legacy semantics —
 * callers write plan_hash only when a manifest was propagated).
 */
public final class ReleasePlanHashUtils {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private ReleasePlanHashUtils() {
    }

    public static String hash(List<DmProdReleaseStmtSegmentDO> rows) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        List<DmProdReleaseStmtSegmentDO> ordered = new ArrayList<>(rows);
        ordered.sort(Comparator.comparingInt(DmProdReleaseStmtSegmentDO::getSegIdx));
        StringBuilder sb = new StringBuilder();
        for (DmProdReleaseStmtSegmentDO r : ordered) {
            if (r == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(r.getSegIdx()).append('|')
                .append(nz(r.getType())).append('|')
                .append(nz(r.getStmtRangeFrom())).append('|')
                .append(nz(r.getStmtRangeTo())).append('|')
                .append(nz(r.getStmtIndex())).append('|')
                .append(nz(r.getTableName())).append('|')
                .append(nz(r.getRowsEstimated())).append('|')
                .append(nz(r.getSqlText())).append('|')
                .append(nz(r.getBatchKeyColumn())).append('|')
                .append(nz(r.getBatchKeyType())).append('|')
                .append(r.getIdempotent() == null ? "" : (r.getIdempotent() ? "1" : "0"));
        }
        return sha256Hex(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String nz(Object v) {
        return v == null ? "" : String.valueOf(v);
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
