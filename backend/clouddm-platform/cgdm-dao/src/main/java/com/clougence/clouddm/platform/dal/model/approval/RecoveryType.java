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
package com.clougence.clouddm.platform.dal.model.approval;

/**
 * C10 (task 10-14-recovery-type-grading, design D1/D3): the recovery-semantics grade of ONE
 * ticket's rollback material — the structured answer to "if this change goes wrong, what does
 * recovery actually look like". Persisted on {@code dm_approval.recovery_type}
 * (varchar(32) NULL, migration V202610140001 — stock rows stay NULL and are derived at read
 * time, never backfilled).
 * <p>
 * Semantics table (plan-doc §2/§4.4 four-class taxonomy widened to the code reality — the
 * batch-checkpoint and gh-ost-old-table grades are NON-ROLLBACK recovery semantics and are
 * labeled as such; nothing here ever promises "auto rollback"):
 * <ul>
 * <li>{@link #IRREVERSIBLE} — DROP/TRUNCATE-class statement: no platform recovery artifact
 *     exists; the database backup is the only basis.</li>
 * <li>{@link #BATCH_CHECKPOINT} — lock-free batched DML: partial success is NOT a rollback;
 *     committed batches are KEPT, recovery = checkpoint continuation / manual adjudication
 *     (the 三点明示 copy owns the user-facing wording).</li>
 * <li>{@link #GHOST_OLD_TABLE} — gh-ost DDL: after cut-over the old table is retained and a
 *     manual rollback is possible BEFORE it is dropped (NOT rollback-capable at any time;
 *     the static grade is the conservative label — the runtime stage display stays with the
 *     ghost group panel).</li>
 * <li>{@link #COMPENSATION_SQL} — applicant-provided reverse SQL: human-reviewed, then
 *     executed as a NEW ticket (never auto-executed).</li>
 * <li>{@link #TRANSACTION} — transactional execution: a failure rolls the group back
 *     automatically, leaving no residue.</li>
 * <li>{@link #BACKUP_TEMPLATE} — system-generated backup-table template: must be manually
 *     completed and executed; never verified/drilled in-platform.</li>
 * <li>{@link #UNSPECIFIED} — stock rows / insufficient signals / no material: honestly
 *     "ungraded" (no fake default).</li>
 * </ul>
 * <p>
 * <b>Severity order = declaration order</b> (strictest first, {@code ordinal()} is the
 * severity): a mixed ticket grades to its MOST SEVERE arm — the {@code ChangeType.merge}
 * "any-wins" merge precedent (裁决 2, 混合取最严). {@link #UNSPECIFIED} is the weakest arm
 * (a fallback, not a severity claim).
 * <p>
 * Companion honesty flag: the API surface pairs this grade with a constant
 * {@code recoveryVerified=false} — NO verification/drill mechanism exists in-platform
 * (裁决 3; the real drill mechanism is the independent O-C10-1 task). The flag is a VO
 * read-time constant, deliberately NOT a DB column (red line 4 — no dead-column fake signal).
 */
public enum RecoveryType {

    /** DROP/TRUNCATE-class: no recovery artifact; database backup only. */
    IRREVERSIBLE,

    /** Batched lock-free DML: partial success kept, checkpoint continuation — NOT a rollback. */
    BATCH_CHECKPOINT,

    /** gh-ost DDL: old table retained after cut-over; manual rollback possible before its drop. */
    GHOST_OLD_TABLE,

    /** Applicant-provided compensation (reverse) SQL — human-reviewed, executed as a new ticket. */
    COMPENSATION_SQL,

    /** Transactional execution — automatic rollback on failure, no residue. */
    TRANSACTION,

    /** System-generated backup-table template — manual completion/execution required. */
    BACKUP_TEMPLATE,

    /** Stock/insufficient signals — honestly ungraded. */
    UNSPECIFIED;

    /**
     * The mixed-ticket merge (裁决 2 "混合取最严", the ChangeType.merge precedent): the most
     * severe grade wins; severity = declaration order (strictest first). Null/empty input →
     * {@link #UNSPECIFIED} (never throws — the honest fallback).
     */
    public static RecoveryType mostSevere(java.util.Collection<RecoveryType> types) {
        RecoveryType result = null;
        if (types != null) {
            for (RecoveryType type : types) {
                if (type == null) {
                    continue;
                }
                if (result == null || type.ordinal() < result.ordinal()) {
                    result = type;
                }
            }
        }
        return result == null ? UNSPECIFIED : result;
    }
}
