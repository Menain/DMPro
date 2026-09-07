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
package com.clougence.clouddm.console.web.service.logicaldb.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.console.web.model.fo.logicaldb.BindingItemFO;
import com.clougence.clouddm.console.web.model.fo.logicaldb.BindingSetFO;
import com.clougence.clouddm.console.web.model.fo.logicaldb.LogicalDbCreateFO;
import com.clougence.clouddm.console.web.model.fo.logicaldb.LogicalDbListFO;
import com.clougence.clouddm.console.web.model.fo.logicaldb.LogicalDbUpdateFO;
import com.clougence.clouddm.console.web.model.vo.logicaldb.LogicalDbBindingVO;
import com.clougence.clouddm.console.web.model.vo.logicaldb.LogicalDbTarget;
import com.clougence.clouddm.console.web.model.vo.logicaldb.LogicalDbVO;
import com.clougence.clouddm.console.web.model.vo.logicaldb.MyLogicalDbVO;
import com.clougence.clouddm.console.web.service.envparam.DmEnvParamService;
import com.clougence.clouddm.console.web.service.logicaldb.LogicalDbService;
import com.clougence.clouddm.console.web.util.DmDsUtils;
import com.clougence.clouddm.platform.dal.access.AuthDal;
import com.clougence.clouddm.platform.dal.access.DataSourceDal;
import com.clougence.clouddm.platform.dal.access.LogicalDbDal;
import com.clougence.clouddm.platform.dal.access.SystemDal;
import com.clougence.clouddm.platform.dal.model.auth.DmAuthResDO;
import com.clougence.clouddm.platform.dal.model.datasource.DmDsDO;
import com.clougence.clouddm.platform.dal.model.logicaldb.DmLogicalDbDO;
import com.clougence.clouddm.platform.dal.model.logicaldb.DmLogicalDbEnvBindingDO;
import com.clougence.clouddm.platform.dal.model.logicaldb.GovRole;
import com.clougence.clouddm.platform.dal.model.logicaldb.LogicalDbStatus;
import com.clougence.clouddm.platform.dal.model.system.DmSysEnvDO;
import com.clougence.clouddm.sdk.model.env.EnvParamKeys;
import com.clougence.clouddm.sdk.security.auth.AuthKind;
import com.clougence.utils.CollectionUtils;
import com.clougence.utils.StringUtils;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class LogicalDbServiceImpl implements LogicalDbService {

    @Resource
    private LogicalDbDal       logicalDbDal;
    @Resource
    private SystemDal          systemDal;
    @Resource
    private DataSourceDal      dsDal;
    @Resource
    private AuthDal            authDal;
    @Resource
    private DmEnvParamService  envParamService;

    // ==================== CRUD ====================

    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRED)
    @Override
    public LogicalDbVO create(String puid, String uid, LogicalDbCreateFO fo) {
        DmLogicalDbDO existing = logicalDbDal.logicalDbMapper().queryByResourceCode(fo.getResourceCode());
        if (existing != null) {
            throw new ErrorMessageException("Logical DB resource code already exists: " + fo.getResourceCode());
        }

        DmLogicalDbDO logicalDbDO = new DmLogicalDbDO();
        logicalDbDO.setResourceCode(fo.getResourceCode());
        logicalDbDO.setResourceName(fo.getResourceName());
        logicalDbDO.setDescription(fo.getDescription());
        logicalDbDO.setStatus(LogicalDbStatus.ENABLED.name());
        logicalDbDO.setCreatorUid(puid);
        try {
            logicalDbDal.logicalDbMapper().insert(logicalDbDO);
        } catch (DuplicateKeyException e) {
            throw new ErrorMessageException("Logical DB resource code already exists: " + fo.getResourceCode());
        }
        return toLogicalDbVO(logicalDbDO);
    }

    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRED)
    @Override
    public void update(String puid, String uid, LogicalDbUpdateFO fo) {
        DmLogicalDbDO logicalDb = requireLogicalDbOwnedBy(puid, fo.getId());

        if (StringUtils.isNotBlank(fo.getResourceName())) {
            logicalDb.setResourceName(fo.getResourceName());
        }
        if (fo.getDescription() != null) {
            logicalDb.setDescription(fo.getDescription());
        }
        if (StringUtils.isNotBlank(fo.getStatus())) {
            LogicalDbStatus.valueOf(fo.getStatus());
            logicalDb.setStatus(fo.getStatus());
        }
        logicalDbDal.logicalDbMapper().updateById(logicalDb);
    }

    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRED)
    @Override
    public void delete(String puid, String uid, long logicalDbId) {
        requireLogicalDbOwnedBy(puid, logicalDbId);

        // TODO Phase4+: reject deletion when downstream references (stmt_version/promotion) exist

        logicalDbDal.bindingMapper().deleteByLogicalDbId(logicalDbId);
        logicalDbDal.logicalDbMapper().deleteById(logicalDbId);
    }

    @Override
    public LogicalDbVO detail(String puid, long logicalDbId) {
        DmLogicalDbDO logicalDb = requireLogicalDbOwnedBy(puid, logicalDbId);
        return toLogicalDbVO(logicalDb);
    }

    @Override
    public List<LogicalDbVO> list(String puid, LogicalDbListFO fo) {
        String keyword = fo != null ? fo.getKeyword() : null;
        List<DmLogicalDbDO> logicalDbs = logicalDbDal.logicalDbMapper()
            .selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DmLogicalDbDO>()
                .eq(DmLogicalDbDO::getCreatorUid, puid)
                .orderByDesc(DmLogicalDbDO::getGmtCreate));
        return logicalDbs.stream()
            .filter(db -> StringUtils.isBlank(keyword)
                || db.getResourceCode().contains(keyword)
                || db.getResourceName().contains(keyword))
            .map(this::toLogicalDbVO)
            .collect(Collectors.toList());
    }

    // ==================== Binding management ====================

    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRED)
    @Override
    public void bindingSet(String puid, String uid, BindingSetFO fo) {
        requireLogicalDbOwnedBy(puid, fo.getLogicalDbId());

        List<BindingItemFO> items = fo.getBindings();
        if (CollectionUtils.isEmpty(items)) {
            // empty list = clear all bindings
            logicalDbDal.bindingMapper().deleteByLogicalDbId(fo.getLogicalDbId());
            return;
        }

        // --- validate all items (fail-fast: any single failure rejects the entire set) ---

        // env existence + tenant ownership (single query per env)
        for (BindingItemFO item : items) {
            DmSysEnvDO env = systemDal.envMapper().queryByEnvID(puid, item.getEnvId());
            if (env == null) {
                throw new ErrorMessageException("Environment not found or not belong to current tenant: " + item.getEnvId());
            }
        }

        // ds ownership (cache listByUser result)
        List<DmDsDO> dsList = dsDal.dsMapper().listByUser(puid);
        Set<Long> dsIds = dsList.stream().map(DmDsDO::getId).collect(Collectors.toSet());

        // envId uniqueness within list + resPath validation
        Set<Long> seenEnvIds = new HashSet<>();
        for (BindingItemFO item : items) {
            if (!seenEnvIds.add(item.getEnvId())) {
                throw new ErrorMessageException("Duplicate environment in binding set: " + item.getEnvId());
            }
            if (!dsIds.contains(item.getDsId())) {
                throw new ErrorMessageException("Datasource not belong to current tenant: " + item.getDsId());
            }

            // resPath: normalize and validate segment count ∈ [1,2] (catalog/schema granularity)
            String normalizedPath = DmDsUtils.normalizeResourcePath(item.getResPath());
            List<String> segments = pathSegments(normalizedPath);
            if (segments.isEmpty() || segments.size() > 2) {
                throw new ErrorMessageException("resPath must be at catalog or schema granularity (1-2 segments): " + item.getResPath());
            }
            item.setResPath(normalizedPath);
        }

        // GOV_ROLE conflict check: same non-empty role appearing ≥2 times → reject
        Map<String, List<Long>> roleEnvMap = new HashMap<>();
        for (BindingItemFO item : items) {
            String govRole = envParamService.queryParam(puid, item.getEnvId(), EnvParamKeys.GOV_ROLE);
            if (StringUtils.isNotBlank(govRole)) {
                roleEnvMap.computeIfAbsent(govRole, k -> new ArrayList<>()).add(item.getEnvId());
            }
        }
        for (Map.Entry<String, List<Long>> entry : roleEnvMap.entrySet()) {
            if (entry.getValue().size() > 1) {
                throw new ErrorMessageException("GOV_ROLE conflict: role '" + entry.getKey()
                    + "' is assigned to multiple environments: " + entry.getValue());
            }
        }

        // --- all validations passed: delete-all + insert-all (same transaction) ---
        logicalDbDal.bindingMapper().deleteByLogicalDbId(fo.getLogicalDbId());
        for (BindingItemFO item : items) {
            DmLogicalDbEnvBindingDO bindingDO = new DmLogicalDbEnvBindingDO();
            bindingDO.setLogicalDbId(fo.getLogicalDbId());
            bindingDO.setEnvId(item.getEnvId());
            bindingDO.setDsId(item.getDsId());
            bindingDO.setResPath(item.getResPath());
            try {
                logicalDbDal.bindingMapper().insert(bindingDO);
            } catch (DuplicateKeyException e) {
                throw new ErrorMessageException("Binding already exists for logical_db_id=" + fo.getLogicalDbId()
                    + ", env_id=" + item.getEnvId());
            }
        }
    }

    @Override
    public List<LogicalDbBindingVO> bindingList(String puid, long logicalDbId) {
        requireLogicalDbOwnedBy(puid, logicalDbId);
        List<DmLogicalDbEnvBindingDO> bindings = logicalDbDal.bindingMapper().listByLogicalDbId(logicalDbId);
        if (CollectionUtils.isEmpty(bindings)) {
            return Collections.emptyList();
        }

        // cache ds list for name resolution
        List<DmDsDO> dsList = dsDal.dsMapper().listByUser(puid);
        Map<Long, String> dsNameMap = dsList.stream().collect(Collectors.toMap(DmDsDO::getId, DmDsDO::getInstanceId, (a, b) -> a));

        List<LogicalDbBindingVO> vos = new ArrayList<>();
        for (DmLogicalDbEnvBindingDO binding : bindings) {
            LogicalDbBindingVO vo = new LogicalDbBindingVO();
            vo.setBindingId(binding.getId());
            vo.setEnvId(binding.getEnvId());
            vo.setDsId(binding.getDsId());
            vo.setResPath(binding.getResPath());
            vo.setDsName(dsNameMap.get(binding.getDsId()));

            DmSysEnvDO env = systemDal.envMapper().queryByEnvID(puid, binding.getEnvId());
            if (env != null) {
                vo.setEnvName(env.getEnvName());
            }

            // GOV_* display values: null = not configured → default semantics
            vo.setGovRole(envParamService.queryParam(puid, binding.getEnvId(), EnvParamKeys.GOV_ROLE));
            vo.setGovDmlDirect(envParamService.queryParam(puid, binding.getEnvId(), EnvParamKeys.GOV_DML_DIRECT));
            vo.setGovDmlRowLimit(envParamService.queryParam(puid, binding.getEnvId(), EnvParamKeys.GOV_DML_ROW_LIMIT));
            vo.setGovAutoConfirm(envParamService.queryParam(puid, binding.getEnvId(), EnvParamKeys.GOV_AUTO_CONFIRM));
            vos.add(vo);
        }
        return vos;
    }

    // ==================== Server-side resolution (internal API) ====================

    @Override
    public LogicalDbTarget getBinding(String puid, long logicalDbId, GovRole role) {
        DmLogicalDbDO logicalDb = requireLogicalDbOwnedBy(puid, logicalDbId);
        if (!LogicalDbStatus.ENABLED.name().equals(logicalDb.getStatus())) {
            throw new ErrorMessageException("Logical DB is not enabled: " + logicalDbId);
        }

        List<DmLogicalDbEnvBindingDO> bindings = logicalDbDal.bindingMapper().listByLogicalDbId(logicalDbId);
        List<DmLogicalDbEnvBindingDO> matched = new ArrayList<>();
        for (DmLogicalDbEnvBindingDO binding : bindings) {
            String govRole = envParamService.queryParam(puid, binding.getEnvId(), EnvParamKeys.GOV_ROLE);
            if (role.name().equals(govRole)) {
                matched.add(binding);
            }
        }

        if (matched.isEmpty()) {
            throw new ErrorMessageException("Logical DB " + logicalDbId + " has no binding for " + role + " role environment");
        }
        if (matched.size() > 1) {
            List<Long> conflictEnvIds = matched.stream().map(DmLogicalDbEnvBindingDO::getEnvId).collect(Collectors.toList());
            throw new ErrorMessageException("GOV_ROLE conflict: multiple " + role + " bindings found for logical DB "
                + logicalDbId + ", envIds=" + conflictEnvIds);
        }

        DmLogicalDbEnvBindingDO target = matched.get(0);
        LogicalDbTarget result = new LogicalDbTarget();
        result.setBindingId(target.getId());
        result.setLogicalDbId(logicalDbId);
        result.setEnvId(target.getEnvId());
        result.setDsId(target.getDsId());
        result.setResPath(target.getResPath());
        result.setGovRole(role);
        return result;
    }

    // ==================== myLogicalDbs ====================

    @Override
    public List<MyLogicalDbVO> myLogicalDbs(String puid, String uid) {
        // get all ENABLED logical dbs owned by the tenant
        List<DmLogicalDbDO> logicalDbs = logicalDbDal.logicalDbMapper()
            .selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DmLogicalDbDO>()
                .eq(DmLogicalDbDO::getCreatorUid, puid)
                .eq(DmLogicalDbDO::getStatus, LogicalDbStatus.ENABLED.name())
                .orderByDesc(DmLogicalDbDO::getGmtCreate));

        // short-circuit: primary account sees all (no auth query needed)
        boolean isPrimary = StringUtils.equals(uid, puid);
        // D1: single listByKind query for all auth_res rows of this uid (only when not primary)
        List<DmAuthResDO> authRows = isPrimary ? Collections.emptyList() : authDal.resMapper().listByKind(uid, AuthKind.DataSource);

        List<MyLogicalDbVO> result = new ArrayList<>();
        for (DmLogicalDbDO logicalDb : logicalDbs) {
            List<DmLogicalDbEnvBindingDO> bindings = logicalDbDal.bindingMapper().listByLogicalDbId(logicalDb.getId());
            if (CollectionUtils.isEmpty(bindings)) {
                continue;
            }

            // filter bindings to those with GOV_ROLE ∈ {PRE, PROD}
            List<DmLogicalDbEnvBindingDO> govBindings = new ArrayList<>();
            for (DmLogicalDbEnvBindingDO binding : bindings) {
                String govRole = envParamService.queryParam(puid, binding.getEnvId(), EnvParamKeys.GOV_ROLE);
                if (GovRole.PRE.name().equals(govRole) || GovRole.PROD.name().equals(govRole)) {
                    govBindings.add(binding);
                }
            }
            if (govBindings.isEmpty()) {
                continue;
            }

            if (isPrimary) {
                result.add(toMyLogicalDbVO(logicalDb));
                continue;
            }

            // check if user has any auth on any PRE/PROD binding (in-memory match against pre-fetched authRows)
            if (hasAnyBindingAuth(govBindings, authRows)) {
                result.add(toMyLogicalDbVO(logicalDb));
            }
        }
        return result;
    }

    // ==================== Auth matching (D1) ====================

    private boolean hasAnyBindingAuth(List<DmLogicalDbEnvBindingDO> bindings, List<DmAuthResDO> authRows) {
        if (CollectionUtils.isEmpty(authRows)) {
            return false;
        }

        for (DmLogicalDbEnvBindingDO binding : bindings) {
            for (DmAuthResDO row : authRows) {
                if (!row.isEffective()) {
                    continue;
                }
                // match resId (dsId)
                if (!java.util.Objects.equals(row.getResId(), binding.getDsId())) {
                    continue;
                }
                // bidirectional prefix match
                String rowPath = row.getResPath();
                String bindingPath = binding.getResPath();
                if (bindingPath.startsWith(rowPath) || rowPath.startsWith(bindingPath)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ==================== Helpers ====================

    private DmLogicalDbDO requireLogicalDbOwnedBy(String puid, long logicalDbId) {
        DmLogicalDbDO logicalDb = logicalDbDal.logicalDbMapper().selectById(logicalDbId);
        if (logicalDb == null) {
            throw new ErrorMessageException("Logical DB not found: " + logicalDbId);
        }
        if (!StringUtils.equals(logicalDb.getCreatorUid(), puid)) {
            throw new ErrorMessageException("Logical DB does not belong to the current tenant");
        }
        return logicalDb;
    }

    private List<String> pathSegments(String normalizedPath) {
        if (StringUtils.isBlank(normalizedPath) || "/".equals(normalizedPath)) {
            return Collections.emptyList();
        }
        return Arrays.stream(normalizedPath.split("/"))
            .filter(StringUtils::isNotBlank)
            .collect(Collectors.toList());
    }

    private LogicalDbVO toLogicalDbVO(DmLogicalDbDO logicalDb) {
        LogicalDbVO vo = new LogicalDbVO();
        vo.setId(logicalDb.getId());
        vo.setResourceCode(logicalDb.getResourceCode());
        vo.setResourceName(logicalDb.getResourceName());
        vo.setDescription(logicalDb.getDescription());
        vo.setStatus(logicalDb.getStatus());
        vo.setGmtCreate(logicalDb.getGmtCreate());
        return vo;
    }

    private MyLogicalDbVO toMyLogicalDbVO(DmLogicalDbDO logicalDb) {
        MyLogicalDbVO vo = new MyLogicalDbVO();
        vo.setId(logicalDb.getId());
        vo.setResourceCode(logicalDb.getResourceCode());
        vo.setResourceName(logicalDb.getResourceName());
        return vo;
    }
}
