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

import static com.clougence.clouddm.sdk.security.auth.def.SecRoleAuthLabel.GOV_DB_PAIR_MANAGE;

import java.util.List;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.clougence.clouddm.api.common.rpc.ResWebData;
import com.clougence.clouddm.api.common.rpc.ResWebDataUtils;
import com.clougence.clouddm.console.web.constants.DmControllerUrlPrefix;
import com.clougence.clouddm.console.web.global.jwtsession.RequestAuth;
import com.clougence.clouddm.console.web.model.fo.dbpair.DbPairCreateFO;
import com.clougence.clouddm.console.web.model.fo.dbpair.DbPairIdFO;
import com.clougence.clouddm.console.web.model.fo.dbpair.DbPairListFO;
import com.clougence.clouddm.console.web.model.fo.dbpair.DbPairUpdateFO;
import com.clougence.clouddm.console.web.model.fo.dbpair.DbServiceCreateFO;
import com.clougence.clouddm.console.web.model.fo.dbpair.DbServiceIdFO;
import com.clougence.clouddm.console.web.model.fo.dbpair.DbServiceListFO;
import com.clougence.clouddm.console.web.model.fo.dbpair.DbServiceUpdateFO;
import com.clougence.clouddm.console.web.model.vo.dbpair.DbPairVO;
import com.clougence.clouddm.console.web.model.vo.dbpair.DbServiceVO;
import com.clougence.clouddm.console.web.service.auth.RdpUserService;
import com.clougence.clouddm.console.web.service.dbpair.DbPairService;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

/**
 * P1 DB pair admin controller: manages pre-prod to production DB mappings and service lists.
 * All endpoints require {@link SecRoleAuthLabel#GOV_DB_PAIR_MANAGE}.
 * <p>
 * Note: audit logging is intentionally omitted in P1 — registering a new ResourceType
 * requires touching the 4-point audit contract (AuditType, ResourceType, getResourceName switch,
 * resourceFlagDesc switch) in RdpOpAuditServiceImpl which is out of scope for this task.
 * Audit can be added in a follow-up when the ResourceType registration is done holistically.
 */
@RestController
@RequestMapping(value = DmControllerUrlPrefix.CONSOLE_PREFIX)
@Slf4j
public class DbPairController {

    @Resource
    private DbPairService dbPairService;

    // ==================== Pair ====================

    @RequestAuth(GOV_DB_PAIR_MANAGE)
    @RequestMapping(value = "/dbPair/list", method = RequestMethod.POST)
    public ResWebData<?> pairList(@Valid @RequestBody DbPairListFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        List<DbPairVO> data = dbPairService.pairList(puid, fo);
        return ResWebDataUtils.buildSuccess(data);
    }

    @RequestAuth(GOV_DB_PAIR_MANAGE)
    @RequestMapping(value = "/dbPair/create", method = RequestMethod.POST)
    public ResWebData<?> pairCreate(@Valid @RequestBody DbPairCreateFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        DbPairVO vo = dbPairService.pairCreate(puid, uid, fo);
        return ResWebDataUtils.buildSuccess(vo);
    }

    @RequestAuth(GOV_DB_PAIR_MANAGE)
    @RequestMapping(value = "/dbPair/update", method = RequestMethod.POST)
    public ResWebData<?> pairUpdate(@Valid @RequestBody DbPairUpdateFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        dbPairService.pairUpdate(puid, uid, fo);
        return ResWebDataUtils.buildSuccess();
    }

    @RequestAuth(GOV_DB_PAIR_MANAGE)
    @RequestMapping(value = "/dbPair/delete", method = RequestMethod.POST)
    public ResWebData<?> pairDelete(@Valid @RequestBody DbPairIdFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        dbPairService.pairDelete(puid, uid, fo.getId());
        return ResWebDataUtils.buildSuccess();
    }

    // ==================== Service ====================

    @RequestAuth(GOV_DB_PAIR_MANAGE)
    @RequestMapping(value = "/dbService/list", method = RequestMethod.POST)
    public ResWebData<?> serviceList(@Valid @RequestBody DbServiceListFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        List<DbServiceVO> data = dbPairService.serviceList(puid, fo);
        return ResWebDataUtils.buildSuccess(data);
    }

    @RequestAuth(GOV_DB_PAIR_MANAGE)
    @RequestMapping(value = "/dbService/create", method = RequestMethod.POST)
    public ResWebData<?> serviceCreate(@Valid @RequestBody DbServiceCreateFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        DbServiceVO vo = dbPairService.serviceCreate(puid, uid, fo);
        return ResWebDataUtils.buildSuccess(vo);
    }

    @RequestAuth(GOV_DB_PAIR_MANAGE)
    @RequestMapping(value = "/dbService/update", method = RequestMethod.POST)
    public ResWebData<?> serviceUpdate(@Valid @RequestBody DbServiceUpdateFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        dbPairService.serviceUpdate(puid, uid, fo);
        return ResWebDataUtils.buildSuccess();
    }

    @RequestAuth(GOV_DB_PAIR_MANAGE)
    @RequestMapping(value = "/dbService/delete", method = RequestMethod.POST)
    public ResWebData<?> serviceDelete(@Valid @RequestBody DbServiceIdFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        dbPairService.serviceDelete(puid, uid, fo.getId());
        return ResWebDataUtils.buildSuccess();
    }
}
