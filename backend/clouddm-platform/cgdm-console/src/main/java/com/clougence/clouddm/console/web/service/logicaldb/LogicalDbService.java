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
package com.clougence.clouddm.console.web.service.logicaldb;

import java.util.List;

import com.clougence.clouddm.console.web.model.fo.logicaldb.BindingSetFO;
import com.clougence.clouddm.console.web.model.fo.logicaldb.LogicalDbCreateFO;
import com.clougence.clouddm.console.web.model.fo.logicaldb.LogicalDbListFO;
import com.clougence.clouddm.console.web.model.fo.logicaldb.LogicalDbUpdateFO;
import com.clougence.clouddm.console.web.model.vo.logicaldb.LogicalDbBindingVO;
import com.clougence.clouddm.console.web.model.vo.logicaldb.LogicalDbTarget;
import com.clougence.clouddm.console.web.model.vo.logicaldb.LogicalDbVO;
import com.clougence.clouddm.console.web.model.vo.logicaldb.MyLogicalDbVO;
import com.clougence.clouddm.platform.dal.model.logicaldb.GovRole;

public interface LogicalDbService {

    LogicalDbVO create(String puid, String uid, LogicalDbCreateFO fo);

    void update(String puid, String uid, LogicalDbUpdateFO fo);

    void delete(String puid, String uid, long logicalDbId);

    LogicalDbVO detail(String puid, long logicalDbId);

    List<LogicalDbVO> list(String puid, LogicalDbListFO fo);

    void bindingSet(String puid, String uid, BindingSetFO fo);

    List<LogicalDbBindingVO> bindingList(String puid, long logicalDbId);

    List<MyLogicalDbVO> myLogicalDbs(String puid, String uid);

    /**
     * Server-side resolution of the target binding for a given governance role.
     * Internal API — no HTTP surface. Consumed by Phase 4 preSubmit and Phase 6 promote.
     *
     * @throws com.clougence.clouddm.api.common.exception.ErrorMessageException if 0 or >1 bindings match
     */
    LogicalDbTarget getBinding(String puid, long logicalDbId, GovRole role);
}
