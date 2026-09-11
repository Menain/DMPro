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
import static com.clougence.clouddm.sdk.security.auth.def.SecRoleAuthLabel.RDP_WORKER_ORDER_REQUEST;

import java.util.List;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.clougence.clouddm.api.common.rpc.ResWebData;
import com.clougence.clouddm.api.common.rpc.ResWebDataUtils;
import com.clougence.clouddm.console.web.constants.DmControllerUrlPrefix;
import com.clougence.clouddm.console.web.global.jwtsession.RequestAuth;
import com.clougence.clouddm.console.web.model.fo.govticket.GovPairSideFO;
import com.clougence.clouddm.console.web.model.fo.govticket.GovTicketV2CheckFO;
import com.clougence.clouddm.console.web.model.fo.govticket.GovTicketV2GroupIdFO;
import com.clougence.clouddm.console.web.model.fo.govticket.GovTicketV2SubmitFO;
import com.clougence.clouddm.console.web.model.fo.govticket.GovTicketV2TicketIdFO;
import com.clougence.clouddm.console.web.model.vo.dbpair.DbPairVO;
import com.clougence.clouddm.console.web.model.vo.dbpair.DbServiceVO;
import com.clougence.clouddm.console.web.model.vo.govticket.GovTicketV2CheckVO;
import com.clougence.clouddm.console.web.model.vo.govticket.GovTicketV2GroupVO;
import com.clougence.clouddm.console.web.model.vo.govticket.GovTicketV2SubmitVO;
import com.clougence.clouddm.console.web.service.auth.RdpUserService;
import com.clougence.clouddm.console.web.service.govticket.GovTicketV2Service;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

/**
 * V2 governance ticket controller — multi-DB groups, precheck gate, group-level retry.
 * <p>
 * Permissions (design §3):
 * <ul>
 *   <li>availablePairs / availableServices: RefAnyOnes (any logged-in user)</li>
 *   <li>check / submit: RDP_WORKER_ORDER_REQUEST (no DM_DAUTH_TICKET — submit ≠ process, design §11)</li>
 *   <li>retryGroup: DM_DAUTH_TICKET (processing side — DBA only)</li>
 *   <li>groupList: RefAnyOnes (ticket visibility controlled at service layer)</li>
 * </ul>
 */
@RestController
@RequestMapping(value = DmControllerUrlPrefix.CONSOLE_PREFIX)
@Slf4j
public class GovTicketV2Controller {

    @Resource
    private GovTicketV2Service govTicketV2Service;

    @RequestAuth(strategy = RequestAuth.AuthStrategy.RefAnyOnes)
    @RequestMapping(value = "/dbChangeV2/availablePairs", method = RequestMethod.POST)
    public ResWebData<?> availablePairs(@Valid @RequestBody GovPairSideFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        List<DbPairVO> data = govTicketV2Service.availablePairs(puid, fo != null ? fo.getSide() : null);
        return ResWebDataUtils.buildSuccess(data);
    }

    @RequestAuth(strategy = RequestAuth.AuthStrategy.RefAnyOnes)
    @RequestMapping(value = "/dbChangeV2/availableServices", method = RequestMethod.POST)
    public ResWebData<?> availableServices(HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        List<DbServiceVO> data = govTicketV2Service.availableServices(puid);
        return ResWebDataUtils.buildSuccess(data);
    }

    @RequestAuth(RDP_WORKER_ORDER_REQUEST)
    @RequestMapping(value = "/dbChangeV2/check", method = RequestMethod.POST)
    public ResWebData<?> check(@Valid @RequestBody GovTicketV2CheckFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        GovTicketV2CheckVO data = govTicketV2Service.check(puid, uid, fo);
        return ResWebDataUtils.buildSuccess(data);
    }

    @RequestAuth(RDP_WORKER_ORDER_REQUEST)
    @RequestMapping(value = "/dbChangeV2/submit", method = RequestMethod.POST)
    public ResWebData<?> submit(@Valid @RequestBody GovTicketV2SubmitFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        GovTicketV2SubmitVO data = govTicketV2Service.submit(puid, uid, fo);
        return ResWebDataUtils.buildSuccess(data);
    }

    @RequestAuth(DM_DAUTH_TICKET)
    @RequestMapping(value = "/dbChangeV2/retryGroup", method = RequestMethod.POST)
    public ResWebData<?> retryGroup(@Valid @RequestBody GovTicketV2GroupIdFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        govTicketV2Service.retryGroup(puid, uid, fo.getGroupId());
        return ResWebDataUtils.buildSuccess();
    }

    @RequestAuth(strategy = RequestAuth.AuthStrategy.RefAnyOnes)
    @RequestMapping(value = "/dbChangeV2/groupList", method = RequestMethod.POST)
    public ResWebData<?> groupList(@Valid @RequestBody GovTicketV2TicketIdFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        List<GovTicketV2GroupVO> data = govTicketV2Service.groupList(puid, uid, fo.getTicketId());
        return ResWebDataUtils.buildSuccess(data);
    }
}
