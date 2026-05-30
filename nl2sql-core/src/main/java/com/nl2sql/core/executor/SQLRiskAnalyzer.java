package com.nl2sql.core.executor;

import com.nl2sql.core.datasource.DataSourceManager;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@SuppressWarnings("unused") // JdbcTemplate 保留用于动态数据源访问（通过 DataSourceManager）
public class SQLRiskAnalyzer {
    
    private final DataSourceManager dataSourceManager;
    private final RedisTemplate<String, Object> redisTemplate;
    
    @Value("${sql.execution.table-stats-cache-hours:12}")
    private long cacheHours;
    
    private static final String TABLE_STATS_CACHE_KEY = "sql:tablestats:%s:%s"; // datasourceId:tableName
    private static final String EXPLAIN_CACHE_KEY = "sql:explain:%s:%s"; // datasourceId:md5(sql)
    
    public SQLRiskAnalyzer(DataSourceManager dataSourceManager, RedisTemplate<String, Object> redisTemplate) {
        this.dataSourceManager = dataSourceManager;
        this.redisTemplate = redisTemplate;
    }
    
    /**
     * 分析SQL风险
     */
    public RiskAnalysisResult analyzeRisk(String sql, Long datasourceId) {
        RiskAnalysisResult result = new RiskAnalysisResult();
        result.setSql(sql);
        
        if (datasourceId == null) {
            log.warn("[SQLRiskAnalyzer] datasourceId 为 null，无法执行 EXPLAIN");
            result.setRiskLevel("HIGH");
            result.getRisks().add("缺少数据源ID，无法执行风险分析");
            return result;
        }
        
        try {
            // ✅ 关键优化：尝试从缓存获取 EXPLAIN 结果
            String explainCacheKey = String.format(EXPLAIN_CACHE_KEY, datasourceId, md5(sql));
            RiskAnalysisResult cached = (RiskAnalysisResult) redisTemplate.opsForValue().get(explainCacheKey);
            
            if (cached != null) {
                log.info("[SQLRiskAnalyzer] ⚡ EXPLAIN 缓存命中: datasourceId={}, sql={}", datasourceId, sql.substring(0, Math.min(50, sql.length())));
                return cached;
            }
            
            // 获取对应数据源的 JdbcTemplate
            JdbcTemplate jdbcTemplate = dataSourceManager.getJdbcTemplate(datasourceId);
            
            // 1. 执行EXPLAIN获取执行计划
            List<Map<String, Object>> explainResult = executeExplain(sql, jdbcTemplate);
            result.setExplainResult(explainResult);
            
            // 2. 提取涉及的表
            Set<String> tables = extractTablesFromExplain(explainResult);
            result.setInvolvedTables(new ArrayList<>(tables));
            
            // 3. 获取表统计信息(带缓存)
            Map<String, TableStats> tableStatsMap = new HashMap<>();
            for (String table : tables) {
                TableStats stats = getTableStatsWithCache(table, datasourceId, jdbcTemplate);
                tableStatsMap.put(table, stats);
            }
            result.setTableStats(tableStatsMap);
            
            // 4. 风险评估
            List<String> risks = assessRisks(explainResult, tableStatsMap);
            result.setRisks(risks);
            result.setRiskLevel(calculateRiskLevel(risks));
            
            // 5. 生成优化建议
            List<String> suggestions = generateSuggestions(explainResult, tableStatsMap, risks);
            result.setSuggestions(suggestions);
            
            log.info("SQL风险分析完成: datasourceId={}, 风险等级={}, 风险点={}", datasourceId, result.getRiskLevel(), risks.size());
            
            // ✅ 关键优化：缓存 EXPLAIN 结果（1小时）
            redisTemplate.opsForValue().set(explainCacheKey, result, 1, TimeUnit.HOURS);
            log.info("[SQLRiskAnalyzer] EXPLAIN 结果已缓存: key={}", explainCacheKey);
            
        } catch (Exception e) {
            log.error("SQL风险分析失败: datasourceId={}", datasourceId, e);
            result.setRiskLevel("HIGH");
            result.getRisks().add("分析失败: " + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * 兼容旧接口（不推荐使用）
     * @deprecated 使用 analyzeRisk(String sql, Long datasourceId)
     */
    @Deprecated
    public RiskAnalysisResult analyzeRisk(String sql) {
        log.warn("[SQLRiskAnalyzer] 调用了已弃用的方法 analyzeRisk(String)，请使用 analyzeRisk(String, Long)");
        RiskAnalysisResult result = new RiskAnalysisResult();
        result.setSql(sql);
        result.setRiskLevel("HIGH");
        result.getRisks().add("未指定数据源ID，无法执行分析");
        return result;
    }
    
    /**
     * 执行EXPLAIN
     */
    private List<Map<String, Object>> executeExplain(String sql, JdbcTemplate jdbcTemplate) {
        try {
            String explainSQL = "EXPLAIN " + sql;
            return jdbcTemplate.queryForList(explainSQL);
        } catch (Exception e) {
            log.warn("EXPLAIN执行失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
    
    /**
     * 从EXPLAIN结果提取表名
     */
    private Set<String> extractTablesFromExplain(List<Map<String, Object>> explainResult) {
        Set<String> tables = new HashSet<>();
        
        for (Map<String, Object> row : explainResult) {
            Object table = row.get("table");
            if (table != null && !table.toString().isEmpty()) {
                tables.add(table.toString().toLowerCase());
            }
        }
        
        return tables;
    }
    
    /**
     * 获取表统计信息(带缓存)
     */
    private TableStats getTableStatsWithCache(String tableName, Long datasourceId, JdbcTemplate jdbcTemplate) {
        try {
            // 先查缓存（包含 datasourceId）
            String cacheKey = String.format(TABLE_STATS_CACHE_KEY, datasourceId, tableName);
            TableStats cached = (TableStats) redisTemplate.opsForValue().get(cacheKey);
            
            if (cached != null) {
                log.debug("命中表统计缓存: datasourceId={}, table={}", datasourceId, tableName);
                return cached;
            }
            
            // 查数据库
            TableStats stats = fetchTableStats(tableName, jdbcTemplate);
            
            // 写入缓存
            if (stats != null) {
                redisTemplate.opsForValue().set(cacheKey, stats, cacheHours, TimeUnit.HOURS);
                log.info("更新表统计缓存: datasourceId={}, table={}, rows={}", datasourceId, tableName, stats.getRowCount());
            }
            
            return stats;
            
        } catch (Exception e) {
            log.error("获取表统计信息失败: datasourceId={}, table={}", datasourceId, tableName, e);
            return new TableStats();
        }
    }
    
    /**
     * 从数据库获取表统计信息
     */
    private TableStats fetchTableStats(String tableName, JdbcTemplate jdbcTemplate) {
        try {
            String dbSchema = jdbcTemplate.getDataSource().getConnection().getCatalog();
            
            // 获取表行数和大小
            String statsSQL = "SELECT table_rows, data_length, index_length " +
                             "FROM information_schema.tables " +
                             "WHERE table_schema = ? AND table_name = ?";
            
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(statsSQL, dbSchema, tableName);
            
            if (rows.isEmpty()) {
                return null;
            }
            
            Map<String, Object> row = rows.get(0);
            TableStats stats = new TableStats();
            stats.setTableName(tableName);
            stats.setRowCount(((Number) row.get("table_rows")).longValue());
            stats.setDataSize(((Number) row.get("data_length")).longValue());
            stats.setIndexSize(((Number) row.get("index_length")).longValue());
            
            // 获取索引信息
            List<IndexInfo> indexes = fetchIndexInfo(tableName, jdbcTemplate);
            stats.setIndexes(indexes);
            
            return stats;
            
        } catch (Exception e) {
            log.error("查询表统计信息失败: {}", tableName, e);
            return null;
        }
    }
    
    /**
     * 获取表的索引信息
     */
    private List<IndexInfo> fetchIndexInfo(String tableName, JdbcTemplate jdbcTemplate) {
        try {
            String dbSchema = jdbcTemplate.getDataSource().getConnection().getCatalog();
            
            String indexSQL = "SELECT index_name, column_name, non_unique, seq_in_index " +
                             "FROM information_schema.statistics " +
                             "WHERE table_schema = ? AND table_name = ? " +
                             "ORDER BY index_name, seq_in_index";
            
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(indexSQL, dbSchema, tableName);
            
            Map<String, IndexInfo> indexMap = new LinkedHashMap<>();
            
            for (Map<String, Object> row : rows) {
                String indexName = (String) row.get("index_name");
                
                if (!indexMap.containsKey(indexName)) {
                    IndexInfo index = new IndexInfo();
                    index.setIndexName(indexName);
                    index.setUnique(((Number) row.get("non_unique")).intValue() == 0);
                    index.setColumns(new ArrayList<>());
                    indexMap.put(indexName, index);
                }
                
                indexMap.get(indexName).getColumns().add((String) row.get("column_name"));
            }
            
            return new ArrayList<>(indexMap.values());
            
        } catch (Exception e) {
            log.error("查询索引信息失败: {}", tableName, e);
            return Collections.emptyList();
        }
    }
    
    /**
     * 评估风险
     */
    private List<String> assessRisks(List<Map<String, Object>> explainResult, 
                                     Map<String, TableStats> tableStatsMap) {
        List<String> risks = new ArrayList<>();
        
        for (Map<String, Object> row : explainResult) {
            String type = (String) row.get("type");
            String key = (String) row.get("key");  // 实际使用的索引
            String extra = (String) row.get("extra");
            Object tableObj = row.get("table");
            
            if (tableObj == null) continue;
            String table = tableObj.toString().toLowerCase();
            
            TableStats stats = tableStatsMap.get(table);
            long rowCount = stats != null ? stats.getRowCount() : 0;
            List<IndexInfo> indexes = stats != null ? stats.getIndexes() : Collections.emptyList();
            
            // 风险1: 全表扫描
            if ("ALL".equals(type)) {
                risks.add(String.format("表[%s]进行全表扫描(行数:%d)", table, rowCount));
            }
            
            // 风险2: 大表全表扫描
            if ("ALL".equals(type) && rowCount > 100000) {
                risks.add(String.format("⚠️ 高危: 大表[%s]全表扫描(%d行)，可能导致性能问题", table, rowCount));
            }
            
            // 风险3: 有索引但未使用
            if ("ALL".equals(type) && !indexes.isEmpty()) {
                String indexNames = indexes.stream()
                    .map(IndexInfo::getIndexName)
                    .filter(name -> !name.equals("PRIMARY"))
                    .limit(3)
                    .collect(java.util.stream.Collectors.joining(", "));
                
                if (!indexNames.isEmpty()) {
                    risks.add(String.format("表[%s]有可用索引[%s]但EXPLAIN未使用", table, indexNames));
                }
            }
            
            // 风险4: 未使用索引
            if (extra != null && extra.contains("Using filesort")) {
                risks.add(String.format("表[%s]需要文件排序，可能影响性能", table));
            }
            
            // 风险5: 临时表
            if (extra != null && extra.contains("Using temporary")) {
                risks.add(String.format("表[%s]需要使用临时表，可能消耗较多内存", table));
            }
            
            // 风险6: JOIN无索引
            if ("ALL".equals(type) && explainResult.size() > 1) {
                risks.add(String.format("JOIN操作中表[%s]未使用索引", table));
            }
            
            // 风险7: 索引扫描但行数过多
            if ("index".equals(type) && rowCount > 500000) {
                risks.add(String.format("表[%s]进行索引全扫描(行数:%d)，可能效率低下", table, rowCount));
            }
        }
        
        return risks;
    }
    
    /**
     * 计算风险等级
     */
    private String calculateRiskLevel(List<String> risks) {
        if (risks.isEmpty()) {
            return "LOW";
        }
        
        long highRiskCount = risks.stream()
            .filter(r -> r.contains("高危") || r.contains("⚠️"))
            .count();
        
        if (highRiskCount > 0) {
            return "HIGH";
        } else if (risks.size() > 2) {
            return "MEDIUM";
        } else {
            return "LOW";
        }
    }
    
    /**
     * 生成优化建议
     */
    private List<String> generateSuggestions(List<Map<String, Object>> explainResult,
                                            Map<String, TableStats> tableStatsMap,
                                            List<String> risks) {
        List<String> suggestions = new ArrayList<>();
        
        // ✅ LOW风险时不添加任何建议
        if (risks.isEmpty()) {
            return suggestions;
        }
        
        for (String risk : risks) {
            if (risk.contains("全表扫描") && !risk.contains("有可用索引")) {
                suggestions.add("💡 建议添加合适的WHERE条件或使用索引");
            }
            if (risk.contains("有可用索引") && risk.contains("但EXPLAIN未使用")) {
                // 提取表名和索引信息
                String table = extractTableNameFromRisk(risk);
                TableStats stats = tableStatsMap.get(table);
                if (stats != null && !stats.getIndexes().isEmpty()) {
                    String indexColumns = stats.getIndexes().stream()
                        .filter(idx -> !idx.getIndexName().equals("PRIMARY"))
                        .map(idx -> idx.getColumns().get(0))
                        .limit(2)
                        .collect(java.util.stream.Collectors.joining(", "));
                    
                    if (!indexColumns.isEmpty()) {
                        suggestions.add(String.format("💡 检查WHERE条件是否匹配现有索引字段: [%s]，或调整查询条件以利用索引", indexColumns));
                    }
                }
            }
            if (risk.contains("文件排序")) {
                String table = extractTableNameFromRisk(risk);
                suggestions.add(String.format("💡 建议在ORDER BY字段上创建索引（表: %s）", table));
            }
            if (risk.contains("临时表")) {
                suggestions.add("💡 建议优化GROUP BY或DISTINCT操作");
            }
            if (risk.contains("JOIN") && risk.contains("未使用索引")) {
                String table = extractTableNameFromRisk(risk);
                suggestions.add(String.format("💡 建议在JOIN字段上创建索引（表: %s）", table));
            }
            if (risk.contains("索引全扫描")) {
                suggestions.add("💡 索引全扫描效率低，建议添加WHERE条件缩小扫描范围");
            }
        }
        
        return suggestions;
    }
    
    /**
     * 从风险信息中提取表名
     */
    private String extractTableNameFromRisk(String risk) {
        try {
            int start = risk.indexOf("[") + 1;
            int end = risk.indexOf("]");
            if (start > 0 && end > start) {
                return risk.substring(start, end);
            }
        } catch (Exception e) {
            // ignore
        }
        return "unknown";
    }
    
    /**
     * 清除表统计缓存
     */
    public void clearTableStatsCache(String tableName) {
        String cacheKey = String.format(TABLE_STATS_CACHE_KEY, tableName);
        redisTemplate.delete(cacheKey);
        log.info("清除表统计缓存: {}", tableName);
    }
    
    /**
     * 计算字符串的 MD5 哈希值（用于生成缓存 key）
     */
    private String md5(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            // 降级：使用 hashCode
            return String.valueOf(Math.abs(input.hashCode()));
        }
    }
    
    // ==================== 数据类 ====================
    
    @Data
    public static class RiskAnalysisResult {
        private String sql;
        private List<Map<String, Object>> explainResult;
        private List<String> involvedTables;
        private Map<String, TableStats> tableStats;
        private List<String> risks;
        private String riskLevel; // LOW, MEDIUM, HIGH
        private List<String> suggestions;
        
        public RiskAnalysisResult() {
            this.explainResult = new ArrayList<>();
            this.involvedTables = new ArrayList<>();
            this.tableStats = new HashMap<>();
            this.risks = new ArrayList<>();
            this.suggestions = new ArrayList<>();
            this.riskLevel = "UNKNOWN";
        }
    }
    
    @Data
    public static class TableStats {
        private String tableName;
        private long rowCount;
        private long dataSize;      // 字节
        private long indexSize;     // 字节
        private List<IndexInfo> indexes;
        
        public TableStats() {
            this.indexes = new ArrayList<>();
        }
        
        public String getDataSizeFormatted() {
            return formatSize(dataSize);
        }
        
        public String getIndexSizeFormatted() {
            return formatSize(indexSize);
        }
        
        private String formatSize(long bytes) {
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
            if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
            return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }
    
    @Data
    public static class IndexInfo {
        private String indexName;
        private boolean unique;
        private List<String> columns;
    }
}
