package com.clougence.clouddm.console.web.model.vo.slowsql;

import lombok.Data;

/**
 * A single row in the Top slow SQL aggregated list.
 */
@Data
public class SlowSqlTopRowVO {

    private String  md5;

    private String  schema;

    private String  sqlTemplate;

    private String  sqlText;

    private Long    execTimes;

    private Float   queryTime;

    private Float   queryTimeAvg;

    private Float   queryTimeMax;

    private Float   lockTime;

    private Float   lockTimeAvg;

    private Long    rowsExamined;

    private Float   rowsExaminedAvg;

    private Long    rowsSent;

    private Float   rowsSentAvg;
}
