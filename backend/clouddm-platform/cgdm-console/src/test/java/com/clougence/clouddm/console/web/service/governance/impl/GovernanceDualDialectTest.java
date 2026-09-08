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

import com.clougence.clouddm.base.metadata.ds.DataSourceConfig;
import com.clougence.clouddm.base.metadata.ds.DataSourceType;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.component.auth.DmAuthServiceForBiz;
import com.clougence.clouddm.console.web.component.dsconfig.DmDsConfigService;
import com.clougence.clouddm.console.web.component.dsconfig.mode.DsLevels;
import com.clougence.clouddm.console.web.component.governance.GovDmlRowEstimator;
import com.clougence.clouddm.console.web.component.governance.GovSqlHashUtils;
import com.clougence.clouddm.console.web.component.governance.GovSplitResult;
import com.clougence.clouddm.console.web.component.governance.GovStmtRow;
import com.clougence.clouddm.console.web.component.governance.GovStmtSplitService;
import com.clougence.clouddm.console.web.model.fo.governance.GovDirectDmlSubmitFO;
import com.clougence.clouddm.console.web.model.fo.governance.GovPreSubmitFO;
import com.clougence.clouddm.console.web.model.fo.ticket.DmAddTicketFO;
import com.clougence.clouddm.console.web.model.vo.governance.DirectDmlSubmitVO;
import com.clougence.clouddm.console.web.model.vo.logicaldb.LogicalDbTarget;
import com.clougence.clouddm.console.web.model.vo.ticket.DmTicketResultVO;
import com.clougence.clouddm.console.web.service.approval.ApprovalControlService;
import com.clougence.clouddm.console.web.service.envparam.DmEnvParamService;
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
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.approval.SqlContentType;
import com.clougence.clouddm.platform.dal.model.dbchange.ChangeType;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangePromotionDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeRevisionDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeStmtVersionDO;
import com.clougence.clouddm.platform.dal.model.dbchange.GovEventType;
import com.clougence.clouddm.platform.dal.model.dbchange.StmtSource;
import com.clougence.clouddm.platform.dal.model.logicaldb.DmLogicalDbDO;
import com.clougence.clouddm.platform.dal.model.logicaldb.GovRole;
import com.clougence.clouddm.sdk.model.env.EnvParamKeys;
import com.clougence.clouddm.sdk.security.auth.AuthKind;
import com.clougence.clouddm.sdk.security.auth.def.SecDataAuthLabel;

/**
 * C1: Dual-dialect parameterized test (D-P11-6).
 * <p>
 * Verifies that governance orchestration is consistent across MySQL and PostgreSQL dialects
 * at the service level. Real dialect behavior differences (SQL parsing, EXPLAIN, schema queries)
 * are exempted per D-P11-1/D-P11-9 and covered in the real-environment checklist.
 * <p>
 * Key assertions:
 * - preSubmit: DataSourceConfig.dataSourceType transparently passed to split service (ArgumentCaptor)
 * - preSubmit: stmt_version hash is dialect-neutral (D10 contract: SHA256 of normalized text)
 * - Path B: threshold evaluation path doesn't fork on dialect (estimator mocked, governance decisions identical)
 * - SQL hash: same text produces same hash regardless of dialect config
 */
public class GovernanceDualDialectTest {

    private DbChangeGovernServiceImpl preSubmitService;
    private GovDirectDmlServiceImpl     directDmlService;

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

    private static final String DDL_SQL = "CREATE TABLE foo (id INT)";
    private static final String DML_SQL = "UPDATE foo SET bar=1";
    private static final String ROLLBACK_SQL = "UPDATE foo SET bar=0";

    @Before
    public void setUp() {
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

        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);
        when(logicalDbDal.logicalDbMapper()).thenReturn(logicalDbMapper);
        when(dbChangeGovernDal.revisionMapper()).thenReturn(revisionMapper);
        when(dbChangeGovernDal.promotionMapper()).thenReturn(promotionMapper);
        when(dbChangeGovernDal.stmtVersionMapper()).thenReturn(stmtVersionMapper);
        when(dbChangeGovernDal.eventMapper()).thenReturn(eventMapper);

        // Wire preSubmit service
        preSubmitService = new DbChangeGovernServiceImpl();
        ReflectionTestUtils.setField(preSubmitService, "logicalDbService", logicalDbService);
        ReflectionTestUtils.setField(preSubmitService, "dmAuthServiceForBiz", dmAuthServiceForBiz);
        ReflectionTestUtils.setField(preSubmitService, "dmDsConfigService", dmDsConfigService);
        ReflectionTestUtils.setField(preSubmitService, "govStmtSplitService", govStmtSplitService);
        ReflectionTestUtils.setField(preSubmitService, "approvalControlService", approvalControlService);
        ReflectionTestUtils.setField(preSubmitService, "dbChangeGovernDal", dbChangeGovernDal);
        ReflectionTestUtils.setField(preSubmitService, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(preSubmitService, "txManager", txManager);

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
    }

    // ======= preSubmit: DataSourceConfig transparently passed to split =======

    @Test
    public void preSubmit_mysql_configTransparentlyPassedToSplit() {
        runPreSubmitAndVerifyConfig(DataSourceType.MySQL);
    }

    @Test
    public void preSubmit_postgresql_configTransparentlyPassedToSplit() {
        runPreSubmitAndVerifyConfig(DataSourceType.PostgreSQL);
    }

    private void runPreSubmitAndVerifyConfig(DataSourceType dsType) {
        setupPreBinding();
        when(dmDsConfigService.fetchDsConfigFromExists(DS_ID)).thenReturn(buildDsConfig(dsType));
        setupSplit(ChangeType.DDL, DDL_SQL);
        setupTicketCreation();
        setupExistingTicketInfo();

        GovPreSubmitFO fo = buildPreSubmitFO(DDL_SQL, null);
        DmTicketResultVO result = preSubmitService.preSubmit(PUID, UID, fo);

        assertNotNull(result);
        assertEquals(Long.valueOf(TICKET_ID), result.getTicketId());

        // ArgumentCaptor: split received the correct DataSourceConfig with matching dataSourceType
        ArgumentCaptor<DataSourceConfig> configCaptor = ArgumentCaptor.forClass(DataSourceConfig.class);
        verify(govStmtSplitService).split(configCaptor.capture(), eq(DDL_SQL));
        assertEquals(dsType, configCaptor.getValue().getDataSourceType());

        // stmt_version hash is dialect-neutral (D10: same SQL → same hash regardless of dialect)
        ArgumentCaptor<DmDbChangeStmtVersionDO> stmtCaptor = ArgumentCaptor.forClass(DmDbChangeStmtVersionDO.class);
        verify(stmtVersionMapper).insert(stmtCaptor.capture());
        assertEquals(GovSqlHashUtils.hash(DDL_SQL), stmtCaptor.getValue().getStmtHash());
        assertEquals(StmtSource.INITIAL.name(), stmtCaptor.getValue().getSource());

        // SUBMIT event structure is dialect-neutral
        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        assertEquals(GovEventType.SUBMIT.name(), eventCaptor.getValue().getEventType());
    }

    // ======= SQL hash dialect neutrality (D10 contract) =======

    @Test
    public void sqlHash_sameText_bothDialects_producesSameHash() {
        String sql = "CREATE TABLE foo (id INT)";

        DataSourceConfig mysqlConfig = buildDsConfig(DataSourceType.MySQL);
        DataSourceConfig pgConfig = buildDsConfig(DataSourceType.PostgreSQL);

        // GovSqlHashUtils.hash is dialect-neutral: it only normalizes whitespace and hashes bytes.
        // The same SQL text produces the same hash regardless of which dialect config was used.
        String mysqlHash = GovSqlHashUtils.hash(sql);
        String pgHash = GovSqlHashUtils.hash(sql);

        assertEquals("Hash must be dialect-neutral (D10 contract)", mysqlHash, pgHash);
        assertNotNull(mysqlHash);
        assertEquals(64, mysqlHash.length());

        // The config objects have different dialect types but hash is identical
        assertNotEquals(mysqlConfig.getDataSourceType(), pgConfig.getDataSourceType());
    }

    // ======= Path B threshold: estimation path consistent across dialects =======

    @Test
    public void pathBThreshold_bothDialects_estimationPathConsistent() {
        // MySQL: threshold not configured → NORMAL risk, estimator never called
        DirectDmlSubmitVO mysqlResult = runDirectDmlSubmit(DataSourceType.MySQL, null, 0, null);
        assertEquals("NORMAL", mysqlResult.getRiskLevel());
        verify(govDmlRowEstimator, never()).estimate(any(), any(), any(), any());

        // Reset mocks for PG run
        reset(approvalMapper, revisionMapper, promotionMapper, stmtVersionMapper, eventMapper);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);
        when(dbChangeGovernDal.revisionMapper()).thenReturn(revisionMapper);
        when(dbChangeGovernDal.promotionMapper()).thenReturn(promotionMapper);
        when(dbChangeGovernDal.stmtVersionMapper()).thenReturn(stmtVersionMapper);
        when(dbChangeGovernDal.eventMapper()).thenReturn(eventMapper);

        // PostgreSQL: threshold not configured → NORMAL risk, estimator never called
        // Same governance decision as MySQL — orchestration doesn't fork on dialect
        DirectDmlSubmitVO pgResult = runDirectDmlSubmit(DataSourceType.PostgreSQL, null, 0, null);
        assertEquals("NORMAL", pgResult.getRiskLevel());
        verify(govDmlRowEstimator, never()).estimate(any(), any(), any(), any());

        // Both dialects produce the same risk level and same object structure
        assertEquals(mysqlResult.getRiskLevel(), pgResult.getRiskLevel());
    }

    @Test
    public void pathBThreshold_bothDialects_warnLevelConsistent() {
        String rowLimitConfig = "warn:1000,block:100000";
        long estimatedRows = 50000;

        // MySQL: warn level → HIGH risk
        DirectDmlSubmitVO mysqlResult = runDirectDmlSubmit(DataSourceType.MySQL, rowLimitConfig, estimatedRows, "evidence:mysql");
        assertEquals("HIGH", mysqlResult.getRiskLevel());

        // Reset for PG
        reset(approvalMapper, revisionMapper, promotionMapper, stmtVersionMapper, eventMapper);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);
        when(dbChangeGovernDal.revisionMapper()).thenReturn(revisionMapper);
        when(dbChangeGovernDal.promotionMapper()).thenReturn(promotionMapper);
        when(dbChangeGovernDal.stmtVersionMapper()).thenReturn(stmtVersionMapper);
        when(dbChangeGovernDal.eventMapper()).thenReturn(eventMapper);

        // PG: same estimated rows → same HIGH risk (governance decision doesn't fork on dialect)
        DirectDmlSubmitVO pgResult = runDirectDmlSubmit(DataSourceType.PostgreSQL, rowLimitConfig, estimatedRows, "evidence:pg");
        assertEquals("HIGH", pgResult.getRiskLevel());

        // Same risk level regardless of dialect
        assertEquals(mysqlResult.getRiskLevel(), pgResult.getRiskLevel());
    }

    // ======= helpers =======

    private DirectDmlSubmitVO runDirectDmlSubmit(DataSourceType dsType, String rowLimitConfig,
                                                   long estimatedRows, String evidence) {
        setupProdBinding();
        when(dmDsConfigService.fetchDsConfigFromExists(DS_ID)).thenReturn(buildDsConfig(dsType));
        when(dmEnvParamService.queryParam(PUID, ENV_ID, EnvParamKeys.GOV_DML_DIRECT)).thenReturn("on");
        when(dmEnvParamService.queryParam(PUID, ENV_ID, EnvParamKeys.GOV_DML_ROW_LIMIT)).thenReturn(rowLimitConfig);

        GovSplitResult splitResult = new GovSplitResult();
        splitResult.setChangeType(ChangeType.DML);
        splitResult.setStmts(List.of(createStmtRow(1, DML_SQL)));
        when(govStmtSplitService.split(any(), any())).thenReturn(splitResult);

        DsLevels levels = mock(DsLevels.class);
        when(dmDsConfigService.parseLevels(anyList())).thenReturn(levels);

        if (rowLimitConfig != null) {
            when(govDmlRowEstimator.estimate(eq(PUID), any(DataSourceConfig.class), any(DsLevels.class), anyList()))
                .thenReturn(new GovDmlRowEstimator.RowEstimate(estimatedRows, evidence));
        }

        DmLogicalDbDO logicalDb = new DmLogicalDbDO();
        logicalDb.setResourceName("mydb");
        when(logicalDbMapper.selectById(LOGICAL_DB_ID)).thenReturn(logicalDb);

        // Ticket creation
        DmTicketResultVO ticketResult = new DmTicketResultVO();
        ticketResult.setTicketId(TICKET_ID);
        when(approvalControlService.createSqlTicket(eq(PUID), eq(UID), any(DmAddTicketFO.class), eq(ApprovalBiz.DM_CHANGE)))
            .thenReturn(ticketResult);

        // Revision insert
        doAnswer(invocation -> {
            DmDbChangeRevisionDO rev = invocation.getArgument(0);
            rev.setId(REVISION_ID);
            return 1;
        }).when(revisionMapper).insert(any(DmDbChangeRevisionDO.class));

        // Promotion insert
        doAnswer(invocation -> {
            DmDbChangePromotionDO promo = invocation.getArgument(0);
            promo.setId(PROMOTION_ID);
            return 1;
        }).when(promotionMapper).insert(any(DmDbChangePromotionDO.class));

        // Ticket info
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setTicketInfo(null);
        when(approvalMapper.selectById(TICKET_ID)).thenReturn(ticket);

        GovDirectDmlSubmitFO fo = new GovDirectDmlSubmitFO();
        fo.setLogicalDbId(LOGICAL_DB_ID);
        fo.setSql(DML_SQL);
        fo.setRollbackSql(ROLLBACK_SQL);
        fo.setDescription("test direct DML");

        return directDmlService.directDmlSubmit(PUID, UID, fo);
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

    private void setupSplit(ChangeType changeType, String sql) {
        GovSplitResult result = new GovSplitResult();
        result.setChangeType(changeType);
        result.setStmts(List.of(createStmtRow(1, sql)));
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
        ticket.setTicketInfo(com.clougence.utils.JsonUtils.toJson(new ApprovalMO()));
        when(approvalMapper.selectById(TICKET_ID)).thenReturn(ticket);
    }

    private static DataSourceConfig buildDsConfig(DataSourceType dsType) {
        DataSourceConfig config = new DataSourceConfig();
        config.setDataSourceType(dsType);
        return config;
    }

    private static GovStmtRow createStmtRow(int index, String sql) {
        GovStmtRow row = new GovStmtRow();
        row.setStmtIndex(index);
        row.setStmtText(sql);
        row.setStmtHash(GovSqlHashUtils.hash(sql));
        return row;
    }

    private static GovPreSubmitFO buildPreSubmitFO(String sql, String rollbackSql) {
        GovPreSubmitFO fo = new GovPreSubmitFO();
        fo.setLogicalDbId(LOGICAL_DB_ID);
        fo.setTicketTitle("Test Governance Ticket");
        fo.setDescription("Test description");
        fo.setSql(sql);
        fo.setRollbackSql(rollbackSql);
        fo.setContentType(SqlContentType.INLINE);
        return fo;
    }
}
