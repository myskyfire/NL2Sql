package com.nl2sql.web.controller;

import com.nl2sql.common.result.Result;
import com.nl2sql.core.cache.MetadataCacheService;
import com.nl2sql.core.metadata.MetadataService;
import com.nl2sql.metadata.entity.DataSourceConfig;
import com.nl2sql.metadata.service.DataSourceConfigService;
import com.nl2sql.metadata.service.MetadataCollectorService;
import com.nl2sql.metadata.service.MetadataQueryService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
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
    
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    @Autowired(required = false)
    private MetadataCacheService metadataCacheService;
    
    @Autowired(required = false)
    private MetadataService metadataService;
    
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
     * ✅ 新增：离线导入表结构（支持SQL文本和文件上传）
     */
    @PostMapping("/metadata/import-offline/{datasourceId}")
    public Result<Map<String, Object>> importOfflineMetadata(
            @PathVariable Long datasourceId,
            @RequestBody Map<String, String> request) {
        try {
            String sqlContent = request.get("sqlContent");
            if (sqlContent == null || sqlContent.trim().isEmpty()) {
                return Result.error("SQL内容不能为空");
            }
            
            log.info("[离线导入] 开始为数据源 {} 导入表结构", datasourceId);
            
            // 调用Service解析并导入
            Map<String, Object> result = metadataCollectorService.importOfflineMetadata(datasourceId, sqlContent);
            
            // 清除缓存
            metadataQueryService.clearCache(datasourceId);
            if (metadataCacheService != null) {
                metadataCacheService.invalidateAll();
                log.info("[离线导入] 元数据已导入，清除所有 Caffeine 缓存");
            }
            
            return Result.success(result);
        } catch (Exception e) {
            log.error("[离线导入] 失败", e);
            return Result.error("导入失败: " + e.getMessage());
        }
    }
    
    /**
     * 同步元数据
     */
    @PostMapping("/metadata/sync/{datasourceId}")
    public Result<Map<String, Object>> syncMetadata(@PathVariable Long datasourceId,
                                                     @RequestParam(required = false) Long operatorId,
                                                     @RequestParam(required = false, defaultValue = "false") Boolean forceOverride) {
        try {
            // 检查是否有本地修改的元数据
            if (!forceOverride) {
                Map<String, Object> localModifiedInfo = metadataQueryService.getLocalModifiedCount(datasourceId);
                int tableCount = (int) localModifiedInfo.getOrDefault("tableCount", 0);
                int columnCount = (int) localModifiedInfo.getOrDefault("columnCount", 0);
                
                if (tableCount > 0 || columnCount > 0) {
                    Map<String, Object> response = new HashMap<>();
                    response.put("hasLocalModified", true);
                    response.put("tableCount", tableCount);
                    response.put("columnCount", columnCount);
                    response.put("message", String.format("检测到 %d 个表和 %d 个字段有本地修改，同步将覆盖这些数据。是否继续？", tableCount, columnCount));
                    return Result.success(response);
                }
            }
            
            MetadataCollectorService.SyncResult result = 
                metadataCollectorService.syncAllMetadata(datasourceId, operatorId != null ? operatorId : 1L);
            
            Map<String, Object> response = new HashMap<>();
            response.put("status", result.getStatus());
            response.put("tableCount", result.getTableCount());
            response.put("columnCount", result.getColumnCount());
            response.put("durationSeconds", result.getDurationSeconds());
            response.put("hasLocalModified", false);
            
            if ("FAILED".equals(result.getStatus())) {
                response.put("errorMessage", result.getErrorMessage());
            }
            
            // 清除缓存
            metadataQueryService.clearCache(datasourceId);
            
            // ✅ 关键：清除 Caffeine 元数据缓存（Schema + 关联关系）
            if (metadataCacheService != null) {
                metadataCacheService.invalidateAll();
                log.info("[AdminMetadata] 元数据已同步，清除所有 Caffeine 缓存");
            }
            
            return Result.success(response);
        } catch (Exception e) {
            log.error("同步元数据失败", e);
            return Result.error("同步失败: " + e.getMessage());
        }
    }
    
    /**
     * ✅ 新增：元数据增强 - 使用LLM生成业务化表描述
     */
    @PostMapping("/metadata/enhance/{datasourceId}")
    public Result<Map<String, Object>> enhanceMetadata(@PathVariable Long datasourceId) {
        try {
            log.info("[元数据增强] 开始为数据源 {} 执行表描述增强", datasourceId);
            
            // 调用异步增强方法
            metadataCollectorService.enhanceTableDescriptionsAsync(datasourceId);
            
            Map<String, Object> response = new HashMap<>();
            response.put("status", "STARTED");
            response.put("message", "元数据增强任务已启动，将在后台异步执行");
            response.put("note", "增强完成后请重启应用以重新加载向量索引");
            
            return Result.success(response);
        } catch (Exception e) {
            log.error("[元数据增强] 启动失败", e);
            return Result.error("增强失败: " + e.getMessage());
        }
    }
    
    /**
     * ✅ 新增：列元数据增强 - 使用LLM生成字段注释
     */
    @PostMapping("/metadata/enhance-columns/{datasourceId}")
    public Result<Map<String, Object>> enhanceColumnMetadata(@PathVariable Long datasourceId) {
        try {
            log.info("[列元数据增强] 开始为数据源 {} 执行字段注释增强", datasourceId);
            
            // 调用异步增强方法
            metadataCollectorService.enhanceColumnDescriptionsAsync(datasourceId);
            
            Map<String, Object> response = new HashMap<>();
            response.put("status", "STARTED");
            response.put("message", "列元数据增强任务已启动，将在后台异步执行（跳过已有注释的字段）");
            response.put("note", "增强完成后可在元数据管理中查看结果");
            
            return Result.success(response);
        } catch (Exception e) {
            log.error("[列元数据增强] 启动失败", e);
            return Result.error("增强失败: " + e.getMessage());
        }
    }
    
    /**
     * ✅ 新增：按需增强指定表的字段注释（反馈驱动优化）
     */
    @PostMapping("/metadata/enhance-table/{datasourceId}/{tableName}")
    public Result<Map<String, Object>> enhanceTableColumns(
            @PathVariable Long datasourceId,
            @PathVariable String tableName) {
        try {
            log.info("[按需增强] 手动触发表 {}.{} 的字段注释增强", datasourceId, tableName);
            
            // 调用按需增强方法
            metadataCollectorService.enhanceColumnDescriptionsForTable(datasourceId, tableName);
            
            Map<String, Object> response = new HashMap<>();
            response.put("status", "STARTED");
            response.put("message", String.format("表 %s.%s 的增强任务已启动，将在后台异步执行", datasourceId, tableName));
            response.put("note", "仅当注释覆盖率 < 50% 时才会实际执行增强");
            
            return Result.success(response);
        } catch (Exception e) {
            log.error("[按需增强] 启动失败", e);
            return Result.error("增强失败: " + e.getMessage());
        }
    }
    
    /**
     * 查询已同步的元数据（所有数据源）
     */
    @GetMapping("/metadata/list")
    public Result<Map<String, Object>> getSyncedMetadata(@RequestParam(required = false) Long datasourceId) {
        try {
            Map<String, Object> response = new HashMap<>();
            Map<String, Object> allTables = new HashMap<>();
            
            if (datasourceId != null) {
                // ✅ 按指定数据源查询
                DataSourceConfig ds = dataSourceConfigService.getConfigById(datasourceId);
                if (ds == null) {
                    return Result.error("数据源不存在: " + datasourceId);
                }
                
                List<com.nl2sql.metadata.entity.TableMetadata> tables = 
                    metadataQueryService.getAllTables(datasourceId);
                
                for (com.nl2sql.metadata.entity.TableMetadata table : tables) {
                    MetadataQueryService.TableDetail detail = 
                        metadataQueryService.getTableDetail(datasourceId, table.getTableName());
                    
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
                
                response.put("tables", allTables);
                response.put("datasourceCount", 1);
                response.put("tableCount", allTables.size());
            } else {
                // 查询所有数据源
                List<DataSourceConfig> datasources = dataSourceConfigService.listActiveConfigs();
                
                for (DataSourceConfig ds : datasources) {
                    List<com.nl2sql.metadata.entity.TableMetadata> tables = 
                        metadataQueryService.getAllTables(ds.getId());
                    
                    for (com.nl2sql.metadata.entity.TableMetadata table : tables) {
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
            }
            
            return Result.success(response);
        } catch (Exception e) {
            log.error("查询元数据失败", e);
            return Result.error("查询失败: " + e.getMessage());
        }
    }
    
    /**
     * ✅ 新增：按数据库名获取表列表
     */
    @GetMapping("/metadata/tables")
    public Result<List<String>> getTablesByDatabase(@RequestParam String databaseName) {
        try {
            // 根据数据库名查找对应的数据源
            List<DataSourceConfig> datasources = dataSourceConfigService.listActiveConfigs();
            DataSourceConfig targetDs = null;
            
            for (DataSourceConfig ds : datasources) {
                if (databaseName.equals(ds.getDatabaseName()) || databaseName.equals(ds.getName())) {
                    targetDs = ds;
                    break;
                }
            }
            
            if (targetDs == null) {
                return Result.error("未找到数据库: " + databaseName);
            }
            
            // 获取该数据源的所有表
            List<com.nl2sql.metadata.entity.TableMetadata> tables = 
                metadataQueryService.getAllTables(targetDs.getId());
            
            List<String> tableNames = tables.stream()
                .map(com.nl2sql.metadata.entity.TableMetadata::getTableName)
                .collect(java.util.stream.Collectors.toList());
            
            return Result.success(tableNames);
        } catch (Exception e) {
            log.error("获取表列表失败", e);
            return Result.error("获取失败: " + e.getMessage());
        }
    }
    
    /**
     * ✅ 新增：刷新元数据缓存（用于数据变更后立即生效）
     */
    @PostMapping("/metadata/refresh")
    public Result<Map<String, Object>> refreshMetadata() {
        try {
            log.info("[元数据刷新] 开始刷新元数据缓存");
            
            if (metadataService != null) {
                metadataService.refreshMetadata();
                log.info("[元数据刷新] ✅ MetadataService 缓存已刷新");
            }
            
            // 清除 Caffeine 缓存
            if (metadataCacheService != null) {
                metadataCacheService.invalidateAll();
                log.info("[元数据刷新] ✅ Caffeine 缓存已清除");
            }
            
            Map<String, Object> response = new HashMap<>();
            response.put("status", "SUCCESS");
            response.put("message", "元数据缓存已刷新");
            
            return Result.success(response);
        } catch (Exception e) {
            log.error("[元数据刷新] 失败", e);
            return Result.error("刷新失败: " + e.getMessage());
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
