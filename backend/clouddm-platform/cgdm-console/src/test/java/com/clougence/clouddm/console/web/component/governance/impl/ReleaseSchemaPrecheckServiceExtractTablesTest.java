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

import java.util.Set;

import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.console.web.component.analysis.QueryAnalysisService;
import com.clougence.clouddm.console.web.component.dsconfig.DmDsConfigService;
import com.clougence.clouddm.console.web.component.governance.ReleaseSchemaPrecheckService;
import com.clougence.clouddm.console.web.component.schema.DsSchemaService;
import com.clougence.clouddm.platform.dal.access.DataSourceDal;
import com.clougence.clouddm.platform.dal.access.SystemDal;

/**
 * Unit tests for extractTables (sub-task A, layer 7: B interface increment).
 * <p>
 * Pins: consistency with B's internal table extraction (regex-based), various
 * SQL statement types, empty/blank input, multi-statement SQL.
 */
public class ReleaseSchemaPrecheckServiceExtractTablesTest {

    private ReleaseSchemaPrecheckServiceImpl service;

    @Before
    public void setUp() {
        service = new ReleaseSchemaPrecheckServiceImpl();
        // Dependencies not needed for extractTables (no split/DS fetch)
        ReflectionTestUtils.setField(service, "dsSchemaService", (DsSchemaService) null);
    }

    @Test
    public void extractTables_createTable() {
        Set<String> tables = service.extractTables("CREATE TABLE foo (id int)");
        assertTrue(tables.contains("foo"));
        assertEquals(1, tables.size());
    }

    @Test
    public void extractTables_dropTable() {
        Set<String> tables = service.extractTables("DROP TABLE bar");
        assertTrue(tables.contains("bar"));
    }

    @Test
    public void extractTables_alterTable() {
        Set<String> tables = service.extractTables("ALTER TABLE baz ADD COLUMN c varchar(10)");
        assertTrue(tables.contains("baz"));
    }

    @Test
    public void extractTables_insert() {
        Set<String> tables = service.extractTables("INSERT INTO mytable VALUES (1)");
        assertTrue(tables.contains("mytable"));
    }

    @Test
    public void extractTables_update() {
        Set<String> tables = service.extractTables("UPDATE mytable SET col = 1");
        assertTrue(tables.contains("mytable"));
    }

    @Test
    public void extractTables_delete() {
        Set<String> tables = service.extractTables("DELETE FROM mytable WHERE id = 1");
        assertTrue(tables.contains("mytable"));
    }

    @Test
    public void extractTables_multiStatement() {
        Set<String> tables = service.extractTables(
            "CREATE TABLE t1 (id int); ALTER TABLE t2 ADD COLUMN c int; DROP TABLE t3");
        assertTrue(tables.contains("t1"));
        assertTrue(tables.contains("t2"));
        assertTrue(tables.contains("t3"));
        assertEquals(3, tables.size());
    }

    @Test
    public void extractTables_backtickQuoted() {
        Set<String> tables = service.extractTables("CREATE TABLE `my table` (id int)");
        assertTrue(tables.contains("my table"));
    }

    @Test
    public void extractTables_qualifiedName() {
        Set<String> tables = service.extractTables("ALTER TABLE `mydb`.`mytable` ADD COLUMN c int");
        assertTrue(tables.contains("mytable"));
    }

    @Test
    public void extractTables_blankInput() {
        Set<String> tables = service.extractTables("");
        assertTrue(tables.isEmpty());
    }

    @Test
    public void extractTables_nullInput() {
        Set<String> tables = service.extractTables(null);
        assertTrue(tables.isEmpty());
    }

    @Test
    public void extractTables_createIndex() {
        Set<String> tables = service.extractTables("CREATE INDEX idx_name ON mytable (col)");
        assertTrue(tables.contains("mytable"));
    }

    @Test
    public void extractTables_renameTable() {
        Set<String> tables = service.extractTables("RENAME TABLE old_table TO new_table");
        assertTrue(tables.contains("old_table"));
        assertTrue(tables.contains("new_table"));
    }

    @Test
    public void extractTables_duplicateTable_deduplicated() {
        Set<String> tables = service.extractTables(
            "ALTER TABLE foo ADD COLUMN c1 int; ALTER TABLE foo ADD COLUMN c2 int");
        assertTrue(tables.contains("foo"));
        assertEquals(1, tables.size());
    }
}
