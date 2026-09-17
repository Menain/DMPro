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
package com.clougence.clouddm.console.web.service.governance;

import java.util.List;

import com.clougence.clouddm.console.web.model.fo.openapi.DbChangeReleaseDetailFO;
import com.clougence.clouddm.console.web.model.fo.openapi.DbChangeReleaseReturnFO;
import com.clougence.clouddm.console.web.model.fo.openapi.DbChangeTicketCommandFO;
import com.clougence.clouddm.console.web.model.fo.openapi.DbChangeTicketListFO;
import com.clougence.clouddm.console.web.model.fo.openapi.DbChangeTicketStmtFO;
import com.clougence.clouddm.console.web.model.fo.openapi.DbChangeTicketSubmitFO;
import com.clougence.clouddm.console.web.model.fo.openapi.DbChangeTicketResubmitFO;
import com.clougence.clouddm.console.web.model.fo.openapi.DbChangeReleaseCreateFO;
import com.clougence.clouddm.console.web.model.fo.openapi.DbChangeReleaseResubmitFO;
import com.clougence.clouddm.console.web.model.fo.openapi.DbChangeTicketDetailFO;
import com.clougence.clouddm.console.web.model.vo.openapi.OpenDbChangeTicketVO;
import com.clougence.clouddm.console.web.model.vo.openapi.OpenDbChangeTicketStmtVO;
import com.clougence.clouddm.console.web.model.vo.openapi.OpenDbChangeTicketSubmitVO;
import com.clougence.clouddm.console.web.model.vo.openapi.OpenDbChangeReleaseCreateVO;
import com.clougence.clouddm.console.web.model.vo.openapi.OpenDbChangeTicketDetailVO;
import com.clougence.clouddm.console.web.model.vo.prodrelease.ProdReleaseDetailVO;

/**
 * Governance open API service — read-only queries for external callers (DingTalk etc.).
 * <ul>
 * <li>Interface A: query db-change tickets by dbName (bidirectional pair resolution).</li>
 * <li>Interface B: query statement groups + precheck results by ticketId (tenant-filtered).</li>
 * <li>Interface C: query production release detail by releaseId (tenant-filtered).</li>
 * </ul>
 */
public interface GovOpenApiService {

    /**
     * Interface A: query db-change tickets by database name.
     * Resolves dbName bidirectionally via dm_db_pair (pre_db_name or prod_db_name),
     * aggregates by ticketId, applies optional ticketType / executedOnly filters,
     * enforces DS authorization, and returns an in-memory paginated list.
     */
    List<OpenDbChangeTicketVO> listTickets(String puid, String uid, DbChangeTicketListFO fo);

    /**
     * Interface B: query statement groups + precheck results by ticket ID.
     * Tenant-filtered: caller's puid must match ticket.primaryUid.
     * Cross-tenant calls return null (not-found semantics — existence is not leaked).
     */
    OpenDbChangeTicketStmtVO ticketStatements(String puid, String uid, DbChangeTicketStmtFO fo);

    /**
     * Interface C: query production release detail by release ID.
     * Tenant-filtered: caller's puid must match release.primaryUid.
     * Cross-tenant calls return null (not-found semantics).
     */
    ProdReleaseDetailVO releaseDetail(String puid, String uid, DbChangeReleaseDetailFO fo);

    /**
     * Interface D: return a production release (external approval-flow withdrawal sync).
     * Tenant-filtered: caller's puid must match release.primaryUid.
     * Either releaseNo or releaseId must be provided; reason is mandatory.
     * Idempotent: RETURNED/terminal releases → clear error, zero side effects.
     */
    void returnRelease(String puid, String uid, DbChangeReleaseReturnFO fo);

    /**
     * Command: approve — advance a WAIT_APPROVAL External-channel ticket to WAIT_CONFIRM
     * (release tickets also trigger handleApproved→APPROVED).
     * Tenant-filtered: cross-tenant → not-found semantics. Channel guard: non-External →
     * CHANNEL_MISMATCH. Status guard: non-WAIT_APPROVAL → INVALID_STATUS.
     */
    void approveTicket(String puid, String uid, DbChangeTicketCommandFO fo);

    /**
     * Command: reject — advance a WAIT_APPROVAL External-channel ticket to REJECTED terminal
     * (release tickets also trigger handleRejected + stmt row deletion).
     * Same tenant/channel/status guards as {@link #approveTicket}.
     */
    void rejectTicket(String puid, String uid, DbChangeTicketCommandFO fo);

    /**
     * Command: cancel — advance a WAIT_APPROVAL External-channel ticket to CANCELED terminal
     * (release tickets also trigger handleCancelled + stmt row deletion).
     * Same tenant/channel/status guards as {@link #approveTicket}.
     */
    void cancelTicket(String puid, String uid, DbChangeTicketCommandFO fo);

    /**
     * Command: return — execution-failure return to REVISION.
     * Tenant-filtered (AK/SK is the credential); skips the console path's three-pronged
     * owner/primary/DBA identity check. Status guard: EXEC_FAIL/WAIT_EXEC + FAILED group +
     * not merged. IM notification to applicant is reused from the console path.
     */
    void returnTicket(String puid, String uid, DbChangeTicketCommandFO fo);

    /**
     * Command: execute — advance a WAIT_CONFIRM External-channel ticket to WAIT_EXEC and
     * start the execution chain (ticket → createExecJob+startJob; release → startExecution).
     * Tenant-filtered; channel guard (External only); status guard (WAIT_CONFIRM only).
     * autoExecConfig is derived per ticket type DML transaction semantics.
     */
    void executeTicket(String puid, String uid, DbChangeTicketCommandFO fo);

    /**
     * Command: proxy ticket submit — create a governance ticket on behalf of a specified applicant.
     * Resolves ownerAccount → uid (tenant-internal check); delegates to GovTicketV2Service.submit.
     * Returns ticketId, bizId, and resulting ticketStatus.
     */
    OpenDbChangeTicketSubmitVO submitTicket(String puid, String uid, DbChangeTicketSubmitFO fo);

    /**
     * Command: proxy ticket resubmit — resubmit a REVISION ticket with modified SQL.
     * Skips the owner-only check (AK/SK is the credential). For External-channel tickets,
     * goes directly to WAIT_EXEC (Q2 方案 A). Returns ticketId, bizId, and resulting ticketStatus.
     */
    OpenDbChangeTicketSubmitVO resubmitTicket(String puid, String uid, DbChangeTicketResubmitFO fo);

    /**
     * Command: proxy release create — merge multiple executed tickets into a production release.
     * Resolves creatorAccount (optional, defaults to service account). Delegates to
     * ProdReleaseService.createRelease. Returns releaseId, releaseNo, and the release's ticketId.
     */
    OpenDbChangeReleaseCreateVO createRelease(String puid, String uid, DbChangeReleaseCreateFO fo);

    /**
     * Command: proxy release resubmit — resubmit a RETURNED release with a final ticket list.
     * Skips the creator/primary/maintainer permission check (AK/SK is the credential).
     * release.creatorUid unchanged; RELEASE_RESUBMITTED event operator = service account uid.
     */
    void resubmitRelease(String puid, String uid, DbChangeReleaseResubmitFO fo);

    /**
     * Read: ticket detail — base info + process list + activity list + event trail.
     * Tenant-filtered: cross-tenant → null (not-found semantics). Desensitized: no AK/SK,
     * no internal exception stacks, no console-specific permission flags.
     */
    OpenDbChangeTicketDetailVO ticketDetail(String puid, String uid, DbChangeTicketDetailFO fo);
}
