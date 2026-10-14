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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.clougence.clouddm.platform.dal.model.approval.RecoveryType;
import com.clougence.clouddm.platform.dal.model.dbchange.ChangeType;
import com.clougence.clouddm.sdk.sql.parser.SplitQueryType;
import com.clougence.utils.JsonUtils;
import com.clougence.utils.StringUtils;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * C10 (task 10-14-recovery-type-grading, design D3): the recovery-type inference engine — a
 * PURE FUNCTION over submission-time signals (zero RPC, zero SELECT, zero locale/request
 * context). Grades ONE ticket's recovery semantics from the structured signals that already
 * exist inside the submit/resubmit transaction, and produces the per-statement companion view
 * (裁决 2: the ticket-level grade is a single value — mixed tickets take the MOST SEVERE arm;
 * the per-statement list is display material, NEVER persisted).
 * <p>
 * <b>Grading ladder (descending scan, first hit wins = "混合取最严", the ChangeType.merge
 * precedent — severity = the RecoveryType declaration order):</b>
 * <ol>
 * <li>{@link RecoveryType#IRREVERSIBLE} — any statement of the DATA-DESTRUCTION class (the
 *     design-D3 core {DROP_TABLE, TRUNCATE_TABLE, DROP_SCHEMA} widened by the 2026-10-14
 *     main-session ruling with DROP_CATALOG / DROP_PARTITION / TRUNCATE_PARTITION /
 *     DROP_COLUMN / TRUNCATE_COLUMN — rebuildable-object drops stay OUT; the full
 *     included/excluded caliber table lives on {@link #IRREVERSIBLE_STMT_TYPES});</li>
 * <li>{@link RecoveryType#BATCH_CHECKPOINT} — any DML_BATCH segment (partial-success semantics,
 *     NOT a rollback — the lockfree-dml 三点明示 copy owns the user-facing wording);</li>
 * <li>{@link RecoveryType#GHOST_OLD_TABLE} — any GHOST segment (the STATIC conservative label:
 *     the old table is retained after cut-over and manual rollback is possible before its drop
 *     — never "rollback-capable at any time"; the runtime stage display stays with the ghost
 *     panel);</li>
 * <li>{@link RecoveryType#COMPENSATION_SQL} — applicant-provided material (手填非空非空白;
 *     裁决 1: providing the material IS the compensation intent declaration — the CONTENT is
 *     never inspected or validated here, validation belongs to the O-C10-1 drill mechanism);</li>
 * <li>{@link RecoveryType#TRANSACTION} — merged changeType == DML ∧ no routed segments (the
 *     D15 execution-config derivation makes such a group necessarily transactional);</li>
 * <li>{@link RecoveryType#BACKUP_TEMPLATE} — system-generated material exists (the actual hit
 *     face = DDL/MIXED non-transactional direct execution + auto template; a DML transactional
 *     ticket's template is companion material shown in the per-statement view — it never steals
 *     the TRANSACTION main grade, design D9);</li>
 * <li>{@link RecoveryType#UNSPECIFIED} — missing signals / unparsable input / no material
 *     (CICD empty-string rows, ATTACHMENT tickets, stock rows beyond read-derivation).</li>
 * </ol>
 * Arm 4 outranks arm 5 by design (裁决 1: the explicit user declaration beats the transactional
 * inference). NEVER THROWS: any failure degrades to UNSPECIFIED + a warn (grading must never
 * block a submission).
 * <p>
 * Read-time stock derivation (design D5, 推荐 a): {@link #deriveFromMaterial} grades pre-C10
 * rows from the material-source flags ONLY (generated + blankness) — never written back; a
 * missing {@code rollBackSqlGenerated} key (pre-09-17 stock) honestly yields UNSPECIFIED.
 */
@Slf4j
public final class RecoveryTypeInferrer {

    /**
     * The arm-1 irreversible statement face (SplitQueryType names — full-enum review 2026-10-14).
     * <p>
     * <b>Widened beyond the design-D3 three-value set by the 2026-10-14 main-session ruling</b>
     * (fail-closed labeling: MISSING a data-destroying statement grades the ticket more
     * optimistically than reality — the exact 表述-能力不符 this task exists to kill; the grade is
     * annotation-only, never a gate, so the widening carries zero behavior risk). Inclusion
     * caliber = DATA-DESTRUCTION class (the lost DATA cannot be rebuilt from any platform
     * recovery artifact):
     * <ul>
     * <li>{@code DROP_TABLE} / {@code TRUNCATE_TABLE} — table data destroyed (design D3 core);</li>
     * <li>{@code DROP_SCHEMA} / {@code DROP_CATALOG} — every table's data under the
     *     schema/catalog destroyed (catalog = the schema superset; MySQL DROP DATABASE ≡ both);</li>
     * <li>{@code DROP_PARTITION} / {@code TRUNCATE_PARTITION} — the partition's DATA is
     *     destroyed (a dropped partition is gone with its rows; contrast ADD/ALTER_PARTITION
     *     which move no data out);</li>
     * <li>{@code DROP_COLUMN} / {@code TRUNCATE_COLUMN} — the column's DATA is destroyed (the
     *     structure is re-addable, the values are not).</li>
     * </ul>
     * <b>Deliberately EXCLUDED</b> (each with its caliber reason):
     * <ul>
     * <li>rebuildable-OBJECT drops — {@code DROP_VIEW / DROP_SEQUENCE / DROP_INDEX / DROP_TYPE /
     *     DROP_SYNONYM / DROP_PROG_OBJ / DROP_TRIGGER / DROP_EVENT / DROP_JOB /
     *     DROP_RESOURCE_GROUP / DROP_USER / DROP_ROLE / DROP_CONSTRAINT / DROP_LIBRARY /
     *     DROP_REPLICATION / DROP_PUB_SUB / DROP_LOG / DROP_POLICY}: pure definitions/state,
     *     rebuildable from a schema snapshot — structure-reversible, NOT data-irreversible
     *     (a sequence's counter state and a user's grants are re-creatable configuration);</li>
     * <li>{@code RENAME_*} (CATALOG/SCHEMA/TABLE/COLUMN/…): a reverse rename restores the
     *     prior state, no data destroyed;</li>
     * <li>{@code DROP_TABLESPACE} — borderline, excluded: in the two primary dialects
     *     (MySQL/PG) the drop REQUIRES an emptied container, and the emptying itself is
     *     DROP/TRUNCATE_TABLE (already arm 1) — no independent data-destruction face;</li>
     * <li>{@code ALTER_TABLE / ALTER_*}: the type face does not reveal the sub-clause (a
     *     column-dropping ALTER is typed DROP_COLUMN by the parser when it resolves the
     *     sub-action; an unresolved ALTER keeps its arm-6/manual-advice treatment — the
     *     generator's ALTER note already says the rollback depends on the sub-clause).</li>
     * </ul>
     */
    private static final Set<String> IRREVERSIBLE_STMT_TYPES = Set.of(
        SplitQueryType.DROP_TABLE.name(),
        SplitQueryType.TRUNCATE_TABLE.name(),
        SplitQueryType.DROP_SCHEMA.name(),
        SplitQueryType.DROP_CATALOG.name(),
        SplitQueryType.DROP_PARTITION.name(),
        SplitQueryType.TRUNCATE_PARTITION.name(),
        SplitQueryType.DROP_COLUMN.name(),
        SplitQueryType.TRUNCATE_COLUMN.name());

    private RecoveryTypeInferrer() {
    }

    // ==================== public API ====================

    /**
     * Grade one ticket + build the per-statement companion view. Never throws.
     */
    public static Result infer(Input input) {
        try {
            Input in = input != null ? input : new Input();
            RecoveryType type = inferType(in);
            return new Result(type, buildStmtViews(in));
        } catch (Exception e) {
            log.warn("[RecoveryTypeInferrer] inference failed, degrading to UNSPECIFIED (submission never blocked)", e);
            return new Result(RecoveryType.UNSPECIFIED, List.of());
        }
    }

    /**
     * Stock-row read-time derivation (design D5): grade from the material-source signals alone
     * (no structural signals exist on the read path — no extra SELECT is performed). Arms:
     * generated TRUE → BACKUP_TEMPLATE; generated FALSE ∧ material non-blank → COMPENSATION_SQL;
     * everything else (missing flag = pre-09-17 stock, or no material) → UNSPECIFIED.
     * The result is NEVER written back (the deriveLegacyStepFinishTimes precedent).
     */
    public static RecoveryType deriveFromMaterial(Boolean rollBackSqlGenerated, String rollBackSql) {
        Input in = new Input();
        in.setGenerated(rollBackSqlGenerated);
        in.setUserProvided(Boolean.FALSE.equals(rollBackSqlGenerated) && StringUtils.isNotBlank(rollBackSql));
        return infer(in).getRecoveryType();
    }

    /**
     * Rebuild one group's structural signals from a STORED precheck_result JSON (the resubmit
     * untouched-SUCCESS-group arm — the modified groups carry fresh typed results instead).
     * Never throws: blank/unparsable → an empty-signal Group (contributes nothing to the ladder).
     */
    @SuppressWarnings("unchecked")
    public static Group groupFromPrecheckJson(String precheckResultJson) {
        Group group = new Group();
        if (StringUtils.isBlank(precheckResultJson)) {
            return group;
        }
        try {
            Map<String, Object> precheck = JsonUtils.toObj(precheckResultJson, Map.class);
            if (precheck == null) {
                return group;
            }
            Object changeType = precheck.get("changeType");
            if (changeType != null) {
                group.setChangeType(String.valueOf(changeType));
            }
            Object statements = precheck.get("statements");
            if (statements instanceof List<?> stmtList) {
                List<String> types = new ArrayList<>();
                for (Object s : stmtList) {
                    if (s instanceof Map<?, ?> sm) {
                        Object t = sm.get("type");
                        types.add(t != null ? String.valueOf(t) : null);
                    }
                }
                group.setStatementTypes(types);
            }
            // segments: the plan carrier parse (TicketSegment.parsePlan is itself never-throw)
            List<TicketSegment> plan = TicketSegment.parsePlan(precheckResultJson);
            if (plan != null) {
                List<Segment> segments = new ArrayList<>();
                for (TicketSegment seg : plan) {
                    if (seg != null && (TicketSegment.TYPE_GHOST.equals(seg.getType())
                            || TicketSegment.TYPE_DML_BATCH.equals(seg.getType()))) {
                        segments.add(new Segment(seg.getType(), seg.getStmtIndex()));
                    }
                }
                group.setSegments(segments.isEmpty() ? null : segments);
            }
        } catch (Exception e) {
            log.warn("[RecoveryTypeInferrer] unparsable precheck_result for recovery grading (group signals degraded)", e);
        }
        return group;
    }

    // ==================== ladder ====================

    private static RecoveryType inferType(Input in) {
        List<Group> groups = in.getGroups();

        // arm 1: IRREVERSIBLE — any irreversible statement type across all groups
        if (groups != null) {
            for (Group g : groups) {
                if (g == null || g.getStatementTypes() == null) {
                    continue;
                }
                for (String type : g.getStatementTypes()) {
                    if (type != null && IRREVERSIBLE_STMT_TYPES.contains(type)) {
                        return RecoveryType.IRREVERSIBLE;
                    }
                }
            }
        }

        // arms 2/3: routed segments (BATCH_CHECKPOINT outranks GHOST_OLD_TABLE)
        boolean hasBatch = false;
        boolean hasGhost = false;
        if (groups != null) {
            for (Group g : groups) {
                if (g == null || g.getSegments() == null) {
                    continue;
                }
                for (Segment s : g.getSegments()) {
                    if (s == null) {
                        continue;
                    }
                    if (TicketSegment.TYPE_DML_BATCH.equals(s.getType())) {
                        hasBatch = true;
                    } else if (TicketSegment.TYPE_GHOST.equals(s.getType())) {
                        hasGhost = true;
                    }
                }
            }
        }
        if (hasBatch) {
            return RecoveryType.BATCH_CHECKPOINT;
        }
        if (hasGhost) {
            return RecoveryType.GHOST_OLD_TABLE;
        }

        // arm 4: COMPENSATION_SQL — the applicant-provided material declaration (裁决 1)
        if (in.isUserProvided()) {
            return RecoveryType.COMPENSATION_SQL;
        }

        // arm 5: TRANSACTION — DML ∧ no routed segments (necessarily transactional, D15)
        ChangeType merged = mergeChangeTypes(groups);
        if (merged == ChangeType.DML && !hasBatch && !hasGhost) {
            return RecoveryType.TRANSACTION;
        }

        // arm 6: BACKUP_TEMPLATE — system-generated material exists
        if (Boolean.TRUE.equals(in.getGenerated())) {
            return RecoveryType.BACKUP_TEMPLATE;
        }

        // arm 7: honest fallback
        return RecoveryType.UNSPECIFIED;
    }

    /** ChangeType.merge guarded for the never-throw contract (merge throws on an empty set). */
    private static ChangeType mergeChangeTypes(List<Group> groups) {
        if (groups == null || groups.isEmpty()) {
            return null;
        }
        List<ChangeType> types = new ArrayList<>();
        for (Group g : groups) {
            if (g == null || StringUtils.isBlank(g.getChangeType())) {
                continue;
            }
            try {
                types.add(ChangeType.valueOf(g.getChangeType()));
            } catch (IllegalArgumentException e) {
                log.warn("[RecoveryTypeInferrer] unknown changeType signal '{}' (ignored)", g.getChangeType());
            }
        }
        if (types.isEmpty()) {
            return null;
        }
        return ChangeType.merge(types);
    }

    // ==================== per-statement companion view ====================

    private static List<StmtView> buildStmtViews(Input in) {
        List<StmtView> views = new ArrayList<>();
        List<Group> groups = in.getGroups();
        if (groups != null && !groups.isEmpty()) {
            for (int go = 0; go < groups.size(); go++) {
                Group g = groups.get(go);
                if (g == null) {
                    continue;
                }
                // routed-segment lookup: 1-based exec_order → segment type
                java.util.Map<Integer, String> routedByExecOrder = new java.util.HashMap<>();
                if (g.getSegments() != null) {
                    for (Segment s : g.getSegments()) {
                        if (s != null && s.getStmtIndex() != null) {
                            routedByExecOrder.put(s.getStmtIndex(), s.getType());
                        }
                    }
                }
                List<String> types = g.getStatementTypes() != null ? g.getStatementTypes() : List.of();
                List<RollbackGenResult.StmtRecovery> gens = genEntriesForGroup(in.getGenResult(), go);
                int n = Math.max(types.size(), gens.size());
                for (int i = 0; i < n; i++) {
                    String type = i < types.size() ? types.get(i) : null;
                    String routed = routedByExecOrder.get(i + 1); // exec_order = 0-based position + 1 (R11)
                    RecoveryType recovery;
                    String note;
                    if (TicketSegment.TYPE_DML_BATCH.equals(routed)) {
                        recovery = RecoveryType.BATCH_CHECKPOINT;
                        note = "分批执行:部分成功非回滚,已提交批次保留,续跑/裁决见分批面板";
                    } else if (TicketSegment.TYPE_GHOST.equals(routed)) {
                        recovery = RecoveryType.GHOST_OLD_TABLE;
                        note = "gh-ost 执行:cut-over 后旧表保留,删除前可人工回退";
                    } else if (type != null && IRREVERSIBLE_STMT_TYPES.contains(type)) {
                        recovery = RecoveryType.IRREVERSIBLE;
                        note = "DROP/TRUNCATE 类语句不可逆,恢复依赖数据库备份";
                    } else if (i < gens.size()) {
                        RollbackGenResult.StmtRecovery gen = gens.get(i);
                        recovery = gen.getRecovery();
                        note = gen.getNote();
                    } else {
                        recovery = RecoveryType.UNSPECIFIED;
                        note = in.isUserProvided() ? "用户手填材料:逐语句语义不自动判别" : "无逐语句结构信号";
                    }
                    views.add(new StmtView(go, i, type, recovery, note));
                }
            }
            return views;
        }
        // no structural groups (legacy create / stock read): the generator companion alone
        if (in.getGenResult() != null) {
            for (RollbackGenResult.StmtRecovery gen : in.getGenResult().getPerStmtTypes()) {
                views.add(new StmtView(gen.getGroupOrdinal(), gen.getIndex(), null, gen.getRecovery(), gen.getNote()));
            }
        }
        return views;
    }

    private static List<RollbackGenResult.StmtRecovery> genEntriesForGroup(RollbackGenResult gen, int groupOrdinal) {
        if (gen == null || gen.getPerStmtTypes() == null) {
            return List.of();
        }
        List<RollbackGenResult.StmtRecovery> entries = new ArrayList<>();
        for (RollbackGenResult.StmtRecovery s : gen.getPerStmtTypes()) {
            if (s != null && s.getGroupOrdinal() == groupOrdinal) {
                entries.add(s);
            }
        }
        // positional alignment: the generator split order within the group
        entries.sort(java.util.Comparator.comparingInt(RollbackGenResult.StmtRecovery::getIndex));
        return entries;
    }

    // ==================== input / output carriers ====================

    /** The inference input — every field optional (missing signals degrade honestly). */
    @Getter
    @Setter
    public static final class Input {
        /** Per-group structural signals (v2 chains); null/empty = no structural grading arms. */
        private List<Group>       groups;
        /** The rollBackSqlGenerated flag; null = unknown (pre-09-17 stock). */
        private Boolean           generated;
        /** Applicant-provided material present (non-blank) — the CONTENT is never inspected. */
        private boolean           userProvided;
        /** The typed generation companion (null for user-provided material / no generation). */
        private RollbackGenResult genResult;
    }

    /** One group's structural signals (fresh precheck result or stored precheck_result JSON). */
    @Getter
    @Setter
    public static final class Group {
        /** DDL | DML | MIXED (ChangeType name); null = unknown. */
        private String        changeType;
        /** SplitQueryType names, positional (the precheck preview domain, 0-based). */
        private List<String>  statementTypes;
        /** Routed segments only (GHOST / DML_BATCH); null = none. */
        private List<Segment> segments;
    }

    /** One routed segment's inference-relevant face. */
    @Getter
    public static final class Segment {
        /** GHOST | DML_BATCH (TicketSegment type constants). */
        private final String  type;
        /** The statement's 1-based exec_order position (R11 domain); nullable. */
        private final Integer stmtIndex;

        public Segment(String type, Integer stmtIndex) {
            this.type = type;
            this.stmtIndex = stmtIndex;
        }
    }

    /** The inference output: the ticket-level grade + the per-statement companion view. */
    @Getter
    public static final class Result {
        private final RecoveryType  recoveryType;
        /** Display material (裁决 2) — NEVER persisted. */
        private final List<StmtView> statements;

        public Result(RecoveryType recoveryType, List<StmtView> statements) {
            this.recoveryType = recoveryType != null ? recoveryType : RecoveryType.UNSPECIFIED;
            this.statements = statements != null ? statements : List.of();
        }
    }

    /**
     * One statement's per-statement view entry {index, type, inferredRecovery, note} (design D3)
     * + its group ordinal (the multi-group alignment key).
     */
    @Getter
    public static final class StmtView {
        /** 0-based group position (the Input.groups order). */
        private final int          groupOrdinal;
        /** 0-based statement position within the group (the precheck preview domain). */
        private final int          index;
        /** The SplitQueryType name (null when no structural signal exists). */
        private final String       type;
        /** The statement's inferred recovery semantics. */
        private final RecoveryType inferredRecovery;
        /** Short human note (zh copy — the generator companion language). */
        private final String       note;

        public StmtView(int groupOrdinal, int index, String type, RecoveryType inferredRecovery, String note) {
            this.groupOrdinal = groupOrdinal;
            this.index = index;
            this.type = type;
            this.inferredRecovery = inferredRecovery;
            this.note = note;
        }
    }
}
