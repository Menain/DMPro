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
 * R4 threshold advice entry (design §4.2 {@code suggestions:[{code,size,rows,threshold}]}):
 * emitted when the B4-caliber estimate reaches an admin-configured threshold. Informational
 * only — the user makes the final routing choice (prd Decision Log 2026-09-24).
 */
@Getter
@Setter
public class GhostSuggestionVO {

    /** stable code for frontend branching, e.g. {@code LARGE_TABLE_SUGGEST_LOCKFREE} */
    private String code;
    /** already-translated display text */
    private String reason;

    private Long   sizeBytes;
    private Long   tableRows;

    /** the admin thresholds in effect (ghostSizeThresholdMb / ghostRowThreshold) */
    private Long   sizeThresholdBytes;
    private Long   rowThreshold;
}
