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
package com.clougence.clouddm.console.web.component.governance.impl;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import com.clougence.clouddm.base.metadata.ds.DataSourceConfig;
import com.clougence.clouddm.console.web.component.analysis.QueryAnalysisService;
import com.clougence.clouddm.console.web.component.dsconfig.DmDsConfigService;
import com.clougence.clouddm.console.web.component.dsconfig.mode.DsLevels;
import com.clougence.clouddm.console.web.component.governance.PrecheckConclusion;
import com.clougence.clouddm.console.web.component.governance.ReleaseSchemaPrecheckService;
import com.clougence.clouddm.console.web.component.governance.SchemaViolation;
import com.clougence.clouddm.console.web.component.schema.DsSchemaService;
import com.clougence.clouddm.platform.dal.access.DataSourceDal;
import com.clougence.clouddm.platform.dal.access.SystemDal;
import com.clougence.clouddm.platform.dal.model.datasource.DmDsDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseStmtDO;
import com.clougence.clouddm.sdk.sql.parser.SplitQueryType;
import com.clougence.clouddm.sdk.sql.parser.SplitScript;
import com.clougence.schema.umi.special.rdb.RdbIndex;
import com.clougence.schema.umi.special.rdb.RdbTable;
import com.clougence.schema.umi.struts.Value;
import com.clougence.utils.StringUtils;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * Production schema precondition precheck engine.
 * <p>
 * Splits the stmt's frozen SQL (same splitter as execution), classifies each script by
 * SplitQueryType, extracts table/column/index names via lightweight regex, fetches live
 * production schema via DsSchemaService, and evaluates per-operation preconditions.
 * <p>
 * All exceptions are caught and converted to WARN_SKIP — the service never throws.
 * Design principle: 宁漏勿误.
 */
@Slf4j
@Service
public class ReleaseSchemaPrecheckServiceImpl implements ReleaseSchemaPrecheckService {

    /** Identifier pattern: backtick-quoted, double-quoted, or bareword. */
    private static final String IDENT = "(?:`[^`]+`|\"[^\"]+\"|[a-zA-Z_\\u4e00-\\u9fa5][\\w\\u4e00-\\u9fa5]*)";

    /** Qualified name: one or more identifiers separated by dots. */
    private static final String QIDENT = "(" + IDENT + "(?:\\s*\\.\\s*" + IDENT + ")*)";

    /** System config key for BLOCK/WARN mode switch (escape hatch). Default: BLOCK. */
    private static final String CONFIG_BLOCK_MODE = "govReleaseSchemaPrecheckBlock";

    /**
     * DDL keywords that, when appearing as the first word after ADD/DROP in an ALTER
     * sub-clause, indicate a non-column operation (constraint, index, partition, etc.).
     * Used to avoid misclassifying "ADD CHECK (...)" as "ADD COLUMN CHECK" — which
     * would falsely BLOCK legitimate releases (宁漏勿误).
     */
    private static final Set<String> NON_COLUMN_KEYWORDS = Set.of(
        "INDEX", "KEY", "CONSTRAINT", "PRIMARY", "UNIQUE", "FULLTEXT",
        "SPATIAL", "FOREIGN", "CHECK", "PARTITION", "CHARACTER");

    // ===== Operation patterns =====

    private static final Pattern P_CREATE_TABLE = Pattern.compile(
        "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?" + QIDENT, Pattern.CASE_INSENSITIVE);

    private static final Pattern P_DROP_TABLE = Pattern.compile(
        "DROP\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?" + QIDENT, Pattern.CASE_INSENSITIVE);

    private static final Pattern P_ALTER_TABLE = Pattern.compile(
        "ALTER\\s+TABLE\\s+" + QIDENT, Pattern.CASE_INSENSITIVE);

    private static final Pattern P_RENAME_TABLE = Pattern.compile(
        "RENAME\\s+TABLE\\s+" + QIDENT + "\\s+TO\\s+" + QIDENT, Pattern.CASE_INSENSITIVE);

    private static final Pattern P_TRUNCATE = Pattern.compile(
        "TRUNCATE\\s+(?:TABLE\\s+)?" + QIDENT, Pattern.CASE_INSENSITIVE);

    private static final Pattern P_CREATE_INDEX = Pattern.compile(
        "CREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+(" + IDENT + ")\\s+ON\\s+" + QIDENT + "\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);

    private static final Pattern P_DROP_INDEX = Pattern.compile(
        "DROP\\s+INDEX\\s+(" + IDENT + ")(?:\\s+ON\\s+" + QIDENT + ")?", Pattern.CASE_INSENSITIVE);

    private static final Pattern P_INSERT = Pattern.compile(
        "INSERT\\s+INTO\\s+" + QIDENT, Pattern.CASE_INSENSITIVE);

    private static final Pattern P_UPDATE = Pattern.compile(
        "UPDATE\\s+" + QIDENT, Pattern.CASE_INSENSITIVE);

    private static final Pattern P_DELETE = Pattern.compile(
        "DELETE\\s+FROM\\s+" + QIDENT, Pattern.CASE_INSENSITIVE);

    // ===== ALTER TABLE sub-clause patterns =====

    private static final Pattern P_ALTER_ADD_COLUMN = Pattern.compile(
        "ADD\\s+(?:COLUMN\\s+)?" + QIDENT, Pattern.CASE_INSENSITIVE);

    private static final Pattern P_ALTER_DROP_COLUMN = Pattern.compile(
        "DROP\\s+(?:COLUMN\\s+)?" + QIDENT, Pattern.CASE_INSENSITIVE);

    private static final Pattern P_ALTER_MODIFY_COLUMN = Pattern.compile(
        "MODIFY\\s+(?:COLUMN\\s+)?" + QIDENT, Pattern.CASE_INSENSITIVE);

    private static final Pattern P_ALTER_CHANGE_COLUMN = Pattern.compile(
        "CHANGE\\s+(?:COLUMN\\s+)?" + QIDENT + "\\s+" + QIDENT, Pattern.CASE_INSENSITIVE);

    private static final Pattern P_ALTER_RENAME_TO = Pattern.compile(
        "RENAME\\s+(?:TO|AS)\\s+" + QIDENT, Pattern.CASE_INSENSITIVE);

    @Resource
    private DsSchemaService      dsSchemaService;
    @Resource
    private QueryAnalysisService  analysisService;
    @Resource
    private DmDsConfigService    configService;
    @Resource
    private DataSourceDal         dsDal;
    @Resource
    private SystemDal            systemDal;

    @Override
    public PrecheckConclusion precheck(DmProdReleaseStmtDO stmt) {
        if (stmt == null || stmt.getProdDsId() == null || StringUtils.isBlank(stmt.getSqlContent())) {
            return PrecheckConclusion.warnSkip("stmt missing required fields (prodDsId or sqlContent)");
        }
        try {
            PrecheckConclusion conclusion = doPrecheck(stmt);
            // Escape hatch: if BLOCK mode is disabled, convert BLOCK to WARN_SKIP
            if (conclusion.isBlock() && !isBlockMode()) {
                return PrecheckConclusion.warnSkip(
                    "Schema precheck violations detected but BLOCK mode is disabled. Violations: "
                        + conclusion.toDetailText());
            }
            return conclusion;
        } catch (Exception e) {
            log.warn("[ReleaseSchemaPrecheck] Unexpected error for stmt id={}, prodDsId={}, prodDbName={}",
                stmt.getId(), stmt.getProdDsId(), stmt.getProdDbName(), e);
            return PrecheckConclusion.warnSkip("Precheck engine error: " + e.getMessage());
        }
    }

    // ==================== core engine ====================

    @Override
    public Set<String> extractTables(String sqlContent) {
        if (StringUtils.isBlank(sqlContent)) {
            return Collections.emptySet();
        }
        Set<String> tables = new HashSet<>();
        // Use regex patterns on the raw SQL text — no DS config or split needed.
        // This is a lightweight extraction for snapshot scope and shared-table detection.
        extractTableNames(sqlContent, P_CREATE_TABLE, 1, tables);
        extractTableNames(sqlContent, P_DROP_TABLE, 1, tables);
        extractTableNames(sqlContent, P_ALTER_TABLE, 1, tables);
        extractTableNames(sqlContent, P_TRUNCATE, 1, tables);
        extractTableNames(sqlContent, P_RENAME_TABLE, 1, tables);
        extractTableNames(sqlContent, P_RENAME_TABLE, 2, tables);
        extractTableNames(sqlContent, P_CREATE_INDEX, 2, tables);
        extractTableNames(sqlContent, P_INSERT, 1, tables);
        extractTableNames(sqlContent, P_UPDATE, 1, tables);
        extractTableNames(sqlContent, P_DELETE, 1, tables);
        return tables;
    }

    private void extractTableNames(String sql, Pattern p, int group, Set<String> out) {
        Matcher m = p.matcher(sql);
        while (m.find()) {
            String name = lastComponent(m.group(group));
            if (StringUtils.isNotBlank(name)) {
                out.add(name);
            }
        }
    }

    private PrecheckConclusion doPrecheck(DmProdReleaseStmtDO stmt) {
        // 1. Resolve DS identity and config
        DmDsDO dsDO = dsDal.dsMapper().queryDsIdentityById(stmt.getProdDsId());
        if (dsDO == null) {
            return PrecheckConclusion.warnSkip("Production datasource not found: " + stmt.getProdDsId());
        }

        DataSourceConfig dsConfig;
        try {
            dsConfig = configService.fetchDsConfigFromExists(stmt.getProdDsId());
        } catch (Exception e) {
            return PrecheckConclusion.warnSkip("Failed to fetch DS config: " + e.getMessage());
        }
        if (dsConfig == null) {
            return PrecheckConclusion.warnSkip("DS config is null for prodDsId: " + stmt.getProdDsId());
        }

        // 2. Build levelsParam (same as createReleaseStmtJob L359-364)
        DsLevels dsLevels;
        try {
            List<String> levels = new ArrayList<>();
            levels.add(dsDO.getDsEnvId().toString());
            levels.add(dsDO.getId().toString());
            levels.add(stmt.getProdDbName());
            dsLevels = configService.parseLevels(levels);
        } catch (Exception e) {
            return PrecheckConclusion.warnSkip("Failed to parse levels: " + e.getMessage());
        }
        Map<com.clougence.schema.umi.struts.UmiTypes, Object> levelsParam = dsLevels.levelsParam();

        // 3. Split SQL (same splitter as execution path)
        List<SplitScript> scripts;
        try (StringReader reader = new StringReader(stmt.getSqlContent());
             Stream<SplitScript> stream = analysisService.analysisSplitStream(
                 dsConfig, reader, Collections.emptyList(), 1, 0)) {
            scripts = stream.toList();
        } catch (Exception e) {
            return PrecheckConclusion.warnSkip("SQL split failed: " + e.getMessage());
        }
        if (scripts.isEmpty()) {
            return PrecheckConclusion.warnSkip("SQL split produced no scripts");
        }

        // 4. Evaluate each script
        SchemaDeltaModel delta = new SchemaDeltaModel();
        Map<String, RdbTable> liveCache = new HashMap<>();
        List<SchemaViolation> violations = new ArrayList<>();

        for (int i = 0; i < scripts.size(); i++) {
            SplitScript script = scripts.get(i);
            int sqlIndex = i + 1;
            String sql = script.getScript();
            if (StringUtils.isBlank(sql)) {
                continue;
            }
            String head = truncate(sql.trim(), 80);
            Set<SplitQueryType> types = script.getType();
            OperationKind kind = classify(types, sql);

            switch (kind) {
                case CREATE_TABLE:
                    evaluateCreateTable(sql, sqlIndex, head, delta, liveCache, dsDO, levelsParam, violations);
                    break;
                case DROP_TABLE:
                    evaluateDropTable(sql, sqlIndex, head, delta, liveCache, dsDO, levelsParam, violations);
                    break;
                case RENAME_TABLE:
                    evaluateRenameTable(sql, sqlIndex, head, delta, liveCache, dsDO, levelsParam, violations);
                    break;
                case TRUNCATE:
                    evaluateTruncate(sql, sqlIndex, head, delta, liveCache, dsDO, levelsParam, violations);
                    break;
                case ALTER_TABLE_MULTI:
                    evaluateAlterTable(sql, sqlIndex, head, delta, liveCache, dsDO, levelsParam, violations);
                    break;
                case CREATE_INDEX:
                    evaluateCreateIndex(sql, sqlIndex, head, delta, liveCache, dsDO, levelsParam, violations);
                    break;
                case DROP_INDEX:
                    evaluateDropIndexStandalone(sql, sqlIndex, head, delta, liveCache, dsDO, levelsParam, violations);
                    break;
                case DML:
                    evaluateDml(sql, sqlIndex, head, delta, liveCache, dsDO, levelsParam, violations);
                    break;
                case UNKNOWN:
                default:
                    // Cannot classify — 宁漏勿误: don't block
                    log.debug("[ReleaseSchemaPrecheck] Unclassified SQL at sql#{}: {}", sqlIndex, head);
                    break;
            }
        }

        if (violations.isEmpty()) {
            return PrecheckConclusion.pass();
        }
        return PrecheckConclusion.block(violations);
    }

    // ==================== classification ====================

    enum OperationKind {
        CREATE_TABLE, DROP_TABLE, RENAME_TABLE, TRUNCATE,
        ALTER_TABLE_MULTI, CREATE_INDEX, DROP_INDEX, DML, UNKNOWN
    }

    private OperationKind classify(Set<SplitQueryType> types, String sql) {
        if (types == null || types.isEmpty()) {
            return classifyByKeyword(sql);
        }
        // Check specific types
        if (containsAny(types, SplitQueryType.CREATE_TABLE)) return OperationKind.CREATE_TABLE;
        if (containsAny(types, SplitQueryType.DROP_TABLE)) return OperationKind.DROP_TABLE;
        if (containsAny(types, SplitQueryType.RENAME_TABLE)) return OperationKind.RENAME_TABLE;
        if (containsAny(types, SplitQueryType.TRUNCATE_TABLE)) return OperationKind.TRUNCATE;
        if (containsAny(types, SplitQueryType.ALTER_TABLE)) return OperationKind.ALTER_TABLE_MULTI;
        if (containsAny(types, SplitQueryType.ADD_INDEX)) return OperationKind.CREATE_INDEX;
        if (containsAny(types, SplitQueryType.DROP_INDEX)) return OperationKind.DROP_INDEX;
        if (containsAny(types, SplitQueryType.INSERT, SplitQueryType.UPDATE, SplitQueryType.DELETE, SplitQueryType.MERGE)) {
            return OperationKind.DML;
        }
        // Fallback to keyword classification
        return classifyByKeyword(sql);
    }

    private OperationKind classifyByKeyword(String sql) {
        String upper = sql.toUpperCase().trim();
        if (upper.startsWith("CREATE") && upper.contains(" TABLE ")) return OperationKind.CREATE_TABLE;
        if (upper.startsWith("DROP") && upper.contains(" TABLE ")) return OperationKind.DROP_TABLE;
        if (upper.startsWith("RENAME") && upper.contains(" TABLE ")) return OperationKind.RENAME_TABLE;
        if (upper.startsWith("TRUNCATE")) return OperationKind.TRUNCATE;
        if (upper.startsWith("ALTER") && upper.contains(" TABLE ")) return OperationKind.ALTER_TABLE_MULTI;
        if (upper.startsWith("CREATE") && upper.contains(" INDEX ")) return OperationKind.CREATE_INDEX;
        if (upper.startsWith("DROP") && upper.contains(" INDEX ")) return OperationKind.DROP_INDEX;
        if (upper.startsWith("INSERT") || upper.startsWith("UPDATE") || upper.startsWith("DELETE") || upper.startsWith("REPLACE")) {
            return OperationKind.DML;
        }
        return OperationKind.UNKNOWN;
    }

    private boolean containsAny(Set<SplitQueryType> types, SplitQueryType... targets) {
        for (SplitQueryType t : targets) {
            if (types.contains(t)) return true;
        }
        return false;
    }

    // ==================== evaluation: per operation kind ====================

    private void evaluateCreateTable(String sql, int sqlIndex, String head,
                                    SchemaDeltaModel delta, Map<String, RdbTable> liveCache,
                                    DmDsDO dsDO, Map<com.clougence.schema.umi.struts.UmiTypes, Object> levelsParam,
                                    List<SchemaViolation> violations) {
        Matcher m = P_CREATE_TABLE.matcher(sql);
        if (!m.find()) {
            log.debug("[ReleaseSchemaPrecheck] CREATE_TABLE regex did not match sql#{}: {}", sqlIndex, head);
            return; // can't extract table name — 宁漏勿误
        }
        String tableName = lastComponent(m.group(1));
        boolean ifNotExists = sql.toUpperCase().contains("IF NOT EXISTS");

        if (ifNotExists) {
            // IF NOT EXISTS — table can exist or not, always PASS
            delta.applyCreateTable(tableName);
            return;
        }
        boolean exists = checkTableExists(tableName, delta, liveCache, dsDO, levelsParam);
        if (exists) {
            violations.add(new SchemaViolation(sqlIndex, head, tableName, null,
                "table to not exist", "exists"));
        }
        delta.applyCreateTable(tableName);
    }

    private void evaluateDropTable(String sql, int sqlIndex, String head,
                                    SchemaDeltaModel delta, Map<String, RdbTable> liveCache,
                                    DmDsDO dsDO, Map<com.clougence.schema.umi.struts.UmiTypes, Object> levelsParam,
                                    List<SchemaViolation> violations) {
        Matcher m = P_DROP_TABLE.matcher(sql);
        if (!m.find()) {
            return;
        }
        String tableName = lastComponent(m.group(1));
        boolean ifExists = sql.toUpperCase().contains("IF EXISTS");

        if (ifExists) {
            delta.applyDropTable(tableName);
            return;
        }
        boolean exists = checkTableExists(tableName, delta, liveCache, dsDO, levelsParam);
        if (!exists) {
            violations.add(new SchemaViolation(sqlIndex, head, tableName, null,
                "table to exist", "missing"));
        }
        delta.applyDropTable(tableName);
    }

    private void evaluateRenameTable(String sql, int sqlIndex, String head,
                                     SchemaDeltaModel delta, Map<String, RdbTable> liveCache,
                                     DmDsDO dsDO, Map<com.clougence.schema.umi.struts.UmiTypes, Object> levelsParam,
                                     List<SchemaViolation> violations) {
        Matcher m = P_RENAME_TABLE.matcher(sql);
        if (!m.find()) {
            return;
        }
        String oldName = lastComponent(m.group(1));
        String newName = lastComponent(m.group(2));

        boolean oldExists = checkTableExists(oldName, delta, liveCache, dsDO, levelsParam);
        if (!oldExists) {
            violations.add(new SchemaViolation(sqlIndex, head, oldName, null,
                "source table to exist", "missing"));
        }
        boolean newExists = checkTableExists(newName, delta, liveCache, dsDO, levelsParam);
        if (newExists) {
            violations.add(new SchemaViolation(sqlIndex, head, newName, null,
                "target table to not exist", "exists"));
        }
        delta.applyRenameTable(oldName, newName);
    }

    private void evaluateTruncate(String sql, int sqlIndex, String head,
                                  SchemaDeltaModel delta, Map<String, RdbTable> liveCache,
                                  DmDsDO dsDO, Map<com.clougence.schema.umi.struts.UmiTypes, Object> levelsParam,
                                  List<SchemaViolation> violations) {
        Matcher m = P_TRUNCATE.matcher(sql);
        if (!m.find()) {
            return;
        }
        String tableName = lastComponent(m.group(1));
        boolean exists = checkTableExists(tableName, delta, liveCache, dsDO, levelsParam);
        if (!exists) {
            violations.add(new SchemaViolation(sqlIndex, head, tableName, null,
                "table to exist", "missing"));
        }
    }

    private void evaluateCreateIndex(String sql, int sqlIndex, String head,
                                     SchemaDeltaModel delta, Map<String, RdbTable> liveCache,
                                     DmDsDO dsDO, Map<com.clougence.schema.umi.struts.UmiTypes, Object> levelsParam,
                                     List<SchemaViolation> violations) {
        Matcher m = P_CREATE_INDEX.matcher(sql);
        if (!m.find()) {
            return;
        }
        String indexName = stripId(m.group(1));
        String tableName = lastComponent(m.group(2));
        String colPart = m.group(3);
        List<String> cols = parseColumnList(colPart);

        // Table must exist
        if (!checkTableExists(tableName, delta, liveCache, dsDO, levelsParam)) {
            violations.add(new SchemaViolation(sqlIndex, head, tableName, null,
                "table to exist", "missing"));
            return;
        }
        // Index must not exist
        if (checkIndexExists(tableName, indexName, delta, liveCache, dsDO, levelsParam)) {
            violations.add(new SchemaViolation(sqlIndex, head, tableName,
                "index " + indexName, "index to not exist", "exists"));
        }
        // Referenced columns must exist
        RdbTable rdbTable = getLiveTable(tableName, delta, liveCache, dsDO, levelsParam);
        for (String col : cols) {
            if (!checkColumnExists(tableName, col, delta, liveCache, dsDO, levelsParam)) {
                violations.add(new SchemaViolation(sqlIndex, head, tableName,
                    "column " + col, "column to exist", "missing"));
            }
        }
        delta.applyAddIndex(tableName, indexName);
    }

    private void evaluateDropIndexStandalone(String sql, int sqlIndex, String head,
                                             SchemaDeltaModel delta, Map<String, RdbTable> liveCache,
                                             DmDsDO dsDO, Map<com.clougence.schema.umi.struts.UmiTypes, Object> levelsParam,
                                             List<SchemaViolation> violations) {
        Matcher m = P_DROP_INDEX.matcher(sql);
        if (!m.find()) {
            return;
        }
        String indexName = stripId(m.group(1));
        String tableName = m.group(2) != null ? lastComponent(m.group(2)) : null;

        if (tableName != null) {
            if (!checkTableExists(tableName, delta, liveCache, dsDO, levelsParam)) {
                violations.add(new SchemaViolation(sqlIndex, head, tableName, null,
                    "table to exist", "missing"));
                return;
            }
            if (!checkIndexExists(tableName, indexName, delta, liveCache, dsDO, levelsParam)) {
                violations.add(new SchemaViolation(sqlIndex, head, tableName,
                    "index " + indexName, "index to exist", "missing"));
            }
            delta.applyDropIndex(tableName, indexName);
        } else {
            // No ON table clause — can't verify, 宁漏勿误
            log.debug("[ReleaseSchemaPrecheck] DROP INDEX without ON clause at sql#{}: {}", sqlIndex, head);
        }
    }

    private void evaluateDml(String sql, int sqlIndex, String head,
                            SchemaDeltaModel delta, Map<String, RdbTable> liveCache,
                            DmDsDO dsDO, Map<com.clougence.schema.umi.struts.UmiTypes, Object> levelsParam,
                            List<SchemaViolation> violations) {
        // Try INSERT, UPDATE, DELETE patterns
        String tableName = null;
        Matcher mi = P_INSERT.matcher(sql);
        if (mi.find()) {
            tableName = lastComponent(mi.group(1));
        }
        if (tableName == null) {
            Matcher mu = P_UPDATE.matcher(sql);
            if (mu.find()) {
                tableName = lastComponent(mu.group(1));
            }
        }
        if (tableName == null) {
            Matcher md = P_DELETE.matcher(sql);
            if (md.find()) {
                tableName = lastComponent(md.group(1));
            }
        }
        if (tableName == null) {
            return; // can't extract — 宁漏勿误
        }
        boolean exists = checkTableExists(tableName, delta, liveCache, dsDO, levelsParam);
        if (!exists) {
            violations.add(new SchemaViolation(sqlIndex, head, tableName, null,
                "table to exist", "missing"));
        }
    }

    // ==================== ALTER TABLE sub-clause evaluation ====================

    private void evaluateAlterTable(String sql, int sqlIndex, String head,
                                    SchemaDeltaModel delta, Map<String, RdbTable> liveCache,
                                    DmDsDO dsDO, Map<com.clougence.schema.umi.struts.UmiTypes, Object> levelsParam,
                                    List<SchemaViolation> violations) {
        Matcher m = P_ALTER_TABLE.matcher(sql);
        if (!m.find()) {
            return;
        }
        String tableName = lastComponent(m.group(1));

        // Get the body after "ALTER TABLE <name>"
        int bodyStart = m.end();
        String body = sql.substring(bodyStart);

        // Split body into sub-clauses by top-level commas
        List<String> subClauses = splitByTopLevelComma(body);

        for (String clause : subClauses) {
            String trimmed = clause.trim();
            if (trimmed.isEmpty()) continue;
            evaluateAlterSubClause(trimmed, tableName, sqlIndex, head,
                delta, liveCache, dsDO, levelsParam, violations);
        }
    }

    private void evaluateAlterSubClause(String clause, String tableName, int sqlIndex, String head,
                                       SchemaDeltaModel delta, Map<String, RdbTable> liveCache,
                                       DmDsDO dsDO, Map<com.clougence.schema.umi.struts.UmiTypes, Object> levelsParam,
                                       List<SchemaViolation> violations) {
        String upper = clause.toUpperCase();

        // RENAME TO <new>
        if (upper.startsWith("RENAME") && (upper.contains(" TO ") || upper.contains(" AS "))) {
            Matcher mr = P_ALTER_RENAME_TO.matcher(clause);
            if (mr.find()) {
                String newName = lastComponent(mr.group(1));
                if (!checkTableExists(tableName, delta, liveCache, dsDO, levelsParam)) {
                    violations.add(new SchemaViolation(sqlIndex, head, tableName, null,
                        "source table to exist", "missing"));
                }
                if (checkTableExists(newName, delta, liveCache, dsDO, levelsParam)) {
                    violations.add(new SchemaViolation(sqlIndex, head, newName, null,
                        "target table to not exist", "exists"));
                }
                delta.applyRenameTable(tableName, newName);
            }
            return;
        }

        // ADD COLUMN <col> — must be a column add, not a constraint/index/partition add.
        // Uses first-word check (not contains) to avoid misclassifying "ADD CHECK (...)"
        // as a column add, which would falsely BLOCK legitimate releases.
        if (upper.startsWith("ADD") && isColumnTargetClause(upper, 3)) {
            Matcher mc = P_ALTER_ADD_COLUMN.matcher(clause);
            if (mc.find()) {
                String colName = lastComponent(mc.group(1));
                if (!checkTableExists(tableName, delta, liveCache, dsDO, levelsParam)) {
                    violations.add(new SchemaViolation(sqlIndex, head, tableName, null,
                        "table to exist", "missing"));
                    return;
                }
                if (checkColumnExists(tableName, colName, delta, liveCache, dsDO, levelsParam)) {
                    violations.add(new SchemaViolation(sqlIndex, head, tableName,
                        "column " + colName, "column to not exist", "exists"));
                }
                delta.applyAddColumn(tableName, colName);
                return;
            }
        }

        // DROP COLUMN <col> — must be a column drop, not an index/constraint/partition drop.
        if (upper.startsWith("DROP") && isColumnTargetClause(upper, 4)) {
            Matcher mc = P_ALTER_DROP_COLUMN.matcher(clause);
            if (mc.find()) {
                String colName = lastComponent(mc.group(1));
                if (!checkTableExists(tableName, delta, liveCache, dsDO, levelsParam)) {
                    violations.add(new SchemaViolation(sqlIndex, head, tableName, null,
                        "table to exist", "missing"));
                    return;
                }
                if (!checkColumnExists(tableName, colName, delta, liveCache, dsDO, levelsParam)) {
                    violations.add(new SchemaViolation(sqlIndex, head, tableName,
                        "column " + colName, "column to exist", "missing"));
                }
                delta.applyDropColumn(tableName, colName);
                return;
            }
        }

        // MODIFY COLUMN <col>
        if (upper.startsWith("MODIFY")) {
            Matcher mc = P_ALTER_MODIFY_COLUMN.matcher(clause);
            if (mc.find()) {
                String colName = lastComponent(mc.group(1));
                if (!checkTableExists(tableName, delta, liveCache, dsDO, levelsParam)) {
                    violations.add(new SchemaViolation(sqlIndex, head, tableName, null,
                        "table to exist", "missing"));
                    return;
                }
                if (!checkColumnExists(tableName, colName, delta, liveCache, dsDO, levelsParam)) {
                    violations.add(new SchemaViolation(sqlIndex, head, tableName,
                        "column " + colName, "column to exist", "missing"));
                }
                return;
            }
        }

        // CHANGE COLUMN <old> <new>
        if (upper.startsWith("CHANGE")) {
            Matcher mc = P_ALTER_CHANGE_COLUMN.matcher(clause);
            if (mc.find()) {
                String oldCol = lastComponent(mc.group(1));
                String newCol = lastComponent(mc.group(2));
                if (!checkTableExists(tableName, delta, liveCache, dsDO, levelsParam)) {
                    violations.add(new SchemaViolation(sqlIndex, head, tableName, null,
                        "table to exist", "missing"));
                    return;
                }
                if (!checkColumnExists(tableName, oldCol, delta, liveCache, dsDO, levelsParam)) {
                    violations.add(new SchemaViolation(sqlIndex, head, tableName,
                        "column " + oldCol, "column to exist", "missing"));
                }
                if (checkColumnExists(tableName, newCol, delta, liveCache, dsDO, levelsParam) && !oldCol.equalsIgnoreCase(newCol)) {
                    violations.add(new SchemaViolation(sqlIndex, head, tableName,
                        "column " + newCol, "column to not exist", "exists"));
                }
                delta.applyDropColumn(tableName, oldCol);
                delta.applyAddColumn(tableName, newCol);
                return;
            }
        }

        // ADD INDEX/KEY — excludes FOREIGN KEY because "ADD FOREIGN KEY (c) REFERENCES p (id)"
        // has two parenthetical groups, which garbles extractIndexColumnsFromClause.
        if (upper.startsWith("ADD") && (upper.contains("INDEX") || upper.contains("KEY"))
            && !upper.contains("FOREIGN")) {
            String idxName = extractIndexNameFromClause(clause);
            List<String> idxCols = extractIndexColumnsFromClause(clause);
            if (!checkTableExists(tableName, delta, liveCache, dsDO, levelsParam)) {
                violations.add(new SchemaViolation(sqlIndex, head, tableName, null,
                    "table to exist", "missing"));
                return;
            }
            if (idxName != null && checkIndexExists(tableName, idxName, delta, liveCache, dsDO, levelsParam)) {
                violations.add(new SchemaViolation(sqlIndex, head, tableName,
                    "index " + idxName, "index to not exist", "exists"));
            }
            for (String col : idxCols) {
                if (!checkColumnExists(tableName, col, delta, liveCache, dsDO, levelsParam)) {
                    violations.add(new SchemaViolation(sqlIndex, head, tableName,
                        "column " + col, "column to exist", "missing"));
                }
            }
            if (idxName != null) {
                delta.applyAddIndex(tableName, idxName);
            }
            return;
        }

        // DROP INDEX/KEY — excludes FOREIGN KEY because FK symbols live in foreignKeys,
        // not in indices/uniqueKeys/pk; checking them as indices would falsely BLOCK.
        if (upper.startsWith("DROP") && (upper.contains("INDEX") || upper.contains("KEY"))
            && !upper.contains("FOREIGN")) {
            String idxName = extractIndexNameFromClause(clause);
            if (!checkTableExists(tableName, delta, liveCache, dsDO, levelsParam)) {
                violations.add(new SchemaViolation(sqlIndex, head, tableName, null,
                    "table to exist", "missing"));
                return;
            }
            if (idxName != null && !checkIndexExists(tableName, idxName, delta, liveCache, dsDO, levelsParam)) {
                violations.add(new SchemaViolation(sqlIndex, head, tableName,
                    "index " + idxName, "index to exist", "missing"));
            }
            if (idxName != null) {
                delta.applyDropIndex(tableName, idxName);
            }
            return;
        }

        // Unclassified sub-clause — 宁漏勿误
        log.debug("[ReleaseSchemaPrecheck] Unclassified ALTER sub-clause: {}", clause);
    }

    // ==================== existence checks (liveSchema ⊕ delta) ====================

    private boolean checkTableExists(String tableName, SchemaDeltaModel delta,
                                     Map<String, RdbTable> liveCache, DmDsDO dsDO,
                                     Map<com.clougence.schema.umi.struts.UmiTypes, Object> levelsParam) {
        if (StringUtils.isBlank(tableName)) return false;
        if (delta.isDropped(tableName)) return false;
        if (delta.isCreated(tableName)) return true;
        RdbTable rdbTable = getLiveTable(tableName, delta, liveCache, dsDO, levelsParam);
        return rdbTable != null;
    }

    private boolean checkColumnExists(String tableName, String colName, SchemaDeltaModel delta,
                                      Map<String, RdbTable> liveCache, DmDsDO dsDO,
                                      Map<com.clougence.schema.umi.struts.UmiTypes, Object> levelsParam) {
        if (StringUtils.isBlank(tableName) || StringUtils.isBlank(colName)) return false;
        if (delta.isColumnDropped(tableName, colName)) return false;
        if (delta.isColumnAdded(tableName, colName)) return true;
        RdbTable rdbTable = getLiveTable(tableName, delta, liveCache, dsDO, levelsParam);
        if (rdbTable == null || rdbTable.getColumns() == null) return false;
        return rdbTable.getColumns().containsKey(colName);
    }

    private boolean checkIndexExists(String tableName, String idxName, SchemaDeltaModel delta,
                                     Map<String, RdbTable> liveCache, DmDsDO dsDO,
                                     Map<com.clougence.schema.umi.struts.UmiTypes, Object> levelsParam) {
        if (StringUtils.isBlank(tableName) || StringUtils.isBlank(idxName)) return false;
        if (delta.isIndexDropped(tableName, idxName)) return false;
        if (delta.isIndexAdded(tableName, idxName)) return true;
        RdbTable rdbTable = getLiveTable(tableName, delta, liveCache, dsDO, levelsParam);
        if (rdbTable == null || rdbTable.getIndices() == null) return false;
        for (RdbIndex idx : rdbTable.getIndices()) {
            if (idxName.equalsIgnoreCase(idx.getName())) return true;
        }
        // Also check unique keys and primary key
        if (rdbTable.getUniqueKeys() != null) {
            for (var uk : rdbTable.getUniqueKeys()) {
                if (idxName.equalsIgnoreCase(uk.getName())) return true;
            }
        }
        if (rdbTable.getPrimaryKey() != null && idxName.equalsIgnoreCase(rdbTable.getPrimaryKey().getName())) {
            return true;
        }
        return false;
    }

    /**
     * Get the RdbTable for a table name. Caches per-precheck-call (not cross-stmt).
     * Returns null if the table doesn't exist or can't be fetched.
     */
    private RdbTable getLiveTable(String tableName, SchemaDeltaModel delta,
                                  Map<String, RdbTable> liveCache, DmDsDO dsDO,
                                  Map<com.clougence.schema.umi.struts.UmiTypes, Object> levelsParam) {
        if (StringUtils.isBlank(tableName)) return null;
        if (delta.isCreated(tableName)) {
            // Table was created by delta in this stmt — no live schema to fetch
            return liveCache.get(tableName); // may be null — that's fine, column checks will handle
        }
        if (liveCache.containsKey(tableName)) {
            return liveCache.get(tableName); // may be null (table doesn't exist)
        }
        RdbTable rdbTable = null;
        try {
            Value value = dsSchemaService.realTimeFetchSelectObject(dsDO, levelsParam, tableName);
            if (value instanceof RdbTable) {
                rdbTable = (RdbTable) value;
            }
        } catch (Exception e) {
            log.warn("[ReleaseSchemaPrecheck] Failed to fetch live schema for table {}: {}", tableName, e.getMessage());
            // Schema fetch failure is an infrastructure error — propagate so the outer
            // try-catch in precheck() converts it to WARN_SKIP (fail-open: 宁漏勿误).
            // Returning null here would cause checkTableExists to report "table missing",
            // which could falsely BLOCK a legitimate release.
            throw new RuntimeException("Failed to fetch live schema for table " + tableName, e);
        }
        liveCache.put(tableName, rdbTable);
        return rdbTable;
    }

    // ==================== helpers ====================

    /**
     * Determine whether an ALTER sub-clause (already uppercased) starting at {@code prefixLen}
     * (3 for "ADD", 4 for "DROP") targets a column rather than a constraint/index/partition.
     * Returns true if the COLUMN keyword is explicit or the first word is a bareword column name
     * (not a DDL keyword). Returns false for "ADD CHECK", "ADD UNIQUE", "DROP PARTITION", etc.
     */
    static boolean isColumnTargetClause(String upperClause, int prefixLen) {
        String after = upperClause.substring(prefixLen).trim();
        if (after.isEmpty()) return false;
        String firstWord = after.split("\\s+")[0];
        if ("COLUMN".equals(firstWord)) return true;   // explicit COLUMN keyword
        return !NON_COLUMN_KEYWORDS.contains(firstWord); // bareword column name
    }

    private boolean isBlockMode() {
        try {
            Boolean block = systemDal.fetchSystemConf(CONFIG_BLOCK_MODE, Boolean.class);
            return block == null || block;
        } catch (Exception e) {
            return true; // default BLOCK
        }
    }

    /**
     * Split a string by commas at the top parenthesis depth (not inside parentheses).
     */
    static List<String> splitByTopLevelComma(String text) {
        List<String> result = new ArrayList<>();
        int depth = 0;
        int start = 0;
        boolean inBacktick = false;
        boolean inDQuote = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '`') inBacktick = !inBacktick;
            else if (c == '"') inDQuote = !inDQuote;
            else if (!inBacktick && !inDQuote) {
                if (c == '(') depth++;
                else if (c == ')') depth--;
                else if (c == ',' && depth == 0) {
                    result.add(text.substring(start, i));
                    start = i + 1;
                }
            }
        }
        if (start < text.length()) {
            result.add(text.substring(start));
        }
        return result;
    }

    /**
     * Extract the last component of a qualified identifier and strip quotes.
     * e.g., "`db`.`table`" → "table", "`table`" → "table", "table" → "table"
     */
    static String lastComponent(String qualified) {
        if (qualified == null || qualified.isEmpty()) return null;
        // Find last '.' not inside backticks or quotes
        int lastDot = -1;
        boolean inBacktick = false;
        boolean inDQuote = false;
        for (int i = 0; i < qualified.length(); i++) {
            char c = qualified.charAt(i);
            if (c == '`') inBacktick = !inBacktick;
            else if (c == '"') inDQuote = !inDQuote;
            else if (c == '.' && !inBacktick && !inDQuote) lastDot = i;
        }
        String last = lastDot >= 0 ? qualified.substring(lastDot + 1).trim() : qualified.trim();
        return stripId(last);
    }

    static String stripId(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.startsWith("`") && s.endsWith("`") && s.length() >= 2) {
            return s.substring(1, s.length() - 1);
        }
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    static List<String> parseColumnList(String colPart) {
        if (colPart == null || colPart.trim().isEmpty()) return Collections.emptyList();
        List<String> cols = new ArrayList<>();
        for (String part : colPart.split(",")) {
            String name = stripId(part.trim());
            // Strip length/type info: "col(10)" → "col"
            int parenIdx = name.indexOf('(');
            if (parenIdx > 0) name = name.substring(0, parenIdx).trim();
            // Strip ASC/DESC
            name = name.replaceAll("(?i)\\s+(ASC|DESC)$", "").trim();
            if (!name.isEmpty()) {
                cols.add(name);
            }
        }
        return cols;
    }

    static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }

    static String extractIndexNameFromClause(String clause) {
        // Try to extract index name from "ADD INDEX <name> (...)" or "DROP INDEX <name>"
        Matcher m = Pattern.compile(
            "(?:INDEX|KEY)\\s+(" + IDENT + ")\\s*\\(", Pattern.CASE_INSENSITIVE).matcher(clause);
        if (m.find()) return stripId(m.group(1));
        // Try without paren: "DROP INDEX `idx`"
        m = Pattern.compile("(?:INDEX|KEY)\\s+(" + IDENT + ")(?:\\s|$)", Pattern.CASE_INSENSITIVE).matcher(clause);
        if (m.find()) return stripId(m.group(1));
        return null; // unnamed index
    }

    static List<String> extractIndexColumnsFromClause(String clause) {
        int parenStart = clause.indexOf('(');
        int parenEnd = clause.lastIndexOf(')');
        if (parenStart < 0 || parenEnd < 0 || parenEnd <= parenStart) return Collections.emptyList();
        return parseColumnList(clause.substring(parenStart + 1, parenEnd));
    }
}
