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
package com.clougence.clouddm.onlineddl.ghost;

import lombok.Getter;

/**
 * Business failure of the GHOST tool carrying a stable machine-readable {@code code} as message
 * prefix ({@code CODE: detail}). The console layer (GhostServiceImpl, S4) maps the prefix onto
 * i18n keys of the design §7 error matrix; tool-session errors surface through
 * {@code ToolUtils.buildError(e)} so the code MUST stay inside {@code getMessage()}.
 */
@Getter
public class GhostToolException extends RuntimeException {

    // ---- codes (stable contract for the console-side i18n translation, design §7) ----
    /** launch payload missing/blank fields (dsId, schema, ddlSql) */
    public static final String CODE_BAD_LAUNCH_CONFIG   = "GHOST_BAD_LAUNCH_CONFIG";
    /** datasource is not plain MySQL (gh-ost is MySQL-only) */
    public static final String CODE_DS_TYPE_UNSUPPORTED = "GHOST_DS_TYPE_UNSUPPORTED";
    /** SSH-tunnel / SSL datasource — not expressible on the gh-ost command line */
    public static final String CODE_TUNNEL_UNSUPPORTED  = "GHOST_TUNNEL_UNSUPPORTED";
    /** not a single ALTER TABLE statement (multi-statement / non-ALTER / exotic identifier) */
    public static final String CODE_STMT_UNSUPPORTED    = "GHOST_STMT_UNSUPPORTED";
    /** db prefix inside the statement differs from the session schema */
    public static final String CODE_SCHEMA_MISMATCH     = "GHOST_SCHEMA_MISMATCH";
    /** gh-ost binary not found / not executable / unsupported OS (Windows) */
    public static final String CODE_BINARY_MISSING      = "GHOST_BINARY_MISSING";
    /** START invoked twice on the same session */
    public static final String CODE_ALREADY_STARTED     = "GHOST_ALREADY_STARTED";
    /** control method (PAUSE/RESUME/STOP) invoked before a successful init */
    public static final String CODE_NOT_INITIALIZED     = "GHOST_NOT_INITIALIZED";
    /** gh-ost process exited non-zero without a user stop request */
    public static final String CODE_EXEC_FAILED         = "GHOST_EXEC_FAILED";
    /** gh-ost was stopped by the user through the panic flag */
    public static final String CODE_ABORTED             = "GHOST_ABORTED";

    private final String code;

    public GhostToolException(String code, String detail){
        super(code + ": " + detail);
        this.code = code;
    }

    public GhostToolException(String code, String detail, Throwable cause){
        super(code + ": " + detail, cause);
        this.code = code;
    }
}
