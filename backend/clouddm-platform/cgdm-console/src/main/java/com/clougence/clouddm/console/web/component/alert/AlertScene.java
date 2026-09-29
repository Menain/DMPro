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
    GHOST_LARGE_TABLE_NO_PK
}
