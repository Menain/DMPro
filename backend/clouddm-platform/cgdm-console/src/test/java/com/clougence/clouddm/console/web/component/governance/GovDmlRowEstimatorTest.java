/*
 * Copyright 2026 杭州开云集致科技有限公司
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.clougence.clouddm.console.web.component.governance;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.base.metadata.ds.DataSourceConfig;
import com.clougence.clouddm.console.web.component.analysis.QueryAnalysisService;
import com.clougence.clouddm.console.web.component.dsconfig.DmDsConfigService;
import com.clougence.clouddm.console.web.component.dsconfig.mode.DsLevels;
import com.clougence.clouddm.console.web.component.execute.QueryService;

/**
 * Tests for GovDmlRowEstimator — three-branch row estimation.
 * The three branches (INSERT/UNSUPPORTED/NATIVE_EXPLAIN) mirror DmlExplainPreInitHandler.
 * Full branch testing requires PluginManager SPI lookups (static) which are not easily mockable;
 * these tests cover the no-SPI path and error handling, verifying the estimator never throws
 * and always returns a RowEstimate (0 + evidence on failure).
 */
public class GovDmlRowEstimatorTest {

    private GovDmlRowEstimator    estimator;
    private QueryAnalysisService  queryAnalysisService;
    private QueryService          queryService;
    private DmDsConfigService     dmDsConfigService;

    private static final String PUID = "puid-001";

    @Before
    public void setUp() {
        estimator = new GovDmlRowEstimator();
        queryAnalysisService = mock(QueryAnalysisService.class);
        queryService = mock(QueryService.class);
        dmDsConfigService = mock(DmDsConfigService.class);
        ReflectionTestUtils.setField(estimator, "queryAnalysisService", queryAnalysisService);
        ReflectionTestUtils.setField(estimator, "queryService", queryService);
        ReflectionTestUtils.setField(estimator, "dmDsConfigService", dmDsConfigService);
    }

    @Test
    public void estimate_noDsConfig_returnsZero() {
        GovDmlRowEstimator.RowEstimate result = estimator.estimate(PUID, null, null, List.of("UPDATE foo SET bar=1"));

        assertEquals(0, result.getEstimatedRows());
        assertNotNull(result.getEvidence());
        assertTrue(result.getEvidence().contains("no ExplainPlanSpi"));
    }

    @Test
    public void estimate_nullDataSourceType_returnsZero() {
        DataSourceConfig dsConfig = new DataSourceConfig();
        DsLevels levels = mock(DsLevels.class);

        GovDmlRowEstimator.RowEstimate result = estimator.estimate(PUID, dsConfig, levels, List.of("UPDATE foo SET bar=1"));

        assertEquals(0, result.getEstimatedRows());
        assertTrue(result.getEvidence().contains("no ExplainPlanSpi"));
    }

    @Test
    public void estimate_emptyStmtList_returnsZero() {
        DataSourceConfig dsConfig = new DataSourceConfig();
        DsLevels levels = mock(DsLevels.class);

        GovDmlRowEstimator.RowEstimate result = estimator.estimate(PUID, dsConfig, levels, List.of());

        // No SPI → returns 0 immediately
        assertEquals(0, result.getEstimatedRows());
    }

    @Test
    public void rowEstimate_valueObject() {
        GovDmlRowEstimator.RowEstimate estimate = new GovDmlRowEstimator.RowEstimate(42, "test-evidence");
        assertEquals(42, estimate.getEstimatedRows());
        assertEquals("test-evidence", estimate.getEvidence());
    }
}
