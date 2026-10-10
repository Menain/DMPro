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

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.junit.Test;

import com.clougence.clouddm.console.web.component.approval.ApprovalFlowService;
import com.clougence.clouddm.console.web.global.i18n.DmI18nUtils;
import com.clougence.clouddm.console.web.global.i18n.I18nRdpMsgKeys;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalType;
import com.clougence.clouddm.sdk.approval.ApprovalProvider;

/**
 * ding-bridge R7 / design §7 — direct-connect channel deprecation pin (getTicketTypes single point).
 * <p>
 * Contract:
 * <ul>
 * <li>DingTalk/Feishu/Wechat rows are KEPT (legacy type=DingTalk environments still echo in the
 *     env-param modal) but forced enable=false with the deprecated desc — new configurations
 *     cannot pick them (env.vue renders enable=false cards without a click handler).</li>
 * <li>The deprecation short-circuits BEFORE the plugin/enable/status probes — a retired plugin
 *     is never called for a non-selectable channel (proven via desc: in a plugin-less unit
 *     environment the old code would produce the PLUGIN_NOT_FOUND desc).</li>
 * <li>Internal/External rows are untouched (always enabled, empty desc).</li>
 * <li>Legacy runtime chain mechanism pin: getTicketTypes is the DROPDOWN data source only —
 *     submit routing / scheduler / Stream read the STORED env param via ApprovalType.getByName,
 *     which must keep resolving "DingTalk" (enum + SUPPORT_LIST untouched, zero plugin deletion).</li>
 * </ul>
 */
public class ApprovalTypeDeprecationTest {

    private List<Map<String, Object>> ticketTypes() {
        // same construction as ExternalApprovalTypeTest: empty handler list, no plugins loaded
        ApprovalProviderServiceImpl service = new ApprovalProviderServiceImpl(Collections.emptyList());
        return service.getTicketTypes("puid-001");
    }

    private Map<String, Object> row(List<Map<String, Object>> types, String approvalType) {
        for (Map<String, Object> row : types) {
            if (approvalType.equals(row.get("approvalType"))) {
                return row;
            }
        }
        return null;
    }

    // ==================== deprecated rows: kept, disabled, deprecated desc ====================

    @Test
    public void directChannels_rowsKept_disabledWithDeprecatedDesc() {
        List<Map<String, Object>> types = ticketTypes();
        String deprecatedDesc = DmI18nUtils.getMessage(I18nRdpMsgKeys.TICKET_APPROVAL_TYPE_DEPRECATED.name());

        for (String channel : new String[] { "DingTalk", "Feishu", "Wechat" }) {
            Map<String, Object> row = row(types, channel);
            assertNotNull("row must be KEPT for legacy env echo: " + channel, row);
            assertEquals("new configurations cannot pick " + channel, Boolean.FALSE, row.get("enable"));
            assertEquals("desc must carry the deprecation marker: " + channel, deprecatedDesc, row.get("desc"));
            assertNotNull("i18n label intact for the disabled card: " + channel, row.get("i18n"));
            assertFalse(((String) row.get("i18n")).trim().isEmpty());
        }
    }

    @Test
    public void directChannels_shortCircuitBeforePluginProbes() {
        // In a plugin-less unit environment the pre-deprecation code produced the PLUGIN_NOT_FOUND
        // desc; the deprecated desc proves the short-circuit precedes every plugin/SPI probe.
        List<Map<String, Object>> types = ticketTypes();
        String pluginNotFoundDesc = DmI18nUtils.getMessage(I18nRdpMsgKeys.TICKET_APPROVAL_PLUGIN_NOT_FOUND.name());
        String deprecatedDesc = DmI18nUtils.getMessage(I18nRdpMsgKeys.TICKET_APPROVAL_TYPE_DEPRECATED.name());
        assertNotEquals("the two descs must be distinguishable for this pin", pluginNotFoundDesc, deprecatedDesc);

        for (String channel : new String[] { "DingTalk", "Feishu", "Wechat" }) {
            assertNotEquals("retired plugins are never probed for " + channel,
                pluginNotFoundDesc, row(types, channel).get("desc"));
        }
    }

    @Test
    public void rowSet_exactlyFiveRows_legacyEchoShapeIntact() {
        List<Map<String, Object>> types = ticketTypes();
        // Internal + External + the three kept-but-disabled direct channels (no row deleted)
        assertEquals(5, types.size());
        assertNotNull(row(types, "Internal"));
        assertNotNull(row(types, "External"));
        assertNotNull(row(types, "DingTalk"));
        assertNotNull(row(types, "Feishu"));
        assertNotNull(row(types, "Wechat"));
    }

    // ==================== Internal/External untouched ====================

    @Test
    public void internalExternalRows_untouched() {
        List<Map<String, Object>> types = ticketTypes();

        Map<String, Object> internal = row(types, "Internal");
        assertEquals(Boolean.TRUE, internal.get("enable"));
        assertEquals("", internal.get("desc"));

        Map<String, Object> external = row(types, "External");
        assertEquals(Boolean.TRUE, external.get("enable"));
        assertEquals("", external.get("desc"));
    }

    // ==================== legacy runtime chain mechanism pin (存量行为不变) ====================

    @Test
    public void legacyDingTalkRuntimeChain_enumAndSupportListUntouched() {
        // Mechanism (survey §9): the submit chain resolves the channel from the STORED env param
        // string via ApprovalType.getByName — never from getTicketTypes. Deprecating the dropdown
        // row therefore cannot touch already-configured DingTalk environments.
        assertSame(ApprovalType.DingTalk, ApprovalType.getByName("DingTalk"));
        assertSame(ApprovalType.Feishu, ApprovalType.getByName("Feishu"));
        assertSame(ApprovalType.Wechat, ApprovalType.getByName("Wechat"));

        // SUPPORT_LIST untouched (no plugin/channel code deletion — prd R7 red line)
        assertTrue(ApprovalFlowService.SUPPORT_LIST.contains(ApprovalProvider.DingTalk));
        assertTrue(ApprovalFlowService.SUPPORT_LIST.contains(ApprovalProvider.Feishu));
        assertTrue(ApprovalFlowService.SUPPORT_LIST.contains(ApprovalProvider.Wechat));
        assertEquals(3, ApprovalFlowService.SUPPORT_LIST.size());

        // i18n labels of the legacy enum intact (env-modal echo of stored type=DingTalk)
        assertEquals("APPROVAL_DINGTALK", ApprovalType.DingTalk.getI18nKey());
        assertSame(ApprovalType.DingTalk, ApprovalType.valueOfProvider(ApprovalProvider.DingTalk));
    }

    // ==================== i18n three-file-set pin ====================

    @Test
    public void deprecatedKey_presentInAllThreeMsgProperties() throws Exception {
        String[] resources = {
            "/rdp/static/i18n/msg.properties",
            "/rdp/static/i18n/msg_en_US.properties",
            "/rdp/static/i18n/msg_zh_CN.properties"
        };
        for (String res : resources) {
            Properties p = new Properties();
            try (InputStream in = getClass().getResourceAsStream(res)) {
                assertNotNull("resource must exist: " + res, in);
                p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
            String value = p.getProperty(I18nRdpMsgKeys.TICKET_APPROVAL_TYPE_DEPRECATED.name());
            assertNotNull("key missing in " + res, value);
            assertFalse("key blank in " + res, value.trim().isEmpty());
        }
    }
}
