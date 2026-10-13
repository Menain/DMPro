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
package com.clougence.clouddm.console.web.service.ghost.asyntask;

import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import com.clougence.clouddm.console.web.component.execute.asyntask.AsyncTask;
import com.clougence.clouddm.console.web.service.ghost.GhostService;
import com.clougence.clouddm.sdk.model.ghost.GhostStatusDTO;
import com.clougence.utils.JsonUtils;
import com.clougence.utils.StringUtils;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * GHOST tracking handler (the {@code FakerAsyncTask} pattern): polls the tool session once a
 * second, mirrors progress/message onto the dock task and drives the terminal transition —
 * this IS the Faker-style terminal writeback (FakerAsyncTask.executeTask finishTask-on-COMPLETE),
 * with the gh-ost status domain: COMPLETED → finish, FAILED → failedTaskDoNotPause (FAILURE, no
 * auto-retry pause), ABORTED → finish carrying the abort reason (user termination; the dock
 * CANCEL path usually wins the race since /ghost/stop also cancels the task).
 * <p>
 * oldTableName/lastStatus persistence rides on {@code ghostService.tailStatusForTask} (shared
 * with the console pass-through — one write path, write-on-change). Session release is split by
 * form: STANDALONE keeps the session inspectable after a stop (the explicit /ghost/close owns
 * the release, unlike Faker); TICKET-bound tasks have no close button, so every terminal
 * transition releases the session here (deviation-6 closure — the sidecar GHOST concurrency
 * slot, cap 2, is only returned on session close).
 */
@Slf4j
@Service
@Scope("prototype")
public class GhostAsyncTask extends AsyncTask {

    @Resource
    private GhostService ghostService;
    // @Lazy: governance component layer (the completion service lazy-reaches GhostService back)
    @org.springframework.context.annotation.Lazy
    @Resource
    private com.clougence.clouddm.console.web.component.governance.GhostGroupCompletionService ghostGroupCompletionService;
    // @Lazy: ADR-013 M2 release-chain segment orchestrator (the release-bound terminal branch) —
    //   safe-side proxy injection (governance component iron rule).
    @org.springframework.context.annotation.Lazy
    @Resource
    private com.clougence.clouddm.console.web.component.governance.ReleaseSegmentService releaseSegmentService;

    @Override
    protected void executeTask(int retryCnt, String configData) {
        GhostAsyncTaskConfig taskConfig = JsonUtils.toObj(configData, GhostAsyncTaskConfig.class);

        boolean hasInstance = this.ghostService.hasSession(taskConfig.getUserId(), taskConfig.getSessionId());
        if (!hasInstance) {
            if (retryCnt == 0) {
                this.updateMessage("the ghost session is not exist.");
            }
            // reflect the persisted tool state so a retry can never flip a failed migration to COMPLETE
            if (GhostService.STATUS_FAILED.equals(taskConfig.getLastStatus())) {
                String reason = "ghost migration failed: " + safeMessage(taskConfig);
                this.writeBackTerminal(taskConfig, false, reason);
                this.failedTaskDoNotPause(new IllegalStateException(reason));
            } else if (isChainBound(taskConfig)) {
                // R3 ruling (design §6.4): a CHAIN-derived task (ticket OR release bound) whose session
                // vanished with a non-FAILED last status has an UNKNOWN outcome — judge it FAILED
                // (retryable) instead of the standalone form's optimistic COMPLETE. A FINISHED ticket /
                // a SUCCESS release stmt over an unchanged table is an unacceptable misjudgment.
                // Standalone keeps the old shape.
                String detail = "ghost session lost before a terminal status (result unknown)";
                this.writeBackTerminal(taskConfig, false, detail);
                this.failedTaskDoNotPause(new IllegalStateException(detail));
            } else {
                this.finishTask(null);
            }
            return;
        }

        GhostStatusDTO statusDTO = this.ghostService.tailStatusForTask(taskConfig.getUserId(), taskConfig.getSessionId());
        String status = statusDTO == null ? null : statusDTO.getStatus();

        if (isToolTerminal(status)) {
            this.updateProcessAndMessage(statusDTO);
            this.notifyAsyncEvent();
            if (GhostService.STATUS_FAILED.equals(status)) {
                String reason = failureReason(statusDTO);
                this.writeBackTerminal(taskConfig, false, reason);
                this.releaseChainSession(taskConfig);
                this.failedTaskDoNotPause(new IllegalStateException(reason));
            } else {
                // COMPLETED / ABORTED — the result string becomes the task's final status message.
                // Chain form: 终止=ABORTED → group/stmt FAILED (R5); only COMPLETED counts as success.
                boolean success = GhostService.STATUS_COMPLETED.equals(status);
                this.writeBackTerminal(taskConfig, success, success ? null : terminalResult(status, statusDTO));
                this.releaseChainSession(taskConfig);
                this.finishTask(terminalResult(status, statusDTO));
            }
            return;
        }

        if (this.isInterrupted()) {
            // NO ticket writeback here by design (暂停不改工单/组状态): a pause keeps the group
            // EXECUTING (the throttled migration resumes through the dock), and a Stop/Close
            // termination is written back deterministically by the console stop endpoint
            // (GhostServiceImpl.stop) with the sweep liveness probe as the backstop — hooking
            // this branch would race a concurrent retryGroup rebuild (old task interrupting
            // while the fresh dispatch already moved the group back to EXECUTING).
            if (this.isPause()) {
                this.ghostService.pauseSession(taskConfig.getUserId(), taskConfig.getSessionId());
                this.updateMessage("interrupt by Pause");
            } else {
                // STOP already panicked gh-ost through the controller. Standalone keeps the
                // session for log forensics (the explicit /ghost/close owns the release); the
                // CHAIN forms (ticket / release) have no close button — the chain releases the
                // sidecar slot here.
                this.updateMessage("interrupt by Stop/Close");
                this.releaseChainSession(taskConfig);
            }

            this.updateProcessAndMessage(statusDTO);
            this.notifyAsyncEvent();
            this.finishTask(null);
            return;
        }

        // a dock-resume re-entering a still-throttled migration lifts the throttle (Faker shape)
        if (retryCnt == 0 && GhostService.STATUS_PAUSED.equals(status)) {
            this.ghostService.resumeSession(taskConfig.getUserId(), taskConfig.getSessionId());
        }

        this.updateProcessAndMessage(statusDTO);
        this.notifyAsyncEvent();
        this.delayTask(1, TimeUnit.SECONDS);
    }

    /**
     * B6 M1 (design §6.4) + ADR-013 M2 (design D4): the chain-form terminal writeback hook, routed by
     * the configData binding under a MUTUAL-EXCLUSION contract (red line 2 — the ticket path is ZERO
     * diff):
     * <ul>
     * <li>{@code ticketId != null} (ticket-bound) → {@code onGhostTerminal} (the unchanged ticket
     *     chain: group/ticket state machine mirror of handleV2JobCompletion);</li>
     * <li>{@code ticketId == null && releaseStmtId != null} (release-bound) → {@code onReleaseGhostTerminal}
     *     (the release chain: segment advance + stmt terminal + chain re-drive);</li>
     * <li>both null (standalone) → skip entirely (behavior completely unchanged);</li>
     * <li>both set → INVALID (a launch binds exactly one chain) → defensive skip + warn.</li>
     * </ul>
     * The hook runs BEFORE the task transition (its own transaction commits independently) and NEVER
     * breaks the task lifecycle: a writeback failure degrades to an error log and the WAIT_EXEC sweep
     * liveness probe backstops the judgement.
     */
    private void writeBackTerminal(GhostAsyncTaskConfig taskConfig, boolean success, String detail) {
        boolean ticketBound = isTicketBound(taskConfig);
        boolean releaseBound = isReleaseBound(taskConfig);
        if (ticketBound && releaseBound) {
            log.warn("[GhostAsyncTask] task session {} carries BOTH ticket and release bindings — invalid, "
                    + "skipping writeback (a launch binds exactly one chain)", taskConfig.getSessionId());
            return;
        }
        if (ticketBound) {
            try {
                this.ghostGroupCompletionService.onGhostTerminal(
                        taskConfig.getTicketId(), taskConfig.getGroupId(), taskConfig.getSegmentIndex(), success, detail);
            } catch (Exception e) {
                log.error("[GhostAsyncTask] ticket writeback failed for ticket {} group {} (the sweep probe backstops)",
                        taskConfig.getTicketId(), taskConfig.getGroupId(), e);
            }
        } else if (releaseBound) {
            try {
                this.releaseSegmentService.onReleaseGhostTerminal(
                        taskConfig.getReleaseStmtId(), taskConfig.getSegmentIndex(), success, detail);
            } catch (Exception e) {
                log.error("[GhostAsyncTask] release writeback failed for release stmt {} (the sweep probe backstops)",
                        taskConfig.getReleaseStmtId(), e);
            }
        }
    }

    static boolean isTicketBound(GhostAsyncTaskConfig taskConfig) {
        return taskConfig != null && taskConfig.getTicketId() != null && taskConfig.getGroupId() != null;
    }

    /** ADR-013 M2: release-chain binding (releaseStmtId present). Mutually exclusive with ticket-bound. */
    static boolean isReleaseBound(GhostAsyncTaskConfig taskConfig) {
        return taskConfig != null && taskConfig.getReleaseStmtId() != null;
    }

    /** true when the task is bound to EITHER chain (ticket ∨ release) — the session-ownership test. */
    static boolean isChainBound(GhostAsyncTaskConfig taskConfig) {
        return isTicketBound(taskConfig) || isReleaseBound(taskConfig);
    }

    /**
     * Terminal session release, CHAIN forms only (ticket ∨ release — deviation-6 closure: the chain
     * owns the session lifecycle, neither detail surface has a close button). The sidecar GHOST
     * concurrency counter (cap 2) decrements ONLY on session close, so an unclosed terminal session
     * permanently burns a slot and would starve every future auto-route/release dispatch after two
     * migrations. Standalone sessions keep their explicit /ghost/close path and are NEVER touched here
     * (dual-form compatibility; the ticket-bound release behavior is byte-identical — ADR-013 M2 only
     * widens the guard to also cover the release-bound form). Best effort: a release failure only logs.
     */
    private void releaseChainSession(GhostAsyncTaskConfig taskConfig) {
        if (!isChainBound(taskConfig)) {
            return;
        }
        try {
            this.ghostService.releaseSessionQuietly(taskConfig.getUserId(), taskConfig.getSessionId());
        } catch (Exception e) {
            log.error("[GhostAsyncTask] terminal session release failed for session {} (sidecar slot stays occupied)",
                    taskConfig.getSessionId(), e);
        }
    }

    protected void updateProcessAndMessage(GhostStatusDTO statusDTO) {
        try {
            if (statusDTO == null) {
                return;
            }
            this.updateMessage(statusDTO.getMessage());
            // maxValue==0 would divide by zero in updateProcess — the progress bar needs a total
            if (statusDTO.isUseProgress() && statusDTO.getMaxValue() > 0) {
                this.updateProcess(statusDTO.getCurValue(), statusDTO.getMaxValue());
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    static boolean isToolTerminal(String status) {
        return GhostService.STATUS_COMPLETED.equals(status) //
                || GhostService.STATUS_FAILED.equals(status) //
                || GhostService.STATUS_ABORTED.equals(status);
    }

    private static String failureReason(GhostStatusDTO statusDTO) {
        String reason = StringUtils.isNotBlank(statusDTO.getFailReason()) ? statusDTO.getFailReason() : statusDTO.getMessage();
        return "ghost migration failed: " + (reason == null ? "unknown reason" : reason);
    }

    private static String terminalResult(String status, GhostStatusDTO statusDTO) {
        if (GhostService.STATUS_ABORTED.equals(status)) {
            String reason = StringUtils.isNotBlank(statusDTO.getFailReason()) ? statusDTO.getFailReason() : "";
            return "ABORTED" + (reason.isEmpty() ? "" : ": " + reason);
        }
        String message = statusDTO.getMessage();
        return StringUtils.isBlank(message) ? status : message;
    }

    private static String safeMessage(GhostAsyncTaskConfig taskConfig) {
        return taskConfig == null ? "" : StringUtils.trimToEmpty(taskConfig.getLastStatus());
    }
}
