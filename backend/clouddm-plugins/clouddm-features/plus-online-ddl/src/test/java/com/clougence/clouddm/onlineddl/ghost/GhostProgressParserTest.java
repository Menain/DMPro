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
package com.clougence.clouddm.onlineddl.ghost;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.clougence.clouddm.sdk.model.ghost.GhostStatusDTO;

/**
 * Progress parser fixtures use the REAL log lines recorded by the 2026-09-24 spike
 * (research/spike-result.md §3) and the format templates extracted from the bundled gh-ost 1.1.4
 * binary ("Copy: %d/%d %.1f%%; Applied: %d; Backlog: %d/%d; Time: %+v(total), %+v(copy);
 * streamer: %+v; Lag: %.2fs, HeartbeatLag: %.2fs, State: %s; ETA: %s").
 */
public class GhostProgressParserTest {

    // ------------------------------------------------------------------ pure line parsing

    @Test
    public void copyStatusLineSpikeShape() {
        GhostLogEvent event = GhostProgressParser.parseLine(
                "2026-09-24 17:13:22 INFO Copy: 39/39 100.0%; Applied: 0; Backlog: 0/1000; Time: 1s(total), 1s(copy); streamer: mysql-bin.001157:83828365; Lag: 0.01s, HeartbeatLag: 0.02s, State: migrating; ETA: due");

        assertEquals(GhostLogEvent.Type.COPY_PROGRESS, event.getType());
        assertEquals(39, event.getCurValue());
        assertEquals(39, event.getMaxValue());
        assertEquals(100.0, event.getPercent(), 0.001);
        assertEquals(0, event.getApplied());
        assertEquals("0/1000", event.getBacklog());
        // the real Lag is captured, NOT the HeartbeatLag value
        assertEquals("0.01", event.getLag());
        assertEquals("migrating", event.getState());
        assertEquals("due", event.getEta());
    }

    @Test
    public void copyStatusLineMidMigration() {
        GhostLogEvent event = GhostProgressParser.parseLine(
                "2026-09-24 17:20:01 INFO Copy: 45300/100000 45.3%; Applied: 12; Backlog: 0/1000; Time: 30s(total), 29s(copy); streamer: mysql-bin.001157:84000000; Lag: 0.02s, HeartbeatLag: 0.03s, State: migrating; ETA: 35s");

        assertEquals(GhostLogEvent.Type.COPY_PROGRESS, event.getType());
        assertEquals(45300, event.getCurValue());
        assertEquals(100000, event.getMaxValue());
        assertEquals("35s", event.getEta());
    }

    @Test
    public void oldTableNameCapturedFromMagicCutOverLine() {
        GhostLogEvent event = GhostProgressParser.parseLine(
                "2026-09-24 17:13:24 INFO Creating magic cut-over table `linshiceshi`.`_t_spike_20260924171321_del`");

        assertEquals(GhostLogEvent.Type.OLD_TABLE, event.getType());
        assertEquals("_t_spike_20260924171321_del", event.getValue());
    }

    @Test
    public void phaseMarkers() {
        assertEquals(GhostLogEvent.Phase.GHOST_TABLE_CREATED,
                GhostProgressParser.parseLine("2026-09-24 17:13:21 INFO Ghost table created").getPhase());
        assertEquals(GhostLogEvent.Phase.ROW_COPY_COMPLETE,
                GhostProgressParser.parseLine("2026-09-24 17:13:23 INFO Row copy complete").getPhase());
        assertEquals(GhostLogEvent.Phase.CUT_OVER,
                GhostProgressParser.parseLine("2026-09-24 17:13:24 INFO Grabbing voluntary lock").getPhase());
        assertEquals(GhostLogEvent.Phase.CUT_OVER,
                GhostProgressParser.parseLine("2026-09-24 17:13:26 INFO Tables renamed").getPhase());
        assertEquals(GhostLogEvent.Phase.CUT_OVER,
                GhostProgressParser.parseLine("2026-09-24 17:13:26 INFO Lock & rename duration: 1.987916335s").getPhase());
        assertEquals(GhostLogEvent.Phase.DONE,
                GhostProgressParser.parseLine("2026-09-24 17:13:26 INFO Done migrating `linshiceshi`.`t_spike`").getPhase());
        assertEquals(GhostLogEvent.Phase.DONE, GhostProgressParser.parseLine("# Done").getPhase());
    }

    @Test
    public void fatalLineCaptured() {
        GhostLogEvent event = GhostProgressParser.parseLine(
                "2026-09-24 17:10:00 FATAL Unexpected database port reported: 20336");

        assertEquals(GhostLogEvent.Type.FATAL, event.getType());
        assertEquals("Unexpected database port reported: 20336", event.getValue());
    }

    @Test
    public void teardownNoiseIsNeverAFailure() {
        // spike-result §3.2: both lines are normal shutdown races right before "# Done"
        assertEquals(GhostLogEvent.Type.NOISE,
                GhostProgressParser.parseLine("2026-09-24 17:13:26 ERROR [error] binlogsyncer.go:631 connection was bad").getType());
        assertEquals(GhostLogEvent.Type.NOISE,
                GhostProgressParser.parseLine("2026-09-24 17:13:26 ERROR [error] binlogstreamer.go:77 close sync with err").getType());
        // the benign streamer close line is simply ignored (not noise-marked, not fatal)
        assertEquals(GhostLogEvent.Type.IGNORED,
                GhostProgressParser.parseLine("2026-09-24 17:13:26 INFO Closed streamer connection. err=<nil>").getType());
    }

    @Test
    public void throttleMarkers() {
        assertEquals(GhostLogEvent.Type.THROTTLED,
                GhostProgressParser.parseLine("2026-09-24 17:13:24 INFO throttling before swapping tables").getType());
        assertEquals(GhostLogEvent.Type.UNTHROTTLED,
                GhostProgressParser.parseLine("2026-09-24 17:13:25 INFO done throttling").getType());
    }

    @Test
    public void unrelatedLinesIgnored() {
        assertEquals(GhostLogEvent.Type.IGNORED,
                GhostProgressParser.parseLine("2026-09-24 17:13:21 INFO Migrating `linshiceshi`.`t_spike`").getType());
        assertEquals(GhostLogEvent.Type.IGNORED, GhostProgressParser.parseLine("").getType());
        assertEquals(GhostLogEvent.Type.IGNORED, GhostProgressParser.parseLine(null).getType());
    }

    // ------------------------------------------------------------------ state accumulation

    @Test
    public void stateAccumulatesSpikeSequence() {
        GhostProgressState state = new GhostProgressState();
        state.markRunning();

        state.apply(GhostProgressParser.parseLine("2026-09-24 17:13:21 INFO Ghost table created"));
        state.apply(GhostProgressParser.parseLine(
                "2026-09-24 17:13:22 INFO Copy: 0/39 0.0%; Applied: 0; Backlog: 0/1000; Time: 1s(total), 0s(copy); streamer: mysql-bin.001157:83827953; Lag: 0.01s, HeartbeatLag: 0.02s, State: migrating; ETA: N/A"));
        state.apply(GhostProgressParser.parseLine(
                "2026-09-24 17:13:23 INFO Copy: 39/39 100.0%; Applied: 0; Backlog: 0/1000; Time: 1s(total), 1s(copy); streamer: mysql-bin.001157:83828365; Lag: 0.01s, HeartbeatLag: 0.02s, State: migrating; ETA: due"));
        state.apply(GhostProgressParser.parseLine("2026-09-24 17:13:23 INFO Row copy complete"));
        state.apply(GhostProgressParser.parseLine("2026-09-24 17:13:24 INFO Grabbing voluntary lock"));
        state.apply(GhostProgressParser.parseLine(
                "2026-09-24 17:13:24 INFO Creating magic cut-over table `linshiceshi`.`_t_spike_20260924171321_del`"));
        state.apply(GhostProgressParser.parseLine("2026-09-24 17:13:26 INFO Tables renamed"));
        state.apply(GhostProgressParser.parseLine("2026-09-24 17:13:26 ERROR [error] binlogsyncer.go:631 connection was bad"));
        state.apply(GhostProgressParser.parseLine("2026-09-24 17:13:26 ERROR [error] binlogstreamer.go:77 close sync with err"));
        state.apply(GhostProgressParser.parseLine("# Done"));
        state.markCompleted();

        GhostStatusDTO dto = state.toStatusDTO();
        assertEquals(GhostProgressState.STATUS_COMPLETED, dto.getStatus());
        assertEquals(GhostProgressState.PHASE_DONE, dto.getPhase());
        assertTrue(dto.isUseProgress());
        assertEquals(39, dto.getCurValue());
        assertEquals(39, dto.getMaxValue());
        assertEquals("_t_spike_20260924171321_del", dto.getOldTableName());
        assertNull(dto.getFailReason());
        assertTrue(dto.getMessage().contains("Copy: 39/39 100.0%"));
        assertTrue(dto.getMessage().contains("Lag: 0.01s"));
        assertTrue(dto.getMessage().contains("ETA: due"));
    }

    @Test
    public void phaseNeverDowngrades() {
        GhostProgressState state = new GhostProgressState();
        state.apply(GhostLogEvent.phase(GhostLogEvent.Phase.CUT_OVER));
        // a late status line after cut-over must not drag the phase back to COPYING
        state.apply(GhostProgressParser.parseLine(
                "2026-09-24 17:13:26 INFO Copy: 39/39 100.0%; Applied: 0; Backlog: 0/1000; Time: 5s(total), 1s(copy); streamer: mysql-bin.001157:1; Lag: 0.01s, HeartbeatLag: 0.02s, State: migrating; ETA: due"));

        assertEquals(GhostProgressState.PHASE_CUT_OVER, state.toStatusDTO().getPhase());
    }

    @Test
    public void fatalMarksFailureWithSummary() {
        GhostProgressState state = new GhostProgressState();
        state.markRunning();
        state.apply(GhostProgressParser.parseLine("2026-09-24 17:10:00 FATAL Unexpected database port reported: 20336"));
        state.markFailed("gh-ost exited with code 1.");

        GhostStatusDTO dto = state.toStatusDTO();
        assertEquals(GhostProgressState.STATUS_FAILED, dto.getStatus());
        // explicit fail reason wins; the FATAL summary stays reachable through the message
        assertEquals("gh-ost exited with code 1.", dto.getFailReason());
        assertTrue(dto.getMessage().contains("FATAL: Unexpected database port reported: 20336"));
    }

    @Test
    public void userPauseOutranksLogUnthrottle() {
        GhostProgressState state = new GhostProgressState();
        state.markRunning();
        state.markUserPaused();
        assertEquals(GhostProgressState.STATUS_PAUSED, state.getStatus());

        // a stale "done throttling" line must not resume a user-paused session
        state.apply(GhostLogEvent.throttled(false));
        assertEquals(GhostProgressState.STATUS_PAUSED, state.getStatus());

        state.markUserResumed();
        assertEquals(GhostProgressState.STATUS_RUNNING, state.getStatus());

        // log-side throttle corroboration flips RUNNING → PAUSED on its own
        state.apply(GhostLogEvent.throttled(true));
        assertEquals(GhostProgressState.STATUS_PAUSED, state.getStatus());
        state.apply(GhostLogEvent.throttled(false));
        assertEquals(GhostProgressState.STATUS_RUNNING, state.getStatus());
    }

    @Test
    public void abortIsTerminalAndSurvivesCompleted() {
        GhostProgressState state = new GhostProgressState();
        state.markAborted("stopped by user (panic flag), gh-ost exit code 1.");
        state.markCompleted(); // must NOT downgrade an abort

        assertEquals(GhostProgressState.STATUS_ABORTED, state.getStatus());
        assertTrue(state.isFinished());
        assertFalse(GhostProgressState.STATUS_COMPLETED.equals(state.getStatus()));
    }

    @Test
    public void freshStateIsInitWithoutProgress() {
        GhostStatusDTO dto = new GhostProgressState().toStatusDTO();

        assertEquals(GhostProgressState.STATUS_INIT, dto.getStatus());
        assertEquals(GhostProgressState.PHASE_READY, dto.getPhase());
        assertFalse(dto.isUseProgress());
        assertEquals(0, dto.getCurValue());
        assertEquals(0, dto.getMaxValue());
    }
}
