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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.console.web.component.approval.ApprovalFlowService;
import com.clougence.clouddm.console.web.component.approval.ApprovalStateService;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.component.auth.DmAuthServiceForBiz;
import com.clougence.clouddm.console.web.component.execute.AutoExecService;
import com.clougence.clouddm.console.web.component.governance.GovReturnNotifyService;
import com.clougence.clouddm.console.web.component.governance.PrecheckConclusion;
import com.clougence.clouddm.console.web.component.governance.ProdReleaseStateMachine;
import com.clougence.clouddm.console.web.component.governance.ReleaseSchemaDiffService;
import com.clougence.clouddm.console.web.component.governance.ReleaseSchemaPrecheckService;
import com.clougence.clouddm.console.web.model.fo.prodrelease.ProdReleaseResubmitFO;
import com.clougence.clouddm.console.web.util.DmTeamUtils;
import com.clougence.clouddm.console.web.model.vo.ticket.DmTicketResultVO;
import com.clougence.clouddm.console.web.service.approval.ApprovalControlService;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.DbChangeEventDal;
import com.clougence.clouddm.platform.dal.access.DbPairDal;
import com.clougence.clouddm.platform.dal.access.DataSourceDal;
import com.clougence.clouddm.platform.dal.access.ExecutionDal;
import com.clougence.clouddm.platform.dal.access.ProdReleaseDal;
import com.clougence.clouddm.platform.dal.access.TicketDbStmtDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangeEventMapper;
import com.clougence.clouddm.platform.dal.mapper.datasource.DmDsMapper;
import com.clougence.clouddm.platform.dal.mapper.dbpair.DmDbPairMapper;
import com.clougence.clouddm.platform.dal.mapper.govticket.DmTicketDbStmtMapper;
import com.clougence.clouddm.platform.dal.mapper.govticket.DmTicketStmtSchemaMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseStmtMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseStmtSchemaMapper;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.approval.SqlContentType;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO;
import com.clougence.clouddm.platform.dal.model.dbchange.GovEventType;
import com.clougence.clouddm.platform.dal.model.dbpair.DmDbPairDO;
import com.clougence.clouddm.platform.dal.model.datasource.DmDsDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseStmtDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.ProdReleaseStatus;
import com.clougence.utils.JsonUtils;

/**
 * Tests for ProdReleaseServiceImpl.resubmitRelease (R3: RETURNED release resubmit).
 * Contract (2026-09-14 revision): resubmit FO carries {@code ticketIds} — the final
 * full ticket list. The applicant may add new eligible tickets or remove unwanted ones
 * from the original merge list. Candidate validity is enforced authoritatively by
 * collectSourceEntries (same as create).
 */
public class ProdReleaseResubmitTest {

    private ProdReleaseServiceImpl  service;
    private ProdReleaseDal           prodReleaseDal;
    private DmProdReleaseMapper      releaseMapper;
    private DmProdReleaseStmtMapper  stmtMapper;
    private DmProdReleaseStmtSchemaMapper schemaMapper;
    private TicketDbStmtDal          ticketDbStmtDal;
    private DmTicketDbStmtMapper     ticketStmtMapper;
    private DmTicketStmtSchemaMapper ticketSchemaMapper;
    private ApprovalDal              approvalDal;
    private DmApprovalMapper         approvalMapper;
    private DbPairDal               dbPairDal;
    private DmDbPairMapper           pairMapper;
    private DataSourceDal            dsDal;
    private DmDsMapper               dsMapper;
    private ApprovalControlService   approvalControlService;
    private ProdReleaseStateMachine  releaseStateMachine;
    private DbChangeEventDal         dbChangeEventDal;
    private DmDbChangeEventMapper     eventMapper;
    private ApprovalStateService     approvalStateService;
    private ApprovalFlowService      approvalFlowService;
    private GovReturnNotifyService   govReturnNotifyService;

    private static final String PUID = "puid-001";
    private static final String UID  = "uid-001";

    @Before
    public void setUp() {
        service = new ProdReleaseServiceImpl();

        ExecutionDal staticExecDal = mock(ExecutionDal.class);
        com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoJobMapper staticJobMapper
            = mock(com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoJobMapper.class);
        when(staticExecDal.autoJobMapper()).thenReturn(staticJobMapper);
        ReflectionTestUtils.setField(DmTeamUtils.class, "executionDal", staticExecDal);

        prodReleaseDal = mock(ProdReleaseDal.class);
        releaseMapper = mock(DmProdReleaseMapper.class);
        stmtMapper = mock(DmProdReleaseStmtMapper.class);
        schemaMapper = mock(DmProdReleaseStmtSchemaMapper.class);
        when(prodReleaseDal.releaseMapper()).thenReturn(releaseMapper);
        when(prodReleaseDal.stmtMapper()).thenReturn(stmtMapper);
        when(prodReleaseDal.schemaMapper()).thenReturn(schemaMapper);

        ticketDbStmtDal = mock(TicketDbStmtDal.class);
        ticketStmtMapper = mock(DmTicketDbStmtMapper.class);
        ticketSchemaMapper = mock(DmTicketStmtSchemaMapper.class);
        when(ticketDbStmtDal.stmtMapper()).thenReturn(ticketStmtMapper);
        when(ticketDbStmtDal.schemaMapper()).thenReturn(ticketSchemaMapper);

        approvalDal = mock(ApprovalDal.class);
        approvalMapper = mock(DmApprovalMapper.class);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);

        dbPairDal = mock(DbPairDal.class);
        pairMapper = mock(DmDbPairMapper.class);
        when(dbPairDal.pairMapper()).thenReturn(pairMapper);

        dsDal = mock(DataSourceDal.class);
        dsMapper = mock(DmDsMapper.class);
        when(dsDal.dsMapper()).thenReturn(dsMapper);

        approvalControlService = mock(ApprovalControlService.class);
        releaseStateMachine = mock(ProdReleaseStateMachine.class);
        dbChangeEventDal = mock(DbChangeEventDal.class);
        eventMapper = mock(DmDbChangeEventMapper.class);
        when(dbChangeEventDal.eventMapper()).thenReturn(eventMapper);
        when(eventMapper.insert(any(DmDbChangeEventDO.class))).thenReturn(1);

        approvalStateService = mock(ApprovalStateService.class);
        approvalFlowService = mock(ApprovalFlowService.class);
        govReturnNotifyService = mock(GovReturnNotifyService.class);

        ReleaseSchemaPrecheckService precheckService = mock(ReleaseSchemaPrecheckService.class);
        when(precheckService.precheck(any())).thenReturn(PrecheckConclusion.pass());
        when(precheckService.extractTables(anyString())).thenReturn(java.util.Collections.emptySet());
        ReleaseSchemaDiffService diffService = mock(ReleaseSchemaDiffService.class);
        when(diffService.diff(any())).thenReturn(PrecheckConclusion.pass());

        ExecutionDal execDal = mock(ExecutionDal.class);
        DmAuthServiceForBiz dmAuthServiceForBiz = mock(DmAuthServiceForBiz.class);

        ReflectionTestUtils.setField(service, "prodReleaseDal", prodReleaseDal);
        ReflectionTestUtils.setField(service, "dbChangeEventDal", dbChangeEventDal);
        ReflectionTestUtils.setField(service, "ticketDbStmtDal", ticketDbStmtDal);
        ReflectionTestUtils.setField(service, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(service, "approvalControlService", approvalControlService);
        ReflectionTestUtils.setField(service, "approvalStateService", approvalStateService);
        ReflectionTestUtils.setField(service, "approvalFlowService", approvalFlowService);
        ReflectionTestUtils.setField(service, "releaseStateMachine", releaseStateMachine);
        ReflectionTestUtils.setField(service, "dbPairDal", dbPairDal);
        ReflectionTestUtils.setField(service, "dsDal", dsDal);
        ReflectionTestUtils.setField(service, "dmAuthServiceForBiz", dmAuthServiceForBiz);
        ReflectionTestUtils.setField(service, "releaseSchemaPrecheckService", precheckService);
        ReflectionTestUtils.setField(service, "releaseSchemaDiffService", diffService);
        ReflectionTestUtils.setField(service, "execDal", execDal);
        ReflectionTestUtils.setField(service, "govReturnNotifyService", govReturnNotifyService);
    }

    @Test
    public void resubmit_nonReturned_throws() {
        long releaseId = 400L;
        DmProdReleaseDO release = buildReturnedRelease(releaseId);
        release.setStatus(ProdReleaseStatus.APPROVING.name());
        when(releaseMapper.selectByIdForUpdate(releaseId)).thenReturn(release);

        ProdReleaseResubmitFO fo = new ProdReleaseResubmitFO();
        fo.setReleaseId(releaseId);
        fo.setTicketIds(List.of(100L));

        try {
            service.resubmitRelease(PUID, UID, true, fo);
            fail("Expected ErrorMessageException");
        } catch (ErrorMessageException ex) {
            // ok
        }
        verify(stmtMapper, never()).insert(any(DmProdReleaseStmtDO.class));
    }

    @Test
    public void resubmit_emptyTicketIds_throws() {
        long releaseId = 401L;
        DmProdReleaseDO release = buildReturnedRelease(releaseId);
        when(releaseMapper.selectByIdForUpdate(releaseId)).thenReturn(release);

        ProdReleaseResubmitFO fo = new ProdReleaseResubmitFO();
        fo.setReleaseId(releaseId);
        fo.setTicketIds(java.util.Collections.emptyList());

        try {
            service.resubmitRelease(PUID, UID, true, fo);
            fail("Expected ErrorMessageException");
        } catch (ErrorMessageException ex) {
            // ok — NO_TICKETS
        }
        verify(stmtMapper, never()).insert(any(DmProdReleaseStmtDO.class));
    }

    @Test
    public void resubmit_duplicateTicketIds_deduped_success() {
        long releaseId = 402L;
        DmProdReleaseDO release = buildReturnedRelease(releaseId);
        when(releaseMapper.selectByIdForUpdate(releaseId)).thenReturn(release);

        ProdReleaseResubmitFO fo = new ProdReleaseResubmitFO();
        fo.setReleaseId(releaseId);
        // Duplicate ticketIds should be deduped (LinkedHashSet) — 3x100 → 1 unique.
        fo.setTicketIds(List.of(100L, 100L, 100L));

        // Mock ticket 100 as valid
        DmApprovalDO ticket = buildSourceTicket(100L, ApprovalStatus.FINISHED);
        when(approvalMapper.queryById(100L)).thenReturn(ticket);
        DmTicketDbStmtDO stmt = buildSourceStmt(200L, 100L, "SUCCESS");
        when(ticketStmtMapper.queryByTicketId(100L)).thenReturn(List.of(stmt));
        when(stmtMapper.queryBySourceStmtId(200L)).thenReturn(null);
        DmDbPairDO pair = buildPair(300L, 10L, "prod_db");
        when(pairMapper.selectById(300L)).thenReturn(pair);
        when(releaseMapper.countActiveByProdDb(10L, "prod_db")).thenReturn(0);
        when(ticketSchemaMapper.queryByStmtId(200L)).thenReturn(java.util.Collections.emptyList());
        when(stmtMapper.insert(any(DmProdReleaseStmtDO.class))).thenAnswer(invocation -> {
            DmProdReleaseStmtDO arg = invocation.getArgument(0);
            arg.setId(800L);
            return 1;
        });
        DmDsDO prodDs = new DmDsDO();
        prodDs.setDsEnvId(1L);
        when(dsMapper.queryDsIdentityById(10L)).thenReturn(prodDs);
        DmTicketResultVO ticketResult = new DmTicketResultVO();
        ticketResult.setTicketId(700L);
        when(approvalControlService.createSqlTicket(any(), any(), any(), eq(ApprovalBiz.DM_PROD_RELEASE)))
            .thenReturn(ticketResult);
        DmApprovalDO newApproval = new DmApprovalDO();
        newApproval.setId(700L);
        newApproval.setTicketInfo(JsonUtils.toJson(new ApprovalMO()));
        when(approvalMapper.queryById(700L)).thenReturn(newApproval);
        when(releaseStateMachine.transit(eq(releaseId),
            eq(Set.of(ProdReleaseStatus.RETURNED)), eq(ProdReleaseStatus.APPROVING))).thenReturn(true);

        service.resubmitRelease(PUID, UID, true, fo);

        // Only one stmt insert (deduped 3x100 → 1 ticket → 1 stmt)
        verify(stmtMapper, times(1)).insert(any(DmProdReleaseStmtDO.class));
    }

    @Test
    public void resubmit_usurpedTicket_throws_zeroObjects() {
        long releaseId = 403L;
        DmProdReleaseDO release = buildReturnedRelease(releaseId);
        when(releaseMapper.selectByIdForUpdate(releaseId)).thenReturn(release);

        // Source ticket is FINISHED, but stmt is already merged (usurped)
        DmApprovalDO ticket = buildSourceTicket(100L, ApprovalStatus.FINISHED);
        when(approvalMapper.queryById(100L)).thenReturn(ticket);

        DmTicketDbStmtDO stmt = buildSourceStmt(200L, 100L, "SUCCESS");
        when(ticketStmtMapper.queryByTicketId(100L)).thenReturn(List.of(stmt));

        // stmt already merged by another release
        DmProdReleaseStmtDO existing = new DmProdReleaseStmtDO();
        existing.setReleaseId(999L);
        when(stmtMapper.queryBySourceStmtId(200L)).thenReturn(existing);

        ProdReleaseResubmitFO fo = new ProdReleaseResubmitFO();
        fo.setReleaseId(releaseId);
        fo.setTicketIds(List.of(100L));

        try {
            service.resubmitRelease(PUID, UID, true, fo);
            fail("Expected ErrorMessageException");
        } catch (ErrorMessageException ex) {
            // ok — usurped ticket raises error
        }
        // Verify zero stmt inserts (no objects created)
        verify(stmtMapper, never()).insert(any(DmProdReleaseStmtDO.class));
        verify(releaseStateMachine, never()).transit(anyLong(), anySet(), any());
    }

    @Test
    public void resubmit_crossTenantTicket_throws_zeroObjects() {
        long releaseId = 404L;
        DmProdReleaseDO release = buildReturnedRelease(releaseId);
        when(releaseMapper.selectByIdForUpdate(releaseId)).thenReturn(release);

        // Source ticket belongs to a different tenant
        DmApprovalDO ticket = buildSourceTicket(100L, ApprovalStatus.FINISHED);
        ticket.setPrimaryUid("other-tenant");
        when(approvalMapper.queryById(100L)).thenReturn(ticket);

        ProdReleaseResubmitFO fo = new ProdReleaseResubmitFO();
        fo.setReleaseId(releaseId);
        fo.setTicketIds(List.of(100L));

        try {
            service.resubmitRelease(PUID, UID, true, fo);
            fail("Expected ErrorMessageException");
        } catch (ErrorMessageException ex) {
            // ok — cross-tenant ticket raises TENANT_MISMATCH
        }
        verify(stmtMapper, never()).insert(any(DmProdReleaseStmtDO.class));
        verify(releaseStateMachine, never()).transit(anyLong(), anySet(), any());
    }

    @Test
    public void resubmit_pureRemoval_success_rebuildsSubset() {
        long releaseId = 405L;
        DmProdReleaseDO release = buildReturnedReleaseWithTwoTickets(releaseId);
        when(releaseMapper.selectByIdForUpdate(releaseId)).thenReturn(release);

        // Only keep ticket 100 (remove 101)
        mockTicket(100L, 200L, "prod_db", 10L, 300L);
        ProdReleaseResubmitFO fo = new ProdReleaseResubmitFO();
        fo.setReleaseId(releaseId);
        fo.setTicketIds(List.of(100L));

        service.resubmitRelease(PUID, UID, true, fo);

        // Verify only 1 stmt insert (ticket 100 only)
        verify(stmtMapper, times(1)).insert(any(DmProdReleaseStmtDO.class));
        // Verify gate_result rewritten with sourceTicketCount=1
        verify(releaseMapper).updateGateResult(eq(releaseId), argThat((String json) -> {
            try {
                Map<?, ?> gr = JsonUtils.toObj(json, Map.class);
                return Integer.valueOf(1).equals(gr.get("sourceTicketCount"));
            } catch (Exception e) {
                return false;
            }
        }));
        // Verify transit RETURNED→APPROVING
        verify(releaseStateMachine).transit(eq(releaseId),
            eq(Set.of(ProdReleaseStatus.RETURNED)), eq(ProdReleaseStatus.APPROVING));
    }

    @Test
    public void resubmit_addNewTicket_success_mergedIntoRebuild() {
        long releaseId = 406L;
        DmProdReleaseDO release = buildReturnedRelease(releaseId); // original has ticket 100
        when(releaseMapper.selectByIdForUpdate(releaseId)).thenReturn(release);

        // Original ticket 100 + new ticket 101
        mockTicket(100L, 200L, "prod_db", 10L, 300L);
        mockTicket(101L, 201L, "prod_db", 10L, 301L);

        ProdReleaseResubmitFO fo = new ProdReleaseResubmitFO();
        fo.setReleaseId(releaseId);
        fo.setTicketIds(List.of(100L, 101L));

        service.resubmitRelease(PUID, UID, true, fo);

        // Verify 2 stmt inserts (both tickets)
        verify(stmtMapper, times(2)).insert(any(DmProdReleaseStmtDO.class));
        // Verify gate_result rewritten with sourceTicketCount=2
        verify(releaseMapper).updateGateResult(eq(releaseId), argThat((String json) -> {
            try {
                Map<?, ?> gr = JsonUtils.toObj(json, Map.class);
                return Integer.valueOf(2).equals(gr.get("sourceTicketCount"));
            } catch (Exception e) {
                return false;
            }
        }));
        // Verify event contains addedTicketIds=[101] and removedTicketIds=[] (value assertion, not just existence)
        verify(eventMapper).insert(argThat((DmDbChangeEventDO e) -> {
            if (!GovEventType.RELEASE_RESUBMITTED.name().equals(e.getEventType())) return false;
            if (!UID.equals(e.getOperatorUid())) return false;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> ed = JsonUtils.toObj(e.getEventData(), Map.class);
                if (ed == null) return false;
                List<?> added = (List<?>) ed.get("addedTicketIds");
                List<?> removed = (List<?>) ed.get("removedTicketIds");
                // Use String comparison to avoid Integer/Long type mismatch from JSON deserialization
                return added != null && String.valueOf(added).equals("[101]")
                    && removed != null && String.valueOf(removed).equals("[]");
            } catch (Exception ex) {
                return false;
            }
        }));
    }

    @Test
    public void resubmit_simultaneousAddRemove_success_eventDiff() {
        long releaseId = 407L;
        DmProdReleaseDO release = buildReturnedReleaseWithTwoTickets(releaseId); // original: 100, 101
        when(releaseMapper.selectByIdForUpdate(releaseId)).thenReturn(release);

        // Remove 101, add 102 → final list: 100, 102
        mockTicket(100L, 200L, "prod_db", 10L, 300L);
        mockTicket(102L, 202L, "prod_db", 10L, 302L);

        ProdReleaseResubmitFO fo = new ProdReleaseResubmitFO();
        fo.setReleaseId(releaseId);
        fo.setTicketIds(List.of(100L, 102L));

        service.resubmitRelease(PUID, UID, true, fo);

        // Verify 2 stmt inserts
        verify(stmtMapper, times(2)).insert(any(DmProdReleaseStmtDO.class));
        // Verify gate_result sourceTicketCount=2
        verify(releaseMapper).updateGateResult(eq(releaseId), argThat((String json) -> {
            try {
                Map<?, ?> gr = JsonUtils.toObj(json, Map.class);
                return Integer.valueOf(2).equals(gr.get("sourceTicketCount"));
            } catch (Exception e) {
                return false;
            }
        }));
        // Verify event eventData has addedTicketIds=[102] and removedTicketIds=[101] (exact value assertion)
        verify(eventMapper).insert(argThat((DmDbChangeEventDO e) -> {
            if (!GovEventType.RELEASE_RESUBMITTED.name().equals(e.getEventType())) return false;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> ed = JsonUtils.toObj(e.getEventData(), Map.class);
                if (ed == null) return false;
                List<?> added = (List<?>) ed.get("addedTicketIds");
                List<?> removed = (List<?>) ed.get("removedTicketIds");
                // Use String comparison to avoid Integer/Long type mismatch from JSON deserialization
                return added != null && String.valueOf(added).equals("[102]")
                    && removed != null && String.valueOf(removed).equals("[101]");
            } catch (Exception ex) {
                return false;
            }
        }));
        // Verify new approvalId backfilled
        verify(releaseMapper).updateApprovalId(releaseId, 700L);
        // Verify transit RETURNED→APPROVING
        verify(releaseStateMachine).transit(eq(releaseId),
            eq(Set.of(ProdReleaseStatus.RETURNED)), eq(ProdReleaseStatus.APPROVING));
    }

    @Test
    public void resubmit_finalListContainsInvalidTicket_throws_zeroObjects() {
        long releaseId = 408L;
        DmProdReleaseDO release = buildReturnedRelease(releaseId); // original: 100
        when(releaseMapper.selectByIdForUpdate(releaseId)).thenReturn(release);

        // Ticket 100 is valid, but ticket 101 is not FINISHED
        mockTicket(100L, 200L, "prod_db", 10L, 300L);
        DmApprovalDO badTicket = buildSourceTicket(101L, ApprovalStatus.WAIT_APPROVAL);
        when(approvalMapper.queryById(101L)).thenReturn(badTicket);

        ProdReleaseResubmitFO fo = new ProdReleaseResubmitFO();
        fo.setReleaseId(releaseId);
        fo.setTicketIds(List.of(100L, 101L));

        try {
            service.resubmitRelease(PUID, UID, true, fo);
            fail("Expected ErrorMessageException");
        } catch (ErrorMessageException ex) {
            // ok — invalid ticket raises error carrying ticketId
        }
        // Verify zero stmt inserts and zero transit
        verify(stmtMapper, never()).insert(any(DmProdReleaseStmtDO.class));
        verify(releaseStateMachine, never()).transit(anyLong(), anySet(), any());
    }

    // ==================== helpers ====================

    /**
     * Build a RETURNED release whose gate_result contains one source ticket (100).
     */
    private DmProdReleaseDO buildReturnedRelease(long id) {
        DmProdReleaseDO release = new DmProdReleaseDO();
        release.setId(id);
        release.setReleaseNo("REL-" + id);
        release.setStatus(ProdReleaseStatus.RETURNED.name());
        release.setPrimaryUid(PUID);
        release.setCreatorUid(UID);
        release.setApprovalId(null);

        Map<String, Object> gateResult = new LinkedHashMap<>();
        gateResult.put("sourceTicketCount", 1);
        List<Map<String, Object>> stmts = new java.util.ArrayList<>();
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("sourceTicketId", 100L);
        s.put("sourceStmtId", 200L);
        s.put("prodDbName", "prod_db");
        s.put("seq", 1);
        s.put("hash", "somehash");
        stmts.add(s);
        gateResult.put("stmts", stmts);
        release.setGateResult(JsonUtils.toJson(gateResult));
        return release;
    }

    /**
     * Build a RETURNED release whose gate_result contains two source tickets (100, 101).
     */
    private DmProdReleaseDO buildReturnedReleaseWithTwoTickets(long id) {
        DmProdReleaseDO release = new DmProdReleaseDO();
        release.setId(id);
        release.setReleaseNo("REL-" + id);
        release.setStatus(ProdReleaseStatus.RETURNED.name());
        release.setPrimaryUid(PUID);
        release.setCreatorUid(UID);
        release.setApprovalId(null);

        Map<String, Object> gateResult = new LinkedHashMap<>();
        gateResult.put("sourceTicketCount", 2);
        List<Map<String, Object>> stmts = new java.util.ArrayList<>();
        Map<String, Object> s1 = new LinkedHashMap<>();
        s1.put("sourceTicketId", 100L);
        s1.put("sourceStmtId", 200L);
        s1.put("prodDbName", "prod_db");
        s1.put("seq", 1);
        s1.put("hash", "hash100");
        stmts.add(s1);
        Map<String, Object> s2 = new LinkedHashMap<>();
        s2.put("sourceTicketId", 101L);
        s2.put("sourceStmtId", 201L);
        s2.put("prodDbName", "prod_db");
        s2.put("seq", 2);
        s2.put("hash", "hash101");
        stmts.add(s2);
        gateResult.put("stmts", stmts);
        release.setGateResult(JsonUtils.toJson(gateResult));
        return release;
    }

    private DmApprovalDO buildSourceTicket(long id, ApprovalStatus status) {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(id);
        ticket.setTicketStatus(status);
        ticket.setPrimaryUid(PUID);
        ticket.setOwnerUid(UID);
        ticket.setTicketTitle("Test ticket " + id);
        // gmtCreate is used by the sort step (sourceEntries.sort by ticketGmtCreate);
        // null gmtCreate NPEs when comparing two tickets.
        ticket.setGmtCreate(new java.util.Date(id * 1000L));
        ApprovalMO mo = new ApprovalMO();
        mo.setTicketType("PRE_DDL");
        ticket.setTicketInfo(JsonUtils.toJson(mo));
        return ticket;
    }

    private DmTicketDbStmtDO buildSourceStmt(long id, long ticketId, String execStatus) {
        DmTicketDbStmtDO stmt = new DmTicketDbStmtDO();
        stmt.setId(id);
        stmt.setTicketId(ticketId);
        stmt.setPairId(300L);
        stmt.setDbName("pre_db");
        stmt.setSqlContent("CREATE TABLE t(id int);");
        stmt.setExecStatus(execStatus);
        return stmt;
    }

    private DmDbPairDO buildPair(long pairId, long prodDsId, String prodDbName) {
        DmDbPairDO pair = new DmDbPairDO();
        pair.setId(pairId);
        pair.setProdDsId(prodDsId);
        pair.setProdDbName(prodDbName);
        pair.setStatus("ENABLED");
        return pair;
    }

    /**
     * Mock a fully-valid source ticket: FINISHED + PRE_DDL + SUCCESS stmt + not merged
     * + pair enabled + no active release on prod DB + empty snapshots.
     * Also mocks the auto-increment ID assignment on stmt insert, the prod DS identity,
     * the new approval ticket, and the state machine transit.
     */
    private void mockTicket(long ticketId, long stmtId, String prodDbName, long prodDsId, long pairId) {
        DmApprovalDO ticket = buildSourceTicket(ticketId, ApprovalStatus.FINISHED);
        when(approvalMapper.queryById(ticketId)).thenReturn(ticket);

        DmTicketDbStmtDO stmt = buildSourceStmt(stmtId, ticketId, "SUCCESS");
        stmt.setPairId(pairId);
        when(ticketStmtMapper.queryByTicketId(ticketId)).thenReturn(List.of(stmt));
        when(stmtMapper.queryBySourceStmtId(stmtId)).thenReturn(null);

        DmDbPairDO pair = buildPair(pairId, prodDsId, prodDbName);
        when(pairMapper.selectById(pairId)).thenReturn(pair);
        when(releaseMapper.countActiveByProdDb(prodDsId, prodDbName)).thenReturn(0);
        when(ticketSchemaMapper.queryByStmtId(stmtId)).thenReturn(java.util.Collections.emptyList());

        when(stmtMapper.insert(any(DmProdReleaseStmtDO.class))).thenAnswer(invocation -> {
            DmProdReleaseStmtDO arg = invocation.getArgument(0);
            arg.setId(800L);
            return 1;
        });

        DmDsDO prodDs = new DmDsDO();
        prodDs.setDsEnvId(1L);
        when(dsMapper.queryDsIdentityById(prodDsId)).thenReturn(prodDs);

        DmTicketResultVO ticketResult = new DmTicketResultVO();
        ticketResult.setTicketId(700L);
        when(approvalControlService.createSqlTicket(any(), any(), any(), eq(ApprovalBiz.DM_PROD_RELEASE)))
            .thenReturn(ticketResult);

        DmApprovalDO newApproval = new DmApprovalDO();
        newApproval.setId(700L);
        newApproval.setTicketInfo(JsonUtils.toJson(new ApprovalMO()));
        when(approvalMapper.queryById(700L)).thenReturn(newApproval);

        when(releaseStateMachine.transit(anyLong(),
            eq(Set.of(ProdReleaseStatus.RETURNED)), eq(ProdReleaseStatus.APPROVING))).thenReturn(true);
    }
}
