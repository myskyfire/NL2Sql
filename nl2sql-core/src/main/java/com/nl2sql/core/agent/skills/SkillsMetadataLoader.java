package com.nl2sql.core.agent.skills;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Skills 元数据加载器
 * 
 * 从 skills.json 文件加载 Skills 描述信息，
 * 用于动态生成 SystemMessage 中的 Skills 说明
 */
@Slf4j
@Component
public class SkillsMetadataLoader {
    
    private static final String SKILLS_BASE_PATH = "skills/";
    
    private List<SkillMetadata> skills = Collections.emptyList();
    
    private final Yaml yaml = new Yaml();
    
    @PostConstruct
    public void loadSkills() {
        try {
            log.info("[SkillsMetadataLoader] 开始扫描 Skills...");
            
            // 扫描 skills 目录下的所有子目录
            Resource baseResource = new ClassPathResource(SKILLS_BASE_PATH);
            if (!baseResource.exists()) {
                log.warn("[SkillsMetadataLoader] 未找到 {} 目录，使用空列表", SKILLS_BASE_PATH);
                return;
            }
            
            // 获取所有 SKILL.md 文件
            List<SkillMetadata> discoveredSkills = new ArrayList<>();
            
            // 手动扫描已知技能目录（Spring Resource 不支持直接列出目录）
            String[] skillDirs = {"standard-query", "report-with-insights"};
            
            for (String dir : skillDirs) {
                try {
                    String skillPath = SKILLS_BASE_PATH + dir + "/SKILL.md";
                    Resource skillResource = new ClassPathResource(skillPath);
                    
                    if (skillResource.exists()) {
                        SkillMetadata skill = parseSkillMarkdown(skillResource);
                        if (skill != null) {
                            discoveredSkills.add(skill);
                            log.info("  ✓ 加载 Skill: {} ({})", skill.getName(), skill.getDisplayName());
                        }
                    } else {
                        log.warn("  ⚠ 未找到: {}", skillPath);
                    }
                } catch (Exception e) {
                    log.error("  ✗ 加载 Skill 失败: {}", dir, e);
                }
            }
            
            this.skills = Collections.unmodifiableList(discoveredSkills);
            log.info("[SkillsMetadataLoader] 成功加载 {} 个 Skills", skills.size());
            
        } catch (Exception e) {
            log.error("[SkillsMetadataLoader] 加载 Skills 失败", e);
            this.skills = Collections.emptyList();
        }
    }
    
    /**
     * 获取所有 Skills 元数据
     */
    public List<SkillMetadata> getAllSkills() {
        return Collections.unmodifiableList(skills);
    }
    
    /**
     * 根据名称获取 Skill 元数据
     */
    public SkillMetadata getSkillByName(String name) {
        return skills.stream()
            .filter(s -> s.getName().equals(name))
            .findFirst()
            .orElse(null);
    }
    
    /**
     * 生成 SystemMessage 中的 Skills 说明文本
     */
    public String generateSkillsDescription() {
        if (skills.isEmpty()) {
            return "当前没有可用的 Skills。";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("## 可用的高级技能（Skills）\n\n");
        sb.append("你可以调用以下高级技能来完成复杂任务。**优先使用 Skills**，而非手动组合底层 Tools！\n\n");
        
        // 按优先级排序
        List<SkillMetadata> sortedSkills = skills.stream()
            .sorted(Comparator.comparingInt(SkillMetadata::getPriority))
            .collect(Collectors.toList());
        
        for (int i = 0; i < sortedSkills.size(); i++) {
            SkillMetadata skill = sortedSkills.get(i);
            sb.append("### ").append(i + 1).append(". **").append(skill.getName()).append("** - ")
              .append(skill.getDisplayName()).append("\n\n");
            sb.append("**功能**：").append(skill.getDescription()).append("\n\n");
            
            if (skill.getWhenToUse() != null && !skill.getWhenToUse().isEmpty()) {
                sb.append("**适用场景**：").append(skill.getWhenToUse()).append("\n\n");
            }
            
            if (skill.getWhenNotToUse() != null && !skill.getWhenNotToUse().isEmpty()) {
                sb.append("**不适用场景**：").append(skill.getWhenNotToUse()).append("\n\n");
            }
            
            if (skill.getExamples() != null && !skill.getExamples().isEmpty()) {
                sb.append("**示例**：\n");
                for (String example : skill.getExamples()) {
                    sb.append("- \"").append(example).append("\"\n");
                }
                sb.append("\n");
            }
            
            if (skill.getFeatures() != null && !skill.getFeatures().isEmpty()) {
                sb.append("**特性**：\n");
                for (String feature : skill.getFeatures()) {
                    sb.append("- ").append(feature).append("\n");
                }
                sb.append("\n");
            }
            
            sb.append("---\n\n");
        }
        
        sb.append("⚠️ **重要原则**：\n");
        sb.append("- 优先使用 Skills，它们封装了完整的业务流程和错误处理\n");
        sb.append("- 只有在需要精细控制或调试时，才手动调用底层 Tools\n");
        sb.append("- 选择 Skill 时，参考'适用场景'和'不适用场景'\n");
        
        return sb.toString();
    }
    
    /**
     * 解析 SKILL.md 文件，提取 YAML frontmatter
     */
    private SkillMetadata parseSkillMarkdown(Resource resource) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            
            StringBuilder content = new StringBuilder();
            String line;
            boolean inFrontmatter = false;
            StringBuilder frontmatterBuilder = new StringBuilder();
            int lineCount = 0;

            while ((line = reader.readLine()) != null) {
                lineCount++;
                
                // 检测 frontmatter 边界
                String trimmedLine = line.trim();
                
                if (trimmedLine.equals("---")) {
                    if (!inFrontmatter && lineCount <= 5) {
                        // 开始的 --- (允许前几行有空行或BOM)
                        inFrontmatter = true;
                        continue;
                    } else if (inFrontmatter) {
                        // 结束的 ---
                        break;
                    }
                }
                
                if (inFrontmatter) {
                    frontmatterBuilder.append(line).append("\n");
                }
                
                content.append(line).append("\n");
            }
            
            // 解析 YAML frontmatter
            String frontmatterYaml = frontmatterBuilder.toString();
            if (frontmatterYaml.trim().isEmpty()) {
                log.warn("[SkillsMetadataLoader] 未找到 YAML frontmatter: {}", resource.getFilename());
                return null;
            }
            
            Map<String, Object> frontmatter = yaml.load(frontmatterYaml);
            
            // 构建 SkillMetadata
            SkillMetadata skill = new SkillMetadata();
            skill.setName((String) frontmatter.get("name"));
            skill.setDisplayName((String) frontmatter.getOrDefault("displayName", skill.getName()));
            skill.setDescription((String) frontmatter.get("description"));
            skill.setCategory((String) frontmatter.getOrDefault("category", "general"));
            skill.setPriority(((Number) frontmatter.getOrDefault("priority", 999)).intValue());
            skill.setVersion((String) frontmatter.getOrDefault("version", "1.0"));
            
            // 验证必填字段
            if (skill.getName() == null || skill.getName().trim().isEmpty()) {
                log.error("[SkillsMetadataLoader] Skill 缺少 name 字段: {}", resource.getFilename());
                return null;
            }
            if (skill.getDescription() == null || skill.getDescription().trim().isEmpty()) {
                log.error("[SkillsMetadataLoader] Skill 缺少 description 字段: {}", resource.getFilename());
                return null;
            }
            
            // 从 Markdown body 中提取示例和特性（简化版，可以根据需要扩展）
            String markdownBody = content.toString();
            skill.setExamples(extractExamples(markdownBody));
            skill.setFeatures(extractFeatures(markdownBody));
            skill.setWhenToUse(extractSection(markdownBody, "适用场景"));
            skill.setWhenNotToUse(extractSection(markdownBody, "不应该使用此 Skill"));
            
            return skill;
            
        } catch (IOException e) {
            log.error("[SkillsMetadataLoader] 解析 SKILL.md 失败: {}", resource.getFilename(), e);
            return null;
        }
    }
    
    /**
     * 从 Markdown 中提取示例
     */
    private List<String> extractExamples(String markdown) {
        List<String> examples = new ArrayList<>();
        // 简单实现：查找 "**用户**：" 后面的内容
        String[] lines = markdown.split("\n");
        for (String line : lines) {
            if (line.contains("**用户**：")) {
                int start = line.indexOf("**用户**：") + "**用户**：".length();
                String example = line.substring(start).trim();
                if (!example.isEmpty()) {
                    examples.add(example);
                }
            }
        }
        return examples.isEmpty() ? Arrays.asList("查询数据", "统计分析") : examples;
    }
    
    /**
     * 从 Markdown 中提取特性
     */
    private List<String> extractFeatures(String markdown) {
        List<String> features = new ArrayList<>();
        // 简单实现：查找 "- " 开头的列表项（在功能说明部分）
        boolean inFeatureSection = false;
        String[] lines = markdown.split("\n");
        for (String line : lines) {
            if (line.contains("## 功能说明")) {
                inFeatureSection = true;
                continue;
            }
            if (inFeatureSection && line.startsWith("## ")) {
                break;
            }
            if (inFeatureSection && line.trim().startsWith("- ")) {
                features.add(line.trim().substring(2).trim());
            }
        }
        return features.isEmpty() ? Arrays.asList("自动处理完整流程") : features;
    }
    
    /**
     * 从 Markdown 中提取指定章节内容
     */
    private String extractSection(String markdown, String sectionTitle) {
        String[] lines = markdown.split("\n");
        StringBuilder section = new StringBuilder();
        boolean inSection = false;
        
        for (String line : lines) {
            if (line.contains(sectionTitle)) {
                inSection = true;
                continue;
            }
            if (inSection && line.startsWith("## ")) {
                break;
            }
            if (inSection && !line.trim().isEmpty()) {
                section.append(line.trim()).append(" ");
            }
        }
        
        return section.length() > 0 ? section.toString().trim() : "";
    }
    
    // ==================== 内部数据类 ====================
    
    @Data
    public static class SkillsConfig {
        private List<SkillMetadata> skills;
        private Metadata metadata;
    }
    
    @Data
    public static class SkillMetadata {
        private String name;
        private String displayName;
        private String description;
        private String category;
        private Integer priority;
        private String version;
        private List<SkillParameter> parameters;
        private List<String> examples;
        private List<String> features;
        private String whenToUse;
        private String whenNotToUse;
    }
    
    @Data
    public static class SkillParameter {
        private String name;
        private String type;
        private boolean required;
        private String description;
    }
    
    @Data
    public static class Metadata {
        private String version;
        private String lastUpdated;
        private Integer totalSkills;
    }
}
