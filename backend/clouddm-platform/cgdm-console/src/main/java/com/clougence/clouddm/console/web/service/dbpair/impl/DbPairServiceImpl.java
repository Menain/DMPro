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
package com.clougence.clouddm.console.web.service.dbpair.impl;

import java.util.ArrayList;
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

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.console.web.model.fo.dbpair.DbPairCreateFO;
import com.clougence.clouddm.console.web.model.fo.dbpair.DbPairListFO;
import com.clougence.clouddm.console.web.model.fo.dbpair.DbPairUpdateFO;
import com.clougence.clouddm.console.web.model.fo.dbpair.DbServiceCreateFO;
import com.clougence.clouddm.console.web.model.fo.dbpair.DbServiceListFO;
import com.clougence.clouddm.console.web.model.fo.dbpair.DbServiceUpdateFO;
import com.clougence.clouddm.console.web.model.vo.dbpair.DbPairServiceItemVO;
import com.clougence.clouddm.console.web.model.vo.dbpair.DbPairVO;
import com.clougence.clouddm.console.web.model.vo.dbpair.DbServiceVO;
import com.clougence.clouddm.console.web.service.dbpair.DbPairService;
import com.clougence.clouddm.platform.dal.access.DbPairDal;
import com.clougence.clouddm.platform.dal.access.DataSourceDal;
import com.clougence.clouddm.platform.dal.access.SystemDal;
import com.clougence.clouddm.platform.dal.mapper.dbpair.DmDbPairMapper;
import com.clougence.clouddm.platform.dal.mapper.dbpair.DmDbPairServiceMapper;
import com.clougence.clouddm.platform.dal.mapper.dbpair.DmDbServiceMapper;
import com.clougence.clouddm.platform.dal.mapper.datasource.DmDsMapper;
import com.clougence.clouddm.platform.dal.mapper.system.DmSysEnvMapper;
import com.clougence.clouddm.platform.dal.model.datasource.DmDsDO;
import com.clougence.clouddm.platform.dal.model.dbpair.DmDbPairDO;
import com.clougence.clouddm.platform.dal.model.dbpair.DmDbPairServiceDO;
import com.clougence.clouddm.platform.dal.model.dbpair.DmDbServiceDO;
import com.clougence.clouddm.platform.dal.model.system.DmSysEnvDO;
import com.clougence.utils.CollectionUtils;
import com.clougence.utils.StringUtils;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class DbPairServiceImpl implements DbPairService {

    private static final String STATUS_ENABLED  = "ENABLED";
    private static final String STATUS_DISABLED = "DISABLED";

    @Resource
    private DbPairDal       dbPairDal;
    @Resource
    private DataSourceDal   dsDal;
    @Resource
    private SystemDal      systemDal;

    // ==================== Pair CRUD ====================

    @Override
    public List<DbPairVO> pairList(String puid, DbPairListFO fo) {
        DmDbPairMapper pairMapper = dbPairDal.pairMapper();
        List<DmDbPairDO> pairs = pairMapper.selectList(
            new LambdaQueryWrapper<DmDbPairDO>().orderByDesc(DmDbPairDO::getGmtCreate));

        String keyword = fo != null ? fo.getKeyword() : null;
        if (StringUtils.isNotBlank(keyword)) {
            String kw = keyword.toLowerCase();
            pairs = pairs.stream().filter(pair -> {
                String preName = pair.getPreDbName() != null ? pair.getPreDbName().toLowerCase() : "";
                String prodName = pair.getProdDbName() != null ? pair.getProdDbName().toLowerCase() : "";
                String remark = pair.getRemark() != null ? pair.getRemark().toLowerCase() : "";
                return preName.contains(kw) || prodName.contains(kw) || remark.contains(kw);
            }).collect(Collectors.toList());
        }

        if (CollectionUtils.isEmpty(pairs)) {
            return Collections.emptyList();
        }

        return pairs.stream().map(pair -> toDbPairVO(pair)).collect(Collectors.toList());
    }

    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRED)
    @Override
    public DbPairVO pairCreate(String puid, String uid, DbPairCreateFO fo) {
        // validate: prod side required
        if (fo.getProdDsId() == null || StringUtils.isBlank(fo.getProdDbName())) {
            throw new ErrorMessageException("Production datasource and database name are required");
        }
        // validate: pre side must have both or neither
        boolean preDsNull = fo.getPreDsId() == null;
        boolean preDbBlank = StringUtils.isBlank(fo.getPreDbName());
        if (preDsNull != preDbBlank) {
            throw new ErrorMessageException("Pre-production datasource and database name must both be set or both be empty");
        }

        DmDbPairDO pairDO = new DmDbPairDO();
        pairDO.setPreDsId(fo.getPreDsId());
        pairDO.setPreDbName(fo.getPreDbName());
        pairDO.setProdDsId(fo.getProdDsId());
        pairDO.setProdDbName(fo.getProdDbName());
        pairDO.setStatus(STATUS_ENABLED);
        pairDO.setRemark(fo.getRemark());
        try {
            dbPairDal.pairMapper().insert(pairDO);
        } catch (DuplicateKeyException e) {
            String msg = buildConflictMessage(fo.getPreDsId(), fo.getPreDbName(), fo.getProdDsId(), fo.getProdDbName());
            throw new ErrorMessageException(msg);
        }

        // replace service associations
        replaceServices(pairDO.getId(), fo.getServiceIds());
        return toDbPairVO(pairDO);
    }

    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRED)
    @Override
    public void pairUpdate(String puid, String uid, DbPairUpdateFO fo) {
        DmDbPairDO pair = requirePair(fo.getId());
        // only remark/status/serviceIds are editable; pre/prod sides are immutable
        if (fo.getRemark() != null) {
            pair.setRemark(fo.getRemark());
        }
        if (StringUtils.isNotBlank(fo.getStatus())) {
            if (!STATUS_ENABLED.equals(fo.getStatus()) && !STATUS_DISABLED.equals(fo.getStatus())) {
                throw new ErrorMessageException("Invalid status: " + fo.getStatus());
            }
            pair.setStatus(fo.getStatus());
        }
        dbPairDal.pairMapper().updateById(pair);

        if (fo.getServiceIds() != null) {
            replaceServices(fo.getId(), fo.getServiceIds());
        }
    }

    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRED)
    @Override
    public void pairDelete(String puid, String uid, long pairId) {
        requirePair(pairId);
        dbPairDal.pairServiceMapper().deleteByPairId(pairId);
        dbPairDal.pairMapper().deleteById(pairId);
    }

    // ==================== Service CRUD ====================

    @Override
    public List<DbServiceVO> serviceList(String puid, DbServiceListFO fo) {
        DmDbServiceMapper serviceMapper = dbPairDal.serviceMapper();
        List<DmDbServiceDO> services = serviceMapper.selectList(
            new LambdaQueryWrapper<DmDbServiceDO>().orderByDesc(DmDbServiceDO::getGmtCreate));

        String keyword = fo != null ? fo.getKeyword() : null;
        if (StringUtils.isNotBlank(keyword)) {
            String kw = keyword.toLowerCase();
            services = services.stream().filter(svc -> {
                String code = svc.getServiceCode() != null ? svc.getServiceCode().toLowerCase() : "";
                String name = svc.getServiceName() != null ? svc.getServiceName().toLowerCase() : "";
                return code.contains(kw) || name.contains(kw);
            }).collect(Collectors.toList());
        }

        if (CollectionUtils.isEmpty(services)) {
            return Collections.emptyList();
        }

        // batch count pair associations for all services
        List<Long> serviceIds = services.stream().map(DmDbServiceDO::getId).collect(Collectors.toList());
        Map<Long, Integer> countMap = batchCountByServiceId(serviceIds);

        return services.stream()
            .map(svc -> toDbServiceVO(svc, countMap.getOrDefault(svc.getId(), 0)))
            .collect(Collectors.toList());
    }

    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRED)
    @Override
    public DbServiceVO serviceCreate(String puid, String uid, DbServiceCreateFO fo) {
        DmDbServiceDO serviceDO = new DmDbServiceDO();
        serviceDO.setServiceCode(fo.getServiceCode());
        serviceDO.setServiceName(fo.getServiceName());
        serviceDO.setRemark(fo.getRemark());
        try {
            dbPairDal.serviceMapper().insert(serviceDO);
        } catch (DuplicateKeyException e) {
            throw new ErrorMessageException("Service code already exists: " + fo.getServiceCode());
        }
        return toDbServiceVO(serviceDO, 0);
    }

    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRED)
    @Override
    public void serviceUpdate(String puid, String uid, DbServiceUpdateFO fo) {
        DmDbServiceDO service = requireService(fo.getId());
        // service_code is immutable after creation
        service.setServiceName(fo.getServiceName());
        service.setRemark(fo.getRemark());
        dbPairDal.serviceMapper().updateById(service);
    }

    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRED)
    @Override
    public void serviceDelete(String puid, String uid, long serviceId) {
        requireService(serviceId);
        int count = dbPairDal.pairServiceMapper().countByServiceId(serviceId);
        if (count > 0) {
            throw new ErrorMessageException("Service is referenced by " + count + " DB pair(s), please remove associations first");
        }
        dbPairDal.serviceMapper().deleteById(serviceId);
    }

    // ==================== Helpers ====================

    private DmDbPairDO requirePair(long pairId) {
        DmDbPairDO pair = dbPairDal.pairMapper().selectById(pairId);
        if (pair == null) {
            throw new ErrorMessageException("DB pair not found: " + pairId);
        }
        return pair;
    }

    private DmDbServiceDO requireService(long serviceId) {
        DmDbServiceDO service = dbPairDal.serviceMapper().selectById(serviceId);
        if (service == null) {
            throw new ErrorMessageException("DB service not found: " + serviceId);
        }
        return service;
    }

    private void replaceServices(long pairId, List<Long> serviceIds) {
        dbPairDal.pairServiceMapper().deleteByPairId(pairId);
        if (CollectionUtils.isEmpty(serviceIds)) {
            return;
        }
        // deduplicate
        Set<Long> uniqueIds = new HashSet<>(serviceIds);
        for (Long serviceId : uniqueIds) {
            DmDbPairServiceDO ps = new DmDbPairServiceDO();
            ps.setPairId(pairId);
            ps.setServiceId(serviceId);
            try {
                dbPairDal.pairServiceMapper().insert(ps);
            } catch (DuplicateKeyException e) {
                // already exists, safe to skip
            }
        }
    }

    private String buildConflictMessage(Long preDsId, String preDbName, Long prodDsId, String prodDbName) {
        // check which side conflicts
        if (preDsId != null && StringUtils.isNotBlank(preDbName)) {
            DmDbPairDO existing = dbPairDal.pairMapper().selectOne(
                new LambdaQueryWrapper<DmDbPairDO>()
                    .eq(DmDbPairDO::getPreDsId, preDsId)
                    .eq(DmDbPairDO::getPreDbName, preDbName));
            if (existing != null) {
                String status = existing.getStatus();
                if (STATUS_DISABLED.equals(status)) {
                    return "A disabled mapping already exists for pre-production datasource + database, please re-enable it instead of creating a new one (pairId=" + existing.getId() + ")";
                }
                return "Pre-production datasource + database name already mapped (pairId=" + existing.getId() + ")";
            }
        }
        DmDbPairDO existingProd = dbPairDal.pairMapper().selectOne(
            new LambdaQueryWrapper<DmDbPairDO>()
                .eq(DmDbPairDO::getProdDsId, prodDsId)
                .eq(DmDbPairDO::getProdDbName, prodDbName));
        if (existingProd != null) {
            String status = existingProd.getStatus();
            if (STATUS_DISABLED.equals(status)) {
                return "A disabled mapping already exists for production datasource + database, please re-enable it instead of creating a new one (pairId=" + existingProd.getId() + ")";
            }
            return "Production datasource + database name already mapped (pairId=" + existingProd.getId() + ")";
        }
        return "DB pair already exists with the same datasource + database name";
    }

    private DbPairVO toDbPairVO(DmDbPairDO pair) {
        DbPairVO vo = new DbPairVO();
        vo.setId(pair.getId());
        vo.setPreDsId(pair.getPreDsId());
        vo.setPreDbName(pair.getPreDbName());
        vo.setProdDsId(pair.getProdDsId());
        vo.setProdDbName(pair.getProdDbName());
        vo.setStatus(pair.getStatus());
        vo.setRemark(pair.getRemark());
        vo.setGmtCreate(pair.getGmtCreate());

        // resolve DS names and env names
        DmDsMapper dsMapper = dsDal.dsMapper();
        DmSysEnvMapper envMapper = systemDal.envMapper();

        if (pair.getPreDsId() != null) {
            DmDsDO preDs = dsMapper.queryDsIdentityById(pair.getPreDsId());
            if (preDs != null) {
                vo.setPreDsName(preDs.getInstanceDesc() != null ? preDs.getInstanceDesc() : preDs.getInstanceId());
                if (preDs.getDsEnvId() != null) {
                    DmSysEnvDO preEnv = envMapper.selectById(preDs.getDsEnvId());
                    if (preEnv != null) {
                        vo.setPreEnvName(preEnv.getEnvName());
                    }
                }
            }
        }

        DmDsDO prodDs = dsMapper.queryDsIdentityById(pair.getProdDsId());
        if (prodDs != null) {
            vo.setProdDsName(prodDs.getInstanceDesc() != null ? prodDs.getInstanceDesc() : prodDs.getInstanceId());
            if (prodDs.getDsEnvId() != null) {
                DmSysEnvDO prodEnv = envMapper.selectById(prodDs.getDsEnvId());
                if (prodEnv != null) {
                    vo.setProdEnvName(prodEnv.getEnvName());
                }
            }
        }

        // resolve services
        List<DmDbPairServiceDO> pairServices = dbPairDal.pairServiceMapper().listByPairId(pair.getId());
        if (!CollectionUtils.isEmpty(pairServices)) {
            List<Long> serviceIds = pairServices.stream()
                .map(DmDbPairServiceDO::getServiceId)
                .collect(Collectors.toList());
            List<DmDbServiceDO> services = dbPairDal.serviceMapper().selectBatchIds(serviceIds);
            Map<Long, DmDbServiceDO> svcMap = services.stream()
                .collect(Collectors.toMap(DmDbServiceDO::getId, s -> s, (a, b) -> a));
            List<DbPairServiceItemVO> items = new ArrayList<>();
            for (DmDbPairServiceDO ps : pairServices) {
                DmDbServiceDO svc = svcMap.get(ps.getServiceId());
                if (svc != null) {
                    DbPairServiceItemVO item = new DbPairServiceItemVO();
                    item.setId(svc.getId());
                    item.setServiceCode(svc.getServiceCode());
                    item.setServiceName(svc.getServiceName());
                    items.add(item);
                }
            }
            vo.setServices(items);
        } else {
            vo.setServices(Collections.emptyList());
        }

        return vo;
    }

    private DbServiceVO toDbServiceVO(DmDbServiceDO service, int pairCount) {
        DbServiceVO vo = new DbServiceVO();
        vo.setId(service.getId());
        vo.setServiceCode(service.getServiceCode());
        vo.setServiceName(service.getServiceName());
        vo.setRemark(service.getRemark());
        vo.setPairCount(pairCount);
        vo.setGmtCreate(service.getGmtCreate());
        return vo;
    }

    private Map<Long, Integer> batchCountByServiceId(List<Long> serviceIds) {
        Map<Long, Integer> result = new HashMap<>();
        for (Long serviceId : serviceIds) {
            int count = dbPairDal.pairServiceMapper().countByServiceId(serviceId);
            result.put(serviceId, count);
        }
        return result;
    }
}
