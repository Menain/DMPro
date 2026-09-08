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
package com.clougence.clouddm.console.web.service.permpgroup.impl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.*;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.console.web.component.auth.DmAuthServiceForManage;
import com.clougence.clouddm.console.web.model.fo.permpgroup.CreatePermGroupFO;
import com.clougence.clouddm.console.web.model.fo.permpgroup.PermGroupMemberFO;
import com.clougence.clouddm.console.web.model.fo.permpgroup.PermGroupResourceFO;
import com.clougence.clouddm.console.web.model.fo.permpgroup.PermGroupResourceRevokeFO;
import com.clougence.clouddm.console.web.model.fo.permpgroup.UpdatePermGroupFO;
import com.clougence.clouddm.console.web.model.vo.permpgroup.PermGroupVO;
import com.clougence.clouddm.platform.dal.access.AuthDal;
import com.clougence.clouddm.platform.dal.access.DataSourceDal;
import com.clougence.clouddm.platform.dal.access.PermGroupDal;
import com.clougence.clouddm.platform.dal.mapper.auth.DmAuthResMapper;
import com.clougence.clouddm.platform.dal.mapper.auth.DmAuthUserMapper;
import com.clougence.clouddm.platform.dal.mapper.datasource.DmDsMapper;
import com.clougence.clouddm.platform.dal.mapper.permpgroup.DmPermGroupGrantRecordMapper;
import com.clougence.clouddm.platform.dal.mapper.permpgroup.DmPermGroupMapper;
import com.clougence.clouddm.platform.dal.mapper.permpgroup.DmPermGroupMemberMapper;
import com.clougence.clouddm.platform.dal.mapper.permpgroup.DmPermGroupResourceMapper;
import com.clougence.clouddm.platform.dal.model.auth.DmAuthResDO;
import com.clougence.clouddm.platform.dal.model.auth.DmAuthUserDO;
import com.clougence.clouddm.platform.dal.model.datasource.DmDsDO;
import com.clougence.clouddm.platform.dal.model.permpgroup.DmPermGroupDO;
import com.clougence.clouddm.platform.dal.model.permpgroup.DmPermGroupGrantRecordDO;
import com.clougence.clouddm.platform.dal.model.permpgroup.DmPermGroupMemberDO;
import com.clougence.clouddm.platform.dal.model.permpgroup.DmPermGroupResourceDO;
import com.clougence.clouddm.platform.dal.model.permpgroup.PermGroupStatus;
import com.clougence.clouddm.sdk.security.auth.AuthInfo;
import com.clougence.clouddm.sdk.security.auth.AuthInfoType;
import com.clougence.clouddm.sdk.security.auth.AuthKind;

public class PermGroupServiceImplTest {

    private PermGroupServiceImpl         service;

    private PermGroupDal                 permGroupDal;
    private DmPermGroupMapper            groupMapper;
    private DmPermGroupMemberMapper      memberMapper;
    private DmPermGroupResourceMapper    resourceMapper;
    private DmPermGroupGrantRecordMapper grantRecordMapper;

    private AuthDal                      authDal;
    private DmAuthResMapper              resMapper;
    private DmAuthUserMapper             userMapper;

    private DataSourceDal                dsDal;
    private DmDsMapper                   dsMapper;

    private DmAuthServiceForManage      authServiceForManage;

    private static final String PUID      = "0000000000000001";
    private static final String MEMBER_UID = "0000000000000002";
    private static final long   GROUP_ID  = 1L;
    private static final long   DS_ID     = 100L;
    private static final long   RESOURCE_ID = 10L;
    private static final long   AUTH_RES_ID = 500L;

    @Before
    public void setUp() {
        service = new PermGroupServiceImpl();

        permGroupDal = mock(PermGroupDal.class);
        groupMapper = mock(DmPermGroupMapper.class);
        memberMapper = mock(DmPermGroupMemberMapper.class);
        resourceMapper = mock(DmPermGroupResourceMapper.class);
        grantRecordMapper = mock(DmPermGroupGrantRecordMapper.class);
        when(permGroupDal.permGroupMapper()).thenReturn(groupMapper);
        when(permGroupDal.permGroupMemberMapper()).thenReturn(memberMapper);
        when(permGroupDal.permGroupResourceMapper()).thenReturn(resourceMapper);
        when(permGroupDal.permGroupGrantRecordMapper()).thenReturn(grantRecordMapper);

        authDal = mock(AuthDal.class);
        resMapper = mock(DmAuthResMapper.class);
        userMapper = mock(DmAuthUserMapper.class);
        when(authDal.resMapper()).thenReturn(resMapper);
        when(authDal.userMapper()).thenReturn(userMapper);

        dsDal = mock(DataSourceDal.class);
        dsMapper = mock(DmDsMapper.class);
        when(dsDal.dsMapper()).thenReturn(dsMapper);

        authServiceForManage = mock(DmAuthServiceForManage.class);
        // return a known label so validateLabels passes for DM_QUERY
        AuthInfo queryLabel = new AuthInfo();
        queryLabel.setKey("DM_QUERY");
        queryLabel.setAuthType(AuthInfoType.Auth);
        when(authServiceForManage.getDataAuthLabel()).thenReturn(Collections.singletonList(queryLabel));

        ReflectionTestUtils.setField(service, "permGroupDal", permGroupDal);
        ReflectionTestUtils.setField(service, "authDal", authDal);
        ReflectionTestUtils.setField(service, "dsDal", dsDal);
        ReflectionTestUtils.setField(service, "authServiceForManage", authServiceForManage);
    }

    private DmPermGroupDO activeGroup() {
        DmPermGroupDO group = new DmPermGroupDO();
        group.setId(GROUP_ID);
        group.setGroupCode("TEST_GROUP");
        group.setGroupName("Test Group");
        group.setStatus(PermGroupStatus.ACTIVE.name());
        group.setCreatorUid(PUID);
        return group;
    }

    private DmPermGroupResourceDO dsResource() {
        DmPermGroupResourceDO res = new DmPermGroupResourceDO();
        res.setId(RESOURCE_ID);
        res.setGroupId(GROUP_ID);
        res.setAuthKind(AuthKind.DataSource);
        res.setResId(DS_ID);
        res.setResPath("/catalog/schema/");
        res.setAuthLabels(new ArrayList<>(Arrays.asList("DM_QUERY")));
        return res;
    }

    private DmDsDO ds() {
        DmDsDO ds = new DmDsDO();
        ds.setId(DS_ID);
        ds.setInstanceId("test-instance");
        return ds;
    }

    private DmAuthUserDO user(String uid) {
        DmAuthUserDO user = new DmAuthUserDO();
        user.setUid(uid);
        user.setUsername("user-" + uid);
        return user;
    }

    // ==================== Group CRUD ====================

    @Test
    public void createGroup_insertsActiveGroup() {
        when(groupMapper.queryByGroupCode("TEST_GROUP")).thenReturn(null);

        CreatePermGroupFO fo = new CreatePermGroupFO();
        fo.setGroupCode("TEST_GROUP");
        fo.setGroupName("Test Group");
        fo.setDescription("desc");

        PermGroupVO vo = service.createGroup(PUID, "uid", fo);

        ArgumentCaptor<DmPermGroupDO> captor = ArgumentCaptor.forClass(DmPermGroupDO.class);
        verify(groupMapper).insert(captor.capture());
        assertEquals(PermGroupStatus.ACTIVE.name(), captor.getValue().getStatus());
        assertEquals(PUID, captor.getValue().getCreatorUid());
        assertEquals("TEST_GROUP", vo.getGroupCode());
    }

    @Test(expected = ErrorMessageException.class)
    public void createGroup_duplicateCode_throws() {
        when(groupMapper.queryByGroupCode("DUP")).thenReturn(activeGroup());
        CreatePermGroupFO fo = new CreatePermGroupFO();
        fo.setGroupCode("DUP");
        fo.setGroupName("dup");
        service.createGroup(PUID, "uid", fo);
    }

    @Test(expected = ErrorMessageException.class)
    public void updateGroup_notOwner_throws() {
        DmPermGroupDO group = activeGroup();
        group.setCreatorUid("other-puid");
        when(groupMapper.selectById(GROUP_ID)).thenReturn(group);
        UpdatePermGroupFO fo = new UpdatePermGroupFO();
        fo.setGroupId(GROUP_ID);
        fo.setGroupName("new");
        service.updateGroup(PUID, "uid", fo);
    }

    // ==================== Expansion: addMembers ====================

    @Test
    public void addMembers_expandsAuthResWithPermGroupMarker() {
        when(groupMapper.selectById(GROUP_ID)).thenReturn(activeGroup());
        when(userMapper.queryByUid(MEMBER_UID)).thenReturn(user(MEMBER_UID));
        when(resourceMapper.listByGroupId(GROUP_ID)).thenReturn(Collections.singletonList(dsResource()));
        when(grantRecordMapper.findByGroupResourceAndMember(RESOURCE_ID, MEMBER_UID)).thenReturn(null);
        when(dsMapper.selectById(DS_ID)).thenReturn(ds());
        // cascade: return the label itself
        when(authServiceForManage.getCascadeAuthByLabel("DM_QUERY")).thenReturn(Collections.emptyList());

        PermGroupMemberFO fo = new PermGroupMemberFO();
        fo.setGroupId(GROUP_ID);
        fo.setUids(Collections.singletonList(MEMBER_UID));
        service.addMembers(PUID, "uid", fo);

        // verify auth_res inserted with PERM_GROUP marker
        ArgumentCaptor<DmAuthResDO> authCaptor = ArgumentCaptor.forClass(DmAuthResDO.class);
        verify(resMapper).insert(authCaptor.capture());
        DmAuthResDO inserted = authCaptor.getValue();
        assertEquals(MEMBER_UID, inserted.getOwnerUid());
        assertTrue(inserted.getResDesc().startsWith("PERM_GROUP:"));
        assertEquals(Long.valueOf(DS_ID), inserted.getResId());
        assertEquals(AuthKind.DataSource, inserted.getKindType());
        assertEquals("/catalog/schema/", inserted.getResPath());
        assertEquals("catalog", inserted.getLevelOne());
        assertEquals("schema", inserted.getLevelTwo());

        // verify grant record inserted
        ArgumentCaptor<DmPermGroupGrantRecordDO> recordCaptor = ArgumentCaptor.forClass(DmPermGroupGrantRecordDO.class);
        verify(grantRecordMapper).insert(recordCaptor.capture());
        assertEquals(MEMBER_UID, recordCaptor.getValue().getMemberUid());
        assertEquals(Long.valueOf(RESOURCE_ID), recordCaptor.getValue().getGroupResourceId());
    }

    @Test
    public void addMembers_skipsAlreadyExpanded() {
        when(groupMapper.selectById(GROUP_ID)).thenReturn(activeGroup());
        when(userMapper.queryByUid(MEMBER_UID)).thenReturn(user(MEMBER_UID));
        when(resourceMapper.listByGroupId(GROUP_ID)).thenReturn(Collections.singletonList(dsResource()));

        // grant record already exists
        DmPermGroupGrantRecordDO existing = new DmPermGroupGrantRecordDO();
        existing.setAuthResId(AUTH_RES_ID);
        when(grantRecordMapper.findByGroupResourceAndMember(RESOURCE_ID, MEMBER_UID)).thenReturn(existing);

        PermGroupMemberFO fo = new PermGroupMemberFO();
        fo.setGroupId(GROUP_ID);
        fo.setUids(Collections.singletonList(MEMBER_UID));
        service.addMembers(PUID, "uid", fo);

        // resMapper.insert should NOT be called (already expanded)
        verify(resMapper, never()).insert(any(DmAuthResDO.class));
        verify(grantRecordMapper, never()).insert(any(DmPermGroupGrantRecordDO.class));
    }

    @Test(expected = ErrorMessageException.class)
    public void addMembers_unknownUser_throws() {
        when(groupMapper.selectById(GROUP_ID)).thenReturn(activeGroup());
        when(userMapper.queryByUid("BAD_UID")).thenReturn(null);

        PermGroupMemberFO fo = new PermGroupMemberFO();
        fo.setGroupId(GROUP_ID);
        fo.setUids(Collections.singletonList("BAD_UID"));
        service.addMembers(PUID, "uid", fo);
    }

    // ==================== Revocation: removeMembers ====================

    @Test
    public void removeMembers_revokesAuthResAndGrantRecord() {
        when(groupMapper.selectById(GROUP_ID)).thenReturn(activeGroup());

        DmPermGroupGrantRecordDO record = new DmPermGroupGrantRecordDO();
        record.setId(1L);
        record.setAuthResId(AUTH_RES_ID);
        record.setMemberUid(MEMBER_UID);
        when(grantRecordMapper.listByGroupId(GROUP_ID)).thenReturn(Collections.singletonList(record));

        PermGroupMemberFO fo = new PermGroupMemberFO();
        fo.setGroupId(GROUP_ID);
        fo.setUids(Collections.singletonList(MEMBER_UID));
        service.removeMembers(PUID, "uid", fo);

        // verify auth_res deleted by id (only our own ledger row)
        verify(resMapper).deleteById(AUTH_RES_ID);
        verify(grantRecordMapper).deleteById(1L);
    }

    @Test
    public void removeMembers_idempotentWhenAuthResAlreadyGone() {
        when(groupMapper.selectById(GROUP_ID)).thenReturn(activeGroup());

        DmPermGroupGrantRecordDO record = new DmPermGroupGrantRecordDO();
        record.setId(1L);
        record.setAuthResId(AUTH_RES_ID);
        record.setMemberUid(MEMBER_UID);
        when(grantRecordMapper.listByGroupId(GROUP_ID)).thenReturn(Collections.singletonList(record));
        // deleteById on non-existent row is a no-op at DB level, no exception

        PermGroupMemberFO fo = new PermGroupMemberFO();
        fo.setGroupId(GROUP_ID);
        fo.setUids(Collections.singletonList(MEMBER_UID));
        service.removeMembers(PUID, "uid", fo);

        // still calls deleteById (no-op) and deletes the grant record
        verify(resMapper).deleteById(AUTH_RES_ID);
        verify(grantRecordMapper).deleteById(1L);
    }

    // ==================== Resource grant/revoke ====================

    @Test
    public void grantResource_insertsResourceAndExpands() {
        when(groupMapper.selectById(GROUP_ID)).thenReturn(activeGroup());
        when(dsMapper.listByUser(PUID)).thenReturn(Collections.singletonList(ds()));
        when(memberMapper.listByGroupId(GROUP_ID)).thenReturn(Collections.emptyList());
        when(authServiceForManage.getCascadeAuthByLabel("DM_QUERY")).thenReturn(Collections.emptyList());

        PermGroupResourceFO fo = new PermGroupResourceFO();
        fo.setGroupId(GROUP_ID);
        fo.setAuthKind("DataSource");
        fo.setResId(DS_ID);
        fo.setResPaths(Arrays.asList("catalog", "schema"));
        fo.setAuthLabels(Collections.singletonList("DM_QUERY"));

        service.grantResource(PUID, "uid", fo);

        ArgumentCaptor<DmPermGroupResourceDO> resCaptor = ArgumentCaptor.forClass(DmPermGroupResourceDO.class);
        verify(resourceMapper).insert(resCaptor.capture());
        assertEquals("/catalog/schema/", resCaptor.getValue().getResPath());
    }

    @Test(expected = ErrorMessageException.class)
    public void grantResource_dsNotOwned_throws() {
        when(groupMapper.selectById(GROUP_ID)).thenReturn(activeGroup());
        when(dsMapper.listByUser(PUID)).thenReturn(Collections.emptyList());

        PermGroupResourceFO fo = new PermGroupResourceFO();
        fo.setGroupId(GROUP_ID);
        fo.setAuthKind("DataSource");
        fo.setResId(DS_ID);
        fo.setResPaths(Arrays.asList("catalog"));
        fo.setAuthLabels(Collections.singletonList("DM_QUERY"));
        service.grantResource(PUID, "uid", fo);
    }

    @Test
    public void revokeResources_deletesGrantRecordsAndAuthRes() {
        when(groupMapper.selectById(GROUP_ID)).thenReturn(activeGroup());

        DmPermGroupGrantRecordDO record = new DmPermGroupGrantRecordDO();
        record.setId(1L);
        record.setAuthResId(AUTH_RES_ID);
        when(grantRecordMapper.listByGroupResourceId(RESOURCE_ID)).thenReturn(Collections.singletonList(record));

        PermGroupResourceRevokeFO fo = new PermGroupResourceRevokeFO();
        fo.setGroupId(GROUP_ID);
        fo.setGroupResourceIds(Collections.singletonList(RESOURCE_ID));
        service.revokeResources(PUID, "uid", fo);

        verify(resMapper).deleteById(AUTH_RES_ID);
        verify(grantRecordMapper).deleteById(1L);
        verify(resourceMapper).deleteById(RESOURCE_ID);
    }

    // ==================== Status change ====================

    @Test
    public void disableGroup_revokesAllExpansions() {
        DmPermGroupDO group = activeGroup();
        when(groupMapper.selectById(GROUP_ID)).thenReturn(group);

        DmPermGroupGrantRecordDO record = new DmPermGroupGrantRecordDO();
        record.setId(1L);
        record.setAuthResId(AUTH_RES_ID);
        when(grantRecordMapper.listByGroupId(GROUP_ID)).thenReturn(Collections.singletonList(record));

        service.updateGroupStatus(PUID, "uid", GROUP_ID, "INACTIVE");

        verify(resMapper).deleteById(AUTH_RES_ID);
        verify(grantRecordMapper).deleteById(1L);
        assertEquals(PermGroupStatus.INACTIVE.name(), group.getStatus());
    }

    @Test
    public void enableGroup_reExpandsAllMembers() {
        DmPermGroupDO group = activeGroup();
        group.setStatus(PermGroupStatus.INACTIVE.name());
        when(groupMapper.selectById(GROUP_ID)).thenReturn(group);

        // no orphan records to clean
        when(grantRecordMapper.listByGroupId(GROUP_ID)).thenReturn(Collections.emptyList());
        when(memberMapper.listByGroupId(GROUP_ID)).thenReturn(Collections.singletonList(member(MEMBER_UID)));
        when(resourceMapper.listByGroupId(GROUP_ID)).thenReturn(Collections.singletonList(dsResource()));
        when(grantRecordMapper.findByGroupResourceAndMember(RESOURCE_ID, MEMBER_UID)).thenReturn(null);
        when(dsMapper.selectById(DS_ID)).thenReturn(ds());
        when(authServiceForManage.getCascadeAuthByLabel("DM_QUERY")).thenReturn(Collections.emptyList());

        service.updateGroupStatus(PUID, "uid", GROUP_ID, "ACTIVE");

        // verify re-expansion happened
        verify(resMapper).insert(any(DmAuthResDO.class));
        verify(grantRecordMapper).insert(any(DmPermGroupGrantRecordDO.class));
        assertEquals(PermGroupStatus.ACTIVE.name(), group.getStatus());
    }

    // ==================== Orphan cleanup ====================

    @Test
    public void enableGroup_cleansOrphanGrantRecords() {
        DmPermGroupDO group = activeGroup();
        group.setStatus(PermGroupStatus.INACTIVE.name());
        when(groupMapper.selectById(GROUP_ID)).thenReturn(group);

        // orphan: grant record exists but auth_res row was deleted by sweeper
        DmPermGroupGrantRecordDO orphan = new DmPermGroupGrantRecordDO();
        orphan.setId(1L);
        orphan.setAuthResId(AUTH_RES_ID);
        orphan.setMemberUid(MEMBER_UID);
        when(grantRecordMapper.listByGroupId(GROUP_ID)).thenReturn(Collections.singletonList(orphan));
        // selectBatchIds returns empty (auth_res row is gone)
        when(resMapper.selectBatchIds(Collections.singletonList(AUTH_RES_ID))).thenReturn(Collections.emptyList());

        // no members/resources to re-expand
        when(memberMapper.listByGroupId(GROUP_ID)).thenReturn(Collections.emptyList());
        when(resourceMapper.listByGroupId(GROUP_ID)).thenReturn(Collections.emptyList());

        service.updateGroupStatus(PUID, "uid", GROUP_ID, "ACTIVE");

        // orphan grant record should be deleted
        verify(grantRecordMapper).deleteById(1L);
    }

    // ==================== B5: sweeper expiry — mixed orphan/non-orphan ====================

    @Test
    public void cleanOrphanGrantRecords_expiredExpandedRowDeleted_nonExpiredRowUntouched() {
        // Simulates the deleteByEndTimeExceed sweeper having deleted the expired
        // expanded auth_res row. The non-expired expanded auth_res row still exists.
        // cleanOrphanGrantRecords (called during re-activation) should delete only
        // the orphan grant record, not the non-orphan one.
        DmPermGroupDO group = activeGroup();
        group.setStatus(PermGroupStatus.INACTIVE.name());
        when(groupMapper.selectById(GROUP_ID)).thenReturn(group);

        // Grant record for expired expanded row (auth_res deleted by sweeper)
        DmPermGroupGrantRecordDO expiredRecord = new DmPermGroupGrantRecordDO();
        expiredRecord.setId(1L);
        expiredRecord.setAuthResId(500L);
        expiredRecord.setMemberUid(MEMBER_UID);

        // Grant record for non-expired expanded row (auth_res still exists)
        DmPermGroupGrantRecordDO activeRecord = new DmPermGroupGrantRecordDO();
        activeRecord.setId(2L);
        activeRecord.setAuthResId(501L);
        activeRecord.setMemberUid(MEMBER_UID);

        when(grantRecordMapper.listByGroupId(GROUP_ID)).thenReturn(Arrays.asList(expiredRecord, activeRecord));

        // selectBatchIds: only the non-expired auth_res row exists
        DmAuthResDO activeAuthRes = new DmAuthResDO();
        activeAuthRes.setId(501L);
        when(resMapper.selectBatchIds(Arrays.asList(500L, 501L))).thenReturn(Collections.singletonList(activeAuthRes));

        // no members/resources to re-expand (focus on orphan cleanup)
        when(memberMapper.listByGroupId(GROUP_ID)).thenReturn(Collections.emptyList());
        when(resourceMapper.listByGroupId(GROUP_ID)).thenReturn(Collections.emptyList());

        service.updateGroupStatus(PUID, "uid", GROUP_ID, "ACTIVE");

        // Orphan grant record (expired expanded row) should be deleted
        verify(grantRecordMapper).deleteById(1L);
        // Non-orphan grant record (non-expired expanded row) should NOT be deleted
        verify(grantRecordMapper, never()).deleteById(2L);
    }

    @Test
    public void cleanOrphanGrantRecords_directGrantRows_notInLedger_notAffected() {
        // Direct-grant rows (without PERM_GROUP marker) are created via the user
        // management path, not the PermGroupService expansion path. They have no
        // corresponding grant record in dm_perm_group_grant_record, so
        // cleanOrphanGrantRecords cannot touch them — the ledger only contains
        // expanded rows. This test pins that structural contract.
        DmPermGroupDO group = activeGroup();
        group.setStatus(PermGroupStatus.INACTIVE.name());
        when(groupMapper.selectById(GROUP_ID)).thenReturn(group);

        // Grant record only for the expanded row — no grant record for direct-grant
        DmPermGroupGrantRecordDO expandedRecord = new DmPermGroupGrantRecordDO();
        expandedRecord.setId(1L);
        expandedRecord.setAuthResId(500L);
        expandedRecord.setMemberUid(MEMBER_UID);
        when(grantRecordMapper.listByGroupId(GROUP_ID)).thenReturn(Collections.singletonList(expandedRecord));

        // The expanded auth_res row still exists (not expired)
        DmAuthResDO expandedAuthRes = new DmAuthResDO();
        expandedAuthRes.setId(500L);
        when(resMapper.selectBatchIds(Collections.singletonList(500L))).thenReturn(Collections.singletonList(expandedAuthRes));

        when(memberMapper.listByGroupId(GROUP_ID)).thenReturn(Collections.emptyList());
        when(resourceMapper.listByGroupId(GROUP_ID)).thenReturn(Collections.emptyList());

        service.updateGroupStatus(PUID, "uid", GROUP_ID, "ACTIVE");

        // Expanded grant record NOT deleted (auth_res still exists)
        verify(grantRecordMapper, never()).deleteById(1L);
        // Direct-grant rows were never in the ledger — no deleteById calls at all
        verify(grantRecordMapper, never()).deleteById(anyLong());
    }

    // ==================== Label cascade ====================

    @Test
    public void expansion_cascadesLabels() {
        when(groupMapper.selectById(GROUP_ID)).thenReturn(activeGroup());
        when(userMapper.queryByUid(MEMBER_UID)).thenReturn(user(MEMBER_UID));

        DmPermGroupResourceDO res = dsResource();
        res.setAuthLabels(new ArrayList<>(Arrays.asList("DM_DML")));
        when(resourceMapper.listByGroupId(GROUP_ID)).thenReturn(Collections.singletonList(res));
        when(grantRecordMapper.findByGroupResourceAndMember(RESOURCE_ID, MEMBER_UID)).thenReturn(null);
        when(dsMapper.selectById(DS_ID)).thenReturn(ds());

        // cascade: DM_DML -> [DM_DML, DM_QUERY]
        AuthInfo dmlInfo = mock(AuthInfo.class);
        when(dmlInfo.getKey()).thenReturn("DM_DML");
        AuthInfo queryInfo = mock(AuthInfo.class);
        when(queryInfo.getKey()).thenReturn("DM_QUERY");
        when(authServiceForManage.getCascadeAuthByLabel("DM_DML")).thenReturn(Arrays.asList(dmlInfo, queryInfo));

        PermGroupMemberFO fo = new PermGroupMemberFO();
        fo.setGroupId(GROUP_ID);
        fo.setUids(Collections.singletonList(MEMBER_UID));
        service.addMembers(PUID, "uid", fo);

        ArgumentCaptor<DmAuthResDO> authCaptor = ArgumentCaptor.forClass(DmAuthResDO.class);
        verify(resMapper).insert(authCaptor.capture());
        List<String> labels = authCaptor.getValue().getAuthLabels();
        assertTrue(labels.contains("DM_DML"));
        assertTrue(labels.contains("DM_QUERY"));
    }

    // ==================== Global resource ====================

    @Test
    public void expansion_globalResource_setsAllInstanceId() {
        when(groupMapper.selectById(GROUP_ID)).thenReturn(activeGroup());
        when(userMapper.queryByUid(MEMBER_UID)).thenReturn(user(MEMBER_UID));

        DmPermGroupResourceDO res = new DmPermGroupResourceDO();
        res.setId(RESOURCE_ID);
        res.setGroupId(GROUP_ID);
        res.setAuthKind(AuthKind.DataSource);
        res.setResId(0L); // global
        res.setResPath("/");
        res.setAuthLabels(new ArrayList<>(Arrays.asList("DM_QUERY")));
        when(resourceMapper.listByGroupId(GROUP_ID)).thenReturn(Collections.singletonList(res));
        when(grantRecordMapper.findByGroupResourceAndMember(RESOURCE_ID, MEMBER_UID)).thenReturn(null);
        when(authServiceForManage.getCascadeAuthByLabel("DM_QUERY")).thenReturn(Collections.emptyList());

        PermGroupMemberFO fo = new PermGroupMemberFO();
        fo.setGroupId(GROUP_ID);
        fo.setUids(Collections.singletonList(MEMBER_UID));
        service.addMembers(PUID, "uid", fo);

        ArgumentCaptor<DmAuthResDO> authCaptor = ArgumentCaptor.forClass(DmAuthResDO.class);
        verify(resMapper).insert(authCaptor.capture());
        DmAuthResDO inserted = authCaptor.getValue();
        assertEquals("ALL", inserted.getResInstId());
        assertEquals("/", inserted.getResPath());
        assertEquals("/", inserted.getLevelOne());
    }

    @Test
    public void expansion_globalResource_emptyLabels_fillsAllDataLabels() {
        when(groupMapper.selectById(GROUP_ID)).thenReturn(activeGroup());
        when(userMapper.queryByUid(MEMBER_UID)).thenReturn(user(MEMBER_UID));

        DmPermGroupResourceDO res = new DmPermGroupResourceDO();
        res.setId(RESOURCE_ID);
        res.setGroupId(GROUP_ID);
        res.setAuthKind(AuthKind.DataSource);
        res.setResId(0L);
        res.setResPath("/");
        res.setAuthLabels(new ArrayList<>());
        when(resourceMapper.listByGroupId(GROUP_ID)).thenReturn(Collections.singletonList(res));
        when(grantRecordMapper.findByGroupResourceAndMember(RESOURCE_ID, MEMBER_UID)).thenReturn(null);

        // mock allDataAuthLabels
        AuthInfo labelInfo = mock(AuthInfo.class);
        when(labelInfo.getAuthType()).thenReturn(AuthInfoType.Auth);
        when(labelInfo.getKey()).thenReturn("DM_QUERY");
        when(authServiceForManage.getDataAuthLabel()).thenReturn(Collections.singletonList(labelInfo));
        when(authServiceForManage.getCascadeAuthByLabel("DM_QUERY")).thenReturn(Collections.emptyList());

        PermGroupMemberFO fo = new PermGroupMemberFO();
        fo.setGroupId(GROUP_ID);
        fo.setUids(Collections.singletonList(MEMBER_UID));
        service.addMembers(PUID, "uid", fo);

        ArgumentCaptor<DmAuthResDO> authCaptor = ArgumentCaptor.forClass(DmAuthResDO.class);
        verify(resMapper).insert(authCaptor.capture());
        assertTrue(authCaptor.getValue().getAuthLabels().contains("DM_QUERY"));
    }

    // ==================== Delete group ====================

    @Test
    public void deleteGroup_revokesAllAndDeletesEverything() {
        when(groupMapper.selectById(GROUP_ID)).thenReturn(activeGroup());

        DmPermGroupGrantRecordDO record = new DmPermGroupGrantRecordDO();
        record.setId(1L);
        record.setAuthResId(AUTH_RES_ID);
        when(grantRecordMapper.listByGroupId(GROUP_ID)).thenReturn(Collections.singletonList(record));

        service.deleteGroup(PUID, "uid", GROUP_ID);

        verify(resMapper).deleteById(AUTH_RES_ID);
        verify(grantRecordMapper).deleteById(1L);
        verify(memberMapper).deleteByGroupId(GROUP_ID);
        verify(resourceMapper).deleteByGroupId(GROUP_ID);
        verify(groupMapper).deleteById(GROUP_ID);
    }

    private DmPermGroupMemberDO member(String uid) {
        DmPermGroupMemberDO m = new DmPermGroupMemberDO();
        m.setGroupId(GROUP_ID);
        m.setUid(uid);
        return m;
    }
}
