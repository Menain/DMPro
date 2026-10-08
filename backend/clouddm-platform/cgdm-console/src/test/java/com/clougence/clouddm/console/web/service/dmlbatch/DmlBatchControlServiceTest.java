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
package com.clougence.clouddm.console.web.service.dmlbatch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.console.web.component.execute.AsyncTaskService;
import com.clougence.clouddm.console.web.component.governance.TicketSegment;
import com.clougence.clouddm.console.web.service.dmlbatch.asyntask.DmlBatchAsyncTaskConfig;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.DbChangeEventDal;
import com.clougence.clouddm.platform.dal.access.TicketDbStmtDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangeEventMapper;
import com.clougence.clouddm.platform.dal.mapper.govticket.DmTicketDbStmtMapper;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO;
import com.clougence.clouddm.platform.dal.model.dbchange.GovEventType;
import com.clougence.clouddm.platform.dal.model.execution.AsyncTaskStatus;
import com.clougence.clouddm.platform.dal.model.execution.DmExecAsyncTaskDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;
import com.clougence.utils.JsonUtils;

/**
 * B7 N2 (implement.md 单测矩阵「控制端点:归属/段状态/事件留痕」): the ticket-chain control plane
 * of a DML_BATCH task (design §6.1/§6.2/§6.4) — the validation order (tenant uniform-not-found →
 * group belongs → task located → segment EXECUTING → status admits the operation), the framework
 * operation delegation (pauseTask/resumeTask/cancelTask — the batch-granularity semantics live in
 * the tick loop, this service only flips the framework state), the host-affinity translation
 * (framework IllegalStateException → DML_BATCH_TASK_REMOTE_RUNNING guidance, never a raw 500),
 * and the event trail (DML_BATCH_PAUSE/RESUME/ABORT — operator = the real user, best-effort
 * AFTER the operation: a trail failure never reverts the control action).
 * <p>
 * Discipline: JUnit4 + hand-written mocks + ReflectionTestUtils; NO Spring.
 */
public class DmlBatchControlServiceTest {

    private DmlBatchControlService   service;
    private DmApprovalMapper         approvalMapper;
    private DmTicketDbStmtMapper     stmtMapper;
    private DmDbChangeEventMapper    eventMapper;
    private AsyncTaskService         asyncTaskService;
    private DmlBatchService          dmlBatchService;

    private static final long   TICKET_ID = 900L;
    private static final long   GROUP_ID  = 100L;
    private static final long   TASK_ID   = 55L;
    private static final String PUID      = "puid-001";
    private static final String UID       = "uid-dba";
    private static final String DB        = "prod_db";

    private DmExecAsyncTaskDO currentTask;

    @Before
    public void setUp() {
        service = new DmlBatchControlService();

        ApprovalDal approvalDal = mock(ApprovalDal.class);
        approvalMapper = mock(DmApprovalMapper.class);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);
        TicketDbStmtDal ticketDbStmtDal = mock(TicketDbStmtDal.class);
        stmtMapper = mock(DmTicketDbStmtMapper.class);
        when(ticketDbStmtDal.stmtMapper()).thenReturn(stmtMapper);
        DbChangeEventDal dbChangeEventDal = mock(DbChangeEventDal.class);
        eventMapper = mock(DmDbChangeEventMapper.class);
        when(dbChangeEventDal.eventMapper()).thenReturn(eventMapper);
        asyncTaskService = mock(AsyncTaskService.class);
        dmlBatchService = mock(DmlBatchService.class);

        ReflectionTestUtils.setField(service, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(service, "ticketDbStmtDal", ticketDbStmtDal);
        ReflectionTestUtils.setField(service, "dbChangeEventDal", dbChangeEventDal);
        ReflectionTestUtils.setField(service, "asyncTaskService", asyncTaskService);
        ReflectionTestUtils.setField(service, "dmlBatchService", dmlBatchService);

        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setBizId("biz-900");
        ticket.setPrimaryUid(PUID);
        ticket.setOwnerUid("uid-submitter");
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket);

        when(stmtMapper.queryById(GROUP_ID)).thenAnswer(inv -> executingGroup());
        when(dmlBatchService.findTaskForTicketGroup(eq(TICKET_ID), eq(GROUP_ID), any()))
            .thenAnswer(inv -> currentTask);
        currentTask = task(AsyncTaskStatus.RUNNING);
    }

    // ==================== fixtures ====================

    private static DmTicketDbStmtDO executingGroup() {
        TicketSegment seg = new TicketSegment();
        seg.setSegIdx(1);
        seg.setType(TicketSegment.TYPE_DML_BATCH);
        seg.setStatus(TicketSegment.STATUS_EXECUTING);
        seg.setTaskId(TASK_ID);
        seg.setTable("t_order");
        DmTicketDbStmtDO group = new DmTicketDbStmtDO();
        group.setId(GROUP_ID);
        group.setTicketId(TICKET_ID);
        group.setDsId(20L);
        group.setDbName(DB);
        group.setExecStatus("EXECUTING");
        group.setExecSegments(JsonUtils.toJson(List.of(seg)));
        return group;
    }

    private static DmExecAsyncTaskDO task(AsyncTaskStatus status) {
        DmlBatchAsyncTaskConfig cfg = new DmlBatchAsyncTaskConfig();
        cfg.setBizId("batch-biz");
        cfg.setTicketId(TICKET_ID);
        cfg.setGroupId(GROUP_ID);
        cfg.setSegmentIndex(1);
        DmExecAsyncTaskDO task = new DmExecAsyncTaskDO();
        task.setId(TASK_ID);
        task.setBizId("batch-biz");
        task.setBizType(DmlBatchService.BIZ_TYPE);
        task.setStatus(status);
        task.setConfigData(JsonUtils.toJson(cfg));
        return task;
    }

    private DmDbChangeEventDO capturedEvent() {
        ArgumentCaptor<DmDbChangeEventDO> captor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(captor.capture());
        return captor.getValue();
    }

    // ==================== pause ====================

    @Test
    public void pause_runningTask_frameworkPause_eventTrailed() {
        service.pause(PUID, UID, TICKET_ID, GROUP_ID, 1);

        verify(asyncTaskService).pauseTask(eq(TASK_ID), anyString());
        DmDbChangeEventDO event = capturedEvent();
        assertEquals(GovEventType.DML_BATCH_PAUSE.name(), event.getEventType());
        assertEquals(TICKET_ID, event.getTicketId().longValue());
        assertEquals(UID, event.getOperatorUid());
        assertTrue(event.getEventData().contains("\"taskId\":" + TASK_ID));
        assertTrue(event.getEventData().contains("\"segmentIndex\":1"));
        assertTrue(event.getEventData().contains("batch-biz"));
    }

    @Test
    public void pause_blockTask_alsoAdmitted() {
        currentTask = task(AsyncTaskStatus.BLOCK);

        service.pause(PUID, UID, TICKET_ID, GROUP_ID, null);

        verify(asyncTaskService).pauseTask(eq(TASK_ID), anyString());
    }

    @Test
    public void pause_alreadyPaused_stateInvalid_noFrameworkOp() {
        currentTask = task(AsyncTaskStatus.PAUSE);

        try {
            service.pause(PUID, UID, TICKET_ID, GROUP_ID, 1);
            fail("expected the state-invalid rejection");
        } catch (ErrorMessageException expected) {
            assertNotNull(expected.getMessage());
        }
        verifyNoInteractions(asyncTaskService);
        verify(eventMapper, never()).insert(any(DmDbChangeEventDO.class));
    }

    @Test
    public void pause_waitStart_transientReject_noSilentNoop() {
        // the framework pauseTask has NO WAIT_START transition (silent no-op) — the honest
        // answer is the state-invalid rejection ("retry in a moment"), never a fake success
        currentTask = task(AsyncTaskStatus.WAIT_START);

        try {
            service.pause(PUID, UID, TICKET_ID, GROUP_ID, 1);
            fail("expected the state-invalid rejection");
        } catch (ErrorMessageException expected) {
            assertNotNull(expected.getMessage());
        }
        verifyNoInteractions(asyncTaskService);
    }

    @Test
    public void pause_terminalTask_stateInvalid() {
        currentTask = task(AsyncTaskStatus.COMPLETE);

        try {
            service.pause(PUID, UID, TICKET_ID, GROUP_ID, 1);
            fail("expected the state-invalid rejection");
        } catch (ErrorMessageException expected) {
            assertNotNull(expected.getMessage());
        }
        verifyNoInteractions(asyncTaskService);
    }

    @Test
    public void pause_runningOnAnotherConsole_remoteRunningGuidance() {
        doThrow(new IllegalStateException("the pause failed, may be the task running on another console."))
            .when(asyncTaskService).pauseTask(anyLong(), anyString());

        try {
            service.pause(PUID, UID, TICKET_ID, GROUP_ID, 1);
            fail("expected the host-affinity translation");
        } catch (ErrorMessageException expected) {
            assertNotNull(expected.getMessage()); // DML_BATCH_TASK_REMOTE_RUNNING copy
        }
        verify(eventMapper, never()).insert(any(DmDbChangeEventDO.class)); // the operation did NOT succeed — no trail
    }

    // ==================== resume ====================

    @Test
    public void resume_pausedTask_frameworkResume_eventTrailed() {
        currentTask = task(AsyncTaskStatus.PAUSE);

        service.resume(PUID, UID, TICKET_ID, GROUP_ID, 1);

        verify(asyncTaskService).resumeTask(eq(TASK_ID), anyString());
        assertEquals(GovEventType.DML_BATCH_RESUME.name(), capturedEvent().getEventType());
    }

    @Test
    public void resume_notPaused_stateInvalid() {
        currentTask = task(AsyncTaskStatus.RUNNING);

        try {
            service.resume(PUID, UID, TICKET_ID, GROUP_ID, 1);
            fail("expected the state-invalid rejection");
        } catch (ErrorMessageException expected) {
            assertNotNull(expected.getMessage());
        }
        verifyNoInteractions(asyncTaskService);
    }

    // ==================== stop ====================

    @Test
    public void stop_runningTask_frameworkCancel_abortEventTrailed() {
        service.stop(PUID, UID, TICKET_ID, GROUP_ID, 1);

        verify(asyncTaskService).cancelTask(eq(TASK_ID), anyString());
        assertEquals(GovEventType.DML_BATCH_ABORT.name(), capturedEvent().getEventType());
    }

    @Test
    public void stop_pausedTask_admitted_dbTransitionPath() {
        // cancelTask from PAUSE is a DB-state transition (works from ANY console — no affinity)
        currentTask = task(AsyncTaskStatus.PAUSE);

        service.stop(PUID, UID, TICKET_ID, GROUP_ID, 1);

        verify(asyncTaskService).cancelTask(eq(TASK_ID), anyString());
    }

    @Test
    public void stop_initTask_admitted() {
        currentTask = task(AsyncTaskStatus.INIT);

        service.stop(PUID, UID, TICKET_ID, GROUP_ID, 1);

        verify(asyncTaskService).cancelTask(eq(TASK_ID), anyString());
    }

    @Test
    public void stop_waitStart_rejected_frameworkHasNoTransition() {
        // cancelTask's switch has NO WAIT_START case (silent no-op → the task would still run) —
        // rejected honestly instead
        currentTask = task(AsyncTaskStatus.WAIT_START);

        try {
            service.stop(PUID, UID, TICKET_ID, GROUP_ID, 1);
            fail("expected the state-invalid rejection");
        } catch (ErrorMessageException expected) {
            assertNotNull(expected.getMessage());
        }
        verifyNoInteractions(asyncTaskService);
    }

    @Test
    public void stop_terminalTask_stateInvalid() {
        currentTask = task(AsyncTaskStatus.CANCEL);

        try {
            service.stop(PUID, UID, TICKET_ID, GROUP_ID, 1);
            fail("expected the state-invalid rejection");
        } catch (ErrorMessageException expected) {
            assertNotNull(expected.getMessage());
        }
        verifyNoInteractions(asyncTaskService);
    }

    // ==================== validation order ====================

    @Test
    public void anyOp_crossTenant_uniformNotFound_nothingTouched() {
        try {
            service.pause("other-tenant", UID, TICKET_ID, GROUP_ID, 1);
            fail("expected the uniform not-found");
        } catch (ErrorMessageException expected) {
            assertNotNull(expected.getMessage());
        }
        verifyNoInteractions(asyncTaskService);
        verifyNoInteractions(dmlBatchService);
    }

    @Test
    public void anyOp_ticketMissing_uniformNotFound() {
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(null);

        try {
            service.stop(PUID, UID, TICKET_ID, GROUP_ID, 1);
            fail("expected the uniform not-found");
        } catch (ErrorMessageException expected) {
            assertNotNull(expected.getMessage());
        }
        verifyNoInteractions(asyncTaskService);
    }

    @Test
    public void anyOp_groupOfAnotherTicket_groupNotFound() {
        DmTicketDbStmtDO foreign = executingGroup();
        foreign.setTicketId(999L);
        when(stmtMapper.queryById(GROUP_ID)).thenReturn(foreign);

        try {
            service.pause(PUID, UID, TICKET_ID, GROUP_ID, 1);
            fail("expected the group-not-found");
        } catch (ErrorMessageException expected) {
            assertNotNull(expected.getMessage());
        }
        verifyNoInteractions(asyncTaskService);
    }

    @Test
    public void anyOp_noBoundTask_taskNotFound() {
        when(dmlBatchService.findTaskForTicketGroup(eq(TICKET_ID), eq(GROUP_ID), any())).thenReturn(null);

        try {
            service.resume(PUID, UID, TICKET_ID, GROUP_ID, 1);
            fail("expected the task-not-found");
        } catch (ErrorMessageException expected) {
            assertNotNull(expected.getMessage());
        }
        verifyNoInteractions(asyncTaskService);
    }

    @Test
    public void anyOp_groupNotExecuting_segmentNotExecutingReject() {
        DmTicketDbStmtDO failed = executingGroup();
        failed.setExecStatus("FAILED");
        when(stmtMapper.queryById(GROUP_ID)).thenReturn(failed);

        try {
            service.stop(PUID, UID, TICKET_ID, GROUP_ID, 1);
            fail("expected the segment-not-executing rejection");
        } catch (ErrorMessageException expected) {
            assertNotNull(expected.getMessage());
        }
        verifyNoInteractions(asyncTaskService);
    }

    @Test
    public void anyOp_segmentTerminalWhileTaskListed_rejected_staleView() {
        TicketSegment seg = new TicketSegment();
        seg.setSegIdx(1);
        seg.setType(TicketSegment.TYPE_DML_BATCH);
        seg.setStatus(TicketSegment.STATUS_FAILED); // the sweep just judged it
        DmTicketDbStmtDO group = executingGroup();
        group.setExecSegments(JsonUtils.toJson(List.of(seg)));
        when(stmtMapper.queryById(GROUP_ID)).thenReturn(group);

        try {
            service.pause(PUID, UID, TICKET_ID, GROUP_ID, 1);
            fail("expected the segment-not-executing rejection");
        } catch (ErrorMessageException expected) {
            assertNotNull(expected.getMessage());
        }
        verifyNoInteractions(asyncTaskService);
    }

    @Test
    public void anyOp_corruptRuntime_rejected_defensive() {
        DmTicketDbStmtDO group = executingGroup();
        group.setExecSegments("{corrupt");
        when(stmtMapper.queryById(GROUP_ID)).thenReturn(group);

        try {
            service.pause(PUID, UID, TICKET_ID, GROUP_ID, 1);
            fail("expected the defensive rejection over an unparsable runtime");
        } catch (ErrorMessageException expected) {
            assertNotNull(expected.getMessage());
        }
        verifyNoInteractions(asyncTaskService);
    }

    // ==================== trail best-effort ====================

    @Test
    public void pause_eventInsertFails_controlActionStands_neverThrows() {
        when(eventMapper.insert(any(DmDbChangeEventDO.class))).thenThrow(new RuntimeException("event store down"));

        service.pause(PUID, UID, TICKET_ID, GROUP_ID, 1); // no exception escapes

        verify(asyncTaskService).pauseTask(eq(TASK_ID), anyString());
    }
}
