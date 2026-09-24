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
package com.clougence.clouddm.sdk.model.ghost;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * GHOST tool {@code tailStatus} payload, mirroring the {@code FakerStatusDTO} progress contract
 * (useProgress/curValue/maxValue/message) plus gh-ost specific fields:
 *
 * <ul>
 * <li>{@code status} — session-level state: {@code INIT / RUNNING / PAUSED / COMPLETED / FAILED / ABORTED};</li>
 * <li>{@code phase} — gh-ost migration phase: {@code READY / GHOST_TABLE_CREATED / COPYING / ROW_COPY_COMPLETE / CUT_OVER / DONE};</li>
 * <li>{@code oldTableName} — captured from the "Creating magic cut-over table" log line at cut-over
 *     time; the console persists it (AsyncTask configData) for the "drop old table" action (R7);</li>
 * <li>{@code failReason} — gh-ost FATAL summary / exit information on FAILED or ABORTED.</li>
 * </ul>
 *
 * curValue/maxValue carry the copied row counts parsed from the gh-ost
 * {@code Copy: N/M pct%; ...} status lines.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class GhostStatusDTO {

    private long    curValue;
    private long    maxValue;
    private boolean useProgress;
    private String  message;

    private String  status;
    private String  phase;
    private String  oldTableName;
    private String  failReason;
}
