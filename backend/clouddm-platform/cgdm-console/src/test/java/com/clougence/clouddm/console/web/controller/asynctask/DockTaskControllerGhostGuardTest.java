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

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.api.common.rpc.ResWebData;
import com.clougence.clouddm.console.web.component.execute.AsyncTaskService;
import com.clougence.clouddm.console.web.model.fo.asyntask.ActionAsyncTaskFO;
import com.clougence.clouddm.console.web.service.asyntask.AsyncTaskServiceService;
import com.clougence.clouddm.console.web.service.auth.RdpUserService;
import com.clougence.clouddm.console.web.service.ghost.asyntask.GhostAsyncTaskConfig;
import com.clougence.clouddm.platform.dal.access.ExecutionDal;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecAsyncTaskMapper;
import com.clougence.clouddm.platform.dal.model.execution.AsyncTaskStatus;
import com.clougence.clouddm.platform.dal.model.execution.DmExecAsyncTaskDO;
import com.clougence.utils.JsonUtils;

import jakarta.servlet.http.HttpServletRequest;

/**
 * B6 M3 dock guard (M2 check 裁决项 1): a TICKET-BOUND GHOST task rejects dock pause/cancel —
 * its lifecycle belongs to the ticket chain (pause = throttle flag with the task still tracking;
 * stop = panic flag + deterministic writeback + audit). A raw dock pause would finish the task
 * over a throttled-alive migration and let the sweep judge "writeback lost" FAILED; a raw dock
 * cancel would kill the tracker without any ticket writeback. STANDALONE ghost tasks (no ticket
 * binding) and every other bizType keep the dock behavior byte-for-byte.
 * <p>
 * Discipline: JUnit4 + hand-written mocks + ReflectionTestUtils — the controller method is
 * invoked directly (no MockMvc / Spring context).
 */
public class DockTaskControllerGhostGuardTest {

    private DockTaskController        controller;
    private ExecutionDal              executionDal;
    private DmExecAsyncTaskMapper     asyncTaskMapper;
    private AsyncTaskService          scheduleService;
    private HttpServletRequest        request;

    private static final String UID      = "uid-op";
    private static final long   TASK_ID  = 77L;

    @Before
    public void setUp() {
        controller = new DockTaskController();
        executionDal = mock(ExecutionDal.class);
        asyncTaskMapper = mock(DmExecAsyncTaskMapper.class);
        when(executionDal.asyncTaskMapper()).thenReturn(asyncTaskMapper);
        scheduleService = mock(AsyncTaskService.class);
        ReflectionTestUtils.setField(controller, "executionDal", executionDal);
        ReflectionTestUtils.setField(controller, "scheduleService", scheduleService);
        ReflectionTestUtils.setField(controller, "asyncTaskService", mock(AsyncTaskServiceService.class));

        request = mock(HttpServletRequest.class);
        when(request.getAttribute(RdpUserService.UID)).thenReturn(UID);
        when(asyncTaskMapper.queryByIdAndOwnerUid(TASK_ID, UID)).thenAnswer(inv -> ownedTask());
    }

    /** per-test task swap (the queryByIdAndOwnerUid answer reads it) */
    private DmExecAsyncTaskDO currentTask = ticketBoundGhostTask();

    private DmExecAsyncTaskDO ownedTask() {
        return currentTask;
    }

    // ==================== pause ====================

    @Test
    public void pause_ticketBoundGhostTask_rejected_schedulerNeverReached() {
        ResWebData<?> res = controller.pauseTask(fo(), request);

        assertFalse(res.isSuccess());
        verifyNoInteractions(scheduleService);
    }

    @Test
    public void pause_standaloneGhostTask_allowed_unchanged() {
        currentTask = standaloneGhostTask();

        ResWebData<?> res = controller.pauseTask(fo(), request);

        assertTrue(res.isSuccess());
        verify(scheduleService).pauseTask(eq(TASK_ID), anyString());
    }

    @Test
    public void pause_otherBizType_allowed_unchanged() {
        currentTask = fakerTask();

        ResWebData<?> res = controller.pauseTask(fo(), request);

        assertTrue(res.isSuccess());
        verify(scheduleService).pauseTask(eq(TASK_ID), anyString());
    }

    // ==================== cancel ====================

    @Test
    public void cancel_ticketBoundGhostTask_rejected_schedulerNeverReached() {
        ResWebData<?> res = controller.cancelTask(fo(), request);

        assertFalse(res.isSuccess());
        verifyNoInteractions(scheduleService);
    }

    @Test
    public void cancel_standaloneGhostTask_allowed_unchanged() {
        currentTask = standaloneGhostTask();

        ResWebData<?> res = controller.cancelTask(fo(), request);

        assertTrue(res.isSuccess());
        verify(scheduleService).cancelTask(eq(TASK_ID), anyString());
    }

    @Test
    public void cancel_otherBizType_allowed_unchanged() {
        currentTask = fakerTask();

        ResWebData<?> res = controller.cancelTask(fo(), request);

        assertTrue(res.isSuccess());
        verify(scheduleService).cancelTask(eq(TASK_ID), anyString());
    }

    // ==================== guard edges ====================

    @Test
    public void guard_partialBinding_ticketIdOnly_treatedAsBound() {
        // executeForTicket always writes the full binding; a ticketId without groupId still keys
        // the writeback hook's scene, so the guard leans protective
        GhostAsyncTaskConfig cfg = new GhostAsyncTaskConfig();
        cfg.setTicketId(900L);
        currentTask = ghostTask(cfg);

        assertFalse(controller.pauseTask(fo(), request).isSuccess());
        verifyNoInteractions(scheduleService);
    }

    @Test
    public void guard_unparsableConfigData_failOpen_legacyDockBehavior() {
        DmExecAsyncTaskDO broken = ghostTask(null);
        broken.setConfigData("{not json");
        currentTask = broken;

        assertTrue(controller.pauseTask(fo(), request).isSuccess());
        verify(scheduleService).pauseTask(eq(TASK_ID), anyString());
    }

    @Test
    public void guard_taskNotOwned_existingNotFoundBehavior_unchanged() {
        when(asyncTaskMapper.queryByIdAndOwnerUid(TASK_ID, UID)).thenReturn(null);

        assertFalse(controller.pauseTask(fo(), request).isSuccess());
        assertFalse(controller.cancelTask(fo(), request).isSuccess());
        verifyNoInteractions(scheduleService);
    }

    // ==================== retry (guard extended by the M3 check) ====================

    @Test
    public void retry_ticketBoundGhostTask_rejected_schedulerNeverReached() {
        // the "a retry of a terminal task is harmless" claim does NOT hold: re-queueing the STALE
        // tracker of a rebuilt segment re-runs executeTask over its dead session, and the
        // session-lost writeback passes the rebuilt segment's EXECUTING CAS (keyed on segmentIndex,
        // not task identity) — falsely FAILing the live migration. The recovery entry of a
        // ticket-bound task is the ticket-chain retryGroup, never a dock retry.
        assertFalse(DockTaskController.isTicketBoundGhostTask(null));

        ResWebData<?> res = controller.retryTask(fo(), request);

        assertFalse(res.isSuccess());
        verifyNoInteractions(scheduleService);
    }

    @Test
    public void retry_standaloneGhostTask_allowed_unchanged() {
        currentTask = standaloneGhostTask();

        ResWebData<?> res = controller.retryTask(fo(), request);

        assertTrue(res.isSuccess());
        verify(scheduleService).retryTask(eq(TASK_ID), anyString());
    }

    @Test
    public void retry_otherBizType_allowed_unchanged() {
        currentTask = fakerTask();

        ResWebData<?> res = controller.retryTask(fo(), request);

        assertTrue(res.isSuccess());
        verify(scheduleService).retryTask(eq(TASK_ID), anyString());
    }

    // ==================== resume (deliberately unguarded) ====================

    @Test
    public void resume_ticketBoundGhostTask_allowed_throttleLiftByDesign() {
        // resume stays unguarded: re-entering a still-throttled migration lifts the throttle and
        // keeps tracking (the documented Faker-shape behavior of GhostAsyncTask — a correct,
        // desired action; no stale-writeback surface: the task keeps polling its LIVE session),
        // and PAUSE is unreachable for new ticket-bound tasks once pause is blocked
        ResWebData<?> res = controller.resumeTask(fo(), request);

        assertTrue(res.isSuccess());
        verify(scheduleService).resumeTask(eq(TASK_ID), anyString());
    }

    // ==================== helpers ====================

    private DmExecAsyncTaskDO ticketBoundGhostTask() {
        GhostAsyncTaskConfig cfg = new GhostAsyncTaskConfig();
        cfg.setTicketId(900L);
        cfg.setGroupId(100L);
        cfg.setSegmentIndex(2);
        return ghostTask(cfg);
    }

    private DmExecAsyncTaskDO standaloneGhostTask() {
        return ghostTask(new GhostAsyncTaskConfig()); // no ticket binding
    }

    private DmExecAsyncTaskDO ghostTask(GhostAsyncTaskConfig cfg) {
        DmExecAsyncTaskDO task = new DmExecAsyncTaskDO();
        task.setId(TASK_ID);
        task.setBizId("ghost-session-1");
        task.setBizType("GHOST");
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
