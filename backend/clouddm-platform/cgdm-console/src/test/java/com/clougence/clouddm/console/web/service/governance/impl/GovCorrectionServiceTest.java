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

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Stream;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.base.metadata.ds.DataSourceConfig;
import com.clougence.clouddm.console.web.component.analysis.QueryAnalysisService;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.component.detectrule.SecRulesCheckResult;
import com.clougence.clouddm.console.web.component.dsconfig.DmDsConfigService;
import com.clougence.clouddm.console.web.component.execute.AutoExecService;
import com.clougence.clouddm.console.web.component.governance.GovSqlHashUtils;
import com.clougence.clouddm.console.web.model.fo.governance.GovCorrectStatementFO;
import com.clougence.clouddm.console.web.model.vo.logicaldb.LogicalDbTarget;
import com.clougence.clouddm.console.web.service.governance.GovCorrectionService;
import com.clougence.clouddm.console.web.service.logicaldb.LogicalDbService;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.DbChangeGovernDal;
import com.clougence.clouddm.platform.dal.access.ExecutionDal;
import com.clougence.clouddm.platform.dal.access.MonitorDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangeEventMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangeStmtVersionMapper;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoJobMapper;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoTaskMapper;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeStmtVersionDO;
import com.clougence.clouddm.platform.dal.model.dbchange.GovEventType;
import com.clougence.clouddm.platform.dal.model.dbchange.StmtSource;
import com.clougence.clouddm.platform.dal.model.execution.AutoExecTaskStatus;
import com.clougence.clouddm.platform.dal.model.execution.DmExecAutoJobDO;
import com.clougence.clouddm.platform.dal.model.execution.DmExecAutoTaskDO;
import com.clougence.clouddm.platform.dal.model.logicaldb.GovRole;
import com.clougence.clouddm.platform.dal.model.monitor.DmMonBizLogDO;
import com.clougence.clouddm.platform.dal.model.monitor.Loglevel;
import com.clougence.clouddm.platform.dal.model.monitor.LogDependBizType;
import com.clougence.clouddm.platform.dal.mapper.monitor.DmMonBizLogMapper;
import com.clougence.clouddm.sdk.sql.parser.SplitScript;
import com.clougence.clouddm.sdk.sql.parser.SplitQueryType;
import com.clougence.utils.JsonUtils;

public class GovCorrectionServiceTest {

    private GovCorrectionService     service;

    private ApprovalDal             approvalDal;
    private DmApprovalMapper        approvalMapper;
    private DbChangeGovernDal       dbChangeGovernDal;
    private DmDbChangeStmtVersionMapper stmtVersionMapper;
    private DmDbChangeEventMapper   eventMapper;
    private ExecutionDal            executionDal;
    private DmExecAutoJobMapper     autoJobMapper;
    private DmExecAutoTaskMapper    autoTaskMapper;
    private MonitorDal              monitorDal;
    private DmMonBizLogMapper       bizLogMapper;
    private LogicalDbService        logicalDbService;
    private DmDsConfigService       dmDsConfigService;
    private QueryAnalysisService    queryAnalysisService;
    private AutoExecService         autoExecService;
    private PlatformTransactionManager txManager;

    private static final String     PUID        = "puid-001";
    private static final String     UID         = "uid-001";
    private static final long       TICKET_ID   = 200L;
    private static final long       JOB_ID      = 60L;
    private static final long       TASK_ID     = 300L;
    private static final long       LOGICAL_DB_ID = 10L;
    private static final String     BIZ_ID      = "ticket-biz-002";

    @Before
    public void setUp() {
        approvalDal = mock(ApprovalDal.class);
        dbChangeGovernDal = mock(DbChangeGovernDal.class);
        executionDal = mock(ExecutionDal.class);
        monitorDal = mock(MonitorDal.class);
        logicalDbService = mock(LogicalDbService.class);
        dmDsConfigService = mock(DmDsConfigService.class);
        queryAnalysisService = mock(QueryAnalysisService.class);
        autoExecService = mock(AutoExecService.class);
        txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        approvalMapper = mock(DmApprovalMapper.class);
        stmtVersionMapper = mock(DmDbChangeStmtVersionMapper.class);
        eventMapper = mock(DmDbChangeEventMapper.class);
        autoJobMapper = mock(DmExecAutoJobMapper.class);
        autoTaskMapper = mock(DmExecAutoTaskMapper.class);
        bizLogMapper = mock(DmMonBizLogMapper.class);

        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);
        when(dbChangeGovernDal.stmtVersionMapper()).thenReturn(stmtVersionMapper);
        when(dbChangeGovernDal.eventMapper()).thenReturn(eventMapper);
        when(executionDal.autoJobMapper()).thenReturn(autoJobMapper);
        when(executionDal.autoTaskMapper()).thenReturn(autoTaskMapper);
        when(monitorDal.bizLogMapper()).thenReturn(bizLogMapper);

        GovCorrectionServiceImpl impl = new GovCorrectionServiceImpl();
        ReflectionTestUtils.setField(impl, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(impl, "dbChangeGovernDal", dbChangeGovernDal);
        ReflectionTestUtils.setField(impl, "executionDal", executionDal);
        ReflectionTestUtils.setField(impl, "monitorDal", monitorDal);
        ReflectionTestUtils.setField(impl, "logicalDbService", logicalDbService);
        ReflectionTestUtils.setField(impl, "dmDsConfigService", dmDsConfigService);
        ReflectionTestUtils.setField(impl, "queryAnalysisService", queryAnalysisService);
        ReflectionTestUtils.setField(impl, "autoExecService", autoExecService);
        ReflectionTestUtils.setField(impl, "txManager", txManager);
        service = impl;
    }

    @Test
    public void correct_nonSubmitter_rejected() {
        setupTicket(UID, ApprovalStatus.EXEC_FAIL, GovRole.PRE.name());
        setupJob();
        setupFailedTask();

        GovCorrectStatementFO fo = buildFO();
        // Different uid
        try {
            service.correctStatement(PUID, "other-uid", fo);
            fail("Should reject non-submitter");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("submitter"));
        }
        verify(autoExecService, never()).replaceTask(any(), anyLong(), any());
    }

    @Test
    public void correct_nonExecFail_rejected() {
        setupTicket(UID, ApprovalStatus.WAIT_APPROVAL, GovRole.PRE.name());
        setupJob();
        setupFailedTask();

        GovCorrectStatementFO fo = buildFO();
        try {
            service.correctStatement(PUID, UID, fo);
            fail("Should reject non-EXEC_FAIL");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("EXEC_FAIL"));
        }
        verify(autoExecService, never()).replaceTask(any(), anyLong(), any());
    }

    @Test
    public void correct_prodTicket_rejected() {
        setupTicket(UID, ApprovalStatus.EXEC_FAIL, GovRole.PROD.name());
        setupJob();
        setupFailedTask();

        GovCorrectStatementFO fo = buildFO();
        try {
            service.correctStatement(PUID, UID, fo);
            fail("Should reject PROD ticket");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("PRE"));
        }
        verify(autoExecService, never()).replaceTask(any(), anyLong(), any());
    }

    @Test
    public void correct_nonGovernanceTicket_rejected() {
        setupTicket(UID, ApprovalStatus.EXEC_FAIL, null);
        setupJob();
        setupFailedTask();

        GovCorrectStatementFO fo = buildFO();
        try {
            service.correctStatement(PUID, UID, fo);
            fail("Should reject non-governance ticket");
        } catch (ErrorMessageException e) {
            // Expected — either "PRE" or "not a DM_CHANGE"
        }
        verify(autoExecService, never()).replaceTask(any(), anyLong(), any());
    }

    @Test
    public void correct_ruleViolation_rejectedVersionNotIncremented() {
        setupTicket(UID, ApprovalStatus.EXEC_FAIL, GovRole.PRE.name());
        setupJob();
        setupFailedTask();
        setupBinding();
        setupStmtVersion(1, "INSERT INTO t VALUES (1)", "INSERT INTO t VALUES (2)");
        setupBizLog("Syntax error near 'VALUES'");

        // Rule audit returns FAILURE
        when(queryAnalysisService.analysisRulesStream(any(), any(), any(), anyInt(), anyInt(), any()))
            .thenReturn(java.util.stream.Stream.of(createFailureRuleResult()));

        GovCorrectStatementFO fo = buildFO();
        try {
            service.correctStatement(PUID, UID, fo);
            fail("Should reject rule violation");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("rejected"));
        }
        // No stmt_version insert, no replaceTask, no retryJob
        verify(stmtVersionMapper, never()).insert(any(DmDbChangeStmtVersionDO.class));
        verify(autoExecService, never()).replaceTask(any(), anyLong(), any());
    }

    @Test
    public void correct_idempotentGuard_skipsToRetryJob() {
        setupTicket(UID, ApprovalStatus.EXEC_FAIL, GovRole.PRE.name());
        setupJob();
        // Task already CANCELED (replaceTask already ran in a previous call)
        DmExecAutoTaskDO canceledTask = buildTask(AutoExecTaskStatus.CANCELED);
        // Also a new WAIT_EXEC task from the previous replaceTask call
        DmExecAutoTaskDO newTask = buildTask(AutoExecTaskStatus.WAIT_EXEC);
        newTask.setId(301L);
        when(autoTaskMapper.queryListByJobId(JOB_ID, null)).thenReturn(List.of(canceledTask, newTask));

        // stmt_version already has the corrected hash
        String newSql = "INSERT INTO t VALUES (2)";
        String newHash = GovSqlHashUtils.hash(newSql);
        DmDbChangeStmtVersionDO v1 = buildStmtVersion(1, 1, "INSERT INTO t VALUES (1)", GovSqlHashUtils.hash("INSERT INTO t VALUES (1)"));
        DmDbChangeStmtVersionDO v2 = buildStmtVersion(1, 2, newSql, newHash);
        when(stmtVersionMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(v1, v2));

        GovCorrectStatementFO fo = buildFO();
        fo.setNewSql(newSql);

        long result = service.correctStatement(PUID, UID, fo);

        assertEquals(LOGICAL_DB_ID, result);
        // Should skip version insert + replaceTask, go straight to retryJob
        verify(stmtVersionMapper, never()).insert(any(DmDbChangeStmtVersionDO.class));
        verify(autoExecService, never()).replaceTask(any(), anyLong(), any());
        verify(autoExecService).retryJob(BIZ_ID);
    }

    @Test
    public void correct_idempotentGuard_versionWrittenButReplaceTaskNotDone() {
        // Scenario: Transaction A succeeded (version written), but replaceTask failed.
        // Task is still FAILED (not CANCELED). Retry should skip version insert and do replaceTask only.
        setupTicket(UID, ApprovalStatus.EXEC_FAIL, GovRole.PRE.name());
        setupJob();
        // Task still FAILED (replaceTask was not done in previous call)
        DmExecAutoTaskDO failedTask = buildTask(AutoExecTaskStatus.FAILED);
        when(autoTaskMapper.queryListByJobId(JOB_ID, null)).thenReturn(List.of(failedTask));

        // stmt_version already has the corrected hash (version 2 = previous correction attempt)
        String newSql = "INSERT INTO t VALUES (2)";
        String newHash = GovSqlHashUtils.hash(newSql);
        DmDbChangeStmtVersionDO v1 = buildStmtVersion(1, 1, "INSERT INTO t VALUES (1)", GovSqlHashUtils.hash("INSERT INTO t VALUES (1)"));
        DmDbChangeStmtVersionDO v2 = buildStmtVersion(1, 2, newSql, newHash);
        when(stmtVersionMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(v1, v2));

        GovCorrectStatementFO fo = buildFO();
        fo.setNewSql(newSql);

        long result = service.correctStatement(PUID, UID, fo);

        assertEquals(LOGICAL_DB_ID, result);
        // Should NOT insert another version (no duplicate CORRECTION row)
        verify(stmtVersionMapper, never()).insert(any(DmDbChangeStmtVersionDO.class));
        // Should call replaceTask (was not done in previous attempt)
        verify(autoExecService).replaceTask(BIZ_ID, TASK_ID, newSql);
        // Should call retryJob
        verify(autoExecService).retryJob(BIZ_ID);
    }

    // ======= B1: transactional full-rollback rerun =======

    @Test
    public void correct_transactionalFullRollback_allTasksRollback_retryJobResetsAll() {
        // Pure DML ticket (enableTransactional=true): engine rolls back ALL tasks on failure.
        // 3 statements, all in ROLLBACK state after transactional failure.
        setupTicket(UID, ApprovalStatus.EXEC_FAIL, GovRole.PRE.name());
        setupJob();
        long taskId1 = 300L;
        long taskId2 = 301L;
        long taskId3 = 302L;
        DmExecAutoTaskDO task1 = buildTask(taskId1, 1, AutoExecTaskStatus.ROLLBACK, "INSERT INTO t VALUES (1)");
        DmExecAutoTaskDO task2 = buildTask(taskId2, 2, AutoExecTaskStatus.ROLLBACK, "INSERT INTO t VALUES (2)");
        DmExecAutoTaskDO task3 = buildTask(taskId3, 3, AutoExecTaskStatus.ROLLBACK, "INSERT INTO t VALUES (3)");
        when(autoTaskMapper.queryListByJobId(JOB_ID, null)).thenReturn(List.of(task1, task2, task3));

        // stmt_version v1 for stmt_index=2 (the statement being corrected)
        String oldSql = "INSERT INTO t VALUES (2)";
        String newSql = "INSERT INTO t VALUES (22)";
        DmDbChangeStmtVersionDO v1 = buildStmtVersion(2, 1, oldSql, GovSqlHashUtils.hash(oldSql));
        when(stmtVersionMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(v1));

        setupBinding();
        setupAuditPass();
        setupBizLog("auto-Task-301", "constraint violation on t");

        GovCorrectStatementFO fo = new GovCorrectStatementFO();
        fo.setTicketId(TICKET_ID);
        fo.setStmtIndex(2);
        fo.setNewSql(newSql);
        fo.setReason("fix value");

        service.correctStatement(PUID, UID, fo);

        // replaceTask called only for the corrected statement (task2) — tasks 1 and 3 untouched
        ArgumentCaptor<String> bizIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> taskIdCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(autoExecService, times(1)).replaceTask(bizIdCaptor.capture(), taskIdCaptor.capture(), sqlCaptor.capture());
        assertEquals(BIZ_ID, bizIdCaptor.getValue());
        assertEquals(taskId2, taskIdCaptor.getValue().longValue());
        assertEquals(newSql, sqlCaptor.getValue());

        // retryJob called — this resets ALL ROLLBACK tasks (the "all re-run" contract)
        verify(autoExecService).retryJob(BIZ_ID);
    }

    // ======= B2: mixed ticket DDL already applied =======

    @Test
    public void correct_mixedTicket_ddlSuccessDmlFailed_onlyFailedTaskReplaced() {
        // Mixed ticket (DDL + DML, non-transactional): DDL succeeds, DML fails.
        // DDL task is FINISH (already applied, not replayed); DML task is FAILED.
        setupTicket(UID, ApprovalStatus.EXEC_FAIL, GovRole.PRE.name());
        setupJob();
        long ddlTaskId = 300L;
        long dmlTaskId = 301L;
        DmExecAutoTaskDO ddlTask = buildTask(ddlTaskId, 1, AutoExecTaskStatus.FINISH, "CREATE TABLE t (id INT)");
        DmExecAutoTaskDO dmlTask = buildTask(dmlTaskId, 2, AutoExecTaskStatus.FAILED, "INSERT INTO t VALUES (2)");
        when(autoTaskMapper.queryListByJobId(JOB_ID, null)).thenReturn(List.of(ddlTask, dmlTask));

        String oldSql = "INSERT INTO t VALUES (2)";
        String newSql = "INSERT INTO t VALUES (22)";
        DmDbChangeStmtVersionDO v1 = buildStmtVersion(2, 1, oldSql, GovSqlHashUtils.hash(oldSql));
        when(stmtVersionMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(v1));

        setupBinding();
        setupAuditPass();
        setupBizLog("auto-Task-301", "duplicate key");

        GovCorrectStatementFO fo = new GovCorrectStatementFO();
        fo.setTicketId(TICKET_ID);
        fo.setStmtIndex(2);
        fo.setNewSql(newSql);
        fo.setReason("fix value");

        service.correctStatement(PUID, UID, fo);

        // replaceTask called only for the FAILED DML task — DDL task (FINISH) never touched
        verify(autoExecService, times(1)).replaceTask(BIZ_ID, dmlTaskId, newSql);
        verify(autoExecService, never()).replaceTask(any(), eq(ddlTaskId), any());

        // retryJob called — engine resumes from failure point (FINISH tasks not replayed)
        verify(autoExecService).retryJob(BIZ_ID);
    }

    // ======= B3: first success full chain =======

    @Test
    public void correct_firstSuccess_fullChain_versionIncrementEventReplaceTaskRetryJob() {
        // Only v1 (INITIAL) exists — first correction from version 1 to version 2.
        setupTicket(UID, ApprovalStatus.EXEC_FAIL, GovRole.PRE.name());
        setupJob();
        setupFailedTask();
        String oldSql = "INSERT INTO t VALUES (1)";
        String newSql = "INSERT INTO t VALUES (2)";
        DmDbChangeStmtVersionDO v1 = buildStmtVersion(1, 1, oldSql, GovSqlHashUtils.hash(oldSql));
        when(stmtVersionMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(v1));

        setupBinding();
        setupAuditPass();
        setupBizLog("auto-Task-old001", "syntax error");

        GovCorrectStatementFO fo = new GovCorrectStatementFO();
        fo.setTicketId(TICKET_ID);
        fo.setStmtIndex(1);
        fo.setNewSql(newSql);
        fo.setReason("fix typo");

        service.correctStatement(PUID, UID, fo);

        // Version row: version=2, source=CORRECTION, hash matches new SQL
        ArgumentCaptor<DmDbChangeStmtVersionDO> stmtCaptor = ArgumentCaptor.forClass(DmDbChangeStmtVersionDO.class);
        verify(stmtVersionMapper).insert(stmtCaptor.capture());
        DmDbChangeStmtVersionDO inserted = stmtCaptor.getValue();
        assertEquals(Long.valueOf(TICKET_ID), inserted.getTicketId());
        assertEquals(Integer.valueOf(1), inserted.getStmtIndex());
        assertEquals(Integer.valueOf(2), inserted.getStmtVersion());
        assertEquals(newSql, inserted.getStmtText());
        assertEquals(GovSqlHashUtils.hash(newSql), inserted.getStmtHash());
        assertEquals(StmtSource.CORRECTION.name(), inserted.getSource());
        assertEquals(UID, inserted.getOperatorUid());
        assertEquals("syntax error", inserted.getFailReason());

        // CORRECTION event: fromVersion=1, toVersion=2, reason
        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        DmDbChangeEventDO event = eventCaptor.getValue();
        assertEquals(GovEventType.CORRECTION.name(), event.getEventType());
        assertEquals(ApprovalStatus.EXEC_FAIL.name(), event.getFromStatus());
        assertEquals(ApprovalStatus.EXEC_FAIL.name(), event.getToStatus());
        assertEquals(UID, event.getOperatorUid());
        java.util.Map<String, Object> data = JsonUtils.toObj(event.getEventData(), java.util.HashMap.class);
        assertEquals(1, data.get("stmtIndex"));
        assertEquals(1, data.get("fromVersion"));
        assertEquals(2, data.get("toVersion"));
        assertEquals("fix typo", data.get("reason"));

        // replaceTask called with correct args
        verify(autoExecService).replaceTask(BIZ_ID, TASK_ID, newSql);
        // retryJob called
        verify(autoExecService).retryJob(BIZ_ID);
    }

    // ======= helpers =======

    private GovCorrectStatementFO buildFO() {
        GovCorrectStatementFO fo = new GovCorrectStatementFO();
        fo.setTicketId(TICKET_ID);
        fo.setStmtIndex(1);
        fo.setNewSql("INSERT INTO t VALUES (2)");
        fo.setReason("fix typo");
        return fo;
    }

    private void setupTicket(String ownerUid, ApprovalStatus status, String govRole) {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setOwnerUid(ownerUid);
        ticket.setPrimaryUid(PUID);
        ticket.setApproBiz(ApprovalBiz.DM_CHANGE);
        ticket.setTicketStatus(status);
        ticket.setBizId(BIZ_ID);
        ticket.setBindDsId(20L);
        ApprovalMO mo = new ApprovalMO();
        mo.setLogicalDbId(LOGICAL_DB_ID);
        mo.setGovRole(govRole);
        ticket.setTicketInfo(JsonUtils.toJson(mo));
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket);
    }

    private void setupJob() {
        DmExecAutoJobDO job = new DmExecAutoJobDO();
        job.setId(JOB_ID);
        job.setDependOnBizId(BIZ_ID);
        when(autoJobMapper.queryByDependOnBizId(BIZ_ID)).thenReturn(job);
    }

    private void setupFailedTask() {
        DmExecAutoTaskDO task = buildTask(AutoExecTaskStatus.FAILED);
        when(autoTaskMapper.queryListByJobId(JOB_ID, null)).thenReturn(List.of(task));
    }

    private DmExecAutoTaskDO buildTask(AutoExecTaskStatus status) {
        DmExecAutoTaskDO task = new DmExecAutoTaskDO();
        task.setId(TASK_ID);
        task.setAutoExecJobId(JOB_ID);
        task.setExecOrder(1);
        task.setStatus(status);
        task.setBizId("auto-Task-old001");
        task.setQueryId("old-query-id");
        task.setExecSql("INSERT INTO t VALUES (1)");
        return task;
    }

    private DmExecAutoTaskDO buildTask(long id, int execOrder, AutoExecTaskStatus status, String execSql) {
        DmExecAutoTaskDO task = new DmExecAutoTaskDO();
        task.setId(id);
        task.setAutoExecJobId(JOB_ID);
        task.setExecOrder(execOrder);
        task.setStatus(status);
        task.setBizId("auto-Task-" + id);
        task.setQueryId("old-query-" + id);
        task.setExecSql(execSql);
        return task;
    }

    private void setupAuditPass() {
        // Rule audit: no FAILURE results (empty SecRulesCheckResult)
        when(queryAnalysisService.analysisRulesStream(any(), any(), any(), anyInt(), anyInt(), any()))
            .thenReturn(Stream.of(new SecRulesCheckResult()));

        // Behavior analysis: all SQL is DML (INSERT) — used by isDml/isDdl checks
        SplitScript dmlScript = new SplitScript();
        dmlScript.setType(EnumSet.of(SplitQueryType.INSERT));
        dmlScript.setScript("INSERT INTO t VALUES (2)");
        when(queryAnalysisService.analysisSplitStream(any(), any(), any(), anyInt(), anyInt()))
            .thenAnswer(invocation -> Stream.of(dmlScript));
    }

    private void setupBinding() {
        LogicalDbTarget target = new LogicalDbTarget();
        target.setBindingId(1L);
        target.setLogicalDbId(LOGICAL_DB_ID);
        target.setEnvId(5L);
        target.setDsId(20L);
        target.setResPath("/mydb/");
        target.setGovRole(GovRole.PRE);
        when(logicalDbService.getBinding(PUID, LOGICAL_DB_ID, GovRole.PRE)).thenReturn(target);
        when(dmDsConfigService.fetchDsConfigFromExists(20L)).thenReturn(mock(DataSourceConfig.class));
        when(dmDsConfigService.parseLevels(anyList())).thenReturn(
            new com.clougence.clouddm.console.web.component.dsconfig.mode.DsLevels(
                "5", null, List.of("5", "20", "mydb"), List.of("5", "20", "mydb"),
                Collections.emptyList(), new java.util.HashMap<>()));
    }

    private void setupStmtVersion(int stmtIndex, String oldSql, String newSql) {
        DmDbChangeStmtVersionDO v1 = buildStmtVersion(stmtIndex, 1, oldSql, GovSqlHashUtils.hash(oldSql));
        when(stmtVersionMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(v1));
    }

    private DmDbChangeStmtVersionDO buildStmtVersion(int stmtIndex, int version, String sql, String hash) {
        DmDbChangeStmtVersionDO stmt = new DmDbChangeStmtVersionDO();
        stmt.setTicketId(TICKET_ID);
        stmt.setStmtIndex(stmtIndex);
        stmt.setStmtVersion(version);
        stmt.setStmtText(sql);
        stmt.setStmtHash(hash);
        stmt.setSource(StmtSource.INITIAL.name());
        stmt.setOperatorUid(UID);
        return stmt;
    }

    private void setupBizLog(String errorContent) {
        setupBizLog("auto-Task-old001", errorContent);
    }

    private void setupBizLog(String taskBizId, String errorContent) {
        DmMonBizLogDO logDO = new DmMonBizLogDO(Loglevel.ERROR, errorContent, LogDependBizType.AUTO_EXEC_TASK, taskBizId);
        when(bizLogMapper.queryListByBizIdAndType(taskBizId, LogDependBizType.AUTO_EXEC_TASK))
            .thenReturn(List.of(logDO));
    }

    private com.clougence.clouddm.console.web.component.detectrule.SecRulesCheckResult createFailureRuleResult() {
        com.clougence.clouddm.console.web.component.detectrule.SecRulesCheckResult result =
            new com.clougence.clouddm.console.web.component.detectrule.SecRulesCheckResult();
        result.addResult("test-rule", com.clougence.clouddm.sdk.service.secrules.RuleLevel.FAILURE, null, "blocked");
        return result;
    }
}
