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
 * DingTalk approval bridge outbound (task 10-09-dingtalk-approval-bridge, design §3.1):
 * four RUNTIME-state columns on dm_approval tracking the outbound flows/create lifecycle
 * of External-channel approval rows (PROD_DML DIRECT tickets and DM_PROD_RELEASE approval
 * tickets — the outbound subject is uniformly the dm_approval row).
 * <p>
 * State machine: NULL/NOT_STARTED → STARTING → STARTED | FAILED. FAILED rows are re-sent
 * by the compensation sweep while {@code outbound_retry_count < 3} and
 * {@code outbound_next_retry_at <= now} (backoff 1m/5m/15m); terminal stop = FAILED with
 * retry_count at the cap or next_retry_at NULL (non-retryable 4xx contract error).
 * {@code outbound_error} keeps the latest failure cause (truncated to 512). The columns
 * are written ONLY by the outbound machinery and NEVER touch gmt_modified (it doubles as
 * the scheduler lease field — the EXEC_FAIL idle-spin lesson).
 * <p>
 * Pure addition — no existing tables or columns are touched; appro_identity keeps its
 * "write only when currently empty" contract (the outbound flowId backfill and the inbound
 * command externalFlowId are dual sources, first-writer-wins). safeExecute ignores error
 * code 1060 (duplicate column). Rollback = ALTER TABLE dm_approval DROP COLUMN
 * outbound_status, DROP COLUMN outbound_error, DROP COLUMN outbound_retry_count,
 * DROP COLUMN outbound_next_retry_at (transparent to older binaries — the columns are
 * never read by pre-bridge code).
 */
public class V202610090001__dm_approval_outbound_bridge extends AbstractUpgradeJavaMigration {

    @Override
    public List<String> collectScript() {
        return List.of("""
                ALTER TABLE dm_approval ADD COLUMN outbound_status varchar(32) NULL
                """, """
                ALTER TABLE dm_approval ADD COLUMN outbound_error varchar(512) NULL
                """, """
                ALTER TABLE dm_approval ADD COLUMN outbound_retry_count int NULL DEFAULT 0
                """, """
                ALTER TABLE dm_approval ADD COLUMN outbound_next_retry_at datetime NULL
                """);
    }
}
