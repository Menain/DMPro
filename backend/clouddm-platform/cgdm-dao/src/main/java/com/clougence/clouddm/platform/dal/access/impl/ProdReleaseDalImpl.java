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
package com.clougence.clouddm.platform.dal.access.impl;

import org.springframework.stereotype.Service;

import com.clougence.clouddm.platform.dal.access.ProdReleaseDal;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmGovRouteOverrideMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmGovSchemaGateOverrideMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseStmtMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseStmtSchemaMapper;
import com.clougence.clouddm.platform.dal.mapper.prodrelease.DmProdReleaseStmtSegmentMapper;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class ProdReleaseDalImpl implements ProdReleaseDal {

    @Resource
    private DmProdReleaseMapper      releaseMapper;
    @Resource
    private DmProdReleaseStmtMapper  stmtMapper;
    @Resource
    private DmProdReleaseStmtSchemaMapper schemaMapper;
    @Resource
    private DmProdReleaseStmtSegmentMapper segmentMapper;
    @Resource
    private DmGovSchemaGateOverrideMapper gateOverrideMapper;
    @Resource
    private DmGovRouteOverrideMapper routeOverrideMapper;

    @Override
    public DmProdReleaseMapper releaseMapper() {
        return releaseMapper;
    }

    @Override
    public DmProdReleaseStmtMapper stmtMapper() {
        return stmtMapper;
    }

    @Override
    public DmProdReleaseStmtSchemaMapper schemaMapper() {
        return schemaMapper;
    }

    @Override
    public DmProdReleaseStmtSegmentMapper segmentMapper() {
        return segmentMapper;
    }

    @Override
    public DmGovSchemaGateOverrideMapper gateOverrideMapper() {
        return gateOverrideMapper;
    }

    @Override
    public DmGovRouteOverrideMapper routeOverrideMapper() {
        return routeOverrideMapper;
    }
}
