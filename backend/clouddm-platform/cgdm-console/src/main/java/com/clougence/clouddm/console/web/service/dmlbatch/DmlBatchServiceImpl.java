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
package com.clougence.clouddm.console.web.service.dmlbatch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.api.sidecar.session.execute.ResultList;
import com.clougence.clouddm.base.metadata.ds.DataSourceConfig;
import com.clougence.clouddm.base.metadata.ds.DataSourceType;
import com.clougence.clouddm.console.web.component.config.RootUserConfig;
import com.clougence.clouddm.console.web.component.execute.asyntask.AsyncTaskConfig;
import com.clougence.clouddm.console.web.component.governance.TicketSegment;
import com.clougence.clouddm.console.web.component.opsdiag.DiagOpts;
import com.clougence.clouddm.console.web.component.opsdiag.DiagResultUtils;
import com.clougence.clouddm.console.web.component.opsdiag.DiagSession;
import com.clougence.clouddm.console.web.component.opsdiag.DiagTimeoutException;
import com.clougence.clouddm.console.web.component.opsdiag.OpsDiagnosticExecutor;
import com.clougence.clouddm.console.web.global.i18n.DmI18nUtils;
import com.clougence.clouddm.console.web.global.i18n.I18nDmMsgKeys;
import com.clougence.clouddm.console.web.service.asyntask.AsyncTaskServiceService;
import com.clougence.clouddm.console.web.service.dmlbatch.asyntask.DmlBatchAsyncTask;
import com.clougence.clouddm.console.web.service.dmlbatch.asyntask.DmlBatchAsyncTaskConfig;
import com.clougence.clouddm.console.web.service.dmlbatch.asyntask.DmlBatchCheckpoint;
import com.clougence.clouddm.platform.dal.access.ExecutionDal;
import com.clougence.clouddm.platform.dal.access.SystemDal;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.datasource.DmDsDO;
import com.clougence.clouddm.platform.dal.model.execution.AsyncTaskProcessType;
import com.clougence.clouddm.platform.dal.model.execution.DmExecAsyncTaskDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;
import com.clougence.utils.JsonUtils;
import com.clougence.utils.StringUtils;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * B7 lock-free DML engine service (design §4/§5/§9/§11). Channel-C probe shape mirrors
 * {@code GhostServiceImpl.probeForRoute} (never-throw, 10s budget, identifiers validated and
 * embedded as ESCAPED STRING LITERALS — ops-diagnostics §4 / the B4 lesson: MySQL doubles
 * backslashes first, PG doubles quotes only).
 * <p>
 * <b>Probe SQL contracts (full-text pinned by unit tests, both dialects):</b>
 * <ul>
 * <li>MySQL PK: information_schema.KEY_COLUMN_USAGE (constraint_name='PRIMARY',
 *     ordinal_position order) joined to COLUMNS for data_type/numeric_scale;</li>
 * <li>MySQL UK: information_schema.STATISTICS (non_unique=0, index_name&lt;&gt;'PRIMARY',
 *     index_name+seq_in_index order) joined to COLUMNS — carries is_nullable, the N1a handover
 *     obligation #1 (a NULLABLE single-column UK counts as NO KEY: multiple NULLs coexist under
 *     uniqueness and {@code k > lo} is never true for NULL — those rows would silently never
 *     enter any batch while the boundary SELECT terminates normally);</li>
 * <li>PG PK/UK: pg_constraint (contype 'p'/'u') ⋈ pg_class/pg_namespace/pg_attribute with
 *     format_type + attnotnull, conkey order via array_position; the schema predicate is
 *     {@code current_schema()} for an unprefixed statement (consistent with the batch sessions'
 *     default search_path) or the escaped explicit prefix;</li>
 * <li>row counts: B4 calibers verbatim (MySQL information_schema.TABLES.table_rows estimate /
 *     PG GREATEST(reltuples,0)::bigint).</li>
 * </ul>
 * PK columns are NOT NULL by construction in both dialects (免判); the nullability gate applies
 * to UK candidates only.
 */
@Slf4j
@Service
public class DmlBatchServiceImpl implements DmlBatchService {

    /** identifier safety bound (B4 contract: MySQL caps at 64, PG at 63 — 64 covers both). */
    static final int MAX_IDENTIFIER_LENGTH = 64;

    /** task-list bound of the binding lookup (newest first) — ghost TASK_LIST_LIMIT parity. */
    static final int TASK_LIST_LIMIT = 200;

    /** hard timeout (seconds) of one probe statement group; tests may shorten it. */
    private long queryTimeoutSeconds = 10;

    // ---- probe SQL (full-text pinned; the %s slots are PRE-ESCAPED string literals or the
    //      PG current_schema() predicate — never raw user text) ----

    /** MySQL PK columns in key order + type/scale (is_nullable selected for uniformity; PK is NOT NULL by construction). */
    static String mysqlPkSql(String escapedDb, String escapedTable) {
        return "SELECT k.column_name AS column_name, c.data_type AS data_type, " //
                + "c.numeric_scale AS numeric_scale, c.is_nullable AS is_nullable " //
                + "FROM information_schema.key_column_usage k " //
                + "JOIN information_schema.columns c ON c.table_schema = k.table_schema " //
                + "AND c.table_name = k.table_name AND c.column_name = k.column_name " //
                + "WHERE k.table_schema = " + escapedDb + " AND k.table_name = " + escapedTable + " " //
                + "AND k.constraint_name = 'PRIMARY' ORDER BY k.ordinal_position";
    }

    /** MySQL unique-index columns (non_unique=0, PRIMARY excluded) in index_name+seq order with nullability. */
    static String mysqlUkSql(String escapedDb, String escapedTable) {
        return "SELECT s.index_name AS index_name, s.column_name AS column_name, " //
                + "c.data_type AS data_type, c.numeric_scale AS numeric_scale, c.is_nullable AS is_nullable " //
                + "FROM information_schema.statistics s " //
                + "JOIN information_schema.columns c ON c.table_schema = s.table_schema " //
                + "AND c.table_name = s.table_name AND c.column_name = s.column_name " //
                + "WHERE s.table_schema = " + escapedDb + " AND s.table_name = " + escapedTable + " " //
                + "AND s.non_unique = 0 AND s.index_name <> 'PRIMARY' ORDER BY s.index_name, s.seq_in_index";
    }

    /** MySQL B4-caliber row estimate. */
    static String mysqlTableRowsSql(String escapedDb, String escapedTable) {
        return "SELECT table_rows AS row_estimate FROM information_schema.tables " //
                + "WHERE table_type = 'BASE TABLE' AND table_schema = " + escapedDb + " AND table_name = " + escapedTable;
    }

    /** PG PK columns in conkey order + format_type + attnotnull. {@code schemaPredicate} = "nsp.nspname = current_schema()" or an escaped literal comparison. */
    static String pgPkSql(String schemaPredicate, String escapedTable) {
        return "SELECT a.attname AS column_name, format_type(a.atttypid, a.atttypmod) AS data_type, " //
                + "a.attnotnull AS not_null " //
                + "FROM pg_constraint con " //
                + "JOIN pg_class rel ON rel.oid = con.conrelid " //
                + "JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace " //
                + "JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = ANY (con.conkey) " //
                + "WHERE con.contype = 'p' AND " + schemaPredicate + " AND rel.relname = " + escapedTable + " " //
                + "ORDER BY array_position(con.conkey, a.attnum)";
    }

    /** PG unique-constraint columns (contype='u') in conname+conkey order with attnotnull. */
    static String pgUkSql(String schemaPredicate, String escapedTable) {
        return "SELECT con.conname AS index_name, a.attname AS column_name, " //
                + "format_type(a.atttypid, a.atttypmod) AS data_type, a.attnotnull AS not_null, " //
                + "array_position(con.conkey, a.attnum) AS seq_in_index " //
                + "FROM pg_constraint con " //
                + "JOIN pg_class rel ON rel.oid = con.conrelid " //
                + "JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace " //
                + "JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = ANY (con.conkey) " //
                + "WHERE con.contype = 'u' AND " + schemaPredicate + " AND rel.relname = " + escapedTable + " " //
                + "ORDER BY con.conname, array_position(con.conkey, a.attnum)";
    }

    /** PG B4-caliber row estimate (reltuples depends on ANALYZE freshness — the documented estimate caliber). */
    static String pgTableRowsSql(String schemaPredicate, String escapedTable) {
        return "SELECT GREATEST(rel.reltuples, 0)::bigint AS row_estimate " //
                + "FROM pg_class rel JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace " //
                + "WHERE rel.relkind IN ('r','p') AND " + schemaPredicate + " AND rel.relname = " + escapedTable;
    }

    /** the unqualified-statement PG schema predicate (batch sessions run on the default search_path). */
    static final String PG_CURRENT_SCHEMA_PREDICATE = "nsp.nspname = current_schema()";

    @Resource
    private OpsDiagnosticExecutor   diagnosticExecutor;
    @Resource
    private SystemDal               systemDal;
    @Resource
    private AsyncTaskServiceService asyncTaskServiceService;
    @Resource
    private ExecutionDal            executionDal;

    // ==================================================================================
    // version + dialect gate (裁决 #3, N1a handover obligation #2)
    // ==================================================================================

    @Override
    public boolean isBatchRouteSupportedDs(DmDsDO dsDO) {
        if (dsDO == null || dsDO.getDataSourceType() == null) {
            return false;
        }
        Integer major = parseMajorVersion(dsDO.getVersion());
        if (major == null) {
            // blank/unparsable version → conservative status quo (不路由不拒绝): the rewrite SPI
            // does NOT gate versions (N1a check 移交④), so the console gate is the ONLY guard —
            // an unknown version never routes
            return false;
        }
        if (dsDO.getDataSourceType() == DataSourceType.MySQL) {
            return major >= 8; // validated target MySQL 8.0; later majors share the grammar family
        }
        if (dsDO.getDataSourceType() == DataSourceType.PostgreSQL) {
            return major >= 15; // validated target PG 15 (PG majors are single-numbered since 10 —
                                // "15", "15.4" and "PostgreSQL 15.4 on x86_64" all yield 15)
        }
        return false; // every other dialect (including the MySQL-wire variants) keeps the direct chain
    }

    /**
     * The first decimal run of a version string ("8.0.32"→8, "15"→15, "PostgreSQL 15.4 on
     * x86_64"→15); null when the string carries no digits or the run overflows an int.
     * Major-only is the correct granularity for BOTH floors: MySQL 5.7 vs 8.0 differ at the
     * major, and PG has been single-numbered-major since 10.
     */
    static Integer parseMajorVersion(String version) {
        if (StringUtils.isBlank(version)) {
            return null;
        }
        int i = 0;
        while (i < version.length()) {
            if (Character.isDigit(version.charAt(i))) {
                int start = i;
                while (i < version.length() && Character.isDigit(version.charAt(i))) {
                    i++;
                }
                try {
                    return Integer.parseInt(version.substring(start, i));
                } catch (NumberFormatException e) {
                    return null; // absurdly long digit run — unparsable
                }
            }
            i++;
        }
        return null;
    }

    // ==================================================================================
    // batch-key probe (channel C, never-throw)
    // ==================================================================================

    @Override
    public DmlBatchKeyProbe probeForKey(String uid, DmDsDO dsDO, DataSourceConfig dsConfig,
                                        String dbName, String schemaName, String tableName) {
        DmlBatchKeyProbe probe = new DmlBatchKeyProbe();
        probe.setRowThreshold(rowThreshold());
        try {
            validateIdentifier(dbName);
            validateIdentifier(tableName);
            if (StringUtils.isNotBlank(schemaName)) {
                validateIdentifier(schemaName);
            }
            boolean postgres = dsDO.getDataSourceType() == DataSourceType.PostgreSQL;

            final String rowsSql;
            final String pkSql;
            final String ukSql;
            if (postgres) {
                // PG connects ON the target database (DiagOpts.catalog); an explicit statement
                // schema prefix (rare — the shape gate rejects a prefix != dbName) probes that
                // schema, an unprefixed statement probes current_schema() — the same resolution
                // the batch sessions use (default search_path), so probe and execution agree.
                String predicate = StringUtils.isBlank(schemaName) ? PG_CURRENT_SCHEMA_PREDICATE
                    : "nsp.nspname = " + DmlBatchLiterals.escapePgStringLiteral(schemaName);
                String escapedTable = DmlBatchLiterals.escapePgStringLiteral(tableName);
                rowsSql = pgTableRowsSql(predicate, escapedTable);
                pkSql = pgPkSql(predicate, escapedTable);
                ukSql = pgUkSql(predicate, escapedTable);
            } else {
                String escapedDb = DmlBatchLiterals.escapeMySqlStringLiteral(dbName);
                String escapedTable = DmlBatchLiterals.escapeMySqlStringLiteral(tableName);
                rowsSql = mysqlTableRowsSql(escapedDb, escapedTable);
                pkSql = mysqlPkSql(escapedDb, escapedTable);
                ukSql = mysqlUkSql(escapedDb, escapedTable);
            }

            diagnosticExecutor.runInSession(uid, dsDO, dsConfig, diagOpts(postgres ? dbName : null), session -> {
                List<Map<String, String>> rowRows = executeStatement(session, rowsSql);
                if (rowRows.isEmpty()) {
                    return null; // table not found → tableFound=false: silent status quo (the execution chain fails loudly)
                }
                probe.setTableFound(true);
                probe.setTableRows(parseLong(rowRows.get(0).get("row_estimate")));
                selectKey(probe, executeStatement(session, pkSql), executeStatement(session, ukSql), postgres);
                return null;
            });
            if (probe.isTableFound()) {
                probe.setProbed(true);
            }
        } catch (DiagTimeoutException e) {
            log.warn("[DmlBatch] key probe timed out after {}s on dsId={}, table {}.{} — degraded to the direct chain",
                queryTimeoutSeconds, dsDO == null ? null : dsDO.getId(), dbName, tableName);
        } catch (Exception e) {
            // NEVER-THROW contract: including sneaky-thrown checked shapes — degrade, do not block
            log.warn("[DmlBatch] key probe failed on dsId={}, table {}.{} — degraded to the direct chain",
                dsDO == null ? null : dsDO.getId(), dbName, tableName, e);
        }
        return probe;
    }

    /**
     * Key selection over the raw probe rows (design §4.1 contract + handover obligation #1):
     * single-column PK → first single-column NOT NULL UK (deterministic order) → not batchable.
     * Whitelist miss with a key present = UNSUPPORTED (yellow, direct); confirmed absence of any
     * usable key = NO_KEY (the marking seam's DML_NO_KEY rejection input).
     */
    static void selectKey(DmlBatchKeyProbe probe, List<Map<String, String>> pkRows,
                          List<Map<String, String>> ukRows, boolean postgres) {
        if (pkRows.size() == 1) {
            String column = pkRows.get(0).get("column_name");
            String type = mapKeyType(pkRows.get(0), postgres);
            if (type != null && DmlBatchLiterals.isValidKeyColumn(column)) {
                probe.setKeyState(DmlBatchKeyProbe.STATE_OK);
                probe.setKeyColumn(column);
                probe.setKeyType(type);
                return;
            }
            probe.setKeyState(DmlBatchKeyProbe.STATE_UNSUPPORTED);
            probe.setKeyDetail(type == null
                ? "primary key type " + pkRows.get(0).get("data_type")
                : "primary key column " + column + " is not a plain identifier");
            return;
        }
        if (pkRows.size() > 1) {
            probe.setKeyState(DmlBatchKeyProbe.STATE_UNSUPPORTED);
            probe.setKeyDetail("composite primary key (" + joinColumns(pkRows) + ")");
            return;
        }

        // no PK → UK candidates grouped by index name (probe order preserved: MySQL
        // index_name+seq_in_index / PG conname+conkey position — deterministic selection)
        Map<String, List<Map<String, String>>> byIndex = new LinkedHashMap<>();
        for (Map<String, String> row : ukRows) {
            byIndex.computeIfAbsent(row.get("index_name"), k -> new ArrayList<>()).add(row);
        }
        if (byIndex.isEmpty()) {
            probe.setKeyState(DmlBatchKeyProbe.STATE_NO_KEY);
            probe.setKeyDetail("no primary key and no unique key");
            return;
        }
        List<Map<String, String>> singleNotNull = new ArrayList<>();
        List<String> compositeKeys = new ArrayList<>();
        List<String> nullableSingles = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, String>>> entry : byIndex.entrySet()) {
            List<Map<String, String>> cols = entry.getValue();
            if (cols.size() > 1) {
                compositeKeys.add("(" + joinColumns(cols) + ")");
            } else if (isNotNull(cols.get(0), postgres)) {
                singleNotNull.add(cols.get(0));
            } else {
                nullableSingles.add(cols.get(0).get("column_name"));
            }
        }
        if (singleNotNull.isEmpty()) {
            if (!compositeKeys.isEmpty()) {
                // a unique constraint DOES exist (composite) — factually not "no key": yellow, not FAIL
                probe.setKeyState(DmlBatchKeyProbe.STATE_UNSUPPORTED);
                probe.setKeyDetail("composite unique key " + compositeKeys.get(0));
                return;
            }
            // ONLY nullable single-column UKs — handover obligation #1: 视同无键 (NULL rows would
            // silently never enter any batch) → the marking seam's DML_NO_KEY rejection path
            probe.setKeyState(DmlBatchKeyProbe.STATE_NO_KEY);
            probe.setKeyDetail("only nullable unique key (" + String.join(",", nullableSingles)
                               + ") - NULL keys would never enter a batch");
            return;
        }
        Map<String, String> first = singleNotNull.get(0);
        String column = first.get("column_name");
        String type = mapKeyType(first, postgres);
        if (type != null && DmlBatchLiterals.isValidKeyColumn(column)) {
            probe.setKeyState(DmlBatchKeyProbe.STATE_OK);
            probe.setKeyColumn(column);
            probe.setKeyType(type);
            return;
        }
        probe.setKeyState(DmlBatchKeyProbe.STATE_UNSUPPORTED);
        probe.setKeyDetail(type == null
            ? "unique key type " + first.get("data_type")
            : "unique key column " + column + " is not a plain identifier");
    }

    /**
     * v1 key-type whitelist mapping (design §4.1; 裁决 #18 string keys INCLUDED — the collation
     * safety argument lives in the DmlBatchRangeSpec javadoc: uniqueness is enforced in the SAME
     * collation as the cursor comparisons, so collation-equal variants cannot coexist in a
     * unique index and the strict order is collision-free). Excluded: FLOAT/DOUBLE (precision),
     * DECIMAL/numeric with scale > 0, PG text (whitelist is CHAR/VARCHAR — conservative),
     * TIME/YEAR/JSON/ENUM/binary families. Null = not batchable.
     */
    static String mapKeyType(Map<String, String> row, boolean postgres) {
        String dataType = row.get("data_type");
        if (StringUtils.isBlank(dataType)) {
            return null;
        }
        String type = dataType.trim().toLowerCase(java.util.Locale.ROOT);
        if (postgres) {
            switch (type) {
                case "smallint": case "integer": case "bigint":
                case "int2": case "int4": case "int8":
                case "smallserial": case "serial": case "bigserial":
                case "serial2": case "serial4": case "serial8":
                    return com.clougence.clouddm.sdk.sql.editor.rewrite.DmlBatchRangeSpec.KEY_TYPE_INT;
                case "date":
                    return com.clougence.clouddm.sdk.sql.editor.rewrite.DmlBatchRangeSpec.KEY_TYPE_DATETIME;
                default:
                    break;
            }
            if (type.startsWith("timestamp")) { // with/without time zone, optional (p) precision
                return com.clougence.clouddm.sdk.sql.editor.rewrite.DmlBatchRangeSpec.KEY_TYPE_DATETIME;
            }
            if (type.startsWith("numeric")) {
                // DECIMAL0 only: format_type renders "numeric(p,0)"; a bare "numeric" (unbound
                // scale) or scale>0 is outside the whitelist
                return type.endsWith(",0)")
                    ? com.clougence.clouddm.sdk.sql.editor.rewrite.DmlBatchRangeSpec.KEY_TYPE_DECIMAL0 : null;
            }
            if (type.startsWith("character varying") || type.startsWith("character(") || type.equals("character")
                || type.startsWith("bpchar")) {
                return com.clougence.clouddm.sdk.sql.editor.rewrite.DmlBatchRangeSpec.KEY_TYPE_STRING;
            }
            return null;
        }
        switch (type) {
            case "tinyint": case "smallint": case "mediumint": case "int": case "integer": case "bigint":
                return com.clougence.clouddm.sdk.sql.editor.rewrite.DmlBatchRangeSpec.KEY_TYPE_INT;
            case "decimal": case "numeric": {
                Long scale = parseLong(row.get("numeric_scale"));
                return scale != null && scale == 0
                    ? com.clougence.clouddm.sdk.sql.editor.rewrite.DmlBatchRangeSpec.KEY_TYPE_DECIMAL0 : null;
            }
            case "date": case "datetime": case "timestamp":
                return com.clougence.clouddm.sdk.sql.editor.rewrite.DmlBatchRangeSpec.KEY_TYPE_DATETIME;
            case "char": case "varchar":
                return com.clougence.clouddm.sdk.sql.editor.rewrite.DmlBatchRangeSpec.KEY_TYPE_STRING;
            default:
                return null;
        }
    }

    /** MySQL IS_NULLABLE='NO' / PG attnotnull ('t'/'true') — the UK NOT NULL obligation. */
    static boolean isNotNull(Map<String, String> row, boolean postgres) {
        if (postgres) {
            String notNull = row.get("not_null");
            return "t".equalsIgnoreCase(StringUtils.trimToEmpty(notNull))
                || "true".equalsIgnoreCase(StringUtils.trimToEmpty(notNull));
        }
        return "NO".equalsIgnoreCase(StringUtils.trimToEmpty(row.get("is_nullable")));
    }

    private static String joinColumns(List<Map<String, String>> rows) {
        List<String> cols = new ArrayList<>(rows.size());
        for (Map<String, String> row : rows) {
            cols.add(row.get("column_name"));
        }
        return String.join(",", cols);
    }

    // ==================================================================================
    // six-KV parameter plane (fail-safe reads + defensive clamping into the WhiteList ranges)
    // ==================================================================================

    @Override
    public DmlBatchEffectiveParams resolveEffectiveParams() {
        DmlBatchEffectiveParams params = new DmlBatchEffectiveParams();
        params.setBatchSize(clamp(fetchInt(RootUserConfig.Fields.dmlBatchSize, 5000), 1000, 50000));
        params.setIntervalMs(clamp(fetchInt(RootUserConfig.Fields.dmlBatchIntervalMs, 1000), 0, 60000));
        params.setLagThresholdSec(clamp(fetchInt(RootUserConfig.Fields.dmlLagThresholdSec, 10), 1, 3600));
        params.setTimeoutSec(clamp(fetchInt(RootUserConfig.Fields.dmlBatchTimeoutSec, 300), 10, 3600));
        params.setMaxBatchRetries(DmlBatchEffectiveParams.MAX_BATCH_RETRIES);
        return params;
    }

    @Override
    public boolean isAutoRouteEnabled() {
        try {
            Boolean enabled = systemDal.fetchSystemConf(RootUserConfig.Fields.dmlBatchAutoRoute, Boolean.class);
            return enabled == null || enabled; // default true (mirrors the @UserConfigDef defaultValue)
        } catch (Exception e) {
            return true; // fail-safe: a broken switch read never disables silently... (ghostAutoRoute pattern)
        }
    }

    @Override
    public long rowThreshold() {
        try {
            Long threshold = systemDal.fetchSystemConf(RootUserConfig.Fields.dmlRowThreshold, Long.class, 1_000_000L);
            return threshold == null || threshold < 1 ? 1_000_000L : threshold;
        } catch (Exception e) {
            return 1_000_000L;
        }
    }

    private int fetchInt(String key, int defaultValue) {
        try {
            Integer value = systemDal.fetchSystemConf(key, Integer.class, defaultValue);
            return value == null ? defaultValue : value;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // ==================================================================================
    // dispatch-mechanism task submission
    // ==================================================================================

    @Override
    public Long submitForTicket(DmApprovalDO ticket, DmTicketDbStmtDO group, TicketSegment segment,
                                Map<String, Object> inheritedCheckpoint) {
        if (segment.getBatchKey() == null || StringUtils.isBlank(segment.getBatchKey().getColumn())
            || StringUtils.isBlank(segment.getSql())) {
            // a plan WE wrote cannot be incomplete — refuse loudly rather than launch a task that
            // cannot build its batch SQL (the dispatch transaction rolls back; the sweep re-dispatches)
            throw new IllegalStateException("DML_BATCH segment " + segment.getSegIdx()
                                            + " of group " + group.getId() + " carries no frozen batchKey/sql");
        }
        String bizId = UUID.randomUUID().toString().replace("-", "");

        DmlBatchAsyncTaskConfig taskConfig = new DmlBatchAsyncTaskConfig();
        taskConfig.setBizId(bizId);
        taskConfig.setTicketId(ticket.getId());
        taskConfig.setGroupId(group.getId());
        taskConfig.setSegmentIndex(segment.getSegIdx());
        taskConfig.setUserId(ticket.getOwnerUid()); // R19: the submitter's identity + data permissions
        taskConfig.setDsId(group.getDsId());
        taskConfig.setDbName(group.getDbName());
        taskConfig.setStmtSql(segment.getSql());
        taskConfig.setBatchKey(new TicketSegment.BatchKey(
            segment.getBatchKey().getColumn(), segment.getBatchKey().getKeyType()));
        taskConfig.setEffectiveParams(resolveEffectiveParams()); // dispatch-time snapshot (design §11)
        taskConfig.setEstimatedRows(segment.getEstimatedRows());
        taskConfig.setIdempotent(segment.getIdempotent());
        taskConfig.setCheckpoint(seedCheckpoint(inheritedCheckpoint));
        // N2 window-crash resolution (design §7.4): the SKIP verdict rides the segment (set by
        // resumeGroupUnderLock) into the fresh task's first tick; RERUN/first-dispatch stay null
        taskConfig.setPendingResolution(segment.getPendingResolution());

        AsyncTaskConfig config = new AsyncTaskConfig();
        config.setTitle(DmI18nUtils.getMessage(I18nDmMsgKeys.DML_BATCH_ASYNC_TITLE_MESSAGE.name(), segment.getTable()));
        config.setDescription(DmI18nUtils.getMessage(I18nDmMsgKeys.DML_BATCH_ASYNC_DESC_MESSAGE.name(),
            group.getDbName(), segment.getTable()));
        config.setConfigData(JsonUtils.toJson(taskConfig));
        config.setShowInDock(true);
        config.setProcessType(AsyncTaskProcessType.PROGRESS);
        config.setHandlerType(DmlBatchAsyncTask.class);
        config.setBizType(BIZ_TYPE);
        config.setBizId(bizId);
        // Failure contract: submitTask failure THROWS — nothing was started (no session exists in
        // the B7 shape, the inverse direction of the ghost closeQuietly coupling); the caller's
        // transaction rollback leaves the segment PENDING for the sweep re-dispatch row.
        asyncTaskServiceService.submitTask(ticket.getOwnerUid(), config);

        DmExecAsyncTaskDO task = asyncTaskServiceService.queryAsyncTaskByBizId(bizId, BIZ_TYPE);
        return task == null ? null : task.getId();
    }

    /**
     * Fresh or inherited checkpoint (design §7.4): the DONE side only ({cursor, batchesDone,
     * rowsDone, lastDone}) — the intent side is NEVER inherited (a manual retry is the human
     * decision; N2 adds the RERUN/SKIP resolution guard). An UNPARSABLE inherited carrier
     * THROWS: silently starting from a null cursor would REPLAY every already-committed batch —
     * the exact silent double-execution the whole design forbids.
     */
    static DmlBatchCheckpoint seedCheckpoint(Map<String, Object> inheritedCheckpoint) {
        if (inheritedCheckpoint == null || inheritedCheckpoint.isEmpty()) {
            return new DmlBatchCheckpoint();
        }
        try {
            DmlBatchCheckpoint cp = JsonUtils.toObj(JsonUtils.toJson(inheritedCheckpoint), DmlBatchCheckpoint.class);
            if (cp == null) {
                throw new IllegalStateException("null checkpoint");
            }
            cp.setIntent(null);       // done-side only — the fresh task starts with no batch in flight
            cp.setLastTickAt(null);   // the heartbeat belongs to the NEW task's lifecycle
            cp.setLastStatus(null);
            return cp;
        } catch (Exception e) {
            throw new IllegalStateException("Unparsable inherited DML_BATCH checkpoint — refusing to restart the cursor", e);
        }
    }

    // ==================================================================================
    // ticket-bound task lookup (C6 direct reuse, bizType parameterized)
    // ==================================================================================

    @Override
    public DmExecAsyncTaskDO findTaskForTicketGroup(long ticketId, long groupId, Integer segmentIndex) {
        LambdaQueryWrapper<DmExecAsyncTaskDO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DmExecAsyncTaskDO::getBizType, BIZ_TYPE) //
                // Targeted config_data LIKE pre-filter on the compact-JSON binding keys (JsonUtils
                // writes no spaces) — the ghost findTaskForTicketGroup contract verbatim: without
                // it the LIMIT-bounded newest-first scan misses a LONG-RUNNING batch task once
                // newer DML_BATCH tasks pile up (false "task missing" → false dead-judgement over
                // a live loop). Substring over-inclusion (ticketId 90 vs 900) is harmless — the
                // exact parse-equality check below remains the authority.
                .like(DmExecAsyncTaskDO::getConfigData, "\"ticketId\":" + ticketId) //
                .like(DmExecAsyncTaskDO::getConfigData, "\"groupId\":" + groupId) //
                .orderByDesc(DmExecAsyncTaskDO::getId) //
                .last("LIMIT " + TASK_LIST_LIMIT);
        if (segmentIndex != null) {
            wrapper.like(DmExecAsyncTaskDO::getConfigData, "\"segmentIndex\":" + segmentIndex);
        }
        List<DmExecAsyncTaskDO> rows = executionDal.asyncTaskMapper().selectList(wrapper);
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        for (DmExecAsyncTaskDO row : rows) {
            DmlBatchAsyncTaskConfig cfg = parseTaskConfig(row);
            if (cfg == null || cfg.getTicketId() == null || cfg.getTicketId() != ticketId //
                    || cfg.getGroupId() == null || cfg.getGroupId() != groupId) {
                continue;
            }
            if (segmentIndex != null && !segmentIndex.equals(cfg.getSegmentIndex())) {
                continue; // exact segment binding required
            }
            return row;
        }
        return null;
    }

    /** the shared never-throw configData parse (JsonUtils sneaky-throws the CHECKED exception). */
    public static DmlBatchAsyncTaskConfig parseTaskConfig(DmExecAsyncTaskDO task) {
        if (task == null || StringUtils.isBlank(task.getConfigData())) {
            return null;
        }
        try {
            return JsonUtils.toObj(task.getConfigData(), DmlBatchAsyncTaskConfig.class);
        } catch (Exception e) {
            log.warn("[DmlBatch] task configData unparsable, taskId={}", task.getId(), e);
            return null;
        }
    }

    // ==================================================================================
    // channel-C helpers (ops-diagnostics spec: validate → escape → timeout → degrade)
    // ==================================================================================

    /** B4 granularity: per-statement wrapping; PG needs the catalog to reach the target database. */
    private DiagOpts diagOpts(String catalog) {
        return DiagOpts.builder().timeoutSeconds((int) queryTimeoutSeconds).catalog(catalog).build();
    }

    private static List<Map<String, String>> executeStatement(DiagSession session, String sql) {
        ResultList resultList = session.execute(sql);
        String failure = DiagResultUtils.firstFailureMessage(resultList);
        if (failure != null) {
            log.warn("[DmlBatch] probe query failed: {}", failure);
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.CONSOLE_QUERY_EXEC_ERROR.name(), failure));
        }
        return DiagResultUtils.extractRows(resultList);
    }

    /** Non-blank, ≤64 chars, no NUL — rejected before any SQL is built (B4 contract; a rejection degrades the probe, never the ticket). */
    static void validateIdentifier(String name) {
        if (StringUtils.isBlank(name) || name.length() > MAX_IDENTIFIER_LENGTH || name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid identifier for the DML batch probe");
        }
    }

    static Long parseLong(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
