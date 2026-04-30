package com.nl2sql.web.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * SystemHealth Mapper - 系统健康检查相关查询
 */
@Mapper
public interface SystemHealthMapper {
    
    /**
     * 测试数据库连接
     */
    @Select("SELECT 1")
    int testConnection();
}
