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
package com.clougence.clouddm.console.web.service.logicaldb.impl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.base.metadata.ds.DataSourceType;
import com.clougence.clouddm.console.web.model.fo.logicaldb.BindingItemFO;
import com.clougence.clouddm.console.web.model.fo.logicaldb.BindingSetFO;
import com.clougence.clouddm.console.web.model.fo.logicaldb.LogicalDbCreateFO;
import com.clougence.clouddm.console.web.model.fo.logicaldb.LogicalDbListFO;
import com.clougence.clouddm.console.web.model.fo.logicaldb.LogicalDbUpdateFO;
import com.clougence.clouddm.console.web.model.vo.logicaldb.LogicalDbBindingVO;
import com.clougence.clouddm.console.web.model.vo.logicaldb.LogicalDbTarget;
import com.clougence.clouddm.console.web.model.vo.logicaldb.LogicalDbVO;
import com.clougence.clouddm.console.web.model.vo.logicaldb.MyLogicalDbVO;
import com.clougence.clouddm.console.web.service.envparam.DmEnvParamService;
import com.clougence.clouddm.platform.dal.access.AuthDal;
import com.clougence.clouddm.platform.dal.access.DataSourceDal;
import com.clougence.clouddm.platform.dal.access.LogicalDbDal;
import com.clougence.clouddm.platform.dal.access.SystemDal;
import com.clougence.clouddm.platform.dal.mapper.auth.DmAuthResMapper;
import com.clougence.clouddm.platform.dal.mapper.datasource.DmDsMapper;
import com.clougence.clouddm.platform.dal.mapper.logicaldb.DmLogicalDbEnvBindingMapper;
import com.clougence.clouddm.platform.dal.mapper.logicaldb.DmLogicalDbMapper;
import com.clougence.clouddm.platform.dal.mapper.system.DmSysEnvMapper;
import com.clougence.clouddm.platform.dal.model.auth.DmAuthResDO;
import com.clougence.clouddm.platform.dal.model.datasource.DmDsDO;
import com.clougence.clouddm.platform.dal.model.logicaldb.DmLogicalDbDO;
import com.clougence.clouddm.platform.dal.model.logicaldb.DmLogicalDbEnvBindingDO;
import com.clougence.clouddm.platform.dal.model.logicaldb.GovRole;
import com.clougence.clouddm.platform.dal.model.logicaldb.LogicalDbStatus;
import com.clougence.clouddm.platform.dal.model.system.DmSysEnvDO;
import com.clougence.clouddm.sdk.model.env.EnvParamKeys;
import com.clougence.clouddm.sdk.security.auth.AuthKind;

public class LogicalDbServiceImplTest {

    private LogicalDbServiceImpl       service;

    private LogicalDbDal               logicalDbDal;
    private DmLogicalDbMapper           logicalDbMapper;
    private DmLogicalDbEnvBindingMapper bindingMapper;

    private SystemDal                  systemDal;
    private DmSysEnvMapper             envMapper;

    private DataSourceDal              dsDal;
    private DmDsMapper                 dsMapper;

    private AuthDal                    authDal;
    private DmAuthResMapper            resMapper;

    private DmEnvParamService          envParamService;

    private static final String PUID          = "0000000000000001";
    private static final String UID           = "0000000000000002";
    private static final long   LOGICAL_DB_ID = 1L;
    private static final long   ENV_ID        = 10L;
    private static final long   ENV_ID_2      = 11L;
    private static final long   DS_ID         = 100L;
    private static final long   DS_ID_2       = 101L;
    private static final long   BINDING_ID    = 500L;

    @Before
    public void setUp() {
        service = new LogicalDbServiceImpl();

        logicalDbDal = mock(LogicalDbDal.class);
        logicalDbMapper = mock(DmLogicalDbMapper.class);
        bindingMapper = mock(DmLogicalDbEnvBindingMapper.class);
        when(logicalDbDal.logicalDbMapper()).thenReturn(logicalDbMapper);
        when(logicalDbDal.bindingMapper()).thenReturn(bindingMapper);

        systemDal = mock(SystemDal.class);
        envMapper = mock(DmSysEnvMapper.class);
        when(systemDal.envMapper()).thenReturn(envMapper);

        dsDal = mock(DataSourceDal.class);
        dsMapper = mock(DmDsMapper.class);
        when(dsDal.dsMapper()).thenReturn(dsMapper);

        authDal = mock(AuthDal.class);
        resMapper = mock(DmAuthResMapper.class);
        when(authDal.resMapper()).thenReturn(resMapper);

        envParamService = mock(DmEnvParamService.class);

        ReflectionTestUtils.setField(service, "logicalDbDal", logicalDbDal);
        ReflectionTestUtils.setField(service, "systemDal", systemDal);
        ReflectionTestUtils.setField(service, "dsDal", dsDal);
        ReflectionTestUtils.setField(service, "authDal", authDal);
        ReflectionTestUtils.setField(service, "envParamService", envParamService);
    }

    // ==================== Helper data builders ====================

    private DmLogicalDbDO enabledLogicalDb() {
        DmLogicalDbDO db = new DmLogicalDbDO();
        db.setId(LOGICAL_DB_ID);
        db.setResourceCode("ORDER_DB");
        db.setResourceName("Order Database");
        db.setStatus(LogicalDbStatus.ENABLED.name());
        db.setCreatorUid(PUID);
        return db;
    }

    private DmLogicalDbDO disabledLogicalDb() {
        DmLogicalDbDO db = enabledLogicalDb();
        db.setStatus(LogicalDbStatus.DISABLED.name());
        return db;
    }

    private DmSysEnvDO env(long envId) {
        DmSysEnvDO env = new DmSysEnvDO();
        env.setId(envId);
        env.setEnvName("env-" + envId);
        env.setOwnerUid(PUID);
        return env;
    }

    private DmDsDO ds(long dsId) {
        DmDsDO ds = new DmDsDO();
        ds.setId(dsId);
        ds.setInstanceId("instance-" + dsId);
        return ds;
    }

    private DmDsDO dsWithType(long dsId, DataSourceType type) {
        DmDsDO ds = ds(dsId);
        ds.setDataSourceType(type);
        return ds;
    }

    private DmLogicalDbEnvBindingDO binding(long bindingId, long envId, long dsId, String resPath) {
        DmLogicalDbEnvBindingDO b = new DmLogicalDbEnvBindingDO();
        b.setId(bindingId);
        b.setLogicalDbId(LOGICAL_DB_ID);
        b.setEnvId(envId);
        b.setDsId(dsId);
        b.setResPath(resPath);
        return b;
    }

    private DmAuthResDO authRow(long dsId, String resPath, String resDesc) {
        DmAuthResDO row = new DmAuthResDO();
        row.setResId(dsId);
        row.setResPath(resPath);
        row.setResDesc(resDesc);
        row.setKindType(AuthKind.DataSource);
        // null startTime + endTime = permanent (isEffective = true)
        return row;
    }

    private DmAuthResDO expiredAuthRow(long dsId, String resPath) {
        DmAuthResDO row = authRow(dsId, resPath, null);
        row.setEndTime(new Date(System.currentTimeMillis() - 60000));
        return row;
    }

    private BindingItemFO bindingItem(long envId, long dsId, String resPath) {
        BindingItemFO item = new BindingItemFO();
        item.setEnvId(envId);
        item.setDsId(dsId);
        item.setResPath(resPath);
        return item;
    }

    // ==================== create ====================

    @Test
    public void create_insertsEnabledLogicalDb() {
        when(logicalDbMapper.queryByResourceCode("ORDER_DB")).thenReturn(null);

        LogicalDbCreateFO fo = new LogicalDbCreateFO();
        fo.setResourceCode("ORDER_DB");
        fo.setResourceName("Order Database");
        fo.setDescription("desc");

        LogicalDbVO vo = service.create(PUID, UID, fo);

        ArgumentCaptor<DmLogicalDbDO> captor = ArgumentCaptor.forClass(DmLogicalDbDO.class);
        verify(logicalDbMapper).insert(captor.capture());
        assertEquals(LogicalDbStatus.ENABLED.name(), captor.getValue().getStatus());
        assertEquals(PUID, captor.getValue().getCreatorUid());
        assertEquals("ORDER_DB", vo.getResourceCode());
    }

    @Test(expected = ErrorMessageException.class)
    public void create_duplicateCode_throws() {
        when(logicalDbMapper.queryByResourceCode("DUP")).thenReturn(enabledLogicalDb());

        LogicalDbCreateFO fo = new LogicalDbCreateFO();
        fo.setResourceCode("DUP");
        fo.setResourceName("dup");
        service.create(PUID, UID, fo);
    }

    @Test(expected = ErrorMessageException.class)
    public void create_duplicateKeyException_throws() {
        when(logicalDbMapper.queryByResourceCode("ORDER_DB")).thenReturn(null);
        doThrow(new DuplicateKeyException("uk_logical_db_code"))
            .when(logicalDbMapper).insert(any(DmLogicalDbDO.class));

        LogicalDbCreateFO fo = new LogicalDbCreateFO();
        fo.setResourceCode("ORDER_DB");
        fo.setResourceName("Order Database");
        service.create(PUID, UID, fo);
    }

    // ==================== update ====================

    @Test
    public void update_updatesFields() {
        when(logicalDbMapper.selectById(LOGICAL_DB_ID)).thenReturn(enabledLogicalDb());

        LogicalDbUpdateFO fo = new LogicalDbUpdateFO();
        fo.setId(LOGICAL_DB_ID);
        fo.setResourceName("New Name");
        fo.setDescription("new desc");
        fo.setStatus(LogicalDbStatus.DISABLED.name());

        service.update(PUID, UID, fo);

        ArgumentCaptor<DmLogicalDbDO> captor = ArgumentCaptor.forClass(DmLogicalDbDO.class);
        verify(logicalDbMapper).updateById(captor.capture());
        assertEquals("New Name", captor.getValue().getResourceName());
        assertEquals(LogicalDbStatus.DISABLED.name(), captor.getValue().getStatus());
    }

    @Test(expected = ErrorMessageException.class)
    public void update_notOwner_throws() {
        DmLogicalDbDO db = enabledLogicalDb();
        db.setCreatorUid("other-puid");
        when(logicalDbMapper.selectById(LOGICAL_DB_ID)).thenReturn(db);

        LogicalDbUpdateFO fo = new LogicalDbUpdateFO();
        fo.setId(LOGICAL_DB_ID);
        fo.setResourceName("new");
        service.update(PUID, UID, fo);
    }

    // ==================== delete ====================

    @Test
    public void delete_cascadesBindings() {
        when(logicalDbMapper.selectById(LOGICAL_DB_ID)).thenReturn(enabledLogicalDb());

        service.delete(PUID, UID, LOGICAL_DB_ID);

        verify(bindingMapper).deleteByLogicalDbId(LOGICAL_DB_ID);
        verify(logicalDbMapper).deleteById(LOGICAL_DB_ID);
    }

    // ==================== list ====================

    @Test
    public void list_returnsTenantScopedDbs() {
        DmLogicalDbDO db1 = enabledLogicalDb();
        DmLogicalDbDO db2 = enabledLogicalDb();
        db2.setId(2L);
        db2.setResourceCode("USER_DB");
        db2.setResourceName("User Database");
        when(logicalDbMapper.selectList(any())).thenReturn(Arrays.asList(db1, db2));

        LogicalDbListFO fo = new LogicalDbListFO();
        fo.setKeyword(null);

        List<LogicalDbVO> result = service.list(PUID, fo);
        assertEquals(2, result.size());
    }

    @Test
    public void list_filtersByKeyword() {
        DmLogicalDbDO db1 = enabledLogicalDb();
        DmLogicalDbDO db2 = enabledLogicalDb();
        db2.setId(2L);
        db2.setResourceCode("USER_DB");
        db2.setResourceName("User Database");
        when(logicalDbMapper.selectList(any())).thenReturn(Arrays.asList(db1, db2));

        LogicalDbListFO fo = new LogicalDbListFO();
        fo.setKeyword("ORDER");

        List<LogicalDbVO> result = service.list(PUID, fo);
        assertEquals(1, result.size());
        assertEquals("ORDER_DB", result.get(0).getResourceCode());
    }

    // ==================== bindingSet ====================

    @Test
    public void bindingSet_success_deletesAllAndInsertsAll() {
        when(logicalDbMapper.selectById(LOGICAL_DB_ID)).thenReturn(enabledLogicalDb());
        when(envMapper.queryByEnvID(PUID, ENV_ID)).thenReturn(env(ENV_ID));
        when(dsMapper.listByUser(PUID)).thenReturn(Collections.singletonList(ds(DS_ID)));
        when(envParamService.queryParam(PUID, ENV_ID, EnvParamKeys.GOV_ROLE)).thenReturn(GovRole.PRE.name());

        BindingSetFO fo = new BindingSetFO();
        fo.setLogicalDbId(LOGICAL_DB_ID);
        fo.setBindings(Collections.singletonList(bindingItem(ENV_ID, DS_ID, "/mydb/myschema/")));

        service.bindingSet(PUID, UID, fo);

        verify(bindingMapper).deleteByLogicalDbId(LOGICAL_DB_ID);
        ArgumentCaptor<DmLogicalDbEnvBindingDO> captor = ArgumentCaptor.forClass(DmLogicalDbEnvBindingDO.class);
        verify(bindingMapper).insert(captor.capture());
        assertEquals(Long.valueOf(ENV_ID), captor.getValue().getEnvId());
        assertEquals(Long.valueOf(DS_ID), captor.getValue().getDsId());
        assertEquals("/mydb/myschema/", captor.getValue().getResPath());
    }

    @Test
    public void bindingSet_normalizesResPath() {
        when(logicalDbMapper.selectById(LOGICAL_DB_ID)).thenReturn(enabledLogicalDb());
        when(envMapper.queryByEnvID(PUID, ENV_ID)).thenReturn(env(ENV_ID));
        when(dsMapper.listByUser(PUID)).thenReturn(Collections.singletonList(ds(DS_ID)));
        when(envParamService.queryParam(PUID, ENV_ID, EnvParamKeys.GOV_ROLE)).thenReturn(null);

        BindingSetFO fo = new BindingSetFO();
        fo.setLogicalDbId(LOGICAL_DB_ID);
        // no trailing slash — should be normalized
        fo.setBindings(Collections.singletonList(bindingItem(ENV_ID, DS_ID, "mydb/myschema")));

        service.bindingSet(PUID, UID, fo);

        ArgumentCaptor<DmLogicalDbEnvBindingDO> captor = ArgumentCaptor.forClass(DmLogicalDbEnvBindingDO.class);
        verify(bindingMapper).insert(captor.capture());
        assertEquals("/mydb/myschema/", captor.getValue().getResPath());
    }

    @Test(expected = ErrorMessageException.class)
    public void bindingSet_envNotOwned_throws() {
        when(logicalDbMapper.selectById(LOGICAL_DB_ID)).thenReturn(enabledLogicalDb());
        when(envMapper.queryByEnvID(PUID, ENV_ID)).thenReturn(null);

        BindingSetFO fo = new BindingSetFO();
        fo.setLogicalDbId(LOGICAL_DB_ID);
        fo.setBindings(Collections.singletonList(bindingItem(ENV_ID, DS_ID, "/mydb/")));

        service.bindingSet(PUID, UID, fo);
    }

    @Test(expected = ErrorMessageException.class)
    public void bindingSet_dsNotOwned_throws() {
        when(logicalDbMapper.selectById(LOGICAL_DB_ID)).thenReturn(enabledLogicalDb());
        when(envMapper.queryByEnvID(PUID, ENV_ID)).thenReturn(env(ENV_ID));
        when(dsMapper.listByUser(PUID)).thenReturn(Collections.emptyList());

        BindingSetFO fo = new BindingSetFO();
        fo.setLogicalDbId(LOGICAL_DB_ID);
        fo.setBindings(Collections.singletonList(bindingItem(ENV_ID, DS_ID, "/mydb/")));

        service.bindingSet(PUID, UID, fo);
    }

    @Test(expected = ErrorMessageException.class)
    public void bindingSet_resPathRootRejects() {
        when(logicalDbMapper.selectById(LOGICAL_DB_ID)).thenReturn(enabledLogicalDb());
        when(envMapper.queryByEnvID(PUID, ENV_ID)).thenReturn(env(ENV_ID));
        when(dsMapper.listByUser(PUID)).thenReturn(Collections.singletonList(ds(DS_ID)));
        when(envParamService.queryParam(PUID, ENV_ID, EnvParamKeys.GOV_ROLE)).thenReturn(null);

        BindingSetFO fo = new BindingSetFO();
        fo.setLogicalDbId(LOGICAL_DB_ID);
        fo.setBindings(Collections.singletonList(bindingItem(ENV_ID, DS_ID, "/")));

        service.bindingSet(PUID, UID, fo);
    }

    @Test(expected = ErrorMessageException.class)
    public void bindingSet_resPathTooDeepRejects() {
        when(logicalDbMapper.selectById(LOGICAL_DB_ID)).thenReturn(enabledLogicalDb());
        when(envMapper.queryByEnvID(PUID, ENV_ID)).thenReturn(env(ENV_ID));
        when(dsMapper.listByUser(PUID)).thenReturn(Collections.singletonList(ds(DS_ID)));
        when(envParamService.queryParam(PUID, ENV_ID, EnvParamKeys.GOV_ROLE)).thenReturn(null);

        BindingSetFO fo = new BindingSetFO();
        fo.setLogicalDbId(LOGICAL_DB_ID);
        // 3 segments = table level → reject
        fo.setBindings(Collections.singletonList(bindingItem(ENV_ID, DS_ID, "/db/schema/table/")));

        service.bindingSet(PUID, UID, fo);
    }

    @Test(expected = ErrorMessageException.class)
    public void bindingSet_duplicateEnvIdRejects() {
        when(logicalDbMapper.selectById(LOGICAL_DB_ID)).thenReturn(enabledLogicalDb());
        when(envMapper.queryByEnvID(PUID, ENV_ID)).thenReturn(env(ENV_ID));
        when(dsMapper.listByUser(PUID)).thenReturn(Arrays.asList(ds(DS_ID), ds(DS_ID_2)));
        when(envParamService.queryParam(PUID, ENV_ID, EnvParamKeys.GOV_ROLE)).thenReturn(null);

        BindingSetFO fo = new BindingSetFO();
        fo.setLogicalDbId(LOGICAL_DB_ID);
        // same envId twice
        fo.setBindings(Arrays.asList(
            bindingItem(ENV_ID, DS_ID, "/mydb/"),
            bindingItem(ENV_ID, DS_ID_2, "/otherdb/")));

        service.bindingSet(PUID, UID, fo);
    }

    @Test(expected = ErrorMessageException.class)
    public void bindingSet_govRoleConflictRejects() {
        when(logicalDbMapper.selectById(LOGICAL_DB_ID)).thenReturn(enabledLogicalDb());
        when(envMapper.queryByEnvID(PUID, ENV_ID)).thenReturn(env(ENV_ID));
        when(envMapper.queryByEnvID(PUID, ENV_ID_2)).thenReturn(env(ENV_ID_2));
        when(dsMapper.listByUser(PUID)).thenReturn(Arrays.asList(ds(DS_ID), ds(DS_ID_2)));
        // both envs have GOV_ROLE=PRE → conflict
        when(envParamService.queryParam(PUID, ENV_ID, EnvParamKeys.GOV_ROLE)).thenReturn(GovRole.PRE.name());
        when(envParamService.queryParam(PUID, ENV_ID_2, EnvParamKeys.GOV_ROLE)).thenReturn(GovRole.PRE.name());

        BindingSetFO fo = new BindingSetFO();
        fo.setLogicalDbId(LOGICAL_DB_ID);
        fo.setBindings(Arrays.asList(
            bindingItem(ENV_ID, DS_ID, "/mydb/"),
            bindingItem(ENV_ID_2, DS_ID_2, "/otherdb/")));

        service.bindingSet(PUID, UID, fo);
    }

    @Test
    public void bindingSet_emptyListClearsBindings() {
        when(logicalDbMapper.selectById(LOGICAL_DB_ID)).thenReturn(enabledLogicalDb());

        BindingSetFO fo = new BindingSetFO();
        fo.setLogicalDbId(LOGICAL_DB_ID);
        fo.setBindings(Collections.emptyList());

        service.bindingSet(PUID, UID, fo);

        verify(bindingMapper).deleteByLogicalDbId(LOGICAL_DB_ID);
        verify(bindingMapper, never()).insert(any(DmLogicalDbEnvBindingDO.class));
    }

    // ==================== bindingList ====================

    @Test
    public void bindingList_nullParamsShowDefaults() {
        when(logicalDbMapper.selectById(LOGICAL_DB_ID)).thenReturn(enabledLogicalDb());
        when(bindingMapper.listByLogicalDbId(LOGICAL_DB_ID))
            .thenReturn(Collections.singletonList(binding(BINDING_ID, ENV_ID, DS_ID, "/mydb/")));
        when(envMapper.queryByEnvID(PUID, ENV_ID)).thenReturn(env(ENV_ID));
        when(dsMapper.listByUser(PUID)).thenReturn(Collections.singletonList(ds(DS_ID)));
        // all params null = not configured
        when(envParamService.queryParam(eq(PUID), eq(ENV_ID), any(String.class))).thenReturn(null);

        List<LogicalDbBindingVO> result = service.bindingList(PUID, LOGICAL_DB_ID);
        assertEquals(1, result.size());
        LogicalDbBindingVO vo = result.get(0);
        assertNull(vo.getGovRole());
        assertNull(vo.getGovDmlDirect());
        assertNull(vo.getGovDmlRowLimit());
        assertNull(vo.getGovAutoConfirm());
        assertEquals("env-" + ENV_ID, vo.getEnvName());
        assertEquals("instance-" + DS_ID, vo.getDsName());
    }

    // ==================== getBinding ====================

    @Test
    public void getBinding_oneMatch_returnsTarget() {
        when(logicalDbMapper.selectById(LOGICAL_DB_ID)).thenReturn(enabledLogicalDb());
        when(bindingMapper.listByLogicalDbId(LOGICAL_DB_ID))
            .thenReturn(Collections.singletonList(binding(BINDING_ID, ENV_ID, DS_ID, "/mydb/")));
        when(envParamService.queryParam(PUID, ENV_ID, EnvParamKeys.GOV_ROLE)).thenReturn(GovRole.PRE.name());

        LogicalDbTarget target = service.getBinding(PUID, LOGICAL_DB_ID, GovRole.PRE);

        assertEquals(Long.valueOf(BINDING_ID), target.getBindingId());
        assertEquals(Long.valueOf(LOGICAL_DB_ID), target.getLogicalDbId());
        assertEquals(Long.valueOf(ENV_ID), target.getEnvId());
        assertEquals(Long.valueOf(DS_ID), target.getDsId());
        assertEquals("/mydb/", target.getResPath());
        assertEquals(GovRole.PRE, target.getGovRole());
    }

    @Test(expected = ErrorMessageException.class)
    public void getBinding_zeroMatch_throws() {
        when(logicalDbMapper.selectById(LOGICAL_DB_ID)).thenReturn(enabledLogicalDb());
        when(bindingMapper.listByLogicalDbId(LOGICAL_DB_ID))
            .thenReturn(Collections.singletonList(binding(BINDING_ID, ENV_ID, DS_ID, "/mydb/")));
        when(envParamService.queryParam(PUID, ENV_ID, EnvParamKeys.GOV_ROLE)).thenReturn(null);

        service.getBinding(PUID, LOGICAL_DB_ID, GovRole.PROD);
    }

    @Test(expected = ErrorMessageException.class)
    public void getBinding_multipleMatches_throws() {
        when(logicalDbMapper.selectById(LOGICAL_DB_ID)).thenReturn(enabledLogicalDb());
        when(bindingMapper.listByLogicalDbId(LOGICAL_DB_ID)).thenReturn(Arrays.asList(
            binding(BINDING_ID, ENV_ID, DS_ID, "/mydb/"),
            binding(BINDING_ID + 1, ENV_ID_2, DS_ID_2, "/otherdb/")));
        when(envParamService.queryParam(PUID, ENV_ID, EnvParamKeys.GOV_ROLE)).thenReturn(GovRole.PROD.name());
        when(envParamService.queryParam(PUID, ENV_ID_2, EnvParamKeys.GOV_ROLE)).thenReturn(GovRole.PROD.name());

        service.getBinding(PUID, LOGICAL_DB_ID, GovRole.PROD);
    }

    @Test(expected = ErrorMessageException.class)
    public void getBinding_disabledDb_throws() {
        when(logicalDbMapper.selectById(LOGICAL_DB_ID)).thenReturn(disabledLogicalDb());

        service.getBinding(PUID, LOGICAL_DB_ID, GovRole.PRE);
    }

    @Test(expected = ErrorMessageException.class)
    public void getBinding_notOwned_throws() {
        DmLogicalDbDO db = enabledLogicalDb();
        db.setCreatorUid("other-puid");
        when(logicalDbMapper.selectById(LOGICAL_DB_ID)).thenReturn(db);

        service.getBinding(PUID, LOGICAL_DB_ID, GovRole.PRE);
    }

    // ==================== myLogicalDbs ====================

    @Test
    public void myLogicalDbs_puidShortCircuit_allVisible() {
        when(logicalDbMapper.selectList(any())).thenReturn(Collections.singletonList(enabledLogicalDb()));
        when(bindingMapper.listByLogicalDbId(LOGICAL_DB_ID))
            .thenReturn(Collections.singletonList(binding(BINDING_ID, ENV_ID, DS_ID, "/mydb/")));
        when(envParamService.queryParam(PUID, ENV_ID, EnvParamKeys.GOV_ROLE)).thenReturn(GovRole.PRE.name());
        when(dsMapper.listByUser(PUID)).thenReturn(Collections.singletonList(dsWithType(DS_ID, DataSourceType.MySQL)));

        // uid == puid → short-circuit, no auth query
        List<MyLogicalDbVO> result = service.myLogicalDbs(PUID, PUID);
        assertEquals(1, result.size());
        assertEquals("ORDER_DB", result.get(0).getResourceCode());
        assertEquals("MySQL", result.get(0).getDsType());
        // verify listByKind was NOT called (short-circuit)
        verify(resMapper, never()).listByKind(any(), any());
    }

    @Test
    public void myLogicalDbs_directAuthMatch_visible() {
        when(logicalDbMapper.selectList(any())).thenReturn(Collections.singletonList(enabledLogicalDb()));
        when(bindingMapper.listByLogicalDbId(LOGICAL_DB_ID))
            .thenReturn(Collections.singletonList(binding(BINDING_ID, ENV_ID, DS_ID, "/mydb/")));
        when(envParamService.queryParam(PUID, ENV_ID, EnvParamKeys.GOV_ROLE)).thenReturn(GovRole.PRE.name());
        when(dsMapper.listByUser(PUID)).thenReturn(Collections.singletonList(dsWithType(DS_ID, DataSourceType.PostgreSQL)));
        // direct grant auth row
        when(resMapper.listByKind(UID, AuthKind.DataSource))
            .thenReturn(Collections.singletonList(authRow(DS_ID, "/mydb/", "direct grant")));

        List<MyLogicalDbVO> result = service.myLogicalDbs(PUID, UID);
        assertEquals(1, result.size());
        assertEquals("PostgreSQL", result.get(0).getDsType());
    }

    @Test
    public void myLogicalDbs_permGroupAuthMatch_visible() {
        when(logicalDbMapper.selectList(any())).thenReturn(Collections.singletonList(enabledLogicalDb()));
        when(bindingMapper.listByLogicalDbId(LOGICAL_DB_ID))
            .thenReturn(Collections.singletonList(binding(BINDING_ID, ENV_ID, DS_ID, "/mydb/")));
        // GOV_ROLE=PROD only (no PRE binding) → dsType null, listByUser not called
        when(envParamService.queryParam(PUID, ENV_ID, EnvParamKeys.GOV_ROLE)).thenReturn(GovRole.PROD.name());
        // PERM_GROUP expanded auth row
        when(resMapper.listByKind(UID, AuthKind.DataSource))
            .thenReturn(Collections.singletonList(authRow(DS_ID, "/mydb/", "PERM_GROUP:1:5")));

        List<MyLogicalDbVO> result = service.myLogicalDbs(PUID, UID);
        assertEquals(1, result.size());
        assertNull(result.get(0).getDsType());
        // no PRE binding → dsType lazy load skipped
        verify(dsMapper, never()).listByUser(any());
    }

    @Test
    public void myLogicalDbs_expiredAuthNoMatch_notVisible() {
        when(logicalDbMapper.selectList(any())).thenReturn(Collections.singletonList(enabledLogicalDb()));
        when(bindingMapper.listByLogicalDbId(LOGICAL_DB_ID))
            .thenReturn(Collections.singletonList(binding(BINDING_ID, ENV_ID, DS_ID, "/mydb/")));
        when(envParamService.queryParam(PUID, ENV_ID, EnvParamKeys.GOV_ROLE)).thenReturn(GovRole.PRE.name());
        // expired auth row → isEffective() = false
        when(resMapper.listByKind(UID, AuthKind.DataSource))
            .thenReturn(Collections.singletonList(expiredAuthRow(DS_ID, "/mydb/")));

        List<MyLogicalDbVO> result = service.myLogicalDbs(PUID, UID);
        assertTrue(result.isEmpty());
    }

    @Test
    public void myLogicalDbs_noBindings_notVisible() {
        DmLogicalDbDO db = enabledLogicalDb();
        when(logicalDbMapper.selectList(any())).thenReturn(Collections.singletonList(db));
        when(bindingMapper.listByLogicalDbId(LOGICAL_DB_ID)).thenReturn(Collections.emptyList());

        List<MyLogicalDbVO> result = service.myLogicalDbs(PUID, UID);
        assertTrue(result.isEmpty());
    }

    @Test
    public void myLogicalDbs_emptyGovRole_notParticipating() {
        when(logicalDbMapper.selectList(any())).thenReturn(Collections.singletonList(enabledLogicalDb()));
        when(bindingMapper.listByLogicalDbId(LOGICAL_DB_ID))
            .thenReturn(Collections.singletonList(binding(BINDING_ID, ENV_ID, DS_ID, "/mydb/")));
        // GOV_ROLE = null → binding doesn't participate
        when(envParamService.queryParam(PUID, ENV_ID, EnvParamKeys.GOV_ROLE)).thenReturn(null);

        List<MyLogicalDbVO> result = service.myLogicalDbs(PUID, UID);
        assertTrue(result.isEmpty());
    }

    @Test
    public void myLogicalDbs_hasAnyAuthRowVisible_labelNotFiltered() {
        when(logicalDbMapper.selectList(any())).thenReturn(Collections.singletonList(enabledLogicalDb()));
        when(bindingMapper.listByLogicalDbId(LOGICAL_DB_ID))
            .thenReturn(Collections.singletonList(binding(BINDING_ID, ENV_ID, DS_ID, "/mydb/")));
        when(envParamService.queryParam(PUID, ENV_ID, EnvParamKeys.GOV_ROLE)).thenReturn(GovRole.PRE.name());
        when(dsMapper.listByUser(PUID)).thenReturn(Collections.singletonList(dsWithType(DS_ID, DataSourceType.MySQL)));
        // auth row with empty labels list — still matches (label not filtered)
        DmAuthResDO row = authRow(DS_ID, "/mydb/", null);
        row.setAuthLabels(new ArrayList<>());
        when(resMapper.listByKind(UID, AuthKind.DataSource))
            .thenReturn(Collections.singletonList(row));

        List<MyLogicalDbVO> result = service.myLogicalDbs(PUID, UID);
        assertEquals(1, result.size());
        assertEquals("MySQL", result.get(0).getDsType());
    }

    @Test
    public void myLogicalDbs_bidirectionalPrefixMatch_visible() {
        when(logicalDbMapper.selectList(any())).thenReturn(Collections.singletonList(enabledLogicalDb()));
        // binding path is deeper than auth row path
        when(bindingMapper.listByLogicalDbId(LOGICAL_DB_ID))
            .thenReturn(Collections.singletonList(binding(BINDING_ID, ENV_ID, DS_ID, "/mydb/myschema/")));
        when(envParamService.queryParam(PUID, ENV_ID, EnvParamKeys.GOV_ROLE)).thenReturn(GovRole.PRE.name());
        when(dsMapper.listByUser(PUID)).thenReturn(Collections.singletonList(dsWithType(DS_ID, DataSourceType.MySQL)));
        // auth row path is shorter → binding path starts with auth path → match
        when(resMapper.listByKind(UID, AuthKind.DataSource))
            .thenReturn(Collections.singletonList(authRow(DS_ID, "/mydb/", "direct")));

        List<MyLogicalDbVO> result = service.myLogicalDbs(PUID, UID);
        assertEquals(1, result.size());
        assertEquals("MySQL", result.get(0).getDsType());
    }

    @Test
    public void myLogicalDbs_multipleLogicalDbs_callsListByKindOnce() {
        // D1: listByKind must be called exactly once regardless of logical db count
        DmLogicalDbDO db1 = enabledLogicalDb();
        DmLogicalDbDO db2 = enabledLogicalDb();
        db2.setId(2L);
        db2.setResourceCode("USER_DB");
        when(logicalDbMapper.selectList(any())).thenReturn(Arrays.asList(db1, db2));

        when(bindingMapper.listByLogicalDbId(LOGICAL_DB_ID))
            .thenReturn(Collections.singletonList(binding(BINDING_ID, ENV_ID, DS_ID, "/mydb/")));
        when(bindingMapper.listByLogicalDbId(2L))
            .thenReturn(Collections.singletonList(binding(600L, ENV_ID_2, DS_ID_2, "/userdb/")));
        when(envParamService.queryParam(PUID, ENV_ID, EnvParamKeys.GOV_ROLE)).thenReturn(GovRole.PRE.name());
        when(envParamService.queryParam(PUID, ENV_ID_2, EnvParamKeys.GOV_ROLE)).thenReturn(GovRole.PROD.name());
        when(dsMapper.listByUser(PUID)).thenReturn(Arrays.asList(
            dsWithType(DS_ID, DataSourceType.MySQL),
            dsWithType(DS_ID_2, DataSourceType.PostgreSQL)));
        when(resMapper.listByKind(UID, AuthKind.DataSource))
            .thenReturn(Arrays.asList(
                authRow(DS_ID, "/mydb/", "direct"),
                authRow(DS_ID_2, "/userdb/", "PERM_GROUP:1:5")));

        List<MyLogicalDbVO> result = service.myLogicalDbs(PUID, UID);
        assertEquals(2, result.size());
        // db1 has PRE binding → dsType resolved; db2 has PROD only → dsType null
        assertEquals("MySQL", result.get(0).getDsType());
        assertNull(result.get(1).getDsType());
        // D1: single listByKind call regardless of N logical dbs
        verify(resMapper, times(1)).listByKind(UID, AuthKind.DataSource);
        // single listByUser call regardless of N logical dbs (lazy dsType cache)
        verify(dsMapper, times(1)).listByUser(PUID);
    }

    @Test
    public void myLogicalDbs_preBindingButDsLookupMiss_dsTypeNullNoThrow() {
        when(logicalDbMapper.selectList(any())).thenReturn(Collections.singletonList(enabledLogicalDb()));
        when(bindingMapper.listByLogicalDbId(LOGICAL_DB_ID))
            .thenReturn(Collections.singletonList(binding(BINDING_ID, ENV_ID, DS_ID, "/mydb/")));
        when(envParamService.queryParam(PUID, ENV_ID, EnvParamKeys.GOV_ROLE)).thenReturn(GovRole.PRE.name());
        // PRE binding exists but ds is absent from listByUser result (e.g. deleted) → dsType null, no exception
        when(dsMapper.listByUser(PUID)).thenReturn(Collections.emptyList());
        when(resMapper.listByKind(UID, AuthKind.DataSource))
            .thenReturn(Collections.singletonList(authRow(DS_ID, "/mydb/", "direct")));

        List<MyLogicalDbVO> result = service.myLogicalDbs(PUID, UID);
        assertEquals(1, result.size());
        assertNull(result.get(0).getDsType());
    }
}
