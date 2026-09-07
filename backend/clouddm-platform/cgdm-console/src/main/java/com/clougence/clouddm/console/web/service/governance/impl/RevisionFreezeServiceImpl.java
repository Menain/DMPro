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

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.component.governance.GovSqlHashUtils;
import com.clougence.clouddm.console.web.model.vo.logicaldb.LogicalDbTarget;
import com.clougence.clouddm.console.web.service.governance.RevisionFreezeService;
import com.clougence.clouddm.console.web.service.logicaldb.LogicalDbService;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.DbChangeGovernDal;
import com.clougence.clouddm.platform.dal.access.ExecutionDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangeRevisionMapper;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStage;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalProcessActivityDO;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalProcessDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeRevisionDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeStmtVersionDO;
import com.clougence.clouddm.platform.dal.model.dbchange.GovEventType;
import com.clougence.clouddm.platform.dal.model.dbchange.RevisionSourceType;
import com.clougence.clouddm.platform.dal.model.execution.AutoExecTaskStatus;
import com.clougence.clouddm.platform.dal.model.execution.DmExecAutoJobDO;
import com.clougence.clouddm.platform.dal.model.execution.DmExecAutoTaskDO;
import com.clougence.clouddm.platform.dal.model.logicaldb.GovRole;
import com.clougence.utils.JsonUtils;
import com.clougence.utils.StringUtils;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class RevisionFreezeServiceImpl implements RevisionFreezeService {

    private static final String  SYSTEM_OPERATOR   = "SYSTEM";
    private static final int     REVISION_CODE_RETRY_MAX = 5;

    @Resource
    private ApprovalDal              approvalDal;
    @Resource
    private DbChangeGovernDal       dbChangeGovernDal;
    @Resource
    private ExecutionDal            executionDal;
    @Resource
    private LogicalDbService        logicalDbService;
    @Resource
    private PlatformTransactionManager txManager;

    @Override
    public void freezeFinishedRevisions() {
        DmApprovalMapper mapper = this.approvalDal.approvalMapper();
        List<Long> ticketIds = mapper.listFinishedTicketIdList(ApprovalBiz.DM_CHANGE);
        for (Long ticketId : ticketIds) {
            try {
                this.freezeOneTicket(ticketId);
            } catch (Exception e) {
                log.error("[RevisionFreeze] error freezing ticket {}", ticketId, e);
            }
        }
    }

    private void freezeOneTicket(long ticketId) {
        DmApprovalDO ticket = this.approvalDal.approvalMapper().queryById(ticketId);
        if (ticket == null) {
            return;
        }

        // Filter: governance PRE ticket
        ApprovalMO mo = parseTicketInfo(ticket.getTicketInfo());
        if (mo == null || mo.getGovRole() == null || !GovRole.PRE.name().equals(mo.getGovRole())) {
            return;
        }

        // Idempotent: skip if revision already exists
        DmDbChangeRevisionDO existing = this.dbChangeGovernDal.revisionMapper().queryBySourceTicketId(ticketId);
        if (existing != null) {
            return;
        }

        // Collect manifest data
        List<DmDbChangeStmtVersionDO> stmtVersions = this.dbChangeGovernDal.stmtVersionMapper().queryByTicketId(ticketId);
        Map<Integer, DmDbChangeStmtVersionDO> currentVersions = resolveCurrentVersions(stmtVersions);

        // Check task terminal states
        Map<Integer, String> preExecMap = resolveTaskTerminalStates(ticket.getBizId(), currentVersions.size());

        // Anomaly: any non-SUCCESS task → don't freeze, record event
        if (preExecMap.isEmpty() || preExecMap.values().stream().anyMatch(s -> !"SUCCESS".equals(s))) {
            this.appendFreezeAnomalyEvent(ticketId, preExecMap);
            return;
        }

        // Build manifest
        String manifest = buildStmtManifest(currentVersions, preExecMap);

        // Build audit snapshot
        String auditSnapshot = buildAuditSnapshot(ticketId);

        // Resolve logical db / env from binding
        LogicalDbTarget target = this.logicalDbService.getBinding(ticket.getPrimaryUid(), mo.getLogicalDbId(), GovRole.PRE);

        // Determine change_type from SUBMIT event
        String changeType = resolveChangeType(ticketId);

        // Build revision DO
        DmDbChangeRevisionDO revision = new DmDbChangeRevisionDO();
        revision.setLogicalDbId(mo.getLogicalDbId());
        revision.setEnvId(target.getEnvId());
        revision.setSourceType(RevisionSourceType.PRE_TICKET.name());
        revision.setSourceTicketId(ticketId);
        revision.setChangeType(changeType);
        revision.setSqlText(ticket.getRawSql());
        revision.setRollbackSqlText(ticket.getRollBackSql());
        revision.setSqlHash(GovSqlHashUtils.hash(ticket.getRawSql()));
        revision.setRollbackSqlHash(StringUtils.isBlank(ticket.getRollBackSql()) ? null : GovSqlHashUtils.hash(ticket.getRollBackSql()));
        revision.setStmtManifest(manifest);
        revision.setAuditSnapshot(auditSnapshot);

        // Insert revision + event in transaction (idempotent via UNIQUE(source_ticket_id))
        this.insertRevisionWithRetry(revision, ticketId);
    }

    // ------- manifest construction -------

    private static Map<Integer, DmDbChangeStmtVersionDO> resolveCurrentVersions(List<DmDbChangeStmtVersionDO> rows) {
        Map<Integer, DmDbChangeStmtVersionDO> result = new LinkedHashMap<>();
        for (DmDbChangeStmtVersionDO row : rows) {
            DmDbChangeStmtVersionDO current = result.get(row.getStmtIndex());
            if (current == null || row.getStmtVersion() > current.getStmtVersion()) {
                result.put(row.getStmtIndex(), row);
            }
        }
        return result;
    }

    private static String buildStmtManifest(Map<Integer, DmDbChangeStmtVersionDO> currentVersions, Map<Integer, String> preExecMap) {
        List<Map<String, Object>> manifest = new ArrayList<>();
        for (Map.Entry<Integer, DmDbChangeStmtVersionDO> entry : currentVersions.entrySet()) {
            DmDbChangeStmtVersionDO stmt = entry.getValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("idx", stmt.getStmtIndex());
            item.put("stmt_hash", stmt.getStmtHash());
            item.put("version", stmt.getStmtVersion());
            item.put("pre_exec", preExecMap.getOrDefault(stmt.getStmtIndex(), "UNKNOWN"));
            manifest.add(item);
        }
        return JsonUtils.toJson(manifest);
    }

    // ------- task terminal state resolution -------

    private Map<Integer, String> resolveTaskTerminalStates(String ticketBizId, int expectedCount) {
        Map<Integer, String> result = new LinkedHashMap<>();
        DmExecAutoJobDO job = this.executionDal.autoJobMapper().queryByDependOnBizId(ticketBizId);
        if (job == null) {
            return result;
        }
        List<DmExecAutoTaskDO> tasks = this.executionDal.autoTaskMapper().queryListByJobId(job.getId(), null);
        for (DmExecAutoTaskDO task : tasks) {
            String preExec = task.getStatus() == AutoExecTaskStatus.FINISH ? "SUCCESS" : task.getStatus().name();
            result.put(task.getExecOrder(), preExec);
        }
        return result;
    }

    // ------- audit snapshot construction -------

    private String buildAuditSnapshot(long ticketId) {
        DmApprovalProcessDO process = this.approvalDal.processMapper().queryByStage(ticketId, ApprovalStage.EXPLAIN);
        if (process == null) {
            return null;
        }
        List<DmApprovalProcessActivityDO> activities = this.approvalDal.activityMapper().queryByTicketId(ticketId);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        for (DmApprovalProcessActivityDO activity : activities) {
            if (!process.getId().equals(activity.getProcessId())) {
                continue;
            }
            if (StringUtils.isBlank(activity.getActivityId())) {
                continue;
            }
            if (activity.getActivityId().equals("BEHAVIOR_ANALYSIS")
                || activity.getActivityId().equals("SECURITY_RULE")
                || activity.getActivityId().equals("DML_EXPLAIN")) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("status", activity.getTaskStatus());
                entry.put("context", StringUtils.isBlank(activity.getContext()) ? null : JsonUtils.toObj(activity.getContext(), HashMap.class));
                snapshot.put(activity.getActivityId(), entry);
            }
        }
        return snapshot.isEmpty() ? null : JsonUtils.toJson(snapshot);
    }

    // ------- revision insert with retry on revision_code collision -------

    private void insertRevisionWithRetry(DmDbChangeRevisionDO revision, long ticketId) {
        for (int attempt = 0; attempt < REVISION_CODE_RETRY_MAX; attempt++) {
            revision.setRevisionCode(this.generateRevisionCode());
            try {
                TransactionTemplate transaction = new TransactionTemplate(this.txManager);
                transaction.executeWithoutResult(status -> {
                    this.dbChangeGovernDal.revisionMapper().insert(revision);
                    this.appendFrozenEvent(ticketId, revision.getId(), revision.getRevisionCode());
                });
                return;
            } catch (DuplicateKeyException e) {
                // revision_code collision (concurrent freeze) or UNIQUE(source_ticket_id) collision (re-scan)
                // — both are idempotent outcomes, skip silently.
                DmDbChangeRevisionDO existing = this.dbChangeGovernDal.revisionMapper().queryBySourceTicketId(ticketId);
                if (existing != null) {
                    return;
                }
                // revision_code collision — retry with next number
                log.warn("[RevisionFreeze] revision_code collision, retrying (attempt {})", attempt + 1);
            }
        }
        log.error("[RevisionFreeze] revision_code collision exhausted retries for ticket {}", ticketId);
    }

    private String generateRevisionCode() {
        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String prefix = "REV-" + dateStr + "-";

        DmDbChangeRevisionMapper revisionMapper = this.dbChangeGovernDal.revisionMapper();
        LambdaQueryWrapper<DmDbChangeRevisionDO> wrapper = new LambdaQueryWrapper<>();
        wrapper.likeRight(DmDbChangeRevisionDO::getRevisionCode, prefix);
        wrapper.orderByDesc(DmDbChangeRevisionDO::getRevisionCode);
        wrapper.last("LIMIT 1");
        DmDbChangeRevisionDO maxRevision = revisionMapper.selectOne(wrapper);

        int nextNum = 1;
        if (maxRevision != null) {
            String maxCode = maxRevision.getRevisionCode();
            String numPart = maxCode.substring(maxCode.lastIndexOf("-") + 1);
            nextNum = Integer.parseInt(numPart) + 1;
        }
        return prefix + String.format("%04d", nextNum);
    }

    // ------- event helpers -------

    private void appendFrozenEvent(long ticketId, long revisionId, String revisionCode) {
        DmDbChangeEventDO eventDO = new DmDbChangeEventDO();
        eventDO.setTicketId(ticketId);
        eventDO.setRevisionId(revisionId);
        eventDO.setEventType(GovEventType.REVISION_FROZEN.name());
        eventDO.setToStatus(null);
        eventDO.setOperatorUid(SYSTEM_OPERATOR);

        Map<String, Object> data = new HashMap<>();
        data.put("revisionCode", revisionCode);
        eventDO.setEventData(JsonUtils.toJson(data));

        this.dbChangeGovernDal.eventMapper().insert(eventDO);
    }

    private void appendFreezeAnomalyEvent(long ticketId, Map<Integer, String> preExecMap) {
        DmDbChangeEventDO eventDO = new DmDbChangeEventDO();
        eventDO.setTicketId(ticketId);
        eventDO.setEventType(GovEventType.FREEZE_ANOMALY.name());
        eventDO.setOperatorUid(SYSTEM_OPERATOR);

        Map<String, Object> data = new HashMap<>();
        data.put("preExec", preExecMap);
        eventDO.setEventData(JsonUtils.toJson(data));

        this.dbChangeGovernDal.eventMapper().insert(eventDO);
    }

    // ------- helpers -------

    private String resolveChangeType(long ticketId) {
        List<DmDbChangeEventDO> events = this.dbChangeGovernDal.eventMapper().queryByTicketId(ticketId);
        for (DmDbChangeEventDO event : events) {
            if (GovEventType.SUBMIT.name().equals(event.getEventType())) {
                Map<String, Object> data = JsonUtils.toObj(event.getEventData(), HashMap.class);
                Object ct = data.get("changeType");
                if (ct != null) {
                    return String.valueOf(ct);
                }
            }
        }
        throw new IllegalStateException("No SUBMIT event found for ticket " + ticketId);
    }

    private static ApprovalMO parseTicketInfo(String ticketInfo) {
        if (StringUtils.isEmpty(ticketInfo)) {
            return null;
        }
        return JsonUtils.toObj(ticketInfo, ApprovalMO.class);
    }
}
