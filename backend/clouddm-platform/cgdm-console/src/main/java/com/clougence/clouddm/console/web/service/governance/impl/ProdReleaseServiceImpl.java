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
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.api.console.autoexec.ErrorStrategy;
import com.clougence.clouddm.console.web.component.approval.ApprovalStateService;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.component.auth.DmAuthServiceForBiz;
import com.clougence.clouddm.console.web.component.execute.AutoExecService;
import com.clougence.clouddm.console.web.component.governance.GovSqlHashUtils;
import com.clougence.clouddm.console.web.component.governance.PrecheckConclusion;
import com.clougence.clouddm.console.web.component.governance.ProdReleaseStateMachine;
import com.clougence.clouddm.console.web.component.governance.ReleaseSchemaDiffService;
import com.clougence.clouddm.console.web.component.governance.ReleaseSchemaPrecheckService;
import com.clougence.clouddm.console.web.component.governance.GovReturnNotifyService;
import com.clougence.clouddm.console.web.global.i18n.DmI18nUtils;
import com.clougence.clouddm.console.web.global.i18n.I18nDmMsgKeys;
import com.clougence.clouddm.console.web.model.fo.prodrelease.ProdReleaseCreateFO;
import com.clougence.clouddm.console.web.model.fo.prodrelease.ProdReleaseReturnStmtFO;
import com.clougence.clouddm.console.web.model.fo.ticket.DmAddTicketFO;
import com.clougence.clouddm.console.web.model.vo.prodrelease.ProdReleaseDetailVO;
import com.clougence.clouddm.console.web.model.vo.prodrelease.ProdReleaseListVO;
import com.clougence.clouddm.console.web.model.vo.ticket.DmTicketResultVO;
import com.clougence.clouddm.console.web.service.approval.ApprovalControlService;
import com.clougence.clouddm.console.web.service.governance.ProdReleaseService;
import com.clougence.clouddm.console.web.util.DsResPathObj;
import com.clougence.clouddm.console.web.util.DmTeamUtils;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.DbChangeEventDal;
import com.clougence.clouddm.platform.dal.access.DbPairDal;
import com.clougence.clouddm.platform.dal.access.DataSourceDal;
import com.clougence.clouddm.platform.dal.access.ProdReleaseDal;
import com.clougence.clouddm.platform.dal.access.TicketDbStmtDal;
import com.clougence.clouddm.platform.dal.mapper.datasource.DmDsMapper;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.approval.SqlContentType;
import com.clougence.clouddm.platform.dal.model.datasource.DmDsDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO;
import com.clougence.clouddm.platform.dal.model.dbchange.GovEventType;
import com.clougence.clouddm.platform.dal.model.dbpair.DmDbPairDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketStmtSchemaDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseStmtDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseStmtSchemaDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.ProdReleaseStatus;
import com.clougence.utils.JsonUtils;
import com.clougence.utils.StringUtils;
import com.clougence.utils.format.DateFormatType;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class ProdReleaseServiceImpl implements ProdReleaseService {

    private static final String EXEC_STATUS_PENDING  = "PENDING";
    private static final String EXEC_STATUS_EXECUTING = "EXECUTING";
    private static final String EXEC_STATUS_SUCCESS  = "SUCCESS";
    private static final String EXEC_STATUS_FAILED  = "FAILED";
    private static final String TICKET_TYPE_PRE_DDL  = "PRE_DDL";
    private static final String STATUS_ENABLED       = "ENABLED";
    private static final String SYSTEM_OPERATOR      = "SYSTEM";

    @Resource
    private ProdReleaseDal          prodReleaseDal;
    @Resource
    private DbChangeEventDal      dbChangeEventDal;
    @Resource
    private TicketDbStmtDal        ticketDbStmtDal;
    @Resource
    private ApprovalDal            approvalDal;
    @Resource
    private DataSourceDal          dsDal;
    @Resource
    private DbPairDal              dbPairDal;
    @Resource
    private ApprovalControlService  approvalControlService;
    @Resource
    private ProdReleaseStateMachine releaseStateMachine;
    @Resource
    private DmAuthServiceForBiz     dmAuthServiceForBiz;
    @Resource
    private AutoExecService         autoExecService;
    @Resource
    private ApprovalStateService    approvalStateService;
    @Resource
    private ReleaseSchemaPrecheckService releaseSchemaPrecheckService;
    @Resource
    private ReleaseSchemaDiffService releaseSchemaDiffService;
    // @Lazy: GovReturnNotifyService injects ImSenderService; defensive proxy to avoid
    //   any cycle through the governance/approval component layer.
    @org.springframework.context.annotation.Lazy
    @Resource
    private GovReturnNotifyService govReturnNotifyService;

    // ==================== create (merge transaction) ====================

    @Override
    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRED)
    public long createRelease(String puid, String uid, ProdReleaseCreateFO fo) {
        List<Long> ticketIds = fo.getTicketIds();
        if (ticketIds == null || ticketIds.isEmpty()) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.GOV_RELEASE_NO_TICKETS.name()));
        }

        // Step 1: Validate each ticket's four conditions and collect source stmts
        List<SourceStmtEntry> sourceEntries = new ArrayList<>();
        Set<Long> seenProdDbs = new java.util.HashSet<>();
        for (Long ticketId : ticketIds) {
            DmApprovalDO ticket = approvalDal.approvalMapper().queryById(ticketId);
            if (ticket == null) {
                throw new ErrorMessageException(DmI18nUtils.getMessage(
                    I18nDmMsgKeys.GOV_RELEASE_TICKET_NOT_FOUND.name(), ticketId));
            }
            // Condition 1: ticket status = FINISHED
            if (ticket.getTicketStatus() != ApprovalStatus.FINISHED) {
                throw new ErrorMessageException(DmI18nUtils.getMessage(
                    I18nDmMsgKeys.GOV_RELEASE_TICKET_NOT_FINISHED.name(), ticketId));
            }
            // Condition 2: ticketType = PRE_DDL
            ApprovalMO mo = JsonUtils.toObj(ticket.getTicketInfo(), ApprovalMO.class);
            if (mo == null || !TICKET_TYPE_PRE_DDL.equals(mo.getTicketType())) {
                throw new ErrorMessageException(DmI18nUtils.getMessage(
                    I18nDmMsgKeys.GOV_RELEASE_TICKET_NOT_PRE_DDL.name(), ticketId));
            }

            // Get stmts for this ticket
            List<DmTicketDbStmtDO> stmts = ticketDbStmtDal.stmtMapper().queryByTicketId(ticketId);
            for (DmTicketDbStmtDO stmt : stmts) {
                // Condition 3: stmt exec_status = SUCCESS
                if (!EXEC_STATUS_SUCCESS.equals(stmt.getExecStatus())) {
                    throw new ErrorMessageException(DmI18nUtils.getMessage(
                        I18nDmMsgKeys.GOV_RELEASE_STMT_NOT_SUCCESS.name(), ticketId, stmt.getDbName()));
                }

                // Condition 4: not already merged (uk_source_stmt check)
                DmProdReleaseStmtDO existing = prodReleaseDal.stmtMapper().queryBySourceStmtId(stmt.getId());
                if (existing != null) {
                    throw new ErrorMessageException(DmI18nUtils.getMessage(
                        I18nDmMsgKeys.GOV_RELEASE_STMT_ALREADY_MERGED.name(), ticketId, stmt.getDbName(),
                        existing.getReleaseId()));
                }

                // Resolve pair to get prod DS and prod DB name
                DmDbPairDO pair = dbPairDal.pairMapper().selectById(stmt.getPairId());
                if (pair == null || !STATUS_ENABLED.equals(pair.getStatus())) {
                    throw new ErrorMessageException(DmI18nUtils.getMessage(
                        I18nDmMsgKeys.GOV_RELEASE_PAIR_NOT_FOUND.name(), stmt.getPairId()));
                }

                // checkResAuth on prod DS (DM_DAUTH_TICKET)
                DmDsDO prodDs = dsDal.dsMapper().queryDsIdentityById(pair.getProdDsId());
                if (prodDs == null) {
                    throw new ErrorMessageException("Production datasource not found for pair: " + pair.getId());
                }
                dmAuthServiceForBiz.checkResAuth(
                    puid, uid, pair.getProdDsId(),
                    new DsResPathObj("/" + prodDs.getInstanceId() + "/" + pair.getProdDbName()),
                    com.clougence.clouddm.sdk.security.auth.def.SecDataAuthLabel.DM_DAUTH_TICKET,
                    com.clougence.clouddm.sdk.security.auth.AuthKind.DataSource);

                // Library-level lock check (service-layer, design §6)
                int activeCount = prodReleaseDal.releaseMapper().countActiveByProdDb(
                    pair.getProdDsId(), pair.getProdDbName());
                if (activeCount > 0) {
                    throw new ErrorMessageException(DmI18nUtils.getMessage(
                        I18nDmMsgKeys.GOV_RELEASE_DB_LOCKED.name(), pair.getProdDbName()));
                }

                SourceStmtEntry entry = new SourceStmtEntry(stmt, pair, ticket, ticket.getGmtCreate());

                // Sub-task A: query CAPTURED snapshots from the pre-release side.
                // No snapshots → merge proceeds normally (compatibility: pre-A releases).
                try {
                    entry.snapshots = ticketDbStmtDal.schemaMapper().queryByStmtId(stmt.getId());
                } catch (Exception e) {
                    log.warn("[ProdRelease] Failed to query snapshots for stmt {}: {}", stmt.getId(), e.getMessage());
                    entry.snapshots = java.util.Collections.emptyList();
                }

                sourceEntries.add(entry);
            }
        }

        if (sourceEntries.isEmpty()) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.GOV_RELEASE_NO_STMTS.name()));
        }

        // Step 2: Sort by source ticket gmt_create, then by stmt id (original order within ticket)
        sourceEntries.sort((a, b) -> {
            int cmp = a.ticketGmtCreate.compareTo(b.ticketGmtCreate);
            if (cmp != 0) return cmp;
            return a.stmt.getId().compareTo(b.stmt.getId());
        });

        // Step 3: Assign seq per (prodDsId, prodDbName) group
        Map<String, Integer> seqCounter = new HashMap<>();
        List<StmtBuildEntry> buildEntries = new ArrayList<>();
        for (SourceStmtEntry se : sourceEntries) {
            String key = se.pair.getProdDsId() + "|" + se.pair.getProdDbName();
            int seq = seqCounter.getOrDefault(key, 0) + 1;
            seqCounter.put(key, seq);

            String sqlContent = se.stmt.getSqlContent();
            String hash = GovSqlHashUtils.hash(sqlContent);
            String executionKey = GovSqlHashUtils.hash(se.stmt.getId() + "|" + se.pair.getProdDsId() + "|" + se.pair.getProdDbName());

            buildEntries.add(new StmtBuildEntry(se, seq, hash, executionKey));
        }

        // Step 4: Generate release_no
        String releaseNo = generateReleaseNo();

        // Step 5: Build gate_result snapshot (records the merge validation)
        Map<String, Object> gateResult = new LinkedHashMap<>();
        gateResult.put("validatedAt", new Date().toString());
        gateResult.put("sourceTicketCount", ticketIds.size());
        gateResult.put("stmtCount", buildEntries.size());
        List<Map<String, Object>> stmtSnapshots = new ArrayList<>();
        for (StmtBuildEntry be : buildEntries) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("sourceTicketId", be.source.stmt.getTicketId());
            s.put("sourceStmtId", be.source.stmt.getId());
            s.put("prodDbName", be.source.pair.getProdDbName());
            s.put("seq", be.seq);
            s.put("hash", be.hash);
            stmtSnapshots.add(s);
        }
        gateResult.put("stmts", stmtSnapshots);
        String gateResultJson = JsonUtils.toJson(gateResult);

        // Step 6: Insert release row (status = APPROVING — CREATED is transient)
        String title = StringUtils.isNotBlank(fo.getTitle()) ? fo.getTitle()
            : "Release " + releaseNo;

        DmProdReleaseDO release = new DmProdReleaseDO();
        release.setReleaseNo(releaseNo);
        release.setTitle(title);
        release.setStatus(ProdReleaseStatus.APPROVING.name());
        release.setCreatorUid(uid);
        release.setPrimaryUid(puid);
        release.setGateResult(gateResultJson);
        prodReleaseDal.releaseMapper().insert(release);

        // Step 7: Insert stmt snapshots (UK uk_source_stmt is the concurrent-merge safety net)
        try {
            for (StmtBuildEntry be : buildEntries) {
                DmProdReleaseStmtDO stmt = new DmProdReleaseStmtDO();
                stmt.setReleaseId(release.getId());
                stmt.setProdDsId(be.source.pair.getProdDsId());
                stmt.setProdDbName(be.source.pair.getProdDbName());
                stmt.setSeq(be.seq);
                stmt.setSqlContent(be.source.stmt.getSqlContent());
                stmt.setHash(be.hash);
                stmt.setSourceTicketId(be.source.stmt.getTicketId());
                stmt.setSourceStmtId(be.source.stmt.getId());
                stmt.setExecutionKey(be.executionKey);
                stmt.setExecStatus(EXEC_STATUS_PENDING);
                prodReleaseDal.stmtMapper().insert(stmt);

                // Sub-task A: propagate CAPTURED snapshots to the release side
                propagateSnapshots(release.getId(), stmt.getId(), be, buildEntries);
            }
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(
                I18nDmMsgKeys.GOV_RELEASE_STMT_ALREADY_MERGED.name(), "concurrent", "unknown", 0));
        }

        // Step 8: Create approval ticket (approBiz = DM_PROD_RELEASE).
        // createSqlTicket reads the env ticket-info config internally (approType=Internal default).
        long firstProdDsId = buildEntries.get(0).source.pair.getProdDsId();
        DmDsDO firstProdDs = dsDal.dsMapper().queryDsIdentityById(firstProdDsId);

        DmAddTicketFO ticketFO = new DmAddTicketFO();
        ticketFO.setTicketTitle(title);
        ticketFO.setRawSql("-- Production release: " + releaseNo + "\n-- " + buildEntries.size() + " statement(s)");
        ticketFO.setContentType(SqlContentType.INLINE);
        ticketFO.setDescription("Production release " + releaseNo + " merging " + ticketIds.size() + " ticket(s)");
        ticketFO.setForce(true);
        ticketFO.setDbLevels(List.of(firstProdDs.getDsEnvId().toString(), String.valueOf(firstProdDsId),
            buildEntries.get(0).source.pair.getProdDbName()));

        DmTicketResultVO ticketResult = approvalControlService.createSqlTicket(puid, uid, ticketFO, ApprovalBiz.DM_PROD_RELEASE);

        // Writeback releaseId/releaseNo into ticketInfo
        DmApprovalDO ticket = approvalDal.approvalMapper().queryById(ticketResult.getTicketId());
        if (ticket != null) {
            ApprovalMO writeMO = JsonUtils.toObj(ticket.getTicketInfo(), ApprovalMO.class);
            if (writeMO != null) {
                writeMO.setReleaseId(release.getId());
                writeMO.setReleaseNo(releaseNo);
                ticket.setTicketInfo(JsonUtils.toJson(writeMO));
                approvalDal.approvalMapper().updateById(ticket);
            }
        }

        // Step 9: Backfill release.approval_id
        prodReleaseDal.releaseMapper().updateApprovalId(release.getId(), ticketResult.getTicketId());

        // createSqlTicket already ran createProcess for approBiz; a second call here would
        // insert duplicate APPROVAL/CONFIRM/EXECUTION process rows (initializeProcess is
        // non-idempotent) and ghost stage nodes in the ticket progress UI.

        // Step 10: Event RELEASE_CREATED
        appendReleaseEvent(release.getId(), GovEventType.RELEASE_CREATED, null, ProdReleaseStatus.APPROVING.name(), uid, null);

        return release.getId();
    }

    // ==================== startExecution ====================

    @Override
    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRED)
    public void startExecution(long releaseId, String uid) {
        DmProdReleaseDO release = prodReleaseDal.releaseMapper().queryById(releaseId);
        if (release == null) {
            throw new ErrorMessageException("Release not found: " + releaseId);
        }

        // Transit APPROVED → EXECUTING
        boolean ok = releaseStateMachine.transit(releaseId,
            Set.of(ProdReleaseStatus.APPROVED), ProdReleaseStatus.EXECUTING);
        if (!ok) {
            throw new ErrorMessageException("Release is not in APPROVED state, current: " + release.getStatus());
        }

        // Append event
        appendReleaseEvent(releaseId, GovEventType.RELEASE_EXEC_STARTED,
            ProdReleaseStatus.APPROVED.name(), ProdReleaseStatus.EXECUTING.name(), uid, null);

        // Get all stmts, group by (prodDsId, prodDbName)
        List<DmProdReleaseStmtDO> stmts = prodReleaseDal.stmtMapper().queryByReleaseId(releaseId);
        Map<String, List<DmProdReleaseStmtDO>> byDb = stmts.stream()
            .collect(Collectors.groupingBy(s -> s.getProdDsId() + "|" + s.getProdDbName()));

        // For each prod DB group, find the first PENDING stmt (smallest seq), build job
        for (Map.Entry<String, List<DmProdReleaseStmtDO>> entry : byDb.entrySet()) {
            DmProdReleaseStmtDO first = prodReleaseDal.stmtMapper().firstPendingStmt(
                releaseId, entry.getValue().get(0).getProdDsId(), entry.getValue().get(0).getProdDbName());
            if (first == null) {
                continue; // no PENDING stmts for this DB
            }

            // Gate: re-hash and compare (anti-drift, design §4)
            String recomputed = GovSqlHashUtils.hash(first.getSqlContent());
            if (!recomputed.equals(first.getHash())) {
                // Hash drift — mark stmt FAILED, event, skip
                prodReleaseDal.stmtMapper().updateExecStatus(first.getId(), EXEC_STATUS_FAILED,
                    "Hash drift detected: stored=" + first.getHash() + " recomputed=" + recomputed);
                appendReleaseEvent(releaseId, GovEventType.RELEASE_HASH_DRIFT,
                    null, null, uid, JsonUtils.toJson(Map.of(
                        "stmtId", first.getId(),
                        "storedHash", first.getHash(),
                        "recomputedHash", recomputed)));
                continue;
            }

            // Gate: schema precondition check (design §6 — mirrors hash-drift behavior)
            PrecheckConclusion precheckResult = releaseSchemaPrecheckService.precheck(first);
            if (precheckResult.isBlock()) {
                prodReleaseDal.stmtMapper().updateExecStatus(first.getId(), EXEC_STATUS_FAILED,
                    precheckResult.toDetailText());
                appendReleaseEvent(releaseId, GovEventType.RELEASE_SCHEMA_PRECHECK_FAILED,
                    null, null, uid, precheckResult.toEventData(first.getId()));
                continue;
            }
            if (precheckResult.isWarnSkip()) {
                appendReleaseEvent(releaseId, GovEventType.RELEASE_SCHEMA_PRECHECK_SKIPPED,
                    null, null, uid, precheckResult.toEventData(first.getId()));
            }

            // Gate: schema drift diff (sub-task A — order: hash → B precheck → A diff)
            PrecheckConclusion diffResult = releaseSchemaDiffService.diff(first);
            if (diffResult.isBlock()) {
                prodReleaseDal.stmtMapper().updateExecStatus(first.getId(), EXEC_STATUS_FAILED,
                    diffResult.toDetailText());
                appendReleaseEvent(releaseId, GovEventType.RELEASE_SCHEMA_DRIFT,
                    null, null, uid, diffResult.toEventData(first.getId()));
                continue;
            }
            if (diffResult.isWarnSkip()) {
                appendReleaseEvent(releaseId, diffWarnEventType(diffResult),
                    null, null, uid, diffResult.toEventData(first.getId()));
            }

            // Create job for this stmt
            String jobBizId = DmTeamUtils.nextExecJobBizId();
            String languageTag = DmI18nUtils.getLocale().toLanguageTag();
            this.autoExecService.createReleaseStmtJob(first, jobBizId,
                false, ErrorStrategy.NONE, languageTag, uid);
            this.autoExecService.startJob(jobBizId, uid);
        }

        // Aggregation check: hash-drift-caused FAILED stmts may leave all stmts terminal
        // with no running jobs (design §4: "绝不执行" — release must not stay stuck in EXECUTING).
        aggregateIfAllTerminal(releaseId);
    }

    private void aggregateIfAllTerminal(long releaseId) {
        List<DmProdReleaseStmtDO> allStmts = prodReleaseDal.stmtMapper().queryByReleaseId(releaseId);
        boolean allTerminal = allStmts.stream().allMatch(s ->
            EXEC_STATUS_SUCCESS.equals(s.getExecStatus()) || EXEC_STATUS_FAILED.equals(s.getExecStatus()));
        if (allTerminal) {
            boolean anyFailed = allStmts.stream().anyMatch(s -> EXEC_STATUS_FAILED.equals(s.getExecStatus()));
            if (anyFailed) {
                releaseStateMachine.transit(releaseId,
                    Set.of(ProdReleaseStatus.EXECUTING), ProdReleaseStatus.PARTIAL_FAILED);
                appendReleaseEvent(releaseId, GovEventType.RELEASE_STMT_FAILED,
                    ProdReleaseStatus.EXECUTING.name(), ProdReleaseStatus.PARTIAL_FAILED.name(), null, null);
                // Update approval ticket
                DmProdReleaseDO release = prodReleaseDal.releaseMapper().queryById(releaseId);
                if (release != null && release.getApprovalId() != null) {
                    approvalStateService.failExecution(
                        approvalDal.approvalMapper().queryById(release.getApprovalId()).getBizId(),
                        "Hash drift or execution failure detected");
                }
            } else {
                releaseStateMachine.transit(releaseId,
                    Set.of(ProdReleaseStatus.EXECUTING), ProdReleaseStatus.DONE);
                appendReleaseEvent(releaseId, GovEventType.RELEASE_DONE,
                    ProdReleaseStatus.EXECUTING.name(), ProdReleaseStatus.DONE.name(), null, null);
            }
        }
    }

    // ==================== retryReleaseStmt ====================

    @Override
    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRED)
    public void retryReleaseStmt(String puid, String uid, long stmtId) {
        DmProdReleaseStmtDO stmt = prodReleaseDal.stmtMapper().queryById(stmtId);
        if (stmt == null) {
            throw new ErrorMessageException("Release statement not found: " + stmtId);
        }

        DmProdReleaseDO release = prodReleaseDal.releaseMapper().queryById(stmt.getReleaseId());
        if (release == null || !puid.equals(release.getPrimaryUid())) {
            throw new ErrorMessageException("Release not found: " + stmt.getReleaseId());
        }

        // Check resAuth on prod DS
        DmDsDO prodDs = dsDal.dsMapper().queryDsIdentityById(stmt.getProdDsId());
        dmAuthServiceForBiz.checkResAuth(
            puid, uid, stmt.getProdDsId(),
            new DsResPathObj("/" + prodDs.getInstanceId() + "/" + stmt.getProdDbName()),
            com.clougence.clouddm.sdk.security.auth.def.SecDataAuthLabel.DM_DAUTH_TICKET,
            com.clougence.clouddm.sdk.security.auth.AuthKind.DataSource);

        // Release must be EXECUTING or PARTIAL_FAILED
        if (!ProdReleaseStatus.EXECUTING.name().equals(release.getStatus())
            && !ProdReleaseStatus.PARTIAL_FAILED.name().equals(release.getStatus())) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(
                I18nDmMsgKeys.GOV_RELEASE_NOT_RETRYABLE.name(), release.getStatus()));
        }

        // If PARTIAL_FAILED, transit back to EXECUTING
        if (ProdReleaseStatus.PARTIAL_FAILED.name().equals(release.getStatus())) {
            boolean ok = releaseStateMachine.transit(stmt.getReleaseId(),
                Set.of(ProdReleaseStatus.PARTIAL_FAILED), ProdReleaseStatus.EXECUTING);
            if (!ok) {
                throw new ErrorMessageException("Cannot transit PARTIAL_FAILED → EXECUTING for release: " + release.getReleaseNo());
            }
        }

        // Stmt must be FAILED (or EXECUTING/PENDING with no unfinished job — inheritance fix)
        String status = stmt.getExecStatus();
        if (!EXEC_STATUS_FAILED.equals(status) && !EXEC_STATUS_EXECUTING.equals(status) && !EXEC_STATUS_PENDING.equals(status)) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(
                I18nDmMsgKeys.GOV_RELEASE_STMT_NOT_RETRYABLE.name(), status));
        }
        if (EXEC_STATUS_EXECUTING.equals(status) || EXEC_STATUS_PENDING.equals(status)) {
            // Check if there's an unfinished job for this stmt
            var existingJob = execDal.autoJobMapper().queryByDependOnReleaseStmtId(stmt.getId());
            if (existingJob != null && existingJob.getStatus() != com.clougence.clouddm.platform.dal.model.execution.AutoExecJobStatus.FAILED
                && existingJob.getStatus() != com.clougence.clouddm.platform.dal.model.execution.AutoExecJobStatus.TERMINATION) {
                throw new ErrorMessageException(DmI18nUtils.getMessage(
                    I18nDmMsgKeys.GOV_RELEASE_STMT_JOB_ACTIVE.name()));
            }
        }

        // Gate: re-hash and compare (anti-drift — existing defect fix, same semantics as startExecution)
        String recomputed = GovSqlHashUtils.hash(stmt.getSqlContent());
        if (!recomputed.equals(stmt.getHash())) {
            prodReleaseDal.stmtMapper().updateExecStatus(stmt.getId(), EXEC_STATUS_FAILED,
                "Hash drift detected: stored=" + stmt.getHash() + " recomputed=" + recomputed);
            appendReleaseEvent(stmt.getReleaseId(), GovEventType.RELEASE_HASH_DRIFT,
                null, null, uid, JsonUtils.toJson(Map.of(
                    "stmtId", stmt.getId(),
                    "storedHash", stmt.getHash(),
                    "recomputedHash", recomputed)));
            return;
        }

        // Gate: schema precondition check (design §6)
        PrecheckConclusion precheckResult = releaseSchemaPrecheckService.precheck(stmt);
        if (precheckResult.isBlock()) {
            prodReleaseDal.stmtMapper().updateExecStatus(stmt.getId(), EXEC_STATUS_FAILED,
                precheckResult.toDetailText());
            appendReleaseEvent(stmt.getReleaseId(), GovEventType.RELEASE_SCHEMA_PRECHECK_FAILED,
                null, null, uid, precheckResult.toEventData(stmt.getId()));
            return;
        }
        if (precheckResult.isWarnSkip()) {
            appendReleaseEvent(stmt.getReleaseId(), GovEventType.RELEASE_SCHEMA_PRECHECK_SKIPPED,
                null, null, uid, precheckResult.toEventData(stmt.getId()));
        }

        // Gate: schema drift diff (sub-task A — order: hash → B precheck → A diff)
        PrecheckConclusion diffResult = releaseSchemaDiffService.diff(stmt);
        if (diffResult.isBlock()) {
            prodReleaseDal.stmtMapper().updateExecStatus(stmt.getId(), EXEC_STATUS_FAILED,
                diffResult.toDetailText());
            appendReleaseEvent(stmt.getReleaseId(), GovEventType.RELEASE_SCHEMA_DRIFT,
                null, null, uid, diffResult.toEventData(stmt.getId()));
            return;
        }
        if (diffResult.isWarnSkip()) {
            appendReleaseEvent(stmt.getReleaseId(), diffWarnEventType(diffResult),
                null, null, uid, diffResult.toEventData(stmt.getId()));
        }

        // Reset stmt to PENDING
        prodReleaseDal.stmtMapper().updateExecStatus(stmt.getId(), EXEC_STATUS_PENDING, null);

        // Rebuild job
        String jobBizId = DmTeamUtils.nextExecJobBizId();
        String languageTag = DmI18nUtils.getLocale().toLanguageTag();
        this.autoExecService.createReleaseStmtJob(stmt, jobBizId,
            false, ErrorStrategy.NONE, languageTag, uid);
        this.autoExecService.startJob(jobBizId, uid);
    }

    // ==================== returnStmt (layer 2 return-for-revision) ====================

    @Override
    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRED)
    public void returnStmt(String puid, String uid, ProdReleaseReturnStmtFO fo) {
        long stmtId = fo.getStmtId();
        DmProdReleaseStmtDO stmt = prodReleaseDal.stmtMapper().queryById(stmtId);
        if (stmt == null) {
            throw new ErrorMessageException("Release statement not found: " + stmtId);
        }

        // Take release row lock for the entire operation (serializes against concurrent
        // completion callbacks and chain advances — design §3 / R#9).
        DmProdReleaseDO release = prodReleaseDal.releaseMapper().selectByIdForUpdate(stmt.getReleaseId());
        if (release == null || !puid.equals(release.getPrimaryUid())) {
            throw new ErrorMessageException("Release not found: " + stmt.getReleaseId());
        }

        // Guard 1: stmt.exec_status must be FAILED only (SUCCESS/EXECUTING/PENDING all rejected)
        if (!EXEC_STATUS_FAILED.equals(stmt.getExecStatus())) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(
                I18nDmMsgKeys.GOV_RELEASE_STMT_NOT_RETURNABLE.name(), stmt.getExecStatus()));
        }

        // Guard 2: release must be EXECUTING or PARTIAL_FAILED (mirrors retryReleaseStmt entry)
        if (!ProdReleaseStatus.EXECUTING.name().equals(release.getStatus())
            && !ProdReleaseStatus.PARTIAL_FAILED.name().equals(release.getStatus())) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(
                I18nDmMsgKeys.GOV_RELEASE_NOT_RETRYABLE.name(), release.getStatus()));
        }

        // Guard 3: no active job for this stmt
        var existingJob = execDal.autoJobMapper().queryByDependOnReleaseStmtId(stmt.getId());
        if (existingJob != null
            && existingJob.getStatus() != com.clougence.clouddm.platform.dal.model.execution.AutoExecJobStatus.FAILED
            && existingJob.getStatus() != com.clougence.clouddm.platform.dal.model.execution.AutoExecJobStatus.TERMINATION) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(
                I18nDmMsgKeys.GOV_RELEASE_STMT_JOB_ACTIVE.name()));
        }

        // Step 1: delete residual FAILED job (prevent dangling depend_on_release_stmt_id reference)
        if (existingJob != null) {
            autoExecService.deleteJob(existingJob.getBizId());
        }

        // Step 2: delete the release stmt row (frees uk_source_stmt + uk_execution_key)
        prodReleaseDal.stmtMapper().deleteByStmtId(stmtId);

        // Step 3: delete release_stmt_schema rows for this stmt (orphan cleanup)
        prodReleaseDal.schemaMapper().deleteByReleaseStmtId(stmtId);

        // Step 4: source ticket side — set group FAILED + ticket REVISION (idempotent for multi-stmt returns)
        DmTicketDbStmtDO sourceStmt = ticketDbStmtDal.stmtMapper().queryById(stmt.getSourceStmtId());
        if (sourceStmt != null) {
            // Set group exec_status -> FAILED with return detail
            String execDetail = "Returned from release " + release.getReleaseNo()
                + (fo.getReason() != null ? ": " + fo.getReason() : "");
            ticketDbStmtDal.stmtMapper().updateExecStatus(sourceStmt.getId(), EXEC_STATUS_FAILED, execDetail);

            // Set source ticket to REVISION (if not already — idempotent for multi-stmt same-ticket returns)
            DmApprovalDO sourceTicket = approvalDal.approvalMapper().queryById(sourceStmt.getTicketId());
            if (sourceTicket != null && sourceTicket.getTicketStatus() != ApprovalStatus.REVISION) {
                // Source ticket must be FINISHED (it was merged) — transition to REVISION
                approvalStateService.updateApprovalStatus(
                    sourceTicket.getId(), ApprovalStatus.REVISION, "Release stmt returned for revision");
            }
        }

        // Step 5: event RELEASE_STMT_RETURNED (real uid, NOT SYSTEM — NOT NULL contract)
        appendReleaseEvent(release.getId(), GovEventType.RELEASE_STMT_RETURNED,
            null, null, uid, JsonUtils.toJson(Map.of(
                "stmtId", stmtId,
                "sourceTicketId", stmt.getSourceTicketId(),
                "sourceStmtId", stmt.getSourceStmtId(),
                "executionKey", stmt.getExecutionKey() != null ? stmt.getExecutionKey() : "",
                "reason", fo.getReason() != null ? fo.getReason() : "")));

        // Step 6: trigger aggregation check — if all remaining stmts are terminal, complete the release.
        // (remaining all SUCCESS -> DONE is correct: the returned stmt goes back to the ticket side
        // and will be re-merged into a new release; R#8.)
        // If the release was PARTIAL_FAILED, transit back to EXECUTING first so that
        // aggregateIfAllTerminal (which only transits from EXECUTING) can fire correctly.
        // This mirrors the retryReleaseStmt entry pattern (L484-486).
        if (ProdReleaseStatus.PARTIAL_FAILED.name().equals(release.getStatus())) {
            releaseStateMachine.transit(release.getId(),
                Set.of(ProdReleaseStatus.PARTIAL_FAILED), ProdReleaseStatus.EXECUTING);
        }
        aggregateIfAllTerminal(release.getId());

        // Step 7: IM notify source ticket applicant (operator != applicant when DBA returns).
        // Deep link points to the source ticket (applicant edits SQL on the ticket page, not the release page).
        if (sourceStmt != null) {
            DmApprovalDO sourceTicket = approvalDal.approvalMapper().queryById(sourceStmt.getTicketId());
            if (sourceTicket != null && !uid.equals(sourceTicket.getOwnerUid())) {
                govReturnNotifyService.notifyReturn(
                    puid, uid, sourceTicket.getOwnerUid(),
                    sourceTicket.getId(), sourceTicket.getTicketTitle(),
                    fo.getReason(), "RELEASE");
            }
        }
    }

    // ==================== handleApproved / handleRejected / handleCancelled ====================

    @Override
    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRED)
    public void handleApproved(long releaseId) {
        boolean ok = releaseStateMachine.transit(releaseId,
            Set.of(ProdReleaseStatus.APPROVING), ProdReleaseStatus.APPROVED);
        if (!ok) {
            log.warn("[ProdRelease] transit APPROVING→APPROVED failed for release {}", releaseId);
            return;
        }
        appendReleaseEvent(releaseId, GovEventType.RELEASE_APPROVED,
            ProdReleaseStatus.APPROVING.name(), ProdReleaseStatus.APPROVED.name(), null, null);
    }

    @Override
    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRED)
    public void handleRejected(long releaseId) {
        DmProdReleaseDO release = prodReleaseDal.releaseMapper().queryById(releaseId);
        if (release == null) return;

        // Transit to REJECTED (only from APPROVING/APPROVED)
        boolean ok = releaseStateMachine.transit(releaseId,
            Set.of(ProdReleaseStatus.APPROVING, ProdReleaseStatus.APPROVED),
            ProdReleaseStatus.REJECTED);
        if (!ok) {
            log.warn("[ProdRelease] transit to REJECTED failed for release {}, current: {}", releaseId, release.getStatus());
            return;
        }

        // Delete stmt rows (release UK locks)
        prodReleaseDal.stmtMapper().deleteByReleaseId(releaseId);

        // Event
        appendReleaseEvent(releaseId, GovEventType.RELEASE_REJECTED,
            release.getStatus(), ProdReleaseStatus.REJECTED.name(), null, null);
    }

    @Override
    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRED)
    public void handleCancelled(long releaseId) {
        DmProdReleaseDO release = prodReleaseDal.releaseMapper().queryById(releaseId);
        if (release == null) return;

        // Transit to CANCELLED (only from APPROVING/APPROVED)
        boolean ok = releaseStateMachine.transit(releaseId,
            Set.of(ProdReleaseStatus.APPROVING, ProdReleaseStatus.APPROVED),
            ProdReleaseStatus.CANCELLED);
        if (!ok) {
            log.warn("[ProdRelease] transit to CANCELLED failed for release {}, current: {}", releaseId, release.getStatus());
            return;
        }

        // Delete stmt rows (release UK locks)
        prodReleaseDal.stmtMapper().deleteByReleaseId(releaseId);

        // Event
        appendReleaseEvent(releaseId, GovEventType.RELEASE_CANCELLED,
            release.getStatus(), ProdReleaseStatus.CANCELLED.name(), null, null);
    }

    // ==================== list / detail ====================

    @Override
    public List<ProdReleaseListVO> listReleases(String puid, String status, Integer days, int page, int size) {
        Page<DmProdReleaseDO> pageObj = new Page<>(page, size);
        Date since = computeSince(days);
        var result = prodReleaseDal.releaseMapper().listByConditionAndPage(pageObj, puid, status, since);
        List<ProdReleaseListVO> vos = new ArrayList<>();
        for (DmProdReleaseDO rel : result.getRecords()) {
            ProdReleaseListVO vo = new ProdReleaseListVO();
            vo.setId(rel.getId());
            vo.setReleaseNo(rel.getReleaseNo());
            vo.setTitle(rel.getTitle());
            vo.setStatus(rel.getStatus());
            vo.setApprovalId(rel.getApprovalId());
            vo.setGmtCreate(DateFormatType.s_yyyyMMdd_HHmmss.format(rel.getGmtCreate()));
            vos.add(vo);
        }
        return vos;
    }

    /**
     * Convert days filter to a lower-bound Date.
     * null or <=0 → null (no time filter); otherwise now - days days.
     */
    static Date computeSince(Integer days) {
        if (days == null || days <= 0) {
            return null;
        }
        return new Date(System.currentTimeMillis() - days * 86400_000L);
    }

    @Override
    public ProdReleaseDetailVO getDetail(String puid, long releaseId) {
        DmProdReleaseDO release = prodReleaseDal.releaseMapper().queryById(releaseId);
        if (release == null || !puid.equals(release.getPrimaryUid())) {
            throw new ErrorMessageException("Release not found: " + releaseId);
        }

        ProdReleaseDetailVO vo = new ProdReleaseDetailVO();
        vo.setId(release.getId());
        vo.setReleaseNo(release.getReleaseNo());
        vo.setTitle(release.getTitle());
        vo.setStatus(release.getStatus());
        vo.setApprovalId(release.getApprovalId());
        vo.setCreatorUid(release.getCreatorUid());
        vo.setGmtCreate(DateFormatType.s_yyyyMMdd_HHmmss.format(release.getGmtCreate()));
        vo.setGateResult(release.getGateResult());

        // Stmts grouped by (prodDsId, prodDbName)
        List<DmProdReleaseStmtDO> stmts = prodReleaseDal.stmtMapper().queryByReleaseId(releaseId);
        Map<String, ProdReleaseDetailVO.StmtGroup> groupMap = new LinkedHashMap<>();
        for (DmProdReleaseStmtDO s : stmts) {
            String key = s.getProdDsId() + "|" + s.getProdDbName();
            ProdReleaseDetailVO.StmtGroup group = groupMap.computeIfAbsent(key, k -> {
                ProdReleaseDetailVO.StmtGroup g = new ProdReleaseDetailVO.StmtGroup();
                g.setProdDsId(s.getProdDsId());
                g.setProdDbName(s.getProdDbName());
                g.setStmts(new ArrayList<>());
                return g;
            });
            ProdReleaseDetailVO.StmtEntry se = new ProdReleaseDetailVO.StmtEntry();
            se.setId(s.getId());
            se.setSeq(s.getSeq());
            se.setSqlContent(s.getSqlContent());
            se.setHash(s.getHash());
            se.setSourceTicketId(s.getSourceTicketId());
            se.setSourceStmtId(s.getSourceStmtId());
            se.setExecStatus(s.getExecStatus());
            se.setExecDetail(s.getExecDetail());
            se.setGmtCreate(DateFormatType.s_yyyyMMdd_HHmmss.format(s.getGmtCreate()));
            group.getStmts().add(se);
        }
        vo.setStmtGroups(new ArrayList<>(groupMap.values()));

        // Events
        List<DmDbChangeEventDO> events = dbChangeEventDal.eventMapper().queryByReleaseId(releaseId);
        List<ProdReleaseDetailVO.EventEntry> eventEntries = new ArrayList<>();
        for (DmDbChangeEventDO e : events) {
            ProdReleaseDetailVO.EventEntry ee = new ProdReleaseDetailVO.EventEntry();
            ee.setEventType(e.getEventType());
            ee.setFromStatus(e.getFromStatus());
            ee.setToStatus(e.getToStatus());
            ee.setOperatorUid(e.getOperatorUid());
            ee.setGmtCreate(DateFormatType.s_yyyyMMdd_HHmmss.format(e.getGmtCreate()));
            ee.setEventData(e.getEventData());
            eventEntries.add(ee);
        }
        vo.setEvents(eventEntries);

        return vo;
    }

    // ==================== helpers ====================

    /**
     * Propagate CAPTURED snapshots from the pre-release side to the release side.
     * shared_flag is computed per (prodDsId, prodDbName) group: a table touched by
     * >1 stmt in the group → shared_flag=1 (degraded to WARN at the diff gate).
     * No snapshots → no propagation (compatibility: pre-A releases degrade to B).
     */
    private void propagateSnapshots(long releaseId, long releaseStmtId, StmtBuildEntry be,
                                    List<StmtBuildEntry> buildEntries) {
        if (be.source.snapshots == null || be.source.snapshots.isEmpty()) {
            return;
        }

        // Compute shared tables for this (prodDsId, prodDbName) group.
        // A table is shared if it appears in >1 stmt's table set within the same group.
        // Tables from snapshots are known; tables from stmts without snapshots are
        // computed via extractTables (avoid missing shared markers).
        Set<String> sharedTables = computeSharedTables(be.source.pair.getProdDsId(),
            be.source.pair.getProdDbName(), buildEntries);

        List<DmProdReleaseStmtSchemaDO> rows = new ArrayList<>();
        for (DmTicketStmtSchemaDO snap : be.source.snapshots) {
            if (!"CAPTURED".equals(snap.getCaptureStatus())) {
                continue;
            }
            DmProdReleaseStmtSchemaDO row = new DmProdReleaseStmtSchemaDO();
            row.setReleaseId(releaseId);
            row.setReleaseStmtId(releaseStmtId);
            row.setProdDsId(be.source.pair.getProdDsId());
            row.setProdDbName(be.source.pair.getProdDbName());
            row.setTableName(snap.getTableName());
            row.setExistsFlag(snap.getExistsFlag());
            row.setSchemaJson(snap.getSchemaJson());
            row.setFingerprint(snap.getFingerprint());
            row.setSharedFlag(sharedTables.contains(snap.getTableName()) ? 1 : 0);
            rows.add(row);
        }
        if (!rows.isEmpty()) {
            prodReleaseDal.schemaMapper().batchInsert(rows);
        }
    }

    /**
     * Compute the set of tables touched by >1 stmt within the same (prodDsId, prodDbName) group.
     * Called per build entry — scans all build entries sharing the same prod ds/db.
     */
    private Set<String> computeSharedTables(long prodDsId, String prodDbName,
                                           List<StmtBuildEntry> buildEntries) {
        // Collect per-stmt table sets for this group
        List<Set<String>> perStmtTables = new ArrayList<>();
        for (StmtBuildEntry be : buildEntries) {
            if (be.source.pair.getProdDsId() != prodDsId
                || !prodDbName.equals(be.source.pair.getProdDbName())) {
                continue;
            }
            Set<String> tables = new HashSet<>();
            if (be.source.snapshots != null) {
                for (DmTicketStmtSchemaDO snap : be.source.snapshots) {
                    if ("CAPTURED".equals(snap.getCaptureStatus())) {
                        tables.add(snap.getTableName());
                    }
                }
            }
            // If snapshots didn't cover all tables, use extractTables to fill the gap
            Set<String> extracted = releaseSchemaPrecheckService.extractTables(be.source.stmt.getSqlContent());
            tables.addAll(extracted);
            perStmtTables.add(tables);
        }

        // Find tables that appear in >1 stmt's set
        Map<String, Integer> count = new HashMap<>();
        for (Set<String> tables : perStmtTables) {
            for (String t : tables) {
                count.merge(t, 1, Integer::sum);
            }
        }
        Set<String> shared = new HashSet<>();
        for (Map.Entry<String, Integer> e : count.entrySet()) {
            if (e.getValue() > 1) {
                shared.add(e.getKey());
            }
        }
        return shared;
    }

    private String generateReleaseNo() {
        String datePart = DateFormatType.s_yyyyMMdd.format(new Date());
        String random = String.format("%04d", new java.util.Random().nextInt(10000));
        return "REL-" + datePart + "-" + random;
    }

    /**
     * Distinguish SNAPSHOT_MISSING (no pre-before snapshot) from DRIFT_WARN
     * (shared table or WARN-level-only drift) based on the WARN_SKIP reason.
     */
    private static GovEventType diffWarnEventType(PrecheckConclusion conclusion) {
        String reason = conclusion.getSkipReason();
        if (reason != null && reason.contains("No pre-before schema snapshot")) {
            return GovEventType.RELEASE_SCHEMA_SNAPSHOT_MISSING;
        }
        return GovEventType.RELEASE_SCHEMA_DRIFT_WARN;
    }

    private void appendReleaseEvent(long releaseId, GovEventType type, String fromStatus, String toStatus, String operatorUid, String eventData) {
        DmDbChangeEventDO event = new DmDbChangeEventDO();
        event.setReleaseId(releaseId);
        event.setEventType(type.name());
        event.setFromStatus(fromStatus);
        event.setToStatus(toStatus);
        // dm_db_change_event.operator_uid is NOT NULL without default and MyBatis-Plus
        // inline insert skips null fields. Callback-driven events (handleApproved/Rejected/
        // Cancelled, completion aggregation) have no operator uid in scope — the real
        // approver identity lives on the approval activity rows. Fall back to SYSTEM
        // (same convention as GovAutoAdvanceServiceImpl) instead of failing the insert.
        event.setOperatorUid(StringUtils.isBlank(operatorUid) ? SYSTEM_OPERATOR : operatorUid);
        event.setEventData(eventData);
        dbChangeEventDal.eventMapper().insert(event);
    }

    @Resource
    private com.clougence.clouddm.platform.dal.access.ExecutionDal execDal;

    // Internal entry classes
    private static class SourceStmtEntry {
        final DmTicketDbStmtDO stmt;
        final DmDbPairDO pair;
        final DmApprovalDO ticket;
        final Date ticketGmtCreate;
        List<DmTicketStmtSchemaDO> snapshots; // CAPTURED snapshots from pre-release side

        SourceStmtEntry(DmTicketDbStmtDO stmt, DmDbPairDO pair, DmApprovalDO ticket, Date ticketGmtCreate) {
            this.stmt = stmt;
            this.pair = pair;
            this.ticket = ticket;
            this.ticketGmtCreate = ticketGmtCreate;
        }
    }

    private static class StmtBuildEntry {
        final SourceStmtEntry source;
        final int seq;
        final String hash;
        final String executionKey;

        StmtBuildEntry(SourceStmtEntry source, int seq, String hash, String executionKey) {
            this.source = source;
            this.seq = seq;
            this.hash = hash;
            this.executionKey = executionKey;
        }
    }
}
