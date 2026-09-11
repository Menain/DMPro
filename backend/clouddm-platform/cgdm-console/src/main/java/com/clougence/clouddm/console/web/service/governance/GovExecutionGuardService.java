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
 * Gate-two guard service — retained as the v2 dispatch / confirm touchpoint (design §2 red line).
 * <p>
 * P5: the legacy six-gate evaluation (built around the retired PROD promotion chain) was removed;
 * the guard now passes through for every live ticket (v2 PRE_DDL / PROD_DML and CI/CD), which is
 * exactly the short-circuit those tickets always took. The touchpoint contracts below are
 * preserved verbatim so the existing call sites in {@code AutoExecServiceImpl} and
 * {@code ApprovalControlServiceImpl} stay unchanged.
 */
public interface GovExecutionGuardService {

    /**
     * Touchpoint #2: ticket DO is already at the call site, config comes from the confirm FO.
     */
    GuardConclusion checkByTicket(String puid, DmApprovalDO ticket, RsExecAutoJobConfigObj jobConfig);

    /**
     * Touchpoint #3: only jobId is available. The guard internally resolves job → ticket.
     */
    GuardConclusion checkByJob(String puid, long jobId);

    /**
     * Touchpoint #5: legacy PROD tickets rejected skip/continue (production exec set = approval set).
     * No legacy PROD tickets remain, so this is now a no-op; the wiring is kept for the call sites.
     */
    void assertNotGovernanceProd(DmApprovalDO ticket);
}
