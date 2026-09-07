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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.base.metadata.ds.DataSourceConfig;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.component.auth.DmAuthServiceForBiz;
import com.clougence.clouddm.console.web.component.dsconfig.DmDsConfigService;
import com.clougence.clouddm.console.web.component.governance.GovSplitResult;
import com.clougence.clouddm.console.web.component.governance.GovStmtRow;
import com.clougence.clouddm.console.web.component.governance.GovStmtSplitService;
import com.clougence.clouddm.console.web.model.fo.governance.GovPreSubmitFO;
import com.clougence.clouddm.console.web.model.fo.governance.GovStmtTimelineFO;
import com.clougence.clouddm.console.web.model.fo.ticket.DmAddTicketFO;
import com.clougence.clouddm.console.web.model.vo.governance.StmtTimelineVO;
import com.clougence.clouddm.console.web.model.vo.logicaldb.LogicalDbTarget;
import com.clougence.clouddm.console.web.model.vo.ticket.DmTicketResultVO;
import com.clougence.clouddm.console.web.service.approval.ApprovalControlService;
import com.clougence.clouddm.console.web.service.governance.DbChangeGovernService;
import com.clougence.clouddm.console.web.service.logicaldb.LogicalDbService;
import com.clougence.clouddm.console.web.util.DsResPathObj;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.DbChangeGovernDal;
import com.clougence.clouddm.platform.dal.access.ExecutionDal;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.approval.SqlContentType;
import com.clougence.clouddm.platform.dal.model.dbchange.ChangeType;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeStmtVersionDO;
import com.clougence.clouddm.platform.dal.model.dbchange.GovEventType;
import com.clougence.clouddm.platform.dal.model.dbchange.StmtSource;
import com.clougence.clouddm.platform.dal.model.execution.AutoExecTaskStatus;
import com.clougence.clouddm.platform.dal.model.execution.DmExecAutoJobDO;
import com.clougence.clouddm.platform.dal.model.execution.DmExecAutoTaskDO;
import com.clougence.clouddm.platform.dal.model.logicaldb.GovRole;
import com.clougence.clouddm.sdk.security.auth.AuthKind;
import com.clougence.clouddm.sdk.security.auth.def.SecDataAuthLabel;
import com.clougence.utils.JsonUtils;
import com.clougence.utils.StringUtils;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class DbChangeGovernServiceImpl implements DbChangeGovernService {

    @Resource
    private LogicalDbService       logicalDbService;
    @Resource
    private DmAuthServiceForBiz    dmAuthServiceForBiz;
    @Resource
    private DmDsConfigService      dmDsConfigService;
    @Resource
    private GovStmtSplitService   govStmtSplitService;
    @Resource
    private ApprovalControlService approvalControlService;
    @Resource
    private DbChangeGovernDal     dbChangeGovernDal;
    @Resource
    private ApprovalDal            approvalDal;
    @Resource
    private ExecutionDal           executionDal;
    @Resource
    private PlatformTransactionManager txManager;

    @Override
    public DmTicketResultVO preSubmit(String puid, String uid, GovPreSubmitFO fo) {
        if (fo.getContentType() == SqlContentType.ATTACHMENT) {
            throw new ErrorMessageException("Governance ticket currently only supports inline SQL");
        }

        LogicalDbTarget target = logicalDbService.getBinding(puid, fo.getLogicalDbId(), GovRole.PRE);

        dmAuthServiceForBiz.checkResAuth(
            puid, uid, target.getDsId(),
            new DsResPathObj(target.getResPath()),
            SecDataAuthLabel.DM_DAUTH_TICKET, AuthKind.DataSource);

        DataSourceConfig dsConfig = dmDsConfigService.fetchDsConfigFromExists(target.getDsId());
        GovSplitResult splitResult = govStmtSplitService.split(dsConfig, fo.getSql());

        if (hasDml(splitResult.getChangeType()) && StringUtils.isBlank(fo.getRollbackSql())) {
            throw new ErrorMessageException("Rollback SQL is required when the ticket contains DML statements");
        }

        DmAddTicketFO ticketFO = buildTicketFO(fo, target);

        TransactionTemplate transaction = new TransactionTemplate(this.txManager);
        return transaction.execute(status -> {
            DmTicketResultVO result = approvalControlService.createSqlTicket(puid, uid, ticketFO, ApprovalBiz.DM_CHANGE);
            if (result == null || result.getTicketId() == null) {
                return result;
            }

            long ticketId = result.getTicketId();
            updateTicketInfoWithGovFields(ticketId, fo.getLogicalDbId(), GovRole.PRE.name());
            insertStmtVersions(ticketId, splitResult.getStmts(), uid);
            appendSubmitEvent(ticketId, uid, splitResult.getChangeType(), splitResult.getStmts().size());

            return result;
        });
    }

    private static boolean hasDml(ChangeType changeType) {
        return changeType == ChangeType.DML || changeType == ChangeType.MIXED;
    }

    private static DmAddTicketFO buildTicketFO(GovPreSubmitFO fo, LogicalDbTarget target) {
        DmAddTicketFO ticketFO = new DmAddTicketFO();
        ticketFO.setDbLevels(buildDbLevels(target));
        ticketFO.setRawSql(fo.getSql());
        ticketFO.setRollBackSql(fo.getRollbackSql());
        ticketFO.setContentType(SqlContentType.INLINE);
        ticketFO.setTicketTitle(fo.getTicketTitle());
        ticketFO.setDescription(fo.getDescription());
        ticketFO.setForce(true);
        return ticketFO;
    }

    private static List<String> buildDbLevels(LogicalDbTarget target) {
        List<String> dbLevels = new ArrayList<>();
        dbLevels.add(String.valueOf(target.getEnvId()));
        dbLevels.add(String.valueOf(target.getDsId()));
        String path = target.getResPath();
        if (path == null || path.equals("/")) {
            throw new ErrorMessageException("Governance binding resPath has no segments");
        }
        String trimmed = path;
        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (!trimmed.isEmpty()) {
            for (String segment : trimmed.split("/")) {
                if (!segment.isEmpty()) {
                    dbLevels.add(segment);
                }
            }
        }
        return dbLevels;
    }

    private void updateTicketInfoWithGovFields(long ticketId, Long logicalDbId, String govRole) {
        DmApprovalDO ticket = approvalDal.approvalMapper().selectById(ticketId);
        if (ticket == null) {
            return;
        }
        ApprovalMO mo = StringUtils.isEmpty(ticket.getTicketInfo())
            ? new ApprovalMO()
            : JsonUtils.toObj(ticket.getTicketInfo(), ApprovalMO.class);
        mo.setLogicalDbId(logicalDbId);
        mo.setGovRole(govRole);
        approvalDal.approvalMapper().updateTicketInfo(ticketId, JsonUtils.toJson(mo));
    }

    private void insertStmtVersions(long ticketId, List<GovStmtRow> stmts, String uid) {
        for (GovStmtRow row : stmts) {
            DmDbChangeStmtVersionDO stmtDO = new DmDbChangeStmtVersionDO();
            stmtDO.setTicketId(ticketId);
            stmtDO.setStmtIndex(row.getStmtIndex());
            stmtDO.setStmtVersion(1);
            stmtDO.setStmtText(row.getStmtText());
            stmtDO.setStmtHash(row.getStmtHash());
            stmtDO.setSource(StmtSource.INITIAL.name());
            stmtDO.setOperatorUid(uid);
            dbChangeGovernDal.stmtVersionMapper().insert(stmtDO);
        }
    }

    private void appendSubmitEvent(long ticketId, String uid, ChangeType changeType, int stmtCount) {
        DmDbChangeEventDO eventDO = new DmDbChangeEventDO();
        eventDO.setTicketId(ticketId);
        eventDO.setEventType(GovEventType.SUBMIT.name());
        eventDO.setToStatus(ApprovalStatus.PRE_INIT_WAIT.name());
        eventDO.setOperatorUid(uid);

        Map<String, Object> data = new HashMap<>();
        data.put("changeType", changeType.name());
        data.put("stmtCount", stmtCount);
        eventDO.setEventData(JsonUtils.toJson(data));

        dbChangeGovernDal.eventMapper().insert(eventDO);
    }

    @Override
    public StmtTimelineVO stmtTimeline(String puid, String uid, GovStmtTimelineFO fo) {
        long ticketId = fo.getTicketId();

        // Source 1: stmt_version rows — group by stmt_index, sort by version ascending
        List<DmDbChangeStmtVersionDO> stmtVersions = dbChangeGovernDal.stmtVersionMapper().queryByTicketId(ticketId);
        Map<Integer, List<DmDbChangeStmtVersionDO>> versionGroups = new LinkedHashMap<>();
        for (DmDbChangeStmtVersionDO row : stmtVersions) {
            versionGroups.computeIfAbsent(row.getStmtIndex(), k -> new ArrayList<>()).add(row);
        }
        for (List<DmDbChangeStmtVersionDO> group : versionGroups.values()) {
            group.sort((a, b) -> Integer.compare(a.getStmtVersion(), b.getStmtVersion()));
        }

        // Source 2: task terminal states — resolve via ticket → bizId → job → tasks
        Map<Integer, String> taskStatusByIndex = new LinkedHashMap<>();
        Map<Integer, Integer> correctionCountByIndex = new LinkedHashMap<>();
        DmApprovalDO ticket = approvalDal.approvalMapper().queryById(ticketId);
        if (ticket != null && ticket.getBizId() != null) {
            DmExecAutoJobDO job = executionDal.autoJobMapper().queryByDependOnBizId(ticket.getBizId());
            if (job != null) {
                List<DmExecAutoTaskDO> tasks = executionDal.autoTaskMapper().queryListByJobId(job.getId(), null);
                // Group by exec_order, pick the latest non-CANCELED status per order
                Map<Integer, DmExecAutoTaskDO> latestByOrder = new LinkedHashMap<>();
                Map<Integer, Integer> canceledCountByOrder = new HashMap<>();
                for (DmExecAutoTaskDO task : tasks) {
                    int order = task.getExecOrder();
                    if (task.getStatus() == AutoExecTaskStatus.CANCELED) {
                        canceledCountByOrder.merge(order, 1, Integer::sum);
                    } else {
                        DmExecAutoTaskDO existing = latestByOrder.get(order);
                        if (existing == null || task.getId() > existing.getId()) {
                            latestByOrder.put(order, task);
                        }
                    }
                }
                for (Map.Entry<Integer, DmExecAutoTaskDO> entry : latestByOrder.entrySet()) {
                    taskStatusByIndex.put(entry.getKey(), entry.getValue().getStatus().name());
                }
                for (Map.Entry<Integer, Integer> entry : canceledCountByOrder.entrySet()) {
                    correctionCountByIndex.put(entry.getKey(), entry.getValue());
                }
            }
        }

        // Source 3: CORRECTION events — filter from event list
        List<DmDbChangeEventDO> events = dbChangeGovernDal.eventMapper().queryByTicketId(ticketId);
        Map<Integer, Integer> eventCorrectionCount = new HashMap<>();
        for (DmDbChangeEventDO event : events) {
            if (!GovEventType.CORRECTION.name().equals(event.getEventType())) {
                continue;
            }
            if (StringUtils.isBlank(event.getEventData())) {
                continue;
            }
            Map<String, Object> data = JsonUtils.toObj(event.getEventData(), HashMap.class);
            Object stmtIdx = data.get("stmtIndex");
            if (stmtIdx != null) {
                int idx = Integer.parseInt(String.valueOf(stmtIdx));
                eventCorrectionCount.merge(idx, 1, Integer::sum);
            }
        }

        // Aggregate: one StmtGroup per stmt_index
        List<StmtTimelineVO.StmtGroup> groups = new ArrayList<>();
        for (Map.Entry<Integer, List<DmDbChangeStmtVersionDO>> entry : versionGroups.entrySet()) {
            int stmtIndex = entry.getKey();
            StmtTimelineVO.StmtGroup group = new StmtTimelineVO.StmtGroup();
            group.setStmtIndex(stmtIndex);
            group.setCurrentStatus(taskStatusByIndex.getOrDefault(stmtIndex, "UNKNOWN"));
            group.setCorrectionCount(eventCorrectionCount.getOrDefault(stmtIndex, 0));

            List<StmtTimelineVO.VersionEntry> versions = new ArrayList<>();
            for (DmDbChangeStmtVersionDO row : entry.getValue()) {
                StmtTimelineVO.VersionEntry version = new StmtTimelineVO.VersionEntry();
                version.setVersion(row.getStmtVersion());
                version.setStmtHash(row.getStmtHash());
                version.setSource(row.getSource());
                version.setFailReason(row.getFailReason());
                version.setOperatorUid(row.getOperatorUid());
                version.setGmtCreate(row.getGmtCreate());
                versions.add(version);
            }
            group.setVersions(versions);
            groups.add(group);
        }

        StmtTimelineVO vo = new StmtTimelineVO();
        vo.setGroups(groups);
        return vo;
    }
}
