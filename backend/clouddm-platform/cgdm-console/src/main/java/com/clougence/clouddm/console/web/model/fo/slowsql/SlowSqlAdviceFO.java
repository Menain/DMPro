package com.clougence.clouddm.console.web.model.fo.slowsql;

import lombok.Data;

/**
 * Query parameters for SQL optimization advice.
 */
@Data
public class SlowSqlAdviceFO {

    private Long   dsId;

    private String sqlText;

    private String schema;
}
