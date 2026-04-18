package com.nl2sql.common.event;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.context.ApplicationEvent;

/**
 * 流式对话进度事件
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class StreamProgressEvent extends ApplicationEvent {
    
    private String sessionId;
    private String step;          // generating_sql, sql_generated, executing, summarizing, chart_generating
    private String message;       // 显示消息
    private String sql;           // SQL语句
    private Object data;          // 数据（查询结果/图表配置等）
    private Integer rowCount;     // 记录数
    private Double executionTime; // 执行耗时
    
    public StreamProgressEvent(Object source, String sessionId, String step, String message, Object data) {
        super(source != null ? source : "stream-chat");
        this.sessionId = sessionId;
        this.step = step;
        this.message = message;
        this.data = data;
    }
    
    public static StreamProgressEvent creating(String sessionId) {
        return new StreamProgressEvent(null, sessionId, "generating_sql", "🔄 正在生成SQL...", null);
    }
    
    public static StreamProgressEvent sqlGenerated(String sessionId, String sql) {
        StreamProgressEvent event = new StreamProgressEvent(null, sessionId, "sql_generated", "✅ SQL生成并修正完成", null);
        event.setSql(sql);
        return event;
    }
    
    public static StreamProgressEvent executing(String sessionId) {
        return new StreamProgressEvent(null, sessionId, "executing", "⚙️ 执行查询，请稍后...", null);
    }
    
    public static StreamProgressEvent queryResult(String sessionId, Object data, int rowCount, double executionTime) {
        StreamProgressEvent event = new StreamProgressEvent(null, sessionId, "query_result", 
            String.format("✅ 查询到 %d 条结果，结果如下:", rowCount), data);
        event.setRowCount(rowCount);
        event.setExecutionTime(executionTime);
        return event;
    }
    
    public static StreamProgressEvent summarizing(String sessionId) {
        return new StreamProgressEvent(null, sessionId, "summarizing", "🤖 执行AI总结中...", null);
    }
    
    public static StreamProgressEvent summaryResult(String sessionId, String summary) {
        StreamProgressEvent event = new StreamProgressEvent(null, sessionId, "summary_result", "📝 AI总结如下:", summary);
        return event;
    }
    
    public static StreamProgressEvent generatingChart(String sessionId) {
        return new StreamProgressEvent(null, sessionId, "generating_chart", "📊 生成图表中...", null);
    }
    
    public static StreamProgressEvent chartResult(String sessionId, Object chartConfig) {
        StreamProgressEvent event = new StreamProgressEvent(null, sessionId, "chart_result", "📈 生成图表结果如下:", chartConfig);
        return event;
    }
    
    public static StreamProgressEvent completed(String sessionId) {
        return new StreamProgressEvent(null, sessionId, "complete", "✨ 查询完成", null);
    }
}
