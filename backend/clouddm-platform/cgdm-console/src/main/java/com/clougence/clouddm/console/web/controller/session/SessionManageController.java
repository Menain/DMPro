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
package com.clougence.clouddm.console.web.controller.session;

import static com.clougence.clouddm.sdk.security.auth.def.SecRoleAuthLabel.DM_SESSION_KILL;
import static com.clougence.clouddm.sdk.security.auth.def.SecRoleAuthLabel.DM_SESSION_READ;

import java.util.List;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.clougence.clouddm.api.common.rpc.ResWebData;
import com.clougence.clouddm.api.common.rpc.ResWebDataUtils;
import com.clougence.clouddm.console.web.constants.DmControllerUrlPrefix;
import com.clougence.clouddm.console.web.global.jwtsession.RequestAuth;
import com.clougence.clouddm.console.web.model.fo.session.SessionKillFO;
import com.clougence.clouddm.console.web.model.fo.session.SessionListFO;
import com.clougence.clouddm.console.web.model.vo.session.DbSessionListVO;
import com.clougence.clouddm.console.web.model.vo.session.SessionInstanceVO;
import com.clougence.clouddm.console.web.model.vo.session.SessionKillAuditObj;
import com.clougence.clouddm.console.web.service.auth.RdpUserService;
import com.clougence.clouddm.console.web.service.session.SessionManageService;
import com.clougence.clouddm.platform.dal.model.ResourceType;
import com.clougence.clouddm.platform.dal.model.monitor.AuditType;
import com.clougence.clouddm.platform.dal.model.monitor.SecurityLevel;
import com.clougence.rdp.service.RdpOpAuditService;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

/**
 * DBA session management controller (B2, design 09-17-session-management).
 * <p>
 * Read endpoints are gated by {@code DM_SESSION_READ}; the kill endpoint is a high-risk write
 * gated by {@code DM_SESSION_KILL} plus the operation-password second factor (global switch
 * controlled), and every successful kill is explicitly written to the operation audit
 * (PermissionGroupController pattern).
 */
@RestController
@RequestMapping(value = DmControllerUrlPrefix.CONSOLE_PREFIX + "/session")
@Slf4j
public class SessionManageController {

    @Resource
    private SessionManageService sessionManageService;
    @Resource
    private RdpOpAuditService    rdpOpAuditService;

    @RequestAuth(DM_SESSION_READ)
    @RequestMapping(value = "/listInstances", method = RequestMethod.POST)
    public ResWebData<List<SessionInstanceVO>> listInstances(HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        return ResWebDataUtils.buildSuccess(sessionManageService.listInstances(puid, uid));
    }

    @RequestAuth(DM_SESSION_READ)
    @RequestMapping(value = "/list", method = RequestMethod.POST)
    public ResWebData<DbSessionListVO> list(@Valid @RequestBody SessionListFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        return ResWebDataUtils.buildSuccess(sessionManageService.list(puid, uid, fo));
    }

    @RequestAuth(value = DM_SESSION_KILL, checkOpPassword = true)
    @RequestMapping(value = "/kill", method = RequestMethod.POST)
    public ResWebData<?> kill(@Valid @RequestBody SessionKillFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        SessionKillAuditObj auditObj = sessionManageService.kill(puid, uid, fo);
        // audit only after business success (high-risk operation, resource = datasource instance)
        rdpOpAuditService.logAndAddOperationAudit(puid, uid, request.getRequestURI(), request.getRemoteAddr(), //
                fo.getDsId(), auditObj, SecurityLevel.HIGH, AuditType.KILL_DB_SESSION, ResourceType.DATASOURCE);
        return ResWebDataUtils.buildSuccess();
    }
}
