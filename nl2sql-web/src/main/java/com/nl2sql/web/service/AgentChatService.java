package com.nl2sql.web.service;

import com.nl2sql.auth.service.AuthService;
import com.nl2sql.common.result.Result;
import com.nl2sql.common.util.LogContextUtil;
import com.nl2sql.core.agent.ReActAgent;
import com.nl2sql.core.agent.tools.NL2SQLTool;
import com.nl2sql.core.rag.SQLFeedbackService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Agent 对话服务
 * 
 * 职责：处理 Agent 对话的核心业务逻辑
 */
@Slf4j
@Service
public class AgentChatService {
    
    @Autowired
    private ReActAgent reActAgent;
    
    @Autowired(required = false)
    private SQLFeedbackService feedbackService;
    
    @Autowired(required = false)
    private JdbcTemplate jdbcTemplate;
    
    @Autowired(required = false)
    private NL2SQLTool nl2sqlTool;
    
    @Autowired(required = false)
    private IntentClassifier intentClassifier;
    
    @Autowired
    private AgentResponseProcessor responseProcessor;
    
    /**
     * 处理聊天请求
     */
    public Result<Map<String, Object>> processChat(
        ChatRequest request, 
        AuthService.UserInfo userInfo
    ) {
        long startTime = System.currentTimeMillis();
        
        LogContextUtil.setUserContext(userInfo.getUserId(), userInfo.getUsername());
        
        try {
            // 1. 应用默认评分
            applyDefaultRating(request, userInfo);
            
            // 2. 修复编码问题
            String fixedMessage = fixEncoding(request.getMessage());
            
            // 3. 构建完整消息（包含 context）
            String fullMessage = buildFullMessage(fixedMessage, request.getContext());
            
            // 4. 意图识别
            String intent = classifyIntent(fullMessage);
            
            // 5. 设置会话ID
            String sessionId = resolveSessionId(request, userInfo);
            setSessionId(sessionId);
            
            try {
                // 6. 调用 ReAct Agent
                String agentResponse = executeAgent(fullMessage, request, userInfo);
                
                long executionTime = System.currentTimeMillis() - startTime;
                
                // 7. 处理响应
                Map<String, Object> response = responseProcessor.processResponse(
                    agentResponse,
                    buildRequestMap(request),
                    buildUserInfoMap(userInfo)
                );
                
                // 8. 添加元数据
                enrichResponse(response, sessionId, executionTime, request);
                
                // 9. 记录查询日志
                logQueryToDatabase(request, response, userInfo);
                
                return Result.success(response);
                
            } finally {
                clearSessionId();
            }
            
        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("[Agent对话] 处理失败 (耗时: {} ms)", executionTime, e);
            return Result.error(500, "Agent 处理失败: " + e.getMessage());
        } finally {
            LogContextUtil.clear();
        }
    }
    
    /**
     * 应用默认评分
     */
    private void applyDefaultRating(ChatRequest request, AuthService.UserInfo userInfo) {
        if (feedbackService == null) return;
        
        String sessionId = resolveSessionId(request, userInfo);
        try {
            boolean applied = feedbackService.applyDefaultRating(sessionId);
            if (applied) {
                log.info("[Agent对话] 已应用默认评分: sessionId={}", sessionId);
            }
        } catch (Exception e) {
            log.warn("[Agent对话] 应用默认评分失败，继续处理: {}", e.getMessage());
        }
    }
    
    /**
     * 修复编码问题
     */
    private String fixEncoding(String message) {
        if (message == null || message.trim().isEmpty()) {
            return message;
        }
        
        // 检测是否包含乱码特征
        if (message.matches("^[?\\s]+$")) {
            log.warn("[fixEncoding] 检测到纯问号文本，尝试重新解码");
            try {
                byte[] bytes = message.getBytes("ISO-8859-1");
                String fixed = new String(bytes, "UTF-8");
                
                if (fixed.matches(".*[\\u4e00-\\u9fa5].*")) {
                    log.info("[fixEncoding] 成功修复编码: {} -> {}", message, fixed);
                    return fixed;
                }
            } catch (Exception e) {
                log.error("[fixEncoding] 编码修复失败", e);
            }
        }
        
        return message;
    }
    
    /**
     * 构建完整消息
     */
    private String buildFullMessage(String message, Map<String, Object> context) {
        if (context == null || context.isEmpty()) {
            return message;
        }
        
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            
            // 如果 nl2sqlTool 中有最新的 SQL，覆盖 context 中的旧 SQL
            Map<String, Object> contextMap = new HashMap<>(context);
            if (nl2sqlTool != null) {
                String latestSQL = nl2sqlTool.getCurrentSQL();
                String latestQuery = nl2sqlTool.getCurrentQuery();
                if (latestSQL != null && !latestSQL.trim().isEmpty()) {
                    log.info("[Agent对话] 使用最新 SQL 覆盖 context: {}", latestSQL);
                    contextMap.put("generatedSQL", latestSQL);
                    if (latestQuery != null) {
                        contextMap.put("lastQuery", latestQuery);
                    }
                }
            }
            
            String contextJson = mapper.writeValueAsString(contextMap);
            String fullMessage = message + "\n\nContext: " + contextJson;
            log.info("[Agent对话] 附加 Context: {}", contextJson);
            return fullMessage;
            
        } catch (Exception e) {
            log.warn("[Agent对话] Context 序列化失败", e);
            return message;
        }
    }
    
    /**
     * 意图识别
     */
    private String classifyIntent(String message) {
        String intent = "QUERY"; // 默认意图
        if (intentClassifier != null) {
            intent = intentClassifier.classify(message);
            log.debug("[Agent对话] 意图识别结果: {}", intent);
        }
        return intent;
    }
    
    /**
     * 解析会话ID
     */
    private String resolveSessionId(ChatRequest request, AuthService.UserInfo userInfo) {
        return request.getSessionId() != null ? 
            request.getSessionId() : "default_" + userInfo.getUserId();
    }
    
    /**
     * 设置会话ID
     */
    private void setSessionId(String sessionId) {
        if (nl2sqlTool != null) {
            nl2sqlTool.setCurrentSessionId(sessionId);
            log.debug("[Agent对话] 已设置会话ID: {}", sessionId);
        }
    }
    
    /**
     * 清除会话ID
     */
    private void clearSessionId() {
        if (nl2sqlTool != null) {
            nl2sqlTool.clearCurrentSessionId();
        }
    }
    
    /**
     * 执行 Agent
     */
    private String executeAgent(
        String fullMessage, 
        ChatRequest request, 
        AuthService.UserInfo userInfo
    ) {
        log.info("[Agent对话] 调用 ReActAgent.execute()...");
        return reActAgent.execute(
            fullMessage,
            request.getDatasourceId(),
            userInfo.getUserId().longValue(),
            userInfo.getUsername()
        );
    }
    
    /**
     * 丰富响应数据
     */
    private void enrichResponse(
        Map<String, Object> response, 
        String sessionId, 
        long executionTime,
        ChatRequest request
    ) {
        response.put("sessionId", sessionId);
        response.put("executionTime", executionTime);
        
        // 返回 datasourceId（如果不存在）
        if (request.getDatasourceId() != null && !response.containsKey("datasourceId")) {
            response.put("datasourceId", request.getDatasourceId());
        }
    }
    
    /**
     * 构建请求 Map
     */
    private Map<String, Object> buildRequestMap(ChatRequest request) {
        Map<String, Object> requestMap = new HashMap<>();
        requestMap.put("message", request.getMessage());
        requestMap.put("datasourceId", request.getDatasourceId());
        requestMap.put("sessionId", request.getSessionId());
        requestMap.put("userId", 1L);
        requestMap.put("context", request.getContext());
        return requestMap;
    }
    
    /**
     * 构建用户信息 Map
     */
    private Map<String, Object> buildUserInfoMap(AuthService.UserInfo userInfo) {
        Map<String, Object> userInfoMap = new HashMap<>();
        userInfoMap.put("userId", userInfo.getUserId());
        userInfoMap.put("username", userInfo.getUsername());
        userInfoMap.put("realName", userInfo.getRealName());
        return userInfoMap;
    }
    
    /**
     * 记录查询日志到数据库
     */
    private void logQueryToDatabase(
        ChatRequest request, 
        Map<String, Object> response, 
        AuthService.UserInfo userInfo
    ) {
        if (jdbcTemplate == null) {
            log.debug("[查询日志] JdbcTemplate 未注入，跳过日志记录");
            return;
        }
        
        try {
            Boolean success = (Boolean) response.get("success");
            String sql = (String) response.get("sql");
            
            if (success != null && success && sql != null && !sql.trim().isEmpty()) {
                String insertSql = "INSERT INTO nl2sql_query_log " +
                    "(session_id, user_id, question, generated_sql, executed_sql, " +
                    "execution_success, row_count, execution_time_ms, datasource_id) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
                
                String sessionId = request.getSessionId() != null ? 
                    request.getSessionId() : "default_" + userInfo.getUserId();
                
                Integer rowCount = response.get("rowCount") != null ? 
                    (Integer) response.get("rowCount") : 0;
                Long executionTime = response.get("executionTime") != null ? 
                    (Long) response.get("executionTime") : 0L;
                
                jdbcTemplate.update(insertSql,
                    sessionId,
                    userInfo.getUserId(),
                    request.getMessage(),
                    sql,
                    sql,
                    success,
                    rowCount,
                    executionTime,
                    request.getDatasourceId()
                );
                
                log.debug("[查询日志] 记录成功: sessionId={}", sessionId);
            }
        } catch (Exception e) {
            log.warn("[查询日志] 记录失败", e);
        }
    }
    
    /**
     * 聊天请求 DTO
     */
    public static class ChatRequest {
        @jakarta.validation.constraints.NotBlank(message = "消息不能为空")
        private String message;
        private Long datasourceId;
        private String sessionId;
        private Map<String, Object> context;
        
        // Getters and Setters
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public Long getDatasourceId() { return datasourceId; }
        public void setDatasourceId(Long datasourceId) { this.datasourceId = datasourceId; }
        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }
        public Map<String, Object> getContext() { return context; }
        public void setContext(Map<String, Object> context) { this.context = context; }
    }
}
