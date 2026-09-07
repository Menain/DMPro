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
package com.clougence.clouddm.console.web.service.governance.impl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.component.cicd.ImSenderConfig;
import com.clougence.clouddm.console.web.component.cicd.ImSenderService;
import com.clougence.clouddm.console.web.service.governance.GovFailureNotifyService;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.DbChangeGovernDal;
import com.clougence.clouddm.platform.dal.access.ExecutionDal;
import com.clougence.clouddm.platform.dal.access.MonitorDal;
import com.clougence.clouddm.platform.dal.access.SystemDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangeEventMapper;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoJobMapper;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoTaskMapper;
import com.clougence.clouddm.platform.dal.mapper.monitor.DmMonBizLogMapper;
import com.clougence.clouddm.platform.dal.mapper.system.DmSysMessengerMapper;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO;
import com.clougence.clouddm.platform.dal.model.dbchange.GovEventType;
import com.clougence.clouddm.platform.dal.model.execution.AutoExecTaskStatus;
import com.clougence.clouddm.platform.dal.model.execution.DmExecAutoJobDO;
import com.clougence.clouddm.platform.dal.model.execution.DmExecAutoTaskDO;
import com.clougence.clouddm.platform.dal.model.logicaldb.GovRole;
import com.clougence.clouddm.platform.dal.model.monitor.DmMonBizLogDO;
import com.clougence.clouddm.platform.dal.model.monitor.Loglevel;
import com.clougence.clouddm.platform.dal.model.monitor.LogDependBizType;
import com.clougence.clouddm.platform.dal.model.system.DmSysMessengerDO;
import com.clougence.clouddm.platform.dal.model.system.ImType;
import com.clougence.clouddm.sdk.messenger.MsgContent;
import com.clougence.clouddm.sdk.messenger.MsgSendResult;
import com.clougence.utils.JsonUtils;

public class GovFailureNotifyServiceTest {

    private GovFailureNotifyService service;

    private ApprovalDal           approvalDal;
    private DmApprovalMapper      approvalMapper;
    private DbChangeGovernDal     dbChangeGovernDal;
    private DmDbChangeEventMapper eventMapper;
    private ExecutionDal          executionDal;
    private DmExecAutoJobMapper   autoJobMapper;
    private DmExecAutoTaskMapper  autoTaskMapper;
    private MonitorDal            monitorDal;
    private DmMonBizLogMapper     bizLogMapper;
    private SystemDal             systemDal;
    private DmSysMessengerMapper  messengerMapper;
    private ImSenderService       imSenderService;

    private static final String   PUID      = "puid-001";
    private static final String   UID       = "uid-001";
    private static final long     TICKET_ID = 200L;
    private static final long     JOB_ID    = 60L;
    private static final String   BIZ_ID    = "ticket-biz-002";

    @Before
    public void setUp() {
        approvalDal = mock(ApprovalDal.class);
        dbChangeGovernDal = mock(DbChangeGovernDal.class);
        executionDal = mock(ExecutionDal.class);
        monitorDal = mock(MonitorDal.class);
        systemDal = mock(SystemDal.class);
        imSenderService = mock(ImSenderService.class);

        approvalMapper = mock(DmApprovalMapper.class);
        eventMapper = mock(DmDbChangeEventMapper.class);
        autoJobMapper = mock(DmExecAutoJobMapper.class);
        autoTaskMapper = mock(DmExecAutoTaskMapper.class);
        bizLogMapper = mock(DmMonBizLogMapper.class);
        messengerMapper = mock(DmSysMessengerMapper.class);

        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);
        when(dbChangeGovernDal.eventMapper()).thenReturn(eventMapper);
        when(executionDal.autoJobMapper()).thenReturn(autoJobMapper);
        when(executionDal.autoTaskMapper()).thenReturn(autoTaskMapper);
        when(monitorDal.bizLogMapper()).thenReturn(bizLogMapper);
        when(systemDal.messengerMapper()).thenReturn(messengerMapper);

        GovFailureNotifyServiceImpl impl = new GovFailureNotifyServiceImpl();
        ReflectionTestUtils.setField(impl, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(impl, "dbChangeGovernDal", dbChangeGovernDal);
        ReflectionTestUtils.setField(impl, "executionDal", executionDal);
        ReflectionTestUtils.setField(impl, "monitorDal", monitorDal);
        ReflectionTestUtils.setField(impl, "systemDal", systemDal);
        ReflectionTestUtils.setField(impl, "imSenderService", imSenderService);
        service = impl;
    }

    @Test
    public void notify_execFailGovernanceTicket_sendsMessage() {
        setupExecFailTicket();
        setupJob();
        setupFailedTask();
        setupBizLog("Column not found");
        setupMessenger(true);
        when(eventMapper.queryByTicketId(TICKET_ID)).thenReturn(Collections.emptyList());

        service.scanAndNotify();

        ArgumentCaptor<MsgContent> msgCaptor = ArgumentCaptor.forClass(MsgContent.class);
        verify(imSenderService).sendMessage(eq(UID), any(ImSenderConfig.class), msgCaptor.capture());
        String body = msgCaptor.getValue().getBody();
        assertTrue(body.contains("Ticket: #200"));
        assertTrue(body.contains("Failed statement: #1"));
        assertTrue(body.contains("Column not found"));
        assertTrue(body.contains("/ticket/200"));
    }

    @Test
    public void notify_alreadyNotified_skipSend() {
        setupExecFailTicket();
        setupJob();
        setupFailedTask();
        setupMessenger(true);

        DmDbChangeEventDO notifiedEvent = new DmDbChangeEventDO();
        notifiedEvent.setEventType(GovEventType.FAIL_NOTIFIED.name());
        Map<String, Object> data = new HashMap<>();
        data.put("taskBizId", "auto-Task-fail001");
        notifiedEvent.setEventData(JsonUtils.toJson(data));
        when(eventMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(notifiedEvent));

        service.scanAndNotify();

        verify(imSenderService, never()).sendMessage(any(), any(), any());
    }

    @Test
    public void notify_nonGovernanceTicket_skipNoQueries() {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setApproBiz(ApprovalBiz.DM_QUERY);
        ticket.setTicketStatus(ApprovalStatus.EXEC_FAIL);
        ticket.setPrimaryUid(PUID);
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket);
        when(approvalMapper.listUnFinishTicketIdList()).thenReturn(List.of(TICKET_ID));

        service.scanAndNotify();

        verify(eventMapper, never()).queryByTicketId(any());
        verifyNoInteractions(imSenderService);
    }

    @Test
    public void notify_nonExecFail_skip() {
        setupTicket(ApprovalStatus.WAIT_APPROVAL, GovRole.PRE.name());
        when(approvalMapper.listUnFinishTicketIdList()).thenReturn(List.of(TICKET_ID));

        service.scanAndNotify();

        verify(eventMapper, never()).queryByTicketId(any());
        verifyNoInteractions(imSenderService);
    }

    @Test
    public void notify_noMessenger_logsWarningNoCrash() {
        setupExecFailTicket();
        setupJob();
        setupFailedTask();
        setupBizLog("error");
        when(messengerMapper.queryMessengerByOwner(PUID)).thenReturn(Collections.emptyList());
        when(eventMapper.queryByTicketId(TICKET_ID)).thenReturn(Collections.emptyList());

        service.scanAndNotify();

        verify(imSenderService, never()).sendMessage(any(), any(), any());
        // No FAIL_NOTIFIED event written — next scan should retry
        verify(eventMapper, never()).insert(any(DmDbChangeEventDO.class));
    }

    @Test
    public void notify_imProviderError_swallowedNoCrash() {
        setupExecFailTicket();
        setupJob();
        setupFailedTask();
        setupBizLog("error");
        setupMessenger(true);
        when(eventMapper.queryByTicketId(TICKET_ID)).thenReturn(Collections.emptyList());
        doThrow(new ErrorMessageException("missing provider"))
            .when(imSenderService).sendMessage(eq(UID), any(ImSenderConfig.class), any(MsgContent.class));

        // Should not throw — catch and continue
        service.scanAndNotify();
    }

    @Test
    public void notify_failNotifiedEventWritten() {
        setupExecFailTicket();
        setupJob();
        setupFailedTask();
        setupBizLog("error");
        setupMessenger(true);
        when(eventMapper.queryByTicketId(TICKET_ID)).thenReturn(Collections.emptyList());
        when(imSenderService.sendMessage(any(), any(), any())).thenReturn(MsgSendResult.success("id", "ok"));

        service.scanAndNotify();

        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        assertEquals(GovEventType.FAIL_NOTIFIED.name(), eventCaptor.getValue().getEventType());
        Map<String, Object> data = JsonUtils.toObj(eventCaptor.getValue().getEventData(), HashMap.class);
        assertEquals("auto-Task-fail001", data.get("taskBizId"));
    }

    // ======= helpers =======

    private void setupExecFailTicket() {
        setupTicket(ApprovalStatus.EXEC_FAIL, GovRole.PRE.name());
    }

    private void setupTicket(ApprovalStatus status, String govRole) {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setOwnerUid(UID);
        ticket.setPrimaryUid(PUID);
        ticket.setApproBiz(ApprovalBiz.DM_CHANGE);
        ticket.setTicketStatus(status);
        ticket.setBizId(BIZ_ID);
        ticket.setTicketTitle("test ticket");
        ApprovalMO mo = new ApprovalMO();
        mo.setLogicalDbId(10L);
        mo.setGovRole(govRole);
        ticket.setTicketInfo(JsonUtils.toJson(mo));
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket);
        when(approvalMapper.listUnFinishTicketIdList()).thenReturn(List.of(TICKET_ID));
    }

    private void setupJob() {
        DmExecAutoJobDO job = new DmExecAutoJobDO();
        job.setId(JOB_ID);
        job.setDependOnBizId(BIZ_ID);
        when(autoJobMapper.queryByDependOnBizId(BIZ_ID)).thenReturn(job);
    }

    private void setupFailedTask() {
        DmExecAutoTaskDO task = new DmExecAutoTaskDO();
        task.setId(300L);
        task.setAutoExecJobId(JOB_ID);
        task.setExecOrder(1);
        task.setStatus(AutoExecTaskStatus.FAILED);
        task.setBizId("auto-Task-fail001");
        task.setExecSql("INSERT INTO t VALUES (1)");
        when(autoTaskMapper.queryListByJobId(JOB_ID, AutoExecTaskStatus.FAILED)).thenReturn(List.of(task));
        when(autoTaskMapper.queryListByJobId(JOB_ID, AutoExecTaskStatus.ROLLBACK)).thenReturn(Collections.emptyList());
    }

    private void setupBizLog(String errorContent) {
        DmMonBizLogDO logDO = new DmMonBizLogDO(Loglevel.ERROR, errorContent, LogDependBizType.AUTO_EXEC_TASK, "auto-Task-fail001");
        when(bizLogMapper.queryListByBizIdAndType("auto-Task-fail001", LogDependBizType.AUTO_EXEC_TASK))
            .thenReturn(List.of(logDO));
    }

    private void setupMessenger(boolean enabled) {
        DmSysMessengerDO messenger = new DmSysMessengerDO();
        messenger.setOwnerUid(PUID);
        messenger.setImType(ImType.DingTalk);
        messenger.setWebhook("https://oapi.dingtalk.com/robot/send?access_token=test");
        messenger.setSecret("secret");
        messenger.setEnable(enabled);
        when(messengerMapper.queryMessengerByOwner(PUID)).thenReturn(List.of(messenger));
    }
}
