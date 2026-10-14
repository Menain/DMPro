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
package com.clougence.rdp.service;

import java.util.Date;
import java.util.List;

import com.clougence.clouddm.console.web.model.fo.ExportOpAuditFO;
import com.clougence.clouddm.console.web.model.vo.DmPageVO;
import com.clougence.clouddm.console.web.model.vo.OpAuditConditionVO;
import com.clougence.clouddm.console.web.model.vo.RdpOpAuditVO;
import com.clougence.clouddm.platform.dal.model.ResourceType;
import com.clougence.clouddm.platform.dal.model.monitor.AuditType;
import com.clougence.clouddm.platform.dal.model.monitor.DmMonOpAuditDO;
import com.clougence.clouddm.platform.dal.model.monitor.SecurityLevel;

import jakarta.servlet.http.HttpServletResponse;

/**
 * @author bucketli 2020/4/13 12:53
 */
public interface RdpOpAuditService {

    int DEFAULT_PAGE_SIZE = 20;
    int MAX_PAGE_SIZE     = 60;

    /**
     * add OperationAuditDO data to database.ORIGIN DATA, not referent any other metadata.
     */
    void addOperationAudit(DmMonOpAuditDO auditDO);

    /**
     * C8 M2 (design D4): the trailing four parameters are the additive audit-chain filter
     * dimensions (channel / ticketId / releaseId / sqlFingerprint) — all optional.
     */
    List<RdpOpAuditVO> queryUserAllAudit(String puid, String uid, SecurityLevel securityLevel, String userNameLike, String auditType, String resourceType, Date start, Date end,
                                         long startId, int pageSize, String channel, Long ticketId, Long releaseId, String sqlFingerprint);

    DmPageVO<RdpOpAuditVO> pageUserAllAudit(String puid, String uid, SecurityLevel securityLevel, String userNameLike, String auditType, String resourceType, Date start, Date end,
                                            int pageNumber, int pageSize, String channel, Long ticketId, Long releaseId, String sqlFingerprint);

    /**
     * query audit by userName and basic condition. if startId not specified, fill it with 0. if pageSize not specified, fill it with DEFAULT_PAGE_SIZE.
     *
     * @param userName      not be null
     * @param securityLevel optional
     * @param auditType     optional
     * @param resourceType  optional
     * @param start         optional
     * @param end           optional
     * @param startId       optional
     * @param pageSize      optional
     */
    List<RdpOpAuditVO> findAuditByUserName(String puid, String userName, SecurityLevel securityLevel, String auditType, String resourceType, Date start, Date end, long startId,
                                           int pageSize);

    void logAndAddOperationAudit(String puid, String uid, String requestUri, String remoteAddr, Object resId, Object obj, SecurityLevel securityLevel, AuditType auditType,
                                 ResourceType resType);

    void logAndAddOperationAudit(String puid, String uid, String requestUri, String remoteAddr, Object resId, Object obj, SecurityLevel securityLevel, AuditType auditType,
                                 ResourceType resType, String oldName);

    /**
     * C8 M2 additive overload (design D3, red line 1 — the two overloads above keep their exact
     * signatures for the ~59 existing call sites): the 9-arg shape PLUS the audit-chain relation
     * keys (sql_fingerprint / ticket_id / release_id / channel columns). {@code chain} may be
     * null; a null {@code chain.channel} defers to the sink's single-point URI inference
     * ({@link com.clougence.clouddm.platform.dal.model.monitor.AuditChannel#inferFromUri}).
     * resourceName resolution is unchanged (getResourceName by resType — resId must be the
     * ResourceType's own PK, four-point contract).
     */
    void logAndAddOperationAudit(String puid, String uid, String requestUri, String remoteAddr, Object resId, Object obj, SecurityLevel securityLevel, AuditType auditType,
                                 ResourceType resType, com.clougence.rdp.service.model.AuditChainMO chain);

    /**
     * C8 M2 additive overload: the 10-arg (explicit resourceName — the PURE_URL bypass shape)
     * PLUS the audit-chain relation keys. This is the "11-arg" sink overload of design D3; the
     * sink performs ZERO extra SELECTs for the chain keys (red line 4 — all caller-supplied).
     */
    void logAndAddOperationAudit(String puid, String uid, String requestUri, String remoteAddr, Object resId, Object obj, SecurityLevel securityLevel, AuditType auditType,
                                 ResourceType resType, String oldName, com.clougence.rdp.service.model.AuditChainMO chain);

    OpAuditConditionVO queryListCondition();

    void exportAuditLog(ExportOpAuditFO exportOpAuditFO, HttpServletResponse response);

    /**
     * C8 M3 (裁决 3, design D4): the audit-detail read — one row by id, tenant-isolated by
     * owner_uid (a cross-tenant/missing id reads as not-found), returning the log_info body
     * AFTER the AuditSanitizeUtils sanitization兜底 + the audit-detail-label key translation.
     * Replaces the never-implemented {@code /logview/grep/operationlog} endpoint the UI's
     * "查看" dialog was built against (survey D-2 dead-endpoint defect).
     */
    com.clougence.clouddm.console.web.model.vo.AuditDetailVO queryAuditDetail(String puid, long id);
}
