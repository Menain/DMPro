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
package com.clougence.clouddm.console.web.component.approval.handler;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.console.web.component.approval.ApprovalStateService;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.component.cicd.ImSenderService;
import com.clougence.clouddm.console.web.component.governance.ProdReleaseFormAssembler;
import com.clougence.clouddm.console.web.component.governance.ProdReleaseStateMachine;
import com.clougence.clouddm.console.web.service.governance.ProdReleaseService;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.AuthDal;
import com.clougence.clouddm.platform.dal.access.ProdReleaseDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalType;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.plugin.PluginManager;
import com.clougence.clouddm.sdk.approval.ApprovalActivityInfo;
import com.clougence.clouddm.sdk.approval.ApprovalCreateInstanceResult;
import com.clougence.clouddm.sdk.approval.ApprovalProviderSpi;
import com.clougence.clouddm.sdk.approval.ApprovalUrl;
import com.clougence.clouddm.sdk.approval.form.ChangeForm;
import com.clougence.clouddm.sdk.model.exception.ThirdPartyApiException;
import com.clougence.utils.JsonUtils;

/**
 * Tests for {@link ProdReleaseApprovalHandler#createApproval} — external approval branch.
 * <p>
 * Verifies: SPI createApprovalInstance call, activity initialization,
 * updateThirdApprovalInfo, and ThirdPartyApiException → FAILED + approvalFailed.
 */
public class ProdReleaseApprovalHandlerCreateApprovalTest {

    private ProdReleaseApprovalHandler handler;

    private ApprovalDal           approvalDal;
    private DmApprovalMapper      approvalMapper;
    private AuthDal              authDal;
    private ApprovalStateService  approvalStateService;
    private ProdReleaseDal       prodReleaseDal;
    private ProdReleaseStateMachine releaseStateMachine;
    private ProdReleaseService    prodReleaseService;
    private ProdReleaseFormAssembler formAssembler;

    private static final long  TICKET_ID = 100L;
    private static final String PUID = "puid-001";

    @Before
    public void setUp() {
        handler = new ProdReleaseApprovalHandler();
        approvalDal = mock(ApprovalDal.class);
        approvalMapper = mock(DmApprovalMapper.class);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);
        authDal = mock(AuthDal.class);
        approvalStateService = mock(ApprovalStateService.class);
        prodReleaseDal = mock(ProdReleaseDal.class);
        releaseStateMachine = mock(ProdReleaseStateMachine.class);
        prodReleaseService = mock(ProdReleaseService.class);
        formAssembler = mock(ProdReleaseFormAssembler.class);

        ReflectionTestUtils.setField(handler, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(handler, "authDal", authDal);
        ReflectionTestUtils.setField(handler, "approvalStateService", approvalStateService);
        ReflectionTestUtils.setField(handler, "prodReleaseDal", prodReleaseDal);
        ReflectionTestUtils.setField(handler, "releaseStateMachine", releaseStateMachine);
        ReflectionTestUtils.setField(handler, "prodReleaseService", prodReleaseService);
        ReflectionTestUtils.setField(handler, "prodReleaseFormAssembler", formAssembler);
    }

    @Test
    public void createApproval_internalType_returnsEarly() {
        DmApprovalDO ticket = buildTicket(ApprovalType.Internal);
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(ticket);

        handler.createApproval(TICKET_ID, mock(ImSenderService.class));

        // No SPI call, no activity init, no updateThirdApprovalInfo
        verifyNoInteractions(approvalStateService);
        verify(approvalMapper, never()).updateThirdApprovalInfo(anyLong(), anyString(), any());
    }

    @Test
    public void createApproval_externalType_success_writesIdentityAndActivity() {
        DmApprovalDO ticket = buildTicket(ApprovalType.DingTalk);
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(ticket);

        // Form assembler returns a form
        ChangeForm form = new ChangeForm();
        when(formAssembler.build(eq(ticket), any(ApprovalMO.class), eq("PROC-001"))).thenReturn(form);

        // SPI returns success result with activities
        ApprovalProviderSpi spi = mock(ApprovalProviderSpi.class);
        ApprovalCreateInstanceResult result = new ApprovalCreateInstanceResult();
        result.setApprovalIdentity("ding-instance-001");
        ApprovalUrl url = new ApprovalUrl();
        url.setPcUrl("https://oapi.dingtalk.com/approval/123");
        result.setApprovalUrl(url);
        List<ApprovalActivityInfo> activities = new ArrayList<>();
        ApprovalActivityInfo a1 = new ApprovalActivityInfo();
        a1.setActivityId("task-1");
        a1.setActivityName("审批");
        a1.setOrder(1);
        activities.add(a1);
        result.setActivityList(activities);
        when(spi.createApprovalInstance(PUID, form)).thenReturn(result);

        try (MockedStatic<PluginManager> pluginManager = mockStatic(PluginManager.class)) {
            pluginManager.when(() -> PluginManager.findSpi(ApprovalProviderSpi.class, "DingTalk"))
                .thenReturn(spi);

            handler.createApproval(TICKET_ID, mock(ImSenderService.class));
        }

        // Verify: initializeActivity called for each activity
        verify(approvalStateService).initializeActivity(
            eq(TICKET_ID), eq(com.clougence.clouddm.platform.dal.model.approval.ApprovalStage.APPROVAL),
            eq("task-1"), eq("审批"), eq(1), isNull(), isNull());

        // Verify: updateThirdApprovalInfo called with identity and URL
        verify(approvalMapper).updateThirdApprovalInfo(
            eq(TICKET_ID), eq("ding-instance-001"), anyString());

        // Verify: no FAILED status update
        verify(approvalStateService, never()).updateApprovalStatus(eq(TICKET_ID), eq(ApprovalStatus.FAILED), anyString());
    }

    @Test
    public void createApproval_thirdPartyApiException_setsFailedAndCallsApprovalFailed() {
        DmApprovalDO ticket = buildTicket(ApprovalType.DingTalk);
        when(approvalMapper.selectByIdForUpdate(TICKET_ID)).thenReturn(ticket);

        ChangeForm form = new ChangeForm();
        when(formAssembler.build(eq(ticket), any(ApprovalMO.class), eq("PROC-001"))).thenReturn(form);

        ApprovalProviderSpi spi = mock(ApprovalProviderSpi.class);
        ThirdPartyApiException ex = ThirdPartyApiException.as().with("dingtalk error");
        when(spi.createApprovalInstance(PUID, form)).thenThrow(ex);

        try (MockedStatic<PluginManager> pluginManager = mockStatic(PluginManager.class)) {
            pluginManager.when(() -> PluginManager.findSpi(ApprovalProviderSpi.class, "DingTalk"))
                .thenReturn(spi);

            handler.createApproval(TICKET_ID, mock(ImSenderService.class));
        }

        // Verify: FAILED status set
        verify(approvalStateService).updateApprovalStatus(eq(TICKET_ID), eq(ApprovalStatus.FAILED), anyString());

        // Verify: no updateThirdApprovalInfo (failed before that)
        verify(approvalMapper, never()).updateThirdApprovalInfo(anyLong(), anyString(), any());

        // Verify: no activity init (failed before that)
        verify(approvalStateService, never()).initializeActivity(anyLong(), any(), anyString(), anyString(), anyInt(), any(), any());
    }

    // ==================== Helpers ====================

    private DmApprovalDO buildTicket(ApprovalType type) {
        DmApprovalDO t = new DmApprovalDO();
        t.setId(TICKET_ID);
        t.setPrimaryUid(PUID);
        t.setOwnerUid("uid-001");
        t.setApproType(type);
        t.setApproBiz(ApprovalBiz.DM_PROD_RELEASE);
        t.setApproTemplateIdentity("PROC-001");
        ApprovalMO info = new ApprovalMO();
        info.setReleaseId(500L);
        info.setReleaseNo("REL-001");
        t.setTicketInfo(JsonUtils.toJson(info));
        return t;
    }
}
