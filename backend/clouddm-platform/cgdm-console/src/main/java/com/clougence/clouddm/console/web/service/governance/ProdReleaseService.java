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
import java.util.Map;

import com.clougence.clouddm.console.web.model.fo.prodrelease.ProdReleaseCreateFO;
import com.clougence.clouddm.console.web.model.vo.prodrelease.ProdReleaseDetailVO;
import com.clougence.clouddm.console.web.model.vo.prodrelease.ProdReleaseListVO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseStmtDO;

/**
 * Production release service — merge, approve, execute, retry, reject.
 */
public interface ProdReleaseService {

    /**
     * Create a production release by merging multiple executed PRE_DDL ticket statement groups.
     * Transaction: insert release + stmt snapshots + create approval ticket + createProcess + event.
     */
    long createRelease(String puid, String uid, ProdReleaseCreateFO fo);

    /**
     * Start execution after approval confirmation.
     * Gate: re-hash each stmt's sql_content and compare with stored hash; drift = FAILED + event + reject.
     * For each prod DB group, pick the smallest-seq PENDING stmt and create a job.
     */
    void startExecution(long releaseId, String uid);

    /**
     * Retry a failed release stmt (rebuild job + start).
     * Only FAILED (or EXECUTING/PENDING with no unfinished job) stmts are accepted.
     * Release must be EXECUTING or PARTIAL_FAILED.
     */
    void retryReleaseStmt(String puid, String uid, long stmtId);

    /**
     * Handle approval: transit release APPROVING→APPROVED + event (called by ProdReleaseApprovalHandler).
     */
    void handleApproved(long releaseId);

    /**
     * Handle rejection: transit release→REJECTED, delete stmt rows (release UK locks), event.
     */
    void handleRejected(long releaseId);

    /**
     * Handle cancellation: transit release→CANCELLED, delete stmt rows, event.
     */
    void handleCancelled(long releaseId);

    /**
     * List releases for a tenant (paginated, tenant-filtered by primary_uid).
     */
    List<ProdReleaseListVO> listReleases(String puid, String status, int page, int size);

    /**
     * Get release detail (stmts grouped by prod DB, status, event timeline).
     */
    ProdReleaseDetailVO getDetail(String puid, long releaseId);
}
