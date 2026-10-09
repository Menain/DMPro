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
package com.clougence.clouddm.console.web.component.bridge;

import lombok.Getter;
import lombok.Setter;

/**
 * flows/create request body (task 10-09-dingtalk-approval-bridge, design §2.1 — single-side
 * proposal, bilateral confirmation at联调). Wire shape:
 * <pre>
 * {"bizType":"TICKET|RELEASE","bizId":"&lt;ticketId|releaseId&gt;","bizNo":"&lt;工单号|releaseNo&gt;",
 *  "title":"...","applicant":{"account":"...","name":"..."},"summary":"≤200 chars",
 *  "context":{"puid":"...","approBiz":"PROD_DML|DM_PROD_RELEASE"}}
 * </pre>
 * {@code (bizType,bizId)} is the bridge-side dedup key — repeated creates for the same pair
 * return the SAME flowId ({@code duplicated=true}) instead of spawning a second DingTalk
 * instance (the double-send safety net behind the local row-level CAS claims).
 */
@Getter
@Setter
public class BridgeCreateRequest {

    private String    bizType;
    private String    bizId;
    private String    bizNo;
    private String    title;
    private Applicant applicant;
    /** SQL/entry summary, ≤200 chars — the ledger list-level caliber (no full SQL, no connection strings). */
    private String    summary;
    private Context   context;

    @Getter
    @Setter
    public static class Applicant {
        /** platform account uid of the applicant (ticket ownerUid / release creator). */
        private String account;
        /** display name (degrades to the raw account when the lookup fails). */
        private String name;

        public Applicant() {
        }

        public Applicant(String account, String name) {
            this.account = account;
            this.name = name;
        }
    }

    @Getter
    @Setter
    public static class Context {
        /** tenant realm (primary uid). */
        private String puid;
        /** ticket type (PROD_DML) or DM_PROD_RELEASE for release approval rows. */
        private String approBiz;

        public Context() {
        }

        public Context(String puid, String approBiz) {
            this.puid = puid;
            this.approBiz = approBiz;
        }
    }
}
