package com.nl2sql.web.controller;

import com.nl2sql.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 查询模板控制器
 * TODO: 单人项目优先级较低,当前仅返回空列表避免报错
 * 后续可根据实际需求实现完整的模板管理功能
 */
@Slf4j
@RestController
@RequestMapping("/api/template")
public class TemplateController {
    
    /**
     * 获取我的模板
     */
    @GetMapping("/my")
    public Result<List<Map<String, Object>>> getMyTemplates() {
        log.debug("查询我的模板 - 功能待实现");
        // TODO: 实现从数据库查询用户个人模板
        return Result.success(new ArrayList<>());
    }
    
    /**
     * 获取公共模板
     */
    @GetMapping("/public")
    public Result<List<Map<String, Object>>> getPublicTemplates() {
        log.debug("查询公共模板 - 功能待实现");
        // TODO: 实现从数据库查询公共模板
        return Result.success(new ArrayList<>());
    }
    
    /**
     * 保存模板
     */
    @PostMapping("/save")
    public Result<Map<String, Object>> saveTemplate(@RequestBody Map<String, Object> template) {
        log.debug("保存模板 - 功能待实现, 参数: {}", template);
        // TODO: 实现模板保存逻辑
        Map<String, Object> result = new HashMap<>();
        result.put("id", "TODO");
        return Result.success(result);
    }
    
    /**
     * 删除模板
     */
    @DeleteMapping("/{id}")
    public Result<Void> deleteTemplate(@PathVariable String id) {
        log.debug("删除模板 - 功能待实现, ID: {}", id);
        // TODO: 实现模板删除逻辑
        return Result.success(null);
    }
}
