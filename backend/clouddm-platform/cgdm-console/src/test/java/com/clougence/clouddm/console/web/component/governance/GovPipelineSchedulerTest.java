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

/**
 * P5 trim: duties 2-5 (revision freeze / failure notify / promotion sync / PROD auto-confirm) were
 * removed with the legacy promotion chain. Only duty 1 (advancePreTickets, v2 PRE_DDL path) remains,
 * so the scheduler no longer exercises per-duty isolation — this test pins that the single duty is
 * invoked and a Throwable is swallowed by the loop guard.
 */
public class GovPipelineSchedulerTest {

    private GovPipelineScheduler  scheduler;
    private GovAutoAdvanceService advanceService;

    @Before
    public void setUp() {
        scheduler = new GovPipelineScheduler();
        advanceService = mock(GovAutoAdvanceService.class);
        ReflectionTestUtils.setField(scheduler, "govAutoAdvanceService", advanceService);
    }

    @Test
    public void doSchedule_invokesAdvancePreTickets() throws Exception {
        invokeDoSchedule();
        verify(advanceService).advancePreTickets();
    }

    @Test
    public void doSchedule_advanceThrows_noExceptionEscapes() throws Exception {
        doThrow(new RuntimeException("advance error")).when(advanceService).advancePreTickets();

        invokeDoSchedule();

        verify(advanceService).advancePreTickets();
    }

    private void invokeDoSchedule() throws Exception {
        java.lang.reflect.Method method = GovPipelineScheduler.class.getDeclaredMethod("doSchedule");
        method.setAccessible(true);
        method.invoke(scheduler);
    }
}
