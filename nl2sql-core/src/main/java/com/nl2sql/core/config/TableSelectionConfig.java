package com.nl2sql.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 表选择配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "table-selection")
public class TableSelectionConfig {
    
    /**
     * 自动选择数据源：当LLM唯一确定且confidence=high时，跳过前端确认
     */
    private boolean autoSelectDatasource = false;
}
