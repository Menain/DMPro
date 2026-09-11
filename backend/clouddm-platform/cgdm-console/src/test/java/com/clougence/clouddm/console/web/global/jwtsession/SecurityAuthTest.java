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
package com.clougence.clouddm.console.web.global.jwtsession;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.console.web.service.auth.RdpRoleService;
import com.clougence.clouddm.platform.dal.model.auth.DmAuthRoleDO;
import com.clougence.clouddm.platform.dal.model.monitor.SecurityLevel;
import com.clougence.rdp.controller.LogicalDbController;
import com.clougence.rdp.controller.PermissionGroupController;

/**
 * Phase 11 Wave A / A1: security auth tests.
 * <p>
 * Two test groups:
 * <ol>
 * <li>JwtManager.testAuth direct test — the pure-Java core of the "no label -> 403" chain.
 * The method is private; we invoke it via reflection. Dependencies (roleService) are mockable.</li>
 * <li>Controller @RequestAuth annotation pinning — reflection scan of all @RequestMapping methods
 * in the three governance controllers, asserting every method carries @RequestAuth with values
 * drawn from the SecRoleAuthLabel registry. Prevents future label drift.</li>
 * </ol>
 * Research: research/test-infrastructure.md §5 (JwtManager.testAuth is pure Java, no Spring needed).
 */
public class SecurityAuthTest {

    private JwtManager    jwtManager;
    private RdpRoleService roleService;

    private static final long ROLE_ID = 1L;

    @Before
    public void setUp() {
        jwtManager = new JwtManager();
        roleService = mock(RdpRoleService.class);
        ReflectionTestUtils.setField(jwtManager, "roleService", roleService);
    }

    // ======= testAuth: no-label -> false (the "403" core) =======

    @Test
    public void testAuth_nullAuth_returnsFalse() {
        Boolean result = ReflectionTestUtils.invokeMethod(jwtManager, "testAuth", (Object) null, ROLE_ID);
        assertFalse(result);
    }

    @Test
    public void testAuth_refRoleSet_noMatchingLabel_returnsFalse() {
        DmAuthRoleDO role = new DmAuthRoleDO();
        role.setRoleAuthLabels(List.of("RDP_ENV_READ"));
        when(roleService.fetchRoleById(ROLE_ID)).thenReturn(role);

        RequestAuth auth = mockAuth(RequestAuth.AuthStrategy.RefRoleSet, "RDP_DB_CHANGE_PROD_PROMOTE");

        Boolean result = ReflectionTestUtils.invokeMethod(jwtManager, "testAuth", auth, ROLE_ID);
        assertFalse("Role without the required label must be denied", result);
    }

    // ======= testAuth: has-label -> true =======

    @Test
    public void testAuth_refRoleSet_matchingLabel_returnsTrue() {
        DmAuthRoleDO role = new DmAuthRoleDO();
        role.setRoleAuthLabels(List.of("RDP_DB_CHANGE_PROD_PROMOTE", "RDP_ENV_READ"));
        when(roleService.fetchRoleById(ROLE_ID)).thenReturn(role);

        RequestAuth auth = mockAuth(RequestAuth.AuthStrategy.RefRoleSet, "RDP_DB_CHANGE_PROD_PROMOTE");

        Boolean result = ReflectionTestUtils.invokeMethod(jwtManager, "testAuth", auth, ROLE_ID);
        assertTrue(result);
    }

    @Test
    public void testAuth_ignoreStrategy_returnsTrue() {
        // Ignore strategy short-circuits before any role lookup
        RequestAuth auth = mockAuth(RequestAuth.AuthStrategy.Ignore);

        Boolean result = ReflectionTestUtils.invokeMethod(jwtManager, "testAuth", auth, ROLE_ID);
        assertTrue(result);
        verifyNoInteractions(roleService);
    }

    @Test
    public void testAuth_refAnyOnes_roleFound_returnsTrue() {
        DmAuthRoleDO role = new DmAuthRoleDO();
        role.setRoleAuthLabels(List.of());
        when(roleService.fetchRoleById(ROLE_ID)).thenReturn(role);

        RequestAuth auth = mockAuth(RequestAuth.AuthStrategy.RefAnyOnes);

        Boolean result = ReflectionTestUtils.invokeMethod(jwtManager, "testAuth", auth, ROLE_ID);
        assertTrue(result);
    }

    @Test
    public void testAuth_refAnyOnes_roleNotFound_returnsFalse() {
        when(roleService.fetchRoleById(ROLE_ID)).thenReturn(null);

        RequestAuth auth = mockAuth(RequestAuth.AuthStrategy.RefAnyOnes);

        Boolean result = ReflectionTestUtils.invokeMethod(jwtManager, "testAuth", auth, ROLE_ID);
        assertFalse(result);
    }

    @Test
    public void testAuth_refRoleSet_roleNotFound_returnsFalse() {
        when(roleService.fetchRoleById(ROLE_ID)).thenReturn(null);

        RequestAuth auth = mockAuth(RequestAuth.AuthStrategy.RefRoleSet, "RDP_PERM_GROUP_MANAGE");

        Boolean result = ReflectionTestUtils.invokeMethod(jwtManager, "testAuth", auth, ROLE_ID);
        assertFalse(result);
    }

    // ======= helper =======

    private RequestAuth mockAuth(RequestAuth.AuthStrategy strategy, String... labels) {
        RequestAuth auth = mock(RequestAuth.class);
        when(auth.strategy()).thenReturn(strategy);
        when(auth.value()).thenReturn(labels);
        return auth;
    }

    // ======= Controller @RequestAuth annotation pinning =======

    private static final Set<String> REGISTERED_LABELS = new HashSet<>(Arrays.asList(
        "RDP_ENV_READ", "RDP_ENV_MANAGE",
        "RDP_USER_READ", "RDP_USER_MANAGE", "RDP_AUTH_READ", "RDP_AUTH_MANAGE",
        "RDP_ROLE_READ", "RDP_ROLE_MANAGE",
        "RDP_OP_AUDIT_READ", "RDP_OP_AUDIT_EXPORT",
        "RDP_PRI_USER_AK_SK_R", "RDP_PRI_USER_AK_SK_W",
        "RDP_PRI_USER_KV_CONF_R", "RDP_PRI_USER_KV_CONF_W",
        "RDP_PRI_USER_NORMAL_CONF_R", "RDP_PRI_USER_THIRD_PARTY_CONF_W",
        "RDP_DS_READ", "RDP_DS_MANAGE",
        "RDP_WORKER_ORDER_READ", "RDP_WORKER_ORDER_REQUEST",
        "RDP_WORKER_ORDER_APPROVE", "RDP_WORKER_ORDER_EXECUTE",
        "DM_QUERY_CONSOLE", "DM_QUERY_EXPORT", "DM_OBJECT_MANAGER", "DM_DS_MAINTENANCE",
        "DM_DS_READ", "DM_DS_MANAGE", "DM_SSH_CHANNEL_READ", "DM_SSH_CHANNEL_WRITE",
        "DM_WORKER_READ", "DM_WORKER_MANAGE",
        "DM_SECRULES_READ", "DM_SECRULES_MANAGE",
        "DM_CICD_FLOW_READ", "DM_CICD_FLOW_OPERATE", "DM_CICD_FLOW_MANAGE",
        "DM_IM_READ", "DM_IM_MANAGE",
        "DM_GIT_OPS_READ", "DM_GIT_OPS_MANAGE",
        "DM_SQL_AUDIT",
        "RDP_DB_CHANGE_GOVERN_READ", "RDP_PERM_GROUP_MANAGE",
        "RDP_LOGICAL_DB_MANAGE", "RDP_DB_CHANGE_PROD_PROMOTE", "RDP_DB_CHANGE_PROD_DML_DIRECT"
    ));

    @Test
    public void permissionGroupController_allMappingsHaveRequestAuth_registeredLabels() {
        assertControllerAnnotations(PermissionGroupController.class, SecurityLevel.NORMAL);
    }

    @Test
    public void logicalDbController_allMappingsHaveRequestAuth_registeredLabels() {
        assertControllerAnnotations(LogicalDbController.class, SecurityLevel.NORMAL);
    }

    private void assertControllerAnnotations(Class<?> controllerClass, SecurityLevel expectedLevel) {
        int mappingCount = 0;
        for (Method method : controllerClass.getDeclaredMethods()) {
            if (method.getAnnotation(org.springframework.web.bind.annotation.RequestMapping.class) == null) {
                continue;
            }
            mappingCount++;

            RequestAuth auth = method.getAnnotation(RequestAuth.class);
            assertNotNull(
                controllerClass.getSimpleName() + "." + method.getName() + " must have @RequestAuth",
                auth);

            // strategy=RefAnyOnes means login-only (no label needed) — skip label check
            if (auth.strategy() == RequestAuth.AuthStrategy.RefAnyOnes
                || auth.strategy() == RequestAuth.AuthStrategy.Ignore) {
                continue;
            }

            assertTrue(
                controllerClass.getSimpleName() + "." + method.getName()
                    + " @RequestAuth must have at least one label",
                auth.value().length > 0);

            for (String label : auth.value()) {
                assertTrue(
                    controllerClass.getSimpleName() + "." + method.getName()
                        + " uses unregistered label: " + label,
                    REGISTERED_LABELS.contains(label));
            }

            // Pin the security level distribution to prevent drift
            assertEquals(
                controllerClass.getSimpleName() + "." + method.getName()
                    + " security level must be " + expectedLevel,
                expectedLevel, auth.level());
        }
        assertTrue("Controller " + controllerClass.getSimpleName() + " must have @RequestMapping methods",
            mappingCount > 0);
    }
}
