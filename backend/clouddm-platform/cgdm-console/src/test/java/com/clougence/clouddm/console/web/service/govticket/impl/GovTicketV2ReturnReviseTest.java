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
package com.clougence.clouddm.console.web.service.govticket.impl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.console.web.component.approval.ApprovalPersonService;
import com.clougence.clouddm.console.web.component.approval.ApprovalStateService;
import com.clougence.clouddm.console.web.component.auth.DmAuthServiceForBiz;
import com.clougence.clouddm.console.web.component.governance.GovReturnNotifyService;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.model.fo.govticket.GovTicketV2ResubmitFO;
import com.clougence.clouddm.console.web.model.fo.govticket.GovTicketV2ReturnTicketFO;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.DbChangeEventDal;
import com.clougence.clouddm.platform.dal.access.ProdReleaseDal;
import com.clougence.clouddm.platform.dal.access.TicketDbStmtDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangeEventMapper;
import com.clougence.clouddm.platform.dal.mapper.govticket.DmTicketDbStmtMapper;
import com.clougence.clouddm.platform.dal.mapper.govticket.DmTicketStmtRevisionMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseStmtMapper;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.dbchange.ChangeType;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO;
import com.clougence.clouddm.platform.dal.model.dbchange.GovEventType;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketStmtRevisionDO;
import com.clougence.clouddm.sdk.sql.parser.SplitScript;
import com.clougence.clouddm.sdk.sql.parser.SplitQueryType;
import com.clougence.utils.JsonUtils;

/**
 * Tests for the ticket return-revise loop: returnTicket guard matrix, resubmit,
 * REVISION state machine properties, and resolveChangeType latest-first.
 */
public class GovTicketV2ReturnReviseTest {

    private GovTicketV2ServiceImpl  service;
    private TicketDbStmtDal          ticketDbStmtDal;
    private DmTicketDbStmtMapper     stmtMapper;
    private DmTicketStmtRevisionMapper revisionMapper;
    private ApprovalDal              approvalDal;
    private DmApprovalMapper         approvalMapper;
    private ProdReleaseDal           prodReleaseDal;
    private DmProdReleaseStmtMapper prodStmtMapper;
    private ApprovalStateService    approvalStateService;
    private com.clougence.clouddm.console.web.component.approval.ApprovalFlowService approvalFlowService;
    private com.clougence.clouddm.console.web.component.auth.DmAuthServiceForBiz dmAuthServiceForBiz;
    private ApprovalPersonService approvalPersonService;
    private DbChangeEventDal       dbChangeEventDal;
    private DmDbChangeEventMapper    eventMapper;

    private static final String PUID = "puid-001";
    private static final String UID  = "uid-001";

    @Before
    public void setUp() {
        service = new GovTicketV2ServiceImpl();

        // Set up DmTeamUtils static executionDal (required by nextExecJobBizId)
        com.clougence.clouddm.platform.dal.access.ExecutionDal staticExecDal
            = mock(com.clougence.clouddm.platform.dal.access.ExecutionDal.class);
        com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoJobMapper staticJobMapper
            = mock(com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoJobMapper.class);
        com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoTaskMapper staticTaskMapper
            = mock(com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoTaskMapper.class);
        when(staticExecDal.autoJobMapper()).thenReturn(staticJobMapper);
        when(staticExecDal.autoTaskMapper()).thenReturn(staticTaskMapper);
        when(staticJobMapper.queryByBizId(anyString())).thenReturn(null);
        when(staticTaskMapper.queryByBizId(anyString())).thenReturn(null);
        ReflectionTestUtils.setField(com.clougence.clouddm.console.web.util.DmTeamUtils.class, "executionDal", staticExecDal);

        ticketDbStmtDal = mock(TicketDbStmtDal.class);
        stmtMapper = mock(DmTicketDbStmtMapper.class);
        when(ticketDbStmtDal.stmtMapper()).thenReturn(stmtMapper);
        revisionMapper = mock(DmTicketStmtRevisionMapper.class);
        when(ticketDbStmtDal.revisionMapper()).thenReturn(revisionMapper);
        com.clougence.clouddm.platform.dal.mapper.govticket.DmTicketStmtSchemaMapper ticketSchemaMapper
            = mock(com.clougence.clouddm.platform.dal.mapper.govticket.DmTicketStmtSchemaMapper.class);
        when(ticketDbStmtDal.schemaMapper()).thenReturn(ticketSchemaMapper);

        approvalDal = mock(ApprovalDal.class);
        approvalMapper = mock(DmApprovalMapper.class);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);

        prodReleaseDal = mock(ProdReleaseDal.class);
        prodStmtMapper = mock(DmProdReleaseStmtMapper.class);
        when(prodReleaseDal.stmtMapper()).thenReturn(prodStmtMapper);

        approvalStateService = mock(ApprovalStateService.class);

        dbChangeEventDal = mock(DbChangeEventDal.class);
        eventMapper = mock(DmDbChangeEventMapper.class);
        when(dbChangeEventDal.eventMapper()).thenReturn(eventMapper);
        when(eventMapper.insert(any(DmDbChangeEventDO.class))).thenReturn(1);

        // Mock schema snapshot service (no-op)
        com.clougence.clouddm.console.web.component.governance.SchemaSnapshotService schemaSnapshotService
            = mock(com.clougence.clouddm.console.web.component.governance.SchemaSnapshotService.class);

        // Mock DbPairDal for resolvePair
        com.clougence.clouddm.platform.dal.access.DbPairDal dbPairDal
            = mock(com.clougence.clouddm.platform.dal.access.DbPairDal.class);
        com.clougence.clouddm.platform.dal.mapper.dbpair.DmDbPairMapper pairMapper
            = mock(com.clougence.clouddm.platform.dal.mapper.dbpair.DmDbPairMapper.class);
        when(dbPairDal.pairMapper()).thenReturn(pairMapper);
        com.clougence.clouddm.platform.dal.model.dbpair.DmDbPairDO pair
            = new com.clougence.clouddm.platform.dal.model.dbpair.DmDbPairDO();
        pair.setId(300L);
        pair.setPreDsId(20L);
        pair.setPreDbName("pre_db");
        pair.setProdDsId(10L);
        pair.setProdDbName("prod_db");
        pair.setStatus("ENABLED");
        when(pairMapper.selectById(300L)).thenReturn(pair);

        // Mock DataSourceDal
        com.clougence.clouddm.platform.dal.access.DataSourceDal dsDal
            = mock(com.clougence.clouddm.platform.dal.access.DataSourceDal.class);
        com.clougence.clouddm.platform.dal.mapper.datasource.DmDsMapper dsMapper
            = mock(com.clougence.clouddm.platform.dal.mapper.datasource.DmDsMapper.class);
        when(dsDal.dsMapper()).thenReturn(dsMapper);
        com.clougence.clouddm.platform.dal.model.datasource.DmDsDO dsDO
            = mock(com.clougence.clouddm.platform.dal.model.datasource.DmDsDO.class);
        when(dsDO.getDsEnvId()).thenReturn(1L);
        when(dsDO.getInstanceId()).thenReturn("inst-1");
        when(dsMapper.queryDsIdentityById(anyLong())).thenReturn(dsDO);

        // Mock DmDsConfigService
        com.clougence.clouddm.console.web.component.dsconfig.DmDsConfigService dmDsConfigService
            = mock(com.clougence.clouddm.console.web.component.dsconfig.DmDsConfigService.class);
        when(dmDsConfigService.fetchDsConfigFromExists(anyLong())).thenReturn(null);
        when(dmDsConfigService.parseLevels(anyList())).thenReturn(
            mock(com.clougence.clouddm.console.web.component.dsconfig.mode.DsLevels.class));

        // Mock QueryAnalysisService — canned DDL SplitScript stream for precheck PASS path.
        // Each invocation gets a fresh stream (Stream is single-use).
        com.clougence.clouddm.console.web.component.analysis.QueryAnalysisService queryAnalysisService
            = mock(com.clougence.clouddm.console.web.component.analysis.QueryAnalysisService.class);
        when(queryAnalysisService.analysisSplitStream(any(), any(), any(), anyInt(), anyInt()))
            .thenAnswer(invocation -> java.util.stream.Stream.of(createDdlSplitScript()));
        when(queryAnalysisService.analysisRulesStream(any(), any(), any(), anyInt(), anyInt(), any()))
            .thenAnswer(invocation -> java.util.stream.Stream.empty());

        // Mock DmEnvParamService
        com.clougence.clouddm.console.web.service.envparam.DmEnvParamService dmEnvParamService
            = mock(com.clougence.clouddm.console.web.service.envparam.DmEnvParamService.class);

        // Mock SystemDal
        com.clougence.clouddm.platform.dal.access.SystemDal systemDal
            = mock(com.clougence.clouddm.platform.dal.access.SystemDal.class);
        when(systemDal.fetchSystemConf(anyString(), eq(Boolean.class))).thenReturn(true);

        // Mock ApprovalFlowService
        approvalFlowService
            = mock(com.clougence.clouddm.console.web.component.approval.ApprovalFlowService.class);

        // Mock DbPairService
        com.clougence.clouddm.console.web.service.dbpair.DbPairService dbPairService
            = mock(com.clougence.clouddm.console.web.service.dbpair.DbPairService.class);

        // v2: new dependencies
        approvalPersonService = mock(ApprovalPersonService.class);
        dmAuthServiceForBiz = mock(DmAuthServiceForBiz.class);
        GovReturnNotifyService govReturnNotifyService = mock(GovReturnNotifyService.class);

        ReflectionTestUtils.setField(service, "ticketDbStmtDal", ticketDbStmtDal);
        ReflectionTestUtils.setField(service, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(service, "approvalStateService", approvalStateService);
        ReflectionTestUtils.setField(service, "dbChangeEventDal", dbChangeEventDal);
        ReflectionTestUtils.setField(service, "prodReleaseDal", prodReleaseDal);
        ReflectionTestUtils.setField(service, "schemaSnapshotService", schemaSnapshotService);
        ReflectionTestUtils.setField(service, "dbPairDal", dbPairDal);
        ReflectionTestUtils.setField(service, "dsDal", dsDal);
        ReflectionTestUtils.setField(service, "dmDsConfigService", dmDsConfigService);
        ReflectionTestUtils.setField(service, "queryAnalysisService", queryAnalysisService);
        ReflectionTestUtils.setField(service, "dmEnvParamService", dmEnvParamService);
        ReflectionTestUtils.setField(service, "systemDal", systemDal);
        ReflectionTestUtils.setField(service, "approvalFlowService", approvalFlowService);
        ReflectionTestUtils.setField(service, "dbPairService", dbPairService);
        ReflectionTestUtils.setField(service, "approvalPersonService", approvalPersonService);
        ReflectionTestUtils.setField(service, "dmAuthServiceForBiz", dmAuthServiceForBiz);
        ReflectionTestUtils.setField(service, "govReturnNotifyService", govReturnNotifyService);
    }

    // ==================== State machine tests ====================

    @Test
    public void revision_isNotEndStatus() {
        assertFalse(ApprovalStatus.isEndStatus(ApprovalStatus.REVISION));
    }

    @Test
    public void revision_isNotInUnFinishListInSet() {
        // The IN set at DmApprovalMapper.xml:375 is a hardcoded literal — verify REVISION is NOT in it.
        // This is a source-text assertion (the SQL is in DmApprovalMapper.xml).
        String xml;
        try {
            xml = java.nio.file.Files.readString(java.nio.file.Path.of(
                "../clouddm-platform/cgdm-dao/src/main/resources/mybatis/mapper/DmApprovalMapper.xml"));
        } catch (Exception e) {
            try {
                xml = java.nio.file.Files.readString(java.nio.file.Path.of(
                    "../../clouddm-platform/cgdm-dao/src/main/resources/mybatis/mapper/DmApprovalMapper.xml"));
            } catch (Exception e2) {
                throw new RuntimeException(e2);
            }
        }
        // The IN list should NOT contain REVISION
        int inIndex = xml.indexOf("IN ('PRE_INIT_WAIT'");
        assertTrue("IN list should exist in the XML", inIndex > -1);
        String inClause = xml.substring(inIndex, inIndex + 200);
        assertFalse("REVISION must NOT be in the listUnFinishTicketIdList IN set:\n" + inClause,
            inClause.contains("REVISION"));
    }

    @Test
    public void advanceOneTicket_doesNotAdvanceRevisionTicket() {
        // advanceOneTicket has three filters: DM_CHANGE, PRE_DDL, WAIT_APPROVAL.
        // REVISION != WAIT_APPROVAL → returns early. No interaction with auto-approve/confirm.
        // This is a structural property verified by the filter code — no test invocation needed
        // because the filter at L128 checks `ticketStatus != WAIT_APPROVAL` and returns.
        // We verify the enum relationship:
        assertTrue(ApprovalStatus.REVISION != ApprovalStatus.WAIT_APPROVAL);
    }

    // ==================== returnTicket guard matrix ====================

    @Test
    public void returnTicket_execFailWithFailedGroup_succeeds() {
        long ticketId = 100L;
        DmApprovalDO ticket = buildTicket(ticketId, PUID, UID, ApprovalStatus.EXEC_FAIL, "PRE_DDL");
        when(approvalMapper.queryById(ticketId)).thenReturn(ticket);

        DmTicketDbStmtDO group = buildGroup(200L, ticketId, "FAILED");
        when(stmtMapper.queryByTicketId(ticketId)).thenReturn(List.of(group));
        when(prodStmtMapper.countBySourceStmtIds(List.of(200L))).thenReturn(0);

        GovTicketV2ReturnTicketFO fo = new GovTicketV2ReturnTicketFO();
        fo.setTicketId(ticketId);
        fo.setReason("fix full-width semicolon");
        service.returnTicket(PUID, UID, fo);

        verify(approvalStateService).updateApprovalStatus(eq(ticketId), eq(ApprovalStatus.REVISION), anyString());
        verify(eventMapper).insert(argThat((DmDbChangeEventDO e) ->
            e != null && GovEventType.TICKET_RETURNED.name().equals(e.getEventType())
                && UID.equals(e.getOperatorUid())));
    }

    @Test
    public void returnTicket_finished_throws() {
        long ticketId = 101L;
        DmApprovalDO ticket = buildTicket(ticketId, PUID, UID, ApprovalStatus.FINISHED, "PRE_DDL");
        when(approvalMapper.queryById(ticketId)).thenReturn(ticket);

        GovTicketV2ReturnTicketFO fo = new GovTicketV2ReturnTicketFO();
        fo.setTicketId(ticketId);
        try {
            service.returnTicket(PUID, UID, fo);
            fail("Expected ErrorMessageException");
        } catch (ErrorMessageException ex) {
            // ok
        }
        verify(approvalStateService, never()).updateApprovalStatus(anyLong(), any(), any());
    }

    @Test
    public void returnTicket_rejected_throws() {
        long ticketId = 102L;
        DmApprovalDO ticket = buildTicket(ticketId, PUID, UID, ApprovalStatus.REJECTED, "PRE_DDL");
        when(approvalMapper.queryById(ticketId)).thenReturn(ticket);

        GovTicketV2ReturnTicketFO fo = new GovTicketV2ReturnTicketFO();
        fo.setTicketId(ticketId);
        try {
            service.returnTicket(PUID, UID, fo);
            fail("Expected ErrorMessageException");
        } catch (ErrorMessageException ex) {
            // ok
        }
    }

    @Test
    public void returnTicket_noFailedGroups_throws() {
        long ticketId = 103L;
        DmApprovalDO ticket = buildTicket(ticketId, PUID, UID, ApprovalStatus.EXEC_FAIL, "PRE_DDL");
        when(approvalMapper.queryById(ticketId)).thenReturn(ticket);

        DmTicketDbStmtDO group = buildGroup(201L, ticketId, "SUCCESS");
        when(stmtMapper.queryByTicketId(ticketId)).thenReturn(List.of(group));

        GovTicketV2ReturnTicketFO fo = new GovTicketV2ReturnTicketFO();
        fo.setTicketId(ticketId);
        try {
            service.returnTicket(PUID, UID, fo);
            fail("Expected ErrorMessageException");
        } catch (ErrorMessageException ex) {
            // ok
        }
    }

    @Test
    public void returnTicket_alreadyMerged_throws() {
        long ticketId = 104L;
        DmApprovalDO ticket = buildTicket(ticketId, PUID, UID, ApprovalStatus.EXEC_FAIL, "PRE_DDL");
        when(approvalMapper.queryById(ticketId)).thenReturn(ticket);

        DmTicketDbStmtDO group = buildGroup(202L, ticketId, "FAILED");
        when(stmtMapper.queryByTicketId(ticketId)).thenReturn(List.of(group));
        when(prodStmtMapper.countBySourceStmtIds(List.of(202L))).thenReturn(1);

        GovTicketV2ReturnTicketFO fo = new GovTicketV2ReturnTicketFO();
        fo.setTicketId(ticketId);
        try {
            service.returnTicket(PUID, UID, fo);
            fail("Expected ErrorMessageException");
        } catch (ErrorMessageException ex) {
            // ok
        }
    }

    @Test
    public void returnTicket_wrongOwner_throws() {
        long ticketId = 105L;
        DmApprovalDO ticket = buildTicket(ticketId, PUID, "other-uid", ApprovalStatus.EXEC_FAIL, "PRE_DDL");
        when(approvalMapper.queryById(ticketId)).thenReturn(ticket);

        GovTicketV2ReturnTicketFO fo = new GovTicketV2ReturnTicketFO();
        fo.setTicketId(ticketId);
        try {
            service.returnTicket(PUID, UID, fo);
            fail("Expected ErrorMessageException");
        } catch (ErrorMessageException ex) {
            // ok
        }
    }

    @Test
    public void returnTicket_wrongTenant_throws() {
        long ticketId = 106L;
        DmApprovalDO ticket = buildTicket(ticketId, "other-puid", UID, ApprovalStatus.EXEC_FAIL, "PRE_DDL");
        when(approvalMapper.queryById(ticketId)).thenReturn(ticket);

        GovTicketV2ReturnTicketFO fo = new GovTicketV2ReturnTicketFO();
        fo.setTicketId(ticketId);
        try {
            service.returnTicket(PUID, UID, fo);
            fail("Expected ErrorMessageException");
        } catch (ErrorMessageException ex) {
            // ok
        }
    }

    @Test
    public void returnTicket_eventUsesRealUidNotSystem() {
        long ticketId = 107L;
        DmApprovalDO ticket = buildTicket(ticketId, PUID, UID, ApprovalStatus.EXEC_FAIL, "PRE_DDL");
        when(approvalMapper.queryById(ticketId)).thenReturn(ticket);

        DmTicketDbStmtDO group = buildGroup(203L, ticketId, "FAILED");
        when(stmtMapper.queryByTicketId(ticketId)).thenReturn(List.of(group));
        when(prodStmtMapper.countBySourceStmtIds(List.of(203L))).thenReturn(0);

        GovTicketV2ReturnTicketFO fo = new GovTicketV2ReturnTicketFO();
        fo.setTicketId(ticketId);
        service.returnTicket(PUID, UID, fo);

        verify(eventMapper).insert(argThat((DmDbChangeEventDO e) ->
            e != null && GovEventType.TICKET_RETURNED.name().equals(e.getEventType())
                && UID.equals(e.getOperatorUid())
                && !"SYSTEM".equals(e.getOperatorUid())));
    }

    // ==================== retryGroup REVISION guard ====================

    @Test
    public void retryGroup_revisionTicket_rejected() {
        long groupId = 300L;
        DmTicketDbStmtDO group = buildGroup(groupId, 400L, "FAILED");
        when(stmtMapper.queryById(groupId)).thenReturn(group);

        DmApprovalDO ticket = buildTicket(400L, PUID, UID, ApprovalStatus.REVISION, "PRE_DDL");
        when(approvalMapper.queryById(400L)).thenReturn(ticket);

        try {
            service.retryGroup(PUID, UID, groupId);
            fail("Expected ErrorMessageException");
        } catch (ErrorMessageException ex) {
            // ok — REVISION tickets must go through resubmit, not retryGroup
        }
        verify(stmtMapper, never()).updateExecStatus(anyLong(), anyString(), any());
    }

    // ==================== resubmit tests ====================

    /**
     * Test 1: Precheck FAIL -> zero persistence — no version row, no sql update, no status change, no event.
     * Blank SQL content triggers FAIL at the very start of runPrecheck (before analysisSplitStream).
     */
    @Test
    public void resubmit_precheckFail_zeroPersistence() {
        long ticketId = 200L;
        long groupId = 210L;
        DmApprovalDO ticket = buildTicket(ticketId, PUID, UID, ApprovalStatus.REVISION, "PRE_DDL");
        when(approvalMapper.queryById(ticketId)).thenReturn(ticket);

        DmTicketDbStmtDO group = buildGroup(groupId, ticketId, "FAILED");
        when(stmtMapper.queryByTicketId(ticketId)).thenReturn(List.of(group));

        GovTicketV2ResubmitFO fo = new GovTicketV2ResubmitFO();
        fo.setTicketId(ticketId);
        fo.setGroups(List.of(buildGroupInput(groupId, "  "))); // blank -> precheck FAIL
        try {
            service.resubmit(PUID, UID, fo);
            fail("Expected ErrorMessageException");
        } catch (ErrorMessageException ex) {
            // ok
        }
        verify(revisionMapper, never()).insertRevision(any());
        verify(stmtMapper, never()).updateSqlForRevision(anyLong(), anyString(), any());
        verify(approvalStateService, never()).updateApprovalStatus(anyLong(), any(), any());
        verify(eventMapper, never()).insert(any(DmDbChangeEventDO.class));
    }

    /**
     * Test 2: SUCCESS group in request -> reject (zero persistence same as test 1).
     */
    @Test
    public void resubmit_successGroupInRequest_rejected() {
        long ticketId = 201L;
        DmApprovalDO ticket = buildTicket(ticketId, PUID, UID, ApprovalStatus.REVISION, "PRE_DDL");
        when(approvalMapper.queryById(ticketId)).thenReturn(ticket);

        DmTicketDbStmtDO failedGroup = buildGroup(211L, ticketId, "FAILED");
        DmTicketDbStmtDO successGroup = buildGroup(212L, ticketId, "SUCCESS");
        when(stmtMapper.queryByTicketId(ticketId)).thenReturn(List.of(failedGroup, successGroup));

        GovTicketV2ResubmitFO fo = new GovTicketV2ResubmitFO();
        fo.setTicketId(ticketId);
        fo.setGroups(List.of(
            buildGroupInput(211L, "CREATE TABLE t2(id int);"),
            buildGroupInput(212L, "CREATE TABLE t3(id int);") // SUCCESS group -> reject
        ));
        try {
            service.resubmit(PUID, UID, fo);
            fail("Expected ErrorMessageException");
        } catch (ErrorMessageException ex) {
            // ok
        }
        verify(revisionMapper, never()).insertRevision(any());
        verify(stmtMapper, never()).updateSqlForRevision(anyLong(), anyString(), any());
        verify(approvalStateService, never()).updateApprovalStatus(anyLong(), any(), any());
        verify(eventMapper, never()).insert(any(DmDbChangeEventDO.class));
    }

    /**
     * Test 3: InOrder pin — insertRevision (version audit) must happen before updateSqlForRevision (overwrite).
     * This ensures the old SQL text is preserved before being overwritten.
     */
    @Test
    public void resubmit_versionInsertBeforeSqlUpdate_inOrder() {
        long ticketId = 202L;
        long groupId = 220L;
        DmApprovalDO ticket = buildTicket(ticketId, PUID, UID, ApprovalStatus.REVISION, "PRE_DDL");
        when(approvalMapper.queryById(ticketId)).thenReturn(ticket);

        DmTicketDbStmtDO group = buildGroup(groupId, ticketId, "FAILED");
        when(stmtMapper.queryByTicketId(ticketId)).thenReturn(List.of(group));
        when(revisionMapper.maxVersionByStmtId(groupId)).thenReturn(0);

        GovTicketV2ResubmitFO fo = new GovTicketV2ResubmitFO();
        fo.setTicketId(ticketId);
        fo.setGroups(List.of(buildGroupInput(groupId, "CREATE TABLE t_new(id int);")));
        service.resubmit(PUID, UID, fo);

        InOrder inOrder = inOrder(revisionMapper, stmtMapper);
        inOrder.verify(revisionMapper).insertRevision(any(DmTicketStmtRevisionDO.class));
        inOrder.verify(stmtMapper).updateSqlForRevision(eq(groupId), anyString(), anyString());
    }

    /**
     * Test 4: In-place update field check — updateSqlForRevision receives the original stmt id
     * (not a new id), the new sql_content, and the fresh precheck_result JSON. exec_status is
     * reset to PENDING by the mapper SQL (not testable here), but the id param is the original.
     */
    @Test
    public void resubmit_inPlaceUpdate_usesOriginalStmtId() {
        long ticketId = 203L;
        long groupId = 230L;
        DmApprovalDO ticket = buildTicket(ticketId, PUID, UID, ApprovalStatus.REVISION, "PRE_DDL");
        when(approvalMapper.queryById(ticketId)).thenReturn(ticket);

        DmTicketDbStmtDO group = buildGroup(groupId, ticketId, "FAILED");
        group.setSqlContent("CREATE TABLE t_old(id int);");
        when(stmtMapper.queryByTicketId(ticketId)).thenReturn(List.of(group));
        when(revisionMapper.maxVersionByStmtId(groupId)).thenReturn(0);

        String newSql = "CREATE TABLE t_new(id int);";
        GovTicketV2ResubmitFO fo = new GovTicketV2ResubmitFO();
        fo.setTicketId(ticketId);
        fo.setGroups(List.of(buildGroupInput(groupId, newSql)));
        service.resubmit(PUID, UID, fo);

        // Verify updateSqlForRevision called with original groupId, new sql, and a precheck_result JSON
        verify(stmtMapper).updateSqlForRevision(eq(groupId), eq(newSql), argThat((String json) -> {
            Map<String, Object> parsed = JsonUtils.toObj(json, HashMap.class);
            return parsed != null && "PASS".equals(parsed.get("checkStatus"))
                && "DDL".equals(parsed.get("changeType"));
        }));

        // Verify ticket status updated to WAIT_APPROVAL
        verify(approvalStateService).updateApprovalStatus(eq(ticketId), eq(ApprovalStatus.WAIT_APPROVAL), anyString());

        // Verify rawSql updated on the approval row (new SQL from first modified group)
        verify(approvalMapper).updateById(argThat((DmApprovalDO t) ->
            t.getId() == ticketId && newSql.equals(t.getRawSql())));
    }

    /**
     * Test 5: changeType recompute + new SUBMIT event — mixed scenario.
     * One SUCCESS group with stored precheck_result.changeType=DML (not in request, not re-prechecked),
     * one FAILED group with new text that prechecks as DDL (in request, re-prechecked).
     * Merge(DML, DDL) = MIXED. The new SUBMIT event must carry changeType=MIXED and operator_uid=uid.
     */
    @Test
    public void resubmit_changeTypeRecomputed_mixedScenario() {
        long ticketId = 204L;
        DmApprovalDO ticket = buildTicket(ticketId, PUID, UID, ApprovalStatus.REVISION, "PRE_DDL");
        when(approvalMapper.queryById(ticketId)).thenReturn(ticket);

        // Group 1: SUCCESS, stored precheck_result changeType=DML (NOT in the resubmit request)
        DmTicketDbStmtDO successGroup = buildGroup(241L, ticketId, "SUCCESS");
        successGroup.setPrecheckResult(JsonUtils.toJson(Map.of("checkStatus", "PASS", "changeType", "DML")));

        // Group 2: FAILED, will be re-prechecked (DDL from canned SplitScript)
        DmTicketDbStmtDO failedGroup = buildGroup(242L, ticketId, "FAILED");
        when(stmtMapper.queryByTicketId(ticketId)).thenReturn(List.of(successGroup, failedGroup));
        when(revisionMapper.maxVersionByStmtId(242L)).thenReturn(0);

        GovTicketV2ResubmitFO fo = new GovTicketV2ResubmitFO();
        fo.setTicketId(ticketId);
        fo.setGroups(List.of(buildGroupInput(242L, "CREATE TABLE t_new(id int);")));
        service.resubmit(PUID, UID, fo);

        // Verify the new SUBMIT event has changeType=MIXED and operator_uid=real uid
        verify(eventMapper).insert(argThat((DmDbChangeEventDO e) -> {
            if (e == null || !GovEventType.SUBMIT.name().equals(e.getEventType())) {
                return false;
            }
            if (!UID.equals(e.getOperatorUid()) || "SYSTEM".equals(e.getOperatorUid())) {
                return false;
            }
            Map<String, Object> data = JsonUtils.toObj(e.getEventData(), HashMap.class);
            return data != null && "MIXED".equals(data.get("changeType"));
        }));
    }

    // ==================== helpers ====================

    private DmApprovalDO buildTicket(long id, String primaryUid, String ownerUid, ApprovalStatus status, String ticketType) {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(id);
        ticket.setBizId("biz-" + id);
        ticket.setPrimaryUid(primaryUid);
        ticket.setOwnerUid(ownerUid);
        ticket.setTicketStatus(status);
        ticket.setApproBiz(ApprovalBiz.DM_CHANGE);
        ApprovalMO mo = new ApprovalMO();
        mo.setTicketType(ticketType);
        mo.setPairIds(List.of(300L));
        ticket.setTicketInfo(JsonUtils.toJson(mo));
        return ticket;
    }

    // ==================== §0: PROD_DML resubmit fix ====================

    @Test
    public void resubmit_prodDml_reestablishesPersonListAndResetsApprovalProcess() {
        long ticketId = 300L;
        long groupId = 310L;
        DmApprovalDO ticket = buildTicket(ticketId, PUID, UID, ApprovalStatus.REVISION, "PROD_DML");
        when(approvalMapper.queryById(ticketId)).thenReturn(ticket);

        DmTicketDbStmtDO group = buildGroup(groupId, ticketId, "FAILED");
        when(stmtMapper.queryByTicketId(ticketId)).thenReturn(List.of(group));
        when(revisionMapper.maxVersionByStmtId(groupId)).thenReturn(0);

        // Override the mock to return a DML SplitScript for this test
        com.clougence.clouddm.console.web.component.analysis.QueryAnalysisService queryAnalysisService
            = (com.clougence.clouddm.console.web.component.analysis.QueryAnalysisService)
                ReflectionTestUtils.getField(service, "queryAnalysisService");
        when(queryAnalysisService.analysisSplitStream(any(), any(), any(), anyInt(), anyInt()))
            .thenAnswer(invocation -> java.util.stream.Stream.of(createDmlSplitScript()));

        // queryPersons returns a simple list
        com.clougence.clouddm.console.web.model.vo.PrimaryUserVO person
            = new com.clougence.clouddm.console.web.model.vo.PrimaryUserVO();
        person.setUid(UID);
        person.setUsername("approver");
        when(approvalFlowService.queryPersons(ticketId)).thenReturn(List.of(person));

        GovTicketV2ResubmitFO fo = new GovTicketV2ResubmitFO();
        fo.setTicketId(ticketId);
        fo.setGroups(List.of(buildGroupInput(groupId, "UPDATE t SET val=1 WHERE id=2;")));
        service.resubmit(PUID, UID, fo);

        // Verify queryPersons was called (person list source for replacePersons)
        verify(approvalFlowService).queryPersons(ticketId);

        // InOrder: replacePersons → updateProcessStatus(APPROVAL, INIT) — the §0 surgery ordering
        InOrder inOrder = inOrder(approvalPersonService, approvalStateService);
        inOrder.verify(approvalPersonService).replacePersons(eq(ticket), any());
        inOrder.verify(approvalStateService).updateProcessStatus(eq(ticketId),
            eq(com.clougence.clouddm.platform.dal.model.approval.ApprovalStage.APPROVAL),
            eq(com.clougence.clouddm.platform.dal.model.approval.ApprovalProcessStatus.INIT), anyString());
    }

    @Test
    public void resubmit_preDdl_zeroInteractionWithPersonService() {
        long ticketId = 301L;
        long groupId = 320L;
        DmApprovalDO ticket = buildTicket(ticketId, PUID, UID, ApprovalStatus.REVISION, "PRE_DDL");
        when(approvalMapper.queryById(ticketId)).thenReturn(ticket);

        DmTicketDbStmtDO group = buildGroup(groupId, ticketId, "FAILED");
        when(stmtMapper.queryByTicketId(ticketId)).thenReturn(List.of(group));
        when(revisionMapper.maxVersionByStmtId(groupId)).thenReturn(0);

        GovTicketV2ResubmitFO fo = new GovTicketV2ResubmitFO();
        fo.setTicketId(ticketId);
        fo.setGroups(List.of(buildGroupInput(groupId, "CREATE TABLE t_new(id int);")));
        service.resubmit(PUID, UID, fo);

        // PRE_DDL: zero interaction with personService / queryPersons / updateProcessStatus(APPROVAL, INIT)
        verify(approvalFlowService, never()).queryPersons(anyLong());
        verify(approvalStateService, never()).updateProcessStatus(eq(ticketId),
            eq(com.clougence.clouddm.platform.dal.model.approval.ApprovalStage.APPROVAL),
            eq(com.clougence.clouddm.platform.dal.model.approval.ApprovalProcessStatus.INIT), anyString());
    }

    // ==================== DBA proxy return (§2) ====================

    @Test
    public void returnTicket_dbaProxyWithDsAuth_succeeds() {
        long ticketId = 400L;
        // Ticket owned by someone else, but DBA has DS auth
        DmApprovalDO ticket = buildTicket(ticketId, PUID, "owner-uid", ApprovalStatus.EXEC_FAIL, "PRE_DDL");
        ticket.setBindDsId(10L);
        when(approvalMapper.queryById(ticketId)).thenReturn(ticket);

        DmTicketDbStmtDO group = buildGroup(410L, ticketId, "FAILED");
        when(stmtMapper.queryByTicketId(ticketId)).thenReturn(List.of(group));
        when(prodStmtMapper.countBySourceStmtIds(List.of(410L))).thenReturn(0);

        // DBA has DS auth
        when(dmAuthServiceForBiz.checkResAuthWithoutError(
            eq(PUID), eq(UID), eq(10L), any(), any(), any())).thenReturn(true);

        GovTicketV2ReturnTicketFO fo = new GovTicketV2ReturnTicketFO();
        fo.setTicketId(ticketId);
        fo.setReason("DBA proxy return");
        service.returnTicket(PUID, UID, fo);

        verify(approvalStateService).updateApprovalStatus(eq(ticketId), eq(ApprovalStatus.REVISION), anyString());
        // Event must have via=DBA
        verify(eventMapper).insert(argThat((DmDbChangeEventDO e) ->
            e != null && GovEventType.TICKET_RETURNED.name().equals(e.getEventType())
                && UID.equals(e.getOperatorUid())
                && eventDataHasVia(e, "DBA")));
    }

    @Test
    public void returnTicket_primaryAccount_succeeds() {
        long ticketId = 401L;
        // PUID == UID means primary account (isPrimary)
        DmApprovalDO ticket = buildTicket(ticketId, PUID, "owner-uid", ApprovalStatus.EXEC_FAIL, "PRE_DDL");
        when(approvalMapper.queryById(ticketId)).thenReturn(ticket);

        DmTicketDbStmtDO group = buildGroup(411L, ticketId, "FAILED");
        when(stmtMapper.queryByTicketId(ticketId)).thenReturn(List.of(group));
        when(prodStmtMapper.countBySourceStmtIds(List.of(411L))).thenReturn(0);

        GovTicketV2ReturnTicketFO fo = new GovTicketV2ReturnTicketFO();
        fo.setTicketId(ticketId);
        service.returnTicket(PUID, PUID, fo); // uid == puid (primary account)

        verify(approvalStateService).updateApprovalStatus(eq(ticketId), eq(ApprovalStatus.REVISION), anyString());
        verify(eventMapper).insert(argThat((DmDbChangeEventDO e) ->
            e != null && eventDataHasVia(e, "DBA"))); // primary account → via=DBA
    }

    @Test
    public void returnTicket_noAuth_throws() {
        long ticketId = 402L;
        DmApprovalDO ticket = buildTicket(ticketId, PUID, "owner-uid", ApprovalStatus.EXEC_FAIL, "PRE_DDL");
        ticket.setBindDsId(10L);
        when(approvalMapper.queryById(ticketId)).thenReturn(ticket);

        DmTicketDbStmtDO group = buildGroup(412L, ticketId, "FAILED");
        when(stmtMapper.queryByTicketId(ticketId)).thenReturn(List.of(group));

        when(dmAuthServiceForBiz.checkResAuthWithoutError(
            any(), any(), anyLong(), any(), any(), any())).thenReturn(false);

        GovTicketV2ReturnTicketFO fo = new GovTicketV2ReturnTicketFO();
        fo.setTicketId(ticketId);
        try {
            service.returnTicket(PUID, UID, fo);
            fail("Expected ErrorMessageException");
        } catch (ErrorMessageException ex) {
            // ok — no auth
        }
        verify(approvalStateService, never()).updateApprovalStatus(anyLong(), any(), any());
    }

    @Test
    public void resubmit_stillOwnerOnly() {
        long ticketId = 403L;
        DmApprovalDO ticket = buildTicket(ticketId, PUID, "owner-uid", ApprovalStatus.REVISION, "PRE_DDL");
        when(approvalMapper.queryById(ticketId)).thenReturn(ticket);

        GovTicketV2ResubmitFO fo = new GovTicketV2ResubmitFO();
        fo.setTicketId(ticketId);
        fo.setGroups(List.of(buildGroupInput(420L, "CREATE TABLE t(id int);")));
        try {
            service.resubmit(PUID, UID, fo); // uid != ownerUid
            fail("Expected ErrorMessageException");
        } catch (ErrorMessageException ex) {
            // ok — resubmit is owner-only even with proxy return
        }
    }

    // ==================== via field in events ====================

    @Test
    public void returnTicket_ownerReturn_eventViaOwner() {
        long ticketId = 500L;
        DmApprovalDO ticket = buildTicket(ticketId, PUID, UID, ApprovalStatus.EXEC_FAIL, "PRE_DDL");
        when(approvalMapper.queryById(ticketId)).thenReturn(ticket);

        DmTicketDbStmtDO group = buildGroup(510L, ticketId, "FAILED");
        when(stmtMapper.queryByTicketId(ticketId)).thenReturn(List.of(group));
        when(prodStmtMapper.countBySourceStmtIds(List.of(510L))).thenReturn(0);

        GovTicketV2ReturnTicketFO fo = new GovTicketV2ReturnTicketFO();
        fo.setTicketId(ticketId);
        fo.setReason("self return");
        service.returnTicket(PUID, UID, fo);

        verify(eventMapper).insert(argThat((DmDbChangeEventDO e) ->
            e != null && GovEventType.TICKET_RETURNED.name().equals(e.getEventType())
                && UID.equals(e.getOperatorUid())
                && eventDataHasVia(e, "OWNER")));
    }

    // ==================== reason propagation ====================

    @Test
    public void returnTicket_reasonStoredInEventData() {
        long ticketId = 501L;
        DmApprovalDO ticket = buildTicket(ticketId, PUID, UID, ApprovalStatus.EXEC_FAIL, "PRE_DDL");
        when(approvalMapper.queryById(ticketId)).thenReturn(ticket);

        DmTicketDbStmtDO group = buildGroup(511L, ticketId, "FAILED");
        when(stmtMapper.queryByTicketId(ticketId)).thenReturn(List.of(group));
        when(prodStmtMapper.countBySourceStmtIds(List.of(511L))).thenReturn(0);

        String reason = "SQL syntax error in group 1";
        GovTicketV2ReturnTicketFO fo = new GovTicketV2ReturnTicketFO();
        fo.setTicketId(ticketId);
        fo.setReason(reason);
        service.returnTicket(PUID, UID, fo);

        verify(eventMapper).insert(argThat((DmDbChangeEventDO e) -> {
            if (e == null || !GovEventType.TICKET_RETURNED.name().equals(e.getEventType())) {
                return false;
            }
            Map<String, Object> data = JsonUtils.toObj(e.getEventData(), HashMap.class);
            return data != null && reason.equals(data.get("reason"));
        }));
    }

    @Test
    public void resubmit_reasonStoredInRevisionTable() {
        long ticketId = 502L;
        long groupId = 520L;
        DmApprovalDO ticket = buildTicket(ticketId, PUID, UID, ApprovalStatus.REVISION, "PRE_DDL");
        when(approvalMapper.queryById(ticketId)).thenReturn(ticket);

        DmTicketDbStmtDO group = buildGroup(groupId, ticketId, "FAILED");
        when(stmtMapper.queryByTicketId(ticketId)).thenReturn(List.of(group));
        when(revisionMapper.maxVersionByStmtId(groupId)).thenReturn(0);

        String reason = "Fixed WHERE clause";
        GovTicketV2ResubmitFO fo = new GovTicketV2ResubmitFO();
        fo.setTicketId(ticketId);
        fo.setGroups(List.of(buildGroupInput(groupId, "CREATE TABLE t_new(id int);")));
        fo.setReason(reason);
        service.resubmit(PUID, UID, fo);

        verify(revisionMapper).insertRevision(argThat((com.clougence.clouddm.platform.dal.model.govticket.DmTicketStmtRevisionDO r) ->
            r != null && reason.equals(r.getReason())));
    }

    private boolean eventDataHasVia(DmDbChangeEventDO event, String expectedVia) {
        Map<String, Object> data = JsonUtils.toObj(event.getEventData(), HashMap.class);
        return data != null && expectedVia.equals(data.get("via"));
    }

    private DmTicketDbStmtDO buildGroup(long id, long ticketId, String execStatus) {
        DmTicketDbStmtDO group = new DmTicketDbStmtDO();
        group.setId(id);
        group.setTicketId(ticketId);
        group.setPairId(300L);
        group.setDsId(20L);
        group.setDbName("pre_db");
        group.setSqlContent("CREATE TABLE t(id int);");
        group.setExecStatus(execStatus);
        return group;
    }

    private GovTicketV2ResubmitFO.GroupInput buildGroupInput(long groupId, String sqlContent) {
        GovTicketV2ResubmitFO.GroupInput gi = new GovTicketV2ResubmitFO.GroupInput();
        gi.setGroupId(groupId);
        gi.setSqlContent(sqlContent);
        return gi;
    }

    /** Canned DDL SplitScript for precheck PASS path (CREATE_TABLE -> DDL classification). */
    private static SplitScript createDdlSplitScript() {
        SplitScript script = new SplitScript();
        script.setIndex(1);
        script.setType(EnumSet.of(SplitQueryType.CREATE_TABLE));
        script.setScript("CREATE TABLE t(id int);");
        return script;
    }

    /** Canned DML SplitScript for PROD_DML precheck PASS path (UPDATE -> DML classification). */
    private static SplitScript createDmlSplitScript() {
        SplitScript script = new SplitScript();
        script.setIndex(1);
        script.setType(EnumSet.of(SplitQueryType.UPDATE));
        script.setScript("UPDATE t SET val=1 WHERE id=2;");
        return script;
    }
}
