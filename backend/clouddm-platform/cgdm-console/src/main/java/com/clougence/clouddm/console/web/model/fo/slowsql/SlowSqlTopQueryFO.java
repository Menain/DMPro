package com.clougence.clouddm.console.web.model.fo.slowsql;

import lombok.Data;

/**
 * Query parameters for Top slow SQL list.
 */
@Data
public class SlowSqlTopQueryFO {

    private Long    dsId;

    private String  startTime;

    private String  endTime;

    private String  sortBy;

    private String  orderBy;

    private String  schema;

    private Integer limit;

    private Integer offset;
}
