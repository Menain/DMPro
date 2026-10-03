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
package com.clougence.clouddm.console.web.service.govticket.impl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.model.vo.govticket.GovTicketV2GroupVO;
import com.clougence.clouddm.console.web.service.ghost.GhostService;
import com.clougence.clouddm.console.web.service.ghost.asyntask.GhostAsyncTaskConfig;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.TicketDbStmtDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.govticket.DmTicketDbStmtMapper;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.execution.AsyncTaskStatus;
import com.clougence.clouddm.platform.dal.model.execution.DmExecAsyncTaskDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;
import com.clougence.utils.JsonUtils;

/**
 * B6 M1 (Step 7d D0): the ADDITIVE ghost-task projection of {@code groupList} — ghost-routed
 * groups project the bound GHOST AsyncTask (sessionId / old-table facts / effective tool status)
 * for the ticket-detail panel; non-ghost groups and undispatched ghost groups stay null and the
 * projection never breaks the plain read endpoint. The base groupList shape is untouched.
 */
public class GhostGroupListTest {

    private GovTicketV2ServiceImpl service;
    private TicketDbStmtDal        ticketDbStmtDal;
    private DmTicketDbStmtMapper   stmtMapper;
    private ApprovalDal            approvalDal;
    private DmApprovalMapper       approvalMapper;
    private GhostService           ghostService;

    private static final String PUID            = "puid-001";
    private static final String UID             = "uid-001";
    private static final long   TICKET_ID       = 900L;
    private static final long   GHOST_GROUP_ID  = 100L;   // id-ASC position 0 → flags[0]
    private static final long   PLAIN_GROUP_ID  = 101L;   // id-ASC position 1 → flags[1]
    private static final long   TASK_ID         = 77L;
    private static final String SESSION         = "sess-ghost-1";

    @Before
    public void setUp() {
        service = new GovTicketV2ServiceImpl();

        ticketDbStmtDal = mock(TicketDbStmtDal.class);
        stmtMapper = mock(DmTicketDbStmtMapper.class);
        when(ticketDbStmtDal.stmtMapper()).thenReturn(stmtMapper);

        approvalDal = mock(ApprovalDal.class);
        approvalMapper = mock(DmApprovalMapper.class);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);

        ghostService = mock(GhostService.class);

        ReflectionTestUtils.setField(service, "ticketDbStmtDal", ticketDbStmtDal);
        ReflectionTestUtils.setField(service, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(service, "ghostService", ghostService);

        // default fixture: two groups, the FIRST one ghost-routed (positional flags contract)
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket(List.of(true, false)));
        when(stmtMapper.queryByTicketId(TICKET_ID))
            .thenReturn(List.of(group(GHOST_GROUP_ID), group(PLAIN_GROUP_ID)));
    }

    // ==================== ghost group WITH a dispatched task ====================

    @Test
    public void ghostGroup_withTask_projectsAllFields_plainGroupStaysNull() {
        when(ghostService.findTaskForTicketGroup(TICKET_ID, GHOST_GROUP_ID))
            .thenReturn(ghostTask(AsyncTaskStatus.RUNNING, "RUNNING", "t_big_20260928120000_del", Boolean.FALSE));

        List<GovTicketV2GroupVO> list = service.groupList(PUID, UID, TICKET_ID);

        assertEquals(2, list.size());
        GovTicketV2GroupVO ghostVo = list.get(0);
        assertEquals(Long.valueOf(GHOST_GROUP_ID), ghostVo.getGroupId());
        assertEquals(Long.valueOf(TASK_ID), ghostVo.getGhostTaskId());
        assertEquals(SESSION, ghostVo.getGhostSessionId());
        assertEquals("t_big_20260928120000_del", ghostVo.getGhostOldTableName());
        assertEquals(Boolean.FALSE, ghostVo.getGhostOldTableDropped());
        assertEquals(GhostService.STATUS_RUNNING, ghostVo.getGhostTaskStatus());
        // base projection untouched
        assertEquals("db-1", ghostVo.getDbName());

        GovTicketV2GroupVO plainVo = list.get(1);
        assertNull(plainVo.getGhostTaskId());
        assertNull(plainVo.getGhostSessionId());
        assertNull(plainVo.getGhostOldTableName());
        assertNull(plainVo.getGhostOldTableDropped());
        assertNull(plainVo.getGhostTaskStatus());
        // the positional pre-filter is real: the plain group never touches the GHOST task mapper
        verify(ghostService, never()).findTaskForTicketGroup(TICKET_ID, PLAIN_GROUP_ID);
    }

    @Test
    public void ghostGroup_taskWithoutOldTable_projectsSessionAndNullTableFacts() {
        // freshly dispatched task: no cut-over yet (oldTableName null), no status captured yet
        when(ghostService.findTaskForTicketGroup(TICKET_ID, GHOST_GROUP_ID))
            .thenReturn(ghostTask(AsyncTaskStatus.RUNNING, null, null, null));

        GovTicketV2GroupVO ghostVo = service.groupList(PUID, UID, TICKET_ID).get(0);

        assertEquals(Long.valueOf(TASK_ID), ghostVo.getGhostTaskId());
        assertEquals(SESSION, ghostVo.getGhostSessionId());
        assertNull(ghostVo.getGhostOldTableName());
        assertNull(ghostVo.getGhostOldTableDropped());
        assertNull("no captured lastStatus on a non-terminal task → null (frontend renders INIT)",
            ghostVo.getGhostTaskStatus());
    }

    // ==================== non-ghost / undispatched groups ====================

    @Test
    public void legacyTicket_withoutFlags_neverTouchesGhostMapper() {
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket(null));

        List<GovTicketV2GroupVO> list = service.groupList(PUID, UID, TICKET_ID);

        assertEquals(2, list.size());
        for (GovTicketV2GroupVO vo : list) {
            assertNull(vo.getGhostTaskId());
            assertNull(vo.getGhostSessionId());
            assertNull(vo.getGhostTaskStatus());
        }
        verify(ghostService, never()).findTaskForTicketGroup(anyLong(), anyLong());
    }

    @Test
    public void ghostGroup_taskMissing_nullProjection_baseFieldsIntact() {
        when(ghostService.findTaskForTicketGroup(TICKET_ID, GHOST_GROUP_ID)).thenReturn(null);

        GovTicketV2GroupVO ghostVo = service.groupList(PUID, UID, TICKET_ID).get(0);

        assertNull(ghostVo.getGhostTaskId());
        assertNull(ghostVo.getGhostSessionId());
        assertNull(ghostVo.getGhostOldTableName());
        assertNull(ghostVo.getGhostOldTableDropped());
        assertNull(ghostVo.getGhostTaskStatus());
        assertEquals("db-1", ghostVo.getDbName());
        assertEquals("EXECUTING", ghostVo.getExecStatus());
    }

    // ==================== B6 M4: execSegments passthrough ====================

    @Test
    public void execSegments_passthroughByteIdentical_plainGroupNull() {
        String runtime = "[{\"segIdx\":1,\"type\":\"AUTOEXEC\",\"status\":\"SUCCESS\",\"stmtRange\":[1,2]},"
            + "{\"segIdx\":2,\"type\":\"GHOST\",\"status\":\"EXECUTING\",\"stmtIndex\":3,"
            + "\"sessionId\":\"" + SESSION + "\",\"taskId\":" + TASK_ID + "}]";
        DmTicketDbStmtDO segmented = group(GHOST_GROUP_ID);
        segmented.setExecSegments(runtime);
        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(segmented, group(PLAIN_GROUP_ID)));
        when(ghostService.findTaskForTicketGroup(TICKET_ID, GHOST_GROUP_ID))
            .thenReturn(ghostTask(AsyncTaskStatus.RUNNING, "RUNNING", null, null));

        List<GovTicketV2GroupVO> list = service.groupList(PUID, UID, TICKET_ID);

        // verbatim column passthrough — no re-serialization, no trimming (the frontend parses it)
        assertEquals(runtime, list.get(0).getExecSegments());
        assertNull("pure-normal groups never carry a runtime", list.get(1).getExecSegments());
    }

    @Test
    public void execSegments_ungated_legacyTicketWithoutFlagsStillProjects() {
        // the passthrough is NOT gated on ghostRouteFlags (only the ghost-task projection is) —
        // the segment list renders from the runtime/plan even when the flag pre-filter skips
        String runtime = "[{\"segIdx\":1,\"type\":\"GHOST\",\"status\":\"PENDING\",\"stmtIndex\":1}]";
        DmTicketDbStmtDO segmented = group(GHOST_GROUP_ID);
        segmented.setExecSegments(runtime);
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket(null));
        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(segmented, group(PLAIN_GROUP_ID)));

        List<GovTicketV2GroupVO> list = service.groupList(PUID, UID, TICKET_ID);

        assertEquals(runtime, list.get(0).getExecSegments());
        assertNull(list.get(0).getGhostSessionId()); // the ghost projection stays flag-gated
        verify(ghostService, never()).findTaskForTicketGroup(anyLong(), anyLong());
    }

    // ==================== effective-status semantics ====================

    @Test
    public void terminalTaskStatus_winsOverStaleCapturedLastStatus() {
        // session-lost FAILED path (GhostAsyncTask R3 ruling): the task terminates WITHOUT a
        // final tool-state capture — the stale RUNNING lastStatus must not keep the UI polling
        when(ghostService.findTaskForTicketGroup(TICKET_ID, GHOST_GROUP_ID))
            .thenReturn(ghostTask(AsyncTaskStatus.FAILURE, "RUNNING", null, null));

        GovTicketV2GroupVO ghostVo = service.groupList(PUID, UID, TICKET_ID).get(0);
        assertEquals(GhostService.STATUS_FAILED, ghostVo.getGhostTaskStatus());
    }

    // ==================== never-throw projection ====================

    @Test
    public void projectionFailure_degradesToNullFields_listSurvives() {
        when(ghostService.findTaskForTicketGroup(TICKET_ID, GHOST_GROUP_ID))
            .thenThrow(new IllegalStateException("mapper down"));

        List<GovTicketV2GroupVO> list = service.groupList(PUID, UID, TICKET_ID);

        assertEquals(2, list.size());
        assertNull(list.get(0).getGhostTaskId());
        assertNull(list.get(0).getGhostSessionId());
        assertEquals("db-1", list.get(0).getDbName());
    }

    @Test
    public void unparsableConfigData_fallsBackToBizIdSession_noThrow() {
        DmExecAsyncTaskDO task = ghostTask(AsyncTaskStatus.COMPLETE, "RUNNING", null, null);
        task.setConfigData("{not-json");
        when(ghostService.findTaskForTicketGroup(TICKET_ID, GHOST_GROUP_ID)).thenReturn(task);

        GovTicketV2GroupVO ghostVo = service.groupList(PUID, UID, TICKET_ID).get(0);

        assertEquals(Long.valueOf(TASK_ID), ghostVo.getGhostTaskId());
        assertEquals("bizId = tool sessionId launch contract carries the projection",
            SESSION, ghostVo.getGhostSessionId());
        // cfg unparsable → the terminal AsyncTaskStatus still resolves the effective status
        assertEquals(GhostService.STATUS_COMPLETED, ghostVo.getGhostTaskStatus());
        assertNull(ghostVo.getGhostOldTableName());
    }

    // ==================== fixtures ====================

    private DmApprovalDO ticket(List<Boolean> ghostFlags) {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setBizId("biz-900");
        ticket.setPrimaryUid(PUID);
        ticket.setOwnerUid(UID);
        ApprovalMO mo = new ApprovalMO();
        mo.setTicketType("PRE_DDL");
        mo.setGhostRouteFlags(ghostFlags);
        ticket.setTicketInfo(JsonUtils.toJson(mo));
        return ticket;
    }

    private DmTicketDbStmtDO group(long groupId) {
        DmTicketDbStmtDO group = new DmTicketDbStmtDO();
        group.setId(groupId);
        group.setTicketId(TICKET_ID);
        group.setPairId(300L);
        group.setDsId(20L);
        group.setDbName("db-1");
        group.setSqlContent("ALTER TABLE t_big ADD COLUMN c1 INT");
        group.setExecStatus("EXECUTING");
        return group;
    }

    private static DmExecAsyncTaskDO ghostTask(AsyncTaskStatus status, String lastStatus,
                                               String oldTableName, Boolean oldTableDropped) {
        DmExecAsyncTaskDO task = new DmExecAsyncTaskDO();
        task.setId(TASK_ID);
        task.setBizId(SESSION);
        task.setBizType("GHOST");
        task.setStatus(status);
        GhostAsyncTaskConfig cfg = new GhostAsyncTaskConfig();
        cfg.setUserId(UID);
        cfg.setSessionId(SESSION);
        cfg.setTicketId(TICKET_ID);
        cfg.setGroupId(GHOST_GROUP_ID);
        cfg.setLastStatus(lastStatus);
        cfg.setOldTableName(oldTableName);
        cfg.setOldTableDropped(oldTableDropped);
        task.setConfigData(JsonUtils.toJson(cfg));
        return task;
    }
}
