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
package com.clougence.clouddm.console.web.component.alert;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.clougence.clouddm.console.web.component.cicd.ImSenderService;
import com.clougence.clouddm.console.web.component.config.RootUserConfig;
import com.clougence.clouddm.platform.dal.access.AuthDal;
import com.clougence.clouddm.platform.dal.access.SystemDal;
import com.clougence.clouddm.platform.dal.mapper.auth.DmAuthUserMapper;
import com.clougence.clouddm.platform.dal.model.auth.DmAuthUserDO;
import com.clougence.clouddm.platform.dal.model.system.DmSysMessengerDO;
import com.clougence.clouddm.sdk.messenger.MsgContent;
import com.clougence.clouddm.sdk.messenger.MsgSendResult;
import com.clougence.clouddm.sdk.messenger.MsgSendType;

/**
 * B6 M1 (Step 7c): AlertPushService — the scene-based alert channel (GovReturnNotifyService
 * template copy). Matrix: the alertPushEnable switch semantics (off → silent skip incl. the
 * trail signal; null/exception → fail-safe ON), the no-messenger silent degradation, send
 * exception isolation, the enable filter, the afterCommit transaction registration (vs the
 * immediate defensive branch), and the message copy assembly (applicant username / table /
 * stats / risk line / deep link). ALL sends are mocked — zero real network (红线 discipline).
 */
public class AlertPushServiceTest {

    private AlertPushService   service;
    private SystemDal          systemDal;
    private com.clougence.clouddm.platform.dal.mapper.system.DmSysMessengerMapper messengerMapper;
    private ImSenderService    imSenderService;
    private DmAuthUserMapper   userMapper;

    private static final String PUID       = "puid-001";
    private static final String APPLICANT  = "uid-applicant";
    private static final long   TICKET_ID  = 900L;

    @Before
    public void setUp() {
        service = new AlertPushService();
        systemDal = mock(SystemDal.class);
        messengerMapper = mock(com.clougence.clouddm.platform.dal.mapper.system.DmSysMessengerMapper.class);
        when(systemDal.messengerMapper()).thenReturn(messengerMapper);
        imSenderService = mock(ImSenderService.class);
        when(imSenderService.sendMessage(any(), any(), any())).thenReturn(MsgSendResult.success("msg-1", "ok"));
        AuthDal authDal = mock(AuthDal.class);
        userMapper = mock(DmAuthUserMapper.class);
        when(authDal.userMapper()).thenReturn(userMapper);
        DmAuthUserDO applicant = new DmAuthUserDO();
        applicant.setUid(APPLICANT);
        applicant.setUsername("zhang-san");
        when(userMapper.queryByUid(APPLICANT)).thenReturn(applicant);

        ReflectionTestUtils.setField(service, "systemDal", systemDal);
        ReflectionTestUtils.setField(service, "imSenderService", imSenderService);
        ReflectionTestUtils.setField(service, "authDal", authDal);

        // switch ON by default (null → fail-safe default true)
        when(systemDal.fetchSystemConf(eq(RootUserConfig.Fields.alertPushEnable), eq(Boolean.class)))
            .thenReturn(null);
        when(messengerMapper.queryMessengerByOwner(PUID)).thenReturn(List.of(messenger(true)));
    }

    @After
    public void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ==================== switch semantics ====================

    @Test
    public void switchOff_silentSkip_noMessengerQueryNoSend() {
        when(systemDal.fetchSystemConf(eq(RootUserConfig.Fields.alertPushEnable), eq(Boolean.class)))
            .thenReturn(Boolean.FALSE);

        assertFalse(service.pushAlert(PUID, AlertScene.GHOST_LARGE_TABLE_NO_PK, ctx(AlertContext.RISK_NO_UNIQUE_KEY)));

        verify(messengerMapper, never()).queryMessengerByOwner(any());
        verify(imSenderService, never()).sendMessage(any(), any(), any());
    }

    @Test
    public void switchUnset_failSafeOn_sends() {
        assertTrue(service.pushAlert(PUID, AlertScene.GHOST_LARGE_TABLE_NO_PK, ctx(AlertContext.RISK_NO_UNIQUE_KEY)));
        verify(imSenderService).sendMessage(eq(PUID), any(), any());
    }

    @Test
    public void switchReadFailure_failSafeOn_sends() {
        when(systemDal.fetchSystemConf(eq(RootUserConfig.Fields.alertPushEnable), eq(Boolean.class)))
            .thenThrow(new RuntimeException("kv store down"));

        assertTrue(service.pushAlert(PUID, AlertScene.GHOST_LARGE_TABLE_NO_PK, ctx(AlertContext.RISK_NO_UNIQUE_KEY)));
        verify(imSenderService).sendMessage(eq(PUID), any(), any());
    }

    // ==================== degradation (never-throw) ====================

    @Test
    public void noMessenger_silentSkip_returnsTrue() {
        when(messengerMapper.queryMessengerByOwner(PUID)).thenReturn(Collections.emptyList());

        // accepted for delivery (switch on) — the missing robot config degrades inside doSend
        assertTrue(service.pushAlert(PUID, AlertScene.GHOST_LARGE_TABLE_NO_PK, ctx(AlertContext.RISK_NO_UNIQUE_KEY)));
        verify(imSenderService, never()).sendMessage(any(), any(), any());
    }

    @Test
    public void messengerQueryFailure_neverThrows() {
        when(messengerMapper.queryMessengerByOwner(PUID)).thenThrow(new RuntimeException("db down"));

        assertTrue(service.pushAlert(PUID, AlertScene.GHOST_LARGE_TABLE_NO_PK, ctx(AlertContext.RISK_NO_UNIQUE_KEY)));
        verify(imSenderService, never()).sendMessage(any(), any(), any());
    }

    @Test
    public void sendException_isolatedAndContinuesNextMessenger() {
        DmSysMessengerDO first = messenger(true);
        first.setId(1L);
        DmSysMessengerDO second = messenger(true);
        second.setId(2L);
        when(messengerMapper.queryMessengerByOwner(PUID)).thenReturn(List.of(first, second));
        when(imSenderService.sendMessage(eq(PUID), any(), any()))
            .thenThrow(new RuntimeException("IM provider down"))
            .thenReturn(MsgSendResult.success("msg-2", "ok"));

        service.pushAlert(PUID, AlertScene.GHOST_LARGE_TABLE_NO_PK, ctx(AlertContext.RISK_NO_UNIQUE_KEY));

        verify(imSenderService, times(2)).sendMessage(eq(PUID), any(), any());
    }

    @Test
    public void disabledMessenger_skipped() {
        when(messengerMapper.queryMessengerByOwner(PUID))
            .thenReturn(List.of(messenger(false), messenger(true)));

        service.pushAlert(PUID, AlertScene.GHOST_LARGE_TABLE_NO_PK, ctx(AlertContext.RISK_NO_UNIQUE_KEY));

        verify(imSenderService, times(1)).sendMessage(eq(PUID), any(), any());
    }

    @Test
    public void nullSceneOrContext_neverThrows() {
        assertFalse(service.pushAlert(PUID, null, ctx(AlertContext.RISK_NO_UNIQUE_KEY)));
        assertFalse(service.pushAlert(PUID, AlertScene.GHOST_LARGE_TABLE_NO_PK, null));
        verify(imSenderService, never()).sendMessage(any(), any(), any());
    }

    // ==================== transaction safety ====================

    @Test
    public void insideTransaction_defersToSendAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertTrue(service.pushAlert(PUID, AlertScene.GHOST_LARGE_TABLE_NO_PK, ctx(AlertContext.RISK_NO_UNIQUE_KEY)));
            // nothing sent while the transaction is open
            verify(imSenderService, never()).sendMessage(any(), any(), any());

            List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
            assertEquals(1, syncs.size());
            syncs.forEach(TransactionSynchronization::afterCommit);
            verify(imSenderService).sendMessage(eq(PUID), any(), any());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    public void withoutTransaction_sendsImmediately_defensiveBranch() {
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive());

        service.pushAlert(PUID, AlertScene.GHOST_LARGE_TABLE_NO_PK, ctx(AlertContext.RISK_NO_UNIQUE_KEY));

        verify(imSenderService).sendMessage(eq(PUID), any(), any());
    }

    // ==================== message copy ====================

    @Test
    public void messageCopy_carriesApplicantTableStatsRiskAndDeepLink() {
        service.pushAlert(PUID, AlertScene.GHOST_LARGE_TABLE_NO_PK, ctx(AlertContext.RISK_NO_UNIQUE_KEY));

        MsgContent sent = captureSentMessage();
        assertEquals(MsgSendType.Text, sent.getType());
        assertNotNull(sent.getMessageId());
        String body = sent.getBody();
        assertTrue(body.contains("zhang-san"));         // applicant username resolved
        assertTrue(body.contains("pre_db.t_nopk"));      // db.table display form
        assertTrue(body.contains("20000000"));           // rows
        assertTrue(body.contains("2048"));               // totalBytes 2GB → 2048 MB
        assertTrue(body.contains("/ticket/900"));        // deep link
        // risk line = the no-PK copy (locale-robust: assert on the stable non-i18n fragment gh-ost)
        assertTrue(body.toLowerCase().contains("gh-ost"));
    }

    @Test
    public void messageCopy_tunnelRisk_usesTheTunnelCopy() {
        String noPkBody = sendAndCapture(AlertContext.RISK_NO_UNIQUE_KEY);
        String tunnelBody = sendAndCapture(AlertContext.RISK_TUNNEL);

        assertNotEquals(noPkBody, tunnelBody);
        assertTrue(tunnelBody.toLowerCase().contains("ssh") || tunnelBody.contains("SSH"));
    }

    @Test
    public void messageCopy_usernameLookupFailure_fallsBackToUid() {
        when(userMapper.queryByUid(APPLICANT)).thenThrow(new RuntimeException("auth db down"));

        service.pushAlert(PUID, AlertScene.GHOST_LARGE_TABLE_NO_PK, ctx(AlertContext.RISK_NO_UNIQUE_KEY));

        assertTrue(captureSentMessage().getBody().contains(APPLICANT));
    }

    @Test
    public void messageCopy_nullStats_renderPlaceholders() {
        AlertContext ctx = ctx(AlertContext.RISK_NO_UNIQUE_KEY);
        ctx.setTableRows(null);
        ctx.setTotalBytes(null);

        service.pushAlert(PUID, AlertScene.GHOST_LARGE_TABLE_NO_PK, ctx);

        assertTrue(captureSentMessage().getBody().contains("-"));
    }

    // ==================== helpers ====================

    private String sendAndCapture(String riskCode) {
        reset(imSenderService);
        when(imSenderService.sendMessage(any(), any(), any())).thenReturn(MsgSendResult.success("m", "ok"));
        service.pushAlert(PUID, AlertScene.GHOST_LARGE_TABLE_NO_PK, ctx(riskCode));
        return captureSentMessage().getBody();
    }

    private MsgContent captureSentMessage() {
        org.mockito.ArgumentCaptor<MsgContent> captor = org.mockito.ArgumentCaptor.forClass(MsgContent.class);
        verify(imSenderService, atLeastOnce()).sendMessage(eq(PUID), any(), captor.capture());
        return captor.getValue();
    }

    private static AlertContext ctx(String riskCode) {
        AlertContext ctx = new AlertContext();
        ctx.setTicketId(TICKET_ID);
        ctx.setApplicantUid(APPLICANT);
        ctx.setDbName("pre_db");
        ctx.setTableName("t_nopk");
        ctx.setTableRows(20_000_000L);
        ctx.setTotalBytes(2L * 1024 * 1024 * 1024);
        ctx.setRowThreshold(10_000_000L);
        ctx.setSizeThresholdBytes(1024L * 1024 * 1024);
        ctx.setRiskCode(riskCode);
        return ctx;
    }

    private static DmSysMessengerDO messenger(boolean enabled) {
        DmSysMessengerDO m = new DmSysMessengerDO();
        m.setId(1L);
        m.setOwnerUid(PUID);
        m.setWebhook("https://example.com/hook");
        m.setSecret("secret");
        m.setEnable(enabled);
        return m;
    }
}
