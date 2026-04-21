package com.nl2sql.web.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 数据源会话管理服务
 * 
 * 职责：
 * - 会话级数据源缓存（避免重复LLM调用）
 * - 清除命令识别
 * - 失败降级建议
 */
@Slf4j
@Service
public class DatasourceSessionService {
    
    private static final String DATASOURCE_CACHE_KEY_PREFIX = "datasource:session:";
    private static final long CACHE_TTL_MINUTES = 30; // 缓存30分钟
    private static final long AUTO_RENEW_MINUTES = 5; // 成功查询后自动续期5分钟
    
    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;
    
    @Autowired(required = false)
    private JdbcTemplate jdbcTemplate;
    
    /**
     * ✅ 解析数据源ID（混合策略核心）
     * 
     * 优先级：
     * 1. 前端显式指定 → 直接使用并缓存
     * 2. Session缓存存在 → 使用缓存并续期
     * 3. 只有1个活跃数据源 → 自动选择并缓存
     * 4. 多个数据源且无缓存 → 返回null，由Agent处理
     */
    public Long resolveDatasourceId(String sessionId, Long frontendDatasourceId) {
        // 情况1：前端显式指定
        if (frontendDatasourceId != null) {
            log.debug("[数据源会话] 前端指定: {}", frontendDatasourceId);
            cacheDatasource(sessionId, frontendDatasourceId);
            return frontendDatasourceId;
        }
        
        // 情况2：从Session缓存读取
        if (redisTemplate != null && sessionId != null) {
            try {
                String cacheKey = DATASOURCE_CACHE_KEY_PREFIX + sessionId;
                String cachedDsId = redisTemplate.opsForValue().get(cacheKey);
                
                if (cachedDsId != null && !cachedDsId.isEmpty()) {
                    Long dsId = Long.parseLong(cachedDsId);
                    log.info("[数据源会话] ✅ 命中缓存: sessionId={}, datasourceId={}", sessionId, dsId);
                    
                    // 自动续期
                    renewCacheTTL(sessionId);
                    return dsId;
                }
            } catch (Exception e) {
                log.warn("[数据源会话] 读取缓存失败", e);
            }
        }
        
        // 情况3：尝试自动选择单数据源
        if (jdbcTemplate != null) {
            try {
                Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM datasource_config WHERE is_active = 1",
                    Integer.class
                );
                
                if (count != null && count == 1) {
                    Long singleDsId = jdbcTemplate.queryForObject(
                        "SELECT id FROM datasource_config WHERE is_active = 1 LIMIT 1",
                        Long.class
                    );
                    
                    if (singleDsId != null) {
                        log.info("[数据源会话] ✅ 自动选择唯一活跃数据源: {}", singleDsId);
                        cacheDatasource(sessionId, singleDsId);
                        return singleDsId;
                    }
                } else if (count != null && count > 1) {
                    log.debug("[数据源会话] 有{}个活跃数据源，交由Agent智能选择", count);
                }
            } catch (Exception e) {
                log.warn("[数据源会话] 自动选择失败", e);
            }
        }
        
        // 情况4：无法自动选择
        log.debug("[数据源会话] 无法自动选择，返回null");
        return null;
    }
    
    /**
     * ✅ 清除会话数据源缓存
     * 
     * 触发场景：
     * - 用户输入"清除上下文" / "切换数据源" / "重置"
     * - 连续2次找不到表错误
     */
    public void clearDatasourceCache(String sessionId) {
        if (redisTemplate == null || sessionId == null) {
            return;
        }
        
        try {
            String cacheKey = DATASOURCE_CACHE_KEY_PREFIX + sessionId;
            Boolean deleted = redisTemplate.delete(cacheKey);
            
            if (Boolean.TRUE.equals(deleted)) {
                log.info("[数据源会话] 🗑️ 已清除缓存: sessionId={}", sessionId);
            }
        } catch (Exception e) {
            log.warn("[数据源会话] 清除缓存失败", e);
        }
    }
    
    /**
     * ✅ 检测是否为清除命令
     */
    public boolean isClearCommand(String message) {
        if (message == null || message.trim().isEmpty()) {
            return false;
        }
        
        String lowerMessage = message.toLowerCase().trim();
        
        // 关键词匹配
        return lowerMessage.contains("清除上下文") 
            || lowerMessage.contains("清除当前")
            || lowerMessage.contains("切换数据源")
            || lowerMessage.contains("重置")
            || lowerMessage.contains("reset")
            || lowerMessage.contains("clear context")
            || lowerMessage.contains("switch datasource");
    }
    
    /**
     * ✅ 记录查询成功（用于自动续期）
     */
    public void recordSuccess(String sessionId) {
        if (redisTemplate == null || sessionId == null) {
            return;
        }
        
        try {
            renewCacheTTL(sessionId);
        } catch (Exception e) {
            log.warn("[数据源会话] 续期失败", e);
        }
    }
    
    /**
     * ✅ 记录查询失败（用于连续失败检测）
     * 
     * @return true表示达到阈值，应清除缓存
     */
    public boolean recordFailure(String sessionId) {
        if (redisTemplate == null || sessionId == null) {
            return false;
        }
        
        try {
            String failureKey = DATASOURCE_CACHE_KEY_PREFIX + "failure:" + sessionId;
            
            // 递增失败计数
            Long count = redisTemplate.opsForValue().increment(failureKey);
            
            // 设置过期时间（5分钟）
            if (count != null && count == 1) {
                redisTemplate.expire(failureKey, 5, TimeUnit.MINUTES);
            }
            
            // 连续2次失败，清除缓存
            if (count != null && count >= 2) {
                log.warn("[数据源会话] ⚠️ 连续{}次失败，清除缓存", count);
                clearDatasourceCache(sessionId);
                redisTemplate.delete(failureKey); // 重置计数器
                return true;
            }
            
            return false;
        } catch (Exception e) {
            log.warn("[数据源会话] 记录失败失败", e);
            return false;
        }
    }
    
    /**
     * ✅ 获取其他可用数据源列表（用于失败降级建议）
     */
    public List<Map<String, Object>> getAlternativeDatasources(Long currentDsId) {
        if (jdbcTemplate == null) {
            return List.of();
        }
        
        try {
            if (currentDsId == null) {
                return jdbcTemplate.queryForList(
                    "SELECT id, name, database_name, description FROM datasource_config WHERE is_active = 1 ORDER BY name"
                );
            } else {
                return jdbcTemplate.queryForList(
                    "SELECT id, name, database_name, description FROM datasource_config WHERE is_active = 1 AND id != ? ORDER BY name",
                    currentDsId
                );
            }
        } catch (Exception e) {
            log.warn("[数据源会话] 获取备选数据源失败", e);
            return List.of();
        }
    }
    
    /**
     * ✅ 检查表是否存在于指定数据源
     */
    public boolean tableExistsInDatasource(Long datasourceId, String tableName) {
        if (jdbcTemplate == null || datasourceId == null || tableName == null) {
            return false;
        }
        
        try {
            // 先获取数据源的数据库名
            String dbName = jdbcTemplate.queryForObject(
                "SELECT database_name FROM datasource_config WHERE id = ?",
                String.class,
                datasourceId
            );
            
            if (dbName == null) {
                return false;
            }
            
            // 检查表是否存在
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = ?",
                Integer.class,
                dbName,
                tableName
            );
            
            return count != null && count > 0;
        } catch (Exception e) {
            log.debug("[数据源会话] 检查表存在性失败: table={}", tableName, e);
            return false;
        }
    }
    
    // ==================== 私有方法 ====================
    
    /**
     * 缓存数据源ID到Session
     */
    private void cacheDatasource(String sessionId, Long datasourceId) {
        if (redisTemplate == null || sessionId == null || datasourceId == null) {
            return;
        }
        
        try {
            String cacheKey = DATASOURCE_CACHE_KEY_PREFIX + sessionId;
            redisTemplate.opsForValue().set(cacheKey, String.valueOf(datasourceId), CACHE_TTL_MINUTES, TimeUnit.MINUTES);
            log.debug("[数据源会话] 已缓存: sessionId={}, datasourceId={}, TTL={}min", sessionId, datasourceId, CACHE_TTL_MINUTES);
        } catch (Exception e) {
            log.warn("[数据源会话] 缓存失败", e);
        }
    }
    
    /**
     * 续期缓存TTL
     */
    private void renewCacheTTL(String sessionId) {
        if (redisTemplate == null || sessionId == null) {
            return;
        }
        
        try {
            String cacheKey = DATASOURCE_CACHE_KEY_PREFIX + sessionId;
            Boolean exists = redisTemplate.hasKey(cacheKey);
            
            if (Boolean.TRUE.equals(exists)) {
                redisTemplate.expire(cacheKey, CACHE_TTL_MINUTES, TimeUnit.MINUTES);
                log.debug("[数据源会话] 已续期: sessionId={}", sessionId);
            }
        } catch (Exception e) {
            log.warn("[数据源会话] 续期失败", e);
        }
    }
}
