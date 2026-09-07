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

import com.clougence.clouddm.platform.dal.access.DbChangeGovernDal;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangeEventMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangeRevisionMapper;
import com.clougence.clouddm.platform.dal.mapper.dbchange.DmDbChangeStmtVersionMapper;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class DbChangeGovernDalImpl implements DbChangeGovernDal {

    @Resource
    private DmDbChangeStmtVersionMapper stmtVersionMapper;
    @Resource
    private DmDbChangeRevisionMapper   revisionMapper;
    @Resource
    private DmDbChangeEventMapper      eventMapper;

    @Override
    public DmDbChangeStmtVersionMapper stmtVersionMapper() {
        return stmtVersionMapper;
    }

    @Override
    public DmDbChangeRevisionMapper revisionMapper() {
        return revisionMapper;
    }

    @Override
    public DmDbChangeEventMapper eventMapper() {
        return eventMapper;
    }
}
