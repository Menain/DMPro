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

import java.util.Date;
import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.console.web.component.approval.model.ApprovalExecutionStateMO;
import com.clougence.clouddm.console.web.model.vo.ticket.RdpTicketProcessVO;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalProcessStatus;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalProcessActivityDO;
import com.clougence.clouddm.platform.dal.model.execution.DmExecAutoJobDO;
import com.clougence.utils.JsonUtils;

import java.util.ArrayList;

/**
 * Step-times fix (2026-09-12): pins the read-side derivation that repairs the degenerate
 * bulk stamp (all three execution steps finished at the terminal moment) left on tickets
 * completed before the v2 per-step lifecycle hooks existed.
 */
public class ApprovalExecutionStepTimesDerivationTest {

    private static final long PROCESS_ID = 900L;
    private static final long BULK_STAMP = 1789191001866L;
    private static final long JOB_CREATED = 1789190990000L;
    private static final long JOB_ENDED = 1789190998000L;

    @Test
    public void degenerateBulkStamp_derivesPerStepTimesFromJobTimeline() {
        RdpTicketProcessVO processVO = process(ApprovalProcessStatus.FINISH);
        List<DmApprovalProcessActivityDO> activities = List.of(
            activity(ApprovalExecutionStateMO.TYPE_PREPARATION, BULK_STAMP),
            activity(ApprovalExecutionStateMO.TYPE_DISPATCH, BULK_STAMP),
            activity(ApprovalExecutionStateMO.TYPE_RUNNING, BULK_STAMP));
        DmExecAutoJobDO job = new DmExecAutoJobDO();
        job.setGmtCreate(new Date(JOB_CREATED));
        job.setEndTime(new Date(JOB_ENDED));

        Map<String, Long> derived = invoke(processVO, activities, List.of(job));

        assertEquals("v2 dispatch is synchronous with job creation",
            Long.valueOf(JOB_CREATED), derived.get(ApprovalExecutionStateMO.TYPE_PREPARATION));
        assertEquals(Long.valueOf(JOB_CREATED), derived.get(ApprovalExecutionStateMO.TYPE_DISPATCH));
        assertEquals("max end_time is the true execution end",
            Long.valueOf(JOB_ENDED), derived.get(ApprovalExecutionStateMO.TYPE_RUNNING));
    }

    @Test
    public void genuinePerStepStamps_leftUntouched() {
        RdpTicketProcessVO processVO = process(ApprovalProcessStatus.FINISH);
        List<DmApprovalProcessActivityDO> activities = List.of(
            activity(ApprovalExecutionStateMO.TYPE_PREPARATION, JOB_CREATED),
            activity(ApprovalExecutionStateMO.TYPE_DISPATCH, JOB_CREATED + 500),
            activity(ApprovalExecutionStateMO.TYPE_RUNNING, JOB_ENDED));
        DmExecAutoJobDO job = new DmExecAutoJobDO();
        job.setGmtCreate(new Date(JOB_CREATED));
        job.setEndTime(new Date(JOB_ENDED));

        Map<String, Long> derived = invoke(processVO, activities, List.of(job));

        assertTrue("post-fix tickets keep their genuine stamps", derived.isEmpty());
    }

    @Test
    public void nonTerminalProcess_leftUntouched() {
        RdpTicketProcessVO processVO = process(ApprovalProcessStatus.INIT);
        List<DmApprovalProcessActivityDO> activities = List.of(
            activity(ApprovalExecutionStateMO.TYPE_PREPARATION, BULK_STAMP),
            activity(ApprovalExecutionStateMO.TYPE_DISPATCH, BULK_STAMP),
            activity(ApprovalExecutionStateMO.TYPE_RUNNING, BULK_STAMP));

        Map<String, Long> derived = invoke(processVO, activities, List.of());

        assertTrue(derived.isEmpty());
    }

    @Test
    public void degenerateStampWithoutJobs_leftUntouched() {
        RdpTicketProcessVO processVO = process(ApprovalProcessStatus.FINISH);
        List<DmApprovalProcessActivityDO> activities = List.of(
            activity(ApprovalExecutionStateMO.TYPE_PREPARATION, BULK_STAMP),
            activity(ApprovalExecutionStateMO.TYPE_DISPATCH, BULK_STAMP),
            activity(ApprovalExecutionStateMO.TYPE_RUNNING, BULK_STAMP));

        Map<String, Long> derived = invoke(processVO, activities, new ArrayList<>());

        assertTrue("no job timeline → keep the stored stamp", derived.isEmpty());
    }

    // ==================== helpers ====================

    @SuppressWarnings("unchecked")
    private Map<String, Long> invoke(RdpTicketProcessVO processVO,
                                     List<DmApprovalProcessActivityDO> activities,
                                     List<DmExecAutoJobDO> jobs) {
        return (Map<String, Long>) ReflectionTestUtils.invokeMethod(
            ApprovalControlServiceImpl.class, "deriveLegacyStepFinishTimes", processVO, activities, jobs);
    }

    private RdpTicketProcessVO process(ApprovalProcessStatus status) {
        RdpTicketProcessVO processVO = new RdpTicketProcessVO();
        processVO.setTicketProcessId(PROCESS_ID);
        processVO.setTicketProcessStatus(status);
        return processVO;
    }

    private DmApprovalProcessActivityDO activity(String type, long finishTimeUtc) {
        ApprovalExecutionStateMO state = new ApprovalExecutionStateMO(type, 1);
        state.setExecutionStatus(ApprovalExecutionStateMO.STATUS_FINISHED);
        state.setFinishTimeUtc(finishTimeUtc);
        DmApprovalProcessActivityDO activity = new DmApprovalProcessActivityDO();
        activity.setId((long) type.hashCode());
        activity.setProcessId(PROCESS_ID);
        activity.setActivityId(type);
        activity.setContext(JsonUtils.toJson(state));
        return activity;
    }
}
