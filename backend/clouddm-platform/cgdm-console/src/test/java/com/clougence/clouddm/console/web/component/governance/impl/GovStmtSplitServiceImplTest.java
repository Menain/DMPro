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
package com.clougence.clouddm.console.web.component.governance.impl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.io.Reader;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.base.metadata.ds.DataSourceConfig;
import com.clougence.clouddm.console.web.component.analysis.QueryAnalysisService;
import com.clougence.clouddm.console.web.component.governance.GovSplitResult;
import com.clougence.clouddm.console.web.component.governance.GovStmtRow;
import com.clougence.clouddm.console.web.component.governance.GovSqlHashUtils;
import com.clougence.clouddm.platform.dal.model.dbchange.ChangeType;
import com.clougence.clouddm.sdk.sql.parser.SplitQueryType;
import com.clougence.clouddm.sdk.sql.parser.SplitScript;

public class GovStmtSplitServiceImplTest {

    private GovStmtSplitServiceImpl service;
    private QueryAnalysisService     queryAnalysisService;

    @Before
    public void setUp() {
        service = new GovStmtSplitServiceImpl();
        queryAnalysisService = mock(QueryAnalysisService.class);
        ReflectionTestUtils.setField(service, "queryAnalysisService", queryAnalysisService);
    }

    @Test
    public void pureDdl_changeTypeDdl() {
        mockSplit(createScript(0, Set.of(SplitQueryType.CREATE_TABLE), "CREATE TABLE foo (id INT)"));
        GovSplitResult result = service.split(mockDsConfig(), "CREATE TABLE foo (id INT)");
        assertEquals(ChangeType.DDL, result.getChangeType());
        assertEquals(1, result.getStmts().size());
        assertEquals(1, result.getStmts().get(0).getStmtIndex());
        assertEquals("CREATE TABLE foo (id INT)", result.getStmts().get(0).getStmtText());
        assertEquals(GovSqlHashUtils.hash("CREATE TABLE foo (id INT)"), result.getStmts().get(0).getStmtHash());
    }

    @Test
    public void pureDml_changeTypeDml() {
        mockSplit(createScript(0, Set.of(SplitQueryType.INSERT), "INSERT INTO foo VALUES (1)"));
        GovSplitResult result = service.split(mockDsConfig(), "INSERT INTO foo VALUES (1)");
        assertEquals(ChangeType.DML, result.getChangeType());
    }

    @Test
    public void mixedDdlDml_changeTypeMixed() {
        mockSplit(
            createScript(0, Set.of(SplitQueryType.CREATE_TABLE), "CREATE TABLE foo (id INT)"),
            createScript(1, Set.of(SplitQueryType.INSERT), "INSERT INTO foo VALUES (1)")
        );
        GovSplitResult result = service.split(mockDsConfig(), "CREATE TABLE foo (id INT);\nINSERT INTO foo VALUES (1)");
        assertEquals(ChangeType.MIXED, result.getChangeType());
        assertEquals(2, result.getStmts().size());
        assertEquals(1, result.getStmts().get(0).getStmtIndex());
        assertEquals(2, result.getStmts().get(1).getStmtIndex());
    }

    @Test
    public void selectStatement_rejected() {
        mockSplit(createScript(0, Set.of(SplitQueryType.SELECT), "SELECT * FROM foo"));
        try {
            service.split(mockDsConfig(), "SELECT * FROM foo");
            fail("Should reject SELECT");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("rejected statement type"));
        }
    }

    @Test
    public void emptySplit_rejected() {
        when(queryAnalysisService.analysisSplitStream(any(), any(Reader.class), anyList(), anyInt(), anyInt()))
            .thenReturn(Stream.empty());
        try {
            service.split(mockDsConfig(), "");
            fail("Should reject empty SQL");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("at least one"));
        }
    }

    @Test
    public void multipleDml_statementsIndexedFromOne() {
        mockSplit(
            createScript(0, Set.of(SplitQueryType.INSERT), "INSERT INTO a VALUES (1)"),
            createScript(1, Set.of(SplitQueryType.UPDATE), "UPDATE b SET v = 2"),
            createScript(2, Set.of(SplitQueryType.DELETE), "DELETE FROM c WHERE v = 3")
        );
        GovSplitResult result = service.split(mockDsConfig(), "INSERT INTO a VALUES (1); UPDATE b SET v = 2; DELETE FROM c WHERE v = 3");
        assertEquals(ChangeType.DML, result.getChangeType());
        assertEquals(3, result.getStmts().size());
        assertEquals(1, result.getStmts().get(0).getStmtIndex());
        assertEquals(2, result.getStmts().get(1).getStmtIndex());
        assertEquals(3, result.getStmts().get(2).getStmtIndex());
    }

    @Test
    public void alterTable_treatedAsDdl() {
        mockSplit(createScript(0, Set.of(SplitQueryType.ALTER_TABLE), "ALTER TABLE foo ADD COLUMN bar INT"));
        GovSplitResult result = service.split(mockDsConfig(), "ALTER TABLE foo ADD COLUMN bar INT");
        assertEquals(ChangeType.DDL, result.getChangeType());
    }

    @Test
    public void dropTable_treatedAsDdl() {
        mockSplit(createScript(0, Set.of(SplitQueryType.DROP_TABLE), "DROP TABLE foo"));
        GovSplitResult result = service.split(mockDsConfig(), "DROP TABLE foo");
        assertEquals(ChangeType.DDL, result.getChangeType());
    }

    @Test
    public void merge_treatedAsDml() {
        mockSplit(createScript(0, Set.of(SplitQueryType.MERGE), "MERGE INTO foo USING bar ..."));
        GovSplitResult result = service.split(mockDsConfig(), "MERGE INTO foo USING bar ...");
        assertEquals(ChangeType.DML, result.getChangeType());
    }

    @Test
    public void stmtHash_matchesGovSqlHashUtils() {
        String sql = "CREATE TABLE foo (id INT)";
        mockSplit(createScript(0, Set.of(SplitQueryType.CREATE_TABLE), sql));
        GovSplitResult result = service.split(mockDsConfig(), sql);
        assertEquals(GovSqlHashUtils.hash(sql), result.getStmts().get(0).getStmtHash());
    }

    // --- helpers ---

    private void mockSplit(SplitScript... scripts) {
        when(queryAnalysisService.analysisSplitStream(any(), any(Reader.class), any(), anyInt(), anyInt()))
            .thenReturn(Stream.of(scripts));
    }

    private static SplitScript createScript(long index, Set<SplitQueryType> type, String script) {
        SplitScript s = new SplitScript();
        s.setIndex(index);
        s.setType(type);
        s.setScript(script);
        return s;
    }

    private static DataSourceConfig mockDsConfig() {
        return new DataSourceConfig();
    }
}
