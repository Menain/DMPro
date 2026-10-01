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
package com.clougence.clouddm.console.web.service.ghost;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.api.sidecar.session.execute.ResultList;
import com.clougence.clouddm.base.metadata.ds.DataSourceConfig;
import com.clougence.clouddm.base.metadata.ds.DataSourceType;
import com.clougence.clouddm.base.metadata.ds.SslMode;
import com.clougence.clouddm.base.metadata.ds.tools.GhostPluginConfig;
import com.clougence.clouddm.console.web.component.config.RootUserConfig;
import com.clougence.clouddm.console.web.component.dsconfig.DmDsConfigService;
import com.clougence.clouddm.console.web.component.execute.ToolsService;
import com.clougence.clouddm.console.web.component.execute.asyntask.AsyncTaskConfig;
import com.clougence.clouddm.console.web.component.opsdiag.DiagOpts;
import com.clougence.clouddm.console.web.component.opsdiag.DiagResultUtils;
import com.clougence.clouddm.console.web.component.opsdiag.DiagSession;
import com.clougence.clouddm.console.web.component.opsdiag.DiagTimeoutException;
import com.clougence.clouddm.console.web.component.opsdiag.OpsDiagnosticExecutor;
import com.clougence.clouddm.console.web.global.i18n.DmI18nUtils;
import com.clougence.clouddm.console.web.global.i18n.I18nDmMsgKeys;
import com.clougence.clouddm.console.web.global.i18n.I18nRdpMsgKeys;
import com.clougence.clouddm.console.web.model.fo.ghost.GhostExecuteFO;
import com.clougence.clouddm.console.web.model.fo.ghost.GhostPrecheckFO;
import com.clougence.clouddm.console.web.model.vo.ghost.GhostBlockVO;
import com.clougence.clouddm.console.web.model.vo.ghost.GhostExecuteVO;
import com.clougence.clouddm.console.web.model.vo.ghost.GhostOpAuditObj;
import com.clougence.clouddm.console.web.model.vo.ghost.GhostPrecheckVO;
import com.clougence.clouddm.console.web.model.vo.ghost.GhostRouteProbe;
import com.clougence.clouddm.console.web.model.vo.ghost.GhostSuggestionVO;
import com.clougence.clouddm.console.web.model.vo.ghost.GhostTableStatVO;
import com.clougence.clouddm.console.web.model.vo.ghost.GhostTaskVO;
import com.clougence.clouddm.console.web.service.asyntask.AsyncTaskServiceService;
import com.clougence.clouddm.console.web.service.ghost.asyntask.GhostAsyncTask;
import com.clougence.clouddm.console.web.service.ghost.asyntask.GhostAsyncTaskConfig;
import com.clougence.clouddm.platform.dal.access.AuthDal;
import com.clougence.clouddm.platform.dal.access.DataSourceDal;
import com.clougence.clouddm.platform.dal.access.ExecutionDal;
import com.clougence.clouddm.platform.dal.access.SystemDal;
import com.clougence.clouddm.platform.dal.model.LifeCycleState;
import com.clougence.clouddm.platform.dal.model.auth.DmAuthUserDO;
import com.clougence.clouddm.platform.dal.model.datasource.DmDsDO;
import com.clougence.clouddm.platform.dal.model.execution.AsyncTaskProcessType;
import com.clougence.clouddm.platform.dal.model.execution.AsyncTaskStatus;
import com.clougence.clouddm.platform.dal.model.execution.DmExecAsyncTaskDO;
import com.clougence.clouddm.sdk.execute.tools.ToolSessionContextDTO;
import com.clougence.clouddm.sdk.execute.tools.ToolUtils;
import com.clougence.clouddm.sdk.model.ghost.GhostCutOverType;
import com.clougence.clouddm.sdk.model.ghost.GhostLaunchDTO;
import com.clougence.clouddm.sdk.model.ghost.GhostMethod;
import com.clougence.clouddm.sdk.model.ghost.GhostOptionsDTO;
import com.clougence.clouddm.sdk.model.ghost.GhostStatusDTO;
import com.clougence.clouddm.sdk.model.ghost.GhostTailRequestDTO;
import com.clougence.clouddm.sdk.model.ghost.GhostTailResponseDTO;
import com.clougence.utils.JsonUtils;
import com.clougence.utils.StringUtils;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * Lock-free DDL (gh-ost) console service (B6 S4, design 09-17-online-ddl-activation §4).
 * <p>
 * Session chain: {@code toolsService.createSession(uid, "GHOST", ctx)} with
 * {@code ctx.configuration} = {@link GhostLaunchDTO} JSON (the FakerServiceImpl:172/196
 * precedent), tracking through {@code dm_exec_async_task} (bizType=GHOST, bizId=sessionId;
 * {@code configData} doubles as the R7 old-table persistence carrier — Step 0 ruling, no new
 * table). Every tool-session call runs under the LAUNCHING uid resolved from the task record, so
 * any DM_GHOST_EXEC holder of the same tenant realm can manage the migration (the governance
 * puid "admin semantics"); foreign-tenant sessions are rejected with not-found semantics.
 * <p>
 * Read-only precheck probes and the R7 old-table DROP go through channel C on the shared
 * {@link OpsDiagnosticExecutor} (ops-diagnostics spec): identifier validation BEFORE any remote
 * call, value positions escaped as string literals (backslash doubling first — the B4/spec §4
 * contract), the DROP additionally restricted to gh-ost's own {@code [A-Za-z0-9_$]} old-table
 * name shape and quoted as an identifier with backtick doubling.
 * <p>
 * Tool errors carry the plugin's stable {@code CODE: detail} message prefix
 * ({@code GhostToolException}); {@link #translateGhostFailure} maps the prefix onto the design §7
 * i18n matrix (GHOST_EXEC_FAILED is further classified by the gh-ost FATAL detail content —
 * privilege / topology / binlog / resource / heartbeat).
 */
@Slf4j
@Service
public class GhostServiceImpl implements GhostService, GhostMethod {

    /** R3: plain MySQL only — mirrors the plugin's authoritative checkDataSource gate. */
    static final Set<DataSourceType> SUPPORTED = EnumSet.of(DataSourceType.MySQL);

    /** identifier safety bound (B4 contract: MySQL caps identifiers at 64 chars). */
    static final int                 MAX_IDENTIFIER_LENGTH = 64;

    /** gh-ost old-table names derive from the launch-time [\w$]+ identifier + "_YYYYMMDDHHMMSS_del". */
    static final Pattern             OLD_TABLE_NAME_PATTERN = Pattern.compile("[A-Za-z0-9_$]{1,64}");

    /** plugin error-code prefixes (GhostToolException contract — stable cross-module vocabulary). */
    static final String              CODE_BAD_LAUNCH_CONFIG   = "GHOST_BAD_LAUNCH_CONFIG";
    static final String              CODE_DS_TYPE_UNSUPPORTED = "GHOST_DS_TYPE_UNSUPPORTED";
    static final String              CODE_TUNNEL_UNSUPPORTED  = "GHOST_TUNNEL_UNSUPPORTED";
    static final String              CODE_STMT_UNSUPPORTED    = "GHOST_STMT_UNSUPPORTED";
    static final String              CODE_SCHEMA_MISMATCH     = "GHOST_SCHEMA_MISMATCH";
    static final String              CODE_BINARY_MISSING      = "GHOST_BINARY_MISSING";
    static final String              CODE_ALREADY_STARTED     = "GHOST_ALREADY_STARTED";
    static final String              CODE_NOT_INITIALIZED     = "GHOST_NOT_INITIALIZED";
    static final String              CODE_EXEC_FAILED         = "GHOST_EXEC_FAILED";
    static final String              CODE_ABORTED             = "GHOST_ABORTED";

    private static final Pattern     GHOST_CODE_PATTERN       = Pattern.compile("GHOST_[A-Z_]+");

    /** R4 suggestion code (frontend branches on it; reason text is already translated). */
    static final String              SUGGEST_LARGE_TABLE      = "LARGE_TABLE_SUGGEST_LOCKFREE";

    /** thresholds defaults — must mirror the RootUserConfig @UserConfigDef defaultValue. */
    static final int                 DEFAULT_SIZE_THRESHOLD_MB = 1024;
    static final long                DEFAULT_ROW_THRESHOLD     = 10_000_000L;

    /** task-list bound (newest first). */
    static final int                 TASK_LIST_LIMIT           = 200;

    /** hard timeout (seconds) of one precheck statement group; tests may shorten it. */
    private long                     queryTimeoutSeconds       = 10;
    /** hard timeout (seconds) of the old-table DROP operation (a huge table drop can take a while). */
    private long                     dropTimeoutSeconds        = 30;

    @Resource
    private DataSourceDal            dsDal;
    @Resource
    private DmDsConfigService        dmDsConfigService;
    @Resource
    private OpsDiagnosticExecutor    diagnosticExecutor;
    @Resource
    private ToolsService             toolsService;
    @Resource
    private AsyncTaskServiceService  asyncTaskServiceService;
    @Resource
    private ExecutionDal             executionDal;
    @Resource
    private SystemDal                systemDal;
    @Resource
    private AuthDal                  authDal;
    // @Lazy: the completion service reaches back into GhostService (sweep liveness probe) —
    //   safe-side proxy injection (governance component iron rule).
    @org.springframework.context.annotation.Lazy
    @Resource
    private com.clougence.clouddm.console.web.component.governance.GhostGroupCompletionService ghostGroupCompletionService;

    // ==================================================================================
    // precheck (R3 hard gates + R4 threshold suggestion, design §4.2)
    // ==================================================================================

    @Override
    public GhostPrecheckVO precheck(String puid, String uid, GhostPrecheckFO fo) {
        DmDsDO dsDO = requireOwnedDs(puid, fo == null ? null : fo.getDsId());
        String dbName = fo.getDbName();
        // injection guard BEFORE any remote call (B2/B4 fail-fast shape)
        validateIdentifier(dbName);

        GhostPrecheckVO vo = new GhostPrecheckVO();
        DataSourceConfig dsConfig = dmDsConfigService.fetchDsConfigFromExists(dsDO.getId());

        // -- in-memory hard blocks (no SQL dispatched) --
        if (!SUPPORTED.contains(dsDO.getDataSourceType())) {
            addBlock(vo, I18nDmMsgKeys.GHOST_DS_TYPE_UNSUPPORTED);
        }
        if (Boolean.TRUE.equals(dsConfig.getSshProxyEnabled())) {
            addBlock(vo, I18nDmMsgKeys.GHOST_TUNNEL_UNSUPPORTED);
        } else if (dsConfig.getSslMode() != null && dsConfig.getSslMode() != SslMode.DISABLED) {
            addBlock(vo, I18nDmMsgKeys.GHOST_TUNNEL_UNSUPPORTED);
        }

        // -- statement shape (optional input; the plugin re-runs the authoritative check at init) --
        String tableName = fo.getTableName();
        if (StringUtils.isNotBlank(fo.getDdlSql())) {
            try {
                GhostAlterStmtChecker.AlterStmtTarget target = GhostAlterStmtChecker.check(fo.getDdlSql(), dbName);
                tableName = target.getTableName();
            } catch (GhostAlterStmtChecker.AlterStmtReject reject) {
                addBlockWithDetail(vo, reject.getCode(), reject.getDetail());
            }
        }
        if (StringUtils.isBlank(tableName)) {
            // nothing to probe: neither a cascade-selected table nor a parsable statement
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nRdpMsgKeys.COMM_BAD_ARG_ERROR.name()));
        }
        validateIdentifier(tableName);

        // -- remote probes only when nothing hard-blocked yet (zero side effects otherwise) --
        if (vo.getHardBlocks().isEmpty()) {
            probeTable(uid, dsDO, dsConfig, dbName, tableName, vo);

            if (vo.getHardBlocks().isEmpty() && vo.getTable() != null) {
                appendThresholdSuggestion(vo);
            }
        }
        return vo;
    }

    /** Channel-C probe: existence + B4-caliber size first, then the PK/UK requirement (design §4.2). */
    private void probeTable(String uid, DmDsDO dsDO, DataSourceConfig dsConfig, String dbName, String tableName, GhostPrecheckVO vo) {
        final String escapedDb = escapeMySqlStringLiteral(dbName);
        final String escapedTable = escapeMySqlStringLiteral(tableName);
        try {
            diagnosticExecutor.runInSession(uid, dsDO, dsConfig, diagOpts(), session -> {
                List<Map<String, String>> statRows = executeStatement(session, tableStatSql(escapedDb, escapedTable));
                if (statRows.isEmpty()) {
                    addBlock(vo, I18nDmMsgKeys.GHOST_TABLE_NOT_FOUND, dbName + "." + tableName);
                    return null;
                }
                vo.setTable(mapTableStat(statRows.get(0), tableName));

                List<Map<String, String>> uniqueKeyRows = executeStatement(session, uniqueKeySql(escapedDb, escapedTable));
                if (uniqueKeyRows.isEmpty()) {
                    addBlock(vo, I18nDmMsgKeys.GHOST_NO_UNIQUE_KEY);
                }
                return null;
            });
        } catch (DiagTimeoutException e) {
            log.warn("[Ghost] precheck query timed out after {}s on dsId={}", queryTimeoutSeconds, dsDO.getId());
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_QUERY_TIMEOUT.name()));
        }
    }

    /** R4: B4 MySQL estimate reaches an admin threshold → informational suggestion (user decides). */
    private void appendThresholdSuggestion(GhostPrecheckVO vo) {
        long sizeThresholdMb = systemDal.fetchSystemConf(RootUserConfig.Fields.ghostSizeThresholdMb, //
                Integer.class, DEFAULT_SIZE_THRESHOLD_MB).longValue();
        long rowThreshold = systemDal.fetchSystemConf(RootUserConfig.Fields.ghostRowThreshold, //
                Long.class, DEFAULT_ROW_THRESHOLD);

        GhostTableStatVO table = vo.getTable();
        long sizeThresholdBytes = sizeThresholdMb * 1024L * 1024L;
        boolean sizeHit = table.getTotalBytes() != null && table.getTotalBytes() >= sizeThresholdBytes;
        boolean rowsHit = table.getTableRows() != null && table.getTableRows() >= rowThreshold;
        if (!sizeHit && !rowsHit) {
            return;
        }
        GhostSuggestionVO suggestion = new GhostSuggestionVO();
        suggestion.setCode(SUGGEST_LARGE_TABLE);
        suggestion.setReason(DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_SUGGEST_LARGE_TABLE.name(), sizeThresholdMb, rowThreshold));
        suggestion.setSizeBytes(table.getTotalBytes());
        suggestion.setTableRows(table.getTableRows());
        suggestion.setSizeThresholdBytes(sizeThresholdBytes);
        suggestion.setRowThreshold(rowThreshold);
        vo.getSuggestions().add(suggestion);
    }

    // ==================================================================================
    // B6 M1 ticket auto-route probe (design §6.1) — reuses the precheck channel-C shape
    // ==================================================================================

    /**
     * NEVER-THROW route probe (R6 ruling: one channel-C round trip per ALTER, 10s timeout,
     * degrade to the status-quo direct chain — a probe failure must not block submission,
     * route to gh-ost, or fire an alert). Reuses the standalone precheck's SQL contracts
     * ({@link #tableStatSql} B4 caliber + {@link #uniqueKeySql} PK/UK) and threshold KVs.
     */
    @Override
    public GhostRouteProbe probeForRoute(String uid, DmDsDO dsDO, DataSourceConfig dsConfig, String dbName, String tableName) {
        GhostRouteProbe probe = new GhostRouteProbe();
        try {
            validateIdentifier(dbName);
            validateIdentifier(tableName);

            long sizeThresholdMb = systemDal.fetchSystemConf(RootUserConfig.Fields.ghostSizeThresholdMb, //
                    Integer.class, DEFAULT_SIZE_THRESHOLD_MB).longValue();
            long rowThreshold = systemDal.fetchSystemConf(RootUserConfig.Fields.ghostRowThreshold, //
                    Long.class, DEFAULT_ROW_THRESHOLD);
            long sizeThresholdBytes = sizeThresholdMb * 1024L * 1024L;
            probe.setSizeThresholdBytes(sizeThresholdBytes);
            probe.setRowThreshold(rowThreshold);

            final String escapedDb = escapeMySqlStringLiteral(dbName);
            final String escapedTable = escapeMySqlStringLiteral(tableName);
            diagnosticExecutor.runInSession(uid, dsDO, dsConfig, diagOpts(), session -> {
                List<Map<String, String>> statRows = executeStatement(session, tableStatSql(escapedDb, escapedTable));
                if (statRows.isEmpty()) {
                    return null; // table not found → probe stays tableFound=false (no route, no alert)
                }
                probe.setTableFound(true);
                GhostTableStatVO stat = mapTableStat(statRows.get(0), tableName);
                probe.setTableRows(stat.getTableRows());
                probe.setTotalBytes(stat.getTotalBytes());

                List<Map<String, String>> uniqueKeyRows = executeStatement(session, uniqueKeySql(escapedDb, escapedTable));
                probe.setUniqueKeyPresent(!uniqueKeyRows.isEmpty());
                return null;
            });
            if (!probe.isTableFound()) {
                return probe; // probed=false: a missing table is the ticket precheck's business, not routing's
            }
            boolean sizeHit = probe.getTotalBytes() != null && probe.getTotalBytes() >= sizeThresholdBytes;
            boolean rowsHit = probe.getTableRows() != null && probe.getTableRows() >= rowThreshold;
            probe.setExceeded(sizeHit || rowsHit);
            probe.setProbed(true);
        } catch (DiagTimeoutException e) {
            log.warn("[Ghost] route probe timed out after {}s on dsId={}, table {}.{} — degraded to the direct chain",
                    queryTimeoutSeconds, dsDO == null ? null : dsDO.getId(), dbName, tableName);
        } catch (Exception e) {
            // NEVER-THROW contract: including sneaky-thrown checked shapes — degrade, do not block
            log.warn("[Ghost] route probe failed on dsId={}, table {}.{} — degraded to the direct chain",
                    dsDO == null ? null : dsDO.getId(), dbName, tableName, e);
        }
        return probe;
    }

    @Override
    public DmExecAsyncTaskDO findTaskForTicketGroup(long ticketId, long groupId) {
        return findTaskForTicketGroup(ticketId, groupId, null);
    }

    /**
     * B6 M3 segment-retry target-effect probe (interface contract above): parse the decidable
     * single-action ADD COLUMN shape, then one channel-C round trip against
     * information_schema.COLUMNS. NEVER-THROW — every failure/timeout/undecidable shape degrades
     * to false (the caller conservatively rebuilds; gh-ost's duplicate-column error backstops).
     * Trusted ticket-chain surface: no tenant check (the group row's dsId comes from the ticket).
     */
    @Override
    public boolean probeAddColumnLanded(String uid, long dsId, String dbName, String alterSql) {
        try {
            GhostResumeProbe.Target target = GhostResumeProbe.parseSingleAddColumn(alterSql, dbName);
            if (target == null) {
                return false; // undecidable shape (MODIFY/DROP/multi-action/...) → conservative rebuild
            }
            DmDsDO dsDO = dsDal.dsMapper().queryDsIdentityById(dsId);
            if (dsDO == null || !SUPPORTED.contains(dsDO.getDataSourceType())) {
                return false;
            }
            validateIdentifier(dbName);
            validateIdentifier(target.getTable());
            validateIdentifier(target.getColumn());
            DataSourceConfig dsConfig = dmDsConfigService.fetchDsConfigFromExists(dsId);
            String sql = columnExistsSql(escapeMySqlStringLiteral(dbName), //
                    escapeMySqlStringLiteral(target.getTable()), escapeMySqlStringLiteral(target.getColumn()));
            boolean[] landed = { false };
            diagnosticExecutor.runInSession(uid, dsDO, dsConfig, diagOpts(), session -> {
                List<Map<String, String>> rows = executeStatement(session, sql);
                if (!rows.isEmpty()) {
                    Long cnt = parseLong(rows.get(0).get("cnt"));
                    landed[0] = cnt != null && cnt > 0;
                }
                return null;
            });
            if (landed[0]) {
                log.info("[Ghost] resume probe: column {}.{}.{} already exists — treating the lost-writeback"
                        + " segment as landed", dbName, target.getTable(), target.getColumn());
            }
            return landed[0];
        } catch (DiagTimeoutException e) {
            log.warn("[Ghost] resume probe timed out after {}s on dsId={} — conservative rebuild", queryTimeoutSeconds, dsId);
            return false;
        } catch (Exception e) {
            // NEVER-THROW contract: including sneaky-thrown checked shapes — conservative rebuild
            log.warn("[Ghost] resume probe failed on dsId={}, db {} — conservative rebuild", dsId, dbName, e);
            return false;
        }
    }

    /** existence probe of ONE column (value comparisons — string-literal escaping, B4 contract). */
    static String columnExistsSql(String escapedDb, String escapedTable, String escapedColumn) {
        return "SELECT COUNT(*) AS cnt FROM information_schema.columns " //
                + "WHERE table_schema = " + escapedDb + " AND table_name = " + escapedTable //
                + " AND column_name = " + escapedColumn;
    }

    @Override
    public DmExecAsyncTaskDO findTaskForTicketGroup(long ticketId, long groupId, Integer segmentIndex) {
        LambdaQueryWrapper<DmExecAsyncTaskDO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DmExecAsyncTaskDO::getBizType, GhostPluginConfig.TOOL_NAME) //
                // Targeted pre-filter on the compact-JSON binding keys ("ticketId":<id> — JsonUtils
                // writes no spaces). Without it the LIMIT-bounded newest-first scan can miss the
                // bound task of a LONG-RUNNING migration once TASK_LIST_LIMIT newer GHOST tasks
                // pile up above it (all tenants share the scan): the sweep probe would then
                // misjudge "task record missing" → FAILED, and a retry could double-launch over
                // the live migration. Substring over-inclusion (ticketId 90 vs 900) is harmless —
                // the exact parse-equality check below remains the authority.
                // B6 M2 (前瞻③): the pre-filter extends to groupId + segmentIndex — a segmented
                // group hosts one task PER ghost segment, so "newest of the group" would be
                // ambiguous across segments; the triple locates the segment's task exactly.
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
            GhostAsyncTaskConfig cfg = parseTaskConfig(row);
            if (cfg == null || cfg.getTicketId() == null || cfg.getTicketId() != ticketId //
                    || cfg.getGroupId() == null || cfg.getGroupId() != groupId) {
                continue;
            }
            if (segmentIndex != null && !segmentIndex.equals(cfg.getSegmentIndex())) {
                continue; // exact segment binding required (M1 tasks carry none)
            }
            return row;
        }
        return null;
    }

    // ==================================================================================
    // execute (R2 launch: createSession + START + AsyncTask bizType=GHOST)
    // ==================================================================================

    @Override
    public GhostExecuteVO execute(String puid, String uid, GhostExecuteFO fo) {
        return doExecute(puid, uid, fo, null, null, null);
    }

    @Override
    public GhostExecuteVO executeForTicket(String puid, String uid, GhostExecuteFO fo, long ticketId, long groupId,
                                           Integer segmentIndex) {
        return doExecute(puid, uid, fo, ticketId, groupId, segmentIndex);
    }

    /**
     * Shared launch body of both forms (dual-form compatibility, design §6.4): the ticket form
     * only differs by the ticketId/groupId(+segmentIndex, B6 M2) binding persisted into the
     * AsyncTask configData — everything else (session chain, task submission, audit payload)
     * is identical.
     */
    private GhostExecuteVO doExecute(String puid, String uid, GhostExecuteFO fo, Long ticketId, Long groupId,
                                     Integer segmentIndex) {
        DmDsDO dsDO = requireOwnedSupportedDs(puid, fo == null ? null : fo.getDsId());
        DataSourceConfig dsConfig = dmDsConfigService.fetchDsConfigFromExists(dsDO.getId());
        checkDirectConnection(dsConfig);

        String dbName = fo.getDbName();
        validateIdentifier(dbName);
        GhostAlterStmtChecker.AlterStmtTarget target;
        try {
            target = GhostAlterStmtChecker.check(fo.getDdlSql(), dbName);
        } catch (GhostAlterStmtChecker.AlterStmtReject reject) {
            throw rejectAsMessage(reject);
        }
        validateIdentifier(target.getTableName());

        // R14 merge chain: task options (ticket scene: none) → admin ghostDefaultOptions JSON →
        // plugin @ConfigDef compile-time defaults. The resolved snapshot rides on the launch DTO
        // AND is persisted into the task configData (auditability/reproduction).
        GhostOptionsDTO effectiveOptions = resolveEffectiveOptions(fo.getOptions());

        GhostLaunchDTO launch = new GhostLaunchDTO();
        launch.setDsId(dsDO.getId());
        launch.setSchema(dbName);
        launch.setDdlSql(fo.getDdlSql());
        launch.setOptions(effectiveOptions);
        launch.setTicketId(ticketId);

        String sessionId = UUID.randomUUID().toString().replace("-", "");
        ToolSessionContextDTO contextDTO = new ToolSessionContextDTO();
        contextDTO.setSessionId(sessionId);
        contextDTO.setConfiguration(JsonUtils.toJson(launch));
        contextDTO.setBindClusterId(dsDO.getBindClusterId() == null ? 0L : dsDO.getBindClusterId());

        try {
            sessionId = toolsService.createSession(uid, GhostPluginConfig.TOOL_NAME, contextDTO);
        } catch (ErrorMessageException e) {
            throw e;
        } catch (RuntimeException e) {
            throw translateGhostFailure(e);
        }
        try {
            toolsService.invoke(uid, sessionId, START, null);
        } catch (ErrorMessageException e) {
            closeQuietly(uid, sessionId);
            throw e;
        } catch (RuntimeException e) {
            closeQuietly(uid, sessionId);
            throw translateGhostFailure(e);
        }

        GhostAsyncTaskConfig taskConfig = new GhostAsyncTaskConfig();
        taskConfig.setUserId(uid);
        taskConfig.setSessionId(sessionId);
        taskConfig.setDsId(dsDO.getId());
        taskConfig.setDbName(dbName);
        taskConfig.setTableName(target.getTableName());
        taskConfig.setDdlSql(fo.getDdlSql());
        taskConfig.setTicketId(ticketId);
        taskConfig.setGroupId(groupId);
        taskConfig.setSegmentIndex(segmentIndex);
        taskConfig.setEffectiveOptions(effectiveOptions);

        AsyncTaskConfig config = new AsyncTaskConfig();
        config.setTitle(DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_ASYNC_TITLE_MESSAGE.name(), target.getTableName()));
        config.setDescription(DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_ASYNC_DESC_MESSAGE.name(), dbName, target.getTableName()));
        config.setConfigData(JsonUtils.toJson(taskConfig));
        config.setShowInDock(true);
        config.setProcessType(AsyncTaskProcessType.PROGRESS);
        config.setHandlerType(GhostAsyncTask.class);
        config.setBizType(GhostPluginConfig.TOOL_NAME);
        config.setBizId(sessionId);
        try {
            asyncTaskServiceService.submitTask(uid, config);
        } catch (RuntimeException e) {
            // B6 M2 Step-0 ⑥ (S4 debt closure, M1 check ruling 3): createSession + START already
            // succeeded — without the tracking task NOTHING would ever write back, release the
            // sidecar slot or panic the process, and a user retry could double-launch a second
            // gh-ost over the live one (initiallyDropGhostTable makes them delete each other's
            // shadow table). Release the session, then surface the original failure.
            log.error("[Ghost] AsyncTask submission failed for session {} — releasing the started session", sessionId, e);
            closeQuietly(uid, sessionId);
            throw e;
        }

        GhostExecuteVO vo = new GhostExecuteVO();
        vo.setSessionId(sessionId);
        DmExecAsyncTaskDO task = asyncTaskServiceService.queryAsyncTaskByBizId(sessionId, GhostPluginConfig.TOOL_NAME);
        vo.setTaskId(task == null ? null : task.getId());
        vo.setAudit(buildAuditObj(sessionId, dsDO.getId(), dbName, target.getTableName(), fo.getDdlSql(), null));
        return vo;
    }

    // ==================================================================================
    // R14 options merge chain: task options → admin ghostDefaultOptions JSON → plugin defaults
    // ==================================================================================

    /**
     * Resolve the EFFECTIVE gh-ost options (design §6.7 merge contract). Layer 1 (bottom) =
     * the plugin {@code GhostPluginConfig} @ConfigDef compile-time defaults; layer 2 = the admin
     * {@code ghostDefaultOptions} JSON KV (malformed JSON → the whole layer is skipped with a
     * warn log, falling back to the plugin defaults); layer 3 (top) = the task-level options
     * (standalone launch form; the ticket scene passes none).
     */
    GhostOptionsDTO resolveEffectiveOptions(GhostOptionsDTO taskOptions) {
        GhostOptionsDTO effective = pluginDefaults();
        overlayAdminJson(effective);
        if (taskOptions != null) {
            overlayTaskOptions(effective, taskOptions);
        }
        return effective;
    }

    /** layer 1: the plugin compile-time defaults (field-by-field copy of GhostPluginConfig). */
    static GhostOptionsDTO pluginDefaults() {
        GhostPluginConfig cfg = new GhostPluginConfig();
        GhostOptionsDTO base = new GhostOptionsDTO();
        base.setAssumeRbr(cfg.getAssumeRbr());
        base.setAllowOnMaster(cfg.getAllowOnMaster());
        base.setCutOverLockTimeoutSec(cfg.getCutOverLockTimeoutSec());
        base.setDmlBatchSize(cfg.getDmlBatchSize());
        base.setChunkSize(cfg.getChunkSize());
        base.setVerbose(cfg.getVerbose());
        base.setCutOverType(cfg.getCutOverType());
        base.setExactRowCount(cfg.getExactRowCount());
        base.setConcurrentRowCount(cfg.getConcurrentRowCount());
        base.setHbIntervalMs(cfg.getHbIntervalMs());
        base.setTimestampOldTable(cfg.getTimestampOldTable());
        base.setInitiallyDropGhostTable(cfg.getInitiallyDropGhostTable());
        base.setAssumeDsProxyInFront(cfg.getAssumeDsProxyInFront());
        return base;
    }

    /** layer 2: the admin JSON KV — per-key tolerant overlay; any malformed shape skips the layer. */
    private void overlayAdminJson(GhostOptionsDTO base) {
        String json;
        try {
            json = systemDal.fetchSystemConf(RootUserConfig.Fields.ghostDefaultOptions, String.class, "");
        } catch (RuntimeException e) {
            log.warn("[Ghost] ghostDefaultOptions read failed, falling back to plugin defaults", e);
            return;
        }
        if (StringUtils.isBlank(json)) {
            return;
        }
        Map<?, ?> map;
        try {
            map = JsonUtils.toObj(json, HashMap.class);
        } catch (Exception e) {
            // JsonUtils sneaky-throws the CHECKED JsonProcessingException — catch Exception, not RuntimeException
            log.warn("[Ghost] ghostDefaultOptions is not a valid JSON object ({}), falling back to plugin defaults", json, e);
            return;
        }
        if (map == null || map.isEmpty()) {
            return;
        }
        try {
            Boolean assumeRbr = readBool(map, "assumeRbr");
            if (assumeRbr != null) { base.setAssumeRbr(assumeRbr); }
            Boolean allowOnMaster = readBool(map, "allowOnMaster");
            if (allowOnMaster != null) { base.setAllowOnMaster(allowOnMaster); }
            Integer cutOverLockTimeoutSec = readInt(map, "cutOverLockTimeoutSec");
            if (cutOverLockTimeoutSec != null) { base.setCutOverLockTimeoutSec(cutOverLockTimeoutSec); }
            Integer dmlBatchSize = readInt(map, "dmlBatchSize");
            if (dmlBatchSize != null) { base.setDmlBatchSize(dmlBatchSize); }
            Integer chunkSize = readInt(map, "chunkSize");
            if (chunkSize != null) { base.setChunkSize(chunkSize); }
            Boolean verbose = readBool(map, "verbose");
            if (verbose != null) { base.setVerbose(verbose); }
            Object cutOverType = map.get("cutOverType");
            if (cutOverType != null) {
                base.setCutOverType(GhostCutOverType.valueOf(String.valueOf(cutOverType).trim()));
            }
            Boolean exactRowCount = readBool(map, "exactRowCount");
            if (exactRowCount != null) { base.setExactRowCount(exactRowCount); }
            Boolean concurrentRowCount = readBool(map, "concurrentRowCount");
            if (concurrentRowCount != null) { base.setConcurrentRowCount(concurrentRowCount); }
            Integer hbIntervalMs = readInt(map, "hbIntervalMs");
            if (hbIntervalMs != null) { base.setHbIntervalMs(hbIntervalMs); }
            Boolean timestampOldTable = readBool(map, "timestampOldTable");
            if (timestampOldTable != null) { base.setTimestampOldTable(timestampOldTable); }
            Boolean initiallyDropGhostTable = readBool(map, "initiallyDropGhostTable");
            if (initiallyDropGhostTable != null) { base.setInitiallyDropGhostTable(initiallyDropGhostTable); }
            Boolean assumeDsProxyInFront = readBool(map, "assumeDsProxyInFront");
            if (assumeDsProxyInFront != null) { base.setAssumeDsProxyInFront(assumeDsProxyInFront); }
        } catch (RuntimeException e) {
            // a bad value (wrong type / unknown enum name) discards the WHOLE admin layer —
            // partial application of a malformed config would be harder to reason about
            log.warn("[Ghost] ghostDefaultOptions has an invalid value ({}), falling back to plugin defaults", json, e);
            reloadPluginDefaults(base);
        }
    }

    private static void reloadPluginDefaults(GhostOptionsDTO base) {
        GhostOptionsDTO fresh = pluginDefaults();
        base.setAssumeRbr(fresh.getAssumeRbr());
        base.setAllowOnMaster(fresh.getAllowOnMaster());
        base.setCutOverLockTimeoutSec(fresh.getCutOverLockTimeoutSec());
        base.setDmlBatchSize(fresh.getDmlBatchSize());
        base.setChunkSize(fresh.getChunkSize());
        base.setVerbose(fresh.getVerbose());
        base.setCutOverType(fresh.getCutOverType());
        base.setExactRowCount(fresh.getExactRowCount());
        base.setConcurrentRowCount(fresh.getConcurrentRowCount());
        base.setHbIntervalMs(fresh.getHbIntervalMs());
        base.setTimestampOldTable(fresh.getTimestampOldTable());
        base.setInitiallyDropGhostTable(fresh.getInitiallyDropGhostTable());
        base.setAssumeDsProxyInFront(fresh.getAssumeDsProxyInFront());
    }

    /** layer 3: task-level overrides, field by field (null field = keep the lower layer). */
    static void overlayTaskOptions(GhostOptionsDTO base, GhostOptionsDTO task) {
        if (task.getAssumeRbr() != null) { base.setAssumeRbr(task.getAssumeRbr()); }
        if (task.getAllowOnMaster() != null) { base.setAllowOnMaster(task.getAllowOnMaster()); }
        if (task.getCutOverLockTimeoutSec() != null) { base.setCutOverLockTimeoutSec(task.getCutOverLockTimeoutSec()); }
        if (task.getDmlBatchSize() != null) { base.setDmlBatchSize(task.getDmlBatchSize()); }
        if (task.getChunkSize() != null) { base.setChunkSize(task.getChunkSize()); }
        if (task.getVerbose() != null) { base.setVerbose(task.getVerbose()); }
        if (task.getCutOverType() != null) { base.setCutOverType(task.getCutOverType()); }
        if (task.getExactRowCount() != null) { base.setExactRowCount(task.getExactRowCount()); }
        if (task.getConcurrentRowCount() != null) { base.setConcurrentRowCount(task.getConcurrentRowCount()); }
        if (task.getHbIntervalMs() != null) { base.setHbIntervalMs(task.getHbIntervalMs()); }
        if (task.getTimestampOldTable() != null) { base.setTimestampOldTable(task.getTimestampOldTable()); }
        if (task.getInitiallyDropGhostTable() != null) { base.setInitiallyDropGhostTable(task.getInitiallyDropGhostTable()); }
        if (task.getAssumeDsProxyInFront() != null) { base.setAssumeDsProxyInFront(task.getAssumeDsProxyInFront()); }
    }

    private static Boolean readBool(Map<?, ?> map, String key) {
        Object v = map.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        return Boolean.valueOf(String.valueOf(v).trim());
    }

    private static Integer readInt(Map<?, ?> map, String key) {
        Object v = map.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        return Integer.valueOf(String.valueOf(v).trim());
    }

    // ==================================================================================
    // tailLog / tailStatus (R6 pass-through + R7 oldTableName capture; task fallback)
    // ==================================================================================

    @Override
    public GhostTailResponseDTO tailLog(String puid, String uid, String sessionId, int startLine) {
        DmExecAsyncTaskDO task = requireTenantTask(puid, sessionId);
        GhostAsyncTaskConfig cfg = parseTaskConfig(task);
        String ownerUid = resolveOwnerUid(task, cfg);

        GhostTailRequestDTO request = new GhostTailRequestDTO();
        request.setStartLine(startLine);
        String result = toolsService.tailLog(ownerUid, sessionId, ToolUtils.buildRequest(JsonUtils.toJson(request)));
        if (StringUtils.isBlank(result)) {
            // session already closed: degrade to the task record (status only, logs are gone
            // unless the admin keepLog option retained them on the worker)
            GhostTailResponseDTO fallback = new GhostTailResponseDTO();
            fallback.setLogArr(Collections.emptyList());
            fallback.setEndLine(startLine);
            fallback.setStatus(resolveToolStatus(task, cfg));
            return fallback;
        }
        GhostTailResponseDTO dto = JsonUtils.toObj(result, GhostTailResponseDTO.class);
        if (dto != null && StringUtils.isNotBlank(dto.getStatus())) {
            GhostStatusDTO statusDTO = new GhostStatusDTO();
            statusDTO.setStatus(dto.getStatus());
            captureSessionState(task, statusDTO);
        }
        return dto;
    }

    @Override
    public GhostStatusDTO tailStatus(String puid, String uid, String sessionId) {
        DmExecAsyncTaskDO task = requireTenantTask(puid, sessionId);
        GhostAsyncTaskConfig cfg = parseTaskConfig(task);
        String ownerUid = resolveOwnerUid(task, cfg);

        String result = toolsService.tailStatus(ownerUid, sessionId, null);
        if (StringUtils.isBlank(result)) {
            return synthesizeStatusFromTask(task, cfg);
        }
        GhostStatusDTO dto = JsonUtils.toObj(result, GhostStatusDTO.class);
        captureSessionState(task, dto);
        return dto;
    }

    @Override
    public GhostStatusDTO tailStatusForTask(String ownerUid, String sessionId) {
        String result = toolsService.tailStatus(ownerUid, sessionId, null);
        if (StringUtils.isBlank(result)) {
            return null;
        }
        GhostStatusDTO dto = JsonUtils.toObj(result, GhostStatusDTO.class);
        DmExecAsyncTaskDO task = asyncTaskServiceService.queryAsyncTaskByBizId(sessionId, GhostPluginConfig.TOOL_NAME);
        captureSessionState(task, dto);
        return dto;
    }

    /**
     * R7 carrier write: oldTableName (captured from the gh-ost magic cut-over log line) and the
     * last tool status land in {@code dm_exec_async_task.configData}, write-on-change only.
     */
    private void captureSessionState(DmExecAsyncTaskDO task, GhostStatusDTO dto) {
        if (task == null || dto == null) {
            return;
        }
        GhostAsyncTaskConfig cfg = parseTaskConfig(task);
        if (cfg == null) {
            return;
        }
        boolean changed = false;
        if (StringUtils.isNotBlank(dto.getOldTableName()) && !dto.getOldTableName().equals(cfg.getOldTableName())) {
            cfg.setOldTableName(dto.getOldTableName());
            changed = true;
        }
        if (StringUtils.isNotBlank(dto.getStatus()) && !dto.getStatus().equals(cfg.getLastStatus())) {
            cfg.setLastStatus(dto.getStatus());
            changed = true;
        }
        if (changed) {
            persistTaskConfig(task, cfg);
        }
    }

    /** Fallback status when the tool session is already gone (closed / worker restarted). */
    private GhostStatusDTO synthesizeStatusFromTask(DmExecAsyncTaskDO task, GhostAsyncTaskConfig cfg) {
        GhostStatusDTO dto = new GhostStatusDTO();
        String status = resolveToolStatus(task, cfg);
        dto.setStatus(status);
        dto.setMessage(task.getStatusMsg());
        if (cfg != null) {
            dto.setOldTableName(cfg.getOldTableName());
        }
        if (STATUS_FAILED.equals(status) || STATUS_ABORTED.equals(status)) {
            dto.setFailReason(task.getStatusMsg());
        }
        if (task.getProcessType() == AsyncTaskProcessType.PROGRESS && StringUtils.isNotBlank(task.getProcessValue())) {
            try {
                long percent = Long.parseLong(task.getProcessValue().trim());
                dto.setUseProgress(true);
                dto.setCurValue(percent);
                dto.setMaxValue(100);
            } catch (NumberFormatException ignore) {
                // a non-numeric processValue simply carries no progress bar
            }
        }
        return dto;
    }

    private static String resolveToolStatus(DmExecAsyncTaskDO task, GhostAsyncTaskConfig cfg) {
        if (cfg != null && StringUtils.isNotBlank(cfg.getLastStatus())) {
            return cfg.getLastStatus();
        }
        return mapTaskStatusToTool(task.getStatus());
    }

    /** AsyncTaskStatus → the tool status domain (fallback only; the captured lastStatus wins). */
    static String mapTaskStatusToTool(AsyncTaskStatus status) {
        if (status == null) {
            return null;
        }
        switch (status) {
            case COMPLETE:
                return STATUS_COMPLETED;
            case FAILURE:
                return STATUS_FAILED;
            case CANCEL:
                return STATUS_ABORTED;
            case PAUSE:
            case PAUSING:
                return STATUS_PAUSED;
            case RUNNING:
            case CANCELING:
                return STATUS_RUNNING;
            case INIT:
            case BLOCK:
            case WAIT_START:
            default:
                return STATUS_INIT;
        }
    }

    // ==================================================================================
    // control operations (R5 pause/resume/stop + close), dock task kept in sync (Faker shape)
    // ==================================================================================

    @Override
    public GhostOpAuditObj pause(String puid, String uid, String sessionId) {
        DmExecAsyncTaskDO task = requireTenantTask(puid, sessionId);
        GhostAsyncTaskConfig cfg = parseTaskConfig(task);
        invokeControl(resolveOwnerUid(task, cfg), sessionId, PAUSE);
        // B6 M2 Step-0 ⑤ ruling (option a, dock-PAUSE edge): a TICKET-bound task is NOT dock-
        // paused — 暂停=throttle is a session-level control that must not change tracking state
        // (design §6.3). The framework pause cancels the handler's future, and the worker's
        // finishTask can win the race against f.cancel(), terminalizing the task (COMPLETE)
        // while gh-ost is merely throttled — the sweep would then judge the EXECUTING group
        // "writeback lost" → FAILED over a LIVE migration. Keeping the poller RUNNING preserves
        // progress capture and guarantees the terminal writeback fires. Standalone tasks keep
        // the dock-pause shape (S5 behavior, resumable through the dock).
        if (cfg == null || cfg.getTicketId() == null) {
            asyncTaskServiceService.pauseTask(sessionId, GhostPluginConfig.TOOL_NAME, "pause By console");
        }
        return auditObjOf(task, cfg, null);
    }

    @Override
    public GhostOpAuditObj resume(String puid, String uid, String sessionId) {
        DmExecAsyncTaskDO task = requireTenantTask(puid, sessionId);
        GhostAsyncTaskConfig cfg = parseTaskConfig(task);
        invokeControl(resolveOwnerUid(task, cfg), sessionId, RESUME);
        asyncTaskServiceService.resumeTask(sessionId, GhostPluginConfig.TOOL_NAME, "resume By console");
        return auditObjOf(task, cfg, null);
    }

    @Override
    public GhostOpAuditObj stop(String puid, String uid, String sessionId) {
        DmExecAsyncTaskDO task = requireTenantTask(puid, sessionId);
        GhostAsyncTaskConfig cfg = parseTaskConfig(task);
        invokeControl(resolveOwnerUid(task, cfg), sessionId, STOP);
        // B6 M1 (R5): a ticket-bound termination drives the group FAILED deterministically
        // (终止 → 组 FAILED → 工单 EXEC_FAIL). The dock CANCEL below usually wins the race against
        // the poller, so the ABORTED tool-status hook may never fire — this is the primary
        // writeback, the sweep liveness probe is the backstop. Never breaks the stop itself.
        if (cfg != null && cfg.getTicketId() != null && cfg.getGroupId() != null) {
            try {
                ghostGroupCompletionService.onGhostTerminal(cfg.getTicketId(), cfg.getGroupId(),
                        cfg.getSegmentIndex(), false, "terminated by user (panic flag)");
            } catch (RuntimeException e) {
                log.error("[Ghost] ticket writeback on stop failed for session {} (the sweep probe backstops)", sessionId, e);
            }
        }
        // the dock task follows the user termination (Faker close precedent); the handler's
        // cancel branch keeps the session open for log forensics
        asyncTaskServiceService.cancelTask(sessionId, GhostPluginConfig.TOOL_NAME, "stop By console");
        return auditObjOf(task, cfg, null);
    }

    @Override
    public void close(String puid, String uid, String sessionId) {
        DmExecAsyncTaskDO task = requireTenantTask(puid, sessionId);
        GhostAsyncTaskConfig cfg = parseTaskConfig(task);
        // closeSession is a no-op when the session row is already gone
        toolsService.closeSession(resolveOwnerUid(task, cfg), sessionId);
        asyncTaskServiceService.cancelTask(sessionId, GhostPluginConfig.TOOL_NAME, "close By console");
    }

    /**
     * Ticket-form terminal session release (deviation-6 closure): closeSession only — no tenant
     * check (trusted handler surface), no cancelTask (the caller IS the task transition), no
     * audit. NEVER-THROW: the sidecar slot release is best effort and must not break the task
     * lifecycle; closeSession on an already-gone session is a no-op.
     */
    @Override
    public void releaseSessionQuietly(String ownerUid, String sessionId) {
        if (StringUtils.isBlank(sessionId)) {
            return;
        }
        closeQuietly(ownerUid, sessionId);
    }

    @Override
    public boolean hasSession(String ownerUid, String sessionId) {
        return toolsService.hasSession(ownerUid, sessionId);
    }

    @Override
    public void pauseSession(String ownerUid, String sessionId) {
        invokeTool(ownerUid, sessionId, PAUSE);
    }

    @Override
    public void resumeSession(String ownerUid, String sessionId) {
        invokeTool(ownerUid, sessionId, RESUME);
    }

    /** control invoke with an explicit ended-session guard (invoke on a dead session is a silent no-op). */
    private void invokeControl(String ownerUid, String sessionId, String method) {
        if (!toolsService.hasSession(ownerUid, sessionId)) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_SESSION_ENDED.name()));
        }
        invokeTool(ownerUid, sessionId, method);
    }

    private void invokeTool(String ownerUid, String sessionId, String method) {
        try {
            toolsService.invoke(ownerUid, sessionId, method, null);
        } catch (ErrorMessageException e) {
            throw e;
        } catch (RuntimeException e) {
            throw translateGhostFailure(e);
        }
    }

    // ==================================================================================
    // old-table drop (R7: terminal task + captured name + channel C + audit)
    // ==================================================================================

    @Override
    public GhostOpAuditObj dropOldTable(String puid, String uid, String sessionId) {
        DmExecAsyncTaskDO task = requireTenantTask(puid, sessionId);
        GhostAsyncTaskConfig cfg = parseTaskConfig(task);
        if (cfg == null) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_TASK_NOT_FOUND.name()));
        }
        if (!isTerminalTaskStatus(task.getStatus())) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_TASK_NOT_TERMINAL.name()));
        }
        String oldTableName = cfg.getOldTableName();
        if (StringUtils.isBlank(oldTableName) || Boolean.TRUE.equals(cfg.getOldTableDropped())) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_OLD_TABLE_ABSENT.name()));
        }
        // injection guard: gh-ost old-table names are [A-Za-z0-9_$]{1,64} by construction (the
        // launch-time parser already rejected anything else) — fail fast before any remote call
        if (!OLD_TABLE_NAME_PATTERN.matcher(oldTableName).matches()) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nRdpMsgKeys.COMM_BAD_ARG_ERROR.name()));
        }
        String dbName = cfg.getDbName();
        validateIdentifier(dbName);

        DmDsDO dsDO = requireOwnedSupportedDs(puid, cfg.getDsId());
        DataSourceConfig dsConfig = dmDsConfigService.fetchDsConfigFromExists(dsDO.getId());

        final String dropSql = dropTableSql(dbName, oldTableName);
        try {
            // B2 shape: one budget for create + DROP + release (a single admin statement)
            diagnosticExecutor.runInSession(uid, dsDO, dsConfig, //
                    DiagOpts.builder().timeoutSeconds((int) dropTimeoutSeconds).operationLevelTimeout(true).build(), //
                    session -> {
                        ResultList result = session.execute(dropSql);
                        String failure = DiagResultUtils.firstFailureMessage(result);
                        if (failure != null) {
                            if (isTableAbsentError(failure)) {
                                throw new ErrorMessageException(DmI18nUtils.getMessage(
                                        I18nDmMsgKeys.GHOST_OLD_TABLE_NOT_FOUND.name(), dbName + "." + oldTableName));
                            }
                            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_DROP_FAILED.name(), failure));
                        }
                        return null;
                    });
        } catch (DiagTimeoutException e) {
            log.warn("[Ghost] drop old table timed out after {}s on dsId={}", dropTimeoutSeconds, dsDO.getId());
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_QUERY_TIMEOUT.name()));
        }

        cfg.setOldTableDropped(true);
        cfg.setOldTableDropUid(uid);
        cfg.setOldTableDropTime(System.currentTimeMillis());
        persistTaskConfig(task, cfg);
        log.info("[Ghost] old table {}.{} dropped by uid={} (session {})", dbName, oldTableName, uid, sessionId);

        return auditObjOf(task, cfg, oldTableName);
    }

    /** MySQL DROP failure fragments meaning the table is already gone (idempotent-drop hint). */
    static boolean isTableAbsentError(String failure) {
        String lower = failure.toLowerCase(Locale.ROOT);
        return lower.contains("doesn't exist") || lower.contains("unknown table");
    }

    static boolean isTerminalTaskStatus(AsyncTaskStatus status) {
        return status == AsyncTaskStatus.COMPLETE || status == AsyncTaskStatus.FAILURE || status == AsyncTaskStatus.CANCEL;
    }

    // ==================================================================================
    // task list (R8: tenant-wide, newest first)
    // ==================================================================================

    @Override
    public List<GhostTaskVO> tasks(String puid, String uid) {
        Set<String> tenantUids = resolveTenantUids(puid);
        LambdaQueryWrapper<DmExecAsyncTaskDO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DmExecAsyncTaskDO::getBizType, GhostPluginConfig.TOOL_NAME) //
                .in(DmExecAsyncTaskDO::getUid, tenantUids) //
                .orderByDesc(DmExecAsyncTaskDO::getId) //
                .last("LIMIT " + TASK_LIST_LIMIT);
        List<DmExecAsyncTaskDO> rows = executionDal.asyncTaskMapper().selectList(wrapper);

        List<GhostTaskVO> result = new ArrayList<>();
        if (rows == null || rows.isEmpty()) {
            return result;
        }
        List<GhostAsyncTaskConfig> cfgs = new ArrayList<>(rows.size());
        Set<Long> dsIds = new LinkedHashSet<>();
        Set<String> initiatorUids = new LinkedHashSet<>();
        for (DmExecAsyncTaskDO row : rows) {
            GhostAsyncTaskConfig cfg = parseTaskConfig(row);
            cfgs.add(cfg);
            if (cfg != null && cfg.getDsId() != null) {
                dsIds.add(cfg.getDsId());
            }
            if (StringUtils.isNotBlank(row.getUid())) {
                initiatorUids.add(row.getUid());
            }
        }
        // method-local resolution maps (no singleton caches)
        Map<Long, String> dsNames = resolveDsNames(puid, dsIds);
        Map<String, String> userNames = resolveUserNames(initiatorUids);

        for (int i = 0; i < rows.size(); i++) {
            DmExecAsyncTaskDO row = rows.get(i);
            GhostAsyncTaskConfig cfg = cfgs.get(i);
            GhostTaskVO vo = new GhostTaskVO();
            vo.setTaskId(row.getId());
            vo.setSessionId(row.getBizId());
            vo.setTitle(row.getTitle());
            vo.setStatus(row.getStatus() == null ? null : row.getStatus().name());
            vo.setStatusMsg(row.getStatusMsg());
            vo.setProgress(parseProgress(row));
            vo.setInitiatorUid(row.getUid());
            vo.setInitiatorName(userNames.get(row.getUid()));
            vo.setGmtCreate(row.getGmtCreate());
            vo.setTimeOfStart(row.getTimeOfStart());
            vo.setTimeOfFinish(row.getTimeOfFinish());
            if (cfg != null) {
                vo.setToolStatus(cfg.getLastStatus());
                vo.setDsId(cfg.getDsId());
                vo.setDsName(cfg.getDsId() == null ? null : dsNames.get(cfg.getDsId()));
                vo.setDbName(cfg.getDbName());
                vo.setTableName(cfg.getTableName());
                vo.setDdlSql(cfg.getDdlSql());
                vo.setOldTableName(cfg.getOldTableName());
                vo.setOldTableDropped(Boolean.TRUE.equals(cfg.getOldTableDropped()));
            }
            result.add(vo);
        }
        return result;
    }

    private static Integer parseProgress(DmExecAsyncTaskDO row) {
        if (row.getProcessType() != AsyncTaskProcessType.PROGRESS || StringUtils.isBlank(row.getProcessValue())) {
            return null;
        }
        try {
            return Integer.valueOf(row.getProcessValue().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Map<Long, String> resolveDsNames(String puid, Set<Long> dsIds) {
        if (dsIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<DmDsDO> dsList = dsDal.dsMapper().listByOwnerAndIds(puid, new ArrayList<>(dsIds));
        Map<Long, String> names = new HashMap<>();
        if (dsList != null) {
            for (DmDsDO dsDO : dsList) {
                names.put(dsDO.getId(), StringUtils.isNotBlank(dsDO.getInstanceDesc()) ? dsDO.getInstanceDesc() : dsDO.getHost());
            }
        }
        return names;
    }

    private Map<String, String> resolveUserNames(Set<String> uids) {
        Map<String, String> names = new HashMap<>();
        for (String one : uids) {
            try {
                DmAuthUserDO user = authDal.userMapper().queryByUid(one);
                if (user != null) {
                    names.put(one, user.getUsername());
                }
            } catch (RuntimeException e) {
                log.warn("[Ghost] resolve initiator name failed for uid={}", one, e);
            }
        }
        return names;
    }

    // ==================================================================================
    // plugin error translation (design §7 matrix; CODE: detail prefix contract)
    // ==================================================================================

    /**
     * Maps a tool-session RuntimeException (message carries the plugin's {@code CODE: detail}
     * prefix, possibly wrapped) onto the design §7 i18n matrix. Unknown shapes fall back to
     * GHOST_TOOL_FAILED with the raw message as {0}.
     */
    static ErrorMessageException translateGhostFailure(RuntimeException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        Matcher matcher = GHOST_CODE_PATTERN.matcher(msg);
        if (!matcher.find()) {
            return message(I18nDmMsgKeys.GHOST_TOOL_FAILED, msg);
        }
        String code = matcher.group();
        String detail = msg.substring(matcher.end());
        if (detail.startsWith(":")) {
            detail = detail.substring(1).trim();
        }
        if (detail.isEmpty()) {
            detail = msg;
        }
        switch (code) {
            case CODE_BAD_LAUNCH_CONFIG:
                return message(I18nDmMsgKeys.GHOST_BAD_LAUNCH_CONFIG, detail);
            case CODE_DS_TYPE_UNSUPPORTED:
                return plain(I18nDmMsgKeys.GHOST_DS_TYPE_UNSUPPORTED);
            case CODE_TUNNEL_UNSUPPORTED:
                return plain(I18nDmMsgKeys.GHOST_TUNNEL_UNSUPPORTED);
            case CODE_STMT_UNSUPPORTED:
                return plain(I18nDmMsgKeys.GHOST_STMT_UNSUPPORTED);
            case CODE_SCHEMA_MISMATCH:
                return message(I18nDmMsgKeys.GHOST_SCHEMA_MISMATCH, detail);
            case CODE_BINARY_MISSING:
                return message(I18nDmMsgKeys.GHOST_BINARY_MISSING, detail);
            case CODE_ALREADY_STARTED:
                return plain(I18nDmMsgKeys.GHOST_ALREADY_STARTED);
            case CODE_NOT_INITIALIZED:
                return plain(I18nDmMsgKeys.GHOST_NOT_INITIALIZED);
            case CODE_ABORTED:
                return message(I18nDmMsgKeys.GHOST_ABORTED, detail);
            case CODE_EXEC_FAILED:
                return classifyExecFailure(detail);
            default:
                return message(I18nDmMsgKeys.GHOST_TOOL_FAILED, msg);
        }
    }

    /**
     * GHOST_EXEC_FAILED detail classification (spike-evidenced gh-ost FATAL shapes): privilege
     * (Error 1045 / REPLICATION), proxy topology (Unexpected database port), binlog (ROW format),
     * resource (critical load / disk), heartbeat loss — everything else stays the generic key.
     */
    static ErrorMessageException classifyExecFailure(String detail) {
        String lower = detail.toLowerCase(Locale.ROOT);
        if (lower.contains("access denied") || lower.contains("error 1045") || lower.contains("replication")) {
            return message(I18nDmMsgKeys.GHOST_PRIVILEGE_MISSING, detail);
        }
        if (lower.contains("unexpected database port")) {
            return message(I18nDmMsgKeys.GHOST_TOPOLOGY_UNSUPPORTED, detail);
        }
        if (lower.contains("binlog") || lower.contains("binary log")) {
            return message(I18nDmMsgKeys.GHOST_BINLOG_INVALID, detail);
        }
        if (lower.contains("critical load") || lower.contains("disk") || lower.contains("no space")) {
            return message(I18nDmMsgKeys.GHOST_RESOURCE, detail);
        }
        if (lower.contains("heartbeat")) {
            return message(I18nDmMsgKeys.GHOST_TIMEOUT, detail);
        }
        return message(I18nDmMsgKeys.GHOST_EXEC_FAILED, detail);
    }

    private static ErrorMessageException plain(I18nDmMsgKeys key) {
        return new ErrorMessageException(DmI18nUtils.getMessage(key.name()));
    }

    private static ErrorMessageException message(I18nDmMsgKeys key, Object... args) {
        return new ErrorMessageException(DmI18nUtils.getMessage(key.name(), args));
    }

    private static ErrorMessageException rejectAsMessage(GhostAlterStmtChecker.AlterStmtReject reject) {
        if (GhostAlterStmtChecker.CODE_SCHEMA_MISMATCH.equals(reject.getCode())) {
            return message(I18nDmMsgKeys.GHOST_SCHEMA_MISMATCH, reject.getDetail());
        }
        return plain(I18nDmMsgKeys.GHOST_STMT_UNSUPPORTED);
    }

    // ==================================================================================
    // channel-C helpers (ops-diagnostics spec: validate → escape → timeout → translate)
    // ==================================================================================

    /** B4 granularity: per-statement wrapping, read-only autoCommit shape. */
    private DiagOpts diagOpts() {
        return DiagOpts.builder().timeoutSeconds((int) queryTimeoutSeconds).build();
    }

    private static List<Map<String, String>> executeStatement(DiagSession session, String sql) {
        ResultList resultList = session.execute(sql);
        String failure = DiagResultUtils.firstFailureMessage(resultList);
        if (failure != null) {
            log.warn("[Ghost] precheck query failed: {}", failure);
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_QUERY_FAILED.name(), failure));
        }
        return DiagResultUtils.extractRows(resultList);
    }

    /** PK/UK existence probe (design §4.2): any NON_UNIQUE=0 index row satisfies gh-ost. */
    static String uniqueKeySql(String escapedDb, String escapedTable) {
        return "SELECT index_name FROM information_schema.statistics " //
                + "WHERE table_schema = " + escapedDb + " AND table_name = " + escapedTable + " AND non_unique = 0 LIMIT 1";
    }

    /** B4 M2-caliber size/row estimate of one table. */
    static String tableStatSql(String escapedDb, String escapedTable) {
        return "SELECT table_name, table_rows AS row_estimate, data_length AS data_bytes, " //
                + "index_length AS index_bytes " //
                + "FROM information_schema.tables " //
                + "WHERE table_type = 'BASE TABLE' AND table_schema = " + escapedDb + " AND table_name = " + escapedTable;
    }

    /** the caller guarantees the OLD_TABLE_NAME_PATTERN shape; doubling stays as defense in depth. */
    static String dropTableSql(String dbName, String oldTableName) {
        return "DROP TABLE " + quoteMySqlIdentifier(dbName) + "." + quoteMySqlIdentifier(oldTableName);
    }

    static String quoteMySqlIdentifier(String name) {
        return '`' + name.replace("`", "``") + '`';
    }

    /** Non-blank, ≤64 chars, no NUL — rejected with COMM_BAD_ARG_ERROR before any SQL is built (B4 contract). */
    static void validateIdentifier(String name) {
        if (StringUtils.isBlank(name) || name.length() > MAX_IDENTIFIER_LENGTH || name.indexOf('\0') >= 0) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nRdpMsgKeys.COMM_BAD_ARG_ERROR.name()));
        }
    }

    /**
     * MySQL string-literal quoting for value comparisons (spec §4 / B4 lesson): backslash
     * doubling FIRST (default sql_mode has no NO_BACKSLASH_ESCAPES), then single-quote doubling.
     */
    static String escapeMySqlStringLiteral(String name) {
        return '\'' + name.replace("\\", "\\\\").replace("'", "''") + '\'';
    }

    private static GhostTableStatVO mapTableStat(Map<String, String> row, String tableName) {
        GhostTableStatVO stat = new GhostTableStatVO();
        stat.setTableName(tableName);
        stat.setDataLength(parseLong(row.get("data_bytes")));
        stat.setIndexLength(parseLong(row.get("index_bytes")));
        stat.setTableRows(parseLong(row.get("row_estimate")));
        if (stat.getDataLength() != null && stat.getIndexLength() != null) {
            long total = stat.getDataLength() + stat.getIndexLength();
            stat.setTotalBytes(total);
            // R3 disk-headroom hint: the shadow table needs roughly one extra copy (×2 total)
            stat.setEstimatedDiskBytes(total * 2);
        }
        return stat;
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
    // ownership / tenant resolution
    // ==================================================================================

    /** tenant ownership + lifecycle (not-found semantics never leak existence — B2/B4 shape). */
    private DmDsDO requireOwnedDs(String puid, Long dsId) {
        if (dsId == null) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_DS_NOT_FOUND.name()));
        }
        List<DmDsDO> owned = dsDal.dsMapper().listByOwnerAndIds(puid, Collections.singletonList(dsId));
        DmDsDO dsDO = (owned == null || owned.isEmpty()) ? null : owned.get(0);
        if (dsDO == null || !isUsable(dsDO)) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_DS_NOT_FOUND.name()));
        }
        return dsDO;
    }

    private DmDsDO requireOwnedSupportedDs(String puid, Long dsId) {
        DmDsDO dsDO = requireOwnedDs(puid, dsId);
        if (!SUPPORTED.contains(dsDO.getDataSourceType())) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_DS_TYPE_UNSUPPORTED.name()));
        }
        return dsDO;
    }

    /** R3: gh-ost speaks the plain MySQL wire protocol — SSH tunnel / SSL are not expressible. */
    private static void checkDirectConnection(DataSourceConfig dsConfig) {
        if (Boolean.TRUE.equals(dsConfig.getSshProxyEnabled()) //
                || (dsConfig.getSslMode() != null && dsConfig.getSslMode() != SslMode.DISABLED)) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_TUNNEL_UNSUPPORTED.name()));
        }
    }

    private static boolean isUsable(DmDsDO dsDO) {
        return dsDO != null && dsDO.getDataSourceType() != null //
                && dsDO.getLifeCycleState() != LifeCycleState.DELETED && dsDO.getLifeCycleState() != LifeCycleState.DELETING;
    }

    /** task by bizId + tenant containment of its owner (foreign tenant = not-found semantics). */
    private DmExecAsyncTaskDO requireTenantTask(String puid, String sessionId) {
        DmExecAsyncTaskDO task = StringUtils.isBlank(sessionId) ? null //
                : asyncTaskServiceService.queryAsyncTaskByBizId(sessionId, GhostPluginConfig.TOOL_NAME);
        if (task == null || !resolveTenantUids(puid).contains(task.getUid())) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_TASK_NOT_FOUND.name()));
        }
        return task;
    }

    /**
     * The tenant realm of {@code puid} (governance convention): the primary account itself plus
     * every sub-account linked through parentId. Method-local set, never cached.
     */
    private Set<String> resolveTenantUids(String puid) {
        Set<String> uids = new LinkedHashSet<>();
        if (StringUtils.isNotBlank(puid)) {
            uids.add(puid);
        }
        DmAuthUserDO parent = authDal.userMapper().queryByUid(puid);
        if (parent != null && parent.getId() != null) {
            List<DmAuthUserDO> subAccounts = authDal.userMapper().listByParentId(parent.getId());
            if (subAccounts != null) {
                for (DmAuthUserDO sub : subAccounts) {
                    if (sub != null && StringUtils.isNotBlank(sub.getUid())) {
                        uids.add(sub.getUid());
                    }
                }
            }
        }
        return uids;
    }

    // ==================================================================================
    // task configData (de)serialization — the R7 persistence carrier
    // ==================================================================================

    private GhostAsyncTaskConfig parseTaskConfig(DmExecAsyncTaskDO task) {
        if (task == null || StringUtils.isBlank(task.getConfigData())) {
            return null;
        }
        try {
            return JsonUtils.toObj(task.getConfigData(), GhostAsyncTaskConfig.class);
        } catch (Exception e) {
            // JsonUtils sneaky-throws the CHECKED JsonProcessingException — catch Exception
            log.warn("[Ghost] task configData unparsable, taskId={}", task.getId(), e);
            return null;
        }
    }

    private void persistTaskConfig(DmExecAsyncTaskDO task, GhostAsyncTaskConfig cfg) {
        DmExecAsyncTaskDO partial = new DmExecAsyncTaskDO();
        partial.setId(task.getId());
        partial.setConfigData(JsonUtils.toJson(cfg));
        // primitive booleans always land in the MyBatis-Plus NOT_NULL update SET — copy them so
        // the dock visibility / fastFail semantics are never clobbered by the partial update
        partial.setShowInDock(task.isShowInDock());
        partial.setFastFail(task.isFastFail());
        executionDal.asyncTaskMapper().updateById(partial);
        task.setConfigData(partial.getConfigData());
    }

    private static String resolveOwnerUid(DmExecAsyncTaskDO task, GhostAsyncTaskConfig cfg) {
        if (cfg != null && StringUtils.isNotBlank(cfg.getUserId())) {
            return cfg.getUserId();
        }
        return task.getUid();
    }

    private static GhostOpAuditObj auditObjOf(DmExecAsyncTaskDO task, GhostAsyncTaskConfig cfg, String oldTableName) {
        if (cfg == null) {
            return buildAuditObj(task.getBizId(), null, null, null, null, oldTableName);
        }
        return buildAuditObj(cfg.getSessionId(), cfg.getDsId(), cfg.getDbName(), cfg.getTableName(), cfg.getDdlSql(), oldTableName);
    }

    static GhostOpAuditObj buildAuditObj(String sessionId, Long dsId, String dbName, String tableName, String ddlSql, String oldTableName) {
        GhostOpAuditObj obj = new GhostOpAuditObj();
        obj.setSessionId(sessionId);
        obj.setDsId(dsId);
        obj.setDbName(dbName);
        obj.setTableName(tableName);
        obj.setDdlSql(truncate(ddlSql, GhostOpAuditObj.DDL_MAX_LENGTH));
        obj.setOldTableName(oldTableName);
        obj.setResult("SUCCESS");
        return obj;
    }

    static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private void closeQuietly(String uid, String sessionId) {
        try {
            toolsService.closeSession(uid, sessionId);
        } catch (RuntimeException e) {
            log.warn("[Ghost] cleanup closeSession failed for {}", sessionId, e);
        }
    }

    private static void addBlock(GhostPrecheckVO vo, I18nDmMsgKeys key, Object... args) {
        vo.getHardBlocks().add(new GhostBlockVO(key.name(), DmI18nUtils.getMessage(key.name(), args)));
    }

    /** hard block from a statement-checker rejection (the code names ARE the i18n key names). */
    private static void addBlockWithDetail(GhostPrecheckVO vo, String code, String detail) {
        if (I18nDmMsgKeys.GHOST_SCHEMA_MISMATCH.name().equals(code)) {
            addBlock(vo, I18nDmMsgKeys.GHOST_SCHEMA_MISMATCH, detail);
        } else {
            addBlock(vo, I18nDmMsgKeys.GHOST_STMT_UNSUPPORTED);
        }
    }
}
