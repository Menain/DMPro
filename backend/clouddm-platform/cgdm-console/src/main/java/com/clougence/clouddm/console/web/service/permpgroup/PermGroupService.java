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
package com.clougence.clouddm.console.web.service.permpgroup;

import java.util.List;

import com.clougence.clouddm.console.web.model.fo.permpgroup.CreatePermGroupFO;
import com.clougence.clouddm.console.web.model.fo.permpgroup.PermGroupMemberFO;
import com.clougence.clouddm.console.web.model.fo.permpgroup.PermGroupResourceFO;
import com.clougence.clouddm.console.web.model.fo.permpgroup.PermGroupResourceRevokeFO;
import com.clougence.clouddm.console.web.model.fo.permpgroup.UpdatePermGroupFO;
import com.clougence.clouddm.console.web.model.vo.permpgroup.PermGroupMemberVO;
import com.clougence.clouddm.console.web.model.vo.permpgroup.PermGroupResourceVO;
import com.clougence.clouddm.console.web.model.vo.permpgroup.PermGroupVO;

public interface PermGroupService {

    PermGroupVO createGroup(String puid, String uid, CreatePermGroupFO fo);

    void updateGroup(String puid, String uid, UpdatePermGroupFO fo);

    void deleteGroup(String puid, String uid, long groupId);

    void updateGroupStatus(String puid, String uid, long groupId, String status);

    List<PermGroupVO> listGroups(String puid);

    PermGroupVO getGroupDetail(String puid, long groupId);

    void addMembers(String puid, String uid, PermGroupMemberFO fo);

    void removeMembers(String puid, String uid, PermGroupMemberFO fo);

    List<PermGroupMemberVO> listMembers(String puid, long groupId);

    void grantResource(String puid, String uid, PermGroupResourceFO fo);

    void revokeResources(String puid, String uid, PermGroupResourceRevokeFO fo);

    List<PermGroupResourceVO> listResources(String puid, long groupId);
}
