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
package com.clougence.clouddm.platform.dal.model.execution;

import com.clougence.clouddm.api.console.autoexec.ErrorStrategy;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class RsExecAutoJobConfigObj {

    private boolean       enableTransactional;
    private ErrorStrategy errorStrategy;

    private Long          retryWaitTime;
    private Long          retryCount;
    private String        languageTag;

    /**
     * ADR-013 M2 (release-chain segment-aware dispatch, design D3): the 1-based segIdx of the
     * AUTOEXEC release segment this job executes — an ADDITIVE config-JSON key (no new column on
     * dm_exec_auto_job; the depend_on_release_stmt_id column already scopes the job to its stmt).
     * Null for every legacy/whole-stmt release job and for all ticket-chain jobs (byte-identical
     * config shape — the field is skipped on serialization when null). Observability/completion
     * cross-check only: the segment completion CAS keys on the EXECUTING+AUTOEXEC segment row and
     * the recorded jobBizId, never on this value.
     */
    private Integer       segmentIdx;
}
