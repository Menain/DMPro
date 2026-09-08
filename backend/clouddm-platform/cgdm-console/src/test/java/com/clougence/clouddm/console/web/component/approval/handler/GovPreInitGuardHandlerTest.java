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
package com.clougence.clouddm.console.web.component.approval.handler;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.component.approval.model.PreInitContext;
import com.clougence.clouddm.console.web.component.governance.GovSqlHashUtils;
import com.clougence.clouddm.console.web.model.vo.logicaldb.LogicalDbTarget;
import com.clougence.clouddm.console.web.service.logicaldb.LogicalDbService;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.DbChangeGovernDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangePromotionMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangeRevisionMapper;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangePromotionDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeRevisionDO;
import com.clougence.clouddm.platform.dal.model.dbchange.PromotionStatus;
import com.clougence.clouddm.platform.dal.model.logicaldb.GovRole;
import com.clougence.utils.JsonUtils;

public class GovPreInitGuardHandlerTest {

    private GovPreInitGuardHandler handler;

    private ApprovalDal            approvalDal;
    private DmApprovalMapper      approvalMapper;
    private DbChangeGovernDal     dbChangeGovernDal;
    private DmDbChangePromotionMapper promotionMapper;
    private DmDbChangeRevisionMapper revisionMapper;
    private LogicalDbService      logicalDbService;

    private static final String   PUID         = "puid-001";
    private static final long     TICKET_ID    = 100L;
    private static final long     PROMOTION_ID = 50L;
    private static final long     REVISION_ID  = 30L;
    private static final long     LOGICAL_DB_ID = 10L;
    private static final String   SQL_TEXT     = "CREATE TABLE foo (id INT)";

    @Before
    public void setUp() {
        approvalDal = mock(ApprovalDal.class);
        dbChangeGovernDal = mock(DbChangeGovernDal.class);
        logicalDbService = mock(LogicalDbService.class);

        approvalMapper = mock(DmApprovalMapper.class);
        promotionMapper = mock(DmDbChangePromotionMapper.class);
        revisionMapper = mock(DmDbChangeRevisionMapper.class);

        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);
        when(dbChangeGovernDal.promotionMapper()).thenReturn(promotionMapper);
        when(dbChangeGovernDal.revisionMapper()).thenReturn(revisionMapper);

        handler = new GovPreInitGuardHandler();
        ReflectionTestUtils.setField(handler, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(handler, "dbChangeGovernDal", dbChangeGovernDal);
        ReflectionTestUtils.setField(handler, "logicalDbService", logicalDbService);
    }

    @Test
    public void displayOrder_isZero() {
        assertEquals(0, handler.displayOrder());
    }

    @Test
    public void supports_prodDmChange_returnsTrue() {
        DmApprovalDO ticket = buildTicket("PROD");
        assertTrue(handler.supports(ticket));
    }

    @Test
    public void supports_preDmChange_returnsFalse() {
        DmApprovalDO ticket = buildTicket(GovRole.PRE.name());
        assertFalse(handler.supports(ticket));
    }

    @Test
    public void supports_dmQuery_returnsFalse() {
        DmApprovalDO ticket = buildTicket("PROD");
        ticket.setApproBiz(ApprovalBiz.DM_QUERY);
        assertFalse(handler.supports(ticket));
    }

    @Test
    public void supports_nonGovernance_returnsFalse() {
        DmApprovalDO ticket = buildTicket(null);
        assertFalse(handler.supports(ticket));
    }

    @Test
    public void doHandle_pass_normalReturn() throws Exception {
        DmApprovalDO ticket = buildTicket("PROD");
        ticket.setRawSql(SQL_TEXT);
        DmApprovalDO fullTicket = buildTicket("PROD");
        fullTicket.setRawSql(SQL_TEXT);
        when(approvalMapper.queryByBizId(ticket.getBizId())).thenReturn(fullTicket);
        when(promotionMapper.selectById(PROMOTION_ID)).thenReturn(buildPromotion(PromotionStatus.APPROVED));
        when(revisionMapper.selectById(REVISION_ID)).thenReturn(buildRevision());
        when(logicalDbService.getBinding(PUID, LOGICAL_DB_ID, GovRole.PROD)).thenReturn(buildTarget());

        PreInitContext context = mock(PreInitContext.class);
        when(context.getApproval()).thenReturn(ticket);

        handler.doHandle(context); // no exception thrown
    }

    @Test(expected = ErrorMessageException.class)
    public void doHandle_promotionNotFound_throws() throws Exception {
        DmApprovalDO ticket = buildTicket("PROD");
        when(approvalMapper.queryByBizId(ticket.getBizId())).thenReturn(ticket);
        when(promotionMapper.selectById(PROMOTION_ID)).thenReturn(null);

        PreInitContext context = mock(PreInitContext.class);
        when(context.getApproval()).thenReturn(ticket);

        handler.doHandle(context);
    }

    @Test(expected = ErrorMessageException.class)
    public void doHandle_promotionFailed_throws() throws Exception {
        DmApprovalDO ticket = buildTicket("PROD");
        when(approvalMapper.queryByBizId(ticket.getBizId())).thenReturn(ticket);
        when(promotionMapper.selectById(PROMOTION_ID)).thenReturn(buildPromotion(PromotionStatus.FAILED));

        PreInitContext context = mock(PreInitContext.class);
        when(context.getApproval()).thenReturn(ticket);

        handler.doHandle(context);
    }

    @Test(expected = ErrorMessageException.class)
    public void doHandle_hashMismatch_throws() throws Exception {
        DmApprovalDO ticket = buildTicket("PROD");
        ticket.setRawSql("DROP TABLE foo");
        DmApprovalDO fullTicket = buildTicket("PROD");
        fullTicket.setRawSql("DROP TABLE foo");
        when(approvalMapper.queryByBizId(ticket.getBizId())).thenReturn(fullTicket);
        when(promotionMapper.selectById(PROMOTION_ID)).thenReturn(buildPromotion(PromotionStatus.APPROVED));
        DmDbChangeRevisionDO rev = buildRevision(); // hash for "CREATE TABLE foo (id INT)"
        when(revisionMapper.selectById(REVISION_ID)).thenReturn(rev);

        PreInitContext context = mock(PreInitContext.class);
        when(context.getApproval()).thenReturn(ticket);

        handler.doHandle(context);
    }

    @Test(expected = ErrorMessageException.class)
    public void doHandle_bindingChanged_throws() throws Exception {
        DmApprovalDO ticket = buildTicket("PROD");
        ticket.setRawSql(SQL_TEXT);
        DmApprovalDO fullTicket = buildTicket("PROD");
        fullTicket.setRawSql(SQL_TEXT);
        when(approvalMapper.queryByBizId(ticket.getBizId())).thenReturn(fullTicket);
        when(promotionMapper.selectById(PROMOTION_ID)).thenReturn(buildPromotion(PromotionStatus.APPROVED));
        when(revisionMapper.selectById(REVISION_ID)).thenReturn(buildRevision());
        LogicalDbTarget changed = buildTarget();
        changed.setDsId(999L);
        when(logicalDbService.getBinding(PUID, LOGICAL_DB_ID, GovRole.PROD)).thenReturn(changed);

        PreInitContext context = mock(PreInitContext.class);
        when(context.getApproval()).thenReturn(ticket);

        handler.doHandle(context);
    }

    // ======= helpers =======

    private DmApprovalDO buildTicket(String govRole) {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setApproBiz(ApprovalBiz.DM_CHANGE);
        ticket.setPrimaryUid(PUID);
        ticket.setBizId("biz-001");
        ticket.setRawSql(SQL_TEXT);

        ApprovalMO mo = new ApprovalMO();
        if (govRole != null) {
            mo.setGovRole(govRole);
            mo.setPromotionId(PROMOTION_ID);
            mo.setRevisionId(REVISION_ID);
            mo.setLogicalDbId(LOGICAL_DB_ID);
        }
        ticket.setTicketInfo(JsonUtils.toJson(mo));
        return ticket;
    }

    private DmDbChangePromotionDO buildPromotion(PromotionStatus status) {
        DmDbChangePromotionDO p = new DmDbChangePromotionDO();
        p.setId(PROMOTION_ID);
        p.setStatus(status.name());
        p.setProdEnvId(5L);
        p.setProdDsId(20L);
        p.setProdResPath("/mydb/");
        return p;
    }

    private DmDbChangeRevisionDO buildRevision() {
        DmDbChangeRevisionDO rev = new DmDbChangeRevisionDO();
        rev.setId(REVISION_ID);
        rev.setSqlText(SQL_TEXT);
        rev.setSqlHash(GovSqlHashUtils.hash(SQL_TEXT));
        return rev;
    }

    private LogicalDbTarget buildTarget() {
        LogicalDbTarget t = new LogicalDbTarget();
        t.setLogicalDbId(LOGICAL_DB_ID);
        t.setEnvId(5L);
        t.setDsId(20L);
        t.setResPath("/mydb/");
        t.setGovRole(GovRole.PROD);
        return t;
    }
}
