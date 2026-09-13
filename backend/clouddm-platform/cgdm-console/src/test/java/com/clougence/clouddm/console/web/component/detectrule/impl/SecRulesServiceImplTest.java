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
}
