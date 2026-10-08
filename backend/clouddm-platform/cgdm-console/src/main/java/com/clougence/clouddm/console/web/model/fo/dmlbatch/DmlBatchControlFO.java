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
package com.clougence.clouddm.console.web.model.fo.dmlbatch;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * B7 N2 control-plane request (design §6.1 — the ticketId+groupId[+segmentIndex] locator shape):
 * the batch task is resolved through {@code DmlBatchService.findTaskForTicketGroup} (the C6
 * binding lookup — a raw taskId body was rejected: the ticket-chain locators keep the tenant and
 * segment-state validation on the ticket's own coordinates, and the frontend N3 consumes the
 * segment view which carries exactly this triple).
 * <p>
 * {@code segmentIndex} is nullable — null resolves the NEWEST DML_BATCH task bound to
 * (ticketId, groupId) (a group hosts at most one executing DML_BATCH segment at a time, so the
 * ambiguity is structural-free in v1; the exact index pins the lookup for forensics).
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = false)
public class DmlBatchControlFO {

    @NotNull
    private Long    ticketId;

    @NotNull
    private Long    groupId;

    /** nullable — null = the newest task bound to (ticketId, groupId). */
    private Integer segmentIndex;
}
