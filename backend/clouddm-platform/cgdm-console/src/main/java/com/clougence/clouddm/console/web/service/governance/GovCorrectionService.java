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

import com.clougence.clouddm.console.web.model.fo.governance.GovCorrectStatementFO;

/**
 * Governance statement correction service — PRE failure correction loop (spec §4.6, §6.1-③, Phase 5 design D2).
 * <p>
 * Flow: auth triple → locate failed task → incremental audit (zero side-effects) →
 * stmt_version+1 (CORRECTION) + event → replaceTask → retryJob.
 */
public interface GovCorrectionService {

    long correctStatement(String puid, String uid, GovCorrectStatementFO fo);
}
