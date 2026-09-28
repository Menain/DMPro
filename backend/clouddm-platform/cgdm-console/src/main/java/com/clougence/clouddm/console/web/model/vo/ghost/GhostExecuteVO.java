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
package com.clougence.clouddm.console.web.model.vo.ghost;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Getter;
import lombok.Setter;

/**
 * Launch outcome of {@code POST /ghost/execute}: the tool session id the frontend polls
 * (tailStatus / tailLog) and controls (pause / resume / stop / close) with, plus the AsyncTask
 * id backing the task list row. {@code audit} never goes on the wire — it carries the parsed
 * target (table name etc.) back to the controller for the success-path audit row (the B2
 * SessionKillAuditObj pattern).
 */
@Getter
@Setter
public class GhostExecuteVO {

    private String           sessionId;
    private Long             taskId;

    @JsonIgnore
    private GhostOpAuditObj  audit;
}
