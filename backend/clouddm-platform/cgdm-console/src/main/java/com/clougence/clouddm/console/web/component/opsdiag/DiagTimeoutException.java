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
package com.clougence.clouddm.console.web.component.opsdiag;

/**
 * Neutral timeout signal of {@link OpsDiagnosticExecutor}: one remote operation (createSession,
 * a statement, or — in operation-level mode — the whole session body) exceeded its hard timeout.
 * <p>
 * The executor deliberately carries no i18n keys for timeouts: each consuming service translates
 * this signal into its own user-facing key at the boundary where the timeout role is known
 * (B2 {@code SESSION_QUERY_TIMEOUT}, B3 {@code LOCK_QUERY_TIMEOUT} whole-failure vs per-block
 * partialErrors degradation, design 09-19-table-space-usage §1.2).
 */
public class DiagTimeoutException extends RuntimeException {
}
