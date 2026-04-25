package com.nl2sql.web.controller;

import com.nl2sql.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * 帮助中心控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/help")
public class HelpController {
    
    /**
     * 获取帮助中心HTML内容
     */
    @GetMapping("/content")
    public Result<String> getHelpContent() {
        try {
            // 读取USER_QUERY_GUIDE.md文件
            ClassPathResource resource = new ClassPathResource("USER_QUERY_GUIDE.md");
            
            if (!resource.exists()) {
                log.warn("帮助文档不存在: USER_QUERY_GUIDE.md");
                return Result.error("帮助文档不存在");
            }
            
            // 读取文件内容
            String markdownContent;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                markdownContent = reader.lines().collect(Collectors.joining("\n"));
            }
            
            // 将Markdown转换为HTML
            String htmlContent = convertMarkdownToHtml(markdownContent);
            
            return Result.success(htmlContent);
        } catch (Exception e) {
            log.error("获取帮助内容失败", e);
            return Result.error("获取帮助内容失败: " + e.getMessage());
        }
    }
    
    /**
     * 简单的Markdown转HTML转换器
     * 针对USER_QUERY_GUIDE.md的格式进行优化
     */
    private String convertMarkdownToHtml(String markdown) {
        StringBuilder html = new StringBuilder();
        String[] lines = markdown.split("\n");
        
        boolean inTable = false;
        boolean inCodeBlock = false;
        StringBuilder tableHtml = new StringBuilder();
        StringBuilder codeBlock = new StringBuilder();
        
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            
            // 代码块处理
            if (line.trim().startsWith("```")) {
                if (inCodeBlock) {
                    // 结束代码块
                    html.append("<div class=\"code-block\"><pre><code>")
                        .append(escapeHtml(codeBlock.toString()))
                        .append("</code></pre></div>\n");
                    codeBlock.setLength(0);
                    inCodeBlock = false;
                } else {
                    // 开始代码块
                    inCodeBlock = true;
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
                        tableHtml.append("<td>").append(trimmed).append("</td>\n");
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
            
            // 标题
            if (line.startsWith("#### ")) {
                html.append("<h4>").append(line.substring(5)).append("</h4>\n");
            } else if (line.startsWith("### ")) {
                html.append("<h3 id=\"").append(generateId(line.substring(4))).append("\">")
                    .append(line.substring(4)).append("</h3>\n");
            } else if (line.startsWith("## ")) {
                html.append("<h2 id=\"").append(generateId(line.substring(3))).append("\">")
                    .append(line.substring(3)).append("</h2>\n");
            } else if (line.startsWith("# ")) {
                html.append("<h1>").append(line.substring(2)).append("</h1>\n");
            }
            // 列表项
            else if (line.trim().startsWith("- ") || line.trim().startsWith("* ")) {
                String content = line.trim().substring(2);
                html.append("<li>").append(processInlineFormatting(content)).append("</li>\n");
            }
            // 有序列表
            else if (line.matches("^\\d+\\.\\s.*")) {
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
        
        return html.toString();
    }
    
    /**
     * 处理行内格式化（粗体、代码等）
     */
    private String processInlineFormatting(String text) {
        // 粗体 **text**
        text = text.replaceAll("\\*\\*(.+?)\\*\\*", "<strong>$1</strong>");
        
        // 行内代码 `code`
        text = text.replaceAll("`([^`]+)`", "<code style=\"background:#f0f0f0;padding:2px 6px;border-radius:3px;\">$1</code>");
        
        // 链接 [text](url)
        text = text.replaceAll("\\[([^\\]]+)\\]\\(([^)]+)\\)", "<a href=\"$2\" target=\"_blank\">$1</a>");
        
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
                  .replaceAll("[^a-z0-9\\u4e00-\\u9fa5]", "-")
                  .replaceAll("-+", "-")
                  .replaceAll("^-|-$", "");
    }
}
