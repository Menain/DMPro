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
package com.clougence.clouddm.platform.dal.mapper.monitor;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.clougence.clouddm.platform.dal.model.monitor.LogDependBizType;
import com.clougence.clouddm.platform.dal.model.monitor.DmMonBizLogDO;

public interface DmMonBizLogMapper extends BaseMapper<DmMonBizLogDO> {

    List<DmMonBizLogDO> queryListByBizId(@Param("bizId") String bizId);

    List<DmMonBizLogDO> queryListByBizIdAndType(String bizId, LogDependBizType bizType);

    /**
     * C8 M1 (design D2): retention cleanup — delete one LIMIT-500 batch of rows older than
     * {@code date} (gmt_create; the table has no owner column — platform-global rows). The
     * caller loops until 0.
     */
    int deleteBeforeDate(@Param("date") java.util.Date date);
}
