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
 * Duty 5 of the governance pipeline scheduler (Phase 7, design D9).
 * Auto-confirms PROD governance tickets when GOV_AUTO_CONFIRM=on.
 */
public interface GovAutoConfirmService {

    /**
     * Scan non-terminal governance PROD tickets in WAIT_CONFIRM status.
     * If the PROD env has GOV_AUTO_CONFIRM=on, call confirmTicketBySystem
     * (which naturally triggers the touchpoint #2 guard — gate-two is always on the path).
     */
    void autoConfirmProdTickets();
}
