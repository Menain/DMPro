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
 * ADR-013 M2 (裁决 2, R6 capability re-check): the result of re-verifying the gh-ost HARD
 * PRECONDITIONS against the prod target right before a frozen GHOST segment replays. This is a
 * CAPABILITY check only — it NEVER re-evaluates the routing decision (thresholds / row counts /
 * the boundary matrix stay frozen in the manifest; red line 4 "冻结计划是唯一路由真相源").
 * <p>
 * Items verified (design D4): prod DS dialect = MySQL family, direct connection (no SSH tunnel /
 * SSL — gh-ost speaks the plain wire protocol), the channel-C diagnostic session is reachable, and
 * the target table exists with a PK/UK. The gh-ost binary/tool availability is NOT pre-probed here
 * (it lives on the sidecar and is proven by the launch itself — a GHOST_BINARY_MISSING launch
 * failure is translated into the same RELEASE_ROUTE_CAPABILITY disposal by the caller).
 * <p>
 * Not cached: every dispatch re-verifies (the plan is frozen, the environment is not).
 */
@Getter
@Setter
public class ReleaseGhostCapability {

    /** true = every hard precondition holds → the GHOST segment may launch. */
    private boolean capable;

    /**
     * The failed item code (null when {@link #capable}): one of
     * {@code DS_NOT_FOUND | DS_TYPE_UNSUPPORTED | TUNNEL_UNSUPPORTED | CHANNEL_UNAVAILABLE |
     * TABLE_NOT_FOUND | NO_UNIQUE_KEY}. Disclosed in the RELEASE_ROUTE_CAPABILITY eventData so the
     * operator (and the M3 override grantor) knows exactly what to remediate.
     */
    private String  failCode;

    /** Human-readable failure detail (null when capable). */
    private String  failReason;

    public static ReleaseGhostCapability capable() {
        ReleaseGhostCapability c = new ReleaseGhostCapability();
        c.capable = true;
        return c;
    }

    public static ReleaseGhostCapability fail(String failCode, String failReason) {
        ReleaseGhostCapability c = new ReleaseGhostCapability();
        c.capable = false;
        c.failCode = failCode;
        c.failReason = failReason;
        return c;
    }
}
