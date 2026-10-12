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

public enum LogDependBizType {
    AUTO_EXEC_JOB,
    AUTO_EXEC_TASK,
    /**
     * B7 lock-free DML: per-batch forensic trail of a DmlBatchAsyncTask (dm_mon_biz_log,
     * dependOnBizId = the task bizId). The FIRST batch logs the full rewritten statement text,
     * every batch logs its (seq, lo, hi, affectRows, costMs) — design §3.4 injection-surface
     * forensics (magnitude = batch count, the B6 jobLog precedent). Additive enum value.
     */
    DML_BATCH_TASK,
    /**
     * C6/M4 masking observation (task 10-11-masking-fail-closed): a query ran with NO masking
     * spec bound to the env (NOT_BOUND = explicit opt-out — allowed by the opt-in activation
     * contract, but now VISIBLE). dependOnBizId = dsId; rate-limited to one row per dsId per
     * day at the write side; content carries dsId/envId only, never SQL or data values.
     */
    MASKING_NOT_BOUND,
    /**
     * C6/M5 root-exemption trail (the storm-protected tier of design §5): root skips masking on
     * EVERY query, so this lands in dm_mon_biz_log (ops side, high frequency allowed) instead
     * of op_audit. dependOnBizId = dsId; content = uid/dsId/exemption marker, no data values.
     */
    MASKING_ROOT_EXEMPT
}
