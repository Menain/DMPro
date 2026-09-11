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
package com.clougence.clouddm.console.web.service.govticket.impl;

import java.io.StringReader;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.base.metadata.ds.DataSourceConfig;
import com.clougence.clouddm.console.web.component.analysis.AnalysisRuleOptions;
import com.clougence.clouddm.console.web.component.analysis.QueryAnalysisService;
import com.clougence.clouddm.console.web.component.approval.ApprovalFlowService;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.component.detectrule.SecRulesCheckResult;
import com.clougence.clouddm.console.web.component.dsconfig.DmDsConfigService;
import com.clougence.clouddm.console.web.component.dsconfig.mode.DsLevels;
import com.clougence.clouddm.console.web.component.governance.impl.GovStmtSplitServiceImpl;
import com.clougence.clouddm.console.web.global.i18n.DmI18nUtils;
import com.clougence.clouddm.console.web.global.i18n.I18nDmMsgKeys;
import com.clougence.clouddm.console.web.model.fo.govticket.GovTicketV2CheckFO;
import com.clougence.clouddm.console.web.model.fo.govticket.GovTicketV2SubmitFO;
import com.clougence.clouddm.console.web.model.vo.dbpair.DbPairVO;
import com.clougence.clouddm.console.web.model.vo.dbpair.DbServiceVO;
import com.clougence.clouddm.console.web.model.vo.govticket.GovTicketV2CheckVO;
import com.clougence.clouddm.console.web.model.vo.govticket.GovTicketV2GroupVO;
import com.clougence.clouddm.console.web.model.vo.govticket.GovTicketV2SubmitVO;
import com.clougence.clouddm.console.web.service.dbpair.DbPairService;
import com.clougence.clouddm.console.web.service.envparam.DmEnvParamService;
import com.clougence.clouddm.console.web.service.govticket.GovTicketV2Service;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.DataSourceDal;
import com.clougence.clouddm.platform.dal.access.DbChangeEventDal;
import com.clougence.clouddm.platform.dal.access.NamingDao;
import com.clougence.clouddm.platform.dal.access.SystemDal;
import com.clougence.clouddm.platform.dal.access.TicketDbStmtDal;
import com.clougence.clouddm.platform.dal.mapper.datasource.DmDsMapper;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalFeature;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalType;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.datasource.DmDsDO;
import com.clougence.clouddm.platform.dal.model.dbchange.ChangeType;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO;
import com.clougence.clouddm.platform.dal.model.dbchange.GovEventType;
import com.clougence.clouddm.platform.dal.model.dbpair.DmDbPairDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;
import com.clougence.clouddm.platform.dal.model.secrule.WarnLevel;
import com.clougence.clouddm.platform.dal.model.system.DmSysEnvDO;
import com.clougence.clouddm.sdk.execute.session.QueryArg;
import com.clougence.clouddm.sdk.service.secrules.Requester;
import com.clougence.clouddm.sdk.service.secrules.RuleLevel;
import com.clougence.clouddm.sdk.sql.parser.SplitScript;
import com.clougence.clouddm.sdk.sql.parser.SplitQueryType;
import com.clougence.schema.umi.struts.UmiTypes;
import com.clougence.utils.CollectionUtils;
import com.clougence.utils.JsonUtils;
import com.clougence.utils.StringUtils;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class GovTicketV2ServiceImpl implements GovTicketV2Service {

    private static final String STATUS_ENABLED    = "ENABLED";
    private static final String EXEC_STATUS_PENDING  = "PENDING";
    private static final String EXEC_STATUS_EXECUTING = "EXECUTING";
    private static final String EXEC_STATUS_SUCCESS  = "SUCCESS";
    private static final String EXEC_STATUS_FAILED  = "FAILED";
    private static final String TICKET_TYPE_PRE_DDL  = "PRE_DDL";
    private static final String TICKET_TYPE_PROD_DML = "PROD_DML";
    private static final String ENGINE_V2           = "v2";

    private static final RuleLevel[] BLOCK_LEVELS = { RuleLevel.FAILURE, RuleLevel.TICKET };

    @Resource
    private DbPairService       dbPairService;
    @Resource
    private TicketDbStmtDal     ticketDbStmtDal;
    @Resource
    private DataSourceDal       dsDal;
    @Resource
    private SystemDal          systemDal;
    @Resource
    private DmDsConfigService   dmDsConfigService;
    @Resource
    private QueryAnalysisService queryAnalysisService;
    @Resource
    private DmEnvParamService   dmEnvParamService;
    @Resource
    private ApprovalDal         approvalDal;
    @Resource
    private DbChangeEventDal   dbChangeEventDal;
    @Resource
    private ApprovalFlowService approvalFlowService;
    @Resource
    private NamingDao           namingDao;
    @Resource
    private com.clougence.clouddm.console.web.component.execute.AutoExecService autoExecService;
    @Resource
    private org.springframework.transaction.PlatformTransactionManager txManager;
    @Resource
    private com.clougence.clouddm.platform.dal.access.ExecutionDal execDal;

    // ==================== check ====================

    @Override
    public GovTicketV2CheckVO check(String puid, String uid, GovTicketV2CheckFO fo) {
        String ticketType = fo.getTicketType();
        validateTicketType(ticketType);

        GovTicketV2CheckVO vo = new GovTicketV2CheckVO();
        vo.setTicketType(ticketType);
        List<GovTicketV2CheckVO.GroupResult> groupResults = new ArrayList<>();

        for (GovTicketV2CheckFO.GroupInput gi : fo.getGroups()) {
            groupResults.add(runPrecheck(puid, uid, ticketType, gi.getPairId(), gi.getSqlContent()));
        }

        vo.setGroups(groupResults);
        return vo;
    }

    // ==================== submit ====================

    @Override
    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRED)
    public GovTicketV2SubmitVO submit(String puid, String uid, GovTicketV2SubmitFO fo) {
        String ticketType = fo.getTicketType();
        validateTicketType(ticketType);

        // Authoritative re-run precheck (blocking — any group failure rejects the whole ticket)
        List<GroupPrecheck> prechecks = new ArrayList<>();
        for (GovTicketV2SubmitFO.GroupInput gi : fo.getGroups()) {
            GovTicketV2CheckVO.GroupResult gr = runPrecheck(puid, uid, ticketType, gi.getPairId(), gi.getSqlContent());
            if ("FAIL".equals(gr.getCheckStatus())) {
                throw new ErrorMessageException(DmI18nUtils.getMessage(
                    I18nDmMsgKeys.GOV_TICKET_V2_PRECHECK_FAILED.name(), gr.getDbName(), gr.getErrorMessage()));
            }
            prechecks.add(new GroupPrecheck(gi.getPairId(), gi.getSqlContent(), gr, buildPrecheckResultJson(gr)));
        }

        // Resolve first group for display-compat fields
        GroupPrecheck first = prechecks.get(0);
        DmDbPairDO firstPair = resolvePair(first.pairId);
        long firstDsId = resolveDsId(ticketType, firstPair);
        DmDsDO firstDs = dsDal.dsMapper().queryDsIdentityById(firstDsId);
        DmSysEnvDO envDO = systemDal.envMapper().queryByEnvID(puid, firstDs.getDsEnvId());

        // Build ticketInfo JSON
        ApprovalMO mo = new ApprovalMO();
        mo.setTicketType(ticketType);
        mo.setServiceId(fo.getServiceId());
        mo.setPairIds(prechecks.stream().map(g -> g.pairId).collect(Collectors.toList()));

        // Determine approval type
        ApprovalType approvalType = ApprovalType.Internal;
        if (TICKET_TYPE_PROD_DML.equals(ticketType)) {
            var ticketConfig = dmEnvParamService.querySqlTicketInfoParam(puid, firstDs.getDsEnvId());
            if (ticketConfig != null && StringUtils.isNotBlank(ticketConfig.getType())) {
                approvalType = ApprovalType.valueOf(ticketConfig.getType());
            }
        }

        // Create dm_approval
        String bizId = namingDao.genApprovalBizId();
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setBizId(bizId);
        ticket.setOwnerUid(uid);
        ticket.setPrimaryUid(puid);
        ticket.setBindDsId(firstDsId);
        ticket.setTargetInfo("/" + firstDs.getInstanceId() + "/" + resolveDbName(ticketType, firstPair));
        ticket.setDescription(fo.getDescription());
        ticket.setTicketTitle(fo.getTicketTitle());
        ticket.setTicketStatus(ApprovalStatus.WAIT_APPROVAL);
        ticket.setApproBiz(ApprovalBiz.DM_CHANGE);
        ticket.setApproType(approvalType);
        ticket.setEnvName(envDO != null ? envDO.getEnvName() : null);
        ticket.setContentType(com.clougence.clouddm.platform.dal.model.approval.SqlContentType.INLINE);
        ticket.setFeatures(List.of(ApprovalFeature.values()));
        ticket.setRawSql(first.sqlContent);
        ticket.setTicketInfo(JsonUtils.toJson(mo));
        ticket.setLevels(List.of(firstDs.getDsEnvId().toString(), String.valueOf(firstDsId), resolveDbName(ticketType, firstPair)));
        ticket.setStatusMessage("Waiting for approval");

        approvalDal.approvalMapper().insert(ticket);

        // Create approval process
        approvalFlowService.createProcess(ticket.getId(), ApprovalBiz.DM_CHANGE, true);

        // Insert statement groups
        for (GroupPrecheck pc : prechecks) {
            DmDbPairDO pair = resolvePair(pc.pairId);
            DmTicketDbStmtDO stmt = new DmTicketDbStmtDO();
            stmt.setTicketId(ticket.getId());
            stmt.setPairId(pc.pairId);
            stmt.setDsId(resolveDsId(ticketType, pair));
            stmt.setDbName(resolveDbName(ticketType, pair));
            stmt.setSqlContent(pc.sqlContent);
            stmt.setPrecheckResult(pc.precheckResultJson);
            stmt.setExecStatus(EXEC_STATUS_PENDING);
            ticketDbStmtDal.stmtMapper().insert(stmt);
        }

        // Append submit event
        appendSubmitEvent(ticket.getId(), uid, ticketType, prechecks.size());

        GovTicketV2SubmitVO result = new GovTicketV2SubmitVO();
        result.setTicketId(ticket.getId());
        result.setTicketType(ticketType);
        return result;
    }

    // ==================== retryGroup ====================

    @Override
    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRED)
    public void retryGroup(String puid, String uid, long groupId) {
        DmTicketDbStmtDO group = ticketDbStmtDal.stmtMapper().queryById(groupId);
        if (group == null) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.GOV_TICKET_V2_GROUP_NOT_FOUND.name(), groupId));
        }
        if (!EXEC_STATUS_FAILED.equals(group.getExecStatus())
            && !EXEC_STATUS_PENDING.equals(group.getExecStatus())
            && !EXEC_STATUS_EXECUTING.equals(group.getExecStatus())) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.GOV_TICKET_V2_GROUP_NOT_FAILED.name()));
        }
        // Inheritance fix (design §8): for PENDING/EXECUTING, also verify no active job exists
        if (EXEC_STATUS_PENDING.equals(group.getExecStatus()) || EXEC_STATUS_EXECUTING.equals(group.getExecStatus())) {
            var existingJob = this.execDal.autoJobMapper().queryByDependOnGroupId(groupId);
            if (existingJob != null
                && existingJob.getStatus() != com.clougence.clouddm.platform.dal.model.execution.AutoExecJobStatus.FAILED
                && existingJob.getStatus() != com.clougence.clouddm.platform.dal.model.execution.AutoExecJobStatus.TERMINATION) {
                throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.GOV_RELEASE_STMT_JOB_ACTIVE.name()));
            }
        }

        // Load ticket to determine ticket type for exec config + tenant scope check
        DmApprovalDO ticket = approvalDal.approvalMapper().queryById(group.getTicketId());
        if (ticket == null) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.GOV_TICKET_V2_TICKET_NOT_FOUND.name(), group.getTicketId()));
        }
        if (!puid.equals(ticket.getPrimaryUid())) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.GOV_TICKET_V2_TICKET_NOT_FOUND.name(), group.getTicketId()));
        }

        ApprovalMO mo = JsonUtils.toObj(ticket.getTicketInfo(), ApprovalMO.class);
        String ticketType = mo != null ? mo.getTicketType() : null;

        // Determine exec config from ticket type (type purity ensures PRE_DDL=DDL, PROD_DML=DML)
        boolean transactional = TICKET_TYPE_PROD_DML.equals(ticketType);
        com.clougence.clouddm.api.console.autoexec.ErrorStrategy errorStrategy
            = com.clougence.clouddm.api.console.autoexec.ErrorStrategy.NONE;

        // Reset group to PENDING
        ticketDbStmtDal.stmtMapper().updateExecStatus(groupId, EXEC_STATUS_PENDING, null);

        // Rebuild group job
        String jobBizId = com.clougence.clouddm.console.web.util.DmTeamUtils.nextExecJobBizId();
        String languageTag = com.clougence.clouddm.console.web.global.i18n.DmI18nUtils.getLocale().toLanguageTag();
        this.autoExecService.createGroupJob(group, jobBizId, transactional, errorStrategy, languageTag, uid);
        this.autoExecService.startJob(jobBizId, uid);
    }

    // ==================== groupList ====================

    @Override
    public List<GovTicketV2GroupVO> groupList(String puid, String uid, long ticketId) {
        // Tenant scope: verify the ticket belongs to the caller's realm before returning groups.
        DmApprovalDO ticket = this.approvalDal.approvalMapper().queryById(ticketId);
        if (ticket == null || !puid.equals(ticket.getPrimaryUid())) {
            return Collections.emptyList();
        }
        List<DmTicketDbStmtDO> groups = ticketDbStmtDal.stmtMapper().queryByTicketId(ticketId);
        if (CollectionUtils.isEmpty(groups)) {
            return Collections.emptyList();
        }
        List<GovTicketV2GroupVO> result = new ArrayList<>();
        for (DmTicketDbStmtDO g : groups) {
            GovTicketV2GroupVO vo = new GovTicketV2GroupVO();
            vo.setGroupId(g.getId());
            vo.setPairId(g.getPairId());
            vo.setDsId(g.getDsId());
            vo.setDbName(g.getDbName());
            vo.setSqlContent(g.getSqlContent());
            vo.setPrecheckResult(g.getPrecheckResult());
            vo.setExecStatus(g.getExecStatus());
            vo.setExecDetail(g.getExecDetail());
            result.add(vo);
        }
        return result;
    }

    // ==================== availablePairs / availableServices ====================

    @Override
    public List<DbPairVO> availablePairs(String puid, String side) {
        return dbPairService.availablePairs(puid, side);
    }

    @Override
    public List<DbServiceVO> availableServices(String puid) {
        return dbPairService.availableServices(puid);
    }

    // ==================== precheck engine ====================

    private GovTicketV2CheckVO.GroupResult runPrecheck(String puid, String uid, String ticketType, long pairId, String sqlContent) {
        GovTicketV2CheckVO.GroupResult result = new GovTicketV2CheckVO.GroupResult();
        result.setPairId(pairId);

        if (StringUtils.isBlank(sqlContent)) {
            result.setCheckStatus("FAIL");
            result.setErrorMessage("SQL content is empty");
            return result;
        }

        // Resolve pair and target DS
        DmDbPairDO pair = resolvePair(pairId);
        if (pair == null || !STATUS_ENABLED.equals(pair.getStatus())) {
            result.setCheckStatus("FAIL");
            result.setErrorMessage(DmI18nUtils.getMessage(I18nDmMsgKeys.GOV_TICKET_V2_PAIR_NOT_FOUND.name(), pairId));
            return result;
        }
        result.setDbName(resolveDbName(ticketType, pair));

        long dsId = resolveDsId(ticketType, pair);
        DmDsDO dsDO = dsDal.dsMapper().queryDsIdentityById(dsId);
        if (dsDO == null) {
            result.setCheckStatus("FAIL");
            result.setErrorMessage("Datasource not found for dsId: " + dsId);
            return result;
        }

        DataSourceConfig dsConfig = dmDsConfigService.fetchDsConfigFromExists(dsId);

        // Build levels for rules check
        String dbName = resolveDbName(ticketType, pair);
        List<String> levelsList = new ArrayList<>();
        levelsList.add(dsDO.getDsEnvId().toString());
        levelsList.add(String.valueOf(dsId));
        levelsList.add(dbName);
        DsLevels dsLevels = dmDsConfigService.parseLevels(levelsList);
        Map<UmiTypes, Object> levelsParam = dsLevels.levelsParam();

        // Step 1: SQL split + type classification
        List<SplitScript> scripts;
        try (StringReader reader = new StringReader(sqlContent);
             Stream<SplitScript> stream = queryAnalysisService.analysisSplitStream(
                 dsConfig, reader, Collections.emptyList(), 1, 0)) {
            scripts = stream.toList();
        } catch (Exception e) {
            log.error("[GovTicketV2] SQL split failed for pair {}", pairId, e);
            result.setCheckStatus("FAIL");
            result.setErrorMessage("SQL split failed: " + e.getMessage());
            return result;
        }

        if (scripts.isEmpty()) {
            result.setCheckStatus("FAIL");
            result.setErrorMessage("No valid SQL statements found");
            return result;
        }

        boolean hasDdl = false;
        boolean hasDml = false;
        List<GovTicketV2CheckVO.StmtPreview> stmtPreviews = new ArrayList<>();
        for (SplitScript script : scripts) {
            Set<SplitQueryType> types = script.getType();
            boolean stmtIsDdl = false;
            boolean stmtIsDml = false;
            if (types != null) {
                for (SplitQueryType type : types) {
                    if (GovStmtSplitServiceImpl.isDmlType(type)) {
                        stmtIsDml = true;
                    } else if (GovStmtSplitServiceImpl.isDdlType(type)) {
                        stmtIsDdl = true;
                    }
                }
            }
            if (stmtIsDdl) { hasDdl = true; }
            if (stmtIsDml) { hasDml = true; }

            GovTicketV2CheckVO.StmtPreview sp = new GovTicketV2CheckVO.StmtPreview();
            sp.setIndex((int) script.getIndex());
            sp.setType(types != null && !types.isEmpty() ? types.iterator().next().name() : "UNKNOWN");
            String sqlPreview = script.getScript();
            sp.setSql(sqlPreview.length() > 500 ? sqlPreview.substring(0, 500) : sqlPreview);
            stmtPreviews.add(sp);
        }
        result.setStatements(stmtPreviews);

        String changeType;
        if (hasDdl && hasDml) {
            changeType = ChangeType.MIXED.name();
        } else if (hasDdl) {
            changeType = ChangeType.DDL.name();
        } else {
            changeType = ChangeType.DML.name();
        }
        result.setChangeType(changeType);

        // Step 2: Type purity check
        String requiredType = TICKET_TYPE_PRE_DDL.equals(ticketType) ? "DDL" : "DML";
        String forbiddenType = TICKET_TYPE_PRE_DDL.equals(ticketType) ? "DML" : "DDL";
        if (TICKET_TYPE_PRE_DDL.equals(ticketType) && hasDml) {
            result.setCheckStatus("FAIL");
            result.setErrorMessage(DmI18nUtils.getMessage(I18nDmMsgKeys.GOV_TICKET_V2_TYPE_MISMATCH.name(), result.getDbName(), "DML", requiredType));
            return result;
        }
        if (TICKET_TYPE_PROD_DML.equals(ticketType) && hasDdl) {
            result.setCheckStatus("FAIL");
            result.setErrorMessage(DmI18nUtils.getMessage(I18nDmMsgKeys.GOV_TICKET_V2_TYPE_MISMATCH.name(), result.getDbName(), "DDL", requiredType));
            return result;
        }

        // Step 3: Security rules check (blocking semantics — force=false equivalent)
        AnalysisRuleOptions options = AnalysisRuleOptions.builder()
            .currentUid(uid)
            .dsId(dsId)
            .levels(levelsParam)
            .requester(Requester.TICKET)
            .unsupportedLevel(WarnLevel.FAILURE)
            .build();

        SecRulesCheckResult checkResult = new SecRulesCheckResult();
        try (StringReader reader = new StringReader(sqlContent);
             Stream<SecRulesCheckResult> results = queryAnalysisService.analysisRulesStream(
                 dsConfig, reader, Collections.emptyList(), 1, 0, options)) {
            results.forEachOrdered(checkResult::merge);
        } catch (Exception e) {
            log.error("[GovTicketV2] Rules check failed for pair {}", pairId, e);
            result.setCheckStatus("FAIL");
            result.setErrorMessage("Rules check failed: " + e.getMessage());
            return result;
        }

        GovTicketV2CheckVO.RulesCheckSummary rulesSummary = buildRulesSummary(checkResult);
        result.setRulesCheck(rulesSummary);

        // Block if any FAILURE or TICKET level rule hit
        if (checkResult.hasAnyTarget(BLOCK_LEVELS)) {
            result.setCheckStatus("FAIL");
            List<String> failures = new ArrayList<>();
            for (Map.Entry<String, RuleLevel> entry : checkResult.getChecked().entrySet()) {
                if (entry.getValue() == RuleLevel.FAILURE || entry.getValue() == RuleLevel.TICKET) {
                    String msg = checkResult.getMessageMap().get(entry.getKey());
                    failures.add(entry.getKey() + ": " + (msg != null ? msg : entry.getValue()));
                }
            }
            result.setErrorMessage(String.join("; ", failures));
            return result;
        }

        result.setCheckStatus("PASS");
        return result;
    }

    private GovTicketV2CheckVO.RulesCheckSummary buildRulesSummary(SecRulesCheckResult checkResult) {
        GovTicketV2CheckVO.RulesCheckSummary summary = new GovTicketV2CheckVO.RulesCheckSummary();
        Map<String, RuleLevel> checked = checkResult.getChecked();
        if (checked == null || checked.isEmpty()) {
            summary.setChecked(false);
            summary.setMessages(Collections.emptyList());
            return summary;
        }
        summary.setChecked(true);
        List<GovTicketV2CheckVO.RuleMessage> messages = new ArrayList<>();
        checked.forEach((name, level) -> {
            GovTicketV2CheckVO.RuleMessage rm = new GovTicketV2CheckVO.RuleMessage();
            rm.setRule(name);
            rm.setLevel(level.name());
            rm.setMessage(checkResult.getMessageMap().get(name));
            messages.add(rm);
        });
        summary.setMessages(messages);
        return summary;
    }

    private String buildPrecheckResultJson(GovTicketV2CheckVO.GroupResult gr) {
        Map<String, Object> json = new HashMap<>();
        json.put("checkStatus", gr.getCheckStatus());
        json.put("changeType", gr.getChangeType());

        List<Map<String, Object>> stmts = new ArrayList<>();
        if (gr.getStatements() != null) {
            for (GovTicketV2CheckVO.StmtPreview sp : gr.getStatements()) {
                Map<String, Object> s = new HashMap<>();
                s.put("index", sp.getIndex());
                s.put("type", sp.getType());
                s.put("sql", sp.getSql());
                stmts.add(s);
            }
        }
        json.put("statements", stmts);

        Map<String, Object> rules = new HashMap<>();
        GovTicketV2CheckVO.RulesCheckSummary rc = gr.getRulesCheck();
        if (rc != null) {
            rules.put("checked", rc.isChecked());
            List<Map<String, Object>> msgs = new ArrayList<>();
            if (rc.getMessages() != null) {
                for (GovTicketV2CheckVO.RuleMessage rm : rc.getMessages()) {
                    Map<String, Object> m = new HashMap<>();
                    m.put("level", rm.getLevel());
                    m.put("rule", rm.getRule());
                    m.put("message", rm.getMessage());
                    msgs.add(m);
                }
            }
            rules.put("messages", msgs);
        } else {
            rules.put("checked", false);
            rules.put("messages", Collections.emptyList());
        }
        json.put("rulesCheck", rules);

        json.put("checkedAt", OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        json.put("engine", ENGINE_V2);

        if (gr.getErrorMessage() != null) {
            json.put("errorMessage", gr.getErrorMessage());
        }

        return JsonUtils.toJson(json);
    }

    // ==================== helpers ====================

    private void validateTicketType(String ticketType) {
        if (!TICKET_TYPE_PRE_DDL.equals(ticketType) && !TICKET_TYPE_PROD_DML.equals(ticketType)) {
            throw new ErrorMessageException("Invalid ticket type: " + ticketType + ", must be PRE_DDL or PROD_DML");
        }
    }

    private DmDbPairDO resolvePair(long pairId) {
        return dbPairDal.pairMapper().selectById(pairId);
    }

    @Resource
    private com.clougence.clouddm.platform.dal.access.DbPairDal dbPairDal;

    private long resolveDsId(String ticketType, DmDbPairDO pair) {
        if (TICKET_TYPE_PRE_DDL.equals(ticketType)) {
            return pair.getPreDsId();
        }
        return pair.getProdDsId();
    }

    private String resolveDbName(String ticketType, DmDbPairDO pair) {
        if (TICKET_TYPE_PRE_DDL.equals(ticketType)) {
            return pair.getPreDbName();
        }
        return pair.getProdDbName();
    }

    private void appendSubmitEvent(long ticketId, String uid, String ticketType, int groupCount) {
        DmDbChangeEventDO event = new DmDbChangeEventDO();
        event.setTicketId(ticketId);
        event.setEventType(GovEventType.SUBMIT.name());
        event.setFromStatus(null);
        event.setToStatus(ApprovalStatus.WAIT_APPROVAL.name());
        event.setOperatorUid(uid);

        Map<String, Object> data = new HashMap<>();
        data.put("ticketType", ticketType);
        data.put("groupCount", groupCount);
        event.setEventData(JsonUtils.toJson(data));

        dbChangeEventDal.eventMapper().insert(event);
    }

    // Internal precheck result holder for submit
    private static class GroupPrecheck {
        final long   pairId;
        final String sqlContent;
        final GovTicketV2CheckVO.GroupResult result;
        final String precheckResultJson;

        GroupPrecheck(long pairId, String sqlContent, GovTicketV2CheckVO.GroupResult result, String precheckResultJson) {
            this.pairId = pairId;
            this.sqlContent = sqlContent;
            this.result = result;
            this.precheckResultJson = precheckResultJson;
        }
    }
}
