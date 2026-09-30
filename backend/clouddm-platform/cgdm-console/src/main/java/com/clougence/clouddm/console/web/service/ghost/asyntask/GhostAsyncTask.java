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
                this.writeBackTicketTerminal(taskConfig, false, reason);
                this.failedTaskDoNotPause(new IllegalStateException(reason));
            } else if (isTicketBound(taskConfig)) {
                // R3 ruling (design §6.4): a TICKET-derived task whose session vanished with a
                // non-FAILED last status has an UNKNOWN outcome — judge it FAILED (retryable)
                // instead of the standalone form's optimistic COMPLETE. A FINISHED ticket over
                // an unchanged table is an unacceptable misjudgment. Standalone keeps the old shape.
                String detail = "ghost session lost before a terminal status (result unknown)";
                this.writeBackTicketTerminal(taskConfig, false, detail);
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
                this.writeBackTicketTerminal(taskConfig, false, reason);
                this.releaseTicketSession(taskConfig);
                this.failedTaskDoNotPause(new IllegalStateException(reason));
            } else {
                // COMPLETED / ABORTED — the result string becomes the task's final status message.
                // Ticket form: 终止=ABORTED → group FAILED (R5); only COMPLETED counts as success.
                boolean success = GhostService.STATUS_COMPLETED.equals(status);
                this.writeBackTicketTerminal(taskConfig, success, success ? null : terminalResult(status, statusDTO));
                this.releaseTicketSession(taskConfig);
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
                // TICKET form has no close button — the chain releases the sidecar slot here.
                this.updateMessage("interrupt by Stop/Close");
                this.releaseTicketSession(taskConfig);
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
     * B6 M1 (design §6.4): the ticket-form terminal writeback hook — the group/ticket state
     * machine mirror of handleV2JobCompletion, keyed on the configData ticketId/groupId binding.
     * Standalone tasks (binding null) skip entirely — behavior completely unchanged. The hook
     * runs BEFORE the task transition (its own transaction commits independently) and NEVER
     * breaks the task lifecycle: a writeback failure degrades to an error log and the WAIT_EXEC
     * sweep liveness probe backstops the group judgement.
     */
    private void writeBackTicketTerminal(GhostAsyncTaskConfig taskConfig, boolean success, String detail) {
        if (!isTicketBound(taskConfig)) {
            return;
        }
        try {
            this.ghostGroupCompletionService.onGhostTerminal(
                    taskConfig.getTicketId(), taskConfig.getGroupId(), taskConfig.getSegmentIndex(), success, detail);
        } catch (Exception e) {
            log.error("[GhostAsyncTask] ticket writeback failed for ticket {} group {} (the sweep probe backstops)",
                    taskConfig.getTicketId(), taskConfig.getGroupId(), e);
        }
    }

    static boolean isTicketBound(GhostAsyncTaskConfig taskConfig) {
        return taskConfig != null && taskConfig.getTicketId() != null && taskConfig.getGroupId() != null;
    }

    /**
     * Terminal session release, TICKET form only (deviation-6 closure: the chain owns the session
     * lifecycle — the ticket detail has no close button). The sidecar GHOST concurrency counter
     * (cap 2) decrements ONLY on session close, so an unclosed terminal session permanently burns
     * a slot and would starve every future auto-route dispatch after two migrations. Standalone
     * sessions keep their explicit /ghost/close path and are NEVER touched here (dual-form
     * compatibility). Best effort: a release failure only logs — the task transition proceeds.
     */
    private void releaseTicketSession(GhostAsyncTaskConfig taskConfig) {
        if (!isTicketBound(taskConfig)) {
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
