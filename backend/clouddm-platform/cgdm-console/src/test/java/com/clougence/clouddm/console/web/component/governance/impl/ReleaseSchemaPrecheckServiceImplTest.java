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
package com.clougence.clouddm.console.web.component.governance.impl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.base.metadata.ds.DataSourceConfig;
import com.clougence.clouddm.console.web.component.analysis.QueryAnalysisService;
import com.clougence.clouddm.console.web.component.dsconfig.DmDsConfigService;
import com.clougence.clouddm.console.web.component.dsconfig.mode.DsLevels;
import com.clougence.clouddm.console.web.component.governance.PrecheckConclusion;
import com.clougence.clouddm.console.web.component.governance.SchemaViolation;
import com.clougence.clouddm.console.web.component.schema.DsSchemaService;
import com.clougence.clouddm.platform.dal.access.DataSourceDal;
import com.clougence.clouddm.platform.dal.access.SystemDal;
import com.clougence.clouddm.platform.dal.mapper.datasource.DmDsMapper;
import com.clougence.clouddm.platform.dal.model.datasource.DmDsDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseStmtDO;
import com.clougence.clouddm.sdk.sql.parser.SplitQueryType;
import com.clougence.clouddm.sdk.sql.parser.SplitScript;
import com.clougence.schema.umi.special.rdb.RdbColumn;
import com.clougence.schema.umi.special.rdb.RdbIndex;
import com.clougence.schema.umi.special.rdb.RdbTable;
import com.clougence.schema.umi.struts.UmiTypes;
import com.clougence.schema.umi.struts.Value;

/**
 * Unit tests for the schema precondition precheck engine.
 * Covers: classification, rule matrix, delta inference, degradation/WARN_SKIP,
 * and the BLOCK/WARN mode switch.
 * <p>
 * Pure Mockito + ReflectionTestUtils — no Spring context.
 */
public class ReleaseSchemaPrecheckServiceImplTest {

    private ReleaseSchemaPrecheckServiceImpl service;
    private DsSchemaService       dsSchemaService;
    private QueryAnalysisService   analysisService;
    private DmDsConfigService     configService;
    private DataSourceDal         dsDal;
    private SystemDal             systemDal;
    private DmDsMapper            dsMapper;

    private static final long   PROD_DS_ID = 10L;
    private static final long   DS_ENV_ID  = 1L;
    private static final String PROD_DB    = "prod_db";

    @Before
    public void setUp() {
        service = new ReleaseSchemaPrecheckServiceImpl();

        dsSchemaService = mock(DsSchemaService.class);
        analysisService = mock(QueryAnalysisService.class);
        configService = mock(DmDsConfigService.class);
        dsDal = mock(DataSourceDal.class);
        systemDal = mock(SystemDal.class);
        dsMapper = mock(DmDsMapper.class);

        when(dsDal.dsMapper()).thenReturn(dsMapper);

        // Default DS identity
        DmDsDO dsDO = mock(DmDsDO.class);
        when(dsDO.getId()).thenReturn(PROD_DS_ID);
        when(dsDO.getDsEnvId()).thenReturn(DS_ENV_ID);
        when(dsMapper.queryDsIdentityById(PROD_DS_ID)).thenReturn(dsDO);
        when(dsMapper.selectById(anyString())).thenReturn(dsDO);

        // Default config
        DataSourceConfig dsConfig = mock(DataSourceConfig.class);
        when(configService.fetchDsConfigFromExists(PROD_DS_ID)).thenReturn(dsConfig);

        // Default levels (DsLevels record)
        Map<UmiTypes, Object> levelsParam = new HashMap<>();
        levelsParam.put(UmiTypes.Schema, PROD_DB);
        DsLevels dsLevels = new DsLevels(String.valueOf(DS_ENV_ID), dsDO,
            List.of(String.valueOf(DS_ENV_ID), String.valueOf(PROD_DS_ID), PROD_DB),
            List.of(PROD_DB),
            List.of(UmiTypes.Schema),
            levelsParam);
        when(configService.parseLevels(anyList())).thenReturn(dsLevels);

        // Default BLOCK mode
        when(systemDal.fetchSystemConf(anyString(), eq(Boolean.class))).thenReturn(true);

        ReflectionTestUtils.setField(service, "dsSchemaService", dsSchemaService);
        ReflectionTestUtils.setField(service, "analysisService", analysisService);
        ReflectionTestUtils.setField(service, "configService", configService);
        ReflectionTestUtils.setField(service, "dsDal", dsDal);
        ReflectionTestUtils.setField(service, "systemDal", systemDal);
    }

    // ==================== helpers ====================

    private DmProdReleaseStmtDO buildStmt(String sql) {
        DmProdReleaseStmtDO stmt = new DmProdReleaseStmtDO();
        stmt.setId(1L);
        stmt.setReleaseId(100L);
        stmt.setProdDsId(PROD_DS_ID);
        stmt.setProdDbName(PROD_DB);
        stmt.setSeq(1);
        stmt.setSqlContent(sql);
        stmt.setHash("hash");
        stmt.setExecStatus("PENDING");
        return stmt;
    }

    private void mockSplit(String sql, String... scripts) {
        List<SplitScript> list = new java.util.ArrayList<>();
        for (String s : scripts) {
            SplitScript script = new SplitScript();
            script.setScript(s);
            script.setType(classifyScript(s));
            script.setIndex(list.size());
            list.add(script);
        }
        when(analysisService.analysisSplitStream(any(), any(), any(), anyInt(), anyInt()))
            .thenAnswer(inv -> Stream.of(list.toArray(new SplitScript[0])));
    }

    /** Lightweight classifier for test scripts — mirrors the service's fallback classifier. */
    private Set<SplitQueryType> classifyScript(String sql) {
        String upper = sql.toUpperCase().trim();
        Set<SplitQueryType> types = new HashSet<>();
        if (upper.startsWith("CREATE") && upper.contains(" TABLE ")) types.add(SplitQueryType.CREATE_TABLE);
        else if (upper.startsWith("DROP") && upper.contains(" TABLE ")) types.add(SplitQueryType.DROP_TABLE);
        else if (upper.startsWith("RENAME") && upper.contains(" TABLE ")) types.add(SplitQueryType.RENAME_TABLE);
        else if (upper.startsWith("TRUNCATE")) types.add(SplitQueryType.TRUNCATE_TABLE);
        else if (upper.startsWith("ALTER") && upper.contains(" TABLE ")) {
            types.add(SplitQueryType.ALTER_TABLE);
            if (upper.contains("ADD") && upper.contains("COLUMN")) types.add(SplitQueryType.ADD_COLUMN);
            if (upper.contains("DROP") && upper.contains("COLUMN")) types.add(SplitQueryType.DROP_COLUMN);
            if (upper.contains("ADD") && (upper.contains("INDEX") || upper.contains("KEY"))) types.add(SplitQueryType.ADD_INDEX);
            if (upper.contains("DROP") && (upper.contains("INDEX") || upper.contains("KEY"))) types.add(SplitQueryType.DROP_INDEX);
        }
        else if (upper.startsWith("CREATE") && upper.contains(" INDEX ")) types.add(SplitQueryType.ADD_INDEX);
        else if (upper.startsWith("DROP") && upper.contains(" INDEX ")) types.add(SplitQueryType.DROP_INDEX);
        else if (upper.startsWith("INSERT")) types.add(SplitQueryType.INSERT);
        else if (upper.startsWith("UPDATE")) types.add(SplitQueryType.UPDATE);
        else if (upper.startsWith("DELETE")) types.add(SplitQueryType.DELETE);
        else types.add(SplitQueryType.UNKNOWN);
        return types;
    }

    private RdbTable buildTable(String name, String... columns) {
        RdbTable table = new RdbTable();
        table.setName(name);
        Map<String, RdbColumn> colMap = new LinkedHashMap<>();
        for (int i = 0; i < columns.length; i++) {
            RdbColumn col = new RdbColumn();
            col.setName(columns[i]);
            col.setIndex(i);
            colMap.put(columns[i], col);
        }
        table.setColumns(colMap);
        return table;
    }

    private RdbTable buildTableWithIndex(String name, String indexName, String[] columns, String[] indexCols) {
        RdbTable table = buildTable(name, columns);
        RdbIndex idx = new RdbIndex();
        idx.setName(indexName);
        idx.setColumnList(List.of(indexCols));
        table.setIndices(List.of(idx));
        return table;
    }

    private void mockTableSchema(String tableName, RdbTable table) {
        when(dsSchemaService.realTimeFetchSelectObject(any(), any(), eq(tableName)))
            .thenReturn(table);
    }

    private void mockTableNotFound(String tableName) {
        when(dsSchemaService.realTimeFetchSelectObject(any(), any(), eq(tableName)))
            .thenReturn(null);
    }

    // ==================== §8.1 Classifier tests ====================

    @Test
    public void classify_createTable_recognized() {
        String sql = "CREATE TABLE t1 (id int);";
        mockSplit(sql, sql);
        // Table doesn't exist → PASS
        mockTableNotFound("t1");

        PrecheckConclusion c = service.precheck(buildStmt(sql));
        assertTrue(c.isPass());
    }

    @Test
    public void classify_dropTable_recognized() {
        String sql = "DROP TABLE t1;";
        mockSplit(sql, sql);
        mockTableSchema("t1", buildTable("t1", "id"));

        PrecheckConclusion c = service.precheck(buildStmt(sql));
        assertTrue(c.isPass());
    }

    @Test
    public void classify_ifNotExists_alwaysPass() {
        String sql = "CREATE TABLE IF NOT EXISTS t1 (id int);";
        mockSplit(sql, sql);
        // Table exists but IF NOT EXISTS → PASS
        mockTableSchema("t1", buildTable("t1", "id"));

        PrecheckConclusion c = service.precheck(buildStmt(sql));
        assertTrue(c.isPass());
    }

    @Test
    public void classify_ifExists_alwaysPass() {
        String sql = "DROP TABLE IF EXISTS t1;";
        mockSplit(sql, sql);
        mockTableNotFound("t1");

        PrecheckConclusion c = service.precheck(buildStmt(sql));
        assertTrue(c.isPass());
    }

    @Test
    public void classify_unknown_warnsSkip() {
        String sql = "GRANT SELECT ON t1 TO user1;";
        // Will produce UNKNOWN type → no violation, but also no issue → PASS
        mockSplit(sql, sql);

        PrecheckConclusion c = service.precheck(buildStmt(sql));
        // UNKNOWN → no evaluation → PASS (no violations, not a warn-skip)
        assertTrue(c.isPass());
    }

    // ==================== §8.2 Rule matrix tests ====================

    @Test
    public void rule_createTable_exists_blocks() {
        String sql = "CREATE TABLE t1 (id int);";
        mockSplit(sql, sql);
        mockTableSchema("t1", buildTable("t1", "id"));

        PrecheckConclusion c = service.precheck(buildStmt(sql));
        assertTrue(c.isBlock());
        assertTrue(c.toDetailText().contains("t1"));
        assertTrue(c.toDetailText().contains("not exist"));
    }

    @Test
    public void rule_createTable_notExists_passes() {
        String sql = "CREATE TABLE t1 (id int);";
        mockSplit(sql, sql);
        mockTableNotFound("t1");

        PrecheckConclusion c = service.precheck(buildStmt(sql));
        assertTrue(c.isPass());
    }

    @Test
    public void rule_dropTable_exists_passes() {
        String sql = "DROP TABLE t1;";
        mockSplit(sql, sql);
        mockTableSchema("t1", buildTable("t1", "id"));

        PrecheckConclusion c = service.precheck(buildStmt(sql));
        assertTrue(c.isPass());
    }

    @Test
    public void rule_dropTable_notExists_blocks() {
        String sql = "DROP TABLE t1;";
        mockSplit(sql, sql);
        mockTableNotFound("t1");

        PrecheckConclusion c = service.precheck(buildStmt(sql));
        assertTrue(c.isBlock());
        assertTrue(c.toDetailText().contains("t1"));
        assertTrue(c.toDetailText().contains("exist"));
    }

    @Test
    public void rule_alterAddColumn_tableMissing_blocks() {
        String sql = "ALTER TABLE t1 ADD COLUMN c1 int;";
        mockSplit(sql, sql);
        mockTableNotFound("t1");

        PrecheckConclusion c = service.precheck(buildStmt(sql));
        assertTrue(c.isBlock());
        assertTrue(c.toDetailText().contains("t1"));
    }

    @Test
    public void rule_alterAddColumn_notExist_passes() {
        String sql = "ALTER TABLE t1 ADD COLUMN c1 int;";
        mockSplit(sql, sql);
        mockTableSchema("t1", buildTable("t1", "id"));

        PrecheckConclusion c = service.precheck(buildStmt(sql));
        assertTrue(c.isPass());
    }

    @Test
    public void rule_alterAddColumn_alreadyExists_blocks() {
        String sql = "ALTER TABLE t1 ADD COLUMN id int;";
        mockSplit(sql, sql);
        mockTableSchema("t1", buildTable("t1", "id"));

        PrecheckConclusion c = service.precheck(buildStmt(sql));
        assertTrue(c.isBlock());
        assertTrue(c.toDetailText().contains("id"));
        assertTrue(c.toDetailText().contains("not exist"));
    }

    @Test
    public void rule_alterDropColumn_exists_passes() {
        String sql = "ALTER TABLE t1 DROP COLUMN id;";
        mockSplit(sql, sql);
        mockTableSchema("t1", buildTable("t1", "id"));

        PrecheckConclusion c = service.precheck(buildStmt(sql));
        assertTrue(c.isPass());
    }

    @Test
    public void rule_alterDropColumn_notExist_blocks() {
        String sql = "ALTER TABLE t1 DROP COLUMN c1;";
        mockSplit(sql, sql);
        mockTableSchema("t1", buildTable("t1", "id"));

        PrecheckConclusion c = service.precheck(buildStmt(sql));
        assertTrue(c.isBlock());
        assertTrue(c.toDetailText().contains("c1"));
    }

    @Test
    public void rule_alterModifyColumn_exists_passes() {
        String sql = "ALTER TABLE t1 MODIFY COLUMN id varchar(50);";
        mockSplit(sql, sql);
        mockTableSchema("t1", buildTable("t1", "id"));

        PrecheckConclusion c = service.precheck(buildStmt(sql));
        assertTrue(c.isPass());
    }

    @Test
    public void rule_alterModifyColumn_notExist_blocks() {
        String sql = "ALTER TABLE t1 MODIFY COLUMN c1 varchar(50);";
        mockSplit(sql, sql);
        mockTableSchema("t1", buildTable("t1", "id"));

        PrecheckConclusion c = service.precheck(buildStmt(sql));
        assertTrue(c.isBlock());
    }

    @Test
    public void rule_renameTable_sourceMissing_blocks() {
        String sql = "RENAME TABLE old_t TO new_t;";
        mockSplit(sql, sql);
        mockTableNotFound("old_t");
        mockTableNotFound("new_t");

        PrecheckConclusion c = service.precheck(buildStmt(sql));
        assertTrue(c.isBlock());
        assertTrue(c.toDetailText().contains("old_t"));
    }

    @Test
    public void rule_renameTable_targetExists_blocks() {
        String sql = "RENAME TABLE old_t TO new_t;";
        mockSplit(sql, sql);
        mockTableSchema("old_t", buildTable("old_t", "id"));
        mockTableSchema("new_t", buildTable("new_t", "id"));

        PrecheckConclusion c = service.precheck(buildStmt(sql));
        assertTrue(c.isBlock());
        assertTrue(c.toDetailText().contains("new_t"));
    }

    @Test
    public void rule_renameTable_valid_passes() {
        String sql = "RENAME TABLE old_t TO new_t;";
        mockSplit(sql, sql);
        mockTableSchema("old_t", buildTable("old_t", "id"));
        mockTableNotFound("new_t");

        PrecheckConclusion c = service.precheck(buildStmt(sql));
        assertTrue(c.isPass());
    }

    @Test
    public void rule_truncate_exists_passes() {
        String sql = "TRUNCATE TABLE t1;";
        mockSplit(sql, sql);
        mockTableSchema("t1", buildTable("t1", "id"));

        PrecheckConclusion c = service.precheck(buildStmt(sql));
        assertTrue(c.isPass());
    }

    @Test
    public void rule_truncate_notExists_blocks() {
        String sql = "TRUNCATE TABLE t1;";
        mockSplit(sql, sql);
        mockTableNotFound("t1");

        PrecheckConclusion c = service.precheck(buildStmt(sql));
        assertTrue(c.isBlock());
    }

    @Test
    public void rule_createIndex_tableExists_indexNew_colExists_passes() {
        String sql = "CREATE INDEX idx1 ON t1 (id);";
        mockSplit(sql, sql);
        mockTableSchema("t1", buildTable("t1", "id"));

        PrecheckConclusion c = service.precheck(buildStmt(sql));
        assertTrue(c.isPass());
    }

    @Test
    public void rule_createIndex_indexExists_blocks() {
        String sql = "CREATE INDEX idx1 ON t1 (id);";
        mockSplit(sql, sql);
        mockTableSchema("t1", buildTableWithIndex("t1", "idx1", new String[]{"id"}, new String[]{"id"}));

        PrecheckConclusion c = service.precheck(buildStmt(sql));
        assertTrue(c.isBlock());
        assertTrue(c.toDetailText().contains("idx1"));
    }

    @Test
    public void rule_dropIndex_exists_passes() {
        String sql = "DROP INDEX idx1 ON t1;";
        mockSplit(sql, sql);
        mockTableSchema("t1", buildTableWithIndex("t1", "idx1", new String[]{"id"}, new String[]{"id"}));

        PrecheckConclusion c = service.precheck(buildStmt(sql));
        assertTrue(c.isPass());
    }

    @Test
    public void rule_dropIndex_notExists_blocks() {
        String sql = "DROP INDEX idx1 ON t1;";
        mockSplit(sql, sql);
        mockTableSchema("t1", buildTable("t1", "id"));

        PrecheckConclusion c = service.precheck(buildStmt(sql));
        assertTrue(c.isBlock());
        assertTrue(c.toDetailText().contains("idx1"));
    }

    @Test
    public void rule_dml_tableExists_passes() {
        String sql = "INSERT INTO t1 (id) VALUES (1);";
        mockSplit(sql, sql);
        mockTableSchema("t1", buildTable("t1", "id"));

        PrecheckConclusion c = service.precheck(buildStmt(sql));
        assertTrue(c.isPass());
    }

    @Test
    public void rule_dml_tableMissing_blocks() {
        String sql = "INSERT INTO t1 (id) VALUES (1);";
        mockSplit(sql, sql);
        mockTableNotFound("t1");

        PrecheckConclusion c = service.precheck(buildStmt(sql));
        assertTrue(c.isBlock());
    }

    @Test
    public void rule_update_tableExists_passes() {
        String sql = "UPDATE t1 SET id = 1;";
        mockSplit(sql, sql);
        mockTableSchema("t1", buildTable("t1", "id"));

        PrecheckConclusion c = service.precheck(buildStmt(sql));
        assertTrue(c.isPass());
    }

    @Test
    public void rule_delete_tableExists_passes() {
        String sql = "DELETE FROM t1 WHERE id = 1;";
        mockSplit(sql, sql);
        mockTableSchema("t1", buildTable("t1", "id"));

        PrecheckConclusion c = service.precheck(buildStmt(sql));
        assertTrue(c.isPass());
    }

    // ==================== §8.3 Delta inference tests ====================

    @Test
    public void delta_addColumnThenCreateIndex_passes() {
        // Two SQL in one stmt: ADD COLUMN c; then CREATE INDEX on c
        String sql1 = "ALTER TABLE t1 ADD COLUMN c int;";
        String sql2 = "CREATE INDEX idx ON t1 (c);";
        String combined = sql1 + "\n" + sql2;
        mockSplit(combined, sql1, sql2);
        // Table exists with column "id" but not "c"
        mockTableSchema("t1", buildTable("t1", "id"));

        PrecheckConclusion c = service.precheck(buildStmt(combined));
        // ADD COLUMN c → column doesn't exist → PASS
        // CREATE INDEX idx ON t1 (c) → index doesn't exist, column c was added by delta → PASS
        assertTrue(c.isPass());
    }

    @Test
    public void delta_addColumnTwice_secondBlocks() {
        String sql1 = "ALTER TABLE t1 ADD COLUMN c int;";
        String sql2 = "ALTER TABLE t1 ADD COLUMN c int;";
        String combined = sql1 + "\n" + sql2;
        mockSplit(combined, sql1, sql2);
        mockTableSchema("t1", buildTable("t1", "id"));

        PrecheckConclusion c = service.precheck(buildStmt(combined));
        assertTrue(c.isBlock());
        assertTrue(c.toDetailText().contains("c"));
    }

    @Test
    public void delta_createTableThenAddColumn_passes() {
        String sql1 = "CREATE TABLE t1 (id int);";
        String sql2 = "ALTER TABLE t1 ADD COLUMN c int;";
        String combined = sql1 + "\n" + sql2;
        mockSplit(combined, sql1, sql2);
        // Table doesn't exist initially
        mockTableNotFound("t1");

        PrecheckConclusion c = service.precheck(buildStmt(combined));
        // CREATE TABLE → table doesn't exist → PASS
        // ADD COLUMN c → table is delta-created, column c doesn't exist → PASS
        assertTrue(c.isPass());
    }

    @Test
    public void delta_dropColumnThenAddSameColumn_passes() {
        String sql1 = "ALTER TABLE t1 DROP COLUMN c;";
        String sql2 = "ALTER TABLE t1 ADD COLUMN c int;";
        String combined = sql1 + "\n" + sql2;
        mockSplit(combined, sql1, sql2);
        mockTableSchema("t1", buildTable("t1", "id", "c"));

        PrecheckConclusion c = service.precheck(buildStmt(combined));
        // DROP COLUMN c → exists → PASS, delta marks dropped
        // ADD COLUMN c → delta says dropped → doesn't exist → PASS
        assertTrue(c.isPass());
    }

    @Test
    public void delta_alterMultiClause_dropAndAddDifferentColumns_passes() {
        // Single ALTER TABLE with multiple sub-clauses
        String sql = "ALTER TABLE t1 DROP COLUMN a, ADD COLUMN b int;";
        mockSplit(sql, sql);
        mockTableSchema("t1", buildTable("t1", "id", "a"));

        PrecheckConclusion c = service.precheck(buildStmt(sql));
        // DROP COLUMN a → exists → PASS
        // ADD COLUMN b → doesn't exist → PASS
        assertTrue(c.isPass());
    }

    @Test
    public void delta_alterMultiClause_dropColumnThatDoesNotExist_blocks() {
        String sql = "ALTER TABLE t1 DROP COLUMN a, ADD COLUMN b int;";
        mockSplit(sql, sql);
        mockTableSchema("t1", buildTable("t1", "id"));

        PrecheckConclusion c = service.precheck(buildStmt(sql));
        // DROP COLUMN a → doesn't exist → BLOCK
        assertTrue(c.isBlock());
        assertTrue(c.toDetailText().contains("a"));
    }

    @Test
    public void delta_alterAddIndexThenDropIndex_passes() {
        String sql = "ALTER TABLE t1 ADD INDEX idx1 (id), DROP INDEX idx2;";
        mockSplit(sql, sql);
        // Table has idx2 but not idx1
        RdbTable table = buildTable("t1", "id");
        RdbIndex idx2 = new RdbIndex();
        idx2.setName("idx2");
        idx2.setColumnList(List.of("id"));
        table.setIndices(List.of(idx2));

        // Need to use specific table name for mock
        when(dsSchemaService.realTimeFetchSelectObject(any(), any(), eq("t1")))
            .thenReturn(table);

        PrecheckConclusion c = service.precheck(buildStmt(sql));
        // ADD INDEX idx1 → doesn't exist, column id exists → PASS
        // DROP INDEX idx2 → exists → PASS
        assertTrue(c.isPass());
    }

    // ==================== False-block regression tests (宁漏勿误) ====================

    @Test
    public void noFalseBlock_alterAddCheckConstraint_passes() {
        // ADD CHECK (...) is a constraint, not a column — must not BLOCK
        String sql = "ALTER TABLE t1 ADD CHECK (c1 > 0);";
        mockSplit(sql, sql);
        mockTableSchema("t1", buildTable("t1", "id", "c1"));

        PrecheckConclusion c = service.precheck(buildStmt(sql));
        assertTrue("ADD CHECK must not be misclassified as ADD COLUMN", c.isPass());
    }

    @Test
    public void noFalseBlock_alterAddUniqueIndex_passes() {
        // ADD UNIQUE (c) is an unnamed unique index, not a column — must not BLOCK
        String sql = "ALTER TABLE t1 ADD UNIQUE (c1);";
        mockSplit(sql, sql);
        mockTableSchema("t1", buildTable("t1", "id", "c1"));

        PrecheckConclusion c = service.precheck(buildStmt(sql));
        assertTrue("ADD UNIQUE must not be misclassified as ADD COLUMN", c.isPass());
    }

    @Test
    public void noFalseBlock_alterAddFulltext_passes() {
        String sql = "ALTER TABLE t1 ADD FULLTEXT (c1);";
        mockSplit(sql, sql);
        mockTableSchema("t1", buildTable("t1", "id", "c1"));

        PrecheckConclusion c = service.precheck(buildStmt(sql));
        assertTrue("ADD FULLTEXT must not be misclassified as ADD COLUMN", c.isPass());
    }

    @Test
    public void noFalseBlock_alterAddPartition_passes() {
        String sql = "ALTER TABLE t1 ADD PARTITION (PARTITION p0 VALUES LESS THAN (100));";
        mockSplit(sql, sql);
        mockTableSchema("t1", buildTable("t1", "id"));

        PrecheckConclusion c = service.precheck(buildStmt(sql));
        assertTrue("ADD PARTITION must not be misclassified as ADD COLUMN", c.isPass());
    }

    @Test
    public void noFalseBlock_alterDropForeignKey_passes() {
        // DROP FOREIGN KEY fk — FK symbols are not in the index list; must not BLOCK
        String sql = "ALTER TABLE t1 DROP FOREIGN KEY fk_symbol;";
        mockSplit(sql, sql);
        mockTableSchema("t1", buildTable("t1", "id"));

        PrecheckConclusion c = service.precheck(buildStmt(sql));
        assertTrue("DROP FOREIGN KEY must not be misclassified as DROP INDEX", c.isPass());
    }

    @Test
    public void noFalseBlock_alterDropPartition_passes() {
        String sql = "ALTER TABLE t1 DROP PARTITION p0;";
        mockSplit(sql, sql);
        mockTableSchema("t1", buildTable("t1", "id"));

        PrecheckConclusion c = service.precheck(buildStmt(sql));
        assertTrue("DROP PARTITION must not be misclassified as DROP COLUMN", c.isPass());
    }

    @Test
    public void noFalseBlock_alterDropCheckConstraint_passes() {
        String sql = "ALTER TABLE t1 DROP CHECK chk1;";
        mockSplit(sql, sql);
        mockTableSchema("t1", buildTable("t1", "id"));

        PrecheckConclusion c = service.precheck(buildStmt(sql));
        assertTrue("DROP CHECK must not be misclassified as DROP COLUMN", c.isPass());
    }

    @Test
    public void noFalseBlock_addColumnWithoutColumnKeyword_stillChecks() {
        // ADD c INT (no COLUMN keyword) — should still be treated as column add
        String sql = "ALTER TABLE t1 ADD c1 int;";
        mockSplit(sql, sql);
        mockTableSchema("t1", buildTable("t1", "id"));

        PrecheckConclusion c = service.precheck(buildStmt(sql));
        assertTrue("ADD c1 (bareword) should be treated as column add", c.isPass());
    }

    @Test
    public void noFalseBlock_addColumnWithoutColumnKeyword_existsBlocks() {
        // ADD existing_column (no COLUMN keyword) — column exists → should BLOCK
        String sql = "ALTER TABLE t1 ADD id int;";
        mockSplit(sql, sql);
        mockTableSchema("t1", buildTable("t1", "id"));

        PrecheckConclusion c = service.precheck(buildStmt(sql));
        assertTrue("ADD id (bareword, exists) should BLOCK", c.isBlock());
    }

    @Test
    public void noFalseBlock_alterAddConstraintPrimaryKey_passes() {
        // ADD CONSTRAINT PRIMARY KEY (id) — not a column add; column check is valid
        String sql = "ALTER TABLE t1 ADD CONSTRAINT pk1 PRIMARY KEY (id);";
        mockSplit(sql, sql);
        mockTableSchema("t1", buildTable("t1", "id"));

        PrecheckConclusion c = service.precheck(buildStmt(sql));
        assertTrue("ADD CONSTRAINT PRIMARY KEY must not BLOCK when column exists", c.isPass());
    }

    @Test
    public void noFalseBlock_alterAddForeignKey_passes() {
        // ADD FOREIGN KEY (c) REFERENCES p (id) — has two parenthetical groups;
        // column extraction would garble, so the gate skips it (宁漏勿误). Must PASS.
        String sql = "ALTER TABLE t1 ADD FOREIGN KEY (c1) REFERENCES p2 (id);";
        mockSplit(sql, sql);
        mockTableSchema("t1", buildTable("t1", "id", "c1"));

        PrecheckConclusion c = service.precheck(buildStmt(sql));
        assertTrue("ADD FOREIGN KEY must not BLOCK (garbled extraction risk)", c.isPass());
    }

    // ==================== §8.4 Degradation tests ====================

    @Test
    public void degradation_splitFails_warnsSkip() {
        when(analysisService.analysisSplitStream(any(), any(), any(), anyInt(), anyInt()))
            .thenThrow(new RuntimeException("split engine error"));

        PrecheckConclusion c = service.precheck(buildStmt("CREATE TABLE t1 (id int);"));
        assertTrue(c.isWarnSkip());
        assertTrue(c.getSkipReason().contains("split"));
    }

    @Test
    public void degradation_schemaFetchFails_warnsSkip() {
        String sql = "CREATE TABLE t1 (id int);";
        mockSplit(sql, sql);
        when(dsSchemaService.realTimeFetchSelectObject(any(), any(), anyString()))
            .thenThrow(new RuntimeException("RSocket timeout"));

        PrecheckConclusion c = service.precheck(buildStmt(sql));
        assertTrue(c.isWarnSkip());
        assertTrue(c.getSkipReason().contains("Failed to fetch live schema"));
    }

    @Test
    public void degradation_dsConfigFails_warnsSkip() {
        when(configService.fetchDsConfigFromExists(PROD_DS_ID))
            .thenThrow(new RuntimeException("config error"));

        PrecheckConclusion c = service.precheck(buildStmt("CREATE TABLE t1 (id int);"));
        assertTrue(c.isWarnSkip());
        assertTrue(c.getSkipReason().contains("DS config"));
    }

    @Test
    public void degradation_dsIdentityNull_warnsSkip() {
        when(dsMapper.queryDsIdentityById(PROD_DS_ID)).thenReturn(null);

        PrecheckConclusion c = service.precheck(buildStmt("CREATE TABLE t1 (id int);"));
        assertTrue(c.isWarnSkip());
        assertTrue(c.getSkipReason().contains("datasource"));
    }

    @Test
    public void degradation_nullStmt_warnsSkip() {
        PrecheckConclusion c = service.precheck(null);
        assertTrue(c.isWarnSkip());
    }

    @Test
    public void degradation_emptySql_warnsSkip() {
        PrecheckConclusion c = service.precheck(buildStmt(""));
        assertTrue(c.isWarnSkip());
    }

    @Test
    public void degradation_splitEmpty_warnsSkip() {
        when(analysisService.analysisSplitStream(any(), any(), any(), anyInt(), anyInt()))
            .thenAnswer(inv -> Stream.empty());

        PrecheckConclusion c = service.precheck(buildStmt("CREATE TABLE t1 (id int);"));
        assertTrue(c.isWarnSkip());
        assertTrue(c.getSkipReason().contains("no scripts"));
    }

    @Test
    public void degradation_neverThrows() {
        // Even with NPE in mock setup, service should catch and return WARN_SKIP
        when(analysisService.analysisSplitStream(any(), any(), any(), anyInt(), anyInt()))
            .thenThrow(new NullPointerException("unexpected"));

        PrecheckConclusion c = service.precheck(buildStmt("CREATE TABLE t1 (id int);"));
        assertTrue(c.isWarnSkip());
        // No exception thrown
    }

    // ==================== BLOCK/WARN mode switch tests ====================

    @Test
    public void switch_blockMode_violationBlocks() {
        String sql = "CREATE TABLE t1 (id int);";
        mockSplit(sql, sql);
        mockTableSchema("t1", buildTable("t1", "id"));

        PrecheckConclusion c = service.precheck(buildStmt(sql));
        assertTrue(c.isBlock());
    }

    @Test
    public void switch_warnMode_violationBecomesWarnSkip() {
        when(systemDal.fetchSystemConf(anyString(), eq(Boolean.class))).thenReturn(false);

        String sql = "CREATE TABLE t1 (id int);";
        mockSplit(sql, sql);
        mockTableSchema("t1", buildTable("t1", "id"));

        PrecheckConclusion c = service.precheck(buildStmt(sql));
        assertTrue(c.isWarnSkip());
        assertTrue(c.getSkipReason().contains("BLOCK mode is disabled"));
    }

    @Test
    public void switch_nullConfig_defaultsToBlock() {
        when(systemDal.fetchSystemConf(anyString(), eq(Boolean.class))).thenReturn(null);

        String sql = "CREATE TABLE t1 (id int);";
        mockSplit(sql, sql);
        mockTableSchema("t1", buildTable("t1", "id"));

        PrecheckConclusion c = service.precheck(buildStmt(sql));
        assertTrue(c.isBlock());
    }

    // ==================== Identifier handling tests ====================

    @Test
    public void identifier_backtickQuoted_handled() {
        String sql = "CREATE TABLE `t1` (`id` int);";
        mockSplit(sql, sql);
        mockTableSchema("t1", buildTable("t1", "id"));

        PrecheckConclusion c = service.precheck(buildStmt(sql));
        assertTrue(c.isBlock());
        assertTrue(c.toDetailText().contains("t1"));
    }

    @Test
    public void identifier_qualifiedName_handled() {
        String sql = "CREATE TABLE `prod_db`.`t1` (id int);";
        mockSplit(sql, sql);
        mockTableSchema("t1", buildTable("t1", "id"));

        PrecheckConclusion c = service.precheck(buildStmt(sql));
        assertTrue(c.isBlock());
        assertTrue(c.toDetailText().contains("t1"));
    }

    @Test
    public void identifier_alterTableWithBackticks_handled() {
        String sql = "ALTER TABLE `t1` ADD COLUMN `c1` int;";
        mockSplit(sql, sql);
        mockTableSchema("t1", buildTable("t1", "id"));

        PrecheckConclusion c = service.precheck(buildStmt(sql));
        assertTrue(c.isPass());
    }

    // ==================== Event data / detail text ====================

    @Test
    public void eventData_block_containsViolations() {
        String sql = "CREATE TABLE t1 (id int);";
        mockSplit(sql, sql);
        mockTableSchema("t1", buildTable("t1", "id"));

        PrecheckConclusion c = service.precheck(buildStmt(sql));
        String eventData = c.toEventData(42L);
        assertTrue(eventData.contains("BLOCK"));
        assertTrue(eventData.contains("42"));
    }

    @Test
    public void eventData_warnSkip_containsReason() {
        when(analysisService.analysisSplitStream(any(), any(), any(), anyInt(), anyInt()))
            .thenThrow(new RuntimeException("split error"));

        PrecheckConclusion c = service.precheck(buildStmt("CREATE TABLE t1 (id int);"));
        String eventData = c.toEventData(42L);
        assertTrue(eventData.contains("WARN_SKIP"));
        assertTrue(eventData.contains("42"));
    }

    @Test
    public void eventData_pass_containsPass() {
        String sql = "CREATE TABLE t1 (id int);";
        mockSplit(sql, sql);
        mockTableNotFound("t1");

        PrecheckConclusion c = service.precheck(buildStmt(sql));
        String eventData = c.toEventData(42L);
        assertTrue(eventData.contains("PASS"));
    }
}
