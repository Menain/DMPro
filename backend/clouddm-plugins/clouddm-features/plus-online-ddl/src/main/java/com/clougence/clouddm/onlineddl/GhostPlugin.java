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
package com.clougence.clouddm.onlineddl;

import com.clougence.clouddm.base.metadata.ds.tools.GhostPluginConfig;
import com.clougence.clouddm.onlineddl.config.i18n.GhostI18nKeys;
import com.clougence.clouddm.onlineddl.ghost.GhostTools;
import com.clougence.clouddm.sdk.DsPlugin;
import com.clougence.clouddm.sdk.DsPluginBinder;
import com.clougence.clouddm.sdk.Plugin;
import com.clougence.clouddm.sdk.service.config.ConfigService;
import com.clougence.clouddm.sdk.service.execute.SessionService;

import lombok.extern.slf4j.Slf4j;

/**
 * Online DDL plugin entry (gh-ost lock-free ALTER TABLE), registered as a global tool via the
 * current {@code bindGlobalToolService} extension point. Structurally mirrors {@code FakerPlugin}
 * (the only other tool-style global plugin): no {@code dsProduct} on {@link Plugin} → FooMeta.
 *
 * <p>The legacy {@code addSpi(new MySessionSpi())} (redundant — ds-mysql registers its own
 * session SPI) and {@code addFeature(FUN_SUPPORT_RDB_TX, SUPPORT_FAKER)} (constants no longer
 * exist; faker-template leftovers) registrations were removed on revival.</p>
 *
 * @author wanshao create time is 2021/1/9
 **/
@Slf4j
@Plugin(name = "i18n::" + GhostI18nKeys.PLUGIN_NAME_GHOST)
public class GhostPlugin implements DsPlugin {

    @Override
    public void loadPlugin(DsPluginBinder dsPlugin) {
        SessionService sessionService = dsPlugin.findGlobalService(SessionService.class);
        ConfigService configService = dsPlugin.findGlobalService(ConfigService.class);

        dsPlugin.bindGlobalToolService(GhostPluginConfig.TOOL_NAME, (toolsConfig) -> {
            return new GhostTools(sessionService, configService, (GhostPluginConfig) toolsConfig);
        });

        dsPlugin.bindGlobalI18n(GhostI18nKeys.class);
        log.info("GHOST tool bound (gh-ost lock-free DDL).");
    }
}
