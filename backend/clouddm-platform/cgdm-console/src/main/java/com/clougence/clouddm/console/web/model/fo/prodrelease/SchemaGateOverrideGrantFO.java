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
package com.clougence.clouddm.console.web.model.fo.prodrelease;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * Grant of a governed schema-gate override (ADR-011). reason is DAL-enforced NOT NULL and
 * FO-enforced @NotBlank (no rubber-stamp grants); expireAt is an epoch-millis instant that
 * must be in the future and at most 7 days ahead (service-enforced — no permanent exemptions).
 */
@Getter
@Setter
public class SchemaGateOverrideGrantFO {

    @NotNull
    private Long   releaseId;

    /** Optional: null = release-level override; non-null = stmt-level (must belong to the release). */
    private Long   stmtId;

    /** PRECHECK | DIFF — one grant releases exactly one gate class. */
    @NotBlank
    private String gateType;

    @NotBlank
    private String reason;

    /** Epoch millis; future and ≤ now + 7 days. */
    @NotNull
    private Long   expireAt;
}
