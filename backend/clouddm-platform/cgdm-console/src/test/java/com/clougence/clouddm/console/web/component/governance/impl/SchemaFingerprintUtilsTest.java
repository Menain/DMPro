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
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.clougence.adapter.mysql.MyUmiAttributeNames;
import com.clougence.clouddm.console.web.component.governance.SchemaFingerprintUtils;
import com.clougence.schema.umi.special.rdb.RdbColumn;
import com.clougence.schema.umi.special.rdb.RdbForeignKey;
import com.clougence.schema.umi.special.rdb.RdbIndex;
import com.clougence.schema.umi.special.rdb.RdbIndexType;
import com.clougence.schema.umi.special.rdb.RdbPrimaryKey;
import com.clougence.schema.umi.special.rdb.RdbTable;
import com.clougence.schema.umi.special.rdb.RdbUniqueKey;
import com.clougence.schema.umi.struts.constraint.NonNull;

/**
 * Unit tests for SchemaFingerprintUtils (sub-task A, layer 1: normalization).
 * <p>
 * Pins: byte-stability, noise exclusion (AUTO_INCREMENT counter, data lengths),
 * UNSIGNED/AUTO_INCREMENT from attributes, WARN-level (ENGINE/comment) excluded
 * from fingerprint but present in schema_json.
 */
public class SchemaFingerprintUtilsTest {

    // ==================== helpers ====================

    private RdbTable buildSimpleTable() {
        RdbTable table = new RdbTable();
        table.setName("users");
        table.setAttribute(MyUmiAttributeNames.CHARACTER_SET, "utf8mb4");
        table.setAttribute(MyUmiAttributeNames.COLLATION, "utf8mb4_general_ci");
        table.setAttribute(MyUmiAttributeNames.ENGINE, "InnoDB");
        table.setComment("user table");

        Map<String, RdbColumn> cols = new LinkedHashMap<>();
        RdbColumn id = new RdbColumn();
        id.setName("id");
        id.setIndex(1);
        id.setAttribute(MyUmiAttributeNames.DATA_TYPE, "int");
        id.setAttribute(MyUmiAttributeNames.COLUMN_TYPE, "int");
        id.setAttribute(MyUmiAttributeNames.UNSIGNED, "true");
        id.setAttribute(com.clougence.schema.umi.special.rdb.RdbAttributeNames.AUTO_INCREMENT, "true");
        id.addConstraint(new NonNull());
        cols.put("id", id);

        RdbColumn name = new RdbColumn();
        name.setName("name");
        name.setIndex(2);
        name.setAttribute(MyUmiAttributeNames.DATA_TYPE, "varchar");
        name.setAttribute(MyUmiAttributeNames.COLUMN_TYPE, "varchar(255)");
        name.setCharLength(255L);
        name.setComment("user name");
        cols.put("name", name);

        table.setColumns(cols);
        return table;
    }

    // ==================== byte stability ====================

    @Test
    public void fingerprint_byteStable_sameTableProducesSameFingerprint() {
        RdbTable t1 = buildSimpleTable();
        RdbTable t2 = buildSimpleTable();
        assertEquals(SchemaFingerprintUtils.fingerprint(t1), SchemaFingerprintUtils.fingerprint(t2));
    }

    @Test
    public void fingerprint_byteStable_columnOrderIrrelevant() {
        RdbTable t1 = buildSimpleTable();
        // Build t2 with reversed column map insertion order but identical attributes
        RdbTable t2 = new RdbTable();
        t2.setName("users");
        t2.setAttribute(MyUmiAttributeNames.CHARACTER_SET, "utf8mb4");
        t2.setAttribute(MyUmiAttributeNames.COLLATION, "utf8mb4_general_ci");
        t2.setAttribute(MyUmiAttributeNames.ENGINE, "InnoDB");
        t2.setComment("user table");

        Map<String, RdbColumn> cols = new LinkedHashMap<>();
        RdbColumn name = new RdbColumn();
        name.setName("name");
        name.setIndex(2);
        name.setAttribute(MyUmiAttributeNames.DATA_TYPE, "varchar");
        name.setAttribute(MyUmiAttributeNames.COLUMN_TYPE, "varchar(255)");
        name.setCharLength(255L);
        name.setComment("user name");
        cols.put("name", name);

        RdbColumn id = new RdbColumn();
        id.setName("id");
        id.setIndex(1);
        id.setAttribute(MyUmiAttributeNames.DATA_TYPE, "int");
        id.setAttribute(MyUmiAttributeNames.COLUMN_TYPE, "int");
        id.setAttribute(MyUmiAttributeNames.UNSIGNED, "true");
        id.setAttribute(com.clougence.schema.umi.special.rdb.RdbAttributeNames.AUTO_INCREMENT, "true");
        id.addConstraint(new NonNull());
        cols.put("id", id);

        t2.setColumns(cols);
        assertEquals(SchemaFingerprintUtils.fingerprint(t1), SchemaFingerprintUtils.fingerprint(t2));
    }

    // ==================== noise exclusion ====================

    @Test
    public void fingerprint_noiseExclusion_autoIncrementCounterDoesNotChange() {
        RdbTable t1 = buildSimpleTable();
        RdbTable t2 = buildSimpleTable();
        // AUTO_INCREMENT_STAR is a counter — should NOT affect fingerprint
        t2.setAttribute(MyUmiAttributeNames.AUTO_INCREMENT_STAR, "999");
        assertEquals(SchemaFingerprintUtils.fingerprint(t1), SchemaFingerprintUtils.fingerprint(t2));
    }

    @Test
    public void fingerprint_noiseExclusion_dataLengthDoesNotChange() {
        RdbTable t1 = buildSimpleTable();
        RdbTable t2 = buildSimpleTable();
        t2.setAttribute(MyUmiAttributeNames.DATA_LENGTH, "12345678");
        t2.setAttribute(MyUmiAttributeNames.DATA_ROW, "999");
        assertEquals(SchemaFingerprintUtils.fingerprint(t1), SchemaFingerprintUtils.fingerprint(t2));
    }

    // ==================== UNSIGNED / AUTO_INCREMENT from attributes ====================

    @Test
    public void fingerprint_unsignedFromAttributes_unsignedChangeBreaksFingerprint() {
        RdbTable t1 = buildSimpleTable();
        RdbTable t2 = buildSimpleTable();
        // Change unsigned from "true" to "false" on id column
        for (RdbColumn col : t2.getColumns().values()) {
            if ("id".equals(col.getName())) {
                col.setAttribute(MyUmiAttributeNames.UNSIGNED, "false");
            }
        }
        assertNotEquals(SchemaFingerprintUtils.fingerprint(t1), SchemaFingerprintUtils.fingerprint(t2));
    }

    @Test
    public void fingerprint_autoIncrementFromAttributes_autoIncChangeBreaksFingerprint() {
        RdbTable t1 = buildSimpleTable();
        RdbTable t2 = buildSimpleTable();
        for (RdbColumn col : t2.getColumns().values()) {
            if ("id".equals(col.getName())) {
                col.setAttribute(com.clougence.schema.umi.special.rdb.RdbAttributeNames.AUTO_INCREMENT, "false");
            }
        }
        assertNotEquals(SchemaFingerprintUtils.fingerprint(t1), SchemaFingerprintUtils.fingerprint(t2));
    }

    // ==================== WARN-level excluded from fingerprint ====================

    @Test
    public void fingerprint_warnLevelExcluded_engineChangeDoesNotChangeFingerprint() {
        RdbTable t1 = buildSimpleTable();
        RdbTable t2 = buildSimpleTable();
        t2.setAttribute(MyUmiAttributeNames.ENGINE, "MyISAM");
        assertEquals(SchemaFingerprintUtils.fingerprint(t1), SchemaFingerprintUtils.fingerprint(t2));
    }

    @Test
    public void fingerprint_warnLevelExcluded_commentChangeDoesNotChangeFingerprint() {
        RdbTable t1 = buildSimpleTable();
        RdbTable t2 = buildSimpleTable();
        t2.setComment("changed comment");
        // Table comment is WARN-level — should not change fingerprint
        assertEquals(SchemaFingerprintUtils.fingerprint(t1), SchemaFingerprintUtils.fingerprint(t2));
    }

    @Test
    public void fingerprint_warnLevelExcluded_columnCommentDoesNotChangeFingerprint() {
        RdbTable t1 = buildSimpleTable();
        RdbTable t2 = buildSimpleTable();
        for (RdbColumn col : t2.getColumns().values()) {
            if ("name".equals(col.getName())) {
                col.setComment("changed column comment");
            }
        }
        assertEquals(SchemaFingerprintUtils.fingerprint(t1), SchemaFingerprintUtils.fingerprint(t2));
    }

    // ==================== WARN-level in schema_json ====================

    @Test
    public void canonicalJson_warnLevelPresent_engineAndCommentInJson() {
        RdbTable table = buildSimpleTable();
        String json = SchemaFingerprintUtils.canonicalJson(table);
        assertNotNull(json);
        assertTrue(json.contains("InnoDB"));
        assertTrue(json.contains("user table"));
    }

    // ==================== BLOCK-level changes DO change fingerprint ====================

    @Test
    public void fingerprint_blockLevelChange_columnTypeChangeBreaksFingerprint() {
        RdbTable t1 = buildSimpleTable();
        RdbTable t2 = buildSimpleTable();
        for (RdbColumn col : t2.getColumns().values()) {
            if ("name".equals(col.getName())) {
                col.setAttribute(MyUmiAttributeNames.COLUMN_TYPE, "varchar(100)");
                col.setCharLength(100L);
            }
        }
        assertNotEquals(SchemaFingerprintUtils.fingerprint(t1), SchemaFingerprintUtils.fingerprint(t2));
    }

    @Test
    public void fingerprint_blockLevelChange_charsetChangeBreaksFingerprint() {
        RdbTable t1 = buildSimpleTable();
        RdbTable t2 = buildSimpleTable();
        t2.setAttribute(MyUmiAttributeNames.CHARACTER_SET, "latin1");
        assertNotEquals(SchemaFingerprintUtils.fingerprint(t1), SchemaFingerprintUtils.fingerprint(t2));
    }

    @Test
    public void fingerprint_blockLevelChange_nullableChangeBreaksFingerprint() {
        RdbTable t1 = buildSimpleTable();
        RdbTable t2 = buildSimpleTable();
        // Add NonNull to "name" column (was nullable before)
        for (RdbColumn col : t2.getColumns().values()) {
            if ("name".equals(col.getName())) {
                col.addConstraint(new NonNull());
            }
        }
        assertNotEquals(SchemaFingerprintUtils.fingerprint(t1), SchemaFingerprintUtils.fingerprint(t2));
    }

    // ==================== non-existent table sentinel ====================

    @Test
    public void fingerprint_nonExistentTable_returnsSentinel() {
        assertEquals(SchemaFingerprintUtils.NON_EXISTENT_FINGERPRINT, SchemaFingerprintUtils.fingerprint(null));
    }

    // ==================== index/PK/FK in fingerprint ====================

    @Test
    public void fingerprint_indexChange_breaksFingerprint() {
        RdbTable t1 = buildSimpleTable();
        RdbIndex idx = new RdbIndex();
        idx.setName("idx_name");
        idx.setType(RdbIndexType.Normal);
        idx.setColumnList(List.of("name"));
        t1.setIndices(List.of(idx));

        RdbTable t2 = buildSimpleTable();
        RdbIndex idx2 = new RdbIndex();
        idx2.setName("idx_name");
        idx2.setType(RdbIndexType.Unique);
        idx2.setColumnList(List.of("name"));
        t2.setIndices(List.of(idx2));

        assertNotEquals(SchemaFingerprintUtils.fingerprint(t1), SchemaFingerprintUtils.fingerprint(t2));
    }

    @Test
    public void fingerprint_pkChange_breaksFingerprint() {
        RdbTable t1 = buildSimpleTable();
        RdbPrimaryKey pk1 = new RdbPrimaryKey();
        pk1.setName("PRIMARY");
        pk1.setColumnList(List.of("id"));
        t1.setPrimaryKey(pk1);

        RdbTable t2 = buildSimpleTable();
        RdbPrimaryKey pk2 = new RdbPrimaryKey();
        pk2.setName("PRIMARY");
        pk2.setColumnList(List.of("name"));
        t2.setPrimaryKey(pk2);

        assertNotEquals(SchemaFingerprintUtils.fingerprint(t1), SchemaFingerprintUtils.fingerprint(t2));
    }
}
