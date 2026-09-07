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
package com.clougence.clouddm.console.web.component.auth.impl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.*;

import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.console.web.component.auth.DmAuthLabelService;
import com.clougence.clouddm.console.web.model.fo.security.BatchModifyUserAuthFO;
import com.clougence.clouddm.console.web.model.fo.security.BatchModifyUserAuthOperation;
import com.clougence.clouddm.console.web.model.fo.security.ModifyAuthForAppend;
import com.clougence.clouddm.console.web.model.fo.security.ModifyAuthForDelete;
import com.clougence.clouddm.console.web.model.fo.security.ModifyUserAuthFO;
import com.clougence.clouddm.platform.dal.access.AuthDal;
import com.clougence.clouddm.platform.dal.access.DataSourceDal;
import com.clougence.clouddm.platform.dal.mapper.auth.DmAuthResMapper;
import com.clougence.clouddm.platform.dal.mapper.datasource.DmDsMapper;
import com.clougence.clouddm.platform.dal.model.auth.DmAuthResDO;
import com.clougence.clouddm.platform.dal.model.datasource.DmDsDO;
import com.clougence.clouddm.sdk.security.auth.AuthInfo;
import com.clougence.clouddm.sdk.security.auth.AuthInfoType;
import com.clougence.clouddm.sdk.security.auth.AuthKind;

/**
 * Touchpoint #4 tests: verify that the 3 individual-revocation paths reject
 * permission-group-expanded auth rows (res_desc starts with "PERM_GROUP:"),
 * and that unmarked rows pass through with zero behavior change.
 */
public class DmAuthServiceForManageTouchpointTest {

    private DmAuthServiceForManageImpl service;

    private AuthDal          authDal;
    private DmAuthResMapper  resMapper;
    private DataSourceDal    dsDal;
    private DmDsMapper       dsMapper;
    private DmAuthLabelService authLabelService;

    private static final String PUID       = "0000000000000001";
    private static final String TARGET_UID = "0000000000000002";
    private static final long   DS_ID      = 100L;
    private static final String RES_PATH   = "/catalog/";

    @Before
    public void setUp() {
        service = new DmAuthServiceForManageImpl();

        authDal = mock(AuthDal.class);
        resMapper = mock(DmAuthResMapper.class);
        when(authDal.resMapper()).thenReturn(resMapper);

        dsDal = mock(DataSourceDal.class);
        dsMapper = mock(DmDsMapper.class);
        when(dsDal.dsMapper()).thenReturn(dsMapper);

        authLabelService = mock(DmAuthLabelService.class);
        // return a known label so evalLabels treats DM_QUERY as a known label
        AuthInfo queryLabel = new AuthInfo();
        queryLabel.setKey("DM_QUERY");
        queryLabel.setAuthType(AuthInfoType.Auth);
        when(authLabelService.getDataAuthLabel()).thenReturn(Collections.singletonList(queryLabel));
        // cascade returns self (no additional cascade)
        AuthInfo cascadeInfo = new AuthInfo();
        cascadeInfo.setKey("DM_QUERY");
        when(authLabelService.getCascadeAuthByLabel("DM_QUERY")).thenReturn(Collections.singletonList(cascadeInfo));

        ReflectionTestUtils.setField(service, "authDal", authDal);
        ReflectionTestUtils.setField(service, "dsDal", dsDal);
        ReflectionTestUtils.setField(service, "authLabelService", authLabelService);
    }

    private DmDsDO ds(long id) {
        DmDsDO ds = new DmDsDO();
        ds.setId(id);
        ds.setInstanceId("inst-" + id);
        ds.setInstanceDesc("desc-" + id);
        return ds;
    }

    private DmAuthResDO permGroupRow() {
        DmAuthResDO row = new DmAuthResDO();
        row.setId(1L);
        row.setResId(DS_ID);
        row.setResPath(RES_PATH);
        row.setKindType(AuthKind.DataSource);
        row.setOwnerUid(TARGET_UID);
        row.setResDesc("PERM_GROUP:1:1");
        row.setAuthLabels(new ArrayList<>(Arrays.asList("DM_QUERY")));
        return row;
    }

    private DmAuthResDO normalRow() {
        DmAuthResDO row = new DmAuthResDO();
        row.setId(2L);
        row.setResId(DS_ID);
        row.setResPath(RES_PATH);
        row.setKindType(AuthKind.DataSource);
        row.setOwnerUid(TARGET_UID);
        row.setResDesc("normal desc");
        row.setAuthLabels(new ArrayList<>(Arrays.asList("DM_QUERY")));
        return row;
    }

    // ==================== Touchpoint 1: deleteDataAuth (modifyUserAuth deletes) ====================

    @Test(expected = ErrorMessageException.class)
    public void deleteDataAuth_permGroupRow_throws() {
        // checkResOwner: selectBatchIds returns row with resId
        when(resMapper.selectBatchIds(Collections.singletonList(1L))).thenReturn(Collections.singletonList(permGroupRow()));
        // checkResOwner: dsMapper.listByUser returns DS that owns resId
        when(dsMapper.listByUser(PUID)).thenReturn(Collections.singletonList(ds(DS_ID)));
        // deleteDataAuth: queryByPath returns PERM_GROUP row
        when(resMapper.queryByPath(DS_ID, TARGET_UID, AuthKind.DataSource, RES_PATH))
            .thenReturn(Collections.singletonList(permGroupRow()));

        ModifyUserAuthFO fo = new ModifyUserAuthFO();
        fo.setAuthKind(AuthKind.DataSource);
        fo.setTargetUid(TARGET_UID);
        ModifyAuthForDelete delete = new ModifyAuthForDelete();
        delete.setAuthId(1L);
        delete.setResId(DS_ID);
        delete.setResPaths(Arrays.asList("catalog"));
        fo.setDeletes(Collections.singletonList(delete));

        service.modifyUserAuth(PUID, fo);
    }

    @Test
    public void deleteDataAuth_normalRow_passesThrough() {
        when(resMapper.selectBatchIds(Collections.singletonList(2L))).thenReturn(Collections.singletonList(normalRow()));
        when(dsMapper.listByUser(PUID)).thenReturn(Collections.singletonList(ds(DS_ID)));
        when(resMapper.queryByPath(DS_ID, TARGET_UID, AuthKind.DataSource, RES_PATH))
            .thenReturn(Collections.singletonList(normalRow()));
        // listByKind called by appendDataAuth (with empty append list)
        when(resMapper.listByKind(TARGET_UID, AuthKind.DataSource)).thenReturn(Collections.emptyList());

        ModifyUserAuthFO fo = new ModifyUserAuthFO();
        fo.setAuthKind(AuthKind.DataSource);
        fo.setTargetUid(TARGET_UID);
        ModifyAuthForDelete delete = new ModifyAuthForDelete();
        delete.setAuthId(2L);
        delete.setResId(DS_ID);
        delete.setResPaths(Arrays.asList("catalog"));
        fo.setDeletes(Collections.singletonList(delete));

        service.modifyUserAuth(PUID, fo);

        // deleteByPath should have been called (no exception)
        verify(resMapper).deleteByPath(DS_ID, TARGET_UID, AuthKind.DataSource, RES_PATH);
    }

    // ==================== Touchpoint 2: appendDataAuth (modifyUserAuth appends) ====================

    @Test(expected = ErrorMessageException.class)
    public void appendDataAuth_oldPermGroupRow_throws() {
        when(dsMapper.listByUser(PUID)).thenReturn(Collections.singletonList(ds(DS_ID)));
        when(dsMapper.listByIds(Collections.singletonList(DS_ID))).thenReturn(Collections.singletonList(ds(DS_ID)));
        // listByKind returns old PERM_GROUP row
        when(resMapper.listByKind(TARGET_UID, AuthKind.DataSource)).thenReturn(Collections.singletonList(permGroupRow()));

        ModifyUserAuthFO fo = new ModifyUserAuthFO();
        fo.setAuthKind(AuthKind.DataSource);
        fo.setTargetUid(TARGET_UID);
        ModifyAuthForAppend append = new ModifyAuthForAppend();
        append.setResId(DS_ID);
        append.setResPaths(Arrays.asList("catalog"));
        append.setAuthLabels(Arrays.asList("DM_QUERY"));
        fo.setAppends(Collections.singletonList(append));

        service.modifyUserAuth(PUID, fo);
    }

    // ==================== Touchpoint 3: revokeGrantedAuth (batchModifyUserAuth REVOKE) ====================

    @Test(expected = ErrorMessageException.class)
    public void revokeGrantedAuth_permGroupRow_throws() {
        when(dsMapper.listByUser(PUID)).thenReturn(Collections.singletonList(ds(DS_ID)));
        // queryByPath returns PERM_GROUP row with matching labels
        when(resMapper.queryByPath(DS_ID, TARGET_UID, AuthKind.DataSource, RES_PATH))
            .thenReturn(Collections.singletonList(permGroupRow()));

        BatchModifyUserAuthFO fo = new BatchModifyUserAuthFO();
        fo.setAuthKind(AuthKind.DataSource);
        fo.setOperation(BatchModifyUserAuthOperation.REVOKE);
        fo.setTargetUids(Collections.singletonList(TARGET_UID));
        ModifyAuthForAppend change = new ModifyAuthForAppend();
        change.setResId(DS_ID);
        change.setResPaths(Arrays.asList("catalog"));
        change.setAuthLabels(Arrays.asList("DM_QUERY"));
        fo.setChanges(Collections.singletonList(change));

        service.batchModifyUserAuth(PUID, fo);
    }

    @Test
    public void revokeGrantedAuth_normalRow_succeeds() {
        when(dsMapper.listByUser(PUID)).thenReturn(Collections.singletonList(ds(DS_ID)));
        when(resMapper.queryByPath(DS_ID, TARGET_UID, AuthKind.DataSource, RES_PATH))
            .thenReturn(Collections.singletonList(normalRow()));

        BatchModifyUserAuthFO fo = new BatchModifyUserAuthFO();
        fo.setAuthKind(AuthKind.DataSource);
        fo.setOperation(BatchModifyUserAuthOperation.REVOKE);
        fo.setTargetUids(Collections.singletonList(TARGET_UID));
        ModifyAuthForAppend change = new ModifyAuthForAppend();
        change.setResId(DS_ID);
        change.setResPaths(Arrays.asList("catalog"));
        change.setAuthLabels(Arrays.asList("DM_QUERY"));
        fo.setChanges(Collections.singletonList(change));

        service.batchModifyUserAuth(PUID, fo);

        // deleteById should have been called (all labels revoked → row deleted)
        verify(resMapper).deleteById(2L);
    }

    @Test(expected = ErrorMessageException.class)
    public void revokeGrantedAuth_permGroupRow_partialLabelRevoke_throws() {
        // PERM_GROUP row with DM_DML + DM_QUERY labels; revoke only DM_QUERY (partial)
        DmAuthResDO row = permGroupRow();
        row.setAuthLabels(new ArrayList<>(Arrays.asList("DM_DML", "DM_QUERY")));

        when(dsMapper.listByUser(PUID)).thenReturn(Collections.singletonList(ds(DS_ID)));
        when(resMapper.queryByPath(DS_ID, TARGET_UID, AuthKind.DataSource, RES_PATH))
            .thenReturn(Collections.singletonList(row));

        BatchModifyUserAuthFO fo = new BatchModifyUserAuthFO();
        fo.setAuthKind(AuthKind.DataSource);
        fo.setOperation(BatchModifyUserAuthOperation.REVOKE);
        fo.setTargetUids(Collections.singletonList(TARGET_UID));
        ModifyAuthForAppend change = new ModifyAuthForAppend();
        change.setResId(DS_ID);
        change.setResPaths(Arrays.asList("catalog"));
        // revoke only DM_QUERY (partial — not all labels, so updateById path)
        change.setAuthLabels(Arrays.asList("DM_QUERY"));
        fo.setChanges(Collections.singletonList(change));

        service.batchModifyUserAuth(PUID, fo);
    }
}
