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
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import com.clougence.clouddm.api.console.autoexec.ErrorStrategy;
import com.clougence.clouddm.base.metadata.ds.DataSourceConfig;
import com.clougence.clouddm.console.web.component.approval.ApprovalHandler;
import com.clougence.clouddm.console.web.component.approval.ApprovalStateService;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalStageMO;
import com.clougence.clouddm.console.web.component.auth.DmAuthServiceForBiz;
import com.clougence.clouddm.console.web.component.cicd.ImSenderService;
import com.clougence.clouddm.console.web.component.dsconfig.DmDsConfigService;
import com.clougence.clouddm.console.web.component.governance.GovSqlHashUtils;
import com.clougence.clouddm.console.web.component.governance.GovSplitResult;
import com.clougence.clouddm.console.web.component.governance.GovStmtRow;
import com.clougence.clouddm.console.web.component.governance.GovStmtSplitService;
import com.clougence.clouddm.console.web.model.fo.governance.GovPreSubmitFO;
import com.clougence.clouddm.console.web.model.fo.ticket.DmAddTicketFO;
import com.clougence.clouddm.console.web.model.fo.ticket.DmAutoExecConfigFO;
import com.clougence.clouddm.console.web.model.vo.envparam.DmEnvParamTicketDesVO;
import com.clougence.clouddm.console.web.model.vo.logicaldb.LogicalDbTarget;
import com.clougence.clouddm.console.web.model.vo.ticket.DmTicketResultVO;
import com.clougence.clouddm.console.web.service.approval.ApprovalControlService;
import com.clougence.clouddm.console.web.service.envparam.DmEnvParamService;
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
import com.clougence.clouddm.platform.dal.model.approval.ApprovalProcessStatus;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStage;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalType;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalProcessActivityDO;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalProcessDO;
import com.clougence.clouddm.platform.dal.model.approval.SqlContentType;
import com.clougence.clouddm.platform.dal.model.dbchange.ChangeType;
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

/**
 * C2: PRE full chain orchestration test (D-P11-7).
 * <p>
 * Wires real service instances (DbChangeGovernServiceImpl, GovAutoAdvanceServiceImpl,
 * RevisionFreezeServiceImpl) with mock DAL/engine boundaries. Uses in-memory event
 * and stmt_version stores to verify cross-service state flow.
 * <p>
 * Chain: preSubmit → promoter SYSTEM auto-advance → execution success → freeze.
 * Asserts: event sequence [SUBMIT → SYSTEM_APPROVE → SYSTEM_CONFIRM → REVISION_FROZEN],
 * revision row with manifest {idx, stmt_hash, version, pre_exec=SUCCESS}.
 * <p>
 * Real services: 3. Mock boundaries: 17 (DAL mappers ×8, LogicalDbService, ApprovalControlService,
 * DmEnvParamService, ApprovalStateService, ImSenderService, ApprovalHandler, txManager,
 * GovStmtSplitService, ExecutionDal).
 */
public class GovernanceChainPreTest {

    private DbChangeGovernServiceImpl  preSubmitService;
    private GovAutoAdvanceServiceImpl  autoAdvanceService;
    private RevisionFreezeServiceImpl  freezeService;

    private LogicalDbService        logicalDbService;
    private DmAuthServiceForBiz     dmAuthServiceForBiz;
    private DmDsConfigService        dmDsConfigService;
    private GovStmtSplitService     govStmtSplitService;
    private ApprovalControlService  approvalControlService;
    private DmEnvParamService        dmEnvParamService;
    private ApprovalStateService     approvalStateService;
    private ImSenderService          imSenderService;
    private ApprovalHandler          changeHandler;
    private ApprovalDal              approvalDal;
    private DbChangeGovernDal        dbChangeGovernDal;
    private ExecutionDal             executionDal;

    private DmApprovalMapper            approvalMapper;
    private DmApprovalProcessMapper     processMapper;
    private DmApprovalProcessActivityMapper activityMapper;
    private DmDbChangeStmtVersionMapper stmtVersionMapper;
    private DmDbChangeRevisionMapper    revisionMapper;
    private DmDbChangeEventMapper       eventMapper;
    private DmExecAutoJobMapper         autoJobMapper;
    private DmExecAutoTaskMapper        autoTaskMapper;

    private List<DmDbChangeEventDO>      eventStore;
    private List<DmDbChangeStmtVersionDO> stmtVersionStore;

    private static final String PUID = "puid-001";
    private static final String UID = "uid-001";
    private static final long LOGICAL_DB_ID = 10L;
    private static final long TICKET_ID = 100L;
    private static final long REVISION_ID = 200L;
    private static final long JOB_ID = 400L;
    private static final String BIZ_ID = "biz-001";
    private static final long ENV_ID = 5L;
    private static final long DS_ID = 20L;

    private static final String DDL_SQL = "CREATE TABLE foo (id INT)";

    @Before
    public void setUp() {
        logicalDbService = mock(LogicalDbService.class);
        dmAuthServiceForBiz = mock(DmAuthServiceForBiz.class);
        dmDsConfigService = mock(DmDsConfigService.class);
        govStmtSplitService = mock(GovStmtSplitService.class);
        approvalControlService = mock(ApprovalControlService.class);
        dmEnvParamService = mock(DmEnvParamService.class);
        approvalStateService = mock(ApprovalStateService.class);
        imSenderService = mock(ImSenderService.class);
        changeHandler = mock(ApprovalHandler.class);
        approvalDal = mock(ApprovalDal.class);
        dbChangeGovernDal = mock(DbChangeGovernDal.class);
        executionDal = mock(ExecutionDal.class);

        approvalMapper = mock(DmApprovalMapper.class);
        processMapper = mock(DmApprovalProcessMapper.class);
        activityMapper = mock(DmApprovalProcessActivityMapper.class);
        stmtVersionMapper = mock(DmDbChangeStmtVersionMapper.class);
        revisionMapper = mock(DmDbChangeRevisionMapper.class);
        eventMapper = mock(DmDbChangeEventMapper.class);
        autoJobMapper = mock(DmExecAutoJobMapper.class);
        autoTaskMapper = mock(DmExecAutoTaskMapper.class);

        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);
        when(approvalDal.processMapper()).thenReturn(processMapper);
        when(approvalDal.activityMapper()).thenReturn(activityMapper);
        when(dbChangeGovernDal.stmtVersionMapper()).thenReturn(stmtVersionMapper);
        when(dbChangeGovernDal.revisionMapper()).thenReturn(revisionMapper);
        when(dbChangeGovernDal.eventMapper()).thenReturn(eventMapper);
        when(executionDal.autoJobMapper()).thenReturn(autoJobMapper);
        when(executionDal.autoTaskMapper()).thenReturn(autoTaskMapper);
        when(changeHandler.handleType()).thenReturn(ApprovalBiz.DM_CHANGE);

        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        // Wire preSubmit service
        preSubmitService = new DbChangeGovernServiceImpl();
        ReflectionTestUtils.setField(preSubmitService, "logicalDbService", logicalDbService);
        ReflectionTestUtils.setField(preSubmitService, "dmAuthServiceForBiz", dmAuthServiceForBiz);
        ReflectionTestUtils.setField(preSubmitService, "dmDsConfigService", dmDsConfigService);
        ReflectionTestUtils.setField(preSubmitService, "govStmtSplitService", govStmtSplitService);
        ReflectionTestUtils.setField(preSubmitService, "approvalControlService", approvalControlService);
        ReflectionTestUtils.setField(preSubmitService, "dbChangeGovernDal", dbChangeGovernDal);
        ReflectionTestUtils.setField(preSubmitService, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(preSubmitService, "executionDal", executionDal);
        ReflectionTestUtils.setField(preSubmitService, "txManager", txManager);

        // Wire auto-advance service
        autoAdvanceService = new GovAutoAdvanceServiceImpl(List.of(changeHandler));
        ReflectionTestUtils.setField(autoAdvanceService, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(autoAdvanceService, "dbChangeGovernDal", dbChangeGovernDal);
        ReflectionTestUtils.setField(autoAdvanceService, "logicalDbService", logicalDbService);
        ReflectionTestUtils.setField(autoAdvanceService, "dmEnvParamService", dmEnvParamService);
        ReflectionTestUtils.setField(autoAdvanceService, "approvalStateService", approvalStateService);
        ReflectionTestUtils.setField(autoAdvanceService, "approvalControlService", approvalControlService);
        ReflectionTestUtils.setField(autoAdvanceService, "imSenderService", imSenderService);

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
    public void preFullChain_preSubmit_autoAdvance_freeze() {
        // === Step 1: preSubmit ===
        setupPreBinding();
        setupSplit(ChangeType.DDL, DDL_SQL);
        setupTicketCreation();
        setupExistingTicketInfo();

        GovPreSubmitFO fo = new GovPreSubmitFO();
        fo.setLogicalDbId(LOGICAL_DB_ID);
        fo.setTicketTitle("Test PRE Ticket");
        fo.setDescription("Test description");
        fo.setSql(DDL_SQL);
        fo.setContentType(SqlContentType.INLINE);

        DmTicketResultVO result = preSubmitService.preSubmit(PUID, UID, fo);
        assertNotNull(result);
        assertEquals(Long.valueOf(TICKET_ID), result.getTicketId());

        // Verify SUBMIT event
        assertEquals(1, eventStore.size());
        assertEquals(GovEventType.SUBMIT.name(), eventStore.get(0).getEventType());
        assertEquals(UID, eventStore.get(0).getOperatorUid());

        // Verify stmt_version written
        assertEquals(1, stmtVersionStore.size());
        DmDbChangeStmtVersionDO stmt = stmtVersionStore.get(0);
        assertEquals(1, stmt.getStmtVersion().intValue());
        assertEquals(GovSqlHashUtils.hash(DDL_SQL), stmt.getStmtHash());

        // Verify ticketInfo updated with gov fields
        ArgumentCaptor<String> infoCaptor = ArgumentCaptor.forClass(String.class);
        verify(approvalMapper).updateTicketInfo(eq(TICKET_ID), infoCaptor.capture());
        ApprovalMO mo = JsonUtils.toObj(infoCaptor.getValue(), ApprovalMO.class);
        assertEquals(Long.valueOf(LOGICAL_DB_ID), mo.getLogicalDbId());
        assertEquals(GovRole.PRE.name(), mo.getGovRole());

        // === Step 2: promoter SYSTEM auto-advance ===
        DmApprovalDO waitApprovalTicket = buildGovernanceTicket(ApprovalStatus.WAIT_APPROVAL);
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(waitApprovalTicket);
        when(approvalMapper.listUnFinishTicketIdList()).thenReturn(List.of(TICKET_ID));
        setupInternalTemplate();

        autoAdvanceService.advancePreTickets();

        // Verify SYSTEM_APPROVE + SYSTEM_CONFIRM events
        assertTrue("Expected at least 3 events", eventStore.size() >= 3);
        assertEquals(GovEventType.SYSTEM_APPROVE.name(), eventStore.get(1).getEventType());
        assertEquals("SYSTEM", eventStore.get(1).getOperatorUid());
        assertEquals(GovEventType.SYSTEM_CONFIRM.name(), eventStore.get(2).getEventType());
        assertEquals("SYSTEM", eventStore.get(2).getOperatorUid());

        // Verify SYSTEM operator in approval stage
        ArgumentCaptor<String> contextCaptor = ArgumentCaptor.forClass(String.class);
        verify(approvalStateService).updateProcessStatus(eq(TICKET_ID), eq(ApprovalStage.APPROVAL),
            eq(ApprovalProcessStatus.FINISH), contextCaptor.capture());
        ApprovalStageMO stageMO = JsonUtils.toObj(contextCaptor.getValue(), ApprovalStageMO.class);
        assertEquals(List.of("SYSTEM"), stageMO.getExecUserName());

        // Verify handler.approvalApproved called
        verify(changeHandler).approvalApproved(eq(TICKET_ID), eq(ApprovalBiz.DM_CHANGE), eq(imSenderService));

        // Verify D15 config: DDL → enableTransactional=false, errorStrategy=NONE
        ArgumentCaptor<DmAutoExecConfigFO> configCaptor = ArgumentCaptor.forClass(DmAutoExecConfigFO.class);
        verify(approvalControlService).confirmTicketBySystem(eq(TICKET_ID), configCaptor.capture());
        assertFalse("DDL should not be transactional", configCaptor.getValue().isEnableTransactional());
        assertEquals(ErrorStrategy.NONE, configCaptor.getValue().getErrorStrategy());

        // === Step 3: freeze ===
        DmApprovalDO finishedTicket = buildGovernanceTicket(ApprovalStatus.FINISHED);
        finishedTicket.setRawSql(DDL_SQL);
        finishedTicket.setRollBackSql(null);
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(finishedTicket);
        when(approvalMapper.listFinishedTicketIdList(ApprovalBiz.DM_CHANGE)).thenReturn(List.of(TICKET_ID));
        when(revisionMapper.queryBySourceTicketId(TICKET_ID)).thenReturn(null);

        // Tasks all FINISH (execution succeeded)
        DmExecAutoJobDO job = new DmExecAutoJobDO();
        job.setId(JOB_ID);
        when(autoJobMapper.queryByDependOnBizId(BIZ_ID)).thenReturn(job);
        DmExecAutoTaskDO task = new DmExecAutoTaskDO();
        task.setExecOrder(1);
        task.setStatus(AutoExecTaskStatus.FINISH);
        when(autoTaskMapper.queryListByJobId(JOB_ID, null)).thenReturn(List.of(task));

        // Empty audit activities
        DmApprovalProcessDO process = new DmApprovalProcessDO();
        process.setId(500L);
        process.setTicketId(TICKET_ID);
        process.setTicketStage(ApprovalStage.EXPLAIN);
        when(processMapper.queryByStage(TICKET_ID, ApprovalStage.EXPLAIN)).thenReturn(process);
        when(activityMapper.queryByTicketId(TICKET_ID)).thenReturn(Collections.emptyList());

        // Revision insert assigns ID
        doAnswer(invocation -> {
            DmDbChangeRevisionDO rev = invocation.getArgument(0);
            rev.setId(REVISION_ID);
            return 1;
        }).when(revisionMapper).insert(any(DmDbChangeRevisionDO.class));

        freezeService.freezeFinishedRevisions();

        // Verify REVISION_FROZEN event
        boolean foundFrozen = false;
        for (DmDbChangeEventDO event : eventStore) {
            if (GovEventType.REVISION_FROZEN.name().equals(event.getEventType())) {
                foundFrozen = true;
                assertEquals("SYSTEM", event.getOperatorUid());
                assertEquals(Long.valueOf(REVISION_ID), event.getRevisionId());
            }
        }
        assertTrue("REVISION_FROZEN event should be written", foundFrozen);

        // Verify revision row
        ArgumentCaptor<DmDbChangeRevisionDO> revCaptor = ArgumentCaptor.forClass(DmDbChangeRevisionDO.class);
        verify(revisionMapper).insert(revCaptor.capture());
        DmDbChangeRevisionDO revision = revCaptor.getValue();
        assertEquals(RevisionSourceType.PRE_TICKET.name(), revision.getSourceType());
        assertEquals(Long.valueOf(TICKET_ID), revision.getSourceTicketId());
        assertEquals(Long.valueOf(LOGICAL_DB_ID), revision.getLogicalDbId());
        assertEquals(DDL_SQL, revision.getSqlText());
        assertEquals(GovSqlHashUtils.hash(DDL_SQL), revision.getSqlHash());
        assertEquals("DDL", revision.getChangeType());

        // Verify manifest structure
        List<?> manifest = JsonUtils.toObj(revision.getStmtManifest(), List.class);
        assertEquals(1, manifest.size());
        java.util.Map<?, ?> item = (java.util.Map<?, ?>) manifest.get(0);
        assertEquals(1, item.get("idx"));
        assertEquals(GovSqlHashUtils.hash(DDL_SQL), item.get("stmt_hash"));
        assertEquals(1, item.get("version"));
        assertEquals("SUCCESS", item.get("pre_exec"));

        // Verify event sequence: SUBMIT → SYSTEM_APPROVE → SYSTEM_CONFIRM → REVISION_FROZEN
        assertEquals(GovEventType.SUBMIT.name(), eventStore.get(0).getEventType());
        assertEquals(GovEventType.SYSTEM_APPROVE.name(), eventStore.get(1).getEventType());
        assertEquals(GovEventType.SYSTEM_CONFIRM.name(), eventStore.get(2).getEventType());
        assertEquals(GovEventType.REVISION_FROZEN.name(), eventStore.get(3).getEventType());
    }

    // ======= helpers =======

    private void setupPreBinding() {
        LogicalDbTarget target = new LogicalDbTarget();
        target.setBindingId(1L);
        target.setLogicalDbId(LOGICAL_DB_ID);
        target.setEnvId(ENV_ID);
        target.setDsId(DS_ID);
        target.setResPath("/mydb/");
        target.setGovRole(GovRole.PRE);
        when(logicalDbService.getBinding(PUID, LOGICAL_DB_ID, GovRole.PRE)).thenReturn(target);
        when(dmDsConfigService.fetchDsConfigFromExists(DS_ID)).thenReturn(new DataSourceConfig());
    }

    private void setupInternalTemplate() {
        DmEnvParamTicketDesVO vo = DmEnvParamTicketDesVO.builder()
            .openTicket(true)
            .type(ApprovalType.Internal.name())
            .build();
        when(dmEnvParamService.querySqlTicketInfoParam(PUID, ENV_ID)).thenReturn(vo);
    }

    private void setupSplit(ChangeType changeType, String sql) {
        GovSplitResult result = new GovSplitResult();
        result.setChangeType(changeType);
        GovStmtRow row = new GovStmtRow();
        row.setStmtIndex(1);
        row.setStmtText(sql);
        row.setStmtHash(GovSqlHashUtils.hash(sql));
        result.setStmts(List.of(row));
        when(govStmtSplitService.split(any(), any())).thenReturn(result);
    }

    private void setupTicketCreation() {
        DmTicketResultVO vo = new DmTicketResultVO();
        vo.setTicketId(TICKET_ID);
        when(approvalControlService.createSqlTicket(eq(PUID), eq(UID), any(DmAddTicketFO.class), eq(ApprovalBiz.DM_CHANGE)))
            .thenReturn(vo);
    }

    private void setupExistingTicketInfo() {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setTicketInfo(JsonUtils.toJson(new ApprovalMO()));
        when(approvalMapper.selectById(TICKET_ID)).thenReturn(ticket);
    }

    private DmApprovalDO buildGovernanceTicket(ApprovalStatus status) {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setApproBiz(ApprovalBiz.DM_CHANGE);
        ticket.setTicketStatus(status);
        ticket.setPrimaryUid(PUID);
        ticket.setBizId(BIZ_ID);
        ticket.setOwnerUid(UID);

        ApprovalMO mo = new ApprovalMO();
        mo.setLogicalDbId(LOGICAL_DB_ID);
        mo.setGovRole(GovRole.PRE.name());
        ticket.setTicketInfo(JsonUtils.toJson(mo));
        return ticket;
    }
}
