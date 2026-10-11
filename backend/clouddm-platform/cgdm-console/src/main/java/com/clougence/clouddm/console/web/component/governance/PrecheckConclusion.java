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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Conclusion of a production schema precondition precheck for a single release stmt.
 * <p>
 * PASS — all preconditions met, proceed normally. May carry {@link Note}s: the check ran
 * to completion but individual scripts/clauses could not be fully verified (UNCERTAIN —
 * pass + event, ADR-011 S1~S5 regularization) or a previously-ruled design degradation
 * applies (shared-table baseline ambiguity, ENGINE/comment-level drift).
 * BLOCK — at least one precondition is confirmed violated; the caller must mark the stmt FAILED.
 * UNVERIFIABLE — the engine could NOT PROVE safety (missing input, infrastructure failure,
 * parse/split failure); under the fail-closed principle the caller must mark the stmt FAILED
 * (distinct event: "unverifiable" is not "violation" — remediation owners differ). Recovery
 * is fix-infrastructure + retry, or a governed override (dm_gov_schema_gate_override).
 * WARN_SKIP — legacy fail-open state. NO PRODUCER remains since ADR-011 (2026-10-11); kept
 * as dead-code defense for the gate branches and existing call sites.
 * <p>
 * Design principle (ADR-011, fail-closed): cannot prove safe ⇒ do not dispatch.
 * The former principle 宁漏勿误 (when in doubt, WARN_SKIP, never BLOCK) is
 * SUPERSEDED BY ADR-011 — it survives only as the platform-level break-glass switch
 * (govReleaseSchemaPrecheckBlock / govReleaseSchemaDiffBlock = false ⇒ UNVERIFIABLE
 * degrades to WARN_SKIP at the engine exit, single point; confirmed BLOCK is never degraded).
 */
public final class PrecheckConclusion {

    public enum Type { PASS, BLOCK, WARN_SKIP, UNVERIFIABLE }

    /**
     * A PASS-carried annotation: the check passed but something could not be fully verified
     * (S-face uncertain note) or a ruled design degradation applied (D5/D6). {@code code} is
     * the machine-readable discriminator the gate maps to an event type; {@code message} is
     * the human detail written into eventData.
     */
    public static final class Note {

        // Precheck S-face codes (ADR-011 S1~S5 → RELEASE_SCHEMA_PRECHECK_UNCERTAIN event)
        public static final String UNKNOWN_STMT_TYPE       = "UNKNOWN_STMT_TYPE";
        public static final String NAME_EXTRACTION_GAP     = "NAME_EXTRACTION_GAP";
        public static final String FOREIGN_KEY_EXCLUDED    = "FOREIGN_KEY_EXCLUDED";
        public static final String UNCLASSIFIED_SUBCLAUSE  = "UNCLASSIFIED_SUBCLAUSE";
        // Diff codes (ADR-011 D2 legacy / D5 / D6 → SNAPSHOT_MISSING / DRIFT_WARN events)
        public static final String SNAPSHOT_MISSING_LEGACY = "SNAPSHOT_MISSING_LEGACY";
        public static final String SHARED_TABLE_BASELINE   = "SHARED_TABLE_BASELINE";
        public static final String ENGINE_COMMENT_DRIFT    = "ENGINE_COMMENT_DRIFT";

        private final String code;
        private final String message;

        public Note(String code, String message) {
            this.code = code;
            this.message = message;
        }

        public String getCode() {
            return code;
        }

        public String getMessage() {
            return message;
        }

        @Override
        public String toString() {
            return code + ": " + message;
        }
    }

    private final Type        type;
    private final String      skipReason;
    private final List<SchemaViolation> violations;
    private final List<Note>  notes;

    private PrecheckConclusion(Type type, String skipReason, List<SchemaViolation> violations, List<Note> notes) {
        this.type = type;
        this.skipReason = skipReason;
        this.violations = violations == null ? Collections.emptyList() : violations;
        this.notes = notes == null ? Collections.emptyList() : notes;
    }

    public static PrecheckConclusion pass() {
        return new PrecheckConclusion(Type.PASS, null, Collections.emptyList(), Collections.emptyList());
    }

    /** PASS carrying uncertain/degradation notes — the gate records a WARN-class event and proceeds. */
    public static PrecheckConclusion passWithNotes(List<Note> notes) {
        return new PrecheckConclusion(Type.PASS, null, Collections.emptyList(), notes);
    }

    public static PrecheckConclusion block(List<SchemaViolation> violations) {
        return new PrecheckConclusion(Type.BLOCK, null, violations == null ? Collections.emptyList() : violations,
            Collections.emptyList());
    }

    /** Legacy fail-open state — no producer since ADR-011; see class javadoc. */
    public static PrecheckConclusion warnSkip(String reason) {
        return new PrecheckConclusion(Type.WARN_SKIP, reason, Collections.emptyList(), Collections.emptyList());
    }

    /** Fail-closed denial: the engine could not prove safety (ADR-011). */
    public static PrecheckConclusion unverifiable(String reason) {
        return new PrecheckConclusion(Type.UNVERIFIABLE, reason, Collections.emptyList(), Collections.emptyList());
    }

    public Type getType() {
        return type;
    }

    public boolean isBlock() {
        return type == Type.BLOCK;
    }

    public boolean isWarnSkip() {
        return type == Type.WARN_SKIP;
    }

    public boolean isUnverifiable() {
        return type == Type.UNVERIFIABLE;
    }

    public boolean isPass() {
        return type == Type.PASS;
    }

    /** Unified gate denial predicate: confirmed violation OR inability to prove safety. */
    public boolean isDeny() {
        return type == Type.BLOCK || type == Type.UNVERIFIABLE;
    }

    public String getSkipReason() {
        return skipReason;
    }

    public List<SchemaViolation> getViolations() {
        return violations;
    }

    /** PASS-carried notes (uncertain / ruled degradation). Empty for all other states. */
    public List<Note> getNotes() {
        return notes;
    }

    public boolean hasNote(String code) {
        for (Note n : notes) {
            if (code.equals(n.getCode())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Produce a multi-line, human-readable detail text suitable for exec_detail storage.
     * Each violation is one line. The entire text is capped at 4000 chars for safety.
     */
    public String toDetailText() {
        if (type == Type.UNVERIFIABLE) {
            return "Cannot verify production schema safety (" + skipReason + "), not dispatched";
        }
        if (type == Type.WARN_SKIP) {
            return "Schema precheck skipped: " + skipReason;
        }
        if (type == Type.PASS || violations.isEmpty()) {
            return "Schema precheck passed";
        }
        StringBuilder sb = new StringBuilder();
        for (SchemaViolation v : violations) {
            String line = v.toString();
            if (sb.length() + line.length() + 1 > 4000) {
                sb.append("...(truncated)");
                break;
            }
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(line);
        }
        return sb.toString();
    }

    /**
     * Produce a JSON-compatible event data string for dm_db_change_event.
     */
    public String toEventData(long stmtId) {
        return toEventData(stmtId, null);
    }

    /**
     * Same as {@link #toEventData(long)} with an optional gateType annotation
     * (PRECHECK / DIFF) so a shared event type (e.g. RELEASE_SCHEMA_GATE_UNVERIFIABLE)
     * stays attributable to its producing gate.
     */
    public String toEventData(long stmtId, String gateType) {
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("stmtId", stmtId);
        if (gateType != null) {
            data.put("gateType", gateType);
        }
        if (type == Type.UNVERIFIABLE) {
            data.put("result", "UNVERIFIABLE");
            data.put("reason", skipReason);
            // violations key kept (consumers render the whole eventData; key name preserved
            // per ADR-011 — UNVERIFIABLE semantics are expressed by the result value)
            data.put("violations", List.of(skipReason));
        } else if (type == Type.WARN_SKIP) {
            data.put("result", "WARN_SKIP");
            data.put("reason", skipReason);
        } else if (type == Type.BLOCK) {
            data.put("result", "BLOCK");
            List<String> vlist = new ArrayList<>();
            for (SchemaViolation v : violations) {
                vlist.add(v.toString());
            }
            data.put("violations", vlist);
        } else {
            data.put("result", "PASS");
            if (!notes.isEmpty()) {
                List<java.util.Map<String, String>> nlist = new ArrayList<>();
                for (Note n : notes) {
                    java.util.Map<String, String> nm = new java.util.LinkedHashMap<>();
                    nm.put("code", n.getCode());
                    nm.put("message", n.getMessage());
                    nlist.add(nm);
                }
                data.put("notes", nlist);
            }
        }
        return com.clougence.utils.JsonUtils.toJson(data);
    }
}
