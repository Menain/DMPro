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
import com.clougence.clouddm.console.web.model.fo.openapi.DbChangeReleaseReturnFO;
import com.clougence.clouddm.console.web.model.fo.openapi.DbChangeTicketCommandFO;
import com.clougence.clouddm.console.web.model.fo.openapi.DbChangeTicketListFO;
import com.clougence.clouddm.console.web.model.fo.openapi.DbChangeTicketStmtFO;
import com.clougence.clouddm.console.web.model.fo.openapi.DbChangeLedgerListFO;
import com.clougence.clouddm.console.web.model.vo.openapi.OpenDbChangeTicketStmtVO;
import com.clougence.clouddm.console.web.model.vo.openapi.OpenDbChangeTicketVO;
import com.clougence.clouddm.console.web.model.vo.openapi.OpenDbChangeLedgerTicketVO;
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

    // ==================== Interface D: release return (external withdrawal sync) ====================

    @McpTool(DmMcpI18nKey.M_DBCHANGE_RELEASE_RETURN)
    @RequestAuth(strategy = RequestAuth.AuthStrategy.Ignore)
    @RequestMapping(value = "/releases/return", method = RequestMethod.POST)
    public ResWebData<?> releaseReturn(@RequestBody @Valid DbChangeReleaseReturnFO fo,
                                        HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        String requestId = (String) request.getAttribute(OpenApiSessionManager.OPEN_API_REQUEST_ID);

        log.info("[DbChangeOpenApi] releaseReturn request id={}, uid={}, releaseId={}, releaseNo={}",
            requestId, uid, fo.getReleaseId(), fo.getReleaseNo());

        govOpenApiService.returnRelease(puid, uid, fo);

        log.info("[DbChangeOpenApi] releaseReturn request id={}, completed", requestId);
        return ResWebDataUtils.buildSuccess();
    }

    // ==================== Commands: approve / reject / cancel ====================

    @McpTool(DmMcpI18nKey.M_DBCHANGE_TICKET_APPROVE)
    @RequestAuth(strategy = RequestAuth.AuthStrategy.Ignore)
    @RequestMapping(value = "/tickets/approve", method = RequestMethod.POST)
    public ResWebData<?> ticketApprove(@RequestBody @Valid DbChangeTicketCommandFO fo,
                                       HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        String requestId = (String) request.getAttribute(OpenApiSessionManager.OPEN_API_REQUEST_ID);

        log.info("[DbChangeOpenApi] ticketApprove request id={}, uid={}, ticketId={}, approver={}, externalFlowId={}",
            requestId, uid, fo.getTicketId(), fo.getApprover(), fo.getExternalFlowId());

        govOpenApiService.approveTicket(puid, uid, fo);

        log.info("[DbChangeOpenApi] ticketApprove request id={}, completed", requestId);
        return ResWebDataUtils.buildSuccess();
    }

    @McpTool(DmMcpI18nKey.M_DBCHANGE_TICKET_REJECT)
    @RequestAuth(strategy = RequestAuth.AuthStrategy.Ignore)
    @RequestMapping(value = "/tickets/reject", method = RequestMethod.POST)
    public ResWebData<?> ticketReject(@RequestBody @Valid DbChangeTicketCommandFO fo,
                                      HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        String requestId = (String) request.getAttribute(OpenApiSessionManager.OPEN_API_REQUEST_ID);

        log.info("[DbChangeOpenApi] ticketReject request id={}, uid={}, ticketId={}, approver={}",
            requestId, uid, fo.getTicketId(), fo.getApprover());

        govOpenApiService.rejectTicket(puid, uid, fo);

        log.info("[DbChangeOpenApi] ticketReject request id={}, completed", requestId);
        return ResWebDataUtils.buildSuccess();
    }

    @McpTool(DmMcpI18nKey.M_DBCHANGE_TICKET_CANCEL)
    @RequestAuth(strategy = RequestAuth.AuthStrategy.Ignore)
    @RequestMapping(value = "/tickets/cancel", method = RequestMethod.POST)
    public ResWebData<?> ticketCancel(@RequestBody @Valid DbChangeTicketCommandFO fo,
                                      HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        String requestId = (String) request.getAttribute(OpenApiSessionManager.OPEN_API_REQUEST_ID);

        log.info("[DbChangeOpenApi] ticketCancel request id={}, uid={}, ticketId={}, approver={}",
            requestId, uid, fo.getTicketId(), fo.getApprover());

        govOpenApiService.cancelTicket(puid, uid, fo);

        log.info("[DbChangeOpenApi] ticketCancel request id={}, completed", requestId);
        return ResWebDataUtils.buildSuccess();
    }

    // ==================== Command: return ====================

    @McpTool(DmMcpI18nKey.M_DBCHANGE_TICKET_RETURN)
    @RequestAuth(strategy = RequestAuth.AuthStrategy.Ignore)
    @RequestMapping(value = "/tickets/return", method = RequestMethod.POST)
    public ResWebData<?> ticketReturn(@RequestBody @Valid DbChangeTicketCommandFO fo,
                                      HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        String requestId = (String) request.getAttribute(OpenApiSessionManager.OPEN_API_REQUEST_ID);

        log.info("[DbChangeOpenApi] ticketReturn request id={}, uid={}, ticketId={}, reason={}",
            requestId, uid, fo.getTicketId(), fo.getReason());

        govOpenApiService.returnTicket(puid, uid, fo);

        log.info("[DbChangeOpenApi] ticketReturn request id={}, completed", requestId);
        return ResWebDataUtils.buildSuccess();
    }

    // ==================== Command: execute ====================

    @McpTool(DmMcpI18nKey.M_DBCHANGE_TICKET_EXECUTE)
    @RequestAuth(strategy = RequestAuth.AuthStrategy.Ignore)
    @RequestMapping(value = "/tickets/execute", method = RequestMethod.POST)
    public ResWebData<?> ticketExecute(@RequestBody @Valid DbChangeTicketCommandFO fo,
                                       HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        String requestId = (String) request.getAttribute(OpenApiSessionManager.OPEN_API_REQUEST_ID);

        log.info("[DbChangeOpenApi] ticketExecute request id={}, uid={}, ticketId={}",
            requestId, uid, fo.getTicketId());

        govOpenApiService.executeTicket(puid, uid, fo);

        log.info("[DbChangeOpenApi] ticketExecute request id={}, completed", requestId);
        return ResWebDataUtils.buildSuccess();
    }

    // ==================== Command: submit ====================

    @McpTool(DmMcpI18nKey.M_DBCHANGE_TICKET_SUBMIT)
    @RequestAuth(strategy = RequestAuth.AuthStrategy.Ignore)
    @RequestMapping(value = "/tickets/submit", method = RequestMethod.POST)
    public ResWebData<com.clougence.clouddm.console.web.model.vo.openapi.OpenDbChangeTicketSubmitVO> ticketSubmit(
        @RequestBody @Valid com.clougence.clouddm.console.web.model.fo.openapi.DbChangeTicketSubmitFO fo,
        HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        String requestId = (String) request.getAttribute(OpenApiSessionManager.OPEN_API_REQUEST_ID);

        log.info("[DbChangeOpenApi] ticketSubmit request id={}, uid={}, ownerAccount={}, ticketType={}, groupCount={}",
            requestId, uid, fo.getOwnerAccount(), fo.getTicketType(),
            fo.getGroups() != null ? fo.getGroups().size() : 0);

        com.clougence.clouddm.console.web.model.vo.openapi.OpenDbChangeTicketSubmitVO result =
            govOpenApiService.submitTicket(puid, uid, fo);

        log.info("[DbChangeOpenApi] ticketSubmit request id={}, ticketId={}, ticketStatus={}",
            requestId, result.getTicketId(), result.getTicketStatus());
        return ResWebDataUtils.buildSuccess(result);
    }

    // ==================== Command: resubmit ====================

    @McpTool(DmMcpI18nKey.M_DBCHANGE_TICKET_RESUBMIT)
    @RequestAuth(strategy = RequestAuth.AuthStrategy.Ignore)
    @RequestMapping(value = "/tickets/resubmit", method = RequestMethod.POST)
    public ResWebData<com.clougence.clouddm.console.web.model.vo.openapi.OpenDbChangeTicketSubmitVO> ticketResubmit(
        @RequestBody @Valid com.clougence.clouddm.console.web.model.fo.openapi.DbChangeTicketResubmitFO fo,
        HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        String requestId = (String) request.getAttribute(OpenApiSessionManager.OPEN_API_REQUEST_ID);

        log.info("[DbChangeOpenApi] ticketResubmit request id={}, uid={}, ticketId={}, groupCount={}",
            requestId, uid, fo.getTicketId(),
            fo.getGroups() != null ? fo.getGroups().size() : 0);

        com.clougence.clouddm.console.web.model.vo.openapi.OpenDbChangeTicketSubmitVO result =
            govOpenApiService.resubmitTicket(puid, uid, fo);

        log.info("[DbChangeOpenApi] ticketResubmit request id={}, ticketId={}, ticketStatus={}",
            requestId, result.getTicketId(), result.getTicketStatus());
        return ResWebDataUtils.buildSuccess(result);
    }

    // ==================== Command: releases/create ====================

    @McpTool(DmMcpI18nKey.M_DBCHANGE_RELEASE_CREATE)
    @RequestAuth(strategy = RequestAuth.AuthStrategy.Ignore)
    @RequestMapping(value = "/releases/create", method = RequestMethod.POST)
    public ResWebData<com.clougence.clouddm.console.web.model.vo.openapi.OpenDbChangeReleaseCreateVO> releaseCreate(
        @RequestBody @Valid com.clougence.clouddm.console.web.model.fo.openapi.DbChangeReleaseCreateFO fo,
        HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        String requestId = (String) request.getAttribute(OpenApiSessionManager.OPEN_API_REQUEST_ID);

        log.info("[DbChangeOpenApi] releaseCreate request id={}, uid={}, creatorAccount={}, ticketCount={}",
            requestId, uid, fo.getCreatorAccount(),
            fo.getTicketIds() != null ? fo.getTicketIds().size() : 0);

        com.clougence.clouddm.console.web.model.vo.openapi.OpenDbChangeReleaseCreateVO result =
            govOpenApiService.createRelease(puid, uid, fo);

        log.info("[DbChangeOpenApi] releaseCreate request id={}, releaseId={}, releaseNo={}",
            requestId, result.getReleaseId(), result.getReleaseNo());
        return ResWebDataUtils.buildSuccess(result);
    }

    // ==================== Command: releases/resubmit ====================

    @McpTool(DmMcpI18nKey.M_DBCHANGE_RELEASE_RESUBMIT)
    @RequestAuth(strategy = RequestAuth.AuthStrategy.Ignore)
    @RequestMapping(value = "/releases/resubmit", method = RequestMethod.POST)
    public ResWebData<?> releaseResubmit(
        @RequestBody @Valid com.clougence.clouddm.console.web.model.fo.openapi.DbChangeReleaseResubmitFO fo,
        HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        String requestId = (String) request.getAttribute(OpenApiSessionManager.OPEN_API_REQUEST_ID);

        log.info("[DbChangeOpenApi] releaseResubmit request id={}, uid={}, releaseId={}, ticketCount={}",
            requestId, uid, fo.getReleaseId(),
            fo.getTicketIds() != null ? fo.getTicketIds().size() : 0);

        govOpenApiService.resubmitRelease(puid, uid, fo);

        log.info("[DbChangeOpenApi] releaseResubmit request id={}, completed", requestId);
        return ResWebDataUtils.buildSuccess();
    }

    // ==================== Read: tickets/detail ====================

    @McpTool(DmMcpI18nKey.M_DBCHANGE_TICKET_DETAIL)
    @RequestAuth(strategy = RequestAuth.AuthStrategy.Ignore)
    @RequestMapping(value = "/tickets/detail", method = RequestMethod.POST)
    public ResWebData<com.clougence.clouddm.console.web.model.vo.openapi.OpenDbChangeTicketDetailVO> ticketDetail(
        @RequestBody @Valid com.clougence.clouddm.console.web.model.fo.openapi.DbChangeTicketDetailFO fo,
        HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        String requestId = (String) request.getAttribute(OpenApiSessionManager.OPEN_API_REQUEST_ID);

        log.info("[DbChangeOpenApi] ticketDetail request id={}, uid={}, ticketId={}",
            requestId, uid, fo.getTicketId());

        com.clougence.clouddm.console.web.model.vo.openapi.OpenDbChangeTicketDetailVO result =
            govOpenApiService.ticketDetail(puid, uid, fo);

        log.info("[DbChangeOpenApi] ticketDetail request id={}, ticketId={}, found={}",
            requestId, fo.getTicketId(), result != null);
        return ResWebDataUtils.buildSuccess(result);
    }

    // ==================== Read: ledger/list (ding-bridge R5, path 1 entry) ====================

    @McpTool(DmMcpI18nKey.M_DBCHANGE_LEDGER_LIST)
    @RequestAuth(strategy = RequestAuth.AuthStrategy.Ignore)
    @RequestMapping(value = "/ledger/list", method = RequestMethod.POST)
    public ResWebData<List<OpenDbChangeLedgerTicketVO>> ledgerList(@RequestBody @Valid DbChangeLedgerListFO fo,
                                                                    HttpServletRequest request) {
        String puid = (String) request.getAttribute(RdpUserService.PUID);
        String uid = (String) request.getAttribute(RdpUserService.UID);
        String requestId = (String) request.getAttribute(OpenApiSessionManager.OPEN_API_REQUEST_ID);

        log.info("[DbChangeOpenApi] ledgerList request id={}, uid={}, ticketType={}, promoted={}, pageNum={}, pageSize={}",
            requestId, uid, fo.getTicketType(), fo.getPromoted(), fo.getPageNum(), fo.getPageSize());

        List<OpenDbChangeLedgerTicketVO> result = govOpenApiService.ledgerListOpen(puid, uid, fo);

        log.info("[DbChangeOpenApi] ledgerList request id={}, result count={}", requestId, result.size());
        return ResWebDataUtils.buildSuccess(result);
    }
}
