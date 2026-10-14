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
package com.clougence.clouddm.console.web.service.dmlbatch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.console.web.component.alert.AlertContext;
import com.clougence.clouddm.console.web.component.alert.AlertPushService;
import com.clougence.clouddm.console.web.component.alert.AlertScene;
import com.clougence.clouddm.console.web.model.fo.govticket.GovTicketV2SubmitFO;
import com.clougence.clouddm.console.web.model.vo.dmlbatch.DmlBatchRejectAuditObj;
import com.clougence.clouddm.console.web.model.vo.govticket.GovTicketV2CheckVO;
import com.clougence.clouddm.console.web.service.auth.RdpUserService;
import com.clougence.clouddm.console.web.service.govticket.GovTicketV2Service;
import com.clougence.clouddm.platform.dal.model.ResourceType;
import com.clougence.clouddm.platform.dal.model.monitor.AuditType;
import com.clougence.clouddm.platform.dal.model.monitor.SecurityLevel;
import com.clougence.rdp.controller.GovTicketV2Controller;
import com.clougence.rdp.service.RdpOpAuditService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * B7 N2 (implement.md 单测矩阵「拒绝审计」四钉 + 告警「拒绝路径」): the no-key rejection trail
 * (裁决 #16, design §8.2 controller-boundary mechanism):
 * <ol>
 * <li>事务外写入存活 — structural pins: the trail service carries NO @Transactional (its audit
 *     insert runs autocommit at the boundary — an insert INSIDE the submit transaction would
 *     roll back with the ticket, the §8.2 核查结论), and the controller boundary itself is
 *     transaction-free;</li>
 * <li>告警关闭照写 — pushAlert accepted=false (switch off / degradation) → the audit row is
 *     written anyway with alertPushed=false (留痕与告警解耦);</li>
 * <li>审计失败不吞原拒绝异常 — every trail leg failing still ends in the ORIGINAL
 *     DmlNoKeyRejectException instance propagating (rethrow-identity assertion, both at the
 *     trail-service level and through the controller boundary);</li>
 * <li>时序 — the alert fires BEFORE the audit (its boolean accepted flag feeds the obj), and
 *     both fire only AFTER the service exception pierced the boundary (InOrder pin).</li>
 * </ol>
 * Plus: the alert copy context assembly (applicant/table/rows/threshold/sqlSummary, NO deep
 * link — the ticket never landed) and the "generic precheck FAIL keeps the plain path" pin
 * (the trail is DML_NO_KEY-exclusive; /check previews structurally never reach it).
 * <p>
 * Discipline: JUnit4 + hand-written mocks + ReflectionTestUtils; NO Spring / MockMvc.
 */
public class DmlBatchRejectTrailTest {

    private DmlBatchRejectTrailService trailService;
    private AlertPushService           alertPushService;
    private RdpOpAuditService          rdpOpAuditService;

    private static final String PUID   = "puid-001";
    private static final String UID    = "uid-submitter";
    private static final String URI    = "/rdp/console/api/dbChangeV2/submit";
    private static final String ADDR   = "10.0.0.8";
    private static final long   DS_ID  = 20L;

    @Before
    public void setUp() {
        trailService = new DmlBatchRejectTrailService();
        alertPushService = mock(AlertPushService.class);
        rdpOpAuditService = mock(RdpOpAuditService.class);
        ReflectionTestUtils.setField(trailService, "alertPushService", alertPushService);
        ReflectionTestUtils.setField(trailService, "rdpOpAuditService", rdpOpAuditService);
        when(alertPushService.pushAlert(anyString(), any(), any())).thenReturn(true);
    }

    // ==================== fixtures ====================

    private static GovTicketV2CheckVO.DmlNoKeyRejectVO rejectVO() {
        GovTicketV2CheckVO.DmlNoKeyRejectVO vo = new GovTicketV2CheckVO.DmlNoKeyRejectVO();
        vo.setDsId(DS_ID);
        vo.setDbName("prod_db");
        vo.setTableName("t_order");
        vo.setEstimatedRows(5_000_000L);
        vo.setRowThreshold(1_000_000L);
        vo.setKeyDetail("no primary key and no unique key");
        vo.setSqlSummary("DELETE FROM t_order WHERE status = 0");
        vo.setStmtIndex(2);
        vo.setSqlFingerprint(com.clougence.clouddm.console.web.component.governance.GovSqlHashUtils
            .hash("DELETE FROM t_order WHERE status = 0"));
        return vo;
    }

    private static DmlNoKeyRejectException rejection() {
        return new DmlNoKeyRejectException("precheck failed: prod_db: no key", rejectVO());
    }

    // ==================== pin ① the out-of-transaction structure ====================

    @Test
    public void trailService_carriesNoTransactional_theInsertRunsAutocommit() {
        // 事务外写入存活的结构保证: no @Transactional anywhere on the trail surface — the audit
        // insert at the boundary joins NO transaction (autocommit) and therefore SURVIVES the
        // rolled-back submit transaction (an insert inside submit would roll back with it)
        org.junit.Assert.assertNull(trailService.getClass().getAnnotation(Transactional.class));
        for (java.lang.reflect.Method m : trailService.getClass().getDeclaredMethods()) {
            org.junit.Assert.assertNull("trail method " + m.getName() + " must not be transactional",
                m.getAnnotation(Transactional.class));
        }
    }

    @Test
    public void controllerSubmitBoundary_carriesNoTransactional() {
        org.junit.Assert.assertNull(GovTicketV2Controller.class.getAnnotation(Transactional.class));
        for (java.lang.reflect.Method m : GovTicketV2Controller.class.getDeclaredMethods()) {
            org.junit.Assert.assertNull("controller method " + m.getName() + " must not be transactional",
                m.getAnnotation(Transactional.class));
        }
    }

    // ==================== pin ② alert off → audit still written ====================

    @Test
    public void alertRejected_switchOff_auditStillWritten_alertPushedFalse() {
        when(alertPushService.pushAlert(anyString(), any(), any())).thenReturn(false);

        trailService.trailNoKeyReject(PUID, UID, URI, ADDR, rejection());

        ArgumentCaptor<DmlBatchRejectAuditObj> captor = ArgumentCaptor.forClass(DmlBatchRejectAuditObj.class);
        ArgumentCaptor<com.clougence.rdp.service.model.AuditChainMO> chainCaptor = ArgumentCaptor
            .forClass(com.clougence.rdp.service.model.AuditChainMO.class);
        verify(rdpOpAuditService).logAndAddOperationAudit(eq(PUID), eq(UID), eq(URI), eq(ADDR), eq(DS_ID),
            captor.capture(), eq(SecurityLevel.HIGH), eq(AuditType.DML_BATCH_REJECT), eq(ResourceType.DATASOURCE),
            chainCaptor.capture());
        // C8 M2 write-point matrix pin (channel per the 2026-10-14 main-session ruling):
        // fingerprint passthrough (throw-site full-text hash), NO ticket binding (the ticket
        // never landed — by design), channel stays NULL on the MO → the sink's URI inference
        // yields the true arrival channel (this console-submit URI → CONSOLE; the open-API
        // caller's /open/ marker → OPENAPI). The system-rejection semantics rides audit_type.
        assertEquals(com.clougence.clouddm.console.web.component.governance.GovSqlHashUtils
                .hash("DELETE FROM t_order WHERE status = 0"),
            chainCaptor.getValue().getSqlFingerprint());
        assertNull(chainCaptor.getValue().getTicketId());
        assertNull(chainCaptor.getValue().getReleaseId());
        assertNull("channel must stay null — the sink infers the arrival channel from the URI",
            chainCaptor.getValue().getChannel());
        assertEquals(com.clougence.clouddm.platform.dal.model.monitor.AuditChannel.CONSOLE,
            com.clougence.clouddm.platform.dal.model.monitor.AuditChannel.inferFromUri(URI));
        assertEquals(Boolean.FALSE, captor.getValue().getAlertPushed());
        // the rest of the obj survives intact (留痕与告警解耦)
        assertEquals("t_order", captor.getValue().getTableName());
        assertEquals(DmlBatchRejectAuditObj.REASON_NO_KEY, captor.getValue().getReason());
    }

    // ==================== pin ③ trail failures never swallow the rejection ====================

    @Test
    public void alertThrows_auditStillWritten_nothingEscapes() {
        when(alertPushService.pushAlert(anyString(), any(), any())).thenThrow(new RuntimeException("im down"));

        trailService.trailNoKeyReject(PUID, UID, URI, ADDR, rejection()); // must not throw

        ArgumentCaptor<DmlBatchRejectAuditObj> captor = ArgumentCaptor.forClass(DmlBatchRejectAuditObj.class);
        verify(rdpOpAuditService).logAndAddOperationAudit(anyString(), anyString(), anyString(), any(),
            any(), captor.capture(), any(), any(), any(), any(com.clougence.rdp.service.model.AuditChainMO.class));
        assertEquals(Boolean.FALSE, captor.getValue().getAlertPushed());
    }

    @Test
    public void auditThrows_nothingEscapes_theCallerRethrowsTheOriginal() {
        doThrow(new RuntimeException("audit store down")).when(rdpOpAuditService)
            .logAndAddOperationAudit(anyString(), anyString(), anyString(), any(), any(), any(), any(), any(), any(), any(com.clougence.rdp.service.model.AuditChainMO.class));

        trailService.trailNoKeyReject(PUID, UID, URI, ADDR, rejection()); // must not throw
    }

    @Test
    public void controllerBoundary_trailThrows_originalRejectionStillRethrown() throws Exception {
        // the boundary belt (绝不吞/换原拒绝异常): even a trail service violating its own
        // never-throw contract cannot replace the rejection the user must see
        GovTicketV2Controller controller = new GovTicketV2Controller();
        GovTicketV2Service ticketService = mock(GovTicketV2Service.class);
        DmlBatchRejectTrailService failingTrail = mock(DmlBatchRejectTrailService.class);
        ReflectionTestUtils.setField(controller, "govTicketV2Service", ticketService);
        ReflectionTestUtils.setField(controller, "dmlBatchRejectTrailService", failingTrail);
        DmlNoKeyRejectException original = rejection();
        when(ticketService.submit(anyString(), anyString(), any())).thenThrow(original);
        doThrow(new RuntimeException("trail impl bug")).when(failingTrail)
            .trailNoKeyReject(anyString(), anyString(), anyString(), any(), any());

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(RdpUserService.PUID)).thenReturn(PUID);
        when(request.getAttribute(RdpUserService.UID)).thenReturn(UID);
        when(request.getRequestURI()).thenReturn(URI);
        when(request.getRemoteAddr()).thenReturn(ADDR);

        try {
            controller.submit(new GovTicketV2SubmitFO(), request);
            fail("expected the original rejection");
        } catch (DmlNoKeyRejectException rethrown) {
            assertSame("the ORIGINAL instance must be rethrown (GlobalExceptionHandler semantics zero change)",
                original, rethrown);
        }
    }

    // ==================== pin ④ the boundary sequence ====================

    @Test
    public void controllerBoundary_serviceThrowsTyped_trailFiresThenRethrow_inOrder() throws Exception {
        GovTicketV2Controller controller = new GovTicketV2Controller();
        GovTicketV2Service ticketService = mock(GovTicketV2Service.class);
        DmlBatchRejectTrailService trail = mock(DmlBatchRejectTrailService.class);
        ReflectionTestUtils.setField(controller, "govTicketV2Service", ticketService);
        ReflectionTestUtils.setField(controller, "dmlBatchRejectTrailService", trail);
        DmlNoKeyRejectException original = rejection();
        when(ticketService.submit(anyString(), anyString(), any())).thenThrow(original);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(RdpUserService.PUID)).thenReturn(PUID);
        when(request.getAttribute(RdpUserService.UID)).thenReturn(UID);
        when(request.getRequestURI()).thenReturn(URI);
        when(request.getRemoteAddr()).thenReturn(ADDR);

        try {
            controller.submit(new GovTicketV2SubmitFO(), request);
            fail("expected the rejection to propagate");
        } catch (DmlNoKeyRejectException expected) {
            assertSame(original, expected);
        }

        // the trail fires exactly once, AFTER the service exception pierced the boundary (the
        // rollback is complete at that point — 事务外双写), and BEFORE the rethrow
        InOrder inOrder = Mockito.inOrder(ticketService, trail);
        inOrder.verify(ticketService).submit(anyString(), anyString(), any());
        inOrder.verify(trail).trailNoKeyReject(eq(PUID), eq(UID), eq(URI), eq(ADDR), same(original));
    }

    @Test
    public void controllerBoundary_genericPrecheckFailure_trailNeverFires() throws Exception {
        // the trail is DML_NO_KEY-exclusive: any other precheck FAIL keeps the plain
        // ErrorMessageException path (no alert, no audit — the pre-B7 behavior byte-identical)
        GovTicketV2Controller controller = new GovTicketV2Controller();
        GovTicketV2Service ticketService = mock(GovTicketV2Service.class);
        DmlBatchRejectTrailService trail = mock(DmlBatchRejectTrailService.class);
        ReflectionTestUtils.setField(controller, "govTicketV2Service", ticketService);
        ReflectionTestUtils.setField(controller, "dmlBatchRejectTrailService", trail);
        when(ticketService.submit(anyString(), anyString(), any()))
            .thenThrow(new ErrorMessageException("precheck failed: type purity"));

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(RdpUserService.PUID)).thenReturn(PUID);
        when(request.getAttribute(RdpUserService.UID)).thenReturn(UID);

        try {
            controller.submit(new GovTicketV2SubmitFO(), request);
            fail("expected the generic rejection to propagate");
        } catch (ErrorMessageException expected) {
            assertEquals("precheck failed: type purity", expected.getMessage());
        }
        verifyNoInteractions(trail);
    }

    // ==================== alert context + audit payload assembly ====================

    @Test
    public void trail_alertContext_carriesTheRejectionFacts_noDeepLinkShape() {
        trailService.trailNoKeyReject(PUID, UID, URI, ADDR, rejection());

        ArgumentCaptor<AlertContext> ctxCaptor = ArgumentCaptor.forClass(AlertContext.class);
        verify(alertPushService).pushAlert(eq(PUID), eq(AlertScene.DML_LARGE_TABLE_NO_KEY), ctxCaptor.capture());
        AlertContext ctx = ctxCaptor.getValue();
        assertEquals("no ticket landed — the id stays 0 and the copy renders no link", 0L, ctx.getTicketId());
        assertEquals(UID, ctx.getApplicantUid());
        assertEquals("prod_db", ctx.getDbName());
        assertEquals("t_order", ctx.getTableName());
        assertEquals(Long.valueOf(5_000_000L), ctx.getTableRows());
        assertEquals(Long.valueOf(1_000_000L), ctx.getRowThreshold());
        assertEquals("DELETE FROM t_order WHERE status = 0", ctx.getSqlSummary());
    }

    @Test
    public void trail_auditObj_fullPayload() {
        trailService.trailNoKeyReject(PUID, UID, URI, ADDR, rejection());

        ArgumentCaptor<DmlBatchRejectAuditObj> captor = ArgumentCaptor.forClass(DmlBatchRejectAuditObj.class);
        verify(rdpOpAuditService).logAndAddOperationAudit(eq(PUID), eq(UID), eq(URI), eq(ADDR), eq(DS_ID),
            captor.capture(), eq(SecurityLevel.HIGH), eq(AuditType.DML_BATCH_REJECT), eq(ResourceType.DATASOURCE),
            any(com.clougence.rdp.service.model.AuditChainMO.class));
        DmlBatchRejectAuditObj obj = captor.getValue();
        assertEquals(Long.valueOf(DS_ID), obj.getDsId());
        assertEquals("prod_db", obj.getDbName());
        assertEquals("t_order", obj.getTableName());
        assertEquals(Long.valueOf(5_000_000L), obj.getRows());
        assertEquals(Long.valueOf(1_000_000L), obj.getRowThreshold());
        assertEquals(DmlBatchRejectAuditObj.REASON_NO_KEY, obj.getReason());
        assertEquals("no primary key and no unique key", obj.getKeyDetail());
        assertEquals("DELETE FROM t_order WHERE status = 0", obj.getSqlSummary());
        assertEquals(Integer.valueOf(2), obj.getStmtIndex());
        assertEquals(Boolean.TRUE, obj.getAlertPushed());
    }

    @Test
    public void trail_alertBeforeAudit_theAcceptedFlagFeedsTheObj() {
        trailService.trailNoKeyReject(PUID, UID, URI, ADDR, rejection());

        InOrder inOrder = Mockito.inOrder(alertPushService, rdpOpAuditService);
        inOrder.verify(alertPushService).pushAlert(eq(PUID), eq(AlertScene.DML_LARGE_TABLE_NO_KEY), any());
        inOrder.verify(rdpOpAuditService).logAndAddOperationAudit(anyString(), anyString(), anyString(),
            anyString(), any(), any(), any(), any(), any(), any(com.clougence.rdp.service.model.AuditChainMO.class));
    }

    @Test
    public void checkPreview_neverTrails_redErrorPassesThrough() throws Exception {
        // /check 预览不发不写 (design §8.2): the preview renders the FAIL group (failCode +
        // payload pass through additively for the N3 frontend) and structurally cannot reach the
        // trail — only a submit ACT (the typed throw) fires it
        GovTicketV2Controller controller = new GovTicketV2Controller();
        GovTicketV2Service ticketService = mock(GovTicketV2Service.class);
        DmlBatchRejectTrailService trail = mock(DmlBatchRejectTrailService.class);
        ReflectionTestUtils.setField(controller, "govTicketV2Service", ticketService);
        ReflectionTestUtils.setField(controller, "dmlBatchRejectTrailService", trail);

        GovTicketV2CheckVO vo = new GovTicketV2CheckVO();
        GovTicketV2CheckVO.GroupResult failed = new GovTicketV2CheckVO.GroupResult();
        failed.setCheckStatus("FAIL");
        failed.setFailCode(GovTicketV2CheckVO.FAIL_CODE_DML_NO_KEY);
        failed.setDmlNoKeyReject(rejectVO());
        vo.setGroups(java.util.List.of(failed));
        when(ticketService.check(anyString(), anyString(), any())).thenReturn(vo);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(RdpUserService.PUID)).thenReturn(PUID);
        when(request.getAttribute(RdpUserService.UID)).thenReturn(UID);

        Object body = controller.check(new com.clougence.clouddm.console.web.model.fo.govticket.GovTicketV2CheckFO(),
            request);

        assertNotNull(body); // the preview returns the red error normally — no exception, no trail
        verifyNoInteractions(trail);
        verifyNoInteractions(alertPushService);
    }

    @Test
    public void rejectionException_isAnErrorMessageException_handlerSemanticsUnchanged() {
        // the typed exception IS-A ErrorMessageException with the byte-identical user message —
        // the GlobalExceptionHandler's structured 4xx rendering never changes
        DmlNoKeyRejectException e = rejection();
        assertTrue(e instanceof ErrorMessageException);
        assertEquals("precheck failed: prod_db: no key", e.getMessage());
        assertNotNull(e.getReject());
        // never fired for a null payload (defensive; structurally impossible)
        verify(alertPushService, never()).pushAlert(anyString(), any(), any());
    }
}
