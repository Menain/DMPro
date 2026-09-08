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
package com.clougence.clouddm.console.web.model.fo.governance;

import static org.junit.Assert.*;

import org.junit.Test;

import com.clougence.utils.JsonUtils;

/**
 * Phase 11 Wave A / A2: FO field whitelist (smuggling) rejection tests.
 * <p>
 * Every governance FO that uses {@code @JsonAnySetter} must reject unknown properties
 * at deserialization time. This is the defense-in-depth against smuggled {@code sql},
 * {@code dsId}, {@code envId}, and {@code approvalStatus} fields (spec §6.4).
 * <p>
 * The existing GateOneTest#promote_smuggleSql_rejectedAtDeserialization covers one
 * FO (GovPromoteFO) with one field ({@code sql}). This class fills the matrix gaps:
 * all three governance FOs x four smuggle fields each.
 */
public class GovFoSmugglingTest {

    // ======= GovPromoteFO =======

    @Test
    public void promoteFO_smuggleSql_rejected() {
        assertSmuggleRejected("{\"revisionId\":300,\"sql\":\"DELETE FROM users\"}", GovPromoteFO.class);
    }

    @Test
    public void promoteFO_smuggleDsId_rejected() {
        assertSmuggleRejected("{\"revisionId\":300,\"dsId\":999}", GovPromoteFO.class);
    }

    @Test
    public void promoteFO_smuggleEnvId_rejected() {
        assertSmuggleRejected("{\"revisionId\":300,\"envId\":5}", GovPromoteFO.class);
    }

    @Test
    public void promoteFO_smuggleApprovalStatus_rejected() {
        assertSmuggleRejected("{\"revisionId\":300,\"approvalStatus\":\"APPROVED\"}", GovPromoteFO.class);
    }

    // ======= GovPreSubmitFO =======

    @Test
    public void preSubmitFO_smuggleDsId_rejected() {
        String json = "{\"logicalDbId\":10,\"ticketTitle\":\"t\",\"sql\":\"SELECT 1\",\"dsId\":999}";
        assertSmuggleRejected(json, GovPreSubmitFO.class);
    }

    @Test
    public void preSubmitFO_smuggleEnvId_rejected() {
        String json = "{\"logicalDbId\":10,\"ticketTitle\":\"t\",\"sql\":\"SELECT 1\",\"envId\":5}";
        assertSmuggleRejected(json, GovPreSubmitFO.class);
    }

    @Test
    public void preSubmitFO_smuggleApprovalStatus_rejected() {
        String json = "{\"logicalDbId\":10,\"ticketTitle\":\"t\",\"sql\":\"SELECT 1\",\"approvalStatus\":\"APPROVED\"}";
        assertSmuggleRejected(json, GovPreSubmitFO.class);
    }

    // ======= GovDirectDmlSubmitFO =======

    @Test
    public void directDmlSubmitFO_smuggleDsId_rejected() {
        String json = "{\"logicalDbId\":10,\"sql\":\"UPDATE t SET v=1\",\"rollbackSql\":\"UPDATE t SET v=0\",\"dsId\":999}";
        assertSmuggleRejected(json, GovDirectDmlSubmitFO.class);
    }

    @Test
    public void directDmlSubmitFO_smuggleEnvId_rejected() {
        String json = "{\"logicalDbId\":10,\"sql\":\"UPDATE t SET v=1\",\"rollbackSql\":\"UPDATE t SET v=0\",\"envId\":5}";
        assertSmuggleRejected(json, GovDirectDmlSubmitFO.class);
    }

    @Test
    public void directDmlSubmitFO_smuggleApprovalStatus_rejected() {
        String json = "{\"logicalDbId\":10,\"sql\":\"UPDATE t SET v=1\",\"rollbackSql\":\"UPDATE t SET v=0\",\"approvalStatus\":\"APPROVED\"}";
        assertSmuggleRejected(json, GovDirectDmlSubmitFO.class);
    }

    // ======= GovCorrectStatementFO: no @JsonAnySetter, extra fields silently ignored =======

    @Test
    public void correctStatementFO_smuggleApprovalStatus_ignoredNotRejected() {
        // GovCorrectStatementFO does NOT have @JsonAnySetter — extra fields are silently
        // ignored by Jackson (default behavior). This is acceptable because the FO has no
        // security-sensitive fields that could influence the correction outcome. The correction
        // service reads ticketId/stmtIndex/newSql from the FO and derives everything else
        // from server-side DB state. Pinning this behavior so a future @JsonAnySetter addition
        // would require updating this test.
        String json = "{\"ticketId\":200,\"stmtIndex\":1,\"newSql\":\"SELECT 1\",\"reason\":\"fix\",\"approvalStatus\":\"APPROVED\"}";
        GovCorrectStatementFO fo = JsonUtils.toObj(json, GovCorrectStatementFO.class);
        assertEquals(Long.valueOf(200L), fo.getTicketId());
        assertEquals(Integer.valueOf(1), fo.getStmtIndex());
        assertEquals("SELECT 1", fo.getNewSql());
        // approvalStatus is silently dropped — no field to read it from
    }

    // ======= helper =======

    private void assertSmuggleRejected(String json, Class<?> foClass) {
        try {
            JsonUtils.toObj(json, foClass);
            fail("Deserialization should have thrown for smuggled field in " + foClass.getSimpleName());
        } catch (Exception e) {
            // Expected: IllegalArgumentException from @JsonAnySetter
        }
    }
}
