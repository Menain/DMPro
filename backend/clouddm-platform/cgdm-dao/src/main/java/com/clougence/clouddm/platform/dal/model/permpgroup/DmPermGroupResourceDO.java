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
package com.clougence.clouddm.platform.dal.model.permpgroup;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.clougence.clouddm.sdk.security.auth.AuthKind;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName(value = "dm_perm_group_resource", autoResultMap = true)
public class DmPermGroupResourceDO {

    @TableId(type = IdType.AUTO)
    private Long           id;

    @TableField(insertStrategy = FieldStrategy.NOT_NULL, updateStrategy = FieldStrategy.NOT_NULL)
    private Date           gmtCreate;

    @TableField(insertStrategy = FieldStrategy.NOT_NULL, updateStrategy = FieldStrategy.NOT_NULL)
    private Date           gmtModified;

    private Long           groupId;

    private AuthKind       authKind;

    private Long           resId;

    private String         resPath;

    @TableField(value = "res_auth_label", typeHandler = JacksonTypeHandler.class)
    private List<String>   authLabels = new ArrayList<>();

    private Date           startTime;

    private Date           endTime;
}
