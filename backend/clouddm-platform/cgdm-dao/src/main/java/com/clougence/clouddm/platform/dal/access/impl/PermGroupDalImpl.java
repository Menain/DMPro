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

import com.clougence.clouddm.platform.dal.access.PermGroupDal;
import com.clougence.clouddm.platform.dal.mapper.permpgroup.DmPermGroupGrantRecordMapper;
import com.clougence.clouddm.platform.dal.mapper.permpgroup.DmPermGroupMapper;
import com.clougence.clouddm.platform.dal.mapper.permpgroup.DmPermGroupMemberMapper;
import com.clougence.clouddm.platform.dal.mapper.permpgroup.DmPermGroupResourceMapper;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class PermGroupDalImpl implements PermGroupDal {

    @Resource
    private DmPermGroupMapper             permGroupMapper;
    @Resource
    private DmPermGroupMemberMapper       permGroupMemberMapper;
    @Resource
    private DmPermGroupResourceMapper     permGroupResourceMapper;
    @Resource
    private DmPermGroupGrantRecordMapper  permGroupGrantRecordMapper;

    @Override
    public DmPermGroupMapper permGroupMapper() {
        return permGroupMapper;
    }

    @Override
    public DmPermGroupMemberMapper permGroupMemberMapper() {
        return permGroupMemberMapper;
    }

    @Override
    public DmPermGroupResourceMapper permGroupResourceMapper() {
        return permGroupResourceMapper;
    }

    @Override
    public DmPermGroupGrantRecordMapper permGroupGrantRecordMapper() {
        return permGroupGrantRecordMapper;
    }
}
