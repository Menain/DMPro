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

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import com.clougence.clouddm.api.common.boot.UnifiedPostConstruct;
import com.clougence.clouddm.console.web.component.governance.SchemaPatrolService;
import com.clougence.clouddm.platform.dal.access.GovSchemaPatrolDal;
import com.clougence.clouddm.platform.dal.access.SystemDal;
import com.clougence.clouddm.platform.dal.model.govpatrol.DmGovSchemaPatrolRunDO;
import com.clougence.utils.ThreadUtils;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * Schema drift patrol scheduler (sub-task C).
 * <p>
 * AutoExecScheduleService-style tick: a single-thread daemon pool runs
 * {@link #tick()} every 15 minutes. Each tick checks:
 * <ol>
 *   <li>{@code govSchemaPatrolEnabled} (default true)</li>
 *   <li>no RUNNING run exists</li>
 *   <li>enough time elapsed since the last AUTO run (interval default 1440 min)</li>
 * </ol>
 * All three must pass to start a new AUTO patrol. All config is live-read from
 * SystemDal on every tick — no restart needed to change parameters.
 * <p>
 * Exceptions never escape the tick ( Throwable-guarded ) so the scheduler thread
 * survives pair-level or config-level failures.
 */
@Slf4j
@Service
public class SchemaPatrolScheduler implements UnifiedPostConstruct {

    private static final String CFG_ENABLED      = "govSchemaPatrolEnabled";
    private static final String CFG_INTERVAL_MIN = "govSchemaPatrolIntervalMinutes";

    private static final String SYSTEM_OPERATOR  = "SYSTEM";
    private static final String TRIGGER_AUTO     = "AUTO";

    @Resource
    private SchemaPatrolService  patrolService;
    @Resource
    private GovSchemaPatrolDal   patrolDal;
    @Resource
    private SystemDal           systemDal;

    private ScheduledThreadPoolExecutor tickExecutor;

    @Override
    public void init() {
        tickExecutor = new ScheduledThreadPoolExecutor(1,
            ThreadUtils.daemonThreadFactory(this.getClass().getClassLoader(), "SchemaPatrolTick-%s"));
        // initial delay 5 min, period 15 min
        tickExecutor.scheduleWithFixedDelay(this::tick, 5, 15, TimeUnit.MINUTES);
        log.info("SchemaPatrolScheduler started (tick every 15 min)");
    }

    @Override
    public void stop() {
        if (tickExecutor != null) {
            tickExecutor.shutdownNow();
        }
    }

    /**
     * Single tick — checks all three conditions and starts an AUTO patrol if eligible.
     * Package-private for unit testing.
     */
    void tick() {
        try {
            // 1. Enabled check
            Boolean enabled = fetchConfig(CFG_ENABLED, Boolean.class, true);
            if (enabled == null || !enabled) {
                return;
            }

            // 2. No RUNNING run
            DmGovSchemaPatrolRunDO running = patrolDal.runMapper().queryRunning();
            if (running != null) {
                log.debug("[SchemaPatrol] tick: RUNNING run {} exists, skipping", running.getId());
                return;
            }

            // 3. Interval elapsed
            DmGovSchemaPatrolRunDO lastAuto = patrolDal.runMapper().queryLatestAuto();
            if (lastAuto != null && lastAuto.getGmtCreate() != null) {
                long intervalMin = fetchConfig(CFG_INTERVAL_MIN, Long.class, 1440L);
                long elapsedMin = (System.currentTimeMillis() - lastAuto.getGmtCreate().getTime()) / 60_000L;
                if (elapsedMin < intervalMin) {
                    log.debug("[SchemaPatrol] tick: last AUTO run {} was {} min ago, interval={} min, skipping",
                        lastAuto.getId(), elapsedMin, intervalMin);
                    return;
                }
            }

            // All conditions met — start patrol
            long runId = patrolService.startRun(TRIGGER_AUTO, SYSTEM_OPERATOR, null);
            if (runId > 0) {
                log.info("[SchemaPatrol] tick: started AUTO patrol run {}", runId);
            }
        } catch (Exception e) {
            log.error("[SchemaPatrol] tick error", e);
        }
    }

    private <T> T fetchConfig(String key, Class<T> type, T defaultValue) {
        try {
            return systemDal.fetchSystemConf(key, type, defaultValue);
        } catch (Exception e) {
            log.warn("[SchemaPatrol] Failed to read config {}: {}", key, e.getMessage());
            return defaultValue;
        }
    }
}
