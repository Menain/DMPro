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
package com.clougence.clouddm.console.web.component.execute;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.api.sidecar.autoexec.AutoExecRService;
import com.clougence.clouddm.console.web.component.approval.ApprovalStateService;
import com.clougence.clouddm.console.web.component.execute.impl.AutoExecServiceImpl;
import com.clougence.clouddm.console.web.component.governance.GuardConclusion;
import com.clougence.clouddm.console.web.service.approval.ApprovalControlService;
import com.clougence.clouddm.console.web.service.governance.GovExecutionGuardService;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.DataSourceDal;
import com.clougence.clouddm.platform.dal.access.ExecutionDal;
import com.clougence.clouddm.platform.dal.access.ObjectCacheDao;
import com.clougence.clouddm.platform.dal.access.SystemDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoJobMapper;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoTaskMapper;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.execution.DmExecAutoJobDO;

/**
 * B4 dual-worker + guard-must-pass tests for AutoExecServiceImpl.dispatchJob.
 * <p>
 * Verifies: (1) second worker skips entirely when claimJobForPackaging returns 0
 * (no guard check, no package creation, no dispatch); (2) first worker passes
 * through the gate-two guard before any execution, and on DENY deletes the job
 * + restores confirmation without dispatching.
 */
public class DispatchJobGuardTest {

    private AutoExecServiceImpl       service;
    private ExecutionDal             execDal;
    private DmExecAutoJobMapper      autoJobMapper;
    private DmExecAutoTaskMapper     autoTaskMapper;
    private ApprovalDal              approvalDal;
    private DmApprovalMapper         approvalMapper;
    private GovExecutionGuardService govExecutionGuardService;
    private ApprovalControlService   approvalControlService;
    private AutoExecRService         execRService;
    private ApprovalStateService      approvalStateService;

    private static final long     JOB_ID    = 50L;
    private static final String   BIZ_ID    = "ticket-biz-001";
    private static final String   PUID      = "puid-001";
    private static final long     TICKET_ID = 200L;

    @Before
    public void setUp() {
        service = new AutoExecServiceImpl();
        execDal = mock(ExecutionDal.class);
        autoJobMapper = mock(DmExecAutoJobMapper.class);
        autoTaskMapper = mock(DmExecAutoTaskMapper.class);
        approvalDal = mock(ApprovalDal.class);
        approvalMapper = mock(DmApprovalMapper.class);
        govExecutionGuardService = mock(GovExecutionGuardService.class);
        approvalControlService = mock(ApprovalControlService.class);
        execRService = mock(AutoExecRService.class);
        approvalStateService = mock(ApprovalStateService.class);

        when(execDal.autoJobMapper()).thenReturn(autoJobMapper);
        when(execDal.autoTaskMapper()).thenReturn(autoTaskMapper);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);

        ReflectionTestUtils.setField(service, "execDal", execDal);
        ReflectionTestUtils.setField(service, "systemDal", mock(SystemDal.class));
        ReflectionTestUtils.setField(service, "dsDal", mock(DataSourceDal.class));
        ReflectionTestUtils.setField(service, "cacheDao", mock(ObjectCacheDao.class));
        ReflectionTestUtils.setField(service, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(service, "govExecutionGuardService", govExecutionGuardService);
        ReflectionTestUtils.setField(service, "approvalControlService", approvalControlService);
        ReflectionTestUtils.setField(service, "execRService", execRService);
        ReflectionTestUtils.setField(service, "approvalStateService", approvalStateService);
    }

    @Test
    public void dispatchJob_secondWorkerClaimReturnsZero_skipsEntirely() {
        // Second worker: claimJobForPackaging returns 0 (another worker already claimed)
        when(autoJobMapper.claimJobForPackaging(JOB_ID)).thenReturn(0);

        service.dispatchJob(JOB_ID);

        // No guard check, no job query, no package creation, no dispatch
        verifyNoInteractions(govExecutionGuardService);
        verify(autoJobMapper, never()).queryById(anyLong());
        verifyNoInteractions(execRService);
        verifyNoInteractions(approvalStateService);
    }

    @Test
    public void dispatchJob_firstWorkerClaimGuardDeny_deletesJobAndRestores() {
        // First worker: claim succeeds (returns 1)
        when(autoJobMapper.claimJobForPackaging(JOB_ID)).thenReturn(1);

        // Guard job + ticket found
        DmExecAutoJobDO job = new DmExecAutoJobDO();
        job.setId(JOB_ID);
        job.setDependOnBizId(BIZ_ID);
        when(autoJobMapper.queryById(JOB_ID)).thenReturn(job);

        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setPrimaryUid(PUID);
        when(approvalMapper.queryByBizId(BIZ_ID)).thenReturn(ticket);

        // Guard returns DENY
        GuardConclusion deny = GuardConclusion.deny("hash mismatch");
        when(govExecutionGuardService.checkByJob(PUID, JOB_ID)).thenReturn(deny);

        // doDeleteJob: queryByIdForUpdate returns the job
        when(autoJobMapper.queryByIdForUpdate(JOB_ID)).thenReturn(job);

        service.dispatchJob(JOB_ID);

        // Guard was called (gate-two guard is mandatory before execution)
        verify(govExecutionGuardService).checkByJob(PUID, JOB_ID);

        // Job + tasks deleted (frees depend_on_biz_id UNIQUE for re-confirmation)
        verify(autoJobMapper).queryByIdForUpdate(JOB_ID);
        verify(autoTaskMapper).deleteByJobId(JOB_ID);
        verify(autoJobMapper).deleteById(JOB_ID);

        // Confirmation restored — ticket returned to WAIT_CONFIRM
        verify(approvalControlService).restoreExecutionConfirmationByGuard(TICKET_ID, "hash mismatch");

        // No execution dispatch happened
        verifyNoInteractions(execRService);
        verify(approvalStateService, never()).markExecutionDispatched(any());
    }
}
