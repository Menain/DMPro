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
package com.clougence.clouddm.platform.dal.access;

import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangeEventMapper;

/**
 * Append-only {@code dm_db_change_event} table access. P5 trimmed the legacy governance
 * Dal (which also exposed stmt_version / revision / promotion mappers) down to the single
 * event mapper that the v2 ticket and P3 production-release pipelines reuse.
 */
public interface DbChangeEventDal {

    DmDbChangeEventMapper eventMapper();
}
