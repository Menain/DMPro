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

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.service.envparam.DmEnvParamService;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.AuthDal;
import com.clougence.clouddm.platform.dal.access.DbChangeGovernDal;
import com.clougence.clouddm.platform.dal.access.LogicalDbDal;
import com.clougence.clouddm.platform.dal.access.SystemDal;
import com.clougence.clouddm.platform.dal.mapper.approval.DmApprovalMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangePromotionMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangeRevisionMapper;
import com.clougence.clouddm.platform.dal.mapper.logicaldb.DmLogicalDbMapper;
import com.clougence.clouddm.platform.dal.mapper.system.DmSysEnvMapper;
import com.clougence.clouddm.platform.dal.model.approval.ApprovalStatus;
import com.clougence.clouddm.platform.dal.model.approval.DmApprovalDO;
import com.clougence.clouddm.platform.dal.model.auth.DmAuthUserDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangePromotionDO;
import com.clougence.clouddm.platform.dal.model.dbchange.DmDbChangeRevisionDO;
import com.clougence.clouddm.platform.dal.model.dbchange.PromotionType;
import com.clougence.clouddm.platform.dal.model.dbchange.RevisionSourceType;
import com.clougence.clouddm.platform.dal.model.logicaldb.DmLogicalDbDO;
import com.clougence.clouddm.platform.dal.model.system.DmSysEnvDO;
import com.clougence.clouddm.sdk.approval.form.ChangeForm;
import com.clougence.clouddm.sdk.model.env.EnvParamKeys;

/**
 * Phase 9: risk-level consistency between path A (live compute) and path B (gate_result).
 * <p>
 * Both paths use the same {@link GovRowLimitConfig} parsing and the same
 * shouldBlock/shouldWarn threshold logic. This test verifies:
 * <ul>
 * <li>Same rows + same threshold → same level (consistency)</li>
 * <li>Boundary: rows==block → HIGH (not block, per shouldWarn ≤ block)</li>
 * <li>Boundary: rows==warn → NORMAL (per shouldWarn > warn strict)</li>
 * <li>Pure DDL → NORMAL regardless of threshold config</li>
 * <li>Unconfigured GOV_DML_ROW_LIMIT → NORMAL</li>
 * </ul>
 */
public class RiskLevelConsistencyTest {

    private GovChangeFormAssembler assembler;

    private DbChangeGovernDal    dbChangeGovernDal;
    private LogicalDbDal          logicalDbDal;
    private DmEnvParamService     dmEnvParamService;
    private SystemDal             systemDal;
    private AuthDal               authDal;
    private ApprovalDal           approvalDal;

    private DmDbChangePromotionMapper promotionMapper;
    private DmDbChangeRevisionMapper  revisionMapper;
    private DmLogicalDbMapper         logicalDbMapper;
    private DmSysEnvMapper            envMapper;
    private DmApprovalMapper          approvalMapper;

    private static final String PUID = "puid-001";
    private static final String UID  = "uid-001";
    private static final long   TICKET_ID    = 100L;
    private static final long   PROMOTION_ID = 300L;
    private static final long   REVISION_ID  = 200L;
    private static final long   LOGICAL_DB_ID = 10L;
    private static final long   PROD_ENV_ID   = 5L;
    private static final long   SOURCE_TICKET_ID = 77L;

    @Before
    public void setUp() {
        assembler = new GovChangeFormAssembler();
        dbChangeGovernDal = mock(DbChangeGovernDal.class);
        logicalDbDal = mock(LogicalDbDal.class);
        dmEnvParamService = mock(DmEnvParamService.class);
        systemDal = mock(SystemDal.class);
        authDal = mock(AuthDal.class);
        approvalDal = mock(ApprovalDal.class);

        promotionMapper = mock(DmDbChangePromotionMapper.class);
        revisionMapper = mock(DmDbChangeRevisionMapper.class);
        logicalDbMapper = mock(DmLogicalDbMapper.class);
        envMapper = mock(DmSysEnvMapper.class);
        approvalMapper = mock(DmApprovalMapper.class);

        ReflectionTestUtils.setField(assembler, "dbChangeGovernDal", dbChangeGovernDal);
        ReflectionTestUtils.setField(assembler, "logicalDbDal", logicalDbDal);
        ReflectionTestUtils.setField(assembler, "dmEnvParamService", dmEnvParamService);
        ReflectionTestUtils.setField(assembler, "systemDal", systemDal);
        ReflectionTestUtils.setField(assembler, "authDal", authDal);
        ReflectionTestUtils.setField(assembler, "approvalDal", approvalDal);

        when(dbChangeGovernDal.promotionMapper()).thenReturn(promotionMapper);
        when(dbChangeGovernDal.revisionMapper()).thenReturn(revisionMapper);
        when(logicalDbDal.logicalDbMapper()).thenReturn(logicalDbMapper);
        when(systemDal.envMapper()).thenReturn(envMapper);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);
    }

    // ======= Consistency: same rows + same threshold → same level =======

    @Test
    public void pathA_and_pathB_sameRows_sameThreshold_sameLevel() {
        // Threshold: warn=1000, block=100000. Rows=5000 (warn < 5000 ≤ block → HIGH)
        long rows = 5000;
        String threshold = "warn:1000,block:100000";

        // Path A: live compute → HIGH
        String pathAResult = computePathARiskLevel("DML", rows, threshold);
        assertEquals("HIGH", pathAResult);

        // Path B: gate_result stores riskLevel=HIGH
        String gateResult = buildGateResultJson("HIGH", rows, 1000L, 100000L);
        String pathBResult = readRiskLevelFromGateResult(gateResult);
        assertEquals("HIGH", pathBResult);

        assertEquals(pathAResult, pathBResult);
    }

    @Test
    public void pathA_and_pathB_lowRows_sameLevel_NORMAL() {
        long rows = 100;
        String threshold = "warn:1000,block:100000";

        String pathAResult = computePathARiskLevel("DML", rows, threshold);
        assertEquals("NORMAL", pathAResult);

        String gateResult = buildGateResultJson("NORMAL", rows, 1000L, 100000L);
        String pathBResult = readRiskLevelFromGateResult(gateResult);
        assertEquals("NORMAL", pathBResult);

        assertEquals(pathAResult, pathBResult);
    }

    // ======= Boundary tests (same semantics as Phase 8) =======

    @Test
    public void boundary_rowsEqualsBlock_warnNotBlock() {
        // rows == block (100000): shouldWarn checks rows > warn && rows <= block → true → HIGH
        // shouldBlock checks rows > block → false (100000 > 100000 is false)
        // So rows==block → shouldWarn=true → HIGH (consistent with Phase 8 boundary)
        String pathAResult = computePathARiskLevel("DML", 100000, "warn:1000,block:100000");
        assertEquals("HIGH", pathAResult);
    }

    @Test
    public void boundary_rowsEqualsWarn_normalNotWarn() {
        // rows == warn (1000): shouldWarn checks rows > warn → false (1000 > 1000 is false)
        // So rows==warn → NORMAL (strict >, not >=)
        String pathAResult = computePathARiskLevel("DML", 1000, "warn:1000,block:100000");
        assertEquals("NORMAL", pathAResult);
    }

    @Test
    public void boundary_rowsOneAboveWarn_HIGH() {
        String pathAResult = computePathARiskLevel("DML", 1001, "warn:1000,block:100000");
        assertEquals("HIGH", pathAResult);
    }

    // ======= DDL and unconfigured =======

    @Test
    public void pureDDL_alwaysNormal_regardlessOfThreshold() {
        String result = computePathARiskLevel("DDL", 999999, "warn:1000,block:100000");
        assertEquals("NORMAL", result);
    }

    @Test
    public void unconfiguredThreshold_alwaysNormal() {
        String result = computePathARiskLevel("DML", 999999, "");
        assertEquals("NORMAL", result);

        result = computePathARiskLevel("DML", 999999, null);
        assertEquals("NORMAL", result);
    }

    @Test
    public void mixedType_treatedLikeDML() {
        // MIXED should be tiered like DML
        String result = computePathARiskLevel("MIXED", 5000, "warn:1000,block:100000");
        assertEquals("HIGH", result);
    }

    // ======= Full assembler integration: path A vs B end-to-end =======

    @Test
    public void fullAssembly_pathA_riskLevelConsistentWithPathB() {
        long rows = 5000;

        // Path A: live compute → HIGH (warn=1000 < 5000 ≤ block=100000)
        String pathAResult = computePathARiskLevel("DML", rows, "warn:1000,block:100000");
        assertEquals("HIGH", pathAResult);

        // Path B: gate_result stores riskLevel=HIGH (same rows, same threshold)
        String pathBGateResult = buildGateResultJson("HIGH", rows, 1000L, 100000L);
        String pathBResult = readRiskLevelFromGateResult(pathBGateResult);
        assertEquals("HIGH", pathBResult);

        // Consistency: both paths produce the same risk level for the same rows + threshold
        assertEquals(pathAResult, pathBResult);
    }

    // ======= Helpers =======

    private String computePathARiskLevel(String changeType, long rows, String thresholdConfig) {
        reset(dbChangeGovernDal, logicalDbDal, systemDal, authDal, approvalDal, dmEnvParamService);
        when(dbChangeGovernDal.promotionMapper()).thenReturn(promotionMapper);
        when(dbChangeGovernDal.revisionMapper()).thenReturn(revisionMapper);
        when(logicalDbDal.logicalDbMapper()).thenReturn(logicalDbMapper);
        when(systemDal.envMapper()).thenReturn(envMapper);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);

        DmApprovalDO ticket = buildTicket();
        ticket.setExpectedAffectedRows(rows);
        ticket.setRawSql("UPDATE orders SET status=1");

        setupUserMock();
        setupCommonMocks(thresholdConfig);

        DmDbChangePromotionDO promo = buildPromotion(PromotionType.PRE_PROMOTION);
        when(promotionMapper.selectById(PROMOTION_ID)).thenReturn(promo);
        DmDbChangeRevisionDO rev = buildRevision(RevisionSourceType.PRE_TICKET, changeType);
        when(revisionMapper.selectById(REVISION_ID)).thenReturn(rev);

        DmApprovalDO sourceTicket = new DmApprovalDO();
        sourceTicket.setTicketStatus(ApprovalStatus.FINISHED);
        when(approvalMapper.queryById(SOURCE_TICKET_ID)).thenReturn(sourceTicket);

        ApprovalMO info = buildGovMO("PROD");
        ChangeForm form = assembler.build(ticket, info, "PROC-001");
        return extractRiskLevel(form.getTicketDesc());
    }

    private String readRiskLevelFromGateResult(String gateResult) {
        // Use the same parsing logic the assembler uses
        DmApprovalDO ticket = buildTicket();
        ticket.setRawSql("UPDATE orders SET status=1");
        ticket.setExpectedAffectedRows(0L);

        reset(dbChangeGovernDal, logicalDbDal, systemDal, authDal, approvalDal, dmEnvParamService);
        when(dbChangeGovernDal.promotionMapper()).thenReturn(promotionMapper);
        when(dbChangeGovernDal.revisionMapper()).thenReturn(revisionMapper);
        when(logicalDbDal.logicalDbMapper()).thenReturn(logicalDbMapper);
        when(systemDal.envMapper()).thenReturn(envMapper);
        when(approvalDal.approvalMapper()).thenReturn(approvalMapper);

        setupUserMock();
        setupCommonMocks(null);

        DmDbChangePromotionDO promo = buildPromotion(PromotionType.DIRECT_PROD_DML);
        promo.setGateResult(gateResult);
        when(promotionMapper.selectById(PROMOTION_ID)).thenReturn(promo);
        DmDbChangeRevisionDO rev = buildRevision(RevisionSourceType.DIRECT_PROD_DML, "DML");
        when(revisionMapper.selectById(REVISION_ID)).thenReturn(rev);

        ApprovalMO info = buildGovMO("PROD");
        ChangeForm form = assembler.build(ticket, info, "PROC-001");
        return extractRiskLevel(form.getTicketDesc());
    }

    private static String extractRiskLevel(String desc) {
        int idx = desc.indexOf("风险等级: ");
        if (idx < 0) return "NOT_FOUND";
        String substr = desc.substring(idx + "风险等级: ".length());
        int newline = substr.indexOf('\n');
        return newline >= 0 ? substr.substring(0, newline).trim() : substr.trim();
    }

    private DmApprovalDO buildTicket() {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setPrimaryUid(PUID);
        ticket.setOwnerUid(UID);
        ticket.setBizId("BIZ-001");
        return ticket;
    }

    private ApprovalMO buildGovMO(String govRole) {
        ApprovalMO info = new ApprovalMO();
        info.setGovRole(govRole);
        info.setPromotionId(PROMOTION_ID);
        info.setRevisionId(REVISION_ID);
        info.setLogicalDbId(LOGICAL_DB_ID);
        return info;
    }

    private DmDbChangePromotionDO buildPromotion(PromotionType type) {
        DmDbChangePromotionDO promo = new DmDbChangePromotionDO();
        promo.setId(PROMOTION_ID);
        promo.setPromotionCode("PROMO-001");
        promo.setPromotionType(type.name());
        promo.setProdEnvId(PROD_ENV_ID);
        return promo;
    }

    private DmDbChangeRevisionDO buildRevision(RevisionSourceType sourceType, String changeType) {
        DmDbChangeRevisionDO rev = new DmDbChangeRevisionDO();
        rev.setId(REVISION_ID);
        rev.setRevisionCode("REV-001");
        rev.setSourceType(sourceType.name());
        rev.setSourceTicketId(sourceType == RevisionSourceType.PRE_TICKET ? SOURCE_TICKET_ID : TICKET_ID);
        rev.setChangeType(changeType);
        rev.setSqlHash("a1b2c3d4e5f67890");
        String manifest = "[{\"idx\":1,\"stmt_hash\":\"h1\",\"version\":1,\"pre_exec\":\"SUCCESS\"}]";
        rev.setStmtManifest(manifest);
        return rev;
    }

    private void setupCommonMocks(String thresholdConfig) {
        DmLogicalDbDO logicalDb = new DmLogicalDbDO();
        logicalDb.setId(LOGICAL_DB_ID);
        logicalDb.setResourceName("order_db");
        when(logicalDbMapper.selectById(LOGICAL_DB_ID)).thenReturn(logicalDb);

        DmSysEnvDO env = new DmSysEnvDO();
        env.setId(PROD_ENV_ID);
        env.setEnvName("PROD");
        when(envMapper.selectById(PROD_ENV_ID)).thenReturn(env);

        if (thresholdConfig != null) {
            when(dmEnvParamService.queryParam(PUID, PROD_ENV_ID, EnvParamKeys.GOV_DML_ROW_LIMIT))
                .thenReturn(thresholdConfig);
        }
    }

    private void setupUserMock() {
        DmAuthUserDO user = new DmAuthUserDO();
        user.setPhone("13800000001");
        user.setUsername("alice");
        com.clougence.clouddm.platform.dal.mapper.auth.DmAuthUserMapper userMapper =
            mock(com.clougence.clouddm.platform.dal.mapper.auth.DmAuthUserMapper.class);
        when(authDal.userMapper()).thenReturn(userMapper);
        when(userMapper.queryByUid(UID)).thenReturn(user);
    }

    private static String buildGateResultJson(String riskLevel, long estimatedRows, Long warn, Long block) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        sb.append("{\"item\":1,\"label\":\"DML-only\",\"pass\":true,\"reason\":\"changeType=DML\"},");
        sb.append("{\"item\":6,\"label\":\"Threshold\",\"pass\":true,\"reason\":\"");
        sb.append("riskLevel=").append(riskLevel)
          .append(", estimatedRows=").append(estimatedRows)
          .append(", warn=").append(warn)
          .append(", block=").append(block);
        sb.append("\",\"riskLevel\":\"").append(riskLevel).append("\"}");
        sb.append("]");
        return sb.toString();
    }
}
