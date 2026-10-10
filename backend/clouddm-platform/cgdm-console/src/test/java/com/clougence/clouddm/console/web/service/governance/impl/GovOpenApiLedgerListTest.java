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
package com.clougence.clouddm.console.web.service.governance.impl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.constants.DmMcpI18nKey;
import com.clougence.clouddm.console.web.model.fo.openapi.DbChangeLedgerListFO;
import com.clougence.clouddm.console.web.model.vo.openapi.OpenDbChangeLedgerTicketVO;
import com.clougence.clouddm.console.web.model.vo.prodrelease.GovLedgerTicketVO;
import com.clougence.clouddm.console.web.model.vo.prodrelease.LedgerDbItemVO;
import com.clougence.clouddm.console.web.service.governance.GovLedgerService;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.DataSourceDal;
import com.clougence.clouddm.platform.dal.access.DbPairDal;
import com.clougence.clouddm.platform.dal.access.ProdReleaseDal;
import com.clougence.clouddm.platform.dal.access.TicketDbStmtDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.datasource.DmDsMapper;
import com.clougence.clouddm.platform.dal.mapper.dbpair.DmDbPairMapper;
import com.clougence.clouddm.platform.dal.mapper.dbpair.DmDbServiceMapper;
import com.clougence.clouddm.platform.dal.mapper.govticket.DmTicketDbStmtMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseStmtMapper;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.datasource.DmDsDO;
import com.clougence.clouddm.platform.dal.model.dbpair.DmDbPairDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;
import com.clougence.utils.JsonUtils;

/**
 * ding-bridge R5 / design §5 — the open ledger list endpoint ({@code ledgerListOpen}).
 * <p>
 * Pins:
 * <ul>
 * <li>TENANT ISOLATION (hard constraint, prd A4): cross-tenant rows are silently dropped —
 *     an all-cross-tenant projection yields an EMPTY LIST (list semantics, never not-found,
 *     existence not leaked).</li>
 * <li>Single source of truth: admission branches come from the console projection
 *     ({@link GovLedgerService#list}) — the open method never reimplements them.</li>
 * <li>Desensitization: the open VO carries no ownerUid / full SQL / precheck / exec detail
 *     fields (structural reflection pin) and sqlSummary passes through capped.</li>
 * <li>Filters (ticketType / promoted tri-state) and in-memory pagination semantics.</li>
 * <li>Console D6 regression pin: {@code GovLedgerServiceImpl.list(puid)} keeps returning
 *     OTHER tenants' rows (no tenant filtering at the console layer — behavior must not flip).</li>
 * <li>i18n three-file-set pin: both new mcp keys exist (non-blank) in all three properties.</li>
 * </ul>
 */
public class GovOpenApiLedgerListTest {

    private GovOpenApiServiceImpl service;
    private GovLedgerService      govLedgerService;
    private ApprovalDal           approvalDal;
    private DmApprovalMapper      approvalMapper;

    private static final String PUID       = "puid-001";
    private static final String OTHER_PUID = "puid-other";
    private static final String UID        = "uid-001";

    @Before
    public void setUp() {
        service = new GovOpenApiServiceImpl();
        govLedgerService = mock(GovLedgerService.class);
        approvalDal = mock(ApprovalDal.class);
        approvalMapper = mock(DmApprovalMapper.class);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);

        ReflectionTestUtils.setField(service, "govLedgerService", govLedgerService);
        ReflectionTestUtils.setField(service, "approvalDal", approvalDal);
    }

    // ==================== tenant isolation (prd A4 hard constraint) ====================

    @Test
    public void ledgerListOpen_crossTenantRows_droppedSilently() {
        when(govLedgerService.list(PUID)).thenReturn(Arrays.asList(
            buildRow(100L, "PRE_DDL", false),
            buildRow(101L, "PRE_DDL", false)));
        when(approvalMapper.selectBatchIds(anyCollection())).thenReturn(Arrays.asList(
            buildTicket(100L, PUID),
            buildTicket(101L, OTHER_PUID)));

        List<OpenDbChangeLedgerTicketVO> result = service.ledgerListOpen(PUID, UID, new DbChangeLedgerListFO());

        assertEquals("only the caller tenant's row survives", 1, result.size());
        assertEquals(100L, result.get(0).getTicketId());
    }

    @Test
    public void ledgerListOpen_allCrossTenant_emptyListNeverNotFound() {
        when(govLedgerService.list(PUID)).thenReturn(Arrays.asList(
            buildRow(200L, "PRE_DDL", false),
            buildRow(201L, "PROD_DML", true)));
        when(approvalMapper.selectBatchIds(anyCollection())).thenReturn(Arrays.asList(
            buildTicket(200L, OTHER_PUID),
            buildTicket(201L, "puid-third")));

        List<OpenDbChangeLedgerTicketVO> result = service.ledgerListOpen(PUID, UID, new DbChangeLedgerListFO());

        assertNotNull("list semantics — empty list, not null/not-found", result);
        assertTrue(result.isEmpty());
    }

    @Test
    public void ledgerListOpen_ticketVanishedMidFlight_rowSkipped() {
        // projection row whose dm_approval row disappeared between the two reads → skipped, no NPE
        when(govLedgerService.list(PUID)).thenReturn(Collections.singletonList(buildRow(300L, "PRE_DDL", false)));
        when(approvalMapper.selectBatchIds(anyCollection())).thenReturn(Collections.emptyList());

        List<OpenDbChangeLedgerTicketVO> result = service.ledgerListOpen(PUID, UID, new DbChangeLedgerListFO());

        assertTrue(result.isEmpty());
    }

    @Test
    public void ledgerListOpen_delegatesAdmissionToConsoleProjection() {
        // single source of truth pin: the open method consumes GovLedgerService.list (never
        // reimplements the two admission branches) and passes the caller puid through
        when(govLedgerService.list(PUID)).thenReturn(Collections.emptyList());

        service.ledgerListOpen(PUID, UID, new DbChangeLedgerListFO());

        verify(govLedgerService, times(1)).list(PUID);
        verify(approvalMapper, never()).selectBatchIds(anyCollection()); // empty projection short-circuits
    }

    // ==================== desensitization (design §5) ====================

    @Test
    public void ledgerListOpen_openVoCarriesNoFullSqlNoOwnerUid_structuralPin() {
        Set<String> forbidden = Set.of("ownerUid", "sqlContent", "rawSql", "rollBackSql",
            "precheckResult", "execDetail", "primaryUid");
        for (java.lang.reflect.Field f : OpenDbChangeLedgerTicketVO.class.getDeclaredFields()) {
            assertFalse("open ledger VO must not expose " + f.getName(), forbidden.contains(f.getName()));
        }
    }

    @Test
    public void ledgerListOpen_sqlSummaryPassesThrough_listLevelShapeOnly() {
        GovLedgerTicketVO row = buildRow(400L, "PRE_DDL", false);
        String capped = "UPDATE t SET c = 1 WHERE id = 42;"; // projection already caps at 200 chars
        row.setSqlSummary(capped);
        when(govLedgerService.list(PUID)).thenReturn(Collections.singletonList(row));
        when(approvalMapper.selectBatchIds(anyCollection()))
            .thenReturn(Collections.singletonList(buildTicket(400L, PUID)));

        OpenDbChangeLedgerTicketVO vo = service.ledgerListOpen(PUID, UID, new DbChangeLedgerListFO()).get(0);

        assertEquals(capped, vo.getSqlSummary());
        assertTrue(vo.getSqlSummary().length() <= 200);
    }

    // ==================== field mapping ====================

    @Test
    public void ledgerListOpen_mapsAllListLevelFields() {
        GovLedgerTicketVO row = buildRow(500L, "PROD_DML", true);
        row.setTicketTitle("orders 表数据修正");
        row.setServiceName("Order Service");
        row.setServiceId(5L);
        row.setGmtCreate("20261009 100000");
        row.setExecStatus("PENDING");
        row.setSqlSummary("UPDATE orders SET ...");
        row.setApprovalMode("VIA_RELEASE");
        row.setReleaseNo("REL-20261009-0001");
        row.setReleaseStatus("APPROVAL");
        row.setGhostRouted(true);
        row.setDmlBatchRouted(true);
        LedgerDbItemVO preDb = new LedgerDbItemVO();
        preDb.setDsId(10L);
        preDb.setDbName("pre_db");
        preDb.setDsName("pre-ds");
        row.setPreDbs(Collections.singletonList(preDb));
        row.setProdDbs(Collections.singletonList("prod-ds/prod_db"));

        when(govLedgerService.list(PUID)).thenReturn(Collections.singletonList(row));
        when(approvalMapper.selectBatchIds(anyCollection()))
            .thenReturn(Collections.singletonList(buildTicket(500L, PUID)));

        OpenDbChangeLedgerTicketVO vo = service.ledgerListOpen(PUID, UID, new DbChangeLedgerListFO()).get(0);

        assertEquals(500L, vo.getTicketId());
        assertEquals("orders 表数据修正", vo.getTicketTitle());
        assertEquals("Order Service", vo.getServiceName());
        assertEquals(Long.valueOf(5L), vo.getServiceId());
        assertEquals("20261009 100000", vo.getGmtCreate());
        assertEquals("PENDING", vo.getExecStatus());
        assertEquals("UPDATE orders SET ...", vo.getSqlSummary());
        assertEquals("PROD_DML", vo.getTicketType());
        assertEquals("VIA_RELEASE", vo.getApprovalMode());
        assertEquals(1, vo.getPreDbs().size());
        assertEquals("pre_db", vo.getPreDbs().get(0).getDbName());
        assertEquals(Collections.singletonList("prod-ds/prod_db"), vo.getProdDbs());
        assertTrue(vo.isPromoted());
        assertEquals("REL-20261009-0001", vo.getReleaseNo());
        assertEquals("APPROVAL", vo.getReleaseStatus());
        assertTrue(vo.isGhostRouted());
        assertTrue(vo.isDmlBatchRouted());
    }

    // ==================== filters ====================

    @Test
    public void ledgerListOpen_ticketTypeFilter() {
        when(govLedgerService.list(PUID)).thenReturn(Arrays.asList(
            buildRow(600L, "PRE_DDL", false),
            buildRow(601L, "PROD_DML", false)));
        when(approvalMapper.selectBatchIds(anyCollection())).thenReturn(Arrays.asList(
            buildTicket(600L, PUID),
            buildTicket(601L, PUID)));

        DbChangeLedgerListFO fo = new DbChangeLedgerListFO();
        fo.setTicketType("PROD_DML");
        List<OpenDbChangeLedgerTicketVO> result = service.ledgerListOpen(PUID, UID, fo);

        assertEquals(1, result.size());
        assertEquals(601L, result.get(0).getTicketId());
    }

    @Test
    public void ledgerListOpen_promotedTriStateFilter() {
        when(govLedgerService.list(PUID)).thenReturn(Arrays.asList(
            buildRow(700L, "PRE_DDL", true),
            buildRow(701L, "PROD_DML", false)));
        when(approvalMapper.selectBatchIds(anyCollection())).thenReturn(Arrays.asList(
            buildTicket(700L, PUID),
            buildTicket(701L, PUID)));

        DbChangeLedgerListFO onlyMerged = new DbChangeLedgerListFO();
        onlyMerged.setPromoted(Boolean.TRUE);
        List<OpenDbChangeLedgerTicketVO> merged = service.ledgerListOpen(PUID, UID, onlyMerged);
        assertEquals(1, merged.size());
        assertEquals(700L, merged.get(0).getTicketId());

        DbChangeLedgerListFO onlyCandidates = new DbChangeLedgerListFO();
        onlyCandidates.setPromoted(Boolean.FALSE);
        List<OpenDbChangeLedgerTicketVO> candidates = service.ledgerListOpen(PUID, UID, onlyCandidates);
        assertEquals(1, candidates.size());
        assertEquals(701L, candidates.get(0).getTicketId());

        // null (default) → no filtering
        List<OpenDbChangeLedgerTicketVO> all = service.ledgerListOpen(PUID, UID, new DbChangeLedgerListFO());
        assertEquals(2, all.size());
    }

    // ==================== pagination ====================

    @Test
    public void ledgerListOpen_pagination() {
        List<GovLedgerTicketVO> rows = new ArrayList<>();
        List<DmApprovalDO> tickets = new ArrayList<>();
        for (long id = 800L; id < 805L; id++) {
            rows.add(buildRow(id, "PRE_DDL", false));
            tickets.add(buildTicket(id, PUID));
        }
        when(govLedgerService.list(PUID)).thenReturn(rows);
        when(approvalMapper.selectBatchIds(anyCollection())).thenReturn(tickets);

        DbChangeLedgerListFO fo = new DbChangeLedgerListFO();
        fo.setPageNum(2);
        fo.setPageSize(2);
        List<OpenDbChangeLedgerTicketVO> page2 = service.ledgerListOpen(PUID, UID, fo);
        assertEquals(2, page2.size());
        assertEquals(802L, page2.get(0).getTicketId());
        assertEquals(803L, page2.get(1).getTicketId());

        fo.setPageNum(3);
        List<OpenDbChangeLedgerTicketVO> tail = service.ledgerListOpen(PUID, UID, fo);
        assertEquals("last page is short, not padded", 1, tail.size());
        assertEquals(804L, tail.get(0).getTicketId());

        fo.setPageNum(9);
        assertTrue("out-of-range page → empty list", service.ledgerListOpen(PUID, UID, fo).isEmpty());
    }

    @Test
    public void ledgerListOpen_paginationDefaultsClamped() {
        List<GovLedgerTicketVO> rows = new ArrayList<>();
        List<DmApprovalDO> tickets = new ArrayList<>();
        for (long id = 810L; id < 813L; id++) {
            rows.add(buildRow(id, "PRE_DDL", false));
            tickets.add(buildTicket(id, PUID));
        }
        when(govLedgerService.list(PUID)).thenReturn(rows);
        when(approvalMapper.selectBatchIds(anyCollection())).thenReturn(tickets);

        DbChangeLedgerListFO fo = new DbChangeLedgerListFO();
        fo.setPageNum(0);   // <1 → page 1
        fo.setPageSize(-5); // <1 → default 20
        List<OpenDbChangeLedgerTicketVO> result = service.ledgerListOpen(PUID, UID, fo);
        assertEquals(3, result.size());
        assertEquals(810L, result.get(0).getTicketId());
    }

    // ==================== console D6 regression pin (red line: list(puid) zero change) ====================

    @Test
    public void consoleLedgerList_d6Pin_noTenantFilteringAtConsoleLayer() {
        // The console projection MUST keep returning other tenants' rows (D6: every logged-in
        // user sees the whole ledger). If this pin breaks, the console behavior was flipped —
        // tenant isolation belongs to ledgerListOpen ONLY.
        GovLedgerServiceImpl consoleService = new GovLedgerServiceImpl();

        TicketDbStmtDal stmtDal = mock(TicketDbStmtDal.class);
        DmTicketDbStmtMapper stmtMapper = mock(DmTicketDbStmtMapper.class);
        when(stmtDal.stmtMapper()).thenReturn(stmtMapper);
        ApprovalDal consoleApprovalDal = mock(ApprovalDal.class);
        DmApprovalMapper consoleApprovalMapper = mock(DmApprovalMapper.class);
        when(consoleApprovalDal.approvalMapper()).thenReturn(consoleApprovalMapper);
        ProdReleaseDal releaseDal = mock(ProdReleaseDal.class);
        DmProdReleaseStmtMapper releaseStmtMapper = mock(DmProdReleaseStmtMapper.class);
        when(releaseDal.stmtMapper()).thenReturn(releaseStmtMapper);
        DbPairDal pairDal = mock(DbPairDal.class);
        when(pairDal.pairMapper()).thenReturn(mock(DmDbPairMapper.class));
        DmDbServiceMapper serviceMapper = mock(DmDbServiceMapper.class);
        when(pairDal.serviceMapper()).thenReturn(serviceMapper);
        DataSourceDal dsDal = mock(DataSourceDal.class);
        DmDsMapper dsMapper = mock(DmDsMapper.class);
        when(dsDal.dsMapper()).thenReturn(dsMapper);

        ReflectionTestUtils.setField(consoleService, "ticketDbStmtDal", stmtDal);
        ReflectionTestUtils.setField(consoleService, "approvalDal", consoleApprovalDal);
        ReflectionTestUtils.setField(consoleService, "prodReleaseDal", releaseDal);
        ReflectionTestUtils.setField(consoleService, "dbPairDal", pairDal);
        ReflectionTestUtils.setField(consoleService, "dsDal", dsDal);

        DmTicketDbStmtDO stmt = new DmTicketDbStmtDO();
        stmt.setId(900L);
        stmt.setTicketId(900L);
        stmt.setPairId(30L);
        stmt.setDsId(10L);
        stmt.setDbName("pre_db");
        stmt.setSqlContent("CREATE TABLE t(id int);");
        stmt.setExecStatus("SUCCESS");
        when(stmtMapper.selectList(any())).thenReturn(Collections.singletonList(stmt));

        // ticket belongs to ANOTHER tenant — D6: the console list still returns it
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(900L);
        ticket.setTicketStatus(ApprovalStatus.FINISHED);
        ticket.setPrimaryUid(OTHER_PUID);
        ticket.setTicketTitle("other tenant ticket");
        ticket.setGmtCreate(new Date());
        ApprovalMO mo = new ApprovalMO();
        mo.setTicketType("PRE_DDL");
        ticket.setTicketInfo(JsonUtils.toJson(mo));
        when(consoleApprovalMapper.selectBatchIds(anyCollection())).thenReturn(Collections.singletonList(ticket));
        when(releaseStmtMapper.queryBySourceStmtId(900L)).thenReturn(null);

        DmDbPairDO pair = new DmDbPairDO();
        pair.setId(30L);
        pair.setProdDsId(20L);
        pair.setProdDbName("prod_db");
        pair.setStatus("ENABLED");
        when(pairDal.pairMapper().selectBatchIds(anyCollection())).thenReturn(Collections.singletonList(pair));
        DmDsDO ds = new DmDsDO();
        ds.setId(10L);
        ds.setInstanceDesc("ds");
        when(dsMapper.queryDsIdentityById(anyLong())).thenReturn(ds);

        List<GovLedgerTicketVO> consoleRows = consoleService.list(PUID);

        assertEquals("D6 pin: console list has NO tenant filtering (open surface filters, console never does)",
            1, consoleRows.size());
        assertEquals(900L, consoleRows.get(0).getTicketId());
    }

    // ==================== i18n three-file-set pin ====================

    @Test
    public void newMcpKeys_presentInAllThreePropertiesFiles() throws Exception {
        String[] resources = {
            "/i18n/mcp.properties", "/i18n/mcp_en_US.properties", "/i18n/mcp_zh_CN.properties"
        };
        String[] keys = { DmMcpI18nKey.M_DBCHANGE_LEDGER_LIST, DmMcpI18nKey.F_PROMOTED_DESC };
        for (String res : resources) {
            Properties p = new Properties();
            try (InputStream in = getClass().getResourceAsStream(res)) {
                assertNotNull("resource must exist: " + res, in);
                p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
            for (String key : keys) {
                String value = p.getProperty(key);
                assertNotNull("key " + key + " missing in " + res, value);
                assertFalse("key " + key + " blank in " + res, value.trim().isEmpty());
            }
        }
    }

    // ==================== helpers ====================

    private GovLedgerTicketVO buildRow(long ticketId, String ticketType, boolean promoted) {
        GovLedgerTicketVO row = new GovLedgerTicketVO();
        row.setTicketId(ticketId);
        row.setTicketType(ticketType);
        row.setPromoted(promoted);
        row.setPreDbs(Collections.emptyList());
        row.setProdDbs(Collections.emptyList());
        return row;
    }

    private DmApprovalDO buildTicket(long id, String primaryUid) {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(id);
        ticket.setPrimaryUid(primaryUid);
        return ticket;
    }
}
