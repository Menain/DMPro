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
package com.clougence.clouddm.console.web.component.governance;

import lombok.Getter;
import lombok.Setter;

/**
 * Result of {@link RiskAssessor#assess} — carries the final risk level, the structured
 * impact scope (for JSON persistence), and a human-readable summary (for external approval
 * form fields and detail-page display).
 */
@Getter
@Setter
public class RiskAssessment {

    private RiskLevel    level;
    private ImpactScope  scope;
    private String       readableText;

    public static RiskAssessment of(RiskLevel level, ImpactScope scope, String readableText) {
        RiskAssessment result = new RiskAssessment();
        result.level = level;
        result.scope = scope;
        result.readableText = readableText;
        return result;
    }
}
