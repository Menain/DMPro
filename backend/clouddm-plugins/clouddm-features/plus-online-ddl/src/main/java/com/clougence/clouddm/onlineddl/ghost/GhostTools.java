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
package com.clougence.clouddm.onlineddl.ghost;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.MDC;

import com.clougence.clouddm.base.metadata.ds.DataSourceConfig;
import com.clougence.clouddm.base.metadata.ds.DataSourceType;
import com.clougence.clouddm.base.metadata.ds.SslMode;
import com.clougence.clouddm.base.metadata.ds.tools.GhostPluginConfig;
import com.clougence.clouddm.sdk.execute.tools.Tool;
import com.clougence.clouddm.sdk.execute.tools.ToolRequestDTO;
import com.clougence.clouddm.sdk.execute.tools.ToolResultDTO;
import com.clougence.clouddm.sdk.execute.tools.ToolSessionContextDTO;
import com.clougence.clouddm.sdk.execute.tools.ToolUtils;
import com.clougence.clouddm.sdk.model.ghost.GhostConfig;
import com.clougence.clouddm.sdk.model.ghost.GhostLaunchDTO;
import com.clougence.clouddm.sdk.model.ghost.GhostMethod;
import com.clougence.clouddm.sdk.model.ghost.GhostOptionsDTO;
import com.clougence.clouddm.sdk.model.ghost.GhostStatusDTO;
import com.clougence.clouddm.sdk.model.ghost.GhostTailRequestDTO;
import com.clougence.clouddm.sdk.model.ghost.GhostTailResponseDTO;
import com.clougence.clouddm.sdk.service.config.ConfigService;
import com.clougence.clouddm.sdk.service.execute.SessionService;
import com.clougence.utils.ExceptionUtils;
import com.clougence.utils.HostUtil;
import com.clougence.utils.JsonUtils;
import com.clougence.utils.StringUtils;

/**
 * GHOST tool session facade (implements the current {@link Tool} SPI, design §2.3), mirroring
 * {@code FakerTools}: init parses {@code ToolSessionContextDTO.configuration} into
 * {@link GhostLaunchDTO}, runs the R3 hard checks and assembles the {@link GhostConfig} runtime
 * context; invoke dispatches {@link GhostMethod} keys onto {@link GhostExecutor}; the session
 * main.log doubles as the gh-ost output sink (logFileFullPath ≡ ToolUtils.getLogFile()).
 *
 * <p>PK/UK existence is NOT re-checked here (the console precheck of S4 is the main gate); a
 * missing unique key fails naturally inside gh-ost and surfaces through the FATAL/exit-code
 * translation.</p>
 */
public class GhostTools implements Tool, GhostMethod {

    private static final Logger  logger          = ToolUtils.getLoggerAppender();

    private static final String  GHOST_BIN_NAME  = "gh-ost";
    private static final String  TOOLS_SUBDIR    = "tools" + File.separator + GHOST_BIN_NAME;
    private static final int     PAGE_LINES      = 25;
    private static final long    STOP_WAIT_MILLIS = 15_000L;
    private static final long    VERSION_PROBE_TIMEOUT_MILLIS = 10_000L;

    private final SessionService    sessionService;
    private final ConfigService     configService;
    private final GhostPluginConfig globalConfig;

    private final GhostProgressState progress      = new GhostProgressState();
    private final AtomicBoolean      startRequested = new AtomicBoolean(false);

    private GhostLaunchDTO     launchDTO;
    private GhostExecutor      executor;
    private Map<String, String> mdcContext;
    private volatile String    binaryVersion;

    public GhostTools(SessionService sessionService, ConfigService configService, GhostPluginConfig globalConfig){
        this.sessionService = sessionService;
        this.configService = configService;
        this.globalConfig = globalConfig;
    }

    // ------------------------------------------------------------------ Tool SPI

    @Override
    public void init(ToolSessionContextDTO contextDTO) throws Exception {
        GhostLaunchDTO launch = JsonUtils.toObj(contextDTO.getConfiguration(), GhostLaunchDTO.class);
        if (launch == null) {
            throw new GhostToolException(GhostToolException.CODE_BAD_LAUNCH_CONFIG, "session configuration is blank or unparsable.");
        }
        if (launch.getDsId() <= 0) {
            throw new GhostToolException(GhostToolException.CODE_BAD_LAUNCH_CONFIG, "dsId is required.");
        }
        if (StringUtils.isBlank(launch.getSchema())) {
            throw new GhostToolException(GhostToolException.CODE_BAD_LAUNCH_CONFIG, "schema is required.");
        }
        if (StringUtils.isBlank(launch.getDdlSql())) {
            throw new GhostToolException(GhostToolException.CODE_BAD_LAUNCH_CONFIG, "ddlSql is required.");
        }
        this.launchDTO = launch;

        // R13: gh-ost is a unix binary driven through its own process — Windows has no support
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (osName.startsWith("win")) {
            throw new GhostToolException(GhostToolException.CODE_BINARY_MISSING,
                    "lock-free DDL requires a Linux/macOS deployment host (the gh-ost binary ships under tools/gh-ost/ of the Linux release package), current OS: "
                                                                                                                                     + osName);
        }

        DataSourceConfig dsConfig = this.configService.fetchDsConfig(launch.getDsId());
        checkDataSource(dsConfig, launch.getDsId());

        // R3: single plain ALTER TABLE (throws CODE_STMT_UNSUPPORTED / CODE_SCHEMA_MISMATCH)
        GhostAlterTableParser.AlterTarget target = GhostAlterTableParser.parse(launch.getDdlSql(), launch.getSchema());

        String logPath = MDC.get(ToolUtils.MDCKEY_LOG_PATH);
        String logFile = MDC.get(ToolUtils.MDCKEY_LOG_FILE);
        if (StringUtils.isBlank(logPath) || StringUtils.isBlank(logFile)) {
            throw new IllegalStateException("tool session log MDC (" + ToolUtils.MDCKEY_LOG_PATH + "/"
                                            + ToolUtils.MDCKEY_LOG_FILE + ") is not prepared.");
        }

        String toolHomeDir = resolveToolHome();
        File binary = new File(toolHomeDir, GHOST_BIN_NAME);
        if (!binary.isFile()) {
            // fail fast at session creation; spawn re-checks and chmods (design §3)
            throw new GhostToolException(GhostToolException.CODE_BINARY_MISSING,
                    "gh-ost binary not found at " + binary.getAbsolutePath()
                                                    + " — deploy the linux binary to <appHome>/tools/gh-ost/gh-ost (or <appDataHome>/tools/gh-ost/gh-ost), see the deployment guide.");
        }

        // S3 version stamp: probe once per session, leave a trace in the session main.log;
        // a probe failure only warns — it must never block the migration (design §3)
        this.binaryVersion = probeBinaryVersion(binary);
        if (this.binaryVersion != null) {
            logger.info("[GH-OST] binary version: " + this.binaryVersion + " (" + binary.getAbsolutePath() + ")");
        } else {
            logger.warn("[GH-OST] binary version probe returned no output (" + binary.getAbsolutePath() + ").");
        }

        GhostConfig ghostConfig = new GhostConfig();
        ghostConfig.setSessionId(contextDTO.getSessionId());
        ghostConfig.setDatabase(target.getDatabase());
        ghostConfig.setSchema(launch.getSchema());
        ghostConfig.setDdlSql(target.getAlterClause());
        ghostConfig.setFileDir(new File(logPath).getAbsolutePath() + File.separator);
        ghostConfig.setToolHomeDir(toolHomeDir);
        ghostConfig.setLogFileFullPath(new File(logFile).getAbsolutePath());
        ghostConfig.setGhostOptionsDTO(mergeOptions(this.globalConfig, launch.getOptions()));

        this.executor = new GhostExecutor(dsConfig, ghostConfig, target, this.progress);
        this.mdcContext = MDC.getCopyOfContextMap();
        logger.info("ghost session init complete. dsId=" + launch.getDsId() + ", database=" + target.getDatabase()
                    + ", table=" + target.getTableName() + ", alter=" + target.getAlterClause());
    }

    @Override
    public ToolResultDTO invoke(String methodKey, ToolRequestDTO requestDTO) {
        if (START.equals(methodKey)) {
            return this.start();
        } else if (PAUSE.equals(methodKey)) {
            requireExecutor();
            this.executor.pause();
            this.progress.markUserPaused();
            return ToolUtils.build(true);
        } else if (RESUME.equals(methodKey)) {
            requireExecutor();
            this.executor.resume();
            this.progress.markUserResumed();
            return ToolUtils.build(true);
        } else if (STOP.equals(methodKey)) {
            requireExecutor();
            this.executor.stop();
            return ToolUtils.build(true);
        } else {
            String msg = "'" + methodKey + "' undefined method.";
            logger.error(msg);
            throw new IllegalStateException(msg);
        }
    }

    @Override
    public ToolResultDTO tailStatus(ToolRequestDTO requestDTO) {
        GhostStatusDTO statusDTO = this.progress.toStatusDTO();
        return ToolUtils.buildSuccess(JsonUtils.toJson(statusDTO));
    }

    @Override
    public ToolResultDTO tailLog(ToolRequestDTO requestDTO) {
        GhostTailRequestDTO request = requestDTO == null ? null : JsonUtils.toObj(requestDTO.getBody(), GhostTailRequestDTO.class);
        if (request == null) {
            request = new GhostTailRequestDTO();
        }

        File logAbsFile = ToolUtils.getLogFile();
        String logAbsoluteFile = logAbsFile.getAbsolutePath();

        if (!logAbsFile.exists()) {
            return ToolUtils.buildSuccess(JsonUtils.toJson(
                    tailResponse(logAbsoluteFile, request.getStartLine(), Collections.singletonList("logFile " + logAbsoluteFile + " does not exist."))));
        }
        if (!logAbsFile.canRead()) {
            return ToolUtils.buildSuccess(JsonUtils.toJson(
                    tailResponse(logAbsoluteFile, request.getStartLine(), Collections.singletonList("logFile " + logAbsoluteFile + " no read permission."))));
        }

        List<String> logData = new LinkedList<>();
        int startLine = request.getStartLine();
        int endLine = startLine + PAGE_LINES;
        try (BufferedReader reader = new BufferedReader(new FileReader(logAbsFile))) {
            List<String> all = new LinkedList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                all.add(line);
            }

            // paging semantics identical to FakerTools.tailLog: startLine==0 → the newest page
            if (startLine == 0) {
                for (int i = all.size() - 1; i >= 0; i--) {
                    if (endLine <= 0) {
                        break;
                    }
                    logData.add(all.get(i));
                    endLine--;
                }
                Collections.reverse(logData);
                endLine = PAGE_LINES;
            } else {
                for (int i = 0; i < all.size(); i++) {
                    if (i > endLine) {
                        break;
                    }
                    if (i >= startLine) {
                        logData.add(all.get(i));
                    }
                }
            }
        } catch (Exception e) {
            return ToolUtils.buildSuccess(JsonUtils.toJson(tailResponse(logAbsoluteFile, request.getStartLine(),
                    Collections.singletonList("logFile " + logAbsoluteFile + " read failed, " + ExceptionUtils.getRootCauseMessage(e)))));
        }

        return ToolUtils.buildSuccess(JsonUtils.toJson(tailResponse(logAbsoluteFile, endLine, logData)));
    }

    @Override
    public void close() {
        logger.info("ghost to stop.");
        if (this.executor != null) {
            if (this.executor.isAlive()) {
                // panic first so gh-ost aborts promptly, then wait, then force as a last resort
                this.executor.stop();
                if (!this.executor.waitForExit(STOP_WAIT_MILLIS)) {
                    this.executor.destroyForcibly();
                }
            }
            this.executor.clean();
        }
        logger.info("ghost stop complete.");

        if (!Boolean.TRUE.equals(this.globalConfig.getKeepLog())) {
            File logAbsFile = ToolUtils.getLogFile();
            logAbsFile.delete();
            logAbsFile.getParentFile().delete();
        }
    }

    // ------------------------------------------------------------------ internals

    private ToolResultDTO start() {
        requireExecutor();
        if (!this.startRequested.compareAndSet(false, true)) {
            return ToolUtils.buildError(new GhostToolException(GhostToolException.CODE_ALREADY_STARTED,
                    "the migration was already started for this session."));
        }

        final Map<String, String> mdc = this.mdcContext;
        final GhostExecutor exec = this.executor;
        Thread runner = new Thread(() -> {
            // the spawn thread must carry the session MDC so the tools-logger sift appender
            // keeps writing into this session's main.log
            if (mdc != null) {
                MDC.setContextMap(mdc);
            }
            try {
                progress.markRunning();
                exec.startBlocking();
            } catch (Throwable e) {
                progress.markFailed(ExceptionUtils.getRootCauseMessage(e));
                logger.error("[GH-OST] runner failure: " + ExceptionUtils.getRootCauseMessage(e), e);
            } finally {
                MDC.clear();
            }
        }, "ghost-exec-" + this.launchDTO.getDsId());
        runner.setDaemon(true);
        runner.start();
        return ToolUtils.buildSuccess(GhostProgressState.STATUS_RUNNING);
    }

    private void requireExecutor() {
        if (this.executor == null) {
            throw new GhostToolException(GhostToolException.CODE_NOT_INITIALIZED, "ghost session is not initialized.");
        }
    }

    private GhostTailResponseDTO tailResponse(String logAbsoluteFile, int endLine, List<String> logArr) {
        GhostTailResponseDTO dto = new GhostTailResponseDTO();
        dto.setLogFile(logAbsoluteFile);
        dto.setLogHost(HostUtil.getHostIp());
        dto.setLogArr(logArr);
        dto.setEndLine(endLine);
        dto.setStatus(this.progress.getStatus());
        return dto;
    }

    /** R3 hard checks on the datasource: plain MySQL, direct connection (no SSH tunnel / SSL) */
    static void checkDataSource(DataSourceConfig dsConfig, long dsId) {
        if (dsConfig == null) {
            throw new GhostToolException(GhostToolException.CODE_DS_TYPE_UNSUPPORTED, "datasource not found: " + dsId);
        }
        if (dsConfig.getDataSourceType() != DataSourceType.MySQL) {
            throw new GhostToolException(GhostToolException.CODE_DS_TYPE_UNSUPPORTED,
                    "gh-ost only support MySQL DDL. current type: " + dsConfig.getDataSourceType());
        }
        if (Boolean.TRUE.equals(dsConfig.getSshProxyEnabled())) {
            throw new GhostToolException(GhostToolException.CODE_TUNNEL_UNSUPPORTED,
                    "datasource uses an SSH tunnel — lock-free DDL requires a direct connection.");
        }
        if (dsConfig.getSslMode() != null && dsConfig.getSslMode() != SslMode.DISABLED) {
            throw new GhostToolException(GhostToolException.CODE_TUNNEL_UNSUPPORTED,
                    "datasource uses SSL mode " + dsConfig.getSslMode() + " — lock-free DDL requires a direct connection.");
        }
    }

    /**
     * Task-level options override the admin-level {@link GhostPluginConfig} field by field
     * (null-check merge, S1 contract): a null task DTO or null task field keeps the admin value;
     * a null admin value keeps the GhostOptionsDTO initializer default.
     */
    static GhostOptionsDTO mergeOptions(GhostPluginConfig admin, GhostOptionsDTO task) {
        GhostOptionsDTO base = new GhostOptionsDTO();
        GhostOptionsDTO merged = new GhostOptionsDTO();
        merged.setAssumeRbr(firstNonNull(task == null ? null : task.getAssumeRbr(), admin == null ? null : admin.getAssumeRbr(), base.getAssumeRbr()));
        merged.setAllowOnMaster(firstNonNull(task == null ? null : task.getAllowOnMaster(), admin == null ? null : admin.getAllowOnMaster(), base.getAllowOnMaster()));
        merged.setCutOverLockTimeoutSec(firstNonNull(task == null ? null : task.getCutOverLockTimeoutSec(), admin == null ? null : admin.getCutOverLockTimeoutSec(), base.getCutOverLockTimeoutSec()));
        merged.setDmlBatchSize(firstNonNull(task == null ? null : task.getDmlBatchSize(), admin == null ? null : admin.getDmlBatchSize(), base.getDmlBatchSize()));
        merged.setChunkSize(firstNonNull(task == null ? null : task.getChunkSize(), admin == null ? null : admin.getChunkSize(), base.getChunkSize()));
        merged.setVerbose(firstNonNull(task == null ? null : task.getVerbose(), admin == null ? null : admin.getVerbose(), base.getVerbose()));
        merged.setCutOverType(firstNonNull(task == null ? null : task.getCutOverType(), admin == null ? null : admin.getCutOverType(), base.getCutOverType()));
        merged.setExactRowCount(firstNonNull(task == null ? null : task.getExactRowCount(), admin == null ? null : admin.getExactRowCount(), base.getExactRowCount()));
        merged.setConcurrentRowCount(firstNonNull(task == null ? null : task.getConcurrentRowCount(), admin == null ? null : admin.getConcurrentRowCount(), base.getConcurrentRowCount()));
        merged.setHbIntervalMs(firstNonNull(task == null ? null : task.getHbIntervalMs(), admin == null ? null : admin.getHbIntervalMs(), base.getHbIntervalMs()));
        merged.setTimestampOldTable(firstNonNull(task == null ? null : task.getTimestampOldTable(), admin == null ? null : admin.getTimestampOldTable(), base.getTimestampOldTable()));
        merged.setInitiallyDropGhostTable(firstNonNull(task == null ? null : task.getInitiallyDropGhostTable(), admin == null ? null : admin.getInitiallyDropGhostTable(), base.getInitiallyDropGhostTable()));
        merged.setAssumeDsProxyInFront(firstNonNull(task == null ? null : task.getAssumeDsProxyInFront(), admin == null ? null : admin.getAssumeDsProxyInFront(), base.getAssumeDsProxyInFront()));
        return merged;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * Seam for the gh-ost home directory (design §3). Step-2 minimal implementation mirroring
     * {@code GlobalConfUtils.getAppHome()/getAppDataHome()} semantics through system properties
     * (cgdm-api is not on the plugin classpath): {@code <appHome>/tools/gh-ost} when the binary
     * is there, otherwise {@code <appDataHome>/tools/gh-ost}. Step 3 owns the full resolution
     * (release-package copy hooks, version logging); tests override this method.
     */
    protected String resolveToolHome() {
        String userHome = System.getProperty("user.home");

        String appHome = System.getProperty("app.home");
        if (StringUtils.isBlank(appHome)) {
            appHome = userHome + File.separator + "clouddm";
        }
        File primary = new File(appHome, TOOLS_SUBDIR);
        if (new File(primary, GHOST_BIN_NAME).isFile()) {
            return primary.getAbsolutePath();
        }

        String appData = System.getProperty("app.data");
        if (StringUtils.isBlank(appData)) {
            appData = userHome + File.separator + ".clougence" + File.separator + "clouddm";
        }
        return new File(appData, TOOLS_SUBDIR).getAbsolutePath();
    }

    /**
     * S3 version-trace seam: run {@code <binary> --version} once and return the trimmed output,
     * or null on any failure (spawn error, timeout, non-zero exit, blank output) — failures only
     * log a warning, they never propagate. The child's stdin is closed immediately so a binary
     * waiting for input can never pin the probe; waitFor is bounded by
     * {@link #VERSION_PROBE_TIMEOUT_MILLIS}. Protected for tests (override to skip the real
     * process spawn, mirroring the {@link #resolveToolHome()} pattern).
     */
    protected String probeBinaryVersion(File binary) {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(binary.getAbsolutePath(), "--version");
            builder.redirectErrorStream(true);
            process = builder.start();
            // EOF on the child's stdin right away — the probe must never block on a prompt
            process.getOutputStream().close();

            if (!process.waitFor(VERSION_PROBE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                logger.warn("[GH-OST] binary version probe timed out: " + binary.getAbsolutePath());
                return null;
            }
            String output;
            try (InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                logger.warn("[GH-OST] binary version probe exited with code " + exitCode + ": " + binary.getAbsolutePath());
                return null;
            }
            return output.isEmpty() ? null : output;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("[GH-OST] binary version probe interrupted: " + binary.getAbsolutePath());
            return null;
        } catch (Throwable t) {
            // not executable, wrong architecture, spawn failure... — warn only (never blocks init)
            logger.warn("[GH-OST] binary version probe failed: " + ExceptionUtils.getRootCauseMessage(t));
            return null;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    // ------------------------------------------------------------------ test seams

    GhostExecutor getExecutor() { return executor; }

    GhostProgressState getProgress() { return progress; }

    String getBinaryVersion() { return binaryVersion; }
}
