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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.component.auth.DmAuthServiceForBiz;
import com.clougence.clouddm.console.web.component.governance.GovSqlHashUtils;
import com.clougence.clouddm.console.web.component.governance.PromotionStateMachine;
import com.clougence.clouddm.console.web.model.fo.governance.GovPromoteFO;
import com.clougence.clouddm.console.web.model.fo.governance.GovPromotionListFO;
import com.clougence.clouddm.console.web.model.fo.ticket.DmAddTicketFO;
import com.clougence.clouddm.console.web.model.vo.DmPageVO;
import com.clougence.clouddm.console.web.model.vo.envparam.DmEnvParamTicketDesVO;
import com.clougence.clouddm.console.web.model.vo.governance.AvailableRevisionVO;
import com.clougence.clouddm.console.web.model.vo.governance.PromotionDetailVO;
import com.clougence.clouddm.console.web.model.vo.governance.PromotionVO;
import com.clougence.clouddm.console.web.model.vo.logicaldb.LogicalDbTarget;
import com.clougence.clouddm.console.web.model.vo.ticket.DmTicketResultVO;
import com.clougence.clouddm.console.web.service.approval.ApprovalControlService;
import com.clougence.clouddm.console.web.service.envparam.DmEnvParamService;
import com.clougence.clouddm.console.web.service.governance.GovPromotionService;
import com.clougence.clouddm.console.web.service.logicaldb.LogicalDbService;
import com.clougence.clouddm.console.web.util.DsResPathObj;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.DbChangeGovernDal;
import com.clougence.clouddm.platform.dal.access.LogicalDbDal;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangePromotionMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangeRevisionMapper;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalType;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.approval.SqlContentType;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangePromotionDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeRevisionDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeStmtVersionDO;
import com.clougence.clouddm.platform.dal.model.dbchange.GovEventType;
import com.clougence.clouddm.platform.dal.model.dbchange.PromotionStatus;
import com.clougence.clouddm.platform.dal.model.dbchange.PromotionType;
import com.clougence.clouddm.platform.dal.model.logicaldb.DmLogicalDbDO;
import com.clougence.clouddm.platform.dal.model.logicaldb.GovRole;
import com.clougence.clouddm.platform.dal.util.PageUtils;
import com.clougence.utils.JsonUtils;
import com.clougence.utils.StringUtils;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class GovPromotionServiceImpl implements GovPromotionService {

    private static final String SYSTEM_OPERATOR     = "SYSTEM";
    private static final int    PROMO_CODE_RETRY_MAX = 5;

    @Resource
    private DbChangeGovernDal     dbChangeGovernDal;
    @Resource
    private ApprovalDal           approvalDal;
    @Resource
    private LogicalDbDal          logicalDbDal;
    @Resource
    private LogicalDbService     logicalDbService;
    @Resource
    private DmAuthServiceForBiz  dmAuthServiceForBiz;
    @Resource
    private DmEnvParamService     dmEnvParamService;
    @Resource
    private ApprovalControlService approvalControlService;
    @Resource
    private PromotionStateMachine  stateMachine;
    @Resource
    private PlatformTransactionManager txManager;

    // ======= availableRevisions =======

    @Override
    public List<AvailableRevisionVO> availableRevisions(String puid, String uid) {
        List<DmDbChangeRevisionDO> revisions = dbChangeGovernDal.revisionMapper().listByTenant(puid);

        // Pre-fetch all logical dbs for this tenant (single query per db is wasteful)
        Set<Long> logicalDbIds = revisions.stream().map(DmDbChangeRevisionDO::getLogicalDbId).collect(Collectors.toSet());
        Map<Long, DmLogicalDbDO> logicalDbMap = loadLogicalDbMap(logicalDbIds);

        // Cache binding resolution per logical db (D5: "same-db multi-revision reuse")
        Map<Long, LogicalDbTarget> bindingCache = new HashMap<>();
        // Cache auth result per logical db (D5: "checkResAuth once per db")
        Set<Long> authPassedDbIds = new HashSet<>();

        List<AvailableRevisionVO> result = new ArrayList<>();
        for (DmDbChangeRevisionDO rev : revisions) {
            // Filter 1: source ticket FINISHED (defensive — freeze contract guarantees this)
            if (!isSourceTicketFinished(rev.getSourceTicketId())) {
                continue;
            }

            // Filter 2: manifest every stmt pre_exec == SUCCESS
            if (!manifestAllSuccess(rev.getStmtManifest())) {
                continue;
            }

            // Filter 3: not already consumed by a promotion
            if (dbChangeGovernDal.promotionMapper().queryByRevisionId(rev.getId()) != null) {
                continue;
            }

            // Filter 4: logical db ENABLED
            DmLogicalDbDO logicalDb = logicalDbMap.get(rev.getLogicalDbId());
            if (logicalDb == null || !"ENABLED".equals(logicalDb.getStatus())) {
                continue;
            }

            // Filter 5: getBinding(PROD) resolvable (0 match = excluded, not an error — list semantics)
            LogicalDbTarget target = bindingCache.get(rev.getLogicalDbId());
            if (target == null && !bindingCache.containsKey(rev.getLogicalDbId())) {
                try {
                    target = logicalDbService.getBinding(puid, rev.getLogicalDbId(), GovRole.PROD);
                    bindingCache.put(rev.getLogicalDbId(), target);
                } catch (ErrorMessageException e) {
                    // 0 matches = excluded, not an error in list context
                    bindingCache.put(rev.getLogicalDbId(), null);
                }
            }
            if (target == null) {
                continue;
            }

            // Filter 6: checkResAuth on PROD binding (once per db)
            if (!authPassedDbIds.contains(rev.getLogicalDbId())) {
                boolean hasAuth = dmAuthServiceForBiz.checkResAuthWithoutError(
                    puid, uid, target.getDsId(),
                    new DsResPathObj(target.getResPath()),
                    com.clougence.clouddm.sdk.security.auth.def.SecDataAuthLabel.DM_DAUTH_TICKET,
                    com.clougence.clouddm.sdk.security.auth.AuthKind.DataSource);
                if (!hasAuth) {
                    continue;
                }
                authPassedDbIds.add(rev.getLogicalDbId());
            }

            result.add(toAvailableRevisionVO(rev, logicalDb));
        }
        return result;
    }

    private Map<Long, DmLogicalDbDO> loadLogicalDbMap(Set<Long> ids) {
        Map<Long, DmLogicalDbDO> map = new HashMap<>();
        for (Long id : ids) {
            DmLogicalDbDO db = logicalDbDal.logicalDbMapper().selectById(id);
            if (db != null) {
                map.put(id, db);
            }
        }
        return map;
    }

    private boolean isSourceTicketFinished(Long ticketId) {
        if (ticketId == null) {
            return false;
        }
        DmApprovalDO ticket = approvalDal.approvalMapper().queryById(ticketId);
        return ticket != null && ticket.getTicketStatus() == ApprovalStatus.FINISHED;
    }

    private boolean manifestAllSuccess(String stmtManifest) {
        if (StringUtils.isBlank(stmtManifest)) {
            return false;
        }
        List<Map<String, Object>> items = JsonUtils.toObj(stmtManifest, List.class);
        if (items == null || items.isEmpty()) {
            return false;
        }
        for (Map<String, Object> item : items) {
            Object preExec = item.get("pre_exec");
            if (preExec == null || !"SUCCESS".equals(String.valueOf(preExec))) {
                return false;
            }
        }
        return true;
    }

    private static AvailableRevisionVO toAvailableRevisionVO(DmDbChangeRevisionDO rev, DmLogicalDbDO logicalDb) {
        AvailableRevisionVO vo = new AvailableRevisionVO();
        vo.setRevisionId(rev.getId());
        vo.setRevisionCode(rev.getRevisionCode());
        vo.setChangeType(rev.getChangeType());
        vo.setGmtCreate(rev.getGmtCreate());
        vo.setSourceTicketId(rev.getSourceTicketId());
        vo.setLogicalDbId(rev.getLogicalDbId());
        vo.setLogicalDbResourceName(logicalDb != null ? logicalDb.getResourceName() : null);

        // Count stmts from manifest
        List<Map<String, Object>> manifest = StringUtils.isBlank(rev.getStmtManifest())
            ? List.of()
            : JsonUtils.toObj(rev.getStmtManifest(), List.class);
        vo.setStmtCount(manifest != null ? manifest.size() : 0);
        return vo;
    }

    // ======= promote (gate-one + create promotion + create PROD ticket) =======

    /**
     * Phase 9 gap (design D10, spec §2.2 touchpoint #7):
     * <p>
     * PROD ticket creation succeeds in this method, but the asynchronous PRE_INIT → WAIT_APPROVAL →
     * ApprovalTaskScheduler → external-approval-instance-creation chain will fail in real environments
     * because {@code ChangeApprovalHandler.convertToChangeForm} (L278) depends on CI/CD fields
     * ({@code changeId}/{@code changeOwnerUid}) that governance tickets do not carry. The exception is
     * caught at L233 → {@code failTicket} → ticket FAILED → duty-4 sync maps promotion to FAILED.
     * <p>
     * This is the spec-mandated Phase 9 sequencing gap (touchpoint #7 = Phase 9), NOT a Phase 6 defect.
     * Phase 9 will extend {@code convertToChangeForm} with a governance branch. Until then, real PROD
     * promotions terminate at FAILED in production; unit tests mock the boundary at ticket-creation success.
     */
    @Override
    public long promote(String puid, String uid, GovPromoteFO fo) {
        long revisionId = fo.getRevisionId();

        // Load revision
        DmDbChangeRevisionDO revision = dbChangeGovernDal.revisionMapper().selectById(revisionId);
        if (revision == null) {
            throw new ErrorMessageException("Revision not found: " + revisionId);
        }

        // Tenant check: revision's logical db must belong to this puid
        DmLogicalDbDO logicalDb = logicalDbDal.logicalDbMapper().selectById(revision.getLogicalDbId());
        if (logicalDb == null || !puid.equals(logicalDb.getCreatorUid())) {
            throw new ErrorMessageException("Revision not found: " + revisionId);
        }

        // Evaluate 7-gate (zero side-effect read segment)
        List<GateResult> gateResults = evaluateGate(puid, uid, revision, logicalDb);
        String gateJson = serializeGateResults(gateResults);

        // DENY: write GATE_DENY event (not promotion row), throw business exception (D1)
        boolean allPass = gateResults.stream().allMatch(GateResult::isPass);
        if (!allPass) {
            appendGateDenyEvent(revisionId, uid, gateJson);
            String denySummary = gateResults.stream()
                .filter(g -> !g.isPass())
                .map(g -> "G" + g.getItem() + ": " + g.getReason())
                .collect(Collectors.joining("; "));
            throw new ErrorMessageException("Promotion gate denied: " + denySummary);
        }

        // All PASS — same-transaction: insert promotion + event + create PROD ticket + updateProdApprovalId + ticketInfo
        return executePromotionInTransaction(puid, uid, fo, revision, logicalDb, gateJson);
    }

    private long executePromotionInTransaction(String puid, String uid, GovPromoteFO fo,
                                                DmDbChangeRevisionDO revision, DmLogicalDbDO logicalDb,
                                                String gateJson) {
        LogicalDbTarget prodTarget = logicalDbService.getBinding(puid, revision.getLogicalDbId(), GovRole.PROD);
        long revisionId = revision.getId();
        long logicalDbId = revision.getLogicalDbId();

        // execution_key = GovSqlHashUtils.hash(revisionId + "|" + prodDsId + "|" + logicalDbId)
        String executionKey = GovSqlHashUtils.hash(revisionId + "|" + prodTarget.getDsId() + "|" + logicalDbId);

        for (int attempt = 0; attempt < PROMO_CODE_RETRY_MAX; attempt++) {
            String promotionCode = generatePromotionCode();
            DmDbChangePromotionDO promotion = new DmDbChangePromotionDO();
            promotion.setPromotionCode(promotionCode);
            promotion.setPromotionType(PromotionType.PRE_PROMOTION.name());
            promotion.setRevisionId(revisionId);
            promotion.setLogicalDbId(logicalDbId);
            promotion.setProdEnvId(prodTarget.getEnvId());
            promotion.setProdDsId(prodTarget.getDsId());
            promotion.setProdResPath(prodTarget.getResPath());
            promotion.setExecutionKey(executionKey);
            promotion.setGateResult(gateJson);
            promotion.setStatus(PromotionStatus.CREATED.name());

            try {
                TransactionTemplate transaction = new TransactionTemplate(txManager);
                Long[] promotionIdHolder = new Long[1];
                transaction.executeWithoutResult(status -> {
                    dbChangeGovernDal.promotionMapper().insert(promotion);
                    long promotionId = promotion.getId();
                    promotionIdHolder[0] = promotionId;

                    // Event: PROMOTION_CREATED
                    appendPromotionEvent(promotionId, revisionId, GovEventType.PROMOTION_CREATED, null, null, uid, null);

                    // Create PROD ticket with frozen SQL
                    DmAddTicketFO ticketFO = buildProdTicketFO(fo, revision, logicalDb, prodTarget);
                    DmTicketResultVO result = approvalControlService.createSqlTicket(puid, uid, ticketFO, ApprovalBiz.DM_CHANGE);
                    long ticketId = result.getTicketId();

                    // Update prod_approval_id
                    dbChangeGovernDal.promotionMapper().updateProdApprovalId(promotionId, ticketId);

                    // Write ticketInfo governance fields
                    updateTicketInfoWithGovFields(ticketId, promotionId, revisionId, logicalDbId, GovRole.PROD.name());
                });
                return promotionIdHolder[0];
            } catch (DuplicateKeyException e) {
                // Check if revision_id UNIQUE collision (already promoted)
                DmDbChangePromotionDO existing = dbChangeGovernDal.promotionMapper().queryByRevisionId(revisionId);
                if (existing != null) {
                    throw new ErrorMessageException("Revision " + revisionId + " has already been promoted");
                }
                // execution_key collision (same binding triple) — also means already promoted
                log.warn("[Promotion] promotion_code collision, retrying (attempt {})", attempt + 1);
            }
        }
        throw new ErrorMessageException("Failed to generate unique promotion code after retries");
    }

    private static DmAddTicketFO buildProdTicketFO(GovPromoteFO fo, DmDbChangeRevisionDO revision,
                                                    DmLogicalDbDO logicalDb, LogicalDbTarget prodTarget) {
        DmAddTicketFO ticketFO = new DmAddTicketFO();
        ticketFO.setDbLevels(buildDbLevels(prodTarget));
        ticketFO.setRawSql(revision.getSqlText());
        ticketFO.setRollBackSql(revision.getRollbackSqlText());
        ticketFO.setContentType(SqlContentType.INLINE);
        // ticketTitle: revision_code + " · " + resource_name (server-constructed, @NotBlank)
        ticketFO.setTicketTitle(revision.getRevisionCode() + " · " + logicalDb.getResourceName());
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

    // ======= Gate evaluator (7 gates) =======

    private List<GateResult> evaluateGate(String puid, String uid, DmDbChangeRevisionDO revision, DmLogicalDbDO logicalDb) {
        List<GateResult> results = new ArrayList<>();

        // G1: revision hash integrity + manifest structure
        results.add(evalGate1(revision));

        // G2: source ticket FINISHED + manifest every stmt pre_exec == SUCCESS + manifest ↔ stmt_version current
        results.add(evalGate2(revision));

        // G3: PROD resource auth (DM_DAUTH_TICKET)
        results.add(evalGate3(puid, uid, revision, logicalDb));

        // G4: getBinding(PROD) resolvable → snapshot
        results.add(evalGate4(puid, revision));

        // G5: revision not already consumed
        results.add(evalGate5(revision));

        // G6: DML/MIXED → rollback non-empty
        results.add(evalGate6(revision));

        // G7: PROD env approval template configured + non-Internal
        results.add(evalGate7(puid, revision));

        return results;
    }

    private GateResult evalGate1(DmDbChangeRevisionDO revision) {
        GateResult result = new GateResult(1, "Revision hash integrity");
        String recomputedHash = GovSqlHashUtils.hash(revision.getSqlText());
        if (!recomputedHash.equals(revision.getSqlHash())) {
            result.setFail("Recomputed sql_hash does not match stored hash");
            return result;
        }
        if (StringUtils.isBlank(revision.getStmtManifest())) {
            result.setFail("stmt_manifest is empty");
            return result;
        }
        List<Map<String, Object>> manifest = JsonUtils.toObj(revision.getStmtManifest(), List.class);
        if (manifest == null || manifest.isEmpty()) {
            result.setFail("stmt_manifest has no entries");
            return result;
        }
        for (Map<String, Object> item : manifest) {
            if (item.get("idx") == null || item.get("stmt_hash") == null || item.get("version") == null || item.get("pre_exec") == null) {
                result.setFail("stmt_manifest entry missing required fields (idx/stmt_hash/version/pre_exec)");
                return result;
            }
        }
        result.setPass();
        return result;
    }

    private GateResult evalGate2(DmDbChangeRevisionDO revision) {
        GateResult result = new GateResult(2, "Source ticket FINISHED + manifest pre_exec SUCCESS");
        Long ticketId = revision.getSourceTicketId();
        if (ticketId == null) {
            result.setFail("No source ticket");
            return result;
        }
        DmApprovalDO ticket = approvalDal.approvalMapper().queryById(ticketId);
        if (ticket == null || ticket.getTicketStatus() != ApprovalStatus.FINISHED) {
            result.setFail("Source ticket is not FINISHED");
            return result;
        }
        // manifest every pre_exec == SUCCESS
        List<Map<String, Object>> manifest = JsonUtils.toObj(revision.getStmtManifest(), List.class);
        for (Map<String, Object> item : manifest) {
            String preExec = String.valueOf(item.get("pre_exec"));
            if (!"SUCCESS".equals(preExec)) {
                result.setFail("Manifest stmt " + item.get("idx") + " pre_exec=" + preExec + " (expected SUCCESS)");
                return result;
            }
        }
        // Verify manifest ↔ stmt_version current version hash consistency
        List<DmDbChangeStmtVersionDO> stmtVersions = dbChangeGovernDal.stmtVersionMapper().queryByTicketId(ticketId);
        Map<Integer, DmDbChangeStmtVersionDO> currentVersions = new HashMap<>();
        for (DmDbChangeStmtVersionDO sv : stmtVersions) {
            DmDbChangeStmtVersionDO current = currentVersions.get(sv.getStmtIndex());
            if (current == null || sv.getStmtVersion() > current.getStmtVersion()) {
                currentVersions.put(sv.getStmtIndex(), sv);
            }
        }
        for (Map<String, Object> item : manifest) {
            int idx = ((Number) item.get("idx")).intValue();
            String manifestHash = String.valueOf(item.get("stmt_hash"));
            DmDbChangeStmtVersionDO sv = currentVersions.get(idx);
            if (sv == null) {
                result.setFail("Manifest stmt " + idx + " has no matching stmt_version row");
                return result;
            }
            if (!manifestHash.equals(sv.getStmtHash())) {
                result.setFail("Manifest stmt " + idx + " hash mismatch with current stmt_version");
                return result;
            }
        }
        result.setPass();
        return result;
    }

    private GateResult evalGate3(String puid, String uid, DmDbChangeRevisionDO revision, DmLogicalDbDO logicalDb) {
        GateResult result = new GateResult(3, "PROD resource auth");
        try {
            LogicalDbTarget target = logicalDbService.getBinding(puid, revision.getLogicalDbId(), GovRole.PROD);
            dmAuthServiceForBiz.checkResAuth(
                puid, uid, target.getDsId(),
                new DsResPathObj(target.getResPath()),
                com.clougence.clouddm.sdk.security.auth.def.SecDataAuthLabel.DM_DAUTH_TICKET,
                com.clougence.clouddm.sdk.security.auth.AuthKind.DataSource);
            result.setPass();
        } catch (ErrorMessageException e) {
            result.setFail("PROD resource auth denied: " + e.getMessage());
        }
        return result;
    }

    private GateResult evalGate4(String puid, DmDbChangeRevisionDO revision) {
        GateResult result = new GateResult(4, "PROD binding resolvable");
        try {
            logicalDbService.getBinding(puid, revision.getLogicalDbId(), GovRole.PROD);
            result.setPass();
        } catch (ErrorMessageException e) {
            result.setFail("PROD binding not resolvable: " + e.getMessage());
        }
        return result;
    }

    private GateResult evalGate5(DmDbChangeRevisionDO revision) {
        GateResult result = new GateResult(5, "Revision not already promoted");
        DmDbChangePromotionDO existing = dbChangeGovernDal.promotionMapper().queryByRevisionId(revision.getId());
        if (existing != null) {
            result.setFail("Revision already promoted (promotion " + existing.getPromotionCode() + ")");
            return result;
        }
        result.setPass();
        return result;
    }

    private GateResult evalGate6(DmDbChangeRevisionDO revision) {
        GateResult result = new GateResult(6, "Rollback SQL required for DML/MIXED");
        String changeType = revision.getChangeType();
        if ("DML".equals(changeType) || "MIXED".equals(changeType)) {
            if (StringUtils.isBlank(revision.getRollbackSqlText())) {
                result.setFail("Rollback SQL is required for " + changeType + " revision");
                return result;
            }
        }
        result.setPass();
        return result;
    }

    private GateResult evalGate7(String puid, DmDbChangeRevisionDO revision) {
        GateResult result = new GateResult(7, "PROD approval template configured (non-Internal)");
        try {
            LogicalDbTarget target = logicalDbService.getBinding(puid, revision.getLogicalDbId(), GovRole.PROD);
            DmEnvParamTicketDesVO config = dmEnvParamService.querySqlTicketInfoParam(puid, target.getEnvId());
            if (config == null
                || !config.isOpenTicket()
                || StringUtils.isBlank(config.getType())
                || ApprovalType.Internal.name().equals(config.getType())
                || config.isDelete()) {
                result.setFail("PROD must configure a third-party approval template (Internal not allowed)");
                return result;
            }
            result.setPass();
        } catch (ErrorMessageException e) {
            result.setFail("Cannot resolve PROD binding for template check: " + e.getMessage());
        }
        return result;
    }

    // ======= promotionList =======

    @Override
    public DmPageVO<PromotionVO> promotionList(String puid, GovPromotionListFO fo) {
        Page<?> page = PageUtils.startPage(fo.getPage());
        IPage<DmDbChangePromotionDO> promotions = dbChangeGovernDal.promotionMapper()
            .listPromotionByConditionAndPage(page, puid, fo.getStatus(), fo.getPromotionType());

        DmPageVO<PromotionVO> result = new DmPageVO<>(promotions);
        List<PromotionVO> vos = promotions.getRecords().stream()
            .map(GovPromotionServiceImpl::toPromotionVO)
            .collect(Collectors.toList());
        result.setRecords(vos);
        return result;
    }

    private static PromotionVO toPromotionVO(DmDbChangePromotionDO p) {
        PromotionVO vo = new PromotionVO();
        vo.setId(p.getId());
        vo.setPromotionCode(p.getPromotionCode());
        vo.setPromotionType(p.getPromotionType());
        vo.setRevisionId(p.getRevisionId());
        vo.setLogicalDbId(p.getLogicalDbId());
        vo.setStatus(p.getStatus());
        vo.setGmtCreate(p.getGmtCreate());
        vo.setGmtModified(p.getGmtModified());
        return vo;
    }

    // ======= promotionDetail =======

    @Override
    public long resolveLogicalDbId(String puid, long promotionId) {
        DmDbChangePromotionDO promotion = dbChangeGovernDal.promotionMapper().selectById(promotionId);
        if (promotion == null) {
            throw new ErrorMessageException("Promotion not found: " + promotionId);
        }
        DmLogicalDbDO logicalDb = logicalDbDal.logicalDbMapper().selectById(promotion.getLogicalDbId());
        if (logicalDb == null || !puid.equals(logicalDb.getCreatorUid())) {
            throw new ErrorMessageException("Promotion not found: " + promotionId);
        }
        return promotion.getLogicalDbId();
    }

    @Override
    public PromotionDetailVO promotionDetail(String puid, long promotionId) {
        DmDbChangePromotionDO promotion = dbChangeGovernDal.promotionMapper().selectById(promotionId);
        if (promotion == null) {
            throw new ErrorMessageException("Promotion not found: " + promotionId);
        }

        // Tenant check: logical db must belong to puid
        DmLogicalDbDO logicalDb = logicalDbDal.logicalDbMapper().selectById(promotion.getLogicalDbId());
        if (logicalDb == null || !puid.equals(logicalDb.getCreatorUid())) {
            throw new ErrorMessageException("Promotion not found: " + promotionId);
        }

        PromotionDetailVO vo = new PromotionDetailVO();
        vo.setId(promotion.getId());
        vo.setPromotionCode(promotion.getPromotionCode());
        vo.setPromotionType(promotion.getPromotionType());
        vo.setRevisionId(promotion.getRevisionId());
        vo.setLogicalDbId(promotion.getLogicalDbId());
        vo.setProdEnvId(promotion.getProdEnvId());
        vo.setProdDsId(promotion.getProdDsId());
        vo.setProdResPath(promotion.getProdResPath());
        vo.setProdApprovalId(promotion.getProdApprovalId());
        vo.setExecutionKey(promotion.getExecutionKey());
        vo.setStatus(promotion.getStatus());
        vo.setGmtCreate(promotion.getGmtCreate());
        vo.setGmtModified(promotion.getGmtModified());

        // Parse gate_result JSON to VO list
        vo.setGateResult(parseGateResult(promotion.getGateResult()));
        // preflight_result always empty in Phase 6
        vo.setPreflightResult(List.of());

        // Revision summary
        DmDbChangeRevisionDO revision = dbChangeGovernDal.revisionMapper().selectById(promotion.getRevisionId());
        if (revision != null) {
            PromotionDetailVO.RevisionSummaryVO revVO = new PromotionDetailVO.RevisionSummaryVO();
            revVO.setRevisionId(revision.getId());
            revVO.setRevisionCode(revision.getRevisionCode());
            revVO.setChangeType(revision.getChangeType());
            revVO.setGmtCreate(revision.getGmtCreate());
            revVO.setSourceTicketId(revision.getSourceTicketId());
            revVO.setSqlHash(revision.getSqlHash());
            List<Map<String, Object>> manifest = StringUtils.isBlank(revision.getStmtManifest())
                ? List.of()
                : JsonUtils.toObj(revision.getStmtManifest(), List.class);
            revVO.setStmtCount(manifest != null ? manifest.size() : 0);
            vo.setRevision(revVO);
        }

        // Events: by promotion_id (includes GATE_DENY history via revision_id)
        List<DmDbChangeEventDO> events = dbChangeGovernDal.eventMapper().queryByPromotionId(promotionId);
        // Also include GATE_DENY events by revision_id (they were written before promotion row existed)
        List<DmDbChangeEventDO> denyEvents = dbChangeGovernDal.eventMapper().queryByRevisionId(promotion.getRevisionId());
        List<DmDbChangeEventDO> allEvents = new ArrayList<>(denyEvents);
        allEvents.addAll(events);
        allEvents.sort((a, b) -> {
            Date da = a.getGmtCreate();
            Date db = b.getGmtCreate();
            if (da == null || db == null) {
                return 0;
            }
            return da.compareTo(db);
        });

        List<PromotionDetailVO.EventHandlerVO> eventVOs = new ArrayList<>();
        for (DmDbChangeEventDO event : allEvents) {
            PromotionDetailVO.EventHandlerVO evVO = new PromotionDetailVO.EventHandlerVO();
            evVO.setId(event.getId());
            evVO.setEventType(event.getEventType());
            evVO.setFromStatus(event.getFromStatus());
            evVO.setToStatus(event.getToStatus());
            evVO.setOperatorUid(event.getOperatorUid());
            evVO.setGmtCreate(event.getGmtCreate());
            evVO.setEventData(event.getEventData());
            eventVOs.add(evVO);
        }
        vo.setEvents(eventVOs);

        return vo;
    }

    private List<PromotionDetailVO.GateItemVO> parseGateResult(String gateResultJson) {
        if (StringUtils.isBlank(gateResultJson)) {
            return List.of();
        }
        List<Map<String, Object>> items = JsonUtils.toObj(gateResultJson, List.class);
        if (items == null) {
            return List.of();
        }
        List<PromotionDetailVO.GateItemVO> result = new ArrayList<>();
        for (Map<String, Object> item : items) {
            PromotionDetailVO.GateItemVO gvo = new PromotionDetailVO.GateItemVO();
            gvo.setItem(((Number) item.get("item")).intValue());
            gvo.setLabel(String.valueOf(item.get("label")));
            gvo.setPass(Boolean.TRUE.equals(item.get("pass")));
            gvo.setReason(item.get("reason") != null ? String.valueOf(item.get("reason")) : null);
            gvo.setTimestamp(item.get("timestamp") != null ? String.valueOf(item.get("timestamp")) : null);
            result.add(gvo);
        }
        return result;
    }

    // ======= helpers =======

    private String serializeGateResults(List<GateResult> results) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (GateResult g : results) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("item", g.getItem());
            item.put("label", g.getLabel());
            item.put("pass", g.isPass());
            item.put("reason", g.getReason());
            item.put("timestamp", g.getTimestamp());
            items.add(item);
        }
        return JsonUtils.toJson(items);
    }

    private void appendGateDenyEvent(Long revisionId, String uid, String gateJson) {
        DmDbChangeEventDO event = new DmDbChangeEventDO();
        event.setRevisionId(revisionId);
        event.setEventType(GovEventType.GATE_DENY.name());
        event.setOperatorUid(uid);

        Map<String, Object> data = new HashMap<>();
        data.put("gateResult", gateJson);
        data.put("revisionId", revisionId);
        data.put("operator", uid);
        event.setEventData(JsonUtils.toJson(data));

        dbChangeGovernDal.eventMapper().insert(event);
    }

    private void appendPromotionEvent(Long promotionId, Long revisionId, GovEventType eventType,
                                       String fromStatus, String toStatus, String operatorUid, String eventData) {
        DmDbChangeEventDO event = new DmDbChangeEventDO();
        event.setPromotionId(promotionId);
        event.setRevisionId(revisionId);
        event.setEventType(eventType.name());
        event.setFromStatus(fromStatus);
        event.setToStatus(toStatus);
        event.setOperatorUid(operatorUid);
        event.setEventData(eventData);
        dbChangeGovernDal.eventMapper().insert(event);
    }

    // ======= Gate result model =======

    private static class GateResult {
        private final int    item;
        private final String label;
        private boolean      pass;
        private String       reason;
        private final String timestamp;

        GateResult(int item, String label) {
            this.item = item;
            this.label = label;
            this.timestamp = new Date().toString();
        }

        void setPass() { this.pass = true; this.reason = null; }
        void setFail(String reason) { this.pass = false; this.reason = reason; }

        int getItem() { return item; }
        String getLabel() { return label; }
        boolean isPass() { return pass; }
        String getReason() { return reason; }
        String getTimestamp() { return timestamp; }
    }
}
