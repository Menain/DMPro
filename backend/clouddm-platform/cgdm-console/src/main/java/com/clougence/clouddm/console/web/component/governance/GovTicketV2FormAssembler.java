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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.platform.dal.access.AuthDal;
import com.clougence.clouddm.platform.dal.access.DbPairDal;
import com.clougence.clouddm.platform.dal.access.TicketDbStmtDal;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.auth.DmAuthUserDO;
import com.clougence.clouddm.platform.dal.model.dbpair.DmDbServiceDO;
import com.clougence.clouddm.platform.dal.model.govticket.DmTicketDbStmtDO;
import com.clougence.clouddm.sdk.approval.form.ChangeForm;
import com.clougence.utils.StringUtils;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * Assembles a {@link ChangeForm} for v2 governance tickets (PRE_DDL / PROD_DML).
 * <p>
 * Used by {@link com.clougence.clouddm.console.web.component.approval.handler.ChangeApprovalHandler#convertToChangeForm}
 * when ticketInfo.ticketType is set (v2 branch). Maps the v2 group data onto ChangeForm fields:
 * <ul>
 * <li>ticketTitle ← ticket title (or bizId fallback)</li>
 * <li>targetDs ← DB name list (from groups, comma-separated, truncated 400)</li>
 * <li>executeSql ← per-group SQL summary (truncated 4000, with deep-link tail)</li>
 * <li>ticketDesc ← multiline: ticket type text / service name / DB count / deep link</li>
 * <li>flowName / changeName / branch = null (no CI/CD flow)</li>
 * <li>ticketUserPhone ← ticket owner phone (for DingTalk originator resolution)</li>
 * </ul>
 * <p>
 * Null-safe: missing groups or serviceId produces a degraded form, not an exception.
 */
@Slf4j
@Service
public class GovTicketV2FormAssembler {

    private static final int TITLE_MAX       = 400;
    private static final int TARGET_DS_MAX    = 400;
    private static final int EXECUTE_SQL_MAX  = 4000;
    // Reserve for the deep-link suffix appended when SQL is truncated.
    // DingApiUtils.safeLength truncates at 4000 (DingApiUtils.java:36,40); the assembler must
    // leave room for the suffix so DingApiUtils does not cut it off.
    private static final int DEEP_LINK_RESERVE = 60;

    @Resource
    private TicketDbStmtDal   ticketDbStmtDal;
    @Resource
    private AuthDal           authDal;
    @Resource
    private DbPairDal         dbPairDal;

    public ChangeForm build(DmApprovalDO ticketDO, ApprovalMO info, String templateId) {
        ChangeForm form = new ChangeForm();
        form.setTemplateIdentity(templateId);

        // ticketUserPhone: ticket owner → AuthDal → phone
        DmAuthUserDO userDO = authDal.userMapper().queryByUid(ticketDO.getOwnerUid());
        form.setTicketUserPhone(userDO != null ? userDO.getPhone() : null);

        // Load v2 groups for this ticket
        List<DmTicketDbStmtDO> groups = ticketDbStmtDal.stmtMapper().queryByTicketId(ticketDO.getId());
        if (groups == null) {
            groups = new ArrayList<>();
        }

        // Field: ticketTitle = ticket title (or bizId fallback)
        form.setTicketTitle(resolveTicketTitle(ticketDO));

        // Field: targetDs = DB name list (unique, comma-separated)
        form.setTargetDs(buildTargetDs(groups));

        // Field: executeSql = per-group SQL summary (truncated 4000)
        form.setExecuteSql(buildExecuteSql(groups, ticketDO.getId()));

        // Field: ticketDesc = multiline metadata
        form.setTicketDesc(buildTicketDesc(info, userDO, groups, ticketDO.getId()));

        // flowName / changeName / branch = null (no CI/CD flow)
        return form;
    }

    // ======= Field: ticketTitle =======

    private String resolveTicketTitle(DmApprovalDO ticketDO) {
        if (StringUtils.isNotBlank(ticketDO.getTicketTitle())) {
            return truncate(ticketDO.getTicketTitle(), TITLE_MAX);
        }
        return truncate(ticketDO.getBizId(), TITLE_MAX);
    }

    // ======= Field: targetDs =======

    private String buildTargetDs(List<DmTicketDbStmtDO> groups) {
        Set<String> dbNames = new LinkedHashSet<>();
        for (DmTicketDbStmtDO g : groups) {
            if (StringUtils.isNotBlank(g.getDbName())) {
                dbNames.add(g.getDbName());
            }
        }
        if (dbNames.isEmpty()) {
            return "-";
        }
        return truncate(String.join(", ", dbNames), TARGET_DS_MAX);
    }

    // ======= Field: executeSql =======
    // ASSEMBLER FIX: DingApiUtils.safeLength (DingApiUtils.java:36) truncates at 4000; if
    // assembler output exceeds 4000 the deep-link suffix is cut off. SQL is truncated at
    // (EXECUTE_SQL_MAX - DEEP_LINK_RESERVE) so the suffix fits within the 4000 limit.
    private String buildExecuteSql(List<DmTicketDbStmtDO> groups, long ticketId) {
        if (groups.isEmpty()) {
            return "";
        }
        String deepLinkSuffix = "\n\n（完整 SQL 见平台 /ticket/" + ticketId + "）";
        int sqlLimit = EXECUTE_SQL_MAX - DEEP_LINK_RESERVE;
        StringBuilder sb = new StringBuilder();
        for (DmTicketDbStmtDO g : groups) {
            if (sb.length() > 0) {
                sb.append("\n---\n");
            }
            sb.append("[").append(g.getDbName() != null ? g.getDbName() : "-").append("] ");
            String sql = g.getSqlContent();
            if (sql == null) {
                sql = "";
            }
            if (sb.length() + sql.length() > sqlLimit) {
                int remaining = sqlLimit - sb.length();
                if (remaining > 0) {
                    sb.append(sql, 0, remaining);
                }
                break;
            }
            sb.append(sql);
        }
        if (sb.length() >= sqlLimit) {
            sb = new StringBuilder(sb.substring(0, Math.min(sb.length(), sqlLimit)));
            sb.append(deepLinkSuffix);
        }
        return sb.toString();
    }

    // ======= Field: ticketDesc =======

    private String buildTicketDesc(ApprovalMO info, DmAuthUserDO userDO,
                                    List<DmTicketDbStmtDO> groups, long ticketId) {
        StringBuilder sb = new StringBuilder();
        if (userDO != null && StringUtils.isNotBlank(userDO.getUsername())) {
            sb.append("申请人: ").append(userDO.getUsername()).append("\n");
        }
        // Ticket type text
        String ticketType = info != null ? info.getTicketType() : null;
        if (TICKET_TYPE_PRE_DDL.equals(ticketType)) {
            sb.append("工单类型: 预发 DDL\n");
        } else if (TICKET_TYPE_PROD_DML.equals(ticketType)) {
            sb.append("工单类型: 生产 DML\n");
        } else if (ticketType != null) {
            sb.append("工单类型: ").append(ticketType).append("\n");
        }
        // Service name
        String serviceName = resolveServiceName(info != null ? info.getServiceId() : null);
        if (StringUtils.isNotBlank(serviceName)) {
            sb.append("服务: ").append(serviceName).append("\n");
        }
        // DB count
        Set<String> dbNames = new LinkedHashSet<>();
        for (DmTicketDbStmtDO g : groups) {
            if (StringUtils.isNotBlank(g.getDbName())) {
                dbNames.add(g.getDbName());
            }
        }
        sb.append("涉及库数: ").append(dbNames.size()).append("\n");
        sb.append("完整 SQL 见: /ticket/").append(ticketId);
        return sb.toString();
    }

    // ======= Helpers =======

    private String resolveServiceName(Long serviceId) {
        if (serviceId == null) {
            return null;
        }
        DmDbServiceDO svc = dbPairDal.serviceMapper().selectById(serviceId);
        return svc != null ? svc.getServiceName() : null;
    }

    private static final String TICKET_TYPE_PRE_DDL  = "PRE_DDL";
    private static final String TICKET_TYPE_PROD_DML = "PROD_DML";

    private static String truncate(String str, int max) {
        if (str == null || str.length() <= max) {
            return str != null ? str : "";
        }
        return str.substring(0, max) + " ...";
    }
}
