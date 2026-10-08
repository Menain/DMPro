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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * B7 N3 发布链披露 read face (design §10/T18): {@link GhostRouteMarks} answers the two release
 * merge-surface questions independently — "did this group ever carry a GHOST route?" and (new)
 * "did this group ever carry a DML_BATCH route?" — over the union of both mark carriers (segment
 * PLAN ∨ display marks). The N3 precision pin: a DML_BATCH-only plan sets ONLY its own key (the
 * N1b-widened {@code isSegmentedPlan} must NOT leak into the gh-ost-specific ghost read face —
 * the release hint copy is per-family), and every shape degrades never-throw.
 */
public class GhostRouteMarksTest {

    private static final String GHOST_PLAN =
        "{\"checkStatus\":\"PASS\",\"changeType\":\"DDL\",\"segments\":["
            + "{\"segIdx\":1,\"type\":\"GHOST\",\"stmtIndex\":1,\"table\":\"t_big\","
            + "\"sql\":\"ALTER TABLE t_big ADD c INT\"}]}";

    private static final String DML_BATCH_PLAN =
        "{\"checkStatus\":\"PASS\",\"changeType\":\"DML\",\"segments\":["
            + "{\"segIdx\":1,\"type\":\"DML_BATCH\",\"stmtIndex\":1,\"table\":\"t_order\","
            + "\"estimatedRows\":5000000,\"idempotent\":true,"
            + "\"sql\":\"DELETE FROM t_order WHERE status = 0\","
            + "\"batchKey\":{\"column\":\"id\",\"keyType\":\"INT\"}}]}";

    private static final String MIXED_PLAN =
        "{\"checkStatus\":\"PASS\",\"changeType\":\"MIXED\",\"segments\":["
            + "{\"segIdx\":1,\"type\":\"AUTOEXEC\",\"stmtRange\":[1,1]},"
            + "{\"segIdx\":2,\"type\":\"DML_BATCH\",\"stmtIndex\":2,\"table\":\"t_order\","
            + "\"sql\":\"UPDATE t_order SET a = 1 WHERE b = 0\","
            + "\"batchKey\":{\"column\":\"id\",\"keyType\":\"INT\"}},"
            + "{\"segIdx\":3,\"type\":\"GHOST\",\"stmtIndex\":3,\"table\":\"t_big\","
            + "\"sql\":\"ALTER TABLE t_big ADD c INT\"}]}";

    private static final String AUTOEXEC_ONLY_NO_PLAN_SHAPE =
        "{\"checkStatus\":\"PASS\",\"changeType\":\"DML\"}";

    private static final String DML_BATCH_DISPLAY_ONLY =
        "{\"checkStatus\":\"PASS\",\"changeType\":\"DML\",\"dmlBatchRoutes\":["
            + "{\"stmtIndex\":1,\"route\":\"DML_BATCH\",\"tableName\":\"t_order\"}]}";

    private static final String DML_BATCH_DISPLAY_NON_ROUTE =
        "{\"checkStatus\":\"PASS\",\"changeType\":\"DML\",\"dmlBatchRoutes\":["
            + "{\"stmtIndex\":1,\"route\":\"DIRECT_KEY_UNSUPPORTED\",\"tableName\":\"t_order\"},"
            + "{\"stmtIndex\":2,\"route\":\"SAME_TABLE_ACCUMULATE\",\"tableName\":\"t_x\"}]}";

    private static final String GHOST_M1_DISPLAY_ONLY =
        "{\"checkStatus\":\"PASS\",\"changeType\":\"DDL\",\"ghostRoutes\":["
            + "{\"stmtIndex\":1,\"route\":\"GHOST\",\"tableName\":\"t_big\"}]}";

    // ==================== hasDmlBatchRoute (the N3 twin) ====================

    @Test
    public void dmlBatchPlan_detected() {
        assertTrue(GhostRouteMarks.hasDmlBatchRoute(DML_BATCH_PLAN));
    }

    @Test
    public void mixedPlan_detected() {
        assertTrue(GhostRouteMarks.hasDmlBatchRoute(MIXED_PLAN));
    }

    @Test
    public void displayCarrierOnly_detected_routeValueIsTheKey() {
        // the display-mark arm (union-of-carriers symmetry with the ghost read face)
        assertTrue(GhostRouteMarks.hasDmlBatchRoute(DML_BATCH_DISPLAY_ONLY));
    }

    @Test
    public void nonRoutedDisplayMarks_notDetected() {
        // DIRECT_* / accumulate display lines are NOT a batch route (only route==DML_BATCH counts)
        assertFalse(GhostRouteMarks.hasDmlBatchRoute(DML_BATCH_DISPLAY_NON_ROUTE));
    }

    @Test
    public void ghostPlanOnly_notDetected() {
        assertFalse(GhostRouteMarks.hasDmlBatchRoute(GHOST_PLAN));
    }

    @Test
    public void plainOrAbsentOrCorrupt_neverThrows_false() {
        assertFalse(GhostRouteMarks.hasDmlBatchRoute(AUTOEXEC_ONLY_NO_PLAN_SHAPE));
        assertFalse(GhostRouteMarks.hasDmlBatchRoute(null));
        assertFalse(GhostRouteMarks.hasDmlBatchRoute(""));
        assertFalse(GhostRouteMarks.hasDmlBatchRoute("{corrupt"));
        assertFalse(GhostRouteMarks.hasDmlBatchRoute("{\"segments\":\"not-a-list\"}"));
    }

    // ==================== hasGhostRoute precision (the N3 fix) ====================

    @Test
    public void ghostPlan_detected() {
        assertTrue(GhostRouteMarks.hasGhostRoute(GHOST_PLAN));
    }

    @Test
    public void mixedPlan_ghostArmDetected() {
        assertTrue(GhostRouteMarks.hasGhostRoute(MIXED_PLAN));
    }

    @Test
    public void m1DisplayCarrierOnly_detected() {
        assertTrue(GhostRouteMarks.hasGhostRoute(GHOST_M1_DISPLAY_ONLY));
    }

    @Test
    public void dmlBatchOnlyPlan_doesNotLeakIntoGhostReadFace() {
        // the precision pin: the gh-ost-specific release hint ("以直接 ALTER 方式执行") must NOT
        // fire for a batch-only source — its own dmlBatchRouted key carries that disclosure
        assertFalse(GhostRouteMarks.hasGhostRoute(DML_BATCH_PLAN));
        assertTrue(GhostRouteMarks.hasDmlBatchRoute(DML_BATCH_PLAN));
    }

    @Test
    public void plainOrCorrupt_ghostArm_false() {
        assertFalse(GhostRouteMarks.hasGhostRoute(AUTOEXEC_ONLY_NO_PLAN_SHAPE));
        assertFalse(GhostRouteMarks.hasGhostRoute(null));
        assertFalse(GhostRouteMarks.hasGhostRoute("{corrupt"));
    }
}
