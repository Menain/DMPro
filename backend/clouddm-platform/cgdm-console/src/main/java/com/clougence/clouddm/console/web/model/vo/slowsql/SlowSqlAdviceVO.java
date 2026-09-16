package com.clougence.clouddm.console.web.model.vo.slowsql;

import java.util.List;

import lombok.Data;

/**
 * SQL optimization advice response.
 */
@Data
public class SlowSqlAdviceVO {

    private List<AdviceItem> advices;

    private List<String>    comments;

    private CostInfo        cost;

    private String          sqlPlan;

    @Data
    public static class AdviceItem {

        private String       tableName;

        private String       tableSchema;

        private List<String> indexSqls;
    }

    @Data
    public static class CostInfo {

        private Double before;

        private Double after;

        private Double ratio;
    }
}
