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
package com.clougence.clouddm.console.web.service.govticket.impl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.console.web.component.approval.ApprovalStateService;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.component.execute.AutoExecService;
import com.clougence.clouddm.console.web.model.fo.ghost.GhostExecuteFO;
import com.clougence.clouddm.console.web.model.vo.ghost.GhostExecuteVO;
import com.clougence.clouddm.console.web.model.vo.ghost.GhostOpAuditObj;
import com.clougence.clouddm.console.web.service.ghost.GhostService;
import com.clougence.clouddm.console.web.service.govticket.GovTicketV2Service;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.ExecutionDal;
import com.clougence.clouddm.platform.dal.access.TicketDbStmtDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoJobMapper;
import com.clougence.clouddm.platform.dal.mapper.govticket.DmTicketDbStmtMapper;
import com.clougence.clouddm.platform.dal.model.ResourceType;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.execution.AsyncTaskStatus;
import com.clougence.clouddm.platform.dal.model.execution.DmExecAsyncTaskDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;
import com.clougence.clouddm.platform.dal.model.monitor.AuditType;
import com.clougence.clouddm.platform.dal.model.monitor.SecurityLevel;
import com.clougence.rdp.service.RdpOpAuditService;
import com.clougence.utils.JsonUtils;

/**
 * B6 M1 (Step 7a): dispatchGhostGroup (the retryGroup-mirroring "build execution +
 * markExecutionDispatched" shape) and the retryGroup ghost variant — the extended active check
 * (active autoexec job ∨ active GHOST AsyncTask), the rebuild shape (close old session/task →
 * fresh dispatch, old-table remnants untouched) and the legacy-path regression for unmarked
 * groups (the pinned 5-method matrix lives in GovTicketV2RetryGroupTest, untouched).
 */
public class GhostDispatchTest {

    private GovTicketV2ServiceImpl   service;
    private TicketDbStmtDal          ticketDbStmtDal;
    private DmTicketDbStmtMapper     stmtMapper;
    private ApprovalDal              approvalDal;
    private DmApprovalMapper         approvalMapper;
    private ExecutionDal             execDal;
    private DmExecAutoJobMapper      autoJobMapper;
    private AutoExecService          autoExecService;
    private ApprovalStateService     approvalStateService;
    private GhostService             ghostService;
    private RdpOpAuditService        rdpOpAuditService;
    private com.clougence.clouddm.console.web.component.governance.SchemaSnapshotService schemaSnapshotService;

    private static final String PUID     = "puid-001";
    private static final String OWNER    = "uid-submitter";
    private static final String ACTOR    = "uid-dba";
    private static final long   TICKET_ID = 900L;
    private static final long   GROUP_ID  = 100L;
    private static final long   DS_ID     = 20L;
    private static final String DB        = "pre_db";
    private static final String ALTER     = "ALTER TABLE t_big ADD COLUMN c1 INT";

    @Before
    public void setUp() {
        service = new GovTicketV2ServiceImpl();

        // DmTeamUtils static executionDal (not used by the ghost path, present for parity)
        ExecutionDal staticExecDal = mock(ExecutionDal.class);
        DmExecAutoJobMapper staticJobMapper = mock(DmExecAutoJobMapper.class);
        com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoTaskMapper staticTaskMapper
            = mock(com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoTaskMapper.class);
        when(staticExecDal.autoJobMapper()).thenReturn(staticJobMapper);
        when(staticExecDal.autoTaskMapper()).thenReturn(staticTaskMapper);
        when(staticJobMapper.queryByBizId(anyString())).thenReturn(null);
        when(staticTaskMapper.queryByBizId(anyString())).thenReturn(null);
        ReflectionTestUtils.setField(com.clougence.clouddm.console.web.util.DmTeamUtils.class, "executionDal", staticExecDal);

        ticketDbStmtDal = mock(TicketDbStmtDal.class);
        stmtMapper = mock(DmTicketDbStmtMapper.class);
        when(ticketDbStmtDal.stmtMapper()).thenReturn(stmtMapper);
        when(ticketDbStmtDal.schemaMapper())
            .thenReturn(mock(com.clougence.clouddm.platform.dal.mapper.govticket.DmTicketStmtSchemaMapper.class));

        approvalDal = mock(ApprovalDal.class);
        approvalMapper = mock(DmApprovalMapper.class);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);

        execDal = mock(ExecutionDal.class);
        autoJobMapper = mock(DmExecAutoJobMapper.class);
        when(execDal.autoJobMapper()).thenReturn(autoJobMapper);

        autoExecService = mock(AutoExecService.class);
        approvalStateService = mock(ApprovalStateService.class);
        schemaSnapshotService = mock(com.clougence.clouddm.console.web.component.governance.SchemaSnapshotService.class);

        ghostService = mock(GhostService.class);
        when(ghostService.executeForTicket(anyString(), anyString(), any(), anyLong(), anyLong(), any()))
            .thenAnswer(inv -> {
                GhostExecuteVO vo = new GhostExecuteVO();
                vo.setSessionId("session-new");
                GhostOpAuditObj audit = new GhostOpAuditObj();
                audit.setSessionId("session-new");
                audit.setDsId(DS_ID);
                audit.setResult("SUCCESS");
                vo.setAudit(audit);
                return vo;
            });

        rdpOpAuditService = mock(RdpOpAuditService.class);

        ReflectionTestUtils.setField(service, "ticketDbStmtDal", ticketDbStmtDal);
        ReflectionTestUtils.setField(service, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(service, "execDal", execDal);
        ReflectionTestUtils.setField(service, "autoExecService", autoExecService);
        ReflectionTestUtils.setField(service, "approvalStateService", approvalStateService);
        ReflectionTestUtils.setField(service, "schemaSnapshotService", schemaSnapshotService);
        ReflectionTestUtils.setField(service, "ghostService", ghostService);
        ReflectionTestUtils.setField(service, "rdpOpAuditService", rdpOpAuditService);
    }

    // ==================== dispatchGhostGroup ====================

    @Test
    public void dispatch_executesBindsGroup_marksExecuting_closesPreparation_audits() {
        DmApprovalDO ticket = ticket(List.of(true));
        DmTicketDbStmtDO group = group("PENDING");
        when(stmtMapper.queryById(GROUP_ID)).thenReturn(group); // the lock-time CAS re-read

        service.dispatchGhostGroup(ticket, group, GovTicketV2Service.GHOST_VIA_AUTO_ROUTE);

        // --- concurrency contract: the ticket row lock precedes the launch AND the group write
        //     (a fast terminal hook must never observe the pre-dispatch group snapshot) ---
        InOrder lockOrder = inOrder(approvalMapper, ghostService, stmtMapper);
        lockOrder.verify(approvalMapper).selectByIdForUpdate(TICKET_ID);
        lockOrder.verify(ghostService).executeForTicket(eq(PUID), eq(OWNER), any(), eq(TICKET_ID), eq(GROUP_ID), isNull());
        lockOrder.verify(stmtMapper).updateExecStatus(GROUP_ID, "EXECUTING", null);

        // --- launch: submitter-owned session bound to ticket/group, empty FO options (R14 chain) ---
        ArgumentCaptor<GhostExecuteFO> foCaptor = ArgumentCaptor.forClass(GhostExecuteFO.class);
        verify(ghostService).executeForTicket(eq(PUID), eq(OWNER), foCaptor.capture(), eq(TICKET_ID), eq(GROUP_ID), isNull());
        GhostExecuteFO fo = foCaptor.getValue();
        assertEquals(Long.valueOf(DS_ID), fo.getDsId());
        assertEquals(DB, fo.getDbName());
        assertEquals(ALTER, fo.getDdlSql());
        assertNull("ticket scene carries NO task options — the admin merge chain applies", fo.getOptions());

        // --- group row EXECUTING + PREPARATION closed (retryGroup :410-418 shape) ---
        verify(stmtMapper).updateExecStatus(GROUP_ID, "EXECUTING", null);
        verify(approvalStateService).markExecutionDispatched("biz-900");
        verify(schemaSnapshotService).captureIfMissing(group, TICKET_ID);

        // --- R10 audit: subject = submitter, HIGH GHOST_EXECUTE, obj carries the ticket source ---
        ArgumentCaptor<Object> objCaptor = ArgumentCaptor.forClass(Object.class);
        verify(rdpOpAuditService).logAndAddOperationAudit(eq(PUID), eq(OWNER), anyString(), eq(null),
            eq(DS_ID), objCaptor.capture(), eq(SecurityLevel.HIGH), eq(AuditType.GHOST_EXECUTE),
            eq(ResourceType.DATASOURCE));
        GhostOpAuditObj auditObj = (GhostOpAuditObj) objCaptor.getValue();
        assertEquals(Long.valueOf(TICKET_ID), auditObj.getTicketId());
        assertEquals(Long.valueOf(GROUP_ID), auditObj.getGroupId());
        assertEquals(GovTicketV2Service.GHOST_VIA_AUTO_ROUTE, auditObj.getVia());
    }

    @Test
    public void dispatch_launchFailure_propagates_groupStaysUntouched() {
        when(ghostService.executeForTicket(anyString(), anyString(), any(), anyLong(), anyLong(), any()))
            .thenThrow(new ErrorMessageException("GHOST_BINARY_MISSING: no binary"));
        DmApprovalDO ticket = ticket(List.of(true));
        DmTicketDbStmtDO group = group("PENDING");
        when(stmtMapper.queryById(GROUP_ID)).thenReturn(group); // the lock-time CAS re-read

        try {
            service.dispatchGhostGroup(ticket, group, "TICKET_AUTO_ROUTE");
            fail("Expected ErrorMessageException");
        } catch (ErrorMessageException expected) {
            // the caller (main chain try-catch / retryGroup) owns the surfacing
        }
        verify(stmtMapper, never()).updateExecStatus(anyLong(), anyString(), any());
        verify(approvalStateService, never()).markExecutionDispatched(anyString());
    }

    @Test
    public void dispatch_auditFailure_neverBreaksTheDispatch() {
        doThrow(new RuntimeException("audit store down")).when(rdpOpAuditService).logAndAddOperationAudit(
            anyString(), anyString(), anyString(), any(), any(), any(), any(), any(), any());
        DmApprovalDO ticket = ticket(List.of(true));
        when(stmtMapper.queryById(GROUP_ID)).thenReturn(group("PENDING")); // the lock-time CAS re-read

        service.dispatchGhostGroup(ticket, group("PENDING"), "TICKET_AUTO_ROUTE"); // must not throw

        verify(stmtMapper).updateExecStatus(GROUP_ID, "EXECUTING", null);
        verify(approvalStateService).markExecutionDispatched("biz-900");
    }

    @Test
    public void dispatch_groupAlreadyExecutingUnderLock_idempotentSkip_zeroSideEffects() {
        // the auto-advance caller holds a PRE-LOCK PENDING snapshot; a concurrent retryGroup ghost
        // variant committed first — under the ticket lock the group is already EXECUTING. The
        // dispatch must skip idempotently instead of launching a SECOND gh-ost session over the
        // live migration (no uk-constraint analog exists on the ghost path — this CAS is it).
        DmApprovalDO ticket = ticket(List.of(true));
        DmTicketDbStmtDO staleSnapshot = group("PENDING");
        when(stmtMapper.queryById(GROUP_ID)).thenReturn(group("EXECUTING"));

        service.dispatchGhostGroup(ticket, staleSnapshot, GovTicketV2Service.GHOST_VIA_AUTO_ROUTE);

        verify(ghostService, never()).executeForTicket(anyString(), anyString(), any(), anyLong(), anyLong(), any());
        verify(stmtMapper, never()).updateExecStatus(anyLong(), anyString(), any());
        verify(approvalStateService, never()).markExecutionDispatched(anyString());
        verify(schemaSnapshotService, never()).captureIfMissing(any(), anyLong());
        verifyNoInteractions(rdpOpAuditService);
    }

    @Test
    public void dispatch_groupRowMissingUnderLock_skips() {
        DmApprovalDO ticket = ticket(List.of(true));
        when(stmtMapper.queryById(GROUP_ID)).thenReturn(null);

        service.dispatchGhostGroup(ticket, group("PENDING"), GovTicketV2Service.GHOST_VIA_AUTO_ROUTE);

        verify(ghostService, never()).executeForTicket(anyString(), anyString(), any(), anyLong(), anyLong(), any());
        verify(stmtMapper, never()).updateExecStatus(anyLong(), anyString(), any());
    }

    @Test
    public void dispatch_failedGroupUnderLock_redispatches() {
        // FAILED is dispatchable (the retryGroup ghost variant resets to PENDING first, but a
        // retry-race loser may still read FAILED under the lock — both are eligible)
        DmApprovalDO ticket = ticket(List.of(true));
        when(stmtMapper.queryById(GROUP_ID)).thenReturn(group("FAILED"));

        service.dispatchGhostGroup(ticket, group("FAILED"), GovTicketV2Service.GHOST_VIA_RETRY);

        verify(ghostService).executeForTicket(eq(PUID), eq(OWNER), any(), eq(TICKET_ID), eq(GROUP_ID), isNull());
        verify(stmtMapper).updateExecStatus(GROUP_ID, "EXECUTING", null);
    }

    // ==================== retryGroup ghost variant ====================

    @Test
    public void retryGhost_failedGroupWithTerminalTask_rebuilds() {
        DmTicketDbStmtDO group = group("FAILED");
        when(stmtMapper.queryById(GROUP_ID)).thenReturn(group);
        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(group));
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket(List.of(true)));
        when(autoJobMapper.queryByDependOnGroupId(GROUP_ID)).thenReturn(null);
        when(ghostService.findTaskForTicketGroup(TICKET_ID, GROUP_ID))
            .thenReturn(ghostTask(AsyncTaskStatus.FAILURE, "session-old"));

        service.retryGroup(PUID, ACTOR, GROUP_ID);

        // rebuild order: ticket row lock (lock-order discipline) → reset PENDING → dispose the
        // old session → fresh dispatch → EXECUTING
        InOrder inOrder = inOrder(approvalMapper, stmtMapper, ghostService);
        inOrder.verify(approvalMapper).selectByIdForUpdate(TICKET_ID);
        inOrder.verify(stmtMapper).updateExecStatus(GROUP_ID, "PENDING", null);
        inOrder.verify(ghostService).close(PUID, ACTOR, "session-old");
        inOrder.verify(ghostService).executeForTicket(eq(PUID), eq(OWNER), any(), eq(TICKET_ID), eq(GROUP_ID), isNull());
        inOrder.verify(stmtMapper).updateExecStatus(GROUP_ID, "EXECUTING", null);
        verify(approvalStateService).markExecutionDispatched("biz-900");
        // the autoexec rebuild path must stay untouched for a ghost group
        verify(autoExecService, never()).createGroupJob(any(), anyString(), anyBoolean(), any(), anyString(), anyString());
        // retry via the ghost variant annotates the audit with TICKET_RETRY
        ArgumentCaptor<Object> objCaptor = ArgumentCaptor.forClass(Object.class);
        verify(rdpOpAuditService).logAndAddOperationAudit(anyString(), anyString(), anyString(), any(),
            any(), objCaptor.capture(), any(), any(), any());
        assertEquals("TICKET_RETRY", ((GhostOpAuditObj) objCaptor.getValue()).getVia());
    }

    @Test
    public void retryGhost_activeTask_rejected_zeroSideEffects() {
        DmTicketDbStmtDO group = group("EXECUTING");
        when(stmtMapper.queryById(GROUP_ID)).thenReturn(group);
        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(group));
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket(List.of(true)));
        when(autoJobMapper.queryByDependOnGroupId(GROUP_ID)).thenReturn(null);
        when(ghostService.findTaskForTicketGroup(TICKET_ID, GROUP_ID))
            .thenReturn(ghostTask(AsyncTaskStatus.RUNNING, "session-live"));

        try {
            service.retryGroup(PUID, ACTOR, GROUP_ID);
            fail("Expected ErrorMessageException");
        } catch (ErrorMessageException expected) {
            // GOV_TICKET_V2_GHOST_TASK_ACTIVE
        }
        verify(stmtMapper, never()).updateExecStatus(anyLong(), anyString(), any());
        verify(ghostService, never()).executeForTicket(anyString(), anyString(), any(), anyLong(), anyLong(), any());
        verify(ghostService, never()).close(anyString(), anyString(), anyString());
    }

    @Test
    public void retryGhost_activeTaskStates_allReject() {
        // the extended active check covers the ruling's set plus the transient/unstarted states
        for (AsyncTaskStatus status : List.of(AsyncTaskStatus.WAIT_START, AsyncTaskStatus.RUNNING,
                AsyncTaskStatus.BLOCK, AsyncTaskStatus.PAUSE, AsyncTaskStatus.INIT,
                AsyncTaskStatus.PAUSING, AsyncTaskStatus.CANCELING)) {
            setUp();
            DmTicketDbStmtDO group = group("EXECUTING");
            when(stmtMapper.queryById(GROUP_ID)).thenReturn(group);
            when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(group));
            when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket(List.of(true)));
            when(autoJobMapper.queryByDependOnGroupId(GROUP_ID)).thenReturn(null);
            when(ghostService.findTaskForTicketGroup(TICKET_ID, GROUP_ID)).thenReturn(ghostTask(status, "s"));

            try {
                service.retryGroup(PUID, ACTOR, GROUP_ID);
                fail("Expected rejection for task status " + status);
            } catch (ErrorMessageException expected) {
                // ok
            }
        }
    }

    @Test
    public void retryGhost_noPreviousTask_dispatchesWithoutClose() {
        DmTicketDbStmtDO group = group("FAILED");
        when(stmtMapper.queryById(GROUP_ID)).thenReturn(group);
        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(group));
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket(List.of(true)));
        when(autoJobMapper.queryByDependOnGroupId(GROUP_ID)).thenReturn(null);
        when(ghostService.findTaskForTicketGroup(TICKET_ID, GROUP_ID)).thenReturn(null);

        service.retryGroup(PUID, ACTOR, GROUP_ID);

        verify(ghostService, never()).close(anyString(), anyString(), anyString());
        verify(ghostService).executeForTicket(eq(PUID), eq(OWNER), any(), eq(TICKET_ID), eq(GROUP_ID), isNull());
        verify(stmtMapper).updateExecStatus(GROUP_ID, "EXECUTING", null);
    }

    @Test
    public void retryGhost_oldSessionCleanupFailure_neverBlocksRebuild() {
        DmTicketDbStmtDO group = group("FAILED");
        when(stmtMapper.queryById(GROUP_ID)).thenReturn(group);
        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(group));
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket(List.of(true)));
        when(autoJobMapper.queryByDependOnGroupId(GROUP_ID)).thenReturn(null);
        when(ghostService.findTaskForTicketGroup(TICKET_ID, GROUP_ID))
            .thenReturn(ghostTask(AsyncTaskStatus.CANCEL, "session-dead"));
        doThrow(new RuntimeException("sidecar gone")).when(ghostService).close(anyString(), anyString(), anyString());

        service.retryGroup(PUID, ACTOR, GROUP_ID); // must not throw

        verify(ghostService).executeForTicket(eq(PUID), eq(OWNER), any(), eq(TICKET_ID), eq(GROUP_ID), isNull());
        verify(stmtMapper).updateExecStatus(GROUP_ID, "EXECUTING", null);
    }

    @Test
    public void retryGhost_secondPositionGroup_positionalFlagLookup() {
        // group 100 = plain (flag false), group 101 = ghost (flag true) — id-ASC positional contract
        DmTicketDbStmtDO plain = group("SUCCESS");
        plain.setId(GROUP_ID);
        DmTicketDbStmtDO ghost = group("FAILED");
        ghost.setId(GROUP_ID + 1);
        when(stmtMapper.queryById(GROUP_ID + 1)).thenReturn(ghost);
        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(plain, ghost));
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket(List.of(false, true)));
        when(ghostService.findTaskForTicketGroup(TICKET_ID, GROUP_ID + 1)).thenReturn(null);

        service.retryGroup(PUID, ACTOR, GROUP_ID + 1);

        verify(ghostService).executeForTicket(eq(PUID), eq(OWNER), any(), eq(TICKET_ID), eq(GROUP_ID + 1), isNull());
        verify(autoExecService, never()).createGroupJob(any(), anyString(), anyBoolean(), any(), anyString(), anyString());
    }

    @Test
    public void retry_unmarkedGroup_keepsTheAutoexecPath_regressionPin() {
        DmTicketDbStmtDO group = group("FAILED");
        when(stmtMapper.queryById(GROUP_ID)).thenReturn(group);
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket(null)); // legacy: no flags at all

        service.retryGroup(PUID, ACTOR, GROUP_ID);

        verify(ghostService, never()).findTaskForTicketGroup(anyLong(), anyLong());
        verify(ghostService, never()).executeForTicket(anyString(), anyString(), any(), anyLong(), anyLong(), any());
        verify(autoExecService).createGroupJob(eq(group), anyString(), anyBoolean(), any(), anyString(), eq(ACTOR));
        verify(autoExecService).startJob(anyString(), eq(ACTOR));
        verify(stmtMapper).updateExecStatus(GROUP_ID, "PENDING", null);
        verify(approvalStateService).markExecutionDispatched("biz-900");
    }

    @Test
    public void retry_falseFlagGroup_keepsTheAutoexecPath_regressionPin() {
        DmTicketDbStmtDO group = group("FAILED");
        when(stmtMapper.queryById(GROUP_ID)).thenReturn(group);
        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(group));
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket(List.of(false)));

        service.retryGroup(PUID, ACTOR, GROUP_ID);

        verify(ghostService, never()).executeForTicket(anyString(), anyString(), any(), anyLong(), anyLong(), any());
        verify(autoExecService).createGroupJob(eq(group), anyString(), anyBoolean(), any(), anyString(), eq(ACTOR));
    }

    // ==================== B6 M2: dispatchGhostSegment (the in-chain single-segment launch) ====================

    @Test
    public void dispatchSegment_launchesWithSegmentSqlAndIndex_recordsSessionOnSegment_audits() {
        DmApprovalDO ticket = ticket(List.of(true));
        DmTicketDbStmtDO group = group("EXECUTING"); // mid-chain: the group is already EXECUTING
        com.clougence.clouddm.console.web.component.governance.TicketSegment seg
            = com.clougence.clouddm.console.web.component.governance.TicketSegment
                .ghostPlan(2, 3, "t_big", 20_000_000L, "ALTER TABLE t_big ADD COLUMN c1 INT");
        seg.setStatus(com.clougence.clouddm.console.web.component.governance.TicketSegment.STATUS_EXECUTING);

        service.dispatchGhostSegment(ticket, group, seg, GovTicketV2Service.GHOST_VIA_SEGMENT_CHAIN);

        // the launch carries the SEGMENT's frozen sql (never the group fulltext) + the segIdx binding
        ArgumentCaptor<com.clougence.clouddm.console.web.model.fo.ghost.GhostExecuteFO> foCaptor
            = ArgumentCaptor.forClass(com.clougence.clouddm.console.web.model.fo.ghost.GhostExecuteFO.class);
        verify(ghostService).executeForTicket(eq(PUID), eq(OWNER), foCaptor.capture(),
            eq(TICKET_ID), eq(GROUP_ID), eq(Integer.valueOf(2)));
        assertEquals("ALTER TABLE t_big ADD COLUMN c1 INT", foCaptor.getValue().getDdlSql());
        assertNull("ticket scene carries NO task options — the admin merge chain applies",
            foCaptor.getValue().getOptions());

        // sessionId/taskId land ON the segment instance (the caller persists the runtime — single writer)
        assertEquals("session-new", seg.getSessionId());

        // group row EXECUTING (idempotent mid-chain rewrite) + snapshot capture
        verify(stmtMapper).updateExecStatus(GROUP_ID, "EXECUTING", null);
        verify(schemaSnapshotService).captureIfMissing(group, TICKET_ID);

        // audit: submitter subject, segment position + chain via
        ArgumentCaptor<Object> objCaptor = ArgumentCaptor.forClass(Object.class);
        verify(rdpOpAuditService).logAndAddOperationAudit(eq(PUID), eq(OWNER), anyString(), eq(null),
            eq(DS_ID), objCaptor.capture(), eq(SecurityLevel.HIGH), eq(AuditType.GHOST_EXECUTE),
            eq(ResourceType.DATASOURCE));
        GhostOpAuditObj auditObj = (GhostOpAuditObj) objCaptor.getValue();
        assertEquals(Long.valueOf(TICKET_ID), auditObj.getTicketId());
        assertEquals(Long.valueOf(GROUP_ID), auditObj.getGroupId());
        assertEquals(Integer.valueOf(2), auditObj.getSegmentIndex());
        assertEquals(GovTicketV2Service.GHOST_VIA_SEGMENT_CHAIN, auditObj.getVia());
    }

    @Test
    public void dispatchSegment_auditFailure_neverBreaksTheDispatch() {
        doThrow(new RuntimeException("audit store down")).when(rdpOpAuditService).logAndAddOperationAudit(
            anyString(), anyString(), anyString(), any(), any(), any(), any(), any(), any());
        com.clougence.clouddm.console.web.component.governance.TicketSegment seg
            = com.clougence.clouddm.console.web.component.governance.TicketSegment
                .ghostPlan(1, 1, "t_big", 1L, "ALTER TABLE t_big ADD COLUMN c1 INT");

        service.dispatchGhostSegment(ticket(List.of(true)), group("PENDING"), seg,
            GovTicketV2Service.GHOST_VIA_AUTO_ROUTE); // must not throw

        verify(stmtMapper).updateExecStatus(GROUP_ID, "EXECUTING", null);
    }

    // ==================== helpers ====================

    private DmApprovalDO ticket(List<Boolean> ghostFlags) {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setBizId("biz-900");
        ticket.setPrimaryUid(PUID);
        ticket.setOwnerUid(OWNER);
        ApprovalMO mo = new ApprovalMO();
        mo.setTicketType("PRE_DDL");
        mo.setGhostRouteFlags(ghostFlags);
        ticket.setTicketInfo(JsonUtils.toJson(mo));
        return ticket;
    }

    private DmTicketDbStmtDO group(String execStatus) {
        DmTicketDbStmtDO group = new DmTicketDbStmtDO();
        group.setId(GROUP_ID);
        group.setTicketId(TICKET_ID);
        group.setPairId(300L);
        group.setDsId(DS_ID);
        group.setDbName(DB);
        group.setSqlContent(ALTER);
        group.setExecStatus(execStatus);
        return group;
    }

    private static DmExecAsyncTaskDO ghostTask(AsyncTaskStatus status, String sessionId) {
        DmExecAsyncTaskDO task = new DmExecAsyncTaskDO();
        task.setId(77L);
        task.setBizId(sessionId);
        task.setBizType("GHOST");
        task.setStatus(status);
        return task;
    }
}
