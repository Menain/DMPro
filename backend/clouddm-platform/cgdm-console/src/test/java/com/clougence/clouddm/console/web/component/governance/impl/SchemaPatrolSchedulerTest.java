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

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.Date;

import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.console.web.component.governance.SchemaPatrolService;
import com.clougence.clouddm.platform.dal.access.GovSchemaPatrolDal;
import com.clougence.clouddm.platform.dal.access.SystemDal;
import com.clougence.clouddm.platform.dal.mapper.govpatrol.DmGovSchemaPatrolRunMapper;
import com.clougence.clouddm.platform.dal.model.govpatrol.DmGovSchemaPatrolRunDO;

/**
 * Unit tests for SchemaPatrolScheduler (sub-task C, layer: tick lifecycle).
 * <p>
 * Pins: tick three-branch gate (disabled / RUNNING exists / interval not elapsed),
 * all-pass starts AUTO run, exception never escapes.
 */
public class SchemaPatrolSchedulerTest {

    private SchemaPatrolScheduler scheduler;
    private SchemaPatrolService  patrolService;
    private GovSchemaPatrolDal   patrolDal;
    private DmGovSchemaPatrolRunMapper runMapper;
    private SystemDal           systemDal;

    @Before
    public void setUp() {
        scheduler = new SchemaPatrolScheduler();
        patrolService = mock(SchemaPatrolService.class);
        patrolDal = mock(GovSchemaPatrolDal.class);
        runMapper = mock(DmGovSchemaPatrolRunMapper.class);
        systemDal = mock(SystemDal.class);

        when(patrolDal.runMapper()).thenReturn(runMapper);

        ReflectionTestUtils.setField(scheduler, "patrolService", patrolService);
        ReflectionTestUtils.setField(scheduler, "patrolDal", patrolDal);
        ReflectionTestUtils.setField(scheduler, "systemDal", systemDal);
    }

    @Test
    public void tick_disabled_doesNotStartRun() {
        when(systemDal.fetchSystemConf(eq("govSchemaPatrolEnabled"), eq(Boolean.class), any()))
            .thenReturn(false);

        scheduler.tick();

        verify(runMapper, never()).queryRunning();
        verify(patrolService, never()).startRun(any(), any(), any());
    }

    @Test
    public void tick_runningExists_doesNotStartRun() {
        when(systemDal.fetchSystemConf(eq("govSchemaPatrolEnabled"), eq(Boolean.class), any()))
            .thenReturn(true);
        DmGovSchemaPatrolRunDO running = new DmGovSchemaPatrolRunDO();
        running.setId(1L);
        running.setStatus("RUNNING");
        when(runMapper.queryRunning()).thenReturn(running);

        scheduler.tick();

        verify(runMapper, never()).queryLatestAuto();
        verify(patrolService, never()).startRun(any(), any(), any());
    }

    @Test
    public void tick_intervalNotElapsed_doesNotStartRun() {
        when(systemDal.fetchSystemConf(eq("govSchemaPatrolEnabled"), eq(Boolean.class), any()))
            .thenReturn(true);
        when(runMapper.queryRunning()).thenReturn(null);

        DmGovSchemaPatrolRunDO lastAuto = new DmGovSchemaPatrolRunDO();
        lastAuto.setId(1L);
        lastAuto.setGmtCreate(new Date(System.currentTimeMillis() - 10 * 60_000));
        when(runMapper.queryLatestAuto()).thenReturn(lastAuto);
        when(systemDal.fetchSystemConf(eq("govSchemaPatrolIntervalMinutes"), eq(Long.class), any()))
            .thenReturn(1440L);

        scheduler.tick();

        verify(patrolService, never()).startRun(any(), any(), any());
    }

    @Test
    public void tick_allConditionsMet_startsAutoRun() {
        when(systemDal.fetchSystemConf(eq("govSchemaPatrolEnabled"), eq(Boolean.class), any()))
            .thenReturn(true);
        when(runMapper.queryRunning()).thenReturn(null);
        when(runMapper.queryLatestAuto()).thenReturn(null);
        when(patrolService.startRun(eq("AUTO"), eq("SYSTEM"), isNull()))
            .thenReturn(42L);

        scheduler.tick();

        verify(patrolService).startRun("AUTO", "SYSTEM", null);
    }

    @Test
    public void tick_intervalElapsed_startsRun() {
        when(systemDal.fetchSystemConf(eq("govSchemaPatrolEnabled"), eq(Boolean.class), any()))
            .thenReturn(true);
        when(runMapper.queryRunning()).thenReturn(null);

        DmGovSchemaPatrolRunDO lastAuto = new DmGovSchemaPatrolRunDO();
        lastAuto.setId(1L);
        lastAuto.setGmtCreate(new Date(System.currentTimeMillis() - 2 * 24 * 60 * 60_000L));
        when(runMapper.queryLatestAuto()).thenReturn(lastAuto);
        when(systemDal.fetchSystemConf(eq("govSchemaPatrolIntervalMinutes"), eq(Long.class), any()))
            .thenReturn(1440L);
        when(patrolService.startRun(eq("AUTO"), eq("SYSTEM"), isNull()))
            .thenReturn(43L);

        scheduler.tick();

        verify(patrolService).startRun("AUTO", "SYSTEM", null);
    }

    @Test
    public void tick_exceptionDoesNotPropagate() {
        // fetchSystemConf throws — fetchConfig catches and returns default (true),
        // so the tick continues but other mocks return defaults (null = no RUNNING,
        // null = no lastAuto). The key pin: exception does not escape tick().
        when(systemDal.fetchSystemConf(eq("govSchemaPatrolEnabled"), eq(Boolean.class), any()))
            .thenThrow(new RuntimeException("config read error"));
        when(runMapper.queryRunning()).thenReturn(null);
        when(runMapper.queryLatestAuto()).thenReturn(null);

        // Should not throw — this is the pin
        scheduler.tick();
    }

    @Test
    public void tick_startRunReturnsNegative_doesNotThrow() {
        when(systemDal.fetchSystemConf(eq("govSchemaPatrolEnabled"), eq(Boolean.class), any()))
            .thenReturn(true);
        when(runMapper.queryRunning()).thenReturn(null);
        when(runMapper.queryLatestAuto()).thenReturn(null);
        when(patrolService.startRun(eq("AUTO"), eq("SYSTEM"), isNull()))
            .thenReturn(-1L);

        scheduler.tick();
    }
}
