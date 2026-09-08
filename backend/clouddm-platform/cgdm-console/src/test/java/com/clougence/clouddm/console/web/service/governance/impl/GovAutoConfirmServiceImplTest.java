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

import java.util.Collections;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.model.fo.ticket.DmAutoExecConfigFO;
import com.clougence.clouddm.console.web.service.approval.ApprovalControlService;
import com.clougence.clouddm.console.web.service.envparam.DmEnvParamService;
import com.clougence.clouddm.console.web.service.governance.GovAutoConfirmService;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.DbChangeGovernDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangeEventMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangeRevisionMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangePromotionMapper;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.dbchange.ChangeType;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangePromotionDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeRevisionDO;
import com.clougence.clouddm.platform.dal.model.dbchange.GovEventType;
import com.clougence.clouddm.platform.dal.model.logicaldb.GovRole;
import com.clougence.clouddm.sdk.model.env.EnvParamKeys;
import com.clougence.utils.JsonUtils;

public class GovAutoConfirmServiceImplTest {

    private GovAutoConfirmService  service;

    private ApprovalDal            approvalDal;
    private DmApprovalMapper      approvalMapper;
    private DbChangeGovernDal     dbChangeGovernDal;
    private DmDbChangePromotionMapper promotionMapper;
    private DmDbChangeRevisionMapper revisionMapper;
    private DmDbChangeEventMapper  eventMapper;
    private ApprovalControlService approvalControlService;
    private DmEnvParamService      dmEnvParamService;

    private static final String   PUID         = "puid-001";
    private static final long     TICKET_ID    = 100L;
    private static final long     PROMOTION_ID = 50L;
    private static final long     REVISION_ID  = 30L;
    private static final long     LOGICAL_DB_ID = 10L;
    private static final long     PROD_ENV_ID  = 5L;

    @Before
    public void setUp() {
        approvalDal = mock(ApprovalDal.class);
        dbChangeGovernDal = mock(DbChangeGovernDal.class);
        approvalControlService = mock(ApprovalControlService.class);
        dmEnvParamService = mock(DmEnvParamService.class);

        approvalMapper = mock(DmApprovalMapper.class);
        promotionMapper = mock(DmDbChangePromotionMapper.class);
        revisionMapper = mock(DmDbChangeRevisionMapper.class);
        eventMapper = mock(DmDbChangeEventMapper.class);

        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);
        when(dbChangeGovernDal.promotionMapper()).thenReturn(promotionMapper);
        when(dbChangeGovernDal.revisionMapper()).thenReturn(revisionMapper);
        when(dbChangeGovernDal.eventMapper()).thenReturn(eventMapper);

        GovAutoConfirmServiceImpl impl = new GovAutoConfirmServiceImpl();
        ReflectionTestUtils.setField(impl, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(impl, "dbChangeGovernDal", dbChangeGovernDal);
        ReflectionTestUtils.setField(impl, "approvalControlService", approvalControlService);
        ReflectionTestUtils.setField(impl, "dmEnvParamService", dmEnvParamService);
        service = impl;

        when(approvalMapper.listUnFinishTicketIdList()).thenReturn(Collections.emptyList());
    }

    @Test
    public void autoConfirm_on_confirmsWithCorrectConfig() {
        DmApprovalDO ticket = buildTicket(GovRole.PROD.name(), ApprovalStatus.WAIT_CONFIRM);
        when(approvalMapper.listUnFinishTicketIdList()).thenReturn(List.of(TICKET_ID));
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket);
        DmDbChangePromotionDO promo = buildPromotion();
        when(promotionMapper.selectById(PROMOTION_ID)).thenReturn(promo);
        when(dmEnvParamService.queryParam(PUID, PROD_ENV_ID, EnvParamKeys.GOV_AUTO_CONFIRM)).thenReturn("on");
        DmDbChangeRevisionDO rev = new DmDbChangeRevisionDO();
        rev.setChangeType(ChangeType.DML.name());
        when(revisionMapper.selectById(REVISION_ID)).thenReturn(rev);

        service.autoConfirmProdTickets();

        ArgumentCaptor<DmAutoExecConfigFO> configCaptor = ArgumentCaptor.forClass(DmAutoExecConfigFO.class);
        verify(approvalControlService).confirmTicketBySystem(eq(TICKET_ID), configCaptor.capture());
        DmAutoExecConfigFO config = configCaptor.getValue();
        assertTrue("DML should be transactional", config.isEnableTransactional());
        assertEquals(com.clougence.clouddm.api.console.autoexec.ErrorStrategy.NONE, config.getErrorStrategy());

        // Event written
        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        assertEquals(GovEventType.AUTO_CONFIRM.name(), eventCaptor.getValue().getEventType());
    }

    @Test
    public void autoConfirm_off_noAction() {
        DmApprovalDO ticket = buildTicket(GovRole.PROD.name(), ApprovalStatus.WAIT_CONFIRM);
        when(approvalMapper.listUnFinishTicketIdList()).thenReturn(List.of(TICKET_ID));
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket);
        when(promotionMapper.selectById(PROMOTION_ID)).thenReturn(buildPromotion());
        when(dmEnvParamService.queryParam(PUID, PROD_ENV_ID, EnvParamKeys.GOV_AUTO_CONFIRM)).thenReturn("off");

        service.autoConfirmProdTickets();

        verify(approvalControlService, never()).confirmTicketBySystem(anyLong(), any());
    }

    @Test
    public void autoConfirm_nullParam_noAction() {
        DmApprovalDO ticket = buildTicket(GovRole.PROD.name(), ApprovalStatus.WAIT_CONFIRM);
        when(approvalMapper.listUnFinishTicketIdList()).thenReturn(List.of(TICKET_ID));
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket);
        when(promotionMapper.selectById(PROMOTION_ID)).thenReturn(buildPromotion());
        when(dmEnvParamService.queryParam(PUID, PROD_ENV_ID, EnvParamKeys.GOV_AUTO_CONFIRM)).thenReturn(null);

        service.autoConfirmProdTickets();

        verify(approvalControlService, never()).confirmTicketBySystem(anyLong(), any());
    }

    @Test
    public void autoConfirm_notWaitConfirm_skipped() {
        DmApprovalDO ticket = buildTicket(GovRole.PROD.name(), ApprovalStatus.RUNNING);
        when(approvalMapper.listUnFinishTicketIdList()).thenReturn(List.of(TICKET_ID));
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket);

        service.autoConfirmProdTickets();

        verify(approvalControlService, never()).confirmTicketBySystem(anyLong(), any());
    }

    @Test
    public void autoConfirm_preTicket_skipped() {
        DmApprovalDO ticket = buildTicket(GovRole.PRE.name(), ApprovalStatus.WAIT_CONFIRM);
        when(approvalMapper.listUnFinishTicketIdList()).thenReturn(List.of(TICKET_ID));
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket);

        service.autoConfirmProdTickets();

        verify(approvalControlService, never()).confirmTicketBySystem(anyLong(), any());
        verify(dbChangeGovernDal, never()).promotionMapper();
    }

    @Test
    public void autoConfirm_nonGovernance_skippedNoGovQueries() {
        DmApprovalDO ticket = buildTicket(null, ApprovalStatus.WAIT_CONFIRM);
        when(approvalMapper.listUnFinishTicketIdList()).thenReturn(List.of(TICKET_ID));
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket);

        service.autoConfirmProdTickets();

        verify(approvalControlService, never()).confirmTicketBySystem(anyLong(), any());
        verify(dbChangeGovernDal, never()).promotionMapper();
    }

    @Test
    public void autoConfirm_ddl_correctConfig() {
        DmApprovalDO ticket = buildTicket(GovRole.PROD.name(), ApprovalStatus.WAIT_CONFIRM);
        when(approvalMapper.listUnFinishTicketIdList()).thenReturn(List.of(TICKET_ID));
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket);
        when(promotionMapper.selectById(PROMOTION_ID)).thenReturn(buildPromotion());
        when(dmEnvParamService.queryParam(PUID, PROD_ENV_ID, EnvParamKeys.GOV_AUTO_CONFIRM)).thenReturn("on");
        DmDbChangeRevisionDO rev = new DmDbChangeRevisionDO();
        rev.setChangeType(ChangeType.DDL.name());
        when(revisionMapper.selectById(REVISION_ID)).thenReturn(rev);

        service.autoConfirmProdTickets();

        ArgumentCaptor<DmAutoExecConfigFO> configCaptor = ArgumentCaptor.forClass(DmAutoExecConfigFO.class);
        verify(approvalControlService).confirmTicketBySystem(eq(TICKET_ID), configCaptor.capture());
        assertFalse("DDL should not be transactional", configCaptor.getValue().isEnableTransactional());
    }

    @Test
    public void autoConfirm_exceptionIsolated_otherTicketsStillProcessed() {
        DmApprovalDO ticket1 = buildTicket(GovRole.PROD.name(), ApprovalStatus.WAIT_CONFIRM);
        DmApprovalDO ticket2 = buildTicket(GovRole.PROD.name(), ApprovalStatus.WAIT_CONFIRM);
        when(approvalMapper.listUnFinishTicketIdList()).thenReturn(List.of(101L, 102L));
        when(approvalMapper.queryById(101L)).thenReturn(ticket1);
        when(approvalMapper.queryById(102L)).thenReturn(ticket2);
        when(promotionMapper.selectById(PROMOTION_ID)).thenReturn(buildPromotion());
        when(dmEnvParamService.queryParam(PUID, PROD_ENV_ID, EnvParamKeys.GOV_AUTO_CONFIRM)).thenReturn("on");
        DmDbChangeRevisionDO rev = new DmDbChangeRevisionDO();
        rev.setChangeType(ChangeType.DML.name());
        when(revisionMapper.selectById(REVISION_ID)).thenReturn(rev);

        // First ticket throws
        doThrow(new RuntimeException("confirm failed"))
            .when(approvalControlService).confirmTicketBySystem(eq(101L), any());

        service.autoConfirmProdTickets();

        // Second ticket still processed
        verify(approvalControlService).confirmTicketBySystem(eq(102L), any());
    }

    private DmApprovalDO buildTicket(String govRole, ApprovalStatus status) {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setApproBiz(ApprovalBiz.DM_CHANGE);
        ticket.setTicketStatus(status);
        ticket.setPrimaryUid(PUID);

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

    private DmDbChangePromotionDO buildPromotion() {
        DmDbChangePromotionDO p = new DmDbChangePromotionDO();
        p.setId(PROMOTION_ID);
        p.setProdEnvId(PROD_ENV_ID);
        return p;
    }
}
