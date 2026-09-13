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
package com.clougence.clouddm.console.web.component.governance.impl;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * In-memory model of schema changes applied by prior SQL statements within the same
 * release stmt (analysisSplitStream can split a single stmt into multiple SQL scripts).
 * <p>
 * Existence checks consult: liveSchema (fetched from production) ⊕ prior deltas.
 * Cross-stmt caching is NOT done — the chained gate always fetches fresh live schema
 * after the prior stmt has executed in production.
 */
class SchemaDeltaModel {

    private final Set<String>           createdTables = new HashSet<>();
    private final Set<String>           droppedTables = new HashSet<>();
    private final Map<String, Set<String>> addedCols   = new HashMap<>();
    private final Map<String, Set<String>> droppedCols = new HashMap<>();
    private final Map<String, Set<String>> addedIdx   = new HashMap<>();
    private final Map<String, Set<String>> droppedIdx = new HashMap<>();

    boolean tableExists(String table) {
        if (table == null) return false;
        if (droppedTables.contains(table)) return false;
        if (createdTables.contains(table)) return true;
        return true; // can't prove non-existence from delta alone — caller checks live schema
    }

    boolean isCreated(String table) {
        return table != null && createdTables.contains(table);
    }

    boolean isDropped(String table) {
        return table != null && droppedTables.contains(table);
    }

    boolean isColumnAdded(String table, String col) {
        if (table == null || col == null) return false;
        Set<String> cols = addedCols.get(table);
        return cols != null && cols.contains(col);
    }

    boolean isColumnDropped(String table, String col) {
        if (table == null || col == null) return false;
        Set<String> cols = droppedCols.get(table);
        return cols != null && cols.contains(col);
    }

    boolean isIndexAdded(String table, String idx) {
        if (table == null || idx == null) return false;
        Set<String> idxs = addedIdx.get(table);
        return idxs != null && idxs.contains(idx);
    }

    boolean isIndexDropped(String table, String idx) {
        if (table == null || idx == null) return false;
        Set<String> idxs = droppedIdx.get(table);
        return idxs != null && idxs.contains(idx);
    }

    void applyCreateTable(String table) {
        if (table == null) return;
        createdTables.add(table);
        droppedTables.remove(table);
    }

    void applyDropTable(String table) {
        if (table == null) return;
        droppedTables.add(table);
        createdTables.remove(table);
    }

    void applyAddColumn(String table, String col) {
        if (table == null || col == null) return;
        addedCols.computeIfAbsent(table, k -> new HashSet<>()).add(col);
        droppedCols.getOrDefault(table, Collections.emptySet()).remove(col);
    }

    void applyDropColumn(String table, String col) {
        if (table == null || col == null) return;
        droppedCols.computeIfAbsent(table, k -> new HashSet<>()).add(col);
        addedCols.getOrDefault(table, Collections.emptySet()).remove(col);
    }

    void applyAddIndex(String table, String idx) {
        if (table == null || idx == null) return;
        addedIdx.computeIfAbsent(table, k -> new HashSet<>()).add(idx);
    }

    void applyDropIndex(String table, String idx) {
        if (table == null || idx == null) return;
        droppedIdx.computeIfAbsent(table, k -> new HashSet<>()).add(idx);
    }

    void applyRenameTable(String oldName, String newName) {
        if (oldName != null) {
            droppedTables.add(oldName);
            createdTables.remove(oldName);
        }
        if (newName != null) {
            createdTables.add(newName);
            droppedTables.remove(newName);
        }
    }
}
