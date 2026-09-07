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
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.console.web.component.dsconfig.DmDsConfigService;
import com.clougence.clouddm.console.web.component.governance.GovStmtSplitService;
import com.clougence.clouddm.console.web.model.fo.governance.GovStmtTimelineFO;
import com.clougence.clouddm.console.web.model.vo.governance.StmtTimelineVO;
import com.clougence.clouddm.console.web.service.approval.ApprovalControlService;
import com.clougence.clouddm.console.web.service.logicaldb.LogicalDbService;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.DbChangeGovernDal;
import com.clougence.clouddm.platform.dal.access.ExecutionDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangeEventMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangeStmtVersionMapper;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoJobMapper;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoTaskMapper;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeStmtVersionDO;
import com.clougence.clouddm.platform.dal.model.dbchange.GovEventType;
import com.clougence.clouddm.platform.dal.model.dbchange.StmtSource;
import com.clougence.clouddm.platform.dal.model.execution.AutoExecTaskStatus;
import com.clougence.clouddm.platform.dal.model.execution.DmExecAutoJobDO;
import com.clougence.clouddm.platform.dal.model.execution.DmExecAutoTaskDO;
import com.clougence.utils.JsonUtils;

public class StmtTimelineTest {

    private DbChangeGovernServiceImpl service;

    private ApprovalDal          approvalDal;
    private DmApprovalMapper     approvalMapper;
    private DbChangeGovernDal   dbChangeGovernDal;
    private DmDbChangeStmtVersionMapper stmtVersionMapper;
    private DmDbChangeEventMapper eventMapper;
    private ExecutionDal        executionDal;
    private DmExecAutoJobMapper  autoJobMapper;
    private DmExecAutoTaskMapper autoTaskMapper;

    private static final long   TICKET_ID = 200L;
    private static final long   JOB_ID    = 60L;
    private static final String BIZ_ID    = "ticket-biz-002";

    @Before
    public void setUp() {
        approvalDal = mock(ApprovalDal.class);
        dbChangeGovernDal = mock(DbChangeGovernDal.class);
        executionDal = mock(ExecutionDal.class);

        approvalMapper = mock(DmApprovalMapper.class);
        stmtVersionMapper = mock(DmDbChangeStmtVersionMapper.class);
        eventMapper = mock(DmDbChangeEventMapper.class);
        autoJobMapper = mock(DmExecAutoJobMapper.class);
        autoTaskMapper = mock(DmExecAutoTaskMapper.class);

        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);
        when(dbChangeGovernDal.stmtVersionMapper()).thenReturn(stmtVersionMapper);
        when(dbChangeGovernDal.eventMapper()).thenReturn(eventMapper);
        when(executionDal.autoJobMapper()).thenReturn(autoJobMapper);
        when(executionDal.autoTaskMapper()).thenReturn(autoTaskMapper);

        service = new DbChangeGovernServiceImpl();
        ReflectionTestUtils.setField(service, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(service, "dbChangeGovernDal", dbChangeGovernDal);
        ReflectionTestUtils.setField(service, "executionDal", executionDal);
        ReflectionTestUtils.setField(service, "logicalDbService", mock(LogicalDbService.class));
        ReflectionTestUtils.setField(service, "dmAuthServiceForBiz", mock(com.clougence.clouddm.console.web.component.auth.DmAuthServiceForBiz.class));
        ReflectionTestUtils.setField(service, "dmDsConfigService", mock(DmDsConfigService.class));
        ReflectionTestUtils.setField(service, "govStmtSplitService", mock(GovStmtSplitService.class));
        ReflectionTestUtils.setField(service, "approvalControlService", mock(ApprovalControlService.class));
    }

    @Test
    public void stmtTimeline_multiVersion_correctionChain() {
        setupTicket();
        setupJob();
        // Task: old CANCELED + new WAIT_EXEC for exec_order=1
        DmExecAutoTaskDO oldTask = buildTask(301L, 1, AutoExecTaskStatus.CANCELED);
        DmExecAutoTaskDO newTask = buildTask(302L, 1, AutoExecTaskStatus.WAIT_EXEC);
        when(autoTaskMapper.queryListByJobId(JOB_ID, null)).thenReturn(List.of(oldTask, newTask));

        // stmt_version: v1 (INITIAL) + v2 (CORRECTION)
        DmDbChangeStmtVersionDO v1 = buildStmtVersion(1, 1, "INSERT INTO t VALUES (1)", "hash1", StmtSource.INITIAL, null);
        DmDbChangeStmtVersionDO v2 = buildStmtVersion(1, 2, "INSERT INTO t VALUES (2)", "hash2", StmtSource.CORRECTION, "syntax error");
        when(stmtVersionMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(v1, v2));

        // Event: CORRECTION event
        DmDbChangeEventDO corrEvent = new DmDbChangeEventDO();
        corrEvent.setEventType(GovEventType.CORRECTION.name());
        Map<String, Object> corrData = new HashMap<>();
        corrData.put("stmtIndex", 1);
        corrData.put("fromVersion", 1);
        corrData.put("toVersion", 2);
        corrEvent.setEventData(JsonUtils.toJson(corrData));
        when(eventMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(corrEvent));

        GovStmtTimelineFO fo = new GovStmtTimelineFO();
        fo.setTicketId(TICKET_ID);

        StmtTimelineVO vo = service.stmtTimeline("puid", "uid", fo);

        assertNotNull(vo.getGroups());
        assertEquals(1, vo.getGroups().size());

        StmtTimelineVO.StmtGroup group = vo.getGroups().get(0);
        assertEquals(1, group.getStmtIndex());
        assertEquals("WAIT_EXEC", group.getCurrentStatus());
        assertEquals(1, group.getCorrectionCount());

        List<StmtTimelineVO.VersionEntry> versions = group.getVersions();
        assertEquals(2, versions.size());
        assertEquals(1, versions.get(0).getVersion());
        assertEquals(StmtSource.INITIAL.name(), versions.get(0).getSource());
        assertEquals(2, versions.get(1).getVersion());
        assertEquals(StmtSource.CORRECTION.name(), versions.get(1).getSource());
        assertEquals("syntax error", versions.get(1).getFailReason());
    }

    @Test
    public void stmtTimeline_noJob_returnsUnknownStatus() {
        setupTicket();
        when(autoJobMapper.queryByDependOnBizId(BIZ_ID)).thenReturn(null);
        when(stmtVersionMapper.queryByTicketId(TICKET_ID)).thenReturn(Collections.emptyList());
        when(eventMapper.queryByTicketId(TICKET_ID)).thenReturn(Collections.emptyList());

        GovStmtTimelineFO fo = new GovStmtTimelineFO();
        fo.setTicketId(TICKET_ID);

        StmtTimelineVO vo = service.stmtTimeline("puid", "uid", fo);

        assertNotNull(vo.getGroups());
        assertTrue(vo.getGroups().isEmpty());
    }

    @Test
    public void stmtTimeline_canceledHistoryPicksLatestNonCanceled() {
        setupTicket();
        setupJob();
        // 3 tasks for exec_order=1: old FAILED (id=301), CANCELED (id=302), new WAIT_EXEC (id=303)
        DmExecAutoTaskDO t1 = buildTask(301L, 1, AutoExecTaskStatus.FAILED);
        DmExecAutoTaskDO t2 = buildTask(302L, 1, AutoExecTaskStatus.CANCELED);
        DmExecAutoTaskDO t3 = buildTask(303L, 1, AutoExecTaskStatus.WAIT_EXEC);
        when(autoTaskMapper.queryListByJobId(JOB_ID, null)).thenReturn(List.of(t1, t2, t3));

        DmDbChangeStmtVersionDO v1 = buildStmtVersion(1, 1, "sql", "hash", StmtSource.INITIAL, null);
        when(stmtVersionMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(v1));
        when(eventMapper.queryByTicketId(TICKET_ID)).thenReturn(Collections.emptyList());

        GovStmtTimelineFO fo = new GovStmtTimelineFO();
        fo.setTicketId(TICKET_ID);

        StmtTimelineVO vo = service.stmtTimeline("puid", "uid", fo);

        // Should pick WAIT_EXEC (the latest non-CANCELED), not FAILED or CANCELED
        StmtTimelineVO.StmtGroup group = vo.getGroups().get(0);
        assertEquals("WAIT_EXEC", group.getCurrentStatus());
    }

    private void setupTicket() {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setApproBiz(ApprovalBiz.DM_CHANGE);
        ticket.setBizId(BIZ_ID);
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket);
    }

    private void setupJob() {
        DmExecAutoJobDO job = new DmExecAutoJobDO();
        job.setId(JOB_ID);
        job.setDependOnBizId(BIZ_ID);
        when(autoJobMapper.queryByDependOnBizId(BIZ_ID)).thenReturn(job);
    }

    private DmExecAutoTaskDO buildTask(long id, int execOrder, AutoExecTaskStatus status) {
        DmExecAutoTaskDO task = new DmExecAutoTaskDO();
        task.setId(id);
        task.setAutoExecJobId(JOB_ID);
        task.setExecOrder(execOrder);
        task.setStatus(status);
        return task;
    }

    private DmDbChangeStmtVersionDO buildStmtVersion(int stmtIndex, int version, String sql, String hash, StmtSource source, String failReason) {
        DmDbChangeStmtVersionDO stmt = new DmDbChangeStmtVersionDO();
        stmt.setTicketId(TICKET_ID);
        stmt.setStmtIndex(stmtIndex);
        stmt.setStmtVersion(version);
        stmt.setStmtText(sql);
        stmt.setStmtHash(hash);
        stmt.setSource(source.name());
        stmt.setFailReason(failReason);
        stmt.setOperatorUid("uid-001");
        return stmt;
    }
}
