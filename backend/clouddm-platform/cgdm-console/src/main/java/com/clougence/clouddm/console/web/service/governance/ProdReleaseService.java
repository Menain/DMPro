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
import com.clougence.clouddm.console.web.model.fo.prodrelease.ProdReleaseReturnFO;
import com.clougence.clouddm.console.web.model.fo.prodrelease.ProdReleaseReturnStmtFO;
import com.clougence.clouddm.console.web.model.fo.prodrelease.ProdReleaseResubmitFO;
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
     * Return a FAILED release stmt to its source ticket for revision.
     * Releases uk_source_stmt + uk_execution_key (physical delete), sets the source ticket
     * to REVISION and the corresponding group to FAILED. Triggers aggregation on the release.
     * Only FAILED stmts that never executed successfully are accepted.
     */
    void returnStmt(String puid, String uid, ProdReleaseReturnStmtFO fo);

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
     * days: null or <=0 means no time filter; otherwise only releases with gmt_create >= now-days are returned.
     */
    List<ProdReleaseListVO> listReleases(String puid, String status, Integer days, int page, int size);

    /**
     * Get release detail (stmts grouped by prod DB, status, event timeline).
     * For RETURNED releases, also computes sourceTickets dry-run availability.
     */
    ProdReleaseDetailVO getDetail(String puid, long releaseId);

    /**
     * Return an entire release (APPROVING/APPROVED → RETURNED).
     * Deletes all stmt + schema rows (releases source tickets), cancels the approval
     * flow without triggering the handler dispatch, and records a RELEASE_RETURNED event.
     * Platform-side: puid must match release.primaryUid (admin/primary only via controller).
     */
    void returnRelease(String puid, String uid, long releaseId, String reason);

    /**
     * Resubmit a RETURNED release (RETURNED → APPROVING).
     * Re-runs collectSourceEntries on the remaining tickets (after optional exclusion),
     * rebuilds stmt rows + gate_result, creates a new approval flow, and backfills approvalId.
     * Any invalidated/usurped ticket raises a clear error with the ticketId — no silent drop.
     * Permission: caller must be the release creator, the primary account (uid == puid),
     * or a maintainer (isMaintainer == true).
     */
    void resubmitRelease(String puid, String uid, boolean isMaintainer, ProdReleaseResubmitFO fo);

    /**
     * Resubmit a RETURNED release — open command variant.
     * Skips the creator/primary/maintainer permission check; tenant-level auth (AK/SK is the credential).
     * release.creatorUid unchanged; RELEASE_RESUBMITTED event operator = service account uid.
     * 09-14 diff/gate_result semantics are fully reused.
     */
    void resubmitReleaseOpenCommand(String puid, String uid, ProdReleaseResubmitFO fo);

    /**
     * Cancel (discard) a RETURNED release (RETURNED → CANCELLED).
     * Terminal — no approval flow to cancel (already cancelled on return).
     * Permission: caller must be the release creator, the primary account (uid == puid),
     * or a maintainer (isMaintainer == true).
     */
    void cancelRelease(String puid, String uid, boolean isMaintainer, long releaseId);
}
