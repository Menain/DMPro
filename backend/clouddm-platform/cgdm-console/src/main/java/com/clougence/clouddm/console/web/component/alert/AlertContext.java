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
package com.clougence.clouddm.console.web.component.alert;

import lombok.Getter;
import lombok.Setter;

/**
 * Data holder of one {@link AlertPushService#pushAlert} call. The GHOST_LARGE_TABLE_NO_PK
 * scene consumes the ticket/table/stat fields; future scenes add their own fields here
 * (additive — the send chain reads only what its scene needs).
 */
@Getter
@Setter
public class AlertContext {

    /** the ticket the alert was raised on (deep link + event trail) */
    private long   ticketId;
    /** ticket applicant uid — resolved to the username inside the message copy */
    private String applicantUid;

    /** target table (db.table display form) */
    private String dbName;
    private String tableName;

    /** B4-estimate stats captured by the same precheck probe (zero extra round trips) */
    private Long   tableRows;
    private Long   totalBytes;
    private Long   sizeThresholdBytes;
    private Long   rowThreshold;

    /**
     * risk-copy discriminator of the GHOST_LARGE_TABLE_NO_PK scene family:
     * {@link #RISK_NO_UNIQUE_KEY} (no PK/UK) or {@link #RISK_TUNNEL} (SSH/SSL datasource).
     */
    private String riskCode;

    public static final String RISK_NO_UNIQUE_KEY = "NO_UNIQUE_KEY";
    public static final String RISK_TUNNEL        = "TUNNEL_UNSUPPORTED";
}
