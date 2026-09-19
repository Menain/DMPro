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
package com.clougence.clouddm.console.web.service.lockanalysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
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
import com.clougence.clouddm.console.web.model.fo.lockanalysis.LockOverviewFO;
import com.clougence.clouddm.console.web.model.vo.lockanalysis.LockOverviewVO;
import com.clougence.clouddm.console.web.model.vo.lockanalysis.LockTrxVO;
import com.clougence.clouddm.console.web.model.vo.lockanalysis.LockWaitVO;
import com.clougence.clouddm.console.web.model.vo.session.SessionInstanceVO;
import com.clougence.clouddm.platform.dal.access.DataSourceDal;
import com.clougence.clouddm.platform.dal.model.LifeCycleState;
import com.clougence.clouddm.platform.dal.model.datasource.DmDsDO;
import com.clougence.utils.StringUtils;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * Lock &amp; deadlock analysis service (B3), design 09-17-lock-deadlock-analysis.
 * <p>
 * Read-only diagnostics through channel C, running on the shared {@link OpsDiagnosticExecutor}
 * (short-lived worker session, createSession → timeout-wrapped statements → finally release;
 * the B4 Step-0 extraction converged the former byte-identical B2/B3 private copies). Timeout
 * granularity is per-statement: createSession and every SQL are wrapped individually, so one
 * hanging block never starves the others. The executor's neutral {@link DiagTimeoutException} is
 * translated here by role — {@link LockQueryTimeoutException} remains the B3-internal marker.
 * <p>
 * Dialects (design §2, dispatched by dsType — never by guessing from the version string):
 * <ul>
 * <li>MySQL 8.0 branch: {@code SELECT VERSION()} defense (MariaDB / major &lt; 8 / unparsable →
 * LOCK_VERSION_UNSUPPORTED) → {@code @@performance_schema} probe (OFF → lock-wait block degrades,
 * querying data_locks would fake "no lock waits") → data_lock_waits join → innodb_trx →
 * {@code SHOW ENGINE INNODB STATUS} with an explicit displayChars (131072, kept by
 * {@code DmDsUtils.fillRequestConfig}) so the LATEST DETECTED DEADLOCK section survives;</li>
 * <li>PG branch: pg_blocking_pids LATERAL expansion + pg_stat_activity active transactions;
 * no deadlock query (PG deadlock details live in the server log) → deadlockSupported=false;
 * {@code <insufficient privileges>} masking (no pg_monitor) → privilegeHint, not an error.</li>
 * </ul>
 * Error semantics (design §7): probe failure/timeout fails the whole overview; a MySQL
 * access/command-denied failure on any block fails the whole overview (all three blocks depend
 * on PROCESS); any other single-block failure/timeout degrades only that block (partialErrors).
 */
@Slf4j
@Service
public class LockAnalysisServiceImpl implements LockAnalysisService {

    /** Whitelist: strictly the datasource planning (MySQL 8.0 + PG 15); unverified MySQL-compatible types stay out (design §1). */
    static final Set<DataSourceType> SUPPORTED = EnumSet.of(DataSourceType.MySQL, DataSourceType.PostgreSQL);

    static final String BRANCH_MYSQL_8  = "MYSQL_8";
    static final String BRANCH_POSTGRES = "POSTGRES";

    /** partialErrors keys (design §4.1). */
    static final String BLOCK_LOCK_WAITS  = "lockWaits";
    static final String BLOCK_TRANSACTIONS = "transactions";
    static final String BLOCK_DEADLOCK    = "deadlock";

    // ---- MySQL 8.0 branch SQL (design §4.2) ----
    static final String SQL_VERSION       = "SELECT VERSION()";
    static final String SQL_PS_ENABLED    = "SELECT @@performance_schema AS ps_enabled";
    static final String SQL_MY_LOCK_WAITS = "SELECT r.trx_mysql_thread_id AS waiting_pid, r.trx_query AS waiting_query, " //
            + "TIMESTAMPDIFF(SECOND, r.trx_wait_started, NOW()) AS wait_seconds, " //
            + "CONCAT(l.OBJECT_SCHEMA, '.', l.OBJECT_NAME) AS lock_table, l.INDEX_NAME AS lock_index, " //
            + "l.LOCK_TYPE AS lock_type, l.LOCK_MODE AS lock_mode, l.LOCK_DATA AS lock_data, " //
            + "b.trx_mysql_thread_id AS blocking_pid, b.trx_id AS blocking_trx_id, b.trx_query AS blocking_query, " //
            + "b.trx_started AS blocking_trx_started, b.trx_rows_locked AS blocking_rows_locked " //
            + "FROM performance_schema.data_lock_waits w " //
            + "JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID " //
            + "JOIN information_schema.innodb_trx r ON r.trx_id = w.REQUESTING_ENGINE_TRANSACTION_ID " //
            + "JOIN information_schema.innodb_trx b ON b.trx_id = w.BLOCKING_ENGINE_TRANSACTION_ID " //
            + "ORDER BY wait_seconds DESC";
    static final String SQL_MY_TRX        = "SELECT trx_id, trx_state, trx_started, " //
            + "TIMESTAMPDIFF(SECOND, trx_started, NOW()) AS duration_seconds, " //
            + "trx_rows_locked, trx_rows_modified, trx_mysql_thread_id AS thread_pid, trx_query AS query " //
            + "FROM information_schema.innodb_trx ORDER BY trx_started";
    static final String SQL_INNODB_STATUS = "SHOW ENGINE INNODB STATUS";

    // ---- PostgreSQL branch SQL (design §4.3) ----
    static final String SQL_PG_LOCK_WAITS = "SELECT blocked.pid::text AS waiting_pid, blocked.query AS waiting_query, " //
            + "EXTRACT(EPOCH FROM (now() - blocked.query_start))::bigint AS wait_seconds, " //
            + "COALESCE(rl.relation::regclass::text, rl.locktype) AS lock_table, " //
            + "NULL AS lock_index, rl.locktype AS lock_type, rl.mode AS lock_mode, NULL AS lock_data, " //
            + "blocking.pid::text AS blocking_pid, NULL AS blocking_trx_id, blocking.query AS blocking_query, " //
            + "blocking.xact_start AS blocking_trx_started, NULL AS blocking_rows_locked " //
            + "FROM pg_stat_activity blocked " //
            + "JOIN LATERAL unnest(pg_blocking_pids(blocked.pid)) AS bp(pid) ON true " //
            + "JOIN pg_stat_activity blocking ON blocking.pid = bp.pid " //
            + "LEFT JOIN pg_locks rl ON rl.pid = blocked.pid AND NOT rl.granted " //
            + "ORDER BY wait_seconds DESC";
    static final String SQL_PG_TRX        = "SELECT NULL AS trx_id, a.state, a.xact_start AS started, " //
            + "EXTRACT(EPOCH FROM (now() - a.xact_start))::bigint AS duration_seconds, " //
            + "NULL AS rows_locked, NULL AS rows_modified, a.pid::text AS thread_pid, a.query " //
            + "FROM pg_stat_activity a " //
            + "WHERE a.xact_start IS NOT NULL AND a.backend_type = 'client backend' " //
            + "ORDER BY a.xact_start";

    /** INNODB STATUS carries the deadlock log as one huge cell — request it undisplayed-truncated (design §4.4 passthrough). */
    static final int    DEADLOCK_STATUS_DISPLAY_CHARS = 131072;

    static final String DEADLOCK_SECTION_MARKER       = "LATEST DETECTED DEADLOCK";
    static final String DEADLOCK_TRUNCATION_MARKER    = "…";
    /** A section-separator line inside INNODB STATUS is a run of dashes (banners use 12+). */
    static final int    DASH_SEPARATOR_MIN_LENGTH     = 10;

    /** PG native masking of the query text when the viewing account lacks pg_monitor (B2 §13.4 contract). */
    static final String PG_INSUFFICIENT_PRIVILEGES    = "<insufficient privileges>";

    /** Lowercased MySQL error fragments meaning the DS account lacks the PROCESS privilege (design §5). */
    static final List<String> NO_PRIVILEGE_ERRORS     = List.of("access denied", "command denied");

    /** Hard timeout (seconds) for one remote operation; tests may shorten it. */
    private long                     queryTimeoutSeconds = 10;

    @Resource
    private DataSourceDal            dsDal;
    @Resource
    private DmDsConfigService        dmDsConfigService;
    @Resource
    private OpsDiagnosticExecutor    diagnosticExecutor;

    // ==================================================================================
    // listInstances
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
    // overview
    // ==================================================================================

    @Override
    public LockOverviewVO overview(String puid, String uid, LockOverviewFO fo) {
        DmDsDO dsDO = requireOwnedSupportedDs(puid, fo == null ? null : fo.getDsId());
        DataSourceConfig dsConfig = dmDsConfigService.fetchDsConfigFromExists(dsDO.getId());
        LockOverviewVO vo = new LockOverviewVO();
        try {
            return diagnosticExecutor.runInSession(uid, dsDO, dsConfig, diagOpts(), session -> {
                if (isPostgres(dsDO.getDataSourceType())) {
                    fillPostgresOverview(session, vo);
                } else {
                    fillMySqlOverview(session, vo);
                }
                return vo;
            });
        } catch (DiagTimeoutException e) {
            // only the createSession wrap can escape here — probe/statement timeouts are
            // translated at their call sites (probe → whole failure, block → partialErrors)
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.LOCK_QUERY_TIMEOUT.name()));
        }
    }

    /** B3 granularity: per-statement wrapping (createSession included), autoCommit read-only shape. */
    private DiagOpts diagOpts() {
        return DiagOpts.builder().timeoutSeconds((int) queryTimeoutSeconds).build();
    }

    private void fillMySqlOverview(DiagSession session, LockOverviewVO vo) {
        vo.setBranch(BRANCH_MYSQL_8);
        vo.setDeadlockSupported(true);

        // version defense (design §4.2): 8.0 physically removed the 5.7 lock views — refusing here
        // avoids bizarre SQL errors; unparsable strings are refused too (no 5.7 fallback exists)
        String version = probeSingleValue(session, SQL_VERSION);
        vo.setVersion(version);
        if (!isMySql8OrLater(version)) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.LOCK_VERSION_UNSUPPORTED.name(), version));
        }

        // performance_schema probe: when OFF, data_locks is silently empty — degrade the lock-wait
        // block instead of faking "no lock waits"; transactions/deadlock are unaffected
        String psEnabled = probeSingleValue(session, SQL_PS_ENABLED);
        if ("1".equals(StringUtils.trimToEmpty(psEnabled))) {
            vo.setLockWaits(mapLockWaits(queryBlock(session, SQL_MY_LOCK_WAITS, 0, BLOCK_LOCK_WAITS, vo, true)));
        } else {
            vo.getPartialErrors().put(BLOCK_LOCK_WAITS, DmI18nUtils.getMessage(I18nDmMsgKeys.LOCK_PS_DISABLED.name()));
        }

        vo.setTransactions(mapTransactions(queryBlock(session, SQL_MY_TRX, 0, BLOCK_TRANSACTIONS, vo, true)));

        // latest deadlock: the Status cell needs the explicit displayChars passthrough (design §4.4)
        fillDeadlock(vo, queryBlock(session, SQL_INNODB_STATUS, DEADLOCK_STATUS_DISPLAY_CHARS, BLOCK_DEADLOCK, vo, true));
    }

    private void fillPostgresOverview(DiagSession session, LockOverviewVO vo) {
        vo.setBranch(BRANCH_POSTGRES);
        // PG deadlock details live in the server log (unreachable on cloud instances) — hide the
        // section instead of showing a fake "no deadlock" empty state (design §4.3)
        vo.setDeadlockSupported(false);

        vo.setLockWaits(mapLockWaits(queryBlock(session, SQL_PG_LOCK_WAITS, 0, BLOCK_LOCK_WAITS, vo, false)));
        vo.setTransactions(mapTransactions(queryBlock(session, SQL_PG_TRX, 0, BLOCK_TRANSACTIONS, vo, false)));
        vo.setPrivilegeHint(resolvePgPrivilegeHint(vo.getLockWaits(), vo.getTransactions()));
    }

    /** Latest-deadlock block: extract the section from the INNODB STATUS text; absent section is a normal state, not an error. */
    private static void fillDeadlock(LockOverviewVO vo, List<Map<String, String>> statusRows) {
        if (statusRows == null) {
            return; // block degraded — partialErrors already carries the i18ned reason
        }
        String statusText = statusRows.isEmpty() ? null : statusRows.get(0).get("status");
        String section = extractDeadlockSection(statusText);
        if (section == null) {
            vo.setDeadlockAbsent(true);
            return;
        }
        vo.setDeadlock(section);
    }

    // ==================================================================================
    // block queries: per-SQL hard timeout + block-level degradation (design §7)
    // ==================================================================================

    /** Single-row single-value probe (VERSION / @@performance_schema); failure or timeout fails the whole overview. */
    private String probeSingleValue(DiagSession session, String sql) {
        try {
            return session.executeSingleValue(sql);
        } catch (DiagTimeoutException e) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.LOCK_QUERY_TIMEOUT.name()));
        }
    }

    /**
     * Runs one block query. Returns the flattened rows, or null after recording the block-level
     * degradation into {@code partialErrors} (timeout / non-privilege failure). A MySQL
     * access/command-denied failure escalates to a whole-overview LOCK_QUERY_NO_PRIVILEGE
     * (all MySQL blocks depend on the PROCESS privilege) when {@code escalateNoPrivilege}.
     */
    private List<Map<String, String>> queryBlock(DiagSession session, String sql, int displayChars, String blockKey, LockOverviewVO vo,
            boolean escalateNoPrivilege) {
        ResultList resultList;
        try {
            resultList = executeStatement(session, sql, displayChars);
        } catch (LockQueryTimeoutException e) {
            log.warn("[LockAnalysis] block {} query timed out after {}s", blockKey, queryTimeoutSeconds);
            vo.getPartialErrors().put(blockKey, DmI18nUtils.getMessage(I18nDmMsgKeys.LOCK_QUERY_TIMEOUT.name()));
            return null;
        }
        String failure = DiagResultUtils.firstFailureMessage(resultList);
        if (failure != null) {
            if (escalateNoPrivilege && isNoPrivilegeFailure(failure)) {
                throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.LOCK_QUERY_NO_PRIVILEGE.name(), failure));
            }
            log.warn("[LockAnalysis] block {} query failed: {}", blockKey, failure);
            vo.getPartialErrors().put(blockKey, DmI18nUtils.getMessage(I18nDmMsgKeys.CONSOLE_QUERY_EXEC_ERROR.name(), failure));
            return null;
        }
        return DiagResultUtils.extractRows(resultList);
    }

    /**
     * Statement execution with the B3-internal timeout marker (design §1.3 C2): the executor's
     * neutral {@link DiagTimeoutException} is translated into {@link LockQueryTimeoutException}
     * so the block-level degradation contract above stays role-specific.
     */
    private static ResultList executeStatement(DiagSession session, String sql, int displayChars) {
        try {
            return displayChars > 0 ? session.execute(sql, displayChars) : session.execute(sql);
        } catch (DiagTimeoutException e) {
            throw new LockQueryTimeoutException();
        }
    }

    static boolean isNoPrivilegeFailure(String failureMessage) {
        String lowerFailure = failureMessage.toLowerCase(Locale.ROOT);
        for (String fragment : NO_PRIVILEGE_ERRORS) {
            if (lowerFailure.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    // ==================================================================================
    // row mapping (column aliases are unified snake_case across both dialects' SQL, design §4.3)
    // ==================================================================================

    private static List<LockWaitVO> mapLockWaits(List<Map<String, String>> rows) {
        if (rows == null) {
            return null;
        }
        List<LockWaitVO> waits = new ArrayList<>();
        for (Map<String, String> row : rows) {
            LockWaitVO vo = new LockWaitVO();
            vo.setWaitingPid(row.get("waiting_pid"));
            vo.setWaitingQuery(row.get("waiting_query"));
            vo.setWaitSeconds(parseLong(row.get("wait_seconds")));
            vo.setLockTable(row.get("lock_table"));
            vo.setLockIndex(row.get("lock_index"));
            vo.setLockType(row.get("lock_type"));
            vo.setLockMode(row.get("lock_mode"));
            vo.setLockData(row.get("lock_data"));
            vo.setBlockingPid(row.get("blocking_pid"));
            vo.setBlockingTrxId(row.get("blocking_trx_id"));
            vo.setBlockingQuery(row.get("blocking_query"));
            vo.setBlockingTrxStarted(row.get("blocking_trx_started"));
            vo.setBlockingRowsLocked(parseLong(row.get("blocking_rows_locked")));
            waits.add(vo);
        }
        return waits;
    }

    private static List<LockTrxVO> mapTransactions(List<Map<String, String>> rows) {
        if (rows == null) {
            return null;
        }
        List<LockTrxVO> transactions = new ArrayList<>();
        for (Map<String, String> row : rows) {
            LockTrxVO vo = new LockTrxVO();
            vo.setTrxId(row.get("trx_id"));
            vo.setState(pickColumn(row, "trx_state", "state"));
            vo.setStarted(pickColumn(row, "trx_started", "started"));
            vo.setDurationSeconds(parseLong(row.get("duration_seconds")));
            vo.setRowsLocked(parseLong(pickColumn(row, "trx_rows_locked", "rows_locked")));
            vo.setRowsModified(parseLong(pickColumn(row, "trx_rows_modified", "rows_modified")));
            vo.setThreadPid(row.get("thread_pid"));
            vo.setQuery(row.get("query"));
            transactions.add(vo);
        }
        return transactions;
    }

    /** First present column wins — innodb_trx and the PG projection name the same field differently. */
    private static String pickColumn(Map<String, String> row, String... names) {
        for (String name : names) {
            if (row.containsKey(name)) {
                return row.get(name);
            }
        }
        return null;
    }

    /**
     * PG only (B2 §13.4 contract): any masked query text means the DS account lacks pg_monitor —
     * return the already-translated hint for the frontend Alert. Never set for the MySQL branch.
     */
    static String resolvePgPrivilegeHint(List<LockWaitVO> lockWaits, List<LockTrxVO> transactions) {
        if (lockWaits != null) {
            for (LockWaitVO wait : lockWaits) {
                if (isMaskedQuery(wait.getWaitingQuery()) || isMaskedQuery(wait.getBlockingQuery())) {
                    return DmI18nUtils.getMessage(I18nDmMsgKeys.LOCK_QUERY_PRIVILEGE_HINT_PG.name());
                }
            }
        }
        if (transactions != null) {
            for (LockTrxVO trx : transactions) {
                if (isMaskedQuery(trx.getQuery())) {
                    return DmI18nUtils.getMessage(I18nDmMsgKeys.LOCK_QUERY_PRIVILEGE_HINT_PG.name());
                }
            }
        }
        return null;
    }

    private static boolean isMaskedQuery(String query) {
        return query != null && query.toLowerCase(Locale.ROOT).contains(PG_INSUFFICIENT_PRIVILEGES);
    }

    // ==================================================================================
    // version defense & deadlock section extraction
    // ==================================================================================

    /** MySQL branch only: MariaDB and any major &lt; 8 (or unparsable string) are out of planning scope (design §4.2). */
    static boolean isMySql8OrLater(String version) {
        if (StringUtils.isBlank(version)) {
            return false;
        }
        if (version.toLowerCase(Locale.ROOT).contains("mariadb")) {
            return false;
        }
        Integer major = parseMajorVersion(version);
        return major != null && major >= 8;
    }

    /** Leading integer before the first dot ("8.0.32" → 8, "5.7.44-log" → 5, "garbage" → null). */
    static Integer parseMajorVersion(String version) {
        String trimmed = version.trim();
        int dot = trimmed.indexOf('.');
        String head = dot > 0 ? trimmed.substring(0, dot) : trimmed;
        if (!isPureDigits(head)) {
            return null;
        }
        try {
            return Integer.parseInt(head);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Extracts the LATEST DETECTED DEADLOCK section from the raw INNODB STATUS text: content
     * starts after the marker banner's closing dash line and ends before the next ≥10-dash
     * separator. No marker → null (deadlockAbsent, a normal state). No closing separator → the
     * text was cut by the column-bytes limit — keep everything to the tail plus an ellipsis
     * marker (truncation is not a failure, design §4.2).
     */
    static String extractDeadlockSection(String statusText) {
        if (StringUtils.isBlank(statusText)) {
            return null;
        }
        String[] lines = statusText.split("\n", -1);
        int markerIndex = -1;
        for (int i = 0; i < lines.length; i++) {
            if (DEADLOCK_SECTION_MARKER.equalsIgnoreCase(StringUtils.trimToEmpty(lines[i]))) {
                markerIndex = i;
                break;
            }
        }
        if (markerIndex < 0) {
            return null;
        }
        int start = markerIndex + 1;
        while (start < lines.length && isDashSeparator(lines[start])) {
            start++;
        }
        int end = start;
        while (end < lines.length && !isDashSeparator(lines[end])) {
            end++;
        }
        StringBuilder section = new StringBuilder();
        for (int i = start; i < end; i++) {
            if (section.length() > 0) {
                section.append('\n');
            }
            section.append(lines[i]);
        }
        if (end >= lines.length) {
            if (section.length() > 0) {
                section.append('\n');
            }
            section.append(DEADLOCK_TRUNCATION_MARKER);
        }
        return section.toString();
    }

    private static boolean isDashSeparator(String line) {
        String trimmed = StringUtils.trimToEmpty(line);
        if (trimmed.length() < DASH_SEPARATOR_MIN_LENGTH) {
            return false;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (trimmed.charAt(i) != '-') {
                return false;
            }
        }
        return true;
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
    // ownership & whitelist validation (B2 pattern, LOCK_* keys per design §5)
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
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.LOCK_DS_TYPE_UNSUPPORTED.name()));
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

    static boolean isPureDigits(String value) {
        return StringUtils.isNotBlank(value) && value.chars().allMatch(Character::isDigit);
    }
}
