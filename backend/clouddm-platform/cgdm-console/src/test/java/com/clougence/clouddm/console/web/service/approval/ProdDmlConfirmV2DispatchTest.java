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
package com.clougence.clouddm.console.web.service.approval;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.api.console.autoexec.ErrorStrategy;
import com.clougence.clouddm.console.web.component.approval.ApprovalFlowService;
import com.clougence.clouddm.console.web.component.approval.ApprovalService;
import com.clougence.clouddm.console.web.component.approval.ApprovalStateService;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.component.approval.schedule.ApprovalTaskScheduler;
import com.clougence.clouddm.console.web.component.execute.AutoExecService;
import com.clougence.clouddm.console.web.component.governance.GuardConclusion;
import com.clougence.clouddm.console.web.component.governance.SchemaSnapshotService;
import com.clougence.clouddm.console.web.constants.DmConfirmActionType;
import com.clougence.clouddm.console.web.model.fo.ticket.DmAutoExecConfigFO;
import com.clougence.clouddm.console.web.model.fo.ticket.DmConfirmTicketFO;
import com.clougence.clouddm.console.web.service.governance.GovExecutionGuardService;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.AuthDal;
import com.clougence.clouddm.platform.dal.access.ExecutionDal;
import com.clougence.clouddm.platform.dal.access.ObjectCacheDao;
import com.clougence.clouddm.platform.dal.access.TicketDbStmtDal;
import com.clougence.clouddm.platform.dal.access.entry.DsCacheEntry;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.auth.DmAuthRoleMapper;
import com.clougence.clouddm.platform.dal.mapper.auth.DmAuthUserMapper;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoJobMapper;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoTaskMapper;
import com.clougence.clouddm.platform.dal.mapper.govticket.DmTicketDbStmtMapper;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalProcessStatus;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStage;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.auth.AccountType;
import com.clougence.clouddm.platform.dal.model.auth.DmAuthUserDO;
import com.clougence.clouddm.platform.dal.model.execution.AutoExecType;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;
import com.clougence.utils.JsonUtils;

/**
 * B7 N0 (裁决 #4 / B6 债务 #2): the PROD_DML human/SYSTEM confirm-execution chain migrated from
 * the legacy single job (createExecJob consuming dm_approval.rawSql = FIRST GROUP ONLY — the
 * "multi-group PROD_DML executes only its first group" defect) to the v2 per-group path
 * (createGroupJob + startJob per group, mirroring GovAutoAdvanceServiceImpl.systemAutoConfirmForV2).
 * <p>
 * Pins (implement.md Step N0):
 * <ul>
 *   <li>multi-group confirm → EVERY group dispatched (the defect fix itself), id-ASC order,
 *       per-group create→start, config projected from the confirm FO (transactional /
 *       errorStrategy / retry params / execType / execTime / languageTag / operator uid);</li>
 *   <li>single-group config projection parity with the pre-switch FO semantics;</li>
 *   <li>MANUAL_EXEC builds no executor (FINISHED at confirm — structural, pinned at the
 *       confirmTicket entry); SPECIFY_TIME execTime pass-through; transactional pass-through
 *       (NOT forced in N0 — no segments yet);</li>
 *   <li>strict failure path: any group exception → reverse-order best-effort
 *       deleteJobIfNotDispatched (RACE-FREE: only pre-dispatch PREPARING/INIT jobs are deleted;
 *       a scheduler-claimed job survives and its writeback owns the group state — deleting it
 *       would orphan the live sidecar execution and double-execute on re-confirm) +
 *       EXECUTING→PENDING group reset ONLY for real deletions (a stuck EXECUTING row would be
 *       skipped by the PENDING/FAILED filter on re-confirm) + restoreExecutionConfirmation —
 *       "失败即完整恢复"; the trailing markExecutionDispatched runs inside the same guarded
 *       region;</li>
 *   <li>three-entry equivalence: prepareExecJobAsSystemAsync (reached by the open command
 *       execute via confirmTicketBySystem) dispatches the identical shape with SYSTEM_OPERATOR;</li>
 *   <li>legacy tickets (no/unparsable ticketInfo.ticketType) and PRE_DDL keep the original
 *       createExecJob path byte-for-byte (never createGroupJob, updateAutoExecFlag kept at its
 *       original position, no explicit markExecutionDispatched — the legacy dispatchJob hook
 *       owns the step-times there);</li>
 *   <li>v2 branch does NOT set ticketInfo.autoExec (裁决 #20: the flag gates the LEGACY detail
 *       card which has no data for v2 tickets — the group section is the presentation, same as
 *       the PRE_DDL status quo) and DOES call markExecutionDispatched after the loop (:415
 *       step-times contract — v2 jobs carry no dependOnBizId, the legacy hook never fires);</li>
 *   <li>guard DENY still throws into the existing catch (touchpoint #2 contract, both copies).</li>
 * </ul>
 * Completion writeback needs no new pin here: the v2 group jobs land on the EXISTING frozen
 * handleV2JobCompletion discriminator branch (AutoExecV2JobCompletionTest, zero changes).
 * <p>
 * JUnit4 + handwritten mocks + ReflectionTestUtils (governance test discipline — no Spring
 * context / MockMvc / mockStatic; the DmTeamUtils static DAL field is set the same way
 * AutoExecSegmentAwareJobTest does it).
 */
public class ProdDmlConfirmV2DispatchTest {

    private ApprovalControlServiceImpl service;

    private ApprovalDal              approvalDal;
    private DmApprovalMapper         approvalMapper;
    private AuthDal                  authDal;
    private DmAuthUserMapper         userMapper;
    private TicketDbStmtDal          ticketDbStmtDal;
    private DmTicketDbStmtMapper     stmtMapper;
    private AutoExecService          autoExecService;
    private ApprovalStateService     approvalStateService;
    private ApprovalFlowService      approvalFlowService;
    private ApprovalService          approvalService;
    private GovExecutionGuardService govExecutionGuardService;
    private SchemaSnapshotService    schemaSnapshotService;
    private ApprovalTaskScheduler    approvalTaskScheduler;
    private ObjectCacheDao           objectCacheDao;

    private static final long   TICKET_ID      = 900L;
    private static final String BIZ_ID         = "ticket-biz-900";
    private static final String PUID           = "puid-001";
    private static final String UID            = "uid-001";
    private static final String PASSED_JOB_BIZ = "passed-job-biz";
    private static final long   BIND_DS_ID     = 20L;
    private static final Locale LOCALE         = Locale.forLanguageTag("zh-CN");

    private DmApprovalDO rdpTicket;
    private DmApprovalDO dmTicket;

    @Before
    public void setUp() {
        service = new ApprovalControlServiceImpl();

        // DmTeamUtils static executionDal (nextExecJobBizId generator) — AutoExecSegmentAwareJobTest recipe
        ExecutionDal staticExecDal = mock(ExecutionDal.class);
        DmExecAutoJobMapper staticJobMapper = mock(DmExecAutoJobMapper.class);
        DmExecAutoTaskMapper staticTaskMapper = mock(DmExecAutoTaskMapper.class);
        when(staticExecDal.autoJobMapper()).thenReturn(staticJobMapper);
        when(staticExecDal.autoTaskMapper()).thenReturn(staticTaskMapper);
        when(staticJobMapper.queryByBizId(anyString())).thenReturn(null);
        when(staticTaskMapper.queryByBizId(anyString())).thenReturn(null);
        ReflectionTestUtils.setField(com.clougence.clouddm.console.web.util.DmTeamUtils.class, "executionDal", staticExecDal);

        approvalDal = mock(ApprovalDal.class);
        approvalMapper = mock(DmApprovalMapper.class);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);
        authDal = mock(AuthDal.class);
        userMapper = mock(DmAuthUserMapper.class);
        DmAuthRoleMapper roleMapper = mock(DmAuthRoleMapper.class);
        when(authDal.userMapper()).thenReturn(userMapper);
        when(authDal.roleMapper()).thenReturn(roleMapper);
        ticketDbStmtDal = mock(TicketDbStmtDal.class);
        stmtMapper = mock(DmTicketDbStmtMapper.class);
        when(ticketDbStmtDal.stmtMapper()).thenReturn(stmtMapper);
        autoExecService = mock(AutoExecService.class);
        approvalStateService = mock(ApprovalStateService.class);
        approvalFlowService = mock(ApprovalFlowService.class);
        approvalService = mock(ApprovalService.class);
        govExecutionGuardService = mock(GovExecutionGuardService.class);
        schemaSnapshotService = mock(SchemaSnapshotService.class);
        approvalTaskScheduler = mock(ApprovalTaskScheduler.class);
        objectCacheDao = mock(ObjectCacheDao.class);

        ReflectionTestUtils.setField(service, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(service, "authDal", authDal);
        ReflectionTestUtils.setField(service, "ticketDbStmtDal", ticketDbStmtDal);
        ReflectionTestUtils.setField(service, "autoExecService", autoExecService);
        ReflectionTestUtils.setField(service, "approvalStateService", approvalStateService);
        ReflectionTestUtils.setField(service, "approvalFlowService", approvalFlowService);
        ReflectionTestUtils.setField(service, "approvalService", approvalService);
        ReflectionTestUtils.setField(service, "govExecutionGuardService", govExecutionGuardService);
        ReflectionTestUtils.setField(service, "schemaSnapshotService", schemaSnapshotService);
        ReflectionTestUtils.setField(service, "approvalTaskScheduler", approvalTaskScheduler);
        ReflectionTestUtils.setField(service, "objectCacheDao", objectCacheDao);
        ReflectionTestUtils.setField(service, "txManager",
            mock(org.springframework.transaction.PlatformTransactionManager.class));
        // legacy createExecJob dependencies (only exercised by the legacy-path pins)
        com.clougence.clouddm.console.web.component.dsconfig.DmDsConfigService dmDsConfigService
            = mock(com.clougence.clouddm.console.web.component.dsconfig.DmDsConfigService.class);
        com.clougence.clouddm.console.web.component.dsconfig.mode.DsLevels levels
            = mock(com.clougence.clouddm.console.web.component.dsconfig.mode.DsLevels.class);
        when(levels.dbLevels()).thenReturn(List.of("1", String.valueOf(BIND_DS_ID), "db"));
        when(dmDsConfigService.parseLevels(anyList())).thenReturn(levels);
        when(dmDsConfigService.fetchDsConfigFromExists(BIND_DS_ID))
            .thenReturn(new com.clougence.clouddm.base.metadata.ds.DataSourceConfig());
        ReflectionTestUtils.setField(service, "dmDsConfigService", dmDsConfigService);

        // primary-account operator → checkJobOperationEnable passes without role lookups
        DmAuthUserDO user = new DmAuthUserDO();
        user.setUid(UID);
        user.setUsername("operator");
        user.setAccountType(AccountType.PRIMARY_ACCOUNT);
        when(userMapper.queryByUid(UID)).thenReturn(user);

        // guard PASS by default
        when(govExecutionGuardService.checkByTicket(any(), any(), any())).thenReturn(GuardConclusion.pass());

        rdpTicket = ticket(ApprovalStatus.WAIT_EXEC);
        dmTicket = ticket(ApprovalStatus.WAIT_EXEC);
        dmTicket.setTicketInfo(v2TicketInfo("PROD_DML"));
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(rdpTicket);
        when(approvalMapper.queryByBizId(BIZ_ID)).thenReturn(dmTicket);
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(rdpTicket);
        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(new ArrayList<>());
    }

    // ======= the defect fix itself: multi-group → EVERY group dispatched =======

    @Test
    public void manualConfirm_multiGroupProdDml_everyGroupGetsV2Job_inIdOrder() {
        DmTicketDbStmtDO g1 = group(11L, "PENDING");
        DmTicketDbStmtDO g2 = group(12L, "PENDING");
        DmTicketDbStmtDO g3 = group(13L, "PENDING");
        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(g1, g2, g3));

        invokeManualPrepare(confirmFo(AutoExecType.IMMEDIATE, true, ErrorStrategy.NONE, 111L, 2L, null));

        // per-group createGroupJob with the FULL confirm-FO projection (extended overload)
        ArgumentCaptor<DmTicketDbStmtDO> groupCaptor = ArgumentCaptor.forClass(DmTicketDbStmtDO.class);
        ArgumentCaptor<String> createBizCaptor = ArgumentCaptor.forClass(String.class);
        verify(autoExecService, times(3)).createGroupJob(groupCaptor.capture(), createBizCaptor.capture(),
            eq(true), eq(ErrorStrategy.NONE), eq(111L), eq(2L),
            eq(AutoExecType.IMMEDIATE), isNull(), eq("zh-CN"), eq(UID));
        // id-ASC group order = the queryByTicketId order (positional contract with the flags)
        assertSame(g1, groupCaptor.getAllValues().get(0));
        assertSame(g2, groupCaptor.getAllValues().get(1));
        assertSame(g3, groupCaptor.getAllValues().get(2));

        // every created job is started, per-group create→start (mirror of the auto-advance loop)
        ArgumentCaptor<String> startBizCaptor = ArgumentCaptor.forClass(String.class);
        verify(autoExecService, times(3)).startJob(startBizCaptor.capture(), eq(UID));
        assertEquals(createBizCaptor.getAllValues(), startBizCaptor.getAllValues());
        InOrder inOrder = inOrder(autoExecService, schemaSnapshotService, approvalStateService);
        inOrder.verify(schemaSnapshotService).captureIfMissing(same(g1), eq(TICKET_ID));
        inOrder.verify(autoExecService).createGroupJob(same(g1), anyString(), anyBoolean(), any(), any(), any(), any(), any(), anyString(), anyString());
        inOrder.verify(autoExecService).startJob(anyString(), eq(UID));
        inOrder.verify(schemaSnapshotService).captureIfMissing(same(g2), eq(TICKET_ID));
        inOrder.verify(autoExecService).createGroupJob(same(g2), anyString(), anyBoolean(), any(), any(), any(), any(), any(), anyString(), anyString());
        inOrder.verify(autoExecService).startJob(anyString(), eq(UID));
        inOrder.verify(schemaSnapshotService).captureIfMissing(same(g3), eq(TICKET_ID));
        inOrder.verify(autoExecService).createGroupJob(same(g3), anyString(), anyBoolean(), any(), any(), any(), any(), any(), anyString(), anyString());
        inOrder.verify(autoExecService).startJob(anyString(), eq(UID));
        // step-times (:415): PREPARATION closes at the real dispatch moment — AFTER the loop
        inOrder.verify(approvalStateService).markExecutionDispatched(BIZ_ID);

        // the legacy single-job path is NOT taken, and the v2 branch never sets autoExec
        verify(approvalService, never()).consumeSqlFile(anyLong(), any());
        verify(approvalMapper, never()).updateTicketInfo(anyLong(), anyString());
        verify(autoExecService, never()).createGroupJob(any(), anyString(), anyBoolean(), any(), anyString(), anyString());
        verify(autoExecService, never()).deleteJob(anyString());
        verify(autoExecService, never()).deleteJobIfNotDispatched(anyString());
    }

    @Test
    public void manualConfirm_singleGroup_configProjectionBitForBit() {
        DmTicketDbStmtDO g1 = group(11L, "PENDING");
        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(g1));

        // dialog defaults: transactional=false, NONE, retryWaitTime=111s, retryCount=2
        invokeManualPrepare(confirmFo(AutoExecType.IMMEDIATE, false, ErrorStrategy.NONE, 111L, 2L, null));

        ArgumentCaptor<String> bizCaptor = ArgumentCaptor.forClass(String.class);
        verify(autoExecService).createGroupJob(same(g1), bizCaptor.capture(),
            eq(false), eq(ErrorStrategy.NONE), eq(111L), eq(2L),
            eq(AutoExecType.IMMEDIATE), isNull(), eq("zh-CN"), eq(UID));
        verify(autoExecService).startJob(bizCaptor.getValue(), UID);
        verify(approvalStateService).markExecutionDispatched(BIZ_ID);
        // the confirmTicket-provided jobBizId is deliberately unused in the v2 branch
        assertNotEquals(PASSED_JOB_BIZ, bizCaptor.getValue());
    }

    @Test
    public void manualConfirm_specifyTime_execTypeAndExecTimePassThrough() {
        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(group(11L, "PENDING")));
        long execTime = System.currentTimeMillis() + 3600_000L;

        invokeManualPrepare(confirmFo(AutoExecType.SPECIFY_TIME, false, ErrorStrategy.NONE, null, null, execTime));

        verify(autoExecService).createGroupJob(any(), anyString(), eq(false), eq(ErrorStrategy.NONE),
            isNull(), isNull(), eq(AutoExecType.SPECIFY_TIME), eq(execTime), eq("zh-CN"), eq(UID));
    }

    @Test
    public void manualConfirm_transactionalTrue_passThroughNotForced() {
        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(group(11L, "PENDING")));

        invokeManualPrepare(confirmFo(AutoExecType.IMMEDIATE, true, ErrorStrategy.NONE, null, null, null));

        // N0 has no segments — the user's transactional choice passes through unforced (the
        // batched-group forcing is N1+ scope, design §13)
        verify(autoExecService).createGroupJob(any(), anyString(), eq(true), eq(ErrorStrategy.NONE),
            isNull(), isNull(), eq(AutoExecType.IMMEDIATE), isNull(), eq("zh-CN"), eq(UID));
    }

    @Test
    public void manualConfirm_successAndExecutingGroupsSkipped_onlyPendingFailedDispatched() {
        DmTicketDbStmtDO done = group(11L, "SUCCESS");     // retained (resubmit contract)
        DmTicketDbStmtDO failed = group(12L, "FAILED");    // dispatchable (defensive parity)
        DmTicketDbStmtDO pending = group(13L, "PENDING");  // dispatchable
        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(done, failed, pending));

        invokeManualPrepare(confirmFo(AutoExecType.IMMEDIATE, false, ErrorStrategy.NONE, null, null, null));

        ArgumentCaptor<DmTicketDbStmtDO> captor = ArgumentCaptor.forClass(DmTicketDbStmtDO.class);
        verify(autoExecService, times(2)).createGroupJob(captor.capture(), anyString(), anyBoolean(), any(),
            any(), any(), any(), any(), anyString(), anyString());
        assertSame(failed, captor.getAllValues().get(0));
        assertSame(pending, captor.getAllValues().get(1));
        verify(schemaSnapshotService, never()).captureIfMissing(same(done), anyLong());
    }

    // ======= MANUAL_EXEC: no executor at all (structural gate at confirmTicket) =======

    @Test
    public void confirmTicket_manualExec_noControlTaskNoExecutor() {
        rdpTicket.setTicketStatus(ApprovalStatus.WAIT_CONFIRM);
        dmTicket.setTicketStatus(ApprovalStatus.WAIT_CONFIRM);
        DmConfirmTicketFO fo = confirmFo(AutoExecType.MANUAL_EXEC, false, ErrorStrategy.NONE, null, null, null);

        String jobBizId = service.confirmTicket(PUID, TICKET_ID, fo);

        // FINISHED at confirm — no control task, no job of either shape, terminal transition
        assertNull(jobBizId);
        verify(approvalTaskScheduler, never()).submitControlTask(anyLong(), any(Runnable.class));
        verifyNoInteractions(autoExecService);
        verify(approvalFlowService).transitionTicketToTerminal(eq(TICKET_ID), eq(ApprovalStatus.FINISHED), any());
    }

    // ======= strict failure path: 失败即完整恢复 =======

    @Test
    public void manualConfirm_group2CreateFails_group1CleanedUpAndConfirmationRestored() {
        DmTicketDbStmtDO g1 = group(11L, "PENDING");
        DmTicketDbStmtDO g2 = group(12L, "PENDING");
        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(g1, g2));
        when(autoExecService.deleteJobIfNotDispatched(anyString())).thenReturn(true);
        doThrow(new IllegalStateException("boom")).when(autoExecService).createGroupJob(
            same(g2), anyString(), anyBoolean(), any(), any(), any(), any(), any(), anyString(), anyString());

        invokeManualPrepare(confirmFo(AutoExecType.IMMEDIATE, false, ErrorStrategy.NONE, null, null, null));

        // g1's created job is deleted through the race-free guarded delete (capture the
        // generated bizId from the create call)
        ArgumentCaptor<String> createdBiz = ArgumentCaptor.forClass(String.class);
        verify(autoExecService).createGroupJob(same(g1), createdBiz.capture(), anyBoolean(), any(),
            any(), any(), any(), any(), anyString(), anyString());
        verify(autoExecService).deleteJobIfNotDispatched(createdBiz.getValue());
        verify(autoExecService, never()).deleteJob(createdBiz.getValue());
        // the outer catch also runs the legacy no-op deleteJob on the unused passed bizId
        verify(autoExecService).deleteJob(PASSED_JOB_BIZ);
        // g1's EXECUTING flip is reset (the guarded delete reported a real deletion) — a stuck
        // EXECUTING row would be skipped by the PENDING/FAILED filter on re-confirm, stranding
        // the ticket (Step 0 finding ⑤)
        verify(stmtMapper).updateExecStatus(11L, "PENDING", null);
        verify(stmtMapper, never()).updateExecStatus(eq(12L), anyString(), any());
        // restoreExecutionConfirmation ran (WAIT_CONFIRM + stage resets + progress reset)
        verify(approvalMapper).updateStatusByEnum(eq(TICKET_ID), eq(ApprovalStatus.WAIT_CONFIRM), anyString());
        verify(approvalStateService).updateProcessStatus(TICKET_ID, ApprovalStage.CONFIRM, ApprovalProcessStatus.INIT, null);
        verify(approvalStateService).updateProcessStatus(TICKET_ID, ApprovalStage.EXECUTION, ApprovalProcessStatus.INIT, null);
        verify(approvalStateService).resetExecutionProgress(TICKET_ID);
        // no dispatch mark, g1's startJob happened but nothing after the failure
        verify(approvalStateService, never()).markExecutionDispatched(anyString());
        verify(autoExecService, times(1)).startJob(anyString(), anyString());
    }

    @Test
    public void manualConfirm_group2StartFails_reverseOrderCleanupOfBothCreatedJobs() {
        DmTicketDbStmtDO g1 = group(11L, "PENDING");
        DmTicketDbStmtDO g2 = group(12L, "PENDING");
        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(g1, g2));
        when(autoExecService.deleteJobIfNotDispatched(anyString())).thenReturn(true);
        // first startJob (g1) succeeds, second (g2) fails
        doNothing().doThrow(new IllegalStateException("start failed"))
            .when(autoExecService).startJob(anyString(), eq(UID));

        invokeManualPrepare(confirmFo(AutoExecType.IMMEDIATE, false, ErrorStrategy.NONE, null, null, null));

        ArgumentCaptor<String> bizCaptor = ArgumentCaptor.forClass(String.class);
        verify(autoExecService, times(2)).createGroupJob(any(), bizCaptor.capture(),
            anyBoolean(), any(), any(), any(), any(), any(), anyString(), anyString());
        List<String> created = bizCaptor.getAllValues();
        assertEquals(2, created.size());
        // reverse-order teardown: g2's job first, then g1's (guarded pre-dispatch deletes)
        InOrder inOrder = inOrder(autoExecService);
        inOrder.verify(autoExecService).deleteJobIfNotDispatched(created.get(1));
        inOrder.verify(autoExecService).deleteJobIfNotDispatched(created.get(0));
        // both group rows reset (both deletes reported real deletions)
        verify(stmtMapper).updateExecStatus(11L, "PENDING", null);
        verify(stmtMapper).updateExecStatus(12L, "PENDING", null);
        verify(approvalMapper).updateStatusByEnum(eq(TICKET_ID), eq(ApprovalStatus.WAIT_CONFIRM), anyString());
        verify(approvalStateService, never()).markExecutionDispatched(anyString());
    }

    @Test
    public void manualConfirm_cleanupSkipsDispatchedJob_groupRowNotReset() {
        // Race-free cleanup contract: a job the scheduler already claimed (deleteJobIfNotDispatched
        // → false) is LEFT RUNNING and its group row is NOT reset — deleting a dispatched job
        // would orphan the live sidecar execution and double-execute the group on re-confirm;
        // the job's completion writeback owns the group state (SUCCESS-retained filter keeps a
        // re-confirm non-overlapping).
        DmTicketDbStmtDO g1 = group(11L, "PENDING");
        DmTicketDbStmtDO g2 = group(12L, "PENDING");
        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(g1, g2));
        ArgumentCaptor<String> createdBiz = ArgumentCaptor.forClass(String.class);
        doThrow(new IllegalStateException("boom")).when(autoExecService).createGroupJob(
            same(g2), anyString(), anyBoolean(), any(), any(), any(), any(), any(), anyString(), anyString());
        // g1's job was claimed by the scheduler before the cleanup ran
        when(autoExecService.deleteJobIfNotDispatched(anyString())).thenReturn(false);

        invokeManualPrepare(confirmFo(AutoExecType.IMMEDIATE, false, ErrorStrategy.NONE, null, null, null));

        verify(autoExecService).createGroupJob(same(g1), createdBiz.capture(), anyBoolean(), any(),
            any(), any(), any(), any(), anyString(), anyString());
        verify(autoExecService).deleteJobIfNotDispatched(createdBiz.getValue());
        // the surviving job's group keeps its EXECUTING flip — NO reset, NO unconditional delete
        verify(stmtMapper, never()).updateExecStatus(eq(11L), anyString(), any());
        verify(autoExecService, never()).deleteJob(createdBiz.getValue());
        // the ticket is still restored (the failure itself is surfaced; convergence rides the
        // surviving job's writeback + the re-confirm filter)
        verify(approvalMapper).updateStatusByEnum(eq(TICKET_ID), eq(ApprovalStatus.WAIT_CONFIRM), anyString());
    }

    @Test
    public void manualConfirm_dispatchMarkFails_createdJobsCleanedUpAndRestored() {
        // markExecutionDispatched runs INSIDE the guarded region: its failure triggers the same
        // race-free cleanup instead of leaving started jobs running against a restored ticket.
        DmTicketDbStmtDO g1 = group(11L, "PENDING");
        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(g1));
        when(autoExecService.deleteJobIfNotDispatched(anyString())).thenReturn(true);
        doThrow(new IllegalStateException("mark failed"))
            .when(approvalStateService).markExecutionDispatched(BIZ_ID);

        invokeManualPrepare(confirmFo(AutoExecType.IMMEDIATE, false, ErrorStrategy.NONE, null, null, null));

        ArgumentCaptor<String> createdBiz = ArgumentCaptor.forClass(String.class);
        verify(autoExecService).createGroupJob(same(g1), createdBiz.capture(), anyBoolean(), any(),
            any(), any(), any(), any(), anyString(), anyString());
        verify(autoExecService).startJob(createdBiz.getValue(), UID);
        verify(autoExecService).deleteJobIfNotDispatched(createdBiz.getValue());
        verify(stmtMapper).updateExecStatus(11L, "PENDING", null);
        verify(approvalMapper).updateStatusByEnum(eq(TICKET_ID), eq(ApprovalStatus.WAIT_CONFIRM), anyString());
    }

    @Test
    public void manualConfirm_guardDeny_throwsIntoExistingCatch_noJobsRestored() {
        when(govExecutionGuardService.checkByTicket(any(), any(), any()))
            .thenReturn(GuardConclusion.deny("blocked by gate"));

        invokeManualPrepare(confirmFo(AutoExecType.IMMEDIATE, false, ErrorStrategy.NONE, null, null, null));

        // touchpoint #2 contract kept: DENY throws into the catch → restore, zero dispatch
        // (the catch's deleteJob on the unused passed bizId is the only executor interaction —
        // a safe no-op on the absent bizId)
        verify(autoExecService, never()).createGroupJob(any(), anyString(), anyBoolean(), any(),
            any(), any(), any(), any(), anyString(), anyString());
        verify(autoExecService, never()).startJob(anyString(), anyString());
        verify(autoExecService).deleteJob(PASSED_JOB_BIZ);
        verify(approvalMapper).updateStatusByEnum(eq(TICKET_ID), eq(ApprovalStatus.WAIT_CONFIRM), anyString());
    }

    // ======= legacy path: byte-for-byte preservation =======

    @Test
    public void manualConfirm_legacyTicketWithoutTicketType_createExecJobPathUntouched() {
        dmTicket.setTicketInfo(null); // old ticket — no ticketType
        stubLegacyCreateExecJobDependencies();

        invokeManualPrepare(confirmFo(AutoExecType.IMMEDIATE, false, ErrorStrategy.NONE, 111L, 2L, null));

        // legacy single job from rawSql via consumeSqlFile, the passed jobBizId, autoExec flag,
        // startJob — and NEVER the v2 group path nor the explicit dispatch mark (the legacy
        // dispatchJob hook owns step-times there)
        verify(approvalService).consumeSqlFile(eq(TICKET_ID), any());
        verify(autoExecService).startJob(PASSED_JOB_BIZ, UID);
        verify(autoExecService, never()).createGroupJob(any(), anyString(), anyBoolean(), any(),
            any(), any(), any(), any(), anyString(), anyString());
        verify(autoExecService, never()).createGroupJob(any(), anyString(), anyBoolean(), any(), anyString(), anyString());
        ArgumentCaptor<String> infoCaptor = ArgumentCaptor.forClass(String.class);
        verify(approvalMapper).updateTicketInfo(eq(TICKET_ID), infoCaptor.capture());
        assertTrue(infoCaptor.getValue().contains("\"autoExec\":true"));
        verify(approvalStateService, never()).markExecutionDispatched(anyString());
        verifyNoInteractions(stmtMapper);
    }

    @Test
    public void isV2ProdDmlTicket_tolerantParseMatrix_neverThrows() {
        // the discriminator is the SINGLE v2/legacy decision point (design §2.1) — it must be
        // tolerant on every carrier shape (B6 sweep-parse lesson: JsonUtils sneaky-throws the
        // CHECKED JsonProcessingException; unparsable → false → the legacy path stays the
        // status-quo default). NOTE: an unparsable ticketInfo would later crash the PRE-EXISTING
        // updateAutoExecFlag/restore parse (:1249/:1264 — untouched legacy behavior, machine-
        // written carrier cannot corrupt per B6 discipline); this pin covers the N0 surface only.
        assertFalse(discriminate(null));
        assertFalse(discriminate(ticketWithInfo(null)));
        assertFalse(discriminate(ticketWithInfo("")));
        assertFalse(discriminate(ticketWithInfo("{not-json")));
        assertFalse(discriminate(ticketWithInfo("{}")));                       // no ticketType
        assertFalse(discriminate(ticketWithInfo(v2TicketInfo("PRE_DDL"))));    // PROD_DML-only
        assertTrue(discriminate(ticketWithInfo(v2TicketInfo("PROD_DML"))));
    }

    private boolean discriminate(DmApprovalDO ticket) {
        Boolean result = ReflectionTestUtils.invokeMethod(service, "isV2ProdDmlTicket", ticket);
        return Boolean.TRUE.equals(result);
    }

    private static DmApprovalDO ticketWithInfo(String ticketInfo) {
        DmApprovalDO t = ticket(ApprovalStatus.WAIT_EXEC);
        t.setTicketInfo(ticketInfo);
        return t;
    }

    @Test
    public void manualConfirm_preDdlTicketType_keepsLegacyPath_statusQuo() {
        dmTicket.setTicketInfo(v2TicketInfo("PRE_DDL"));
        stubLegacyCreateExecJobDependencies();

        invokeManualPrepare(confirmFo(AutoExecType.IMMEDIATE, false, ErrorStrategy.NONE, null, null, null));

        // discrimination is PROD_DML-only (design §2.1): PRE_DDL never reaches here in practice
        // (the auto-advance scheduler SYSTEM-confirms it via confirmTicketBySystemForV2); should
        // it ever land here, it keeps the status-quo legacy path
        verify(approvalService).consumeSqlFile(eq(TICKET_ID), any());
        verify(autoExecService, never()).createGroupJob(any(), anyString(), anyBoolean(), any(),
            any(), any(), any(), any(), anyString(), anyString());
    }

    // ======= SYSTEM copy (open command execute entry): three-entry equivalence =======

    @Test
    public void systemConfirm_multiGroupProdDml_identicalShapeWithSystemOperator() {
        DmTicketDbStmtDO g1 = group(11L, "PENDING");
        DmTicketDbStmtDO g2 = group(12L, "PENDING");
        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(g1, g2));

        invokeSystemPrepare(confirmFo(AutoExecType.IMMEDIATE, true, ErrorStrategy.NONE, 111L, 2L, null));

        ArgumentCaptor<String> createBizCaptor = ArgumentCaptor.forClass(String.class);
        verify(autoExecService, times(2)).createGroupJob(any(), createBizCaptor.capture(),
            eq(true), eq(ErrorStrategy.NONE), eq(111L), eq(2L),
            eq(AutoExecType.IMMEDIATE), isNull(), eq("zh-CN"), eq("SYSTEM"));
        ArgumentCaptor<String> startBizCaptor = ArgumentCaptor.forClass(String.class);
        verify(autoExecService, times(2)).startJob(startBizCaptor.capture(), eq("SYSTEM"));
        assertEquals(createBizCaptor.getAllValues(), startBizCaptor.getAllValues());
        verify(approvalStateService).markExecutionDispatched(BIZ_ID);
        verify(approvalMapper, never()).updateTicketInfo(anyLong(), anyString());
        verify(approvalService, never()).consumeSqlFile(anyLong(), any());
    }

    @Test
    public void systemConfirm_legacyTicket_identicalToManualCopyLegacyBranch() {
        dmTicket.setTicketInfo(null);
        stubLegacyCreateExecJobDependencies();

        invokeSystemPrepare(confirmFo(AutoExecType.IMMEDIATE, false, ErrorStrategy.NONE, null, null, null));

        verify(approvalService).consumeSqlFile(eq(TICKET_ID), any());
        verify(autoExecService).startJob(PASSED_JOB_BIZ, "SYSTEM");
        verify(autoExecService, never()).createGroupJob(any(), anyString(), anyBoolean(), any(),
            any(), any(), any(), any(), anyString(), anyString());
        ArgumentCaptor<String> infoCaptor = ArgumentCaptor.forClass(String.class);
        verify(approvalMapper).updateTicketInfo(eq(TICKET_ID), infoCaptor.capture());
        assertTrue(infoCaptor.getValue().contains("\"autoExec\":true"));
    }

    @Test
    public void systemConfirm_group2Fails_sameStrictCleanupAndRestore() {
        DmTicketDbStmtDO g1 = group(11L, "PENDING");
        DmTicketDbStmtDO g2 = group(12L, "PENDING");
        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(g1, g2));
        when(autoExecService.deleteJobIfNotDispatched(anyString())).thenReturn(true);
        doThrow(new IllegalStateException("boom")).when(autoExecService).createGroupJob(
            same(g2), anyString(), anyBoolean(), any(), any(), any(), any(), any(), anyString(), anyString());

        invokeSystemPrepare(confirmFo(AutoExecType.IMMEDIATE, false, ErrorStrategy.NONE, null, null, null));

        ArgumentCaptor<String> createdBiz = ArgumentCaptor.forClass(String.class);
        verify(autoExecService).createGroupJob(same(g1), createdBiz.capture(), anyBoolean(), any(),
            any(), any(), any(), any(), anyString(), anyString());
        verify(autoExecService).deleteJobIfNotDispatched(createdBiz.getValue());
        verify(stmtMapper).updateExecStatus(11L, "PENDING", null);
        verify(approvalMapper).updateStatusByEnum(eq(TICKET_ID), eq(ApprovalStatus.WAIT_CONFIRM), anyString());
    }

    // ======= helpers =======

    private void invokeManualPrepare(DmConfirmTicketFO fo) {
        ReflectionTestUtils.invokeMethod(service, "prepareExecJobAsync", TICKET_ID, fo, PASSED_JOB_BIZ, LOCALE);
    }

    private void invokeSystemPrepare(DmConfirmTicketFO fo) {
        fo.setConfirmUid("SYSTEM");
        ReflectionTestUtils.invokeMethod(service, "prepareExecJobAsSystemAsync", TICKET_ID, fo, PASSED_JOB_BIZ, LOCALE);
    }

    private DmConfirmTicketFO confirmFo(AutoExecType execType, boolean transactional, ErrorStrategy strategy,
                                        Long retryWaitTime, Long retryCount, Long execTime) {
        DmAutoExecConfigFO config = new DmAutoExecConfigFO();
        config.setAutoExecType(execType);
        config.setEnableTransactional(transactional);
        config.setErrorStrategy(strategy);
        config.setRetryWaitTime(retryWaitTime);
        config.setRetryCount(retryCount);
        config.setExecTime(execTime);
        config.setSnapshot(false);
        DmConfirmTicketFO fo = new DmConfirmTicketFO();
        fo.setTicketId(TICKET_ID);
        fo.setConfirmActionType(DmConfirmActionType.CONFIRM);
        fo.setConfirmUid(UID);
        fo.setAutoExecConfig(config);
        return fo;
    }

    private static DmApprovalDO ticket(ApprovalStatus status) {
        DmApprovalDO t = new DmApprovalDO();
        t.setId(TICKET_ID);
        t.setBizId(BIZ_ID);
        t.setPrimaryUid(PUID);
        t.setOwnerUid(UID);
        t.setApproBiz(ApprovalBiz.DM_CHANGE);
        t.setTicketStatus(status);
        t.setBindDsId(BIND_DS_ID);
        t.setLevels(List.of("1", String.valueOf(BIND_DS_ID), "db"));
        t.setRawSql("UPDATE t SET a = 1 WHERE id > 0;");
        return t;
    }

    private static String v2TicketInfo(String ticketType) {
        ApprovalMO mo = new ApprovalMO();
        mo.setTicketType(ticketType);
        return JsonUtils.toJson(mo);
    }

    private static DmTicketDbStmtDO group(long id, String execStatus) {
        DmTicketDbStmtDO group = new DmTicketDbStmtDO();
        group.setId(id);
        group.setTicketId(TICKET_ID);
        group.setPairId(300L);
        group.setDsId(BIND_DS_ID);
        group.setDbName("db");
        group.setSqlContent("UPDATE t SET a = 1 WHERE id > 0;");
        group.setPrecheckResult("{\"checkStatus\":\"PASS\",\"changeType\":\"DML\"}");
        group.setExecStatus(execStatus);
        return group;
    }

    private void stubLegacyCreateExecJobDependencies() {
        DsCacheEntry entry = mock(DsCacheEntry.class);
        when(entry.getEnvId()).thenReturn(1L);
        when(objectCacheDao.queryByDsId(BIND_DS_ID)).thenReturn(entry);
    }
}
