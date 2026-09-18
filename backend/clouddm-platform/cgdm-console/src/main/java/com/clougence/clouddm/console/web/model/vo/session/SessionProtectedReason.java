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

/**
 * Reasons why a database session is protected from being killed (design §4 protection matrix).
 * The VO transports the enum name as a plain string for frontend i18n mapping.
 */
public enum SessionProtectedReason {

    /** The connection currently used by this platform query itself. */
    PLATFORM_SELF,

    /** MySQL system users such as event_scheduler / system user / sql/main. */
    SYSTEM_USER,

    /** Replication threads such as Binlog Dump / slave_io / slave_sql. */
    REPLICATION_THREAD,
}
