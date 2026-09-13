/*
 * Copyright 2026 杭州开云集致科技有限公司
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.clougence.clouddm.console.web.component.governance;

import java.util.List;

import com.clougence.clouddm.console.web.model.vo.govpatrol.GovPatrolItemVO;
import com.clougence.clouddm.console.web.model.vo.govpatrol.GovPatrolRunDetailVO;
import com.clougence.clouddm.console.web.model.vo.govpatrol.GovPatrolRunVO;

/**
 * Periodic schema drift patrol over paired pre/prod databases (sub-task C).
 * <p>
 * Reports only — never blocks any ticket or release. Identifies out-of-band
 * structural changes (DBA hand-edits, bypassed DDL) by comparing live table
 * structures on both sides of each enabled db pair.
 * <p>
 * Three diff classes: ONLY_PRE / ONLY_PROD / MISMATCH.
 * Three explain classes: EXEMPT_ACTIVE (in-flight release) /
 * PENDING_RELEASE (unpublished pre-side change, pre-leading only) /
 * UNEXPLAINED (real drift signal).
 */
public interface SchemaPatrolService {

    /**
     * Start a patrol run. Creates a RUNNING row, submits async execution,
     * and returns the run id immediately.
     *
     * @param triggerType "AUTO" or "MANUAL"
     * @param startedBy   operator uid (or "SYSTEM" for auto)
     * @param pairId      optional — patrol only this pair; null = all enabled pairs
     * @return run id, or -1 if a RUNNING run already exists (collision rejection)
     */
    long startRun(String triggerType, String startedBy, Long pairId);

    /**
     * Paginated list of patrol runs, newest first.
     */
    List<GovPatrolRunVO> listRuns(int page, int size);

    /**
     * Run detail: run summary + all diff items.
     */
    GovPatrolRunDetailVO runDetail(long runId);

    /**
     * Convenience accessor for controller tests (item list only).
     */
    List<GovPatrolItemVO> listItems(long runId);
}
