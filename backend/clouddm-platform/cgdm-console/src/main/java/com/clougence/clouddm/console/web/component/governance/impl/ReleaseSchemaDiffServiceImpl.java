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
 *   <li>Shared table (shared_flag=1): WARN (ordered replay makes baseline ambiguous)</li>
 *   <li>No snapshot: WARN_SKIP (RELEASE_SCHEMA_SNAPSHOT_MISSING)</li>
 *   <li>exists_flag=0: production should not have the table</li>
 * </ul>
 * <p>
 * All exceptions → WARN_SKIP (fail-open: 宁漏勿误).
 * Escape hatch: govReleaseSchemaDiffBlock=false → BLOCK degrades to WARN.
 */
@Slf4j
@Service
public class ReleaseSchemaDiffServiceImpl implements ReleaseSchemaDiffService {

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
        if (stmt == null || stmt.getProdDsId() == null || stmt.getId() == null) {
            return PrecheckConclusion.warnSkip("stmt missing required fields");
        }
        try {
            PrecheckConclusion conclusion = doDiff(stmt);
            // Escape hatch: if BLOCK mode disabled, convert BLOCK to WARN
            if (conclusion.isBlock() && !isBlockMode()) {
                return PrecheckConclusion.warnSkip(
                    "Schema drift detected but BLOCK mode is disabled. Drift: "
                        + conclusion.toDetailText());
            }
            return conclusion;
        } catch (Exception e) {
            log.warn("[ReleaseSchemaDiff] Unexpected error for stmt id={}, prodDsId={}, prodDbName={}",
                stmt.getId(), stmt.getProdDsId(), stmt.getProdDbName(), e);
            return PrecheckConclusion.warnSkip("Diff engine error: " + e.getMessage());
        }
    }

    private PrecheckConclusion doDiff(DmProdReleaseStmtDO stmt) {
        // 1. Load frozen snapshots for this release stmt
        List<DmProdReleaseStmtSchemaDO> snapshots = prodReleaseDal.schemaMapper()
            .queryByReleaseStmtId(stmt.getId());

        if (snapshots == null || snapshots.isEmpty()) {
            // No snapshots → WARN_SKIP (compatibility: pre-A releases, or capture failed)
            return PrecheckConclusion.warnSkip("No pre-before schema snapshot for stmt " + stmt.getId());
        }

        // 2. Resolve production DS identity
        DmDsDO dsDO = dsDal.dsMapper().queryDsIdentityById(stmt.getProdDsId());
        if (dsDO == null) {
            return PrecheckConclusion.warnSkip("Production datasource not found: " + stmt.getProdDsId());
        }

        // 3. Build levelsParam for the production DB
        Map<UmiTypes, Object> levelsParam;
        try {
            List<String> levels = new ArrayList<>();
            levels.add(dsDO.getDsEnvId().toString());
            levels.add(dsDO.getId().toString());
            levels.add(stmt.getProdDbName());
            DsLevels dsLevels = configService.parseLevels(levels);
            levelsParam = dsLevels.levelsParam();
        } catch (Exception e) {
            return PrecheckConclusion.warnSkip("Failed to parse levels: " + e.getMessage());
        }

        // 4. Evaluate each table
        List<SchemaViolation> violations = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (DmProdReleaseStmtSchemaDO snap : snapshots) {
            String tableName = snap.getTableName();

            // Shared table → WARN (degraded, ordered replay makes baseline ambiguous)
            if (snap.getSharedFlag() == 1) {
                warnings.add("Shared table " + tableName
                    + " — equivalence diff skipped (touched by multiple stmts in the release group)");
                continue;
            }

            // Exclusive table → full equivalence diff
            diffExclusiveTable(snap, tableName, dsDO, levelsParam, violations, warnings);
        }

        if (!violations.isEmpty()) {
            return PrecheckConclusion.block(violations);
        }
        if (!warnings.isEmpty()) {
            return PrecheckConclusion.warnSkip(String.join("; ", warnings));
        }
        return PrecheckConclusion.pass();
    }

    private void diffExclusiveTable(DmProdReleaseStmtSchemaDO snap, String tableName,
                                   DmDsDO dsDO, Map<UmiTypes, Object> levelsParam,
                                   List<SchemaViolation> violations, List<String> warnings) {
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
            // Infrastructure error → fail-open (宁漏勿误)
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
                // Only WARN-level fields changed
                warnings.add("Table " + tableName
                    + " — WARN-level drift (ENGINE or comment changed, structural fields unchanged)");
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

    private boolean isBlockMode() {
        try {
            Boolean block = systemDal.fetchSystemConf(CONFIG_DIFF_BLOCK_MODE, Boolean.class);
            return block == null || block;
        } catch (Exception e) {
            return true; // default BLOCK
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
