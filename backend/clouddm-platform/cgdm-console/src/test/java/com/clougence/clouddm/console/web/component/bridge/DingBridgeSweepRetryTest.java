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

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
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
import com.clougence.utils.JsonUtils;

/**
 * Task 10-09 Step 3 (design §4, test matrix row 3): the compensation machinery — sweep
 * backoff sequence (1m/5m/15m slots indexed by the retry count), the MAX_AUTO_RETRY cap
 * (silent stop + ONE terminal alert), row-level CAS claims (multi-console double-send
 * protection), per-row failure isolation, kill-switch no-op, and the manual reissue guard
 * matrix (STARTED/STARTING reject, FAILED/NOT_STARTED allowed with retry_count reset,
 * tenant + channel + switch guards, synchronous failure propagation). Zero real network.
 */
public class DingBridgeSweepRetryTest {

    private DingBridgeOutboundService service;
    private SystemDal                 systemDal;
    private DmApprovalMapper          approvalMapper;
    private DingBridgeClient          client;
    private AlertPushService          alertPushService;

    private static final String PUID        = "puid-001";
    private static final String UID         = "uid-op";
    private static final long   APPROVAL_ID = 900L;

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
    }

    // ==================== sweep gates ====================

    @Test
    public void sweep_switchOff_noScanAtAll() {
        when(systemDal.fetchSystemConf(RootUserConfig.Fields.dingBridgeOutboundEnable, Boolean.class))
            .thenReturn(Boolean.FALSE);

        assertEquals(0, service.retryFailedOutbounds());

        verify(approvalMapper, never()).listOutboundRetryDue(anyInt(), anyInt());
        verifyNoInteractions(client);
    }

    @Test
    public void sweep_scanUsesCapAndLimit() {
        when(approvalMapper.listOutboundRetryDue(anyInt(), anyInt())).thenReturn(List.of());

        service.retryFailedOutbounds();

        verify(approvalMapper).listOutboundRetryDue(
                eq(DingBridgeOutboundService.MAX_AUTO_RETRY), eq(DingBridgeOutboundService.SWEEP_BATCH_LIMIT));
    }

    // ==================== backoff sequence + CAS ====================

    @Test
    public void sweep_retry0Failure_writesCount1_next5m_silent() {
        stubDueFailedRow(0);
        when(approvalMapper.claimOutboundForRetry(APPROVAL_ID, 0)).thenReturn(1);
        when(client.createFlow(any(), any())).thenReturn(BridgeFlowResult.failure("HTTP 503", true));
        long before = System.currentTimeMillis();

        assertEquals(1, service.retryFailedOutbounds());

        ArgumentCaptor<Date> next = ArgumentCaptor.forClass(Date.class);
        verify(approvalMapper).markOutboundFailed(eq(APPROVAL_ID), eq("HTTP 503"), eq(1), next.capture());
        long delta = next.getValue().getTime() - before;
        assertTrue("retry#2 slot ≈ now+5m, was +" + delta + "ms", delta >= 299_000 && delta <= 301_000);
        // mid-sequence retry failure = silent (anti-bombardment, design §4)
        verifyNoInteractions(alertPushService);
    }

    @Test
    public void sweep_retry1Failure_writesCount2_next15m_silent() {
        stubDueFailedRow(1);
        when(approvalMapper.claimOutboundForRetry(APPROVAL_ID, 1)).thenReturn(1);
        when(client.createFlow(any(), any())).thenReturn(BridgeFlowResult.failure("HTTP 500", true));
        long before = System.currentTimeMillis();

        service.retryFailedOutbounds();

        ArgumentCaptor<Date> next = ArgumentCaptor.forClass(Date.class);
        verify(approvalMapper).markOutboundFailed(eq(APPROVAL_ID), eq("HTTP 500"), eq(2), next.capture());
        long delta = next.getValue().getTime() - before;
        assertTrue("retry#3 slot ≈ now+15m, was +" + delta + "ms", delta >= 899_000 && delta <= 901_000);
        verifyNoInteractions(alertPushService);
    }

    @Test
    public void sweep_retry2Failure_hitsCap_nextNull_oneTerminalFinalAlert() {
        stubDueFailedRow(2);
        when(approvalMapper.claimOutboundForRetry(APPROVAL_ID, 2)).thenReturn(1);
        when(client.createFlow(any(), any())).thenReturn(BridgeFlowResult.failure("HTTP 503", true));

        service.retryFailedOutbounds();

        verify(approvalMapper).markOutboundFailed(eq(APPROVAL_ID), eq("HTTP 503"), eq(3), isNull());
        ArgumentCaptor<AlertContext> ctx = ArgumentCaptor.forClass(AlertContext.class);
        verify(alertPushService, times(1)).pushAlert(eq(PUID), eq(AlertScene.DING_BRIDGE_OUTBOUND_FAILED), ctx.capture());
        assertTrue("cap reached = the one terminal alert, final copy", ctx.getValue().isBridgeFinal());
    }

    @Test
    public void sweep_nonRetryableDuringRetry_nextNull_terminalAlert() {
        stubDueFailedRow(0);
        when(approvalMapper.claimOutboundForRetry(APPROVAL_ID, 0)).thenReturn(1);
        when(client.createFlow(any(), any())).thenReturn(BridgeFlowResult.failure("HTTP 400: contract", false));

        service.retryFailedOutbounds();

        verify(approvalMapper).markOutboundFailed(eq(APPROVAL_ID), eq("HTTP 400: contract"), eq(1), isNull());
        ArgumentCaptor<AlertContext> ctx = ArgumentCaptor.forClass(AlertContext.class);
        verify(alertPushService, times(1)).pushAlert(eq(PUID), eq(AlertScene.DING_BRIDGE_OUTBOUND_FAILED), ctx.capture());
        assertTrue(ctx.getValue().isBridgeFinal());
    }

    @Test
    public void sweep_claimLost_noClientCall_multiConsoleSafe() {
        stubDueFailedRow(0);
        when(approvalMapper.claimOutboundForRetry(APPROVAL_ID, 0)).thenReturn(0);

        service.retryFailedOutbounds();

        verifyNoInteractions(client);
        verify(approvalMapper, never()).markOutboundFailed(anyLong(), any(), anyInt(), any());
    }

    @Test
    public void sweep_rowNoLongerFailed_skipped() {
        when(approvalMapper.listOutboundRetryDue(anyInt(), anyInt())).thenReturn(List.of(APPROVAL_ID));
        DmApprovalDO row = externalRow();
        row.setOutboundStatus(DingBridgeOutboundService.STATUS_STARTED); // raced to STARTED since the scan
        when(approvalMapper.queryById(APPROVAL_ID)).thenReturn(row);

        service.retryFailedOutbounds();

        verify(approvalMapper, never()).claimOutboundForRetry(anyLong(), anyInt());
        verifyNoInteractions(client);
    }

    @Test
    public void sweep_rowAtCap_skippedSilently() {
        when(approvalMapper.listOutboundRetryDue(anyInt(), anyInt())).thenReturn(List.of(APPROVAL_ID));
        DmApprovalDO row = externalRow();
        row.setOutboundStatus(DingBridgeOutboundService.STATUS_FAILED);
        row.setOutboundRetryCount(3);
        when(approvalMapper.queryById(APPROVAL_ID)).thenReturn(row);

        service.retryFailedOutbounds();

        verify(approvalMapper, never()).claimOutboundForRetry(anyLong(), anyInt());
        verifyNoInteractions(alertPushService); // 上限后静默(终态告警已在达标那一刻发过)
    }

    @Test
    public void sweep_success_marksStartedAndBackfills() {
        stubDueFailedRow(1);
        when(approvalMapper.claimOutboundForRetry(APPROVAL_ID, 1)).thenReturn(1);
        when(client.createFlow(any(), any())).thenReturn(BridgeFlowResult.ok("flow-77", false));

        service.retryFailedOutbounds();

        verify(approvalMapper).markOutboundStarted(APPROVAL_ID);
        verify(approvalMapper).updateOutboundFlowIdIfEmpty(APPROVAL_ID, "flow-77");
        verify(approvalMapper, never()).markOutboundFailed(anyLong(), any(), anyInt(), any());
    }

    @Test
    public void sweep_perRowCrash_isolatedFromNextRow() {
        when(approvalMapper.listOutboundRetryDue(anyInt(), anyInt())).thenReturn(List.of(901L, 902L));
        when(approvalMapper.queryById(901L)).thenThrow(new RuntimeException("db hiccup"));
        DmApprovalDO row2 = externalRow();
        row2.setId(902L);
        row2.setOutboundStatus(DingBridgeOutboundService.STATUS_FAILED);
        row2.setOutboundRetryCount(0);
        when(approvalMapper.queryById(902L)).thenReturn(row2);
        when(approvalMapper.claimOutboundForRetry(902L, 0)).thenReturn(1);
        when(client.createFlow(any(), any())).thenReturn(BridgeFlowResult.ok("f", false));

        assertEquals("the crashing row is isolated, the second one still runs", 1, service.retryFailedOutbounds());
        verify(approvalMapper).markOutboundStarted(902L);
    }

    /**
     * Terminal-row quiesce (check fix 2026-10-09 — EXEC_FAIL idle-spin lesson): a FAILED row whose
     * ticket went terminal (reject/cancel/close landed while the outbound was down) can never make
     * progress — the send guard refuses re-initiation, so without quiescing it would be re-scanned
     * by EVERY 30s sweep round forever (scan churn + log noise). Quiesce = keep FAILED + the retry
     * count, clear next_retry_at → permanently out of the listOutboundRetryDue scan set, NO alert
     * (the ticket is terminal; the actionable FAILED alert already fired).
     */
    @Test
    public void sweep_terminalTicketFailedRow_quiescedOutOfScanSet_noClientNoAlert() {
        DmApprovalDO row = externalRow();
        row.setTicketStatus(ApprovalStatus.CANCELED); // terminal — the reject/cancel arrived mid-retry-window
        row.setOutboundStatus(DingBridgeOutboundService.STATUS_FAILED);
        row.setOutboundRetryCount(1);
        when(approvalMapper.listOutboundRetryDue(anyInt(), anyInt())).thenReturn(List.of(APPROVAL_ID));
        when(approvalMapper.queryById(APPROVAL_ID)).thenReturn(row);

        service.retryFailedOutbounds();

        verify(approvalMapper, never()).claimOutboundForRetry(anyLong(), anyInt());
        verifyNoInteractions(client);
        verifyNoInteractions(alertPushService);
        ArgumentCaptor<String> err = ArgumentCaptor.forClass(String.class);
        verify(approvalMapper).markOutboundFailed(eq(APPROVAL_ID), err.capture(), eq(1), isNull());
        assertTrue("quiesce cause names the terminal state: " + err.getValue(),
                err.getValue().contains("terminal") && err.getValue().contains("CANCELED"));
    }

    /** a NOT_STARTED/NULL row of a terminal ticket is never scanned — quiesce must NOT write FAILED onto it. */
    @Test
    public void terminalGuard_nullOutboundStatus_noFailedWrite() {
        DmApprovalDO row = externalRow();
        row.setTicketStatus(ApprovalStatus.REJECTED); // outbound_status NULL
        when(approvalMapper.queryById(APPROVAL_ID)).thenReturn(row);

        service.triggerCreateFlow(PUID, APPROVAL_ID); // no tx → immediate defensive branch

        verify(approvalMapper, never()).claimOutboundFirstSend(anyLong());
        verify(approvalMapper, never()).markOutboundFailed(anyLong(), any(), anyInt(), any());
        verifyNoInteractions(client);
    }

    // ==================== scheduler dispatch (check fix: the sweep must never occupy the dispatcher) ====================

    /**
     * A sweep round can carry up to SWEEP_BATCH_LIMIT bridge HTTP calls — on a slow/down bridge
     * (exactly when retries accrue) an INLINE round would stall the single TicketTask-Dispatcher
     * thread for minutes (design §12 red line: outbound must never hang the main chain). Pin:
     * due tick → exactly ONE worker-pool submission + zero inline sweep; 30s throttle; in-flight
     * guard caps occupancy at one round; the submitted runnable runs the sweep and releases the
     * guard; the next due window submits again.
     */
    @Test
    public void scheduler_sweepRunsOnWorkerPool_neverInlineOnDispatcher() {
        com.clougence.clouddm.console.web.component.approval.schedule.ApprovalTaskScheduler scheduler =
            new com.clougence.clouddm.console.web.component.approval.schedule.ApprovalTaskScheduler();
        com.clougence.clouddm.console.web.component.approval.schedule.ApprovalTaskProcessor processor =
            mock(com.clougence.clouddm.console.web.component.approval.schedule.ApprovalTaskProcessor.class);
        ApprovalDal schedulerDal = mock(ApprovalDal.class);
        DmApprovalMapper schedulerMapper = mock(DmApprovalMapper.class);
        when(schedulerDal.approvalMapper()).thenReturn(schedulerMapper);
        // non-empty list → skips the empty-list 5s sleep branch; trySchedule no-ops (null taskInQueueSet)
        when(schedulerMapper.listUnFinishTicketIdList()).thenReturn(List.of(1L));
        java.util.concurrent.ThreadPoolExecutor executor = mock(java.util.concurrent.ThreadPoolExecutor.class);
        ReflectionTestUtils.setField(scheduler, "taskProcessor", processor);
        ReflectionTestUtils.setField(scheduler, "approvalDal", schedulerDal);
        ReflectionTestUtils.setField(scheduler, "threadPoolExecutor", executor);

        // due tick #1 → one worker-pool submission, ZERO inline sweep on the dispatcher thread
        ReflectionTestUtils.invokeMethod(scheduler, "doSchedule");
        verify(processor, never()).sweepOutboundRetry();
        ArgumentCaptor<Runnable> round = ArgumentCaptor.forClass(Runnable.class);
        verify(executor, times(1)).execute(round.capture());

        // tick #2 right after → throttled (30s window not elapsed), no second submission
        ReflectionTestUtils.invokeMethod(scheduler, "doSchedule");
        verify(executor, times(1)).execute(any(Runnable.class));

        // tick #3 with an elapsed window but the round STILL in flight → skipped (occupancy cap 1)
        ReflectionTestUtils.setField(scheduler, "lastOutboundSweepAt", 0L);
        ReflectionTestUtils.invokeMethod(scheduler, "doSchedule");
        verify(executor, times(1)).execute(any(Runnable.class));

        // the submitted round runs the sweep off-dispatcher and releases the in-flight guard
        round.getValue().run();
        verify(processor, times(1)).sweepOutboundRetry();

        // tick #4 with an elapsed window → submits again
        ReflectionTestUtils.setField(scheduler, "lastOutboundSweepAt", 0L);
        ReflectionTestUtils.invokeMethod(scheduler, "doSchedule");
        verify(executor, times(2)).execute(any(Runnable.class));
    }

    // ==================== manual reissue guard matrix (design §4) ====================

    @Test
    public void reissue_switchOff_throwsDisabled() {
        when(systemDal.fetchSystemConf(RootUserConfig.Fields.dingBridgeOutboundEnable, Boolean.class))
            .thenReturn(Boolean.FALSE);

        assertReissueThrows();
        verifyNoInteractions(client);
    }

    @Test
    public void reissue_baseUrlBlank_throwsDisabled() {
        when(systemDal.fetchSystemConf(RootUserConfig.Fields.dingBridgeBaseUrl, String.class)).thenReturn("");

        assertReissueThrows();
        verifyNoInteractions(client);
    }

    @Test
    public void reissue_missingOrCrossTenant_throwsNotFound() {
        when(approvalMapper.queryById(APPROVAL_ID)).thenReturn(null);
        assertReissueThrows();

        DmApprovalDO otherTenant = externalRow();
        otherTenant.setPrimaryUid("other-puid");
        when(approvalMapper.queryById(APPROVAL_ID)).thenReturn(otherTenant);
        assertReissueThrows();
        verifyNoInteractions(client);
    }

    @Test
    public void reissue_nonExternal_throwsChannelMismatch() {
        DmApprovalDO internal = externalRow();
        internal.setApproType(ApprovalType.Internal);
        when(approvalMapper.queryById(APPROVAL_ID)).thenReturn(internal);

        assertReissueThrows();
        verifyNoInteractions(client);
    }

    @Test
    public void reissue_started_throwsAlreadyStarted_doubleSendGuard() {
        DmApprovalDO started = externalRow();
        started.setOutboundStatus(DingBridgeOutboundService.STATUS_STARTED);
        when(approvalMapper.queryById(APPROVAL_ID)).thenReturn(started);

        assertReissueThrows();
        verify(approvalMapper, never()).claimOutboundForReissue(anyLong());
        verifyNoInteractions(client);
    }

    @Test
    public void reissue_starting_throwsInFlight() {
        DmApprovalDO starting = externalRow();
        starting.setOutboundStatus(DingBridgeOutboundService.STATUS_STARTING);
        when(approvalMapper.queryById(APPROVAL_ID)).thenReturn(starting);

        assertReissueThrows();
        verifyNoInteractions(client);
    }

    @Test
    public void reissue_fromFailed_success_claimsWithReset_startedStands() {
        DmApprovalDO failed = externalRow();
        failed.setOutboundStatus(DingBridgeOutboundService.STATUS_FAILED);
        failed.setOutboundRetryCount(2);
        failed.setOutboundError("old cause");
        DmApprovalDO started = externalRow();
        started.setOutboundStatus(DingBridgeOutboundService.STATUS_STARTED);
        // reads: guard → attempt-fresh → final outcome
        when(approvalMapper.queryById(APPROVAL_ID)).thenReturn(failed, failed, started);
        when(approvalMapper.claimOutboundForReissue(APPROVAL_ID)).thenReturn(1);
        when(client.createFlow(any(), any())).thenReturn(BridgeFlowResult.ok("flow-9", false));

        service.reissueFlow(PUID, UID, APPROVAL_ID); // must not throw

        verify(approvalMapper).claimOutboundForReissue(APPROVAL_ID); // retry_count reset lives in the CAS SQL
        verify(approvalMapper).markOutboundStarted(APPROVAL_ID);
        verify(approvalMapper).updateOutboundFlowIdIfEmpty(APPROVAL_ID, "flow-9");
        // reissue failure of a previously-FAILED row does not re-fire the alert (user sees the
        // synchronous outcome); success fires none either
        verifyNoInteractions(alertPushService);
    }

    @Test
    public void reissue_fromNotStarted_allowed() {
        DmApprovalDO notStarted = externalRow(); // outbound_status NULL
        DmApprovalDO started = externalRow();
        started.setOutboundStatus(DingBridgeOutboundService.STATUS_STARTED);
        when(approvalMapper.queryById(APPROVAL_ID)).thenReturn(notStarted, notStarted, started);
        when(approvalMapper.claimOutboundForReissue(APPROVAL_ID)).thenReturn(1);
        when(client.createFlow(any(), any())).thenReturn(BridgeFlowResult.ok("flow-10", false));

        service.reissueFlow(PUID, UID, APPROVAL_ID);

        verify(approvalMapper).markOutboundStarted(APPROVAL_ID);
    }

    @Test
    public void reissue_failure_throwsWithPersistedCause_andAlertsFirstFailureFromNotStarted() {
        DmApprovalDO notStarted = externalRow();
        DmApprovalDO failedAfter = externalRow();
        failedAfter.setOutboundStatus(DingBridgeOutboundService.STATUS_FAILED);
        failedAfter.setOutboundError("HTTP 503: down");
        when(approvalMapper.queryById(APPROVAL_ID)).thenReturn(notStarted, notStarted, failedAfter);
        when(approvalMapper.claimOutboundForReissue(APPROVAL_ID)).thenReturn(1);
        when(client.createFlow(any(), any())).thenReturn(BridgeFlowResult.failure("HTTP 503: down", true));

        try {
            service.reissueFlow(PUID, UID, APPROVAL_ID);
            fail("Expected ErrorMessageException carrying the persisted cause");
        } catch (ErrorMessageException ex) {
            assertTrue("cause propagated: " + ex.getMessage(), ex.getMessage().contains("HTTP 503"));
        }
        // first transition to FAILED (previous status was NOT_STARTED) → one alert
        verify(alertPushService, times(1)).pushAlert(eq(PUID), eq(AlertScene.DING_BRIDGE_OUTBOUND_FAILED), any());
    }

    @Test
    public void reissue_claimLostConcurrently_throwsFailedOutcome() {
        DmApprovalDO failed = externalRow();
        failed.setOutboundStatus(DingBridgeOutboundService.STATUS_FAILED);
        when(approvalMapper.queryById(APPROVAL_ID)).thenReturn(failed);
        when(approvalMapper.claimOutboundForReissue(APPROVAL_ID)).thenReturn(0); // sweep won the race

        try {
            service.reissueFlow(PUID, UID, APPROVAL_ID);
            fail("Expected ErrorMessageException — status never reached STARTED");
        } catch (ErrorMessageException ex) {
            // ok
        }
        verifyNoInteractions(client);
    }

    // ==================== helpers ====================

    private void assertReissueThrows() {
        try {
            service.reissueFlow(PUID, UID, APPROVAL_ID);
            fail("Expected ErrorMessageException");
        } catch (ErrorMessageException ex) {
            // ok
        }
    }

    private void stubDueFailedRow(int retryCount) {
        when(approvalMapper.listOutboundRetryDue(anyInt(), anyInt())).thenReturn(List.of(APPROVAL_ID));
        DmApprovalDO row = externalRow();
        row.setOutboundStatus(DingBridgeOutboundService.STATUS_FAILED);
        row.setOutboundRetryCount(retryCount);
        row.setOutboundNextRetryAt(new Date(System.currentTimeMillis() - 1000));
        when(approvalMapper.queryById(APPROVAL_ID)).thenReturn(row);
    }

    private DmApprovalDO externalRow() {
        DmApprovalDO row = new DmApprovalDO();
        row.setId(APPROVAL_ID);
        row.setBizId("biz-900");
        row.setOwnerUid(UID);
        row.setPrimaryUid(PUID);
        row.setApproType(ApprovalType.External);
        row.setApproBiz(ApprovalBiz.DM_CHANGE);
        row.setTicketStatus(ApprovalStatus.WAIT_APPROVAL);
        row.setTicketTitle("t");
        row.setRawSql("UPDATE t SET a=1 WHERE id=1;");
        ApprovalMO mo = new ApprovalMO();
        mo.setTicketType("PROD_DML");
        row.setTicketInfo(JsonUtils.toJson(mo));
        return row;
    }
}
