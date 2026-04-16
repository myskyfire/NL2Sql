package com.nl2sql.core.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.core.llm.LLMService;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 数据源澄清 Tool - 使用LLM智能选择或列出可用数据源
 */
@Slf4j
@Component
public class DatasourceClarificationTool {
    
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    private final LLMService llmService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    public DatasourceClarificationTool(LLMService llmService) {
        this.llmService = llmService;
    }
    
    /**
     * 智能选择数据源或列出选项
     * 
     * @param userQuery 用户原始问题
     * @return JSON格式的澄清响应
     */
    @Tool("根据用户问题智能选择数据源。如果意图明确则推荐对应数据源并请求确认；如果无法判断则列出所有数据源供选择。输入用户问题")
    public String clarifyDatasource(String userQuery) {
        try {
            log.info("[DatasourceClarification] 分析用户问题: {}", userQuery);
                
            List<Map<String, Object>> datasources = getActiveDatasources();
                
            if (datasources.isEmpty()) {
                return "{\"status\":\"error\",\"message\":\"没有可用的数据源\"}";
            }
                
            // 情况1：只有一个数据源，直接使用（返回JSON，后端自动执行）
            if (datasources.size() == 1) {
                Map<String, Object> ds = datasources.get(0);
                Long dsId = ((Number) ds.get("id")).longValue();
                
                Map<String, Object> response = new HashMap<>();
                response.put("status", "clarification_needed");
                response.put("clarificationType", "datasource_recommendation");
                response.put("message", String.format(
                    "✅ 检测到唯一数据源：%s",
                    formatDatasourceInfo(ds)
                ));
                response.put("recommendedDatasourceId", dsId);
                response.put("autoExecuted", true); // ✅ 标记已自动执行
                
                String result = objectMapper.writeValueAsString(response);
                log.info("[DatasourceClarification] 唯一数据源，自动执行: {}", result);
                return result;
            }
                
            // 情况2：使用LLM智能匹配
            Map<String, Object> matchedDs = llmIntelligentMatch(userQuery, datasources);
                
            if (matchedDs != null) {
                // ✅ 匹配成功，返回推荐并强制 LLM 立即执行查询
                Map<String, Object> response = new HashMap<>();
                response.put("status", "clarification_needed");
                response.put("clarificationType", "datasource_recommendation");
                            
                String message = String.format(
                    "🎯 已自动选择数据源：%s\n\n%s\n\n⚠️ 重要：请立即调用 execute_standard_query(question=\"%s\", datasourceId=%s) 执行查询，不要输出任何确认问句！",
                    matchedDs.get("name"),
                    formatDatasourceInfo(matchedDs),
                    userQuery,
                    matchedDs.get("id")
                );
                response.put("message", message);
                response.put("recommendedDatasourceId", ((Number) matchedDs.get("id")).longValue());
                            
                String result = objectMapper.writeValueAsString(response);
                log.info("[DatasourceClarification] 推荐数据源: {}", result);
                return result;
            }
                
            // 情况3：LLM无法确定，返回所有候选让用户选择
            return buildDatasourceSelectionResponse(datasources);
                
        } catch (Exception e) {
            log.error("[DatasourceClarification] 处理失败", e);
            return "{\"status\":\"error\",\"message\":\"无法获取数据源列表: " + escapeJson(e.getMessage()) + "\"}";
        }
    }
    
    /**
     * 获取所有激活的数据源（包含business_category字段）
     */
    private List<Map<String, Object>> getActiveDatasources() {
        return jdbcTemplate.queryForList(
            "SELECT id, name, db_type, host, port, database_name, description, business_category FROM datasource_config WHERE is_active = 1 ORDER BY name"
        );
    }
    
    /**
     * 使用LLM智能匹配数据源（分层传递策略）
     */
    private Map<String, Object> llmIntelligentMatch(String userQuery, List<Map<String, Object>> datasources) {
        try {
            // ✅ 第一层：构建数据源基本信息
            StringBuilder datasourceInfo = new StringBuilder();
            for (Map<String, Object> ds : datasources) {
                datasourceInfo.append(String.format(
                    "- ID: %d, 名称: %s, 数据库: %s, 类型: %s, 说明: %s, 业务类别: %s\n",
                    ds.get("id"),
                    ds.get("name"),
                    ds.get("database_name"),
                    ds.get("db_type"),
                    ds.get("description") != null ? ds.get("description") : "无",
                    ds.get("business_category") != null ? ds.get("business_category") : "无"
                ));
            }
            
            // ✅ 第一层Prompt：基于数据源基本信息进行初步匹配
            String firstLayerPrompt = String.format(
                "你是一个数据源选择助手。根据用户问题和可用数据源列表，判断应该使用哪个数据源。\n\n" +
                "## 用户问题\n%s\n\n" +
                "## 可用数据源（基本信息）\n%s\n\n" +
                "## 任务\n" +
                "1. 分析用户问题的意图和业务领域\n" +
                "2. 如果只有一个数据源明显匹配，返回其ID\n" +
                "3. 如果有多个候选或无法确定，返回null并标记需要查看表结构\n\n" +
                "## 输出格式\n" +
                "只返回JSON格式，不要有其他文字：\n" +
                "{\"matched_datasource_id\": 数据源ID或null, \"confidence\": \"high/medium/low\", \"need_table_info\": true/false, \"reason\": \"匹配原因\"}\n\n" +
                "## 示例\n" +
                "用户问：'统计订单数量' -> {\"matched_datasource_id\": 1, \"confidence\": \"high\", \"need_table_info\": false, \"reason\": \"订单查询应使用订单数据库\"}\n" +
                "用户问：'分析销售数据' -> {\"matched_datasource_id\": null, \"confidence\": \"low\", \"need_table_info\": true, \"reason\": \"销售相关数据可能在多个数据源中\"}",
                userQuery,
                datasourceInfo.toString()
            );
            
            log.info("[DatasourceClarification] 第一层：调用LLM进行数据源初步匹配");
            String firstResponse = llmService.generateSQL(firstLayerPrompt);
            log.info("[DatasourceClarification] 第一层LLM响应: {}", firstResponse);
            
            // 解析第一层响应
            Map<String, Object> firstResult = parseLlmResponse(firstResponse);
            
            if (firstResult == null) {
                log.info("[DatasourceClarification] 第一层解析失败，进入第二层");
                return secondLayerMatch(userQuery, datasources);
            }
            
            Integer matchedId = (Integer) firstResult.get("matched_datasource_id");
            Boolean needTableInfo = (Boolean) firstResult.getOrDefault("need_table_info", false);
            
            // 情况1：LLM明确匹配到唯一数据源且不需要表信息
            if (matchedId != null && !needTableInfo) {
                for (Map<String, Object> ds : datasources) {
                    if (((Number) ds.get("id")).intValue() == matchedId) {
                        log.info("[DatasourceClarification] 第一层匹配成功: {}", ds.get("name"));
                        return ds;
                    }
                }
            }
            
            // 情况2：需要查看表结构才能确定，进入第二层
            if (needTableInfo || matchedId == null) {
                log.info("[DatasourceClarification] 需要表结构信息，进入第二层匹配");
                return secondLayerMatch(userQuery, datasources);
            }
            
            log.info("[DatasourceClarification] LLM未能确定数据源");
            return null;
            
        } catch (Exception e) {
            log.error("[DatasourceClarification] LLM匹配失败", e);
            return null; // LLM失败时返回null，降级为列出所有选项
        }
    }
    
    /**
     * ✅ 第二层匹配：包含表结构信息
     */
    private Map<String, Object> secondLayerMatch(String userQuery, List<Map<String, Object>> datasources) {
        try {
            // 构建包含表结构的详细信息
            StringBuilder detailedInfo = new StringBuilder();
            
            for (Map<String, Object> ds : datasources) {
                Long dsId = ((Number) ds.get("id")).longValue();
                String dbName = String.valueOf(ds.get("database_name"));
                
                detailedInfo.append(String.format(
                    "\n### 数据源 [%d] %s\n",
                    ds.get("id"),
                    ds.get("name")
                ));
                detailedInfo.append(String.format("- 数据库: %s\n", dbName));
                detailedInfo.append(String.format("- 类型: %s\n", ds.get("db_type")));
                if (ds.get("description") != null) {
                    detailedInfo.append(String.format("- 说明: %s\n", ds.get("description")));
                }
                if (ds.get("business_category") != null) {
                    detailedInfo.append(String.format("- 业务类别: %s\n", ds.get("business_category")));
                }
                
                // ✅ 查询该数据源的核心表（限制为前10个表，避免Token爆炸）
                try {
                    List<Map<String, Object>> tables = jdbcTemplate.queryForList(
                        "SELECT table_name, table_comment FROM information_schema.tables " +
                        "WHERE table_schema = ? AND table_type = 'BASE TABLE' " +
                        "ORDER BY table_name LIMIT 10",
                        dbName
                    );
                    
                    if (!tables.isEmpty()) {
                        detailedInfo.append("- 核心表:\n");
                        for (Map<String, Object> table : tables) {
                            String tableName = String.valueOf(table.get("table_name"));
                            String tableComment = table.get("table_comment") != null ? 
                                String.valueOf(table.get("table_comment")) : "无说明";
                            detailedInfo.append(String.format("  - %s: %s\n", tableName, tableComment));
                        }
                    } else {
                        detailedInfo.append("- 核心表: 无表或无法访问\n");
                    }
                } catch (Exception e) {
                    log.warn("[DatasourceClarification] 查询数据源{}的表结构失败: {}", dsId, e.getMessage());
                    detailedInfo.append("- 核心表: 查询失败\n");
                }
            }
            
            // ✅ 第二层Prompt：基于详细表结构信息进行精确匹配
            String secondLayerPrompt = String.format(
                "你是一个数据源选择专家。现在提供了更详细的数据源信息（包括核心表结构），请重新判断。\n\n" +
                "## 用户问题\n%s\n\n" +
                "## 可用数据源（含表结构）\n%s\n\n" +
                "## 任务\n" +
                "1. 仔细分析用户问题涉及的表和字段\n" +
                "2. 根据表名和表注释，找到最匹配的数据源\n" +
                "3. 如果仍然无法确定，返回null\n\n" +
                "## 输出格式\n" +
                "只返回JSON格式：\n" +
                "{\"matched_datasource_id\": 数据源ID或null, \"confidence\": \"high/medium/low\", \"reason\": \"详细说明匹配原因，包括涉及的表\"}\n\n" +
                "## 示例\n" +
                "用户问：'统计订单数量' -> {\"matched_datasource_id\": 1, \"confidence\": \"high\", \"reason\": \"数据源1包含orders表（订单表），适合查询订单数据\"}",
                userQuery,
                detailedInfo.toString()
            );
            
            log.info("[DatasourceClarification] 第二层：调用LLM进行精确匹配");
            String secondResponse = llmService.generateSQL(secondLayerPrompt);
            log.info("[DatasourceClarification] 第二层LLM响应: {}", secondResponse);
            
            // 解析第二层响应
            Map<String, Object> secondResult = parseLlmResponse(secondResponse);
            
            if (secondResult != null && secondResult.containsKey("matched_datasource_id")) {
                Integer matchedId = (Integer) secondResult.get("matched_datasource_id");
                if (matchedId != null) {
                    for (Map<String, Object> ds : datasources) {
                        if (((Number) ds.get("id")).intValue() == matchedId) {
                            log.info("[DatasourceClarification] 第二层匹配成功: {}", ds.get("name"));
                            return ds;
                        }
                    }
                }
            }
            
            log.info("[DatasourceClarification] 第二层仍未能确定数据源");
            return null;
            
        } catch (Exception e) {
            log.error("[DatasourceClarification] 第二层匹配失败", e);
            return null;
        }
    }
    
    /**
     * 解析LLM返回的JSON
     */
    private Map<String, Object> parseLlmResponse(String response) {
        try {
            // 去除可能的Markdown代码块
            String cleaned = response.trim();
            if (cleaned.startsWith("```") && cleaned.endsWith("```")) {
                cleaned = cleaned.substring(3, cleaned.length() - 3).trim();
                if (cleaned.startsWith("json")) {
                    cleaned = cleaned.substring(4).trim();
                }
            }
            
            return objectMapper.readValue(cleaned, Map.class);
        } catch (Exception e) {
            log.warn("[DatasourceClarification] 解析LLM响应失败: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * 构建数据源选择响应（JSON格式）
     */
    private String buildDatasourceSelectionResponse(List<Map<String, Object>> datasources) {
        StringBuilder json = new StringBuilder();
        json.append("{\"status\":\"clarification_needed\",\"clarificationType\":\"datasource_selection\",\"message\":\"📋 请选择数据源：\",\"availableDatasources\": [");
        
        for (int i = 0; i < datasources.size(); i++) {
            if (i > 0) json.append(",");
            Map<String, Object> ds = datasources.get(i);
            json.append("{");
            json.append("\"id\":").append(ds.get("id")).append(",");
            json.append("\"name\":\"").append(escapeJson(String.valueOf(ds.get("name")))).append("\",");
            json.append("\"db_type\":\"").append(escapeJson(String.valueOf(ds.get("db_type")))).append("\",");
            json.append("\"database_name\":\"").append(escapeJson(String.valueOf(ds.get("database_name")))).append("\"");
            if (ds.get("description") != null && !String.valueOf(ds.get("description")).isEmpty()) {
                json.append(",\"description\":\"").append(escapeJson(String.valueOf(ds.get("description")))).append("\"");
            }
            json.append("}");
        }
        
        json.append("]}");
        
        log.info("[DatasourceClarification] 返回{}个数据源选项", datasources.size());
        return json.toString();
    }
    
    /**
     * 格式化数据源信息
     */
    private String formatDatasourceInfo(Map<String, Object> ds) {
        StringBuilder info = new StringBuilder();
        info.append(String.format("- 名称: %s\n", ds.get("name")));
        info.append(String.format("- 类型: %s\n", ds.get("db_type")));
        info.append(String.format("- 数据库: %s\n", ds.get("database_name")));
        if (ds.get("description") != null && !String.valueOf(ds.get("description")).isEmpty()) {
            info.append(String.format("- 说明: %s", ds.get("description")));
        }
        return info.toString();
    }
    
    /**
     * 转义 JSON 字符串中的特殊字符
     */
    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }
}
