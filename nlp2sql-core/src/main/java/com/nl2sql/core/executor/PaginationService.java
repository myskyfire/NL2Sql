package com.nl2sql.core.executor;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class PaginationService {
    
    /**
     * 分页结果
     */
    @Data
    public static class PageResult {
        private List<Map<String, Object>> data;
        private int pageNum;
        private int pageSize;
        private long totalRows;
        private int totalPages;
        private boolean hasNext;
        private boolean hasPrevious;
        
        public PageResult(List<Map<String, Object>> data, int pageNum, int pageSize, long totalRows) {
            this.data = data;
            this.pageNum = pageNum;
            this.pageSize = pageSize;
            this.totalRows = totalRows;
            this.totalPages = (int) Math.ceil((double) totalRows / pageSize);
            this.hasNext = pageNum < totalPages;
            this.hasPrevious = pageNum > 1;
        }
    }
    
    /**
     * 对查询结果进行分页
     * 
     * @param allData 完整数据
     * @param pageNum 页码（从1开始）
     * @param pageSize 每页大小
     * @return 分页结果
     */
    public PageResult paginate(List<Map<String, Object>> allData, int pageNum, int pageSize) {
        if (allData == null || allData.isEmpty()) {
            return new PageResult(Collections.emptyList(), pageNum, pageSize, 0);
        }
        
        long totalRows = allData.size();
        int fromIndex = (pageNum - 1) * pageSize;
        int toIndex = Math.min(fromIndex + pageSize, allData.size());
        
        // 边界检查
        if (fromIndex >= allData.size()) {
            log.warn("页码超出范围: pageNum={}, totalRows={}, pageSize={}", pageNum, totalRows, pageSize);
            return new PageResult(Collections.emptyList(), pageNum, pageSize, totalRows);
        }
        
        List<Map<String, Object>> pageData = allData.subList(fromIndex, toIndex);
        
        log.debug("分页查询: pageNum={}, pageSize={}, totalRows={}, pageRows={}", 
            pageNum, pageSize, totalRows, pageData.size());
        
        return new PageResult(pageData, pageNum, pageSize, totalRows);
    }
    
    /**
     * 生成LIMIT子句用于数据库层面分页
     * 
     * @param pageNum 页码
     * @param pageSize 每页大小
     * @return LIMIT子句
     */
    public String generateLimitClause(int pageNum, int pageSize) {
        int offset = (pageNum - 1) * pageSize;
        return String.format("LIMIT %d, %d", offset, pageSize);
    }
    
    /**
     * 在SQL中添加分页
     * 
     * @param originalSql 原始SQL
     * @param pageNum 页码
     * @param pageSize 每页大小
     * @return 带分页的SQL
     */
    public String addPaginationToSQL(String originalSql, int pageNum, int pageSize) {
        if (originalSql == null || originalSql.trim().isEmpty()) {
            return originalSql;
        }
        
        String sql = originalSql.trim();
        
        // 如果已有LIMIT，替换它
        if (sql.toUpperCase().contains("LIMIT")) {
            sql = sql.replaceAll("(?i)LIMIT\\s+\\d+(\\s*,\\s*\\d+|\\s+OFFSET\\s+\\d+)?", "");
        }
        
        // 添加新的LIMIT
        return sql + " " + generateLimitClause(pageNum, pageSize);
    }
}
