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
package com.clougence.clouddm.onlineddl.config.i18n;

import com.clougence.utils.i18n.I18nResource;

/**
 * i18n keys of the Online DDL (gh-ost) plugin, mirroring the {@code FakerI18nKeys} mechanism:
 * the {@link I18nResource} bundle paths are loaded into the global i18n layer via
 * {@code DsPluginBinder.bindGlobalI18n(GhostI18nKeys.class)} at plugin load.
 */
@I18nResource({ "/META-INF/ghost-ui" })
public interface GhostI18nKeys {

    String PLUGIN_NAME_GHOST = "PLUGIN_NAME_GHOST";
}
