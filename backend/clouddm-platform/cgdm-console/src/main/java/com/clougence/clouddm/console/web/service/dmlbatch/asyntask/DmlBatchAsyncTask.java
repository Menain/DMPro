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
package com.clougence.clouddm.console.web.service.dmlbatch.asyntask;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import com.clougence.clouddm.api.sidecar.session.execute.ResultList;
import com.clougence.clouddm.base.metadata.ds.DataSourceConfig;
import com.clougence.clouddm.base.metadata.ds.DataSourceType;
import com.clougence.clouddm.console.web.component.dsconfig.DmDsConfigService;
import com.clougence.clouddm.console.web.component.dsconfig.mode.DsLevels;
import com.clougence.clouddm.console.web.component.execute.asyntask.AsyncTask;
import com.clougence.clouddm.console.web.component.governance.TicketSegment;
import com.clougence.clouddm.console.web.component.opsdiag.DiagOpts;
import com.clougence.clouddm.console.web.component.opsdiag.DiagResultUtils;
import com.clougence.clouddm.console.web.component.opsdiag.DiagSession;
import com.clougence.clouddm.console.web.component.opsdiag.DiagTimeoutException;
import com.clougence.clouddm.console.web.component.opsdiag.OpsDiagnosticExecutor;
import com.clougence.clouddm.console.web.global.i18n.DmI18nUtils;
import com.clougence.clouddm.console.web.global.i18n.I18nDmMsgKeys;
import com.clougence.clouddm.console.web.service.asyntask.AsyncTaskServiceService;
import com.clougence.clouddm.console.web.service.dmlbatch.DmlBatchEffectiveParams;
import com.clougence.clouddm.console.web.service.dmlbatch.DmlBatchLiterals;
import com.clougence.clouddm.console.web.service.dmlbatch.DmlBatchServiceImpl;
import com.clougence.clouddm.platform.dal.access.DataSourceDal;
import com.clougence.clouddm.platform.dal.access.MonitorDal;
import com.clougence.clouddm.platform.dal.model.datasource.DmDsDO;
import com.clougence.clouddm.platform.dal.model.execution.DmExecAsyncTaskDO;
import com.clougence.clouddm.platform.dal.model.monitor.DmMonBizLogDO;
import com.clougence.clouddm.platform.dal.model.monitor.Loglevel;
import com.clougence.clouddm.platform.dal.model.monitor.LogDependBizType;
import com.clougence.clouddm.sdk.service.secrules.Requester;
import com.clougence.clouddm.sdk.sql.SqlEngineSpi;
import com.clougence.clouddm.sdk.sql.SqlParserParameters;
import com.clougence.clouddm.sdk.sql.editor.rewrite.DmlBatchRangeSpec;
import com.clougence.clouddm.sdk.sql.editor.rewrite.RewriteContext;
import com.clougence.clouddm.sdk.sql.editor.rewrite.RewriteSpi;
import com.clougence.clouddm.sdk.execute.resultset.echo.Result;
import com.clougence.clouddm.sdk.execute.resultset.echo.ResultCount;
import com.clougence.utils.JsonUtils;
import com.clougence.utils.StringUtils;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * B7 lock-free DML — the keyset batch-loop execution body (design §5.2; the GhostAsyncTask
 * framework-face mirror, but where the ghost handler POLLS an external tool, this handler IS
 * the executor: one tick = one batch, and {@code delayTask(intervalMs)} is simultaneously the
 * throttle knob and the scheduling primitive — 限流旋钮与调度原语合一).
 * <p>
 * <b>Tick contract</b> (design §5.2):
 * <ol>
 * <li>cancel → terminal writeback (ABORTED, partial success kept, NO automatic rollback) and
 *     return (the framework's cancel path owns the task status); pause → HEARTBEAT WRITE +
 *     yield (暂停按批粒度: an in-flight batch is never interrupted; 暂停中心跳照写 so the sweep
 *     never misjudges);</li>
 * <li>restart recovery: {@code intent ∧ ¬retryPending} on entry = a window crash (the outcome
 *     of a batch is UNKNOWN) → NEVER auto-continue — terminal writeback FAILED + UNRESOLVED
 *     (option C hard contract; a non-idempotent UPDATE re-run would double-apply silently).
 *     {@code retryPending} means a DB error WAS recorded (statement atomicity proves zero
 *     effect) → the retry flow continues (裁决 #21 narrowing);</li>
 * <li>fresh per-tick session (R10: zero session occupancy during delayTask): intent write
 *     (console DB, BEFORE the batch) → boundary SELECT (step ①) → NULL hi = ALL DONE (the
 *     boundary is the ONLY termination authority — never affectRows==0, the MySQL
 *     matched-but-unchanged trap, design §17 Wrong-vs-Correct) → batch DML (step ②) → done
 *     write (cursor=hi, intent cleared, heartbeat — ONE configData update);</li>
 * <li>updateProcess/updateMessage + delayTask(intervalMs).</li>
 * </ol>
 * <b>Failure taxonomy</b> (design §7.2): a batch DML DB error (ResultMessage Error — the
 * statement atomically rolled back server-side) → automatic same-seq retry ≤ maxBatchRetries
 * with {@code retryPending/retryCount/lastBatchError} recorded on the intent (intervalMs×2
 * backoff); a console wrapper TIMEOUT or transport exception during the batch DML → outcome
 * UNKNOWN → UNRESOLVED class (never an automatic retry); rewrite null / IAE / render null /
 * boundary-SELECT failure → loud non-retryable segment FAILED (严禁回退直接执行 — N1a handover
 * obligation #4; the boundary SELECT has no write effect, so its failure is safe-FAILED).
 * <p>
 * ⚠ STALE CONFIGDATA: the framework passes the IN-MEMORY taskDO.configData (frozen at task
 * load) — every tick RE-QUERIES its own row by bizId and parses the FRESH configData (the
 * checkpoint advances per tick; the bootstrap copy only carries the immutable identity).
 * <p>
 * Forensics (design §3.4, A10): the FIRST batch logs its full rewritten statement text and
 * EVERY batch logs (seq, lo, hi, affectRows, costMs) to dm_mon_biz_log
 * (dependOnBizId = task bizId, LogDependBizType.DML_BATCH_TASK) — never-throw.
 */
@Slf4j
@Service
@Scope("prototype")
public class DmlBatchAsyncTask extends AsyncTask {

    @Resource
    private AsyncTaskServiceService asyncTaskServiceService;
    @Resource
    private DataSourceDal           dsDal;
    @Resource
    private DmDsConfigService       dmDsConfigService;
    @Resource
    private OpsDiagnosticExecutor   diagnosticExecutor;
    @Resource
    private MonitorDal              monitorDal;
    // @Lazy: the completion service is a governance component that lazy-reaches the service
    //   layer back — safe-side proxy injection (iron rule).
    @org.springframework.context.annotation.Lazy
    @Resource
    private com.clougence.clouddm.console.web.component.governance.DmlBatchCompletionService dmlBatchCompletionService;

    /** the batch tick's outcome classification (the framework transitions ride on it). */
    private enum TickOutcome { BATCH_DONE, RETRY_WAIT, RETRY_EXHAUSTED, COMPLETED, SAFE_FAILED, UNKNOWN_FAILED }

    @Override
    protected void executeTask(int doCnt, String configData) {
        DmlBatchAsyncTaskConfig bootstrap = DmlBatchServiceImpl.parseTaskConfig(
            fakeTask(configData));
        if (bootstrap == null || StringUtils.isBlank(bootstrap.getBizId())) {
            // a carrier WE wrote cannot be unparsable — loud, no writeback possible (no binding)
            this.failedTaskDoNotPause(new IllegalStateException("DML_BATCH task configData unparsable or bizId missing"));
            return;
        }
        // fresh-row re-query (the in-memory copy goes stale after the first checkpoint write)
        DmExecAsyncTaskDO task = asyncTaskServiceService.queryAsyncTaskByBizId(
            bootstrap.getBizId(), com.clougence.clouddm.console.web.service.dmlbatch.DmlBatchService.BIZ_TYPE);
        DmlBatchAsyncTaskConfig cfg = DmlBatchServiceImpl.parseTaskConfig(task);
        if (cfg == null) {
            cfg = bootstrap; // the row vanished mid-flight (never happens by design) — bootstrap is the best truth
        }
        if (cfg.getCheckpoint() == null) {
            cfg.setCheckpoint(new DmlBatchCheckpoint());
        }
        DmlBatchCheckpoint cp = cfg.getCheckpoint();
        this.ensureEstimatedRows(cfg); // launch-frozen progress denominator (max>0 guard inside)

        // ---- 1. cancel / pause (batch granularity — an in-flight batch is never interrupted) ----
        if (this.isCancel()) {
            String progress = progressSummary(cp);
            this.writeBackTerminal(cfg, task, false, DmI18nUtils.getMessage(
                I18nDmMsgKeys.DML_BATCH_ABORTED.name(), "user stop"), summary(cp, "user stop", false));
            this.updateMessage("aborted by user (" + progress + ")");
            this.notifyAsyncEvent();
            return; // the framework's cancel path already completed the future → CANCEL status
        }
        if (this.isPause()) {
            // 暂停中心跳照写 (the sweep dead-judgement reads lastTickAt — a paused task must stay fresh)
            cp.setLastTickAt(System.currentTimeMillis());
            cp.setLastStatus("PAUSED");
            this.persistConfigQuietly(task, cfg);
            this.updateMessage("paused at a batch boundary (" + progressSummary(cp) + ")");
            this.notifyAsyncEvent();
            return; // future already cancelled → PAUSE status; dock/工单链 resume re-enters a fresh tick
        }

        // ---- 2. restart recovery: intent ∧ ¬retryPending = window crash → UNRESOLVED (option C) ----
        if (cp.getIntent() != null && !Boolean.TRUE.equals(cp.getIntent().getRetryPending())) {
            long seq = cp.getIntent().getSeq();
            String detail = DmI18nUtils.getMessage(I18nDmMsgKeys.DML_BATCH_UNRESOLVED.name(), seq);
            cp.setLastTickAt(System.currentTimeMillis());
            cp.setLastStatus("UNRESOLVED");
            this.persistConfigQuietly(task, cfg);
            this.writeBackTerminal(cfg, task, false, detail, summary(cp, detail, true));
            this.failedTaskDoNotPause(new IllegalStateException(detail));
            return;
        }

        // ---- 3. one batch ----
        TickResult result;
        try {
            result = this.runBatchTick(cfg, cp, task);
        } catch (TickFailure failure) {
            result = failure.toResult();
        } catch (RuntimeException e) {
            // unexpected tick infrastructure failure BEFORE any batch statement (resolution
            // inside runBatchTick classifies everything after the intent write) — safe FAILED
            log.error("[DmlBatch] unexpected tick failure for task {}", bootstrap.getBizId(), e);
            result = TickResult.safe("batch tick failed: " + rootMessage(e));
        }

        switch (result.kind) {
            case COMPLETED: {
                // boundary NULL = the ONLY termination authority (never affectRows==0)
                this.updateProcessAndMessage(cp);
                this.writeBackTerminal(cfg, task, true, null, summary(cp, null, false));
                this.notifyAsyncEvent();
                this.finishTask("all batches done: " + cp.getBatchesDone() + " batches, " + cp.getRowsDone() + " rows");
                return;
            }
            case SAFE_FAILED: {
                // zero write effect proven (rewrite/boundary/render class) — retryGroup resumes from the cursor
                cp.setLastTickAt(System.currentTimeMillis());
                cp.setLastStatus("FAILED");
                cp.setIntent(null); // the intent side is proven side-effect-free — clean forensics
                this.persistConfigQuietly(task, cfg);
                this.updateProcessAndMessage(cp);
                this.writeBackTerminal(cfg, task, false, result.detail, summary(cp, result.detail, false));
                this.notifyAsyncEvent();
                this.failedTaskDoNotPause(new IllegalStateException(result.detail));
                return;
            }
            case UNKNOWN_FAILED: {
                // the batch statement's outcome is UNKNOWN (console timeout / transport loss /
                // post-commit checkpoint persist failure) — the option-C window: FAILED +
                // UNRESOLVED, NEVER an automatic retry (intent deliberately left on the carrier)
                cp.setLastTickAt(System.currentTimeMillis());
                cp.setLastStatus("UNRESOLVED");
                this.persistConfigQuietly(task, cfg);
                this.updateProcessAndMessage(cp);
                this.writeBackTerminal(cfg, task, false, result.detail, summary(cp, result.detail, true));
                this.notifyAsyncEvent();
                this.failedTaskDoNotPause(new IllegalStateException(result.detail));
                return;
            }
            case RETRY_EXHAUSTED: {
                cp.setLastTickAt(System.currentTimeMillis());
                cp.setLastStatus("FAILED");
                this.persistConfigQuietly(task, cfg);
                this.updateProcessAndMessage(cp);
                this.writeBackTerminal(cfg, task, false, result.detail, summary(cp, result.detail, false));
                this.notifyAsyncEvent();
                this.failedTaskDoNotPause(new IllegalStateException(result.detail));
                return;
            }
            case RETRY_WAIT: {
                this.updateProcessAndMessage(cp);
                this.notifyAsyncEvent();
                this.delayTask(backoffMillis(cfg), TimeUnit.MILLISECONDS);
                return;
            }
            case BATCH_DONE:
            default: {
                this.updateProcessAndMessage(cp);
                this.notifyAsyncEvent();
                long interval = effective(cfg).getIntervalMs();
                this.delayTask((int) Math.min(interval, Integer.MAX_VALUE), TimeUnit.MILLISECONDS);
            }
        }
    }

    // ==================================================================================
    // one batch: session → intent → boundary → range DML → done (design §5.2 steps 2-3)
    // ==================================================================================

    private static final class TickResult {
        final TickOutcome kind;
        final String      detail;
        final long        affectRows;
        final long        seq;

        private TickResult(TickOutcome kind, String detail, long affectRows, long seq) {
            this.kind = kind;
            this.detail = detail;
            this.affectRows = affectRows;
            this.seq = seq;
        }

        static TickResult done(long seq, long affectRows) { return new TickResult(TickOutcome.BATCH_DONE, null, affectRows, seq); }
        static TickResult retryWait(long seq) { return new TickResult(TickOutcome.RETRY_WAIT, null, 0, seq); }
        static TickResult exhausted(long seq, String detail) { return new TickResult(TickOutcome.RETRY_EXHAUSTED, detail, 0, seq); }
        static TickResult completed() { return new TickResult(TickOutcome.COMPLETED, null, 0, 0); }
        static TickResult safe(String detail) { return new TickResult(TickOutcome.SAFE_FAILED, detail, 0, 0); }
        static TickResult unknown(String detail) { return new TickResult(TickOutcome.UNKNOWN_FAILED, detail, 0, 0); }
    }

    /** typed tick failure carrying the side-effect classification (safe vs unknown). */
    private static final class TickFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final boolean             unknown;
        final String              detail;

        TickFailure(boolean unknown, String detail) {
            super(detail);
            this.unknown = unknown;
            this.detail = detail;
        }

        TickResult toResult() {
            return unknown ? TickResult.unknown(detail) : TickResult.safe(detail);
        }
    }

    private TickResult runBatchTick(DmlBatchAsyncTaskConfig cfg, DmlBatchCheckpoint cp, DmExecAsyncTaskDO task) {
        DmlBatchEffectiveParams params = effective(cfg);
        TicketSegment.BatchKey key = cfg.getBatchKey();
        if (key == null || StringUtils.isBlank(key.getColumn()) || StringUtils.isBlank(key.getKeyType())
            || StringUtils.isBlank(cfg.getStmtSql()) || cfg.getDsId() == null) {
            throw new TickFailure(false, "DML_BATCH task carrier incomplete (no frozen key/sql/dsId)");
        }
        if (!DmlBatchLiterals.isValidKeyColumn(key.getColumn())) {
            throw new TickFailure(false, "frozen batch key column failed the [\\w$]+ whitelist: " + key.getColumn());
        }

        DmDsDO dsDO = dsDal.dsMapper().queryDsIdentityById(cfg.getDsId());
        if (dsDO == null) {
            throw new TickFailure(false, "datasource " + cfg.getDsId() + " not found");
        }
        boolean postgres = dsDO.getDataSourceType() == DataSourceType.PostgreSQL;
        DataSourceConfig dsConfig = dmDsConfigService.fetchDsConfigFromExists(dsDO.getId());

        // rewrite SPI resolution (the SqlRowEstimator acquisition precedent; per-call context —
        // N1a handover obligation #6: schemaName = the group dbName on EVERY call)
        List<String> levels = List.of(dsDO.getDsEnvId().toString(), String.valueOf(dsDO.getId()),
            StringUtils.trimToEmpty(cfg.getDbName()));
        DsLevels dsLevels = dmDsConfigService.parseLevels(levels);
        SqlParserParameters parserParams = dmDsConfigService.fetchSqlParserParameters(dsConfig, dsLevels.levelsParam());
        SqlEngineSpi sqlEngine = dmDsConfigService.fetchSqlEngineSpi(dsConfig);
        RewriteSpi rewriteSpi = sqlEngine == null ? null : sqlEngine.rewriteSpi(parserParams);
        if (rewriteSpi == null) {
            throw new TickFailure(false, "no rewrite SPI for dsType " + dsDO.getDataSourceType());
        }
        RewriteContext ctx = new RewriteContext();
        ctx.setParameters(parserParams);
        ctx.setSchemaName(cfg.getDbName()); // obligation #6 — set per call, never shared state

        // cursor literal (raw checkpoint value → typed render; the first batch omits the lower
        // bound). Single-assignment form: the lambda below captures it (effectively final).
        final String loLiteral;
        if (DmlBatchLiterals.isBlankCursor(cp.getCursor())) {
            loLiteral = null;
        } else {
            String rendered = DmlBatchLiterals.renderLiteral(key.getKeyType(), cp.getCursor(), postgres);
            if (rendered == null) {
                throw new TickFailure(false, "cursor literal failed the typed render (keyType "
                                            + key.getKeyType() + ") — refusing to build a batch statement");
            }
            loLiteral = rendered;
        }

        // step ① boundary SQL (rewrite null = loud batch failure, NEVER direct execution — obligation #4)
        String queryId = cfg.getBizId() + "-" + (cp.getBatchesDone() + 1);
        DmlBatchRangeSpec boundarySpec = DmlBatchRangeSpec.of(key.getColumn(), key.getKeyType(),
            loLiteral, null, params.getBatchSize());
        final String boundarySql;
        try {
            boundarySql = rewriteSpi.rewriteToBatchBoundarySelect(queryId, cfg.getStmtSql(), boundarySpec, ctx);
        } catch (IllegalArgumentException e) {
            throw new TickFailure(false, "invalid batch spec rejected by the rewrite SPI: " + e.getMessage());
        }
        if (boundarySql == null) {
            throw new TickFailure(false, DmI18nUtils.getMessage(I18nDmMsgKeys.DML_BATCH_EXEC_FAILED.name(),
                "boundary rewrite returned null — the frozen statement is not transformable (never falls back to direct execution)"));
        }

        DiagOpts opts = DiagOpts.builder()
            .timeoutSeconds(params.getTimeoutSec()) // console-side hard budget per statement
            .requester(Requester.TICKET)            // sidecar JDBC tier = 300s (values of
                                                    // dmlBatchTimeoutSec above 300 widen only the
                                                    // console wrapper — the batch size is the
                                                    // real lever for longer statements)
            .autoCommit(true)                       // every batch commits independently — the core lock-free win
            .catalog(cfg.getDbName())               // PG connects ON the target database; MySQL ignores it
            .build();

        final boolean[] intentInFlight = { false }; // batch DML may have executed → UNKNOWN class
        try {
            return diagnosticExecutor.runInSession(cfg.getUserId(), dsDO, dsConfig, opts, session -> {
                // ---- intent write (BEFORE anything else inside the session — the window contract) ----
                DmlBatchCheckpoint.Intent intent = cp.getIntent();
                if (intent == null) {
                    intent = new DmlBatchCheckpoint.Intent();
                    intent.setSeq(cp.getBatchesDone() + 1);
                    cp.setIntent(intent);
                }
                // retry continuation keeps the SAME seq (idempotent overwrite, §7.2)
                intent.setLo(cp.getCursor());
                intent.setStartedAt(System.currentTimeMillis());
                cp.setLastTickAt(intent.getStartedAt());
                cp.setLastStatus(Boolean.TRUE.equals(intent.getRetryPending()) ? "RETRYING" : "RUNNING");
                this.persistConfig(task, cfg);
                final long seq = intent.getSeq();

                // ---- step ① boundary discovery ----
                final String hiRaw;
                try {
                    hiRaw = session.executeSingleValue(boundarySql);
                } catch (RuntimeException e) {
                    // the boundary SELECT has NO write effect — its failure is safe-FAILED (design
                    // §7.2: 边界 SQL 异常 directly segment FAILED); clear the intent for clean forensics
                    cp.setIntent(null);
                    cp.setLastStatus("FAILED");
                    this.persistConfig(task, cfg);
                    throw new TickFailure(false, DmI18nUtils.getMessage(I18nDmMsgKeys.DML_BATCH_EXEC_FAILED.name(),
                        "boundary select failed: " + rootMessage(e)));
                }
                if (hiRaw == null) {
                    // NULL MAX = every key range is exhausted — the ONLY termination authority
                    cp.setIntent(null);
                    cp.setLastTickAt(System.currentTimeMillis());
                    cp.setLastStatus("COMPLETED");
                    this.persistConfig(task, cfg);
                    return TickResult.completed();
                }
                String hiLiteral = DmlBatchLiterals.renderLiteral(key.getKeyType(), hiRaw, postgres);
                if (hiLiteral == null) {
                    cp.setIntent(null);
                    cp.setLastStatus("FAILED");
                    this.persistConfig(task, cfg);
                    throw new TickFailure(false, "boundary value failed the typed render (keyType "
                        + key.getKeyType() + ") — refusing to build a batch statement");
                }

                // ---- step ② range execution ----
                DmlBatchRangeSpec rangeSpec = DmlBatchRangeSpec.of(key.getColumn(), key.getKeyType(),
                    loLiteral, hiLiteral, params.getBatchSize());
                final String rangeSql;
                try {
                    rangeSql = rewriteSpi.rewriteToBatchRange(queryId, cfg.getStmtSql(), rangeSpec, ctx);
                } catch (IllegalArgumentException e) {
                    cp.setIntent(null);
                    cp.setLastStatus("FAILED");
                    this.persistConfig(task, cfg);
                    throw new TickFailure(false, "invalid batch spec rejected by the rewrite SPI: " + e.getMessage());
                }
                if (rangeSql == null) {
                    cp.setIntent(null);
                    cp.setLastStatus("FAILED");
                    this.persistConfig(task, cfg);
                    throw new TickFailure(false, DmI18nUtils.getMessage(I18nDmMsgKeys.DML_BATCH_EXEC_FAILED.name(),
                        "range rewrite returned null — the frozen statement is not transformable (never falls back to direct execution)"));
                }

                long startedAt = System.currentTimeMillis();
                final ResultList resultList;
                try {
                    intentInFlight[0] = true; // from here on the batch MAY have hit the database
                    resultList = session.execute(rangeSql);
                } catch (DiagTimeoutException e) {
                    // console wrapper budget exhausted — the server-side statement may still
                    // complete: outcome UNKNOWN → the option-C window class (never auto-retry)
                    throw new TickFailure(true, DmI18nUtils.getMessage(I18nDmMsgKeys.DML_BATCH_UNRESOLVED.name(), seq)
                        + " (batch statement timed out after " + params.getTimeoutSec() + "s — outcome unknown)");
                } catch (RuntimeException e) {
                    // transport-class loss while the statement was in flight — same UNKNOWN class
                    throw new TickFailure(true, DmI18nUtils.getMessage(I18nDmMsgKeys.DML_BATCH_UNRESOLVED.name(), seq)
                        + " (connection lost during the batch statement: " + rootMessage(e) + ")");
                }
                String dbError = DiagResultUtils.firstFailureMessage(resultList);
                if (dbError != null) {
                    // a DB-level error: the statement atomically rolled back server-side — zero
                    // effect PROVEN → the automatic same-seq retry flow (retryPending recorded)
                    int retryCount = intent.getRetryCount() == null ? 1 : intent.getRetryCount() + 1;
                    intent.setRetryPending(true);
                    intent.setRetryCount(retryCount);
                    intent.setLastBatchError(dbError);
                    cp.setLastTickAt(System.currentTimeMillis());
                    cp.setLastStatus("RETRYING");
                    this.persistConfig(task, cfg);
                    if (retryCount > params.getMaxBatchRetries()) {
                        throw new TickFailure(false, DmI18nUtils.getMessage(
                            I18nDmMsgKeys.DML_BATCH_EXEC_FAILED.name(), dbError)
                            + " (retries exhausted: " + params.getMaxBatchRetries() + ")");
                    }
                    log.warn("[DmlBatch] task {} batch {} failed (retry {}/{}): {}",
                        cfg.getBizId(), seq, retryCount, params.getMaxBatchRetries(), dbError);
                    return TickResult.retryWait(seq);
                }

                long affectRows = extractUpdateCount(resultList);
                long costMs = System.currentTimeMillis() - startedAt;

                // ---- done write (AFTER the batch committed — ONE configData update) ----
                DmlBatchCheckpoint.Done done = new DmlBatchCheckpoint.Done();
                done.setSeq(seq);
                done.setHi(hiRaw);
                done.setAffectRows(affectRows);
                done.setFinishedAt(System.currentTimeMillis());
                cp.setCursor(hiRaw);
                cp.setBatchesDone(cp.getBatchesDone() + 1);
                cp.setRowsDone(cp.getRowsDone() + affectRows);
                cp.setIntent(null);
                cp.setLastDone(done);
                cp.setLastTickAt(System.currentTimeMillis());
                cp.setLastStatus("RUNNING");
                try {
                    this.persistConfig(task, cfg);
                } catch (RuntimeException e) {
                    // the batch COMMITTED but the checkpoint write failed — the exact
                    // commit-checkpoint window: outcome of the RECORDING unknown → UNRESOLVED class
                    throw new TickFailure(true, DmI18nUtils.getMessage(I18nDmMsgKeys.DML_BATCH_UNRESOLVED.name(), seq)
                        + " (checkpoint persist failed after the batch committed: " + rootMessage(e) + ")");
                }

                this.batchForensics(cfg, seq, loLiteral, hiLiteral, affectRows, costMs, seq == 1 ? rangeSql : null);
                return TickResult.done(seq, affectRows);
            });
        } catch (TickFailure failure) {
            throw failure;
        } catch (RuntimeException e) {
            // session creation / infrastructure failure: classified by whether the batch DML was in flight
            if (intentInFlight[0]) {
                throw new TickFailure(true, DmI18nUtils.getMessage(I18nDmMsgKeys.DML_BATCH_UNRESOLVED.name(),
                    cp.getIntent() == null ? cp.getBatchesDone() + 1 : cp.getIntent().getSeq())
                    + " (" + rootMessage(e) + ")");
            }
            throw new TickFailure(false, DmI18nUtils.getMessage(I18nDmMsgKeys.DML_BATCH_EXEC_FAILED.name(), rootMessage(e)));
        }
    }

    // ==================================================================================
    // checkpoint persistence / progress / terminal writeback / forensics
    // ==================================================================================

    /**
     * ONE configData update (the GhostServiceImpl.persistTaskConfig pattern — primitive booleans
     * ride along so the MyBatis-Plus NOT_NULL update never clobbers dock/fastFail semantics).
     * The task row was re-queried at tick start, so this is a fresh-row partial update.
     */
    private void persistConfig(DmExecAsyncTaskDO task, DmlBatchAsyncTaskConfig cfg) {
        DmExecAsyncTaskDO partial = new DmExecAsyncTaskDO();
        partial.setId(task.getId());
        partial.setConfigData(JsonUtils.toJson(cfg));
        partial.setShowInDock(task.isShowInDock());
        partial.setFastFail(task.isFastFail());
        this.executionDal.asyncTaskMapper().updateById(partial);
        task.setConfigData(partial.getConfigData()); // keep the in-memory row consistent for this tick
    }

    /**
     * Best-effort variant for the TICKET-TERMINAL and pause branches: the writeback hook / task
     * transition must not be preempted by a failing carrier write (a console-DB outage in the
     * UNKNOWN branch is exactly the scenario being reported — throwing here would skip the
     * terminal writeback and leak the exception out of executeTask; the sweep heartbeat probe
     * backstops the stale carrier). The intent/done writes INSIDE the batch tick keep the strict
     * {@link #persistConfig} (their failure classification is contract-relevant, §7.3).
     */
    private void persistConfigQuietly(DmExecAsyncTaskDO task, DmlBatchAsyncTaskConfig cfg) {
        try {
            persistConfig(task, cfg);
        } catch (RuntimeException e) {
            log.error("[DmlBatch] checkpoint carrier write failed for task {} (the terminal writeback proceeds)",
                cfg.getBizId(), e);
        }
    }

    /**
     * Terminal writeback hook (the third advanceSegment callback caller — design §5.3): runs
     * BEFORE the task transition, carries its own transaction inside DmlBatchCompletionService
     * (the AsyncTask worker thread has none), and NEVER breaks the task lifecycle — a writeback
     * failure degrades to an error log and the WAIT_EXEC sweep heartbeat probe backstops.
     */
    private void writeBackTerminal(DmlBatchAsyncTaskConfig cfg, DmExecAsyncTaskDO task,
                                   boolean success, String detail, Map<String, Object> summary) {
        if (cfg.getTicketId() == null || cfg.getGroupId() == null) {
            return; // B8 seam: a standalone task (v1 has none) has no ticket writeback
        }
        try {
            this.dmlBatchCompletionService.onBatchTerminal(cfg.getTicketId(), cfg.getGroupId(),
                cfg.getSegmentIndex(), task == null ? null : task.getId(), success, detail, summary);
        } catch (Exception e) {
            log.error("[DmlBatch] ticket writeback failed for ticket {} group {} segment {} (the sweep probe backstops)",
                cfg.getTicketId(), cfg.getGroupId(), cfg.getSegmentIndex(), e);
        }
    }

    /** the segment summary freeze (design §5.3): {status, batchesDone, rowsDone, cursor?, error?, unresolvedIntent?}. */
    static Map<String, Object> summary(DmlBatchCheckpoint cp, String error, boolean unresolvedIntent) {
        Map<String, Object> summary = new HashMap<>();
        summary.put("batchesDone", cp.getBatchesDone());
        summary.put("rowsDone", cp.getRowsDone());
        if (cp.getCursor() != null) {
            summary.put("cursor", cp.getCursor());
        }
        if (error != null) {
            summary.put("error", error);
        }
        if (unresolvedIntent) {
            summary.put("unresolvedIntent", Boolean.TRUE);
            if (cp.getIntent() != null) {
                summary.put("intentSeq", cp.getIntent().getSeq());
            }
        }
        return summary;
    }

    private static String progressSummary(DmlBatchCheckpoint cp) {
        return "batches=" + cp.getBatchesDone() + ", rows=" + cp.getRowsDone();
    }

    /** dock progress + statusMsg (the GhostAsyncTask.updateProcessAndMessage pattern; max>0 guard). */
    private void updateProcessAndMessage(DmlBatchCheckpoint cp) {
        try {
            this.updateMessage(buildStatusMessage(cp));
            Long max = this.estimatedRowsCache;
            if (max != null && max > 0) {
                this.updateProcess(cp.getRowsDone(), max);
            }
        } catch (Exception e) {
            log.error("[DmlBatch] progress update failed (continues)", e);
        }
    }

    private Long estimatedRowsCache;

    private String buildStatusMessage(DmlBatchCheckpoint cp) {
        StringBuilder sb = new StringBuilder();
        sb.append(cp.getLastStatus() == null ? "RUNNING" : cp.getLastStatus());
        sb.append(" · batches=").append(cp.getBatchesDone());
        sb.append(", rows=").append(cp.getRowsDone());
        if (estimatedRowsCache != null && estimatedRowsCache > 0) {
            sb.append('/').append('~').append(estimatedRowsCache);
        }
        if (cp.getCursor() != null) {
            String cursor = cp.getCursor();
            sb.append(", cursor=").append(cursor.length() > 40 ? cursor.substring(0, 40) + "…" : cursor);
        }
        if (cp.getIntent() != null && cp.getIntent().getLastBatchError() != null) {
            String err = cp.getIntent().getLastBatchError();
            sb.append(", lastError=").append(err.length() > 120 ? err.substring(0, 120) + "…" : err);
        }
        return sb.toString();
    }

    /** the estimatedRows denominator is launch-frozen — resolved once per task instance. */
    private void ensureEstimatedRows(DmlBatchAsyncTaskConfig cfg) {
        if (this.estimatedRowsCache == null) {
            this.estimatedRowsCache = cfg.getEstimatedRows();
        }
    }

    /** per-batch forensic trail (design §3.4/A10) — never-throw (forensics must not break execution). */
    private void batchForensics(DmlBatchAsyncTaskConfig cfg, long seq, String loLiteral, String hiLiteral,
                                long affectRows, long costMs, String firstBatchSql) {
        try {
            StringBuilder content = new StringBuilder();
            content.append("[DML-BATCH] seq=").append(seq)
                .append(" lo=").append(loLiteral == null ? "(start)" : loLiteral)
                .append(" hi=").append(hiLiteral)
                .append(" affectRows=").append(affectRows)
                .append(" costMs=").append(costMs);
            if (firstBatchSql != null) {
                content.append(" firstBatchSql=").append(firstBatchSql);
            }
            DmMonBizLogDO logDO = new DmMonBizLogDO(Loglevel.INFO, content.toString(),
                LogDependBizType.DML_BATCH_TASK, cfg.getBizId());
            monitorDal.bizLogMapper().insert(logDO);
        } catch (RuntimeException e) {
            log.warn("[DmlBatch] batch forensics log failed for task {} seq {} (execution continues)",
                cfg.getBizId(), seq, e);
        }
    }

    // ==================================================================================
    // helpers
    // ==================================================================================

    private static DmlBatchEffectiveParams effective(DmlBatchAsyncTaskConfig cfg) {
        return cfg.getEffectiveParams() == null ? new DmlBatchEffectiveParams() : cfg.getEffectiveParams();
    }

    /** retry backoff = intervalMs×2 (design §7.2), floor 1s so a 0-interval config still yields. */
    private static int backoffMillis(DmlBatchAsyncTaskConfig cfg) {
        long backoff = Math.max(effective(cfg).getIntervalMs() * 2, 1000L);
        return (int) Math.min(backoff, 600_000L);
    }

    /** the DML affect-row count of one channel-C execution (ResultCount.updateCount sum). */
    static long extractUpdateCount(ResultList resultList) {
        if (resultList == null || resultList.getResultList() == null) {
            return 0L;
        }
        long total = 0L;
        for (Result result : resultList.getResultList()) {
            if (result instanceof ResultCount count) {
                total += count.getUpdateCount();
            }
        }
        return total;
    }

    private static String rootMessage(Throwable e) {
        String message = e.getMessage();
        return StringUtils.isBlank(message) ? e.getClass().getSimpleName() : message;
    }

    /** adapter so the shared never-throw parser can read the bootstrap configData string. */
    private static DmExecAsyncTaskDO fakeTask(String configData) {
        DmExecAsyncTaskDO task = new DmExecAsyncTaskDO();
        task.setConfigData(configData);
        return task;
    }
}
