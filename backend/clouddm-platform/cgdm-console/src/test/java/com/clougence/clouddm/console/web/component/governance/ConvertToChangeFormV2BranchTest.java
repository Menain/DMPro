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
 * Tests for the v2 branch of {@link ChangeApprovalHandler#convertToChangeForm} (P4).
 * <p>
 * P5 trim: the legacy governance (govRole) branch was removed together with
 * {@code GovChangeFormAssembler}, leaving a two-branch dispatch:
 * <ol>
 * <li>v2 ticket (ticketType != null) → GovTicketV2FormAssembler</li>
 * <li>CI/CD (ticketType null) → original CI/CD logic</li>
 * </ol>
 */
public class ConvertToChangeFormV2BranchTest {

    private ChangeApprovalHandler      handler;
    private GovTicketV2FormAssembler   govTicketV2FormAssembler;
    private ChangeFlowDal              changeFlowDal;
    private AuthDal                    authDal;

    private static final long   TICKET_ID = 100L;

    @Before
    public void setUp() {
        handler = new ChangeApprovalHandler();
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
    }

    // ======= Branch 2: CI/CD (ticketType null) → original logic =======

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

        // v2 assembler not consulted for CI/CD tickets
        verifyNoInteractions(govTicketV2FormAssembler);
    }

    // ======= Helper: invoke private convertToChangeForm via reflection =======

    private ChangeForm invokeConvertToChangeForm(DmApprovalDO ticket, String templateId) throws Exception {
        java.lang.reflect.Method method = ChangeApprovalHandler.class
            .getDeclaredMethod("convertToChangeForm", DmApprovalDO.class, String.class);
        method.setAccessible(true);
        return (ChangeForm) method.invoke(handler, ticket, templateId);
    }
}
