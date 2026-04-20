package com.nl2sql.web.controller;

import com.nl2sql.common.result.Result;
import com.nl2sql.common.util.NetworkUtils;
import com.nl2sql.core.rag.SQLFeedbackService;
import com.nl2sql.core.rag.dto.SQLFeedbackRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

/**
 * SQL反馈Controller
 * 收集用户对生成SQL的评分和反馈
 */
@Slf4j
@RestController
@RequestMapping("/api/feedback")
public class SQLFeedbackController {
    
    @Autowired
    private SQLFeedbackService feedbackService;
    
    /**
     * 提交SQL反馈
     * 
     * @param request 反馈请求
     * @param httpRequest HTTP请求（获取IP和UA）
     * @return 反馈ID
     */
    @PostMapping("/submit")
    public Result<Long> submitFeedback(
            @RequestBody SQLFeedbackRequest request,
            HttpServletRequest httpRequest) {
        try {
            // 获取用户IP和User-Agent
            String ipAddress = NetworkUtils.getClientIp(httpRequest);
            String userAgent = httpRequest.getHeader("User-Agent");
            
            Long feedbackId = feedbackService.submitFeedback(request, ipAddress, userAgent);
            
            log.info("SQL反馈提交成功: feedbackId={}, rating={}", 
                feedbackId, request.getRating());
            
            return Result.success(feedbackId);
            
        } catch (IllegalArgumentException e) {
            log.warn("SQL反馈参数错误: {}", e.getMessage());
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("提交SQL反馈失败", e);
            return Result.error("提交反馈失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取反馈统计信息
     */
    @GetMapping("/stats")
    public Result<Map<String, Object>> getFeedbackStats() {
        try {
            Map<String, Object> stats = feedbackService.getFeedbackStats();
            return Result.success(stats);
        } catch (Exception e) {
            log.error("获取反馈统计失败", e);
            return Result.error("获取统计信息失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取低分反馈列表（管理员用）
     * 
     * @param limit 返回数量，默认10
     */
    @GetMapping("/low-rating")
    public Result<List<Map<String, Object>>> getLowRatingFeedbacks(
            @RequestParam(defaultValue = "10") int limit) {
        try {
            List<Map<String, Object>> feedbacks = feedbackService.getLowRatingFeedbacks(limit);
            return Result.success(feedbacks);
        } catch (Exception e) {
            log.error("获取低分反馈失败", e);
            return Result.error("获取反馈列表失败: " + e.getMessage());
        }
    }
}
