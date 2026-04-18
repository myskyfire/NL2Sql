package com.nl2sql.core.agent.skills;

import groovy.lang.GroovyClassLoader;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Groovy Skill 执行器
 * 
 * 动态扫描、加载并执行 Groovy 脚本实现的 Skills
 */
@Slf4j
@Component
public class GroovySkillExecutor {
    
    private static final String SKILLS_BASE_PATH = "skills/";
    
    /**
     * 从配置文件读取的 Skills 扫描目录列表
     */
    @Value("${skills.groovy.scan-dirs:}")
    private List<String> scanDirs = Collections.emptyList();
    
    private final GroovyClassLoader classLoader = new GroovyClassLoader();
    
    /**
     * 缓存已编译的 Class，避免重复编译
     */
    private final Map<String, Class<?>> classCache = new ConcurrentHashMap<>();
    
    /**
     * 已发现的 Skills 元数据
     */
    private List<SkillInfo> discoveredSkills = Collections.emptyList();
    
    private ApplicationContext applicationContext;
    
    public void setApplicationContext(ApplicationContext context) {
        this.applicationContext = context;
    }
    
    @PostConstruct
    public void scanSkills() {
        log.info("[GroovySkillExecutor] 开始扫描 Groovy Skills...");
        
        // 如果配置文件中没有指定扫描目录，则使用默认值
        if (scanDirs == null || scanDirs.isEmpty()) {
            scanDirs = Arrays.asList("standard-query", "report-with-insights");
            log.info("[GroovySkillExecutor] 未配置 skills.groovy.scan-dirs，使用默认值: {}", scanDirs);
        }
        
        List<SkillInfo> skills = new ArrayList<>();
        
        for (String dir : scanDirs) {
            try {
                String skillPath = SKILLS_BASE_PATH + dir + "/";
                String scriptPath = parseScriptField(skillPath);
                
                if (scriptPath != null && !scriptPath.trim().isEmpty()) {
                    SkillInfo info = new SkillInfo();
                    // ✅ 优先使用 SKILL.md 中的 name 字段，否则使用目录名转换
                    String skillName = parseNameField(skillPath);
                    if (skillName == null || skillName.trim().isEmpty()) {
                        skillName = dir.replace("-", "_"); // standard-query -> standard_query
                    }
                    info.setName(skillName);
                    info.setToolName("execute_" + skillName); // execute_standard_query
                    info.setSkillPath(skillPath);
                    info.setDescription(parseDescription(skillPath));
                    info.setRequiredParams(parseRequiredParams(skillPath));  // ✅ 解析必需参数
                    skills.add(info);
                    
                    log.info("  ✓ 发现 Skill: {} ({}) [必需参数: {}]", 
                        info.getToolName(), info.getDescription(), info.getRequiredParams());
                }
            } catch (Exception e) {
                log.error("  ✗ 扫描 Skill 失败: {}", dir, e);
            }
        }
        
        this.discoveredSkills = Collections.unmodifiableList(skills);
        log.info("[GroovySkillExecutor] 共发现 {} 个 Groovy Skills", skills.size());
    }
    
    /**
     * 获取所有已发现的 Skills
     */
    public List<SkillInfo> getDiscoveredSkills() {
        return discoveredSkills;
    }
    
    /**
     * 执行 Skill
     * 
     * @param skillPath SKILL.md 所在目录路径（classpath）
     * @param context 执行上下文
     * @return 执行结果
     */
    public Object executeSkill(String skillPath, SkillContext context) {
        try {
            // 从 SKILL.md 中解析 script 字段
            String scriptRelativePath = parseScriptField(skillPath);
            if (scriptRelativePath == null || scriptRelativePath.trim().isEmpty()) {
                throw new IllegalArgumentException("SKILL.md 中未配置 script 字段: " + skillPath);
            }
            
            // 构建完整的 classpath 路径
            String scriptClasspath = skillPath.endsWith("/") ? skillPath + scriptRelativePath : skillPath + "/" + scriptRelativePath;
            
            log.debug("[GroovySkillExecutor] 执行 Skill: {}, 脚本路径: {}", skillPath, scriptClasspath);
            
            // 加载或从缓存获取 Class
            Class<?> skillClass = loadOrCacheClass(scriptClasspath);
            
            // 实例化
            Object skillInstance = skillClass.getDeclaredConstructor().newInstance();
            
            // 查找 execute 方法
            Method executeMethod = findExecuteMethod(skillClass);
            if (executeMethod == null) {
                throw new IllegalStateException("Skill 类中未找到 execute(SkillContext) 方法: " + skillClass.getName());
            }
            
            // 注入 ApplicationContext
            context.setApplicationContext(applicationContext);
            
            // 执行
            Object result = executeMethod.invoke(skillInstance, context);
            
            log.debug("[GroovySkillExecutor] Skill 执行成功: {}", skillPath);
            return result;
            
        } catch (Exception e) {
            log.error("[GroovySkillExecutor] 执行 Skill 失败: {}", skillPath, e);
            throw new RuntimeException("执行 Skill 失败: " + skillPath, e);
        }
    }
    
    /**
     * 检查 Skill 是否有对应的 Groovy 脚本
     */
    public boolean hasScript(String skillPath) {
        try {
            String scriptRelativePath = parseScriptField(skillPath);
            if (scriptRelativePath == null || scriptRelativePath.trim().isEmpty()) {
                return false;
            }
            
            String scriptClasspath = skillPath.endsWith("/") ? skillPath + scriptRelativePath : skillPath + "/" + scriptRelativePath;
            return classLoader.getResourceAsStream(scriptClasspath) != null;
            
        } catch (Exception e) {
            log.warn("[GroovySkillExecutor] 检查脚本存在性失败: {}", skillPath, e);
            return false;
        }
    }
    
    /**
     * 从 SKILL.md 中解析 name 字段
     */
    private String parseNameField(String skillPath) {
        try {
            String skillMdPath = skillPath.endsWith("/") ? skillPath + "SKILL.md" : skillPath + "/SKILL.md";
            // ⚠️ 关键修复：使用当前类的 ClassLoader 以支持 Spring Boot JAR 包资源加载
            var resource = new ClassPathResource(skillMdPath, getClass().getClassLoader());
            
            if (!resource.exists()) {
                return null;
            }
            
            try (var reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                
                String line;
                boolean inFrontmatter = false;
                int lineCount = 0;
                
                while ((line = reader.readLine()) != null) {
                    lineCount++;
                    
                    if (line.trim().equals("---")) {
                        if (lineCount == 1) {
                            inFrontmatter = true;
                            continue;
                        } else if (inFrontmatter) {
                            break;
                        }
                    }
                    
                    if (inFrontmatter && line.startsWith("name:")) {
                        return line.substring("name:".length()).trim();
                    }
                }
            }
            
            return null;
            
        } catch (Exception e) {
            log.error("[GroovySkillExecutor] 解析 name 字段失败: {}", skillPath, e);
            return null;
        }
    }
    
    /**
     * 从 SKILL.md 中解析 script 字段
     */
    private String parseScriptField(String skillPath) {
        try {
            String skillMdPath = skillPath.endsWith("/") ? skillPath + "SKILL.md" : skillPath + "/SKILL.md";
            // ⚠️ 关键修复：使用当前类的 ClassLoader 以支持 Spring Boot JAR 包资源加载
            var resource = new ClassPathResource(skillMdPath, getClass().getClassLoader());
            
            if (!resource.exists()) {
                log.warn("[GroovySkillExecutor] 未找到 SKILL.md: {}", skillMdPath);
                return null;
            }
            
            // 简单解析 YAML frontmatter 中的 script 字段
            try (var reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                
                String line;
                boolean inFrontmatter = false;
                int lineCount = 0;
                
                while ((line = reader.readLine()) != null) {
                    lineCount++;
                    
                    if (line.trim().equals("---")) {
                        if (lineCount == 1) {
                            inFrontmatter = true;
                            continue;
                        } else if (inFrontmatter) {
                            break;
                        }
                    }
                    
                    if (inFrontmatter && line.startsWith("script:")) {
                        return line.substring("script:".length()).trim();
                    }
                }
            }
            
            return null;
            
        } catch (Exception e) {
            log.error("[GroovySkillExecutor] 解析 script 字段失败: {}", skillPath, e);
            return null;
        }
    }
    
    /**
     * 从 SKILL.md 中解析 description 字段
     */
    private String parseDescription(String skillPath) {
        try {
            String skillMdPath = skillPath.endsWith("/") ? skillPath + "SKILL.md" : skillPath + "/SKILL.md";
            // ⚠️ 关键修复：使用当前类的 ClassLoader 以支持 Spring Boot JAR 包资源加载
            var resource = new ClassPathResource(skillMdPath, getClass().getClassLoader());
            
            if (!resource.exists()) {
                return "";
            }
            
            try (var reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                
                String line;
                boolean inFrontmatter = false;
                int lineCount = 0;
                
                while ((line = reader.readLine()) != null) {
                    lineCount++;
                    
                    if (line.trim().equals("---")) {
                        if (lineCount == 1) {
                            inFrontmatter = true;
                            continue;
                        } else if (inFrontmatter) {
                            break;
                        }
                    }
                    
                    if (inFrontmatter && line.startsWith("description:")) {
                        return line.substring("description:".length()).trim();
                    }
                }
            }
            
            return "";
            
        } catch (Exception e) {
            log.error("[GroovySkillExecutor] 解析 description 字段失败: {}", skillPath, e);
            return "";
        }
    }
    
    /**
     * ✅ 从 SKILL.md 中解析 requiredParams 字段
     * 格式：requiredParams: [datasourceId, question]
     */
    private Set<String> parseRequiredParams(String skillPath) {
        Set<String> params = new HashSet<>();
        
        try {
            String skillMdPath = skillPath.endsWith("/") ? skillPath + "SKILL.md" : skillPath + "/SKILL.md";
            // ⚠️ 关键修复：使用当前类的 ClassLoader 以支持 Spring Boot JAR 包资源加载
            var resource = new ClassPathResource(skillMdPath, getClass().getClassLoader());
            
            if (!resource.exists()) {
                return params;
            }
            
            try (var reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                
                String line;
                boolean inFrontmatter = false;
                int lineCount = 0;
                
                while ((line = reader.readLine()) != null) {
                    lineCount++;
                    
                    if (line.trim().equals("---")) {
                        if (lineCount == 1) {
                            inFrontmatter = true;
                            continue;
                        } else if (inFrontmatter) {
                            break;
                        }
                    }
                    
                    if (inFrontmatter && line.startsWith("requiredParams:")) {
                        String value = line.substring("requiredParams:".length()).trim();
                        // 解析 YAML 数组格式：[param1, param2]
                        if (value.startsWith("[") && value.endsWith("]")) {
                            String content = value.substring(1, value.length() - 1);
                            String[] items = content.split(",");
                            for (String item : items) {
                                String param = item.trim();
                                if (!param.isEmpty()) {
                                    params.add(param);
                                }
                            }
                        }
                        break;
                    }
                }
            }
            
        } catch (Exception e) {
            log.error("[GroovySkillExecutor] 解析 requiredParams 字段失败: {}", skillPath, e);
        }
        
        return params;
    }
    
    /**
     * 加载或从缓存获取 Class
     */
    private Class<?> loadOrCacheClass(String classpath) throws Exception {
        return classCache.computeIfAbsent(classpath, path -> {
            try {
                // ⚠️ 关键修复：使用当前类的 ClassLoader 以支持 Spring Boot JAR 包资源加载
                var resource = new org.springframework.core.io.ClassPathResource(path, getClass().getClassLoader());
                if (!resource.exists()) {
                    throw new IllegalArgumentException("找不到脚本文件: " + path);
                }
                
                // ⚠️ 关键修复：从 InputStream 读取脚本内容，而不是 getFile()
                // 因为 JAR 包内的资源无法通过文件系统路径访问
                String scriptContent;
                try (var inputStream = resource.getInputStream();
                     var reader = new BufferedReader(
                         new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                    scriptContent = reader.lines().collect(Collectors.joining("\n"));
                }
                
                log.debug("[GroovySkillExecutor] 加载 Groovy 脚本: {}", path);
                return classLoader.parseClass(scriptContent);
                
            } catch (Exception e) {
                throw new RuntimeException("编译 Groovy 脚本失败: " + path, e);
            }
        });
    }
    
    /**
     * 查找 execute(SkillContext) 方法
     */
    private Method findExecuteMethod(Class<?> clazz) {
        for (Method method : clazz.getMethods()) {
            if ("execute".equals(method.getName()) 
                && method.getParameterCount() == 1 
                && SkillContext.class.isAssignableFrom(method.getParameterTypes()[0])) {
                return method;
            }
        }
        return null;
    }
    
    /**
     * 清除缓存（用于热重载）
     */
    public void clearCache() {
        classCache.clear();
        log.info("[GroovySkillExecutor] 已清除 Class 缓存");
    }
    
    // ==================== 内部类 ====================
    
    @Data
    public static class SkillInfo {
        private String name;          // Skill 名称（如 standard_query）
        private String toolName;      // Tool 名称（如 execute_standard_query）
        private String skillPath;     // SKILL.md 路径
        private String description;   // Skill 描述
        private Set<String> requiredParams = new HashSet<>();  // ✅ 必需参数列表
    }
}
