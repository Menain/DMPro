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
package com.clougence.clouddm.console.web.component.governance;

import static org.mockito.Mockito.*;

import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.console.web.service.governance.GovAutoAdvanceService;
import com.clougence.clouddm.console.web.service.governance.GovFailureNotifyService;
import com.clougence.clouddm.console.web.service.governance.RevisionFreezeService;

public class GovPipelineSchedulerTest {

    private GovPipelineScheduler scheduler;
    private GovAutoAdvanceService  advanceService;
    private RevisionFreezeService   freezeService;
    private GovFailureNotifyService  notifyService;

    @Before
    public void setUp() {
        scheduler = new GovPipelineScheduler();
        advanceService = mock(GovAutoAdvanceService.class);
        freezeService = mock(RevisionFreezeService.class);
        notifyService = mock(GovFailureNotifyService.class);
        ReflectionTestUtils.setField(scheduler, "govAutoAdvanceService", advanceService);
        ReflectionTestUtils.setField(scheduler, "revisionFreezeService", freezeService);
        ReflectionTestUtils.setField(scheduler, "govFailureNotifyService", notifyService);
    }

    @Test
    public void doSchedule_advanceThrows_freezeAndNotifyStillRun() throws Exception {
        doThrow(new RuntimeException("advance error"))
            .when(advanceService).advancePreTickets();

        invokeDoSchedule();

        verify(freezeService).freezeFinishedRevisions();
        verify(notifyService).scanAndNotify();
    }

    @Test
    public void doSchedule_freezeThrows_advanceAndNotifyStillRun() throws Exception {
        doThrow(new RuntimeException("freeze error"))
            .when(freezeService).freezeFinishedRevisions();

        invokeDoSchedule();

        verify(advanceService).advancePreTickets();
        verify(notifyService).scanAndNotify();
    }

    @Test
    public void doSchedule_notifyThrows_advanceAndFreezeStillRun() throws Exception {
        doThrow(new RuntimeException("notify error"))
            .when(notifyService).scanAndNotify();

        invokeDoSchedule();

        verify(advanceService).advancePreTickets();
        verify(freezeService).freezeFinishedRevisions();
    }

    @Test
    public void doSchedule_allThrow_noExceptionEscapes() throws Exception {
        doThrow(new RuntimeException("advance error"))
            .when(advanceService).advancePreTickets();
        doThrow(new RuntimeException("freeze error"))
            .when(freezeService).freezeFinishedRevisions();
        doThrow(new RuntimeException("notify error"))
            .when(notifyService).scanAndNotify();

        invokeDoSchedule();

        verify(advanceService).advancePreTickets();
        verify(freezeService).freezeFinishedRevisions();
        verify(notifyService).scanAndNotify();
    }

    private void invokeDoSchedule() throws Exception {
        java.lang.reflect.Method method = GovPipelineScheduler.class.getDeclaredMethod("doSchedule");
        method.setAccessible(true);
        method.invoke(scheduler);
    }
}
