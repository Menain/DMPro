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

import com.clougence.clouddm.console.web.model.fo.prodrelease.GovLedgerDetailFO;
import com.clougence.clouddm.console.web.model.vo.govticket.GovTicketV2GroupVO;
import com.clougence.clouddm.console.web.model.vo.prodrelease.GovLedgerTicketVO;

/**
 * Ledger service — read-only, no tenant filtering (D6: all logged-in users can see).
 */
public interface GovLedgerService {

    /**
     * List all candidate pre-release tickets (FINISHED + PRE_DDL + all stmts SUCCESS),
     * with preDbs / prodDbs aggregated for cross-db merge into a release.
     */
    List<GovLedgerTicketVO> list(String puid);

    /**
     * Get all statement group details for a ticket across every pre-release db (read-only, no auth).
     */
    List<GovTicketV2GroupVO> detail(String puid, GovLedgerDetailFO fo);
}
