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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.api.console.autoexec.ErrorStrategy;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.model.fo.ticket.DmAutoExecConfigFO;
import com.clougence.clouddm.console.web.service.approval.ApprovalControlService;
import com.clougence.clouddm.console.web.service.envparam.DmEnvParamService;
import com.clougence.clouddm.console.web.service.governance.GovAutoConfirmService;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.DbChangeGovernDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.dbchange.ChangeType;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangePromotionDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeRevisionDO;
import com.clougence.clouddm.platform.dal.model.dbchange.GovEventType;
import com.clougence.clouddm.platform.dal.model.execution.AutoExecType;
import com.clougence.clouddm.sdk.model.env.EnvParamKeys;
import com.clougence.utils.JsonUtils;
import com.clougence.utils.StringUtils;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * Duty 5: auto-confirm PROD governance tickets when GOV_AUTO_CONFIRM=on (Phase 7, design D9).
 * <p>
 * The confirm path naturally triggers the touchpoint #2 guard — gate-two is always on the path,
 * there is no bypass (§4.5 "自动路径同样必经 Guard+Preflight").
 */
@Slf4j
@Service
public class GovAutoConfirmServiceImpl implements GovAutoConfirmService {

    private static final String SYSTEM_OPERATOR = "SYSTEM";

    @Resource
    private ApprovalDal          approvalDal;
    @Resource
    private DbChangeGovernDal   dbChangeGovernDal;
    @Resource
    private ApprovalControlService approvalControlService;
    @Resource
    private DmEnvParamService    dmEnvParamService;

    @Override
    public void autoConfirmProdTickets() {
        DmApprovalMapper mapper = this.approvalDal.approvalMapper();
        List<Long> ticketIds = mapper.listUnFinishTicketIdList();
        for (Long ticketId : ticketIds) {
            try {
                this.confirmOneTicket(ticketId);
            } catch (Exception e) {
                log.error("[GovAutoConfirm] error confirming ticket {}", ticketId, e);
            }
        }
    }

    private void confirmOneTicket(long ticketId) {
        DmApprovalDO ticket = this.approvalDal.approvalMapper().queryById(ticketId);
        if (ticket == null) {
            return;
        }

        // Filter 1: approBiz == DM_CHANGE
        if (ticket.getApproBiz() != ApprovalBiz.DM_CHANGE) {
            return;
        }

        // Filter 2: ticketInfo.govRole == "PROD"
        ApprovalMO mo = parseTicketInfo(ticket.getTicketInfo());
        if (mo == null || !"PROD".equals(mo.getGovRole())) {
            return;
        }

        // Filter 3: status == WAIT_CONFIRM
        if (ticket.getTicketStatus() != ApprovalStatus.WAIT_CONFIRM) {
            return;
        }

        // Filter 4: promotion.prodEnvId has GOV_AUTO_CONFIRM == "on"
        DmDbChangePromotionDO promotion = this.dbChangeGovernDal.promotionMapper().selectById(mo.getPromotionId());
        if (promotion == null) {
            return;
        }
        String autoConfirm = this.dmEnvParamService.queryParam(
            ticket.getPrimaryUid(), promotion.getProdEnvId(), EnvParamKeys.GOV_AUTO_CONFIRM);
        if (!"on".equals(autoConfirm)) {
            return;
        }

        // Resolve changeType for D15 config routing
        ChangeType changeType = resolveChangeType(mo.getRevisionId());
        DmAutoExecConfigFO config = buildAutoExecConfig(changeType);

        // Confirm via SYSTEM path — triggers touchpoint #2 guard (gate-two mandatory)
        this.approvalControlService.confirmTicketBySystem(ticketId, config);

        // Event: AUTO_CONFIRM
        this.appendAutoConfirmEvent(ticketId, mo.getPromotionId());
    }

    // ------- D15 execution config routing (same value table as Phase 4) -------

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

    private ChangeType resolveChangeType(Long revisionId) {
        DmDbChangeRevisionDO revision = this.dbChangeGovernDal.revisionMapper().selectById(revisionId);
        if (revision == null) {
            throw new ErrorMessageException("Revision not found: " + revisionId);
        }
        return ChangeType.valueOf(revision.getChangeType());
    }

    private void appendAutoConfirmEvent(long ticketId, Long promotionId) {
        DmDbChangeEventDO event = new DmDbChangeEventDO();
        event.setTicketId(ticketId);
        event.setPromotionId(promotionId);
        event.setEventType(GovEventType.AUTO_CONFIRM.name());
        event.setFromStatus(ApprovalStatus.WAIT_CONFIRM.name());
        event.setToStatus(ApprovalStatus.WAIT_EXEC.name());
        event.setOperatorUid(SYSTEM_OPERATOR);

        Map<String, Object> data = new HashMap<>();
        data.put("reason", "GOV_AUTO_CONFIRM=on");
        event.setEventData(JsonUtils.toJson(data));

        this.dbChangeGovernDal.eventMapper().insert(event);
    }

    private static ApprovalMO parseTicketInfo(String ticketInfo) {
        if (StringUtils.isEmpty(ticketInfo)) {
            return null;
        }
        return JsonUtils.toObj(ticketInfo, ApprovalMO.class);
    }
}
