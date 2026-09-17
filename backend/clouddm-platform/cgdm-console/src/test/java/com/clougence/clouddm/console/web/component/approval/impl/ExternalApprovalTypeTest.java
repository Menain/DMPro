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
package com.clougence.clouddm.console.web.component.approval.impl;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.clougence.clouddm.console.web.component.approval.ApprovalFlowService;
import com.clougence.clouddm.console.web.util.RdpConvertUtils;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalType;
import com.clougence.clouddm.sdk.approval.ApprovalProvider;

/**
 * T1: External approval type modeling pin.
 * <p>
 * Verifies the External enum is correctly wired: not in SUPPORT_LIST (no SPI),
 * getTicketTypes includes an always-enabled External row, config key resolves
 * to null (always enabled), and valueOfCode/valueOfProvider round-trip.
 */
public class ExternalApprovalTypeTest {

    @Test
    public void external_notInSupportList() {
        List<ApprovalProvider> support = ApprovalFlowService.SUPPORT_LIST;
        assertFalse("External must NOT be in SUPPORT_LIST (not an SPI provider)",
            support.contains(ApprovalProvider.External));
    }

    @Test
    public void external_enumProperties() {
        assertEquals(ApprovalProvider.External, ApprovalType.External.getProviderType());
        assertEquals("APPROVAL_EXTERNAL", ApprovalType.External.getI18nKey());
    }

    @Test
    public void external_valueOfProvider_roundTrip() {
        assertSame(ApprovalType.External, ApprovalType.valueOfProvider(ApprovalProvider.External));
    }

    @Test
    public void external_valueOfCode_roundTrip() {
        assertSame(ApprovalType.External, ApprovalType.Internal.valueOfCode("External"));
        // case-insensitive
        assertSame(ApprovalType.External, ApprovalType.Internal.valueOfCode("external"));
        assertSame(ApprovalType.External, ApprovalType.Internal.valueOfCode("EXTERNAL"));
    }

    @Test
    public void external_getByName_roundTrip() {
        assertSame(ApprovalType.External, ApprovalType.getByName("External"));
        assertNull("null input returns null", ApprovalType.getByName(null));
    }

    @Test
    public void external_convertToApprovalEnableConfigKey_returnsNull() {
        // External walks the default branch → null → checkEnableApproval returns true (always enabled)
        assertNull(RdpConvertUtils.convertToApprovalEnableConfigKey(ApprovalProvider.External));
    }

    @Test
    public void external_getTicketTypes_includesEnabledRow() {
        // Construct the service with an empty handler list (no handler resolution needed for getTicketTypes).
        // PluginManager.getSpiNamesByType returns empty in a unit test (no plugins loaded),
        // so SUPPORT_LIST items get enable=false — External is added directly, always enabled.
        ApprovalProviderServiceImpl service = new ApprovalProviderServiceImpl(java.util.Collections.emptyList());

        List<Map<String, Object>> types = service.getTicketTypes("puid-001");

        // Find the External row
        Map<String, Object> externalRow = null;
        for (Map<String, Object> row : types) {
            if ("External".equals(row.get("approvalType"))) {
                externalRow = row;
                break;
            }
        }
        assertNotNull("getTicketTypes must include an External row", externalRow);
        assertTrue("External row must be enabled", (Boolean) externalRow.get("enable"));
        assertEquals("External row desc must be empty", "", externalRow.get("desc"));
    }

    @Test
    public void external_getTicketTypes_includesInternalRow() {
        // Regression pin: Internal row still present and enabled
        ApprovalProviderServiceImpl service = new ApprovalProviderServiceImpl(java.util.Collections.emptyList());
        List<Map<String, Object>> types = service.getTicketTypes("puid-001");

        Map<String, Object> internalRow = null;
        for (Map<String, Object> row : types) {
            if ("Internal".equals(row.get("approvalType"))) {
                internalRow = row;
                break;
            }
        }
        assertNotNull("Internal row must still be present", internalRow);
        assertTrue("Internal row must be enabled", (Boolean) internalRow.get("enable"));
    }
}
