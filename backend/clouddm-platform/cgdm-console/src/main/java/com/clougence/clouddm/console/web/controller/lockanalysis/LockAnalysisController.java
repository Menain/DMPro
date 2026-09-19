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
package com.clougence.clouddm.console.web.controller.lockanalysis;

import static com.clougence.clouddm.sdk.security.auth.def.SecRoleAuthLabel.DM_LOCK_READ;

import java.util.List;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.clougence.clouddm.api.common.rpc.ResWebData;
import com.clougence.clouddm.api.common.rpc.ResWebDataUtils;
import com.clougence.clouddm.console.web.constants.DmControllerUrlPrefix;
import com.clougence.clouddm.console.web.global.jwtsession.RequestAuth;
import com.clougence.clouddm.console.web.model.fo.lockanalysis.LockOverviewFO;
import com.clougence.clouddm.console.web.model.vo.lockanalysis.LockOverviewVO;
import com.clougence.clouddm.console.web.model.vo.session.SessionInstanceVO;
import com.clougence.clouddm.console.web.service.auth.RdpUserService;
import com.clougence.clouddm.console.web.service.lockanalysis.LockAnalysisService;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

/**
 * DBA lock &amp; deadlock analysis controller (B3, design 09-17-lock-deadlock-analysis).
 * <p>
 * Purely read-only: both endpoints are gated by {@code DM_LOCK_READ}; no write endpoint exists
 * and no operation audit is recorded (platform precedent: read-only endpoints such as the B2
 * session list and slow-SQL pages are not audited — channel C's audit obligation applies to
 * mutating actions only). Session termination goes through the B2 session-management page.
 */
@RestController
@RequestMapping(value = DmControllerUrlPrefix.CONSOLE_PREFIX + "/lockAnalysis")
@Slf4j
public class LockAnalysisController {

    @Resource
    private LockAnalysisService lockAnalysisService;

    @RequestAuth(DM_LOCK_READ)
    @RequestMapping(value = "/listInstances", method = RequestMethod.POST)
    public ResWebData<List<SessionInstanceVO>> listInstances(HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        return ResWebDataUtils.buildSuccess(lockAnalysisService.listInstances(puid, uid));
    }

    @RequestAuth(DM_LOCK_READ)
    @RequestMapping(value = "/overview", method = RequestMethod.POST)
    public ResWebData<LockOverviewVO> overview(@Valid @RequestBody LockOverviewFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        return ResWebDataUtils.buildSuccess(lockAnalysisService.overview(puid, uid, fo));
    }
}
