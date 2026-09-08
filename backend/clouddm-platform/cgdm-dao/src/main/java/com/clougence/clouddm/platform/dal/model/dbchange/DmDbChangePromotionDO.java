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
package com.clougence.clouddm.platform.dal.model.dbchange;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

/**
 * Promotion — the production deployment artifact and its state machine (spec §3.4).
 * Unlike revision/stmt_version, this table IS mutable via controlled state-machine transitions.
 * Status writes go through the single-point transit() method; prod_approval_id via updateProdApprovalId().
 */
@Getter
@Setter
@TableName(value = "dm_db_change_promotion")
public class DmDbChangePromotionDO {

    @TableId(type = IdType.AUTO)
    private Long   id;

    @TableField(insertStrategy = FieldStrategy.NOT_NULL, updateStrategy = FieldStrategy.NOT_NULL)
    private Date   gmtCreate;

    @TableField(insertStrategy = FieldStrategy.NOT_NULL, updateStrategy = FieldStrategy.NOT_NULL)
    private Date   gmtModified;

    private String promotionCode;

    private String promotionType;

    private Long   revisionId;

    private Long   logicalDbId;

    private Long   prodEnvId;

    private Long   prodDsId;

    private String prodResPath;

    private Long   prodApprovalId;

    private String executionKey;

    private String gateResult;

    private String preflightResult;

    private String status;
}
