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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.clougence.clouddm.api.common.boot.UnifiedPostConstruct;
import com.clougence.clouddm.console.web.component.dsconfig.DmDsConfigService;
import com.clougence.clouddm.console.web.component.dsconfig.mode.DsLevels;
import com.clougence.clouddm.console.web.component.governance.ReleaseSchemaPrecheckService;
import com.clougence.clouddm.console.web.component.governance.SchemaDriftDetailDiffer;
import com.clougence.clouddm.console.web.component.governance.SchemaFingerprintUtils;
import com.clougence.clouddm.console.web.component.governance.SchemaPatrolService;
import com.clougence.clouddm.console.web.component.schema.DsSchemaService;
import com.clougence.clouddm.console.web.model.vo.govpatrol.GovPatrolItemVO;
import com.clougence.clouddm.console.web.model.vo.govpatrol.GovPatrolRunDetailVO;
import com.clougence.clouddm.console.web.model.vo.govpatrol.GovPatrolRunVO;
import com.clougence.clouddm.platform.dal.access.DataSourceDal;
import com.clougence.clouddm.platform.dal.access.DbPairDal;
import com.clougence.clouddm.platform.dal.access.GovSchemaPatrolDal;
import com.clougence.clouddm.platform.dal.access.ProdReleaseDal;
import com.clougence.clouddm.platform.dal.access.SystemDal;
import com.clougence.clouddm.platform.dal.access.TicketDbStmtDal;
import com.clougence.clouddm.platform.dal.mapper.datasource.DmDsMapper;
import com.clougence.clouddm.platform.dal.mapper.govpatrol.DmGovSchemaPatrolItemMapper;
import com.clougence.clouddm.platform.dal.mapper.govpatrol.DmGovSchemaPatrolRunMapper;
import com.clougence.clouddm.platform.dal.model.datasource.DmDsDO;
import com.clougence.clouddm.platform.dal.model.dbpair.DmDbPairDO;
import com.clougence.clouddm.platform.dal.model.govpatrol.DmGovSchemaPatrolItemDO;
import com.clougence.clouddm.platform.dal.model.govpatrol.DmGovSchemaPatrolRunDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketStmtSchemaDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseStmtDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseStmtSchemaDO;
import com.clougence.clouddm.sdk.execute.meta.DsElement;
import com.clougence.schema.umi.special.rdb.RdbTable;
import com.clougence.schema.umi.struts.UmiTypes;
import com.clougence.schema.umi.struts.Value;
import com.clougence.utils.ThreadUtils;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * Schema drift patrol engine (sub-task C).
 * <p>
 * For each enabled db pair, fetches live table structures from both pre and prod
 * sides via DsSchemaService (RSocket to worker — console never connects to user
 * DBs directly). Compares fingerprints, classifies diffs into three explain
 * classes, and persists results.
 * <p>
 * Read-only red line: only listLeaf and realTimeFetchSelectObject metadata RPCs
 * are issued — never any DDL/DML against user databases.
 * <p>
 * Throttle: pair-serial, table-serial, inter-table sleep, per-pair table limit,
 * per-pair time budget. Pair-level failure isolation — one pair's error never
 * escapes to the daemon thread.
 */
@Slf4j
@Service
public class SchemaPatrolServiceImpl implements SchemaPatrolService, UnifiedPostConstruct {

    // ==================== config keys ====================
    private static final String CFG_ENABLED         = "govSchemaPatrolEnabled";
    private static final String CFG_INTERVAL_MIN    = "govSchemaPatrolIntervalMinutes";
    private static final String CFG_TABLE_LIMIT     = "govSchemaPatrolTableLimit";
    private static final String CFG_BUDGET_MIN      = "govSchemaPatrolBudgetMinutes";
    private static final String CFG_SLEEP_MS        = "govSchemaPatrolSleepMs";
    private static final String CFG_KEEP_RUNS       = "govSchemaPatrolKeepRuns";

    // ==================== status / class constants ====================
    private static final String STATUS_RUNNING    = "RUNNING";
    private static final String STATUS_DONE       = "DONE";
    private static final String STATUS_PARTIAL    = "PARTIAL";
    private static final String STATUS_FAILED     = "FAILED";
    private static final String STATUS_CANCELLED  = "CANCELLED";

    private static final String DIFF_ONLY_PRE     = "ONLY_PRE";
    private static final String DIFF_ONLY_PROD    = "ONLY_PROD";
    private static final String DIFF_MISMATCH     = "MISMATCH";

    private static final String EXPLAIN_EXEMPT    = "EXEMPT_ACTIVE";
    private static final String EXPLAIN_PENDING   = "PENDING_RELEASE";
    private static final String EXPLAIN_UNEXPLAINED = "UNEXPLAINED";

    private static final String SYSTEM_OPERATOR    = "SYSTEM";

    // ==================== dependencies ====================
    @Resource
    private DsSchemaService              dsSchemaService;
    @Resource
    private DmDsConfigService            configService;
    @Resource
    private DbPairDal                   dbPairDal;
    @Resource
    private DataSourceDal               dsDal;
    @Resource
    private ProdReleaseDal             prodReleaseDal;
    @Resource
    private TicketDbStmtDal           ticketDbStmtDal;
    @Resource
    private GovSchemaPatrolDal         govSchemaPatrolDal;
    @Resource
    private SystemDal                  systemDal;
    @Resource
    private ReleaseSchemaPrecheckService precheckService;

    private ExecutorService patrolExecutor;

    /** Run id currently executing in this process's executor; -1 when idle. */
    private final AtomicLong activeRunId         = new AtomicLong(-1);
    /** Run id requested to stop; the executor thread observes it at checkpoints. */
    private final AtomicLong cancelRequestedRunId = new AtomicLong(-1);

    // ==================== lifecycle ====================

    @Override
    public void init() {
        patrolExecutor = Executors.newSingleThreadExecutor(
            ThreadUtils.daemonThreadFactory(this.getClass().getClassLoader(), "SchemaPatrol-exec-%s"));
        // Orphaned RUNNING rows: a run executing when the previous process died can
        // never finish — without cleanup it permanently blocks startRun (collision
        // guard) and the scheduler tick. Mark FAILED so patrol can run again.
        try {
            DmGovSchemaPatrolRunMapper runMapper = govSchemaPatrolDal.runMapper();
            DmGovSchemaPatrolRunDO orphan;
            while ((orphan = runMapper.queryRunning()) != null) {
                runMapper.updateStatus(orphan.getId(), STATUS_FAILED, orphan.getPairsDone(),
                    orphan.getUnexplainedCount(), "Interrupted by server restart");
                log.info("[SchemaPatrol] Orphaned RUNNING run {} marked FAILED on startup", orphan.getId());
            }
        } catch (Exception e) {
            log.warn("[SchemaPatrol] Startup orphan cleanup failed", e);
        }
        log.info("SchemaPatrolService started");
    }

    @Override
    public void stop() {
        if (patrolExecutor != null) {
            patrolExecutor.shutdownNow();
        }
    }

    // ==================== public API ====================

    @Override
    public long startRun(String triggerType, String startedBy, Long pairId) {
        // Collision guard
        DmGovSchemaPatrolRunMapper runMapper = govSchemaPatrolDal.runMapper();
        if (runMapper.queryRunning() != null) {
            log.info("[SchemaPatrol] Skipping {} run — a RUNNING run already exists", triggerType);
            return -1;
        }

        // Resolve pairs
        List<DmDbPairDO> pairs;
        if (pairId != null) {
            DmDbPairDO pair = dbPairDal.pairMapper().selectById(pairId);
            if (pair == null || !"ENABLED".equals(pair.getStatus())) {
                log.warn("[SchemaPatrol] Pair {} not found or not ENABLED", pairId);
                return -1;
            }
            pairs = List.of(pair);
        } else {
            pairs = listEnabledPairs();
            if (pairs.isEmpty()) {
                log.info("[SchemaPatrol] No enabled db pairs — nothing to patrol");
            }
        }

        // Create run row
        DmGovSchemaPatrolRunDO run = new DmGovSchemaPatrolRunDO();
        run.setTriggerType(triggerType);
        run.setStatus(STATUS_RUNNING);
        run.setPairsTotal(pairs.size());
        run.setPairsDone(0);
        run.setUnexplainedCount(0);
        run.setStartedBy(startedBy);
        runMapper.insertRun(run);
        long runId = run.getId();

        // Async execute — activeRunId set before submit so stopRun never mistakes
        // a queued run for an orphan (cleared in executeRun's finally).
        activeRunId.set(runId);
        final List<DmDbPairDO> finalPairs = pairs;
        patrolExecutor.submit(() -> {
            try {
                executeRun(runId, finalPairs);
            } catch (Exception e) {
                log.error("[SchemaPatrol] executeRun failed for runId={}", runId, e);
                try {
                    runMapper.updateStatus(runId, STATUS_FAILED, 0, 0, truncate(e.getMessage(), 1000));
                } catch (Exception ex) {
                    log.error("[SchemaPatrol] Failed to mark run {} as FAILED", runId, ex);
                }
            }
        });

        return runId;
    }

    @Override
    public String stopRun(long runId) {
        DmGovSchemaPatrolRunDO run = govSchemaPatrolDal.runMapper().queryById(runId);
        if (run == null || !STATUS_RUNNING.equals(run.getStatus())) {
            return STOP_RESULT_NOT_RUNNING;
        }
        if (activeRunId.get() == runId) {
            // Live run in this process — signal the executor thread; it flips the
            // status to CANCELLED at the next per-pair / per-table checkpoint.
            cancelRequestedRunId.set(runId);
            log.info("[SchemaPatrol] Stop requested for active run {}", runId);
            return STOP_RESULT_STOPPING;
        }
        // Orphaned RUNNING row (executor thread died with a previous process) —
        // nobody to signal, mark CANCELLED synchronously.
        govSchemaPatrolDal.runMapper().updateStatus(runId, STATUS_CANCELLED,
            run.getPairsDone(), run.getUnexplainedCount(), "Stopped by user");
        log.info("[SchemaPatrol] Orphaned run {} marked CANCELLED by stop request", runId);
        return STOP_RESULT_STOPPED;
    }

    // ==================== core patrol loop ====================

    @Override
    public List<GovPatrolRunVO> listRuns(int page, int size) {
        IPage<DmGovSchemaPatrolRunDO> pageResult = govSchemaPatrolDal.runMapper()
            .listRuns(new Page<>(page, size));
        List<GovPatrolRunVO> result = new ArrayList<>();
        for (DmGovSchemaPatrolRunDO run : pageResult.getRecords()) {
            result.add(toRunVO(run));
        }
        return result;
    }

    @Override
    public GovPatrolRunDetailVO runDetail(long runId) {
        DmGovSchemaPatrolRunDO run = govSchemaPatrolDal.runMapper().queryById(runId);
        if (run == null) {
            return null;
        }
        GovPatrolRunDetailVO detail = new GovPatrolRunDetailVO();
        detail.setRun(toRunVO(run));
        detail.setItems(listItems(runId));
        return detail;
    }

    @Override
    public List<GovPatrolItemVO> listItems(long runId) {
        List<DmGovSchemaPatrolItemDO> items = govSchemaPatrolDal.itemMapper().queryByRunId(runId);
        List<GovPatrolItemVO> result = new ArrayList<>();
        for (DmGovSchemaPatrolItemDO item : items) {
            GovPatrolItemVO vo = new GovPatrolItemVO();
            vo.setPairId(item.getPairId());
            vo.setPreDbName(item.getPreDbName());
            vo.setProdDbName(item.getProdDbName());
            vo.setTableName(item.getTableName());
            vo.setDiffClass(item.getDiffClass());
            vo.setExplainClass(item.getExplainClass());
            vo.setExplainRef(item.getExplainRef());
            vo.setDetailJson(item.getDetailJson());
            result.add(vo);
        }
        return result;
    }

    private static GovPatrolRunVO toRunVO(DmGovSchemaPatrolRunDO run) {
        GovPatrolRunVO vo = new GovPatrolRunVO();
        vo.setId(run.getId());
        vo.setGmtCreate(run.getGmtCreate());
        vo.setTriggerType(run.getTriggerType());
        vo.setStatus(run.getStatus());
        vo.setPairsTotal(run.getPairsTotal());
        vo.setPairsDone(run.getPairsDone());
        vo.setUnexplainedCount(run.getUnexplainedCount());
        vo.setError(run.getError());
        vo.setStartedBy(run.getStartedBy());
        return vo;
    }

    // ==================== executeRun (async) ====================

    void executeRun(long runId, List<DmDbPairDO> pairs) {
        DmGovSchemaPatrolRunMapper runMapper = govSchemaPatrolDal.runMapper();
        boolean partial = false;
        int totalUnexplained = 0;
        int pairsDone = 0;

        try {
            for (DmDbPairDO pair : pairs) {
                if (isCancelRequested(runId)) {
                    break;
                }
                try {
                    PairResult result = patrolPair(pair, runId);
                    totalUnexplained += result.unexplainedCount;
                    if (result.partial) {
                        partial = true;
                    }
                } catch (Exception e) {
                    log.error("[SchemaPatrol] Pair {} (pre={},prod={}) failed: {}",
                        pair.getId(), pair.getPreDbName(), pair.getProdDbName(), e.getMessage(), e);
                    partial = true;
                }
                try {
                    runMapper.incrementPairsDone(runId);
                } catch (Exception e) {
                    log.warn("[SchemaPatrol] Failed to increment pairs_done for run {}", runId, e);
                }
                pairsDone++;
            }

            String status;
            String error = null;
            if (isCancelRequested(runId)) {
                status = STATUS_CANCELLED;
                error = "Stopped by user";
            } else {
                status = partial ? STATUS_PARTIAL : STATUS_DONE;
            }
            runMapper.updateStatus(runId, status, pairsDone, totalUnexplained, error);

            // Retention cleanup
            int keepRuns = fetchConfig(CFG_KEEP_RUNS, Integer.class, 30);
            try {
                govSchemaPatrolDal.itemMapper().deleteItemsByOldRuns(keepRuns);
                runMapper.deleteOldRuns(keepRuns);
            } catch (Exception e) {
                log.warn("[SchemaPatrol] Retention cleanup failed for run {}", runId, e);
            }

            log.info("[SchemaPatrol] Run {} completed: status={}, pairs={}/{}, unexplained={}",
                runId, status, pairsDone, pairs.size(), totalUnexplained);
        } finally {
            cancelRequestedRunId.compareAndSet(runId, -1);
            activeRunId.compareAndSet(runId, -1);
        }
    }

    private boolean isCancelRequested(long runId) {
        return cancelRequestedRunId.get() == runId;
    }

    // ==================== per-pair patrol ====================

    PairResult patrolPair(DmDbPairDO pair, long runId) throws Exception {
        PairResult result = new PairResult();

        long preDsId = pair.getPreDsId();
        long prodDsId = pair.getProdDsId();
        String preDb = pair.getPreDbName();
        String prodDb = pair.getProdDbName();

        // Resolve DS identities
        DmDsMapper dsMapper = dsDal.dsMapper();
        DmDsDO preDsDO = dsMapper.queryDsIdentityById(preDsId);
        DmDsDO prodDsDO = dsMapper.queryDsIdentityById(prodDsId);
        if (preDsDO == null || prodDsDO == null) {
            log.warn("[SchemaPatrol] DS not found for pair {}: pre={}, prod={}",
                pair.getId(), preDsId, prodDsId);
            result.partial = true;
            return result;
        }

        // Build levelsParam for both sides
        Map<UmiTypes, Object> preLevelsParam = buildLevelsParam(preDsDO, preDb);
        Map<UmiTypes, Object> prodLevelsParam = buildLevelsParam(prodDsDO, prodDb);

        // Build explain context (EXEMPT_ACTIVE + PENDING_RELEASE table sets)
        ExplainContext ctx = buildExplainContext(pair);

        // Fetch table lists (refreshCache=true to discover out-of-band tables)
        List<String> preTables = listTableNames(preDsDO, preLevelsParam);
        List<String> prodTables = listTableNames(prodDsDO, prodLevelsParam);

        Set<String> preSet = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        preSet.addAll(preTables);
        Set<String> prodSet = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        prodSet.addAll(prodTables);

        // Compute diffs
        Set<String> onlyPre = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        onlyPre.addAll(preSet);
        onlyPre.removeAll(prodSet);

        Set<String> onlyProd = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        onlyProd.addAll(prodSet);
        onlyProd.removeAll(preSet);

        Set<String> common = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        common.addAll(preSet);
        common.retainAll(prodSet);

        // Throttle config
        int tableLimit = fetchConfig(CFG_TABLE_LIMIT, Integer.class, 500);
        long sleepMs = fetchConfig(CFG_SLEEP_MS, Long.class, 50L);
        long budgetMs = fetchConfig(CFG_BUDGET_MIN, Long.class, 10L) * 60_000L;

        long startTime = System.currentTimeMillis();
        List<DmGovSchemaPatrolItemDO> items = new ArrayList<>();
        int processed = 0;
        boolean budgetExceeded = false;

        // Process ONLY_PRE
        for (String table : onlyPre) {
            if (isCancelRequested(runId)) break;
            if (processed >= tableLimit || budgetExceeded) break;
            budgetExceeded = checkBudget(startTime, budgetMs);
            if (budgetExceeded) break;
            sleep(sleepMs);

            String detail = SchemaDriftDetailDiffer.onlyOneSideJson(true, false);
            ExplainResult explain = classifyExplain(table, DIFF_ONLY_PRE, ctx);
            items.add(buildItem(runId, pair, table, DIFF_ONLY_PRE, explain, detail));
            processed++;
        }

        // Process ONLY_PROD
        for (String table : onlyProd) {
            if (isCancelRequested(runId)) break;
            if (processed >= tableLimit || budgetExceeded) break;
            budgetExceeded = checkBudget(startTime, budgetMs);
            if (budgetExceeded) break;
            sleep(sleepMs);

            String detail = SchemaDriftDetailDiffer.onlyOneSideJson(false, true);
            ExplainResult explain = classifyExplain(table, DIFF_ONLY_PROD, ctx);
            items.add(buildItem(runId, pair, table, DIFF_ONLY_PROD, explain, detail));
            processed++;
        }

        // Process common tables (MISMATCH check)
        for (String table : common) {
            if (isCancelRequested(runId)) break;
            if (processed >= tableLimit || budgetExceeded) break;
            budgetExceeded = checkBudget(startTime, budgetMs);
            if (budgetExceeded) break;
            sleep(sleepMs);

            RdbTable preTable = fetchTable(preDsDO, preLevelsParam, table);
            RdbTable prodTable = fetchTable(prodDsDO, prodLevelsParam, table);

            String preFp = fingerprintOf(preTable);
            String prodFp = fingerprintOf(prodTable);

            if (!preFp.equals(prodFp)) {
                String preJson = preTable != null ? SchemaFingerprintUtils.canonicalJson(preTable) : null;
                String prodJson = prodTable != null ? SchemaFingerprintUtils.canonicalJson(prodTable) : null;
                String detail = SchemaDriftDetailDiffer.diffTableJson(preJson, prodJson);
                ExplainResult explain = classifyExplain(table, DIFF_MISMATCH, ctx);
                items.add(buildItem(runId, pair, table, DIFF_MISMATCH, explain, detail));
            }
            processed++;
        }

        if (processed >= tableLimit) {
            log.info("[SchemaPatrol] Pair {} hit table limit ({}), marking PARTIAL", pair.getId(), tableLimit);
            result.partial = true;
        }
        if (budgetExceeded) {
            log.info("[SchemaPatrol] Pair {} exceeded time budget ({}ms), marking PARTIAL", pair.getId(), budgetMs);
            result.partial = true;
        }

        // Batch insert items
        if (!items.isEmpty()) {
            govSchemaPatrolDal.itemMapper().batchInsert(items);
        }

        // Count unexplained
        for (DmGovSchemaPatrolItemDO item : items) {
            if (EXPLAIN_UNEXPLAINED.equals(item.getExplainClass())) {
                result.unexplainedCount++;
            }
        }

        log.info("[SchemaPatrol] Pair {} (pre={}/{}, prod={}/{}) done: {} diff items, {} unexplained",
            pair.getId(), preDsId, preDb, prodDsId, prodDb, items.size(), result.unexplainedCount);

        return result;
    }

    // ==================== explain context ====================

    private ExplainContext buildExplainContext(DmDbPairDO pair) {
        Map<String, String> exemptActiveTables = new TreeMapCI<>();
        Map<String, String> pendingReleaseTables = new TreeMapCI<>();

        // EXEMPT_ACTIVE: active releases touching this pair's prod DB
        List<DmProdReleaseDO> activeReleases = prodReleaseDal.releaseMapper().listActive();
        for (DmProdReleaseDO release : activeReleases) {
            List<DmProdReleaseStmtDO> stmts = prodReleaseDal.stmtMapper().queryByReleaseId(release.getId());
            for (DmProdReleaseStmtDO stmt : stmts) {
                // Filter to this pair's prod DB
                if (!pair.getProdDsId().equals(stmt.getProdDsId()) ||
                    !pair.getProdDbName().equals(stmt.getProdDbName())) {
                    continue;
                }
                // Get tables from schema snapshot rows
                List<DmProdReleaseStmtSchemaDO> schemaRows = prodReleaseDal.schemaMapper()
                    .queryByReleaseStmtId(stmt.getId());
                if (!schemaRows.isEmpty()) {
                    for (DmProdReleaseStmtSchemaDO row : schemaRows) {
                        exemptActiveTables.put(row.getTableName(), release.getReleaseNo());
                    }
                } else {
                    // No snapshot rows — extract tables from SQL content
                    Set<String> extracted = precheckService.extractTables(stmt.getSqlContent());
                    for (String table : extracted) {
                        exemptActiveTables.put(table, release.getReleaseNo());
                    }
                }
            }
        }

        // PENDING_RELEASE: SUCCESS pre stmts not merged into any release
        List<DmTicketDbStmtDO> preStmts = ticketDbStmtDal.stmtMapper()
            .queryByDsAndDb(pair.getPreDsId(), pair.getPreDbName());
        for (DmTicketDbStmtDO stmt : preStmts) {
            if (!"SUCCESS".equals(stmt.getExecStatus())) {
                continue;
            }
            // Check if merged
            DmProdReleaseStmtDO merged = prodReleaseDal.stmtMapper()
                .queryBySourceStmtId(stmt.getId());
            if (merged != null) {
                continue; // already merged — not pending
            }
            // Get snapshot tables
            List<DmTicketStmtSchemaDO> snapshotRows = ticketDbStmtDal.schemaMapper()
                .queryByStmtId(stmt.getId());
            for (DmTicketStmtSchemaDO row : snapshotRows) {
                pendingReleaseTables.put(row.getTableName(), String.valueOf(stmt.getTicketId()));
            }
        }

        return new ExplainContext(exemptActiveTables, pendingReleaseTables);
    }

    private ExplainResult classifyExplain(String tableName, String diffClass, ExplainContext ctx) {
        // 1. EXEMPT_ACTIVE takes priority
        String releaseNo = ctx.exemptActiveTables.get(tableName);
        if (releaseNo != null) {
            return new ExplainResult(EXPLAIN_EXEMPT, releaseNo);
        }
        // 2. ONLY_PROD is always prod-leading — never PENDING_RELEASE
        if (DIFF_ONLY_PROD.equals(diffClass)) {
            return new ExplainResult(EXPLAIN_UNEXPLAINED, null);
        }
        // 3. ONLY_PRE or MISMATCH — check PENDING_RELEASE (pre-leading only)
        String ticketRef = ctx.pendingReleaseTables.get(tableName);
        if (ticketRef != null) {
            return new ExplainResult(EXPLAIN_PENDING, ticketRef);
        }
        // 4. No explanation source — real drift
        return new ExplainResult(EXPLAIN_UNEXPLAINED, null);
    }

    // ==================== helper methods ====================

    private List<DmDbPairDO> listEnabledPairs() {
        LambdaQueryWrapper<DmDbPairDO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DmDbPairDO::getStatus, "ENABLED");
        return dbPairDal.pairMapper().selectList(wrapper);
    }

    private Map<UmiTypes, Object> buildLevelsParam(DmDsDO dsDO, String dbName) {
        List<String> levels = new ArrayList<>();
        levels.add(dsDO.getDsEnvId().toString());
        levels.add(String.valueOf(dsDO.getId()));
        levels.add(dbName);
        DsLevels dsLevels = configService.parseLevels(levels);
        return dsLevels.levelsParam();
    }

    private List<String> listTableNames(DmDsDO dsDO, Map<UmiTypes, Object> levelsParam) {
        List<DsElement> elements = dsSchemaService.listLeaf(dsDO, levelsParam, UmiTypes.Table, null, true);
        if (elements == null || elements.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> names = new ArrayList<>();
        for (DsElement e : elements) {
            if (e.getObjName() != null && !e.getObjName().isEmpty()) {
                names.add(e.getObjName());
            }
        }
        return names;
    }

    private RdbTable fetchTable(DmDsDO dsDO, Map<UmiTypes, Object> levelsParam, String tableName) {
        try {
            Value value = dsSchemaService.realTimeFetchSelectObject(dsDO, levelsParam, tableName);
            if (value instanceof RdbTable) {
                return (RdbTable) value;
            }
        } catch (Exception e) {
            log.warn("[SchemaPatrol] Failed to fetch table {} from DS {}: {}",
                tableName, dsDO.getId(), e.getMessage());
        }
        return null;
    }

    private static String fingerprintOf(RdbTable table) {
        if (table == null) {
            return SchemaFingerprintUtils.NON_EXISTENT_FINGERPRINT;
        }
        return SchemaFingerprintUtils.fingerprint(table);
    }

    private boolean checkBudget(long startTime, long budgetMs) {
        return (System.currentTimeMillis() - startTime) >= budgetMs;
    }

    private void sleep(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private DmGovSchemaPatrolItemDO buildItem(long runId, DmDbPairDO pair, String tableName,
                                               String diffClass, ExplainResult explain, String detailJson) {
        DmGovSchemaPatrolItemDO item = new DmGovSchemaPatrolItemDO();
        item.setRunId(runId);
        item.setPairId(pair.getId());
        item.setPreDsId(pair.getPreDsId());
        item.setPreDbName(pair.getPreDbName());
        item.setProdDsId(pair.getProdDsId());
        item.setProdDbName(pair.getProdDbName());
        item.setTableName(tableName);
        item.setDiffClass(diffClass);
        item.setExplainClass(explain.explainClass);
        item.setExplainRef(explain.ref);
        item.setDetailJson(detailJson);
        return item;
    }

    private <T> T fetchConfig(String key, Class<T> type, T defaultValue) {
        try {
            return systemDal.fetchSystemConf(key, type, defaultValue);
        } catch (Exception e) {
            log.warn("[SchemaPatrol] Failed to read config {}: {}", key, e.getMessage());
            return defaultValue;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    // ==================== inner types ====================

    static class PairResult {
        int  unexplainedCount;
        boolean partial;
    }

    static class ExplainResult {
        final String explainClass;
        final String ref;

        ExplainResult(String explainClass, String ref) {
            this.explainClass = explainClass;
            this.ref = ref;
        }
    }

    static class ExplainContext {
        final Map<String, String> exemptActiveTables;
        final Map<String, String> pendingReleaseTables;

        ExplainContext(Map<String, String> exemptActiveTables, Map<String, String> pendingReleaseTables) {
            this.exemptActiveTables = exemptActiveTables;
            this.pendingReleaseTables = pendingReleaseTables;
        }
    }

    /** Case-insensitive TreeMap for table name lookups. */
    static class TreeMapCI<V> extends java.util.TreeMap<String, V> {
        TreeMapCI() {
            super(String.CASE_INSENSITIVE_ORDER);
        }
    }
}
