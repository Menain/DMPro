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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.api.sidecar.session.execute.ResultList;
import com.clougence.clouddm.base.metadata.ds.DataSourceConfig;
import com.clougence.clouddm.base.metadata.ds.DataSourceType;
import com.clougence.clouddm.console.web.component.dsconfig.DmDsConfigService;
import com.clougence.clouddm.console.web.component.dsconfig.mode.DsLevels;
import com.clougence.clouddm.console.web.component.execute.QueryService;
import com.clougence.clouddm.console.web.component.execute.asyntask.InterruptedType;
import com.clougence.clouddm.console.web.component.execute.asyntask.ScheduleTask;
import com.clougence.clouddm.console.web.component.governance.TicketSegment;
import com.clougence.clouddm.console.web.component.opsdiag.DiagTimeoutException;
import com.clougence.clouddm.console.web.component.opsdiag.OpsDiagnosticExecutor;
import com.clougence.clouddm.console.web.service.asyntask.AsyncTaskServiceService;
import com.clougence.clouddm.console.web.service.dmlbatch.DmlBatchEffectiveParams;
import com.clougence.clouddm.console.web.service.dmlbatch.DmlBatchService;
import com.clougence.clouddm.platform.dal.access.DataSourceDal;
import com.clougence.clouddm.platform.dal.access.ExecutionDal;
import com.clougence.clouddm.platform.dal.access.MonitorDal;
import com.clougence.clouddm.platform.dal.mapper.datasource.DmDsMapper;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecAsyncTaskMapper;
import com.clougence.clouddm.platform.dal.mapper.monitor.DmMonBizLogMapper;
import com.clougence.clouddm.platform.dal.model.datasource.DmDsDO;
import com.clougence.clouddm.platform.dal.model.execution.AsyncTaskStatus;
import com.clougence.clouddm.platform.dal.model.execution.DmExecAsyncTaskDO;
import com.clougence.clouddm.platform.dal.model.monitor.DmMonBizLogDO;
import com.clougence.clouddm.sdk.execute.resultset.echo.Result;
import com.clougence.clouddm.sdk.execute.resultset.echo.ResultCount;
import com.clougence.clouddm.sdk.execute.resultset.echo.ResultMessage;
import com.clougence.clouddm.sdk.execute.resultset.echo.ResultSet;
import com.clougence.clouddm.sdk.execute.resultset.echo.ResultSetMeta;
import com.clougence.clouddm.sdk.execute.resultset.echo.ResultSetRow;
import com.clougence.clouddm.sdk.execute.resultset.echo.ResultSetValue;
import com.clougence.clouddm.sdk.execute.resultset.echo.ResultType;
import com.clougence.clouddm.sdk.execute.session.QueryRequest;
import com.clougence.clouddm.sdk.execute.session.QueryResultConf;
import com.clougence.clouddm.sdk.execute.session.SessionContextDTO;
import com.clougence.clouddm.sdk.execute.session.SessionSpi;
import com.clougence.clouddm.sdk.service.secrules.Requester;
import com.clougence.clouddm.sdk.sql.SqlEngineSpi;
import com.clougence.clouddm.sdk.sql.SqlParserParameters;
import com.clougence.clouddm.sdk.sql.editor.rewrite.DmlBatchRangeSpec;
import com.clougence.clouddm.sdk.sql.editor.rewrite.RewriteSpi;
import com.clougence.schema.umi.struts.UmiTypes;
import com.clougence.utils.JsonUtils;

/**
 * B7 N1b (implement.md 单测矩阵「批循环」): the DmlBatchAsyncTask tick contract — the intent/done
 * DUAL-WRITE ORDER (checkpoint 推进序), the boundary-NULL termination authority (**never**
 * affectRows==0 — the MySQL matched-but-unchanged trap, design §17 Wrong-vs-Correct pin), the
 * automatic same-seq batch retry (retryPending / backoff / exhaustion), the heartbeat on EVERY
 * tick (batch / pause — 暂停中心跳照写), the window-crash UNRESOLVED detection (intent ∧
 * ¬retryPending → 一律人工, 裁决 #9/#21), the loud non-retryable rewrite failures (obligation #4:
 * null → NEVER direct execution), the UNKNOWN class (console timeout / post-commit persist
 * failure), the per-tick session shape (R10: catalog + autoCommit + TICKET requester) and the
 * first-batch biz_log forensics (A10).
 * <p>
 * Discipline: JUnit4 + hand-written mocks + ReflectionTestUtils + ArgumentCaptor; the channel-C
 * scaffold is the GhostServiceImplTest pattern (the executor's protected seams isolate the
 * PluginManager statics; queryService is the mocked bottom layer).
 */
public class DmlBatchAsyncTaskTest {

    private DmlBatchAsyncTask            task;
    private QueryService                 queryService;
    private AsyncTaskServiceService      asyncTaskServiceService;
    private DmExecAsyncTaskMapper        asyncTaskMapper;
    private DmMonBizLogMapper            bizLogMapper;
    private RewriteSpi                   rewriteSpi;
    private com.clougence.clouddm.console.web.component.governance.DmlBatchCompletionService completion;

    private static final String BIZ_ID   = "batch-biz-1";
    private static final long   TASK_ID  = 55L;
    private static final long   DS_ID    = 20L;
    private static final String UID      = "uid-submitter";
    private static final String DB       = "prod_db";
    private static final String STMT_SQL = "UPDATE t_order SET status = 1 WHERE status = 0";
    private static final String BOUNDARY_SQL = "SELECT MAX(`id`) FROM (SELECT `id` FROM `prod_db`.`t_order` WHERE (status = 0) ORDER BY `id` LIMIT 5000) bnd";
    private static final String RANGE_SQL = "UPDATE t_order SET status = 1 WHERE ((status = 0) AND `id` <= 42)";
    private static final String DIAG_SESSION = "diag-session";

    /** the stored task row's configData — the persist mock round-trips through it (DB simulation). */
    private String                       storedConfigData;
    /** every configData JSON persisted during the tick, in write order (the dual-write序 pin). */
    private final List<String>           persistedConfigs = Collections.synchronizedList(new ArrayList<>());
    /** the SQL bodies dispatched to the (mocked) worker, in order. */
    private final List<String>           executedSqls = Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong             queryIdSeq = new AtomicLong();

    /** per-test swappable worker answers */
    private ResultList                   boundaryResult;
    private ResultList                   rangeResult;
    private RuntimeException             rangeThrows;

    private DmExecAsyncTaskDO            storedRow;

    private final SessionSpi fakeSpi = new SessionSpi() {
        @Override
        public SessionContextDTO createSessionContext(DataSourceConfig dsConfig, Map<String, Object> params) {
            SessionContextDTO ctx = new SessionContextDTO();
            ctx.setSessionId("ctx-session");
            return ctx;
        }

        @Override
        public QueryRequest createQueryRequest(DataSourceConfig dsConfig) {
            QueryRequest request = new QueryRequest();
            request.setResultConf(new QueryResultConf());
            return request;
        }

        @Override
        public String newQueryId() {
            return "q-" + queryIdSeq.incrementAndGet();
        }
    };

    @Before
    public void setUp() {
        task = new DmlBatchAsyncTask();

        OpsDiagnosticExecutor diagnosticExecutor = new OpsDiagnosticExecutor() {
            @Override
            protected SessionSpi findSessionSpi(DataSourceType dsType) {
                return fakeSpi;
            }

            @Override
            protected DsLevels createLevels(DmDsDO dsDO, SessionContextDTO ctx) {
                return new DsLevels(String.valueOf(dsDO.getDsEnvId()), dsDO, //
                        List.of(String.valueOf(dsDO.getDsEnvId()), String.valueOf(dsDO.getId())), //
                        Collections.emptyList(), Collections.<UmiTypes> emptyList(), Collections.emptyMap());
            }
        };

        queryService = mock(QueryService.class);
        asyncTaskServiceService = mock(AsyncTaskServiceService.class);
        completion = mock(com.clougence.clouddm.console.web.component.governance.DmlBatchCompletionService.class);
        ExecutionDal executionDal = mock(ExecutionDal.class);
        asyncTaskMapper = mock(DmExecAsyncTaskMapper.class);
        when(executionDal.asyncTaskMapper()).thenReturn(asyncTaskMapper);
        MonitorDal monitorDal = mock(MonitorDal.class);
        bizLogMapper = mock(DmMonBizLogMapper.class);
        when(monitorDal.bizLogMapper()).thenReturn(bizLogMapper);

        DataSourceDal dsDal = mock(DataSourceDal.class);
        DmDsMapper dsMapper = mock(DmDsMapper.class);
        when(dsDal.dsMapper()).thenReturn(dsMapper);
        DmDsDO dsDO = new DmDsDO();
        dsDO.setId(DS_ID);
        dsDO.setDsEnvId(1L);
        dsDO.setDataSourceType(DataSourceType.MySQL);
        dsDO.setVersion("8.0");
        when(dsMapper.queryDsIdentityById(DS_ID)).thenReturn(dsDO);

        DmDsConfigService dmDsConfigService = mock(DmDsConfigService.class);
        DataSourceConfig dsConfig = new DataSourceConfig();
        dsConfig.setDataSourceType(DataSourceType.MySQL);
        when(dmDsConfigService.fetchDsConfigFromExists(anyLong())).thenReturn(dsConfig);
        when(dmDsConfigService.parseLevels(anyList())).thenReturn(
            mock(com.clougence.clouddm.console.web.component.dsconfig.mode.DsLevels.class));
        when(dmDsConfigService.fetchSqlParserParameters(any(), any())).thenReturn(SqlParserParameters.empty());
        SqlEngineSpi sqlEngine = mock(SqlEngineSpi.class);
        rewriteSpi = mock(RewriteSpi.class);
        when(dmDsConfigService.fetchSqlEngineSpi(any(DataSourceConfig.class))).thenReturn(sqlEngine);
        when(sqlEngine.rewriteSpi(any())).thenReturn(rewriteSpi);
        when(rewriteSpi.rewriteToBatchBoundarySelect(anyString(), anyString(), any(), any())).thenReturn(BOUNDARY_SQL);
        when(rewriteSpi.rewriteToBatchRange(anyString(), anyString(), any(), any())).thenReturn(RANGE_SQL);

        ReflectionTestUtils.setField(diagnosticExecutor, "queryService", queryService);
        ReflectionTestUtils.setField(diagnosticExecutor, "dmDsConfigService", dmDsConfigService);
        ReflectionTestUtils.setField(task, "asyncTaskServiceService", asyncTaskServiceService);
        ReflectionTestUtils.setField(task, "dsDal", dsDal);
        ReflectionTestUtils.setField(task, "dmDsConfigService", dmDsConfigService);
        ReflectionTestUtils.setField(task, "diagnosticExecutor", diagnosticExecutor);
        ReflectionTestUtils.setField(task, "monitorDal", monitorDal);
        ReflectionTestUtils.setField(task, "dmlBatchCompletionService", completion);
        ReflectionTestUtils.setField(task, "executionDal", executionDal);

        when(queryService.createSession(anyString(), any(), any())).thenReturn(DIAG_SESSION);

        // the stored task row: the tick's fresh re-query source; the persist mock round-trips
        storedRow = new DmExecAsyncTaskDO();
        storedRow.setId(TASK_ID);
        storedRow.setBizId(BIZ_ID);
        storedRow.setBizType(DmlBatchService.BIZ_TYPE);
        storedRow.setStatus(AsyncTaskStatus.RUNNING);
        storedRow.setShowInDock(false); // keep the static event bus out of the unit test
        when(asyncTaskServiceService.queryAsyncTaskByBizId(BIZ_ID, DmlBatchService.BIZ_TYPE))
            .thenAnswer(inv -> storedRow);
        when(asyncTaskMapper.updateById(any(DmExecAsyncTaskDO.class))).thenAnswer(inv -> {
            DmExecAsyncTaskDO partial = inv.getArgument(0);
            storedConfigData = partial.getConfigData();
            storedRow.setConfigData(partial.getConfigData());
            persistedConfigs.add(partial.getConfigData());
            return 1;
        });

        // default worker answers: boundary finds hi=42, the batch affects 42 rows
        boundaryResult = queryResult(new String[] { "max" }, new String[][] { { "42" } });
        rangeResult = countResult(42L);
        when(queryService.syncExecuteQuery(anyString(), anyString(), any(QueryRequest.class))).thenAnswer(inv -> {
            QueryRequest request = inv.getArgument(2);
            String body = request.getQueryBody();
            executedSqls.add(body);
            if (body.startsWith("SELECT MAX(")) {
                return boundaryResult;
            }
            if (rangeThrows != null) {
                throw rangeThrows;
            }
            return rangeResult;
        });

        // the framework's in-memory taskDO (updateMessage/updateProcess/notifyAsyncEvent target)
        DmExecAsyncTaskDO taskDO = new DmExecAsyncTaskDO();
        taskDO.setId(TASK_ID);
        taskDO.setBizId(BIZ_ID);
        taskDO.setShowInDock(false);
        ReflectionTestUtils.setField(task, "taskDO", taskDO);
    }

    /** the launch bootstrap configData (what the framework passes into executeTask). */
    private String bootstrapConfig(DmlBatchCheckpoint checkpoint, Long estimatedRows) {
        DmlBatchAsyncTaskConfig cfg = new DmlBatchAsyncTaskConfig();
        cfg.setBizId(BIZ_ID);
        cfg.setTicketId(900L);
        cfg.setGroupId(100L);
        cfg.setSegmentIndex(1);
        cfg.setUserId(UID);
        cfg.setDsId(DS_ID);
        cfg.setDbName(DB);
        cfg.setStmtSql(STMT_SQL);
        cfg.setBatchKey(new TicketSegment.BatchKey("id", DmlBatchRangeSpec.KEY_TYPE_INT));
        cfg.setEffectiveParams(new DmlBatchEffectiveParams()); // batchSize 5000, interval 1000, timeout 300, retries 3
        cfg.setEstimatedRows(estimatedRows);
        cfg.setIdempotent(Boolean.FALSE);
        cfg.setCheckpoint(checkpoint);
        String json = JsonUtils.toJson(cfg);
        storedConfigData = json;
        storedRow.setConfigData(json);
        return json;
    }

    private void invokeTick(String configData) {
        ReflectionTestUtils.invokeMethod(task, "executeTask", 0, configData);
    }

    private DmlBatchCheckpoint persistedCheckpoint(int writeIndex) {
        DmlBatchAsyncTaskConfig cfg = JsonUtils.toObj(persistedConfigs.get(writeIndex), DmlBatchAsyncTaskConfig.class);
        return cfg.getCheckpoint();
    }

    // ==================================================================================
    // the happy batch: intent/done dual-write order + forensics + throttle delay
    // ==================================================================================

    @Test
    public void tick_firstBatch_intentWrittenBeforeExecution_doneAfter_dualWriteOrder() {
        invokeTick(bootstrapConfig(new DmlBatchCheckpoint(), 5_000_000L));

        // TWO configData writes: ① the intent (before the boundary/DML) ② the done (after commit)
        assertEquals(2, persistedConfigs.size());
        DmlBatchCheckpoint intentWrite = persistedCheckpoint(0);
        assertNotNull("intent written BEFORE the batch executes (§7.3 window contract)", intentWrite.getIntent());
        assertEquals(1L, intentWrite.getIntent().getSeq());
        assertNull(intentWrite.getIntent().getLo()); // first batch — no lower bound
        assertNotNull(intentWrite.getIntent().getStartedAt());
        assertEquals(0L, intentWrite.getBatchesDone());
        assertNull(intentWrite.getCursor());

        DmlBatchCheckpoint doneWrite = persistedCheckpoint(1);
        assertNull("the done write CLEARS the intent", doneWrite.getIntent());
        assertEquals("42", doneWrite.getCursor());          // the cursor advances to hi
        assertEquals(1L, doneWrite.getBatchesDone());
        assertEquals(42L, doneWrite.getRowsDone());
        assertNotNull(doneWrite.getLastDone());
        assertEquals(1L, doneWrite.getLastDone().getSeq());
        assertEquals("42", doneWrite.getLastDone().getHi());
        assertEquals(42L, doneWrite.getLastDone().getAffectRows());
        assertNotNull("heartbeat rides the done write (每 tick 必写)", doneWrite.getLastTickAt());

        // the execution order: boundary SELECT then the range DML
        assertEquals(2, executedSqls.size());
        assertEquals(BOUNDARY_SQL, executedSqls.get(0));
        assertEquals(RANGE_SQL, executedSqls.get(1));

        // no terminal hook mid-flight; the throttle knob = delayTask(intervalMs)
        verify(completion, never()).onBatchTerminal(anyLong(), anyLong(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any());
        assertEquals(ScheduleTask.TaskStatus.Delay, ReflectionTestUtils.getField(task, "status"));
        assertEquals(1000, ReflectionTestUtils.getField(task, "delayTime"));
        assertEquals(TimeUnit.MILLISECONDS, ReflectionTestUtils.getField(task, "delayUnit"));
    }

    @Test
    public void tick_firstBatch_bizLogForensics_carriesFullRewrittenSql() {
        invokeTick(bootstrapConfig(new DmlBatchCheckpoint(), 5_000_000L));

        ArgumentCaptor<DmMonBizLogDO> captor = ArgumentCaptor.forClass(DmMonBizLogDO.class);
        verify(bizLogMapper).insert(captor.capture());
        DmMonBizLogDO log = captor.getValue();
        assertEquals(BIZ_ID, log.getDependOnBizId());
        assertEquals(com.clougence.clouddm.platform.dal.model.monitor.LogDependBizType.DML_BATCH_TASK,
            log.getDependOnBizType());
        assertTrue(log.getContent().contains("seq=1"));
        assertTrue(log.getContent().contains("lo=(start)"));
        assertTrue(log.getContent().contains("hi=42"));
        assertTrue(log.getContent().contains("affectRows=42"));
        assertTrue("the FIRST batch logs its full rewritten statement (A10)",
            log.getContent().contains("firstBatchSql=" + RANGE_SQL));
    }

    @Test
    public void tick_laterBatch_forensicsWithoutSql_cursorRidesAsLo() {
        DmlBatchCheckpoint cp = new DmlBatchCheckpoint();
        cp.setCursor("42");
        cp.setBatchesDone(1);
        cp.setRowsDone(42);

        invokeTick(bootstrapConfig(cp, 5_000_000L));

        // the boundary/range specs carry the cursor as the exclusive lower bound
        ArgumentCaptor<DmlBatchRangeSpec> specCaptor = ArgumentCaptor.forClass(DmlBatchRangeSpec.class);
        verify(rewriteSpi).rewriteToBatchBoundarySelect(anyString(), eq(STMT_SQL), specCaptor.capture(), any());
        assertEquals("42", specCaptor.getValue().getLoLiteral());
        assertEquals(5000L, specCaptor.getValue().getLimit());
        assertEquals("id", specCaptor.getValue().getKeyColumn());

        ArgumentCaptor<DmMonBizLogDO> logCaptor = ArgumentCaptor.forClass(DmMonBizLogDO.class);
        verify(bizLogMapper).insert(logCaptor.capture());
        assertTrue(logCaptor.getValue().getContent().contains("seq=2"));
        assertTrue(logCaptor.getValue().getContent().contains("lo=42"));
        assertFalse("only batch 1 logs the full SQL", logCaptor.getValue().getContent().contains("firstBatchSql"));
        // the done write advanced the state (boundary hi=42 again → cursor stays "42")
        assertEquals("42", persistedCheckpoint(1).getCursor());
        assertEquals(2L, persistedCheckpoint(1).getBatchesDone());
    }

    // ==================================================================================
    // termination authority: the boundary NULL — NEVER affectRows==0
    // ==================================================================================

    @Test
    public void tick_boundaryNull_terminatesSuccess_theOnlyTerminationAuthority() {
        boundaryResult = queryResult(new String[] { "max" }, new String[][] { { null } });
        DmlBatchCheckpoint cp = new DmlBatchCheckpoint();
        cp.setCursor("99");
        cp.setBatchesDone(5);
        cp.setRowsDone(250);

        invokeTick(bootstrapConfig(cp, 5_000_000L));

        assertEquals(1, executedSqls.size()); // ONLY the boundary ran — no DML this tick
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> summary = ArgumentCaptor.forClass(Map.class);
        verify(completion).onBatchTerminal(eq(900L), eq(100L), eq(1), eq(TASK_ID), eq(true), eq(null),
            summary.capture());
        assertEquals(5L, summary.getValue().get("batchesDone")); // progress frozen into the summary
        assertEquals(ScheduleTask.TaskStatus.Finish, ReflectionTestUtils.getField(task, "status"));
        // the intent write happened first (the window contract holds even for the no-op tick),
        // then the NULL boundary cleared it — the FINAL carrier state is intent-free
        assertEquals(2, persistedConfigs.size());
        assertNotNull(persistedCheckpoint(0).getIntent());
        assertNull(persistedCheckpoint(1).getIntent());
        assertEquals("COMPLETED", persistedCheckpoint(1).getLastStatus());
    }

    @Test
    public void tick_batchAffectRowsZero_continues_neverTerminatesOnAffectRows() {
        // design §17 Wrong-vs-Correct: a MySQL UPDATE matching unchanged rows returns 0 — the
        // loop MUST continue (the boundary NULL is the only authority)
        rangeResult = countResult(0L);

        invokeTick(bootstrapConfig(new DmlBatchCheckpoint(), 5_000_000L));

        verify(completion, never()).onBatchTerminal(anyLong(), anyLong(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any());
        assertEquals(ScheduleTask.TaskStatus.Delay, ReflectionTestUtils.getField(task, "status"));
        DmlBatchCheckpoint done = persistedCheckpoint(1);
        assertEquals("42", done.getCursor());   // the cursor STILL advances (the range was processed)
        assertEquals(1L, done.getBatchesDone());
        assertEquals(0L, done.getRowsDone());
    }

    // ==================================================================================
    // batch failure: automatic same-seq retry / backoff / exhaustion
    // ==================================================================================

    @Test
    public void tick_dbError_retryPendingRecorded_sameSeq_backoffDelay_noTerminal() {
        rangeResult = errorResult("ERROR 1205 Lock wait timeout exceeded");

        invokeTick(bootstrapConfig(new DmlBatchCheckpoint(), 5_000_000L));

        verify(completion, never()).onBatchTerminal(anyLong(), anyLong(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any());
        DmlBatchCheckpoint retryWrite = persistedCheckpoint(1);
        assertNotNull("the intent SURVIVES a retry-pending failure (same seq)", retryWrite.getIntent());
        assertEquals(1L, retryWrite.getIntent().getSeq());
        assertEquals(Boolean.TRUE, retryWrite.getIntent().getRetryPending());
        assertEquals(Integer.valueOf(1), retryWrite.getIntent().getRetryCount());
        assertEquals("ERROR 1205 Lock wait timeout exceeded", retryWrite.getIntent().getLastBatchError());
        assertEquals("RETRYING", retryWrite.getLastStatus());
        assertNull(retryWrite.getCursor()); // the cursor NEVER advances on a failed batch
        // backoff = intervalMs × 2
        assertEquals(ScheduleTask.TaskStatus.Delay, ReflectionTestUtils.getField(task, "status"));
        assertEquals(2000, ReflectionTestUtils.getField(task, "delayTime"));
    }

    @Test
    public void tick_dbError_retriesExhausted_terminalFailed_withLastError() {
        DmlBatchCheckpoint cp = new DmlBatchCheckpoint();
        DmlBatchCheckpoint.Intent intent = new DmlBatchCheckpoint.Intent();
        intent.setSeq(3L);
        intent.setRetryPending(true);
        intent.setRetryCount(3); // already at the compile-time max
        intent.setLastBatchError("prev error");
        cp.setIntent(intent);
        cp.setBatchesDone(2);
        cp.setCursor("10");
        rangeResult = errorResult("ERROR 1213 Deadlock found");

        invokeTick(bootstrapConfig(cp, 5_000_000L));

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> summary = ArgumentCaptor.forClass(Map.class);
        verify(completion).onBatchTerminal(eq(900L), eq(100L), eq(1), eq(TASK_ID), eq(false), detail.capture(),
            summary.capture());
        assertTrue(detail.getValue().contains("ERROR 1213 Deadlock found"));
        assertTrue(detail.getValue().contains("retries exhausted"));
        assertEquals(2L, summary.getValue().get("batchesDone")); // the done side stays intact
        assertEquals(ScheduleTask.TaskStatus.Failed, ReflectionTestUtils.getField(task, "status"));
        assertEquals(Boolean.TRUE, ReflectionTestUtils.getField(task, "fastFail")); // FAILURE, no auto-retry pause
        assertNull("the exhausted failure clears the intent (zero effect proven)",
            persistedCheckpoint(persistedConfigs.size() - 1).getIntent());
    }

    @Test
    public void tick_retryPendingAtEntry_continuesRetryFlow_notEscalatedUnresolved() {
        // 裁决 #21 narrowing: retryPending = a DB error WAS recorded (zero effect proven) →
        // the restart continues the automatic retry flow, NOT the manual UNRESOLVED path
        DmlBatchCheckpoint cp = new DmlBatchCheckpoint();
        DmlBatchCheckpoint.Intent intent = new DmlBatchCheckpoint.Intent();
        intent.setSeq(3L);
        intent.setRetryPending(true);
        intent.setRetryCount(1);
        cp.setIntent(intent);
        cp.setBatchesDone(2);
        cp.setCursor("10");

        invokeTick(bootstrapConfig(cp, 5_000_000L));

        assertEquals(2, executedSqls.size()); // boundary + range actually ran
        DmlBatchCheckpoint done = persistedCheckpoint(1);
        assertNull(done.getIntent());
        assertEquals(3L, done.getBatchesDone());
        assertEquals("42", done.getCursor());
        assertEquals(3L, done.getLastDone().getSeq()); // the SAME seq — idempotent intent overwrite
        verify(completion, never()).onBatchTerminal(anyLong(), anyLong(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any());
    }

    // ==================================================================================
    // window crash: intent ∧ ¬retryPending → UNRESOLVED, 一律人工 (option C hard contract)
    // ==================================================================================

    @Test
    public void tick_restartWithIntentNoRetryPending_unresolvedTerminal_neverAutoContinues() {
        DmlBatchCheckpoint cp = new DmlBatchCheckpoint();
        DmlBatchCheckpoint.Intent intent = new DmlBatchCheckpoint.Intent();
        intent.setSeq(4L);
        intent.setLo("10");
        cp.setIntent(intent); // a crash between the commit and the done write
        cp.setBatchesDone(3);
        cp.setCursor("10");

        invokeTick(bootstrapConfig(cp, 5_000_000L));

        assertTrue(executedSqls.isEmpty());       // NOT ONE statement runs — 不自动续
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> summary = ArgumentCaptor.forClass(Map.class);
        verify(completion).onBatchTerminal(eq(900L), eq(100L), eq(1), eq(TASK_ID), eq(false), detail.capture(),
            summary.capture());
        assertTrue(detail.getValue().contains("4")); // the batch seq rides the manual-resolution copy
        assertEquals(Boolean.TRUE, summary.getValue().get("unresolvedIntent"));
        assertEquals(4L, summary.getValue().get("intentSeq"));
        assertEquals(ScheduleTask.TaskStatus.Failed, ReflectionTestUtils.getField(task, "status"));
    }

    @Test
    public void tick_batchTimeout_unknownOutcome_unresolvedClass_intentKeptOnCarrier() {
        rangeThrows = new DiagTimeoutException();

        invokeTick(bootstrapConfig(new DmlBatchCheckpoint(), 5_000_000L));

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> summary = ArgumentCaptor.forClass(Map.class);
        verify(completion).onBatchTerminal(eq(900L), eq(100L), eq(1), eq(TASK_ID), eq(false), detail.capture(),
            summary.capture());
        assertTrue(detail.getValue().contains("outcome unknown") || detail.getValue().contains("结果未知"));
        assertEquals(Boolean.TRUE, summary.getValue().get("unresolvedIntent"));
        // the intent deliberately STAYS on the carrier (the outcome is unknown — the N2 modal
        // and the forensics read it; retryGroup inheritance strips it)
        DmlBatchCheckpoint last = persistedCheckpoint(persistedConfigs.size() - 1);
        assertNotNull(last.getIntent());
        assertEquals("UNRESOLVED", last.getLastStatus());
        assertEquals(ScheduleTask.TaskStatus.Failed, ReflectionTestUtils.getField(task, "status"));
    }

    @Test
    public void tick_transportLossDuringBatch_unknownClass() {
        rangeThrows = new RuntimeException("connection reset");

        invokeTick(bootstrapConfig(new DmlBatchCheckpoint(), 5_000_000L));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> summary = ArgumentCaptor.forClass(Map.class);
        verify(completion).onBatchTerminal(anyLong(), anyLong(), any(), any(), eq(false), anyString(),
            summary.capture());
        assertEquals(Boolean.TRUE, summary.getValue().get("unresolvedIntent"));
    }

    @Test
    public void tick_donePersistFailureAfterCommit_unknownClass_neverSilentSuccess() {
        // the exact commit-checkpoint window ON the console side: the batch committed but the
        // done write failed → UNKNOWN (never a false SUCCESS, never an auto re-run)
        when(asyncTaskMapper.updateById(any(DmExecAsyncTaskDO.class)))
            .thenReturn(1) // the intent write succeeds
            .thenThrow(new RuntimeException("console db gone")); // the done write fails

        invokeTick(bootstrapConfig(new DmlBatchCheckpoint(), 5_000_000L));

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(completion).onBatchTerminal(anyLong(), anyLong(), any(), any(), eq(false), detail.capture(), any());
        assertTrue(detail.getValue().contains("checkpoint persist failed"));
        assertEquals(ScheduleTask.TaskStatus.Failed, ReflectionTestUtils.getField(task, "status"));
    }

    // ==================================================================================
    // loud non-retryable failures (obligation #4: rewrite null → NEVER direct execution)
    // ==================================================================================

    @Test
    public void tick_boundaryRewriteNull_loudFailure_neverDirectExecution() {
        when(rewriteSpi.rewriteToBatchBoundarySelect(anyString(), anyString(), any(), any())).thenReturn(null);

        invokeTick(bootstrapConfig(new DmlBatchCheckpoint(), 5_000_000L));

        assertTrue("the original statement must NEVER be executed directly", executedSqls.isEmpty());
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(completion).onBatchTerminal(anyLong(), anyLong(), any(), any(), eq(false), detail.capture(), any());
        assertTrue(detail.getValue().contains("never falls back to direct execution"));
        assertEquals(ScheduleTask.TaskStatus.Failed, ReflectionTestUtils.getField(task, "status"));
    }

    @Test
    public void tick_rangeRewriteNull_intentCleared_loudFailure() {
        when(rewriteSpi.rewriteToBatchRange(anyString(), anyString(), any(), any())).thenReturn(null);

        invokeTick(bootstrapConfig(new DmlBatchCheckpoint(), 5_000_000L));

        assertEquals(1, executedSqls.size()); // only the boundary ran (no write effect)
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(completion).onBatchTerminal(anyLong(), anyLong(), any(), any(), eq(false), detail.capture(), any());
        assertTrue(detail.getValue().contains("never falls back to direct execution"));
        assertNull("the proven side-effect-free failure clears the intent",
            persistedCheckpoint(persistedConfigs.size() - 1).getIntent());
    }

    @Test
    public void tick_boundarySelectDbError_safeFailed_intentCleared_noAutoRetry() {
        // design §7.2: 边界 SQL 异常 → directly segment FAILED (the SELECT has no write effect)
        boundaryResult = errorResult("ERROR 2013 lost connection");

        invokeTick(bootstrapConfig(new DmlBatchCheckpoint(), 5_000_000L));

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(completion).onBatchTerminal(anyLong(), anyLong(), any(), any(), eq(false), detail.capture(), any());
        assertTrue(detail.getValue().contains("boundary select failed"));
        assertNull(persistedCheckpoint(persistedConfigs.size() - 1).getIntent());
        assertEquals(ScheduleTask.TaskStatus.Failed, ReflectionTestUtils.getField(task, "status"));
    }

    @Test
    public void tick_cursorRenderFailure_loudFailure_beforeAnyRemoteCall() {
        DmlBatchCheckpoint cp = new DmlBatchCheckpoint();
        cp.setCursor("not-a-number"); // an INT key with a corrupt cursor — refuse to build SQL

        invokeTick(bootstrapConfig(cp, 5_000_000L));

        assertTrue(executedSqls.isEmpty());
        verify(queryService, never()).createSession(anyString(), any(), any());
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(completion).onBatchTerminal(anyLong(), anyLong(), any(), any(), eq(false), detail.capture(), any());
        assertTrue(detail.getValue().contains("typed render"));
    }

    // ==================================================================================
    // pause / cancel (batch granularity) + the heartbeat discipline
    // ==================================================================================

    @Test
    public void tick_pause_heartbeatWritten_noSession_statusMsg() {
        ReflectionTestUtils.invokeMethod(task, "setInterrupted", InterruptedType.PAUSE);
        DmlBatchCheckpoint cp = new DmlBatchCheckpoint();
        cp.setBatchesDone(2);

        invokeTick(bootstrapConfig(cp, 5_000_000L));

        // 暂停中心跳照写 — the sweep dead-judgement must never misfire on a paused task
        assertEquals(1, persistedConfigs.size());
        DmlBatchCheckpoint write = persistedCheckpoint(0);
        assertNotNull(write.getLastTickAt());
        assertEquals("PAUSED", write.getLastStatus());
        assertEquals(2L, write.getBatchesDone()); // the checkpoint is untouched
        assertTrue(executedSqls.isEmpty());       // batch granularity: nothing runs
        verify(queryService, never()).createSession(anyString(), any(), any());
        verify(completion, never()).onBatchTerminal(anyLong(), anyLong(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any());
        verify(asyncTaskMapper).updateStatusMessage(eq(TASK_ID), org.mockito.ArgumentMatchers.contains("paused"));
    }

    @Test
    public void tick_cancel_terminalAborted_partialSuccessKept() {
        ReflectionTestUtils.invokeMethod(task, "setInterrupted", InterruptedType.CANCEL);
        DmlBatchCheckpoint cp = new DmlBatchCheckpoint();
        cp.setBatchesDone(3);
        cp.setRowsDone(150);
        cp.setCursor("30");

        invokeTick(bootstrapConfig(cp, 5_000_000L));

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> summary = ArgumentCaptor.forClass(Map.class);
        verify(completion).onBatchTerminal(eq(900L), eq(100L), eq(1), eq(TASK_ID), eq(false), detail.capture(),
            summary.capture());
        assertTrue(detail.getValue().contains("终止") || detail.getValue().contains("abort"));
        assertEquals(3L, summary.getValue().get("batchesDone")); // the partial-success scene is preserved
        assertEquals("30", summary.getValue().get("cursor"));
        assertTrue(executedSqls.isEmpty());
    }

    // ==================================================================================
    // session shape (R10 per-tick session; each batch commits independently)
    // ==================================================================================

    @Test
    public void tick_sessionShape_catalogAutoCommitTicketRequester() {
        invokeTick(bootstrapConfig(new DmlBatchCheckpoint(), 5_000_000L));

        ArgumentCaptor<SessionContextDTO> ctxCaptor = ArgumentCaptor.forClass(SessionContextDTO.class);
        verify(queryService).createSession(eq(UID), any(), ctxCaptor.capture()); // owner = the submitter (R19)
        assertEquals(DB, ctxCaptor.getValue().getRdbCatalog());   // PG reaches the target database
        assertTrue(ctxCaptor.getValue().isRdbAutoCommit());       // 每批独立提交 — the lock-free win
        assertFalse(ctxCaptor.getValue().isRdbReadOnly());

        ArgumentCaptor<QueryRequest> reqCaptor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(queryService, org.mockito.Mockito.times(2))
            .syncExecuteQuery(eq(UID), eq(DIAG_SESSION), reqCaptor.capture());
        assertEquals(Requester.TICKET, reqCaptor.getAllValues().get(0).getRequester());
    }

    // ==================================================================================
    // progress projection
    // ==================================================================================

    @Test
    public void tick_updateProcess_percentFromRowsDoneOverEstimate() {
        DmlBatchCheckpoint cp = new DmlBatchCheckpoint();
        cp.setRowsDone(25);

        invokeTick(bootstrapConfig(cp, 100L)); // +42 rows this batch → 67/100

        verify(asyncTaskMapper).updateProcess(eq(TASK_ID), eq("PROGRESS"), eq("67"));
    }

    @Test
    public void tick_nullEstimate_noProcessUpdate_messageStillWritten() {
        invokeTick(bootstrapConfig(new DmlBatchCheckpoint(), null));

        verify(asyncTaskMapper, never()).updateProcess(anyLong(), anyString(), anyString());
        verify(asyncTaskMapper).updateStatusMessage(eq(TASK_ID), org.mockito.ArgumentMatchers.contains("batches=1"));
    }

    @Test
    public void tick_terminalHookFailure_neverBreaksTaskLifecycle() {
        boundaryResult = queryResult(new String[] { "max" }, new String[][] { { null } });
        org.mockito.Mockito.doThrow(new RuntimeException("writeback down"))
            .when(completion).onBatchTerminal(anyLong(), anyLong(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any());

        invokeTick(bootstrapConfig(new DmlBatchCheckpoint(), 5_000_000L));

        // the task still finishes (the sweep heartbeat probe backstops the group judgement)
        assertEquals(ScheduleTask.TaskStatus.Finish, ReflectionTestUtils.getField(task, "status"));
    }

    // ==================================================================================
    // ResultList fixtures
    // ==================================================================================

    private static ResultList queryResult(String[] columns, String[][] rows) {
        ResultSetMeta meta = new ResultSetMeta();
        meta.setSuccess(true);
        meta.setResultType(ResultType.ResultSetMeta);
        meta.setColumnList(Arrays.asList(columns));

        ResultSet resultSet = new ResultSet();
        resultSet.setSuccess(true);
        resultSet.setResultType(ResultType.ResultSet);
        List<ResultSetRow> rowSet = new ArrayList<>();
        for (String[] rawRow : rows) {
            ResultSetRow resultRow = new ResultSetRow();
            List<ResultSetValue> data = new ArrayList<>();
            for (String value : rawRow) {
                data.add(ResultSetValue.of(true, false, value, 0, value == null ? 0 : value.length()));
            }
            resultRow.setData(data);
            rowSet.add(resultRow);
        }
        resultSet.setRowSet(rowSet);

        ResultList list = new ResultList();
        List<Result> results = new ArrayList<>();
        results.add(meta);
        results.add(resultSet);
        list.setResultList(results);
        return list;
    }

    private static ResultList countResult(long updateCount) {
        ResultCount count = new ResultCount();
        count.setSuccess(true);
        count.setResultType(ResultType.ResultCount);
        count.setUpdateCount(updateCount);
        ResultList list = new ResultList();
        list.setResultList(List.of(count));
        return list;
    }

    private static ResultList errorResult(String message) {
        ResultMessage error = new ResultMessage();
        error.setSuccess(false);
        error.setMessage(message);
        error.setResultType(ResultType.Message);
        ResultList list = new ResultList();
        list.setResultList(List.of(error));
        return list;
    }
}
