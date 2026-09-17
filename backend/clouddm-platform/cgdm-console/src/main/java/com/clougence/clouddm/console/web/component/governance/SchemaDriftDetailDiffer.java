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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;

/**
 * Field-level canonical-JSON differ for schema drift patrol (sub-task C).
 * <p>
 * Parses two canonical JSON strings produced by
 * {@link SchemaFingerprintUtils#canonicalJson} and produces a human-readable
 * detail JSON showing per-section differences (columns/indexes/uks/fks/charset/collation).
 * <p>
 * The canonical JSON is guaranteed key-sorted and byte-stable (A's contract),
 * so the differ can rely on deterministic section names: columns, primaryKey,
 * uniqueKeys, indices, foreignKeys, characterSet, collation, engine, tableComment.
 */
public final class SchemaDriftDetailDiffer {

    private static final ObjectMapper SORTED_MAPPER = new ObjectMapper()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private SchemaDriftDetailDiffer() {
    }

    /**
     * Build a detail JSON for a table that exists on only one side.
     */
    public static String onlyOneSideJson(boolean preExists, boolean prodExists) {
        if (preExists && !prodExists) {
            return "{\"direction\":\"ONLY_PRE\",\"preExists\":true,\"prodExists\":false}";
        }
        if (prodExists && !preExists) {
            return "{\"direction\":\"ONLY_PROD\",\"preExists\":false,\"prodExists\":true}";
        }
        return "{\"preExists\":" + preExists + ",\"prodExists\":" + prodExists + "}";
    }

    /**
     * Diff two canonical JSON strings and produce a detail JSON.
     * Returns null if either JSON is null or empty.
     */
    public static String diffTableJson(String preJson, String prodJson) {
        if (preJson == null || preJson.isEmpty() || prodJson == null || prodJson.isEmpty()) {
            return null;
        }
        try {
            JsonNode pre = SORTED_MAPPER.readTree(preJson);
            JsonNode prod = SORTED_MAPPER.readTree(prodJson);

            Map<String, Object> detail = new LinkedHashMap<>();

            // Table-level scalar fields
            diffScalar(detail, "charset", pre, prod, "characterSet");
            diffScalar(detail, "collation", pre, prod, "collation");
            diffScalar(detail, "engine", pre, prod, "engine");
            diffScalar(detail, "tableComment", pre, prod, "tableComment");

            // Columns
            diffNamedArray(detail, "columns", pre.path("columns"), prod.path("columns"));

            // Primary key
            diffPrimaryKey(detail, pre.path("primaryKey"), prod.path("primaryKey"));

            // Unique keys
            diffNamedArray(detail, "uks", pre.path("uniqueKeys"), prod.path("uniqueKeys"));

            // Indices
            diffNamedArray(detail, "indexes", pre.path("indices"), prod.path("indices"));

            // Foreign keys
            diffNamedArray(detail, "fks", pre.path("foreignKeys"), prod.path("foreignKeys"));

            return SORTED_MAPPER.writeValueAsString(detail);
        } catch (Exception e) {
            // Differ failure must never break patrol — return a simple note
            return "{\"error\":\"Failed to build detail: " + escape(e.getMessage()) + "\"}";
        }
    }

    // ==================== section differs ====================

    private static void diffScalar(Map<String, Object> detail, String label,
                                   JsonNode pre, JsonNode prod, String field) {
        String preVal = textOrEmpty(pre.path(field));
        String prodVal = textOrEmpty(prod.path(field));
        if (!preVal.equals(prodVal)) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("pre", preVal);
            entry.put("prod", prodVal);
            detail.put(label, entry);
        }
    }

    /**
     * Diff two arrays of named objects (columns, indices, uks, fks).
     * Produces added/removed/changed sections keyed by object name.
     */
    private static void diffNamedArray(Map<String, Object> detail, String label,
                                      JsonNode preArr, JsonNode prodArr) {
        Map<String, JsonNode> preMap = indexByName(preArr);
        Map<String, JsonNode> prodMap = indexByName(prodArr);

        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<Map<String, Object>> changed = new ArrayList<>();

        for (String name : unionKeys(preMap, prodMap)) {
            JsonNode preItem = preMap.get(name);
            JsonNode prodItem = prodMap.get(name);
            if (preItem == null && prodItem != null) {
                added.add(name);
            } else if (preItem != null && prodItem == null) {
                removed.add(name);
            } else if (preItem != null && !preItem.equals(prodItem)) {
                Map<String, Object> fields = diffFields(preItem, prodItem);
                if (!fields.isEmpty()) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("name", name);
                    entry.put("fields", fields);
                    changed.add(entry);
                }
            }
        }

        if (!added.isEmpty() || !removed.isEmpty() || !changed.isEmpty()) {
            Map<String, Object> section = new LinkedHashMap<>();
            section.put("added", added);
            section.put("removed", removed);
            section.put("changed", changed);
            detail.put(label, section);
        }
    }

    private static void diffPrimaryKey(Map<String, Object> detail, JsonNode prePk, JsonNode prodPk) {
        if (prePk.isNull() && prodPk.isNull()) {
            return;
        }
        if (prePk.isNull() || prodPk.isNull()) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("pre", prePk.isNull() ? "absent" : "present");
            entry.put("prod", prodPk.isNull() ? "absent" : "present");
            detail.put("primaryKey", entry);
            return;
        }
        if (!prePk.equals(prodPk)) {
            Map<String, Object> fields = diffFields(prePk, prodPk);
            if (!fields.isEmpty()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("fields", fields);
                detail.put("primaryKey", entry);
            }
        }
    }

    // ==================== helpers ====================

    private static Map<String, JsonNode> indexByName(JsonNode arr) {
        Map<String, JsonNode> map = new TreeMap<>();
        if (arr == null || !arr.isArray()) {
            return map;
        }
        for (JsonNode item : arr) {
            String name = textOrEmpty(item.path("name"));
            if (!name.isEmpty()) {
                map.put(name, item);
            }
        }
        return map;
    }

    private static List<String> unionKeys(Map<String, JsonNode> a, Map<String, JsonNode> b) {
        List<String> keys = new ArrayList<>();
        TreeMap<String, JsonNode> sorted = new TreeMap<>();
        sorted.putAll(a);
        sorted.putAll(b);
        keys.addAll(sorted.keySet());
        return keys;
    }

    /**
     * Per-field differences as {fieldName: {"pre": v, "prod": v}} so the detail
     * JSON is self-explanatory — a field-name-only list told readers THAT a
     * column changed but not what it changed from/to. A null side means the
     * field is absent on that side.
     */
    private static Map<String, Object> diffFields(JsonNode a, JsonNode b) {
        Map<String, Object> fields = new LinkedHashMap<>();
        Iterator<String> names = a.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            JsonNode aVal = a.get(name);
            JsonNode bVal = b.get(name);
            if (bVal == null || !aVal.equals(bVal)) {
                fields.put(name, preProdValues(aVal, bVal));
            }
        }
        // Check for fields only in b
        Iterator<String> bNames = b.fieldNames();
        while (bNames.hasNext()) {
            String name = bNames.next();
            if (a.get(name) == null) {
                fields.put(name, preProdValues(null, b.get(name)));
            }
        }
        return fields;
    }

    private static Map<String, Object> preProdValues(JsonNode pre, JsonNode prod) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("pre", plainValue(pre));
        entry.put("prod", plainValue(prod));
        return entry;
    }

    /** Text nodes unwrap to plain strings; numbers/booleans/arrays serialize as-is. */
    private static Object plainValue(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        return node;
    }

    private static String textOrEmpty(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "";
        }
        return node.asText("");
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
}
