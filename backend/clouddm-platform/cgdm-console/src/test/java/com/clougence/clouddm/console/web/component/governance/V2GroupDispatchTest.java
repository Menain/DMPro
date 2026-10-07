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
package com.clougence.clouddm.console.web.component.governance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import com.clougence.clouddm.api.console.autoexec.ErrorStrategy;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.service.govticket.GovTicketV2Service;
import com.clougence.clouddm.platform.dal.access.ExecutionDal;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoJobMapper;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoTaskMapper;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;

/**
 * B7 N1b (implement.md 单测矩阵「共享分发器」): the single-point decision order
 * <b>segments → flags → legacy</b> (design §2.3) — every main-chain dispatch site (auto-advance /
 * human confirm / SYSTEM confirm / External resubmit) converges here, so the order is pinned ONCE
 * at the shared method plus the equivalence proof rides on the frozen caller suites
 * (GovAutoAdvanceServiceTest 12 + ProdDmlConfirmV2DispatchTest 17 + GovAutoAdvanceGhostDispatch 6,
 * all green with ZERO change — the collaborators are passed THROUGH from the callers' own
 * fields, which is exactly what keeps those pins meaningful).
 * <p>
 * Discipline: JUnit4 + hand-written mocks + ArgumentCaptor; NO Spring.
 */
public class V2GroupDispatchTest {

    private TicketSegmentService   segmentService;
    private GovTicketV2Service     govTicketV2Service;
    private SchemaSnapshotService  schemaSnapshotService;
    private boolean                launcherCalled;
    private DmTicketDbStmtDO       launchedGroup;
    private String                 launchedBizId;
    private String                 launchedUid;

    private DmApprovalDO           ticket;

    private static final long TICKET_ID = 900L;

    private final V2GroupDispatch.GroupJobLauncher launcher = (group, jobBizId, uid) -> {
        launcherCalled = true;
        launchedGroup = group;
        launchedBizId = jobBizId;
        launchedUid = uid;
    };

    @Before
    public void setUp() {
        // DmTeamUtils static executionDal (required by nextExecJobBizId on the legacy branch)
        ExecutionDal staticExecDal = mock(ExecutionDal.class);
        DmExecAutoJobMapper staticJobMapper = mock(DmExecAutoJobMapper.class);
        DmExecAutoTaskMapper staticTaskMapper = mock(DmExecAutoTaskMapper.class);
        when(staticExecDal.autoJobMapper()).thenReturn(staticJobMapper);
        when(staticExecDal.autoTaskMapper()).thenReturn(staticTaskMapper);
        when(staticJobMapper.queryByBizId(anyString())).thenReturn(null);
        when(staticTaskMapper.queryByBizId(anyString())).thenReturn(null);
        org.springframework.test.util.ReflectionTestUtils.setField(
            com.clougence.clouddm.console.web.util.DmTeamUtils.class, "executionDal", staticExecDal);

        segmentService = mock(TicketSegmentService.class);
        govTicketV2Service = mock(GovTicketV2Service.class);
        schemaSnapshotService = mock(SchemaSnapshotService.class);
        launcherCalled = false;

        ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setBizId("biz-900");
        ticket.setOwnerUid("uid-submitter");
    }

    private static DmTicketDbStmtDO group(long id, String precheckResult) {
        DmTicketDbStmtDO group = new DmTicketDbStmtDO();
        group.setId(id);
        group.setTicketId(TICKET_ID);
        group.setExecStatus("PENDING");
        group.setPrecheckResult(precheckResult);
        return group;
    }

    private static ApprovalMO mo(Boolean... flags) {
        ApprovalMO mo = new ApprovalMO();
        mo.setGhostRouteFlags(List.of(flags));
        return mo;
    }

    private V2GroupDispatch.Outcome dispatch(DmTicketDbStmtDO group, int position, ApprovalMO mo) {
        return V2GroupDispatch.dispatch(ticket, group, position, mo,
            true, ErrorStrategy.NONE, "zh-CN", "SYSTEM", GovTicketV2Service.GHOST_VIA_AUTO_ROUTE,
            segmentService, govTicketV2Service, schemaSnapshotService, launcher);
    }

    // ==================== branch 1: segments win ====================

    @Test
    public void ghostSegmentPlan_orchestratorOwnsDispatch_flagsAndLauncherUntouched() {
        DmTicketDbStmtDO group = group(11L,
            "{\"segments\":[{\"segIdx\":1,\"type\":\"AUTOEXEC\",\"stmtRange\":[1,2]},"
                + "{\"segIdx\":2,\"type\":\"GHOST\",\"stmtIndex\":3}]}");

        V2GroupDispatch.Outcome outcome = dispatch(group, 0, mo(true));

        assertEquals(V2GroupDispatch.Path.SEGMENT, outcome.getPath());
        assertNull(outcome.getJobBizId());
        // the config projection rides through (the caller's transactional/errorStrategy/languageTag/operator)
        verify(segmentService).dispatchFirstSegment(same(ticket), same(group), eq(true),
            eq(ErrorStrategy.NONE), eq("zh-CN"), eq("SYSTEM"));
        verify(govTicketV2Service, never()).dispatchGhostGroup(any(), any(), anyString());
        verifyNoInteractions(schemaSnapshotService);
        assertFalse(launcherCalled);
    }

    @Test
    public void dmlBatchOnlyPlan_segmentBranch_theWidenedGatePin() {
        // B7: a DML_BATCH-only plan (NO ghost segment) must take the segments branch — the
        // isSegmentedPlan widening (hasRoutedSegment); without it the group would fall through
        // to flags/legacy and the big DML would execute directly (the R18 bypass)
        DmTicketDbStmtDO group = group(11L,
            "{\"segments\":[{\"segIdx\":1,\"type\":\"DML_BATCH\",\"stmtIndex\":1,\"batchKey\":{\"column\":\"id\",\"keyType\":\"INT\"}}]}");

        V2GroupDispatch.Outcome outcome = dispatch(group, 0, mo(true));

        assertEquals(V2GroupDispatch.Path.SEGMENT, outcome.getPath());
        verify(segmentService).dispatchFirstSegment(same(ticket), same(group), anyBoolean(), any(), anyString(), anyString());
    }

    @Test
    public void mixedGhostAndBatchPlan_segmentBranch() {
        DmTicketDbStmtDO group = group(11L,
            "{\"segments\":[{\"segIdx\":1,\"type\":\"GHOST\",\"stmtIndex\":1},"
                + "{\"segIdx\":2,\"type\":\"DML_BATCH\",\"stmtIndex\":2}]}");

        assertEquals(V2GroupDispatch.Path.SEGMENT, dispatch(group, 0, mo(true)).getPath());
        verify(segmentService).dispatchFirstSegment(any(), any(), anyBoolean(), any(), anyString(), anyString());
    }

    // ==================== branch 2: M1 flags shape ====================

    @Test
    public void flagsWithoutPlan_m1GhostDispatch_byteCompat() {
        DmTicketDbStmtDO group = group(11L, "{\"ghostRoutes\":[{\"stmtIndex\":1,\"route\":\"GHOST\"}]}");

        V2GroupDispatch.Outcome outcome = dispatch(group, 0, mo(true));

        assertEquals(V2GroupDispatch.Path.GHOST_FLAG, outcome.getPath());
        verify(govTicketV2Service).dispatchGhostGroup(same(ticket), same(group),
            eq(GovTicketV2Service.GHOST_VIA_AUTO_ROUTE));
        verifyNoInteractions(segmentService);
        assertFalse(launcherCalled);
    }

    @Test
    public void flagsLookup_isPositional_secondGroupReadsIndexOne() {
        DmTicketDbStmtDO g0 = group(10L, "{}");
        DmTicketDbStmtDO g1 = group(11L, "{\"ghostRoutes\":[{\"stmtIndex\":1,\"route\":\"GHOST\"}]}");
        ApprovalMO mo = mo(false, true); // only position 1 routed

        assertEquals(V2GroupDispatch.Path.LEGACY, dispatch(g0, 0, mo).getPath());
        assertEquals(V2GroupDispatch.Path.GHOST_FLAG, dispatch(g1, 1, mo).getPath());
        verify(govTicketV2Service).dispatchGhostGroup(same(ticket), same(g1), anyString());
    }

    @Test
    public void nullMo_flagsBranchNeverFires() {
        DmTicketDbStmtDO group = group(11L, "{}");

        assertEquals(V2GroupDispatch.Path.LEGACY, dispatch(group, 0, null).getPath());
        verify(govTicketV2Service, never()).dispatchGhostGroup(any(), any(), anyString());
    }

    @Test
    public void alertPassFlagNotSet_legacyBranch() {
        // ALERT_PASS marks never set the flag (they stay inside their AUTOEXEC segment)
        DmTicketDbStmtDO group = group(11L, "{\"ghostRoutes\":[{\"stmtIndex\":1,\"route\":\"ALERT_PASS\"}]}");

        assertEquals(V2GroupDispatch.Path.LEGACY, dispatch(group, 0, mo(false)).getPath());
    }

    // ==================== branch 3: legacy ====================

    @Test
    public void unmarkedGroup_legacyProjection_captureThenLaunch_freshBizId() {
        DmTicketDbStmtDO group = group(12L, "{\"checkStatus\":\"PASS\",\"changeType\":\"DML\"}");

        V2GroupDispatch.Outcome outcome = dispatch(group, 0, mo(false));

        assertEquals(V2GroupDispatch.Path.LEGACY, outcome.getPath());
        assertNotNull(outcome.getJobBizId());
        assertEquals(outcome.getJobBizId(), launchedBizId);
        // the Sub-task A order: snapshot BEFORE the launch (regression pin of the converged sites)
        InOrder inOrder = Mockito.inOrder(schemaSnapshotService);
        inOrder.verify(schemaSnapshotService).captureIfMissing(same(group), eq(TICKET_ID));
        assertTrue(launcherCalled);
        assertEquals(group, launchedGroup);
        assertEquals("SYSTEM", launchedUid);
        verifyNoInteractions(segmentService, govTicketV2Service);
    }

    @Test
    public void nullPrecheckResult_legacyBranch_neverThrows() {
        DmTicketDbStmtDO group = group(12L, null);

        assertEquals(V2GroupDispatch.Path.LEGACY, dispatch(group, 0, null).getPath());
        assertTrue(launcherCalled);
    }

    @Test
    public void corruptPrecheckResult_degradesToLegacy_neverThrows() {
        // TicketSegment.parsePlan is never-throw — a corrupt carrier must not wedge the dispatch
        DmTicketDbStmtDO group = group(12L, "{corrupt");

        assertEquals(V2GroupDispatch.Path.LEGACY, dispatch(group, 0, mo(false)).getPath());
        verifyNoInteractions(segmentService);
    }
}
