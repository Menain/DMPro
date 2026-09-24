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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure line-level parser of gh-ost 1.1.4 output (design 09-17-online-ddl-activation §2.6).
 * Fixtures for the unit tests are the real log lines recorded during the 2026-09-24 spike
 * (research/spike-result.md) and the format templates extracted from the bundled binary.
 *
 * <p>Deviations from the design regex (verified against the binary's status template
 * {@code Copy: %d/%d %.1f%%; Applied: %d; Backlog: %d/%d; Time: %+v(total), %+v(copy); streamer: %+v;
 * Lag: %.2fs, HeartbeatLag: %.2fs, State: %s; ETA: %s}):</p>
 * <ul>
 * <li>the Lag capture is anchored on {@code "; Lag: "} with a non-greedy prefix — a greedy
 *     {@code .*Lag:} would capture the trailing {@code HeartbeatLag} value instead;</li>
 * <li>State is captured as {@code [^;]+} rather than {@code \w+} because the state value is a
 *     free-form %s (e.g. "postponing cut-over stage").</li>
 * </ul>
 */
public final class GhostProgressParser {

    private static final Pattern COPY_STATUS  = Pattern.compile(
            "Copy: (\\d+)/(\\d+) ([\\d.]+)%; Applied: (\\d+); Backlog: (\\d+/\\d+);.*?; Lag: ([\\d.]+)s.*?State: ([^;]+); ETA: (\\S+)");

    /** {@code Creating magic cut-over table `db`.`_tbl_20260924171321_del`} (binary template: "Creating magic cut-over table %s.%s") */
    private static final Pattern MAGIC_TABLE  = Pattern.compile(
            "Creating magic cut-over table\\s+`?[\\w$]+`?\\s*\\.\\s*`?([\\w$]+)`?");

    private static final Pattern FATAL_LINE   = Pattern.compile("(?:^|\\s)FATAL\\s+(.+)$");

    // ---- phase markers (literal gh-ost messages, all confirmed present in the 1.1.4 binary) ----
    private static final String[] PHASE_GHOST_TABLE = { "Ghost table created" };
    private static final String[] PHASE_COPY_DONE   = { "Row copy complete" };
    private static final String[] PHASE_CUT_OVER    = { "Grabbing voluntary lock", "Tables renamed",
            "AllEventsUpToLockProcessed", "Lock & rename", "Looking for magic cut-over table" };
    private static final String[] PHASE_DONE        = { "Done migrating", "# Done" };

    /**
     * Throttling markers. "throttling before swapping tables" and "done throttling" are literal
     * strings in the bundled 1.1.4 binary; the flag-file spellings are defensive aliases. NOTE:
     * the spike did not exercise the throttle path (table too small) — S6 smoke must verify.
     */
    private static final String[] THROTTLED_MARKERS   = { "throttling before swapping tables", "throttle flag file",
            "ThrottleFlagFile" };
    private static final String[] UNTHROTTLED_MARKERS = { "done throttling" };

    // ---- teardown noise (spike-result §3.2): normal shutdown races, NEVER a failure ----
    private static final String[][] NOISE_MARKERS = { { "binlogsyncer.go:631", "connection was bad" },
            { "binlogstreamer.go:77", "close sync with err" } };

    private GhostProgressParser(){
    }

    /** pure function: one raw gh-ost output line → one event; never throws, never null */
    public static GhostLogEvent parseLine(String rawLine) {
        if (rawLine == null) {
            return GhostLogEvent.ignored();
        }
        String line = rawLine.trim();
        if (line.isEmpty()) {
            return GhostLogEvent.ignored();
        }

        for (String[] noise : NOISE_MARKERS) {
            if (containsAll(line, noise)) {
                return GhostLogEvent.noise();
            }
        }

        Matcher fatal = FATAL_LINE.matcher(line);
        if (fatal.find()) {
            return GhostLogEvent.fatal(fatal.group(1).trim());
        }

        Matcher copy = COPY_STATUS.matcher(line);
        if (copy.find()) {
            return GhostLogEvent.copyProgress(Long.parseLong(copy.group(1)), Long.parseLong(copy.group(2)),
                    Double.parseDouble(copy.group(3)), Long.parseLong(copy.group(4)), copy.group(5), copy.group(6),
                    copy.group(7).trim(), copy.group(8));
        }

        Matcher magic = MAGIC_TABLE.matcher(line);
        if (magic.find()) {
            return GhostLogEvent.oldTable(magic.group(1));
        }

        if (containsAny(line, PHASE_DONE)) {
            return GhostLogEvent.phase(GhostLogEvent.Phase.DONE);
        }
        if (containsAny(line, PHASE_COPY_DONE)) {
            return GhostLogEvent.phase(GhostLogEvent.Phase.ROW_COPY_COMPLETE);
        }
        if (containsAny(line, PHASE_CUT_OVER)) {
            return GhostLogEvent.phase(GhostLogEvent.Phase.CUT_OVER);
        }
        if (containsAny(line, PHASE_GHOST_TABLE)) {
            return GhostLogEvent.phase(GhostLogEvent.Phase.GHOST_TABLE_CREATED);
        }

        if (containsAny(line, UNTHROTTLED_MARKERS)) {
            return GhostLogEvent.throttled(false);
        }
        if (containsAny(line, THROTTLED_MARKERS)) {
            return GhostLogEvent.throttled(true);
        }

        return GhostLogEvent.ignored();
    }

    private static boolean containsAll(String line, String[] tokens) {
        for (String token : tokens) {
            if (!line.contains(token)) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsAny(String line, String[] tokens) {
        String lower = line.toLowerCase();
        for (String token : tokens) {
            if (lower.contains(token.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
}
