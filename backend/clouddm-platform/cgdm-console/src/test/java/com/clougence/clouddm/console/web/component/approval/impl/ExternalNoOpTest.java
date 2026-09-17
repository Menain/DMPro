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

import java.util.Collections;
import java.util.Date;

import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.console.web.component.approval.ApprovalFlowService;
import com.clougence.clouddm.console.web.component.approval.ApprovalHandler;
import com.clougence.clouddm.console.web.component.approval.ApprovalPersonService;
import com.clougence.clouddm.console.web.component.approval.ApprovalStateService;
import com.clougence.clouddm.console.web.component.approval.handler.ChangeApprovalHandler;
import com.clougence.clouddm.console.web.component.approval.handler.ProdReleaseApprovalHandler;
import com.clougence.clouddm.console.web.component.approval.schedule.ApprovalPreInitService;
import com.clougence.clouddm.console.web.component.approval.schedule.ApprovalTaskProcessor;
import com.clougence.clouddm.console.web.component.cicd.ImSenderService;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.AuthDal;
import com.clougence.clouddm.platform.dal.access.SystemDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalProcessMapper;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStage;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalType;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalProcessDO;
import com.clougence.clouddm.platform.dal.model.system.DmSysUserConfDO;
import com.clougence.clouddm.platform.dal.mapper.system.DmSysUserConfMapper;

/**
 * T2: External channel no-op pin.
 * <p>
 * Verifies that External-type tickets are invisible to the scheduler/handler
 * sweep logic: processWaitApproval early-exits without creating instances or
 * polling, handler.createApproval early-exits, and refreshApprovalStatus
 * defensively returns without throwing NOT_SUPPORT.
 */
public class ExternalNoOpTest {

    private static final long  TICKET_ID = 200L;
    private static final String PUID     = "puid-002";

    // ==================== processWaitApproval no-op ====================

    @Test
    public void processWaitApproval_externalType_zeroCreateApprovalAndPolling() {
        ApprovalTaskProcessor processor = new ApprovalTaskProcessor(Collections.<ApprovalHandler>emptyList());

        ApprovalDal approvalDal = mock(ApprovalDal.class);
        DmApprovalMapper approvalMapper = mock(DmApprovalMapper.class);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);
        DmApprovalProcessMapper processMapper = mock(DmApprovalProcessMapper.class);
        when(approvalDal.processMapper()).thenReturn(processMapper);
        DmApprovalProcessDO processDO = new DmApprovalProcessDO();
        processDO.setId(1L);
        processDO.setGmtModified(new Date());
        when(processMapper.queryByStage(TICKET_ID, ApprovalStage.APPROVAL)).thenReturn(processDO);

        SystemDal systemDal = mock(SystemDal.class);
        DmSysUserConfMapper userConfMapper = mock(DmSysUserConfMapper.class);
        when(systemDal.userConfMapper()).thenReturn(userConfMapper);

        ReflectionTestUtils.setField(processor, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(processor, "systemDal", systemDal);
        ReflectionTestUtils.setField(processor, "approvalProviderService", mock(ApprovalProviderServiceImpl.class));
        ReflectionTestUtils.setField(processor, "imSenderService", mock(ImSenderService.class));
        ReflectionTestUtils.setField(processor, "preInitService", mock(ApprovalPreInitService.class));
        ReflectionTestUtils.setField(processor, "approvalFlowService", mock(ApprovalFlowService.class));
        ReflectionTestUtils.setField(processor, "approvalStateService", mock(ApprovalStateService.class));
        ReflectionTestUtils.setField(processor, "approvalPersonService", mock(ApprovalPersonService.class));
        ReflectionTestUtils.setField(processor, "txManager", mock(org.springframework.transaction.PlatformTransactionManager.class));

        DmApprovalDO ticket = buildTicket(ApprovalType.External, ApprovalStatus.WAIT_APPROVAL);
        processor.processWaitApproval(ticket);

        // External early-exit: no selectByIdForUpdate, no userConfMapper (polling) calls
        verify(approvalMapper, never()).selectByIdForUpdate(anyLong());
        verify(systemDal, never()).userConfMapper();
    }

    @Test
    public void processWaitApproval_dingTalkType_stillEntersNonInternalBranch_regressionPin() {
        // Regression pin: DingTalk type still enters the createApproval/polling branch
        ApprovalTaskProcessor processor = new ApprovalTaskProcessor(Collections.<ApprovalHandler>emptyList());

        ApprovalDal approvalDal = mock(ApprovalDal.class);
        DmApprovalMapper approvalMapper = mock(DmApprovalMapper.class);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);
        DmApprovalProcessMapper processMapper = mock(DmApprovalProcessMapper.class);
        when(approvalDal.processMapper()).thenReturn(processMapper);
        DmApprovalProcessDO processDO = new DmApprovalProcessDO();
        processDO.setId(1L);
        processDO.setGmtModified(new Date());
        when(processMapper.queryByStage(TICKET_ID, ApprovalStage.APPROVAL)).thenReturn(processDO);

        SystemDal systemDal = mock(SystemDal.class);
        DmSysUserConfMapper userConfMapper = mock(DmSysUserConfMapper.class);
        when(systemDal.userConfMapper()).thenReturn(userConfMapper);
        DmSysUserConfDO confDO = new DmSysUserConfDO();
        confDO.setConfigValue("0"); // interval <=0 disables polling
        when(userConfMapper.queryByUidAndConfigName(anyString(), anyString())).thenReturn(confDO);

        ReflectionTestUtils.setField(processor, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(processor, "systemDal", systemDal);
        ReflectionTestUtils.setField(processor, "approvalProviderService", mock(ApprovalProviderServiceImpl.class));
        ReflectionTestUtils.setField(processor, "imSenderService", mock(ImSenderService.class));
        ReflectionTestUtils.setField(processor, "preInitService", mock(ApprovalPreInitService.class));
        ReflectionTestUtils.setField(processor, "approvalFlowService", mock(ApprovalFlowService.class));
        ReflectionTestUtils.setField(processor, "approvalStateService", mock(ApprovalStateService.class));
        ReflectionTestUtils.setField(processor, "approvalPersonService", mock(ApprovalPersonService.class));
        ReflectionTestUtils.setField(processor, "txManager", mock(org.springframework.transaction.PlatformTransactionManager.class));

        DmApprovalDO ticket = buildTicket(ApprovalType.DingTalk, ApprovalStatus.WAIT_APPROVAL);
        ticket.setApproIdentity("ding-instance-001"); // has identity → skip createApproval
        processor.processWaitApproval(ticket);

        // DingTalk with identity → entered non-Internal branch, polling disabled by interval=0
        verify(systemDal, atLeastOnce()).userConfMapper();
    }

    // ==================== refreshApprovalStatus defensive early exit ====================

    @Test
    public void refreshApprovalStatus_externalType_returnsWithoutThrowing() {
        ApprovalProviderServiceImpl service = new ApprovalProviderServiceImpl(Collections.emptyList());

        ApprovalDal approvalDal = mock(ApprovalDal.class);
        DmApprovalMapper approvalMapper = mock(DmApprovalMapper.class);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);

        DmApprovalDO ticket = buildTicket(ApprovalType.External, ApprovalStatus.WAIT_APPROVAL);
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket);

        ReflectionTestUtils.setField(service, "approvalDal", approvalDal);

        // Should return without throwing — no NOT_SUPPORT exception
        service.refreshApprovalStatus(TICKET_ID);

        // No further calls beyond queryById (External early exit)
        verify(approvalMapper, never()).selectByIdForUpdate(anyLong());
    }

    @Test
    public void refreshApprovalStatus_endStatus_returnsEarly_regressionPin() {
        // Regression pin: end-status tickets still return early (existing behavior)
        ApprovalProviderServiceImpl service = new ApprovalProviderServiceImpl(Collections.emptyList());

        ApprovalDal approvalDal = mock(ApprovalDal.class);
        DmApprovalMapper approvalMapper = mock(DmApprovalMapper.class);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);

        DmApprovalDO ticket = buildTicket(ApprovalType.DingTalk, ApprovalStatus.FINISHED);
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket);

        ReflectionTestUtils.setField(service, "approvalDal", approvalDal);

        service.refreshApprovalStatus(TICKET_ID);

        verify(approvalMapper, never()).selectByIdForUpdate(anyLong());
    }

    // ==================== handler.createApproval early exit ====================

    @Test
    public void changeHandler_createApproval_external_returnsEarly() {
        ChangeApprovalHandler handler = new ChangeApprovalHandler();
        ApprovalDal approvalDal = mock(ApprovalDal.class);
        DmApprovalMapper approvalMapper = mock(DmApprovalMapper.class);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);

        DmApprovalDO ticket = buildTicket(ApprovalType.External, ApprovalStatus.WAIT_APPROVAL);
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(ticket);

        ReflectionTestUtils.setField(handler, "approvalDal", approvalDal);

        handler.createApproval(TICKET_ID, mock(ImSenderService.class));

        // No updateThirdApprovalInfo, no state changes
        verify(approvalMapper, never()).updateThirdApprovalInfo(anyLong(), anyString(), any());
    }

    @Test
    public void prodReleaseHandler_createApproval_external_returnsEarly() {
        ProdReleaseApprovalHandler handler = new ProdReleaseApprovalHandler();
        ApprovalDal approvalDal = mock(ApprovalDal.class);
        DmApprovalMapper approvalMapper = mock(DmApprovalMapper.class);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);

        DmApprovalDO ticket = buildTicket(ApprovalType.External, ApprovalStatus.WAIT_APPROVAL);
        ticket.setApproBiz(ApprovalBiz.DM_PROD_RELEASE);
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(ticket);

        ReflectionTestUtils.setField(handler, "approvalDal", approvalDal);

        handler.createApproval(TICKET_ID, mock(ImSenderService.class));

        verify(approvalMapper, never()).updateThirdApprovalInfo(anyLong(), anyString(), any());
    }

    // ==================== Helpers ====================

    private DmApprovalDO buildTicket(ApprovalType type, ApprovalStatus status) {
        DmApprovalDO t = new DmApprovalDO();
        t.setId(TICKET_ID);
        t.setPrimaryUid(PUID);
        t.setOwnerUid("uid-002");
        t.setApproType(type);
        t.setApproBiz(ApprovalBiz.DM_CHANGE);
        t.setTicketStatus(status);
        return t;
    }

    private ApprovalTaskProcessor createTaskProcessor() {
        ApprovalTaskProcessor processor = new ApprovalTaskProcessor(Collections.<ApprovalHandler>emptyList());

        ApprovalDal approvalDal = mock(ApprovalDal.class);
        DmApprovalMapper approvalMapper = mock(DmApprovalMapper.class);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);

        SystemDal systemDal = mock(SystemDal.class);
        AuthDal authDal = mock(AuthDal.class);
        ApprovalProviderServiceImpl providerService = mock(ApprovalProviderServiceImpl.class);
        ImSenderService imSenderService = mock(ImSenderService.class);
        ApprovalPreInitService preInitService = mock(ApprovalPreInitService.class);
        ApprovalFlowService approvalFlowService = mock(ApprovalFlowService.class);
        ApprovalStateService approvalStateService = mock(ApprovalStateService.class);
        ApprovalPersonService approvalPersonService = mock(ApprovalPersonService.class);
        org.springframework.transaction.PlatformTransactionManager txManager
            = mock(org.springframework.transaction.PlatformTransactionManager.class);

        ReflectionTestUtils.setField(processor, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(processor, "systemDal", systemDal);
        ReflectionTestUtils.setField(processor, "authDal", authDal);
        ReflectionTestUtils.setField(processor, "approvalProviderService", providerService);
        ReflectionTestUtils.setField(processor, "imSenderService", imSenderService);
        ReflectionTestUtils.setField(processor, "preInitService", preInitService);
        ReflectionTestUtils.setField(processor, "approvalFlowService", approvalFlowService);
        ReflectionTestUtils.setField(processor, "approvalStateService", approvalStateService);
        ReflectionTestUtils.setField(processor, "approvalPersonService", approvalPersonService);
        ReflectionTestUtils.setField(processor, "txManager", txManager);

        return processor;
    }
}
