/*
 * Copyright 2026 杭州开云集致科技有限公司
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.clougence.clouddm.console.web.component.governance.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.clougence.clouddm.console.web.component.dsconfig.DmDsConfigService;
import com.clougence.clouddm.console.web.component.dsconfig.mode.DsLevels;
import com.clougence.clouddm.console.web.component.governance.PrecheckConclusion;
import com.clougence.clouddm.console.web.component.governance.ReleaseSchemaDiffService;
import com.clougence.clouddm.console.web.component.governance.SchemaFingerprintUtils;
import com.clougence.clouddm.console.web.component.governance.SchemaViolation;
import com.clougence.clouddm.console.web.component.schema.DsSchemaService;
import com.clougence.clouddm.platform.dal.access.ProdReleaseDal;
import com.clougence.clouddm.platform.dal.access.SystemDal;
import com.clougence.clouddm.platform.dal.access.DataSourceDal;
import com.clougence.clouddm.platform.dal.model.datasource.DmDsDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseStmtDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseStmtSchemaDO;
import com.clougence.schema.umi.special.rdb.RdbTable;
import com.clougence.schema.umi.struts.UmiTypes;
import com.clougence.schema.umi.struts.Value;
import com.clougence.utils.StringUtils;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * Schema drift diff engine implementation (sub-task A).
 * <p>
 * For each release stmt, loads the frozen pre-before snapshots, fetches the live
 * production structure, and compares:
 * <ul>
 *   <li>Exclusive table (shared_flag=0): BLOCK-level fingerprint diff</li>
 *   <li>Shared table (shared_flag=1): PASS + SHARED_TABLE_BASELINE note (D5 — ordered replay
 *       makes the baseline ambiguous; a previously-ruled design degradation, not an unknown)</li>
 *   <li>No snapshot (D2 fork, ADR-011): the WHOLE release has zero snapshot rows → pre-A legacy
 *       release → PASS + SNAPSHOT_MISSING_LEGACY note (one-time documented pass event); the
 *       release HAS snapshot rows but this stmt has none → capture-chain bug on a new release →
 *       UNVERIFIABLE (deny)</li>
 *   <li>exists_flag=0: production should not have the table</li>
 * </ul>
 * <p>
 * Fail-closed (ADR-011, supersedes 宁漏勿误): the service never throws; "cannot prove safe"
 * outcomes (missing stmt fields D1, DS absent D3, levels failure D4, live-schema fetch /
 * engine errors D7) return UNVERIFIABLE so the gate denies dispatch. ENGINE/comment-level
 * drift (D6) stays passing with an ENGINE_COMMENT_DRIFT note — SchemaFingerprintUtils
 * explicitly excludes those fields (ruled design, untouched). Break-glass: the registered
 * switch govReleaseSchemaDiffBlock=false degrades UNVERIFIABLE back to legacy WARN_SKIP at
 * the SINGLE exit point below (confirmed BLOCK drift is never degraded — the old escape
 * hatch that could silence proven violations is removed, ADR-011).
 */
@Slf4j
@Service
public class ReleaseSchemaDiffServiceImpl implements ReleaseSchemaDiffService {

    /** Fail-closed master switch for the diff gate — semantics mirror the precheck twin (ADR-011 M6). */
    private static final String CONFIG_DIFF_BLOCK_MODE = "govReleaseSchemaDiffBlock";

    @Resource
    private ProdReleaseDal          prodReleaseDal;
    @Resource
    private DataSourceDal          dsDal;
    @Resource
    private DmDsConfigService      configService;
    @Resource
    private DsSchemaService        dsSchemaService;
    @Resource
    private SystemDal              systemDal;

    @Override
    public PrecheckConclusion diff(DmProdReleaseStmtDO stmt) {
        PrecheckConclusion conclusion;
        if (stmt == null || stmt.getProdDsId() == null || stmt.getId() == null) {
            // D1 — data integrity broken, cannot prove safe (ADR-011)
            conclusion = PrecheckConclusion.unverifiable("stmt missing required fields");
        } else {
            try {
                conclusion = doDiff(stmt);
            } catch (Exception e) {
                // D7 — live-schema fetch failure / diff engine catch-all
                log.warn("[ReleaseSchemaDiff] Unexpected error for stmt id={}, prodDsId={}, prodDbName={}",
                    stmt.getId(), stmt.getProdDsId(), stmt.getProdDbName(), e);
                conclusion = PrecheckConclusion.unverifiable("Diff engine error: " + e.getMessage());
            }
        }
        // Break-glass single-point收口 (ADR-011 M6): switch false → UNVERIFIABLE degrades to the
        // legacy WARN_SKIP fail-open path. The former "BLOCK → WARN" downgrade (D8) is REMOVED —
        // confirmed drift can no longer be silenced by a global switch.
        if (conclusion.isUnverifiable() && !isBlockMode()) {
            return PrecheckConclusion.warnSkip(conclusion.getSkipReason());
        }
        return conclusion;
    }

    private PrecheckConclusion doDiff(DmProdReleaseStmtDO stmt) {
        // 1. Load frozen snapshots for this release stmt
        List<DmProdReleaseStmtSchemaDO> snapshots = prodReleaseDal.schemaMapper()
            .queryByReleaseStmtId(stmt.getId());

        if (snapshots == null || snapshots.isEmpty()) {
            // D2 fork (ADR-011): snapshots are mandatorily propagated at createRelease since
            // sub-task A, so a new release always has rows. Whole-release zero rows ≈ pre-A
            // legacy shape → one-time documented pass; rows elsewhere in the release → this
            // stmt's capture chain broke → deny.
            List<DmProdReleaseStmtSchemaDO> releaseSnapshots = stmt.getReleaseId() == null ? null
                : prodReleaseDal.schemaMapper().queryByReleaseId(stmt.getReleaseId());
            if (releaseSnapshots == null || releaseSnapshots.isEmpty()) {
                return PrecheckConclusion.passWithNotes(List.of(
                    new PrecheckConclusion.Note(PrecheckConclusion.Note.SNAPSHOT_MISSING_LEGACY,
                        "No pre-before schema snapshot for stmt " + stmt.getId()
                            + " — legacy release (zero snapshots release-wide), passed once")));
            }
            return PrecheckConclusion.unverifiable(
                "No pre-before schema snapshot for stmt " + stmt.getId()
                    + " (release " + stmt.getReleaseId() + " has snapshots for other statements — capture-chain gap)");
        }

        // 2. Resolve production DS identity (D3)
        DmDsDO dsDO = dsDal.dsMapper().queryDsIdentityById(stmt.getProdDsId());
        if (dsDO == null) {
            return PrecheckConclusion.unverifiable("Production datasource not found: " + stmt.getProdDsId());
        }

        // 3. Build levelsParam for the production DB (D4)
        Map<UmiTypes, Object> levelsParam;
        try {
            List<String> levels = new ArrayList<>();
            levels.add(dsDO.getDsEnvId().toString());
            levels.add(dsDO.getId().toString());
            levels.add(stmt.getProdDbName());
            DsLevels dsLevels = configService.parseLevels(levels);
            levelsParam = dsLevels.levelsParam();
        } catch (Exception e) {
            return PrecheckConclusion.unverifiable("Failed to parse levels: " + e.getMessage());
        }

        // 4. Evaluate each table
        List<SchemaViolation> violations = new ArrayList<>();
        List<PrecheckConclusion.Note> notes = new ArrayList<>();

        for (DmProdReleaseStmtSchemaDO snap : snapshots) {
            String tableName = snap.getTableName();

            // D5 — shared table: ruled design degradation (ordered replay makes the pre-before
            // baseline ambiguous; the B precheck still guards every stmt) → PASS + note
            if (snap.getSharedFlag() == 1) {
                notes.add(new PrecheckConclusion.Note(PrecheckConclusion.Note.SHARED_TABLE_BASELINE,
                    "Shared table " + tableName
                        + " — equivalence diff skipped (touched by multiple stmts in the release group)"));
                continue;
            }

            // Exclusive table → full equivalence diff
            diffExclusiveTable(snap, tableName, dsDO, levelsParam, violations, notes);
        }

        if (!violations.isEmpty()) {
            return PrecheckConclusion.block(violations);
        }
        if (!notes.isEmpty()) {
            return PrecheckConclusion.passWithNotes(notes);
        }
        return PrecheckConclusion.pass();
    }

    private void diffExclusiveTable(DmProdReleaseStmtSchemaDO snap, String tableName,
                                   DmDsDO dsDO, Map<UmiTypes, Object> levelsParam,
                                   List<SchemaViolation> violations, List<PrecheckConclusion.Note> notes) {
        // Fetch live production table
        RdbTable liveTable = null;
        boolean liveExists = false;
        try {
            Value value = dsSchemaService.realTimeFetchSelectObject(dsDO, levelsParam, tableName);
            if (value instanceof RdbTable) {
                liveTable = (RdbTable) value;
                liveExists = true;
            }
        } catch (Exception e) {
            // D7 — infrastructure error: cannot fetch evidence ⇒ cannot prove safe. Propagate so
            // the single exit in diff() converts it to UNVERIFIABLE (fail-closed, ADR-011).
            throw new RuntimeException("Failed to fetch live production schema for table " + tableName, e);
        }

        boolean snapExists = snap.getExistsFlag() == 1;

        if (!snapExists) {
            // Pre-before: table did not exist. Production should also not have it.
            if (liveExists) {
                violations.add(new SchemaViolation(1, "CREATE TABLE " + tableName,
                    tableName, null, "table to not exist (pre-before absent)", "exists in production"));
            }
            // else: PASS — table correctly absent
            return;
        }

        // Pre-before: table existed. Production should still have it.
        if (!liveExists) {
            violations.add(new SchemaViolation(1, "ALTER/DROP " + tableName,
                tableName, null, "table to exist (pre-before present)", "missing in production"));
            return;
        }

        // Both exist — compare fingerprints
        String liveFingerprint = SchemaFingerprintUtils.fingerprint(liveTable);
        if (liveFingerprint.equals(snap.getFingerprint())) {
            // BLOCK-level structure matches — check WARN-level (ENGINE, comments)
            String liveJson = SchemaFingerprintUtils.canonicalJson(liveTable);
            if (liveJson != null && !liveJson.equals(snap.getSchemaJson())) {
                // D6 — only WARN-level fields changed: ruled design degradation
                // (SchemaFingerprintUtils explicitly excludes ENGINE/comments) → PASS + note
                notes.add(new PrecheckConclusion.Note(PrecheckConclusion.Note.ENGINE_COMMENT_DRIFT,
                    "Table " + tableName
                        + " — WARN-level drift (ENGINE or comment changed, structural fields unchanged)"));
            }
            // else: full match → PASS
            return;
        }

        // BLOCK-level fingerprint mismatch → BLOCK + detail
        violations.add(new SchemaViolation(1, "ALTER " + tableName,
            tableName, null,
            "pre-before fingerprint " + truncate(snap.getFingerprint(), 16),
            "production fingerprint " + truncate(liveFingerprint, 16)));
    }

    // ==================== helpers ====================

    /**
     * Fail-closed master switch (ADR-011 semantics): true = UNVERIFIABLE denies dispatch.
     * false = platform break-glass — UNVERIFIABLE degrades to legacy WARN_SKIP.
     * Default and read-failure both stay true (fail-closed on an unreadable switch).
     */
    private boolean isBlockMode() {
        try {
            Boolean block = systemDal.fetchSystemConf(CONFIG_DIFF_BLOCK_MODE, Boolean.class);
            return block == null || block;
        } catch (Exception e) {
            return true; // default fail-closed
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
