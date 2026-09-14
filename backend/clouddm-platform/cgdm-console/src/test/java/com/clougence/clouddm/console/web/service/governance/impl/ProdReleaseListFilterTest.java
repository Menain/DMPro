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
import java.util.Date;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.clougence.clouddm.console.web.component.approval.ApprovalStateService;
import com.clougence.clouddm.console.web.component.auth.DmAuthServiceForBiz;
import com.clougence.clouddm.console.web.component.execute.AutoExecService;
import com.clougence.clouddm.console.web.component.governance.ProdReleaseStateMachine;
import com.clougence.clouddm.console.web.component.governance.ReleaseSchemaDiffService;
import com.clougence.clouddm.console.web.component.governance.ReleaseSchemaPrecheckService;
import com.clougence.clouddm.console.web.service.approval.ApprovalControlService;
import com.clougence.clouddm.console.web.util.DmTeamUtils;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.DbPairDal;
import com.clougence.clouddm.platform.dal.access.DataSourceDal;
import com.clougence.clouddm.platform.dal.access.DbChangeEventDal;
import com.clougence.clouddm.platform.dal.access.ProdReleaseDal;
import com.clougence.clouddm.platform.dal.access.TicketDbStmtDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangeEventMapper;
import com.clougence.clouddm.platform.dal.mapper.dbpair.DmDbPairMapper;
import com.clougence.clouddm.platform.dal.mapper.datasource.DmDsMapper;
import com.clougence.clouddm.platform.dal.mapper.govticket.DmTicketDbStmtMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseStmtMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseStmtSchemaMapper;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseDO;

/**
 * Tests for the release-list time-range filter (days → since conversion).
 * Isolated from the dirty ProdReleaseServiceImplTest to avoid touching uncommitted user changes.
 */
public class ProdReleaseListFilterTest {

    private ProdReleaseServiceImpl  service;
    private ProdReleaseDal           prodReleaseDal;
    private DmProdReleaseMapper      releaseMapper;

    private static final String PUID = "puid-001";

    @Before
    public void setUp() {
        service = new ProdReleaseServiceImpl();

        // DmTeamUtils static mock (required by constructor context)
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
        ReflectionTestUtils.setField(DmTeamUtils.class, "executionDal", staticExecDal);

        prodReleaseDal = mock(ProdReleaseDal.class);
        releaseMapper = mock(DmProdReleaseMapper.class);
        when(prodReleaseDal.releaseMapper()).thenReturn(releaseMapper);

        // Minimal mocks for the dependencies the service references
        TicketDbStmtDal ticketDbStmtDal = mock(TicketDbStmtDal.class);
        when(ticketDbStmtDal.stmtMapper()).thenReturn(mock(DmTicketDbStmtMapper.class));
        ApprovalDal approvalDal = mock(ApprovalDal.class);
        when(approvalDal.approvalMapper()).thenReturn(mock(DmApprovalMapper.class));
        DbPairDal dbPairDal = mock(DbPairDal.class);
        when(dbPairDal.pairMapper()).thenReturn(mock(DmDbPairMapper.class));
        DataSourceDal dsDal = mock(DataSourceDal.class);
        when(dsDal.dsMapper()).thenReturn(mock(DmDsMapper.class));
        DbChangeEventDal dbChangeEventDal = mock(DbChangeEventDal.class);
        when(dbChangeEventDal.eventMapper()).thenReturn(mock(DmDbChangeEventMapper.class));

        ReflectionTestUtils.setField(service, "prodReleaseDal", prodReleaseDal);
        ReflectionTestUtils.setField(service, "ticketDbStmtDal", ticketDbStmtDal);
        ReflectionTestUtils.setField(service, "approvalDal", approvalDal);
        ReflectionTestUtils.setField(service, "dbPairDal", dbPairDal);
        ReflectionTestUtils.setField(service, "dsDal", dsDal);
        ReflectionTestUtils.setField(service, "dbChangeEventDal", dbChangeEventDal);
        ReflectionTestUtils.setField(service, "releaseStateMachine", mock(ProdReleaseStateMachine.class));
        ReflectionTestUtils.setField(service, "dmAuthServiceForBiz", mock(DmAuthServiceForBiz.class));
        ReflectionTestUtils.setField(service, "autoExecService", mock(AutoExecService.class));
        ReflectionTestUtils.setField(service, "approvalControlService", mock(ApprovalControlService.class));
        ReflectionTestUtils.setField(service, "approvalStateService", mock(ApprovalStateService.class));
        ReflectionTestUtils.setField(service, "releaseSchemaPrecheckService", mock(ReleaseSchemaPrecheckService.class));
        ReflectionTestUtils.setField(service, "releaseSchemaDiffService", mock(ReleaseSchemaDiffService.class));
    }

    @Test
    public void computeSince_null_returnsNull() {
        assertNull(ProdReleaseServiceImpl.computeSince(null));
    }

    @Test
    public void computeSince_zero_returnsNull() {
        assertNull(ProdReleaseServiceImpl.computeSince(0));
    }

    @Test
    public void computeSince_negative_returnsNull() {
        assertNull(ProdReleaseServiceImpl.computeSince(-1));
    }

    @Test
    public void computeSince_7_approxNowMinus7Days() {
        Date before = new Date(System.currentTimeMillis() - 7 * 86400_000L);
        Date since = ProdReleaseServiceImpl.computeSince(7);
        Date after = new Date(System.currentTimeMillis() - 7 * 86400_000L + 5000); // 5s tolerance

        assertNotNull(since);
        assertTrue("since should be >= now-7d-5s", !since.before(before));
        assertTrue("since should be <= now-7d+5s", !since.after(after));
    }

    @Test
    public void listReleases_nullDays_passesNullSince() {
        IPage<DmProdReleaseDO> emptyPage = new Page<>(1, 20);
        when(releaseMapper.listByConditionAndPage(any(), eq(PUID), any(), any())).thenReturn(emptyPage);

        service.listReleases(PUID, null, null, 1, 20);

        ArgumentCaptor<Date> captor = ArgumentCaptor.forClass(Date.class);
        verify(releaseMapper).listByConditionAndPage(any(), eq(PUID), isNull(), captor.capture());
        assertNull(captor.getValue());
    }

    @Test
    public void listReleases_zeroDays_passesNullSince() {
        IPage<DmProdReleaseDO> emptyPage = new Page<>(1, 20);
        when(releaseMapper.listByConditionAndPage(any(), eq(PUID), any(), any())).thenReturn(emptyPage);

        service.listReleases(PUID, null, 0, 1, 20);

        ArgumentCaptor<Date> captor = ArgumentCaptor.forClass(Date.class);
        verify(releaseMapper).listByConditionAndPage(any(), eq(PUID), isNull(), captor.capture());
        assertNull(captor.getValue());
    }

    @Test
    public void listReleases_negativeDays_passesNullSince() {
        IPage<DmProdReleaseDO> emptyPage = new Page<>(1, 20);
        when(releaseMapper.listByConditionAndPage(any(), eq(PUID), any(), any())).thenReturn(emptyPage);

        service.listReleases(PUID, null, -1, 1, 20);

        ArgumentCaptor<Date> captor = ArgumentCaptor.forClass(Date.class);
        verify(releaseMapper).listByConditionAndPage(any(), eq(PUID), isNull(), captor.capture());
        assertNull(captor.getValue());
    }

    @Test
    public void listReleases_7days_passesApproxNowMinus7d() {
        IPage<DmProdReleaseDO> emptyPage = new Page<>(1, 20);
        when(releaseMapper.listByConditionAndPage(any(), eq(PUID), any(), any())).thenReturn(emptyPage);

        Date before = new Date(System.currentTimeMillis() - 7 * 86400_000L);
        service.listReleases(PUID, null, 7, 1, 20);
        Date after = new Date(System.currentTimeMillis() - 7 * 86400_000L + 5000);

        ArgumentCaptor<Date> captor = ArgumentCaptor.forClass(Date.class);
        verify(releaseMapper).listByConditionAndPage(any(), eq(PUID), isNull(), captor.capture());
        Date since = captor.getValue();
        assertNotNull(since);
        assertTrue("since should be >= now-7d-5s", !since.before(before));
        assertTrue("since should be <= now-7d+5s", !since.after(after));
    }

    @Test
    public void listReleases_statusAndDaysCombined_bothPassedToMapper() {
        IPage<DmProdReleaseDO> emptyPage = new Page<>(1, 20);
        when(releaseMapper.listByConditionAndPage(any(), eq(PUID), any(), any())).thenReturn(emptyPage);

        service.listReleases(PUID, "EXECUTING", 30, 1, 20);

        ArgumentCaptor<Date> captor = ArgumentCaptor.forClass(Date.class);
        verify(releaseMapper).listByConditionAndPage(any(), eq(PUID), eq("EXECUTING"), captor.capture());
        assertNotNull(captor.getValue());
    }
}
