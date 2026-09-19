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
package com.clougence.clouddm.console.web.service.space;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.api.sidecar.session.execute.ResultList;
import com.clougence.clouddm.base.metadata.ds.DataSourceConfig;
import com.clougence.clouddm.base.metadata.ds.DataSourceType;
import com.clougence.clouddm.console.web.component.dsconfig.DmDsConfigService;
import com.clougence.clouddm.console.web.component.opsdiag.DiagOpts;
import com.clougence.clouddm.console.web.component.opsdiag.DiagResultUtils;
import com.clougence.clouddm.console.web.component.opsdiag.DiagSession;
import com.clougence.clouddm.console.web.component.opsdiag.DiagTimeoutException;
import com.clougence.clouddm.console.web.component.opsdiag.OpsDiagnosticExecutor;
import com.clougence.clouddm.console.web.global.i18n.DmI18nUtils;
import com.clougence.clouddm.console.web.global.i18n.I18nDmMsgKeys;
import com.clougence.clouddm.console.web.global.i18n.I18nRdpMsgKeys;
import com.clougence.clouddm.console.web.model.fo.space.SpaceDatabasesFO;
import com.clougence.clouddm.console.web.model.fo.space.SpaceTablesFO;
import com.clougence.clouddm.console.web.model.vo.session.SessionInstanceVO;
import com.clougence.clouddm.console.web.model.vo.space.SpaceDbVO;
import com.clougence.clouddm.console.web.model.vo.space.SpaceDatabasesVO;
import com.clougence.clouddm.console.web.model.vo.space.SpaceTableVO;
import com.clougence.clouddm.console.web.model.vo.space.SpaceTablesVO;
import com.clougence.clouddm.platform.dal.access.DataSourceDal;
import com.clougence.clouddm.platform.dal.model.LifeCycleState;
import com.clougence.clouddm.platform.dal.model.datasource.DmDsDO;
import com.clougence.utils.StringUtils;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * Table-space usage snapshot service (B4), design 09-19-table-space-usage.
 * <p>
 * Read-only diagnostics through channel C on the shared {@link OpsDiagnosticExecutor} (short-lived
 * worker session; per-statement timeout granularity — the B3/B4 shape). The executor's neutral
 * {@link DiagTimeoutException} is translated here into {@code SPACE_QUERY_TIMEOUT}; a statement
 * ERROR fails the whole (single-SQL) request with {@code SPACE_QUERY_FAILED} carrying the raw
 * reason — there is no block-level degradation because every endpoint is one coherent snapshot.
 * <p>
 * Dialects (design §2, dispatched by dsType):
 * <ul>
 * <li>MySQL 8.0: information_schema.tables aggregates. All InnoDB figures are estimates served
 *     from the instance statistics cache ({@code information_schema_stats_expiry}, ≤24h stale —
 *     the page copy says so; the platform never touches session variables or runs ANALYZE).
 *     Tables the DS account cannot see silently disappear (IS visibility rule) — no privilege
 *     error and no privilegeHint surface exists for this branch;</li>
 * <li>PG 15 (2026-09-19 smoke ruling — instance-wide database level): the database level lists
 *     EVERY non-template database of the instance through the shared {@code pg_database} catalog
 *     with {@code pg_database_size} (callable from any connection, no target-db privilege needed);
 *     the breakdown columns stay null there because per-database internals are unreachable from a
 *     single connection. The table-level drill-down opens its session ON the selected database
 *     (DiagOpts.catalog → rdbCatalog → QueryServiceImpl DEFAULT_DATABASE override — the workbench
 *     switch-db mechanism) and lists every schema of it via pg_class/pg_namespace with
 *     pg_table_size / pg_indexes_size / pg_total_relation_size — exact physical bytes. It needs
 *     CONNECT on the target db (PUBLIC by default; a revoked grant surfaces as a createSession
 *     failure through the existing translation path).</li>
 * </ul>
 * dbName safety (design §2.3): validated server-side (non-blank, ≤{@value #MAX_IDENTIFIER_LENGTH}
 * chars, no NUL) before any remote call — fail-fast, zero side effects (the B2 kill-guard
 * precedent). On MySQL it is then embedded into M2 as a <b>string literal</b> (single quotes;
 * identifier quoting there would parse as a column reference). On PG it is NEVER embedded into
 * SQL — it only selects the connection target (catalog), so no escaping surface exists.
 */
@Slf4j
@Service
public class SpaceServiceImpl implements SpaceService {

    /** Whitelist: strictly the datasource planning (MySQL 8.0 + PG 15), same shape as B2/B3 (design §3). */
    static final Set<DataSourceType> SUPPORTED = EnumSet.of(DataSourceType.MySQL, DataSourceType.PostgreSQL);

    static final String BRANCH_MYSQL_8  = "MYSQL_8";
    static final String BRANCH_POSTGRES = "POSTGRES";

    /** Identifier safety bound (design §2.3): MySQL caps identifiers at 64 chars (PG at 63 — 64 covers both plans). */
    static final int    MAX_IDENTIFIER_LENGTH = 64;

    // ---- MySQL 8.0 branch SQL (design §2.1; system schemas excluded, BASE TABLE only) ----
    static final String SQL_MY_DATABASES  = "SELECT table_schema AS db_name, COUNT(*) AS table_count, " //
            + "SUM(table_rows) AS row_estimate, SUM(data_length) AS data_bytes, SUM(index_length) AS index_bytes, " //
            + "SUM(data_free) AS free_bytes, SUM(data_length + index_length) AS total_bytes " //
            + "FROM information_schema.tables " //
            + "WHERE table_type = 'BASE TABLE' " //
            + "AND table_schema NOT IN ('information_schema','mysql','sys','performance_schema') " //
            + "GROUP BY table_schema ORDER BY total_bytes DESC";

    // ---- PostgreSQL 15 branch SQL (design §2.2; instance-wide database level, 2026-09-19 smoke ruling) ----
    /** Shared-catalog database level: every non-template db of the INSTANCE with its exact total size; breakdown columns unreachable cross-database → NULL. */
    static final String SQL_PG_DATABASES = "SELECT d.datname AS db_name, NULL AS table_count, NULL AS row_estimate, " //
            + "NULL AS data_bytes, NULL AS index_bytes, NULL AS free_bytes, pg_database_size(d.oid) AS total_bytes " //
            + "FROM pg_database d WHERE NOT d.datistemplate ORDER BY total_bytes DESC";
    /** Table level, run ON the target database (DiagOpts.catalog): every schema at once, system/other-session temp schemas excluded. */
    static final String SQL_PG_TABLES    = "SELECT n.nspname AS schema_name, c.relname AS table_name, " //
            + "GREATEST(c.reltuples,0)::bigint AS row_estimate, " //
            + "pg_table_size(c.oid) AS data_bytes, pg_indexes_size(c.oid) AS index_bytes, NULL AS free_bytes, " //
            + "pg_total_relation_size(c.oid) AS total_bytes " //
            + "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace " //
            + "WHERE c.relkind IN ('r','p') AND n.nspname NOT IN ('pg_catalog','information_schema','pg_toast') " //
            + "AND NOT pg_is_other_temp_schema(n.oid) " //
            + "ORDER BY total_bytes DESC";

    /** Hard timeout (seconds) for one remote operation; tests may shorten it. */
    private long                  queryTimeoutSeconds = 10;

    @Resource
    private DataSourceDal         dsDal;
    @Resource
    private DmDsConfigService     dmDsConfigService;
    @Resource
    private OpsDiagnosticExecutor diagnosticExecutor;

    // ==================================================================================
    // listInstances (B2/B3 shape: tenant ∩ whitelist ∩ not-deleted, sorted by display name)
    // ==================================================================================

    @Override
    public List<SessionInstanceVO> listInstances(String puid, String uid) {
        List<DmDsDO> dsList = dsDal.dsMapper().listByOwner(puid);
        List<SessionInstanceVO> result = new ArrayList<>();
        if (dsList == null) {
            return result;
        }
        for (DmDsDO dsDO : dsList) {
            if (!isUsable(dsDO) || !SUPPORTED.contains(dsDO.getDataSourceType())) {
                continue;
            }
            SessionInstanceVO vo = new SessionInstanceVO();
            vo.setDsId(dsDO.getId());
            vo.setDsName(StringUtils.isNotBlank(dsDO.getInstanceDesc()) ? dsDO.getInstanceDesc() : dsDO.getHost());
            vo.setDsType(dsDO.getDataSourceType().getTypeName());
            result.add(vo);
        }
        result.sort((a, b) -> {
            String nameA = a.getDsName() == null ? "" : a.getDsName();
            String nameB = b.getDsName() == null ? "" : b.getDsName();
            return nameA.compareToIgnoreCase(nameB);
        });
        return result;
    }

    // ==================================================================================
    // databases (MySQL: one M1 statement; PG: current_database probe + P1 in the same session)
    // ==================================================================================

    @Override
    public SpaceDatabasesVO databases(String puid, String uid, SpaceDatabasesFO fo) {
        DmDsDO dsDO = requireOwnedSupportedDs(puid, fo == null ? null : fo.getDsId());
        DataSourceConfig dsConfig = dmDsConfigService.fetchDsConfigFromExists(dsDO.getId());
        boolean postgres = isPostgres(dsDO.getDataSourceType());
        SpaceDatabasesVO vo = new SpaceDatabasesVO();
        vo.setBranch(postgres ? BRANCH_POSTGRES : BRANCH_MYSQL_8);
        // PG lists the INSTANCE's databases from the shared catalog — the session stays on the
        // datasource default database; no probe and no per-db connection needed at this level
        String sql = postgres ? SQL_PG_DATABASES : SQL_MY_DATABASES;
        try {
            return diagnosticExecutor.runInSession(uid, dsDO, dsConfig, diagOpts(null), session -> {
                vo.setDbs(mapDatabases(executeStatement(session, sql)));
                return vo;
            });
        } catch (DiagTimeoutException e) {
            log.warn("[SpaceUsage] databases query timed out after {}s on dsId={}", queryTimeoutSeconds, dsDO.getId());
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.SPACE_QUERY_TIMEOUT.name()));
        }
    }

    // ==================================================================================
    // tables (identifier validate → escape → M2/P2)
    // ==================================================================================

    @Override
    public SpaceTablesVO tables(String puid, String uid, SpaceTablesFO fo) {
        DmDsDO dsDO = requireOwnedSupportedDs(puid, fo == null ? null : fo.getDsId());
        String dbName = fo.getDbName();
        // injection guard (design §2.3): the name is embedded into the statement, so validate it
        // before opening any diagnostic session — fail fast, zero remote calls (B2 kill-guard shape)
        validateIdentifier(dbName);
        DataSourceConfig dsConfig = dmDsConfigService.fetchDsConfigFromExists(dsDO.getId());
        boolean postgres = isPostgres(dsDO.getDataSourceType());
        // PG: dbName selects the CONNECTION target (rdbCatalog → DEFAULT_DATABASE override) and is
        // never embedded into SQL — the statement lists every schema of that database.
        // MySQL: dbName is embedded into M2 as an escaped string literal.
        String sql = postgres ? SQL_PG_TABLES : myTablesSql(escapeMySqlStringLiteral(dbName));
        SpaceTablesVO vo = new SpaceTablesVO();
        vo.setDbName(dbName);
        try {
            return diagnosticExecutor.runInSession(uid, dsDO, dsConfig, diagOpts(postgres ? dbName : null), session -> {
                vo.setTables(mapTables(executeStatement(session, sql)));
                return vo;
            });
        } catch (DiagTimeoutException e) {
            log.warn("[SpaceUsage] tables query timed out after {}s on dsId={}", queryTimeoutSeconds, dsDO.getId());
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.SPACE_QUERY_TIMEOUT.name()));
        }
    }

    /** Table-level MySQL statement with the already-escaped schema name embedded (design §2.1 M2). */
    static String myTablesSql(String escapedDbName) {
        return "SELECT table_name, table_rows AS row_estimate, data_length AS data_bytes, " //
                + "index_length AS index_bytes, data_free AS free_bytes, data_length + index_length AS total_bytes " //
                + "FROM information_schema.tables " //
                + "WHERE table_type = 'BASE TABLE' AND table_schema = " + escapedDbName + " " //
                + "ORDER BY total_bytes DESC";
    }

    // ==================================================================================
    // identifier safety (design §2.3 — validate, then quote as a string literal)
    // ==================================================================================

    /** Non-blank, ≤64 chars, no NUL — rejected with COMM_BAD_ARG_ERROR before any SQL is built. */
    static void validateIdentifier(String name) {
        if (StringUtils.isBlank(name) || name.length() > MAX_IDENTIFIER_LENGTH || name.indexOf('\0') >= 0) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nRdpMsgKeys.COMM_BAD_ARG_ERROR.name()));
        }
    }

    /**
     * MySQL string-literal quoting for the {@code table_schema = <value>} comparison: single-quote
     * wrap, inner backslash doubling FIRST (default sql_mode has no NO_BACKSLASH_ESCAPES, so {@code \}
     * is the escape character — {@code \'} would otherwise break out of the literal), then inner
     * single-quote doubling. Under a non-default NO_BACKSLASH_ESCAPES mode the doubled backslash only
     * loses equivalence (matches nothing) — never safety.
     */
    static String escapeMySqlStringLiteral(String name) {
        return '\'' + name.replace("\\", "\\\\").replace("'", "''") + '\'';
    }


    // ==================================================================================
    // statement execution & row mapping (unified snake_case column set, design §2)
    // ==================================================================================

    /**
     * B4 granularity: per-statement wrapping (createSession included), read-only autoCommit shape.
     * {@code catalog} non-null (PG table-level drill-down only) pins the session connection to the
     * target database — PG connections are single-database, and cross-database SQL does not exist.
     */
    private DiagOpts diagOpts(String catalog) {
        return DiagOpts.builder().timeoutSeconds((int) queryTimeoutSeconds).catalog(catalog).build();
    }

    /**
     * Runs one snapshot statement. Unlike B3 there is no block-level degradation: every endpoint
     * is a single coherent snapshot, so a statement ERROR fails the whole request with
     * SPACE_QUERY_FAILED carrying the raw reason (design §4). Timeouts surface as the executor's
     * neutral signal and are translated at the endpoint level.
     */
    private static List<Map<String, String>> executeStatement(DiagSession session, String sql) {
        ResultList resultList = session.execute(sql);
        String failure = DiagResultUtils.firstFailureMessage(resultList);
        if (failure != null) {
            log.warn("[SpaceUsage] snapshot query failed: {}", failure);
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.SPACE_QUERY_FAILED.name(), failure));
        }
        return DiagResultUtils.extractRows(resultList);
    }

    private static List<SpaceDbVO> mapDatabases(List<Map<String, String>> rows) {
        List<SpaceDbVO> dbs = new ArrayList<>();
        for (Map<String, String> row : rows) {
            SpaceDbVO vo = new SpaceDbVO();
            vo.setDbName(row.get("db_name"));
            vo.setTableCount(parseLong(row.get("table_count")));
            vo.setRowEstimate(parseLong(row.get("row_estimate")));
            vo.setDataBytes(parseLong(row.get("data_bytes")));
            vo.setIndexBytes(parseLong(row.get("index_bytes")));
            // PG projects NULL AS free_bytes (no free-space equivalent) — the frontend renders '-'
            vo.setFreeBytes(parseLong(row.get("free_bytes")));
            vo.setTotalBytes(parseLong(row.get("total_bytes")));
            dbs.add(vo);
        }
        return dbs;
    }

    private static List<SpaceTableVO> mapTables(List<Map<String, String>> rows) {
        List<SpaceTableVO> tables = new ArrayList<>();
        for (Map<String, String> row : rows) {
            SpaceTableVO vo = new SpaceTableVO();
            // PG only (cross-schema drill-down); MySQL M2 has no schema_name column → null → column hidden
            vo.setSchemaName(row.get("schema_name"));
            vo.setTableName(row.get("table_name"));
            vo.setRowEstimate(parseLong(row.get("row_estimate")));
            vo.setDataBytes(parseLong(row.get("data_bytes")));
            vo.setIndexBytes(parseLong(row.get("index_bytes")));
            vo.setFreeBytes(parseLong(row.get("free_bytes")));
            vo.setTotalBytes(parseLong(row.get("total_bytes")));
            tables.add(vo);
        }
        return tables;
    }

    private static Long parseLong(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ==================================================================================
    // ownership & whitelist validation (B2/B3 pattern, SPACE_* keys per design §4)
    // ==================================================================================

    /** Tenant ownership + lifecycle + type whitelist; not-found semantics never leak existence. */
    private DmDsDO requireOwnedSupportedDs(String puid, Long dsId) {
        if (dsId == null) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.SESSION_DS_NOT_FOUND.name()));
        }
        List<DmDsDO> owned = dsDal.dsMapper().listByOwnerAndIds(puid, Collections.singletonList(dsId));
        DmDsDO dsDO = (owned == null || owned.isEmpty()) ? null : owned.get(0);
        if (dsDO == null || !isUsable(dsDO)) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.SESSION_DS_NOT_FOUND.name()));
        }
        if (!SUPPORTED.contains(dsDO.getDataSourceType())) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.SPACE_DS_TYPE_UNSUPPORTED.name()));
        }
        return dsDO;
    }

    private static boolean isUsable(DmDsDO dsDO) {
        return dsDO != null && dsDO.getDataSourceType() != null //
                && dsDO.getLifeCycleState() != LifeCycleState.DELETED && dsDO.getLifeCycleState() != LifeCycleState.DELETING;
    }

    static boolean isPostgres(DataSourceType dsType) {
        return dsType == DataSourceType.PostgreSQL;
    }
}
