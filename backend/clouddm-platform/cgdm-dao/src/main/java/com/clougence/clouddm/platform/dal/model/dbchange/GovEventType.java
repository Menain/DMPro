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
    RELEASE_DONE
}
