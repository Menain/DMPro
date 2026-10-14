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
package com.clougence.clouddm.platform.dal.model.monitor;

import com.clougence.utils.JsonUtils;

import lombok.Data;

public enum AuditType {

    //****** DATA SOURCE ******
    ADD_DATA_SOURCE,
    DELETE_DATA_SOURCE,
    QUERY_DATA_SOURCE_CONFIG,
    UPDATE_DATA_SOURCE_CONFIG,
    UPDATE_DATA_SOURCE_DESC,
    UPDATE_DS_ACCOUNT_PASSWD,
    DELETE_DS_ACCOUNT_PASSWD,
    //****** QUERY RESULT ******
    EXPORT_QUERY_RESULT,
    DOWNLOAD_QUERY_RESULT,
    //******* DATA JOB *******
    QUERY_JOB_INFO,
    UPDATE_PARAMS,
    UPDATE_SUBSCRIBE,
    UPDATE_SUBSCRIBE_FULL,
    CREATE_JOB,
    START_JOB,
    STOP_JOB,
    RESTART_JOB,
    DELETE_JOB,
    MANUAL_MERGE,
    REPLAY_JOB,
    UPDATE_POSITION,
    RESET_POSITION,
    QUERY_CURRENT_POSITION,
    ATTACH_INCRE_TASK,
    DETACH_INCRE_TASK,
    ADD_REVISE,
    ADD_CHECK,
    PAUSE_SCHEDULE,
    RESUME_SCHEDULE,
    START_SCHEDULE,
    ACTIVE_FSM,
    //******* SYSTEM CONFIG *******
    ADD_SUB_ACCOUNT,
    UPDATE_SUB_ACCOUNT,
    MODIFY_SUB_ACCOUNT_AUTH,
    ENABLE_SUB_ACCOUNT,
    DISABLE_SUB_ACCOUNT,
    UPDATE_SUB_ACCOUNT_PWD,
    UPDATE_SUB_ACCOUNT_ROLE,
    DELETE_SUB_ACCOUNT,
    CREATE_ROLE,
    UPDATE_ROLE,
    DELETE_ROLE,
    ADD_DS_ENV,
    UPDATE_DS_ENV,
    DELETE_DS_ENV,
    LOGIN_SUCCESS,
    LOGIN_FAIL,
    LOGOUT,
    QUERY_ACCOUNT_AK_SK,
    RESET_ACCOUNT_AK_SK,
    UPDATE_ACCOUNT_EMAIL,
    UPDATE_ACCOUNT_PHONE,
    UPDATE_ACCOUNT_USER_NAME,
    UPDATE_ACCOUNT_PWD,
    UPDATE_ACCOUNT_OP_PWD,

    UPDATE_SYSTEM_CONFIG,
    AUTHORIZE_ACCESS_TO_ALIYUN,
    REVOKE_ACCESS_TO_ALIYUN,
    //******* PERMISSION GROUP *******
    CREATE_PERM_GROUP,
    UPDATE_PERM_GROUP,
    DELETE_PERM_GROUP,
    ENABLE_PERM_GROUP,
    DISABLE_PERM_GROUP,
    ADD_PERM_GROUP_MEMBER,
    REMOVE_PERM_GROUP_MEMBER,
    GRANT_PERM_GROUP_RESOURCE,
    REVOKE_PERM_GROUP_RESOURCE,
    //******* LOGICAL DB *******
    CREATE_LOGICAL_DB,
    UPDATE_LOGICAL_DB,
    DELETE_LOGICAL_DB,
    SET_LOGICAL_DB_BINDING,
    //******* DB CHANGE GOVERNANCE *******
    SUBMIT_DB_CHANGE_PRE,
    CORRECT_DB_CHANGE_STMT,
    PROMOTE_DB_CHANGE,
    SUBMIT_DB_CHANGE_DIRECT_DML,
    //******* SESSION MANAGE *******
    KILL_DB_SESSION,
    //******* ONLINE DDL (GH-OST) *******
    GHOST_EXECUTE,
    GHOST_PAUSE,
    GHOST_RESUME,
    GHOST_STOP,
    GHOST_DROP_OLD_TABLE,
    //******* LOCK-FREE DML (B7 batched large-table UPDATE/DELETE) *******
    /**
     * B7 four-point registration (governance-contracts audit contract): enum + RdpOpAuditServiceImpl
     * init whitelist + getResourceName DATASOURCE case (existing — resId = dsId) + label 3-language
     * properties. Written at batch-segment dispatch (HIGH, subject = ticket submitter, obj annotated
     * ticketId/groupId/segmentIndex/via — the GHOST_EXECUTE precedent). N2 reuses it for the
     * window-crash manual resolution actions (obj carries resolution + seq).
     */
    DML_BATCH_EXECUTE,
    /**
     * B7 N2 (裁决 #16, design §8.2): the audit trail of a no-key large-table DML submission
     * REJECTION. The submit transaction rolls back (no ticket row exists), so this row is written
     * OUTSIDE the transaction at the controller boundary (a bare MyBatis insert with no active
     * transaction = autocommit → survives the rollback) — the ONLY in-platform trail of the
     * rejection. HIGH, subject = the submitter, resId = dsId (ResourceType.DATASOURCE),
     * obj = {table, rows, reason:NO_KEY, sqlSummary≤500, alertPushed}. Four-point registration
     * identical to DML_BATCH_EXECUTE. Written even when the alert push is disabled / has no
     * messenger (alertPushed=false — 留痕与告警解耦).
     */
    DML_BATCH_REJECT,
    //******* SCHEMA GATE OVERRIDE (ADR-011, task 10-11-schema-gate-fail-closed) *******
    /**
     * Grant of a governed schema-gate override (dm_gov_schema_gate_override row): a permissioned
     * DBA (GOV_SCHEMA_GATE_OVERRIDE label) authorizes one future gate denial of ONE gate class
     * (PRECHECK|DIFF) to be downgraded to pass — reason mandatory, expiry ≤ 7 days, single-use
     * consumption. HIGH; resId = releaseId; obj = {releaseId, stmtId, gateType, reason, expireAt}.
     * Registration: enum + RdpOpAuditServiceImpl init whitelist + explicit resourceName via the
     * 10-arg overload (releaseNo — no ResourceType table of its own, getResourceName bypassed).
     */
    GOV_SCHEMA_GATE_OVERRIDE_GRANT,
    /** Revocation of an unconsumed override grant — same trail shape as the GRANT row. */
    GOV_SCHEMA_GATE_OVERRIDE_REVOKE,
    //******* ROUTE OVERRIDE (ADR-013 M3, task 10-12-release-chain-frozen-routing) *******
    /**
     * Grant of a governed route override (dm_gov_route_override row): a permissioned DBA
     * (GOV_ROUTE_OVERRIDE label) authorizes one future route block (DML_BATCH unsupported ∨
     * GHOST capability failure) of ONE release stmt to be released as a DIRECT single-statement
     * replay — reason mandatory, expiry ≤ 7 days, single-use consumption. HIGH; resId = releaseId;
     * obj = the grant FO {releaseId, stmtId, reason, expireAt}. Registration: enum +
     * RdpOpAuditServiceImpl init whitelist + explicit resourceName via the 10-arg overload
     * ("release:&lt;id&gt;" — the C4 GRANT twin, getResourceName bypassed). Plan/hash drift are
     * NOT overridable — integrity classes, out of this audit family's scope by design.
     */
    GOV_ROUTE_OVERRIDE_GRANT,
    /**
     * Consumption of a route override at one of the two dispatch block points — the trail of an
     * explicit risk acceptance actually taking effect. HIGH; subject = the GRANTOR (createdBy —
     * the dispatch runner is SYSTEM); resId = releaseId; obj = RouteOverrideAuditObj
     * {overrideId, releaseId, stmtId, segIdx, segmentType, blockCode, blockReason, reason,
     * createdBy, expireAt, coveredSegIdxs} — NO SQL text. Written by the consumption itself
     * (same-tx twin: the RELEASE_ROUTE_OVERRIDE event). Registration identical to the GRANT row.
     */
    GOV_ROUTE_OVERRIDE_CONSUME,
    //******* MASKING FAIL-CLOSED (C6, task 10-11-masking-fail-closed) *******
    /**
     * C6/M5 four-point registration (design §5): an EXEMPTED view of ORIGINAL (unmasked) values —
     * the DM_DAUTH_SENSITIVE-labeled query paths (without/with provenance) and the data-editor
     * table-level exemption. HIGH; subject = the exempted viewer; resId = dsId
     * (ResourceType.DATASOURCE — the existing getResourceName case); obj =
     * SensitiveOriginalViewObj {exemption, dsId, uid, resPaths≤20, queryId} — NEVER data values.
     * The root exemption deliberately does NOT land here (every-query frequency → the
     * dm_mon_biz_log MASKING_ROOT_EXEMPT tier instead — the storm-protection ruling).
     */
    SENSITIVE_ORIGINAL_VIEW,
    /**
     * C6/M3 (design §3, B2 ruling): a query REJECTED at analysis time because the masking
     * binding is DANGLING (env key bound but the spec is deleted/disabled/unparsable and no
     * valid legacy fallback exists) — the attempt is trailed even though nothing was served.
     * HIGH; resId = dsId (ResourceType.DATASOURCE); obj = SensitiveOriginalViewObj
     * {reason=DANGLING_BINDING, dsId, envId, uid}. Registration identical to
     * SENSITIVE_ORIGINAL_VIEW. Written never-throw BEFORE the i18n rejection propagates.
     */
    SENSITIVE_MASKING_REJECTED,
    //******* ROLLBACK MATERIAL (C10, task 10-14-recovery-type-grading) *******
    /**
     * C10 (design D7): one rollback-MATERIAL write — system generation (backup template),
     * applicant provision, or resubmit regeneration — trailed together with its recovery-type
     * grade. NORMAL level (a material-change trail, not a security event). resId = ticketId,
     * ResourceType.PURE_URL with the EXPLICIT resourceName "ticket:&lt;id&gt;" via the
     * oldName+chain overload (the C4/C7 bypass precedent — deliberately NO getResourceName
     * case). obj = RollbackMaterialAuditObj {source GENERATED|USER|REGENERATED, recoveryType,
     * fingerprint (GovSqlHashUtils over the FULL material text — the governance join caliber),
     * summary ≤500} — NEVER the full SQL text (the C8 obj discipline; the material itself lives
     * in dm_approval.roll_back_sql). Chain columns via AuditChainMO.ofTicket (channel NULL →
     * the sink's URI inference = the real arrival plane). The resubmit "blank + original
     * applicant-provided" branch keeps the material unchanged → NO row (no change, no trail).
     */
    ROLLBACK_MATERIAL;

    public static String genUK(AuditType type, String UUIDKey, Object resId) {
        OperationDTO operationDTO = new OperationDTO();
        operationDTO.setType(type);
        operationDTO.setKey(UUIDKey);
        operationDTO.setResId(String.valueOf(resId));
        return JsonUtils.toJson(operationDTO);
    }

    @Data
    private static class OperationDTO {

        private AuditType type;

        private String    key;

        private String    resId;
    }

}
