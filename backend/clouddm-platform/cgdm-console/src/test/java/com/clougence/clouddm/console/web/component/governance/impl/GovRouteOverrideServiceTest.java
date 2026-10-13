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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Properties;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.console.web.component.governance.GovRouteOverrideService;
import com.clougence.clouddm.console.web.model.fo.prodrelease.RouteOverrideGrantFO;
import com.clougence.clouddm.console.web.model.vo.prodrelease.RouteOverrideAuditObj;
import com.clougence.clouddm.console.web.model.vo.prodrelease.RouteOverrideVO;
import com.clougence.clouddm.platform.dal.access.DbChangeEventDal;
import com.clougence.clouddm.platform.dal.access.ProdReleaseDal;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangeEventMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmGovRouteOverrideMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmGovSchemaGateOverrideMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseStmtMapper;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO;
import com.clougence.clouddm.platform.dal.model.monitor.AuditType;
import com.clougence.clouddm.platform.dal.model.monitor.SecurityLevel;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmGovRouteOverrideDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseStmtDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseStmtSegmentDO;
import com.clougence.clouddm.sdk.security.auth.def.SecRoleAuthLabel;

/**
 * ADR-013 M3 governed route-override service (task 10-12-release-chain-frozen-routing; class
 * explicitly authorized in design D12 / implement.md M3.6).
 * <p>
 * Covers the full override-chain semantics the two dispatch block points rely on: create
 * validation (reason mandatory ≤1024 — no rubber stamps; expiry future AND ≤ 7 days — no
 * permanent exemptions; tenant isolation; stmt ownership; stmtId mandatory — granularity is
 * exactly (releaseId, stmtId); ACTIVE-UNIQUE — at most one consumable PENDING per stmt), list
 * status derivation (PENDING/CONSUMED/EXPIRED), the SINGLE-USE consumption contract (CAS
 * PENDING→CONSUMED + covered_seg_idxs freeze; CAS loss ⇒ null ⇒ the block stands — fail-closed),
 * and the consumption twin trails (RELEASE_ROUTE_OVERRIDE event same-tx + HIGH op_audit in the
 * GRANTOR's name, disclosure whitelist — no SQL text).
 * <p>
 * ISOLATION pins (裁决 4 red line): this service NEVER touches the C4 schema-gate override
 * surface (gateOverrideMapper zero interactions), and the four-point registration of the
 * GOV_ROUTE_OVERRIDE label + the two GOV_ROUTE_OVERRIDE_* audit types is complete.
 * <p>
 * Discipline: JUnit4 + hand-written mocks + ReflectionTestUtils — no Spring context.
 */
public class GovRouteOverrideServiceTest {

    private GovRouteOverrideServiceImpl service;
    private ProdReleaseDal              prodReleaseDal;
    private DmProdReleaseMapper         releaseMapper;
    private DmProdReleaseStmtMapper     stmtMapper;
    private DmGovRouteOverrideMapper    routeOverrideMapper;
    private DmGovSchemaGateOverrideMapper gateOverrideMapper;
    private DbChangeEventDal            dbChangeEventDal;
    private DmDbChangeEventMapper       eventMapper;
    private com.clougence.rdp.service.RdpOpAuditService rdpOpAuditService;

    private static final String PUID = "puid-001";
    private static final String UID  = "dba-uid-001";
    private static final long   RELEASE_ID = 500L;
    private static final long   STMT_ID    = 800L;

    @Before
    public void setUp() {
        service = new GovRouteOverrideServiceImpl();

        prodReleaseDal = mock(ProdReleaseDal.class);
        releaseMapper = mock(DmProdReleaseMapper.class);
        stmtMapper = mock(DmProdReleaseStmtMapper.class);
        routeOverrideMapper = mock(DmGovRouteOverrideMapper.class);
        gateOverrideMapper = mock(DmGovSchemaGateOverrideMapper.class);
        when(prodReleaseDal.releaseMapper()).thenReturn(releaseMapper);
        when(prodReleaseDal.stmtMapper()).thenReturn(stmtMapper);
        when(prodReleaseDal.routeOverrideMapper()).thenReturn(routeOverrideMapper);
        when(prodReleaseDal.gateOverrideMapper()).thenReturn(gateOverrideMapper);

        DmProdReleaseDO release = new DmProdReleaseDO();
        release.setId(RELEASE_ID);
        release.setReleaseNo("REL-500");
        release.setPrimaryUid(PUID);
        when(releaseMapper.queryById(RELEASE_ID)).thenReturn(release);

        DmProdReleaseStmtDO stmt = new DmProdReleaseStmtDO();
        stmt.setId(STMT_ID);
        stmt.setReleaseId(RELEASE_ID);
        when(stmtMapper.queryById(STMT_ID)).thenReturn(stmt);

        dbChangeEventDal = mock(DbChangeEventDal.class);
        eventMapper = mock(DmDbChangeEventMapper.class);
        when(dbChangeEventDal.eventMapper()).thenReturn(eventMapper);
        when(eventMapper.insert(any(DmDbChangeEventDO.class))).thenReturn(1);

        rdpOpAuditService = mock(com.clougence.rdp.service.RdpOpAuditService.class);

        ReflectionTestUtils.setField(service, "prodReleaseDal", prodReleaseDal);
        ReflectionTestUtils.setField(service, "dbChangeEventDal", dbChangeEventDal);
        ReflectionTestUtils.setField(service, "rdpOpAuditService", rdpOpAuditService);
    }

    // ==================== helpers ====================

    private RouteOverrideGrantFO buildGrantFO(Long stmtId, String reason, long expireAt) {
        RouteOverrideGrantFO fo = new RouteOverrideGrantFO();
        fo.setReleaseId(RELEASE_ID);
        fo.setStmtId(stmtId);
        fo.setReason(reason);
        fo.setExpireAt(expireAt);
        return fo;
    }

    private DmGovRouteOverrideDO buildRow(long id, String status, Date expireAt) {
        DmGovRouteOverrideDO row = new DmGovRouteOverrideDO();
        row.setId(id);
        row.setReleaseId(RELEASE_ID);
        row.setStmtId(STMT_ID);
        row.setReason("risk accepted: maintenance window");
        row.setCreatedBy(UID);
        row.setStatus(status);
        row.setExpireAt(expireAt);
        return row;
    }

    private DmProdReleaseStmtSegmentDO buildSeg(int segIdx, String type, String sqlText) {
        DmProdReleaseStmtSegmentDO seg = new DmProdReleaseStmtSegmentDO();
        seg.setReleaseId(RELEASE_ID);
        seg.setReleaseStmtId(STMT_ID);
        seg.setSegIdx(segIdx);
        seg.setType(type);
        seg.setStmtIndex(segIdx);
        seg.setTableName("t_order");
        seg.setSqlText(sqlText);
        seg.setStatus("EXECUTING");
        return seg;
    }

    // ==================== create validation ====================

    @Test
    public void create_valid_insertsPendingRow() {
        when(routeOverrideMapper.findConsumable(eq(RELEASE_ID), eq(STMT_ID), any(Date.class))).thenReturn(null);
        when(routeOverrideMapper.insert(any(DmGovRouteOverrideDO.class))).thenAnswer(inv -> {
            inv.getArgument(0, DmGovRouteOverrideDO.class).setId(1L);
            return 1;
        });

        long id = service.create(PUID, UID,
            buildGrantFO(STMT_ID, "  batch executor unavailable, direct replay approved  ",
                System.currentTimeMillis() + 3600_000L));

        assertEquals(1L, id);
        ArgumentCaptor<DmGovRouteOverrideDO> captor = ArgumentCaptor.forClass(DmGovRouteOverrideDO.class);
        verify(routeOverrideMapper).insert(captor.capture());
        DmGovRouteOverrideDO row = captor.getValue();
        assertEquals(Long.valueOf(RELEASE_ID), row.getReleaseId());
        assertEquals(Long.valueOf(STMT_ID), row.getStmtId());
        assertEquals(UID, row.getCreatedBy());
        assertEquals("PENDING", row.getStatus());
        assertEquals("batch executor unavailable, direct replay approved", row.getReason()); // trimmed
        assertNotNull(row.getExpireAt());
    }

    @Test
    public void create_blankReason_throws() {
        try {
            service.create(PUID, UID, buildGrantFO(STMT_ID, "   ", System.currentTimeMillis() + 3600_000L));
            fail("Expected ErrorMessageException — reason is mandatory (no rubber-stamp grants)");
        } catch (ErrorMessageException ex) {
            assertTrue(ex.getMessage().contains("reason"));
        }
        verify(routeOverrideMapper, never()).insert(any(DmGovRouteOverrideDO.class));
    }

    @Test
    public void create_reasonOver1024_throws() {
        try {
            service.create(PUID, UID,
                buildGrantFO(STMT_ID, "x".repeat(1025), System.currentTimeMillis() + 3600_000L));
            fail("Expected ErrorMessageException — reason ≤ 1024 characters");
        } catch (ErrorMessageException ex) {
            assertTrue(ex.getMessage().contains("1024"));
        }
        verify(routeOverrideMapper, never()).insert(any(DmGovRouteOverrideDO.class));
    }

    @Test
    public void create_expireAtPast_throws() {
        try {
            service.create(PUID, UID, buildGrantFO(STMT_ID, "reason", System.currentTimeMillis() - 1000L));
            fail("Expected ErrorMessageException");
        } catch (ErrorMessageException ex) {
            assertTrue(ex.getMessage().contains("future"));
        }
    }

    @Test
    public void create_expireAtBeyond7Days_throws() {
        long eightDays = System.currentTimeMillis() + 8L * 24 * 60 * 60 * 1000;
        try {
            service.create(PUID, UID, buildGrantFO(STMT_ID, "reason", eightDays));
            fail("Expected ErrorMessageException — no permanent exemptions (≤ 7 days)");
        } catch (ErrorMessageException ex) {
            assertTrue(ex.getMessage().contains("7 days"));
        }
    }

    @Test
    public void create_nullStmtId_throws() {
        // granularity is exactly (releaseId, stmtId) — no release-level blanket (design D8)
        try {
            service.create(PUID, UID, buildGrantFO(null, "reason", System.currentTimeMillis() + 3600_000L));
            fail("Expected ErrorMessageException — stmtId is mandatory");
        } catch (ErrorMessageException ex) {
            assertTrue(ex.getMessage().contains("stmtId"));
        }
        verify(routeOverrideMapper, never()).insert(any(DmGovRouteOverrideDO.class));
    }

    @Test
    public void create_wrongTenant_throws() {
        when(releaseMapper.queryById(RELEASE_ID)).thenAnswer(inv -> {
            DmProdReleaseDO other = new DmProdReleaseDO();
            other.setId(RELEASE_ID);
            other.setPrimaryUid("other-tenant");
            return other;
        });
        try {
            service.create(PUID, UID, buildGrantFO(STMT_ID, "reason", System.currentTimeMillis() + 3600_000L));
            fail("Expected ErrorMessageException — cross-tenant reads as not-found");
        } catch (ErrorMessageException ex) {
            assertTrue(ex.getMessage().contains("not found"));
        }
    }

    @Test
    public void create_stmtNotInRelease_throws() {
        DmProdReleaseStmtDO foreign = new DmProdReleaseStmtDO();
        foreign.setId(801L);
        foreign.setReleaseId(999L); // another release
        when(stmtMapper.queryById(801L)).thenReturn(foreign);
        try {
            service.create(PUID, UID, buildGrantFO(801L, "reason", System.currentTimeMillis() + 3600_000L));
            fail("Expected ErrorMessageException");
        } catch (ErrorMessageException ex) {
            assertTrue(ex.getMessage().contains("801"));
        }
    }

    @Test
    public void create_activeOverrideExists_throws() {
        // active-unique (design D8): at most ONE consumable PENDING per (releaseId, stmtId) —
        // service-enforced (the C4 migration shape: no DB partial-unique constraint)
        when(routeOverrideMapper.findConsumable(eq(RELEASE_ID), eq(STMT_ID), any(Date.class)))
            .thenReturn(buildRow(9L, "PENDING", new Date(System.currentTimeMillis() + 3600_000L)));
        try {
            service.create(PUID, UID, buildGrantFO(STMT_ID, "reason", System.currentTimeMillis() + 3600_000L));
            fail("Expected ErrorMessageException — an active route override already exists");
        } catch (ErrorMessageException ex) {
            assertTrue(ex.getMessage().contains("already exists"));
        }
        verify(routeOverrideMapper, never()).insert(any(DmGovRouteOverrideDO.class));
    }

    // ==================== list status derivation ====================

    @Test
    public void list_derivesAllThreeStatuses() {
        long now = System.currentTimeMillis();
        when(routeOverrideMapper.queryByReleaseId(RELEASE_ID)).thenReturn(List.of(
            buildRow(1L, "PENDING", new Date(now + 3600_000L)),  // PENDING (active)
            buildRow(2L, "CONSUMED", new Date(now + 3600_000L)),// CONSUMED
            buildRow(3L, "PENDING", new Date(now - 1000L))      // EXPIRED (derived — no sweeper)
        ));

        List<RouteOverrideVO> vos = service.list(PUID, RELEASE_ID);

        assertEquals(3, vos.size());
        assertEquals("PENDING", vos.get(0).getStatus());
        assertEquals("CONSUMED", vos.get(1).getStatus());
        assertEquals("EXPIRED", vos.get(2).getStatus());
    }

    @Test
    public void list_wrongTenant_throws() {
        when(releaseMapper.queryById(RELEASE_ID)).thenReturn(null);
        try {
            service.list(PUID, RELEASE_ID);
            fail("Expected ErrorMessageException");
        } catch (ErrorMessageException ex) {
            assertTrue(ex.getMessage().contains("not found"));
        }
    }

    // ==================== findAndConsume: single-use CAS contract ====================

    @Test
    public void findAndConsume_consumable_casConsumesWritesEventAndAudit() {
        DmGovRouteOverrideDO candidate = buildRow(10L, "PENDING", new Date(System.currentTimeMillis() + 3600_000L));
        when(routeOverrideMapper.findConsumable(eq(RELEASE_ID), eq(STMT_ID), any(Date.class)))
            .thenReturn(candidate);
        when(routeOverrideMapper.consumeCas(eq(10L), any(Date.class), eq("2"))).thenReturn(1);

        DmProdReleaseDO release = releaseMapper.queryById(RELEASE_ID);
        release.setCreatorUid("creator-001");
        DmProdReleaseStmtDO stmt = stmtMapper.queryById(STMT_ID);
        DmProdReleaseStmtSegmentDO seg = buildSeg(2, "DML_BATCH", "DELETE FROM t_order WHERE status = 0");

        DmGovRouteOverrideDO result = service.findAndConsume(release, stmt, seg,
            GovRouteOverrideService.BLOCK_ROUTE_UNSUPPORTED, "route unsupported");

        assertSame(candidate, result);
        assertEquals("CONSUMED", result.getStatus());
        assertNotNull(result.getConsumedAt());
        assertEquals("2", result.getCoveredSegIdxs()); // the released segIdx frozen at consumption

        // RELEASE_ROUTE_OVERRIDE event — disclosure whitelist: segIdx/type/blockCode/override trail,
        // NO SQL text (C2 discipline)
        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        DmDbChangeEventDO event = eventCaptor.getValue();
        assertEquals("RELEASE_ROUTE_OVERRIDE", event.getEventType());
        assertEquals(Long.valueOf(RELEASE_ID), event.getReleaseId());
        assertEquals("SYSTEM", event.getOperatorUid()); // dispatch scope; grantor trail in eventData
        assertTrue(event.getEventData().contains("\"segIdx\":2"));
        assertTrue(event.getEventData().contains("DML_BATCH"));
        assertTrue(event.getEventData().contains("ROUTE_UNSUPPORTED"));
        assertTrue(event.getEventData().contains("\"overrideId\":10"));
        assertTrue(event.getEventData().contains(UID)); // creator = the grantor
        assertFalse("NO SQL text in eventData", event.getEventData().contains("DELETE FROM"));

        // HIGH op_audit in the GRANTOR's name (the risk-acceptance subject), release resource,
        // obj = the C4-shaped plain trail POJO (GhostOpAuditObj NOT reused)
        ArgumentCaptor<RouteOverrideAuditObj> auditCaptor = ArgumentCaptor.forClass(RouteOverrideAuditObj.class);
        verify(rdpOpAuditService).logAndAddOperationAudit(eq(PUID), eq(UID), anyString(), isNull(),
            eq(RELEASE_ID), auditCaptor.capture(),
            eq(SecurityLevel.HIGH), eq(AuditType.GOV_ROUTE_OVERRIDE_CONSUME),
            eq(com.clougence.clouddm.platform.dal.model.ResourceType.PURE_URL), eq("release:" + RELEASE_ID));
        RouteOverrideAuditObj obj = auditCaptor.getValue();
        assertEquals(Long.valueOf(10L), obj.getOverrideId());
        assertEquals(Integer.valueOf(2), obj.getSegIdx());
        assertEquals("ROUTE_UNSUPPORTED", obj.getBlockCode());
        assertEquals("2", obj.getCoveredSegIdxs());
    }

    @Test
    public void findAndConsume_casLost_returnsNull_noEventNoAudit() {
        // single-use strictness: a concurrent consumption won the CAS — the block STANDS
        DmGovRouteOverrideDO candidate = buildRow(11L, "PENDING", new Date(System.currentTimeMillis() + 3600_000L));
        when(routeOverrideMapper.findConsumable(eq(RELEASE_ID), eq(STMT_ID), any(Date.class)))
            .thenReturn(candidate);
        when(routeOverrideMapper.consumeCas(eq(11L), any(Date.class), anyString())).thenReturn(0);

        DmGovRouteOverrideDO result = service.findAndConsume(releaseMapper.queryById(RELEASE_ID),
            stmtMapper.queryById(STMT_ID), buildSeg(1, "DML_BATCH", "DELETE FROM t_order"),
            GovRouteOverrideService.BLOCK_ROUTE_UNSUPPORTED, "route unsupported");

        assertNull(result);
        verifyNoInteractions(eventMapper);
        verifyNoInteractions(rdpOpAuditService);
    }

    @Test
    public void findAndConsume_noneConsumable_returnsNull() {
        // expired/consumed rows never reach the service (SQL filter) — empty candidate
        when(routeOverrideMapper.findConsumable(anyLong(), anyLong(), any(Date.class))).thenReturn(null);

        assertNull(service.findAndConsume(releaseMapper.queryById(RELEASE_ID),
            stmtMapper.queryById(STMT_ID), buildSeg(1, "GHOST", "ALTER TABLE t_order ADD c INT"),
            GovRouteOverrideService.BLOCK_ROUTE_CAPABILITY, "NO_UNIQUE_KEY"));

        verify(routeOverrideMapper, never()).consumeCas(anyLong(), any(Date.class), any());
        verifyNoInteractions(eventMapper);
        verifyNoInteractions(rdpOpAuditService);
    }

    @Test
    public void findAndConsume_expiryEvaluatedAgainstFreshNow() {
        // the expiry predicate lives in SQL (expire_at > #{now}); pin that the service passes a
        // fresh now so an expired grant can never be selected (DmAuthResDO.isEffective pattern)
        long before = System.currentTimeMillis();
        when(routeOverrideMapper.findConsumable(anyLong(), anyLong(), any(Date.class))).thenReturn(null);

        service.findAndConsume(releaseMapper.queryById(RELEASE_ID), stmtMapper.queryById(STMT_ID),
            buildSeg(1, "GHOST", "ALTER TABLE t_order ADD c INT"),
            GovRouteOverrideService.BLOCK_ROUTE_CAPABILITY, "fail");

        ArgumentCaptor<Date> nowCaptor = ArgumentCaptor.forClass(Date.class);
        verify(routeOverrideMapper).findConsumable(eq(RELEASE_ID), eq(STMT_ID), nowCaptor.capture());
        assertTrue(nowCaptor.getValue().getTime() >= before);
        assertTrue(nowCaptor.getValue().getTime() <= System.currentTimeMillis());
    }

    // ==================== isolation from the C4 schema-gate override (裁决 4 red line) ====================

    @Test
    public void isolation_neverTouchesSchemaGateOverrideSurface() {
        // consuming a ROUTE override must never read/write the C4 schema-gate override table —
        // the two surfaces are fully isolated (two tables, two labels, two events; no
        // cross-substitution in either direction)
        when(routeOverrideMapper.findConsumable(anyLong(), anyLong(), any(Date.class)))
            .thenReturn(buildRow(12L, "PENDING", new Date(System.currentTimeMillis() + 3600_000L)));
        when(routeOverrideMapper.consumeCas(anyLong(), any(Date.class), anyString())).thenReturn(1);

        service.findAndConsume(releaseMapper.queryById(RELEASE_ID), stmtMapper.queryById(STMT_ID),
            buildSeg(3, "DML_BATCH", "UPDATE t_order SET x = 1"),
            GovRouteOverrideService.BLOCK_ROUTE_UNSUPPORTED, "route unsupported");

        verifyNoInteractions(gateOverrideMapper);
    }

    @Test
    public void create_neverTouchesSchemaGateOverrideSurface() {
        when(routeOverrideMapper.findConsumable(eq(RELEASE_ID), eq(STMT_ID), any(Date.class))).thenReturn(null);
        when(routeOverrideMapper.insert(any(DmGovRouteOverrideDO.class))).thenAnswer(inv -> {
            inv.getArgument(0, DmGovRouteOverrideDO.class).setId(2L);
            return 1;
        });

        service.create(PUID, UID, buildGrantFO(STMT_ID, "reason", System.currentTimeMillis() + 3600_000L));

        verifyNoInteractions(gateOverrideMapper);
    }

    // ==================== four-point registration pins (label + audit types + i18n) ====================

    @Test
    public void registration_labelAndAuditTypesComplete() throws Exception {
        // 1. label enum (tag=DBA drives the default role binding via RdpRoleServiceImpl.init)
        assertEquals("GOV_ROUTE_OVERRIDE", SecRoleAuthLabel.GOV_ROUTE_OVERRIDE);
        // 2. audit enum values exist (the init whitelist membership is pinned by the
        //    RdpOpAuditServiceImpl edit; valueOf proves the enum registration)
        assertNotNull(AuditType.valueOf("GOV_ROUTE_OVERRIDE_GRANT"));
        assertNotNull(AuditType.valueOf("GOV_ROUTE_OVERRIDE_CONSUME"));
        // 3. i18n: the label key in all three rdp-auth-label files, the audit-type keys in all
        //    three console label files (三语同键纪律 — missing keys = raw-key UI noise)
        for (String file : new String[] { "META-INF/rdp/sdk/i18n/rdp-auth-label.properties",
                                          "META-INF/rdp/sdk/i18n/rdp-auth-label_en_US.properties",
                                          "META-INF/rdp/sdk/i18n/rdp-auth-label_zh_CN.properties" }) {
            Properties props = loadClasspathProperties(file);
            assertTrue(file + " missing AUTH_KEY_GOV_ROUTE_OVERRIDE",
                props.getProperty("AUTH_KEY_GOV_ROUTE_OVERRIDE") != null
                    && !props.getProperty("AUTH_KEY_GOV_ROUTE_OVERRIDE").isBlank());
        }
        for (String file : new String[] { "rdp/static/i18n/label.properties",
                                          "rdp/static/i18n/label_en_US.properties",
                                          "rdp/static/i18n/label_zh_CN.properties" }) {
            Properties props = loadClasspathProperties(file);
            assertTrue(file + " missing GOV_ROUTE_OVERRIDE_GRANT",
                props.getProperty("GOV_ROUTE_OVERRIDE_GRANT") != null);
            assertTrue(file + " missing GOV_ROUTE_OVERRIDE_CONSUME",
                props.getProperty("GOV_ROUTE_OVERRIDE_CONSUME") != null);
        }
    }

    @Test
    public void registration_auditWhitelistContainsBothTypes() {
        // the init whitelist membership (point 2/3 of the audit four-point contract) — a missing
        // entry silently drops the rows from the audit page filter
        com.clougence.rdp.service.impl.RdpOpAuditServiceImpl auditService
            = new com.clougence.rdp.service.impl.RdpOpAuditServiceImpl();
        ReflectionTestUtils.invokeMethod(auditService, "init");
        @SuppressWarnings("unchecked")
        List<AuditType> whitelist = (List<AuditType>) ReflectionTestUtils.getField(auditService, "auditTypes");
        assertNotNull(whitelist);
        assertTrue(whitelist.contains(AuditType.GOV_ROUTE_OVERRIDE_GRANT));
        assertTrue(whitelist.contains(AuditType.GOV_ROUTE_OVERRIDE_CONSUME));
        // the C4 schema-gate override family stays registered (additive discipline regression guard)
        assertTrue(whitelist.contains(AuditType.GOV_SCHEMA_GATE_OVERRIDE_GRANT));
    }

    private static Properties loadClasspathProperties(String path) throws Exception {
        Properties props = new Properties();
        try (InputStream in = GovRouteOverrideServiceTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull("classpath resource missing: " + path, in);
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return props;
    }

    // ==================== block-reason disclosure truncation ====================

    @Test
    public void findAndConsume_blockReasonTruncatedTo512() {
        when(routeOverrideMapper.findConsumable(eq(RELEASE_ID), eq(STMT_ID), any(Date.class)))
            .thenReturn(buildRow(13L, "PENDING", new Date(System.currentTimeMillis() + 3600_000L)));
        when(routeOverrideMapper.consumeCas(anyLong(), any(Date.class), anyString())).thenReturn(1);

        service.findAndConsume(releaseMapper.queryById(RELEASE_ID), stmtMapper.queryById(STMT_ID),
            buildSeg(1, "GHOST", "ALTER TABLE t_order ADD c INT"),
            GovRouteOverrideService.BLOCK_ROUTE_CAPABILITY, "y".repeat(1200));

        ArgumentCaptor<RouteOverrideAuditObj> auditCaptor = ArgumentCaptor.forClass(RouteOverrideAuditObj.class);
        verify(rdpOpAuditService).logAndAddOperationAudit(anyString(), anyString(), anyString(), isNull(),
            anyLong(), auditCaptor.capture(), any(), any(), any(), contains("release:"));
        assertEquals(512, auditCaptor.getValue().getBlockReason().length());
    }
}
