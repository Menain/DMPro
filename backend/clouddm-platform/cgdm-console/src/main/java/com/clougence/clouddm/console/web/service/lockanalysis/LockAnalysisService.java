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
package com.clougence.clouddm.console.web.service.lockanalysis;

import java.util.List;

import com.clougence.clouddm.console.web.model.fo.lockanalysis.LockOverviewFO;
import com.clougence.clouddm.console.web.model.vo.lockanalysis.LockOverviewVO;
import com.clougence.clouddm.console.web.model.vo.session.SessionInstanceVO;

/**
 * DBA lock &amp; deadlock analysis (B3): real-time lock-wait chains, running/long transactions
 * and the latest InnoDB deadlock report, all read-only through the short-lived diagnostic
 * session channel (channel C: QueryService.createSession → syncExecuteQuery → closeSession).
 */
public interface LockAnalysisService {

    /**
     * Datasources visible to the current tenant, filtered by the lock-analysis whitelist
     * (MySQL 8.0 family + PostgreSQL), sorted by display name. Reuses the B2 instance VO.
     */
    List<SessionInstanceVO> listInstances(String puid, String uid);

    /**
     * Lock/deadlock overview of the given datasource: dialect-dispatched (MySQL 8.0 / PG),
     * with block-level degradation (partialErrors) for non-privilege single-block failures.
     */
    LockOverviewVO overview(String puid, String uid, LockOverviewFO fo);
}
