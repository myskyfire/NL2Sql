package com.nl2sql.core.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * SQL 差异对比 Tool - 原子能力：对比两个SQL的差异
 */
@Slf4j
@Component
public class CompareSQLTool {
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Tool("对比两个SQL语句的差异。输入原始SQL和新SQL，返回差异分析包括字段变化、条件变化等")
    public String compareSQL(String originalSql, String newSql) {
        try {
            log.info("[CompareSQLTool] 对比SQL差异");
            
            if (originalSql == null || newSql == null) {
                return ToolResponseBuilder.error("NULL_SQL", "SQL不能为空")
                    .addMetadata("toolName", "compare_sql")
                    .build();
            }
            
            List<String> differences = new ArrayList<>();
            
            // 1. 检查SELECT字段
            String origSelect = extractSelectFields(originalSql);
            String newSelect = extractSelectFields(newSql);
            if (!origSelect.equals(newSelect)) {
                differences.add("SELECT字段发生变化");
            }
            
            // 2. 检查FROM表
            String origFrom = extractFromTable(originalSql);
            String newFrom = extractFromTable(newSql);
            if (!origFrom.equals(newFrom)) {
                differences.add("FROM表发生变化: " + origFrom + " -> " + newFrom);
            }
            
            // 3. 检查WHERE条件
            String origWhere = extractWhereClause(originalSql);
            String newWhere = extractWhereClause(newSql);
            if (!origWhere.equals(newWhere)) {
                differences.add("WHERE条件发生变化");
            }
            
            // 4. 检查是否有JOIN
            boolean origHasJoin = originalSql.toUpperCase().contains("JOIN");
            boolean newHasJoin = newSql.toUpperCase().contains("JOIN");
            if (origHasJoin != newHasJoin) {
                differences.add("JOIN操作" + (newHasJoin ? "新增" : "移除"));
            }
            
            // 5. 检查是否有GROUP BY
            boolean origHasGroup = originalSql.toUpperCase().contains("GROUP BY");
            boolean newHasGroup = newSql.toUpperCase().contains("GROUP BY");
            if (origHasGroup != newHasGroup) {
                differences.add("GROUP BY" + (newHasGroup ? "新增" : "移除"));
            }
            
            // ✅ 构建统一响应
            Map<String, Object> data = new HashMap<>();
            data.put("isIdentical", differences.isEmpty());
            data.put("differenceCount", differences.size());
            data.put("differences", differences);
            
            return ToolResponseBuilder.success("data")
                .withData(data)
                .addMetadata("toolName", "compare_sql")
                .build();
            
        } catch (Exception e) {
            log.error("[CompareSQLTool] 对比失败", e);
            return ToolResponseBuilder.error("COMPARE_ERROR", e.getMessage())
                .addMetadata("toolName", "compare_sql")
                .build();
        }
    }
    
    private String extractSelectFields(String sql) {
        int selectIndex = sql.toUpperCase().indexOf("SELECT");
        int fromIndex = sql.toUpperCase().indexOf("FROM");
        if (selectIndex >= 0 && fromIndex > selectIndex) {
            return sql.substring(selectIndex + 6, fromIndex).trim();
        }
        return "";
    }
    
    private String extractFromTable(String sql) {
        int fromIndex = sql.toUpperCase().indexOf("FROM");
        int whereIndex = sql.toUpperCase().indexOf("WHERE");
        if (fromIndex >= 0) {
            int endIndex = whereIndex > fromIndex ? whereIndex : sql.length();
            return sql.substring(fromIndex + 4, endIndex).trim();
        }
        return "";
    }
    
    private String extractWhereClause(String sql) {
        int whereIndex = sql.toUpperCase().indexOf("WHERE");
        if (whereIndex >= 0) {
            return sql.substring(whereIndex).trim();
        }
        return "";
    }
}
