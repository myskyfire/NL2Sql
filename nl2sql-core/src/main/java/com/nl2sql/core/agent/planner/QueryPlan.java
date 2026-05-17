package com.nl2sql.core.agent.planner;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * QueryPlan - 结构化的查询计划
 *
 * 由 PlannerAgent 生成，供 WorkflowEngine 和 Worker 执行
 */
@Data
public class QueryPlan {

    /**
     * 数据源信息
     */
    private DatasourceInfo datasource;

    /**
     * 涉及的表及JOIN关系
     */
    private List<TablePlan> tables = new ArrayList<>();

    /**
     * SQL执行步骤（单步或多步）
     */
    private List<SqlStep> sqlSteps = new ArrayList<>();

    /**
     * 图表计划
     */
    private ChartPlan chart;

    /**
     * 是否需要AI总结
     */
    private boolean needSummary;

    /**
     * 复杂度等级
     */
    private ComplexityLevel complexity;

    /**
     * 用户原始问题（透传给Worker）
     */
    private String userQuestion;

    public enum ComplexityLevel {
        SIMPLE,    // 单表查询，直接执行
        MODERATE,  // 多表JOIN，需要Plan
        COMPLEX    // 多步聚合+图表+总结，需要完整Pipeline
    }

    @Data
    public static class DatasourceInfo {
        private Long id;
        private String name;
    }

    @Data
    public static class TablePlan {
        private String tableName;
        private String joinType;      // LEFT/INNER/CROSS/null（第一张表）
        private String joinCondition; // ON a.id = b.order_id
        private List<String> columns; // 需要的字段
    }

    @Data
    public static class SqlStep {
        private int order;
        private String description;   // 这步要做什么
        private String generatedSql;  // 生成后填入
        private String validatedSql;  // 校验/纠错后填入
        private boolean requiresRetry;
        private String lastError;
    }

    @Data
    public static class ChartPlan {
        private boolean needed;
        private ChartType type;
        
        @JsonProperty("xField")
        private String xfield;
        
        @JsonProperty("yField")
        private String yfield;
        
        @JsonProperty("groupByField")
        private String groupByField;
        
        private String title;

        // 兼容旧代码的 getter 方法
        public String getXField() {
            return xfield;
        }
        
        public void setXField(String xField) {
            this.xfield = xField;
        }
        
        public String getYField() {
            return yfield;
        }
        
        public void setYField(String yField) {
            this.yfield = yField;
        }

        public enum ChartType {
            BAR, LINE, PIE, TABLE, SCATTER, AREA, UNKNOWN
        }
    }
}
