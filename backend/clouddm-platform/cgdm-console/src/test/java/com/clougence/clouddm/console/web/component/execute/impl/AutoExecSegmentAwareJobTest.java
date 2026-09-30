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

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.api.console.autoexec.ErrorStrategy;
import com.clougence.clouddm.console.web.component.analysis.QueryAnalysisService;
import com.clougence.clouddm.console.web.component.dsconfig.DmDsConfigService;
import com.clougence.clouddm.console.web.component.governance.TicketSegment;
import com.clougence.clouddm.platform.dal.access.DataSourceDal;
import com.clougence.clouddm.platform.dal.access.ExecutionDal;
import com.clougence.clouddm.platform.dal.access.TicketDbStmtDal;
import com.clougence.clouddm.platform.dal.mapper.datasource.DmDsMapper;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoJobMapper;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoTaskMapper;
import com.clougence.clouddm.platform.dal.mapper.govticket.DmTicketDbStmtMapper;
import com.clougence.clouddm.platform.dal.model.datasource.DmDsDO;
import com.clougence.clouddm.platform.dal.model.execution.DmExecAutoJobDO;
import com.clougence.clouddm.platform.dal.model.execution.DmExecAutoTaskDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;
import com.clougence.clouddm.sdk.sql.parser.SplitQueryType;
import com.clougence.clouddm.sdk.sql.parser.SplitScript;
import com.clougence.utils.JsonUtils;

import java.util.EnumSet;

/**
 * B6 M2 (Step 8): the segment-aware task construction of createGroupJob —
 * <ul>
 * <li>exec_order keeps the GLOBAL numbering WITH HOLES: a ghost segment's statement consumes
 *     its number without a task row, so "exec_order = SplitScript.index + 1" holds for every
 *     task row that exists (stmt_index contract, M2 revision — the plan↔task join);</li>
 * <li>only the CURRENT AUTOEXEC segment's stmtRange becomes task rows (first non-terminal
 *     segment of the runtime);</li>
 * <li>an unsegmented group builds the whole text bit-for-bit as before (regression pin);</li>
 * <li>misuse shapes (current segment GHOST / corrupt runtime / empty range) fail the job build
 *     through the EXISTING cleanup path (job deleted + group restored PENDING with the error).</li>
 * </ul>
 * The signature is untouched — the segment mark rides on the group DO input (precheckResult /
 * execSegments columns travel with the row).
 */
public class AutoExecSegmentAwareJobTest {

    private AutoExecServiceImpl    autoExecService;
    private ExecutionDal           execDal;
    private DmExecAutoJobMapper    autoJobMapper;
    private DmExecAutoTaskMapper   autoTaskMapper;
    private TicketDbStmtDal        ticketDbStmtDal;
    private DmTicketDbStmtMapper   stmtMapper;
    private QueryAnalysisService   analysisService;

    private static final long   GROUP_ID = 100L;
    private static final long   DS_ID    = 20L;
    private static final String DB       = "pre_db";

    /** per-test split result of the group fulltext */
    private List<SplitScript>    splitScripts;

    @Before
    public void setUp() {
        autoExecService = new AutoExecServiceImpl();

        // DmTeamUtils static executionDal (biz-id generators)
        ExecutionDal staticExecDal = mock(ExecutionDal.class);
        DmExecAutoJobMapper staticJobMapper = mock(DmExecAutoJobMapper.class);
        DmExecAutoTaskMapper staticTaskMapper = mock(DmExecAutoTaskMapper.class);
        when(staticExecDal.autoJobMapper()).thenReturn(staticJobMapper);
        when(staticExecDal.autoTaskMapper()).thenReturn(staticTaskMapper);
        when(staticJobMapper.queryByBizId(anyString())).thenReturn(null);
        when(staticTaskMapper.queryByBizId(anyString())).thenReturn(null);
        ReflectionTestUtils.setField(com.clougence.clouddm.console.web.util.DmTeamUtils.class, "executionDal", staticExecDal);

        execDal = mock(ExecutionDal.class);
        autoJobMapper = mock(DmExecAutoJobMapper.class);
        autoTaskMapper = mock(DmExecAutoTaskMapper.class);
        when(execDal.autoJobMapper()).thenReturn(autoJobMapper);
        when(execDal.autoTaskMapper()).thenReturn(autoTaskMapper);
        when(autoJobMapper.queryByDependOnGroupId(GROUP_ID)).thenReturn(null);
        when(autoTaskMapper.batchInsert(anyList())).thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());

        ticketDbStmtDal = mock(TicketDbStmtDal.class);
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

        analysisService = mock(QueryAnalysisService.class);
        when(analysisService.analysisSplitStream(any(), any(), any(), anyInt(), anyInt()))
            .thenAnswer(inv -> splitScripts.stream());

        ReflectionTestUtils.setField(autoExecService, "execDal", execDal);
        ReflectionTestUtils.setField(autoExecService, "ticketDbStmtDal", ticketDbStmtDal);
        ReflectionTestUtils.setField(autoExecService, "dsDal", dsDal);
        ReflectionTestUtils.setField(autoExecService, "configService", configService);
        ReflectionTestUtils.setField(autoExecService, "analysisService", analysisService);
        ReflectionTestUtils.setField(autoExecService, "txManager",
            mock(org.springframework.transaction.PlatformTransactionManager.class));

        splitScripts = List.of(
            script(0, "INSERT INTO a VALUES (1)"),
            script(1, "UPDATE b SET x=1"),
            script(2, "ALTER TABLE t_big ADD COLUMN c1 INT"),
            script(3, "DELETE FROM c WHERE 1=1"));
    }

    @Test
    public void build_firstAutoexecSegment_onlyItsRangeBecomesTasks_globalNumbering() {
        // runtime: [AUTOEXEC[1,2] EXECUTING, GHOST(3) PENDING, AUTOEXEC[4,4] PENDING]
        DmTicketDbStmtDO group = group(runtimeJson(
            runtimeSeg(1, "AUTOEXEC", "EXECUTING", List.of(1, 2), null),
            runtimeSeg(2, "GHOST", "PENDING", null, 3),
            runtimeSeg(3, "AUTOEXEC", "PENDING", List.of(4, 4), null)));

        autoExecService.createGroupJob(group, "job-1", false, ErrorStrategy.NONE, "zh-CN", "SYSTEM");

        List<DmExecAutoTaskDO> tasks = capturedTasks();
        assertEquals(2, tasks.size());
        assertEquals(Integer.valueOf(1), tasks.get(0).getExecOrder());
        assertEquals("INSERT INTO a VALUES (1)", tasks.get(0).getExecSql());
        assertEquals(Integer.valueOf(2), tasks.get(1).getExecOrder());
        // the ghost statement (global position 3) has NO task row — the exec_order hole
        verify(stmtMapper).updateExecStatus(GROUP_ID, "EXECUTING", null);
    }

    @Test
    public void build_trailingAutoexecSegment_holeNumberingPreserved_planTaskJoinIntact() {
        // runtime: [AUTOEXEC[1,2] SUCCESS, GHOST(3) SUCCESS, AUTOEXEC[4,4] EXECUTING]
        DmTicketDbStmtDO group = group(runtimeJson(
            runtimeSeg(1, "AUTOEXEC", "SUCCESS", List.of(1, 2), null),
            runtimeSeg(2, "GHOST", "SUCCESS", null, 3),
            runtimeSeg(3, "AUTOEXEC", "EXECUTING", List.of(4, 4), null)));

        autoExecService.createGroupJob(group, "job-3", false, ErrorStrategy.NONE, "zh-CN", "SYSTEM");

        List<DmExecAutoTaskDO> tasks = capturedTasks();
        assertEquals(1, tasks.size());
        // exec_order = SplitScript.index + 1 = 4 (NOT re-densed to 1 — the stmt_index contract)
        assertEquals(Integer.valueOf(4), tasks.get(0).getExecOrder());
        assertEquals("DELETE FROM c WHERE 1=1", tasks.get(0).getExecSql());
    }

    @Test
    public void build_unsegmentedGroup_wholeTextLegacyShape_regressionPin() {
        DmTicketDbStmtDO group = group(null); // no runtime — legacy/M1/plain group

        autoExecService.createGroupJob(group, "job-legacy", false, ErrorStrategy.NONE, "zh-CN", "SYSTEM");

        List<DmExecAutoTaskDO> tasks = capturedTasks();
        assertEquals(4, tasks.size());
        for (int i = 0; i < 4; i++) {
            assertEquals(Integer.valueOf(i + 1), tasks.get(i).getExecOrder());
        }
    }

    @Test
    public void build_currentSegmentIsGhost_rejectedWithCleanup() {
        // misuse: createGroupJob must never be called while a GHOST segment is current —
        // refusing beats executing the whole text (which would run the ghost ALTER directly)
        DmTicketDbStmtDO group = group(runtimeJson(
            runtimeSeg(1, "GHOST", "EXECUTING", null, 1),
            runtimeSeg(2, "AUTOEXEC", "PENDING", List.of(2, 2), null)));

        try {
            autoExecService.createGroupJob(group, "job-x", false, ErrorStrategy.NONE, "zh-CN", "SYSTEM");
            fail("Expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("AUTOEXEC segment"));
        }
        verify(autoTaskMapper, never()).batchInsert(anyList());
    }

    @Test
    public void build_corruptRuntime_rejected_neverExecutesWholeText() {
        DmTicketDbStmtDO group = group("{not json");

        try {
            autoExecService.createGroupJob(group, "job-x", false, ErrorStrategy.NONE, "zh-CN", "SYSTEM");
            fail("Expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("Unparsable segment runtime"));
        }
        verify(autoTaskMapper, never()).batchInsert(anyList());
    }

    @Test
    public void build_segmentRangeEmptyInText_rejectedWithExistingEmptyJobGuard() {
        // plan/text mismatch (defensive): the current range yields zero statements → the
        // existing "at least one SQL statement" guard fires through the same cleanup path
        DmTicketDbStmtDO group = group(runtimeJson(
            runtimeSeg(1, "AUTOEXEC", "EXECUTING", List.of(7, 9), null)));

        try {
            autoExecService.createGroupJob(group, "job-x", false, ErrorStrategy.NONE, "zh-CN", "SYSTEM");
            fail("Expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("at least one SQL statement"));
        }
    }

    // ==================== helpers ====================

    private DmTicketDbStmtDO group(String execSegments) {
        DmTicketDbStmtDO group = new DmTicketDbStmtDO();
        group.setId(GROUP_ID);
        group.setTicketId(900L);
        group.setPairId(300L);
        group.setDsId(DS_ID);
        group.setDbName(DB);
        group.setSqlContent("INSERT INTO a VALUES (1); UPDATE b SET x=1; ALTER TABLE t_big ADD COLUMN c1 INT; DELETE FROM c WHERE 1=1;");
        group.setPrecheckResult("{\"checkStatus\":\"PASS\",\"changeType\":\"MIXED\"}");
        group.setExecStatus("PENDING");
        group.setExecSegments(execSegments);
        return group;
    }

    private static TicketSegment runtimeSeg(int segIdx, String type, String status,
                                            List<Integer> stmtRange, Integer stmtIndex) {
        TicketSegment seg = new TicketSegment();
        seg.setSegIdx(segIdx);
        seg.setType(type);
        seg.setStatus(status);
        seg.setStmtRange(stmtRange);
        seg.setStmtIndex(stmtIndex);
        if (stmtIndex != null) {
            seg.setSql("ALTER TABLE t_big ADD COLUMN c1 INT");
        }
        return seg;
    }

    private static String runtimeJson(TicketSegment... segments) {
        return JsonUtils.toJson(List.of(segments));
    }

    private static SplitScript script(long index, String sql) {
        SplitScript script = new SplitScript();
        script.setIndex(index);
        script.setType(EnumSet.of(sql.trim().toUpperCase().startsWith("ALTER")
            ? SplitQueryType.ALTER_TABLE : SplitQueryType.INSERT));
        script.setScript(sql);
        return script;
    }

    @SuppressWarnings("unchecked")
    private List<DmExecAutoTaskDO> capturedTasks() {
        ArgumentCaptor<List<DmExecAutoTaskDO>> captor = ArgumentCaptor.forClass(List.class);
        verify(autoTaskMapper, atLeastOnce()).batchInsert(captor.capture());
        List<DmExecAutoTaskDO> all = new ArrayList<>();
        for (List<DmExecAutoTaskDO> batch : captor.getAllValues()) {
            all.addAll(batch);
        }
        return all;
    }
}
