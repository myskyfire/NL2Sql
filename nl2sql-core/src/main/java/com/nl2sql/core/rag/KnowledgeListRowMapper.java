package com.nl2sql.core.rag;

import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 知识库列表RowMapper（包含created_at）
 */
public class KnowledgeListRowMapper implements RowMapper<RagKnowledgeBaseService.KnowledgeItem> {
    @Override
    public RagKnowledgeBaseService.KnowledgeItem mapRow(ResultSet rs, int rowNum) throws SQLException {
        RagKnowledgeBaseService.KnowledgeItem item = new RagKnowledgeBaseService.KnowledgeItem();
        item.setId(rs.getLong("id"));
        item.setQuestion(rs.getString("question"));
        item.setAnswer(rs.getString("answer"));
        item.setSqlExample(rs.getString("sql_example"));
        item.setCategory(rs.getString("category"));
        item.setQualityScore(rs.getFloat("quality_score"));
        item.setUsageCount(rs.getInt("usage_count"));
        
        // created_at字段
        try {
            java.sql.Timestamp timestamp = rs.getTimestamp("created_at");
            if (timestamp != null) {
                item.setCreatedAt(timestamp.toLocalDateTime());
            }
        } catch (SQLException e) {
            // 忽略
        }
        
        return item;
    }
}
