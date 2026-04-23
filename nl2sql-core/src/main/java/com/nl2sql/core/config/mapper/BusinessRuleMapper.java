package com.nl2sql.core.config.mapper;

import com.nl2sql.core.config.BusinessRuleConfig;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 业务规则Mapper
 */
@Mapper
public interface BusinessRuleMapper {
    
    /**
     * 插入规则
     */
    @Insert("INSERT INTO business_rules (rule_id, rule_name, rule_type, rule_content, datasource_id, priority, enabled, description, created_by) " +
            "VALUES (#{ruleId}, #{ruleName}, #{ruleType}, #{ruleContentJson}, #{datasourceId}, #{priority}, #{enabled}, #{description}, #{createdBy})")
    void insert(BusinessRuleConfig rule);
    
    /**
     * 更新规则
     */
    @Update("UPDATE business_rules SET rule_name=#{ruleName}, rule_content=#{ruleContentJson}, " +
            "datasource_id=#{datasourceId}, priority=#{priority}, enabled=#{enabled}, " +
            "description=#{description}, updated_by=#{updatedBy} WHERE rule_id=#{ruleId}")
    void update(BusinessRuleConfig rule);
    
    /**
     * 删除规则
     */
    @Delete("DELETE FROM business_rules WHERE rule_id=#{ruleId}")
    void delete(String ruleId);
    
    /**
     * 根据ID查询规则
     */
    @Select("SELECT * FROM business_rules WHERE rule_id=#{ruleId}")
    @Results({
        @Result(property = "ruleId", column = "rule_id"),
        @Result(property = "ruleName", column = "rule_name"),
        @Result(property = "ruleType", column = "rule_type"),
        @Result(property = "ruleContentJson", column = "rule_content"),
        @Result(property = "datasourceId", column = "datasource_id"),
        @Result(property = "priority", column = "priority"),
        @Result(property = "enabled", column = "enabled"),
        @Result(property = "description", column = "description"),
        @Result(property = "createdBy", column = "created_by"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedBy", column = "updated_by"),
        @Result(property = "updatedAt", column = "updated_at")
    })
    BusinessRuleConfig findByRuleId(String ruleId);
    
    /**
     * 查询所有启用的规则
     */
    @Select("SELECT * FROM business_rules WHERE enabled=1 ORDER BY priority DESC")
    @Results({
        @Result(property = "ruleId", column = "rule_id"),
        @Result(property = "ruleName", column = "rule_name"),
        @Result(property = "ruleType", column = "rule_type"),
        @Result(property = "ruleContentJson", column = "rule_content"),
        @Result(property = "datasourceId", column = "datasource_id"),
        @Result(property = "priority", column = "priority"),
        @Result(property = "enabled", column = "enabled"),
        @Result(property = "description", column = "description"),
        @Result(property = "createdBy", column = "created_by"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedBy", column = "updated_by"),
        @Result(property = "updatedAt", column = "updated_at")
    })
    List<BusinessRuleConfig> findAllEnabled();
    
    /**
     * 根据类型查询规则
     */
    @Select("SELECT * FROM business_rules WHERE rule_type=#{ruleType} AND enabled=1 ORDER BY priority DESC")
    @Results({
        @Result(property = "ruleId", column = "rule_id"),
        @Result(property = "ruleName", column = "rule_name"),
        @Result(property = "ruleType", column = "rule_type"),
        @Result(property = "ruleContentJson", column = "rule_content"),
        @Result(property = "datasourceId", column = "datasource_id"),
        @Result(property = "priority", column = "priority"),
        @Result(property = "enabled", column = "enabled"),
        @Result(property = "description", column = "description"),
        @Result(property = "createdBy", column = "created_by"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedBy", column = "updated_by"),
        @Result(property = "updatedAt", column = "updated_at")
    })
    List<BusinessRuleConfig> findByType(String ruleType);
    
    /**
     * 根据类型和数据源查询规则
     */
    @Select("SELECT * FROM business_rules WHERE rule_type=#{ruleType} AND (datasource_id IS NULL OR datasource_id=#{datasourceId}) AND enabled=1 ORDER BY priority DESC")
    @Results({
        @Result(property = "ruleId", column = "rule_id"),
        @Result(property = "ruleName", column = "rule_name"),
        @Result(property = "ruleType", column = "rule_type"),
        @Result(property = "ruleContentJson", column = "rule_content"),
        @Result(property = "datasourceId", column = "datasource_id"),
        @Result(property = "priority", column = "priority"),
        @Result(property = "enabled", column = "enabled"),
        @Result(property = "description", column = "description"),
        @Result(property = "createdBy", column = "created_by"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedBy", column = "updated_by"),
        @Result(property = "updatedAt", column = "updated_at")
    })
    List<BusinessRuleConfig> findByTypeAndDatasource(@Param("ruleType") String ruleType, @Param("datasourceId") Long datasourceId);
}
