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
package com.clougence.clouddm.console.web.component.execute.impl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.Date;
import java.util.EnumSet;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.api.console.autoexec.ErrorStrategy;
import com.clougence.clouddm.console.web.component.analysis.QueryAnalysisService;
import com.clougence.clouddm.console.web.component.dsconfig.DmDsConfigService;
import com.clougence.clouddm.platform.dal.access.DataSourceDal;
import com.clougence.clouddm.platform.dal.access.ExecutionDal;
import com.clougence.clouddm.platform.dal.access.TicketDbStmtDal;
import com.clougence.clouddm.platform.dal.mapper.datasource.DmDsMapper;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoJobMapper;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoTaskMapper;
import com.clougence.clouddm.platform.dal.mapper.govticket.DmTicketDbStmtMapper;
import com.clougence.clouddm.platform.dal.model.datasource.DmDsDO;
import com.clougence.clouddm.platform.dal.model.execution.AutoExecJobStatus;
import com.clougence.clouddm.platform.dal.model.execution.AutoExecType;
import com.clougence.clouddm.platform.dal.model.execution.DmExecAutoJobDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;
import com.clougence.clouddm.sdk.sql.parser.SplitQueryType;
import com.clougence.clouddm.sdk.sql.parser.SplitScript;

/**
 * B7 N0: the extended {@code createGroupJob} overload — the confirm-FO execution config
 * projection for the PROD_DML human-confirm chain migration (design §2.2, Step 0 finding ②:
 * the 6-arg form hardcoded IMMEDIATE/now and carried no retry parameters).
 * <ul>
 *   <li>execType/execTime pass through — SPECIFY_TIME keeps its scheduled semantics
 *       (schedule_time = execTime; the frozen scheduler scan holds INIT jobs until then,
 *       mirroring legacy {@code createJob} :192-196 verbatim);</li>
 *   <li>retryWaitTime/retryCount land in the job config; the RETRY validation message mirrors
 *       {@code createJob} exactly;</li>
 *   <li>the 6-arg overload delegates with byte-identical defaults (IMMEDIATE, scheduleTime≈now,
 *       retry fields NULL) — the five existing SYSTEM-chain callers never change shape
 *       (additive-extension regression pin);</li>
 *   <li>the v2 job row discriminator shape is untouched (dependOnGroupId set, dependOnBizId
 *       NULL, PREPARING, group flipped EXECUTING after insert).</li>
 *   <li>{@code deleteJobIfNotDispatched} (the race-free strict-cleanup guard): pre-dispatch
 *       PREPARING/INIT jobs are deleted under the row lock; scheduler-claimed jobs (PACKAGING+)
 *       survive — deleting a dispatched job would orphan the live sidecar execution and
 *       double-execute the group on re-confirm.</li>
 * </ul>
 * JUnit4 + handwritten mocks + ReflectionTestUtils (governance-contracts test discipline;
 * setup recipe mirrors AutoExecSegmentAwareJobTest — task construction itself stays pinned
 * there, zero changes).
 */
public class AutoExecGroupJobExecConfigTest {

    private AutoExecServiceImpl  autoExecService;
    private DmExecAutoJobMapper  autoJobMapper;
    private DmExecAutoTaskMapper autoTaskMapper;
    private DmTicketDbStmtMapper stmtMapper;

    private static final long   GROUP_ID   = 100L;
    private static final long   DS_ID      = 20L;
    private static final String DB         = "prod_db";
    private static final String JOB_BIZ_ID = "job-biz-1";

    @Before
    public void setUp() {
        autoExecService = new AutoExecServiceImpl();

        // DmTeamUtils static executionDal (biz-id generators) — recipe from AutoExecSegmentAwareJobTest
        ExecutionDal staticExecDal = mock(ExecutionDal.class);
        DmExecAutoJobMapper staticJobMapper = mock(DmExecAutoJobMapper.class);
        DmExecAutoTaskMapper staticTaskMapper = mock(DmExecAutoTaskMapper.class);
        when(staticExecDal.autoJobMapper()).thenReturn(staticJobMapper);
        when(staticExecDal.autoTaskMapper()).thenReturn(staticTaskMapper);
        when(staticJobMapper.queryByBizId(anyString())).thenReturn(null);
        when(staticTaskMapper.queryByBizId(anyString())).thenReturn(null);
        ReflectionTestUtils.setField(com.clougence.clouddm.console.web.util.DmTeamUtils.class, "executionDal", staticExecDal);

        ExecutionDal execDal = mock(ExecutionDal.class);
        autoJobMapper = mock(DmExecAutoJobMapper.class);
        autoTaskMapper = mock(DmExecAutoTaskMapper.class);
        when(execDal.autoJobMapper()).thenReturn(autoJobMapper);
        when(execDal.autoTaskMapper()).thenReturn(autoTaskMapper);
        when(autoJobMapper.queryByDependOnGroupId(GROUP_ID)).thenReturn(null);
        when(autoTaskMapper.batchInsert(anyList())).thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());

        TicketDbStmtDal ticketDbStmtDal = mock(TicketDbStmtDal.class);
        stmtMapper = mock(DmTicketDbStmtMapper.class);
        when(ticketDbStmtDal.stmtMapper()).thenReturn(stmtMapper);

        DataSourceDal dsDal = mock(DataSourceDal.class);
        DmDsMapper dsMapper = mock(DmDsMapper.class);
        when(dsDal.dsMapper()).thenReturn(dsMapper);
        DmDsDO dsDO = new DmDsDO();
        dsDO.setId(DS_ID);
        dsDO.setDsEnvId(1L);
        when(dsMapper.queryDsIdentityById(DS_ID)).thenReturn(dsDO);

        DmDsConfigService configService = mock(DmDsConfigService.class);
        when(configService.fetchDsConfigFromExists(DS_ID))
            .thenReturn(new com.clougence.clouddm.base.metadata.ds.DataSourceConfig());
        com.clougence.clouddm.console.web.component.dsconfig.mode.DsLevels levels
            = mock(com.clougence.clouddm.console.web.component.dsconfig.mode.DsLevels.class);
        when(levels.dbLevels()).thenReturn(List.of("1", String.valueOf(DS_ID), DB));
        when(configService.parseLevels(anyList())).thenReturn(levels);

        QueryAnalysisService analysisService = mock(QueryAnalysisService.class);
        when(analysisService.analysisSplitStream(any(), any(), any(), anyInt(), anyInt()))
            .thenAnswer(inv -> List.of(script(0, "UPDATE t SET a = 1 WHERE id > 0")).stream());

        ReflectionTestUtils.setField(autoExecService, "execDal", execDal);
        ReflectionTestUtils.setField(autoExecService, "ticketDbStmtDal", ticketDbStmtDal);
        ReflectionTestUtils.setField(autoExecService, "dsDal", dsDal);
        ReflectionTestUtils.setField(autoExecService, "configService", configService);
        ReflectionTestUtils.setField(autoExecService, "analysisService", analysisService);
        ReflectionTestUtils.setField(autoExecService, "txManager",
            mock(org.springframework.transaction.PlatformTransactionManager.class));
    }

    // ======= extended form: exec config projection =======

    @Test
    public void extendedForm_immediate_configProjectedOntoJobRow() {
        long before = System.currentTimeMillis();
        autoExecService.createGroupJob(group(), JOB_BIZ_ID, true, ErrorStrategy.NONE,
            111L, 2L, AutoExecType.IMMEDIATE, null, "zh-CN", "uid-1");

        DmExecAutoJobDO job = capturedJob();
        // v2 discriminator shape untouched (frozen contract :400)
        assertEquals(Long.valueOf(GROUP_ID), job.getDependOnGroupId());
        assertNull(job.getDependOnBizId());
        assertEquals(AutoExecJobStatus.PREPARING, job.getStatus());
        assertEquals(JOB_BIZ_ID, job.getBizId());
        assertEquals("uid-1", job.getUid());
        assertEquals(Long.valueOf(DS_ID), job.getDataSourceId());
        // config projection from the confirm FO
        assertEquals(AutoExecType.IMMEDIATE, job.getExecType());
        assertTrue(job.getConfig().isEnableTransactional());
        assertEquals(ErrorStrategy.NONE, job.getConfig().getErrorStrategy());
        assertEquals(Long.valueOf(111L), job.getConfig().getRetryWaitTime());
        assertEquals(Long.valueOf(2L), job.getConfig().getRetryCount());
        assertEquals("zh-CN", job.getConfig().getLanguageTag());
        // IMMEDIATE → scheduleTime = now (mirrors createJob :192-193)
        assertNotNull(job.getScheduleTime());
        assertTrue(job.getScheduleTime().getTime() >= before);
        assertTrue(job.getScheduleTime().getTime() <= System.currentTimeMillis());
        // group flip after insert (inheritance fix, unchanged)
        verify(stmtMapper).updateExecStatus(GROUP_ID, "EXECUTING", null);
    }

    @Test
    public void extendedForm_specifyTime_execTypeAndScheduleTimePassThrough() {
        long execTime = System.currentTimeMillis() + 3600_000L;
        autoExecService.createGroupJob(group(), JOB_BIZ_ID, false, ErrorStrategy.NONE,
            null, null, AutoExecType.SPECIFY_TIME, execTime, "zh-CN", "uid-1");

        DmExecAutoJobDO job = capturedJob();
        assertEquals(AutoExecType.SPECIFY_TIME, job.getExecType());
        // the frozen scheduler scan (listUnFinishJobIdList: now-5s >= schedule_time) holds the
        // INIT job until execTime — SPECIFY_TIME group semantics preserved (N0 requirement)
        assertEquals(new Date(execTime), job.getScheduleTime());
    }

    @Test
    public void extendedForm_retryStrategyWithoutParams_rejectedWithCreateJobMessage() {
        try {
            autoExecService.createGroupJob(group(), JOB_BIZ_ID, true, ErrorStrategy.RETRY,
                null, null, AutoExecType.IMMEDIATE, null, "zh-CN", "uid-1");
            fail("RETRY without retryWaitTime/retryCount must be rejected (createJob parity)");
        } catch (ErrorMessageException e) {
            // exact message parity with createJob :169
            assertEquals("retry wait time or retry count not should be null", e.getMessage());
        }
        // validation precedes every side effect — no job row, no group flip
        verify(autoJobMapper, never()).insert(any(DmExecAutoJobDO.class));
        verify(stmtMapper, never()).updateExecStatus(anyLong(), anyString(), any());
    }

    @Test
    public void extendedForm_retryStrategyNegativeParams_rejectedWithCreateJobMessage() {
        try {
            autoExecService.createGroupJob(group(), JOB_BIZ_ID, true, ErrorStrategy.RETRY,
                -1L, 2L, AutoExecType.IMMEDIATE, null, "zh-CN", "uid-1");
            fail("RETRY with negative retryWaitTime must be rejected (createJob parity)");
        } catch (ErrorMessageException e) {
            assertEquals("retry wait time or retry count should be greater than 0", e.getMessage());
        }
        verify(autoJobMapper, never()).insert(any(DmExecAutoJobDO.class));
    }

    @Test
    public void extendedForm_retryStrategyWithParams_projectedIntoConfig() {
        autoExecService.createGroupJob(group(), JOB_BIZ_ID, true, ErrorStrategy.RETRY,
            30L, 3L, AutoExecType.IMMEDIATE, null, "zh-CN", "uid-1");

        DmExecAutoJobDO job = capturedJob();
        assertEquals(ErrorStrategy.RETRY, job.getConfig().getErrorStrategy());
        assertEquals(Long.valueOf(30L), job.getConfig().getRetryWaitTime());
        assertEquals(Long.valueOf(3L), job.getConfig().getRetryCount());
    }

    // ======= 6-arg delegate: byte-parity with the pre-N0 hardcoded shape =======

    @Test
    public void sixArgDelegate_bitParity_immediateNowNoRetryFields() {
        long before = System.currentTimeMillis();
        autoExecService.createGroupJob(group(), JOB_BIZ_ID, false, ErrorStrategy.NONE, "zh-CN", "SYSTEM");

        DmExecAutoJobDO job = capturedJob();
        // exactly the pre-N0 hardcoded values (IMMEDIATE / new Date() / retry fields unset)
        assertEquals(AutoExecType.IMMEDIATE, job.getExecType());
        assertNotNull(job.getScheduleTime());
        assertTrue(job.getScheduleTime().getTime() >= before);
        assertNull(job.getConfig().getRetryWaitTime());
        assertNull(job.getConfig().getRetryCount());
        assertFalse(job.getConfig().isEnableTransactional());
        assertEquals(ErrorStrategy.NONE, job.getConfig().getErrorStrategy());
        assertEquals("zh-CN", job.getConfig().getLanguageTag());
        assertEquals("SYSTEM", job.getUid());
        assertEquals(Long.valueOf(GROUP_ID), job.getDependOnGroupId());
        assertNull(job.getDependOnBizId());
        verify(stmtMapper).updateExecStatus(GROUP_ID, "EXECUTING", null);
    }

    // ======= deleteJobIfNotDispatched: the race-free strict-cleanup guard (B7 N0) =======
    //
    // The N0 confirm-cleanup deletes created group jobs on the failure path. deleteJob (the
    // legacy unconditional form) would delete a job the scheduler already claimed (INIT→
    // PACKAGING row-locked CAS) and dispatched to the sidecar — orphaning the live execution
    // (reportMessage callbacks no-op on deleted rows) and, with the group row reset, silently
    // DOUBLE-EXECUTING the group's DML on re-confirm. The guard deletes ONLY pre-dispatch jobs
    // (PREPARING/INIT) under queryByIdForUpdate — the same row lock the claim CAS takes, so the
    // outcome is deterministic: claim first ⇒ guard sees PACKAGING+ and skips; delete first ⇒
    // the claim matches 0 rows and the dispatch no-ops.

    @Test
    public void deleteGuard_preparingJob_deletedAndReported() {
        DmExecAutoJobDO job = jobRow(7L, AutoExecJobStatus.PREPARING);
        when(autoJobMapper.queryByBizId(JOB_BIZ_ID)).thenReturn(job);
        when(autoJobMapper.queryByIdForUpdate(7L)).thenReturn(job);

        assertTrue(autoExecService.deleteJobIfNotDispatched(JOB_BIZ_ID));
        verify(autoTaskMapper).deleteByJobId(7L);
        verify(autoJobMapper).deleteById(7L);
    }

    @Test
    public void deleteGuard_initJob_deletedAndReported() {
        DmExecAutoJobDO job = jobRow(7L, AutoExecJobStatus.INIT);
        when(autoJobMapper.queryByBizId(JOB_BIZ_ID)).thenReturn(job);
        when(autoJobMapper.queryByIdForUpdate(7L)).thenReturn(job);

        assertTrue(autoExecService.deleteJobIfNotDispatched(JOB_BIZ_ID));
        verify(autoTaskMapper).deleteByJobId(7L);
        verify(autoJobMapper).deleteById(7L);
    }

    @Test
    public void deleteGuard_dispatchedJob_leftRunningNotDeleted() {
        // PACKAGING and beyond = claimed by the scheduler → LEFT RUNNING (writeback owns the
        // group state; the SUCCESS-retained re-confirm filter prevents double execution)
        for (AutoExecJobStatus dispatched : List.of(AutoExecJobStatus.PACKAGING, AutoExecJobStatus.WAIT_EXEC,
                AutoExecJobStatus.EXECUTING, AutoExecJobStatus.FINISH, AutoExecJobStatus.FAILED)) {
            setUp(); // fresh mocks per status
            DmExecAutoJobDO job = jobRow(7L, dispatched);
            when(autoJobMapper.queryByBizId(JOB_BIZ_ID)).thenReturn(job);
            when(autoJobMapper.queryByIdForUpdate(7L)).thenReturn(job);

            assertFalse("status " + dispatched + " must survive the guard",
                autoExecService.deleteJobIfNotDispatched(JOB_BIZ_ID));
            verify(autoJobMapper, never()).deleteById(anyLong());
            verify(autoTaskMapper, never()).deleteByJobId(anyLong());
        }
    }

    @Test
    public void deleteGuard_absentJob_falseWithoutSideEffects() {
        when(autoJobMapper.queryByBizId(JOB_BIZ_ID)).thenReturn(null);

        assertFalse(autoExecService.deleteJobIfNotDispatched(JOB_BIZ_ID));
        verify(autoJobMapper, never()).queryByIdForUpdate(anyLong());
        verify(autoJobMapper, never()).deleteById(anyLong());
        verify(autoTaskMapper, never()).deleteByJobId(anyLong());
    }

    // ======= helpers =======

    private static DmExecAutoJobDO jobRow(long id, AutoExecJobStatus status) {
        DmExecAutoJobDO job = new DmExecAutoJobDO();
        job.setId(id);
        job.setBizId(JOB_BIZ_ID);
        job.setStatus(status);
        return job;
    }

    private DmExecAutoJobDO capturedJob() {
        ArgumentCaptor<DmExecAutoJobDO> captor = ArgumentCaptor.forClass(DmExecAutoJobDO.class);
        verify(autoJobMapper).insert(captor.capture());
        return captor.getValue();
    }

    private static DmTicketDbStmtDO group() {
        DmTicketDbStmtDO group = new DmTicketDbStmtDO();
        group.setId(GROUP_ID);
        group.setTicketId(900L);
        group.setPairId(300L);
        group.setDsId(DS_ID);
        group.setDbName(DB);
        group.setSqlContent("UPDATE t SET a = 1 WHERE id > 0;");
        group.setPrecheckResult("{\"checkStatus\":\"PASS\",\"changeType\":\"DML\"}");
        group.setExecStatus("PENDING");
        return group;
    }

    private static SplitScript script(long index, String sql) {
        SplitScript script = new SplitScript();
        script.setIndex(index);
        script.setType(EnumSet.of(SplitQueryType.UPDATE));
        script.setScript(sql);
        return script;
    }
}
