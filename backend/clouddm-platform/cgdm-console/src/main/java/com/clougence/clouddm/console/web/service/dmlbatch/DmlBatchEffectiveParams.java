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
package com.clougence.clouddm.console.web.service.dmlbatch;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * B7 lock-free DML — the FLAT effective-parameter snapshot frozen into a batch task's configData
 * at dispatch (design §11, 裁决 #19: six flat KVs, no JSON options layer; the B6 effectiveOptions
 * snapshot discipline — the parameters actually used are auditable/reproducible per task).
 * <p>
 * Field initializers carry the SAME values as the RootUserConfig @UserConfigDef defaults so a
 * bare instance is immediately usable; {@code DmlBatchServiceImpl.resolveEffectiveParams}
 * overlays the fail-safe KV reads and clamps every value into its WhiteList range (a corrupt KV
 * row can never produce an out-of-contract batch shape).
 * <p>
 * {@code maxBatchRetries} is a COMPILE-TIME constant (design §7.2 — deliberately not a KV),
 * carried in the snapshot for auditability.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class DmlBatchEffectiveParams {

    /** compile-time constant (design §7.2): automatic same-batch retries before the segment FAILs. */
    public static final int MAX_BATCH_RETRIES = 3;

    /** rows per batch (dmlBatchSize; WhiteList 1000~50000 — org spec §9.8.2 ≤5000 default). */
    private int  batchSize      = 5000;
    /** throttle knob between batches (dmlBatchIntervalMs; WhiteList 0~60000). */
    private long intervalMs     = 1000;
    /** replica-lag yield threshold (dmlLagThresholdSec; WhiteList 1~3600 — N2 consumes it). */
    private int  lagThresholdSec = 10;
    /** console-side hard budget of one batch statement (dmlBatchTimeoutSec; WhiteList 10~3600). */
    private int  timeoutSec     = 300;
    /** constant — see {@link #MAX_BATCH_RETRIES}. */
    private int  maxBatchRetries = MAX_BATCH_RETRIES;
}
