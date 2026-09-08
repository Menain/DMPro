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
package com.clougence.clouddm.console.web.component.governance;

import lombok.Getter;
import lombok.Setter;

/**
 * Single gate-two evaluation result (Phase 7, design D1).
 * status ∈ PASS / DENY / SKIPPED (SKIPPED = a later gate after a prior DENY).
 */
@Getter
@Setter
public class GateItem {

    private String item;
    private boolean pass;
    private String evidence;
    private long   ts;
    private String status;

    public static GateItem pass(String item, String evidence) {
        GateItem g = new GateItem();
        g.setItem(item);
        g.setPass(true);
        g.setEvidence(evidence);
        g.setTs(System.currentTimeMillis());
        g.setStatus("PASS");
        return g;
    }

    public static GateItem deny(String item, String evidence) {
        GateItem g = new GateItem();
        g.setItem(item);
        g.setPass(false);
        g.setEvidence(evidence);
        g.setTs(System.currentTimeMillis());
        g.setStatus("DENY");
        return g;
    }

    public static GateItem skipped(String item) {
        GateItem g = new GateItem();
        g.setItem(item);
        g.setPass(false);
        g.setEvidence("skipped — prior gate DENY");
        g.setTs(System.currentTimeMillis());
        g.setStatus("SKIPPED");
        return g;
    }

    public boolean isDeny() {
        return !pass;
    }
}
