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
package com.clougence.clouddm.console.web.model.vo.space;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * Database(schema)-level space usage snapshot of one datasource instance
 * (design 09-19-table-space-usage §3). The instance summary shown on the page is aggregated by
 * the frontend from {@code dbs} (full set, never from a filtered view) — no extra server SQL.
 */
@Data
public class SpaceDatabasesVO {

    /** Dialect branch: "MYSQL_8" or "POSTGRES" (same values as the B3 lock overview). */
    private String          branch;

    /**
     * Per-database rows for BOTH dialects (2026-09-19 smoke ruling): MySQL = user databases with
     * full breakdown estimates; PG = every non-template database of the instance via the shared
     * pg_database catalog — total_bytes only (pg_database_size), breakdown columns stay null
     * because per-database internals are unreachable from a single connection (frontend shows
     * '-'). Already ordered by total_bytes DESC.
     */
    private List<SpaceDbVO> dbs = new ArrayList<>();
}
