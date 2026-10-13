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

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.clougence.clouddm.api.console.autoexec.ErrorStrategy;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.global.i18n.DmI18nUtils;
import com.clougence.clouddm.console.web.model.fo.ghost.GhostExecuteFO;
import com.clougence.clouddm.console.web.model.vo.ghost.GhostExecuteVO;
import com.clougence.clouddm.console.web.model.vo.ghost.ReleaseGhostCapability;
import com.clougence.clouddm.console.web.service.ghost.GhostService;
import com.clougence.clouddm.console.web.service.ghost.asyntask.GhostAsyncTaskConfig;
import com.clougence.clouddm.console.web.util.DmTeamUtils;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.DbChangeEventDal;
import com.clougence.clouddm.platform.dal.access.ExecutionDal;
import com.clougence.clouddm.platform.dal.access.ProdReleaseDal;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO;
import com.clougence.clouddm.platform.dal.model.dbchange.GovEventType;
import com.clougence.clouddm.platform.dal.model.execution.AsyncTaskStatus;
import com.clougence.clouddm.platform.dal.model.execution.DmExecAsyncTaskDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseStmtDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseStmtSegmentDO;
import com.clougence.utils.JsonUtils;
import com.clougence.utils.StringUtils;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * ADR-013 M2 (release-chain segment-aware dispatch, design D3/D4/D5/D9): the SEGMENT ORCHESTRATOR
 * of a production release statement whose frozen manifest ({@code dm_prod_release_stmt_segment})
 * carries ≥1 routed segment. This is the release-chain twin of {@code TicketSegmentService} but a
 * SEPARATE writer — the two chains never share an orchestrator (single-writer discipline: the
 * ticket chain writes {@code dm_ticket_db_stmt.exec_segments}, the release chain writes the
 * manifest's runtime columns).
 * <p>
 * <b>Lock contract</b>: every {@code *UnderLock} entry assumes the CALLER holds the release row
 * lock ({@code releaseMapper.selectByIdForUpdate}) — the writer event sources (the AUTOEXEC job
 * completion callback {@code handleReleaseJobCompletion}, the GHOST AsyncTask terminal hook
 * {@code onReleaseGhostTerminal}, and the sweep repair) all take that single lock first, so segment
 * progression is serialized per release with no deadlock (the {@code handleReleaseJobCompletion}
 * convention extended to segments). The segment status CAS is re-read UNDER the lock; advancement
 * proceeds only while the target segment is still EXECUTING (exactly-once against replays/races).
 * <p>
 * <b>Aggregation split</b>: this service NEVER drives the release state machine — it writes segment
 * terminals, dispatches the next segment, or writes the STMT terminal when the last segment
 * finished; the caller's chain-advance + release-aggregation tail ({@code AutoExecServiceImpl}) owns
 * the next-stmt dispatch and DONE/PARTIAL_FAILED exactly once.
 * <p>
 * <b>Red lines</b>: a stmt with a manifest has NO DIRECT fallback (structural — the only branches
 * are per-segment AUTOEXEC/GHOST/DML_BATCH); a DML_BATCH segment is BLOCKED, never silently
 * downgraded (裁决 3); a GHOST segment replays the FROZEN routing decision after a CAPABILITY
 * re-check only (裁决 2 — never re-probes thresholds/re-routes); the null-plan legacy shape is
 * byte-identical (this service is never entered for an empty manifest).
 */
@Slf4j
@Service
public class ReleaseSegmentService {

    private static final String EXEC_STATUS_PENDING   = "PENDING";
    private static final String EXEC_STATUS_EXECUTING = "EXECUTING";
    private static final String EXEC_STATUS_SUCCESS   = "SUCCESS";
    private static final String EXEC_STATUS_FAILED    = "FAILED";
    private static final String SYSTEM_OPERATOR       = "SYSTEM";
    private static final String TICKET_TYPE_PROD_DML  = "PROD_DML";
    /** requestUri marker of the system-side release GHOST_EXECUTE audit row (no HTTP request exists — the ticket-side GHOST_AUTO_DISPATCH_URI twin). */
    private static final String GHOST_AUDIT_URI       = "/system/prodRelease/ghost/dispatch";
    /** dispatch-origin annotation of the release-chain GHOST_EXECUTE audit row (the TICKET_SEGMENT_CHAIN twin). */
    private static final String GHOST_AUDIT_VIA       = "RELEASE_SEGMENT_CHAIN";

    @Resource
    private ProdReleaseDal      prodReleaseDal;
    @Resource
    private ExecutionDal        execDal;
    @Resource
    private DbChangeEventDal    dbChangeEventDal;
    @Resource
    private ApprovalDal         approvalDal;
    @Resource
    private com.clougence.rdp.service.RdpOpAuditService rdpOpAuditService;
    // ADR-013 M3 (design D8): governed route-override consumption at the two block points
    // (DML_BATCH unsupported ∨ GHOST capability failure) — single-use CAS, release = DIRECT replay.
    // Plain injection: the override service only reaches the DAL + audit/event surfaces (no cycle).
    @Resource
    private GovRouteOverrideService govRouteOverrideService;
    // @Lazy: AutoExecServiceImpl reaches back into this service (createReleaseStmtJob delegation +
    //   handleReleaseJobCompletion segment completion) — safe-side proxy injection (iron rule).
    @org.springframework.context.annotation.Lazy
    @Resource
    private com.clougence.clouddm.console.web.component.execute.AutoExecService autoExecService;
    // @Lazy: the ghost tool surface lives in the service layer which lazy-reaches the governance
    //   components back — safe-side proxy injection (governance component iron rule).
    @org.springframework.context.annotation.Lazy
    @Resource
    private GhostService        ghostService;

    // ==================================================================================
    // first-segment dispatch (createReleaseStmtJob delegation, design D3)
    // ==================================================================================

    /**
     * Dispatch the FIRST non-terminal segment of a manifest-carrying release stmt (the release twin
     * of {@code TicketSegmentService.dispatchFirstSegment}). Caller contract: invoked from
     * {@code createReleaseStmtJob} ONLY when the manifest is non-empty (the null-plan legacy shape
     * never reaches here — byte-identical). Takes the release row lock, CAS-re-reads the stmt
     * (only PENDING/FAILED are dispatchable), re-reads the manifest fresh under the lock, marks the
     * first non-terminal segment EXECUTING and dispatches it by type.
     * <p>
     * Also the RETRY resume entry (design D9): {@code prepareRetry} reset the FAILED segment to
     * PENDING (SUCCESS segments never re-play), so {@code firstNonTerminal} lands on the resume
     * target and the chain continues from there.
     */
    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRED)
    public void dispatchFirstReleaseSegment(DmProdReleaseStmtDO stmt, String uid) {
        DmProdReleaseDO release = this.prodReleaseDal.releaseMapper().selectByIdForUpdate(stmt.getReleaseId());
        if (release == null) {
            return;
        }
        DmProdReleaseStmtDO fresh = this.prodReleaseDal.stmtMapper().queryById(stmt.getId());
        if (fresh == null
            || (!EXEC_STATUS_PENDING.equals(fresh.getExecStatus())
                && !EXEC_STATUS_FAILED.equals(fresh.getExecStatus()))) {
            log.warn("[ReleaseSegment] first-segment dispatch skipped for release stmt {} — status {} under the lock",
                stmt.getId(), fresh == null ? null : fresh.getExecStatus());
            return;
        }
        List<DmProdReleaseStmtSegmentDO> rows = this.prodReleaseDal.segmentMapper().queryByReleaseStmtId(fresh.getId());
        DmProdReleaseStmtSegmentDO target = firstNonTerminal(rows);
        if (target == null) {
            log.warn("[ReleaseSegment] first-segment dispatch skipped for release stmt {} — no non-terminal segment",
                fresh.getId());
            return;
        }
        markExecuting(target);
        dispatchSegmentUnderLock(release, fresh, target, uid);
    }

    /**
     * Type-specific dispatch of an already-EXECUTING-marked segment (caller persists after a launch
     * that recorded bindings). AUTOEXEC → a segment-filtered job (started here); GHOST → capability
     * re-check + gh-ost launch; DML_BATCH → BLOCKED (裁决 3, never downgraded). The AUTOEXEC exec-config
     * (transactional / errorStrategy / languageTag) is resolved HERE (self-contained — the completion
     * callbacks that re-enter mid-chain carry no config), mirroring {@code TicketSegmentService}'s
     * mid-chain {@code resolveTransactional} parity: transactional = the source ticket type (PROD_DML →
     * true), errorStrategy = NONE (the release chain is NONE by construction), languageTag = current locale.
     *
     * @return true when the stmt became TERMINAL as a result (a DML_BATCH block or a GHOST capability
     *         /launch failure wrote stmt FAILED) — the caller must run the chain-advance+aggregation
     *         tail; false when a body was dispatched (its completion callback owns the tail).
     */
    private boolean dispatchSegmentUnderLock(DmProdReleaseDO release, DmProdReleaseStmtDO stmt,
                                             DmProdReleaseStmtSegmentDO seg, String uid) {
        if (TicketSegment.TYPE_GHOST.equals(seg.getType())) {
            return dispatchReleaseGhostSegment(release, stmt, seg, uid);
        }
        if (TicketSegment.TYPE_DML_BATCH.equals(seg.getType())) {
            return blockDmlBatchSegment(release, stmt, seg, uid);
        }
        if (!TicketSegment.TYPE_AUTOEXEC.equals(seg.getType())) {
            // UNKNOWN segment type (a corrupt/tampered manifest row — the M1 writer only emits the
            // three plan types): FAIL-CLOSED, never execute (red line 1 — a manifest-carrying stmt
            // must have NO path back to a whole-text direct replay; the M3 plan-drift gate is the
            // tamper detector, this guard is the structural one).
            log.error("[ReleaseSegment] unknown manifest segment type '{}' for release stmt {} segment {} — blocked",
                seg.getType(), stmt.getId(), seg.getSegIdx());
            failSegment(release, stmt, seg,
                "unknown manifest segment type: " + seg.getType(), null, null);
            return true;
        }
        // AUTOEXEC: build the segment-filtered job (holes numbering) + start it (the caller's
        // createReleaseStmtJob returns false so the outer startJob is skipped — this owns it).
        dispatchDirectSegmentJob(stmt, seg, uid);
        return false;
    }

    /**
     * Build + start the segment-filtered DIRECT job of one segment (the AUTOEXEC branch's body,
     * shared with the M3 override-released replay of a blocked GHOST/DML_BATCH segment — design
     * D5/D8: a consumed route override releases the segment as a DIRECT single-statement job,
     * exec_order = stmtIndex, an EXPLICIT risk acceptance, never a silent downgrade). Records the
     * jobBizId binding on the segment row BEFORE the job insert (the completion callback locates
     * the segment by it — any plan type).
     */
    private void dispatchDirectSegmentJob(DmProdReleaseStmtDO stmt, DmProdReleaseStmtSegmentDO seg, String uid) {
        String jobBizId = DmTeamUtils.nextExecJobBizId();
        seg.setJobBizId(jobBizId);
        persistRuntime(seg);
        boolean transactional = resolveStmtTransactional(stmt.getSourceTicketId());
        String languageTag = DmI18nUtils.getLocale().toLanguageTag();
        this.autoExecService.createReleaseSegmentJob(stmt, seg, jobBizId, transactional,
            ErrorStrategy.NONE, languageTag, uid);
        this.autoExecService.startJob(jobBizId, uid);
    }

    /**
     * The release stmt's exec-config transactional flag (design §6 Q3 twin): a PROD_DML source ticket →
     * true (aligns with the direct-execution path); PRE_DDL / unknown / query failure → false (fail-safe).
     * Resolved per dispatch (the completion callbacks carry no config). Never throws.
     */
    private boolean resolveStmtTransactional(Long sourceTicketId) {
        if (sourceTicketId == null) {
            return false;
        }
        try {
            DmApprovalDO sourceTicket = this.approvalDal.approvalMapper().queryById(sourceTicketId);
            if (sourceTicket != null) {
                ApprovalMO mo = JsonUtils.toObj(sourceTicket.getTicketInfo(), ApprovalMO.class);
                return mo != null && TICKET_TYPE_PROD_DML.equals(mo.getTicketType());
            }
        } catch (Exception e) {
            log.warn("[ReleaseSegment] failed to resolve transactional for source ticket {}: {}",
                sourceTicketId, e.getMessage());
        }
        return false;
    }

    // ==================================================================================
    // GHOST segment dispatch (capability re-check + launch, design D4 / 裁决 2)
    // ==================================================================================

    /**
     * Dispatch ONE frozen GHOST segment against the prod target: capability re-check FIRST (only the
     * gh-ost hard preconditions — dialect/direct-connection/channel/table+PK-UK; NEVER the routing
     * decision, red line 4), then the launch binding (releaseStmtId/releaseId/segmentIndex). Any
     * capability failure OR launch failure (incl. GHOST_BINARY_MISSING) first offers the M3 route
     * override a consumption (design D8 — an explicit lock-risk acceptance releases the segment as
     * a DIRECT single-statement replay); with nothing consumable the block STANDS: stmt FAILED +
     * RELEASE_ROUTE_CAPABILITY event. The session owner = the release creator (stable across the
     * release lifecycle, mirrored by the sweep probe / session release).
     *
     * @return true when the stmt became terminal FAILED (capability/launch failure, no override);
     *         false on launch OR an override-released DIRECT dispatch.
     */
    private boolean dispatchReleaseGhostSegment(DmProdReleaseDO release, DmProdReleaseStmtDO stmt,
                                                DmProdReleaseStmtSegmentDO seg, String uid) {
        String puid = release.getPrimaryUid();
        String ownerUid = StringUtils.isNotBlank(release.getCreatorUid()) ? release.getCreatorUid() : uid;
        // capability re-check (not cached — every dispatch re-verifies; the plan is frozen, the env is not)
        ReleaseGhostCapability cap = this.ghostService.probeReleaseCapability(
            ownerUid, stmt.getProdDsId(), stmt.getProdDbName(), seg.getTableName());
        if (cap == null || !cap.isCapable()) {
            String code = cap == null ? "CHANNEL_UNAVAILABLE" : cap.getFailCode();
            String reason = cap == null ? "capability probe unavailable" : cap.getFailReason();
            String detail = "GHOST capability re-check failed [" + code + "]: " + reason;
            // M3 override channel (design D8): consumed → the segment replays as AUTOEXEC DIRECT
            // (explicit lock-risk acceptance — event + HIGH audit written by the override service)
            if (tryRouteOverrideRelease(release, stmt, seg,
                GovRouteOverrideService.BLOCK_ROUTE_CAPABILITY, detail, uid)) {
                return false;
            }
            failSegment(release, stmt, seg, detail,
                GovEventType.RELEASE_ROUTE_CAPABILITY, capabilityEventData(stmt, seg, code, reason));
            return true;
        }
        GhostExecuteFO fo = new GhostExecuteFO();
        fo.setDsId(stmt.getProdDsId());
        fo.setDbName(stmt.getProdDbName());
        fo.setDdlSql(seg.getSqlText()); // the FULL frozen statement text (never the stmt fulltext)
        try {
            GhostExecuteVO vo = this.ghostService.executeForRelease(
                puid, ownerUid, fo, stmt.getId(), release.getId(), seg.getSegIdx());
            seg.setSessionId(vo == null ? null : vo.getSessionId());
            seg.setTaskId(vo == null ? null : vo.getTaskId());
            persistRuntime(seg);
            // stmt → EXECUTING (first segment: PENDING→EXECUTING; mid-chain: idempotent rewrite)
            this.prodReleaseDal.stmtMapper().updateExecStatus(stmt.getId(), EXEC_STATUS_EXECUTING, null);
            auditGhostLaunch(puid, ownerUid, release, stmt, seg, vo);
            return false;
        } catch (RuntimeException e) {
            // launch failure (incl. GHOST_BINARY_MISSING tool unavailability) → capability disposal
            // (the binary lives on the sidecar and is only proven by the launch itself — design D4 ②)
            log.warn("[ReleaseSegment] GHOST launch failed for release stmt {} segment {} — blocked",
                stmt.getId(), seg.getSegIdx(), e);
            String detail = "GHOST launch failed: " + e.getMessage();
            if (tryRouteOverrideRelease(release, stmt, seg,
                GovRouteOverrideService.BLOCK_ROUTE_CAPABILITY, detail, uid)) {
                return false;
            }
            failSegment(release, stmt, seg, detail,
                GovEventType.RELEASE_ROUTE_CAPABILITY,
                capabilityEventData(stmt, seg, "LAUNCH_FAILED", e.getMessage()));
            return true;
        }
    }

    /**
     * M3 route-override consumption attempt at ONE of the two block points (design D8): findAndConsume
     * (single-use CAS + RELEASE_ROUTE_OVERRIDE event + HIGH grantor audit inside the override
     * service); on success the blocked segment is released as a DIRECT single-statement job
     * (dispatchDirectSegmentJob — exec_order = stmtIndex, the D5 "该段按 AUTOEXEC 直发" semantics).
     * NEVER a silent path: the consumption writes the event + audit trails before the dispatch.
     *
     * @return true when the override was consumed and the DIRECT job dispatched; false when the
     *         block stands (nothing consumable / CAS lost — the caller writes the block disposal).
     */
    private boolean tryRouteOverrideRelease(DmProdReleaseDO release, DmProdReleaseStmtDO stmt,
                                            DmProdReleaseStmtSegmentDO seg, String blockCode,
                                            String blockReason, String uid) {
        com.clougence.clouddm.platform.dal.model.prodrelease.DmGovRouteOverrideDO override
            = this.govRouteOverrideService.findAndConsume(release, stmt, seg, blockCode, blockReason);
        if (override == null) {
            return false;
        }
        log.info("[ReleaseSegment] route override {} consumed for release stmt {} segment {} ({}) — direct replay",
            override.getId(), stmt.getId(), seg.getSegIdx(), blockCode);
        dispatchDirectSegmentJob(stmt, seg, uid);
        return true;
    }

    /**
     * GHOST_EXECUTE audit in the release creator's name (the ticket-side R10 audit twin — a
     * system-launched PROD gh-ost migration is a HIGH-audited mutating operation; resId = the prod
     * dsId = the DATASOURCE ResourceType's own PK, four-point contract satisfied by the existing
     * GHOST_EXECUTE registration). The audit obj is annotated with the release binding
     * (releaseStmtId/releaseId/segmentIndex/via — mutually exclusive with the ticket annotation).
     * Audit failure is logged and NEVER rolls back the already-launched migration (the session and
     * task exist regardless — the ticket-side dispatchGhostGroup discipline).
     */
    private void auditGhostLaunch(String puid, String ownerUid, DmProdReleaseDO release,
                                  DmProdReleaseStmtDO stmt, DmProdReleaseStmtSegmentDO seg, GhostExecuteVO vo) {
        com.clougence.clouddm.console.web.model.vo.ghost.GhostOpAuditObj auditObj = vo == null ? null : vo.getAudit();
        if (auditObj != null) {
            auditObj.setReleaseStmtId(stmt.getId());
            auditObj.setReleaseId(release.getId());
            auditObj.setSegmentIndex(seg.getSegIdx());
            auditObj.setVia(GHOST_AUDIT_VIA);
        }
        try {
            this.rdpOpAuditService.logAndAddOperationAudit(puid, ownerUid,
                GHOST_AUDIT_URI, null, stmt.getProdDsId(), auditObj,
                com.clougence.clouddm.platform.dal.model.monitor.SecurityLevel.HIGH,
                com.clougence.clouddm.platform.dal.model.monitor.AuditType.GHOST_EXECUTE,
                com.clougence.clouddm.platform.dal.model.ResourceType.DATASOURCE);
        } catch (RuntimeException e) {
            log.error("[ReleaseSegment] GHOST_EXECUTE audit failed for release stmt {} segment {} (dispatch kept)",
                stmt.getId(), seg.getSegIdx(), e);
        }
    }

    // ==================================================================================
    // DML_BATCH segment block (裁决 3, design D5 — never downgraded, B8 seam zero-touch)
    // ==================================================================================

    /**
     * BLOCK a DML_BATCH segment at its dispatch turn: the release chain has NO batch executor
     * (O-C7-1 follow-up), so the stmt fails with RELEASE_ROUTE_UNSUPPORTED rather than silently
     * replaying the frozen text as ONE direct statement (red line 1). Recovery = a route override
     * (M3, design D8 — a consumed grant releases THIS segment as a DIRECT single-statement replay,
     * explicit risk acceptance with event + HIGH audit trails) ∨ returnStmt to the ticket chain ∨
     * the O-C7-1 executor. eventData is the disclosure WHITELIST (segIdx/table/rows/batchKey/
     * sourceTicketId — NO SQL text, C2 eventData discipline).
     *
     * @return true when the block stands (the stmt became terminal FAILED); false when a route
     *         override was consumed and the DIRECT job dispatched.
     */
    private boolean blockDmlBatchSegment(DmProdReleaseDO release, DmProdReleaseStmtDO stmt,
                                         DmProdReleaseStmtSegmentDO seg, String uid) {
        String detail = "DML_BATCH segment is not executable on the release chain (route unsupported)";
        if (tryRouteOverrideRelease(release, stmt, seg,
            GovRouteOverrideService.BLOCK_ROUTE_UNSUPPORTED, detail, uid)) {
            return false;
        }
        failSegment(release, stmt, seg, detail,
            GovEventType.RELEASE_ROUTE_UNSUPPORTED, unsupportedEventData(stmt, seg));
        return true;
    }

    // ==================================================================================
    // segment completion (the two callback callers — AUTOEXEC job + GHOST terminal hook)
    // ==================================================================================

    /**
     * DIRECT-job segment completion (caller: {@code handleReleaseJobCompletion}, UNDER its release
     * row lock, after the job terminal flip). Locates the segment by the recorded jobBizId — the
     * exact binding covers BOTH shapes: a plain AUTOEXEC segment AND an M3 override-released
     * GHOST/DML_BATCH segment (plan type frozen, job-driven at runtime). A stale/replayed callback
     * never advances a segment that moved on (the M2 jobBizId cross-check, promoted to the locator).
     * The lenient EXECUTING+AUTOEXEC shape fallback only applies when a biz id is blank
     * (unreachable by construction — both sides always record one; defensive parity with M2).
     *
     * @return true when the stmt reached a TERMINAL state (the caller runs the chain-advance tail);
     *         false when the next segment was dispatched or the CAS skipped (replay/race).
     */
    public boolean completeReleaseAutoexecSegmentUnderLock(DmProdReleaseDO release, DmProdReleaseStmtDO stmt,
                                                           String jobBizId, boolean success, String detail, String uid) {
        List<DmProdReleaseStmtSegmentDO> rows = this.prodReleaseDal.segmentMapper().queryByReleaseStmtId(stmt.getId());
        DmProdReleaseStmtSegmentDO seg = null;
        for (DmProdReleaseStmtSegmentDO one : rows) {
            if (one == null || !EXEC_STATUS_EXECUTING.equals(one.getStatus())) {
                continue;
            }
            if (StringUtils.isNotBlank(jobBizId) && StringUtils.isNotBlank(one.getJobBizId())) {
                if (jobBizId.equals(one.getJobBizId())) {
                    seg = one; // exact job binding (any plan type — incl. override-released)
                    break;
                }
            } else if (TicketSegment.TYPE_AUTOEXEC.equals(one.getType())) {
                seg = one; // lenient legacy shape (a blank biz id on either side)
                break;
            }
        }
        if (seg == null) {
            // replay/race: no EXECUTING segment bound to this job — the first writer won, or the
            // callback's job is stale (a superseded segment's biz id no longer matches any row)
            if (StringUtils.isNotBlank(jobBizId)) {
                log.warn("[ReleaseSegment] stale job callback for release stmt {} (job {} matches no EXECUTING segment) — skipped",
                    stmt.getId(), jobBizId);
            }
            return false;
        }
        return advanceUnderLock(release, stmt, rows, seg, success, detail, uid);
    }

    /**
     * GHOST segment terminal hook (caller: {@code GhostAsyncTask} write-back, the release-bound
     * branch — mutually exclusive with the ticket chain). Carries its OWN transaction (the AsyncTask
     * worker thread has none) and takes the release row lock first (the {@code onGhostTerminal}
     * convention). CAS on the configData segmentIndex (EXECUTING+GHOST shape), advances the segment,
     * and — when the stmt reached terminal — runs the chain-advance+aggregation tail through
     * {@code AutoExecService} (the release serial-chain successor dispatch + release aggregation).
     */
    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRED)
    public void onReleaseGhostTerminal(long releaseStmtId, Integer segmentIndex, boolean success, String detail) {
        DmProdReleaseStmtDO stmt = this.prodReleaseDal.stmtMapper().queryById(releaseStmtId);
        if (stmt == null) {
            return;
        }
        DmProdReleaseDO release = this.prodReleaseDal.releaseMapper().selectByIdForUpdate(stmt.getReleaseId());
        if (release == null) {
            return;
        }
        List<DmProdReleaseStmtSegmentDO> rows = this.prodReleaseDal.segmentMapper().queryByReleaseStmtId(releaseStmtId);
        DmProdReleaseStmtSegmentDO seg = findGhostSegment(rows, segmentIndex);
        if (seg == null || !EXEC_STATUS_EXECUTING.equals(seg.getStatus())
            || !TicketSegment.TYPE_GHOST.equals(seg.getType())
            // M3: an override-released GHOST segment is JOB-driven (jobBizId recorded, no ghost
            // task ever launched) — its completion arrives through the AUTOEXEC job callback, the
            // ghost hook must never advance it (defensive: no task exists to fire this hook)
            || StringUtils.isNotBlank(seg.getJobBizId())) {
            return; // CAS: replayed hook / sweep already judged / stale task of a rebuilt segment
        }
        String uid = StringUtils.isNotBlank(release.getCreatorUid()) ? release.getCreatorUid() : SYSTEM_OPERATOR;
        boolean stmtTerminal = advanceUnderLock(release, stmt, rows, seg, success, detail, uid);
        if (stmtTerminal) {
            DmProdReleaseStmtDO fresh = this.prodReleaseDal.stmtMapper().queryById(releaseStmtId);
            boolean stmtSuccess = fresh != null && EXEC_STATUS_SUCCESS.equals(fresh.getExecStatus());
            appendStmtTerminalEvent(release.getId(), releaseStmtId, stmtSuccess, detail);
            this.autoExecService.advanceReleaseChainUnderLock(release, fresh == null ? stmt : fresh, stmtSuccess, uid);
        }
    }

    /**
     * Stmt-terminal event parity with the AUTOEXEC callback path ({@code handleReleaseJobCompletion}
     * appends RELEASE_STMT_SUCCESS/FAILED per stmt terminal): the release-detail timeline shows ONE
     * stmt-terminal event per stmt regardless of which executor landed it. Same shape as the twin
     * ({@code appendReleaseStmtEvent}: toStatus=status, eventData{stmtId,status,detail?}, operator
     * SYSTEM — the executor-callback scope has no interactive user). Never throws.
     */
    private void appendStmtTerminalEvent(long releaseId, long stmtId, boolean success, String detail) {
        try {
            String status = success ? EXEC_STATUS_SUCCESS : EXEC_STATUS_FAILED;
            Map<String, Object> data = new java.util.HashMap<>();
            data.put("stmtId", stmtId);
            data.put("status", status);
            if (detail != null) {
                data.put("detail", detail);
            }
            DmDbChangeEventDO event = new DmDbChangeEventDO();
            event.setReleaseId(releaseId);
            event.setEventType(success ? GovEventType.RELEASE_STMT_SUCCESS.name()
                                       : GovEventType.RELEASE_STMT_FAILED.name());
            event.setToStatus(status);
            event.setOperatorUid(SYSTEM_OPERATOR);
            event.setEventData(JsonUtils.toJson(data));
            this.dbChangeEventDal.eventMapper().insert(event);
        } catch (Exception e) {
            log.warn("[ReleaseSegment] failed to append the stmt-terminal event for release stmt {}", stmtId, e);
        }
    }

    /**
     * The advance core (design D4/D9, the release twin of {@code TicketSegmentService.advanceUnderLock}):
     * segment terminal write → failure: stmt FAILED immediately (downstream segments stay PENDING);
     * success + next PENDING: mark EXECUTING + dispatch it (chained, in-transaction); success + no
     * next: stmt SUCCESS ("stmt terminal = all segments terminal"). NEVER drives the release state
     * machine — the caller's tail owns chain-advance+aggregation. Caller holds the release row lock.
     *
     * @return true when the STMT reached a terminal state (SUCCESS or FAILED); false when the next
     *         segment was dispatched (its completion callback owns the tail).
     */
    private boolean advanceUnderLock(DmProdReleaseDO release, DmProdReleaseStmtDO stmt,
                                     List<DmProdReleaseStmtSegmentDO> rows, DmProdReleaseStmtSegmentDO seg,
                                     boolean success, String detail, String uid) {
        seg.setStatus(success ? EXEC_STATUS_SUCCESS : EXEC_STATUS_FAILED);
        seg.setFinishedAt(System.currentTimeMillis());
        if (!success) {
            seg.setError(detail);
        }
        // freeze the terminal-evidence summary BEFORE the next AUTOEXEC segment's job creation
        // physically replaces this segment's job/task rows (uk_exec_auto_job_release_stmt single-row
        // ruling — the summary is the accepted forensic trail, mirrors the ticket-side uk ruling (a))
        seg.setSummary(buildSummary(seg));
        persistRuntime(seg);

        if (!success) {
            // segment FAILED → stmt FAILED (downstream segments stay PENDING; chain stops)
            this.prodReleaseDal.stmtMapper().updateExecStatus(stmt.getId(), EXEC_STATUS_FAILED, detail);
            return true;
        }

        DmProdReleaseStmtSegmentDO next = nextPending(rows, seg.getSegIdx());
        if (next == null) {
            // last segment landed → stmt SUCCESS
            this.prodReleaseDal.stmtMapper().updateExecStatus(stmt.getId(), EXEC_STATUS_SUCCESS, null);
            return true;
        }
        markExecuting(next);
        // a mid-chain DML_BATCH block / GHOST capability failure makes the stmt terminal FAILED (true);
        // a dispatched AUTOEXEC/GHOST body returns false (its completion callback owns the tail)
        return dispatchSegmentUnderLock(release, stmt, next, uid);
    }

    // ==================================================================================
    // retry reset (design D9 — SUCCESS segments never re-play) + sweep liveness probe
    // ==================================================================================

    /**
     * Reset a FAILED release stmt's segments for a retry resume (caller: {@code retryReleaseStmt},
     * before {@code createReleaseStmtJob}): every non-SUCCESS segment (the FAILED target, plus any
     * downstream PENDING) clears its error/timestamps/stale bindings back to PENDING; SUCCESS
     * segments are NEVER touched (已成功段永不重放, the option-C spirit). {@code dispatchFirstReleaseSegment}
     * then lands on {@code firstNonTerminal} = the reset target and continues the chain. A GHOST
     * target's recorded session is released first (the sidecar cap-2 slot — the rebuild launches a
     * fresh task, never reuses the old one; mirrors {@code resumeGroupUnderLock}).
     */
    public void prepareRetry(DmProdReleaseStmtDO stmt) {
        List<DmProdReleaseStmtSegmentDO> rows = this.prodReleaseDal.segmentMapper().queryByReleaseStmtId(stmt.getId());
        if (rows == null || rows.isEmpty()) {
            return; // null-plan legacy — nothing to reset
        }
        for (DmProdReleaseStmtSegmentDO seg : rows) {
            if (seg == null || EXEC_STATUS_SUCCESS.equals(seg.getStatus())) {
                continue; // SUCCESS segments never re-play
            }
            // release a GHOST target's old session before the rebuild (cap-2 slot economy)
            if (TicketSegment.TYPE_GHOST.equals(seg.getType()) && StringUtils.isNotBlank(seg.getSessionId())) {
                releaseSessionQuietly(stmt, seg);
            }
            seg.setStatus(EXEC_STATUS_PENDING);
            seg.setError(null);
            seg.setStartedAt(null);
            seg.setFinishedAt(null);
            seg.setJobBizId(null);
            seg.setSessionId(null);
            seg.setTaskId(null);
            seg.setSummary(null);
            persistRuntime(seg);
        }
    }

    /**
     * Sweep liveness probe of ONE EXECUTING release stmt's segments (caller:
     * {@code recoverReleaseCompletionIfDue}, UNDER its release row lock; a SIMPLIFIED twin of
     * {@code TicketSegmentService.repairGroupUnderLock} — segment-level terminal writeback + advance
     * only, NO release-level chain re-drive here). Runs in a FRESH transaction ({@code REQUIRES_NEW})
     * and re-reads under the caller's held lock (the RR staleness guard, check F1); it must NEVER
     * re-take the release row lock (the suspended caller holds it — self-deadlock).
     * <p>
     * Only the GHOST segment needs a probe: an AUTOEXEC segment is driven by its job-completion
     * callback (the release aggregation backstops it) and a DML_BATCH segment is never EXECUTING
     * (blocked at dispatch). R3 ruling preserved: an unknown ghost outcome (dead task / lost session)
     * is judged FAILED — a retryable failure beats a false SUCCESS.
     */
    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRES_NEW)
    public void probeReleaseSegmentUnderLock(DmProdReleaseDO release, DmProdReleaseStmtDO staleStmt) {
        DmProdReleaseStmtDO stmt = this.prodReleaseDal.stmtMapper().queryById(staleStmt.getId());
        if (stmt == null || !EXEC_STATUS_EXECUTING.equals(stmt.getExecStatus())) {
            return; // self-guard: only an EXECUTING stmt is repairable (fresh re-read)
        }
        List<DmProdReleaseStmtSegmentDO> rows = this.prodReleaseDal.segmentMapper().queryByReleaseStmtId(stmt.getId());
        DmProdReleaseStmtSegmentDO current = firstNonTerminal(rows);
        if (current == null) {
            // every segment terminal but the stmt write was lost — recover the stmt terminal
            boolean anyFailed = false;
            String error = null;
            for (DmProdReleaseStmtSegmentDO r : rows) {
                if (r != null && EXEC_STATUS_FAILED.equals(r.getStatus())) {
                    anyFailed = true;
                    if (StringUtils.isNotBlank(r.getError())) {
                        error = r.getError();
                    }
                }
            }
            this.prodReleaseDal.stmtMapper().updateExecStatus(stmt.getId(),
                anyFailed ? EXEC_STATUS_FAILED : EXEC_STATUS_SUCCESS, anyFailed ? error : null);
            return;
        }
        if (EXEC_STATUS_PENDING.equals(current.getStatus())) {
            // a crashed mid-advance left this segment undispatched (the predecessor committed SUCCESS
            // but the dispatch rolled back) — re-mark + dispatch it (mirrors repairGroupUnderLock's
            // PENDING row; safe under the fresh REQUIRES_NEW read: a committed PENDING means no body ran)
            log.warn("[ReleaseSegment] sweep re-dispatching the undispatched segment {} of release stmt {}",
                current.getSegIdx(), stmt.getId());
            markExecuting(current);
            dispatchSegmentUnderLock(release, stmt, current,
                StringUtils.isNotBlank(release.getCreatorUid()) ? release.getCreatorUid() : SYSTEM_OPERATOR);
            return;
        }
        if (!EXEC_STATUS_EXECUTING.equals(current.getStatus())
            || !TicketSegment.TYPE_GHOST.equals(current.getType())
            // M3: an override-released GHOST segment carries a jobBizId (DIRECT job-driven — the
            // AUTOEXEC callback + the release aggregation backstop it, no ghost task exists to probe)
            || StringUtils.isNotBlank(current.getJobBizId())) {
            return; // EXECUTING AUTOEXEC is callback-driven; DML_BATCH is never EXECUTING (blocked at dispatch)
        }
        probeGhostSegmentUnderLock(release, stmt, rows, current);
    }

    /** GHOST segment liveness probe (mirrors {@code GhostGroupCompletionService.probeGhostGroupUnderLock}). */
    private void probeGhostSegmentUnderLock(DmProdReleaseDO release, DmProdReleaseStmtDO stmt,
                                            List<DmProdReleaseStmtSegmentDO> rows, DmProdReleaseStmtSegmentDO seg) {
        DmExecAsyncTaskDO task = this.ghostService.findTaskForReleaseStmt(stmt.getId(), seg.getSegIdx());
        String uid = StringUtils.isNotBlank(release.getCreatorUid()) ? release.getCreatorUid() : SYSTEM_OPERATOR;
        if (task == null) {
            failSegment(release, stmt, seg,
                "ghost task record missing while the segment is EXECUTING", null, null);
            return;
        }
        AsyncTaskStatus status = task.getStatus();
        String ownerUid = resolveGhostOwnerUid(task, uid);
        if (isTerminalTaskStatus(status)) {
            if (status == AsyncTaskStatus.COMPLETE) {
                // the terminal hook's writeback was lost — replay the completion exactly like it would
                log.warn("[ReleaseSegment] sweep recovering the lost completion of GHOST segment {} release stmt {} (task COMPLETE)",
                    seg.getSegIdx(), stmt.getId());
                advanceUnderLock(release, stmt, rows, seg, true, null, uid);
                return;
            }
            // FAILURE / CANCEL: release the orphan session (cap-2 slot) + advance as FAILED
            releaseSessionByOwner(ownerUid, task.getBizId());
            advanceUnderLock(release, stmt, rows, seg, false,
                "ghost task " + status + " (completion writeback lost)", uid);
            return;
        }
        // active task → session liveness decides (host-affinity orphan / sidecar restart)
        if (this.ghostService.hasSession(ownerUid, task.getBizId())) {
            return; // healthy: the migration is still in flight
        }
        // session already gone (hasSession false) — nothing left to release; R3: unknown → FAILED
        failSegment(release, stmt, seg,
            "ghost session lost while the task is " + status + " (result unknown)", null, null);
    }

    // ==================================================================================
    // segment terminal + event helpers
    // ==================================================================================

    /** Write a segment FAILED + its stmt FAILED (+ optional event). Used by the block/capability/probe paths. */
    private void failSegment(DmProdReleaseDO release, DmProdReleaseStmtDO stmt, DmProdReleaseStmtSegmentDO seg,
                             String detail, GovEventType eventType, String eventData) {
        seg.setStatus(EXEC_STATUS_FAILED);
        seg.setError(detail);
        seg.setFinishedAt(System.currentTimeMillis());
        seg.setSummary(buildSummary(seg));
        persistRuntime(seg);
        this.prodReleaseDal.stmtMapper().updateExecStatus(stmt.getId(), EXEC_STATUS_FAILED, detail);
        if (eventType != null) {
            appendReleaseEvent(release.getId(), eventType, eventData);
        }
    }

    /** Minimal terminal-evidence summary {status, startedAt?, finishedAt?, error?} (forensic trail). */
    private static String buildSummary(DmProdReleaseStmtSegmentDO seg) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("status", seg.getStatus());
        if (seg.getStartedAt() != null) {
            m.put("startedAt", seg.getStartedAt());
        }
        if (seg.getFinishedAt() != null) {
            m.put("finishedAt", seg.getFinishedAt());
        }
        if (StringUtils.isNotBlank(seg.getError())) {
            m.put("error", seg.getError());
        }
        return JsonUtils.toJson(m);
    }

    private void markExecuting(DmProdReleaseStmtSegmentDO seg) {
        seg.setStatus(EXEC_STATUS_EXECUTING);
        seg.setStartedAt(System.currentTimeMillis());
        persistRuntime(seg);
    }

    /** Runtime-column write (single-writer discipline — only inside the release-row-lock window). */
    private void persistRuntime(DmProdReleaseStmtSegmentDO seg) {
        this.prodReleaseDal.segmentMapper().updateRuntime(seg);
    }

    /** Best-effort release of a GHOST segment's recorded session (cap-2 slot economy; never-throw). */
    private void releaseSessionQuietly(DmProdReleaseStmtDO stmt, DmProdReleaseStmtSegmentDO seg) {
        if (seg == null || StringUtils.isBlank(seg.getSessionId())) {
            return;
        }
        try {
            // owner uid = the launch uid; resolve from the task record when present, else the release creator
            DmExecAsyncTaskDO task = seg.getTaskId() == null ? null : this.execDal.asyncTaskMapper().queryById(seg.getTaskId());
            String ownerUid = task == null ? null : resolveGhostOwnerUid(task, null);
            this.ghostService.releaseSessionQuietly(ownerUid, seg.getSessionId());
        } catch (RuntimeException e) {
            log.warn("[ReleaseSegment] ghost session release failed for release stmt {} segment {} (continues)",
                stmt.getId(), seg.getSegIdx(), e);
        }
    }

    private void releaseSessionByOwner(String ownerUid, String sessionId) {
        if (StringUtils.isBlank(sessionId)) {
            return;
        }
        try {
            this.ghostService.releaseSessionQuietly(ownerUid, sessionId);
        } catch (RuntimeException e) {
            log.warn("[ReleaseSegment] orphan ghost session release failed for session {} (continues)", sessionId, e);
        }
    }

    private String resolveGhostOwnerUid(DmExecAsyncTaskDO task, String fallback) {
        GhostAsyncTaskConfig cfg = parseGhostConfig(task);
        if (cfg != null && StringUtils.isNotBlank(cfg.getUserId())) {
            return cfg.getUserId();
        }
        if (task != null && StringUtils.isNotBlank(task.getUid())) {
            return task.getUid();
        }
        return fallback;
    }

    private static GhostAsyncTaskConfig parseGhostConfig(DmExecAsyncTaskDO task) {
        if (task == null || StringUtils.isBlank(task.getConfigData())) {
            return null;
        }
        try {
            return JsonUtils.toObj(task.getConfigData(), GhostAsyncTaskConfig.class);
        } catch (Exception e) {
            log.warn("[ReleaseSegment] ghost task configData unparsable, taskId={}", task.getId(), e);
            return null;
        }
    }

    private void appendReleaseEvent(long releaseId, GovEventType type, String eventData) {
        try {
            DmDbChangeEventDO event = new DmDbChangeEventDO();
            event.setReleaseId(releaseId);
            event.setEventType(type.name());
            event.setOperatorUid(SYSTEM_OPERATOR);
            event.setEventData(eventData);
            this.dbChangeEventDal.eventMapper().insert(event);
        } catch (Exception e) {
            log.warn("[ReleaseSegment] failed to append {} event for release {}", type, releaseId, e);
        }
    }

    /** RELEASE_ROUTE_UNSUPPORTED eventData — the disclosure WHITELIST (NO SQL text, C2 discipline). */
    private static String unsupportedEventData(DmProdReleaseStmtDO stmt, DmProdReleaseStmtSegmentDO seg) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("stmtId", stmt.getId());
        data.put("segIdx", seg.getSegIdx());
        data.put("table", seg.getTableName());
        data.put("rowsEstimated", seg.getRowsEstimated());
        data.put("batchKeyColumn", seg.getBatchKeyColumn());
        data.put("batchKeyType", seg.getBatchKeyType());
        data.put("sourceTicketId", stmt.getSourceTicketId());
        return JsonUtils.toJson(data);
    }

    /** RELEASE_ROUTE_CAPABILITY eventData — the failed item disclosure. */
    private static String capabilityEventData(DmProdReleaseStmtDO stmt, DmProdReleaseStmtSegmentDO seg,
                                              String failCode, String failReason) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("stmtId", stmt.getId());
        data.put("segIdx", seg.getSegIdx());
        data.put("table", seg.getTableName());
        data.put("failCode", failCode);
        data.put("failReason", failReason);
        return JsonUtils.toJson(data);
    }

    // ==================================================================================
    // manifest-row segment helpers (the DO-row twin of TicketSegment's static carriers)
    // ==================================================================================

    static DmProdReleaseStmtSegmentDO firstNonTerminal(List<DmProdReleaseStmtSegmentDO> rows) {
        if (rows == null) {
            return null;
        }
        for (DmProdReleaseStmtSegmentDO r : rows) {
            if (r != null && !isTerminal(r.getStatus())) {
                return r;
            }
        }
        return null;
    }

    static DmProdReleaseStmtSegmentDO nextPending(List<DmProdReleaseStmtSegmentDO> rows, int afterSegIdx) {
        if (rows == null) {
            return null;
        }
        for (DmProdReleaseStmtSegmentDO r : rows) {
            if (r != null && r.getSegIdx() > afterSegIdx && EXEC_STATUS_PENDING.equals(r.getStatus())) {
                return r;
            }
        }
        return null;
    }

    static DmProdReleaseStmtSegmentDO findGhostSegment(List<DmProdReleaseStmtSegmentDO> rows, Integer segIdx) {
        if (rows == null) {
            return null;
        }
        if (segIdx != null) {
            for (DmProdReleaseStmtSegmentDO r : rows) {
                if (r != null && r.getSegIdx() == segIdx.intValue()) {
                    return r; // exact binding — no shape fallback
                }
            }
            return null;
        }
        for (DmProdReleaseStmtSegmentDO r : rows) {
            if (r != null && EXEC_STATUS_EXECUTING.equals(r.getStatus())
                && TicketSegment.TYPE_GHOST.equals(r.getType())) {
                return r;
            }
        }
        return null;
    }

    private static boolean isTerminal(String status) {
        return EXEC_STATUS_SUCCESS.equals(status) || EXEC_STATUS_FAILED.equals(status);
    }

    static boolean isTerminalTaskStatus(AsyncTaskStatus status) {
        return status == AsyncTaskStatus.COMPLETE || status == AsyncTaskStatus.FAILURE || status == AsyncTaskStatus.CANCEL;
    }
}
