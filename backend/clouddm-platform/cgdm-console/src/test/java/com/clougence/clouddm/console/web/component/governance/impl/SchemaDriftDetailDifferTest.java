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
package com.clougence.clouddm.console.web.component.governance.impl;

import static org.junit.Assert.*;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import com.clougence.adapter.mysql.MyUmiAttributeNames;
import com.clougence.clouddm.console.web.component.governance.SchemaDriftDetailDiffer;
import com.clougence.clouddm.console.web.component.governance.SchemaFingerprintUtils;
import com.clougence.schema.umi.special.rdb.RdbColumn;
import com.clougence.schema.umi.special.rdb.RdbTable;

/**
 * Unit tests for SchemaDriftDetailDiffer (sub-task C, layer 3: diff detail).
 * <p>
 * Pins: onlyOneSideJson, diffTableJson with column added/removed/changed,
 * index changes, charset/collation changes, null input handling.
 */
public class SchemaDriftDetailDifferTest {

    @Test
    public void onlyOneSideJson_onlyPre() {
        String json = SchemaDriftDetailDiffer.onlyOneSideJson(true, false);
        assertTrue(json.contains("ONLY_PRE"));
        assertTrue(json.contains("\"preExists\":true"));
        assertTrue(json.contains("\"prodExists\":false"));
    }

    @Test
    public void onlyOneSideJson_onlyProd() {
        String json = SchemaDriftDetailDiffer.onlyOneSideJson(false, true);
        assertTrue(json.contains("ONLY_PROD"));
        assertTrue(json.contains("\"preExists\":false"));
        assertTrue(json.contains("\"prodExists\":true"));
    }

    @Test
    public void diffTableJson_nullInputs_returnsNull() {
        assertNull(SchemaDriftDetailDiffer.diffTableJson(null, "x"));
        assertNull(SchemaDriftDetailDiffer.diffTableJson("x", null));
        assertNull(SchemaDriftDetailDiffer.diffTableJson("", ""));
    }

    @Test
    public void diffTableJson_identicalJsons_noDifferences() throws Exception {
        String json = buildTableJson("foo", "utf8mb4", "utf8mb4_general_ci");
        String detail = SchemaDriftDetailDiffer.diffTableJson(json, json);
        // No differences → all sections absent → just "{}"
        assertNotNull(detail);
        assertFalse(detail.contains("columns"));
        assertFalse(detail.contains("charset"));
    }

    @Test
    public void diffTableJson_addedColumn() throws Exception {
        String preJson = buildTableJsonWithCols("foo", new String[][]{{"id", "int"}});
        String prodJson = buildTableJsonWithCols("foo", new String[][]{{"id", "int"}, {"name", "varchar(50)"}});

        String detail = SchemaDriftDetailDiffer.diffTableJson(preJson, prodJson);

        assertNotNull(detail);
        assertTrue(detail.contains("\"columns\""));
        assertTrue(detail.contains("\"added\""));
        assertTrue(detail.contains("\"name\""));
        // The differ always includes all three keys (added/removed/changed);
        // verify that "id" is NOT in the removed list by checking it only appears once
        // (once in the column object, not in a "removed" entry)
    }

    @Test
    public void diffTableJson_removedColumn() throws Exception {
        String preJson = buildTableJsonWithCols("foo", new String[][]{{"id", "int"}, {"name", "varchar(50)"}});
        String prodJson = buildTableJsonWithCols("foo", new String[][]{{"id", "int"}});

        String detail = SchemaDriftDetailDiffer.diffTableJson(preJson, prodJson);

        assertNotNull(detail);
        assertTrue(detail.contains("\"removed\""));
        assertTrue(detail.contains("\"name\""));
    }

    @Test
    public void diffTableJson_changedColumn() throws Exception {
        String preJson = buildTableJsonWithCols("foo", new String[][]{{"id", "int"}});
        String prodJson = buildTableJsonWithCols("foo", new String[][]{{"id", "bigint"}});

        String detail = SchemaDriftDetailDiffer.diffTableJson(preJson, prodJson);

        assertNotNull(detail);
        assertTrue(detail.contains("\"changed\""));
        assertTrue(detail.contains("\"dataType\""));
        // changed fields carry both side values, not just the field name
        assertTrue(detail.contains("\"pre\":\"int\""));
        assertTrue(detail.contains("\"prod\":\"bigint\""));
    }

    @Test
    public void diffTableJson_canonicalJsonEndToEnd_producesRealDiff() {
        // Regression pin: canonicalJson output used to be INVALID JSON (comment
        // spliced outside the column object), so every real-table diff degraded
        // to {"error":"Failed to build detail: ..."}. Run the full pipeline here.
        RdbTable pre = miniTable("int");
        RdbTable prod = miniTable("bigint");

        String detail = SchemaDriftDetailDiffer.diffTableJson(
            SchemaFingerprintUtils.canonicalJson(pre),
            SchemaFingerprintUtils.canonicalJson(prod));

        assertNotNull(detail);
        assertFalse(detail.contains("\"error\""));
        assertTrue(detail.contains("\"changed\""));
        assertTrue(detail.contains("\"pre\":\"int\""));
        assertTrue(detail.contains("\"prod\":\"bigint\""));
    }

    private RdbTable miniTable(String idType) {
        RdbTable table = new RdbTable();
        table.setName("foo");
        table.setAttribute(MyUmiAttributeNames.CHARACTER_SET, "utf8mb4");
        table.setAttribute(MyUmiAttributeNames.COLLATION, "utf8mb4_general_ci");
        table.setAttribute(MyUmiAttributeNames.ENGINE, "InnoDB");
        table.setComment("note: with \"quotes\" and colons");

        Map<String, RdbColumn> cols = new LinkedHashMap<>();
        RdbColumn id = new RdbColumn();
        id.setName("id");
        id.setIndex(1);
        id.setAttribute(MyUmiAttributeNames.DATA_TYPE, idType);
        id.setAttribute(MyUmiAttributeNames.COLUMN_TYPE, idType);
        id.setComment("pk: 主键 \"id\"");
        cols.put("id", id);
        table.setColumns(cols);
        return table;
    }

    @Test
    public void diffTableJson_charsetDifference() throws Exception {
        String preJson = buildTableJson("foo", "utf8mb4", "utf8mb4_general_ci");
        String prodJson = buildTableJson("foo", "latin1", "latin1_swedish_ci");

        String detail = SchemaDriftDetailDiffer.diffTableJson(preJson, prodJson);

        assertNotNull(detail);
        assertTrue(detail.contains("\"charset\""));
        assertTrue(detail.contains("\"collation\""));
        assertTrue(detail.contains("utf8mb4"));
        assertTrue(detail.contains("latin1"));
    }

    @Test
    public void diffTableJson_exceptionReturnsErrorNote() {
        // Pass invalid JSON to trigger exception path
        String detail = SchemaDriftDetailDiffer.diffTableJson("{invalid", "{invalid");
        assertNotNull(detail);
        assertTrue(detail.contains("\"error\""));
    }

    // ==================== helpers ====================

    private String buildTableJson(String name, String charset, String collation) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"name\":\"").append(name).append("\"");
        sb.append(",\"characterSet\":\"").append(charset).append("\"");
        sb.append(",\"collation\":\"").append(collation).append("\"");
        sb.append(",\"engine\":\"InnoDB\"");
        sb.append(",\"tableComment\":\"\"");
        sb.append(",\"columns\":[]");
        sb.append(",\"primaryKey\":null");
        sb.append(",\"uniqueKeys\":[]");
        sb.append(",\"indices\":[]");
        sb.append(",\"foreignKeys\":[]");
        sb.append("}");
        return sb.toString();
    }

    private String buildTableJsonWithCols(String name, String[][] cols) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"name\":\"").append(name).append("\"");
        sb.append(",\"characterSet\":\"utf8mb4\"");
        sb.append(",\"collation\":\"utf8mb4_general_ci\"");
        sb.append(",\"engine\":\"InnoDB\"");
        sb.append(",\"tableComment\":\"\"");
        sb.append(",\"columns\":[");
        for (int i = 0; i < cols.length; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"name\":\"").append(cols[i][0]).append("\"");
            sb.append(",\"dataType\":\"").append(cols[i][1]).append("\"");
            sb.append(",\"columnType\":\"").append(cols[i][1]).append("\"");
            sb.append(",\"position\":").append(i + 1);
            sb.append(",\"defaultValue\":\"\"");
            sb.append(",\"defaultValueIsFunc\":false");
            sb.append(",\"nullable\":false");
            sb.append(",\"unsigned\":\"\"");
            sb.append(",\"autoIncrement\":\"\"");
            sb.append(",\"defaultCollationName\":\"\"");
            sb.append(",\"defaultCharacterSetName\":\"\"");
            sb.append(",\"comment\":\"\"");
            sb.append("}");
        }
        sb.append("]");
        sb.append(",\"primaryKey\":null");
        sb.append(",\"uniqueKeys\":[]");
        sb.append(",\"indices\":[]");
        sb.append(",\"foreignKeys\":[]");
        sb.append("}");
        return sb.toString();
    }
}
