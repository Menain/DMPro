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
import com.clougence.clouddm.console.web.service.governance.RevisionFreezeService;

public class GovPipelineSchedulerTest {

    private GovPipelineScheduler scheduler;
    private GovAutoAdvanceService  advanceService;
    private RevisionFreezeService   freezeService;

    @Before
    public void setUp() {
        scheduler = new GovPipelineScheduler();
        advanceService = mock(GovAutoAdvanceService.class);
        freezeService = mock(RevisionFreezeService.class);
        ReflectionTestUtils.setField(scheduler, "govAutoAdvanceService", advanceService);
        ReflectionTestUtils.setField(scheduler, "revisionFreezeService", freezeService);
    }

    @Test
    public void doSchedule_advanceThrows_freezeStillRuns() throws Exception {
        doThrow(new RuntimeException("advance error"))
            .when(advanceService).advancePreTickets();

        // Invoke doSchedule via reflection (private method)
        java.lang.reflect.Method method = GovPipelineScheduler.class.getDeclaredMethod("doSchedule");
        method.setAccessible(true);
        method.invoke(scheduler);

        // freeze must still be called despite advance throwing
        verify(freezeService).freezeFinishedRevisions();
    }

    @Test
    public void doSchedule_freezeThrows_advanceStillRan() throws Exception {
        doThrow(new RuntimeException("freeze error"))
            .when(freezeService).freezeFinishedRevisions();

        java.lang.reflect.Method method = GovPipelineScheduler.class.getDeclaredMethod("doSchedule");
        method.setAccessible(true);
        method.invoke(scheduler);

        verify(advanceService).advancePreTickets();
    }

    @Test
    public void doSchedule_bothThrow_noExceptionEscapes() throws Exception {
        doThrow(new RuntimeException("advance error"))
            .when(advanceService).advancePreTickets();
        doThrow(new RuntimeException("freeze error"))
            .when(freezeService).freezeFinishedRevisions();

        java.lang.reflect.Method method = GovPipelineScheduler.class.getDeclaredMethod("doSchedule");
        method.setAccessible(true);
        // Should not throw
        method.invoke(scheduler);

        verify(advanceService).advancePreTickets();
        verify(freezeService).freezeFinishedRevisions();
    }
}
