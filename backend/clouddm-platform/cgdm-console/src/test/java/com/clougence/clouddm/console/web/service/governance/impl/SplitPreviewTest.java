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
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.base.metadata.ds.DataSourceConfig;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.component.auth.DmAuthServiceForBiz;
import com.clougence.clouddm.console.web.component.dsconfig.DmDsConfigService;
import com.clougence.clouddm.console.web.component.governance.GovSplitResult;
import com.clougence.clouddm.console.web.component.governance.GovStmtRow;
import com.clougence.clouddm.console.web.component.governance.GovStmtSplitService;
import com.clougence.clouddm.console.web.model.fo.governance.GovSplitPreviewFO;
import com.clougence.clouddm.console.web.model.vo.governance.SplitPreviewVO;
import com.clougence.clouddm.console.web.service.approval.ApprovalControlService;
import com.clougence.clouddm.console.web.service.logicaldb.LogicalDbService;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.DbChangeGovernDal;
import com.clougence.clouddm.platform.dal.access.ExecutionDal;
import com.clougence.clouddm.platform.dal.model.dbchange.ChangeType;
import com.clougence.clouddm.platform.dal.model.logicaldb.GovRole;
import com.clougence.clouddm.console.web.model.vo.logicaldb.LogicalDbTarget;
import com.clougence.clouddm.sdk.security.auth.AuthKind;
import com.clougence.clouddm.sdk.security.auth.def.SecDataAuthLabel;

public class SplitPreviewTest {

    private DbChangeGovernServiceImpl service;

    private LogicalDbService       logicalDbService;
    private DmAuthServiceForBiz    dmAuthServiceForBiz;
    private DmDsConfigService       dmDsConfigService;
    private GovStmtSplitService     govStmtSplitService;

    private static final String     PUID          = "puid-001";
    private static final String     UID           = "uid-001";
    private static final long       LOGICAL_DB_ID = 10L;
    private static final long       DS_ID         = 20L;

    @Before
    public void setUp() {
        logicalDbService = mock(LogicalDbService.class);
        dmAuthServiceForBiz = mock(DmAuthServiceForBiz.class);
        dmDsConfigService = mock(DmDsConfigService.class);
        govStmtSplitService = mock(GovStmtSplitService.class);

        service = new DbChangeGovernServiceImpl();
        ReflectionTestUtils.setField(service, "logicalDbService", logicalDbService);
        ReflectionTestUtils.setField(service, "dmAuthServiceForBiz", dmAuthServiceForBiz);
        ReflectionTestUtils.setField(service, "dmDsConfigService", dmDsConfigService);
        ReflectionTestUtils.setField(service, "govStmtSplitService", govStmtSplitService);
        ReflectionTestUtils.setField(service, "approvalDal", mock(ApprovalDal.class));
        ReflectionTestUtils.setField(service, "dbChangeGovernDal", mock(DbChangeGovernDal.class));
        ReflectionTestUtils.setField(service, "executionDal", mock(ExecutionDal.class));
        ReflectionTestUtils.setField(service, "approvalControlService", mock(ApprovalControlService.class));
    }

    @Test
    public void splitPreview_ddl_success() {
        setupBinding();
        setupSplit(ChangeType.DDL, "CREATE TABLE foo (id INT)");

        GovSplitPreviewFO fo = new GovSplitPreviewFO();
        fo.setLogicalDbId(LOGICAL_DB_ID);
        fo.setSql("CREATE TABLE foo (id INT)");

        SplitPreviewVO vo = service.splitPreview(PUID, UID, fo);

        assertEquals("DDL", vo.getChangeType());
        assertEquals(1, vo.getStmts().size());
        SplitPreviewVO.StmtPreviewVO stmt = vo.getStmts().get(0);
        assertEquals(1, stmt.getStmtIndex());
        assertEquals("CREATE TABLE foo (id INT)", stmt.getSql());
        assertEquals("DDL", stmt.getChangeType());
        assertFalse(stmt.getExecConfig().isEnableTransactional());
        assertEquals("NONE", stmt.getExecConfig().getErrorStrategy());
    }

    @Test
    public void splitPreview_dml_transactionalConfig() {
        setupBinding();
        setupSplit(ChangeType.DML, "INSERT INTO foo VALUES (1)");

        GovSplitPreviewFO fo = new GovSplitPreviewFO();
        fo.setLogicalDbId(LOGICAL_DB_ID);
        fo.setSql("INSERT INTO foo VALUES (1)");

        SplitPreviewVO vo = service.splitPreview(PUID, UID, fo);

        assertEquals("DML", vo.getChangeType());
        SplitPreviewVO.StmtPreviewVO stmt = vo.getStmts().get(0);
        assertEquals("DML", stmt.getChangeType());
        assertTrue(stmt.getExecConfig().isEnableTransactional());
        assertEquals("NONE", stmt.getExecConfig().getErrorStrategy());
    }

    @Test
    public void splitPreview_mixed_perStmtChangeTypes() {
        setupBinding();
        GovSplitResult splitResult = new GovSplitResult();
        splitResult.setChangeType(ChangeType.MIXED);
        splitResult.setStmts(List.of(
            createStmtRow(1, "CREATE TABLE foo (id INT)", ChangeType.DDL),
            createStmtRow(2, "INSERT INTO foo VALUES (1)", ChangeType.DML)
        ));
        when(govStmtSplitService.split(any(), any())).thenReturn(splitResult);

        GovSplitPreviewFO fo = new GovSplitPreviewFO();
        fo.setLogicalDbId(LOGICAL_DB_ID);
        fo.setSql("CREATE TABLE foo; INSERT INTO foo VALUES (1)");

        SplitPreviewVO vo = service.splitPreview(PUID, UID, fo);

        assertEquals("MIXED", vo.getChangeType());
        assertEquals(2, vo.getStmts().size());
        // Per-statement types: stmt 1 is DDL, stmt 2 is DML (not MIXED)
        assertEquals("DDL", vo.getStmts().get(0).getChangeType());
        assertEquals("DML", vo.getStmts().get(1).getChangeType());
        // Exec config is ticket-level (overall MIXED → autocommit)
        for (SplitPreviewVO.StmtPreviewVO stmt : vo.getStmts()) {
            assertFalse(stmt.getExecConfig().isEnableTransactional());
            assertEquals("NONE", stmt.getExecConfig().getErrorStrategy());
        }
    }

    @Test
    public void splitPreview_delegatesToSplitService_correctArgs() {
        setupBinding();
        DataSourceConfig dsConfig = new DataSourceConfig();
        when(dmDsConfigService.fetchDsConfigFromExists(DS_ID)).thenReturn(dsConfig);
        setupSplit(ChangeType.DDL, "CREATE TABLE foo (id INT)");

        GovSplitPreviewFO fo = new GovSplitPreviewFO();
        fo.setLogicalDbId(LOGICAL_DB_ID);
        fo.setSql("CREATE TABLE foo (id INT)");

        service.splitPreview(PUID, UID, fo);

        // Verify the split service was called with the resolved dsConfig and the original SQL text
        verify(govStmtSplitService).split(eq(dsConfig), eq("CREATE TABLE foo (id INT)"));
    }

    @Test
    public void splitPreview_noPreBinding_rejected() {
        when(logicalDbService.getBinding(PUID, LOGICAL_DB_ID, GovRole.PRE))
            .thenThrow(new ErrorMessageException("No PRE binding"));

        GovSplitPreviewFO fo = new GovSplitPreviewFO();
        fo.setLogicalDbId(LOGICAL_DB_ID);
        fo.setSql("CREATE TABLE foo (id INT)");

        try {
            service.splitPreview(PUID, UID, fo);
            fail("Should reject when no PRE binding");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("No PRE binding"));
        }
        verify(govStmtSplitService, never()).split(any(), any());
    }

    @Test
    public void splitPreview_authDenied_rejected() {
        setupBinding();
        doThrow(new ErrorMessageException("Permission denied"))
            .when(dmAuthServiceForBiz).checkResAuth(
                eq(PUID), eq(UID), eq(DS_ID), any(),
                eq(SecDataAuthLabel.DM_DAUTH_TICKET), eq(AuthKind.DataSource));

        GovSplitPreviewFO fo = new GovSplitPreviewFO();
        fo.setLogicalDbId(LOGICAL_DB_ID);
        fo.setSql("CREATE TABLE foo (id INT)");

        try {
            service.splitPreview(PUID, UID, fo);
            fail("Should reject on auth failure");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("Permission denied"));
        }
        verify(govStmtSplitService, never()).split(any(), any());
    }

    @Test
    public void splitPreview_selectRejected_bySplitService() {
        setupBinding();
        when(govStmtSplitService.split(any(), any()))
            .thenThrow(new ErrorMessageException("Governance ticket only accepts DDL and DML, rejected statement type: SELECT"));

        GovSplitPreviewFO fo = new GovSplitPreviewFO();
        fo.setLogicalDbId(LOGICAL_DB_ID);
        fo.setSql("SELECT * FROM foo");

        try {
            service.splitPreview(PUID, UID, fo);
            fail("Should reject SELECT");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("rejected statement type"));
        }
    }

    @Test
    public void splitPreview_longSql_truncated() {
        setupBinding();
        String longSql = "CREATE TABLE foo (id INT, ".repeat(100) + "col TEXT)";
        GovStmtRow row = new GovStmtRow();
        row.setStmtIndex(1);
        row.setStmtText(longSql);
        row.setStmtHash("hash-1");
        row.setChangeType(ChangeType.DDL);
        GovSplitResult splitResult = new GovSplitResult();
        splitResult.setChangeType(ChangeType.DDL);
        splitResult.setStmts(List.of(row));
        when(govStmtSplitService.split(any(), any())).thenReturn(splitResult);

        GovSplitPreviewFO fo = new GovSplitPreviewFO();
        fo.setLogicalDbId(LOGICAL_DB_ID);
        fo.setSql(longSql);

        SplitPreviewVO vo = service.splitPreview(PUID, UID, fo);
        String previewSql = vo.getStmts().get(0).getSql();
        assertTrue("Preview SQL should be truncated", previewSql.length() <= 504);
        assertTrue("Truncated SQL should end with ...", previewSql.endsWith("..."));
    }

    // --- helpers ---

    private void setupBinding() {
        LogicalDbTarget target = new LogicalDbTarget();
        target.setBindingId(1L);
        target.setLogicalDbId(LOGICAL_DB_ID);
        target.setEnvId(5L);
        target.setDsId(DS_ID);
        target.setResPath("/mydb/");
        target.setGovRole(GovRole.PRE);
        when(logicalDbService.getBinding(PUID, LOGICAL_DB_ID, GovRole.PRE)).thenReturn(target);
        when(dmDsConfigService.fetchDsConfigFromExists(DS_ID)).thenReturn(new DataSourceConfig());
    }

    private void setupSplit(ChangeType changeType, String sql) {
        GovSplitResult result = new GovSplitResult();
        result.setChangeType(changeType);
        result.setStmts(List.of(createStmtRow(1, sql, changeType)));
        when(govStmtSplitService.split(any(), any())).thenReturn(result);
    }

    private static GovStmtRow createStmtRow(int index, String text, ChangeType changeType) {
        GovStmtRow row = new GovStmtRow();
        row.setStmtIndex(index);
        row.setStmtText(text);
        row.setStmtHash("hash-" + index);
        row.setChangeType(changeType);
        return row;
    }
}
