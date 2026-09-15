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
package com.clougence.rdp.controller;

import static org.junit.Assert.*;

import java.lang.reflect.Method;

import org.junit.Test;

import com.clougence.clouddm.console.web.global.jwtsession.RequestAuth;

/**
 * Auth annotation reflection tests for the three new return-revise endpoints.
 * Verifies the @RequestAuth values match the design (§1/§3 permission specs).
 */
public class GovReturnReviseControllerAuthTest {

    @Test
    public void returnTicket_hasRdpWorkerOrderRequestAuth() throws Exception {
        Method method = GovTicketV2Controller.class.getDeclaredMethod("returnTicket",
            com.clougence.clouddm.console.web.model.fo.govticket.GovTicketV2ReturnTicketFO.class,
            jakarta.servlet.http.HttpServletRequest.class);
        RequestAuth auth = method.getAnnotation(RequestAuth.class);
        assertNotNull("returnTicket must have @RequestAuth", auth);
        String[] labels = auth.value();
        assertTrue("returnTicket must require RDP_WORKER_ORDER_REQUEST",
            java.util.Arrays.asList(labels).contains("RDP_WORKER_ORDER_REQUEST"));
    }

    @Test
    public void resubmit_hasRdpWorkerOrderRequestAuth() throws Exception {
        Method method = GovTicketV2Controller.class.getDeclaredMethod("resubmit",
            com.clougence.clouddm.console.web.model.fo.govticket.GovTicketV2ResubmitFO.class,
            jakarta.servlet.http.HttpServletRequest.class);
        RequestAuth auth = method.getAnnotation(RequestAuth.class);
        assertNotNull("resubmit must have @RequestAuth", auth);
        String[] labels = auth.value();
        assertTrue("resubmit must require RDP_WORKER_ORDER_REQUEST",
            java.util.Arrays.asList(labels).contains("RDP_WORKER_ORDER_REQUEST"));
    }

    @Test
    public void returnStmt_hasRefPrimaryOrMaintainerAuth() throws Exception {
        Method method = GovReleaseController.class.getDeclaredMethod("returnStmt",
            com.clougence.clouddm.console.web.model.fo.prodrelease.ProdReleaseReturnStmtFO.class,
            jakarta.servlet.http.HttpServletRequest.class);
        RequestAuth auth = method.getAnnotation(RequestAuth.class);
        assertNotNull("returnStmt must have @RequestAuth", auth);
        assertEquals("returnStmt must use RefPrimaryOrMaintainer strategy",
            RequestAuth.AuthStrategy.RefPrimaryOrMaintainer, auth.strategy());
    }
}
