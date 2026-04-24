package com.nl2sql.core.rag.provider;

import java.util.List;

/**
 * 向量数据库提供者统一接口
 * 借鉴LangChain设计，为多种向量数据库提供统一适配层
 */
public interface VectorStoreProvider {
    
    /**
     * 获取提供者名称
     * @return 提供者名称（chroma/milvus/qdrant/mysql）
     */
    String getName();
    
    /**
     * 检查向量数据库是否可用
     * @return true=可用, false=不可用
     */
    boolean isAvailable();
    
    /**
     * 添加知识到向量库
     * @param question 问题
     * @param answer 答案
     * @param sqlExample SQL示例
     * @param category 分类
     * @param qualityScore 质量评分（0-1）
     * @return 文档ID
     */
    String addKnowledge(String question, String answer, String sqlExample, String category, float qualityScore);
    
    /**
     * 搜索相似问题
     * @param question 查询问题
     * @param maxResults 最大返回结果数
     * @param minScore 最小相似度阈值
     * @return 搜索结果列表
     */
    List<VectorSearchResult> searchSimilar(String question, int maxResults, double minScore);
    
    /**
     * 清空所有知识
     */
    void clearAll();
    
    /**
     * 获取配置信息
     * @return 配置Map
     */
    java.util.Map<String, Object> getConfig();
}
