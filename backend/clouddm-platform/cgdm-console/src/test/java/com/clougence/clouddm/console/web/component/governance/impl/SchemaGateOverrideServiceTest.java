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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.console.web.model.fo.prodrelease.SchemaGateOverrideGrantFO;
import com.clougence.clouddm.console.web.model.vo.prodrelease.SchemaGateOverrideVO;
import com.clougence.clouddm.platform.dal.access.ProdReleaseDal;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmGovSchemaGateOverrideMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseStmtMapper;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmGovSchemaGateOverrideDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseStmtDO;

/**
 * ADR-011 governed schema-gate override service (task 10-11-schema-gate-fail-closed;
 * class explicitly authorized in implement.md Step 6 / design §7).
 * <p>
 * Covers the full override chain semantics that the gates rely on:
 * grant validation (reason mandatory — no rubber stamps; expiry future AND ≤ 7 days — no
 * permanent exemptions; tenant isolation; gateType domain; stmt ownership), list status
 * derivation (ACTIVE/CONSUMED/EXPIRED/REVOKED), revoke CAS (unconsumed only), and the
 * SINGLE-USE consumption contract (stmt-level before release-level; CAS loss ⇒ deny —
 * aligned with the DML_BATCH_RESOLUTION "an unconsumed ruling re-freezes" strictness).
 * <p>
 * Discipline: JUnit4 + hand-written mocks + ReflectionTestUtils — no Spring context.
 */
public class SchemaGateOverrideServiceTest {

    private SchemaGateOverrideServiceImpl service;
    private ProdReleaseDal                prodReleaseDal;
    private DmProdReleaseMapper           releaseMapper;
    private DmProdReleaseStmtMapper       stmtMapper;
    private DmGovSchemaGateOverrideMapper overrideMapper;

    private static final String PUID = "puid-001";
    private static final String UID  = "dba-uid-001";
    private static final long   RELEASE_ID = 500L;

    @Before
    public void setUp() {
        service = new SchemaGateOverrideServiceImpl();

        prodReleaseDal = mock(ProdReleaseDal.class);
        releaseMapper = mock(DmProdReleaseMapper.class);
        stmtMapper = mock(DmProdReleaseStmtMapper.class);
        overrideMapper = mock(DmGovSchemaGateOverrideMapper.class);
        when(prodReleaseDal.releaseMapper()).thenReturn(releaseMapper);
        when(prodReleaseDal.stmtMapper()).thenReturn(stmtMapper);
        when(prodReleaseDal.gateOverrideMapper()).thenReturn(overrideMapper);

        DmProdReleaseDO release = new DmProdReleaseDO();
        release.setId(RELEASE_ID);
        release.setReleaseNo("REL-500");
        release.setPrimaryUid(PUID);
        when(releaseMapper.queryById(RELEASE_ID)).thenReturn(release);

        ReflectionTestUtils.setField(service, "prodReleaseDal", prodReleaseDal);
    }

    // ==================== helpers ====================

    private SchemaGateOverrideGrantFO buildGrantFO(Long stmtId, String gateType, String reason, long expireAt) {
        SchemaGateOverrideGrantFO fo = new SchemaGateOverrideGrantFO();
        fo.setReleaseId(RELEASE_ID);
        fo.setStmtId(stmtId);
        fo.setGateType(gateType);
        fo.setReason(reason);
        fo.setExpireAt(expireAt);
        return fo;
    }

    private DmGovSchemaGateOverrideDO buildRow(long id, Long stmtId, int consumed, int revoked, Date expireAt) {
        DmGovSchemaGateOverrideDO row = new DmGovSchemaGateOverrideDO();
        row.setId(id);
        row.setReleaseId(RELEASE_ID);
        row.setStmtId(stmtId);
        row.setGateType("PRECHECK");
        row.setOperatorUid(UID);
        row.setReason("reason");
        row.setExpireAt(expireAt);
        row.setConsumed(consumed);
        row.setRevoked(revoked);
        return row;
    }

    // ==================== grant validation ====================

    @Test
    public void grant_validStmtLevel_insertsRow() {
        DmProdReleaseStmtDO stmt = new DmProdReleaseStmtDO();
        stmt.setId(600L);
        stmt.setReleaseId(RELEASE_ID);
        when(stmtMapper.queryById(600L)).thenReturn(stmt);
        when(overrideMapper.insert(any(DmGovSchemaGateOverrideDO.class))).thenAnswer(inv -> {
            inv.getArgument(0, DmGovSchemaGateOverrideDO.class).setId(1L);
            return 1;
        });

        long id = service.grant(PUID, UID,
            buildGrantFO(600L, "precheck", "  infra window approved by ops  ", System.currentTimeMillis() + 3600_000L));

        assertEquals(1L, id);
        ArgumentCaptor<DmGovSchemaGateOverrideDO> captor = ArgumentCaptor.forClass(DmGovSchemaGateOverrideDO.class);
        verify(overrideMapper).insert(captor.capture());
        DmGovSchemaGateOverrideDO row = captor.getValue();
        assertEquals(Long.valueOf(RELEASE_ID), row.getReleaseId());
        assertEquals(Long.valueOf(600L), row.getStmtId());
        assertEquals("PRECHECK", row.getGateType());       // normalized upper-case
        assertEquals(UID, row.getOperatorUid());
        assertEquals("infra window approved by ops", row.getReason()); // trimmed
        assertEquals(0, row.getConsumed());
        assertEquals(0, row.getRevoked());
        assertNotNull(row.getExpireAt());
    }

    /**
     * C8 M5.3 (O-C7-6a): the gate table gains its active-uniqueness backstop — pre-C8 multiple
     * ACTIVE grants of one granularity were tolerated (findAndConsume picked the newest); the
     * uk_gate_override_active generated column now rejects the second insert and grant() renders
     * the friendly rejection. The expired-active convergence (materialized expired mark) runs
     * BEFORE the insert so a legitimate re-grant after expiry is never blocked.
     */
    @Test
    public void grant_dbUniqueRace_duplicateKey_friendlyRejection_staleConvergedFirst() {
        DmProdReleaseStmtDO stmt = new DmProdReleaseStmtDO();
        stmt.setId(601L);
        stmt.setReleaseId(RELEASE_ID);
        when(stmtMapper.queryById(601L)).thenReturn(stmt);
        when(overrideMapper.insert(any(DmGovSchemaGateOverrideDO.class)))
            .thenThrow(new org.springframework.dao.DuplicateKeyException("uk_gate_override_active"));

        try {
            service.grant(PUID, UID,
                buildGrantFO(601L, "precheck", "race loser", System.currentTimeMillis() + 3600_000L));
            fail("Expected ErrorMessageException — the DB constraint sealed the double-active race");
        } catch (ErrorMessageException expected) {
            assertTrue(expected.getMessage().contains("already exists"));
        }

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(overrideMapper);
        inOrder.verify(overrideMapper).expireStaleActive(eq(RELEASE_ID), eq(601L), eq("PRECHECK"), any(java.util.Date.class));
        inOrder.verify(overrideMapper).insert(any(DmGovSchemaGateOverrideDO.class));
    }

    @Test
    public void grant_releaseLevel_nullStmtId_ok() {
        // Mimic MyBatis-Plus IdType.AUTO backfill of the generated key
        when(overrideMapper.insert(any(DmGovSchemaGateOverrideDO.class))).thenAnswer(inv -> {
            inv.getArgument(0, DmGovSchemaGateOverrideDO.class).setId(2L);
            return 1;
        });

        service.grant(PUID, UID, buildGrantFO(null, "DIFF", "release-wide ruling",
            System.currentTimeMillis() + 3600_000L));

        verify(overrideMapper).insert(argThat((DmGovSchemaGateOverrideDO row) ->
            row != null && row.getStmtId() == null && "DIFF".equals(row.getGateType())));
        // No stmt lookup for release-level grants
        verify(stmtMapper, never()).queryById(anyLong());
    }

    @Test
    public void grant_blankReason_throws() {
        try {
            service.grant(PUID, UID, buildGrantFO(null, "PRECHECK", "   ", System.currentTimeMillis() + 3600_000L));
            fail("Expected ErrorMessageException — reason is mandatory (no rubber-stamp grants)");
        } catch (ErrorMessageException ex) {
            assertTrue(ex.getMessage().contains("reason"));
        }
        verify(overrideMapper, never()).insert(any(DmGovSchemaGateOverrideDO.class));
    }

    @Test
    public void grant_invalidGateType_throws() {
        try {
            service.grant(PUID, UID, buildGrantFO(null, "HASH_DRIFT", "reason", System.currentTimeMillis() + 3600_000L));
            fail("Expected ErrorMessageException — hash drift is NOT overridable, only PRECHECK/DIFF");
        } catch (ErrorMessageException ex) {
            assertTrue(ex.getMessage().contains("gateType"));
        }
    }

    @Test
    public void grant_expireAtPast_throws() {
        try {
            service.grant(PUID, UID, buildGrantFO(null, "PRECHECK", "reason", System.currentTimeMillis() - 1000L));
            fail("Expected ErrorMessageException");
        } catch (ErrorMessageException ex) {
            assertTrue(ex.getMessage().contains("future"));
        }
    }

    @Test
    public void grant_expireAtBeyond7Days_throws() {
        long eightDays = System.currentTimeMillis() + 8L * 24 * 60 * 60 * 1000;
        try {
            service.grant(PUID, UID, buildGrantFO(null, "PRECHECK", "reason", eightDays));
            fail("Expected ErrorMessageException — no permanent exemptions (≤ 7 days)");
        } catch (ErrorMessageException ex) {
            assertTrue(ex.getMessage().contains("7 days"));
        }
    }

    @Test
    public void grant_wrongTenant_throws() {
        when(releaseMapper.queryById(RELEASE_ID)).thenAnswer(inv -> {
            DmProdReleaseDO other = new DmProdReleaseDO();
            other.setId(RELEASE_ID);
            other.setPrimaryUid("other-tenant");
            return other;
        });
        try {
            service.grant(PUID, UID, buildGrantFO(null, "PRECHECK", "reason", System.currentTimeMillis() + 3600_000L));
            fail("Expected ErrorMessageException — cross-tenant reads as not-found");
        } catch (ErrorMessageException ex) {
            assertTrue(ex.getMessage().contains("not found"));
        }
    }

    @Test
    public void grant_stmtNotInRelease_throws() {
        DmProdReleaseStmtDO foreign = new DmProdReleaseStmtDO();
        foreign.setId(601L);
        foreign.setReleaseId(999L); // another release
        when(stmtMapper.queryById(601L)).thenReturn(foreign);
        try {
            service.grant(PUID, UID, buildGrantFO(601L, "PRECHECK", "reason", System.currentTimeMillis() + 3600_000L));
            fail("Expected ErrorMessageException");
        } catch (ErrorMessageException ex) {
            assertTrue(ex.getMessage().contains("601"));
        }
    }

    // ==================== list status derivation ====================

    @Test
    public void list_derivesAllFourStatuses() {
        long now = System.currentTimeMillis();
        when(overrideMapper.queryByReleaseId(RELEASE_ID)).thenReturn(List.of(
            buildRow(1L, null, 0, 0, new Date(now + 3600_000L)),  // ACTIVE
            buildRow(2L, 600L, 1, 0, new Date(now + 3600_000L)), // CONSUMED
            buildRow(3L, null, 0, 0, new Date(now - 1000L)),     // EXPIRED
            buildRow(4L, null, 0, 1, new Date(now + 3600_000L))  // REVOKED
        ));

        List<SchemaGateOverrideVO> vos = service.list(PUID, RELEASE_ID);

        assertEquals(4, vos.size());
        assertEquals("ACTIVE", vos.get(0).getStatus());
        assertEquals("CONSUMED", vos.get(1).getStatus());
        assertEquals("EXPIRED", vos.get(2).getStatus());
        assertEquals("REVOKED", vos.get(3).getStatus());
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

    // ==================== revoke ====================

    @Test
    public void revoke_unconsumed_casSucceeds() {
        when(overrideMapper.selectById(1L)).thenReturn(buildRow(1L, null, 0, 0, new Date(System.currentTimeMillis() + 3600_000L)));
        when(overrideMapper.revokeCas(eq(1L), any(Date.class), eq(UID))).thenReturn(1);

        service.revoke(PUID, UID, 1L);

        verify(overrideMapper).revokeCas(eq(1L), any(Date.class), eq(UID));
    }

    @Test
    public void revoke_alreadyConsumedOrRevoked_throws() {
        when(overrideMapper.selectById(2L)).thenReturn(buildRow(2L, null, 1, 0, new Date(System.currentTimeMillis() + 3600_000L)));
        when(overrideMapper.revokeCas(eq(2L), any(Date.class), eq(UID))).thenReturn(0); // CAS lost

        try {
            service.revoke(PUID, UID, 2L);
            fail("Expected ErrorMessageException — consumed grants cannot be revoked");
        } catch (ErrorMessageException ex) {
            assertTrue(ex.getMessage().contains("consumed or revoked"));
        }
    }

    @Test
    public void revoke_notFound_throws() {
        when(overrideMapper.selectById(3L)).thenReturn(null);
        try {
            service.revoke(PUID, UID, 3L);
            fail("Expected ErrorMessageException");
        } catch (ErrorMessageException ex) {
            assertTrue(ex.getMessage().contains("not found"));
        }
    }

    // ==================== findAndConsume: single-use CAS contract ====================

    @Test
    public void findAndConsume_stmtLevelPreferred_releaseLevelNeverQueried() {
        DmGovSchemaGateOverrideDO stmtLevel = buildRow(10L, 600L, 0, 0, new Date(System.currentTimeMillis() + 3600_000L));
        when(overrideMapper.findConsumableStmtLevel(eq(RELEASE_ID), eq(600L), eq("PRECHECK"), any(Date.class)))
            .thenReturn(stmtLevel);
        when(overrideMapper.consumeCas(eq(10L), any(Date.class), anyString())).thenReturn(1);

        DmGovSchemaGateOverrideDO result = service.findAndConsume(RELEASE_ID, 600L, "PRECHECK", "gate=PRECHECK stmt=600");

        assertSame(stmtLevel, result);
        assertEquals(1, result.getConsumed());
        assertNotNull(result.getConsumedAt());
        // Precise grant wins — the release-level blanket is NOT touched (not burned)
        verify(overrideMapper, never()).findConsumableReleaseLevel(anyLong(), anyString(), any(Date.class));
    }

    @Test
    public void findAndConsume_noStmtLevel_fallsBackToReleaseLevel() {
        DmGovSchemaGateOverrideDO releaseLevel = buildRow(11L, null, 0, 0, new Date(System.currentTimeMillis() + 3600_000L));
        when(overrideMapper.findConsumableStmtLevel(eq(RELEASE_ID), eq(600L), eq("PRECHECK"), any(Date.class)))
            .thenReturn(null);
        when(overrideMapper.findConsumableReleaseLevel(eq(RELEASE_ID), eq("PRECHECK"), any(Date.class)))
            .thenReturn(releaseLevel);
        when(overrideMapper.consumeCas(eq(11L), any(Date.class), anyString())).thenReturn(1);

        DmGovSchemaGateOverrideDO result = service.findAndConsume(RELEASE_ID, 600L, "PRECHECK", "detail");

        assertSame(releaseLevel, result);
    }

    @Test
    public void findAndConsume_casLostOnStmtLevel_triesReleaseLevelThenDenies() {
        // Second gate hits an already-consumed stmt-level row (CAS returns 0) and no
        // release-level grant exists → null → the gate RE-DENIES (single-use strictness:
        // "an unconsumed ruling re-freezes", and a consumed one never releases twice)
        DmGovSchemaGateOverrideDO stale = buildRow(12L, 600L, 0, 0, new Date(System.currentTimeMillis() + 3600_000L));
        when(overrideMapper.findConsumableStmtLevel(eq(RELEASE_ID), eq(600L), eq("PRECHECK"), any(Date.class)))
            .thenReturn(stale);
        when(overrideMapper.consumeCas(eq(12L), any(Date.class), anyString())).thenReturn(0);
        when(overrideMapper.findConsumableReleaseLevel(eq(RELEASE_ID), eq("PRECHECK"), any(Date.class)))
            .thenReturn(null);

        assertNull(service.findAndConsume(RELEASE_ID, 600L, "PRECHECK", "detail"));
    }

    @Test
    public void findAndConsume_noneConsumable_returnsNull() {
        // Expired/consumed/revoked rows never reach the service (SQL filter) — empty candidates
        when(overrideMapper.findConsumableStmtLevel(anyLong(), anyLong(), anyString(), any(Date.class)))
            .thenReturn(null);
        when(overrideMapper.findConsumableReleaseLevel(anyLong(), anyString(), any(Date.class)))
            .thenReturn(null);

        assertNull(service.findAndConsume(RELEASE_ID, 600L, "PRECHECK", "detail"));
        verify(overrideMapper, never()).consumeCas(anyLong(), any(Date.class), any());
    }

    @Test
    public void findAndConsume_expiryEvaluatedAgainstNowParam() {
        // The expiry predicate lives in SQL (expire_at > #{now}); pin that the service passes a
        // fresh now so an expired grant can never be selected (DmAuthResDO.isEffective pattern)
        long before = System.currentTimeMillis();
        when(overrideMapper.findConsumableStmtLevel(anyLong(), anyLong(), anyString(), any(Date.class)))
            .thenReturn(null);
        when(overrideMapper.findConsumableReleaseLevel(anyLong(), anyString(), any(Date.class)))
            .thenReturn(null);

        service.findAndConsume(RELEASE_ID, 600L, "DIFF", "detail");

        ArgumentCaptor<Date> nowCaptor = ArgumentCaptor.forClass(Date.class);
        verify(overrideMapper).findConsumableStmtLevel(eq(RELEASE_ID), eq(600L), eq("DIFF"), nowCaptor.capture());
        assertTrue(nowCaptor.getValue().getTime() >= before);
        assertTrue(nowCaptor.getValue().getTime() <= System.currentTimeMillis());
    }

    @Test
    public void findAndConsume_detailTruncatedTo512() {
        DmGovSchemaGateOverrideDO releaseLevel = buildRow(13L, null, 0, 0, new Date(System.currentTimeMillis() + 3600_000L));
        when(overrideMapper.findConsumableStmtLevel(anyLong(), anyLong(), anyString(), any(Date.class)))
            .thenReturn(null);
        when(overrideMapper.findConsumableReleaseLevel(anyLong(), anyString(), any(Date.class)))
            .thenReturn(releaseLevel);
        when(overrideMapper.consumeCas(anyLong(), any(Date.class), anyString())).thenReturn(1);

        String longDetail = "x".repeat(1200);
        service.findAndConsume(RELEASE_ID, 600L, "PRECHECK", longDetail);

        ArgumentCaptor<String> detailCaptor = ArgumentCaptor.forClass(String.class);
        verify(overrideMapper).consumeCas(eq(13L), any(Date.class), detailCaptor.capture());
        assertEquals(512, detailCaptor.getValue().length());
    }
}
