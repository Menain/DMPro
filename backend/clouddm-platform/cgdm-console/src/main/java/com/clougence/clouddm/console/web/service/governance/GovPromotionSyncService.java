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

/**
 * Governance promotion status sync service — Phase 6 duty 4 (design D6).
 * <p>
 * Scans non-terminal promotions (including FAILED for the revival edge),
 * maps the source ticket status to the promotion target via D2 table,
 * and performs a conditional state-machine transit (idempotent).
 */
public interface GovPromotionSyncService {

    void syncPromotionStatus();
}
