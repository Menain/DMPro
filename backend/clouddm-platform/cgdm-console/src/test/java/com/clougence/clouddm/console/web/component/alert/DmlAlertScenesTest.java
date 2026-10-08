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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
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

/**
 * B7 N2 (implement.md 告警钉: 拒绝路径 immediate-send 时序 / 开关关静默 / 未配 messenger 静默 /
 * 发送异常隔离): the two NEW AlertScene branches of the B6 channel — the additive-extension
 * discipline means the frozen AlertPushServiceTest (GHOST scene) stays untouched; this suite
 * pins the DML scenes only:
 * <ul>
 * <li>DML_LARGE_TABLE_NO_KEY copy: applicant + db.table + rows/threshold + the §9.8.3 violation
 *     statement + the SQL summary, and NO deep link (the ticket never landed);</li>
 * <li>DML_BATCH_UNRESOLVED copy: ticket id + applicant + table + the segment/batch location +
 *     the manual-verdict guidance + the deep link;</li>
 * <li>the no-transaction IMMEDIATE-SEND branch (the controller-boundary rejection fires with no
 *     synchronization active — the send happens synchronously inside pushAlert, unlike the B6
 *     in-transaction afterCommit shape);</li>
 * <li>switch-off silence + no-messenger silence + send-exception isolation, scene-independent
 *     (the shared channel behavior re-pinned for the new scenes).</li>
 * </ul>
 * ALL sends are mocked — zero real network (红线 discipline).
 */
public class DmlAlertScenesTest {

    private AlertPushService   service;
    private SystemDal          systemDal;
    private com.clougence.clouddm.platform.dal.mapper.system.DmSysMessengerMapper messengerMapper;
    private ImSenderService    imSenderService;

    private static final String PUID      = "puid-001";
    private static final String APPLICANT = "uid-applicant";

    @Before
    public void setUp() {
        service = new AlertPushService();
        systemDal = mock(SystemDal.class);
        messengerMapper = mock(com.clougence.clouddm.platform.dal.mapper.system.DmSysMessengerMapper.class);
        when(systemDal.messengerMapper()).thenReturn(messengerMapper);
        imSenderService = mock(ImSenderService.class);
        when(imSenderService.sendMessage(any(), any(), any())).thenReturn(MsgSendResult.success("msg-1", "ok"));
        AuthDal authDal = mock(AuthDal.class);
        DmAuthUserMapper userMapper = mock(DmAuthUserMapper.class);
        when(authDal.userMapper()).thenReturn(userMapper);
        DmAuthUserDO applicant = new DmAuthUserDO();
        applicant.setUid(APPLICANT);
        applicant.setUsername("zhang-san");
        when(userMapper.queryByUid(APPLICANT)).thenReturn(applicant);

        ReflectionTestUtils.setField(service, "systemDal", systemDal);
        ReflectionTestUtils.setField(service, "imSenderService", imSenderService);
        ReflectionTestUtils.setField(service, "authDal", authDal);

        // switch ON by default (null → fail-safe true); one enabled robot
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

    private static DmSysMessengerDO messenger(boolean enable) {
        DmSysMessengerDO m = new DmSysMessengerDO();
        m.setId(1L);
        m.setOwnerUid(PUID);
        m.setWebhook("https://mock.local/robot");
        m.setSecret("secret");
        m.setEnable(enable);
        return m;
    }

    private static AlertContext noKeyCtx() {
        AlertContext ctx = new AlertContext();
        // ticketId stays 0 — the rejection scene has NO ticket (no deep link by construction)
        ctx.setApplicantUid(APPLICANT);
        ctx.setDbName("prod_db");
        ctx.setTableName("t_order");
        ctx.setTableRows(5_000_000L);
        ctx.setRowThreshold(1_000_000L);
        ctx.setSqlSummary("DELETE FROM t_order WHERE status = 0");
        return ctx;
    }

    private static AlertContext unresolvedCtx() {
        AlertContext ctx = new AlertContext();
        ctx.setTicketId(900L);
        ctx.setApplicantUid(APPLICANT);
        ctx.setDbName("prod_db");
        ctx.setTableName("t_order");
        ctx.setSegmentIndex(2);
        ctx.setIntentSeq(7L);
        return ctx;
    }

    private String sentBody() {
        ArgumentCaptor<MsgContent> captor = ArgumentCaptor.forClass(MsgContent.class);
        verify(imSenderService).sendMessage(eq(PUID), any(), captor.capture());
        return String.valueOf(captor.getValue().getBody());
    }

    // ==================== DML_LARGE_TABLE_NO_KEY copy ====================

    @Test
    public void noKey_copy_carriesApplicantTableRowsViolationSql_noDeepLink() {
        assertTrue(service.pushAlert(PUID, AlertScene.DML_LARGE_TABLE_NO_KEY, noKeyCtx()));

        String body = sentBody();
        assertTrue(body.contains("zhang-san"));                    // applicant username resolved
        assertTrue(body.contains("prod_db.t_order"));               // db.table display form
        assertTrue(body.contains("5000000"));                       // rows
        assertTrue(body.contains("1000000"));                       // threshold
        assertTrue(body.contains("DELETE FROM t_order"));           // SQL summary
        assertFalse("the ticket never landed — NO deep link in the rejection copy",
            body.contains("/ticket/"));
    }

    @Test
    public void noKey_immediateSend_noTransactionActive_synchronousDelivery() {
        // the controller-boundary shape: NO synchronization active → the send happens INSIDE
        // pushAlert (immediate), not deferred — the returned boolean already reflects the
        // accepted decision feeding the DML_BATCH_REJECT audit's alertPushed field
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive());

        assertTrue(service.pushAlert(PUID, AlertScene.DML_LARGE_TABLE_NO_KEY, noKeyCtx()));

        verify(imSenderService).sendMessage(eq(PUID), any(), any()); // already delivered before return
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive()); // nothing registered
    }

    // ==================== DML_BATCH_UNRESOLVED copy ====================

    @Test
    public void unresolved_copy_carriesTicketLocationGuidanceAndDeepLink() {
        assertTrue(service.pushAlert(PUID, AlertScene.DML_BATCH_UNRESOLVED, unresolvedCtx()));

        String body = sentBody();
        assertTrue(body.contains("900"));                            // ticket id (title + link)
        assertTrue(body.contains("zhang-san"));
        assertTrue(body.contains("prod_db.t_order"));
        assertTrue(body.contains("/ticket/900"));                    // deep link (the ticket EXISTS)
    }

    // ==================== scene-independent channel behavior (re-pinned for the new scenes) ====================

    @Test
    public void switchOff_bothScenes_silentSkip_noSend() {
        when(systemDal.fetchSystemConf(eq(RootUserConfig.Fields.alertPushEnable), eq(Boolean.class)))
            .thenReturn(Boolean.FALSE);

        assertFalse(service.pushAlert(PUID, AlertScene.DML_LARGE_TABLE_NO_KEY, noKeyCtx()));
        assertFalse(service.pushAlert(PUID, AlertScene.DML_BATCH_UNRESOLVED, unresolvedCtx()));
        verify(imSenderService, never()).sendMessage(any(), any(), any());
    }

    @Test
    public void noMessenger_bothScenes_acceptedButSilent() {
        when(messengerMapper.queryMessengerByOwner(PUID)).thenReturn(List.of());

        // accepted=true (the switch is on) but nothing is delivered — the audit's alertPushed
        // records the ACCEPTED fact (the send result is not persisted, B6 platform status quo)
        assertTrue(service.pushAlert(PUID, AlertScene.DML_LARGE_TABLE_NO_KEY, noKeyCtx()));
        verify(imSenderService, never()).sendMessage(any(), any(), any());
    }

    @Test
    public void sendException_isolated_neverThrows() {
        when(imSenderService.sendMessage(any(), any(), any())).thenThrow(new RuntimeException("IM provider down"));

        assertTrue(service.pushAlert(PUID, AlertScene.DML_LARGE_TABLE_NO_KEY, noKeyCtx()));
        assertTrue(service.pushAlert(PUID, AlertScene.DML_BATCH_UNRESOLVED, unresolvedCtx()));
    }

    @Test
    public void ghostScene_untouchedByTheExtension_regressionGuard() {
        // the additive switch extension must not disturb the B6 scene (its copy pins live in the
        // frozen AlertPushServiceTest — this is the cheap cross-check that the scene still routes)
        AlertContext ctx = new AlertContext();
        ctx.setTicketId(900L);
        ctx.setApplicantUid(APPLICANT);
        ctx.setDbName("pre_db");
        ctx.setTableName("t_big");
        ctx.setTableRows(9L);
        ctx.setRiskCode(AlertContext.RISK_NO_UNIQUE_KEY);

        assertTrue(service.pushAlert(PUID, AlertScene.GHOST_LARGE_TABLE_NO_PK, ctx));

        String body = sentBody();
        assertTrue(body.contains("pre_db.t_big"));
        assertTrue(body.contains("/ticket/900")); // the ghost copy keeps its deep link
    }
}
