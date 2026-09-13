/*
 * Copyright 2026 杭州开云集致科技有限公司
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.clougence.clouddm.platform.dal.model.prodrelease;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

/**
 * Frozen copy of a pre-release schema snapshot, propagated at createRelease time.
 * One row per (release_stmt_id, table_name). shared_flag marks tables touched by
 * >1 stmt within the same (prod_ds_id, prod_db_name) group — shared tables are
 * degraded to WARN in the diff gate (ordered replay makes pre-before baseline
 * ambiguous for shared tables).
 */
@Getter
@Setter
@TableName(value = "dm_prod_release_stmt_schema")
public class DmProdReleaseStmtSchemaDO {

    @TableId(type = IdType.AUTO)
    private Long   id;

    @TableField(insertStrategy = FieldStrategy.NOT_NULL, updateStrategy = FieldStrategy.NOT_NULL)
    private Date   gmtCreate;

    @TableField(insertStrategy = FieldStrategy.NOT_NULL, updateStrategy = FieldStrategy.NOT_NULL)
    private Date   gmtModified;

    private Long   releaseId;

    private Long   releaseStmtId;

    private Long   prodDsId;

    private String prodDbName;

    private String tableName;

    private int    existsFlag;

    private String schemaJson;

    private String fingerprint;

    private int    sharedFlag;
}
