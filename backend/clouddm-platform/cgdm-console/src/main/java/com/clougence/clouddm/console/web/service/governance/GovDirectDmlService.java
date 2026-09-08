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

import com.clougence.clouddm.console.web.model.fo.governance.GovDirectDmlSubmitFO;
import com.clougence.clouddm.console.web.model.vo.governance.DirectDmlSubmitVO;

/**
 * Path B direct production DML submit service (Phase 8).
 * Orchestrates: four validations → threshold evaluation → same-transaction three-object creation.
 */
public interface GovDirectDmlService {

    /**
     * Submit direct production DML.
     * <p>
     * Validation chain (cheap-first, expensive-last):
     * FO smuggling → getBinding(PROD) → GOV_DML_DIRECT switch → checkResAuth →
     * split changeType==DML → rollbackSql non-empty → threshold evaluation.
     * <p>
     * Same-transaction six objects: PROD ticket → stmt_version × N → revision → promotion → event → ticketInfo.
     *
     * @return three-object summary with riskLevel
     */
    DirectDmlSubmitVO directDmlSubmit(String puid, String uid, GovDirectDmlSubmitFO fo);
}
