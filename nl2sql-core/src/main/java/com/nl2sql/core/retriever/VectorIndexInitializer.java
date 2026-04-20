package com.nl2sql.core.retriever;

import com.nl2sql.core.metadata.MetadataService;
import com.nl2sql.core.metadata.TableMetadata;
import com.nl2sql.metadata.entity.DataSourceConfig;
import com.nl2sql.metadata.service.DataSourceConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 向量索引初始化器
 * 
 * 职责：应用启动时，为每个数据源构建向量索引
 * 触发时机：Spring Boot 应用启动完成后自动执行
 */
@Slf4j
@Component
public class VectorIndexInitializer implements ApplicationRunner {
    
    private final DataSourceConfigService dataSourceConfigService;
    private final MetadataService metadataService;
    private final VectorRetriever vectorRetriever;
    
    public VectorIndexInitializer(
        DataSourceConfigService dataSourceConfigService,
        MetadataService metadataService,
        VectorRetriever vectorRetriever
    ) {
        this.dataSourceConfigService = dataSourceConfigService;
        this.metadataService = metadataService;
        this.vectorRetriever = vectorRetriever;
    }
    
    @Override
    public void run(ApplicationArguments args) throws Exception {
        log.info("========== 开始初始化向量索引 ==========");
        
        try {
            // 获取所有激活的数据源
            List<DataSourceConfig> datasources = dataSourceConfigService.listActiveConfigs();
            
            if (datasources == null || datasources.isEmpty()) {
                log.warn("没有可用的数据源，跳过向量索引初始化");
                return;
            }
            
            log.info("检测到 {} 个激活的数据源", datasources.size());
            
            int successCount = 0;
            int skipCount = 0;
            int failCount = 0;
            
            // 为每个数据源单独构建向量索引
            for (DataSourceConfig ds : datasources) {
                try {
                    // 获取该数据源的元数据
                    Map<String, TableMetadata> metadata = 
                        metadataService.getAllMetadataByDatasource(ds.getId());
                    
                    if (metadata != null && !metadata.isEmpty()) {
                        vectorRetriever.buildIndex(ds.getId(), metadata);
                        log.info("✅ 数据源 [{}] ({}) 向量索引构建完成，表数量: {}", 
                            ds.getName(), ds.getId(), metadata.size());
                        successCount++;
                    } else {
                        log.warn("⚠️ 数据源 [{}] ({}) 没有元数据，跳过", ds.getName(), ds.getId());
                        skipCount++;
                    }
                } catch (Exception e) {
                    log.error("❌ 数据源 [{}] ({}) 向量索引构建失败", ds.getName(), ds.getId(), e);
                    failCount++;
                }
            }
            
            log.info("========== 向量索引初始化完成 ==========");
            log.info("总计: {} 个数据源 | 成功: {} | 跳过: {} | 失败: {}", 
                datasources.size(), successCount, skipCount, failCount);
            
        } catch (Exception e) {
            log.error("向量索引初始化失败", e);
        }
    }
}
