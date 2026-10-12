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
package com.clougence.clouddm.console.web.component.approval.impl;

import java.util.*;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.console.web.component.approval.ApprovalFlowService;
import com.clougence.clouddm.console.web.component.approval.ApprovalHandler;
import com.clougence.clouddm.console.web.component.approval.ApprovalStateService;
import com.clougence.clouddm.console.web.component.approval.model.ExternalCommandMeta;
import com.clougence.clouddm.console.web.component.cicd.ImSenderService;
import com.clougence.clouddm.console.web.component.config.RootUserConfig;
import com.clougence.clouddm.console.web.global.i18n.DmI18nUtils;
import com.clougence.clouddm.console.web.global.i18n.I18nRdpMsgKeys;
import com.clougence.clouddm.console.web.model.vo.RdpApproTemplateVO;
import com.clougence.clouddm.console.web.util.RdpConvertUtils;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.AuthDal;
import com.clougence.clouddm.platform.dal.access.DbChangeEventDal;
import com.clougence.clouddm.platform.dal.access.SystemDal;
import com.clougence.clouddm.platform.dal.model.approval.*;
import com.clougence.clouddm.platform.dal.model.auth.DmAuthUserDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO;
import com.clougence.clouddm.platform.dal.model.dbchange.GovEventType;
import com.clougence.clouddm.platform.dal.model.system.DmSysUserConfDO;
import com.clougence.clouddm.platform.plugin.PluginManager;
import com.clougence.clouddm.sdk.LifeSpiRequest;
import com.clougence.clouddm.sdk.LifeSpiResponse;
import com.clougence.clouddm.sdk.LifeSpiStatus;
import com.clougence.clouddm.sdk.approval.*;
import com.clougence.clouddm.sdk.model.exception.ThirdPartyApiErrorType;
import com.clougence.clouddm.sdk.model.exception.ThirdPartyApiException;
import com.clougence.clouddm.sdk.service.approval.ApprovalActivity;
import com.clougence.clouddm.sdk.service.approval.ApprovalActivityStatus;
import com.clougence.clouddm.sdk.service.approval.ApprovalIdentity;
import com.clougence.clouddm.sdk.service.approval.ApprovalRefreshService;
import com.clougence.utils.CollectionUtils;
import com.clougence.utils.JsonUtils;
import com.clougence.utils.StringUtils;
import com.fasterxml.jackson.core.type.TypeReference;

import jakarta.annotation.Resource;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

/**
 * @author Ekko
 * @date 2024/5/7 14:16
*/
@Slf4j
@Service
public class ApprovalProviderServiceImpl implements ApprovalRefreshService {

    /**
     * Direct-connect approval channels deprecated by the DingTalk bridge architecture
     * (10-09 decision #2). The bridge project holds the only DingTalk app + Stream connection;
     * new configurations must use {@link ApprovalType#External}. Rows for these providers are
     * KEPT in getTicketTypes (so legacy type=DingTalk environments still echo in the env-param
     * modal) but forced enable=false. The runtime chain (submit routing / template check /
     * scheduler / Stream) reads the STORED env param — never this list — so already-configured
     * environments keep working bit-for-bit. Plugin code is untouched (deprecation is display-only).
     */
    private static final Set<ApprovalProvider> DEPRECATED_DIRECT_CHANNELS =
        Set.of(ApprovalProvider.DingTalk, ApprovalProvider.Feishu, ApprovalProvider.Wechat);

    /**
     * Guard-rejection reasons recorded in EXTERNAL_COMMAND_REJECTED eventData
     * (10-11 rejected-command-audit): end status / not WAIT_APPROVAL / not the External channel.
     * <p>
     * ADR-012 round binding (task 10-11-approval-round-binding) additively extends the
     * vocabulary: FLOW_ID_MISMATCH / ROUND_MISMATCH (tolerant mismatch rejections of the
     * three-state commands) and FLOW_ID_MISSING / ROUND_MISSING (strict tier — config-gated
     * by govExternalCommandStrictFlow, default false = unreachable); O-C2-1 adds the
     * execute/return open-command guard reasons (EXECUTE_STATUS_MISMATCH / RETURN_GUARD —
     * CHANNEL_MISMATCH is reused for the execute channel guard). Public because
     * GovOpenApiServiceImpl audits through the same vocabulary (single reason source).
     */
    public static final String REJECT_REASON_END_STATUS              = "END_STATUS";
    public static final String REJECT_REASON_STATUS_MISMATCH         = "STATUS_MISMATCH";
    public static final String REJECT_REASON_CHANNEL_MISMATCH        = "CHANNEL_MISMATCH";
    public static final String REJECT_REASON_FLOW_ID_MISMATCH        = "FLOW_ID_MISMATCH";
    public static final String REJECT_REASON_ROUND_MISMATCH          = "ROUND_MISMATCH";
    public static final String REJECT_REASON_FLOW_ID_MISSING         = "FLOW_ID_MISSING";
    public static final String REJECT_REASON_ROUND_MISSING           = "ROUND_MISSING";
    public static final String REJECT_REASON_EXECUTE_STATUS_MISMATCH = "EXECUTE_STATUS_MISMATCH";
    /** umbrella reason of the /tickets/return preconditions (status/approBiz/ticketType/failed-group/merged — the throw site inside GovTicketV2ServiceImpl carries the detail; eventData.ticketStatus + the application log disambiguate). */
    public static final String REJECT_REASON_RETURN_GUARD            = "RETURN_GUARD";

    /**
     * Strict-tier fail-safe default (ADR-012 Decision 3 / design M7): false = tolerant mode —
     * a missing externalFlowId/externalRound NEVER rejects. Flipping the switch to true is a
     * gray-release decision requiring bridge-upgrade integration-test evidence (the frozen §5.4
     * "ticketId-only minimal call" contract forbids strict-by-default). A config read failure
     * stays tolerant as well — the strict tier must never activate by accident.
     */
    public static final boolean DEFAULT_STRICT_FLOW_MODE = false;

    @Resource
    private SystemDal                               systemDal;
    @Resource
    private AuthDal                                 authDal;
    @Resource
    private ApprovalDal                             approvalDal;
    @Resource
    private ImSenderService                         imSenderService;
    @Resource
    private ApprovalStateService                    approvalStateService;
    @Resource
    private DbChangeEventDal                        dbChangeEventDal;
    @Resource
    private PlatformTransactionManager            txManager;

    private final Map<ApprovalBiz, ApprovalHandler> approvalHandlers;

    public ApprovalProviderServiceImpl(List<ApprovalHandler> approvalHandlers){
        this.approvalHandlers = new EnumMap<>(ApprovalBiz.class);
        for (ApprovalHandler approvalHandler : approvalHandlers) {
            ApprovalBiz type = approvalHandler.handleType();
            if (this.approvalHandlers.putIfAbsent(type, approvalHandler) != null) {
                throw new IllegalStateException("ApprovalHandler about " + type + " already exists");
            }
        }
    }

    @Override
    @SneakyThrows
    public void refreshTicket(ApprovalIdentity callback) {
        DmApprovalDO approvalDO = approvalDal.approvalMapper().queryByApproIdentity(callback.getApproIdentity(), callback.getProviderType(), callback.getOwnerUid());
        if (approvalDO == null) {
            log.error("Callback event not find ticket for approval instance: {} and type: {} and puid: {}", //
                    callback.getApproIdentity(), callback.getProviderType(), callback.getOwnerUid());
            return;
        }
        this.refreshApprovalStatus(approvalDO.getId());
    }

    @Override
    @SneakyThrows
    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRED)
    public void updateActivity(ApprovalActivity activity) {
        DmApprovalDO approvalDO = approvalDal.approvalMapper().queryByApproIdentity(activity.getApprovalIdentity(), activity.getPlatform(), activity.getPuid());
        // avoid receive another callback
        if (approvalDO == null) {
            log.error("Callback event not find ticket for approval instance: {} and type: {} and puid: {}", //
                    activity.getApprovalIdentity(), activity.getPlatform(), activity.getPuid());
            return;
        }
        DmApprovalProcessDO processDO = this.approvalDal.processMapper().queryByStage(approvalDO.getId(), ApprovalStage.APPROVAL);
        DmApprovalProcessActivityDO activityDO = this.approvalDal.activityMapper().queryByProcessIdAndActivityIdForUpdate(processDO.getId(), activity.getActivityId());

        String context = activityDO.getContext();
        List<ApprovalActivity> list;
        if (StringUtils.isEmpty(context)) {
            list = new ArrayList<>();
        } else {
            list = JsonUtils.toList(context, new TypeReference<>() {});
        }

        ApprovalActivity originTask = null;
        for (ApprovalActivity approvalTask : list) {
            if (approvalTask.getTaskId().equals(activity.getTaskId())) {
                originTask = approvalTask;
                break;
            }
        }
        if (originTask == null && activity.getStatus() != ApprovalActivityStatus.CLOSE) {
            if (StringUtils.isEmpty(activity.getUserName())) {
                ApprovalProviderSpi service = PluginManager.findSpi(ApprovalProviderSpi.class, approvalDO.getApproType().name());
                ApprovalUserInfo userInfo = service.getUserDetailByUid(approvalDO.getPrimaryUid(), activity.getUserId());
                activity.setUserName(userInfo.getUsername());
            }
            list.add(activity);
        } else if (ApprovalActivityStatus.canUpdate(originTask.getStatus(), activity.getStatus())) {
            if (activity.getStatus() == ApprovalActivityStatus.CLOSE) {
                list.remove(originTask);
            } else {
                originTask.setStatus(activity.getStatus());
                originTask.setRemark(activity.getRemark());
                originTask.setFinishTime(activity.getFinishTime());
            }
        }

        String json = JsonUtils.toJson(list);
        approvalDal.activityMapper().updateContext(processDO.getId(), activityDO.getActivityId(), json);
    }

    public List<Map<String, Object>> getTicketTypes(String ownerUid) {
        List<Map<String, Object>> list = new ArrayList<>();

        // inner
        Map<String, Object> innerProvider = new HashMap<>();
        innerProvider.put("approvalType", ApprovalType.Internal.name());
        innerProvider.put("i18n", DmI18nUtils.getMessage(ApprovalType.Internal.getI18nKey()));
        innerProvider.put("enable", true);
        innerProvider.put("desc", "");
        list.add(innerProvider);

        // External orchestration mode: command-driven, no SPI plugin, always enabled
        // (mirrors Internal row shape — no findSpi/status check, no config toggle)
        Map<String, Object> externalProvider = new HashMap<>();
        externalProvider.put("approvalType", ApprovalType.External.name());
        externalProvider.put("i18n", DmI18nUtils.getMessage(ApprovalType.External.getI18nKey()));
        externalProvider.put("enable", true);
        externalProvider.put("desc", "");
        list.add(externalProvider);

        List<String> serviceNames = PluginManager.getSpiNamesByType(ApprovalProviderSpi.class);

        for (ApprovalProvider type : ApprovalFlowService.SUPPORT_LIST) {
            Map<String, Object> provider = new HashMap<>();
            provider.put("approvalType", type.name());
            provider.put("i18n", DmI18nUtils.getMessage(ApprovalType.valueOfProvider(type).getI18nKey()));

            // Deprecated direct-connect channels (DingTalk bridge architecture, 10-09): keep the
            // row for legacy env echo, force enable=false so new configurations cannot pick it.
            // Short-circuits the plugin/enable/status probes below — a retired plugin is never
            // called for a channel that is no longer selectable anyway.
            if (DEPRECATED_DIRECT_CHANNELS.contains(type)) {
                provider.put("enable", false);
                provider.put("desc", DmI18nUtils.getMessage(I18nRdpMsgKeys.TICKET_APPROVAL_TYPE_DEPRECATED.name()));
                list.add(provider);
                continue;
            }

            // not found plugin
            if (!serviceNames.contains(type.name())) {
                provider.put("enable", false);
                provider.put("desc", DmI18nUtils.getMessage(I18nRdpMsgKeys.TICKET_APPROVAL_PLUGIN_NOT_FOUND.name()));
                list.add(provider);
                continue;
            }

            // not enable
            if (!this.checkEnableApproval(ownerUid, type)) {
                provider.put("enable", false);
                provider.put("desc", DmI18nUtils.getMessage(I18nRdpMsgKeys.TICKET_APPROVAL_NOT_ENABLE.name()));
                list.add(provider);
                continue;
            }

            ApprovalProviderSpi sdkService = PluginManager.findSpi(ApprovalProviderSpi.class, type.name());
            LifeSpiResponse invoke = sdkService.status(ownerUid, new LifeSpiRequest());
            LifeSpiStatus info = JsonUtils.toObj(invoke.getBody(), LifeSpiStatus.class);
            if (!info.isRunning()) {
                provider.put("enable", false);
                provider.put("desc", DmI18nUtils.getMessage(I18nRdpMsgKeys.TICKET_APPROVAL_PLUGIN_STATUS_FAILED.name()));
                list.add(provider);
                continue;
            }

            provider.put("enable", true);
            provider.put("desc", "");
            list.add(provider);
        }

        return list;
    }

    public boolean checkEnableApproval(String ownerUid, ApprovalProvider type) {
        String cfgKey = RdpConvertUtils.convertToApprovalEnableConfigKey(type);
        if (StringUtils.isBlank(cfgKey)) {
            return true;
        }

        DmSysUserConfDO configDO = systemDal.userConfMapper().queryByUidAndConfigName(ownerUid, cfgKey);
        if (configDO == null || StringUtils.isBlank(configDO.getConfigValue())) {
            return false;
        }
        return StringUtils.equalsIgnoreCase(configDO.getConfigValue().trim(), "true");
    }

    public List<RdpApproTemplateVO> listTemplates(String ownerUid, ApprovalType type) {
        if (type != ApprovalType.Internal) {
            List<DmApprovalTemplateDO> templateDOS = approvalDal.templateMapper().listByPrimaryUidAndType(ownerUid, type);
            if (templateDOS.isEmpty()) {
                return refreshTemplates(ownerUid, type);
            } else {
                return templateDOS.stream().map(cacheObj -> {
                    RdpApproTemplateVO vo = new RdpApproTemplateVO();
                    vo.setApproTemplateName(cacheObj.getTemplateName());
                    vo.setTemplateIdentity(cacheObj.getTemplateIdentity());
                    vo.setApproUrl(cacheObj.getApproUrl());
                    return vo;
                }).collect(Collectors.toList());
            }

        } else {
            return Collections.singletonList(ApprovalFlowService.innerTemplate());
        }
    }

    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRED)
    public List<RdpApproTemplateVO> refreshTemplates(String ownerUid, ApprovalType type) {
        List<RdpApproTemplateVO> voList;
        List<DmApprovalTemplateDO> cacheList;
        if (type != ApprovalType.Internal) {
            ApprovalProviderSpi approvalService = PluginManager.findSpi(ApprovalProviderSpi.class, type.name());
            if (approvalService == null) {
                throw new ErrorMessageException(DmI18nUtils.getMessage(I18nRdpMsgKeys.TICKET_APPROVAL_NOT_SUPPORT.name(), type));
            }

            List<ApprovalTemplate> templates = approvalService.getTemplates(ownerUid);
            cacheList = templates.stream().map(template -> {
                DmApprovalTemplateDO templateDO = new DmApprovalTemplateDO();
                templateDO.setPrimaryUid(ownerUid);
                templateDO.setApprovalType(type);
                templateDO.setTemplateName(template.getApproTemplateName());
                templateDO.setTemplateIdentity(template.getTemplateIdentity());
                templateDO.setApproUrl(template.getApproUrl());
                return templateDO;
            }).collect(Collectors.toList());

            voList = cacheList.stream().map(cacheObj -> {
                RdpApproTemplateVO vo = new RdpApproTemplateVO();
                vo.setApproTemplateName(cacheObj.getTemplateName());
                vo.setTemplateIdentity(cacheObj.getTemplateIdentity());
                vo.setApproUrl(cacheObj.getApproUrl());
                return vo;
            }).collect(Collectors.toList());

        } else {
            RdpApproTemplateVO vo = ApprovalFlowService.innerTemplate();
            voList = Collections.singletonList(vo);

            DmApprovalTemplateDO templateDO = new DmApprovalTemplateDO();
            templateDO.setApproUrl(vo.getApproUrl());
            templateDO.setTemplateName(vo.getApproTemplateName());
            templateDO.setApprovalType(ApprovalType.Internal);
            templateDO.setTemplateIdentity(vo.getTemplateIdentity());
            templateDO.setPrimaryUid(ownerUid);

            cacheList = Collections.singletonList(templateDO);
        }
        approvalDal.templateMapper().deleteByPrimaryUid(ownerUid, type);
        if (CollectionUtils.isNotEmpty(cacheList)) {
            approvalDal.templateMapper().insertTemplateBatch(cacheList);
        }
        return voList;
    }

    public void cancelApprovalInst(Long ticketId) {
        DmApprovalDO ticketDO = approvalDal.approvalMapper().queryById(ticketId);
        ApprovalProviderSpi approvalService = PluginManager.findSpi(ApprovalProviderSpi.class, ticketDO.getApproType().name());
        if (approvalService == null) {
            return;
        }

        DmAuthUserDO ticketUser = authDal.userMapper().queryByUid(ticketDO.getOwnerUid());

        ApprovalInstanceCancelInfo cancelInstanceInfo = new ApprovalInstanceCancelInfo();
        cancelInstanceInfo.setTicketUserPhone(ticketUser.getPhone());
        cancelInstanceInfo.setApprovalInstanceIdentity(ticketDO.getApproIdentity());
        cancelInstanceInfo.setApprovalTemplateCode(ticketDO.getApproTemplateIdentity());
        if (StringUtils.isNotEmpty(ticketDO.getApproIdentity())) {
            approvalService.cancelApprovalInst(ticketDO.getPrimaryUid(), cancelInstanceInfo);
        }
    }

    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRED)
    public void refreshApprovalStatus(long ticketId) {
        DmApprovalDO ticket = approvalDal.approvalMapper().queryById(ticketId);
        if (ApprovalStatus.isEndStatus(ticket.getTicketStatus())) {
            return;
        }

        // External orchestration mode: no external SPI to poll, status driven by commands.
        // Defensive early-exit — even if accidentally invoked, do not throw NOT_SUPPORT.
        if (ticket.getApproType() == ApprovalType.External) {
            return;
        }

        try {
            ApprovalProviderSpi approvalService = PluginManager.findSpi(ApprovalProviderSpi.class, ticket.getApproType().name());
            if (approvalService == null) {
                throw new ErrorMessageException(DmI18nUtils.getMessage(I18nRdpMsgKeys.TICKET_APPROVAL_NOT_SUPPORT.name(), ticket.getApproType()));
            }
            ApprovalInstanceInfo lastInfo = approvalService.getLastInfo(ticket.getPrimaryUid(), ticket.getApproIdentity());
            ApprovalInstanceStatus status = lastInfo.getStatus();

            DmApprovalProcessDO processDO = approvalDal.processMapper().queryByStage(ticket.getId(), ApprovalStage.APPROVAL);
            for (Map.Entry<String, List<ApprovalActivity>> stringListEntry : lastInfo.getMap().entrySet()) {
                this.approvalDal.activityMapper().updateContext(processDO.getId(), stringListEntry.getKey(), JsonUtils.toJson(stringListEntry.getValue()));
            }

            // Delegate to the extracted three-step state-migration method (meta=null →
            // no guards, no activity synthesis, no externalFlowId, no audit event —
            // behavior is bit-for-bit identical to the former inline switch block).
            applyExternalApprovalResult(ticketId, status, null);

            this.approvalDal.processMapper().updateModified(processDO.getId());
        } catch (ThirdPartyApiException e) {
            if (e.getErrorType() == ThirdPartyApiErrorType.CONNECTION_ERROR) {
                log.error("ticketId：{},refreshTicketStatus net error,message：{}", ticketId, e.getMessage());
            } else {
                throw e;
            }
        }
    }

    /**
     * Three-step approval state migration, extracted from {@link #refreshApprovalStatus}.
     * <p>
     * Mirrors the original switch block (L325-362) bit-for-bit when {@code meta == null}
     * (the DingTalk/Internal callback path). When {@code meta != null} (the open command
     * API path), additionally performs: state guards, flow-consistency guards (ADR-012:
     * FLOW_ID_MISMATCH / ROUND_MISMATCH — tolerant, blank/absent values skip; strict tier
     * config-gated and default-off), activity synthesis, externalFlowId persistence (with
     * empty-window observation), and audit event recording.
     * <p>
     * The three steps per status branch:
     * <ol>
     *   <li>updateProcessStatus(ticketId, APPROVAL, FINISH|REJECT|CLOSED, context)</li>
     *   <li>updateApprovalStatus(ticketId, WAIT_CONFIRM|REJECTED|CANCELED, msg) + process cleanup</li>
     *   <li>handler(approBiz).approvalApproved|Rejected|Canceled(ticketId, approBiz, imSender)</li>
     * </ol>
     * <p>
     * Row lock (selectByIdForUpdate) fronts the method for concurrent safety: when the
     * scheduler sweep / detail-page refreshCache / open command race on the same ticket,
     * the row lock serializes their mutations.
     *
     * @param ticketId the dm_approval.id
     * @param status   the external approval instance status (COMPLETED/REFUSE/CANCELED/TERMINATED/FAILED)
     * @param meta     command metadata (null for the DingTalk/Internal callback path;
     *                 non-null for the open command API path with operator/approver/comment/externalFlowId)
     */
    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRED)
    public void applyExternalApprovalResult(long ticketId, ApprovalInstanceStatus status, ExternalCommandMeta meta) {
        DmApprovalDO ticket = approvalDal.approvalMapper().selectByIdForUpdate(ticketId);

        // Guards — only for the command path (meta != null). The callback path
        // (meta == null) already checked isEndStatus/External in refreshApprovalStatus.
        // Every rejection is audited BEFORE the throw (10-11 rejected-command-audit R1):
        // late / duplicate / stale-round callbacks leave a durable EXTERNAL_COMMAND_REJECTED
        // trail; the throw semantics and the zero-state-side-effect contract are unchanged (R2).
        if (meta != null) {
            if (ApprovalStatus.isEndStatus(ticket.getTicketStatus())) {
                appendRejectedCommandEvent(ticket, status, meta, REJECT_REASON_END_STATUS);
                throw new ErrorMessageException(DmI18nUtils.getMessage(
                    I18nRdpMsgKeys.GOV_OPEN_COMMAND_INVALID_STATUS.name(),
                    ticketId, ticket.getTicketStatus(), status.name()));
            }
            if (ticket.getTicketStatus() != ApprovalStatus.WAIT_APPROVAL) {
                appendRejectedCommandEvent(ticket, status, meta, REJECT_REASON_STATUS_MISMATCH);
                throw new ErrorMessageException(DmI18nUtils.getMessage(
                    I18nRdpMsgKeys.GOV_OPEN_COMMAND_INVALID_STATUS.name(),
                    ticketId, ticket.getTicketStatus(), status.name()));
            }
            if (ticket.getApproType() != ApprovalType.External) {
                appendRejectedCommandEvent(ticket, status, meta, REJECT_REASON_CHANNEL_MISMATCH);
                throw new ErrorMessageException(DmI18nUtils.getMessage(
                    I18nRdpMsgKeys.GOV_OPEN_COMMAND_CHANNEL_MISMATCH.name(),
                    ticketId, ticket.getApproType()));
            }

            // Guard 4 — flow consistency (ADR-012 Decision 2, form A): both sides non-blank
            // and different ⇒ the command provably belongs to a DIFFERENT flow (stale F1
            // callback hitting a resubmitted row) → reject + audit, zero state side effects.
            // TOLERANT: either side blank skips the check — a blank command flowId is the
            // frozen §5.4 "ticketId-only minimal call", and a blank appro_identity is the
            // legitimate §10.6 empty window (observed at the backfill point below, never
            // rejected in tolerant mode). Scope stays meta!=null ∧ External (guard 3 above).
            String commandFlowId  = meta.getExternalFlowId();
            String recordedFlowId = ticket.getApproIdentity();
            if (StringUtils.isNotBlank(commandFlowId)) {
                if (StringUtils.isNotBlank(recordedFlowId) && !recordedFlowId.equals(commandFlowId)) {
                    appendRejectedCommandEvent(ticket, status, meta, REJECT_REASON_FLOW_ID_MISMATCH);
                    throw new ErrorMessageException(DmI18nUtils.getMessage(
                        I18nRdpMsgKeys.GOV_OPEN_COMMAND_FLOW_MISMATCH.name(),
                        ticketId, commandFlowId, recordedFlowId));
                }
            } else if (isStrictFlowMode()) {
                // Strict tier (ADR-012 Decision 3 — code in place, config-gated, default
                // unreachable): missing flowId rejects once the bridge upgrade is confirmed
                // and gray-released with integration-test evidence.
                appendRejectedCommandEvent(ticket, status, meta, REJECT_REASON_FLOW_ID_MISSING);
                throw new ErrorMessageException(DmI18nUtils.getMessage(
                    I18nRdpMsgKeys.GOV_OPEN_COMMAND_FLOW_MISSING.name(),
                    ticketId, mapToCommandName(status)));
            }

            // Guard 5 — approval-round binding (ADR-012 Decision 3, form B): a carried
            // externalRound that differs from the row's approval_round proves the command
            // was emitted by an OLD round's flow (identity-empty window included — the round
            // token closes what pure flowId comparison cannot) → reject + audit. Absent →
            // skipped (tolerant-first; old bridges never send the field). A null recorded
            // round (unmigrated row read) is skipped too — never reject on absent evidence.
            Integer externalRound = meta.getExternalRound();
            if (externalRound != null) {
                Integer recordedRound = ticket.getApprovalRound();
                if (recordedRound != null && !externalRound.equals(recordedRound)) {
                    appendRejectedCommandEvent(ticket, status, meta, REJECT_REASON_ROUND_MISMATCH);
                    throw new ErrorMessageException(DmI18nUtils.getMessage(
                        I18nRdpMsgKeys.GOV_OPEN_COMMAND_ROUND_MISMATCH.name(),
                        ticketId, externalRound, recordedRound));
                }
            } else if (isStrictFlowMode()) {
                appendRejectedCommandEvent(ticket, status, meta, REJECT_REASON_ROUND_MISSING);
                throw new ErrorMessageException(DmI18nUtils.getMessage(
                    I18nRdpMsgKeys.GOV_OPEN_COMMAND_ROUND_MISSING.name(),
                    ticketId, mapToCommandName(status)));
            }
        }

        // Activity synthesis — only for the command path when approver/comment is present.
        // activityId = "ext-" + timestamp (globally unique, never collides with SPI-sourced IDs).
        if (meta != null && (StringUtils.isNotBlank(meta.getApprover()) || StringUtils.isNotBlank(meta.getComment()))) {
            String activityId = "ext-" + System.currentTimeMillis();
            ApprovalActivityStatus actStatus = mapToActivityStatus(status);
            Map<String, Object> actContext = new HashMap<>();
            if (StringUtils.isNotBlank(meta.getApprover())) {
                actContext.put("userName", meta.getApprover());
            }
            if (StringUtils.isNotBlank(meta.getComment())) {
                actContext.put("remark", meta.getComment());
            }
            actContext.put("finishTime", new java.util.Date());
            this.approvalStateService.initializeActivity(
                ticketId, ApprovalStage.APPROVAL, activityId,
                "External Approval", 0, actStatus != null ? actStatus.name() : null,
                JsonUtils.toJson(actContext));
        }

        // externalFlowId → approIdentity — only when the current approIdentity is empty
        // (never overwrite an identity set by a prior createApproval or command).
        // ADR-012 Decision 4 — empty-window observation: this write IS the §10.6 "events may
        // arrive before the outbound response" window (and, until guard 4 existed, the stale-flow
        // back-write hole). The write rule is untouched (the dual-source "only when empty"
        // contract stands); the window becomes MEASURABLE — warn log + identityBackfill marker
        // on the success event — so the strict tier can gray-release on real frequency data.
        boolean identityBackfill = false;
        if (meta != null && StringUtils.isNotBlank(meta.getExternalFlowId())
            && StringUtils.isEmpty(ticket.getApproIdentity())) {
            approvalDal.approvalMapper().updateThirdApprovalInfo(ticketId, meta.getExternalFlowId(), null);
            identityBackfill = true;
            log.warn("ticketId={}: inbound command back-filled an EMPTY appro_identity with externalFlowId={}"
                + " — identity-empty-window sample (ADR-012 observation; the outbound flowId backfill"
                + " had not landed first)", ticketId, meta.getExternalFlowId());
        }

        // Three-step state migration (mirrors the former inline switch block).
        switch (status) {
            case CANCELED:
            case TERMINATED: {
                // step1
                this.approvalStateService.updateProcessStatus(ticketId, ApprovalStage.APPROVAL, ApprovalProcessStatus.CLOSED, null);
                // step2
                this.approvalStateService.updateApprovalStatus(ticket.getId(), ApprovalStatus.CANCELED, null);
                this.approvalDal.processMapper().updateNotEndProcessByTicketId(ticketId, ApprovalProcessStatus.CLOSED);
                // step3
                this.approvalHandler(ticket.getApproBiz()).approvalCanceled(ticket.getId(), ticket.getApproBiz(), imSenderService);
                break;
            }
            case COMPLETED: {
                // step1
                this.approvalStateService.updateProcessStatus(ticketId, ApprovalStage.APPROVAL, ApprovalProcessStatus.FINISH, null);
                // step2
                this.approvalHandler(ticket.getApproBiz()).approvalApproved(ticket.getId(), ticket.getApproBiz(), imSenderService);
                break;
            }
            case REFUSE: {
                // step1
                this.approvalStateService.updateProcessStatus(ticketId, ApprovalStage.APPROVAL, ApprovalProcessStatus.REJECT, null);
                // step2
                this.approvalStateService.updateApprovalStatus(ticket.getId(), ApprovalStatus.REJECTED, null);
                this.approvalDal.processMapper().updateNotEndProcessByTicketId(ticketId, ApprovalProcessStatus.REJECT);
                // step3
                this.approvalHandler(ticket.getApproBiz()).approvalRejected(ticket.getId(), ticket.getApproBiz(), imSenderService);
                break;
            }
            case FAILED: {
                // step1
                this.approvalStateService.updateApprovalStatus(ticket.getId(), ApprovalStatus.FAILED, null);
                this.failedTicket(ticket);
                // step2
                this.approvalHandler(ticket.getApproBiz()).approvalFailed(ticket.getId(), ticket.getApproBiz(), imSenderService);
                break;
            }
            default:
                // RUNNING and other non-terminal statuses: no state migration.
                break;
        }

        // Audit event — only for the command path (meta != null).
        // operator = service account uid (NOT SYSTEM); eventData carries via + command metadata.
        if (meta != null) {
            GovEventType eventType = mapToEventType(status);
            if (eventType != null) {
                String fromStatus = ApprovalStatus.WAIT_APPROVAL.name();
                String toStatus = mapToTicketStatus(status);
                appendExternalCommandEvent(ticketId, eventType, fromStatus, toStatus, meta, identityBackfill);
            }
        }
    }

    private ApprovalActivityStatus mapToActivityStatus(ApprovalInstanceStatus status) {
        switch (status) {
            case COMPLETED:
                return ApprovalActivityStatus.COMPLETED;
            case REFUSE:
                return ApprovalActivityStatus.REFUSE;
            case CANCELED:
            case TERMINATED:
                return ApprovalActivityStatus.CANCELED;
            case FAILED:
                return ApprovalActivityStatus.CLOSE;
            default:
                return null;
        }
    }

    private GovEventType mapToEventType(ApprovalInstanceStatus status) {
        switch (status) {
            case COMPLETED:
                return GovEventType.EXTERNAL_APPROVED;
            case REFUSE:
                return GovEventType.EXTERNAL_REJECTED;
            case CANCELED:
            case TERMINATED:
                return GovEventType.EXTERNAL_CANCELED;
            default:
                return null;
        }
    }

    private String mapToTicketStatus(ApprovalInstanceStatus status) {
        switch (status) {
            case COMPLETED:
                return ApprovalStatus.WAIT_CONFIRM.name();
            case REFUSE:
                return ApprovalStatus.REJECTED.name();
            case CANCELED:
            case TERMINATED:
                return ApprovalStatus.CANCELED.name();
            case FAILED:
                return ApprovalStatus.FAILED.name();
            default:
                return null;
        }
    }

    private void appendExternalCommandEvent(long ticketId, GovEventType eventType,
                                            String fromStatus, String toStatus, ExternalCommandMeta meta,
                                            boolean identityBackfill) {
        DmDbChangeEventDO eventDO = new DmDbChangeEventDO();
        eventDO.setTicketId(ticketId);
        eventDO.setEventType(eventType.name());
        eventDO.setFromStatus(fromStatus);
        eventDO.setToStatus(toStatus);
        eventDO.setOperatorUid(meta.getOperatorUid());

        Map<String, Object> data = new HashMap<>();
        data.put("via", "EXTERNAL_COMMAND");
        if (StringUtils.isNotBlank(meta.getApprover())) {
            data.put("approver", meta.getApprover());
        }
        if (StringUtils.isNotBlank(meta.getComment())) {
            data.put("comment", meta.getComment());
        }
        if (StringUtils.isNotBlank(meta.getExternalFlowId())) {
            data.put("externalFlowId", meta.getExternalFlowId());
        }
        if (identityBackfill) {
            // ADR-012 Decision 4 observation marker (additive): the command's flowId landed on
            // an EMPTY appro_identity — the §10.6 identity-empty window made searchable.
            // INBOUND command path only; the outbound CAS backfill is the normal path and
            // never carries this marker.
            data.put("identityBackfill", true);
        }
        eventDO.setEventData(JsonUtils.toJson(data));

        this.dbChangeEventDal.eventMapper().insert(eventDO);
    }

    /**
     * Strict-tier config read (ADR-012 Decision 3 / design M7 — reservation only): true means a
     * three-state command MISSING externalFlowId / externalRound is rejected (FLOW_ID_MISSING /
     * ROUND_MISSING). Default AND read-failure both stay {@link #DEFAULT_STRICT_FLOW_MODE} = false
     * (tolerant) — the strict tier must never activate by accident; flipping it is a gray-release
     * decision requiring bridge-upgrade integration-test evidence (the §10.6 "events may precede
     * the outbound response" boundary collides with strict-by-default).
     */
    private boolean isStrictFlowMode() {
        try {
            Boolean strict = systemDal.fetchSystemConf(RootUserConfig.Fields.govExternalCommandStrictFlow, Boolean.class);
            return strict != null && strict;
        } catch (Exception e) {
            return DEFAULT_STRICT_FLOW_MODE;
        }
    }

    /**
     * Audit trail for a command rejected by the {@link #applyExternalApprovalResult} guards
     * (10-11 rejected-command-audit R1) — before this, a late / duplicate / stale-round callback
     * left ZERO durable trace (the throw preceded any event write; only the approve endpoint logged).
     * <p>
     * Transaction boundary (R2): the guard is about to throw INSIDE the surrounding REQUIRED tx,
     * whose rollback would erase a same-tx insert — so the event rides its OWN REQUIRES_NEW
     * transaction (the DingBridgeOutboundService.inNewTx pattern; programmatic template because a
     * self-invoked @Transactional method would bypass the proxy). The inner tx touches ONLY
     * dm_db_change_event — never the dm_approval row this tx holds under selectByIdForUpdate —
     * so the suspension cannot deadlock, and the insert commits before the throw propagates.
     * Audit failure degrades to a warn log: it must never swallow the rejection (the throw IS
     * the contract) nor add state side effects.
     * <p>
     * Payload discipline: NO credentials / signature parameters — command name, rejection reason,
     * the ticket status that rejected it (+approType on channel mismatch), externalFlowId when
     * carried (+recordedFlowId on FLOW_ID_MISMATCH — both mismatch values are auditable, flowIds
     * are not credentials); caller identity = operator_uid column (same shape as
     * {@link #appendExternalCommandEvent}; ticketId-only like the success events — release-side
     * retrieval goes through dm_prod_release.approval_id → ticketId).
     */
    private void appendRejectedCommandEvent(DmApprovalDO ticket, ApprovalInstanceStatus status,
                                             ExternalCommandMeta meta, String reason) {
        appendRejectedCommandEvent(ticket, mapToCommandName(status), reason, meta);
    }

    /**
     * Command-named core of the rejected-command audit — the SINGLE EXTERNAL_COMMAND_REJECTED
     * write point (10-11 rejected-command-audit R2 discipline; made public by ADR-012 M8/O-C2-1
     * so GovOpenApiServiceImpl's execute/return guard rejections ride the same REQUIRES_NEW
     * mechanism instead of copy-pasting it). Organization note (design M8 discretion): kept on
     * this service rather than extracted into a new component — GovOpenApiServiceImpl already
     * injects ApprovalProviderServiceImpl, the txManager/eventDal wiring lives here, and the
     * reason/command vocabulary constants sit right above; a separate class would only add an
     * injection edge with zero cohesion gain.
     * <p>
     * {@code command} whitelist (eventData.command): approve / reject / cancel (via
     * {@link #mapToCommandName}) + execute / return (GovOpenApiServiceImpl). {@code meta} may be
     * null for callers without command metadata — the event still records command/reason/status.
     */
    public void appendRejectedCommandEvent(DmApprovalDO ticket, String command, String reason,
                                           ExternalCommandMeta meta) {
        try {
            DmDbChangeEventDO eventDO = new DmDbChangeEventDO();
            eventDO.setTicketId(ticket.getId());
            eventDO.setEventType(GovEventType.EXTERNAL_COMMAND_REJECTED.name());
            eventDO.setFromStatus(ticket.getTicketStatus() != null ? ticket.getTicketStatus().name() : null);
            // toStatus stays null — a rejected command NEVER moves the ticket.
            eventDO.setOperatorUid(meta != null ? meta.getOperatorUid() : null);

            Map<String, Object> data = new HashMap<>();
            data.put("via", "EXTERNAL_COMMAND");
            data.put("command", command);
            data.put("reason", reason);
            if (eventDO.getFromStatus() != null) {
                data.put("ticketStatus", eventDO.getFromStatus());
            }
            if (REJECT_REASON_CHANNEL_MISMATCH.equals(reason) && ticket.getApproType() != null) {
                data.put("approType", ticket.getApproType().name());
            }
            if (meta != null && StringUtils.isNotBlank(meta.getExternalFlowId())) {
                data.put("externalFlowId", meta.getExternalFlowId());
            }
            if (REJECT_REASON_FLOW_ID_MISMATCH.equals(reason) && StringUtils.isNotBlank(ticket.getApproIdentity())) {
                // ADR-012 A1: the mismatch is auditable on BOTH sides — the recorded identity
                // next to the carried externalFlowId (flowIds are not credentials).
                data.put("recordedFlowId", ticket.getApproIdentity());
            }
            eventDO.setEventData(JsonUtils.toJson(data));

            TransactionTemplate template = new TransactionTemplate(txManager);
            template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            template.execute(txStatus -> this.dbChangeEventDal.eventMapper().insert(eventDO));
        } catch (Throwable t) {
            log.warn("ticketId={}: external command rejected (reason={}) but the audit event failed to persist",
                ticket.getId(), reason, t);
        }
    }

    /** Open-command name for EXTERNAL_COMMAND_REJECTED eventData (defensive fallback = status name). */
    private String mapToCommandName(ApprovalInstanceStatus status) {
        switch (status) {
            case COMPLETED:
                return "approve";
            case REFUSE:
                return "reject";
            case CANCELED:
            case TERMINATED:
                return "cancel";
            default:
                return status.name().toLowerCase();
        }
    }

    private void failedTicket(DmApprovalDO ticket) {
        List<DmApprovalProcessDO> processList = approvalDal.processMapper().listByTicketId(ticket.getId());
        for (DmApprovalProcessDO processDO : processList) {
            if (processDO.getTicketStage() == ApprovalStage.APPROVAL &&//
                processDO.getProcessStatus() != ApprovalProcessStatus.FINISH) {
                this.approvalStateService.updateProcessStatus(ticket.getId(), processDO.getTicketStage(), ApprovalProcessStatus.FAIL, null);
            } else if (processDO.getTicketStage().ordinal() > ApprovalStage.APPROVAL.ordinal() && //
                       processDO.getProcessStatus() == ApprovalProcessStatus.INIT) {
                this.approvalStateService.updateProcessStatus(ticket.getId(), processDO.getTicketStage(), ApprovalProcessStatus.CLOSED, null);
            }
        }
    }

    private ApprovalHandler approvalHandler(ApprovalBiz approvalBiz) {
        ApprovalHandler approvalHandler = this.approvalHandlers.get(approvalBiz);
        if (approvalHandler == null) {
            throw new IllegalStateException("ApprovalHandler about " + approvalBiz + " does not exist");
        }
        return approvalHandler;
    }

    public DmApprovalTemplateDO checkApprovalAndReturnTemplate(String ownerUid, ApprovalType type, String templateId, Locale locale) {
        if (!this.checkEnableApproval(ownerUid, type.getProviderType())) {
            if (locale == null) {
                throw new ErrorMessageException(DmI18nUtils.getMessage(I18nRdpMsgKeys.TICKET_APPROVAL_TYPE_NOT_ENABLE.name(), type));
            } else {
                throw new ErrorMessageException(DmI18nUtils.getMessage(I18nRdpMsgKeys.TICKET_APPROVAL_TYPE_NOT_ENABLE.name(), locale, type));
            }
        }

        DmApprovalTemplateDO templateDO = this.approvalDal.templateMapper().queryByUidAndTemId(ownerUid, templateId);
        if (templateDO == null) {
            if (locale == null) {
                throw new ErrorMessageException(DmI18nUtils.getMessage(I18nRdpMsgKeys.TICKET_TEMPLATE_NOT_EXISTS.name()));
            } else {
                throw new ErrorMessageException(DmI18nUtils.getMessage(I18nRdpMsgKeys.TICKET_TEMPLATE_NOT_EXISTS.name(), locale));
            }
        } else {
            return templateDO;
        }
    }

    public void addTemplateByUrl(String ownerUid, ApprovalType type, String templateUrl) {
        if (!this.checkEnableApproval(ownerUid, type.getProviderType())) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nRdpMsgKeys.TICKET_APPROVAL_TYPE_NOT_ENABLE.name(), type));
        }
        ApprovalProviderSpi approvalService = PluginManager.findSpi(ApprovalProviderSpi.class, type.name());
        if (approvalService == null) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nRdpMsgKeys.TICKET_APPROVAL_NOT_SUPPORT.name(), type));
        }

        if (type == ApprovalType.Feishu) {
            processFeishu(ownerUid, type, templateUrl); // process feishu
        } else if (type == ApprovalType.Wechat) {
            processWeChat(ownerUid, type, templateUrl); // process Wechat
        } else {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nRdpMsgKeys.TICKET_APPROVAL_NOT_SUPPORT.name(), type));
        }
    }

    private void processFeishu(String ownerUid, ApprovalType type, String templateUrl) {
        //  - like https://www.feishu.cn/approval/admin/createApproval?id=7512448164570939393&definitionCode=CA7EB488-A2CA-4F56-A1E5-B226C9E2479E
        if (templateUrl.indexOf("?") > 0) {
            String params = templateUrl.substring(templateUrl.indexOf("?") + 1);
            Map<String, String> map = StringUtils.toMap(params, "&", "=");
            if (map.containsKey("definitionCode")) {
                addAndRefresh(ownerUid, type, RootUserConfig.Fields.feishuApprovalTemplateList, map.get("definitionCode"));
            }
        }
    }

    private void processWeChat(String ownerUid, ApprovalType type, String templateUrl) {
        //  - like https://work.weixin.qq.com/wework_admin/frame#approval_v2/app/120/C4c5FLb3D23jBPxumAfnUv1mk6YESzMfyaHDiuH6d
        String[] split = StringUtils.split(templateUrl, "/");
        if (split.length > 0) {
            String definitionCode = split[split.length - 1].trim();
            addAndRefresh(ownerUid, type, RootUserConfig.Fields.wechatApprovalTemplateList, definitionCode);
        }
    }

    private void addAndRefresh(String ownerUid, ApprovalType type, String templateListKey, String templateId) {
        DmSysUserConfDO configDO = this.systemDal.userConfMapper().queryByUidAndConfigName(ownerUid, templateListKey);
        Set<String> newList = new LinkedHashSet<>();
        if (configDO != null && StringUtils.isNotBlank(configDO.getConfigValue())) {
            for (String str : configDO.getConfigValue().split(",")) {
                newList.add(str.trim());
            }
        }
        newList.add(templateId.trim());
        String configValue = StringUtils.join(newList, ",");

        if (configDO == null) {
            this.systemDal.userConfMapper().insertUserConfig(ownerUid, templateListKey, configValue);
        } else {
            this.systemDal.userConfMapper().updateUserConfig(ownerUid, templateListKey, configValue);
        }
        this.refreshTemplates(ownerUid, type);
    }

    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRED)
    public void removeTemplateById(String ownerUid, ApprovalType type, String templateId) {
        if (!this.checkEnableApproval(ownerUid, type.getProviderType())) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nRdpMsgKeys.TICKET_APPROVAL_TYPE_NOT_ENABLE.name(), type));
        }
        ApprovalProviderSpi approvalService = PluginManager.findSpi(ApprovalProviderSpi.class, type.name());
        if (approvalService == null) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nRdpMsgKeys.TICKET_APPROVAL_NOT_SUPPORT.name(), type));
        }

        switch (type) {
            case Feishu:
                removeAndRefresh(approvalService, ownerUid, type, RootUserConfig.Fields.feishuApprovalTemplateList, templateId);
                return;
            case Wechat:
                removeAndRefresh(approvalService, ownerUid, type, RootUserConfig.Fields.wechatApprovalTemplateList, templateId);
                return;
            default:
                approvalService.useTemplate(ownerUid, templateId, null);
        }
    }

    private void removeAndRefresh(ApprovalProviderSpi approvalService, String ownerUid, ApprovalType type, String templateListKey, String templateId) {
        DmSysUserConfDO configDO = this.systemDal.userConfMapper().queryByUidAndConfigName(ownerUid, templateListKey);
        if (configDO == null || StringUtils.isBlank(configDO.getConfigValue())) {
            return;
        }

        List<String> newList = new ArrayList<>();
        for (String str : configDO.getConfigValue().split(",")) {
            if (!StringUtils.equals(str.trim(), templateId.trim())) {
                newList.add(str.trim());
            }
        }
        configDO.setConfigValue(StringUtils.join(newList, ","));

        this.systemDal.userConfMapper().updateUserConfig(ownerUid, templateListKey, configDO.getConfigValue());
        this.refreshTemplates(ownerUid, type);
        approvalService.useTemplate(ownerUid, templateId, null);
    }
}
