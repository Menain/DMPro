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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.clougence.clouddm.base.metadata.ds.DataSourceConfig;
import com.clougence.clouddm.base.metadata.ds.DataSourceType;
import com.clougence.clouddm.sdk.model.ghost.GhostConfig;
import com.clougence.clouddm.sdk.model.ghost.GhostCutOverType;
import com.clougence.clouddm.sdk.model.ghost.GhostOptionsDTO;

/**
 * Command-assembly contract (B4 lesson: assert COMPLETE command fragments, never single tokens),
 * flag-file control side effects, and end-to-end stdin credential injection against a fake
 * /bin/sh gh-ost stand-in.
 */
public class GhostExecutorTest {

    private static final String PASSWORD = "S3cret!pwd";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // ------------------------------------------------------------------ fixtures

    private DataSourceConfig dsConfig(String host) {
        DataSourceConfig config = new DataSourceConfig();
        config.setDataSourceType(DataSourceType.MySQL);
        config.setHost(host);
        config.setUserName("developer");
        config.setPassword(PASSWORD);
        return config;
    }

    private GhostConfig ghostConfig(String toolHomeDir) throws Exception {
        File fileDir = tmp.newFolder("session");
        GhostConfig config = new GhostConfig();
        config.setSessionId("sess1");
        config.setDatabase("linshiceshi");
        config.setSchema("linshiceshi");
        config.setDdlSql("ADD COLUMN extra INT");
        config.setFileDir(fileDir.getAbsolutePath() + File.separator);
        config.setToolHomeDir(toolHomeDir);
        config.setLogFileFullPath(new File(fileDir, "main.log").getAbsolutePath());
        config.setGhostOptionsDTO(new GhostOptionsDTO());
        return config;
    }

    private GhostExecutor executor(String host, String toolHomeDir) throws Exception {
        return executorWith(host, toolHomeDir, new GhostOptionsDTO());
    }

    private GhostExecutor executorWith(String host, String toolHomeDir, GhostOptionsDTO options) throws Exception {
        GhostConfig config = ghostConfig(toolHomeDir);
        config.setGhostOptionsDTO(options);
        GhostAlterTableParser.AlterTarget target = GhostAlterTableParser.parse("ALTER TABLE t_spike ADD COLUMN extra INT", "linshiceshi");
        return new GhostExecutor(dsConfig(host), config, target, new GhostProgressState());
    }

    private static String joined(GhostExecutor executor) {
        return String.join(" ", executor.buildCommand());
    }

    private File fakeGhost(String script) throws Exception {
        File home = tmp.newFolder("tools");
        File bin = new File(home, "gh-ost");
        Files.write(bin.toPath(), script.getBytes(StandardCharsets.UTF_8));
        assertTrue("fake gh-ost must be executable", bin.setExecutable(true));
        return home;
    }

    // ------------------------------------------------------------------ command contract

    @Test
    public void commandCarriesAskPassAndNeverThePassword() throws Exception {
        String cmd = joined(executor("10.206.100.12:3306", "/opt/tools/gh-ost"));

        assertTrue(cmd.contains("--ask-pass"));
        assertFalse("password flag must never enter argv", cmd.contains("--password"));
        assertFalse("password value must never enter argv", cmd.contains(PASSWORD));
        // spike pitfall #1: --ask-pass only covers the password, the user must be explicit
        assertTrue(cmd.contains("--user=developer"));
    }

    @Test
    public void commandCarriesCompleteConnectionFragment() throws Exception {
        String cmd = joined(executor("10.206.100.12:3306", "/opt/tools/gh-ost"));

        assertTrue(cmd.startsWith("/opt/tools/gh-ost/gh-ost --host=10.206.100.12 --port=3306 "
                                  + "--assume-master-host=10.206.100.12:3306 --user=developer --ask-pass "
                                  + "--database=linshiceshi --table=t_spike --alter=ADD COLUMN extra INT"));
    }

    @Test
    public void defaultOptionsProduceCompleteFlagFragment() throws Exception {
        String cmd = joined(executor("10.206.100.12:3306", "/opt/tools/gh-ost"));

        // GhostOptionsDTO defaults (aligned with GhostPluginConfig): full contiguous fragment
        assertTrue(cmd.contains("--aliyun-rds --assume-rbr --allow-on-master --cut-over-lock-timeout-seconds=10 "
                                + "--dml-batch-size=10 --chunk-size=3000 --cut-over=default "
                                + "--heartbeat-interval-millis=2000 --initially-drop-ghost-table --timestamp-old-table"));
        assertFalse(cmd.contains("--verbose"));
        assertFalse(cmd.contains("--exact-rowcount"));
        assertFalse(cmd.contains("--concurrent-rowcount"));
    }

    @Test
    public void disabledOptionsStayOutOfTheCommand() throws Exception {
        GhostOptionsDTO options = new GhostOptionsDTO();
        options.setAssumeDsProxyInFront(false);
        options.setAssumeRbr(false);
        options.setAllowOnMaster(false);
        options.setTimestampOldTable(false);
        options.setInitiallyDropGhostTable(false);
        GhostExecutor executor = executorWith("10.206.100.12:3306", "/opt/tools/gh-ost", options);

        String cmd = joined(executor);
        assertFalse(cmd.contains("--aliyun-rds"));
        assertFalse(cmd.contains("--assume-rbr"));
        assertFalse(cmd.contains("--allow-on-master"));
        assertFalse(cmd.contains("--timestamp-old-table"));
        assertFalse(cmd.contains("--initially-drop-ghost-table"));
        // independent flags survive
        assertTrue(cmd.contains("--cut-over=default"));
        assertTrue(cmd.contains("--execute"));
    }

    @Test
    public void enabledCountAndVerboseOptionsAppear() throws Exception {
        GhostOptionsDTO options = new GhostOptionsDTO();
        options.setVerbose(true);
        options.setExactRowCount(true);
        options.setConcurrentRowCount(true);
        GhostExecutor executor = executorWith("10.206.100.12:3306", "/opt/tools/gh-ost", options);

        String cmd = joined(executor);
        assertTrue(cmd.contains("--verbose --cut-over=default --exact-rowcount --concurrent-rowcount"));
    }

    @Test
    public void atomicCutOverUsesLowercaseEnumVal() throws Exception {
        GhostOptionsDTO options = new GhostOptionsDTO();
        options.setCutOverType(GhostCutOverType.ATOMIC);
        GhostExecutor executor = executorWith("10.206.100.12:3306", "/opt/tools/gh-ost", options);

        assertTrue(joined(executor).contains("--cut-over=atomic"));
    }

    @Test
    public void fileFlagsAndExecuteCloseTheCommand() throws Exception {
        GhostExecutor executor = executor("10.206.100.12:3306", "/opt/tools/gh-ost");
        String cmd = joined(executor);

        String expectedTail = "--serve-socket-file=" + executor.getSockFile()
                              + " --throttle-flag-file=" + executor.getPauseFile()
                              + " --panic-flag-file=" + executor.getPanicFile()
                              + " --execute";
        assertTrue("command must end with the file flags + --execute: " + cmd, cmd.endsWith(expectedTail));
        assertTrue(executor.getPauseFile().endsWith("sess1.pause.flag"));
        assertTrue(executor.getPanicFile().endsWith("sess1.panic.flag"));
        assertTrue(executor.getSockFile().endsWith("sess1.sock"));
    }

    @Test
    public void hostWithoutPortDefaultsTo3306() throws Exception {
        String cmd = joined(executor("db.internal", "/opt/tools/gh-ost"));

        assertTrue(cmd.contains("--host=db.internal --port=3306 --assume-master-host=db.internal:3306"));
    }

    @Test
    public void illegalHostRejected() {
        try {
            GhostExecutor.splitHostPort("a:b:c");
            fail("illegal host must be rejected");
        } catch (GhostToolException e) {
            assertEquals(GhostToolException.CODE_BAD_LAUNCH_CONFIG, e.getCode());
        }
        try {
            GhostExecutor.splitHostPort("  ");
            fail("blank host must be rejected");
        } catch (GhostToolException e) {
            assertEquals(GhostToolException.CODE_BAD_LAUNCH_CONFIG, e.getCode());
        }
    }

    // ------------------------------------------------------------------ flag-file control

    @Test
    public void pauseResumeStopCleanFileSideEffects() throws Exception {
        GhostExecutor executor = executor("10.206.100.12:3306", "/opt/tools/gh-ost");
        File pause = new File(executor.getPauseFile());
        File panic = new File(executor.getPanicFile());
        File sock = new File(executor.getSockFile());

        executor.pause();
        assertTrue(pause.isFile());
        executor.pause(); // idempotent
        assertTrue(pause.isFile());

        executor.resume();
        assertFalse(pause.exists());
        executor.resume(); // idempotent

        executor.stop();
        assertTrue(panic.isFile());

        assertTrue(sock.createNewFile());
        executor.clean();
        assertFalse(pause.exists());
        assertFalse(panic.exists());
        assertFalse(sock.exists());
    }

    // ------------------------------------------------------------------ process E2E (fake binary)

    @Test
    public void passwordTravelsViaStdinNotArgv() throws Exception {
        File home = fakeGhost("#!/bin/sh\nread pw\necho \"PWD-VIA-STDIN:$pw\"\necho \"ARGS:$@\"\necho \"# Done\"\nexit 0\n");
        GhostExecutor executor = executor("10.206.100.12:3306", home.getAbsolutePath());
        GhostProgressState progress = progressOf(executor);

        executor.startBlocking();

        String log = readLog(executor);
        assertTrue("password must reach gh-ost through stdin", log.contains("PWD-VIA-STDIN:" + PASSWORD));
        for (String line : log.split("\n")) {
            if (line.startsWith("ARGS:")) {
                assertFalse("argv must not leak the password: " + line, line.contains(PASSWORD));
                assertTrue(line.contains("--ask-pass"));
            }
        }
        assertEquals(GhostProgressState.STATUS_COMPLETED, progress.getStatus());
        assertEquals(GhostProgressState.PHASE_DONE, progress.toStatusDTO().getPhase());
    }

    @Test
    public void fatalErrorDrivesFailedState() throws Exception {
        File home = fakeGhost("#!/bin/sh\nread pw\n"
                              + "echo '2026-09-24 17:10:00 FATAL Unexpected database port reported: 20336'\nexit 1\n");
        GhostExecutor executor = executor("10.206.100.12:3306", home.getAbsolutePath());
        GhostProgressState progress = progressOf(executor);

        executor.startBlocking();

        assertEquals(GhostProgressState.STATUS_FAILED, progress.getStatus());
        assertEquals("Unexpected database port reported: 20336", progress.toStatusDTO().getFailReason());
    }

    @Test
    public void teardownNoiseDoesNotFailASuccessfulRun() throws Exception {
        File home = fakeGhost("#!/bin/sh\nread pw\n"
                              + "echo '2026-09-24 17:13:26 ERROR [error] binlogsyncer.go:631 connection was bad'\n"
                              + "echo '2026-09-24 17:13:26 ERROR [error] binlogstreamer.go:77 close sync with err' >&2\n"
                              + "echo '# Done'\nexit 0\n");
        GhostExecutor executor = executor("10.206.100.12:3306", home.getAbsolutePath());
        GhostProgressState progress = progressOf(executor);

        executor.startBlocking();

        assertEquals(GhostProgressState.STATUS_COMPLETED, progress.getStatus());
        // noise lines still land in main.log (tolerated, not hidden)
        assertTrue(readLog(executor).contains("connection was bad"));
    }

    @Test
    public void panicFlagDrivesAbortedState() throws Exception {
        File home = fakeGhost("#!/bin/sh\nread pw\necho 'panicking'\nexit 1\n");
        GhostExecutor executor = executor("10.206.100.12:3306", home.getAbsolutePath());
        GhostProgressState progress = progressOf(executor);
        assertTrue(new File(executor.getPanicFile()).getParentFile().exists() || new File(executor.getPanicFile()).getParentFile().mkdirs());
        assertTrue(new File(executor.getPanicFile()).createNewFile());

        executor.startBlocking();

        assertEquals(GhostProgressState.STATUS_ABORTED, progress.getStatus());
        assertTrue(progress.toStatusDTO().getFailReason().contains("panic flag"));
    }

    @Test
    public void missingBinaryRejected() throws Exception {
        File home = tmp.newFolder("empty-tools");
        GhostExecutor executor = executor("10.206.100.12:3306", home.getAbsolutePath());

        try {
            executor.startBlocking();
            fail("missing binary must be rejected");
        } catch (GhostToolException e) {
            assertEquals(GhostToolException.CODE_BINARY_MISSING, e.getCode());
        }
    }

    @Test
    public void doubleSpawnRejected() throws Exception {
        File home = fakeGhost("#!/bin/sh\nread pw\necho '# Done'\nexit 0\n");
        GhostExecutor executor = executor("10.206.100.12:3306", home.getAbsolutePath());

        executor.startBlocking();
        try {
            executor.startBlocking();
            fail("second spawn must be rejected");
        } catch (GhostToolException e) {
            assertEquals(GhostToolException.CODE_ALREADY_STARTED, e.getCode());
        }
    }

    // ------------------------------------------------------------------ helpers

    private static GhostProgressState progressOf(GhostExecutor executor) {
        return executor.getProgress();
    }

    private static String readLog(GhostExecutor executor) throws Exception {
        File logFile = new File(executor.getContext().getLogFileFullPath());
        List<String> lines = Files.readAllLines(logFile.toPath(), StandardCharsets.UTF_8);
        return String.join("\n", lines);
    }
}
