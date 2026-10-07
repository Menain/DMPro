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

import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;

/**
 * B6 M1: reader helpers for the {@code ApprovalMO.ghostRouteFlags} execution-time authority.
 * <p>
 * <b>B7 SEMANTIC RENAMING (裁决 #12, task 09-17-lockfree-dml)</b>: the flag's meaning WIDENED
 * from "this group is ghost-routed" to <b>"this group HAS NON-DIRECT-EXECUTION SEGMENTS"</b>
 * (GHOST ∨ DML_BATCH). The field name and the wire shape are deliberately UNCHANGED — in-flight
 * tickets keep their semantics and every reader (the sweep pre-filter, the groupList projection,
 * the retryGroup flags ladder) works on the widened meaning without code change. Dual-write
 * coupling: ANY path that writes a segments plan/runtime with a routed segment MUST set the
 * flag in the same breath (R2 — a flagless DML_BATCH-only ticket would silently lose its
 * WAIT_EXEC sweep repair).
 * <p>
 * POSITIONAL CONTRACT: flag index i ↔ the i-th group of the ticket, where "i-th" means the
 * {@code dm_ticket_db_stmt} ORDER BY id ASC order (pinned in the mapper XML) = the submit
 * insertion order = the {@code ApprovalMO.pairIds} order (itself positional). Groups are never
 * inserted/deleted after submit (resubmit updates in place, stmt id unchanged), so the alignment
 * is stable for the ticket lifetime. null / shorter list / null entry = NOT routed (legacy
 * tickets and every ticket submitted with both auto-route switches off keep the field absent).
 */
public final class GhostRouteFlags {

    private GhostRouteFlags() {
    }

    /**
     * true when the group at {@code position} (id-ASC index) is marked for a NON-DIRECT route
     * (gh-ost migration ∨ DML batch loop — the B7 widened semantics).
     */
    public static boolean isRouted(ApprovalMO mo, int position) {
        if (mo == null || position < 0) {
            return false;
        }
        List<Boolean> flags = mo.getGhostRouteFlags();
        return flags != null && position < flags.size() && Boolean.TRUE.equals(flags.get(position));
    }

    /** true when ANY group of the ticket is ghost-routed (cheap sweep pre-filter). */
    public static boolean anyRouted(ApprovalMO mo) {
        if (mo == null || mo.getGhostRouteFlags() == null) {
            return false;
        }
        return mo.getGhostRouteFlags().stream().anyMatch(Boolean.TRUE::equals);
    }
}
