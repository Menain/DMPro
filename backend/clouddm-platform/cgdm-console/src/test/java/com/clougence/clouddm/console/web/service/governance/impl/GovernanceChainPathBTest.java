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

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import com.clougence.clouddm.api.console.autoexec.ErrorStrategy;
import com.clougence.clouddm.base.metadata.ds.DataSourceConfig;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.component.auth.DmAuthServiceForBiz;
import com.clougence.clouddm.console.web.component.dsconfig.DmDsConfigService;
import com.clougence.clouddm.console.web.component.dsconfig.mode.DsLevels;
import com.clougence.clouddm.console.web.component.governance.GovDmlRowEstimator;
import com.clougence.clouddm.console.web.component.governance.GovPreflightChecker;
import com.clougence.clouddm.console.web.component.governance.GovSqlHashUtils;
import com.clougence.clouddm.console.web.component.governance.GovSplitResult;
import com.clougence.clouddm.console.web.component.governance.GovStmtRow;
import com.clougence.clouddm.console.web.component.governance.GovStmtSplitService;
import com.clougence.clouddm.console.web.component.governance.GuardConclusion;
import com.clougence.clouddm.console.web.model.fo.governance.GovDirectDmlSubmitFO;
import com.clougence.clouddm.console.web.model.fo.ticket.DmAddTicketFO;
import com.clougence.clouddm.console.web.model.vo.governance.DirectDmlSubmitVO;
import com.clougence.clouddm.console.web.model.vo.logicaldb.LogicalDbTarget;
import com.clougence.clouddm.console.web.model.vo.ticket.DmTicketResultVO;
import com.clougence.clouddm.console.web.service.approval.ApprovalControlService;
import com.clougence.clouddm.console.web.service.envparam.DmEnvParamService;
import com.clougence.clouddm.console.web.service.governance.GovExecutionGuardService;
import com.clougence.clouddm.console.web.service.logicaldb.LogicalDbService;
import com.clougence.clouddm.console.web.util.DsResPathObj;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.DataSourceDal;
import com.clougence.clouddm.platform.dal.access.DbChangeGovernDal;
import com.clougence.clouddm.platform.dal.access.ExecutionDal;
import com.clougence.clouddm.platform.dal.access.LogicalDbDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.datasource.DmDsMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangeEventMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangePromotionMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangeRevisionMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangeStmtVersionMapper;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoJobMapper;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoTaskMapper;
import com.clougence.clouddm.platform.dal.mapper.logicaldb.DmLogicalDbMapper;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.datasource.DmDsDO;
import com.clougence.clouddm.platform.dal.model.dbchange.ChangeType;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangePromotionDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeRevisionDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeStmtVersionDO;
import com.clougence.clouddm.platform.dal.model.dbchange.GovEventType;
import com.clougence.clouddm.platform.dal.model.dbchange.PromotionStatus;
import com.clougence.clouddm.platform.dal.model.dbchange.PromotionType;
import com.clougence.clouddm.platform.dal.model.dbchange.RevisionSourceType;
import com.clougence.clouddm.platform.dal.model.dbchange.StmtSource;
import com.clougence.clouddm.platform.dal.model.execution.RsExecAutoJobConfigObj;
import com.clougence.clouddm.platform.dal.model.logicaldb.DmLogicalDbDO;
import com.clougence.clouddm.platform.dal.model.logicaldb.GovRole;
import com.clougence.clouddm.sdk.model.env.EnvParamKeys;
import com.clougence.clouddm.sdk.security.auth.AuthKind;
import com.clougence.clouddm.sdk.security.auth.def.SecDataAuthLabel;
import com.clougence.utils.JsonUtils;

/**
 * C4: Path B direct DML full chain orchestration test (D-P11-7).
 * <p>
 * Wires real service instances: GovDirectDmlServiceImpl (directDmlSubmit),
 * GovExecutionGuardServiceImpl (gate-two).
 * <p>
 * Chain: directDmlSubmit (threshold warn level) → assert 6 objects same-transaction
 *        → simulate approval (promotion → APPROVED) → guard checkByTicket (all gates pass,
 *        G2 parseManifest ignores pre_exec=PENDING per path B compatibility contract).
 * <p>
 * Real services: 2. Mock boundaries: ~16.
 * <p>
 * In-memory fixture: AtomicReference<DmDbChangePromotionDO> for promotion state flow.
 */
public class GovernanceChainPathBTest {

    private GovDirectDmlServiceImpl     directDmlService;
    private GovExecutionGuardService     guardService;

    private LogicalDbService    logicalDbService;
    private DmAuthServiceForBiz dmAuthServiceForBiz;
    private DmDsConfigService   dmDsConfigService;
    private GovStmtSplitService  govStmtSplitService;
    private ApprovalControlService approvalControlService;
    private DmEnvParamService   dmEnvParamService;
    private GovDmlRowEstimator  govDmlRowEstimator;
    private GovPreflightChecker  govPreflightChecker;
    private ApprovalDal          approvalDal;
    private DbChangeGovernDal    dbChangeGovernDal;
    private LogicalDbDal         logicalDbDal;
    private DataSourceDal        dataSourceDal;
    private ExecutionDal         executionDal;

    private DmApprovalMapper        approvalMapper;
    private DmDbChangeRevisionMapper revisionMapper;
    private DmDbChangePromotionMapper promotionMapper;
    private DmDbChangeStmtVersionMapper stmtVersionMapper;
    private DmDbChangeEventMapper   eventMapper;
    private DmLogicalDbMapper        logicalDbMapper;
    private DmDsMapper              dsMapper;
    private DmExecAutoJobMapper     jobMapper;
    private DmExecAutoTaskMapper    taskMapper;

    private AtomicReference<DmDbChangePromotionDO> promotionRef;

    private static final String PUID = "puid-001";
    private static final String UID = "uid-001";
    private static final long LOGICAL_DB_ID = 10L;
    private static final long TICKET_ID = 100L;
    private static final long REVISION_ID = 200L;
    private static final long PROMOTION_ID = 300L;
    private static final long ENV_ID = 5L;
    private static final long DS_ID = 20L;

    private static final String DML_SQL = "UPDATE foo SET bar=1";
    private static final String ROLLBACK_SQL = "UPDATE foo SET bar=0";
    private static final String SQL_HASH = GovSqlHashUtils.hash(DML_SQL);

    @Before
    public void setUp() {
        logicalDbService = mock(LogicalDbService.class);
        dmAuthServiceForBiz = mock(DmAuthServiceForBiz.class);
        dmDsConfigService = mock(DmDsConfigService.class);
        govStmtSplitService = mock(GovStmtSplitService.class);
        approvalControlService = mock(ApprovalControlService.class);
        dmEnvParamService = mock(DmEnvParamService.class);
        govDmlRowEstimator = mock(GovDmlRowEstimator.class);
        govPreflightChecker = mock(GovPreflightChecker.class);
        approvalDal = mock(ApprovalDal.class);
        dbChangeGovernDal = mock(DbChangeGovernDal.class);
        logicalDbDal = mock(LogicalDbDal.class);
        dataSourceDal = mock(DataSourceDal.class);
        executionDal = mock(ExecutionDal.class);

        approvalMapper = mock(DmApprovalMapper.class);
        revisionMapper = mock(DmDbChangeRevisionMapper.class);
        promotionMapper = mock(DmDbChangePromotionMapper.class);
        stmtVersionMapper = mock(DmDbChangeStmtVersionMapper.class);
        eventMapper = mock(DmDbChangeEventMapper.class);
        logicalDbMapper = mock(DmLogicalDbMapper.class);
        dsMapper = mock(DmDsMapper.class);
        jobMapper = mock(DmExecAutoJobMapper.class);
        taskMapper = mock(DmExecAutoTaskMapper.class);

        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);
        when(dbChangeGovernDal.revisionMapper()).thenReturn(revisionMapper);
        when(dbChangeGovernDal.promotionMapper()).thenReturn(promotionMapper);
        when(dbChangeGovernDal.stmtVersionMapper()).thenReturn(stmtVersionMapper);
        when(dbChangeGovernDal.eventMapper()).thenReturn(eventMapper);
        when(logicalDbDal.logicalDbMapper()).thenReturn(logicalDbMapper);
        when(dataSourceDal.dsMapper()).thenReturn(dsMapper);
        when(executionDal.autoJobMapper()).thenReturn(jobMapper);
        when(executionDal.autoTaskMapper()).thenReturn(taskMapper);

        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        // Wire directDml service
        directDmlService = new GovDirectDmlServiceImpl();
        ReflectionTestUtils.setField(directDmlService, "logicalDbService", logicalDbService);
        ReflectionTestUtils.setField(directDmlService, "dmAuthServiceForBiz", dmAuthServiceForBiz);
        ReflectionTestUtils.setField(directDmlService, "dmDsConfigService", dmDsConfigService);
        ReflectionTestUtils.setField(directDmlService, "govStmtSplitService", govStmtSplitService);
        ReflectionTestUtils.setField(directDmlService, "approvalControlService", approvalControlService);
        ReflectionTestUtils.setField(directDmlService, "dmEnvParamService", dmEnvParamService);
        ReflectionTestUtils.setField(directDmlService, "govDmlRowEstimator", govDmlRowEstimator);
        ReflectionTestUtils.setField(directDmlService, "dbChangeGovernDal", dbChangeGovernDal);
        ReflectionTestUtils.setField(directDmlService, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(directDmlService, "logicalDbDal", logicalDbDal);
        ReflectionTestUtils.setField(directDmlService, "txManager", txManager);

        // Wire guard service
        GovExecutionGuardServiceImpl guardImpl = new GovExecutionGuardServiceImpl();
        ReflectionTestUtils.setField(guardImpl, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(guardImpl, "dbChangeGovernDal", dbChangeGovernDal);
        ReflectionTestUtils.setField(guardImpl, "executionDal", executionDal);
        ReflectionTestUtils.setField(guardImpl, "dataSourceDal", dataSourceDal);
        ReflectionTestUtils.setField(guardImpl, "logicalDbService", logicalDbService);
        ReflectionTestUtils.setField(guardImpl, "govStmtSplitService", govStmtSplitService);
        ReflectionTestUtils.setField(guardImpl, "govPreflightChecker", govPreflightChecker);
        ReflectionTestUtils.setField(guardImpl, "dmDsConfigService", dmDsConfigService);
        guardService = guardImpl;

        promotionRef = new AtomicReference<>();
        when(promotionMapper.selectById(PROMOTION_ID)).thenAnswer(inv -> promotionRef.get());
    }

    @Test
    public void pathBFullChain_directDmlSubmit_guard() {
        // === Step 1: directDmlSubmit (threshold warn level) ===
        setupProdBinding();
        when(dmDsConfigService.fetchDsConfigFromExists(DS_ID)).thenReturn(new DataSourceConfig());
        when(dmEnvParamService.queryParam(PUID, ENV_ID, EnvParamKeys.GOV_DML_DIRECT)).thenReturn("on");

        // Threshold: warn level (50000 rows, warn=1000, block=100000)
        when(dmEnvParamService.queryParam(PUID, ENV_ID, EnvParamKeys.GOV_DML_ROW_LIMIT)).thenReturn("warn:1000,block:100000");
        when(govDmlRowEstimator.estimate(eq(PUID), any(DataSourceConfig.class), any(DsLevels.class), anyList()))
            .thenReturn(new GovDmlRowEstimator.RowEstimate(50000, "evidence:warn"));

        GovSplitResult splitResult = new GovSplitResult();
        splitResult.setChangeType(ChangeType.DML);
        GovStmtRow row = new GovStmtRow();
        row.setStmtIndex(1);
        row.setStmtText(DML_SQL);
        row.setStmtHash(SQL_HASH);
        splitResult.setStmts(List.of(row));
        when(govStmtSplitService.split(any(), any())).thenReturn(splitResult);

        DsLevels levels = mock(DsLevels.class);
        when(dmDsConfigService.parseLevels(anyList())).thenReturn(levels);

        DmLogicalDbDO logicalDb = new DmLogicalDbDO();
        logicalDb.setResourceName("mydb");
        when(logicalDbMapper.selectById(LOGICAL_DB_ID)).thenReturn(logicalDb);

        DmTicketResultVO ticketResult = new DmTicketResultVO();
        ticketResult.setTicketId(TICKET_ID);
        when(approvalControlService.createSqlTicket(eq(PUID), eq(UID), any(DmAddTicketFO.class), eq(ApprovalBiz.DM_CHANGE)))
            .thenReturn(ticketResult);

        doAnswer(invocation -> {
            DmDbChangeRevisionDO rev = invocation.getArgument(0);
            rev.setId(REVISION_ID);
            return 1;
        }).when(revisionMapper).insert(any(DmDbChangeRevisionDO.class));

        doAnswer(invocation -> {
            DmDbChangePromotionDO promo = invocation.getArgument(0);
            promo.setId(PROMOTION_ID);
            promo.setStatus(PromotionStatus.CREATED.name());
            promotionRef.set(promo);
            return 1;
        }).when(promotionMapper).insert(any(DmDbChangePromotionDO.class));

        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setTicketInfo(null);
        when(approvalMapper.selectById(TICKET_ID)).thenReturn(ticket);

        GovDirectDmlSubmitFO fo = new GovDirectDmlSubmitFO();
        fo.setLogicalDbId(LOGICAL_DB_ID);
        fo.setSql(DML_SQL);
        fo.setRollbackSql(ROLLBACK_SQL);
        fo.setDescription("test direct DML");

        DirectDmlSubmitVO vo = directDmlService.directDmlSubmit(PUID, UID, fo);

        assertNotNull(vo);
        assertEquals(Long.valueOf(TICKET_ID), vo.getTicketId());
        assertEquals(Long.valueOf(REVISION_ID), vo.getRevisionId());
        assertEquals(Long.valueOf(PROMOTION_ID), vo.getPromotionId());
        assertEquals("HIGH", vo.getRiskLevel());

        // Verify 6 objects: ticket, stmt_version, revision, promotion, event, ticketInfo

        // 1. Ticket (DM_CHANGE, force=true)
        ArgumentCaptor<DmAddTicketFO> foCaptor = ArgumentCaptor.forClass(DmAddTicketFO.class);
        verify(approvalControlService).createSqlTicket(eq(PUID), eq(UID), foCaptor.capture(), eq(ApprovalBiz.DM_CHANGE));
        assertTrue(foCaptor.getValue().isForce());
        assertTrue(foCaptor.getValue().getTicketTitle().startsWith("DIRECT-DML · "));

        // 2. stmt_version (INITIAL)
        ArgumentCaptor<DmDbChangeStmtVersionDO> stmtCaptor = ArgumentCaptor.forClass(DmDbChangeStmtVersionDO.class);
        verify(stmtVersionMapper).insert(stmtCaptor.capture());
        assertEquals(StmtSource.INITIAL.name(), stmtCaptor.getValue().getSource());
        assertEquals(1, stmtCaptor.getValue().getStmtVersion().intValue());

        // 3. Revision (DIRECT_PROD_DML, source_ticket_id=self, pre_exec=PENDING)
        ArgumentCaptor<DmDbChangeRevisionDO> revCaptor = ArgumentCaptor.forClass(DmDbChangeRevisionDO.class);
        verify(revisionMapper).insert(revCaptor.capture());
        DmDbChangeRevisionDO revision = revCaptor.getValue();
        assertEquals(RevisionSourceType.DIRECT_PROD_DML.name(), revision.getSourceType());
        assertEquals(Long.valueOf(TICKET_ID), revision.getSourceTicketId());
        assertEquals("DML", revision.getChangeType());
        assertEquals(DML_SQL, revision.getSqlText());
        // Verify manifest pre_exec=PENDING
        List<?> manifest = JsonUtils.toObj(revision.getStmtManifest(), List.class);
        assertEquals(1, manifest.size());
        java.util.Map<?, ?> item = (java.util.Map<?, ?>) manifest.get(0);
        assertEquals("PENDING", item.get("pre_exec"));

        // 4. Promotion (DIRECT_PROD_DML, gate_result with riskLevel)
        ArgumentCaptor<DmDbChangePromotionDO> promoCaptor = ArgumentCaptor.forClass(DmDbChangePromotionDO.class);
        verify(promotionMapper).insert(promoCaptor.capture());
        DmDbChangePromotionDO promotion = promoCaptor.getValue();
        assertEquals(PromotionType.DIRECT_PROD_DML.name(), promotion.getPromotionType());
        assertEquals(Long.valueOf(TICKET_ID), promotion.getProdApprovalId());
        assertTrue(promotion.getGateResult().contains("HIGH"));
        assertNotNull(promotion.getExecutionKey());

        // 5. Event (DIRECT_DML_SUBMIT)
        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        assertEquals(GovEventType.DIRECT_DML_SUBMIT.name(), eventCaptor.getValue().getEventType());

        // 6. ticketInfo 4-field writeback
        ArgumentCaptor<String> infoCaptor = ArgumentCaptor.forClass(String.class);
        verify(approvalMapper).updateTicketInfo(eq(TICKET_ID), infoCaptor.capture());
        ApprovalMO mo = JsonUtils.toObj(infoCaptor.getValue(), ApprovalMO.class);
        assertEquals(Long.valueOf(PROMOTION_ID), mo.getPromotionId());
        assertEquals(Long.valueOf(REVISION_ID), mo.getRevisionId());
        assertEquals(Long.valueOf(LOGICAL_DB_ID), mo.getLogicalDbId());
        assertEquals(GovRole.PROD.name(), mo.getGovRole());

        // === Step 2: simulate approval (promotion → APPROVED) ===
        DmDbChangePromotionDO promo = promotionRef.get();
        promo.setStatus(PromotionStatus.APPROVED.name());

        // === Step 3: guard checkByTicket (G2 ignores pre_exec=PENDING) ===
        setupGuardPassChain();

        DmApprovalDO govTicket = buildGovTicket();
        RsExecAutoJobConfigObj config = new RsExecAutoJobConfigObj();
        config.setErrorStrategy(ErrorStrategy.NONE);
        config.setEnableTransactional(true); // DML → transactional

        GuardConclusion conclusion = guardService.checkByTicket(PUID, govTicket, config);

        assertTrue("Guard should pass for path B (pre_exec=PENDING ignored by G2)", conclusion.isPass());
    }

    // ======= helpers =======

    private void setupProdBinding() {
        LogicalDbTarget target = new LogicalDbTarget();
        target.setBindingId(1L);
        target.setLogicalDbId(LOGICAL_DB_ID);
        target.setEnvId(ENV_ID);
        target.setDsId(DS_ID);
        target.setResPath("/mydb/");
        target.setGovRole(GovRole.PROD);
        when(logicalDbService.getBinding(PUID, LOGICAL_DB_ID, GovRole.PROD)).thenReturn(target);
    }

    private void setupGuardPassChain() {
        // G2: revision with matching hash + manifest (pre_exec=PENDING for path B)
        DmDbChangeRevisionDO rev = new DmDbChangeRevisionDO();
        rev.setId(REVISION_ID);
        rev.setSqlText(DML_SQL);
        rev.setSqlHash(SQL_HASH);
        rev.setChangeType("DML");
        List<java.util.Map<String, Object>> manifest = new java.util.ArrayList<>();
        java.util.Map<String, Object> item = new java.util.LinkedHashMap<>();
        item.put("idx", 1);
        item.put("stmt_hash", SQL_HASH);
        item.put("version", 1);
        item.put("pre_exec", "PENDING"); // path B: no PRE execution
        manifest.add(item);
        rev.setStmtManifest(JsonUtils.toJson(manifest));
        when(revisionMapper.selectById(REVISION_ID)).thenReturn(rev);

        DmDsDO dsDO = new DmDsDO();
        dsDO.setId(DS_ID);
        when(dsMapper.queryDsIdentityById(DS_ID)).thenReturn(dsDO);
        when(dmDsConfigService.fetchDsConfigFromExists(DS_ID)).thenReturn(null);

        GovSplitResult splitResult = new GovSplitResult();
        GovStmtRow row = new GovStmtRow();
        row.setStmtIndex(1);
        row.setStmtText(DML_SQL);
        row.setStmtHash(SQL_HASH);
        splitResult.setStmts(List.of(row));
        splitResult.setChangeType(ChangeType.DML);
        when(govStmtSplitService.split(isNull(), eq(DML_SQL))).thenReturn(splitResult);

        // G4: preflight pass
        when(govPreflightChecker.check(any(), any(), anyString(), any())).thenReturn(List.of());

        // G3: binding match (already set by setupProdBinding)
    }

    private DmApprovalDO buildGovTicket() {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setApproBiz(ApprovalBiz.DM_CHANGE);
        ticket.setPrimaryUid(PUID);
        ticket.setRawSql(DML_SQL);
        ticket.setBizId("biz-001");
        ticket.setBindDsId(DS_ID);

        ApprovalMO mo = new ApprovalMO();
        mo.setGovRole(GovRole.PROD.name());
        mo.setPromotionId(PROMOTION_ID);
        mo.setRevisionId(REVISION_ID);
        mo.setLogicalDbId(LOGICAL_DB_ID);
        ticket.setTicketInfo(JsonUtils.toJson(mo));
        return ticket;
    }
}
