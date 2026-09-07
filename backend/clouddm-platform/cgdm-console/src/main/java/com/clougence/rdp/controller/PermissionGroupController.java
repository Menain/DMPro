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
package com.clougence.rdp.controller;

import static com.clougence.clouddm.sdk.security.auth.def.SecRoleAuthLabel.RDP_PERM_GROUP_MANAGE;

import java.util.List;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.clougence.clouddm.api.common.rpc.ResWebData;
import com.clougence.clouddm.api.common.rpc.ResWebDataUtils;
import com.clougence.clouddm.console.web.constants.DmControllerUrlPrefix;
import com.clougence.clouddm.console.web.global.jwtsession.RequestAuth;
import com.clougence.clouddm.console.web.model.fo.permpgroup.CreatePermGroupFO;
import com.clougence.clouddm.console.web.model.fo.permpgroup.PermGroupIdFO;
import com.clougence.clouddm.console.web.model.fo.permpgroup.PermGroupMemberFO;
import com.clougence.clouddm.console.web.model.fo.permpgroup.PermGroupResourceFO;
import com.clougence.clouddm.console.web.model.fo.permpgroup.PermGroupResourceRevokeFO;
import com.clougence.clouddm.console.web.model.fo.permpgroup.PermGroupStatusFO;
import com.clougence.clouddm.console.web.model.fo.permpgroup.UpdatePermGroupFO;
import com.clougence.clouddm.console.web.model.vo.permpgroup.PermGroupMemberVO;
import com.clougence.clouddm.console.web.model.vo.permpgroup.PermGroupResourceVO;
import com.clougence.clouddm.console.web.model.vo.permpgroup.PermGroupVO;
import com.clougence.clouddm.console.web.service.auth.RdpUserService;
import com.clougence.clouddm.console.web.service.permpgroup.PermGroupService;
import com.clougence.clouddm.platform.dal.model.ResourceType;
import com.clougence.clouddm.platform.dal.model.monitor.AuditType;
import com.clougence.clouddm.platform.dal.model.monitor.SecurityLevel;
import com.clougence.rdp.service.RdpOpAuditService;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping(value = DmControllerUrlPrefix.CONSOLE_PREFIX + "/permGroup")
@Slf4j
public class PermissionGroupController {

    @Resource
    private PermGroupService  permGroupService;
    @Resource
    private RdpOpAuditService rdpOpAuditService;

    @RequestAuth(RDP_PERM_GROUP_MANAGE)
    @RequestMapping(value = "/list", method = RequestMethod.POST)
    public ResWebData<?> list(HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        List<PermGroupVO> groups = permGroupService.listGroups(puid);
        return ResWebDataUtils.buildSuccess(groups);
    }

    @RequestAuth(RDP_PERM_GROUP_MANAGE)
    @RequestMapping(value = "/create", method = RequestMethod.POST)
    public ResWebData<?> create(@Valid @RequestBody CreatePermGroupFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        PermGroupVO vo = permGroupService.createGroup(puid, uid, fo);
        rdpOpAuditService.logAndAddOperationAudit(puid, uid, request.getRequestURI(), request.getRemoteAddr(),
            vo.getId(), fo, SecurityLevel.HIGH, AuditType.CREATE_PERM_GROUP, ResourceType.PERM_GROUP);
        return ResWebDataUtils.buildSuccess(vo);
    }

    @RequestAuth(RDP_PERM_GROUP_MANAGE)
    @RequestMapping(value = "/update", method = RequestMethod.POST)
    public ResWebData<?> update(@Valid @RequestBody UpdatePermGroupFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        permGroupService.updateGroup(puid, uid, fo);
        rdpOpAuditService.logAndAddOperationAudit(puid, uid, request.getRequestURI(), request.getRemoteAddr(),
            fo.getGroupId(), fo, SecurityLevel.HIGH, AuditType.UPDATE_PERM_GROUP, ResourceType.PERM_GROUP);
        return ResWebDataUtils.buildSuccess();
    }

    @RequestAuth(RDP_PERM_GROUP_MANAGE)
    @RequestMapping(value = "/delete", method = RequestMethod.POST)
    public ResWebData<?> delete(@Valid @RequestBody PermGroupIdFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        permGroupService.deleteGroup(puid, uid, fo.getGroupId());
        rdpOpAuditService.logAndAddOperationAudit(puid, uid, request.getRequestURI(), request.getRemoteAddr(),
            fo.getGroupId(), fo, SecurityLevel.HIGH, AuditType.DELETE_PERM_GROUP, ResourceType.PERM_GROUP);
        return ResWebDataUtils.buildSuccess();
    }

    @RequestAuth(RDP_PERM_GROUP_MANAGE)
    @RequestMapping(value = "/detail", method = RequestMethod.POST)
    public ResWebData<?> detail(@Valid @RequestBody PermGroupIdFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        PermGroupVO vo = permGroupService.getGroupDetail(puid, fo.getGroupId());
        return ResWebDataUtils.buildSuccess(vo);
    }

    @RequestAuth(RDP_PERM_GROUP_MANAGE)
    @RequestMapping(value = "/updateStatus", method = RequestMethod.POST)
    public ResWebData<?> updateStatus(@Valid @RequestBody PermGroupStatusFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        permGroupService.updateGroupStatus(puid, uid, fo.getGroupId(), fo.getStatus());
        AuditType auditType = "ACTIVE".equals(fo.getStatus()) ? AuditType.ENABLE_PERM_GROUP : AuditType.DISABLE_PERM_GROUP;
        rdpOpAuditService.logAndAddOperationAudit(puid, uid, request.getRequestURI(), request.getRemoteAddr(),
            fo.getGroupId(), fo, SecurityLevel.HIGH, auditType, ResourceType.PERM_GROUP);
        return ResWebDataUtils.buildSuccess();
    }

    @RequestAuth(RDP_PERM_GROUP_MANAGE)
    @RequestMapping(value = "/memberAdd", method = RequestMethod.POST)
    public ResWebData<?> memberAdd(@Valid @RequestBody PermGroupMemberFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        permGroupService.addMembers(puid, uid, fo);
        rdpOpAuditService.logAndAddOperationAudit(puid, uid, request.getRequestURI(), request.getRemoteAddr(),
            fo.getGroupId(), fo, SecurityLevel.HIGH, AuditType.ADD_PERM_GROUP_MEMBER, ResourceType.PERM_GROUP);
        return ResWebDataUtils.buildSuccess();
    }

    @RequestAuth(RDP_PERM_GROUP_MANAGE)
    @RequestMapping(value = "/memberRemove", method = RequestMethod.POST)
    public ResWebData<?> memberRemove(@Valid @RequestBody PermGroupMemberFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        permGroupService.removeMembers(puid, uid, fo);
        rdpOpAuditService.logAndAddOperationAudit(puid, uid, request.getRequestURI(), request.getRemoteAddr(),
            fo.getGroupId(), fo, SecurityLevel.HIGH, AuditType.REMOVE_PERM_GROUP_MEMBER, ResourceType.PERM_GROUP);
        return ResWebDataUtils.buildSuccess();
    }

    @RequestAuth(RDP_PERM_GROUP_MANAGE)
    @RequestMapping(value = "/memberList", method = RequestMethod.POST)
    public ResWebData<?> memberList(@Valid @RequestBody PermGroupIdFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        List<PermGroupMemberVO> members = permGroupService.listMembers(puid, fo.getGroupId());
        return ResWebDataUtils.buildSuccess(members);
    }

    @RequestAuth(RDP_PERM_GROUP_MANAGE)
    @RequestMapping(value = "/resourceGrant", method = RequestMethod.POST)
    public ResWebData<?> resourceGrant(@Valid @RequestBody PermGroupResourceFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        permGroupService.grantResource(puid, uid, fo);
        rdpOpAuditService.logAndAddOperationAudit(puid, uid, request.getRequestURI(), request.getRemoteAddr(),
            fo.getGroupId(), fo, SecurityLevel.HIGH, AuditType.GRANT_PERM_GROUP_RESOURCE, ResourceType.PERM_GROUP);
        return ResWebDataUtils.buildSuccess();
    }

    @RequestAuth(RDP_PERM_GROUP_MANAGE)
    @RequestMapping(value = "/resourceRevoke", method = RequestMethod.POST)
    public ResWebData<?> resourceRevoke(@Valid @RequestBody PermGroupResourceRevokeFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        permGroupService.revokeResources(puid, uid, fo);
        rdpOpAuditService.logAndAddOperationAudit(puid, uid, request.getRequestURI(), request.getRemoteAddr(),
            fo.getGroupId(), fo, SecurityLevel.HIGH, AuditType.REVOKE_PERM_GROUP_RESOURCE, ResourceType.PERM_GROUP);
        return ResWebDataUtils.buildSuccess();
    }

    @RequestAuth(RDP_PERM_GROUP_MANAGE)
    @RequestMapping(value = "/resourceList", method = RequestMethod.POST)
    public ResWebData<?> resourceList(@Valid @RequestBody PermGroupIdFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        List<PermGroupResourceVO> resources = permGroupService.listResources(puid, fo.getGroupId());
        return ResWebDataUtils.buildSuccess(resources);
    }
}
