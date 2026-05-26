#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""更新 README Roadmap"""

import re

# 读取文件
with open('README.md', 'r', encoding='utf-8') as f:
    content = f.read()

# 定义三个部分的替换
replacements = [
    # 短期规划
    (
        r'### 近期规划 \(1-3 个月\) — 正在开发中\n\n- \[ \] \*\*SQL 执行沙箱\*\*.*?- \[ \] \*\*Milvus 向量数据库支持\*\*.*?\n',
        '''### 短期规划 (1-3 个月) — 高优先级

- [ ] **查询结果导出增强** — Excel/PDF/Word 多格式导出，支持自定义模板
- [ ] **SQL 执行计划可视化** — EXPLAIN 结果图形化展示，索引建议自动化
- [ ] **自然语言追问增强** — 基于对话历史的上下文关联追问（当前仅支持按钮式）
- [ ] **批量查询任务** — 一次性提交多个查询，异步执行 + 进度通知
- [ ] **定时报表推送** — 按日/周/月自动生成关键指标报表，邮件/企业微信推送

'''
    ),
    # 中期规划
    (
        r'### 中期规划 \(3-6 个月\) — 设计阶段\n\n- \[ \] \*\*查询结果可视化 Dashboard\*\*.*?- \[ \] \*\*Elasticsearch 向量检索支持\*\*.*?\n',
        '''### 中期规划 (3-6 个月) — 架构演进

- [ ] **Sub-Agent 专业化拆分** — 参考 [SUB_AGENT_ROADMAP.md](SUB_AGENT_ROADMAP.md)，演进为 Master Agent + 6 个专用 Sub-Agent（SQL生成/图表推荐/异常检测/知识管理/权限控制/性能优化）
- [ ] **跨源联邦查询** — 跨 MySQL/PostgreSQL/ClickHouse 的 JOIN 查询，自动下推优化
- [ ] **实时流式数据处理** — Kafka/Flink 集成，支持实时数据源的 NL2SQL 查询
- [ ] **多租户隔离架构** — 企业级 SaaS 方案，数据完全隔离，独立配置与资源配额
- [ ] **查询成本管控** — LLM Token 预算限制 + 慢查询熔断 + 并发限流

'''
    ),
    # 长期愿景
    (
        r'### 长期愿景 \(6-12 个月\) — 规划中\n\n- \[ \] \*\*自动化异常检测\*\*.*?- \[ \] \*\*Sub-Agent 架构演进\*\*.*?\n',
        '''### 长期愿景 (6-12 个月) — 生态建设

- [ ] **插件市场** — 第三方开发者可发布行业术语包/数据源适配器/图表插件
- [ ] **低代码工作流编排** — 可视化拖拽构建复杂查询流程（类似 Zapier）
- [ ] **AI 辅助建模** — 基于历史查询自动推荐数据模型优化方案（范式调整/物化视图）
- [ ] **移动端 App** — iOS/Android 原生应用，支持语音输入 + 离线缓存

'''
    )
]

# 执行替换
for pattern, replacement in replacements:
    content = re.sub(pattern, replacement, content, flags=re.DOTALL)

# 写入文件
with open('README.md', 'w', encoding='utf-8') as f:
    f.write(content)

print("✅ Roadmap 更新完成！")
