package com.nl2sql.core.agent.monitoring;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * Skill 执行监控切面
 * 
 * 功能：
 * - 自动拦截所有 Skills/SimpleDataQuerySkill 等类的 execute 方法
 * - 记录执行时间、成功/失败状态
 * - 将指标数据上报到 SkillMetricsRecorder
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class SkillMetricsAspect {
    
    private final SkillMetricsRecorder metricsRecorder;
    
    /**
     * 拦截所有 Skills 的 execute 方法
     * 匹配模式：com.nl2sql.core.agent.skills..*.execute(..)
     * 包括：
     * - StandardQuerySkill.execute()
     * - ReportWithInsightsSkill.execute()
     * - SimpleDataQuerySkill (Groovy)
     * - SQLValidateAndExecuteSkill (Groovy)
     * - SQLPerformanceAnalysisSkill (Groovy)
     */
    @Around("execution(* com.nl2sql.core.agent.skills..*.execute(..))")
    public Object recordSkillExecution(ProceedingJoinPoint joinPoint) throws Throwable {
        String skillName = extractSkillName(joinPoint);
        long startTime = System.currentTimeMillis();
        
        try {
            // 记录开始
            metricsRecorder.recordStart(skillName);
            
            // 执行目标方法
            Object result = joinPoint.proceed();
            
            // 记录成功
            long executionTime = System.currentTimeMillis() - startTime;
            metricsRecorder.recordSuccess(skillName, executionTime);
            
            return result;
            
        } catch (Exception e) {
            // 记录失败
            long executionTime = System.currentTimeMillis() - startTime;
            metricsRecorder.recordFailure(skillName, executionTime, e.getMessage());
            
            // 重新抛出异常
            throw e;
        }
    }
    
    /**
     * 从 JoinPoint 提取 Skill 名称
     * 
     * @param joinPoint AOP 连接点
     * @return Skill 名称（小写，去除 "Skill" 后缀）
     */
    private String extractSkillName(ProceedingJoinPoint joinPoint) {
        String className = joinPoint.getTarget().getClass().getSimpleName();
        // 移除 "Skill" 后缀并转为小写
        // 例如：StandardQuerySkill -> standard_query
        return className.replace("Skill", "").toLowerCase();
    }
}
