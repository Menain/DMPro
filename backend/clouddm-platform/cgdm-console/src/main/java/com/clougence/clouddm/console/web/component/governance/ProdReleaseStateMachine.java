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

import com.clougence.clouddm.platform.dal.access.ProdReleaseDal;
import com.clougence.clouddm.platform.dal.model.prodrelease.ProdReleaseStatus;

import jakarta.annotation.Resource;

/**
 * Single-point production-release state machine — all status writes go through here.
 * <p>
 * Transition table (design §2):
 * - Main trunk: APPROVING -> APPROVED -> EXECUTING -> DONE
 * - PARTIAL_FAILED: some stmts failed; one revival edge PARTIAL_FAILED -> EXECUTING (retry)
 * - REJECTED: only from APPROVING/APPROVED (pre-execution)
 * - CANCELLED: only from APPROVING/APPROVED (pre-execution)
 * - Terminal states DONE/REJECTED/CANCELLED have no outgoing edges
 * <p>
 * Note: CREATED is a transient in-memory state; the release row is inserted
 * directly as APPROVING (after createProcess). No CREATED in the DB.
 */
@Service
public class ProdReleaseStateMachine {

    @Resource
    private ProdReleaseDal prodReleaseDal;

    /**
     * Attempt a state transition with expected-source-set guard.
     * @return true if transition succeeded (1 row affected), false if illegal/concurrent (0 rows).
     */
    public boolean transit(long releaseId, Set<ProdReleaseStatus> expectedFrom, ProdReleaseStatus to) {
        List<String> fromNames = expectedFrom.stream().map(ProdReleaseStatus::name).toList();
        int affected = prodReleaseDal.releaseMapper().transitStatus(releaseId, to.name(), fromNames);
        return affected > 0;
    }

    /**
     * Convenience: single expected-from state.
     */
    public boolean transit(long releaseId, ProdReleaseStatus expectedFrom, ProdReleaseStatus to) {
        return transit(releaseId, Set.of(expectedFrom), to);
    }

    /**
     * Compute the legal expected-from set for a given target status.
     * Only legal transitions are allowed; illegal ones return empty set -> transit always fails.
     */
    public Set<ProdReleaseStatus> legalFromFor(ProdReleaseStatus to) {
        return LEGAL_TRANSITIONS.getOrDefault(to, Set.of());
    }

    // ------- legal transition table (design §2) -------

    private static final Map<ProdReleaseStatus, Set<ProdReleaseStatus>> LEGAL_TRANSITIONS
        = new EnumMap<>(ProdReleaseStatus.class);

    static {
        LEGAL_TRANSITIONS.put(ProdReleaseStatus.APPROVING, Set.of());
        LEGAL_TRANSITIONS.put(ProdReleaseStatus.APPROVED, Set.of(
            ProdReleaseStatus.APPROVING));
        LEGAL_TRANSITIONS.put(ProdReleaseStatus.EXECUTING, Set.of(
            ProdReleaseStatus.APPROVED,
            ProdReleaseStatus.PARTIAL_FAILED));  // revival edge
        LEGAL_TRANSITIONS.put(ProdReleaseStatus.DONE, Set.of(
            ProdReleaseStatus.EXECUTING));
        LEGAL_TRANSITIONS.put(ProdReleaseStatus.PARTIAL_FAILED, Set.of(
            ProdReleaseStatus.EXECUTING));
        LEGAL_TRANSITIONS.put(ProdReleaseStatus.REJECTED, Set.of(
            ProdReleaseStatus.APPROVING,
            ProdReleaseStatus.APPROVED));
        LEGAL_TRANSITIONS.put(ProdReleaseStatus.CANCELLED, Set.of(
            ProdReleaseStatus.APPROVING,
            ProdReleaseStatus.APPROVED));
    }
}
