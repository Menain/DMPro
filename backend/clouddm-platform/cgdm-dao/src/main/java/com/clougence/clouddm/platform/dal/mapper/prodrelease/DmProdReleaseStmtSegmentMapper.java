/*
 * Copyright 2026 杭州开云集致科技有限公司
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.clougence.clouddm.platform.dal.mapper.prodrelease;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseStmtSegmentDO;

/**
 * ADR-013 M1 frozen segment-plan manifest DAL surface (mirrors
 * {@link DmProdReleaseStmtSchemaMapper}): merge-time batchInsert, seg_idx-ordered read,
 * lock-window runtime update (M2 consumers) and the two cascade deletes. No arbitrary
 * update/delete — the plan columns are frozen at merge (never touched by updateRuntime).
 */
public interface DmProdReleaseStmtSegmentMapper extends BaseMapper<DmProdReleaseStmtSegmentDO> {

    /**
     * The frozen segment sequence of one release stmt, ordered by seg_idx
     * (the M2 dispatch reads the whole plan in one call; M3 re-hashes it for the
     * plan-drift gate).
     */
    List<DmProdReleaseStmtSegmentDO> queryByReleaseStmtId(@Param("releaseStmtId") long releaseStmtId);

    /**
     * C8 M5.4 (O-C7-6b): ALL manifest rows of one release in a single read (idx_release),
     * ordered by (release_stmt_id, seg_idx) — the getDetail per-stmt N+1 replacement; the
     * caller groups by releaseStmtId in memory, each group keeping the queryByReleaseStmtId
     * seg_idx order.
     */
    List<DmProdReleaseStmtSegmentDO> queryByReleaseId(@Param("releaseId") long releaseId);

    /**
     * Batch-insert the frozen manifest rows (createRelease / resubmit merge transaction,
     * propagateSegments — lifecycle twin of the schema table's batchInsert).
     */
    int batchInsert(@Param("rows") List<DmProdReleaseStmtSegmentDO> rows);

    /**
     * Runtime-column update by id, called ONLY inside the release-row-lock window
     * (single-writer discipline, M2). Sets exactly the runtime columns
     * (status/task_id/session_id/job_biz_id/summary/error/checkpoint/started_at/finished_at);
     * plan columns are immutable after merge.
     */
    int updateRuntime(@Param("row") DmProdReleaseStmtSegmentDO row);

    /**
     * Delete all manifest rows of one release stmt (returnStmt orphan cleanup, mirrors
     * {@code schemaMapper().deleteByReleaseStmtId}).
     */
    int deleteByReleaseStmtId(@Param("releaseStmtId") long releaseStmtId);

    /**
     * Delete all manifest rows of an entire release (returnRelease / handleRejected /
     * handleCancelled, mirrors {@code schemaMapper().deleteByReleaseId}).
     */
    int deleteByReleaseId(@Param("releaseId") long releaseId);
}
