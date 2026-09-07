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

import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.base.metadata.ds.DataSourceConfig;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.component.auth.DmAuthServiceForBiz;
import com.clougence.clouddm.console.web.component.dsconfig.DmDsConfigService;
import com.clougence.clouddm.console.web.component.governance.GovSplitResult;
import com.clougence.clouddm.console.web.component.governance.GovStmtRow;
import com.clougence.clouddm.console.web.component.governance.GovStmtSplitService;
import com.clougence.clouddm.console.web.model.fo.governance.GovPreSubmitFO;
import com.clougence.clouddm.console.web.model.fo.ticket.DmAddTicketFO;
import com.clougence.clouddm.console.web.model.vo.logicaldb.LogicalDbTarget;
import com.clougence.clouddm.console.web.model.vo.ticket.DmTicketResultVO;
import com.clougence.clouddm.console.web.service.approval.ApprovalControlService;
import com.clougence.clouddm.console.web.service.governance.DbChangeGovernService;
import com.clougence.clouddm.console.web.service.logicaldb.LogicalDbService;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.DbChangeGovernDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangeEventMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangeStmtVersionMapper;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.approval.SqlContentType;
import com.clougence.clouddm.platform.dal.model.dbchange.ChangeType;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeStmtVersionDO;
import com.clougence.clouddm.platform.dal.model.dbchange.GovEventType;
import com.clougence.clouddm.platform.dal.model.dbchange.StmtSource;
import com.clougence.clouddm.platform.dal.model.logicaldb.GovRole;
import com.clougence.clouddm.sdk.security.auth.AuthKind;
import com.clougence.utils.JsonUtils;

public class DbChangeGovernServiceImplTest {

    private DbChangeGovernService     service;

    private LogicalDbService          logicalDbService;
    private DmAuthServiceForBiz       dmAuthServiceForBiz;
    private DmDsConfigService         dmDsConfigService;
    private GovStmtSplitService       govStmtSplitService;
    private ApprovalControlService    approvalControlService;
    private DbChangeGovernDal         dbChangeGovernDal;
    private ApprovalDal               approvalDal;
    private DmApprovalMapper          approvalMapper;
    private DmDbChangeStmtVersionMapper stmtVersionMapper;
    private DmDbChangeEventMapper     eventMapper;

    private static final String       PUID     = "puid-001";
    private static final String       UID      = "uid-001";
    private static final long         LOGICAL_DB_ID = 10L;
    private static final long         TICKET_ID    = 100L;

    @Before
    public void setUp() {
        DbChangeGovernServiceImpl impl = new DbChangeGovernServiceImpl();
        logicalDbService = mock(LogicalDbService.class);
        dmAuthServiceForBiz = mock(DmAuthServiceForBiz.class);
        dmDsConfigService = mock(DmDsConfigService.class);
        govStmtSplitService = mock(GovStmtSplitService.class);
        approvalControlService = mock(ApprovalControlService.class);
        dbChangeGovernDal = mock(DbChangeGovernDal.class);
        approvalDal = mock(ApprovalDal.class);
        approvalMapper = mock(DmApprovalMapper.class);
        stmtVersionMapper = mock(DmDbChangeStmtVersionMapper.class);
        eventMapper = mock(DmDbChangeEventMapper.class);

        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        ReflectionTestUtils.setField(impl, "logicalDbService", logicalDbService);
        ReflectionTestUtils.setField(impl, "dmAuthServiceForBiz", dmAuthServiceForBiz);
        ReflectionTestUtils.setField(impl, "dmDsConfigService", dmDsConfigService);
        ReflectionTestUtils.setField(impl, "govStmtSplitService", govStmtSplitService);
        ReflectionTestUtils.setField(impl, "approvalControlService", approvalControlService);
        ReflectionTestUtils.setField(impl, "dbChangeGovernDal", dbChangeGovernDal);
        ReflectionTestUtils.setField(impl, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(impl, "txManager", txManager);

        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);
        when(dbChangeGovernDal.stmtVersionMapper()).thenReturn(stmtVersionMapper);
        when(dbChangeGovernDal.eventMapper()).thenReturn(eventMapper);

        service = impl;
    }

    @Test
    public void preSubmit_success_ddl() {
        setupBinding();
        setupSplit(ChangeType.DDL, "CREATE TABLE foo (id INT)");
        setupTicketCreation(ApprovalBiz.DM_CHANGE);
        setupExistingTicketInfo();

        GovPreSubmitFO fo = buildFO("CREATE TABLE foo (id INT)", null);
        DmTicketResultVO result = service.preSubmit(PUID, UID, fo);

        assertNotNull(result);
        assertEquals(Long.valueOf(TICKET_ID), result.getTicketId());

        verifyStmtVersionsInserted(1);
        verifyEventAppended(GovEventType.SUBMIT, ApprovalStatus.PRE_INIT_WAIT.name());
        verifyTicketInfoUpdatedWithGovFields();
    }

    @Test
    public void preSubmit_success_dml_withRollback() {
        setupBinding();
        setupSplit(ChangeType.DML, "INSERT INTO foo VALUES (1)");
        setupTicketCreation(ApprovalBiz.DM_CHANGE);
        setupExistingTicketInfo();

        GovPreSubmitFO fo = buildFO("INSERT INTO foo VALUES (1)", "DELETE FROM foo");
        DmTicketResultVO result = service.preSubmit(PUID, UID, fo);

        assertNotNull(result);
        assertEquals(Long.valueOf(TICKET_ID), result.getTicketId());
        verifyStmtVersionsInserted(1);
    }

    @Test
    public void preSubmit_mixed_withRollback() {
        setupBinding();
        GovSplitResult splitResult = new GovSplitResult();
        splitResult.setChangeType(ChangeType.MIXED);
        splitResult.setStmts(List.of(
            createStmtRow(1, "CREATE TABLE foo (id INT)"),
            createStmtRow(2, "INSERT INTO foo VALUES (1)")
        ));
        when(govStmtSplitService.split(any(), any())).thenReturn(splitResult);
        setupTicketCreation(ApprovalBiz.DM_CHANGE);
        setupExistingTicketInfo();

        GovPreSubmitFO fo = buildFO("CREATE TABLE foo; INSERT INTO foo VALUES (1)", "DELETE FROM foo");
        DmTicketResultVO result = service.preSubmit(PUID, UID, fo);

        assertNotNull(result);
        verifyStmtVersionsInserted(2);
    }

    @Test
    public void preSubmit_dml_withoutRollback_rejected() {
        setupBinding();
        setupSplit(ChangeType.DML, "INSERT INTO foo VALUES (1)");

        GovPreSubmitFO fo = buildFO("INSERT INTO foo VALUES (1)", null);
        try {
            service.preSubmit(PUID, UID, fo);
            fail("Should reject DML without rollback SQL");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("Rollback SQL is required"));
        }
        verify(approvalControlService, never()).createSqlTicket(any(), any(), any(), any());
    }

    @Test
    public void preSubmit_mixed_withoutRollback_rejected() {
        setupBinding();
        GovSplitResult splitResult = new GovSplitResult();
        splitResult.setChangeType(ChangeType.MIXED);
        splitResult.setStmts(List.of(createStmtRow(1, "CREATE TABLE foo (id INT)")));
        when(govStmtSplitService.split(any(), any())).thenReturn(splitResult);

        GovPreSubmitFO fo = buildFO("CREATE TABLE foo; INSERT INTO foo VALUES (1)", null);
        try {
            service.preSubmit(PUID, UID, fo);
            fail("Should reject MIXED without rollback SQL");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("Rollback SQL is required"));
        }
    }

    @Test
    public void preSubmit_noPreBinding_rejected() {
        when(logicalDbService.getBinding(PUID, LOGICAL_DB_ID, GovRole.PRE))
            .thenThrow(new ErrorMessageException("No PRE binding"));

        GovPreSubmitFO fo = buildFO("CREATE TABLE foo (id INT)", null);
        try {
            service.preSubmit(PUID, UID, fo);
            fail("Should reject when no PRE binding");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("No PRE binding"));
        }
    }

    @Test
    public void preSubmit_authDenied_rejected() {
        setupBinding();
        doThrow(new ErrorMessageException("Permission denied"))
            .when(dmAuthServiceForBiz).checkResAuth(eq(PUID), eq(UID), anyLong(), any(), eq("DM_TICKET"), eq(AuthKind.DataSource));

        GovPreSubmitFO fo = buildFO("CREATE TABLE foo (id INT)", null);
        try {
            service.preSubmit(PUID, UID, fo);
            fail("Should reject on auth failure");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("Permission denied"));
        }
    }

    @Test
    public void preSubmit_attachment_rejected() {
        GovPreSubmitFO fo = buildFO("CREATE TABLE foo", null);
        fo.setContentType(SqlContentType.ATTACHMENT);
        fo.setAttachmentId(999L);

        try {
            service.preSubmit(PUID, UID, fo);
            fail("Should reject ATTACHMENT");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("inline SQL"));
        }
    }

    @Test
    public void preSubmit_selectRejected() {
        setupBinding();
        GovSplitResult splitResult = new GovSplitResult();
        splitResult.setChangeType(ChangeType.DDL);
        splitResult.setStmts(List.of(createStmtRow(1, "SELECT * FROM foo")));
        when(govStmtSplitService.split(any(), any()))
            .thenThrow(new ErrorMessageException("Governance ticket only accepts DDL and DML, rejected statement type: SELECT"));

        GovPreSubmitFO fo = buildFO("SELECT * FROM foo", null);
        try {
            service.preSubmit(PUID, UID, fo);
            fail("Should reject SELECT");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("rejected statement type"));
        }
    }

    @Test
    public void preSubmit_verifyForceTrueInTicketFO() {
        setupBinding();
        setupSplit(ChangeType.DDL, "CREATE TABLE foo (id INT)");
        setupTicketCreation(ApprovalBiz.DM_CHANGE);
        setupExistingTicketInfo();

        GovPreSubmitFO fo = buildFO("CREATE TABLE foo (id INT)", null);
        service.preSubmit(PUID, UID, fo);

        ArgumentCaptor<DmAddTicketFO> foCaptor = ArgumentCaptor.forClass(DmAddTicketFO.class);
        verify(approvalControlService).createSqlTicket(eq(PUID), eq(UID), foCaptor.capture(), eq(ApprovalBiz.DM_CHANGE));
        assertTrue("Governance ticket should always use force=true", foCaptor.getValue().isForce());
        assertEquals(SqlContentType.INLINE, foCaptor.getValue().getContentType());
    }

    // --- helpers ---

    private void setupBinding() {
        LogicalDbTarget target = new LogicalDbTarget();
        target.setBindingId(1L);
        target.setLogicalDbId(LOGICAL_DB_ID);
        target.setEnvId(5L);
        target.setDsId(20L);
        target.setResPath("/mydb/");
        target.setGovRole(GovRole.PRE);
        when(logicalDbService.getBinding(PUID, LOGICAL_DB_ID, GovRole.PRE)).thenReturn(target);
        when(dmDsConfigService.fetchDsConfigFromExists(20L)).thenReturn(new DataSourceConfig());
    }

    private void setupSplit(ChangeType changeType, String sql) {
        GovSplitResult result = new GovSplitResult();
        result.setChangeType(changeType);
        result.setStmts(List.of(createStmtRow(1, sql)));
        when(govStmtSplitService.split(any(), any())).thenReturn(result);
    }

    private void setupTicketCreation(ApprovalBiz expectedBiz) {
        DmTicketResultVO vo = new DmTicketResultVO();
        vo.setTicketId(TICKET_ID);
        when(approvalControlService.createSqlTicket(eq(PUID), eq(UID), any(DmAddTicketFO.class), eq(expectedBiz)))
            .thenReturn(vo);
    }

    private void setupExistingTicketInfo() {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setTicketInfo(JsonUtils.toJson(new ApprovalMO()));
        when(approvalMapper.selectById(TICKET_ID)).thenReturn(ticket);
    }

    private void verifyStmtVersionsInserted(int expectedCount) {
        ArgumentCaptor<DmDbChangeStmtVersionDO> captor = ArgumentCaptor.forClass(DmDbChangeStmtVersionDO.class);
        verify(stmtVersionMapper, times(expectedCount)).insert(captor.capture());
        for (DmDbChangeStmtVersionDO stmt : captor.getAllValues()) {
            assertEquals(Long.valueOf(TICKET_ID), stmt.getTicketId());
            assertEquals(1, stmt.getStmtVersion().intValue());
            assertEquals(StmtSource.INITIAL.name(), stmt.getSource());
            assertEquals(UID, stmt.getOperatorUid());
            assertNotNull(stmt.getStmtHash());
            assertNotNull(stmt.getStmtText());
        }
    }

    private void verifyEventAppended(GovEventType eventType, String toStatus) {
        ArgumentCaptor<DmDbChangeEventDO> captor = ArgumentCaptor.forClass(DmDbChangeEventDO.class);
        verify(eventMapper).insert(captor.capture());
        DmDbChangeEventDO event = captor.getValue();
        assertEquals(Long.valueOf(TICKET_ID), event.getTicketId());
        assertEquals(eventType.name(), event.getEventType());
        assertEquals(toStatus, event.getToStatus());
        assertEquals(UID, event.getOperatorUid());
        assertNotNull(event.getEventData());
    }

    private void verifyTicketInfoUpdatedWithGovFields() {
        ArgumentCaptor<String> infoCaptor = ArgumentCaptor.forClass(String.class);
        verify(approvalMapper).updateTicketInfo(eq(TICKET_ID), infoCaptor.capture());
        ApprovalMO mo = JsonUtils.toObj(infoCaptor.getValue(), ApprovalMO.class);
        assertEquals(Long.valueOf(LOGICAL_DB_ID), mo.getLogicalDbId());
        assertEquals(GovRole.PRE.name(), mo.getGovRole());
    }

    private static GovStmtRow createStmtRow(int index, String text) {
        GovStmtRow row = new GovStmtRow();
        row.setStmtIndex(index);
        row.setStmtText(text);
        row.setStmtHash("hash-" + index);
        return row;
    }

    private static GovPreSubmitFO buildFO(String sql, String rollbackSql) {
        GovPreSubmitFO fo = new GovPreSubmitFO();
        fo.setLogicalDbId(LOGICAL_DB_ID);
        fo.setTicketTitle("Test Governance Ticket");
        fo.setDescription("Test description");
        fo.setSql(sql);
        fo.setRollbackSql(rollbackSql);
        fo.setContentType(SqlContentType.INLINE);
        return fo;
    }
}
