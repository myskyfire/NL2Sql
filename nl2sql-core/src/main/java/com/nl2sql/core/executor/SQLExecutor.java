package com.nl2sql.core.executor;

import com.nl2sql.core.datasource.DataSourceManager;
import com.nl2sql.core.metadata.ValueMappingService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@Slf4j
@Service
public class SQLExecutor {
    
    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;
    private final SQLExecutionLogService executionLogService;
    private final DataSourceManager dataSourceManager;  // 动态数据源管理器
    private final ValueMappingService valueMappingService;  // 值映射服务
    private final com.nl2sql.core.agent.tools.NL2SQLTool nl2sqlTool;  // LLM翻译工具
    private final com.nl2sql.core.llm.ModelRouterService modelRouter;  // 模型路由服务
    private final com.nl2sql.core.cache.QueryCacheService queryCacheService;  // 查询结果缓存
    private final com.nl2sql.core.cache.MetadataCacheService metadataCacheService;  // 元数据缓存服务
    
    @Value("${sql.execution.query-timeout:30}")
    private int queryTimeout;
    
    @Value("${sql.execution.slow-query-threshold:5}")
    private double slowQueryThreshold;
    
    @Value("${sql.execution.max-rows:100}")
    private int maxRows;
    
    // 字段名映射缓存：key=datasourceId_tableName, value=Map<英文字段名, 中文注释>
    private static final java.util.concurrent.ConcurrentHashMap<String, Map<String, String>> COLUMN_NAME_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    
    private static final ExecutorService executorService = Executors.newCachedThreadPool();
    
    public SQLExecutor(JdbcTemplate jdbcTemplate, DataSource dataSource, 
                      SQLExecutionLogService executionLogService,
                      DataSourceManager dataSourceManager,
                      ValueMappingService valueMappingService,
                      com.nl2sql.core.agent.tools.NL2SQLTool nl2sqlTool,
                      com.nl2sql.core.llm.ModelRouterService modelRouter,
                      com.nl2sql.core.cache.QueryCacheService queryCacheService,
                      com.nl2sql.core.cache.MetadataCacheService metadataCacheService) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
        this.executionLogService = executionLogService;
        this.dataSourceManager = dataSourceManager;
        this.valueMappingService = valueMappingService;
        this.nl2sqlTool = nl2sqlTool;
        this.modelRouter = modelRouter;
        this.queryCacheService = queryCacheService;
        this.metadataCacheService = metadataCacheService;
    }
    
    @Data
    public static class QueryResult {
        private List<Map<String, Object>> data;
        private String error;
        private double executionTime;
        private int rowCount;
    }
    
    /**
     * 执行查询（使用默认数据源）
     */
    public QueryResult executeQuery(String sql, Long userId, String username, String ipAddress) {
        return executeQuery(sql, null, userId, username, ipAddress);
    }
    
    /**
     * 执行查询（支持指定数据源）
     */
    public QueryResult executeQuery(String sql, Long datasourceId, Long userId, String username, String ipAddress) {
        QueryResult result = new QueryResult();
        long startTime = System.currentTimeMillis();
        
        // ✅ 步骤1：尝试从缓存获取结果
        if (queryCacheService != null) {
            try {
                com.nl2sql.core.cache.QueryCacheService.CachedResult cachedResult = 
                    queryCacheService.getFromCache(sql);
                
                if (cachedResult != null && cachedResult.getData() != null) {
                    long cacheHitTime = System.currentTimeMillis() - startTime;
                    log.info("[✅ 缓存命中] SQL查询结果来自缓存，耗时={}ms, 行数={}", 
                        cacheHitTime, cachedResult.getData().size());
                    
                    result.setData(cachedResult.getData());
                    result.setRowCount(cachedResult.getRowCount());
                    result.setExecutionTime(cacheHitTime / 1000.0);
                    result.setError(null);
                    
                    return result; // 直接返回缓存结果
                }
            } catch (Exception e) {
                log.warn("[缓存读取失败] 继续执行数据库查询: {}", e.getMessage());
            }
        }
        
        log.debug("[❌ 缓存未命中] 执行数据库查询...");
        
        // 最终安全检查：确保只执行查询操作
        String upperSQL = sql.trim().toUpperCase();
        if (!upperSQL.startsWith("SELECT") && !upperSQL.startsWith("SHOW") && 
            !upperSQL.startsWith("DESC") && !upperSQL.startsWith("EXPLAIN")) {
            result.setError("安全拦截：仅允许执行SELECT/SHOW/DESC/EXPLAIN查询语句");
            result.setExecutionTime(0);
            log.error("[安全拦截] 尝试执行非查询SQL: {}", sql);
            return result;
        }
        
        Connection conn = null;
        Statement stmt = null;
        ResultSet rs = null;
        
        try {
            log.info("执行查询SQL: {}", sql);
            
            // 应用值映射（将自然语言值转换为数据库实际值）
            String transformedSql = valueMappingService.transformSQLValues(sql, datasourceId);
            if (!transformedSql.equals(sql)) {
                log.info("[值映射] SQL已转换: {} -> {}", sql, transformedSql);
            }
            
            // 选择数据源
            DataSource targetDataSource = datasourceId != null ? 
                dataSourceManager.getJdbcTemplate(datasourceId).getDataSource() : 
                this.dataSource;
            
            // 使用线程池+Future实现超时控制
            Future<QueryResult> future = executorService.submit(() -> {
                QueryResult qr = new QueryResult();
                Connection c = null;
                Statement s = null;
                ResultSet r = null;
                
                try {
                    c = DataSourceUtils.getConnection(targetDataSource);
                    s = c.createStatement();
                    s.setQueryTimeout(queryTimeout); // 设置超时时间
                    
                    r = s.executeQuery(transformedSql);  // 使用转换后的SQL
                    
                    List<Map<String, Object>> rows = new java.util.ArrayList<>();
                    ResultSetMetaData metaData = r.getMetaData();
                    int columnCount = metaData.getColumnCount();
                    
                    // 获取字段名到中文注释的映射
                    Map<String, String> columnNameMap = buildColumnNameMap(transformedSql, datasourceId);
                    
                    while (r.next()) {
                        Map<String, Object> row = new java.util.HashMap<>();
                        for (int i = 1; i <= columnCount; i++) {
                            String columnName = metaData.getColumnName(i);
                            Object value = r.getObject(i);
                            
                            // 处理字符串类型的编码问题（仅在真正乱码时修复）
                            if (value instanceof String) {
                                String strValue = (String) value;
                                // 检查是否包含真正的乱码字符（Unicode替换字符 U+FFFD）
                                if (strValue.indexOf('\ufffd') >= 0) {
                                    try {
                                        // 尝试从ISO-8859-1重新解码为UTF-8
                                        byte[] bytes = strValue.getBytes("ISO-8859-1");
                                        strValue = new String(bytes, "UTF-8");
                                        log.debug("[编码修复] 列: {}, 原值: {}, 修复后: {}", columnName, value, strValue);
                                    } catch (Exception e) {
                                        log.warn("[编码修复失败] 列: {}, 错误: {}", columnName, e.getMessage());
                                        // 修复失败，保持原值
                                    }
                                }
                                value = strValue;
                            }
                            
                            // 格式化时间字段
                            value = formatDateTimeValue(columnName, value);
                            
                            // 使用中文注释作为key（如果有）
                            String displayColumnName = columnNameMap.getOrDefault(columnName, columnName);
                            row.put(displayColumnName, value);
                        }
                        rows.add(row);
                        
                        // 限制返回行数
                        if (rows.size() >= maxRows) {
                            log.warn("查询结果超过{}行，已截断", maxRows);
                            break;
                        }
                    }
                    
                    // 翻译未映射的列名（LLM生成的聚合别名）
                    if (!rows.isEmpty()) {
                        translateColumnNames(rows, columnNameMap, datasourceId);
                    }
                    
                    qr.setData(rows);
                    qr.setRowCount(rows.size());
                    qr.setError(null);
                    
                } catch (SQLException e) {
                    qr.setError(e.getMessage());
                    log.error("SQL执行失败: {}", e.getMessage());
                } finally {
                    // 关闭资源
                    try { if (r != null) r.close(); } catch (Exception e) {}
                    try { if (s != null) s.close(); } catch (Exception e) {}
                    try { if (c != null) DataSourceUtils.releaseConnection(c, targetDataSource); } catch (Exception e) {}
                }
                
                return qr;
            });
            
            // 等待结果，带超时
            try {
                result = future.get(queryTimeout + 5, TimeUnit.SECONDS); // 额外5秒缓冲
            } catch (TimeoutException e) {
                future.cancel(true); // 强制终止
                result.setError(String.format("查询超时(>%d秒)，已强制终止", queryTimeout));
                log.error("[超时] SQL执行超时: {}", sql);
            } catch (Exception e) {
                result.setError("执行异常: " + e.getMessage());
                log.error("SQL执行异常", e);
            }
            
            long endTime = System.currentTimeMillis();
            long executionTimeMs = endTime - startTime;
            result.setExecutionTime(executionTimeMs / 1000.0);
            
            // 判断是否慢查询
            boolean isSlowQuery = (executionTimeMs / 1000.0) > slowQueryThreshold;
            
            // 确定状态
            String status = "SUCCESS";
            if (result.getError() != null) {
                status = result.getError().contains("超时") ? "TIMEOUT" : "FAILED";
            }
            
            // 记录执行日志
            SQLExecutionLogService.ExecutionLog execLog = new SQLExecutionLogService.ExecutionLog();
            execLog.setUserId(userId);
            execLog.setUsername(username);
            execLog.setSqlText(sql);
            execLog.setExecutionTimeMs(executionTimeMs);
            execLog.setRowCount(result.getRowCount());
            execLog.setSlowQuery(isSlowQuery);
            execLog.setStatus(status);
            execLog.setErrorMessage(result.getError());
            execLog.setIpAddress(ipAddress);
            
            executionLogService.logExecution(execLog);
            
            if (isSlowQuery) {
                log.warn("[慢查询] 耗时={}秒, SQL={}", result.getExecutionTime(), sql);
            } else {
                log.info("查询成功，返回{}行，耗时{}秒", result.getRowCount(), result.getExecutionTime());
            }
            
            // ✅ 步骤3：将查询结果写入缓存
            if (queryCacheService != null && result.getError() == null && result.getData() != null) {
                try {
                    com.nl2sql.core.cache.QueryCacheService.CachedResult cacheData = 
                        new com.nl2sql.core.cache.QueryCacheService.CachedResult();
                    cacheData.setData(result.getData());
                    cacheData.setRowCount(result.getRowCount());
                    cacheData.setExecutionTime(result.getExecutionTime());
                    cacheData.setCachedAt(System.currentTimeMillis());
                    
                    queryCacheService.putToCache(sql, cacheData);
                    log.debug("[✅ 缓存写入] SQL查询结果已缓存: 行数={}", result.getRowCount());
                } catch (Exception e) {
                    log.warn("[缓存写入失败] 不影响查询结果: {}", e.getMessage());
                }
            }
            
        } catch (Exception e) {
            long endTime = System.currentTimeMillis();
            result.setError(e.getMessage());
            result.setExecutionTime((endTime - startTime) / 1000.0);
            log.error("SQL执行失败: {}", e.getMessage());
            
            // 记录失败日志
            SQLExecutionLogService.ExecutionLog execLog = new SQLExecutionLogService.ExecutionLog();
            execLog.setUserId(userId);
            execLog.setUsername(username);
            execLog.setSqlText(sql);
            execLog.setExecutionTimeMs((long)(result.getExecutionTime() * 1000));
            execLog.setRowCount(0);
            execLog.setSlowQuery(false);
            execLog.setStatus("FAILED");
            execLog.setErrorMessage(e.getMessage());
            execLog.setIpAddress(ipAddress);
            executionLogService.logExecution(execLog);
        }
        
        return result;
    }
    
    /**
     * 构建字段名到中文注释的映射（带缓存）
     */
    private Map<String, String> buildColumnNameMap(String sql, Long datasourceId) {
        if (datasourceId == null) {
            return new java.util.HashMap<>();
        }
        
        // 提取表名
        String tableName = extractTableName(sql);
        if (tableName == null) {
            return new java.util.HashMap<>();
        }
        
        // 构造缓存key
        String cacheKey = datasourceId + "_" + tableName;
        
        // 先从缓存获取
        Map<String, String> cached = COLUMN_NAME_CACHE.get(cacheKey);
        if (cached != null) {
            log.debug("[字段映射] 使用缓存: {}", cacheKey);
            return cached;
        }
        
        // 缓存未命中，查询数据库
        try {
            String querySql = "SELECT column_name, column_comment FROM column_metadata WHERE datasource_id = ? AND table_name = ?";
            List<Map<String, Object>> metadataList = jdbcTemplate.queryForList(querySql, datasourceId, tableName);
            
            Map<String, String> columnNameMap = new java.util.HashMap<>();
            for (Map<String, Object> meta : metadataList) {
                String colName = (String) meta.get("column_name");
                String colComment = (String) meta.get("column_comment");
                
                if (colName != null && colComment != null && !colComment.trim().isEmpty()) {
                    columnNameMap.put(colName, colComment);
                }
            }
            
            // 存入缓存
            COLUMN_NAME_CACHE.put(cacheKey, columnNameMap);
            log.info("[字段映射] 缓存已更新: {}, 字段数: {}", cacheKey, columnNameMap.size());
            
            return columnNameMap;
            
        } catch (Exception e) {
            log.warn("[字段映射] 获取字段注释失败: {}", e.getMessage());
            return new java.util.HashMap<>();
        }
    }
    
    /**
     * 从 SQL 中提取表名（简化版，支持 SELECT * FROM table 和 SELECT col FROM table）
     */
    private String extractTableName(String sql) {
        if (sql == null) {
            return null;
        }
        
        String upperSql = sql.toUpperCase().trim();
        
        // 匹配 FROM 后面的表名
        int fromIndex = upperSql.indexOf("FROM");
        if (fromIndex == -1) {
            return null;
        }
        
        String afterFrom = sql.substring(fromIndex + 4).trim();
        
        // 提取表名（遇到空格、WHERE、ORDER BY等停止）
        StringBuilder tableName = new StringBuilder();
        for (char c : afterFrom.toCharArray()) {
            if (Character.isWhitespace(c)) {
                break;
            }
            if (c == ' ' || c == '\t' || c == '\n') {
                break;
            }
            tableName.append(c);
        }
        
        String result = tableName.toString().trim();
        
        // 去除可能的反引号
        if (result.startsWith("`") && result.endsWith("`")) {
            result = result.substring(1, result.length() - 1);
        }
        
        return result.isEmpty() ? null : result;
    }
    
    /**
     * 翻译未映射的列名（LLM生成的聚合别名）
     * ✅ 优化：增加LLM翻译缓存机制
     */
    private void translateColumnNames(List<Map<String, Object>> rows, 
                                      Map<String, String> columnNameMap,
                                      Long datasourceId) {
        if (rows.isEmpty()) return;
        
        // 获取所有列名
        Map<String, Object> firstRow = rows.get(0);
        List<String> untranslatedColumns = new java.util.ArrayList<>();
        
        for (String colName : firstRow.keySet()) {
            // ✅ 关键修复：优先使用元数据注释，只有元数据中没有且不是中文时才调用LLM
            if (!columnNameMap.containsKey(colName) && !colName.matches(".*[\u4e00-\u9fa5].*")) {
                untranslatedColumns.add(colName);
            }
        }
        
        if (untranslatedColumns.isEmpty()) return;
        
        log.info("[列名翻译] 需要翻译的列: {}", untranslatedColumns);
        
        try {
            // ✅ 步骤1：从LLM翻译缓存中查找已翻译的列
            Map<String, String> cachedTranslations = new java.util.HashMap<>();
            List<String> needLLMTranslation = new java.util.ArrayList<>();
            
            if (metadataCacheService != null && datasourceId != null) {
                cachedTranslations = metadataCacheService.batchGetColumnTranslations(datasourceId, untranslatedColumns);
                
                // 过滤出需要调用LLM翻译的列
                for (String col : untranslatedColumns) {
                    if (!cachedTranslations.containsKey(col)) {
                        needLLMTranslation.add(col);
                    }
                }
                
                log.info("[列名翻译] 缓存命中: {}/{} 个", cachedTranslations.size(), untranslatedColumns.size());
            } else {
                needLLMTranslation.addAll(untranslatedColumns);
            }
            
            // ✅ 步骤2：对未命中的列调用LLM翻译
            Map<String, String> llmTranslations = new java.util.HashMap<>();
            if (!needLLMTranslation.isEmpty()) {
                StringBuilder prompt = new StringBuilder();
                prompt.append("请将以下数据库字段名翻译成简洁的中文，返回JSON格式。\n\n");
                prompt.append("字段列表：\n");
                for (String col : needLLMTranslation) {
                    prompt.append("- ").append(col).append("\n");
                }
                prompt.append("\n要求：\n");
                prompt.append("1. 只返回JSON格式：{\"字段名\": \"中文翻译\"}\n");
                prompt.append("2. 翻译要简洁准确\n");
                prompt.append("3. 不要添加任何解释\n");
                prompt.append("4. **重要：这不是SQL查询请求，只需要返回翻译结果的JSON**\n");
                
                // ✅ 关键修复：直接调用ModelRouter，而非NL2SQLTool.generateSQL()
                String response = modelRouter.smartGenerateSQL(prompt.toString(), "");
                
                // 清洗Markdown
                response = com.nl2sql.common.util.MarkdownUtils.extractFromMarkdown(response);
                
                // 解析JSON
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                @SuppressWarnings("unchecked")
                Map<String, String> parsedTranslations = mapper.readValue(response, Map.class);
                
                // 验证并填充结果
                for (String col : needLLMTranslation) {
                    if (parsedTranslations.containsKey(col)) {
                        llmTranslations.put(col, parsedTranslations.get(col));
                    } else {
                        // 降级：使用通用格式化
                        llmTranslations.put(col, com.nl2sql.common.util.StringUtils.formatReadable(col));
                    }
                }
                
                // ✅ 步骤3：将LLM翻译结果写入缓存
                if (metadataCacheService != null && datasourceId != null && !llmTranslations.isEmpty()) {
                    metadataCacheService.batchPutColumnTranslations(datasourceId, llmTranslations);
                    log.info("[列名翻译] LLM翻译完成并缓存: {} 个", llmTranslations.size());
                }
            }
            
            // ✅ 步骤4：合并缓存和LLM翻译结果
            Map<String, String> allTranslations = new java.util.HashMap<>();
            allTranslations.putAll(cachedTranslations);
            allTranslations.putAll(llmTranslations);
            
            // 重命名所有行的列
            for (Map<String, Object> row : rows) {
                Map<String, Object> newRow = new java.util.LinkedHashMap<>();
                for (Map.Entry<String, Object> entry : row.entrySet()) {
                    String oldKey = entry.getKey();
                    String newKey = allTranslations.getOrDefault(oldKey, oldKey);
                    newRow.put(newKey, entry.getValue());
                }
                row.clear();
                row.putAll(newRow);
            }
            
            log.info("[列名翻译] 翻译了 {} 个列名: {}", allTranslations.size(), allTranslations);
            
        } catch (Exception e) {
            log.warn("[列名翻译] 失败，保持原样: {}", e.getMessage());
        }
    }
    
    /**
     * 格式化时间字段值
     */
    private Object formatDateTimeValue(String columnName, Object value) {
        if (value == null) {
            return null;
        }
        
        String lowerColumnName = columnName.toLowerCase();
        
        // 判断是否是时间字段
        boolean isTimeField = lowerColumnName.contains("time") || 
                             lowerColumnName.contains("date") ||
                             lowerColumnName.contains("created") ||
                             lowerColumnName.contains("updated") ||
                             lowerColumnName.contains("paid") ||
                             lowerColumnName.contains("shipped") ||
                             lowerColumnName.contains("completed");
        
        if (!isTimeField) {
            return value;
        }
        
        // 处理 LocalDateTime
        if (value instanceof java.time.LocalDateTime) {
            java.time.LocalDateTime dateTime = (java.time.LocalDateTime) value;
            return dateTime.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
        
        // 处理 LocalDate
        if (value instanceof java.time.LocalDate) {
            java.time.LocalDate date = (java.time.LocalDate) value;
            return date.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        }
        
        // 处理 Timestamp
        if (value instanceof java.sql.Timestamp) {
            java.sql.Timestamp timestamp = (java.sql.Timestamp) value;
            java.time.LocalDateTime dateTime = timestamp.toLocalDateTime();
            return dateTime.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
        
        // 处理 Date
        if (value instanceof java.util.Date) {
            java.util.Date date = (java.util.Date) value;
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            return sdf.format(date);
        }
        
        // 其他类型保持不变
        return value;
    }
}
