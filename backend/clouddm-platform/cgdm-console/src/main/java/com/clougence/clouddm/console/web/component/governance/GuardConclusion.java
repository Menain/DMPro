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

import java.util.ArrayList;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/**
 * Gate-two evaluation conclusion (Phase 7, design D1).
 * <p>
 * Guard returns this without throwing — the touchpoint method decides the disposal.
 * The conclusion carries per-gate results for preflight_result persistence (design D8).
 */
@Getter
@Setter
public class GuardConclusion {

    private boolean          pass;
    private List<GateItem>   items     = new ArrayList<>();
    private String           summary;

    public boolean isDeny() {
        return !pass;
    }

    public static GuardConclusion pass() {
        GuardConclusion c = new GuardConclusion();
        c.setPass(true);
        c.setSummary("PASS");
        return c;
    }

    public static GuardConclusion deny(String summary) {
        GuardConclusion c = new GuardConclusion();
        c.setPass(false);
        c.setSummary(summary);
        return c;
    }

    public void addItem(GateItem item) {
        this.items.add(item);
    }

    public GateItem lastItem() {
        if (this.items.isEmpty()) {
            return null;
        }
        return this.items.get(this.items.size() - 1);
    }
}
