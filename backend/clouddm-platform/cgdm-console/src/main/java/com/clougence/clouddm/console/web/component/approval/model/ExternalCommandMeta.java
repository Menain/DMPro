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
package com.clougence.clouddm.console.web.component.approval.model;

/**
 * Metadata carried by an external orchestration command (approve/reject/cancel).
 * <p>
 * When {@code null} (the DingTalk/Internal callback path via refreshApprovalStatus),
 * all optional fields are absent and applyExternalApprovalResult behaves exactly
 * as the original refreshApprovalStatus switch block — zero behavior difference.
 * <p>
 * When non-null (the open command API path), carries:
 * <ul>
 *   <li>{@code operatorUid} — the service account uid resolved from AK/SK (NOT SYSTEM).</li>
 *   <li>{@code approver} — optional, human-readable approver name for activity synthesis.</li>
 *   <li>{@code comment} — optional, approval comment for activity context.</li>
 *   <li>{@code externalFlowId} — optional, external engine's flow identity for approIdentity.</li>
 *   <li>{@code externalRound} — optional (ADR-012 round binding, task 10-11-approval-round-binding),
 *       the approval round the command believes it acts on; compared against
 *       dm_approval.approval_round when present, skipped when absent (tolerant-first: the frozen
 *       §5.4 "ticketId-only minimal call" contract forbids rejecting a missing round by default).</li>
 * </ul>
 */
public class ExternalCommandMeta {

    private final String  operatorUid;
    private final String  approver;
    private final String  comment;
    private final String  externalFlowId;
    private final Integer externalRound;

    public ExternalCommandMeta(String operatorUid, String approver, String comment, String externalFlowId) {
        this(operatorUid, approver, comment, externalFlowId, null);
    }

    public ExternalCommandMeta(String operatorUid, String approver, String comment,
                               String externalFlowId, Integer externalRound) {
        this.operatorUid = operatorUid;
        this.approver = approver;
        this.comment = comment;
        this.externalFlowId = externalFlowId;
        this.externalRound = externalRound;
    }

    public String getOperatorUid() {
        return operatorUid;
    }

    public String getApprover() {
        return approver;
    }

    public String getComment() {
        return comment;
    }

    public String getExternalFlowId() {
        return externalFlowId;
    }

    public Integer getExternalRound() {
        return externalRound;
    }
}
