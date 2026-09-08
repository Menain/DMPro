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

import java.util.List;

import lombok.Getter;
import lombok.Setter;

/**
 * Read-only split preview result — change type + per-statement rows with execution config summary.
 * Does not persist anything; response SQL may be truncated for display.
 */
@Getter
@Setter
public class SplitPreviewVO {

    private String                 changeType;
    private List<StmtPreviewVO>     stmts;

    @Getter
    @Setter
    public static class StmtPreviewVO {

        private int                stmtIndex;
        private String            sql;
        private String            changeType;
        private ExecConfigSummary execConfig;
    }

    /**
     * D15 execution-config summary derived from the overall change type.
     * DML → enableTransactional=true; DDL/MIXED → false; errorStrategy=NONE always.
     */
    @Getter
    @Setter
    public static class ExecConfigSummary {

        private boolean enableTransactional;
        private String  errorStrategy;
    }
}
