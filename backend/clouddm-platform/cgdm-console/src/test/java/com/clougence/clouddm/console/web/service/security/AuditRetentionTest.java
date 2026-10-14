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
package com.clougence.clouddm.console.web.service.security;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.console.web.component.config.RootUserConfig;
import com.clougence.clouddm.console.web.service.auth.RdpUserService;
import com.clougence.clouddm.platform.dal.access.ExecutionDal;
import com.clougence.clouddm.platform.dal.access.MonitorDal;
import com.clougence.clouddm.platform.dal.access.SystemDal;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecSqlAuditMapper;
import com.clougence.clouddm.platform.dal.mapper.monitor.DmMonBizLogMapper;
import com.clougence.clouddm.platform.dal.mapper.monitor.DmMonOpAuditMapper;
import com.clougence.clouddm.platform.dal.mapper.system.DmSysUserConfMapper;
import com.clougence.clouddm.platform.dal.model.auth.DmAuthUserDO;
import com.clougence.clouddm.platform.dal.model.monitor.LogDependBizType;
import com.clougence.clouddm.platform.dal.model.system.DmSysUserConfDO;

/**
 * C8 M1 test class (task 10-13-audit-chain-enhancement, design D8 — one of the three
 * authorized new classes): audit retention cleanup + the M1 retrieval-foundation defect pins.
 * <p>
 * Sections:
 * <ol>
 * <li>D-1 failing-repro pin — {@code DmMonBizLogMapper.queryListByBizIdAndType} shipped (since
 *     the initial commit) with a MISSING {@code AND} between its two WHERE conditions → a MySQL
 *     syntax error on every call. Its only caller (the C6/M4 MASKING_NOT_BOUND observation
 *     dedup query, QueryAnalysisServiceImpl.observeMaskingNotBound) is never-throw, so the
 *     syntax error was swallowed and the observation row was inferred to have NEVER landed.
 *     Repro form per design D1: a bare MyBatis {@link Configuration} loads the mapper XML (no
 *     Spring context — repository discipline) and the {@link BoundSql} text is asserted.</li>
 * <li>Retention cleanup (design D2): the two new RootUserConfig KVs, clamping, the 0/negative
 *     disable escape hatch, the batched deleteBeforeDate legs and the D-3 configDO null guard
 *     (a missing config row must warn+skip the round, never NPE the 30-min scheduler into a
 *     silent standstill).</li>
 * </ol>
 * Discipline: JUnit4 + hand-written mocks + ReflectionTestUtils — no Spring context.
 */
public class AuditRetentionTest {

    // ==================== D-1: queryListByBizIdAndType WHERE shape ====================

    /**
     * The BoundSql of queryListByBizIdAndType must join its two predicates with AND. Pre-fix
     * this assertion fails (the two conditions are concatenated with whitespace only —
     * "depend_on_biz_id = ? depend_on_biz_type = ?" is a MySQL syntax error), which is the
     * failing repro of the survey A3/D-1 defect; post-fix (one AND added in the XML) it pins
     * the repair against regression.
     */
    @Test
    public void bizLogMapper_queryListByBizIdAndType_predicatesJoinedWithAnd() throws Exception {
        Configuration configuration = new Configuration();
        String resource = "mybatis/mapper/DmMonBizLogMapper.xml";
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull("mapper XML missing on the test classpath: " + resource, in);
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }

        MappedStatement ms = configuration.getMappedStatement(
            "com.clougence.clouddm.platform.dal.mapper.monitor.DmMonBizLogMapper.queryListByBizIdAndType");
        assertNotNull(ms);

        Map<String, Object> param = new HashMap<>();
        param.put("bizId", "42");
        param.put("bizType", LogDependBizType.MASKING_NOT_BOUND);
        String sql = ms.getBoundSql(param).getSql().replaceAll("\\s+", " ");

        assertTrue("the two WHERE predicates must be joined with AND (D-1 defect repro), got: " + sql,
            sql.contains("depend_on_biz_id = ? AND depend_on_biz_type = ?"));
    }

    // ==================== retention sweep harness (design D2) ====================

    private AuditServiceImpl      service;
    private SystemDal             systemDal;
    private DmSysUserConfMapper   userConfMapper;
    private ExecutionDal          execDal;
    private DmExecSqlAuditMapper  sqlAuditMapper;
    private MonitorDal            monitorDal;
    private DmMonOpAuditMapper    opAuditMapper;
    private DmMonBizLogMapper     bizLogMapper;
    private RdpUserService        userService;

    private static final String UID1 = "primary-1";
    private static final String UID2 = "primary-2";

    @Before
    public void setUp() {
        service = new AuditServiceImpl();
        systemDal = mock(SystemDal.class);
        userConfMapper = mock(DmSysUserConfMapper.class);
        when(systemDal.userConfMapper()).thenReturn(userConfMapper);
        execDal = mock(ExecutionDal.class);
        sqlAuditMapper = mock(DmExecSqlAuditMapper.class);
        when(execDal.sqlAuditMapper()).thenReturn(sqlAuditMapper);
        monitorDal = mock(MonitorDal.class);
        opAuditMapper = mock(DmMonOpAuditMapper.class);
        bizLogMapper = mock(DmMonBizLogMapper.class);
        when(monitorDal.opAuditMapper()).thenReturn(opAuditMapper);
        when(monitorDal.bizLogMapper()).thenReturn(bizLogMapper);
        userService = mock(RdpUserService.class);
        ReflectionTestUtils.setField(service, "systemDal", systemDal);
        ReflectionTestUtils.setField(service, "execDal", execDal);
        ReflectionTestUtils.setField(service, "monitorDal", monitorDal);
        ReflectionTestUtils.setField(service, "userService", userService);
        when(userService.listPrimaryUser()).thenReturn(List.of(primary(UID1)));
        // default: every fail-safe KV read yields its passed default (the fetchUserConf contract)
        when(systemDal.fetchUserConf(anyString(), anyString(), eq(Integer.class), any()))
            .thenAnswer(inv -> inv.getArgument(3));
        // default: the sql-audit config row exists with a sane value (the legacy leg reads it raw)
        when(userConfMapper.queryByUidAndConfigName(anyString(), eq(RootUserConfig.Fields.sqlAuditRetentionDays)))
            .thenReturn(conf("30"));
    }

    private static DmAuthUserDO primary(String uid) {
        DmAuthUserDO user = new DmAuthUserDO();
        user.setUid(uid);
        return user;
    }

    private static DmSysUserConfDO conf(String value) {
        DmSysUserConfDO confDO = new DmSysUserConfDO();
        confDO.setConfigValue(value);
        return confDO;
    }

    private void runSweep() {
        ReflectionTestUtils.invokeMethod(service, "deleteTimeoutLog");
    }

    private void stubOpAuditRetention(String uid, Integer value) {
        when(systemDal.fetchUserConf(eq(uid), eq(RootUserConfig.Fields.opAuditRetentionDays), eq(Integer.class), any()))
            .thenReturn(value);
    }

    private void stubBizLogRetention(String uid, Integer value) {
        when(systemDal.fetchUserConf(eq(uid), eq(RootUserConfig.Fields.bizLogRetentionDays), eq(Integer.class), any()))
            .thenReturn(value);
    }

    /** the cutoff Date argument must sit at now - dayCount days (±1 minute tolerance). */
    private static void assertCutoffDays(Date captured, int dayCount) {
        long expected = System.currentTimeMillis() - (long) dayCount * 24 * 60 * 60 * 1000;
        assertTrue("cutoff " + captured + " must be ~" + dayCount + " days back",
            Math.abs(captured.getTime() - expected) < 60_000L);
    }

    // ==================== op_audit leg ====================

    @Test
    public void opAudit_defaultRetention_batchesUntilDrained() {
        // default stub → 365; two batches then drained (the LIMIT-500 loop)
        when(opAuditMapper.deleteBeforeDate(eq(UID1), any())).thenReturn(500, 137, 0);

        runSweep();

        ArgumentCaptor<Date> cutoff = ArgumentCaptor.forClass(Date.class);
        verify(opAuditMapper, times(3)).deleteBeforeDate(eq(UID1), cutoff.capture());
        assertCutoffDays(cutoff.getValue(), AuditServiceImpl.OP_AUDIT_RETENTION_DEFAULT_DAYS);
    }

    @Test
    public void opAudit_clampedIntoRange_andDisabledByNonPositive() {
        // over-max clamps to 3650
        stubOpAuditRetention(UID1, 999999);
        when(opAuditMapper.deleteBeforeDate(eq(UID1), any())).thenReturn(0);
        runSweep();
        ArgumentCaptor<Date> cutoff = ArgumentCaptor.forClass(Date.class);
        verify(opAuditMapper).deleteBeforeDate(eq(UID1), cutoff.capture());
        assertCutoffDays(cutoff.getValue(), AuditServiceImpl.OP_AUDIT_RETENTION_MAX_DAYS);

        // under-min clamps to 30
        org.mockito.Mockito.reset(opAuditMapper);
        stubOpAuditRetention(UID1, 1);
        runSweep();
        cutoff = ArgumentCaptor.forClass(Date.class);
        verify(opAuditMapper).deleteBeforeDate(eq(UID1), cutoff.capture());
        assertCutoffDays(cutoff.getValue(), AuditServiceImpl.OP_AUDIT_RETENTION_MIN_DAYS);

        // 0 = DISABLED (the permanent-retention escape hatch) — negative likewise
        org.mockito.Mockito.reset(opAuditMapper);
        stubOpAuditRetention(UID1, 0);
        runSweep();
        verify(opAuditMapper, never()).deleteBeforeDate(anyString(), any());
        stubOpAuditRetention(UID1, -5);
        runSweep();
        verify(opAuditMapper, never()).deleteBeforeDate(anyString(), any());
    }

    // ==================== biz_log leg (global, most-conservative resolution) ====================

    @Test
    public void bizLog_defaultRetention_globalBatchedDelete() {
        when(bizLogMapper.deleteBeforeDate(any())).thenReturn(500, 0);

        runSweep();

        ArgumentCaptor<Date> cutoff = ArgumentCaptor.forClass(Date.class);
        verify(bizLogMapper, times(2)).deleteBeforeDate(cutoff.capture());
        assertCutoffDays(cutoff.getValue(), AuditServiceImpl.BIZ_LOG_RETENTION_DEFAULT_DAYS);
    }

    @Test
    public void bizLog_clampedToMinimum_andLargestEffectiveWins() {
        // under-min clamps to 7
        stubBizLogRetention(UID1, 1);
        when(bizLogMapper.deleteBeforeDate(any())).thenReturn(0);
        runSweep();
        ArgumentCaptor<Date> cutoff = ArgumentCaptor.forClass(Date.class);
        verify(bizLogMapper).deleteBeforeDate(cutoff.capture());
        assertCutoffDays(cutoff.getValue(), AuditServiceImpl.BIZ_LOG_RETENTION_MIN_DAYS);

        // two primaries: the LARGEST effective day count wins (a row dies only when every
        // tenant window has passed — the table has no owner column)
        org.mockito.Mockito.reset(bizLogMapper);
        when(userService.listPrimaryUser()).thenReturn(List.of(primary(UID1), primary(UID2)));
        stubBizLogRetention(UID1, 30);
        stubBizLogRetention(UID2, 120);
        when(bizLogMapper.deleteBeforeDate(any())).thenReturn(0);
        runSweep();
        cutoff = ArgumentCaptor.forClass(Date.class);
        verify(bizLogMapper).deleteBeforeDate(cutoff.capture());
        assertCutoffDays(cutoff.getValue(), 120);
    }

    @Test
    public void bizLog_anyPrimaryDisabling_disablesTheGlobalLeg() {
        when(userService.listPrimaryUser()).thenReturn(List.of(primary(UID1), primary(UID2)));
        stubBizLogRetention(UID1, 90);
        stubBizLogRetention(UID2, 0); // one disable vote kills the global leg

        runSweep();

        verify(bizLogMapper, never()).deleteBeforeDate(any());
    }

    // ==================== D-3 guard + leg isolation (调度不停摆) ====================

    @Test
    public void missingSqlAuditConfigRow_warnsAndSkipsLeg_otherLegsStillRun() {
        // D-3: the raw configDO read returns null → pre-fix this NPE-ed the scheduled task dead
        when(userConfMapper.queryByUidAndConfigName(anyString(), eq(RootUserConfig.Fields.sqlAuditRetentionDays)))
            .thenReturn(null);
        when(opAuditMapper.deleteBeforeDate(eq(UID1), any())).thenReturn(0);
        when(bizLogMapper.deleteBeforeDate(any())).thenReturn(0);

        runSweep(); // must not throw

        verify(sqlAuditMapper, never()).deleteAuditBeforeDate(anyString(), any());
        verify(opAuditMapper).deleteBeforeDate(eq(UID1), any()); // op leg unaffected
        verify(bizLogMapper).deleteBeforeDate(any());            // biz leg unaffected
    }

    @Test
    public void oneLegFailing_neverKillsTheOtherLegs() {
        when(opAuditMapper.deleteBeforeDate(eq(UID1), any())).thenThrow(new RuntimeException("op audit store down"));
        when(bizLogMapper.deleteBeforeDate(any())).thenReturn(0);

        runSweep(); // must not throw — scheduleWithFixedDelay would silently stop otherwise

        verify(bizLogMapper).deleteBeforeDate(any()); // the later leg still ran
    }

    @Test
    public void sqlAuditLeg_clampBehaviourUnchanged() {
        // the legacy leg keeps its own caliber: 1-60 clamp (200 → 60), untouched by C8
        when(userConfMapper.queryByUidAndConfigName(anyString(), eq(RootUserConfig.Fields.sqlAuditRetentionDays)))
            .thenReturn(conf("200"));
        when(sqlAuditMapper.deleteAuditBeforeDate(eq(UID1), any())).thenReturn(0);

        runSweep();

        ArgumentCaptor<Date> cutoff = ArgumentCaptor.forClass(Date.class);
        verify(sqlAuditMapper).deleteAuditBeforeDate(eq(UID1), cutoff.capture());
        assertCutoffDays(cutoff.getValue(), 60);
    }
}
