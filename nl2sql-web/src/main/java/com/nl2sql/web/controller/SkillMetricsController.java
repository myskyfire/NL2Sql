package com.nl2sql.web.controller;

import com.nl2sql.core.agent.monitoring.SkillMetricsRecorder;
import com.nl2sql.core.agent.monitoring.SkillMetricsRecorder.SkillMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Skill 指标查询接口
 * 
 * 提供 REST API 用于：
 * - 查询所有 Skill 的执行指标
 * - 重置指标数据
 */
@RestController
@RequestMapping("/api/admin/skill-metrics")
@RequiredArgsConstructor
public class SkillMetricsController {
    
    private final SkillMetricsRecorder metricsRecorder;
    
    /**
     * 获取所有 Skill 指标
     * 
     * @return 包含所有 Skill 指标的 JSON 响应
     */
    @GetMapping
    public Map<String, Object> getAllMetrics() {
        Map<String, Object> response = new HashMap<>();
        
        Map<String, SkillMetrics> allMetrics = metricsRecorder.getAllMetrics();
        Map<String, Map<String, Object>> metricsData = new HashMap<>();
        
        for (Map.Entry<String, SkillMetrics> entry : allMetrics.entrySet()) {
            SkillMetrics m = entry.getValue();
            Map<String, Object> data = new HashMap<>();
            data.put("totalCalls", m.getTotalCalls().get());
            data.put("successCalls", m.getSuccessCalls().get());
            data.put("failedCalls", m.getFailedCalls().get());
            data.put("successRate", m.getSuccessRate());
            data.put("averageExecutionTimeMs", m.getAverageExecutionTimeMs());
            data.put("maxExecutionTimeMs", m.getMaxExecutionTimeMs().get());
            metricsData.put(entry.getKey(), data);
        }
        
        response.put("success", true);
        response.put("data", metricsData);
        response.put("timestamp", System.currentTimeMillis());
        
        return response;
    }
    
    /**
     * 重置所有指标
     * 
     * @return 操作结果
     */
    @PostMapping("/reset")
    public Map<String, Object> resetMetrics() {
        metricsRecorder.resetAll();
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "指标已重置");
        return response;
    }
}
