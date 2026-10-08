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
package com.clougence.clouddm.console.web.service.dmlbatch;

import org.springframework.stereotype.Service;

import com.clougence.clouddm.console.web.component.alert.AlertContext;
import com.clougence.clouddm.console.web.component.alert.AlertPushService;
import com.clougence.clouddm.console.web.component.alert.AlertScene;
import com.clougence.clouddm.console.web.model.vo.dmlbatch.DmlBatchRejectAuditObj;
import com.clougence.clouddm.console.web.model.vo.govticket.GovTicketV2CheckVO;
import com.clougence.clouddm.platform.dal.model.ResourceType;
import com.clougence.clouddm.platform.dal.model.monitor.AuditType;
import com.clougence.clouddm.platform.dal.model.monitor.SecurityLevel;
import com.clougence.rdp.service.RdpOpAuditService;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * B7 N2 (裁决 #16, design §8.2 — the 2026-10-05核查结论 landed): the no-key rejection trail.
 * Called at the SUBMIT BOUNDARIES (GovTicketV2Controller.submit + the open-API submitTicket —
 * 三入口对等 discipline, R18: the open channel must not become a trail bypass) AFTER the
 * {@link DmlNoKeyRejectException} pierced the service transaction proxy — the rollback is
 * COMPLETE at that point, so:
 * <ol>
 * <li>{@code pushAlert(DML_LARGE_TABLE_NO_KEY)} runs on the NO-TX immediate-send branch of
 *     AlertPushService (no synchronization active outside the transaction — the send is
 *     synchronous and its boolean accepted flag feeds the audit);</li>
 * <li>{@code logAndAddOperationAudit(DML_BATCH_REJECT)} — RdpOpAuditServiceImpl's bare insert
 *     joins NO active transaction here → autocommit → the row SURVIVES the rejection (the whole
 *     point of the boundary mechanism: inside the submit transaction it would roll back with the
 *     ticket);</li>
 * <li>the CALLER rethrows the original exception — this service NEVER throws and NEVER touches
 *     the rejection semantics (①② each independently try-caught; 绝不吞/换原拒绝异常).</li>
 * </ol>
 * 留痕与告警解耦: alertPushEnable off / no messenger / send failure → alertPushed=false, the
 * audit row is written anyway. Per-submission cadence: every rejected submit re-alerts and
 * re-audits (the B6 resubmit re-hit semantics; the audit uuidKey carries a timestamp — no
 * collision). /check previews never reach this service (no submit act — 防轰炸纪律).
 */
@Slf4j
@Service
public class DmlBatchRejectTrailService {

    @Resource
    private AlertPushService    alertPushService;
    @Resource
    private RdpOpAuditService   rdpOpAuditService;

    /**
     * The trail double-write of one rejected submission (never-throw by contract — both legs are
     * independently guarded; the caller rethrows the rejection afterwards).
     *
     * @param uid        the submitter (audit subject + alert copy applicant)
     * @param uri        the rejecting endpoint's request URI (audit operationUri)
     * @param remoteAddr the submitter's address (audit sourceIp; nullable on service-side callers)
     */
    public void trailNoKeyReject(String puid, String uid, String uri, String remoteAddr,
                                 DmlNoKeyRejectException rejection) {
        GovTicketV2CheckVO.DmlNoKeyRejectVO reject = rejection.getReject();
        if (reject == null) {
            return; // structurally impossible (the typed throw builds the payload) — defensive
        }

        // ① the IM alert (no-tx immediate send; accepted=false on switch-off/degradation)
        boolean alertPushed = false;
        try {
            AlertContext ctx = new AlertContext();
            // ticketId stays 0 — the ticket never landed and the NO_KEY copy renders no deep link
            ctx.setApplicantUid(uid);
            ctx.setDbName(reject.getDbName());
            ctx.setTableName(reject.getTableName());
            ctx.setTableRows(reject.getEstimatedRows());
            ctx.setRowThreshold(reject.getRowThreshold());
            ctx.setSqlSummary(reject.getSqlSummary());
            alertPushed = alertPushService.pushAlert(puid, AlertScene.DML_LARGE_TABLE_NO_KEY, ctx);
        } catch (Exception e) {
            log.error("[DmlBatchRejectTrail] alert push failed for the no-key rejection on dsId={} table={}.{} (the audit leg proceeds)",
                reject.getDsId(), reject.getDbName(), reject.getTableName(), e);
        }

        // ② the audit row (autocommit — survives the rolled-back submit transaction)
        try {
            DmlBatchRejectAuditObj obj = new DmlBatchRejectAuditObj();
            obj.setDsId(reject.getDsId());
            obj.setDbName(reject.getDbName());
            obj.setTableName(reject.getTableName());
            obj.setRows(reject.getEstimatedRows());
            obj.setRowThreshold(reject.getRowThreshold());
            obj.setReason(DmlBatchRejectAuditObj.REASON_NO_KEY);
            obj.setKeyDetail(reject.getKeyDetail());
            obj.setSqlSummary(reject.getSqlSummary());
            obj.setStmtIndex(reject.getStmtIndex());
            obj.setAlertPushed(alertPushed);
            rdpOpAuditService.logAndAddOperationAudit(puid, uid, uri, remoteAddr, reject.getDsId(), obj,
                SecurityLevel.HIGH, AuditType.DML_BATCH_REJECT, ResourceType.DATASOURCE);
        } catch (Exception e) {
            // the audit facility swallows internally already (logOperation try-catch); this guard
            // is the spec's belt-and-braces ①②隔离 — the rejection rethrow must never be preempted
            log.error("[DmlBatchRejectTrail] DML_BATCH_REJECT audit failed for dsId={} table={}.{}",
                reject.getDsId(), reject.getDbName(), reject.getTableName(), e);
        }
    }
}
