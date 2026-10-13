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
package com.clougence.clouddm.console.web.component.governance.impl;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.console.web.component.governance.GovRouteOverrideService;
import com.clougence.clouddm.console.web.model.fo.prodrelease.RouteOverrideGrantFO;
import com.clougence.clouddm.console.web.model.vo.prodrelease.RouteOverrideAuditObj;
import com.clougence.clouddm.console.web.model.vo.prodrelease.RouteOverrideVO;
import com.clougence.clouddm.platform.dal.access.DbChangeEventDal;
import com.clougence.clouddm.platform.dal.access.ProdReleaseDal;
import com.clougence.clouddm.platform.dal.model.ResourceType;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeEventDO;
import com.clougence.clouddm.platform.dal.model.dbchange.GovEventType;
import com.clougence.clouddm.platform.dal.model.monitor.AuditType;
import com.clougence.clouddm.platform.dal.model.monitor.SecurityLevel;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmGovRouteOverrideDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseStmtDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseStmtSegmentDO;
import com.clougence.utils.JsonUtils;
import com.clougence.utils.StringUtils;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * Governed route-override implementation (ADR-013 M3, design D8). See the interface contract;
 * the DAL surface is insert/select + CAS consume only — no arbitrary updates, no revoke
 * (a grant lives until consumption or expiry; the design's column list has no revoke face).
 * <p>
 * ISOLATION from the C4 schema-gate override is structural: this service touches ONLY
 * dm_gov_route_override (never gateOverrideMapper) and the C4 service touches only its own
 * table — consuming one can never release a denial of the other (red line, pinned).
 */
@Slf4j
@Service
public class GovRouteOverrideServiceImpl implements GovRouteOverrideService {

    private static final int    REASON_MAX_LEN     = 1024;
    private static final int    BLOCK_REASON_MAX   = 512;
    private static final String STATUS_PENDING     = "PENDING";
    private static final String STATUS_CONSUMED    = "CONSUMED";
    private static final String STATUS_EXPIRED     = "EXPIRED";
    private static final String SYSTEM_OPERATOR    = "SYSTEM";
    /** requestUri marker of the consumption audit row (no HTTP request exists — the release GHOST dispatch marker twin). */
    private static final String CONSUME_AUDIT_URI  = "/system/prodRelease/routeOverride/consume";

    @Resource
    private ProdReleaseDal prodReleaseDal;
    @Resource
    private DbChangeEventDal dbChangeEventDal;
    @Resource
    private com.clougence.rdp.service.RdpOpAuditService rdpOpAuditService;

    @Override
    public long create(String puid, String uid, RouteOverrideGrantFO fo) {
        // Tenant check (mirrors the C4 grant): cross-tenant reads as not-found
        DmProdReleaseDO release = requireTenantRelease(puid, fo.getReleaseId());

        if (fo.getStmtId() == null) {
            throw new ErrorMessageException("Override stmtId is required (granularity is exactly (releaseId, stmtId))");
        }
        DmProdReleaseStmtDO stmt = prodReleaseDal.stmtMapper().queryById(fo.getStmtId());
        if (stmt == null || !release.getId().equals(stmt.getReleaseId())) {
            throw new ErrorMessageException("Release statement not found in release " + release.getId()
                + ": " + fo.getStmtId());
        }
        if (StringUtils.isBlank(fo.getReason())) {
            throw new ErrorMessageException("Override reason is required");
        }
        String reason = fo.getReason().trim();
        if (reason.length() > REASON_MAX_LEN) {
            throw new ErrorMessageException("Override reason exceeds " + REASON_MAX_LEN + " characters");
        }
        Date now = new Date();
        if (fo.getExpireAt() == null) {
            throw new ErrorMessageException("Override expireAt is required");
        }
        Date expireAt = new Date(fo.getExpireAt());
        if (!expireAt.after(now)) {
            throw new ErrorMessageException("Override expireAt must be in the future");
        }
        if (fo.getExpireAt() - now.getTime() > MAX_VALIDITY_MS) {
            throw new ErrorMessageException("Override expireAt exceeds the maximum validity window of 7 days");
        }
        // Active uniqueness (design D8 "每 (release_id,stmt_id) 至多一条 PENDING"): service-enforced —
        // the C4 migration shape has no DB partial-unique constraint (MySQL cannot express one),
        // so a second grant while a consumable row exists is rejected here.
        if (prodReleaseDal.routeOverrideMapper().findConsumable(release.getId(), stmt.getId(), now) != null) {
            throw new ErrorMessageException(
                "An active route override already exists for release statement " + stmt.getId());
        }

        DmGovRouteOverrideDO row = new DmGovRouteOverrideDO();
        row.setReleaseId(release.getId());
        row.setStmtId(stmt.getId());
        row.setReason(reason);
        row.setCreatedBy(uid);
        row.setStatus(STATUS_PENDING);
        row.setExpireAt(expireAt);
        prodReleaseDal.routeOverrideMapper().insert(row);
        log.info("[RouteOverride] granted id={} release={} stmt={} creator={} expireAt={}",
            row.getId(), release.getId(), stmt.getId(), uid, expireAt);
        return row.getId();
    }

    @Override
    public List<RouteOverrideVO> list(String puid, long releaseId) {
        requireTenantRelease(puid, releaseId);
        List<DmGovRouteOverrideDO> rows = prodReleaseDal.routeOverrideMapper().queryByReleaseId(releaseId);
        List<RouteOverrideVO> vos = new ArrayList<>();
        if (rows == null) {
            return vos;
        }
        Date now = new Date();
        for (DmGovRouteOverrideDO row : rows) {
            vos.add(toVO(row, now));
        }
        return vos;
    }

    @Override
    public DmGovRouteOverrideDO findAndConsume(DmProdReleaseDO release, DmProdReleaseStmtDO stmt,
                                               DmProdReleaseStmtSegmentDO seg, String blockCode, String blockReason) {
        Date now = new Date();
        DmGovRouteOverrideDO candidate = prodReleaseDal.routeOverrideMapper()
            .findConsumable(release.getId(), stmt.getId(), now);
        if (candidate == null) {
            return null; // nothing consumable — the block STANDS (fail-closed)
        }
        String coveredSegIdxs = String.valueOf(seg.getSegIdx());
        if (prodReleaseDal.routeOverrideMapper().consumeCas(candidate.getId(), now, coveredSegIdxs) == 0) {
            return null; // CAS lost (concurrent consumption) — single-use strictness, the block stands
        }
        candidate.setStatus(STATUS_CONSUMED);
        candidate.setConsumedAt(now);
        candidate.setCoveredSegIdxs(coveredSegIdxs);

        // Consumption trails: the event rides the SAME transaction (consumption integrity — a
        // rolled-back dispatch un-consumes the override atomically); the HIGH audit never throws
        // (logAndAddOperationAudit swallows internally) and never rolls back the release.
        appendRouteOverrideEvent(release, stmt, seg, blockCode, blockReason, candidate);
        auditConsumption(release, stmt, seg, blockCode, blockReason, candidate);
        return candidate;
    }

    // ==================== helpers ====================

    private DmProdReleaseDO requireTenantRelease(String puid, Long releaseId) {
        if (releaseId == null) {
            throw new ErrorMessageException("Release id is required");
        }
        DmProdReleaseDO release = prodReleaseDal.releaseMapper().queryById(releaseId);
        if (release == null || !puid.equals(release.getPrimaryUid())) {
            throw new ErrorMessageException("Release not found: " + releaseId);
        }
        return release;
    }

    /** RELEASE_ROUTE_OVERRIDE event — disclosure whitelist (NO SQL text, C2 discipline). */
    private void appendRouteOverrideEvent(DmProdReleaseDO release, DmProdReleaseStmtDO stmt,
                                          DmProdReleaseStmtSegmentDO seg, String blockCode, String blockReason,
                                          DmGovRouteOverrideDO row) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("stmtId", stmt.getId());
        data.put("segIdx", seg.getSegIdx());
        data.put("segmentType", seg.getType());
        data.put("blockCode", blockCode);
        data.put("overrideId", row.getId());
        data.put("reason", row.getReason());
        data.put("expireAt", row.getExpireAt());
        data.put("creator", row.getCreatedBy());
        data.put("coveredSegIdxs", row.getCoveredSegIdxs());
        if (StringUtils.isNotBlank(blockReason)) {
            data.put("blockReason", truncate(blockReason, BLOCK_REASON_MAX));
        }
        DmDbChangeEventDO event = new DmDbChangeEventDO();
        event.setReleaseId(release.getId());
        event.setEventType(GovEventType.RELEASE_ROUTE_OVERRIDE.name());
        // operator_uid column is NOT NULL — the dispatch scope has no interactive user; the
        // GRANTOR trail lives in eventData.creator (the C4 gate-override event convention)
        event.setOperatorUid(SYSTEM_OPERATOR);
        event.setEventData(JsonUtils.toJson(data));
        dbChangeEventDal.eventMapper().insert(event);
    }

    /**
     * HIGH op_audit of the consumption in the GRANTOR's name (the risk-acceptance subject — the
     * dispatch runner is SYSTEM; the C4 grant audit's trail shape with resId = releaseId +
     * resourceName "release:&lt;id&gt;" via the 10-arg overload, getResourceName bypassed).
     */
    private void auditConsumption(DmProdReleaseDO release, DmProdReleaseStmtDO stmt,
                                  DmProdReleaseStmtSegmentDO seg, String blockCode, String blockReason,
                                  DmGovRouteOverrideDO row) {
        RouteOverrideAuditObj obj = new RouteOverrideAuditObj();
        obj.setOverrideId(row.getId());
        obj.setReleaseId(release.getId());
        obj.setStmtId(stmt.getId());
        obj.setSegIdx(seg.getSegIdx());
        obj.setSegmentType(seg.getType());
        obj.setBlockCode(blockCode);
        obj.setReason(row.getReason());
        obj.setCreatedBy(row.getCreatedBy());
        obj.setExpireAt(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(row.getExpireAt()));
        obj.setCoveredSegIdxs(row.getCoveredSegIdxs());
        if (StringUtils.isNotBlank(blockReason)) {
            obj.setBlockReason(truncate(blockReason, BLOCK_REASON_MAX));
        }
        rdpOpAuditService.logAndAddOperationAudit(release.getPrimaryUid(), row.getCreatedBy(),
            CONSUME_AUDIT_URI, null, release.getId(), obj,
            SecurityLevel.HIGH, AuditType.GOV_ROUTE_OVERRIDE_CONSUME,
            ResourceType.PURE_URL, "release:" + release.getId());
    }

    private RouteOverrideVO toVO(DmGovRouteOverrideDO row, Date now) {
        RouteOverrideVO vo = new RouteOverrideVO();
        vo.setId(row.getId());
        vo.setReleaseId(row.getReleaseId());
        vo.setStmtId(row.getStmtId());
        vo.setReason(row.getReason());
        vo.setCreatedBy(row.getCreatedBy());
        vo.setExpireAt(row.getExpireAt());
        vo.setConsumedAt(row.getConsumedAt());
        vo.setCoveredSegIdxs(row.getCoveredSegIdxs());
        vo.setGmtCreate(row.getGmtCreate());
        vo.setStatus(deriveStatus(row, now));
        return vo;
    }

    private static String deriveStatus(DmGovRouteOverrideDO row, Date now) {
        if (STATUS_CONSUMED.equals(row.getStatus())) {
            return STATUS_CONSUMED;
        }
        if (row.getExpireAt() != null && !now.before(row.getExpireAt())) {
            return STATUS_EXPIRED;
        }
        return STATUS_PENDING;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }
}
