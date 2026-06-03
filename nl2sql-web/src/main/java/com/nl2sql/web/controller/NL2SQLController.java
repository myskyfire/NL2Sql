package com.nl2sql.web.controller;

import com.nl2sql.audit.AuditService;
import com.nl2sql.auth.service.AuthService;
import com.nl2sql.common.result.Result;
import com.nl2sql.conversation.ConversationService;
import com.nl2sql.core.cache.QueryCacheService;
import com.nl2sql.core.datasource.DatasourceAccessService;
import com.nl2sql.core.executor.ExcelExportService;
import com.nl2sql.core.executor.SQLExecutor;
import com.nl2sql.core.retriever.VectorRetriever;
import com.nl2sql.core.metadata.MetadataService;
import com.nl2sql.core.visualization.ChartRecommendationService;
import com.nl2sql.web.service.NL2SQLDepService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.constraints.NotBlank;
import java.util.*;

/**
 * NL2SQL查询控制器 - 仅负责HTTP请求处理
 * 业务逻辑已委托给NL2SQLService
 */
@Slf4j
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class NL2SQLController {
    
    private final NL2SQLDepService nl2sqlService;
    private final MetadataService metadataService;
    private final VectorRetriever vectorRetriever;
    private final ConversationService conversationService;
    private final AuditService auditService;
    private final AuthService authService;
    private final ExcelExportService excelExportService;
    private final QueryCacheService cacheService;
    private final SQLExecutor sqlExecutor;
    private final ChartRecommendationService chartRecommendationService;
    private final DatasourceAccessService datasourceAccessService;
    
    public NL2SQLController(
        NL2SQLDepService nl2sqlService,
        MetadataService metadataService,
        VectorRetriever vectorRetriever,
        ConversationService conversationService,
        AuditService auditService,
        AuthService authService,
        ExcelExportService excelExportService,
        QueryCacheService cacheService,
        SQLExecutor sqlExecutor,
        ChartRecommendationService chartRecommendationService,
        DatasourceAccessService datasourceAccessService
    ) {
        this.nl2sqlService = nl2sqlService;
        this.metadataService = metadataService;
        this.vectorRetriever = vectorRetriever;
        this.conversationService = conversationService;
        this.auditService = auditService;
        this.authService = authService;
        this.excelExportService = excelExportService;
        this.cacheService = cacheService;
        this.sqlExecutor = sqlExecutor;
        this.chartRecommendationService = chartRecommendationService;
        this.datasourceAccessService = datasourceAccessService;
    }

    
    /**
     * NL2SQL查询接口 - 已废弃
     * @deprecated 请使用 /api/agent/chat 端点（真正的 ReAct Agent）
     */
    @Deprecated
    @PostMapping("/query")
    public Result<Map<String, Object>> query(
        @RequestBody @Validated QueryRequest request,
        @RequestHeader(value = "Authorization", required = false) String token
    ) {
        log.warn("[DEPRECATED] 调用了已废弃的 /api/query 端点，请使用 /api/agent/chat");
        
        Map<String, Object> response = new HashMap<>();
        response.put("status", "deprecated");
        response.put("message", "此接口已废弃，请使用 /api/agent/chat 端点获得更好的Agent体验");
        response.put("migrationGuide", "访问 http://localhost:8080/agent-chat.html 使用新的对话式界面");
        
        return Result.success(response);
    }
    
    /**
     * 验证用户Token
     */
    private AuthService.UserInfo validateUser(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        
        // 去除Bearer前缀（如果有）
        if (token.startsWith("Bearer ")) {
            token = token.substring(7);
        }
        
        return authService.validateToken(token);
    }
    
    /**
     * 创建错误响应
     */
    private Result<Map<String, Object>> createErrorResponse(String status, String error) {
        Map<String, Object> response = new HashMap<>();
        response.put("status", status);
        response.put("error", error);
        return Result.success(response);
    }
    
    /**
     * 导出Excel
     */
    @PostMapping("/export/excel")
    public Result<byte[]> exportExcel(
        @RequestBody ExportRequest request,
        @RequestHeader(value = "Authorization", required = false) String token
    ) {
        AuthService.UserInfo userInfo = validateUser(token);
        if (userInfo == null) {
            return Result.<byte[]>error("请先登录");
        }
        
        try {
            byte[] excelData = excelExportService.exportToExcel(
                request.getData(),
                request.getFileName()
            );
            return Result.success(excelData);
        } catch (Exception e) {
            log.error("导出Excel失败", e);
            return Result.<byte[]>error("导出失败: " + e.getMessage());
        }
    }
    
    /**
     * SQL纠错 - 已废弃
     * @deprecated 此功能已整合到NL2SQL生成流程中
     */
    @Deprecated
    @PostMapping("/correct")
    public Result<Map<String, Object>> correctSQL(
        @RequestBody CorrectRequest request,
        @RequestHeader(value = "Authorization", required = false) String token
    ) {
        return Result.error("此接口已废弃，SQL纠错已整合到NL2SQL生成流程中");
    }
    
    /**
     * 手动执行SQL
     */
    @PostMapping("/manual-sql")
    public Result<Map<String, Object>> executeManualSQL(
        @RequestBody ManualSQLRequest request,
        @RequestHeader(value = "Authorization", required = false) String token
    ) {
        AuthService.UserInfo userInfo = validateUser(token);
        if (userInfo == null) {
            return Result.error("请先登录");
        }
        
        if (request.getDatasourceId() == null) {
            return Result.error("请选择数据源");
        }
        
        try {
            log.info("[手动执行SQL] 用户={}, datasourceId={}", userInfo.getUsername(), request.getDatasourceId());
            
            Map<String, Object> response = new HashMap<>();
            
            if (datasourceAccessService.isMcpEnabled()) {
                // MCP 模式：通过 DatasourceAccessService 执行（自动降级到 JDBC）
                DatasourceAccessService.SqlExecutionResult mcpResult = 
                    datasourceAccessService.executeSql(request.getDatasourceId(), request.getSql());
                
                if (mcpResult.getError() != null) {
                    response.put("error", mcpResult.getError());
                } else {
                    response.put("data", mcpResult.getData());
                    response.put("rowCount", mcpResult.getRowCount());
                    response.put("executionTime", mcpResult.getExecutionTime());
                    
                    // 生成图表推荐（如果有数据）
                    if (mcpResult.getData() != null && !mcpResult.getData().isEmpty()) {
                        try {
                            ChartRecommendationService.ChartRecommendation chartRec = 
                                chartRecommendationService.recommendCharts("手动SQL查询", mcpResult.getData());
                            response.put("chartRecommendation", chartRec);
                            log.info("[手动执行SQL] 图表推荐完成: {}个图表", chartRec.getCharts().size());
                        } catch (Exception e) {
                            log.warn("[手动执行SQL] 图表推荐失败", e);
                        }
                    }
                }
            } else {
                // JDBC 模式：原有逻辑
                SQLExecutor.QueryResult result = sqlExecutor.executeQuery(
                    request.getSql(),
                    request.getDatasourceId(),
                    userInfo.getUserId(),
                    userInfo.getUsername(),
                    "unknown"
                );
                
                if (result.getError() != null) {
                    response.put("error", result.getError());
                } else {
                    response.put("data", result.getData());
                    response.put("rowCount", result.getRowCount());
                    response.put("executionTime", result.getExecutionTime());
                    
                    // 生成图表推荐（如果有数据）
                    if (result.getData() != null && !result.getData().isEmpty()) {
                        try {
                            ChartRecommendationService.ChartRecommendation chartRec = 
                                chartRecommendationService.recommendCharts("手动SQL查询", result.getData());
                            response.put("chartRecommendation", chartRec);
                            log.info("[手动执行SQL] 图表推荐完成: {}个图表", chartRec.getCharts().size());
                        } catch (Exception e) {
                            log.warn("[手动执行SQL] 图表推荐失败", e);
                        }
                    }
                }
            }
            
            return Result.success(response);
        } catch (Exception e) {
            log.error("[手动执行SQL] 失败", e);
            return Result.error("执行失败: " + e.getMessage());
        }
    }
    
    // ==================== 请求对象 ====================
    
    @Data
    public static class QueryRequest {
        @NotBlank(message = "查询语句不能为空")
        private String query;
        
        private Long datasourceId;
        
        private String sessionId;
        
        private String username;
        
        private String ipAddress;
        
        private Integer pageNum = 1;
        
        private Integer pageSize = 50;
        
        private Boolean skipSQLGeneration = false;
        
        private String sql;  // AI总结时传入的SQL语句
        
        private List<Map<String, Object>> data;  // AI总结时传入的查询结果数据
        
        private Boolean autoExecute = true;  // 是否自动执行SQL（默认true）
        
        private Boolean autoSummarize = false;  // 是否自动生成AI总结
        
        private Boolean autoChartRecommend = true;  // 是否自动推荐图表（默认true）
    }
    
    @Data
    public static class ExportRequest {
        private List<Map<String, Object>> data;
        private String fileName;
        private String sheetName;
    }
    
    @Data
    public static class CorrectRequest {
        private String sql;
        private String error;
    }
    
    @Data
    public static class ManualSQLRequest {
        private String sql;
        private Long datasourceId;
    }
}
