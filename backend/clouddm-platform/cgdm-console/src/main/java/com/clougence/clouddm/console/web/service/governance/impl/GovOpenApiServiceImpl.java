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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.console.web.component.approval.impl.ApprovalProviderServiceImpl;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.component.approval.model.ExternalCommandMeta;
import com.clougence.clouddm.console.web.component.auth.DmResAuthService;
import com.clougence.clouddm.console.web.model.fo.openapi.DbChangeReleaseDetailFO;
import com.clougence.clouddm.console.web.model.fo.openapi.DbChangeReleaseReturnFO;
import com.clougence.clouddm.console.web.model.fo.openapi.DbChangeTicketCommandFO;
import com.clougence.clouddm.console.web.model.fo.openapi.DbChangeTicketListFO;
import com.clougence.clouddm.console.web.model.fo.openapi.DbChangeTicketStmtFO;
import com.clougence.clouddm.console.web.model.fo.openapi.DbChangeTicketSubmitFO;
import com.clougence.clouddm.console.web.model.fo.openapi.DbChangeTicketResubmitFO;
import com.clougence.clouddm.console.web.model.fo.openapi.DbChangeReleaseCreateFO;
import com.clougence.clouddm.console.web.model.fo.openapi.DbChangeReleaseResubmitFO;
import com.clougence.clouddm.console.web.model.fo.openapi.DbChangeTicketDetailFO;
import com.clougence.clouddm.console.web.model.fo.openapi.DbChangeLedgerListFO;
import com.clougence.clouddm.console.web.model.fo.govticket.GovTicketV2SubmitFO;
import com.clougence.clouddm.console.web.model.fo.govticket.GovTicketV2ResubmitFO;
import com.clougence.clouddm.console.web.model.vo.govticket.GovTicketV2SubmitVO;
import com.clougence.clouddm.console.web.model.fo.ticket.DmAutoExecConfigFO;
import com.clougence.clouddm.console.web.model.fo.ticket.RdpQueryTicketDetailFO;
import com.clougence.clouddm.console.web.model.vo.openapi.OpenDbChangeTicketStmtVO;
import com.clougence.clouddm.console.web.model.vo.openapi.OpenDbChangeTicketVO;
import com.clougence.clouddm.console.web.model.vo.openapi.OpenDbChangeTicketSubmitVO;
import com.clougence.clouddm.console.web.model.vo.openapi.OpenDbChangeReleaseCreateVO;
import com.clougence.clouddm.console.web.model.vo.openapi.OpenDbChangeTicketDetailVO;
import com.clougence.clouddm.console.web.model.vo.openapi.OpenDbChangeLedgerTicketVO;
import com.clougence.clouddm.console.web.model.vo.prodrelease.GovLedgerTicketVO;
import com.clougence.clouddm.console.web.model.vo.prodrelease.ProdReleaseDetailVO;
import com.clougence.clouddm.console.web.model.vo.ticket.RdpTicketBaseInfoVO;
import com.clougence.clouddm.console.web.model.vo.ticket.RdpTicketProcessVO;
import com.clougence.clouddm.console.web.model.vo.ticket.RdpTicketActivityVO;
import com.clougence.clouddm.console.web.service.approval.ApprovalControlService;
import com.clougence.clouddm.console.web.service.governance.GovLedgerService;
import com.clougence.clouddm.console.web.service.governance.GovOpenApiService;
import com.clougence.clouddm.console.web.service.governance.ProdReleaseService;
import com.clougence.clouddm.console.web.service.govticket.GovTicketV2Service;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.AuthDal;
import com.clougence.clouddm.platform.dal.access.DbPairDal;
import com.clougence.clouddm.platform.dal.access.DbChangeEventDal;
import com.clougence.clouddm.platform.dal.access.ProdReleaseDal;
import com.clougence.clouddm.platform.dal.access.TicketDbStmtDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.dbpair.DmDbPairMapper;
import com.clougence.clouddm.platform.dal.mapper.dbpair.DmDbServiceMapper;
import com.clougence.clouddm.platform.dal.mapper.govticket.DmTicketDbStmtMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseStmtMapper;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalType;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.auth.DmAuthUserDO;
import com.clougence.clouddm.platform.dal.model.dbchange.ChangeType;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO;
import com.clougence.clouddm.platform.dal.model.dbchange.GovEventType;
import com.clougence.clouddm.platform.dal.model.dbpair.DmDbPairDO;
import com.clougence.clouddm.platform.dal.model.dbpair.DmDbServiceDO;
import com.clougence.clouddm.platform.dal.model.execution.AutoExecType;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseStmtDO;
import com.clougence.clouddm.api.console.autoexec.ErrorStrategy;
import com.clougence.clouddm.sdk.approval.ApprovalInstanceStatus;
import com.clougence.clouddm.sdk.security.auth.AuthKind;
import com.clougence.utils.JsonUtils;
import com.clougence.utils.StringUtils;
import com.clougence.utils.format.DateFormatType;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * Governance open API service implementation.
 * <p>
 * Interface A resolves dbName bidirectionally via dm_db_pair, aggregates by ticketId,
 * applies ticketType / executedOnly filters, enforces DS authorization, and paginates in memory.
 * Interfaces B and C are tenant-filtered (primaryUid == caller.puid); cross-tenant calls
 * return null (not-found semantics — existence is not leaked).
 */
@Slf4j
@Service
public class GovOpenApiServiceImpl implements GovOpenApiService {

    private static final String STATUS_ENABLED     = "ENABLED";
    private static final String EXEC_STATUS_SUCCESS = "SUCCESS";
    private static final String EXEC_STATUS_FAILED  = "FAILED";
    private static final String EXEC_STATUS_PENDING = "PENDING";
    private static final String EXEC_STATUS_EXECUTING = "EXECUTING";
    private static final String TICKET_TYPE_PRE_DDL  = "PRE_DDL";
    private static final String TICKET_TYPE_PROD_DML = "PROD_DML";

    @Resource
    private DbPairDal           dbPairDal;
    @Resource
    private TicketDbStmtDal      ticketDbStmtDal;
    @Resource
    private ApprovalDal          approvalDal;
    @Resource
    private ProdReleaseDal       prodReleaseDal;
    @Resource
    private ProdReleaseService   prodReleaseService;
    // ding-bridge R5: console ledger projection = single source of truth for the two admission
    // branches; the open surface layers tenant isolation on top (ledgerListOpen) — the console
    // list itself is deliberately tenant-agnostic (D6) and must never gain filtering.
    @Resource
    private GovLedgerService     govLedgerService;
    @Resource
    private DmResAuthService     dmResAuthService;
    @Resource
    private ApprovalProviderServiceImpl approvalProviderService;
    @Resource
    private ApprovalControlService      approvalControlService;
    @Resource
    private GovTicketV2Service           govTicketV2Service;
    @Resource
    private DbChangeEventDal             dbChangeEventDal;
    @Resource
    private AuthDal                       authDal;
    // B7 N2: the no-key rejection trail — the open submit channel hooks the SAME boundary
    // mechanism as GovTicketV2Controller.submit (R18 三入口对等: the open API must not become a
    // trail bypass; submitTicket carries no @Transactional, so the typed exception arriving here
    // has already rolled the submit transaction back — the trail writes survive identically).
    @Resource
    private com.clougence.clouddm.console.web.service.dmlbatch.DmlBatchRejectTrailService dmlBatchRejectTrailService;

    /** the audit operationUri marker of the open submit boundary (no HttpServletRequest here). */
    private static final String OPEN_SUBMIT_URI = "/open/dbChange/tickets/submit";

    // ==================== Interface A: listTickets ====================

    @Override
    public List<OpenDbChangeTicketVO> listTickets(String puid, String uid, DbChangeTicketListFO fo) {
        // 1. Resolve dbName → (dsId, dbName) combos via dm_db_pair bidirectional match
        Set<DsDbKey> combos = resolveDbNameCombos(fo.getDbName());
        if (combos.isEmpty()) {
            return Collections.emptyList();
        }

        // 2. DS authorization filter — caller must have any auth on the DS (DataSourceApi.listDs precedent)
        List<Long> authedDsIds = dmResAuthService.listResByUserContainAnyAuth(uid, AuthKind.DataSource);
        if (authedDsIds.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> authedDsSet = new HashSet<>(authedDsIds);

        // 3. Query stmts for each authorized (dsId, dbName) combo and aggregate by ticketId
        Map<Long, List<DmTicketDbStmtDO>> byTicket = new LinkedHashMap<>();
        for (DsDbKey combo : combos) {
            if (!authedDsSet.contains(combo.dsId)) {
                continue; // caller has no auth on this DS — skip
            }
            List<DmTicketDbStmtDO> stmts = ticketDbStmtDal.stmtMapper().queryByDsAndDb(combo.dsId, combo.dbName);
            if (stmts == null || stmts.isEmpty()) {
                continue;
            }
            for (DmTicketDbStmtDO s : stmts) {
                byTicket.computeIfAbsent(s.getTicketId(), k -> new ArrayList<>()).add(s);
            }
        }

        if (byTicket.isEmpty()) {
            return Collections.emptyList();
        }

        // 4. Load tickets, apply filters, build VOs
        List<OpenDbChangeTicketVO> result = new ArrayList<>();
        Map<OpenDbChangeTicketVO, Long> voServiceIds = new LinkedHashMap<>();

        for (Map.Entry<Long, List<DmTicketDbStmtDO>> entry : byTicket.entrySet()) {
            long ticketId = entry.getKey();
            List<DmTicketDbStmtDO> ticketStmts = entry.getValue();

            DmApprovalDO ticket = approvalDal.approvalMapper().queryById(ticketId);
            if (ticket == null) {
                continue;
            }

            // Only governance v2 tickets (approBiz=DM_CHANGE, ticketInfo has ticketType)
            if (ticket.getApproBiz() != ApprovalBiz.DM_CHANGE) {
                continue;
            }
            ApprovalMO mo = parseTicketInfo(ticket);
            if (mo == null || mo.getTicketType() == null) {
                continue; // not a v2 governance ticket
            }

            // ticketType filter (optional)
            if (StringUtils.isNotBlank(fo.getTicketType()) && !fo.getTicketType().equals(mo.getTicketType())) {
                continue;
            }

            // executedOnly filter: ticket FINISHED && all groups SUCCESS
            boolean allSuccess = ticketStmts.stream().allMatch(s -> EXEC_STATUS_SUCCESS.equals(s.getExecStatus()));
            if (fo.isExecutedOnly()) {
                if (ticket.getTicketStatus() != ApprovalStatus.FINISHED || !allSuccess) {
                    continue;
                }
            }

            OpenDbChangeTicketVO vo = buildTicketVO(ticket, mo, ticketStmts, allSuccess);
            voServiceIds.put(vo, mo.getServiceId());
            result.add(vo);
        }

        // 5. Batch-fill serviceName
        fillServiceNames(voServiceIds);

        // 6. In-memory pagination
        return paginate(result, fo.getPage(), fo.getSize());
    }

    // ==================== Interface B: ticketStatements ====================

    @Override
    public OpenDbChangeTicketStmtVO ticketStatements(String puid, String uid, DbChangeTicketStmtFO fo) {
        DmApprovalDO ticket = approvalDal.approvalMapper().queryById(fo.getTicketId());
        // Tenant check — cross-tenant returns null (not-found semantics, existence not leaked)
        if (ticket == null || !puid.equals(ticket.getPrimaryUid())) {
            return null;
        }

        // Only governance v2 tickets
        if (ticket.getApproBiz() != ApprovalBiz.DM_CHANGE) {
            return null;
        }
        ApprovalMO mo = parseTicketInfo(ticket);
        if (mo == null || mo.getTicketType() == null) {
            return null;
        }

        List<DmTicketDbStmtDO> groups = ticketDbStmtDal.stmtMapper().queryByTicketId(fo.getTicketId());
        List<OpenDbChangeTicketStmtVO.Group> groupVOs = new ArrayList<>();
        if (groups != null) {
            for (DmTicketDbStmtDO g : groups) {
                OpenDbChangeTicketStmtVO.Group gv = new OpenDbChangeTicketStmtVO.Group();
                gv.setGroupId(g.getId());
                gv.setPairId(g.getPairId());
                gv.setDsId(g.getDsId());
                gv.setDbName(g.getDbName());
                gv.setSqlContent(g.getSqlContent());
                gv.setPrecheckResult(g.getPrecheckResult());
                gv.setExecStatus(g.getExecStatus());
                gv.setExecDetail(g.getExecDetail());
                groupVOs.add(gv);
            }
        }

        OpenDbChangeTicketStmtVO vo = new OpenDbChangeTicketStmtVO();
        vo.setTicketId(ticket.getId());
        vo.setTicketType(mo.getTicketType());
        vo.setTitle(ticket.getTicketTitle());
        vo.setTicketStatus(ticket.getTicketStatus() != null ? ticket.getTicketStatus().name() : null);
        // serviceName resolved via batch lookup
        vo.setServiceName(resolveServiceName(mo.getServiceId()));
        vo.setGroups(groupVOs);
        return vo;
    }

    // ==================== Interface C: releaseDetail ====================

    @Override
    public ProdReleaseDetailVO releaseDetail(String puid, String uid, DbChangeReleaseDetailFO fo) {
        // Direct lookup for tenant verification — avoids exception-based control flow
        DmProdReleaseDO release = prodReleaseDal.releaseMapper().queryById(fo.getReleaseId());
        if (release == null || !puid.equals(release.getPrimaryUid())) {
            return null; // not-found semantics — existence not leaked
        }
        // Delegate to existing service for full detail assembly
        return prodReleaseService.getDetail(puid, fo.getReleaseId());
    }

    // ==================== Interface D: returnRelease (external withdrawal sync) ====================

    @Override
    public void returnRelease(String puid, String uid, DbChangeReleaseReturnFO fo) {
        // Resolve releaseId: prefer releaseId, fall back to releaseNo lookup
        long releaseId;
        if (fo.getReleaseId() != null) {
            releaseId = fo.getReleaseId();
        } else if (StringUtils.isNotBlank(fo.getReleaseNo())) {
            DmProdReleaseDO release = prodReleaseDal.releaseMapper().queryByReleaseNo(fo.getReleaseNo());
            if (release == null || !puid.equals(release.getPrimaryUid())) {
                throw new ErrorMessageException("Release not found: " + fo.getReleaseNo());
            }
            releaseId = release.getId();
        } else {
            throw new ErrorMessageException("Either releaseId or releaseNo must be provided");
        }

        // Tenant check (not-found semantics for cross-tenant)
        DmProdReleaseDO release = prodReleaseDal.releaseMapper().queryById(releaseId);
        if (release == null || !puid.equals(release.getPrimaryUid())) {
            throw new ErrorMessageException("Release not found: " + releaseId);
        }

        // Delegate to ProdReleaseService — permission scope = tenant match only (AK/SK is the credential)
        prodReleaseService.returnRelease(puid, uid, releaseId, fo.getReason());
    }

    // ==================== Commands: approve / reject / cancel ====================

    @Override
    public void approveTicket(String puid, String uid, DbChangeTicketCommandFO fo) {
        DmApprovalDO ticket = resolveTicketForCommand(puid, fo.getTicketId());
        ExternalCommandMeta meta = buildCommandMeta(uid, fo);
        approvalProviderService.applyExternalApprovalResult(ticket.getId(), ApprovalInstanceStatus.COMPLETED, meta);
    }

    @Override
    public void rejectTicket(String puid, String uid, DbChangeTicketCommandFO fo) {
        DmApprovalDO ticket = resolveTicketForCommand(puid, fo.getTicketId());
        ExternalCommandMeta meta = buildCommandMeta(uid, fo);
        approvalProviderService.applyExternalApprovalResult(ticket.getId(), ApprovalInstanceStatus.REFUSE, meta);
    }

    @Override
    public void cancelTicket(String puid, String uid, DbChangeTicketCommandFO fo) {
        DmApprovalDO ticket = resolveTicketForCommand(puid, fo.getTicketId());
        ExternalCommandMeta meta = buildCommandMeta(uid, fo);
        approvalProviderService.applyExternalApprovalResult(ticket.getId(), ApprovalInstanceStatus.TERMINATED, meta);
    }

    // ==================== Command: return ====================

    @Override
    public void returnTicket(String puid, String uid, DbChangeTicketCommandFO fo) {
        govTicketV2Service.returnTicketOpenCommand(puid, uid, fo.getTicketId(), fo.getReason());
    }

    // ==================== Command: execute ====================

    @Override
    public void executeTicket(String puid, String uid, DbChangeTicketCommandFO fo) {
        long ticketId = fo.getTicketId();
        DmApprovalDO ticket = approvalDal.approvalMapper().queryById(ticketId);
        if (ticket == null || !puid.equals(ticket.getPrimaryUid())) {
            throw new ErrorMessageException("Ticket not found: " + ticketId);
        }
        // Status guard: must be WAIT_CONFIRM
        if (ticket.getTicketStatus() != ApprovalStatus.WAIT_CONFIRM) {
            throw new ErrorMessageException("Ticket " + ticketId + " is in status "
                + ticket.getTicketStatus() + ", expected WAIT_CONFIRM for execute command");
        }
        // Channel guard: must be External
        if (ticket.getApproType() != ApprovalType.External) {
            throw new ErrorMessageException("Ticket " + ticketId + " uses approval channel "
                + ticket.getApproType() + "; execute command requires the External orchestration channel");
        }

        // Derive autoExecConfig per ticket type DML transaction semantics (D15 routing, 09-15 contract).
        // Release tickets (DM_PROD_RELEASE) use DML semantics (release stmts are always DML snapshots).
        DmAutoExecConfigFO config = buildAutoExecConfigForTicket(ticket);

        // Delegate to the SYSTEM confirm variant — skips checkJobOperationEnable (no user record for SYSTEM),
        // state-machine guards retained. Release tickets go through prepareExecJobAsSystemAsync →
        // startExecution (release branch added below). B7 N0: v2 PROD_DML tickets go through the
        // per-group createGroupJob + startJob loop inside prepareExecJobAsSystemAsync (was:
        // createExecJob single legacy job consuming rawSql = first group only); legacy tickets
        // without ticketInfo.ticketType keep createExecJob + startJob byte-for-byte.
        approvalControlService.confirmTicketBySystem(ticketId, config);

        // Audit event: SYSTEM_CONFIRM, operator = service account uid (NOT SYSTEM — Q4 调和:
        // mechanism context stays SYSTEM, attribution in the event layer).
        appendExecuteCommandEvent(ticketId, uid, ticket.getApproBiz());
    }

    private void appendExecuteCommandEvent(long ticketId, String operatorUid, ApprovalBiz approBiz) {
        DmDbChangeEventDO event = new DmDbChangeEventDO();
        event.setTicketId(ticketId);
        event.setEventType(GovEventType.SYSTEM_CONFIRM.name());
        event.setFromStatus(ApprovalStatus.WAIT_CONFIRM.name());
        event.setToStatus(ApprovalStatus.WAIT_EXEC.name());
        event.setOperatorUid(operatorUid);

        Map<String, Object> data = new HashMap<>();
        data.put("via", "EXTERNAL_COMMAND");
        data.put("approBiz", approBiz.name());
        event.setEventData(JsonUtils.toJson(data));

        dbChangeEventDal.eventMapper().insert(event);
    }

    /**
     * Derive the autoExecConfig for the execute command.
     * <p>
     * Mirrors GovAutoAdvanceServiceImpl.buildAutoExecConfig (D15 routing):
     * - DML → enableTransactional=true
     * - DDL/MIXED → enableTransactional=false
     * - errorStrategy=NONE always
     * - autoExecType=IMMEDIATE always
     * - snapshot=false always
     * <p>
     * ChangeType is resolved from the latest SUBMIT event (same as GovAutoAdvanceServiceImpl.resolveChangeType).
     * Release tickets (DM_PROD_RELEASE) default to DML — release stmts are DML snapshots.
     */
    private DmAutoExecConfigFO buildAutoExecConfigForTicket(DmApprovalDO ticket) {
        ChangeType changeType;
        if (ticket.getApproBiz() == ApprovalBiz.DM_PROD_RELEASE) {
            // Release stmts are always DML snapshots — no DDL in a release.
            changeType = ChangeType.DML;
        } else {
            changeType = resolveChangeTypeFromSubmitEvent(ticket.getId());
        }

        DmAutoExecConfigFO config = new DmAutoExecConfigFO();
        config.setAutoExecType(AutoExecType.IMMEDIATE);
        config.setErrorStrategy(ErrorStrategy.NONE);
        config.setEnableTransactional(changeType == ChangeType.DML);
        config.setRetryWaitTime(null);
        config.setRetryCount(null);
        config.setExecTime(null);
        config.setSnapshot(false);
        return config;
    }

    /**
     * Resolve ChangeType from the latest SUBMIT event (mirrors GovAutoAdvanceServiceImpl.resolveChangeType).
     * Falls back to per-group precheck results if the SUBMIT event lacks changeType.
     */
    private ChangeType resolveChangeTypeFromSubmitEvent(long ticketId) {
        List<DmDbChangeEventDO> events = dbChangeEventDal.eventMapper().queryByTicketId(ticketId);
        DmDbChangeEventDO latestSubmit = null;
        for (int i = events.size() - 1; i >= 0; i--) {
            if (GovEventType.SUBMIT.name().equals(events.get(i).getEventType())) {
                latestSubmit = events.get(i);
                break;
            }
        }
        if (latestSubmit != null) {
            Map<String, Object> data = JsonUtils.toObj(latestSubmit.getEventData(), HashMap.class);
            Object ct = data == null ? null : data.get("changeType");
            if (ct != null) {
                return ChangeType.valueOf(String.valueOf(ct));
            }
        }
        // Legacy fallback: derive from per-group precheck results
        List<ChangeType> groupTypes = new ArrayList<>();
        for (DmTicketDbStmtDO stmt : ticketDbStmtDal.stmtMapper().queryByTicketId(ticketId)) {
            Map<String, Object> precheck = JsonUtils.toObj(stmt.getPrecheckResult(), HashMap.class);
            Object ct = precheck == null ? null : precheck.get("changeType");
            if (ct != null) {
                groupTypes.add(ChangeType.valueOf(String.valueOf(ct)));
            }
        }
        if (groupTypes.isEmpty()) {
            throw new ErrorMessageException("No changeType available for ticket " + ticketId
                + ": SUBMIT event lacks it and no statement group precheck carries it");
        }
        return ChangeType.merge(groupTypes);
    }

    // ==================== Command helpers ====================

    /**
     * Resolve a ticket for a three-state command (approve/reject/cancel).
     * Tenant-filtered: cross-tenant → not-found semantics (existence not leaked).
     * The WAIT_APPROVAL / isEndStatus / External channel guards are enforced inside
     * applyExternalApprovalResult (meta != null path).
     */
    private DmApprovalDO resolveTicketForCommand(String puid, Long ticketId) {
        DmApprovalDO ticket = approvalDal.approvalMapper().queryById(ticketId);
        if (ticket == null || !puid.equals(ticket.getPrimaryUid())) {
            throw new ErrorMessageException("Ticket not found: " + ticketId);
        }
        return ticket;
    }

    private ExternalCommandMeta buildCommandMeta(String operatorUid, DbChangeTicketCommandFO fo) {
        return new ExternalCommandMeta(operatorUid, fo.getApprover(), fo.getComment(), fo.getExternalFlowId());
    }

    // ==================== Helpers: dbName pair resolution ====================

    /**
     * Resolves a dbName to all (dsId, dbName) combos by matching both sides of dm_db_pair.
     * A pair matches if pre_db_name==dbName or prod_db_name==dbName (ENABLED pairs only).
     * For a matching pair:
     *   - pre side match → (pair.preDsId, pair.preDbName)
     *   - prod side match → (pair.prodDsId, pair.prodDbName)
     */
    private Set<DsDbKey> resolveDbNameCombos(String dbName) {
        if (StringUtils.isBlank(dbName)) {
            return Collections.emptySet();
        }

        DmDbPairMapper pairMapper = dbPairDal.pairMapper();
        // Query ENABLED pairs where pre_db_name = dbName OR prod_db_name = dbName
        List<DmDbPairDO> pairs = pairMapper.selectList(
            new LambdaQueryWrapper<DmDbPairDO>()
                .eq(DmDbPairDO::getStatus, STATUS_ENABLED)
                .and(w -> w.eq(DmDbPairDO::getPreDbName, dbName)
                           .or().eq(DmDbPairDO::getProdDbName, dbName)));

        Set<DsDbKey> combos = new java.util.LinkedHashSet<>();
        for (DmDbPairDO pair : pairs) {
            // pre side match
            if (dbName.equals(pair.getPreDbName()) && pair.getPreDsId() != null) {
                combos.add(new DsDbKey(pair.getPreDsId(), pair.getPreDbName()));
            }
            // prod side match
            if (dbName.equals(pair.getProdDbName()) && pair.getProdDsId() != null) {
                combos.add(new DsDbKey(pair.getProdDsId(), pair.getProdDbName()));
            }
        }
        return combos;
    }

    // ==================== Helpers: VO building ====================

    private OpenDbChangeTicketVO buildTicketVO(DmApprovalDO ticket, ApprovalMO mo,
                                                List<DmTicketDbStmtDO> ticketStmts, boolean allSuccess) {
        OpenDbChangeTicketVO vo = new OpenDbChangeTicketVO();
        vo.setTicketId(ticket.getId());
        vo.setTicketType(mo.getTicketType());
        vo.setApprovalMode(mo.getApprovalMode());
        vo.setTicketStatus(ticket.getTicketStatus() != null ? ticket.getTicketStatus().name() : null);
        vo.setTitle(ticket.getTicketTitle());

        // dbNames: unique list from all groups
        List<String> dbNames = new ArrayList<>();
        Set<String> seenDbNames = new HashSet<>();
        for (DmTicketDbStmtDO s : ticketStmts) {
            if (s.getDbName() != null && seenDbNames.add(s.getDbName())) {
                dbNames.add(s.getDbName());
            }
        }
        vo.setDbNames(dbNames);

        // sqlSummary: first 200 chars of first group's SQL
        String sqlContent = ticketStmts.get(0).getSqlContent();
        if (sqlContent != null) {
            vo.setSqlSummary(sqlContent.length() > 200 ? sqlContent.substring(0, 200) : sqlContent);
        }

        // execStatus: derive from ticket status + group statuses
        vo.setExecStatus(deriveExecStatus(ticket, ticketStmts, allSuccess));

        // executedAt: gmtCreate of the first SUCCESS group (or null)
        for (DmTicketDbStmtDO s : ticketStmts) {
            if (EXEC_STATUS_SUCCESS.equals(s.getExecStatus()) && s.getGmtModified() != null) {
                vo.setExecutedAt(DateFormatType.s_yyyyMMdd_HHmmss.format(s.getGmtModified()));
                break;
            }
        }

        // promoted: check if any stmt group already merged into a release
        // (not just the first group — a ticket may have multiple DBs, some promoted some not)
        DmProdReleaseStmtDO releaseStmt = null;
        for (DmTicketDbStmtDO s : ticketStmts) {
            releaseStmt = prodReleaseDal.stmtMapper().queryBySourceStmtId(s.getId());
            if (releaseStmt != null) {
                break;
            }
        }
        boolean promoted = releaseStmt != null;
        vo.setPromoted(promoted);
        if (promoted && releaseStmt != null) {
            DmProdReleaseDO release = prodReleaseDal.releaseMapper().queryById(releaseStmt.getReleaseId());
            if (release != null) {
                vo.setReleaseId(release.getId());
                vo.setReleaseNo(release.getReleaseNo());
                vo.setReleaseStatus(release.getStatus());
            }
        }

        return vo;
    }

    /**
     * Derives a display execStatus from the ticket status and group execution statuses.
     * WAIT_APPROVAL → if all PENDING → "WAIT_APPROVAL"
     * EXECUTING → if any group EXECUTING or mixed PENDING/SUCCESS → "EXECUTING"
     * EXECUTED → if ticket FINISHED && all SUCCESS
     * FAILED → if any group FAILED
     */
    private String deriveExecStatus(DmApprovalDO ticket, List<DmTicketDbStmtDO> stmts, boolean allSuccess) {
        boolean anyFailed = stmts.stream().anyMatch(s -> EXEC_STATUS_FAILED.equals(s.getExecStatus()));
        if (anyFailed) {
            return EXEC_STATUS_FAILED;
        }
        if (ticket.getTicketStatus() == ApprovalStatus.FINISHED && allSuccess) {
            return "EXECUTED";
        }
        boolean anyExecuting = stmts.stream().anyMatch(s -> EXEC_STATUS_EXECUTING.equals(s.getExecStatus()));
        if (anyExecuting) {
            return EXEC_STATUS_EXECUTING;
        }
        // No FAILED, no EXECUTING, not all SUCCESS → either all PENDING (WAIT_APPROVAL) or partial SUCCESS (EXECUTING)
        boolean anySuccess = stmts.stream().anyMatch(s -> EXEC_STATUS_SUCCESS.equals(s.getExecStatus()));
        if (anySuccess) {
            return EXEC_STATUS_EXECUTING; // some done, some pending
        }
        return ticket.getTicketStatus() != null ? ticket.getTicketStatus().name() : EXEC_STATUS_PENDING;
    }

    // ==================== Helpers: serviceName ====================

    private void fillServiceNames(Map<OpenDbChangeTicketVO, Long> voServiceIds) {
        if (voServiceIds.isEmpty()) {
            return;
        }
        Set<Long> serviceIds = new HashSet<>();
        for (Long sid : voServiceIds.values()) {
            if (sid != null) {
                serviceIds.add(sid);
            }
        }
        if (serviceIds.isEmpty()) {
            return;
        }
        DmDbServiceMapper serviceMapper = dbPairDal.serviceMapper();
        List<DmDbServiceDO> services = serviceMapper.selectBatchIds(serviceIds);
        Map<Long, String> nameMap = new java.util.HashMap<>();
        if (services != null) {
            for (DmDbServiceDO svc : services) {
                nameMap.put(svc.getId(), svc.getServiceName());
            }
        }
        for (Map.Entry<OpenDbChangeTicketVO, Long> e : voServiceIds.entrySet()) {
            Long sid = e.getValue();
            if (sid != null) {
                e.getKey().setServiceName(nameMap.get(sid));
            }
        }
    }

    private String resolveServiceName(Long serviceId) {
        if (serviceId == null) {
            return null;
        }
        DmDbServiceDO svc = dbPairDal.serviceMapper().selectById(serviceId);
        return svc != null ? svc.getServiceName() : null;
    }

    // ==================== Helpers: misc ====================

    private ApprovalMO parseTicketInfo(DmApprovalDO ticket) {
        if (ticket == null || StringUtils.isBlank(ticket.getTicketInfo())) {
            return null;
        }
        try {
            return JsonUtils.toObj(ticket.getTicketInfo(), ApprovalMO.class);
        } catch (Exception e) {
            return null;
        }
    }

    private <T> List<T> paginate(List<T> list, int page, int size) {
        if (page < 1) {
            page = 1;
        }
        if (size < 1) {
            size = 20;
        }
        int from = (page - 1) * size;
        if (from >= list.size()) {
            return Collections.emptyList();
        }
        int to = Math.min(from + size, list.size());
        return list.subList(from, to);
    }

    /** Composite key for (dsId, dbName) — used for deduplication in pair resolution. */
    private static final class DsDbKey {
        final long   dsId;
        final String dbName;

        DsDbKey(long dsId, String dbName) {
            this.dsId = dsId;
            this.dbName = dbName;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof DsDbKey)) return false;
            DsDbKey that = (DsDbKey) o;
            return dsId == that.dsId && java.util.Objects.equals(dbName, that.dbName);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(dsId, dbName);
        }
    }

    // ==================== Command: submit / resubmit (Step 8) ====================

    @Override
    public OpenDbChangeTicketSubmitVO submitTicket(String puid, String uid, DbChangeTicketSubmitFO fo) {
        // Resolve ownerAccount → uid (tenant-internal check; not-found semantics)
        String ownerUid = resolveAccountUid(puid, fo.getOwnerAccount());

        // Build GovTicketV2SubmitFO from the open FO subset
        GovTicketV2SubmitFO submitFO = new GovTicketV2SubmitFO();
        submitFO.setTicketType(fo.getTicketType());
        submitFO.setServiceId(fo.getServiceId());
        submitFO.setTicketTitle(fo.getTicketTitle());
        submitFO.setDescription(fo.getDescription());
        submitFO.setApprovalMode(fo.getApprovalMode());
        List<GovTicketV2SubmitFO.GroupInput> groups = new ArrayList<>();
        if (fo.getGroups() != null) {
            for (DbChangeTicketSubmitFO.GroupInput gi : fo.getGroups()) {
                GovTicketV2SubmitFO.GroupInput g = new GovTicketV2SubmitFO.GroupInput();
                g.setPairId(gi.getPairId());
                g.setSqlContent(gi.getSqlContent());
                groups.add(g);
            }
        }
        submitFO.setGroups(groups);

        // Delegate to GovTicketV2Service.submit (no owner identity check in submit — any tenant member can submit)
        GovTicketV2SubmitVO submitResult;
        try {
            submitResult = govTicketV2Service.submit(puid, ownerUid, submitFO);
        } catch (com.clougence.clouddm.console.web.service.dmlbatch.DmlNoKeyRejectException e) {
            // B7 N2 (design §8.2 + R18 parity): the open channel fires the SAME out-of-transaction
            // trail as the web controller boundary (subject = the resolved ownerUid = submitter;
            // no HttpServletRequest here — the URI marker identifies the channel), then rethrows
            // unchanged for the open error envelope. The outer guard mirrors the controller's
            // belt: the trail never preempts the rejection semantics.
            try {
                dmlBatchRejectTrailService.trailNoKeyReject(puid, ownerUid, OPEN_SUBMIT_URI, null, e);
            } catch (RuntimeException trailFailure) {
                log.error("[GovOpenApi] the no-key rejection trail failed (the rejection itself is rethrown unchanged)",
                    trailFailure);
            }
            throw e;
        }

        // Build response VO — query the created ticket to get bizId and status
        DmApprovalDO ticket = approvalDal.approvalMapper().queryById(submitResult.getTicketId());
        OpenDbChangeTicketSubmitVO vo = new OpenDbChangeTicketSubmitVO();
        vo.setTicketId(submitResult.getTicketId());
        vo.setTicketType(submitResult.getTicketType());
        if (ticket != null) {
            vo.setBizId(ticket.getBizId());
            vo.setTicketStatus(ticket.getTicketStatus() != null ? ticket.getTicketStatus().name() : null);
        }
        return vo;
    }

    @Override
    public OpenDbChangeTicketSubmitVO resubmitTicket(String puid, String uid, DbChangeTicketResubmitFO fo) {
        // Build GovTicketV2ResubmitFO from the open FO
        GovTicketV2ResubmitFO resubmitFO = new GovTicketV2ResubmitFO();
        resubmitFO.setTicketId(fo.getTicketId());
        resubmitFO.setReason(fo.getReason());
        resubmitFO.setRollBackSql(fo.getRollBackSql());
        List<GovTicketV2ResubmitFO.GroupInput> groups = new ArrayList<>();
        if (fo.getGroups() != null) {
            for (DbChangeTicketResubmitFO.GroupInput gi : fo.getGroups()) {
                GovTicketV2ResubmitFO.GroupInput g = new GovTicketV2ResubmitFO.GroupInput();
                g.setGroupId(gi.getGroupId());
                g.setSqlContent(gi.getSqlContent());
                groups.add(g);
            }
        }
        resubmitFO.setGroups(groups);

        // Delegate to the open command resubmit (skips owner-only check)
        govTicketV2Service.resubmitOpenCommand(puid, uid, resubmitFO);

        // Build response VO
        DmApprovalDO ticket = approvalDal.approvalMapper().queryById(fo.getTicketId());
        OpenDbChangeTicketSubmitVO vo = new OpenDbChangeTicketSubmitVO();
        if (ticket != null) {
            vo.setTicketId(ticket.getId());
            vo.setBizId(ticket.getBizId());
            vo.setTicketStatus(ticket.getTicketStatus() != null ? ticket.getTicketStatus().name() : null);
            ApprovalMO mo = parseTicketInfo(ticket);
            if (mo != null) {
                vo.setTicketType(mo.getTicketType());
            }
        }
        return vo;
    }

    // ==================== Command: releases create / resubmit (Step 9) ====================

    @Override
    public OpenDbChangeReleaseCreateVO createRelease(String puid, String uid, DbChangeReleaseCreateFO fo) {
        // Resolve creatorAccount (optional — defaults to service account uid)
        String creatorUid = uid;
        if (StringUtils.isNotBlank(fo.getCreatorAccount())) {
            creatorUid = resolveAccountUid(puid, fo.getCreatorAccount());
        }

        // Build ProdReleaseCreateFO
        com.clougence.clouddm.console.web.model.fo.prodrelease.ProdReleaseCreateFO createFO =
            new com.clougence.clouddm.console.web.model.fo.prodrelease.ProdReleaseCreateFO();
        createFO.setTicketIds(fo.getTicketIds());
        createFO.setTitle(fo.getTitle());

        // Delegate to ProdReleaseService.createRelease (full chain: eligibility/gates/tenant/lock/uk)
        long releaseId = prodReleaseService.createRelease(puid, creatorUid, createFO);

        // Build response VO — releaseId + releaseNo + approval ticketId
        DmProdReleaseDO release = prodReleaseDal.releaseMapper().queryById(releaseId);
        OpenDbChangeReleaseCreateVO vo = new OpenDbChangeReleaseCreateVO();
        vo.setReleaseId(releaseId);
        if (release != null) {
            vo.setReleaseNo(release.getReleaseNo());
            vo.setTicketId(release.getApprovalId()); // release's dm_approval id (Q9 command routing key)
        }
        return vo;
    }

    @Override
    public void resubmitRelease(String puid, String uid, DbChangeReleaseResubmitFO fo) {
        // Build ProdReleaseResubmitFO
        com.clougence.clouddm.console.web.model.fo.prodrelease.ProdReleaseResubmitFO resubmitFO =
            new com.clougence.clouddm.console.web.model.fo.prodrelease.ProdReleaseResubmitFO();
        resubmitFO.setReleaseId(fo.getReleaseId());
        resubmitFO.setTicketIds(fo.getTicketIds());
        resubmitFO.setTitle(fo.getTitle());

        // Delegate to the open command resubmit (skips creator/primary/maintainer check)
        prodReleaseService.resubmitReleaseOpenCommand(puid, uid, resubmitFO);
    }

    // ==================== Read: ticket detail (Step 10) ====================

    @Override
    public OpenDbChangeTicketDetailVO ticketDetail(String puid, String uid, DbChangeTicketDetailFO fo) {
        // Tenant check — cross-tenant returns null (not-found semantics)
        DmApprovalDO ticket = approvalDal.approvalMapper().queryById(fo.getTicketId());
        if (ticket == null || !puid.equals(ticket.getPrimaryUid())) {
            return null;
        }

        // Delegate to console queryTicketBaseInfo (refreshCache External skip already in place — batch 1).
        // Console-specific permission flags (canApproval/canExecute/canClose) are computed but not
        // exposed in the open VO (desensitized).
        RdpQueryTicketDetailFO baseFO = new RdpQueryTicketDetailFO();
        baseFO.setTicketId(fo.getTicketId());
        baseFO.setRefreshCache(false); // open API callers never trigger cache refresh
        RdpTicketBaseInfoVO baseVO = approvalControlService.queryTicketBaseInfo(puid, uid, baseFO);

        OpenDbChangeTicketDetailVO vo = new OpenDbChangeTicketDetailVO();
        if (baseVO == null) {
            return vo;
        }
        vo.setTicketId(baseVO.getId());
        vo.setBizId(baseVO.getBizId());
        vo.setTicketTitle(baseVO.getTicketTitle());
        vo.setTicketType(baseVO.getTicketType());
        vo.setTicketStatus(baseVO.getTicketStatus() != null ? baseVO.getTicketStatus().name() : null);
        vo.setServiceName(baseVO.getServiceName());
        vo.setApproType(baseVO.getApproType() != null ? baseVO.getApproType().name() : null);
        vo.setApproIdentity(baseVO.getApproIdentity());
        vo.setApproTemplateName(baseVO.getApproTemplateName());
        vo.setDescription(baseVO.getDescription());
        vo.setStatusMessage(baseVO.getStatusMessage());
        vo.setOwnerUserName(baseVO.getUserName());
        vo.setTargetInfo(baseVO.getTargetInfo());
        vo.setGmtCreate(baseVO.getGmtCreate());
        vo.setGmtModified(baseVO.getGmtModified());
        vo.setFinishTime(baseVO.getFinishTime());
        vo.setReleaseId(baseVO.getReleaseId() != null ? String.valueOf(baseVO.getReleaseId()) : null);
        vo.setReleaseNo(baseVO.getReleaseNo());

        // Process list with activities
        List<OpenDbChangeTicketDetailVO.ProcessEntry> processEntries = new ArrayList<>();
        if (baseVO.getTicketProcessVOList() != null) {
            for (RdpTicketProcessVO procVO : baseVO.getTicketProcessVOList()) {
                OpenDbChangeTicketDetailVO.ProcessEntry pe = new OpenDbChangeTicketDetailVO.ProcessEntry();
                pe.setStage(procVO.getTicketStage() != null ? procVO.getTicketStage().name() : null);
                pe.setProcessStatus(procVO.getTicketProcessStatus() != null ? procVO.getTicketProcessStatus().name() : null);
                pe.setStartTime(procVO.getGmtCreate());
                pe.setFinishTime(procVO.getFinishTime());
                pe.setExecMsg(procVO.getStageContext());
                if (procVO.isHasActivity() && procVO.getActivityList() != null) {
                    List<OpenDbChangeTicketDetailVO.ActivityEntry> activities = new ArrayList<>();
                    for (RdpTicketActivityVO actVO : procVO.getActivityList()) {
                        OpenDbChangeTicketDetailVO.ActivityEntry ae = new OpenDbChangeTicketDetailVO.ActivityEntry();
                        ae.setActivityTitle(actVO.getActivityTitle());
                        ae.setActivityStatus(actVO.getActivityStatus() != null ? actVO.getActivityStatus().name() : null);
                        ae.setStartTime(actVO.getStartTime());
                        ae.setFinishTime(actVO.getFinishTime());
                        ae.setRemark(actVO.getRemark());
                        if (actVO.getApprovalUserList() != null && !actVO.getApprovalUserList().isEmpty()) {
                            ae.setApprovalUser(String.join(", ", actVO.getApprovalUserList()));
                        }
                        activities.add(ae);
                    }
                    pe.setActivities(activities);
                }
                processEntries.add(pe);
            }
        }
        vo.setProcessList(processEntries);

        // Event trail
        List<DmDbChangeEventDO> events = dbChangeEventDal.eventMapper().queryByTicketId(fo.getTicketId());
        List<OpenDbChangeTicketDetailVO.EventEntry> eventEntries = new ArrayList<>();
        if (events != null) {
            for (DmDbChangeEventDO event : events) {
                OpenDbChangeTicketDetailVO.EventEntry ee = new OpenDbChangeTicketDetailVO.EventEntry();
                ee.setEventType(event.getEventType());
                ee.setFromStatus(event.getFromStatus());
                ee.setToStatus(event.getToStatus());
                ee.setOperatorUid(event.getOperatorUid());
                ee.setGmtCreate(event.getGmtCreate() != null
                    ? com.clougence.utils.format.DateFormatType.s_yyyyMMdd_HHmmss.format(event.getGmtCreate()) : null);
                ee.setEventData(event.getEventData());
                eventEntries.add(ee);
            }
        }
        vo.setEvents(eventEntries);

        return vo;
    }

    // ==================== Read: ledger list (ding-bridge R5) ====================

    @Override
    public List<OpenDbChangeLedgerTicketVO> ledgerListOpen(String puid, String uid, DbChangeLedgerListFO fo) {
        // Console projection is the single source of truth for the two admission branches
        // (PRE_DDL executed / PROD_DML VIA_RELEASE pool). GovLedgerServiceImpl.list is
        // deliberately tenant-agnostic (D6) and MUST stay untouched — tenant isolation for
        // the open surface is layered on HERE: batch-load the tickets behind the projected
        // rows and keep only primaryUid == caller puid. Cross-tenant rows are silently
        // dropped (empty list — list semantics, never not-found; existence not leaked).
        List<GovLedgerTicketVO> rows = govLedgerService.list(puid);
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }

        Set<Long> ticketIds = new HashSet<>();
        for (GovLedgerTicketVO row : rows) {
            ticketIds.add(row.getTicketId());
        }
        List<DmApprovalDO> tickets = approvalDal.approvalMapper().selectBatchIds(ticketIds);
        Map<Long, DmApprovalDO> ticketMap = new HashMap<>();
        if (tickets != null) {
            for (DmApprovalDO t : tickets) {
                ticketMap.put(t.getId(), t);
            }
        }

        List<OpenDbChangeLedgerTicketVO> result = new ArrayList<>();
        for (GovLedgerTicketVO row : rows) {
            DmApprovalDO ticket = ticketMap.get(row.getTicketId());
            if (ticket == null || !puid.equals(ticket.getPrimaryUid())) {
                continue; // tenant isolation (also skips rows whose ticket vanished mid-flight)
            }
            if (StringUtils.isNotBlank(fo.getTicketType()) && !fo.getTicketType().equals(row.getTicketType())) {
                continue;
            }
            if (fo.getPromoted() != null && fo.getPromoted().booleanValue() != row.isPromoted()) {
                continue;
            }
            result.add(toOpenLedgerVO(row));
        }

        return paginate(result, fo.getPageNum(), fo.getPageSize());
    }

    /**
     * Map the console ledger row to the desensitized open shape: list-level fields only —
     * sqlSummary (≤200 chars, capped by the console projection), no full SQL, no connection
     * strings, and ownerUid is deliberately NOT carried over.
     */
    private OpenDbChangeLedgerTicketVO toOpenLedgerVO(GovLedgerTicketVO row) {
        OpenDbChangeLedgerTicketVO vo = new OpenDbChangeLedgerTicketVO();
        vo.setTicketId(row.getTicketId());
        vo.setTicketTitle(row.getTicketTitle());
        vo.setServiceName(row.getServiceName());
        vo.setServiceId(row.getServiceId());
        vo.setGmtCreate(row.getGmtCreate());
        vo.setExecStatus(row.getExecStatus());
        vo.setSqlSummary(row.getSqlSummary());
        vo.setTicketType(row.getTicketType());
        vo.setApprovalMode(row.getApprovalMode());
        vo.setPreDbs(row.getPreDbs());
        vo.setProdDbs(row.getProdDbs());
        vo.setPromoted(row.isPromoted());
        vo.setReleaseNo(row.getReleaseNo());
        vo.setReleaseStatus(row.getReleaseStatus());
        vo.setGhostRouted(row.isGhostRouted());
        vo.setDmlBatchRouted(row.isDmlBatchRouted());
        return vo;
    }

    // ==================== Account resolution helper ====================

    /**
     * Resolve a platform account name to its uid, with tenant-internal check.
     * Cross-tenant resolution → not-found semantics (existence not leaked).
     * @param puid caller's primary uid (tenant realm)
     * @param account platform account name
     * @return the resolved uid
     * @throws ErrorMessageException if the account doesn't exist or doesn't belong to the caller's tenant
     */
    private String resolveAccountUid(String puid, String account) {
        DmAuthUserDO user = authDal.userMapper().queryBySubAccount(account);
        if (user == null) {
            throw new ErrorMessageException("Account not found: " + account);
        }
        // Tenant-internal check: resolved user's primaryUid must equal caller's puid
        String userPuid = user.getParentId() != null ? String.valueOf(user.getParentId()) : user.getUid();
        if (!puid.equals(userPuid)) {
            throw new ErrorMessageException("Account not found: " + account);
        }
        return user.getUid();
    }
}
