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
import com.clougence.clouddm.console.web.component.approval.ApprovalPersonService;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalStageMO;
import com.clougence.clouddm.console.web.component.auth.DmAuthServiceForBiz;
import com.clougence.clouddm.console.web.component.governance.FullWidthSqlLint;
import com.clougence.clouddm.console.web.component.governance.GovReturnNotifyService;
import com.clougence.clouddm.console.web.component.governance.impl.GovStmtSplitServiceImpl;
import com.clougence.clouddm.console.web.global.i18n.DmI18nUtils;
import com.clougence.clouddm.console.web.global.i18n.I18nDmMsgKeys;
import com.clougence.clouddm.console.web.model.fo.govticket.GovTicketV2CheckFO;
import com.clougence.clouddm.console.web.model.fo.govticket.GovTicketV2ResubmitFO;
import com.clougence.clouddm.console.web.model.fo.govticket.GovTicketV2ReturnTicketFO;
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
import com.clougence.clouddm.platform.dal.model.approval.ApprovalProcessStatus;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStage;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalType;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalTemplateDO;
import com.clougence.clouddm.platform.dal.model.datasource.DmDsDO;
import com.clougence.clouddm.platform.dal.model.dbchange.ChangeType;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO;
import com.clougence.clouddm.platform.dal.model.dbchange.GovEventType;
import com.clougence.clouddm.platform.dal.model.dbpair.DmDbPairDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketStmtRevisionDO;
import com.clougence.clouddm.platform.dal.model.secrule.WarnLevel;
import com.clougence.clouddm.platform.dal.model.system.DmSysEnvDO;
import com.clougence.clouddm.sdk.execute.session.QueryArg;
import com.clougence.clouddm.sdk.security.auth.AuthKind;
import com.clougence.clouddm.sdk.security.auth.def.SecDataAuthLabel;
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
    private static final String APPROVAL_MODE_DIRECT      = "DIRECT";
    private static final String APPROVAL_MODE_VIA_RELEASE  = "VIA_RELEASE";
    private static final String ENGINE_V2           = "v2";
    private static final String CONFIG_FULL_WIDTH_LINT_BLOCK = "govTicketFullWidthLintBlock";

    /** B6 M1: requestUri marker of the system-side GHOST_EXECUTE audit row (no HTTP request exists) */
    private static final String GHOST_AUTO_DISPATCH_URI = "/system/dbChangeV2/ghost/auto-dispatch";

    /** B7 N1b: requestUri marker of the system-side DML_BATCH_EXECUTE audit row (no HTTP request exists) */
    private static final String DML_BATCH_AUTO_DISPATCH_URI = "/system/dbChangeV2/dmlBatch/auto-dispatch";
    /** N2: the audit operationUri marker of the window-crash manual-resolution retry (design §7.4). */
    private static final String DML_BATCH_RESOLUTION_URI = "/system/dbChangeV2/dmlBatch/resolution";

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
    private com.clougence.clouddm.console.web.component.approval.ApprovalStateService approvalStateService;
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
    @Resource
    private com.clougence.clouddm.console.web.component.governance.SchemaSnapshotService schemaSnapshotService;
    @Resource
    private com.clougence.clouddm.platform.dal.access.ProdReleaseDal prodReleaseDal;
    @Resource
    private ApprovalPersonService  approvalPersonService;
    @Resource
    private DmAuthServiceForBiz    dmAuthServiceForBiz;
    // @Lazy: GovReturnNotifyService is in the governance component layer which could
    //   transitively cycle through the approval chain. Safe-side proxy injection.
    @org.springframework.context.annotation.Lazy
    @Resource
    private GovReturnNotifyService govReturnNotifyService;
    @Resource
    private com.clougence.clouddm.console.web.component.governance.RollbackSqlGenerator rollbackSqlGenerator;
    @Resource
    private com.clougence.clouddm.console.web.component.governance.RiskAssessmentService riskAssessmentService;
    @Resource
    private com.clougence.clouddm.console.web.component.governance.SqlRowEstimator sqlRowEstimator;
    // @Lazy: B6 M1 ghost auto-route collaborators — the ghost surface lazy-reaches back into the
    //   governance component layer (GhostGroupCompletionService), safe-side proxy injection (iron rule).
    @org.springframework.context.annotation.Lazy
    @Resource
    private com.clougence.clouddm.console.web.service.ghost.GhostService ghostService;
    @org.springframework.context.annotation.Lazy
    @Resource
    private com.clougence.clouddm.console.web.component.alert.AlertPushService alertPushService;
    // @Lazy: B6 M3 segment retry-resume — the orchestrator lazy-reaches this service back
    //   (dispatchGhostSegment), safe-side proxy injection (governance component iron rule).
    @org.springframework.context.annotation.Lazy
    @Resource
    private com.clougence.clouddm.console.web.component.governance.TicketSegmentService ticketSegmentService;
    // @Lazy: B7 N1b lock-free DML engine (marking-seam probe + dispatch-seam task submission) —
    //   the batch service lazy-reaches the governance components back through the AsyncTask
    //   completion hook; safe-side proxy injection (iron rule).
    @org.springframework.context.annotation.Lazy
    @Resource
    private com.clougence.clouddm.console.web.service.dmlbatch.DmlBatchService dmlBatchService;
    // @Lazy: DingTalk bridge outbound (task 10-09) — the bridge service lazy-reaches the alert/
    //   approval layers back; safe-side proxy injection (governance component iron rule).
    @org.springframework.context.annotation.Lazy
    @Resource
    private com.clougence.clouddm.console.web.component.bridge.DingBridgeOutboundService dingBridgeOutboundService;
    @Resource
    private com.clougence.rdp.service.RdpOpAuditService rdpOpAuditService;

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

        // Resolve approval mode: DIRECT (default) or VIA_RELEASE. Only PROD_DML may use VIA_RELEASE;
        // null/blank/unknown values default to DIRECT (legacy behavior). PRE_DDL + VIA_RELEASE = 400.
        String approvalMode = resolveApprovalMode(ticketType, fo.getApprovalMode());

        // Authoritative re-run precheck (blocking — any group failure rejects the whole ticket)
        List<GroupPrecheck> prechecks = new ArrayList<>();
        for (GovTicketV2SubmitFO.GroupInput gi : fo.getGroups()) {
            GovTicketV2CheckVO.GroupResult gr = runPrecheck(puid, uid, ticketType, gi.getPairId(), gi.getSqlContent());
            if ("FAIL".equals(gr.getCheckStatus())) {
                throwPrecheckFailure(gr);
            }
            prechecks.add(new GroupPrecheck(0L, gi.getPairId(), gi.getSqlContent(), gr, buildPrecheckResultJson(gr)));
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
        mo.setApprovalMode(approvalMode);
        // B6 M1: ghost auto-route flags — the execution-time authority, positional with pairIds
        // and the group insertion order below (null when nothing is routed — legacy shape kept).
        List<Boolean> ghostFlags = buildGhostRouteFlags(prechecks);
        if (ghostFlags != null) {
            mo.setGhostRouteFlags(ghostFlags);
        }

        // VIA_RELEASE: ticket enters the release ledger pool at WAIT_RELEASE — no approval process,
        // no approval persons, no standalone execution entry (double-execution mutex by construction).
        boolean viaRelease = APPROVAL_MODE_VIA_RELEASE.equals(approvalMode);

        // Determine approval type (VIA_RELEASE still stores it for display; no process consumes it).
        // For external channels (DingTalk/Feishu/Wechat/Custom) the template must exist and the channel
        // must be enabled at submit time — fail-fast here, before any dm_approval insert, so a
        // misconfigured env never creates a ticket whose OA approval instance would be doomed
        // (processCode comes from approTemplateIdentity at handler.createApproval time). Internal
        // path keeps both template fields null — legacy bit-by-bit (regression pin).
        ApprovalType approvalType = ApprovalType.Internal;
        String approTemplateId = null;
        String approTemplateName = null;
        if (TICKET_TYPE_PROD_DML.equals(ticketType)) {
            var ticketConfig = dmEnvParamService.querySqlTicketInfoParam(puid, firstDs.getDsEnvId());
            if (ticketConfig != null && StringUtils.isNotBlank(ticketConfig.getType())) {
                approvalType = ApprovalType.valueOf(ticketConfig.getType());
            }
            if (approvalType != ApprovalType.Internal && approvalType != ApprovalType.External) {
                // checkApprovalAndReturnTemplate throws ErrorMessageException on missing template /
                // disabled channel (TICKET_TEMPLATE_NOT_EXISTS / TICKET_APPROVAL_TYPE_NOT_ENABLE);
                // production impl never returns null, so no defensive null-check (AUTH path precedent).
                DmApprovalTemplateDO templateDO = approvalFlowService.checkApprovalAndReturnTemplate(
                    puid, approvalType, ticketConfig.getTemplateId(), null);
                approTemplateId = ticketConfig.getTemplateId();
                approTemplateName = templateDO.getTemplateName();
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
        ticket.setTicketStatus(viaRelease ? ApprovalStatus.WAIT_RELEASE : ApprovalStatus.WAIT_APPROVAL);
        ticket.setApproBiz(ApprovalBiz.DM_CHANGE);
        ticket.setApproType(approvalType);
        // External channels only: template identity/name persisted so the approval handler can
        // populate processCode when creating the OA approval instance. Internal stays null (regression pin).
        if (approTemplateId != null) {
            ticket.setApproTemplateIdentity(approTemplateId);
            ticket.setApproTemplateName(approTemplateName);
        }
        ticket.setEnvName(envDO != null ? envDO.getEnvName() : null);
        ticket.setContentType(com.clougence.clouddm.platform.dal.model.approval.SqlContentType.INLINE);
        ticket.setFeatures(List.of(ApprovalFeature.values()));
        ticket.setRawSql(first.sqlContent);
        // Rollback SQL: applicant-provided takes priority; blank → system-generated backup template
        // from the combined group SQL (ticket-level, zero DDL — stored on dm_approval.roll_back_sql).
        if (StringUtils.isNotBlank(fo.getRollBackSql())) {
            ticket.setRollBackSql(fo.getRollBackSql());
            mo.setRollBackSqlGenerated(false);
        } else {
            String combinedSql = prechecks.stream()
                .map(pc -> pc.sqlContent)
                .collect(Collectors.joining(";"));
            String generated = rollbackSqlGenerator.generate(combinedSql);
            if (StringUtils.isNotBlank(generated)) {
                ticket.setRollBackSql(generated);
                mo.setRollBackSqlGenerated(true);
            }
        }
        // Risk assessment (design D2: synchronous in submit transaction)
        String dsType = firstDs != null && firstDs.getDataSourceType() != null ? firstDs.getDataSourceType().name() : null;
        String envName = envDO != null ? envDO.getEnvName() : null;
        com.clougence.clouddm.console.web.component.governance.RiskAssessment riskAssessment
            = assessRiskFromPrechecks(puid, firstDs.getDsEnvId(), envName, dsType, prechecks);
        mo.setRiskLevel(riskAssessment.getLevel().name());
        mo.setImpactScope(com.clougence.utils.JsonUtils.toJson(riskAssessment.getScope()));
        mo.setImpactScopeText(riskAssessment.getReadableText());
        // Writeback expected_affected_rows (design D3)
        Long totalRows = null;
        for (GroupPrecheck pc : prechecks) {
            if (pc.result.getEstimatedRows() != null) {
                if (totalRows == null) {
                    totalRows = 0L;
                }
                totalRows += pc.result.getEstimatedRows();
            }
        }
        if (totalRows != null) {
            ticket.setExpectedAffectedRows(totalRows);
        }
        ticket.setTicketInfo(JsonUtils.toJson(mo));
        ticket.setLevels(List.of(firstDs.getDsEnvId().toString(), String.valueOf(firstDsId), resolveDbName(ticketType, firstPair)));
        ticket.setStatusMessage(viaRelease ? "Waiting for release merge" : "Waiting for approval");

        approvalDal.approvalMapper().insert(ticket);

        // Create approval process — skipped for VIA_RELEASE (no approval persons / process rows;
        // canApproval=false by empty person list, approvalTicket rejects by status guard + NO_PERMISSION).
        if (!viaRelease) {
            approvalFlowService.createProcess(ticket.getId(), ApprovalBiz.DM_CHANGE, true);
        }

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
        ChangeType ticketChangeType = ChangeType.merge(prechecks.stream()
            .map(pc -> ChangeType.valueOf(pc.result.getChangeType()))
            .collect(Collectors.toList()));
        appendSubmitEvent(ticket.getId(), uid, ticketType, prechecks.size(), ticketChangeType, approvalMode);

        // B6 M1 (R15 告警放行): threshold-exceeding ALTERs that cannot run lock-free proceed on the
        // direct chain — the alert fact is trailed into the event chain inside this transaction and
        // the IM push is registered for afterCommit (never blocks or rolls back the submission).
        registerGhostAlerts(puid, uid, ticket.getId(), ticket.getOwnerUid(), prechecks);

        // DingTalk bridge outbound hook 1 (task 10-09, design §3.3): an External-channel DIRECT
        // submission registers the afterCommit flows/create trigger — the bridge initiates the
        // DingTalk approval instance while the ticket waits at WAIT_APPROVAL for inbound commands.
        // VIA_RELEASE never triggers (no approval instance exists, design boundary #14); Internal/
        // plugin channels never trigger (boundary #13). Never blocks or rolls back the submission.
        if (!viaRelease && approvalType == ApprovalType.External) {
            try {
                dingBridgeOutboundService.triggerCreateFlow(puid, ticket.getId());
            } catch (Throwable t) {
                log.warn("[DingBridge] submit outbound trigger failed for ticket {}, degraded", ticket.getId(), t);
            }
        }

        GovTicketV2SubmitVO result = new GovTicketV2SubmitVO();
        result.setTicketId(ticket.getId());
        result.setTicketType(ticketType);
        return result;
    }

    // ==================== retryGroup ====================

    @Override
    public void retryGroup(String puid, String uid, long groupId) {
        // B7 N2: the legacy 3-arg form delegates with a null resolution — byte-identical behavior
        // for every non-DML_BATCH group and every non-unresolved DML_BATCH failure (普通段续跑).
        retryGroup(puid, uid, groupId, null);
    }

    @Override
    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRED)
    public void retryGroup(String puid, String uid, long groupId, String dmlBatchResolution) {
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

        // REVISION guard: a ticket in REVISION must be resubmitted (with modified SQL),
        // not retried as-is — retryGroup would replay the frozen (possibly broken) text.
        if (ticket.getTicketStatus() == ApprovalStatus.REVISION) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.GOV_TICKET_V2_NOT_RETURNABLE.name()));
        }

        ApprovalMO mo = JsonUtils.toObj(ticket.getTicketInfo(), ApprovalMO.class);
        String ticketType = mo != null ? mo.getTicketType() : null;

        // B6 M3 (design §6.5, replaces the M2 R10 window guard): a SEGMENTED group (plan segments
        // ∨ runtime exec_segments) resumes FROM THE FAILED SEGMENT — whole-group replay would
        // necessarily fail on an already-SUCCESS ghost segment (its ALTER landed: column exists)
        // and could trigger a second migration / old-table pile-up. The orchestrator owns the
        // resume semantics (fresh under-lock read, extended active check, ghost idempotent cleanup
        // + ADD-COLUMN target-effect probe, success segments never re-played). Decision order
        // segments → flags → legacy is the compatibility contract: M1-marked groups WITHOUT a
        // segment plan (ghostRouteFlags only — the single-ALTER degenerate shape of existing
        // tickets) keep the ghost retry variant below, and legacy groups keep the whole-group
        // replay byte-for-byte (regression pins).
        if (StringUtils.isNotBlank(group.getExecSegments())
            || com.clougence.clouddm.console.web.component.governance.TicketSegment.isSegmentedPlan(group.getPrecheckResult())) {
            // B7 N2 (design §7.4): capture the resolution trail material BEFORE the resume —
            // resetSegmentForRerun inside the orchestrator clears the summary that carries the
            // unresolvedIntent/intentSeq markers. Best-effort read of the pre-lock group row (the
            // AUTHORITATIVE guard runs under the lock inside resumeGroupUnderLock; this capture
            // only feeds the event+audit trail after a successful resume).
            DmlResolutionTrail resolutionTrail = dmlBatchResolution == null ? null
                : captureDmlResolutionTrail(group, dmlBatchResolution);
            // Lock-order discipline: the ticket row lock precedes ANY group-row write (the
            // retryGhostGroup / handleV2JobCompletion single-lock contract — no AB-BA deadlock).
            approvalDal.approvalMapper().selectByIdForUpdate(ticket.getId());
            // N2: the null-resolution path keeps the B6 3-arg call byte-identical (the frozen
            // RetryGroup pins verify THAT signature on their orchestrator mock); only an explicit
            // verdict reaches the 4-arg overload.
            boolean groupTerminalByProbe = dmlBatchResolution == null
                ? ticketSegmentService.resumeGroupUnderLock(ticket, group.getId(), uid)
                : ticketSegmentService.resumeGroupUnderLock(ticket, group.getId(), uid, dmlBatchResolution);
            // Step-times contract (:415): the v2 (re)dispatch closes PREPARATION at the real
            // dispatch moment (legacy retryGroup shape below).
            this.approvalStateService.markExecutionDispatched(ticket.getBizId());
            if (groupTerminalByProbe) {
                // the resume-probe advanced the LAST segment synchronously (group terminal written,
                // NOTHING dispatched) — no completion callback will ever fire and the EXEC_FAIL
                // ticket is outside the WAIT_EXEC sweep IN set, so THIS call must drive the shared
                // aggregation tail or the ticket sticks at EXEC_FAIL over all-SUCCESS groups.
                aggregateTicketAfterSegmentResume(ticket);
            }
            if (resolutionTrail != null) {
                // 人工裁决双留痕 (裁决 #13, design §7.4): event + audit AFTER the successful resume
                // (a rejected resume — RESOLUTION_REQUIRED / active-body — leaves no trail).
                appendDmlResolutionEvent(ticket.getId(), uid, resolutionTrail);
                auditDmlResolution(ticket, uid, resolutionTrail);
            }
            return;
        }

        // B6 M1: ghost-route variant — a group marked at precheck has NO autoexec job; its retry
        // rebuilds the GHOST tool session instead (dispatch site #2, design §6.3). The active
        // check is extended inside: active autoexec job ∨ active GHOST AsyncTask both reject.
        // Legacy/unmarked groups keep the original path byte-for-byte (regression pin).
        if (isGhostRoutedGroup(mo, group)) {
            retryGhostGroup(puid, uid, ticket, group, mo);
            return;
        }

        // Determine exec config from ticket type (precheck keeps PROD_DML pure DML; PRE tickets may mix DDL+DML)
        boolean transactional = TICKET_TYPE_PROD_DML.equals(ticketType);
        com.clougence.clouddm.api.console.autoexec.ErrorStrategy errorStrategy
            = com.clougence.clouddm.api.console.autoexec.ErrorStrategy.NONE;

        // Reset group to PENDING
        ticketDbStmtDal.stmtMapper().updateExecStatus(groupId, EXEC_STATUS_PENDING, null);

        // Sub-task A: fill-missing snapshot capture before re-execution.
        // first-capture-wins: existing CAPTURED rows are never replaced. This ensures
        // the pre-before baseline remains the first attempt's state, not a post-partial-
        // execution polluted state.
        schemaSnapshotService.captureIfMissing(group, group.getTicketId());

        // Rebuild group job
        String jobBizId = com.clougence.clouddm.console.web.util.DmTeamUtils.nextExecJobBizId();
        String languageTag = com.clougence.clouddm.console.web.global.i18n.DmI18nUtils.getLocale().toLanguageTag();
        this.autoExecService.createGroupJob(group, jobBizId, transactional, errorStrategy, languageTag, uid);
        this.autoExecService.startJob(jobBizId, uid);

        // Step-times fix (2026-09-12): v2 group jobs carry no dependOnBizId, so the legacy
        // preparation hook never fires — close PREPARATION at the real (re)dispatch moment.
        this.approvalStateService.markExecutionDispatched(ticket.getBizId());
    }

    /**
     * B6 M3 (check fix): the aggregation tail for a segment resume that wrote the GROUP TERMINAL
     * synchronously (the probe-success-on-the-last-segment path — nothing dispatched, so no
     * completion callback will fire and the EXEC_FAIL ticket is outside the WAIT_EXEC sweep IN
     * set). Mirrors the handleV2JobCompletion tail (:833-846) byte-for-byte: all groups terminal →
     * anyFailed ? failExecution : completeExecution. Runs UNDER the ticket row lock retryGroup just
     * took, in its transaction — the same single-lock/single-writer discipline as every other
     * aggregation caller (callback / hook / sweep).
     */
    private void aggregateTicketAfterSegmentResume(DmApprovalDO ticket) {
        List<DmTicketDbStmtDO> allGroups = ticketDbStmtDal.stmtMapper().queryByTicketId(ticket.getId());
        boolean allTerminal = allGroups.stream().allMatch(g ->
            EXEC_STATUS_SUCCESS.equals(g.getExecStatus()) || EXEC_STATUS_FAILED.equals(g.getExecStatus()));
        if (!allTerminal) {
            return;
        }
        boolean anyFailed = allGroups.stream().anyMatch(g -> EXEC_STATUS_FAILED.equals(g.getExecStatus()));
        if (anyFailed) {
            this.approvalStateService.failExecution(ticket.getBizId(),
                "One or more database groups failed execution");
        } else {
            this.approvalStateService.completeExecution(ticket.getBizId());
        }
    }

    // ==================== B6 M1: ghost auto-route dispatch / retry / alerts ====================

    /**
     * B6 M1 (design §6.3): dispatch a ghost-marked single-ALTER group through the GHOST tool
     * session — the mirror of the retryGroup "build execution + markExecutionDispatched" shape.
     * Callers: the SYSTEM auto-advance chain (GovAutoAdvanceServiceImpl dispatch site #1) and
     * the retryGroup ghost variant (site #2). The External-resubmit direct-to-exec branch is
     * PRE_DDL-unreachable by construction (External is PROD_DML-only, whose purity rule excludes
     * DDL — design §6.3), so it carries no ghost branch (defensive equivalence left to M3).
     * <p>
     * Session owner = audit subject = the ticket SUBMITTER (design §6.9: the auto dispatch is a
     * system action recorded in the submitter's name; the submitter needs no DM_GHOST_EXEC).
     * The options stay empty on the FO → the admin ghostDefaultOptions merge chain applies (R14).
     */
    @Override
    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRED)
    public void dispatchGhostGroup(DmApprovalDO ticket, DmTicketDbStmtDO group, String via) {
        // Concurrency contract (handleV2JobCompletion :757-766 semantics — single lock, same
        // order in every path): take the TICKET ROW LOCK first, before the remote launch and
        // the group-row write. A fast-finishing migration's terminal hook (which locks the same
        // ticket row before its EXECUTING-CAS read) then waits for this dispatch transaction to
        // commit instead of skipping the writeback over a stale PENDING group snapshot.
        approvalDal.approvalMapper().selectByIdForUpdate(ticket.getId());

        // CAS re-check under the lock: the auto-advance caller passes a PRE-LOCK group snapshot,
        // and a concurrent retryGroup ghost variant (same lock discipline) may have rebuilt and
        // dispatched the group while this transaction waited for the lock. Only PENDING/FAILED
        // groups are dispatchable — anything else means another writer already owns the group,
        // so skip idempotently instead of double-launching a second gh-ost session over a live
        // migration (autoexec's equivalent guard is the uk_exec_auto_job_group physical CAS,
        // which the ghost path has no analog of — this re-read is that analog).
        DmTicketDbStmtDO lockedGroup = ticketDbStmtDal.stmtMapper().queryById(group.getId());
        if (lockedGroup == null
            || (!EXEC_STATUS_PENDING.equals(lockedGroup.getExecStatus())
                && !EXEC_STATUS_FAILED.equals(lockedGroup.getExecStatus()))) {
            log.warn("[GovTicketV2] ghost dispatch skipped for ticket {} group {} — status {} under the lock"
                    + " (a concurrent writer already dispatched it)", ticket.getId(), group.getId(),
                lockedGroup == null ? null : lockedGroup.getExecStatus());
            return;
        }

        String submitterUid = ticket.getOwnerUid();
        // pre-before baseline (first-capture-wins; failures never block — same contract as the
        // autoexec dispatch sites)
        schemaSnapshotService.captureIfMissing(lockedGroup, ticket.getId());

        com.clougence.clouddm.console.web.model.fo.ghost.GhostExecuteFO fo
            = new com.clougence.clouddm.console.web.model.fo.ghost.GhostExecuteFO();
        fo.setDsId(lockedGroup.getDsId());
        fo.setDbName(lockedGroup.getDbName());
        fo.setDdlSql(lockedGroup.getSqlContent());
        // M1 single-segment degenerate shape: the whole group text IS the ALTER; no segmentIndex
        // binding (null) — existing M1 tickets keep the M1 completion/probe path (compatibility pin).
        com.clougence.clouddm.console.web.model.vo.ghost.GhostExecuteVO vo
            = ghostService.executeForTicket(ticket.getPrimaryUid(), submitterUid, fo, ticket.getId(), group.getId(), null);

        // group row → EXECUTING (single-segment degenerate shape; the terminal writeback hook /
        // sweep probe own the transition out of it)
        ticketDbStmtDal.stmtMapper().updateExecStatus(group.getId(), EXEC_STATUS_EXECUTING, null);

        // step-times contract: close PREPARATION at the real (re)dispatch moment (retryGroup shape)
        this.approvalStateService.markExecutionDispatched(ticket.getBizId());

        // R10 audit: GHOST_EXECUTE in the submitter's name, obj annotated with the ticket source.
        // Audit failure must NOT roll back the already-started remote migration (the session and
        // task exist regardless) — log and proceed.
        com.clougence.clouddm.console.web.model.vo.ghost.GhostOpAuditObj auditObj = vo == null ? null : vo.getAudit();
        if (auditObj != null) {
            auditObj.setTicketId(ticket.getId());
            auditObj.setGroupId(group.getId());
            auditObj.setVia(via);
        }
        try {
            rdpOpAuditService.logAndAddOperationAudit(ticket.getPrimaryUid(), submitterUid,
                GHOST_AUTO_DISPATCH_URI, null, group.getDsId(), auditObj,
                com.clougence.clouddm.platform.dal.model.monitor.SecurityLevel.HIGH,
                com.clougence.clouddm.platform.dal.model.monitor.AuditType.GHOST_EXECUTE,
                com.clougence.clouddm.platform.dal.model.ResourceType.DATASOURCE);
        } catch (RuntimeException e) {
            log.error("[GovTicketV2] GHOST_EXECUTE audit failed for ticket {} group {} (dispatch kept)",
                ticket.getId(), group.getId(), e);
        }
    }

    /**
     * B6 M2 (design §6.3): dispatch ONE GHOST segment of a segmented group — the in-chain
     * variant of dispatchGhostGroup. Caller contract: the ticket row lock is ALREADY held and
     * the group/segment state transitions are owned by the caller (TicketSegmentService) — no
     * PENDING/FAILED group CAS here (the group is mid-chain EXECUTING; the segment-status CAS
     * already passed under the same lock). The session launch binds (ticketId, groupId,
     * segmentIndex) into the AsyncTask configData; sessionId/taskId are recorded ON the passed
     * segment instance and the CALLER persists the runtime (single writer discipline).
     * <p>
     * <b>Post-commit dispatch evaluation (B6 M3, M2 check 裁决项 2 — ruling: NOT converted)</b>:
     * the remote launch runs INSIDE this transaction, so "launch succeeded + transaction later
     * rolled back" leaves an orphan session (untracked gh-ost + a burned sidecar cap-2 slot —
     * the AsyncTask row rolls back with the transaction and the in-memory dispatcher only picks
     * tasks from committed rows). Moving the launch to a TransactionSynchronization afterCommit
     * was evaluated and REJECTED: it would break the documented under-lock atomicity the F1
     * fresh-read invariants rely on ("job flip + segment advance commit atomically", contract
     * :414), split the dispatch across 5 call sites into commit + post-launch runtime update
     * transactions, and open a NEW race — the ~7s sweep probing "segment EXECUTING ∧ task
     * missing" between the commit and the launch would falsely FAIL healthy dispatches without
     * a grace-window mechanism (new sweep semantics + new tests). Accepted caliber instead: the
     * post-launch window inside the transaction is one runtime persist + (for a mid-chain
     * advance) one aggregation-tail SELECT — minimal by construction; orphans WITH a recorded
     * sessionId converge through the sweep's failSegment closeQuietly (M3); the rare
     * rollback-window orphan (sessionId never recorded) is reclaimed at sidecar restart, and its
     * DB effect converges through the M3 resume probe (a landed ADD COLUMN is detected instead
     * of replayed; other shapes hit gh-ost's duplicate-object safe failure).
     */
    @Override
    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRED)
    public void dispatchGhostSegment(DmApprovalDO ticket, DmTicketDbStmtDO group,
                                     com.clougence.clouddm.console.web.component.governance.TicketSegment segment,
                                     String via) {
        String submitterUid = ticket.getOwnerUid();
        // pre-before baseline (first-capture-wins; never blocks — same contract as the other
        // dispatch sites; mid-chain this is usually a no-op, the first dispatch already captured)
        schemaSnapshotService.captureIfMissing(group, ticket.getId());

        com.clougence.clouddm.console.web.model.fo.ghost.GhostExecuteFO fo
            = new com.clougence.clouddm.console.web.model.fo.ghost.GhostExecuteFO();
        fo.setDsId(group.getDsId());
        fo.setDbName(group.getDbName());
        fo.setDdlSql(segment.getSql()); // the FULL frozen statement text from the plan (never the group fulltext)
        com.clougence.clouddm.console.web.model.vo.ghost.GhostExecuteVO vo
            = ghostService.executeForTicket(ticket.getPrimaryUid(), submitterUid, fo,
                ticket.getId(), group.getId(), segment.getSegIdx());
        segment.setSessionId(vo == null ? null : vo.getSessionId());
        segment.setTaskId(vo == null ? null : vo.getTaskId());

        // group row → EXECUTING (first segment: PENDING→EXECUTING; mid-chain: idempotent rewrite)
        ticketDbStmtDal.stmtMapper().updateExecStatus(group.getId(), EXEC_STATUS_EXECUTING, null);

        // R10 audit: GHOST_EXECUTE in the submitter's name, obj annotated with the ticket source
        // + segment position. Audit failure must NOT roll back the already-started remote
        // migration (dispatchGhostGroup contract).
        com.clougence.clouddm.console.web.model.vo.ghost.GhostOpAuditObj auditObj = vo == null ? null : vo.getAudit();
        if (auditObj != null) {
            auditObj.setTicketId(ticket.getId());
            auditObj.setGroupId(group.getId());
            auditObj.setSegmentIndex(segment.getSegIdx());
            auditObj.setVia(via);
        }
        try {
            rdpOpAuditService.logAndAddOperationAudit(ticket.getPrimaryUid(), submitterUid,
                GHOST_AUTO_DISPATCH_URI, null, group.getDsId(), auditObj,
                com.clougence.clouddm.platform.dal.model.monitor.SecurityLevel.HIGH,
                com.clougence.clouddm.platform.dal.model.monitor.AuditType.GHOST_EXECUTE,
                com.clougence.clouddm.platform.dal.model.ResourceType.DATASOURCE);
        } catch (RuntimeException e) {
            log.error("[GovTicketV2] GHOST_EXECUTE audit failed for ticket {} group {} segment {} (dispatch kept)",
                ticket.getId(), group.getId(), segment.getSegIdx(), e);
        }
    }

    /**
     * B7 N1b (design §5.3/§9 dispatch seam): dispatch ONE DML_BATCH segment — the structural
     * mirror of {@link #dispatchGhostSegment} with the console-side AsyncTask replacing the
     * gh-ost tool session. Caller contract IDENTICAL: the ticket row lock is ALREADY held and
     * the caller (TicketSegmentService) owns the segment state transitions — no group CAS here;
     * the taskId is recorded ON the passed segment instance and the CALLER persists the runtime
     * (single-writer discipline).
     * <p>
     * The launch mechanism (configData assembly + effectiveParams snapshot + submitTask) lives in
     * {@code DmlBatchService.submitForTicket}; a submitTask failure THROWS with no execution-body
     * residue (a batch task has no session — the inverse direction of the ghost closeQuietly
     * coupling), the dispatch transaction rolls back and the segment stays for the sweep's
     * PENDING re-dispatch row. The retryGroup resume path rides the same method: the segment's
     * {@code resumeCheckpoint} (done-side inheritance, design §7.4) seeds the fresh task's
     * checkpoint — 已成功批永不重放 is physically guaranteed by the cursor inheritance.
     * <p>
     * Post-commit dispatch conversion: the same EVALUATED-AND-REJECTED ruling as
     * dispatchGhostSegment applies verbatim (under-lock dispatch atomicity, single call surface,
     * no sweep grace-window mechanism); the residual rollback window is even smaller here — an
     * uncommitted AsyncTask row is invisible to the dispatcher (it reads committed rows only), so
     * a rollback simply un-submits the task with ZERO side effects (no session, no binary).
     */
    @Override
    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRED)
    public void dispatchBatchSegment(DmApprovalDO ticket, DmTicketDbStmtDO group,
                                     com.clougence.clouddm.console.web.component.governance.TicketSegment segment,
                                     String via) {
        String submitterUid = ticket.getOwnerUid();
        // pre-before baseline (first-capture-wins; never blocks — same contract as the other
        // dispatch sites; mid-chain this is usually a no-op, the first dispatch already captured)
        schemaSnapshotService.captureIfMissing(group, ticket.getId());

        // the launch: configData assembly (frozen stmt/batchKey + effectiveParams snapshot +
        // binding triple + inherited-or-fresh checkpoint) + submitTask — throws on failure with
        // no residue (interface contract)
        Long taskId = dmlBatchService.submitForTicket(ticket, group, segment, segment.getResumeCheckpoint());
        segment.setTaskId(taskId); // the completion CAS taskId cross-check key (debt #5)

        // group row → EXECUTING (first segment: PENDING→EXECUTING; mid-chain: idempotent rewrite)
        ticketDbStmtDal.stmtMapper().updateExecStatus(group.getId(), EXEC_STATUS_EXECUTING, null);

        // R13 audit: DML_BATCH_EXECUTE in the submitter's name, obj annotated with the ticket
        // source + segment position + the frozen key facts. Audit failure must NOT roll back the
        // already-submitted task (GHOST_EXECUTE discipline — the task exists regardless).
        com.clougence.clouddm.console.web.model.vo.dmlbatch.DmlBatchOpAuditObj auditObj
            = buildDmlBatchAuditObj(ticket, group, segment, via);
        decorateDmlBatchAuditTaskFacts(auditObj, segment.getTaskId());
        try {
            rdpOpAuditService.logAndAddOperationAudit(ticket.getPrimaryUid(), submitterUid,
                DML_BATCH_AUTO_DISPATCH_URI, null, group.getDsId(), auditObj,
                com.clougence.clouddm.platform.dal.model.monitor.SecurityLevel.HIGH,
                com.clougence.clouddm.platform.dal.model.monitor.AuditType.DML_BATCH_EXECUTE,
                com.clougence.clouddm.platform.dal.model.ResourceType.DATASOURCE);
        } catch (RuntimeException e) {
            log.error("[GovTicketV2] DML_BATCH_EXECUTE audit failed for ticket {} group {} segment {} (dispatch kept)",
                ticket.getId(), group.getId(), segment.getSegIdx(), e);
        }
    }

    /** the DML_BATCH_EXECUTE audit payload (the frozen plan facts + dispatch origin). */
    private static com.clougence.clouddm.console.web.model.vo.dmlbatch.DmlBatchOpAuditObj buildDmlBatchAuditObj(
            DmApprovalDO ticket, DmTicketDbStmtDO group,
            com.clougence.clouddm.console.web.component.governance.TicketSegment segment, String via) {
        com.clougence.clouddm.console.web.model.vo.dmlbatch.DmlBatchOpAuditObj obj
            = new com.clougence.clouddm.console.web.model.vo.dmlbatch.DmlBatchOpAuditObj();
        obj.setTicketId(ticket.getId());
        obj.setGroupId(group.getId());
        obj.setSegmentIndex(segment.getSegIdx());
        obj.setVia(via);
        obj.setDsId(group.getDsId());
        obj.setDbName(group.getDbName());
        obj.setTableName(segment.getTable());
        if (segment.getBatchKey() != null) {
            obj.setKeyColumn(segment.getBatchKey().getColumn());
            obj.setKeyType(segment.getBatchKey().getKeyType());
        }
        obj.setEstimatedRows(segment.getEstimatedRows());
        obj.setIdempotent(segment.getIdempotent());
        String sql = segment.getSql();
        obj.setSqlSummary(sql != null && sql.length()
            > com.clougence.clouddm.console.web.model.vo.dmlbatch.DmlBatchOpAuditObj.SQL_MAX_LENGTH
                ? sql.substring(0, com.clougence.clouddm.console.web.model.vo.dmlbatch.DmlBatchOpAuditObj.SQL_MAX_LENGTH)
                : sql);
        return obj;
    }

    /**
     * N2 (N1b check handover #3): fill the audit obj's task facts — taskBizId (the dm_mon_biz_log
     * forensics key) + the dispatch-frozen batchSize — via segment.taskId → the submitted task row
     * → its configData. NEVER-THROW audit decoration (the audit try-catch below already keeps a
     * failing audit from rolling the dispatch back; a failing DECORATION must not fail the audit
     * either — an unresolvable row leaves the two fields null, which is exactly the pre-N2 shape).
     */
    private void decorateDmlBatchAuditTaskFacts(
            com.clougence.clouddm.console.web.model.vo.dmlbatch.DmlBatchOpAuditObj auditObj, Long taskId) {
        if (auditObj == null || taskId == null) {
            return;
        }
        try {
            com.clougence.clouddm.platform.dal.model.execution.DmExecAsyncTaskDO taskRow
                = this.execDal.asyncTaskMapper().queryById(taskId);
            com.clougence.clouddm.console.web.service.dmlbatch.asyntask.DmlBatchAsyncTaskConfig cfg
                = com.clougence.clouddm.console.web.service.dmlbatch.DmlBatchServiceImpl.parseTaskConfig(taskRow);
            if (cfg == null) {
                return;
            }
            auditObj.setTaskBizId(cfg.getBizId());
            if (cfg.getEffectiveParams() != null) {
                auditObj.setBatchSize(cfg.getEffectiveParams().getBatchSize());
            }
        } catch (RuntimeException e) {
            log.warn("[GovTicketV2] DML_BATCH audit task-fact decoration failed for taskId {} (audit proceeds without them)",
                taskId, e);
        }
    }

    /** positional ghost-route lookup of one group (id-ASC index — see GhostRouteFlags contract). */
    private boolean isGhostRoutedGroup(ApprovalMO mo, DmTicketDbStmtDO group) {
        if (!com.clougence.clouddm.console.web.component.governance.GhostRouteFlags.anyRouted(mo)) {
            return false; // cheap pre-filter: legacy/unmarked tickets never touch the mapper
        }
        List<DmTicketDbStmtDO> all = ticketDbStmtDal.stmtMapper().queryByTicketId(group.getTicketId());
        for (int i = 0; i < all.size(); i++) {
            DmTicketDbStmtDO one = all.get(i);
            if (one.getId() != null && one.getId().equals(group.getId())) {
                return com.clougence.clouddm.console.web.component.governance.GhostRouteFlags.isRouted(mo, i);
            }
        }
        return false;
    }

    /** the N2 resolution trail material (captured pre-resume; the reset clears the summary). */
    private static final class DmlResolutionTrail {
        long    groupId;
        int     segmentIndex;
        String  resolution;
        Long    intentSeq;
        String  table;
        Long    dsId;
        String  dbName;
    }

    /**
     * Best-effort pre-resume capture of the resolution trail material (design §7.4 双留痕): the
     * first resumable DML_BATCH segment whose frozen summary carries unresolvedIntent. Null when
     * the shape does not match (a non-DML target / no unresolved marker — the authoritative guard
     * lives under the lock inside resumeGroupUnderLock; a null trail simply writes no event/audit).
     * Never-throw: an unparsable runtime degrades to no trail, never blocks the retry itself.
     */
    private static DmlResolutionTrail captureDmlResolutionTrail(DmTicketDbStmtDO group, String resolution) {
        try {
            List<com.clougence.clouddm.console.web.component.governance.TicketSegment> runtime
                = com.clougence.clouddm.console.web.component.governance.TicketSegment.parseRuntime(group.getExecSegments());
            if (runtime == null) {
                return null;
            }
            com.clougence.clouddm.console.web.component.governance.TicketSegment target
                = com.clougence.clouddm.console.web.component.governance.TicketSegment.firstResumable(runtime);
            if (target == null
                || !com.clougence.clouddm.console.web.component.governance.TicketSegment.TYPE_DML_BATCH.equals(target.getType())
                || !com.clougence.clouddm.console.web.component.governance.TicketSegmentService.isUnresolvedSummary(target.getSummary())) {
                return null;
            }
            DmlResolutionTrail trail = new DmlResolutionTrail();
            trail.groupId = group.getId();
            trail.segmentIndex = target.getSegIdx();
            trail.resolution = resolution;
            Object seq = target.getSummary().get("intentSeq");
            trail.intentSeq = seq instanceof Number ? ((Number) seq).longValue() : null;
            trail.table = target.getTable();
            trail.dsId = group.getDsId();
            trail.dbName = group.getDbName();
            return trail;
        } catch (RuntimeException e) {
            return null; // trail-only capture — the retry proceeds regardless
        }
    }

    /** the resolution event trail (裁决 #13: 高风险人工干预 — event + audit 双留痕). */
    private void appendDmlResolutionEvent(long ticketId, String uid, DmlResolutionTrail trail) {
        DmDbChangeEventDO event = new DmDbChangeEventDO();
        event.setTicketId(ticketId);
        event.setEventType(GovEventType.DML_BATCH_RESOLUTION.name());
        event.setFromStatus(null);
        event.setToStatus(null);
        event.setOperatorUid(uid);
        Map<String, Object> data = new HashMap<>();
        data.put("groupId", trail.groupId);
        data.put("segmentIndex", trail.segmentIndex);
        data.put("resolution", trail.resolution);
        data.put("intentSeq", trail.intentSeq);
        event.setEventData(JsonUtils.toJson(data));
        dbChangeEventDal.eventMapper().insert(event);
    }

    /**
     * the resolution audit twin (design §7.4: RERUN/SKIP reuse AuditType.DML_BATCH_EXECUTE — obj
     * annotated resolution+intentSeq; 四点注册成本为零). Subject = the RETRY OPERATOR (the human
     * who adjudicated — unlike the dispatch row whose subject is the submitter, this is a manual
     * high-risk intervention). Audit failure never rolls the resumed dispatch back (GHOST_EXECUTE
     * discipline — the fresh task is already launched at this point).
     */
    private void auditDmlResolution(DmApprovalDO ticket, String uid, DmlResolutionTrail trail) {
        try {
            com.clougence.clouddm.console.web.model.vo.dmlbatch.DmlBatchOpAuditObj obj
                = new com.clougence.clouddm.console.web.model.vo.dmlbatch.DmlBatchOpAuditObj();
            obj.setTicketId(ticket.getId());
            obj.setGroupId(trail.groupId);
            obj.setSegmentIndex(trail.segmentIndex);
            obj.setVia(GHOST_VIA_RETRY);
            obj.setDsId(trail.dsId);
            obj.setDbName(trail.dbName);
            obj.setTableName(trail.table);
            obj.setResolution(trail.resolution);
            obj.setIntentSeq(trail.intentSeq);
            rdpOpAuditService.logAndAddOperationAudit(ticket.getPrimaryUid(), uid,
                DML_BATCH_RESOLUTION_URI, null, trail.dsId, obj,
                com.clougence.clouddm.platform.dal.model.monitor.SecurityLevel.HIGH,
                com.clougence.clouddm.platform.dal.model.monitor.AuditType.DML_BATCH_EXECUTE,
                com.clougence.clouddm.platform.dal.model.ResourceType.DATASOURCE);
        } catch (RuntimeException e) {
            log.error("[GovTicketV2] DML_BATCH resolution audit failed for ticket {} group {} (the resume is kept)",
                ticket.getId(), trail.groupId, e);
        }
    }

    /**
     * retryGroup ghost variant: rebuild = close the old session / dispose the old task, then
     * dispatch fresh. Old-table remnants are deliberately NOT cleaned (R7 retention semantics —
     * they stay manually droppable). An ACTIVE ghost task rejects the retry (the extended
     * active check: WAIT_START/RUNNING/BLOCK/PAUSE plus the transient INIT/PAUSING/CANCELING —
     * a superset of the ruling's set, since rebuilding over a settling task could let its
     * lost-session writeback fail the FRESH dispatch through the EXECUTING CAS window).
     */
    private void retryGhostGroup(String puid, String uid, DmApprovalDO ticket, DmTicketDbStmtDO group, ApprovalMO mo) {
        long groupId = group.getId();
        // Lock-order discipline: the ticket row lock precedes ANY group-row write in this
        // transaction, so the retry never forms an AB-BA deadlock with the terminal hook
        // (ticket → group order in every ghost writer).
        approvalDal.approvalMapper().selectByIdForUpdate(ticket.getId());

        com.clougence.clouddm.platform.dal.model.execution.DmExecAsyncTaskDO oldTask
            = ghostService.findTaskForTicketGroup(ticket.getId(), groupId);
        if (oldTask != null && !isTerminalGhostTaskStatus(oldTask.getStatus())) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(
                I18nDmMsgKeys.GOV_TICKET_V2_GHOST_TASK_ACTIVE.name()));
        }

        // Reset group to PENDING (autoexec variant parity)
        ticketDbStmtDal.stmtMapper().updateExecStatus(groupId, EXEC_STATUS_PENDING, null);

        // idempotent cleanup of the previous attempt (best effort — a dead sidecar must not block
        // the rebuild; the old task is already terminal so cancelTask inside close is a no-op)
        if (oldTask != null) {
            try {
                ghostService.close(puid, uid, oldTask.getBizId());
            } catch (RuntimeException e) {
                log.warn("[GovTicketV2] old ghost session cleanup failed for group {} (rebuild continues)", groupId, e);
            }
        }

        dispatchGhostGroup(ticket, group, GHOST_VIA_RETRY);
    }

    private static boolean isTerminalGhostTaskStatus(com.clougence.clouddm.platform.dal.model.execution.AsyncTaskStatus status) {
        return status == com.clougence.clouddm.platform.dal.model.execution.AsyncTaskStatus.COMPLETE
            || status == com.clougence.clouddm.platform.dal.model.execution.AsyncTaskStatus.FAILURE
            || status == com.clougence.clouddm.platform.dal.model.execution.AsyncTaskStatus.CANCEL;
    }

    /**
     * null when NO group is routed (legacy ticketInfo shape kept byte-identical).
     * <p>
     * ⚠ DUAL-WRITE COUPLING PIN (B6 M4; B7 semantic widening, 裁决 #12): these flags are the
     * sweep/projection ENTRY GATES — their semantics widened from "ghost-routed" to <b>"HAS
     * NON-DIRECT-EXECUTION SEGMENTS" (GHOST ∨ DML_BATCH)</b>. Field name and wire shape are
     * unchanged (in-flight ticket compatibility). A DML_BATCH-only ticket that did NOT set its
     * flag would silently lose the WAIT_EXEC sweep repair (R2) — the marking seam and this
     * builder derive from the SAME authoritative evaluation, so plan and flag can never diverge.
     */
    private static List<Boolean> buildGhostRouteFlags(List<GroupPrecheck> prechecks) {
        List<Boolean> flags = new ArrayList<>(prechecks.size());
        boolean any = false;
        for (GroupPrecheck pc : prechecks) {
            boolean routed = hasRoutedMark(pc.result);
            flags.add(routed);
            any |= routed;
        }
        return any ? flags : null;
    }

    /**
     * B7: a group counts as ROUTED when its evaluation produced a GHOST mark (the B6 display
     * carrier — belt) OR its segment plan carries a DML_BATCH segment (the B7 addition —
     * braces; a GHOST route always writes a plan too, so the plan check alone would suffice,
     * keeping both preserves the exact B6 predicate for ghost groups).
     */
    private static boolean hasRoutedMark(GovTicketV2CheckVO.GroupResult gr) {
        return hasGhostRoute(gr)
            || (gr != null && com.clougence.clouddm.console.web.component.governance.TicketSegment
                .hasDmlBatchSegment(gr.getSegments()));
    }

    private static boolean hasGhostRoute(GovTicketV2CheckVO.GroupResult gr) {
        if (gr == null || gr.getGhostRoutes() == null) {
            return false;
        }
        return gr.getGhostRoutes().stream()
            .anyMatch(r -> GovTicketV2CheckVO.GhostRouteVO.ROUTE_GHOST.equals(r.getRoute()));
    }

    /**
     * resubmit rebuild of the positional flags: fresh evaluations for the modified groups,
     * retained flags for untouched (SUCCESS) groups at their positions. B7: the fresh-evaluation
     * predicate is the WIDENED hasRoutedMark (GHOST ∨ DML_BATCH — the dual-write coupling pin,
     * a modified group whose new text routes a DML_BATCH segment must set its flag or lose the
     * sweep repair, R2).
     */
    private static void rebuildGhostRouteFlags(ApprovalMO mo, List<DmTicketDbStmtDO> allGroups,
                                               Map<Long, GroupPrecheck> precheckByGroupId) {
        List<Boolean> oldFlags = mo.getGhostRouteFlags();
        List<Boolean> newFlags = new ArrayList<>(allGroups.size());
        boolean any = false;
        for (int i = 0; i < allGroups.size(); i++) {
            GroupPrecheck pc = precheckByGroupId.get(allGroups.get(i).getId());
            Boolean flag;
            if (pc != null) {
                flag = hasRoutedMark(pc.result);
            } else {
                flag = oldFlags != null && i < oldFlags.size() ? oldFlags.get(i) : Boolean.FALSE;
            }
            newFlags.add(flag);
            any |= Boolean.TRUE.equals(flag);
        }
        if (any) {
            mo.setGhostRouteFlags(newFlags);
        } else if (oldFlags != null) {
            mo.setGhostRouteFlags(null);
        }
    }

    /**
     * R15 告警放行 registration (design §6.2): ALERT_PASS marks collected during the authoritative
     * precheck are trailed as ONE ticket event (in-transaction, zero new tables) and pushed to the
     * tenant IM robots afterCommit. Switch off → no trail, no push (nothing was triggered);
     * every send-side failure degrades inside AlertPushService (never-throw).
     */
    private void registerGhostAlerts(String puid, String actorUid, long ticketId, String applicantUid,
                                     List<GroupPrecheck> prechecks) {
        List<AlertHit> hits = new ArrayList<>();
        for (GroupPrecheck pc : prechecks) {
            if (pc.result == null || pc.result.getGhostRoutes() == null) {
                continue;
            }
            for (GovTicketV2CheckVO.GhostRouteVO r : pc.result.getGhostRoutes()) {
                if (GovTicketV2CheckVO.GhostRouteVO.ROUTE_ALERT_PASS.equals(r.getRoute())) {
                    hits.add(new AlertHit(pc.result.getDbName(), r));
                }
            }
        }
        if (hits.isEmpty() || !alertPushService.isAlertPushEnabled()) {
            return;
        }
        appendGhostAlertEvent(ticketId, actorUid, hits);
        for (AlertHit hit : hits) {
            com.clougence.clouddm.console.web.component.alert.AlertContext ctx
                = new com.clougence.clouddm.console.web.component.alert.AlertContext();
            ctx.setTicketId(ticketId);
            ctx.setApplicantUid(applicantUid);
            ctx.setDbName(hit.dbName);
            ctx.setTableName(hit.route.getTableName());
            ctx.setTableRows(hit.route.getTableRows());
            ctx.setTotalBytes(hit.route.getTotalBytes());
            ctx.setSizeThresholdBytes(hit.route.getSizeThresholdBytes());
            ctx.setRowThreshold(hit.route.getRowThreshold());
            ctx.setRiskCode(hit.route.getAlertReason());
            alertPushService.pushAlert(puid,
                com.clougence.clouddm.console.web.component.alert.AlertScene.GHOST_LARGE_TABLE_NO_PK, ctx);
        }
    }

    private void appendGhostAlertEvent(long ticketId, String actorUid, List<AlertHit> hits) {
        DmDbChangeEventDO event = new DmDbChangeEventDO();
        event.setTicketId(ticketId);
        event.setEventType(GovEventType.GHOST_ALERT_TRIGGERED.name());
        event.setFromStatus(null);
        event.setToStatus(null);
        event.setOperatorUid(actorUid);

        Map<String, Object> data = new HashMap<>();
        data.put("scene", com.clougence.clouddm.console.web.component.alert.AlertScene.GHOST_LARGE_TABLE_NO_PK.name());
        List<Map<String, Object>> tables = new ArrayList<>();
        for (AlertHit hit : hits) {
            Map<String, Object> t = new HashMap<>();
            t.put("dbName", hit.dbName);
            t.put("tableName", hit.route.getTableName());
            t.put("alertReason", hit.route.getAlertReason());
            t.put("tableRows", hit.route.getTableRows());
            t.put("totalBytes", hit.route.getTotalBytes());
            tables.add(t);
        }
        data.put("tables", tables);
        event.setEventData(JsonUtils.toJson(data));

        dbChangeEventDal.eventMapper().insert(event);
    }

    /** one ALERT_PASS mark with its group dbName (the display VO carries no db). */
    private static class AlertHit {
        final String dbName;
        final GovTicketV2CheckVO.GhostRouteVO route;

        AlertHit(String dbName, GovTicketV2CheckVO.GhostRouteVO route) {
            this.dbName = dbName;
            this.route = route;
        }
    }

    // ==================== returnTicket ====================

    @Override
    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRED)
    public void returnTicket(String puid, String uid, GovTicketV2ReturnTicketFO fo) {
        returnTicketInternal(puid, uid, fo.getTicketId(), fo.getReason(), false);
    }

    @Override
    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRED)
    public void returnTicketOpenCommand(String puid, String uid, long ticketId, String reason) {
        returnTicketInternal(puid, uid, ticketId, reason, true);
    }

    /**
     * Shared return-to-REVISION logic for both the console path and the open command path.
     * <p>
     * Console path ({@code openCommand=false}): enforces the three-pronged identity check
     * (owner ∨ primary ∨ DM_DAUTH_TICKET@bindDsId) — behavior bit-for-bit unchanged (regression pin).
     * <p>
     * Open command path ({@code openCommand=true}): skips the identity check; tenant-level auth
     * (AK/SK is the credential). eventData.via = EXTERNAL_COMMAND. Status guards, REVISION transition,
     * TICKET_RETURNED event, and IM afterCommit notify are fully reused.
     */
    private void returnTicketInternal(String puid, String uid, long ticketId, String reason, boolean openCommand) {
        DmApprovalDO ticket = approvalDal.approvalMapper().queryById(ticketId);
        if (ticket == null || !puid.equals(ticket.getPrimaryUid())) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.GOV_TICKET_V2_TICKET_NOT_FOUND.name(), ticketId));
        }
        // DBA/admin proxy return (design §2): owner OR primary-account OR DM_DAUTH_TICKET@bindDsId
        // Console path only — open command path skips this (tenant match is the credential).
        if (!openCommand) {
            if (!uid.equals(ticket.getOwnerUid()) && !uid.equals(ticket.getPrimaryUid())) {
                boolean hasDsAuth = false;
                if (ticket.getBindDsId() != null) {
                    hasDsAuth = dmAuthServiceForBiz.checkResAuthWithoutError(
                        puid, uid, ticket.getBindDsId(),
                        new com.clougence.clouddm.console.web.util.DsResPathObj(ticket.getLevelPath()),
                        SecDataAuthLabel.DM_DAUTH_TICKET, AuthKind.DataSource);
                }
                if (!hasDsAuth) {
                    throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.GOV_TICKET_V2_NOT_AUTHORIZED_RETURN.name()));
                }
            }
        }
        String via = openCommand ? "EXTERNAL_COMMAND" : (uid.equals(ticket.getOwnerUid()) ? "OWNER" : "DBA");
        // Guard 1: ticket_status must be EXEC_FAIL or WAIT_EXEC (WAIT_EXEC + FAILED groups = race window)
        ApprovalStatus currentStatus = ticket.getTicketStatus();
        if (currentStatus != ApprovalStatus.EXEC_FAIL && currentStatus != ApprovalStatus.WAIT_EXEC) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.GOV_TICKET_V2_NOT_RETURNABLE.name()));
        }
        // Guard 4: approBiz==DM_CHANGE and ticketType is v2 (PRE_DDL / PROD_DML)
        if (ticket.getApproBiz() != ApprovalBiz.DM_CHANGE) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.GOV_TICKET_V2_NOT_RETURNABLE.name()));
        }
        ApprovalMO mo = JsonUtils.toObj(ticket.getTicketInfo(), ApprovalMO.class);
        String ticketType = mo != null ? mo.getTicketType() : null;
        if (!TICKET_TYPE_PRE_DDL.equals(ticketType) && !TICKET_TYPE_PROD_DML.equals(ticketType)) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.GOV_TICKET_V2_NOT_RETURNABLE.name()));
        }

        // Guard 2: at least one group exec_status=FAILED
        List<DmTicketDbStmtDO> groups = ticketDbStmtDal.stmtMapper().queryByTicketId(ticketId);
        List<Long> failedGroupIds = groups.stream()
            .filter(g -> EXEC_STATUS_FAILED.equals(g.getExecStatus()))
            .map(DmTicketDbStmtDO::getId)
            .collect(Collectors.toList());
        if (failedGroupIds.isEmpty()) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.GOV_TICKET_V2_NO_FAILED_GROUPS.name()));
        }

        // Guard 3: no stmt is already merged into a release (countBySourceStmtIds must be 0)
        List<Long> stmtIds = groups.stream().map(DmTicketDbStmtDO::getId).collect(Collectors.toList());
        int mergedCount = prodReleaseDal.stmtMapper().countBySourceStmtIds(stmtIds);
        if (mergedCount > 0) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.GOV_TICKET_V2_ALREADY_MERGED.name()));
        }

        // Action: ticket_status -> REVISION (updateStatusByEnum, NOT transitionTicketToTerminal)
        approvalStateService.updateApprovalStatus(ticketId, ApprovalStatus.REVISION, "Returned for revision");

        // Event TICKET_RETURNED (real uid, NOT SYSTEM — NOT NULL contract; via=OWNER/DBA/EXTERNAL_COMMAND additive)
        appendTicketReturnedEvent(ticketId, uid, currentStatus.name(), ApprovalStatus.REVISION.name(),
            reason, failedGroupIds, via);

        // IM notify applicant (operator != applicant when DBA proxy or external command return)
        govReturnNotifyService.notifyReturn(puid, uid, ticket.getOwnerUid(),
            ticketId, ticket.getTicketTitle(), reason, via);
    }

    // ==================== resubmit ====================

    @Override
    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRED)
    public void resubmit(String puid, String uid, GovTicketV2ResubmitFO fo) {
        resubmitInternal(puid, uid, fo, false);
    }

    @Override
    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRED)
    public void resubmitOpenCommand(String puid, String uid, GovTicketV2ResubmitFO fo) {
        resubmitInternal(puid, uid, fo, true);
    }

    /**
     * Shared resubmit logic for both the console path and the open command path.
     * <p>
     * Console path ({@code openCommand=false}): enforces the owner-only identity check
     * — behavior bit-for-bit unchanged (regression pin).
     * <p>
     * Open command path ({@code openCommand=true}): skips the owner-only check;
     * tenant-level auth (AK/SK is the credential).
     * <p>
     * External-channel tickets (approType==External, PROD_DML DIRECT) go directly to
     * WAIT_EXEC (Q2 方案 A): APPROVAL→FINISH + CONFIRM→FINISH + EXECUTION→INIT +
     * initializeExecutionProgress + per-group createGroupJob/startJob — single transaction,
     * no intermediate WAIT_CONFIRM, no approval surface rebuild. The original external
     * approval still stands (execution failure ≠ approval rejected).
     * Internal/DingTalk channels use the legacy resubmit→WAIT_APPROVAL path (regression pin).
     */
    private void resubmitInternal(String puid, String uid, GovTicketV2ResubmitFO fo, boolean openCommand) {
        long ticketId = fo.getTicketId();
        DmApprovalDO ticket = approvalDal.approvalMapper().queryById(ticketId);
        if (ticket == null || !puid.equals(ticket.getPrimaryUid())) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.GOV_TICKET_V2_TICKET_NOT_FOUND.name(), ticketId));
        }
        // Owner-only check — console path only. Open command path skips this (tenant match is the credential).
        if (!openCommand && !uid.equals(ticket.getOwnerUid())) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.GOV_TICKET_V2_NOT_OWNER.name()));
        }
        // Guard: ticket_status must be REVISION
        if (ticket.getTicketStatus() != ApprovalStatus.REVISION) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.GOV_TICKET_V2_NOT_REVISION.name()));
        }

        ApprovalMO mo = JsonUtils.toObj(ticket.getTicketInfo(), ApprovalMO.class);
        String ticketType = mo != null ? mo.getTicketType() : null;
        String approvalMode = mo != null ? mo.getApprovalMode() : null;
        boolean viaRelease = APPROVAL_MODE_VIA_RELEASE.equals(approvalMode);

        // Load all groups and build a lookup by id
        List<DmTicketDbStmtDO> allGroups = ticketDbStmtDal.stmtMapper().queryByTicketId(ticketId);
        Map<Long, DmTicketDbStmtDO> groupMap = allGroups.stream()
            .collect(Collectors.toMap(DmTicketDbStmtDO::getId, g -> g));

        // Validate: every requested groupId belongs to this ticket and is FAILED
        // (SUCCESS groups in the request -> reject entire submission)
        for (GovTicketV2ResubmitFO.GroupInput gi : fo.getGroups()) {
            DmTicketDbStmtDO group = groupMap.get(gi.getGroupId());
            if (group == null) {
                throw new ErrorMessageException(DmI18nUtils.getMessage(
                    I18nDmMsgKeys.GOV_TICKET_V2_GROUP_NOT_IN_TICKET.name(), gi.getGroupId()));
            }
            if (!EXEC_STATUS_FAILED.equals(group.getExecStatus())) {
                throw new ErrorMessageException(DmI18nUtils.getMessage(
                    I18nDmMsgKeys.GOV_TICKET_V2_SUCCESS_GROUP_NOT_EDITABLE.name(), gi.getGroupId()));
            }
        }

        // Step 1: run authoritative precheck for each modified group (blocking — any FAIL rejects whole ticket)
        List<GroupPrecheck> prechecks = new ArrayList<>();
        for (GovTicketV2ResubmitFO.GroupInput gi : fo.getGroups()) {
            DmTicketDbStmtDO group = groupMap.get(gi.getGroupId());
            // Resolve pairId from the group row (pair set is frozen during REVISION)
            GovTicketV2CheckVO.GroupResult gr = runPrecheck(puid, uid, ticketType, group.getPairId(), gi.getSqlContent());
            if ("FAIL".equals(gr.getCheckStatus())) {
                // N2: same typed shape as the submit loop (a resubmitted no-key group throws the
                // typed DmlNoKeyRejectException too — the resubmit boundary trail is a documented
                // leftover; the typed exception degrades to plain ErrorMessageException semantics
                // at the handler, so the rejection itself is identical either way)
                throwPrecheckFailure(gr);
            }
            prechecks.add(new GroupPrecheck(group.getId(), group.getPairId(), gi.getSqlContent(), gr, buildPrecheckResultJson(gr)));
        }

        // Step 2: write version audit rows (old sql_content full text, version = max+1)
        for (GovTicketV2ResubmitFO.GroupInput gi : fo.getGroups()) {
            DmTicketDbStmtDO group = groupMap.get(gi.getGroupId());
            int maxVersion = ticketDbStmtDal.revisionMapper().maxVersionByStmtId(group.getId());
            DmTicketStmtRevisionDO revision = new DmTicketStmtRevisionDO();
            revision.setTicketId(ticketId);
            revision.setStmtId(group.getId());
            revision.setVersion(maxVersion + 1);
            revision.setSqlContent(group.getSqlContent());
            revision.setOperatorUid(uid);
            revision.setReason(fo.getReason());
            ticketDbStmtDal.revisionMapper().insertRevision(revision);
        }

        // Step 3: in-place update sql_content + precheck_result + reset exec_status to PENDING (stmt id unchanged)
        for (GroupPrecheck pc : prechecks) {
            ticketDbStmtDal.stmtMapper().updateSqlForRevision(pc.groupId, pc.sqlContent, pc.precheckResultJson);
            // B6 M2 (design §6.5, contract :417 step3): the segment RUNTIME must reset together
            // with the rewritten plan — a FAILED group may carry exec_segments of the previous
            // round whose stmtRanges point into the OLD text; a stale runtime would make the
            // first-segment dispatch execute wrong statement bounds. Null = the fresh plan
            // (recomputed by the authoritative precheck above) re-initializes the runtime at
            // dispatch time. Java-side write (not folded into updateSqlForRevision) so the
            // reset stays unit-testable at the mapper seam.
            ticketDbStmtDal.stmtMapper().updateExecSegments(pc.groupId, null);
            // B7 N1b: keep the IN-MEMORY group DO consistent with the two writes above (the
            // same-transaction DO list `allGroups` was loaded BEFORE the revision). The External
            // direct-to-exec branch below dispatches from those DOs — without this sync its
            // decision order would read the STALE precheck_result (a fresh DML_BATCH plan would
            // fall through to the legacy branch) and createGroupJob would split the STALE
            // sql_content (a pre-existing staleness bug this sync closes). Mirrors the
            // persistRuntime in-memory consistency discipline of the orchestrator.
            DmTicketDbStmtDO refreshed = groupMap.get(pc.groupId);
            if (refreshed != null) {
                refreshed.setSqlContent(pc.sqlContent);
                refreshed.setPrecheckResult(pc.precheckResultJson);
                refreshed.setExecStatus(EXEC_STATUS_PENDING);
                refreshed.setExecDetail(null);
                refreshed.setExecSegments(null);
            }
        }

        // Step 4: update dm_approval ticket_status. VIA_RELEASE returns to the release ledger pool
        // (WAIT_RELEASE); External PROD_DML DIRECT goes directly to WAIT_EXEC (Q2 方案 A —
        // the original external approval still stands, execution failure ≠ approval rejected);
        // all others return to WAIT_APPROVAL where the auto-advance / human-approval chain picks up.
        GroupPrecheck first = prechecks.get(0);
        ApprovalStatus resubmitTarget;
        if (viaRelease) {
            resubmitTarget = ApprovalStatus.WAIT_RELEASE;
        } else if (ticket.getApproType() == ApprovalType.External) {
            resubmitTarget = ApprovalStatus.WAIT_EXEC;
        } else {
            resubmitTarget = ApprovalStatus.WAIT_APPROVAL;
        }
        approvalStateService.updateApprovalStatus(ticketId, resubmitTarget, "Resubmitted after revision");
        // Update rawSql to the first modified group's SQL (display-compat; levels/ticketInfo frozen — pair set unchanged).
        // Rollback SQL resubmit handling (design D7):
        //   - Applicant provided new rollback SQL → use it (rollBackSqlGenerated=false)
        //   - Blank + original was system-generated → regenerate from the new combined SQL
        //   - Blank + original was applicant-provided → keep existing value (rollBackSql not touched)
        Map<Long, GroupPrecheck> precheckByGroupId = prechecks.stream()
            .collect(Collectors.toMap(pc -> pc.groupId, pc -> pc));
        String combinedResubmitSql = allGroups.stream()
            .map(g -> {
                GroupPrecheck pc = precheckByGroupId.get(g.getId());
                return pc != null ? pc.sqlContent : g.getSqlContent();
            })
            .filter(StringUtils::isNotBlank)
            .collect(Collectors.joining(";"));
        DmApprovalDO ticketUpdate = new DmApprovalDO();
        ticketUpdate.setId(ticketId);
        ticketUpdate.setRawSql(first.sqlContent);
        if (StringUtils.isNotBlank(fo.getRollBackSql())) {
            ticketUpdate.setRollBackSql(fo.getRollBackSql());
            mo.setRollBackSqlGenerated(false);
        } else if (Boolean.TRUE.equals(mo.getRollBackSqlGenerated())) {
            String generated = rollbackSqlGenerator.generate(combinedResubmitSql);
            if (StringUtils.isNotBlank(generated)) {
                ticketUpdate.setRollBackSql(generated);
                mo.setRollBackSqlGenerated(true);
            }
        }
        // Risk assessment recompute (design D2: REVISION resubmit 重算)
        DmDsDO resubmitDs = dsDal.dsMapper().queryDsIdentityById(ticket.getBindDsId());
        String resubmitDsType = resubmitDs != null && resubmitDs.getDataSourceType() != null ? resubmitDs.getDataSourceType().name() : null;
        String resubmitEnvName = ticket.getEnvName();
        long resubmitEnvId = resubmitDs != null ? resubmitDs.getDsEnvId() : 0;
        com.clougence.clouddm.console.web.component.governance.RiskAssessment resubmitRisk
            = assessRiskFromPrechecks(puid, resubmitEnvId, resubmitEnvName, resubmitDsType, prechecks);
        mo.setRiskLevel(resubmitRisk.getLevel().name());
        mo.setImpactScope(com.clougence.utils.JsonUtils.toJson(resubmitRisk.getScope()));
        mo.setImpactScopeText(resubmitRisk.getReadableText());
        Long resubmitTotalRows = null;
        for (GroupPrecheck pc : prechecks) {
            if (pc.result.getEstimatedRows() != null) {
                if (resubmitTotalRows == null) {
                    resubmitTotalRows = 0L;
                }
                resubmitTotalRows += pc.result.getEstimatedRows();
            }
        }
        if (resubmitTotalRows != null) {
            ticketUpdate.setExpectedAffectedRows(resubmitTotalRows);
        }
        // B6 M1: rebuild the positional ghost-route flags — fresh evaluations for the modified
        // groups, retained flags for untouched (SUCCESS, non-editable) groups. The authoritative
        // precheck re-run above recomputed the marks from scratch (thresholds/table growth during
        // the revision interval are naturally absorbed). Null when nothing is routed anymore.
        rebuildGhostRouteFlags(mo, allGroups, precheckByGroupId);
        ticketUpdate.setTicketInfo(JsonUtils.toJson(mo));
        approvalDal.approvalMapper().updateById(ticketUpdate);

        // Compute recomputed changeType for all groups (used by both the External direct-to-exec path
        // and the SUBMIT event). For modified groups, use the fresh precheck changeType; for SUCCESS
        // groups (unmodified), use the stored precheck_result changeType.
        List<ChangeType> allChangeTypes = new ArrayList<>();
        for (DmTicketDbStmtDO g : allGroups) {
            GroupPrecheck pc = precheckByGroupId.get(g.getId());
            if (pc != null) {
                allChangeTypes.add(ChangeType.valueOf(pc.result.getChangeType()));
            } else {
                Map<String, Object> storedPrecheck = JsonUtils.toObj(g.getPrecheckResult(), HashMap.class);
                Object ct = storedPrecheck == null ? null : storedPrecheck.get("changeType");
                if (ct != null) {
                    allChangeTypes.add(ChangeType.valueOf(String.valueOf(ct)));
                }
            }
        }
        ChangeType ticketChangeType = ChangeType.merge(allChangeTypes);

        // Step 5 (External direct-to-WAIT_EXEC): for External-channel PROD_DML DIRECT tickets, the
        // resubmit bypasses the approval surface entirely (Q2 方案 A). The original external approval
        // still stands (execution failure ≠ approval rejected). Single-transaction, no intermediate
        // WAIT_CONFIRM, no handler.approvalApproved (no IM/release side effects). State transitions
        // mirror GovAutoAdvanceServiceImpl.systemAutoConfirmForV2 (APPROVAL→FINISH, CONFIRM→FINISH,
        // EXECUTION→INIT, initializeExecutionProgress) + per-group createGroupJob/startJob.
        if (ticket.getApproType() == ApprovalType.External && !viaRelease) {
            // APPROVAL process → FINISH (context notes the bypass; original approval record preserved)
            ApprovalStageMO approvalFinMO = new ApprovalStageMO();
            approvalFinMO.setExecMsg("External approval bypassed on resubmit (execution failure return)");
            approvalStateService.updateProcessStatus(ticketId, ApprovalStage.APPROVAL,
                ApprovalProcessStatus.FINISH, JsonUtils.toJson(approvalFinMO));

            // CONFIRM process → FINISH (SYSTEM context, mirrors confirmTicketBySystemInTransaction L1294)
            ApprovalStageMO confirmFinMO = new ApprovalStageMO();
            confirmFinMO.setExecUserName(Collections.singletonList("SYSTEM"));
            confirmFinMO.setExecMsg("External resubmit direct-to-exec (system confirm bypassed)");
            approvalStateService.updateProcessStatus(ticketId, ApprovalStage.CONFIRM,
                ApprovalProcessStatus.FINISH, JsonUtils.toJson(confirmFinMO));

            // EXECUTION process → INIT (mirrors L1307) + initializeExecutionProgress (mirrors L1308)
            ApprovalStageMO execInitMO = new ApprovalStageMO();
            execInitMO.setExecMsg("External resubmit direct-to-exec");
            approvalStateService.updateProcessStatus(ticketId, ApprovalStage.EXECUTION,
                ApprovalProcessStatus.INIT, JsonUtils.toJson(execInitMO));
            approvalStateService.initializeExecutionProgress(ticketId);

            // Per-group dispatch through the SHARED decision order (mirrors
            // GovAutoAdvanceServiceImpl — B7 N1b convergence, design §2.3).
            // B6 M1 note (design §6.3) stays valid for the FLAGS branch: a ghost mark is
            // unreachable here BY CONSTRUCTION (External-channel tickets are PROD_DML-only —
            // submit rejects the External approval type for PRE_DDL — and PROD_DML's type-purity
            // rule excludes DDL). The SEGMENTS branch, however, is LIVE for B7: a resubmitted
            // PROD_DML group can carry a DML_BATCH plan (the marking gate includes PROD_DML) —
            // without the decision order, createGroupJob over the reset (runtime-null) group
            // would execute the FULL text directly, silently bypassing the batch route (the
            // R18 绕行后门 this convergence closes). The legacy branch keeps the original
            // 6-arg createGroupJob projection byte-for-byte (regression pin).
            com.clougence.clouddm.console.web.model.fo.ticket.DmAutoExecConfigFO execConfig = buildAutoExecConfig(ticketChangeType);
            java.util.Locale locale = DmI18nUtils.getLocale();
            String languageTag = locale.toLanguageTag();
            int position = 0;
            for (DmTicketDbStmtDO group : allGroups) {
                final int groupPosition = position++;
                String execStatus = group.getExecStatus();
                if (!EXEC_STATUS_PENDING.equals(execStatus) && !EXEC_STATUS_FAILED.equals(execStatus)) {
                    continue; // skip non-eligible groups
                }
                try {
                    com.clougence.clouddm.console.web.component.governance.V2GroupDispatch.dispatch(
                        ticket, group, groupPosition, mo,
                        execConfig.isEnableTransactional(), execConfig.getErrorStrategy(), languageTag, "SYSTEM",
                        GHOST_VIA_AUTO_ROUTE,
                        ticketSegmentService, this, schemaSnapshotService,
                        (g, jobBizId, opUid) -> {
                            autoExecService.createGroupJob(g, jobBizId,
                                execConfig.isEnableTransactional(), execConfig.getErrorStrategy(),
                                languageTag, opUid);
                            autoExecService.startJob(jobBizId, opUid);
                        });
                } catch (Exception e) {
                    log.error("[GovTicketV2] Failed to dispatch group for ticket {}, group {} (External resubmit)",
                        ticketId, group.getId(), e);
                }
            }
            // Mark dispatch time so PREPARATION step closes at the real dispatch moment
            // (mirrors GovAutoAdvanceServiceImpl L206)
            approvalStateService.markExecutionDispatched(ticket.getBizId());
        }

        // Step 5 (§0 fix): PROD_DML re-approval surgery — re-establish person list + reset APPROVAL process.
        // The v1 comment below was aspirational: PROD_DML has no auto-advance chain (scheduler skips it),
        // and approvalTicket L165 physically deleted the person list. Without this surgery the ticket is
        // invisible to all approvers (canApproval=false) and approvalTicket throws NO_PERMISSION.
        // PRE_DDL needs none of this — its auto-advance chain self-heals (systemAutoApprove resets
        // APPROVAL→FINISH, confirmTicketBySystem resets CONFIRM/EXECUTION).
        // VIA_RELEASE needs none of this either — it has no approval surface to repair (no process rows,
        // no person list); it goes straight back to WAIT_RELEASE (release ledger pool).
        // External is also excluded — its direct-to-WAIT_EXEC path (above) handles all process transitions.
        if (TICKET_TYPE_PROD_DML.equals(ticketType) && !viaRelease && ticket.getApproType() != ApprovalType.External) {
            List<com.clougence.clouddm.console.web.model.vo.PrimaryUserVO> persons = approvalFlowService.queryPersons(ticketId);
            approvalPersonService.replacePersons(ticket, persons);

            ApprovalStageMO resetMO = new ApprovalStageMO();
            resetMO.setExecMsg("Resubmitted for re-approval");
            approvalStateService.updateProcessStatus(ticketId, ApprovalStage.APPROVAL, ApprovalProcessStatus.INIT, JsonUtils.toJson(resetMO));
        }

        // CONFIRM/EXECUTION process rows need NO manual reset: re-approval flows through
        // approvalApproved → WAIT_CONFIRM, then confirmTicket resets CONFIRM (L1146) and
        // EXECUTION (L1154-1162) on its own. (approval-chain.md Q5 step-by-step trace.)

        // Step 6: append new SUBMIT event with recomputed changeType (computed above before the
        // External direct-to-exec path — both paths share the same allChangeTypes/ticketChangeType).
        // For External resubmit, the SUBMIT event's toStatus is WAIT_EXEC (direct-to-exec) and
        // eventData carries via=EXTERNAL_RESUBMIT for audit distinction.
        if (ticket.getApproType() == ApprovalType.External && !viaRelease) {
            appendSubmitEvent(ticketId, uid, ticketType, allGroups.size(), ticketChangeType,
                approvalMode != null ? approvalMode : APPROVAL_MODE_DIRECT,
                "EXTERNAL_RESUBMIT", ApprovalStatus.WAIT_EXEC);
            // SYSTEM_CONFIRM event (mirrors GovAutoAdvanceServiceImpl L170-171): records the system
            // confirm bypass. fromStatus=WAIT_CONFIRM (bypassed, never actually reached), toStatus=WAIT_EXEC.
            appendSystemConfirmEvent(ticketId, uid, ticketChangeType);
        } else {
            appendSubmitEvent(ticketId, uid, ticketType, allGroups.size(), ticketChangeType,
                approvalMode != null ? approvalMode : APPROVAL_MODE_DIRECT);
        }

        // B6 M1 (R15): resubmit is an authoritative precheck path too — a modified group that
        // re-hits an alert-pass condition alerts again (same registration shape as submit; the
        // External branch is PROD_DML-only and can never carry ghost marks — harmless).
        registerGhostAlerts(puid, uid, ticketId, ticket.getOwnerUid(), prechecks);

        // Step 7: resolveChangeType now reads the LATEST SUBMIT event (GovAutoAdvanceServiceImpl
        // change) — the scheduler will pick up the new changeType on the next cycle.
        // Step 8: PRE_DDL auto-advance will fire (ticket is WAIT_APPROVAL, PRE_DDL, in IN set);
        // PROD_DML DIRECT goes to human re-approval; PROD_DML VIA_RELEASE returns to the release
        // ledger pool (WAIT_RELEASE) — no manual dispatch needed.
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
        // B6 M1 (Step 7d D0): positional route flags = the execution-time authority
        // (GhostRouteFlags contract: index i ↔ i-th group of this id-ASC list). Cheap pre-filter —
        // legacy/unrouted tickets never touch the GHOST task mapper.
        //
        // ⚠ DUAL-WRITE COUPLING PIN (B6 M4, M2 check 前瞻6; B7 semantic widening, 裁决 #12): this
        // frontend projection (BOTH the ghost AND the DML_BATCH twin below) AND the WAIT_EXEC sweep
        // repair (ChangeApprovalHandler.probeGhostGroupsIfDue) gate on GhostRouteFlags.anyRouted
        // BEFORE ever looking at exec_segments — a segmented group only reaches
        // TicketSegmentService.repairGroupUnderLock INSIDE that gate. The flags' semantics B7
        // WIDENED from "ghost-routed" to "HAS NON-DIRECT-EXECUTION SEGMENTS" (GHOST ∨ DML_BATCH;
        // field name / wire shape unchanged) — a DML_BATCH-only ticket that did not set its flag
        // would silently lose its sweep repair AND its batch progress projection (R2). The
        // coupling holds because submit/resubmit derive the flags and the segments plan from the
        // SAME authoritative evaluation run (evaluateGhostRoute ∨ evaluateDmlBatchRoute).
        // ANY future path that writes segments (plan or runtime, ANY routed type) MUST write the
        // flags in the same breath — or these pre-filters must be made segments-aware — otherwise
        // the group silently loses both its projection here and its sweep safety net.
        ApprovalMO mo = JsonUtils.toObj(ticket.getTicketInfo(), ApprovalMO.class);
        boolean anyGhostRouted = com.clougence.clouddm.console.web.component.governance.GhostRouteFlags.anyRouted(mo);
        List<GovTicketV2GroupVO> result = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            DmTicketDbStmtDO g = groups.get(i);
            GovTicketV2GroupVO vo = new GovTicketV2GroupVO();
            vo.setGroupId(g.getId());
            vo.setPairId(g.getPairId());
            vo.setDsId(g.getDsId());
            vo.setDbName(g.getDbName());
            vo.setSqlContent(g.getSqlContent());
            vo.setPrecheckResult(g.getPrecheckResult());
            vo.setExecStatus(g.getExecStatus());
            vo.setExecDetail(g.getExecDetail());
            // B6 M4: segment runtime passthrough (verbatim column, ungated — the frontend falls
            // back to the precheck_result plan and renders nothing for unsegmented groups)
            vo.setExecSegments(g.getExecSegments());
            if (anyGhostRouted
                && com.clougence.clouddm.console.web.component.governance.GhostRouteFlags.isRouted(mo, i)) {
                fillGhostTaskProjection(vo, ticketId, g.getId());
                // B7 N3: the DML_BATCH twin under the SAME widened flags gate — a mixed PRE group
                // fills BOTH projections, a DML_BATCH-only group fills only this one (the ghost
                // lookup no-ops on the absent GHOST task and vice versa)
                fillDmlBatchTaskProjection(vo, ticketId, g.getId());
            }
            result.add(vo);
        }
        return result;
    }

    /**
     * B6 M1 (Step 7d D0): additive ghost-task projection for the ticket-detail group panel —
     * the NEWEST GHOST AsyncTask bound to the group (configData ticketId/groupId binding) supplies
     * the sessionId (live progress/control channel), the cut-over old-table facts (R7) and the
     * effective tool status. Never-throw: a projection failure degrades to null ghost fields —
     * the group list itself (a plain read endpoint) must survive.
     */
    private void fillGhostTaskProjection(GovTicketV2GroupVO vo, long ticketId, long groupId) {
        try {
            com.clougence.clouddm.platform.dal.model.execution.DmExecAsyncTaskDO task
                = ghostService.findTaskForTicketGroup(ticketId, groupId);
            if (task == null) {
                return; // ghost-marked but not dispatched yet (pending approval) — nothing to project
            }
            vo.setGhostTaskId(task.getId());
            vo.setGhostSessionId(task.getBizId()); // launch contract: bizId = tool sessionId
            com.clougence.clouddm.console.web.service.ghost.asyntask.GhostAsyncTaskConfig cfg
                = parseGhostTaskConfig(task);
            if (cfg != null) {
                if (StringUtils.isNotBlank(cfg.getSessionId())) {
                    vo.setGhostSessionId(cfg.getSessionId());
                }
                vo.setGhostOldTableName(cfg.getOldTableName());
                vo.setGhostOldTableDropped(cfg.getOldTableDropped());
            }
            vo.setGhostTaskStatus(resolveGhostTaskStatus(task, cfg));
        } catch (Exception e) {
            log.warn("[GovTicketV2] ghost task projection failed for ticket {} group {} (ghost fields degrade to null)",
                ticketId, groupId, e);
        }
    }

    /** GhostServiceImpl.parseTaskConfig precedent — an unparsable configData only degrades the cfg-derived fields. */
    private static com.clougence.clouddm.console.web.service.ghost.asyntask.GhostAsyncTaskConfig parseGhostTaskConfig(
            com.clougence.clouddm.platform.dal.model.execution.DmExecAsyncTaskDO task) {
        if (StringUtils.isBlank(task.getConfigData())) {
            return null;
        }
        try {
            return JsonUtils.toObj(task.getConfigData(),
                com.clougence.clouddm.console.web.service.ghost.asyntask.GhostAsyncTaskConfig.class);
        } catch (Exception e) {
            // JsonUtils sneaky-throws the CHECKED JsonProcessingException — catch Exception
            log.warn("[GovTicketV2] ghost task configData unparsable, taskId={}", task.getId(), e);
            return null;
        }
    }

    /**
     * Effective tool-level status of the bound task: a TERMINAL AsyncTaskStatus wins over the
     * captured lastStatus — the session-lost FAILED path (GhostAsyncTask R3 ruling) terminates
     * the task without a final tool-state capture, and a stale non-terminal lastStatus would
     * otherwise keep the frontend polling a dead session forever. Non-terminal → the captured
     * lastStatus (null = not captured yet; the frontend renders INIT).
     */
    private static String resolveGhostTaskStatus(
            com.clougence.clouddm.platform.dal.model.execution.DmExecAsyncTaskDO task,
            com.clougence.clouddm.console.web.service.ghost.asyntask.GhostAsyncTaskConfig cfg) {
        com.clougence.clouddm.platform.dal.model.execution.AsyncTaskStatus status = task.getStatus();
        if (status == com.clougence.clouddm.platform.dal.model.execution.AsyncTaskStatus.COMPLETE) {
            return com.clougence.clouddm.console.web.service.ghost.GhostService.STATUS_COMPLETED;
        }
        if (status == com.clougence.clouddm.platform.dal.model.execution.AsyncTaskStatus.FAILURE) {
            return com.clougence.clouddm.console.web.service.ghost.GhostService.STATUS_FAILED;
        }
        if (status == com.clougence.clouddm.platform.dal.model.execution.AsyncTaskStatus.CANCEL) {
            return com.clougence.clouddm.console.web.service.ghost.GhostService.STATUS_ABORTED;
        }
        return cfg == null ? null : cfg.getLastStatus();
    }

    /**
     * B7 N3 (design §10, groupList 投影扩展): additive DML_BATCH projection for the ticket-detail
     * segment view — the NEWEST batch task bound to the group supplies the live checkpoint
     * (batches/rows/percent), the lag-throttle state, the control-endpoint coordinates
     * (taskId/segmentIndex/taskStatus) and the window-crash resolution material
     * (unresolvedIntent/intentSeq/idempotent/lastBatchError/skipVerdictPending). It rides the
     * existing 5s group refresh — ZERO new polling endpoints (unlike ghost there is no log tail;
     * the whole progress lives in the task configData).
     * <p>
     * Never-throw: a projection failure degrades to a null dmlBatchProgress — the group list is a
     * plain read endpoint and the segment runtime passthrough still renders. The unresolvedIntent
     * derivation is TERMINAL-GATED: a RUNNING task with an in-flight intent is a healthy batch;
     * the resolution guard's authority stays the segment summary in execSegments (N2 single-key
     * ruling) — this field is display material only. The {@code dmlBatchService == null} guard is
     * the engine-unwired degradation (the frozen GhostGroupListTest suite predates the field and
     * must keep projecting byte-identically).
     */
    private void fillDmlBatchTaskProjection(GovTicketV2GroupVO vo, long ticketId, long groupId) {
        if (dmlBatchService == null) {
            return;
        }
        try {
            com.clougence.clouddm.platform.dal.model.execution.DmExecAsyncTaskDO task
                = dmlBatchService.findTaskForTicketGroup(ticketId, groupId, null);
            if (task == null) {
                return; // batch-marked but not dispatched yet (or GHOST-only group) — nothing to project
            }
            GovTicketV2GroupVO.DmlBatchProgressVO progress = new GovTicketV2GroupVO.DmlBatchProgressVO();
            progress.setTaskId(task.getId());
            com.clougence.clouddm.platform.dal.model.execution.AsyncTaskStatus status = task.getStatus();
            progress.setTaskStatus(status == null ? null : status.name());
            progress.setPaused(status == com.clougence.clouddm.platform.dal.model.execution.AsyncTaskStatus.PAUSE);
            vo.setDmlBatchProgress(progress);

            com.clougence.clouddm.console.web.service.dmlbatch.asyntask.DmlBatchAsyncTaskConfig cfg
                = com.clougence.clouddm.console.web.service.dmlbatch.DmlBatchServiceImpl.parseTaskConfig(task);
            if (cfg == null) {
                return; // unparsable configData — the task-level fields above still stand
            }
            progress.setSegmentIndex(cfg.getSegmentIndex());
            progress.setEstimatedRows(cfg.getEstimatedRows());
            progress.setIdempotent(cfg.getIdempotent());
            boolean skipVerdictPending = com.clougence.clouddm.console.web.component.governance.TicketSegment
                .RESOLUTION_SKIP_BATCH.equals(cfg.getPendingResolution());
            progress.setSkipVerdictPending(skipVerdictPending);

            com.clougence.clouddm.console.web.service.dmlbatch.asyntask.DmlBatchCheckpoint cp = cfg.getCheckpoint();
            if (cp == null) {
                return;
            }
            progress.setBatchesDone(cp.getBatchesDone());
            progress.setRowsDone(cp.getRowsDone());
            if (cfg.getEstimatedRows() != null && cfg.getEstimatedRows() > 0) {
                long percent = cp.getRowsDone() * 100 / cfg.getEstimatedRows();
                progress.setPercentApprox((int) Math.min(100L, Math.max(0L, percent)));
            }
            if (cp.getLagState() != null) {
                GovTicketV2GroupVO.LagStateVO lag = new GovTicketV2GroupVO.LagStateVO();
                lag.setVisible(cp.getLagState().isVisible());
                lag.setLastLagSec(cp.getLagState().getLastLagSec());
                lag.setThrottled(cp.getLagState().getThrottledAt() != null);
                progress.setLagState(lag);
            }
            com.clougence.clouddm.console.web.service.dmlbatch.asyntask.DmlBatchCheckpoint.Intent intent
                = cp.getIntent();
            if (intent != null) {
                progress.setRetryPending(intent.getRetryPending());
                progress.setLastBatchError(intent.getLastBatchError());
            }
            boolean terminal = status == com.clougence.clouddm.platform.dal.model.execution.AsyncTaskStatus.COMPLETE
                || status == com.clougence.clouddm.platform.dal.model.execution.AsyncTaskStatus.FAILURE
                || status == com.clougence.clouddm.platform.dal.model.execution.AsyncTaskStatus.CANCEL;
            if (terminal) {
                // the carrier mirror of the summary marker logic (isUnresolvedIntent ∨
                // isSkipVerdictPending — the N2 check re-freeze included): intent ∧ ¬retryPending
                // is the true window crash; an unconsumed SKIP verdict means the adjudicated batch
                // was neither skipped nor proven (intentSeq = batchesDone+1 by construction)
                boolean unresolved = (intent != null && !Boolean.TRUE.equals(intent.getRetryPending()))
                    || skipVerdictPending;
                progress.setUnresolvedIntent(unresolved);
                if (unresolved) {
                    progress.setIntentSeq(intent != null && !Boolean.TRUE.equals(intent.getRetryPending())
                        ? intent.getSeq() : cp.getBatchesDone() + 1);
                }
            }
        } catch (Exception e) {
            log.warn("[GovTicketV2] dml batch projection failed for ticket {} group {} (dmlBatchProgress degrades to null)",
                ticketId, groupId, e);
            vo.setDmlBatchProgress(null);
        }
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

        // Full-width character lint (fail-open; switch-controlled)
        try {
            List<FullWidthSqlLint.Hit> fwHits = FullWidthSqlLint.scan(sqlContent);
            if (!fwHits.isEmpty()) {
                if (isFullWidthLintBlock()) {
                    result.setCheckStatus("FAIL");
                    int limit = Math.min(fwHits.size(), 5);
                    List<String> msgs = new ArrayList<>();
                    for (int k = 0; k < limit; k++) {
                        msgs.add(FullWidthSqlLint.formatHit(fwHits.get(k)));
                    }
                    result.setErrorMessage(String.join("\n", msgs));
                    return result;
                } else {
                    log.debug("[GovTicketV2] Full-width lint hit but switch off (pairId={}, hits={})", pairId, fwHits.size());
                }
            }
        } catch (Exception e) {
            log.warn("[GovTicketV2] Full-width lint scan error for pair {}, fail-open", pairId, e);
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

        // Step 2: Type purity check — PROD_DML must be pure DML; PRE tickets may mix DDL+DML so the
        // frozen release snapshot can replay the whole pre-statement set in production.
        if (TICKET_TYPE_PROD_DML.equals(ticketType) && hasDdl) {
            result.setCheckStatus("FAIL");
            result.setErrorMessage(DmI18nUtils.getMessage(I18nDmMsgKeys.GOV_TICKET_V2_TYPE_MISMATCH.name(), result.getDbName(), "DDL", "DML"));
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

        // Step 4: EXPLAIN row estimation (design D3) — degradation contract: never blocks submission.
        // B7: the EstimationResult is kept as a local — Step 6 consumes the ADDITIVE per-statement
        // exposure for its per-statement threshold attribution (the group total alone is unsound
        // for multi-statement groups; the legacy estimatedRows/rowsEstimated contract is untouched).
        com.clougence.clouddm.console.web.component.governance.SqlRowEstimator.EstimationResult estResult = null;
        try {
            estResult = sqlRowEstimator.estimate(sqlContent, dsConfig, dsLevels, uid, dsId);
            result.setEstimatedRows(estResult.estimatedRows());
            result.setRowsEstimated(estResult.rowsEstimated());
        } catch (Exception e) {
            log.warn("[GovTicketV2] Row estimation failed for pair {}, degraded to unknown", pairId, e);
            result.setRowsEstimated(false);
        }

        // Step 5 (B6 M1, design §6.1): ghost auto-route evaluation — PRE_DDL only, MySQL only,
        // ghostAutoRoute kill-switch gated (off → no evaluation/marking/alerting at all). The
        // shared path (/check preview + submit + resubmit) recomputes the marks from scratch on
        // every run (idempotent). Degradation contract like Step 4: an evaluation failure keeps
        // the status quo and never blocks submission — EXCEPT the M1-transition multi-statement
        // intercept which is a deliberate FAIL (design §6.10 boundary matrix).
        // B7: the outcome (routed candidates) is RETURNED — the segment plan build moved to the
        // combined builder below (ghost ∨ DML_BATCH), output for ghost-only groups unchanged.
        GhostRouteOutcome ghostOutcome = GhostRouteOutcome.EMPTY;
        if (TICKET_TYPE_PRE_DDL.equals(ticketType)
                && dsDO.getDataSourceType() == com.clougence.clouddm.base.metadata.ds.DataSourceType.MySQL
                && isGhostAutoRouteEnabled()) {
            try {
                ghostOutcome = evaluateGhostRoute(uid, dsDO, dsConfig, result, scripts, dbName);
            } catch (RuntimeException e) {
                log.warn("[GovTicketV2] ghost route evaluation failed for pair {}, status quo kept", pairId, e);
                ghostOutcome = GhostRouteOutcome.EMPTY;
            }
            if ("FAIL".equals(result.getCheckStatus())) {
                return result;
            }
        }

        // Step 6 (B7 N1b, design §9 marking seam): DML batch auto-route evaluation —
        // (PROD_DML ∨ PRE_DDL) ∧ {MySQL ≥ 8.0, PG ≥ 15} (the version gate is CONSOLE-side —
        // N1a handover obligation #2: the rewrite SPI does not gate versions) ∧ dmlBatchAutoRoute
        // kill switch (fail-safe read). Shared-path + idempotent-recompute discipline as Step 5;
        // OUTER degradation contract as Step 5 (handover obligation #3: analyzeDmlForBatch only
        // catches RuntimeException — Errors pierce; evaluation infrastructure failure = warn +
        // status quo, NEVER a ticket FAIL). The ONLY deliberate FAIL is the DML_NO_KEY rejection
        // (裁决 #5 — a normal evaluation RESULT, not an exception; alert + audit hooks are N2).
        DmlBatchOutcome dmlOutcome = DmlBatchOutcome.EMPTY;
        // the dmlBatchService null guard = engine-unwired degradation (the status-quo direct
        // chain — same fail-safe direction as the kill switch; production Spring always injects,
        // the legacy pin suites predate the field and must keep evaluating byte-identically)
        if ((TICKET_TYPE_PROD_DML.equals(ticketType) || TICKET_TYPE_PRE_DDL.equals(ticketType))
                && dmlBatchService != null
                && dmlBatchService.isBatchRouteSupportedDs(dsDO)
                && dmlBatchService.isAutoRouteEnabled()) {
            try {
                dmlOutcome = evaluateDmlBatchRoute(uid, dsDO, dsConfig, dsLevels, result, scripts, dbName, estResult);
            } catch (RuntimeException e) {
                log.warn("[GovTicketV2] DML batch route evaluation failed for pair {}, status quo kept", pairId, e);
                dmlOutcome = DmlBatchOutcome.EMPTY;
            }
            if ("FAIL".equals(result.getCheckStatus())) {
                return result;
            }
        }

        // Combined segment plan (B7: the B6 write condition "≥1 GHOST segment" widens to
        // "≥1 GHOST ∨ DML_BATCH segment" — a pure-normal group keeps the legacy precheck_result
        // shape byte-identical, regression pin). 1-based exec_order domain, global-with-holes
        // numbering (R11) — a routed statement consumes its number without a task row.
        List<com.clougence.clouddm.console.web.component.governance.TicketSegment> plan
            = buildSegmentPlan(scripts.size(), ghostOutcome, dmlOutcome);
        if (plan != null) {
            result.setSegments(plan);
        }

        result.setCheckStatus("PASS");
        return result;
    }

    /**
     * B6 M1+M2 ghost auto-route evaluation of one PRE_DDL group (design §6.1/§6.10 boundary matrix):
     * <ul>
     * <li>ALTER + hard checks pass (PK/UK + direct connection) + ≥ threshold → route mark GHOST
     *     AND one GHOST segment in the segment plan (M2 — the single-ALTER group is the
     *     single-GHOST-segment degenerate shape; the M1 multi-statement FAIL intercept is LIFTED,
     *     consecutive plain statements merge into AUTOEXEC segments around each GHOST segment);</li>
     * <li>≥ threshold but NOT lock-free capable (no PK/UK ∨ SSH/SSL datasource) → ALERT_PASS:
     *     the statement STAYS in its AUTOEXEC segment (direct execution) and the submit path
     *     pushes the IM alert (R15 告警放行);</li>
     * <li>&lt; threshold / degraded probe / non-ALTER → status quo (no marks, no alerts).</li>
     * </ul>
     * Cost = one channel-C probe per ALTER statement (R6 ruling: 10s timeout, degrade, no cache).
     * <p>
     * B7 refactor: the routed candidates are RETURNED (GhostRouteOutcome) instead of building the
     * segment plan in-place — the plan is now built ONCE by the combined builder (ghost ∨
     * DML_BATCH, write condition "≥1 routed segment"). The ghost-only build output is unchanged
     * byte-for-byte (the same segIdx assignment / autoexec merging / field freeze).
     */
    private GhostRouteOutcome evaluateGhostRoute(String uid, com.clougence.clouddm.platform.dal.model.datasource.DmDsDO dsDO,
                                    DataSourceConfig dsConfig, GovTicketV2CheckVO.GroupResult result,
                                    List<SplitScript> scripts, String dbName) {
        // ALTER candidates in the 1-based exec_order domain (R11: SplitScript.index + 1 — the
        // preview index elsewhere in this method is 0-based, do not mix the two domains)
        List<GhostAlterCandidate> alters = new ArrayList<>();
        for (SplitScript script : scripts) {
            Set<SplitQueryType> types = script.getType();
            if (types == null || !types.contains(SplitQueryType.ALTER_TABLE)) {
                continue;
            }
            try {
                com.clougence.clouddm.console.web.service.ghost.GhostAlterStmtChecker.AlterStmtTarget target
                    = com.clougence.clouddm.console.web.service.ghost.GhostAlterStmtChecker.check(script.getScript(), dbName);
                alters.add(new GhostAlterCandidate((int) script.getIndex() + 1, target.getTableName(), script.getScript()));
            } catch (com.clougence.clouddm.console.web.service.ghost.GhostAlterStmtChecker.AlterStmtReject reject) {
                // exotic ALTER shapes (comments / non-[\w$] identifiers / schema mismatch) are not
                // ghost-routable — they stay on the direct chain (宁可误拒不可误放)
                log.debug("[GovTicketV2] statement {} is not ghost-routable: {}", script.getIndex(), reject.getDetail());
            }
        }
        if (alters.isEmpty() || dsConfig == null) {
            return GhostRouteOutcome.EMPTY;
        }

        // ds-level hard check (in-memory, zero SQL): gh-ost speaks the plain MySQL wire protocol
        boolean tunnelBlocked = Boolean.TRUE.equals(dsConfig.getSshProxyEnabled())
            || (dsConfig.getSslMode() != null
                && dsConfig.getSslMode() != com.clougence.clouddm.base.metadata.ds.SslMode.DISABLED);

        List<GovTicketV2CheckVO.GhostRouteVO> routes = new ArrayList<>();
        List<GhostAlterCandidate> ghostRouted = new ArrayList<>();
        Map<Integer, Long> rowsByStmtIdx = new HashMap<>();
        for (GhostAlterCandidate alter : alters) {
            com.clougence.clouddm.console.web.model.vo.ghost.GhostRouteProbe probe
                = ghostService.probeForRoute(uid, dsDO, dsConfig, dbName, alter.tableName);
            if (!probe.isProbed() || !probe.isExceeded()) {
                continue; // degraded probe or below threshold → status quo
            }
            if (tunnelBlocked) {
                routes.add(buildGhostRouteVO(dbName, alter, probe,
                    GovTicketV2CheckVO.GhostRouteVO.ROUTE_ALERT_PASS,
                    com.clougence.clouddm.console.web.component.alert.AlertContext.RISK_TUNNEL));
                continue;
            }
            if (!probe.isUniqueKeyPresent()) {
                routes.add(buildGhostRouteVO(dbName, alter, probe,
                    GovTicketV2CheckVO.GhostRouteVO.ROUTE_ALERT_PASS,
                    com.clougence.clouddm.console.web.component.alert.AlertContext.RISK_NO_UNIQUE_KEY));
                continue;
            }
            // ≥ threshold + hard checks pass → GHOST mark + GHOST segment (M2 segmentation —
            // multi-statement groups are NO LONGER intercepted; ALERT_PASS statements above
            // stay inside their AUTOEXEC segments and never produce a GHOST segment)
            routes.add(buildGhostRouteVO(dbName, alter, probe,
                GovTicketV2CheckVO.GhostRouteVO.ROUTE_GHOST, null));
            ghostRouted.add(alter);
            rowsByStmtIdx.put(alter.stmtIndex, probe.getTableRows());
        }
        if (!routes.isEmpty()) {
            result.setGhostRoutes(routes);
        }
        return new GhostRouteOutcome(ghostRouted, rowsByStmtIdx);
    }

    /**
     * M2 segment plan (design §6.1), B7-generalized (design §9): consecutive plain statements
     * merge into ONE AUTOEXEC segment, every ROUTED statement (ghost ALTER ∨ DML_BATCH
     * UPDATE/DELETE) becomes its own segment — [普通×2, GHOST, 普通] yields 3 segments,
     * [大 DML, 普通, 大 ALTER] yields 4. All bounds are 1-based in the exec_order domain (R11);
     * a routed statement consumes its global number WITHOUT a task row (the exec_order hole,
     * stmt_index contract). The routed segment carries the FULL frozen statement text so the
     * execution-time dispatch is self-contained (sql_content is frozen between precheck and
     * execution — same splitter + same text ⇒ positional validity).
     * <p>
     * Returns null when NOTHING routed — the plan is written ONLY when ≥1 GHOST ∨ DML_BATCH
     * segment exists (B6 write condition widened; a pure-normal group keeps the legacy
     * precheck_result shape byte-identical, regression pin). The ghost-only build output is
     * byte-identical to the pre-B7 builder (same segIdx assignment order, same field freeze).
     */
    private static List<com.clougence.clouddm.console.web.component.governance.TicketSegment> buildSegmentPlan(
            int totalStmts, GhostRouteOutcome ghost, DmlBatchOutcome dml) {
        // merge the routed prototypes by 1-based stmtIndex (an ALTER and an UPDATE/DELETE can
        // never share a statement position — the TreeMap key is collision-free by construction)
        java.util.TreeMap<Integer, com.clougence.clouddm.console.web.component.governance.TicketSegment> routedByStmt
            = new java.util.TreeMap<>();
        for (GhostAlterCandidate candidate : ghost.routed) {
            routedByStmt.put(candidate.stmtIndex,
                com.clougence.clouddm.console.web.component.governance.TicketSegment.ghostPlan(
                    0, candidate.stmtIndex, candidate.tableName,
                    ghost.rowsByStmtIdx.get(candidate.stmtIndex), candidate.sql));
        }
        for (DmlBatchCandidate candidate : dml.routed) {
            routedByStmt.put(candidate.stmtIndex, candidate.prototype);
        }
        if (routedByStmt.isEmpty()) {
            return null;
        }
        List<com.clougence.clouddm.console.web.component.governance.TicketSegment> plan = new ArrayList<>();
        int segIdx = 1;
        int runStart = 1;
        for (Map.Entry<Integer, com.clougence.clouddm.console.web.component.governance.TicketSegment> entry
                : routedByStmt.entrySet()) {
            int stmtIndex = entry.getKey();
            if (stmtIndex > runStart) {
                plan.add(com.clougence.clouddm.console.web.component.governance.TicketSegment
                    .autoexecPlan(segIdx++, runStart, stmtIndex - 1));
            }
            com.clougence.clouddm.console.web.component.governance.TicketSegment prototype = entry.getValue();
            prototype.setSegIdx(segIdx++);
            plan.add(prototype);
            runStart = stmtIndex + 1;
        }
        if (runStart <= totalStmts) {
            plan.add(com.clougence.clouddm.console.web.component.governance.TicketSegment
                .autoexecPlan(segIdx, runStart, totalStmts));
        }
        return plan;
    }

    /**
     * B7 N1b marking seam (design §9/§13 boundary matrix): evaluates every UPDATE/DELETE
     * statement of the group for lock-free keyset batch routing. Per-candidate pipeline
     * (design §0 order — shape gate FIRST, it is the cheap local AST step):
     * <ol>
     * <li><b>shape gate</b> ({@code RewriteSpi.analyzeDmlForBatch}, N1a whitelist — single-table
     *     WHERE-carrying UPDATE/DELETE, no CTE/PARTITION/ORDER BY/LIMIT/SET-subquery/indexHint/
     *     comment/multi-statement/session-variable, db prefix = group dbName): rejected → stays
     *     direct; a grey hint rides along ONLY when the statement is confirmed over-threshold by
     *     its own EXPLAIN estimate (an unestimated small statement stays silent — the direct
     *     chain is today's behavior);</li>
     * <li><b>threshold attribution</b> (裁决 #6 combined decision, PER STATEMENT): the additive
     *     per-statement EXPLAIN estimate when available ({@code ≥ dmlRowThreshold} → exceeded);
     *     an unestimated statement falls back to the probed table rows after the key probe.
     *     Below threshold → status quo, ZERO remote calls (R6 cost discipline: probe only what
     *     may route). Group pre-gate: every candidate estimated ∧ group total &lt; threshold →
     *     the whole evaluation short-circuits (each statement's estimate ≤ the total);</li>
     * <li><b>key probe</b> (channel C, never-throw): degraded/absent table → grey hint, NEVER a
     *     rejection (探测不可得 ≠ 违规); confirmed NO usable key (no PK ∧ no single-column NOT
     *     NULL UK — nullable-UK-only counts as no key, N1a handover obligation #1) ∧ exceeded →
     *     <b>group FAIL DML_NO_KEY</b> (裁决 #5 — the step's ONLY deliberate FAIL; alert + audit
     *     are N2 controller-boundary hooks); key exists but outside the whitelist (composite /
     *     float / exotic) → yellow flag, direct execution;</li>
     * <li><b>route</b>: freeze sql/batchKey/estimatedRows/idempotent into a DML_BATCH segment
     *     prototype + the ROUTE_ANNOUNCE yellow flag (batch size / approx batches / the
     *     partial-success &amp; no-auto-rollback &amp; snapshot semantics copy) + the
     *     NONIDEMPOTENT risk line when the shape analysis found a self-referencing SET or a
     *     non-deterministic function.</li>
     * </ol>
     * R13 display-level anti-gaming: same-table Σ of the estimated statements ≥ threshold with no
     * statement individually routed → a group-level SAME_TABLE_ACCUMULATE hint (never a route).
     * <p>
     * Display carrier = {@code precheck_result.dmlBatchRoutes} (additive key, contract :404); the
     * execution-time authority stays the segments plan + the positional flags (dual-write pin).
     */
    private DmlBatchOutcome evaluateDmlBatchRoute(String uid,
            com.clougence.clouddm.platform.dal.model.datasource.DmDsDO dsDO, DataSourceConfig dsConfig,
            com.clougence.clouddm.console.web.component.dsconfig.mode.DsLevels dsLevels,
            GovTicketV2CheckVO.GroupResult result, List<SplitScript> scripts, String dbName,
            com.clougence.clouddm.console.web.component.governance.SqlRowEstimator.EstimationResult estResult) {
        DmlBatchOutcome outcome = new DmlBatchOutcome();
        long threshold = dmlBatchService.rowThreshold();

        // candidates: UPDATE/DELETE statements, 1-based exec_order domain (R11 — SplitScript.index+1)
        List<SplitScript> candidates = new ArrayList<>();
        for (SplitScript script : scripts) {
            Set<SplitQueryType> types = script.getType();
            if (types != null && (types.contains(SplitQueryType.UPDATE) || types.contains(SplitQueryType.DELETE))) {
                candidates.add(script);
            }
        }
        if (candidates.isEmpty()) {
            return outcome;
        }

        // per-statement EXPLAIN estimates (SqlRowEstimator additive exposure; null = unestimated)
        Map<Integer, Long> perStmt = new HashMap<>();
        if (estResult != null && estResult.perStatement() != null) {
            for (com.clougence.clouddm.console.web.component.governance.SqlRowEstimator.StmtEstimation est
                    : estResult.perStatement()) {
                perStmt.put(est.stmtIndex(), est.rows());
            }
        }

        // cheap group-level pre-gate (zero remote calls): every candidate estimated ∧ total <
        // threshold → no statement can reach it (each estimate ≤ the total) → status quo
        boolean allEstimated = true;
        for (SplitScript script : candidates) {
            if (perStmt.get((int) script.getIndex() + 1) == null) {
                allEstimated = false;
                break;
            }
        }
        if (allEstimated && estResult != null && estResult.estimatedRows() != null
                && estResult.estimatedRows() < threshold) {
            return outcome; // 未超阈值 → 直接执行(现状不变)
        }

        // shape-gate SPI (the DmlExplainPreInitHandler acquisition precedent; the dialect gate
        // passed — a null SPI here is defensive only)
        com.clougence.clouddm.sdk.sql.SqlEngineSpi sqlEngine = dmDsConfigService.fetchSqlEngineSpi(dsConfig);
        com.clougence.clouddm.sdk.sql.SqlParserParameters parameters
            = dmDsConfigService.fetchSqlParserParameters(dsConfig, dsLevels.levelsParam());
        com.clougence.clouddm.sdk.sql.editor.rewrite.RewriteSpi rewriteSpi
            = sqlEngine == null ? null : sqlEngine.rewriteSpi(parameters);
        if (rewriteSpi == null) {
            return outcome; // dialect without batch-rewrite support — status quo (default SPI contract)
        }
        com.clougence.clouddm.console.web.service.dmlbatch.DmlBatchEffectiveParams params
            = dmlBatchService.resolveEffectiveParams();

        List<GovTicketV2CheckVO.DmlBatchRouteVO> routes = new ArrayList<>();
        Map<String, Long> accumulateByTable = new java.util.LinkedHashMap<>();
        Set<String> routedTables = new java.util.HashSet<>();

        // B7 N3 (N1a handover obligation #2): the group-level session-state gate — computed ONCE
        // per group (local, zero remote cost); consumed at the ROUTE step below
        boolean groupHasSessionState = groupCarriesSessionState(scripts);

        for (SplitScript script : candidates) {
            int stmtIndex = (int) script.getIndex() + 1; // 1-based exec_order domain (R11)
            Long stmtRows = perStmt.get(stmtIndex);

            // 1. shape gate (local AST, zero remote cost) — fresh RewriteContext PER CALL with
            //    schemaName = the group dbName (N1a handover obligation #6)
            com.clougence.clouddm.sdk.sql.editor.rewrite.RewriteContext ctx
                = new com.clougence.clouddm.sdk.sql.editor.rewrite.RewriteContext();
            ctx.setParameters(parameters);
            ctx.setSchemaName(dbName);
            com.clougence.clouddm.sdk.sql.editor.rewrite.DmlBatchShapeMO shape
                = rewriteSpi.analyzeDmlForBatch("precheck-" + stmtIndex, script.getScript(), ctx);
            if (shape == null) {
                continue; // dialect unsupported (defensive — the version gate already passed)
            }
            if (!shape.isSupported()) {
                // grey hint ONLY for a confirmed over-threshold statement (its own estimate);
                // unestimated/小 statements stay silent — 能力限制非违规, direct chain is today's behavior
                if (stmtRows != null && stmtRows >= threshold) {
                    GovTicketV2CheckVO.DmlBatchRouteVO shapeVO = buildDmlRouteVO(stmtIndex,
                        GovTicketV2CheckVO.DmlBatchRouteVO.ROUTE_DIRECT_SHAPE_UNSUPPORTED, shape, stmtRows, null,
                        threshold, DmI18nUtils.getMessage(I18nDmMsgKeys.DML_BATCH_SHAPE_UNSUPPORTED.name(),
                            String.valueOf(shape.getRejectCode())));
                    shapeVO.setRejectCode(shape.getRejectCode());
                    routes.add(shapeVO);
                }
                continue;
            }

            String table = shape.getTableName();
            if (stmtRows != null) {
                accumulateByTable.merge(table, stmtRows, Long::sum); // R13 bookkeeping (estimated statements only)
                if (stmtRows < threshold) {
                    continue; // below threshold → status quo, no probe (R6 cost discipline)
                }
            }

            // 2/3. shape passed ∧ (estimate ≥ threshold ∨ estimate unavailable) → the ONE channel-C
            //      probe per statement (key + B4-caliber table rows; never-throw degradation)
            com.clougence.clouddm.console.web.service.dmlbatch.DmlBatchKeyProbe probe
                = dmlBatchService.probeForKey(uid, dsDO, dsConfig, dbName, shape.getSchemaName(), table);
            if (!probe.isProbed() || !probe.isTableFound()) {
                routes.add(buildDmlRouteVO(stmtIndex,
                    GovTicketV2CheckVO.DmlBatchRouteVO.ROUTE_DIRECT_PROBE_DEGRADED, shape, stmtRows, null, threshold,
                    DmI18nUtils.getMessage(I18nDmMsgKeys.DML_BATCH_PROBE_DEGRADED.name(), table)));
                continue;
            }

            // threshold attribution: the estimate wins; fallback = the probed table rows (裁决 #6)
            long signalRows;
            if (stmtRows != null) {
                signalRows = stmtRows;
            } else {
                Long tableRows = probe.getTableRows();
                if (tableRows == null || tableRows < threshold) {
                    continue; // below threshold under the fallback → status quo
                }
                signalRows = tableRows;
            }

            if (com.clougence.clouddm.console.web.service.dmlbatch.DmlBatchKeyProbe.STATE_NO_KEY
                    .equals(probe.getKeyState())) {
                // 超阈值 ∧ 形状门通过 ∧ 确认无可用键 → 组级 FAIL (裁决 #5; the /check red error +
                // submit whole-ticket rejection ride the existing precheck-FAIL machinery)
                result.setCheckStatus("FAIL");
                result.setErrorMessage(DmI18nUtils.getMessage(
                    I18nDmMsgKeys.GOV_TICKET_V2_DML_NO_KEY_REJECT.name(),
                    dbName + "." + table, display(signalRows), display(threshold),
                    probe.getKeyDetail() == null ? "-" : probe.getKeyDetail()));
                // N2 (design §8.2, 未决⑦ 定形): the typed rejection payload — failCode +
                // context ride the GroupResult so the SUBMIT boundary can throw the typed
                // DmlNoKeyRejectException (the controller-boundary alert + DML_BATCH_REJECT
                // audit fire OUTSIDE the rolled-back transaction off this carrier). /check
                // exposes both keys additively (red error rendering unchanged).
                result.setFailCode(GovTicketV2CheckVO.FAIL_CODE_DML_NO_KEY);
                GovTicketV2CheckVO.DmlNoKeyRejectVO reject = new GovTicketV2CheckVO.DmlNoKeyRejectVO();
                reject.setDsId(dsDO.getId());
                reject.setDbName(dbName);
                reject.setTableName(table);
                reject.setEstimatedRows(signalRows);
                reject.setRowThreshold(threshold);
                reject.setKeyDetail(probe.getKeyDetail());
                reject.setStmtIndex(stmtIndex);
                String rejectedSql = script.getScript();
                reject.setSqlSummary(rejectedSql != null && rejectedSql.length()
                    > com.clougence.clouddm.console.web.model.vo.dmlbatch.DmlBatchOpAuditObj.SQL_MAX_LENGTH
                        ? rejectedSql.substring(0,
                            com.clougence.clouddm.console.web.model.vo.dmlbatch.DmlBatchOpAuditObj.SQL_MAX_LENGTH)
                        : rejectedSql);
                result.setDmlNoKeyReject(reject);
                return outcome; // rejected — submit refuses the whole ticket; nothing else to evaluate
            }
            if (!com.clougence.clouddm.console.web.service.dmlbatch.DmlBatchKeyProbe.STATE_OK
                    .equals(probe.getKeyState())) {
                // key exists but outside the v1 whitelist (composite/float/exotic) → yellow, direct
                GovTicketV2CheckVO.DmlBatchRouteVO keyVO = buildDmlRouteVO(stmtIndex,
                    GovTicketV2CheckVO.DmlBatchRouteVO.ROUTE_DIRECT_KEY_UNSUPPORTED, shape, signalRows,
                    probe.getTableRows(), threshold,
                    DmI18nUtils.getMessage(I18nDmMsgKeys.DML_BATCH_KEY_UNSUPPORTED.name(), table,
                        probe.getKeyDetail() == null ? "-" : probe.getKeyDetail()));
                keyVO.setKeyDetail(probe.getKeyDetail());
                routes.add(keyVO);
                continue;
            }

            // N3 group-level session-state downgrade (N1a handover obligation #2): every tick of
            // the batch loop runs in a FRESH channel-C session, so a group carrying SET/session-
            // state statements cannot be batched soundly (PG `SET search_path` + unqualified names
            // = cross-session resolution drift; MySQL `SET @v` feeding a later statement — an @v
            // reference INSIDE this statement is already shape-gate rejected, this covers the
            // clean statement that still relies on a group mate). The WHOLE GROUP gives up
            // DML_BATCH routing; the statement stays direct with the display key. The NO_KEY
            // rejection above is deliberately UNAFFECTED (the §9.8.3 violation of an
            // over-threshold keyless DML stands regardless of routing).
            if (groupHasSessionState) {
                GovTicketV2CheckVO.DmlBatchRouteVO sessionVO = buildDmlRouteVO(stmtIndex,
                    GovTicketV2CheckVO.DmlBatchRouteVO.ROUTE_DIRECT_GROUP_SESSION_STATE, shape, signalRows,
                    probe.getTableRows(), threshold,
                    DmI18nUtils.getMessage(I18nDmMsgKeys.DML_BATCH_GROUP_SESSION_STATE.name(), table));
                routes.add(sessionVO);
                routedTables.add(table); // the per-statement display supersedes the accumulate hint
                continue;
            }

            // 4. ROUTE — freeze the segment prototype (sql/batchKey/estimatedRows/idempotent)
            boolean idempotent = !shape.isSelfReferencingSet()
                && (shape.getNondeterministicFunctions() == null || shape.getNondeterministicFunctions().isEmpty());
            com.clougence.clouddm.console.web.component.governance.TicketSegment.BatchKey batchKey
                = new com.clougence.clouddm.console.web.component.governance.TicketSegment.BatchKey(
                    probe.getKeyColumn(), probe.getKeyType());
            com.clougence.clouddm.console.web.component.governance.TicketSegment prototype
                = com.clougence.clouddm.console.web.component.governance.TicketSegment.dmlBatchPlan(
                    0, stmtIndex, table, signalRows, script.getScript(), batchKey, idempotent);
            outcome.routed.add(new DmlBatchCandidate(stmtIndex, prototype));
            routedTables.add(table);

            GovTicketV2CheckVO.DmlBatchRouteVO vo = buildDmlRouteVO(stmtIndex,
                GovTicketV2CheckVO.DmlBatchRouteVO.ROUTE_DML_BATCH, shape, signalRows, probe.getTableRows(),
                threshold, null);
            vo.setKeyColumn(probe.getKeyColumn());
            vo.setKeyType(probe.getKeyType());
            vo.setIdempotent(idempotent);
            vo.setBatchSize(params.getBatchSize());
            long batches = Math.max(1L, (signalRows + params.getBatchSize() - 1) / params.getBatchSize());
            vo.setApproxBatches(batches);
            // Announcement caliber (task 10-11 M5, copy-only): when the per-statement estimate was
            // unavailable and signalRows fell back to the probed TABLE SCALE, say so — the number
            // must not masquerade as an affected-rows estimate. Routing decision is untouched.
            String announceKey = stmtRows != null
                ? I18nDmMsgKeys.DML_BATCH_ROUTE_ANNOUNCE.name()
                : I18nDmMsgKeys.DML_BATCH_ROUTE_ANNOUNCE_TABLE_SCALE.name();
            StringBuilder announce = new StringBuilder(DmI18nUtils.getMessage(
                announceKey, table, display(signalRows), display(threshold),
                probe.getKeyColumn(), String.valueOf(params.getBatchSize()), String.valueOf(batches)));
            if (!idempotent) {
                announce.append('\n').append(DmI18nUtils.getMessage(
                    I18nDmMsgKeys.DML_BATCH_NONIDEMPOTENT_RISK.name()));
            }
            vo.setMessage(announce.toString());
            routes.add(vo);
        }

        // R13 display-level anti-gaming: same-table accumulation ≥ threshold, nothing routed on it
        for (Map.Entry<String, Long> entry : accumulateByTable.entrySet()) {
            if (!routedTables.contains(entry.getKey()) && entry.getValue() >= threshold) {
                GovTicketV2CheckVO.DmlBatchRouteVO vo = buildDmlRouteVO(0,
                    GovTicketV2CheckVO.DmlBatchRouteVO.ROUTE_SAME_TABLE_ACCUMULATE, null, entry.getValue(), null,
                    threshold, DmI18nUtils.getMessage(I18nDmMsgKeys.DML_BATCH_SAME_TABLE_ACCUMULATE.name(),
                        entry.getKey(), display(entry.getValue()), display(threshold)));
                vo.setTableName(entry.getKey());
                routes.add(vo);
            }
        }

        if (!routes.isEmpty()) {
            result.setDmlBatchRoutes(routes);
        }
        return outcome;
    }

    /**
     * B7 N3 (N1a handover obligation #2, group-level session-state rule): true when ANY statement
     * of the group falls outside the DML∪DDL whitelist — SET/session variables, USE/schema
     * switches, transaction control, procedural blocks, SELECT, unknown/unclassified shapes. The
     * batch loop opens a FRESH channel-C session per tick (design §5.2/R10), so session context
     * established by group mates would silently NOT apply to the batched statement — an
     * undetectable semantic drift the per-statement shape gate cannot see (it has no group
     * context; the MySQL SPI rejecting @v references INSIDE a statement is the statement-level
     * twin of this group-level gate). Conservative whitelist (宁可误拒不可误放): EVERY type of
     * EVERY statement must classify as DML ∨ DDL by the same GovStmtSplitServiceImpl predicates
     * the changeType derivation uses; null/empty type sets fire the gate too. Consequence: the
     * whole group gives up DML_BATCH routing (display key per would-be-routed statement); the
     * NO_KEY rejection and every other hint are unaffected. GHOST routing is NOT gated (gh-ost
     * applies the ALTER from its own toolchain, no group-session dependency).
     */
    private static boolean groupCarriesSessionState(List<SplitScript> scripts) {
        for (SplitScript script : scripts) {
            Set<SplitQueryType> types = script.getType();
            if (types == null || types.isEmpty()) {
                return true;
            }
            for (SplitQueryType type : types) {
                if (!GovStmtSplitServiceImpl.isDmlType(type) && !GovStmtSplitServiceImpl.isDdlType(type)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** one DML_BATCH display mark (the GhostRouteVO builder pattern; shape embedded verbatim — obligation #7). */
    private static GovTicketV2CheckVO.DmlBatchRouteVO buildDmlRouteVO(
            int stmtIndex, String route,
            com.clougence.clouddm.sdk.sql.editor.rewrite.DmlBatchShapeMO shape,
            Long estimatedRows, Long tableRows, long threshold, String message) {
        GovTicketV2CheckVO.DmlBatchRouteVO vo = new GovTicketV2CheckVO.DmlBatchRouteVO();
        vo.setStmtIndex(stmtIndex);
        vo.setRoute(route);
        if (shape != null) {
            vo.setTableName(shape.getTableName());
            vo.setShape(shape);
        }
        vo.setEstimatedRows(estimatedRows);
        vo.setTableRows(tableRows);
        vo.setRowThreshold(threshold);
        vo.setMessage(message);
        return vo;
    }

    /** B7: the ghost evaluation's routed candidates (the combined plan builder's input). */
    private static final class GhostRouteOutcome {
        static final GhostRouteOutcome EMPTY = new GhostRouteOutcome(List.of(), Map.of());

        final List<GhostAlterCandidate> routed;
        final Map<Integer, Long>        rowsByStmtIdx;

        GhostRouteOutcome(List<GhostAlterCandidate> routed, Map<Integer, Long> rowsByStmtIdx) {
            this.routed = routed;
            this.rowsByStmtIdx = rowsByStmtIdx;
        }
    }

    /** B7: the DML batch evaluation's routed candidates (the combined plan builder's input). */
    private static final class DmlBatchOutcome {
        static final DmlBatchOutcome EMPTY = new DmlBatchOutcome();

        final List<DmlBatchCandidate> routed = new ArrayList<>();
    }

    /** one DML_BATCH-routed statement: its 1-based position + the frozen segment prototype. */
    private static final class DmlBatchCandidate {
        final int stmtIndex;
        final com.clougence.clouddm.console.web.component.governance.TicketSegment prototype;

        DmlBatchCandidate(int stmtIndex,
                          com.clougence.clouddm.console.web.component.governance.TicketSegment prototype) {
            this.stmtIndex = stmtIndex;
            this.prototype = prototype;
        }
    }

    private static GovTicketV2CheckVO.GhostRouteVO buildGhostRouteVO(
            String dbName, GhostAlterCandidate alter,
            com.clougence.clouddm.console.web.model.vo.ghost.GhostRouteProbe probe,
            String route, String alertReason) {
        GovTicketV2CheckVO.GhostRouteVO vo = new GovTicketV2CheckVO.GhostRouteVO();
        vo.setStmtIndex(alter.stmtIndex);
        vo.setRoute(route);
        vo.setTableName(alter.tableName);
        vo.setTableRows(probe.getTableRows());
        vo.setTotalBytes(probe.getTotalBytes());
        vo.setSizeThresholdBytes(probe.getSizeThresholdBytes());
        vo.setRowThreshold(probe.getRowThreshold());
        vo.setAlertReason(alertReason);
        if (GovTicketV2CheckVO.GhostRouteVO.ROUTE_GHOST.equals(route)) {
            vo.setMessage(DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_ROUTE_ANNOUNCE.name(), //
                alter.tableName, display(probe.getTableRows()), displayMb(probe.getTotalBytes()), //
                display(probe.getRowThreshold()), displayMb(probe.getSizeThresholdBytes())));
        } else {
            I18nDmMsgKeys riskKey = com.clougence.clouddm.console.web.component.alert.AlertContext.RISK_TUNNEL.equals(alertReason) //
                ? I18nDmMsgKeys.GHOST_ROUTE_RISK_TUNNEL : I18nDmMsgKeys.GHOST_ROUTE_RISK_NO_UNIQUE_KEY;
            vo.setMessage(DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_ROUTE_ALERT_PASS.name(), //
                dbName + "." + alter.tableName, DmI18nUtils.getMessage(riskKey.name())));
        }
        return vo;
    }

    private static String display(Long value) {
        return value == null ? "-" : String.valueOf(value);
    }

    private static String displayMb(Long bytes) {
        return bytes == null ? "-" : String.valueOf(bytes / (1024L * 1024L));
    }

    /** one ghost-routable ALTER statement of the group (1-based exec_order domain). */
    private static class GhostAlterCandidate {
        final int    stmtIndex;
        final String tableName;
        /** M2: the FULL frozen statement text — rides into the GHOST segment plan (self-contained dispatch) */
        final String sql;

        GhostAlterCandidate(int stmtIndex, String tableName, String sql) {
            this.stmtIndex = stmtIndex;
            this.tableName = tableName;
            this.sql = sql;
        }
    }

    /** ghostAutoRoute kill switch — fail-safe read mirroring the govTicketFullWidthLintBlock pattern. */
    private boolean isGhostAutoRouteEnabled() {
        try {
            Boolean enabled = systemDal.fetchSystemConf(
                com.clougence.clouddm.console.web.component.config.RootUserConfig.Fields.ghostAutoRoute, Boolean.class);
            return enabled == null || enabled; // default true (mirrors the @UserConfigDef defaultValue)
        } catch (Exception e) {
            return true;
        }
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

    /**
     * Precheck FAIL → the whole-ticket rejection exception (submit + resubmit loops share this
     * single shape). N2 (design §8.2, 未决⑦ 定形): a DML_NO_KEY failure is thrown TYPED — the
     * submit boundaries (GovTicketV2Controller + the open-API submitTicket) catch the type to
     * fire the OUT-OF-TRANSACTION alert + DML_BATCH_REJECT audit trail after the rollback, then
     * rethrow. The user-facing message is byte-identical to the generic shape (the
     * GOV_TICKET_V2_PRECHECK_FAILED wrapper), and the typed exception IS-A ErrorMessageException,
     * so the GlobalExceptionHandler semantics never change.
     */
    private static void throwPrecheckFailure(GovTicketV2CheckVO.GroupResult gr) {
        String message = DmI18nUtils.getMessage(
            I18nDmMsgKeys.GOV_TICKET_V2_PRECHECK_FAILED.name(), gr.getDbName(), gr.getErrorMessage());
        if (GovTicketV2CheckVO.FAIL_CODE_DML_NO_KEY.equals(gr.getFailCode()) && gr.getDmlNoKeyReject() != null) {
            throw new com.clougence.clouddm.console.web.service.dmlbatch.DmlNoKeyRejectException(
                message, gr.getDmlNoKeyReject());
        }
        throw new ErrorMessageException(message);
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

        // Additive: estimatedRows + rowsEstimated (design D3; @JsonIgnoreProperties on old consumers)
        if (gr.getEstimatedRows() != null) {
            json.put("estimatedRows", gr.getEstimatedRows());
        }
        if (gr.getRowsEstimated() != null) {
            json.put("rowsEstimated", gr.getRowsEstimated());
        }

        // Additive (B6 M1): ghost auto-route marks — the DISPLAY carrier of the routing decision
        // (the execution-time authority is ApprovalMO.ghostRouteFlags). stmtIndex is 1-based in
        // the exec_order domain (R11). Absent when no evaluation took place (legacy shape).
        if (gr.getGhostRoutes() != null && !gr.getGhostRoutes().isEmpty()) {
            List<Map<String, Object>> routes = new ArrayList<>();
            for (GovTicketV2CheckVO.GhostRouteVO r : gr.getGhostRoutes()) {
                Map<String, Object> m = new HashMap<>();
                m.put("stmtIndex", r.getStmtIndex());
                m.put("route", r.getRoute());
                m.put("tableName", r.getTableName());
                m.put("tableRows", r.getTableRows());
                m.put("totalBytes", r.getTotalBytes());
                m.put("sizeThresholdBytes", r.getSizeThresholdBytes());
                m.put("rowThreshold", r.getRowThreshold());
                if (r.getAlertReason() != null) {
                    m.put("alertReason", r.getAlertReason());
                }
                if (r.getMessage() != null) {
                    m.put("message", r.getMessage());
                }
                routes.add(m);
            }
            json.put("ghostRoutes", routes);
        }

        // Additive (B7 N1b): the DML batch routing decision record (design §9 display carrier —
        // the execution-time authority stays the segments plan + positional flags). One entry per
        // evaluated statement + the optional group-level accumulate hint; the FULL shape analysis
        // (DmlBatchShapeMO incl. the SplitQueryType enum, serialized as its name — N1a handover
        // obligation #7) rides verbatim for auditability. Absent when no evaluation took place.
        if (gr.getDmlBatchRoutes() != null && !gr.getDmlBatchRoutes().isEmpty()) {
            List<Map<String, Object>> dmlRoutes = new ArrayList<>();
            for (GovTicketV2CheckVO.DmlBatchRouteVO r : gr.getDmlBatchRoutes()) {
                Map<String, Object> m = new HashMap<>();
                m.put("stmtIndex", r.getStmtIndex());
                m.put("route", r.getRoute());
                if (r.getTableName() != null) {
                    m.put("tableName", r.getTableName());
                }
                if (r.getEstimatedRows() != null) {
                    m.put("estimatedRows", r.getEstimatedRows());
                }
                if (r.getTableRows() != null) {
                    m.put("tableRows", r.getTableRows());
                }
                if (r.getRowThreshold() != null) {
                    m.put("rowThreshold", r.getRowThreshold());
                }
                if (r.getKeyColumn() != null) {
                    m.put("keyColumn", r.getKeyColumn());
                }
                if (r.getKeyType() != null) {
                    m.put("keyType", r.getKeyType());
                }
                if (r.getIdempotent() != null) {
                    m.put("idempotent", r.getIdempotent());
                }
                if (r.getBatchSize() != null) {
                    m.put("batchSize", r.getBatchSize());
                }
                if (r.getApproxBatches() != null) {
                    m.put("approxBatches", r.getApproxBatches());
                }
                if (r.getRejectCode() != null) {
                    m.put("rejectCode", r.getRejectCode());
                }
                if (r.getKeyDetail() != null) {
                    m.put("keyDetail", r.getKeyDetail());
                }
                if (r.getShape() != null) {
                    // the whole MO embedded verbatim (additive contract; the open API passes the
                    // carrier through untouched — SplitQueryType renders as its enum name)
                    m.put("shape", r.getShape());
                }
                if (r.getMessage() != null) {
                    m.put("message", r.getMessage());
                }
                dmlRoutes.add(m);
            }
            json.put("dmlBatchRoutes", dmlRoutes);
        }

        // Additive (B6 M2): the segment PLAN (plan of record, contract :404) — written ONLY when
        // at least one routed segment exists (B7: GHOST ∨ DML_BATCH), so a pure-normal group keeps
        // the legacy shape byte-identical. 1-based exec_order domain keys (R11): AUTOEXEC carries
        // stmtRange [from,to] inclusive; GHOST / DML_BATCH carry stmtIndex + the probed table +
        // the FULL frozen statement text (self-contained execution-time dispatch); DML_BATCH
        // additionally freezes batchKey{column,keyType} + estimatedRows + idempotent (B7 plan
        // freeze — the dispatch never re-probes).
        if (gr.getSegments() != null && !gr.getSegments().isEmpty()) {
            List<Map<String, Object>> segments = new ArrayList<>();
            for (com.clougence.clouddm.console.web.component.governance.TicketSegment seg : gr.getSegments()) {
                Map<String, Object> m = new HashMap<>();
                m.put("segIdx", seg.getSegIdx());
                m.put("type", seg.getType());
                if (seg.getStmtRange() != null) {
                    m.put("stmtRange", seg.getStmtRange());
                }
                if (seg.getStmtIndex() != null) {
                    m.put("stmtIndex", seg.getStmtIndex());
                }
                if (seg.getTable() != null) {
                    m.put("table", seg.getTable());
                }
                if (seg.getRows() != null) {
                    m.put("rows", seg.getRows());
                }
                if (seg.getSql() != null) {
                    m.put("sql", seg.getSql());
                }
                if (seg.getBatchKey() != null) {
                    Map<String, Object> key = new HashMap<>();
                    key.put("column", seg.getBatchKey().getColumn());
                    key.put("keyType", seg.getBatchKey().getKeyType());
                    m.put("batchKey", key);
                }
                if (seg.getEstimatedRows() != null) {
                    m.put("estimatedRows", seg.getEstimatedRows());
                }
                if (seg.getIdempotent() != null) {
                    m.put("idempotent", seg.getIdempotent());
                }
                segments.add(m);
            }
            json.put("segments", segments);
        }

        json.put("checkedAt", OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        json.put("engine", ENGINE_V2);

        if (gr.getErrorMessage() != null) {
            json.put("errorMessage", gr.getErrorMessage());
        }

        return JsonUtils.toJson(json);
    }

    // ==================== helpers ====================

    private boolean isFullWidthLintBlock() {
        try {
            Boolean block = systemDal.fetchSystemConf(CONFIG_FULL_WIDTH_LINT_BLOCK, Boolean.class);
            return block == null || block;
        } catch (Exception e) {
            return true; // default BLOCK
        }
    }

    private void validateTicketType(String ticketType) {
        if (!TICKET_TYPE_PRE_DDL.equals(ticketType) && !TICKET_TYPE_PROD_DML.equals(ticketType)) {
            throw new ErrorMessageException("Invalid ticket type: " + ticketType + ", must be PRE_DDL or PROD_DML");
        }
    }

    /**
     * Resolve and validate the approval mode. PRE_DDL + VIA_RELEASE is rejected (400);
     * null/blank/unknown values default to DIRECT (legacy behavior for both ticket types).
     */
    private String resolveApprovalMode(String ticketType, String rawMode) {
        if (StringUtils.isBlank(rawMode)) {
            return APPROVAL_MODE_DIRECT;
        }
        String mode = rawMode.trim();
        if (!APPROVAL_MODE_DIRECT.equals(mode) && !APPROVAL_MODE_VIA_RELEASE.equals(mode)) {
            // Unknown value → fail-safe to DIRECT (legacy behavior)
            return APPROVAL_MODE_DIRECT;
        }
        if (APPROVAL_MODE_VIA_RELEASE.equals(mode) && TICKET_TYPE_PRE_DDL.equals(ticketType)) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(
                I18nDmMsgKeys.GOV_TICKET_V2_VIA_RELEASE_PRE_DDL_INVALID.name()));
        }
        return mode;
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

    private void appendSubmitEvent(long ticketId, String uid, String ticketType, int groupCount, ChangeType changeType, String approvalMode) {
        appendSubmitEvent(ticketId, uid, ticketType, groupCount, changeType, approvalMode, null, null);
    }

    /**
     * Overload for the External resubmit path: explicit toStatus (WAIT_EXEC for direct-to-exec)
     * and via tag (EXTERNAL_RESUBMIT) for audit distinction. When via/toStatus are null, the
     * legacy toStatus computation is used (regression pin for Internal/DingTalk/VIA_RELEASE).
     */
    private void appendSubmitEvent(long ticketId, String uid, String ticketType, int groupCount,
                                   ChangeType changeType, String approvalMode, String via, ApprovalStatus toStatus) {
        DmDbChangeEventDO event = new DmDbChangeEventDO();
        event.setTicketId(ticketId);
        event.setEventType(GovEventType.SUBMIT.name());
        event.setFromStatus(null);
        event.setToStatus(toStatus != null ? toStatus.name()
            : (APPROVAL_MODE_VIA_RELEASE.equals(approvalMode)
                ? ApprovalStatus.WAIT_RELEASE.name() : ApprovalStatus.WAIT_APPROVAL.name()));
        event.setOperatorUid(uid);

        Map<String, Object> data = new HashMap<>();
        data.put("ticketType", ticketType);
        data.put("groupCount", groupCount);
        data.put("changeType", changeType.name());
        data.put("approvalMode", approvalMode);
        if (via != null) {
            data.put("via", via);
        }
        event.setEventData(JsonUtils.toJson(data));

        dbChangeEventDal.eventMapper().insert(event);
    }

    /**
     * SYSTEM_CONFIRM event for the External resubmit direct-to-exec path.
     * Mirrors GovAutoAdvanceServiceImpl.appendEvent(L170-171): records the system confirm
     * bypass (WAIT_CONFIRM→WAIT_EXEC). operatorUid = the caller uid (service account for
     * open command, owner for console). eventData carries via=EXTERNAL_RESUBMIT + changeType.
     */
    private void appendSystemConfirmEvent(long ticketId, String uid, ChangeType changeType) {
        DmDbChangeEventDO event = new DmDbChangeEventDO();
        event.setTicketId(ticketId);
        event.setEventType(GovEventType.SYSTEM_CONFIRM.name());
        event.setFromStatus(ApprovalStatus.WAIT_CONFIRM.name());
        event.setToStatus(ApprovalStatus.WAIT_EXEC.name());
        event.setOperatorUid(uid);

        Map<String, Object> data = new HashMap<>();
        data.put("changeType", changeType.name());
        data.put("via", "EXTERNAL_RESUBMIT");
        event.setEventData(JsonUtils.toJson(data));

        dbChangeEventDal.eventMapper().insert(event);
    }

    /**
     * Build autoExecConfig from ChangeType — mirrors GovAutoAdvanceServiceImpl.buildAutoExecConfig (D15 routing):
     * DML → enableTransactional=true; DDL/MIXED → false; errorStrategy=NONE always; IMMEDIATE always.
     */
    private static com.clougence.clouddm.console.web.model.fo.ticket.DmAutoExecConfigFO buildAutoExecConfig(ChangeType changeType) {
        com.clougence.clouddm.console.web.model.fo.ticket.DmAutoExecConfigFO config =
            new com.clougence.clouddm.console.web.model.fo.ticket.DmAutoExecConfigFO();
        config.setAutoExecType(com.clougence.clouddm.platform.dal.model.execution.AutoExecType.IMMEDIATE);
        config.setErrorStrategy(com.clougence.clouddm.api.console.autoexec.ErrorStrategy.NONE);
        config.setEnableTransactional(changeType == ChangeType.DML);
        config.setRetryWaitTime(null);
        config.setRetryCount(null);
        config.setExecTime(null);
        config.setSnapshot(false);
        return config;
    }

    private void appendTicketReturnedEvent(long ticketId, String uid, String fromStatus, String toStatus,
                                           String reason, List<Long> failedGroupIds, String via) {
        DmDbChangeEventDO event = new DmDbChangeEventDO();
        event.setTicketId(ticketId);
        event.setEventType(GovEventType.TICKET_RETURNED.name());
        event.setFromStatus(fromStatus);
        event.setToStatus(toStatus);
        event.setOperatorUid(uid);

        Map<String, Object> data = new HashMap<>();
        data.put("reason", reason);
        data.put("failedGroupIds", failedGroupIds);
        data.put("via", via);
        event.setEventData(JsonUtils.toJson(data));

        dbChangeEventDal.eventMapper().insert(event);
    }

    // Internal precheck result holder for submit
    private static class GroupPrecheck {
        final long   groupId;   // 0 for submit (stmt not yet inserted); actual id for resubmit
        final long   pairId;
        final String sqlContent;
        final GovTicketV2CheckVO.GroupResult result;
        final String precheckResultJson;

        GroupPrecheck(long groupId, long pairId, String sqlContent, GovTicketV2CheckVO.GroupResult result, String precheckResultJson) {
            this.groupId = groupId;
            this.pairId = pairId;
            this.sqlContent = sqlContent;
            this.result = result;
            this.precheckResultJson = precheckResultJson;
        }
    }

    // ==================== risk assessment ====================

    /**
     * Assesses risk level from v2 precheck results (design D2: synchronous in submit transaction).
     * Reconstructs SecRulesCheckResult and statement types from the GroupResult VOs.
     */
    private com.clougence.clouddm.console.web.component.governance.RiskAssessment assessRiskFromPrechecks(
        String puid, long envId, String envName, String dsType, List<GroupPrecheck> prechecks) {

        java.util.List<java.util.Set<SplitQueryType>> allStmtTypes = new java.util.ArrayList<>();
        SecRulesCheckResult mergedRules = new SecRulesCheckResult();
        long totalEstimatedRows = 0;
        boolean anyRowsEstimated = false;
        java.util.List<String> tables = new java.util.ArrayList<>();

        for (GroupPrecheck pc : prechecks) {
            GovTicketV2CheckVO.GroupResult gr = pc.result;
            if (gr == null) {
                continue;
            }
            // Reconstruct statement types
            if (gr.getStatements() != null) {
                for (GovTicketV2CheckVO.StmtPreview sp : gr.getStatements()) {
                    java.util.Set<SplitQueryType> types = new java.util.HashSet<>();
                    if (sp.getType() != null) {
                        try {
                            types.add(SplitQueryType.valueOf(sp.getType()));
                        } catch (Exception ignored) {
                            // unknown type
                        }
                    }
                    allStmtTypes.add(types);
                }
            }
            // Reconstruct rules check result
            if (gr.getRulesCheck() != null && gr.getRulesCheck().getMessages() != null) {
                for (GovTicketV2CheckVO.RuleMessage rm : gr.getRulesCheck().getMessages()) {
                    try {
                        RuleLevel level = RuleLevel.valueOf(rm.getLevel());
                        mergedRules.addResult(rm.getRule(), level, null, rm.getMessage());
                    } catch (Exception ignored) {
                        // unknown level
                    }
                }
            }
            // Sum estimated rows
            if (gr.getEstimatedRows() != null) {
                totalEstimatedRows += gr.getEstimatedRows();
                anyRowsEstimated = true;
            }
            // Collect table names (use dbName as context)
            if (StringUtils.isNotBlank(gr.getDbName())) {
                tables.add(gr.getDbName());
            }
        }

        Long estimatedRows = anyRowsEstimated ? totalEstimatedRows : null;
        return riskAssessmentService.assess(
            puid, envId, allStmtTypes, mergedRules, estimatedRows, envName, dsType, tables);
    }
}
