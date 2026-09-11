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

import com.clougence.clouddm.console.web.model.fo.openapi.DbChangeTicketListFO;
import com.clougence.clouddm.console.web.model.fo.openapi.DbChangeTicketStmtFO;
import com.clougence.clouddm.console.web.model.fo.openapi.DbChangeReleaseDetailFO;
import com.clougence.clouddm.console.web.model.vo.openapi.OpenDbChangeTicketVO;
import com.clougence.clouddm.console.web.model.vo.openapi.OpenDbChangeTicketStmtVO;
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
}
