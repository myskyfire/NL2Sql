package com.nl2sql.web.controller;

import com.nl2sql.common.result.Result;
import com.nl2sql.common.util.TemplateUtils;
import com.nl2sql.core.rag.RagKnowledgeBaseService;
import com.nl2sql.core.rag.dto.BatchImportResult;
import com.nl2sql.core.rag.dto.QAImportRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * RAG知识库管理Controller
 * 用于手动录入和管理QA对
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/rag")
public class RagManagementController {
    
    @Autowired
    private RagKnowledgeBaseService ragKnowledgeBaseService;
    
    /**
     * 单个导入QA对
     * 
     * @param request QA对导入请求
     * @return 导入的知识ID
     */
    @PostMapping("/qa/import")
    public Result<Long> importQAPair(@RequestBody QAImportRequest request) {
        try {
            // 参数校验
            if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
                return Result.error("问题不能为空");
            }
            if (request.getSql() == null || request.getSql().trim().isEmpty()) {
                return Result.error("SQL不能为空");
            }
            
            // 设置默认值
            String category = request.getCategory() != null ? request.getCategory() : "manual_imported";
            float qualityScore = request.getQualityScore() != null ? request.getQualityScore() : 0.9f;
            
            // 生成AI回答（如果未提供）
            String answer = request.getAnswer();
            if (answer == null || answer.trim().isEmpty()) {
                answer = TemplateUtils.generateAnswerTemplate(request.getQuestion(), request.getSql());
            }
            
            // 保存QA对
            Long id = ragKnowledgeBaseService.saveQAPair(
                request.getQuestion(),
                answer,
                request.getSql(),
                category,
                qualityScore
            );
            
            log.info("手动导入QA对成功: id={}, question={}", id, request.getQuestion());
            return Result.success(id);
            
        } catch (Exception e) {
            log.error("手动导入QA对失败", e);
            return Result.error("导入失败: " + e.getMessage());
        }
    }
    
    /**
     * 批量导入QA对
     * 
     * @param requests QA对列表
     * @return 导入结果统计
     */
    @PostMapping("/qa/batch-import")
    public Result<BatchImportResult> batchImportQAPairs(@RequestBody List<QAImportRequest> requests) {
        try {
            if (requests == null || requests.isEmpty()) {
                return Result.error("导入列表不能为空");
            }
            
            BatchImportResult result = ragKnowledgeBaseService.batchImportQAPairs(requests);
            
            log.info("批量导入QA对完成: success={}, failed={}", 
                result.getSuccessCount(), result.getFailedCount());
            
            return Result.success(result);
            
        } catch (Exception e) {
            log.error("批量导入QA对失败", e);
            return Result.error("批量导入失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取知识库统计信息
     */
    @GetMapping("/stats")
    public Result<Object> getStats() {
        try {
            // TODO: 实现统计查询
            return Result.success("统计功能待实现");
        } catch (Exception e) {
            log.error("获取统计信息失败", e);
            return Result.error("获取统计信息失败: " + e.getMessage());
        }
    }
    
    /**
     * 清空知识库（危险操作）
     */
    @DeleteMapping("/clear")
    public Result<String> clearKnowledgeBase() {
        try {
            // TODO: 实现清空功能，需要管理员权限验证
            return Result.error("清空功能待实现，需要管理员权限");
        } catch (Exception e) {
            log.error("清空知识库失败", e);
            return Result.error("清空失败: " + e.getMessage());
        }
    }
}
