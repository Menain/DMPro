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
package com.clougence.clouddm.platform.dal.mapper.govpatrol;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.clougence.clouddm.platform.dal.model.govpatrol.DmGovSchemaPatrolRunDO;

public interface DmGovSchemaPatrolRunMapper extends BaseMapper<DmGovSchemaPatrolRunDO> {

    /**
     * Insert a new run row and return the generated id.
     */
    int insertRun(DmGovSchemaPatrolRunDO row);

    /**
     * Find a run by id.
     */
    DmGovSchemaPatrolRunDO queryById(@Param("id") long id);

    /**
     * Paginated list ordered by gmt_create desc.
     */
    IPage<DmGovSchemaPatrolRunDO> listRuns(Page<?> page);

    /**
     * Check if any RUNNING run exists (concurrency guard for tick).
     */
    DmGovSchemaPatrolRunDO queryRunning();

    /**
     * Find the most recent AUTO run (for interval check).
     */
    DmGovSchemaPatrolRunDO queryLatestAuto();

    /**
     * Update run status and counts.
     */
    int updateStatus(@Param("id") long id, @Param("status") String status,
                     @Param("pairsDone") int pairsDone, @Param("unexplainedCount") int unexplainedCount,
                     @Param("error") String error);

    /**
     * Increment pairs_done by 1.
     */
    int incrementPairsDone(@Param("id") long id);

    /**
     * Delete runs older than the keep threshold (cascade items via SQL).
     */
    int deleteOldRuns(@Param("keepCount") int keepCount);
}
