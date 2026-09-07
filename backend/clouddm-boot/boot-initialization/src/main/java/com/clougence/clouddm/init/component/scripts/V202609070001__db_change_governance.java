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
 * Phase 1 of the database change governance platform: creates the 10 governance metadata tables.
 * Tables: permission group domain (4), environment governance domain (2), change governance domain (4).
 * Pure addition, no existing tables or historical migrations are touched.
 */
public class V202609070001__db_change_governance extends AbstractUpgradeJavaMigration {

    @Override
    public List<String> collectScript() {
        return List.of("""
                CREATE TABLE IF NOT EXISTS dm_perm_group
                (
                    id           bigint       NOT NULL AUTO_INCREMENT,
                    gmt_create   datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    group_code   varchar(64)  NOT NULL,
                    group_name   varchar(127) NOT NULL,
                    description  varchar(512) NULL,
                    status       varchar(32)  NOT NULL,
                    creator_uid  varchar(127) NOT NULL,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_group_code (group_code)
                ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
                """, """
                CREATE TABLE IF NOT EXISTS dm_perm_group_member
                (
                    id           bigint       NOT NULL AUTO_INCREMENT,
                    gmt_create   datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    group_id     bigint       NOT NULL,
                    uid          varchar(127) NOT NULL,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_group_member (group_id, uid),
                    KEY idx_member_uid (uid)
                ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
                """, """
                CREATE TABLE IF NOT EXISTS dm_perm_group_resource
                (
                    id             bigint       NOT NULL AUTO_INCREMENT,
                    gmt_create     datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified   datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    group_id       bigint       NOT NULL,
                    auth_kind      varchar(64)  NOT NULL,
                    res_id         bigint       NOT NULL,
                    res_path       varchar(512) NOT NULL,
                    res_auth_label text         NULL,
                    start_time     datetime     NULL,
                    end_time       datetime     NULL,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_group_resource (group_id, auth_kind, res_id, res_path),
                    KEY idx_group_resource_group (group_id)
                ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
                """, """
                CREATE TABLE IF NOT EXISTS dm_perm_group_grant_record
                (
                    id                 bigint       NOT NULL AUTO_INCREMENT,
                    gmt_create         datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified       datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    group_id           bigint       NOT NULL,
                    group_resource_id  bigint       NOT NULL,
                    member_uid         varchar(127) NOT NULL,
                    auth_res_id        bigint       NOT NULL,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_grant_record (group_resource_id, member_uid),
                    KEY idx_grant_group (group_id),
                    KEY idx_grant_auth_res (auth_res_id)
                ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
                """, """
                CREATE TABLE IF NOT EXISTS dm_logical_db
                (
                    id            bigint       NOT NULL AUTO_INCREMENT,
                    gmt_create    datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified  datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    resource_code varchar(64)  NOT NULL,
                    resource_name varchar(127) NOT NULL,
                    description   varchar(512) NULL,
                    status        varchar(32)  NOT NULL,
                    creator_uid   varchar(127) NOT NULL,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_logical_db_code (resource_code)
                ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
                """, """
                CREATE TABLE IF NOT EXISTS dm_logical_db_env_binding
                (
                    id            bigint       NOT NULL AUTO_INCREMENT,
                    gmt_create    datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified  datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    logical_db_id bigint       NOT NULL,
                    env_id        bigint       NOT NULL,
                    ds_id         bigint       NOT NULL,
                    res_path      varchar(512) NOT NULL,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_logical_db_env (logical_db_id, env_id),
                    KEY idx_binding_env (env_id),
                    KEY idx_binding_ds (ds_id)
                ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
                """, """
                CREATE TABLE IF NOT EXISTS dm_db_change_stmt_version
                (
                    id           bigint       NOT NULL AUTO_INCREMENT,
                    gmt_create   datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    ticket_id    bigint       NOT NULL,
                    stmt_index   int          NOT NULL,
                    stmt_version int          NOT NULL,
                    stmt_text    longtext     NOT NULL,
                    stmt_hash    varchar(64)  NOT NULL,
                    source       varchar(32)  NOT NULL,
                    fail_reason  text         NULL,
                    operator_uid  varchar(127) NOT NULL,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_stmt_version (ticket_id, stmt_index, stmt_version)
                ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
                """, """
                CREATE TABLE IF NOT EXISTS dm_db_change_revision
                (
                    id                 bigint       NOT NULL AUTO_INCREMENT,
                    gmt_create         datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified       datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    revision_code      varchar(64)  NOT NULL,
                    logical_db_id      bigint       NOT NULL,
                    env_id             bigint       NOT NULL,
                    source_type        varchar(32)  NOT NULL,
                    source_ticket_id   bigint       NOT NULL,
                    change_type        varchar(16)  NOT NULL,
                    sql_text           longtext     NOT NULL,
                    rollback_sql_text  longtext     NULL,
                    sql_hash           varchar(64)  NOT NULL,
                    rollback_sql_hash  varchar(64)  NULL,
                    stmt_manifest      longtext     NOT NULL,
                    audit_snapshot     longtext     NULL,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_revision_code (revision_code),
                    UNIQUE KEY uk_source_ticket (source_ticket_id),
                    KEY idx_revision_logical_db (logical_db_id)
                ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
                """, """
                CREATE TABLE IF NOT EXISTS dm_db_change_promotion
                (
                    id                 bigint       NOT NULL AUTO_INCREMENT,
                    gmt_create         datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified       datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    promotion_code     varchar(64)  NOT NULL,
                    promotion_type     varchar(32)  NOT NULL,
                    revision_id        bigint       NOT NULL,
                    logical_db_id      bigint       NOT NULL,
                    prod_env_id        bigint       NOT NULL,
                    prod_ds_id         bigint       NOT NULL,
                    prod_res_path      varchar(512) NOT NULL,
                    prod_approval_id   bigint       NULL,
                    execution_key      varchar(64)  NOT NULL,
                    gate_result        longtext     NULL,
                    preflight_result   longtext     NULL,
                    status             varchar(32)  NOT NULL,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_promotion_code (promotion_code),
                    UNIQUE KEY uk_promotion_revision (revision_id),
                    UNIQUE KEY uk_execution_key (execution_key),
                    KEY idx_promotion_approval (prod_approval_id)
                ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
                """, """
                CREATE TABLE IF NOT EXISTS dm_db_change_event
                (
                    id           bigint       NOT NULL AUTO_INCREMENT,
                    gmt_create   datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    gmt_modified datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    promotion_id bigint       NULL,
                    revision_id  bigint       NULL,
                    ticket_id    bigint       NULL,
                    event_type   varchar(64)  NOT NULL,
                    from_status  varchar(32)  NULL,
                    to_status    varchar(32)  NULL,
                    operator_uid varchar(127) NOT NULL,
                    event_data   longtext     NULL,
                    PRIMARY KEY (id),
                    KEY idx_event_promotion (promotion_id, gmt_create),
                    KEY idx_event_revision (revision_id),
                    KEY idx_event_ticket (ticket_id, gmt_create)
                ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
                """);
    }
}
