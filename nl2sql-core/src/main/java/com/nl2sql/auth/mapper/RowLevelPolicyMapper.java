package com.nl2sql.auth.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 行级权限策略Mapper
 */
@Mapper
public interface RowLevelPolicyMapper {

    /**
     * 查询用户在指定表上的行级过滤策略
     */
    List<Map<String, Object>> findActivePoliciesByUserAndTable(
            @Param("userId") Long userId,
            @Param("role") String role,
            @Param("datasourceId") Long datasourceId,
            @Param("tableName") String tableName);

    /**
     * 查询用户在多个表上的行级过滤策略
     */
    List<Map<String, Object>> findActivePoliciesByUserAndTables(
            @Param("userId") Long userId,
            @Param("role") String role,
            @Param("datasourceId") Long datasourceId,
            @Param("tableNames") List<String> tableNames);

    /**
     * 查询所有活跃策略
     */
    List<Map<String, Object>> findAllActivePolicies();

    /**
     * 查询所有策略（含非活跃）
     */
    List<Map<String, Object>> findAllPolicies(@Param("datasourceId") Long datasourceId);

    /**
     * 根据ID查询策略
     */
    Map<String, Object> findPolicyById(@Param("id") Long id);

    /**
     * 插入策略
     */
    void insertPolicy(@Param("policyName") String policyName,
                      @Param("datasourceId") Long datasourceId,
                      @Param("tableName") String tableName,
                      @Param("columnName") String columnName,
                      @Param("filterType") String filterType,
                      @Param("filterValue") String filterValue,
                      @Param("priority") Integer priority);

    /**
     * 更新策略
     */
    int updatePolicy(@Param("id") Long id,
                     @Param("policyName") String policyName,
                     @Param("datasourceId") Long datasourceId,
                     @Param("tableName") String tableName,
                     @Param("columnName") String columnName,
                     @Param("filterType") String filterType,
                     @Param("filterValue") String filterValue,
                     @Param("priority") Integer priority,
                     @Param("isActive") Integer isActive);

    /**
     * 停用策略
     */
    int deactivatePolicy(@Param("id") Long id);

    /**
     * 删除策略
     */
    int deletePolicy(@Param("id") Long id);

    /**
     * 插入策略绑定
     */
    void insertPolicyBinding(@Param("policyId") Long policyId,
                             @Param("userId") Long userId,
                             @Param("roleName") String roleName);

    /**
     * 删除策略的所有绑定
     */
    int deletePolicyBindings(@Param("policyId") Long policyId);

    /**
     * 查询策略的绑定用户列表
     */
    List<Map<String, Object>> findPolicyBindings(@Param("policyId") Long policyId);
}
