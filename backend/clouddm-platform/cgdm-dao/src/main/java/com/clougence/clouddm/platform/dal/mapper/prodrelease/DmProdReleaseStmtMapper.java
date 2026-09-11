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
package com.clougence.clouddm.platform.dal.mapper.prodrelease;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseStmtDO;

public interface DmProdReleaseStmtMapper extends BaseMapper<DmProdReleaseStmtDO> {

    DmProdReleaseStmtDO queryById(@Param("id") long id);

    List<DmProdReleaseStmtDO> queryByReleaseId(@Param("releaseId") long releaseId);

    /**
     * Check if a source stmt has already been merged into any release (uk_source_stmt).
     */
    DmProdReleaseStmtDO queryBySourceStmtId(@Param("sourceStmtId") long sourceStmtId);

    /**
     * Find the next PENDING stmt for a (release, prod_ds_id, prod_db_name) after a given seq.
     * Used for chained execution: after one stmt completes, start the next in sequence.
     * Returns null if no more PENDING stmts for that DB.
     */
    DmProdReleaseStmtDO nextPendingStmt(
        @Param("releaseId") long releaseId,
        @Param("prodDsId") long prodDsId,
        @Param("prodDbName") String prodDbName,
        @Param("afterSeq") int afterSeq);

    /**
     * Find the first PENDING stmt for a (release, prod_ds_id, prod_db_name) — smallest seq.
     * Used at startExecution to kick off each DB's execution chain.
     */
    DmProdReleaseStmtDO firstPendingStmt(
        @Param("releaseId") long releaseId,
        @Param("prodDsId") long prodDsId,
        @Param("prodDbName") String prodDbName);

    int updateExecStatus(@Param("id") long id, @Param("execStatus") String execStatus, @Param("execDetail") String execDetail);

    /**
     * Delete all stmt rows for a release (used on REJECTED/CANCELLED to release UK locks).
     */
    int deleteByReleaseId(@Param("releaseId") long releaseId);
}
