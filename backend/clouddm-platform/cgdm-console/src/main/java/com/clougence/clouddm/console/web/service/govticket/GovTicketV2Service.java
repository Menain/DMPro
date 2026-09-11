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
import com.clougence.clouddm.console.web.model.fo.govticket.GovTicketV2SubmitFO;
import com.clougence.clouddm.console.web.model.vo.dbpair.DbPairVO;
import com.clougence.clouddm.console.web.model.vo.dbpair.DbServiceVO;
import com.clougence.clouddm.console.web.model.vo.govticket.GovTicketV2CheckVO;
import com.clougence.clouddm.console.web.model.vo.govticket.GovTicketV2GroupVO;
import com.clougence.clouddm.console.web.model.vo.govticket.GovTicketV2SubmitVO;

public interface GovTicketV2Service {

    /** Read-only precheck preview (no persistence). */
    GovTicketV2CheckVO check(String puid, String uid, GovTicketV2CheckFO fo);

    /** Authoritative submit: server re-runs precheck (blocking), creates ticket + statement groups. */
    GovTicketV2SubmitVO submit(String puid, String uid, GovTicketV2SubmitFO fo);

    /** Retry a single failed group (rebuild its exec job). */
    void retryGroup(String puid, String uid, long groupId);

    /** List statement groups for a ticket (detail page). */
    List<GovTicketV2GroupVO> groupList(String puid, String uid, long ticketId);

    /** Available pairs for the ticket creation dropdown. */
    List<DbPairVO> availablePairs(String puid, String side);

    /** Available services for the ticket creation dropdown. */
    List<DbServiceVO> availableServices(String puid);
}
