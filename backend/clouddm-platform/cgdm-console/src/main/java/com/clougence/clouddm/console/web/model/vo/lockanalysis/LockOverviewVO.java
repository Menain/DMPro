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
package com.clougence.clouddm.console.web.model.vo.lockanalysis;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.Data;

/**
 * Lock/deadlock analysis overview of one datasource instance (design 09-17-lock-deadlock-analysis §4.1).
 */
@Data
public class LockOverviewVO {

    /** Dialect branch: "MYSQL_8" or "POSTGRES". */
    private String              branch;

    /** Raw VERSION() string (MySQL); PG does not probe versions — null. */
    private String              version;

    /** MySQL true / PG false — the frontend hides the deadlock section when false. */
    private boolean             deadlockSupported;

    private List<LockWaitVO>    lockWaits;

    private List<LockTrxVO>     transactions;

    /** Raw LATEST DETECTED DEADLOCK section text; null when absent/unsupported. */
    private String              deadlock;

    /** True = the status query succeeded but no deadlock was ever recorded (vs failed/unsupported). */
    private boolean             deadlockAbsent;

    /**
     * Already-i18ned hint when PG masks other sessions' query text ({@code <insufficient privileges>},
     * same contract as B2 design §13.4) — the frontend renders it verbatim in a warning Alert.
     */
    private String              privilegeHint;

    /**
     * Block-level degradation: key ∈ {lockWaits, transactions, deadlock}, value = already-i18n
     * text shown in the block's Alert. Empty when every block succeeded.
     */
    private Map<String, String> partialErrors = new LinkedHashMap<>();
}
