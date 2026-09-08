/*
 * Copyright 2026 杭州开云集致科技有限公司
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.clougence.clouddm.console.web.service.governance.impl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import com.clougence.clouddm.base.metadata.ds.DataSourceConfig;
import com.clougence.clouddm.console.web.component.analysis.QueryAnalysisService;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.component.cicd.ImSenderService;
import com.clougence.clouddm.console.web.component.detectrule.SecRulesCheckResult;
import com.clougence.clouddm.console.web.component.dsconfig.DmDsConfigService;
import com.clougence.clouddm.console.web.component.dsconfig.mode.DsLevels;
import com.clougence.clouddm.console.web.component.execute.AutoExecService;
import com.clougence.clouddm.console.web.component.governance.GovSqlHashUtils;
import com.clougence.clouddm.console.web.model.fo.governance.GovCorrectStatementFO;
import com.clougence.clouddm.console.web.model.vo.logicaldb.LogicalDbTarget;
import com.clougence.clouddm.console.web.service.logicaldb.LogicalDbService;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.DbChangeGovernDal;
import com.clougence.clouddm.platform.dal.access.ExecutionDal;
import com.clougence.clouddm.platform.dal.access.MonitorDal;
import com.clougence.clouddm.platform.dal.access.SystemDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalProcessActivityMapper;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalProcessMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangeEventMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangeRevisionMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangeStmtVersionMapper;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoJobMapper;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoTaskMapper;
import com.clougence.clouddm.platform.dal.mapper.monitor.DmMonBizLogMapper;
import com.clougence.clouddm.platform.dal.mapper.system.DmSysMessengerMapper;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStage;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalProcessDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeRevisionDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeStmtVersionDO;
import com.clougence.clouddm.platform.dal.model.dbchange.GovEventType;
import com.clougence.clouddm.platform.dal.model.dbchange.RevisionSourceType;
import com.clougence.clouddm.platform.dal.model.dbchange.StmtSource;
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
import com.clougence.clouddm.sdk.sql.parser.SplitScript;
import com.clougence.clouddm.sdk.sql.parser.SplitQueryType;
import com.clougence.utils.JsonUtils;

/**
 * C5: EXEC_FAIL retry chain orchestration test (D-P11-7).
 * <p>
 * Wires real service instances: GovFailureNotifyServiceImpl (notify),
 * GovCorrectionServiceImpl (correctStatement), RevisionFreezeServiceImpl (freeze).
 * <p>
 * Chain: execution failure (task FAILED) → notify (FAIL_NOTIFIED + deep link)
 *        → correctStatement (version+1 + CORRECTION event + replaceTask + retryJob)
 *        → execution success (tasks FINISH) → freeze (manifest version=2 with correction history).
 * <p>
 * Real services: 3. Mock boundaries: ~16 (DAL mappers, execution engine, monitor, system, IM,
 * LogicalDbService, DmDsConfigService, QueryAnalysisService, AutoExecService, txManager).
 * <p>
 * In-memory fixture: event store + stmt_version store for cross-service state flow.
 */
public class GovernanceChainRetryTest {

    private GovFailureNotifyServiceImpl  notifyService;
    private GovCorrectionServiceImpl     correctionService;
    private RevisionFreezeServiceImpl    freezeService;

    private LogicalDbService    logicalDbService;
    private DmDsConfigService   dmDsConfigService;
    private QueryAnalysisService queryAnalysisService;
    private AutoExecService     autoExecService;
    private ImSenderService     imSenderService;
    private ApprovalDal         approvalDal;
    private DbChangeGovernDal   dbChangeGovernDal;
    private ExecutionDal        executionDal;
    private MonitorDal          monitorDal;
    private SystemDal           systemDal;

    private DmApprovalMapper        approvalMapper;
    private DmApprovalProcessMapper  processMapper;
    private DmApprovalProcessActivityMapper activityMapper;
    private DmDbChangeStmtVersionMapper stmtVersionMapper;
    private DmDbChangeRevisionMapper revisionMapper;
    private DmDbChangeEventMapper   eventMapper;
    private DmExecAutoJobMapper      autoJobMapper;
    private DmExecAutoTaskMapper     autoTaskMapper;
    private DmMonBizLogMapper        bizLogMapper;
    private DmSysMessengerMapper     messengerMapper;

    private List<DmDbChangeEventDO>      eventStore;
    private List<DmDbChangeStmtVersionDO> stmtVersionStore;

    private static final String PUID = "puid-001";
    private static final String UID = "uid-001";
    private static final long LOGICAL_DB_ID = 10L;
    private static final long TICKET_ID = 200L;
    private static final long JOB_ID = 60L;
    private static final long TASK_ID = 300L;
    private static final long REVISION_ID = 400L;
    private static final String BIZ_ID = "ticket-biz-002";
    private static final String TASK_BIZ_ID = "auto-Task-old001";
    private static final long ENV_ID = 5L;
    private static final long DS_ID = 20L;

    private static final String OLD_SQL = "INSERT INTO t VALUES (1)";
    private static final String NEW_SQL = "INSERT INTO t VALUES (2)";

    @Before
    public void setUp() {
        logicalDbService = mock(LogicalDbService.class);
        dmDsConfigService = mock(DmDsConfigService.class);
        queryAnalysisService = mock(QueryAnalysisService.class);
        autoExecService = mock(AutoExecService.class);
        imSenderService = mock(ImSenderService.class);
        approvalDal = mock(ApprovalDal.class);
        dbChangeGovernDal = mock(DbChangeGovernDal.class);
        executionDal = mock(ExecutionDal.class);
        monitorDal = mock(MonitorDal.class);
        systemDal = mock(SystemDal.class);

        approvalMapper = mock(DmApprovalMapper.class);
        processMapper = mock(DmApprovalProcessMapper.class);
        activityMapper = mock(DmApprovalProcessActivityMapper.class);
        stmtVersionMapper = mock(DmDbChangeStmtVersionMapper.class);
        revisionMapper = mock(DmDbChangeRevisionMapper.class);
        eventMapper = mock(DmDbChangeEventMapper.class);
        autoJobMapper = mock(DmExecAutoJobMapper.class);
        autoTaskMapper = mock(DmExecAutoTaskMapper.class);
        bizLogMapper = mock(DmMonBizLogMapper.class);
        messengerMapper = mock(DmSysMessengerMapper.class);

        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);
        when(approvalDal.processMapper()).thenReturn(processMapper);
        when(approvalDal.activityMapper()).thenReturn(activityMapper);
        when(dbChangeGovernDal.stmtVersionMapper()).thenReturn(stmtVersionMapper);
        when(dbChangeGovernDal.revisionMapper()).thenReturn(revisionMapper);
        when(dbChangeGovernDal.eventMapper()).thenReturn(eventMapper);
        when(executionDal.autoJobMapper()).thenReturn(autoJobMapper);
        when(executionDal.autoTaskMapper()).thenReturn(autoTaskMapper);
        when(monitorDal.bizLogMapper()).thenReturn(bizLogMapper);
        when(systemDal.messengerMapper()).thenReturn(messengerMapper);

        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        // Wire notify service
        notifyService = new GovFailureNotifyServiceImpl();
        ReflectionTestUtils.setField(notifyService, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(notifyService, "dbChangeGovernDal", dbChangeGovernDal);
        ReflectionTestUtils.setField(notifyService, "executionDal", executionDal);
        ReflectionTestUtils.setField(notifyService, "monitorDal", monitorDal);
        ReflectionTestUtils.setField(notifyService, "systemDal", systemDal);
        ReflectionTestUtils.setField(notifyService, "imSenderService", imSenderService);

        // Wire correction service
        correctionService = new GovCorrectionServiceImpl();
        ReflectionTestUtils.setField(correctionService, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(correctionService, "dbChangeGovernDal", dbChangeGovernDal);
        ReflectionTestUtils.setField(correctionService, "executionDal", executionDal);
        ReflectionTestUtils.setField(correctionService, "monitorDal", monitorDal);
        ReflectionTestUtils.setField(correctionService, "logicalDbService", logicalDbService);
        ReflectionTestUtils.setField(correctionService, "dmDsConfigService", dmDsConfigService);
        ReflectionTestUtils.setField(correctionService, "queryAnalysisService", queryAnalysisService);
        ReflectionTestUtils.setField(correctionService, "autoExecService", autoExecService);
        ReflectionTestUtils.setField(correctionService, "txManager", txManager);

        // Wire freeze service
        freezeService = new RevisionFreezeServiceImpl();
        ReflectionTestUtils.setField(freezeService, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(freezeService, "dbChangeGovernDal", dbChangeGovernDal);
        ReflectionTestUtils.setField(freezeService, "executionDal", executionDal);
        ReflectionTestUtils.setField(freezeService, "logicalDbService", logicalDbService);
        ReflectionTestUtils.setField(freezeService, "txManager", txManager);

        // In-memory stores
        eventStore = new ArrayList<>();
        stmtVersionStore = new ArrayList<>();

        doAnswer(invocation -> {
            DmDbChangeEventDO event = invocation.getArgument(0);
            eventStore.add(event);
            return 1;
        }).when(eventMapper).insert(any(DmDbChangeEventDO.class));
        when(eventMapper.queryByTicketId(TICKET_ID)).thenAnswer(inv -> new ArrayList<>(eventStore));

        doAnswer(invocation -> {
            DmDbChangeStmtVersionDO stmt = invocation.getArgument(0);
            stmtVersionStore.add(stmt);
            return 1;
        }).when(stmtVersionMapper).insert(any(DmDbChangeStmtVersionDO.class));
        when(stmtVersionMapper.queryByTicketId(TICKET_ID)).thenAnswer(inv -> new ArrayList<>(stmtVersionStore));

        when(approvalMapper.listUnFinishTicketIdList()).thenReturn(Collections.emptyList());
        when(approvalMapper.listFinishedTicketIdList(any())).thenReturn(Collections.emptyList());
    }

    @Test
    public void retryFullChain_fail_notify_correct_freeze() {
        // === Pre-state: SUBMIT event + stmt_version v1 (INITIAL) ===
        DmDbChangeEventDO submitEvent = new DmDbChangeEventDO();
        submitEvent.setTicketId(TICKET_ID);
        submitEvent.setEventType(GovEventType.SUBMIT.name());
        Map<String, Object> submitData = new HashMap<>();
        submitData.put("changeType", "DML");
        submitEvent.setEventData(JsonUtils.toJson(submitData));
        eventStore.add(submitEvent);

        DmDbChangeStmtVersionDO v1 = new DmDbChangeStmtVersionDO();
        v1.setTicketId(TICKET_ID);
        v1.setStmtIndex(1);
        v1.setStmtVersion(1);
        v1.setStmtText(OLD_SQL);
        v1.setStmtHash(GovSqlHashUtils.hash(OLD_SQL));
        v1.setSource(StmtSource.INITIAL.name());
        v1.setOperatorUid(UID);
        stmtVersionStore.add(v1);

        // === Step 1: execution failure (task FAILED) ===
        DmApprovalDO execFailTicket = buildExecFailTicket();
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(execFailTicket);
        when(approvalMapper.listUnFinishTicketIdList()).thenReturn(List.of(TICKET_ID));

        DmExecAutoJobDO job = new DmExecAutoJobDO();
        job.setId(JOB_ID);
        when(autoJobMapper.queryByDependOnBizId(BIZ_ID)).thenReturn(job);

        DmExecAutoTaskDO failedTask = new DmExecAutoTaskDO();
        failedTask.setId(TASK_ID);
        failedTask.setAutoExecJobId(JOB_ID);
        failedTask.setExecOrder(1);
        failedTask.setStatus(AutoExecTaskStatus.FAILED);
        failedTask.setBizId(TASK_BIZ_ID);
        failedTask.setQueryId("old-query-id");
        failedTask.setExecSql(OLD_SQL);
        when(autoTaskMapper.queryListByJobId(JOB_ID, AutoExecTaskStatus.FAILED)).thenReturn(List.of(failedTask));

        // Error log for notify + correction
        DmMonBizLogDO errorLog = new DmMonBizLogDO(Loglevel.ERROR, "syntax error", LogDependBizType.AUTO_EXEC_TASK, TASK_BIZ_ID);
        when(bizLogMapper.queryListByBizIdAndType(TASK_BIZ_ID, LogDependBizType.AUTO_EXEC_TASK)).thenReturn(List.of(errorLog));

        // Messenger for notify
        DmSysMessengerDO messenger = new DmSysMessengerDO();
        messenger.setEnable(true);
        messenger.setImType(ImType.DingTalk);
        messenger.setWebhook("https://oapi.dingtalk.com/robot/send?access_token=test");
        when(messengerMapper.queryMessengerByOwner(PUID)).thenReturn(List.of(messenger));

        when(imSenderService.sendMessage(eq(UID), any(), any()))
            .thenReturn(MsgSendResult.success("id", "ok"));

        // === Step 2: notify ===
        notifyService.scanAndNotify();

        // Verify FAIL_NOTIFIED event
        boolean foundFailNotified = false;
        for (DmDbChangeEventDO event : eventStore) {
            if (GovEventType.FAIL_NOTIFIED.name().equals(event.getEventType())) {
                foundFailNotified = true;
                assertEquals("SYSTEM", event.getOperatorUid());
                // Verify eventData contains taskBizId and stmtIndex
                Map<String, Object> data = JsonUtils.toObj(event.getEventData(), HashMap.class);
                assertEquals(TASK_BIZ_ID, data.get("taskBizId"));
                assertEquals(1, data.get("stmtIndex"));
            }
        }
        assertTrue("FAIL_NOTIFIED event should be written", foundFailNotified);

        // Verify message sent with deep link
        ArgumentCaptor<MsgContent> msgCaptor = ArgumentCaptor.forClass(MsgContent.class);
        verify(imSenderService).sendMessage(eq(UID), any(), msgCaptor.capture());
        String body = msgCaptor.getValue().getBody();
        assertTrue("Message should contain deep link", body.contains("/ticket/" + TICKET_ID));
        assertTrue("Message should contain ticket ID", body.contains("#" + TICKET_ID));
        assertFalse("Deep link must not carry credentials", body.contains("token=") || body.contains("session="));

        // === Step 3: correctStatement (version+1) ===
        // Set up audit pass (no rule failures, DML type)
        when(queryAnalysisService.analysisRulesStream(any(), any(), any(), anyInt(), anyInt(), any()))
            .thenReturn(Stream.of(new SecRulesCheckResult()));
        SplitScript dmlScript = new SplitScript();
        dmlScript.setType(EnumSet.of(SplitQueryType.INSERT));
        dmlScript.setScript(NEW_SQL);
        when(queryAnalysisService.analysisSplitStream(any(), any(), any(), anyInt(), anyInt()))
            .thenAnswer(inv -> Stream.of(dmlScript));

        // Binding + dsConfig
        setupPreBinding();
        when(dmDsConfigService.fetchDsConfigFromExists(DS_ID)).thenReturn(mock(DataSourceConfig.class));
        DsLevels levels = mock(DsLevels.class);
        when(levels.levelsParam()).thenReturn(new HashMap<>());
        when(dmDsConfigService.parseLevels(anyList())).thenReturn(levels);

        // Task query for correction (returns FAILED task for exec_order=1)
        when(autoTaskMapper.queryListByJobId(JOB_ID, null)).thenReturn(List.of(failedTask));

        GovCorrectStatementFO fo = new GovCorrectStatementFO();
        fo.setTicketId(TICKET_ID);
        fo.setStmtIndex(1);
        fo.setNewSql(NEW_SQL);
        fo.setReason("fix typo");

        correctionService.correctStatement(PUID, UID, fo);

        // Verify version+1 (stmt_version v2 CORRECTION)
        assertEquals("stmt_version store should have v1 + v2", 2, stmtVersionStore.size());
        DmDbChangeStmtVersionDO v2 = stmtVersionStore.get(1);
        assertEquals(Integer.valueOf(2), v2.getStmtVersion());
        assertEquals(StmtSource.CORRECTION.name(), v2.getSource());
        assertEquals(NEW_SQL, v2.getStmtText());
        assertEquals(GovSqlHashUtils.hash(NEW_SQL), v2.getStmtHash());
        assertEquals("syntax error", v2.getFailReason());

        // Verify CORRECTION event
        boolean foundCorrection = false;
        for (DmDbChangeEventDO event : eventStore) {
            if (GovEventType.CORRECTION.name().equals(event.getEventType())) {
                foundCorrection = true;
                Map<String, Object> data = JsonUtils.toObj(event.getEventData(), HashMap.class);
                assertEquals(1, data.get("stmtIndex"));
                assertEquals(1, data.get("fromVersion"));
                assertEquals(2, data.get("toVersion"));
            }
        }
        assertTrue("CORRECTION event should be written", foundCorrection);

        // Verify replaceTask + retryJob called
        verify(autoExecService).replaceTask(BIZ_ID, TASK_ID, NEW_SQL);
        verify(autoExecService).retryJob(BIZ_ID);

        // === Step 4: execution success (tasks FINISH) ===
        DmApprovalDO finishedTicket = buildFinishedTicket();
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(finishedTicket);
        when(approvalMapper.listFinishedTicketIdList(ApprovalBiz.DM_CHANGE)).thenReturn(List.of(TICKET_ID));

        DmExecAutoTaskDO successTask = new DmExecAutoTaskDO();
        successTask.setExecOrder(1);
        successTask.setStatus(AutoExecTaskStatus.FINISH);
        when(autoTaskMapper.queryListByJobId(JOB_ID, null)).thenReturn(List.of(successTask));

        // Empty audit activities
        DmApprovalProcessDO process = new DmApprovalProcessDO();
        process.setId(500L);
        process.setTicketId(TICKET_ID);
        process.setTicketStage(ApprovalStage.EXPLAIN);
        when(processMapper.queryByStage(TICKET_ID, ApprovalStage.EXPLAIN)).thenReturn(process);
        when(activityMapper.queryByTicketId(TICKET_ID)).thenReturn(Collections.emptyList());

        // Revision insert
        when(revisionMapper.queryBySourceTicketId(TICKET_ID)).thenReturn(null);
        doAnswer(invocation -> {
            DmDbChangeRevisionDO rev = invocation.getArgument(0);
            rev.setId(REVISION_ID);
            return 1;
        }).when(revisionMapper).insert(any(DmDbChangeRevisionDO.class));

        // === Step 5: freeze ===
        freezeService.freezeFinishedRevisions();

        // Verify REVISION_FROZEN event
        boolean foundFrozen = false;
        for (DmDbChangeEventDO event : eventStore) {
            if (GovEventType.REVISION_FROZEN.name().equals(event.getEventType())) {
                foundFrozen = true;
                assertEquals("SYSTEM", event.getOperatorUid());
            }
        }
        assertTrue("REVISION_FROZEN event should be written", foundFrozen);

        // Verify revision with manifest version=2 (correction history)
        ArgumentCaptor<DmDbChangeRevisionDO> revCaptor = ArgumentCaptor.forClass(DmDbChangeRevisionDO.class);
        verify(revisionMapper).insert(revCaptor.capture());
        DmDbChangeRevisionDO revision = revCaptor.getValue();
        assertEquals(RevisionSourceType.PRE_TICKET.name(), revision.getSourceType());
        assertEquals("DML", revision.getChangeType());

        // Manifest should show version=2 (max version per stmt_index)
        List<?> manifest = JsonUtils.toObj(revision.getStmtManifest(), List.class);
        assertEquals(1, manifest.size());
        java.util.Map<?, ?> item = (java.util.Map<?, ?>) manifest.get(0);
        assertEquals(1, item.get("idx"));
        assertEquals(GovSqlHashUtils.hash(NEW_SQL), item.get("stmt_hash"));
        assertEquals(2, item.get("version"));
        assertEquals("SUCCESS", item.get("pre_exec"));

        // Verify event sequence: SUBMIT → FAIL_NOTIFIED → CORRECTION → REVISION_FROZEN
        assertEquals(GovEventType.SUBMIT.name(), eventStore.get(0).getEventType());
        assertEquals(GovEventType.FAIL_NOTIFIED.name(), eventStore.get(1).getEventType());
        assertEquals(GovEventType.CORRECTION.name(), eventStore.get(2).getEventType());
        assertEquals(GovEventType.REVISION_FROZEN.name(), eventStore.get(3).getEventType());
    }

    // ======= helpers =======

    private DmApprovalDO buildExecFailTicket() {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setOwnerUid(UID);
        ticket.setPrimaryUid(PUID);
        ticket.setApproBiz(ApprovalBiz.DM_CHANGE);
        ticket.setTicketStatus(ApprovalStatus.EXEC_FAIL);
        ticket.setBizId(BIZ_ID);
        ticket.setRawSql(OLD_SQL);
        ticket.setRollBackSql("DELETE FROM t");
        ticket.setTicketTitle("Test Ticket");

        ApprovalMO mo = new ApprovalMO();
        mo.setLogicalDbId(LOGICAL_DB_ID);
        mo.setGovRole(GovRole.PRE.name());
        ticket.setTicketInfo(JsonUtils.toJson(mo));
        return ticket;
    }

    private DmApprovalDO buildFinishedTicket() {
        DmApprovalDO ticket = buildExecFailTicket();
        ticket.setTicketStatus(ApprovalStatus.FINISHED);
        return ticket;
    }

    private void setupPreBinding() {
        LogicalDbTarget target = new LogicalDbTarget();
        target.setBindingId(1L);
        target.setLogicalDbId(LOGICAL_DB_ID);
        target.setEnvId(ENV_ID);
        target.setDsId(DS_ID);
        target.setResPath("/mydb/");
        target.setGovRole(GovRole.PRE);
        when(logicalDbService.getBinding(PUID, LOGICAL_DB_ID, GovRole.PRE)).thenReturn(target);
    }
}
