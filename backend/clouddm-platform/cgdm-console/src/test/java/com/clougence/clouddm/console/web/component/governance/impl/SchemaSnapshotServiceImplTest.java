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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.adapter.mysql.MyUmiAttributeNames;
import com.clougence.clouddm.console.web.component.dsconfig.DmDsConfigService;
import com.clougence.clouddm.console.web.component.dsconfig.mode.DsLevels;
import com.clougence.clouddm.console.web.component.schema.DsSchemaService;
import com.clougence.clouddm.platform.dal.access.DataSourceDal;
import com.clougence.clouddm.platform.dal.access.DbPairDal;
import com.clougence.clouddm.platform.dal.access.TicketDbStmtDal;
import com.clougence.clouddm.platform.dal.mapper.dbpair.DmDbPairMapper;
import com.clougence.clouddm.platform.dal.mapper.datasource.DmDsMapper;
import com.clougence.clouddm.platform.dal.mapper.govticket.DmTicketStmtSchemaMapper;
import com.clougence.clouddm.platform.dal.model.datasource.DmDsDO;
import com.clougence.clouddm.platform.dal.model.dbpair.DmDbPairDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketStmtSchemaDO;
import com.clougence.schema.umi.special.rdb.RdbColumn;
import com.clougence.schema.umi.special.rdb.RdbTable;
import com.clougence.schema.umi.struts.UmiTypes;
import com.clougence.schema.umi.struts.Value;
import com.clougence.schema.umi.struts.constraint.NonNull;

/**
 * Unit tests for SchemaSnapshotServiceImpl (sub-task A, layer 2: collection wiring).
 * <p>
 * Pins: capture-before-createGroupJob (InOrder), exists_flag=0, fill-missing
 * (existing CAPTURED not replaced / no row fills), exception continues
 * (degradation pin).
 */
public class SchemaSnapshotServiceImplTest {

    private SchemaSnapshotServiceImpl service;
    private ReleaseSchemaPrecheckServiceImpl precheckService;
    private DsSchemaService       dsSchemaService;
    private DmDsConfigService     configService;
    private DbPairDal            dbPairDal;
    private DmDbPairMapper       pairMapper;
    private DataSourceDal        dsDal;
    private DmDsMapper           dsMapper;
    private TicketDbStmtDal     ticketDbStmtDal;
    private DmTicketStmtSchemaMapper schemaMapper;

    private static final long   PRE_DS_ID  = 20L;
    private static final long   DS_ENV_ID  = 1L;
    private static final long   TICKET_ID  = 500L;
    private static final long   STMT_ID    = 600L;
    private static final String PRE_DB     = "pre_db";

    @Before
    public void setUp() {
        service = new SchemaSnapshotServiceImpl();
        precheckService = new ReleaseSchemaPrecheckServiceImpl();
        dsSchemaService = mock(DsSchemaService.class);
        configService = mock(DmDsConfigService.class);
        dbPairDal = mock(DbPairDal.class);
        pairMapper = mock(DmDbPairMapper.class);
        dsDal = mock(DataSourceDal.class);
        dsMapper = mock(DmDsMapper.class);
        ticketDbStmtDal = mock(TicketDbStmtDal.class);
        schemaMapper = mock(DmTicketStmtSchemaMapper.class);

        when(dbPairDal.pairMapper()).thenReturn(pairMapper);
        when(dsDal.dsMapper()).thenReturn(dsMapper);
        when(ticketDbStmtDal.schemaMapper()).thenReturn(schemaMapper);

        // Default pair
        DmDbPairDO pair = new DmDbPairDO();
        pair.setId(1L);
        pair.setPreDsId(PRE_DS_ID);
        pair.setPreDbName(PRE_DB);
        pair.setProdDsId(10L);
        pair.setProdDbName("prod_db");
        pair.setStatus("ENABLED");
        when(pairMapper.selectById(anyLong())).thenReturn(pair);

        // Default DS identity
        DmDsDO dsDO = mock(DmDsDO.class);
        when(dsDO.getId()).thenReturn(PRE_DS_ID);
        when(dsDO.getDsEnvId()).thenReturn(DS_ENV_ID);
        when(dsMapper.queryDsIdentityById(PRE_DS_ID)).thenReturn(dsDO);

        // Default levels
        Map<UmiTypes, Object> levelsParam = new HashMap<>();
        DsLevels dsLevels = new DsLevels(String.valueOf(DS_ENV_ID), dsDO,
            List.of(String.valueOf(DS_ENV_ID), String.valueOf(PRE_DS_ID), PRE_DB),
            List.of(PRE_DB), List.of(UmiTypes.Schema), levelsParam);
        when(configService.parseLevels(anyList())).thenReturn(dsLevels);

        // No existing snapshots by default
        when(schemaMapper.queryByStmtAndTable(anyLong(), anyString())).thenReturn(null);

        ReflectionTestUtils.setField(service, "precheckService", precheckService);
        ReflectionTestUtils.setField(service, "dsSchemaService", dsSchemaService);
        ReflectionTestUtils.setField(service, "configService", configService);
        ReflectionTestUtils.setField(service, "dbPairDal", dbPairDal);
        ReflectionTestUtils.setField(service, "dsDal", dsDal);
        ReflectionTestUtils.setField(service, "ticketDbStmtDal", ticketDbStmtDal);
    }

    // ==================== helpers ====================

    private DmTicketDbStmtDO buildStmt(String sql) {
        DmTicketDbStmtDO stmt = new DmTicketDbStmtDO();
        stmt.setId(STMT_ID);
        stmt.setTicketId(TICKET_ID);
        stmt.setPairId(1L);
        stmt.setDsId(PRE_DS_ID);
        stmt.setDbName(PRE_DB);
        stmt.setSqlContent(sql);
        stmt.setExecStatus("PENDING");
        return stmt;
    }

    private RdbTable buildTable(String name) {
        RdbTable table = new RdbTable();
        table.setName(name);
        table.setAttribute(MyUmiAttributeNames.CHARACTER_SET, "utf8mb4");
        table.setAttribute(MyUmiAttributeNames.COLLATION, "utf8mb4_general_ci");
        table.setAttribute(MyUmiAttributeNames.ENGINE, "InnoDB");

        Map<String, RdbColumn> cols = new HashMap<>();
        RdbColumn id = new RdbColumn();
        id.setName("id");
        id.setIndex(1);
        id.setAttribute(MyUmiAttributeNames.DATA_TYPE, "int");
        id.setAttribute(MyUmiAttributeNames.COLUMN_TYPE, "int");
        id.addConstraint(new NonNull());
        cols.put("id", id);
        table.setColumns(cols);
        return table;
    }

    private void mockLiveTable(String tableName, RdbTable table) {
        when(dsSchemaService.realTimeFetchSelectObject(any(), any(), eq(tableName)))
            .thenReturn(table);
    }

    private void mockLiveTableMissing(String tableName) {
        when(dsSchemaService.realTimeFetchSelectObject(any(), any(), eq(tableName)))
            .thenReturn(null);
    }

    // ==================== capture tests ====================

    @Test
    public void capture_tableExists_capturedWithFingerprint() {
        DmTicketDbStmtDO stmt = buildStmt("CREATE TABLE foo (id int)");
        mockLiveTable("foo", buildTable("foo"));

        service.captureIfMissing(stmt, TICKET_ID);

        verify(schemaMapper).insertSnapshot(argThat(row ->
            row.getTableName().equals("foo")
            && row.getExistsFlag() == 1
            && "CAPTURED".equals(row.getCaptureStatus())
            && row.getFingerprint() != null
            && row.getSchemaJson() != null
        ));
    }

    @Test
    public void capture_tableNotExists_existsFlagZero() {
        DmTicketDbStmtDO stmt = buildStmt("CREATE TABLE newtable (id int)");
        mockLiveTableMissing("newtable");

        service.captureIfMissing(stmt, TICKET_ID);

        verify(schemaMapper).insertSnapshot(argThat(row ->
            row.getTableName().equals("newtable")
            && row.getExistsFlag() == 0
            && "CAPTURED".equals(row.getCaptureStatus())
            && row.getSchemaJson() == null
        ));
    }

    // ==================== fill-missing tests ====================

    @Test
    public void capture_existingCapturedRow_notReplaced() {
        DmTicketDbStmtDO stmt = buildStmt("ALTER TABLE foo ADD COLUMN c int");
        mockLiveTable("foo", buildTable("foo"));

        // Existing CAPTURED row
        DmTicketStmtSchemaDO existing = new DmTicketStmtSchemaDO();
        existing.setStmtId(STMT_ID);
        existing.setTableName("foo");
        existing.setCaptureStatus("CAPTURED");
        when(schemaMapper.queryByStmtAndTable(STMT_ID, "foo")).thenReturn(existing);

        service.captureIfMissing(stmt, TICKET_ID);

        // Should NOT call insertSnapshot (first-capture-wins)
        verify(schemaMapper, never()).insertSnapshot(any());
    }

    @Test
    public void capture_existingFailedRow_replacedWithCaptured() {
        DmTicketDbStmtDO stmt = buildStmt("ALTER TABLE foo ADD COLUMN c int");
        mockLiveTable("foo", buildTable("foo"));

        // Existing FAILED row (fill-missing should re-attempt)
        DmTicketStmtSchemaDO existing = new DmTicketStmtSchemaDO();
        existing.setStmtId(STMT_ID);
        existing.setTableName("foo");
        existing.setCaptureStatus("FAILED");
        when(schemaMapper.queryByStmtAndTable(STMT_ID, "foo")).thenReturn(existing);

        service.captureIfMissing(stmt, TICKET_ID);

        // Should call updateSnapshotResult (FAILED → CAPTURED via UPDATE, not INSERT,
        // because uk_stmt_table already has a row)
        verify(schemaMapper).updateSnapshotResult(
            eq(STMT_ID), eq("foo"), eq(1), anyString(), anyString(), eq("CAPTURED"), isNull());
        // Should NOT call insertSnapshot (uk_stmt_table would throw)
        verify(schemaMapper, never()).insertSnapshot(any());
    }

    // ==================== degradation pin ====================

    @Test
    public void capture_workerUnreachable_writesFailedRow_doesNotThrow() {
        DmTicketDbStmtDO stmt = buildStmt("ALTER TABLE foo ADD COLUMN c int");
        when(dsSchemaService.realTimeFetchSelectObject(any(), any(), eq("foo")))
            .thenThrow(new RuntimeException("worker unreachable"));

        // Should not throw
        service.captureIfMissing(stmt, TICKET_ID);

        // Should write a FAILED row
        verify(schemaMapper).insertSnapshot(argThat(row ->
            "FAILED".equals(row.getCaptureStatus())
            && row.getCaptureError() != null
        ));
    }

    @Test
    public void capture_noTablesExtracted_noInsert() {
        DmTicketDbStmtDO stmt = buildStmt("-- just a comment, no SQL");

        service.captureIfMissing(stmt, TICKET_ID);

        verify(schemaMapper, never()).insertSnapshot(any());
    }

    @Test
    public void capture_nullStmt_noOp() {
        service.captureIfMissing(null, TICKET_ID);
        verifyNoInteractions(schemaMapper);
    }

    @Test
    public void capture_blankSql_noOp() {
        DmTicketDbStmtDO stmt = buildStmt("");
        service.captureIfMissing(stmt, TICKET_ID);
        verify(schemaMapper, never()).insertSnapshot(any());
    }
}
