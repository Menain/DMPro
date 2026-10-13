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
package com.clougence.clouddm.platform.dal.access;

import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmGovRouteOverrideMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmGovSchemaGateOverrideMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseStmtMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseStmtSchemaMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseStmtSegmentMapper;

/**
 * Dal aggregation for production release tables.
 * release table is mutable via controlled state-machine methods (transitStatus / updateApprovalId / updateGateResult).
 * stmt table has insert, select, updateExecStatus, deleteByReleaseId — no arbitrary update/delete.
 * stmt_schema table has batchInsert, queryByReleaseStmtId, queryByReleaseId.
 * stmt_segment table (ADR-013 frozen plan manifest) has batchInsert, queryByReleaseStmtId,
 * lock-window updateRuntime + the two cascade deletes — plan columns frozen at merge.
 * gate_override table (ADR-011) has insert/select + CAS consume/revoke only.
 * route_override table (ADR-013 M3) has insert/select + CAS consume only (no revoke — design D8).
 */
public interface ProdReleaseDal {

    DmProdReleaseMapper releaseMapper();

    DmProdReleaseStmtMapper stmtMapper();

    DmProdReleaseStmtSchemaMapper schemaMapper();

    DmProdReleaseStmtSegmentMapper segmentMapper();

    DmGovSchemaGateOverrideMapper gateOverrideMapper();

    DmGovRouteOverrideMapper routeOverrideMapper();
}
