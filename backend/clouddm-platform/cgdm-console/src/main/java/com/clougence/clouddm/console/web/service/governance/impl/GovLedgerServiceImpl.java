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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.model.fo.prodrelease.GovLedgerDetailFO;
import com.clougence.clouddm.console.web.model.vo.govticket.GovTicketV2GroupVO;
import com.clougence.clouddm.console.web.model.vo.prodrelease.GovLedgerTicketVO;
import com.clougence.clouddm.console.web.model.vo.prodrelease.LedgerDbItemVO;
import com.clougence.clouddm.console.web.service.governance.GovLedgerService;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.DataSourceDal;
import com.clougence.clouddm.platform.dal.access.DbPairDal;
import com.clougence.clouddm.platform.dal.access.ProdReleaseDal;
import com.clougence.clouddm.platform.dal.access.TicketDbStmtDal;
import com.clougence.clouddm.platform.dal.mapper.datasource.DmDsMapper;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.datasource.DmDsDO;
import com.clougence.clouddm.platform.dal.model.dbpair.DmDbPairDO;
import com.clougence.clouddm.platform.dal.model.dbpair.DmDbServiceDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseStmtDO;
import com.clougence.utils.JsonUtils;
import com.clougence.utils.format.DateFormatType;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * Ledger service — read-only, no primary_uid filtering (D6: all logged-in users can see).
 * Lists tickets in two admission branches:
 * <ul>
 *   <li>Branch A (existing): PRE_DDL, FINISHED, all stmts SUCCESS — pre-env executed, awaiting release merge.</li>
 *   <li>Branch B (new): PROD_DML, approvalMode=VIA_RELEASE, status∈{WAIT_RELEASE, FINISHED},
 *       all stmts PENDING — entered the release pool at submit (no approval process), awaiting
 *       release merge; FINISHED rows (via-release-completed) remain for traceability.</li>
 * </ul>
 * Direct PROD_DML tickets (FINISHED + all SUCCESS, no VIA_RELEASE flag) never appear.
 * Aligned with {@code ProdReleaseServiceImpl.collectSourceEntries} so the frontend can never show
 * a selectable ticket that would be rejected on submit.
 */
@Slf4j
@Service
public class GovLedgerServiceImpl implements GovLedgerService {

    private static final String TICKET_TYPE_PRE_DDL  = "PRE_DDL";
    private static final String TICKET_TYPE_PROD_DML = "PROD_DML";
    private static final String APPROVAL_MODE_VIA_RELEASE = "VIA_RELEASE";
    private static final String EXEC_STATUS_SUCCESS  = "SUCCESS";
    private static final String EXEC_STATUS_PENDING = "PENDING";
    private static final String STATUS_ENABLED       = "ENABLED";

    @Resource
    private TicketDbStmtDal    ticketDbStmtDal;
    @Resource
    private ApprovalDal         approvalDal;
    @Resource
    private ProdReleaseDal      prodReleaseDal;
    @Resource
    private DbPairDal          dbPairDal;
    @Resource
    private DataSourceDal       dsDal;

    @Override
    public List<GovLedgerTicketVO> list(String puid) {
        // 1. Load all statement groups, ordered by id for deterministic first-stmt semantics
        List<DmTicketDbStmtDO> stmts = ticketDbStmtDal.stmtMapper().selectList(
            new LambdaQueryWrapper<DmTicketDbStmtDO>().orderByAsc(DmTicketDbStmtDO::getId));
        if (stmts == null || stmts.isEmpty()) {
            return Collections.emptyList();
        }

        // 2. Group by ticketId (LinkedHashMap preserves first-seen order)
        Map<Long, List<DmTicketDbStmtDO>> byTicket = new LinkedHashMap<>();
        for (DmTicketDbStmtDO s : stmts) {
            byTicket.computeIfAbsent(s.getTicketId(), k -> new ArrayList<>()).add(s);
        }

        // 3. Batch-load all tickets (avoid N+1)
        Set<Long> ticketIds = byTicket.keySet();
        List<DmApprovalDO> tickets = approvalDal.approvalMapper().selectBatchIds(ticketIds);
        Map<Long, DmApprovalDO> ticketMap = new HashMap<>();
        if (tickets != null) {
            for (DmApprovalDO t : tickets) {
                ticketMap.put(t.getId(), t);
            }
        }

        // 4. Batch-load all distinct pairs (for prodDbs resolution)
        Set<Long> pairIds = new HashSet<>();
        for (DmTicketDbStmtDO s : stmts) {
            if (s.getPairId() != null) {
                pairIds.add(s.getPairId());
            }
        }
        Map<Long, DmDbPairDO> pairMap = batchLoadPairs(pairIds);

        // 5. Resolve dsNames for all distinct dsIds (pre + prod) with a cache map
        Set<Long> dsIds = new HashSet<>();
        for (DmTicketDbStmtDO s : stmts) {
            if (s.getDsId() != null) {
                dsIds.add(s.getDsId());
            }
        }
        for (DmDbPairDO pair : pairMap.values()) {
            if (pair.getProdDsId() != null) {
                dsIds.add(pair.getProdDsId());
            }
        }
        Map<Long, String> dsNameMap = resolveDsNames(dsIds);

        // 6. Build VOs
        Map<GovLedgerTicketVO, Long> voServiceIds = new LinkedHashMap<>();
        List<GovLedgerTicketVO> result = new ArrayList<>();
        for (Map.Entry<Long, List<DmTicketDbStmtDO>> entry : byTicket.entrySet()) {
            long ticketId = entry.getKey();
            List<DmTicketDbStmtDO> ticketStmts = entry.getValue();

            DmApprovalDO ticket = ticketMap.get(ticketId);
            if (ticket == null) continue;

            // Two admission branches (union):
            //   A (existing): PRE_DDL && FINISHED && all SUCCESS
            //   B (new): PROD_DML && approvalMode==VIA_RELEASE && status∈{WAIT_RELEASE, FINISHED} && all PENDING
            // Direct PROD_DML (FINISHED + all SUCCESS, no VIA_RELEASE flag) matches neither branch.
            ApprovalMO mo = JsonUtils.toObj(ticket.getTicketInfo(), ApprovalMO.class);
            String ticketType = mo != null ? mo.getTicketType() : null;
            String approvalMode = mo != null ? mo.getApprovalMode() : null;
            ApprovalStatus ticketStatus = ticket.getTicketStatus();

            boolean branchA = TICKET_TYPE_PRE_DDL.equals(ticketType)
                && ticketStatus == ApprovalStatus.FINISHED
                && ticketStmts.stream().allMatch(s -> EXEC_STATUS_SUCCESS.equals(s.getExecStatus()));

            boolean branchB = TICKET_TYPE_PROD_DML.equals(ticketType)
                && APPROVAL_MODE_VIA_RELEASE.equals(approvalMode)
                && (ticketStatus == ApprovalStatus.WAIT_RELEASE || ticketStatus == ApprovalStatus.FINISHED)
                && ticketStmts.stream().allMatch(s -> EXEC_STATUS_PENDING.equals(s.getExecStatus()));

            if (!branchA && !branchB) continue;

            // Promoted check: first stmt already in dm_prod_release_stmt (shared by both branches)
            DmTicketDbStmtDO firstStmt = ticketStmts.get(0);
            DmProdReleaseStmtDO releaseStmt = prodReleaseDal.stmtMapper().queryBySourceStmtId(firstStmt.getId());
            boolean promoted = releaseStmt != null;

            GovLedgerTicketVO vo = new GovLedgerTicketVO();
            vo.setTicketId(ticketId);
            vo.setTicketTitle(ticket.getTicketTitle());
            vo.setOwnerUid(ticket.getOwnerUid());
            vo.setGmtCreate(DateFormatType.s_yyyyMMdd_HHmmss.format(ticket.getGmtCreate()));
            vo.setTicketType(ticketType);
            vo.setApprovalMode(approvalMode);
            vo.setExecStatus(branchA ? EXEC_STATUS_SUCCESS : EXEC_STATUS_PENDING);
            String sqlContent = firstStmt.getSqlContent();
            if (sqlContent != null) {
                vo.setSqlSummary(sqlContent.length() > 200 ? sqlContent.substring(0, 200) : sqlContent);
            }
            vo.setServiceId(mo.getServiceId());
            vo.setPreDbs(buildPreDbs(ticketStmts, dsNameMap));
            vo.setProdDbs(buildProdDbs(ticketStmts, pairMap, dsNameMap));
            vo.setPromoted(promoted);
            // B6 M3 过渡明示 (additive display flag): any group carrying a ghost route mark
            // (segment plan ∨ M1 ghostRoutes) → the merge-confirm modal shows the direct-replay
            // lock-risk hint (release-chain ghost routing is M5).
            vo.setGhostRouted(ticketStmts.stream().anyMatch(
                s -> com.clougence.clouddm.console.web.component.governance.GhostRouteMarks
                    .hasGhostRoute(s.getPrecheckResult())));
            if (promoted && releaseStmt != null) {
                DmProdReleaseDO release = prodReleaseDal.releaseMapper().queryById(releaseStmt.getReleaseId());
                if (release != null) {
                    vo.setReleaseNo(release.getReleaseNo());
                    vo.setReleaseStatus(release.getStatus());
                }
            }

            voServiceIds.put(vo, mo.getServiceId());
            result.add(vo);
        }

        // 7. Batch-fill serviceName from dm_db_service
        fillServiceNames(voServiceIds);

        return result;
    }

    /**
     * Batch-load pairs by id; returns a map keyed by pair id.
     */
    private Map<Long, DmDbPairDO> batchLoadPairs(Set<Long> pairIds) {
        if (pairIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<DmDbPairDO> pairs = dbPairDal.pairMapper().selectBatchIds(pairIds);
        if (pairs == null || pairs.isEmpty()) {
            return Collections.emptyMap();
        }
        return pairs.stream().collect(Collectors.toMap(DmDbPairDO::getId, p -> p, (a, b) -> a));
    }

    /**
     * Resolve ds display names for a set of dsIds, caching each query.
     * dsName = instanceDesc if present, else instanceId (same convention as DbPairServiceImpl.toDbPairVO).
     */
    private Map<Long, String> resolveDsNames(Set<Long> dsIds) {
        if (dsIds.isEmpty()) {
            return Collections.emptyMap();
        }
        DmDsMapper dsMapper = dsDal.dsMapper();
        Map<Long, String> map = new HashMap<>();
        for (Long dsId : dsIds) {
            if (dsId == null) {
                continue;
            }
            DmDsDO ds = dsMapper.queryDsIdentityById(dsId);
            if (ds != null) {
                map.put(dsId, ds.getInstanceDesc() != null ? ds.getInstanceDesc() : ds.getInstanceId());
            }
        }
        return map;
    }

    /**
     * Build deduped, ordered list of pre-release database items from a ticket's statement groups.
     */
    private List<LedgerDbItemVO> buildPreDbs(List<DmTicketDbStmtDO> ticketStmts, Map<Long, String> dsNameMap) {
        Set<String> seen = new LinkedHashSet<>();
        List<LedgerDbItemVO> result = new ArrayList<>();
        for (DmTicketDbStmtDO s : ticketStmts) {
            String key = s.getDsId() + "|" + s.getDbName();
            if (seen.add(key)) {
                LedgerDbItemVO item = new LedgerDbItemVO();
                item.setDsId(s.getDsId());
                item.setDbName(s.getDbName());
                item.setDsName(dsNameMap.get(s.getDsId()));
                result.add(item);
            }
        }
        return result;
    }

    /**
     * Build deduped, ordered list of prod database display names ("dsName/dbName") from a ticket's pairs.
     * Pairs that are missing or disabled are skipped with a warning log — the ledger is read-only;
     * createRelease will hard-validate when the user actually submits.
     */
    private List<String> buildProdDbs(List<DmTicketDbStmtDO> ticketStmts, Map<Long, DmDbPairDO> pairMap, Map<Long, String> dsNameMap) {
        Set<String> seen = new LinkedHashSet<>();
        for (DmTicketDbStmtDO s : ticketStmts) {
            if (s.getPairId() == null) {
                continue;
            }
            DmDbPairDO pair = pairMap.get(s.getPairId());
            if (pair == null) {
                log.warn("Ledger: pair {} not found for ticket {} stmt {}, skipping prodDb", s.getPairId(), s.getTicketId(), s.getId());
                continue;
            }
            if (!STATUS_ENABLED.equals(pair.getStatus())) {
                log.warn("Ledger: pair {} status is {}, skipping prodDb for ticket {}", s.getPairId(), pair.getStatus(), s.getTicketId());
                continue;
            }
            String prodDsName = dsNameMap.get(pair.getProdDsId());
            if (prodDsName == null) {
                prodDsName = String.valueOf(pair.getProdDsId());
            }
            String label = prodDsName + "/" + pair.getProdDbName();
            seen.add(label);
        }
        return new ArrayList<>(seen);
    }

    /**
     * Batch-resolve serviceName from dm_db_service by serviceId collected from ticketInfo JSON.
     * serviceId null or not found in dm_db_service → serviceName stays null (frontend tolerates).
     */
    private void fillServiceNames(Map<GovLedgerTicketVO, Long> voServiceIds) {
        if (voServiceIds.isEmpty()) return;

        Set<Long> serviceIds = new HashSet<>();
        for (Long sid : voServiceIds.values()) {
            if (sid != null) {
                serviceIds.add(sid);
            }
        }
        if (serviceIds.isEmpty()) return;

        List<DmDbServiceDO> services = dbPairDal.serviceMapper().selectBatchIds(serviceIds);
        Map<Long, String> nameMap = new HashMap<>();
        if (services != null) {
            for (DmDbServiceDO svc : services) {
                nameMap.put(svc.getId(), svc.getServiceName());
            }
        }

        for (Map.Entry<GovLedgerTicketVO, Long> e : voServiceIds.entrySet()) {
            Long sid = e.getValue();
            if (sid != null) {
                e.getKey().setServiceName(nameMap.get(sid));
            }
        }
    }

    @Override
    public List<GovTicketV2GroupVO> detail(String puid, GovLedgerDetailFO fo) {
        // Read-only: return all statement groups for this ticket (every pre-release db)
        List<DmTicketDbStmtDO> stmts = ticketDbStmtDal.stmtMapper().queryByTicketId(fo.getTicketId());
        if (stmts == null || stmts.isEmpty()) {
            return Collections.emptyList();
        }

        List<GovTicketV2GroupVO> result = new ArrayList<>();
        for (DmTicketDbStmtDO g : stmts) {
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
}
