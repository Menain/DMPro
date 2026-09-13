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
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.adapter.mysql.MyUmiAttributeNames;
import com.clougence.clouddm.console.web.component.dsconfig.DmDsConfigService;
import com.clougence.clouddm.console.web.component.dsconfig.mode.DsLevels;
import com.clougence.clouddm.console.web.component.schema.DsSchemaService;
import com.clougence.clouddm.platform.dal.access.DataSourceDal;
import com.clougence.clouddm.platform.dal.access.DbPairDal;
import com.clougence.clouddm.platform.dal.access.GovSchemaPatrolDal;
import com.clougence.clouddm.platform.dal.access.ProdReleaseDal;
import com.clougence.clouddm.platform.dal.access.SystemDal;
import com.clougence.clouddm.platform.dal.access.TicketDbStmtDal;
import com.clougence.clouddm.platform.dal.mapper.datasource.DmDsMapper;
import com.clougence.clouddm.platform.dal.mapper.dbpair.DmDbPairMapper;
import com.clougence.clouddm.platform.dal.mapper.govpatrol.DmGovSchemaPatrolItemMapper;
import com.clougence.clouddm.platform.dal.mapper.govpatrol.DmGovSchemaPatrolRunMapper;
import com.clougence.clouddm.platform.dal.mapper.govticket.DmTicketDbStmtMapper;
import com.clougence.clouddm.platform.dal.mapper.govticket.DmTicketStmtSchemaMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseStmtMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseStmtSchemaMapper;
import com.clougence.clouddm.platform.dal.model.datasource.DmDsDO;
import com.clougence.clouddm.platform.dal.model.dbpair.DmDbPairDO;
import com.clougence.clouddm.platform.dal.model.govpatrol.DmGovSchemaPatrolItemDO;
import com.clougence.clouddm.platform.dal.model.govpatrol.DmGovSchemaPatrolRunDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketStmtSchemaDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseStmtDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseStmtSchemaDO;
import com.clougence.clouddm.sdk.execute.meta.DsElement;
import com.clougence.schema.umi.special.rdb.RdbColumn;
import com.clougence.schema.umi.special.rdb.RdbTable;
import com.clougence.schema.umi.struts.UmiTypes;

/**
 * Unit tests for SchemaPatrolServiceImpl (sub-task C).
 * <p>
 * Test groups:
 * 1. Explain classifier (EXEMPT_ACTIVE/PENDING_RELEASE/UNEXPLAINED, direction pin)
 * 2. Diff layer (ONLY_PRE/ONLY_PROD/MISMATCH/equal-no-item)
 * 3. Throttle (table limit, time budget -> PARTIAL)
 * 4. Read-only red line (only listLeaf + realTimeFetchSelectObject on DsSchemaService)
 */
public class SchemaPatrolServiceImplTest {

    private SchemaPatrolServiceImpl service;

    private DsSchemaService           dsSchemaService;
    private DmDsConfigService          configService;
    private DbPairDal                 dbPairDal;
    private DmDbPairMapper            pairMapper;
    private DataSourceDal             dsDal;
    private DmDsMapper               dsMapper;
    private ProdReleaseDal           prodReleaseDal;
    private DmProdReleaseMapper       releaseMapper;
    private DmProdReleaseStmtMapper  releaseStmtMapper;
    private DmProdReleaseStmtSchemaMapper releaseSchemaMapper;
    private TicketDbStmtDal          ticketDbStmtDal;
    private DmTicketDbStmtMapper     ticketStmtMapper;
    private DmTicketStmtSchemaMapper ticketSchemaMapper;
    private GovSchemaPatrolDal       patrolDal;
    private DmGovSchemaPatrolRunMapper runMapper;
    private DmGovSchemaPatrolItemMapper itemMapper;
    private SystemDal                systemDal;
    private ReleaseSchemaPrecheckServiceImpl precheckService;

    private DmDsDO preDsDO;
    private DmDsDO prodDsDO;

    private static final long   PRE_DS_ID  = 20L;
    private static final long   PROD_DS_ID = 10L;
    private static final long   DS_ENV_ID  = 1L;
    private static final String PRE_DB     = "pre_db";
    private static final String PROD_DB    = "prod_db";
    private static final long   PAIR_ID    = 1L;
    private static final long   RUN_ID     = 100L;

    @Before
    public void setUp() {
        service = new SchemaPatrolServiceImpl();

        dsSchemaService = mock(DsSchemaService.class);
        configService = mock(DmDsConfigService.class);
        dbPairDal = mock(DbPairDal.class);
        pairMapper = mock(DmDbPairMapper.class);
        dsDal = mock(DataSourceDal.class);
        dsMapper = mock(DmDsMapper.class);
        prodReleaseDal = mock(ProdReleaseDal.class);
        releaseMapper = mock(DmProdReleaseMapper.class);
        releaseStmtMapper = mock(DmProdReleaseStmtMapper.class);
        releaseSchemaMapper = mock(DmProdReleaseStmtSchemaMapper.class);
        ticketDbStmtDal = mock(TicketDbStmtDal.class);
        ticketStmtMapper = mock(DmTicketDbStmtMapper.class);
        ticketSchemaMapper = mock(DmTicketStmtSchemaMapper.class);
        patrolDal = mock(GovSchemaPatrolDal.class);
        runMapper = mock(DmGovSchemaPatrolRunMapper.class);
        itemMapper = mock(DmGovSchemaPatrolItemMapper.class);
        systemDal = mock(SystemDal.class);
        precheckService = new ReleaseSchemaPrecheckServiceImpl();

        when(dbPairDal.pairMapper()).thenReturn(pairMapper);
        when(dsDal.dsMapper()).thenReturn(dsMapper);
        when(prodReleaseDal.releaseMapper()).thenReturn(releaseMapper);
        when(prodReleaseDal.stmtMapper()).thenReturn(releaseStmtMapper);
        when(prodReleaseDal.schemaMapper()).thenReturn(releaseSchemaMapper);
        when(ticketDbStmtDal.stmtMapper()).thenReturn(ticketStmtMapper);
        when(ticketDbStmtDal.schemaMapper()).thenReturn(ticketSchemaMapper);
        when(patrolDal.runMapper()).thenReturn(runMapper);
        when(patrolDal.itemMapper()).thenReturn(itemMapper);

        // DS identities — stored as fields for mock matchers
        preDsDO = mock(DmDsDO.class);
        when(preDsDO.getId()).thenReturn(PRE_DS_ID);
        when(preDsDO.getDsEnvId()).thenReturn(DS_ENV_ID);
        prodDsDO = mock(DmDsDO.class);
        when(prodDsDO.getId()).thenReturn(PROD_DS_ID);
        when(prodDsDO.getDsEnvId()).thenReturn(DS_ENV_ID);
        when(dsMapper.queryDsIdentityById(PRE_DS_ID)).thenReturn(preDsDO);
        when(dsMapper.queryDsIdentityById(PROD_DS_ID)).thenReturn(prodDsDO);

        // Default levelsParam
        Map<UmiTypes, Object> levelsParam = new HashMap<>();
        DsLevels dsLevels = new DsLevels(String.valueOf(DS_ENV_ID), preDsDO,
            List.of(String.valueOf(DS_ENV_ID), String.valueOf(PRE_DS_ID), PRE_DB),
            List.of(PRE_DB), List.of(UmiTypes.Schema), levelsParam);
        when(configService.parseLevels(anyList())).thenReturn(dsLevels);

        // Default config: large limits, no throttle
        when(systemDal.fetchSystemConf(eq("govSchemaPatrolTableLimit"), eq(Integer.class), anyInt())).thenReturn(500);
        when(systemDal.fetchSystemConf(eq("govSchemaPatrolSleepMs"), eq(Long.class), anyLong())).thenReturn(0L);
        when(systemDal.fetchSystemConf(eq("govSchemaPatrolBudgetMinutes"), eq(Long.class), anyLong())).thenReturn(10L);
        when(systemDal.fetchSystemConf(eq("govSchemaPatrolKeepRuns"), eq(Integer.class), anyInt())).thenReturn(30);

        // Default: no active releases, no pre stmts
        when(releaseMapper.listActive()).thenReturn(java.util.Collections.emptyList());
        when(ticketStmtMapper.queryByDsAndDb(anyLong(), anyString())).thenReturn(java.util.Collections.emptyList());

        // Synchronous executor for tests (runs task immediately in the same thread)
        ExecutorService syncExecutor = new AbstractExecutorService() {
            @Override public void execute(Runnable command) { command.run(); }
            @Override public void shutdown() {}
            @Override public List<Runnable> shutdownNow() { return java.util.Collections.emptyList(); }
            @Override public boolean isShutdown() { return false; }
            @Override public boolean isTerminated() { return false; }
            @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
        };

        ReflectionTestUtils.setField(service, "dsSchemaService", dsSchemaService);
        ReflectionTestUtils.setField(service, "configService", configService);
        ReflectionTestUtils.setField(service, "dbPairDal", dbPairDal);
        ReflectionTestUtils.setField(service, "dsDal", dsDal);
        ReflectionTestUtils.setField(service, "prodReleaseDal", prodReleaseDal);
        ReflectionTestUtils.setField(service, "ticketDbStmtDal", ticketDbStmtDal);
        ReflectionTestUtils.setField(service, "govSchemaPatrolDal", patrolDal);
        ReflectionTestUtils.setField(service, "systemDal", systemDal);
        ReflectionTestUtils.setField(service, "precheckService", precheckService);
        ReflectionTestUtils.setField(service, "patrolExecutor", syncExecutor);
    }

    // ==================== helpers ====================

    private DmDbPairDO buildPair() {
        DmDbPairDO pair = new DmDbPairDO();
        pair.setId(PAIR_ID);
        pair.setPreDsId(PRE_DS_ID);
        pair.setPreDbName(PRE_DB);
        pair.setProdDsId(PROD_DS_ID);
        pair.setProdDbName(PROD_DB);
        pair.setStatus("ENABLED");
        return pair;
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
        cols.put("id", id);
        table.setColumns(cols);
        return table;
    }

    private RdbTable buildTableWithExtraCol(String name, String colName, String dataType) {
        RdbTable table = buildTable(name);
        Map<String, RdbColumn> cols = new HashMap<>(table.getColumns());
        RdbColumn extra = new RdbColumn();
        extra.setName(colName);
        extra.setIndex(2);
        extra.setAttribute(MyUmiAttributeNames.DATA_TYPE, dataType);
        extra.setAttribute(MyUmiAttributeNames.COLUMN_TYPE, dataType);
        cols.put(colName, extra);
        table.setColumns(cols);
        return table;
    }

    /**
     * Set up table lists for pre and prod sides. Uses sequential thenReturn
     * since listLeaf is called twice (pre first, prod second).
     */
    private void mockTableLists(List<String> preTables, List<String> prodTables) {
        when(dsSchemaService.listLeaf(any(), any(), eq(UmiTypes.Table), any(), eq(true)))
            .thenReturn(toElements(preTables), toElements(prodTables));
    }

    private void mockPreLiveTable(String tableName, RdbTable table) {
        when(dsSchemaService.realTimeFetchSelectObject(eq(preDsDO), any(), eq(tableName)))
            .thenReturn(table);
    }

    private void mockProdLiveTable(String tableName, RdbTable table) {
        when(dsSchemaService.realTimeFetchSelectObject(eq(prodDsDO), any(), eq(tableName)))
            .thenReturn(table);
    }

    private List<DsElement> toElements(List<String> names) {
        List<DsElement> elements = new ArrayList<>();
        for (String n : names) {
            DsElement e = new DsElement();
            e.setObjName(n);
            elements.add(e);
        }
        return elements;
    }

    private List<DmGovSchemaPatrolItemDO> captureItems() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DmGovSchemaPatrolItemDO>> captor = ArgumentCaptor.forClass(List.class);
        verify(itemMapper).batchInsert(captor.capture());
        return captor.getValue();
    }

    // ==================== Group 1: Explain classifier ====================

    @Test
    public void classify_exemptActive_tableInActiveRelease() throws Exception {
        DmDbPairDO pair = buildPair();

        DmProdReleaseDO release = new DmProdReleaseDO();
        release.setId(50L);
        release.setReleaseNo("REL-001");
        release.setStatus("EXECUTING");
        when(releaseMapper.listActive()).thenReturn(List.of(release));

        DmProdReleaseStmtDO stmt = new DmProdReleaseStmtDO();
        stmt.setId(60L);
        stmt.setReleaseId(50L);
        stmt.setProdDsId(PROD_DS_ID);
        stmt.setProdDbName(PROD_DB);
        stmt.setSqlContent("ALTER TABLE exempt_table ADD COLUMN c int");
        when(releaseStmtMapper.queryByReleaseId(50L)).thenReturn(List.of(stmt));

        DmProdReleaseStmtSchemaDO schemaRow = new DmProdReleaseStmtSchemaDO();
        schemaRow.setReleaseStmtId(60L);
        schemaRow.setTableName("exempt_table");
        when(releaseSchemaMapper.queryByReleaseStmtId(60L)).thenReturn(List.of(schemaRow));

        // Common table with MISMATCH — should be EXEMPT_ACTIVE
        mockTableLists(List.of("exempt_table"), List.of("exempt_table"));
        mockPreLiveTable("exempt_table", buildTableWithExtraCol("exempt_table", "extra", "int"));
        mockProdLiveTable("exempt_table", buildTable("exempt_table"));

        service.patrolPair(pair, RUN_ID);

        List<DmGovSchemaPatrolItemDO> items = captureItems();
        assertEquals(1, items.size());
        assertEquals("exempt_table", items.get(0).getTableName());
        assertEquals("MISMATCH", items.get(0).getDiffClass());
        assertEquals("EXEMPT_ACTIVE", items.get(0).getExplainClass());
        assertEquals("REL-001", items.get(0).getExplainRef());
    }

    @Test
    public void classify_exemptActive_includesPartialFailed() throws Exception {
        DmDbPairDO pair = buildPair();

        DmProdReleaseDO release = new DmProdReleaseDO();
        release.setId(51L);
        release.setReleaseNo("REL-002");
        release.setStatus("PARTIAL_FAILED");
        when(releaseMapper.listActive()).thenReturn(List.of(release));

        DmProdReleaseStmtDO stmt = new DmProdReleaseStmtDO();
        stmt.setId(61L);
        stmt.setReleaseId(51L);
        stmt.setProdDsId(PROD_DS_ID);
        stmt.setProdDbName(PROD_DB);
        stmt.setSqlContent("CREATE TABLE pf_table (id int)");
        when(releaseStmtMapper.queryByReleaseId(51L)).thenReturn(List.of(stmt));
        when(releaseSchemaMapper.queryByReleaseStmtId(61L)).thenReturn(java.util.Collections.emptyList());

        // Pre has "pf_table", prod doesn't -> ONLY_PRE, but EXEMPT_ACTIVE
        mockTableLists(List.of("pf_table"), List.of());

        service.patrolPair(pair, RUN_ID);

        List<DmGovSchemaPatrolItemDO> items = captureItems();
        assertEquals(1, items.size());
        assertEquals("ONLY_PRE", items.get(0).getDiffClass());
        assertEquals("EXEMPT_ACTIVE", items.get(0).getExplainClass());
    }

    @Test
    public void classify_pendingRelease_successPreStmtNotMerged() throws Exception {
        DmDbPairDO pair = buildPair();

        DmTicketDbStmtDO preStmt = new DmTicketDbStmtDO();
        preStmt.setId(70L);
        preStmt.setTicketId(80L);
        preStmt.setDsId(PRE_DS_ID);
        preStmt.setDbName(PRE_DB);
        preStmt.setExecStatus("SUCCESS");
        when(ticketStmtMapper.queryByDsAndDb(PRE_DS_ID, PRE_DB)).thenReturn(List.of(preStmt));
        when(releaseStmtMapper.queryBySourceStmtId(70L)).thenReturn(null);

        DmTicketStmtSchemaDO snapRow = new DmTicketStmtSchemaDO();
        snapRow.setStmtId(70L);
        snapRow.setTableName("pending_table");
        when(ticketSchemaMapper.queryByStmtId(70L)).thenReturn(List.of(snapRow));

        // Common table with MISMATCH -> should be PENDING_RELEASE
        mockTableLists(List.of("pending_table"), List.of("pending_table"));
        mockPreLiveTable("pending_table", buildTableWithExtraCol("pending_table", "new_col", "int"));
        mockProdLiveTable("pending_table", buildTable("pending_table"));

        service.patrolPair(pair, RUN_ID);

        List<DmGovSchemaPatrolItemDO> items = captureItems();
        assertEquals(1, items.size());
        assertEquals("MISMATCH", items.get(0).getDiffClass());
        assertEquals("PENDING_RELEASE", items.get(0).getExplainClass());
        assertEquals("80", items.get(0).getExplainRef());
    }

    @Test
    public void classify_onlyProd_neverPendingRelease() throws Exception {
        DmDbPairDO pair = buildPair();

        DmTicketDbStmtDO preStmt = new DmTicketDbStmtDO();
        preStmt.setId(71L);
        preStmt.setTicketId(81L);
        preStmt.setDsId(PRE_DS_ID);
        preStmt.setDbName(PRE_DB);
        preStmt.setExecStatus("SUCCESS");
        when(ticketStmtMapper.queryByDsAndDb(PRE_DS_ID, PRE_DB)).thenReturn(List.of(preStmt));
        when(releaseStmtMapper.queryBySourceStmtId(71L)).thenReturn(null);

        DmTicketStmtSchemaDO snapRow = new DmTicketStmtSchemaDO();
        snapRow.setStmtId(71L);
        snapRow.setTableName("drift_table");
        when(ticketSchemaMapper.queryByStmtId(71L)).thenReturn(List.of(snapRow));

        // ONLY_PROD — even though table is in pending set, direction pin -> UNEXPLAINED
        mockTableLists(List.of(), List.of("drift_table"));

        service.patrolPair(pair, RUN_ID);

        List<DmGovSchemaPatrolItemDO> items = captureItems();
        assertEquals(1, items.size());
        assertEquals("ONLY_PROD", items.get(0).getDiffClass());
        assertEquals("UNEXPLAINED", items.get(0).getExplainClass());
    }

    @Test
    public void classify_noExplanationSource_unexplained() throws Exception {
        DmDbPairDO pair = buildPair();
        mockTableLists(List.of("unknown_table"), List.of());

        service.patrolPair(pair, RUN_ID);

        List<DmGovSchemaPatrolItemDO> items = captureItems();
        assertEquals(1, items.size());
        assertEquals("ONLY_PRE", items.get(0).getDiffClass());
        assertEquals("UNEXPLAINED", items.get(0).getExplainClass());
    }

    // ==================== Group 2: Diff layer ====================

    @Test
    public void diff_onlyPreAndOnlyProd_bothClassified() throws Exception {
        DmDbPairDO pair = buildPair();
        mockTableLists(List.of("pre_only"), List.of("prod_only"));

        service.patrolPair(pair, RUN_ID);

        List<DmGovSchemaPatrolItemDO> items = captureItems();
        boolean hasOnlyPre = items.stream().anyMatch(i ->
            "ONLY_PRE".equals(i.getDiffClass()) && "pre_only".equals(i.getTableName()));
        boolean hasOnlyProd = items.stream().anyMatch(i ->
            "ONLY_PROD".equals(i.getDiffClass()) && "prod_only".equals(i.getTableName()));
        assertTrue(hasOnlyPre);
        assertTrue(hasOnlyProd);
    }

    @Test
    public void diff_mismatch_commonTableDifferentFingerprint() throws Exception {
        DmDbPairDO pair = buildPair();
        mockTableLists(List.of("shared"), List.of("shared"));
        mockPreLiveTable("shared", buildTableWithExtraCol("shared", "extra", "int"));
        mockProdLiveTable("shared", buildTable("shared"));

        service.patrolPair(pair, RUN_ID);

        List<DmGovSchemaPatrolItemDO> items = captureItems();
        assertEquals(1, items.size());
        assertEquals("MISMATCH", items.get(0).getDiffClass());
        assertNotNull(items.get(0).getDetailJson());
    }

    @Test
    public void diff_equal_noItemCreated() throws Exception {
        DmDbPairDO pair = buildPair();
        mockTableLists(List.of("same"), List.of("same"));
        RdbTable table = buildTable("same");
        mockPreLiveTable("same", table);
        mockProdLiveTable("same", table);

        service.patrolPair(pair, RUN_ID);

        verify(itemMapper, never()).batchInsert(anyList());
    }

    @Test
    public void diff_emptyTableLists_noItems() throws Exception {
        DmDbPairDO pair = buildPair();
        mockTableLists(List.of(), List.of());

        service.patrolPair(pair, RUN_ID);

        verify(itemMapper, never()).batchInsert(anyList());
    }

    // ==================== Group 3: Throttle ====================

    @Test
    public void throttle_tableLimitExceeded_partial() throws Exception {
        DmDbPairDO pair = buildPair();
        when(systemDal.fetchSystemConf(eq("govSchemaPatrolTableLimit"), eq(Integer.class), anyInt())).thenReturn(1);
        mockTableLists(List.of("t1", "t2", "t3"), List.of());

        SchemaPatrolServiceImpl.PairResult result = service.patrolPair(pair, RUN_ID);

        assertTrue(result.partial);
        List<DmGovSchemaPatrolItemDO> items = captureItems();
        assertEquals(1, items.size());
    }

    @Test
    public void throttle_timeBudgetExceeded_partial() throws Exception {
        DmDbPairDO pair = buildPair();
        when(systemDal.fetchSystemConf(eq("govSchemaPatrolBudgetMinutes"), eq(Long.class), anyLong())).thenReturn(0L);
        mockTableLists(List.of("t1", "t2"), List.of());

        SchemaPatrolServiceImpl.PairResult result = service.patrolPair(pair, RUN_ID);

        assertTrue(result.partial);
        verify(itemMapper, never()).batchInsert(anyList());
    }

    // ==================== Group 4: Read-only red line ====================

    @Test
    public void readOnly_onlyListLeafAndRealTimeFetchCalled() throws Exception {
        DmDbPairDO pair = buildPair();
        mockTableLists(List.of("t1"), List.of("t1"));
        RdbTable table = buildTable("t1");
        mockPreLiveTable("t1", table);
        mockProdLiveTable("t1", table);

        service.patrolPair(pair, RUN_ID);

        verify(dsSchemaService, atLeast(1)).listLeaf(any(), any(), eq(UmiTypes.Table), any(), eq(true));
        verify(dsSchemaService, atLeast(1)).realTimeFetchSelectObject(any(), any(), anyString());
        verifyNoMoreInteractions(dsSchemaService);
    }

    // ==================== Run lifecycle ====================

    @Test
    public void executeRun_allPairsDone_statusDone() {
        DmDbPairDO pair = buildPair();
        mockTableLists(List.of(), List.of());

        service.executeRun(RUN_ID, List.of(pair));

        verify(runMapper).updateStatus(eq(RUN_ID), eq("DONE"), eq(1), eq(0), isNull());
        verify(runMapper).incrementPairsDone(RUN_ID);
    }

    @Test
    public void executeRun_pairFails_statusPartial() {
        DmDbPairDO pair = buildPair();
        when(dsSchemaService.listLeaf(any(), any(), eq(UmiTypes.Table), any(), eq(true)))
            .thenThrow(new RuntimeException("fail"));

        service.executeRun(RUN_ID, List.of(pair));

        verify(runMapper).updateStatus(eq(RUN_ID), eq("PARTIAL"), eq(1), eq(0), isNull());
    }

    @Test
    public void startRun_collisionReturnsMinusOne() {
        DmGovSchemaPatrolRunDO running = new DmGovSchemaPatrolRunDO();
        running.setId(999L);
        running.setStatus("RUNNING");
        when(runMapper.queryRunning()).thenReturn(running);

        long runId = service.startRun("MANUAL", "user1", null);
        assertEquals(-1L, runId);
        verify(runMapper, never()).insertRun(any());
    }

    @Test
    public void startRun_success_insertsAndReturnsRunId() {
        when(runMapper.queryRunning()).thenReturn(null);
        when(pairMapper.selectList(any())).thenReturn(java.util.Collections.emptyList());
        // runMapper.insertRun needs to set the id — mock it
        when(runMapper.insertRun(any())).thenAnswer(inv -> {
            DmGovSchemaPatrolRunDO run = inv.getArgument(0);
            run.setId(42L);
            return 1;
        });

        long runId = service.startRun("MANUAL", "user1", null);
        assertEquals(42L, runId);
        verify(runMapper).insertRun(any());
    }

    @Test
    public void retentionCleanup_deletesOldRunsAndItems() {
        DmDbPairDO pair = buildPair();
        mockTableLists(List.of(), List.of());

        service.executeRun(RUN_ID, List.of(pair));

        verify(itemMapper).deleteItemsByOldRuns(30);
        verify(runMapper).deleteOldRuns(30);
    }
}
