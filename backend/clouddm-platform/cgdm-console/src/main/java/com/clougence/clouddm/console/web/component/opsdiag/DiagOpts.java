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

import java.util.Map;

import com.clougence.clouddm.sdk.service.secrules.Requester;
import com.clougence.schema.umi.struts.UmiTypes;

/**
 * Immutable options of one {@link OpsDiagnosticExecutor#runInSession} run. The defaults are the
 * B2/B3 read-only diagnostic shape (autoCommit=true, no rollback, CONSOLE requester, per-statement
 * timeout); the {@code rollbackOnClose} / {@code levelsParam} switches exist for the ticket-domain
 * family (SqlRowEstimator / DmlExplainPreInitHandler) whose migration is deferred (design §1.3 C4).
 */
public final class DiagOpts {

    /** Default hard timeout (seconds) per wrapped remote operation — the B2/B3 value. */
    public static final int DEFAULT_TIMEOUT_SECONDS = 10;

    private final int                     timeoutSeconds;
    private final Requester               requester;
    private final boolean                 autoCommit;
    private final boolean                 rollbackOnClose;
    /**
     * Timeout granularity (research channel-c-refactor-surface R1): {@code false} (default, B3/B4
     * shape) wraps createSession and every statement individually; {@code true} (B2 shape) wraps
     * createSession + the whole body + the finally release in ONE shared budget, so a serial
     * multi-statement operation can never exceed timeoutSeconds wall-clock in total.
     */
    private final boolean                 operationLevelTimeout;
    /** Non-null switches session-context construction to {@code DmDsUtils.createSessionCtx} (default-db/schema params, C4 shape). */
    private final Map<UmiTypes, Object>   levelsParam;
    /**
     * Target database for the session connection (nullable). When non-blank the executor sets
     * {@code ctx.rdbCatalog}, which {@code QueryServiceImpl.sessionConfigOverrides} turns into a
     * DEFAULT_DATABASE config override — the session connects to that database instead of the
     * datasource default (the same mechanism the workbench uses when switching database). PG is
     * single-database per connection, so the B4 table-level drill-down needs this to reach the
     * selected database; MySQL ignores it (cross-database queries work from any connection).
     */
    private final String                  catalog;

    private DiagOpts(Builder builder) {
        this.timeoutSeconds = builder.timeoutSeconds;
        this.requester = builder.requester;
        this.autoCommit = builder.autoCommit;
        this.rollbackOnClose = builder.rollbackOnClose;
        this.operationLevelTimeout = builder.operationLevelTimeout;
        this.levelsParam = builder.levelsParam;
        this.catalog = builder.catalog;
    }

    public static Builder builder() {
        return new Builder();
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public Requester getRequester() {
        return requester;
    }

    public boolean isAutoCommit() {
        return autoCommit;
    }

    public boolean isRollbackOnClose() {
        return rollbackOnClose;
    }

    public boolean isOperationLevelTimeout() {
        return operationLevelTimeout;
    }

    public Map<UmiTypes, Object> getLevelsParam() {
        return levelsParam;
    }

    public String getCatalog() {
        return catalog;
    }

    public static final class Builder {

        private int                   timeoutSeconds      = DEFAULT_TIMEOUT_SECONDS;
        private Requester             requester           = Requester.CONSOLE;
        private boolean               autoCommit          = true;
        private boolean               rollbackOnClose     = false;
        private boolean               operationLevelTimeout = false;
        private Map<UmiTypes, Object> levelsParam;
        private String                catalog;

        private Builder() {
        }

        public Builder timeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
            return this;
        }

        public Builder requester(Requester requester) {
            this.requester = requester;
            return this;
        }

        public Builder autoCommit(boolean autoCommit) {
            this.autoCommit = autoCommit;
            return this;
        }

        public Builder rollbackOnClose(boolean rollbackOnClose) {
            this.rollbackOnClose = rollbackOnClose;
            return this;
        }

        public Builder operationLevelTimeout(boolean operationLevelTimeout) {
            this.operationLevelTimeout = operationLevelTimeout;
            return this;
        }

        public Builder levelsParam(Map<UmiTypes, Object> levelsParam) {
            this.levelsParam = levelsParam;
            return this;
        }

        public Builder catalog(String catalog) {
            this.catalog = catalog;
            return this;
        }

        public DiagOpts build() {
            return new DiagOpts(this);
        }
    }
}
