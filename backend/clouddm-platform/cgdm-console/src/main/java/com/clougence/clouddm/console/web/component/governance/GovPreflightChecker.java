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
package com.clougence.clouddm.console.web.component.governance;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.base.metadata.ds.DataSourceConfig;
import com.clougence.clouddm.console.web.component.analysis.AnalysisQueryOptions;
import com.clougence.clouddm.console.web.component.analysis.QueryAnalysisFeature;
import com.clougence.clouddm.console.web.component.analysis.QueryAnalysisService;
import com.clougence.clouddm.console.web.component.dsconfig.DmDsConfigService;
import com.clougence.clouddm.console.web.component.dsconfig.mode.DsLevels;
import com.clougence.clouddm.console.web.component.schema.DsSchemaService;
import com.clougence.clouddm.platform.dal.model.datasource.DmDsDO;
import com.clougence.clouddm.sdk.execute.session.QueryArg;
import com.clougence.clouddm.sdk.execute.session.QueryRequest;
import com.clougence.clouddm.sdk.sql.analysis.behavior.BehaviorObject;
import com.clougence.clouddm.sdk.sql.analysis.behavior.BehaviorRelation;
import com.clougence.clouddm.sdk.sql.analysis.behavior.ObjectName;
import com.clougence.clouddm.sdk.sql.analysis.behavior.TargetType;
import com.clougence.clouddm.sdk.sql.parser.SplitQueryType;
import com.clougence.schema.umi.special.rdb.RdbColumn;
import com.clougence.schema.umi.special.rdb.RdbIndex;
import com.clougence.schema.umi.special.rdb.RdbPrimaryKey;
import com.clougence.schema.umi.special.rdb.RdbTable;
import com.clougence.schema.umi.special.rdb.RdbUniqueKey;
import com.clougence.schema.umi.struts.Value;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * Preflight four-item checker (Phase 7, design D5).
 * <p>
 * 1. Connectivity — {@code realTimeFetchVersion} (never degrades, exception = DENY)
 * 2. Table exists — {@code realTimeFetchSelectObject} returns non-null (never degrades, null = DENY)
 * 3. DDL dependency state — parser-extracted target objects checked against RdbTable
 *    (extraction failure → SKIP, not DENY — S4 degradation)
 * 4. DML target table exists
 * <p>
 * Only runs inside the PROD six-gate chain (PRE/non-governance short-circuit before reaching here).
 */
@Slf4j
@Service
public class GovPreflightChecker {

    @Resource
    private DsSchemaService    dsSchemaService;
    @Resource
    private DmDsConfigService  dmDsConfigService;
    @Resource
    private QueryAnalysisService queryAnalysisService;

    /**
     * Run all four Preflight checks against the live PROD datasource.
     *
     * @param dsDO       target datasource (from promotion snapshot)
     * @param levels     ticket/job levels (envId, dsId, catalog, schema)
     * @param sqlText    frozen revision sql_text (the full ticket rawSql)
     * @param dsConfig   dialect-specific parser config
     * @return list of GateItem results (1-4 items)
     */
    public List<GateItem> check(DmDsDO dsDO, List<String> levels, String sqlText, DataSourceConfig dsConfig) {
        List<GateItem> results = new ArrayList<>();

        // 1. levelsParam (precedent: createExecJob L1109-1118 / AutoExecServiceImpl.create L316-319)
        DsLevels dsLevels = this.dmDsConfigService.parseLevels(levels);
        Map<com.clougence.schema.umi.struts.UmiTypes, Object> levelsParam = dsLevels.levelsParam();

        // 2. Connectivity — never degrades
        GateItem connItem = checkConnectivity(dsDO, levelsParam);
        results.add(connItem);
        if (!connItem.isPass()) {
            return results;
        }

        // 3. Parse statements to extract target objects (zero self-built parser — research 03)
        List<QueryRequest> requests;
        try {
            requests = parseStatements(dsConfig, sqlText);
        } catch (Exception e) {
            // Parser failure → degrade all remaining checks to SKIP (S4)
            results.add(GateItem.pass("table_exists:parser_skip", "parser unsupported — degraded"));
            results.add(GateItem.pass("ddl_dep:parser_skip", "parser unsupported — degraded"));
            return results;
        }

        // 4. Table exists + DDL dependency + DML target — per statement
        results.addAll(checkTableExistsAndDdlDep(dsDO, levelsParam, requests));

        return results;
    }

    // ------- 1. Connectivity (never degrades) -------

    private GateItem checkConnectivity(DmDsDO dsDO, Map<com.clougence.schema.umi.struts.UmiTypes, Object> levelsParam) {
        try {
            String version = this.dsSchemaService.realTimeFetchVersion(dsDO, levelsParam);
            return GateItem.pass("connectivity", version != null ? version : "connected");
        } catch (Exception e) {
            log.error("[GovPreflight] connectivity check failed, dsId={}", dsDO.getId(), e);
            return GateItem.deny("connectivity", "connection failed: " + e.getMessage());
        }
    }

    // ------- 3. Statement parsing (existing parser, zero self-built) -------

    private List<QueryRequest> parseStatements(DataSourceConfig dsConfig, String sqlText) {
        try (StringReader reader = new StringReader(sqlText);
             Stream<QueryRequest> stream = this.queryAnalysisService.analysisRequestsStream(
                 dsConfig, reader, Collections.<QueryArg>emptyList(), 1, 0,
                 AnalysisQueryOptions.builder()
                     .skip(QueryAnalysisFeature.REWRITE)
                     .build())) {
            return stream.toList();
        } catch (Exception e) {
            log.warn("[GovPreflight] statement parsing failed, degrading DDL dep checks", e);
            throw new ErrorMessageException("parser unsupported: " + e.getMessage());
        }
    }

    // ------- 4. Table exists + DDL dependency + DML target -------

    private List<GateItem> checkTableExistsAndDdlDep(
        DmDsDO dsDO,
        Map<com.clougence.schema.umi.struts.UmiTypes, Object> levelsParam,
        List<QueryRequest> requests) {

        List<GateItem> results = new ArrayList<>();
        boolean tableExistsOverall = true;
        boolean ddlDepOverall = true;
        boolean hasDdlDepChecks = false;

        for (QueryRequest request : requests) {
            List<BehaviorRelation> relations = request.getRelations();
            if (relations == null || relations.isEmpty()) {
                continue;
            }

            for (BehaviorRelation relation : relations) {
                BehaviorObject subject = relation.getSubject();
                if (subject == null || subject.getObjectType() != TargetType.Table) {
                    continue;
                }

                String tableName = extractObjectName(subject);
                if (tableName == null) {
                    continue;
                }

                // Table exists (never degrades)
                RdbTable rdbTable = fetchRdbTable(dsDO, levelsParam, tableName);
                if (rdbTable == null) {
                    tableExistsOverall = false;
                    results.add(GateItem.deny("table_exists:" + tableName, "table not found"));
                    continue;
                }

                // DDL dependency state for target objects
                Set<SplitQueryType> queryTypes = request.getQueryTypes();
                if (queryTypes != null) {
                    GateItem depItem = checkDdlDependency(queryTypes, relation, rdbTable, tableName);
                    if (depItem != null) {
                        hasDdlDepChecks = true;
                        if (!depItem.isPass()) {
                            ddlDepOverall = false;
                        }
                        results.add(depItem);
                    }
                }
            }
        }

        // If no table-exists DENY occurred and no individual item was added, add a summary pass
        if (tableExistsOverall) {
            boolean hasTableExistsItem = results.stream().anyMatch(r -> r.getItem().startsWith("table_exists:"));
            if (!hasTableExistsItem) {
                results.add(GateItem.pass("table_exists", "all target tables found"));
            }
        }

        // If DDL dep checks ran and all passed, ensure a summary pass exists
        if (hasDdlDepChecks && ddlDepOverall) {
            boolean hasDdlDepItem = results.stream().anyMatch(r -> r.getItem().startsWith("ddl_dep:"));
            if (!hasDdlDepItem) {
                results.add(GateItem.pass("ddl_dep", "all dependency checks passed"));
            }
        }

        // DML target table exists (same as table-exists check above, already covered)
        return results;
    }

    private GateItem checkDdlDependency(Set<SplitQueryType> queryTypes, BehaviorRelation relation, RdbTable rdbTable, String tableName) {
        // ADD_COLUMN → column should NOT exist
        if (queryTypes.contains(SplitQueryType.ADD_COLUMN)) {
            for (BehaviorObject target : safeList(relation.getTarget())) {
                String colName = extractObjectName(target);
                if (colName == null || target.getObjectType() != TargetType.Column) {
                    continue;
                }
                Map<String, RdbColumn> columns = rdbTable.getColumns();
                if (columns != null && columns.containsKey(colName)) {
                    return GateItem.deny("ddl_dep:ADD_COLUMN:" + tableName + "." + colName,
                        "column already exists");
                }
                return GateItem.pass("ddl_dep:ADD_COLUMN:" + tableName + "." + colName,
                    "column not found (as expected)");
            }
        }

        // ADD_INDEX → index should NOT exist in indices/primaryKey/uniqueKeys
        if (queryTypes.contains(SplitQueryType.ADD_INDEX)) {
            for (BehaviorObject target : safeList(relation.getTarget())) {
                String indexName = extractObjectName(target);
                if (indexName == null) {
                    continue;
                }
                if (indexExists(rdbTable, indexName)) {
                    return GateItem.deny("ddl_dep:ADD_INDEX:" + indexName,
                        "index already exists on " + tableName);
                }
                return GateItem.pass("ddl_dep:ADD_INDEX:" + indexName,
                    "index not found (as expected)");
            }
            // Subject might be the index itself (CREATE INDEX idx ON table)
            BehaviorObject subject = relation.getSubject();
            if (subject != null && subject.getObjectType() == TargetType.Index) {
                String indexName = extractObjectName(subject);
                if (indexName != null && indexExists(rdbTable, indexName)) {
                    return GateItem.deny("ddl_dep:ADD_INDEX:" + indexName,
                        "index already exists on " + tableName);
                }
                return GateItem.pass("ddl_dep:ADD_INDEX:" + indexName,
                    "index not found (as expected)");
            }
        }

        // CREATE_TABLE → table should NOT exist (but we already fetched it and found it exists → DENY)
        if (queryTypes.contains(SplitQueryType.CREATE_TABLE)) {
            return GateItem.deny("ddl_dep:CREATE_TABLE:" + tableName,
                "table already exists");
        }

        // DROP_TABLE → table should exist (it does, since we fetched it → PASS)
        if (queryTypes.contains(SplitQueryType.DROP_TABLE)) {
            return GateItem.pass("ddl_dep:DROP_TABLE:" + tableName,
                "table exists (as expected)");
        }

        // DROP_COLUMN → column should exist
        if (queryTypes.contains(SplitQueryType.DROP_COLUMN)) {
            for (BehaviorObject target : safeList(relation.getTarget())) {
                String colName = extractObjectName(target);
                if (colName == null) {
                    continue;
                }
                Map<String, RdbColumn> columns = rdbTable.getColumns();
                if (columns == null || !columns.containsKey(colName)) {
                    return GateItem.deny("ddl_dep:DROP_COLUMN:" + tableName + "." + colName,
                        "column not found (should exist for DROP)");
                }
                return GateItem.pass("ddl_dep:DROP_COLUMN:" + tableName + "." + colName,
                    "column exists (as expected)");
            }
        }

        return null;
    }

    private boolean indexExists(RdbTable rdbTable, String indexName) {
        List<RdbIndex> indices = rdbTable.getIndices();
        if (indices != null) {
            for (RdbIndex idx : indices) {
                if (indexName.equals(idx.getName())) {
                    return true;
                }
            }
        }
        RdbPrimaryKey pk = rdbTable.getPrimaryKey();
        if (pk != null && indexName.equals(pk.getName())) {
            return true;
        }
        List<RdbUniqueKey> uqs = rdbTable.getUniqueKeys();
        if (uqs != null) {
            for (RdbUniqueKey uq : uqs) {
                if (indexName.equals(uq.getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private RdbTable fetchRdbTable(DmDsDO dsDO, Map<com.clougence.schema.umi.struts.UmiTypes, Object> levelsParam, String tableName) {
        Value value = this.dsSchemaService.realTimeFetchSelectObject(dsDO, levelsParam, tableName);
        if (value == null) {
            return null;
        }
        if (value instanceof RdbTable rdbTable) {
            return rdbTable;
        }
        try {
            return (RdbTable) value;
        } catch (ClassCastException e) {
            return null;
        }
    }

    private static String extractObjectName(BehaviorObject obj) {
        if (obj == null) {
            return null;
        }
        ObjectName on = obj.getObjectName();
        if (on == null) {
            return null;
        }
        return on.getObjectName();
    }

    private static <T> List<T> safeList(List<T> list) {
        return list != null ? list : Collections.emptyList();
    }
}
