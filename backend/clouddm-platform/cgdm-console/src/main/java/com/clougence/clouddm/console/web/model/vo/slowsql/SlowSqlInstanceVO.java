package com.clougence.clouddm.console.web.model.vo.slowsql;

import lombok.Data;

/**
 * A datasource that has Tencent Cloud instance mapping configured.
 */
@Data
public class SlowSqlInstanceVO {

    private Long   dsId;

    private String dsName;

    private String region;

    private String cloudInstanceId;
}
