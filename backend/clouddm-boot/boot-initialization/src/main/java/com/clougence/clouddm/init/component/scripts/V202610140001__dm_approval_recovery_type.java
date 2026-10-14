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
 * C10 recovery-type grading (task 10-14-recovery-type-grading, design D1): one additive
 * nullable varchar column on dm_approval persisting the ticket-level RecoveryType grade
 * (enum name — IRREVERSIBLE / BATCH_CHECKPOINT / GHOST_OLD_TABLE / COMPENSATION_SQL /
 * TRANSACTION / BACKUP_TEMPLATE / UNSPECIFIED, strictest-first declaration order).
 * <p>
 * Lifecycle: written by the rollback-material write points (v2 submit / v2 resubmit D7
 * branches 1-2 / legacy create / CICD empty-material rows → UNSPECIFIED); the resubmit
 * "blank + original applicant-provided" branch deliberately does NOT touch the column
 * (material unchanged → grade unchanged). Stock rows stay NULL with NO backfill (the C8
 * audit-column discipline — old rows lack the precheck signals a reliable grading would
 * need); the detail read derives them at read time from the material-source flags
 * (deriveLegacyStepFinishTimes precedent, never written back).
 * <p>
 * Deliberately NO recovery_verified column: no verification/drill mechanism exists, so the
 * honesty flag is a VO read-time constant (red line 4 — no dead-column fake signal; the real
 * drill mechanism is the independent O-C10-1 task).
 * <p>
 * Pure addition — no existing tables or columns are touched; safeExecute ignores error code
 * 1060 (duplicate column) so the migration is rerun-idempotent. Rollback = ALTER TABLE
 * dm_approval DROP COLUMN recovery_type (transparent to older binaries — the column is never
 * read by pre-C10 code; the detail VO field is additive). Version note: V202610130004 is the
 * current max (scripts-directory check 2026-10-14); this task takes the next day's first slot.
 */
public class V202610140001__dm_approval_recovery_type extends AbstractUpgradeJavaMigration {

    @Override
    public List<String> collectScript() {
        return List.of("""
                ALTER TABLE dm_approval ADD COLUMN recovery_type varchar(32) NULL
                """);
    }
}
