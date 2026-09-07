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
package com.clougence.clouddm.platform.dal.mapper.permpgroup;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.clougence.clouddm.platform.dal.model.permpgroup.DmPermGroupGrantRecordDO;

public interface DmPermGroupGrantRecordMapper extends BaseMapper<DmPermGroupGrantRecordDO> {

    List<DmPermGroupGrantRecordDO> listByGroupId(@Param("groupId") long groupId);

    List<DmPermGroupGrantRecordDO> listByGroupResourceId(@Param("groupResourceId") long groupResourceId);

    DmPermGroupGrantRecordDO findByGroupResourceAndMember(@Param("groupResourceId") long groupResourceId, @Param("memberUid") String memberUid);

    int deleteByGroupId(@Param("groupId") long groupId);

    int deleteByGroupResourceId(@Param("groupResourceId") long groupResourceId);
}
