package com.nl2sql.core.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 配置化行业目标提取器
 * 从 industry_concept 表动态加载关键词配置，无需硬编码
 */
@Slf4j
public class ConfigurableIndustryTargetExtractor implements IndustryTargetExtractor {
    
    private final JdbcTemplate jdbcTemplate;
    private final String industryCode;
    
    public ConfigurableIndustryTargetExtractor(JdbcTemplate jdbcTemplate, String industryCode) {
        this.jdbcTemplate = jdbcTemplate;
        this.industryCode = industryCode;
        log.info("[ConfigurableExtractor] 初始化行业提取器: industryCode={}", industryCode);
    }
    
    @Override
    public String extract(String query) {
        // 调用接口默认实现：从配置表加载并匹配
        return IndustryTargetExtractor.extractFromConfig(query, jdbcTemplate, industryCode);
    }
}
