package com.nl2sql.web.controller;

import com.nl2sql.auth.service.AuthService;
import com.nl2sql.common.result.Result;
import com.nl2sql.common.util.LogContextUtil;
import com.nl2sql.core.agent.ReActAgent;
import com.nl2sql.web.service.AgentResponseProcessor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.validation.constraints.NotBlank;
import java.util.HashMap;
import java.util.Map;

/**
 * Agent 对话控制器 - 使用 ReAct Agent
 * 
 * 架构说明：
 * - Controller只负责接收请求和返回结果
 * - 所有业务逻辑由 ReActAgent 自主决策
 * - 响应处理由 AgentResponseProcessor 负责
 * - LLM根据SystemMessage中的指令自主决定调用哪些Tool
 */
@Slf4j
@RestController
@RequestMapping("/api/agent")
public class AgentController {
    
    @Autowired
    private ReActAgent reActAgent;
    
    @Autowired
    private AuthService authService;
    
    @Autowired
    private AgentResponseProcessor responseProcessor;
    
    /**
     * Agent 对话接口 - 测试版本（无需认证）
     */
    @PostMapping("/chat/test")
    public Result<Map<String, Object>> chatTest(
        @RequestBody @Validated ChatRequest request
    ) {
        log.info("[测试接口] 绕过认证，直接使用测试用户");
        
        // 使用测试用户
        AuthService.UserInfo testUser = new AuthService.UserInfo();
        testUser.setUserId(1L);
        testUser.setUsername("test_user");
        testUser.setRealName("测试用户");
        LogContextUtil.setUserContext(testUser.getUserId(), testUser.getUsername());
        
        return processChat(request, testUser);
    }
    
    /**
     * Agent 对话接口
     * 
     * Agent会自主决定：
     * 1. 是否需要澄清
     * 2. 调用哪些Tool（retrieve_schema, generate_sql, execute_sql等）
     * 3. 调用顺序是什么
     * 4. 何时结束并返回结果
     */
    @PostMapping("/chat")
    public Result<Map<String, Object>> chat(
        @RequestBody @Validated ChatRequest request,
        @RequestHeader(value = "Authorization", required = false) String token
    ) {
        long startTime = System.currentTimeMillis();
        log.info("[Agent对话] ========== 开始处理 ==========");
        log.info("[Agent对话] 用户消息: {}", request.getMessage());
        log.info("[Agent对话] SessionId: {}", request.getSessionId());
        log.info("[Agent对话] DatasourceId: {}", request.getDatasourceId());
        
        // 1. 权限校验
        AuthService.UserInfo userInfo = validateUser(token);
        if (userInfo == null) {
            return Result.error(401, "请先登录");
        }
        
        return processChat(request, userInfo);
    }
    
    /**
     * 处理聊天请求的通用逻辑
     */
    private Result<Map<String, Object>> processChat(ChatRequest request, AuthService.UserInfo userInfo) {
        long startTime = System.currentTimeMillis();
        
        LogContextUtil.setUserContext(userInfo.getUserId(), userInfo.getUsername());
        
        try {
            // ✅ 关键修复：检测并修复中文乱码
            String originalMessage = request.getMessage();
            String fixedMessage = fixEncoding(originalMessage);
            if (!originalMessage.equals(fixedMessage)) {
                log.warn("[Agent对话] 检测到编码问题，已修复: {} -> {}", originalMessage, fixedMessage);
            }
            
            // 构建完整的用户消息（包含 context）
            String fullMessage = fixedMessage;
            if (request.getContext() != null && !request.getContext().isEmpty()) {
                try {
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    String contextJson = mapper.writeValueAsString(request.getContext());
                    fullMessage = fixedMessage + "\n\nContext: " + contextJson;
                    log.info("[Agent对话] 附加 Context: {}", contextJson);
                } catch (Exception e) {
                    log.warn("[Agent对话] Context 序列化失败", e);
                }
            }
            
            log.info("[Agent对话] 最终用户消息: {}", fullMessage);
            
            // 3. 调用 ReAct Agent，让 LLM 自主决策
            log.info("[Agent对话] 调用 ReActAgent.execute()...");
            String agentResponse = reActAgent.execute(
                fullMessage,
                request.getDatasourceId(),
                userInfo.getUserId().longValue(),
                userInfo.getUsername()
            );
            
            long executionTime = System.currentTimeMillis() - startTime;
            
            log.info("[Agent对话] 执行耗时: {} ms", executionTime);
            log.info("[Agent对话] ========== 处理完成 ==========");
            
            // 4. 使用 AgentResponseProcessor 处理响应
            Map<String, Object> response = responseProcessor.processResponse(
                agentResponse,
                buildRequestMap(request),
                buildUserInfoMap(userInfo)
            );
            
            // 5. 添加元数据
            response.put("sessionId", request.getSessionId() != null ? 
                request.getSessionId() : "default_" + userInfo.getUserId());
            response.put("executionTime", executionTime);
            
            // 返回 datasourceId（如果不存在）
            if (request.getDatasourceId() != null && !response.containsKey("datasourceId")) {
                response.put("datasourceId", request.getDatasourceId());
            }
            
            log.info("[Agent对话] 最终响应: {}", response);
            
            return Result.success(response);
            
        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("[Agent对话] 处理失败 (耗时: {} ms)", executionTime, e);
            log.error("[Agent对话] ========== 处理异常 ==========");
            return Result.error(500, "Agent 处理失败: " + e.getMessage());
        } finally {
            LogContextUtil.clear();
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
        requestMap.put("userId", 1L);  // 默认值，实际应该从 userInfo 获取
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
     * 验证用户
     */
    private AuthService.UserInfo validateUser(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        
        try {
            String cleanToken = token.startsWith("Bearer ") ? token.substring(7) : token;
            AuthService.UserInfo userInfo = authService.validateToken(cleanToken);
            
            if (userInfo != null && authService.isInWhitelist(userInfo.getUserId())) {
                return userInfo;
            }
            
            return null;
        } catch (Exception e) {
            log.warn("Token验证失败", e);
            return null;
        }
    }
    
    /**
     * ✅ 检测并修复中文乱码
     * 处理 ISO-8859-1 -> UTF-8 的编码转换问题
     */
    private String fixEncoding(String text) {
        if (text == null || text.trim().isEmpty()) {
            return text;
        }
        
        // 检测是否包含乱码特征（全是问号或特殊字符）
        if (text.matches("^[?\\s]+$")) {
            log.warn("[fixEncoding] 检测到纯问号文本，尝试重新解码");
            try {
                // 假设原始是 UTF-8，但被错误地当作 ISO-8859-1 解码
                byte[] bytes = text.getBytes("ISO-8859-1");
                String fixed = new String(bytes, "UTF-8");
                
                // 验证修复后的文本是否包含中文字符
                if (fixed.matches(".*[\\u4e00-\\u9fa5].*")) {
                    log.info("[fixEncoding] 成功修复编码: {} -> {}", text, fixed);
                    return fixed;
                }
            } catch (Exception e) {
                log.error("[fixEncoding] 编码修复失败", e);
            }
        }
        
        return text;
    }
    
    /**
     * 对话请求
     */
    @Data
    public static class ChatRequest {
        @NotBlank(message = "消息不能为空")
        private String message;
        
        private Long datasourceId;  // 可选：用户指定的数据源
        
        private String sessionId;   // 会话ID（用于多轮对话）
        
        private Map<String, Object> context;  // 上下文数据（如 SQL、图表类型等）
    }
}
