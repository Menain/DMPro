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

import java.util.List;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.console.web.component.approval.ApprovalStateService;
import com.clougence.clouddm.console.web.component.execute.AutoExecService;
import com.clougence.clouddm.console.web.component.governance.GovReturnNotifyService;
import com.clougence.clouddm.console.web.component.governance.PrecheckConclusion;
import com.clougence.clouddm.console.web.component.governance.ProdReleaseStateMachine;
import com.clougence.clouddm.console.web.component.governance.ReleaseSchemaDiffService;
import com.clougence.clouddm.console.web.component.governance.ReleaseSchemaPrecheckService;
import com.clougence.clouddm.console.web.model.fo.prodrelease.ProdReleaseReturnStmtFO;
import com.clougence.clouddm.console.web.util.DmTeamUtils;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.DbChangeEventDal;
import com.clougence.clouddm.platform.dal.access.DbPairDal;
import com.clougence.clouddm.platform.dal.access.DataSourceDal;
import com.clougence.clouddm.platform.dal.access.ExecutionDal;
import com.clougence.clouddm.platform.dal.access.ProdReleaseDal;
import com.clougence.clouddm.platform.dal.access.TicketDbStmtDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangeEventMapper;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoJobMapper;
import com.clougence.clouddm.platform.dal.mapper.govticket.DmTicketDbStmtMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseStmtMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseStmtSchemaMapper;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO;
import com.clougence.clouddm.platform.dal.model.dbchange.GovEventType;
import com.clougence.clouddm.platform.dal.model.execution.AutoExecJobStatus;
import com.clougence.clouddm.platform.dal.model.execution.DmExecAutoJobDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseStmtDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.ProdReleaseStatus;

/**
 * Tests for ProdReleaseServiceImpl.returnStmt (layer 2 release stmt return-for-revision):
 * - FAILED stmt return succeeds: six-step sequence (delete job, delete stmt, delete schema,
 *   source ticket REVISION + group FAILED, event, aggregation)
 * - SUCCESS stmt rejected
 * - Active job rejected
 * - Aggregation: remaining all SUCCESS -> DONE; still EXECUTING -> no-op
 * - Idempotent: same ticket multi-stmt return
 * - Event uses real uid (NOT SYSTEM)
 */
public class ProdReleaseReturnStmtTest {

    private ProdReleaseServiceImpl  service;
    private ProdReleaseDal           prodReleaseDal;
    private DmProdReleaseMapper      releaseMapper;
    private DmProdReleaseStmtMapper  stmtMapper;
    private DmProdReleaseStmtSchemaMapper schemaMapper;
    private TicketDbStmtDal          ticketDbStmtDal;
    private DmTicketDbStmtMapper     ticketStmtMapper;
    private ApprovalDal              approvalDal;
    private DmApprovalMapper         approvalMapper;
    private ApprovalStateService    approvalStateService;
    private AutoExecService          autoExecService;
    private DbChangeEventDal       dbChangeEventDal;
    private DmDbChangeEventMapper    eventMapper;
    private ExecutionDal            execDal;
    private DmExecAutoJobMapper      autoJobMapper;
    private ProdReleaseStateMachine  releaseStateMachine;

    private static final String PUID = "puid-001";
    private static final String UID  = "uid-001";

    @Before
    public void setUp() {
        service = new ProdReleaseServiceImpl();

        // Set up DmTeamUtils static executionDal
        com.clougence.clouddm.platform.dal.access.ExecutionDal staticExecDal
            = mock(com.clougence.clouddm.platform.dal.access.ExecutionDal.class);
        com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoJobMapper staticJobMapper
            = mock(com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoJobMapper.class);
        com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoTaskMapper staticTaskMapper
            = mock(com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoTaskMapper.class);
        when(staticExecDal.autoJobMapper()).thenReturn(staticJobMapper);
        when(staticExecDal.autoTaskMapper()).thenReturn(staticTaskMapper);
        when(staticJobMapper.queryByBizId(anyString())).thenReturn(null);
        when(staticTaskMapper.queryByBizId(anyString())).thenReturn(null);
        ReflectionTestUtils.setField(DmTeamUtils.class, "executionDal", staticExecDal);

        prodReleaseDal = mock(ProdReleaseDal.class);
        releaseMapper = mock(DmProdReleaseMapper.class);
        stmtMapper = mock(DmProdReleaseStmtMapper.class);
        schemaMapper = mock(DmProdReleaseStmtSchemaMapper.class);
        when(prodReleaseDal.releaseMapper()).thenReturn(releaseMapper);
        when(prodReleaseDal.stmtMapper()).thenReturn(stmtMapper);
        when(prodReleaseDal.schemaMapper()).thenReturn(schemaMapper);
        // ADR-013 M1: frozen segment manifest mapper (returnStmt cascade delete)
        when(prodReleaseDal.segmentMapper()).thenReturn(mock(
            com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseStmtSegmentMapper.class));

        ticketDbStmtDal = mock(TicketDbStmtDal.class);
        ticketStmtMapper = mock(DmTicketDbStmtMapper.class);
        when(ticketDbStmtDal.stmtMapper()).thenReturn(ticketStmtMapper);

        approvalDal = mock(ApprovalDal.class);
        approvalMapper = mock(DmApprovalMapper.class);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);

        approvalStateService = mock(ApprovalStateService.class);
        autoExecService = mock(AutoExecService.class);

        dbChangeEventDal = mock(DbChangeEventDal.class);
        eventMapper = mock(DmDbChangeEventMapper.class);
        when(dbChangeEventDal.eventMapper()).thenReturn(eventMapper);
        when(eventMapper.insert(any(DmDbChangeEventDO.class))).thenReturn(1);

        execDal = mock(ExecutionDal.class);
        autoJobMapper = mock(DmExecAutoJobMapper.class);
        when(execDal.autoJobMapper()).thenReturn(autoJobMapper);

        releaseStateMachine = mock(ProdReleaseStateMachine.class);

        // Mock DS / auth / precheck / diff
        DataSourceDal dsDal = mock(DataSourceDal.class);
        DmProdReleaseStmtSchemaMapper prodSchemaMapper = schemaMapper;
        ReleaseSchemaPrecheckService releaseSchemaPrecheckService = mock(ReleaseSchemaPrecheckService.class);
        when(releaseSchemaPrecheckService.precheck(any())).thenReturn(PrecheckConclusion.pass());
        ReleaseSchemaDiffService releaseSchemaDiffService = mock(ReleaseSchemaDiffService.class);
        when(releaseSchemaDiffService.diff(any())).thenReturn(PrecheckConclusion.pass());

        ReflectionTestUtils.setField(service, "prodReleaseDal", prodReleaseDal);
        ReflectionTestUtils.setField(service, "dbChangeEventDal", dbChangeEventDal);
        ReflectionTestUtils.setField(service, "ticketDbStmtDal", ticketDbStmtDal);
        ReflectionTestUtils.setField(service, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(service, "approvalStateService", approvalStateService);
        ReflectionTestUtils.setField(service, "autoExecService", autoExecService);
        ReflectionTestUtils.setField(service, "releaseStateMachine", releaseStateMachine);
        ReflectionTestUtils.setField(service, "execDal", execDal);
        ReflectionTestUtils.setField(service, "dsDal", dsDal);
        ReflectionTestUtils.setField(service, "releaseSchemaPrecheckService", releaseSchemaPrecheckService);
        ReflectionTestUtils.setField(service, "releaseSchemaDiffService", releaseSchemaDiffService);

        // v2: IM notification service (mocked — all tests set sourceTicket ownerUid=UID so notify is skipped)
        GovReturnNotifyService govReturnNotifyService = mock(GovReturnNotifyService.class);
        ReflectionTestUtils.setField(service, "govReturnNotifyService", govReturnNotifyService);
    }

    @Test
    public void returnStmt_failedStmt_executingRelease_succeeds() {
        long stmtId = 600L;
        long releaseId = 610L;
        long sourceStmtId = 200L;
        long sourceTicketId = 100L;

        DmProdReleaseStmtDO stmt = buildReleaseStmt(stmtId, releaseId, "FAILED", sourceStmtId, sourceTicketId);
        when(stmtMapper.queryById(stmtId)).thenReturn(stmt);

        DmProdReleaseDO release = buildRelease(releaseId, ProdReleaseStatus.EXECUTING);
        when(releaseMapper.selectByIdForUpdate(releaseId)).thenReturn(release);
        when(autoJobMapper.queryByDependOnReleaseStmtId(stmtId)).thenReturn(null);

        // Source ticket + stmt
        DmApprovalDO sourceTicket = new DmApprovalDO();
        sourceTicket.setId(sourceTicketId);
        sourceTicket.setOwnerUid(UID); // actor == owner → IM notify skipped
        sourceTicket.setTicketStatus(ApprovalStatus.FINISHED);
        when(approvalMapper.queryById(sourceTicketId)).thenReturn(sourceTicket);

        DmTicketDbStmtDO sourceStmt = new DmTicketDbStmtDO();
        sourceStmt.setId(sourceStmtId);
        sourceStmt.setTicketId(sourceTicketId);
        when(ticketStmtMapper.queryById(sourceStmtId)).thenReturn(sourceStmt);

        // Aggregation: remaining stmts (none or all SUCCESS) → DONE
        when(stmtMapper.queryByReleaseId(releaseId)).thenReturn(java.util.Collections.emptyList());

        ProdReleaseReturnStmtFO fo = new ProdReleaseReturnStmtFO();
        fo.setStmtId(stmtId);
        fo.setReason("fix SQL text");
        service.returnStmt(PUID, UID, fo);

        // Six-step sequence verification
        verify(stmtMapper).deleteByStmtId(stmtId);
        verify(schemaMapper).deleteByReleaseStmtId(stmtId);
        verify(ticketStmtMapper).updateExecStatus(eq(sourceStmtId), eq("FAILED"), contains("Returned from release"));
        verify(approvalStateService).updateApprovalStatus(eq(sourceTicketId), eq(ApprovalStatus.REVISION), anyString());
        verify(eventMapper).insert(argThat((DmDbChangeEventDO e) ->
            e != null && GovEventType.RELEASE_STMT_RETURNED.name().equals(e.getEventType())
                && UID.equals(e.getOperatorUid())
                && !"SYSTEM".equals(e.getOperatorUid())));
    }

    @Test
    public void returnStmt_successStmt_throws() {
        long stmtId = 601L;
        DmProdReleaseStmtDO stmt = buildReleaseStmt(stmtId, 611L, "SUCCESS", 201L, 101L);
        when(stmtMapper.queryById(stmtId)).thenReturn(stmt);

        DmProdReleaseDO release = buildRelease(611L, ProdReleaseStatus.EXECUTING);
        when(releaseMapper.selectByIdForUpdate(611L)).thenReturn(release);

        ProdReleaseReturnStmtFO fo = new ProdReleaseReturnStmtFO();
        fo.setStmtId(stmtId);
        try {
            service.returnStmt(PUID, UID, fo);
            fail("Expected ErrorMessageException");
        } catch (ErrorMessageException ex) {
            // ok
        }
        verify(stmtMapper, never()).deleteByStmtId(anyLong());
    }

    @Test
    public void returnStmt_executingStmt_throws() {
        long stmtId = 602L;
        DmProdReleaseStmtDO stmt = buildReleaseStmt(stmtId, 612L, "EXECUTING", 202L, 102L);
        when(stmtMapper.queryById(stmtId)).thenReturn(stmt);

        DmProdReleaseDO release = buildRelease(612L, ProdReleaseStatus.EXECUTING);
        when(releaseMapper.selectByIdForUpdate(612L)).thenReturn(release);

        ProdReleaseReturnStmtFO fo = new ProdReleaseReturnStmtFO();
        fo.setStmtId(stmtId);
        try {
            service.returnStmt(PUID, UID, fo);
            fail("Expected ErrorMessageException");
        } catch (ErrorMessageException ex) {
            // ok
        }
    }

    @Test
    public void returnStmt_activeJob_throws() {
        long stmtId = 603L;
        DmProdReleaseStmtDO stmt = buildReleaseStmt(stmtId, 613L, "EXECUTING", 203L, 103L);
        when(stmtMapper.queryById(stmtId)).thenReturn(stmt);

        DmProdReleaseDO release = buildRelease(613L, ProdReleaseStatus.EXECUTING);
        when(releaseMapper.selectByIdForUpdate(613L)).thenReturn(release);

        DmExecAutoJobDO activeJob = mock(DmExecAutoJobDO.class);
        when(activeJob.getStatus()).thenReturn(AutoExecJobStatus.EXECUTING);
        when(activeJob.getBizId()).thenReturn("job-biz-603");
        when(autoJobMapper.queryByDependOnReleaseStmtId(stmtId)).thenReturn(activeJob);

        ProdReleaseReturnStmtFO fo = new ProdReleaseReturnStmtFO();
        fo.setStmtId(stmtId);
        try {
            service.returnStmt(PUID, UID, fo);
            fail("Expected ErrorMessageException");
        } catch (ErrorMessageException ex) {
            // ok
        }
        verify(stmtMapper, never()).deleteByStmtId(anyLong());
    }

    @Test
    public void returnStmt_aggregation_remainingAllSuccess_dones() {
        long stmtId = 604L;
        long releaseId = 614L;
        DmProdReleaseStmtDO stmt = buildReleaseStmt(stmtId, releaseId, "FAILED", 204L, 104L);
        when(stmtMapper.queryById(stmtId)).thenReturn(stmt);

        DmProdReleaseDO release = buildRelease(releaseId, ProdReleaseStatus.EXECUTING);
        when(releaseMapper.selectByIdForUpdate(releaseId)).thenReturn(release);
        when(autoJobMapper.queryByDependOnReleaseStmtId(stmtId)).thenReturn(null);

        DmApprovalDO sourceTicket = new DmApprovalDO();
        sourceTicket.setId(104L);
        sourceTicket.setOwnerUid(UID); // actor == owner → IM notify skipped
        sourceTicket.setTicketStatus(ApprovalStatus.FINISHED);
        when(approvalMapper.queryById(104L)).thenReturn(sourceTicket);

        DmTicketDbStmtDO sourceStmt = new DmTicketDbStmtDO();
        sourceStmt.setId(204L);
        sourceStmt.setTicketId(104L);
        when(ticketStmtMapper.queryById(204L)).thenReturn(sourceStmt);

        // Remaining: one SUCCESS stmt
        DmProdReleaseStmtDO successStmt = buildReleaseStmt(700L, releaseId, "SUCCESS", 205L, 105L);
        when(stmtMapper.queryByReleaseId(releaseId)).thenReturn(List.of(successStmt));

        ProdReleaseReturnStmtFO fo = new ProdReleaseReturnStmtFO();
        fo.setStmtId(stmtId);
        service.returnStmt(PUID, UID, fo);

        // All terminal (SUCCESS only) → DONE
        verify(releaseStateMachine).transit(eq(releaseId),
            eq(Set.of(ProdReleaseStatus.EXECUTING)), eq(ProdReleaseStatus.DONE));
    }

    @Test
    public void returnStmt_aggregation_hasExecuting_noOp() {
        long stmtId = 605L;
        long releaseId = 615L;
        DmProdReleaseStmtDO stmt = buildReleaseStmt(stmtId, releaseId, "FAILED", 206L, 106L);
        when(stmtMapper.queryById(stmtId)).thenReturn(stmt);

        DmProdReleaseDO release = buildRelease(releaseId, ProdReleaseStatus.EXECUTING);
        when(releaseMapper.selectByIdForUpdate(releaseId)).thenReturn(release);
        when(autoJobMapper.queryByDependOnReleaseStmtId(stmtId)).thenReturn(null);

        DmApprovalDO sourceTicket = new DmApprovalDO();
        sourceTicket.setId(106L);
        sourceTicket.setOwnerUid(UID); // actor == owner → IM notify skipped
        sourceTicket.setTicketStatus(ApprovalStatus.FINISHED);
        when(approvalMapper.queryById(106L)).thenReturn(sourceTicket);

        DmTicketDbStmtDO sourceStmt = new DmTicketDbStmtDO();
        sourceStmt.setId(206L);
        sourceStmt.setTicketId(106L);
        when(ticketStmtMapper.queryById(206L)).thenReturn(sourceStmt);

        // Remaining: one EXECUTING stmt (not terminal)
        DmProdReleaseStmtDO executingStmt = buildReleaseStmt(701L, releaseId, "EXECUTING", 207L, 107L);
        when(stmtMapper.queryByReleaseId(releaseId)).thenReturn(List.of(executingStmt));

        ProdReleaseReturnStmtFO fo = new ProdReleaseReturnStmtFO();
        fo.setStmtId(stmtId);
        service.returnStmt(PUID, UID, fo);

        // Not all terminal → no transit
        verify(releaseStateMachine, never()).transit(anyLong(), anySet(), any());
    }

    @Test
    public void returnStmt_partialFailedRelease_remainingAllSuccess_dones() {
        long stmtId = 607L;
        long releaseId = 617L;
        DmProdReleaseStmtDO stmt = buildReleaseStmt(stmtId, releaseId, "FAILED", 209L, 109L);
        when(stmtMapper.queryById(stmtId)).thenReturn(stmt);

        // Release is PARTIAL_FAILED (not EXECUTING) — the critical edge case
        DmProdReleaseDO release = buildRelease(releaseId, ProdReleaseStatus.PARTIAL_FAILED);
        when(releaseMapper.selectByIdForUpdate(releaseId)).thenReturn(release);
        when(autoJobMapper.queryByDependOnReleaseStmtId(stmtId)).thenReturn(null);

        DmApprovalDO sourceTicket = new DmApprovalDO();
        sourceTicket.setId(109L);
        sourceTicket.setOwnerUid(UID); // actor == owner → IM notify skipped
        sourceTicket.setTicketStatus(ApprovalStatus.FINISHED);
        when(approvalMapper.queryById(109L)).thenReturn(sourceTicket);

        DmTicketDbStmtDO sourceStmt = new DmTicketDbStmtDO();
        sourceStmt.setId(209L);
        sourceStmt.setTicketId(109L);
        when(ticketStmtMapper.queryById(209L)).thenReturn(sourceStmt);

        // Remaining: one SUCCESS stmt
        DmProdReleaseStmtDO successStmt = buildReleaseStmt(702L, releaseId, "SUCCESS", 210L, 110L);
        when(stmtMapper.queryByReleaseId(releaseId)).thenReturn(List.of(successStmt));

        ProdReleaseReturnStmtFO fo = new ProdReleaseReturnStmtFO();
        fo.setStmtId(stmtId);
        service.returnStmt(PUID, UID, fo);

        // Verify: PARTIAL_FAILED -> EXECUTING transit (fix for aggregation bug), then EXECUTING -> DONE
        verify(releaseStateMachine).transit(eq(releaseId),
            eq(Set.of(ProdReleaseStatus.PARTIAL_FAILED)), eq(ProdReleaseStatus.EXECUTING));
        verify(releaseStateMachine).transit(eq(releaseId),
            eq(Set.of(ProdReleaseStatus.EXECUTING)), eq(ProdReleaseStatus.DONE));
    }

    @Test
    public void returnStmt_idempotent_secondReturnSameTicket() {
        long stmtId = 606L;
        long releaseId = 616L;
        DmProdReleaseStmtDO stmt = buildReleaseStmt(stmtId, releaseId, "FAILED", 208L, 108L);
        when(stmtMapper.queryById(stmtId)).thenReturn(stmt);

        DmProdReleaseDO release = buildRelease(releaseId, ProdReleaseStatus.EXECUTING);
        when(releaseMapper.selectByIdForUpdate(releaseId)).thenReturn(release);
        when(autoJobMapper.queryByDependOnReleaseStmtId(stmtId)).thenReturn(null);

        // Source ticket is ALREADY in REVISION (returned from a previous stmt)
        DmApprovalDO sourceTicket = new DmApprovalDO();
        sourceTicket.setId(108L);
        sourceTicket.setOwnerUid(UID); // actor == owner → IM notify skipped
        sourceTicket.setTicketStatus(ApprovalStatus.REVISION);
        when(approvalMapper.queryById(108L)).thenReturn(sourceTicket);

        DmTicketDbStmtDO sourceStmt = new DmTicketDbStmtDO();
        sourceStmt.setId(208L);
        sourceStmt.setTicketId(108L);
        when(ticketStmtMapper.queryById(208L)).thenReturn(sourceStmt);

        when(stmtMapper.queryByReleaseId(releaseId)).thenReturn(java.util.Collections.emptyList());

        ProdReleaseReturnStmtFO fo = new ProdReleaseReturnStmtFO();
        fo.setStmtId(stmtId);
        service.returnStmt(PUID, UID, fo);

        // Ticket stays at REVISION (no second updateApprovalStatus call for REVISION)
        verify(approvalStateService, never()).updateApprovalStatus(eq(108L), eq(ApprovalStatus.REVISION), any());
        // But group is still set to FAILED + exec_detail
        verify(ticketStmtMapper).updateExecStatus(eq(208L), eq("FAILED"), anyString());
    }

    // ==================== helpers ====================

    private DmProdReleaseDO buildRelease(long id, ProdReleaseStatus status) {
        DmProdReleaseDO release = new DmProdReleaseDO();
        release.setId(id);
        release.setReleaseNo("REL-" + id);
        release.setStatus(status.name());
        release.setPrimaryUid(PUID);
        release.setCreatorUid(UID);
        return release;
    }

    private DmProdReleaseStmtDO buildReleaseStmt(long id, long releaseId, String execStatus,
                                                 long sourceStmtId, long sourceTicketId) {
        DmProdReleaseStmtDO stmt = new DmProdReleaseStmtDO();
        stmt.setId(id);
        stmt.setReleaseId(releaseId);
        stmt.setProdDsId(10L);
        stmt.setProdDbName("prod_db");
        stmt.setSeq(1);
        stmt.setSqlContent("CREATE TABLE t(id int);");
        stmt.setHash("some-hash");
        stmt.setSourceStmtId(sourceStmtId);
        stmt.setSourceTicketId(sourceTicketId);
        stmt.setExecutionKey("exec-key-" + id);
        stmt.setExecStatus(execStatus);
        return stmt;
    }
}
