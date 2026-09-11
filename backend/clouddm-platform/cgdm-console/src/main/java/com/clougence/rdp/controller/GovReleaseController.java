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

import static com.clougence.clouddm.sdk.security.auth.def.SecDataAuthLabel.DM_DAUTH_TICKET;

import java.util.List;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.clougence.clouddm.api.common.rpc.ResWebData;
import com.clougence.clouddm.api.common.rpc.ResWebDataUtils;
import com.clougence.clouddm.console.web.constants.DmControllerUrlPrefix;
import com.clougence.clouddm.console.web.global.jwtsession.RequestAuth;
import com.clougence.clouddm.console.web.model.fo.prodrelease.ProdReleaseCreateFO;
import com.clougence.clouddm.console.web.model.fo.prodrelease.ProdReleaseDetailFO;
import com.clougence.clouddm.console.web.model.fo.prodrelease.ProdReleaseListFO;
import com.clougence.clouddm.console.web.model.fo.prodrelease.ProdReleaseRetryStmtFO;
import com.clougence.clouddm.console.web.model.vo.prodrelease.ProdReleaseDetailVO;
import com.clougence.clouddm.console.web.model.vo.prodrelease.ProdReleaseListVO;
import com.clougence.clouddm.console.web.service.auth.RdpUserService;
import com.clougence.clouddm.console.web.service.governance.ProdReleaseService;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

/**
 * Production release controller — create (DM_DAUTH_TICKET), list/detail (RefAnyOnes + tenant filter),
 * retryStmt (DM_DAUTH_TICKET + tenant).
 */
@RestController
@RequestMapping(value = DmControllerUrlPrefix.CONSOLE_PREFIX)
@Slf4j
public class GovReleaseController {

    @Resource
    private ProdReleaseService prodReleaseService;

    @RequestAuth(DM_DAUTH_TICKET)
    @RequestMapping(value = "/govrelease/create", method = RequestMethod.POST)
    public ResWebData<?> create(@Valid @RequestBody ProdReleaseCreateFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        long releaseId = prodReleaseService.createRelease(puid, uid, fo);
        return ResWebDataUtils.buildSuccess(releaseId);
    }

    @RequestAuth(strategy = RequestAuth.AuthStrategy.RefAnyOnes)
    @RequestMapping(value = "/govrelease/list", method = RequestMethod.POST)
    public ResWebData<?> list(@RequestBody ProdReleaseListFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        List<ProdReleaseListVO> data = prodReleaseService.listReleases(
            puid, fo != null ? fo.getStatus() : null,
            fo != null ? fo.getPage() : 1, fo != null ? fo.getSize() : 20);
        return ResWebDataUtils.buildSuccess(data);
    }

    @RequestAuth(strategy = RequestAuth.AuthStrategy.RefAnyOnes)
    @RequestMapping(value = "/govrelease/detail", method = RequestMethod.POST)
    public ResWebData<?> detail(@Valid @RequestBody ProdReleaseDetailFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        ProdReleaseDetailVO data = prodReleaseService.getDetail(puid, fo.getReleaseId());
        return ResWebDataUtils.buildSuccess(data);
    }

    @RequestAuth(DM_DAUTH_TICKET)
    @RequestMapping(value = "/govrelease/retryStmt", method = RequestMethod.POST)
    public ResWebData<?> retryStmt(@Valid @RequestBody ProdReleaseRetryStmtFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        prodReleaseService.retryReleaseStmt(puid, uid, fo.getStmtId());
        return ResWebDataUtils.buildSuccess();
    }
}
