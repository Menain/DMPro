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
package com.clougence.clouddm.console.web.controller.governance;

import static com.clougence.clouddm.platform.dal.model.monitor.SecurityLevel.HIGH;
import static com.clougence.clouddm.sdk.security.auth.def.SecRoleAuthLabel.RDP_DB_CHANGE_GOVERN_READ;
import static com.clougence.clouddm.sdk.security.auth.def.SecRoleAuthLabel.RDP_DB_CHANGE_PROD_DML_DIRECT;
import static com.clougence.clouddm.sdk.security.auth.def.SecRoleAuthLabel.RDP_DB_CHANGE_PROD_PROMOTE;
import static com.clougence.clouddm.sdk.security.auth.def.SecRoleAuthLabel.RDP_WORKER_ORDER_READ;
import static com.clougence.clouddm.sdk.security.auth.def.SecRoleAuthLabel.RDP_WORKER_ORDER_REQUEST;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.clougence.clouddm.api.common.rpc.ResWebData;
import com.clougence.clouddm.api.common.rpc.ResWebDataUtils;
import com.clougence.clouddm.console.web.constants.DmControllerUrlPrefix;
import com.clougence.clouddm.console.web.global.jwtsession.RequestAuth;
import com.clougence.clouddm.console.web.model.fo.governance.GovCorrectStatementFO;
import com.clougence.clouddm.console.web.model.fo.governance.GovDirectDmlSubmitFO;
import com.clougence.clouddm.console.web.model.fo.governance.GovEventTimelineFO;
import com.clougence.clouddm.console.web.model.fo.governance.GovPreSubmitFO;
import com.clougence.clouddm.console.web.model.fo.governance.GovPromotionListFO;
import com.clougence.clouddm.console.web.model.fo.governance.GovPromoteFO;
import com.clougence.clouddm.console.web.model.fo.governance.GovRevisionDetailFO;
import com.clougence.clouddm.console.web.model.fo.governance.GovSplitPreviewFO;
import com.clougence.clouddm.console.web.model.fo.governance.GovStmtTimelineFO;
import com.clougence.clouddm.console.web.model.fo.logicaldb.LogicalDbIdFO;
import com.clougence.clouddm.console.web.model.vo.DmPageVO;
import com.clougence.clouddm.console.web.model.vo.governance.AvailableRevisionVO;
import com.clougence.clouddm.console.web.model.vo.governance.DirectDmlSubmitVO;
import com.clougence.clouddm.console.web.model.vo.governance.PromotionDetailVO;
import com.clougence.clouddm.console.web.model.vo.governance.PromotionVO;
import com.clougence.clouddm.console.web.model.vo.governance.RevisionDetailVO;
import com.clougence.clouddm.console.web.model.vo.governance.SplitPreviewVO;
import com.clougence.clouddm.console.web.model.vo.governance.StmtTimelineVO;
import com.clougence.clouddm.console.web.model.vo.ticket.DmTicketResultVO;
import com.clougence.clouddm.console.web.service.auth.RdpUserService;
import com.clougence.clouddm.console.web.service.governance.DbChangeGovernService;
import com.clougence.clouddm.console.web.service.governance.GovCorrectionService;
import com.clougence.clouddm.console.web.service.governance.GovDirectDmlService;
import com.clougence.clouddm.console.web.service.governance.GovPromotionService;
import com.clougence.clouddm.platform.dal.model.ResourceType;
import com.clougence.clouddm.platform.dal.model.monitor.AuditType;
import com.clougence.rdp.service.RdpOpAuditService;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping(value = DmControllerUrlPrefix.CONSOLE_PREFIX + "/dbChangeGovern")
@Slf4j
public class DbChangeGovernController {

    @Resource
    private DbChangeGovernService dbChangeGovernService;
    @Resource
    private GovCorrectionService  govCorrectionService;
    @Resource
    private GovPromotionService   govPromotionService;
    @Resource
    private GovDirectDmlService   govDirectDmlService;
    @Resource
    private RdpOpAuditService    rdpOpAuditService;

    @RequestAuth(level = HIGH, value = RDP_WORKER_ORDER_REQUEST)
    @RequestMapping(value = "/preSubmit", method = RequestMethod.POST)
    public ResWebData<?> preSubmit(@Valid @RequestBody GovPreSubmitFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        DmTicketResultVO vo = dbChangeGovernService.preSubmit(puid, uid, fo);
        rdpOpAuditService.logAndAddOperationAudit(puid, uid, request.getRequestURI(), request.getRemoteAddr(),
            fo.getLogicalDbId(), fo, HIGH, AuditType.SUBMIT_DB_CHANGE_PRE, ResourceType.LOGICAL_DB);
        return ResWebDataUtils.buildSuccess(vo);
    }

    @RequestAuth(level = HIGH, value = RDP_WORKER_ORDER_REQUEST)
    @RequestMapping(value = "/correctStatement", method = RequestMethod.POST)
    public ResWebData<?> correctStatement(@Valid @RequestBody GovCorrectStatementFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        long logicalDbId = govCorrectionService.correctStatement(puid, uid, fo);
        rdpOpAuditService.logAndAddOperationAudit(puid, uid, request.getRequestURI(), request.getRemoteAddr(),
            logicalDbId, fo, HIGH, AuditType.CORRECT_DB_CHANGE_STMT, ResourceType.LOGICAL_DB);
        return ResWebDataUtils.buildSuccess();
    }

    @RequestAuth(level = HIGH, value = RDP_WORKER_ORDER_READ)
    @RequestMapping(value = "/stmtTimeline", method = RequestMethod.POST)
    public ResWebData<StmtTimelineVO> stmtTimeline(@Valid @RequestBody GovStmtTimelineFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        StmtTimelineVO vo = dbChangeGovernService.stmtTimeline(puid, uid, fo);
        return ResWebDataUtils.buildSuccess(vo);
    }

    // ======================== Phase 10: split preview + event timeline ========================

    @RequestAuth(level = HIGH, value = RDP_WORKER_ORDER_REQUEST)
    @RequestMapping(value = "/splitPreview", method = RequestMethod.POST)
    public ResWebData<SplitPreviewVO> splitPreview(@Valid @RequestBody GovSplitPreviewFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        SplitPreviewVO vo = dbChangeGovernService.splitPreview(puid, uid, fo);
        return ResWebDataUtils.buildSuccess(vo);
    }

    @RequestAuth(level = HIGH, value = RDP_WORKER_ORDER_READ)
    @RequestMapping(value = "/eventTimeline", method = RequestMethod.POST)
    public ResWebData<java.util.List<PromotionDetailVO.EventHandlerVO>> eventTimeline(
            @Valid @RequestBody GovEventTimelineFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        java.util.List<PromotionDetailVO.EventHandlerVO> list = dbChangeGovernService.eventTimeline(puid, uid, fo);
        return ResWebDataUtils.buildSuccess(list);
    }

    // ======================== Phase 6: Promotion ========================

    @RequestAuth(level = HIGH, value = RDP_DB_CHANGE_GOVERN_READ)
    @RequestMapping(value = "/availableRevisions", method = RequestMethod.POST)
    public ResWebData<java.util.List<AvailableRevisionVO>> availableRevisions(HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        java.util.List<AvailableRevisionVO> list = govPromotionService.availableRevisions(puid, uid);
        return ResWebDataUtils.buildSuccess(list);
    }

    @RequestAuth(level = HIGH, value = RDP_DB_CHANGE_GOVERN_READ)
    @RequestMapping(value = "/revisionDetail", method = RequestMethod.POST)
    public ResWebData<RevisionDetailVO> revisionDetail(@Valid @RequestBody GovRevisionDetailFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        RevisionDetailVO vo = govPromotionService.revisionDetail(puid, uid, fo.getRevisionId());
        return ResWebDataUtils.buildSuccess(vo);
    }

    @RequestAuth(level = HIGH, value = RDP_DB_CHANGE_PROD_PROMOTE)
    @RequestMapping(value = "/promote", method = RequestMethod.POST)
    public ResWebData<?> promote(@Valid @RequestBody GovPromoteFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        long promotionId = govPromotionService.promote(puid, uid, fo);
        long logicalDbId = govPromotionService.resolveLogicalDbId(puid, promotionId);
        rdpOpAuditService.logAndAddOperationAudit(puid, uid, request.getRequestURI(), request.getRemoteAddr(),
            logicalDbId, fo, HIGH, AuditType.PROMOTE_DB_CHANGE, ResourceType.LOGICAL_DB);
        return ResWebDataUtils.buildSuccess(promotionId);
    }

    @RequestAuth(level = HIGH, value = RDP_DB_CHANGE_GOVERN_READ)
    @RequestMapping(value = "/promotionList", method = RequestMethod.POST)
    public ResWebData<DmPageVO<PromotionVO>> promotionList(@Valid @RequestBody GovPromotionListFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        DmPageVO<PromotionVO> page = govPromotionService.promotionList(puid, fo);
        return ResWebDataUtils.buildSuccess(page);
    }

    @RequestAuth(level = HIGH, value = RDP_DB_CHANGE_GOVERN_READ)
    @RequestMapping(value = "/promotionDetail", method = RequestMethod.POST)
    public ResWebData<PromotionDetailVO> promotionDetail(@Valid @RequestBody LogicalDbIdFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        PromotionDetailVO vo = govPromotionService.promotionDetail(puid, fo.getId());
        return ResWebDataUtils.buildSuccess(vo);
    }

    // ======================== Phase 8: Path B Direct DML ========================

    /**
     * Direct production DML submit (Path B).
     * <p>
     * DBA-only label + server-side resource auth double insurance.
     * Resolves PROD binding, evaluates threshold, creates three objects in one transaction.
     * <p>
     * Phase 9 seam (resolved): gate_result riskLevel/estimatedRows is consumed by
     * {@code GovChangeFormAssembler} (convertToChangeForm governance branch) for the
     * approval form risk-level field on path-B direct DML tickets.
     */
    @RequestAuth(level = HIGH, value = RDP_DB_CHANGE_PROD_DML_DIRECT)
    @RequestMapping(value = "/directDmlSubmit", method = RequestMethod.POST)
    public ResWebData<DirectDmlSubmitVO> directDmlSubmit(@Valid @RequestBody GovDirectDmlSubmitFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        DirectDmlSubmitVO vo = govDirectDmlService.directDmlSubmit(puid, uid, fo);
        rdpOpAuditService.logAndAddOperationAudit(puid, uid, request.getRequestURI(), request.getRemoteAddr(),
            fo.getLogicalDbId(), fo, HIGH, AuditType.SUBMIT_DB_CHANGE_DIRECT_DML, ResourceType.LOGICAL_DB);
        return ResWebDataUtils.buildSuccess(vo);
    }
}
