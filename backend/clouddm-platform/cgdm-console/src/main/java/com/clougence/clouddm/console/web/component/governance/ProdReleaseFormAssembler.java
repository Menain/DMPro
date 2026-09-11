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
import com.clougence.clouddm.platform.dal.access.ProdReleaseDal;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.auth.DmAuthUserDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseStmtDO;
import com.clougence.clouddm.sdk.approval.form.ChangeForm;
import com.clougence.utils.StringUtils;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * Assembles a {@link ChangeForm} for production release tickets (P4 touchpoint).
 * <p>
 * Maps release + stmt snapshot data onto the existing ChangeForm fields consumed by
 * DingApiUtils.getChangeFormParam:
 * <ul>
 * <li>ticketTitle ← releaseNo + " " + title (truncated 400)</li>
 * <li>targetDs ← prod DB name list (unique, comma-separated, truncated 400)</li>
 * <li>executeSql ← per-stmt SQL summary (truncated 4000, with deep-link tail)</li>
 * <li>ticketDesc ← multiline: applicant / total stmt count / prod DB count / deep link</li>
 * <li>flowName / changeName / branch = null (no CI/CD flow)</li>
 * <li>ticketUserPhone ← release creator phone (for DingTalk originator resolution)</li>
 * </ul>
 */
@Slf4j
@Service
public class ProdReleaseFormAssembler {

    private static final int TITLE_MAX       = 400;
    private static final int TARGET_DS_MAX    = 400;
    private static final int EXECUTE_SQL_MAX  = 4000;
    // Reserve for the deep-link suffix appended when SQL is truncated.
    // DingApiUtils.safeLength truncates at 4000 (DingApiUtils.java:36,40); the assembler must
    // leave room for the suffix so DingApiUtils does not cut it off.
    private static final int DEEP_LINK_RESERVE = 60;

    @Resource
    private ProdReleaseDal   prodReleaseDal;
    @Resource
    private AuthDal          authDal;

    public ChangeForm build(DmApprovalDO ticketDO, ApprovalMO info, String templateId) {
        Long releaseId = info != null ? info.getReleaseId() : null;
        // Fail-safe: if releaseId missing, fall back to ticket title (degraded form, not exception)
        if (releaseId == null) {
            return buildDegradedForm(ticketDO, templateId);
        }

        DmProdReleaseDO release = prodReleaseDal.releaseMapper().queryById(releaseId);
        if (release == null) {
            return buildDegradedForm(ticketDO, templateId);
        }

        List<DmProdReleaseStmtDO> stmts = prodReleaseDal.stmtMapper().queryByReleaseId(releaseId);
        if (stmts == null) {
            stmts = new ArrayList<>();
        }

        ChangeForm form = new ChangeForm();
        form.setTemplateIdentity(templateId);

        // ticketUserPhone: release creator → AuthDal → phone
        DmAuthUserDO userDO = authDal.userMapper().queryByUid(release.getCreatorUid());
        form.setTicketUserPhone(userDO != null ? userDO.getPhone() : null);

        // Field: ticketTitle = releaseNo + " " + title
        form.setTicketTitle(buildTicketTitle(release));

        // Field: targetDs = prod DB name list (unique, comma-separated)
        form.setTargetDs(buildTargetDs(stmts));

        // Field: executeSql = SQL summary (per-stmt, truncated 4000)
        form.setExecuteSql(buildExecuteSql(stmts, ticketDO.getId()));

        // Field: ticketDesc = multiline metadata
        form.setTicketDesc(buildTicketDesc(userDO, stmts, release, ticketDO.getId()));

        // flowName / changeName / branch = null (no CI/CD flow)
        return form;
    }

    // ======= Field: ticketTitle =======

    private String buildTicketTitle(DmProdReleaseDO release) {
        String title = (release.getReleaseNo() != null ? release.getReleaseNo() : "")
            + (StringUtils.isNotBlank(release.getTitle()) ? " " + release.getTitle() : "");
        return truncate(title, TITLE_MAX);
    }

    // ======= Field: targetDs =======

    private String buildTargetDs(List<DmProdReleaseStmtDO> stmts) {
        Set<String> dbNames = new LinkedHashSet<>();
        for (DmProdReleaseStmtDO s : stmts) {
            if (StringUtils.isNotBlank(s.getProdDbName())) {
                dbNames.add(s.getProdDbName());
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
    private String buildExecuteSql(List<DmProdReleaseStmtDO> stmts, long ticketId) {
        if (stmts.isEmpty()) {
            return "";
        }
        String deepLinkSuffix = "\n\n（完整 SQL 见平台 /ticket/" + ticketId + "）";
        int sqlLimit = EXECUTE_SQL_MAX - DEEP_LINK_RESERVE;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < stmts.size(); i++) {
            DmProdReleaseStmtDO s = stmts.get(i);
            if (sb.length() > 0) {
                sb.append("\n---\n");
            }
            sb.append("[").append(s.getProdDbName()).append("] ");
            String sql = s.getSqlContent();
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

    private String buildTicketDesc(DmAuthUserDO userDO, List<DmProdReleaseStmtDO> stmts,
                                   DmProdReleaseDO release, long ticketId) {
        StringBuilder sb = new StringBuilder();
        if (userDO != null && StringUtils.isNotBlank(userDO.getUsername())) {
            sb.append("申请人: ").append(userDO.getUsername()).append("\n");
        }
        sb.append("发布单号: ").append(release.getReleaseNo() != null ? release.getReleaseNo() : "-").append("\n");

        // prod DB count
        Set<String> dbNames = new LinkedHashSet<>();
        for (DmProdReleaseStmtDO s : stmts) {
            if (StringUtils.isNotBlank(s.getProdDbName())) {
                dbNames.add(s.getProdDbName());
            }
        }
        sb.append("目标生产库: ").append(dbNames.size()).append(" 个\n");
        sb.append("语句总数: ").append(stmts.size()).append("\n");
        sb.append("完整 SQL 见: /ticket/").append(ticketId);
        return sb.toString();
    }

    // ======= Degraded form (missing release data) =======

    private ChangeForm buildDegradedForm(DmApprovalDO ticketDO, String templateId) {
        ChangeForm form = new ChangeForm();
        form.setTemplateIdentity(templateId);
        form.setTicketTitle(ticketDO.getTicketTitle());
        form.setTargetDs("-");
        form.setExecuteSql(StringUtils.isNotBlank(ticketDO.getRawSql()) ? ticketDO.getRawSql() : "");
        form.setTicketDesc("发布单数据缺失，详情见平台 /ticket/" + ticketDO.getId());
        DmAuthUserDO userDO = authDal.userMapper().queryByUid(ticketDO.getOwnerUid());
        form.setTicketUserPhone(userDO != null ? userDO.getPhone() : null);
        return form;
    }

    private static String truncate(String str, int max) {
        if (str == null || str.length() <= max) {
            return str != null ? str : "";
        }
        return str.substring(0, max) + " ...";
    }
}
