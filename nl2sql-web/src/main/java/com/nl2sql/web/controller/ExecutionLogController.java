package com.nl2sql.web.controller;

import com.nl2sql.common.result.Result;
import com.nl2sql.core.executor.SQLExecutionLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/admin")
public class ExecutionLogController {
    
    private final SQLExecutionLogService logService;
    
    public ExecutionLogController(SQLExecutionLogService logService) {
        this.logService = logService;
    }
    
    /**
     * 查询执行日志列表
     */
    @GetMapping("/execution-logs/list")
    public Result<List<Map<String, Object>>> getLogs(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Boolean isSlowQuery,
            @RequestParam(defaultValue = "100") int limit) {
        try {
            SQLExecutionLogService.QueryCondition condition = new SQLExecutionLogService.QueryCondition();
            condition.setStartDate(startDate);
            condition.setEndDate(endDate);
            condition.setStatus(status);
            condition.setIsSlowQuery(isSlowQuery);
            condition.setLimit(limit);
            
            List<Map<String, Object>> logs = logService.queryLogs(condition);
            return Result.success(logs);
        } catch (Exception e) {
            log.error("查询执行日志失败", e);
            return Result.error("查询失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取统计信息
     */
    @GetMapping("/execution-logs/stats")
    public Result<Map<String, Object>> getStats(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        try {
            Map<String, Object> stats = logService.getStatistics(null, startDate, endDate);
            return Result.success(stats);
        } catch (Exception e) {
            log.error("获取统计信息失败", e);
            return Result.error("统计失败: " + e.getMessage());
        }
    }
}
