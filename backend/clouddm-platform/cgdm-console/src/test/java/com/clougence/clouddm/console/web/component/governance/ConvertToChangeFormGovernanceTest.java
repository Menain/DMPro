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

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.console.web.component.approval.model.ApprovalMO;
import com.clougence.clouddm.console.web.service.envparam.DmEnvParamService;
import com.clougence.clouddm.platform.dal.access.ApprovalDal;
import com.clougence.clouddm.platform.dal.access.AuthDal;
import com.clougence.clouddm.platform.dal.access.ChangeFlowDal;
import com.clougence.clouddm.platform.dal.access.DbChangeGovernDal;
import com.clougence.clouddm.platform.dal.access.LogicalDbDal;
import com.clougence.clouddm.platform.dal.access.SystemDal;
import com.clougence.clouddm.platform.dal.mapper.cicd.DmChangeFlowMapper;
import com.clougence.clouddm.platform.dal.mapper.cicd.DmChangeMapper;
import com.clougence.clouddm.platform.dal.model.cicd.DmChangeDO;
import com.clougence.clouddm.platform.dal.model.cicd.DmChangeFlowDO;
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
 * Tests for {@link GovChangeFormAssembler} (Phase 9 touchpoint #7).
 * <p>
 * Covers: nine-field governance assembly, null-safe D4 boundaries, risk-level
 * dual-path (D3), SQL truncation, PRE exec result (D5), and CI/CD pass-through.
 */
public class ConvertToChangeFormGovernanceTest {

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

    // ======= Path A: full nine-field assembly =======

    @Test
    public void pathA_governanceProdTicket_nineFieldsAsserted() {
        DmApprovalDO ticket = buildGovernanceTicket("PROD", PROMOTION_ID, REVISION_ID, LOGICAL_DB_ID);
        ticket.setRawSql("CREATE TABLE orders (id INT);");
        ticket.setExpectedAffectedRows(null); // pure DDL → no explain
        ticket.setOwnerUid(UID);

        DmAuthUserDO user = new DmAuthUserDO();
        user.setPhone("13800000001");
        user.setUsername("alice");
        when(authDal.userMapper()).thenReturn(mock(com.clougence.clouddm.platform.dal.mapper.auth.DmAuthUserMapper.class));
        when(authDal.userMapper().queryByUid(UID)).thenReturn(user);

        DmDbChangePromotionDO promotion = buildPromotion(PROMOTION_ID, "PROMO-20260908-0001", PromotionType.PRE_PROMOTION, PROD_ENV_ID);
        when(promotionMapper.selectById(PROMOTION_ID)).thenReturn(promotion);

        DmDbChangeRevisionDO revision = buildRevision(REVISION_ID, "REV-20260908-0001",
            RevisionSourceType.PRE_TICKET, SOURCE_TICKET_ID, "DDL", null);
        when(revisionMapper.selectById(REVISION_ID)).thenReturn(revision);

        DmLogicalDbDO logicalDb = buildLogicalDb(LOGICAL_DB_ID, "order_db");
        when(logicalDbMapper.selectById(LOGICAL_DB_ID)).thenReturn(logicalDb);

        DmSysEnvDO env = buildEnv(PROD_ENV_ID, "PROD");
        when(envMapper.selectById(PROD_ENV_ID)).thenReturn(env);

        DmApprovalDO sourceTicket = new DmApprovalDO();
        sourceTicket.setTicketStatus(ApprovalStatus.FINISHED);
        when(approvalMapper.queryById(SOURCE_TICKET_ID)).thenReturn(sourceTicket);

        ApprovalMO info = buildGovMO("PROD", PROMOTION_ID, REVISION_ID, LOGICAL_DB_ID);

        ChangeForm form = assembler.build(ticket, info, "PROC-001");

        // Field 1: 变更单号
        assertEquals("PROMO-20260908-0001", form.getTicketTitle());
        // Field 2+3: logical_db @ env
        assertEquals("order_db @ PROD", form.getTargetDs());
        // Field 4: applicant in ticketDesc
        assertTrue(form.getTicketDesc().contains("申请人: alice"));
        // Field 5: risk level (pure DDL → NORMAL)
        assertTrue(form.getTicketDesc().contains("风险等级: NORMAL"));
        // Field 6: expected rows null for DDL → not in desc
        assertFalse(form.getTicketDesc().contains("预估影响行数"));
        // Field 7: sql_hash
        assertTrue(form.getTicketDesc().contains("SQL Hash: " + revision.getSqlHash()));
        // Field 8: PRE result (path A, source FINISHED, manifest)
        assertTrue(form.getTicketDesc().contains("PRE 执行结果: 2/2 SUCCESS"));
        // Field 9: SQL summary (short SQL → full text)
        assertEquals("CREATE TABLE orders (id INT);", form.getExecuteSql());
        // Phone + template
        assertEquals("13800000001", form.getTicketUserPhone());
        assertEquals("PROC-001", form.getTemplateIdentity());
        // CI/CD fields null
        assertNull(form.getFlowName());
        assertNull(form.getChangeName());
        assertNull(form.getBranch());
        // Deep link
        assertTrue(form.getTicketDesc().contains("/ticket/" + TICKET_ID));
    }

    // ======= Path B: risk level from gate_result =======

    @Test
    public void pathB_riskLevel_readFromGateResult_HIGH() {
        DmApprovalDO ticket = buildGovernanceTicket("PROD", PROMOTION_ID, REVISION_ID, LOGICAL_DB_ID);
        ticket.setRawSql("UPDATE orders SET status=1");
        ticket.setExpectedAffectedRows(5000L);

        setupUser();
        DmDbChangePromotionDO promotion = buildPromotion(PROMOTION_ID, "PROMO-B-001", PromotionType.DIRECT_PROD_DML, PROD_ENV_ID);
        String gateResult = buildPathBGateResultJson("HIGH", 5000, 1000L, 100000L);
        promotion.setGateResult(gateResult);
        when(promotionMapper.selectById(PROMOTION_ID)).thenReturn(promotion);

        DmDbChangeRevisionDO revision = buildRevision(REVISION_ID, "REV-B-001",
            RevisionSourceType.DIRECT_PROD_DML, TICKET_ID, "DML", "abc123hash");
        revision.setSourceTicketId(TICKET_ID); // path B: source_ticket_id = self
        when(revisionMapper.selectById(REVISION_ID)).thenReturn(revision);

        DmLogicalDbDO logicalDb = buildLogicalDb(LOGICAL_DB_ID, "order_db");
        when(logicalDbMapper.selectById(LOGICAL_DB_ID)).thenReturn(logicalDb);
        DmSysEnvDO env = buildEnv(PROD_ENV_ID, "PROD");
        when(envMapper.selectById(PROD_ENV_ID)).thenReturn(env);

        ApprovalMO info = buildGovMO("PROD", PROMOTION_ID, REVISION_ID, LOGICAL_DB_ID);

        ChangeForm form = assembler.build(ticket, info, "PROC-001");

        assertTrue(form.getTicketDesc().contains("风险等级: HIGH"));
        assertTrue(form.getTicketDesc().contains("预估影响行数: 5000"));
        assertTrue(form.getTicketDesc().contains("PRE 执行结果: 直发（无 PRE）"));
    }

    @Test
    public void pathB_riskLevel_readFromGateResult_NORMAL() {
        DmApprovalDO ticket = buildGovernanceTicket("PROD", PROMOTION_ID, REVISION_ID, LOGICAL_DB_ID);
        ticket.setRawSql("UPDATE orders SET status=1");
        ticket.setExpectedAffectedRows(100L);

        setupUser();
        DmDbChangePromotionDO promotion = buildPromotion(PROMOTION_ID, "PROMO-B-002", PromotionType.DIRECT_PROD_DML, PROD_ENV_ID);
        promotion.setGateResult(buildPathBGateResultJson("NORMAL", 100, 1000L, 100000L));
        when(promotionMapper.selectById(PROMOTION_ID)).thenReturn(promotion);

        DmDbChangeRevisionDO revision = buildRevision(REVISION_ID, "REV-B-002",
            RevisionSourceType.DIRECT_PROD_DML, TICKET_ID, "DML", "abc123hash");
        when(revisionMapper.selectById(REVISION_ID)).thenReturn(revision);

        when(logicalDbMapper.selectById(LOGICAL_DB_ID)).thenReturn(buildLogicalDb(LOGICAL_DB_ID, "order_db"));
        when(envMapper.selectById(PROD_ENV_ID)).thenReturn(buildEnv(PROD_ENV_ID, "PROD"));

        ApprovalMO info = buildGovMO("PROD", PROMOTION_ID, REVISION_ID, LOGICAL_DB_ID);
        ChangeForm form = assembler.build(ticket, info, "PROC-001");

        assertTrue(form.getTicketDesc().contains("风险等级: NORMAL"));
    }

    @Test
    public void pathB_riskLevel_blankGateResult_degradesToNormal() {
        DmApprovalDO ticket = buildGovernanceTicket("PROD", PROMOTION_ID, REVISION_ID, LOGICAL_DB_ID);
        ticket.setRawSql("UPDATE orders SET status=1");
        ticket.setExpectedAffectedRows(100L);

        setupUser();
        DmDbChangePromotionDO promotion = buildPromotion(PROMOTION_ID, "PROMO-B-003", PromotionType.DIRECT_PROD_DML, PROD_ENV_ID);
        promotion.setGateResult(null); // blank gate_result
        when(promotionMapper.selectById(PROMOTION_ID)).thenReturn(promotion);

        DmDbChangeRevisionDO revision = buildRevision(REVISION_ID, "REV-B-003",
            RevisionSourceType.DIRECT_PROD_DML, TICKET_ID, "DML", "abc123hash");
        when(revisionMapper.selectById(REVISION_ID)).thenReturn(revision);

        when(logicalDbMapper.selectById(LOGICAL_DB_ID)).thenReturn(buildLogicalDb(LOGICAL_DB_ID, "order_db"));
        when(envMapper.selectById(PROD_ENV_ID)).thenReturn(buildEnv(PROD_ENV_ID, "PROD"));

        ApprovalMO info = buildGovMO("PROD", PROMOTION_ID, REVISION_ID, LOGICAL_DB_ID);
        ChangeForm form = assembler.build(ticket, info, "PROC-001");

        assertTrue(form.getTicketDesc().contains("风险等级: NORMAL"));
    }

    @Test
    public void pathB_riskLevel_noThresholdItem_degradesToNormal() {
        DmApprovalDO ticket = buildGovernanceTicket("PROD", PROMOTION_ID, REVISION_ID, LOGICAL_DB_ID);
        ticket.setRawSql("UPDATE orders SET status=1");
        ticket.setExpectedAffectedRows(100L);

        setupUser();
        DmDbChangePromotionDO promotion = buildPromotion(PROMOTION_ID, "PROMO-B-004", PromotionType.DIRECT_PROD_DML, PROD_ENV_ID);
        // gate_result has items but no "Threshold" labeled item
        promotion.setGateResult("[{\"item\":1,\"label\":\"DML-only\",\"pass\":true,\"reason\":\"ok\"}]");
        when(promotionMapper.selectById(PROMOTION_ID)).thenReturn(promotion);

        DmDbChangeRevisionDO revision = buildRevision(REVISION_ID, "REV-B-004",
            RevisionSourceType.DIRECT_PROD_DML, TICKET_ID, "DML", "abc123hash");
        when(revisionMapper.selectById(REVISION_ID)).thenReturn(revision);

        when(logicalDbMapper.selectById(LOGICAL_DB_ID)).thenReturn(buildLogicalDb(LOGICAL_DB_ID, "order_db"));
        when(envMapper.selectById(PROD_ENV_ID)).thenReturn(buildEnv(PROD_ENV_ID, "PROD"));

        ApprovalMO info = buildGovMO("PROD", PROMOTION_ID, REVISION_ID, LOGICAL_DB_ID);
        ChangeForm form = assembler.build(ticket, info, "PROC-001");

        assertTrue(form.getTicketDesc().contains("风险等级: NORMAL"));
    }

    @Test
    public void pathB_riskLevel_thresholdWithoutRiskLevelField_degradesToNormal() {
        DmApprovalDO ticket = buildGovernanceTicket("PROD", PROMOTION_ID, REVISION_ID, LOGICAL_DB_ID);
        ticket.setRawSql("UPDATE orders SET status=1");
        ticket.setExpectedAffectedRows(100L);

        setupUser();
        DmDbChangePromotionDO promotion = buildPromotion(PROMOTION_ID, "PROMO-B-005", PromotionType.DIRECT_PROD_DML, PROD_ENV_ID);
        // Threshold item exists but no structured riskLevel field (backward compat edge)
        promotion.setGateResult("[{\"item\":6,\"label\":\"Threshold\",\"pass\":true,\"reason\":\"riskLevel=HIGH\"}]");
        when(promotionMapper.selectById(PROMOTION_ID)).thenReturn(promotion);

        DmDbChangeRevisionDO revision = buildRevision(REVISION_ID, "REV-B-005",
            RevisionSourceType.DIRECT_PROD_DML, TICKET_ID, "DML", "abc123hash");
        when(revisionMapper.selectById(REVISION_ID)).thenReturn(revision);

        when(logicalDbMapper.selectById(LOGICAL_DB_ID)).thenReturn(buildLogicalDb(LOGICAL_DB_ID, "order_db"));
        when(envMapper.selectById(PROD_ENV_ID)).thenReturn(buildEnv(PROD_ENV_ID, "PROD"));

        ApprovalMO info = buildGovMO("PROD", PROMOTION_ID, REVISION_ID, LOGICAL_DB_ID);
        ChangeForm form = assembler.build(ticket, info, "PROC-001");

        // No structured riskLevel field → degrades to NORMAL (safe default)
        assertTrue(form.getTicketDesc().contains("风险等级: NORMAL"));
    }

    @Test
    public void pathB_riskLevel_malformedGateResult_degradesToNormal() {
        DmApprovalDO ticket = buildGovernanceTicket("PROD", PROMOTION_ID, REVISION_ID, LOGICAL_DB_ID);
        ticket.setRawSql("UPDATE orders SET status=1");
        ticket.setExpectedAffectedRows(100L);

        setupUser();
        DmDbChangePromotionDO promotion = buildPromotion(PROMOTION_ID, "PROMO-B-006", PromotionType.DIRECT_PROD_DML, PROD_ENV_ID);
        promotion.setGateResult("{broken json");
        when(promotionMapper.selectById(PROMOTION_ID)).thenReturn(promotion);

        DmDbChangeRevisionDO revision = buildRevision(REVISION_ID, "REV-B-006",
            RevisionSourceType.DIRECT_PROD_DML, TICKET_ID, "DML", "abc123hash");
        when(revisionMapper.selectById(REVISION_ID)).thenReturn(revision);

        when(logicalDbMapper.selectById(LOGICAL_DB_ID)).thenReturn(buildLogicalDb(LOGICAL_DB_ID, "order_db"));
        when(envMapper.selectById(PROD_ENV_ID)).thenReturn(buildEnv(PROD_ENV_ID, "PROD"));

        ApprovalMO info = buildGovMO("PROD", PROMOTION_ID, REVISION_ID, LOGICAL_DB_ID);
        ChangeForm form = assembler.build(ticket, info, "PROC-001");

        // Malformed JSON → catch → NORMAL (no exception thrown)
        assertTrue(form.getTicketDesc().contains("风险等级: NORMAL"));
    }

    // ======= Path A: risk level live compute =======

    @Test
    public void pathA_riskLevel_DML_warnThreshold_HIGH() {
        DmApprovalDO ticket = buildGovernanceTicket("PROD", PROMOTION_ID, REVISION_ID, LOGICAL_DB_ID);
        ticket.setRawSql("UPDATE orders SET status=1");
        ticket.setExpectedAffectedRows(5000L); // warn < 5000 ≤ block

        setupUser();
        DmDbChangePromotionDO promotion = buildPromotion(PROMOTION_ID, "PROMO-001", PromotionType.PRE_PROMOTION, PROD_ENV_ID);
        when(promotionMapper.selectById(PROMOTION_ID)).thenReturn(promotion);

        DmDbChangeRevisionDO revision = buildRevision(REVISION_ID, "REV-001",
            RevisionSourceType.PRE_TICKET, SOURCE_TICKET_ID, "DML", "hash123");
        when(revisionMapper.selectById(REVISION_ID)).thenReturn(revision);

        when(logicalDbMapper.selectById(LOGICAL_DB_ID)).thenReturn(buildLogicalDb(LOGICAL_DB_ID, "order_db"));
        when(envMapper.selectById(PROD_ENV_ID)).thenReturn(buildEnv(PROD_ENV_ID, "PROD"));
        when(dmEnvParamService.queryParam(PUID, PROD_ENV_ID, EnvParamKeys.GOV_DML_ROW_LIMIT)).thenReturn("warn:1000,block:100000");

        DmApprovalDO sourceTicket = new DmApprovalDO();
        sourceTicket.setTicketStatus(ApprovalStatus.FINISHED);
        when(approvalMapper.queryById(SOURCE_TICKET_ID)).thenReturn(sourceTicket);

        ApprovalMO info = buildGovMO("PROD", PROMOTION_ID, REVISION_ID, LOGICAL_DB_ID);
        ChangeForm form = assembler.build(ticket, info, "PROC-001");

        assertTrue(form.getTicketDesc().contains("风险等级: HIGH"));
    }

    @Test
    public void pathA_riskLevel_DDL_normal() {
        DmApprovalDO ticket = buildGovernanceTicket("PROD", PROMOTION_ID, REVISION_ID, LOGICAL_DB_ID);
        ticket.setRawSql("ALTER TABLE orders ADD COLUMN note TEXT");
        ticket.setExpectedAffectedRows(null);

        setupUser();
        DmDbChangePromotionDO promotion = buildPromotion(PROMOTION_ID, "PROMO-001", PromotionType.PRE_PROMOTION, PROD_ENV_ID);
        when(promotionMapper.selectById(PROMOTION_ID)).thenReturn(promotion);

        DmDbChangeRevisionDO revision = buildRevision(REVISION_ID, "REV-001",
            RevisionSourceType.PRE_TICKET, SOURCE_TICKET_ID, "DDL", "hash123");
        when(revisionMapper.selectById(REVISION_ID)).thenReturn(revision);

        when(logicalDbMapper.selectById(LOGICAL_DB_ID)).thenReturn(buildLogicalDb(LOGICAL_DB_ID, "order_db"));
        when(envMapper.selectById(PROD_ENV_ID)).thenReturn(buildEnv(PROD_ENV_ID, "PROD"));

        DmApprovalDO sourceTicket = new DmApprovalDO();
        sourceTicket.setTicketStatus(ApprovalStatus.FINISHED);
        when(approvalMapper.queryById(SOURCE_TICKET_ID)).thenReturn(sourceTicket);

        ApprovalMO info = buildGovMO("PROD", PROMOTION_ID, REVISION_ID, LOGICAL_DB_ID);
        ChangeForm form = assembler.build(ticket, info, "PROC-001");

        assertTrue(form.getTicketDesc().contains("风险等级: NORMAL"));
        assertFalse(form.getTicketDesc().contains("预估影响行数"));
    }

    @Test
    public void pathA_riskLevel_nullExpectedRows_treatedAsZero_normal() {
        DmApprovalDO ticket = buildGovernanceTicket("PROD", PROMOTION_ID, REVISION_ID, LOGICAL_DB_ID);
        ticket.setRawSql("UPDATE orders SET status=1");
        ticket.setExpectedAffectedRows(null); // null → 0 → ≤warn → NORMAL

        setupUser();
        DmDbChangePromotionDO promotion = buildPromotion(PROMOTION_ID, "PROMO-001", PromotionType.PRE_PROMOTION, PROD_ENV_ID);
        when(promotionMapper.selectById(PROMOTION_ID)).thenReturn(promotion);

        DmDbChangeRevisionDO revision = buildRevision(REVISION_ID, "REV-001",
            RevisionSourceType.PRE_TICKET, SOURCE_TICKET_ID, "DML", "hash123");
        when(revisionMapper.selectById(REVISION_ID)).thenReturn(revision);

        when(logicalDbMapper.selectById(LOGICAL_DB_ID)).thenReturn(buildLogicalDb(LOGICAL_DB_ID, "order_db"));
        when(envMapper.selectById(PROD_ENV_ID)).thenReturn(buildEnv(PROD_ENV_ID, "PROD"));
        when(dmEnvParamService.queryParam(PUID, PROD_ENV_ID, EnvParamKeys.GOV_DML_ROW_LIMIT)).thenReturn("warn:1000,block:100000");

        DmApprovalDO sourceTicket = new DmApprovalDO();
        sourceTicket.setTicketStatus(ApprovalStatus.FINISHED);
        when(approvalMapper.queryById(SOURCE_TICKET_ID)).thenReturn(sourceTicket);

        ApprovalMO info = buildGovMO("PROD", PROMOTION_ID, REVISION_ID, LOGICAL_DB_ID);
        ChangeForm form = assembler.build(ticket, info, "PROC-001");

        assertTrue(form.getTicketDesc().contains("风险等级: NORMAL"));
    }

    // ======= D4: null-safe boundaries =======

    @Test
    public void d4_logicalDbIdNull_throwsFailFast() {
        DmApprovalDO ticket = buildGovernanceTicket("PROD", null, null, null);

        ApprovalMO info = new ApprovalMO();
        info.setGovRole("PROD");
        info.setLogicalDbId(null); // data corruption

        try {
            assembler.build(ticket, info, "PROC-001");
            fail("Expected ErrorMessageException for null logicalDbId");
        } catch (ErrorMessageException e) {
            assertTrue(e.getMessage().contains("logicalDbId"));
        }
    }

    @Test
    public void d4_preMisconfigured_promotionRevisionNull_degradedForm() {
        // PRE ticket misconfigured with external template: promotionId/revisionId null
        DmApprovalDO ticket = buildGovernanceTicket("PRE", null, null, LOGICAL_DB_ID);
        ticket.setRawSql("SELECT 1");
        ticket.setExpectedAffectedRows(null);
        ticket.setOwnerUid(UID);

        setupUser();
        when(logicalDbMapper.selectById(LOGICAL_DB_ID)).thenReturn(buildLogicalDb(LOGICAL_DB_ID, "order_db"));

        ApprovalMO info = new ApprovalMO();
        info.setGovRole("PRE");
        info.setLogicalDbId(LOGICAL_DB_ID);
        info.setPromotionId(null);
        info.setRevisionId(null);

        ChangeForm form = assembler.build(ticket, info, "PROC-001");

        // Degraded form, not exception
        assertNotNull(form);
        assertEquals("SELECT 1", form.getExecuteSql());
        // No env (promotion null) → just resource name
        assertEquals("order_db", form.getTargetDs());
        // Risk level NORMAL, PRE result "-"
        assertTrue(form.getTicketDesc().contains("风险等级: NORMAL"));
        assertTrue(form.getTicketDesc().contains("PRE 执行结果: -"));
        // No sql_hash (revision null)
        assertFalse(form.getTicketDesc().contains("SQL Hash"));
    }

    // ======= SQL truncation =======

    @Test
    public void sqlSummary_shortSql_passesThrough() {
        String shortSql = "CREATE TABLE t (id INT);";
        String result = GovChangeFormAssembler.truncateSqlForForm(shortSql, 1L);
        assertEquals(shortSql, result);
    }

    @Test
    public void sqlSummary_longSql_truncatedWithDeepLink() {
        StringBuilder longSql = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            longSql.append("SELECT col").append(i).append(" FROM big_table; ");
        }
        String result = GovChangeFormAssembler.truncateSqlForForm(longSql.toString(), 42L);
        assertTrue(result.length() > 2000);
        assertTrue(result.length() < 2100);
        assertTrue(result.startsWith(longSql.substring(0, 2000)));
        assertTrue(result.contains("/ticket/42"));
    }

    @Test
    public void sqlSummary_blankSql_returnsEmpty() {
        assertEquals("", GovChangeFormAssembler.truncateSqlForForm(null, 1L));
        assertEquals("", GovChangeFormAssembler.truncateSqlForForm("", 1L));
        assertEquals("", GovChangeFormAssembler.truncateSqlForForm("   ", 1L));
    }

    @Test
    public void sqlSummary_exactlyAtMaxLength_notTruncated() {
        String exact = repeatChar('a', 2000);
        String result = GovChangeFormAssembler.truncateSqlForForm(exact, 1L);
        assertEquals(exact, result);
    }

    // ======= CI/CD pass-through (D8) — exercises handler.convertToChangeForm via reflection =======

    /**
     * D8 hard standard: a valid CI/CD ticketInfo (changeId/changeOwnerUid present, govRole=null)
     * must produce byte-identical output to the pre-Phase-9 code. The governance assembler
     * must NEVER be called.
     */
    @Test
    public void cicdPassThrough_validCicdTicketInfo_byteIdenticalToOriginal() throws Exception {
        // 1. Build a real CI/CD ticket
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setOwnerUid(UID);
        ticket.setTicketTitle("CI/CD change ticket");
        ticket.setDescription("CI/CD flow description");
        ticket.setRawSql("ALTER TABLE orders ADD COLUMN note TEXT");
        ticket.setTargetInfo("mysql-prod @ order_db");
        ticket.setTicketInfo("{\"changeId\":999,\"changeOwnerUid\":\"cicd-owner\"}");

        // 2. Mock CI/CD dependencies
        com.clougence.clouddm.console.web.component.approval.handler.ChangeApprovalHandler handler =
            new com.clougence.clouddm.console.web.component.approval.handler.ChangeApprovalHandler();

        ChangeFlowDal cicdFlowDal = mock(ChangeFlowDal.class);
        AuthDal cicdAuthDal = mock(AuthDal.class);
        GovChangeFormAssembler assemblerMock = mock(GovChangeFormAssembler.class);
        ReflectionTestUtils.setField(handler, "changeFlowDal", cicdFlowDal);
        ReflectionTestUtils.setField(handler, "authDal", cicdAuthDal);
        ReflectionTestUtils.setField(handler, "govChangeFormAssembler", assemblerMock);

        DmChangeMapper changeMapper = mock(DmChangeMapper.class);
        DmChangeFlowMapper flowMapper = mock(DmChangeFlowMapper.class);
        when(cicdFlowDal.changeMapper()).thenReturn(changeMapper);
        when(cicdFlowDal.flowMapper()).thenReturn(flowMapper);

        DmChangeDO changeDO = new DmChangeDO();
        changeDO.setOwnerUid("cicd-owner");
        changeDO.setRefFlowId(42L);
        changeDO.setChangeName("release-1.0");
        changeDO.setChangeBranch("main");
        when(changeMapper.queryChangeById(999L)).thenReturn(changeDO);

        DmChangeFlowDO flowDO = new DmChangeFlowDO();
        flowDO.setFlowName("prod-flow");
        when(flowMapper.queryByOwnerAndId("cicd-owner", 42L)).thenReturn(flowDO);

        DmAuthUserDO userDO = new DmAuthUserDO();
        userDO.setPhone("13900000000");
        com.clougence.clouddm.platform.dal.mapper.auth.DmAuthUserMapper userMapper =
            mock(com.clougence.clouddm.platform.dal.mapper.auth.DmAuthUserMapper.class);
        when(cicdAuthDal.userMapper()).thenReturn(userMapper);
        when(userMapper.queryByUid(UID)).thenReturn(userDO);

        // 3. Call private convertToChangeForm via reflection
        java.lang.reflect.Method method = com.clougence.clouddm.console.web.component.approval.handler.ChangeApprovalHandler.class
            .getDeclaredMethod("convertToChangeForm", DmApprovalDO.class, String.class);
        method.setAccessible(true);
        ChangeForm form = (ChangeForm) method.invoke(handler, ticket, "PROC-CICD-001");

        // 4. Assert CI/CD form values (byte-identical to original logic)
        assertEquals("13900000000", form.getTicketUserPhone());
        assertEquals("CI/CD change ticket", form.getTicketTitle());
        assertEquals("CI/CD flow description", form.getTicketDesc());
        assertEquals("PROC-CICD-001", form.getTemplateIdentity());
        assertEquals("mysql-prod @ order_db", form.getTargetDs());
        assertEquals("ALTER TABLE orders ADD COLUMN note TEXT", form.getExecuteSql());
        assertEquals("prod-flow", form.getFlowName());
        assertEquals("release-1.0", form.getChangeName());
        assertEquals("main", form.getBranch());

        // 5. Governance assembler NEVER called for CI/CD tickets
        verifyNoInteractions(assemblerMock);
    }

    /**
     * D8: invalid JSON ticketInfo → try-catch → info=null → CI/CD path → IllegalArgumentException
     * (same outcome as pre-Phase-9: ticket fails). The governance assembler is never called.
     */
    @Test
    public void cicdPassThrough_malformedJson_fallsToCicdPath_notGovernance() throws Exception {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setOwnerUid(UID);
        ticket.setTicketInfo("{broken json");

        com.clougence.clouddm.console.web.component.approval.handler.ChangeApprovalHandler handler =
            new com.clougence.clouddm.console.web.component.approval.handler.ChangeApprovalHandler();
        GovChangeFormAssembler assemblerMock = mock(GovChangeFormAssembler.class);
        ReflectionTestUtils.setField(handler, "govChangeFormAssembler", assemblerMock);

        java.lang.reflect.Method method = com.clougence.clouddm.console.web.component.approval.handler.ChangeApprovalHandler.class
            .getDeclaredMethod("convertToChangeForm", DmApprovalDO.class, String.class);
        method.setAccessible(true);

        try {
            method.invoke(handler, ticket, "PROC-001");
            fail("Expected IllegalArgumentException for null ticket info");
        } catch (java.lang.reflect.InvocationTargetException e) {
            // Reflection wraps the thrown exception
            assertTrue(e.getCause() instanceof IllegalArgumentException);
            assertTrue(e.getCause().getMessage().contains("ticket info is null"));
        }

        // Governance assembler NEVER called
        verifyNoInteractions(assemblerMock);
    }

    /**
     * D8: null ticketInfo → JsonUtils.toObj returns null → CI/CD path → IllegalArgumentException.
     * This is the identical behavior to pre-Phase-9 (the original code also checks info==null).
     */
    @Test
    public void cicdPassThrough_nullTicketInfo_fallsToCicdPath_notGovernance() throws Exception {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setOwnerUid(UID);
        ticket.setTicketInfo(null);

        com.clougence.clouddm.console.web.component.approval.handler.ChangeApprovalHandler handler =
            new com.clougence.clouddm.console.web.component.approval.handler.ChangeApprovalHandler();
        GovChangeFormAssembler assemblerMock = mock(GovChangeFormAssembler.class);
        ReflectionTestUtils.setField(handler, "govChangeFormAssembler", assemblerMock);

        java.lang.reflect.Method method = com.clougence.clouddm.console.web.component.approval.handler.ChangeApprovalHandler.class
            .getDeclaredMethod("convertToChangeForm", DmApprovalDO.class, String.class);
        method.setAccessible(true);

        try {
            method.invoke(handler, ticket, "PROC-001");
            fail("Expected IllegalArgumentException for null ticket info");
        } catch (java.lang.reflect.InvocationTargetException e) {
            assertTrue(e.getCause() instanceof IllegalArgumentException);
        }

        verifyNoInteractions(assemblerMock);
    }

    // ======= Helpers =======

    private DmApprovalDO buildGovernanceTicket(String govRole, Long promotionId, Long revisionId, Long logicalDbId) {
        DmApprovalDO ticket = new DmApprovalDO();
        ticket.setId(TICKET_ID);
        ticket.setPrimaryUid(PUID);
        ticket.setOwnerUid(UID);
        ticket.setBizId("BIZ-001");
        ticket.setRawSql("SELECT 1");
        ticket.setTicketTitle("test ticket");
        ticket.setDescription("test desc");
        return ticket;
    }

    private ApprovalMO buildGovMO(String govRole, Long promotionId, Long revisionId, Long logicalDbId) {
        ApprovalMO info = new ApprovalMO();
        info.setGovRole(govRole);
        info.setPromotionId(promotionId);
        info.setRevisionId(revisionId);
        info.setLogicalDbId(logicalDbId);
        return info;
    }

    private DmDbChangePromotionDO buildPromotion(long id, String code, PromotionType type, long prodEnvId) {
        DmDbChangePromotionDO promo = new DmDbChangePromotionDO();
        promo.setId(id);
        promo.setPromotionCode(code);
        promo.setPromotionType(type.name());
        promo.setProdEnvId(prodEnvId);
        return promo;
    }

    private DmDbChangeRevisionDO buildRevision(long id, String code, RevisionSourceType sourceType,
                                               long sourceTicketId, String changeType, String sqlHash) {
        DmDbChangeRevisionDO rev = new DmDbChangeRevisionDO();
        rev.setId(id);
        rev.setRevisionCode(code);
        rev.setSourceType(sourceType.name());
        rev.setSourceTicketId(sourceTicketId);
        rev.setChangeType(changeType);
        rev.setSqlHash(sqlHash != null ? sqlHash : "a1b2c3d4e5f6");
        // Build manifest: 2 stmts, all SUCCESS
        String manifest = "[{\"idx\":1,\"stmt_hash\":\"h1\",\"version\":1,\"pre_exec\":\"SUCCESS\"},"
            + "{\"idx\":2,\"stmt_hash\":\"h2\",\"version\":1,\"pre_exec\":\"SUCCESS\"}]";
        rev.setStmtManifest(manifest);
        return rev;
    }

    private DmLogicalDbDO buildLogicalDb(long id, String resourceName) {
        DmLogicalDbDO db = new DmLogicalDbDO();
        db.setId(id);
        db.setResourceName(resourceName);
        return db;
    }

    private DmSysEnvDO buildEnv(long id, String envName) {
        DmSysEnvDO env = new DmSysEnvDO();
        env.setId(id);
        env.setEnvName(envName);
        return env;
    }

    private void setupUser() {
        DmAuthUserDO user = new DmAuthUserDO();
        user.setPhone("13800000001");
        user.setUsername("alice");
        com.clougence.clouddm.platform.dal.mapper.auth.DmAuthUserMapper userMapper =
            mock(com.clougence.clouddm.platform.dal.mapper.auth.DmAuthUserMapper.class);
        when(authDal.userMapper()).thenReturn(userMapper);
        when(userMapper.queryByUid(UID)).thenReturn(user);
    }

    private static String buildPathBGateResultJson(String riskLevel, long estimatedRows, Long warn, Long block) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        sb.append("{\"item\":1,\"label\":\"DML-only\",\"pass\":true,\"reason\":\"changeType=DML\"},");
        sb.append("{\"item\":2,\"label\":\"GOV_DML_DIRECT\",\"pass\":true,\"reason\":\"on\"},");
        sb.append("{\"item\":3,\"label\":\"Auth label\",\"pass\":true,\"reason\":\"label\"},");
        sb.append("{\"item\":4,\"label\":\"Resource permission\",\"pass\":true,\"reason\":\"passed\"},");
        sb.append("{\"item\":5,\"label\":\"Rollback SQL\",\"pass\":true,\"reason\":\"provided\"},");
        String thresholdEvidence;
        if (warn != null && block != null) {
            thresholdEvidence = "riskLevel=" + riskLevel + ", estimatedRows=" + estimatedRows
                + ", warn=" + warn + ", block=" + block;
        } else {
            thresholdEvidence = "riskLevel=" + riskLevel + ", row_limit not configured";
        }
        sb.append("{\"item\":6,\"label\":\"Threshold\",\"pass\":true,\"reason\":\"").append(thresholdEvidence).append("\",\"riskLevel\":\"").append(riskLevel).append("\"}");
        sb.append("]");
        return sb.toString();
    }

    private static String repeatChar(char c, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
