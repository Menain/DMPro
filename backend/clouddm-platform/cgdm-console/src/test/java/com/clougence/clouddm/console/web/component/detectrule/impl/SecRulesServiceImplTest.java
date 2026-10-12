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
package com.clougence.clouddm.console.web.component.detectrule.impl;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Collections;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.base.metadata.ds.DataSourceType;
import com.clougence.clouddm.console.web.component.detectrule.SecCheckerRules;
import com.clougence.clouddm.console.web.service.envparam.DmEnvParamService;
import com.clougence.clouddm.platform.dal.access.ObjectCacheDao;
import com.clougence.clouddm.platform.dal.access.SecRuleDal;
import com.clougence.clouddm.platform.dal.access.entry.DsCacheEntry;
import com.clougence.clouddm.platform.dal.mapper.secrule.DmSecRefererMapper;
import com.clougence.clouddm.platform.dal.mapper.secrule.DmSecRangeMapper;
import com.clougence.clouddm.platform.dal.mapper.secrule.DmSecSensitiveMapper;
import com.clougence.clouddm.platform.dal.mapper.secrule.DmSecSpecMapper;
import com.clougence.clouddm.platform.dal.model.secrule.DmSecRangeDO;
import com.clougence.clouddm.platform.dal.model.secrule.DmSecRefererDO;
import com.clougence.clouddm.platform.dal.model.secrule.DmSecSensitiveDO;
import com.clougence.clouddm.platform.dal.model.secrule.DmSecSpecDO;
import com.clougence.clouddm.platform.dal.model.secrule.RuleKind;
import com.clougence.clouddm.platform.dal.model.secrule.RuleSensitiveMode;
import com.clougence.clouddm.platform.dal.model.secrule.RuleScriptType;
import com.clougence.clouddm.sdk.model.env.EnvParamKeys;
import com.clougence.clouddm.sdk.service.secrules.SecRulesCheckerService;

public class SecRulesServiceImplTest {

    private SecRuleDal               secRuleDal;
    private DmSecSpecMapper          specMapper;
    private DmSecRefererMapper      refererMapper;
    private DmSecRangeMapper         rangeMapper;
    private DmSecSensitiveMapper    sensitiveMapper;
    private ObjectCacheDao           objectCacheDao;
    private DmEnvParamService        envParamService;
    private SecRulesCheckerService   checkerSpi;
    private SecRulesServiceImpl      secRulesService;

    private static final String PUID  = "test-puid";
    private static final long   DS_ID = 10L;
    private static final long   ENV_ID = 3L;
    private static final long   SPEC_OLD = 1L;
    private static final long   SPEC_NEW = 2L;

    @Before
    public void setUp() {
        secRuleDal = mock(SecRuleDal.class);
        specMapper = mock(DmSecSpecMapper.class);
        refererMapper = mock(DmSecRefererMapper.class);
        rangeMapper = mock(DmSecRangeMapper.class);
        sensitiveMapper = mock(DmSecSensitiveMapper.class);
        objectCacheDao = mock(ObjectCacheDao.class);
        envParamService = mock(DmEnvParamService.class);
        checkerSpi = mock(SecRulesCheckerService.class);

        when(secRuleDal.specMapper()).thenReturn(specMapper);
        when(secRuleDal.refererMapper()).thenReturn(refererMapper);
        when(secRuleDal.rangeMapper()).thenReturn(rangeMapper);
        when(secRuleDal.sensitiveMapper()).thenReturn(sensitiveMapper);
        when(checkerSpi.getParameters(anyString(), anyString())).thenReturn(Collections.emptyList());

        secRulesService = new SecRulesServiceImpl();
        ReflectionTestUtils.setField(secRulesService, "secRuleDal", secRuleDal);
        ReflectionTestUtils.setField(secRulesService, "objectCacheDao", objectCacheDao);
        ReflectionTestUtils.setField(secRulesService, "dmEnvParamService", envParamService);
        ReflectionTestUtils.setField(secRulesService, "checkerSpiCache", checkerSpi);

        DsCacheEntry dsCache = new DsCacheEntry();
        dsCache.setOwnerUid(PUID);
        dsCache.setDsNumId(DS_ID);
        dsCache.setDsInstId("inst-10");
        dsCache.setDsType(DataSourceType.MySQL);
        dsCache.setEnvId(ENV_ID);
        when(objectCacheDao.queryByDsId(DS_ID)).thenReturn(dsCache);
    }

    private DmSecSpecDO createSpec(long id, String name) {
        DmSecSpecDO spec = new DmSecSpecDO();
        spec.setId(id);
        spec.setOwnerUid(PUID);
        spec.setName(name);
        spec.setEnable(true);
        spec.setRuleKind(RuleKind.SENSITIVE);
        return spec;
    }

    private DmSecRefererDO createSenReferer(long ruleId) {
        DmSecRefererDO ref = new DmSecRefererDO();
        ref.setId(ruleId * 10);
        ref.setRefSpec(SPEC_OLD);
        ref.setRefRule(ruleId);
        ref.setRefRuleKind(RuleKind.SENSITIVE);
        ref.setEnable(true);
        ref.setSenMode(RuleSensitiveMode.VALUE);
        return ref;
    }

    private DmSecSensitiveDO createSenRule(long id) {
        DmSecSensitiveDO rule = new DmSecSensitiveDO();
        rule.setId(id);
        rule.setName("sen-rule-" + id);
        rule.setDescription("desc");
        rule.setScriptType(RuleScriptType.DetectRules);
        rule.setScriptContent("return null");
        rule.setScriptMD5("md5-" + id);
        rule.setSenMode(RuleSensitiveMode.VALUE);
        return rule;
    }

    private void mockSpecReferers(long specId, List<DmSecRefererDO> referers) {
        when(refererMapper.listBySpecId(PUID, specId)).thenReturn(referers);
        when(rangeMapper.queryListBySpecId(PUID, specId)).thenReturn(Collections.<DmSecRangeDO>emptyList());
        if (!referers.isEmpty()) {
            when(sensitiveMapper.queryByIds(eq(PUID), any())).thenReturn(
                referers.stream()
                    .filter(r -> r.getRefRuleKind() == RuleKind.SENSITIVE)
                    .map(r -> createSenRule(r.getRefRule()))
                    .collect(java.util.stream.Collectors.toList())
            );
        }
    }

    // -- Branch 1: both keys empty -> invalid --

    @Test
    public void resolveCheckerRules_bothKeysEmpty_returnsInvalid() {
        when(envParamService.queryParam(PUID, ENV_ID, EnvParamKeys.DM_BIND_CHECK_SPEC)).thenReturn(null);
        when(envParamService.queryParam(PUID, ENV_ID, EnvParamKeys.DM_BIND_SENSITIVE_SPEC)).thenReturn(null);

        SecCheckerRules rules = secRulesService.fetchCheckerRules(PUID, DS_ID);

        assertFalse(rules.isValid());
    }

    // -- Branch 2: only old key bound -> SENSITIVE falls back to old spec --

    @Test
    public void resolveCheckerRules_onlyOldKeyBound_sensitiveFallsBackToOldSpec() {
        when(envParamService.queryParam(PUID, ENV_ID, EnvParamKeys.DM_BIND_CHECK_SPEC)).thenReturn("1");
        when(envParamService.queryParam(PUID, ENV_ID, EnvParamKeys.DM_BIND_SENSITIVE_SPEC)).thenReturn(null);

        DmSecSpecDO oldSpec = createSpec(SPEC_OLD, "old-spec");
        when(specMapper.queryByIdAndUid(PUID, SPEC_OLD)).thenReturn(oldSpec);

        mockSpecReferers(SPEC_OLD, Collections.singletonList(createSenReferer(50L)));

        SecCheckerRules rules = secRulesService.fetchCheckerRules(PUID, DS_ID);

        assertTrue(rules.isValid());
        assertFalse(rules.getSenRuleList().isEmpty());
        assertEquals("old-spec", rules.getDsUseSensitiveSpecName());
        // QUERY group should be empty (no QUERY referers in this spec)
        assertTrue(rules.getQueryRuleList().isEmpty());
    }

    // -- Branch 3: only new key bound -> SENSITIVE from new spec, QUERY empty --

    @Test
    public void resolveCheckerRules_onlyNewKeyBound_sensitiveFromNewSpec() {
        when(envParamService.queryParam(PUID, ENV_ID, EnvParamKeys.DM_BIND_CHECK_SPEC)).thenReturn(null);
        when(envParamService.queryParam(PUID, ENV_ID, EnvParamKeys.DM_BIND_SENSITIVE_SPEC)).thenReturn("2");

        DmSecSpecDO newSpec = createSpec(SPEC_NEW, "new-spec");
        when(specMapper.queryByIdAndUid(PUID, SPEC_NEW)).thenReturn(newSpec);

        mockSpecReferers(SPEC_NEW, Collections.singletonList(createSenReferer(60L)));

        SecCheckerRules rules = secRulesService.fetchCheckerRules(PUID, DS_ID);

        assertTrue(rules.isValid());
        assertFalse(rules.getSenRuleList().isEmpty());
        assertEquals("new-spec", rules.getDsUseSensitiveSpecName());
        // QUERY spec name should be null (no old key bound)
        assertNull(rules.getDsUseSpecName());
        // Verify old spec was never queried for referers (no fallback)
        verify(refererMapper, never()).listBySpecId(PUID, SPEC_OLD);
    }

    // -- Branch 4: both keys bound -> no fallback, each chain reads its own spec --

    @Test
    public void resolveCheckerRules_bothKeysBound_noFallbackForSensitive() {
        when(envParamService.queryParam(PUID, ENV_ID, EnvParamKeys.DM_BIND_CHECK_SPEC)).thenReturn("1");
        when(envParamService.queryParam(PUID, ENV_ID, EnvParamKeys.DM_BIND_SENSITIVE_SPEC)).thenReturn("2");

        DmSecSpecDO oldSpec = createSpec(SPEC_OLD, "old-spec");
        oldSpec.setRuleKind(RuleKind.QUERY);
        DmSecSpecDO newSpec = createSpec(SPEC_NEW, "new-spec");
        when(specMapper.queryByIdAndUid(PUID, SPEC_OLD)).thenReturn(oldSpec);
        when(specMapper.queryByIdAndUid(PUID, SPEC_NEW)).thenReturn(newSpec);

        // old spec has a SENSITIVE referer, but since new key is bound, it should NOT be used
        mockSpecReferers(SPEC_OLD, Collections.singletonList(createSenReferer(50L)));
        mockSpecReferers(SPEC_NEW, Collections.singletonList(createSenReferer(60L)));

        SecCheckerRules rules = secRulesService.fetchCheckerRules(PUID, DS_ID);

        assertTrue(rules.isValid());
        assertFalse(rules.getSenRuleList().isEmpty());
        // SENSITIVE spec name should be from new spec, not old (no fallback)
        assertEquals("new-spec", rules.getDsUseSensitiveSpecName());
        // QUERY spec name should be from old spec
        assertEquals("old-spec", rules.getDsUseSpecName());
    }

    // ==================================================================================
    // C6/M3 tri-state matrix (task 10-11-masking-fail-closed, design §3 / B2 ruling):
    // NOT_BOUND = both keys blank (explicit opt-out); DANGLING = a key carries a value but
    // no usable spec resolves and no valid legacy fallback rescues; VALID = a binding
    // resolves to an enabled spec. The existing valid/rule behaviour above stays pinned —
    // the tri-state is an ADDITIVE marker on SecCheckerRules.
    // ==================================================================================

    // both keys blank → NOT_BOUND (never bound = explicit opt-out, NOT dangling)
    @Test
    public void maskingState_bothKeysEmpty_notBound() {
        when(envParamService.queryParam(PUID, ENV_ID, EnvParamKeys.DM_BIND_CHECK_SPEC)).thenReturn(null);
        when(envParamService.queryParam(PUID, ENV_ID, EnvParamKeys.DM_BIND_SENSITIVE_SPEC)).thenReturn("");

        SecCheckerRules rules = secRulesService.fetchCheckerRules(PUID, DS_ID);

        assertFalse(rules.isValid());
        assertEquals(SecCheckerRules.MaskingBindingState.NOT_BOUND, rules.getMaskingBindingState());
        // the invalid shell still carries the identity (M4/M5 consumers need it)
        assertEquals(PUID, rules.getOwnerUid());
        assertEquals(DS_ID, rules.getDsId());
        assertEquals(ENV_ID, rules.getEnvId());
    }

    // new key bound but the spec is deleted + old key blank → DANGLING (no fallback exists)
    @Test
    public void maskingState_newKeySpecMissing_dangling() {
        when(envParamService.queryParam(PUID, ENV_ID, EnvParamKeys.DM_BIND_CHECK_SPEC)).thenReturn(null);
        when(envParamService.queryParam(PUID, ENV_ID, EnvParamKeys.DM_BIND_SENSITIVE_SPEC)).thenReturn("2");
        when(specMapper.queryByIdAndUid(PUID, SPEC_NEW)).thenReturn(null);

        SecCheckerRules rules = secRulesService.fetchCheckerRules(PUID, DS_ID);

        assertFalse(rules.isValid());
        assertEquals(SecCheckerRules.MaskingBindingState.DANGLING, rules.getMaskingBindingState());
    }

    // new key bound but the spec is DISABLED → DANGLING
    @Test
    public void maskingState_newKeySpecDisabled_dangling() {
        when(envParamService.queryParam(PUID, ENV_ID, EnvParamKeys.DM_BIND_CHECK_SPEC)).thenReturn(null);
        when(envParamService.queryParam(PUID, ENV_ID, EnvParamKeys.DM_BIND_SENSITIVE_SPEC)).thenReturn("2");
        DmSecSpecDO disabled = createSpec(SPEC_NEW, "disabled-spec");
        disabled.setEnable(false);
        when(specMapper.queryByIdAndUid(PUID, SPEC_NEW)).thenReturn(disabled);

        SecCheckerRules rules = secRulesService.fetchCheckerRules(PUID, DS_ID);

        assertEquals(SecCheckerRules.MaskingBindingState.DANGLING, rules.getMaskingBindingState());
    }

    // new key carries a non-numeric value → DANGLING (a value exists but names no spec)
    @Test
    public void maskingState_newKeyNonNumeric_dangling() {
        when(envParamService.queryParam(PUID, ENV_ID, EnvParamKeys.DM_BIND_CHECK_SPEC)).thenReturn(null);
        when(envParamService.queryParam(PUID, ENV_ID, EnvParamKeys.DM_BIND_SENSITIVE_SPEC)).thenReturn("abc");

        SecCheckerRules rules = secRulesService.fetchCheckerRules(PUID, DS_ID);

        assertEquals(SecCheckerRules.MaskingBindingState.DANGLING, rules.getMaskingBindingState());
    }

    // new key DANGLING + old key VALID → VALID (the legacy fallback rescues — design §3)
    @Test
    public void maskingState_newKeyDanglingOldKeyValid_valid() {
        when(envParamService.queryParam(PUID, ENV_ID, EnvParamKeys.DM_BIND_CHECK_SPEC)).thenReturn("1");
        when(envParamService.queryParam(PUID, ENV_ID, EnvParamKeys.DM_BIND_SENSITIVE_SPEC)).thenReturn("2");
        when(specMapper.queryByIdAndUid(PUID, SPEC_OLD)).thenReturn(createSpec(SPEC_OLD, "old-spec"));
        when(specMapper.queryByIdAndUid(PUID, SPEC_NEW)).thenReturn(null);
        mockSpecReferers(SPEC_OLD, Collections.singletonList(createSenReferer(50L)));

        SecCheckerRules rules = secRulesService.fetchCheckerRules(PUID, DS_ID);

        assertEquals(SecCheckerRules.MaskingBindingState.VALID, rules.getMaskingBindingState());
        assertTrue(rules.isValid());
        // the fallback serves the old spec's SENSITIVE referers (behaviour unchanged)
        assertFalse(rules.getSenRuleList().isEmpty());
    }

    // both keys DANGLING → DANGLING
    @Test
    public void maskingState_bothKeysDangling_dangling() {
        when(envParamService.queryParam(PUID, ENV_ID, EnvParamKeys.DM_BIND_CHECK_SPEC)).thenReturn("1");
        when(envParamService.queryParam(PUID, ENV_ID, EnvParamKeys.DM_BIND_SENSITIVE_SPEC)).thenReturn("2");
        when(specMapper.queryByIdAndUid(PUID, SPEC_OLD)).thenReturn(null);
        when(specMapper.queryByIdAndUid(PUID, SPEC_NEW)).thenReturn(null);

        SecCheckerRules rules = secRulesService.fetchCheckerRules(PUID, DS_ID);

        assertEquals(SecCheckerRules.MaskingBindingState.DANGLING, rules.getMaskingBindingState());
    }

    // old key DANGLING + new key blank → DANGLING (legacy chain follows the same rule)
    @Test
    public void maskingState_oldKeyDanglingNewKeyBlank_dangling() {
        when(envParamService.queryParam(PUID, ENV_ID, EnvParamKeys.DM_BIND_CHECK_SPEC)).thenReturn("1");
        when(envParamService.queryParam(PUID, ENV_ID, EnvParamKeys.DM_BIND_SENSITIVE_SPEC)).thenReturn(null);
        when(specMapper.queryByIdAndUid(PUID, SPEC_OLD)).thenReturn(null);

        SecCheckerRules rules = secRulesService.fetchCheckerRules(PUID, DS_ID);

        assertEquals(SecCheckerRules.MaskingBindingState.DANGLING, rules.getMaskingBindingState());
    }

    // old key blank + old-key style: new key blank, old key VALID but zero SENSITIVE referers
    // → VALID (a valid binding with zero enabled referers is an explicit opt-out — the pinned
    // no-fallback ruling; NEVER dangling: rejection is spec-level only)
    @Test
    public void maskingState_validSpecZeroReferers_validNotDangling() {
        when(envParamService.queryParam(PUID, ENV_ID, EnvParamKeys.DM_BIND_CHECK_SPEC)).thenReturn(null);
        when(envParamService.queryParam(PUID, ENV_ID, EnvParamKeys.DM_BIND_SENSITIVE_SPEC)).thenReturn("2");
        when(specMapper.queryByIdAndUid(PUID, SPEC_NEW)).thenReturn(createSpec(SPEC_NEW, "empty-spec"));
        mockSpecReferers(SPEC_NEW, Collections.<DmSecRefererDO>emptyList());

        SecCheckerRules rules = secRulesService.fetchCheckerRules(PUID, DS_ID);

        assertEquals(SecCheckerRules.MaskingBindingState.VALID, rules.getMaskingBindingState());
        assertFalse(rules.isValid()); // zero rules → the invalid shell (existing semantics kept)
        assertTrue(rules.getSenRuleList().isEmpty());
    }

    // new key VALID (with referers) → VALID regardless of the old key's state
    @Test
    public void maskingState_newKeyValid_valid() {
        when(envParamService.queryParam(PUID, ENV_ID, EnvParamKeys.DM_BIND_CHECK_SPEC)).thenReturn("1");
        when(envParamService.queryParam(PUID, ENV_ID, EnvParamKeys.DM_BIND_SENSITIVE_SPEC)).thenReturn("2");
        when(specMapper.queryByIdAndUid(PUID, SPEC_OLD)).thenReturn(null); // old dangling — irrelevant
        when(specMapper.queryByIdAndUid(PUID, SPEC_NEW)).thenReturn(createSpec(SPEC_NEW, "new-spec"));
        mockSpecReferers(SPEC_NEW, Collections.singletonList(createSenReferer(60L)));

        SecCheckerRules rules = secRulesService.fetchCheckerRules(PUID, DS_ID);

        assertEquals(SecCheckerRules.MaskingBindingState.VALID, rules.getMaskingBindingState());
        assertTrue(rules.isValid());
    }
}
