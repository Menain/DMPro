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

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.base.metadata.ds.DataSourceConfig;
import com.clougence.clouddm.console.web.component.analysis.AnalysisRuleOptions;
import com.clougence.clouddm.console.web.component.analysis.QueryAnalysisService;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.component.dsconfig.DmDsConfigService;
import com.clougence.clouddm.console.web.component.detectrule.SecRulesCheckResult;
import com.clougence.clouddm.console.web.component.execute.AutoExecService;
import com.clougence.clouddm.console.web.component.governance.GovSqlHashUtils;
import com.clougence.clouddm.console.web.model.fo.governance.GovCorrectStatementFO;
import com.clougence.clouddm.console.web.model.vo.logicaldb.LogicalDbTarget;
import com.clougence.clouddm.console.web.service.governance.GovCorrectionService;
import com.clougence.clouddm.console.web.service.logicaldb.LogicalDbService;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.DbChangeGovernDal;
import com.clougence.clouddm.platform.dal.access.ExecutionDal;
import com.clougence.clouddm.platform.dal.access.MonitorDal;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeStmtVersionDO;
import com.clougence.clouddm.platform.dal.model.dbchange.GovEventType;
import com.clougence.clouddm.platform.dal.model.dbchange.StmtSource;
import com.clougence.clouddm.platform.dal.model.execution.AutoExecTaskStatus;
import com.clougence.clouddm.platform.dal.model.execution.DmExecAutoJobDO;
import com.clougence.clouddm.platform.dal.model.execution.DmExecAutoTaskDO;
import com.clougence.clouddm.platform.dal.model.logicaldb.GovRole;
import com.clougence.clouddm.platform.dal.model.monitor.Loglevel;
import com.clougence.clouddm.platform.dal.model.monitor.LogDependBizType;
import com.clougence.clouddm.platform.dal.model.monitor.DmMonBizLogDO;
import com.clougence.clouddm.platform.dal.model.secrule.WarnLevel;
import com.clougence.clouddm.sdk.execute.session.QueryArg;
import com.clougence.clouddm.sdk.service.secrules.Requester;
import com.clougence.clouddm.sdk.service.secrules.RuleLevel;
import com.clougence.clouddm.sdk.sql.parser.SplitScript;
import com.clougence.clouddm.sdk.sql.parser.SplitQueryType;
import com.clougence.utils.JsonUtils;
import com.clougence.utils.StringUtils;
import com.clougence.schema.umi.struts.UmiTypes;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class GovCorrectionServiceImpl implements GovCorrectionService {

    private static final RuleLevel[] BLOCK_LEVELS       = { RuleLevel.FAILURE };
    private static final int          FAIL_REASON_MAX_LENGTH = 500;

    @Resource
    private ApprovalDal               approvalDal;
    @Resource
    private DbChangeGovernDal         dbChangeGovernDal;
    @Resource
    private ExecutionDal              executionDal;
    @Resource
    private MonitorDal               monitorDal;
    @Resource
    private LogicalDbService          logicalDbService;
    @Resource
    private DmDsConfigService         dmDsConfigService;
    @Resource
    private QueryAnalysisService      queryAnalysisService;
    @Resource
    private AutoExecService           autoExecService;
    @Resource
    private PlatformTransactionManager txManager;

    @Override
    public long correctStatement(String puid, String uid, GovCorrectStatementFO fo) {
        long ticketId = fo.getTicketId();
        DmApprovalDO ticket = checkTicket(ticketId);

        // Auth triple: submitter + EXEC_FAIL + PRE governance
        if (!Objects.equals(ticket.getOwnerUid(), uid)) {
            throw new ErrorMessageException("Only the ticket submitter can correct statements");
        }
        if (ticket.getTicketStatus() != ApprovalStatus.EXEC_FAIL) {
            throw new ErrorMessageException("Ticket is not in EXEC_FAIL state");
        }
        ApprovalMO mo = parseTicketInfo(ticket.getTicketInfo());
        if (mo == null || !GovRole.PRE.name().equals(mo.getGovRole())) {
            throw new ErrorMessageException("Only PRE governance tickets support statement correction");
        }

        // Locate job
        DmExecAutoJobDO job = executionDal.autoJobMapper().queryByDependOnBizId(ticket.getBizId());
        if (job == null) {
            throw new ErrorMessageException("Execution job not found for ticket " + ticketId);
        }

        // Resolve current versions and check idempotent guard
        List<DmDbChangeStmtVersionDO> stmtVersions = dbChangeGovernDal.stmtVersionMapper().queryByTicketId(ticketId);
        DmDbChangeStmtVersionDO currentVersion = resolveCurrentVersion(stmtVersions, fo.getStmtIndex());
        String newSqlHash = GovSqlHashUtils.hash(fo.getNewSql());

        // Idempotent guard: if max version hash already matches newSql, the version was already written
        // in a previous call (Transaction A succeeded). Skip version insert + audit (already done).
        // Then conditionally complete the remaining steps based on whether replaceTask also succeeded.
        if (currentVersion != null && newSqlHash.equals(currentVersion.getStmtHash())) {
            DmExecAutoTaskDO canceledTask = findCanceledTask(job.getId(), fo.getStmtIndex());
            if (canceledTask != null) {
                // Both version insert + replaceTask completed — just ensure retryJob runs
                autoExecService.retryJob(ticket.getBizId());
                return mo.getLogicalDbId();
            }
            // Version written but replaceTask not done — locate the failed task and do replaceTask only
            DmExecAutoTaskDO failedTask = findFailedTask(job.getId(), fo.getStmtIndex());
            if (failedTask.getStatus() != AutoExecTaskStatus.FAILED && failedTask.getStatus() != AutoExecTaskStatus.ROLLBACK) {
                throw new ErrorMessageException(
                    "Statement " + fo.getStmtIndex() + " is not in FAILED or ROLLBACK state");
            }
            autoExecService.replaceTask(ticket.getBizId(), failedTask.getId(), fo.getNewSql());
            autoExecService.retryJob(ticket.getBizId());
            return mo.getLogicalDbId();
        }

        // Locate failed task by stmt_index (= exec_order)
        DmExecAutoTaskDO failedTask = findFailedTask(job.getId(), fo.getStmtIndex());
        if (failedTask.getStatus() != AutoExecTaskStatus.FAILED && failedTask.getStatus() != AutoExecTaskStatus.ROLLBACK) {
            throw new ErrorMessageException(
                "Statement " + fo.getStmtIndex() + " is not in FAILED or ROLLBACK state");
        }

        // Incremental audit (zero side-effects segment)
        LogicalDbTarget target = logicalDbService.getBinding(ticket.getPrimaryUid(), mo.getLogicalDbId(), GovRole.PRE);
        DataSourceConfig dsConfig = dmDsConfigService.fetchDsConfigFromExists(target.getDsId());
        Map<UmiTypes, Object> levelsParam = dmDsConfigService.parseLevels(buildLevels(ticket, target)).levelsParam();

        AnalysisRuleOptions options = AnalysisRuleOptions.builder()
            .currentUid(uid)
            .dsId(target.getDsId())
            .levels(levelsParam)
            .requester(Requester.TICKET)
            .unsupportedLevel(WarnLevel.FAILURE)
            .build();

        SecRulesCheckResult ruleResult = new SecRulesCheckResult();
        try (StringReader reader = new StringReader(fo.getNewSql());
             Stream<SecRulesCheckResult> results = queryAnalysisService.analysisRulesStream(
                 dsConfig, reader, Collections.<QueryArg>emptyList(), 1, 0, options)) {
            results.forEachOrdered(ruleResult::merge);
        } catch (Exception e) {
            throw new ErrorMessageException("Rule audit failed: " + e.getMessage());
        }
        if (ruleResult.hasAnyTarget(BLOCK_LEVELS)) {
            throw new ErrorMessageException("Statement correction rejected by security rules");
        }

        // Behavior analysis: newSql type must match original stmt type
        boolean newIsDml = isDml(fo.getNewSql(), dsConfig);
        boolean newIsDdl = isDdl(fo.getNewSql(), dsConfig);
        if (!newIsDml && !newIsDdl) {
            throw new ErrorMessageException("Corrected statement must be DDL or DML");
        }
        if (currentVersion != null) {
            boolean origIsDml = isDml(currentVersion.getStmtText(), dsConfig);
            boolean origIsDdl = isDdl(currentVersion.getStmtText(), dsConfig);
            if (newIsDml != origIsDml || newIsDdl != origIsDdl) {
                throw new ErrorMessageException(
                    "Corrected statement type does not match the original statement type");
            }
        }

        // Fetch fail_reason from dm_mon_biz_log (task DO has no error column)
        String failReason = fetchFailReason(failedTask.getBizId());

        // Transaction A: stmt_version insert + CORRECTION event
        int newVersion = (currentVersion != null ? currentVersion.getStmtVersion() : 0) + 1;
        int oldVersion = currentVersion != null ? currentVersion.getStmtVersion() : 0;

        TransactionTemplate transaction = new TransactionTemplate(this.txManager);
        transaction.executeWithoutResult(status -> {
            insertCorrectionStmtVersion(ticketId, fo, newVersion, newSqlHash, failReason, uid);
            appendCorrectionEvent(ticketId, uid, fo.getStmtIndex(), oldVersion, newVersion, fo.getReason());
        });

        // replaceTask (Transaction B — engine side)
        autoExecService.replaceTask(ticket.getBizId(), failedTask.getId(), fo.getNewSql());

        // retryJob — resume execution (failures here don't roll back version+event)
        autoExecService.retryJob(ticket.getBizId());

        return mo.getLogicalDbId();
    }

    // ------- helpers -------

    private DmApprovalDO checkTicket(long ticketId) {
        DmApprovalDO ticket = approvalDal.approvalMapper().queryById(ticketId);
        if (ticket == null) {
            throw new ErrorMessageException("Ticket not found: " + ticketId);
        }
        if (ticket.getApproBiz() != ApprovalBiz.DM_CHANGE) {
            throw new ErrorMessageException("Ticket " + ticketId + " is not a DM_CHANGE ticket");
        }
        return ticket;
    }

    private DmExecAutoTaskDO findFailedTask(long jobId, int stmtIndex) {
        List<DmExecAutoTaskDO> tasks = executionDal.autoTaskMapper().queryListByJobId(jobId, null);
        // Find the non-CANCELED task matching exec_order = stmtIndex
        for (DmExecAutoTaskDO task : tasks) {
            if (task.getExecOrder() == stmtIndex && task.getStatus() != AutoExecTaskStatus.CANCELED) {
                return task;
            }
        }
        throw new ErrorMessageException("No active task found for statement " + stmtIndex);
    }

    private DmExecAutoTaskDO findCanceledTask(long jobId, int stmtIndex) {
        List<DmExecAutoTaskDO> tasks = executionDal.autoTaskMapper().queryListByJobId(jobId, null);
        for (DmExecAutoTaskDO task : tasks) {
            if (task.getExecOrder() == stmtIndex && task.getStatus() == AutoExecTaskStatus.CANCELED) {
                return task;
            }
        }
        return null;
    }

    private static DmDbChangeStmtVersionDO resolveCurrentVersion(List<DmDbChangeStmtVersionDO> rows, int stmtIndex) {
        DmDbChangeStmtVersionDO current = null;
        for (DmDbChangeStmtVersionDO row : rows) {
            if (row.getStmtIndex() != stmtIndex) {
                continue;
            }
            if (current == null || row.getStmtVersion() > current.getStmtVersion()) {
                current = row;
            }
        }
        return current;
    }

    private String fetchFailReason(String taskBizId) {
        List<DmMonBizLogDO> logs = monitorDal.bizLogMapper().queryListByBizIdAndType(taskBizId, LogDependBizType.AUTO_EXEC_TASK);
        for (DmMonBizLogDO logEntry : logs) {
            if (logEntry.getLogLevel() == Loglevel.ERROR) {
                String content = logEntry.getContent();
                if (StringUtils.isNotBlank(content)) {
                    return content.length() > FAIL_REASON_MAX_LENGTH
                        ? content.substring(0, FAIL_REASON_MAX_LENGTH)
                        : content;
                }
            }
        }
        return null;
    }

    private void insertCorrectionStmtVersion(long ticketId, GovCorrectStatementFO fo, int version, String hash, String failReason, String uid) {
        DmDbChangeStmtVersionDO stmtDO = new DmDbChangeStmtVersionDO();
        stmtDO.setTicketId(ticketId);
        stmtDO.setStmtIndex(fo.getStmtIndex());
        stmtDO.setStmtVersion(version);
        stmtDO.setStmtText(fo.getNewSql());
        stmtDO.setStmtHash(hash);
        stmtDO.setSource(StmtSource.CORRECTION.name());
        stmtDO.setFailReason(failReason);
        stmtDO.setOperatorUid(uid);
        dbChangeGovernDal.stmtVersionMapper().insert(stmtDO);
    }

    private void appendCorrectionEvent(long ticketId, String uid, int stmtIndex, int oldVersion, int newVersion, String reason) {
        DmDbChangeEventDO eventDO = new DmDbChangeEventDO();
        eventDO.setTicketId(ticketId);
        eventDO.setEventType(GovEventType.CORRECTION.name());
        eventDO.setFromStatus(ApprovalStatus.EXEC_FAIL.name());
        eventDO.setToStatus(ApprovalStatus.EXEC_FAIL.name());
        eventDO.setOperatorUid(uid);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("stmtIndex", stmtIndex);
        data.put("fromVersion", oldVersion);
        data.put("toVersion", newVersion);
        data.put("reason", reason);
        eventDO.setEventData(JsonUtils.toJson(data));

        dbChangeGovernDal.eventMapper().insert(eventDO);
    }

    private static List<String> buildLevels(DmApprovalDO ticket, LogicalDbTarget target) {
        List<String> levels = new ArrayList<>();
        levels.add(String.valueOf(target.getEnvId()));
        levels.add(String.valueOf(target.getDsId()));
        String path = target.getResPath();
        if (path != null && !path.equals("/")) {
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
                        levels.add(segment);
                    }
                }
            }
        }
        return levels;
    }

    private boolean isDml(String sql, DataSourceConfig dsConfig) {
        SplitScript script = splitSingle(sql, dsConfig);
        if (script == null || script.getType() == null) {
            return false;
        }
        for (SplitQueryType type : script.getType()) {
            if (type == SplitQueryType.INSERT || type == SplitQueryType.UPDATE
                || type == SplitQueryType.DELETE || type == SplitQueryType.MERGE) {
                return true;
            }
        }
        return false;
    }

    private boolean isDdl(String sql, DataSourceConfig dsConfig) {
        SplitScript script = splitSingle(sql, dsConfig);
        if (script == null || script.getType() == null) {
            return false;
        }
        for (SplitQueryType type : script.getType()) {
            if (isDdlType(type)) {
                return true;
            }
        }
        return false;
    }

    private SplitScript splitSingle(String sql, DataSourceConfig dsConfig) {
        try (StringReader reader = new StringReader(sql);
             Stream<SplitScript> stream = queryAnalysisService.analysisSplitStream(
                 dsConfig, reader, Collections.<QueryArg>emptyList(), 1, 0)) {
            return stream.findFirst().orElse(null);
        } catch (Exception e) {
            log.error("Governance SQL split failed during correction audit", e);
            throw new ErrorMessageException("SQL analysis failed: " + e.getMessage());
        }
    }

    private static boolean isDdlType(SplitQueryType type) {
        if (type == SplitQueryType.INSERT || type == SplitQueryType.UPDATE
            || type == SplitQueryType.DELETE || type == SplitQueryType.MERGE) {
            return false;
        }
        if (type == SplitQueryType.ADMIN_PERFORMANCE) {
            return false;
        }
        String name = type.name();
        return name.startsWith("CREATE_")
            || name.startsWith("ALTER_")
            || name.startsWith("DROP_")
            || name.startsWith("RENAME_")
            || name.startsWith("COMMENT_")
            || name.startsWith("TRUNCATE_")
            || name.startsWith("ADD_")
            || name.startsWith("ADMIN_")
            || type == SplitQueryType.GRANT
            || type == SplitQueryType.REVOKE
            || type == SplitQueryType.TRANSFER_PRIVILEGE;
    }

    private static ApprovalMO parseTicketInfo(String ticketInfo) {
        if (StringUtils.isEmpty(ticketInfo)) {
            return null;
        }
        return JsonUtils.toObj(ticketInfo, ApprovalMO.class);
    }
}
