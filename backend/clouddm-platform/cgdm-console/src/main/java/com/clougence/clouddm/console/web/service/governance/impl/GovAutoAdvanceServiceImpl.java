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
package com.clougence.clouddm.console.web.service.governance.impl;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.api.console.autoexec.ErrorStrategy;
import com.clougence.clouddm.console.web.component.approval.ApprovalHandler;
import com.clougence.clouddm.console.web.component.approval.ApprovalStateService;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalStageMO;
import com.clougence.clouddm.console.web.component.cicd.ImSenderService;
import com.clougence.clouddm.console.web.component.execute.AutoExecService;
import com.clougence.clouddm.console.web.global.i18n.DmI18nUtils;
import com.clougence.clouddm.console.web.model.fo.ticket.DmAutoExecConfigFO;
import com.clougence.clouddm.console.web.model.vo.envparam.DmEnvParamTicketDesVO;
import com.clougence.clouddm.console.web.model.vo.logicaldb.LogicalDbTarget;
import com.clougence.clouddm.console.web.service.approval.ApprovalControlService;
import com.clougence.clouddm.console.web.service.envparam.DmEnvParamService;
import com.clougence.clouddm.console.web.service.governance.GovAutoAdvanceService;
import com.clougence.clouddm.console.web.service.logicaldb.LogicalDbService;
import com.clougence.clouddm.console.web.util.DmTeamUtils;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.DbChangeGovernDal;
import com.clougence.clouddm.platform.dal.access.TicketDbStmtDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalProcessStatus;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStage;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalType;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.dbchange.ChangeType;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO;
import com.clougence.clouddm.platform.dal.model.dbchange.GovEventType;
import com.clougence.clouddm.platform.dal.model.execution.AutoExecType;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;
import com.clougence.clouddm.platform.dal.model.logicaldb.GovRole;
import com.clougence.utils.JsonUtils;
import com.clougence.utils.StringUtils;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class GovAutoAdvanceServiceImpl implements GovAutoAdvanceService {

    private static final String SYSTEM_OPERATOR = "SYSTEM";

    @Resource
    private ApprovalDal                approvalDal;
    @Resource
    private DbChangeGovernDal         dbChangeGovernDal;
    @Resource
    private LogicalDbService          logicalDbService;
    @Resource
    private DmEnvParamService         dmEnvParamService;
    @Resource
    private ApprovalStateService      approvalStateService;
    @Resource
    private ApprovalControlService    approvalControlService;
    @Resource
    private ImSenderService           imSenderService;
    @Resource
    private TicketDbStmtDal           ticketDbStmtDal;
    @Resource
    private AutoExecService           autoExecService;

    private final Map<ApprovalBiz, ApprovalHandler> approvalHandlers;

    public GovAutoAdvanceServiceImpl(List<ApprovalHandler> handlers) {
        this.approvalHandlers = new EnumMap<>(ApprovalBiz.class);
        for (ApprovalHandler handler : handlers) {
            this.approvalHandlers.putIfAbsent(handler.handleType(), handler);
        }
    }

    @Override
    public void advancePreTickets() {
        DmApprovalMapper mapper = this.approvalDal.approvalMapper();
        List<Long> ticketIds = mapper.listUnFinishTicketIdList();
        for (Long ticketId : ticketIds) {
            try {
                this.advanceOneTicket(ticketId);
            } catch (Exception e) {
                log.error("[GovAutoAdvance] error advancing ticket {}", ticketId, e);
            }
        }
    }

    private void advanceOneTicket(long ticketId) {
        DmApprovalDO ticket = this.approvalDal.approvalMapper().queryById(ticketId);
        if (ticket == null) {
            return;
        }

        // Filter 1: approBiz == DM_CHANGE
        if (ticket.getApproBiz() != ApprovalBiz.DM_CHANGE) {
            return;
        }

        // Filter 2: ticketInfo.ticketType == "PRE_DDL" (v2 only — old tickets without ticketType are skipped)
        ApprovalMO mo = parseTicketInfo(ticket.getTicketInfo());
        if (mo == null || mo.getTicketType() == null) {
            return;
        }
        if (!"PRE_DDL".equals(mo.getTicketType())) {
            return;
        }

        // Filter 3: status == WAIT_APPROVAL
        if (ticket.getTicketStatus() != ApprovalStatus.WAIT_APPROVAL) {
            return;
        }

        // All conditions met — do SYSTEM auto-approve + auto-confirm + per-group createGroupJob
        ChangeType changeType = this.resolveChangeType(ticketId);
        this.systemAutoApprove(ticket, changeType);
        this.systemAutoConfirmForV2(ticket, changeType);
    }

    // ------- SYSTEM auto-approve (WAIT_APPROVAL → WAIT_CONFIRM) -------

    private void systemAutoApprove(DmApprovalDO ticket, ChangeType changeType) {
        long ticketId = ticket.getId();

        // step1: APPROVAL stage → FINISH with SYSTEM operator context
        ApprovalStageMO stageMO = new ApprovalStageMO();
        stageMO.setExecUserName(Collections.singletonList(SYSTEM_OPERATOR));
        stageMO.setExecMsg("SYSTEM auto-approve (governance promoter)");
        this.approvalStateService.updateProcessStatus(
            ticketId, ApprovalStage.APPROVAL, ApprovalProcessStatus.FINISH, JsonUtils.toJson(stageMO));

        // step2: handler.approvalApproved → WAIT_CONFIRM
        ApprovalHandler handler = this.approvalHandlers.get(ApprovalBiz.DM_CHANGE);
        if (handler == null) {
            throw new IllegalStateException("ApprovalHandler for DM_CHANGE not found");
        }
        handler.approvalApproved(ticketId, ApprovalBiz.DM_CHANGE, this.imSenderService);

        // event
        this.appendEvent(ticketId, GovEventType.SYSTEM_APPROVE,
            ApprovalStatus.WAIT_APPROVAL.name(), ApprovalStatus.WAIT_CONFIRM.name(), changeType);
    }

    // ------- SYSTEM auto-confirm (WAIT_CONFIRM → WAIT_EXEC) -------

    private void systemAutoConfirm(DmApprovalDO ticket, ChangeType changeType) {
        long ticketId = ticket.getId();
        DmAutoExecConfigFO config = buildAutoExecConfig(changeType);
        this.approvalControlService.confirmTicketBySystem(ticketId, config);
        this.appendEvent(ticketId, GovEventType.SYSTEM_CONFIRM,
            ApprovalStatus.WAIT_CONFIRM.name(), ApprovalStatus.WAIT_EXEC.name(), changeType);
    }

    // ------- V2 SYSTEM auto-confirm (WAIT_CONFIRM → WAIT_EXEC, no old-style job, then per-group createGroupJob) -------

    private void systemAutoConfirmForV2(DmApprovalDO ticket, ChangeType changeType) {
        long ticketId = ticket.getId();
        DmAutoExecConfigFO config = buildAutoExecConfig(changeType);

        // State transition only (no old-style single job creation)
        this.approvalControlService.confirmTicketBySystemForV2(ticketId, config);
        this.appendEvent(ticketId, GovEventType.SYSTEM_CONFIRM,
            ApprovalStatus.WAIT_CONFIRM.name(), ApprovalStatus.WAIT_EXEC.name(), changeType);

        // Per-group createGroupJob
        List<DmTicketDbStmtDO> groups = this.ticketDbStmtDal.stmtMapper().queryByTicketId(ticketId);
        Locale locale = DmI18nUtils.getLocale();
        String languageTag = locale.toLanguageTag();

        for (DmTicketDbStmtDO group : groups) {
            String execStatus = group.getExecStatus();
            if (!"PENDING".equals(execStatus) && !"FAILED".equals(execStatus)) {
                continue; // skip non-eligible groups
            }
            try {
                String jobBizId = DmTeamUtils.nextExecJobBizId();
                this.autoExecService.createGroupJob(
                    group, jobBizId,
                    config.isEnableTransactional(),
                    config.getErrorStrategy(),
                    languageTag,
                    SYSTEM_OPERATOR);
                this.autoExecService.startJob(jobBizId, SYSTEM_OPERATOR);
            } catch (Exception e) {
                log.error("[GovAutoAdvance] Failed to create group job for ticket {}, group {}", ticketId, group.getId(), e);
            }
        }
    }

    // ------- D15 execution config routing (design D8 / B4) -------

    private static DmAutoExecConfigFO buildAutoExecConfig(ChangeType changeType) {
        DmAutoExecConfigFO config = new DmAutoExecConfigFO();
        config.setAutoExecType(AutoExecType.IMMEDIATE);
        config.setErrorStrategy(ErrorStrategy.NONE);
        if (changeType == ChangeType.DML) {
            config.setEnableTransactional(true);
        } else {
            config.setEnableTransactional(false);
        }
        config.setRetryWaitTime(null);
        config.setRetryCount(null);
        config.setExecTime(null);
        config.setSnapshot(false);
        return config;
    }

    // ------- helpers -------

    private ChangeType resolveChangeType(long ticketId) {
        List<DmDbChangeEventDO> events = this.dbChangeGovernDal.eventMapper().queryByTicketId(ticketId);
        for (DmDbChangeEventDO event : events) {
            if (GovEventType.SUBMIT.name().equals(event.getEventType())) {
                Map<String, Object> data = JsonUtils.toObj(event.getEventData(), HashMap.class);
                Object ct = data.get("changeType");
                if (ct != null) {
                    return ChangeType.valueOf(String.valueOf(ct));
                }
            }
        }
        throw new ErrorMessageException("No SUBMIT event found for ticket " + ticketId);
    }

    private void appendEvent(long ticketId, GovEventType eventType, String fromStatus, String toStatus, ChangeType changeType) {
        DmDbChangeEventDO eventDO = new DmDbChangeEventDO();
        eventDO.setTicketId(ticketId);
        eventDO.setEventType(eventType.name());
        eventDO.setFromStatus(fromStatus);
        eventDO.setToStatus(toStatus);
        eventDO.setOperatorUid(SYSTEM_OPERATOR);

        Map<String, Object> data = new HashMap<>();
        data.put("changeType", changeType.name());
        eventDO.setEventData(JsonUtils.toJson(data));

        this.dbChangeGovernDal.eventMapper().insert(eventDO);
    }

    private static ApprovalMO parseTicketInfo(String ticketInfo) {
        if (StringUtils.isEmpty(ticketInfo)) {
            return null;
        }
        return JsonUtils.toObj(ticketInfo, ApprovalMO.class);
    }
}
