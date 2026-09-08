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
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.component.auth.DmAuthServiceForBiz;
import com.clougence.clouddm.console.web.component.governance.GovSqlHashUtils;
import com.clougence.clouddm.console.web.component.governance.PromotionStateMachine;
import com.clougence.clouddm.console.web.model.fo.governance.GovPromoteFO;
import com.clougence.clouddm.console.web.model.fo.ticket.DmAddTicketFO;
import com.clougence.clouddm.console.web.model.vo.envparam.DmEnvParamTicketDesVO;
import com.clougence.clouddm.console.web.model.vo.logicaldb.LogicalDbTarget;
import com.clougence.clouddm.console.web.model.vo.ticket.DmTicketResultVO;
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
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangeStmtVersionMapper;
import com.clougence.clouddm.platform.dal.mapper.logicaldb.DmLogicalDbMapper;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalType;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangePromotionDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeRevisionDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeStmtVersionDO;
import com.clougence.clouddm.platform.dal.model.dbchange.GovEventType;
import com.clougence.clouddm.platform.dal.model.dbchange.PromotionStatus;
import com.clougence.clouddm.platform.dal.model.logicaldb.DmLogicalDbDO;
import com.clougence.clouddm.platform.dal.model.logicaldb.GovRole;
import com.clougence.utils.JsonUtils;

public class GateOneTest {

    private GovPromotionService   service;

    private DbChangeGovernDal    dbChangeGovernDal;
    private ApprovalDal           approvalDal;
    private LogicalDbDal          logicalDbDal;
    private LogicalDbService     logicalDbService;
    private DmAuthServiceForBiz  dmAuthServiceForBiz;
    private DmEnvParamService     dmEnvParamService;
    private ApprovalControlService approvalControlService;
    private PromotionStateMachine  stateMachine;

    private DmDbChangeRevisionMapper  revisionMapper;
    private DmDbChangeStmtVersionMapper stmtVersionMapper;
    private DmDbChangeEventMapper     eventMapper;
    private DmDbChangePromotionMapper promotionMapper;
    private DmApprovalMapper          approvalMapper;
    private DmLogicalDbMapper         logicalDbMapper;

    private static final String PUID       = "puid-001";
    private static final String UID        = "uid-001";
    private static final long   REVISION_ID = 300L;
    private static final long   LOGICAL_DB_ID = 10L;
    private static final long   TICKET_ID  = 200L;
    private static final long   PROD_ENV_ID = 5L;
    private static final long   PROD_DS_ID  = 20L;
    private static final long   PROMOTION_ID = 500L;

    private static final String RAW_SQL = "CREATE TABLE foo (id INT)";
    private static final String ROLLBACK_SQL = "DROP TABLE foo";

    @Before
    public void setUp() {
        dbChangeGovernDal = mock(DbChangeGovernDal.class);
        approvalDal = mock(ApprovalDal.class);
        logicalDbDal = mock(LogicalDbDal.class);
        logicalDbService = mock(LogicalDbService.class);
        dmAuthServiceForBiz = mock(DmAuthServiceForBiz.class);
        dmEnvParamService = mock(DmEnvParamService.class);
        approvalControlService = mock(ApprovalControlService.class);
        stateMachine = mock(PromotionStateMachine.class);

        revisionMapper = mock(DmDbChangeRevisionMapper.class);
        stmtVersionMapper = mock(DmDbChangeStmtVersionMapper.class);
        eventMapper = mock(DmDbChangeEventMapper.class);
        promotionMapper = mock(DmDbChangePromotionMapper.class);
        approvalMapper = mock(DmApprovalMapper.class);
        logicalDbMapper = mock(DmLogicalDbMapper.class);

        when(dbChangeGovernDal.revisionMapper()).thenReturn(revisionMapper);
        when(dbChangeGovernDal.stmtVersionMapper()).thenReturn(stmtVersionMapper);
        when(dbChangeGovernDal.eventMapper()).thenReturn(eventMapper);
        when(dbChangeGovernDal.promotionMapper()).thenReturn(promotionMapper);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);
        when(logicalDbDal.logicalDbMapper()).thenReturn(logicalDbMapper);

        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        GovPromotionServiceImpl impl = new GovPromotionServiceImpl();
        ReflectionTestUtils.setField(impl, "dbChangeGovernDal", dbChangeGovernDal);
        ReflectionTestUtils.setField(impl, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(impl, "logicalDbDal", logicalDbDal);
        ReflectionTestUtils.setField(impl, "logicalDbService", logicalDbService);
        ReflectionTestUtils.setField(impl, "dmAuthServiceForBiz", dmAuthServiceForBiz);
        ReflectionTestUtils.setField(impl, "dmEnvParamService", dmEnvParamService);
        ReflectionTestUtils.setField(impl, "approvalControlService", approvalControlService);
        ReflectionTestUtils.setField(impl, "stateMachine", stateMachine);
        ReflectionTestUtils.setField(impl, "txManager", txManager);

        service = impl;
    }

    // ======= Gate 1: revision hash integrity =======

    @Test
    public void gate1_hashMismatch_deny() {
        DmDbChangeRevisionDO revision = buildRevision("WRONG_HASH", buildManifest());
        setupAllGatesPassingExcept(revision);

        GovPromoteFO fo = new GovPromoteFO();
        fo.setRevisionId(REVISION_ID);

        try {
            service.promote(PUID, UID, fo);
            fail("Should have thrown");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("gate denied"));
        }

        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        assertEquals(GovEventType.GATE_DENY.name(), eventCaptor.getValue().getEventType());
        verify(promotionMapper, never()).insert(any(DmDbChangePromotionDO.class));
    }

    // ======= Gate 2: source ticket FINISHED =======

    @Test
    public void gate2_sourceTicketNotFinished_deny() {
        DmDbChangeRevisionDO revision = buildRevision(GovSqlHashUtils.hash(RAW_SQL), buildManifest());
        setupAllGatesPassingExcept(revision);
        // Override: ticket NOT finished
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setTicketStatus(ApprovalStatus.EXEC_FAIL);
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket);

        GovPromoteFO fo = new GovPromoteFO();
        fo.setRevisionId(REVISION_ID);

        try {
            service.promote(PUID, UID, fo);
            fail("Should have thrown");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("gate denied"));
        }

        verify(promotionMapper, never()).insert(any(DmDbChangePromotionDO.class));
        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        assertEquals(GovEventType.GATE_DENY.name(), eventCaptor.getValue().getEventType());
    }

    @Test
    public void gate2_manifestPreExecNotSuccess_deny() {
        // DENY matrix ⑤ variant: pre_exec mismatch (manifest pre_exec != SUCCESS)
        List<Map<String, Object>> manifest = new ArrayList<>();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("idx", 1);
        item.put("stmt_hash", "hash-1");
        item.put("version", 1);
        item.put("pre_exec", "FAILED");
        manifest.add(item);

        DmDbChangeRevisionDO revision = buildRevision(GovSqlHashUtils.hash(RAW_SQL), JsonUtils.toJson(manifest));
        setupAllGatesPassingExcept(revision);

        GovPromoteFO fo = new GovPromoteFO();
        fo.setRevisionId(REVISION_ID);

        try {
            service.promote(PUID, UID, fo);
            fail("Should have thrown");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("gate denied"));
        }

        verify(promotionMapper, never()).insert(any(DmDbChangePromotionDO.class));
        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        assertEquals(GovEventType.GATE_DENY.name(), eventCaptor.getValue().getEventType());
    }

    @Test
    public void gate2_manifestVersionHashStale_deny() {
        // DENY matrix ⑤ variant: version mismatch — manifest carries an old version's hash
        // that doesn't match the current stmt_version hash
        DmDbChangeRevisionDO revision = buildRevision(GovSqlHashUtils.hash(RAW_SQL), buildManifest());
        setupAllGatesPassingExcept(revision);
        // Override: stmt_version has a different hash than the manifest
        DmDbChangeStmtVersionDO sv = new DmDbChangeStmtVersionDO();
        sv.setTicketId(TICKET_ID);
        sv.setStmtIndex(1);
        sv.setStmtVersion(2); // newer version with different hash
        sv.setStmtHash("new-hash-version-2");
        when(stmtVersionMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(sv));

        GovPromoteFO fo = new GovPromoteFO();
        fo.setRevisionId(REVISION_ID);

        try {
            service.promote(PUID, UID, fo);
            fail("Should have thrown");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("gate denied"));
        }

        verify(promotionMapper, never()).insert(any(DmDbChangePromotionDO.class));
        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        assertEquals(GovEventType.GATE_DENY.name(), eventCaptor.getValue().getEventType());
    }

    // ======= Gate 3: PROD resource auth =======

    @Test
    public void gate3_authDenied_deny() {
        DmDbChangeRevisionDO revision = buildRevision(GovSqlHashUtils.hash(RAW_SQL), buildManifest());
        setupAllGatesPassingExcept(revision);
        // Override: auth denied
        doThrow(new ErrorMessageException("Auth denied"))
            .when(dmAuthServiceForBiz).checkResAuth(eq(PUID), eq(UID), eq(PROD_DS_ID), any(), any(), any());

        GovPromoteFO fo = new GovPromoteFO();
        fo.setRevisionId(REVISION_ID);

        try {
            service.promote(PUID, UID, fo);
            fail("Should have thrown");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("gate denied"));
        }

        verify(promotionMapper, never()).insert(any(DmDbChangePromotionDO.class));
        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        assertEquals(GovEventType.GATE_DENY.name(), eventCaptor.getValue().getEventType());
    }

    // ======= Gate 4: getBinding(PROD) resolvable =======

    @Test
    public void gate4_noProdBinding_deny() {
        DmDbChangeRevisionDO revision = buildRevision(GovSqlHashUtils.hash(RAW_SQL), buildManifest());
        setupAllGatesPassingExcept(revision);
        // Override: getBinding throws
        when(logicalDbService.getBinding(PUID, LOGICAL_DB_ID, GovRole.PROD))
            .thenThrow(new ErrorMessageException("No PROD binding"));

        GovPromoteFO fo = new GovPromoteFO();
        fo.setRevisionId(REVISION_ID);

        try {
            service.promote(PUID, UID, fo);
            fail("Should have thrown");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("gate denied"));
        }

        verify(promotionMapper, never()).insert(any(DmDbChangePromotionDO.class));
        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        assertEquals(GovEventType.GATE_DENY.name(), eventCaptor.getValue().getEventType());
    }

    // ======= Gate 5: revision not already promoted =======

    @Test
    public void gate5_alreadyPromoted_deny() {
        DmDbChangeRevisionDO revision = buildRevision(GovSqlHashUtils.hash(RAW_SQL), buildManifest());
        setupAllGatesPassingExcept(revision);
        // Override: already promoted
        DmDbChangePromotionDO existing = new DmDbChangePromotionDO();
        existing.setPromotionCode("PROMO-EXISTING");
        when(promotionMapper.queryByRevisionId(REVISION_ID)).thenReturn(existing);

        GovPromoteFO fo = new GovPromoteFO();
        fo.setRevisionId(REVISION_ID);

        try {
            service.promote(PUID, UID, fo);
            fail("Should have thrown");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("gate denied"));
        }

        verify(promotionMapper, never()).insert(any(DmDbChangePromotionDO.class));
        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        assertEquals(GovEventType.GATE_DENY.name(), eventCaptor.getValue().getEventType());
    }

    // ======= Gate 6: DML without rollback =======

    @Test
    public void gate6_dmlWithoutRollback_deny() {
        DmDbChangeRevisionDO revision = buildRevision(GovSqlHashUtils.hash(RAW_SQL), buildManifest());
        revision.setChangeType("DML");
        revision.setRollbackSqlText(null);
        setupAllGatesPassingExcept(revision);

        GovPromoteFO fo = new GovPromoteFO();
        fo.setRevisionId(REVISION_ID);

        try {
            service.promote(PUID, UID, fo);
            fail("Should have thrown");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("gate denied"));
        }

        verify(promotionMapper, never()).insert(any(DmDbChangePromotionDO.class));
        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        assertEquals(GovEventType.GATE_DENY.name(), eventCaptor.getValue().getEventType());
    }

    // ======= Gate 7: PROD approval template not configured (Internal) =======

    @Test
    public void gate7_internalApproval_deny() {
        DmDbChangeRevisionDO revision = buildRevision(GovSqlHashUtils.hash(RAW_SQL), buildManifest());
        setupAllGatesPassingExcept(revision);
        // Override: Internal approval
        DmEnvParamTicketDesVO config = DmEnvParamTicketDesVO.builder()
            .openTicket(true)
            .type(ApprovalType.Internal.name())
            .build();
        when(dmEnvParamService.querySqlTicketInfoParam(PUID, PROD_ENV_ID)).thenReturn(config);

        GovPromoteFO fo = new GovPromoteFO();
        fo.setRevisionId(REVISION_ID);

        try {
            service.promote(PUID, UID, fo);
            fail("Should have thrown");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("gate denied"));
        }

        verify(promotionMapper, never()).insert(any(DmDbChangePromotionDO.class));
        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        assertEquals(GovEventType.GATE_DENY.name(), eventCaptor.getValue().getEventType());
    }

    @Test
    public void gate7_noConfig_deny() {
        DmDbChangeRevisionDO revision = buildRevision(GovSqlHashUtils.hash(RAW_SQL), buildManifest());
        setupAllGatesPassingExcept(revision);
        // Override: no config
        when(dmEnvParamService.querySqlTicketInfoParam(PUID, PROD_ENV_ID)).thenReturn(null);

        GovPromoteFO fo = new GovPromoteFO();
        fo.setRevisionId(REVISION_ID);

        try {
            service.promote(PUID, UID, fo);
            fail("Should have thrown");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("gate denied"));
        }

        verify(promotionMapper, never()).insert(any(DmDbChangePromotionDO.class));
        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(eventCaptor.capture());
        assertEquals(GovEventType.GATE_DENY.name(), eventCaptor.getValue().getEventType());
    }

    // ======= All PASS: success chain =======

    @Test
    public void promote_allPass_successChain() {
        DmDbChangeRevisionDO revision = buildRevision(GovSqlHashUtils.hash(RAW_SQL), buildManifest());
        setupAllGatesPassingExcept(revision);

        // Ticket creation succeeds
        DmTicketResultVO ticketResult = new DmTicketResultVO();
        ticketResult.setTicketId(999L);
        when(approvalControlService.createSqlTicket(eq(PUID), eq(UID), any(DmAddTicketFO.class), eq(ApprovalBiz.DM_CHANGE)))
            .thenReturn(ticketResult);

        // Promotion insert assigns ID
        doAnswer(invocation -> {
            DmDbChangePromotionDO p = invocation.getArgument(0);
            p.setId(PROMOTION_ID);
            return 1;
        }).when(promotionMapper).insert(any(DmDbChangePromotionDO.class));

        // PROD ticket exists for ticketInfo writeback
        DmApprovalDO prodTicket = new DmApprovalDO();
        prodTicket.setId(999L);
        when(approvalMapper.selectById(999L)).thenReturn(prodTicket);

        GovPromoteFO fo = new GovPromoteFO();
        fo.setRevisionId(REVISION_ID);
        fo.setDescription("test promotion");

        long promotionId = service.promote(PUID, UID, fo);

        assertEquals(PROMOTION_ID, promotionId);

        // Verify promotion row fields
        ArgumentCaptor<DmDbChangePromotionDO> promoCaptor = ArgumentCaptor.forClass(DmDbChangePromotionDO.class);
        verify(promotionMapper).insert(promoCaptor.capture());
        DmDbChangePromotionDO promotion = promoCaptor.getValue();
        assertEquals(PromotionStatus.CREATED.name(), promotion.getStatus());
        assertEquals(REVISION_ID, promotion.getRevisionId().longValue());
        assertEquals(LOGICAL_DB_ID, promotion.getLogicalDbId().longValue());
        assertEquals(PROD_ENV_ID, promotion.getProdEnvId().longValue());
        assertEquals(PROD_DS_ID, promotion.getProdDsId().longValue());
        assertNotNull(promotion.getExecutionKey());
        assertNotNull(promotion.getGateResult());
        assertTrue(promotion.getPromotionCode().startsWith("PROMO-"));

        // Verify PROD ticket FO: rawSql = frozen revision (byte-identical)
        ArgumentCaptor<DmAddTicketFO> foCaptor = ArgumentCaptor.forClass(DmAddTicketFO.class);
        verify(approvalControlService).createSqlTicket(eq(PUID), eq(UID), foCaptor.capture(), eq(ApprovalBiz.DM_CHANGE));
        DmAddTicketFO ticketFO = foCaptor.getValue();
        assertEquals(RAW_SQL, ticketFO.getRawSql());
        assertEquals(ROLLBACK_SQL, ticketFO.getRollBackSql());
        assertTrue(ticketFO.getTicketTitle().contains("REV-"));

        // Verify prod_approval_id updated
        verify(promotionMapper).updateProdApprovalId(PROMOTION_ID, 999L);

        // Verify ticketInfo written with governance fields
        ArgumentCaptor<String> ticketInfoCaptor = ArgumentCaptor.forClass(String.class);
        verify(approvalMapper).updateTicketInfo(eq(999L), ticketInfoCaptor.capture());
        ApprovalMO mo = JsonUtils.toObj(ticketInfoCaptor.getValue(), ApprovalMO.class);
        assertEquals(Long.valueOf(PROMOTION_ID), mo.getPromotionId());
        assertEquals(Long.valueOf(REVISION_ID), mo.getRevisionId());
        assertEquals(Long.valueOf(LOGICAL_DB_ID), mo.getLogicalDbId());
        assertEquals(GovRole.PROD.name(), mo.getGovRole());

        // Verify PROMOTION_CREATED event
        ArgumentCaptor<DmDbChangeEventDO> eventCaptor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper, atLeast(1)).insert(eventCaptor.capture());
        boolean foundCreatedEvent = false;
        for (DmDbChangeEventDO event : eventCaptor.getAllValues()) {
            if (GovEventType.PROMOTION_CREATED.name().equals(event.getEventType())) {
                foundCreatedEvent = true;
                assertEquals(Long.valueOf(PROMOTION_ID), event.getPromotionId());
            }
        }
        assertTrue("PROMOTION_CREATED event should be written", foundCreatedEvent);
    }

    // ======= Duplicate key: friendly error =======

    @Test
    public void promote_duplicateRevisionId_friendlyError() {
        DmDbChangeRevisionDO revision = buildRevision(GovSqlHashUtils.hash(RAW_SQL), buildManifest());
        setupAllGatesPassingExcept(revision);

        // First insert throws DuplicateKey (revision_id unique)
        doThrow(new DuplicateKeyException("Duplicate entry"))
            .when(promotionMapper).insert(any(DmDbChangePromotionDO.class));
        // queryByRevisionId: first call (G5 gate check) returns null (pass),
        // second call (after DuplicateKey) returns existing (for the error message)
        DmDbChangePromotionDO existing = new DmDbChangePromotionDO();
        existing.setId(777L);
        when(promotionMapper.queryByRevisionId(REVISION_ID))
            .thenReturn(null)
            .thenReturn(existing);

        GovPromoteFO fo = new GovPromoteFO();
        fo.setRevisionId(REVISION_ID);

        try {
            service.promote(PUID, UID, fo);
            fail("Should have thrown");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("already been promoted"));
        }
    }

    // ======= Duplicate key: execution_key / promotion_code collision retry =======

    @Test
    public void promote_executionKeyCollision_retryThenSuccess() {
        DmDbChangeRevisionDO revision = buildRevision(GovSqlHashUtils.hash(RAW_SQL), buildManifest());
        setupAllGatesPassingExcept(revision);

        // Ticket creation succeeds
        DmTicketResultVO ticketResult = new DmTicketResultVO();
        ticketResult.setTicketId(999L);
        when(approvalControlService.createSqlTicket(eq(PUID), eq(UID), any(DmAddTicketFO.class), eq(ApprovalBiz.DM_CHANGE)))
            .thenReturn(ticketResult);

        DmApprovalDO prodTicket = new DmApprovalDO();
        prodTicket.setId(999L);
        when(approvalMapper.selectById(999L)).thenReturn(prodTicket);

        // First insert throws DuplicateKey (promotion_code collision), second succeeds
        doThrow(new DuplicateKeyException("Duplicate entry 'PROMO-...'"))
            .doAnswer(invocation -> {
                DmDbChangePromotionDO p = invocation.getArgument(0);
                p.setId(PROMOTION_ID);
                return 1;
            })
            .when(promotionMapper).insert(any(DmDbChangePromotionDO.class));

        // queryByRevisionId: G5 gate returns null; after DuplicateKey, also returns null
        // (no existing promotion with this revision_id — collision is on promotion_code)
        when(promotionMapper.queryByRevisionId(REVISION_ID)).thenReturn(null);

        GovPromoteFO fo = new GovPromoteFO();
        fo.setRevisionId(REVISION_ID);
        fo.setDescription("retry test");

        long promotionId = service.promote(PUID, UID, fo);
        assertEquals(PROMOTION_ID, promotionId);

        // Verify insert was called twice (first failed, second succeeded)
        verify(promotionMapper, times(2)).insert(any(DmDbChangePromotionDO.class));
    }

    @Test
    public void promote_codeCollisionRetriesExhausted_friendlyError() {
        DmDbChangeRevisionDO revision = buildRevision(GovSqlHashUtils.hash(RAW_SQL), buildManifest());
        setupAllGatesPassingExcept(revision);

        // All insert attempts throw DuplicateKey (promotion_code keeps colliding)
        doThrow(new DuplicateKeyException("Duplicate entry"))
            .when(promotionMapper).insert(any(DmDbChangePromotionDO.class));

        // queryByRevisionId always null (collision is on code, not revision_id)
        when(promotionMapper.queryByRevisionId(REVISION_ID)).thenReturn(null);

        GovPromoteFO fo = new GovPromoteFO();
        fo.setRevisionId(REVISION_ID);

        try {
            service.promote(PUID, UID, fo);
            fail("Should have thrown");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("Failed to generate unique promotion code"));
        }

        // Verify retry limit (PROMO_CODE_RETRY_MAX = 5)
        verify(promotionMapper, times(5)).insert(any(DmDbChangePromotionDO.class));
    }

    // ======= @JsonAnySetter: smuggle sql/dsId/envId =======

    @Test
    public void promote_smuggleSql_rejectedAtDeserialization() {
        String json = "{\"revisionId\":300,\"sql\":\"DELETE FROM users\"}";
        try {
            JsonUtils.toObj(json, GovPromoteFO.class);
            fail("Should have thrown");
        } catch (Exception e) {
            // expected — IllegalArgumentException from @JsonAnySetter
        }
    }

    // ======= helpers =======

    /**
     * Sets up ALL gate dependencies to PASS, EXCEPT the gate-specific test data
     * is controlled by the revision object passed in.
     */
    private void setupAllGatesPassingExcept(DmDbChangeRevisionDO revision) {
        when(revisionMapper.selectById(REVISION_ID)).thenReturn(revision);

        DmLogicalDbDO logicalDb = new DmLogicalDbDO();
        logicalDb.setId(LOGICAL_DB_ID);
        logicalDb.setResourceCode("ORDER_DB");
        logicalDb.setResourceName("Order DB");
        logicalDb.setStatus("ENABLED");
        logicalDb.setCreatorUid(PUID);
        when(logicalDbMapper.selectById(LOGICAL_DB_ID)).thenReturn(logicalDb);

        // G2: source ticket FINISHED
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setTicketStatus(ApprovalStatus.FINISHED);
        when(approvalMapper.queryById(TICKET_ID)).thenReturn(ticket);

        // G2: stmt_version matching manifest
        DmDbChangeStmtVersionDO sv = new DmDbChangeStmtVersionDO();
        sv.setTicketId(TICKET_ID);
        sv.setStmtIndex(1);
        sv.setStmtVersion(1);
        sv.setStmtHash("hash-1");
        when(stmtVersionMapper.queryByTicketId(TICKET_ID)).thenReturn(List.of(sv));

        // G5: no existing promotion
        when(promotionMapper.queryByRevisionId(REVISION_ID)).thenReturn(null);

        // G3/G4: PROD binding resolvable
        LogicalDbTarget target = new LogicalDbTarget();
        target.setBindingId(1L);
        target.setLogicalDbId(LOGICAL_DB_ID);
        target.setEnvId(PROD_ENV_ID);
        target.setDsId(PROD_DS_ID);
        target.setResPath("/mydb/");
        target.setGovRole(GovRole.PROD);
        when(logicalDbService.getBinding(PUID, LOGICAL_DB_ID, GovRole.PROD)).thenReturn(target);

        // G3: auth passes (no exception thrown = pass)

        // G7: third-party approval configured
        DmEnvParamTicketDesVO config = DmEnvParamTicketDesVO.builder()
            .openTicket(true)
            .type(ApprovalType.DingTalk.name())
            .build();
        when(dmEnvParamService.querySqlTicketInfoParam(PUID, PROD_ENV_ID)).thenReturn(config);
    }

    private DmDbChangeRevisionDO buildRevision(String sqlHash, String manifest) {
        DmDbChangeRevisionDO revision = new DmDbChangeRevisionDO();
        revision.setId(REVISION_ID);
        revision.setRevisionCode("REV-20260907-0001");
        revision.setLogicalDbId(LOGICAL_DB_ID);
        revision.setEnvId(PROD_ENV_ID);
        revision.setSourceTicketId(TICKET_ID);
        revision.setChangeType("DDL");
        revision.setSqlText(RAW_SQL);
        revision.setRollbackSqlText(ROLLBACK_SQL);
        revision.setSqlHash(sqlHash);
        revision.setStmtManifest(manifest);
        return revision;
    }

    private String buildManifest() {
        List<Map<String, Object>> manifest = new ArrayList<>();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("idx", 1);
        item.put("stmt_hash", "hash-1");
        item.put("version", 1);
        item.put("pre_exec", "SUCCESS");
        manifest.add(item);
        return JsonUtils.toJson(manifest);
    }
}
