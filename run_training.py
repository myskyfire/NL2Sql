import requests
import random
import json
import re
import mysql.connector
import sys
import os
import time
from datetime import datetime
from concurrent.futures import ThreadPoolExecutor, as_completed
from collections import defaultdict

if sys.stdout.encoding != 'utf-8':
    sys.stdout.reconfigure(encoding='utf-8')
if sys.stderr.encoding != 'utf-8':
    sys.stderr.reconfigure(encoding='utf-8')

TOKEN = os.environ.get("NL2SQL_TOKEN", "0dc3e6ef912746ecade2864a49446e92")
API_URL = os.environ.get("NL2SQL_API_URL", "http://localhost:8080/api/agent/chat/test")
FEEDBACK_URL = os.environ.get("NL2SQL_FEEDBACK_URL", "http://localhost:8080/api/feedback/submit")

DB_CONFIG = {
    'host': os.environ.get("NL2SQL_DB_HOST", "localhost"),
    'port': int(os.environ.get("NL2SQL_DB_PORT", "33061")),
    'user': os.environ.get("NL2SQL_DB_USER", "root"),
    'password': os.environ.get("NL2SQL_DB_PASSWORD", "123456"),
    'database': os.environ.get("NL2SQL_DB_NAME", "trade")
}

MAX_ROUNDS = int(os.environ.get("NL2SQL_MAX_ROUNDS", "5"))
MAX_WORKERS = int(os.environ.get("NL2SQL_MAX_WORKERS", "2"))
REQUEST_TIMEOUT = int(os.environ.get("NL2SQL_TIMEOUT", "180"))
EARLY_STOP_PATIENCE = int(os.environ.get("NL2SQL_EARLY_STOP", "2"))
PASS_SCORE = 4

db_connection_pool = []


def get_db_connection():
    global db_connection_pool
    if db_connection_pool:
        conn = db_connection_pool.pop()
        try:
            conn.ping(reconnect=True)
            return conn
        except:
            pass
    try:
        conn = mysql.connector.connect(**DB_CONFIG)
        return conn
    except Exception as e:
        print(f"  [DB] connection failed: {e}")
        return None


def return_db_connection(conn):
    global db_connection_pool
    if conn and len(db_connection_pool) < 5:
        try:
            conn.ping(reconnect=True)
            db_connection_pool.append(conn)
        except:
            try:
                conn.close()
            except:
                pass
    elif conn:
        try:
            conn.close()
        except:
            pass


def close_all_connections():
    global db_connection_pool
    for conn in db_connection_pool:
        try:
            conn.close()
        except:
            pass
    db_connection_pool.clear()


test_cases = []
with open('training_test_cases.txt', 'r', encoding='utf-8-sig') as f:
    for line in f:
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        parts = line.split('|')
        if len(parts) >= 4:
            difficulty = parts[4].strip() if len(parts) > 4 else 'MEDIUM'
            if difficulty not in ('EASY', 'MEDIUM', 'HARD'):
                difficulty = 'MEDIUM'
            datasource_id = 1
            if len(parts) > 5 and parts[5].strip().isdigit():
                datasource_id = int(parts[5].strip())
            test_cases.append({
                'question': parts[0],
                'expected_sql': parts[1],
                'tables': [x.strip() for x in parts[2].split(',')],
                'functions': [x.strip() for x in parts[3].split(',') if x.strip()],
                'difficulty': difficulty,
                'datasource_id': datasource_id
            })

print(f"Loaded {len(test_cases)} test cases")
diff_counts = defaultdict(int)
for tc in test_cases:
    diff_counts[tc['difficulty']] += 1
for d in ['EASY', 'MEDIUM', 'HARD']:
    print(f"  {d}: {diff_counts.get(d, 0)}")


def normalize_sql(sql):
    if not sql:
        return ""
    sql = sql.lower().strip()
    sql = re.sub(r'\s+', ' ', sql)
    sql = re.sub(r'\s*\(\s*', ' ( ', sql)
    sql = re.sub(r'\s*\)\s*', ' ) ', sql)
    sql = sql.rstrip(';').strip()
    return sql


def normalize_row(row):
    if isinstance(row, dict):
        return tuple(sorted((k.lower(), _normalize_val(v)) for k, v in row.items()))
    elif isinstance(row, (list, tuple)):
        return tuple(_normalize_val(v) for v in row)
    return (_normalize_val(row),)


def _normalize_val(v):
    if v is None:
        return None
    if isinstance(v, float):
        return round(v, 2)
    if isinstance(v, datetime):
        return v.strftime('%Y-%m-%d %H:%M:%S')
    if isinstance(v, bytes):
        return v.decode('utf-8', errors='replace')
    return v


def compare_result_sets(expected_rows, actual_rows):
    if expected_rows is None and actual_rows is None:
        return 1.0, "both empty"
    if expected_rows is None or actual_rows is None:
        return 0.0, "one side empty"

    exp_count = len(expected_rows)
    act_count = len(actual_rows)

    if exp_count == 0 and act_count == 0:
        return 1.0, "both 0 rows"
    if exp_count == 0 or act_count == 0:
        return 0.0, f"row count mismatch: expected {exp_count}, got {act_count}"

    exp_cols = set()
    act_cols = set()
    if expected_rows and isinstance(expected_rows[0], dict):
        exp_cols = {k.lower() for k in expected_rows[0].keys()}
    if actual_rows and isinstance(actual_rows[0], dict):
        act_cols = {k.lower() for k in actual_rows[0].keys()}

    col_overlap = len(exp_cols & act_cols) / max(len(exp_cols | act_cols), 1)

    exp_normalized = set(normalize_row(r) for r in expected_rows)
    act_normalized = set(normalize_row(r) for r in actual_rows)

    if exp_count <= 20 and act_count <= 20:
        exp_list = sorted(exp_normalized)
        act_list = sorted(act_normalized)
        if exp_list == act_list:
            return 1.0, "exact match"
        row_match = len(exp_normalized & act_normalized) / max(len(exp_normalized | act_normalized), 1)
        return row_match, f"partial match ({row_match:.0%} rows overlap)"

    row_match = len(exp_normalized & act_normalized) / max(len(exp_normalized), 1)

    count_ratio = min(exp_count, act_count) / max(exp_count, act_count)
    combined = row_match * 0.7 + count_ratio * 0.2 + col_overlap * 0.1

    return combined, f"set match: {row_match:.0%}, count: {count_ratio:.0%}, cols: {col_overlap:.0%}"


def execute_sql(sql, datasource_id=1):
    conn = None
    try:
        conn = get_db_connection()
        if conn is None:
            return False, "DB connection failed"
        cursor = conn.cursor(dictionary=True)
        cursor.execute(sql)
        if cursor.description:
            results = cursor.fetchall()
            cursor.close()
            return_db_connection(conn)
            return True, results
        else:
            cursor.close()
            return_db_connection(conn)
            return True, []
    except Exception as e:
        if conn:
            try:
                conn.rollback()
            except:
                pass
            return_db_connection(conn)
        return False, str(e)[:200]


def tokenize_sql(sql):
    tokens = []
    current = ''
    in_string = False
    string_char = None
    paren_depth = 0

    for ch in sql:
        if in_string:
            current += ch
            if ch == string_char:
                in_string = False
            continue

        if ch in ("'", '"'):
            in_string = True
            string_char = ch
            current += ch
            continue

        if ch == '(':
            paren_depth += 1
            if current.strip():
                tokens.append(current.strip())
            tokens.append('(')
            current = ''
            continue

        if ch == ')':
            if current.strip():
                tokens.append(current.strip())
            tokens.append(')')
            current = ''
            paren_depth -= 1
            continue

        if ch in (' ', '\t', '\n', '\r', ','):
            if current.strip():
                tokens.append(current.strip())
            current = ''
            continue

        current += ch

    if current.strip():
        tokens.append(current.strip())

    return tokens


def find_matching_paren(tokens, start):
    depth = 0
    for i in range(start, len(tokens)):
        if tokens[i] == '(':
            depth += 1
        elif tokens[i] == ')':
            depth -= 1
            if depth == 0:
                return i
    return -1


def extract_tables_from_tokens(tokens):
    tables = set()
    i = 0
    while i < len(tokens):
        tok = tokens[i].lower()

        if tok == 'from' and i + 1 < len(tokens):
            j = i + 1
            while j < len(tokens):
                t = tokens[j].lower()
                if t == '(':
                    end = find_matching_paren(tokens, j)
                    if end > 0:
                        sub_tokens = tokens[j + 1:end]
                        tables.update(extract_tables_from_tokens(sub_tokens))
                    j = end + 1 if end > 0 else j + 1
                    continue
                if t in ('where', 'group', 'order', 'having', 'limit', 'on', 'join',
                         'left', 'right', 'inner', 'cross', 'full', 'union', 'intersect',
                         'except', 'set'):
                    break
                if t not in ('select', 'as', 'and', 'or', 'not', 'in', 'exists',
                             'case', 'when', 'then', 'else', 'end', 'between',
                             'like', 'is', 'null', 'true', 'false', 'distinct'):
                    if j + 1 < len(tokens) and tokens[j + 1].lower() == 'as':
                        tables.add(t)
                        j += 2
                        continue
                    if not t.isdigit():
                        tables.add(t)
                j += 1
            i = j
            continue

        join_prefixes = ('left', 'right', 'inner', 'cross', 'full')
        if tok in join_prefixes and i + 1 < len(tokens) and tokens[i + 1].lower() == 'join':
            i += 2
        elif tok == 'join':
            i += 1
        else:
            i += 1
            continue

        if i < len(tokens):
            j = i
            while j < len(tokens):
                t = tokens[j].lower()
                if t in ('on', 'where', 'group', 'order', 'having', 'limit',
                         'join', 'left', 'right', 'inner', 'cross', 'full',
                         'union', 'set'):
                    break
                if t not in ('select', 'as', 'and', 'or', 'not', 'in', 'exists',
                             'case', 'when', 'then', 'else', 'end', 'between',
                             'like', 'is', 'null', 'true', 'false', 'distinct'):
                    if not t.isdigit():
                        tables.add(t)
                j += 1
            i = j
            continue

    return tables


def extract_sql_components(sql):
    sql_norm = normalize_sql(sql)
    components = {}

    tokens = tokenize_sql(sql_norm)
    components['tables'] = extract_tables_from_tokens(tokens)

    select_match = re.search(r'select\s+(.*?)\s+from', sql_norm, re.DOTALL)
    if select_match:
        select_clause = select_match.group(1)
        select_set = set()
        agg_pattern = r'(count|sum|avg|max|min)\s*\(\s*(?:distinct\s+)?([^)]+)\)'
        for func, col in re.findall(agg_pattern, select_clause):
            select_set.add((func, col.strip()))
        case_pattern = r'case\s+when\b'
        if re.search(case_pattern, select_clause):
            select_set.add(('case_when', 'true'))
        if re.search(r'\bdistinct\b', select_clause):
            select_set.add(('distinct', 'true'))
        components['select'] = select_set

    join_pattern = r'((?:left|right|inner|cross|full)\s+)?join\s+(\w+)\s+on\s+([^\s]+)\s*=\s*([^\s]+)'
    joins = re.findall(join_pattern, sql_norm)
    if joins:
        join_set = set()
        for jtype, table, left, right in joins:
            jtype_clean = jtype.strip() if jtype.strip() else 'inner'
            join_set.add((jtype_clean, table, f"{left}={right}"))
        components['joins'] = join_set

    where_match = re.search(r'where\s+(.*?)(?:group|order|having|limit|$)', sql_norm, re.DOTALL)
    if where_match:
        where_clause = where_match.group(1).strip()
        conditions = re.split(r'\s+and\s+', where_clause)
        where_set = set()
        for c in conditions:
            c = c.strip()
            if c:
                if re.search(r'\bexists\s*\(', c):
                    where_set.add(('exists', 'true'))
                elif re.search(r'\bnot\s+exists\s*\(', c):
                    where_set.add(('not_exists', 'true'))
                else:
                    where_set.add(('condition', c))
        components['where'] = where_set

    group_match = re.search(r'group\s+by\s+(.*?)(?:order|having|limit|$)', sql_norm, re.DOTALL)
    if group_match:
        group_clause = group_match.group(1).strip()
        if re.search(r'\bcase\s+when\b', group_clause):
            components['group_by'] = {('case_when', 'true')}
        else:
            cols = [c.strip() for c in group_clause.split(',')]
            components['group_by'] = set(cols)

    order_match = re.search(r'order\s+by\s+(.*?)(?:limit|$)', sql_norm, re.DOTALL)
    if order_match:
        order_clause = order_match.group(1).strip()
        order_parts = re.findall(r'(\w+(?:\.\w+)?)\s*(asc|desc)?', order_clause)
        components['order_by'] = set((col, dir if dir else 'asc') for col, dir in order_parts if col)

    having_match = re.search(r'having\s+(.*?)(?:order|limit|$)', sql_norm, re.DOTALL)
    if having_match:
        components['having'] = {having_match.group(1).strip()}

    limit_match = re.search(r'limit\s+(\d+)', sql_norm)
    if limit_match:
        components['limit'] = int(limit_match.group(1))

    if re.search(r'\bcase\s+when\b', sql_norm):
        components['has_case_when'] = True

    if re.search(r'\bnot\s+exists\b', sql_norm):
        components['has_not_exists'] = True

    if re.search(r'\bexists\b', sql_norm) and not re.search(r'\bnot\s+exists\b', sql_norm):
        components['has_exists'] = True

    return components


def calculate_exact_set_match(expected_sql, actual_sql):
    exp_comp = extract_sql_components(expected_sql)
    act_comp = extract_sql_components(actual_sql)

    component_scores = {}

    for comp_name in ['select', 'where', 'group_by', 'order_by', 'having', 'tables', 'joins']:
        exp_set = exp_comp.get(comp_name, set())
        act_set = act_comp.get(comp_name, set())

        if not exp_set and not act_set:
            component_scores[comp_name] = 1.0
        elif not exp_set or not act_set:
            if comp_name == 'tables':
                component_scores[comp_name] = 0.0
            elif not exp_set and act_set:
                component_scores[comp_name] = 0.8
            else:
                component_scores[comp_name] = 0.0
        else:
            intersection = exp_set & act_set
            union = exp_set | act_set
            component_scores[comp_name] = len(intersection) / len(union) if union else 1.0

    for special_key in ['has_case_when', 'has_not_exists', 'has_exists']:
        if exp_comp.get(special_key) and not act_comp.get(special_key):
            component_scores[special_key] = 0.0
        else:
            component_scores[special_key] = 1.0

    if 'limit' in exp_comp and 'limit' in act_comp:
        component_scores['limit'] = 1.0 if exp_comp['limit'] == act_comp['limit'] else 0.5
    elif 'limit' not in exp_comp and 'limit' not in act_comp:
        component_scores['limit'] = 1.0
    else:
        component_scores['limit'] = 0.0

    scoring_keys = ['select', 'where', 'group_by', 'order_by', 'having', 'tables', 'joins',
                    'limit', 'has_case_when', 'has_not_exists', 'has_exists']
    valid_scores = [component_scores[k] for k in scoring_keys if k in component_scores]
    overall_score = sum(valid_scores) / len(valid_scores) if valid_scores else 0.0

    return overall_score, component_scores


def check_table_coverage(expected_tables, actual_sql):
    actual_lower = normalize_sql(actual_sql)
    actual_components = extract_sql_components(actual_sql)
    actual_tables = actual_components.get('tables', set())

    missing = []
    for t in expected_tables:
        t_lower = t.lower().strip()
        found = False
        for at in actual_tables:
            if t_lower == at.lower().strip():
                found = True
                break
        if not found:
            pattern = r'\b' + re.escape(t_lower) + r'\b'
            if re.search(pattern, actual_lower):
                found = True
        if not found:
            missing.append(t)

    return missing


def generate_llm_friendly_reason(score, detailed_scores, strengths, issues):
    reason_parts = []

    if score == 5:
        reason_parts.append("SQL完全正确")
    elif score == 4:
        reason_parts.append("SQL基本正确，有小瑕疵")
    elif score == 3:
        reason_parts.append("SQL可用但存在明显问题")
    elif score == 2:
        reason_parts.append("SQL有严重错误")
    else:
        reason_parts.append("SQL完全不可用")

    ex_score = detailed_scores['execution_accuracy']
    esm_score = detailed_scores['exact_set_match']
    tc_score = detailed_scores['table_coverage']
    fc_score = detailed_scores['function_coverage']

    if ex_score == 1.0:
        reason_parts.append("SQL可正常执行")
    elif ex_score == 0.0:
        reason_parts.append("SQL执行失败")

    if esm_score >= 0.9:
        reason_parts.append("结构与预期高度一致")
    elif esm_score >= 0.6:
        reason_parts.append(f"结构部分匹配({esm_score:.0%})")
    else:
        reason_parts.append(f"结构与预期差异大({esm_score:.0%})")

    if tc_score < 1.0:
        reason_parts.append("缺少必要的表")
    if fc_score < 1.0:
        reason_parts.append("缺少关键的SQL函数/子句")

    if strengths:
        for s in strengths[:2]:
            reason_parts.append(s)

    if issues:
        for i in issues[:3]:
            reason_parts.append(i)

    return " | ".join(reason_parts)


def score_sql_generation(tc, actual_sql, execution_results=None):
    scores = {}
    issues = []
    strengths = []

    missing_tables = check_table_coverage(tc['tables'], actual_sql)
    table_score = 1.0 if not missing_tables else max(0, 1.0 - len(missing_tables) * 0.5)
    scores['table_coverage'] = table_score

    if missing_tables:
        issues.append(f"缺少表: {', '.join(missing_tables)}")
    else:
        strengths.append("表覆盖完整")

    actual_lower = normalize_sql(actual_sql)
    func_checks = {
        'JOIN': r'\bjoin\b',
        'LEFT JOIN': r'\bleft\s+join\b',
        'RIGHT JOIN': r'\bright\s+join\b',
        'COUNT': r'\bcount\s*\(',
        'SUM': r'\bsum\s*\(',
        'AVG': r'\bavg\s*\(',
        'MAX': r'\bmax\s*\(',
        'MIN': r'\bmin\s*\(',
        'GROUP BY': r'\bgroup\s+by\b',
        'WHERE': r'\bwhere\b',
        'ORDER BY': r'\border\s+by\b',
        'HAVING': r'\bhaving\b',
        'DISTINCT': r'\bdistinct\b',
        'LIMIT': r'\blimit\b',
        'CASE': r'\bcase\s+when\b',
        'EXISTS': r'\bexists\s*\(',
        'NOT EXISTS': r'\bnot\s+exists\s*\(',
        'BETWEEN': r'\bbetween\b',
        'IN': r'\bin\s*\(',
        'LIKE': r'\blike\b',
        'ROUND': r'\bround\s*\(',
        'DATE_FORMAT': r'\bdate_format\s*\(',
        'DATE_SUB': r'\bdate_sub\s*\(',
        'YEAR': r'\byear\s*\(',
        'MONTH': r'\bmonth\s*\(',
        'DATE': r'\bdate\s*\(',
        'NULLIF': r'\bnullif\s*\(',
    }

    missing_funcs = []
    matched_funcs = []
    for f in tc['functions']:
        f_upper = f.upper().strip()
        if f_upper in func_checks:
            if re.search(func_checks[f_upper], actual_lower):
                matched_funcs.append(f)
            else:
                missing_funcs.append(f)

    func_score = 1.0 if not missing_funcs else max(0, 1.0 - len(missing_funcs) * 0.3)
    scores['function_coverage'] = func_score

    if missing_funcs:
        issues.append(f"缺少函数: {', '.join(missing_funcs)}")
    if matched_funcs:
        strengths.append(f"正确使用: {', '.join(matched_funcs)}")

    exact_set_score, component_details = calculate_exact_set_match(tc['expected_sql'], actual_sql)
    scores['exact_set_match'] = exact_set_score

    if execution_results is not None:
        exec_success, exec_data = execution_results
        if exec_success:
            expected_exec = execute_sql(tc['expected_sql'], tc.get('datasource_id', 1))
            if expected_exec[0]:
                exec_score, match_detail = compare_result_sets(expected_exec[1], exec_data)
                if exec_score >= 0.9:
                    strengths.append(f"结果集匹配({match_detail})")
                elif exec_score >= 0.5:
                    issues.append(f"结果集部分匹配({match_detail})")
                else:
                    issues.append(f"结果集不匹配({match_detail})")
            else:
                exec_score = 0.7
                strengths.append("SQL可执行(预期SQL执行失败,无法对比结果)")
        else:
            exec_score = 0.0
            issues.append(f"SQL执行失败: {str(exec_data)[:80]}")
    else:
        exec_score = 0.0
        issues.append("未执行验证")
    scores['execution_accuracy'] = exec_score

    weighted_score = (
        scores['execution_accuracy'] * 0.4 +
        scores['exact_set_match'] * 0.3 +
        scores['table_coverage'] * 0.2 +
        scores['function_coverage'] * 0.1
    )

    final_score = max(1, min(5, round(weighted_score * 5)))

    error_categories = []
    if scores['execution_accuracy'] == 0.0:
        error_categories.append('EXEC_FAIL')
    elif scores['execution_accuracy'] < 0.9:
        error_categories.append('RESULT_MISMATCH')
    if scores['table_coverage'] < 1.0:
        error_categories.append('MISSING_TABLE')
    if scores['function_coverage'] < 1.0:
        error_categories.append('MISSING_FUNC')
    if scores['exact_set_match'] < 0.6:
        error_categories.append('STRUCT_MISMATCH')
    if not error_categories:
        error_categories.append('OK')

    detailed_reason = generate_llm_friendly_reason(final_score, scores, strengths, issues)

    return final_score, scores, detailed_reason, error_categories


def select_test_cases(all_cases, round_num, wrong_questions, round_history):
    if round_num == 1:
        return list(all_cases)

    selected = []

    if wrong_questions:
        wrong_sample_size = min(len(wrong_questions), max(10, len(all_cases) // 3))
        wrong_sample = random.sample(wrong_questions, min(wrong_sample_size, len(wrong_questions)))
        selected.extend(wrong_sample)

    remaining_slots = max(0, len(all_cases) - len(selected))
    if remaining_slots > 0:
        easy_cases = [tc for tc in all_cases if tc['difficulty'] == 'EASY']
        medium_cases = [tc for tc in all_cases if tc['difficulty'] == 'MEDIUM']
        hard_cases = [tc for tc in all_cases if tc['difficulty'] == 'HARD']

        easy_count = max(1, int(remaining_slots * 0.2))
        medium_count = max(1, int(remaining_slots * 0.5))
        hard_count = max(1, int(remaining_slots * 0.3))

        selected.extend(random.sample(easy_cases, min(easy_count, len(easy_cases))))
        selected.extend(random.sample(medium_cases, min(medium_count, len(medium_cases))))
        selected.extend(random.sample(hard_cases, min(hard_count, len(hard_cases))))

    seen = set()
    unique = []
    for tc in selected:
        key = tc['question']
        if key not in seen:
            seen.add(key)
            unique.append(tc)

    return unique


def run_single_test(tc, round_num):
    try:
        resp = requests.post(
            API_URL,
            json={'message': tc['question'], 'datasourceId': tc.get('datasource_id', 1)},
            headers={'Authorization': f'Bearer {TOKEN}'},
            timeout=REQUEST_TIMEOUT
        )

        if resp.status_code != 200:
            return {
                'question': tc['question'],
                'expected_sql': tc['expected_sql'],
                'actual_sql': '',
                'score': 1,
                'difficulty': tc['difficulty'],
                'reason': f'HTTP {resp.status_code}',
                'details': '',
                'error_categories': ['HTTP_ERROR']
            }

        result = resp.json()
        if result.get('code') != 200 or not result.get('data', {}).get('sql'):
            return {
                'question': tc['question'],
                'expected_sql': tc['expected_sql'],
                'actual_sql': '',
                'score': 1,
                'difficulty': tc['difficulty'],
                'reason': 'No SQL generated',
                'details': '',
                'error_categories': ['NO_SQL']
            }

        actual_sql = result['data']['sql']

        execution_results = None
        try:
            execution_results = execute_sql(actual_sql, tc.get('datasource_id', 1))
        except Exception as e:
            execution_results = (False, f"Exception: {str(e)[:100]}")

        score, detailed_scores, detailed_reason, error_categories = score_sql_generation(tc, actual_sql, execution_results)

        feedback_details = (f"EX:{detailed_scores['execution_accuracy']:.2f} "
                           f"ESM:{detailed_scores['exact_set_match']:.2f} "
                           f"TC:{detailed_scores['table_coverage']:.2f} "
                           f"FC:{detailed_scores['function_coverage']:.2f}")
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
        except Exception as e:
            print(f"  [Feedback] submit failed: {e}")

        return {
            'question': tc['question'],
            'expected_sql': tc['expected_sql'],
            'actual_sql': actual_sql,
            'score': score,
            'difficulty': tc['difficulty'],
            'reason': detailed_reason,
            'details': feedback_details,
            'error_categories': error_categories
        }

    except Exception as e:
        return {
            'question': tc['question'],
            'expected_sql': tc['expected_sql'],
            'actual_sql': '',
            'score': 1,
            'difficulty': tc['difficulty'],
            'reason': f'Exception: {str(e)[:100]}',
            'details': '',
            'error_categories': ['EXCEPTION']
        }


all_results = []
wrong_questions = []
best_avg_score = 0.0
no_improve_count = 0

for round_num in range(1, MAX_ROUNDS + 1):
    print(f"\n{'='*60}")
    print(f"Round {round_num}/{MAX_ROUNDS}")
    print(f"  wrong_questions pool: {len(wrong_questions)}")
    print('='*60)

    selected = select_test_cases(test_cases, round_num, wrong_questions, all_results)

    if round_num == 1:
        print(f"  Full test: {len(selected)} cases")
    else:
        wrong_in_selected = sum(1 for tc in selected if tc['question'] in {wq['question'] for wq in wrong_questions})
        print(f"  Selected: {len(selected)} cases (wrong-question retry: {wrong_in_selected})")

    round_results = []

    with ThreadPoolExecutor(max_workers=MAX_WORKERS) as executor:
        future_to_idx = {executor.submit(run_single_test, tc, round_num): idx
                        for idx, tc in enumerate(selected)}

        for future in as_completed(future_to_idx):
            idx = future_to_idx[future]
            try:
                result = future.result()
                round_results.append(result)

                score = result['score']
                icon = "⭐" * score
                diff_tag = f"[{result['difficulty']}]"
                print(f"  [{idx+1}/{len(selected)}] {icon} {diff_tag} {result['question'][:30]}... = {score}/5")
            except Exception as e:
                print(f"  [{idx+1}/{len(selected)}] FAILED: {e}")

    if not round_results:
        print(f"  No results in round {round_num}, skipping")
        continue

    avg_score = sum(r['score'] for r in round_results) / len(round_results)
    pass_count = sum(1 for r in round_results if r['score'] >= PASS_SCORE)
    pass_rate = pass_count / len(round_results) * 100

    diff_stats = defaultdict(lambda: {'total': 0, 'pass': 0, 'scores': []})
    for r in round_results:
        d = r['difficulty']
        diff_stats[d]['total'] += 1
        diff_stats[d]['scores'].append(r['score'])
        if r['score'] >= PASS_SCORE:
            diff_stats[d]['pass'] += 1

    print(f"\n  Round {round_num} Summary:")
    print(f"    Avg Score: {avg_score:.2f}/5.0")
    print(f"    Pass Rate (>=4): {pass_rate:.1f}% ({pass_count}/{len(round_results)})")
    for d in ['EASY', 'MEDIUM', 'HARD']:
        if d in diff_stats:
            ds = diff_stats[d]
            d_avg = sum(ds['scores']) / len(ds['scores'])
            d_pass = ds['pass'] / ds['total'] * 100 if ds['total'] > 0 else 0
            print(f"    {d}: avg={d_avg:.2f}, pass={d_pass:.0f}% ({ds['pass']}/{ds['total']})")

    failed_questions = {r['question'] for r in round_results if r['score'] < PASS_SCORE}
    new_wrong = [tc for tc in selected if tc['question'] in failed_questions]
    wrong_map = {wq['question']: wq for wq in wrong_questions}
    for wq in new_wrong:
        wrong_map[wq['question']] = wq
    wrong_questions = list(wrong_map.values())

    report = f"# NL2SQL Training - Round {round_num}\n\n"
    report += f"**Date**: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n"
    report += f"**Test Cases**: {len(round_results)}\n"
    report += f"**Average Score**: {avg_score:.2f} / 5.0\n"
    report += f"**Pass Rate (>=4)**: {pass_rate:.1f}%\n\n"

    report += "## Difficulty Breakdown\n\n"
    report += "| Difficulty | Count | Avg Score | Pass Rate |\n"
    report += "|------------|-------|-----------|----------|\n"
    for d in ['EASY', 'MEDIUM', 'HARD']:
        if d in diff_stats:
            ds = diff_stats[d]
            d_avg = sum(ds['scores']) / len(ds['scores'])
            d_pass = ds['pass'] / ds['total'] * 100 if ds['total'] > 0 else 0
            report += f"| {d} | {ds['total']} | {d_avg:.2f} | {d_pass:.0f}% |\n"
    report += "\n"

    error_cat_counts = defaultdict(int)
    for r in round_results:
        for ec in r.get('error_categories', []):
            error_cat_counts[ec] += 1
    if error_cat_counts:
        report += "## Error Categories\n\n"
        report += "| Category | Count | Description |\n"
        report += "|----------|-------|-------------|\n"
        cat_desc = {
            'OK': 'All good',
            'EXEC_FAIL': 'SQL execution failed',
            'RESULT_MISMATCH': 'Result set mismatch',
            'MISSING_TABLE': 'Missing required table',
            'MISSING_FUNC': 'Missing required function',
            'STRUCT_MISMATCH': 'SQL structure mismatch',
            'HTTP_ERROR': 'API HTTP error',
            'NO_SQL': 'No SQL generated',
            'EXCEPTION': 'Runtime exception'
        }
        for ec, cnt in sorted(error_cat_counts.items(), key=lambda x: -x[1]):
            report += f"| {ec} | {cnt} | {cat_desc.get(ec, '')} |\n"
        report += "\n"

    worst_cases = sorted(round_results, key=lambda r: r['score'])[:10]
    if worst_cases:
        report += "## Worst 10 Cases\n\n"
        report += "| # | Score | Difficulty | Question | Error |\n"
        report += "|---|-------|------------|----------|-------|\n"
        for j, r in enumerate(worst_cases, 1):
            cats = ', '.join(r.get('error_categories', []))
            report += f"| {j} | {r['score']}/5 | {r['difficulty']} | {r['question'][:40]} | {cats} |\n"
        report += "\n"

    report += "## Detailed Results\n\n"
    report += "| # | Difficulty | Question | Score | Error | Reason |\n"
    report += "|---|------------|----------|-------|-------|--------|\n"
    for j, r in enumerate(round_results, 1):
        reason_short = r.get('reason', 'N/A')[:60]
        cats = ', '.join(r.get('error_categories', []))
        report += f"| {j} | {r['difficulty']} | {r['question'][:40]} | {r['score']}/5 | {cats} | {reason_short} |\n"

    with open(f'training_round_{round_num}.md', 'w', encoding='utf-8') as f:
        f.write(report)

    all_results.append({
        'round': round_num,
        'avg_score': avg_score,
        'pass_rate': pass_rate,
        'total': len(round_results),
        'diff_stats': {d: {'total': ds['total'], 'pass': ds['pass'],
                           'avg': sum(ds['scores']) / len(ds['scores'])}
                      for d, ds in diff_stats.items()},
        'error_categories': dict(error_cat_counts)
    })

    if avg_score > best_avg_score:
        best_avg_score = avg_score
        no_improve_count = 0
        print(f"  New best: {best_avg_score:.2f}")
    else:
        no_improve_count += 1
        print(f"  No improvement ({no_improve_count}/{EARLY_STOP_PATIENCE})")

    if no_improve_count >= EARLY_STOP_PATIENCE:
        print(f"\n  Early stopping: no improvement for {EARLY_STOP_PATIENCE} rounds")
        break

close_all_connections()

print(f"\n{'='*60}")
print("FINAL SUMMARY")
print('='*60)

if all_results:
    final = all_results[-1]
    best = max(all_results, key=lambda x: x['avg_score'])

    print(f"\n  Total Rounds: {len(all_results)}")
    print(f"  Best Round: {best['round']} (avg={best['avg_score']:.2f})")
    print(f"  Final Round: {final['round']} (avg={final['avg_score']:.2f})")

    print(f"\n  Score Trend:")
    for r in all_results:
        bar = "█" * int(r['avg_score'] * 4)
        print(f"    Round {r['round']}: {r['avg_score']:.2f} {bar}")

    print(f"\n  Difficulty Breakdown (Final Round):")
    for d in ['EASY', 'MEDIUM', 'HARD']:
        if d in final['diff_stats']:
            ds = final['diff_stats'][d]
            print(f"    {d}: avg={ds['avg']:.2f}, pass={ds['pass']}/{ds['total']}")

    summary_report = "# NL2SQL Training - Final Summary\n\n"
    summary_report += f"**Date**: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n"
    summary_report += f"**Total Rounds**: {len(all_results)}\n"
    summary_report += f"**Best Round**: {best['round']} (avg={best['avg_score']:.2f})\n"
    summary_report += f"**Final Round**: {final['round']} (avg={final['avg_score']:.2f})\n\n"

    summary_report += "## Score Trend\n\n"
    summary_report += "| Round | Avg Score | Pass Rate | EASY | MEDIUM | HARD |\n"
    summary_report += "|-------|-----------|----------|------|--------|------|\n"
    for r in all_results:
        easy_str = f"{r['diff_stats'].get('EASY', {}).get('avg', 0):.1f}" if 'EASY' in r['diff_stats'] else "-"
        medium_str = f"{r['diff_stats'].get('MEDIUM', {}).get('avg', 0):.1f}" if 'MEDIUM' in r['diff_stats'] else "-"
        hard_str = f"{r['diff_stats'].get('HARD', {}).get('avg', 0):.1f}" if 'HARD' in r['diff_stats'] else "-"
        summary_report += f"| {r['round']} | {r['avg_score']:.2f} | {r['pass_rate']:.0f}% | {easy_str} | {medium_str} | {hard_str} |\n"
    summary_report += "\n"

    all_error_cats = set()
    for r in all_results:
        all_error_cats.update(r.get('error_categories', {}).keys())
    if all_error_cats:
        summary_report += "## Error Category Trend\n\n"
        sorted_cats = sorted(all_error_cats)
        header = "| Round | " + " | ".join(sorted_cats) + " |\n"
        sep = "|-------" + "|-------" * len(sorted_cats) + "|\n"
        summary_report += header + sep
        for r in all_results:
            ec = r.get('error_categories', {})
            vals = [str(ec.get(c, 0)) for c in sorted_cats]
            summary_report += f"| {r['round']} | " + " | ".join(vals) + " |\n"
        summary_report += "\n"

    if wrong_questions:
        summary_report += "## Persistent Wrong Questions\n\n"
        summary_report += f"**Total**: {len(wrong_questions)}\n\n"
        diff_wq = defaultdict(list)
        for wq in wrong_questions:
            diff_wq[wq['difficulty']].append(wq)
        for d in ['EASY', 'MEDIUM', 'HARD']:
            if diff_wq[d]:
                summary_report += f"### {d} ({len(diff_wq[d])})\n\n"
                for wq in diff_wq[d]:
                    summary_report += f"- {wq['question']}\n"
                summary_report += "\n"

    with open('training_summary.md', 'w', encoding='utf-8') as f:
        f.write(summary_report)

    print(f"\n  Reports saved: training_round_*.md, training_summary.md")
else:
    print("  No results collected")

print('='*60)
