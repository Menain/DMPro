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
package com.clougence.clouddm.console.web.model.fo.govticket;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = false)
public class GovTicketV2GroupIdFO {

    @NotNull
    private Long groupId;

    /**
     * B7 N2 additive (design §7.4): the window-crash manual verdict — {@code RERUN_BATCH} ∨
     * {@code SKIP_BATCH}. Nullable; REQUIRED only when the retry target is a DML_BATCH segment
     * whose frozen summary carries {@code unresolvedIntent} (missing/invalid → rejected with
     * GOV_TICKET_V2_DML_BATCH_RESOLUTION_REQUIRED — the B6 RETRY_UNSUPPORTED narrowing precedent).
     * Every other group/segment shape ignores it (legacy callers send nothing — the FO's
     * ignoreUnknown=false only guards UNKNOWN keys, a new declared field is wire-additive).
     */
    private String dmlBatchResolution;
}
