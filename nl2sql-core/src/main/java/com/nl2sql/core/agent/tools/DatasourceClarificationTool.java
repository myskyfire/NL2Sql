package com.nl2sql.core.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.core.config.TableSelectionConfig;
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
    
    @Autowired
    private TableSelectionConfig tableSelectionConfig;
    
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
    /**
     * ✅ 重构：统一返回JSON结构，便于LLM和前端统一处理
     */
    public String clarifyDatasource(String userQuery) {
        try {
            log.info("[DatasourceClarification] 分析用户问题: {}", userQuery);
                
            List<Map<String, Object>> datasources = getActiveDatasources();
                
            if (datasources.isEmpty()) {
                return buildErrorResponse("没有可用的数据源，请联系管理员配置");
            }
                
            // 情况1：只有一个数据源，自动选择
            if (datasources.size() == 1) {
                Map<String, Object> ds = datasources.get(0);
                Long dsId = ((Number) ds.get("id")).longValue();
                String dsName = (String) ds.get("name");
                
                return buildAutoSelectedResponse(dsId, dsName, formatDatasourceInfo(ds), true);
            }
                
            // 情况2：使用LLM智能匹配
            Map<String, Object> matchedDs = llmIntelligentMatch(userQuery, datasources);
                        
            if (matchedDs != null) {
                Long dsId = ((Number) matchedDs.get("id")).longValue();
                String dsName = (String) matchedDs.get("name");
                return buildAutoSelectedResponse(dsId, dsName, formatDatasourceInfo(matchedDs), false);
            }
                
            // 情况3：LLM无法确定，返回所有候选让用户选择
            return buildDatasourceSelectionResponse(datasources);
                
        } catch (Exception e) {
            log.error("[DatasourceClarification] 处理失败", e);
            return buildErrorResponse("无法获取数据源列表 - " + e.getMessage());
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
                "## 业务领域映射规则（重要！）\n" +
                "- **销售/订单/交易类**：销售额、订单数、GMV、成交金额、客户购买 → 优先匹配包含'订单/交易/trade/order'的数据源\n" +
                "- **财务/会计类**：利润、成本、资产负债、财务报表、会计科目 → 优先匹配包含'财务/会计/finance/accounting'的数据源\n" +
                "- **用户/会员类**：用户数、DAU、活跃度、注册 → 优先匹配包含'用户/user/customer'的数据源\n" +
                "- **地区/地理类**：地区分布、城市统计、区域分析 → 通常与订单/销售数据关联\n\n" +
                "## 任务\n" +
                "1. 分析用户问题的意图和业务领域\n" +
                "2. 根据业务领域映射规则匹配数据源\n" +
                "3. 如果只有一个数据源明显匹配，返回其ID\n" +
                "4. 如果有多个候选或无法确定，返回null并标记需要查看表结构\n\n" +
                "## 输出格式\n" +
                "只返回JSON格式，不要有其他文字：\n" +
                "{\"matched_datasource_id\": 数据源ID或null, \"confidence\": \"high/medium/low\", \"need_table_info\": true/false, \"reason\": \"匹配原因\"}\n\n" +
                "## 示例\n" +
                "用户问：'统计每个地区的销售额' -> {\"matched_datasource_id\": 1, \"confidence\": \"high\", \"need_table_info\": false, \"reason\": \"销售额属于交易/订单领域，该数据源包含订单相关表\"}\n" +
                "用户问：'查询财务报表数据' -> {\"matched_datasource_id\": 2, \"confidence\": \"high\", \"need_table_info\": false, \"reason\": \"财务报表属于会计领域，该数据源包含财务相关表\"}\n" +
                "用户问：'分析某领域数据' -> {\"matched_datasource_id\": null, \"confidence\": \"low\", \"need_table_info\": true, \"reason\": \"相关数据可能在多个数据源中\"}",
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
            String confidence = (String) firstResult.get("confidence");

            // 情况1：LLM明确匹配到唯一数据源且不需要表信息
            if (matchedId != null && !needTableInfo) {
                for (Map<String, Object> ds : datasources) {
                    if (((Number) ds.get("id")).intValue() == matchedId && "high".equalsIgnoreCase(confidence)) {
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
                "用户问：'统计某类数据' -> {\"matched_datasource_id\": 1, \"confidence\": \"high\", \"reason\": \"数据源1包含相关表，适合查询该类数据\"}",
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
                        if (((Number) ds.get("id")).intValue() == matchedId && "high".equalsIgnoreCase((String) secondResult.get("confidence"))) {
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
     * ✅ 重构：构建数据源选择响应 - 返回JSON供前端渲染按钮
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
        
        log.info("[DatasourceClarification] 返回{}个数据源选项供前端渲染", datasources.size());
        return json.toString();
    }
    
    /**
     * ✅ 新增：构建自动选择响应（统一JSON格式）
     */
    private String buildAutoSelectedResponse(Long dsId, String dsName, String datasourceInfo, boolean isOnlyOne) {
        try {
            Map<String, Object> response = new HashMap<>();
            response.put("status", "clarification_needed");
            response.put("clarificationType", "datasource_recommendation");
            
            // ✅ 关键逻辑：检查是否满足自动选择条件
            boolean shouldAutoSelect = tableSelectionConfig.isAutoSelectDatasource() 
                && !isOnlyOne;
            
            String message;
            boolean autoExecuted;
            
            if (isOnlyOne) {
                // 唯一数据源：始终自动执行
                message = String.format(
                    "✅ 系统只有唯一数据源：%s\n\n%s\n\n请直接使用此数据源执行查询，无需再次确认。",
                    escapeJson(dsName), datasourceInfo
                );
                autoExecuted = true;
            } else if (shouldAutoSelect) {
                // ✅ 配置开启 + confidence=high：自动选择，不返回前端确认
                message = String.format(
                    "🎯 自动选择数据源：%s（置信度: high）\n\n%s\n\n已自动使用该数据源执行查询。",
                    escapeJson(dsName), datasourceInfo
                );
                autoExecuted = true;
                log.info("[DatasourceClarification] ✅ 自动选择数据源: {} )", dsName);
            } else {
                // 需要前端确认
                message = String.format(
                    "🎯 根据您的问句，推荐使用数据源：%s\n\n%s\n\n请使用此数据源执行查询。",
                    escapeJson(dsName), datasourceInfo
                );
                autoExecuted = false;
            }
            
            response.put("message", message);
            response.put("recommendedDatasourceId", dsId);
            response.put("autoExecuted", autoExecuted);

            String result = objectMapper.writeValueAsString(response);
            log.info("[DatasourceClarification] 数据源推荐: {} (ID={}, autoExecuted={})",
                dsName, dsId, autoExecuted);
            return result;
            
        } catch (Exception e) {
            log.error("[DatasourceClarification] 构建自动选择响应失败", e);
            return buildErrorResponse("构建响应失败: " + e.getMessage());
        }
    }
    
    /**
     * ✅ 新增：构建错误响应（统一JSON格式）
     */
    private String buildErrorResponse(String errorMessage) {
        try {
            Map<String, Object> response = new HashMap<>();
            response.put("status", "error");
            response.put("message", escapeJson(errorMessage));
            
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            log.error("[DatasourceClarification] 构建错误响应失败", e);
            return "{\"status\":\"error\",\"message\":\"系统错误\"}";
        }
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
