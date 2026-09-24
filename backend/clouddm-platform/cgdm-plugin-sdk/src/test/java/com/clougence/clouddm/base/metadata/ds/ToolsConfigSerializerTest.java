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
package com.clougence.clouddm.base.metadata.ds;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Field;

import org.junit.Test;

import com.clougence.clouddm.base.metadata.ds.tools.FakerPluginConfig;
import com.clougence.clouddm.base.metadata.ds.tools.GhostPluginConfig;
import com.clougence.clouddm.sdk.model.ghost.GhostCutOverType;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * ToolsConfigSerializer dispatch tests (B6/S1): the FAKER branch must stay byte-compatible while
 * the new GHOST branch deserializes into GhostPluginConfig; unknown tool names keep the
 * UnsupportedEncodingException contract. GhostPluginConfig defaults are pinned to the design
 * table (§1.1 of 09-17-online-ddl-activation) both on the field initializers and on the
 * {@code @ConfigDef.defaultValue} annotations (drift guard via reflection).
 */
public class ToolsConfigSerializerTest {

    /** drives the serializer exactly the way Jackson does, minus the @JsonTypeInfo wrapper */
    private static ToolConfig deserialize(String json) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        try (JsonParser parser = mapper.getFactory().createParser(json)) {
            parser.nextToken();
            return new ToolsConfigSerializer().deserialize(parser, null);
        }
    }

    @Test
    public void shouldDeserializeGhostToolConfig() throws IOException {
        ToolConfig config = deserialize("{\"toolName\":\"GHOST\",\"chunkSize\":5000,\"dmlBatchSize\":20,"
                                        + "\"cutOverType\":\"ATOMIC\",\"assumeRbr\":false,\"verbose\":true,\"onlineMaxConcurrent\":2}");

        assertTrue(config instanceof GhostPluginConfig);
        GhostPluginConfig ghost = (GhostPluginConfig) config;
        assertEquals(GhostPluginConfig.TOOL_NAME, ghost.getToolName());
        assertEquals(Integer.valueOf(5000), ghost.getChunkSize());
        assertEquals(Integer.valueOf(20), ghost.getDmlBatchSize());
        assertEquals(GhostCutOverType.ATOMIC, ghost.getCutOverType());
        assertFalse(ghost.getAssumeRbr());
        assertTrue(ghost.getVerbose());
        assertEquals(Integer.valueOf(2), ghost.getOnlineMaxConcurrent());
        // fields absent from the JSON keep the constructor/initializer defaults
        assertEquals(Integer.valueOf(10), ghost.getCutOverLockTimeoutSec());
        assertTrue(ghost.getAllowOnMaster());
        assertEquals(Integer.valueOf(2000), ghost.getHbIntervalMs());
        assertFalse(ghost.getKeepLog());
    }

    @Test
    public void shouldKeepFakerDeserializationUnchanged() throws IOException {
        ToolConfig config = deserialize("{\"toolName\":\"FAKER\",\"keepLog\":true,\"onlineMaxConcurrent\":50}");

        assertTrue(config instanceof FakerPluginConfig);
        FakerPluginConfig faker = (FakerPluginConfig) config;
        assertEquals(FakerPluginConfig.TOOL_NAME, faker.getToolName());
        assertTrue(faker.getKeepLog());
        assertEquals(Integer.valueOf(50), faker.getOnlineMaxConcurrent());
    }

    @Test
    public void shouldRejectUnknownToolName() {
        try {
            deserialize("{\"toolName\":\"PT-OSC\"}");
            fail("unknown toolName must be rejected");
        } catch (UnsupportedEncodingException expected) {
            assertEquals("tool 'PT-OSC' Unsupported.", expected.getMessage());
        } catch (IOException e) {
            fail("unexpected exception type: " + e);
        }
    }

    @Test
    public void shouldRejectMissingToolName() {
        try {
            deserialize("{\"keepLog\":true}");
            fail("missing toolName must be rejected");
        } catch (UnsupportedEncodingException expected) {
            assertEquals("tool 'null' Unsupported.", expected.getMessage());
        } catch (IOException e) {
            fail("unexpected exception type: " + e);
        }
    }

    @Test
    public void ghostDefaultsMustMatchDesignTable() {
        GhostPluginConfig config = new GhostPluginConfig();

        assertEquals("GHOST", GhostPluginConfig.TOOL_NAME);
        assertEquals(GhostPluginConfig.TOOL_NAME, config.getToolName());
        assertEquals(Integer.valueOf(3000), config.getChunkSize());
        assertEquals(Integer.valueOf(10), config.getDmlBatchSize());
        assertEquals(GhostCutOverType.DEFAULT, config.getCutOverType());
        assertEquals(Integer.valueOf(10), config.getCutOverLockTimeoutSec());
        assertTrue(config.getAssumeRbr());
        assertTrue(config.getAllowOnMaster());
        assertTrue(config.getAssumeDsProxyInFront());
        assertTrue(config.getTimestampOldTable());
        assertTrue(config.getInitiallyDropGhostTable());
        assertEquals(Integer.valueOf(2000), config.getHbIntervalMs());
        assertFalse(config.getExactRowCount());
        assertFalse(config.getConcurrentRowCount());
        assertFalse(config.getVerbose());
        assertFalse(config.getKeepLog());
    }

    @Test
    public void ghostConfigDefAnnotationsMustNotDriftFromFieldInitializers() throws Exception {
        GhostPluginConfig config = new GhostPluginConfig();
        int annotated = 0;
        for (Field field : GhostPluginConfig.class.getDeclaredFields()) {
            ConfigDef def = field.getAnnotation(ConfigDef.class);
            if (def == null) {
                continue;
            }
            annotated++;
            field.setAccessible(true);
            Object value = field.get(config);
            assertEquals("@ConfigDef.name must equal the field name: " + field.getName(), //
                    field.getName(), def.name());
            assertEquals("@ConfigDef.defaultValue drifted from the field initializer: " + field.getName(), //
                    def.defaultValue(), String.valueOf(value));
            assertFalse("labelKey must be set: " + field.getName(), def.labelKey().isEmpty());
            assertFalse("admin-tunable parameter must not be readOnly: " + field.getName(), def.readOnly());
        }
        assertEquals(14, annotated);
    }

    @Test
    public void ghostConfigShouldSurviveJacksonRoundTrip() throws IOException {
        // production wire shape: @JsonTypeInfo(Id.CLASS) writes "@class", the reading side
        // resolves the subtype and lands in ToolsConfigSerializer via @JsonDeserialize
        ObjectMapper mapper = new ObjectMapper();
        GhostPluginConfig config = new GhostPluginConfig();
        config.setChunkSize(4096);
        config.setCutOverType(GhostCutOverType.ATOMIC);
        config.setKeepLog(true);

        String json = mapper.writeValueAsString(config);
        assertTrue(json.contains("\"@class\""));

        ToolConfig back = mapper.readValue(json, ToolConfig.class);
        assertTrue(back instanceof GhostPluginConfig);
        GhostPluginConfig ghost = (GhostPluginConfig) back;
        assertEquals(Integer.valueOf(4096), ghost.getChunkSize());
        assertEquals(GhostCutOverType.ATOMIC, ghost.getCutOverType());
        assertTrue(ghost.getKeepLog());
        // untouched defaults survive the round trip as well
        assertTrue(ghost.getAssumeRbr());
        assertEquals(Integer.valueOf(2000), ghost.getHbIntervalMs());
    }
}
