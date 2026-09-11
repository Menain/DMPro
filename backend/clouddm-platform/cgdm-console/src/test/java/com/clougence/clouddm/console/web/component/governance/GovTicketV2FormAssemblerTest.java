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

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.platform.dal.access.AuthDal;
import com.clougence.clouddm.platform.dal.access.DbPairDal;
import com.clougence.clouddm.platform.dal.access.TicketDbStmtDal;
import com.clougence.clouddm.platform.dal.mapper.auth.DmAuthUserMapper;
import com.clougence.clouddm.platform.dal.mapper.dbpair.DmDbServiceMapper;
import com.clougence.clouddm.platform.dal.mapper.govticket.DmTicketDbStmtMapper;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.auth.DmAuthUserDO;
import com.clougence.clouddm.platform.dal.model.dbpair.DmDbServiceDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;
import com.clougence.clouddm.sdk.approval.form.ChangeForm;
import com.clougence.utils.JsonUtils;

/**
 * Tests for {@link GovTicketV2FormAssembler} — field-by-field assertions.
 * Verifies the ChangeForm mapping for DingTalk approval consumption of v2 governance tickets.
 */
public class GovTicketV2FormAssemblerTest {

    private GovTicketV2FormAssembler assembler;

    private TicketDbStmtDal    ticketDbStmtDal;
    private DmTicketDbStmtMapper stmtMapper;
    private AuthDal           authDal;
    private DmAuthUserMapper   userMapper;
    private DbPairDal         dbPairDal;
    private DmDbServiceMapper   serviceMapper;

    private static final String UID  = "uid-001";
    private static final long   TICKET_ID = 100L;

    @Before
    public void setUp() {
        assembler = new GovTicketV2FormAssembler();
        ticketDbStmtDal = mock(TicketDbStmtDal.class);
        stmtMapper = mock(DmTicketDbStmtMapper.class);
        when(ticketDbStmtDal.stmtMapper()).thenReturn(stmtMapper);
        authDal = mock(AuthDal.class);
        userMapper = mock(DmAuthUserMapper.class);
        when(authDal.userMapper()).thenReturn(userMapper);
        dbPairDal = mock(DbPairDal.class);
        serviceMapper = mock(DmDbServiceMapper.class);
        when(dbPairDal.serviceMapper()).thenReturn(serviceMapper);

        ReflectionTestUtils.setField(assembler, "ticketDbStmtDal", ticketDbStmtDal);
        ReflectionTestUtils.setField(assembler, "authDal", authDal);
        ReflectionTestUtils.setField(assembler, "dbPairDal", dbPairDal);
    }

    @Test
    public void build_preDdlTicket_allFieldsAsserted() {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setOwnerUid(UID);
        ticket.setTicketTitle("DDL ticket title");
        ticket.setBizId("BIZ-001");

        ApprovalMO info = new ApprovalMO();
        info.setTicketType("PRE_DDL");
        info.setServiceId(55L);
        ticket.setTicketInfo(JsonUtils.toJson(info));

        List<DmTicketDbStmtDO> groups = new ArrayList<>();
        DmTicketDbStmtDO g1 = new DmTicketDbStmtDO();
        g1.setDbName("orders_db");
        g1.setSqlContent("CREATE TABLE orders (id INT);");
        groups.add(g1);
        DmTicketDbStmtDO g2 = new DmTicketDbStmtDO();
        g2.setDbName("users_db");
        g2.setSqlContent("CREATE TABLE users (id INT);");
        groups.add(g2);
        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(groups);

        DmAuthUserDO user = new DmAuthUserDO();
        user.setPhone("13800000001");
        user.setUsername("bob");
        when(userMapper.queryByUid(UID)).thenReturn(user);

        DmDbServiceDO svc = new DmDbServiceDO();
        svc.setId(55L);
        svc.setServiceName("order-service");
        when(serviceMapper.selectById(55L)).thenReturn(svc);

        ChangeForm form = assembler.build(ticket, info, "PROC-001");

        // ticketTitle
        assertEquals("DDL ticket title", form.getTicketTitle());
        // targetDs contains both DB names
        assertTrue(form.getTargetDs().contains("orders_db"));
        assertTrue(form.getTargetDs().contains("users_db"));
        // executeSql contains both SQLs
        assertTrue(form.getExecuteSql().contains("CREATE TABLE orders"));
        assertTrue(form.getExecuteSql().contains("CREATE TABLE users"));
        // ticketDesc metadata
        assertTrue(form.getTicketDesc().contains("申请人: bob"));
        assertTrue(form.getTicketDesc().contains("工单类型: 预发 DDL"));
        assertTrue(form.getTicketDesc().contains("服务: order-service"));
        assertTrue(form.getTicketDesc().contains("涉及库数: 2"));
        assertTrue(form.getTicketDesc().contains("/ticket/100"));
        // phone + template
        assertEquals("13800000001", form.getTicketUserPhone());
        assertEquals("PROC-001", form.getTemplateIdentity());
        // CI/CD fields null
        assertNull(form.getFlowName());
        assertNull(form.getChangeName());
        assertNull(form.getBranch());
    }

    @Test
    public void build_prodDmlTicket_typeText() {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setOwnerUid(UID);
        ticket.setTicketTitle("DML ticket");
        ticket.setBizId("BIZ-002");

        ApprovalMO info = new ApprovalMO();
        info.setTicketType("PROD_DML");
        info.setServiceId(null);
        ticket.setTicketInfo(JsonUtils.toJson(info));

        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(new ArrayList<>());
        when(userMapper.queryByUid(UID)).thenReturn(new DmAuthUserDO());

        ChangeForm form = assembler.build(ticket, info, "PROC-002");

        assertTrue(form.getTicketDesc().contains("工单类型: 生产 DML"));
        // No service (serviceId null)
        assertFalse(form.getTicketDesc().contains("服务:"));
        // No groups
        assertEquals("-", form.getTargetDs());
        assertEquals("", form.getExecuteSql());
    }

    @Test
    public void build_titleFallback_bizId() {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setOwnerUid(UID);
        ticket.setTicketTitle(null);
        ticket.setBizId("BIZ-FALLBACK");

        ApprovalMO info = new ApprovalMO();
        info.setTicketType("PRE_DDL");
        ticket.setTicketInfo(JsonUtils.toJson(info));

        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(new ArrayList<>());
        when(userMapper.queryByUid(UID)).thenReturn(new DmAuthUserDO());

        ChangeForm form = assembler.build(ticket, info, "PROC-003");
        assertEquals("BIZ-FALLBACK", form.getTicketTitle());
    }

    @Test
    public void build_noGroups_degradedForm() {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setOwnerUid(UID);
        ticket.setTicketTitle("empty ticket");

        ApprovalMO info = new ApprovalMO();
        info.setTicketType("PRE_DDL");
        ticket.setTicketInfo(JsonUtils.toJson(info));

        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(null);
        when(userMapper.queryByUid(UID)).thenReturn(new DmAuthUserDO());

        ChangeForm form = assembler.build(ticket, info, "PROC-004");
        assertNotNull(form);
        assertEquals("empty ticket", form.getTicketTitle());
        assertEquals("-", form.getTargetDs());
        assertEquals("", form.getExecuteSql());
    }

    @Test
    public void build_longSql_truncatedWithDeepLink() {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setOwnerUid(UID);
        ticket.setTicketTitle("t");

        ApprovalMO info = new ApprovalMO();
        info.setTicketType("PRE_DDL");
        ticket.setTicketInfo(JsonUtils.toJson(info));

        StringBuilder longSql = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            longSql.append("SELECT col").append(i).append(" FROM big_table; ");
        }
        DmTicketDbStmtDO g = new DmTicketDbStmtDO();
        g.setDbName("big_db");
        g.setSqlContent(longSql.toString());
        when(stmtMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(g));
        when(userMapper.queryByUid(UID)).thenReturn(new DmAuthUserDO());

        ChangeForm form = assembler.build(ticket, info, "PROC-005");
        // DingApiUtils.safeLength truncates at 4000; assembler must stay within that limit
        // so the deep-link suffix is not cut off by DingApiUtils.
        assertTrue(form.getExecuteSql().length() <= 4000);
        assertTrue(form.getExecuteSql().contains("/ticket/100"));
    }
}
