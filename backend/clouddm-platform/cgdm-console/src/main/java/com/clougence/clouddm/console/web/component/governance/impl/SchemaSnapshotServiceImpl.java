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
package com.clougence.clouddm.console.web.component.governance.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.clougence.clouddm.console.web.component.dsconfig.DmDsConfigService;
import com.clougence.clouddm.console.web.component.dsconfig.mode.DsLevels;
import com.clougence.clouddm.console.web.component.governance.SchemaFingerprintUtils;
import com.clougence.clouddm.console.web.component.governance.SchemaSnapshotService;
import com.clougence.clouddm.console.web.component.schema.DsSchemaService;
import com.clougence.clouddm.platform.dal.access.DbPairDal;
import com.clougence.clouddm.platform.dal.access.DataSourceDal;
import com.clougence.clouddm.platform.dal.access.TicketDbStmtDal;
import com.clougence.clouddm.platform.dal.mapper.dbpair.DmDbPairMapper;
import com.clougence.clouddm.platform.dal.mapper.datasource.DmDsMapper;
import com.clougence.clouddm.platform.dal.model.datasource.DmDsDO;
import com.clougence.clouddm.platform.dal.model.dbpair.DmDbPairDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketStmtSchemaDO;
import com.clougence.schema.umi.special.rdb.RdbTable;
import com.clougence.schema.umi.struts.UmiTypes;
import com.clougence.schema.umi.struts.Value;
import com.clougence.utils.StringUtils;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * Snapshot collection implementation.
 * <p>
 * Fetches live structure from the pre-release DB via DsSchemaService (RSocket to worker),
 * normalizes via SchemaFingerprintUtils, and persists to dm_ticket_stmt_schema.
 * first-capture-wins: existing CAPTURED rows are never replaced.
 */
@Slf4j
@Service
public class SchemaSnapshotServiceImpl implements SchemaSnapshotService {

    private static final String STATUS_CAPTURED = "CAPTURED";
    private static final String STATUS_FAILED  = "FAILED";

    @Resource
    private ReleaseSchemaPrecheckServiceImpl precheckService;
    @Resource
    private DsSchemaService       dsSchemaService;
    @Resource
    private DmDsConfigService     configService;
    @Resource
    private DbPairDal            dbPairDal;
    @Resource
    private DataSourceDal        dsDal;
    @Resource
    private TicketDbStmtDal      ticketDbStmtDal;

    @Override
    public void captureIfMissing(DmTicketDbStmtDO stmt, long ticketId) {
        if (stmt == null || stmt.getPairId() == null || StringUtils.isBlank(stmt.getSqlContent())) {
            return;
        }

        // Resolve the pair to get pre-release DS and DB name
        DmDbPairMapper pairMapper = dbPairDal.pairMapper();
        DmDbPairDO pair = pairMapper.selectById(stmt.getPairId());
        if (pair == null) {
            log.warn("[SchemaSnapshot] Pair not found for stmt {}: pairId={}", stmt.getId(), stmt.getPairId());
            return;
        }

        // Use the pair's preDsId — the stmt.getDsId() is already the pre DS id
        // (resolveDsId in GovTicketV2ServiceImpl sets it from pair.getPreDsId() for PRE_DDL)
        long preDsId = stmt.getDsId();
        String preDbName = stmt.getDbName();
        if (preDsId <= 0 || StringUtils.isBlank(preDbName)) {
            log.warn("[SchemaSnapshot] Missing dsId/dbName for stmt {}: dsId={}, dbName={}",
                stmt.getId(), preDsId, preDbName);
            return;
        }

        // Extract table names from the SQL
        Set<String> tableNames = precheckService.extractTables(stmt.getSqlContent());
        if (tableNames.isEmpty()) {
            log.debug("[SchemaSnapshot] No tables extracted for stmt {}", stmt.getId());
            return;
        }

        // Resolve DS identity
        DmDsMapper dsMapper = dsDal.dsMapper();
        DmDsDO dsDO = dsMapper.queryDsIdentityById(preDsId);
        if (dsDO == null) {
            log.warn("[SchemaSnapshot] DS not found: dsId={}", preDsId);
            return;
        }

        // Build levelsParam for the pre-release DB
        Map<UmiTypes, Object> levelsParam;
        try {
            List<String> levels = new ArrayList<>();
            levels.add(dsDO.getDsEnvId().toString());
            levels.add(String.valueOf(preDsId));
            levels.add(preDbName);
            DsLevels dsLevels = configService.parseLevels(levels);
            levelsParam = dsLevels.levelsParam();
        } catch (Exception e) {
            log.warn("[SchemaSnapshot] Failed to parse levels for stmt {}: {}", stmt.getId(), e.getMessage());
            return;
        }

        // Capture each table
        for (String tableName : tableNames) {
            try {
                captureTableIfMissing(stmt, ticketId, preDsId, preDbName, dsDO, levelsParam, tableName);
            } catch (Exception e) {
                log.warn("[SchemaSnapshot] Failed to capture table {} for stmt {}: {}",
                    tableName, stmt.getId(), e.getMessage());
                // Write a FAILED row so fill-missing knows it was attempted
                writeFailedRow(stmt, ticketId, preDsId, preDbName, tableName, truncateError(e.getMessage()));
            }
        }
    }

    private void captureTableIfMissing(DmTicketDbStmtDO stmt, long ticketId, long preDsId, String preDbName,
                                       DmDsDO dsDO, Map<UmiTypes, Object> levelsParam, String tableName) {
        // first-capture-wins: check if a row already exists
        DmTicketStmtSchemaDO existing = ticketDbStmtDal.schemaMapper()
            .queryByStmtAndTable(stmt.getId(), tableName);
        if (existing != null && STATUS_CAPTURED.equals(existing.getCaptureStatus())) {
            // Already captured — do not replace (first-capture-wins)
            return;
        }

        // Fetch live structure from pre-release DB
        RdbTable rdbTable = null;
        boolean exists = false;
        try {
            Value value = dsSchemaService.realTimeFetchSelectObject(dsDO, levelsParam, tableName);
            if (value instanceof RdbTable) {
                rdbTable = (RdbTable) value;
                exists = true;
            }
        } catch (Exception e) {
            // Re-throw to outer catch which writes a FAILED row
            throw new RuntimeException("Failed to fetch live schema for table " + tableName, e);
        }

        // Build field values
        int existsFlag = exists ? 1 : 0;
        String schemaJson;
        String fingerprint;
        if (exists && rdbTable != null) {
            schemaJson = SchemaFingerprintUtils.canonicalJson(rdbTable);
            fingerprint = SchemaFingerprintUtils.fingerprint(rdbTable);
        } else {
            // Table doesn't exist on pre-release DB — valid pre-before state for CREATE TABLE
            schemaJson = null;
            fingerprint = SchemaFingerprintUtils.NON_EXISTENT_FINGERPRINT;
        }

        if (existing != null) {
            // Existing FAILED row — UPDATE it to CAPTURED (fill-missing for retryGroup).
            // Cannot INSERT because uk_stmt_table already has a row.
            ticketDbStmtDal.schemaMapper().updateSnapshotResult(
                stmt.getId(), tableName, existsFlag, schemaJson, fingerprint, STATUS_CAPTURED, null);
        } else {
            // No existing row — INSERT a new CAPTURED row.
            DmTicketStmtSchemaDO row = new DmTicketStmtSchemaDO();
            row.setTicketId(ticketId);
            row.setStmtId(stmt.getId());
            row.setDsId(preDsId);
            row.setDbName(preDbName);
            row.setTableName(tableName);
            row.setExistsFlag(existsFlag);
            row.setSchemaJson(schemaJson);
            row.setFingerprint(fingerprint);
            row.setCaptureStatus(STATUS_CAPTURED);
            row.setCaptureError(null);

            try {
                ticketDbStmtDal.schemaMapper().insertSnapshot(row);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // uk_stmt_table concurrent insert — another thread captured it first, fine
                log.debug("[SchemaSnapshot] Concurrent capture for stmt {}, table {} — already exists", stmt.getId(), tableName);
            }
        }
    }

    private void writeFailedRow(DmTicketDbStmtDO stmt, long ticketId, long preDsId, String preDbName,
                                String tableName, String error) {
        // Check if a row already exists (don't overwrite)
        DmTicketStmtSchemaDO existing = ticketDbStmtDal.schemaMapper()
            .queryByStmtAndTable(stmt.getId(), tableName);
        if (existing != null) {
            return;
        }

        DmTicketStmtSchemaDO row = new DmTicketStmtSchemaDO();
        row.setTicketId(ticketId);
        row.setStmtId(stmt.getId());
        row.setDsId(preDsId);
        row.setDbName(preDbName);
        row.setTableName(tableName);
        row.setExistsFlag(0);
        row.setSchemaJson(null);
        row.setFingerprint(SchemaFingerprintUtils.NON_EXISTENT_FINGERPRINT);
        row.setCaptureStatus(STATUS_FAILED);
        row.setCaptureError(error);

        try {
            ticketDbStmtDal.schemaMapper().insertSnapshot(row);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // Concurrent — fine
        }
    }

    private static String truncateError(String msg) {
        if (msg == null) return null;
        return msg.length() > 500 ? msg.substring(0, 500) : msg;
    }
}
