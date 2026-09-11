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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.model.fo.prodrelease.GovLedgerDetailFO;
import com.clougence.clouddm.console.web.model.fo.prodrelease.GovLedgerListByDbFO;
import com.clougence.clouddm.console.web.model.vo.dbpair.DbPairVO;
import com.clougence.clouddm.console.web.model.vo.govticket.GovTicketV2GroupVO;
import com.clougence.clouddm.console.web.model.vo.prodrelease.GovLedgerTicketVO;
import com.clougence.clouddm.console.web.service.dbpair.DbPairService;
import com.clougence.clouddm.console.web.service.governance.GovLedgerService;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.ProdReleaseDal;
import com.clougence.clouddm.platform.dal.access.TicketDbStmtDal;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseStmtDO;
import com.clougence.utils.JsonUtils;
import com.clougence.utils.format.DateFormatType;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * Ledger service — read-only, no primary_uid filtering (D6: all logged-in users can see).
 * Coexists with P2's groupList (which does tenant-filter) — independent query paths.
 */
@Slf4j
@Service
public class GovLedgerServiceImpl implements GovLedgerService {

    private static final String TICKET_TYPE_PRE_DDL = "PRE_DDL";
    private static final String EXEC_STATUS_SUCCESS  = "SUCCESS";

    @Resource
    private DbPairService      dbPairService;
    @Resource
    private TicketDbStmtDal    ticketDbStmtDal;
    @Resource
    private ApprovalDal         approvalDal;
    @Resource
    private ProdReleaseDal     prodReleaseDal;

    @Override
    public List<DbPairVO> dbs(String puid) {
        // Reuse availablePairs with side="PRE" (pre-side dimension)
        return dbPairService.availablePairs(puid, "PRE");
    }

    @Override
    public List<GovLedgerTicketVO> listByDb(String puid, GovLedgerListByDbFO fo) {
        // Query all stmt groups for this (dsId, dbName) — no tenant filter
        List<DmTicketDbStmtDO> stmts = ticketDbStmtDal.stmtMapper().queryByDsAndDb(fo.getDsId(), fo.getDbName());
        if (stmts == null || stmts.isEmpty()) {
            return Collections.emptyList();
        }

        // Group by ticketId
        Map<Long, List<DmTicketDbStmtDO>> byTicket = new LinkedHashMap<>();
        for (DmTicketDbStmtDO s : stmts) {
            byTicket.computeIfAbsent(s.getTicketId(), k -> new ArrayList<>()).add(s);
        }

        List<GovLedgerTicketVO> result = new ArrayList<>();
        for (Map.Entry<Long, List<DmTicketDbStmtDO>> entry : byTicket.entrySet()) {
            long ticketId = entry.getKey();
            List<DmTicketDbStmtDO> ticketStmts = entry.getValue();

            // Load ticket
            DmApprovalDO ticket = approvalDal.approvalMapper().queryById(ticketId);
            if (ticket == null) continue;

            // Condition 1: ticket FINISHED
            if (ticket.getTicketStatus() != ApprovalStatus.FINISHED) continue;

            // Condition 2: ticketType = PRE_DDL
            ApprovalMO mo = JsonUtils.toObj(ticket.getTicketInfo(), ApprovalMO.class);
            if (mo == null || !TICKET_TYPE_PRE_DDL.equals(mo.getTicketType())) continue;

            // Condition 3: all stmts SUCCESS for this (dsId, dbName)
            boolean allSuccess = ticketStmts.stream().allMatch(s -> EXEC_STATUS_SUCCESS.equals(s.getExecStatus()));
            if (!allSuccess) continue;

            // Promoted check: any stmt already in dm_prod_release_stmt
            DmProdReleaseStmtDO releaseStmt = prodReleaseDal.stmtMapper().queryBySourceStmtId(ticketStmts.get(0).getId());
            boolean promoted = releaseStmt != null;

            GovLedgerTicketVO vo = new GovLedgerTicketVO();
            vo.setTicketId(ticketId);
            vo.setTicketTitle(ticket.getTicketTitle());
            vo.setOwnerUid(ticket.getOwnerUid());
            vo.setGmtCreate(DateFormatType.s_yyyyMMdd_HHmmss.format(ticket.getGmtCreate()));
            vo.setExecStatus(EXEC_STATUS_SUCCESS);
            // SQL summary: first 200 chars of first stmt's sql_content
            String sqlContent = ticketStmts.get(0).getSqlContent();
            if (sqlContent != null) {
                vo.setSqlSummary(sqlContent.length() > 200 ? sqlContent.substring(0, 200) : sqlContent);
            }
            vo.setPromoted(promoted);
            if (promoted && releaseStmt != null) {
                // Find release for status/no
                var release = prodReleaseDal.releaseMapper().queryById(releaseStmt.getReleaseId());
                if (release != null) {
                    vo.setReleaseNo(release.getReleaseNo());
                    vo.setReleaseStatus(release.getStatus());
                }
            }

            result.add(vo);
        }

        return result;
    }

    @Override
    public List<GovTicketV2GroupVO> detail(String puid, GovLedgerDetailFO fo) {
        // Read-only: return statement groups for this (ticketId, dsId, dbName)
        List<DmTicketDbStmtDO> stmts = ticketDbStmtDal.stmtMapper().queryByTicketIdAndDsAndDb(
            fo.getTicketId(), fo.getDsId(), fo.getDbName());
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
