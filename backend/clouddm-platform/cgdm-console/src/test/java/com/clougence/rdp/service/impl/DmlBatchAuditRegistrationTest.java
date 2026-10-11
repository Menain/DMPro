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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.console.web.global.i18n.I18nDmMsgKeys;
import com.clougence.clouddm.platform.dal.access.DataSourceDal;
import com.clougence.clouddm.platform.dal.mapper.datasource.DmDsMapper;
import com.clougence.clouddm.platform.dal.model.ResourceType;
import com.clougence.clouddm.platform.dal.model.datasource.DmDsDO;
import com.clougence.clouddm.platform.dal.model.monitor.AuditType;

/**
 * B7 N2 (implement.md 单测矩阵「四点注册齐备 + label 三语文案同步核查」): the audit/label
 * registration pins of the governance-contracts four-point audit contract —
 * <ol>
 * <li>enum: AuditType.DML_BATCH_REJECT exists (next to the N1b DML_BATCH_EXECUTE);</li>
 * <li>RdpOpAuditServiceImpl.init whitelist: BOTH DML batch types are registered (a missing entry
 *     = the audit page filter silently drops the rows);</li>
 * <li>getResourceName DATASOURCE case: a dsId-shaped resId resolves the instance name (the
 *     REJECT row's resId = dsId rides the EXISTING case — no new branch needed, pinned anyway);</li>
 * <li>label 三语: DML_BATCH_REJECT + DML_BATCH_EXECUTE keys exist in all three label*.properties.</li>
 * </ol>
 * Plus the N1b-check handover #4 pin: the DM_GHOST_EXEC label copy was updated ADDITIVELY in all
 * THREE dm-auth-label properties (裁决 #15 reuse — the key name / @AuthLabel / authorization
 * relations untouched, only the display copy widened DDL → DDL/DML, three languages in sync),
 * and the 19 new N2 message keys exist in all three msg*.properties (三语同键纪律).
 * <p>
 * Discipline: JUnit4 + hand-written mocks + ReflectionTestUtils; properties are read from the
 * test classpath (console resources + the cgdm-plugin-sdk dependency jar).
 */
public class DmlBatchAuditRegistrationTest {

    // ==================== point 1: enum ====================

    @Test
    public void auditType_dmlBatchReject_enumExists() {
        assertEquals(AuditType.DML_BATCH_REJECT, AuditType.valueOf("DML_BATCH_REJECT"));
        assertEquals(AuditType.DML_BATCH_EXECUTE, AuditType.valueOf("DML_BATCH_EXECUTE"));
    }

    // ==================== point 2: init whitelist ====================

    @Test
    @SuppressWarnings("unchecked")
    public void initWhitelist_containsBothDmlBatchTypes() {
        RdpOpAuditServiceImpl impl = new RdpOpAuditServiceImpl();
        ReflectionTestUtils.invokeMethod(impl, "init");
        List<AuditType> auditTypes = (List<AuditType>) ReflectionTestUtils.getField(impl, "auditTypes");
        assertNotNull(auditTypes);
        assertTrue("DML_BATCH_REJECT must be whitelisted (missing = audit page silently drops it)",
            auditTypes.contains(AuditType.DML_BATCH_REJECT));
        assertTrue(auditTypes.contains(AuditType.DML_BATCH_EXECUTE));
        // the B6 ghost family stays registered (additive discipline regression guard)
        assertTrue(auditTypes.contains(AuditType.GHOST_EXECUTE));
    }

    // ==================== point 3: getResourceName DATASOURCE case ====================

    @Test
    public void getResourceName_datasourceCase_resolvesInstanceIdForDsId() {
        RdpOpAuditServiceImpl impl = new RdpOpAuditServiceImpl();
        DataSourceDal datasourceDal = mock(DataSourceDal.class);
        DmDsMapper dsMapper = mock(DmDsMapper.class);
        when(datasourceDal.dsMapper()).thenReturn(dsMapper);
        DmDsDO ds = new DmDsDO();
        ds.setId(20L);
        ds.setInstanceId("rm-mock-instance");
        when(dsMapper.queryDsIdentityById(anyLong())).thenReturn(ds);
        ReflectionTestUtils.setField(impl, "datasourceDal", datasourceDal);

        String name = ReflectionTestUtils.invokeMethod(impl, "getResourceName", 20L, ResourceType.DATASOURCE);

        assertEquals("rm-mock-instance", name);
    }

    // ==================== point 4: label 三语 (audit type labels) ====================

    @Test
    public void auditLabels_threeLanguages_carryBothDmlBatchKeys() throws Exception {
        for (String file : new String[] { "rdp/static/i18n/label.properties",
                                          "rdp/static/i18n/label_en_US.properties",
                                          "rdp/static/i18n/label_zh_CN.properties" }) {
            Properties props = loadUtf8(file);
            assertNotNull(file + " must carry DML_BATCH_EXECUTE", props.getProperty("DML_BATCH_EXECUTE"));
            assertNotNull(file + " must carry DML_BATCH_REJECT", props.getProperty("DML_BATCH_REJECT"));
        }
        // the copy itself: zh vs en/default (same-key-different-language, values pinned)
        assertEquals("拒绝无锁数据变更提交", loadUtf8("rdp/static/i18n/label_zh_CN.properties")
            .getProperty("DML_BATCH_REJECT"));
        assertEquals("Lock-free DML Batch Reject", loadUtf8("rdp/static/i18n/label_en_US.properties")
            .getProperty("DML_BATCH_REJECT"));
        assertEquals("Lock-free DML Batch Reject", loadUtf8("rdp/static/i18n/label.properties")
            .getProperty("DML_BATCH_REJECT"));
    }

    // ==================== handover #4: DM_GHOST_EXEC label copy, three files in sync ====================

    @Test
    public void dmGhostExecLabel_widenedToDdlDml_threeFilesInSync() throws Exception {
        // 裁决 #15 connected obligation: ONLY the display copy changed — the key name
        // (AUTH_KEY_DM_GHOST_EXEC), the @AuthLabel registration face and every authorization
        // relation are untouched (the key-existence assertion IS the zero-touch proof)
        String zh = loadUtf8("META-INF/rdp/sdk/i18n/dm-auth-label_zh_CN.properties")
            .getProperty("AUTH_KEY_DM_GHOST_EXEC");
        String en = loadUtf8("META-INF/rdp/sdk/i18n/dm-auth-label_en_US.properties")
            .getProperty("AUTH_KEY_DM_GHOST_EXEC");
        String def = loadUtf8("META-INF/rdp/sdk/i18n/dm-auth-label.properties")
            .getProperty("AUTH_KEY_DM_GHOST_EXEC");
        assertEquals("无锁变更执行(DDL/DML,包含:空间占用查看权限)", zh);
        assertEquals("Lock-free Change Execute (DDL/DML, Include: Space Usage View)", en);
        assertEquals("en and default must stay in sync", en, def);
    }

    // ==================== N2 msg keys, three languages in sync ====================

    @Test
    public void n2MessageKeys_existInAllThreeMsgBundles() throws Exception {
        String[] files = { "i18n/msg.properties", "i18n/msg_en_US.properties", "i18n/msg_zh_CN.properties" };
        I18nDmMsgKeys[] n2Keys = {
            I18nDmMsgKeys.DML_BATCH_TASK_NOT_FOUND,
            I18nDmMsgKeys.DML_BATCH_TASK_STATE_INVALID,
            I18nDmMsgKeys.DML_BATCH_TASK_REMOTE_RUNNING,
            I18nDmMsgKeys.DML_BATCH_SEGMENT_NOT_EXECUTING,
            I18nDmMsgKeys.DML_BATCH_TASK_DOCK_OP_FORBIDDEN,
            I18nDmMsgKeys.GOV_TICKET_V2_DML_BATCH_RESOLUTION_REQUIRED,
            I18nDmMsgKeys.DML_BATCH_LAG_UNKNOWN,
            I18nDmMsgKeys.ALERT_PUSH_DML_NO_KEY_TITLE,
            I18nDmMsgKeys.ALERT_PUSH_DML_NO_KEY_APPLICANT,
            I18nDmMsgKeys.ALERT_PUSH_DML_NO_KEY_TABLE,
            I18nDmMsgKeys.ALERT_PUSH_DML_NO_KEY_STATS,
            I18nDmMsgKeys.ALERT_PUSH_DML_NO_KEY_VIOLATION,
            I18nDmMsgKeys.ALERT_PUSH_DML_NO_KEY_SQL,
            I18nDmMsgKeys.ALERT_PUSH_DML_UNRESOLVED_TITLE,
            I18nDmMsgKeys.ALERT_PUSH_DML_UNRESOLVED_APPLICANT,
            I18nDmMsgKeys.ALERT_PUSH_DML_UNRESOLVED_TABLE,
            I18nDmMsgKeys.ALERT_PUSH_DML_UNRESOLVED_BATCH,
            I18nDmMsgKeys.ALERT_PUSH_DML_UNRESOLVED_ACTION,
            I18nDmMsgKeys.ALERT_PUSH_DML_UNRESOLVED_LINK,
        };
        for (String file : files) {
            Properties props = loadUtf8(file);
            for (I18nDmMsgKeys key : n2Keys) {
                String value = props.getProperty(key.name());
                assertNotNull(file + " must carry " + key.name(), value);
                assertFalseBlank(value, file + " " + key.name());
            }
        }
        // the en bundle must not carry MessageFormat-hostile apostrophes (两路径撇号纪律,
        // ops-diagnostics §6: an unescaped ' swallows the {0} placeholders)
        Properties en = loadUtf8(files[1]);
        for (I18nDmMsgKeys key : n2Keys) {
            String value = en.getProperty(key.name());
            assertTrue("en copy of " + key.name() + " must avoid raw apostrophes",
                !value.contains("'"));
        }
    }

    // ==================== ADR-011: schema-gate override registration pins ====================
    // (task 10-11-schema-gate-fail-closed — same registration-discipline character as the B7
    // pins above: enum + whitelist + label三语 + ACTUAL WIRING, heeding the PROD_PROMOTE
    // "defined but never enforced" precedent)

    @Test
    @SuppressWarnings("unchecked")
    public void adr011_auditTypes_enumAndInitWhitelistRegistered() {
        assertEquals(AuditType.GOV_SCHEMA_GATE_OVERRIDE_GRANT, AuditType.valueOf("GOV_SCHEMA_GATE_OVERRIDE_GRANT"));
        assertEquals(AuditType.GOV_SCHEMA_GATE_OVERRIDE_REVOKE, AuditType.valueOf("GOV_SCHEMA_GATE_OVERRIDE_REVOKE"));

        RdpOpAuditServiceImpl impl = new RdpOpAuditServiceImpl();
        ReflectionTestUtils.invokeMethod(impl, "init");
        List<AuditType> auditTypes = (List<AuditType>) ReflectionTestUtils.getField(impl, "auditTypes");
        assertNotNull(auditTypes);
        assertTrue("GOV_SCHEMA_GATE_OVERRIDE_GRANT must be whitelisted (missing = audit page silently drops it)",
            auditTypes.contains(AuditType.GOV_SCHEMA_GATE_OVERRIDE_GRANT));
        assertTrue("GOV_SCHEMA_GATE_OVERRIDE_REVOKE must be whitelisted",
            auditTypes.contains(AuditType.GOV_SCHEMA_GATE_OVERRIDE_REVOKE));
    }

    @Test
    public void adr011_auditTypeLabels_threeLanguagesInSync() throws Exception {
        for (String file : new String[] { "rdp/static/i18n/label.properties",
                                          "rdp/static/i18n/label_en_US.properties",
                                          "rdp/static/i18n/label_zh_CN.properties" }) {
            Properties props = loadUtf8(file);
            assertNotNull(file + " must carry GOV_SCHEMA_GATE_OVERRIDE_GRANT",
                props.getProperty("GOV_SCHEMA_GATE_OVERRIDE_GRANT"));
            assertNotNull(file + " must carry GOV_SCHEMA_GATE_OVERRIDE_REVOKE",
                props.getProperty("GOV_SCHEMA_GATE_OVERRIDE_REVOKE"));
        }
    }

    @Test
    public void adr011_roleAuthLabel_dbaTaggedAndThreeFileI18n() throws Exception {
        // Constant + @AuthLabel registration face: tag=DBA → the GlobalAuthInfoSpi reflection
        // scan puts it into the DBA system-role default set automatically (0 extra code);
        // an EMPTY tag would silently grant it to ALL inner roles — pinned non-empty here.
        assertEquals("GOV_SCHEMA_GATE_OVERRIDE",
            com.clougence.clouddm.sdk.security.auth.def.SecRoleAuthLabel.GOV_SCHEMA_GATE_OVERRIDE);
        java.lang.reflect.Field field = com.clougence.clouddm.sdk.security.auth.def.SecRoleAuthLabel.class
            .getField("GOV_SCHEMA_GATE_OVERRIDE");
        com.clougence.clouddm.sdk.security.auth.AuthLabel ann = field
            .getAnnotation(com.clougence.clouddm.sdk.security.auth.AuthLabel.class);
        assertNotNull("GOV_SCHEMA_GATE_OVERRIDE must carry @AuthLabel", ann);
        assertEquals(com.clougence.clouddm.sdk.security.auth.def.SecAuthCategory.CAT_RDP_DB_CHANGE_GOVERN,
            ann.category());
        assertEquals(com.clougence.clouddm.sdk.security.auth.def.SecAuthI18nKeys.AUTH_KEY_GOV_SCHEMA_GATE_OVERRIDE,
            ann.i18nKey());
        assertTrue("tag must be non-empty and carry DBA (empty tag = granted to ALL inner roles)",
            List.of(ann.tag()).contains(com.clougence.clouddm.sdk.security.auth.def.SecSysRole.DBA_ROLE_NAME));

        // 三文件同步硬约定 (missing entry = startup log.error noise + raw key shown in UI)
        for (String file : new String[] { "META-INF/rdp/sdk/i18n/rdp-auth-label.properties",
                                          "META-INF/rdp/sdk/i18n/rdp-auth-label_zh_CN.properties",
                                          "META-INF/rdp/sdk/i18n/rdp-auth-label_en_US.properties" }) {
            Properties props = loadUtf8(file);
            String value = props.getProperty("AUTH_KEY_GOV_SCHEMA_GATE_OVERRIDE");
            assertNotNull(file + " must carry AUTH_KEY_GOV_SCHEMA_GATE_OVERRIDE", value);
            assertFalseBlank(value, file + " AUTH_KEY_GOV_SCHEMA_GATE_OVERRIDE");
        }
    }

    @Test
    public void adr011_overrideEndpoints_actuallyWiredWithLabel() throws Exception {
        // A5 接线 pin: ALL THREE override endpoints carry @RequestAuth(GOV_SCHEMA_GATE_OVERRIDE)
        // (RefRoleSet default strategy) — a grant without the label 406s in JwtManager.testAuth
        // for sub-accounts (primary/maintainer short-circuit is the platform-wide convention).
        for (String name : new String[] { "overrideGrant", "overrideList", "overrideRevoke" }) {
            java.lang.reflect.Method method = findControllerMethod(name);
            com.clougence.clouddm.console.web.global.jwtsession.RequestAuth auth = method
                .getAnnotation(com.clougence.clouddm.console.web.global.jwtsession.RequestAuth.class);
            assertNotNull(name + " must carry @RequestAuth", auth);
            assertTrue(name + " must enforce GOV_SCHEMA_GATE_OVERRIDE",
                List.of(auth.value()).contains(
                    com.clougence.clouddm.sdk.security.auth.def.SecRoleAuthLabel.GOV_SCHEMA_GATE_OVERRIDE));
        }
    }

    @Test
    public void adr011_failClosedSwitches_registeredInRootUserConfig() throws Exception {
        // M6/A6 pin: both formerly-hidden switches are @UserConfigDef-registered (whitelist
        // admission for upsertUserConfigs → UI + UPDATE_SYSTEM_CONFIG HIGH audit channel),
        // default true = fail-closed, BOOLEAN web op.
        assertSwitchDef("govReleaseSchemaPrecheckBlock");
        assertSwitchDef("govReleaseSchemaDiffBlock");
    }

    private static void assertSwitchDef(String fieldName) throws Exception {
        java.lang.reflect.Field field = com.clougence.clouddm.console.web.component.config.RootUserConfig.class
            .getDeclaredField(fieldName);
        com.clougence.clouddm.console.web.component.config.UserConfigDef def = field
            .getAnnotation(com.clougence.clouddm.console.web.component.config.UserConfigDef.class);
        assertNotNull(fieldName + " must be registered via @UserConfigDef", def);
        assertEquals(fieldName, def.name());
        assertEquals("fail-closed default must stay true", "true", def.defaultValue());
    }

    private static java.lang.reflect.Method findControllerMethod(String name) {
        for (java.lang.reflect.Method m : com.clougence.rdp.controller.GovReleaseController.class
            .getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        throw new AssertionError("GovReleaseController." + name + " endpoint missing");
    }

    private static void assertFalseBlank(String value, String what) {
        assertTrue(what + " must not be blank", value != null && !value.trim().isEmpty());
    }

    private static Properties loadUtf8(String classpathLocation) throws Exception {
        InputStream in = DmlBatchAuditRegistrationTest.class.getClassLoader().getResourceAsStream(classpathLocation);
        assertNotNull("classpath resource missing: " + classpathLocation, in);
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            Properties props = new Properties();
            props.load(reader);
            return props;
        }
    }
}
