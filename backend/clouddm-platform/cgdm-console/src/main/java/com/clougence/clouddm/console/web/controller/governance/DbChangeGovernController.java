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
package com.clougence.clouddm.console.web.controller.governance;

import static com.clougence.clouddm.platform.dal.model.monitor.SecurityLevel.HIGH;
import static com.clougence.clouddm.sdk.security.auth.def.SecRoleAuthLabel.RDP_WORKER_ORDER_REQUEST;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.clougence.clouddm.api.common.rpc.ResWebData;
import com.clougence.clouddm.api.common.rpc.ResWebDataUtils;
import com.clougence.clouddm.console.web.constants.DmControllerUrlPrefix;
import com.clougence.clouddm.console.web.global.jwtsession.RequestAuth;
import com.clougence.clouddm.console.web.model.fo.governance.GovPreSubmitFO;
import com.clougence.clouddm.console.web.model.vo.ticket.DmTicketResultVO;
import com.clougence.clouddm.console.web.service.auth.RdpUserService;
import com.clougence.clouddm.console.web.service.governance.DbChangeGovernService;
import com.clougence.clouddm.platform.dal.model.ResourceType;
import com.clougence.clouddm.platform.dal.model.monitor.AuditType;
import com.clougence.rdp.service.RdpOpAuditService;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping(value = DmControllerUrlPrefix.CONSOLE_PREFIX + "/dbChangeGovern")
@Slf4j
public class DbChangeGovernController {

    @Resource
    private DbChangeGovernService dbChangeGovernService;
    @Resource
    private RdpOpAuditService    rdpOpAuditService;

    @RequestAuth(level = HIGH, value = RDP_WORKER_ORDER_REQUEST)
    @RequestMapping(value = "/preSubmit", method = RequestMethod.POST)
    public ResWebData<?> preSubmit(@Valid @RequestBody GovPreSubmitFO fo, HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        DmTicketResultVO vo = dbChangeGovernService.preSubmit(puid, uid, fo);
        rdpOpAuditService.logAndAddOperationAudit(puid, uid, request.getRequestURI(), request.getRemoteAddr(),
            fo.getLogicalDbId(), fo, HIGH, AuditType.SUBMIT_DB_CHANGE_PRE, ResourceType.LOGICAL_DB);
        return ResWebDataUtils.buildSuccess(vo);
    }
}
