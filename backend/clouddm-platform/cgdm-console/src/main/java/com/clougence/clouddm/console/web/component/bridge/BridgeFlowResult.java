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
package com.clougence.clouddm.console.web.component.bridge;

import lombok.Getter;

/**
 * Normalized outcome of one bridge call (task 10-09-dingtalk-approval-bridge, design §2/§3.3).
 * {@link DingBridgeClient} is never-throw: network exceptions, timeouts and non-2xx responses
 * are ALL funneled into this shape.
 * <p>
 * Error classification (drives the compensation policy, design §9 boundary #2/#3):
 * <ul>
 *   <li>4xx → {@code retryable=false} — contract error, human intervention (no auto retry);</li>
 *   <li>5xx / IO / timeout → {@code retryable=true} — the sweep retries with backoff;</li>
 *   <li>cancel 404 / already-cancelled → {@code ok=true} (idempotent success, design §2.2).</li>
 * </ul>
 */
@Getter
public class BridgeFlowResult {

    private final boolean ok;
    /** the bridge-side DingTalk approval instance id (flows/create 2xx body; null on failure / cancel-404). */
    private final String  flowId;
    /** true when the bridge deduplicated the request by (bizType,bizId) and returned an existing flow. */
    private final boolean duplicated;
    /** failure cause (null when ok). */
    private final String  error;
    /** whether the failure is worth an automatic retry (5xx/IO) or terminal (4xx contract error). */
    private final boolean retryable;

    private BridgeFlowResult(boolean ok, String flowId, boolean duplicated, String error, boolean retryable) {
        this.ok = ok;
        this.flowId = flowId;
        this.duplicated = duplicated;
        this.error = error;
        this.retryable = retryable;
    }

    public static BridgeFlowResult ok(String flowId, boolean duplicated) {
        return new BridgeFlowResult(true, flowId, duplicated, null, false);
    }

    public static BridgeFlowResult failure(String error, boolean retryable) {
        return new BridgeFlowResult(false, null, false, error, retryable);
    }
}
