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
package com.clougence.clouddm.platform.dal.model.dbchange;

/**
 * Governance event types recorded in dm_db_change_event (append-only).
 */
public enum GovEventType {
    SUBMIT,
    SYSTEM_APPROVE,
    SYSTEM_CONFIRM,
    REVISION_FROZEN,
    FREEZE_ANOMALY,
    CORRECTION,
    FAIL_NOTIFIED,
    PROMOTION_CREATED,
    GATE_DENY,
    STATUS_SYNC,
    GUARD_PASS,
    GUARD_DENY,
    AUTO_CONFIRM,
    DIRECT_DML_SUBMIT,
    DIRECT_DML_DENY,
    RELEASE_CREATED,
    RELEASE_APPROVED,
    RELEASE_REJECTED,
    RELEASE_CANCELLED,
    RELEASE_EXEC_STARTED,
    RELEASE_STMT_SUCCESS,
    RELEASE_STMT_FAILED,
    RELEASE_HASH_DRIFT,
    RELEASE_SCHEMA_PRECHECK_FAILED,
    RELEASE_SCHEMA_PRECHECK_SKIPPED,
    RELEASE_SCHEMA_DRIFT,
    RELEASE_SCHEMA_DRIFT_WARN,
    RELEASE_SCHEMA_SNAPSHOT_MISSING,
    RELEASE_DONE,
    /** Layer 1: ticket returned to REVISION for SQL editing (operator_uid = real user). */
    TICKET_RETURNED,
    /** Layer 2: release stmt returned to source ticket for revision (operator_uid = real user). */
    RELEASE_STMT_RETURNED,
    /** Release-level return: entire release returned to RETURNED state (operator_uid = real user). */
    RELEASE_RETURNED,
    /** Release-level resubmit: RETURNED release re-merged and re-submitted to approval (operator_uid = real user). */
    RELEASE_RESUBMITTED,
    /** VIA_RELEASE source ticket completed via release execution (operator_uid = SYSTEM; eventData{releaseId, releaseNo}). */
    TICKET_VIA_RELEASE_DONE,
    /** External command: approve — ticket WAIT_APPROVAL→WAIT_CONFIRM (operator_uid = service account; eventData{via,approver,comment,externalFlowId}). */
    EXTERNAL_APPROVED,
    /** External command: reject — ticket WAIT_APPROVAL→REJECTED (operator_uid = service account; eventData{via,approver,comment,externalFlowId}). */
    EXTERNAL_REJECTED,
    /** External command: cancel — ticket WAIT_APPROVAL→CANCELED (operator_uid = service account; eventData{via,approver,comment,externalFlowId}). */
    EXTERNAL_CANCELED,
    /**
     * B6 M1 (R15): a threshold-exceeding change that CANNOT run lock-free was detected at the
     * authoritative submit/resubmit precheck — the ticket proceeds on the direct chain and an
     * IM alert push was triggered (operator_uid = submitter; eventData{scene,tables[],...}).
     * Records the alert FACT only — the send result is not persisted (platform status quo).
     */
    GHOST_ALERT_TRIGGERED,
    /**
     * B7 N2 (裁决 #13, design §6.2): ticket-chain control-plane action on a DML_BATCH segment —
     * batch-granularity pause (the in-flight batch always runs to completion; the heartbeat is
     * written on the pause-yield tick and the sweep judges a DB-PAUSE task controlled-healthy).
     * operator_uid = the real acting user; eventData{groupId,segmentIndex,taskId,taskBizId}.
     * The ticket/group/segment statuses are NOT touched (B6 throttle = session-level discipline).
     */
    DML_BATCH_PAUSE,
    /** B7 N2 (design §6.2): resume of a paused DML_BATCH task — same trail shape as DML_BATCH_PAUSE. */
    DML_BATCH_RESUME,
    /**
     * B7 N2 (design §6.4): user termination of a DML_BATCH task — completed batches are KEPT
     * (partial success, NO automatic rollback); the cancel drives segment FAILED → group FAILED
     * → ticket EXEC_FAIL through the tick's isCancel branch or (a task cancelled while idle /
     * paused) the sweep's terminal-task advance row.
     */
    DML_BATCH_ABORT,
    /**
     * B7 N2 (design §7.4, 裁决 #13): the window-crash manual resolution carried by retryGroup —
     * dmlBatchResolution ∈ {RERUN_BATCH, SKIP_BATCH} over a segment whose summary froze
     * unresolvedIntent. eventData{groupId,segmentIndex,resolution,intentSeq}; the audit twin is
     * the DML_BATCH_EXECUTE row annotated with resolution+intentSeq (四点注册复用, §12).
     */
    DML_BATCH_RESOLUTION
}
