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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.component.cicd.ImSenderConfig;
import com.clougence.clouddm.console.web.component.cicd.ImSenderService;
import com.clougence.clouddm.console.web.service.governance.GovFailureNotifyService;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.DbChangeGovernDal;
import com.clougence.clouddm.platform.dal.access.ExecutionDal;
import com.clougence.clouddm.platform.dal.access.MonitorDal;
import com.clougence.clouddm.platform.dal.access.SystemDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO;
import com.clougence.clouddm.platform.dal.model.dbchange.GovEventType;
import com.clougence.clouddm.platform.dal.model.execution.AutoExecTaskStatus;
import com.clougence.clouddm.platform.dal.model.execution.DmExecAutoJobDO;
import com.clougence.clouddm.platform.dal.model.execution.DmExecAutoTaskDO;
import com.clougence.clouddm.platform.dal.model.logicaldb.GovRole;
import com.clougence.clouddm.platform.dal.model.monitor.Loglevel;
import com.clougence.clouddm.platform.dal.model.monitor.LogDependBizType;
import com.clougence.clouddm.platform.dal.model.monitor.DmMonBizLogDO;
import com.clougence.clouddm.platform.dal.model.system.DmSysMessengerDO;
import com.clougence.clouddm.sdk.messenger.MsgContent;
import com.clougence.clouddm.sdk.messenger.MsgSendResult;
import com.clougence.clouddm.sdk.messenger.MsgSendType;
import com.clougence.utils.JsonUtils;
import com.clougence.utils.StringUtils;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class GovFailureNotifyServiceImpl implements GovFailureNotifyService {

    private static final int  SQL_SUMMARY_MAX_LENGTH = 200;
    private static final int  ERROR_SUMMARY_MAX_LENGTH = 500;
    private static final String DEEP_LINK_PREFIX = "/ticket/";

    @Resource
    private ApprovalDal               approvalDal;
    @Resource
    private DbChangeGovernDal         dbChangeGovernDal;
    @Resource
    private ExecutionDal              executionDal;
    @Resource
    private MonitorDal               monitorDal;
    @Resource
    private SystemDal                systemDal;
    @Resource
    private ImSenderService           imSenderService;

    @Override
    public void scanAndNotify() {
        DmApprovalMapper mapper = this.approvalDal.approvalMapper();
        List<Long> ticketIds = mapper.listUnFinishTicketIdList();
        for (Long ticketId : ticketIds) {
            try {
                this.notifyOneTicket(ticketId);
            } catch (Exception e) {
                log.error("[GovFailureNotify] error notifying ticket {}", ticketId, e);
            }
        }
    }

    private void notifyOneTicket(long ticketId) {
        DmApprovalDO ticket = this.approvalDal.approvalMapper().queryById(ticketId);
        if (ticket == null) {
            return;
        }

        // Filter: DM_CHANGE + EXEC_FAIL + PRE governance
        if (ticket.getApproBiz() != ApprovalBiz.DM_CHANGE) {
            return;
        }
        if (ticket.getTicketStatus() != ApprovalStatus.EXEC_FAIL) {
            return;
        }
        ApprovalMO mo = parseTicketInfo(ticket.getTicketInfo());
        if (mo == null || !GovRole.PRE.name().equals(mo.getGovRole())) {
            return;
        }

        // Locate failed task
        DmExecAutoJobDO job = this.executionDal.autoJobMapper().queryByDependOnBizId(ticket.getBizId());
        if (job == null) {
            return;
        }
        DmExecAutoTaskDO failedTask = findFailedTask(job.getId());
        if (failedTask == null) {
            return;
        }

        // Idempotent: skip if FAIL_NOTIFIED event already exists for this task bizId
        if (hasFailNotifiedEvent(ticketId, failedTask.getBizId())) {
            return;
        }

        // Fetch error summary from dm_mon_biz_log
        String errorSummary = fetchErrorSummary(failedTask.getBizId());

        // Build and send notification
        String message = buildMessage(ticket, failedTask, errorSummary);
        boolean sendAttempted = this.sendNotification(ticket, message);

        // Record FAIL_NOTIFIED event only when a send was actually attempted (messenger found).
        // If no messenger is configured, skip the event so the next scan retries.
        if (sendAttempted) {
            this.appendFailNotifiedEvent(ticketId, failedTask);
        }
    }

    private DmExecAutoTaskDO findFailedTask(long jobId) {
        List<DmExecAutoTaskDO> tasks = this.executionDal.autoTaskMapper().queryListByJobId(jobId, AutoExecTaskStatus.FAILED);
        if (tasks != null && !tasks.isEmpty()) {
            return tasks.get(0);
        }
        // Also check ROLLBACK (transaction-mode failure)
        tasks = this.executionDal.autoTaskMapper().queryListByJobId(jobId, AutoExecTaskStatus.ROLLBACK);
        if (tasks != null && !tasks.isEmpty()) {
            return tasks.get(0);
        }
        return null;
    }

    private boolean hasFailNotifiedEvent(long ticketId, String taskBizId) {
        List<DmDbChangeEventDO> events = this.dbChangeGovernDal.eventMapper().queryByTicketId(ticketId);
        for (DmDbChangeEventDO event : events) {
            if (!GovEventType.FAIL_NOTIFIED.name().equals(event.getEventType())) {
                continue;
            }
            if (StringUtils.isBlank(event.getEventData())) {
                continue;
            }
            Map<String, Object> data = JsonUtils.toObj(event.getEventData(), HashMap.class);
            Object taskBiz = data.get("taskBizId");
            if (taskBiz != null && taskBizId.equals(String.valueOf(taskBiz))) {
                return true;
            }
        }
        return false;
    }

    private String fetchErrorSummary(String taskBizId) {
        List<DmMonBizLogDO> logs = this.monitorDal.bizLogMapper().queryListByBizIdAndType(taskBizId, LogDependBizType.AUTO_EXEC_TASK);
        for (DmMonBizLogDO logEntry : logs) {
            if (logEntry.getLogLevel() == Loglevel.ERROR) {
                String content = logEntry.getContent();
                if (StringUtils.isNotBlank(content)) {
                    return content.length() > ERROR_SUMMARY_MAX_LENGTH
                        ? content.substring(0, ERROR_SUMMARY_MAX_LENGTH)
                        : content;
                }
            }
        }
        return null;
    }

    private String buildMessage(DmApprovalDO ticket, DmExecAutoTaskDO failedTask, String errorSummary) {
        StringBuilder sb = new StringBuilder();
        sb.append("Governance ticket execution failed\n");
        String title = StringUtils.isNotBlank(ticket.getTicketTitle()) ? ticket.getTicketTitle() : String.valueOf(ticket.getId());
        sb.append("Ticket: #").append(ticket.getId()).append(" (").append(title).append(")\n");
        sb.append("Failed statement: #").append(failedTask.getExecOrder()).append("\n");

        String sqlSummary = failedTask.getExecSql();
        if (sqlSummary != null && sqlSummary.length() > SQL_SUMMARY_MAX_LENGTH) {
            sqlSummary = sqlSummary.substring(0, SQL_SUMMARY_MAX_LENGTH) + "...";
        }
        sb.append("SQL summary: ").append(sqlSummary).append("\n");

        if (StringUtils.isNotBlank(errorSummary)) {
            sb.append("Error: ").append(errorSummary).append("\n");
        }
        sb.append("Detail: ").append(DEEP_LINK_PREFIX).append(ticket.getId());
        return sb.toString();
    }

    private boolean sendNotification(DmApprovalDO ticket, String message) {
        String puid = ticket.getPrimaryUid();
        List<DmSysMessengerDO> messengers = this.systemDal.messengerMapper().queryMessengerByOwner(puid);
        if (messengers == null || messengers.isEmpty()) {
            log.warn("[GovFailureNotify] no IM messenger configured for puid={}, ticket={}", puid, ticket.getId());
            return false;
        }

        for (DmSysMessengerDO messenger : messengers) {
            if (!messenger.isEnable()) {
                continue;
            }
            ImSenderConfig imConfig = ImSenderConfig.builder()
                .imType(messenger.getImType())
                .webhookUrl(messenger.getWebhook())
                .secret(messenger.getSecret())
                .build();

            MsgContent msgContent = new MsgContent();
            msgContent.setMessageId(UUID.randomUUID().toString());
            msgContent.setBody(message);
            msgContent.setType(MsgSendType.Text);

            try {
                MsgSendResult result = this.imSenderService.sendMessage(ticket.getOwnerUid(), imConfig, msgContent);
                if (result != null && !result.isSuccess()) {
                    log.warn("[GovFailureNotify] IM send failed for ticket={}, result={}", ticket.getId(), result);
                }
            } catch (ErrorMessageException e) {
                log.error("[GovFailureNotify] IM provider unavailable for ticket={}, continuing", ticket.getId(), e);
            }
            return true;
        }
        return false;
    }

    private void appendFailNotifiedEvent(long ticketId, DmExecAutoTaskDO failedTask) {
        DmDbChangeEventDO eventDO = new DmDbChangeEventDO();
        eventDO.setTicketId(ticketId);
        eventDO.setEventType(GovEventType.FAIL_NOTIFIED.name());
        eventDO.setFromStatus(ApprovalStatus.EXEC_FAIL.name());
        eventDO.setToStatus(ApprovalStatus.EXEC_FAIL.name());
        eventDO.setOperatorUid("SYSTEM");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("stmtIndex", failedTask.getExecOrder());
        data.put("taskBizId", failedTask.getBizId());
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
