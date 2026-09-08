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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.clougence.clouddm.console.web.component.governance.PromotionStateMachine;
import com.clougence.clouddm.console.web.service.governance.GovPromotionSyncService;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.DbChangeGovernDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangePromotionDO;
import com.clougence.clouddm.platform.dal.model.dbchange.GovEventType;
import com.clougence.clouddm.platform.dal.model.dbchange.PromotionStatus;
import com.clougence.utils.JsonUtils;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class GovPromotionSyncServiceImpl implements GovPromotionSyncService {

    private static final String SYSTEM_OPERATOR = "SYSTEM";

    @Resource
    private DbChangeGovernDal       dbChangeGovernDal;
    @Resource
    private ApprovalDal             approvalDal;
    @Resource
    private PromotionStateMachine  stateMachine;

    @Override
    public void syncPromotionStatus() {
        List<DmDbChangePromotionDO> promotions = dbChangeGovernDal.promotionMapper().listNonTerminal();
        for (DmDbChangePromotionDO promotion : promotions) {
            try {
                syncOnePromotion(promotion);
            } catch (Exception e) {
                log.error("[GovPromotionSync] error syncing promotion {}", promotion.getId(), e);
            }
        }
    }

    private void syncOnePromotion(DmDbChangePromotionDO promotion) {
        Long ticketId = promotion.getProdApprovalId();
        if (ticketId == null) {
            // Ticket not yet created — nothing to sync
            return;
        }

        DmApprovalMapper mapper = approvalDal.approvalMapper();
        DmApprovalDO ticket = mapper.queryById(ticketId);
        if (ticket == null) {
            return;
        }

        String ticketStatusName = ticket.getTicketStatus().name();
        PromotionStatus currentStatus = PromotionStatus.valueOf(promotion.getStatus());

        // Map ticket status → promotion target (design D2)
        PromotionStatus target = stateMachine.mapTicketStatus(ticketStatusName, currentStatus);
        if (target == null) {
            // No promotion-side effect (e.g. CLOSED ticket after FAILED promotion)
            return;
        }

        // Already at target — idempotent skip
        if (target == currentStatus) {
            return;
        }

        // Compute legal expected-from set for the target
        Set<PromotionStatus> expectedFrom = stateMachine.legalFromFor(target);
        if (expectedFrom.isEmpty()) {
            // Target has no legal incoming transitions
            return;
        }

        // Only transit if current status is in the expected-from set
        if (!expectedFrom.contains(currentStatus)) {
            // Illegal transition from current — skip silently (not an error, just not legal)
            return;
        }

        boolean success = stateMachine.transit(promotion.getId(), expectedFrom, target);
        if (success) {
            appendStatusSyncEvent(promotion.getId(), promotion.getRevisionId(),
                currentStatus.name(), target.name(), ticketStatusName);
        }
        // If not success (0 rows) = concurrent modification or status changed between read and write — idempotent skip
    }

    private void appendStatusSyncEvent(Long promotionId, Long revisionId,
                                       String fromStatus, String toStatus, String ticketStatus) {
        DmDbChangeEventDO event = new DmDbChangeEventDO();
        event.setPromotionId(promotionId);
        event.setRevisionId(revisionId);
        event.setEventType(GovEventType.STATUS_SYNC.name());
        event.setFromStatus(fromStatus);
        event.setToStatus(toStatus);
        event.setOperatorUid(SYSTEM_OPERATOR);

        Map<String, Object> data = new HashMap<>();
        data.put("ticketStatus", ticketStatus);
        event.setEventData(JsonUtils.toJson(data));

        dbChangeGovernDal.eventMapper().insert(event);
    }
}
