package com.nl2sql.core.config;

import com.nl2sql.core.cache.ConfigurableIndustryTargetExtractor;
import com.nl2sql.core.cache.IndustryTargetExtractor;
import com.nl2sql.core.cache.QueryStructureExtractor;
import com.nl2sql.metadata.mapper.IndustryConceptAdminMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * 查询结构提取器配置
 * 根据数据源动态选择行业目标提取器
 */
@Slf4j
@Configuration
public class QueryStructureExtractorConfig {
    
    /**
     * 创建行业提取器工厂（根据数据源ID返回对应的提取器）
     */
    @Bean
    public IndustryTargetExtractorFactory industryTargetExtractorFactory(JdbcTemplate jdbcTemplate, IndustryConceptAdminMapper adminMapper) {
        return new IndustryTargetExtractorFactory(jdbcTemplate, adminMapper);
    }
    
    /**
     * 行业提取器工厂
     * ✅ 重构：从硬编码实例改为动态创建配置化提取器
     */
    public static class IndustryTargetExtractorFactory {
        private final JdbcTemplate jdbcTemplate;
        private final IndustryConceptAdminMapper adminMapper;
        private final Map<String, IndustryTargetExtractor> extractorCache = new HashMap<>();
        
        public IndustryTargetExtractorFactory(JdbcTemplate jdbcTemplate, IndustryConceptAdminMapper adminMapper) {
            this.jdbcTemplate = jdbcTemplate;
            this.adminMapper = adminMapper;
            // ✅ 不再预注册硬编码实例，改为按需动态创建
        }
        
        /**
         * 根据数据源ID获取行业提取器
         * ✅ 重构：优先使用配置化提取器，降级到通用提取器
         */
        public IndustryTargetExtractor getExtractor(Long datasourceId) {
            if (datasourceId == null) {
                return null;
            }
            
            try {
                // 1. 从数据库查询数据源的行业代码
                String industryCode = adminMapper.selectIndustryCodeByDatasourceId(datasourceId);
                
                if (industryCode == null) {
                    log.debug("[IndustryExtractor] 数据源 {} 未配置行业，使用通用提取器", datasourceId);
                    return null;
                }
                
                // 2. 从缓存获取或动态创建配置化提取器
                IndustryTargetExtractor extractor = extractorCache.computeIfAbsent(industryCode, code -> {
                    log.info("[IndustryExtractor] 创建配置化提取器: industryCode={}", code);
                    return new ConfigurableIndustryTargetExtractor(jdbcTemplate, code);
                });
                
                log.debug("[IndustryExtractor] 数据源 {} 使用 {} 行业提取器（配置化）", datasourceId, industryCode);
                return extractor;
                
            } catch (Exception e) {
                log.warn("[IndustryExtractor] 查询行业配置失败: datasourceId={}", datasourceId, e);
                return null;
            }
        }
    }
}
