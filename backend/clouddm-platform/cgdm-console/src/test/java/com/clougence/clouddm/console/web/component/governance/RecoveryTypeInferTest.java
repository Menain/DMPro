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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.clougence.clouddm.platform.dal.model.approval.RecoveryType;

/**
 * C10 (task 10-14-recovery-type-grading, design D3/D8 — the ONE authorized new test class):
 * the RecoveryTypeInferrer grading matrix.
 * <p>
 * Coverage:
 * <ul>
 * <li>every ladder arm 1-7 in isolation (A1 推断矩阵逐臂 pin);</li>
 * <li>mixed-signal merges — "取最严" severity order (A1 归并);</li>
 * <li>手填优先 (裁决 1: arm 4 outranks the arm-5 transactional inference);</li>
 * <li>resubmit D7 three-branch × re-grading input shapes (branch 1 new user value, branch 2
 *     regenerated after a DML→DDL edit / a fresh DML_BATCH route, branch 3 = the service writes
 *     nothing — pinned here as "a hypothetical re-grade would lose the old COMPENSATION_SQL
 *     grade", which is WHY the branch skips the inferrer by contract);</li>
 * <li>stock read-time derivation (deriveFromMaterial — 推荐 a: no backfill, missing generated
 *     flag honestly UNSPECIFIED);</li>
 * <li>groupFromPrecheckJson (the resubmit untouched-group arm, never-throw);</li>
 * <li>the per-statement companion view (routed-segment alignment in the 1-based exec_order
 *     domain, generator-entry mapping, the user-provided fallback note);</li>
 * <li>never-throw degradation (null/garbage input → UNSPECIFIED, no exception).</li>
 * </ul>
 * Discipline: JUnit4, no Spring context, no mocks needed (pure function under test).
 */
public class RecoveryTypeInferTest {

    private final RollbackSqlGenerator generator = new RollbackSqlGenerator();

    // ==================== input builders ====================

    private static RecoveryTypeInferrer.Input input(List<RecoveryTypeInferrer.Group> groups,
                                                    Boolean generated, boolean userProvided,
                                                    RollbackGenResult gen) {
        RecoveryTypeInferrer.Input in = new RecoveryTypeInferrer.Input();
        in.setGroups(groups);
        in.setGenerated(generated);
        in.setUserProvided(userProvided);
        in.setGenResult(gen);
        return in;
    }

    private static RecoveryTypeInferrer.Group group(String changeType, List<String> stmtTypes,
                                                    RecoveryTypeInferrer.Segment... segments) {
        RecoveryTypeInferrer.Group g = new RecoveryTypeInferrer.Group();
        g.setChangeType(changeType);
        g.setStatementTypes(stmtTypes);
        if (segments != null && segments.length > 0) {
            g.setSegments(Arrays.asList(segments));
        }
        return g;
    }

    private static RecoveryTypeInferrer.Segment seg(String type, Integer stmtIndex) {
        return new RecoveryTypeInferrer.Segment(type, stmtIndex);
    }

    private static RecoveryType grade(RecoveryTypeInferrer.Input in) {
        return RecoveryTypeInferrer.infer(in).getRecoveryType();
    }

    // ==================== ladder arms (isolated) ====================

    @Test
    public void arm1_dropTable_gradesIrreversible() {
        assertEquals(RecoveryType.IRREVERSIBLE, grade(input(
            List.of(group("DDL", List.of("DROP_TABLE"))), null, false, null)));
    }

    @Test
    public void arm1_truncateTable_gradesIrreversible() {
        assertEquals(RecoveryType.IRREVERSIBLE, grade(input(
            List.of(group("DDL", List.of("TRUNCATE_TABLE"))), null, false, null)));
    }

    @Test
    public void arm1_dropSchema_gradesIrreversible() {
        assertEquals(RecoveryType.IRREVERSIBLE, grade(input(
            List.of(group("DDL", List.of("DROP_SCHEMA"))), null, false, null)));
    }

    // ---- arm-1 widening (2026-10-14 main-session ruling: the DATA-DESTRUCTION class —
    //      fail-closed labeling, missing a data destroyer = an over-optimistic grade) ----

    @Test
    public void arm1_dropCatalog_gradesIrreversible() {
        // the schema superset — every table's data under the catalog is destroyed
        assertEquals(RecoveryType.IRREVERSIBLE, grade(input(
            List.of(group("DDL", List.of("DROP_CATALOG"))), null, false, null)));
    }

    @Test
    public void arm1_truncatePartition_gradesIrreversible() {
        assertEquals(RecoveryType.IRREVERSIBLE, grade(input(
            List.of(group("DDL", List.of("TRUNCATE_PARTITION"))), null, false, null)));
    }

    @Test
    public void arm1_dropPartition_gradesIrreversible() {
        // the dropped partition's DATA is gone — contrast ADD/ALTER_PARTITION (no data out)
        assertEquals(RecoveryType.IRREVERSIBLE, grade(input(
            List.of(group("DDL", List.of("DROP_PARTITION"))), null, false, null)));
    }

    @Test
    public void arm1_columnDataDestruction_dropAndTruncateColumn_gradeIrreversible() {
        // the column STRUCTURE is re-addable, the column DATA is not
        assertEquals(RecoveryType.IRREVERSIBLE, grade(input(
            List.of(group("DDL", List.of("ALTER_TABLE", "DROP_COLUMN"))), null, false, null)));
        assertEquals(RecoveryType.IRREVERSIBLE, grade(input(
            List.of(group("DDL", List.of("TRUNCATE_COLUMN"))), null, false, null)));
    }

    @Test
    public void arm1_rebuildableObjectDrops_doNotGradeIrreversible() {
        // the EXCLUSION caliber pin: definitions/state rebuildable from a schema snapshot are
        // structure-reversible, NOT data-irreversible — they must never hit arm 1 (they fall
        // through to arm 6 with a generated template, or arm 7)
        for (String rebuildable : new String[] { "DROP_VIEW", "DROP_INDEX", "DROP_SEQUENCE",
                "DROP_USER", "DROP_ROLE", "DROP_PROG_OBJ", "DROP_TYPE", "DROP_SYNONYM",
                "DROP_TRIGGER", "DROP_CONSTRAINT", "DROP_TABLESPACE", "RENAME_TABLE",
                "ALTER_TABLE" }) {
            assertEquals("rebuildable/reversible type must not grade IRREVERSIBLE: " + rebuildable,
                RecoveryType.BACKUP_TEMPLATE, grade(input(
                    List.of(group("DDL", List.of(rebuildable))), true, false, null)));
        }
        // …and without a generated template they honestly stay UNSPECIFIED (never IRREVERSIBLE)
        assertEquals(RecoveryType.UNSPECIFIED, grade(input(
            List.of(group("DDL", List.of("DROP_VIEW"))), null, false, null)));
    }

    @Test
    public void arm2_dmlBatchSegment_gradesBatchCheckpoint() {
        assertEquals(RecoveryType.BATCH_CHECKPOINT, grade(input(
            List.of(group("DML", List.of("UPDATE"), seg("DML_BATCH", 1))), true, false, null)));
    }

    @Test
    public void arm3_ghostSegment_gradesGhostOldTable() {
        assertEquals(RecoveryType.GHOST_OLD_TABLE, grade(input(
            List.of(group("DDL", List.of("ALTER_TABLE"), seg("GHOST", 1))), true, false, null)));
    }

    @Test
    public void arm4_userProvided_gradesCompensationSql() {
        assertEquals(RecoveryType.COMPENSATION_SQL, grade(input(
            List.of(group("DDL", List.of("ALTER_TABLE"))), false, true, null)));
    }

    @Test
    public void arm5_dmlNoSegments_gradesTransaction_evenWhenTemplateGenerated() {
        // design D9: the auto template never steals the TRANSACTION main grade of a DML
        // transactional ticket (the template rides the per-statement companion view instead)
        assertEquals(RecoveryType.TRANSACTION, grade(input(
            List.of(group("DML", List.of("UPDATE"))), true, false, null)));
    }

    @Test
    public void arm6_ddlGenerated_gradesBackupTemplate() {
        assertEquals(RecoveryType.BACKUP_TEMPLATE, grade(input(
            List.of(group("DDL", List.of("ALTER_TABLE"))), true, false, null)));
    }

    @Test
    public void arm6_mixedGenerated_gradesBackupTemplate() {
        assertEquals(RecoveryType.BACKUP_TEMPLATE, grade(input(
            List.of(group("MIXED", List.of("UPDATE", "ALTER_TABLE"))), true, false, null)));
    }

    @Test
    public void arm7_noSignals_gradesUnspecified() {
        assertEquals(RecoveryType.UNSPECIFIED, grade(input(null, null, false, null)));
        assertEquals(RecoveryType.UNSPECIFIED, grade(input(List.of(group("DDL", List.of("ALTER_TABLE"))),
            null, false, null))); // DDL, nothing generated, nothing provided → honest UNSPECIFIED
    }

    // ==================== mixed-signal merges (取最严, severity = declaration order) ====================

    @Test
    public void merge_irreversibleBeatsBatchCheckpointAndGhost() {
        assertEquals(RecoveryType.IRREVERSIBLE, grade(input(
            List.of(group("MIXED", List.of("UPDATE", "DROP_TABLE"),
                seg("DML_BATCH", 1), seg("GHOST", 2))),
            true, true, null)));
    }

    @Test
    public void merge_batchCheckpointBeatsGhost() {
        assertEquals(RecoveryType.BATCH_CHECKPOINT, grade(input(
            List.of(group("MIXED", List.of("UPDATE", "ALTER_TABLE"),
                seg("GHOST", 2), seg("DML_BATCH", 1))),
            true, false, null)));
    }

    @Test
    public void merge_ghostBeatsUserProvided() {
        assertEquals(RecoveryType.GHOST_OLD_TABLE, grade(input(
            List.of(group("DDL", List.of("ALTER_TABLE"), seg("GHOST", 1))),
            false, true, null)));
    }

    @Test
    public void merge_userProvidedBeatsTransaction_rulingOne() {
        // 裁决 1: the explicit applicant declaration outranks the transactional inference (arm 4 > arm 5)
        assertEquals(RecoveryType.COMPENSATION_SQL, grade(input(
            List.of(group("DML", List.of("UPDATE"))), false, true, null)));
    }

    @Test
    public void merge_crossGroup_irreversibleInAnyGroupWins() {
        assertEquals(RecoveryType.IRREVERSIBLE, grade(input(
            List.of(group("DML", List.of("UPDATE")), group("DDL", List.of("TRUNCATE_TABLE"))),
            true, false, null)));
    }

    @Test
    public void merge_multiGroupDmlAndDdl_notTransaction_fallsToTemplate() {
        // ChangeType.merge semantics: DML + DDL groups → MIXED → arm 5 misses → arm 6
        assertEquals(RecoveryType.BACKUP_TEMPLATE, grade(input(
            List.of(group("DML", List.of("UPDATE")), group("DDL", List.of("ALTER_TABLE"))),
            true, false, null)));
    }

    @Test
    public void severityEnum_declarationOrderStrictestFirst() {
        assertTrue(RecoveryType.IRREVERSIBLE.ordinal() < RecoveryType.BATCH_CHECKPOINT.ordinal());
        assertTrue(RecoveryType.BATCH_CHECKPOINT.ordinal() < RecoveryType.GHOST_OLD_TABLE.ordinal());
        assertTrue(RecoveryType.GHOST_OLD_TABLE.ordinal() < RecoveryType.COMPENSATION_SQL.ordinal());
        assertTrue(RecoveryType.COMPENSATION_SQL.ordinal() < RecoveryType.TRANSACTION.ordinal());
        assertTrue(RecoveryType.TRANSACTION.ordinal() < RecoveryType.BACKUP_TEMPLATE.ordinal());
        assertTrue(RecoveryType.BACKUP_TEMPLATE.ordinal() < RecoveryType.UNSPECIFIED.ordinal());
        assertEquals(RecoveryType.IRREVERSIBLE, RecoveryType.mostSevere(
            List.of(RecoveryType.TRANSACTION, RecoveryType.IRREVERSIBLE, RecoveryType.BACKUP_TEMPLATE)));
        assertEquals(RecoveryType.UNSPECIFIED, RecoveryType.mostSevere(null));
        assertEquals(RecoveryType.UNSPECIFIED, RecoveryType.mostSevere(List.of()));
    }

    // ==================== resubmit D7 three branches × re-grading ====================

    @Test
    public void d7_branch1_newUserValue_regradesCompensationOverOldGeneratedSignals() {
        // the SQL edit refreshed the structural signals (DDL group); the new user material grades arm 4
        assertEquals(RecoveryType.COMPENSATION_SQL, grade(input(
            List.of(group("DDL", List.of("ALTER_TABLE"))), false, true, null)));
        // …unless a stricter structural arm fired on the NEW sql (DROP smuggled into the revision)
        assertEquals(RecoveryType.IRREVERSIBLE, grade(input(
            List.of(group("DDL", List.of("DROP_TABLE"))), false, true, null)));
    }

    @Test
    public void d7_branch2_regeneratedAfterDmlToDdlEdit_followsNewChangeType() {
        // before the edit the ticket graded TRANSACTION (DML); the revised SQL is DDL →
        // the fresh precheck signals re-grade to BACKUP_TEMPLATE (arm 6)
        assertEquals(RecoveryType.TRANSACTION, grade(input(
            List.of(group("DML", List.of("UPDATE"))), true, false, null)));
        assertEquals(RecoveryType.BACKUP_TEMPLATE, grade(input(
            List.of(group("DDL", List.of("ALTER_TABLE"))), true, false, null)));
    }

    @Test
    public void d7_branch2_regeneratedWithFreshBatchRoute_gradesBatchCheckpoint() {
        assertEquals(RecoveryType.BATCH_CHECKPOINT, grade(input(
            List.of(group("DML", List.of("UPDATE"), seg("DML_BATCH", 1))), true, false, null)));
    }

    @Test
    public void d7_branch3_materialKept_regradeWouldLoseGrade_serviceSkipsByContract() {
        // Branch 3 (blank + original applicant-provided → material KEPT): the service writes NO
        // recovery_type and NO audit row. This pin documents WHY: a hypothetical re-grade with
        // userProvided=false (the FO is blank!) would fall through to UNSPECIFIED and silently
        // lose the persisted COMPENSATION_SQL grade — so the branch must skip the inferrer.
        assertEquals(RecoveryType.COMPENSATION_SQL, grade(input(
            List.of(group("DDL", List.of("ALTER_TABLE"))), false, true, null)));   // the persisted grade
        assertEquals(RecoveryType.UNSPECIFIED, grade(input(
            List.of(group("DDL", List.of("ALTER_TABLE"))), false, false, null)));   // the wrong re-grade
    }

    // ==================== stock read-time derivation (design D5, 推荐 a) ====================

    @Test
    public void derive_generatedTrue_backupTemplate() {
        assertEquals(RecoveryType.BACKUP_TEMPLATE,
            RecoveryTypeInferrer.deriveFromMaterial(Boolean.TRUE, "-- 系统自动生成…CREATE TABLE `t_bak_x` …"));
    }

    @Test
    public void derive_generatedFalseWithMaterial_compensationSql() {
        assertEquals(RecoveryType.COMPENSATION_SQL,
            RecoveryTypeInferrer.deriveFromMaterial(Boolean.FALSE, "DELETE FROM t WHERE id=1;"));
    }

    @Test
    public void derive_generatedFalseBlankMaterial_unspecified() {
        assertEquals(RecoveryType.UNSPECIFIED, RecoveryTypeInferrer.deriveFromMaterial(Boolean.FALSE, "  "));
        assertEquals(RecoveryType.UNSPECIFIED, RecoveryTypeInferrer.deriveFromMaterial(Boolean.FALSE, null));
    }

    @Test
    public void derive_missingGeneratedFlag_preStockHonestlyUnspecified() {
        // pre-09-17 stock rows: the ticket_info JSON has no rollBackSqlGenerated key at all —
        // the material source is ambiguous (user-typed vs an early generator) → never guess
        assertEquals(RecoveryType.UNSPECIFIED,
            RecoveryTypeInferrer.deriveFromMaterial(null, "DELETE FROM t WHERE id=1;"));
        assertEquals(RecoveryType.UNSPECIFIED, RecoveryTypeInferrer.deriveFromMaterial(null, null));
    }

    @Test
    public void derive_cicdEmptyStringRow_unspecified() {
        // ChangeActionForApproval sets rollBackSql="" with no generated flag
        assertEquals(RecoveryType.UNSPECIFIED, RecoveryTypeInferrer.deriveFromMaterial(null, ""));
    }

    // ==================== groupFromPrecheckJson (the resubmit untouched-group arm) ====================

    @Test
    public void fromJson_fullShape_extractsAllStructuralSignals() {
        String json = "{\"checkStatus\":\"PASS\",\"changeType\":\"DML\","
            + "\"statements\":[{\"index\":0,\"type\":\"UPDATE\",\"sql\":\"…\"},{\"index\":1,\"type\":\"DELETE\",\"sql\":\"…\"}],"
            + "\"segments\":[{\"segIdx\":1,\"type\":\"AUTOEXEC\",\"stmtRange\":[2,2]},"
            + "{\"segIdx\":2,\"type\":\"DML_BATCH\",\"stmtIndex\":1,\"table\":\"t\"}]}";
        RecoveryTypeInferrer.Group g = RecoveryTypeInferrer.groupFromPrecheckJson(json);
        assertEquals("DML", g.getChangeType());
        assertEquals(List.of("UPDATE", "DELETE"), g.getStatementTypes());
        assertNotNull(g.getSegments());
        assertEquals(1, g.getSegments().size()); // AUTOEXEC filtered — only routed segments matter
        assertEquals("DML_BATCH", g.getSegments().get(0).getType());
        assertEquals(Integer.valueOf(1), g.getSegments().get(0).getStmtIndex());
        // …and it grades: the stored DML_BATCH route makes an untouched group BATCH_CHECKPOINT
        assertEquals(RecoveryType.BATCH_CHECKPOINT, grade(input(List.of(g), true, false, null)));
    }

    @Test
    public void fromJson_corruptOrBlank_neverThrows_emptySignals() {
        RecoveryTypeInferrer.Group corrupt = RecoveryTypeInferrer.groupFromPrecheckJson("{not-json");
        assertNull(corrupt.getChangeType());
        assertNull(corrupt.getStatementTypes());
        assertNull(corrupt.getSegments());
        RecoveryTypeInferrer.Group blank = RecoveryTypeInferrer.groupFromPrecheckJson("  ");
        assertNull(blank.getChangeType());
        // an all-empty group contributes nothing → honest UNSPECIFIED
        assertEquals(RecoveryType.UNSPECIFIED, grade(input(List.of(corrupt), null, false, null)));
    }

    // ==================== per-statement companion view (裁决 2 display material) ====================

    @Test
    public void stmtView_routedSegmentsAlignOnOneBasedExecOrder() {
        // statements at 0-based positions 0/1/2 → exec_order 1/2/3; the DML_BATCH segment binds
        // stmtIndex=2 (1-based) → the view entry at index 1
        RecoveryTypeInferrer.Result r = RecoveryTypeInferrer.infer(input(
            List.of(group("DML", List.of("UPDATE", "UPDATE", "UPDATE"), seg("DML_BATCH", 2))),
            true, false, null));
        List<RecoveryTypeInferrer.StmtView> views = r.getStatements();
        assertEquals(3, views.size());
        assertEquals(RecoveryType.UNSPECIFIED, views.get(0).getInferredRecovery()); // no gen entry, not routed
        assertEquals(RecoveryType.BATCH_CHECKPOINT, views.get(1).getInferredRecovery());
        assertEquals(1, views.get(1).getIndex());
        assertEquals("UPDATE", views.get(1).getType());
        assertEquals(RecoveryType.UNSPECIFIED, views.get(2).getInferredRecovery());
    }

    @Test
    public void stmtView_irreversibleStatementType() {
        RecoveryTypeInferrer.Result r = RecoveryTypeInferrer.infer(input(
            List.of(group("DDL", List.of("ALTER_TABLE", "DROP_TABLE"))), true, false, null));
        assertEquals(RecoveryType.UNSPECIFIED, r.getStatements().get(0).getInferredRecovery());
        assertEquals(RecoveryType.IRREVERSIBLE, r.getStatements().get(1).getInferredRecovery());
    }

    @Test
    public void stmtView_generatorEntriesMapped_perStatementSemantics() {
        // the real generator companion: UPDATE→BACKUP_TEMPLATE, INSERT→UNSPECIFIED (comment-only),
        // DROP→IRREVERSIBLE, ALTER→UNSPECIFIED, GRANT→UNSPECIFIED
        RollbackGenResult gen = generator.generateTyped(
            "UPDATE t SET a=1 WHERE id=1; INSERT INTO t VALUES(1); DROP TABLE x; ALTER TABLE y ADD COLUMN c INT; GRANT SELECT ON z TO u;",
            null);
        RecoveryTypeInferrer.Result r = RecoveryTypeInferrer.infer(input(
            List.of(group("MIXED", List.of("UPDATE", "INSERT", "DROP_TABLE", "ALTER_TABLE", "GRANT"))),
            true, false, gen));
        List<RecoveryTypeInferrer.StmtView> views = r.getStatements();
        assertEquals(5, views.size());
        assertEquals(RecoveryType.BACKUP_TEMPLATE, views.get(0).getInferredRecovery());
        assertEquals(RecoveryType.UNSPECIFIED, views.get(1).getInferredRecovery());
        assertEquals(RecoveryType.IRREVERSIBLE, views.get(2).getInferredRecovery()); // structural arm beats the gen entry
        assertEquals(RecoveryType.UNSPECIFIED, views.get(3).getInferredRecovery());
        assertEquals(RecoveryType.UNSPECIFIED, views.get(4).getInferredRecovery());
        // the ticket-level grade stays IRREVERSIBLE (arm 1)
        assertEquals(RecoveryType.IRREVERSIBLE, r.getRecoveryType());
        // generator companion face itself
        assertEquals(5, gen.getPerStmtTypes().size());
        assertEquals("t", gen.getPerStmtTypes().get(0).getTable());
        assertNotNull(gen.getPerStmtTypes().get(0).getNote());
        assertEquals(0, gen.getPerStmtTypes().get(0).getGroupOrdinal());
    }

    @Test
    public void stmtView_userProvidedMaterial_fallbackNoteNotFakeSemantics() {
        RecoveryTypeInferrer.Result r = RecoveryTypeInferrer.infer(input(
            List.of(group("DML", List.of("UPDATE"))), false, true, null));
        assertEquals(RecoveryType.COMPENSATION_SQL, r.getRecoveryType());
        assertEquals(1, r.getStatements().size());
        // no generator entries + not routed + not irreversible → honest UNSPECIFIED with the
        // "user material is not per-statement decidable" note
        assertEquals(RecoveryType.UNSPECIFIED, r.getStatements().get(0).getInferredRecovery());
        assertTrue(r.getStatements().get(0).getNote().contains("手填"));
    }

    @Test
    public void stmtView_legacyShapeNoGroups_generatorCompanionOnly() {
        RollbackGenResult gen = generator.generateTyped("UPDATE t SET a=1 WHERE id=1; DROP TABLE x;", null);
        RecoveryTypeInferrer.Result r = RecoveryTypeInferrer.infer(input(null, true, false, gen));
        assertEquals(RecoveryType.BACKUP_TEMPLATE, r.getRecoveryType()); // arm 6 (no structural signals)
        assertEquals(2, r.getStatements().size());
        assertEquals(RecoveryType.BACKUP_TEMPLATE, r.getStatements().get(0).getInferredRecovery());
        assertEquals(RecoveryType.IRREVERSIBLE, r.getStatements().get(1).getInferredRecovery());
        assertNull(r.getStatements().get(0).getType()); // no SplitQueryType signal on the legacy chain
    }

    @Test
    public void stmtView_multiGroup_ordinalsAlign() {
        RollbackGenResult gen = generator.generateCombined(List.of(
            new RollbackSqlGenerator.GroupInput("UPDATE a SET x=1 WHERE id=1;", null, "db1"),
            new RollbackSqlGenerator.GroupInput("UPDATE b SET x=1 WHERE id=1;",
                com.clougence.clouddm.base.metadata.ds.DataSourceType.PostgreSQL, "db2")));
        RecoveryTypeInferrer.Result r = RecoveryTypeInferrer.infer(input(
            List.of(group("DML", List.of("UPDATE")), group("DML", List.of("UPDATE"))),
            true, false, gen));
        assertEquals(2, r.getStatements().size());
        assertEquals(0, r.getStatements().get(0).getGroupOrdinal());
        assertEquals(1, r.getStatements().get(1).getGroupOrdinal());
        assertEquals(RecoveryType.BACKUP_TEMPLATE, r.getStatements().get(0).getInferredRecovery());
        assertEquals(RecoveryType.BACKUP_TEMPLATE, r.getStatements().get(1).getInferredRecovery());
    }

    // ==================== never-throw degradation ====================

    @Test
    public void infer_nullInput_unspecifiedNoThrow() {
        RecoveryTypeInferrer.Result r = RecoveryTypeInferrer.infer(null);
        assertEquals(RecoveryType.UNSPECIFIED, r.getRecoveryType());
        assertTrue(r.getStatements().isEmpty());
    }

    @Test
    public void infer_garbageSignals_neverThrow() {
        List<RecoveryTypeInferrer.Group> groups = new ArrayList<>();
        groups.add(null); // null group entry
        groups.add(group("NOT_A_CHANGE_TYPE", new ArrayList<>(Arrays.asList("UPDATE", null, "BOGUS_TYPE"))));
        groups.add(group(null, null, (RecoveryTypeInferrer.Segment[]) null));
        RecoveryTypeInferrer.Result r = RecoveryTypeInferrer.infer(input(groups, true, false, null));
        // the bogus changeType is ignored (warn), the null stmt type survives → arm 6 via generated
        assertEquals(RecoveryType.BACKUP_TEMPLATE, r.getRecoveryType());
    }

    @Test
    public void infer_segmentWithNullStmtIndex_neverThrow() {
        RecoveryTypeInferrer.Result r = RecoveryTypeInferrer.infer(input(
            List.of(group("DML", List.of("UPDATE"), seg("DML_BATCH", null))), true, false, null));
        // the ticket-level arm 2 only needs the segment TYPE (stmtIndex is the view's alignment key)
        assertEquals(RecoveryType.BATCH_CHECKPOINT, r.getRecoveryType());
        // the view entry cannot align → falls back honestly
        assertEquals(RecoveryType.UNSPECIFIED, r.getStatements().get(0).getInferredRecovery());
    }
}
