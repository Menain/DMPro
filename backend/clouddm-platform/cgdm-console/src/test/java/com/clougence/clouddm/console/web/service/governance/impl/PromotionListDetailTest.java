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

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.console.web.component.auth.DmAuthServiceForBiz;
import com.clougence.clouddm.console.web.component.governance.GovSqlHashUtils;
import com.clougence.clouddm.console.web.component.governance.PromotionStateMachine;
import com.clougence.clouddm.console.web.model.fo.governance.GovPromotionListFO;
import com.clougence.clouddm.console.web.model.vo.DmPageVO;
import com.clougence.clouddm.console.web.model.vo.governance.PromotionDetailVO;
import com.clougence.clouddm.console.web.model.vo.governance.PromotionVO;
import com.clougence.clouddm.console.web.service.approval.ApprovalControlService;
import com.clougence.clouddm.console.web.service.envparam.DmEnvParamService;
import com.clougence.clouddm.console.web.service.governance.GovPromotionService;
import com.clougence.clouddm.console.web.service.logicaldb.LogicalDbService;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.DbChangeGovernDal;
import com.clougence.clouddm.platform.dal.access.LogicalDbDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangeEventMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangePromotionMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangeRevisionMapper;
import com.clougence.clouddm.platform.dal.mapper.logicaldb.DmLogicalDbMapper;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangePromotionDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeRevisionDO;
import com.clougence.clouddm.platform.dal.model.dbchange.GovEventType;
import com.clougence.clouddm.platform.dal.model.dbchange.PromotionStatus;
import com.clougence.clouddm.platform.dal.model.logicaldb.DmLogicalDbDO;
import com.clougence.utils.JsonUtils;

public class PromotionListDetailTest {

    private GovPromotionService   service;

    private DbChangeGovernDal    dbChangeGovernDal;
    private ApprovalDal           approvalDal;
    private LogicalDbDal          logicalDbDal;
    private DmDbChangePromotionMapper promotionMapper;
    private DmDbChangeRevisionMapper  revisionMapper;
    private DmDbChangeEventMapper  eventMapper;
    private DmLogicalDbMapper         logicalDbMapper;

    private static final String PUID       = "puid-001";
    private static final long   PROMOTION_ID = 500L;
    private static final long   LOGICAL_DB_ID = 10L;
    private static final long   REVISION_ID  = 300L;

    @Before
    public void setUp() {
        dbChangeGovernDal = mock(DbChangeGovernDal.class);
        approvalDal = mock(ApprovalDal.class);
        logicalDbDal = mock(LogicalDbDal.class);

        promotionMapper = mock(DmDbChangePromotionMapper.class);
        revisionMapper = mock(DmDbChangeRevisionMapper.class);
        eventMapper = mock(DmDbChangeEventMapper.class);
        logicalDbMapper = mock(DmLogicalDbMapper.class);
        DmApprovalMapper approvalMapper = mock(DmApprovalMapper.class);

        when(dbChangeGovernDal.promotionMapper()).thenReturn(promotionMapper);
        when(dbChangeGovernDal.revisionMapper()).thenReturn(revisionMapper);
        when(dbChangeGovernDal.eventMapper()).thenReturn(eventMapper);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);
        when(logicalDbDal.logicalDbMapper()).thenReturn(logicalDbMapper);

        GovPromotionServiceImpl impl = new GovPromotionServiceImpl();
        ReflectionTestUtils.setField(impl, "dbChangeGovernDal", dbChangeGovernDal);
        ReflectionTestUtils.setField(impl, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(impl, "logicalDbDal", logicalDbDal);
        ReflectionTestUtils.setField(impl, "logicalDbService", mock(LogicalDbService.class));
        ReflectionTestUtils.setField(impl, "dmAuthServiceForBiz", mock(DmAuthServiceForBiz.class));
        ReflectionTestUtils.setField(impl, "dmEnvParamService", mock(DmEnvParamService.class));
        ReflectionTestUtils.setField(impl, "approvalControlService", mock(ApprovalControlService.class));
        ReflectionTestUtils.setField(impl, "stateMachine", mock(PromotionStateMachine.class));
        ReflectionTestUtils.setField(impl, "txManager", mock(org.springframework.transaction.PlatformTransactionManager.class));

        service = impl;
    }

    @Test
    public void promotionList_returnsPaginatedVOs() {
        Page<DmDbChangePromotionDO> page = new Page<>(1, 10);
        DmDbChangePromotionDO promo = new DmDbChangePromotionDO();
        promo.setId(PROMOTION_ID);
        promo.setPromotionCode("PROMO-001");
        promo.setStatus(PromotionStatus.CREATED.name());
        page.setRecords(List.of(promo));
        page.setTotal(1);
        when(promotionMapper.listPromotionByConditionAndPage(any(), eq(PUID), any(), any()))
            .thenReturn(page);

        GovPromotionListFO fo = new GovPromotionListFO();
        fo.setPage(new com.clougence.clouddm.platform.dal.util.PageObj(1, 10));

        DmPageVO<PromotionVO> result = service.promotionList(PUID, fo);

        assertEquals(1, result.getTotal());
        assertEquals(1, result.getRecords().size());
        assertEquals("PROMO-001", result.getRecords().get(0).getPromotionCode());
    }

    @Test
    public void promotionDetail_returnsFullStructure() {
        DmDbChangePromotionDO promo = buildPromotion();
        when(promotionMapper.selectById(PROMOTION_ID)).thenReturn(promo);

        DmLogicalDbDO logicalDb = new DmLogicalDbDO();
        logicalDb.setId(LOGICAL_DB_ID);
        logicalDb.setCreatorUid(PUID);
        when(logicalDbMapper.selectById(LOGICAL_DB_ID)).thenReturn(logicalDb);

        // Revision
        DmDbChangeRevisionDO revision = new DmDbChangeRevisionDO();
        revision.setId(REVISION_ID);
        revision.setRevisionCode("REV-001");
        revision.setChangeType("DDL");
        revision.setSqlHash("abc123");
        revision.setStmtManifest("[{\"idx\":1,\"stmt_hash\":\"h1\",\"version\":1,\"pre_exec\":\"SUCCESS\"}]");
        when(revisionMapper.selectById(REVISION_ID)).thenReturn(revision);

        // Events: one by promotion_id, one GATE_DENY by revision_id
        DmDbChangeEventDO promoEvent = new DmDbChangeEventDO();
        promoEvent.setId(1L);
        promoEvent.setPromotionId(PROMOTION_ID);
        promoEvent.setEventType(GovEventType.PROMOTION_CREATED.name());
        when(eventMapper.queryByPromotionId(PROMOTION_ID)).thenReturn(List.of(promoEvent));

        DmDbChangeEventDO denyEvent = new DmDbChangeEventDO();
        denyEvent.setId(0L);
        denyEvent.setRevisionId(REVISION_ID);
        denyEvent.setEventType(GovEventType.GATE_DENY.name());
        when(eventMapper.queryByRevisionId(REVISION_ID)).thenReturn(List.of(denyEvent));

        PromotionDetailVO vo = service.promotionDetail(PUID, PROMOTION_ID);

        assertEquals(Long.valueOf(PROMOTION_ID), vo.getId());
        assertEquals("PROMO-001", vo.getPromotionCode());
        assertEquals(PromotionStatus.CREATED.name(), vo.getStatus());
        assertNotNull(vo.getGateResult());
        assertTrue(vo.getPreflightResult().isEmpty());
        assertNotNull(vo.getRevision());
        assertEquals("REV-001", vo.getRevision().getRevisionCode());
        assertEquals(1, vo.getRevision().getStmtCount());

        // Both GATE_DENY and PROMOTION_CREATED events in the chain
        assertEquals(2, vo.getEvents().size());
        assertEquals(GovEventType.GATE_DENY.name(), vo.getEvents().get(0).getEventType());
        assertEquals(GovEventType.PROMOTION_CREATED.name(), vo.getEvents().get(1).getEventType());
    }

    @Test
    public void promotionDetail_crossTenant_notFound() {
        DmDbChangePromotionDO promo = buildPromotion();
        when(promotionMapper.selectById(PROMOTION_ID)).thenReturn(promo);

        DmLogicalDbDO logicalDb = new DmLogicalDbDO();
        logicalDb.setId(LOGICAL_DB_ID);
        logicalDb.setCreatorUid("different-puid");
        when(logicalDbMapper.selectById(LOGICAL_DB_ID)).thenReturn(logicalDb);

        try {
            service.promotionDetail(PUID, PROMOTION_ID);
            fail("Should have thrown");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("not found"));
        }
    }

    @Test
    public void promotionDetail_notFound() {
        when(promotionMapper.selectById(PROMOTION_ID)).thenReturn(null);
        try {
            service.promotionDetail(PUID, PROMOTION_ID);
            fail("Should have thrown");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("not found"));
        }
    }

    @Test
    public void resolveLogicalDbId_success() {
        DmDbChangePromotionDO promo = buildPromotion();
        when(promotionMapper.selectById(PROMOTION_ID)).thenReturn(promo);

        DmLogicalDbDO logicalDb = new DmLogicalDbDO();
        logicalDb.setId(LOGICAL_DB_ID);
        logicalDb.setCreatorUid(PUID);
        when(logicalDbMapper.selectById(LOGICAL_DB_ID)).thenReturn(logicalDb);

        long result = service.resolveLogicalDbId(PUID, PROMOTION_ID);
        assertEquals(LOGICAL_DB_ID, result);
    }

    private DmDbChangePromotionDO buildPromotion() {
        DmDbChangePromotionDO promo = new DmDbChangePromotionDO();
        promo.setId(PROMOTION_ID);
        promo.setPromotionCode("PROMO-001");
        promo.setPromotionType("PRE_PROMOTION");
        promo.setRevisionId(REVISION_ID);
        promo.setLogicalDbId(LOGICAL_DB_ID);
        promo.setProdEnvId(5L);
        promo.setProdDsId(20L);
        promo.setProdResPath("/mydb/");
        promo.setExecutionKey("exec-key");
        promo.setStatus(PromotionStatus.CREATED.name());
        // gate_result: one PASS item
        List<java.util.Map<String, Object>> gate = new ArrayList<>();
        java.util.Map<String, Object> item = new java.util.LinkedHashMap<>();
        item.put("item", 1);
        item.put("label", "test");
        item.put("pass", true);
        item.put("reason", null);
        item.put("timestamp", "now");
        gate.add(item);
        promo.setGateResult(JsonUtils.toJson(gate));
        return promo;
    }
}
