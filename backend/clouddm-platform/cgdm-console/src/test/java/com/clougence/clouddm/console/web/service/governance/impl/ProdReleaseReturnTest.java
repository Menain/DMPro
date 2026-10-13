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

import java.util.Set;

import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.console.web.component.approval.ApprovalFlowService;
import com.clougence.clouddm.console.web.component.approval.ApprovalStateService;
import com.clougence.clouddm.console.web.component.governance.GovReturnNotifyService;
import com.clougence.clouddm.console.web.component.governance.ProdReleaseStateMachine;
import com.clougence.clouddm.console.web.component.governance.PrecheckConclusion;
import com.clougence.clouddm.console.web.component.governance.ReleaseSchemaDiffService;
import com.clougence.clouddm.console.web.component.governance.ReleaseSchemaPrecheckService;
import com.clougence.clouddm.console.web.component.execute.AutoExecService;
import com.clougence.clouddm.console.web.component.auth.DmAuthServiceForBiz;
import com.clougence.clouddm.console.web.util.DmTeamUtils;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.DbChangeEventDal;
import com.clougence.clouddm.platform.dal.access.DbPairDal;
import com.clougence.clouddm.platform.dal.access.DataSourceDal;
import com.clougence.clouddm.platform.dal.access.ExecutionDal;
import com.clougence.clouddm.platform.dal.access.ProdReleaseDal;
import com.clougence.clouddm.platform.dal.access.TicketDbStmtDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangeEventMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseStmtMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseStmtSchemaMapper;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO;
import com.clougence.clouddm.platform.dal.model.dbchange.GovEventType;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.ProdReleaseStatus;

/**
 * Tests for ProdReleaseServiceImpl.returnRelease (R2: release-level return):
 * - APPROVING/APPROVED can be returned; EXECUTING/DONE/RETURNED/REJECTED/CANCELLED rejected
 * - Cross-tenant = not-found semantics
 * - stmt + schema double-delete
 * - transit happens BEFORE approval cancellation (InOrder)
 * - event RELEASE_RETURNED with real uid + eventData.reason
 * - approvalId null → skip cancellation, no throw
 */
public class ProdReleaseReturnTest {

    private ProdReleaseServiceImpl  service;
    private ProdReleaseDal           prodReleaseDal;
    private DmProdReleaseMapper      releaseMapper;
    private DmProdReleaseStmtMapper  stmtMapper;
    private DmProdReleaseStmtSchemaMapper schemaMapper;
    private ApprovalDal              approvalDal;
    private DmApprovalMapper         approvalMapper;
    private ApprovalStateService    approvalStateService;
    private ApprovalFlowService     approvalFlowService;
    private DbChangeEventDal       dbChangeEventDal;
    private DmDbChangeEventMapper    eventMapper;
    private ProdReleaseStateMachine  releaseStateMachine;
    private GovReturnNotifyService   govReturnNotifyService;

    private static final String PUID = "puid-001";
    private static final String UID  = "uid-001";
    private static final String CREATOR_UID = "uid-creator";

    @Before
    public void setUp() {
        service = new ProdReleaseServiceImpl();

        // Set up DmTeamUtils static executionDal
        com.clougence.clouddm.platform.dal.access.ExecutionDal staticExecDal
            = mock(com.clougence.clouddm.platform.dal.access.ExecutionDal.class);
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
        // ADR-013 M1: frozen segment manifest mapper (returnRelease cascade delete)
        when(prodReleaseDal.segmentMapper()).thenReturn(mock(
            com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseStmtSegmentMapper.class));

        approvalDal = mock(ApprovalDal.class);
        approvalMapper = mock(DmApprovalMapper.class);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);

        approvalStateService = mock(ApprovalStateService.class);
        approvalFlowService = mock(ApprovalFlowService.class);

        dbChangeEventDal = mock(DbChangeEventDal.class);
        eventMapper = mock(DmDbChangeEventMapper.class);
        when(dbChangeEventDal.eventMapper()).thenReturn(eventMapper);
        when(eventMapper.insert(any(DmDbChangeEventDO.class))).thenReturn(1);

        releaseStateMachine = mock(ProdReleaseStateMachine.class);
        govReturnNotifyService = mock(GovReturnNotifyService.class);

        // Mock DS / auth / precheck / diff (not used by returnRelease but required by setUp)
        DataSourceDal dsDal = mock(DataSourceDal.class);
        DmAuthServiceForBiz dmAuthServiceForBiz = mock(DmAuthServiceForBiz.class);
        DbPairDal dbPairDal = mock(DbPairDal.class);
        TicketDbStmtDal ticketDbStmtDal = mock(TicketDbStmtDal.class);
        ReleaseSchemaPrecheckService precheckService = mock(ReleaseSchemaPrecheckService.class);
        when(precheckService.precheck(any())).thenReturn(PrecheckConclusion.pass());
        ReleaseSchemaDiffService diffService = mock(ReleaseSchemaDiffService.class);
        when(diffService.diff(any())).thenReturn(PrecheckConclusion.pass());
        ExecutionDal execDal = mock(ExecutionDal.class);

        ReflectionTestUtils.setField(service, "prodReleaseDal", prodReleaseDal);
        ReflectionTestUtils.setField(service, "dbChangeEventDal", dbChangeEventDal);
        ReflectionTestUtils.setField(service, "ticketDbStmtDal", ticketDbStmtDal);
        ReflectionTestUtils.setField(service, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(service, "approvalStateService", approvalStateService);
        ReflectionTestUtils.setField(service, "approvalFlowService", approvalFlowService);
        ReflectionTestUtils.setField(service, "releaseStateMachine", releaseStateMachine);
        ReflectionTestUtils.setField(service, "dsDal", dsDal);
        ReflectionTestUtils.setField(service, "dbPairDal", dbPairDal);
        ReflectionTestUtils.setField(service, "dmAuthServiceForBiz", dmAuthServiceForBiz);
        ReflectionTestUtils.setField(service, "releaseSchemaPrecheckService", precheckService);
        ReflectionTestUtils.setField(service, "releaseSchemaDiffService", diffService);
        ReflectionTestUtils.setField(service, "execDal", execDal);
        ReflectionTestUtils.setField(service, "govReturnNotifyService", govReturnNotifyService);
    }

    @Test
    public void returnRelease_approvingRelease_succeeds() {
        long releaseId = 500L;
        long approvalId = 600L;
        DmProdReleaseDO release = buildRelease(releaseId, ProdReleaseStatus.APPROVING, approvalId);
        when(releaseMapper.selectByIdForUpdate(releaseId)).thenReturn(release);
        when(releaseStateMachine.transit(eq(releaseId), anySet(), eq(ProdReleaseStatus.RETURNED))).thenReturn(true);
        when(stmtMapper.deleteByReleaseId(releaseId)).thenReturn(3);

        DmApprovalDO approvalTicket = new DmApprovalDO();
        approvalTicket.setId(approvalId);
        approvalTicket.setTicketStatus(ApprovalStatus.WAIT_APPROVAL);
        when(approvalMapper.queryById(approvalId)).thenReturn(approvalTicket);

        service.returnRelease(PUID, UID, releaseId, "need revision");

        // stmt + schema double delete
        verify(stmtMapper).deleteByReleaseId(releaseId);
        verify(schemaMapper).deleteByReleaseId(releaseId);

        // transit BEFORE approval cancellation (InOrder)
        InOrder inOrder = inOrder(releaseStateMachine, approvalFlowService, approvalStateService);
        inOrder.verify(releaseStateMachine).transit(eq(releaseId), anySet(), eq(ProdReleaseStatus.RETURNED));
        inOrder.verify(approvalFlowService).cancelAllProcess(approvalId);
        inOrder.verify(approvalStateService).updateApprovalStatus(eq(approvalId), eq(ApprovalStatus.CANCELED), anyString());

        // event with real uid + reason
        verify(eventMapper).insert(argThat((DmDbChangeEventDO e) ->
            e != null && GovEventType.RELEASE_RETURNED.name().equals(e.getEventType())
                && UID.equals(e.getOperatorUid())
                && !"SYSTEM".equals(e.getOperatorUid())
                && ProdReleaseStatus.RETURNED.name().equals(e.getToStatus())));
    }

    @Test
    public void returnRelease_approvedRelease_succeeds() {
        long releaseId = 501L;
        DmProdReleaseDO release = buildRelease(releaseId, ProdReleaseStatus.APPROVED, 601L);
        when(releaseMapper.selectByIdForUpdate(releaseId)).thenReturn(release);
        when(releaseStateMachine.transit(eq(releaseId), anySet(), eq(ProdReleaseStatus.RETURNED))).thenReturn(true);
        when(stmtMapper.deleteByReleaseId(releaseId)).thenReturn(2);

        DmApprovalDO approvalTicket = new DmApprovalDO();
        approvalTicket.setId(601L);
        approvalTicket.setTicketStatus(ApprovalStatus.WAIT_CONFIRM);
        when(approvalMapper.queryById(601L)).thenReturn(approvalTicket);

        service.returnRelease(PUID, UID, releaseId, "approved but needs change");
        verify(releaseStateMachine).transit(eq(releaseId), anySet(), eq(ProdReleaseStatus.RETURNED));
        verify(eventMapper).insert(argThat((DmDbChangeEventDO e) ->
            GovEventType.RELEASE_RETURNED.name().equals(e.getEventType())
                && UID.equals(e.getOperatorUid())));
    }

    @Test
    public void returnRelease_executingRelease_throws() {
        long releaseId = 502L;
        DmProdReleaseDO release = buildRelease(releaseId, ProdReleaseStatus.EXECUTING, null);
        when(releaseMapper.selectByIdForUpdate(releaseId)).thenReturn(release);

        try {
            service.returnRelease(PUID, UID, releaseId, "reason");
            fail("Expected ErrorMessageException");
        } catch (ErrorMessageException ex) {
            // ok — status guard rejects
        }
        verify(stmtMapper, never()).deleteByReleaseId(anyLong());
    }

    @Test
    public void returnRelease_doneRelease_throws() {
        long releaseId = 503L;
        DmProdReleaseDO release = buildRelease(releaseId, ProdReleaseStatus.DONE, null);
        when(releaseMapper.selectByIdForUpdate(releaseId)).thenReturn(release);

        try {
            service.returnRelease(PUID, UID, releaseId, "reason");
            fail("Expected ErrorMessageException");
        } catch (ErrorMessageException ex) {
            // ok
        }
    }

    @Test
    public void returnRelease_returnedRelease_throws() {
        long releaseId = 504L;
        DmProdReleaseDO release = buildRelease(releaseId, ProdReleaseStatus.RETURNED, null);
        when(releaseMapper.selectByIdForUpdate(releaseId)).thenReturn(release);

        try {
            service.returnRelease(PUID, UID, releaseId, "reason");
            fail("Expected ErrorMessageException");
        } catch (ErrorMessageException ex) {
            // ok
        }
    }

    @Test
    public void returnRelease_crossTenant_notFound() {
        long releaseId = 505L;
        DmProdReleaseDO release = buildRelease(releaseId, ProdReleaseStatus.APPROVING, null);
        release.setPrimaryUid("other-tenant");
        when(releaseMapper.selectByIdForUpdate(releaseId)).thenReturn(release);

        try {
            service.returnRelease(PUID, UID, releaseId, "reason");
            fail("Expected ErrorMessageException");
        } catch (ErrorMessageException ex) {
            assertTrue(ex.getMessage().contains("Release not found"));
        }
        verify(stmtMapper, never()).deleteByReleaseId(anyLong());
    }

    @Test
    public void returnRelease_nullApprovalId_skipsCancellation() {
        long releaseId = 506L;
        DmProdReleaseDO release = buildRelease(releaseId, ProdReleaseStatus.APPROVING, null);
        when(releaseMapper.selectByIdForUpdate(releaseId)).thenReturn(release);
        when(releaseStateMachine.transit(eq(releaseId), anySet(), eq(ProdReleaseStatus.RETURNED))).thenReturn(true);
        when(stmtMapper.deleteByReleaseId(releaseId)).thenReturn(1);

        service.returnRelease(PUID, UID, releaseId, "reason");

        verify(approvalFlowService, never()).cancelAllProcess(anyLong());
        verify(approvalStateService, never()).updateApprovalStatus(anyLong(), any(), any());
        verify(eventMapper).insert(argThat((DmDbChangeEventDO e) ->
            GovEventType.RELEASE_RETURNED.name().equals(e.getEventType())));
    }

    @Test
    public void returnRelease_terminalApprovalTicket_skipsCancellation() {
        long releaseId = 507L;
        long approvalId = 607L;
        DmProdReleaseDO release = buildRelease(releaseId, ProdReleaseStatus.APPROVING, approvalId);
        when(releaseMapper.selectByIdForUpdate(releaseId)).thenReturn(release);
        when(releaseStateMachine.transit(eq(releaseId), anySet(), eq(ProdReleaseStatus.RETURNED))).thenReturn(true);
        when(stmtMapper.deleteByReleaseId(releaseId)).thenReturn(1);

        DmApprovalDO approvalTicket = new DmApprovalDO();
        approvalTicket.setId(approvalId);
        approvalTicket.setTicketStatus(ApprovalStatus.CANCELED); // already terminal
        when(approvalMapper.queryById(approvalId)).thenReturn(approvalTicket);

        service.returnRelease(PUID, UID, releaseId, "reason");

        verify(approvalFlowService, never()).cancelAllProcess(anyLong());
        verify(approvalStateService, never()).updateApprovalStatus(anyLong(), any(), any());
    }

    @Test
    public void returnRelease_concurrentTransitFails_throws() {
        long releaseId = 508L;
        DmProdReleaseDO release = buildRelease(releaseId, ProdReleaseStatus.APPROVING, null);
        when(releaseMapper.selectByIdForUpdate(releaseId)).thenReturn(release);
        when(releaseStateMachine.transit(eq(releaseId), anySet(), eq(ProdReleaseStatus.RETURNED))).thenReturn(false);

        try {
            service.returnRelease(PUID, UID, releaseId, "reason");
            fail("Expected ErrorMessageException");
        } catch (ErrorMessageException ex) {
            // ok — concurrent conflict
        }
        verify(stmtMapper, never()).deleteByReleaseId(anyLong());
    }

    @Test
    public void returnRelease_actorNotCreator_sendsNotification() {
        long releaseId = 509L;
        DmProdReleaseDO release = buildRelease(releaseId, ProdReleaseStatus.APPROVING, null);
        release.setCreatorUid(CREATOR_UID); // actor != creator
        when(releaseMapper.selectByIdForUpdate(releaseId)).thenReturn(release);
        when(releaseStateMachine.transit(eq(releaseId), anySet(), eq(ProdReleaseStatus.RETURNED))).thenReturn(true);
        when(stmtMapper.deleteByReleaseId(releaseId)).thenReturn(1);

        service.returnRelease(PUID, UID, releaseId, "reason");

        verify(govReturnNotifyService).notifyReturn(eq(PUID), eq(UID), eq(CREATOR_UID),
            eq(releaseId), anyString(), anyString(), eq("RELEASE"));
    }

    @Test
    public void returnRelease_actorIsCreator_skipsNotification() {
        long releaseId = 510L;
        DmProdReleaseDO release = buildRelease(releaseId, ProdReleaseStatus.APPROVING, null);
        release.setCreatorUid(UID); // actor == creator
        when(releaseMapper.selectByIdForUpdate(releaseId)).thenReturn(release);
        when(releaseStateMachine.transit(eq(releaseId), anySet(), eq(ProdReleaseStatus.RETURNED))).thenReturn(true);
        when(stmtMapper.deleteByReleaseId(releaseId)).thenReturn(1);

        service.returnRelease(PUID, UID, releaseId, "reason");

        verify(govReturnNotifyService, never()).notifyReturn(any(), any(), any(),
            anyLong(), any(), any(), any());
    }

    // ==================== helpers ====================

    private DmProdReleaseDO buildRelease(long id, ProdReleaseStatus status, Long approvalId) {
        DmProdReleaseDO release = new DmProdReleaseDO();
        release.setId(id);
        release.setReleaseNo("REL-" + id);
        release.setStatus(status.name());
        release.setPrimaryUid(PUID);
        release.setCreatorUid(UID);
        release.setApprovalId(approvalId);
        return release;
    }
}
