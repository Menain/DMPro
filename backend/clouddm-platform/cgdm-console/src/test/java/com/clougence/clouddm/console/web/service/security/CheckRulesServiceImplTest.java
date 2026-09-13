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
package com.clougence.clouddm.console.web.service.security;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Collections;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.console.web.model.fo.checkrules.SpecRulesFO;
import com.clougence.clouddm.console.web.service.envparam.DmEnvParamService;
import com.clougence.clouddm.platform.dal.access.SecRuleDal;
import com.clougence.clouddm.platform.dal.mapper.secrule.DmSecRefererMapper;
import com.clougence.clouddm.platform.dal.mapper.secrule.DmSecRulesMapper;
import com.clougence.clouddm.platform.dal.mapper.secrule.DmSecSensitiveMapper;
import com.clougence.clouddm.platform.dal.mapper.secrule.DmSecSpecMapper;
import com.clougence.clouddm.platform.dal.model.secrule.DmSecRefererDO;
import com.clougence.clouddm.platform.dal.model.secrule.DmSecRuleDO;
import com.clougence.clouddm.platform.dal.model.secrule.DmSecSensitiveDO;
import com.clougence.clouddm.platform.dal.model.secrule.DmSecSpecDO;
import com.clougence.clouddm.platform.dal.model.secrule.RuleKind;
import com.clougence.clouddm.platform.dal.model.secrule.RuleSensitiveMode;
import com.clougence.clouddm.platform.dal.model.secrule.WarnLevel;
import com.clougence.clouddm.platform.dal.model.system.DmSysEnvDO;
import com.clougence.clouddm.sdk.model.env.EnvParamKeys;

public class CheckRulesServiceImplTest {

    private SecRuleDal               secRuleDal;
    private DmSecSpecMapper          specMapper;
    private DmSecRefererMapper      refererMapper;
    private DmSecRulesMapper         rulesMapper;
    private DmSecSensitiveMapper    sensitiveMapper;
    private DmEnvParamService        envParamService;
    private CheckRulesServiceImpl   checkRulesService;

    private static final String PUID = "test-puid";
    private static final long   SPEC_ID_QUERY = 100L;
    private static final long   SPEC_ID_SENSITIVE = 200L;

    @Before
    public void setUp() {
        secRuleDal = mock(SecRuleDal.class);
        specMapper = mock(DmSecSpecMapper.class);
        refererMapper = mock(DmSecRefererMapper.class);
        rulesMapper = mock(DmSecRulesMapper.class);
        sensitiveMapper = mock(DmSecSensitiveMapper.class);
        envParamService = mock(DmEnvParamService.class);

        when(secRuleDal.specMapper()).thenReturn(specMapper);
        when(secRuleDal.refererMapper()).thenReturn(refererMapper);
        when(secRuleDal.rulesMapper()).thenReturn(rulesMapper);
        when(secRuleDal.sensitiveMapper()).thenReturn(sensitiveMapper);

        checkRulesService = new CheckRulesServiceImpl();
        ReflectionTestUtils.setField(checkRulesService, "secRuleDal", secRuleDal);
        ReflectionTestUtils.setField(checkRulesService, "rdpDsEnvService", envParamService);
    }

    private DmSecSpecDO createSpec(long id, RuleKind kind) {
        DmSecSpecDO spec = new DmSecSpecDO();
        spec.setId(id);
        spec.setOwnerUid(PUID);
        spec.setName("spec-" + id);
        spec.setDescription("desc-" + id);
        spec.setEnable(true);
        spec.setRuleKind(kind);
        return spec;
    }

    // -- saveSpecRules cross-kind rejection --

    @Test
    public void saveSpecRules_rejectsCrossKindBinding_QUERY_spec_with_SENSITIVE_rule() {
        DmSecSpecDO spec = createSpec(SPEC_ID_QUERY, RuleKind.QUERY);
        when(specMapper.queryByIdAndUid(PUID, SPEC_ID_QUERY)).thenReturn(spec);

        SpecRulesFO ruleFO = new SpecRulesFO();
        ruleFO.setRuleId(10L);
        ruleFO.setRuleKind(RuleKind.SENSITIVE);

        try {
            checkRulesService.saveSpecRules(PUID, SPEC_ID_QUERY, Collections.singletonList(ruleFO));
            fail("Expected ErrorMessageException for cross-kind binding");
        } catch (ErrorMessageException e) {
            // expected — kind mismatch
        }
    }

    @Test
    public void saveSpecRules_rejectsCrossKindBinding_SENSITIVE_spec_with_QUERY_rule() {
        DmSecSpecDO spec = createSpec(SPEC_ID_SENSITIVE, RuleKind.SENSITIVE);
        when(specMapper.queryByIdAndUid(PUID, SPEC_ID_SENSITIVE)).thenReturn(spec);

        SpecRulesFO ruleFO = new SpecRulesFO();
        ruleFO.setRuleId(10L);
        ruleFO.setRuleKind(RuleKind.QUERY);

        try {
            checkRulesService.saveSpecRules(PUID, SPEC_ID_SENSITIVE, Collections.singletonList(ruleFO));
            fail("Expected ErrorMessageException for cross-kind binding");
        } catch (ErrorMessageException e) {
            // expected — kind mismatch
        }
    }

    @Test
    public void saveSpecRules_sameKindPassesValidation() {
        DmSecSpecDO spec = createSpec(SPEC_ID_SENSITIVE, RuleKind.SENSITIVE);
        when(specMapper.queryByIdAndUid(PUID, SPEC_ID_SENSITIVE)).thenReturn(spec);
        when(refererMapper.queryBySpecAndRuleOrSen(eq(PUID), eq(SPEC_ID_SENSITIVE), anyLong(), any(RuleKind.class)))
            .thenReturn(null);

        DmSecSensitiveDO senRule = new DmSecSensitiveDO();
        senRule.setId(10L);
        senRule.setScriptMD5("abc123");
        when(sensitiveMapper.queryByIds(eq(PUID), any())).thenReturn(Collections.singletonList(senRule));

        SpecRulesFO ruleFO = new SpecRulesFO();
        ruleFO.setRuleId(10L);
        ruleFO.setRuleKind(RuleKind.SENSITIVE);
        ruleFO.setEnable(true);
        ruleFO.setSenMode(RuleSensitiveMode.VALUE);

        // should not throw
        checkRulesService.saveSpecRules(PUID, SPEC_ID_SENSITIVE, Collections.singletonList(ruleFO));

        ArgumentCaptor<DmSecRefererDO> captor = ArgumentCaptor.forClass(DmSecRefererDO.class);
        verify(refererMapper).insert(captor.capture());
        DmSecRefererDO savedRef = captor.getValue();
        assertEquals(RuleKind.SENSITIVE, savedRef.getRefRuleKind());
        assertEquals(WarnLevel.FAILURE, savedRef.getWarnLevel());
        assertEquals(RuleSensitiveMode.VALUE, savedRef.getSenMode());
    }

    // -- querySpecBindingEnvs key selection --

    @Test
    public void querySpecBindingEnvs_usesCheckSpecId_forQuerySpec() {
        DmSecSpecDO spec = createSpec(SPEC_ID_QUERY, RuleKind.QUERY);
        when(specMapper.queryByIdAndUid(PUID, SPEC_ID_QUERY)).thenReturn(spec);
        when(envParamService.queryListByParamKeyValue(PUID, EnvParamKeys.DM_BIND_CHECK_SPEC, "100"))
            .thenReturn(Collections.emptyList());

        checkRulesService.querySpecBindingEnvs(PUID, SPEC_ID_QUERY);

        verify(envParamService).queryListByParamKeyValue(PUID, EnvParamKeys.DM_BIND_CHECK_SPEC, "100");
        verify(envParamService, never()).queryListByParamKeyValue(eq(PUID), eq(EnvParamKeys.DM_BIND_SENSITIVE_SPEC), anyString());
    }

    @Test
    public void querySpecBindingEnvs_usesSensitiveSpecId_forSensitiveSpec() {
        DmSecSpecDO spec = createSpec(SPEC_ID_SENSITIVE, RuleKind.SENSITIVE);
        when(specMapper.queryByIdAndUid(PUID, SPEC_ID_SENSITIVE)).thenReturn(spec);
        when(envParamService.queryListByParamKeyValue(PUID, EnvParamKeys.DM_BIND_SENSITIVE_SPEC, "200"))
            .thenReturn(Collections.emptyList());

        List<DmSysEnvDO> result = checkRulesService.querySpecBindingEnvs(PUID, SPEC_ID_SENSITIVE);

        verify(envParamService).queryListByParamKeyValue(PUID, EnvParamKeys.DM_BIND_SENSITIVE_SPEC, "200");
        verify(envParamService, never()).queryListByParamKeyValue(eq(PUID), eq(EnvParamKeys.DM_BIND_CHECK_SPEC), anyString());
        assertTrue(result.isEmpty());
    }

    @Test
    public void querySpecBindingEnvs_returnsEmpty_whenSpecNotFound() {
        when(specMapper.queryByIdAndUid(PUID, 999L)).thenReturn(null);

        List<DmSysEnvDO> result = checkRulesService.querySpecBindingEnvs(PUID, 999L);

        assertTrue(result.isEmpty());
        verify(envParamService, never()).queryListByParamKeyValue(anyString(), anyString(), anyString());
    }

    // -- createSpec kind setting --

    @Test
    public void createSpec_setsRuleKind_onSpecAndInitializesSensitiveReferers() {
        DmSecSensitiveDO senRule = new DmSecSensitiveDO();
        senRule.setId(50L);
        senRule.setScriptMD5("md5-50");
        senRule.setSenMode(RuleSensitiveMode.VALUE);
        senRule.setDeprecated(false);

        when(sensitiveMapper.listByUid(PUID)).thenReturn(Collections.singletonList(senRule));

        DmSecSpecDO result = checkRulesService.createSpec(PUID, "sen-spec", "desc", true, RuleKind.SENSITIVE);

        assertEquals(RuleKind.SENSITIVE, result.getRuleKind());

        ArgumentCaptor<DmSecSpecDO> specCaptor = ArgumentCaptor.forClass(DmSecSpecDO.class);
        verify(specMapper).insert(specCaptor.capture());
        assertEquals(RuleKind.SENSITIVE, specCaptor.getValue().getRuleKind());

        ArgumentCaptor<DmSecRefererDO> refCaptor = ArgumentCaptor.forClass(DmSecRefererDO.class);
        verify(refererMapper).insert(refCaptor.capture());
        DmSecRefererDO ref = refCaptor.getValue();
        assertEquals(RuleKind.SENSITIVE, ref.getRefRuleKind());
        assertEquals(Long.valueOf(50L), ref.getRefRule());
        assertFalse(ref.isEnable());
        assertEquals(WarnLevel.FAILURE, ref.getWarnLevel());
        assertEquals(RuleSensitiveMode.VALUE, ref.getSenMode());
        assertEquals("md5-50", ref.getRefMD5());
    }

    @Test
    public void createSpec_setsQueryKind_andInitializesQueryReferers() {
        DmSecRuleDO queryRule = new DmSecRuleDO();
        queryRule.setId(40L);
        queryRule.setScriptMD5("md5-40");
        queryRule.setDeprecated(false);
        when(rulesMapper.listByUid(PUID)).thenReturn(Collections.singletonList(queryRule));

        checkRulesService.createSpec(PUID, "query-spec", "desc", true, RuleKind.QUERY);

        ArgumentCaptor<DmSecSpecDO> specCaptor = ArgumentCaptor.forClass(DmSecSpecDO.class);
        verify(specMapper).insert(specCaptor.capture());
        assertEquals(RuleKind.QUERY, specCaptor.getValue().getRuleKind());

        ArgumentCaptor<DmSecRefererDO> refCaptor = ArgumentCaptor.forClass(DmSecRefererDO.class);
        verify(refererMapper).insert(refCaptor.capture());
        DmSecRefererDO ref = refCaptor.getValue();
        assertEquals(RuleKind.QUERY, ref.getRefRuleKind());
        assertEquals(Long.valueOf(40L), ref.getRefRule());
        assertFalse(ref.isEnable());
        assertEquals(WarnLevel.FAILURE, ref.getWarnLevel());
        assertEquals("md5-40", ref.getRefMD5());
    }
}
