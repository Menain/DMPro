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

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.clougence.clouddm.platform.dal.access.DbChangeGovernDal;
import com.clougence.clouddm.platform.dal.model.dbchange.PromotionStatus;

import jakarta.annotation.Resource;

/**
 * Single-point promotion state machine — all status writes go through here.
 * <p>
 * Transition table (design D2):
 * - Main trunk: CREATED -> APPROVING -> APPROVED -> CONFIRMED -> EXECUTING -> SUCCEEDED
 * - Branches to terminal: REJECTED, CANCELLED, FAILED
 * - FAILED has exactly one revival edge: FAILED -> EXECUTING (ticket EXEC_FAIL retry recovery)
 * - CLOSED/CANCELED ticket -> CANCELLED only if promotion is not already FAILED
 * - Terminal states SUCCEEDED/REJECTED/CANCELLED have no outgoing edges
 */
@Service
public class PromotionStateMachine {

    @Resource
    private DbChangeGovernDal dbChangeGovernDal;

    /**
     * Attempt a state transition with expected-source-set guard.
     * @return true if transition succeeded (1 row affected), false if illegal/concurrent (0 rows).
     */
    public boolean transit(long promotionId, Set<PromotionStatus> expectedFrom, PromotionStatus to) {
        List<String> fromNames = expectedFrom.stream().map(PromotionStatus::name).toList();
        int affected = dbChangeGovernDal.promotionMapper().transitStatus(promotionId, to.name(), fromNames);
        return affected > 0;
    }

    /**
     * Map a ticket status to a promotion target status (design D2 mapping table).
     * @return target status, or null if the ticket status has no promotion-side effect.
     */
    public PromotionStatus mapTicketStatus(String ticketStatusName, PromotionStatus currentPromoStatus) {
        return switch (ticketStatusName) {
            case "PRE_INIT_WAIT", "PRE_INIT_RUN" -> PromotionStatus.CREATED;
            case "WAIT_APPROVAL" -> PromotionStatus.APPROVING;
            case "WAIT_CONFIRM" -> PromotionStatus.APPROVED;
            case "WAIT_EXEC" -> PromotionStatus.CONFIRMED;
            case "RUNNING", "EXEC_PAUSE" -> PromotionStatus.EXECUTING;
            case "FINISHED" -> PromotionStatus.SUCCEEDED;
            case "EXEC_FAIL", "FAILED" -> PromotionStatus.FAILED;
            case "REJECTED" -> PromotionStatus.REJECTED;
            case "CLOSED", "CANCELED" -> {
                // FAILED takes priority — a failed promotion that's then closed stays FAILED
                if (currentPromoStatus == PromotionStatus.FAILED) {
                    yield null;
                }
                yield PromotionStatus.CANCELLED;
            }
            default -> null;
        };
    }

    /**
     * Compute the legal expected-from set for a given target status.
     * Only legal transitions are allowed; illegal ones return empty set -> transit always fails.
     */
    public Set<PromotionStatus> legalFromFor(PromotionStatus to) {
        return LEGAL_TRANSITIONS.getOrDefault(to, Set.of());
    }

    // ------- legal transition table (design D2) -------

    private static final Map<PromotionStatus, Set<PromotionStatus>> LEGAL_TRANSITIONS = new EnumMap<>(PromotionStatus.class);

    static {
        LEGAL_TRANSITIONS.put(PromotionStatus.CREATED, Set.of());
        LEGAL_TRANSITIONS.put(PromotionStatus.APPROVING, Set.of(
            PromotionStatus.CREATED));
        LEGAL_TRANSITIONS.put(PromotionStatus.APPROVED, Set.of(
            PromotionStatus.APPROVING));
        LEGAL_TRANSITIONS.put(PromotionStatus.CONFIRMED, Set.of(
            PromotionStatus.APPROVED));
        LEGAL_TRANSITIONS.put(PromotionStatus.EXECUTING, Set.of(
            PromotionStatus.CONFIRMED,
            PromotionStatus.FAILED));  // revival edge
        LEGAL_TRANSITIONS.put(PromotionStatus.SUCCEEDED, Set.of(
            PromotionStatus.EXECUTING));
        LEGAL_TRANSITIONS.put(PromotionStatus.REJECTED, Set.of(
            PromotionStatus.CREATED,
            PromotionStatus.APPROVING,
            PromotionStatus.APPROVED));
        LEGAL_TRANSITIONS.put(PromotionStatus.CANCELLED, Set.of(
            PromotionStatus.CREATED,
            PromotionStatus.APPROVING,
            PromotionStatus.APPROVED,
            PromotionStatus.CONFIRMED,
            PromotionStatus.EXECUTING));
        LEGAL_TRANSITIONS.put(PromotionStatus.FAILED, Set.of(
            PromotionStatus.CREATED,
            PromotionStatus.APPROVING,
            PromotionStatus.APPROVED,
            PromotionStatus.CONFIRMED,
            PromotionStatus.EXECUTING));
    }
}
