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

import com.clougence.clouddm.platform.dal.model.approval.RecoveryType;

import lombok.Getter;

/**
 * C10 (task 10-14-recovery-type-grading, design D2): the TYPED result of one rollback-template
 * generation run — the template text plus the per-statement classification companion list.
 * <p>
 * {@code text} keeps the exact pre-C10 shape for the MySQL dialect (red line 1 — the 16
 * RollbackSqlGeneratorTest pins are byte-level); {@code perStmtTypes} is the additive companion:
 * one entry per processed statement, feeding the RecoveryTypeInferrer's per-statement view
 * (detail-page display source, 裁决 2 — NEVER persisted).
 * <p>
 * Immutable value object; {@code text == null} means "no material" (blank input), mirroring the
 * legacy {@code generate(rawSql) == null} contract.
 */
@Getter
public final class RollbackGenResult {

    /** The generated template text (null when the input was blank — the legacy contract). */
    private final String              text;

    /** Per-statement classification, in generation order (empty when nothing was processed). */
    private final List<StmtRecovery>  perStmtTypes;

    public RollbackGenResult(String text, List<StmtRecovery> perStmtTypes) {
        this.text = text;
        this.perStmtTypes = perStmtTypes == null ? List.of() : List.copyOf(perStmtTypes);
    }

    /**
     * One processed statement's recovery semantics AS REFLECTED BY THE GENERATED MATERIAL:
     * UPDATE/DELETE → {@link RecoveryType#BACKUP_TEMPLATE} (backup-table template, full-table
     * degradation noted); DROP/TRUNCATE → {@link RecoveryType#IRREVERSIBLE}; INSERT / ALTER /
     * generic / parse-failed → {@link RecoveryType#UNSPECIFIED} (comment-only advice — no
     * effective auto recovery artifact, the note tells the reader what manual work is needed).
     * <p>
     * Index domain: {@code index} is the 0-based position among the group's PROCESSED statements
     * (the generator's own text-level split) — it aligns positionally with the precheck
     * {@code statements[].index} preview domain (also 0-based) for the same input text; the
     * segment-plan join (1-based exec_order) is done by the inferrer (index + 1), never here.
     */
    @Getter
    public static final class StmtRecovery {

        /** 0-based group position within a combined generation (always 0 for a single-group run). */
        private final int          groupOrdinal;
        /** 0-based statement position within the group (generator split order). */
        private final int          index;
        /** The statement's recovery semantics as reflected by its generated material. */
        private final RecoveryType recovery;
        /** Extracted target table name (nullable — extraction may fail by design). */
        private final String       table;
        /** Short human note (zh copy, consistent with the template comment language). */
        private final String       note;

        public StmtRecovery(int groupOrdinal, int index, RecoveryType recovery, String table, String note) {
            this.groupOrdinal = groupOrdinal;
            this.index = index;
            this.recovery = recovery;
            this.table = table;
            this.note = note;
        }

        /** Copy with a re-stamped group ordinal (the combined-generation assembly). */
        StmtRecovery withGroupOrdinal(int ordinal) {
            return new StmtRecovery(ordinal, index, recovery, table, note);
        }
    }
}
