/*
 * Copyright 2026 杭州开云集致科技有限公司
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.clougence.rdp.controller;

import static com.clougence.clouddm.sdk.security.auth.def.SecRoleAuthLabel.GOV_DB_PAIR_MANAGE;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.clougence.clouddm.api.common.rpc.ResWebData;
import com.clougence.clouddm.api.common.rpc.ResWebDataUtils;
import com.clougence.clouddm.console.web.component.governance.SchemaPatrolService;
import com.clougence.clouddm.console.web.constants.DmControllerUrlPrefix;
import com.clougence.clouddm.console.web.global.jwtsession.RequestAuth;
import com.clougence.clouddm.console.web.model.fo.govpatrol.GovPatrolRunDetailFO;
import com.clougence.clouddm.console.web.model.fo.govpatrol.GovPatrolRunListFO;
import com.clougence.clouddm.console.web.model.fo.govpatrol.GovPatrolRunNowFO;
import com.clougence.clouddm.console.web.model.vo.govpatrol.GovPatrolRunDetailVO;
import com.clougence.clouddm.console.web.model.vo.govpatrol.GovPatrolRunVO;
import com.clougence.clouddm.console.web.service.auth.RdpUserService;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

/**
 * Schema drift patrol controller (sub-task C).
 * <p>
 * Extends the db-pair management surface with read-only patrol reports.
 * All endpoints require {@link SecRoleAuthLabel#GOV_DB_PAIR_MANAGE} —
 * patrol is a dbpair management capability, no new permission point.
 */
@RestController
@RequestMapping(value = DmControllerUrlPrefix.CONSOLE_PREFIX)
@Slf4j
public class GovSchemaPatrolController {

    @Resource
    private SchemaPatrolService patrolService;

    @RequestAuth(GOV_DB_PAIR_MANAGE)
    @RequestMapping(value = "/govPatrol/runs", method = RequestMethod.POST)
    public ResWebData<?> runs(@Valid @RequestBody GovPatrolRunListFO fo, HttpServletRequest request) {
        List<GovPatrolRunVO> data = patrolService.listRuns(fo.getPage(), fo.getSize());
        return ResWebDataUtils.buildSuccess(data);
    }

    @RequestAuth(GOV_DB_PAIR_MANAGE)
    @RequestMapping(value = "/govPatrol/runDetail", method = RequestMethod.POST)
    public ResWebData<?> runDetail(@Valid @RequestBody GovPatrolRunDetailFO fo, HttpServletRequest request) {
        GovPatrolRunDetailVO data = patrolService.runDetail(fo.getRunId());
        return ResWebDataUtils.buildSuccess(data);
    }

    @RequestAuth(GOV_DB_PAIR_MANAGE)
    @RequestMapping(value = "/govPatrol/runNow", method = RequestMethod.POST)
    public ResWebData<?> runNow(@Valid @RequestBody GovPatrolRunNowFO fo, HttpServletRequest request) {
        String uid = (String) request.getAttribute(RdpUserService.UID);
        Long pairId = fo != null ? fo.getPairId() : null;
        long runId = patrolService.startRun("MANUAL", uid, pairId);
        if (runId < 0) {
            Map<String, Object> collision = new HashMap<>();
            collision.put("runId", -1);
            collision.put("message", "A patrol run is already in progress");
            return ResWebDataUtils.buildSuccess(collision);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("runId", runId);
        return ResWebDataUtils.buildSuccess(result);
    }
}
