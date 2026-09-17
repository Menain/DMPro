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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.clougence.adapter.mysql.MyUmiAttributeNames;
import com.clougence.schema.umi.special.rdb.RdbAttributeNames;
import com.clougence.schema.umi.special.rdb.RdbColumn;
import com.clougence.schema.umi.special.rdb.RdbForeignKey;
import com.clougence.schema.umi.special.rdb.RdbIndex;
import com.clougence.schema.umi.special.rdb.RdbIndexType;
import com.clougence.schema.umi.special.rdb.RdbPrimaryKey;
import com.clougence.schema.umi.special.rdb.RdbTable;
import com.clougence.schema.umi.special.rdb.RdbUniqueKey;
import com.clougence.schema.umi.struts.constraint.NonNull;
import com.clougence.schema.umi.struts.AttributeUmiData;
import com.clougence.schema.umi.struts.UmiAttributeNames;

/**
 * Canonical JSON + fingerprint for schema snapshots (sub-task A).
 * <p>
 * Produces a byte-stable, deterministic JSON from an {@link RdbTable} and a
 * SHA-256 fingerprint over the BLOCK-level portion of that JSON.
 * <p>
 * Field classification:
 * <ul>
 *   <li><b>BLOCK-level</b> (enters fingerprint): table name/charset/collation,
 *       columns (name/type/position/precision/nullability/default/unsigned/
 *       auto_increment/charset/collation), PK, UK, indices, FK.</li>
 *   <li><b>WARN-level</b> (enters schema_json, excluded from fingerprint):
 *       table ENGINE, table comment, column comment. Changes to these surface
 *       as WARN at the diff gate but never BLOCK.</li>
 *   <li><b>Environment noise</b> (excluded entirely): AUTO_INCREMENT counter,
 *       row counts, timestamps, data lengths.</li>
 * </ul>
 * <p>
 * Critical: UNSIGNED and AUTO_INCREMENT are read from the MySQL plugin's
 * attribute bag (MyUmiAttributeNames.UNSIGNED / RdbAttributeNames.AUTO_INCREMENT),
 * NOT from the typed RdbColumn fields — the MySQL plugin never sets
 * {@code numericUnsigned} or {@code byteLength}.
 */
public final class SchemaFingerprintUtils {

    /** Sentinel fingerprint for a non-existent table (exists_flag=0). */
    public static final String NON_EXISTENT_FINGERPRINT = sha256Hex("NON_EXISTENT_TABLE".getBytes(StandardCharsets.UTF_8));

    private SchemaFingerprintUtils() {
    }

    // ==================== public API ====================

    /**
     * Build the full canonical JSON for a table (BLOCK + WARN level fields).
     * Used for storage (schema_json column) and WARN-level diff comparison.
     * Returns null if table is null.
     */
    public static String canonicalJson(RdbTable table) {
        if (table == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("{");

        // Table-level
        sb.append("\"name\":\"").append(escape(table.getName())).append("\"");
        sb.append(",\"characterSet\":\"").append(escape(attr(table, MyUmiAttributeNames.CHARACTER_SET))).append("\"");
        sb.append(",\"collation\":\"").append(escape(attr(table, MyUmiAttributeNames.COLLATION))).append("\"");
        // WARN-level: ENGINE and comment
        sb.append(",\"engine\":\"").append(escape(attr(table, MyUmiAttributeNames.ENGINE))).append("\"");
        sb.append(",\"tableComment\":\"").append(escape(table.getComment())).append("\"");

        sb.append(",\"columns\":[");
        List<RdbColumn> cols = sortedColumns(table);
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(canonicalColumn(cols.get(i)));
        }
        sb.append("]");

        sb.append(",\"primaryKey\":");
        sb.append(canonicalPk(table.getPrimaryKey()));

        sb.append(",\"uniqueKeys\":[");
        List<RdbUniqueKey> uks = sortedUks(table);
        for (int i = 0; i < uks.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(canonicalUk(uks.get(i)));
        }
        sb.append("]");

        sb.append(",\"indices\":[");
        List<RdbIndex> idxs = sortedIdxs(table);
        for (int i = 0; i < idxs.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(canonicalIdx(idxs.get(i)));
        }
        sb.append("]");

        sb.append(",\"foreignKeys\":[");
        List<RdbForeignKey> fks = sortedFks(table);
        for (int i = 0; i < fks.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(canonicalFk(fks.get(i)));
        }
        sb.append("]");

        sb.append("}");
        return sb.toString();
    }

    /**
     * Compute the BLOCK-level fingerprint (SHA-256 lowercase hex).
     * Excludes WARN-level fields (ENGINE, comments) so their changes never BLOCK.
     * For a non-existent table, use {@link #NON_EXISTENT_FINGERPRINT}.
     */
    public static String fingerprint(RdbTable table) {
        if (table == null) {
            return NON_EXISTENT_FINGERPRINT;
        }
        String blockJson = blockJson(table);
        return sha256Hex(blockJson.getBytes(StandardCharsets.UTF_8));
    }

    // ==================== BLOCK-only JSON (for fingerprint) ====================

    /**
     * Build the BLOCK-level-only JSON (no ENGINE, no comments).
     * This is what the fingerprint is computed from.
     */
    static String blockJson(RdbTable table) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");

        sb.append("\"name\":\"").append(escape(table.getName())).append("\"");
        sb.append(",\"characterSet\":\"").append(escape(attr(table, MyUmiAttributeNames.CHARACTER_SET))).append("\"");
        sb.append(",\"collation\":\"").append(escape(attr(table, MyUmiAttributeNames.COLLATION))).append("\"");

        sb.append(",\"columns\":[");
        List<RdbColumn> cols = sortedColumns(table);
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(blockColumn(cols.get(i)));
        }
        sb.append("]");

        sb.append(",\"primaryKey\":");
        sb.append(canonicalPk(table.getPrimaryKey()));

        sb.append(",\"uniqueKeys\":[");
        List<RdbUniqueKey> uks = sortedUks(table);
        for (int i = 0; i < uks.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(canonicalUk(uks.get(i)));
        }
        sb.append("]");

        sb.append(",\"indices\":[");
        List<RdbIndex> idxs = sortedIdxs(table);
        for (int i = 0; i < idxs.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(canonicalIdx(idxs.get(i)));
        }
        sb.append("]");

        sb.append(",\"foreignKeys\":[");
        List<RdbForeignKey> fks = sortedFks(table);
        for (int i = 0; i < fks.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(canonicalFk(fks.get(i)));
        }
        sb.append("]");

        sb.append("}");
        return sb.toString();
    }

    // ==================== canonical builders (column: full + block) ====================

    private static String canonicalColumn(RdbColumn col) {
        // Full version includes comment (WARN-level). blockColumn() returns a
        // CLOSED '{...}' object — the comment must be spliced INSIDE it.
        // (Appending after the closing brace produced invalid JSON: every
        // detail-differ parse then died with "was expecting comma to separate
        // Array entries" and MISMATCH details degraded to an error note.)
        String block = blockColumn(col);
        return block.substring(0, block.length() - 1)
            + ",\"comment\":\"" + escape(col.getComment()) + "\"}";
    }

    /**
     * BLOCK-level column fields (no comment).
     */
    private static String blockColumn(RdbColumn col) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"name\":\"").append(escape(col.getName())).append("\"");

        // DATA_TYPE and COLUMN_TYPE from attributes (MySQL plugin sets these)
        sb.append(",\"dataType\":\"").append(escape(attr(col, MyUmiAttributeNames.DATA_TYPE))).append("\"");
        sb.append(",\"columnType\":\"").append(escape(attr(col, MyUmiAttributeNames.COLUMN_TYPE))).append("\"");

        sb.append(",\"position\":").append(col.getIndex() != null ? col.getIndex() : 0);

        if (col.getCharLength() != null) {
            sb.append(",\"charLength\":").append(col.getCharLength());
        }
        if (col.getNumericPrecision() != null) {
            sb.append(",\"numericPrecision\":").append(col.getNumericPrecision());
        }
        if (col.getNumericScale() != null) {
            sb.append(",\"numericScale\":").append(col.getNumericScale());
        }
        if (col.getDatetimePrecision() != null) {
            sb.append(",\"datetimePrecision\":").append(col.getDatetimePrecision());
        }

        sb.append(",\"defaultValue\":\"").append(escape(col.getDefaultValue())).append("\"");
        sb.append(",\"defaultValueIsFunc\":").append(col.getDefaultValueIsFunc() != null && col.getDefaultValueIsFunc());

        // nullable — NonNull constraint presence
        boolean nullable = !col.hasConstraint(NonNull.class);
        sb.append(",\"nullable\":").append(nullable);

        // UNSIGNED — from attribute bag (MySQL plugin uses attribute, not typed field)
        sb.append(",\"unsigned\":\"").append(escape(attr(col, MyUmiAttributeNames.UNSIGNED))).append("\"");

        // AUTO_INCREMENT — from attribute bag (boolean "true"/"false")
        sb.append(",\"autoIncrement\":\"").append(escape(attr(col, RdbAttributeNames.AUTO_INCREMENT))).append("\"");

        sb.append(",\"defaultCollationName\":\"").append(escape(attr(col, MyUmiAttributeNames.DEFAULT_COLLATION_NAME))).append("\"");
        sb.append(",\"defaultCharacterSetName\":\"").append(escape(attr(col, MyUmiAttributeNames.DEFAULT_CHARACTER_SET_NAME))).append("\"");

        sb.append("}");
        return sb.toString();
    }

    private static String canonicalPk(RdbPrimaryKey pk) {
        if (pk == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("{\"name\":\"").append(escape(pk.getName())).append("\"");
        sb.append(",\"columns\":").append(canonicalColumnList(pk.getColumnList()));
        sb.append("}");
        return sb.toString();
    }

    private static String canonicalUk(RdbUniqueKey uk) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"name\":\"").append(escape(uk.getName())).append("\"");
        sb.append(",\"columns\":").append(canonicalColumnList(uk.getColumnList()));
        sb.append("}");
        return sb.toString();
    }

    private static String canonicalIdx(RdbIndex idx) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"name\":\"").append(escape(idx.getName())).append("\"");
        RdbIndexType type = idx.getType();
        sb.append(",\"type\":\"").append(type != null ? type.getTypeName() : "").append("\"");
        sb.append(",\"columns\":").append(canonicalColumnList(idx.getColumnList()));
        sb.append("}");
        return sb.toString();
    }

    private static String canonicalFk(RdbForeignKey fk) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"name\":\"").append(escape(fk.getName())).append("\"");
        sb.append(",\"referenceTable\":\"").append(escape(fk.getReferenceTable())).append("\"");
        sb.append(",\"referenceSchema\":\"").append(escape(fk.getReferenceSchema())).append("\"");
        sb.append(",\"columns\":").append(canonicalColumnList(fk.getColumnList()));
        sb.append(",\"referenceMapping\":").append(canonicalRefMap(fk.getReferenceMapping()));
        if (fk.getUpdateRule() != null) {
            sb.append(",\"updateRule\":\"").append(escape(fk.getUpdateRule().name())).append("\"");
        }
        if (fk.getDeleteRule() != null) {
            sb.append(",\"deleteRule\":\"").append(escape(fk.getDeleteRule().name())).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    private static String canonicalColumnList(List<String> cols) {
        if (cols == null || cols.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(escape(cols.get(i))).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }

    private static String canonicalRefMap(Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return "{}";
        }
        TreeMap<String, String> sorted = new TreeMap<>(map);
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        boolean first = true;
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escape(e.getKey())).append("\":\"").append(escape(e.getValue())).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    // ==================== sorting (deterministic order) ====================

    private static List<RdbColumn> sortedColumns(RdbTable table) {
        Map<String, RdbColumn> cols = table.getColumns();
        if (cols == null || cols.isEmpty()) {
            return Collections.emptyList();
        }
        List<RdbColumn> list = new ArrayList<>(cols.values());
        list.sort((a, b) -> {
            int pa = a.getIndex() != null ? a.getIndex() : Integer.MAX_VALUE;
            int pb = b.getIndex() != null ? b.getIndex() : Integer.MAX_VALUE;
            if (pa != pb) return Integer.compare(pa, pb);
            return safeCompare(a.getName(), b.getName());
        });
        return list;
    }

    private static List<RdbUniqueKey> sortedUks(RdbTable table) {
        List<RdbUniqueKey> uks = table.getUniqueKeys();
        if (uks == null || uks.isEmpty()) {
            return Collections.emptyList();
        }
        List<RdbUniqueKey> list = new ArrayList<>(uks);
        list.sort((a, b) -> safeCompare(a.getName(), b.getName()));
        return list;
    }

    private static List<RdbIndex> sortedIdxs(RdbTable table) {
        List<RdbIndex> idxs = table.getIndices();
        if (idxs == null || idxs.isEmpty()) {
            return Collections.emptyList();
        }
        List<RdbIndex> list = new ArrayList<>(idxs);
        list.sort((a, b) -> safeCompare(a.getName(), b.getName()));
        return list;
    }

    private static List<RdbForeignKey> sortedFks(RdbTable table) {
        List<RdbForeignKey> fks = table.getForeignKeys();
        if (fks == null || fks.isEmpty()) {
            return Collections.emptyList();
        }
        List<RdbForeignKey> list = new ArrayList<>(fks);
        list.sort((a, b) -> safeCompare(a.getName(), b.getName()));
        return list;
    }

    // ==================== helpers ====================

    private static String attr(AttributeUmiData data, UmiAttributeNames name) {
        if (data == null || name == null) {
            return "";
        }
        String val = data.getAttribute(name);
        return val != null ? val : "";
    }

    private static int safeCompare(String a, String b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        return a.compareTo(b);
    }

    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            char[] hexChars = new char[digest.length * 2];
            char[] hex = "0123456789abcdef".toCharArray();
            for (int i = 0; i < digest.length; i++) {
                int v = digest[i] & 0xFF;
                hexChars[i * 2] = hex[v >>> 4];
                hexChars[i * 2 + 1] = hex[v & 0x0F];
            }
            return new String(hexChars);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
