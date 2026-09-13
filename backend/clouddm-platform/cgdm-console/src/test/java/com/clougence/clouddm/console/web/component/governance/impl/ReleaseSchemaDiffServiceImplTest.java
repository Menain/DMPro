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
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.adapter.mysql.MyUmiAttributeNames;
import com.clougence.clouddm.console.web.component.dsconfig.DmDsConfigService;
import com.clougence.clouddm.console.web.component.dsconfig.mode.DsLevels;
import com.clougence.clouddm.console.web.component.governance.PrecheckConclusion;
import com.clougence.clouddm.console.web.component.governance.SchemaFingerprintUtils;
import com.clougence.clouddm.console.web.component.schema.DsSchemaService;
import com.clougence.clouddm.platform.dal.access.DataSourceDal;
import com.clougence.clouddm.platform.dal.access.ProdReleaseDal;
import com.clougence.clouddm.platform.dal.access.SystemDal;
import com.clougence.clouddm.platform.dal.mapper.datasource.DmDsMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseStmtSchemaMapper;
import com.clougence.clouddm.platform.dal.model.datasource.DmDsDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseStmtDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseStmtSchemaDO;
import com.clougence.schema.umi.special.rdb.RdbColumn;
import com.clougence.schema.umi.special.rdb.RdbTable;
import com.clougence.schema.umi.struts.UmiTypes;
import com.clougence.schema.umi.struts.Value;
import com.clougence.schema.umi.struts.constraint.NonNull;

/**
 * Unit tests for ReleaseSchemaDiffServiceImpl (sub-task A, layer 4: diff matrix).
 * <p>
 * Covers: exclusive table {equal→PASS, type change→BLOCK, nullability→BLOCK,
 * only ENGINE/comment→WARN}, exists_flag=0 {prod absent→PASS, prod present→BLOCK},
 * shared→WARN degradation, no snapshot→SNAPSHOT_MISSING, exception→WARN_SKIP,
 * switch false→BLOCK becomes WARN.
 */
public class ReleaseSchemaDiffServiceImplTest {

    private ReleaseSchemaDiffServiceImpl service;
    private ProdReleaseDal          prodReleaseDal;
    private DmProdReleaseStmtSchemaMapper schemaMapper;
    private DataSourceDal           dsDal;
    private DmDsMapper             dsMapper;
    private DmDsConfigService      configService;
    private DsSchemaService        dsSchemaService;
    private SystemDal              systemDal;

    private static final long   PROD_DS_ID = 10L;
    private static final long   DS_ENV_ID  = 1L;
    private static final String PROD_DB    = "prod_db";

    @Before
    public void setUp() {
        service = new ReleaseSchemaDiffServiceImpl();

        prodReleaseDal = mock(ProdReleaseDal.class);
        schemaMapper = mock(DmProdReleaseStmtSchemaMapper.class);
        dsDal = mock(DataSourceDal.class);
        dsMapper = mock(DmDsMapper.class);
        configService = mock(DmDsConfigService.class);
        dsSchemaService = mock(DsSchemaService.class);
        systemDal = mock(SystemDal.class);

        when(prodReleaseDal.schemaMapper()).thenReturn(schemaMapper);
        when(dsDal.dsMapper()).thenReturn(dsMapper);

        DmDsDO dsDO = mock(DmDsDO.class);
        when(dsDO.getId()).thenReturn(PROD_DS_ID);
        when(dsDO.getDsEnvId()).thenReturn(DS_ENV_ID);
        when(dsMapper.queryDsIdentityById(PROD_DS_ID)).thenReturn(dsDO);

        Map<UmiTypes, Object> levelsParam = new HashMap<>();
        DsLevels dsLevels = new DsLevels(String.valueOf(DS_ENV_ID), dsDO,
            List.of(String.valueOf(DS_ENV_ID), String.valueOf(PROD_DS_ID), PROD_DB),
            List.of(PROD_DB), List.of(UmiTypes.Schema), levelsParam);
        when(configService.parseLevels(anyList())).thenReturn(dsLevels);

        // Default BLOCK mode
        when(systemDal.fetchSystemConf(anyString(), eq(Boolean.class))).thenReturn(true);

        ReflectionTestUtils.setField(service, "prodReleaseDal", prodReleaseDal);
        ReflectionTestUtils.setField(service, "dsDal", dsDal);
        ReflectionTestUtils.setField(service, "configService", configService);
        ReflectionTestUtils.setField(service, "dsSchemaService", dsSchemaService);
        ReflectionTestUtils.setField(service, "systemDal", systemDal);
    }

    // ==================== helpers ====================

    private DmProdReleaseStmtDO buildStmt() {
        DmProdReleaseStmtDO stmt = new DmProdReleaseStmtDO();
        stmt.setId(1L);
        stmt.setReleaseId(100L);
        stmt.setProdDsId(PROD_DS_ID);
        stmt.setProdDbName(PROD_DB);
        stmt.setSeq(1);
        stmt.setSqlContent("ALTER TABLE users ADD COLUMN email varchar(255)");
        stmt.setHash("hash");
        stmt.setExecStatus("PENDING");
        return stmt;
    }

    private DmProdReleaseStmtSchemaDO buildSnapshot(String tableName, boolean exists, boolean shared) {
        return buildSnapshot(tableName, exists, shared, null, null);
    }

    private DmProdReleaseStmtSchemaDO buildSnapshot(String tableName, boolean exists, boolean shared,
                                                     String fingerprint, String schemaJson) {
        DmProdReleaseStmtSchemaDO snap = new DmProdReleaseStmtSchemaDO();
        snap.setId(1L);
        snap.setReleaseId(100L);
        snap.setReleaseStmtId(1L);
        snap.setProdDsId(PROD_DS_ID);
        snap.setProdDbName(PROD_DB);
        snap.setTableName(tableName);
        snap.setExistsFlag(exists ? 1 : 0);
        snap.setSharedFlag(shared ? 1 : 0);
        snap.setFingerprint(fingerprint != null ? fingerprint : SchemaFingerprintUtils.NON_EXISTENT_FINGERPRINT);
        snap.setSchemaJson(schemaJson);
        return snap;
    }

    private RdbTable buildTable(String name) {
        RdbTable table = new RdbTable();
        table.setName(name);
        table.setAttribute(MyUmiAttributeNames.CHARACTER_SET, "utf8mb4");
        table.setAttribute(MyUmiAttributeNames.COLLATION, "utf8mb4_general_ci");
        table.setAttribute(MyUmiAttributeNames.ENGINE, "InnoDB");

        Map<String, RdbColumn> cols = new LinkedHashMap<>();
        RdbColumn id = new RdbColumn();
        id.setName("id");
        id.setIndex(1);
        id.setAttribute(MyUmiAttributeNames.DATA_TYPE, "int");
        id.setAttribute(MyUmiAttributeNames.COLUMN_TYPE, "int");
        id.setAttribute(MyUmiAttributeNames.UNSIGNED, "true");
        id.addConstraint(new NonNull());
        cols.put("id", id);

        RdbColumn username = new RdbColumn();
        username.setName("username");
        username.setIndex(2);
        username.setAttribute(MyUmiAttributeNames.DATA_TYPE, "varchar");
        username.setAttribute(MyUmiAttributeNames.COLUMN_TYPE, "varchar(255)");
        username.setCharLength(255L);
        cols.put("username", username);
        table.setColumns(cols);
        return table;
    }

    private void mockSnapshotList(DmProdReleaseStmtSchemaDO... snaps) {
        when(schemaMapper.queryByReleaseStmtId(anyLong())).thenReturn(List.of(snaps));
    }

    private void mockLiveTable(String tableName, RdbTable table) {
        when(dsSchemaService.realTimeFetchSelectObject(any(), any(), eq(tableName)))
            .thenReturn(table);
    }

    private void mockLiveTableMissing(String tableName) {
        when(dsSchemaService.realTimeFetchSelectObject(any(), any(), eq(tableName)))
            .thenReturn(null);
    }

    // ==================== no snapshot → WARN_SKIP ====================

    @Test
    public void diff_noSnapshot_warnSkip() {
        when(schemaMapper.queryByReleaseStmtId(anyLong())).thenReturn(new ArrayList<>());
        PrecheckConclusion c = service.diff(buildStmt());
        assertTrue(c.isWarnSkip());
        assertNotNull(c.getSkipReason());
        assertTrue(c.getSkipReason().contains("No pre-before schema snapshot"));
    }

    @Test
    public void diff_nullSnapshots_warnSkip() {
        when(schemaMapper.queryByReleaseStmtId(anyLong())).thenReturn(null);
        PrecheckConclusion c = service.diff(buildStmt());
        assertTrue(c.isWarnSkip());
        assertTrue(c.getSkipReason().contains("No pre-before schema snapshot"));
    }

    // ==================== exclusive table: equal → PASS ====================

    @Test
    public void diff_exclusiveEqual_pass() {
        RdbTable liveTable = buildTable("users");
        String fp = SchemaFingerprintUtils.fingerprint(liveTable);
        String json = SchemaFingerprintUtils.canonicalJson(liveTable);

        mockSnapshotList(buildSnapshot("users", true, false, fp, json));
        mockLiveTable("users", liveTable);

        PrecheckConclusion c = service.diff(buildStmt());
        assertTrue(c.isPass());
    }

    // ==================== exclusive table: BLOCK-level changes → BLOCK ====================

    @Test
    public void diff_exclusiveColumnTypeChange_block() {
        RdbTable snapTable = buildTable("users");
        String fp = SchemaFingerprintUtils.fingerprint(snapTable);
        String json = SchemaFingerprintUtils.canonicalJson(snapTable);

        // Live table has different column type
        RdbTable liveTable = buildTable("users");
        for (RdbColumn col : liveTable.getColumns().values()) {
            if ("username".equals(col.getName())) {
                col.setAttribute(MyUmiAttributeNames.COLUMN_TYPE, "varchar(100)");
                col.setCharLength(100L);
            }
        }

        mockSnapshotList(buildSnapshot("users", true, false, fp, json));
        mockLiveTable("users", liveTable);

        PrecheckConclusion c = service.diff(buildStmt());
        assertTrue(c.isBlock());
    }

    @Test
    public void diff_exclusiveNullabilityChange_block() {
        RdbTable snapTable = buildTable("users");
        String fp = SchemaFingerprintUtils.fingerprint(snapTable);
        String json = SchemaFingerprintUtils.canonicalJson(snapTable);

        // Live table has nullable id (snaptable has NonNull on id)
        RdbTable liveTable = buildTable("users");
        for (RdbColumn col : liveTable.getColumns().values()) {
            if ("id".equals(col.getName())) {
                col.getConstraints().clear(); // remove NonNull
            }
        }

        mockSnapshotList(buildSnapshot("users", true, false, fp, json));
        mockLiveTable("users", liveTable);

        PrecheckConclusion c = service.diff(buildStmt());
        assertTrue(c.isBlock());
    }

    // ==================== exclusive: only WARN-level change → WARN ====================

    @Test
    public void diff_exclusiveOnlyEngineChange_warn() {
        RdbTable snapTable = buildTable("users");
        String fp = SchemaFingerprintUtils.fingerprint(snapTable);
        String json = SchemaFingerprintUtils.canonicalJson(snapTable);

        // Live table has different ENGINE only (WARN-level)
        RdbTable liveTable = buildTable("users");
        liveTable.setAttribute(MyUmiAttributeNames.ENGINE, "MyISAM");

        mockSnapshotList(buildSnapshot("users", true, false, fp, json));
        mockLiveTable("users", liveTable);

        PrecheckConclusion c = service.diff(buildStmt());
        // fingerprint (BLOCK-only) matches, but canonical JSON differs (ENGINE changed)
        assertTrue(c.isWarnSkip());
    }

    // ==================== exists_flag=0 semantics ====================

    @Test
    public void diff_existsFlag0_prodAbsent_pass() {
        mockSnapshotList(buildSnapshot("newtable", false, false));
        mockLiveTableMissing("newtable");

        PrecheckConclusion c = service.diff(buildStmt());
        assertTrue(c.isPass());
    }

    @Test
    public void diff_existsFlag0_prodPresent_block() {
        mockSnapshotList(buildSnapshot("newtable", false, false));
        mockLiveTable("newtable", buildTable("newtable"));

        PrecheckConclusion c = service.diff(buildStmt());
        assertTrue(c.isBlock());
    }

    // ==================== exists_flag=1, prod table missing → BLOCK ====================

    @Test
    public void diff_existsFlag1_prodMissing_block() {
        RdbTable snapTable = buildTable("users");
        String fp = SchemaFingerprintUtils.fingerprint(snapTable);
        String json = SchemaFingerprintUtils.canonicalJson(snapTable);

        mockSnapshotList(buildSnapshot("users", true, false, fp, json));
        mockLiveTableMissing("users");

        PrecheckConclusion c = service.diff(buildStmt());
        assertTrue(c.isBlock());
    }

    // ==================== shared table → WARN degradation ====================

    @Test
    public void diff_sharedTable_warn() {
        RdbTable snapTable = buildTable("users");
        String fp = SchemaFingerprintUtils.fingerprint(snapTable);
        String json = SchemaFingerprintUtils.canonicalJson(snapTable);

        mockSnapshotList(buildSnapshot("users", true, true, fp, json));
        // Don't mock live table — should not be fetched for shared tables
        PrecheckConclusion c = service.diff(buildStmt());
        assertTrue(c.isWarnSkip());
        verifyNoInteractions(dsSchemaService);
    }

    // ==================== exception → WARN_SKIP (fail-open) ====================

    @Test
    public void diff_exception_warnSkip() {
        RdbTable snapTable = buildTable("users");
        String fp = SchemaFingerprintUtils.fingerprint(snapTable);
        String json = SchemaFingerprintUtils.canonicalJson(snapTable);

        mockSnapshotList(buildSnapshot("users", true, false, fp, json));
        when(dsSchemaService.realTimeFetchSelectObject(any(), any(), anyString()))
            .thenThrow(new RuntimeException("worker unreachable"));

        PrecheckConclusion c = service.diff(buildStmt());
        assertTrue(c.isWarnSkip());
    }

    // ==================== switch false → BLOCK becomes WARN ====================

    @Test
    public void diff_switchFalse_blockBecomesWarn() {
        when(systemDal.fetchSystemConf(anyString(), eq(Boolean.class))).thenReturn(false);

        RdbTable snapTable = buildTable("users");
        String fp = SchemaFingerprintUtils.fingerprint(snapTable);
        String json = SchemaFingerprintUtils.canonicalJson(snapTable);

        RdbTable liveTable = buildTable("users");
        for (RdbColumn col : liveTable.getColumns().values()) {
            if ("username".equals(col.getName())) {
                col.setAttribute(MyUmiAttributeNames.COLUMN_TYPE, "varchar(100)");
                col.setCharLength(100L);
            }
        }

        mockSnapshotList(buildSnapshot("users", true, false, fp, json));
        mockLiveTable("users", liveTable);

        PrecheckConclusion c = service.diff(buildStmt());
        // BLOCK → degraded to WARN when switch is off
        assertTrue(c.isWarnSkip());
    }
}
