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
package com.clougence.clouddm.console.web.component.alert;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.clougence.clouddm.console.web.component.cicd.ImSenderConfig;
import com.clougence.clouddm.console.web.component.cicd.ImSenderService;
import com.clougence.clouddm.console.web.component.config.RootUserConfig;
import com.clougence.clouddm.console.web.global.i18n.DmI18nUtils;
import com.clougence.clouddm.console.web.global.i18n.I18nDmMsgKeys;
import com.clougence.clouddm.platform.dal.access.AuthDal;
import com.clougence.clouddm.platform.dal.access.SystemDal;
import com.clougence.clouddm.platform.dal.model.auth.DmAuthUserDO;
import com.clougence.clouddm.platform.dal.model.system.DmSysMessengerDO;
import com.clougence.clouddm.sdk.messenger.MsgContent;
import com.clougence.clouddm.sdk.messenger.MsgSendType;
import com.clougence.utils.StringUtils;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * B6 M1 (R15, design §6.2 方案 A): the generic scene-based alert push channel. Structure is a
 * segment-by-segment copy of the {@code GovReturnNotifyService} template — afterCommit
 * registration → tenant messenger lookup → enable-filtered direct send → three-stage try-catch
 * FULL degradation (never throws, never blocks the business flow).
 * <p>
 * Channel = the platform's existing IM group-robot framework ({@code dm_sys_messenger} +
 * {@code ImSenderService} direct-send path, zero new infrastructure): every ENABLED robot of the
 * tenant receives the text (ops group + applicant group = two rows = double push). Real
 * per-person @ addressing is a cross-plugin SPI enhancement left to phase 2 — this phase notes
 * the applicant inside the copy.
 * <p>
 * Switch: {@code alertPushEnable} (RootUserConfig KV, fail-safe read mirroring the
 * govTicketFullWidthLintBlock pattern) — off or read failure → silent skip; it doubles as the
 * test-environment misfire gate together with the "no messenger configured → silent" behavior.
 */
@Slf4j
@Service
public class AlertPushService {

    /** fail-safe default of the alertPushEnable switch (mirrors the @UserConfigDef defaultValue — drift-pinned by test) */
    public static final boolean DEFAULT_ALERT_PUSH_ENABLE = true;

    @Resource
    private SystemDal       systemDal;
    @Resource
    private ImSenderService imSenderService;
    @Resource
    private AuthDal         authDal;

    /**
     * Push one scene alert to every enabled IM robot of the tenant.
     * <p>
     * Transaction safety (GovReturnNotifyService contract): inside an active transaction the
     * send is deferred to afterCommit (rollback → no spurious alert); without one it fires
     * immediately (defensive branch). NEVER throws — every failure degrades to a warn log.
     *
     * @return true when the alert was accepted for delivery (switch on); false when silently
     *         skipped (switch off / read failure) — the caller writes the ticket event trail
     *         only for accepted alerts.
     */
    public boolean pushAlert(String puid, AlertScene scene, AlertContext ctx) {
        try {
            if (!isAlertPushEnabled()) {
                log.info("[AlertPush] alertPushEnable is off — skipping scene {} (ticket {})",
                        scene, ctx == null ? null : ctx.getTicketId());
                return false;
            }
            if (scene == null || ctx == null) {
                return false;
            }
            String msgText = buildMessage(scene, ctx);
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        doSend(puid, scene, ctx.getTicketId(), msgText);
                    }
                });
            } else {
                doSend(puid, scene, ctx.getTicketId(), msgText);
            }
            return true;
        } catch (Exception e) {
            // the alert facility must never break the caller (submission flow)
            log.warn("[AlertPush] pushAlert failed for scene {}, degraded", scene, e);
            return false;
        }
    }

    /**
     * Fail-safe switch read — the govTicketFullWidthLintBlock pattern (null/exception → default).
     * Public for the trail-writing caller: the ticket event records the alert FACT, so an
     * alert suppressed by the switch leaves no trail (nothing was triggered).
     */
    public boolean isAlertPushEnabled() {
        try {
            Boolean enable = systemDal.fetchSystemConf(RootUserConfig.Fields.alertPushEnable, Boolean.class);
            return enable == null || enable;
        } catch (Exception e) {
            return DEFAULT_ALERT_PUSH_ENABLE;
        }
    }

    private String buildMessage(AlertScene scene, AlertContext ctx) {
        switch (scene) {
            case GHOST_LARGE_TABLE_NO_PK:
                return buildGhostLargeTableMessage(ctx);
            default:
                return DmI18nUtils.getMessage(I18nDmMsgKeys.ALERT_PUSH_GHOST_TITLE.name(), ctx.getTicketId());
        }
    }

    private String buildGhostLargeTableMessage(AlertContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append(DmI18nUtils.getMessage(I18nDmMsgKeys.ALERT_PUSH_GHOST_TITLE.name(), ctx.getTicketId()));
        sb.append("\n").append(DmI18nUtils.getMessage(
                I18nDmMsgKeys.ALERT_PUSH_GHOST_APPLICANT.name(), resolveUsername(ctx.getApplicantUid())));
        String table = (StringUtils.isBlank(ctx.getDbName()) ? "" : ctx.getDbName() + ".")
                       + StringUtils.trimToEmpty(ctx.getTableName());
        sb.append("\n").append(DmI18nUtils.getMessage(I18nDmMsgKeys.ALERT_PUSH_GHOST_TABLE.name(), table));
        sb.append("\n").append(DmI18nUtils.getMessage(I18nDmMsgKeys.ALERT_PUSH_GHOST_STATS.name(), //
                display(ctx.getTableRows()), displayMb(ctx.getTotalBytes()), //
                display(ctx.getRowThreshold()), displayMb(ctx.getSizeThresholdBytes())));
        I18nDmMsgKeys riskKey = AlertContext.RISK_TUNNEL.equals(ctx.getRiskCode()) //
                ? I18nDmMsgKeys.ALERT_PUSH_GHOST_RISK_TUNNEL : I18nDmMsgKeys.ALERT_PUSH_GHOST_RISK_NO_UNIQUE_KEY;
        sb.append("\n").append(DmI18nUtils.getMessage(riskKey.name()));
        sb.append("\n").append(DmI18nUtils.getMessage(I18nDmMsgKeys.ALERT_PUSH_GHOST_LINK.name(), ctx.getTicketId()));
        return sb.toString();
    }

    /** applicant username for the copy; any lookup failure degrades to the raw uid (never throws). */
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
            log.warn("[AlertPush] resolve applicant name failed for uid={}", uid, e);
        }
        return uid;
    }

    private static String display(Long value) {
        return value == null ? "-" : String.valueOf(value);
    }

    private static String displayMb(Long bytes) {
        return bytes == null ? "-" : String.valueOf(bytes / (1024L * 1024L));
    }

    private void doSend(String puid, AlertScene scene, long ticketId, String msgText) {
        List<DmSysMessengerDO> messengers;
        try {
            messengers = systemDal.messengerMapper().queryMessengerByOwner(puid);
        } catch (Exception e) {
            log.warn("[AlertPush] Failed to query messengers for puid={}, scene={}, ticketId={}", puid, scene, ticketId, e);
            return;
        }
        if (messengers == null || messengers.isEmpty()) {
            log.warn("[AlertPush] No IM messenger configured for puid={}, scene={}, ticketId={} — skipping alert",
                    puid, scene, ticketId);
            return;
        }

        MsgContent message = new MsgContent();
        message.setMessageId(UUID.randomUUID().toString());
        message.setType(MsgSendType.Text);
        message.setBody(msgText);

        for (DmSysMessengerDO messenger : messengers) {
            if (!messenger.isEnable()) {
                continue;
            }
            try {
                ImSenderConfig imConfig = ImSenderConfig.builder()
                        .imType(messenger.getImType())
                        .webhookUrl(messenger.getWebhook())
                        .secret(messenger.getSecret())
                        .build();
                imSenderService.sendMessage(puid, imConfig, message);
            } catch (Exception e) {
                // Full degradation: an alert failure must never propagate (send result is not persisted)
                log.warn("[AlertPush] IM send failed for puid={}, messengerId={}, scene={}, ticketId={}",
                        puid, messenger.getId(), scene, ticketId, e);
            }
        }
    }
}
