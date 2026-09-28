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
package com.clougence.clouddm.console.web.model.vo.ghost;

import lombok.Getter;
import lombok.Setter;

/**
 * Target-table statistics in the B4 MySQL estimate caliber (information_schema.TABLES — InnoDB
 * estimates, may be up to 24h stale; the platform never runs ANALYZE). {@code estimatedDiskBytes}
 * is the R3 disk-headroom hint: total × 2 (the shadow table needs roughly one extra copy of the
 * table while the original stays in place).
 */
@Getter
@Setter
public class GhostTableStatVO {

    private String tableName;
    private Long   dataLength;
    private Long   indexLength;
    private Long   tableRows;
    /** dataLength + indexLength (null when either side is unknown) */
    private Long   totalBytes;
    /** totalBytes × 2 — the migration-time disk headroom hint (frontend display only) */
    private Long   estimatedDiskBytes;
}
