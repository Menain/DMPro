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
package com.clougence.clouddm.init.component.scripts;

import java.util.List;

import com.clougence.clouddm.init.component.flyway.AbstractUpgradeJavaMigration;

/**
 * Explicit approval round (ADR-012 Decision 3, task 10-11-approval-round-binding): one
 * additive int column on dm_approval turning the formerly IMPLICIT round (resubmit rebuilds
 * the approval row + clears identity/outbound columns) into a persisted token.
 * <p>
 * Lifecycle: rows are born at round 1 (the DEFAULT — existing rows backfill to "first round",
 * compatible with the pre-round history); a release resubmit's clearOutboundForNewRound
 * increments approval_round while resetting appro_identity + the four outbound columns
 * (round-increment semantics). The outbound flows/create context carries the round additively
 * and an inbound command may carry externalRound — present-and-mismatched rejects
 * (ROUND_MISMATCH); absent skips (tolerant-first: the frozen §5.4 "ticketId-only minimal
 * call" contract forbids rejecting a missing round by default).
 * <p>
 * Pure addition — no existing tables or columns are touched; safeExecute ignores error code
 * 1060 (duplicate column) so the migration is rerun-idempotent. Rollback = ALTER TABLE
 * dm_approval DROP COLUMN approval_round (transparent to older binaries — the column is never
 * read by pre-round code). Version note: V202610110001 is taken by the C4 schema-gate
 * override table; this task takes the next free slot (scripts-directory check 2026-10-11).
 */
public class V202610110002__dm_approval_round extends AbstractUpgradeJavaMigration {

    @Override
    public List<String> collectScript() {
        return List.of("""
                ALTER TABLE dm_approval ADD COLUMN approval_round int NOT NULL DEFAULT 1
                """);
    }
}
