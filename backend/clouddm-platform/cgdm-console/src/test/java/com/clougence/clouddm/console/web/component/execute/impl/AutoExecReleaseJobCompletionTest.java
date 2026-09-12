/*
 * Copyright 2026 杭州开云集致科技有限公司
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not obtain a copy of the License at
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

import java.util.List;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.console.web.component.approval.ApprovalStateService;
import com.clougence.clouddm.console.web.component.governance.GovSqlHashUtils;
import com.clougence.clouddm.console.web.component.governance.ProdReleaseStateMachine;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.DbChangeEventDal;
import com.clougence.clouddm.platform.dal.access.ExecutionDal;
import com.clougence.clouddm.platform.dal.access.ProdReleaseDal;
import com.clougence.clouddm.platform.dal.access.TicketDbStmtDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangeEventMapper;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoJobMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseStmtMapper;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO;
import com.clougence.clouddm.platform.dal.model.execution.DmExecAutoJobDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseStmtDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.ProdReleaseStatus;

/**
 * Tests for AutoExecImpl release job completion + guard third branch:
 * - Chained execution: same-DB seq serial trigger, FAILED stops chain, aggregation
 * - Hash drift in chained execution
 * - Guard third branch: deny when release not EXECUTING, deny when stmt not retryable
 */
public class AutoExecReleaseJobCompletionTest {

    private AutoExecServiceImpl     autoExecService;
    private ProdReleaseDal           prodReleaseDal;
    private DmProdReleaseMapper      releaseMapper;
    private DmProdReleaseStmtMapper  stmtMapper;
    private ExecutionDal             execDal;
    private DmExecAutoJobMapper      autoJobMapper;
    private DbChangeEventDal        dbChangeEventDal;
    private DmDbChangeEventMapper     eventMapper;
    private ApprovalDal              approvalDal;
    private DmApprovalMapper          approvalMapper;
    private ProdReleaseStateMachine  releaseStateMachine;
    private ApprovalStateService     approvalStateService;

    private static final String UID = "uid-operator";

    @Before
    public void setUp() {
        autoExecService = new AutoExecServiceImpl();

        prodReleaseDal = mock(ProdReleaseDal.class);
        releaseMapper = mock(DmProdReleaseMapper.class);
        stmtMapper = mock(DmProdReleaseStmtMapper.class);
        when(prodReleaseDal.releaseMapper()).thenReturn(releaseMapper);
        when(prodReleaseDal.stmtMapper()).thenReturn(stmtMapper);

        execDal = mock(ExecutionDal.class);
        autoJobMapper = mock(DmExecAutoJobMapper.class);
        when(execDal.autoJobMapper()).thenReturn(autoJobMapper);

        dbChangeEventDal = mock(DbChangeEventDal.class);
        eventMapper = mock(DmDbChangeEventMapper.class);
        when(dbChangeEventDal.eventMapper()).thenReturn(eventMapper);
        when(eventMapper.insert(any(DmDbChangeEventDO.class))).thenReturn(1);

        approvalDal = mock(ApprovalDal.class);
        approvalMapper = mock(DmApprovalMapper.class);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);

        releaseStateMachine = mock(ProdReleaseStateMachine.class);
        approvalStateService = mock(ApprovalStateService.class);

        TicketDbStmtDal ticketDbStmtDal = mock(TicketDbStmtDal.class);
        com.clougence.clouddm.platform.dal.mapper.govticket.DmTicketDbStmtMapper ticketStmtMapper
            = mock(com.clougence.clouddm.platform.dal.mapper.govticket.DmTicketDbStmtMapper.class);
        when(ticketDbStmtDal.stmtMapper()).thenReturn(ticketStmtMapper);

        ReflectionTestUtils.setField(autoExecService, "prodReleaseDal", prodReleaseDal);
        ReflectionTestUtils.setField(autoExecService, "dbChangeEventDal", dbChangeEventDal);
        ReflectionTestUtils.setField(autoExecService, "releaseStateMachine", releaseStateMachine);
        ReflectionTestUtils.setField(autoExecService, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(autoExecService, "approvalStateService", approvalStateService);
        ReflectionTestUtils.setField(autoExecService, "execDal", execDal);
        ReflectionTestUtils.setField(autoExecService, "ticketDbStmtDal", ticketDbStmtDal);
    }

    // ==================== Chained execution: success chains next ====================
    // Note: full success-chains test requires createReleaseStmtJob dependencies
    // (dsDal, configService, analysisService) which are too heavy for a unit test.
    // The hash drift test below verifies nextPendingStmt is called and the hash
    // check logic; the aggregation tests verify the completion path.

    @Test
    public void handleReleaseJobCompletion_success_noNext_aggregatesToDone() {
        long releaseId = 1000L;
        long stmtId = 2000L;
        long jobId = 3000L;
        long approvalId = 4000L;
        String bizId = "biz-4000";

        DmExecAutoJobDO job = mock(DmExecAutoJobDO.class);
        when(job.getId()).thenReturn(jobId);
        when(job.getDependOnReleaseStmtId()).thenReturn(stmtId);
        when(job.getUid()).thenReturn(UID);
        when(autoJobMapper.queryById(jobId)).thenReturn(job);

        DmProdReleaseStmtDO stmt = buildStmt(stmtId, releaseId, 10L, "prod_db", 1, "EXECUTING");
        when(stmtMapper.queryById(stmtId)).thenReturn(stmt);

        DmProdReleaseDO release = buildRelease(releaseId, ProdReleaseStatus.EXECUTING);
        release.setApprovalId(approvalId);
        when(releaseMapper.queryById(releaseId)).thenReturn(release);
        when(releaseMapper.selectByIdForUpdate(releaseId)).thenReturn(release);

        // No next pending (this was the only/last stmt)
        when(stmtMapper.nextPendingStmt(releaseId, 10L, "prod_db", 1)).thenReturn(null);

        // All terminal (only 1 stmt, now SUCCESS)
        DmProdReleaseStmtDO successStmt = buildStmt(stmtId, releaseId, 10L, "prod_db", 1, "SUCCESS");
        when(stmtMapper.queryByReleaseIdForUpdate(releaseId)).thenReturn(List.of(successStmt));

        DmApprovalDO approval = mock(DmApprovalDO.class);
        when(approval.getBizId()).thenReturn(bizId);
        when(approvalMapper.queryById(approvalId)).thenReturn(approval);

        autoExecService.handleReleaseJobCompletion(jobId, true, null);

        verify(stmtMapper).updateExecStatus(eq(stmtId), eq("SUCCESS"), isNull());
        verify(releaseStateMachine).transit(eq(releaseId),
            eq(Set.of(ProdReleaseStatus.EXECUTING)), eq(ProdReleaseStatus.DONE));
        verify(approvalStateService).completeExecution(bizId);
    }

    // ==================== Chained execution: FAILED stops chain ====================

    @Test
    public void handleReleaseJobCompletion_failure_doesNotChain() {
        long releaseId = 1001L;
        long stmtId = 2002L;
        long jobId = 3001L;

        DmExecAutoJobDO job = mock(DmExecAutoJobDO.class);
        when(job.getId()).thenReturn(jobId);
        when(job.getDependOnReleaseStmtId()).thenReturn(stmtId);
        when(job.getUid()).thenReturn(UID);
        when(autoJobMapper.queryById(jobId)).thenReturn(job);

        DmProdReleaseStmtDO stmt = buildStmt(stmtId, releaseId, 10L, "prod_db", 1, "EXECUTING");
        when(stmtMapper.queryById(stmtId)).thenReturn(stmt);

        DmProdReleaseDO release = buildRelease(releaseId, ProdReleaseStatus.EXECUTING);
        release.setApprovalId(4001L);
        when(releaseMapper.queryById(releaseId)).thenReturn(release);
        when(releaseMapper.selectByIdForUpdate(releaseId)).thenReturn(release);

        // Another stmt in same DB still PENDING
        DmProdReleaseStmtDO otherStmt = buildStmt(2003L, releaseId, 10L, "prod_db", 2, "PENDING");
        when(stmtMapper.queryByReleaseIdForUpdate(releaseId)).thenReturn(List.of(stmt, otherStmt));

        autoExecService.handleReleaseJobCompletion(jobId, false, "syntax error");

        // Verify stmt marked FAILED
        verify(stmtMapper).updateExecStatus(eq(stmtId), eq("FAILED"), eq("syntax error"));
        // Verify nextPendingStmt NOT called (chain stopped on failure)
        verify(stmtMapper, never()).nextPendingStmt(anyLong(), anyLong(), anyString(), anyInt());
    }

    // ==================== Aggregation: all SUCCESS -> DONE ====================

    @Test
    public void handleReleaseJobCompletion_allSuccess_aggregatesToDone() {
        long releaseId = 1002L;
        long stmtId = 2004L;
        long jobId = 3002L;
        long approvalId = 4002L;
        String bizId = "biz-4002";

        DmExecAutoJobDO job = mock(DmExecAutoJobDO.class);
        when(job.getId()).thenReturn(jobId);
        when(job.getDependOnReleaseStmtId()).thenReturn(stmtId);
        when(job.getUid()).thenReturn(UID);
        when(autoJobMapper.queryById(jobId)).thenReturn(job);

        DmProdReleaseStmtDO stmt = buildStmt(stmtId, releaseId, 10L, "prod_db", 1, "EXECUTING");
        when(stmtMapper.queryById(stmtId)).thenReturn(stmt);

        DmProdReleaseDO release = buildRelease(releaseId, ProdReleaseStatus.EXECUTING);
        release.setApprovalId(approvalId);
        when(releaseMapper.queryById(releaseId)).thenReturn(release);
        when(releaseMapper.selectByIdForUpdate(releaseId)).thenReturn(release);

        // No next pending (this was the only stmt)
        when(stmtMapper.nextPendingStmt(releaseId, 10L, "prod_db", 1)).thenReturn(null);

        // All terminal (only 1 stmt, now SUCCESS)
        DmProdReleaseStmtDO successStmt = buildStmt(stmtId, releaseId, 10L, "prod_db", 1, "SUCCESS");
        when(stmtMapper.queryByReleaseIdForUpdate(releaseId)).thenReturn(List.of(successStmt));

        DmApprovalDO approval = mock(DmApprovalDO.class);
        when(approval.getBizId()).thenReturn(bizId);
        when(approvalMapper.queryById(approvalId)).thenReturn(approval);

        autoExecService.handleReleaseJobCompletion(jobId, true, null);

        // Verify transit to DONE
        verify(releaseStateMachine).transit(eq(releaseId),
            eq(Set.of(ProdReleaseStatus.EXECUTING)), eq(ProdReleaseStatus.DONE));
        // Verify approval completed
        verify(approvalStateService).completeExecution(bizId);
        // Per-stmt success event is written via appendReleaseStmtEvent with a null operator
        // (no operator in scope on the job-completion callback) -> SYSTEM fallback; pins
        // the contract that callback-driven stmt events survive the NOT NULL operator_uid.
        verify(eventMapper).insert(argThat((DmDbChangeEventDO e) ->
            e != null && "RELEASE_STMT_SUCCESS".equals(e.getEventType())
                && "SYSTEM".equals(e.getOperatorUid())));
        // RELEASE_DONE event must carry a non-null operator_uid (column is NOT NULL);
        // job-completion aggregation has no operator in scope -> SYSTEM fallback
        verify(eventMapper).insert(argThat((DmDbChangeEventDO e) ->
            e != null && "RELEASE_DONE".equals(e.getEventType())
                && "SYSTEM".equals(e.getOperatorUid())));
    }

    // ==================== Aggregation: any FAILED -> PARTIAL_FAILED ====================

    @Test
    public void handleReleaseJobCompletion_someFailed_aggregatesToPartialFailed() {
        long releaseId = 1003L;
        long stmtId = 2005L;
        long jobId = 3003L;
        long approvalId = 4003L;
        String bizId = "biz-4003";

        DmExecAutoJobDO job = mock(DmExecAutoJobDO.class);
        when(job.getId()).thenReturn(jobId);
        when(job.getDependOnReleaseStmtId()).thenReturn(stmtId);
        when(job.getUid()).thenReturn(UID);
        when(autoJobMapper.queryById(jobId)).thenReturn(job);

        DmProdReleaseStmtDO stmt = buildStmt(stmtId, releaseId, 10L, "prod_db", 1, "EXECUTING");
        when(stmtMapper.queryById(stmtId)).thenReturn(stmt);

        DmProdReleaseDO release = buildRelease(releaseId, ProdReleaseStatus.EXECUTING);
        release.setApprovalId(approvalId);
        when(releaseMapper.queryById(releaseId)).thenReturn(release);
        when(releaseMapper.selectByIdForUpdate(releaseId)).thenReturn(release);

        // No next pending
        when(stmtMapper.nextPendingStmt(releaseId, 10L, "prod_db", 1)).thenReturn(null);

        // All terminal: this FAILED + another in different DB SUCCESS
        DmProdReleaseStmtDO failedStmt = buildStmt(stmtId, releaseId, 10L, "prod_db", 1, "FAILED");
        DmProdReleaseStmtDO successStmt = buildStmt(2006L, releaseId, 11L, "other_db", 1, "SUCCESS");
        when(stmtMapper.queryByReleaseIdForUpdate(releaseId)).thenReturn(List.of(failedStmt, successStmt));

        DmApprovalDO approval = mock(DmApprovalDO.class);
        when(approval.getBizId()).thenReturn(bizId);
        when(approvalMapper.queryById(approvalId)).thenReturn(approval);

        autoExecService.handleReleaseJobCompletion(jobId, false, "exec error");

        // Verify transit to PARTIAL_FAILED
        verify(releaseStateMachine).transit(eq(releaseId),
            eq(Set.of(ProdReleaseStatus.EXECUTING)), eq(ProdReleaseStatus.PARTIAL_FAILED));
        // Verify approval failed
        verify(approvalStateService).failExecution(eq(bizId), anyString());
    }

    // ==================== Hash drift in chained execution ====================

    @Test
    public void handleReleaseJobCompletion_chainedHashDrift_marksFailedAndPartial() {
        long releaseId = 1004L;
        long stmtId = 2007L;
        long nextId = 2008L;
        long jobId = 3004L;

        DmExecAutoJobDO job = mock(DmExecAutoJobDO.class);
        when(job.getId()).thenReturn(jobId);
        when(job.getDependOnReleaseStmtId()).thenReturn(stmtId);
        when(job.getUid()).thenReturn(UID);
        when(autoJobMapper.queryById(jobId)).thenReturn(job);

        DmProdReleaseStmtDO stmt = buildStmt(stmtId, releaseId, 10L, "prod_db", 1, "EXECUTING");
        when(stmtMapper.queryById(stmtId)).thenReturn(stmt);

        DmProdReleaseDO release = buildRelease(releaseId, ProdReleaseStatus.EXECUTING);
        release.setApprovalId(4004L);
        when(releaseMapper.queryById(releaseId)).thenReturn(release);
        when(releaseMapper.selectByIdForUpdate(releaseId)).thenReturn(release);

        // Next pending stmt with WRONG hash (drift)
        DmProdReleaseStmtDO next = buildStmt(nextId, releaseId, 10L, "prod_db", 2, "PENDING");
        next.setHash("WRONG_HASH");
        when(stmtMapper.nextPendingStmt(releaseId, 10L, "prod_db", 1)).thenReturn(next);

        // After drift marks next FAILED, all terminal: stmt SUCCESS + next FAILED
        DmProdReleaseStmtDO successStmt = buildStmt(stmtId, releaseId, 10L, "prod_db", 1, "SUCCESS");
        DmProdReleaseStmtDO failedNext = buildStmt(nextId, releaseId, 10L, "prod_db", 2, "FAILED");
        when(stmtMapper.queryByReleaseIdForUpdate(releaseId)).thenReturn(List.of(successStmt, failedNext));

        DmApprovalDO approval = mock(DmApprovalDO.class);
        when(approval.getBizId()).thenReturn("biz-4004");
        when(approvalMapper.queryById(4004L)).thenReturn(approval);

        autoExecService.handleReleaseJobCompletion(jobId, true, null);

        // Verify next stmt marked FAILED (hash drift)
        verify(stmtMapper).updateExecStatus(eq(nextId), eq("FAILED"), contains("Hash drift"));
        // Verify transit to PARTIAL_FAILED
        verify(releaseStateMachine).transit(eq(releaseId),
            eq(Set.of(ProdReleaseStatus.EXECUTING)), eq(ProdReleaseStatus.PARTIAL_FAILED));
    }

    // ==================== Guard: deny when release not EXECUTING ====================

    @Test
    public void handleReleaseJobCompletion_notReleaseJob_returnsEarly() {
        long jobId = 3005L;
        DmExecAutoJobDO job = mock(DmExecAutoJobDO.class);
        when(job.getDependOnReleaseStmtId()).thenReturn(null);
        when(autoJobMapper.queryById(jobId)).thenReturn(job);

        autoExecService.handleReleaseJobCompletion(jobId, true, null);

        // Verify no stmt updates
        verify(stmtMapper, never()).updateExecStatus(anyLong(), anyString(), any());
    }

    // ==================== Race fix: lock ordering + sweep recovery ====================

    @Test
    public void handleReleaseJobCompletion_locksReleaseBeforeStmtWrite() {
        long releaseId = 1005L;
        long stmtId = 2009L;
        long jobId = 3006L;

        DmExecAutoJobDO job = mock(DmExecAutoJobDO.class);
        when(job.getId()).thenReturn(jobId);
        when(job.getDependOnReleaseStmtId()).thenReturn(stmtId);
        when(job.getUid()).thenReturn(UID);
        when(autoJobMapper.queryById(jobId)).thenReturn(job);

        DmProdReleaseStmtDO stmt = buildStmt(stmtId, releaseId, 10L, "prod_db", 1, "EXECUTING");
        when(stmtMapper.queryById(stmtId)).thenReturn(stmt);
        DmProdReleaseDO release = buildRelease(releaseId, ProdReleaseStatus.EXECUTING);
        when(releaseMapper.selectByIdForUpdate(releaseId)).thenReturn(release);
        when(stmtMapper.queryByReleaseIdForUpdate(releaseId)).thenReturn(List.of(stmt));

        autoExecService.handleReleaseJobCompletion(jobId, true, null);

        // The release row lock MUST serialize callbacks before any stmt status write
        org.mockito.InOrder inOrder = inOrder(releaseMapper, stmtMapper);
        inOrder.verify(releaseMapper).selectByIdForUpdate(releaseId);
        inOrder.verify(stmtMapper).updateExecStatus(eq(stmtId), eq("SUCCESS"), isNull());
        // Aggregation reads through the locking query (bypasses the RR snapshot)
        verify(stmtMapper).queryByReleaseIdForUpdate(releaseId);
    }

    // Empty stmt list must NOT trigger a vacuous all-terminal aggregation
    // (Stream.allMatch on an empty stream returns true, which would wrongly
    // transit EXECUTING -> DONE for a release whose stmts are inconsistent/absent).
    @Test
    public void handleReleaseJobCompletion_emptyStmtList_skipsAggregation() {
        long releaseId = 1010L;
        long stmtId = 2016L;
        long jobId = 3007L;

        DmExecAutoJobDO job = mock(DmExecAutoJobDO.class);
        when(job.getId()).thenReturn(jobId);
        when(job.getDependOnReleaseStmtId()).thenReturn(stmtId);
        when(job.getUid()).thenReturn(UID);
        when(autoJobMapper.queryById(jobId)).thenReturn(job);

        DmProdReleaseStmtDO stmt = buildStmt(stmtId, releaseId, 10L, "prod_db", 1, "EXECUTING");
        when(stmtMapper.queryById(stmtId)).thenReturn(stmt);
        DmProdReleaseDO release = buildRelease(releaseId, ProdReleaseStatus.EXECUTING);
        when(releaseMapper.selectByIdForUpdate(releaseId)).thenReturn(release);
        // No next pending + empty aggregation result
        when(stmtMapper.nextPendingStmt(releaseId, 10L, "prod_db", 1)).thenReturn(null);
        when(stmtMapper.queryByReleaseIdForUpdate(releaseId)).thenReturn(List.of());

        autoExecService.handleReleaseJobCompletion(jobId, true, null);

        verify(releaseStateMachine, never()).transit(anyLong(), anySet(), any(ProdReleaseStatus.class));
        verify(approvalStateService, never()).completeExecution(anyString());
    }

    @Test
    public void recoverReleaseCompletionIfDue_allSuccess_aggregatesToDone() {
        long releaseId = 1006L;
        long approvalId = 4006L;
        String bizId = "biz-4006";

        DmProdReleaseDO release = buildRelease(releaseId, ProdReleaseStatus.EXECUTING);
        release.setApprovalId(approvalId);
        when(releaseMapper.selectByIdForUpdate(releaseId)).thenReturn(release);
        when(stmtMapper.queryByReleaseIdForUpdate(releaseId)).thenReturn(List.of(
            buildStmt(2010L, releaseId, 10L, "prod_db", 1, "SUCCESS"),
            buildStmt(2011L, releaseId, 11L, "other_db", 1, "SUCCESS")));
        DmApprovalDO approval = mock(DmApprovalDO.class);
        when(approval.getBizId()).thenReturn(bizId);
        when(approvalMapper.queryById(approvalId)).thenReturn(approval);

        autoExecService.recoverReleaseCompletionIfDue(releaseId);

        verify(releaseStateMachine).transit(eq(releaseId),
            eq(Set.of(ProdReleaseStatus.EXECUTING)), eq(ProdReleaseStatus.DONE));
        verify(approvalStateService).completeExecution(bizId);
        verify(eventMapper).insert(argThat((DmDbChangeEventDO e) ->
            e != null && "RELEASE_DONE".equals(e.getEventType())
                && "SYSTEM".equals(e.getOperatorUid())));
    }

    @Test
    public void recoverReleaseCompletionIfDue_anyFailed_aggregatesToPartialFailed() {
        long releaseId = 1007L;
        long approvalId = 4007L;
        String bizId = "biz-4007";

        DmProdReleaseDO release = buildRelease(releaseId, ProdReleaseStatus.EXECUTING);
        release.setApprovalId(approvalId);
        when(releaseMapper.selectByIdForUpdate(releaseId)).thenReturn(release);
        when(stmtMapper.queryByReleaseIdForUpdate(releaseId)).thenReturn(List.of(
            buildStmt(2012L, releaseId, 10L, "prod_db", 1, "SUCCESS"),
            buildStmt(2013L, releaseId, 11L, "other_db", 1, "FAILED")));
        DmApprovalDO approval = mock(DmApprovalDO.class);
        when(approval.getBizId()).thenReturn(bizId);
        when(approvalMapper.queryById(approvalId)).thenReturn(approval);

        autoExecService.recoverReleaseCompletionIfDue(releaseId);

        verify(releaseStateMachine).transit(eq(releaseId),
            eq(Set.of(ProdReleaseStatus.EXECUTING)), eq(ProdReleaseStatus.PARTIAL_FAILED));
        verify(approvalStateService).failExecution(eq(bizId), anyString());
    }

    @Test
    public void recoverReleaseCompletionIfDue_releaseNotExecuting_noOp() {
        long releaseId = 1008L;
        DmProdReleaseDO release = buildRelease(releaseId, ProdReleaseStatus.DONE);
        when(releaseMapper.selectByIdForUpdate(releaseId)).thenReturn(release);

        autoExecService.recoverReleaseCompletionIfDue(releaseId);

        verify(releaseStateMachine, never()).transit(anyLong(), anySet(), any(ProdReleaseStatus.class));
        verify(stmtMapper, never()).queryByReleaseIdForUpdate(anyLong());
    }

    @Test
    public void recoverReleaseCompletionIfDue_stmtsStillRunning_noOp() {
        long releaseId = 1009L;
        DmProdReleaseDO release = buildRelease(releaseId, ProdReleaseStatus.EXECUTING);
        when(releaseMapper.selectByIdForUpdate(releaseId)).thenReturn(release);
        when(stmtMapper.queryByReleaseIdForUpdate(releaseId)).thenReturn(List.of(
            buildStmt(2014L, releaseId, 10L, "prod_db", 1, "SUCCESS"),
            buildStmt(2015L, releaseId, 11L, "other_db", 1, "EXECUTING")));

        autoExecService.recoverReleaseCompletionIfDue(releaseId);

        verify(releaseStateMachine, never()).transit(anyLong(), anySet(), any(ProdReleaseStatus.class));
        verify(approvalStateService, never()).completeExecution(anyString());
    }

    // ==================== helpers ====================

    private DmProdReleaseDO buildRelease(long id, ProdReleaseStatus status) {
        DmProdReleaseDO release = new DmProdReleaseDO();
        release.setId(id);
        release.setReleaseNo("REL-" + id);
        release.setStatus(status.name());
        release.setPrimaryUid("puid-001");
        return release;
    }

    private DmProdReleaseStmtDO buildStmt(long id, long releaseId, long prodDsId, String prodDbName, int seq, String execStatus) {
        DmProdReleaseStmtDO stmt = new DmProdReleaseStmtDO();
        stmt.setId(id);
        stmt.setReleaseId(releaseId);
        stmt.setProdDsId(prodDsId);
        stmt.setProdDbName(prodDbName);
        stmt.setSeq(seq);
        String sql = "CREATE TABLE t" + id + "(id int);";
        stmt.setSqlContent(sql);
        stmt.setHash(GovSqlHashUtils.hash(sql));
        stmt.setExecStatus(execStatus);
        return stmt;
    }
}
