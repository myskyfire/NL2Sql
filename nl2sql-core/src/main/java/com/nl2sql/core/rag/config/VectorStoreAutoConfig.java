package com.nl2sql.core.rag.config;

import com.nl2sql.core.rag.provider.ChromaVectorProvider;
import com.nl2sql.core.rag.provider.MilvusVectorProvider;
import com.nl2sql.core.rag.provider.MySqlVectorProvider;
import com.nl2sql.core.rag.provider.QdrantVectorProvider;
import com.nl2sql.core.rag.provider.VectorStoreManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.annotation.PostConstruct;

/**
 * 向量数据库提供者自动配置
 */
@Configuration
public class VectorStoreAutoConfig {
    
    @Value("${chroma.enabled:false}")
    private boolean chromaEnabled;
    
    @Value("${chroma.url:http://localhost:8000}")
    private String chromaUrl;
    
    @Value("${chroma.collection-name:NL2SQL_rag}")
    private String chromaCollectionName;
    
    @Value("${chroma.timeout:30}")
    private int chromaTimeout;
    
    @Value("${milvus.enabled:false}")
    private boolean milvusEnabled;
    
    @Value("${milvus.host:localhost}")
    private String milvusHost;
    
    @Value("${milvus.port:19530}")
    private int milvusPort;
    
    @Value("${milvus.collection-name:nl2sql_rag}")
    private String milvusCollectionName;
    
    @Value("${milvus.dimension:384}")
    private int milvusDimension;
    
    @Value("${qdrant.enabled:false}")
    private boolean qdrantEnabled;
    
    @Value("${qdrant.url:http://localhost:6333}")
    private String qdrantUrl;
    
    @Value("${qdrant.collection-name:nl2sql_knowledge}")
    private String qdrantCollectionName;
    
    @Value("${qdrant.api-key:}")
    private String qdrantApiKey;
    
    @Autowired
    private VectorStoreManager vectorStoreManager;
    
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    /**
     * 初始化所有向量数据库提供者
     */
    @PostConstruct
    public void initProviders() {
        // 1. 注册Chroma提供者（如果启用）
        if (chromaEnabled) {
            try {
                ChromaVectorProvider chromaProvider = new ChromaVectorProvider(
                    chromaUrl, 
                    chromaCollectionName, 
                    chromaTimeout
                );
                vectorStoreManager.registerProvider(chromaProvider);
                log.info("Chroma向量提供者已注册");
            } catch (Exception e) {
                log.warn("Chroma向量提供者注册失败: {}", e.getMessage());
            }
        } else {
            log.info("Chroma向量数据库已禁用");
        }
        
        // 2. 注册Milvus提供者（如果启用）
        if (milvusEnabled) {
            try {
                MilvusVectorProvider milvusProvider = new MilvusVectorProvider(
                    milvusHost,
                    milvusPort,
                    milvusCollectionName,
                    milvusDimension
                );
                vectorStoreManager.registerProvider(milvusProvider);
                log.info("Milvus向量提供者已注册");
            } catch (Exception e) {
                log.warn("Milvus向量提供者注册失败: {}", e.getMessage());
            }
        } else {
            log.info("Milvus向量数据库已禁用");
        }
        
        // 3. 注册Qdrant提供者（如果启用）
        if (qdrantEnabled) {
            try {
                QdrantVectorProvider qdrantProvider = new QdrantVectorProvider(
                    qdrantUrl,
                    qdrantCollectionName,
                    qdrantApiKey
                );
                vectorStoreManager.registerProvider(qdrantProvider);
                log.info("Qdrant向量提供者已注册");
            } catch (Exception e) {
                log.warn("Qdrant向量提供者注册失败: {}", e.getMessage());
            }
        } else {
            log.info("Qdrant向量数据库已禁用");
        }
        
        // 4. 注册MySQL提供者（始终可用，作为降级方案）
        try {
            MySqlVectorProvider mysqlProvider = new MySqlVectorProvider(jdbcTemplate);
            mysqlProvider.init();
            vectorStoreManager.registerProvider(mysqlProvider);
            log.info("MySQL向量提供者已注册（降级方案）");
        } catch (Exception e) {
            log.error("MySQL向量提供者注册失败: {}", e.getMessage());
        }
        
        // 5. 自动选择最优的可用提供者
        vectorStoreManager.autoSelectProvider();
    }
    
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(VectorStoreAutoConfig.class);
}
