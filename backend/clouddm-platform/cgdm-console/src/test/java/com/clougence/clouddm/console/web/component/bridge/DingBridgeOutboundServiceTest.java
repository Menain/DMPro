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

import java.util.Date;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.clougence.clouddm.console.web.component.alert.AlertContext;
import com.clougence.clouddm.console.web.component.alert.AlertPushService;
import com.clougence.clouddm.console.web.component.alert.AlertScene;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.component.config.RootUserConfig;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.AuthDal;
import com.clougence.clouddm.platform.dal.access.SystemDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.auth.DmAuthUserMapper;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalType;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.auth.DmAuthUserDO;
import com.clougence.utils.JsonUtils;

/**
 * Task 10-09 Step 2 (design §2/§3, test matrix row 1): DingBridgeOutboundService core —
 * kill-switch/baseUrl fail-safe gates (the switch fails CLOSED), payload assembly for both
 * bizType shapes, FlowResult → outbound_status write-backs (REQUIRES_NEW seam mocked at the
 * PTM), 4xx/5xx retry classification incl. the client-level toFlowResult matrix, the
 * single-shot alert cadence, claim-race skips and the appro_identity "write only when empty"
 * dual-source contract (boundary #4/#6). ALL bridge calls are mocked — zero real network (红线).
 */
public class DingBridgeOutboundServiceTest {

    private DingBridgeOutboundService service;
    private SystemDal                 systemDal;
    private DmApprovalMapper          approvalMapper;
    private DmAuthUserMapper          userMapper;
    private DingBridgeClient          client;
    private AlertPushService          alertPushService;
    private PlatformTransactionManager txManager;

    private static final String PUID        = "puid-001";
    private static final String UID         = "uid-owner";
    private static final long   APPROVAL_ID = 900L;
    private static final String BASE_URL    = "http://bridge:8080";

    @Before
    public void setUp() {
        // decrypt seam overridden (no CryptService in bare unit tests)
        service = new DingBridgeOutboundService() {
            @Override
            protected String decryptConfigValue(String encrypted) {
                return "decrypted-" + encrypted;
            }
        };
        systemDal = mock(SystemDal.class);
        ApprovalDal approvalDal = mock(ApprovalDal.class);
        approvalMapper = mock(DmApprovalMapper.class);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);
        AuthDal authDal = mock(AuthDal.class);
        userMapper = mock(DmAuthUserMapper.class);
        when(authDal.userMapper()).thenReturn(userMapper);
        DmAuthUserDO owner = new DmAuthUserDO();
        owner.setUid(UID);
        owner.setUsername("zhang-san");
        when(userMapper.queryByUid(UID)).thenReturn(owner);

        client = mock(DingBridgeClient.class);
        alertPushService = mock(AlertPushService.class);

        ReflectionTestUtils.setField(service, "systemDal", systemDal);
        ReflectionTestUtils.setField(service, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(service, "authDal", authDal);
        ReflectionTestUtils.setField(service, "dingBridgeClient", client);
        ReflectionTestUtils.setField(service, "alertPushService", alertPushService);
        txManager = mock(PlatformTransactionManager.class);
        ReflectionTestUtils.setField(service, "txManager", txManager);

        // KVs: switch ON, baseUrl set, token set, timeout default
        when(systemDal.fetchSystemConf(RootUserConfig.Fields.dingBridgeOutboundEnable, Boolean.class))
            .thenReturn(Boolean.TRUE);
        when(systemDal.fetchSystemConf(RootUserConfig.Fields.dingBridgeBaseUrl, String.class))
            .thenReturn(BASE_URL);
        when(systemDal.fetchSystemConf(RootUserConfig.Fields.dingBridgeAuthToken))
            .thenReturn("enc-token");
        when(systemDal.fetchSystemConf(RootUserConfig.Fields.dingBridgeTimeoutMs, Integer.class))
            .thenReturn(null);

        // default row: External PROD_DML DIRECT ticket, claim wins
        when(approvalMapper.queryById(APPROVAL_ID)).thenReturn(externalTicket());
        when(approvalMapper.claimOutboundFirstSend(APPROVAL_ID)).thenReturn(1);
        when(client.createFlow(any(), any())).thenReturn(BridgeFlowResult.ok("flow-001", false));
    }

    @After
    public void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ==================== constant drift pins ====================

    @Test
    public void policyConstants_driftPin() {
        assertFalse("kill switch default must fail CLOSED", DingBridgeOutboundService.DEFAULT_OUTBOUND_ENABLE);
        assertEquals(10_000, DingBridgeOutboundService.DEFAULT_TIMEOUT_MS);
        assertEquals(1_000, DingBridgeOutboundService.MIN_TIMEOUT_MS);
        assertEquals(60_000, DingBridgeOutboundService.MAX_TIMEOUT_MS);
        assertEquals(3, DingBridgeOutboundService.MAX_AUTO_RETRY);
        assertArrayEquals("backoff 1m/5m/15m (design §4)", new long[] { 60_000L, 300_000L, 900_000L },
                DingBridgeOutboundService.BACKOFF_MS);
        assertEquals(200, DingBridgeOutboundService.SUMMARY_MAX_CHARS);
        assertEquals(500, DingBridgeOutboundService.ERROR_MAX_CHARS);
        assertEquals(3_000L, DingBridgeClient.CONNECT_TIMEOUT_MS);
    }

    /** reflection drift guard (GhostServiceImplTest precedent): the code-side fail-safe defaults
     *  must mirror the RootUserConfig @UserConfigDef defaultValues, and the token must be isSecret. */
    @Test
    public void rootUserConfig_bridgeKvDefaults_mirrorTheCodeConstants() throws Exception {
        java.lang.reflect.Field enable = RootUserConfig.class.getDeclaredField("dingBridgeOutboundEnable");
        com.clougence.clouddm.console.web.component.config.UserConfigDef enableDef = enable
                .getAnnotation(com.clougence.clouddm.console.web.component.config.UserConfigDef.class);
        assertEquals("false", enableDef.defaultValue());
        assertEquals("kill switch fails CLOSED", false, DingBridgeOutboundService.DEFAULT_OUTBOUND_ENABLE);

        java.lang.reflect.Field baseUrl = RootUserConfig.class.getDeclaredField("dingBridgeBaseUrl");
        assertEquals("", baseUrl.getAnnotation(com.clougence.clouddm.console.web.component.config.UserConfigDef.class)
                .defaultValue());

        java.lang.reflect.Field timeout = RootUserConfig.class.getDeclaredField("dingBridgeTimeoutMs");
        assertEquals(String.valueOf(DingBridgeOutboundService.DEFAULT_TIMEOUT_MS),
                timeout.getAnnotation(com.clougence.clouddm.console.web.component.config.UserConfigDef.class).defaultValue());

        java.lang.reflect.Field token = RootUserConfig.class.getDeclaredField("dingBridgeAuthToken");
        com.clougence.clouddm.console.web.component.config.UserConfigDef tokenDef = token
                .getAnnotation(com.clougence.clouddm.console.web.component.config.UserConfigDef.class);
        assertTrue("the bridge token is a credential — isSecret (tencentCloudSecretKey precedent)", tokenDef.isSecret());
        assertFalse("a real secret must never echo", tokenDef.secretEcho());
    }

    // ==================== gates (switch / baseUrl / channel) ====================

    @Test
    public void switchOff_triggerSkipped_zeroClientZeroClaim() {
        when(systemDal.fetchSystemConf(RootUserConfig.Fields.dingBridgeOutboundEnable, Boolean.class))
            .thenReturn(Boolean.FALSE);

        assertFalse(service.triggerCreateFlow(PUID, APPROVAL_ID));

        verify(approvalMapper, never()).claimOutboundFirstSend(anyLong());
        verifyNoInteractions(client);
    }

    @Test
    public void switchUnset_failSafeClosed_skips() {
        when(systemDal.fetchSystemConf(RootUserConfig.Fields.dingBridgeOutboundEnable, Boolean.class))
            .thenReturn(null);

        assertFalse(service.triggerCreateFlow(PUID, APPROVAL_ID));
        verifyNoInteractions(client);
    }

    @Test
    public void switchReadFailure_failSafeClosed_skips() {
        when(systemDal.fetchSystemConf(RootUserConfig.Fields.dingBridgeOutboundEnable, Boolean.class))
            .thenThrow(new RuntimeException("kv store down"));

        assertFalse(service.triggerCreateFlow(PUID, APPROVAL_ID));
        verifyNoInteractions(client);
    }

    @Test
    public void baseUrlBlank_triggerSkipped_statusStaysNotStarted() {
        when(systemDal.fetchSystemConf(RootUserConfig.Fields.dingBridgeBaseUrl, String.class)).thenReturn("  ");

        assertFalse(service.triggerCreateFlow(PUID, APPROVAL_ID));

        verify(approvalMapper, never()).claimOutboundFirstSend(anyLong());
        verifyNoInteractions(client);
    }

    @Test
    public void nonExternalRow_triggerSkipped() {
        DmApprovalDO internal = externalTicket();
        internal.setApproType(ApprovalType.Internal);
        when(approvalMapper.queryById(APPROVAL_ID)).thenReturn(internal);

        assertFalse(service.triggerCreateFlow(PUID, APPROVAL_ID));

        verify(approvalMapper, never()).claimOutboundFirstSend(anyLong());
        verifyNoInteractions(client);
    }

    @Test
    public void terminalTicket_sendSkipped_defensive() {
        DmApprovalDO canceled = externalTicket();
        canceled.setTicketStatus(ApprovalStatus.CANCELED);
        when(approvalMapper.queryById(APPROVAL_ID)).thenReturn(canceled);

        service.triggerCreateFlow(PUID, APPROVAL_ID); // no tx → immediate defensive branch

        verify(approvalMapper, never()).claimOutboundFirstSend(anyLong());
        verifyNoInteractions(client);
    }

    // ==================== afterCommit registration (GovReturnNotifyService template) ====================

    @Test
    public void insideTransaction_defersToAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();

        assertTrue(service.triggerCreateFlow(PUID, APPROVAL_ID));
        // nothing sent while the transaction is open
        verifyNoInteractions(client);

        List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        assertEquals(1, syncs.size());
        syncs.forEach(TransactionSynchronization::afterCommit);
        verify(client).createFlow(any(), any());
    }

    @Test
    public void withoutTransaction_firesImmediately_defensiveBranch() {
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive());

        assertTrue(service.triggerCreateFlow(PUID, APPROVAL_ID));

        verify(client).createFlow(any(), any());
        verify(approvalMapper).markOutboundStarted(APPROVAL_ID);
    }

    /**
     * afterCommit stale-connection trap — STRUCTURAL PIN (check fix 2026-10-09): inside the
     * FIRST-path afterCommit callback NO bare mapper call may precede the first REQUIRES_NEW
     * block. Otherwise mybatis-spring binds the SqlSession to the already-committed business-tx
     * connection (registerSessionHolder keys off isSynchronizationActive, still true during
     * afterCommit) and every later write — claim/STARTED/flowId, even inside REQUIRES_NEW (the
     * SqlSessionHolder survives DataSourceTransactionManager.doSuspend) — is silently rolled back
     * at session close ("no commit following anymore", Spring afterCommit contract). Pin: the
     * callback's first DB touch is the phase-① tx open, the read+claim COMMIT before the HTTP
     * call, the write-back rides a second REQUIRES_NEW tx, and every template tx is
     * PROPAGATION_REQUIRES_NEW.
     */
    @Test
    public void afterCommitPath_everyDbPhaseInsideRequiresNew_structuralPin() {
        when(approvalMapper.updateOutboundFlowIdIfEmpty(APPROVAL_ID, "flow-001")).thenReturn(1);
        TransactionSynchronizationManager.initSynchronization();

        assertTrue(service.triggerCreateFlow(PUID, APPROVAL_ID));
        verifyNoInteractions(client); // registered, not fired yet
        verify(approvalMapper).queryById(APPROVAL_ID); // the ONE registration-time read (inside the business tx)

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

        InOrder order = inOrder(txManager, approvalMapper, client);
        order.verify(txManager).getTransaction(any());            // phase ① tx opens FIRST in the callback
        order.verify(approvalMapper).queryById(APPROVAL_ID);      // fresh read INSIDE the phase-① tx
        order.verify(approvalMapper).claimOutboundFirstSend(APPROVAL_ID);
        order.verify(txManager).commit(any());                    // claim COMMITTED before the HTTP call
        order.verify(client).createFlow(any(), any());            // phase ② — outside any tx
        order.verify(txManager).getTransaction(any());            // phase ③ tx
        order.verify(approvalMapper).markOutboundStarted(APPROVAL_ID);
        order.verify(approvalMapper).updateOutboundFlowIdIfEmpty(APPROVAL_ID, "flow-001");
        order.verify(txManager).commit(any());

        ArgumentCaptor<TransactionDefinition> defCaptor = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(txManager, times(2)).getTransaction(defCaptor.capture());
        for (TransactionDefinition def : defCaptor.getAllValues()) {
            assertEquals("afterCommit data access mandates REQUIRES_NEW (Spring contract)",
                    TransactionDefinition.PROPAGATION_REQUIRES_NEW, def.getPropagationBehavior());
        }
    }

    // ==================== payload assembly (design §2.1) ====================

    @Test
    public void payload_ticketShape_fullContract() {
        service.triggerCreateFlow(PUID, APPROVAL_ID);

        ArgumentCaptor<BridgeEndpoint> epCaptor = ArgumentCaptor.forClass(BridgeEndpoint.class);
        ArgumentCaptor<BridgeCreateRequest> reqCaptor = ArgumentCaptor.forClass(BridgeCreateRequest.class);
        verify(client).createFlow(epCaptor.capture(), reqCaptor.capture());

        BridgeEndpoint ep = epCaptor.getValue();
        assertEquals(BASE_URL, ep.getBaseUrl());
        assertEquals("decrypted-enc-token", ep.getAuthToken());
        assertEquals(DingBridgeOutboundService.DEFAULT_TIMEOUT_MS, ep.getTimeoutMs());

        BridgeCreateRequest req = reqCaptor.getValue();
        assertEquals("TICKET", req.getBizType());
        assertEquals("900", req.getBizId());          // ticketId as string = the bridge dedup key
        assertEquals("biz-900", req.getBizNo());      // dm_approval.biz_id display number
        assertEquals("orders 表新增列", req.getTitle());
        assertEquals(UID, req.getApplicant().getAccount());
        assertEquals("zhang-san", req.getApplicant().getName());
        assertEquals("PROD_DML", req.getContext().getApproBiz());
        assertEquals(PUID, req.getContext().getPuid());
    }

    @Test
    public void payload_releaseShape_bizIdIsReleaseId() {
        DmApprovalDO releaseApproval = externalTicket();
        releaseApproval.setApproBiz(ApprovalBiz.DM_PROD_RELEASE);
        ApprovalMO mo = new ApprovalMO();
        mo.setReleaseId(600L);
        mo.setReleaseNo("REL-20261009-0001");
        releaseApproval.setTicketInfo(JsonUtils.toJson(mo));
        when(approvalMapper.queryById(APPROVAL_ID)).thenReturn(releaseApproval);

        service.triggerCreateFlow(PUID, APPROVAL_ID);

        ArgumentCaptor<BridgeCreateRequest> reqCaptor = ArgumentCaptor.forClass(BridgeCreateRequest.class);
        verify(client).createFlow(any(), reqCaptor.capture());
        BridgeCreateRequest req = reqCaptor.getValue();
        assertEquals("RELEASE", req.getBizType());
        assertEquals("600", req.getBizId());
        assertEquals("REL-20261009-0001", req.getBizNo());
        assertEquals("DM_PROD_RELEASE", req.getContext().getApproBiz());
    }

    @Test
    public void payload_summaryTruncatedTo200() {
        DmApprovalDO row = externalTicket();
        row.setRawSql("U".repeat(500));
        when(approvalMapper.queryById(APPROVAL_ID)).thenReturn(row);

        service.triggerCreateFlow(PUID, APPROVAL_ID);

        ArgumentCaptor<BridgeCreateRequest> reqCaptor = ArgumentCaptor.forClass(BridgeCreateRequest.class);
        verify(client).createFlow(any(), reqCaptor.capture());
        assertEquals(200, reqCaptor.getValue().getSummary().length());
    }

    @Test
    public void payload_usernameLookupFailure_degradesToUid() {
        when(userMapper.queryByUid(UID)).thenThrow(new RuntimeException("auth db down"));

        service.triggerCreateFlow(PUID, APPROVAL_ID);

        ArgumentCaptor<BridgeCreateRequest> reqCaptor = ArgumentCaptor.forClass(BridgeCreateRequest.class);
        verify(client).createFlow(any(), reqCaptor.capture());
        assertEquals(UID, reqCaptor.getValue().getApplicant().getName());
    }

    @Test
    public void timeoutKv_clampedIntoWhitelistRange() {
        when(systemDal.fetchSystemConf(RootUserConfig.Fields.dingBridgeTimeoutMs, Integer.class)).thenReturn(999_999);
        service.triggerCreateFlow(PUID, APPROVAL_ID);
        ArgumentCaptor<BridgeEndpoint> c1 = ArgumentCaptor.forClass(BridgeEndpoint.class);
        verify(client).createFlow(c1.capture(), any());
        assertEquals(60_000L, c1.getValue().getTimeoutMs());

        reset(client);
        when(client.createFlow(any(), any())).thenReturn(BridgeFlowResult.ok("f", false));
        when(systemDal.fetchSystemConf(RootUserConfig.Fields.dingBridgeTimeoutMs, Integer.class)).thenReturn(1);
        service.triggerCreateFlow(PUID, APPROVAL_ID);
        ArgumentCaptor<BridgeEndpoint> c2 = ArgumentCaptor.forClass(BridgeEndpoint.class);
        verify(client).createFlow(c2.capture(), any());
        assertEquals(1_000L, c2.getValue().getTimeoutMs());
    }

    // ==================== success write-back (boundary #4/#6) ====================

    @Test
    public void success_marksStarted_andBackfillsFlowIdWhenEmpty() {
        when(approvalMapper.updateOutboundFlowIdIfEmpty(APPROVAL_ID, "flow-001")).thenReturn(1);

        service.triggerCreateFlow(PUID, APPROVAL_ID);

        verify(approvalMapper).markOutboundStarted(APPROVAL_ID);
        verify(approvalMapper).updateOutboundFlowIdIfEmpty(APPROVAL_ID, "flow-001");
        verify(approvalMapper, never()).markOutboundFailed(anyLong(), any(), anyInt(), any());
        verifyNoInteractions(alertPushService);
    }

    @Test
    public void success_edge6_identityAlreadySetByInbound_backfillSkippedStartedStands() {
        // the inbound command won the race — the "only when empty" guard rejects the backfill
        when(approvalMapper.updateOutboundFlowIdIfEmpty(APPROVAL_ID, "flow-001")).thenReturn(0);

        service.triggerCreateFlow(PUID, APPROVAL_ID);

        verify(approvalMapper).markOutboundStarted(APPROVAL_ID); // STARTED lands regardless
        verify(approvalMapper).updateOutboundFlowIdIfEmpty(APPROVAL_ID, "flow-001");
    }

    @Test
    public void success_blankFlowId_noIdentityWrite() {
        when(client.createFlow(any(), any())).thenReturn(BridgeFlowResult.ok(null, false));

        service.triggerCreateFlow(PUID, APPROVAL_ID);

        verify(approvalMapper).markOutboundStarted(APPROVAL_ID);
        verify(approvalMapper, never()).updateOutboundFlowIdIfEmpty(anyLong(), any());
    }

    // ==================== failure classification + single-shot alert ====================

    @Test
    public void firstFailure5xx_writesFailed_retry0_next1m_alertOnceNotFinal() {
        when(client.createFlow(any(), any())).thenReturn(BridgeFlowResult.failure("HTTP 503: bridge down", true));
        long before = System.currentTimeMillis();

        service.triggerCreateFlow(PUID, APPROVAL_ID);

        ArgumentCaptor<Date> nextCaptor = ArgumentCaptor.forClass(Date.class);
        verify(approvalMapper).markOutboundFailed(eq(APPROVAL_ID), eq("HTTP 503: bridge down"), eq(0), nextCaptor.capture());
        assertNotNull(nextCaptor.getValue());
        long delta = nextCaptor.getValue().getTime() - before;
        assertTrue("next retry ≈ now+1m, was +" + delta + "ms", delta >= 59_000 && delta <= 61_000);

        ArgumentCaptor<AlertContext> ctxCaptor = ArgumentCaptor.forClass(AlertContext.class);
        verify(alertPushService, times(1)).pushAlert(eq(PUID), eq(AlertScene.DING_BRIDGE_OUTBOUND_FAILED), ctxCaptor.capture());
        AlertContext ctx = ctxCaptor.getValue();
        assertEquals(AlertContext.BRIDGE_OP_CREATE, ctx.getBridgeOp());
        assertEquals("TICKET", ctx.getBridgeBizType());
        assertEquals("biz-900", ctx.getBridgeBizNo());
        assertFalse("first failure schedules auto retry — not the final copy", ctx.isBridgeFinal());
        assertEquals(APPROVAL_ID, ctx.getTicketId());
    }

    @Test
    public void firstFailure4xx_nonRetryable_nextNull_alertOnceFinal() {
        when(client.createFlow(any(), any())).thenReturn(BridgeFlowResult.failure("HTTP 400: bad contract", false));

        service.triggerCreateFlow(PUID, APPROVAL_ID);

        verify(approvalMapper).markOutboundFailed(eq(APPROVAL_ID), eq("HTTP 400: bad contract"), eq(0), isNull());
        ArgumentCaptor<AlertContext> ctxCaptor = ArgumentCaptor.forClass(AlertContext.class);
        verify(alertPushService, times(1)).pushAlert(eq(PUID), eq(AlertScene.DING_BRIDGE_OUTBOUND_FAILED), ctxCaptor.capture());
        assertTrue("non-retryable = final copy", ctxCaptor.getValue().isBridgeFinal());
    }

    @Test
    public void errorText_truncatedTo500() {
        when(client.createFlow(any(), any())).thenReturn(BridgeFlowResult.failure("E".repeat(900), true));

        service.triggerCreateFlow(PUID, APPROVAL_ID);

        ArgumentCaptor<String> errCaptor = ArgumentCaptor.forClass(String.class);
        verify(approvalMapper).markOutboundFailed(eq(APPROVAL_ID), errCaptor.capture(), eq(0), any());
        assertEquals(500, errCaptor.getValue().length());
    }

    @Test
    public void claimLost_noClientCall_idempotentSkip() {
        when(approvalMapper.claimOutboundFirstSend(APPROVAL_ID)).thenReturn(0);

        service.triggerCreateFlow(PUID, APPROVAL_ID);

        verifyNoInteractions(client);
        verify(approvalMapper, never()).markOutboundStarted(anyLong());
        verify(approvalMapper, never()).markOutboundFailed(anyLong(), any(), anyInt(), any());
    }

    @Test
    public void writeBackCrash_neverThrowsToCaller() {
        when(approvalMapper.markOutboundStarted(APPROVAL_ID)).thenThrow(new RuntimeException("db down"));

        service.triggerCreateFlow(PUID, APPROVAL_ID); // must not throw (afterCommit context discipline)

        verifyNoInteractions(alertPushService); // the crash-path best-effort write also failed silently
    }

    // ==================== client classification matrix (toFlowResult, zero network) ====================

    @Test
    public void toFlowResult_matrix() {
        BridgeFlowResult ok = DingBridgeClient.toFlowResult(200, "{\"flowId\":\"f-1\",\"duplicated\":true}", false);
        assertTrue(ok.isOk());
        assertEquals("f-1", ok.getFlowId());
        assertTrue(ok.isDuplicated());

        BridgeFlowResult malformed = DingBridgeClient.toFlowResult(200, "not-json{{", false);
        assertFalse(malformed.isOk());
        assertFalse("2xx with unparsable body = contract error, NOT retryable", malformed.isRetryable());

        BridgeFlowResult badRequest = DingBridgeClient.toFlowResult(400, "{\"msg\":\"bad\"}", false);
        assertFalse(badRequest.isOk());
        assertFalse("4xx = contract error, NOT retryable", badRequest.isRetryable());
        assertTrue(badRequest.getError().contains("400"));

        BridgeFlowResult serverError = DingBridgeClient.toFlowResult(503, "unavailable", false);
        assertFalse(serverError.isOk());
        assertTrue("5xx = retryable", serverError.isRetryable());

        BridgeFlowResult cancelNotFound = DingBridgeClient.toFlowResult(404, "", true);
        assertTrue("cancel 404 = idempotent success (design §2.2)", cancelNotFound.isOk());

        BridgeFlowResult createNotFound = DingBridgeClient.toFlowResult(404, "", false);
        assertFalse("create 404 = contract error", createNotFound.isOk());
        assertFalse(createNotFound.isRetryable());
    }

    @Test
    public void joinUrl_trailingSlashTolerant() {
        assertEquals("http://h:1/flows/create", DingBridgeClient.joinUrl("http://h:1", "/flows/create"));
        assertEquals("http://h:1/flows/create", DingBridgeClient.joinUrl("http://h:1/", "/flows/create"));
    }

    // ==================== helpers ====================

    private DmApprovalDO externalTicket() {
        DmApprovalDO row = new DmApprovalDO();
        row.setId(APPROVAL_ID);
        row.setBizId("biz-900");
        row.setOwnerUid(UID);
        row.setPrimaryUid(PUID);
        row.setApproType(ApprovalType.External);
        row.setApproBiz(ApprovalBiz.DM_CHANGE);
        row.setTicketStatus(ApprovalStatus.WAIT_APPROVAL);
        row.setTicketTitle("orders 表新增列");
        row.setRawSql("ALTER TABLE orders ADD COLUMN note TEXT;");
        ApprovalMO mo = new ApprovalMO();
        mo.setTicketType("PROD_DML");
        mo.setApprovalMode("DIRECT");
        row.setTicketInfo(JsonUtils.toJson(mo));
        return row;
    }
}
