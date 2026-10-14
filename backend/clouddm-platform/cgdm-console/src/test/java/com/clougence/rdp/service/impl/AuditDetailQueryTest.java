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
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.console.web.model.vo.AuditDetailVO;
import com.clougence.clouddm.console.web.util.AuditSanitizeUtils;
import com.clougence.clouddm.platform.dal.access.MonitorDal;
import com.clougence.clouddm.platform.dal.mapper.monitor.DmMonOpAuditMapper;
import com.clougence.clouddm.platform.dal.model.monitor.AuditType;
import com.clougence.clouddm.platform.dal.model.monitor.DmMonOpAuditDO;
import com.clougence.clouddm.platform.dal.model.ResourceType;
import com.clougence.clouddm.sdk.security.auth.def.SecRoleAuthLabel;

/**
 * C8 M3 test class (task 10-13-audit-chain-enhancement, design D8 — one of the three authorized
 * new classes): the audit-detail endpoint's service face (裁决 3 — the /logview/grep/operationlog
 * dead-endpoint replacement, survey D-2).
 * <p>
 * Pins: tenant isolation (id + owner_uid — a cross-tenant/missing id reads as not-found), the
 * AuditSanitizeUtils sanitization兜底 over the returned log_info (pre-C1 stock rows may carry
 * plaintext credentials — the raw value must NEVER appear in the response, C1 red line), the
 * unparsable-body pass-through (a 65535-truncated tail never breaks the read), and the endpoint
 * wiring (@RequestAuth(RDP_OP_AUDIT_READ) on the GET /detail method — the permission-denial face
 * is the shared JwtManager label check, pinned at the annotation like the ADR-011 endpoints test).
 * <p>
 * Discipline: JUnit4 + hand-written mocks + ReflectionTestUtils — no Spring context.
 */
public class AuditDetailQueryTest {

    private RdpOpAuditServiceImpl service;
    private DmMonOpAuditMapper    opAuditMapper;

    private static final String PUID = "puid-1";
    private static final long   ID   = 77L;

    @Before
    public void setUp() {
        service = new RdpOpAuditServiceImpl();
        MonitorDal monitorDal = mock(MonitorDal.class);
        opAuditMapper = mock(DmMonOpAuditMapper.class);
        when(monitorDal.opAuditMapper()).thenReturn(opAuditMapper);
        ReflectionTestUtils.setField(service, "monitorDal", monitorDal);
    }

    private static DmMonOpAuditDO row(String ownerUid, String logInfo) {
        DmMonOpAuditDO auditDO = new DmMonOpAuditDO();
        auditDO.setId(ID);
        auditDO.setOwnerUid(ownerUid);
        auditDO.setUid("uid-1");
        auditDO.setAuditType(AuditType.UPDATE_DATA_SOURCE_CONFIG);
        auditDO.setResourceType(ResourceType.DATASOURCE);
        auditDO.setOperationUri("/api/entry/datasource/updateDs");
        auditDO.setLogInfo(logInfo);
        return auditDO;
    }

    // ==================== happy path: sanitized body ====================

    @Test
    public void detail_masksCredentialsKeepsTroubleshootingFields() {
        // a pre-C1 style UPDATE_DATA_SOURCE_CONFIG body: nested configMap with secret keys +
        // non-sensitive identity fields + old/new style keys
        String logInfo = "{\"dsId\":5,\"instanceDesc\":\"prod-mysql\",\"configMap\":{"
            + "\"userName\":\"root\",\"password\":\"plaintext-secret-value\","
            + "\"sslCaPassword\":\"another-secret\",\"host\":\"10.0.0.8\"},"
            + "\"oldInstanceDesc\":\"old-desc\"}";
        when(opAuditMapper.selectById(ID)).thenReturn(row(PUID, logInfo));

        AuditDetailVO vo = service.queryAuditDetail(PUID, ID);

        assertEquals(Long.valueOf(ID), vo.getId());
        assertEquals("/api/entry/datasource/updateDs", vo.getPath());
        assertNotNull("the desc line comes from the RDP_CONSOLE_OPERATION_LOG_DESC i18n key", vo.getDesc());
        String content = vo.getContent();
        assertNotNull(content);
        // the credential values are masked and NEVER appear in the response (C1 red line)
        assertFalse(content.contains("plaintext-secret-value"));
        assertFalse(content.contains("another-secret"));
        assertTrue(content.contains(AuditSanitizeUtils.CREDENTIAL_MASK));
        // non-sensitive fields survive (troubleshooting value kept)
        assertTrue(content.contains("prod-mysql"));
        assertTrue(content.contains("10.0.0.8"));
        assertTrue(content.contains("old-desc"));
    }

    // ==================== tenant isolation ====================

    @Test
    public void detail_crossTenantId_readsAsNotFound() {
        when(opAuditMapper.selectById(ID)).thenReturn(row("some-other-tenant", "{}"));
        try {
            service.queryAuditDetail(PUID, ID);
            fail("a cross-tenant id must read as not-found");
        } catch (ErrorMessageException expected) {
            assertTrue(expected.getMessage().contains("not found"));
        }
    }

    @Test
    public void detail_missingId_readsAsNotFound() {
        when(opAuditMapper.selectById(anyLong())).thenReturn(null);
        try {
            service.queryAuditDetail(PUID, ID);
            fail("a missing id must read as not-found");
        } catch (ErrorMessageException expected) {
            assertTrue(expected.getMessage().contains("not found"));
        }
    }

    // ==================== body edge shapes ====================

    @Test
    public void detail_unparsableBody_passesThroughUnchanged() {
        // the 65535-truncation tail shape: a JSON prefix ending in "..." — never throws, the
        // modal renders the raw text (its isParseError branch)
        String truncated = "{\"a\":1,\"b\":\"long-value...";
        when(opAuditMapper.selectById(ID)).thenReturn(row(PUID, truncated));

        AuditDetailVO vo = service.queryAuditDetail(PUID, ID);

        assertEquals(truncated, vo.getContent());
    }

    @Test
    public void detail_blankBody_contentStaysNull() {
        when(opAuditMapper.selectById(ID)).thenReturn(row(PUID, null));
        assertNull(service.queryAuditDetail(PUID, ID).getContent());
    }

    // ==================== sanitizeLogInfoJson unit face ====================

    @Test
    public void sanitizeLogInfoJson_recursesListsAndNestedMaps_blankKept_nonStringMasked() {
        String json = "{\"entries\":[{\"password\":\"p1\",\"note\":\"keep\"}],"
            + "\"nested\":{\"deep\":{\"accessKeySecret\":\"s1\"}},"
            + "\"emptyPwd\":\"\",\"objSecret\":{\"k\":\"v\"}}";

        String out = AuditSanitizeUtils.sanitizeLogInfoJson(json);

        assertFalse(out.contains("p1"));
        assertFalse(out.contains("s1"));
        assertTrue("list-element and nested-map keys are masked at any depth",
            out.contains(AuditSanitizeUtils.CREDENTIAL_MASK));
        assertTrue("non-sensitive values survive", out.contains("keep"));
        assertTrue("a blank credential stays blank (not-provided stays truthful)", out.contains("\"emptyPwd\":\"\""));
        assertTrue("a non-String value under a sensitive key is fully masked (fail-closed)",
            !out.contains("\"k\":\"v\""));
    }

    @Test
    public void sanitizeLogInfoJson_neverThrowsOnGarbage() {
        assertEquals("not json at all", AuditSanitizeUtils.sanitizeLogInfoJson("not json at all"));
        assertNull(AuditSanitizeUtils.sanitizeLogInfoJson(null));
        assertEquals("", AuditSanitizeUtils.sanitizeLogInfoJson(""));
    }

    // ==================== endpoint wiring (the permission face) ====================

    @Test
    public void detailEndpoint_isGetAndCarriesReadAuth() throws Exception {
        java.lang.reflect.Method method = null;
        for (java.lang.reflect.Method m : com.clougence.rdp.controller.RdpOpAuditController.class.getDeclaredMethods()) {
            if (m.getName().equals("detail")) {
                method = m;
            }
        }
        assertNotNull("the /detail endpoint method must exist", method);
        com.clougence.clouddm.console.web.global.jwtsession.RequestAuth auth = method
            .getAnnotation(com.clougence.clouddm.console.web.global.jwtsession.RequestAuth.class);
        assertNotNull("detail must carry @RequestAuth", auth);
        assertTrue("detail must enforce RDP_OP_AUDIT_READ",
            List.of(auth.value()).contains(SecRoleAuthLabel.RDP_OP_AUDIT_READ));
        org.springframework.web.bind.annotation.RequestMapping mapping = method
            .getAnnotation(org.springframework.web.bind.annotation.RequestMapping.class);
        assertNotNull(mapping);
        assertEquals("/detail", mapping.value()[0]);
        assertEquals(org.springframework.web.bind.annotation.RequestMethod.GET, mapping.method()[0]);
    }
}
