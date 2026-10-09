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
package com.clougence.clouddm.console.web.component.bridge;

import java.util.Date;
import java.util.List;
import java.util.function.Function;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.clougence.clouddm.api.common.crypt.CryptService;
import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.console.web.component.alert.AlertContext;
import com.clougence.clouddm.console.web.component.alert.AlertPushService;
import com.clougence.clouddm.console.web.component.alert.AlertScene;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.component.config.RootUserConfig;
import com.clougence.clouddm.console.web.global.i18n.DmI18nUtils;
import com.clougence.clouddm.console.web.global.i18n.I18nDmMsgKeys;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.AuthDal;
import com.clougence.clouddm.platform.dal.access.SystemDal;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalType;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.auth.DmAuthUserDO;
import com.clougence.utils.ExceptionUtils;
import com.clougence.utils.JsonUtils;
import com.clougence.utils.StringUtils;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * DingTalk approval bridge outbound plane (task 10-09-dingtalk-approval-bridge, design §2/§3/§4).
 * Service shape mirrors {@code AlertPushService}: RootUserConfig kill-switch fail-safe read →
 * afterCommit registration (the {@code GovReturnNotifyService} template) → never-throw client
 * call → REQUIRES_NEW write-back of the dm_approval outbound columns → single-shot failure
 * alerting. The outbound call NEVER runs inside the business transaction and NEVER propagates
 * a failure to the main chain (submit / release-create / cancel / close stay unaffected).
 * <p>
 * Applicability (design §3.1): only {@code appro_type=External} rows with the kill switch ON
 * and a configured baseUrl ever enter STARTING; every other row keeps outbound_status NULL.
 * Idempotency: the local row-level CAS claims (STARTING transitions) guard same-process races;
 * the bridge-side (bizType,bizId) dedup key guards cross-process double-sends. The flowId
 * backfill honors the appro_identity "write only when currently empty" contract — outbound
 * response and inbound command externalFlowId are dual sources, first writer wins (boundary #4/#6).
 * <p>
 * Echo isolation (design §1): outbound cancels hook ONLY the human console paths
 * (ApprovalFlowServiceImpl.cancelTicket/closeTicket, ProdReleaseServiceImpl.return/cancel).
 * The inbound {@code /tickets/cancel} open command travels GovOpenApiService →
 * applyExternalApprovalResult and never passes through those hooks — a bridge command can
 * never trigger an outbound notification back to the bridge.
 */
@Slf4j
@Service
public class DingBridgeOutboundService {

    // ---- fail-safe KV defaults (mirror the @UserConfigDef defaultValues — drift-pinned by test) ----
    /** the kill switch fails CLOSED: unreadable switch = no outbound at all (pure pull-mode). */
    public static final boolean DEFAULT_OUTBOUND_ENABLE = false;
    public static final String  DEFAULT_BASE_URL        = "";
    public static final int     DEFAULT_TIMEOUT_MS      = 10_000;
    /** whitelist Range mirrors (WhiteListServiceForBasic/Full) — defensive clamping at resolve time. */
    public static final int     MIN_TIMEOUT_MS          = 1_000;
    public static final int     MAX_TIMEOUT_MS          = 60_000;

    // ---- compensation policy (design §4 — drift-pinned by test) ----
    public static final int     MAX_AUTO_RETRY          = 3;
    /** backoff slots indexed by the upcoming retry number: retry#1 after 1m, #2 after 5m, #3 after 15m. */
    public static final long[]  BACKOFF_MS              = { 60_000L, 300_000L, 900_000L };
    public static final int     SWEEP_BATCH_LIMIT       = 20;

    // ---- payload/write bounds ----
    public static final int     SUMMARY_MAX_CHARS       = 200;
    public static final int     ERROR_MAX_CHARS         = 500;

    // ---- outbound_status vocabulary (design §3.1; NULL reads as NOT_STARTED) ----
    public static final String  STATUS_NOT_STARTED      = "NOT_STARTED";
    public static final String  STATUS_STARTING         = "STARTING";
    public static final String  STATUS_STARTED          = "STARTED";
    public static final String  STATUS_FAILED           = "FAILED";

    // ---- flows payload bizType ----
    public static final String  BIZ_TYPE_TICKET         = "TICKET";
    public static final String  BIZ_TYPE_RELEASE        = "RELEASE";

    // ---- flows/cancel reasons (design §2.2) ----
    public static final String  REASON_LOCAL_CANCELED    = "LOCAL_CANCELED";
    public static final String  REASON_LOCAL_CLOSED      = "LOCAL_CLOSED";
    public static final String  REASON_RELEASE_RETURNED  = "RELEASE_RETURNED";

    @Resource
    private SystemDal                systemDal;
    @Resource
    private ApprovalDal              approvalDal;
    @Resource
    private AuthDal                  authDal;
    @Resource
    private DingBridgeClient         dingBridgeClient;
    @Resource
    private PlatformTransactionManager txManager;
    // @Lazy: AlertPushService injects ImSenderService (cicd layer); defensive proxy per the
    //   governance-component iron rule (any new transitive dependency could cycle).
    @org.springframework.context.annotation.Lazy
    @Resource
    private AlertPushService         alertPushService;

    /** which CAS claim a create attempt runs under (drives the retry-count and alert policy). */
    private enum Claim { FIRST, RETRY, REISSUE }

    // ==================== trigger (in-business-tx, afterCommit registration) ====================

    /**
     * Register the outbound flows/create for an External-channel approval row — called INSIDE
     * the submit / release-create business transaction (hook points: GovTicketV2ServiceImpl.submit
     * for PROD_DML DIRECT+External, ProdReleaseServiceImpl.createRelease/resubmit for release
     * approval tickets). The actual bridge call fires on afterCommit (rollback → zero calls);
     * without an active transaction it fires immediately (defensive branch, GovReturnNotifyService
     * template). NEVER throws — every failure degrades to a warn log.
     *
     * @return true when a send was registered (switch on + baseUrl set + External row); false
     *         when silently skipped (kill switch off / unconfigured / non-External / read failure).
     */
    public boolean triggerCreateFlow(String puid, long approvalId) {
        try {
            if (!isOutboundEnabled()) {
                log.info("[DingBridge] outbound kill switch off — flows/create skipped for approval {}", approvalId);
                return false;
            }
            if (StringUtils.isBlank(resolveEndpoint().getBaseUrl())) {
                log.info("[DingBridge] dingBridgeBaseUrl blank — flows/create skipped for approval {}", approvalId);
                return false;
            }
            DmApprovalDO row = approvalDal.approvalMapper().queryById(approvalId);
            if (row == null || row.getApproType() != ApprovalType.External) {
                // channel guard: only External-channel rows ever initiate a bridge flow
                return false;
            }
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        doCreateAttempt(approvalId, Claim.FIRST, 0, false);
                    }
                });
            } else {
                doCreateAttempt(approvalId, Claim.FIRST, 0, false);
            }
            return true;
        } catch (Throwable t) {
            // the outbound facility must never break the caller (submit / create flow)
            log.warn("[DingBridge] triggerCreateFlow failed for approval {}, degraded", approvalId, t);
            return false;
        }
    }

    // ==================== cancel notification (裁决 #3, design §2.2) ====================

    /**
     * Register the best-effort outbound flows/cancel for a locally terminated External approval
     * row — called INSIDE the human cancel/close/return business transaction (hook points:
     * ApprovalFlowServiceImpl.cancelTicket/closeTicket, ProdReleaseServiceImpl.returnRelease/
     * cancelRelease). Skips with ZERO calls when the row was never successfully initiated
     * (appro_identity blank — no instance exists to revoke). Failure never blocks the local
     * transition: alert + warn log only (zombie instances are neutralized by the inbound
     * command state guards, design boundary #8). NEVER throws.
     *
     * @param reason one of the {@code REASON_*} constants (wire value of the cancel payload)
     * @return true when a notification was registered; false when skipped.
     */
    public boolean notifyCancelFlow(String puid, Long approvalId, String reason) {
        try {
            if (approvalId == null) {
                return false;
            }
            if (!isOutboundEnabled()) {
                log.info("[DingBridge] outbound kill switch off — flows/cancel ({}) skipped for approval {}", reason, approvalId);
                return false;
            }
            if (StringUtils.isBlank(resolveEndpoint().getBaseUrl())) {
                return false;
            }
            DmApprovalDO row = approvalDal.approvalMapper().queryById(approvalId);
            if (row == null || row.getApproType() != ApprovalType.External) {
                return false;
            }
            if (StringUtils.isBlank(row.getApproIdentity())) {
                // never initiated successfully — nothing to revoke, zero-call skip (prd R4)
                log.info("[DingBridge] no flowId on approval {} — flows/cancel ({}) skipped", approvalId, reason);
                return false;
            }
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        doCancelAttempt(approvalId, reason);
                    }
                });
            } else {
                doCancelAttempt(approvalId, reason);
            }
            return true;
        } catch (Throwable t) {
            log.warn("[DingBridge] notifyCancelFlow failed for approval {} ({}), degraded", approvalId, reason, t);
            return false;
        }
    }

    // ==================== manual reissue (design §4) ====================

    /**
     * Console manual re-issue of a failed/never-started outbound initiation — synchronous: the
     * endpoint reports the bridge outcome to the operator. Guard matrix (design §4): row exists
     * in-tenant (not-found semantics otherwise) ∧ External channel ∧ outbound_status ∈
     * {FAILED, NULL/NOT_STARTED} — STARTED rejects (double-send protection; the bridge-side dedup
     * key is the second net) and STARTING rejects (a send is in flight). retry_count resets to 0
     * via the reissue claim. Throws ErrorMessageException (user-facing i18n) on every rejection;
     * bridge/IO failures inside the attempt degrade into the outbound columns and surface as
     * DING_BRIDGE_REISSUE_FAILED with the persisted cause.
     */
    public void reissueFlow(String puid, String uid, long approvalId) {
        if (!isOutboundEnabled() || StringUtils.isBlank(resolveEndpoint().getBaseUrl())) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.DING_BRIDGE_REISSUE_DISABLED.name()));
        }
        DmApprovalDO row = approvalDal.approvalMapper().queryById(approvalId);
        if (row == null || !puid.equals(row.getPrimaryUid())) {
            // cross-tenant = not-found (existence not disclosed, /releases/return precedent)
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.GOV_TICKET_V2_TICKET_NOT_FOUND.name(), approvalId));
        }
        if (row.getApproType() != ApprovalType.External) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.DING_BRIDGE_REISSUE_CHANNEL_MISMATCH.name(), approvalId));
        }
        String status = row.getOutboundStatus();
        if (STATUS_STARTED.equals(status)) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.DING_BRIDGE_REISSUE_ALREADY_STARTED.name(), approvalId));
        }
        if (STATUS_STARTING.equals(status)) {
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.DING_BRIDGE_REISSUE_IN_FLIGHT.name(), approvalId));
        }
        log.info("[DingBridge] manual reissue requested by uid={} for approval {} (status={})", uid, approvalId, status);
        doCreateAttempt(approvalId, Claim.REISSUE, 0, STATUS_FAILED.equals(status));

        DmApprovalDO after = approvalDal.approvalMapper().queryById(approvalId);
        if (after == null || !STATUS_STARTED.equals(after.getOutboundStatus())) {
            String cause = after == null ? "unknown" : StringUtils.trimToEmpty(after.getOutboundError());
            throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.DING_BRIDGE_REISSUE_FAILED.name(), cause));
        }
    }

    // ==================== compensation sweep (design §4) ====================

    /**
     * One compensation-sweep round — hooked onto the existing ApprovalTaskScheduler tick (NO new
     * thread): scans {@code outbound_status=FAILED ∧ retry_count<MAX_AUTO_RETRY ∧
     * next_retry_at<=now} and re-sends with row-level CAS claims (multi-console safe: the loser
     * of the claim skips). Backoff 1m/5m/15m; cap-reached rows stop scanning silently (the
     * terminal alert already fired once at the cap transition). Kill switch off = the whole
     * sweep is a no-op (pure pull-mode). NEVER throws.
     *
     * @return the number of rows an attempt was made for (diagnostics/log only).
     */
    public int retryFailedOutbounds() {
        int attempted = 0;
        try {
            if (!isOutboundEnabled() || StringUtils.isBlank(resolveEndpoint().getBaseUrl())) {
                return 0;
            }
            List<Long> due = approvalDal.approvalMapper().listOutboundRetryDue(MAX_AUTO_RETRY, SWEEP_BATCH_LIMIT);
            if (due == null || due.isEmpty()) {
                return 0;
            }
            for (Long approvalId : due) {
                try {
                    DmApprovalDO row = approvalDal.approvalMapper().queryById(approvalId);
                    if (row == null || !STATUS_FAILED.equals(row.getOutboundStatus())) {
                        continue; // raced to another state since the scan — skip
                    }
                    int expected = row.getOutboundRetryCount() == null ? 0 : row.getOutboundRetryCount();
                    if (expected >= MAX_AUTO_RETRY) {
                        continue; // terminal silence (the cap alert already fired once)
                    }
                    doCreateAttempt(approvalId, Claim.RETRY, expected, true);
                    attempted++;
                } catch (Throwable perRow) {
                    log.warn("[DingBridge] outbound retry failed for approval {}, isolated", approvalId, perRow);
                }
            }
        } catch (Throwable t) {
            log.warn("[DingBridge] outbound retry sweep degraded", t);
        }
        return attempted;
    }

    /**
     * New approval round reset (design boundary #15): release resubmit rebuilds the approval
     * surface — clear appro_identity + the four outbound columns so the round-2 flowId is not
     * blocked by the "write only when empty" guard (the old instance was already closed by the
     * RELEASE_RETURNED cancel notification). Called INSIDE the resubmit transaction on the FRESH
     * approval row (clean by construction — this is the explicit contract materialization and
     * the safety net should the row ever be reused in the future).
     */
    public void resetForNewApprovalRound(long approvalId) {
        approvalDal.approvalMapper().clearOutboundForNewRound(approvalId);
    }

    // ==================== KV resolution (fail-safe + clamped) ====================

    /**
     * Kill-switch fail-safe read — fails CLOSED (null/exception → {@link #DEFAULT_OUTBOUND_ENABLE}
     * = false): an unreadable switch means NO outbound (pure pull-mode fallback). Public for the
     * hook-point pre-checks and tests.
     */
    public boolean isOutboundEnabled() {
        try {
            Boolean enable = systemDal.fetchSystemConf(RootUserConfig.Fields.dingBridgeOutboundEnable, Boolean.class);
            return enable != null && enable;
        } catch (Exception e) {
            return DEFAULT_OUTBOUND_ENABLE;
        }
    }

    /** Fail-safe resolution of the three connection KVs; the timeout is clamped into the whitelist Range. */
    protected BridgeEndpoint resolveEndpoint() {
        String baseUrl = DEFAULT_BASE_URL;
        String token = "";
        int timeoutMs = DEFAULT_TIMEOUT_MS;
        try {
            String raw = systemDal.fetchSystemConf(RootUserConfig.Fields.dingBridgeBaseUrl, String.class);
            if (raw != null) {
                baseUrl = raw.trim();
            }
        } catch (Exception e) {
            log.warn("[DingBridge] read dingBridgeBaseUrl failed, default blank", e);
        }
        try {
            String encrypted = systemDal.fetchSystemConf(RootUserConfig.Fields.dingBridgeAuthToken);
            if (StringUtils.isNotBlank(encrypted)) {
                token = decryptConfigValue(encrypted);
            }
        } catch (Exception e) {
            log.warn("[DingBridge] read/decrypt dingBridgeAuthToken failed, header omitted", e);
        }
        try {
            Integer configured = systemDal.fetchSystemConf(RootUserConfig.Fields.dingBridgeTimeoutMs, Integer.class);
            if (configured != null) {
                timeoutMs = Math.max(MIN_TIMEOUT_MS, Math.min(MAX_TIMEOUT_MS, configured));
            }
        } catch (Exception e) {
            log.warn("[DingBridge] read dingBridgeTimeoutMs failed, default {}ms", DEFAULT_TIMEOUT_MS, e);
        }
        return new BridgeEndpoint(baseUrl, StringUtils.trimToEmpty(token), timeoutMs);
    }

    /** decryption seam (tencentCloudSecretKey consumer precedent) — protected for tests. */
    protected String decryptConfigValue(String encrypted) {
        return CryptService.INSTANCE.decryptUseDefaultKeyAndSalt(encrypted);
    }

    // ==================== send core (shared by trigger / sweep / reissue) ====================

    /**
     * One flows/create attempt in THREE phases: ①guards + CAS claim + payload assembly (own
     * REQUIRES_NEW tx) → ②the bridge HTTP call (NO transaction — slow external I/O never holds a
     * connection or row locks) → ③write-back STARTED + flowId-if-empty | FAILED + error + backoff
     * with single-shot alerting (own REQUIRES_NEW tx). NEVER throws.
     * <p>
     * <b>Hard invariant — every DB touch of an attempt runs INSIDE an {@link #inNewTx} block and
     * NO mapper call precedes the first block</b> (afterCommit stale-connection trap): in the
     * FIRST path this method executes inside a {@code TransactionSynchronization.afterCommit}
     * callback, where the committed business tx's ConnectionHolder is STILL bound to the thread
     * and synchronizations are STILL active (Spring clears both only after afterCompletion). A
     * bare mapper call there binds the MyBatis SqlSession to that stale connection (mybatis-spring
     * {@code registerSessionHolder} keys off {@code isSynchronizationActive}), and since
     * {@code DataSourceTransactionManager.doSuspend} does NOT unbind the SqlSessionHolder, every
     * later write — even inside a REQUIRES_NEW template — reuses the same stale session and is
     * silently ROLLED BACK at session close (Spring afterCommit contract: data access there
     * "participates in the original transaction ... with no commit following anymore"). With the
     * invariant kept, phase ① is the callback's first DB touch: its session is created inside the
     * inner tx, binds to the fresh inner connection and is committed by the inner tx. (The
     * RETRY/REISSUE paths run without any surrounding transaction — wrapping is
     * semantics-preserving there: the claim simply commits at the end of phase ① instead of
     * per-statement.)
     */
    private void doCreateAttempt(long approvalId, Claim claim, int expectedRetryCount, boolean previousWasFailed) {
        try {
            // phase ① — guards + claim + payload, ALL DB access inside its own tx (see invariant above)
            SendPrep prep = inNewTx(status -> prepareAndClaim(approvalId, claim, expectedRetryCount));
            if (prep == null) {
                return; // guard-skipped or claim lost — logged inside phase ①
            }

            // phase ② — the bridge call, deliberately outside any DB transaction
            BridgeFlowResult result = dingBridgeClient.createFlow(prep.endpoint, prep.request);

            if (result.isOk()) {
                // phase ③ — success write-back in its own tx
                inNewTx(() -> {
                    approvalDal.approvalMapper().markOutboundStarted(approvalId);
                    if (StringUtils.isNotBlank(result.getFlowId())) {
                        int written = approvalDal.approvalMapper().updateOutboundFlowIdIfEmpty(approvalId, result.getFlowId());
                        if (written == 0) {
                            // boundary #6: an inbound command won the race — "only when empty" stands,
                            // STARTED still lands (dual sources carry the same instance id anyway)
                            log.info("[DingBridge] appro_identity of {} already set (inbound first) — flowId backfill skipped",
                                    approvalId);
                        }
                    }
                });
                log.info("[DingBridge] flows/create ok for {} {} (approval {}, flowId={}, duplicated={})",
                        prep.biz.bizType, prep.biz.bizId, approvalId, result.getFlowId(), result.isDuplicated());
            } else {
                handleCreateFailure(prep.row, prep.biz, claim, expectedRetryCount, previousWasFailed, result);
            }
        } catch (Throwable t) {
            // unexpected infrastructure failure (DB down mid-attempt, ...) — best-effort FAILED write
            log.error("[DingBridge] flows/create attempt for approval {} crashed, degraded", approvalId, t);
            try {
                persistFailureAndAlert(approvalId, null, null, claim, expectedRetryCount, previousWasFailed,
                        "internal error: " + ExceptionUtils.getRootCauseMessage(t), true);
            } catch (Throwable inner) {
                log.warn("[DingBridge] failure write-back for approval {} also failed", approvalId, inner);
            }
        }
    }

    /**
     * Phase ① of {@link #doCreateAttempt} — runs INSIDE the phase-① REQUIRES_NEW transaction:
     * fresh row read → applicability guards (External channel / non-terminal ticket / baseUrl
     * present) → row-level CAS claim → payload assembly. {@code null} = skipped (logged here).
     */
    private SendPrep prepareAndClaim(long approvalId, Claim claim, int expectedRetryCount) {
        DmApprovalDO row = approvalDal.approvalMapper().queryById(approvalId);
        if (row == null) {
            log.warn("[DingBridge] approval {} vanished before outbound send — skipped", approvalId);
            return null;
        }
        if (row.getApproType() != ApprovalType.External) {
            log.info("[DingBridge] approval {} is {} (not External) — outbound skipped", approvalId, row.getApproType());
            return null;
        }
        if (ApprovalStatus.isEndStatus(row.getTicketStatus())) {
            // defensive: never initiate a DingTalk flow for an already-terminal ticket
            log.info("[DingBridge] approval {} is terminal ({}) — outbound create skipped",
                    approvalId, row.getTicketStatus());
            quiesceTerminalFailure(row);
            return null;
        }
        BridgeEndpoint endpoint = resolveEndpoint();
        if (StringUtils.isBlank(endpoint.getBaseUrl())) {
            // config vanished between registration and firing — no claim, status untouched
            log.warn("[DingBridge] baseUrl unset at send time — flows/create skipped for approval {}", approvalId);
            return null;
        }
        BizRef biz = resolveBizRef(row);
        if (biz == null) {
            return null; // malformed release row — warned inside
        }

        int claimed = switch (claim) {
            case FIRST -> approvalDal.approvalMapper().claimOutboundFirstSend(approvalId);
            case RETRY -> approvalDal.approvalMapper().claimOutboundForRetry(approvalId, expectedRetryCount);
            case REISSUE -> approvalDal.approvalMapper().claimOutboundForReissue(approvalId);
        };
        if (claimed == 0) {
            // lost the CAS race (concurrent console / sweep / duplicate trigger) — the winner sends
            log.info("[DingBridge] outbound claim lost for approval {} (claim={}) — skipped", approvalId, claim);
            return null;
        }
        return new SendPrep(row, biz, endpoint, buildCreateRequest(row, biz));
    }

    /**
     * Sweep-churn quiesce (EXEC_FAIL idle-spin lesson, governance-contracts "Sweep IN-set
     * discipline"): a FAILED row whose ticket reached a terminal state would be re-scanned by
     * EVERY sweep round forever — the send guard above correctly refuses re-initiation, so the
     * row can never leave the {@code listOutboundRetryDue} scan set on its own (30s scan churn +
     * log noise with zero possible progress). Keep FAILED + the retry count, clear next_retry_at
     * → permanently out of the scan set. NO alert: the ticket is terminal, the flow no longer
     * matters (the FAILED-transition alert already fired while it was still actionable).
     */
    private void quiesceTerminalFailure(DmApprovalDO row) {
        if (!STATUS_FAILED.equals(row.getOutboundStatus())) {
            return; // only FAILED rows are ever scanned — nothing to quiesce
        }
        int retryCount = row.getOutboundRetryCount() == null ? 0 : row.getOutboundRetryCount();
        approvalDal.approvalMapper().markOutboundFailed(row.getId(),
                StringUtils.substring("ticket reached terminal state " + row.getTicketStatus()
                        + " — outbound permanently quiesced", 0, ERROR_MAX_CHARS),
                retryCount, null);
    }

    private void handleCreateFailure(DmApprovalDO row, BizRef biz, Claim claim, int expectedRetryCount,
                                     boolean previousWasFailed, BridgeFlowResult result) {
        long approvalId = row.getId();
        log.warn("[DingBridge] flows/create failed for {} {} (approval {}): {} (retryable={})",
                biz.bizType, biz.bizId, approvalId, result.getError(), result.isRetryable());
        persistFailureAndAlert(approvalId, row, biz, claim, expectedRetryCount, previousWasFailed,
                result.getError(), result.isRetryable());
    }

    /**
     * FAILED write-back + single-shot alerting inside ONE REQUIRES_NEW transaction (design §3.3:
     * the write-back must not hang on the — already committed or absent — caller transaction;
     * the alert rides the inner tx so AlertPushService's afterCommit registration fires on the
     * inner commit instead of being dropped by the outer afterCommit iteration snapshot).
     * <p>
     * Alert cadence (anti-bombardment, design §4): first transition to FAILED → one alert
     * (retry copy when a backoff slot is scheduled, final copy on a non-retryable 4xx);
     * mid-sequence sweep failures → silent; cap reached / non-retryable after retries →
     * one terminal alert.
     */
    private void persistFailureAndAlert(long approvalId, DmApprovalDO row, BizRef biz, Claim claim,
                                        int expectedRetryCount, boolean previousWasFailed,
                                        String error, boolean retryable) {
        int newRetryCount = claim == Claim.RETRY ? expectedRetryCount + 1 : 0;
        boolean willRetry = retryable && newRetryCount < MAX_AUTO_RETRY;
        Date nextRetryAt = willRetry ? new Date(System.currentTimeMillis() + BACKOFF_MS[newRetryCount]) : null;
        String truncatedError = StringUtils.substring(StringUtils.trimToEmpty(error), 0, ERROR_MAX_CHARS);
        inNewTx(() -> {
            approvalDal.approvalMapper().markOutboundFailed(approvalId, truncatedError, newRetryCount, nextRetryAt);
            boolean alertNow = !previousWasFailed || !willRetry;
            if (alertNow && row != null) {
                pushBridgeAlert(row, biz, AlertContext.BRIDGE_OP_CREATE, truncatedError, !willRetry);
            }
        });
    }

    /**
     * One flows/cancel attempt (afterCommit context) — never throws, failure = alert + warn only.
     * <p>
     * Transaction discipline: this callback performs ONLY bare mapper READS (safe in afterCommit —
     * committed data is visible and a read-only session has nothing to roll back) and NO
     * dm_approval write at all; the failure alert rides an inner REQUIRES_NEW tx so
     * AlertPushService's afterCommit registration fires on the inner commit. Should a cancel-side
     * DB write ever be needed, it MUST follow the {@link #doCreateAttempt} phase-wrapping
     * invariant (first DB touch = an inNewTx block), never a bare mapper call.
     */
    private void doCancelAttempt(long approvalId, String reason) {
        try {
            DmApprovalDO row = approvalDal.approvalMapper().queryById(approvalId);
            if (row == null || row.getApproType() != ApprovalType.External) {
                return;
            }
            String flowId = row.getApproIdentity();
            if (StringUtils.isBlank(flowId)) {
                // identity cleared/never set between registration and firing — zero-call skip
                return;
            }
            BridgeEndpoint endpoint = resolveEndpoint();
            if (StringUtils.isBlank(endpoint.getBaseUrl())) {
                log.warn("[DingBridge] baseUrl unset at cancel time — flows/cancel skipped for approval {}", approvalId);
                return;
            }
            BizRef biz = resolveBizRef(row);
            if (biz == null) {
                return;
            }
            BridgeFlowResult result = dingBridgeClient.cancelFlow(endpoint,
                    new BridgeCancelRequest(biz.bizType, biz.bizId, flowId, reason));
            if (result.isOk()) {
                log.info("[DingBridge] flows/cancel ok for {} {} (approval {}, reason={})",
                        biz.bizType, biz.bizId, approvalId, reason);
            } else {
                log.warn("[DingBridge] flows/cancel failed for {} {} (approval {}, reason={}): {} — local flow unaffected",
                        biz.bizType, biz.bizId, approvalId, reason, result.getError());
                String truncatedError = StringUtils.substring(StringUtils.trimToEmpty(result.getError()), 0, ERROR_MAX_CHARS);
                inNewTx(() -> pushBridgeAlert(row, biz, AlertContext.BRIDGE_OP_CANCEL, truncatedError, false));
            }
        } catch (Throwable t) {
            log.warn("[DingBridge] flows/cancel attempt for approval {} crashed, degraded", approvalId, t);
        }
    }

    // ==================== payload assembly ====================

    /** (bizType,bizId,bizNo) triple derived from the approval row — null when a release row lost its ticketInfo link. */
    private BizRef resolveBizRef(DmApprovalDO row) {
        if (row.getApproBiz() == ApprovalBiz.DM_PROD_RELEASE) {
            ApprovalMO mo = parseTicketInfo(row);
            if (mo == null || mo.getReleaseId() == null) {
                log.warn("[DingBridge] release approval {} carries no releaseId in ticketInfo — outbound skipped", row.getId());
                return null;
            }
            return new BizRef(BIZ_TYPE_RELEASE, String.valueOf(mo.getReleaseId()),
                    StringUtils.isNotBlank(mo.getReleaseNo()) ? mo.getReleaseNo() : row.getBizId());
        }
        return new BizRef(BIZ_TYPE_TICKET, String.valueOf(row.getId()), row.getBizId());
    }

    private BridgeCreateRequest buildCreateRequest(DmApprovalDO row, BizRef biz) {
        BridgeCreateRequest req = new BridgeCreateRequest();
        req.setBizType(biz.bizType);
        req.setBizId(biz.bizId);
        req.setBizNo(biz.bizNo);
        req.setTitle(row.getTicketTitle());
        req.setApplicant(new BridgeCreateRequest.Applicant(row.getOwnerUid(), resolveUsername(row.getOwnerUid())));
        req.setSummary(buildSummary(row));
        ApprovalMO mo = parseTicketInfo(row);
        String approBizLabel;
        if (row.getApproBiz() == ApprovalBiz.DM_PROD_RELEASE) {
            approBizLabel = ApprovalBiz.DM_PROD_RELEASE.name();
        } else {
            approBizLabel = mo != null && StringUtils.isNotBlank(mo.getTicketType())
                    ? mo.getTicketType() : (row.getApproBiz() == null ? null : row.getApproBiz().name());
        }
        req.setContext(new BridgeCreateRequest.Context(row.getPrimaryUid(), approBizLabel));
        return req;
    }

    /** ≤200-char SQL/entry summary — the ledger list-level caliber (no full SQL, design §2.1/§5). */
    private static String buildSummary(DmApprovalDO row) {
        String raw = StringUtils.trimToEmpty(row.getRawSql());
        return raw.length() > SUMMARY_MAX_CHARS ? raw.substring(0, SUMMARY_MAX_CHARS) : raw;
    }

    private static ApprovalMO parseTicketInfo(DmApprovalDO row) {
        try {
            return JsonUtils.toObj(row.getTicketInfo(), ApprovalMO.class);
        } catch (Throwable t) {
            log.warn("[DingBridge] ticketInfo of approval {} is unparsable, treated as absent", row.getId());
            return null;
        }
    }

    /** applicant username for the payload; any lookup failure degrades to the raw uid (never throws). */
    private String resolveUsername(String uid) {
        if (StringUtils.isBlank(uid)) {
            return "-";
        }
        try {
            DmAuthUserDO user = authDal.userMapper().queryByUid(uid);
            if (user != null && StringUtils.isNotBlank(user.getUsername())) {
                return user.getUsername();
            }
        } catch (RuntimeException e) {
            log.warn("[DingBridge] resolve applicant name failed for uid={}", uid, e);
        }
        return uid;
    }

    // ==================== internals ====================

    private void pushBridgeAlert(DmApprovalDO row, BizRef biz, String op, String error, boolean finalFailure) {
        try {
            AlertContext ctx = new AlertContext();
            ctx.setTicketId(row.getId());
            ctx.setBridgeOp(op);
            ctx.setBridgeBizType(biz == null ? null : biz.bizType);
            ctx.setBridgeBizNo(biz == null ? null : biz.bizNo);
            ctx.setBridgeError(error);
            ctx.setBridgeFinal(finalFailure);
            alertPushService.pushAlert(row.getPrimaryUid(), AlertScene.DING_BRIDGE_OUTBOUND_FAILED, ctx);
        } catch (Throwable t) {
            // alerting is best-effort on top of an already-failed outbound (never throws upward)
            log.warn("[DingBridge] failure alerting for approval {} degraded", row.getId(), t);
        }
    }

    /**
     * REQUIRES_NEW write-back transaction (design §3.3): every outbound state write runs in its
     * OWN transaction — the caller context is either an afterCommit callback (the original tx is
     * already committed; Spring mandates REQUIRES_NEW for data access there) or no transaction at
     * all (sweep/reissue). Method-local template — no shared mutable state (缓存纪律).
     * <p>
     * NOTE (afterCommit trap, see {@link #doCreateAttempt}): REQUIRES_NEW alone does NOT protect
     * a mapper call if a MyBatis session was already bound to the stale committed connection by
     * an earlier BARE mapper call in the same callback — the SqlSessionHolder survives the
     * transaction suspension and pins every later statement to that stale connection (silently
     * rolled back at session close). Hence the hard invariant: inside an afterCommit callback the
     * FIRST DB touch must already be an inNewTx block, never a bare mapper call.
     */
    private void inNewTx(Runnable action) {
        inNewTx(status -> {
            action.run();
            return null;
        });
    }

    /** value-returning variant of {@link #inNewTx(Runnable)} (phase ① of doCreateAttempt). */
    private <T> T inNewTx(Function<TransactionStatus, T> action) {
        TransactionTemplate template = new TransactionTemplate(txManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(action::apply);
    }

    /** phase-① output: everything phases ②/③ need, assembled inside the phase-① transaction. */
    private static class SendPrep {
        final DmApprovalDO        row;
        final BizRef              biz;
        final BridgeEndpoint      endpoint;
        final BridgeCreateRequest request;

        SendPrep(DmApprovalDO row, BizRef biz, BridgeEndpoint endpoint, BridgeCreateRequest request) {
            this.row = row;
            this.biz = biz;
            this.endpoint = endpoint;
            this.request = request;
        }
    }

    /** the (bizType,bizId,bizNo) identity of the outbound subject. */
    private static class BizRef {
        final String bizType;
        final String bizId;
        final String bizNo;

        BizRef(String bizType, String bizId, String bizNo) {
            this.bizType = bizType;
            this.bizId = bizId;
            this.bizNo = bizNo;
        }
    }
}
