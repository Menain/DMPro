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
package com.clougence.clouddm.console.web.model.vo.session;

import lombok.Data;

/**
 * One row of the database session list (SHOW FULL PROCESSLIST mapping).
 */
@Data
public class DbSessionVO {

    /** Session (connection) id in the target database. */
    private String  id;

    private String  user;

    private String  host;

    private String  db;

    private String  command;

    /** Execution time in seconds. */
    private Long    time;

    private String  state;

    /** Current SQL text, may be very long. */
    private String  info;

    /** Whether the session can be killed (server-side protection matrix result). */
    private boolean killable;

    /** {@link SessionProtectedReason} enum name when not killable, otherwise null. */
    private String  protectedReason;

    /** True when the session user equals the datasource configured account (and is not self). */
    private boolean sharedAccount;
}
