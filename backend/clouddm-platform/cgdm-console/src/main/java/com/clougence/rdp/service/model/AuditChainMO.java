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
package com.clougence.rdp.service.model;

import com.clougence.clouddm.platform.dal.model.monitor.AuditChannel;

import lombok.Getter;
import lombok.Setter;

/**
 * C8 M2 (task 10-13-audit-chain-enhancement, design D3): the audit-chain relation keys of ONE
 * op_audit row, carried by the additive {@code logAndAddOperationAudit} overloads into the
 * four searchable columns (sql_fingerprint / ticket_id / release_id / channel).
 * <p>
 * Hot-path discipline (red line 4): every key is CALLER-SUPPLIED (already in hand at the write
 * point or extracted from the audit obj) — the logOperation sink performs ZERO extra SELECTs
 * to resolve them. All fields are optional:
 * <ul>
 * <li>{@code sqlFingerprint} — GovSqlHashUtils.hash of the FULL frozen statement text (never a
 *     truncated summary; the governance stmt_hash join caliber). Only statements with SQL
 *     semantics carry one;</li>
 * <li>{@code ticketId} / {@code releaseId} — mutually exclusive chain bindings (ticket chain vs
 *     release chain);</li>
 * <li>{@code channel} — an explicit channel verdict; null defers to the sink's single-point
 *     URI inference ({@link AuditChannel#inferFromUri}), so existing semantic-URI write points
 *     need not pass it unless the inference would be wrong for them.</li>
 * </ul>
 */
@Getter
@Setter
public class AuditChainMO {

    private String       sqlFingerprint;
    private Long         ticketId;
    private Long         releaseId;
    private AuditChannel channel;

    public AuditChainMO() {
    }

    /** ticket-chain row: fingerprint (nullable) + ticketId + explicit channel (nullable → URI inference). */
    public static AuditChainMO ofTicket(Long ticketId, String sqlFingerprint, AuditChannel channel) {
        AuditChainMO mo = new AuditChainMO();
        mo.ticketId = ticketId;
        mo.sqlFingerprint = sqlFingerprint;
        mo.channel = channel;
        return mo;
    }

    /** release-chain row: fingerprint (nullable) + releaseId + explicit channel (nullable → URI inference). */
    public static AuditChainMO ofRelease(Long releaseId, String sqlFingerprint, AuditChannel channel) {
        AuditChainMO mo = new AuditChainMO();
        mo.releaseId = releaseId;
        mo.sqlFingerprint = sqlFingerprint;
        mo.channel = channel;
        return mo;
    }

    /** channel-only row (no SQL semantics, no chain binding — e.g. an override grant). */
    public static AuditChainMO ofChannel(AuditChannel channel) {
        AuditChainMO mo = new AuditChainMO();
        mo.channel = channel;
        return mo;
    }
}
