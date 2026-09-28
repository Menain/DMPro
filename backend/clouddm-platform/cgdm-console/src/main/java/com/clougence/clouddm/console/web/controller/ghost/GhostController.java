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
package com.clougence.clouddm.console.web.controller.ghost;

import static com.clougence.clouddm.sdk.security.auth.def.SecRoleAuthLabel.DM_GHOST_EXEC;

import java.util.List;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.clougence.clouddm.api.common.rpc.ResWebData;
import com.clougence.clouddm.api.common.rpc.ResWebDataUtils;
import com.clougence.clouddm.console.web.constants.DmControllerUrlPrefix;
import com.clougence.clouddm.console.web.global.jwtsession.RequestAuth;
import com.clougence.clouddm.console.web.model.fo.ghost.GhostExecuteFO;
import com.clougence.clouddm.console.web.model.fo.ghost.GhostPrecheckFO;
import com.clougence.clouddm.console.web.model.fo.ghost.GhostSessionFO;
import com.clougence.clouddm.console.web.model.fo.ghost.GhostTailLogFO;
import com.clougence.clouddm.console.web.model.vo.ghost.GhostExecuteVO;
import com.clougence.clouddm.console.web.model.vo.ghost.GhostOpAuditObj;
import com.clougence.clouddm.console.web.model.vo.ghost.GhostPrecheckVO;
import com.clougence.clouddm.console.web.model.vo.ghost.GhostTaskVO;
import com.clougence.clouddm.console.web.service.auth.RdpUserService;
import com.clougence.clouddm.console.web.service.ghost.GhostService;
import com.clougence.clouddm.platform.dal.model.ResourceType;
import com.clougence.clouddm.platform.dal.model.monitor.AuditType;
import com.clougence.clouddm.platform.dal.model.monitor.SecurityLevel;
import com.clougence.clouddm.sdk.model.ghost.GhostStatusDTO;
import com.clougence.clouddm.sdk.model.ghost.GhostTailResponseDTO;
import com.clougence.rdp.service.RdpOpAuditService;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

/**
 * Lock-free DDL (gh-ost) controller (B6 S4, design 09-17-online-ddl-activation §4.1 — all POST
 * per the platform REST convention the design's GET wording maps onto).
 * <p>
 * The whole surface is gated by {@code DM_GHOST_EXEC} (CAT_DM_OPS; includes DM_SPACE_READ for
 * the launch form's datasource/db cascade). Channel-C obligations are discharged: mutating
 * operations (execute / pause / resume / stop / drop) write an operation audit row on the
 * SUCCESS path only (the B2 kill precedent — the service returns the audit payload, the
 * controller logs it; resource = DATASOURCE with the datasource id as resId, so the
 * getResourceName case exists). Read endpoints (precheck / tailLog / tailStatus / tasks) are
 * not audited (B3/B4 precedent).
 */
@RestController
@RequestMapping(value = DmControllerUrlPrefix.CONSOLE_PREFIX + "/ghost")
@Slf4j
public class GhostController {

    @Resource
    private GhostService     ghostService;
    @Resource
    private RdpOpAuditService rdpOpAuditService;

    @RequestAuth(DM_GHOST_EXEC)
    @RequestMapping(value = "/precheck", method = RequestMethod.POST)
    public ResWebData<GhostPrecheckVO> precheck(@Valid @RequestBody GhostPrecheckFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        return ResWebDataUtils.buildSuccess(ghostService.precheck(puid, uid, fo));
    }

    @RequestAuth(DM_GHOST_EXEC)
    @RequestMapping(value = "/execute", method = RequestMethod.POST)
    public ResWebData<GhostExecuteVO> execute(@Valid @RequestBody GhostExecuteFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        GhostExecuteVO vo = ghostService.execute(puid, uid, fo);
        // audit only after business success (structure change on a live table — high risk)
        audit(request, puid, uid, vo.getAudit(), SecurityLevel.HIGH, AuditType.GHOST_EXECUTE);
        return ResWebDataUtils.buildSuccess(vo);
    }

    @RequestAuth(DM_GHOST_EXEC)
    @RequestMapping(value = "/tailLog", method = RequestMethod.POST)
    public ResWebData<GhostTailResponseDTO> tailLog(@Valid @RequestBody GhostTailLogFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        return ResWebDataUtils.buildSuccess(ghostService.tailLog(puid, uid, fo.getSessionId(), fo.getStartLine()));
    }

    @RequestAuth(DM_GHOST_EXEC)
    @RequestMapping(value = "/tailStatus", method = RequestMethod.POST)
    public ResWebData<GhostStatusDTO> tailStatus(@Valid @RequestBody GhostSessionFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        return ResWebDataUtils.buildSuccess(ghostService.tailStatus(puid, uid, fo.getSessionId()));
    }

    @RequestAuth(DM_GHOST_EXEC)
    @RequestMapping(value = "/pause", method = RequestMethod.POST)
    public ResWebData<?> pause(@Valid @RequestBody GhostSessionFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        GhostOpAuditObj auditObj = ghostService.pause(puid, uid, fo.getSessionId());
        audit(request, puid, uid, auditObj, SecurityLevel.NORMAL, AuditType.GHOST_PAUSE);
        return ResWebDataUtils.buildSuccess();
    }

    @RequestAuth(DM_GHOST_EXEC)
    @RequestMapping(value = "/resume", method = RequestMethod.POST)
    public ResWebData<?> resume(@Valid @RequestBody GhostSessionFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        GhostOpAuditObj auditObj = ghostService.resume(puid, uid, fo.getSessionId());
        audit(request, puid, uid, auditObj, SecurityLevel.NORMAL, AuditType.GHOST_RESUME);
        return ResWebDataUtils.buildSuccess();
    }

    @RequestAuth(DM_GHOST_EXEC)
    @RequestMapping(value = "/stop", method = RequestMethod.POST)
    public ResWebData<?> stop(@Valid @RequestBody GhostSessionFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        GhostOpAuditObj auditObj = ghostService.stop(puid, uid, fo.getSessionId());
        // terminating a live migration is a high-impact decision (shadow table left behind)
        audit(request, puid, uid, auditObj, SecurityLevel.HIGH, AuditType.GHOST_STOP);
        return ResWebDataUtils.buildSuccess();
    }

    @RequestAuth(DM_GHOST_EXEC)
    @RequestMapping(value = "/close", method = RequestMethod.POST)
    public ResWebData<?> close(@Valid @RequestBody GhostSessionFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        // session release is housekeeping, not in the R10 audit set (Faker close precedent)
        ghostService.close(puid, uid, fo.getSessionId());
        return ResWebDataUtils.buildSuccess();
    }

    @RequestAuth(DM_GHOST_EXEC)
    @RequestMapping(value = "/oldTable/drop", method = RequestMethod.POST)
    public ResWebData<?> dropOldTable(@Valid @RequestBody GhostSessionFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        GhostOpAuditObj auditObj = ghostService.dropOldTable(puid, uid, fo.getSessionId());
        // irreversible data disposal — high risk, success path only (frontend double-confirm)
        audit(request, puid, uid, auditObj, SecurityLevel.HIGH, AuditType.GHOST_DROP_OLD_TABLE);
        return ResWebDataUtils.buildSuccess();
    }

    @RequestAuth(DM_GHOST_EXEC)
    @RequestMapping(value = "/tasks", method = RequestMethod.POST)
    public ResWebData<List<GhostTaskVO>> tasks(HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        return ResWebDataUtils.buildSuccess(ghostService.tasks(puid, uid));
    }

    private void audit(HttpServletRequest request, String puid, String uid, GhostOpAuditObj auditObj, SecurityLevel level,
                       AuditType type) {
        rdpOpAuditService.logAndAddOperationAudit(puid, uid, request.getRequestURI(), request.getRemoteAddr(), //
                auditObj.getDsId(), auditObj, level, type, ResourceType.DATASOURCE);
    }
}
