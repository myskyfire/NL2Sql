package com.nl2sql.web.controller;

import com.nl2sql.common.result.Result;
import com.nl2sql.common.util.TemplateUtils;
import com.nl2sql.core.rag.RagKnowledgeBaseService;
import com.nl2sql.core.rag.dto.BatchImportResult;
import com.nl2sql.core.rag.dto.QAImportRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
            java.util.Map<String, Object> stats = ragKnowledgeBaseService.getStatistics();
            return Result.success(stats);
        } catch (Exception e) {
            log.error("获取统计信息失败", e);
            return Result.error("获取统计信息失败: " + e.getMessage());
        }
    }
    
    /**
     * 查询知识库列表（支持搜索、分页）
     */
    @GetMapping("/knowledge-list")
    public Result<Object> getKnowledgeList(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        try {
            int offset = page * pageSize;
            java.util.List<RagKnowledgeBaseService.KnowledgeItem> list = 
                ragKnowledgeBaseService.queryKnowledgeList(keyword, offset, pageSize);
            
            long total = ragKnowledgeBaseService.getKnowledgeCount(keyword);
            
            Map<String, Object> result = new HashMap<>();
            result.put("list", list);
            result.put("total", total);
            result.put("page", page);
            result.put("pageSize", pageSize);
            result.put("totalPages", (int) Math.ceil((double) total / pageSize));
            
            return Result.success(result);
        } catch (Exception e) {
            log.error("查询知识库列表失败", e);
            return Result.error("查询列表失败: " + e.getMessage());
        }
    }
    
    /**
     * 删除知识库条目
     */
    @DeleteMapping("/knowledge/{id}")
    public Result<String> deleteKnowledge(@PathVariable Long id) {
        try {
            boolean success = ragKnowledgeBaseService.deleteKnowledge(id);
            if (success) {
                return Result.success("删除成功");
            } else {
                return Result.error("删除失败，记录不存在");
            }
        } catch (Exception e) {
            log.error("删除知识库条目失败: id={}", id, e);
            return Result.error("删除失败: " + e.getMessage());
        }
    }
    
    /**
     * 清空知识库（危险操作）
     */
    @DeleteMapping("/clear")
    public Result<String> clearKnowledgeBase() {
        try {
            int deleted = ragKnowledgeBaseService.clearAllKnowledge();
            log.warn("⚠️ RAG知识库已清空: {}条记录", deleted);
            return Result.success("已清空" + deleted + "条知识");
        } catch (Exception e) {
            log.error("清空知识库失败", e);
            return Result.error("清空失败: " + e.getMessage());
        }
    }
}
