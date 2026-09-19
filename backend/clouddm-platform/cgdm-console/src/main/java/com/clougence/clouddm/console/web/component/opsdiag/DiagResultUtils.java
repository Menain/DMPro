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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.clougence.clouddm.api.sidecar.session.execute.ResultList;
import com.clougence.clouddm.sdk.execute.resultset.echo.Result;
import com.clougence.clouddm.sdk.execute.resultset.echo.ResultMessage;
import com.clougence.clouddm.sdk.execute.resultset.echo.ResultSet;
import com.clougence.clouddm.sdk.execute.resultset.echo.ResultSetMeta;
import com.clougence.clouddm.sdk.execute.resultset.echo.ResultSetRow;
import com.clougence.clouddm.sdk.execute.resultset.echo.ResultSetValue;
import com.clougence.clouddm.sdk.execute.session.MessageLevel;
import com.clougence.utils.StringUtils;

/**
 * SQL-agnostic result parsing for channel-C diagnostics, converged from the byte-identical copies
 * in B2 {@code SessionManageServiceImpl} / B3 {@code LockAnalysisServiceImpl} (design §1.2:
 * extractRows / firstFailureMessage / case-insensitive column mapping live here now).
 */
public final class DiagResultUtils {

    private DiagResultUtils() {
    }

    /** Flattens ResultList (ResultSetMeta + ResultSet) into column-lowercased row maps. */
    public static List<Map<String, String>> extractRows(ResultList resultList) {
        List<Map<String, String>> rows = new ArrayList<>();
        if (resultList == null || resultList.getResultList() == null) {
            return rows;
        }
        List<String> columns = null;
        List<ResultSetRow> rowSet = new ArrayList<>();
        for (Result result : resultList.getResultList()) {
            if (result instanceof ResultSetMeta meta) {
                if (columns == null) {
                    columns = meta.getColumnList();
                }
            } else if (result instanceof ResultSet set) {
                if (set.getRowSet() != null) {
                    rowSet.addAll(set.getRowSet());
                }
            }
        }
        if (columns == null) {
            return rows;
        }
        for (ResultSetRow row : rowSet) {
            Map<String, String> mapped = new LinkedHashMap<>();
            List<ResultSetValue> data = row.getData() == null ? Collections.emptyList() : row.getData();
            for (int i = 0; i < columns.size() && i < data.size(); i++) {
                String column = columns.get(i);
                if (column == null) {
                    continue;
                }
                ResultSetValue value = data.get(i);
                mapped.put(column.toLowerCase(Locale.ROOT), value == null ? null : value.getValue());
            }
            rows.add(mapped);
        }
        return rows;
    }

    /** First failure message of a ResultList (ActionServiceImpl / SqlRowEstimator judgement), or null when all ok. */
    public static String firstFailureMessage(ResultList resultList) {
        if (resultList == null || resultList.getResultList() == null) {
            return "empty result";
        }
        for (Result result : resultList.getResultList()) {
            if (result == null) {
                continue;
            }
            if (!result.isSuccess()) {
                return StringUtils.isBlank(result.getMessage()) ? "query failed" : result.getMessage();
            }
            if (result instanceof ResultMessage message && message.getLevel() == MessageLevel.Error) {
                return StringUtils.isBlank(message.getMessage()) ? "query failed" : message.getMessage();
            }
        }
        return null;
    }
}
