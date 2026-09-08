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

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.base.metadata.ds.DataSourceConfig;
import com.clougence.clouddm.console.web.component.analysis.AnalysisQueryOptions;
import com.clougence.clouddm.console.web.component.analysis.QueryAnalysisService;
import com.clougence.clouddm.console.web.component.dsconfig.DmDsConfigService;
import com.clougence.clouddm.console.web.component.dsconfig.mode.DsLevels;
import com.clougence.clouddm.console.web.component.schema.DsSchemaService;
import com.clougence.clouddm.platform.dal.model.datasource.DmDsDO;
import com.clougence.clouddm.sdk.execute.session.QueryRequest;
import com.clougence.clouddm.sdk.sql.analysis.behavior.BehaviorAction;
import com.clougence.clouddm.sdk.sql.analysis.behavior.BehaviorObject;
import com.clougence.clouddm.sdk.sql.analysis.behavior.BehaviorRelation;
import com.clougence.clouddm.sdk.sql.analysis.behavior.ObjectName;
import com.clougence.clouddm.sdk.sql.analysis.behavior.TargetType;
import com.clougence.clouddm.sdk.sql.parser.SplitQueryType;
import com.clougence.schema.umi.special.rdb.RdbColumn;
import com.clougence.schema.umi.special.rdb.RdbTable;
import com.clougence.schema.umi.struts.Value;

public class GovPreflightCheckerTest {

    private GovPreflightChecker   checker;
    private DsSchemaService      dsSchemaService;
    private DmDsConfigService    dmDsConfigService;
    private QueryAnalysisService queryAnalysisService;

    private static final long   DS_ID     = 20L;
    private static final long   ENV_ID    = 5L;

    @Before
    public void setUp() {
        dsSchemaService = mock(DsSchemaService.class);
        dmDsConfigService = mock(DmDsConfigService.class);
        queryAnalysisService = mock(QueryAnalysisService.class);

        checker = new GovPreflightChecker();
        ReflectionTestUtils.setField(checker, "dsSchemaService", dsSchemaService);
        ReflectionTestUtils.setField(checker, "dmDsConfigService", dmDsConfigService);
        ReflectionTestUtils.setField(checker, "queryAnalysisService", queryAnalysisService);

        // Default levelsParam mock
        DsLevels dsLevels = new DsLevels(
            String.valueOf(ENV_ID), new DmDsDO(),
            List.of(String.valueOf(ENV_ID), String.valueOf(DS_ID), "mydb"),
            List.of(String.valueOf(ENV_ID), String.valueOf(DS_ID), "mydb"),
            Collections.<com.clougence.schema.umi.struts.UmiTypes>emptyList(),
            Collections.<com.clougence.schema.umi.struts.UmiTypes, Object>emptyMap());
        when(dmDsConfigService.parseLevels(anyList())).thenReturn(dsLevels);
    }

    @Test
    public void check_connectivityFail_deny() {
        DmDsDO dsDO = buildDsDO();
        when(dsSchemaService.realTimeFetchVersion(eq(dsDO), anyMap()))
            .thenThrow(new RuntimeException("connection refused"));

        List<GateItem> results = checker.check(dsDO, buildLevels(), "SELECT 1", null);

        assertFalse(results.isEmpty());
        GateItem first = results.get(0);
        assertEquals("connectivity", first.getItem());
        assertFalse(first.isPass());
        assertTrue(first.getEvidence().contains("connection failed"));
    }

    @Test
    public void check_tableNotFound_deny() {
        DmDsDO dsDO = buildDsDO();
        when(dsSchemaService.realTimeFetchVersion(any(), any())).thenReturn("MySQL 8.0");
        when(dsSchemaService.realTimeFetchSelectObject(any(), any(), anyString())).thenReturn(null);
        when(queryAnalysisService.analysisRequestsStream(any(), any(), anyList(), anyInt(), anyInt(), any(AnalysisQueryOptions.class)))
            .thenReturn(Stream.<QueryRequest>empty());

        List<GateItem> results = checker.check(dsDO, buildLevels(), "SELECT 1", null);

        boolean hasConnPass = results.stream().anyMatch(r -> "connectivity".equals(r.getItem()) && r.isPass());
        assertTrue(hasConnPass);
    }

    @Test
    public void check_connectivityPass_tableExists_pass() {
        DmDsDO dsDO = buildDsDO();
        when(dsSchemaService.realTimeFetchVersion(any(), any())).thenReturn("MySQL 8.0");
        when(dsSchemaService.realTimeFetchSelectObject(any(), any(), anyString())).thenReturn(buildRdbTable());
        when(queryAnalysisService.analysisRequestsStream(any(), any(), anyList(), anyInt(), anyInt(), any(AnalysisQueryOptions.class)))
            .thenReturn(Stream.<QueryRequest>empty());

        List<GateItem> results = checker.check(dsDO, buildLevels(), "CREATE TABLE foo", null);

        boolean allPass = results.stream().allMatch(GateItem::isPass);
        assertTrue(allPass);
    }

    @Test
    public void check_parserFailure_degradesToSkip() {
        DmDsDO dsDO = buildDsDO();
        when(dsSchemaService.realTimeFetchVersion(eq(dsDO), anyMap())).thenReturn("MySQL 8.0");
        when(queryAnalysisService.analysisRequestsStream(any(), any(), anyList(), anyInt(), anyInt(), any(AnalysisQueryOptions.class)))
            .thenThrow(new RuntimeException("parse error"));

        List<GateItem> results = checker.check(dsDO, buildLevels(), "some complex SQL", null);

        // Connectivity passes, then parser fails → degraded SKIP (not DENY)
        boolean hasConnPass = results.stream().anyMatch(r -> "connectivity".equals(r.getItem()) && r.isPass());
        assertTrue(hasConnPass);
        boolean hasParserSkip = results.stream().anyMatch(r -> r.getItem() != null && r.getItem().contains("parser_skip") && r.isPass());
        assertTrue("Parser failure should degrade to SKIP (pass), not DENY", hasParserSkip);
    }

    // ======= helpers =======

    private DmDsDO buildDsDO() {
        DmDsDO ds = new DmDsDO();
        ds.setId(DS_ID);
        ds.setDsEnvId(ENV_ID);
        return ds;
    }

    private List<String> buildLevels() {
        List<String> levels = new ArrayList<>();
        levels.add(String.valueOf(ENV_ID));
        levels.add(String.valueOf(DS_ID));
        levels.add("mydb");
        return levels;
    }

    private RdbTable buildRdbTable() {
        RdbTable table = new RdbTable();
        Map<String, RdbColumn> columns = new HashMap<>();
        RdbColumn col = new RdbColumn();
        col.setName("id");
        columns.put("id", col);
        table.setColumns(columns);
        return table;
    }
}
