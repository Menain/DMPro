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
package com.clougence.clouddm.console.web.service.governance;

/**
 * Governance promoter — auto-advance PRE governance tickets.
 * Scans WAIT_APPROVAL tickets that are governance PRE with Internal (no external template),
 * performs SYSTEM auto-approve + auto-confirm with D15 execution config routing.
 */
public interface GovAutoAdvanceService {

    /**
     * Scan non-terminal tickets, advance matching PRE governance tickets through
     * auto-approve and auto-confirm. Non-governance tickets exit at the first filter
     * (approBiz check) with zero governance-table queries.
     */
    void advancePreTickets();
}
