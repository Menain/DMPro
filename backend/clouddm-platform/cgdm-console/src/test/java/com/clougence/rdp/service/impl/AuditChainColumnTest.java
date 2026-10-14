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
package com.clougence.rdp.service.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.console.web.component.governance.GovSqlHashUtils;
import com.clougence.clouddm.console.web.service.security.AuditServiceImpl;
import com.clougence.clouddm.platform.dal.access.AuthDal;
import com.clougence.clouddm.platform.dal.access.DataSourceDal;
import com.clougence.clouddm.platform.dal.access.ExecutionDal;
import com.clougence.clouddm.platform.dal.access.MonitorDal;
import com.clougence.clouddm.platform.dal.mapper.auth.DmAuthUserMapper;
import com.clougence.clouddm.platform.dal.mapper.execution.DmExecSqlAuditMapper;
import com.clougence.clouddm.platform.dal.mapper.monitor.DmMonOpAuditMapper;
import com.clougence.clouddm.platform.dal.model.ResourceType;
import com.clougence.clouddm.platform.dal.model.auth.DmAuthUserDO;
import com.clougence.clouddm.platform.dal.model.datasource.DmDsDO;
import com.clougence.clouddm.platform.dal.model.execution.DmExecSqlAuditDO;
import com.clougence.clouddm.platform.dal.model.monitor.AuditChannel;
import com.clougence.clouddm.platform.dal.model.monitor.AuditType;
import com.clougence.clouddm.platform.dal.model.monitor.DmMonOpAuditDO;
import com.clougence.clouddm.platform.dal.model.monitor.SecurityLevel;
import com.clougence.clouddm.sdk.execute.session.QueryRequest;
import com.clougence.clouddm.sdk.service.secrules.Requester;
import com.clougence.rdp.service.RdpOpAuditService;
import com.clougence.rdp.service.model.AuditChainMO;

/**
 * C8 M2 test class (task 10-13-audit-chain-enhancement, design D8 — one of the three authorized
 * new classes): the four audit-chain columns at the logOperation sink + the additive overload
 * delegation + the channel URI inference + the sql_audit fingerprint + the predicate shape.
 * <p>
 * Pins:
 * <ol>
 * <li>red line 1 — the legacy 9/10-arg overloads delegate UNCHANGED (chain null) yet still gain
 *     the inferred channel (存量调用零改动即得 channel);</li>
 * <li>channel resolution order: explicit &gt; URI inference ({@code /open/} ∨ {@code /api/open} →
 *     OPENAPI; {@code /system/} → SYSTEM_AUTO; other non-blank → CONSOLE; null/blank → null) —
 *     the single-point three-arm rule;</li>
 * <li>red line 3 — the sink writes the caller-supplied fingerprint VERBATIM (never re-hashes);
 *     the caliber pin lives at the write points (GovSqlHashUtils.hash of the FULL frozen text,
 *     asserted in the dispatch/trail tests) and in {@link #sqlAudit};</li>
 * <li>red line 4 — the sink performs NO new SELECT for the chain keys (the user lookup stays the
 *     single pre-existing one; the 10/11-arg forms never touch the resourceName resolvers);</li>
 * <li>the operationAuditCondition predicate is additive: with the four new parameters null the
 *     rendered SQL is byte-identical to the pre-C8 shape (bare-MyBatis BoundSql, no Spring).</li>
 * </ol>
 * Discipline: JUnit4 + hand-written mocks + ReflectionTestUtils — no Spring context.
 */
public class AuditChainColumnTest {

    private RdpOpAuditServiceImpl service;
    private AuthDal               authDal;
    private DmAuthUserMapper      userMapper;
    private MonitorDal            monitorDal;
    private DmMonOpAuditMapper    opAuditMapper;
    private DataSourceDal         datasourceDal;

    private static final String PUID = "puid-1";
    private static final String UID  = "uid-1";

    @Before
    public void setUp() {
        service = new RdpOpAuditServiceImpl();
        authDal = mock(AuthDal.class);
        userMapper = mock(DmAuthUserMapper.class);
        when(authDal.userMapper()).thenReturn(userMapper);
        DmAuthUserDO user = new DmAuthUserDO();
        user.setUsername("tester");
        when(userMapper.queryByUid(anyString())).thenReturn(user);
        monitorDal = mock(MonitorDal.class);
        opAuditMapper = mock(DmMonOpAuditMapper.class);
        when(monitorDal.opAuditMapper()).thenReturn(opAuditMapper);
        datasourceDal = mock(DataSourceDal.class);
        ReflectionTestUtils.setField(service, "authDal", authDal);
        ReflectionTestUtils.setField(service, "monitorDal", monitorDal);
        ReflectionTestUtils.setField(service, "datasourceDal", datasourceDal);
    }

    private DmMonOpAuditDO insertedRow() {
        ArgumentCaptor<DmMonOpAuditDO> captor = ArgumentCaptor.forClass(DmMonOpAuditDO.class);
        verify(opAuditMapper).insert(captor.capture());
        return captor.getValue();
    }

    // ==================== 1/2. channel inference: the single-point three-arm rule ====================

    @Test
    public void inferFromUri_threeArms_plusNullArm() {
        // OPENAPI arm: the semantic marker AND the real HTTP prefix
        assertEquals(AuditChannel.OPENAPI, AuditChannel.inferFromUri("/open/dbChange/tickets/submit"));
        assertEquals(AuditChannel.OPENAPI, AuditChannel.inferFromUri("/api/open/dbChange/tickets/submit"));
        // SYSTEM_AUTO arm: the semantic system-plane markers
        assertEquals(AuditChannel.SYSTEM_AUTO, AuditChannel.inferFromUri("/system/dbChangeV2/ghost/auto-dispatch"));
        assertEquals(AuditChannel.SYSTEM_AUTO, AuditChannel.inferFromUri("/system/masking/original-view"));
        // CONSOLE arm: real console URIs
        assertEquals(AuditChannel.CONSOLE, AuditChannel.inferFromUri("/api/entry/datasource/updateDs"));
        assertEquals(AuditChannel.CONSOLE, AuditChannel.inferFromUri("/api/entry/govrelease/overrideGrant"));
        // null arm: absent URI never gets a fake default
        assertNull(AuditChannel.inferFromUri(null));
        assertNull(AuditChannel.inferFromUri("  "));
    }

    // ==================== 1. legacy overload delegation (red line 1) ====================

    @Test
    public void legacyNineArg_overload_gainsInferredChannel_chainColumnsStayNull() {
        DmDsDO ds = new DmDsDO();
        ds.setInstanceId("inst-9");
        com.clougence.clouddm.platform.dal.mapper.datasource.DmDsMapper dsMapper
            = mock(com.clougence.clouddm.platform.dal.mapper.datasource.DmDsMapper.class);
        when(datasourceDal.dsMapper()).thenReturn(dsMapper);
        when(dsMapper.queryDsIdentityById(anyLong())).thenReturn(ds);

        service.logAndAddOperationAudit(PUID, UID, "/api/entry/editor/query", "10.0.0.1", 5L, "obj",
            SecurityLevel.NORMAL, AuditType.EXPORT_QUERY_RESULT, ResourceType.DATASOURCE);

        DmMonOpAuditDO row = insertedRow();
        assertEquals(AuditChannel.CONSOLE, row.getChannel()); // 存量调用零改动即得 channel
        assertNull(row.getSqlFingerprint());
        assertNull(row.getTicketId());
        assertNull(row.getReleaseId());
        assertEquals("inst-9", row.getResourceName()); // getResourceName path unchanged
    }

    @Test
    public void legacyTenArg_overload_systemUri_infersSystemAuto() {
        service.logAndAddOperationAudit(PUID, UID, "/system/prodRelease/ghost/dispatch", null, 900L, "obj",
            SecurityLevel.HIGH, AuditType.GHOST_EXECUTE, ResourceType.PURE_URL, "release:900");

        DmMonOpAuditDO row = insertedRow();
        assertEquals(AuditChannel.SYSTEM_AUTO, row.getChannel());
        assertEquals("release:900", row.getResourceName());
        verifyNoInteractions(datasourceDal); // the 10-arg bypass never resolves a name
    }

    // ==================== 2. explicit channel beats the inference ====================

    @Test
    public void explicitChannel_winsOverUriInference() {
        // a real console URI but an explicit verdict (the data-editor masking landing, design D3
        // "按落点"): explicit OPENAPI here proves the precedence regardless of the URI shape
        service.logAndAddOperationAudit(PUID, UID, "/api/entry/x", null, 5L, "obj",
            SecurityLevel.HIGH, AuditType.SENSITIVE_ORIGINAL_VIEW, ResourceType.PURE_URL, "name",
            AuditChainMO.ofChannel(AuditChannel.OPENAPI));

        assertEquals(AuditChannel.OPENAPI, insertedRow().getChannel());
    }

    @Test
    public void nullChannelOnChain_fallsBackToUriInference() {
        AuditChainMO chain = new AuditChainMO(); // channel null
        service.logAndAddOperationAudit(PUID, UID, "/system/dbChangeV2/dmlBatch/resolution", null, 5L, "obj",
            SecurityLevel.HIGH, AuditType.DML_BATCH_EXECUTE, ResourceType.PURE_URL, "name", chain);

        assertEquals(AuditChannel.SYSTEM_AUTO, insertedRow().getChannel());
    }

    @Test
    public void nullUri_yieldsNullChannel_neverAFakeDefault() {
        service.logAndAddOperationAudit(PUID, UID, null, null, 5L, "obj",
            SecurityLevel.HIGH, AuditType.GHOST_EXECUTE, ResourceType.PURE_URL, "name",
            AuditChainMO.ofChannel(null));

        assertNull(insertedRow().getChannel());
    }

    // ==================== 3/4. chain columns verbatim + zero new SELECT (red lines 3/4) ====================

    @Test
    public void chainColumns_writtenVerbatim_noRehash_noExtraSelect() {
        String fingerprint = GovSqlHashUtils.hash("UPDATE t SET a = 1 WHERE id > 0");

        service.logAndAddOperationAudit(PUID, UID, "/system/dbChangeV2/dmlBatch/auto-dispatch", null, 5L, "obj",
            SecurityLevel.HIGH, AuditType.DML_BATCH_EXECUTE, ResourceType.PURE_URL, "name",
            AuditChainMO.ofTicket(42L, fingerprint, AuditChannel.SYSTEM_AUTO));

        DmMonOpAuditDO row = insertedRow();
        assertEquals("the sink must write the caller-supplied fingerprint VERBATIM (never re-hash)",
            fingerprint, row.getSqlFingerprint());
        assertEquals(Long.valueOf(42L), row.getTicketId());
        assertNull(row.getReleaseId());
        assertEquals(AuditChannel.SYSTEM_AUTO, row.getChannel());
        // red line 4: exactly ONE pre-existing user SELECT, no chain-key lookups, no name resolution
        verify(userMapper, times(1)).queryByUid(UID);
        verifyNoInteractions(datasourceDal);
    }

    @Test
    public void releaseChain_factory_bindsReleaseIdOnly() {
        service.logAndAddOperationAudit(PUID, UID, "/system/prodRelease/routeOverride/consume", null, 5L, "obj",
            SecurityLevel.HIGH, AuditType.GOV_ROUTE_OVERRIDE_CONSUME, ResourceType.PURE_URL, "release:7",
            AuditChainMO.ofRelease(7L, null, AuditChannel.SYSTEM_AUTO));

        DmMonOpAuditDO row = insertedRow();
        assertEquals(Long.valueOf(7L), row.getReleaseId());
        assertNull(row.getTicketId());
        assertNull(row.getSqlFingerprint());
    }

    // ==================== fingerprint caliber: the sql_audit write point (M2.6) ====================

    @Test
    public void sqlAudit_prepareAudit_stampsGovSqlHashUtilsFingerprint() {
        AuditServiceImpl auditService = new AuditServiceImpl();
        ExecutionDal execDal = mock(ExecutionDal.class);
        DmExecSqlAuditMapper sqlAuditMapper = mock(DmExecSqlAuditMapper.class);
        when(execDal.sqlAuditMapper()).thenReturn(sqlAuditMapper);
        when(sqlAuditMapper.queryByQueryId(anyString())).thenReturn(null);
        com.clougence.clouddm.platform.dal.access.DataSourceDal dsDal
            = mock(com.clougence.clouddm.platform.dal.access.DataSourceDal.class);
        com.clougence.clouddm.platform.dal.mapper.datasource.DmDsMapper dsMapper
            = mock(com.clougence.clouddm.platform.dal.mapper.datasource.DmDsMapper.class);
        when(dsDal.dsMapper()).thenReturn(dsMapper);
        DmDsDO ds = new DmDsDO();
        ds.setInstanceId("inst-1");
        ds.setInstanceDesc("desc");
        when(dsMapper.queryDsIdentityById(anyLong())).thenReturn(ds);
        ReflectionTestUtils.setField(auditService, "execDal", execDal);
        ReflectionTestUtils.setField(auditService, "dsDal", dsDal);
        ReflectionTestUtils.setField(auditService, "userService",
            mock(com.clougence.clouddm.console.web.service.auth.RdpUserService.class));

        String sql = "UPDATE t_order SET status = 1\n  WHERE status = 0";
        QueryRequest request = new QueryRequest();
        request.setQueryId("q-1");
        request.setQueryBody(sql);
        request.setRequester(Requester.CONSOLE);

        auditService.prepareAudit(11L, UID, request);

        ArgumentCaptor<DmExecSqlAuditDO> captor = ArgumentCaptor.forClass(DmExecSqlAuditDO.class);
        verify(sqlAuditMapper).insert(captor.capture());
        // the caliber pin: the FULL statement text through the ONE governance hash implementation
        assertEquals(GovSqlHashUtils.hash(sql), captor.getValue().getSqlFingerprint());
        // whitespace-layout stability WITHIN the GovSqlHashUtils contract: per-line indentation /
        // redundant spaces never change the hash (line structure is preserved — lines join with
        // "\n", so a newline→space FLATTENING legitimately hashes differently; pinned both ways)
        assertEquals(captor.getValue().getSqlFingerprint(),
            GovSqlHashUtils.hash("UPDATE t_order SET status = 1\nWHERE status = 0"));
        org.junit.Assert.assertNotEquals(captor.getValue().getSqlFingerprint(),
            GovSqlHashUtils.hash("UPDATE t_order SET status = 1 WHERE status = 0"));
    }

    @Test
    public void sqlAudit_blankBody_leavesFingerprintNull() {
        AuditServiceImpl auditService = new AuditServiceImpl();
        ExecutionDal execDal = mock(ExecutionDal.class);
        DmExecSqlAuditMapper sqlAuditMapper = mock(DmExecSqlAuditMapper.class);
        when(execDal.sqlAuditMapper()).thenReturn(sqlAuditMapper);
        when(sqlAuditMapper.queryByQueryId(anyString())).thenReturn(null);
        com.clougence.clouddm.platform.dal.access.DataSourceDal dsDal
            = mock(com.clougence.clouddm.platform.dal.access.DataSourceDal.class);
        com.clougence.clouddm.platform.dal.mapper.datasource.DmDsMapper dsMapper
            = mock(com.clougence.clouddm.platform.dal.mapper.datasource.DmDsMapper.class);
        when(dsDal.dsMapper()).thenReturn(dsMapper);
        when(dsMapper.queryDsIdentityById(anyLong())).thenReturn(new DmDsDO());
        ReflectionTestUtils.setField(auditService, "execDal", execDal);
        ReflectionTestUtils.setField(auditService, "dsDal", dsDal);
        ReflectionTestUtils.setField(auditService, "userService",
            mock(com.clougence.clouddm.console.web.service.auth.RdpUserService.class));

        QueryRequest request = new QueryRequest();
        request.setQueryId("q-2");
        request.setQueryBody("   ");
        request.setRequester(Requester.CONSOLE);

        auditService.prepareAudit(11L, UID, request);

        ArgumentCaptor<DmExecSqlAuditDO> captor = ArgumentCaptor.forClass(DmExecSqlAuditDO.class);
        verify(sqlAuditMapper).insert(captor.capture());
        assertNull(captor.getValue().getSqlFingerprint());
    }

    // ==================== 5. predicate additivity (all-null → unchanged SQL shape) ====================

    private static Configuration loadOpAuditMapperConfig() throws Exception {
        Configuration configuration = new Configuration();
        String resource = "mybatis/mapper/DmMonOpAuditMapper.xml";
        try (InputStream in = AuditChainColumnTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull("mapper XML missing on the test classpath: " + resource, in);
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
        return configuration;
    }

    private static Map<String, Object> predicateParams() {
        Map<String, Object> params = new HashMap<>();
        params.put("puid", "puid-1");
        params.put("uid", null);
        params.put("securityLevel", null);
        params.put("auditType", null);
        params.put("resourceType", null);
        params.put("userNameLike", null);
        params.put("dateStart", null);
        params.put("dateEnd", null);
        params.put("startId", 0L);
        params.put("offset", 0);
        params.put("pageSize", 20);
        // the four C8 chain filters — all null by default (the shape-invariance arm)
        params.put("channel", null);
        params.put("ticketId", null);
        params.put("releaseId", null);
        params.put("sqlFingerprint", null);
        return params;
    }

    private static String boundSql(Configuration config, String statementId, Map<String, Object> params) {
        MappedStatement ms = config.getMappedStatement(
            "com.clougence.clouddm.platform.dal.mapper.monitor.DmMonOpAuditMapper." + statementId);
        return ms.getBoundSql(params).getSql().replaceAll("\\s+", " ");
    }

    @Test
    public void operationAuditCondition_allChainParamsNull_sqlShapeUnchanged() throws Exception {
        Configuration config = loadOpAuditMapperConfig();
        for (String statement : new String[] { "pageByCondition", "countByCondition", "queryByCondition" }) {
            String sql = boundSql(config, statement, predicateParams());
            assertFalse(statement + " must not render chain predicates when all four params are null: " + sql,
                sql.contains("channel =") || sql.contains("ticket_id =")
                    || sql.contains("release_id =") || sql.contains("sql_fingerprint ="));
            assertTrue(statement + " keeps the mandatory tenant predicate", sql.contains("owner_uid = ?"));
        }
    }

    @Test
    public void operationAuditCondition_chainParams_renderAdditivePredicates() throws Exception {
        Configuration config = loadOpAuditMapperConfig();
        Map<String, Object> params = predicateParams();
        params.put("channel", "SYSTEM_AUTO");
        params.put("ticketId", 42L);
        params.put("releaseId", 7L);
        params.put("sqlFingerprint", "abc123");
        String sql = boundSql(config, "pageByCondition", params);
        assertTrue(sql.contains("AND channel = ?"));
        assertTrue(sql.contains("AND ticket_id = ?"));
        assertTrue(sql.contains("AND release_id = ?"));
        assertTrue(sql.contains("AND sql_fingerprint = ?"));
        // the four new columns ride the select list (VO/export consumers)
        assertTrue(sql.contains("sql_fingerprint") && sql.contains("ticket_id")
            && sql.contains("release_id") && sql.contains("channel"));
    }

    // ==================== the interface surface: the legacy signatures still exist (red line 1) ====================

    @Test
    public void interface_legacyOverloadsUntouched_newOnesAdditive() throws Exception {
        // the 9-arg and 10-arg legacy shapes are declared verbatim (compile-time guarantee for the
        // ~59 existing call sites; this reflection pin documents the red line)
        assertNotNull(RdpOpAuditService.class.getMethod("logAndAddOperationAudit", String.class, String.class,
            String.class, String.class, Object.class, Object.class, SecurityLevel.class, AuditType.class,
            ResourceType.class));
        assertNotNull(RdpOpAuditService.class.getMethod("logAndAddOperationAudit", String.class, String.class,
            String.class, String.class, Object.class, Object.class, SecurityLevel.class, AuditType.class,
            ResourceType.class, String.class));
        // the two additive chain overloads
        assertNotNull(RdpOpAuditService.class.getMethod("logAndAddOperationAudit", String.class, String.class,
            String.class, String.class, Object.class, Object.class, SecurityLevel.class, AuditType.class,
            ResourceType.class, AuditChainMO.class));
        assertNotNull(RdpOpAuditService.class.getMethod("logAndAddOperationAudit", String.class, String.class,
            String.class, String.class, Object.class, Object.class, SecurityLevel.class, AuditType.class,
            ResourceType.class, String.class, AuditChainMO.class));
    }
}
