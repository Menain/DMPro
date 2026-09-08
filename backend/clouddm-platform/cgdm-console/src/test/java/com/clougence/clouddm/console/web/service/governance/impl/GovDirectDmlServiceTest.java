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

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.base.metadata.ds.DataSourceConfig;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.component.auth.DmAuthServiceForBiz;
import com.clougence.clouddm.console.web.component.dsconfig.DmDsConfigService;
import com.clougence.clouddm.console.web.component.dsconfig.mode.DsLevels;
import com.clougence.clouddm.console.web.component.governance.GovDmlRowEstimator;
import com.clougence.clouddm.console.web.component.governance.GovSplitResult;
import com.clougence.clouddm.console.web.component.governance.GovStmtRow;
import com.clougence.clouddm.console.web.component.governance.GovStmtSplitService;
import com.clougence.clouddm.console.web.model.fo.governance.GovDirectDmlSubmitFO;
import com.clougence.clouddm.console.web.model.fo.ticket.DmAddTicketFO;
import com.clougence.clouddm.console.web.model.vo.governance.DirectDmlSubmitVO;
import com.clougence.clouddm.console.web.model.vo.logicaldb.LogicalDbTarget;
import com.clougence.clouddm.console.web.model.vo.ticket.DmTicketResultVO;
import com.clougence.clouddm.console.web.service.approval.ApprovalControlService;
import com.clougence.clouddm.console.web.service.envparam.DmEnvParamService;
import com.clougence.clouddm.console.web.service.governance.GovDirectDmlService;
import com.clougence.clouddm.console.web.service.logicaldb.LogicalDbService;
import com.clougence.clouddm.console.web.util.DsResPathObj;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.DbChangeGovernDal;
import com.clougence.clouddm.platform.dal.access.LogicalDbDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangeEventMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangePromotionMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangeRevisionMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangeStmtVersionMapper;
import com.clougence.clouddm.platform.dal.mapper.logicaldb.DmLogicalDbMapper;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.dbchange.ChangeType;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangePromotionDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeRevisionDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeStmtVersionDO;
import com.clougence.clouddm.platform.dal.model.dbchange.GovEventType;
import com.clougence.clouddm.platform.dal.model.dbchange.PromotionType;
import com.clougence.clouddm.platform.dal.model.dbchange.RevisionSourceType;
import com.clougence.clouddm.platform.dal.model.dbchange.StmtSource;
import com.clougence.clouddm.platform.dal.model.logicaldb.DmLogicalDbDO;
import com.clougence.clouddm.platform.dal.model.logicaldb.GovRole;
import com.clougence.clouddm.sdk.model.env.EnvParamKeys;
import com.clougence.clouddm.sdk.security.auth.AuthKind;
import com.clougence.clouddm.sdk.security.auth.def.SecDataAuthLabel;
import com.clougence.utils.JsonUtils;

public class GovDirectDmlServiceTest {

    private GovDirectDmlService     service;

    private LogicalDbService        logicalDbService;
    private DmAuthServiceForBiz     dmAuthServiceForBiz;
    private DmDsConfigService       dmDsConfigService;
    private GovStmtSplitService    govStmtSplitService;
    private ApprovalControlService  approvalControlService;
    private DmEnvParamService       dmEnvParamService;
    private GovDmlRowEstimator     govDmlRowEstimator;
    private DbChangeGovernDal      dbChangeGovernDal;
    private ApprovalDal             approvalDal;
    private LogicalDbDal            logicalDbDal;
    private DmApprovalMapper        approvalMapper;
    private DmLogicalDbMapper       logicalDbMapper;
    private DmDbChangeRevisionMapper revisionMapper;
    private DmDbChangePromotionMapper promotionMapper;
    private DmDbChangeStmtVersionMapper stmtVersionMapper;
    private DmDbChangeEventMapper   eventMapper;

    private static final String PUID = "puid-001";
    private static final String UID = "uid-001";
    private static final long LOGICAL_DB_ID = 10L;
    private static final long TICKET_ID = 100L;
    private static final long REVISION_ID = 200L;
    private static final long PROMOTION_ID = 300L;
    private static final long ENV_ID = 5L;
    private static final long DS_ID = 20L;

    @Before
    public void setUp() {
        GovDirectDmlServiceImpl impl = new GovDirectDmlServiceImpl();
        logicalDbService = mock(LogicalDbService.class);
        dmAuthServiceForBiz = mock(DmAuthServiceForBiz.class);
        dmDsConfigService = mock(DmDsConfigService.class);
        govStmtSplitService = mock(GovStmtSplitService.class);
        approvalControlService = mock(ApprovalControlService.class);
        dmEnvParamService = mock(DmEnvParamService.class);
        govDmlRowEstimator = mock(GovDmlRowEstimator.class);
        dbChangeGovernDal = mock(DbChangeGovernDal.class);
        approvalDal = mock(ApprovalDal.class);
        logicalDbDal = mock(LogicalDbDal.class);
        approvalMapper = mock(DmApprovalMapper.class);
        logicalDbMapper = mock(DmLogicalDbMapper.class);
        revisionMapper = mock(DmDbChangeRevisionMapper.class);
        promotionMapper = mock(DmDbChangePromotionMapper.class);
        stmtVersionMapper = mock(DmDbChangeStmtVersionMapper.class);
        eventMapper = mock(DmDbChangeEventMapper.class);

        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        ReflectionTestUtils.setField(impl, "logicalDbService", logicalDbService);
        ReflectionTestUtils.setField(impl, "dmAuthServiceForBiz", dmAuthServiceForBiz);
        ReflectionTestUtils.setField(impl, "dmDsConfigService", dmDsConfigService);
        ReflectionTestUtils.setField(impl, "govStmtSplitService", govStmtSplitService);
        ReflectionTestUtils.setField(impl, "approvalControlService", approvalControlService);
        ReflectionTestUtils.setField(impl, "dmEnvParamService", dmEnvParamService);
        ReflectionTestUtils.setField(impl, "govDmlRowEstimator", govDmlRowEstimator);
        ReflectionTestUtils.setField(impl, "dbChangeGovernDal", dbChangeGovernDal);
        ReflectionTestUtils.setField(impl, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(impl, "logicalDbDal", logicalDbDal);
        ReflectionTestUtils.setField(impl, "txManager", txManager);

        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);
        when(logicalDbDal.logicalDbMapper()).thenReturn(logicalDbMapper);
        when(dbChangeGovernDal.revisionMapper()).thenReturn(revisionMapper);
        when(dbChangeGovernDal.promotionMapper()).thenReturn(promotionMapper);
        when(dbChangeGovernDal.stmtVersionMapper()).thenReturn(stmtVersionMapper);
        when(dbChangeGovernDal.eventMapper()).thenReturn(eventMapper);

        service = impl;
    }

    // ======= success chain =======

    @Test
    public void directDmlSubmit_success() {
        setupDefaults();
        setupTicketCreation();
        setupRevisionInsert();
        setupPromotionInsert();
        setupExistingTicketInfo();

        DirectDmlSubmitVO vo = service.directDmlSubmit(PUID, UID, buildFO());

        assertNotNull(vo);
        assertEquals(Long.valueOf(TICKET_ID), vo.getTicketId());
        assertEquals(Long.valueOf(REVISION_ID), vo.getRevisionId());
        assertEquals(Long.valueOf(PROMOTION_ID), vo.getPromotionId());
        assertEquals("NORMAL", vo.getRiskLevel());

        // Verify ticket created with DM_CHANGE
        ArgumentCaptor<DmAddTicketFO> foCaptor = ArgumentCaptor.forClass(DmAddTicketFO.class);
        verify(approvalControlService).createSqlTicket(eq(PUID), eq(UID), foCaptor.capture(), eq(ApprovalBiz.DM_CHANGE));
        assertTrue("force must be true", foCaptor.getValue().isForce());
        assertTrue(foCaptor.getValue().getTicketTitle().startsWith("DIRECT-DML · "));

        // Verify stmt_version inserted
        verify(stmtVersionMapper).insert(any(DmDbChangeStmtVersionDO.class));

        // Verify revision: source_type=DIRECT_PROD_DML, source_ticket_id=self
        ArgumentCaptor<DmDbChangeRevisionDO> revCaptor = ArgumentCaptor.forClass(DmDbChangeRevisionDO.class);
        verify(revisionMapper).insert(revCaptor.capture());
        DmDbChangeRevisionDO rev = revCaptor.getValue();
        assertEquals(RevisionSourceType.DIRECT_PROD_DML.name(), rev.getSourceType());
        assertEquals(Long.valueOf(TICKET_ID), rev.getSourceTicketId());
        assertEquals(ChangeType.DML.name(), rev.getChangeType());
        assertManifestPENDING(rev.getStmtManifest());

        // Verify promotion: type=DIRECT_PROD_DML, status=CREATED
        ArgumentCaptor<DmDbChangePromotionDO> promoCaptor = ArgumentCaptor.forClass(DmDbChangePromotionDO.class);
        verify(promotionMapper).insert(promoCaptor.capture());
        DmDbChangePromotionDO promo = promoCaptor.getValue();
        assertEquals(PromotionType.DIRECT_PROD_DML.name(), promo.getPromotionType());
        assertEquals(Long.valueOf(TICKET_ID), promo.getProdApprovalId());
        assertNotNull(promo.getGateResult());
        assertNotNull(promo.getExecutionKey());

        // Verify event: DIRECT_DML_SUBMIT
        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        assertEquals(GovEventType.DIRECT_DML_SUBMIT.name(), eventCaptor.getValue().getEventType());

        // Verify ticketInfo updated
        verify(approvalMapper).updateTicketInfo(eq(TICKET_ID), anyString());

        // Estimator not called (threshold not configured)
        verify(govDmlRowEstimator, never()).estimate(any(), any(), any(), any());
    }

    // ======= four validations =======

    @Test
    public void directDmlSubmit_switchOff_rejected() {
        setupBinding();
        when(dmEnvParamService.queryParam(PUID, ENV_ID, EnvParamKeys.GOV_DML_DIRECT)).thenReturn("off");

        try {
            service.directDmlSubmit(PUID, UID, buildFO());
            fail("Should reject when switch is off");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("not enabled"));
        }
        verifyZeroObjectsCreated();
    }

    @Test
    public void directDmlSubmit_switchNull_rejected() {
        setupBinding();
        when(dmEnvParamService.queryParam(PUID, ENV_ID, EnvParamKeys.GOV_DML_DIRECT)).thenReturn(null);

        try {
            service.directDmlSubmit(PUID, UID, buildFO());
            fail("Should reject when switch is null");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("not enabled"));
        }
        verifyZeroObjectsCreated();
    }

    @Test
    public void directDmlSubmit_nonDml_rejected() {
        setupDefaults();
        GovSplitResult splitResult = new GovSplitResult();
        splitResult.setChangeType(ChangeType.DDL);
        splitResult.setStmts(List.of(createStmtRow(1, "CREATE TABLE foo (id INT)")));
        when(govStmtSplitService.split(any(), any())).thenReturn(splitResult);

        try {
            service.directDmlSubmit(PUID, UID, buildFO());
            fail("Should reject non-DML");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("pure DML"));
        }
        verifyZeroObjectsCreated();
    }

    @Test
    public void directDmlSubmit_mixed_rejected() {
        setupDefaults();
        GovSplitResult splitResult = new GovSplitResult();
        splitResult.setChangeType(ChangeType.MIXED);
        splitResult.setStmts(List.of(
            createStmtRow(1, "CREATE TABLE foo (id INT)"),
            createStmtRow(2, "INSERT INTO foo VALUES (1)")
        ));
        when(govStmtSplitService.split(any(), any())).thenReturn(splitResult);

        try {
            service.directDmlSubmit(PUID, UID, buildFO());
            fail("Should reject MIXED");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("pure DML"));
        }
        verifyZeroObjectsCreated();
    }

    @Test
    public void directDmlSubmit_authDenied_rejected() {
        setupBinding();
        setupSwitchOn();
        doThrow(new ErrorMessageException("Permission denied"))
            .when(dmAuthServiceForBiz).checkResAuth(eq(PUID), eq(UID), eq(DS_ID), any(DsResPathObj.class),
                eq(SecDataAuthLabel.DM_DAUTH_TICKET), eq(AuthKind.DataSource));

        try {
            service.directDmlSubmit(PUID, UID, buildFO());
            fail("Should reject on auth failure");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("Permission denied"));
        }
        verifyZeroObjectsCreated();
    }

    @Test
    public void directDmlSubmit_noProdBinding_rejected() {
        when(logicalDbService.getBinding(PUID, LOGICAL_DB_ID, GovRole.PROD))
            .thenThrow(new ErrorMessageException("No PROD binding"));

        try {
            service.directDmlSubmit(PUID, UID, buildFO());
            fail("Should reject when no PROD binding");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("No PROD binding"));
        }
        verifyZeroObjectsCreated();
    }

    // ======= threshold evaluation =======

    @Test
    public void directDmlSubmit_blockExceeded_rejected_zeroObjects() {
        setupDefaults();
        setupRowLimit("warn:1000,block:100000");
        setupEstimator(100001, "evidence:block");

        try {
            service.directDmlSubmit(PUID, UID, buildFO());
            fail("Should reject when rows exceed block threshold");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("exceed block threshold"));
        }

        // DENY event written
        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        assertEquals(GovEventType.DIRECT_DML_DENY.name(), eventCaptor.getValue().getEventType());

        // Zero objects created
        verify(approvalControlService, never()).createSqlTicket(any(), any(), any(), any());
        verify(revisionMapper, never()).insert(any(DmDbChangeRevisionDO.class));
        verify(promotionMapper, never()).insert(any(DmDbChangePromotionDO.class));
        verify(stmtVersionMapper, never()).insert(any(DmDbChangeStmtVersionDO.class));
    }

    @Test
    public void directDmlSubmit_warnLevel_high() {
        setupDefaults();
        setupRowLimit("warn:1000,block:100000");
        setupEstimator(50000, "evidence:warn");
        setupTicketCreation();
        setupRevisionInsert();
        setupPromotionInsert();
        setupExistingTicketInfo();

        DirectDmlSubmitVO vo = service.directDmlSubmit(PUID, UID, buildFO());

        assertEquals("HIGH", vo.getRiskLevel());

        ArgumentCaptor<DmDbChangePromotionDO> promoCaptor = ArgumentCaptor.forClass(DmDbChangePromotionDO.class);
        verify(promotionMapper).insert(promoCaptor.capture());
        assertTrue(promoCaptor.getValue().getGateResult().contains("HIGH"));
    }

    @Test
    public void directDmlSubmit_thresholdNotConfigured_skipsEstimator() {
        setupDefaults();
        when(dmEnvParamService.queryParam(PUID, ENV_ID, EnvParamKeys.GOV_DML_ROW_LIMIT)).thenReturn(null);
        setupTicketCreation();
        setupRevisionInsert();
        setupPromotionInsert();
        setupExistingTicketInfo();

        DirectDmlSubmitVO vo = service.directDmlSubmit(PUID, UID, buildFO());

        assertEquals("NORMAL", vo.getRiskLevel());
        verify(govDmlRowEstimator, never()).estimate(any(), any(), any(), any());
    }

    @Test
    public void directDmlSubmit_pgDegradation_rowZero_notRejected() {
        setupDefaults();
        setupRowLimit("warn:1000,block:100000");
        setupEstimator(0, "evidence:pg-unsupported");
        setupTicketCreation();
        setupRevisionInsert();
        setupPromotionInsert();
        setupExistingTicketInfo();

        DirectDmlSubmitVO vo = service.directDmlSubmit(PUID, UID, buildFO());

        assertEquals("NORMAL", vo.getRiskLevel());
        assertEquals(Long.valueOf(TICKET_ID), vo.getTicketId());
    }

    @Test
    public void directDmlSubmit_rowLimitInvalidFormat_treatedAsUnconfigured() {
        setupDefaults();
        when(dmEnvParamService.queryParam(PUID, ENV_ID, EnvParamKeys.GOV_DML_ROW_LIMIT)).thenReturn("garbage");
        setupTicketCreation();
        setupRevisionInsert();
        setupPromotionInsert();
        setupExistingTicketInfo();

        DirectDmlSubmitVO vo = service.directDmlSubmit(PUID, UID, buildFO());

        assertEquals("NORMAL", vo.getRiskLevel());
        verify(govDmlRowEstimator, never()).estimate(any(), any(), any(), any());
    }

    // ======= mid-transaction failure =======

    @Test
    public void directDmlSubmit_revisionInsertFails_rollback() {
        setupDefaults();
        setupTicketCreation();
        doThrow(new RuntimeException("DB error"))
            .when(revisionMapper).insert(any(DmDbChangeRevisionDO.class));

        try {
            service.directDmlSubmit(PUID, UID, buildFO());
            fail("Should fail on revision insert");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("DB error"));
        }

        // Promotion and ticketInfo should not be created
        verify(promotionMapper, never()).insert(any(DmDbChangePromotionDO.class));
        verify(approvalMapper, never()).updateTicketInfo(anyLong(), anyString());
    }

    // ======= helpers =======

    private GovDirectDmlSubmitFO buildFO() {
        GovDirectDmlSubmitFO fo = new GovDirectDmlSubmitFO();
        fo.setLogicalDbId(LOGICAL_DB_ID);
        fo.setSql("UPDATE foo SET bar=1");
        fo.setRollbackSql("UPDATE foo SET bar=0");
        fo.setDescription("test direct DML");
        return fo;
    }

    private GovStmtRow createStmtRow(int index, String sql) {
        GovStmtRow row = new GovStmtRow();
        row.setStmtIndex(index);
        row.setStmtText(sql);
        row.setStmtHash("hash-" + index);
        return row;
    }

    private void setupBinding() {
        LogicalDbTarget target = new LogicalDbTarget();
        target.setBindingId(1L);
        target.setLogicalDbId(LOGICAL_DB_ID);
        target.setEnvId(ENV_ID);
        target.setDsId(DS_ID);
        target.setResPath("/mydb/");
        target.setGovRole(GovRole.PROD);
        when(logicalDbService.getBinding(PUID, LOGICAL_DB_ID, GovRole.PROD)).thenReturn(target);
        when(dmDsConfigService.fetchDsConfigFromExists(DS_ID)).thenReturn(new DataSourceConfig());

        DmLogicalDbDO logicalDb = new DmLogicalDbDO();
        logicalDb.setResourceName("mydb");
        when(logicalDbMapper.selectById(LOGICAL_DB_ID)).thenReturn(logicalDb);
    }

    private void setupSwitchOn() {
        when(dmEnvParamService.queryParam(PUID, ENV_ID, EnvParamKeys.GOV_DML_DIRECT)).thenReturn("on");
    }

    private void setupDefaults() {
        setupBinding();
        setupSwitchOn();
        when(dmEnvParamService.queryParam(PUID, ENV_ID, EnvParamKeys.GOV_DML_ROW_LIMIT)).thenReturn(null);

        GovSplitResult splitResult = new GovSplitResult();
        splitResult.setChangeType(ChangeType.DML);
        splitResult.setStmts(List.of(createStmtRow(1, "UPDATE foo SET bar=1")));
        when(govStmtSplitService.split(any(), any())).thenReturn(splitResult);

        DsLevels levels = mock(DsLevels.class);
        when(dmDsConfigService.parseLevels(anyList())).thenReturn(levels);
    }

    private void setupTicketCreation() {
        DmTicketResultVO result = new DmTicketResultVO();
        result.setTicketId(TICKET_ID);
        when(approvalControlService.createSqlTicket(eq(PUID), eq(UID), any(DmAddTicketFO.class), eq(ApprovalBiz.DM_CHANGE)))
            .thenReturn(result);
    }

    private void setupRevisionInsert() {
        doAnswer(invocation -> {
            DmDbChangeRevisionDO rev = invocation.getArgument(0);
            rev.setId(REVISION_ID);
            return 1;
        }).when(revisionMapper).insert(any(DmDbChangeRevisionDO.class));
    }

    private void setupPromotionInsert() {
        doAnswer(invocation -> {
            DmDbChangePromotionDO promo = invocation.getArgument(0);
            promo.setId(PROMOTION_ID);
            return 1;
        }).when(promotionMapper).insert(any(DmDbChangePromotionDO.class));
    }

    private void setupExistingTicketInfo() {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setTicketInfo(null);
        when(approvalMapper.selectById(TICKET_ID)).thenReturn(ticket);
    }

    private void setupRowLimit(String config) {
        when(dmEnvParamService.queryParam(PUID, ENV_ID, EnvParamKeys.GOV_DML_ROW_LIMIT)).thenReturn(config);
    }

    private void setupEstimator(long estimatedRows, String evidence) {
        when(govDmlRowEstimator.estimate(eq(PUID), any(DataSourceConfig.class), any(DsLevels.class), anyList()))
            .thenReturn(new GovDmlRowEstimator.RowEstimate(estimatedRows, evidence));
    }

    private void assertManifestPENDING(String manifest) {
        List<?> items = JsonUtils.toObj(manifest, List.class);
        assertNotNull(items);
        assertFalse(items.isEmpty());
        for (Object item : items) {
            java.util.Map<?, ?> map = (java.util.Map<?, ?>) item;
            assertEquals("PENDING", String.valueOf(map.get("pre_exec")));
        }
    }

    private void verifyZeroObjectsCreated() {
        verify(approvalControlService, never()).createSqlTicket(any(), any(), any(), any());
        verify(revisionMapper, never()).insert(any(DmDbChangeRevisionDO.class));
        verify(promotionMapper, never()).insert(any(DmDbChangePromotionDO.class));
        verify(stmtVersionMapper, never()).insert(any(DmDbChangeStmtVersionDO.class));
    }
}
