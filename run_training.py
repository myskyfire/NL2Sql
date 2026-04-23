import requests
import random
import json
import re
import mysql.connector
import sys
from datetime import datetime
from concurrent.futures import ThreadPoolExecutor, as_completed

# ✅ 关键修复：设置标准输出编码为UTF-8
if sys.stdout.encoding != 'utf-8':
    sys.stdout.reconfigure(encoding='utf-8')
if sys.stderr.encoding != 'utf-8':
    sys.stderr.reconfigure(encoding='utf-8')

TOKEN = "f4bef8749a264cbcad59b2b0e634cb3f"
API_URL = "http://localhost:8080/api/agent/chat/test"
FEEDBACK_URL = "http://localhost:8080/api/feedback/submit"

# 数据库配置（用于执行验证）
DB_CONFIG = {
    'host': 'localhost',
    'port': 33061,
    'user': 'root',
    'password': '123456',
    'database': 'trade'
}

# 读取测试用例
test_cases = []
with open('training_test_cases.txt', 'r', encoding='utf-8-sig') as f:  # ✅ utf-8-sig自动处理BOM
    for line in f:
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        parts = line.split('|')
        if len(parts) >= 4:
            test_cases.append({
                'question': parts[0],
                'expected_sql': parts[1],
                'tables': [x.strip() for x in parts[2].split(',')],
                'functions': [x.strip() for x in parts[3].split(',') if x.strip()],
                'datasource_id': int(parts[4]) if len(parts) > 4 and parts[4].strip().isdigit() else 1
            })

print(f"Loaded {len(test_cases)} test cases")

# SQL规范化函数
def normalize_sql(sql):
    """规范化SQL用于比较"""
    if not sql:
        return ""
    # 转小写
    sql = sql.lower().strip()
    # 移除多余空格
    sql = re.sub(r'\s+', ' ', sql)
    # 移除末尾分号
    sql = sql.rstrip(';').strip()
    return sql

def execute_sql(sql, datasource_id=1):
    """执行SQL并返回结果集（简化版，实际需要查询数据源配置）"""
    try:
        # TODO: 根据datasource_id获取实际数据库连接
        conn = mysql.connector.connect(**DB_CONFIG)
        cursor = conn.cursor(dictionary=True)
        cursor.execute(sql)
        
        if cursor.description:
            results = cursor.fetchall()
            # 排序以确保一致性
            if results:
                sorted_keys = sorted(results[0].keys())
                results.sort(key=lambda x: tuple(str(x.get(k, '')) for k in sorted_keys))
            cursor.close()
            conn.close()
            return True, results
        else:
            cursor.close()
            conn.close()
            return True, []
    except Exception as e:
        return False, str(e)

def extract_sql_components(sql):
    """
    提取SQL组件并转为集合（Spider Exact Set Match算法）
    
    返回字典：{
        'select': set of (agg_func, column) tuples,
        'where': set of conditions,
        'group_by': set of columns,
        'order_by': set of (column, direction) tuples,
        'having': set of conditions,
        'limit': int or None,
        'tables': set of table names,
        'joins': set of join conditions
    }
    """
    sql_norm = normalize_sql(sql)
    components = {}
    
    # 1. 提取SELECT组件（聚合函数+列名）
    select_match = re.search(r'select\s+(.*?)\s+from', sql_norm, re.DOTALL)
    if select_match:
        select_clause = select_match.group(1)
        # 提取聚合函数和列
        agg_pattern = r'(count|sum|avg|max|min)\s*\(([^)]+)\)'
        aggs = re.findall(agg_pattern, select_clause)
        # 提取普通列
        cols = re.findall(r'(?<!\w)([a-z_]\w*)(?!\s*\()', select_clause)
        
        select_set = set()
        for func, col in aggs:
            select_set.add((func, col.strip()))
        for col in cols:
            if col not in ['distinct', 'as']:
                select_set.add(('none', col))
        components['select'] = select_set
    
    # 2. 提取FROM表名
    from_match = re.search(r'from\s+([a-z_]\w*)', sql_norm)
    if from_match:
        components['tables'] = {from_match.group(1)}
    
    # 3. 提取JOIN条件
    joins = re.findall(r'join\s+([a-z_]\w*)\s+on\s+([^\s]+)\s*=\s*([^\s]+)', sql_norm)
    if joins:
        components['joins'] = set((table, f"{left}={right}") for table, left, right in joins)
    
    # 4. 提取WHERE条件（转为集合，忽略顺序）
    where_match = re.search(r'where\s+(.*?)(?:group|order|having|limit|$)', sql_norm, re.DOTALL)
    if where_match:
        where_clause = where_match.group(1).strip()
        # 简单分割AND条件
        conditions = re.split(r'\s+and\s+', where_clause)
        components['where'] = set(c.strip() for c in conditions if c.strip())
    
    # 5. 提取GROUP BY
    group_match = re.search(r'group\s+by\s+(.*?)(?:order|having|limit|$)', sql_norm, re.DOTALL)
    if group_match:
        cols = [c.strip() for c in group_match.group(1).split(',')]
        components['group_by'] = set(cols)
    
    # 6. 提取ORDER BY
    order_match = re.search(r'order\s+by\s+(.*?)(?:limit|$)', sql_norm, re.DOTALL)
    if order_match:
        order_clause = order_match.group(1).strip()
        # 提取列和方向
        order_parts = re.findall(r'([a-z_]\w*)\s*(asc|desc)?', order_clause)
        components['order_by'] = set((col, dir if dir else 'asc') for col, dir in order_parts)
    
    # 7. 提取HAVING
    having_match = re.search(r'having\s+(.*?)(?:order|limit|$)', sql_norm, re.DOTALL)
    if having_match:
        components['having'] = {having_match.group(1).strip()}
    
    # 8. 提取LIMIT
    limit_match = re.search(r'limit\s+(\d+)', sql_norm)
    if limit_match:
        components['limit'] = int(limit_match.group(1))
    
    return components

def calculate_exact_set_match(expected_sql, actual_sql):
    """
    Spider Exact Set Match算法（优化版）
    
    调整权重策略：
    1. SELECT显式字段 vs SELECT * → 不扣分（显式字段更好）
    2. LIMIT有无 → 不扣分（加LIMIT是合理的安全措施）
    3. = vs LIKE → 轻微扣分0.3（都能查到数据）
    4. 表JOIN错误/字段语义错误 → 严格扣分
    
    返回: (overall_score, component_scores_dict)
    """
    exp_comp = extract_sql_components(expected_sql)
    act_comp = extract_sql_components(actual_sql)
    
    component_scores = {}
    
    # SELECT子句特殊处理：显式字段列表视为与SELECT *等价
    exp_select = exp_comp.get('select', set())
    act_select = act_comp.get('select', set())
    
    if not exp_select and not act_select:
        component_scores['select'] = 1.0
    elif not exp_select or not act_select:
        component_scores['select'] = 0.0
    else:
        # 检查是否是 SELECT * vs 显式字段的差异
        exp_has_star = any('*' in str(s) for s in exp_select)
        act_has_star = any('*' in str(s) for s in act_select)
        
        if exp_has_star != act_has_star:
            # 一个用*，一个用显式字段 → 不扣分，认为等价
            component_scores['select'] = 1.0
        else:
            # 都是显式字段或都用*，正常比较
            intersection = exp_select & act_select
            union = exp_select | act_select
            component_scores['select'] = len(intersection) / len(union) if union else 1.0
    
    # WHERE条件特殊处理：= vs LIKE 轻微扣分
    exp_where = exp_comp.get('where', set())
    act_where = act_comp.get('where', set())
    
    if not exp_where and not act_where:
        component_scores['where'] = 1.0
    elif not exp_where or not act_where:
        component_scores['where'] = 0.0
    else:
        # 检查是否只是 = vs LIKE 的差异
        exp_str = ' '.join(str(w) for w in exp_where)
        act_str = ' '.join(str(w) for w in act_where)
        
        # 如果核心条件相同，只是操作符不同（= vs LIKE）
        if exp_str.replace('=', '').replace('LIKE', '').strip() == act_str.replace('=', '').replace('LIKE', '').strip():
            component_scores['where'] = 0.7  # 轻微扣分
        else:
            intersection = exp_where & act_where
            union = exp_where | act_where
            component_scores['where'] = len(intersection) / len(union) if union else 1.0
    
    # 其他组件正常比较
    for comp_name in ['group_by', 'order_by', 'having', 'tables', 'joins']:
        exp_set = exp_comp.get(comp_name, set())
        act_set = act_comp.get(comp_name, set())
        
        if not exp_set and not act_set:
            component_scores[comp_name] = 1.0
        elif not exp_set or not act_set:
            component_scores[comp_name] = 0.0
        else:
            intersection = exp_set & act_set
            union = exp_set | act_set
            component_scores[comp_name] = len(intersection) / len(union) if union else 1.0
    
    # LIMIT特殊处理：有无LIMIT不扣分
    if 'limit' in exp_comp and 'limit' in act_comp:
        component_scores['limit'] = 1.0  # 都有LIMIT，不比较具体值
    elif 'limit' not in exp_comp and 'limit' not in act_comp:
        component_scores['limit'] = 1.0
    else:
        component_scores['limit'] = 1.0  # 一个有一个没有，也不扣分
    
    # 计算平均分
    overall_score = sum(component_scores.values()) / len(component_scores) if component_scores else 0.0
    
    return overall_score, component_scores

def generate_llm_friendly_reason(score, detailed_scores, strengths, issues, component_details=None):
    """
    生成LLM可理解的评分原因
    
    要求：
    1. 清晰说明哪里对了、哪里错了
    2. 给出具体的改进建议
    3. 使用自然语言，避免技术术语堆砌
    4. 低分必须详细说明错误原因
    5. 高分也要说明优点（强化学习）
    """
    reason_parts = []
    
    # 总体评价
    if score == 5:
        reason_parts.append("【优秀】SQL完全正确")
    elif score == 4:
        reason_parts.append("【良好】SQL基本正确，有小瑕疵")
    elif score == 3:
        reason_parts.append("【中等】SQL可用但存在明显问题")
    elif score == 2:
        reason_parts.append("【较差】SQL有严重错误")
    else:
        reason_parts.append("【错误】SQL完全不可用")
    
    # 详细分析各维度
    ex_score = detailed_scores['execution_accuracy']
    esm_score = detailed_scores['exact_set_match']
    tc_score = detailed_scores['table_coverage']
    fc_score = detailed_scores['function_coverage']
    
    # 执行准确率
    if ex_score == 1.0:
        reason_parts.append("✓ SQL可以正常执行并返回结果")
    elif ex_score == 0.0:
        reason_parts.append("✗ SQL执行失败，请检查语法错误")
    
    # Exact Set Match（结构匹配）
    if esm_score >= 0.9:
        reason_parts.append("✓ SQL结构与预期高度一致")
    elif esm_score >= 0.6:
        reason_parts.append(f"⚠ SQL结构部分匹配({esm_score:.0%})，需要调整")
        if component_details:
            for comp, comp_score in component_details.items():
                if comp_score < 0.5:
                    comp_name_cn = {
                        'select': 'SELECT子句',
                        'where': 'WHERE条件',
                        'group_by': 'GROUP BY分组',
                        'order_by': 'ORDER BY排序',
                        'having': 'HAVING过滤',
                        'tables': '表名',
                        'joins': 'JOIN连接'
                    }.get(comp, comp)
                    reason_parts.append(f"  - {comp_name_cn}不匹配，请检查该部分的列名和条件")
    else:
        reason_parts.append(f"✗ SQL结构与预期差异较大({esm_score:.0%})")
        reason_parts.append("  建议：仔细分析问题需求，确保使用了正确的表和字段")
    
    # 表覆盖
    if tc_score < 1.0:
        reason_parts.append("✗ 缺少必要的表，导致查询结果不完整")
        reason_parts.append("  建议：检查是否需要JOIN其他表来获取所需数据")
    
    # 函数覆盖
    if fc_score < 1.0:
        reason_parts.append("⚠ 缺少关键的SQL函数或子句（如COUNT、GROUP BY等）")
        reason_parts.append("  建议：根据问题类型选择合适的聚合函数和分组方式")
    
    # 添加具体优点（用于强化学习）
    if strengths and score >= 4:
        reason_parts.append("\n优点：")
        for s in strengths[:2]:
            reason_parts.append(f"  {s}")
    
    return " | ".join(reason_parts)

def score_sql_generation(tc, actual_sql, execution_results=None):
    """
    NL2SQL评分标准（基于Spider/BIRD标准）
    
    评分维度：
    1. Execution Accuracy (EX) - 执行准确率（权重40%）
    2. Component Match (CM) - 组件匹配度（权重30%）
    3. Table Coverage (TC) - 表覆盖率（权重20%）
    4. Function Coverage (FC) - 函数覆盖率（权重10%）
    
    最终得分：加权平均分 * 5（转换为1-5星）
    每个分数必须有明确原因
    """
    scores = {}
    issues = []
    strengths = []  # 记录优点
    
    # 1. 表覆盖率（20%）
    actual_lower = actual_sql.lower()
    missing_tables = [t for t in tc['tables'] if t.lower() not in actual_lower]
    extra_tables = []  # 可选：检查是否有多余的表
    
    table_score = 1.0 if not missing_tables else max(0, 1.0 - len(missing_tables) * 0.5)
    scores['table_coverage'] = table_score
    
    if missing_tables:
        issues.append(f"❌ 缺少表: {', '.join(missing_tables)}")
    else:
        strengths.append(f"✓ 表覆盖完整: {', '.join(tc['tables'])}")
    
    # 2. 函数覆盖率（10%）
    func_checks = {
        'JOIN': r'join',
        'COUNT': r'count\s*\(',
        'SUM': r'sum\s*\(',
        'AVG': r'avg\s*\(',
        'MAX': r'max\s*\(',
        'MIN': r'min\s*\(',
        'GROUP BY': r'group\s+by',
        'WHERE': r'where',
        'ORDER BY': r'order\s+by',
        'HAVING': r'having',
        'DISTINCT': r'distinct',
        'LIMIT': r'limit',
        'LEFT JOIN': r'left\s+join',
        'RIGHT JOIN': r'right\s+join'
    }
    
    missing_funcs = []
    matched_funcs = []
    for f in tc['functions']:
        if f in func_checks:
            if re.search(func_checks[f], actual_lower):
                matched_funcs.append(f)
            else:
                missing_funcs.append(f)
    
    func_score = 1.0 if not missing_funcs else max(0, 1.0 - len(missing_funcs) * 0.3)
    scores['function_coverage'] = func_score
    
    if missing_funcs:
        issues.append(f"❌ 缺少函数/子句: {', '.join(missing_funcs)}")
    if matched_funcs:
        strengths.append(f"✓ 正确使用: {', '.join(matched_funcs)}")
    
    # 3. Exact Set Match（Spider标准算法）（30%）
    exact_set_score, component_details = calculate_exact_set_match(tc['expected_sql'], actual_sql)
    scores['exact_set_match'] = exact_set_score
    
    # 4. 执行准确率（40%）- 如果提供了执行结果
    exec_score = 0.5  # 默认中等分数（未验证）
    if execution_results:
        exec_success, exec_data = execution_results
        if exec_success:
            exec_score = 1.0
            strengths.append("✓ SQL可执行且返回结果")
        else:
            exec_score = 0.0
            issues.append(f"❌ SQL执行失败: {str(exec_data)[:100]}")
    else:
        issues.append("⚠️ 未执行验证（仅静态分析）")
    scores['execution_accuracy'] = exec_score
    
    # 计算加权总分
    weighted_score = (
        scores['execution_accuracy'] * 0.4 +
        scores['exact_set_match'] * 0.3 +
        scores['table_coverage'] * 0.2 +
        scores['function_coverage'] * 0.1
    )
    
    # 转换为1-5星（四舍五入）
    final_score = max(1, min(5, round(weighted_score * 5)))
    
    # 根据分数生成详细原因（LLM友好格式）
    detailed_reason = generate_llm_friendly_reason(
        final_score, 
        scores, 
        strengths, 
        issues,
        component_details if 'component_details' in dir() else None
    )
    
    return final_score, scores, detailed_reason

# 执行10轮训练
MAX_WORKERS = 2  # 并行度

for round_num in range(1, 11):
    print(f"\n{'='*50}")
    print(f"Round {round_num}/10")
    print('='*50)
    
    # 第1轮全量测试，后续随机30个
    if round_num == 1:
        selected = test_cases  # 第一轮跑完所有测试用例
        print(f"Full test: {len(selected)} cases")
    else:
        sample_size = min(30, len(test_cases))
        selected = random.sample(test_cases, sample_size)
        print(f"Random sample: {len(selected)} cases")
    
    round_results = []
    
    # 并行执行测试
    def run_single_test(tc):
        """执行单个测试用例"""
        try:
            resp = requests.post(
                API_URL,
                json={'message': tc['question'], 'datasourceId': 1},
                headers={'Authorization': f'Bearer {TOKEN}'},
                timeout=180
            )
            
            if resp.status_code != 200:
                return {
                    'Question': tc['question'],
                    'ExpectedSQL': tc['expected_sql'],
                    'ActualSQL': 'API Error',
                    'Score': 1,
                    'Issues': f'HTTP {resp.status_code}'
                }
            
            result = resp.json()
            if result.get('code') != 200 or not result.get('data', {}).get('sql'):
                return {
                    'Question': tc['question'],
                    'ExpectedSQL': tc['expected_sql'],
                    'ActualSQL': 'Invalid Response',
                    'Score': 1,
                    'Issues': 'No SQL generated'
                }
            
            actual_sql = result['data']['sql']
            
            # 尝试执行SQL验证（可选）
            execution_results = None
            try:
                ds_id = tc.get('datasource_id', 1)
                execution_results = execute_sql(actual_sql, ds_id)
            except Exception as e:
                # ✅ 修复：执行异常说明SQL有严重语法错误，应该标记为执行失败
                # 而不是忽略异常给默认分0.5
                execution_results = (False, f"执行异常: {str(e)[:100]}")
            
            # 使用新的评分标准
            score, detailed_scores, detailed_reason = score_sql_generation(tc, actual_sql, execution_results)
            
            # 提交反馈（包含详细评分）
            feedback_details = f"EX:{detailed_scores['execution_accuracy']:.2f} ESM:{detailed_scores['exact_set_match']:.2f} TC:{detailed_scores['table_coverage']:.2f} FC:{detailed_scores['function_coverage']:.2f}"
            feedback_text = f"Round {round_num}: Score={score}/5 | {detailed_reason} [{feedback_details}]"
            
            try:
                requests.post(
                    FEEDBACK_URL,
                    json={
                        'question': tc['question'],
                        'generatedSql': actual_sql,
                        'rating': score,
                        'feedbackText': feedback_text
                    },
                    headers={'Authorization': f'Bearer {TOKEN}'},
                    timeout=10
                )
            except:
                pass
            
            return {
                'Question': tc['question'],
                'ExpectedSQL': tc['expected_sql'],
                'ActualSQL': actual_sql,
                'Score': score,
                'Reason': detailed_reason,
                'Details': feedback_details
            }
            
        except Exception as e:
            return {
                'Question': tc['question'],
                'ExpectedSQL': tc['expected_sql'],
                'ActualSQL': f'Exception: {str(e)[:100]}',
                'Score': 1,
                'Issues': str(e)
            }
    
    # 使用线程池并行执行
    with ThreadPoolExecutor(max_workers=MAX_WORKERS) as executor:
        future_to_tc = {executor.submit(run_single_test, tc): idx for idx, tc in enumerate(selected)}
        
        for future in as_completed(future_to_tc):
            idx = future_to_tc[future]
            tc = selected[idx]
            
            try:
                result = future.result()
                round_results.append(result)
                
                score = result['Score']
                reason = result.get('Reason', '')
                
                # 根据分数显示不同图标
                if score >= 4:
                    print(f"[{idx+1}/{len(selected)}] ⭐{score} {tc['question']}")
                    if reason:
                        print(f"         {reason}")
                elif score == 3:
                    print(f"[{idx+1}/{len(selected)}] ⭐{score} {tc['question']}")
                    if reason:
                        print(f"         {reason}")
                else:
                    print(f"[{idx+1}/{len(selected)}] ⭐{score} {tc['question']}")
                    if reason:
                        print(f"         {reason}")
            except Exception as e:
                print(f"[{idx+1}/10] {tc['question']} ✗ Failed")
                round_results.append({
                    'Question': tc['question'],
                    'ExpectedSQL': tc['expected_sql'],
                    'ActualSQL': 'Failed',
                    'Score': 1,
                    'Reason': f'Exception: {str(e)[:100]}',
                    'Details': ''
                })
    
    # 保存报告
    avg_score = sum(r['Score'] for r in round_results) / len(round_results)
    
    report = f"# NL2SQL Training - Round {round_num}\n\n"
    report += f"**Date**: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n"
    report += f"**Test Cases**: {len(round_results)}\n"
    report += f"**Average Score**: {avg_score:.2f} / 5.0\n\n"
    report += "## Scoring Breakdown\n"
    report += "- **EX** (Execution Accuracy): 40% weight\n"
    report += "- **CM** (Component Match): 30% weight\n"
    report += "- **TC** (Table Coverage): 20% weight\n"
    report += "- **FC** (Function Coverage): 10% weight\n\n"
    report += "| # | Question | Score | Reason | Details |\n"
    report += "|---|----------|-------|--------|---------|\n"
    
    for j, r in enumerate(round_results, 1):
        details = r.get('Details', '')
        reason = r.get('Reason', 'N/A')
        report += f"| {j} | {r['Question']} | {r['Score']}⭐ | {reason} | {details} |\n"
    
    with open(f'training_round_{round_num}.md', 'w', encoding='utf-8') as f:  # ✅ 明确指定UTF-8编码
        f.write(report)
    
    print(f"\nRound {round_num} saved (Avg: {avg_score:.2f})")

print("\n" + "="*50)
print("All 10 Rounds Complete!")
print("Reports: training_round_1.md ~ training_round_10.md")
print("="*50)
