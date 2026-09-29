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

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.utils.JsonUtils;

/**
 * B6 M1: the positional ghostRouteFlags contract (GhostRouteFlags) — legacy tolerance in every
 * shape (null mo / null flags / shorter list / null entry / out-of-range position) so unmarked
 * and pre-M1 tickets NEVER route to gh-ost.
 */
public class GhostRouteFlagsTest {

    @Test
    public void nullMoOrFlags_neverRouted() {
        assertFalse(GhostRouteFlags.isRouted(null, 0));
        assertFalse(GhostRouteFlags.isRouted(new ApprovalMO(), 0));
        assertFalse(GhostRouteFlags.anyRouted(null));
        assertFalse(GhostRouteFlags.anyRouted(new ApprovalMO()));
    }

    @Test
    public void positionalLookup_exactIndexSemantics() {
        ApprovalMO mo = new ApprovalMO();
        mo.setGhostRouteFlags(Arrays.asList(false, true, null));

        assertFalse(GhostRouteFlags.isRouted(mo, 0));
        assertTrue(GhostRouteFlags.isRouted(mo, 1));
        assertFalse("a null entry is NOT routed", GhostRouteFlags.isRouted(mo, 2));
        assertFalse("out-of-range position is NOT routed", GhostRouteFlags.isRouted(mo, 3));
        assertFalse("negative position is NOT routed", GhostRouteFlags.isRouted(mo, -1));
        assertTrue(GhostRouteFlags.anyRouted(mo));
    }

    @Test
    public void allFalseOrEmpty_neverRouted() {
        ApprovalMO mo = new ApprovalMO();
        mo.setGhostRouteFlags(List.of(false, false));
        assertFalse(GhostRouteFlags.anyRouted(mo));

        mo.setGhostRouteFlags(List.of());
        assertFalse(GhostRouteFlags.anyRouted(mo));
    }

    @Test
    public void survivesTicketInfoJsonRoundTrip() {
        // the flags ride inside the ticketInfo JSON — ignoreUnknown=true keeps legacy rows safe
        ApprovalMO mo = new ApprovalMO();
        mo.setTicketType("PRE_DDL");
        mo.setGhostRouteFlags(List.of(true, false));
        ApprovalMO parsed = JsonUtils.toObj(JsonUtils.toJson(mo), ApprovalMO.class);

        assertTrue(GhostRouteFlags.isRouted(parsed, 0));
        assertFalse(GhostRouteFlags.isRouted(parsed, 1));
    }

    @Test
    public void legacyTicketInfoWithoutTheField_parsesToNullFlags() {
        ApprovalMO parsed = JsonUtils.toObj("{\"ticketType\":\"PRE_DDL\",\"approvalMode\":\"DIRECT\"}", ApprovalMO.class);

        assertNotNull(parsed);
        assertNull(parsed.getGhostRouteFlags());
        assertFalse(GhostRouteFlags.anyRouted(parsed));
    }
}
