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
package com.clougence.clouddm.console.web.service.approval;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.console.web.component.execute.AutoExecService;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.model.fo.ticket.DmQueryAutoExecFO;
import com.clougence.clouddm.console.web.service.governance.GovExecutionGuardService;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.AuthDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.auth.DmAuthRoleMapper;
import com.clougence.clouddm.platform.dal.mapper.auth.DmAuthUserMapper;
import com.clougence.clouddm.platform.dal.model.auth.AccountType;
import com.clougence.clouddm.platform.dal.model.auth.DmAuthUserDO;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.utils.JsonUtils;

/**
 * skipTask / canceledSkipTask wiring test (touchpoint #5).
 * <p>
 * P5: the legacy {@code assertNotGovernanceProd} guard is now a no-op (legacy PROD governance
 * tickets no longer exist). The guard is mocked to throw here so this test still pins the
 * <b>wiring</b> — the guard is called in the correct position (after ownership check, before
 * engine delegation) for both {@code skipTask} and {@code canceledSkipTask}.
 */
public class ApprovalControlSkipContinueTest {

    private ApprovalControlServiceImpl service;

    private ApprovalDal           approvalDal;
    private DmApprovalMapper      approvalMapper;
    private AuthDal               authDal;
    private DmAuthUserMapper      userMapper;
    private DmAuthRoleMapper      roleMapper;
    private GovExecutionGuardService govExecutionGuardService;
    private AutoExecService       autoExecService;

    private static final String PUID     = "puid-001";
    private static final String UID      = "uid-001";
    private static final long   TICKET_ID = 100L;
    private static final long   TASK_ID   = 200L;
    private static final String BIZ_ID    = "biz-001";

    @Before
    public void setUp() {
        approvalDal = mock(ApprovalDal.class);
        approvalMapper = mock(DmApprovalMapper.class);
        authDal = mock(AuthDal.class);
        userMapper = mock(DmAuthUserMapper.class);
        roleMapper = mock(DmAuthRoleMapper.class);
        govExecutionGuardService = mock(GovExecutionGuardService.class);
        autoExecService = mock(AutoExecService.class);

        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);
        when(authDal.userMapper()).thenReturn(userMapper);
        when(authDal.roleMapper()).thenReturn(roleMapper);

        service = new ApprovalControlServiceImpl();
        ReflectionTestUtils.setField(service, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(service, "authDal", authDal);
        ReflectionTestUtils.setField(service, "govExecutionGuardService", govExecutionGuardService);
        ReflectionTestUtils.setField(service, "autoExecService", autoExecService);

        // checkJobOperationEnable: primary account short-circuits to true
        DmAuthUserDO user = new DmAuthUserDO();
        user.setUid(UID);
        user.setAccountType(AccountType.PRIMARY_ACCOUNT);
        when(userMapper.queryByUid(UID)).thenReturn(user);
        // roleMapper.selectById is called before the PRIMARY_ACCOUNT short-circuit;
        // returning a minimal role avoids NPE on the unmocked roleMapper path
        com.clougence.clouddm.platform.dal.model.auth.DmAuthRoleDO role
            = new com.clougence.clouddm.platform.dal.model.auth.DmAuthRoleDO();
        role.setRoleAuthLabels(java.util.List.of());
        when(roleMapper.selectById(any())).thenReturn(role);
    }

    // ======= skipTask =======

    @Test(expected = ErrorMessageException.class)
    public void skipTask_prodGovernanceTicket_throwsBeforeEngine() {
        DmApprovalDO ticket = buildProdGovTicket();
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket);
        doThrow(new ErrorMessageException("PROD governance tickets do not allow skip/continue"))
            .when(govExecutionGuardService).assertNotGovernanceProd(ticket);

        DmQueryAutoExecFO fo = buildFO();
        service.skipTask(PUID, UID, fo);

        verify(autoExecService, never()).skipTask(any(), anyLong());
    }

    @Test
    public void skipTask_nonGovernanceTicket_delegatesToEngine() {
        DmApprovalDO ticket = buildNonGovTicket();
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket);
        // assertNotGovernanceProd is a no-op (no throw)

        DmQueryAutoExecFO fo = buildFO();
        service.skipTask(PUID, UID, fo);

        verify(govExecutionGuardService).assertNotGovernanceProd(ticket);
        verify(autoExecService).skipTask(BIZ_ID, TASK_ID);
    }

    // ======= canceledSkipTask (continueTask) =======

    @Test(expected = ErrorMessageException.class)
    public void canceledSkipTask_prodGovernanceTicket_throwsBeforeEngine() {
        DmApprovalDO ticket = buildProdGovTicket();
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket);
        doThrow(new ErrorMessageException("PROD governance tickets do not allow skip/continue"))
            .when(govExecutionGuardService).assertNotGovernanceProd(ticket);

        DmQueryAutoExecFO fo = buildFO();
        service.canceledSkipTask(PUID, UID, fo);

        verify(autoExecService, never()).continueTask(any(), anyLong());
    }

    @Test
    public void canceledSkipTask_nonGovernanceTicket_delegatesToEngine() {
        DmApprovalDO ticket = buildNonGovTicket();
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket);

        DmQueryAutoExecFO fo = buildFO();
        service.canceledSkipTask(PUID, UID, fo);

        verify(govExecutionGuardService).assertNotGovernanceProd(ticket);
        verify(autoExecService).continueTask(BIZ_ID, TASK_ID);
    }

    // ======= helpers =======

    private DmQueryAutoExecFO buildFO() {
        DmQueryAutoExecFO fo = new DmQueryAutoExecFO();
        fo.setTicketId(TICKET_ID);
        fo.setTaskId(TASK_ID);
        return fo;
    }

    private DmApprovalDO buildProdGovTicket() {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setBizId(BIZ_ID);
        ticket.setOwnerUid(UID);
        ticket.setPrimaryUid(PUID);
        ApprovalMO mo = new ApprovalMO();
        // P5: legacy PROD governance (govRole/promotionId/revisionId/logicalDbId) fields removed.
        // The guard is mocked to throw here, so the ticket content is irrelevant — this pins the wiring
        // (assertNotGovernanceProd called before engine delegation) for both skipTask and canceledSkipTask.
        ticket.setTicketInfo(JsonUtils.toJson(mo));
        return ticket;
    }

    private DmApprovalDO buildNonGovTicket() {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setBizId(BIZ_ID);
        ticket.setOwnerUid(UID);
        ticket.setPrimaryUid(PUID);
        return ticket;
    }
}
