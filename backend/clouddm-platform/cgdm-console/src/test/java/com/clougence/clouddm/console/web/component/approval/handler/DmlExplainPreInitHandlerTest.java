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
package com.clougence.clouddm.console.web.component.approval.handler;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.base.metadata.ds.DataSourceConfig;
import com.clougence.clouddm.console.web.component.approval.ApprovalService;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalAnalysisStateMO;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.component.approval.model.DmlExplainResultMO;
import com.clougence.clouddm.console.web.component.approval.model.PreInitContext;
import com.clougence.clouddm.console.web.component.dsconfig.mode.DsLevels;
import com.clougence.clouddm.console.web.component.governance.ImpactScope;
import com.clougence.clouddm.console.web.component.governance.RiskAssessmentService;
import com.clougence.clouddm.console.web.component.governance.RiskAssessor;
import com.clougence.clouddm.console.web.component.governance.RiskLevelConfig;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalProcessActivityMapper;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalProcessMapper;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalProcessActivityDO;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalProcessDO;
import com.clougence.clouddm.platform.dal.model.datasource.DmDsDO;
import com.clougence.utils.JsonUtils;

/**
 * Task 10-11 M1 (design §3/§7): the PRE_INIT expected-affected-rows aggregation stops
 * masquerading "unknown" as 0. PG DML estimates are null (the EXPLAIN SPI deliberately excludes
 * DML) — an all-null estimate set must persist NULL (the column is nullable), never a fake 0;
 * partial nulls sum the non-null part (the v2 submit-writeback aggregation semantics,
 * GovTicketV2ServiceImpl). Rating behavior is pinned UNCHANGED: the null-rows re-assessment
 * delegates to the real pure-function assessor and must land on the same level as before
 * (levelByRows(null) == levelByRows(0) == LOW — plan-b ruling, no upgrade-on-unknown).
 * <p>
 * JUnit4 + Mockito mocks + ReflectionTestUtils — no Spring context. The doHandle path runs with
 * no EXPLAIN plugin registered (PluginManager empty in unit tests → explainSpi null → empty
 * results), which is exactly the "every estimate unknown" shape.
 */
public class DmlExplainPreInitHandlerTest {

    private static final long TICKET_ID  = 77L;
    private static final long PROCESS_ID = 10L;

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private DmlExplainPreInitHandler        handler;
    private ApprovalService                 approvalService;
    private ApprovalDal                     approvalDal;
    private DmApprovalMapper                approvalMapper;
    private DmApprovalProcessMapper         processMapper;
    private DmApprovalProcessActivityMapper activityMapper;
    private RiskAssessmentService           riskAssessmentService;

    @Before
    public void setUp() {
        // GlobalConfUtils.getTempDataHome() honors app.data — keep the request-cache file inside the rule
        System.setProperty("app.data", tempFolder.getRoot().getAbsolutePath());

        handler = new DmlExplainPreInitHandler();
        approvalService = mock(ApprovalService.class);
        approvalDal = mock(ApprovalDal.class);
        approvalMapper = mock(DmApprovalMapper.class);
        processMapper = mock(DmApprovalProcessMapper.class);
        activityMapper = mock(DmApprovalProcessActivityMapper.class);
        riskAssessmentService = mock(RiskAssessmentService.class);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);
        when(approvalDal.processMapper()).thenReturn(processMapper);
        when(approvalDal.activityMapper()).thenReturn(activityMapper);

        // PreInitContext.startPhase lease chain
        DmApprovalProcessDO processDO = new DmApprovalProcessDO();
        processDO.setId(PROCESS_ID);
        when(processMapper.queryByStage(anyLong(), any())).thenReturn(processDO);
        when(activityMapper.queryByProcessIdAndActivityId(eq(PROCESS_ID), anyString())).thenReturn(new DmApprovalProcessActivityDO());
        when(activityMapper.updatePreInitContext(eq(PROCESS_ID), anyString(), anyString(), anyString())).thenReturn(1);

        ReflectionTestUtils.setField(handler, "approvalService", approvalService);
        ReflectionTestUtils.setField(handler, "riskAssessmentService", riskAssessmentService);
    }

    @After
    public void tearDown() {
        System.clearProperty("app.data");
    }

    // ==================== aggregation semantics (sumEstimatedAffectedRows) ====================

    @Test
    public void allNullEstimates_aggregateToNull_notZero() {
        // ① PG shape: every per-statement estimate unknown → null, NEVER a fake 0
        assertNull(DmlExplainPreInitHandler.sumEstimatedAffectedRows(List.of(result(null), result(null))));
    }

    @Test
    public void emptyResults_aggregateToNull() {
        // no DML / no explain result at all → null (previously 0)
        assertNull(DmlExplainPreInitHandler.sumEstimatedAffectedRows(List.of()));
    }

    @Test
    public void partialNulls_sumNonNullPart() {
        // ② mixed shape (e.g. literal INSERT estimable + UPDATE unknown) → sum of the non-null part
        Long sum = DmlExplainPreInitHandler.sumEstimatedAffectedRows(List.of(result(null), result(120L), result(null), result(30L)));
        assertEquals(Long.valueOf(150L), sum);
    }

    @Test
    public void allPresent_includingTrueZero_isExact() {
        // ③ a true zero-row estimate is a real value and stays distinguishable from unknown
        assertEquals(Long.valueOf(7L),
            DmlExplainPreInitHandler.sumEstimatedAffectedRows(List.of(result(0L), result(7L))));
        assertEquals(Long.valueOf(0L),
            DmlExplainPreInitHandler.sumEstimatedAffectedRows(List.of(result(0L))));
    }

    // ==================== doHandle write + risk re-assessment ====================

    @Test
    public void doHandle_unknownEstimates_persistsNullRows_andLevelNotUpgraded() throws Exception {
        // the re-assessment delegates to the REAL pure-function assessor with default config —
        // pins that null rows land on the same level as the pre-M1 zero (no upgrade-on-unknown)
        when(riskAssessmentService.assess(any(), anyLong(), anyList(), any(), any(), any(), any(), anyList()))
            .thenAnswer(inv -> RiskAssessor.assess(inv.getArgument(2), inv.getArgument(3), inv.getArgument(4),
                new RiskLevelConfig(), inv.getArgument(5), inv.getArgument(6), inv.getArgument(7)));

        DmApprovalDO approval = new DmApprovalDO();
        approval.setId(TICKET_ID);
        approval.setPrimaryUid("puid-001");
        approval.setEnvName("dev");
        approval.setLevels(List.of("5"));

        ApprovalMO mo = new ApprovalMO();
        mo.setRiskLevel("MEDIUM");
        ImpactScope stored = new ImpactScope();
        stored.addStmtType("UPDATE");
        stored.addTable("db.t1");
        stored.setRowsEstimated(false);
        mo.setImpactScope(JsonUtils.toJson(stored));
        mo.setImpactScopeText("stale text");
        approval.setTicketInfo(JsonUtils.toJson(mo));
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(approval);

        handler.doHandle(newContext(approval));

        // ① the DB write is NULL — not 0
        verify(approvalMapper).updateExpectedAffectedRows(eq(TICKET_ID), isNull());

        // ④ the re-assessment received null rows (unknown), never a fake 0
        ArgumentCaptor<Long> rowsCaptor = ArgumentCaptor.forClass(Long.class);
        verify(riskAssessmentService).assess(any(), anyLong(), anyList(), any(), rowsCaptor.capture(), any(), any(), anyList());
        assertNull(rowsCaptor.getValue());

        // rating behavior unchanged: UPDATE + null rows → LOW (same as the pre-M1 zero input),
        // and the written-back scope marks the row dimension as unknown (rowsEstimated=false)
        ArgumentCaptor<DmApprovalDO> updateCaptor = ArgumentCaptor.forClass(DmApprovalDO.class);
        verify(approvalMapper).updateById(updateCaptor.capture());
        ApprovalMO written = JsonUtils.toObj(updateCaptor.getValue().getTicketInfo(), ApprovalMO.class);
        assertEquals("LOW", written.getRiskLevel());
        ImpactScope writtenScope = JsonUtils.toObj(written.getImpactScope(), ImpactScope.class);
        assertEquals(Boolean.FALSE, writtenScope.getRowsEstimated());
        assertNull(writtenScope.getEstimatedRows());
    }

    // ==================== fixtures ====================

    private PreInitContext newContext(DmApprovalDO approval) {
        // no DataSourceType → PluginManager.findDsPlugin(null) → no EXPLAIN SPI → empty results
        DataSourceConfig dsConfig = new DataSourceConfig();
        DsLevels dsLevels = new DsLevels("5", new DmDsDO(), List.of("5"), List.of("db"),
            new ArrayList<>(), new HashMap<>());
        return new PreInitContext(approval, dsConfig, dsLevels, ApprovalAnalysisStateMO.TYPE_DML_EXPLAIN, approvalDal);
    }

    private static DmlExplainResultMO result(Long estimatedAffectedRows) {
        DmlExplainResultMO mo = new DmlExplainResultMO();
        mo.setEstimatedAffectedRows(estimatedAffectedRows);
        return mo;
    }
}
