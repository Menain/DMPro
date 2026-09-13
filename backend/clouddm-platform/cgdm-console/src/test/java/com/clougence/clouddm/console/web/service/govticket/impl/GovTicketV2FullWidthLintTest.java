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
package com.clougence.clouddm.console.web.service.govticket.impl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.io.Reader;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.base.metadata.ds.DataSourceConfig;
import com.clougence.clouddm.console.web.component.analysis.QueryAnalysisService;
import com.clougence.clouddm.console.web.component.dsconfig.DmDsConfigService;
import com.clougence.clouddm.console.web.component.dsconfig.mode.DsLevels;
import com.clougence.clouddm.console.web.component.detectrule.SecRulesCheckResult;
import com.clougence.clouddm.console.web.model.fo.govticket.GovTicketV2CheckFO;
import com.clougence.clouddm.console.web.model.vo.govticket.GovTicketV2CheckVO;
import com.clougence.clouddm.platform.dal.access.DataSourceDal;
import com.clougence.clouddm.platform.dal.access.DbPairDal;
import com.clougence.clouddm.platform.dal.access.SystemDal;
import com.clougence.clouddm.platform.dal.mapper.datasource.DmDsMapper;
import com.clougence.clouddm.platform.dal.mapper.dbpair.DmDbPairMapper;
import com.clougence.clouddm.platform.dal.model.datasource.DmDsDO;
import com.clougence.clouddm.platform.dal.model.dbpair.DmDbPairDO;
import com.clougence.clouddm.sdk.sql.parser.SplitScript;
import com.clougence.clouddm.sdk.sql.parser.SplitQueryType;
import com.clougence.schema.umi.struts.UmiTypes;

/**
 * Integration tests for the full-width character lint hook in
 * {@link GovTicketV2ServiceImpl#check} (which delegates to runPrecheck).
 * <p>
 * Covers: hit-FAIL (full-width outside literal), pass (full-width inside
 * string literal goes through the full flow), and switch-off pass.
 * <p>
 * Pure Mockito + ReflectionTestUtils - no Spring context.
 */
public class GovTicketV2FullWidthLintTest {

    private GovTicketV2ServiceImpl  service;
    private SystemDal               systemDal;
    private DbPairDal               dbPairDal;
    private DmDbPairMapper          pairMapper;
    private DataSourceDal           dsDal;
    private DmDsMapper              dsMapper;
    private DmDsConfigService       dmDsConfigService;
    private QueryAnalysisService    queryAnalysisService;

    private static final String  PUID       = "puid-001";
    private static final String  UID         = "uid-001";
    private static final long    PAIR_ID     = 1L;
    private static final long    PRE_DS_ID   = 10L;
    private static final long    DS_ENV_ID   = 1L;
    private static final String  PRE_DB      = "pre_db";

    @Before
    public void setUp() {
        service = new GovTicketV2ServiceImpl();

        systemDal = mock(SystemDal.class);
        dbPairDal = mock(DbPairDal.class);
        pairMapper = mock(DmDbPairMapper.class);
        when(dbPairDal.pairMapper()).thenReturn(pairMapper);

        dsDal = mock(DataSourceDal.class);
        dsMapper = mock(DmDsMapper.class);
        when(dsDal.dsMapper()).thenReturn(dsMapper);

        dmDsConfigService = mock(DmDsConfigService.class);
        queryAnalysisService = mock(QueryAnalysisService.class);

        // Default: lint switch = block (null -> default true, but mock explicitly)
        when(systemDal.fetchSystemConf("govTicketFullWidthLintBlock", Boolean.class)).thenReturn(true);

        // Default pair: ENABLED with valid pre/prod fields
        DmDbPairDO pair = new DmDbPairDO();
        pair.setId(PAIR_ID);
        pair.setPreDsId(PRE_DS_ID);
        pair.setPreDbName(PRE_DB);
        pair.setStatus("ENABLED");
        when(pairMapper.selectById(PAIR_ID)).thenReturn(pair);

        // Default DS identity
        DmDsDO dsDO = new DmDsDO();
        dsDO.setId(PRE_DS_ID);
        dsDO.setDsEnvId(DS_ENV_ID);
        when(dsMapper.queryDsIdentityById(PRE_DS_ID)).thenReturn(dsDO);

        // Default config
        DataSourceConfig dsConfig = mock(DataSourceConfig.class);
        when(dmDsConfigService.fetchDsConfigFromExists(PRE_DS_ID)).thenReturn(dsConfig);

        // Default levels
        Map<UmiTypes, Object> levelsParam = new HashMap<>();
        levelsParam.put(UmiTypes.Schema, PRE_DB);
        DsLevels dsLevels = new DsLevels(
            String.valueOf(DS_ENV_ID), dsDO,
            List.of(String.valueOf(DS_ENV_ID), String.valueOf(PRE_DS_ID), PRE_DB),
            List.of(PRE_DB),
            List.of(UmiTypes.Schema),
            levelsParam);
        when(dmDsConfigService.parseLevels(anyList())).thenReturn(dsLevels);

        // Default split: one DDL script (safe for PRE_DDL)
        when(queryAnalysisService.analysisSplitStream(any(), any(Reader.class), any(), anyInt(), anyInt()))
            .thenAnswer(inv -> {
                SplitScript script = new SplitScript();
                script.setScript("CREATE TABLE t(id int)");
                script.setType(Collections.singleton(SplitQueryType.CREATE_TABLE));
                script.setIndex(0);
                return Stream.of(script);
            });

        // Default rules: empty (no blocks)
        when(queryAnalysisService.analysisRulesStream(any(), any(Reader.class), any(), anyInt(), anyInt(), any()))
            .thenAnswer(inv -> Stream.empty());

        ReflectionTestUtils.setField(service, "systemDal", systemDal);
        ReflectionTestUtils.setField(service, "dbPairDal", dbPairDal);
        ReflectionTestUtils.setField(service, "dsDal", dsDal);
        ReflectionTestUtils.setField(service, "dmDsConfigService", dmDsConfigService);
        ReflectionTestUtils.setField(service, "queryAnalysisService", queryAnalysisService);
    }

    // ==================== hit -> FAIL ====================

    @Test
    public void runPrecheck_fullWidthOutsideString_fail() {
        // Full-width semicolon at end — outside any literal
        String sql = "CREATE TABLE `bebe` (\n  `c1` tinyint DEFAULT NULL\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci；";

        GovTicketV2CheckVO vo = service.check(PUID, UID, buildFO("PRE_DDL", sql));

        GovTicketV2CheckVO.GroupResult gr = vo.getGroups().get(0);
        assertEquals("FAIL", gr.getCheckStatus());
        assertNotNull(gr.getErrorMessage());
        // Message should contain character, code point, line, col
        assertTrue("should contain character", gr.getErrorMessage().contains("；"));
        assertTrue("should contain code point", gr.getErrorMessage().contains("U+FF1B"));
        assertTrue("should contain line", gr.getErrorMessage().contains("line 3"));
        assertTrue("should contain col", gr.getErrorMessage().contains("col 67"));
        assertTrue("should mention outside literal/comment", gr.getErrorMessage().contains("outside string literal/comment"));
        // Lint fails before pair resolution, so dbName should not be set
        assertNull(gr.getDbName());
    }

    @Test
    public void runPrecheck_multipleFullWidthHitsFirstFiveOnly() {
        // 7 full-width chars — message should list only first 5
        String sql = "SELECT；；；；；；；1";

        GovTicketV2CheckVO vo = service.check(PUID, UID, buildFO("PRE_DDL", sql));

        GovTicketV2CheckVO.GroupResult gr = vo.getGroups().get(0);
        assertEquals("FAIL", gr.getCheckStatus());
        // Count the number of "Full-width character" lines
        String[] lines = gr.getErrorMessage().split("\n");
        assertEquals("should list exactly 5 hits", 5, lines.length);
    }

    // ==================== pass: full-width inside string literal ====================

    @Test
    public void runPrecheck_fullWidthInsideStringLiteral_pass() {
        // Full-width chars inside single-quoted string — should NOT trigger lint
        String sql = "INSERT INTO t VALUES ('hello；world，test')";

        GovTicketV2CheckVO vo = service.check(PUID, UID, buildFO("PRE_DDL", sql));

        GovTicketV2CheckVO.GroupResult gr = vo.getGroups().get(0);
        assertEquals("PASS", gr.getCheckStatus());
        assertNull(gr.getErrorMessage());
    }

    // ==================== pass: switch off ====================

    @Test
    public void runPrecheck_switchOff_pass() {
        // Full-width semicolon outside string, but switch is off
        when(systemDal.fetchSystemConf("govTicketFullWidthLintBlock", Boolean.class)).thenReturn(false);
        String sql = "SELECT * FROM t；";

        GovTicketV2CheckVO vo = service.check(PUID, UID, buildFO("PRE_DDL", sql));

        GovTicketV2CheckVO.GroupResult gr = vo.getGroups().get(0);
        assertEquals("PASS", gr.getCheckStatus());
        assertNull(gr.getErrorMessage());
    }

    @Test
    public void runPrecheck_switchNullDefaultsToBlock_fail() {
        // Switch config not set (null) -> default true (block)
        when(systemDal.fetchSystemConf("govTicketFullWidthLintBlock", Boolean.class)).thenReturn(null);
        String sql = "SELECT * FROM t；";

        GovTicketV2CheckVO vo = service.check(PUID, UID, buildFO("PRE_DDL", sql));

        GovTicketV2CheckVO.GroupResult gr = vo.getGroups().get(0);
        assertEquals("FAIL", gr.getCheckStatus());
        assertTrue(gr.getErrorMessage().contains("U+FF1B"));
    }

    // ==================== helpers ====================

    private GovTicketV2CheckFO buildFO(String ticketType, String sqlContent) {
        GovTicketV2CheckFO fo = new GovTicketV2CheckFO();
        fo.setTicketType(ticketType);
        GovTicketV2CheckFO.GroupInput gi = new GovTicketV2CheckFO.GroupInput();
        gi.setPairId(PAIR_ID);
        gi.setSqlContent(sqlContent);
        fo.setGroups(List.of(gi));
        return fo;
    }
}
