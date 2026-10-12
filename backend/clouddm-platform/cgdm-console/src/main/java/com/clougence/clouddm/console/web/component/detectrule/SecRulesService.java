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
package com.clougence.clouddm.console.web.component.detectrule;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.console.web.global.i18n.DmI18nUtils;
import com.clougence.clouddm.console.web.global.i18n.I18nDmMsgKeys;
import com.clougence.clouddm.sdk.service.secrules.SecRulesCheckerService;

/**
 * @author mode 2020-01-20 21:04
 * @since 1.1.3
 */
public interface SecRulesService {

    void cleanCache(String checkerName);

    SecCheckerRules fetchCheckerRules(String ownerUid, long dsId);

    SecCheckerRules fetchCheckerRulesByDsId(long dsId);

    SecRulesCheckerService checkerSpi();

    /**
     * C6/M3 shared rejection gate (design §3, B2 ruling): a DANGLING masking binding (an env
     * key carries a value but no usable spec resolves and no valid legacy fallback exists)
     * means the masking policy is UNKNOWN → the query is REJECTED with an explicit i18n error,
     * never served silent plaintext. Both console entry points share this single method:
     * the main analysis chain ({@code QueryAnalysisServiceImpl.configMasking}) and the data
     * editor path ({@code DsDataEditorServiceImpl.doFetchData} — it does not flow through
     * configMasking). NOT_BOUND (never bound = explicit opt-out) passes here by design — its
     * observation event lives at the analysis-time caller (M4), not in this gate.
     */
    static void ensureMaskingBindingUsable(SecCheckerRules rules) {
        if (rules != null && rules.getMaskingBindingState() == SecCheckerRules.MaskingBindingState.DANGLING) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.MASKING_BINDING_DANGLING_ERROR.name()));
        }
    }

}
