package com.nl2sql.core.agent.intent;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 意图分类器 - 自动识别用户意图类型
 *
 * 设计原则：
 * 1. 优先使用规则匹配（快速、准确）
 * 2. 规则未命中时降级到 LLM 分类（灵活但较慢）
 * 3. 返回结构化的 IntentClassification 对象
 */
@Slf4j
@Component
public class IntentClassifier {

    // 意图类型枚举
    public enum IntentType {
        QUERY,          // 数据查询
        SUMMARY,        // AI 总结
        CHART,          // 图表生成
        CLARIFY,        // 需要澄清
        COMPLEX,        // 复杂查询（多表 JOIN/多步聚合）
        EXPLORE,        // 数据探索（开放式、步骤不可预知）
        UNKNOWN         // 未知意图
    }

    @Data
    public static class IntentClassification {
        private IntentType type;
        private double confidence;  // 置信度 0-1
        private String reason;      // 分类原因
        private String originalQuery;

        public static IntentClassification of(IntentType type, double confidence, String reason, String query) {
            IntentClassification ic = new IntentClassification();
            ic.type = type;
            ic.confidence = confidence;
            ic.reason = reason;
            ic.originalQuery = query;
            return ic;
        }
    }

    // 意图识别规则（正则表达式）
    private static final Pattern SUMMARY_PATTERN = Pattern.compile(
        "(总结|概述|概括|归纳|分析).*"  // ✅ 以总结/分析类动词开头
    );

    private static final Pattern CHART_PATTERN = Pattern.compile(
        "(图表 | 图形 | 可视化 | 柱状图 | 折线图 | 饼图 | 展示 | 画图 | 绘图)" +
        "|(画 | 展示 | 生成).*(图 | 图表)"
    );

    private static final Pattern CLARIFY_PATTERN = Pattern.compile(
        "(哪些 | 什么 | 列表 | 列出 | 显示所有 | 查看所有).*(数据源 | 数据库 | 表)" +
        "|(数据源 | 数据库).*(选择 | 切换 | 更换)"
    );

    // COMPLEX 意图：多步聚合/深度分析
    private static final Pattern COMPLEX_PATTERN = Pattern.compile(
        "(分析 | 深度分析 | 多维度 | 交叉分析 | 对比 | 环比 | 同比 | 趋势 | 预测 | 归因).*?(数据 | 销售 | 订单 | 用户)" +
        "|(每个 | 各 | 分).*(维度 | 城市 | 地区 | 类别 | 产品 | 时间)"
    );

    // EXPLORE 意图：开放式数据探索，步骤不可预知
    private static final Pattern EXPLORE_PATTERN = Pattern.compile(
        "(探索 | 挖掘 | 发现).*(数据 | 规律 | 模式 | 异常 | 有意思 | 有趣)" +
        "|(数据).*(有什么 | 哪些 | 什么样).*(规律 | 模式 | 特点 | 趋势 | 异常)" +
        "|(帮我 | 帮我看看 | 帮我找找).*(异常 | 问题 | 亮点 | 有意思 | 值得关注)" +
        "|(不知道 | 不确定).*(查什么 | 想看 | 想了解)" +
        "|(随便 | 随意 | 自由).*(看看 | 浏览 | 翻翻)" +
        "|(有什么).*(异常 | 不对劲 | 特别 | 突出)"
    );

    /**
     * 分类用户意图
     *
     * @param userMessage 用户原始消息
     * @return 意图分类结果
     */
    public IntentClassification classify(String userMessage) {
        if (userMessage == null || userMessage.trim().isEmpty()) {
            return IntentClassification.of(
                IntentType.UNKNOWN,
                0.0,
                "消息为空",
                userMessage
            );
        }

        String normalizedMessage = userMessage.toLowerCase().trim();
        log.info("[IntentClassifier] 原始消息: {}, 归一化后: {}", userMessage, normalizedMessage);

        // ✅ 步骤 1: 检查显式标记（前端传入的 [INTENT:xxx]）
        IntentType explicitIntent = detectExplicitIntent(userMessage);
        if (explicitIntent != null) {
            log.debug("[IntentClassifier] 检测到显式意图标记：{}", explicitIntent);
            return IntentClassification.of(
                explicitIntent,
                1.0,
                "显式标记",
                userMessage
            );
        }

        // ✅ 步骤 2: 规则匹配
        IntentClassification ruleResult = matchByRules(normalizedMessage);
        if (ruleResult.getConfidence() >= 0.8) {
            log.info("[IntentClassifier] 规则匹配成功：{} (confidence={}), message={}",
                ruleResult.getType(), ruleResult.getConfidence(), userMessage);
            return ruleResult;
        }

        // ✅ 步骤 3: 默认意图为 QUERY
        log.info("[IntentClassifier] 规则未命中，默认为 QUERY 意图, message={}", userMessage);
        return IntentClassification.of(
            IntentType.QUERY,
            0.6,
            "规则未命中，默认查询意图",
            userMessage
        );
    }

    /**
     * 检测显式意图标记
     */
    private IntentType detectExplicitIntent(String message) {
        if (message.contains("[INTENT:AI_SUMMARY]") || message.contains("[INTENT:SUMMARY]")) {
            return IntentType.SUMMARY;
        }
        if (message.contains("[INTENT:GENERATE_CHART]") || message.contains("[INTENT:CHART]")) {
            return IntentType.CHART;
        }
        if (message.contains("[INTENT:CLARIFY]") || message.contains("[INTENT:DATASOURCE]")) {
            return IntentType.CLARIFY;
        }
        if (message.contains("[INTENT:COMPLEX]")) {
            return IntentType.COMPLEX;
        }
        if (message.contains("[INTENT:EXPLORE]")) {
            return IntentType.EXPLORE;
        }
        if (message.contains("[INTENT:QUERY]")) {
            return IntentType.QUERY;
        }
        return null;
    }

    /**
     * 基于规则匹配意图
     */
    private IntentClassification matchByRules(String message) {
        // ✅ 检查是否为总结意图（优先于 COMPLEX，因为"总结+趋势"应走 AI 分析）
        if (SUMMARY_PATTERN.matcher(message).find()) {
            log.info("[IntentClassifier] SUMMARY_PATTERN 匹配成功: {}", message);
            return IntentClassification.of(
                IntentType.SUMMARY,
                0.9,
                "匹配总结关键词",
                message
            );
        }
        log.debug("[IntentClassifier] SUMMARY_PATTERN 未匹配: {}", message);

        // 检查是否为 EXPLORE 意图（优先于 COMPLEX，开放式探索应走 ReAct）
        if (EXPLORE_PATTERN.matcher(message).find()) {
            return IntentClassification.of(
                IntentType.EXPLORE,
                0.85,
                "匹配数据探索关键词",
                message
            );
        }

        // 检查是否为 COMPLEX 意图（多表 JOIN/多维度，但不含"总结"）
        if (COMPLEX_PATTERN.matcher(message).find()) {
            return IntentClassification.of(
                IntentType.COMPLEX,
                0.85,
                "匹配复杂分析关键词",
                message
            );
        }

        // 检查是否为图表意图
        if (CHART_PATTERN.matcher(message).find()) {
            return IntentClassification.of(
                IntentType.CHART,
                0.9,
                "匹配图表关键词",
                message
            );
        }

        // 检查是否为澄清意图
        if (CLARIFY_PATTERN.matcher(message).find()) {
            return IntentClassification.of(
                IntentType.CLARIFY,
                0.85,
                "匹配澄清关键词",
                message
            );
        }

        // 未匹配任何规则
        return IntentClassification.of(
            IntentType.UNKNOWN,
            0.3,
            "未匹配任何规则",
            message
        );
    }
}
