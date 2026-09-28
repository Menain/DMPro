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
 * with the console pass-through — one write path, write-on-change). Unlike Faker, the cancel
 * branch does NOT close the tool session: after a user stop the session log stays inspectable
 * and the platform's explicit /ghost/close (or keepLog policy) owns the release.
 */
@Slf4j
@Service
@Scope("prototype")
public class GhostAsyncTask extends AsyncTask {

    @Resource
    private GhostService ghostService;

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
                this.failedTaskDoNotPause(new IllegalStateException("ghost migration failed: " + safeMessage(taskConfig)));
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
                this.failedTaskDoNotPause(new IllegalStateException(failureReason(statusDTO)));
            } else {
                // COMPLETED / ABORTED — the result string becomes the task's final status message
                this.finishTask(terminalResult(status, statusDTO));
            }
            return;
        }

        if (this.isInterrupted()) {
            if (this.isPause()) {
                this.ghostService.pauseSession(taskConfig.getUserId(), taskConfig.getSessionId());
                this.updateMessage("interrupt by Pause");
            } else {
                // STOP already panicked gh-ost through the controller; keep the session for logs
                this.updateMessage("interrupt by Stop/Close");
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
