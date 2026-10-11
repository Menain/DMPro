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
    GOV_SCHEMA_GATE_OVERRIDE_REVOKE;

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
