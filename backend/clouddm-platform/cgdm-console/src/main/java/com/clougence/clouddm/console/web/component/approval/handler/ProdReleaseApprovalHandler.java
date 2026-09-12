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
package com.clougence.clouddm.console.web.component.approval.handler;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.console.web.component.approval.ApprovalHandler;
import com.clougence.clouddm.console.web.component.approval.ApprovalStateService;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.component.cicd.ImSenderService;
import com.clougence.clouddm.console.web.component.execute.AutoExecService;
import com.clougence.clouddm.console.web.component.governance.ProdReleaseFormAssembler;
import com.clougence.clouddm.console.web.component.governance.ProdReleaseStateMachine;
import com.clougence.clouddm.console.web.global.i18n.DmI18nUtils;
import com.clougence.clouddm.console.web.global.i18n.I18nDmMsgKeys;
import com.clougence.clouddm.console.web.model.vo.PrimaryUserVO;
import com.clougence.clouddm.console.web.service.governance.ProdReleaseService;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.AuthDal;
import com.clougence.clouddm.platform.dal.access.ProdReleaseDal;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStage;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalType;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.auth.AccountType;
import com.clougence.clouddm.platform.dal.model.auth.DmAuthUserDO;
import com.clougence.clouddm.platform.dal.model.auth.RsAuthPersonObj;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.ProdReleaseStatus;
import com.clougence.clouddm.platform.plugin.PluginManager;
import com.clougence.clouddm.sdk.approval.ApprovalActivityInfo;
import com.clougence.clouddm.sdk.approval.ApprovalCreateInstanceResult;
import com.clougence.clouddm.sdk.approval.ApprovalProviderSpi;
import com.clougence.clouddm.sdk.approval.form.ChangeForm;
import com.clougence.clouddm.sdk.model.exception.ThirdPartyApiException;
import com.clougence.utils.JsonUtils;
import com.clougence.utils.StringUtils;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * Approval handler for production release tickets (ApprovalBiz.DM_PROD_RELEASE).
 * <p>
 * Lifecycle:
 * - createApproval: external approval type creates third-party instance; Internal no-op.
 * - approvalApproved: release APPROVING→APPROVED, ticket→WAIT_CONFIRM.
 * - approvalRejected: release→REJECTED, delete stmt rows (release UK locks), event.
 * - approvalCanceled: release→CANCELLED, delete stmt rows, event.
 * - approvalCompleted: release EXECUTING→DONE (idempotent — may also be set by handleReleaseJobCompletion).
 * - approvalFailed: release EXECUTING→PARTIAL_FAILED (idempotent).
 * - executeTicket/runningCheck: read release status for ticket display.
 * - queryPerson: reuse ChangeApprovalHandler logic (primary + RDP_WORKER_ORDER_APPROVE + DM_DAUTH_TICKET).
 */
@Slf4j
@Service
public class ProdReleaseApprovalHandler implements ApprovalHandler {

    @Resource
    private ApprovalDal              approvalDal;
    @Resource
    private AuthDal                  authDal;
    @Resource
    private ApprovalStateService     approvalStateService;
    @Resource
    private ProdReleaseDal           prodReleaseDal;
    @Resource
    private ProdReleaseStateMachine  releaseStateMachine;
    @Resource
    private ProdReleaseFormAssembler  prodReleaseFormAssembler;
    // @Lazy breaks the bean cycle: provider -> this handler -> ProdReleaseService
    //   -> ApprovalControlService -> ApprovalFlowService -> provider (callbacks only
    //   resolve the service at runtime, never during wiring).
    @org.springframework.context.annotation.Lazy
    @Resource
    private ProdReleaseService        prodReleaseService;
    // @Lazy breaks the bean cycle: AutoExecServiceImpl -> ApprovalControlService ->
    //   this handler (handlers are constructor-collected into the flow services).
    @org.springframework.context.annotation.Lazy
    @Resource
    private AutoExecService           autoExecService;

    @Override
    public ApprovalBiz handleType() {
        return ApprovalBiz.DM_PROD_RELEASE;
    }

    @Override
    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRED)
    public void createApproval(long approvalId, ImSenderService sender) {
        DmApprovalDO ticketDO = approvalDal.approvalMapper().selectByIdForUpdate(approvalId);
        if (ticketDO.getApproType() == ApprovalType.Internal) {
            return; // only external approval need to create approval instance.
        }

        // Build form from release + stmt snapshot data
        // (match ChangeApprovalHandler.convertToChangeForm: parse failure → null → degraded form, not exception)
        ApprovalMO info;
        try {
            info = StringUtils.isBlank(ticketDO.getTicketInfo())
                ? null : JsonUtils.toObj(ticketDO.getTicketInfo(), ApprovalMO.class);
        } catch (Exception e) {
            info = null;
        }
        ChangeForm form = prodReleaseFormAssembler.build(ticketDO, info, ticketDO.getApproTemplateIdentity());

        ApprovalCreateInstanceResult createInstance;
        try {
            ApprovalProviderSpi approvalSdkService = PluginManager.findSpi(ApprovalProviderSpi.class, ticketDO.getApproType().name());
            createInstance = approvalSdkService.createApprovalInstance(ticketDO.getPrimaryUid(), form);
        } catch (ThirdPartyApiException e) {
            this.approvalStateService.updateApprovalStatus(approvalId, ApprovalStatus.FAILED, e.getMessage());
            this.approvalFailed(approvalId, ticketDO.getApproBiz(), sender);
            return;
        }

        for (ApprovalActivityInfo activity : createInstance.getActivityList()) {
            this.approvalStateService.initializeActivity(ticketDO.getId(), ApprovalStage.APPROVAL,
                activity.getActivityId(), activity.getActivityName(), activity.getOrder(), null, null);
        }

        String url = null;
        if (createInstance.getApprovalUrl() != null) {
            url = JsonUtils.toJson(createInstance.getApprovalUrl());
        }
        approvalDal.approvalMapper().updateThirdApprovalInfo(ticketDO.getId(), createInstance.getApprovalIdentity(), url);
    }

    @Override
    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRED)
    public void approvalApproved(long approvalId, ApprovalBiz bizType, ImSenderService sender) {
        // Set ticket to WAIT_CONFIRM
        this.approvalStateService.updateApprovalStatus(approvalId, ApprovalStatus.WAIT_CONFIRM, null);

        // Transit release APPROVING→APPROVED + event
        DmApprovalDO ticket = this.approvalDal.approvalMapper().queryById(approvalId);
        Long releaseId = extractReleaseId(ticket);
        if (releaseId != null) {
            this.prodReleaseService.handleApproved(releaseId);
        }
    }

    @Override
    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRED)
    public void approvalRejected(long approvalId, ApprovalBiz bizType, ImSenderService sender) {
        DmApprovalDO ticket = this.approvalDal.approvalMapper().queryById(approvalId);
        Long releaseId = extractReleaseId(ticket);
        if (releaseId != null) {
            this.prodReleaseService.handleRejected(releaseId);
        }
    }

    @Override
    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRED)
    public void approvalCanceled(long approvalId, ApprovalBiz bizType, ImSenderService sender) {
        DmApprovalDO ticket = this.approvalDal.approvalMapper().queryById(approvalId);
        Long releaseId = extractReleaseId(ticket);
        if (releaseId != null) {
            this.prodReleaseService.handleCancelled(releaseId);
        }
    }

    @Override
    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRED)
    public void approvalCompleted(long approvalId, ApprovalBiz bizType, ImSenderService sender) {
        // Called from external SPI completion or executeTicket path.
        // Idempotent — handleReleaseJobCompletion may have already transited.
        DmApprovalDO ticket = this.approvalDal.approvalMapper().queryById(approvalId);
        Long releaseId = extractReleaseId(ticket);
        if (releaseId != null) {
            this.releaseStateMachine.transit(releaseId,
                Set.of(ProdReleaseStatus.EXECUTING), ProdReleaseStatus.DONE);
        }
    }

    @Override
    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRED)
    public void approvalFailed(long approvalId, ApprovalBiz bizType, ImSenderService sender) {
        // Called when execution fails. Idempotent.
        DmApprovalDO ticket = this.approvalDal.approvalMapper().queryById(approvalId);
        Long releaseId = extractReleaseId(ticket);
        if (releaseId != null) {
            this.releaseStateMachine.transit(releaseId,
                Set.of(ProdReleaseStatus.EXECUTING), ProdReleaseStatus.PARTIAL_FAILED);
        }
    }

    @Override
    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRED)
    public void executeTicket(long approvalId, ApprovalBiz bizType, ImSenderService sender) {
        // WAIT_EXEC sweep entry: safety net for the release completion aggregation
        // (race fix 2026-09-12, mirrors ChangeApprovalHandler.recoverV2CompletionIfDue).
        // A release left EXECUTING with every stmt terminal means a completion callback
        // skipped the aggregation — finish it here so the ticket leaves WAIT_EXEC.
        DmApprovalDO ticket = this.approvalDal.approvalMapper().queryById(approvalId);
        Long releaseId = extractReleaseId(ticket);
        if (releaseId != null) {
            this.autoExecService.recoverReleaseCompletionIfDue(releaseId);
        }
    }

    @Override
    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRED)
    public void runningCheck(long approvalId, ApprovalBiz bizType, ImSenderService sender) {
        // No-op — release execution is driven by job completion callbacks.
    }

    @Override
    public List<PrimaryUserVO> queryPerson(long approvalId) {
        DmApprovalDO ticketDO = this.approvalDal.approvalMapper().queryById(approvalId);
        List<PrimaryUserVO> userVOS = new ArrayList<>();

        // add primary account
        DmAuthUserDO parentUserDO = this.authDal.userMapper().queryByUid(ticketDO.getPrimaryUid());
        PrimaryUserVO primaryUserVO = new PrimaryUserVO();
        primaryUserVO.setUid(ticketDO.getPrimaryUid());
        primaryUserVO.setUsername(parentUserDO.getUsername());
        userVOS.add(primaryUserVO);

        // add sub account who have auth to approval ticket and manage datasource
        List<RsAuthPersonObj> personDOS = this.authDal.userMapper().queryApproPerson(
                AccountType.SUB_ACCOUNT, parentUserDO.getId(), ticketDO.getBindDsId(), ticketDO.getLevelPath());
        for (RsAuthPersonObj personDO : personDOS) {
            List<String> roleAuthLabels = personDO.getRoleAuthLabels();
            List<String> resAuthLabel = personDO.getResAuthLabel();
            if (roleAuthLabels != null && !roleAuthLabels.isEmpty()
                && resAuthLabel != null && !resAuthLabel.isEmpty()
                && roleAuthLabels.contains(com.clougence.clouddm.sdk.security.auth.def.SecRoleAuthLabel.RDP_WORKER_ORDER_APPROVE)
                && resAuthLabel.contains(com.clougence.clouddm.sdk.security.auth.def.SecDataAuthLabel.DM_DAUTH_TICKET)) {
                PrimaryUserVO subVO = new PrimaryUserVO();
                subVO.setUid(personDO.getUid());
                subVO.setUsername(personDO.getUsername());
                userVOS.add(subVO);
            }
        }

        return userVOS;
    }

    private Long extractReleaseId(DmApprovalDO ticket) {
        if (ticket == null || StringUtils.isBlank(ticket.getTicketInfo())) {
            return null;
        }
        ApprovalMO mo = JsonUtils.toObj(ticket.getTicketInfo(), ApprovalMO.class);
        return mo != null ? mo.getReleaseId() : null;
    }
}
