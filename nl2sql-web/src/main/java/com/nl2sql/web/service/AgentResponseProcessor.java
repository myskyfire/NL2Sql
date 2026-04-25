package com.nl2sql.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nl2sql.core.agent.ReActAgent;
import com.nl2sql.core.agent.tools.SQLExecutionTool;
import com.nl2sql.core.service.NL2SQLService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agent 响应处理器
 * 
 * 职责：
 * 1. 清理 Markdown 格式
 * 2. 检测并解析结构化数据（JSON）
 * 3. 处理纯文本响应
 * 4. 从自然语言中提取数据源ID
 * 5. 自动触发第二轮查询
 */
@Slf4j
@Component
public class AgentResponseProcessor {
    
    @Autowired
    private ReActAgent reActAgent;
    
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    @Autowired
    private NL2SQLService nl2sqlService;
    
    @Autowired
    private SQLExecutionTool sqlExecutionTool;
    
    @Autowired(required = false)
    private DatasourceSessionService datasourceSessionService; // ✅ 数据源会话管理
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * 处理 Agent 响应
     * 
     * @param agentResponse Agent 原始响应
     * @param request 用户请求
     * @param userInfo 用户信息
     * @return 处理后的响应
     */
    public Map<String, Object> processResponse(
        String agentResponse,
        Map<String, Object> request,
        Map<String, Object> userInfo
    ) {
        try {
            // 1. 清理 Markdown
            String cleaned = cleanMarkdown(agentResponse);
            
            // 2. 尝试解析 JSON
            if (isJsonResponse(cleaned)) {
                return handleJsonResponse(cleaned, request, userInfo);
            }
            
            // 3. 纯文本响应
            return handleTextResponse(cleaned, request, userInfo);
            
        } catch (Exception e) {
            log.error("[AgentResponseProcessor] 处理响应失败", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("status", "error");
            errorResponse.put("message", "响应处理失败: " + e.getMessage());
            return errorResponse;
        }
    }
    
    /**
     * 清理 Markdown 格式
     */
    private String cleanMarkdown(String text) {
        if (text == null || text.trim().isEmpty()) {
            return text;
        }
        
        // 去除 ```json ... ``` 或 ``` ... ```
        Pattern pattern = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)\\s*```");
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        
        return text.trim();
    }
    
    /**
     * 检查是否是 JSON 响应
     */
    private boolean isJsonResponse(String text) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }
        
        String trimmed = text.trim();
        return trimmed.startsWith("{") && trimmed.endsWith("}");
    }
    
    /**
     * 处理 JSON 响应
     */
    private Map<String, Object> handleJsonResponse(
        String jsonResponse,
        Map<String, Object> request,
        Map<String, Object> userInfo
    ) throws Exception {
        Map<String, Object> parsed = objectMapper.readValue(jsonResponse, Map.class);
        
        // ✅ 关键修复：将 StandardQuerySkill 的 success 字段转换为 status 字段
        if (parsed.containsKey("success") && !parsed.containsKey("status")) {
            Boolean success = (Boolean) parsed.get("success");
            if (success != null && success) {
                parsed.put("status", "success");
            } else if (parsed.containsKey("needsClarification") && (Boolean) parsed.get("needsClarification")) {
                parsed.put("status", "clarification_needed");
                if (!parsed.containsKey("message") && parsed.containsKey("clarificationMessage")) {
                    parsed.put("message", parsed.get("clarificationMessage"));
                }
            } else {
                parsed.put("status", "error");
            }
        }
        
        // ✅ 新增：将 request 中的 datasourceId 传递到响应中，供前端保存
        Object datasourceIdObj = request.get("datasourceId");
        if (datasourceIdObj != null && !parsed.containsKey("datasourceId")) {
            parsed.put("datasourceId", datasourceIdObj);
        }
        
        String status = (String) parsed.get("status");
        
        // ✅ 修正：不再自动执行第二轮查询，让LLM根据Tool返回自主决策
        // Tool返回的JSON会原样传递给LLM作为Observation
        // LLM会根据recommendedDatasourceId决定是否调用execute_standard_query
        
        // 其他状态直接返回
        return parsed;
    }
    
    /**
     * 处理纯文本响应
     */
    private Map<String, Object> handleTextResponse(
        String text,
        Map<String, Object> request,
        Map<String, Object> userInfo
    ) {
        Map<String, Object> response = new HashMap<>();
        
        if (text.contains("推荐使用") || text.contains("🎯")) {
            response.put("status", "clarification_needed");
            response.put("clarificationType", "datasource_recommendation");
            response.put("message", text);
            
            // ✅ 修正：不再自动提取ID并执行，让LLM自主决策
            // 如果文本中包含数据源信息，LLM会自行解析并决定下一步
        } else if (text.contains("抱歉") || text.contains("无法")) {
            response.put("status", "error");
            response.put("message", text);
        } else if (text.contains("错误：") && datasourceSessionService != null) {
            // ✅ 检测SQL生成错误，尝试给出数据源切换建议
            response.put("status", "error");
            response.put("message", text);
            
            // 提取表名（简单启发式）
            String tableName = extractTableNameFromError(text);
            if (tableName != null) {
                log.info("[handleTextResponse] 检测到表不存在错误，表名: {}", tableName);
                
                // 获取当前数据源ID（从 request 中）
                Long currentDsId = request.get("datasourceId") != null 
                    ? ((Number) request.get("datasourceId")).longValue() 
                    : null;
                
                // 查找其他数据源是否有该表
                List<Map<String, Object>> alternatives = datasourceSessionService.getAlternativeDatasources(currentDsId);
                List<String> availableIn = new ArrayList<>();
                
                for (Map<String, Object> alt : alternatives) {
                    Long altDsId = ((Number) alt.get("id")).longValue();
                    String altName = (String) alt.get("name");
                    
                    if (datasourceSessionService.tableExistsInDatasource(altDsId, tableName)) {
                        availableIn.add(altName);
                    }
                }
                
                if (!availableIn.isEmpty()) {
                    String suggestion = String.format(
                        "\n\n💡 **提示**：表 `%s` 可能在以下数据源中存在：%s\n是否要切换到这些数据源重新查询？",
                        tableName,
                        String.join("、", availableIn)
                    );
                    response.put("suggestion", suggestion);
                    response.put("availableDatasources", availableIn);
                }
            }
        } else {
            response.put("status", "success");
            response.put("message", text);
        }
        
        return response;
    }
    
    /**
     * 从自然语言文本中提取数据源ID
     */
    private Long extractDatasourceIdFromText(String text) {
        try {
            // 匹配模式："- 名称: XXX" 或 "**XXX**"
            Pattern pattern = Pattern.compile("(?:名称[:：]|\\*\\*)([^\\n\\*]+?)(?:\\*\\*|$)");
            Matcher matcher = pattern.matcher(text);
            
            if (matcher.find()) {
                String dsName = matcher.group(1).trim();
                log.info("[extractDatasourceIdFromText] 提取到数据源名称: {}", dsName);
                
                // 查询数据库获取对应的 datasourceId
                List<Map<String, Object>> results = jdbcTemplate.queryForList(
                    "SELECT id FROM datasource_config WHERE name = ? AND is_active = 1",
                    dsName
                );
                
                if (!results.isEmpty()) {
                    Long dsId = ((Number) results.get(0).get("id")).longValue();
                    log.info("[extractDatasourceIdFromText] 找到对应的数据源ID: {}", dsId);
                    return dsId;
                } else {
                    log.warn("[extractDatasourceIdFromText] 未找到名为 '{}' 的数据源", dsName);
                }
            }
        } catch (Exception e) {
            log.error("[extractDatasourceIdFromText] 提取失败", e);
        }
        
        return null;
    }
    
    /**
     * ✅ 从错误信息中提取表名（简单启发式）
     */
    private String extractTableNameFromError(String errorMessage) {
        if (errorMessage == null || !errorMessage.contains("错误：")) {
            return null;
        }
        
        try {
            // 尝试匹配常见表不存在错误模式
            // 例如："Table 'xxx' doesn't exist" 或 "找不到表 xxx"
            Pattern[] patterns = {
                Pattern.compile("Table ['\"]([^'\"]+)['\"]"),
                Pattern.compile("表 ['\"]?([a-zA-Z_][a-zA-Z0-9_]*)['\"]?"),
                Pattern.compile("table ['\"]?([a-zA-Z_][a-zA-Z0-9_]*)['\"]?")
            };
            
            for (Pattern pattern : patterns) {
                Matcher matcher = pattern.matcher(errorMessage);
                if (matcher.find()) {
                    String tableName = matcher.group(1);
                    log.debug("[extractTableNameFromError] 提取到表名: {}", tableName);
                    return tableName;
                }
            }
        } catch (Exception e) {
            log.debug("[extractTableNameFromError] 提取失败", e);
        }
        
        return null;
    }
    
    /**
     * 强制执行查询（绕过LLM，直接调用NL2SQLTool + SQLExecutionTool）
     */
    private Map<String, Object> forceExecuteQuery(
        String question,
        Long datasourceId,
        Long userId,
        String username
    ) {
        try {
            log.info("[forceExecuteQuery] 强制执行查询: question={}, datasourceId={}", question, datasourceId);
            
            // 1. 生成SQL
            String sql = nl2sqlService.generateSQL(question, datasourceId);
            log.info("[forceExecuteQuery] 生成的SQL: {}", sql);
            
            // 2. 检查是否是澄清请求
            if (sql.contains("needsClarification") || sql.contains("clarification")) {
                Map<String, Object> parsed = objectMapper.readValue(sql, Map.class);
                parsed.put("autoExecuted", true);
                return parsed;
            }
            
            // 3. 执行SQL
            com.nl2sql.core.agent.tools.SQLExecutionTool.ExecutionResult executionResult = 
                sqlExecutionTool.executeSQL(sql, datasourceId, userId, username);
            log.info("[forceExecuteQuery] 执行结果: success={}", executionResult.success);
            
            // 4. 构建响应
            Map<String, Object> response = new HashMap<>();
            if (executionResult.success) {
                response.put("status", "success");
                response.put("data", executionResult.data);
                response.put("rowCount", executionResult.rowCount);
                response.put("generatedSQL", sql);
                response.put("autoExecuted", true);
            } else {
                response.put("status", "error");
                response.put("message", executionResult.error);
                response.put("autoExecuted", true);
            }
            return response;
        } catch (Exception e) {
            log.error("[forceExecuteQuery] 强制查询失败", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("status", "error");
            errorResponse.put("message", "查询失败: " + e.getMessage());
            return errorResponse;
        }
    }
    
    /**
     * 执行第二轮查询（使用推荐的数据源ID）
     */
    private Map<String, Object> executeSecondRoundQuery(
        Map<String, Object> request,
        Map<String, Object> userInfo,
        Long datasourceId
    ) {
        try {
            String originalMessage = (String) request.get("message");
            Long userId = ((Number) request.get("userId")).longValue();
            String sessionId = (String) request.get("sessionId");
            String username = (String) userInfo.get("username");
            
            log.info("[executeSecondRoundQuery] 使用数据源ID {} 重新执行查询: {}", datasourceId, originalMessage);
            
            // ✅ 关键修复：在message中明确告知LLM已选择的数据源
            String enhancedMessage = String.format(
                "【重要】已确定使用数据源ID=%d，请直接调用 execute_standard_query(question=\"%s\", datasourceId=%d) 执行查询。\n\n用户问题：%s",
                datasourceId, originalMessage, datasourceId, originalMessage
            );
            
            // 调用 ReActAgent 执行第二轮查询
            String secondResponse = reActAgent.execute(
                enhancedMessage,
                datasourceId,
                userId,
                username,
                null  // ✅ 第二轮查询不传历史
            );
            
            // 清理并解析第二轮响应
            String cleaned = cleanMarkdown(secondResponse);
            if (isJsonResponse(cleaned)) {
                Map<String, Object> parsed = objectMapper.readValue(cleaned, Map.class);
                log.info("[executeSecondRoundQuery] 第二轮查询成功，状态: {}", parsed.get("status"));
                
                // ✅ 关键修复：如果LLM仍然返回clarification_needed，强制执行查询
                if ("clarification_needed".equals(parsed.get("status"))) {
                    log.warn("[executeSecondRoundQuery] LLM仍返回clarification_needed，强制执行查询");
                    // 直接调用 StandardQuerySkill 执行查询
                    return forceExecuteQuery(originalMessage, datasourceId, userId, username);
                }
                
                // ✅ 添加标记：已自动执行，前端不应再显示确认按钮
                parsed.put("autoExecuted", true);
                return parsed;
            } else {
                // 纯文本响应
                Map<String, Object> response = new HashMap<>();
                response.put("status", "success");
                response.put("message", cleaned);
                response.put("autoExecuted", true);
                return response;
            }
            
        } catch (Exception e) {
            log.error("[executeSecondRoundQuery] 第二轮查询失败", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("status", "error");
            errorResponse.put("message", "自动查询失败: " + e.getMessage());
            return errorResponse;
        }
    }
}
