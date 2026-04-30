package com.nl2sql.web.controller;

import com.nl2sql.core.agent.skills.GroovySkillExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Skills 管理控制器
 * 
 * 提供 Skills 热重载、重新扫描等管理功能
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/skills")
public class SkillsAdminController {
    
    @Autowired
    private GroovySkillExecutor groovySkillExecutor;
    
    /**
     * 清除 Groovy 脚本缓存（热重载）
     * 
     * @param skillName 可选，指定 Skill 名称；不传则清除所有缓存
     * @return 操作结果
     */
    @PostMapping("/reload")
    public Map<String, Object> reload(@RequestParam(required = false) String skillName) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            log.info("[SkillsAdmin] 收到重载请求: skillName={}", skillName);
            
            // 清除缓存
            groovySkillExecutor.clearCache();
            
            result.put("success", true);
            result.put("message", skillName != null 
                ? "已清除 Skill 缓存: " + skillName 
                : "已清除所有 Skill 缓存");
            result.put("timestamp", System.currentTimeMillis());
            
            log.info("[SkillsAdmin] 缓存清除成功");
            
        } catch (Exception e) {
            log.error("[SkillsAdmin] 缓存清除失败", e);
            result.put("success", false);
            result.put("message", "缓存清除失败: " + e.getMessage());
            result.put("error", e.toString());
        }
        
        return result;
    }
    
    /**
     * 重新扫描所有 Skills
     * 
     * @return 操作结果
     */
    @PostMapping("/rescan")
    public Map<String, Object> rescan() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            log.info("[SkillsAdmin] 收到重新扫描请求");
            
            // TODO: 需要增强 GroovySkillExecutor 支持动态重新扫描
            // 当前仅清除缓存，下次执行时会重新加载
            
            groovySkillExecutor.clearCache();
            
            result.put("success", true);
            result.put("message", "已触发 Skills 重新扫描（缓存已清除，下次执行时生效）");
            result.put("timestamp", System.currentTimeMillis());
            
            log.info("[SkillsAdmin] 重新扫描完成");
            
        } catch (Exception e) {
            log.error("[SkillsAdmin] 重新扫描失败", e);
            result.put("success", false);
            result.put("message", "重新扫描失败: " + e.getMessage());
            result.put("error", e.toString());
        }
        
        return result;
    }
    
    /**
     * 获取已发现的 Skills 列表
     * 
     * @return Skills 信息
     */
    @GetMapping("/list")
    public Map<String, Object> listSkills() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            var skills = groovySkillExecutor.getDiscoveredSkills();
            
            result.put("success", true);
            result.put("count", skills.size());
            result.put("skills", skills);
            result.put("timestamp", System.currentTimeMillis());
            
        } catch (Exception e) {
            log.error("[SkillsAdmin] 获取 Skills 列表失败", e);
            result.put("success", false);
            result.put("message", "获取 Skills 列表失败: " + e.getMessage());
        }
        
        return result;
    }
}
