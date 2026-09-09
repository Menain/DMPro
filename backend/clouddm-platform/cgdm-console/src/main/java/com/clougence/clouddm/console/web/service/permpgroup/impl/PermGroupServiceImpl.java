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

import java.util.*;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.console.web.component.auth.DmAuthServiceForManage;
import com.clougence.clouddm.console.web.model.fo.permpgroup.CreatePermGroupFO;
import com.clougence.clouddm.console.web.model.fo.permpgroup.PermGroupMemberFO;
import com.clougence.clouddm.console.web.model.fo.permpgroup.PermGroupResourceFO;
import com.clougence.clouddm.console.web.model.fo.permpgroup.PermGroupResourceRevokeFO;
import com.clougence.clouddm.console.web.model.fo.permpgroup.UpdatePermGroupFO;
import com.clougence.clouddm.console.web.model.vo.permpgroup.PermGroupMemberVO;
import com.clougence.clouddm.console.web.model.vo.permpgroup.PermGroupResourceVO;
import com.clougence.clouddm.console.web.model.vo.permpgroup.PermGroupVO;
import com.clougence.clouddm.console.web.service.permpgroup.PermGroupService;
import com.clougence.clouddm.console.web.util.DmDsUtils;
import com.clougence.clouddm.platform.dal.access.AuthDal;
import com.clougence.clouddm.platform.dal.access.DataSourceDal;
import com.clougence.clouddm.platform.dal.access.PermGroupDal;
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
import com.clougence.utils.CollectionUtils;
import com.clougence.utils.StringUtils;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class PermGroupServiceImpl implements PermGroupService {

    private static final String PERM_GROUP_DESC_PREFIX = "PERM_GROUP:";

    @Resource
    private PermGroupDal             permGroupDal;
    @Resource
    private AuthDal                  authDal;
    @Resource
    private DataSourceDal            dsDal;
    @Resource
    private DmAuthServiceForManage   authServiceForManage;

    // ==================== Group CRUD ====================

    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRED)
    @Override
    public PermGroupVO createGroup(String puid, String uid, CreatePermGroupFO fo) {
        DmPermGroupDO existing = permGroupDal.permGroupMapper().queryByGroupCode(fo.getGroupCode());
        if (existing != null) {
            throw new ErrorMessageException("Permission group code already exists: " + fo.getGroupCode());
        }

        DmPermGroupDO groupDO = new DmPermGroupDO();
        groupDO.setGroupCode(fo.getGroupCode());
        groupDO.setGroupName(fo.getGroupName());
        groupDO.setDescription(fo.getDescription());
        groupDO.setStatus(PermGroupStatus.ACTIVE.name());
        groupDO.setCreatorUid(puid);
        permGroupDal.permGroupMapper().insert(groupDO);
        return toGroupVO(groupDO);
    }

    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRED)
    @Override
    public void updateGroup(String puid, String uid, UpdatePermGroupFO fo) {
        DmPermGroupDO group = requireGroupOwnedBy(puid, fo.getGroupId());

        if (StringUtils.isNotBlank(fo.getGroupName())) {
            group.setGroupName(fo.getGroupName());
        }
        if (fo.getDescription() != null) {
            group.setDescription(fo.getDescription());
        }
        permGroupDal.permGroupMapper().updateById(group);
    }

    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRED)
    @Override
    public void deleteGroup(String puid, String uid, long groupId) {
        DmPermGroupDO group = requireGroupOwnedBy(puid, groupId);

        // revoke all expanded auth_res rows + grant records
        revokeAllForGroup(groupId);
        // delete members, resources, and the group itself
        permGroupDal.permGroupMemberMapper().deleteByGroupId(groupId);
        permGroupDal.permGroupResourceMapper().deleteByGroupId(groupId);
        permGroupDal.permGroupMapper().deleteById(groupId);
    }

    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRED)
    @Override
    public void updateGroupStatus(String puid, String uid, long groupId, String status) {
        DmPermGroupDO group = requireGroupOwnedBy(puid, groupId);
        PermGroupStatus targetStatus = PermGroupStatus.valueOf(status);

        if (targetStatus == PermGroupStatus.INACTIVE) {
            // stop: revoke all expansions (keep group resource/member rows)
            revokeAllForGroup(groupId);
        } else {
            // re-activate: re-expand all member x resource combinations
            cleanOrphanGrantRecords(groupId);
            List<DmPermGroupMemberDO> members = permGroupDal.permGroupMemberMapper().listByGroupId(groupId);
            List<DmPermGroupResourceDO> resources = permGroupDal.permGroupResourceMapper().listByGroupId(groupId);
            for (DmPermGroupResourceDO resource : resources) {
                for (DmPermGroupMemberDO member : members) {
                    expandSingleRow(resource, member.getUid());
                }
            }
        }
        group.setStatus(targetStatus.name());
        permGroupDal.permGroupMapper().updateById(group);
    }

    @Override
    public List<PermGroupVO> listGroups(String puid) {
        List<DmPermGroupDO> groups = permGroupDal.permGroupMapper()
            .selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DmPermGroupDO>()
                .eq(DmPermGroupDO::getCreatorUid, puid)
                .orderByDesc(DmPermGroupDO::getGmtCreate));
        List<PermGroupVO> vos = new ArrayList<>();
        for (DmPermGroupDO group : groups) {
            PermGroupVO vo = toGroupVO(group);
            vo.setMemberCount(permGroupDal.permGroupMemberMapper()
                .selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DmPermGroupMemberDO>()
                    .eq(DmPermGroupMemberDO::getGroupId, group.getId()))
                .intValue());
            vo.setResourceCount(permGroupDal.permGroupResourceMapper()
                .selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DmPermGroupResourceDO>()
                    .eq(DmPermGroupResourceDO::getGroupId, group.getId()))
                .intValue());
            vos.add(vo);
        }
        return vos;
    }

    @Override
    public PermGroupVO getGroupDetail(String puid, long groupId) {
        DmPermGroupDO group = requireGroupOwnedBy(puid, groupId);
        PermGroupVO vo = toGroupVO(group);
        List<DmPermGroupMemberDO> members = permGroupDal.permGroupMemberMapper().listByGroupId(groupId);
        List<DmPermGroupResourceDO> resources = permGroupDal.permGroupResourceMapper().listByGroupId(groupId);
        vo.setMemberCount(members.size());
        vo.setResourceCount(resources.size());
        return vo;
    }

    // ==================== Member management ====================

    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRED)
    @Override
    public void addMembers(String puid, String uid, PermGroupMemberFO fo) {
        DmPermGroupDO group = requireGroupOwnedBy(puid, fo.getGroupId());

        // validate all uids exist
        for (String memberUid : fo.getUids()) {
            DmAuthUserDO user = authDal.userMapper().queryByUid(memberUid);
            if (user == null) {
                throw new ErrorMessageException("User not found: " + memberUid);
            }
        }

        // insert member records and expand
        List<DmPermGroupResourceDO> resources = permGroupDal.permGroupResourceMapper().listByGroupId(fo.getGroupId());
        for (String memberUid : fo.getUids()) {
            DmPermGroupMemberDO member = new DmPermGroupMemberDO();
            member.setGroupId(fo.getGroupId());
            member.setUid(memberUid);
            try {
                permGroupDal.permGroupMemberMapper().insert(member);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // already a member, skip
                continue;
            }

            // expand this member against all group resources (only if group is active)
            if (PermGroupStatus.ACTIVE.name().equals(group.getStatus())) {
                for (DmPermGroupResourceDO resource : resources) {
                    expandSingleRow(resource, memberUid);
                }
            }
        }
    }

    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRED)
    @Override
    public void removeMembers(String puid, String uid, PermGroupMemberFO fo) {
        requireGroupOwnedBy(puid, fo.getGroupId());

        for (String memberUid : fo.getUids()) {
            revokeForMember(fo.getGroupId(), memberUid);
            // delete the member record
            permGroupDal.permGroupMemberMapper()
                .delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DmPermGroupMemberDO>()
                    .eq(DmPermGroupMemberDO::getGroupId, fo.getGroupId())
                    .eq(DmPermGroupMemberDO::getUid, memberUid));
        }
    }

    @Override
    public List<PermGroupMemberVO> listMembers(String puid, long groupId) {
        requireGroupOwnedBy(puid, groupId);
        List<DmPermGroupMemberDO> members = permGroupDal.permGroupMemberMapper().listByGroupId(groupId);
        List<PermGroupMemberVO> vos = new ArrayList<>();
        for (DmPermGroupMemberDO member : members) {
            PermGroupMemberVO vo = new PermGroupMemberVO();
            vo.setId(member.getId());
            vo.setGroupId(member.getGroupId());
            vo.setUid(member.getUid());
            vo.setGmtCreate(member.getGmtCreate());
            // resolve username
            DmAuthUserDO user = authDal.userMapper().queryByUid(member.getUid());
            if (user != null) {
                vo.setUsername(user.getUsername());
            }
            vos.add(vo);
        }
        return vos;
    }

    // ==================== Resource management ====================

    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRED)
    @Override
    public void grantResource(String puid, String uid, PermGroupResourceFO fo) {
        DmPermGroupDO group = requireGroupOwnedBy(puid, fo.getGroupId());

        AuthKind authKind = AuthKind.valueOf(fo.getAuthKind());

        // validate puid owns the ds
        if (fo.getResId() != DmAuthServiceForManage.GLOBAL_RESOURCE_RES_ID) {
            List<DmDsDO> dss = dsDal.dsMapper().listByUser(puid);
            Set<Long> dsIds = dss.stream().map(DmDsDO::getId).collect(Collectors.toSet());
            if (!dsIds.contains(fo.getResId())) {
                throw new ErrorMessageException("Resource not belong to the primary user: " + fo.getResId());
            }
        }

        // validate labels are known
        if (CollectionUtils.isNotEmpty(fo.getAuthLabels())) {
            validateLabels(fo.getAuthLabels());
        }

        // build group resource record
        DmPermGroupResourceDO resource = new DmPermGroupResourceDO();
        resource.setGroupId(fo.getGroupId());
        resource.setAuthKind(authKind);
        resource.setResId(fo.getResId());
        resource.setResPath(DmDsUtils.buildResourcePath(fo.getResPaths()));
        resource.setAuthLabels(fo.getAuthLabels() != null ? fo.getAuthLabels() : new ArrayList<>());
        if (StringUtils.isNotBlank(fo.getStartTime())) {
            resource.setStartTime(parseDate(fo.getStartTime()));
        }
        if (StringUtils.isNotBlank(fo.getEndTime())) {
            resource.setEndTime(parseDate(fo.getEndTime()));
        }
        permGroupDal.permGroupResourceMapper().insert(resource);

        // expand: for each member, create auth_res row + grant record (only if group is active)
        if (PermGroupStatus.ACTIVE.name().equals(group.getStatus())) {
            expandForResource(resource);
        }
    }

    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRED)
    @Override
    public void revokeResources(String puid, String uid, PermGroupResourceRevokeFO fo) {
        requireGroupOwnedBy(puid, fo.getGroupId());

        for (Long groupResourceId : fo.getGroupResourceIds()) {
            revokeForResource(groupResourceId);
            permGroupDal.permGroupResourceMapper().deleteById(groupResourceId);
        }
    }

    @Override
    public List<PermGroupResourceVO> listResources(String puid, long groupId) {
        requireGroupOwnedBy(puid, groupId);
        List<DmPermGroupResourceDO> resources = permGroupDal.permGroupResourceMapper().listByGroupId(groupId);
        return resources.stream().map(this::toResourceVO).collect(Collectors.toList());
    }

    // ==================== Expansion engine ====================

    private void expandForResource(DmPermGroupResourceDO resource) {
        List<DmPermGroupMemberDO> members = permGroupDal.permGroupMemberMapper().listByGroupId(resource.getGroupId());
        for (DmPermGroupMemberDO member : members) {
            expandSingleRow(resource, member.getUid());
        }
    }

    private void expandSingleRow(DmPermGroupResourceDO resource, String memberUid) {
        // idempotency: skip if grant record already exists
        DmPermGroupGrantRecordDO existing = permGroupDal.permGroupGrantRecordMapper()
            .findByGroupResourceAndMember(resource.getId(), memberUid);
        if (existing != null) {
            return;
        }

        DmAuthResDO authResDO = buildAuthResDO(memberUid, resource);
        // direct insert — bypass mergeGrantedAuth to prevent label merging
        authDal.resMapper().insert(authResDO);

        // write grant record ledger
        DmPermGroupGrantRecordDO record = new DmPermGroupGrantRecordDO();
        record.setGroupId(resource.getGroupId());
        record.setGroupResourceId(resource.getId());
        record.setMemberUid(memberUid);
        record.setAuthResId(authResDO.getId());
        permGroupDal.permGroupGrantRecordMapper().insert(record);
    }

    private DmAuthResDO buildAuthResDO(String memberUid, DmPermGroupResourceDO resource) {
        DmAuthResDO authDO = new DmAuthResDO();
        authDO.setOwnerUid(memberUid);
        authDO.setResId(resource.getResId());
        authDO.setKindType(resource.getAuthKind());
        authDO.setStartTime(resource.getStartTime());
        authDO.setEndTime(resource.getEndTime());

        // PERM_GROUP marker — sole source identification for touchpoint #4 protection
        authDO.setResDesc(PERM_GROUP_DESC_PREFIX + resource.getGroupId() + ":" + resource.getId());

        // global resource vs specific ds
        if (resource.getResId() == DmAuthServiceForManage.GLOBAL_RESOURCE_RES_ID
            && StringUtils.equals(resource.getResPath(), DmAuthServiceForManage.GLOBAL_RESOURCE_PATH)) {
            authDO.setResInstId("ALL");
            authDO.setResPath(DmAuthServiceForManage.GLOBAL_RESOURCE_PATH);
            authDO.setLevelOne(DmAuthServiceForManage.GLOBAL_RESOURCE_PATH);
            if (CollectionUtils.isEmpty(resource.getAuthLabels())) {
                authDO.setAuthLabels(allDataAuthLabels());
            } else {
                authDO.setAuthLabels(cascadeLabels(resource.getAuthLabels()));
            }
        } else {
            DmDsDO ds = dsDal.dsMapper().selectById(resource.getResId());
            if (ds != null) {
                authDO.setResInstId(ds.getInstanceId());
            }
            fillResPathLevels(authDO, resource.getResPath());
            authDO.setAuthLabels(cascadeLabels(resource.getAuthLabels()));
        }

        return authDO;
    }

    // ==================== Revocation engine ====================

    private void revokeAllForGroup(long groupId) {
        List<DmPermGroupGrantRecordDO> records = permGroupDal.permGroupGrantRecordMapper().listByGroupId(groupId);
        for (DmPermGroupGrantRecordDO record : records) {
            // deleteById is idempotent — if the auth_res row was already cleaned by the expired-auth
            // sweeper, this is a no-op
            authDal.resMapper().deleteById(record.getAuthResId());
            permGroupDal.permGroupGrantRecordMapper().deleteById(record.getId());
        }
    }

    private void revokeForMember(long groupId, String memberUid) {
        // find all grant records for this member in this group
        List<DmPermGroupGrantRecordDO> allRecords = permGroupDal.permGroupGrantRecordMapper().listByGroupId(groupId);
        List<DmPermGroupGrantRecordDO> memberRecords = allRecords.stream()
            .filter(r -> StringUtils.equals(r.getMemberUid(), memberUid))
            .collect(Collectors.toList());
        for (DmPermGroupGrantRecordDO record : memberRecords) {
            // only delete our own ledger rows — never touch unmarked rows
            authDal.resMapper().deleteById(record.getAuthResId());
            permGroupDal.permGroupGrantRecordMapper().deleteById(record.getId());
        }
    }

    private void revokeForResource(long groupResourceId) {
        List<DmPermGroupGrantRecordDO> records = permGroupDal.permGroupGrantRecordMapper()
            .listByGroupResourceId(groupResourceId);
        for (DmPermGroupGrantRecordDO record : records) {
            authDal.resMapper().deleteById(record.getAuthResId());
            permGroupDal.permGroupGrantRecordMapper().deleteById(record.getId());
        }
    }

    private void cleanOrphanGrantRecords(long groupId) {
        List<DmPermGroupGrantRecordDO> records = permGroupDal.permGroupGrantRecordMapper().listByGroupId(groupId);
        List<Long> authResIds = records.stream().map(DmPermGroupGrantRecordDO::getAuthResId).collect(Collectors.toList());
        if (authResIds.isEmpty()) {
            return;
        }
        // check which auth_res rows still exist
        List<DmAuthResDO> existingRows = authDal.resMapper().selectBatchIds(authResIds);
        Set<Long> existingIds = existingRows.stream().map(DmAuthResDO::getId).collect(Collectors.toSet());
        // delete orphan grant records (auth_res row was cleaned by expired-auth sweeper)
        for (DmPermGroupGrantRecordDO record : records) {
            if (!existingIds.contains(record.getAuthResId())) {
                permGroupDal.permGroupGrantRecordMapper().deleteById(record.getId());
            }
        }
    }

    // ==================== Helpers ====================

    private void fillResPathLevels(DmAuthResDO authDO, String resPath) {
        authDO.setResPath(resPath);
        List<String> levels = Arrays.stream(resPath.split("/"))
            .filter(StringUtils::isNotBlank)
            .collect(Collectors.toList());
        authDO.setLevelOne(DmAuthServiceForManage.GLOBAL_RESOURCE_PATH);
        switch (levels.size()) {
            case 4:
                authDO.setLevelFour(levels.get(3));
            case 3:
                authDO.setLevelThree(levels.get(2));
            case 2:
                authDO.setLevelTwo(levels.get(1));
            case 1:
                authDO.setLevelOne(levels.get(0));
                break;
        }
    }

    private List<String> cascadeLabels(List<String> authLabels) {
        if (CollectionUtils.isEmpty(authLabels)) {
            return new ArrayList<>();
        }
        Set<String> result = new TreeSet<>();
        for (String label : authLabels) {
            List<AuthInfo> cascaded = authServiceForManage.getCascadeAuthByLabel(label);
            result.addAll(cascaded.stream().map(AuthInfo::getKey).collect(Collectors.toList()));
        }
        return new ArrayList<>(result);
    }

    private List<String> allDataAuthLabels() {
        return authServiceForManage.getDataAuthLabel().stream()
            .filter(a -> a.getAuthType() == AuthInfoType.Auth)
            .map(AuthInfo::getKey)
            .collect(Collectors.toList());
    }

    private void validateLabels(List<String> authLabels) {
        Set<String> knownLabels = allDataAuthLabels().stream().collect(Collectors.toSet());
        for (String label : authLabels) {
            if (!knownLabels.contains(label)) {
                throw new ErrorMessageException("Unknown auth label: " + label);
            }
        }
    }

    private DmPermGroupDO requireGroupOwnedBy(String puid, long groupId) {
        DmPermGroupDO group = permGroupDal.permGroupMapper().selectById(groupId);
        if (group == null) {
            throw new ErrorMessageException("Permission group not found: " + groupId);
        }
        if (!StringUtils.equals(group.getCreatorUid(), puid)) {
            throw new ErrorMessageException("Permission group does not belong to the current user");
        }
        return group;
    }

    private PermGroupVO toGroupVO(DmPermGroupDO group) {
        PermGroupVO vo = new PermGroupVO();
        vo.setId(group.getId());
        vo.setGroupCode(group.getGroupCode());
        vo.setGroupName(group.getGroupName());
        vo.setDescription(group.getDescription());
        vo.setStatus(group.getStatus());
        vo.setCreatorUid(group.getCreatorUid());
        vo.setGmtCreate(group.getGmtCreate());
        return vo;
    }

    private PermGroupResourceVO toResourceVO(DmPermGroupResourceDO resource) {
        PermGroupResourceVO vo = new PermGroupResourceVO();
        vo.setId(resource.getId());
        vo.setGroupId(resource.getGroupId());
        vo.setAuthKind(resource.getAuthKind().name());
        vo.setResId(resource.getResId());
        vo.setResPath(resource.getResPath());
        vo.setAuthLabels(resource.getAuthLabels());
        vo.setStartTime(resource.getStartTime());
        vo.setEndTime(resource.getEndTime());
        vo.setGmtCreate(resource.getGmtCreate());
        return vo;
    }

    private Date parseDate(String date) {
        if (StringUtils.isBlank(date)) {
            return null;
        }
        try {
            return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(date);
        } catch (Exception e) {
            throw new ErrorMessageException("Invalid date format: " + date);
        }
    }
}
