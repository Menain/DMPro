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
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.clougence.clouddm.console.web.component.cicd.ImSenderConfig;
import com.clougence.clouddm.console.web.component.cicd.ImSenderService;
import com.clougence.clouddm.console.web.global.i18n.DmI18nUtils;
import com.clougence.clouddm.console.web.global.i18n.I18nDmMsgKeys;
import com.clougence.clouddm.platform.dal.access.SystemDal;
import com.clougence.clouddm.platform.dal.model.system.DmSysMessengerDO;
import com.clougence.clouddm.sdk.messenger.MsgContent;
import com.clougence.clouddm.sdk.messenger.MsgSendType;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * Sends IM notifications to the ticket applicant when a return-for-revision action
 * is performed by someone other than the applicant (approver return, DBA proxy return,
 * release stmt return). Uses the direct-send path (no change flow dependency) with
 * full degradation: no provider / send failure / no messenger → log.warn, never throw.
 * <p>
 * Tx safety: callers register an afterCommit synchronization so the notification only
 * fires after the business transaction commits (rollback → no spurious notification).
 * If no transaction is active, the notification fires immediately (defensive branch).
 */
@Slf4j
@Service
public class GovReturnNotifyService {

    @Resource
    private SystemDal     systemDal;
    @Resource
    private ImSenderService imSenderService;

    /**
     * Notify the ticket applicant (targetUid) that their ticket was returned for revision.
     * Skipped silently when actorUid == targetUid (self-return — they already know).
     *
     * @param puid       primary-account uid (tenant realm, for messenger config lookup)
     * @param actorUid   the operator who performed the return
     * @param targetUid  the ticket applicant (notification target = ownerUid)
     * @param ticketId   ticket id (for deep link)
     * @param ticketTitle ticket title (for message body, may be null)
     * @param reason     optional return reason (may be null/blank)
     * @param via        return source label: "APPROVER", "DBA", "OWNER", "RELEASE"
     */
    public void notifyReturn(String puid, String actorUid, String targetUid,
                             long ticketId, String ticketTitle, String reason, String via) {
        if (actorUid != null && actorUid.equals(targetUid)) {
            return; // self-return: no notification
        }

        String msgText = buildMessage(ticketId, ticketTitle, reason, via);
        // Defer to afterCommit if inside a transaction; otherwise send immediately.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    doSend(puid, targetUid, ticketId, msgText);
                }
            });
        } else {
            doSend(puid, targetUid, ticketId, msgText);
        }
    }

    private String buildMessage(long ticketId, String ticketTitle, String reason, String via) {
        StringBuilder sb = new StringBuilder();
        boolean isRelease = "RELEASE".equals(via);
        if (isRelease) {
            sb.append(DmI18nUtils.getMessage(I18nDmMsgKeys.GOV_RETURN_NOTIFY_RELEASE_TITLE.name(), ticketId));
        } else {
            sb.append(DmI18nUtils.getMessage(I18nDmMsgKeys.GOV_RETURN_NOTIFY_TITLE.name(), ticketId));
        }
        if (ticketTitle != null && !ticketTitle.isBlank()) {
            sb.append("\n").append(ticketTitle);
        }
        sb.append("\n").append(DmI18nUtils.getMessage(I18nDmMsgKeys.GOV_RETURN_NOTIFY_VIA.name(), via));
        if (reason != null && !reason.isBlank()) {
            sb.append("\n").append(DmI18nUtils.getMessage(I18nDmMsgKeys.GOV_RETURN_NOTIFY_REASON.name(), reason));
        }
        if (isRelease) {
            sb.append("\n").append(DmI18nUtils.getMessage(I18nDmMsgKeys.GOV_RETURN_NOTIFY_RELEASE_LINK.name(), ticketId));
        } else {
            sb.append("\n").append(DmI18nUtils.getMessage(I18nDmMsgKeys.GOV_RETURN_NOTIFY_LINK.name(), ticketId));
        }
        return sb.toString();
    }

    private void doSend(String puid, String targetUid, long ticketId, String msgText) {
        List<DmSysMessengerDO> messengers;
        try {
            messengers = systemDal.messengerMapper().queryMessengerByOwner(puid);
        } catch (Exception e) {
            log.warn("[GovReturnNotify] Failed to query messengers for puid={}, ticketId={}", puid, ticketId, e);
            return;
        }
        if (messengers == null || messengers.isEmpty()) {
            log.warn("[GovReturnNotify] No IM messenger configured for puid={}, ticketId={} — skipping notification", puid, ticketId);
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
                // Full degradation: IM failure must never block the return flow.
                log.warn("[GovReturnNotify] IM send failed for puid={}, messengerId={}, ticketId={}",
                    puid, messenger.getId(), ticketId, e);
            }
        }
    }
}
