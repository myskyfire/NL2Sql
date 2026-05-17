package com.nl2sql.web.controller;

import com.nl2sql.auth.service.AuthService;
import com.nl2sql.common.result.Result;
import com.nl2sql.web.service.AgentChatService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Agent 对话控制器
 * 
 * 职责：
 * - 接收 HTTP 请求
 * - 参数验证
 * - 调用 Service 层处理业务逻辑
 * - 返回 HTTP 响应
 * 
 * ✅ 认证由 AuthInterceptor 自动处理
 * ✅ 业务逻辑由 AgentChatService 处理
 */
@Slf4j
@RestController
@RequestMapping("/api/agent")
public class AgentController {
    
    @Autowired
    private AgentChatService agentChatService;
    
    /**
     * Agent 对话接口 - 测试版本（无需认证）
     */
    @PostMapping("/chat/test")
    public Result<Map<String, Object>> chatTest(
        @RequestBody @Validated AgentChatService.ChatRequest request
    ) {
        log.info("[测试接口] 绕过认证，直接使用测试用户");
        
        // 使用测试用户
        AuthService.UserInfo testUser = new AuthService.UserInfo();
        testUser.setUserId(1L);
        testUser.setUsername("test_user");
        testUser.setRealName("测试用户");
        
        return agentChatService.processChat(request, testUser);
    }
    
    /**
     * Agent 对话接口
     * 
     * ✅ 认证由 AuthInterceptor 自动处理
     * ✅ 业务逻辑由 AgentChatService 处理
     */
    @PostMapping("/chat")
    public Result<Map<String, Object>> chat(
        @RequestBody @Validated AgentChatService.ChatRequest request,
        HttpServletRequest httpRequest
    ) {
        log.info("[Agent对话] ========== 开始处理 ==========");
        log.info("[Agent对话] 用户消息: {}", request.getMessage());
        log.info("[Agent对话] SessionId: {}", request.getSessionId());
        log.info("[Agent对话] DatasourceId: {}", request.getDatasourceId());
        
        // ✅ 从 request attribute 获取已验证的用户信息
        AuthService.UserInfo userInfo = (AuthService.UserInfo) httpRequest.getAttribute("userInfo");
        if (userInfo == null) {
            log.error("[Agent对话] 用户信息缺失");
            return Result.error(500, "认证服务异常");
        }
        
        return agentChatService.processChat(request, userInfo);
    }
    
    /**
     * ✅ 人机协同：确认执行高风险SQL
     * 
     * @param approvalId 确认ID（从human_approval_required响应中获取）
     * @param approved 是否批准(true=执行, false=取消)
     */
    @PostMapping("/approve-sql")
    public Result<Map<String, Object>> approveSql(
        @RequestBody Map<String, Object> request,
        HttpServletRequest httpRequest
    ) {
        String approvalId = (String) request.get("approvalId");
        Boolean approved = com.nl2sql.common.util.BooleanUtils.toBoolean(request.get("approved"));
        
        log.info("[人机协同] 收到SQL确认请求: approvalId={}, approved={}", approvalId, approved);
        
        if (approvalId == null || approvalId.isEmpty()) {
            return Result.error(400, "缺少approvalId参数");
        }
        
        if (approved == null) {
            return Result.error(400, "缺少approved参数");
        }
        
        // ✅ 从 request attribute 获取已验证的用户信息
        AuthService.UserInfo userInfo = (AuthService.UserInfo) httpRequest.getAttribute("userInfo");
        if (userInfo == null) {
            log.error("[人机协同] 用户信息缺失");
            return Result.error(500, "认证服务异常");
        }
        
        return agentChatService.handleSqlApproval(approvalId, approved, userInfo);
    }
}
