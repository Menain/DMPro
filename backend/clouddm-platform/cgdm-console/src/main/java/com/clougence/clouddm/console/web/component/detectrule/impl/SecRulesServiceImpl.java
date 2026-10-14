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

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.clougence.clouddm.api.common.boot.UnifiedPostConstruct;
import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.base.metadata.ds.DataSourceType;
import com.clougence.clouddm.console.web.component.detectrule.SecCheckerRules;
import com.clougence.clouddm.console.web.component.detectrule.SecRulesService;
import com.clougence.clouddm.console.web.global.i18n.DmI18nUtils;
import com.clougence.clouddm.console.web.global.i18n.I18nDmMsgKeys;
import com.clougence.clouddm.console.web.service.envparam.DmEnvParamService;
import com.clougence.clouddm.console.web.util.DmConvertUtils;
import com.clougence.clouddm.platform.dal.access.ObjectCacheDao;
import com.clougence.clouddm.platform.dal.access.SecRuleDal;
import com.clougence.clouddm.platform.dal.access.entry.DsCacheEntry;
import com.clougence.clouddm.platform.dal.model.secrule.*;
import com.clougence.clouddm.platform.plugin.PluginManager;
import com.clougence.clouddm.sdk.model.env.EnvParamKeys;
import com.clougence.clouddm.sdk.service.secrules.CheckerRange;
import com.clougence.clouddm.sdk.service.secrules.CheckerRule;
import com.clougence.clouddm.sdk.service.secrules.SecParam;
import com.clougence.clouddm.sdk.service.secrules.SecRulesCheckerService;
import com.clougence.utils.CollectionUtils;
import com.clougence.utils.NumberUtils;
import com.clougence.utils.StringUtils;
import com.clougence.utils.ThreadUtils;

import jakarta.annotation.Resource;

/**
 * @author mode 2020-01-20 21:04
 * @since 1.1.3
 */
@Service
public class SecRulesServiceImpl implements SecRulesService, UnifiedPostConstruct {

    @Resource
    private SecRuleDal                   secRuleDal;
    @Resource
    private ObjectCacheDao               objectCacheDao;
    @Resource
    private DmEnvParamService            dmEnvParamService;

    private Map<String, SecCheckerRules> checkerRuleCache;
    private SecRulesCheckerService       checkerSpiCache;

    @Override
    public void init() throws Exception {
        this.checkerRuleCache = new ConcurrentHashMap<>();
        ThreadUtils.runDaemonThread(() -> {
            Thread.currentThread().setName("SecRulesEngine-cache-cleanup");
            while (true) {
                checkerRuleCache.clear();
                ThreadUtils.sleep(60 * 1000);
            }
        });
    }

    @Override
    public void stop() {

    }

    @Override
    public SecRulesCheckerService checkerSpi() {
        if (this.checkerSpiCache == null) {
            try {
                this.checkerSpiCache = PluginManager.findService(SecRulesCheckerService.class);
            } catch (UnsupportedOperationException e) {
                throw new ErrorMessageException(DmI18nUtils.getMessage(I18nDmMsgKeys.CHECKRULES_UNSUPPORTED_MESSAGE.name()));
            }
        }
        return this.checkerSpiCache;
    }

    @Override
    public void cleanCache(String checkerName) {

    }

    @Override
    public SecCheckerRules fetchCheckerRulesByDsId(long dsId) {
        DsCacheEntry dsCache = this.objectCacheDao.queryByDsId(dsId);

        DataSourceType dsType = dsCache.getDsType();
        String cacheKey = dsCache.getOwnerUid() + "-" + dsId + "-" + dsType;
        //return this.checkerRuleCache.computeIfAbsent(cacheKey, s -> {
        return this.resolveCheckerRules(dsCache.getOwnerUid(), dsCache);
        //});
    }

    @Override
    public SecCheckerRules fetchCheckerRules(String ownerUid, long dsId) {
        DsCacheEntry dsCache = this.objectCacheDao.queryByDsId(dsId);

        DataSourceType dsType = dsCache.getDsType();
        String cacheKey = ownerUid + "-" + dsId + "-" + dsType;
        //return this.checkerRuleCache.computeIfAbsent(cacheKey, s -> {
        return this.resolveCheckerRules(ownerUid, dsCache);
        //});
    }

    private SecCheckerRules resolveCheckerRules(String ownerUid, DsCacheEntry dsCache) {
        long dsId = dsCache.getDsNumId();
        DataSourceType dsType = dsCache.getDsType();
        long envId = dsCache.getEnvId();

        // QUERY chain: read DM_BIND_CHECK_SPEC (behavior unchanged from original)
        BoundSpec queryBinding = resolveBoundSpec(ownerUid, envId, EnvParamKeys.DM_BIND_CHECK_SPEC);
        DmSecSpecDO querySpec = queryBinding.spec;
        Map<Long, SecRefererWrap> ruleOfQuery = querySpec == null
                ? Collections.emptyMap()
                : groupRefererByKind(resolveSecRefererList(ownerUid, querySpec.getId()), RuleKind.QUERY);

        // SENSITIVE chain: read DM_BIND_SENSITIVE_SPEC;
        // only when new key is unbound / spec invalid, fall back to old key spec's SENSITIVE referers
        BoundSpec senBinding = resolveBoundSpec(ownerUid, envId, EnvParamKeys.DM_BIND_SENSITIVE_SPEC);
        DmSecSpecDO senSpec = senBinding.spec;
        Map<Long, SecRefererWrap> ruleOfSen;
        if (senSpec != null) {
            ruleOfSen = groupRefererByKind(resolveSecRefererList(ownerUid, senSpec.getId()), RuleKind.SENSITIVE);
        } else {
            ruleOfSen = querySpec == null
                    ? Collections.emptyMap()
                    : groupRefererByKind(resolveSecRefererList(ownerUid, querySpec.getId()), RuleKind.SENSITIVE);
        }

        // C6/M3 additive (design §3, B2): the masking-binding tri-state. Combination rules —
        // new key VALID wins; otherwise a VALID legacy key rescues (fallback intact); any
        // DANGLING without a valid fallback stays DANGLING; both blank = NOT_BOUND. The
        // existing valid/rule resolution below is byte-identical to the pre-C6 behaviour.
        SecCheckerRules.MaskingBindingState maskingState = resolveMaskingBindingState(senBinding, queryBinding);

        // C8 M2 additive: the spec the SENSITIVE chain effectively resolved to (new key first,
        // legacy fallback — the senSpecName caliber below), stamped onto BOTH return paths for
        // the SENSITIVE_* audit rows' masking-spec snapshot. Null when nothing resolves
        // (NOT_BOUND / DANGLING) — never-throw caliber, no extra query (both DOs are in hand).
        DmSecSpecDO effectiveSenSpec = senSpec != null ? senSpec : querySpec;

        if (ruleOfQuery.isEmpty() && ruleOfSen.isEmpty()) {
            // C6/M3: the invalid shell keeps ds identity + tri-state (M4/M5 consumers need them;
            // valid=false semantics unchanged for every existing consumer)
            SecCheckerRules shell = new SecCheckerRules(envId, dsId, maskingState, ownerUid);
            if (effectiveSenSpec != null) {
                shell.setMaskingSpecId(effectiveSenSpec.getId());
                shell.setMaskingSpecName(effectiveSenSpec.getName());
            }
            return shell;
        }

        // result
        List<CheckerRule> resultOfQuery = this.convertQueryRulesToCheckerRule(ownerUid, dsType, ruleOfQuery);
        List<CheckerRule> resultOfSen = this.convertSenRulesToCheckerRule(ownerUid, ruleOfSen);

        String querySpecName = querySpec == null ? null : querySpec.getName();
        String senSpecName = senSpec != null ? senSpec.getName()
                : (querySpec == null ? null : querySpec.getName());
        SecCheckerRules rules = new SecCheckerRules(envId, dsId, dsCache.getDsInstId(), dsType,
                querySpecName, senSpecName, resultOfQuery, resultOfSen);
        rules.setMaskingBindingState(maskingState);
        rules.setOwnerUid(ownerUid);
        if (effectiveSenSpec != null) {
            rules.setMaskingSpecId(effectiveSenSpec.getId());
            rules.setMaskingSpecName(effectiveSenSpec.getName());
        }
        return rules;
    }

    /** C6/M3: the tri-state combination table of design §3 (new key first, legacy fallback rescues). */
    private static SecCheckerRules.MaskingBindingState resolveMaskingBindingState(BoundSpec senBinding, BoundSpec queryBinding) {
        if (senBinding.state == SecCheckerRules.MaskingBindingState.VALID
            || queryBinding.state == SecCheckerRules.MaskingBindingState.VALID) {
            return SecCheckerRules.MaskingBindingState.VALID;
        }
        if (senBinding.state == SecCheckerRules.MaskingBindingState.DANGLING
            || queryBinding.state == SecCheckerRules.MaskingBindingState.DANGLING) {
            return SecCheckerRules.MaskingBindingState.DANGLING;
        }
        return SecCheckerRules.MaskingBindingState.NOT_BOUND;
    }

    /**
     * C6/M3: the tri-state carrier of one env-param binding. {@code spec} is non-null only in
     * the VALID state, so every existing {@code == null} consumer keeps its exact behaviour
     * (blank / non-numeric / missing / disabled all used to collapse to null).
     */
    private static final class BoundSpec {
        final SecCheckerRules.MaskingBindingState state;
        final DmSecSpecDO                         spec;

        private BoundSpec(SecCheckerRules.MaskingBindingState state, DmSecSpecDO spec){
            this.state = state;
            this.spec = spec;
        }

        static BoundSpec notBound() {
            return new BoundSpec(SecCheckerRules.MaskingBindingState.NOT_BOUND, null);
        }

        static BoundSpec dangling() {
            return new BoundSpec(SecCheckerRules.MaskingBindingState.DANGLING, null);
        }

        static BoundSpec valid(DmSecSpecDO spec) {
            return new BoundSpec(SecCheckerRules.MaskingBindingState.VALID, spec);
        }
    }

    private BoundSpec resolveBoundSpec(String ownerUid, long envId, String paramKey) {
        String usingSpec = this.dmEnvParamService.queryParam(ownerUid, envId, paramKey);
        if (StringUtils.isBlank(usingSpec)) {
            // never bound = explicit opt-out (NOT the same as an unusable binding — B2 ruling)
            return BoundSpec.notBound();
        }
        if (!NumberUtils.isNumber(usingSpec)) {
            // a value exists but cannot name a spec → dangling (fail-closed side of B2)
            return BoundSpec.dangling();
        }

        long specId = Long.parseLong(usingSpec);
        DmSecSpecDO specDO = this.secRuleDal.specMapper().queryByIdAndUid(ownerUid, specId);
        if (specDO == null || !specDO.isEnable()) {
            return BoundSpec.dangling();
        }
        return BoundSpec.valid(specDO);
    }

    private static Map<Long, SecRefererWrap> groupRefererByKind(Collection<SecRefererWrap> refererWraps, RuleKind kind) {
        Map<Long, SecRefererWrap> result = new HashMap<>();
        refererWraps.forEach(r -> {
            if (r.getReferer().getRefRuleKind() == kind) {
                result.put(r.getReferer().getRefRule(), r);
            }
        });
        return result;
    }

    private Collection<SecRefererWrap> resolveSecRefererList(String ownerUid, long specId) {
        Map<Long, SecRefererWrap> groupBy = new HashMap<>();
        List<DmSecRefererDO> refererList = this.secRuleDal.refererMapper().listBySpecId(ownerUid, specId);
        for (DmSecRefererDO referer : refererList) {
            if (referer.isEnable()) {
                groupBy.put(referer.getId(), new SecRefererWrap(referer));
            }
        }

        if (groupBy.isEmpty()) {
            return Collections.emptyList();
        }

        List<DmSecRangeDO> range = this.secRuleDal.rangeMapper().queryListBySpecId(ownerUid, specId);
        for (DmSecRangeDO rangeDO : range) {
            SecRefererWrap refererWrap = groupBy.get(rangeDO.getRefId());
            if (refererWrap != null) {
                refererWrap.getRangeList().add(rangeDO);
            }
        }

        return groupBy.values();
    }

    private List<CheckerRule> convertQueryRulesToCheckerRule(String ownerUid, DataSourceType dsType, Map<Long, SecRefererWrap> refMap) {
        if (refMap.isEmpty()) {
            return Collections.emptyList();
        }

        List<DmSecRuleDO> queryRules = this.secRuleDal.rulesMapper().queryByIds(ownerUid, refMap.keySet());
        return queryRules.stream().filter(r -> {
            return r.getRuleDsRange().contains(dsType);
        }).map(r -> {
            SecRefererWrap refererWrap = refMap.get(r.getId());
            DmSecRefererDO referer = refererWrap.getReferer();

            CheckerRule rule = new CheckerRule();
            rule.setRuleName(r.getName());
            rule.setRuleDesc(r.getDescription());
            rule.setParameters(extractParameter(r.getScriptType(), r.getScriptContent(), referer.getRuleParam()));
            rule.setScriptType(r.getScriptType().name());
            rule.setScript(r.getScriptContent());
            rule.setRangeList(convertSecRangeDOToCheckerRule(refererWrap.getRangeList()));

            rule.setLevel(referer.getWarnLevel().getRuleLevel());
            if (r.getRuleTarget() != null) {
                rule.setTarget(r.getRuleTarget().getType());
            } else {
                rule.setTarget(null);
            }
            return rule;
        }).collect(Collectors.toList());
    }

    private List<CheckerRule> convertSenRulesToCheckerRule(String ownerUid, Map<Long, SecRefererWrap> refMap) {
        if (refMap.isEmpty()) {
            return Collections.emptyList();
        }

        List<DmSecSensitiveDO> queryRules = this.secRuleDal.sensitiveMapper().queryByIds(ownerUid, refMap.keySet());
        return queryRules.stream().map(r -> {
            SecRefererWrap refererWrap = refMap.get(r.getId());
            DmSecRefererDO referer = refererWrap.getReferer();

            CheckerRule rule = new CheckerRule();
            rule.setRuleName(r.getName());
            rule.setRuleDesc(r.getDescription());
            rule.setParameters(extractParameter(r.getScriptType(), r.getScriptContent(), referer.getRuleParam()));
            rule.setScriptType(r.getScriptType().name());
            rule.setScript(r.getScriptContent());
            rule.setRangeList(convertSecRangeDOToCheckerRule(refererWrap.getRangeList()));

            RuleSensitiveMode senMode = refererWrap.getReferer().getSenMode();
            if (senMode != null) {
                rule.setSenMode(senMode.getSenMode());
            } else {
                rule.setSenMode(r.getSenMode().getSenMode());
            }
            return rule;
        }).collect(Collectors.toList());
    }

    private Map<String, String> extractParameter(RuleScriptType scriptType, String scriptContent, Map<String, String> userParam) {
        List<SecParam> parameters = this.checkerSpi().getParameters(scriptType.name(), scriptContent);
        Map<String, String> paramMap = new HashMap<>();
        if (parameters != null) {
            for (SecParam secParam : parameters) {
                paramMap.put(secParam.getName(), secParam.getDefaultValue());
            }
        }

        if (userParam != null) {
            paramMap.putAll(userParam);
        }
        return paramMap;
    }

    private List<CheckerRange> convertSecRangeDOToCheckerRule(List<DmSecRangeDO> list) {
        if (CollectionUtils.isEmpty(list)) {
            return Collections.emptyList();
        } else {
            return list.stream().map(DmConvertUtils::convertToCheckerRange).collect(Collectors.toList());
        }
    }
}
