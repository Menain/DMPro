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
package com.clougence.clouddm.init.component.scripts;

import java.util.List;

import com.clougence.clouddm.init.component.flyway.AbstractUpgradeJavaMigration;

/**
 * Add rule_kind column to dm_sec_spec so rule sets can be split into
 * QUERY and SENSITIVE families. Existing specs default to QUERY.
 */
public class V202609130003__dm_sec_spec_rule_kind extends AbstractUpgradeJavaMigration {
    @Override
    public List<String> collectScript() {
        return List.of("""
                ALTER TABLE dm_sec_spec ADD COLUMN rule_kind varchar(12) NOT NULL DEFAULT 'QUERY'
                """);
    }
}
