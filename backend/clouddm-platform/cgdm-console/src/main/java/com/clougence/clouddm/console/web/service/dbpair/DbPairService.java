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
package com.clougence.clouddm.console.web.service.dbpair;

import java.util.List;

import com.clougence.clouddm.console.web.model.fo.dbpair.DbPairCreateFO;
import com.clougence.clouddm.console.web.model.fo.dbpair.DbPairListFO;
import com.clougence.clouddm.console.web.model.fo.dbpair.DbPairUpdateFO;
import com.clougence.clouddm.console.web.model.fo.dbpair.DbServiceCreateFO;
import com.clougence.clouddm.console.web.model.fo.dbpair.DbServiceListFO;
import com.clougence.clouddm.console.web.model.fo.dbpair.DbServiceUpdateFO;
import com.clougence.clouddm.console.web.model.vo.dbpair.DbPairVO;
import com.clougence.clouddm.console.web.model.vo.dbpair.DbServiceVO;

public interface DbPairService {

    // ==================== Pair CRUD ====================

    List<DbPairVO> pairList(String puid, DbPairListFO fo);

    DbPairVO pairCreate(String puid, String uid, DbPairCreateFO fo);

    void pairUpdate(String puid, String uid, DbPairUpdateFO fo);

    void pairDelete(String puid, String uid, long pairId);

    // ==================== Service CRUD ====================

    List<DbServiceVO> serviceList(String puid, DbServiceListFO fo);

    DbServiceVO serviceCreate(String puid, String uid, DbServiceCreateFO fo);

    void serviceUpdate(String puid, String uid, DbServiceUpdateFO fo);

    void serviceDelete(String puid, String uid, long serviceId);
}
