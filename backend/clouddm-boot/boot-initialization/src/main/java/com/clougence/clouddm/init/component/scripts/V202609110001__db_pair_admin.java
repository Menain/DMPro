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
 * P1 of the database change governance platform: creates the DB pair admin metadata tables.
 * Tables: dm_db_pair (pre-prod DB mapping), dm_db_service (service list), dm_db_pair_service (many-to-many).
 * Pure addition, no existing tables or historical migrations are touched.
 */
public class V202609110001__db_pair_admin extends AbstractUpgradeJavaMigration {

    @Override
    public List<String> collectScript() {
        return List.of("""
                CREATE TABLE IF NOT EXISTS dm_db_pair
                (
                    id            bigint       NOT NULL AUTO_INCREMENT,
                    gmt_create    datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified  datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    pre_ds_id     bigint       NULL,
                    pre_db_name   varchar(192) NULL,
                    prod_ds_id    bigint       NOT NULL,
                    prod_db_name  varchar(192) NOT NULL,
                    status        varchar(16)  NOT NULL DEFAULT 'ENABLED',
                    remark        varchar(512) NULL,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_pre (pre_ds_id, pre_db_name),
                    UNIQUE KEY uk_prod (prod_ds_id, prod_db_name)
                ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
                """, """
                CREATE TABLE IF NOT EXISTS dm_db_service
                (
                    id            bigint       NOT NULL AUTO_INCREMENT,
                    gmt_create    datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified  datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    service_code  varchar(64)  NOT NULL,
                    service_name  varchar(128) NOT NULL,
                    remark        varchar(512) NULL,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_code (service_code)
                ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
                """, """
                CREATE TABLE IF NOT EXISTS dm_db_pair_service
                (
                    id           bigint       NOT NULL AUTO_INCREMENT,
                    gmt_create   datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    pair_id      bigint       NOT NULL,
                    service_id   bigint       NOT NULL,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_pair_service (pair_id, service_id),
                    KEY idx_service (service_id)
                ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
                """);
    }
}
