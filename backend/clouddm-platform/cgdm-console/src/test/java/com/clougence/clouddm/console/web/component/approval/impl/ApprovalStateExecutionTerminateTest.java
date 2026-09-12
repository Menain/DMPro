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
package com.clougence.clouddm.console.web.component.approval.impl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.stream.Stream;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.console.web.component.approval.ApprovalHandler;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalExecutionStateMO;
import com.clougence.clouddm.console.web.component.cicd.ImSenderService;
import com.clougence.clouddm.console.web.constants.RdpTicketProcessActivityStatus;
import com.clougence.clouddm.console.web.model.vo.ticket.RdpTicketActivityVO;
import com.clougence.clouddm.console.web.util.RdpConvertUtils;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalProcessActivityMapper;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalProcessMapper;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalProcessActivityDO;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalProcessDO;
import com.clougence.utils.JsonUtils;

/**
 * Completion/failure must terminate EVERY outstanding execution activity, not just
 * EXECUTION_RUNNING: v2 group jobs never drive PREPARATION/DISPATCH (their lifecycle
 * hooks are keyed on the legacy dependOnBizId), so a finished v2 ticket would otherwise
 * display a RUNNING preparation row and an INIT dispatch row forever.
 */
public class ApprovalStateExecutionTerminateTest {

    private ApprovalStateServiceImpl       service;
    private ApprovalDal                    approvalDal;
    private DmApprovalMapper               approvalMapper;
    private DmApprovalProcessMapper        processMapper;
    private DmApprovalProcessActivityMapper activityMapper;

    private static final long   TICKET_ID  = 100L;
    private static final long   PROCESS_ID = 16L;
    private static final String BIZ_ID     = "biz-100";

    @Before
    public void setUp() {
        ApprovalHandler handler = mock(ApprovalHandler.class);
        when(handler.handleType()).thenReturn(ApprovalBiz.DM_CHANGE);
        @SuppressWarnings("unchecked")
        ObjectProvider<ApprovalHandler> provider = mock(ObjectProvider.class);
        when(provider.stream()).thenAnswer(invocation -> Stream.of(handler));

        service = new ApprovalStateServiceImpl(provider);

        approvalDal = mock(ApprovalDal.class);
        approvalMapper = mock(DmApprovalMapper.class);
        processMapper = mock(DmApprovalProcessMapper.class);
        activityMapper = mock(DmApprovalProcessActivityMapper.class);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);
        when(approvalDal.processMapper()).thenReturn(processMapper);
        when(approvalDal.activityMapper()).thenReturn(activityMapper);
        ReflectionTestUtils.setField(service, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(service, "imSenderService", mock(ImSenderService.class));

        DmApprovalProcessDO process = new DmApprovalProcessDO();
        process.setId(PROCESS_ID);
        when(processMapper.queryByStage(TICKET_ID, com.clougence.clouddm.platform.dal.model.approval.ApprovalStage.EXECUTION))
            .thenReturn(process);

        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setBizId(BIZ_ID);
        ticket.setApproBiz(ApprovalBiz.DM_CHANGE);
        when(approvalMapper.queryByBizId(BIZ_ID)).thenReturn(ticket);
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket);
    }

    @Test
    public void completeExecution_terminatesOutstandingActivities() {
        stubActivity(ApprovalExecutionStateMO.TYPE_PREPARATION, ApprovalExecutionStateMO.STATUS_RUNNING, 2L);
        stubActivity(ApprovalExecutionStateMO.TYPE_DISPATCH, ApprovalExecutionStateMO.STATUS_INIT, null);
        stubActivity(ApprovalExecutionStateMO.TYPE_RUNNING, ApprovalExecutionStateMO.STATUS_INIT, null);

        service.completeExecution(BIZ_ID);

        ArgumentCaptor<DmApprovalProcessActivityDO> captor = ArgumentCaptor.forClass(DmApprovalProcessActivityDO.class);
        verify(activityMapper, times(3)).updateById(captor.capture());
        for (DmApprovalProcessActivityDO updated : captor.getAllValues()) {
            ApprovalExecutionStateMO state = JsonUtils.toObj(updated.getContext(), ApprovalExecutionStateMO.class);
            assertEquals(ApprovalExecutionStateMO.STATUS_FINISHED, state.getExecutionStatus());
            assertNotNull("finish time must be persisted", state.getFinishTimeUtc());
        }
        ApprovalExecutionStateMO preparation = JsonUtils.toObj(captor.getAllValues().get(0).getContext(), ApprovalExecutionStateMO.class);
        assertEquals("preparation processedCount catches up to total", Long.valueOf(2L), preparation.getProcessedCount());
    }

    @Test
    public void completeExecution_alreadyTerminalActivitiesUntouched() {
        stubActivity(ApprovalExecutionStateMO.TYPE_PREPARATION, ApprovalExecutionStateMO.STATUS_FINISHED, 2L);
        stubActivity(ApprovalExecutionStateMO.TYPE_DISPATCH, ApprovalExecutionStateMO.STATUS_FINISHED, null);
        stubActivity(ApprovalExecutionStateMO.TYPE_RUNNING, ApprovalExecutionStateMO.STATUS_INIT, null);

        service.completeExecution(BIZ_ID);

        ArgumentCaptor<DmApprovalProcessActivityDO> captor = ArgumentCaptor.forClass(DmApprovalProcessActivityDO.class);
        verify(activityMapper, times(1)).updateById(captor.capture());
        ApprovalExecutionStateMO state = JsonUtils.toObj(captor.getValue().getContext(), ApprovalExecutionStateMO.class);
        assertEquals(ApprovalExecutionStateMO.TYPE_RUNNING, state.getExecutionType());
        assertEquals(ApprovalExecutionStateMO.STATUS_FINISHED, state.getExecutionStatus());
    }

    @Test
    public void failExecution_runningFailedAndNotStartedCanceled() {
        stubActivity(ApprovalExecutionStateMO.TYPE_PREPARATION, ApprovalExecutionStateMO.STATUS_RUNNING, 2L);
        stubActivity(ApprovalExecutionStateMO.TYPE_DISPATCH, ApprovalExecutionStateMO.STATUS_INIT, null);
        stubActivity(ApprovalExecutionStateMO.TYPE_RUNNING, ApprovalExecutionStateMO.STATUS_INIT, null);

        service.failExecution(BIZ_ID, "group failed");

        ArgumentCaptor<DmApprovalProcessActivityDO> captor = ArgumentCaptor.forClass(DmApprovalProcessActivityDO.class);
        verify(activityMapper, times(3)).updateById(captor.capture());
        ApprovalExecutionStateMO preparation = JsonUtils.toObj(captor.getAllValues().get(0).getContext(), ApprovalExecutionStateMO.class);
        ApprovalExecutionStateMO dispatch = JsonUtils.toObj(captor.getAllValues().get(1).getContext(), ApprovalExecutionStateMO.class);
        ApprovalExecutionStateMO running = JsonUtils.toObj(captor.getAllValues().get(2).getContext(), ApprovalExecutionStateMO.class);
        assertEquals(ApprovalExecutionStateMO.STATUS_FAILED, preparation.getExecutionStatus());
        assertEquals("group failed", preparation.getErrorMessage());
        assertEquals(ApprovalExecutionStateMO.STATUS_CANCELED, dispatch.getExecutionStatus());
        assertEquals(ApprovalExecutionStateMO.STATUS_CANCELED, running.getExecutionStatus());
    }

    @Test
    public void convertToExecutionActivityVO_exposesFormattedFinishTime() {
        ApprovalExecutionStateMO state = new ApprovalExecutionStateMO(ApprovalExecutionStateMO.TYPE_RUNNING, 3);
        state.setExecutionStatus(ApprovalExecutionStateMO.STATUS_FINISHED);
        state.setFinishTimeUtc(1789180262110L);

        RdpTicketActivityVO vo = RdpConvertUtils.convertToExecutionActivityVO(state);

        assertEquals(RdpTicketProcessActivityStatus.COMPLETED, vo.getActivityStatus());
        assertNotNull("frontend reads finishTime, not finishTimeUtc", vo.getFinishTime());
        assertTrue(vo.getFinishTime().matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
    }

    // ==================== helpers ====================

    private void stubActivity(String type, String executionStatus, Long totalCount) {
        ApprovalExecutionStateMO state = new ApprovalExecutionStateMO(type, 1);
        state.setExecutionStatus(executionStatus);
        state.setTotalCount(totalCount);
        DmApprovalProcessActivityDO activity = new DmApprovalProcessActivityDO();
        activity.setId((long) type.hashCode());
        activity.setProcessId(PROCESS_ID);
        activity.setActivityId(type);
        activity.setContext(JsonUtils.toJson(state));
        when(activityMapper.queryByProcessIdAndActivityId(PROCESS_ID, type)).thenReturn(activity);
    }
}
