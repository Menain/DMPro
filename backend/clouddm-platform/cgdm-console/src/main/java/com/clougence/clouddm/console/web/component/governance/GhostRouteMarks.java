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

import java.util.List;
import java.util.Map;

import com.clougence.utils.JsonUtils;

/**
 * B6 M3 (发布链过渡明示, design §6.5): read-side helper answering "did THIS group row ever carry
 * a lock-free (gh-ost) route?" from its persisted {@code precheck_result} JSON — the union of
 * both marks' carriers:
 * <ul>
 * <li>M2+ segment PLAN: {@code segments[]} with ≥1 GHOST segment ({@link TicketSegment#isSegmentedPlan});</li>
 * <li>M1 display marks: {@code ghostRoutes[]} with {@code route == "GHOST"} (the flags-only
 *     degenerate shape of existing single-ALTER tickets).</li>
 * </ul>
 * Consumers are the release merge surface (gate_result additive hint + ledger row flag): the
 * frozen release snapshot replays the whole group text through the DIRECT chain (PROD ghost
 * routing is M5), so a ghost-routed source means "this text ran lock-free on pre but will run as
 * a plain ALTER on prod" — the product must say so explicitly. Never-throw (a corrupt carrier
 * degrades to false — the hint is display-only).
 */
public final class GhostRouteMarks {

    private static final String ROUTE_GHOST = "GHOST";
    /** B7 N3: the DmlBatchRouteVO.ROUTE_DML_BATCH wire value (the display-mark carrier's key). */
    private static final String ROUTE_DML_BATCH = "DML_BATCH";

    private GhostRouteMarks() {
    }

    /**
     * true when the group's precheck_result carries either ghost-route mark shape.
     * <p>
     * B7 N3 precision fix: the plan arm keys on {@code hasGhostSegment}, NOT the N1b-widened
     * {@code isSegmentedPlan} (GHOST ∨ DML_BATCH) — the release/ledger hint copy is
     * gh-ost-specific ("以直接 ALTER 方式执行"), and a DML_BATCH-only source must set ONLY its own
     * {@link #hasDmlBatchRoute} key, never this one (both keys ride the same additive snapshot;
     * the B6 ghost-source pins carry a GHOST segment and stay green on either predicate).
     */
    public static boolean hasGhostRoute(String precheckResultJson) {
        if (TicketSegment.hasGhostSegment(TicketSegment.parsePlan(precheckResultJson))) {
            return true;
        }
        try {
            Map<?, ?> precheck = JsonUtils.toObj(precheckResultJson, Map.class);
            Object routes = precheck == null ? null : precheck.get("ghostRoutes");
            if (routes instanceof List) {
                for (Object one : (List<?>) routes) {
                    if (one instanceof Map && ROUTE_GHOST.equals(((Map<?, ?>) one).get("route"))) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            // JsonUtils sneaky-throws the CHECKED JsonProcessingException — display-only, degrade
        }
        return false;
    }

    /**
     * B7 N3 (发布链披露, design §10/T18 — the hasGhostRoute twin): true when the group's
     * precheck_result carries a DML_BATCH route mark — the segment PLAN with ≥1 DML_BATCH segment
     * (the execution-time authority; a DML_BATCH mark ALWAYS writes a plan) ∨ the display carrier
     * {@code dmlBatchRoutes[]} with {@code route == "DML_BATCH"} (union-of-carriers symmetry with
     * the ghost read face). Consumers are the release merge surface (gate_result additive hint +
     * ledger row flag): the frozen release snapshot replays the whole group text through the
     * DIRECT chain (release-chain batch routing is v2, the B6 M5 cadence), so a batch-routed
     * source means "this text ran in throttled batches on pre but will run as ONE direct
     * statement on prod" — the product must say so explicitly. Never-throw (display-only).
     */
    public static boolean hasDmlBatchRoute(String precheckResultJson) {
        if (TicketSegment.hasDmlBatchSegment(TicketSegment.parsePlan(precheckResultJson))) {
            return true;
        }
        try {
            Map<?, ?> precheck = JsonUtils.toObj(precheckResultJson, Map.class);
            Object routes = precheck == null ? null : precheck.get("dmlBatchRoutes");
            if (routes instanceof List) {
                for (Object one : (List<?>) routes) {
                    if (one instanceof Map && ROUTE_DML_BATCH.equals(((Map<?, ?>) one).get("route"))) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            // JsonUtils sneaky-throws the CHECKED JsonProcessingException — display-only, degrade
        }
        return false;
    }
}
