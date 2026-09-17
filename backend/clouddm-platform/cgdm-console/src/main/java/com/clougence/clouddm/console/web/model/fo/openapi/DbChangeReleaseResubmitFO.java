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
package com.clougence.clouddm.console.web.model.fo.openapi;

import java.util.List;

import com.clougence.clouddm.console.web.constants.DmMcpI18nKey;
import com.clougence.clouddm.console.web.global.mcp.model.McpField;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * FO for the open API /releases/resubmit endpoint (production release proxy resubmit).
 * <p>
 * {@code releaseId} identifies the RETURNED release to resubmit. {@code ticketIds} is the
 * final full ticket list (may add new eligible tickets or remove unwanted ones from the
 * original merge list). The caller's puid (from AK/SK) must match the release's primaryUid.
 * The creator/primary/maintainer permission check is skipped (AK/SK is the credential).
 * RELEASE_RESUBMITTED event operator = service account uid; release.creatorUid unchanged.
 */
@Getter
@Setter
public class DbChangeReleaseResubmitFO {

    @McpField(value = DmMcpI18nKey.F_RELEASE_ID_DESC, required = true)
    @NotNull(message = "releaseId is required")
    private Long releaseId;

    @McpField(value = DmMcpI18nKey.F_TICKET_IDS_DESC, required = true)
    @NotEmpty(message = "ticketIds must not be empty")
    private List<Long> ticketIds;

    @McpField(value = DmMcpI18nKey.F_TITLE_DESC, required = false)
    private String title;
}
