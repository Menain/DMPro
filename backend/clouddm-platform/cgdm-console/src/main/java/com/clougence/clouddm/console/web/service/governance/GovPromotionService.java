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

import com.clougence.clouddm.console.web.model.fo.governance.GovPromoteFO;
import com.clougence.clouddm.console.web.model.fo.governance.GovPromotionListFO;
import com.clougence.clouddm.console.web.model.vo.DmPageVO;
import com.clougence.clouddm.console.web.model.vo.governance.AvailableRevisionVO;
import com.clougence.clouddm.console.web.model.vo.governance.PromotionDetailVO;
import com.clougence.clouddm.console.web.model.vo.governance.PromotionVO;
import com.clougence.clouddm.console.web.model.vo.governance.RevisionDetailVO;

/**
 * Governance promotion service — Phase 6 (spec §4.2 path-A PROD segment, §4.4 gate-one, §6.1-③).
 * <p>
 * availableRevisions: list promotable revisions (6-filter).
 * promote: evaluate 7-gate, create promotion + PROD ticket atomically.
 * promotionList / promotionDetail: read views.
 */
public interface GovPromotionService {

    List<AvailableRevisionVO> availableRevisions(String puid, String uid);

    long promote(String puid, String uid, GovPromoteFO fo);

    DmPageVO<PromotionVO> promotionList(String puid, GovPromotionListFO fo);

    PromotionDetailVO promotionDetail(String puid, long promotionId);

    /**
     * Resolve the logical DB id for a promotion (used by the controller for audit resId).
     */
    long resolveLogicalDbId(String puid, long promotionId);

    /**
     * Read-only revision detail (spec §6.3 upper section display).
     * Visibility: same tenant + PROD resource auth — mirrors availableRevisions filter 4+5+6.
     */
    RevisionDetailVO revisionDetail(String puid, String uid, long revisionId);
}
