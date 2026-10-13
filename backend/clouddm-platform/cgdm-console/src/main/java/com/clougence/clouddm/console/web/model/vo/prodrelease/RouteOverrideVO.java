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
package com.clougence.clouddm.console.web.model.vo.prodrelease;

import java.util.Date;

import lombok.Getter;
import lombok.Setter;

/**
 * Route-override grant view for the release-detail list (ADR-013 M3). {@code status} is derived
 * server-side: CONSUMED &gt; EXPIRED &gt; PENDING (the C4 twin's derived-status pattern; the DB
 * stores PENDING/CONSUMED only).
 */
@Getter
@Setter
public class RouteOverrideVO {

    private Long   id;
    private Long   releaseId;
    private Long   stmtId;
    private String reason;
    private String createdBy;
    private Date   expireAt;
    private Date   consumedAt;
    /** Comma list of the segIdx values released by the consumption (null until consumed). */
    private String coveredSegIdxs;
    private Date   gmtCreate;
    /** PENDING | CONSUMED | EXPIRED */
    private String status;
}
