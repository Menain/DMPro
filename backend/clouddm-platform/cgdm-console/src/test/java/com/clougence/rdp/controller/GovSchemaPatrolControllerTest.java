/*
 * Copyright 2026 杭州开云集致科技有限公司
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://apache.org/licenses/LICENSE-2.0
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
import java.util.Arrays;

import org.junit.Test;

import com.clougence.clouddm.console.web.global.jwtsession.RequestAuth;

/**
 * Reflection test for GovSchemaPatrolController (sub-task C, layer: auth pins).
 * <p>
 * Verifies every endpoint carries @RequestAuth(GOV_DB_PAIR_MANAGE), mirroring
 * the dbpair management surface — patrol is a dbpair capability, not a new
 * permission point.
 */
public class GovSchemaPatrolControllerTest {

    private static final String EXPECTED_AUTH = "GOV_DB_PAIR_MANAGE";

    private void assertAuth(Method method) {
        RequestAuth annotation = method.getAnnotation(RequestAuth.class);
        assertNotNull(method.getName() + " endpoint must have @RequestAuth", annotation);
        assertTrue(method.getName() + " endpoint must carry GOV_DB_PAIR_MANAGE",
            Arrays.asList(annotation.value()).contains(EXPECTED_AUTH));
    }

    @Test
    public void runsEndpoint_hasGovDbPairManageAuth() throws Exception {
        Method method = GovSchemaPatrolController.class.getMethod("runs",
            com.clougence.clouddm.console.web.model.fo.govpatrol.GovPatrolRunListFO.class,
            jakarta.servlet.http.HttpServletRequest.class);
        assertAuth(method);
    }

    @Test
    public void runDetailEndpoint_hasGovDbPairManageAuth() throws Exception {
        Method method = GovSchemaPatrolController.class.getMethod("runDetail",
            com.clougence.clouddm.console.web.model.fo.govpatrol.GovPatrolRunDetailFO.class,
            jakarta.servlet.http.HttpServletRequest.class);
        assertAuth(method);
    }

    @Test
    public void runNowEndpoint_hasGovDbPairManageAuth() throws Exception {
        Method method = GovSchemaPatrolController.class.getMethod("runNow",
            com.clougence.clouddm.console.web.model.fo.govpatrol.GovPatrolRunNowFO.class,
            jakarta.servlet.http.HttpServletRequest.class);
        assertAuth(method);
    }

    @Test
    public void stopEndpoint_hasGovDbPairManageAuth() throws Exception {
        Method method = GovSchemaPatrolController.class.getMethod("stop",
            com.clougence.clouddm.console.web.model.fo.govpatrol.GovPatrolStopFO.class,
            jakarta.servlet.http.HttpServletRequest.class);
        assertAuth(method);
    }

    @Test
    public void allEndpointsExist() throws Exception {
        assertNotNull(GovSchemaPatrolController.class.getMethod("runs",
            com.clougence.clouddm.console.web.model.fo.govpatrol.GovPatrolRunListFO.class,
            jakarta.servlet.http.HttpServletRequest.class));
        assertNotNull(GovSchemaPatrolController.class.getMethod("runDetail",
            com.clougence.clouddm.console.web.model.fo.govpatrol.GovPatrolRunDetailFO.class,
            jakarta.servlet.http.HttpServletRequest.class));
        assertNotNull(GovSchemaPatrolController.class.getMethod("runNow",
            com.clougence.clouddm.console.web.model.fo.govpatrol.GovPatrolRunNowFO.class,
            jakarta.servlet.http.HttpServletRequest.class));
        assertNotNull(GovSchemaPatrolController.class.getMethod("stop",
            com.clougence.clouddm.console.web.model.fo.govpatrol.GovPatrolStopFO.class,
            jakarta.servlet.http.HttpServletRequest.class));
    }
}
