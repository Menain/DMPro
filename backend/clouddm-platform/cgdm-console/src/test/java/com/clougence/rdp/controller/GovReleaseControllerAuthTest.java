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

import com.clougence.clouddm.console.web.controller.openapi.DbChangeOpenApi;
import com.clougence.clouddm.console.web.global.jwtsession.RequestAuth;
import com.clougence.clouddm.console.web.global.mcp.model.McpTool;
import com.clougence.clouddm.console.web.model.fo.prodrelease.ProdReleaseCancelFO;
import com.clougence.clouddm.console.web.model.fo.prodrelease.ProdReleaseResubmitFO;
import com.clougence.clouddm.console.web.model.fo.prodrelease.ProdReleaseReturnFO;

/**
 * Auth annotation reflection tests for the new R1-R4 release endpoints.
 * Verifies @RequestAuth strategies and @McpTool presence match the design.
 */
public class GovReleaseControllerAuthTest {

    @Test
    public void create_hasRefAnyOnes() throws Exception {
        Method method = GovReleaseController.class.getDeclaredMethod("create",
            com.clougence.clouddm.console.web.model.fo.prodrelease.ProdReleaseCreateFO.class,
            jakarta.servlet.http.HttpServletRequest.class);
        RequestAuth auth = method.getAnnotation(RequestAuth.class);
        assertNotNull("create must have @RequestAuth", auth);
        assertEquals("create must use RefAnyOnes (R1: open to all)",
            RequestAuth.AuthStrategy.RefAnyOnes, auth.strategy());
    }

    @Test
    public void returnRelease_hasRefPrimaryOrMaintainer() throws Exception {
        Method method = GovReleaseController.class.getDeclaredMethod("returnRelease",
            ProdReleaseReturnFO.class, jakarta.servlet.http.HttpServletRequest.class);
        RequestAuth auth = method.getAnnotation(RequestAuth.class);
        assertNotNull("returnRelease must have @RequestAuth", auth);
        assertEquals("returnRelease must use RefPrimaryOrMaintainer (D1: admin/primary only)",
            RequestAuth.AuthStrategy.RefPrimaryOrMaintainer, auth.strategy());
    }

    @Test
    public void resubmit_hasRefAnyOnes() throws Exception {
        Method method = GovReleaseController.class.getDeclaredMethod("resubmit",
            ProdReleaseResubmitFO.class, jakarta.servlet.http.HttpServletRequest.class);
        RequestAuth auth = method.getAnnotation(RequestAuth.class);
        assertNotNull("resubmit must have @RequestAuth", auth);
        assertEquals("resubmit must use RefAnyOnes (applicant self-service)",
            RequestAuth.AuthStrategy.RefAnyOnes, auth.strategy());
    }

    @Test
    public void cancelRelease_hasRefAnyOnes() throws Exception {
        Method method = GovReleaseController.class.getDeclaredMethod("cancelRelease",
            ProdReleaseCancelFO.class, jakarta.servlet.http.HttpServletRequest.class);
        RequestAuth auth = method.getAnnotation(RequestAuth.class);
        assertNotNull("cancelRelease must have @RequestAuth", auth);
        assertEquals("cancelRelease must use RefAnyOnes (applicant self-service)",
            RequestAuth.AuthStrategy.RefAnyOnes, auth.strategy());
    }

    @Test
    public void retryStmt_hasRefPrimaryOrMaintainer() throws Exception {
        Method method = GovReleaseController.class.getDeclaredMethod("retryStmt",
            com.clougence.clouddm.console.web.model.fo.prodrelease.ProdReleaseRetryStmtFO.class,
            jakarta.servlet.http.HttpServletRequest.class);
        RequestAuth auth = method.getAnnotation(RequestAuth.class);
        assertNotNull("retryStmt must have @RequestAuth", auth);
        assertEquals("retryStmt must use RefPrimaryOrMaintainer (D4: not relaxed)",
            RequestAuth.AuthStrategy.RefPrimaryOrMaintainer, auth.strategy());
    }

    @Test
    public void openApiReleaseReturn_hasIgnoreAndMcpTool() throws Exception {
        Method method = DbChangeOpenApi.class.getDeclaredMethod("releaseReturn",
            com.clougence.clouddm.console.web.model.fo.openapi.DbChangeReleaseReturnFO.class,
            jakarta.servlet.http.HttpServletRequest.class);
        RequestAuth auth = method.getAnnotation(RequestAuth.class);
        assertNotNull("releaseReturn must have @RequestAuth", auth);
        assertEquals("releaseReturn must use Ignore (openapi convention)",
            RequestAuth.AuthStrategy.Ignore, auth.strategy());
        McpTool mcpTool = method.getAnnotation(McpTool.class);
        assertNotNull("releaseReturn must have @McpTool", mcpTool);
    }
}
