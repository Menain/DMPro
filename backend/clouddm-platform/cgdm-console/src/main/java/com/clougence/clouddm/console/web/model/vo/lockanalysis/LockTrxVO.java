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
package com.clougence.clouddm.console.web.model.vo.lockanalysis;

import lombok.Data;

/**
 * One running transaction (MySQL information_schema.innodb_trx / PG pg_stat_activity with
 * xact_start, design 09-17-lock-deadlock-analysis §4.1). PG leaves trxId/rowsLocked/rowsModified null.
 */
@Data
public class LockTrxVO {

    private String trxId;

    private String state;

    /** Transaction start time (display string). */
    private String started;

    /** Seconds since the transaction started; >= 60 is highlighted as a long transaction. */
    private Long   durationSeconds;

    private Long   rowsLocked;

    private Long   rowsModified;

    /** Session (connection) id running the transaction. */
    private String threadPid;

    private String query;
}
