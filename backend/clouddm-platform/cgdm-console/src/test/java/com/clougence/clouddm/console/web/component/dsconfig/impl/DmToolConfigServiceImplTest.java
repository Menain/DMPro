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
package com.clougence.clouddm.console.web.component.dsconfig.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import com.clougence.clouddm.base.metadata.ds.ToolConfig;
import com.clougence.clouddm.base.metadata.ds.tools.FakerPluginConfig;
import com.clougence.clouddm.base.metadata.ds.tools.GhostPluginConfig;
import com.clougence.clouddm.sdk.model.ghost.GhostCutOverType;

public class DmToolConfigServiceImplTest {

    private final DmToolConfigServiceImpl service = new DmToolConfigServiceImpl();

    @Test
    public void fetchToolConfig_ghost_lowConcurrencyAndDesignDefaults() {
        ToolConfig config = service.fetchToolConfig(GhostPluginConfig.TOOL_NAME);

        assertTrue(config instanceof GhostPluginConfig);
        assertEquals(GhostPluginConfig.TOOL_NAME, config.getToolName());
        // gh-ost is heavyweight (shadow table doubles the disk footprint + binlog streaming),
        // so the caps stay far below FAKER's 50 (design 09-17-online-ddl-activation §1.2)
        assertEquals(Integer.valueOf(2), config.getOnlineMaxConcurrent());
        assertEquals(Integer.valueOf(2), config.getExportMaxConcurrent());

        GhostPluginConfig ghost = (GhostPluginConfig) config;
        assertEquals(Integer.valueOf(3000), ghost.getChunkSize());
        assertEquals(Integer.valueOf(10), ghost.getDmlBatchSize());
        assertEquals(GhostCutOverType.DEFAULT, ghost.getCutOverType());
        assertEquals(Integer.valueOf(10), ghost.getCutOverLockTimeoutSec());
        assertTrue(ghost.getAssumeRbr());
        assertTrue(ghost.getAllowOnMaster());
        assertTrue(ghost.getAssumeDsProxyInFront());
        assertTrue(ghost.getTimestampOldTable());
        assertTrue(ghost.getInitiallyDropGhostTable());
        assertEquals(Integer.valueOf(2000), ghost.getHbIntervalMs());
        assertFalse(ghost.getExactRowCount());
        assertFalse(ghost.getConcurrentRowCount());
        assertFalse(ghost.getVerbose());
        assertFalse(ghost.getKeepLog());
    }

    @Test
    public void fetchToolConfig_faker_unchanged() {
        ToolConfig config = service.fetchToolConfig(FakerPluginConfig.TOOL_NAME);

        assertTrue(config instanceof FakerPluginConfig);
        assertEquals(FakerPluginConfig.TOOL_NAME, config.getToolName());
        assertEquals(Integer.valueOf(50), config.getOnlineMaxConcurrent());
        assertEquals(Integer.valueOf(50), config.getExportMaxConcurrent());
    }

    @Test
    public void fetchToolConfig_unknownTool_rejected() {
        assertUnsupported("PT-OSC");
        // tool dispatch is case-sensitive by contract (StringUtils.equals)
        assertUnsupported("ghost");
    }

    private void assertUnsupported(String toolName) {
        try {
            service.fetchToolConfig(toolName);
            fail("expected UnsupportedOperationException for tool: " + toolName);
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }
}
