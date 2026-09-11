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

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.console.web.component.approval.handler.ChangeApprovalHandler;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.AuthDal;
import com.clougence.clouddm.platform.dal.access.ChangeFlowDal;
import com.clougence.clouddm.platform.dal.access.ExecutionDal;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.sdk.approval.form.ChangeForm;
import com.clougence.utils.JsonUtils;

/**
 * Tests for the v2 branch added to {@link ChangeApprovalHandler#convertToChangeForm} (P4).
 * <p>
 * Verifies the three-branch mutual exclusion:
 * <ol>
 * <li>v2 ticket (ticketType != null) → GovTicketV2FormAssembler — new branch</li>
 * <li>old governance (govRole != null) → GovChangeFormAssembler — unchanged</li>
 * <li>CI/CD (neither) → original logic — unchanged</li>
 * </ol>
 * Branches are mutually exclusive because v2 tickets set ticketType but not govRole,
 * old governance tickets set govRole but not ticketType, and CI/CD tickets have neither.
 */
public class ConvertToChangeFormV2BranchTest {

    private ChangeApprovalHandler      handler;
    private GovChangeFormAssembler     govChangeFormAssembler;
    private GovTicketV2FormAssembler   govTicketV2FormAssembler;
    private ChangeFlowDal              changeFlowDal;
    private AuthDal                    authDal;

    private static final long   TICKET_ID = 100L;

    @Before
    public void setUp() {
        handler = new ChangeApprovalHandler();
        govChangeFormAssembler = mock(GovChangeFormAssembler.class);
        govTicketV2FormAssembler = mock(GovTicketV2FormAssembler.class);
        changeFlowDal = mock(ChangeFlowDal.class);
        authDal = mock(AuthDal.class);
        ExecutionDal execDal = mock(ExecutionDal.class);
        ApprovalDal approvalDal = mock(ApprovalDal.class);
        com.clougence.clouddm.console.web.component.approval.ApprovalStateService approvalStateService
            = mock(com.clougence.clouddm.console.web.component.approval.ApprovalStateService.class);
        com.clougence.clouddm.console.web.service.cicd.ChangeCascadeService cascadeService
            = mock(com.clougence.clouddm.console.web.service.cicd.ChangeCascadeService.class);

        ReflectionTestUtils.setField(handler, "changeFlowDal", changeFlowDal);
        ReflectionTestUtils.setField(handler, "authDal", authDal);
        ReflectionTestUtils.setField(handler, "execDal", execDal);
        ReflectionTestUtils.setField(handler, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(handler, "approvalStateService", approvalStateService);
        ReflectionTestUtils.setField(handler, "changeCascadeService", cascadeService);
        ReflectionTestUtils.setField(handler, "govChangeFormAssembler", govChangeFormAssembler);
        ReflectionTestUtils.setField(handler, "govTicketV2FormAssembler", govTicketV2FormAssembler);
    }

    // ======= Branch 1: v2 ticket (ticketType != null) → GovTicketV2FormAssembler =======

    @Test
    public void v2Branch_ticketTypeSet_delegatesToV2Assembler() throws Exception {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setOwnerUid("uid-001");
        ticket.setTicketTitle("v2 ticket");

        ApprovalMO info = new ApprovalMO();
        info.setTicketType("PRE_DDL");
        info.setServiceId(55L);
        ticket.setTicketInfo(JsonUtils.toJson(info));

        ChangeForm expectedForm = new ChangeForm();
        expectedForm.setTicketTitle("v2 form");
        when(govTicketV2FormAssembler.build(eq(ticket), any(ApprovalMO.class), eq("PROC-001")))
            .thenReturn(expectedForm);

        ChangeForm result = invokeConvertToChangeForm(ticket, "PROC-001");

        assertSame(expectedForm, result);
        // Old governance assembler must NOT be called
        verifyNoInteractions(govChangeFormAssembler);
    }

    @Test
    public void v2Branch_prodDmlTicketType_delegatesToV2Assembler() throws Exception {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setOwnerUid("uid-001");
        ticket.setTicketTitle("prod dml ticket");

        ApprovalMO info = new ApprovalMO();
        info.setTicketType("PROD_DML");
        ticket.setTicketInfo(JsonUtils.toJson(info));

        ChangeForm expectedForm = new ChangeForm();
        when(govTicketV2FormAssembler.build(eq(ticket), any(ApprovalMO.class), eq("PROC-002")))
            .thenReturn(expectedForm);

        ChangeForm result = invokeConvertToChangeForm(ticket, "PROC-002");

        assertSame(expectedForm, result);
        verifyNoInteractions(govChangeFormAssembler);
    }

    // ======= Branch 2: old governance (govRole != null, ticketType null) → GovChangeFormAssembler =======

    @Test
    public void oldGovBranch_govRoleSet_delegatesToGovChangeFormAssembler() throws Exception {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setOwnerUid("uid-001");

        ApprovalMO info = new ApprovalMO();
        info.setGovRole("PROD"); // old governance — ticketType is null
        info.setLogicalDbId(10L);
        ticket.setTicketInfo(JsonUtils.toJson(info));

        ChangeForm expectedForm = new ChangeForm();
        when(govChangeFormAssembler.build(eq(ticket), any(ApprovalMO.class), eq("PROC-003")))
            .thenReturn(expectedForm);

        ChangeForm result = invokeConvertToChangeForm(ticket, "PROC-003");

        assertSame(expectedForm, result);
        // V2 assembler must NOT be called
        verifyNoInteractions(govTicketV2FormAssembler);
    }

    // ======= Branch 3: CI/CD (neither govRole nor ticketType) → original logic =======

    @Test
    public void cicdBranch_neitherSet_fallsToCicdPath() throws Exception {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setOwnerUid("uid-001");
        ticket.setTicketTitle("cicd ticket");
        ticket.setTicketInfo("{\"changeId\":999,\"changeOwnerUid\":\"cicd-owner\"}");

        // Mock CI/CD path dependencies
        com.clougence.clouddm.platform.dal.mapper.cicd.DmChangeMapper changeMapper
            = mock(com.clougence.clouddm.platform.dal.mapper.cicd.DmChangeMapper.class);
        com.clougence.clouddm.platform.dal.mapper.cicd.DmChangeFlowMapper flowMapper
            = mock(com.clougence.clouddm.platform.dal.mapper.cicd.DmChangeFlowMapper.class);
        when(changeFlowDal.changeMapper()).thenReturn(changeMapper);
        when(changeFlowDal.flowMapper()).thenReturn(flowMapper);

        com.clougence.clouddm.platform.dal.model.cicd.DmChangeDO changeDO
            = new com.clougence.clouddm.platform.dal.model.cicd.DmChangeDO();
        changeDO.setOwnerUid("cicd-owner");
        changeDO.setRefFlowId(42L);
        changeDO.setChangeName("release-1.0");
        changeDO.setChangeBranch("main");
        when(changeMapper.queryChangeById(999L)).thenReturn(changeDO);

        com.clougence.clouddm.platform.dal.model.cicd.DmChangeFlowDO flowDO
            = new com.clougence.clouddm.platform.dal.model.cicd.DmChangeFlowDO();
        flowDO.setFlowName("prod-flow");
        when(flowMapper.queryByOwnerAndId("cicd-owner", 42L)).thenReturn(flowDO);

        com.clougence.clouddm.platform.dal.mapper.auth.DmAuthUserMapper userMapper
            = mock(com.clougence.clouddm.platform.dal.mapper.auth.DmAuthUserMapper.class);
        when(authDal.userMapper()).thenReturn(userMapper);
        com.clougence.clouddm.platform.dal.model.auth.DmAuthUserDO userDO
            = new com.clougence.clouddm.platform.dal.model.auth.DmAuthUserDO();
        userDO.setPhone("13900000000");
        when(userMapper.queryByUid("uid-001")).thenReturn(userDO);

        ChangeForm result = invokeConvertToChangeForm(ticket, "PROC-CICD-001");

        // CI/CD form values
        assertEquals("13900000000", result.getTicketUserPhone());
        assertEquals("cicd ticket", result.getTicketTitle());
        assertEquals("prod-flow", result.getFlowName());
        assertEquals("release-1.0", result.getChangeName());
        assertEquals("main", result.getBranch());

        // Neither assembler called
        verifyNoInteractions(govTicketV2FormAssembler);
        verifyNoInteractions(govChangeFormAssembler);
    }

    // ======= Mutual exclusion: v2 ticket with govRole also set → v2 branch takes precedence =======

    @Test
    public void mutualExclusion_bothTicketTypeAndGovRole_v2TakesPrecedence() throws Exception {
        // Edge case: a ticket with both ticketType and govRole set
        // (shouldn't happen in practice, but v2 branch is checked first)
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setOwnerUid("uid-001");

        ApprovalMO info = new ApprovalMO();
        info.setTicketType("PRE_DDL");
        info.setGovRole("PROD"); // both set — v2 branch should win
        ticket.setTicketInfo(JsonUtils.toJson(info));

        ChangeForm v2Form = new ChangeForm();
        v2Form.setTicketTitle("v2 form");
        when(govTicketV2FormAssembler.build(eq(ticket), any(ApprovalMO.class), eq("PROC-004")))
            .thenReturn(v2Form);

        ChangeForm result = invokeConvertToChangeForm(ticket, "PROC-004");

        assertSame(v2Form, result);
        verifyNoInteractions(govChangeFormAssembler);
    }

    // ======= Helper: invoke private convertToChangeForm via reflection =======

    private ChangeForm invokeConvertToChangeForm(DmApprovalDO ticket, String templateId) throws Exception {
        java.lang.reflect.Method method = ChangeApprovalHandler.class
            .getDeclaredMethod("convertToChangeForm", DmApprovalDO.class, String.class);
        method.setAccessible(true);
        return (ChangeForm) method.invoke(handler, ticket, templateId);
    }
}
