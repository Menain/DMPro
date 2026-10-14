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

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Service;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.console.web.component.governance.SchemaGateOverrideService;
import com.clougence.clouddm.console.web.model.fo.prodrelease.SchemaGateOverrideGrantFO;
import com.clougence.clouddm.console.web.model.vo.prodrelease.SchemaGateOverrideVO;
import com.clougence.clouddm.platform.dal.access.ProdReleaseDal;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmGovSchemaGateOverrideDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseDO;
import com.clougence.clouddm.platform.dal.model.prodrelease.DmProdReleaseStmtDO;
import com.clougence.utils.StringUtils;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * Governed schema-gate override implementation (ADR-011). See the interface contract;
 * the DAL surface is insert/select + CAS consume/revoke only — no arbitrary updates.
 */
@Slf4j
@Service
public class SchemaGateOverrideServiceImpl implements SchemaGateOverrideService {

    private static final int REASON_MAX_LEN   = 1024;
    private static final int DETAIL_MAX_LEN   = 512;

    @Resource
    private ProdReleaseDal prodReleaseDal;

    @Override
    public long grant(String puid, String uid, SchemaGateOverrideGrantFO fo) {
        // Tenant check (mirrors retryReleaseStmt): cross-tenant reads as not-found
        DmProdReleaseDO release = requireTenantRelease(puid, fo.getReleaseId());

        String gateType = fo.getGateType() == null ? null : fo.getGateType().trim().toUpperCase();
        if (!GATE_PRECHECK.equals(gateType) && !GATE_DIFF.equals(gateType)) {
            throw new ErrorMessageException("Invalid gateType: " + fo.getGateType() + " (expected PRECHECK or DIFF)");
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
        if (fo.getStmtId() != null) {
            DmProdReleaseStmtDO stmt = prodReleaseDal.stmtMapper().queryById(fo.getStmtId());
            if (stmt == null || !release.getId().equals(stmt.getReleaseId())) {
                throw new ErrorMessageException("Release statement not found in release " + release.getId()
                    + ": " + fo.getStmtId());
            }
        }

        // C8 M5.3 (V202610130004): converge the time-expired ACTIVE row(s) of exactly this
        // grant's granularity (gate_type + stmt/release level) via the materialized expired mark
        // — they are not consumable (findConsumable* filters expire_at > now) but their generated
        // active_key would otherwise block the uk_gate_override_active insert.
        prodReleaseDal.gateOverrideMapper().expireStaleActive(release.getId(), fo.getStmtId(), gateType, now);

        DmGovSchemaGateOverrideDO row = new DmGovSchemaGateOverrideDO();
        row.setReleaseId(release.getId());
        row.setStmtId(fo.getStmtId());
        row.setGateType(gateType);
        row.setOperatorUid(uid);
        row.setReason(reason);
        row.setExpireAt(expireAt);
        row.setConsumed(0);
        row.setRevoked(0);
        row.setExpired(0);
        try {
            prodReleaseDal.gateOverrideMapper().insert(row);
        } catch (org.springframework.dao.DuplicateKeyException dup) {
            // C8 M5.3: the DB constraint is now the active-uniqueness authority for this table
            // (pre-C8 multiple actives were tolerated and findAndConsume picked the newest) —
            // a second active grant of the same granularity is rejected with a friendly copy.
            throw new ErrorMessageException("An active schema-gate override already exists for release "
                + release.getId() + (fo.getStmtId() == null ? "" : " stmt " + fo.getStmtId())
                + " gate " + gateType);
        }
        log.info("[SchemaGateOverride] granted id={} release={} stmt={} gate={} operator={} expireAt={}",
            row.getId(), release.getId(), fo.getStmtId(), gateType, uid, expireAt);
        return row.getId();
    }

    @Override
    public List<SchemaGateOverrideVO> list(String puid, long releaseId) {
        requireTenantRelease(puid, releaseId);
        List<DmGovSchemaGateOverrideDO> rows = prodReleaseDal.gateOverrideMapper().queryByReleaseId(releaseId);
        List<SchemaGateOverrideVO> vos = new ArrayList<>();
        if (rows == null) {
            return vos;
        }
        Date now = new Date();
        for (DmGovSchemaGateOverrideDO row : rows) {
            vos.add(toVO(row, now));
        }
        return vos;
    }

    @Override
    public long revoke(String puid, String uid, long overrideId) {
        DmGovSchemaGateOverrideDO row = prodReleaseDal.gateOverrideMapper().selectById(overrideId);
        if (row == null) {
            throw new ErrorMessageException("Schema gate override not found: " + overrideId);
        }
        requireTenantRelease(puid, row.getReleaseId());
        int updated = prodReleaseDal.gateOverrideMapper().revokeCas(overrideId, new Date(), uid);
        if (updated == 0) {
            throw new ErrorMessageException(
                "Schema gate override is already consumed or revoked and cannot be revoked: " + overrideId);
        }
        log.info("[SchemaGateOverride] revoked id={} release={} by={}", overrideId, row.getReleaseId(), uid);
        // C8 M2: hand the release binding back to the audit caller (the row was loaded anyway)
        return row.getReleaseId();
    }

    @Override
    public DmGovSchemaGateOverrideDO findAndConsume(long releaseId, Long stmtId, String gateType, String consumedDetail) {
        Date now = new Date();
        String detail = truncate(consumedDetail, DETAIL_MAX_LEN);

        // Stmt-level exact match first (precise grant wins over the release-level blanket)
        if (stmtId != null) {
            DmGovSchemaGateOverrideDO candidate = prodReleaseDal.gateOverrideMapper()
                .findConsumableStmtLevel(releaseId, stmtId, gateType, now);
            if (candidate != null
                && prodReleaseDal.gateOverrideMapper().consumeCas(candidate.getId(), now, detail) > 0) {
                candidate.setConsumed(1);
                candidate.setConsumedAt(now);
                candidate.setConsumedDetail(detail);
                return candidate;
            }
        }
        DmGovSchemaGateOverrideDO candidate = prodReleaseDal.gateOverrideMapper()
            .findConsumableReleaseLevel(releaseId, gateType, now);
        if (candidate != null
            && prodReleaseDal.gateOverrideMapper().consumeCas(candidate.getId(), now, detail) > 0) {
            candidate.setConsumed(1);
            candidate.setConsumedAt(now);
            candidate.setConsumedDetail(detail);
            return candidate;
        }
        // Nothing consumable (none granted / expired / consumed / revoked / CAS race lost) —
        // the caller DENIES (fail-closed: an unconsumed ruling re-freezes, DML_BATCH_RESOLUTION
        // strictness aligned).
        return null;
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

    private SchemaGateOverrideVO toVO(DmGovSchemaGateOverrideDO row, Date now) {
        SchemaGateOverrideVO vo = new SchemaGateOverrideVO();
        vo.setId(row.getId());
        vo.setReleaseId(row.getReleaseId());
        vo.setStmtId(row.getStmtId());
        vo.setGateType(row.getGateType());
        vo.setOperatorUid(row.getOperatorUid());
        vo.setReason(row.getReason());
        vo.setExpireAt(row.getExpireAt());
        vo.setConsumedAt(row.getConsumedAt());
        vo.setConsumedDetail(row.getConsumedDetail());
        vo.setRevokedAt(row.getRevokedAt());
        vo.setRevokedBy(row.getRevokedBy());
        vo.setGmtCreate(row.getGmtCreate());
        vo.setStatus(deriveStatus(row, now));
        return vo;
    }

    private static String deriveStatus(DmGovSchemaGateOverrideDO row, Date now) {
        if (row.getConsumed() == 1) {
            return "CONSUMED";
        }
        if (row.getRevoked() == 1) {
            return "REVOKED";
        }
        // C8 M5.3: the materialized expired mark (set by the grant-time convergence) renders the
        // same EXPIRED the clock derivation always did — a converged row has expire_at ≤ now by
        // construction, so the two arms agree; the flag arm keeps the display correct even if the
        // clock and the convergence ever disagree.
        if (row.getExpired() == 1) {
            return "EXPIRED";
        }
        if (row.getExpireAt() != null && !now.before(row.getExpireAt())) {
            return "EXPIRED";
        }
        return "ACTIVE";
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }
}
