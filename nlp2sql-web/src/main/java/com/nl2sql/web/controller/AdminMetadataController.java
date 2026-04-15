package com.nl2sql.web.controller;

import com.nl2sql.common.result.Result;
import com.nl2sql.metadata.entity.DataSourceConfig;
import com.nl2sql.metadata.service.DataSourceConfigService;
import com.nl2sql.metadata.service.MetadataCollectorService;
import com.nl2sql.metadata.service.MetadataQueryService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/admin")
public class AdminMetadataController {
    
    private final DataSourceConfigService dataSourceConfigService;
    private final MetadataCollectorService metadataCollectorService;
    private final MetadataQueryService metadataQueryService;
    
    public AdminMetadataController(DataSourceConfigService dataSourceConfigService,
                                   MetadataCollectorService metadataCollectorService,
                                   MetadataQueryService metadataQueryService) {
        this.dataSourceConfigService = dataSourceConfigService;
        this.metadataCollectorService = metadataCollectorService;
        this.metadataQueryService = metadataQueryService;
    }
    
    /**
     * 测试数据库连接
     */
    @PostMapping("/datasource/test")
    public Result<Map<String, Object>> testConnection(@RequestBody DataSourceConfig config) {
        try {
            boolean success = dataSourceConfigService.testConnection(config);
            
            Map<String, Object> result = new HashMap<>();
            result.put("success", success);
            result.put("message", success ? "连接成功" : "连接失败");
            
            return success ? Result.success(result) : Result.error("连接失败，请检查配置");
        } catch (Exception e) {
            log.error("测试连接异常", e);
            return Result.error("连接测试异常: " + e.getMessage());
        }
    }
    
    /**
     * 保存数据源配置
     */
    @PostMapping("/datasource/save")
    public Result<Long> saveConfig(@RequestBody DataSourceConfig config) {
        try {
            Long id = dataSourceConfigService.saveConfig(config);
            return Result.success(id);
        } catch (Exception e) {
            log.error("保存配置失败", e);
            return Result.error("保存失败: " + e.getMessage());
        }
    }
    
    /**
     * 列出所有数据源
     */
    @GetMapping("/datasource/list")
    public Result<List<DataSourceConfig>> listConfigs() {
        try {
            List<DataSourceConfig> configs = dataSourceConfigService.listActiveConfigs();
            // 隐藏密码
            configs.forEach(c -> c.setPasswordEncrypted("***"));
            return Result.success(configs);
        } catch (Exception e) {
            log.error("查询配置失败", e);
            return Result.error("查询失败: " + e.getMessage());
        }
    }
    
    /**
     * 同步元数据
     */
    @PostMapping("/metadata/sync/{datasourceId}")
    public Result<Map<String, Object>> syncMetadata(@PathVariable Long datasourceId,
                                                     @RequestParam(required = false) Long operatorId) {
        try {
            MetadataCollectorService.SyncResult result = 
                metadataCollectorService.syncAllMetadata(datasourceId, operatorId != null ? operatorId : 1L);
            
            Map<String, Object> response = new HashMap<>();
            response.put("status", result.getStatus());
            response.put("tableCount", result.getTableCount());
            response.put("columnCount", result.getColumnCount());
            response.put("durationSeconds", result.getDurationSeconds());
            
            if ("FAILED".equals(result.getStatus())) {
                response.put("errorMessage", result.getErrorMessage());
            }
            
            // 清除缓存
            metadataQueryService.clearCache(datasourceId);
            
            return Result.success(response);
        } catch (Exception e) {
            log.error("同步元数据失败", e);
            return Result.error("同步失败: " + e.getMessage());
        }
    }
    
    /**
     * 查询已同步的元数据（所有数据源）
     */
    @GetMapping("/metadata/list")
    public Result<Map<String, Object>> getSyncedMetadata() {
        try {
            // 获取所有数据源
            List<DataSourceConfig> datasources = dataSourceConfigService.listActiveConfigs();
            
            Map<String, Object> response = new HashMap<>();
            Map<String, Object> allTables = new HashMap<>();
            
            for (DataSourceConfig ds : datasources) {
                // 获取该数据源的所有表
                List<com.nl2sql.metadata.entity.TableMetadata> tables = 
                    metadataQueryService.getAllTables(ds.getId());
                
                for (com.nl2sql.metadata.entity.TableMetadata table : tables) {
                    // 获取字段信息
                    MetadataQueryService.TableDetail detail = 
                        metadataQueryService.getTableDetail(ds.getId(), table.getTableName());
                    
                    Map<String, Object> tableInfo = new HashMap<>();
                    tableInfo.put("tableName", table.getTableName());
                    tableInfo.put("tableComment", table.getTableComment());
                    tableInfo.put("datasourceName", ds.getName());
                    tableInfo.put("datasourceId", ds.getId());
                    
                    if (detail != null && detail.getColumns() != null) {
                        tableInfo.put("columns", detail.getColumns());
                    }
                    
                    allTables.put(table.getTableName(), tableInfo);
                }
            }
            
            response.put("tables", allTables);
            response.put("datasourceCount", datasources.size());
            response.put("tableCount", allTables.size());
            
            return Result.success(response);
        } catch (Exception e) {
            log.error("查询元数据失败", e);
            return Result.error("查询失败: " + e.getMessage());
        }
    }
    
    @Data
    public static class TestConnectionRequest {
        private String dbType;
        private String host;
        private Integer port;
        private String databaseName;
        private String username;
        private String password;
    }
}
