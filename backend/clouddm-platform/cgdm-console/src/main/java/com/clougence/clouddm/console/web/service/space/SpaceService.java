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
package com.clougence.clouddm.console.web.service.space;

import java.util.List;

import com.clougence.clouddm.console.web.model.fo.space.SpaceDatabasesFO;
import com.clougence.clouddm.console.web.model.fo.space.SpaceTablesFO;
import com.clougence.clouddm.console.web.model.vo.session.SessionInstanceVO;
import com.clougence.clouddm.console.web.model.vo.space.SpaceDatabasesVO;
import com.clougence.clouddm.console.web.model.vo.space.SpaceTablesVO;

/**
 * DBA table-space usage snapshot (B4, design 09-19-table-space-usage): current database/schema
 * and table level space occupation, read-only through the shared ops-diagnostic executor
 * (channel C: createSession → timeout-wrapped statements → finally release). Snapshot only —
 * no history/trend, no bloat analysis (PRD non-goals).
 */
public interface SpaceService {

    /**
     * Datasources visible to the current tenant, filtered by the space-usage whitelist
     * (MySQL + PostgreSQL), sorted by display name. Reuses the B2 instance VO.
     */
    List<SessionInstanceVO> listInstances(String puid, String uid);

    /**
     * Database-level snapshot (MySQL: user schemas of the instance; PG: schemas inside the
     * connected database + its current_database() name). The frontend aggregates the instance
     * summary from this full set — no extra server-side summary SQL.
     */
    SpaceDatabasesVO databases(String puid, String uid, SpaceDatabasesFO fo);

    /**
     * Table-level snapshot of one database/schema. The dbName is validated and escaped
     * server-side before it is embedded into the statement (design §2.3 identifier safety).
     */
    SpaceTablesVO tables(String puid, String uid, SpaceTablesFO fo);
}
