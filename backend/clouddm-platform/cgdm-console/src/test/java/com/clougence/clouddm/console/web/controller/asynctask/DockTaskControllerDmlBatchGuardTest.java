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
package com.clougence.clouddm.console.web.controller.asynctask;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.api.common.rpc.ResWebData;
import com.clougence.clouddm.console.web.component.execute.AsyncTaskService;
import com.clougence.clouddm.console.web.model.fo.asyntask.ActionAsyncTaskFO;
import com.clougence.clouddm.console.web.service.asyntask.AsyncTaskServiceService;
import com.clougence.clouddm.console.web.service.auth.RdpUserService;
import com.clougence.clouddm.console.web.service.dmlbatch.DmlBatchService;
import com.clougence.clouddm.console.web.service.dmlbatch.asyntask.DmlBatchAsyncTaskConfig;
import com.clougence.clouddm.platform.dal.access.ExecutionDal;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecAsyncTaskMapper;
import com.clougence.clouddm.platform.dal.model.execution.AsyncTaskStatus;
import com.clougence.clouddm.platform.dal.model.execution.DmExecAsyncTaskDO;
import com.clougence.utils.JsonUtils;

import jakarta.servlet.http.HttpServletRequest;

/**
 * B7 N2 dock guard (design §6.5 — STRICTER than the B6 ghost three): a ticket-bound DML_BATCH
 * task rejects ALL FOUR dock operations (pause / cancel / retry AND resume). The ghost resume
 * stays deliberately unguarded (its pause is a tool-level throttle with the tracker still
 * polling); the B7 pause is the framework isPause state machine whose SINGLE legitimate entry is
 * the ticket-chain /dmlBatch control endpoint (DM_GHOST_EXEC-gated, event-trailed, segment-state
 * validated) — a dock/ticket-chain dual entry would interleave untrailed resumes with the
 * trail-bearing ones (单入口原则). Standalone DML_BATCH tasks do not exist in v1 (the B8 seam
 * keeps the binding nullable — fail-open on an unparsable/missing binding is the B6 discipline
 * verbatim). The ghost predicate's behavior is untouched (its own frozen suite pins it).
 * <p>
 * Discipline: JUnit4 + hand-written mocks + ReflectionTestUtils — the controller method is
 * invoked directly (no MockMvc / Spring context).
 */
public class DockTaskControllerDmlBatchGuardTest {

    private DockTaskController        controller;
    private DmExecAsyncTaskMapper     asyncTaskMapper;
    private AsyncTaskService          scheduleService;
    private HttpServletRequest        request;

    private static final String UID     = "uid-op";
    private static final long   TASK_ID = 88L;

    /** per-test task swap (the queryByIdAndOwnerUid answer reads it) */
    private DmExecAsyncTaskDO currentTask = ticketBoundBatchTask();

    @Before
    public void setUp() {
        controller = new DockTaskController();
        ExecutionDal executionDal = mock(ExecutionDal.class);
        asyncTaskMapper = mock(DmExecAsyncTaskMapper.class);
        when(executionDal.asyncTaskMapper()).thenReturn(asyncTaskMapper);
        scheduleService = mock(AsyncTaskService.class);
        ReflectionTestUtils.setField(controller, "executionDal", executionDal);
        ReflectionTestUtils.setField(controller, "scheduleService", scheduleService);
        ReflectionTestUtils.setField(controller, "asyncTaskService", mock(AsyncTaskServiceService.class));

        request = mock(HttpServletRequest.class);
        when(request.getAttribute(RdpUserService.UID)).thenReturn(UID);
        when(asyncTaskMapper.queryByIdAndOwnerUid(TASK_ID, UID)).thenAnswer(inv -> currentTask);
    }

    // ==================== the four rejections ====================

    @Test
    public void pause_ticketBoundBatchTask_rejected_schedulerNeverReached() {
        ResWebData<?> res = controller.pauseTask(fo(), request);

        assertFalse(res.isSuccess());
        verifyNoInteractions(scheduleService);
    }

    @Test
    public void cancel_ticketBoundBatchTask_rejected_schedulerNeverReached() {
        ResWebData<?> res = controller.cancelTask(fo(), request);

        assertFalse(res.isSuccess());
        verifyNoInteractions(scheduleService);
    }

    @Test
    public void retry_ticketBoundBatchTask_rejected_schedulerNeverReached() {
        ResWebData<?> res = controller.retryTask(fo(), request);

        assertFalse(res.isSuccess());
        verifyNoInteractions(scheduleService);
    }

    @Test
    public void resume_ticketBoundBatchTask_rejected_stricterThanGhost() {
        // the B7 difference to B6: resume joins the guard (the isPause state machine keeps a
        // SINGLE entry — the ticket-chain resume endpoint with its DML_BATCH_RESUME event trail)
        ResWebData<?> res = controller.resumeTask(fo(), request);

        assertFalse(res.isSuccess());
        verifyNoInteractions(scheduleService);
    }

    // ==================== fail-open + non-target shapes ====================

    @Test
    public void guard_unparsableConfigData_failOpen_legacyDockBehavior() {
        DmExecAsyncTaskDO broken = batchTask(new DmlBatchAsyncTaskConfig());
        broken.setConfigData("{not json");
        currentTask = broken;

        assertTrue(controller.pauseTask(fo(), request).isSuccess());
        verify(scheduleService).pauseTask(eq(TASK_ID), anyString());
    }

    @Test
    public void guard_standaloneBatchTask_noBinding_allowed() {
        // the B8 seam: a task WITHOUT the ticket binding keeps the legacy dock behavior
        // (v1 creates none — structural fail-open, pinned for the seam's honesty)
        currentTask = batchTask(new DmlBatchAsyncTaskConfig());

        assertTrue(controller.resumeTask(fo(), request).isSuccess());
        verify(scheduleService).resumeTask(eq(TASK_ID), anyString());
    }

    @Test
    public void guard_otherBizType_allowed_unchanged() {
        currentTask = fakerTask();

        assertTrue(controller.pauseTask(fo(), request).isSuccess());
        assertTrue(controller.resumeTask(fo(), request).isSuccess());
        verify(scheduleService).pauseTask(eq(TASK_ID), anyString());
        verify(scheduleService).resumeTask(eq(TASK_ID), anyString());
    }

    @Test
    public void guard_ticketBoundGhostTask_notCaughtByDmlPredicate_ghostResumeStillAllowed() {
        // the two predicates are bizType-disjoint: the ghost resume keeps its B6 unguarded
        // behavior (the ghost three-op guard is pinned by the frozen B6 suite)
        DmExecAsyncTaskDO ghost = new DmExecAsyncTaskDO();
        ghost.setId(TASK_ID);
        ghost.setBizId("ghost-session-1");
        ghost.setBizType("GHOST");
        ghost.setStatus(AsyncTaskStatus.PAUSE);
        ghost.setUid(UID);
        ghost.setConfigData("{\"ticketId\":900,\"groupId\":100,\"segmentIndex\":2}");
        currentTask = ghost;

        assertFalse(DockTaskController.isTicketBoundDmlBatchTask(ghost));
        assertTrue(controller.resumeTask(fo(), request).isSuccess());
        verify(scheduleService).resumeTask(eq(TASK_ID), anyString());
    }

    // ==================== predicate unit ====================

    @Test
    public void predicate_matrix() {
        assertFalse(DockTaskController.isTicketBoundDmlBatchTask(null));
        assertFalse(DockTaskController.isTicketBoundDmlBatchTask(fakerTask()));
        assertFalse(DockTaskController.isTicketBoundDmlBatchTask(batchTask(new DmlBatchAsyncTaskConfig())));
        assertTrue(DockTaskController.isTicketBoundDmlBatchTask(ticketBoundBatchTask()));
        DmExecAsyncTaskDO blank = batchTask(null);
        blank.setConfigData("  ");
        assertFalse(DockTaskController.isTicketBoundDmlBatchTask(blank));
    }

    // ==================== helpers ====================

    private DmExecAsyncTaskDO ticketBoundBatchTask() {
        DmlBatchAsyncTaskConfig cfg = new DmlBatchAsyncTaskConfig();
        cfg.setTicketId(900L);
        cfg.setGroupId(100L);
        cfg.setSegmentIndex(1);
        return batchTask(cfg);
    }

    private DmExecAsyncTaskDO batchTask(DmlBatchAsyncTaskConfig cfg) {
        DmExecAsyncTaskDO task = new DmExecAsyncTaskDO();
        task.setId(TASK_ID);
        task.setBizId("batch-biz-1");
        task.setBizType(DmlBatchService.BIZ_TYPE);
        task.setStatus(AsyncTaskStatus.RUNNING);
        task.setUid(UID);
        task.setConfigData(cfg == null ? null : JsonUtils.toJson(cfg));
        return task;
    }

    private DmExecAsyncTaskDO fakerTask() {
        DmExecAsyncTaskDO task = new DmExecAsyncTaskDO();
        task.setId(TASK_ID);
        task.setBizId("faker-session-1");
        task.setBizType("FAKER");
        task.setStatus(AsyncTaskStatus.RUNNING);
        task.setUid(UID);
        task.setConfigData("{}");
        return task;
    }

    private static ActionAsyncTaskFO fo() {
        ActionAsyncTaskFO fo = new ActionAsyncTaskFO();
        fo.setTaskId(TASK_ID);
        return fo;
    }
}
