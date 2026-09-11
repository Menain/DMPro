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
package com.clougence.clouddm.console.web.service.governance.impl;

import org.springframework.stereotype.Service;

import com.clougence.clouddm.console.web.component.governance.GuardConclusion;
import com.clougence.clouddm.console.web.service.governance.GovExecutionGuardService;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.execution.RsExecAutoJobConfigObj;

/**
 * Gate-two guard entry — retained as the v2 dispatch / confirm touchpoint (design §2 red line).
 * <p>
 * P5 trim: the legacy six-gate evaluation (promotion/revision hash, binding snapshot, preflight,
 * idempotency, config compliance) was built around the now-removed legacy PROD promotion chain.
 * v2 / CI-CD tickets never carried a legacy role marker and always short-circuited to PASS at the
 * top of that chain, so the observable behavior for every live ticket is an unconditional PASS. The
 * legacy PROD path no longer exists (no writer remains), so the dead six-gate body was removed.
 * <p>
 * Touchpoint contracts preserved verbatim:
 * <ul>
 *   <li>#2 {@code checkByTicket} (prepareExecJob) — returns a conclusion; caller throws on deny.</li>
 *   <li>#3 {@code checkByJob} (dispatchJob, incl. v2 group branch) — returns a conclusion; caller
 *       deletes the job + restores on deny.</li>
 *   <li>#5 {@code assertNotGovernanceProd} (skip/continue) — legacy PROD reject; no legacy PROD
 *       tickets remain, so it is now a no-op.</li>
 * </ul>
 */
@Service
public class GovExecutionGuardServiceImpl implements GovExecutionGuardService {

    @Override
    public GuardConclusion checkByTicket(String puid, DmApprovalDO ticket, RsExecAutoJobConfigObj jobConfig) {
        return GuardConclusion.pass();
    }

    @Override
    public GuardConclusion checkByJob(String puid, long jobId) {
        return GuardConclusion.pass();
    }

    @Override
    public void assertNotGovernanceProd(DmApprovalDO ticket) {
        // Legacy PROD governance tickets no longer exist; v2 PROD_DML uses ticketType.
    }
}
