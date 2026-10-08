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
import com.clougence.clouddm.console.web.model.fo.govticket.GovTicketV2ResubmitFO;
import com.clougence.clouddm.console.web.model.fo.govticket.GovTicketV2ReturnTicketFO;
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
 *   <li>retryGroup: RefPrimaryOrMaintainer (processing side — DBA/primary only)</li>
 *   <li>groupList: RefAnyOnes (ticket visibility controlled at service layer)</li>
 * </ul>
 */
@RestController
@RequestMapping(value = DmControllerUrlPrefix.CONSOLE_PREFIX)
@Slf4j
public class GovTicketV2Controller {

    @Resource
    private GovTicketV2Service govTicketV2Service;
    // B7 N2: the no-key rejection trail (alert + DML_BATCH_REJECT audit) — fires at the
    // transaction boundary only (design §8.2), never inside the rolled-back submit transaction.
    @Resource
    private com.clougence.clouddm.console.web.service.dmlbatch.DmlBatchRejectTrailService dmlBatchRejectTrailService;

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
        GovTicketV2SubmitVO data;
        try {
            data = govTicketV2Service.submit(puid, uid, fo);
        } catch (com.clougence.clouddm.console.web.service.dmlbatch.DmlNoKeyRejectException e) {
            // B7 N2 (裁决 #16, design §8.2): the no-key rejection trail fires HERE, at the
            // controller boundary — the exception piercing the service proxy means the submit
            // transaction's rollback is COMPLETE, so the IM alert (no-tx immediate send) and the
            // DML_BATCH_REJECT audit row (autocommit insert) SURVIVE. The trail service is
            // never-throw internally (①② each guarded); this outer guard is the boundary-level
            // belt of the "绝不吞/换原拒绝异常" contract — the rejection semantics are the first
            // priority, the trail is strictly secondary. /check previews never reach this catch
            // (no submit act — 防轰炸纪律).
            try {
                dmlBatchRejectTrailService.trailNoKeyReject(puid, uid, request.getRequestURI(),
                    request.getRemoteAddr(), e);
            } catch (RuntimeException trailFailure) {
                log.error("[GovTicketV2] the no-key rejection trail failed (the rejection itself is rethrown unchanged)",
                    trailFailure);
            }
            throw e;
        }
        return ResWebDataUtils.buildSuccess(data);
    }

    @RequestAuth(strategy = RequestAuth.AuthStrategy.RefPrimaryOrMaintainer)
    @RequestMapping(value = "/dbChangeV2/retryGroup", method = RequestMethod.POST)
    public ResWebData<?> retryGroup(@Valid @RequestBody GovTicketV2GroupIdFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        // B7 N2 (design §7.4): the additive dmlBatchResolution rides the same endpoint — the
        // service guards it (required iff the target segment froze unresolvedIntent) and the
        // permission stays RefPrimaryOrMaintainer (the retry entry's existing gate; the frontend
        // button VISIBILITY additionally keys on DM_GHOST_EXEC — N3, 裁决 #15).
        govTicketV2Service.retryGroup(puid, uid, fo.getGroupId(), fo.getDmlBatchResolution());
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

    @RequestAuth(RDP_WORKER_ORDER_REQUEST)
    @RequestMapping(value = "/dbChangeV2/returnTicket", method = RequestMethod.POST)
    public ResWebData<?> returnTicket(@Valid @RequestBody GovTicketV2ReturnTicketFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        govTicketV2Service.returnTicket(puid, uid, fo);
        return ResWebDataUtils.buildSuccess();
    }

    @RequestAuth(RDP_WORKER_ORDER_REQUEST)
    @RequestMapping(value = "/dbChangeV2/resubmit", method = RequestMethod.POST)
    public ResWebData<?> resubmit(@Valid @RequestBody GovTicketV2ResubmitFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        govTicketV2Service.resubmit(puid, uid, fo);
        return ResWebDataUtils.buildSuccess();
    }
}
