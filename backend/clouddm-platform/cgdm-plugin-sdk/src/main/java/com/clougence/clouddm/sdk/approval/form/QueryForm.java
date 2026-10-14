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
package com.clougence.clouddm.sdk.approval.form;

import com.clougence.clouddm.sdk.approval.ApprovalForm;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class QueryForm extends ApprovalForm {

    private String targetDs;

    private String executeSql;
    private String rollBackSql;
    /**
     * C10 M2 (task 10-14-recovery-type-grading, design D6): the rollback material's recovery-type
     * annotation for the OA approver — a localized label + the constant 未演练 suffix (red line 3:
     * no verified illusion on any surface), assembled by QueryApprovalHandler. The OA form
     * component name ("恢复类型") is an OA-template string — the DingTalk/Feishu templates must
     * carry a matching control for the value to display (联调对齐项; the WeCom template's
     * positional control contract defers its mapping to the same alignment round).
     */
    private String recoveryType;
    private Long   affectCount;
}
