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
package com.clougence.clouddm.console.web.model.vo;

import lombok.Getter;
import lombok.Setter;

/**
 * C8 M3 (task 10-13-audit-chain-enhancement, 裁决 3 / design D4): the audit-detail endpoint
 * payload — the readable face of one dm_mon_op_audit row's log_info (before/after values,
 * override reasons, chain annotations). Shape mirrors the historical LogViewVO the frontend
 * detail modal was built against (desc / path / content), so the modal rewires with zero
 * structural change.
 * <p>
 * {@code content} is the log_info JSON AFTER the AuditSanitizeUtils sanitization兜底 (pre-C1
 * stock rows may carry plaintext credentials — they are masked on read) and AFTER the
 * audit-detail-label dictionary key translation (the old/new field labels — the original
 * RdpLogViewServiceImpl.buildContent semantics).
 */
@Getter
@Setter
public class AuditDetailVO {

    private Long   id;

    /** the row's operationUri (semantic system-plane marker or the real HTTP URI). */
    private String path;

    /** i18n description line (RDP_CONSOLE_OPERATION_LOG_DESC — the historical modal copy). */
    private String desc;

    /** sanitized + label-translated log_info JSON; null when the row carries no body. */
    private String content;
}
