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
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseDO;

public interface DmProdReleaseMapper extends BaseMapper<DmProdReleaseDO> {

    /**
     * Conditional status update — the single point for release state transitions.
     * Returns affected rows: 0 = illegal transition or concurrent conflict (idempotent no-op).
     */
    int transitStatus(@Param("id") long id, @Param("toStatus") String toStatus, @Param("expectedFrom") List<String> expectedFrom);

    /**
     * Single-field update for approval_id (written after ticket creation).
     */
    int updateApprovalId(@Param("id") long id, @Param("approvalId") long approvalId);

    /**
     * Single-field update for gate_result (stores merge snapshot / rejection evidence).
     */
    int updateGateResult(@Param("id") long id, @Param("gateResult") String gateResult);

    DmProdReleaseDO queryById(@Param("id") long id);

    /**
     * Paginated list with tenant filter on primary_uid.
     */
    IPage<DmProdReleaseDO> listByConditionAndPage(
        Page<?> page,
        @Param("puid") String puid,
        @Param("status") String status);

    /**
     * Count non-terminal releases that have stmt rows targeting a specific prod DB.
     * Used for the library-level lock (service-layer check, design §6).
     */
    int countActiveByProdDb(@Param("prodDsId") long prodDsId, @Param("prodDbName") String prodDbName);
}
