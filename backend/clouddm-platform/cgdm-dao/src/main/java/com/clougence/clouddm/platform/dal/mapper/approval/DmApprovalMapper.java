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
package com.clougence.clouddm.platform.dal.mapper.approval;

import java.util.Date;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalBiz;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.ArgApprovalQueryObj;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;

/**
 * @author Ekko
 * @date 2024/5/13 14:43
*/
public interface DmApprovalMapper extends BaseMapper<DmApprovalDO> {

    void updateStatusByEnum(@Param("ticketId") long ticketId, @Param("ticketStatus") ApprovalStatus ticketStatus, @Param("statusMessage") String statusMessage);

    IPage<DmApprovalDO> listTicketByConditionAndPage(Page page, @Param("ticketQuery") ArgApprovalQueryObj ticketQueryObject, @Param("puid") String puid);

    IPage<DmApprovalDO> listConfirmTicketByConditionAndPage(Page page, @Param("ticketQuery") ArgApprovalQueryObj ticketQueryObject);

    IPage<DmApprovalDO> listAuthTicketByConditionAndPage(Page page, @Param("ticketQuery") ArgApprovalQueryObj ticketQueryObject);

    DmApprovalDO queryByBizId(@Param("bizId") String bizId);

    DmApprovalDO queryByBizIdWithoutRawSql(@Param("bizId") String bizId);

    DmApprovalDO queryById(@Param("id") Long id);

    DmApprovalDO queryByApproIdentity(@Param("approIdentity") String approIdentity, @Param("type") String type, @Param("puid") String puid);

    int updateModified(Long id);

    List<Long> listUnFinishTicketIdList();

    List<Long> listFinishedTicketIdList(@Param("approBiz") ApprovalBiz approBiz);

    DmApprovalDO selectByIdForUpdate(@Param("ticketId") Long ticketId);

    void updateThirdApprovalInfo(@Param("ticketId") Long ticketId, @Param("approvalIdentity") String approvalIdentity, @Param("url") String url);

    void updateComment(@Param("ticketId") Long ticketId, @Param("comment") String comment);

    void updateTicketInfo(@Param("ticketId") Long ticketId, @Param("ticketInfo") String ticketInfo);

    void updateExpectedAffectedRows(@Param("ticketId") Long ticketId, @Param("expectedAffectedRows") Long expectedAffectedRows);

    // ---------- DingTalk bridge outbound (task 10-09-dingtalk-approval-bridge, design §3.1/§4) ----------
    // The claim updates are row-level CAS (conditional on the current outbound_status /
    // retry_count) so concurrent consoles and the compensation sweep can never double-send.
    // None of these statements touches gmt_modified (scheduler lease field).

    /** CAS claim of the FIRST send: NULL/NOT_STARTED → STARTING. affected=0 = lost race / already sent. */
    int claimOutboundFirstSend(@Param("ticketId") Long ticketId);

    /** CAS claim of a sweep retry: FAILED with the expected retry_count → STARTING. */
    int claimOutboundForRetry(@Param("ticketId") Long ticketId, @Param("expectedRetryCount") int expectedRetryCount);

    /** CAS claim of a manual reissue: NULL/NOT_STARTED/FAILED → STARTING with retry_count reset to 0. */
    int claimOutboundForReissue(@Param("ticketId") Long ticketId);

    /** Terminal success write: STARTED + error/next-retry cleared. */
    int markOutboundStarted(@Param("ticketId") Long ticketId);

    /** Failure write: FAILED + cause (truncated) + retry_count + next backoff slot (NULL = no further auto retry). */
    int markOutboundFailed(@Param("ticketId") Long ticketId, @Param("error") String error,
                           @Param("retryCount") int retryCount, @Param("nextRetryAt") Date nextRetryAt);

    /** flowId backfill under the "write only when currently empty" contract (dual-source first-writer-wins). */
    int updateOutboundFlowIdIfEmpty(@Param("ticketId") Long ticketId, @Param("flowId") String flowId);

    /** New approval round (release resubmit, design boundary #15): clear appro_identity + the four outbound columns. */
    int clearOutboundForNewRound(@Param("ticketId") Long ticketId);

    /** Compensation sweep candidates: FAILED ∧ retry_count < maxRetry ∧ next_retry_at due. */
    List<Long> listOutboundRetryDue(@Param("maxRetry") int maxRetry, @Param("limit") int limit);

}
