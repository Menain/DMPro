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

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import com.clougence.clouddm.api.common.exception.ErrorMessageException;
import com.clougence.clouddm.base.metadata.ds.DataSourceConfig;
import com.clougence.clouddm.console.web.component.analysis.QueryAnalysisService;
import com.clougence.clouddm.console.web.component.governance.GovSqlHashUtils;
import com.clougence.clouddm.console.web.component.governance.GovSplitResult;
import com.clougence.clouddm.console.web.component.governance.GovStmtRow;
import com.clougence.clouddm.console.web.component.governance.GovStmtSplitService;
import com.clougence.clouddm.platform.dal.model.dbchange.ChangeType;
import com.clougence.clouddm.sdk.execute.session.QueryArg;
import com.clougence.clouddm.sdk.sql.parser.SplitQueryType;
import com.clougence.clouddm.sdk.sql.parser.SplitScript;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class GovStmtSplitServiceImpl implements GovStmtSplitService {

    @Resource
    private QueryAnalysisService queryAnalysisService;

    @Override
    public GovSplitResult split(DataSourceConfig dsConfig, String sqlText) {
        List<SplitScript> scripts;
        try (StringReader reader = new StringReader(sqlText);
             Stream<SplitScript> stream = this.queryAnalysisService.analysisSplitStream(
                 dsConfig, reader, Collections.<QueryArg>emptyList(), 1, 0)) {
            scripts = stream.toList();
        } catch (Exception e) {
            log.error("Governance SQL split failed", e);
            throw new ErrorMessageException("SQL split failed: " + e.getMessage());
        }

        if (scripts.isEmpty()) {
            throw new ErrorMessageException("Governance ticket requires at least one DDL or DML statement");
        }

        List<GovStmtRow> rows = new ArrayList<>(scripts.size());
        boolean hasDdl = false;
        boolean hasDml = false;

        for (SplitScript script : scripts) {
            Set<SplitQueryType> types = script.getType();
            boolean stmtIsDml = false;
            boolean stmtIsDdl = false;

            if (types != null) {
                for (SplitQueryType type : types) {
                    if (isDmlType(type)) {
                        stmtIsDml = true;
                    } else if (isDdlType(type)) {
                        stmtIsDdl = true;
                    } else {
                        throw new ErrorMessageException(
                            "Governance ticket only accepts DDL and DML, rejected statement type: " + type.name());
                    }
                }
            }

            if (stmtIsDml) {
                hasDml = true;
            }
            if (stmtIsDdl) {
                hasDdl = true;
            }

            GovStmtRow row = new GovStmtRow();
            row.setStmtIndex((int) script.getIndex() + 1);
            row.setStmtText(script.getScript());
            row.setStmtHash(GovSqlHashUtils.hash(script.getScript()));
            if (stmtIsDml && stmtIsDdl) {
                row.setChangeType(ChangeType.MIXED);
            } else if (stmtIsDml) {
                row.setChangeType(ChangeType.DML);
            } else {
                row.setChangeType(ChangeType.DDL);
            }
            rows.add(row);
        }

        GovSplitResult result = new GovSplitResult();
        result.setStmts(rows);
        if (hasDml && hasDdl) {
            result.setChangeType(ChangeType.MIXED);
        } else if (hasDml) {
            result.setChangeType(ChangeType.DML);
        } else {
            result.setChangeType(ChangeType.DDL);
        }
        return result;
    }

    private static boolean isDmlType(SplitQueryType type) {
        return switch (type) {
            case INSERT, UPDATE, DELETE, MERGE -> true;
            default -> false;
        };
    }

    private static boolean isDdlType(SplitQueryType type) {
        if (isDmlType(type)) {
            return false;
        }
        if (type == SplitQueryType.ADMIN_PERFORMANCE) {
            return false;
        }
        String name = type.name();
        return name.startsWith("CREATE_")
            || name.startsWith("ALTER_")
            || name.startsWith("DROP_")
            || name.startsWith("RENAME_")
            || name.startsWith("COMMENT_")
            || name.startsWith("TRUNCATE_")
            || name.startsWith("ADD_")
            || name.startsWith("ADMIN_")
            || type == SplitQueryType.GRANT
            || type == SplitQueryType.REVOKE
            || type == SplitQueryType.TRANSFER_PRIVILEGE;
    }
}
