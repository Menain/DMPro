package com.clougence.clouddm.console.web.model.vo.slowsql;

import java.util.List;

import lombok.Data;

/**
 * Top slow SQL list response.
 */
@Data
public class SlowSqlTopListVO {

    private Long                   totalCount;

    private List<SlowSqlTopRowVO>  rows;
}
