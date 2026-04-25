package com.nl2sql.core.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.Map;

/**
 * 查询监控事件 - 异步记录监控数据
 */
@Getter
public class QueryMonitoringEvent extends ApplicationEvent {
    
    private final Map<String, Object> monitoringData;
    
    public QueryMonitoringEvent(Object source, Map<String, Object> monitoringData) {
        super(source);
        this.monitoringData = monitoringData;
    }
}
