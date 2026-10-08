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
package com.clougence.clouddm.console.web.service.dmlbatch;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.console.web.model.vo.govticket.GovTicketV2CheckVO;

/**
 * B7 N2 (design §8.2, 裁决 #16; §16 未决⑦ 定形): the typed no-key large-DML submission
 * rejection. {@code GovTicketV2ServiceImpl.submit} throws THIS (instead of the plain
 * {@link ErrorMessageException}) when the authoritative precheck FAILs a group with
 * {@code failCode == DML_NO_KEY} — the user-facing message stays byte-identical (the
 * GOV_TICKET_V2_PRECHECK_FAILED wrapper), and being an ErrorMessageException subclass the
 * GlobalExceptionHandler renders it exactly like every other precheck rejection (structured 4xx
 * semantics zero change).
 * <p>
 * The payload's job: the submit transaction ROLLS BACK (no ticket row, no event chain, no deep
 * link), so the rejection context (dsId / table / rows / SQL summary ≤500) must ride the
 * exception OUT of the transaction to the controller boundary, where the
 * {@link DmlBatchRejectTrailService} writes the two survivors of the rollback — the IM alert
 * (no-tx immediate-send branch) and the DML_BATCH_REJECT audit row (a bare insert with no active
 * transaction = autocommit). The boundary rethrows the SAME instance afterwards (①② each
 * try-catch — the rejection semantics are the first priority, the trail never swallows or
 * replaces it).
 */
public class DmlNoKeyRejectException extends ErrorMessageException {

    private static final long serialVersionUID = 1L;

    private final GovTicketV2CheckVO.DmlNoKeyRejectVO reject;

    public DmlNoKeyRejectException(String errorMessage, GovTicketV2CheckVO.DmlNoKeyRejectVO reject) {
        super(errorMessage);
        this.reject = reject;
    }

    /** the rejection context (never null — thrown only when the marking seam built it). */
    public GovTicketV2CheckVO.DmlNoKeyRejectVO getReject() {
        return reject;
    }
}
