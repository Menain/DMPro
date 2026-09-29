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
package com.clougence.clouddm.console.web.service.govticket;

import java.util.List;

import com.clougence.clouddm.console.web.model.fo.govticket.GovTicketV2CheckFO;
import com.clougence.clouddm.console.web.model.fo.govticket.GovTicketV2ResubmitFO;
import com.clougence.clouddm.console.web.model.fo.govticket.GovTicketV2ReturnTicketFO;
import com.clougence.clouddm.console.web.model.fo.govticket.GovTicketV2SubmitFO;
import com.clougence.clouddm.console.web.model.vo.dbpair.DbPairVO;
import com.clougence.clouddm.console.web.model.vo.dbpair.DbServiceVO;
import com.clougence.clouddm.console.web.model.vo.govticket.GovTicketV2CheckVO;
import com.clougence.clouddm.console.web.model.vo.govticket.GovTicketV2GroupVO;
import com.clougence.clouddm.console.web.model.vo.govticket.GovTicketV2SubmitVO;

public interface GovTicketV2Service {

    /** B6 M1: dispatch-origin annotations of the ghost auto-route GHOST_EXECUTE audit row. */
    String GHOST_VIA_AUTO_ROUTE = "TICKET_AUTO_ROUTE";
    String GHOST_VIA_RETRY      = "TICKET_RETRY";

    /** Read-only precheck preview (no persistence). */
    GovTicketV2CheckVO check(String puid, String uid, GovTicketV2CheckFO fo);

    /** Authoritative submit: server re-runs precheck (blocking), creates ticket + statement groups. */
    GovTicketV2SubmitVO submit(String puid, String uid, GovTicketV2SubmitFO fo);

    /** Retry a single failed group (rebuild its exec job). */
    void retryGroup(String puid, String uid, long groupId);

    /**
     * B6 M1 (design §6.3): dispatch a ghost-marked PRE_DDL group through the GHOST tool session
     * (auto-route). Mirrors the retryGroup "build execution + markExecutionDispatched" shape:
     * launch the session bound to ticketId/groupId → group row EXECUTING → PREPARATION closed →
     * GHOST_EXECUTE audit in the submitter's name. Internal surface — callers are the SYSTEM
     * auto-advance chain and the retryGroup ghost variant, never a controller.
     *
     * @param via audit annotation of the dispatch origin (TICKET_AUTO_ROUTE / TICKET_RETRY)
     */
    void dispatchGhostGroup(com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO ticket,
                            com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO group,
                            String via);

    /** List statement groups for a ticket (detail page). */
    List<GovTicketV2GroupVO> groupList(String puid, String uid, long ticketId);

    /**
     * Return a ticket (EXEC_FAIL/WAIT_EXEC with FAILED groups) to REVISION for SQL editing.
     * Console path: enforces the three-pronged identity check (owner ∨ primary ∨ DM_DAUTH_TICKET@bindDsId).
     */
    void returnTicket(String puid, String uid, GovTicketV2ReturnTicketFO fo);

    /**
     * Return a ticket (EXEC_FAIL/WAIT_EXEC with FAILED groups) to REVISION — open command variant.
     * Skips the three-pronged identity check; tenant-level auth (AK/SK is the credential).
     * eventData.via = EXTERNAL_COMMAND. Status guards / REVISION / IM notify are reused from the console path.
     */
    void returnTicketOpenCommand(String puid, String uid, long ticketId, String reason);

    /**
     * Resubmit a REVISION ticket with modified SQL per FAILED group.
     * Re-runs authoritative precheck, writes version audit, updates sql_content in-place
     * (stmt id unchanged), resets groups to PENDING, transitions ticket back to WAIT_APPROVAL
     * where the existing scheduler auto-advance chain picks up.
     */
    void resubmit(String puid, String uid, GovTicketV2ResubmitFO fo);

    /**
     * Resubmit a REVISION ticket — open command variant.
     * Skips the owner-only identity check; tenant-level auth (AK/SK is the credential).
     * For External-channel tickets, the resubmit goes directly to WAIT_EXEC (Q2 方案 A),
     * bypassing the approval surface entirely — the original external approval still stands
     * (execution failure ≠ approval rejected). Internal/DingTalk channels use the same
     * resubmit logic as the console path (regression pin).
     */
    void resubmitOpenCommand(String puid, String uid, GovTicketV2ResubmitFO fo);

    /** Available pairs for the ticket creation dropdown. */
    List<DbPairVO> availablePairs(String puid, String side);

    /** Available services for the ticket creation dropdown. */
    List<DbServiceVO> availableServices(String puid);
}
