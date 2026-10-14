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

    // ==================== C8 M3: whitelist/label reconciliation extension ====================
    // (task 10-13-audit-chain-enhancement — the survey A2/C12 census: PURE_URL is the only
    // ResourceType and UPDATE_ACCOUNT_USER_NAME the only AuditType with LIVE write points missing
    // from the init() whitelist; the 22 DATA_JOB + 4 LOGICAL_DB dead types deliberately stay OUT
    // — 主会话裁量 b, no rows to filter on an internal deployment)

    @Test
    @SuppressWarnings("unchecked")
    public void c8_pureUrl_whitelisted_withoutGetResourceNameCase_deadTypesStayOut() {
        RdpOpAuditServiceImpl impl = new RdpOpAuditServiceImpl();
        ReflectionTestUtils.invokeMethod(impl, "init");
        List<ResourceType> resourceTypes = (List<ResourceType>) ReflectionTestUtils.getField(impl, "resourceTypes");
        assertNotNull(resourceTypes);
        assertTrue("PURE_URL must be filterable (the C4/C7 override HIGH rows were visible-but-unfilterable)",
            resourceTypes.contains(ResourceType.PURE_URL));

        // red line 2 (resId-PK contract): PURE_URL gets NO getResourceName case — every PURE_URL
        // write point uses the explicit-resourceName overload (the 10-arg bypass); a case would
        // make the default-throw contract lie and invite the Phase-2/Phase-5 defect class back
        try {
            ReflectionTestUtils.invokeMethod(impl, "getResourceName", 900L, ResourceType.PURE_URL);
            org.junit.Assert.fail("PURE_URL must NOT have a getResourceName case");
        } catch (UnsupportedOperationException expected) {
            assertTrue(expected.getMessage().contains("PURE_URL"));
        }

        // dead types stay out of the dropdown (裁量 b pin)
        assertTrue("TICKET has zero live write points", !resourceTypes.contains(ResourceType.TICKET));
        assertTrue("LOGICAL_DB is a historical-deserialization tolerance enum",
            !resourceTypes.contains(ResourceType.LOGICAL_DB));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void c8_updateAccountUserName_whitelisted_deadAuditTypesStayOut() {
        RdpOpAuditServiceImpl impl = new RdpOpAuditServiceImpl();
        ReflectionTestUtils.invokeMethod(impl, "init");
        List<AuditType> auditTypes = (List<AuditType>) ReflectionTestUtils.getField(impl, "auditTypes");
        assertNotNull(auditTypes);
        assertTrue("UPDATE_ACCOUNT_USER_NAME has a live write point (RdpUserController.updateUserName)",
            auditTypes.contains(AuditType.UPDATE_ACCOUNT_USER_NAME));
        // the existing registrations stay (additive discipline regression guard)
        assertTrue(auditTypes.contains(AuditType.UPDATE_ACCOUNT_PHONE));
        // dead types stay out (22 DATA_JOB + 4 LOGICAL_DB representatives pinned)
        assertTrue(!auditTypes.contains(AuditType.QUERY_JOB_INFO));
        assertTrue(!auditTypes.contains(AuditType.CREATE_LOGICAL_DB));
        assertTrue(!auditTypes.contains(AuditType.SET_LOGICAL_DB_BINDING));
    }

    @Test
    public void c8_labels_threeLanguages_carryTheActiveGapKeys() throws Exception {
        // the C12 label census: PERM_GROUP family 9 + RESET_ACCOUNT_AK_SK + UPDATE_ACCOUNT_USER_NAME
        // (AuditType side) + PURE_URL/PERM_GROUP (ResourceType side). The dead-type labels
        // (DATA_JOB 22 / LOGICAL_DB 4 / governance legacy 4) are deliberately NOT added (无行可显).
        String[] keys = { "CREATE_PERM_GROUP", "UPDATE_PERM_GROUP", "DELETE_PERM_GROUP",
                          "ENABLE_PERM_GROUP", "DISABLE_PERM_GROUP", "ADD_PERM_GROUP_MEMBER",
                          "REMOVE_PERM_GROUP_MEMBER", "GRANT_PERM_GROUP_RESOURCE", "REVOKE_PERM_GROUP_RESOURCE",
                          "RESET_ACCOUNT_AK_SK", "UPDATE_ACCOUNT_USER_NAME",
                          "PURE_URL", "PERM_GROUP" };
        for (String file : new String[] { "rdp/static/i18n/label.properties",
                                          "rdp/static/i18n/label_en_US.properties",
                                          "rdp/static/i18n/label_zh_CN.properties" }) {
            Properties props = loadUtf8(file);
            for (String key : keys) {
                String value = props.getProperty(key);
                assertNotNull(file + " must carry " + key, value);
                assertFalseBlank(value, file + " " + key);
            }
        }
        // the zh copy itself (same-key-different-language values pinned)
        Properties zh = loadUtf8("rdp/static/i18n/label_zh_CN.properties");
        assertEquals("权限组", zh.getProperty("PERM_GROUP"));
        assertEquals("更新账户用户名", zh.getProperty("UPDATE_ACCOUNT_USER_NAME"));
    }

    // ==================== C10: ROLLBACK_MATERIAL four-point registration pins ====================
    // (task 10-14-recovery-type-grading, design D7 — the rollback-material trail: enum + init
    // whitelist + PURE_URL oldName bypass ("ticket:<id>", NO getResourceName case — the C4/C7/C8
    // precedent) + label 三语. A missing point = silent dropdown/filter degradation.)

    @Test
    public void c10_rollbackMaterial_point1_enumExists() {
        assertEquals(AuditType.ROLLBACK_MATERIAL, AuditType.valueOf("ROLLBACK_MATERIAL"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void c10_rollbackMaterial_point2_initWhitelist_andPoint3_pureUrlBypass() {
        RdpOpAuditServiceImpl impl = new RdpOpAuditServiceImpl();
        ReflectionTestUtils.invokeMethod(impl, "init");
        List<AuditType> auditTypes = (List<AuditType>) ReflectionTestUtils.getField(impl, "auditTypes");
        assertNotNull(auditTypes);
        assertTrue("ROLLBACK_MATERIAL must be whitelisted (missing = the audit page filter drops the rows)",
            auditTypes.contains(AuditType.ROLLBACK_MATERIAL));
        // the bypass resType stays filterable (C8 M3 registration — the write points ride it)
        List<ResourceType> resourceTypes = (List<ResourceType>) ReflectionTestUtils.getField(impl, "resourceTypes");
        assertTrue(resourceTypes.contains(ResourceType.PURE_URL));
        // point 3 is the DELIBERATE absence: PURE_URL has no getResourceName case — the write
        // points must use the explicit-resourceName overload ("ticket:<id>"); a case here would
        // violate the resId-must-be-the-ResourceType's-own-PK contract (red line 2 of C8 M3)
        try {
            ReflectionTestUtils.invokeMethod(impl, "getResourceName", 900L, ResourceType.PURE_URL);
            org.junit.Assert.fail("PURE_URL must NOT have a getResourceName case (C10 rides the bypass)");
        } catch (UnsupportedOperationException expected) {
            assertTrue(expected.getMessage().contains("PURE_URL"));
        }
    }

    @Test
    public void c10_rollbackMaterial_point4_labelsThreeLanguages() throws Exception {
        for (String file : new String[] { "rdp/static/i18n/label.properties",
                                          "rdp/static/i18n/label_en_US.properties",
                                          "rdp/static/i18n/label_zh_CN.properties" }) {
            Properties props = loadUtf8(file);
            String value = props.getProperty("ROLLBACK_MATERIAL");
            assertNotNull(file + " must carry ROLLBACK_MATERIAL", value);
            assertFalseBlank(value, file + " ROLLBACK_MATERIAL");
        }
        // the copy itself (same-key-different-language values pinned)
        assertEquals("回滚材料变更", loadUtf8("rdp/static/i18n/label_zh_CN.properties")
            .getProperty("ROLLBACK_MATERIAL"));
        assertEquals("Rollback Material Change", loadUtf8("rdp/static/i18n/label_en_US.properties")
            .getProperty("ROLLBACK_MATERIAL"));
        assertEquals("en and default must stay in sync",
            loadUtf8("rdp/static/i18n/label_en_US.properties").getProperty("ROLLBACK_MATERIAL"),
            loadUtf8("rdp/static/i18n/label.properties").getProperty("ROLLBACK_MATERIAL"));
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
