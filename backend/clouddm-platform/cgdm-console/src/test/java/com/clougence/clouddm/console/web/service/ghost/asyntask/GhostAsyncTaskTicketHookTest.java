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
package com.clougence.clouddm.console.web.service.ghost.asyntask;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.console.web.component.execute.asyntask.InterruptedType;
import com.clougence.clouddm.console.web.component.execute.asyntask.ScheduleTask;
import com.clougence.clouddm.console.web.component.governance.GhostGroupCompletionService;
import com.clougence.clouddm.console.web.service.ghost.GhostService;
import com.clougence.clouddm.platform.dal.access.ExecutionDal;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecAsyncTaskMapper;
import com.clougence.clouddm.platform.dal.model.execution.DmExecAsyncTaskDO;
import com.clougence.clouddm.sdk.model.ghost.GhostStatusDTO;
import com.clougence.utils.JsonUtils;

/**
 * B6 M1 (Step 7a): the GhostAsyncTask terminal writeback hook — ticket-bound tasks drive the
 * group state machine (COMPLETED→success / FAILED→failure / ABORTED→failure, 终止=FAILED per R5),
 * the session-missing branch follows the R3 ruling (ticket form: unknown outcome → FAILED, never
 * the optimistic COMPLETE), the interrupt branch NEVER hooks (暂停不改工单/组状态; stop is owned by
 * the console endpoint + sweep), and standalone tasks (no binding) keep their behavior
 * byte-for-byte (dual-form compatibility). A throwing hook never breaks the task lifecycle.
 */
public class GhostAsyncTaskTicketHookTest {

    private GhostAsyncTask                task;
    private GhostService                  ghostService;
    private GhostGroupCompletionService   completion;

    private static final String SESSION_ID = "session-1";
    private static final String USER_ID    = "uid-launcher";
    private static final long   TICKET_ID  = 900L;
    private static final long   GROUP_ID   = 100L;

    @Before
    public void setUp() {
        task = new GhostAsyncTask();
        ghostService = mock(GhostService.class);
        completion = mock(GhostGroupCompletionService.class);

        ExecutionDal executionDal = mock(ExecutionDal.class);
        when(executionDal.asyncTaskMapper()).thenReturn(mock(DmExecAsyncTaskMapper.class));

        DmExecAsyncTaskDO taskDO = new DmExecAsyncTaskDO();
        taskDO.setId(77L);
        taskDO.setBizId(SESSION_ID);
        taskDO.setBizType("GHOST");
        taskDO.setShowInDock(false); // keeps DmGlobalEventBus out of the unit test

        ReflectionTestUtils.setField(task, "ghostService", ghostService);
        ReflectionTestUtils.setField(task, "ghostGroupCompletionService", completion);
        ReflectionTestUtils.setField(task, "executionDal", executionDal);
        ReflectionTestUtils.setField(task, "taskDO", taskDO);

        when(ghostService.hasSession(anyString(), anyString())).thenReturn(true);
    }

    // ==================== ticket-bound terminal branches ====================

    @Test
    public void completed_ticketBound_hooksSuccess_thenTaskFinishes() {
        when(ghostService.tailStatusForTask(USER_ID, SESSION_ID)).thenReturn(status("COMPLETED", null, null));

        run(ticketBoundConfig(null));

        verify(completion).onGhostTerminal(TICKET_ID, GROUP_ID, (Integer) null, true, null);
        assertEquals(ScheduleTask.TaskStatus.Finish, ReflectionTestUtils.getField(task, "status"));
        // deviation-6 closure: the chain owns the session lifecycle — the sidecar GHOST slot
        // (cap 2) is only returned on session close, so every ticket terminal releases
        verify(ghostService).releaseSessionQuietly(USER_ID, SESSION_ID);
    }

    @Test
    public void completed_ticketBoundWithSegmentIndex_hookCarriesTheBinding_m2() {
        // B6 M2: the configData segmentIndex rides into the writeback hook — the orchestrator's
        // segment CAS keys on the exact binding (multi-ghost-segment groups host one task each)
        when(ghostService.tailStatusForTask(USER_ID, SESSION_ID)).thenReturn(status("COMPLETED", null, null));
        GhostAsyncTaskConfig cfg = ticketBoundConfig(null);
        cfg.setSegmentIndex(2);

        run(cfg);

        verify(completion).onGhostTerminal(TICKET_ID, GROUP_ID, Integer.valueOf(2), true, null);
        assertEquals(ScheduleTask.TaskStatus.Finish, ReflectionTestUtils.getField(task, "status"));
    }

    @Test
    public void failed_ticketBound_hooksFailure_fastFailNoPause() {
        when(ghostService.tailStatusForTask(USER_ID, SESSION_ID))
            .thenReturn(status("FAILED", "FATAL binlog invalid", "FATAL binlog invalid"));

        run(ticketBoundConfig(null));

        verify(completion).onGhostTerminal(eq(TICKET_ID), eq(GROUP_ID), isNull(), eq(false), contains("FATAL binlog invalid"));
        assertEquals(ScheduleTask.TaskStatus.Failed, ReflectionTestUtils.getField(task, "status"));
        // failedTaskDoNotPause: fastFail → the dock task lands FAILURE (never a retryable PAUSE)
        assertEquals(Boolean.TRUE, ReflectionTestUtils.getField(task, "fastFail"));
        verify(ghostService).releaseSessionQuietly(USER_ID, SESSION_ID);
    }

    @Test
    public void aborted_ticketBound_hooksFailure_taskFinishesWithAbortResult() {
        // R5: 终止 = ABORTED → group FAILED (the dock task itself finishes carrying the reason)
        when(ghostService.tailStatusForTask(USER_ID, SESSION_ID))
            .thenReturn(status("ABORTED", null, "panic flag"));

        run(ticketBoundConfig(null));

        verify(completion).onGhostTerminal(eq(TICKET_ID), eq(GROUP_ID), isNull(), eq(false), startsWith("ABORTED"));
        assertEquals(ScheduleTask.TaskStatus.Finish, ReflectionTestUtils.getField(task, "status"));
        verify(ghostService).releaseSessionQuietly(USER_ID, SESSION_ID);
    }

    @Test
    public void sessionMissing_ticketBoundUnknown_judgedFailed_r3Ruling() {
        when(ghostService.hasSession(USER_ID, SESSION_ID)).thenReturn(false);

        run(ticketBoundConfig("RUNNING")); // last seen status is NOT failed → outcome unknown

        verify(completion).onGhostTerminal(eq(TICKET_ID), eq(GROUP_ID), isNull(), eq(false), contains("session lost"));
        assertEquals(ScheduleTask.TaskStatus.Failed, ReflectionTestUtils.getField(task, "status"));
        assertEquals(Boolean.TRUE, ReflectionTestUtils.getField(task, "fastFail"));
    }

    @Test
    public void sessionMissing_ticketBoundLastFailed_hooksFailure() {
        when(ghostService.hasSession(USER_ID, SESSION_ID)).thenReturn(false);

        run(ticketBoundConfig("FAILED"));

        verify(completion).onGhostTerminal(eq(TICKET_ID), eq(GROUP_ID), isNull(), eq(false), contains("ghost migration failed"));
        assertEquals(ScheduleTask.TaskStatus.Failed, ReflectionTestUtils.getField(task, "status"));
    }

    @Test
    public void hookThrows_taskLifecycleProceeds_isolation() {
        doThrow(new RuntimeException("db down")).when(completion)
            .onGhostTerminal(anyLong(), anyLong(), any(), anyBoolean(), any());
        when(ghostService.tailStatusForTask(USER_ID, SESSION_ID)).thenReturn(status("COMPLETED", null, null));

        run(ticketBoundConfig(null)); // must not throw

        assertEquals(ScheduleTask.TaskStatus.Finish, ReflectionTestUtils.getField(task, "status"));
    }

    @Test
    public void releaseThrows_taskLifecycleProceeds_isolation() {
        doThrow(new RuntimeException("sidecar down")).when(ghostService)
            .releaseSessionQuietly(anyString(), anyString());
        when(ghostService.tailStatusForTask(USER_ID, SESSION_ID)).thenReturn(status("COMPLETED", null, null));

        run(ticketBoundConfig(null)); // must not throw

        verify(completion).onGhostTerminal(TICKET_ID, GROUP_ID, (Integer) null, true, null);
        assertEquals(ScheduleTask.TaskStatus.Finish, ReflectionTestUtils.getField(task, "status"));
    }

    @Test
    public void sessionMissing_ticketBound_noReleaseAttempted_sessionAlreadyGone() {
        when(ghostService.hasSession(USER_ID, SESSION_ID)).thenReturn(false);

        run(ticketBoundConfig("RUNNING"));

        // the sidecar already dropped the session (its slot went with it) — nothing to release
        verify(ghostService, never()).releaseSessionQuietly(anyString(), anyString());
    }

    // ==================== interrupt branch: never hooks ====================

    @Test
    public void interruptedPause_neverHooks_groupStaysExecuting() {
        when(ghostService.tailStatusForTask(USER_ID, SESSION_ID)).thenReturn(status("RUNNING", null, null));
        interrupted(InterruptedType.PAUSE);

        run(ticketBoundConfig(null));

        // 暂停不改工单/组状态 — the throttled migration resumes through the dock
        verifyNoInteractions(completion);
        verify(ghostService).pauseSession(USER_ID, SESSION_ID);
        assertEquals(ScheduleTask.TaskStatus.Finish, ReflectionTestUtils.getField(task, "status"));
        // a paused migration is ALIVE (throttled) — the session must survive for the dock resume
        verify(ghostService, never()).releaseSessionQuietly(anyString(), anyString());
    }

    @Test
    public void interruptedCancel_neverHooks_stopWritebackOwnedByEndpointAndSweep() {
        when(ghostService.tailStatusForTask(USER_ID, SESSION_ID)).thenReturn(status("RUNNING", null, null));
        interrupted(InterruptedType.CANCEL);

        run(ticketBoundConfig(null));

        // the stop endpoint already wrote the group FAILED deterministically (or the sweep will)
        verifyNoInteractions(completion);
        verify(ghostService, never()).pauseSession(anyString(), anyString());
        // ticket form: the stop terminal releases the sidecar slot (no close button exists)
        verify(ghostService).releaseSessionQuietly(USER_ID, SESSION_ID);
    }

    @Test
    public void interruptedCancel_standalone_sessionKeptForLogForensics() {
        when(ghostService.tailStatusForTask(USER_ID, SESSION_ID)).thenReturn(status("RUNNING", null, null));
        interrupted(InterruptedType.CANCEL);

        run(standaloneConfig(null));

        // standalone keeps the post-stop session inspectable — /ghost/close owns the release
        verifyNoInteractions(completion);
        verify(ghostService, never()).releaseSessionQuietly(anyString(), anyString());
    }

    // ==================== standalone form: completely unchanged (dual-form compatibility) ====================

    @Test
    public void completed_standalone_neverTouchesTheTicketChain() {
        when(ghostService.tailStatusForTask(USER_ID, SESSION_ID)).thenReturn(status("COMPLETED", null, null));

        run(standaloneConfig(null));

        verifyNoInteractions(completion);
        // dual-form compatibility: the standalone session stays open (the /ghost/close button owns it)
        verify(ghostService, never()).releaseSessionQuietly(anyString(), anyString());
        assertEquals(ScheduleTask.TaskStatus.Finish, ReflectionTestUtils.getField(task, "status"));
    }

    @Test
    public void sessionMissing_standalone_optimisticFinish_regressionPin() {
        when(ghostService.hasSession(USER_ID, SESSION_ID)).thenReturn(false);

        run(standaloneConfig("COMPLETED")); // last status non-failed → the legacy optimistic COMPLETE

        verifyNoInteractions(completion);
        assertEquals(ScheduleTask.TaskStatus.Finish, ReflectionTestUtils.getField(task, "status"));
        // the optimistic branch never goes through failedTaskDoNotPause → no fastFail flag
        assertEquals(Boolean.FALSE, ReflectionTestUtils.getField(task, "fastFail"));
    }

    @Test
    public void failed_standalone_taskFailsWithoutHook() {
        when(ghostService.tailStatusForTask(USER_ID, SESSION_ID))
            .thenReturn(status("FAILED", null, "FATAL something"));

        run(standaloneConfig(null));

        verifyNoInteractions(completion);
        assertEquals(ScheduleTask.TaskStatus.Failed, ReflectionTestUtils.getField(task, "status"));
    }

    // ==================== helpers ====================

    private void run(GhostAsyncTaskConfig config) {
        ReflectionTestUtils.invokeMethod(task, "executeTask", 0, JsonUtils.toJson(config));
    }

    @SuppressWarnings("unchecked")
    private void interrupted(InterruptedType type) {
        AtomicReference<InterruptedType> ref =
            (AtomicReference<InterruptedType>) ReflectionTestUtils.getField(task, "interrupted");
        ref.set(type);
    }

    private static GhostAsyncTaskConfig ticketBoundConfig(String lastStatus) {
        GhostAsyncTaskConfig cfg = standaloneConfig(lastStatus);
        cfg.setTicketId(TICKET_ID);
        cfg.setGroupId(GROUP_ID);
        return cfg;
    }

    private static GhostAsyncTaskConfig standaloneConfig(String lastStatus) {
        GhostAsyncTaskConfig cfg = new GhostAsyncTaskConfig();
        cfg.setUserId(USER_ID);
        cfg.setSessionId(SESSION_ID);
        cfg.setDsId(9L);
        cfg.setDbName("db1");
        cfg.setTableName("t_order");
        cfg.setDdlSql("ALTER TABLE t_order ADD COLUMN c1 INT");
        cfg.setLastStatus(lastStatus);
        return cfg;
    }

    private static GhostStatusDTO status(String status, String message, String failReason) {
        GhostStatusDTO dto = new GhostStatusDTO();
        dto.setStatus(status);
        dto.setMessage(message);
        dto.setFailReason(failReason);
        return dto;
    }
}
