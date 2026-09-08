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
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import com.clougence.clouddm.api.console.autoexec.ErrorStrategy;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.component.auth.DmAuthServiceForBiz;
import com.clougence.clouddm.console.web.component.dsconfig.DmDsConfigService;
import com.clougence.clouddm.console.web.component.governance.GovPreflightChecker;
import com.clougence.clouddm.console.web.component.governance.GovSqlHashUtils;
import com.clougence.clouddm.console.web.component.governance.GovSplitResult;
import com.clougence.clouddm.console.web.component.governance.GovStmtRow;
import com.clougence.clouddm.console.web.component.governance.GovStmtSplitService;
import com.clougence.clouddm.console.web.component.governance.GuardConclusion;
import com.clougence.clouddm.console.web.component.governance.PromotionStateMachine;
import com.clougence.clouddm.console.web.util.DsResPathObj;
import com.clougence.clouddm.console.web.model.fo.governance.GovPromoteFO;
import com.clougence.clouddm.console.web.model.fo.ticket.DmAddTicketFO;
import com.clougence.clouddm.console.web.model.vo.envparam.DmEnvParamTicketDesVO;
import com.clougence.clouddm.console.web.model.vo.governance.AvailableRevisionVO;
import com.clougence.clouddm.console.web.model.vo.logicaldb.LogicalDbTarget;
import com.clougence.clouddm.console.web.model.vo.ticket.DmTicketResultVO;
import com.clougence.clouddm.console.web.service.approval.ApprovalControlService;
import com.clougence.clouddm.console.web.service.envparam.DmEnvParamService;
import com.clougence.clouddm.console.web.service.governance.GovExecutionGuardService;
import com.clougence.clouddm.console.web.service.governance.GovPromotionService;
import com.clougence.clouddm.console.web.service.logicaldb.LogicalDbService;
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
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalType;
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
import com.clougence.clouddm.platform.dal.model.execution.RsExecAutoJobConfigObj;
import com.clougence.clouddm.platform.dal.model.logicaldb.DmLogicalDbDO;
import com.clougence.clouddm.platform.dal.model.logicaldb.GovRole;
import com.clougence.utils.JsonUtils;

/**
 * C3: Promote full chain orchestration test (D-P11-7).
 * <p>
 * Wires real service instances: GovPromotionServiceImpl (availableRevisions + promote),
 * GovPromotionSyncServiceImpl (approval callback sync), GovExecutionGuardServiceImpl (gate-two),
 * PromotionStateMachine (real, delegates to mocked mapper).
 * <p>
 * Chain: availableRevisions → promote (gate-one all pass) → approval callback sync (CREATED→APPROVING→APPROVED)
 *        → guard checkByTicket (gate-two all pass).
 * <p>
 * Real services: 4. Mock boundaries: ~18 (DAL mappers ×8, LogicalDbService, DmAuthServiceForBiz,
 * DmDsConfigService, ApprovalControlService, DmEnvParamService, GovStmtSplitService, GovPreflightChecker,
 * ExecutionDal, DataSourceDal, txManager).
 * <p>
 * In-memory fixture: AtomicReference<DmDbChangePromotionDO> for promotion state flow.
 */
public class GovernanceChainPromoteTest {

    private GovPromotionService        promotionService;
    private GovPromotionSyncServiceImpl syncService;
    private GovExecutionGuardService   guardService;
    private PromotionStateMachine      stateMachine;

    private LogicalDbService    logicalDbService;
    private DmAuthServiceForBiz dmAuthServiceForBiz;
    private DmDsConfigService   dmDsConfigService;
    private ApprovalControlService approvalControlService;
    private DmEnvParamService   dmEnvParamService;
    private GovStmtSplitService  govStmtSplitService;
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
    private static final long TICKET_ID = 200L;
    private static final long REVISION_ID = 300L;
    private static final long PROMOTION_ID = 500L;
    private static final long PROD_TICKET_ID = 999L;
    private static final long ENV_ID = 5L;
    private static final long DS_ID = 20L;

    private static final String RAW_SQL = "CREATE TABLE foo (id INT)";
    private static final String ROLLBACK_SQL = "DROP TABLE foo";
    private static final String SQL_HASH = GovSqlHashUtils.hash(RAW_SQL);

    private static final Set<PromotionStatus> TERMINAL = EnumSet.of(
        PromotionStatus.SUCCEEDED, PromotionStatus.REJECTED, PromotionStatus.CANCELLED);

    @Before
    public void setUp() {
        logicalDbService = mock(LogicalDbService.class);
        dmAuthServiceForBiz = mock(DmAuthServiceForBiz.class);
        dmDsConfigService = mock(DmDsConfigService.class);
        approvalControlService = mock(ApprovalControlService.class);
        dmEnvParamService = mock(DmEnvParamService.class);
        govStmtSplitService = mock(GovStmtSplitService.class);
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

        // Wire state machine (real, delegates to mocked promotionMapper)
        stateMachine = new PromotionStateMachine();
        ReflectionTestUtils.setField(stateMachine, "dbChangeGovernDal", dbChangeGovernDal);

        // Wire promotion service
        GovPromotionServiceImpl promoImpl = new GovPromotionServiceImpl();
        ReflectionTestUtils.setField(promoImpl, "dbChangeGovernDal", dbChangeGovernDal);
        ReflectionTestUtils.setField(promoImpl, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(promoImpl, "logicalDbDal", logicalDbDal);
        ReflectionTestUtils.setField(promoImpl, "logicalDbService", logicalDbService);
        ReflectionTestUtils.setField(promoImpl, "dmAuthServiceForBiz", dmAuthServiceForBiz);
        ReflectionTestUtils.setField(promoImpl, "dmEnvParamService", dmEnvParamService);
        ReflectionTestUtils.setField(promoImpl, "approvalControlService", approvalControlService);
        ReflectionTestUtils.setField(promoImpl, "stateMachine", stateMachine);
        ReflectionTestUtils.setField(promoImpl, "txManager", txManager);
        promotionService = promoImpl;

        // Wire sync service
        syncService = new GovPromotionSyncServiceImpl();
        ReflectionTestUtils.setField(syncService, "dbChangeGovernDal", dbChangeGovernDal);
        ReflectionTestUtils.setField(syncService, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(syncService, "stateMachine", stateMachine);

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

        // In-memory promotion state
        promotionRef = new AtomicReference<>();

        when(promotionMapper.selectById(PROMOTION_ID)).thenAnswer(inv -> promotionRef.get());
        when(promotionMapper.listNonTerminal()).thenAnswer(inv -> {
            DmDbChangePromotionDO promo = promotionRef.get();
            if (promo == null || TERMINAL.contains(PromotionStatus.valueOf(promo.getStatus()))) {
                return List.of();
            }
            return List.of(promo);
        });

        // transitStatus: simulate state machine updating the promotion
        when(promotionMapper.transitStatus(eq(PROMOTION_ID), anyString(), anyList())).thenAnswer(invocation -> {
            String toStatus = invocation.getArgument(1);
            DmDbChangePromotionDO promo = promotionRef.get();
            if (promo != null) {
                promo.setStatus(toStatus);
            }
            return 1;
        });
    }

    @Test
    public void promoteFullChain_availableRevisions_promote_sync_guard() {
        setupRevisionAndSourceTicket();
        setupProdBinding();
        setupProdApprovalTemplate();
        setupGateOnePassingDeps();

        // === Step 1: availableRevisions ===
        List<AvailableRevisionVO> revisions = promotionService.availableRevisions(PUID, UID);
        assertFalse("Available revisions should not be empty", revisions.isEmpty());
        assertEquals(REVISION_ID, revisions.get(0).getRevisionId().longValue());

        // === Step 2: promote (gate-one all pass) ===
        GovPromoteFO fo = new GovPromoteFO();
        fo.setRevisionId(REVISION_ID);
        fo.setDescription("test promotion");

        // Mock ticket creation
        DmTicketResultVO ticketResult = new DmTicketResultVO();
        ticketResult.setTicketId(PROD_TICKET_ID);
        when(approvalControlService.createSqlTicket(eq(PUID), eq(UID), any(DmAddTicketFO.class), eq(ApprovalBiz.DM_CHANGE)))
            .thenReturn(ticketResult);

        // Mock promotion insert
        doAnswer(invocation -> {
            DmDbChangePromotionDO promo = invocation.getArgument(0);
            promo.setId(PROMOTION_ID);
            promo.setStatus(PromotionStatus.CREATED.name());
            promotionRef.set(promo);
            return 1;
        }).when(promotionMapper).insert(any(DmDbChangePromotionDO.class));

        // Mock updateProdApprovalId to update in-memory promotion (sync reads this field)
        doAnswer(invocation -> {
            long promoId = invocation.getArgument(0);
            long ticketId = invocation.getArgument(1);
            DmDbChangePromotionDO promo = promotionRef.get();
            if (promo != null) {
                promo.setProdApprovalId(ticketId);
            }
            return 1;
        }).when(promotionMapper).updateProdApprovalId(anyLong(), anyLong());

        // Mock PROD ticket for ticketInfo writeback
        DmApprovalDO prodTicket = new DmApprovalDO();
        prodTicket.setId(PROD_TICKET_ID);
        when(approvalMapper.selectById(PROD_TICKET_ID)).thenReturn(prodTicket);

        long promotionId = promotionService.promote(PUID, UID, fo);
        assertEquals(PROMOTION_ID, promotionId);

        // Verify promotion row
        ArgumentCaptor<DmDbChangePromotionDO> promoCaptor = ArgumentCaptor.forClass(DmDbChangePromotionDO.class);
        verify(promotionMapper).insert(promoCaptor.capture());
        DmDbChangePromotionDO promotion = promoCaptor.getValue();
        assertEquals(PromotionStatus.CREATED.name(), promotion.getStatus());
        assertEquals(PromotionType.PRE_PROMOTION.name(), promotion.getPromotionType());
        assertEquals(REVISION_ID, promotion.getRevisionId().longValue());
        assertEquals(LOGICAL_DB_ID, promotion.getLogicalDbId().longValue());
        assertEquals(ENV_ID, promotion.getProdEnvId().longValue());
        assertEquals(DS_ID, promotion.getProdDsId().longValue());
        assertEquals(GovSqlHashUtils.hash(REVISION_ID + "|" + DS_ID + "|" + LOGICAL_DB_ID), promotion.getExecutionKey());

        // Verify PROD ticket FO: rawSql = frozen revision (byte-identical)
        ArgumentCaptor<DmAddTicketFO> foCaptor = ArgumentCaptor.forClass(DmAddTicketFO.class);
        verify(approvalControlService).createSqlTicket(eq(PUID), eq(UID), foCaptor.capture(), eq(ApprovalBiz.DM_CHANGE));
        DmAddTicketFO ticketFO = foCaptor.getValue();
        assertEquals(RAW_SQL, ticketFO.getRawSql());
        assertEquals(ROLLBACK_SQL, ticketFO.getRollBackSql());
        assertTrue(ticketFO.getTicketTitle().contains("REV-"));
        assertTrue(ticketFO.isForce());

        // Verify prod_approval_id updated
        verify(promotionMapper).updateProdApprovalId(PROMOTION_ID, PROD_TICKET_ID);

        // Verify ticketInfo 4 fields
        ArgumentCaptor<String> infoCaptor = ArgumentCaptor.forClass(String.class);
        verify(approvalMapper).updateTicketInfo(eq(PROD_TICKET_ID), infoCaptor.capture());
        ApprovalMO mo = JsonUtils.toObj(infoCaptor.getValue(), ApprovalMO.class);
        assertEquals(Long.valueOf(PROMOTION_ID), mo.getPromotionId());
        assertEquals(Long.valueOf(REVISION_ID), mo.getRevisionId());
        assertEquals(Long.valueOf(LOGICAL_DB_ID), mo.getLogicalDbId());
        assertEquals(GovRole.PROD.name(), mo.getGovRole());

        // === Step 3: approval callback sync (CREATED → APPROVING → APPROVED) ===

        // Simulate ticket moving to WAIT_APPROVAL (approval in progress)
        DmApprovalDO waitApprovalTicket = buildProdTicket(ApprovalStatus.WAIT_APPROVAL);
        when(approvalMapper.queryById(PROD_TICKET_ID)).thenReturn(waitApprovalTicket);
        syncService.syncPromotionStatus();
        assertEquals(PromotionStatus.APPROVING.name(), promotionRef.get().getStatus());

        // Simulate ticket moving to WAIT_CONFIRM (approval approved)
        DmApprovalDO waitConfirmTicket = buildProdTicket(ApprovalStatus.WAIT_CONFIRM);
        when(approvalMapper.queryById(PROD_TICKET_ID)).thenReturn(waitConfirmTicket);
        syncService.syncPromotionStatus();
        assertEquals(PromotionStatus.APPROVED.name(), promotionRef.get().getStatus());

        // Verify STATUS_SYNC events written
        ArgumentCaptor<DmDbChangeEventDO> syncEventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper, atLeast(2)).insert(syncEventCaptor.capture());
        boolean foundApprovingSync = false;
        boolean foundApprovedSync = false;
        for (DmDbChangeEventDO event : syncEventCaptor.getAllValues()) {
            if (GovEventType.STATUS_SYNC.name().equals(event.getEventType())) {
                if (PromotionStatus.APPROVING.name().equals(event.getToStatus())) {
                    foundApprovingSync = true;
                }
                if (PromotionStatus.APPROVED.name().equals(event.getToStatus())) {
                    foundApprovedSync = true;
                }
            }
        }
        assertTrue("STATUS_SYNC to APPROVING should be written", foundApprovingSync);
        assertTrue("STATUS_SYNC to APPROVED should be written", foundApprovedSync);

        // === Step 4: guard gate-two (all gates pass) ===
        setupGuardFullPassChain();

        DmApprovalDO prodGovTicket = buildProdGovTicket();
        RsExecAutoJobConfigObj config = new RsExecAutoJobConfigObj();
        config.setErrorStrategy(ErrorStrategy.NONE);
        config.setEnableTransactional(false); // DDL → false

        GuardConclusion conclusion = guardService.checkByTicket(PUID, prodGovTicket, config);

        assertTrue("Guard should pass all gates", conclusion.isPass());

        // Verify guard wrote GUARD_PASS event
        ArgumentCaptor<DmDbChangeEventDO> guardEventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper, atLeast(1)).insert(guardEventCaptor.capture());
        boolean foundGuardPass = false;
        for (DmDbChangeEventDO event : guardEventCaptor.getAllValues()) {
            if (GovEventType.GUARD_PASS.name().equals(event.getEventType())) {
                foundGuardPass = true;
            }
        }
        assertTrue("GUARD_PASS event should be written", foundGuardPass);
    }

    // ======= helpers =======

    private void setupRevisionAndSourceTicket() {
        DmDbChangeRevisionDO revision = new DmDbChangeRevisionDO();
        revision.setId(REVISION_ID);
        revision.setRevisionCode("REV-20260907-0001");
        revision.setLogicalDbId(LOGICAL_DB_ID);
        revision.setEnvId(ENV_ID);
        revision.setSourceType(RevisionSourceType.PRE_TICKET.name());
        revision.setSourceTicketId(TICKET_ID);
        revision.setChangeType("DDL");
        revision.setSqlText(RAW_SQL);
        revision.setRollbackSqlText(ROLLBACK_SQL);
        revision.setSqlHash(SQL_HASH);
        when(revisionMapper.selectById(REVISION_ID)).thenReturn(revision);
        when(revisionMapper.listByTenant(PUID)).thenReturn(List.of(revision));

        // Source ticket FINISHED
        DmApprovalDO sourceTicket = new DmApprovalDO();
        sourceTicket.setId(TICKET_ID);
        sourceTicket.setTicketStatus(ApprovalStatus.FINISHED);
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(sourceTicket);

        // Stmt versions matching manifest
        DmDbChangeStmtVersionDO sv = new DmDbChangeStmtVersionDO();
        sv.setTicketId(TICKET_ID);
        sv.setStmtIndex(1);
        sv.setStmtVersion(1);
        sv.setStmtHash(SQL_HASH);
        when(stmtVersionMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(sv));

        // Manifest
        List<java.util.Map<String, Object>> manifest = new ArrayList<>();
        java.util.Map<String, Object> item = new java.util.LinkedHashMap<>();
        item.put("idx", 1);
        item.put("stmt_hash", SQL_HASH);
        item.put("version", 1);
        item.put("pre_exec", "SUCCESS");
        manifest.add(item);
        revision.setStmtManifest(JsonUtils.toJson(manifest));

        // No existing promotion (G5)
        when(promotionMapper.queryByRevisionId(REVISION_ID)).thenReturn(null);

        // Logical db
        DmLogicalDbDO logicalDb = new DmLogicalDbDO();
        logicalDb.setId(LOGICAL_DB_ID);
        logicalDb.setResourceCode("ORDER_DB");
        logicalDb.setResourceName("Order DB");
        logicalDb.setStatus("ENABLED");
        logicalDb.setCreatorUid(PUID);
        when(logicalDbMapper.selectById(LOGICAL_DB_ID)).thenReturn(logicalDb);
    }

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

    private void setupProdApprovalTemplate() {
        DmEnvParamTicketDesVO config = DmEnvParamTicketDesVO.builder()
            .openTicket(true)
            .type(ApprovalType.DingTalk.name())
            .build();
        when(dmEnvParamService.querySqlTicketInfoParam(PUID, ENV_ID)).thenReturn(config);
    }

    private void setupGateOnePassingDeps() {
        // G3/G4: auth passes (no exception = pass), binding resolvable
        // G7: third-party template configured (DingTalk)
        // availableRevisions filter 6: checkResAuthWithoutError returns true
        when(dmAuthServiceForBiz.checkResAuthWithoutError(
            eq(PUID), eq(UID), eq(DS_ID), any(), anyString(), any()))
            .thenReturn(true);
    }

    private void setupGuardFullPassChain() {
        // G1: promotion APPROVED (already set by sync)
        // G2: revision with matching hash + split
        when(revisionMapper.selectById(REVISION_ID)).thenReturn(buildGuardRevision());
        DmDsDO dsDO = new DmDsDO();
        dsDO.setId(DS_ID);
        when(dsMapper.queryDsIdentityById(DS_ID)).thenReturn(dsDO);
        when(dmDsConfigService.fetchDsConfigFromExists(DS_ID)).thenReturn(null);

        GovSplitResult splitResult = new GovSplitResult();
        GovStmtRow row = new GovStmtRow();
        row.setStmtIndex(1);
        row.setStmtText(RAW_SQL);
        row.setStmtHash(SQL_HASH);
        splitResult.setStmts(List.of(row));
        splitResult.setChangeType(ChangeType.DDL);
        when(govStmtSplitService.split(isNull(), eq(RAW_SQL))).thenReturn(splitResult);

        // G3: binding match
        // (already set up by setupProdBinding)

        // G4: preflight pass
        when(govPreflightChecker.check(any(), any(), anyString(), any())).thenReturn(List.of());

        // G5: execution_key match (already set in promotion)
    }

    private DmApprovalDO buildProdTicket(ApprovalStatus status) {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(PROD_TICKET_ID);
        ticket.setApproBiz(ApprovalBiz.DM_CHANGE);
        ticket.setTicketStatus(status);
        ticket.setPrimaryUid(PUID);
        ticket.setRawSql(RAW_SQL);
        ticket.setBizId("prod-biz-001");
        return ticket;
    }

    private DmApprovalDO buildProdGovTicket() {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(PROD_TICKET_ID);
        ticket.setApproBiz(ApprovalBiz.DM_CHANGE);
        ticket.setTicketStatus(ApprovalStatus.WAIT_EXEC);
        ticket.setPrimaryUid(PUID);
        ticket.setRawSql(RAW_SQL);
        ticket.setBizId("prod-biz-001");
        ticket.setBindDsId(DS_ID);

        ApprovalMO mo = new ApprovalMO();
        mo.setGovRole(GovRole.PROD.name());
        mo.setPromotionId(PROMOTION_ID);
        mo.setRevisionId(REVISION_ID);
        mo.setLogicalDbId(LOGICAL_DB_ID);
        ticket.setTicketInfo(JsonUtils.toJson(mo));
        return ticket;
    }

    private DmDbChangeRevisionDO buildGuardRevision() {
        DmDbChangeRevisionDO rev = new DmDbChangeRevisionDO();
        rev.setId(REVISION_ID);
        rev.setSqlText(RAW_SQL);
        rev.setSqlHash(SQL_HASH);
        rev.setChangeType("DDL");
        List<java.util.Map<String, Object>> manifest = new ArrayList<>();
        java.util.Map<String, Object> item = new java.util.HashMap<>();
        item.put("idx", 1);
        item.put("stmt_hash", SQL_HASH);
        item.put("version", 1);
        item.put("pre_exec", "SUCCESS");
        manifest.add(item);
        rev.setStmtManifest(JsonUtils.toJson(manifest));
        return rev;
    }
}
