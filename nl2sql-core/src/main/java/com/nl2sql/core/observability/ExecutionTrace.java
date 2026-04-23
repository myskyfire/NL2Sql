package com.nl2sql.core.observability;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 执行追踪记录
 * 
 * 记录Agent执行的完整链路，用于性能分析和故障排查
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionTrace {
    
    /**
     * 追踪ID（唯一标识）
     */
    private String traceId;
    
    /**
     * 会话ID
     */
    private String sessionId;
    
    /**
     * 用户ID
     */
    private Long userId;
    
    /**
     * 开始时间
     */
    private LocalDateTime startTime;
    
    /**
     * 结束时间
     */
    private LocalDateTime endTime;
    
    /**
     * 总耗时（毫秒）
     */
    private Long totalDurationMs;
    
    /**
     * 执行阶段列表
     */
    @Builder.Default
    private java.util.List<TraceSpan> spans = new java.util.ArrayList<>();
    
    /**
     * 元数据
     */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();
    
    /**
     * 是否成功
     */
    private Boolean success;
    
    /**
     * 错误信息（如果失败）
     */
    private String errorMessage;
    
    /**
     * 追踪跨度
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TraceSpan {
        /**
         * 阶段名称
         */
        private String stage;
        
        /**
         * 开始时间
         */
        private LocalDateTime startTime;
        
        /**
         * 结束时间
         */
        private LocalDateTime endTime;
        
        /**
         * 耗时（毫秒）
         */
        private Long durationMs;
        
        /**
         * 状态（success/error/skipped）
         */
        private String status;
        
        /**
         * 详细信息
         */
        private Map<String, Object> details;
        
        /**
         * 创建成功的span
         */
        public static TraceSpan success(String stage, long durationMs) {
            return TraceSpan.builder()
                .stage(stage)
                .startTime(LocalDateTime.now().minusNanos(durationMs * 1_000_000))
                .endTime(LocalDateTime.now())
                .durationMs(durationMs)
                .status("success")
                .details(new HashMap<>())
                .build();
        }
        
        /**
         * 创建失败的span
         */
        public static TraceSpan error(String stage, long durationMs, String errorMessage) {
            TraceSpan span = success(stage, durationMs);
            span.setStatus("error");
            span.getDetails().put("errorMessage", errorMessage);
            return span;
        }
    }
    
    /**
     * 添加span
     */
    public void addSpan(TraceSpan span) {
        this.spans.add(span);
    }
    
    /**
     * 计算总耗时
     */
    public void calculateTotalDuration() {
        if (startTime != null && endTime != null) {
            this.totalDurationMs = java.time.Duration.between(startTime, endTime).toMillis();
        }
    }
    
    /**
     * 生成摘要信息
     */
    public String generateSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[Trace %s] 总耗时: %dms\n", traceId, totalDurationMs));
        sb.append(String.format("会话ID: %s, 用户ID: %d\n", sessionId, userId));
        sb.append(String.format("状态: %s\n", success ? "成功" : "失败"));
        
        if (!spans.isEmpty()) {
            sb.append("\n执行阶段:\n");
            for (TraceSpan span : spans) {
                String icon = "success".equals(span.getStatus()) ? "✓" : "✗";
                sb.append(String.format("  %s %s: %dms\n", icon, span.getStage(), span.getDurationMs()));
            }
        }
        
        return sb.toString();
    }
}
