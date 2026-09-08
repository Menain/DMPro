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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.console.web.component.auth.DmAuthServiceForBiz;
import com.clougence.clouddm.console.web.component.governance.PromotionStateMachine;
import com.clougence.clouddm.console.web.service.envparam.DmEnvParamService;
import com.clougence.clouddm.console.web.model.vo.logicaldb.LogicalDbTarget;
import com.clougence.clouddm.console.web.service.approval.ApprovalControlService;
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
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangePromotionDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeRevisionDO;
import com.clougence.clouddm.platform.dal.model.logicaldb.DmLogicalDbDO;
import com.clougence.clouddm.platform.dal.model.logicaldb.GovRole;
import com.clougence.utils.JsonUtils;

public class AvailableRevisionsTest {

    private GovPromotionService   service;

    private DbChangeGovernDal    dbChangeGovernDal;
    private ApprovalDal           approvalDal;
    private LogicalDbDal          logicalDbDal;
    private LogicalDbService     logicalDbService;
    private DmAuthServiceForBiz  dmAuthServiceForBiz;

    private DmDbChangeRevisionMapper  revisionMapper;
    private DmApprovalMapper          approvalMapper;
    private DmDbChangePromotionMapper promotionMapper;
    private DmLogicalDbMapper         logicalDbMapper;

    private static final String PUID       = "puid-001";
    private static final String UID        = "uid-001";
    private static final long   LOGICAL_DB_ID = 10L;
    private static final long   TICKET_ID  = 200L;
    private static final long   PROD_ENV_ID = 5L;
    private static final long   PROD_DS_ID  = 20L;

    @Before
    public void setUp() {
        dbChangeGovernDal = mock(DbChangeGovernDal.class);
        approvalDal = mock(ApprovalDal.class);
        logicalDbDal = mock(LogicalDbDal.class);
        logicalDbService = mock(LogicalDbService.class);
        dmAuthServiceForBiz = mock(DmAuthServiceForBiz.class);

        revisionMapper = mock(DmDbChangeRevisionMapper.class);
        approvalMapper = mock(DmApprovalMapper.class);
        promotionMapper = mock(DmDbChangePromotionMapper.class);
        logicalDbMapper = mock(DmLogicalDbMapper.class);
        DmDbChangeEventMapper eventMapper = mock(DmDbChangeEventMapper.class);

        when(dbChangeGovernDal.revisionMapper()).thenReturn(revisionMapper);
        when(dbChangeGovernDal.eventMapper()).thenReturn(eventMapper);
        when(dbChangeGovernDal.promotionMapper()).thenReturn(promotionMapper);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);
        when(logicalDbDal.logicalDbMapper()).thenReturn(logicalDbMapper);

        GovPromotionServiceImpl impl = new GovPromotionServiceImpl();
        ReflectionTestUtils.setField(impl, "dbChangeGovernDal", dbChangeGovernDal);
        ReflectionTestUtils.setField(impl, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(impl, "logicalDbDal", logicalDbDal);
        ReflectionTestUtils.setField(impl, "logicalDbService", logicalDbService);
        ReflectionTestUtils.setField(impl, "dmAuthServiceForBiz", dmAuthServiceForBiz);
        ReflectionTestUtils.setField(impl, "stateMachine", mock(PromotionStateMachine.class));
        ReflectionTestUtils.setField(impl, "approvalControlService", mock(ApprovalControlService.class));
        ReflectionTestUtils.setField(impl, "dmEnvParamService", mock(DmEnvParamService.class));
        ReflectionTestUtils.setField(impl, "txManager", mock(org.springframework.transaction.PlatformTransactionManager.class));

        service = impl;
    }

    @Test
    public void availableRevisions_allFiltersPassed_returned() {
        DmDbChangeRevisionDO rev = buildRevision(1L, LOGICAL_DB_ID, "DDL");
        when(revisionMapper.listByTenant(PUID)).thenReturn(new ArrayList<>(List.of(rev)));
        setupLogicalDb(LOGICAL_DB_ID, "ENABLED", PUID);
        setupTicketFinished(TICKET_ID);
        when(promotionMapper.queryByRevisionId(1L)).thenReturn(null);
        setupProdBinding(LOGICAL_DB_ID);
        when(dmAuthServiceForBiz.checkResAuthWithoutError(eq(PUID), eq(UID), eq(PROD_DS_ID), any(), any(), any()))
            .thenReturn(true);

        var result = service.availableRevisions(PUID, UID);
        assertEquals(1, result.size());
        assertEquals(Long.valueOf(1L), result.get(0).getRevisionId());
    }

    @Test
    public void availableRevisions_sourceTicketNotFinished_excluded() {
        DmDbChangeRevisionDO rev = buildRevision(1L, LOGICAL_DB_ID, "DDL");
        when(revisionMapper.listByTenant(PUID)).thenReturn(new ArrayList<>(List.of(rev)));
        setupLogicalDb(LOGICAL_DB_ID, "ENABLED", PUID);
        setupTicketNotFinished(TICKET_ID);
        when(promotionMapper.queryByRevisionId(1L)).thenReturn(null);
        setupProdBinding(LOGICAL_DB_ID);

        var result = service.availableRevisions(PUID, UID);
        assertTrue(result.isEmpty());
    }

    @Test
    public void availableRevisions_manifestNotAllSuccess_excluded() {
        DmDbChangeRevisionDO rev = buildRevision(1L, LOGICAL_DB_ID, "DDL");
        rev.setStmtManifest(buildManifestWithFail());
        when(revisionMapper.listByTenant(PUID)).thenReturn(new ArrayList<>(List.of(rev)));
        setupLogicalDb(LOGICAL_DB_ID, "ENABLED", PUID);
        setupTicketFinished(TICKET_ID);
        when(promotionMapper.queryByRevisionId(1L)).thenReturn(null);
        setupProdBinding(LOGICAL_DB_ID);

        var result = service.availableRevisions(PUID, UID);
        assertTrue(result.isEmpty());
    }

    @Test
    public void availableRevisions_alreadyPromoted_excluded() {
        DmDbChangeRevisionDO rev = buildRevision(1L, LOGICAL_DB_ID, "DDL");
        when(revisionMapper.listByTenant(PUID)).thenReturn(new ArrayList<>(List.of(rev)));
        setupLogicalDb(LOGICAL_DB_ID, "ENABLED", PUID);
        setupTicketFinished(TICKET_ID);
        DmDbChangePromotionDO existing = new DmDbChangePromotionDO();
        when(promotionMapper.queryByRevisionId(1L)).thenReturn(existing);

        var result = service.availableRevisions(PUID, UID);
        assertTrue(result.isEmpty());
    }

    @Test
    public void availableRevisions_logicalDbDisabled_excluded() {
        DmDbChangeRevisionDO rev = buildRevision(1L, LOGICAL_DB_ID, "DDL");
        when(revisionMapper.listByTenant(PUID)).thenReturn(new ArrayList<>(List.of(rev)));
        setupLogicalDb(LOGICAL_DB_ID, "DISABLED", PUID);
        setupTicketFinished(TICKET_ID);

        var result = service.availableRevisions(PUID, UID);
        assertTrue(result.isEmpty());
    }

    @Test
    public void availableRevisions_noProdBinding_excluded() {
        DmDbChangeRevisionDO rev = buildRevision(1L, LOGICAL_DB_ID, "DDL");
        when(revisionMapper.listByTenant(PUID)).thenReturn(new ArrayList<>(List.of(rev)));
        setupLogicalDb(LOGICAL_DB_ID, "ENABLED", PUID);
        setupTicketFinished(TICKET_ID);
        when(promotionMapper.queryByRevisionId(1L)).thenReturn(null);
        when(logicalDbService.getBinding(PUID, LOGICAL_DB_ID, GovRole.PROD))
            .thenThrow(new ErrorMessageException("No PROD binding"));

        var result = service.availableRevisions(PUID, UID);
        assertTrue(result.isEmpty());
    }

    @Test
    public void availableRevisions_authDenied_excluded() {
        DmDbChangeRevisionDO rev = buildRevision(1L, LOGICAL_DB_ID, "DDL");
        when(revisionMapper.listByTenant(PUID)).thenReturn(new ArrayList<>(List.of(rev)));
        setupLogicalDb(LOGICAL_DB_ID, "ENABLED", PUID);
        setupTicketFinished(TICKET_ID);
        when(promotionMapper.queryByRevisionId(1L)).thenReturn(null);
        setupProdBinding(LOGICAL_DB_ID);
        when(dmAuthServiceForBiz.checkResAuthWithoutError(eq(PUID), eq(UID), eq(PROD_DS_ID), any(), any(), any()))
            .thenReturn(false);

        var result = service.availableRevisions(PUID, UID);
        assertTrue(result.isEmpty());
    }

    @Test
    public void availableRevisions_emptyRevisions_returnedEmpty() {
        when(revisionMapper.listByTenant(PUID)).thenReturn(new ArrayList<>());
        var result = service.availableRevisions(PUID, UID);
        assertTrue(result.isEmpty());
    }

    // ======= helpers =======

    private DmDbChangeRevisionDO buildRevision(long revId, long logicalDbId, String changeType) {
        DmDbChangeRevisionDO rev = new DmDbChangeRevisionDO();
        rev.setId(revId);
        rev.setRevisionCode("REV-001");
        rev.setLogicalDbId(logicalDbId);
        rev.setSourceTicketId(TICKET_ID);
        rev.setChangeType(changeType);
        rev.setSqlText("CREATE TABLE foo (id INT)");
        rev.setSqlHash("someHash");
        rev.setStmtManifest(buildManifestAllSuccess());
        return rev;
    }

    private String buildManifestAllSuccess() {
        List<Map<String, Object>> manifest = new ArrayList<>();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("idx", 1);
        item.put("stmt_hash", "hash-1");
        item.put("version", 1);
        item.put("pre_exec", "SUCCESS");
        manifest.add(item);
        return JsonUtils.toJson(manifest);
    }

    private String buildManifestWithFail() {
        List<Map<String, Object>> manifest = new ArrayList<>();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("idx", 1);
        item.put("stmt_hash", "hash-1");
        item.put("version", 1);
        item.put("pre_exec", "FAILED");
        manifest.add(item);
        return JsonUtils.toJson(manifest);
    }

    private void setupLogicalDb(long id, String status, String creatorUid) {
        DmLogicalDbDO db = new DmLogicalDbDO();
        db.setId(id);
        db.setResourceName("Test DB");
        db.setStatus(status);
        db.setCreatorUid(creatorUid);
        when(logicalDbMapper.selectById(id)).thenReturn(db);
    }

    private void setupTicketFinished(long ticketId) {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(ticketId);
        ticket.setTicketStatus(ApprovalStatus.FINISHED);
        when(approvalMapper.queryById(ticketId)).thenReturn(ticket);
    }

    private void setupTicketNotFinished(long ticketId) {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(ticketId);
        ticket.setTicketStatus(ApprovalStatus.EXEC_FAIL);
        when(approvalMapper.queryById(ticketId)).thenReturn(ticket);
    }

    private void setupProdBinding(long logicalDbId) {
        LogicalDbTarget target = new LogicalDbTarget();
        target.setBindingId(1L);
        target.setLogicalDbId(logicalDbId);
        target.setEnvId(PROD_ENV_ID);
        target.setDsId(PROD_DS_ID);
        target.setResPath("/mydb/");
        target.setGovRole(GovRole.PROD);
        when(logicalDbService.getBinding(PUID, logicalDbId, GovRole.PROD)).thenReturn(target);
    }
}
