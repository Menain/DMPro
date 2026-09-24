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
package com.clougence.clouddm.base.metadata.ds.tools;

import com.clougence.clouddm.base.metadata.ds.ConfigDef;
import com.clougence.clouddm.base.metadata.ds.ConfigI18nKey;
import com.clougence.clouddm.base.metadata.ds.ToolConfig;
import com.clougence.clouddm.base.metadata.ds.ToolsConfigSerializer;
import com.clougence.clouddm.sdk.model.ghost.GhostCutOverType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import lombok.Getter;
import lombok.Setter;

/**
 * Admin-level config of the GHOST tool (gh-ost lock-free DDL, plus-online-ddl plugin), mirroring
 * {@link FakerPluginConfig}. The ConfigDef defaults are aligned with the task-level
 * {@code GhostOptionsDTO} defaults (design 09-17-online-ddl-activation §1.1); field initializers
 * carry the same values so a bare {@code new GhostPluginConfig()} (as returned by
 * {@code DmToolConfigService.fetchToolConfig}) is immediately usable, and task-level options
 * override them field by field at session launch.
 */
@Getter
@Setter
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonDeserialize(using = ToolsConfigSerializer.class)
public class GhostPluginConfig extends ToolConfig {

    public static final String TOOL_NAME = "GHOST";

    @ConfigDef(name = "chunkSize", defaultValue = "3000", //
            labelKey = ConfigI18nKey.CONFIG_TOOL_GHOST_CHUNK_SIZE_LABEL, readOnly = false)
    private Integer          chunkSize               = 3000;

    @ConfigDef(name = "dmlBatchSize", defaultValue = "10", //
            labelKey = ConfigI18nKey.CONFIG_TOOL_GHOST_DML_BATCH_SIZE_LABEL, readOnly = false)
    private Integer          dmlBatchSize            = 10;

    @ConfigDef(name = "cutOverType", defaultValue = "DEFAULT", //
            labelKey = ConfigI18nKey.CONFIG_TOOL_GHOST_CUT_OVER_TYPE_LABEL, readOnly = false)
    private GhostCutOverType cutOverType             = GhostCutOverType.DEFAULT;

    @ConfigDef(name = "cutOverLockTimeoutSec", defaultValue = "10", //
            labelKey = ConfigI18nKey.CONFIG_TOOL_GHOST_CUT_OVER_LOCK_TIMEOUT_SEC_LABEL, readOnly = false)
    private Integer          cutOverLockTimeoutSec   = 10;

    @ConfigDef(name = "assumeRbr", defaultValue = "true", //
            labelKey = ConfigI18nKey.CONFIG_TOOL_GHOST_ASSUME_RBR_LABEL, readOnly = false)
    private Boolean          assumeRbr               = true;

    @ConfigDef(name = "allowOnMaster", defaultValue = "true", //
            labelKey = ConfigI18nKey.CONFIG_TOOL_GHOST_ALLOW_ON_MASTER_LABEL, readOnly = false)
    private Boolean          allowOnMaster           = true;

    @ConfigDef(name = "assumeDsProxyInFront", defaultValue = "true", //
            labelKey = ConfigI18nKey.CONFIG_TOOL_GHOST_ASSUME_DS_PROXY_IN_FRONT_LABEL, readOnly = false)
    private Boolean          assumeDsProxyInFront    = true;

    @ConfigDef(name = "timestampOldTable", defaultValue = "true", //
            labelKey = ConfigI18nKey.CONFIG_TOOL_GHOST_TIMESTAMP_OLD_TABLE_LABEL, readOnly = false)
    private Boolean          timestampOldTable       = true;

    @ConfigDef(name = "initiallyDropGhostTable", defaultValue = "true", //
            labelKey = ConfigI18nKey.CONFIG_TOOL_GHOST_INITIALLY_DROP_GHOST_TABLE_LABEL, readOnly = false)
    private Boolean          initiallyDropGhostTable = true;

    @ConfigDef(name = "hbIntervalMs", defaultValue = "2000", //
            labelKey = ConfigI18nKey.CONFIG_TOOL_GHOST_HB_INTERVAL_MS_LABEL, readOnly = false)
    private Integer          hbIntervalMs            = 2000;

    @ConfigDef(name = "exactRowCount", defaultValue = "false", //
            labelKey = ConfigI18nKey.CONFIG_TOOL_GHOST_EXACT_ROW_COUNT_LABEL, readOnly = false)
    private Boolean          exactRowCount           = false;

    @ConfigDef(name = "concurrentRowCount", defaultValue = "false", //
            labelKey = ConfigI18nKey.CONFIG_TOOL_GHOST_CONCURRENT_ROW_COUNT_LABEL, readOnly = false)
    private Boolean          concurrentRowCount      = false;

    @ConfigDef(name = "verbose", defaultValue = "false", //
            labelKey = ConfigI18nKey.CONFIG_TOOL_GHOST_VERBOSE_LABEL, readOnly = false)
    private Boolean          verbose                 = false;

    /** session-log retention after close (same semantics as {@link FakerPluginConfig#getKeepLog()}) */
    @ConfigDef(name = "keepLog", defaultValue = "false", //
            labelKey = ConfigI18nKey.CONFIG_TOOL_GHOST_KEEP_LOG_LABEL, readOnly = false)
    private Boolean          keepLog                 = false;

    public GhostPluginConfig(){
        this.setToolName(TOOL_NAME);
    }
}
