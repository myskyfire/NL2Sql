package com.nl2sql.mcp.controller;

import com.nl2sql.mcp.datasource.DataSourcePoolManager;
import com.nl2sql.mcp.datasource.DataSourcePoolManager.DataSourceConfig;
import com.nl2sql.mcp.datasource.DbType;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.Map;

/**
 * 数据源管理 REST API
 * 
 * 用于通过 Web 界面管理数据源，密码不落地到配置文件
 */
@Slf4j
@RestController
@RequestMapping("/api/datasources")
public class DataSourceController {

    @Autowired
    private DataSourcePoolManager poolManager;

    /**
     * 查询所有数据源（不返回密码）
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> listDatasources() {
        Map<Long, DataSourceConfig> datasources = poolManager.getAvailableDatasources();
        Map<String, Object> result = new HashMap<>();
        result.put("count", datasources.size());
        result.put("datasources", datasources);
        result.put("supported_db_types", DbType.getSupportedTypes());
        return ResponseEntity.ok(result);
    }

    /**
     * 添加数据源
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> addDatasource(@RequestBody AddDatasourceRequest request) {
        try {
            Long id = poolManager.addDatasource(
                request.getName(),
                request.getDbType(),
                request.getJdbcUrl(),
                request.getUsername(),
                request.getPassword()
            );
            log.info("通过管理界面添加数据源: id={}, name={}, type={}", id, request.getName(), request.getDbType());
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("datasource_id", id);
            result.put("message", "数据源添加成功");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("添加数据源失败", e);
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "添加失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * 删除数据源
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteDatasource(@PathVariable Long id) {
        try {
            poolManager.closePool(id);
            poolManager.removeDatasource(id);
            log.info("通过管理界面删除数据源: id={}", id);
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "数据源删除成功");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("删除数据源失败", e);
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "删除失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * 测试连接
     */
    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> testConnection(@RequestBody TestConnectionRequest request) {
        Map<String, Object> result = new HashMap<>();
        Connection conn = null;
        try {
            DbType type = DbType.valueOf(request.getDbType());
            Class.forName(type.getDriverClass());
            
            long start = System.currentTimeMillis();
            conn = DriverManager.getConnection(
                request.getJdbcUrl(),
                request.getUsername(),
                request.getPassword()
            );
            long elapsed = System.currentTimeMillis() - start;
            
            result.put("success", true);
            result.put("message", "连接成功");
            result.put("elapsed_ms", elapsed);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.warn("测试连接失败", e);
            result.put("success", false);
            result.put("message", "连接失败: " + e.getMessage());
            return ResponseEntity.ok(result);
        } finally {
            if (conn != null) {
                try { conn.close(); } catch (Exception ignored) {}
            }
        }
    }

    @Data
    public static class AddDatasourceRequest {
        private String name;
        private String dbType;
        private String jdbcUrl;
        private String username;
        private String password;
    }

    @Data
    public static class TestConnectionRequest {
        private String dbType;
        private String jdbcUrl;
        private String username;
        private String password;
    }
}
