package com.nl2sql.security;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
public class ColumnPermissionService {
    
    private final JdbcTemplate jdbcTemplate;
    
    public ColumnPermissionService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }
    
    /**
     * 检查用户是否有列的访问权限
     */
    public boolean hasColumnPermission(Long userId, String tableName, String columnName) {
        String sql = "SELECT can_view FROM column_permissions WHERE user_id = ? AND table_name = ? AND column_name = ?";
        
        try {
            List<Integer> results = jdbcTemplate.queryForList(sql, Integer.class, userId, tableName.toLowerCase(), columnName.toLowerCase());
            
            if (results.isEmpty()) {
                return true; // 默认允许（未配置权限）
            }
            
            return results.get(0) == 1;
        } catch (Exception e) {
            log.error("检查列权限失败: userId={}, table={}, column={}", userId, tableName, columnName, e);
            return false;
        }
    }
    
    /**
     * 获取用户需要脱敏的列
     */
    public Set<String> getDesensitizeColumns(Long userId, String tableName) {
        String sql = "SELECT column_name FROM column_permissions WHERE user_id = ? AND table_name = ? AND need_desensitize = 1";
        
        try {
            List<String> columns = jdbcTemplate.queryForList(sql, String.class, userId, tableName.toLowerCase());
            return new HashSet<>(columns);
        } catch (Exception e) {
            log.error("获取脱敏列失败: userId={}, table={}", userId, tableName, e);
            return Collections.emptySet();
        }
    }
    
    /**
     * 对数据进行列级脱敏
     */
    public void applyColumnLevelDesensitization(List<Map<String, Object>> data, Long userId, String tableName) {
        if (data == null || data.isEmpty()) {
            return;
        }
        
        Set<String> desensitizeColumns = getDesensitizeColumns(userId, tableName);
        
        if (desensitizeColumns.isEmpty()) {
            return;
        }
        
        for (Map<String, Object> row : data) {
            for (String column : desensitizeColumns) {
                if (row.containsKey(column) && row.get(column) != null) {
                    row.put(column, "***");
                }
            }
        }
        
        log.debug("列级脱敏应用: userId={}, table={}, columns={}", userId, tableName, desensitizeColumns);
    }
    
    /**
     * 过滤无权限的列
     */
    public List<Map<String, Object>> filterUnauthorizedColumns(List<Map<String, Object>> data, Long userId, String tableName) {
        if (data == null || data.isEmpty()) {
            return data;
        }
        
        // 获取所有列名
        Set<String> allColumns = data.get(0).keySet();
        
        // 找出无权限的列
        List<String> unauthorizedColumns = new ArrayList<>();
        for (String column : allColumns) {
            if (!hasColumnPermission(userId, tableName, column)) {
                unauthorizedColumns.add(column);
            }
        }
        
        if (unauthorizedColumns.isEmpty()) {
            return data;
        }
        
        // 移除无权限的列
        List<Map<String, Object>> filteredData = new ArrayList<>();
        for (Map<String, Object> row : data) {
            Map<String, Object> filteredRow = new HashMap<>(row);
            for (String column : unauthorizedColumns) {
                filteredRow.remove(column);
            }
            filteredData.add(filteredRow);
        }
        
        log.info("列级权限过滤: userId={}, table={}, removed={}", userId, tableName, unauthorizedColumns);
        
        return filteredData;
    }
}
