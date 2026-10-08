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
package com.clougence.clouddm.console.web.controller.asynctask;

import static com.clougence.clouddm.sdk.security.auth.def.SecRoleAuthLabel.DM_QUERY_CONSOLE;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.clougence.clouddm.api.common.rpc.ResWebData;
import com.clougence.clouddm.api.common.rpc.ResWebDataUtils;
import com.clougence.clouddm.console.web.component.execute.AsyncTaskService;
import com.clougence.clouddm.console.web.constants.DmControllerUrlPrefix;
import com.clougence.clouddm.console.web.global.i18n.DmI18nUtils;
import com.clougence.clouddm.console.web.global.i18n.I18nDmMsgKeys;
import com.clougence.clouddm.console.web.global.jwtsession.RequestAuth;
import com.clougence.clouddm.base.metadata.ds.tools.GhostPluginConfig;
import com.clougence.clouddm.console.web.model.fo.asyntask.ActionAsyncTaskFO;
import com.clougence.clouddm.console.web.model.vo.faker.DmAsyncTaskVO;
import com.clougence.clouddm.console.web.service.asyntask.AsyncTaskServiceService;
import com.clougence.clouddm.console.web.service.auth.RdpUserService;
import com.clougence.clouddm.console.web.service.ghost.asyntask.GhostAsyncTaskConfig;
import com.clougence.clouddm.console.web.util.DmConvertUtils;
import com.clougence.clouddm.platform.dal.access.ExecutionDal;
import com.clougence.clouddm.platform.dal.model.execution.DmExecAsyncTaskDO;
import com.clougence.utils.JsonUtils;
import com.clougence.utils.StringUtils;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

/**
 * @author mode create time is 2021/1/5
 **/
@RestController
@RequestMapping(DmControllerUrlPrefix.CONSOLE_PREFIX + "/asynctask/dockTask")
@Slf4j
public class DockTaskController {
    @Resource
    private ExecutionDal            executionDal;
    @Resource
    private AsyncTaskServiceService asyncTaskService;
    @Resource
    private AsyncTaskService        scheduleService;

    @RequestAuth(strategy = RequestAuth.AuthStrategy.Ignore)
    @RequestMapping(value = "/listDockTask", method = RequestMethod.POST)
    public ResWebData<?> listDockTask(HttpServletRequest request) {
        String uid = (String) request.getAttribute(RdpUserService.UID);

        List<DmExecAsyncTaskDO> tasks = this.asyncTaskService.listDockList(uid);
        List<DmAsyncTaskVO> vos = tasks.stream().map(DmConvertUtils::convertToDmAsyncTaskVO).collect(Collectors.toList());
        return ResWebDataUtils.buildSuccess(vos);
    }

    @RequestAuth(DM_QUERY_CONSOLE)
    @RequestMapping(value = "/cancelTask", method = RequestMethod.POST)
    public ResWebData<?> cancelTask(@Valid @RequestBody ActionAsyncTaskFO fo, HttpServletRequest request) {
        String uid = (String) request.getAttribute(RdpUserService.UID);

        DmExecAsyncTaskDO taskDO = this.executionDal.asyncTaskMapper().queryByIdAndOwnerUid(fo.getTaskId(), uid);
        if (taskDO == null) {
            return ResWebDataUtils.buildError(DmI18nUtils.getMessage(I18nDmMsgKeys.TASK_NOT_EXIST_ERROR.name(), fo.getTaskId()));
        } else if (isTicketBoundGhostTask(taskDO)) {
            // B6 M3 dock guard (M2 check 裁决项 1): a ticket-bound GHOST migration is controlled
            // from the ticket detail page only — its stop path owns the panic flag + the ticket
            // writeback + audit, while a raw dock cancel would kill the tracking task WITHOUT any
            // ticket writeback (the group stays EXECUTING until the sweep judges it dead).
            return ResWebDataUtils.buildError(DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_TASK_DOCK_OP_FORBIDDEN.name()));
        } else if (isTicketBoundDmlBatchTask(taskDO)) {
            // B7 N2 dock guard (design §6.5): a raw dock cancel would flip the task CANCEL
            // WITHOUT the ticket writeback ordering the stop endpoint guarantees (event trail +
            // the isCancel-terminal ABORTED copy); the sweep would advance the segment as a plain
            // lost-writeback failure. The ticket-chain stop endpoint is the single entry.
            return ResWebDataUtils.buildError(DmI18nUtils.getMessage(I18nDmMsgKeys.DML_BATCH_TASK_DOCK_OP_FORBIDDEN.name()));
        } else {
            this.scheduleService.cancelTask(fo.getTaskId(), DmI18nUtils.getMessage(I18nDmMsgKeys.TASK_CANCEL_AT_CONSOLE_MESSAGE.name()));
            return ResWebDataUtils.buildSuccess();
        }
    }

    @RequestAuth(DM_QUERY_CONSOLE)
    @RequestMapping(value = "/pauseTask", method = RequestMethod.POST)
    public ResWebData<?> pauseTask(@Valid @RequestBody ActionAsyncTaskFO fo, HttpServletRequest request) {
        String uid = (String) request.getAttribute(RdpUserService.UID);

        DmExecAsyncTaskDO taskDO = this.executionDal.asyncTaskMapper().queryByIdAndOwnerUid(fo.getTaskId(), uid);
        if (taskDO == null) {
            return ResWebDataUtils.buildError(DmI18nUtils.getMessage(I18nDmMsgKeys.TASK_NOT_EXIST_ERROR.name(), fo.getTaskId()));
        } else if (isTicketBoundGhostTask(taskDO)) {
            // B6 M3 dock guard (M2 check 裁决项 1 — the raw-dock-pause race): a dock pause ends the
            // tracking task (finishTask terminal) while the throttled gh-ost process stays ALIVE
            // and the group stays EXECUTING — the sweep would then judge "writeback lost" FAILED
            // over a live migration. Ticket-form pause/resume lives on the ticket detail page
            // (throttle flag, task keeps tracking). Standalone GHOST tasks are NOT affected.
            return ResWebDataUtils.buildError(DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_TASK_DOCK_OP_FORBIDDEN.name()));
        } else if (isTicketBoundDmlBatchTask(taskDO)) {
            // B7 N2 dock guard (design §6.5): the ticket-chain pause endpoint owns the batch
            // pause (batch-granularity semantics + the DML_BATCH_PAUSE event trail); a raw dock
            // pause would settle the task into PAUSE with NO trail and no ticket-chain knowledge.
            return ResWebDataUtils.buildError(DmI18nUtils.getMessage(I18nDmMsgKeys.DML_BATCH_TASK_DOCK_OP_FORBIDDEN.name()));
        } else {
            this.scheduleService.pauseTask(fo.getTaskId(), DmI18nUtils.getMessage(I18nDmMsgKeys.TASK_PAUSE_AT_CONSOLE_MESSAGE.name()));
            return ResWebDataUtils.buildSuccess();
        }
    }

    @RequestAuth(DM_QUERY_CONSOLE)
    @RequestMapping(value = "/retryTask", method = RequestMethod.POST)
    public ResWebData<?> retryTask(@Valid @RequestBody ActionAsyncTaskFO fo, HttpServletRequest request) {
        String uid = (String) request.getAttribute(RdpUserService.UID);

        DmExecAsyncTaskDO taskDO = this.executionDal.asyncTaskMapper().queryByIdAndOwnerUid(fo.getTaskId(), uid);
        if (taskDO == null) {
            return ResWebDataUtils.buildError(DmI18nUtils.getMessage(I18nDmMsgKeys.TASK_NOT_EXIST_ERROR.name(), fo.getTaskId()));
        } else if (isTicketBoundGhostTask(taskDO)) {
            // B6 M3 dock guard (extended by the M3 check — the "a retry of a terminal task is
            // harmless" claim does NOT hold): re-queueing the STALE tracker of a rebuilt segment
            // re-runs executeTask over its dead session, and the session-lost writeback passes the
            // rebuilt segment's EXECUTING CAS (the CAS keys on segmentIndex, not task identity) —
            // falsely FAILing the live migration. The recovery entry of a ticket-bound task is the
            // ticket-chain retryGroup (which rebuilds session AND task), never a dock retry.
            return ResWebDataUtils.buildError(DmI18nUtils.getMessage(I18nDmMsgKeys.GHOST_TASK_DOCK_OP_FORBIDDEN.name()));
        } else if (isTicketBoundDmlBatchTask(taskDO)) {
            // B7 N2 dock guard (design §6.5): a raw dock retry of a terminal batch task would
            // re-run its tick loop over the STALE checkpoint with the segment possibly already
            // rebuilt by retryGroup (the taskId cross-check, debt #5, is the second line of
            // defense — the guard is the first). Recovery entry = retryGroup (checkpoint
            // inheritance + the resolution guard for UNRESOLVED shapes), never a dock retry.
            return ResWebDataUtils.buildError(DmI18nUtils.getMessage(I18nDmMsgKeys.DML_BATCH_TASK_DOCK_OP_FORBIDDEN.name()));
        } else {
            this.scheduleService.retryTask(fo.getTaskId(), DmI18nUtils.getMessage(I18nDmMsgKeys.TASK_RETRY_AT_CONSOLE_MESSAGE.name()));
            return ResWebDataUtils.buildSuccess();
        }
    }

    @RequestAuth(DM_QUERY_CONSOLE)
    @RequestMapping(value = "/resumeTask", method = RequestMethod.POST)
    public ResWebData<?> resumeTask(@Valid @RequestBody ActionAsyncTaskFO fo, HttpServletRequest request) {
        String uid = (String) request.getAttribute(RdpUserService.UID);

        DmExecAsyncTaskDO taskDO = this.executionDal.asyncTaskMapper().queryByIdAndOwnerUid(fo.getTaskId(), uid);
        if (taskDO == null) {
            return ResWebDataUtils.buildError(DmI18nUtils.getMessage(I18nDmMsgKeys.TASK_NOT_EXIST_ERROR.name(), fo.getTaskId()));
        } else if (isTicketBoundDmlBatchTask(taskDO)) {
            // B7 N2 dock guard (design §6.5 — STRICTER than B6: resume is rejected too). The B6
            // ghost resume stays unguarded because its pause is a tool-level throttle with the
            // tracker still polling; the B7 pause is the framework's isPause state machine whose
            // SINGLE legitimate entry is the ticket-chain resume endpoint (with its
            // DML_BATCH_RESUME event trail and segment-state validation). A dock/ticket-chain
            // dual entry would interleave untrailed resumes with the trail-bearing ones.
            // (GHOST tasks keep the legacy unguarded resume — the ghost predicate above does not
            // match DML_BATCH and this one does not match GHOST.)
            return ResWebDataUtils.buildError(DmI18nUtils.getMessage(I18nDmMsgKeys.DML_BATCH_TASK_DOCK_OP_FORBIDDEN.name()));
        } else {
            this.scheduleService.resumeTask(fo.getTaskId(), DmI18nUtils.getMessage(I18nDmMsgKeys.TASK_RESUME_AT_CONSOLE_MESSAGE.name()));
            return ResWebDataUtils.buildSuccess();
        }
    }

    /**
     * B6 M3 dock guard predicate (M2 check 裁决项 1; retry extended by the M3 check): bizType=GHOST
     * ∧ the configData ticket binding present (ticketId non-null — the prompt caliber;
     * {@code executeForTicket} always writes the full ticketId+groupId pair, and a partial binding
     * still leans protective). Guards pause/cancel/retry: the lifecycle of a ticket migration
     * belongs to the ticket chain — a stale tracker re-run by a dock retry fires a session-lost
     * writeback that passes the rebuilt segment's EXECUTING CAS (keyed on segmentIndex, not task
     * identity) and falsely fails a live migration, so retry joins the guard. Standalone GHOST
     * tasks (no binding) and every other bizType keep the dock behavior byte-for-byte. resume
     * stays unguarded by design: re-entering a still-throttled migration lifts the throttle and
     * keeps tracking (the documented Faker-shape behavior of GhostAsyncTask — a correct, desired
     * action), and PAUSE is unreachable for new ticket-bound tasks once pause is blocked here.
     * An unparsable configData is treated as NOT ticket-bound (fail-open to the legacy dock
     * behavior; a ghost-chain task always carries a machine-written binding).
     */
    static boolean isTicketBoundGhostTask(DmExecAsyncTaskDO taskDO) {
        if (taskDO == null || !GhostPluginConfig.TOOL_NAME.equals(taskDO.getBizType())
            || StringUtils.isBlank(taskDO.getConfigData())) {
            return false;
        }
        try {
            GhostAsyncTaskConfig cfg = JsonUtils.toObj(taskDO.getConfigData(), GhostAsyncTaskConfig.class);
            return cfg != null && cfg.getTicketId() != null;
        } catch (Exception e) {
            // JsonUtils sneaky-throws the CHECKED JsonProcessingException — catch Exception
            return false;
        }
    }

    /**
     * B7 N2 dock guard predicate (design §6.5): bizType=DML_BATCH ∧ the configData ticket binding
     * present (ticketId non-null — the B6 prompt caliber verbatim). Guards ALL FOUR operations
     * (pause/cancel/retry AND resume — stricter than the ghost three: the batch isPause state
     * machine keeps a SINGLE entry, the ticket-chain control endpoints; the ghost resume stays
     * unguarded because its pause is a tool-level throttle with the tracker still polling).
     * Standalone DML_BATCH tasks DO NOT EXIST in v1 (the B8 seam keeps the binding nullable —
     * the fail-open direction is structural here); an unparsable configData fails OPEN to the
     * legacy dock behavior (a batch-chain task always carries a machine-written binding — the
     * B6 discipline verbatim).
     */
    static boolean isTicketBoundDmlBatchTask(DmExecAsyncTaskDO taskDO) {
        if (taskDO == null
            || !com.clougence.clouddm.console.web.service.dmlbatch.DmlBatchService.BIZ_TYPE.equals(taskDO.getBizType())
            || StringUtils.isBlank(taskDO.getConfigData())) {
            return false;
        }
        try {
            com.clougence.clouddm.console.web.service.dmlbatch.asyntask.DmlBatchAsyncTaskConfig cfg
                = com.clougence.utils.JsonUtils.toObj(taskDO.getConfigData(),
                    com.clougence.clouddm.console.web.service.dmlbatch.asyntask.DmlBatchAsyncTaskConfig.class);
            return cfg != null && cfg.getTicketId() != null;
        } catch (Exception e) {
            // JsonUtils sneaky-throws the CHECKED JsonProcessingException — catch Exception
            return false;
        }
    }
}
