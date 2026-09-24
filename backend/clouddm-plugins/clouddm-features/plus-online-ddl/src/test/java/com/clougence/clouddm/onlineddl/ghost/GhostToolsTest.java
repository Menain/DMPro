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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.slf4j.MDC;

import com.clougence.clouddm.base.metadata.ds.DataSourceConfig;
import com.clougence.clouddm.base.metadata.ds.DataSourceType;
import com.clougence.clouddm.base.metadata.ds.SslMode;
import com.clougence.clouddm.base.metadata.ds.ToolConfig;
import com.clougence.clouddm.base.metadata.ds.tools.GhostPluginConfig;
import com.clougence.clouddm.sdk.execute.tools.ToolRequestDTO;
import com.clougence.clouddm.sdk.execute.tools.ToolResultDTO;
import com.clougence.clouddm.sdk.execute.tools.ToolSessionContextDTO;
import com.clougence.clouddm.sdk.execute.tools.ToolUtils;
import com.clougence.clouddm.sdk.model.ghost.GhostCutOverType;
import com.clougence.clouddm.sdk.model.ghost.GhostLaunchDTO;
import com.clougence.clouddm.sdk.model.ghost.GhostOptionsDTO;
import com.clougence.clouddm.sdk.service.config.ConfigData;
import com.clougence.clouddm.sdk.service.config.ConfigService;
import com.clougence.clouddm.sdk.service.secrules.SensitiveConfig;
import com.clougence.schema.dialect.Dialect;
import com.clougence.schema.umi.struts.UmiTypes;
import com.clougence.utils.JsonUtils;

/**
 * GhostTools session-facade tests: R3 hard checks at init, GhostConfig assembly, options merge
 * semantics, method dispatch guards, tailStatus/tailLog contracts, close/keepLog behaviour and
 * the START runner (fake gh-ost binary, no Spring context / no mockStatic).
 */
public class GhostToolsTest {

    private static final String FAKE_VERSION = "1.1.11 (git commit: fake)";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File logDir;
    private File mainLog;

    @Before
    public void setUpMdc() throws Exception {
        logDir = tmp.newFolder("logs", "ghost", "sess1");
        mainLog = new File(logDir, "main.log");
        MDC.put(ToolUtils.MDCKEY_LOG_PATH, logDir.getAbsolutePath());
        MDC.put(ToolUtils.MDCKEY_LOG_FILE, mainLog.getAbsolutePath());
    }

    @After
    public void clearMdc() {
        MDC.remove(ToolUtils.MDCKEY_LOG_PATH);
        MDC.remove(ToolUtils.MDCKEY_LOG_FILE);
    }

    // ------------------------------------------------------------------ fixtures

    private DataSourceConfig mysqlDs() {
        return dsOfType(DataSourceType.MySQL);
    }

    /** DataSourceConfig.setDataSourceType is init-once — build with the type from the start */
    private DataSourceConfig dsOfType(DataSourceType type) {
        DataSourceConfig config = new DataSourceConfig();
        config.setDataSourceType(type);
        config.setHost("10.206.100.12:3306");
        config.setUserName("developer");
        config.setPassword("S3cret!pwd");
        return config;
    }

    private static ConfigService configServiceOf(DataSourceConfig returned) {
        return new ConfigService() {

            @Override
            public List<ConfigData> fetchSettings(List<String> names) { return null; }

            @Override
            public DataSourceConfig fetchDsConfig(long dsId) { return returned; }

            @Override
            public ToolConfig fetchToolConfig(String toolName) { return null; }

            @Override
            public Dialect findDialectByDsType(DataSourceType dsType) { return null; }

            @Override
            public SensitiveConfig fetchSensitiveConfigByDs(long dsId) { return null; }

            @Override
            public List<UmiTypes> fetchDsLevelDef(DataSourceType dsType) { return null; }
        };
    }

    /**
     * GhostTools with the resolveToolHome seam pinned to a prepared tools dir. The version-probe
     * seam is pinned too: the fake gh-ost scripts below block on `read pw`/`sleep`, so a real
     * probe spawn would stall until its timeout — dedicated tests exercise the real probe.
     */
    private GhostTools tools(DataSourceConfig dsConfig, File toolHome) {
        return new GhostTools(null, configServiceOf(dsConfig), new GhostPluginConfig()) {

            @Override
            protected String resolveToolHome() {
                return toolHome.getAbsolutePath();
            }

            @Override
            protected String probeBinaryVersion(File binary) {
                return FAKE_VERSION;
            }
        };
    }

    /** GhostTools with ONLY the resolveToolHome seam pinned — the version probe is the real one */
    private GhostTools toolsWithRealProbe(DataSourceConfig dsConfig, File toolHome) {
        return new GhostTools(null, configServiceOf(dsConfig), new GhostPluginConfig()) {

            @Override
            protected String resolveToolHome() {
                return toolHome.getAbsolutePath();
            }
        };
    }

    private File toolHomeWithBinary(String script) throws Exception {
        File home = tmp.newFolder("tools", "gh-ost");
        File bin = new File(home, "gh-ost");
        if (script != null) {
            Files.write(bin.toPath(), script.getBytes(StandardCharsets.UTF_8));
            assertTrue(bin.setExecutable(true));
        } else {
            assertTrue(bin.createNewFile()); // init only checks existence
        }
        return home;
    }

    private static ToolSessionContextDTO ctx(GhostLaunchDTO launch) {
        ToolSessionContextDTO contextDTO = new ToolSessionContextDTO();
        contextDTO.setSessionId("sess1");
        contextDTO.setConfiguration(launch == null ? null : JsonUtils.toJson(launch));
        return contextDTO;
    }

    private static GhostLaunchDTO launch(String ddlSql) {
        GhostLaunchDTO launch = new GhostLaunchDTO();
        launch.setDsId(9L);
        launch.setSchema("linshiceshi");
        launch.setDdlSql(ddlSql);
        return launch;
    }

    private static void assertCode(String code, Runnable run) {
        try {
            run.run();
            fail("expected GhostToolException " + code);
        } catch (GhostToolException e) {
            assertEquals(code, e.getCode());
            assertTrue(e.getMessage().startsWith(code + ": "));
        }
    }

    // ------------------------------------------------------------------ init hard checks (R3)

    @Test
    public void initHappyPathAssemblesExecutor() throws Exception {
        File home = toolHomeWithBinary(null);
        GhostTools tools = tools(mysqlDs(), home);

        tools.init(ctx(launch("ALTER TABLE t_spike ADD COLUMN extra INT")));

        GhostExecutor executor = tools.getExecutor();
        assertNotNull(executor);
        // fileDir ≡ session log dir; flag files follow the legacy <sessionId><suffix> naming
        assertEquals(new File(logDir, "sess1.pause.flag").getAbsolutePath(), executor.getPauseFile());
        assertEquals(new File(logDir, "sess1.panic.flag").getAbsolutePath(), executor.getPanicFile());
        assertEquals(new File(logDir, "sess1.sock").getAbsolutePath(), executor.getSockFile());
        // logFileFullPath ≡ ToolUtils.getLogFile() (single-log unification, design §2.3)
        assertEquals(mainLog.getAbsolutePath(), executor.getContext().getLogFileFullPath());
        assertEquals(home.getAbsolutePath(), executor.getContext().getToolHomeDir());
        assertEquals("linshiceshi", executor.getContext().getDatabase());
        // GhostConfig.ddlSql carries the ALTER CLAUSE (gh-ost --alter payload), not the statement
        assertEquals("ADD COLUMN extra INT", executor.getContext().getDdlSql());
        // merged options fall back to the admin GhostPluginConfig defaults
        assertEquals(Integer.valueOf(3000), executor.getContext().getGhostOptionsDTO().getChunkSize());
        assertEquals(GhostProgressState.STATUS_INIT, tools.getProgress().getStatus());
        // S3 version stamp: init records whatever the probe seam returned
        assertEquals(FAKE_VERSION, tools.getBinaryVersion());
    }

    @Test
    public void initRejectsNonMySqlDatasource() throws Exception {
        DataSourceConfig pg = dsOfType(DataSourceType.PostgreSQL);
        File home = toolHomeWithBinary(null);

        assertCode(GhostToolException.CODE_DS_TYPE_UNSUPPORTED, () -> {
            try {
                tools(pg, home).init(ctx(launch("ALTER TABLE t_spike ADD COLUMN extra INT")));
            } catch (GhostToolException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Test
    public void initRejectsSshTunnelDatasource() throws Exception {
        DataSourceConfig ssh = mysqlDs();
        ssh.setSshProxyEnabled(true);
        File home = toolHomeWithBinary(null);

        assertCode(GhostToolException.CODE_TUNNEL_UNSUPPORTED, () -> {
            try {
                tools(ssh, home).init(ctx(launch("ALTER TABLE t_spike ADD COLUMN extra INT")));
            } catch (GhostToolException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Test
    public void initRejectsSslDatasource() throws Exception {
        DataSourceConfig ssl = mysqlDs();
        ssl.setSslMode(SslMode.CA);
        File home = toolHomeWithBinary(null);

        assertCode(GhostToolException.CODE_TUNNEL_UNSUPPORTED, () -> {
            try {
                tools(ssl, home).init(ctx(launch("ALTER TABLE t_spike ADD COLUMN extra INT")));
            } catch (GhostToolException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        // DISABLED (and null) stay accepted
        ssl.setSslMode(SslMode.DISABLED);
        GhostTools disabledSsl = tools(ssl, home);
        disabledSsl.init(ctx(launch("ALTER TABLE t_spike ADD COLUMN extra INT")));
        assertNotNull(disabledSsl.getExecutor());
    }

    @Test
    public void initRejectsMultiStatementAndNonAlter() throws Exception {
        File home = toolHomeWithBinary(null);

        assertCode(GhostToolException.CODE_STMT_UNSUPPORTED, () -> {
            try {
                tools(mysqlDs(), home).init(ctx(launch("ALTER TABLE t1 ADD COLUMN a INT; DROP TABLE t2")));
            } catch (GhostToolException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertCode(GhostToolException.CODE_SCHEMA_MISMATCH, () -> {
            try {
                tools(mysqlDs(), home).init(ctx(launch("ALTER TABLE other_db.t1 ADD COLUMN a INT")));
            } catch (GhostToolException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Test
    public void initRejectsBadLaunchConfiguration() throws Exception {
        File home = toolHomeWithBinary(null);

        assertCode(GhostToolException.CODE_BAD_LAUNCH_CONFIG, () -> {
            try {
                tools(mysqlDs(), home).init(ctx(null));
            } catch (GhostToolException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertCode(GhostToolException.CODE_BAD_LAUNCH_CONFIG, () -> {
            GhostLaunchDTO noSchema = launch("ALTER TABLE t1 ADD COLUMN a INT");
            noSchema.setSchema(" ");
            try {
                tools(mysqlDs(), home).init(ctx(noSchema));
            } catch (GhostToolException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Test
    public void initRejectsMissingBinary() throws Exception {
        File home = tmp.newFolder("tools", "empty");

        assertCode(GhostToolException.CODE_BINARY_MISSING, () -> {
            try {
                tools(mysqlDs(), home).init(ctx(launch("ALTER TABLE t_spike ADD COLUMN extra INT")));
            } catch (GhostToolException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    // ------------------------------------------------------------------ S3 version probe (real seam)

    @Test
    public void versionProbeCapturesBinaryVersionAtInit() throws Exception {
        File home = toolHomeWithBinary("#!/bin/sh\necho '1.1.11 (git commit: 2ed192cd39d8a5173b6ef7c4beaa746a2d3bf1aa)'\n");
        GhostTools tools = toolsWithRealProbe(mysqlDs(), home);

        tools.init(ctx(launch("ALTER TABLE t_spike ADD COLUMN extra INT")));

        assertEquals("1.1.11 (git commit: 2ed192cd39d8a5173b6ef7c4beaa746a2d3bf1aa)", tools.getBinaryVersion());
    }

    @Test
    public void versionProbeFailureNeverBlocksInit() throws Exception {
        // not executable → spawn IOException → warn only, version stays null, init completes
        File home = toolHomeWithBinary(null);
        GhostTools tools = toolsWithRealProbe(mysqlDs(), home);
        tools.init(ctx(launch("ALTER TABLE t_spike ADD COLUMN extra INT")));
        assertNotNull(tools.getExecutor());
        assertEquals(null, tools.getBinaryVersion());

        // non-zero exit → null as well (a distinct folder — newFolder refuses duplicates)
        File badHome = tmp.newFolder("tools", "gh-ost-bad");
        File badBin = new File(badHome, "gh-ost");
        Files.write(badBin.toPath(), "#!/bin/sh\necho 'boom' >&2\nexit 3\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(badBin.setExecutable(true));
        GhostTools badTools = toolsWithRealProbe(mysqlDs(), badHome);
        badTools.init(ctx(launch("ALTER TABLE t_spike ADD COLUMN extra INT")));
        assertNotNull(badTools.getExecutor());
        assertEquals(null, badTools.getBinaryVersion());
    }

    // ------------------------------------------------------------------ options merge

    @Test
    public void mergeOptionsTaskOverridesAdminFieldByField() {
        GhostPluginConfig admin = new GhostPluginConfig();
        admin.setChunkSize(1000);
        admin.setVerbose(true);
        GhostOptionsDTO task = new GhostOptionsDTO();
        task.setChunkSize(5000);
        task.setCutOverType(GhostCutOverType.ATOMIC);
        // a fresh GhostOptionsDTO carries non-null initializer defaults on every field — "task
        // silent" therefore means an EXPLICIT null (the wire shape of a partial override JSON)
        task.setAssumeRbr(null);
        task.setVerbose(null);

        GhostOptionsDTO merged = GhostTools.mergeOptions(admin, task);

        assertEquals(Integer.valueOf(5000), merged.getChunkSize());          // task wins
        assertEquals(GhostCutOverType.ATOMIC, merged.getCutOverType());      // task wins
        assertTrue(merged.getVerbose());                                     // task null → admin wins
        assertTrue(merged.getAssumeRbr());                                   // task null → admin default
        assertEquals(Integer.valueOf(10), merged.getDmlBatchSize());         // task initializer default wins over admin's (also 10)
        assertTrue(merged.getAssumeDsProxyInFront());
        // a non-null task field overrides a differing admin value
        task.setDmlBatchSize(77);
        assertEquals(Integer.valueOf(77), GhostTools.mergeOptions(admin, task).getDmlBatchSize());
    }

    @Test
    public void mergeOptionsNullTaskKeepsAdminConfig() {
        GhostPluginConfig admin = new GhostPluginConfig();
        admin.setChunkSize(1234);

        GhostOptionsDTO merged = GhostTools.mergeOptions(admin, null);

        assertEquals(Integer.valueOf(1234), merged.getChunkSize());
        assertEquals(GhostCutOverType.DEFAULT, merged.getCutOverType());
    }

    @Test
    public void mergeOptionsNullAdminKeepsInitializerDefaults() {
        GhostOptionsDTO merged = GhostTools.mergeOptions(null, null);

        assertEquals(Integer.valueOf(3000), merged.getChunkSize());
        assertEquals(Integer.valueOf(10), merged.getDmlBatchSize());
        assertEquals(Integer.valueOf(10), merged.getCutOverLockTimeoutSec());
        assertEquals(Integer.valueOf(2000), merged.getHbIntervalMs());
        assertEquals(GhostCutOverType.DEFAULT, merged.getCutOverType());
    }

    // ------------------------------------------------------------------ invoke dispatch

    @Test
    public void controlMethodsBeforeInitRejected() {
        GhostTools tools = new GhostTools(null, configServiceOf(mysqlDs()), new GhostPluginConfig());

        assertCode(GhostToolException.CODE_NOT_INITIALIZED, () -> tools.invoke("PAUSE", null));
        assertCode(GhostToolException.CODE_NOT_INITIALIZED, () -> tools.invoke("RESUME", null));
        assertCode(GhostToolException.CODE_NOT_INITIALIZED, () -> tools.invoke("STOP", null));
        assertCode(GhostToolException.CODE_NOT_INITIALIZED, () -> tools.invoke("START", null));
    }

    @Test
    public void undefinedMethodRejected() throws Exception {
        File home = toolHomeWithBinary(null);
        GhostTools tools = tools(mysqlDs(), home);
        tools.init(ctx(launch("ALTER TABLE t_spike ADD COLUMN extra INT")));

        try {
            tools.invoke("PREVIEW", null);
            fail("undefined method must be rejected");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("'PREVIEW' undefined method."));
        }
    }

    @Test
    public void pauseResumeToggleTheFlagFileAndStatus() throws Exception {
        File home = toolHomeWithBinary("#!/bin/sh\nread pw\nsleep 30\n");
        GhostTools tools = tools(mysqlDs(), home);
        tools.init(ctx(launch("ALTER TABLE t_spike ADD COLUMN extra INT")));
        File pauseFlag = new File(tools.getExecutor().getPauseFile());

        tools.getProgress().markRunning();
        ToolResultDTO paused = tools.invoke("PAUSE", null);
        assertTrue(paused.isSuccess());
        assertTrue(pauseFlag.isFile());
        assertEquals(GhostProgressState.STATUS_PAUSED, tools.getProgress().getStatus());

        ToolResultDTO resumed = tools.invoke("RESUME", null);
        assertTrue(resumed.isSuccess());
        assertFalse(pauseFlag.exists());
        assertEquals(GhostProgressState.STATUS_RUNNING, tools.getProgress().getStatus());

        ToolResultDTO stopped = tools.invoke("STOP", null);
        assertTrue(stopped.isSuccess());
        assertTrue(new File(tools.getExecutor().getPanicFile()).isFile());

        tools.close(); // kill the sleeping fake process, clean the flags
        assertFalse(new File(tools.getExecutor().getPanicFile()).exists());
    }

    @Test
    public void startSpawnsRunnerAndCompletes() throws Exception {
        File home = toolHomeWithBinary("#!/bin/sh\nread pw\necho \"PWD-VIA-STDIN:$pw\"\n"
                                       + "echo '2026-09-24 17:13:23 INFO Row copy complete'\necho '# Done'\nexit 0\n");
        GhostTools tools = tools(mysqlDs(), home);
        tools.init(ctx(launch("ALTER TABLE t_spike ADD COLUMN extra INT")));

        ToolResultDTO started = tools.invoke("START", null);
        assertTrue(started.isSuccess());
        assertEquals(GhostProgressState.STATUS_RUNNING, started.getBody());

        awaitFinished(tools);
        assertEquals(GhostProgressState.STATUS_COMPLETED, tools.getProgress().getStatus());
        assertEquals(GhostProgressState.PHASE_DONE, tools.getProgress().toStatusDTO().getPhase());
        String log = new String(Files.readAllBytes(mainLog.toPath()), StandardCharsets.UTF_8);
        assertTrue(log.contains("PWD-VIA-STDIN:S3cret!pwd"));

        // second START is refused without touching the process again
        ToolResultDTO second = tools.invoke("START", null);
        assertFalse(second.isSuccess());
        assertTrue(second.getMessage().contains(GhostToolException.CODE_ALREADY_STARTED));
    }

    // ------------------------------------------------------------------ tail contracts

    @Test
    public void tailStatusBeforeStartIsInitWithoutProgress() throws Exception {
        File home = toolHomeWithBinary(null);
        GhostTools tools = tools(mysqlDs(), home);
        tools.init(ctx(launch("ALTER TABLE t_spike ADD COLUMN extra INT")));

        ToolResultDTO result = tools.tailStatus(null);

        assertTrue(result.isSuccess());
        assertTrue(result.getBody().contains("\"status\":\"INIT\""));
        assertTrue(result.getBody().contains("\"phase\":\"READY\""));
        assertTrue(result.getBody().contains("\"useProgress\":false"));
    }

    @Test
    public void tailLogPagesTheSessionMainLog() throws Exception {
        File home = toolHomeWithBinary(null);
        GhostTools tools = tools(mysqlDs(), home);
        tools.init(ctx(launch("ALTER TABLE t_spike ADD COLUMN extra INT")));

        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            content.append("line-").append(i).append('\n');
        }
        Files.write(mainLog.toPath(), content.toString().getBytes(StandardCharsets.UTF_8));

        ToolRequestDTO request = new ToolRequestDTO();
        request.setBody("{\"startLine\":0}");
        ToolResultDTO newest = tools.tailLog(request);
        assertTrue(newest.isSuccess());
        assertTrue("newest page contains the last line", newest.getBody().contains("line-39"));
        assertFalse("newest page does not contain older lines", newest.getBody().contains("line-10"));

        request.setBody("{\"startLine\":5}");
        ToolResultDTO slice = tools.tailLog(request);
        assertTrue(slice.getBody().contains("line-5"));
        assertTrue(slice.getBody().contains("line-25"));
        // the faker paging contract includes indices [startLine, startLine+PAGE] — line-31 is out
        assertFalse(slice.getBody().contains("line-31"));

        // missing log file degrades to an explanatory single line (faker contract)
        assertTrue(mainLog.delete());
        ToolResultDTO missing = tools.tailLog(request);
        assertTrue(missing.isSuccess());
        assertTrue(missing.getBody().contains("does not exist."));
    }

    // ------------------------------------------------------------------ close / keepLog

    @Test
    public void closeRemovesLogUnlessKeepLog() throws Exception {
        File home = toolHomeWithBinary(null);
        GhostTools tools = tools(mysqlDs(), home);
        tools.init(ctx(launch("ALTER TABLE t_spike ADD COLUMN extra INT")));
        Files.write(mainLog.toPath(), "x".getBytes(StandardCharsets.UTF_8));

        tools.close();
        assertFalse("keepLog=false must delete the session main.log", mainLog.exists());

        // keepLog=true keeps the log (FakerPluginConfig semantics); the first close deleted the dir
        assertTrue(logDir.mkdirs());
        MDC.put(ToolUtils.MDCKEY_LOG_PATH, logDir.getAbsolutePath());
        MDC.put(ToolUtils.MDCKEY_LOG_FILE, mainLog.getAbsolutePath());
        GhostPluginConfig keepConfig = new GhostPluginConfig();
        keepConfig.setKeepLog(true);
        GhostTools keepTools = new GhostTools(null, configServiceOf(mysqlDs()), keepConfig) {

            @Override
            protected String resolveToolHome() {
                return home.getAbsolutePath();
            }
        };
        keepTools.init(ctx(launch("ALTER TABLE t_spike ADD COLUMN extra INT")));
        Files.write(mainLog.toPath(), "x".getBytes(StandardCharsets.UTF_8));
        keepTools.close();
        assertTrue("keepLog=true must retain the session main.log", mainLog.exists());
    }

    // ------------------------------------------------------------------ helpers

    private static void awaitFinished(GhostTools tools) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000L;
        while (!tools.getProgress().isFinished() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertTrue("fake gh-ost runner did not finish in time", tools.getProgress().isFinished());
    }
}
