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
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseStmtSchemaDO;

public interface DmProdReleaseStmtSchemaMapper extends BaseMapper<DmProdReleaseStmtSchemaDO> {

    /**
     * Find all schema snapshot rows for a release stmt (frozen copy).
     */
    List<DmProdReleaseStmtSchemaDO> queryByReleaseStmtId(@Param("releaseStmtId") long releaseStmtId);

    /**
     * Find all schema snapshot rows for a release (all stmts, all tables).
     */
    List<DmProdReleaseStmtSchemaDO> queryByReleaseId(@Param("releaseId") long releaseId);

    /**
     * Batch-insert frozen snapshot rows.
     */
    int batchInsert(@Param("rows") List<DmProdReleaseStmtSchemaDO> rows);

    /**
     * Delete all schema snapshot rows for a specific release stmt (used by returnStmt
     * to clean up orphaned rows when the parent stmt row is deleted).
     */
    int deleteByReleaseStmtId(@Param("releaseStmtId") long releaseStmtId);

    /**
     * Delete all schema snapshot rows for an entire release (used by returnRelease,
     * handleRejected, handleCancelled to clean up orphaned rows when all stmt rows
     * are deleted). Mirrors {@code stmtMapper().deleteByReleaseId}.
     */
    int deleteByReleaseId(@Param("releaseId") long releaseId);
}
