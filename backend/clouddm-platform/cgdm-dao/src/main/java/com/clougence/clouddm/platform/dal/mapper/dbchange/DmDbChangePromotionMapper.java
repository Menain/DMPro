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
package com.clougence.clouddm.platform.dal.mapper.dbchange;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangePromotionDO;

public interface DmDbChangePromotionMapper extends BaseMapper<DmDbChangePromotionDO> {

    DmDbChangePromotionDO queryByRevisionId(@Param("revisionId") Long revisionId);

    /**
     * Conditional status update — the single point for promotion state transitions.
     * Returns affected rows: 0 = illegal transition or concurrent conflict (idempotent no-op).
     */
    int transitStatus(@Param("id") long id, @Param("toStatus") String toStatus, @Param("expectedFrom") List<String> expectedFrom);

    /**
     * Single-field update for prod_approval_id (non-status field, written after ticket creation).
     */
    int updateProdApprovalId(@Param("id") long id, @Param("prodApprovalId") long prodApprovalId);

    /**
     * Single-field update for preflight_result (Phase 7 guard evidence).
     * Non-status field — only this column is written, status stays on the state machine.
     */
    int updatePreflightResult(@Param("id") long id, @Param("preflightResult") String preflightResult);

    /**
     * Scan non-terminal promotions (including FAILED — needed for the revival edge).
     */
    List<DmDbChangePromotionDO> listNonTerminal();

    /**
     * Paginated list with tenant filter via JOIN dm_logical_db.
     */
    IPage<DmDbChangePromotionDO> listPromotionByConditionAndPage(
        Page<?> page,
        @Param("puid") String puid,
        @Param("status") String status,
        @Param("promotionType") String promotionType);
}
