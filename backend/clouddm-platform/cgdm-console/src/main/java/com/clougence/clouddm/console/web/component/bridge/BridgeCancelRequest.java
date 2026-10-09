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
import lombok.Setter;

/**
 * flows/cancel request body (task 10-09-dingtalk-approval-bridge, design §2.2). Wire shape:
 * <pre>
 * {"bizType":"TICKET|RELEASE","bizId":"...","flowId":"&lt;nullable&gt;","reason":"LOCAL_CANCELED|LOCAL_CLOSED|RELEASE_RETURNED"}
 * </pre>
 * Best-effort semantics: 404 / already-cancelled counts as success (idempotent); failure
 * NEVER retries and never blocks the local transition — a zombie DingTalk instance is
 * neutralized by the inbound command state guards (design boundary #8).
 */
@Getter
@Setter
public class BridgeCancelRequest {

    private String bizType;
    private String bizId;
    /** the bridge-side flow id (dm_approval.appro_identity); may be null — the bridge can also resolve by (bizType,bizId). */
    private String flowId;
    private String reason;

    public BridgeCancelRequest() {
    }

    public BridgeCancelRequest(String bizType, String bizId, String flowId, String reason) {
        this.bizType = bizType;
        this.bizId = bizId;
        this.flowId = flowId;
        this.reason = reason;
    }
}
