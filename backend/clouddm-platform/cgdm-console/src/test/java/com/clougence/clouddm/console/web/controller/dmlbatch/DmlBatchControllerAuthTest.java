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
package com.clougence.clouddm.console.web.controller.dmlbatch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.Test;

import com.clougence.clouddm.console.web.global.jwtsession.RequestAuth;
import com.clougence.clouddm.console.web.model.fo.dmlbatch.DmlBatchControlFO;

import jakarta.servlet.http.HttpServletRequest;

/**
 * B7 N2 (implement.md 单测矩阵「控制端点 DM_GHOST_EXEC 门控 @RequestAuth 反射 pin」): all three
 * control-plane endpoints are gated by the REUSED DM_GHOST_EXEC label (裁决 #15 用户亲裁 — no new
 * permission label; the copy widened to "DDL/DML" in the dm-auth-label properties, registration
 * face untouched). The GovReturnReviseControllerAuthTest reflection pattern.
 */
public class DmlBatchControllerAuthTest {

    private void assertDmGhostExecGate(String endpoint) throws Exception {
        Method method = DmlBatchController.class.getDeclaredMethod(endpoint,
            DmlBatchControlFO.class, HttpServletRequest.class);
        RequestAuth auth = method.getAnnotation(RequestAuth.class);
        assertNotNull(endpoint + " must have @RequestAuth", auth);
        assertTrue(endpoint + " must require DM_GHOST_EXEC (裁决 #15 reuse, no new label)",
            Arrays.asList(auth.value()).contains("DM_GHOST_EXEC"));
        assertEquals("the label-value form (not a strategy) must be used",
            RequestAuth.AuthStrategy.RefRoleSet, auth.strategy());
    }

    @Test
    public void pause_gatedByDmGhostExec() throws Exception {
        assertDmGhostExecGate("pause");
    }

    @Test
    public void resume_gatedByDmGhostExec() throws Exception {
        assertDmGhostExecGate("resume");
    }

    @Test
    public void stop_gatedByDmGhostExec() throws Exception {
        assertDmGhostExecGate("stop");
    }
}
