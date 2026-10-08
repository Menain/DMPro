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
package com.clougence.clouddm.console.web.controller.dmlbatch;

import static com.clougence.clouddm.sdk.security.auth.def.SecRoleAuthLabel.DM_GHOST_EXEC;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.clougence.clouddm.api.common.rpc.ResWebData;
import com.clougence.clouddm.api.common.rpc.ResWebDataUtils;
import com.clougence.clouddm.console.web.constants.DmControllerUrlPrefix;
import com.clougence.clouddm.console.web.global.jwtsession.RequestAuth;
import com.clougence.clouddm.console.web.model.fo.dmlbatch.DmlBatchControlFO;
import com.clougence.clouddm.console.web.service.auth.RdpUserService;
import com.clougence.clouddm.console.web.service.dmlbatch.DmlBatchControlService;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

/**
 * B7 N2 lock-free DML control plane (design §6.1, T15 — the GhostController mirror). All three
 * operations are gated by {@code DM_GHOST_EXEC} (裁决 #15 用户亲裁: REUSE the lock-free-change
 * permission, no new label — its copy widened to "DDL/DML" in the same change; the submitter
 * needs none of this: auto-routing is a system action, and the ticket-chain retryGroup keeps its
 * own RefPrimaryOrMaintainer gate with frontend button visibility on DM_GHOST_EXEC — N3).
 * <p>
 * NO op-audit rows here (裁决 #13: pause/resume/stop are ticket-chain button actions — the event
 * table trails them, consistent with the ticket-chain HTTP surface status quo; the system-side
 * DML_BATCH_EXECUTE dispatch/resolution audits are the exceptions, written service-side).
 * The dock's raw task operations are guard-rejected for ticket-bound DML_BATCH tasks (§6.5 —
 * ALL FOUR including resume, stricter than the B6 ghost three: the batch isPause state machine
 * keeps a SINGLE entry), making this controller the only legitimate control surface.
 */
@RestController
@RequestMapping(value = DmControllerUrlPrefix.CONSOLE_PREFIX + "/dmlBatch")
@Slf4j
public class DmlBatchController {

    @Resource
    private DmlBatchControlService dmlBatchControlService;

    @RequestAuth(DM_GHOST_EXEC)
    @RequestMapping(value = "/pause", method = RequestMethod.POST)
    public ResWebData<?> pause(@Valid @RequestBody DmlBatchControlFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        dmlBatchControlService.pause(puid, uid, fo.getTicketId(), fo.getGroupId(), fo.getSegmentIndex());
        return ResWebDataUtils.buildSuccess();
    }

    @RequestAuth(DM_GHOST_EXEC)
    @RequestMapping(value = "/resume", method = RequestMethod.POST)
    public ResWebData<?> resume(@Valid @RequestBody DmlBatchControlFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        dmlBatchControlService.resume(puid, uid, fo.getTicketId(), fo.getGroupId(), fo.getSegmentIndex());
        return ResWebDataUtils.buildSuccess();
    }

    /** termination = partial success KEPT + segment/group FAILED + ticket EXEC_FAIL; NO automatic rollback (三点明示). */
    @RequestAuth(DM_GHOST_EXEC)
    @RequestMapping(value = "/stop", method = RequestMethod.POST)
    public ResWebData<?> stop(@Valid @RequestBody DmlBatchControlFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        dmlBatchControlService.stop(puid, uid, fo.getTicketId(), fo.getGroupId(), fo.getSegmentIndex());
        return ResWebDataUtils.buildSuccess();
    }
}
