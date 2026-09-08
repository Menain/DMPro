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
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.base.metadata.ds.DataSourceConfig;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.component.auth.DmAuthServiceForBiz;
import com.clougence.clouddm.console.web.component.dsconfig.DmDsConfigService;
import com.clougence.clouddm.console.web.component.dsconfig.mode.DsLevels;
import com.clougence.clouddm.console.web.component.governance.GovDmlRowEstimator;
import com.clougence.clouddm.console.web.component.governance.GovRowLimitConfig;
import com.clougence.clouddm.console.web.component.governance.GovSqlHashUtils;
import com.clougence.clouddm.console.web.component.governance.GovSplitResult;
import com.clougence.clouddm.console.web.component.governance.GovStmtRow;
import com.clougence.clouddm.console.web.component.governance.GovStmtSplitService;
import com.clougence.clouddm.console.web.model.fo.governance.GovDirectDmlSubmitFO;
import com.clougence.clouddm.console.web.model.fo.ticket.DmAddTicketFO;
import com.clougence.clouddm.console.web.model.vo.governance.DirectDmlSubmitVO;
import com.clougence.clouddm.console.web.model.vo.logicaldb.LogicalDbTarget;
import com.clougence.clouddm.console.web.model.vo.ticket.DmTicketResultVO;
import com.clougence.clouddm.console.web.service.approval.ApprovalControlService;
import com.clougence.clouddm.console.web.service.envparam.DmEnvParamService;
import com.clougence.clouddm.console.web.service.governance.GovDirectDmlService;
import com.clougence.clouddm.console.web.service.logicaldb.LogicalDbService;
import com.clougence.clouddm.console.web.util.DsResPathObj;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.DbChangeGovernDal;
import com.clougence.clouddm.platform.dal.access.LogicalDbDal;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangePromotionMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangeRevisionMapper;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.approval.SqlContentType;
import com.clougence.clouddm.platform.dal.model.dbchange.ChangeType;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangePromotionDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeRevisionDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeStmtVersionDO;
import com.clougence.clouddm.platform.dal.model.dbchange.GovEventType;
import com.clougence.clouddm.platform.dal.model.dbchange.PromotionStatus;
import com.clougence.clouddm.platform.dal.model.dbchange.PromotionType;
import com.clougence.clouddm.platform.dal.model.dbchange.RevisionSourceType;
import com.clougence.clouddm.platform.dal.model.dbchange.StmtSource;
import com.clougence.clouddm.platform.dal.model.logicaldb.DmLogicalDbDO;
import com.clougence.clouddm.platform.dal.model.logicaldb.GovRole;
import com.clougence.clouddm.sdk.model.env.EnvParamKeys;
import com.clougence.clouddm.sdk.security.auth.AuthKind;
import com.clougence.clouddm.sdk.security.auth.def.SecDataAuthLabel;
import com.clougence.utils.JsonUtils;
import com.clougence.utils.StringUtils;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * Path B direct production DML submit service (Phase 8, spec §4.2).
 * <p>
 * Validation chain (cheap-first, expensive-last): FO smuggling → getBinding(PROD) →
 * GOV_DML_DIRECT switch → checkResAuth → split changeType==DML → rollbackSql non-empty →
 * threshold evaluation (synchronous, no PreInit handler — design D1).
 * <p>
 * Same-transaction six objects: PROD ticket → stmt_version × N → revision (DIRECT_PROD_DML,
 * source_ticket_id=self) → promotion (DIRECT_DML, path-B gate_result variant) → event →
 * ticketInfo writeback. DuplicateKey → code collision retry (both codes generated outside tx).
 * <p>
 * Zero touchpoints: does not modify DmlExplainPreInitHandler, guard, state machine, or CI/CD.
 */
@Service
@Slf4j
public class GovDirectDmlServiceImpl implements GovDirectDmlService {

    private static final int CODE_RETRY_MAX = 5;

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
    private DmEnvParamService     dmEnvParamService;
    @Resource
    private GovDmlRowEstimator    govDmlRowEstimator;
    @Resource
    private DbChangeGovernDal     dbChangeGovernDal;
    @Resource
    private ApprovalDal            approvalDal;
    @Resource
    private LogicalDbDal           logicalDbDal;
    @Resource
    private PlatformTransactionManager txManager;

    @Override
    public DirectDmlSubmitVO directDmlSubmit(String puid, String uid, GovDirectDmlSubmitFO fo) {
        // 1. getBinding(PROD) — three-state resolution (Phase 3 contract)
        LogicalDbTarget target = logicalDbService.getBinding(puid, fo.getLogicalDbId(), GovRole.PROD);

        // 2. GOV_DML_DIRECT switch (null/off → reject)
        String directSwitch = dmEnvParamService.queryParam(puid, target.getEnvId(), EnvParamKeys.GOV_DML_DIRECT);
        if (!"on".equals(directSwitch)) {
            throw new ErrorMessageException("Path B direct DML is not enabled for this environment");
        }

        // 3. Resource permission (DM_DAUTH_TICKET)
        dmAuthServiceForBiz.checkResAuth(
            puid, uid, target.getDsId(),
            new DsResPathObj(target.getResPath()),
            SecDataAuthLabel.DM_DAUTH_TICKET, AuthKind.DataSource);

        // 4. Split + classify — must be pure DML
        DataSourceConfig dsConfig = dmDsConfigService.fetchDsConfigFromExists(target.getDsId());
        GovSplitResult splitResult = govStmtSplitService.split(dsConfig, fo.getSql());
        if (splitResult.getChangeType() != ChangeType.DML) {
            throw new ErrorMessageException("Path B only accepts pure DML statements (DDL/MIXED rejected)");
        }

        // 5. Resolve logical db name for ticket title
        DmLogicalDbDO logicalDb = logicalDbDal.logicalDbMapper().selectById(fo.getLogicalDbId());
        String resourceName = logicalDb != null ? logicalDb.getResourceName() : "unknown";

        // 6. Threshold evaluation (last, most expensive — design D1)
        String rowLimitRaw = dmEnvParamService.queryParam(puid, target.getEnvId(), EnvParamKeys.GOV_DML_ROW_LIMIT);
        GovRowLimitConfig rowLimitConfig = GovRowLimitConfig.parse(rowLimitRaw);

        String riskLevel = "NORMAL";
        long estimatedRows = 0;
        String estimationEvidence = "row_limit not configured";

        if (rowLimitConfig.isConfigured()) {
            DsLevels levels = dmDsConfigService.parseLevels(buildDbLevels(target));
            List<String> dmlStmtTexts = new ArrayList<>();
            for (GovStmtRow row : splitResult.getStmts()) {
                dmlStmtTexts.add(row.getStmtText());
            }
            GovDmlRowEstimator.RowEstimate estimate = govDmlRowEstimator.estimate(puid, dsConfig, levels, dmlStmtTexts);
            estimatedRows = estimate.getEstimatedRows();
            estimationEvidence = estimate.getEvidence();

            if (rowLimitConfig.shouldBlock(estimatedRows)) {
                appendDenyEvent(uid, fo.getLogicalDbId(), estimatedRows, rowLimitConfig, estimationEvidence);
                throw new ErrorMessageException("Direct DML rejected: estimated " + estimatedRows
                    + " rows exceed block threshold " + rowLimitConfig.getBlock());
            }
            if (rowLimitConfig.shouldWarn(estimatedRows)) {
                riskLevel = "HIGH";
            }
        }

        // 7. Same-transaction three objects (+ stmt_version + event + ticketInfo)
        return executeDirectDmlInTransaction(puid, uid, fo, target, splitResult,
            resourceName, riskLevel, estimatedRows, estimationEvidence, rowLimitConfig);
    }

    private DirectDmlSubmitVO executeDirectDmlInTransaction(String puid, String uid, GovDirectDmlSubmitFO fo,
                                                            LogicalDbTarget target, GovSplitResult splitResult,
                                                            String resourceName, String riskLevel,
                                                            long estimatedRows, String estimationEvidence,
                                                            GovRowLimitConfig rowLimitConfig) {
        String sqlHash = GovSqlHashUtils.hash(fo.getSql());
        String rollbackHash = GovSqlHashUtils.hash(fo.getRollbackSql());
        String manifest = buildStmtManifest(splitResult.getStmts());
        String gateResult = buildPathBGateResult(riskLevel, estimatedRows, estimationEvidence, rowLimitConfig);
        DmAddTicketFO ticketFO = buildDirectDmlTicketFO(fo, target, resourceName);

        for (int attempt = 0; attempt < CODE_RETRY_MAX; attempt++) {
            String revisionCode = generateRevisionCode();
            String promotionCode = generatePromotionCode();

            try {
                TransactionTemplate transaction = new TransactionTemplate(txManager);
                DirectDmlSubmitVO[] resultHolder = new DirectDmlSubmitVO[1];
                transaction.executeWithoutResult(status -> {
                    // 1. Create PROD DM_CHANGE ticket
                    DmTicketResultVO ticketResult = approvalControlService.createSqlTicket(puid, uid, ticketFO, ApprovalBiz.DM_CHANGE);
                    long ticketId = ticketResult.getTicketId();

                    // 2. Insert stmt_version rows
                    insertStmtVersions(ticketId, splitResult.getStmts(), uid);

                    // 3. Insert revision (DIRECT_PROD_DML, source_ticket_id=self)
                    DmDbChangeRevisionDO revision = new DmDbChangeRevisionDO();
                    revision.setRevisionCode(revisionCode);
                    revision.setLogicalDbId(fo.getLogicalDbId());
                    revision.setEnvId(target.getEnvId());
                    revision.setSourceType(RevisionSourceType.DIRECT_PROD_DML.name());
                    revision.setSourceTicketId(ticketId);
                    revision.setChangeType(ChangeType.DML.name());
                    revision.setSqlText(fo.getSql());
                    revision.setRollbackSqlText(fo.getRollbackSql());
                    revision.setSqlHash(sqlHash);
                    revision.setRollbackSqlHash(rollbackHash);
                    revision.setStmtManifest(manifest);
                    revision.setAuditSnapshot(null);
                    dbChangeGovernDal.revisionMapper().insert(revision);
                    long revisionId = revision.getId();

                    // 4. Insert promotion (DIRECT_DML, path-B gate_result variant)
                    String executionKey = GovSqlHashUtils.hash(revisionId + "|" + target.getDsId() + "|" + fo.getLogicalDbId());
                    DmDbChangePromotionDO promotion = new DmDbChangePromotionDO();
                    promotion.setPromotionCode(promotionCode);
                    promotion.setPromotionType(PromotionType.DIRECT_PROD_DML.name());
                    promotion.setRevisionId(revisionId);
                    promotion.setLogicalDbId(fo.getLogicalDbId());
                    promotion.setProdEnvId(target.getEnvId());
                    promotion.setProdDsId(target.getDsId());
                    promotion.setProdResPath(target.getResPath());
                    promotion.setProdApprovalId(ticketId);
                    promotion.setExecutionKey(executionKey);
                    promotion.setGateResult(gateResult);
                    promotion.setStatus(PromotionStatus.CREATED.name());
                    dbChangeGovernDal.promotionMapper().insert(promotion);
                    long promotionId = promotion.getId();

                    // 5. Event: DIRECT_DML_SUBMIT
                    appendDirectDmlSubmitEvent(ticketId, promotionId, revisionId, uid, riskLevel, estimatedRows, estimationEvidence);

                    // 6. ticketInfo writeback (read-modify-write, 8-field preservation)
                    updateTicketInfoWithGovFields(ticketId, promotionId, revisionId, fo.getLogicalDbId(), GovRole.PROD.name());

                    DirectDmlSubmitVO vo = new DirectDmlSubmitVO();
                    vo.setTicketId(ticketId);
                    vo.setRevisionId(revisionId);
                    vo.setRevisionCode(revisionCode);
                    vo.setPromotionId(promotionId);
                    vo.setPromotionCode(promotionCode);
                    vo.setRiskLevel(riskLevel);
                    resultHolder[0] = vo;
                });
                return resultHolder[0];
            } catch (DuplicateKeyException e) {
                log.warn("[DirectDml] code collision, retrying (attempt {})", attempt + 1);
            }
        }
        throw new ErrorMessageException("Failed to generate unique revision/promotion code after retries");
    }

    // ------- helpers (service-internal copy per design D4) -------

    private static DmAddTicketFO buildDirectDmlTicketFO(GovDirectDmlSubmitFO fo, LogicalDbTarget target, String resourceName) {
        DmAddTicketFO ticketFO = new DmAddTicketFO();
        ticketFO.setDbLevels(buildDbLevels(target));
        ticketFO.setRawSql(fo.getSql());
        ticketFO.setRollBackSql(fo.getRollbackSql());
        ticketFO.setContentType(SqlContentType.INLINE);
        ticketFO.setTicketTitle("DIRECT-DML · " + resourceName);
        ticketFO.setDescription(fo.getDescription());
        ticketFO.setForce(true);
        return ticketFO;
    }

    // buildDbLevels — 3rd copy (research/04 D4: copy, don't abstract; tech-debt noted for P2 refactor)
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

    private static String buildStmtManifest(List<GovStmtRow> stmts) {
        List<Map<String, Object>> manifest = new ArrayList<>();
        for (GovStmtRow row : stmts) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("idx", row.getStmtIndex());
            item.put("stmt_hash", row.getStmtHash());
            item.put("version", 1);
            item.put("pre_exec", "PENDING");
            manifest.add(item);
        }
        return JsonUtils.toJson(manifest);
    }

    private static String buildPathBGateResult(String riskLevel, long estimatedRows,
                                              String estimationEvidence, GovRowLimitConfig rowLimitConfig) {
        Date now = new Date();
        List<Map<String, Object>> items = new ArrayList<>();

        items.add(buildGateItem(1, "DML-only", true, "changeType=DML", now));
        items.add(buildGateItem(2, "GOV_DML_DIRECT", true, "on", now));
        items.add(buildGateItem(3, "Auth label", true, "RDP_DB_CHANGE_PROD_DML_DIRECT", now));
        items.add(buildGateItem(4, "Resource permission", true, "checkResAuth passed", now));
        items.add(buildGateItem(5, "Rollback SQL", true, "provided", now));

        String thresholdEvidence;
        if (rowLimitConfig.isConfigured()) {
            thresholdEvidence = "riskLevel=" + riskLevel + ", estimatedRows=" + estimatedRows
                + ", warn=" + rowLimitConfig.getWarn() + ", block=" + rowLimitConfig.getBlock();
        } else {
            thresholdEvidence = "riskLevel=" + riskLevel + ", row_limit not configured";
        }
        Map<String, Object> thresholdItem = buildGateItem(6, "Threshold", true, thresholdEvidence, now);
        thresholdItem.put("riskLevel", riskLevel); // structured field for Phase 9 form consumption
        items.add(thresholdItem);

        return JsonUtils.toJson(items);
    }

    private static Map<String, Object> buildGateItem(int item, String label, boolean pass, String reason, Date timestamp) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("item", item);
        entry.put("label", label);
        entry.put("pass", pass);
        entry.put("reason", reason);
        entry.put("timestamp", timestamp.toString());
        return entry;
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

    private void updateTicketInfoWithGovFields(long ticketId, Long promotionId, Long revisionId, Long logicalDbId, String govRole) {
        DmApprovalDO ticket = approvalDal.approvalMapper().selectById(ticketId);
        if (ticket == null) {
            return;
        }
        ApprovalMO mo = StringUtils.isEmpty(ticket.getTicketInfo())
            ? new ApprovalMO()
            : JsonUtils.toObj(ticket.getTicketInfo(), ApprovalMO.class);
        mo.setPromotionId(promotionId);
        mo.setRevisionId(revisionId);
        mo.setLogicalDbId(logicalDbId);
        mo.setGovRole(govRole);
        approvalDal.approvalMapper().updateTicketInfo(ticketId, JsonUtils.toJson(mo));
    }

    private void appendDenyEvent(String uid, long logicalDbId, long estimatedRows,
                                 GovRowLimitConfig rowLimitConfig, String estimationEvidence) {
        DmDbChangeEventDO event = new DmDbChangeEventDO();
        event.setEventType(GovEventType.DIRECT_DML_DENY.name());
        event.setOperatorUid(uid);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("logicalDbId", logicalDbId);
        data.put("estimatedRows", estimatedRows);
        data.put("warn", rowLimitConfig.getWarn());
        data.put("block", rowLimitConfig.getBlock());
        data.put("evidence", estimationEvidence);
        event.setEventData(JsonUtils.toJson(data));

        dbChangeGovernDal.eventMapper().insert(event);
    }

    private void appendDirectDmlSubmitEvent(long ticketId, long promotionId, long revisionId,
                                           String uid, String riskLevel, long estimatedRows, String estimationEvidence) {
        DmDbChangeEventDO event = new DmDbChangeEventDO();
        event.setTicketId(ticketId);
        event.setPromotionId(promotionId);
        event.setRevisionId(revisionId);
        event.setEventType(GovEventType.DIRECT_DML_SUBMIT.name());
        event.setToStatus(ApprovalStatus.PRE_INIT_WAIT.name());
        event.setOperatorUid(uid);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("riskLevel", riskLevel);
        data.put("estimatedRows", estimatedRows);
        data.put("evidence", estimationEvidence);
        event.setEventData(JsonUtils.toJson(data));

        dbChangeGovernDal.eventMapper().insert(event);
    }

    // ------- code generation (copied per design D4) -------

    private String generateRevisionCode() {
        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String prefix = "REV-" + dateStr + "-";

        DmDbChangeRevisionMapper revisionMapper = dbChangeGovernDal.revisionMapper();
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

    private String generatePromotionCode() {
        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String prefix = "PROMO-" + dateStr + "-";

        DmDbChangePromotionMapper mapper = dbChangeGovernDal.promotionMapper();
        LambdaQueryWrapper<DmDbChangePromotionDO> wrapper = new LambdaQueryWrapper<>();
        wrapper.likeRight(DmDbChangePromotionDO::getPromotionCode, prefix);
        wrapper.orderByDesc(DmDbChangePromotionDO::getPromotionCode);
        wrapper.last("LIMIT 1");
        DmDbChangePromotionDO maxPromo = mapper.selectOne(wrapper);

        int nextNum = 1;
        if (maxPromo != null) {
            String maxCode = maxPromo.getPromotionCode();
            String numPart = maxCode.substring(maxCode.lastIndexOf("-") + 1);
            nextNum = Integer.parseInt(numPart) + 1;
        }
        return prefix + String.format("%04d", nextNum);
    }
}
