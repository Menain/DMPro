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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.console.web.component.approval.ApprovalFlowService;
import com.clougence.clouddm.console.web.component.approval.ApprovalService;
import com.clougence.clouddm.console.web.component.approval.ApprovalStateService;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.component.approval.schedule.ApprovalTaskScheduler;
import com.clougence.clouddm.console.web.component.execute.AutoExecService;
import com.clougence.clouddm.console.web.component.governance.SchemaSnapshotService;
import com.clougence.clouddm.console.web.constants.DmConfirmActionType;
import com.clougence.clouddm.console.web.global.i18n.DmI18nUtils;
import com.clougence.clouddm.console.web.global.i18n.I18nDmMsgKeys;
import com.clougence.clouddm.console.web.model.fo.ticket.DmAutoExecConfigFO;
import com.clougence.clouddm.console.web.model.fo.ticket.DmConfirmTicketFO;
import com.clougence.clouddm.console.web.service.governance.GovExecutionGuardService;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.AuthDal;
import com.clougence.clouddm.platform.dal.access.ExecutionDal;
import com.clougence.clouddm.platform.dal.access.ObjectCacheDao;
import com.clougence.clouddm.platform.dal.access.TicketDbStmtDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.auth.DmAuthRoleMapper;
import com.clougence.clouddm.platform.dal.mapper.auth.DmAuthUserMapper;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoJobMapper;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoTaskMapper;
import com.clougence.clouddm.platform.dal.mapper.govticket.DmTicketDbStmtMapper;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.auth.AccountType;
import com.clougence.clouddm.platform.dal.model.auth.DmAuthUserDO;
import com.clougence.clouddm.platform.dal.model.execution.AutoExecType;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;
import com.clougence.utils.JsonUtils;

/**
 * B7 N3 (裁决 #17, design §13 "SPECIFY_TIME + 分段组 → v1 确认拒绝引导"): the confirm-entry guard
 * pins. A segmented group (plan carrying a GHOST ∨ DML_BATCH routed segment) cannot honor
 * scheduled execution — the group job's schedule_time only defers the AUTOEXEC part while the
 * routed segments execute through AsyncTasks with NO scheduling carrier — so the confirm is
 * rejected SYNCHRONOUSLY (the ticket stays WAIT_CONFIRM, the operator re-picks IMMEDIATE or
 * MANUAL_EXEC) with GOV_TICKET_V2_DML_BATCH_SCHEDULE_UNSUPPORTED.
 * <p>
 * Preserved faces (the N0 pins' territory, re-asserted here at the confirm ENTRY level):
 * pure-normal groups keep the scheduled semantics byte-identical; MANUAL_EXEC builds no executor
 * and is never guarded; a REFUSE carrying a stale SPECIFY_TIME config is never blocked; legacy
 * tickets (no ticketInfo.ticketType) skip the group read entirely (zero extra queries); the
 * SYSTEM copy (open command execute / auto-advance entry) carries the identical guard — 双副本
 * 同改契约. Discipline: JUnit4 + handwritten mocks + ReflectionTestUtils (the
 * ProdDmlConfirmV2DispatchTest harness pattern — that frozen N0 suite stays zero-change).
 */
public class ProdDmlScheduleGuardTest {

    private ApprovalControlServiceImpl service;

    private DmApprovalMapper         approvalMapper;
    private DmTicketDbStmtMapper     stmtMapper;
    private ApprovalStateService     approvalStateService;
    private ApprovalFlowService      approvalFlowService;
    private ApprovalTaskScheduler    approvalTaskScheduler;

    private static final long   TICKET_ID = 900L;
    private static final String BIZ_ID    = "ticket-biz-900";
    private static final String PUID      = "puid-001";
    private static final String UID       = "uid-001";
    private static final long   BIND_DS   = 20L;

    /** the DML_BATCH plan carrier (isSegmentedPlan = hasRoutedSegment → true). */
    private static final String SEGMENTED_PRECHECK =
        "{\"checkStatus\":\"PASS\",\"changeType\":\"DML\",\"segments\":["
            + "{\"segIdx\":1,\"type\":\"DML_BATCH\",\"stmtIndex\":1,\"table\":\"t_order\","
            + "\"estimatedRows\":5000000,\"idempotent\":true,"
            + "\"sql\":\"DELETE FROM t_order WHERE status = 0\","
            + "\"batchKey\":{\"column\":\"id\",\"keyType\":\"INT\"}}]}";

    private static final String PLAIN_PRECHECK =
        "{\"checkStatus\":\"PASS\",\"changeType\":\"DML\"}";

    private DmApprovalDO rdpTicket;
    private DmApprovalDO dmTicket;

    @Before
    public void setUp() {
        service = new ApprovalControlServiceImpl();

        // DmTeamUtils static executionDal (nextExecJobBizId generator)
        ExecutionDal staticExecDal = mock(ExecutionDal.class);
        DmExecAutoJobMapper staticJobMapper = mock(DmExecAutoJobMapper.class);
        DmExecAutoTaskMapper staticTaskMapper = mock(DmExecAutoTaskMapper.class);
        when(staticExecDal.autoJobMapper()).thenReturn(staticJobMapper);
        when(staticExecDal.autoTaskMapper()).thenReturn(staticTaskMapper);
        when(staticJobMapper.queryByBizId(anyString())).thenReturn(null);
        when(staticTaskMapper.queryByBizId(anyString())).thenReturn(null);
        ReflectionTestUtils.setField(com.clougence.clouddm.console.web.util.DmTeamUtils.class, "executionDal", staticExecDal);

        ApprovalDal approvalDal = mock(ApprovalDal.class);
        approvalMapper = mock(DmApprovalMapper.class);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);
        AuthDal authDal = mock(AuthDal.class);
        DmAuthUserMapper userMapper = mock(DmAuthUserMapper.class);
        when(authDal.userMapper()).thenReturn(userMapper);
        when(authDal.roleMapper()).thenReturn(mock(DmAuthRoleMapper.class));
        TicketDbStmtDal ticketDbStmtDal = mock(TicketDbStmtDal.class);
        stmtMapper = mock(DmTicketDbStmtMapper.class);
        when(ticketDbStmtDal.stmtMapper()).thenReturn(stmtMapper);
        approvalStateService = mock(ApprovalStateService.class);
        approvalFlowService = mock(ApprovalFlowService.class);
        approvalTaskScheduler = mock(ApprovalTaskScheduler.class);
        when(approvalTaskScheduler.submitControlTask(anyLong(), any())).thenReturn(true);

        ReflectionTestUtils.setField(service, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(service, "authDal", authDal);
        ReflectionTestUtils.setField(service, "ticketDbStmtDal", ticketDbStmtDal);
        ReflectionTestUtils.setField(service, "autoExecService", mock(AutoExecService.class));
        ReflectionTestUtils.setField(service, "approvalStateService", approvalStateService);
        ReflectionTestUtils.setField(service, "approvalFlowService", approvalFlowService);
        ReflectionTestUtils.setField(service, "approvalService", mock(ApprovalService.class));
        ReflectionTestUtils.setField(service, "govExecutionGuardService", mock(GovExecutionGuardService.class));
        ReflectionTestUtils.setField(service, "schemaSnapshotService", mock(SchemaSnapshotService.class));
        ReflectionTestUtils.setField(service, "approvalTaskScheduler", approvalTaskScheduler);
        ReflectionTestUtils.setField(service, "objectCacheDao", mock(ObjectCacheDao.class));
        ReflectionTestUtils.setField(service, "txManager",
            mock(org.springframework.transaction.PlatformTransactionManager.class));

        // primary-account operator → checkJobOperationEnable passes without role lookups
        DmAuthUserDO user = new DmAuthUserDO();
        user.setUid(UID);
        user.setUsername("operator");
        user.setAccountType(AccountType.PRIMARY_ACCOUNT);
        when(userMapper.queryByUid(UID)).thenReturn(user);

        rdpTicket = ticket();
        dmTicket = ticket();
        dmTicket.setTicketInfo(v2TicketInfo("PROD_DML"));
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(rdpTicket);
        when(approvalMapper.queryByBizId(BIZ_ID)).thenReturn(dmTicket);
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(rdpTicket);
    }

    // ==================== fixtures ====================

    private static DmApprovalDO ticket() {
        DmApprovalDO t = new DmApprovalDO();
        t.setId(TICKET_ID);
        t.setBizId(BIZ_ID);
        t.setPrimaryUid(PUID);
        t.setOwnerUid(UID);
        t.setApproBiz(ApprovalBiz.DM_CHANGE);
        t.setTicketStatus(ApprovalStatus.WAIT_CONFIRM);
        t.setBindDsId(BIND_DS);
        t.setLevels(List.of("1", String.valueOf(BIND_DS), "db"));
        return t;
    }

    private static String v2TicketInfo(String ticketType) {
        ApprovalMO mo = new ApprovalMO();
        mo.setTicketType(ticketType);
        return JsonUtils.toJson(mo);
    }

    private static DmTicketDbStmtDO group(long id, String precheckResult) {
        DmTicketDbStmtDO group = new DmTicketDbStmtDO();
        group.setId(id);
        group.setTicketId(TICKET_ID);
        group.setPairId(300L);
        group.setDsId(BIND_DS);
        group.setDbName("db");
        group.setSqlContent("DELETE FROM t_order WHERE status = 0;");
        group.setPrecheckResult(precheckResult);
        group.setExecStatus("PENDING");
        return group;
    }

    private DmConfirmTicketFO confirmFo(AutoExecType execType) {
        DmAutoExecConfigFO config = new DmAutoExecConfigFO();
        config.setAutoExecType(execType);
        config.setEnableTransactional(false);
        config.setExecTime(execType == AutoExecType.SPECIFY_TIME ? System.currentTimeMillis() + 3600_000L : null);
        config.setSnapshot(false);
        DmConfirmTicketFO fo = new DmConfirmTicketFO();
        fo.setTicketId(TICKET_ID);
        fo.setConfirmActionType(DmConfirmActionType.CONFIRM);
        fo.setConfirmUid(UID);
        fo.setAutoExecConfig(config);
        return fo;
    }

    private static String scheduleUnsupportedMessage() {
        return DmI18nUtils.getMessage(I18nDmMsgKeys.GOV_TICKET_V2_DML_BATCH_SCHEDULE_UNSUPPORTED.name());
    }

    // ==================== human confirm entry ====================

    @Test
    public void confirm_specifyTime_segmentedGroup_rejected_ticketStaysWaitConfirm() {
        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(group(11L, SEGMENTED_PRECHECK)));

        try {
            service.confirmTicket(PUID, TICKET_ID, confirmFo(AutoExecType.SPECIFY_TIME));
            fail("expected the SPECIFY_TIME + segmented-group rejection (裁决 #17)");
        } catch (ErrorMessageException expected) {
            assertEquals(scheduleUnsupportedMessage(), expected.getMessage());
        }
        // the rejection is synchronous and PRE-transition: no status write, no control task
        verify(approvalMapper, never()).updateStatusByEnum(anyLong(), any(), anyString());
        verify(approvalFlowService, never()).transitionTicketToTerminal(anyLong(), any(), anyString());
        verify(approvalTaskScheduler, never()).submitControlTask(anyLong(), any());
    }

    @Test
    public void confirm_specifyTime_secondGroupSegmented_rejected() {
        // ANY group's segment plan fires the guard (the whole ticket confirms as one)
        when(stmtMapper.queryByTicketId(TICKET_ID))
            .thenReturn(List.of(group(11L, PLAIN_PRECHECK), group(12L, SEGMENTED_PRECHECK)));

        try {
            service.confirmTicket(PUID, TICKET_ID, confirmFo(AutoExecType.SPECIFY_TIME));
            fail("expected the rejection over the second group's plan");
        } catch (ErrorMessageException expected) {
            assertEquals(scheduleUnsupportedMessage(), expected.getMessage());
        }
    }

    @Test
    public void confirm_specifyTime_pureNormalGroups_scheduledSemanticsPreserved() {
        // the N0 preserved face: 纯普通组定时语义不变 — the confirm proceeds to WAIT_EXEC and the
        // control task carries the FO (execTime passthrough happens in the async dispatch)
        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(group(11L, PLAIN_PRECHECK)));

        assertNotNull(service.confirmTicket(PUID, TICKET_ID, confirmFo(AutoExecType.SPECIFY_TIME)));

        verify(approvalMapper).updateStatusByEnum(eq(TICKET_ID), eq(ApprovalStatus.WAIT_EXEC), anyString());
        verify(approvalTaskScheduler).submitControlTask(eq(TICKET_ID), any());
    }

    @Test
    public void confirm_immediate_segmentedGroup_proceeds() {
        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(group(11L, SEGMENTED_PRECHECK)));

        assertNotNull(service.confirmTicket(PUID, TICKET_ID, confirmFo(AutoExecType.IMMEDIATE)));

        verify(approvalMapper).updateStatusByEnum(eq(TICKET_ID), eq(ApprovalStatus.WAIT_EXEC), anyString());
        verify(approvalTaskScheduler).submitControlTask(eq(TICKET_ID), any());
    }

    @Test
    public void confirm_manualExec_segmentedGroup_neverGuarded_noGroupRead() {
        // MANUAL_EXEC 语义保留 (design §13): FINISHED at confirm, no executor — the guard keys on
        // actionStatus == WAIT_EXEC so the manual path never even reads the groups
        service.confirmTicket(PUID, TICKET_ID, confirmFo(AutoExecType.MANUAL_EXEC));

        verify(stmtMapper, never()).queryByTicketId(anyLong());
        verify(approvalFlowService).transitionTicketToTerminal(eq(TICKET_ID), eq(ApprovalStatus.FINISHED), any());
    }

    @Test
    public void confirm_refuseWithStaleSpecifyTimeConfig_neverBlocked() {
        // a REFUSE must not be held hostage by a stale SPECIFY_TIME config over a segmented ticket
        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(group(11L, SEGMENTED_PRECHECK)));
        DmConfirmTicketFO fo = confirmFo(AutoExecType.SPECIFY_TIME);
        fo.setConfirmActionType(DmConfirmActionType.REFUSE);

        service.confirmTicket(PUID, TICKET_ID, fo);

        verify(approvalFlowService).transitionTicketToTerminal(eq(TICKET_ID), eq(ApprovalStatus.REJECTED), any());
    }

    @Test
    public void confirm_legacyTicket_specifyTime_statusQuoZeroGroupRead() {
        // legacy tickets (no/unparsable ticketInfo.ticketType) carry no group rows / segment plans —
        // the v2 discriminator gates the read: byte-identical behavior, ZERO extra queries (the
        // async prepareExecJob* body never runs here — the scheduler mock captures the lambda)
        dmTicket.setTicketInfo(null);

        assertNotNull(service.confirmTicket(PUID, TICKET_ID, confirmFo(AutoExecType.SPECIFY_TIME)));

        verify(stmtMapper, never()).queryByTicketId(anyLong());
        verify(approvalMapper).updateStatusByEnum(eq(TICKET_ID), eq(ApprovalStatus.WAIT_EXEC), anyString());
    }

    // ==================== SYSTEM copy (双副本同改契约) ====================

    @Test
    public void systemConfirm_specifyTime_segmentedGroup_identicalRejection() {
        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(group(11L, SEGMENTED_PRECHECK)));
        DmAutoExecConfigFO config = new DmAutoExecConfigFO();
        config.setAutoExecType(AutoExecType.SPECIFY_TIME);
        config.setEnableTransactional(false);
        config.setExecTime(System.currentTimeMillis() + 3600_000L);

        try {
            service.confirmTicketBySystem(TICKET_ID, config);
            fail("expected the SYSTEM copy to carry the identical guard");
        } catch (ErrorMessageException expected) {
            assertEquals(scheduleUnsupportedMessage(), expected.getMessage());
        }
        verify(approvalMapper, never()).updateStatusByEnum(anyLong(), any(), anyString());
    }

    @Test
    public void systemConfirm_immediate_segmentedGroup_proceeds() {
        // today's SYSTEM callers pass IMMEDIATE by construction (auto-advance / open command) —
        // the guard never fires on the reachable shapes
        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(group(11L, SEGMENTED_PRECHECK)));
        DmAutoExecConfigFO config = new DmAutoExecConfigFO();
        config.setAutoExecType(AutoExecType.IMMEDIATE);
        config.setEnableTransactional(false);

        service.confirmTicketBySystem(TICKET_ID, config);

        verify(approvalMapper).updateStatusByEnum(eq(TICKET_ID), eq(ApprovalStatus.WAIT_EXEC), anyString());
        verify(approvalTaskScheduler).submitControlTask(eq(TICKET_ID), any());
    }

    // ==================== message key registration ====================

    @Test
    public void scheduleUnsupportedKey_resolvesNonEmpty() {
        String message = scheduleUnsupportedMessage();
        assertNotNull(message);
        assertTrue("the key must resolve to real copy, not the bare name",
            message.length() > 0 && !message.equals(I18nDmMsgKeys.GOV_TICKET_V2_DML_BATCH_SCHEDULE_UNSUPPORTED.name()));
    }
}
