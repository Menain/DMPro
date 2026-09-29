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
package com.clougence.clouddm.console.web.model.vo.ghost;

import lombok.Getter;
import lombok.Setter;

/**
 * B6 M1 ticket auto-route probe result (design §6.1): one channel-C round trip per ALTER
 * statement inside {@code runPrecheck} — B4-caliber table stats + the PK/UK existence + the
 * admin threshold hit flags.
 * <p>
 * NEVER-THROW degradation contract (R6 ruling): a probe failure (timeout / unreachable /
 * table gone) yields {@code probed=false} and the caller falls back to the status-quo direct
 * chain — a degraded probe must neither route to gh-ost nor fire an alert nor block the
 * submission.
 */
@Getter
@Setter
public class GhostRouteProbe {

    /** false = degraded probe (timeout/failure) — no route, no alert, status quo */
    private boolean probed;
    /** the target table exists in information_schema (only meaningful when probed) */
    private boolean tableFound;
    /** any NON_UNIQUE=0 index present (gh-ost hard requirement) */
    private boolean uniqueKeyPresent;

    /** B4 MySQL estimate caliber (information_schema.TABLES) */
    private Long    tableRows;
    private Long    totalBytes;

    /** true when totalBytes ≥ size threshold OR tableRows ≥ row threshold (admin KVs) */
    private boolean exceeded;

    /** the admin thresholds in effect (display/alert copy) */
    private Long    sizeThresholdBytes;
    private Long    rowThreshold;
}
