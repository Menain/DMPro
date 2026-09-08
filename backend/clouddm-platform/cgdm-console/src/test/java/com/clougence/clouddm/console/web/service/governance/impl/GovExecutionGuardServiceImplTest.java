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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.api.console.autoexec.ErrorStrategy;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.component.dsconfig.DmDsConfigService;
import com.clougence.clouddm.console.web.component.governance.GovPreflightChecker;
import com.clougence.clouddm.console.web.component.governance.GovSqlHashUtils;
import com.clougence.clouddm.console.web.component.governance.GovSplitResult;
import com.clougence.clouddm.console.web.component.governance.GovStmtRow;
import com.clougence.clouddm.console.web.component.governance.GovStmtSplitService;
import com.clougence.clouddm.console.web.model.vo.logicaldb.LogicalDbTarget;
import com.clougence.clouddm.console.web.service.governance.GovExecutionGuardService;
import com.clougence.clouddm.console.web.service.logicaldb.LogicalDbService;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.DataSourceDal;
import com.clougence.clouddm.platform.dal.access.DbChangeGovernDal;
import com.clougence.clouddm.platform.dal.access.ExecutionDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.datasource.DmDsMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangePromotionMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangeRevisionMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangeEventMapper;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoJobMapper;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoTaskMapper;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.datasource.DmDsDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangePromotionDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeRevisionDO;
import com.clougence.clouddm.platform.dal.model.dbchange.GovEventType;
import com.clougence.clouddm.platform.dal.model.dbchange.PromotionStatus;
import com.clougence.clouddm.platform.dal.model.execution.RsExecAutoJobConfigObj;
import com.clougence.clouddm.platform.dal.model.logicaldb.GovRole;
import com.clougence.utils.JsonUtils;

public class GovExecutionGuardServiceImplTest {

    private GovExecutionGuardService service;

    private ApprovalDal             approvalDal;
    private DmApprovalMapper       approvalMapper;
    private DbChangeGovernDal      dbChangeGovernDal;
    private DmDbChangePromotionMapper promotionMapper;
    private DmDbChangeRevisionMapper revisionMapper;
    private DmDbChangeEventMapper  eventMapper;
    private ExecutionDal           executionDal;
    private DmExecAutoJobMapper    jobMapper;
    private DmExecAutoTaskMapper   taskMapper;
    private DataSourceDal          dataSourceDal;
    private DmDsMapper             dsMapper;
    private LogicalDbService       logicalDbService;
    private GovStmtSplitService    govStmtSplitService;
    private GovPreflightChecker    govPreflightChecker;
    private DmDsConfigService      dmDsConfigService;

    private static final String    PUID          = "puid-001";
    private static final long     TICKET_ID     = 100L;
    private static final long     PROMOTION_ID  = 50L;
    private static final long     REVISION_ID   = 30L;
    private static final long     LOGICAL_DB_ID = 10L;
    private static final long     DS_ID         = 20L;
    private static final long     ENV_ID        = 5L;
    private static final String   SQL_TEXT      = "CREATE TABLE foo (id INT)";
    private static final String   SQL_HASH      = GovSqlHashUtils.hash(SQL_TEXT);

    @Before
    public void setUp() {
        approvalDal = mock(ApprovalDal.class);
        dbChangeGovernDal = mock(DbChangeGovernDal.class);
        executionDal = mock(ExecutionDal.class);
        dataSourceDal = mock(DataSourceDal.class);
        logicalDbService = mock(LogicalDbService.class);
        govStmtSplitService = mock(GovStmtSplitService.class);
        govPreflightChecker = mock(GovPreflightChecker.class);
        dmDsConfigService = mock(DmDsConfigService.class);

        approvalMapper = mock(DmApprovalMapper.class);
        promotionMapper = mock(DmDbChangePromotionMapper.class);
        revisionMapper = mock(DmDbChangeRevisionMapper.class);
        eventMapper = mock(DmDbChangeEventMapper.class);
        jobMapper = mock(DmExecAutoJobMapper.class);
        taskMapper = mock(DmExecAutoTaskMapper.class);
        dsMapper = mock(DmDsMapper.class);

        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);
        when(dbChangeGovernDal.promotionMapper()).thenReturn(promotionMapper);
        when(dbChangeGovernDal.revisionMapper()).thenReturn(revisionMapper);
        when(dbChangeGovernDal.eventMapper()).thenReturn(eventMapper);
        when(executionDal.autoJobMapper()).thenReturn(jobMapper);
        when(executionDal.autoTaskMapper()).thenReturn(taskMapper);
        when(dataSourceDal.dsMapper()).thenReturn(dsMapper);

        GovExecutionGuardServiceImpl impl = new GovExecutionGuardServiceImpl();
        ReflectionTestUtils.setField(impl, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(impl, "dbChangeGovernDal", dbChangeGovernDal);
        ReflectionTestUtils.setField(impl, "executionDal", executionDal);
        ReflectionTestUtils.setField(impl, "dataSourceDal", dataSourceDal);
        ReflectionTestUtils.setField(impl, "logicalDbService", logicalDbService);
        ReflectionTestUtils.setField(impl, "govStmtSplitService", govStmtSplitService);
        ReflectionTestUtils.setField(impl, "govPreflightChecker", govPreflightChecker);
        ReflectionTestUtils.setField(impl, "dmDsConfigService", dmDsConfigService);
        service = impl;
    }

    // ======= short-circuit tests =======

    @Test
    public void checkByTicket_nonGovernance_passZeroGovQueries() {
        DmApprovalDO ticket = buildTicket(null);
        RsExecAutoJobConfigObj config = buildConfig(ErrorStrategy.NONE, true);

        var result = service.checkByTicket(PUID, ticket, config);

        assertTrue(result.isPass());
        verify(dbChangeGovernDal, never()).promotionMapper();
        verify(dbChangeGovernDal, never()).revisionMapper();
        verify(dbChangeGovernDal, never()).eventMapper();
    }

    @Test
    public void checkByTicket_preGovernance_passZeroGovQueries() {
        DmApprovalDO ticket = buildTicket(GovRole.PRE.name());
        RsExecAutoJobConfigObj config = buildConfig(ErrorStrategy.NONE, true);

        var result = service.checkByTicket(PUID, ticket, config);

        assertTrue(result.isPass());
        verify(dbChangeGovernDal, never()).promotionMapper();
        verify(dbChangeGovernDal, never()).revisionMapper();
        verify(dbChangeGovernDal, never()).eventMapper();
    }

    // ======= G1: promotion status =======

    @Test
    public void checkByTicket_promotionStatusCreated_denyG1() {
        when(promotionMapper.selectById(PROMOTION_ID)).thenReturn(buildPromotion(PromotionStatus.CREATED));
        RsExecAutoJobConfigObj config = buildConfig(ErrorStrategy.NONE, false);

        var result = service.checkByTicket(PUID, buildTicket("PROD"), config);

        assertTrue(result.isDeny());
        assertTrue(result.getSummary().contains("G1"));
        verify(revisionMapper, never()).selectById(any());
        // Event trail: GUARD_DENY written
        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        assertEquals(GovEventType.GUARD_DENY.name(), eventCaptor.getValue().getEventType());
    }

    @Test
    public void checkByTicket_promotionStatusRejected_denyG1() {
        // DENY matrix ③: REJECTED variant (previously only CREATED was tested)
        when(promotionMapper.selectById(PROMOTION_ID)).thenReturn(buildPromotion(PromotionStatus.REJECTED));
        RsExecAutoJobConfigObj config = buildConfig(ErrorStrategy.NONE, false);

        var result = service.checkByTicket(PUID, buildTicket("PROD"), config);

        assertTrue(result.isDeny());
        assertTrue(result.getSummary().contains("G1"));
        verify(revisionMapper, never()).selectById(any());
        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        assertEquals(GovEventType.GUARD_DENY.name(), eventCaptor.getValue().getEventType());
    }

    @Test
    public void checkByTicket_promotionNotFound_denyG1() {
        when(promotionMapper.selectById(PROMOTION_ID)).thenReturn(null);
        RsExecAutoJobConfigObj config = buildConfig(ErrorStrategy.NONE, false);

        var result = service.checkByTicket(PUID, buildTicket("PROD"), config);

        assertTrue(result.isDeny());
        verify(revisionMapper, never()).selectById(any());
        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        assertEquals(GovEventType.GUARD_DENY.name(), eventCaptor.getValue().getEventType());
    }

    // ======= G2: hash re-verification =======

    @Test
    public void checkByTicket_wholeTicketHashMismatch_denyG2() {
        DmApprovalDO ticket = buildTicket("PROD");
        ticket.setRawSql("DROP TABLE foo");
        when(promotionMapper.selectById(PROMOTION_ID)).thenReturn(buildPromotion(PromotionStatus.APPROVED));
        DmDbChangeRevisionDO rev = buildRevision();
        rev.setSqlHash("different_hash_value");
        when(revisionMapper.selectById(REVISION_ID)).thenReturn(rev);
        RsExecAutoJobConfigObj config = buildConfig(ErrorStrategy.NONE, false);

        var result = service.checkByTicket(PUID, ticket, config);

        assertTrue(result.isDeny());
        assertTrue(result.getSummary().contains("G2"));
        assertTrue(result.getSummary().contains("hash"));
        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        assertEquals(GovEventType.GUARD_DENY.name(), eventCaptor.getValue().getEventType());
    }

    @Test
    public void checkByTicket_revisionNotFound_denyG2() {
        when(promotionMapper.selectById(PROMOTION_ID)).thenReturn(buildPromotion(PromotionStatus.APPROVED));
        when(revisionMapper.selectById(REVISION_ID)).thenReturn(null);
        RsExecAutoJobConfigObj config = buildConfig(ErrorStrategy.NONE, false);

        var result = service.checkByTicket(PUID, buildTicket("PROD"), config);

        assertTrue(result.isDeny());
        assertTrue(result.getSummary().contains("G2"));
        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        assertEquals(GovEventType.GUARD_DENY.name(), eventCaptor.getValue().getEventType());
    }

    // ======= G3: binding re-verification =======

    @Test
    public void checkByTicket_bindingChanged_denyG3() {
        setupPassingG1G2();
        DmDbChangePromotionDO promo = buildPromotion(PromotionStatus.APPROVED);
        when(promotionMapper.selectById(PROMOTION_ID)).thenReturn(promo);
        LogicalDbTarget changedTarget = buildTarget();
        changedTarget.setDsId(999L);
        when(logicalDbService.getBinding(PUID, LOGICAL_DB_ID, GovRole.PROD)).thenReturn(changedTarget);
        RsExecAutoJobConfigObj config = buildConfig(ErrorStrategy.NONE, false);

        var result = service.checkByTicket(PUID, buildTicket("PROD"), config);

        assertTrue(result.isDeny());
        assertTrue(result.getSummary().contains("G3"));
        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        assertEquals(GovEventType.GUARD_DENY.name(), eventCaptor.getValue().getEventType());
    }

    @Test
    public void checkByTicket_bindingResolutionFail_denyG3() {
        setupPassingG1G2();
        when(promotionMapper.selectById(PROMOTION_ID)).thenReturn(buildPromotion(PromotionStatus.APPROVED));
        when(logicalDbService.getBinding(PUID, LOGICAL_DB_ID, GovRole.PROD))
            .thenThrow(new com.clougence.clouddm.api.common.exception.ErrorMessageException("no PROD binding"));
        RsExecAutoJobConfigObj config = buildConfig(ErrorStrategy.NONE, false);

        var result = service.checkByTicket(PUID, buildTicket("PROD"), config);

        assertTrue(result.isDeny());
        assertTrue(result.getSummary().contains("G3"));
        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        assertEquals(GovEventType.GUARD_DENY.name(), eventCaptor.getValue().getEventType());
    }

    // ======= G4: Preflight =======

    @Test
    public void checkByTicket_preflightFail_denyG4() {
        setupPassingG1G2();
        setupBindingMatch();
        when(promotionMapper.selectById(PROMOTION_ID)).thenReturn(buildPromotion(PromotionStatus.APPROVED));
        when(revisionMapper.selectById(REVISION_ID)).thenReturn(buildRevision());
        DmDsDO dsDO = new DmDsDO();
        dsDO.setId(DS_ID);
        when(dsMapper.queryDsIdentityById(DS_ID)).thenReturn(dsDO);
        when(dmDsConfigService.fetchDsConfigFromExists(DS_ID)).thenReturn(null);
        com.clougence.clouddm.console.web.component.governance.GateItem failItem
            = com.clougence.clouddm.console.web.component.governance.GateItem.deny("connectivity", "connection refused");
        when(govPreflightChecker.check(any(), any(), anyString(), any())).thenReturn(List.of(failItem));
        RsExecAutoJobConfigObj config = buildConfig(ErrorStrategy.NONE, false);

        var result = service.checkByTicket(PUID, buildTicket("PROD"), config);

        assertTrue(result.isDeny());
        assertTrue(result.getSummary().contains("G4"));
        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        assertEquals(GovEventType.GUARD_DENY.name(), eventCaptor.getValue().getEventType());
    }

    // ======= G5: idempotency =======

    @Test
    public void checkByTicket_executionKeyMismatch_denyG5() {
        setupPassingG1G2();
        setupBindingMatch();
        when(promotionMapper.selectById(PROMOTION_ID)).thenReturn(buildPromotion(PromotionStatus.APPROVED));
        when(revisionMapper.selectById(REVISION_ID)).thenReturn(buildRevision());
        DmDsDO dsDO = new DmDsDO();
        dsDO.setId(DS_ID);
        when(dsMapper.queryDsIdentityById(DS_ID)).thenReturn(dsDO);
        when(dmDsConfigService.fetchDsConfigFromExists(DS_ID)).thenReturn(null);
        when(govPreflightChecker.check(any(), any(), anyString(), any())).thenReturn(List.of());
        DmDbChangePromotionDO promo = buildPromotion(PromotionStatus.APPROVED);
        promo.setExecutionKey("wrong_key");
        when(promotionMapper.selectById(PROMOTION_ID)).thenReturn(promo);
        RsExecAutoJobConfigObj config = buildConfig(ErrorStrategy.NONE, false);

        var result = service.checkByTicket(PUID, buildTicket("PROD"), config);

        assertTrue(result.isDeny());
        assertTrue(result.getSummary().contains("G5"));
        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        assertEquals(GovEventType.GUARD_DENY.name(), eventCaptor.getValue().getEventType());
    }

    // ======= G6: config compliance =======

    @Test
    public void checkByTicket_configSkip_denyG6() {
        setupFullPassChain();
        RsExecAutoJobConfigObj config = buildConfig(ErrorStrategy.SKIP, false);

        var result = service.checkByTicket(PUID, buildTicket("PROD"), config);

        assertTrue(result.isDeny());
        assertTrue(result.getSummary().contains("G6"));
        assertTrue(result.getSummary().contains("SKIP"));
        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        assertEquals(GovEventType.GUARD_DENY.name(), eventCaptor.getValue().getEventType());
    }

    @Test
    public void checkByTicket_configTransactionalMismatch_denyG6() {
        setupFullPassChain();
        // DDL revision with enableTransactional=true → should fail
        RsExecAutoJobConfigObj config = buildConfig(ErrorStrategy.NONE, true);

        var result = service.checkByTicket(PUID, buildTicket("PROD"), config);

        assertTrue(result.isDeny());
        assertTrue(result.getSummary().contains("G6"));
        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        assertEquals(GovEventType.GUARD_DENY.name(), eventCaptor.getValue().getEventType());
    }

    // ======= full pass =======

    @Test
    public void checkByTicket_allPass() {
        setupFullPassChain();
        RsExecAutoJobConfigObj config = buildConfig(ErrorStrategy.NONE, false);

        var result = service.checkByTicket(PUID, buildTicket("PROD"), config);

        assertTrue(result.isPass());
        assertEquals("PASS", result.getSummary());
    }

    // ======= assertNotGovernanceProd (touchpoint #5) =======

    @Test(expected = com.clougence.clouddm.api.common.exception.ErrorMessageException.class)
    public void assertNotGovernanceProd_prodTicket_throws() {
        service.assertNotGovernanceProd(buildTicket("PROD"));
    }

    @Test
    public void assertNotGovernanceProd_preTicket_noThrow() {
        service.assertNotGovernanceProd(buildTicket(GovRole.PRE.name()));
    }

    @Test
    public void assertNotGovernanceProd_nonGovernanceTicket_noThrow() {
        service.assertNotGovernanceProd(buildTicket(null));
    }

    // ======= checkByJob =======

    @Test
    public void checkByJob_jobNotFound_deny() {
        when(jobMapper.queryById(999L)).thenReturn(null);

        var result = service.checkByJob(PUID, 999L);

        assertTrue(result.isDeny());
    }

    @Test
    public void checkByJob_ticketNotFound_deny() {
        com.clougence.clouddm.platform.dal.model.execution.DmExecAutoJobDO job
            = new com.clougence.clouddm.platform.dal.model.execution.DmExecAutoJobDO();
        job.setDependOnBizId("biz-001");
        when(jobMapper.queryById(1L)).thenReturn(job);
        when(approvalMapper.queryByBizId("biz-001")).thenReturn(null);

        var result = service.checkByJob(PUID, 1L);

        assertTrue(result.isDeny());
    }

    @Test
    public void checkByJob_nonGovernance_passZeroGovQueries() {
        com.clougence.clouddm.platform.dal.model.execution.DmExecAutoJobDO job
            = new com.clougence.clouddm.platform.dal.model.execution.DmExecAutoJobDO();
        job.setDependOnBizId("biz-001");
        when(jobMapper.queryById(1L)).thenReturn(job);
        DmApprovalDO ticket = buildTicket(null);
        when(approvalMapper.queryByBizId("biz-001")).thenReturn(ticket);

        var result = service.checkByJob(PUID, 1L);

        assertTrue(result.isPass());
        verify(dbChangeGovernDal, never()).promotionMapper();
        verify(dbChangeGovernDal, never()).eventMapper();
    }

    // ======= checkByJob PROD path: per-stmt hash via task rows (touchpoint #3) =======

    @Test
    public void checkByJob_prodTicket_allPass() {
        setupFullPassChain();
        // Override: checkByJob reads config from job.getConfig(), not FO
        com.clougence.clouddm.platform.dal.model.execution.DmExecAutoJobDO job
            = new com.clougence.clouddm.platform.dal.model.execution.DmExecAutoJobDO();
        job.setId(1L);
        job.setDependOnBizId("biz-001");
        RsExecAutoJobConfigObj jobConfig = new RsExecAutoJobConfigObj();
        jobConfig.setErrorStrategy(ErrorStrategy.NONE);
        jobConfig.setEnableTransactional(false); // DDL → false
        job.setConfig(jobConfig);
        when(jobMapper.queryById(1L)).thenReturn(job);
        // checkByJob internally calls jobMapper.queryById again for G2 per-stmt
        // task row for per-stmt hash (exec_order=1, exec_sql=SQL_TEXT)
        com.clougence.clouddm.platform.dal.model.execution.DmExecAutoTaskDO task
            = new com.clougence.clouddm.platform.dal.model.execution.DmExecAutoTaskDO();
        task.setExecOrder(1);
        task.setExecSql(SQL_TEXT);
        when(taskMapper.queryListByJobId(1L, null)).thenReturn(List.of(task));
        when(approvalMapper.queryByBizId("biz-001")).thenReturn(buildTicket("PROD"));

        var result = service.checkByJob(PUID, 1L);

        assertTrue(result.isPass());
    }

    @Test
    public void checkByJob_perStmtHashMismatch_denyG2() {
        setupPassingG1G2();
        // Override: set up job with config
        com.clougence.clouddm.platform.dal.model.execution.DmExecAutoJobDO job
            = new com.clougence.clouddm.platform.dal.model.execution.DmExecAutoJobDO();
        job.setId(1L);
        job.setDependOnBizId("biz-001");
        RsExecAutoJobConfigObj jobConfig = new RsExecAutoJobConfigObj();
        jobConfig.setErrorStrategy(ErrorStrategy.NONE);
        jobConfig.setEnableTransactional(false);
        job.setConfig(jobConfig);
        when(jobMapper.queryById(1L)).thenReturn(job);
        // Task with TAMPERED exec_sql (different from SQL_TEXT)
        com.clougence.clouddm.platform.dal.model.execution.DmExecAutoTaskDO tamperedTask
            = new com.clougence.clouddm.platform.dal.model.execution.DmExecAutoTaskDO();
        tamperedTask.setExecOrder(1);
        tamperedTask.setExecSql("DROP TABLE foo"); // tampered!
        when(taskMapper.queryListByJobId(1L, null)).thenReturn(List.of(tamperedTask));
        when(approvalMapper.queryByBizId("biz-001")).thenReturn(buildTicket("PROD"));
        // G1: promotion APPROVED
        when(promotionMapper.selectById(PROMOTION_ID)).thenReturn(buildPromotion(PromotionStatus.APPROVED));
        // G2: revision with matching whole-ticket hash (SQL_TEXT)
        when(revisionMapper.selectById(REVISION_ID)).thenReturn(buildRevision());

        var result = service.checkByJob(PUID, 1L);

        assertTrue(result.isDeny());
        assertTrue(result.getSummary().contains("G2"));
        assertTrue(result.getSummary().contains("idx=1"));
        // Event trail: GUARD_DENY written at dispatch time
        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        assertEquals(GovEventType.GUARD_DENY.name(), eventCaptor.getValue().getEventType());
    }

    // ======= persistence =======

    @Test
    public void checkByTicket_deny_persistsPreflightResultAndEvent() {
        when(promotionMapper.selectById(PROMOTION_ID)).thenReturn(null);
        RsExecAutoJobConfigObj config = buildConfig(ErrorStrategy.NONE, false);

        service.checkByTicket(PUID, buildTicket("PROD"), config);

        verify(promotionMapper).updatePreflightResult(eq(PROMOTION_ID), anyString());
        verify(eventMapper).insert(any(DmDbChangeEventDO.class));
    }

    @Test
    public void checkByTicket_pass_persistsPreflightResultAndEvent() {
        setupFullPassChain();
        RsExecAutoJobConfigObj config = buildConfig(ErrorStrategy.NONE, false);

        service.checkByTicket(PUID, buildTicket("PROD"), config);

        verify(promotionMapper).updatePreflightResult(eq(PROMOTION_ID), anyString());
        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        assertEquals(GovEventType.GUARD_PASS.name(), eventCaptor.getValue().getEventType());
    }

    // ======= helpers =======

    private void setupPassingG1G2() {
        // G1: promotion APPROVED
        when(promotionMapper.selectById(PROMOTION_ID)).thenReturn(buildPromotion(PromotionStatus.APPROVED));
        // G2: revision with matching hash + split
        when(revisionMapper.selectById(REVISION_ID)).thenReturn(buildRevision());
        DmDsDO dsDO = new DmDsDO();
        dsDO.setId(DS_ID);
        when(dsMapper.queryDsIdentityById(DS_ID)).thenReturn(dsDO);
        when(dmDsConfigService.fetchDsConfigFromExists(DS_ID)).thenReturn(null);
        when(govStmtSplitService.split(isNull(), eq(SQL_TEXT))).thenReturn(buildSplitResult());
    }

    private void setupFullPassChain() {
        setupPassingG1G2();
        setupBindingMatch();
        when(govPreflightChecker.check(any(), any(), anyString(), any())).thenReturn(List.of());
    }

    private void setupBindingMatch() {
        when(logicalDbService.getBinding(PUID, LOGICAL_DB_ID, GovRole.PROD)).thenReturn(buildTarget());
    }

    private DmApprovalDO buildTicket(String govRole) {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setBizId("biz-001");
        ticket.setPrimaryUid(PUID);
        ticket.setRawSql(SQL_TEXT);
        ticket.setBindDsId(DS_ID);

        ApprovalMO mo = new ApprovalMO();
        if (govRole != null) {
            mo.setGovRole(govRole);
            mo.setPromotionId(PROMOTION_ID);
            mo.setRevisionId(REVISION_ID);
            mo.setLogicalDbId(LOGICAL_DB_ID);
        }
        ticket.setTicketInfo(JsonUtils.toJson(mo));
        return ticket;
    }

    private DmDbChangePromotionDO buildPromotion(PromotionStatus status) {
        DmDbChangePromotionDO p = new DmDbChangePromotionDO();
        p.setId(PROMOTION_ID);
        p.setStatus(status.name());
        p.setRevisionId(REVISION_ID);
        p.setLogicalDbId(LOGICAL_DB_ID);
        p.setProdEnvId(ENV_ID);
        p.setProdDsId(DS_ID);
        p.setProdResPath("/mydb/");
        p.setExecutionKey(GovSqlHashUtils.hash(REVISION_ID + "|" + DS_ID + "|" + LOGICAL_DB_ID));
        return p;
    }

    private DmDbChangeRevisionDO buildRevision() {
        DmDbChangeRevisionDO rev = new DmDbChangeRevisionDO();
        rev.setId(REVISION_ID);
        rev.setSqlText(SQL_TEXT);
        rev.setSqlHash(SQL_HASH);
        rev.setChangeType("DDL");
        List<Map<String, Object>> manifest = new ArrayList<>();
        Map<String, Object> item = new HashMap<>();
        item.put("idx", 1);
        item.put("stmt_hash", SQL_HASH);
        item.put("version", 1);
        item.put("pre_exec", "SUCCESS");
        manifest.add(item);
        rev.setStmtManifest(JsonUtils.toJson(manifest));
        return rev;
    }

    private GovSplitResult buildSplitResult() {
        GovSplitResult result = new GovSplitResult();
        GovStmtRow row = new GovStmtRow();
        row.setStmtIndex(1);
        row.setStmtText(SQL_TEXT);
        row.setStmtHash(SQL_HASH);
        result.setStmts(List.of(row));
        result.setChangeType(com.clougence.clouddm.platform.dal.model.dbchange.ChangeType.DDL);
        return result;
    }

    private LogicalDbTarget buildTarget() {
        LogicalDbTarget t = new LogicalDbTarget();
        t.setBindingId(1L);
        t.setLogicalDbId(LOGICAL_DB_ID);
        t.setEnvId(ENV_ID);
        t.setDsId(DS_ID);
        t.setResPath("/mydb/");
        t.setGovRole(GovRole.PROD);
        return t;
    }

    private RsExecAutoJobConfigObj buildConfig(ErrorStrategy errorStrategy, boolean enableTransactional) {
        RsExecAutoJobConfigObj config = new RsExecAutoJobConfigObj();
        config.setErrorStrategy(errorStrategy);
        config.setEnableTransactional(enableTransactional);
        return config;
    }
}
