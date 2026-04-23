package com.nl2sql.web.controller;

import com.nl2sql.auth.service.AuthService;
import com.nl2sql.common.result.Result;
import com.nl2sql.core.rag.PromptLearningService;
import com.nl2sql.core.rag.dto.ABTestRequest;
import com.nl2sql.core.rag.dto.PromptVersionRequest;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Prompt自我学习管理Controller
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/prompt-learning")
public class PromptLearningController {
    
    @Autowired
    private PromptLearningService promptLearningService;
    
    // ==================== Prompt版本管理 ====================
    
    /**
     * 创建Prompt版本
     */
    @PostMapping("/versions")
    public Result<Long> createVersion(
            @RequestBody PromptVersionRequest request,
            HttpServletRequest httpRequest) {
        try {
            Long userId = getCurrentUserId(httpRequest);
            if (userId == null) {
                return Result.error("未登录或Token无效");
            }
            
            Long id = promptLearningService.createPromptVersion(
                request.getVersionName(),
                request.getPromptType(),
                request.getPromptContent(),
                request.getDescription(),
                userId
            );
            
            // 如果设为默认，立即激活
            if (Boolean.TRUE.equals(request.getIsDefault())) {
                promptLearningService.activatePromptVersion(id);
            }
            
            return Result.success(id);
        } catch (Exception e) {
            log.error("创建Prompt版本失败", e);
            return Result.error("创建失败: " + e.getMessage());
        }
    }
    
    /**
     * 更新Prompt版本
     */
    @PutMapping("/versions/{id}")
    public Result<String> updateVersion(@PathVariable Long id, 
                                       @RequestBody PromptVersionRequest request) {
        try {
            promptLearningService.updatePromptVersion(id, request.getPromptContent(), request.getDescription());
            
            // 如果设为默认，激活该版本
            if (Boolean.TRUE.equals(request.getIsDefault())) {
                promptLearningService.activatePromptVersion(id);
            }
            
            return Result.success("更新成功");
        } catch (Exception e) {
            log.error("更新Prompt版本失败", e);
            return Result.error("更新失败: " + e.getMessage());
        }
    }
    
    /**
     * 激活Prompt版本
     */
    @PostMapping("/versions/{id}/activate")
    public Result<String> activateVersion(@PathVariable Long id) {
        try {
            promptLearningService.activatePromptVersion(id);
            return Result.success("激活成功");
        } catch (Exception e) {
            log.error("激活Prompt版本失败", e);
            return Result.error("激活失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取默认Prompt
     */
    @GetMapping("/default/{promptType}")
    public Result<String> getDefaultPrompt(@PathVariable String promptType) {
        try {
            String prompt = promptLearningService.getDefaultPrompt(promptType);
            return Result.success(prompt);
        } catch (Exception e) {
            log.error("获取默认Prompt失败", e);
            return Result.error("获取失败: " + e.getMessage());
        }
    }
    
    /**
     * 列出所有Prompt版本
     */
    @GetMapping("/versions")
    public Result<List<PromptLearningService.PromptVersion>> listVersions(
            @RequestParam(required = false) String promptType) {
        try {
            List<PromptLearningService.PromptVersion> versions = 
                promptLearningService.listPromptVersions(promptType);
            return Result.success(versions);
        } catch (Exception e) {
            log.error("列出Prompt版本失败", e);
            return Result.error("查询失败: " + e.getMessage());
        }
    }
    
    // ==================== A/B测试管理 ====================
    
    /**
     * 创建A/B测试
     */
    @PostMapping("/ab-tests")
    public Result<Long> createABTest(
            @RequestBody ABTestRequest request,
            HttpServletRequest httpRequest) {
        try {
            Long userId = getCurrentUserId(httpRequest);
            if (userId == null) {
                return Result.error("未登录或Token无效");
            }
            
            Long id = promptLearningService.createABTest(
                request.getTestName(),
                request.getPromptType(),
                request.getVersionAId(),
                request.getVersionBId(),
                request.getTrafficSplit(),
                request.getMinSamples(),
                userId
            );
            return Result.success(id);
        } catch (Exception e) {
            log.error("创建A/B测试失败", e);
            return Result.error("创建失败: " + e.getMessage());
        }
    }
    
    /**
     * 停止A/B测试
     */
    @PostMapping("/ab-tests/{id}/stop")
    public Result<String> stopABTest(@PathVariable Long id) {
        try {
            promptLearningService.stopABTest(id);
            return Result.success("测试已停止");
        } catch (Exception e) {
            log.error("停止A/B测试失败", e);
            return Result.error("停止失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取A/B测试统计
     */
    @GetMapping("/ab-tests/{id}/stats")
    public Result<Map<String, Object>> getABTestStats(@PathVariable Long id) {
        try {
            Map<String, Object> stats = promptLearningService.getABTestStats(id);
            return Result.success(stats);
        } catch (Exception e) {
            log.error("获取A/B测试统计失败", e);
            return Result.error("获取失败: " + e.getMessage());
        }
    }
    
    /**
     * 列出所有A/B测试
     */
    @GetMapping("/ab-tests")
    public Result<List<Map<String, Object>>> listABTests(
            @RequestParam(required = false) String status) {
        try {
            List<Map<String, Object>> tests = promptLearningService.listABTests(status);
            return Result.success(tests);
        } catch (Exception e) {
            log.error("列出A/B测试失败", e);
            return Result.error("查询失败: " + e.getMessage());
        }
    }
    
    // ==================== 自动优化 ====================
    
    /**
     * 分析低分反馈，生成优化建议
     */
    @GetMapping("/optimization-suggestions")
    public Result<List<Map<String, Object>>> getOptimizationSuggestions(
            @RequestParam String promptType,
            @RequestParam(defaultValue = "50") int limit) {
        try {
            List<Map<String, Object>> suggestions = 
                promptLearningService.analyzeLowRatingFeedbacks(promptType, limit);
            return Result.success(suggestions);
        } catch (Exception e) {
            log.error("获取优化建议失败", e);
            return Result.error("获取失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取版本使用统计
     */
    @GetMapping("/versions/{id}/stats")
    public Result<Map<String, Object>> getVersionStats(@PathVariable Long id) {
        try {
            Map<String, Object> stats = promptLearningService.getVersionUsageStats(id);
            return Result.success(stats);
        } catch (Exception e) {
            log.error("获取版本统计失败", e);
            return Result.error("获取失败: " + e.getMessage());
        }
    }
    
    /**
     * 从HttpServletRequest获取当前用户ID
     */
    private Long getCurrentUserId(HttpServletRequest request) {
        Object userInfoObj = request.getAttribute("userInfo");
        if (userInfoObj instanceof AuthService.UserInfo) {
            return ((AuthService.UserInfo) userInfoObj).getUserId();
        }
        return null;
    }
}
