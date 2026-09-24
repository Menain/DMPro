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

import lombok.Getter;

/**
 * Immutable line-level event produced by {@link GhostProgressParser#parseLine(String)} (pure
 * function: line → event). Applied onto {@link GhostProgressState} by the executor's output pump.
 */
@Getter
public final class GhostLogEvent {

    public enum Type {
        /** gh-ost periodic status line: {@code Copy: N/M pct%; Applied: ...; ETA: ...} */
        COPY_PROGRESS,
        /** migration phase marker (Ghost table created / Row copy complete / cut-over / done) */
        PHASE,
        /** gh-ost entered throttling (pause corroboration from the log side) */
        THROTTLED,
        /** gh-ost left throttling */
        UNTHROTTLED,
        /** "Creating magic cut-over table" — carries the timestamped old-table name (R7) */
        OLD_TABLE,
        /** FATAL line — failure with the message as summary */
        FATAL,
        /** known harmless teardown noise (spike-result §3.2) — never a failure */
        NOISE,
        /** anything else */
        IGNORED
    }

    public enum Phase {
        GHOST_TABLE_CREATED,
        ROW_COPY_COMPLETE,
        CUT_OVER,
        DONE
    }

    private final Type   type;
    private final Phase  phase;

    // COPY_PROGRESS payload
    private final long   curValue;
    private final long   maxValue;
    private final double percent;
    private final long   applied;
    private final String backlog;
    private final String lag;
    private final String state;
    private final String eta;

    // OLD_TABLE / FATAL payload
    private final String value;

    private GhostLogEvent(Type type, Phase phase, long curValue, long maxValue, double percent, long applied,
                          String backlog, String lag, String state, String eta, String value){
        this.type = type;
        this.phase = phase;
        this.curValue = curValue;
        this.maxValue = maxValue;
        this.percent = percent;
        this.applied = applied;
        this.backlog = backlog;
        this.lag = lag;
        this.state = state;
        this.eta = eta;
        this.value = value;
    }

    public static GhostLogEvent ignored() {
        return new GhostLogEvent(Type.IGNORED, null, 0, 0, 0, 0, null, null, null, null, null);
    }

    public static GhostLogEvent noise() {
        return new GhostLogEvent(Type.NOISE, null, 0, 0, 0, 0, null, null, null, null, null);
    }

    public static GhostLogEvent phase(Phase phase) {
        return new GhostLogEvent(Type.PHASE, phase, 0, 0, 0, 0, null, null, null, null, null);
    }

    public static GhostLogEvent throttled(boolean throttled) {
        return new GhostLogEvent(throttled ? Type.THROTTLED : Type.UNTHROTTLED, null, 0, 0, 0, 0, null, null, null, null, null);
    }

    public static GhostLogEvent oldTable(String oldTableName) {
        return new GhostLogEvent(Type.OLD_TABLE, null, 0, 0, 0, 0, null, null, null, null, oldTableName);
    }

    public static GhostLogEvent fatal(String message) {
        return new GhostLogEvent(Type.FATAL, null, 0, 0, 0, 0, null, null, null, null, message);
    }

    public static GhostLogEvent copyProgress(long cur, long max, double pct, long applied, String backlog, String lag, String state, String eta) {
        return new GhostLogEvent(Type.COPY_PROGRESS, null, cur, max, pct, applied, backlog, lag, state, eta, null);
    }
}
