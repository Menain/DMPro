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
package com.clougence.clouddm.platform.dal.mapper.govticket;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketStmtRevisionDO;

/**
 * Version audit table for ticket stmt SQL edits (return-revise loop).
 * Insert-only (append new version rows); no update/delete path.
 */
public interface DmTicketStmtRevisionMapper extends BaseMapper<DmTicketStmtRevisionDO> {

    /**
     * Find all revision rows for a given stmt, ordered by version ascending.
     */
    List<DmTicketStmtRevisionDO> queryByStmtId(@Param("stmtId") long stmtId);

    /**
     * Get the maximum version number for a given stmt (0 if none).
     */
    int maxVersionByStmtId(@Param("stmtId") long stmtId);

    /**
     * Insert a new revision row.
     */
    int insertRevision(DmTicketStmtRevisionDO row);
}
