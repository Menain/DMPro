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

/**
 * A single precondition violation detected during schema precheck.
 * <p>
 * Produces a single-line readable text for exec_detail storage, e.g.:
 * {@code [sql#1] ALTER TABLE t ADD COLUMN c — table t: expected to exist, actual missing}
 */
public final class SchemaViolation {

    private final int    sqlIndex;       // 1-based index within the stmt's split scripts
    private final String statementHead;  // first 80 chars of the SQL, for identification
    private final String table;
    private final String object;         // column or index name (may be null)
    private final String expected;
    private final String actual;

    public SchemaViolation(int sqlIndex, String statementHead, String table, String object, String expected, String actual) {
        this.sqlIndex = sqlIndex;
        this.statementHead = statementHead == null ? "" : statementHead;
        this.table = table == null ? "" : table;
        this.object = object;
        this.expected = expected == null ? "" : expected;
        this.actual = actual == null ? "" : actual;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[sql#").append(sqlIndex).append("] ");
        // Truncate statement head to 80 chars
        String head = statementHead.length() > 80 ? statementHead.substring(0, 80) : statementHead;
        sb.append(head);
        sb.append(" — ");
        if (table != null && !table.isEmpty()) {
            sb.append("table ").append(table);
        }
        if (object != null && !object.isEmpty()) {
            sb.append(", ").append(object);
        }
        sb.append(": expected ").append(expected);
        sb.append(", actual ").append(actual);
        return sb.toString();
    }

    // Getters for testing

    public int getSqlIndex() {
        return sqlIndex;
    }

    public String getStatementHead() {
        return statementHead;
    }

    public String getTable() {
        return table;
    }

    public String getObject() {
        return object;
    }

    public String getExpected() {
        return expected;
    }

    public String getActual() {
        return actual;
    }
}
