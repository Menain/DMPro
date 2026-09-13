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
 * PASS — all preconditions met, proceed normally.
 * BLOCK — at least one precondition is violated; the caller must mark the stmt FAILED.
 * WARN_SKIP — could not determine (parsing failure, infrastructure error, or BLOCK mode
 * disabled); the caller must let execution proceed and record a warning event.
 * <p>
 * Design principle: 宁漏勿误 — when in doubt, WARN_SKIP, never BLOCK.
 */
public final class PrecheckConclusion {

    public enum Type { PASS, BLOCK, WARN_SKIP }

    private final Type                type;
    private final String              skipReason;
    private final List<SchemaViolation> violations;

    private PrecheckConclusion(Type type, String skipReason, List<SchemaViolation> violations) {
        this.type = type;
        this.skipReason = skipReason;
        this.violations = violations == null ? Collections.emptyList() : violations;
    }

    public static PrecheckConclusion pass() {
        return new PrecheckConclusion(Type.PASS, null, Collections.emptyList());
    }

    public static PrecheckConclusion block(List<SchemaViolation> violations) {
        return new PrecheckConclusion(Type.BLOCK, null, violations == null ? Collections.emptyList() : violations);
    }

    public static PrecheckConclusion warnSkip(String reason) {
        return new PrecheckConclusion(Type.WARN_SKIP, reason, Collections.emptyList());
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

    public boolean isPass() {
        return type == Type.PASS;
    }

    public String getSkipReason() {
        return skipReason;
    }

    public List<SchemaViolation> getViolations() {
        return violations;
    }

    /**
     * Produce a multi-line, human-readable detail text suitable for exec_detail storage.
     * Each violation is one line. The entire text is capped at 4000 chars for safety.
     */
    public String toDetailText() {
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
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("stmtId", stmtId);
        if (type == Type.WARN_SKIP) {
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
        }
        return com.clougence.utils.JsonUtils.toJson(data);
    }
}
