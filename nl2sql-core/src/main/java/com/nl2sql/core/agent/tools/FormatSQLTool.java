package com.nl2sql.core.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * SQL 格式化 Tool - 原子能力：格式化SQL语句提高可读性
 */
@Slf4j
@Component
public class FormatSQLTool {
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Tool("格式化SQL语句，提高可读性。输入SQL语句，返回格式化后的SQL（关键字大写、适当换行和缩进）")
    public String formatSQL(String sql) {
        try {
            log.info("[FormatSQLTool] 格式化SQL");
            
            if (sql == null || sql.trim().isEmpty()) {
                return "{\"success\":false,\"error\":\"SQL不能为空\"}";
            }
            
            // 简单格式化规则
            String formatted = sql.trim();
            
            // 关键字大写
            String[] keywords = {"select", "from", "where", "join", "left", "right", "inner", 
                                "outer", "on", "and", "or", "group", "by", "order", "having",
                                "limit", "offset", "union", "all", "distinct", "as", "in",
                                "between", "like", "is", "null", "not", "exists", "case",
                                "when", "then", "else", "end"};
            
            for (String keyword : keywords) {
                formatted = formatted.replaceAll("(?i)\\b" + keyword + "\\b", keyword.toUpperCase());
            }
            
            // 在主要关键字前添加换行
            formatted = formatted.replaceAll("(?i)\\b(SELECT|FROM|WHERE|JOIN|LEFT JOIN|RIGHT JOIN|INNER JOIN|GROUP BY|ORDER BY|HAVING|LIMIT)\\b", "\n$1");
            
            // 清理多余空行
            formatted = formatted.replaceAll("\n\s*\n", "\n");
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("originalSql", sql);
            result.put("formattedSql", formatted.trim());
            
            return objectMapper.writeValueAsString(result);
            
        } catch (Exception e) {
            log.error("[FormatSQLTool] 格式化失败", e);
            return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }
}
