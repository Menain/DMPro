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
package com.clougence.clouddm.console.web.model.vo.govticket;

import lombok.Getter;
import lombok.Setter;

/**
 * C10 (task 10-14-recovery-type-grading, design D7): the audit obj of a ROLLBACK_MATERIAL row —
 * the obj-whitelist discipline (C8): NO SQL full text ever enters the audit obj; the material
 * itself lives in {@code dm_approval.roll_back_sql} and is joinable via the {@code fingerprint}
 * (GovSqlHashUtils over the FULL material text — the same normalize+SHA-256 caliber as the
 * governance stmt_hash / release hashes, so dm_mon_op_audit ↔ ticket material equality-joins).
 * <p>
 * The {@code source} vocabulary is exactly the three material-CHANGING writes: the resubmit
 * D7 branch 3 ("blank + original applicant-provided → material kept") writes NO row at all
 * (no change, no trail) — hence no KEPT value exists by design.
 */
@Getter
@Setter
public class RollbackMaterialAuditObj {

    /** system-generated backup template at submit / legacy create. */
    public static final String SOURCE_GENERATED   = "GENERATED";
    /** applicant-provided material at submit / resubmit / legacy create. */
    public static final String SOURCE_USER        = "USER";
    /** resubmit D7 branch 2: regenerated from the revised combined SQL. */
    public static final String SOURCE_REGENERATED = "REGENERATED";

    /** the obj summary truncation bound (the C8 sqlSummary ≤500 caliber). */
    public static final int    SUMMARY_MAX_LENGTH = 500;

    /** GENERATED | USER | REGENERATED. */
    private String source;
    /** the RecoveryType grade stamped onto the ticket by the same write (enum name). */
    private String recoveryType;
    /** GovSqlHashUtils.hash of the FULL material text (the chain-column join key). */
    private String fingerprint;
    /** the material's first ≤500 chars (display/troubleshooting — never the hash source). */
    private String summary;

    /** Build the obj with the summary truncation applied at the single construction point. */
    public static RollbackMaterialAuditObj of(String source, String recoveryType, String fingerprint, String material) {
        RollbackMaterialAuditObj obj = new RollbackMaterialAuditObj();
        obj.setSource(source);
        obj.setRecoveryType(recoveryType);
        obj.setFingerprint(fingerprint);
        if (material != null) {
            obj.setSummary(material.length() > SUMMARY_MAX_LENGTH
                ? material.substring(0, SUMMARY_MAX_LENGTH) : material);
        }
        return obj;
    }
}
