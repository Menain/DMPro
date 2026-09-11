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
package com.clougence.clouddm.console.web.controller.openapi;

import java.util.List;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.clougence.clouddm.api.common.rpc.ResWebData;
import com.clougence.clouddm.api.common.rpc.ResWebDataUtils;
import com.clougence.clouddm.console.web.constants.DmControllerUrlPrefix;
import com.clougence.clouddm.console.web.constants.DmMcpI18nKey;
import com.clougence.clouddm.console.web.global.jwtsession.RequestAuth;
import com.clougence.clouddm.console.web.global.mcp.McpApiProvider;
import com.clougence.clouddm.console.web.global.mcp.model.McpTool;
import com.clougence.clouddm.console.web.model.fo.openapi.DbChangeReleaseDetailFO;
import com.clougence.clouddm.console.web.model.fo.openapi.DbChangeTicketListFO;
import com.clougence.clouddm.console.web.model.fo.openapi.DbChangeTicketStmtFO;
import com.clougence.clouddm.console.web.model.vo.openapi.OpenDbChangeTicketStmtVO;
import com.clougence.clouddm.console.web.model.vo.openapi.OpenDbChangeTicketVO;
import com.clougence.clouddm.console.web.model.vo.prodrelease.ProdReleaseDetailVO;
import com.clougence.clouddm.console.web.service.auth.RdpUserService;
import com.clougence.clouddm.console.web.service.governance.GovOpenApiService;
import com.clougence.rdp.component.openapi.OpenApiSessionManager;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

/**
 * Open API for governance db-change queries (P4).
 * <p>
 * Three POST endpoints under /api/open/dbchange, authenticated by OpenApiSessionManager (AK/SK).
 * All methods use @RequestAuth(strategy=Ignore) per openapi convention (JwtInterceptor skips /api/open).
 * Structured access logging at entry point for external caller audit trail.
 */
@McpApiProvider
@RestController
@RequestMapping(value = DmControllerUrlPrefix.OPEN_API_PREFIX + "/dbchange")
@Slf4j
public class DbChangeOpenApi extends BasicApi {

    @Resource
    private GovOpenApiService govOpenApiService;

    // ==================== Interface A: tickets by dbName ====================

    @McpTool(DmMcpI18nKey.M_DBCHANGE_TICKETS)
    @RequestAuth(strategy = RequestAuth.AuthStrategy.Ignore)
    @RequestMapping(value = "/tickets", method = RequestMethod.POST)
    public ResWebData<List<OpenDbChangeTicketVO>> tickets(@RequestBody @Valid DbChangeTicketListFO fo,
                                                           HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        String requestId = (String) request.getAttribute(OpenApiSessionManager.OPEN_API_REQUEST_ID);

        log.info("[DbChangeOpenApi] tickets request id={}, uid={}, dbName={}, ticketType={}, executedOnly={}, page={}, size={}",
            requestId, uid, fo.getDbName(), fo.getTicketType(), fo.isExecutedOnly(), fo.getPage(), fo.getSize());

        List<OpenDbChangeTicketVO> result = govOpenApiService.listTickets(puid, uid, fo);
        log.info("[DbChangeOpenApi] tickets request id={}, result count={}", requestId, result.size());
        return ResWebDataUtils.buildSuccess(result);
    }

    // ==================== Interface B: statements by ticketId ====================

    @McpTool(DmMcpI18nKey.M_DBCHANGE_STATEMENTS)
    @RequestAuth(strategy = RequestAuth.AuthStrategy.Ignore)
    @RequestMapping(value = "/tickets/statements", method = RequestMethod.POST)
    public ResWebData<OpenDbChangeTicketStmtVO> ticketStatements(@RequestBody @Valid DbChangeTicketStmtFO fo,
                                                                  HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        String requestId = (String) request.getAttribute(OpenApiSessionManager.OPEN_API_REQUEST_ID);

        log.info("[DbChangeOpenApi] ticketStatements request id={}, uid={}, ticketId={}",
            requestId, uid, fo.getTicketId());

        OpenDbChangeTicketStmtVO result = govOpenApiService.ticketStatements(puid, uid, fo);
        int groupCount = result != null && result.getGroups() != null ? result.getGroups().size() : 0;
        log.info("[DbChangeOpenApi] ticketStatements request id={}, ticketId={}, groups={}",
            requestId, fo.getTicketId(), groupCount);
        return ResWebDataUtils.buildSuccess(result);
    }

    // ==================== Interface C: release detail by releaseId ====================

    @McpTool(DmMcpI18nKey.M_DBCHANGE_RELEASE)
    @RequestAuth(strategy = RequestAuth.AuthStrategy.Ignore)
    @RequestMapping(value = "/releases/detail", method = RequestMethod.POST)
    public ResWebData<ProdReleaseDetailVO> releaseDetail(@RequestBody @Valid DbChangeReleaseDetailFO fo,
                                                          HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        String requestId = (String) request.getAttribute(OpenApiSessionManager.OPEN_API_REQUEST_ID);

        log.info("[DbChangeOpenApi] releaseDetail request id={}, uid={}, releaseId={}",
            requestId, uid, fo.getReleaseId());

        ProdReleaseDetailVO result = govOpenApiService.releaseDetail(puid, uid, fo);
        int stmtGroupCount = result != null && result.getStmtGroups() != null ? result.getStmtGroups().size() : 0;
        log.info("[DbChangeOpenApi] releaseDetail request id={}, releaseId={}, stmtGroups={}",
            requestId, fo.getReleaseId(), stmtGroupCount);
        return ResWebDataUtils.buildSuccess(result);
    }
}
