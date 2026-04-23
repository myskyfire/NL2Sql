package com.nl2sql.core.agent.tools;

import com.nl2sql.core.agent.tool.BaseToolAdapter;
import com.nl2sql.core.agent.tool.ToolContext;
import com.nl2sql.core.mapper.MetadataMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 数据源检测工具 - 检测是否为单数据源环境
 */
@Slf4j
@Component
public class DatasourceDetectorTool extends BaseToolAdapter {
    
    @Autowired
    private MetadataMapper metadataMapper;
    
    @Override
    public String getName() { return "datasource_detector"; }
    
    @Override
    public String getDescription() { return "检测系统中数据源数量，判断是否为单数据源环境。"; }
    
    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", new HashMap<>());
        schema.put("required", new String[]{});
        return schema;
    }
    
    @Override
    public String getApplicableScenarios() { return "需要优化单数据源场景的流程"; }
    
    @Override
    public String getInapplicableScenarios() { return "多数据源场景"; }
    
    @Override
    protected void validateParameters(ToolContext context) {
        // 无必需参数
    }
    
    @Override
    protected Object doExecute(ToolContext context) throws Exception {
        Integer count = metadataMapper.countDistinctDatasources();
        boolean isSingleSource = count != null && count == 1;
        
        log.debug("[DatasourceDetector] 数据源数量: {}, 是否单数据源: {}", count, isSingleSource);
        
        return Map.of(
            "isSingleSource", isSingleSource,
            "totalCount", count != null ? count : 0
        );
    }
}
