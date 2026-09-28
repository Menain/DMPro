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

import java.util.ArrayList;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/**
 * Precheck outcome (design 09-17-online-ddl-activation §4.2):
 * <ul>
 * <li>{@code hardBlocks} — R3 violations; a non-empty list means lock-free mode must be disabled
 *     and the reason is the already-translated {@code reason} text (rendered verbatim, the B2
 *     privilegeHint zero-frontend-key contract);</li>
 * <li>{@code suggestions} — R4 threshold advice; informational only, the user makes the final
 *     choice (no forced routing);</li>
 * <li>{@code table} — the B4 MySQL estimate caliber the thresholds and the disk hint are computed
 *     from; null when the table probe was skipped (an earlier hard block) or the table is absent.</li>
 * </ul>
 */
@Getter
@Setter
public class GhostPrecheckVO {

    private List<GhostBlockVO>      hardBlocks  = new ArrayList<>();
    private List<GhostSuggestionVO> suggestions = new ArrayList<>();
    private GhostTableStatVO        table;
}
