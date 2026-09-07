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

import static com.clougence.clouddm.console.web.global.jwtsession.RequestAuth.AuthStrategy.RefAnyOnes;
import static com.clougence.clouddm.sdk.security.auth.def.SecRoleAuthLabel.RDP_LOGICAL_DB_MANAGE;

import java.util.List;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.clougence.clouddm.api.common.rpc.ResWebData;
import com.clougence.clouddm.api.common.rpc.ResWebDataUtils;
import com.clougence.clouddm.console.web.constants.DmControllerUrlPrefix;
import com.clougence.clouddm.console.web.global.jwtsession.RequestAuth;
import com.clougence.clouddm.console.web.model.fo.logicaldb.BindingSetFO;
import com.clougence.clouddm.console.web.model.fo.logicaldb.LogicalDbCreateFO;
import com.clougence.clouddm.console.web.model.fo.logicaldb.LogicalDbIdFO;
import com.clougence.clouddm.console.web.model.fo.logicaldb.LogicalDbListFO;
import com.clougence.clouddm.console.web.model.fo.logicaldb.LogicalDbUpdateFO;
import com.clougence.clouddm.console.web.model.vo.logicaldb.LogicalDbBindingVO;
import com.clougence.clouddm.console.web.model.vo.logicaldb.LogicalDbVO;
import com.clougence.clouddm.console.web.model.vo.logicaldb.MyLogicalDbVO;
import com.clougence.clouddm.console.web.service.auth.RdpUserService;
import com.clougence.clouddm.console.web.service.logicaldb.LogicalDbService;
import com.clougence.clouddm.platform.dal.model.ResourceType;
import com.clougence.clouddm.platform.dal.model.monitor.AuditType;
import com.clougence.clouddm.platform.dal.model.monitor.SecurityLevel;
import com.clougence.rdp.service.RdpOpAuditService;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping(value = DmControllerUrlPrefix.CONSOLE_PREFIX + "/logicalDb")
@Slf4j
public class LogicalDbController {

    @Resource
    private LogicalDbService  logicalDbService;
    @Resource
    private RdpOpAuditService rdpOpAuditService;

    @RequestAuth(RDP_LOGICAL_DB_MANAGE)
    @RequestMapping(value = "/list", method = RequestMethod.POST)
    public ResWebData<?> list(@Valid @RequestBody LogicalDbListFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        List<LogicalDbVO> data = logicalDbService.list(puid, fo);
        return ResWebDataUtils.buildSuccess(data);
    }

    @RequestAuth(RDP_LOGICAL_DB_MANAGE)
    @RequestMapping(value = "/create", method = RequestMethod.POST)
    public ResWebData<?> create(@Valid @RequestBody LogicalDbCreateFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        LogicalDbVO vo = logicalDbService.create(puid, uid, fo);
        rdpOpAuditService.logAndAddOperationAudit(puid, uid, request.getRequestURI(), request.getRemoteAddr(),
            vo.getId(), fo, SecurityLevel.HIGH, AuditType.CREATE_LOGICAL_DB, ResourceType.LOGICAL_DB);
        return ResWebDataUtils.buildSuccess(vo);
    }

    @RequestAuth(RDP_LOGICAL_DB_MANAGE)
    @RequestMapping(value = "/update", method = RequestMethod.POST)
    public ResWebData<?> update(@Valid @RequestBody LogicalDbUpdateFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        logicalDbService.update(puid, uid, fo);
        rdpOpAuditService.logAndAddOperationAudit(puid, uid, request.getRequestURI(), request.getRemoteAddr(),
            fo.getId(), fo, SecurityLevel.HIGH, AuditType.UPDATE_LOGICAL_DB, ResourceType.LOGICAL_DB);
        return ResWebDataUtils.buildSuccess();
    }

    @RequestAuth(RDP_LOGICAL_DB_MANAGE)
    @RequestMapping(value = "/delete", method = RequestMethod.POST)
    public ResWebData<?> delete(@Valid @RequestBody LogicalDbIdFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        logicalDbService.delete(puid, uid, fo.getId());
        rdpOpAuditService.logAndAddOperationAudit(puid, uid, request.getRequestURI(), request.getRemoteAddr(),
            fo.getId(), fo, SecurityLevel.HIGH, AuditType.DELETE_LOGICAL_DB, ResourceType.LOGICAL_DB);
        return ResWebDataUtils.buildSuccess();
    }

    @RequestAuth(RDP_LOGICAL_DB_MANAGE)
    @RequestMapping(value = "/detail", method = RequestMethod.POST)
    public ResWebData<?> detail(@Valid @RequestBody LogicalDbIdFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        LogicalDbVO vo = logicalDbService.detail(puid, fo.getId());
        return ResWebDataUtils.buildSuccess(vo);
    }

    @RequestAuth(RDP_LOGICAL_DB_MANAGE)
    @RequestMapping(value = "/bindingSet", method = RequestMethod.POST)
    public ResWebData<?> bindingSet(@Valid @RequestBody BindingSetFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        logicalDbService.bindingSet(puid, uid, fo);
        rdpOpAuditService.logAndAddOperationAudit(puid, uid, request.getRequestURI(), request.getRemoteAddr(),
            fo.getLogicalDbId(), fo, SecurityLevel.HIGH, AuditType.SET_LOGICAL_DB_BINDING, ResourceType.LOGICAL_DB);
        return ResWebDataUtils.buildSuccess();
    }

    @RequestAuth(RDP_LOGICAL_DB_MANAGE)
    @RequestMapping(value = "/bindingList", method = RequestMethod.POST)
    public ResWebData<?> bindingList(@Valid @RequestBody LogicalDbIdFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        List<LogicalDbBindingVO> data = logicalDbService.bindingList(puid, fo.getId());
        return ResWebDataUtils.buildSuccess(data);
    }

    @RequestAuth(strategy = RefAnyOnes)
    @RequestMapping(value = "/myLogicalDbs", method = RequestMethod.POST)
    public ResWebData<?> myLogicalDbs(HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        List<MyLogicalDbVO> data = logicalDbService.myLogicalDbs(puid, uid);
        return ResWebDataUtils.buildSuccess(data);
    }
}
