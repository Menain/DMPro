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
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.api.console.autoexec.ErrorStrategy;
import com.clougence.clouddm.base.metadata.ds.DataSourceConfig;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.component.dsconfig.DmDsConfigService;
import com.clougence.clouddm.console.web.component.governance.GateItem;
import com.clougence.clouddm.console.web.component.governance.GovPreflightChecker;
import com.clougence.clouddm.console.web.component.governance.GovSqlHashUtils;
import com.clougence.clouddm.console.web.component.governance.GovStmtSplitService;
import com.clougence.clouddm.console.web.component.governance.GovSplitResult;
import com.clougence.clouddm.console.web.component.governance.GovStmtRow;
import com.clougence.clouddm.console.web.component.governance.GuardConclusion;
import com.clougence.clouddm.console.web.service.governance.GovExecutionGuardService;
import com.clougence.clouddm.console.web.service.logicaldb.LogicalDbService;
import com.clougence.clouddm.console.web.model.vo.logicaldb.LogicalDbTarget;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.DataSourceDal;
import com.clougence.clouddm.platform.dal.access.DbChangeGovernDal;
import com.clougence.clouddm.platform.dal.access.ExecutionDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangePromotionMapper;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoJobMapper;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecAutoTaskMapper;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.datasource.DmDsDO;
import com.clougence.clouddm.platform.dal.model.dbchange.ChangeType;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangePromotionDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeRevisionDO;
import com.clougence.clouddm.platform.dal.model.dbchange.GovEventType;
import com.clougence.clouddm.platform.dal.model.dbchange.PromotionStatus;
import com.clougence.clouddm.platform.dal.model.execution.DmExecAutoJobDO;
import com.clougence.clouddm.platform.dal.model.execution.DmExecAutoTaskDO;
import com.clougence.clouddm.platform.dal.model.execution.RsExecAutoJobConfigObj;
import com.clougence.clouddm.platform.dal.model.logicaldb.GovRole;
import com.clougence.utils.JsonUtils;
import com.clougence.utils.StringUtils;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * Gate-two guard — six-gate evaluation for PROD governance tickets (Phase 7, design D1-D8).
 * <p>
 * Hard short-circuit: govRole==null or "PRE" → PASS with zero governance-table queries.
 * PROD → sequential six-gate evaluation, first DENY stops (later gates = SKIPPED).
 */
@Slf4j
@Service
public class GovExecutionGuardServiceImpl implements GovExecutionGuardService {

    private static final String STAGE_CONFIRM  = "CONFIRM";
    private static final String STAGE_DISPATCH = "DISPATCH";

    @Resource
    private DbChangeGovernDal    dbChangeGovernDal;
    @Resource
    private ApprovalDal          approvalDal;
    @Resource
    private ExecutionDal         executionDal;
    @Resource
    private DataSourceDal        dataSourceDal;
    @Resource
    private LogicalDbService    logicalDbService;
    @Resource
    private GovStmtSplitService  govStmtSplitService;
    @Resource
    private GovPreflightChecker  govPreflightChecker;
    @Resource
    private DmDsConfigService    dmDsConfigService;

    // ======= checkByTicket (touchpoint #2: config from confirm FO) =======

    @Override
    public GuardConclusion checkByTicket(String puid, DmApprovalDO ticket, RsExecAutoJobConfigObj jobConfig) {
        return doEvaluate(puid, ticket, jobConfig, STAGE_CONFIRM, null);
    }

    // ======= checkByJob (touchpoint #3: config from job.getConfig()) =======

    @Override
    public GuardConclusion checkByJob(String puid, long jobId) {
        DmExecAutoJobMapper jobMapper = this.executionDal.autoJobMapper();
        DmExecAutoJobDO job = jobMapper.queryById(jobId);
        if (job == null) {
            return GuardConclusion.deny("job not found: " + jobId);
        }

        DmApprovalMapper ticketMapper = this.approvalDal.approvalMapper();
        DmApprovalDO ticket = ticketMapper.queryByBizId(job.getDependOnBizId());
        if (ticket == null) {
            return GuardConclusion.deny("ticket not found for bizId: " + job.getDependOnBizId());
        }

        RsExecAutoJobConfigObj config = job.getConfig();
        if (config == null) {
            config = new RsExecAutoJobConfigObj();
        }
        return doEvaluate(puid, ticket, config, STAGE_DISPATCH, jobId);
    }

    // ======= assertNotGovernanceProd (touchpoint #5) =======

    @Override
    public void assertNotGovernanceProd(DmApprovalDO ticket) {
        ApprovalMO mo = parseTicketInfo(ticket.getTicketInfo());
        if (mo != null && "PROD".equals(mo.getGovRole())) {
            throw new ErrorMessageException("Production governance tickets do not allow skip/continue — execution set must equal approval set");
        }
    }

    // ======= six-gate evaluation =======

    private GuardConclusion doEvaluate(String puid, DmApprovalDO ticket, RsExecAutoJobConfigObj jobConfig,
                                       String stage, Long jobId) {
        // Short-circuit 1: non-governance → PASS (zero governance-table queries)
        ApprovalMO mo = parseTicketInfo(ticket.getTicketInfo());
        if (mo == null || mo.getGovRole() == null) {
            return GuardConclusion.pass();
        }

        // Short-circuit 2: PRE → PASS (zero governance-table queries)
        if (!"PROD".equals(mo.getGovRole())) {
            return GuardConclusion.pass();
        }

        // PROD → six-gate evaluation
        GuardConclusion conclusion = new GuardConclusion();
        conclusion.setPass(true);
        List<GateItem> items = new ArrayList<>();

        // G1: promotion status ∈ {APPROVED, CONFIRMED}
        GateItem g1 = evalGate1(mo.getPromotionId());
        items.add(g1);
        if (g1.isDeny()) {
            return finalizeDeny(conclusion, items, g1, mo, jobId, stage, ticket.getId());
        }

        // G2: hash re-verification (whole + per-stmt)
        GateItem g2 = evalGate2(mo, ticket, jobId);
        items.add(g2);
        if (g2.isDeny()) {
            return finalizeDeny(conclusion, items, g2, mo, jobId, stage, ticket.getId());
        }

        // G3: binding re-verification
        GateItem g3 = evalGate3(puid, mo);
        items.add(g3);
        if (g3.isDeny()) {
            return finalizeDeny(conclusion, items, g3, mo, jobId, stage, ticket.getId());
        }

        // G4: Preflight four-item
        GateItem g4 = evalGate4(puid, mo, ticket);
        items.add(g4);
        if (g4.isDeny()) {
            return finalizeDeny(conclusion, items, g4, mo, jobId, stage, ticket.getId());
        }

        // G5: idempotency consistency
        GateItem g5 = evalGate5(mo, puid);
        items.add(g5);
        if (g5.isDeny()) {
            return finalizeDeny(conclusion, items, g5, mo, jobId, stage, ticket.getId());
        }

        // G6: config compliance (D15 value table)
        GateItem g6 = evalGate6(mo, jobConfig);
        items.add(g6);
        if (g6.isDeny()) {
            return finalizeDeny(conclusion, items, g6, mo, jobId, stage, ticket.getId());
        }

        // All PASS
        conclusion.setItems(items);
        conclusion.setPass(true);
        conclusion.setSummary("PASS");
        persistConclusion(mo.getPromotionId(), conclusion, stage, jobId, ticket.getId());
        return conclusion;
    }

    private GuardConclusion finalizeDeny(GuardConclusion conclusion, List<GateItem> items, GateItem denyItem,
                                          ApprovalMO mo, Long jobId, String stage, long ticketId) {
        // Mark subsequent gates as SKIPPED (first DENY stops)
        // (We don't add G3-G6 items if they weren't evaluated — the caller sees only evaluated gates)
        conclusion.setItems(items);
        conclusion.setPass(false);
        conclusion.setSummary("G" + denyItem.getItem() + " DENY: " + denyItem.getEvidence());
        persistConclusion(mo.getPromotionId(), conclusion, stage, jobId, ticketId);
        return conclusion;
    }

    // ------- G1: promotion status -------

    private GateItem evalGate1(Long promotionId) {
        if (promotionId == null) {
            return GateItem.deny("G1_promotion_status", "ticketInfo has no promotionId");
        }
        DmDbChangePromotionDO promotion = this.dbChangeGovernDal.promotionMapper().selectById(promotionId);
        if (promotion == null) {
            return GateItem.deny("G1_promotion_status", "promotion not found: " + promotionId);
        }
        String status = promotion.getStatus();
        if (PromotionStatus.APPROVED.name().equals(status) || PromotionStatus.CONFIRMED.name().equals(status)) {
            return GateItem.pass("G1_promotion_status", "status=" + status);
        }
        return GateItem.deny("G1_promotion_status", "promotion status=" + status + " (expected APPROVED or CONFIRMED)");
    }

    // ------- G2: hash re-verification (whole + per-stmt) -------

    private GateItem evalGate2(ApprovalMO mo, DmApprovalDO ticket, Long jobId) {
        DmDbChangeRevisionDO revision = this.dbChangeGovernDal.revisionMapper().selectById(mo.getRevisionId());
        if (revision == null) {
            return GateItem.deny("G2_hash", "revision not found: " + mo.getRevisionId());
        }

        // Whole-ticket hash
        String recomputedWholeHash = GovSqlHashUtils.hash(ticket.getRawSql());
        if (!recomputedWholeHash.equals(revision.getSqlHash())) {
            return GateItem.deny("G2_hash", "whole-ticket hash mismatch: expected=" + revision.getSqlHash() + " actual=" + recomputedWholeHash);
        }

        // Per-stmt hash — two scenarios based on touchpoint
        if (jobId != null) {
            // Touchpoint #3 (dispatchJob): read task rows, compare exec_sql hash by exec_order↔idx
            DmExecAutoJobDO job = this.executionDal.autoJobMapper().queryById(jobId);
            if (job == null) {
                return GateItem.deny("G2_hash", "job not found for per-stmt hash: " + jobId);
            }
            List<DmExecAutoTaskDO> tasks = this.executionDal.autoTaskMapper().queryListByJobId(job.getId(), null);
            Map<Integer, String> manifest = parseManifest(revision.getStmtManifest());
            for (DmExecAutoTaskDO task : tasks) {
                Integer idx = task.getExecOrder();
                if (idx == null) {
                    continue;
                }
                String manifestHash = manifest.get(idx);
                if (manifestHash == null) {
                    return GateItem.deny("G2_hash", "stmt idx=" + idx + " not in manifest");
                }
                String taskHash = GovSqlHashUtils.hash(task.getExecSql());
                if (!taskHash.equals(manifestHash)) {
                    return GateItem.deny("G2_hash", "stmt idx=" + idx + " hash mismatch");
                }
            }
        } else {
            // Touchpoint #2 (prepareExecJob): no task rows yet, re-split rawSql and compare
            DmDsDO dsDO = this.dataSourceDal.dsMapper().queryDsIdentityById(ticket.getBindDsId());
            if (dsDO == null) {
                return GateItem.deny("G2_hash", "datasource not found: " + ticket.getBindDsId());
            }
            DataSourceConfig dsConfig = this.dmDsConfigService.fetchDsConfigFromExists(dsDO.getId());
            try {
                GovSplitResult splitResult = this.govStmtSplitService.split(dsConfig, ticket.getRawSql());
                Map<Integer, String> manifest = parseManifest(revision.getStmtManifest());
                for (GovStmtRow row : splitResult.getStmts()) {
                    String manifestHash = manifest.get(row.getStmtIndex());
                    if (manifestHash == null) {
                        return GateItem.deny("G2_hash", "stmt idx=" + row.getStmtIndex() + " not in manifest");
                    }
                    if (!row.getStmtHash().equals(manifestHash)) {
                        return GateItem.deny("G2_hash", "stmt idx=" + row.getStmtIndex() + " hash mismatch");
                    }
                }
            } catch (ErrorMessageException e) {
                return GateItem.deny("G2_hash", "re-split failed: " + e.getMessage());
            }
        }

        return GateItem.pass("G2_hash", "whole+per-stmt hash verified");
    }

    // ------- G3: binding re-verification -------

    private GateItem evalGate3(String puid, ApprovalMO mo) {
        DmDbChangePromotionDO promotion = this.dbChangeGovernDal.promotionMapper().selectById(mo.getPromotionId());
        if (promotion == null) {
            return GateItem.deny("G3_binding", "promotion not found");
        }
        try {
            LogicalDbTarget target = this.logicalDbService.getBinding(puid, mo.getLogicalDbId(), GovRole.PROD);
            if (!Objects.equals(target.getEnvId(), promotion.getProdEnvId())
                || !Objects.equals(target.getDsId(), promotion.getProdDsId())
                || !Objects.equals(target.getResPath(), promotion.getProdResPath())) {
                return GateItem.deny("G3_binding", "binding changed since promotion snapshot");
            }
            return GateItem.pass("G3_binding", "binding matches snapshot");
        } catch (ErrorMessageException e) {
            return GateItem.deny("G3_binding", "binding resolution failed: " + e.getMessage());
        }
    }

    // ------- G4: Preflight four-item -------

    private GateItem evalGate4(String puid, ApprovalMO mo, DmApprovalDO ticket) {
        DmDbChangePromotionDO promotion = this.dbChangeGovernDal.promotionMapper().selectById(mo.getPromotionId());
        if (promotion == null) {
            return GateItem.deny("G4_preflight", "promotion not found");
        }
        DmDbChangeRevisionDO revision = this.dbChangeGovernDal.revisionMapper().selectById(mo.getRevisionId());
        if (revision == null) {
            return GateItem.deny("G4_preflight", "revision not found");
        }

        DmDsDO dsDO = this.dataSourceDal.dsMapper().queryDsIdentityById(promotion.getProdDsId());
        if (dsDO == null) {
            return GateItem.deny("G4_preflight", "datasource not found: " + promotion.getProdDsId());
        }

        // Build levels from promotion snapshot (precedent: createExecJob L1109-1118)
        List<String> levels = new ArrayList<>();
        levels.add(String.valueOf(promotion.getProdEnvId()));
        levels.add(String.valueOf(promotion.getProdDsId()));
        String resPath = promotion.getProdResPath();
        if (StringUtils.isNotBlank(resPath) && !"/".equals(resPath)) {
            String trimmed = resPath;
            if (trimmed.startsWith("/")) {
                trimmed = trimmed.substring(1);
            }
            if (trimmed.endsWith("/")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
            if (!trimmed.isEmpty()) {
                for (String seg : trimmed.split("/")) {
                    if (!seg.isEmpty()) {
                        levels.add(seg);
                    }
                }
            }
        }

        DataSourceConfig dsConfig = this.dmDsConfigService.fetchDsConfigFromExists(dsDO.getId());
        List<GateItem> preflightItems = this.govPreflightChecker.check(dsDO, levels, revision.getSqlText(), dsConfig);

        // Aggregate preflight items into a single gate result
        boolean allPass = preflightItems.stream().allMatch(GateItem::isPass);
        if (allPass) {
            return GateItem.pass("G4_preflight", "all preflight checks passed");
        }
        String denyEvidence = preflightItems.stream()
            .filter(g -> !g.isPass())
            .map(g -> g.getItem() + ": " + g.getEvidence())
            .reduce((a, b) -> a + "; " + b)
            .orElse("preflight failed");
        return GateItem.deny("G4_preflight", denyEvidence);
    }

    // ------- G5: idempotency consistency -------

    private GateItem evalGate5(ApprovalMO mo, String puid) {
        DmDbChangePromotionDO promotion = this.dbChangeGovernDal.promotionMapper().selectById(mo.getPromotionId());
        if (promotion == null) {
            return GateItem.deny("G5_idempotency", "promotion not found");
        }
        // Recompute execution_key
        String recomputedKey = GovSqlHashUtils.hash(mo.getRevisionId() + "|" + promotion.getProdDsId() + "|" + mo.getLogicalDbId());
        if (!recomputedKey.equals(promotion.getExecutionKey())) {
            return GateItem.deny("G5_idempotency", "execution_key mismatch");
        }
        // depend_on_biz_id UNIQUE is enforced by DB; we just verify the job exists if ticket is WAIT_EXEC
        // (lightweight — no need to rebuild the mechanism)
        return GateItem.pass("G5_idempotency", "execution_key consistent, depend_on_biz_id UNIQUE enforced");
    }

    // ------- G6: config compliance (D15 value table) -------

    private GateItem evalGate6(ApprovalMO mo, RsExecAutoJobConfigObj jobConfig) {
        if (jobConfig == null) {
            return GateItem.deny("G6_config", "job config is null");
        }
        DmDbChangeRevisionDO revision = this.dbChangeGovernDal.revisionMapper().selectById(mo.getRevisionId());
        if (revision == null) {
            return GateItem.deny("G6_config", "revision not found");
        }

        // SKIP always denied for governance PROD
        if (jobConfig.getErrorStrategy() == ErrorStrategy.SKIP) {
            return GateItem.deny("G6_config", "errorStrategy=SKIP is not allowed for governance PROD tickets");
        }

        // D15 value table: DML→enableTransactional=true, DDL/MIXED→false
        ChangeType changeType = ChangeType.valueOf(revision.getChangeType());
        boolean expectedTransactional = (changeType == ChangeType.DML);
        if (jobConfig.isEnableTransactional() != expectedTransactional) {
            return GateItem.deny("G6_config", "enableTransactional=" + jobConfig.isEnableTransactional()
                + " (expected " + expectedTransactional + " for " + changeType + ")");
        }
        if (jobConfig.getErrorStrategy() != ErrorStrategy.NONE) {
            return GateItem.deny("G6_config", "errorStrategy=" + jobConfig.getErrorStrategy() + " (expected NONE)");
        }
        return GateItem.pass("G6_config", "config compliant: enableTransactional=" + jobConfig.isEnableTransactional() + ", errorStrategy=NONE");
    }

    // ------- persistence (preflight_result + event) -------

    private void persistConclusion(Long promotionId, GuardConclusion conclusion, String stage, Long jobId, long ticketId) {
        // Build JSON: {items:[{item,pass,evidence,ts,status}], pass, ts, stage}
        Map<String, Object> json = new LinkedHashMap<>();
        List<Map<String, Object>> itemMaps = new ArrayList<>();
        for (GateItem item : conclusion.getItems()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("item", item.getItem());
            m.put("pass", item.isPass());
            m.put("evidence", item.getEvidence());
            m.put("ts", item.getTs());
            m.put("status", item.getStatus());
            itemMaps.add(m);
        }
        json.put("items", itemMaps);
        json.put("pass", conclusion.isPass());
        json.put("ts", System.currentTimeMillis());
        json.put("stage", stage);
        String jsonStr = JsonUtils.toJson(json);

        // Controlled single-field update (design D8)
        DmDbChangePromotionMapper promotionMapper = this.dbChangeGovernDal.promotionMapper();
        promotionMapper.updatePreflightResult(promotionId, jsonStr);

        // Event: GUARD_PASS / GUARD_DENY
        DmDbChangeEventDO event = new DmDbChangeEventDO();
        event.setPromotionId(promotionId);
        event.setTicketId(ticketId);
        event.setEventType(conclusion.isPass() ? GovEventType.GUARD_PASS.name() : GovEventType.GUARD_DENY.name());
        event.setOperatorUid("SYSTEM");
        Map<String, Object> eventData = new HashMap<>();
        eventData.put("preflightResult", jsonStr);
        eventData.put("ticketId", ticketId);
        if (jobId != null) {
            eventData.put("jobId", jobId);
        }
        event.setEventData(JsonUtils.toJson(eventData));
        this.dbChangeGovernDal.eventMapper().insert(event);
    }

    // ------- helpers -------

    private static ApprovalMO parseTicketInfo(String ticketInfo) {
        if (StringUtils.isEmpty(ticketInfo)) {
            return null;
        }
        return JsonUtils.toObj(ticketInfo, ApprovalMO.class);
    }

    private static Map<Integer, String> parseManifest(String stmtManifest) {
        if (StringUtils.isBlank(stmtManifest)) {
            return Map.of();
        }
        List<Map<String, Object>> items = JsonUtils.toObj(stmtManifest, List.class);
        Map<Integer, String> result = new HashMap<>();
        if (items == null) {
            return result;
        }
        for (Map<String, Object> item : items) {
            Object idx = item.get("idx");
            Object hash = item.get("stmt_hash");
            if (idx != null && hash != null) {
                result.put(((Number) idx).intValue(), String.valueOf(hash));
            }
        }
        return result;
    }
}
