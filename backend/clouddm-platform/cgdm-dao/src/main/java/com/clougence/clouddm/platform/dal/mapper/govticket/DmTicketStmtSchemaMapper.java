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
package com.clougence.clouddm.platform.dal.mapper.govticket;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketStmtSchemaDO;

public interface DmTicketStmtSchemaMapper extends BaseMapper<DmTicketStmtSchemaDO> {

    /**
     * Find all CAPTURED snapshot rows for a given stmt (ticket group).
     */
    List<DmTicketStmtSchemaDO> queryByStmtId(@Param("stmtId") long stmtId);

    /**
     * Check if a snapshot row already exists for (stmt_id, table_name).
     * Used for first-capture-wins / fill-missing semantics.
     */
    DmTicketStmtSchemaDO queryByStmtAndTable(@Param("stmtId") long stmtId, @Param("tableName") String tableName);

    /**
     * Insert a snapshot row. The caller checks existence first (fill-missing).
     * uk_stmt_table is the concurrent-merge safety net.
     */
    int insertSnapshot(DmTicketStmtSchemaDO row);

    /**
     * Update an existing snapshot row by (stmt_id, table_name).
     * Used for fill-missing: when a FAILED row exists and retryGroup re-attempts
     * capture, this updates the row to CAPTURED instead of inserting a duplicate
     * (which would violate uk_stmt_table).
     */
    int updateSnapshotResult(@Param("stmtId") long stmtId, @Param("tableName") String tableName,
                             @Param("existsFlag") int existsFlag, @Param("schemaJson") String schemaJson,
                             @Param("fingerprint") String fingerprint, @Param("captureStatus") String captureStatus,
                             @Param("captureError") String captureError);
}
