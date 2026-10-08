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
package com.clougence.clouddm.console.web.component.alert;

/**
 * B6 M1 (R15, design §6.2): the scene extension point of the generic alert push channel.
 * A new scene = a new enum value + its message keys — the send chain never changes.
 */
public enum AlertScene {

    /**
     * A large-table (threshold-exceeding) PRE_DDL change that cannot run lock-free: the target
     * table has no PK/UK, or the datasource is an SSH-tunnel/SSL connection (same scene family,
     * different risk-copy parameter — 2026-09-28 extended ruling). The ticket is NOT blocked;
     * it executes directly and ops + applicant are alerted through the tenant IM robots.
     */
    GHOST_LARGE_TABLE_NO_PK,

    /**
     * B7 N2 (裁决 #5/#16, design §8.2): a threshold-exceeding single-table UPDATE/DELETE whose
     * target table has NO usable PK / NOT NULL UK — unlike the ghost ALERT_PASS semantics this
     * submission is REJECTED (org spec §9.8.3: the WHERE predicate must land on a key; a keyless
     * large DML is a violation, not a risk to wave through). The ticket never lands, so the alert
     * fires at the controller boundary AFTER the submit transaction rolled back — the no-tx
     * immediate-send branch of {@link AlertPushService#pushAlert} — with NO deep link, paired
     * with the DML_BATCH_REJECT audit row (留痕与告警解耦: switch off / no messenger → the audit
     * still records alertPushed=false).
     */
    DML_LARGE_TABLE_NO_KEY,

    /**
     * B7 N2 (design §8.3): a DML_BATCH segment terminal carrying the window-crash marker
     * (summary.unresolvedIntent — the batch outcome is UNKNOWN, 裁决 #9 option C). Registered
     * inside the segment-terminal transaction (sweep REQUIRES_NEW inner tx / the completion hook
     * tx — afterCommit fires on their commit); the ticket EXISTS here, so the copy carries the
     * deep link + the segment/batch location + the manual-verdict guidance (RERUN/SKIP through
     * retryGroup).
     */
    DML_BATCH_UNRESOLVED
}
