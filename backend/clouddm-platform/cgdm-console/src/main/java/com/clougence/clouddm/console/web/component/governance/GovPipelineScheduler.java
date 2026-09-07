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

import org.springframework.stereotype.Service;

import com.clougence.clouddm.api.common.boot.UnifiedPostConstruct;
import com.clougence.clouddm.console.web.service.governance.GovAutoAdvanceService;
import com.clougence.clouddm.console.web.service.governance.GovFailureNotifyService;
import com.clougence.clouddm.console.web.service.governance.RevisionFreezeService;
import com.clougence.utils.ThreadUtils;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * Governance pipeline daemon — mirrors ApprovalTaskScheduler skeleton (B1).
 * Single daemon thread, while(true) loop, 1s safeSleep, Throwable guard.
 * Each responsibility runs in its own try-catch so one failure doesn't block the other.
 *
 * Phase 4: advancePreTickets (duty 1) + freezeFinishedRevisions (duty 2).
 * Phase 5 hook: notifyFailedStatements.
 * Phase 6 hook: syncPromotionStatus.
 */
@Slf4j
@Service
public class GovPipelineScheduler implements UnifiedPostConstruct {

    @Resource
    private GovAutoAdvanceService  govAutoAdvanceService;
    @Resource
    private RevisionFreezeService  revisionFreezeService;
    @Resource
    private GovFailureNotifyService govFailureNotifyService;

    @Override
    public void init() throws Exception {
        ThreadUtils.runDaemonThread(this::loopSchedule);
        log.info("GovPipelineScheduler started");
    }

    @Override
    public void stop() {
        // daemon thread exits with JVM — no explicit stop needed (mirrors ApprovalStarter pattern)
    }

    private void loopSchedule() {
        while (true) {
            try {
                doSchedule();
                if (Thread.currentThread().isInterrupted()) {
                    log.warn("[GovPipeline] thread exit");
                    return;
                }
                ThreadUtils.safeSleep(1000);
            } catch (Throwable e) {
                log.error("[GovPipeline] error " + e.getMessage(), e);
            }
        }
    }

    private void doSchedule() {
        // Duty 1: SYSTEM auto-approve + auto-confirm for PRE governance tickets
        try {
            govAutoAdvanceService.advancePreTickets();
        } catch (Throwable e) {
            log.error("[GovPipeline] advancePreTickets error", e);
        }

        // Duty 2: freeze revisions from FINISHED PRE governance tickets
        try {
            revisionFreezeService.freezeFinishedRevisions();
        } catch (Throwable e) {
            log.error("[GovPipeline] freezeFinishedRevisions error", e);
        }

        // Duty 3: notify failed statements (Phase 5)
        try {
            govFailureNotifyService.scanAndNotify();
        } catch (Throwable e) {
            log.error("[GovPipeline] scanAndNotify error", e);
        }

        // Phase 6: syncPromotionStatus();
    }
}
