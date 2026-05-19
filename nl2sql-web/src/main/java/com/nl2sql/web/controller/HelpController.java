package com.nl2sql.web.controller;

import com.nl2sql.auth.service.AuthService;
import com.nl2sql.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 帮助中心控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/help")
public class HelpController {
    
    @Autowired
    private AuthService authService;
    
    /**
     * 文档反馈存储（内存中，生产环境应使用数据库）
     */
    private static final List<Map<String, Object>> FEEDBACK_STORE = new ArrayList<>();
    private static final Map<String, Integer> FEEDBACK_STATS = new ConcurrentHashMap<>();
    
    /**
     * 文档分类配置
     * requiredRole: null 表示所有用户可访问，"admin" 表示仅管理员可访问
     */
    private static final List<Map<String, Object>> CATEGORIES = buildCategories();
    
    private static List<Map<String, Object>> buildCategories() {
        List<Map<String, Object>> categories = new ArrayList<>();
        
        Map<String, Object> coreQuery = new HashMap<>();
        coreQuery.put("id", "core-query");
        coreQuery.put("name", "核心查询功能");
        coreQuery.put("icon", "🤖");
        coreQuery.put("requiredRole", null);
        coreQuery.put("docs", List.of(
            Map.of("id", "agent-chat", "name", "AI Agent 对话", "file", "AGENT_CHAT_GUIDE.md"),
            Map.of("id", "stream-chat", "name", "流式对话", "file", "STREAM_CHAT_GUIDE.md"),
            Map.of("id", "query", "name", "DataMind 查询", "file", "QUERY_GUIDE.md")
        ));
        categories.add(coreQuery);
        
        Map<String, Object> basicConfig = new HashMap<>();
        basicConfig.put("id", "basic-config");
        basicConfig.put("name", "基础配置");
        basicConfig.put("icon", "🗄️");
        basicConfig.put("requiredRole", null);
        basicConfig.put("docs", List.of(
            Map.of("id", "datasource", "name", "数据源管理", "file", "DATASOURCE_GUIDE.md"),
            Map.of("id", "relationship", "name", "表关联管理", "file", "RELATIONSHIP_GUIDE.md"),
            Map.of("id", "metadata", "name", "元数据查看", "file", "METADATA_GUIDE.md")
        ));
        categories.add(basicConfig);
        
        Map<String, Object> aiEnhancement = new HashMap<>();
        aiEnhancement.put("id", "ai-enhancement");
        aiEnhancement.put("name", "AI增强配置");
        aiEnhancement.put("icon", "🧠");
        aiEnhancement.put("requiredRole", "admin");
        aiEnhancement.put("docs", List.of(
            Map.of("id", "rag", "name", "RAG知识库", "file", "RAG_GUIDE.md"),
            Map.of("id", "prompt", "name", "Prompt学习", "file", "PROMPT_GUIDE.md"),
            Map.of("id", "industry-concept", "name", "行业概念管理", "file", "INDUSTRY_CONCEPT_GUIDE.md")
        ));
        categories.add(aiEnhancement);
        
        Map<String, Object> permissionAudit = new HashMap<>();
        permissionAudit.put("id", "permission-audit");
        permissionAudit.put("name", "权限与审计");
        permissionAudit.put("icon", "🔐");
        permissionAudit.put("requiredRole", "admin");
        permissionAudit.put("docs", List.of(
            Map.of("id", "user-management", "name", "用户管理", "file", "USER_MANAGEMENT_GUIDE.md"),
            Map.of("id", "table-permission", "name", "表授权管理", "file", "TABLE_PERMISSION_GUIDE.md"),
            Map.of("id", "execution-logs", "name", "执行日志", "file", "EXECUTION_LOG_GUIDE.md"),
            Map.of("id", "monitoring", "name", "监控Dashboard", "file", "MONITORING_GUIDE.md")
        ));
        categories.add(permissionAudit);
        
        Map<String, Object> tools = new HashMap<>();
        tools.put("id", "tools");
        tools.put("name", "工具类");
        tools.put("icon", "🛠️");
        tools.put("requiredRole", null);
        tools.put("docs", List.of(
            Map.of("id", "manual-sql", "name", "手动执行SQL", "file", "MANUAL_SQL_GUIDE.md"),
            Map.of("id", "template", "name", "查询模板", "file", "TEMPLATE_GUIDE.md"),
            Map.of("id", "quickstart", "name", "快速开始", "file", "QUICKSTART.md")
        ));
        categories.add(tools);
        
        return List.copyOf(categories);
    }
    
    /**
     * 获取文档分类列表（根据用户角色过滤）
     */
    @GetMapping("/categories")
    public Result<List<Map<String, Object>>> getCategories(
            @RequestHeader(value = "Authorization", required = false) String token) {
        try {
            String userRole = "user";
            
            if (token != null && !token.isEmpty()) {
                AuthService.UserInfo userInfo = authService.validateToken(token);
                if (userInfo != null && userInfo.getRole() != null) {
                    userRole = userInfo.getRole();
                }
            }
            
            List<Map<String, Object>> filteredCategories = new ArrayList<>();
            
            for (Map<String, Object> category : CATEGORIES) {
                String requiredRole = (String) category.get("requiredRole");
                
                if (requiredRole == null || userRole.equals(requiredRole)) {
                    filteredCategories.add(category);
                }
            }
            
            return Result.success(filteredCategories);
        } catch (Exception e) {
            log.error("获取帮助分类失败", e);
            return Result.error("获取帮助分类失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取指定文档内容
     */
    @GetMapping("/content/{docId}")
    public Result<String> getHelpContent(
            @RequestHeader(value = "Authorization", required = false) String token,
            @PathVariable String docId) {
        try {
            String userRole = "user";
            
            if (token != null && !token.isEmpty()) {
                AuthService.UserInfo userInfo = authService.validateToken(token);
                if (userInfo != null && userInfo.getRole() != null) {
                    userRole = userInfo.getRole();
                }
            }
            
            String fileName = findDocumentFile(docId);
            if (fileName == null) {
                log.warn("文档不存在: docId={}", docId);
                return Result.error("文档不存在");
            }
            
            if (!hasDocumentPermission(docId, userRole)) {
                log.warn("用户无权限访问文档: docId={}, role={}", docId, userRole);
                return Result.error("权限不足，无法访问该文档");
            }
            
            ClassPathResource resource = new ClassPathResource(fileName);
            if (!resource.exists()) {
                log.warn("文档文件不存在: {}", fileName);
                return Result.error("文档文件不存在");
            }
            
            String markdownContent;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                markdownContent = reader.lines().collect(Collectors.joining("\n"));
            }
            
            String htmlContent = convertMarkdownToHtml(markdownContent);
            return Result.success(htmlContent);
        } catch (Exception e) {
            log.error("获取帮助内容失败", e);
            return Result.error("获取帮助内容失败: " + e.getMessage());
        }
    }
    
    /**
     * 搜索文档（根据用户角色过滤）
     */
    @GetMapping("/search")
    public Result<List<Map<String, Object>>> searchHelp(
            @RequestHeader(value = "Authorization", required = false) String token,
            @RequestParam String q) {
        try {
            List<Map<String, Object>> results = new ArrayList<>();
            
            if (q == null || q.trim().isEmpty()) {
                return Result.success(results);
            }
            
            String userRole = "user";
            if (token != null && !token.isEmpty()) {
                AuthService.UserInfo userInfo = authService.validateToken(token);
                if (userInfo != null && userInfo.getRole() != null) {
                    userRole = userInfo.getRole();
                }
            }
            
            String query = q.toLowerCase().trim();
            
            for (Map<String, Object> category : CATEGORIES) {
                String requiredRole = (String) category.get("requiredRole");
                if (requiredRole != null && !userRole.equals(requiredRole)) {
                    continue;
                }
                
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> docs = (List<Map<String, Object>>) category.get("docs");
                
                for (Map<String, Object> doc : docs) {
                    String fileName = (String) doc.get("file");
                    String docId = (String) doc.get("id");
                    String docName = (String) doc.get("name");
                    
                    try {
                        ClassPathResource resource = new ClassPathResource(fileName);
                        if (resource.exists()) {
                            String content;
                            try (BufferedReader reader = new BufferedReader(
                                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                                content = reader.lines().collect(Collectors.joining("\n"));
                            }
                            
                            List<Map<String, Object>> matches = searchInContent(content, query, docId, docName, (String) category.get("name"));
                            results.addAll(matches);
                        }
                    } catch (Exception e) {
                        log.warn("搜索文档失败: {}", fileName, e);
                    }
                }
            }
            
            return Result.success(results);
        } catch (Exception e) {
            log.error("搜索帮助文档失败", e);
            return Result.error("搜索失败: " + e.getMessage());
        }
    }
    
    /**
     * 在文档内容中搜索
     */
    private List<Map<String, Object>> searchInContent(String content, String query, String docId, String docName, String categoryName) {
        List<Map<String, Object>> results = new ArrayList<>();
        String[] lines = content.split("\n");
        
        String currentSection = "";
        boolean inCodeBlock = false;
        
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            
            // 跟踪代码块
            if (line.trim().startsWith("```")) {
                inCodeBlock = !inCodeBlock;
                continue;
            }
            
            // 跳过代码块中的内容
            if (inCodeBlock) {
                continue;
            }
            
            // 跟踪当前章节
            if (line.startsWith("## ")) {
                currentSection = line.substring(3).trim();
            } else if (line.startsWith("### ")) {
                currentSection = line.substring(4).trim();
            }
            
            // 搜索匹配
            if (line.toLowerCase().contains(query)) {
                Map<String, Object> result = new HashMap<>();
                result.put("docId", docId);
                result.put("docName", docName);
                result.put("categoryName", categoryName);
                result.put("section", currentSection);
                result.put("lineNumber", i + 1);
                result.put("content", extractContext(lines, i, query));
                result.put("highlight", extractHighlight(line, query));
                
                results.add(result);
            }
        }
        
        return results;
    }
    
    /**
     * 提取上下文（前后各 2 行）
     */
    private String extractContext(String[] lines, int index, String query) {
        StringBuilder context = new StringBuilder();
        int start = Math.max(0, index - 2);
        int end = Math.min(lines.length - 1, index + 2);
        
        for (int i = start; i <= end; i++) {
            if (i == index) {
                context.append("→ ");
            }
            context.append(lines[i].trim()).append("\n");
        }
        
        return context.toString();
    }
    
    /**
     * 提取高亮文本
     */
    private String extractHighlight(String line, String query) {
        int index = line.toLowerCase().indexOf(query);
        if (index == -1) {
            return line.length() > 100 ? line.substring(0, 100) + "..." : line;
        }
        
        int start = Math.max(0, index - 30);
        int end = Math.min(line.length(), index + query.length() + 30);
        
        String highlight = line.substring(start, end);
        if (start > 0) highlight = "..." + highlight;
        if (end < line.length()) highlight = highlight + "...";
        
        return highlight;
    }
    
    /**
     * 根据文档 ID 查找文件名
     */
    private String findDocumentFile(String docId) {
        for (Map<String, Object> category : CATEGORIES) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> docs = (List<Map<String, Object>>) category.get("docs");
            
            for (Map<String, Object> doc : docs) {
                if (docId.equals(doc.get("id"))) {
                    return (String) doc.get("file");
                }
            }
        }
        return null;
    }
    
    /**
     * 检查用户是否有权限访问指定文档
     */
    private boolean hasDocumentPermission(String docId, String userRole) {
        for (Map<String, Object> category : CATEGORIES) {
            String requiredRole = (String) category.get("requiredRole");
            
            if (requiredRole != null && !userRole.equals(requiredRole)) {
                continue;
            }
            
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> docs = (List<Map<String, Object>>) category.get("docs");
            
            for (Map<String, Object> doc : docs) {
                if (docId.equals(doc.get("id"))) {
                    return true;
                }
            }
        }
        return false;
    }
    
    /**
     * 简单的Markdown转HTML转换器
     */
    private String convertMarkdownToHtml(String markdown) {
        StringBuilder html = new StringBuilder();
        String[] lines = markdown.split("\n");
        
        boolean inTable = false;
        boolean inCodeBlock = false;
        boolean inList = false;
        StringBuilder tableHtml = new StringBuilder();
        StringBuilder codeBlock = new StringBuilder();
        String currentCodeLang = "";
        
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            
            // 代码块处理
            if (line.trim().startsWith("```")) {
                if (inCodeBlock) {
                    // 结束代码块
                    html.append("<div class=\"code-block\"><pre><code class=\"language-")
                        .append(currentCodeLang)
                        .append("\">")
                        .append(escapeHtml(codeBlock.toString()))
                        .append("</code></pre></div>\n");
                    codeBlock.setLength(0);
                    inCodeBlock = false;
                    currentCodeLang = "";
                } else {
                    // 开始代码块
                    inCodeBlock = true;
                    // 提取语言标识
                    String lang = line.trim().substring(3).trim();
                    if (!lang.isEmpty()) {
                        currentCodeLang = lang;
                    }
                }
                continue;
            }
            
            if (inCodeBlock) {
                codeBlock.append(line).append("\n");
                continue;
            }
            
            // 表格处理
            if (line.trim().startsWith("|")) {
                if (!inTable) {
                    inTable = true;
                    tableHtml.append("<table>\n<thead>\n<tr>\n");
                }
                
                // 跳过分隔线
                if (line.contains("---")) {
                    tableHtml.append("</tr>\n</thead>\n<tbody>\n");
                    continue;
                }
                
                // 解析表格行
                String[] cells = line.split("\\|");
                tableHtml.append("<tr>\n");
                for (String cell : cells) {
                    String trimmed = cell.trim();
                    if (!trimmed.isEmpty()) {
                        tableHtml.append("<td>").append(processInlineFormatting(trimmed)).append("</td>\n");
                    }
                }
                tableHtml.append("</tr>\n");
                continue;
            } else if (inTable) {
                // 表格结束
                tableHtml.append("</tbody>\n</table>\n");
                html.append(tableHtml.toString());
                tableHtml.setLength(0);
                inTable = false;
            }
            
            // 关闭列表
            if (inList && !line.trim().startsWith("- ") && !line.trim().startsWith("* ") && !line.matches("^\\d+\\.\\s.*")) {
                html.append("</ul>\n");
                inList = false;
            }
            
            // 标题
            if (line.startsWith("#### ")) {
                html.append("<h4 id=\"").append(generateId(line.substring(5))).append("\">")
                    .append(line.substring(5)).append("</h4>\n");
            } else if (line.startsWith("### ")) {
                html.append("<h3 id=\"").append(generateId(line.substring(4))).append("\">")
                    .append(line.substring(4)).append("</h3>\n");
            } else if (line.startsWith("## ")) {
                html.append("<h2 id=\"").append(generateId(line.substring(3))).append("\">")
                    .append(line.substring(3)).append("</h2>\n");
            } else if (line.startsWith("# ")) {
                html.append("<h1 id=\"").append(generateId(line.substring(2))).append("\">")
                    .append(line.substring(2)).append("</h1>\n");
            }
            // 无序列表
            else if (line.trim().startsWith("- ") || line.trim().startsWith("* ")) {
                if (!inList) {
                    html.append("<ul>\n");
                    inList = true;
                }
                String content = line.trim().substring(2);
                html.append("<li>").append(processInlineFormatting(content)).append("</li>\n");
            }
            // 有序列表
            else if (line.matches("^\\d+\\.\\s.*")) {
                if (!inList) {
                    html.append("<ul>\n");
                    inList = true;
                }
                String content = line.replaceFirst("^\\d+\\.\\s", "");
                html.append("<li>").append(processInlineFormatting(content)).append("</li>\n");
            }
            // 空行
            else if (line.trim().isEmpty()) {
                html.append("\n");
            }
            // 普通段落
            else {
                html.append("<p>").append(processInlineFormatting(line)).append("</p>\n");
            }
        }
        
        // 关闭未结束的表格
        if (inTable) {
            tableHtml.append("</tbody>\n</table>\n");
            html.append(tableHtml.toString());
        }
        
        // 关闭未结束的列表
        if (inList) {
            html.append("</ul>\n");
        }
        
        return html.toString();
    }
    
    /**
     * 处理行内格式化（粗体、代码等）
     */
    private String processInlineFormatting(String text) {
        // 粗体 **text**
        text = text.replaceAll("\\*\\*(.+?)\\*\\*", "<strong>$1</strong>");
        
        // 斜体 *text*
        text = text.replaceAll("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)", "<em>$1</em>");
        
        // 行内代码 `code`
        text = text.replaceAll("`([^`]+)`", "<code class=\"inline-code\">$1</code>");
        
        // 链接 [text](url)
        text = text.replaceAll("\\[([^\\]]+)\\]\\(([^)]+)\\)", "<a href=\"$2\" target=\"_blank\">$1</a>");
        
        // 对勾符号
        text = text.replaceAll("✅", "<span class=\"checkmark\">✅</span>");
        text = text.replaceAll("❌", "<span class=\"cross\">❌</span>");
        
        return text;
    }
    
    /**
     * HTML转义
     */
    private String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                  .replace("<", "&lt;")
                  .replace(">", "&gt;")
                  .replace("\"", "&quot;")
                  .replace("'", "&#39;");
    }
    
    /**
     * 生成HTML ID
     */
    private String generateId(String text) {
        return text.toLowerCase()
                  .replaceAll("[^a-z0-9\u4e00-\u9fa5]", "-")
                  .replaceAll("-+", "-")
                  .replaceAll("^-|-$", "");
    }
    
    /**
     * 提交文档反馈
     */
    @PostMapping("/feedback")
    public Result<Map<String, Object>> submitFeedback(@RequestBody Map<String, Object> feedback) {
        try {
            String docId = (String) feedback.get("docId");
            String docName = (String) feedback.get("docName");
            String type = (String) feedback.get("type");
            String comment = (String) feedback.get("comment");
            
            if (docId == null || type == null) {
                return Result.error("缺少必要参数");
            }
            
            Map<String, Object> feedbackRecord = new HashMap<>();
            feedbackRecord.put("docId", docId);
            feedbackRecord.put("docName", docName);
            feedbackRecord.put("type", type);
            feedbackRecord.put("comment", comment != null ? comment : "");
            feedbackRecord.put("timestamp", LocalDateTime.now().toString());
            
            synchronized (FEEDBACK_STORE) {
                FEEDBACK_STORE.add(feedbackRecord);
            }
            
            String statsKey = docId + ":" + type;
            FEEDBACK_STATS.merge(statsKey, 1, Integer::sum);
            
            log.info("收到文档反馈: docId={}, type={}, comment={}", docId, type, comment);
            
            Map<String, Object> response = new HashMap<>();
            response.put("message", "反馈已提交");
            return Result.success(response);
        } catch (Exception e) {
            log.error("提交反馈失败", e);
            return Result.error("提交反馈失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取文档反馈统计
     */
    @GetMapping("/feedback/stats")
    public Result<Map<String, Object>> getFeedbackStats(@RequestParam(required = false) String docId) {
        try {
            Map<String, Object> stats = new HashMap<>();
            
            if (docId != null) {
                String helpfulKey = docId + ":helpful";
                String notHelpfulKey = docId + ":not-helpful";
                stats.put("helpful", FEEDBACK_STATS.getOrDefault(helpfulKey, 0));
                stats.put("notHelpful", FEEDBACK_STATS.getOrDefault(notHelpfulKey, 0));
                stats.put("total", FEEDBACK_STATS.getOrDefault(helpfulKey, 0) + FEEDBACK_STATS.getOrDefault(notHelpfulKey, 0));
            } else {
                stats.put("total", FEEDBACK_STATS.values().stream().mapToInt(Integer::intValue).sum());
                stats.put("byDoc", new HashMap<>(FEEDBACK_STATS));
            }
            
            return Result.success(stats);
        } catch (Exception e) {
            log.error("获取反馈统计失败", e);
            return Result.error("获取反馈统计失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取文档反馈列表（管理员）
     */
    @GetMapping("/feedback/list")
    public Result<List<Map<String, Object>>> getFeedbackList(
            @RequestHeader(value = "Authorization", required = false) String token,
            @RequestParam(required = false) String docId,
            @RequestParam(defaultValue = "50") int limit) {
        try {
            String userRole = "user";

            if (token != null && !token.isEmpty()) {
                AuthService.UserInfo userInfo = authService.validateToken(token);
                if (userInfo != null && userInfo.getRole() != null) {
                    userRole = userInfo.getRole();
                }
            }

            if (!"admin".equals(userRole)) {
                log.warn("非管理员用户尝试访问反馈列表: role={}", userRole);
                return Result.error("权限不足，仅管理员可访问反馈列表");
            }
            List<Map<String, Object>> feedbackList;
            
            synchronized (FEEDBACK_STORE) {
                if (docId != null) {
                    feedbackList = FEEDBACK_STORE.stream()
                            .filter(f -> docId.equals(f.get("docId")))
                            .collect(Collectors.toList());
                } else {
                    feedbackList = new ArrayList<>(FEEDBACK_STORE);
                }
            }
            
            int startIndex = Math.max(0, feedbackList.size() - limit);
            List<Map<String, Object>> recentFeedback = feedbackList.subList(startIndex, feedbackList.size());
            
            return Result.success(recentFeedback);
        } catch (Exception e) {
            log.error("获取反馈列表失败", e);
            return Result.error("获取反馈列表失败: " + e.getMessage());
        }
    }
}
