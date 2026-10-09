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
package com.clougence.clouddm.console.web.component.bridge;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.clougence.clouddm.console.web.component.alert.AlertContext;
import com.clougence.clouddm.console.web.component.alert.AlertPushService;
import com.clougence.clouddm.console.web.component.alert.AlertScene;
import com.clougence.clouddm.console.web.component.approval.ApprovalStateService;
import com.clougence.clouddm.console.web.component.approval.impl.ApprovalFlowServiceImpl;
import com.clougence.clouddm.console.web.component.approval.impl.ApprovalProviderServiceImpl;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.component.config.RootUserConfig;
import com.clougence.clouddm.console.web.model.fo.openapi.DbChangeTicketCommandFO;
import com.clougence.clouddm.console.web.service.governance.impl.GovOpenApiServiceImpl;
import com.clougence.clouddm.console.web.service.governance.impl.ProdReleaseServiceImpl;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.AuthDal;
import com.clougence.clouddm.platform.dal.access.DbChangeEventDal;
import com.clougence.clouddm.platform.dal.access.ProdReleaseDal;
import com.clougence.clouddm.platform.dal.access.SystemDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalProcessMapper;
import com.clougence.clouddm.platform.dal.mapper.auth.DmAuthUserMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangeEventMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseStmtMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseStmtSchemaMapper;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalType;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.auth.DmAuthUserDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.ProdReleaseStatus;
import com.clougence.clouddm.sdk.approval.ApprovalInstanceStatus;
import com.clougence.utils.JsonUtils;

/**
 * Task 10-09 Step 4 (design §2.2/§3.3 挂点 3, test matrix row 4): the outbound revoke
 * notification — service-level guards (no flowId = ZERO-call skip, non-External/switch-off
 * skips, best-effort failure = alert once + never blocks), the human-path hook wiring
 * (ApprovalFlowServiceImpl.cancelTicket/closeTicket — the V1/V2 console convergence point —
 * and ProdReleaseServiceImpl.returnRelease/cancelRelease), and the ECHO ISOLATION pin: the
 * inbound /tickets/cancel open command travels GovOpenApiService → applyExternalApprovalResult
 * and never passes a console hook, so a bridge command can never bounce an outbound cancel
 * back to the bridge (design §1).
 */
public class DingBridgeCancelNotifyTest {

    private static final String PUID        = "puid-001";
    private static final String UID         = "uid-op";
    private static final long   APPROVAL_ID = 900L;

    // ---- service fixture ----
    private DingBridgeOutboundService service;
    private SystemDal                 systemDal;
    private DmApprovalMapper          approvalMapper;
    private DingBridgeClient          client;
    private AlertPushService          alertPushService;

    @Before
    public void setUp() {
        service = new DingBridgeOutboundService();
        systemDal = mock(SystemDal.class);
        ApprovalDal approvalDal = mock(ApprovalDal.class);
        approvalMapper = mock(DmApprovalMapper.class);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);
        AuthDal authDal = mock(AuthDal.class);
        when(authDal.userMapper()).thenReturn(mock(DmAuthUserMapper.class));
        client = mock(DingBridgeClient.class);
        alertPushService = mock(AlertPushService.class);

        ReflectionTestUtils.setField(service, "systemDal", systemDal);
        ReflectionTestUtils.setField(service, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(service, "authDal", authDal);
        ReflectionTestUtils.setField(service, "dingBridgeClient", client);
        ReflectionTestUtils.setField(service, "alertPushService", alertPushService);
        ReflectionTestUtils.setField(service, "txManager", mock(PlatformTransactionManager.class));

        when(systemDal.fetchSystemConf(RootUserConfig.Fields.dingBridgeOutboundEnable, Boolean.class))
            .thenReturn(Boolean.TRUE);
        when(systemDal.fetchSystemConf(RootUserConfig.Fields.dingBridgeBaseUrl, String.class))
            .thenReturn("http://bridge:8080");
        when(client.cancelFlow(any(), any())).thenReturn(BridgeFlowResult.ok(null, false));
    }

    @After
    public void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ==================== service-level guards ====================

    @Test
    public void noFlowId_zeroCallSkip() {
        DmApprovalDO row = externalRow(null); // appro_identity blank = never initiated
        when(approvalMapper.queryById(APPROVAL_ID)).thenReturn(row);

        assertFalse(service.notifyCancelFlow(PUID, APPROVAL_ID, DingBridgeOutboundService.REASON_LOCAL_CANCELED));

        verifyNoInteractions(client);
    }

    @Test
    public void nonExternal_skipped() {
        DmApprovalDO row = externalRow("flow-1");
        row.setApproType(ApprovalType.Internal);
        when(approvalMapper.queryById(APPROVAL_ID)).thenReturn(row);

        assertFalse(service.notifyCancelFlow(PUID, APPROVAL_ID, DingBridgeOutboundService.REASON_LOCAL_CANCELED));
        verifyNoInteractions(client);
    }

    @Test
    public void switchOff_skipped() {
        when(systemDal.fetchSystemConf(RootUserConfig.Fields.dingBridgeOutboundEnable, Boolean.class))
            .thenReturn(Boolean.FALSE);
        when(approvalMapper.queryById(APPROVAL_ID)).thenReturn(externalRow("flow-1"));

        assertFalse(service.notifyCancelFlow(PUID, APPROVAL_ID, DingBridgeOutboundService.REASON_LOCAL_CANCELED));
        verifyNoInteractions(client);
    }

    @Test
    public void nullApprovalId_skipped() {
        assertFalse(service.notifyCancelFlow(PUID, null, DingBridgeOutboundService.REASON_LOCAL_CANCELED));
        verifyNoInteractions(client);
    }

    @Test
    public void cancel_payloadShape_ticketRow() {
        when(approvalMapper.queryById(APPROVAL_ID)).thenReturn(externalRow("flow-1"));

        assertTrue(service.notifyCancelFlow(PUID, APPROVAL_ID, DingBridgeOutboundService.REASON_LOCAL_CANCELED));

        ArgumentCaptor<BridgeCancelRequest> captor = ArgumentCaptor.forClass(BridgeCancelRequest.class);
        verify(client).cancelFlow(any(), captor.capture());
        BridgeCancelRequest req = captor.getValue();
        assertEquals("TICKET", req.getBizType());
        assertEquals("900", req.getBizId());
        assertEquals("flow-1", req.getFlowId());
        assertEquals("LOCAL_CANCELED", req.getReason());
    }

    @Test
    public void cancel_releaseRow_bizIdIsReleaseId() {
        DmApprovalDO row = externalRow("flow-2");
        row.setApproBiz(ApprovalBiz.DM_PROD_RELEASE);
        ApprovalMO mo = new ApprovalMO();
        mo.setReleaseId(600L);
        mo.setReleaseNo("REL-1");
        row.setTicketInfo(JsonUtils.toJson(mo));
        when(approvalMapper.queryById(APPROVAL_ID)).thenReturn(row);

        service.notifyCancelFlow(PUID, APPROVAL_ID, DingBridgeOutboundService.REASON_RELEASE_RETURNED);

        ArgumentCaptor<BridgeCancelRequest> captor = ArgumentCaptor.forClass(BridgeCancelRequest.class);
        verify(client).cancelFlow(any(), captor.capture());
        assertEquals("RELEASE", captor.getValue().getBizType());
        assertEquals("600", captor.getValue().getBizId());
        assertEquals("RELEASE_RETURNED", captor.getValue().getReason());
    }

    @Test
    public void cancel_insideTransaction_defersToAfterCommit() {
        when(approvalMapper.queryById(APPROVAL_ID)).thenReturn(externalRow("flow-1"));
        TransactionSynchronizationManager.initSynchronization();

        assertTrue(service.notifyCancelFlow(PUID, APPROVAL_ID, DingBridgeOutboundService.REASON_LOCAL_CLOSED));
        verifyNoInteractions(client); // not sent while the tx is open

        List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        assertEquals(1, syncs.size());
        syncs.forEach(TransactionSynchronization::afterCommit);
        verify(client).cancelFlow(any(), any());
    }

    @Test
    public void cancel_failure_alertOnce_neverThrows_localFlowUnaffected() {
        when(approvalMapper.queryById(APPROVAL_ID)).thenReturn(externalRow("flow-1"));
        when(client.cancelFlow(any(), any())).thenReturn(BridgeFlowResult.failure("HTTP 503", true));

        // must not throw (best-effort semantics)
        service.notifyCancelFlow(PUID, APPROVAL_ID, DingBridgeOutboundService.REASON_LOCAL_CANCELED);

        ArgumentCaptor<AlertContext> ctx = ArgumentCaptor.forClass(AlertContext.class);
        verify(alertPushService, times(1)).pushAlert(eq(PUID), eq(AlertScene.DING_BRIDGE_OUTBOUND_FAILED), ctx.capture());
        assertEquals(AlertContext.BRIDGE_OP_CANCEL, ctx.getValue().getBridgeOp());
        assertFalse("cancel failures never schedule retries", ctx.getValue().isBridgeFinal());
    }

    @Test
    public void cancel_clientCrash_degrades() {
        when(approvalMapper.queryById(APPROVAL_ID)).thenReturn(externalRow("flow-1"));
        when(client.cancelFlow(any(), any())).thenThrow(new RuntimeException("unexpected"));

        service.notifyCancelFlow(PUID, APPROVAL_ID, DingBridgeOutboundService.REASON_LOCAL_CANCELED); // no throw
    }

    // ==================== hook wiring: ApprovalFlowServiceImpl (human convergence point) ====================

    @Test
    public void cancelTicket_hook_notifiesLocalCanceled() {
        DingBridgeOutboundService bridge = mock(DingBridgeOutboundService.class);
        ApprovalFlowServiceImpl flowService = buildFlowService(bridge, ApprovalStatus.WAIT_APPROVAL);

        flowService.cancelTicket(PUID, APPROVAL_ID, "canceled at console");

        verify(bridge).notifyCancelFlow(PUID, APPROVAL_ID, DingBridgeOutboundService.REASON_LOCAL_CANCELED);
    }

    @Test
    public void closeTicket_hook_notifiesLocalClosed() {
        DingBridgeOutboundService bridge = mock(DingBridgeOutboundService.class);
        ApprovalFlowServiceImpl flowService = buildFlowService(bridge, ApprovalStatus.WAIT_APPROVAL);

        flowService.closeTicket(APPROVAL_ID, "closed at console", PUID, UID);

        verify(bridge).notifyCancelFlow(PUID, APPROVAL_ID, DingBridgeOutboundService.REASON_LOCAL_CLOSED);
    }

    @Test
    public void cancelTicket_terminalGuardThrows_noNotification() {
        DingBridgeOutboundService bridge = mock(DingBridgeOutboundService.class);
        ApprovalFlowServiceImpl flowService = buildFlowService(bridge, ApprovalStatus.CANCELED);

        try {
            flowService.cancelTicket(PUID, APPROVAL_ID, "too late");
            fail("Expected ErrorMessageException on terminal ticket");
        } catch (RuntimeException expected) {
            // checkInProgress rejects terminal tickets — the transition (and thus the hook) never runs
        }
        verifyNoInteractions(bridge);
    }

    @Test
    public void cancelTicket_bridgeHookCrash_neverBreaksLocalTransition() {
        DingBridgeOutboundService bridge = mock(DingBridgeOutboundService.class);
        when(bridge.notifyCancelFlow(anyString(), anyLong(), anyString())).thenThrow(new RuntimeException("boom"));
        ApprovalFlowServiceImpl flowService = buildFlowService(bridge, ApprovalStatus.WAIT_APPROVAL);

        flowService.cancelTicket(PUID, APPROVAL_ID, "canceled at console"); // must not throw

        ApprovalStateService stateService = (ApprovalStateService) ReflectionTestUtils.getField(flowService, "approvalStateService");
        verify(stateService).finalizeApproval(eq(APPROVAL_ID), eq(ApprovalStatus.CANCELED), anyString());
    }

    // ==================== hook wiring: ProdReleaseServiceImpl (return / cancel) ====================

    @Test
    public void returnRelease_hook_notifiesReleaseReturned() {
        DingBridgeOutboundService bridge = mock(DingBridgeOutboundService.class);
        ProdReleaseServiceImpl releaseService = buildReleaseService(bridge);
        DmProdReleaseDO release = release(ProdReleaseStatus.APPROVING, 700L);
        when(releaseMapperOf(releaseService).selectByIdForUpdate(500L)).thenReturn(release);
        when(stateMachineOf(releaseService).transit(eq(500L), anySet(), eq(ProdReleaseStatus.RETURNED))).thenReturn(true);
        // the approval ticket behind the release (not terminal → Step 5 cancels it)
        DmApprovalDO approvalTicket = externalRow("flow-r");
        approvalTicket.setId(700L);
        when(approvalMapperOf(releaseService).queryById(700L)).thenReturn(approvalTicket);

        releaseService.returnRelease(PUID, UID, 500L, "申请人撤回");

        verify(bridge).notifyCancelFlow(PUID, 700L, DingBridgeOutboundService.REASON_RELEASE_RETURNED);
    }

    @Test
    public void cancelRelease_hook_notifiesLocalCanceled() {
        DingBridgeOutboundService bridge = mock(DingBridgeOutboundService.class);
        ProdReleaseServiceImpl releaseService = buildReleaseService(bridge);
        DmProdReleaseDO release = release(ProdReleaseStatus.RETURNED, 700L);
        when(releaseMapperOf(releaseService).selectByIdForUpdate(500L)).thenReturn(release);
        when(stateMachineOf(releaseService).transit(eq(500L), anySet(), eq(ProdReleaseStatus.CANCELLED))).thenReturn(true);

        releaseService.cancelRelease(PUID, UID, true, 500L);

        verify(bridge).notifyCancelFlow(PUID, 700L, DingBridgeOutboundService.REASON_LOCAL_CANCELED);
    }

    @Test
    public void returnRelease_bridgeHookCrash_neverBreaksReturn() {
        DingBridgeOutboundService bridge = mock(DingBridgeOutboundService.class);
        when(bridge.notifyCancelFlow(anyString(), any(), anyString())).thenThrow(new RuntimeException("boom"));
        ProdReleaseServiceImpl releaseService = buildReleaseService(bridge);
        DmProdReleaseDO release = release(ProdReleaseStatus.APPROVING, 700L);
        when(releaseMapperOf(releaseService).selectByIdForUpdate(500L)).thenReturn(release);
        when(stateMachineOf(releaseService).transit(eq(500L), anySet(), eq(ProdReleaseStatus.RETURNED))).thenReturn(true);
        DmApprovalDO approvalTicket = externalRow("flow-r");
        approvalTicket.setId(700L);
        when(approvalMapperOf(releaseService).queryById(700L)).thenReturn(approvalTicket);

        releaseService.returnRelease(PUID, UID, 500L, "撤回"); // must not throw
    }

    // ==================== echo isolation (design §1) ====================

    /**
     * The inbound /tickets/cancel open command routes EXCLUSIVELY through
     * applyExternalApprovalResult — it never passes ApprovalFlowServiceImpl.cancelTicket/closeTicket
     * (the only outbound-cancel hooks) nor ProdReleaseServiceImpl.returnRelease/cancelRelease, so a
     * bridge-issued cancel can never bounce an outbound flows/cancel back to the bridge.
     */
    @Test
    public void inboundCancelCommand_routesToApplyExternalResult_onlyZeroConsoleHooks() {
        GovOpenApiServiceImpl openApi = new GovOpenApiServiceImpl();
        ApprovalDal approvalDal = mock(ApprovalDal.class);
        DmApprovalMapper mapper = mock(DmApprovalMapper.class);
        when(approvalDal.approvalMapper()).thenReturn(mapper);
        DmApprovalDO row = externalRow("flow-1");
        when(mapper.queryById(APPROVAL_ID)).thenReturn(row);
        ApprovalProviderServiceImpl providerService = mock(ApprovalProviderServiceImpl.class);
        ReflectionTestUtils.setField(openApi, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(openApi, "approvalProviderService", providerService);

        DbChangeTicketCommandFO fo = new DbChangeTicketCommandFO();
        fo.setTicketId(APPROVAL_ID);
        fo.setApprover("李四");
        openApi.cancelTicket(PUID, UID, fo);

        // the inbound path = the command applier, exclusively
        ArgumentCaptor<ApprovalInstanceStatus> statusCaptor = ArgumentCaptor.forClass(ApprovalInstanceStatus.class);
        verify(providerService).applyExternalApprovalResult(eq(APPROVAL_ID), statusCaptor.capture(), any());
        assertEquals(ApprovalInstanceStatus.TERMINATED, statusCaptor.getValue());
    }

    /** structural pin: the inbound command plane carries NO bridge dependency — nothing outbound can fire from it. */
    @Test
    public void inboundCommandPlane_hasNoBridgeDependency_structuralPin() {
        for (java.lang.reflect.Field f : GovOpenApiServiceImpl.class.getDeclaredFields()) {
            assertNotEquals("GovOpenApiServiceImpl must never depend on the outbound bridge (echo isolation)",
                    DingBridgeOutboundService.class, f.getType());
        }
        for (java.lang.reflect.Field f : ApprovalProviderServiceImpl.class.getDeclaredFields()) {
            assertNotEquals("applyExternalApprovalResult must never depend on the outbound bridge (echo isolation)",
                    DingBridgeOutboundService.class, f.getType());
        }
    }

    // ==================== helpers ====================

    private ApprovalFlowServiceImpl buildFlowService(DingBridgeOutboundService bridge, ApprovalStatus status) {
        ApprovalFlowServiceImpl flowService = new ApprovalFlowServiceImpl(Collections.emptyList());
        ApprovalDal approvalDal = mock(ApprovalDal.class);
        DmApprovalMapper mapper = mock(DmApprovalMapper.class);
        when(approvalDal.approvalMapper()).thenReturn(mapper);
        DmApprovalProcessMapper processMapper = mock(DmApprovalProcessMapper.class);
        when(approvalDal.processMapper()).thenReturn(processMapper);
        when(processMapper.listByTicketId(APPROVAL_ID)).thenReturn(Collections.emptyList());

        DmApprovalDO row = externalRow("flow-1");
        row.setTicketStatus(status);
        row.setDeleted(false);
        when(mapper.queryById(APPROVAL_ID)).thenReturn(row);

        AuthDal authDal = mock(AuthDal.class);
        DmAuthUserMapper userMapper = mock(DmAuthUserMapper.class);
        when(authDal.userMapper()).thenReturn(userMapper);
        DmAuthUserDO user = new DmAuthUserDO();
        user.setUid(UID);
        user.setUsername("op-user");
        when(userMapper.queryByUid(UID)).thenReturn(user);

        ApprovalStateService stateService = mock(ApprovalStateService.class);
        ReflectionTestUtils.setField(flowService, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(flowService, "authDal", authDal);
        ReflectionTestUtils.setField(flowService, "approvalStateService", stateService);
        ReflectionTestUtils.setField(flowService, "dingBridgeOutboundService", bridge);
        return flowService;
    }

    private DmProdReleaseMapper releaseMapperOf(ProdReleaseServiceImpl s) {
        return ((ProdReleaseDal) ReflectionTestUtils.getField(s, "prodReleaseDal")).releaseMapper();
    }

    private com.clougence.clouddm.console.web.component.governance.ProdReleaseStateMachine stateMachineOf(ProdReleaseServiceImpl s) {
        return (com.clougence.clouddm.console.web.component.governance.ProdReleaseStateMachine)
            ReflectionTestUtils.getField(s, "releaseStateMachine");
    }

    private DmApprovalMapper approvalMapperOf(ProdReleaseServiceImpl s) {
        return ((ApprovalDal) ReflectionTestUtils.getField(s, "approvalDal")).approvalMapper();
    }

    private ProdReleaseServiceImpl buildReleaseService(DingBridgeOutboundService bridge) {
        ProdReleaseServiceImpl s = new ProdReleaseServiceImpl();
        ProdReleaseDal prodReleaseDal = mock(ProdReleaseDal.class);
        DmProdReleaseMapper releaseMapper = mock(DmProdReleaseMapper.class);
        DmProdReleaseStmtMapper stmtMapper = mock(DmProdReleaseStmtMapper.class);
        DmProdReleaseStmtSchemaMapper schemaMapper = mock(DmProdReleaseStmtSchemaMapper.class);
        when(prodReleaseDal.releaseMapper()).thenReturn(releaseMapper);
        when(prodReleaseDal.stmtMapper()).thenReturn(stmtMapper);
        when(prodReleaseDal.schemaMapper()).thenReturn(schemaMapper);

        ApprovalDal approvalDal = mock(ApprovalDal.class);
        when(approvalDal.approvalMapper()).thenReturn(mock(DmApprovalMapper.class));

        DbChangeEventDal dbChangeEventDal = mock(DbChangeEventDal.class);
        DmDbChangeEventMapper eventMapper = mock(DmDbChangeEventMapper.class);
        when(dbChangeEventDal.eventMapper()).thenReturn(eventMapper);
        when(eventMapper.insert(any(DmDbChangeEventDO.class))).thenReturn(1);

        ApprovalFlowServiceImpl flowService = mock(ApprovalFlowServiceImpl.class);

        ReflectionTestUtils.setField(s, "prodReleaseDal", prodReleaseDal);
        ReflectionTestUtils.setField(s, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(s, "dbChangeEventDal", dbChangeEventDal);
        ReflectionTestUtils.setField(s, "releaseStateMachine",
            mock(com.clougence.clouddm.console.web.component.governance.ProdReleaseStateMachine.class));
        ReflectionTestUtils.setField(s, "approvalFlowService", flowService);
        ReflectionTestUtils.setField(s, "approvalStateService", mock(ApprovalStateService.class));
        ReflectionTestUtils.setField(s, "govReturnNotifyService",
            mock(com.clougence.clouddm.console.web.component.governance.GovReturnNotifyService.class));
        ReflectionTestUtils.setField(s, "dingBridgeOutboundService", bridge);
        return s;
    }

    private static DmProdReleaseDO release(ProdReleaseStatus status, Long approvalId) {
        DmProdReleaseDO release = new DmProdReleaseDO();
        release.setId(500L);
        release.setReleaseNo("REL-500");
        release.setStatus(status.name());
        release.setPrimaryUid(PUID);
        release.setCreatorUid(UID); // actor == creator → no IM notify branch
        release.setApprovalId(approvalId);
        return release;
    }

    private DmApprovalDO externalRow(String approIdentity) {
        DmApprovalDO row = new DmApprovalDO();
        row.setId(APPROVAL_ID);
        row.setBizId("biz-900");
        row.setOwnerUid(UID);
        row.setPrimaryUid(PUID);
        row.setApproType(ApprovalType.External);
        row.setApproBiz(ApprovalBiz.DM_CHANGE);
        row.setTicketStatus(ApprovalStatus.WAIT_APPROVAL);
        row.setApproIdentity(approIdentity);
        row.setDeleted(false);
        ApprovalMO mo = new ApprovalMO();
        mo.setTicketType("PROD_DML");
        row.setTicketInfo(JsonUtils.toJson(mo));
        return row;
    }
}
