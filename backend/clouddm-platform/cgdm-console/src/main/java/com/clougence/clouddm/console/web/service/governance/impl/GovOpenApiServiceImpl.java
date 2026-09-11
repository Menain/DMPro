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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.component.auth.DmResAuthService;
import com.clougence.clouddm.console.web.model.fo.openapi.DbChangeReleaseDetailFO;
import com.clougence.clouddm.console.web.model.fo.openapi.DbChangeTicketListFO;
import com.clougence.clouddm.console.web.model.fo.openapi.DbChangeTicketStmtFO;
import com.clougence.clouddm.console.web.model.vo.openapi.OpenDbChangeTicketStmtVO;
import com.clougence.clouddm.console.web.model.vo.openapi.OpenDbChangeTicketVO;
import com.clougence.clouddm.console.web.model.vo.prodrelease.ProdReleaseDetailVO;
import com.clougence.clouddm.console.web.service.governance.GovOpenApiService;
import com.clougence.clouddm.console.web.service.governance.ProdReleaseService;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.DbPairDal;
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
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.dbpair.DmDbPairDO;
import com.clougence.clouddm.platform.dal.model.dbpair.DmDbServiceDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseStmtDO;
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
    @Resource
    private DmResAuthService     dmResAuthService;

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

    private List<OpenDbChangeTicketVO> paginate(List<OpenDbChangeTicketVO> list, int page, int size) {
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
}
