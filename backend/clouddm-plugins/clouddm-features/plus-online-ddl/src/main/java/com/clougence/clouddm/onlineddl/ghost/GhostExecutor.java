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
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;

import com.clougence.clouddm.base.metadata.ds.DataSourceConfig;
import com.clougence.clouddm.sdk.execute.tools.ToolUtils;
import com.clougence.clouddm.sdk.model.ghost.GhostConfig;
import com.clougence.clouddm.sdk.model.ghost.GhostOptionsDTO;
import com.clougence.utils.ExceptionUtils;

/**
 * Internal gh-ost process executor — the refactor of the legacy {@code GhostSessionOld} core asset
 * (command assembly, throttle/panic flag-file control, sock/pause/panic file lifecycle) onto the
 * current platform APIs. Differences from the legacy implementation (design §2.4):
 *
 * <ul>
 * <li>{@link ProcessBuilder} direct exec of the binary — no {@code /bin/sh -c} wrapper (removes
 *     the shell-quoting/injection surface, gives raw stdin control);</li>
 * <li>credentials: {@code --ask-pass} + the password is fed through the process stdin and the
 *     stream closed (spike-proven: gh-ost 1.1.4 has no --defaults-file). {@code --password} never
 *     enters argv, so the assembled command is log-safe as-is — the legacy dual-builder
 *     (real cmd + masked cmd) collapses into this single command;</li>
 * <li>{@code --user} is always explicit (spike pitfall #1: --ask-pass only covers the password);</li>
 * <li>{@link DataSourceConfig} replaces the extinct {@code MySqlConfig};</li>
 * <li>stdout+stderr are merged into one stream, tee'd line-by-line into the session main.log and
 *     fed into {@link GhostProgressParser}; teardown noise lines never fail the session — the
 *     terminal state is decided by exit code + panic flag + captured FATAL line;</li>
 * <li>{@code --assume-master-host} stays always-on and {@code --aliyun-rds} keeps being driven by
 *     assumeDsProxyInFront (spike-proven against the Tencent-Cloud proxy topology).</li>
 * </ul>
 *
 * @author bucketli 2022/6/1 15:01:38
 */
public class GhostExecutor {

    private static final Logger logger                 = ToolUtils.getLoggerAppender();

    private static final String BINARY_NAME            = "gh-ost";
    private static final String SOCK_FILE_SUFFIX       = ".sock";
    private static final String PAUSE_FLAG_FILE_SUFFIX = ".pause.flag";
    private static final String PANIC_FLAG_FILE_SUFFIX = ".panic.flag";
    private static final int    DEFAULT_MYSQL_PORT     = 3306;

    private final DataSourceConfig                dsConfig;
    private final GhostConfig                     context;
    private final GhostAlterTableParser.AlterTarget target;
    private final GhostProgressState              progress;

    private final String                          sockFile;
    private final String                          pauseFile;
    private final String                          panicFile;

    private final AtomicBoolean                   spawnRejected = new AtomicBoolean(false);
    private volatile Process                      process;

    public GhostExecutor(DataSourceConfig dsConfig, GhostConfig context, GhostAlterTableParser.AlterTarget target,
                         GhostProgressState progress){
        this.dsConfig = dsConfig;
        this.context = context;
        this.target = target;
        this.progress = progress;
        this.sockFile = context.getFileDir() + context.getSessionId() + SOCK_FILE_SUFFIX;
        this.pauseFile = context.getFileDir() + context.getSessionId() + PAUSE_FLAG_FILE_SUFFIX;
        this.panicFile = context.getFileDir() + context.getSessionId() + PANIC_FLAG_FILE_SUFFIX;
    }

    public String getPauseFile() { return pauseFile; }

    public String getPanicFile() { return panicFile; }

    public String getSockFile() { return sockFile; }

    GhostConfig getContext() { return context; }

    GhostProgressState getProgress() { return progress; }

    public String binaryPath() {
        return context.getToolHomeDir() + File.separator + BINARY_NAME;
    }

    // ------------------------------------------------------------------ command assembly

    /**
     * Assemble the gh-ost argv. The returned command contains NO secret (--ask-pass instead of
     * --password) and is therefore logged verbatim. Values are passed as single argv entries —
     * ProcessBuilder needs no shell quoting.
     */
    protected List<String> buildCommand() {
        GhostOptionsDTO options = context.getGhostOptionsDTO();
        String[] ipPort = splitHostPort(dsConfig.getHost());

        List<String> cmd = new ArrayList<>();
        cmd.add(binaryPath());
        cmd.add("--host=" + ipPort[0]);
        cmd.add("--port=" + ipPort[1]);
        // keep the legacy semantics: never auto-discover the master, treat the configured
        // endpoint as the master (proxy topologies report a different @@port — spike §1)
        cmd.add("--assume-master-host=" + ipPort[0] + ":" + ipPort[1]);
        cmd.add("--user=" + safe(dsConfig.getUserName()));
        // R12: password via stdin only
        cmd.add("--ask-pass");
        cmd.add("--database=" + safe(target.getDatabase()));
        cmd.add("--table=" + safe(target.getTableName()));
        cmd.add("--alter=" + safe(target.getAlterClause()));

        if (boolVal(options == null ? null : options.getAssumeDsProxyInFront())) {
            // a proxy fronts the datasource (or it runs behind Docker/NAT): skip gh-ost's
            // server-side port/topology verification — spike-proven on Tencent-Cloud CDB
            cmd.add("--aliyun-rds");
        }
        if (boolVal(options == null ? null : options.getAssumeRbr())) {
            cmd.add("--assume-rbr");
        }
        if (boolVal(options == null ? null : options.getAllowOnMaster())) {
            cmd.add("--allow-on-master");
        }
        if (intVal(options == null ? null : options.getCutOverLockTimeoutSec()) > 0) {
            cmd.add("--cut-over-lock-timeout-seconds=" + options.getCutOverLockTimeoutSec());
        }
        if (intVal(options == null ? null : options.getDmlBatchSize()) > 0) {
            cmd.add("--dml-batch-size=" + options.getDmlBatchSize());
        }
        if (intVal(options == null ? null : options.getChunkSize()) > 0) {
            cmd.add("--chunk-size=" + options.getChunkSize());
        }
        if (boolVal(options == null ? null : options.getVerbose())) {
            cmd.add("--verbose");
        }
        if (options != null && options.getCutOverType() != null) {
            cmd.add("--cut-over=" + options.getCutOverType().getVal());
        }
        if (boolVal(options == null ? null : options.getExactRowCount())) {
            cmd.add("--exact-rowcount");
        }
        if (boolVal(options == null ? null : options.getConcurrentRowCount())) {
            cmd.add("--concurrent-rowcount");
        }
        if (intVal(options == null ? null : options.getHbIntervalMs()) > 0) {
            cmd.add("--heartbeat-interval-millis=" + options.getHbIntervalMs());
        }
        if (boolVal(options == null ? null : options.getInitiallyDropGhostTable())) {
            cmd.add("--initially-drop-ghost-table");
        }
        if (boolVal(options == null ? null : options.getTimestampOldTable())) {
            cmd.add("--timestamp-old-table");
        }

        cmd.add("--serve-socket-file=" + sockFile);
        cmd.add("--throttle-flag-file=" + pauseFile);
        cmd.add("--panic-flag-file=" + panicFile);
        cmd.add("--execute");
        return cmd;
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Spawn gh-ost and block until the process exits, streaming the merged output into the
     * session main.log and the progress parser. Terminal state is recorded on {@code progress}:
     * panic-flag present → ABORTED; exit 0 → COMPLETED; anything else → FAILED.
     */
    public void startBlocking() {
        if (!spawnRejected.compareAndSet(false, true)) {
            throw new GhostToolException(GhostToolException.CODE_ALREADY_STARTED, "gh-ost was already spawned for this session.");
        }

        initFileDir();
        File binary = new File(binaryPath());
        if (!binary.isFile()) {
            throw new GhostToolException(GhostToolException.CODE_BINARY_MISSING,
                    "gh-ost binary not found at " + binaryPath() + " (see the deployment guide: tools/gh-ost/).");
        }
        if (!binary.canExecute() && !binary.setExecutable(true)) {
            throw new GhostToolException(GhostToolException.CODE_BINARY_MISSING,
                    "gh-ost binary at " + binaryPath() + " is not executable and chmod failed.");
        }

        List<String> cmd = buildCommand();
        // log-safe: argv carries --ask-pass, never the password (design §2.4 revision)
        logger.info("[GH-OST] exec start. cmd: " + String.join(" ", cmd));
        logger.info("[GH-OST] database: " + context.getDatabase() + ", ddl(alter clause): " + context.getDdlSql());

        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(cmd);
            // gh-ost writes its log lines to either stream depending on version/path; merging
            // guarantees main.log receives everything and the noise filter sees every line
            builder.redirectErrorStream(true);
            process = builder.start();
        } catch (IOException e) {
            progress.markFailed("failed to spawn gh-ost: " + ExceptionUtils.getRootCauseMessage(e));
            throw new GhostToolException(GhostToolException.CODE_EXEC_FAILED,
                    "failed to spawn gh-ost: " + ExceptionUtils.getRootCauseMessage(e), e);
        }
        this.process = process;

        try {
            feedPassword(process);
            PumpResult pump = pumpOutput(process);
            int exitCode = process.waitFor();

            if (new File(panicFile).exists()) {
                progress.markAborted("stopped by user (panic flag), gh-ost exit code " + exitCode + ".");
                logger.info("[GH-OST] aborted by panic flag, exit code " + exitCode + ".");
            } else if (exitCode == 0 && pump.fatalMessage == null) {
                progress.markCompleted();
                logger.info("[GH-OST] migration completed, exit code 0.");
            } else {
                String reason = pump.fatalMessage != null ? pump.fatalMessage
                        : "gh-ost exited with code " + exitCode + (pump.lastLine == null ? "." : ", last output: " + pump.lastLine);
                progress.markFailed(reason);
                logger.error("[GH-OST] migration failed: " + reason);
            }
        } catch (IOException e) {
            progress.markFailed("gh-ost stream/exit failure: " + ExceptionUtils.getRootCauseMessage(e));
            throw new GhostToolException(GhostToolException.CODE_EXEC_FAILED,
                    "gh-ost stream/exit failure: " + ExceptionUtils.getRootCauseMessage(e), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            progress.markFailed("gh-ost wait interrupted: " + ExceptionUtils.getRootCauseMessage(e));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /** write the password to gh-ost's stdin (--ask-pass) and close the stream */
    private void feedPassword(Process process) {
        String password = dsConfig.getPassword() == null ? "" : dsConfig.getPassword();
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write((password + "\n").getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        } catch (IOException e) {
            // the process may have died before reading (bad host, bad user...) — do not mask the
            // real error here; the output pump + exit code surface it
            logger.warn("[GH-OST] failed to feed password via stdin: " + ExceptionUtils.getRootCauseMessage(e));
        }
    }

    private static final class PumpResult {

        private String fatalMessage;
        private String lastLine;
    }

    /** stream the merged process output line-by-line into main.log + the progress parser (blocking) */
    private PumpResult pumpOutput(Process process) throws IOException {
        PumpResult result = new PumpResult();
        File logFile = new File(context.getLogFileFullPath());
        File parent = logFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            logger.warn("[GH-OST] log directory create failed: " + parent.getAbsolutePath());
        }

        try (InputStream input = process.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
                BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(new FileOutputStream(logFile, true), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                writer.write(line);
                writer.newLine();
                writer.flush(); // live tail: tailLog must see lines while the migration runs

                GhostLogEvent event = GhostProgressParser.parseLine(line);
                progress.apply(event);
                if (event.getType() == GhostLogEvent.Type.FATAL && result.fatalMessage == null) {
                    result.fatalMessage = event.getValue();
                }
                if (!line.trim().isEmpty()) {
                    result.lastLine = line.trim();
                }
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ flag-file control (legacy mechanism, kept verbatim)

    public void pause() {
        File f = new File(pauseFile);
        if (f.exists()) {
            logger.warn(pauseFile + " already exist,ghost should stop already.");
            return;
        }

        try {
            boolean suc = f.createNewFile();
            if (!suc) {
                logger.warn(pauseFile + " create return false.");
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("create " + pauseFile + " failed,msg:" + ExceptionUtils.getRootCauseMessage(e), e);
        }
    }

    public void resume() {
        File f = new File(pauseFile);
        if (!f.exists()) {
            logger.warn(pauseFile + " not exist,ghost should running yet.");
            return;
        }

        try {
            boolean suc = f.delete();
            if (!suc) {
                logger.warn(pauseFile + " delete return false.");
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("delete " + pauseFile + " failed,msg:" + ExceptionUtils.getRootCauseMessage(e), e);
        }
    }

    public void stop() {
        File f = new File(panicFile);
        if (f.exists()) {
            logger.warn(panicFile + " already exist,ghost should exit already.");
            return;
        }

        try {
            boolean suc = f.createNewFile();
            if (!suc) {
                logger.warn(panicFile + " create return false.");
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("create " + panicFile + " failed,msg:" + ExceptionUtils.getRootCauseMessage(e), e);
        }
    }

    public void clean() {
        deleteQuietly(sockFile);
        deleteQuietly(pauseFile);
        deleteQuietly(panicFile);
    }

    private void deleteQuietly(String path) {
        File f = new File(path);
        if (f.exists()) {
            boolean suc = f.delete();
            if (!suc) {
                logger.warn(path + " delete return false.");
            }
        }
    }

    // ------------------------------------------------------------------ process control helpers

    public boolean isAlive() {
        Process p = this.process;
        return p != null && p.isAlive();
    }

    public boolean waitForExit(long timeoutMillis) {
        Process p = this.process;
        if (p == null) {
            return true;
        }
        try {
            return p.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void destroyForcibly() {
        Process p = this.process;
        if (p != null && p.isAlive()) {
            p.destroyForcibly();
        }
    }

    // ------------------------------------------------------------------ helpers

    protected void initFileDir() {
        File f = new File(context.getFileDir());
        if (!f.exists()) {
            boolean suc = f.mkdirs();
            if (!suc) {
                throw new GhostToolException(GhostToolException.CODE_EXEC_FAILED, context.getFileDir() + " directory create failed.");
            }
        }
    }

    /** legacy appendHostPort semantics: "host[:port]", default 3306 */
    static String[] splitHostPort(String host) {
        if (host == null || host.trim().isEmpty()) {
            throw new GhostToolException(GhostToolException.CODE_BAD_LAUNCH_CONFIG, "datasource host is blank.");
        }
        String[] ipPort = host.trim().split(":");
        if (ipPort.length == 2) {
            return new String[] { ipPort[0], ipPort[1] };
        } else if (ipPort.length == 1) {
            return new String[] { ipPort[0], String.valueOf(DEFAULT_MYSQL_PORT) };
        }
        throw new GhostToolException(GhostToolException.CODE_BAD_LAUNCH_CONFIG, "MySQL host is illegal:" + host);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static boolean boolVal(Boolean value) {
        return Boolean.TRUE.equals(value);
    }

    private static int intVal(Integer value) {
        return value == null ? 0 : value;
    }
}
