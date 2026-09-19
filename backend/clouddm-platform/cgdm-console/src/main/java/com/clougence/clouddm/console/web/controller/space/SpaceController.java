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
package com.clougence.clouddm.console.web.controller.space;

import static com.clougence.clouddm.sdk.security.auth.def.SecRoleAuthLabel.DM_SPACE_READ;

import java.util.List;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.clougence.clouddm.api.common.rpc.ResWebData;
import com.clougence.clouddm.api.common.rpc.ResWebDataUtils;
import com.clougence.clouddm.console.web.constants.DmControllerUrlPrefix;
import com.clougence.clouddm.console.web.global.jwtsession.RequestAuth;
import com.clougence.clouddm.console.web.model.fo.space.SpaceDatabasesFO;
import com.clougence.clouddm.console.web.model.fo.space.SpaceTablesFO;
import com.clougence.clouddm.console.web.model.vo.session.SessionInstanceVO;
import com.clougence.clouddm.console.web.model.vo.space.SpaceDatabasesVO;
import com.clougence.clouddm.console.web.model.vo.space.SpaceTablesVO;
import com.clougence.clouddm.console.web.service.auth.RdpUserService;
import com.clougence.clouddm.console.web.service.space.SpaceService;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

/**
 * DBA table-space usage controller (B4, design 09-19-table-space-usage).
 * <p>
 * Purely read-only: all three endpoints are gated by {@code DM_SPACE_READ}; no write endpoint
 * exists and no operation audit is recorded (platform precedent: read-only endpoints such as the
 * B3 lock analysis and the B2 session list are not audited — channel C's audit obligation applies
 * to mutating actions only). The returned figures are database/schema and table names plus size
 * and row-estimate numbers — no SQL text and no business data samples.
 */
@RestController
@RequestMapping(value = DmControllerUrlPrefix.CONSOLE_PREFIX + "/space")
@Slf4j
public class SpaceController {

    @Resource
    private SpaceService spaceService;

    @RequestAuth(DM_SPACE_READ)
    @RequestMapping(value = "/listInstances", method = RequestMethod.POST)
    public ResWebData<List<SessionInstanceVO>> listInstances(HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        return ResWebDataUtils.buildSuccess(spaceService.listInstances(puid, uid));
    }

    @RequestAuth(DM_SPACE_READ)
    @RequestMapping(value = "/databases", method = RequestMethod.POST)
    public ResWebData<SpaceDatabasesVO> databases(@Valid @RequestBody SpaceDatabasesFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        return ResWebDataUtils.buildSuccess(spaceService.databases(puid, uid, fo));
    }

    @RequestAuth(DM_SPACE_READ)
    @RequestMapping(value = "/tables", method = RequestMethod.POST)
    public ResWebData<SpaceTablesVO> tables(@Valid @RequestBody SpaceTablesFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        return ResWebDataUtils.buildSuccess(spaceService.tables(puid, uid, fo));
    }
}
