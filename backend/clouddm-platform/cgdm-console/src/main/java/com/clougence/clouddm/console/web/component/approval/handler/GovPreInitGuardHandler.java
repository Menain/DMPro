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
package com.clougence.clouddm.console.web.component.approval.handler;

import java.io.IOException;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.component.approval.model.PreInitContext;
import com.clougence.clouddm.console.web.component.governance.GovSqlHashUtils;
import com.clougence.clouddm.console.web.model.vo.logicaldb.LogicalDbTarget;
import com.clougence.clouddm.console.web.service.logicaldb.LogicalDbService;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.DbChangeGovernDal;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangePromotionDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeRevisionDO;
import com.clougence.clouddm.platform.dal.model.dbchange.PromotionStatus;
import com.clougence.clouddm.platform.dal.model.logicaldb.GovRole;
import com.clougence.utils.JsonUtils;
import com.clougence.utils.StringUtils;

import jakarta.annotation.Resource;

/**
 * Governance PROD pre-init guard handler (Phase 7, design D7).
 * <p>
 * Lightweight pre-approval fast-fail subset of gate-two:
 * - promotion exists and not in terminal-negative state
 * - whole-ticket hash matches revision.sql_hash
 * - binding matches promotion snapshot
 * <p>
 * Does NOT run Preflight (metadata checks stay at execution time to avoid long approval-period connections).
 * <p>
 * S6 iron rule: doHandle must throw ErrorMessageException (not call context.fail directly) —
 * handle() is final and calls context.finish() on normal return, which would overwrite FAILED.
 */
@Service
public class GovPreInitGuardHandler extends AbstractPreInitHandler {

    @Resource
    private DbChangeGovernDal dbChangeGovernDal;
    @Resource
    private ApprovalDal       approvalDal;
    @Resource
    private LogicalDbService logicalDbService;

    @Override
    protected String analysisType() {
        return "GOV_GATE_PRE_CHECK";
    }

    @Override
    public int displayOrder() {
        return 0;
    }

    @Override
    public boolean supports(DmApprovalDO approval) {
        if (approval.getApproBiz() != ApprovalBiz.DM_CHANGE) {
            return false;
        }
        ApprovalMO mo = parseTicketInfo(approval.getTicketInfo());
        return mo != null && "PROD".equals(mo.getGovRole());
    }

    @Override
    protected void doHandle(PreInitContext context) throws IOException {
        DmApprovalDO ticket = context.getApproval();
        ApprovalMO mo = parseTicketInfo(ticket.getTicketInfo());
        if (mo == null || mo.getPromotionId() == null || mo.getRevisionId() == null) {
            throw new ErrorMessageException("Governance PROD ticket missing promotionId/revisionId in ticketInfo");
        }

        // 1. promotion exists and not in terminal-negative state
        DmDbChangePromotionDO promotion = this.dbChangeGovernDal.promotionMapper().selectById(mo.getPromotionId());
        if (promotion == null) {
            throw new ErrorMessageException("Governance promotion not found: " + mo.getPromotionId());
        }
        String promoStatus = promotion.getStatus();
        if (PromotionStatus.REJECTED.name().equals(promoStatus)
            || PromotionStatus.CANCELLED.name().equals(promoStatus)
            || PromotionStatus.FAILED.name().equals(promoStatus)) {
            throw new ErrorMessageException("Governance promotion is in terminal-negative state: " + promoStatus);
        }

        // 2. whole-ticket hash matches revision.sql_hash
        DmDbChangeRevisionDO revision = this.dbChangeGovernDal.revisionMapper().selectById(mo.getRevisionId());
        if (revision == null) {
            throw new ErrorMessageException("Governance revision not found: " + mo.getRevisionId());
        }
        // Re-fetch the full ticket (the context's approval may be a partial DO from queryById)
        DmApprovalDO fullTicket = this.approvalDal.approvalMapper().queryByBizId(ticket.getBizId());
        String rawSql = fullTicket != null ? fullTicket.getRawSql() : ticket.getRawSql();
        String recomputedHash = GovSqlHashUtils.hash(rawSql);
        if (!recomputedHash.equals(revision.getSqlHash())) {
            throw new ErrorMessageException("Governance whole-ticket hash mismatch — ticket rawSql has been modified since promotion");
        }

        // 3. binding matches promotion snapshot
        try {
            LogicalDbTarget target = this.logicalDbService.getBinding(ticket.getPrimaryUid(), mo.getLogicalDbId(), GovRole.PROD);
            if (!Objects.equals(target.getEnvId(), promotion.getProdEnvId())
                || !Objects.equals(target.getDsId(), promotion.getProdDsId())
                || !Objects.equals(target.getResPath(), promotion.getProdResPath())) {
                throw new ErrorMessageException("Governance binding changed since promotion snapshot");
            }
        } catch (ErrorMessageException e) {
            throw new ErrorMessageException("Governance binding verification failed: " + e.getMessage());
        }
    }

    private static ApprovalMO parseTicketInfo(String ticketInfo) {
        if (StringUtils.isEmpty(ticketInfo)) {
            return null;
        }
        return JsonUtils.toObj(ticketInfo, ApprovalMO.class);
    }
}
