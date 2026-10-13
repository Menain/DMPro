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
    /**
     * ADR-011 (fail-closed): a schema gate DENIED dispatch because safety could NOT be proven
     * (infrastructure failure, missing snapshot on a new release, parse/split failure — the
     * UNVERIFIABLE conclusion). Distinct from RELEASE_SCHEMA_PRECHECK_FAILED / RELEASE_SCHEMA_DRIFT
     * which record CONFIRMED violations — remediation owners differ (platform fixes infra + retry
     * vs. author fixes SQL). eventData{stmtId,gateType,result:UNVERIFIABLE,reason,violations}.
     */
    RELEASE_SCHEMA_GATE_UNVERIFIABLE,
    /**
     * ADR-011 (governed override): a gate denial was downgraded to pass by consuming a
     * dm_gov_schema_gate_override grant (single-use CAS consumption). operator_uid = the gate
     * runner (SYSTEM on chain advance); the GRANTOR trail lives in eventData.
     * eventData{stmtId,gateType,overrideId,reason,expireAt,operator,checkResult}.
     */
    RELEASE_SCHEMA_GATE_OVERRIDE,
    /**
     * ADR-011 (S1~S5 regularization): the precheck PASSED but individual scripts/clauses could
     * not be fully verified (unclassifiable statement, regex extraction gap, FOREIGN KEY
     * exclusion, unclassified ALTER sub-clause) — the formerly SILENT fail-open surface now
     * leaves a trail. eventData{stmtId,gateType,result:PASS,notes[{code,message}]}.
     */
    RELEASE_SCHEMA_PRECHECK_UNCERTAIN,
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
     * External command REJECTED by the tri-state guards (10-11 rejected-command-audit) — a
     * late / duplicate / stale-round approve|reject|cancel hit an end status, a non-WAIT_APPROVAL
     * status, or a non-External channel: the command throws with ZERO state side effect. Distinct
     * from EXTERNAL_REJECTED, which records the approver's business decision to reject.
     * operator_uid = service account; eventData{via,command,reason,ticketStatus[,approType][,externalFlowId]};
     * written in its own REQUIRES_NEW tx so it survives the guard throw's rollback.
     */
    EXTERNAL_COMMAND_REJECTED,
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
    DML_BATCH_RESOLUTION,
    /**
     * ADR-013 M2 (release-chain segment-aware dispatch, 裁决 3): a release stmt whose frozen
     * manifest carries a DML_BATCH segment reached its dispatch turn — the release chain has NO
     * DML_BATCH executor (O-C7-1 follow-up), so the segment is BLOCKED, never silently downgraded
     * to a direct single-statement replay (red line 1). stmt → FAILED; recovery = a route override
     * (M3, explicit risk acceptance → the segment replays as AUTOEXEC) ∨ returnStmt to the ticket
     * chain (full DML_BATCH capability) ∨ the O-C7-1 executor. operator_uid = SYSTEM (dispatch scope).
     * eventData{stmtId,segIdx,table,rowsEstimated,batchKeyColumn,batchKeyType,sourceTicketId} —
     * NO SQL text (C2 eventData discipline).
     */
    RELEASE_ROUTE_UNSUPPORTED,
    /**
     * ADR-013 M2 (裁决 2, R6 capability re-check): a GHOST segment's prod replay was blocked because
     * the target environment cannot run gh-ost — the frozen routing decision is NEVER re-evaluated
     * (red line 4); only the gh-ost hard preconditions are re-verified at dispatch (prod DS dialect
     * = MySQL family, direct connection, channel-C session reachable, target table exists with a
     * PK/UK; the binary/tool availability is proven by the launch itself). Any failure → stmt FAILED
     * + this event (the failed item disclosed); recovery = a route override (M3, explicit lock-risk
     * acceptance → the segment replays as AUTOEXEC). operator_uid = SYSTEM (dispatch scope).
     * eventData{stmtId,segIdx,table,failCode,failReason}.
     */
    RELEASE_ROUTE_CAPABILITY
}
