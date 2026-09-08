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
package com.clougence.clouddm.console.web.component.governance;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.service.envparam.DmEnvParamService;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.AuthDal;
import com.clougence.clouddm.platform.dal.access.DbChangeGovernDal;
import com.clougence.clouddm.platform.dal.access.LogicalDbDal;
import com.clougence.clouddm.platform.dal.access.SystemDal;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.auth.DmAuthUserDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangePromotionDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeRevisionDO;
import com.clougence.clouddm.platform.dal.model.dbchange.PromotionType;
import com.clougence.clouddm.platform.dal.model.dbchange.RevisionSourceType;
import com.clougence.clouddm.platform.dal.model.logicaldb.DmLogicalDbDO;
import com.clougence.clouddm.platform.dal.model.system.DmSysEnvDO;
import com.clougence.clouddm.sdk.approval.form.ChangeForm;
import com.clougence.clouddm.sdk.model.env.EnvParamKeys;
import com.clougence.utils.JsonUtils;
import com.clougence.utils.StringUtils;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * Assembles a {@link ChangeForm} for governance PROD tickets (touchpoint #7, Phase 9).
 * <p>
 * Maps the spec §5.5 nine governance fields onto the existing ChangeForm structure
 * without extending ChangeForm / DingApiUtils / DingConstant / Provider.
 * <ul>
 * <li>ticketTitle ← promotion_code (fallback revision_code / bizId)</li>
 * <li>targetDs ← "resource_name @ env_name" (from logical_db + promotion snapshot env)</li>
 * <li>executeSql ← rawSql truncated to 2000 chars + deep-link tail</li>
 * <li>ticketDesc ← multi-line: applicant / risk-level / estimated-rows / sql_hash / PRE-result / deep-link</li>
 * <li>flowName / changeName / branch = null (governance has no CI/CD flow)</li>
 * </ul>
 * <p>
 * Field-level null-safe (design D4): PRE governance tickets misconfigured with an external
 * template produce a degraded form ("-" placeholders) instead of an exception — the only
 * fail-fast is logicalDbId==null (data corruption).
 */
@Slf4j
@Service
public class GovChangeFormAssembler {

    private static final int SQL_SUMMARY_MAX = 2000;

    @Resource
    private DbChangeGovernDal  dbChangeGovernDal;
    @Resource
    private LogicalDbDal       logicalDbDal;
    @Resource
    private DmEnvParamService  dmEnvParamService;
    @Resource
    private SystemDal          systemDal;
    @Resource
    private AuthDal            authDal;
    @Resource
    private ApprovalDal        approvalDal;

    public ChangeForm build(DmApprovalDO ticketDO, ApprovalMO info, String templateId) {
        // D4: logicalDbId null = data corruption → fail-fast
        if (info.getLogicalDbId() == null) {
            throw new ErrorMessageException("Governance ticket missing logicalDbId (data corruption)");
        }

        String puid = ticketDO.getPrimaryUid();
        DmAuthUserDO userDO = this.authDal.userMapper().queryByUid(ticketDO.getOwnerUid());

        // Resolve governance objects (null-safe per D4)
        DmDbChangePromotionDO promotion = info.getPromotionId() != null
            ? dbChangeGovernDal.promotionMapper().selectById(info.getPromotionId())
            : null;
        DmDbChangeRevisionDO revision = info.getRevisionId() != null
            ? dbChangeGovernDal.revisionMapper().selectById(info.getRevisionId())
            : null;
        DmLogicalDbDO logicalDb = logicalDbDal.logicalDbMapper().selectById(info.getLogicalDbId());

        ChangeForm form = new ChangeForm();
        form.setTicketUserPhone(userDO != null ? userDO.getPhone() : null);
        form.setTemplateIdentity(templateId);

        // Field 1: 变更单号
        form.setTicketTitle(resolveTicketTitle(promotion, revision, ticketDO));

        // Field 2+3: 逻辑库+环境
        form.setTargetDs(resolveTargetDs(logicalDb, promotion));

        // Field 9: SQL 摘要
        form.setExecuteSql(truncateSqlForForm(ticketDO.getRawSql(), ticketDO.getId()));

        // Fields 4-8: multi-line ticketDesc
        String riskLevel = resolveRiskLevel(promotion, revision, ticketDO, puid);
        String preExecResult = resolvePreExecResult(revision);
        String sqlHash = revision != null ? revision.getSqlHash() : null;
        form.setTicketDesc(buildGovFormDesc(userDO, riskLevel, ticketDO.getExpectedAffectedRows(), sqlHash, preExecResult, ticketDO.getId()));

        // flowName / changeName / branch = null (governance has no CI/CD flow)
        return form;
    }

    // ======= Field 1: 变更单号 =======

    private String resolveTicketTitle(DmDbChangePromotionDO promotion, DmDbChangeRevisionDO revision, DmApprovalDO ticketDO) {
        if (promotion != null && StringUtils.isNotBlank(promotion.getPromotionCode())) {
            return promotion.getPromotionCode();
        }
        if (revision != null && StringUtils.isNotBlank(revision.getRevisionCode())) {
            return revision.getRevisionCode();
        }
        return ticketDO.getBizId();
    }

    // ======= Field 2+3: 逻辑库+环境 =======

    private String resolveTargetDs(DmLogicalDbDO logicalDb, DmDbChangePromotionDO promotion) {
        String resourceName = logicalDb != null ? logicalDb.getResourceName() : "-";
        if (promotion == null || promotion.getProdEnvId() == null) {
            return resourceName; // D4: PRE misconfigured — no env snapshot
        }
        DmSysEnvDO env = systemDal.envMapper().selectById(promotion.getProdEnvId());
        String envName = env != null ? env.getEnvName() : "-";
        return resourceName + " @ " + envName;
    }

    // ======= Field 5: 风险等级 (D3 dual-path) =======

    private String resolveRiskLevel(DmDbChangePromotionDO promotion, DmDbChangeRevisionDO revision,
                                    DmApprovalDO ticketDO, String puid) {
        if (promotion == null || revision == null) {
            return "NORMAL"; // D4: PRE misconfigured → default
        }
        // Path B: read riskLevel from gate_result
        if (PromotionType.DIRECT_PROD_DML.name().equals(promotion.getPromotionType())) {
            return readRiskLevelFromGateResult(promotion.getGateResult());
        }
        // Path A: live compute (not written back to gate_result)
        return computeRiskLevelPathA(revision, ticketDO, promotion, puid);
    }

    private String readRiskLevelFromGateResult(String gateResult) {
        if (StringUtils.isBlank(gateResult)) {
            return "NORMAL";
        }
        try {
            List<Map<String, Object>> items = JsonUtils.toObj(gateResult, List.class);
            if (items == null) {
                return "NORMAL";
            }
            for (Map<String, Object> item : items) {
                if ("Threshold".equals(String.valueOf(item.get("label")))) {
                    Object riskLevel = item.get("riskLevel");
                    if (riskLevel != null && StringUtils.isNotBlank(String.valueOf(riskLevel))) {
                        return String.valueOf(riskLevel).trim();
                    }
                    return "NORMAL";
                }
            }
        } catch (Exception e) {
            log.warn("[GovChangeForm] failed to parse riskLevel from gate_result", e);
        }
        return "NORMAL";
    }

    private String computeRiskLevelPathA(DmDbChangeRevisionDO revision, DmApprovalDO ticketDO,
                                         DmDbChangePromotionDO promotion, String puid) {
        String changeType = revision.getChangeType();
        if (!"DML".equals(changeType) && !"MIXED".equals(changeType)) {
            return "NORMAL"; // pure DDL
        }
        String rowLimitRaw = dmEnvParamService.queryParam(puid, promotion.getProdEnvId(), EnvParamKeys.GOV_DML_ROW_LIMIT);
        GovRowLimitConfig config = GovRowLimitConfig.parse(rowLimitRaw);
        if (!config.isConfigured()) {
            return "NORMAL"; // unconfigured → no tiering
        }
        long rows = ticketDO.getExpectedAffectedRows() != null ? ticketDO.getExpectedAffectedRows() : 0;
        if (config.shouldBlock(rows) || config.shouldWarn(rows)) {
            return "HIGH";
        }
        return "NORMAL";
    }

    // ======= Field 8: PRE 执行结果 (D5) =======

    private String resolvePreExecResult(DmDbChangeRevisionDO revision) {
        if (revision == null) {
            return "-"; // D4: PRE misconfigured
        }
        if (RevisionSourceType.DIRECT_PROD_DML.name().equals(revision.getSourceType())) {
            return "直发（无 PRE）"; // Path B
        }
        // Path A: source PRE ticket FINISHED + manifest pre_exec summary
        Long sourceTicketId = revision.getSourceTicketId();
        if (sourceTicketId == null) {
            return "-"; // no source ticket
        }
        DmApprovalDO sourceTicket = approvalDal.approvalMapper().queryById(sourceTicketId);
        if (sourceTicket == null || sourceTicket.getTicketStatus() != ApprovalStatus.FINISHED) {
            return "-"; // source ticket not finished
        }
        return summarizeManifestPreExec(revision.getStmtManifest());
    }

    private static String summarizeManifestPreExec(String stmtManifest) {
        if (StringUtils.isBlank(stmtManifest)) {
            return "0/0 SUCCESS";
        }
        try {
            List<Map<String, Object>> manifest = JsonUtils.toObj(stmtManifest, List.class);
            if (manifest == null || manifest.isEmpty()) {
                return "0/0 SUCCESS";
            }
            int total = manifest.size();
            long successCount = manifest.stream()
                .filter(item -> "SUCCESS".equals(String.valueOf(item.get("pre_exec"))))
                .count();
            return successCount + "/" + total + " SUCCESS";
        } catch (Exception e) {
            log.warn("[GovChangeForm] failed to parse stmt manifest for pre_exec summary", e);
            return "-"; // parse failure → degrade
        }
    }

    // ======= Field 9: SQL 摘要 =======

    static String truncateSqlForForm(String rawSql, long ticketId) {
        if (StringUtils.isBlank(rawSql)) {
            return "";
        }
        if (rawSql.length() <= SQL_SUMMARY_MAX) {
            return rawSql;
        }
        return rawSql.substring(0, SQL_SUMMARY_MAX) + "\n\n（完整 SQL 见平台 /ticket/" + ticketId + "）";
    }

    // ======= Fields 4-8: ticketDesc multi-line =======

    private static String buildGovFormDesc(DmAuthUserDO userDO, String riskLevel, Long expectedRows,
                                          String sqlHash, String preExecResult, long ticketId) {
        StringBuilder sb = new StringBuilder();
        if (userDO != null && StringUtils.isNotBlank(userDO.getUsername())) {
            sb.append("申请人: ").append(userDO.getUsername()).append("\n");
        }
        sb.append("风险等级: ").append(riskLevel).append("\n");
        if (expectedRows != null) {
            sb.append("预估影响行数: ").append(expectedRows).append("\n");
        }
        if (StringUtils.isNotBlank(sqlHash)) {
            sb.append("SQL Hash: ").append(sqlHash).append("\n");
        }
        sb.append("PRE 执行结果: ").append(preExecResult).append("\n");
        sb.append("完整 SQL 见: /ticket/").append(ticketId);
        return sb.toString();
    }
}
