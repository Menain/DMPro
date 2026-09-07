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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.component.governance.GovSqlHashUtils;
import com.clougence.clouddm.console.web.model.vo.logicaldb.LogicalDbTarget;
import com.clougence.clouddm.console.web.service.governance.RevisionFreezeService;
import com.clougence.clouddm.console.web.service.logicaldb.LogicalDbService;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.DbChangeGovernDal;
import com.clougence.clouddm.platform.dal.access.ExecutionDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalProcessActivityMapper;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalProcessMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangeEventMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangeRevisionMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangeStmtVersionMapper;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoJobMapper;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoTaskMapper;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStage;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalProcessActivityDO;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalProcessDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeRevisionDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeStmtVersionDO;
import com.clougence.clouddm.platform.dal.model.dbchange.GovEventType;
import com.clougence.clouddm.platform.dal.model.dbchange.RevisionSourceType;
import com.clougence.clouddm.platform.dal.model.execution.AutoExecTaskStatus;
import com.clougence.clouddm.platform.dal.model.execution.DmExecAutoJobDO;
import com.clougence.clouddm.platform.dal.model.execution.DmExecAutoTaskDO;
import com.clougence.clouddm.platform.dal.model.logicaldb.GovRole;
import com.clougence.utils.JsonUtils;

public class RevisionFreezeServiceTest {

    private RevisionFreezeService service;

    private ApprovalDal                 approvalDal;
    private DmApprovalMapper            approvalMapper;
    private DmApprovalProcessMapper     processMapper;
    private DmApprovalProcessActivityMapper activityMapper;
    private DbChangeGovernDal          dbChangeGovernDal;
    private DmDbChangeRevisionMapper    revisionMapper;
    private DmDbChangeStmtVersionMapper stmtVersionMapper;
    private DmDbChangeEventMapper       eventMapper;
    private ExecutionDal               executionDal;
    private DmExecAutoJobMapper         autoJobMapper;
    private DmExecAutoTaskMapper        autoTaskMapper;
    private LogicalDbService            logicalDbService;

    private static final String  PUID        = "puid-001";
    private static final long    TICKET_ID   = 200L;
    private static final long    LOGICAL_DB_ID = 10L;
    private static final long    REVISION_ID = 300L;
    private static final String  BIZ_ID      = "biz-001";
    private static final long    JOB_ID      = 400L;

    @Before
    public void setUp() {
        approvalDal = mock(ApprovalDal.class);
        dbChangeGovernDal = mock(DbChangeGovernDal.class);
        executionDal = mock(ExecutionDal.class);
        logicalDbService = mock(LogicalDbService.class);

        approvalMapper = mock(DmApprovalMapper.class);
        processMapper = mock(DmApprovalProcessMapper.class);
        activityMapper = mock(DmApprovalProcessActivityMapper.class);
        revisionMapper = mock(DmDbChangeRevisionMapper.class);
        stmtVersionMapper = mock(DmDbChangeStmtVersionMapper.class);
        eventMapper = mock(DmDbChangeEventMapper.class);
        autoJobMapper = mock(DmExecAutoJobMapper.class);
        autoTaskMapper = mock(DmExecAutoTaskMapper.class);

        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);
        when(approvalDal.processMapper()).thenReturn(processMapper);
        when(approvalDal.activityMapper()).thenReturn(activityMapper);
        when(dbChangeGovernDal.revisionMapper()).thenReturn(revisionMapper);
        when(dbChangeGovernDal.stmtVersionMapper()).thenReturn(stmtVersionMapper);
        when(dbChangeGovernDal.eventMapper()).thenReturn(eventMapper);
        when(executionDal.autoJobMapper()).thenReturn(autoJobMapper);
        when(executionDal.autoTaskMapper()).thenReturn(autoTaskMapper);

        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        RevisionFreezeServiceImpl impl = new RevisionFreezeServiceImpl();
        ReflectionTestUtils.setField(impl, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(impl, "dbChangeGovernDal", dbChangeGovernDal);
        ReflectionTestUtils.setField(impl, "executionDal", executionDal);
        ReflectionTestUtils.setField(impl, "logicalDbService", logicalDbService);
        ReflectionTestUtils.setField(impl, "txManager", txManager);

        service = impl;

        // Default: no finished tickets
        when(approvalMapper.listFinishedTicketIdList(any())).thenReturn(Collections.emptyList());
    }

    @Test
    public void freeze_success_manifestStructureAndHashConsistency() {
        String rawSql = "CREATE TABLE foo (id INT)";
        String rollbackSql = "DROP TABLE foo";

        setupFinishedGovernanceTicket(rawSql, rollbackSql);
        setupNoExistingRevision();
        setupStmtVersions();
        setupFinishedTasks();
        setupEmptyActivities();
        setupBinding();
        setupSubmitEvent("DDL");

        // revisionMapper.insert should succeed and assign ID
        doAnswer(invocation -> {
            DmDbChangeRevisionDO rev = invocation.getArgument(0);
            rev.setId(REVISION_ID);
            return 1;
        }).when(revisionMapper).insert(any(DmDbChangeRevisionDO.class));

        service.freezeFinishedRevisions();

        ArgumentCaptor<DmDbChangeRevisionDO> revCaptor = ArgumentCaptor.forClass(DmDbChangeRevisionDO.class);
        verify(revisionMapper).insert(revCaptor.capture());
        DmDbChangeRevisionDO revision = revCaptor.getValue();

        assertEquals(Long.valueOf(TICKET_ID), revision.getSourceTicketId());
        assertEquals(Long.valueOf(LOGICAL_DB_ID), revision.getLogicalDbId());
        assertEquals(RevisionSourceType.PRE_TICKET.name(), revision.getSourceType());
        assertEquals("DDL", revision.getChangeType());
        assertEquals(rawSql, revision.getSqlText());
        assertEquals(rollbackSql, revision.getRollbackSqlText());
        assertEquals(GovSqlHashUtils.hash(rawSql), revision.getSqlHash());
        assertEquals(GovSqlHashUtils.hash(rollbackSql), revision.getRollbackSqlHash());
        assertNotNull(revision.getStmtManifest());
        assertNotNull(revision.getRevisionCode());
        assertTrue(revision.getRevisionCode().startsWith("REV-"));

        // Verify manifest structure
        List<Map<String, Object>> manifest = JsonUtils.toObj(revision.getStmtManifest(), List.class);
        assertEquals(2, manifest.size());
        Map<String, Object> stmt1 = manifest.get(0);
        assertEquals(1, stmt1.get("idx"));
        assertEquals("hash-1", stmt1.get("stmt_hash"));
        assertEquals(1, stmt1.get("version"));
        assertEquals("SUCCESS", stmt1.get("pre_exec"));

        // Verify REVISION_FROZEN event
        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        DmDbChangeEventDO event = eventCaptor.getValue();
        assertEquals(GovEventType.REVISION_FROZEN.name(), event.getEventType());
        assertEquals("SYSTEM", event.getOperatorUid());
        assertEquals(Long.valueOf(REVISION_ID), event.getRevisionId());
    }

    @Test
    public void freeze_idempotent_secondScanNoInsert() {
        setupFinishedGovernanceTicket("CREATE TABLE foo (id INT)", null);
        DmDbChangeRevisionDO existing = new DmDbChangeRevisionDO();
        existing.setId(REVISION_ID);
        existing.setSourceTicketId(TICKET_ID);
        when(revisionMapper.queryBySourceTicketId(TICKET_ID)).thenReturn(existing);

        service.freezeFinishedRevisions();

        verify(revisionMapper, never()).insert(any(DmDbChangeRevisionDO.class));
        verify(eventMapper, never()).insert(any(DmDbChangeEventDO.class));
    }

    @Test
    public void freeze_anomalyTaskNotFinished_freezeAnomalyEventNoRevision() {
        setupFinishedGovernanceTicket("INSERT INTO foo VALUES (1)", "DELETE FROM foo");
        setupNoExistingRevision();
        setupStmtVersions();
        setupNonFinishedTasks();
        setupBinding();
        setupSubmitEvent("DML");

        service.freezeFinishedRevisions();

        verify(revisionMapper, never()).insert(any(DmDbChangeRevisionDO.class));

        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        DmDbChangeEventDO event = eventCaptor.getValue();
        assertEquals(GovEventType.FREEZE_ANOMALY.name(), event.getEventType());
        assertEquals("SYSTEM", event.getOperatorUid());
    }

    @Test
    public void freeze_nonGovernanceTicket_skipped() {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setApproBiz(ApprovalBiz.DM_CHANGE);
        ticket.setTicketStatus(ApprovalStatus.FINISHED);
        ticket.setPrimaryUid(PUID);
        ticket.setBizId(BIZ_ID);
        ticket.setTicketInfo(JsonUtils.toJson(new ApprovalMO()));

        when(approvalMapper.listFinishedTicketIdList(ApprovalBiz.DM_CHANGE)).thenReturn(List.of(TICKET_ID));
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket);

        service.freezeFinishedRevisions();

        verify(revisionMapper, never()).insert(any(DmDbChangeRevisionDO.class));
    }

    @Test
    public void freeze_duplicateKeyOnSourceTicket_silentSkip() {
        setupFinishedGovernanceTicket("CREATE TABLE foo (id INT)", null);
        setupNoExistingRevision();
        setupStmtVersions();
        setupFinishedTasks();
        setupEmptyActivities();
        setupBinding();
        setupSubmitEvent("DDL");

        // First insert throws DuplicateKeyException (UNIQUE(source_ticket_id) collision from concurrent scan)
        doThrow(new DuplicateKeyException("Duplicate entry"))
            .when(revisionMapper).insert(any(DmDbChangeRevisionDO.class));

        // queryBySourceTicketId returns existing on retry
        DmDbChangeRevisionDO existing = new DmDbChangeRevisionDO();
        existing.setId(REVISION_ID);
        when(revisionMapper.queryBySourceTicketId(TICKET_ID))
            .thenReturn(null)        // first check: no existing
            .thenReturn(existing);   // after DuplicateKey: existing found

        service.freezeFinishedRevisions();

        // Should have attempted insert, caught DuplicateKey, found existing, and silently returned
        verify(revisionMapper, atLeast(1)).insert(any(DmDbChangeRevisionDO.class));
    }

    @Test
    public void freeze_nullRollbackSql_rollbackHashIsNull() {
        setupFinishedGovernanceTicket("CREATE TABLE foo (id INT)", null);
        setupNoExistingRevision();
        setupStmtVersions();
        setupFinishedTasks();
        setupEmptyActivities();
        setupBinding();
        setupSubmitEvent("DDL");

        doAnswer(invocation -> {
            DmDbChangeRevisionDO rev = invocation.getArgument(0);
            rev.setId(REVISION_ID);
            return 1;
        }).when(revisionMapper).insert(any(DmDbChangeRevisionDO.class));

        service.freezeFinishedRevisions();

        ArgumentCaptor<DmDbChangeRevisionDO> revCaptor = ArgumentCaptor.forClass(DmDbChangeRevisionDO.class);
        verify(revisionMapper).insert(revCaptor.capture());
        assertNull(revCaptor.getValue().getRollbackSqlHash());
    }

    // ======= helpers =======

    private void setupFinishedGovernanceTicket(String rawSql, String rollbackSql) {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setApproBiz(ApprovalBiz.DM_CHANGE);
        ticket.setTicketStatus(ApprovalStatus.FINISHED);
        ticket.setPrimaryUid(PUID);
        ticket.setBizId(BIZ_ID);
        ticket.setRawSql(rawSql);
        ticket.setRollBackSql(rollbackSql);

        ApprovalMO mo = new ApprovalMO();
        mo.setLogicalDbId(LOGICAL_DB_ID);
        mo.setGovRole(GovRole.PRE.name());
        ticket.setTicketInfo(JsonUtils.toJson(mo));

        when(approvalMapper.listFinishedTicketIdList(ApprovalBiz.DM_CHANGE)).thenReturn(List.of(TICKET_ID));
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket);
    }

    private void setupNoExistingRevision() {
        when(revisionMapper.queryBySourceTicketId(TICKET_ID)).thenReturn(null);
    }

    private void setupStmtVersions() {
        DmDbChangeStmtVersionDO stmt1 = new DmDbChangeStmtVersionDO();
        stmt1.setTicketId(TICKET_ID);
        stmt1.setStmtIndex(1);
        stmt1.setStmtVersion(1);
        stmt1.setStmtText("CREATE TABLE foo (id INT)");
        stmt1.setStmtHash("hash-1");

        DmDbChangeStmtVersionDO stmt2 = new DmDbChangeStmtVersionDO();
        stmt2.setTicketId(TICKET_ID);
        stmt2.setStmtIndex(2);
        stmt2.setStmtVersion(1);
        stmt2.setStmtText("INSERT INTO foo VALUES (1)");
        stmt2.setStmtHash("hash-2");

        when(stmtVersionMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(stmt1, stmt2));
    }

    private void setupFinishedTasks() {
        DmExecAutoJobDO job = new DmExecAutoJobDO();
        job.setId(JOB_ID);
        when(autoJobMapper.queryByDependOnBizId(BIZ_ID)).thenReturn(job);

        DmExecAutoTaskDO task1 = new DmExecAutoTaskDO();
        task1.setExecOrder(1);
        task1.setStatus(AutoExecTaskStatus.FINISH);

        DmExecAutoTaskDO task2 = new DmExecAutoTaskDO();
        task2.setExecOrder(2);
        task2.setStatus(AutoExecTaskStatus.FINISH);

        when(autoTaskMapper.queryListByJobId(JOB_ID, null)).thenReturn(List.of(task1, task2));
    }

    private void setupNonFinishedTasks() {
        DmExecAutoJobDO job = new DmExecAutoJobDO();
        job.setId(JOB_ID);
        when(autoJobMapper.queryByDependOnBizId(BIZ_ID)).thenReturn(job);

        DmExecAutoTaskDO task1 = new DmExecAutoTaskDO();
        task1.setExecOrder(1);
        task1.setStatus(AutoExecTaskStatus.FINISH);

        DmExecAutoTaskDO task2 = new DmExecAutoTaskDO();
        task2.setExecOrder(2);
        task2.setStatus(AutoExecTaskStatus.FAILED);

        when(autoTaskMapper.queryListByJobId(JOB_ID, null)).thenReturn(List.of(task1, task2));
    }

    private void setupEmptyActivities() {
        DmApprovalProcessDO process = new DmApprovalProcessDO();
        process.setId(500L);
        process.setTicketId(TICKET_ID);
        process.setTicketStage(ApprovalStage.EXPLAIN);
        when(processMapper.queryByStage(TICKET_ID, ApprovalStage.EXPLAIN)).thenReturn(process);
        when(activityMapper.queryByTicketId(TICKET_ID)).thenReturn(Collections.emptyList());
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
    }

    private void setupSubmitEvent(String changeType) {
        DmDbChangeEventDO event = new DmDbChangeEventDO();
        event.setEventType(GovEventType.SUBMIT.name());
        Map<String, Object> data = new HashMap<>();
        data.put("changeType", changeType);
        event.setEventData(JsonUtils.toJson(data));
        when(eventMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(event));
    }
}
