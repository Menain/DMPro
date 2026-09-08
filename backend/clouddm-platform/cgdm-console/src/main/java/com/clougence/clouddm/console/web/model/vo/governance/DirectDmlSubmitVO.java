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
package com.clougence.clouddm.console.web.model.vo.governance;

import lombok.Getter;
import lombok.Setter;

/**
 * Result of a direct DML submit — three-object summary for the frontend success page.
 * riskLevel: NORMAL (≤warn or unconfigured) / HIGH (warn<x≤block).
 * Phase 9 reads gate_result for the approval form risk-level field; Phase 10 frontend consumes this VO.
 */
@Getter
@Setter
public class DirectDmlSubmitVO {

    private Long    ticketId;
    private Long    revisionId;
    private String  revisionCode;
    private Long    promotionId;
    private String  promotionCode;
    private String  riskLevel;
}
