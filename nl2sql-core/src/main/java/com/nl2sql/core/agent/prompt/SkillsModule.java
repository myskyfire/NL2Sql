package com.nl2sql.core.agent.prompt;

import com.nl2sql.core.agent.skills.SkillsMetadataLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Skills 元数据模块 - 动态注入可用的高级技能
 * 
 * 职责：从 SKILL.md 文件加载并生成 Skills 说明
 * Token 估算：~300-500 tokens（取决于 Skill 数量）
 */
@Slf4j
@Component
public class SkillsModule implements PromptModule {
    
    private final SkillsMetadataLoader skillsMetadataLoader;
    
    public SkillsModule(SkillsMetadataLoader skillsMetadataLoader) {
        this.skillsMetadataLoader = skillsMetadataLoader;
    }
    
    @Override
    public String build() {
        if (skillsMetadataLoader == null) {
            log.warn("[SkillsModule] SkillsMetadataLoader 未配置，返回空提示词");
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("## 可用的高级技能（Skills）\n\n");
        sb.append("你可以调用以下高级技能来完成复杂任务。**优先使用 Skills**，而非手动组合底层 Tools！\n\n");
        
        // 调用 SkillsMetadataLoader 生成描述
        sb.append(skillsMetadataLoader.generateSkillsDescription());
        sb.append("\n");
        
        return sb.toString();
    }
    
    @Override
    public int estimateTokens() {
        if (skillsMetadataLoader == null) {
            return 0;
        }
        // 粗略估算：每个 Skill 约 150-250 tokens
        int skillCount = skillsMetadataLoader.getAllSkills().size();
        return skillCount * 200;
    }
}
