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
}
