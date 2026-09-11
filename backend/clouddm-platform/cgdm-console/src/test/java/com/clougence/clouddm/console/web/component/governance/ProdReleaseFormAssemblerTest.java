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
import com.clougence.clouddm.platform.dal.access.ProdReleaseDal;
import com.clougence.clouddm.platform.dal.mapper.auth.DmAuthUserMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseStmtMapper;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.auth.DmAuthUserDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseStmtDO;
import com.clougence.clouddm.sdk.approval.form.ChangeForm;
import com.clougence.utils.JsonUtils;

/**
 * Tests for {@link ProdReleaseFormAssembler} — field-by-field assertions.
 * Verifies the ChangeForm mapping for DingTalk approval consumption.
 */
public class ProdReleaseFormAssemblerTest {

    private ProdReleaseFormAssembler assembler;

    private ProdReleaseDal   prodReleaseDal;
    private DmProdReleaseMapper releaseMapper;
    private DmProdReleaseStmtMapper stmtMapper;
    private AuthDal           authDal;
    private DmAuthUserMapper   userMapper;

    private static final String PUID = "puid-001";
    private static final String UID  = "uid-001";
    private static final long   TICKET_ID  = 100L;
    private static final long   RELEASE_ID = 500L;

    @Before
    public void setUp() {
        assembler = new ProdReleaseFormAssembler();
        prodReleaseDal = mock(ProdReleaseDal.class);
        releaseMapper = mock(DmProdReleaseMapper.class);
        stmtMapper = mock(DmProdReleaseStmtMapper.class);
        when(prodReleaseDal.releaseMapper()).thenReturn(releaseMapper);
        when(prodReleaseDal.stmtMapper()).thenReturn(stmtMapper);
        authDal = mock(AuthDal.class);
        userMapper = mock(DmAuthUserMapper.class);
        when(authDal.userMapper()).thenReturn(userMapper);

        ReflectionTestUtils.setField(assembler, "prodReleaseDal", prodReleaseDal);
        ReflectionTestUtils.setField(assembler, "authDal", authDal);
    }

    @Test
    public void build_fullRelease_allFieldsAsserted() {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setOwnerUid(UID);
        ticket.setPrimaryUid(PUID);
        ticket.setTicketTitle("release ticket");
        ticket.setRawSql("-- raw sql");

        ApprovalMO info = new ApprovalMO();
        info.setReleaseId(RELEASE_ID);
        info.setReleaseNo("REL-20260911-0001");
        ticket.setTicketInfo(JsonUtils.toJson(info));

        DmProdReleaseDO release = new DmProdReleaseDO();
        release.setId(RELEASE_ID);
        release.setReleaseNo("REL-20260911-0001");
        release.setTitle("production release");
        release.setCreatorUid(UID);
        when(releaseMapper.queryById(RELEASE_ID)).thenReturn(release);

        List<DmProdReleaseStmtDO> stmts = new ArrayList<>();
        DmProdReleaseStmtDO s1 = new DmProdReleaseStmtDO();
        s1.setProdDbName("orders_db");
        s1.setSqlContent("ALTER TABLE orders ADD COLUMN note TEXT;");
        s1.setSeq(1);
        stmts.add(s1);
        DmProdReleaseStmtDO s2 = new DmProdReleaseStmtDO();
        s2.setProdDbName("users_db");
        s2.setSqlContent("ALTER TABLE users ADD COLUMN avatar VARCHAR(255);");
        s2.setSeq(2);
        stmts.add(s2);
        when(stmtMapper.queryByReleaseId(RELEASE_ID)).thenReturn(stmts);

        DmAuthUserDO user = new DmAuthUserDO();
        user.setPhone("13800000001");
        user.setUsername("alice");
        when(userMapper.queryByUid(UID)).thenReturn(user);

        ChangeForm form = assembler.build(ticket, info, "PROC-001");

        // ticketTitle = releaseNo + " " + title
        assertEquals("REL-20260911-0001 production release", form.getTicketTitle());
        // targetDs = prod DB names
        assertTrue(form.getTargetDs().contains("orders_db"));
        assertTrue(form.getTargetDs().contains("users_db"));
        // executeSql contains both SQLs
        assertTrue(form.getExecuteSql().contains("ALTER TABLE orders"));
        assertTrue(form.getExecuteSql().contains("ALTER TABLE users"));
        // ticketDesc has metadata
        assertTrue(form.getTicketDesc().contains("申请人: alice"));
        assertTrue(form.getTicketDesc().contains("REL-20260911-0001"));
        assertTrue(form.getTicketDesc().contains("目标生产库: 2 个"));
        assertTrue(form.getTicketDesc().contains("语句总数: 2"));
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
    public void build_missingReleaseId_degradedForm() {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setOwnerUid(UID);
        ticket.setTicketTitle("fallback title");
        ticket.setRawSql("SELECT 1");

        ApprovalMO info = new ApprovalMO();
        info.setReleaseId(null); // missing
        ticket.setTicketInfo(JsonUtils.toJson(info));

        DmAuthUserDO user = new DmAuthUserDO();
        user.setPhone("13800000002");
        when(userMapper.queryByUid(UID)).thenReturn(user);

        ChangeForm form = assembler.build(ticket, info, "PROC-002");

        // Degraded form, not exception
        assertNotNull(form);
        assertEquals("fallback title", form.getTicketTitle());
        assertEquals("-", form.getTargetDs());
        assertEquals("SELECT 1", form.getExecuteSql());
        assertTrue(form.getTicketDesc().contains("发布单数据缺失"));
        assertEquals("13800000002", form.getTicketUserPhone());
        assertEquals("PROC-002", form.getTemplateIdentity());
    }

    @Test
    public void build_releaseNotFound_degradedForm() {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setOwnerUid(UID);
        ticket.setTicketTitle("fallback");
        ticket.setRawSql("SELECT 1");

        ApprovalMO info = new ApprovalMO();
        info.setReleaseId(RELEASE_ID);
        ticket.setTicketInfo(JsonUtils.toJson(info));

        when(releaseMapper.queryById(RELEASE_ID)).thenReturn(null); // release not found
        DmAuthUserDO user = new DmAuthUserDO();
        when(userMapper.queryByUid(UID)).thenReturn(user);

        ChangeForm form = assembler.build(ticket, info, "PROC-003");
        assertNotNull(form);
        assertEquals("fallback", form.getTicketTitle());
        assertEquals("-", form.getTargetDs());
    }

    @Test
    public void build_nullInfo_degradedForm() {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setOwnerUid(UID);
        ticket.setTicketTitle("fallback");
        ticket.setRawSql("SELECT 1");

        DmAuthUserDO user = new DmAuthUserDO();
        when(userMapper.queryByUid(UID)).thenReturn(user);

        ChangeForm form = assembler.build(ticket, null, "PROC-004");
        assertNotNull(form);
        assertEquals("fallback", form.getTicketTitle());
    }

    @Test
    public void build_longSql_truncatedWithDeepLink() {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setOwnerUid(UID);
        ticket.setTicketTitle("t");
        ticket.setRawSql("");

        ApprovalMO info = new ApprovalMO();
        info.setReleaseId(RELEASE_ID);
        ticket.setTicketInfo(JsonUtils.toJson(info));

        DmProdReleaseDO release = new DmProdReleaseDO();
        release.setId(RELEASE_ID);
        release.setReleaseNo("REL-001");
        release.setTitle("t");
        release.setCreatorUid(UID);
        when(releaseMapper.queryById(RELEASE_ID)).thenReturn(release);

        // Build a stmt with very long SQL
        StringBuilder longSql = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            longSql.append("SELECT col").append(i).append(" FROM big_table; ");
        }
        DmProdReleaseStmtDO s = new DmProdReleaseStmtDO();
        s.setProdDbName("big_db");
        s.setSqlContent(longSql.toString());
        s.setSeq(1);
        when(stmtMapper.queryByReleaseId(RELEASE_ID)).thenReturn(List.of(s));
        when(userMapper.queryByUid(UID)).thenReturn(new DmAuthUserDO());

        ChangeForm form = assembler.build(ticket, info, "PROC-005");

        // SQL should be truncated and contain deep link
        // DingApiUtils.safeLength truncates at 4000; assembler must stay within that limit
        // so the deep-link suffix is not cut off by DingApiUtils.
        assertTrue(form.getExecuteSql().length() <= 4000);
        assertTrue(form.getExecuteSql().contains("/ticket/100"));
    }
}
