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

import com.clougence.clouddm.console.web.component.governance.GuardConclusion;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.execution.RsExecAutoJobConfigObj;

/**
 * Gate-two guard service (Phase 7, design D1).
 * <p>
 * The guard returns a conclusion without throwing — the touchpoint method decides disposal.
 * The conclusion is persisted (preflight_result + event) before returning (design D8).
 */
public interface GovExecutionGuardService {

    /**
     * Touchpoint #2: ticket DO is already at the call site, config comes from the confirm FO.
     * Returns PASS immediately for non-governance (govRole==null) and PRE tickets — zero governance-table queries.
     */
    GuardConclusion checkByTicket(String puid, DmApprovalDO ticket, RsExecAutoJobConfigObj jobConfig);

    /**
     * Touchpoint #3: only jobId is available. The guard internally resolves job → ticket.
     */
    GuardConclusion checkByJob(String puid, long jobId);

    /**
     * Touchpoint #5: PROD governance tickets reject skip/continue (production exec set = approval set).
     * Throws ErrorMessageException directly — the disposal IS the throw.
     */
    void assertNotGovernanceProd(DmApprovalDO ticket);
}
